library (
    name: "AmazonACManagerHelper",
    namespace: "gilderman",
    author: "Ilia Gilderman",
    description: "Helper for AmazonACManager intergration",
    category: "Utility",
    importUrl: "https://raw.githubusercontent.com/gilderman/Amazon-Thermostat/main/AmazonACManagerHelper.groovy",
    documentationLink: ""
)

void logDebug(String msg) {
    if (debugLogging || settings?.debugLog) {
        log.debug msg
    }
}

def executeCommand(String state, value) {
    sendEvent(name: state, value: value)
    sendAlexaCommand(state, value)
}

def setHeatingSetpoint(temp) {
    executeCommand("heatingSetpoint", temp)
    updateOperatingState()
}

def setThermostatMode(mode) {
    logDebug "AC ManagerHelper: Set thermostat mode ${mode}"
	executeCommand("thermostatMode", mode)
    updateOperatingState()
    
    logDebug "Mode = ${mode} autoOffHours=${autoOffHours}"
    
    if (mode in ["heat", "cool", "auto"] && autoOffHours?.toInteger() > 0) {
        def seconds = autoOffHours.toInteger() * 3600
        runIn(seconds, autoTurnOff)
        logDebug "Auto-off scheduled in ${autoOffHours} hour(s)"
    } else {
        unschedule("autoTurnOff")
        logDebug "Auto-off canceled"
    }
}

def autoTurnOff() {
    logDebug "Auto-turning off thermostat"
    setThermostatMode("off")
}

def updateTemperature(value) {
    logDebug("Setting temperature to ${value}°")
    sendEvent(name: "temperature", value: value, unit: "°F") // or "°C"
    updateOperatingState()
}

def sendAlexaCommand(String state, value) {
    def command = stateToAlexaCommand(state)
    if (!command) return
    if (parent?.sendCommandToAlexa(acDeviceName, command, value)) {
        logDebug "Sent to Alexa: ${command} = ${value} for ${acDeviceName}"
    } else {
        log.warn "Failed to send ${command} for ${acDeviceName}. Check cookie/config in Amazon AC Manager."
    }
}

def stateToAlexaCommand(String state) {
    switch (state) {
        case 'heatingSetpoint': return 'setHeatingSetpoint'
        case 'coolingSetpoint': return 'setCoolingSetpoint'
        case 'thermostatMode': return 'setThermostatMode'
        case 'thermostatFanMode': return 'setThermostatFanMode'
        default: return null
    }
}

def updateOperatingState() {
    def currentTemp = device.currentValue("temperature") as Double
    def heatSetpoint = device.currentValue("heatingSetpoint") as Double
    def coolSetpoint = device.currentValue("coolingSetpoint") as Double
    def mode = device.currentValue("thermostatMode")
    def state = device.currentValue("thermostatOperatingState")

    String newState = "idle"
    //logDebug "AC ManagerHelper: Operating state before change to mode=$mode state=$state (Current: $currentTemp, Heat: $heatSetpoint, Cool: $coolSetpoint)"

    switch (mode) {
        case "heat":
            if (currentTemp < heatSetpoint) {
                newState = "heating"
            }
            break
        case "cool":
            if (currentTemp > coolSetpoint) {
                newState = "cooling"
            }
            break
        case "auto":
            if (currentTemp < heatSetpoint) {
                newState = "heating"
            } else if (currentTemp > coolSetpoint) {
                newState = "cooling"
            }
            break
        case "off":
        default:
            newState = "idle"
    }

    // Only send event if state has changed
    //logDebug "AC ManagerHelper: Operating state after change to mode=$mode state=$newState (Current: $currentTemp, Heat: $heatSetpoint, Cool: $coolSetpoint)"
    if (state != newState) {
        sendEvent(name: "thermostatOperatingState", value: newState)
        //logDebug "Operating state changed to $newState (Current: $currentTemp, Heat: $heatSetpoint, Cool: $coolSetpoint)"
    }
}

// ─── Alexa API (used by app when helper is included) ─────────────────────────

def COOKIE_TTL_MS = 5 * 24 * 60 * 60 * 1000L  // 5 days – cookie good for many days, re-fetch only when stale

def getAlexaHeaders() {
    def cookie = null
    def csrf = ''
    if (settings?.alexaCookie?.trim()) {
        cookie = settings.alexaCookie.trim()
        def m = (cookie =~ /(?i)(?:^|;\s*)csrf=([^;]+)/)
        csrf = m.find() ? m.group(1).trim() : ''
    } else if (state?.cookieFromServer?.cookie) {
        def fetchedAt = state.cookieFromServer?.fetchedAt as Long ?: 0L
        if (fetchedAt <= 0 || (now() - fetchedAt) < COOKIE_TTL_MS) {
            cookie = state.cookieFromServer.cookie
            csrf = state.cookieFromServer?.csrf ?: ''
        }
        // else: cookie stale (>5 days) – will trigger re-fetch on next poll
    }
    if (!cookie?.trim()) return null
    return [
        'Cookie': cookie,
        'csrf': csrf,
        'Accept': 'application/json',
        'Content-Type': 'application/json',
        'User-Agent': 'Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36',
        'Referer': 'https://alexa.amazon.com/',
        'Origin': 'https://alexa.amazon.com'
    ]
}

def scheduleNextPollIfNeeded() {
    if (settings?.useFastPoll) runIn(30, 'pollAlexaThermostats')
}

def pollAlexaThermostats() {
    logDebug "[Poll] Starting. t0=${now()}"
    def proxyUrl = settings?.downchannelServerUrl?.trim()?.replaceAll(/\/+$/, '')
    if (proxyUrl) {
        def timeoutSec = getAlexaApiTimeout()
        logDebug "[Poll] Using downchannel proxy: ${proxyUrl}/poll (timeout ${timeoutSec}s)"
        asynchttpGet('proxyPollCallback', [uri: "${proxyUrl}/poll", timeout: timeoutSec])
        return
    }
    def headers = getAlexaHeaders()
    if (headers) {
        logDebug "[Poll] Cookie from cache/config → list request"
        doAlexaListRequest(headers)
        return
    }
    def cookieUrl = settings?.cookieServerUrl?.trim()
    if (cookieUrl) {
        cookieUrl = normalizeCookieServerUrl(cookieUrl)
        state.cookieFetchStart = now()
        logDebug "[Poll] Fetching cookie from ${cookieUrl}"
        asynchttpGet('cookieFetchedCallback', [uri: cookieUrl, timeout: 30])
        return
    }
    logDebug "[Poll] No cookie source. Set manual cookie or cookie server URL."
}

def proxyPollCallback(resp, data) {
    def status = 0
    def json = null
    try { status = resp?.status ?: 0 } catch (e) { status = 0 }
    logDebug "[Poll] Proxy callback: HTTP ${status}"
    if (status != 200) {
        log.warn "[Poll] Proxy poll failed: HTTP ${status}"
        state.lastPollResult = "Proxy failed: HTTP ${status}"
        state.lastPollTime = now()
        scheduleNextPollIfNeeded()
        return
    }
    try { json = resp?.json } catch (e) {
        log.warn "[Poll] Proxy parse error: ${e.message}"
        state.lastPollResult = "Proxy parse error"
        state.lastPollTime = now()
        scheduleNextPollIfNeeded()
        return
    }
    def payload = json?.thermostats ?: []
    if (payload) {
        state.lastPollResult = "OK"
        state.lastPollTime = now()
        state.lastPollThermostats = payload.collect { it.name }
        updateThermostats(payload)
    } else {
        state.lastPollResult = "No data from proxy"
        state.lastPollTime = now()
    }
    scheduleNextPollIfNeeded()
}

def cookieFetchedCallback(resp, data) {
    def t0 = state.cookieFetchStart as Long ?: now()
    logDebug "[Poll] Cookie fetch callback: HTTP ${resp.status} (elapsed ${(now() - t0) / 1000}s)"
    if (resp.status != 200) {
        log.warn "Cookie server request failed: ${resp.status}"
        scheduleNextPollIfNeeded()
        return
    }
    def json = resp.json
    if (!json) {
        try { json = new groovy.json.JsonSlurper().parseText(resp.data ?: '{}') }
        catch (e) { log.warn "Cookie server parse error: ${e.message}"; scheduleNextPollIfNeeded(); return }
    }
    def cd = json.cookieData ?: json
    def localCookie = cd.localCookie ?: cd.cookie
    def csrfVal = cd.csrf ?: ''
    if (!localCookie) {
        log.warn "Cookie server returned no cookie or localCookie"
        scheduleNextPollIfNeeded()
        return
    }
    state.cookieFromServer = [cookie: localCookie, csrf: csrfVal, fetchedAt: now()]
    log.info "Cookie cached (valid ~5 days). → list request"
    doAlexaListRequest(getAlexaHeaders())
}

def getAlexaApiTimeout() {
    def sec = (settings?.alexaApiTimeoutSeconds as Integer) ?: 30
    return Math.max(15, Math.min(120, sec))
}

def doAlexaListRequest(headers) {
    def timeoutSec = getAlexaApiTimeout()
    logDebug "[Poll] List request -> alexa.amazon.com (timeout ${timeoutSec}s)"
    state.pollListStart = now()
    def query = '{"query":"{ listEndpoints(listEndpointsInput: {}) { endpoints { id friendlyName displayCategories { primary { value } } } } }"}'
    asynchttpPost('alexaListCallback', [
        uri: 'https://alexa.amazon.com', path: '/nexus/v1/graphql',
        headers: headers, body: query, timeout: timeoutSec
    ], [step: 'list'])
}

def alexaListCallback(resp, data) {
    def status = 0
    def respData = null
    def json = null
    try { status = resp?.status ?: 0 } catch (e) { status = 0 }
    try { respData = resp?.data } catch (e) { respData = null }
    try { json = resp?.json } catch (e) {
        def snippet = respData ? respData.toString().take(150) : ''
        log.warn "[Poll] List failed: HTTP ${status}. No JSON (${e.message}). Body: ${snippet ?: 'empty'}"
        state.lastPollResult = "List failed: HTTP ${status} (no JSON)"
        state.lastPollTime = now()
        scheduleNextPollIfNeeded()
        return
    }
    def elapsed = state.pollListStart ? ((now() - (state.pollListStart as Long)) / 1000) : null
    logDebug "[Poll] List callback: HTTP ${status}${elapsed != null ? " (${elapsed}s)" : ''}"
    if (status != 200 || !json) {
        state.lastPollResult = "List failed: HTTP ${status}"
        state.lastPollTime = now()
        def snippet = ''
        try { if (respData) snippet = ", data=${respData.toString().take(200)}" } catch (e2) { }
        log.warn "[Poll] List failed: status=${status}${snippet}"
        scheduleNextPollIfNeeded(); return
    }
    def endpoints = json?.data?.listEndpoints?.endpoints ?: []
    def thermoCategories = ['THERMOSTAT', 'TEMPERATURE_SENSOR']
    def thermostats = endpoints.findAll { ep ->
        def cat = ep.displayCategories?.primary?.value ?: ''
        thermoCategories.contains(cat.toUpperCase()) || (ep.friendlyName ?: '').toLowerCase().contains('thermostat')
    }
    if (thermostats.isEmpty()) {
        state.lastPollResult = "No thermostats in Alexa"
        state.lastPollTime = now()
        logDebug "No thermostats found in Alexa"; scheduleNextPollIfNeeded(); return
    }
    def targetNames = (settings?.thermostatNames ?: '').split(',').collect { it.trim().toLowerCase() }.findAll { it }
    if (targetNames) thermostats = thermostats.findAll { t -> targetNames.contains((t.friendlyName ?: '').toLowerCase()) }
    if (thermostats.isEmpty()) thermostats = endpoints.findAll { ep -> thermoCategories.contains((ep.displayCategories?.primary?.value ?: '').toUpperCase()) }
    if (targetNames && thermostats.isEmpty()) {
        state.lastPollResult = "No match for: ${targetNames.join(', ')}"
        state.lastPollTime = now()
        scheduleNextPollIfNeeded(); return
    }
    if (thermostats.isEmpty()) { scheduleNextPollIfNeeded(); return }
    def ids = thermostats.collect { "\"${it.id}\"" }.join(', ')
    def timeoutSec = getAlexaApiTimeout()
    logDebug "[Poll] State request -> alexa.amazon.com (timeout ${timeoutSec}s)"
    state.pollStateStart = now()
    def stateQuery = "{\"query\":\"{ listEndpoints(listEndpointsInput: { endpointIds: [${ids}] }) { endpoints { id features { name properties { name ... on ThermostatMode { value } ... on Setpoint { value { value scale } } ... on TemperatureSensor { value { value scale } } } } } } }\"}"
    asynchttpPost('alexaStateCallback', [
        uri: 'https://alexa.amazon.com', path: '/nexus/v1/graphql',
        headers: getAlexaHeaders(), body: stateQuery, timeout: timeoutSec
    ], [thermostats: thermostats])
}

def alexaStateCallback(resp, data) {
    def status = 0
    def json = null
    def respData = null
    try { status = resp?.status ?: 0 } catch (e) { status = 0 }
    try { respData = resp?.data } catch (e) { respData = null }
    try { json = resp?.json } catch (e) {
        def snip = respData ? respData.toString().take(150) : 'no body'
        log.warn "[Poll] State callback: ${e.message}. HTTP ${status}, body: ${snip}..."
        state.lastPollResult = "State failed: HTTP ${status} (no JSON)"
        state.lastPollTime = now()
        scheduleNextPollIfNeeded()
        return
    }
    def elapsed = state.pollStateStart ? ((now() - (state.pollStateStart as Long)) / 1000) : null
    logDebug "[Poll] State callback: HTTP ${status}${elapsed != null ? " (${elapsed}s)" : ''}"
    if (status != 200 || !json) {
        state.lastPollResult = "State failed: HTTP ${status}"
        state.lastPollTime = now()
        def snippet = ''
        try { if (respData) snippet = ", data=${respData.toString().take(200)}" } catch (e2) { }
        log.warn "[Poll] State failed: status=${status}${snippet}"
        scheduleNextPollIfNeeded()
        return
    }
    def endpoints = json?.data?.listEndpoints?.endpoints ?: []
    def thermostats = data.thermostats ?: []
    def payload = []
    endpoints.each { ep ->
        def state = [mode: 'off', currentTemp: null, target: null, lowerSetpoint: null, upperSetpoint: null]
        (ep.features ?: []).each { feat ->
            (feat.properties ?: []).each { prop ->
                def v = prop.value
                def val = (v instanceof Map) ? (v.value != null ? v.value : v) : v
                def scale = (v instanceof Map) ? v.scale : null
                def numVal = (val instanceof Number) ? val : (val != null ? ({ def s = val.toString().replaceAll(/[^\d.]/, ''); s ? s as BigDecimal : null }()) : null)
                def display = val != null ? (scale ? "${val}° ${scale?.take(1)}" : "${val}") : null
                switch (prop.name) {
                    case 'thermostatMode': state.mode = (val ?: 'OFF').toString().toLowerCase(); break
                    case 'temperature': state.currentTemp = display; break
                    case 'targetSetpoint': state.target = display; if (numVal != null) { state.lowerSetpoint = numVal; state.upperSetpoint = numVal }; break
                    case 'lowerSetpoint': state.lowerSetpoint = numVal; break
                    case 'upperSetpoint': state.upperSetpoint = numVal; break
                }
            }
        }
        if (state.lowerSetpoint != null && state.upperSetpoint != null) state.target = "${state.lowerSetpoint}°–${state.upperSetpoint}°"
        def t = thermostats.find { it.id == ep.id }
        if (t) payload << [name: t.friendlyName, endpointId: ep.id, mode: state.mode, currentTemp: state.currentTemp, target: state.target, lowerSetpoint: state.lowerSetpoint, upperSetpoint: state.upperSetpoint]
    }
    if (payload) {
        state.lastPollResult = "OK"
        state.lastPollTime = now()
        state.lastPollThermostats = payload.collect { it.name }
        updateThermostats(payload)
    } else {
        state.lastPollResult = "No data"
        state.lastPollTime = now()
    }
    scheduleNextPollIfNeeded()
}

def sendCommandToAlexa(String deviceName, String command, value) {
    def headers = getAlexaHeadersForCommand()
    if (!headers) {
        log.warn "No Alexa cookie for command. Run Test Cookie Fetch then try again."
        return false
    }
    def child = getChildDevices().find { (it.label ?: '').toLowerCase().endsWith((deviceName ?: '').toLowerCase()) }
    def endpointId = child?.getDataValue('endpointId')
    if (!endpointId) { log.warn "No endpointId for '$deviceName' yet. Poll once to discover."; return false }
    def payload = null
    def featureOp = null
    switch (command) {
        case 'setHeatingSetpoint': payload = [targetSetpoint: [value: value as Number, scale: 'FAHRENHEIT']]; featureOp = 'setTargetSetpoint'; break
        case 'setCoolingSetpoint': payload = [targetSetpoint: [value: value as Number, scale: 'FAHRENHEIT']]; featureOp = 'setTargetSetpoint'; break
        case 'setThermostatMode':
            def mode = ['HEAT','COOL','AUTO','ECO','OFF'].contains(value?.toString()?.toUpperCase()) ? value.toString().toUpperCase() : 'OFF'
            payload = [thermostatMode: mode]; featureOp = 'setThermostatMode'; break
        case 'setThermostatFanMode': return false
        default: return false
    }
    def reqBody = groovy.json.JsonOutput.toJson([
        query: "mutation SetFeatures(\$input: SetEndpointFeaturesInput!) { setEndpointFeatures(setEndpointFeaturesInput: \$input) { featureControlResponses { endpointId } errors { message } } }",
        variables: [input: [featureControlRequests: [[endpointId: endpointId, featureName: 'thermostat', featureOperationName: featureOp, payload: payload]]]]
    ])
    try {
        httpPost([uri: 'https://alexa.amazon.com', path: '/nexus/v1/graphql', headers: headers, body: reqBody, timeout: getAlexaApiTimeout()]) { resp ->
            if (resp.status >= 200 && resp.status < 300) logDebug "Alexa command: $command $value for $deviceName"
            else log.warn "Alexa API error: ${resp.status} ${resp.data}"
        }
        return true
    } catch (e) { log.warn "Alexa command failed: ${e.message}"; return false }
}


