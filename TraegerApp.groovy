/**
 * Traeger WiFire Integration for Hubitat Elevation
 * App: TraegerApp.groovy
 * Version: 1.4.1
 *
 * Responsibilities:
 *   - Traeger auth endpoint for token management
 *   - Grill discovery via REST
 *   - Fetching pre-signed MQTT WSS URL (handed to driver)
 *   - Sending grill commands via REST POST
 *
 * The driver owns MQTT (interfaces.mqtt is not available in apps).
 *
 * Change log:
 *  1.5.0 - FTY Dashboard Switch work
 *  1.4.1 - Companion release for driver log noise fixes
 *  1.4.0 - Migrate from AWS Cognito to Traeger auth endpoint (24h tokens)
 *  1.3.1 - Switch API base URL to new Traeger-branded domain
 *  1.3.0 - Companion release for driver session time tracking feature
 *  1.2.0 - Demote MQTT URL fetch message to debug
 *  1.1.0 - Fix auth response parsing, add null guard for token expiration
 *  1.0.0 - Initial release
 */

definition(
    name: "Traeger Integration",
    namespace: "craigde",
    author: "Craig Dewar",
    description: "Integrates Traeger WiFire grills with Hubitat via Traeger cloud API + MQTT",
    category: "My Apps",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: true
)

preferences {
    page(name: "mainPage")
    page(name: "credentialsPage")
    page(name: "devicesPage")
    page(name: "selectGrillPage")
    page(name: "manageSwitchesPage")
}

// ─── Constants ────────────────────────────────────────────────────────────────
private String authBase()            { "https://auth-api.iot.traegergrills.io" }
private String apiBase()             { "https://mobile-iot-api.iot.traegergrills.io" }
private String driverName()          { "Traeger Grill" }
private String childSwitchDriver()   { "Generic Component Switch" }
private String driverNamespace()     { "craigde" }
private int    tokenRefreshMargin()  { 300 }   // seconds before expiry to refresh

// ─── Pages ────────────────────────────────────────────────────────────────────

def mainPage() {
    dynamicPage(name: "mainPage", title: "Traeger Integration", install: true, uninstall: true) {
        section("Configuration") {
            href "credentialsPage", title: "Account Credentials",
                description: traegerUsername ? "Configured: ${traegerUsername}" : "Not configured — tap to set"
            href "devicesPage", title: "Manage Devices",
                description: getChildGrillDevices()?.size() ? "${getChildGrillDevices().size()} device(s) installed" : "No devices"
            href "selectGrillPage", title: "Manage Grill Dashboard Switches"

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
                    if ( isChildGrillDevice(existing) ) {
                        paragraph "${grill.thingName} — ${existing ? 'Installed ✓' : 'Not installed'}"
                    }
                }
            }
        }
        section("Installed Devices") {
            getChildDevices()?.each { dev ->
                if ( isChildGrillDevice(dev) ) {
                    paragraph "${dev.label} [${dev.getDataValue('thingName')}]"
                    input "btnRefresh_${dev.deviceNetworkId}", "button", title: "Reconnect MQTT: ${dev.label}"
                }
            }
        }
    }
}

def selectGrillPage() {
	dynamicPage(name:"selectGrillPage", title: "Dashboard switches can control their associated grill functions and they mirror the state (on or off) of the function.", nextPage: "manageSwitchesPage") {
        section {
            paragraph "<h3>First, Select the grill for the dashboard switches:</h3>"
             input name:'driverTarget', type: 'device.TraegerGrill', title: 'Grill to add or delete switches:',
            required: true, submitOnChange: true
        }
        //init dashboard switch settings outside the context where the switches will be rendered so re-rendering doesn't
        //cause problems
        if ( driverTarget ) {
            initDashboardSwitchSettings()
        }
        else {
            log.error("No grill selected to configure Dashboard Switches")
        }
   }  
}

def manageSwitchesPage() {
	dynamicPage(name:"manageSwitchesPage", title: "Second, set or clear each item to create or remove the associated switch, Then click the update button to apply your changes.", nextPage: "mainPage") {
        if ( driverTarget ) {
            section {
                paragraph "<strong>Dashboard Switches for ${driverTarget}:</strong>"
                childSwitchMap(null).each { key, cfg -> 
                    def swType = key + "-input"
                    input "${swType}", 'bool', title: "${cfg.label}", submitOnChange: true
                }      
            }  
            section {
                input 'btnManageSwitches', 'button', title: 'Update'
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
    if (buttonName == 'btnManageSwitches') { 
        manageSwitches() 
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
    deleteChildDevices()
}

def deleteChildDevices() {
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

// ─── Child Switch Devices ─────────────────────────────────────────────────────

// Called by Hubitat when a Generic Component Switch child receives on/off
void componentOn(cd) {
    sendEvent(cd, [name: "switch", value: "on", descriptionText: "${cd.displayName} is on"])
    def key = cd.deviceNetworkId.tokenize("-").last()
    def cfg = childSwitchMap(cd)[key]
    if (cfg) {
        logDebug "Child on: ${cd.label}"
        cfg.onCmd()
    }
}

void componentOff(cd) {
    sendEvent(cd, [name: "switch", value: "off", descriptionText: "${cd.displayName} is off"])
    def key = cd.deviceNetworkId.tokenize("-").last()
    def cfg = childSwitchMap(cd)[key]
    if (cfg) {
        logDebug "Child off: ${cd.label}"
        cfg.offCmd()
    }
}

void componentRefresh(cd) {
    def key = cd.deviceNetworkId.tokenize("-").last()
    def cfg = childSwitchMap(cd)[key]
    if (cfg) {
        logDebug "Child Refresh: ${cd.label}"
        cfg.refreshCmd()
    }
}

//called by the child driver
private void syncChildSwitch(String key, String value) {
    def child = null
    getChildSwitchDevices()?.each {
        if( it.deviceNetworkId.tokenize("-").last() == key) {
            child = it
        } 
    }
    if (child) {
        child.parse([[name: "switch", value: value]])
    }
}

private Map childSwitchMap(dev) {
    def parentGrill = null
    if ( dev ) {
        def dni = dev.deviceNetworkId.substring(0, dev.deviceNetworkId.lastIndexOf('-'))
        parentGrill = getChildDevice(dni)
    }
    return [
        "supersmoke": [label: "Super Smoke", onCmd:  {parentGrill.setSuperSmoke("on")  }, 
                                             offCmd: {parentGrill.setSuperSmoke("off") }, 
                                             refreshCmd: { parentGrill.requestStateUpdate() }],
        "keepwarm":   [label: "Keep Warm",   onCmd:  { parentGrill.setKeepWarm("on")    }, 
                                             offCmd: { parentGrill.setKeepWarm("off")   },
                                             refreshCmd: { parentGrill.requestStateUpdate() }]
    ]
}

def initDashboardSwitchSettings() {
    logDebug("Calling initDashboardSwitchSettings()")
    def theGrill = settings?.driverTarget
    if ( !theGrill ) {
        log.error "No grill specified for Dashboard Switches"
        return
    }
    childSwitchMap(null).each { key, cfg -> 
        def swType = key + "-input"
        def dev = null;
        getChildSwitchDevicesForGrill(theGrill).each {
            if( it.deviceNetworkId.tokenize("-").last() == key ){
                dev = it
            }
        }
        app.updateSetting(swType, (dev != null)) 
    }
}

def manageSwitches() {
    def theGrill = settings?.driverTarget
    if (!theGrill) {
        log.error "Error: No grill selected for Manage Dashboard Switches"
        return;
    }
    childSwitchMap(null).each { key, cfg -> 
        def inputSetting = app.getSetting("${key}-input")
        if (  inputSetting == null )
        {
            logDebug("Error: missing setting")
            return;
        }
        def dev = null;
        getChildSwitchDevicesForGrill(theGrill).each {
            if( it.deviceNetworkId.tokenize("-").last() == key ){
                dev = it
            }
        }
        // Is there a change in the switch setting?
        if ( dev && !inputSetting) {
            deleteChildDevice(dev.deviceNetworkId) 
            log.info "[Traeger:${theGrill.label}] Deleted child dashboard switch: ${cfg.label}"
        } 
        else if (inputSetting && !dev) {
            def dni ="${theGrill.deviceNetworkId}-${key}"
            def child = addChildDevice("hubitat", childSwitchDriver(), dni, [
                label: "${theGrill.label} ${cfg.label}",
                isComponent: false
            ])
            if ( child ) {
                log.info "[Traeger:${theGrill.label}] Added child dashboard switch: ${cfg.label}"
            }
            else {
                log.error "Error: Child dashboard switch not added"
            }
        }
    }      
}

// ─── Authentication ───────────────────────────────────────────────────────────

def authenticate() {
    logDebug "[TraegerApp] Authenticating as ${traegerUsername}"
    try {
        httpPostJson([
            uri:  "${authBase()}/tokens",
            body: [username: traegerUsername, password: traegerPassword]
        ]) { resp ->
            def data = resp.data instanceof Map ? resp.data : new groovy.json.JsonSlurper().parseText(resp.data.text)
            state.accessToken  = data.idToken
            state.tokenExpires = now() + (((data.expiresIn ?: 86400) - tokenRefreshMargin()) * 1000L)
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
    getChildGrillDevices()?.each { dev ->
        try {
            dev.scheduledRefresh()
        } 
        catch (Exception e) {
            logDebug "[TraegerApp] Poll error for ${dev.label}: ${e.message}"
        }
    }
}

def initializeAllDrivers() {
    getChildGrillDevices()?.each { dev ->
        try { 
            dev.connectMqtt() 
        }
        catch (Exception e) {
                log.error "[TraegerApp] Failed to init MQTT for ${dev.label}: ${e.message}" 
        }
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

def logDebug(String msg) { if (enableDebug) log.debug msg }

boolean isChildSwitchDevice(com.hubitat.app.ChildDeviceWrapper device) {return device.getTypeName() == childSwitchDriver()}
boolean isChildGrillDevice(com.hubitat.app.ChildDeviceWrapper device) {return device.getTypeName() == driverName()}

List getChildGrillDevices() {
    List ls = []
    getChildDevices()?.each { dev ->
        if (isChildGrillDevice(dev) ) {
            ls << dev
        }
    } 
    return ls 
}

List getChildSwitchDevices() {
    List ls = []
    getChildDevices()?.each { dev ->
        if (isChildSwitchDevice(dev) ) {
            ls << dev
        }
    } 
    return ls 
}

List getChildSwitchDevicesForGrill(theGrill) {
    List ls = []
    getChildDevices()?.each { dev ->
        if (isChildSwitchDevice(dev) ) {
            if ( dev.deviceNetworkId.substring(0, dev.deviceNetworkId.lastIndexOf('-')) == theGrill.deviceNetworkId) {
                ls << dev
            }
        }
    } 
    return ls 
}
