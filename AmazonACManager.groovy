#include gilderman.AmazonACManagerHelper

definition(
    name: "Amazon AC Manager",
    namespace: "gilderman",
    author: "Ilia Gilderman",
    description: "Creates child devices using a custom driver",
    category: "My Apps",
    iconUrl: "https://raw.githubusercontent.com/gilderman/Amazon-Thermostat/icon.svg",
    iconX2Url: "",
    importUrl: "https://github.com/gilderman/Amazon-Thermostat/blob/main/AmazonACManager.groovy",
    oauth: true,
    singleInstance: false,
    installOnOpen: true
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Amazon AC Manager", install: true, uninstall: true) {
        section("Alexa Cookie (direct API auth)") {
            paragraph "Use manual cookie OR cookie server URL — one required for polling. Same URL as downchannel bridge COOKIE_SERVER_URL."
            input name: "alexaCookie", type: "text", title: "Manual cookie (optional)", description: "Paste full Cookie header from DevTools", required: false
            input name: "cookieServerUrl", type: "string", title: "Cookie server URL", description: "Full URL returning { cookie, csrf } — same as bridge", required: false
            input name: "pollIntervalMinutes", type: "number", title: "Poll interval (minutes)", description: "How often to poll Alexa for thermostat state", defaultValue: 2, range: "1..15"
            input name: "useFastPoll", type: "bool", title: "Fast poll (near real-time)", description: "Poll every 30 sec instead of interval. Simulates push notifications in Groovy.", defaultValue: false
        }
        section("Thermostats (AC/HVAC)") {
            input name: "thermostatNames", type: "string", title: "Names as in Alexa app (comma‑separated)", required: true
        }
        section("Create ACs") {
            input "createButton", "button", title: "Create Devices"
        }
        section("Debug") {
            input name: "debugLog", type: "bool", title: "Debug logging", defaultValue: false
        }
    }
}

mappings {
    path("/cookie") {
        action: [
            GET: "cookieEndpoint"
        ]
    }
    path("/statusreportfromtheapp") {
        action: [
            POST: "statusCallback"
        ]
    }
}

def installed() {
    log.debug "Installed with settings: ${settings}"
    createAccessToken()
    if (settings?.thermostatNames) {
        initialize()
    }
}

def updated() {
    log.debug "Updated with settings: ${settings}"
    createAccessToken()
    if (settings?.thermostatNames) {
        unsubscribe()
        unschedule()
        initialize()
    }
}

def initialize() {
    log.debug "App initialized"

    state.totalDevices = thermostatNames?.split(/\s*,\s*/)?.size() ?: 0

    unschedule("pollAlexaThermostats")
    if (settings.useFastPoll) {
        log.debug "Fast poll mode: every 30 seconds (notification-like updates)"
    } else {
        def mins = Math.max(1, Math.min(15, (settings.pollIntervalMinutes as Integer) ?: 2))
        schedule("0 0/${mins} * * * ?", "pollAlexaThermostats")
        log.debug "Poll scheduled every ${mins} minute(s)"
    }

    if (settings.alexaCookie?.trim() || settings.cookieServerUrl?.trim()) {
        runIn(5, "pollAlexaThermostats")
    }
}

def appButtonHandler(btn) {
    if (btn == "createButton") {
        createChildDevices()
		
		if (state.accessToken == null)
			createAccessToken()
    }
}

def createChildDevices() {
    createChildDevicesForDriver(thermostatNames, "Amazon Thermostat (via alexa)")
}

def createChildDevicesForDriver(thermostats, driverName) {
	def deviceNames = thermostats.split(",").collect { it.trim() }
	
    deviceNames.each { name ->
        def dni = "123|auto-${name.replaceAll('[^a-zA-Z0-9]', '_').toLowerCase()}"
        def dev = getChildDevice(dni)

        if (!dev) {
            dev = addChildDevice(
                "gilderman",
                driverName,
                dni,
                location.hubs[0].id,
                [label: "[VIR] ${name}", isComponent: false],
            )
            log.info "✅ Created device: ${dev.displayName} (DNI: ${dni}), acDeviceName: ${name}"
        } else {
            log.info "⏩ Device '${name}' already exists (DNI: ${dni}) — refreshing"
        }
        dev.initialize()
    }
}

def cookieEndpoint() {
	def cookie = null
	def csrfVal = ''
	if (settings?.alexaCookie?.trim()) {
		cookie = settings.alexaCookie.trim()
		def m = (cookie =~ /(?:^|;\s*)csrf=([^;]+)/i)
		csrfVal = m.find() ? m.group(1).trim() : ''
	} else if (state?.cookieFromServer?.cookie && state?.cookieFromServer?.csrf) {
		cookie = state.cookieFromServer.cookie
		csrfVal = state.cookieFromServer.csrf ?: ''
	}
	if (!cookie) {
		render contentType: "application/json", data: [error: "No cookie configured. Set manual cookie or cookie server URL."], status: 503
		return
	}
	render contentType: "application/json", data: [cookie: cookie, csrf: csrfVal], status: 200
}

def statusCallback() {
	def payload = request.JSON
    if (!payload) {
        log.warn "Empty payload in status callback"
        return
    }
    def list = payload instanceof List ? payload : [payload]
    if (list.size() != state.totalDevices) {
        log.debug "Received ${list.size()} thermostats (expected ${state.totalDevices})"
    }
    updateThermostats(list)
}

def logAllStates(device) {
    log.debug "=== States for ${device.displayName} ==="
    device.supportedAttributes.each { attr ->
        def current = device.currentValue(attr.name)
        log.debug "${attr.name}: ${current}"
    }
    log.debug "=============================="
}

def updateThermostats(List<Map> dataList) {
    dataList.each { entry ->
        def name = entry.name
        def endpointId = entry.endpointId
        def mode = entry.mode?.toLowerCase()
        def temp = entry.currentTemp?.replaceAll(/[^\d.]/, '') as Double

        def child = getChildDevices().find { it.label.endsWith(name) }
        if (!child) {
            log.warn "No child device found with name '$name'"
            return
        }
        if (endpointId) {
            child.updateDataValue('endpointId', endpointId)
        }

        child.sendEvent(name: "temperature", value: temp)
        child.sendEvent(name: "thermostatMode", value: mode)

        switch (mode) {
            case "heat":
                def targetVal = entry.target?.replaceAll(/[^\d.]/, '') as Double
                if (targetVal != null) {
                    child.sendEvent(name: "heatingSetpoint", value: targetVal)
                }
                break
            case "cool":
                def targetVal = entry.target?.replaceAll(/[^\d.]/, '') as Double
                if (targetVal != null) {
                    child.sendEvent(name: "coolingSetpoint", value: targetVal)
                }
                break
            case "auto":
                def low = entry.lowerSetpoint
                def high = entry.upperSetpoint
                if (low != null && high != null) {
                    child.sendEvent(name: "heatingSetpoint", value: low)
                    child.sendEvent(name: "coolingSetpoint", value: high)
                } else {
                    def range = entry.target?.findAll(/[\d.]+/)
                    if (range?.size() >= 2) {
                        low = range[0] as Double
                        high = range[1] as Double
                        child.sendEvent(name: "heatingSetpoint", value: low)
                        child.sendEvent(name: "coolingSetpoint", value: high)
                    } else {
                        log.warn "Could not parse auto mode range for '$name': ${entry.target}"
                    }
                }
                break
            case "off":
                // no setpoints updated
                break
            default:
                log.warn "Unknown mode: $mode"
        }
        
        //logAllStates(child)
        child.updateOperatingState()
    }
}
