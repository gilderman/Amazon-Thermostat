metadata {
    definition(name: "Amazon Thermostat (via alexa)", namespace: "gilderman", author: "Ilia Gilderman") {
		capability "Thermostat"
        capability "ThermostatHeatingSetpoint"
        capability "ThermostatCoolingSetpoint"
        capability "ThermostatFanMode"
		capability "RelativeHumidityMeasurement"
		capability "ThermostatMode"
        
        capability "TemperatureMeasurement"
        capability "Sensor"

		command "setHeatingSetpoint", ["number"]
        command "setCoolingSetpoint", ["number"]
        command "setThermostatMode", ["string"]
		command "setThermostatFanMode", ["string"]
		command "updateTemperature", ["number"]
		command "updateHumidity", ["number"]
    
		attribute "heatingSetpoint", "number"
        attribute "coolingSetpoint", "number"
	}
		
	preferences {
		input name: "acDeviceName", type: "string", title: "Name of the HVAC/Heating device", required: true
		input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: true
	}
}


// ==== SET TEMPERATURE ====
final String SET_TO_SPECIFIC_TEMP = "Set the %s thermostat to %d degrees"
final String SET_HEATING_TO = "Set the heat on the %s thermostat to %d degrees"
final String SET_COOLING_TO = "Set the cool on the %s thermostat to %d degrees"

// ==== INCREASE / DECREASE TEMPERATURE ====
final String INCREASE_TEMP = "Increase the temperature on the %s thermostat"
final String DECREASE_TEMP = "Decrease the temperature on the %s thermostat"
final String INCREASE_TEMP_BY = "Increase the temperature on the %s thermostat by %d degrees"
final String DECREASE_TEMP_BY = "Decrease the temperature on the %s thermostat by %d degrees"

// ==== MODE CHANGE ====
final String SET_MODE = "Set the %s thermostat to %s"
final String SET_MODE_HEAT = "Set the %s thermostat to heat"
final String SET_MODE_COOL = "Set the %s thermostat to cool"
final String SET_MODE_AUTO = "Set the %s thermostat to auto"
final String SET_MODE_OFF = "Turn off the %s thermostat"

// ==== FAN MODE ====
final String SET_FAN_AUTO = "Set % fan mode to auto"
final String SET_FAN_CIRCULATE = "Set % fan mode to circulate"
final String SET_FAN_OFF = "Set % fan mode to off"
final String SET_FAN_ON = "Set % fan mode to off"
final String SET_FAN_MODE = "Set % fan mode to %s"

// ==== QUERY / STATUS ====
final String QUERY_TEMPERATURE = "What’s the temperature on the %s thermostat?"
final String QUERY_MODE = "What mode is the %s thermostat in?"
final String QUERY_SETPOINT = "What is the %s thermostat set to?"

// ==== SPECIAL CASES ====
final String TURN_THERMOSTAT_ON = "Turn on the %s thermostat"
final String TURN_THERMOSTAT_OFF = "Turn off the %s thermostat"

private void logDebug(String msg) {
    if (debugLogging) {
        log.debug msg
    }
}

def installed() {
    initialize()
}

def updated() {
    initialize()
}

def initialize() {
    device.updateSetting("acDeviceName", [value: acDeviceName, type: "string"])

    sendEvent(name: "heatingSetpoint", value: 68)
    sendEvent(name: "coolingSetpoint", value: 75)
    sendEvent(name: "thermostatMode", value: "off")
	sendEvent(name: "thermostatFanMode", value: "auto")
}

def setHeatingSetpoint(temp) {
    sendEvent(name: "heatingSetpoint", value: temp)
	sendAlexaCommand(SET_HEATING_TO, temp)
}

def setCoolingSetpoint(temp) {
    sendEvent(name: "coolingSetpoint", value: temp)
	sendAlexaCommand(SET_COOLING_TO, temp)
}

def setThermostatMode(mode) {
    sendEvent(name: "thermostatMode", value: mode)
	sendAlexaCommand(SET_MODE, mode)
}

def setThermostatFanMode(String mode) {
    logDebug("Setting thermostat fan mode to $mode")
    // validation is optional but recommended
    def validModes = ["auto", "on", "circulate"]
    if (validModes.contains(mode)) {
        sendEvent(name: "thermostatFanMode", value: mode)
    } else {
        log.warn "Invalid fan mode: $mode"
    }
}

def updateHumidity(value) {
	logDebug("Setting humidity to ${value}°")
    sendEvent(name: "humidity", value: value, unit: "%")
}

def updateTemperature(value) {
    logDebug("Setting temperature to ${value}°")
    sendEvent(name: "temperature", value: value, unit: "°F") // or "°C"
}

def sendAlexaCommand(cmd, param = null) {
    def dev = parent?.getSelectedSpeachDevice()
    if (dev) {
		def fullCmd = String.format(cmd, settings.acDeviceName, param)
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
