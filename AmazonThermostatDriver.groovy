#include gilderman.AmazonACManagerHelper

metadata {
    definition(name: "Amazon Thermostat", namespace: "gilderman", author: "Ilia Gilderman") {
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
        attribute "supportedThermostatFanModes", "ENUM", ["off", "on", "auto"]
	}
		
	preferences {
		input name: "acDeviceName", type: "string", title: "Name as in Alexa app", description: "Must match exactly", required: true
		input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: false
	}
}

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
    sendEvent(name: "supportedThermostatFanModes", value: ["off", "on", "auto"])
	sendEvent(name: "thermostatFanMode", value: "auto")
}

def initialize() {
    device.updateSetting("acDeviceName", [value: acDeviceName, type: "string"])
    device.updateSetting("debugLogging", [value: debugLogging, type: "bool"])

    configure()
}

def setCoolingSetpoint(temp) {
    executeCommand("coolingSetpoint", temp)
    updateOperatingState()
}

def setThermostatFanMode(String mode) {
    executeCommand("thermostatFanMode", mode)
}

def updateHumidity(value) {
	logDebug("Setting humidity to ${value}")
    sendEvent(name: "humidity", value: value, unit: "%")
}