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
        input name: "autoOffHours", type: "number", title: "Auto-Off Time (hours)", description: "Turn off after this many hours", defaultValue: 3, range: "0..24"
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

