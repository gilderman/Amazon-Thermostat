#include gilderman.AmazonACManagerHelper

metadata {
    definition(name: "Amazon Thermostat Heating (via alexa)", namespace: "gilderman", author: "Ilia Gilderman") {
		capability "Thermostat"
        capability "ThermostatHeatingSetpoint"
		capability "ThermostatMode"
        capability "ThermostatOperatingState"
        
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

