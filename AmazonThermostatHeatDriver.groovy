metadata {
    definition(name: "Amazon Thermostat Heating (via alexa)", namespace: "gilderman", author: "Ilia Gilderman") {
		capability "Thermostat"
        capability "ThermostatHeatingSetpoint"
		capability "ThermostatMode"
        
        capability "TemperatureMeasurement"
        capability "Sensor"

		command "setHeatingSetpoint", ["number"]
        command "setThermostatMode", ["string"]
		command "updateTemperature", ["number"]
        
        command "configure"
		command "initialize"
    
		attribute "heatingSetpoint", "number"
        
        attribute "supportedThermostatModes", "ENUM", ["off", "heat", "auto"]
	}
		
	preferences {
		input name: "acDeviceName", type: "string", title: "Name of the Heating device", required: true
		input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: true
	}
}

/*
 * Possible Alexa thermostat commands
 *
 * ==== SET TEMPERATURE ====
 * Set the %s thermostat to %d degrees
 * Set the heat on the %s thermostat to %d degrees
 *
 * ==== INCREASE / DECREASE TEMPERATURE ====
 * Increase the temperature on the %s thermostat
 * Decrease the temperature on the %s thermostat
 * Increase the temperature on the %s thermostat by %d degrees
 * Decrease the temperature on the %s thermostat by %d degrees
 *
 * ==== MODE CHANGE ====
 * Set the %s thermostat to %s
 * Set the %s thermostat to heat
 * Set the %s thermostat to auto
 * Turn off the %s thermostat
 *
 * ==== QUERY / STATUS ====
 * What’s the temperature on the %s thermostat?
 * What mode is the %s thermostat in?
 * What is the %s thermostat set to?
 *
 *  ==== SPECIAL CASES ====
 * Turn on the %s thermostat
 * Turn off the %s thermostat
 *
 */

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

def configure() {
    sendEvent(name: "heatingSetpoint", value: 90)
    sendEvent(name: "thermostatMode", value: "off")
    sendEvent(name: "supportedThermostatModes", value: ["off", "heat", "auto"])
}

def initialize() {
    device.updateSetting("acDeviceName", [value: acDeviceName, type: "string"])
    device.updateSetting("debugLogging", [value: debugLogging, type: "bool"])

    configure()
}

def executeCommand(cmd, state, value) {
    sendEvent(name: state, value:value)
    sendAlexaCommand(cmd, value)
}

def setHeatingSetpoint(temp) {
    executeCommand("Set the heating setpoint for the %s thermostat to %d degrees", "heatingSetpoint", temp)
}

def setThermostatMode(mode) {
	executeCommand("Set the %s thermostat to %s", "thermostatMode", mode)
}

def updateTemperature(value) {
    logDebug("Setting temperature to ${value}°")
    sendEvent(name: "temperature", value: value, unit: "°F") // or "°C"
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
