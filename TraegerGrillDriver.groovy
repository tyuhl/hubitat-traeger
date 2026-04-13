/**
 * Traeger WiFire Grill Driver for Hubitat Elevation
 * Version: 1.4.1
 *
 * Uses interfaces.webSocket with manual MQTT framing (same approach as Mysa MQTT driver)
 * because Hubitat's interfaces.mqtt cannot connect to AWS IoT WSS pre-signed URLs.
 *
 * Architecture:
 *  - WebSocket connects to Traeger's pre-signed WSS URL (from parent app)
 *  - MQTT CONNECT/SUBSCRIBE/PINGREQ implemented manually as byte packets
 *  - Incoming PUBLISH packets parsed for grill state
 *  - Commands sent via REST POST through parent app (not MQTT)
 *
 * Change log:
 *  1.4.1 - Quiet down reconnect/close logs (info/warn → debug after first
 *          attempt), harden backoff reset against stale connectedAt state
 *  1.3.0 - Add session/cumulative active time tracking with reset command
 *          (thanks @tyuhl), restrict supportedThermostatModes to heat/off,
 *          fix pellet-low button firing on every poll instead of falling edge
 *  1.2.0 - Demote connection step messages to debug, fix exponential backoff
 *          being defeated by retained MQTT messages resetting attempt counter
 *  1.1.0 - Exponential backoff on reconnect, adaptive log levels, fix null
 *          cast and Elvis operator issues
 *  1.0.0 - Initial release
 */

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

metadata {
    definition(name: "Traeger Grill", namespace: "craigde", author: "Craig Dewar") {
        capability "TemperatureMeasurement"
        capability "Thermostat"
        capability "Switch"
        capability "Refresh"
        capability "Sensor"
        capability "Actuator"
        capability "Initialize"

        attribute "grillTemperature",    "number"
        attribute "grillSetTemperature", "number"
        attribute "ambientTemperature",  "number"   // controller electronics temp, not outdoor air
        attribute "probeTemperature",    "number"
        attribute "probeSetTemperature", "number"
        attribute "probeState",          "string"
        attribute "grillState",          "string"
        attribute "grillStateCode",      "number"
        attribute "heatingState",        "string"
        attribute "connected",           "string"
        attribute "temperatureUnit",     "string"
        attribute "superSmoke",          "string"
        attribute "keepWarm",            "string"
        attribute "timerActive",         "string"
        attribute "timerRemaining",      "string"
        attribute "pelletLevel",         "number"   // percent 0-100
        attribute "probeAlarmFired",     "string"   // true/false
        attribute "cookTimerComplete",   "string"   // true/false
        attribute "errors",              "number"
        attribute "mqttStatus",          "string"
        attribute "lastUpdate",          "string"
        attribute "grillSessionTime",    "string"   // HH:MM:SS — current session duration (resets each cook)
        attribute "grillTotalActiveTime","string"   // HH:MM:SS — lifetime cumulative active time

        capability "PushableButton"
        // Button map:
        // 1 = Preheat complete (grill reached set temp)
        // 2 = Probe at target temp (probe_alarm_fired)
        // 3 = Pellets low (< 20%)
        // 4 = Grill offline or error
        // 5 = Cook timer complete

        command "setGrillTemperature",  [[name: "Temperature*", type: "NUMBER"]]
        command "setProbeTemperature",  [[name: "Temperature*", type: "NUMBER"]]
        command "shutdownGrill"
        command "setSuperSmoke",        [[name: "Enabled*", type: "ENUM", constraints: ["on","off"]]]
        command "setKeepWarm",          [[name: "Enabled*", type: "ENUM", constraints: ["on","off"]]]
        command "setTimerMinutes",      [[name: "Minutes*", type: "NUMBER"]]
        command "cancelTimer"
        command "requestStateUpdate"
        command "connectMqtt"
        command "disconnectMqtt"
        command "initializeMqtt"
        command "resetGrillActiveTime"
    }

    preferences {
        input "enableDebug", "bool", title: "Enable debug logging", defaultValue: false
    }
}

// ─── Lifecycle ────────────────────────────────────────────────────────────────

def installed() {
    log.info "[Traeger:${device.label}] Installed"
    sendEvent(name: "mqttStatus", value: "disconnected")
    sendEvent(name: "numberOfButtons", value: 5)
    sendEvent(name: "grillSessionTime", value: "00:00:00")
    sendEvent(name: "grillTotalActiveTime", value: "00:00:00")
    runIn(3, "initialize")
}

def updated() {
    log.info "[Traeger:${device.label}] Updated"
    initialize()
}

def initialize() {
    logDebug "initialize()"
    // suppress unsupported modes so they aren't shown in thermostat tile
    sendEvent(name: "supportedThermostatModes", value: ["heat", "off"])
    state.reconnectAttempt = 0
    disconnectMqtt()
    runIn(5, "connectMqtt")
}

def uninstalled() {
    disconnectMqtt()
}

// ─── WebSocket / MQTT Connection ──────────────────────────────────────────────

def initializeMqtt() { connectMqtt() }  // alias for backwards compatibility

def connectMqtt() {
    logDebug "connectMqtt()"
    disconnectMqtt()

    def url = parent?.getSignedMqttUrl()
    if (!url) {
        log.error "[Traeger:${device.label}] Could not get signed MQTT URL from parent app"
        sendEvent(name: "mqttStatus", value: "error: no URL")
        scheduleReconnect()
        return
    }

    logDebug "Connecting WebSocket to: ${url.take(80)}…"
    try {
        interfaces.webSocket.connect(
            url,
            byteInterface: true,
            pingInterval:  55,
            headers: ["Sec-WebSocket-Protocol": "mqtt"]
        )
        sendEvent(name: "mqttStatus", value: "connecting")
    } catch (Exception e) {
        log.error "[Traeger:${device.label}] WebSocket connect failed (${e.class.simpleName}): ${e.message}"
        sendEvent(name: "mqttStatus", value: "error: ${e.message}")
        scheduleReconnect()
    }
}

private void scheduleReconnect() {
    def attempt = (state.reconnectAttempt ?: 0) + 1
    state.reconnectAttempt = attempt
    // Exponential backoff: 30s, 60s, 120s, 240s, capped at 300s (5 min)
    def delay = Math.min(30 * Math.pow(2, attempt - 1) as int, 300)
    if (attempt == 1) {
        log.info "[Traeger:${device.label}] Reconnect attempt ${attempt} in ${delay}s"
    } else {
        logDebug "Reconnect attempt ${attempt} in ${delay}s"
    }
    runIn(delay, "connectMqtt")
}

def disconnectMqtt() {
    unschedule("connectMqtt")
    unschedule("sendMqttPing")
    try { interfaces.webSocket.close() } catch (Exception e) { /* ignore */ }
    state.wsConnected   = false
    state.mqttConnected = false
    state.connectedAt   = 0
    state.packetId      = 0
    sendEvent(name: "mqttStatus", value: "disconnected")
}

// ─── WebSocket Callbacks ──────────────────────────────────────────────────────

def webSocketStatus(String status) {
    logDebug "webSocketStatus: ${status}"
    if (status.startsWith("status: open")) {
        logDebug "WebSocket open — sending MQTT CONNECT"
        state.wsConnected = true
        sendEvent(name: "mqttStatus", value: "ws-open")
        sendMqttConnect()
    } else if (status.startsWith("failure:") || status.startsWith("status: closing")) {
        def attempt = state.reconnectAttempt ?: 0
        if (attempt == 0) {
            log.warn "[Traeger:${device.label}] WebSocket closed: ${status}"
        } else {
            logDebug "WebSocket closed: ${status}"
        }
        state.wsConnected   = false
        state.mqttConnected = false
        state.connectedAt   = 0
        unschedule("sendMqttPing")
        sendEvent(name: "mqttStatus", value: "disconnected")
        scheduleReconnect()
    }
}

def parse(String hexMessage) {
    try {
        byte[] data = hubitat.helper.HexUtils.hexStringToByteArray(hexMessage)
        parseMqttPacket(data)
    } catch (Exception e) {
        log.error "[Traeger:${device.label}] parse error: ${e.message}"
    }
}

// ─── MQTT Packet Handling ─────────────────────────────────────────────────────

private void parseMqttPacket(byte[] data) {
    if (!data || data.length < 2) return
    int packetType = (data[0] & 0xF0) >> 4
    switch (packetType) {
        case 2:  handleConnack(data); break
        case 3:  handlePublish(data); break
        case 4:  logDebug "PUBACK received";  break
        case 9:  handleSuback(data);  break
        case 13: logDebug "PINGRESP received"; break
        default: logDebug "Unknown MQTT packet type: ${packetType}"
    }
}

private void handleConnack(byte[] data) {
    if (data.length < 4) return
    int returnCode = data[3] & 0xFF
    if (returnCode == 0) {
        logDebug "MQTT CONNACK accepted — subscribing"
        state.mqttConnected = true
        state.connectedAt = now()
        sendEvent(name: "mqttStatus", value: "connected")
        subscribeToGrill()
        runIn(2, "requestStateUpdate")
        // Ping every 50s (URL keepalive + MQTT keepalive)
        schedule("0/50 * * * * ?", "sendMqttPing")
    } else {
        log.warn "[Traeger:${device.label}] MQTT CONNACK refused, code=${returnCode}"
        sendEvent(name: "mqttStatus", value: "refused: ${returnCode}")
        scheduleReconnect()
    }
}

private void handleSuback(byte[] data) {
    logDebug "SUBACK received — subscribed OK"
}

private void handlePublish(byte[] data) {
    try {
        int offset = 1
        // Decode remaining length
        int remainingLength = 0
        int multiplier = 1
        int encodedByte = 0x80
        while ((encodedByte & 0x80) != 0) {
            encodedByte = data[offset++] & 0xFF
            remainingLength += (encodedByte & 0x7F) * multiplier
            multiplier *= 128
        }

        int variableHeaderStart = offset
        // Topic
        int topicLen = ((data[offset++] & 0xFF) << 8) | (data[offset++] & 0xFF)
        String topic = new String(data, offset, topicLen, "UTF-8")
        offset += topicLen
        // Packet ID if QoS > 0
        int qos = (data[0] & 0x06) >> 1
        if (qos > 0) {
            int packetId = ((data[offset++] & 0xFF) << 8) | (data[offset++] & 0xFF)
            sendMqttPuback(packetId)
        }
        // Payload
        int payloadLen = remainingLength - (offset - variableHeaderStart)
        if (payloadLen <= 0) return
        String payload = new String(data, offset, payloadLen, "UTF-8")
        logDebug "PUBLISH topic=${topic} payload=${payload.take(200)}"
        def json = new JsonSlurper().parseText(payload)
        handleStatePayload(json)
    } catch (Exception e) {
        log.error "[Traeger:${device.label}] handlePublish error: ${e.message}"
    }
}

// ─── MQTT Packet Builders ─────────────────────────────────────────────────────

private void sendMqttConnect() {
    def clientId = "hubitat-traeger-${device.getDataValue('thingName')}-${now()}"
    ByteArrayOutputStream buf = new ByteArrayOutputStream()
    // Protocol name
    writeUTF(buf, "MQTT")
    buf.write(4)       // Protocol level 3.1.1
    buf.write(0x02)    // Connect flags: clean session
    buf.write(0); buf.write(60) // Keep-alive 60s
    // Payload
    writeUTF(buf, clientId)
    sendMqttFrame(0x10, buf.toByteArray())
    logDebug "Sent MQTT CONNECT clientId=${clientId}"
}

private void subscribeToGrill() {
    def thingName = device.getDataValue("thingName")
    def topic     = "prod/thing/update/${thingName}"
    state.packetId = (state.packetId ?: 0) + 1
    ByteArrayOutputStream buf = new ByteArrayOutputStream()
    buf.write((state.packetId >> 8) & 0xFF)
    buf.write(state.packetId & 0xFF)
    writeUTF(buf, topic)
    buf.write(1)  // QoS 1
    sendMqttFrame(0x82, buf.toByteArray())
    logDebug "Subscribed to ${topic}"
}

private void sendMqttPuback(int packetId) {
    sendMqttFrame(0x40, [(packetId >> 8) & 0xFF, packetId & 0xFF] as byte[])
}

def sendMqttPing() {
    if (state.wsConnected) {
        interfaces.webSocket.sendMessage("C000")
        logDebug "Sent PINGREQ"
    }
}

private void sendMqttFrame(int fixedHeader, byte[] payload) {
    ByteArrayOutputStream frame = new ByteArrayOutputStream()
    frame.write(fixedHeader)
    int len = payload.length
    while (true) {
        int b = len % 128
        len = len.intdiv(128)
        if (len > 0) b |= 0x80
        frame.write(b)
        if (len == 0) break
    }
    frame.write(payload)
    interfaces.webSocket.sendMessage(
        hubitat.helper.HexUtils.byteArrayToHexString(frame.toByteArray())
    )
}

private void writeUTF(ByteArrayOutputStream buf, String s) {
    byte[] b = s.getBytes("UTF-8")
    buf.write((b.length >> 8) & 0xFF)
    buf.write(b.length & 0xFF)
    buf.write(b)
}

// ─── Active Time Tracking ─────────────────────────────────────────────────────

// Active state codes: igniting(4), preheating(5), manual_cook(6), custom_cook(7)
// idle(3) is included because the grill is powered on and consuming pellets
@groovy.transform.Field static final List ACTIVE_STATE_CODES = [3, 4, 5, 6, 7]

/**
 * Called each time a state payload arrives with a valid system_status.
 * Starts the session clock on transition into an active state,
 * and flushes elapsed time to the cumulative total on transition out.
 */
private void updateActiveTime(int stateCode, String prevGrillState) {
    boolean nowActive  = stateCode in ACTIVE_STATE_CODES
    boolean wasActive  = prevGrillState in ["idle", "igniting", "preheating", "manual_cook", "custom_cook"]

    if (nowActive && !wasActive) {
        // Grill just turned on — start session clock
        state.sessionStartMs = now()
        log.info "[Traeger:${device.label}] Grill session started"
        sendEvent(name: "grillSessionTime", value: "00:00:00")
        // Tick the session display every minute while active
        runIn(60, "tickSessionTime")

    } else if (!nowActive && wasActive) {
        // Grill just turned off — flush session into cumulative total
        if (state.sessionStartMs) {
            long sessionMs  = now() - (state.sessionStartMs as Long)
            long totalMs    = (state.totalActiveMs ?: 0L) + sessionMs
            state.totalActiveMs  = totalMs
            state.sessionStartMs = null
            unschedule("tickSessionTime")
            def sessionStr = msToHMS(sessionMs)
            log.info "[Traeger:${device.label}] Grill session ended — session: ${sessionStr}, total: ${msToHMS(totalMs)}"
            sendEvent(name: "grillSessionTime",     value: "00:00:00")
            sendEvent(name: "grillTotalActiveTime", value: msToHMS(totalMs))
        }

    } else if (nowActive && state.sessionStartMs) {
        // Still active — refresh session display (called by tickSessionTime too)
        long sessionMs = now() - (state.sessionStartMs as Long)
        sendEvent(name: "grillSessionTime",     value: msToHMS(sessionMs))
        sendEvent(name: "grillTotalActiveTime", value: msToHMS((state.totalActiveMs ?: 0L) + sessionMs))
    }
}

/** Scheduled every 60s while grill is active to keep the dashboard ticking. */
def tickSessionTime() {
    if (state.sessionStartMs) {
        long sessionMs = now() - (state.sessionStartMs as Long)
        sendEvent(name: "grillSessionTime",     value: msToHMS(sessionMs))
        sendEvent(name: "grillTotalActiveTime", value: msToHMS((state.totalActiveMs ?: 0L) + sessionMs))
        runIn(60, "tickSessionTime")
    }
}

/** Reset cumulative total and current session. */
def resetGrillActiveTime() {
    log.info "[Traeger:${device.label}] Resetting grill active time"
    state.totalActiveMs  = 0L
    state.sessionStartMs = state.sessionStartMs ? now() : null  // keep session running if active
    sendEvent(name: "grillTotalActiveTime", value: "00:00:00")
    sendEvent(name: "grillSessionTime",     value: "00:00:00")
}

private String msToHMS(long ms) {
    long totalSec = ms / 1000L
    long h = totalSec / 3600
    long m = (totalSec % 3600) / 60
    long s = totalSec % 60
    return String.format("%02d:%02d:%02d", h, m, s)
}

// ─── State Parsing ────────────────────────────────────────────────────────────

private void handleStatePayload(Map payload) {
    logDebug "RAW payload keys: ${payload?.keySet()}"
    logDebug "RAW status: ${payload?.status}"
    if (payload?.acc) logDebug "RAW acc: ${payload.acc}"

    // Only reset reconnect backoff if MQTT is currently connected AND has
    // been stable for > 60s. The mqttConnected guard prevents stale/buffered
    // PUBLISHes from previous cycles defeating the exponential backoff.
    long connAt = (state.connectedAt ?: 0L) as long
    long connectedFor = (connAt > 0L) ? (now() - connAt) : 0L
    if (state.mqttConnected && connAt > 0L && connectedFor > 60000L) {
        if ((state.reconnectAttempt ?: 0) > 0) {
            log.info "[Traeger:${device.label}] MQTT connected — receiving grill data"
        }
        state.reconnectAttempt = 0
    }

    def s = payload?.status
    if (!s) { logDebug "No status block"; return }

    def unit = (s.units == 0) ? "C" : "F"
    sendEvent(name: "temperatureUnit", value: unit)

    if (s.grill     != null) { sendEvent(name:"grillTemperature",    value:s.grill,      unit:unit); sendEvent(name:"temperature", value:s.grill, unit:unit) }
    if (s.set       != null) { sendEvent(name:"grillSetTemperature", value:s.set,        unit:unit); sendEvent(name:"thermostatSetpoint", value:s.set, unit:unit); sendEvent(name:"heatingSetpoint", value:s.set, unit:unit); sendEvent(name:"coolingSetpoint", value:s.set, unit:unit) }
    if (s.ambient   != null) { sendEvent(name:"ambientTemperature",  value:s.ambient,    unit:unit) }
    if (s.probe     != null) { sendEvent(name:"probeTemperature",    value:s.probe,      unit:unit) }
    if (s.probe_set != null) { sendEvent(name:"probeSetTemperature", value:s.probe_set,  unit:unit) }

    def stateCode = s.system_status
    // Capture previous state before sendEvent updates currentValue (needed for edge detection)
    def prevGrillState = device.currentValue("grillState")
    if (stateCode != null) {
        def stateName = grillStateName(stateCode as int)
        sendEvent(name:"grillStateCode", value:stateCode)
        sendEvent(name:"grillState",     value:stateName)
        sendEvent(name:"switch",         value:(stateCode in [3,4,5,6,7]) ? "on" : "off")
        sendEvent(name:"thermostatMode", value:(stateCode in [3,4,5,6,7]) ? "heat" : "off")
        sendEvent(name:"thermostatOperatingState", value:thermostatOpState(stateCode as int, s))
        // Track cumulative and session active time
        updateActiveTime(stateCode as int, prevGrillState ?: "")
    }
    if (s.grill != null && s.set != null && stateCode != null) {
        sendEvent(name:"heatingState", value:heatingStateName(stateCode as int, s.grill as int, s.set as int))
    }
    if (s.smoke     != null) sendEvent(name:"superSmoke", value:s.smoke    ? "on" : "off")
    if (s.keepwarm  != null) sendEvent(name:"keepWarm",   value:s.keepwarm ? "on" : "off")
    if (s.connected != null) sendEvent(name:"connected",  value:s.connected.toString())
    if (s.probe_state != null) sendEvent(name:"probeState", value:probeStateName(s.probe_state as int))
    // probe_con: 1=connected, 0=not connected
    if (s.probe_con != null) {
        def ps = (s.probe_con as int) == 1 ? "set" : "idle"
        if (s.probe_state == null) sendEvent(name:"probeState", value:ps)
    }

    def timerStart = ((s.cook_timer_start != null ? s.cook_timer_start : s.sys_timer_start) ?: 0) as Long
    def timerEnd   = ((s.cook_timer_end   != null ? s.cook_timer_end   : s.sys_timer_end)   ?: 0) as Long
    def nowSec     = (now() / 1000L) as Long
    if (timerStart > 0 && timerEnd > nowSec) {
        def rem  = timerEnd - nowSec
        sendEvent(name:"timerActive",    value:"true")
        sendEvent(name:"timerRemaining", value:"${(rem/60).toInteger()}m ${(rem%60).toInteger()}s")
    } else {
        sendEvent(name:"timerActive",    value:"false")
        sendEvent(name:"timerRemaining", value:"")
    }
    // Pellet level
    def pelletLevel = s.pellet_level != null ? s.pellet_level : (s.pellet != null ? s.pellet : s.pellets)
    if (pelletLevel != null) {
        def pct = pelletLevel as int
        def prev = device.currentValue("pelletLevel")
        def prevPct = (prev != null ? prev : 100) as int
        sendEvent(name:"pelletLevel", value:pct, unit:"%")
        // Button 3: pellets low — only fire on falling edge (>= 20 → < 20)
        if (pct < 20 && prevPct >= 20) pushButton(3)
    }

    // Probe alarm fired (probe reached target temp)
    if (s.probe_alarm_fired != null) {
        def fired = (s.probe_alarm_fired as int) == 1
        def prev  = device.currentValue("probeAlarmFired")
        sendEvent(name:"probeAlarmFired", value:fired.toString())
        // Button 2: probe at target — only fire on rising edge
        if (fired && prev != "true") pushButton(2)
    }

    // Cook timer complete
    if (s.cook_timer_complete != null) {
        def complete = (s.cook_timer_complete as int) == 1
        def prev     = device.currentValue("cookTimerComplete")
        sendEvent(name:"cookTimerComplete", value:complete.toString())
        // Button 5: cook timer complete — only fire on rising edge
        if (complete && prev != "true") pushButton(5)
    }

    // Errors
    if (s.errors != null) {
        def errVal = s.errors as int
        def prevErr = (device.currentValue("errors") ?: 0) as int
        sendEvent(name:"errors", value:errVal)
        // Button 4: new error appeared
        if (errVal > 0 && prevErr == 0) pushButton(4)
    }

    // Button 4: grill offline
    if (stateCode == 99) {
        if (prevGrillState != "offline") pushButton(4)
    }

    // Button 1: preheat complete — grill transitions from preheating to cooking
    if (stateCode in [6, 7]) {
        if (prevGrillState == "preheating") pushButton(1)
    }
    sendEvent(name:"lastUpdate", value:new Date().toString())
}

private String grillStateName(int c) {
    switch(c) {
        case 99: return "offline"
        case 9:  return "shutdown"
        case 8:  return "cool_down"
        case 7:  return "custom_cook"
        case 6:  return "manual_cook"
        case 5:  return "preheating"
        case 4:  return "igniting"
        case 3:  return "idle"
        case 2:  return "sleeping"
        default: return "unknown_${c}"
    }
}

private String probeStateName(int c) {
    switch(c) {
        case 0: return "idle"
        case 1: return "set"
        case 2: return "close"
        case 3: return "at_temp"
        case 4: return "fell_out"
        default: return "unknown_${c}"
    }
}

private String heatingStateName(int stateCode, int current, int target) {
    if (stateCode == 99)              return "offline"
    if (stateCode in [8,9])           return "cool_down"
    if (stateCode in [2,3])           return "idle"
    if (stateCode == 4)               return "igniting"
    if (stateCode == 5)               return "preheating"
    if (stateCode in [6,7]) {
        if (current < target - 5)     return "heating"
        if (current > target + 10)    return "over_temp"
        return "at_temp"
    }
    return "idle"
}

private String thermostatOpState(int stateCode, Map s) {
    if (stateCode in [99,9,2,3,8]) return "idle"
    if (stateCode in [4,5])        return "heating"
    if (stateCode in [6,7])        return (s.grill != null && s.set != null && (s.grill as int) >= (s.set as int) - 5) ? "idle" : "heating"
    return "idle"
}

// ─── Commands ─────────────────────────────────────────────────────────────────

def on()  { setGrillTemperature(device.currentValue("grillSetTemperature") ?: 225) }
def off() { shutdownGrill() }
def refresh()            { requestStateUpdate() }
def requestStateUpdate() { sendCmd("90") }

def setThermostatSetpoint(t) { setGrillTemperature(t) }
def setHeatingSetpoint(t)    { setGrillTemperature(t) }
def setCoolingSetpoint(t)    { setGrillTemperature(t) }
def setThermostatMode(m)     { if (m == "off") shutdownGrill() }
def auto() {} ; def cool() {} ; def heat() { on() }
def emergencyHeat() {} ; def fanAuto() {} ; def fanCirculate() {} ; def fanOn() {}

def setGrillTemperature(temp) { sendCmd("11,${temp as int}") }
def setProbeTemperature(temp) { sendCmd("14,${temp as int}") }
def shutdownGrill()           { sendCmd("17") }
def setSuperSmoke(String e)   { sendCmd(e == "on" ? "20" : "21") }
def setKeepWarm(String e)     { sendCmd(e == "on" ? "18" : "19") }
def setTimerMinutes(m)        { sendCmd("12,${(m as int)*60}") }
def cancelTimer()             { sendCmd("12,0") }

// Scheduled refresh called by app poll
def scheduledRefresh() {
    if (!state.mqttConnected) {
        logDebug "scheduledRefresh: MQTT not connected, reconnecting"
        connectMqtt()
    } else {
        requestStateUpdate()
    }
}

private void sendCmd(String command) {
    def thingName = device.getDataValue("thingName")
    if (!thingName) { log.error "[Traeger:${device.label}] No thingName set"; return }
    try { parent?.sendGrillCommand(thingName, command) }
    catch (Exception e) { log.error "[Traeger:${device.label}] sendCmd(${command}) failed: ${e.message}" }
}

private void pushButton(int buttonNum) {
    log.info "[Traeger:${device.label}] Button ${buttonNum} pushed"
    sendEvent(name: "pushed", value: buttonNum, isStateChange: true,
              descriptionText: buttonDescription(buttonNum))
}

private String buttonDescription(int n) {
    switch (n) {
        case 1: return "Preheat complete — grill reached set temperature"
        case 2: return "Probe at target temperature"
        case 3: return "Pellets low (below 20%)"
        case 4: return "Grill offline or error"
        case 5: return "Cook timer complete"
        default: return "Button ${n} pushed"
    }
}

private void logDebug(String msg) { if (enableDebug) log.debug "[Traeger:${device.label}] ${msg}" }
