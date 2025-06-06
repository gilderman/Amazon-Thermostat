metadata {
    definition(name: "Amazon Thermostat (via alexa)", namespace: "gilderman", author: "Ilia Gilderman") {
        capability "Switch"
        capability "TemperatureMeasurement"

        command "setTemp", ["number"]
    }

    preferences {
        input name: "acDeviceName", type: "string", title: "Name of the AC device in Alexa", required: true
    }
}

def installed() {
    log.debug "Installed"
    initialize()
}

def initialize(String acDeviceName) {
    device.updateSetting("acDeviceName", [value: acDeviceName, type: "string"])
}

def updated() {
    log.debug "Updated"
    initialize()
}

def initialize() {
    if (!device.currentValue("switch")) sendEvent(name: "switch", value: "off")
    if (!device.currentValue("temperature")) sendEvent(name: "temperature", value: 70)
}

def on() {
    log.debug "Device turned ON"
    sendEvent(name: "switch", value: "on")
    controlOtherDevice("on")
}

def off() {
    log.debug "Device turned OFF"
    sendEvent(name: "switch", value: "off")
    controlOtherDevice("off")
}

def setTemp(temp) {
    log.debug "Setting temperature to ${temp}"
    sendEvent(name: "temperature", value: temp)
    state.temp = temp
}

def controlOtherDevice(cmd) {
    def dev = parent?.getSelectedSpeachDevice()
  
    if (dev) {
        log.debug "Calling '${cmd}' on device ${dev.displayName}"
        try {
            if (cmd == "on") {
                dev.voiceCmdAsText("set ${settings.acDeviceName} thermostat to heat")
                dev.voiceCmdAsText("set ${settings.acDeviceName} thermostat temperature to ${device.currentValue("temperature")}")
            }
            else {
                dev.voiceCmdAsText("set ${settings.acDeviceName} thermostat to off")
            }
        } catch (Exception e) {
            log.warn "Failed to run '${cmd}' on ${dev}: ${e.message}"
        }
    } else {
        log.warn "No device with speechSynthesis capabilty selected"
    }
}
