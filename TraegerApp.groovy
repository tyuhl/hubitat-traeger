/**
 * Traeger WiFire Integration for Hubitat Elevation
 * App: TraegerApp.groovy
 * Version: 1.3.0
 *
 * Responsibilities:
 *   - AWS Cognito authentication + token refresh
 *   - Grill discovery via REST
 *   - Fetching pre-signed MQTT WSS URL (handed to driver)
 *   - Sending grill commands via REST POST
 *
 * The driver owns MQTT (interfaces.mqtt is not available in apps).
 *
 * Change log:
 *  1.3.0 - Companion release for driver session time tracking feature
 *  1.2.0 - Demote MQTT URL fetch message to debug
 *  1.1.0 - Fix auth response parsing, add null guard for token expiration
 *  1.0.0 - Initial release
 */

definition(
    name: "Traeger Integration",
    namespace: "craigde",
    author: "Craig Dewar",
    description: "Integrates Traeger WiFire grills with Hubitat via AWS Cognito + MQTT",
    category: "My Apps",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: true
)

preferences {
    page(name: "mainPage")
    page(name: "credentialsPage")
    page(name: "devicesPage")
}

// ─── Constants ────────────────────────────────────────────────────────────────
private String cognitoClientId()    { "4id473dsrcq4kevlgrikukqn2a" }
private String apiBase()            { "https://mobile-iot-api.iot.traegergrills.io" }
private String driverName()         { "Traeger Grill" }
private String driverNamespace()    { "craigde" }
private int    tokenRefreshMargin() { 300 }   // seconds before expiry to refresh
private int    mqttUrlMargin()      { 300 }   // seconds before URL expiry to refresh

// ─── Pages ────────────────────────────────────────────────────────────────────

def mainPage() {
    dynamicPage(name: "mainPage", title: "Traeger Integration", install: true, uninstall: true) {
        section("Configuration") {
            href "credentialsPage", title: "Account Credentials",
                description: traegerUsername ? "Configured: ${traegerUsername}" : "Not configured — tap to set"
            href "devicesPage", title: "Manage Devices",
                description: getChildDevices()?.size() ? "${getChildDevices().size()} device(s) installed" : "No devices"
        }
        section("Options") {
            input "pollInterval", "number", title: "Poll interval (minutes)", defaultValue: 15, range: "5..60"
            input "enableDebug",  "bool",   title: "Enable debug logging",    defaultValue: false
        }
        section("Status") {
            paragraph "Token valid: ${state.tokenExpires ? (now() < (state.tokenExpires as Long) ? 'Yes ✓' : 'Expired') : 'Not authenticated'}"
        }
    }
}

def credentialsPage() {
    dynamicPage(name: "credentialsPage", title: "Traeger Account", nextPage: "mainPage") {
        section("Enter your Traeger app credentials") {
            input "traegerUsername", "email",    title: "Email address", required: true
            input "traegerPassword", "password", title: "Password",      required: true
        }
    }
}

def devicesPage() {
    dynamicPage(name: "devicesPage", title: "Traeger Devices", nextPage: "mainPage") {
        section {
            paragraph "Press Discover to search your Traeger account, then Create All to install devices."
            input "btnDiscover",  "button", title: "Discover Devices"
            input "btnCreateAll", "button", title: "Create All Devices"
        }
        if (state.discoveredGrills) {
            section("Discovered Grills") {
                state.discoveredGrills.each { grill ->
                    def existing = getChildDevice("traeger-${grill.thingName}")
                    paragraph "${grill.thingName} — ${existing ? 'Installed ✓' : 'Not installed'}"
                }
            }
        }
        section("Installed Devices") {
            getChildDevices()?.each { dev ->
                paragraph "${dev.label} [${dev.getDataValue('thingName')}]"
                input "btnRefresh_${dev.deviceNetworkId}", "button", title: "Reconnect MQTT: ${dev.label}"
            }
        }
    }
}

def appButtonHandler(buttonName) {
    if (buttonName == "btnDiscover")  { discoverGrills();   return }
    if (buttonName == "btnCreateAll") { createAllDevices(); return }
    if (buttonName.startsWith("btnRefresh_")) {
        def dni = buttonName.replace("btnRefresh_", "")
        getChildDevice(dni)?.connectMqtt()
        return
    }
}

// ─── Lifecycle ────────────────────────────────────────────────────────────────

def installed() {
    log.info "[TraegerApp] Installed"
    initialize()
}

def updated() {
    log.info "[TraegerApp] Updated"
    unschedule()
    initialize()
}

def initialize() {
    logDebug "[TraegerApp] Initializing"
    def interval = (pollInterval ?: 15) as int
    schedule("0 */${interval} * * * ?", "scheduledPoll")
    // Tell each driver to (re)connect its MQTT after a short delay
    runIn(5, "initializeAllDrivers")
}

def uninstalled() {
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

// ─── Authentication ───────────────────────────────────────────────────────────

def authenticate() {
    logDebug "[TraegerApp] Authenticating as ${traegerUsername}"
    def bodyMap = [
        AuthFlow:       "USER_PASSWORD_AUTH",
        AuthParameters: [USERNAME: traegerUsername, PASSWORD: traegerPassword],
        ClientId:       cognitoClientId()
    ]
    def bodyJson = new groovy.json.JsonOutput().toJson(bodyMap)
    try {
        httpPost([
            uri:         "https://cognito-idp.us-west-2.amazonaws.com/",
            contentType: "text/plain",
            headers: [
                "Content-Type": "application/x-amz-json-1.1",
                "X-Amz-Target": "AWSCognitoIdentityProviderService.InitiateAuth"
            ],
            body: bodyJson
        ]) { resp ->
            def parsed = resp.data instanceof Map ? resp.data : new groovy.json.JsonSlurper().parseText(resp.data.text)
            def result = parsed.AuthenticationResult
            state.accessToken  = result.IdToken
            state.refreshToken = result.RefreshToken
            state.tokenExpires = now() + ((result.ExpiresIn - tokenRefreshMargin()) * 1000L)
            logDebug "[TraegerApp] Authenticated — token expires ${new Date((long)state.tokenExpires)}"
        }
    } catch (Exception e) {
        log.error "[TraegerApp] Authentication failed: ${e.message}"
        throw e
    }
}

def refreshTokenIfNeeded() {
    if (!state.accessToken || now() >= (state.tokenExpires ?: 0L)) {
        authenticate()
    }
}

// ─── Grill Discovery ──────────────────────────────────────────────────────────

def discoverGrills() {
    logDebug "[TraegerApp] Discovering grills"
    refreshTokenIfNeeded()
    try {
        httpGet([
            uri:     "${apiBase()}/users/self",
            headers: [Authorization: "Bearer ${state.accessToken}"]
        ]) { resp ->
            state.discoveredGrills = resp.data.things
            log.info "[TraegerApp] Found ${state.discoveredGrills?.size() ?: 0} grill(s)"
        }
    } catch (Exception e) {
        log.error "[TraegerApp] Discovery failed: ${e.message}"
        throw e
    }
}

def createAllDevices() {
    if (!state.discoveredGrills) discoverGrills()
    state.discoveredGrills?.each { grill -> createGrillDevice(grill) }
}

def createGrillDevice(grill) {
    def dni = "traeger-${grill.thingName}"
    if (getChildDevice(dni)) { logDebug "[TraegerApp] ${dni} already exists"; return }
    def label = grill.nickname ?: grill.name ?: grill.thingName
    try {
        def dev = addChildDevice(driverNamespace(), driverName(), dni, [
            label: "Traeger ${label}",
            name:  driverName()
        ])
        dev.updateDataValue("thingName", grill.thingName)
        log.info "[TraegerApp] Created device: ${label} (${grill.thingName})"
    } catch (Exception e) {
        log.error "[TraegerApp] Failed to create ${dni}: ${e.message}"
    }
}

// ─── MQTT URL (called by drivers) ────────────────────────────────────────────

/**
 * Called by child drivers to get a valid pre-signed MQTT WSS URL.
 * Caches the URL and only refreshes when near expiry.
 * Returns the URL string, or null on failure.
 */
String getSignedMqttUrl() {
    // Always fetch a fresh URL - pre-signed URLs expire quickly and must not be reused
    logDebug "[TraegerApp] Fetching fresh signed MQTT URL"
    refreshTokenIfNeeded()
    try {
        httpPostJson([
            uri:     "${apiBase()}/mqtt-connections",
            headers: [Authorization: "Bearer ${state.accessToken}"],
            body:    [:]
        ]) { resp ->
            state.mqttUrl        = resp.data.signedUrl
            def expSec           = (resp.data.expirationSeconds ?: 300) as Long
            state.mqttUrlExpires = now() + (expSec * 1000L)
            logDebug "[TraegerApp] MQTT URL obtained (expires ${expSec}s): ${state.mqttUrl?.take(60)}…"
        }
        return state.mqttUrl
    } catch (Exception e) {
        log.error "[TraegerApp] Failed to get MQTT URL: ${e.message}"
        return null
    }
}

// ─── Commands (called by drivers) ────────────────────────────────────────────

/**
 * Called by child drivers to send a command to a grill.
 * command examples: "90" (refresh), "11,225" (set temp), "17" (shutdown)
 */
def sendGrillCommand(String thingName, String command) {
    logDebug "[TraegerApp] sendGrillCommand: ${thingName} → ${command}"
    refreshTokenIfNeeded()
    try {
        httpPostJson([
            uri:     "${apiBase()}/things/${thingName}/commands",
            headers: [Authorization: "Bearer ${state.accessToken}"],
            body:    [command: command]
        ]) { resp ->
            logDebug "[TraegerApp] Command OK: ${command} (${resp.status})"
        }
    } catch (Exception e) {
        log.error "[TraegerApp] Command failed for ${thingName}: ${e.message}"
        throw e
    }
}

// ─── Polling ─────────────────────────────────────────────────────────────────

def scheduledPoll() {
    logDebug "[TraegerApp] Scheduled poll — checking drivers"
    getChildDevices()?.each { dev ->
        try {
            dev.scheduledRefresh()
        } catch (Exception e) {
            logDebug "[TraegerApp] Poll error for ${dev.label}: ${e.message}"
        }
    }
}

def initializeAllDrivers() {
    getChildDevices()?.each { dev ->
        try { dev.connectMqtt() }
        catch (Exception e) { log.error "[TraegerApp] Failed to init MQTT for ${dev.label}: ${e.message}" }
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

def logDebug(String msg) { if (enableDebug) log.debug msg }
