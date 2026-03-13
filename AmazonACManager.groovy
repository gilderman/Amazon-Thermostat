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
        section("Cookie Status") {
            paragraph getCookieStatusText()
        }
        section("Poll Status") {
            paragraph getPollStatusText()
        }
        section("Alexa Cookie (direct API auth)") {
            paragraph "Use manual cookie OR cookie server URL — one required for polling. Echo Speaks Docker: use http://HOST:8091/cookieData (include /cookieData)."
            input name: "alexaCookie", type: "text", title: "Manual cookie (optional)", description: "Paste full Cookie header from DevTools", required: false
            input name: "cookieServerUrl", type: "string", title: "Cookie server URL", description: "Echo Speaks: http://HOST:8091/cookieData — or Hubitat /cookie", required: false
            input name: "downchannelServerUrl", type: "string", title: "Downchannel server URL (optional)", description: "e.g. http://192.168.21.100:3099 — routes poll via local proxy, fixes 408 errors from Hubitat→Alexa TLS issues", required: false
            input name: "pollIntervalMinutes", type: "number", title: "Poll interval (minutes)", description: "How often to poll Alexa for thermostat state", defaultValue: 2, range: "1..15"
            input name: "useFastPoll", type: "bool", title: "Fast poll (near real-time)", description: "Poll every 30 sec instead of interval. Simulates push notifications in Groovy.", defaultValue: false
            input name: "alexaApiTimeoutSeconds", type: "number", title: "Alexa API timeout (sec)", description: "Request timeout. Try increasing if 408 timeout errors.", defaultValue: 30, range: "15..120"
            paragraph "Test result appears in Logs (Info level)."
            input "testCookieButton", "button", title: "Test Cookie Fetch"
        }
        section("Thermostats (AC/HVAC)") {
            input name: "thermostatNames", type: "string", title: "Names as in Alexa app (comma‑separated)", required: true
            input "pollNowButton", "button", title: "Poll Now"
        }
        section("Create ACs") {
            input "createButton", "button", title: "Create Devices"
        }
        section("Debug") {
            input name: "debugLog", type: "bool", title: "Debug logging", description: "Show [Poll] timing and flow in logs (enable Debug in Live Logs)", defaultValue: false
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

def getAlexaHeadersForCommand() {
    def cookie = null
    def csrfVal = ''
    if (settings?.alexaCookie?.trim()) {
        cookie = settings.alexaCookie.trim()
        def m = (cookie =~ /(?i)(?:^|;\s*)csrf=([^;]+)/)
        csrfVal = m.find() ? m.group(1).trim() : ''
    } else if (state?.cookieFromServer?.cookie) {
        cookie = state.cookieFromServer.cookie
        csrfVal = state.cookieFromServer?.csrf ?: ''
    }
    if (!cookie?.trim()) return null
    return ['Cookie': cookie, 'csrf': csrfVal, 'Accept': 'application/json', 'Content-Type': 'application/json',
        'User-Agent': 'Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36', 'Referer': 'https://alexa.amazon.com/', 'Origin': 'https://alexa.amazon.com']
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
    } else if (btn == "testCookieButton") {
        testCookieFetch()
    } else if (btn == "pollNowButton") {
        log.info "Manual poll triggered"
        pollAlexaThermostats()
    }
}

def testCookieFetch() {
    if (settings?.alexaCookie?.trim()) {
        state.lastCookieTestResult = "Manual cookie configured"
        state.lastCookieTestTime = now()
        log.info("Manual cookie is configured. No fetch needed.")
        return
    }
    def cookieServer = settings?.cookieServerUrl?.trim()
    if (!cookieServer) {
        state.lastCookieTestResult = "Not configured"
        state.lastCookieTestTime = now()
        log.info("Configure cookie server URL first.")
        return
    }
    cookieServer = normalizeCookieServerUrl(cookieServer)
    log.info "Cookie fetch URL: ${cookieServer}"
    asynchttpGet('cookieTestCallback', [uri: cookieServer, timeout: 30])
}

def cookieTestCallback(resp, data) {
    def msg
    if (resp.status == 200) {
        def json = resp.json
        if (!json) {
            try { json = new groovy.json.JsonSlurper().parseText(resp.data ?: '{}') }
            catch (e) { msg = "Parse error: ${e.message}"; log.warn msg }
        }
        if (json) {
            def cd = json.cookieData ?: json
            def cookie = cd.localCookie ?: cd.cookie
            def csrfVal = cd.csrf ?: ''
            if (cookie) {
                def hasToken = (cookie =~ /(?i)at-main=/).find()
                msg = hasToken ? "Cookie fetch OK. Bearer token present." : "Cookie fetch OK but at-main may be missing."
                state.cookieFromServer = [cookie: cookie, csrf: csrfVal, fetchedAt: now()]
            } else {
                msg = "Cookie server returned no cookie or localCookie."
            }
        }
    } else {
        msg = "Cookie fetch failed: HTTP ${resp.status}"
    }
    msg = msg ?: "Cookie fetch failed."
    state.lastCookieTestResult = msg
    state.lastCookieTestTime = now()
    log.info msg
}

def normalizeCookieServerUrl(url) {
    if (!url?.trim()) return url
    url = url.trim().replaceAll(/\/+$/, '')
    if (url ==~ /.*:8091$/) return url + '/cookieData'
    return url
}

def getPollStatusText() {
    def result = state.lastPollResult
    if (!result) return "Last poll: —"
    def ts = state.lastPollTime ? new Date(state.lastPollTime as Long).format("yyyy-MM-dd HH:mm") : ""
    def ok = (result == "OK")
    def names = state.lastPollThermostats ? " (${state.lastPollThermostats.join(', ')})" : ""
    return "Last poll: ${ok ? 'OK' : result}${names}${ts ? " @ ${ts}" : ""}"
}

def getCookieStatusText() {
    def hasManual = settings?.alexaCookie?.trim()
    def hasServer = settings?.cookieServerUrl?.trim()
    def source = hasManual ? "Manual cookie configured ✓" : (hasServer ? "Cookie server configured ✓" : "⚠ Not configured — set cookie or server URL")
    def testInfo = state.lastCookieTestResult
    if (!testInfo) return "Status: ${source}"
    def ts = state.lastCookieTestTime ? new Date(state.lastCookieTestTime as Long).format("HH:mm") : ""
    def ok = testInfo.contains("OK") || testInfo.contains("Bearer token present") || testInfo == "Manual cookie configured"
    def result = ok ? "OK" : (testInfo == "Not configured" ? "" : "FAIL: " + (testInfo.length() > 35 ? testInfo.take(35) + "..." : testInfo))
    return "Status: ${source}  |  Last test: ${result}${ts ? " (${ts})" : ""}"
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
                [label: "[VIR] ${name}", name: ${name}, isComponent: false],
            )
            dev.updateSetting("acDeviceName", [value: name, type: "string"])
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
		def m = (cookie =~ /(?i)(?:^|;\s*)csrf=([^;]+)/)
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
        def name = (entry.name ?: '').trim()
        def endpointId = entry.endpointId
        def mode = entry.mode?.toLowerCase()
        def temp = entry.currentTemp?.replaceAll(/[^\d.]/, '') as Double

        def child = getChildDevices().find { (it.name ?: '').toLowerCase().endsWith((name ?: '').toLowerCase()) }
        if (!child) {
            def existing = getChildDevices().collect { it.name }.join(', ')
            log.warn "No child device found for '$name'. Existing: [$existing]"
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
