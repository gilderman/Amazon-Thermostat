#include gilderman.AmazonACManagerHelper

metadata {
    definition(name: "Amazon Thermostat (via alexa)", namespace: "gilderman", author: "Ilia Gilderman") {
		capability "Thermostat"
        capability "ThermostatHeatingSetpoint"
        capability "ThermostatCoolingSetpoint"
        capability "ThermostatFanMode"
		capability "RelativeHumidityMeasurement"
		capability "ThermostatMode"
        capability "ThermostatOperatingState"
        
        capability "TemperatureMeasurement"
        capability "Sensor"

		command "setHeatingSetpoint", ["number"]
        command "setCoolingSetpoint", ["number"]
        command "setThermostatMode", ["string"]
		command "setThermostatFanMode", ["string"]
		command "updateTemperature", ["number"]
		command "updateHumidity", ["number"]
        
        command "configure"
		command "initialize"
    
		attribute "heatingSetpoint", "number"
        attribute "coolingSetpoint", "number"
        
        attribute "supportedThermostatModes", "ENUM", ["off", "heat", "cool", "auto"]
	}
		
	preferences {
		input name: "acDeviceName", type: "string", title: "Name of the HVAC/Heating device", required: true
		input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: true
	}
}

/*
 * Possible Alexa thermostat commands
 *
 * ==== SET TEMPERATURE ====
 * Set the %s thermostat to %d degrees
 * Set the heat on the %s thermostat to %d degrees
 * Set the cool on the %s thermostat to %d degrees
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
 * Set the %s thermostat to cool
 * Set the %s thermostat to auto
 * Turn off the %s thermostat
 *
 * ==== FAN MODE ====
 * Set % fan mode to auto
 * Set % fan mode to circulate
 * Set % fan mode to off
 * Set % fan mode to off
 * Set % fan mode to %s
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

def installed() {
    initialize()
}

def updated() {
    initialize()
}

def configure() {
    sendEvent(name: "heatingSetpoint", value: 68)
    sendEvent(name: "coolingSetpoint", value: 75)
    sendEvent(name: "thermostatMode", value: "off")
    sendEvent(name: "supportedThermostatModes", value: ["off", "heat", "cool", "auto"])
	sendEvent(name: "thermostatFanMode", value: "auto")
}

def initialize() {
    device.updateSetting("acDeviceName", [value: acDeviceName, type: "string"])
    device.updateSetting("debugLogging", [value: debugLogging, type: "bool"])

    configure()
}

def setCoolingSetpoint(temp) {
    executeCommand("Set the cooling setpoint for the %s thermostat to %d degrees", "coolingSetpoint", temp)
    updateOperatingState()
}

def setThermostatFanMode(String mode) {
    executeCommand("Set %s fan mode to %s", "thermostatFanMode", mode)
}

def updateHumidity(value) {
	logDebug("Setting humidity to ${value}")
    sendEvent(name: "humidity", value: value, unit: "%")
}