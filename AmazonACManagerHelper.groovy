library (
    name: "AmazonACManagerHelper",
    namespace: "gilderman",
    author: "Ilia Gilderman",
    description: "Helper for AmazonACManager intergration",
    category: "Utility",
    importUrl: "https://raw.githubusercontent.com/gilderman/utec-lock/main/libraries/AmazonACManagerHelper.groovy",
    documentationLink: ""
)

void logDebug(String msg) {
    if (debugLogging) {
        log.debug msg
    }
}

def executeCommand(cmd, state, value) {
    sendEvent(name: state, value:value)
    sendAlexaCommand(cmd, value)
}

def setHeatingSetpoint(temp) {
    executeCommand("Set the heating setpoint for the %s thermostat to %d degrees", "heatingSetpoint", temp)
    updateOperatingState()
}

def setThermostatMode(mode) {
    logDebug "AC ManagerHelper: Set thermostat mode ${mode}"
	executeCommand("Set the %s thermostat to %s", "thermostatMode", mode)
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

def sendAlexaCommand(String cmd, param = null) {
    def dev = parent?.getSelectedSpeachDevice()
    if (dev) {
        logDebug "Command ${cmd}"
        logDebug "name ${acDeviceName}"
        logDebug "param ${param}"
        
		def fullCmd = String.format(cmd, acDeviceName, param)
        logDebug "Calling '${fullCmd}' on device ${dev.displayName}"
		dev.voiceCmdAsText(fullCmd)
        try {
        } catch (Exception e) {
            log.warn "Failed to run '${fullCmd}' on ${dev.displayName}: ${e.message}"
        }
    } else {
        log.warn "No device with speechSynthesis capabilty selected"
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

