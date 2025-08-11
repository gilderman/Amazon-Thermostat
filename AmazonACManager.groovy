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
        section("Select Speach Device") {
            input name: "selectedSpeachDevice", type: "capability.speechSynthesis", title: "Select Devices", multiple: false, required: true
			input name: "thermostatNames", type: "string", title: "Names of the thermostats as they appear in the Alexa app, separated by comma", required: true
            input name: "thermostatHeatNames", type: "string", title: "Names of the thermostats floor heaters as they appear in the Alexa app, separated by comma", required: true
        }
        
        section("Create ACs") {
            input "createButton", "button", title: "Create Devices"
        }
		
		section("App URLs") {
            paragraph "Cloud ${getFullApiServerUrl()}"
            paragraph "Local ${getLocalApiServerUrl()}"
            paragraph "AccessToken ${state.accessToken}"
       }
    }
}

mappings {
    path("/statusreportfromtheapp") {
        action: [
            POST: "statusCallback"
        ]
    }
}

def installed() {
    log.debug "Installed with settings: ${settings}"
    if (settings?.thermostatNames && settings?.thermostatHeatNames) {
        initialize()
    }
}

def updated() {
    log.debug "Updated with settings: ${settings}"
    if (settings?.thermostatNames && settings?.thermostatHeatNames) {
        unsubscribe()
        unschedule()
        initialize()
    }
}

def initialize() {
    log.debug "App initialized"

    def count1 = thermostatNames?.split(/\s*,\s*/)?.size() ?: 0
    def count2 = thermostatHeatNames?.split(/\s*,\s*/)?.size() ?: 0
    state.totalDevices = count1 + count2

    log.debug "Total devices: ${state.totalDevices}"
}

def appButtonHandler(btn) {
    if (btn == "createButton") {
        createChildDevices()
		
		if (state.accessToken == null)
			createAccessToken()
    }
}

def getSelectedSpeachDevice() {
    return settings.selectedSpeachDevice
}

def createChildDevices() {
    createChildDevicesForDriver(thermostatNames, "Amazon Thermostat (via alexa)")
    createChildDevicesForDriver(thermostatHeatNames, "Amazon Thermostat Heating (via alexa)")
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
            dev.initialize()
        } else {
            log.info "⏩ Device '${name}' already exists (DNI: ${dni}) — skipping"
        }
    }
}

def statusCallback() {
	def payload = request.JSON
    log.debug "Received callback from the app: ${payload}"  
	
    
    if (payload.size == state.totalDevices)
    	updateThermostats(payload)
    else {
        log.warn "Bad payload recieved ${payload.size} ${state.totalDevices}"
    }
}

def updateThermostats(List<Map> dataList) {
    dataList.each { entry ->
        def name = entry.name
        def mode = entry.mode?.toLowerCase()
        def temp = entry.currentTemp?.replaceAll(/[^\d.]/, '') as Double

        def child = getChildDevices().find {  it.label.endsWith(name) }
        if (!child) {
            log.warn "No child device found with name '$name'"
            return
        }

        log.debug "Updating '$name': mode=$mode, temp=$temp, raw target=${entry.target}"

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
                def range = entry.target?.findAll(/\d+/)
                if (range?.size() == 2) {
                    def low = range[0] as Double
                    def high = range[1] as Double
                    child.sendEvent(name: "heatingSetpoint", value: low)
                    child.sendEvent(name: "coolingSetpoint", value: high)
                    log.debug "Auto mode range: heat=$low, cool=$high"
                } else {
                    log.warn "Could not parse auto mode range for '$name': ${entry.target}"
                }
                break
            case "off":
                // no setpoints updated
                break
            default:
                log.warn "Unknown mode: $mode"
        }
    }
}
