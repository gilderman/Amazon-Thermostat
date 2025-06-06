definition(
    name: "Amazon AC Manager",
    namespace: "gilderman",
    author: "Ilia Gilderman",
    description: "Creates child devices using a custom driver",
    category: "My Apps",
    iconUrl: "https://raw.githubusercontent.com/gilderman/AmazonAC/temp.svgh",
    iconX2Url: "",
	importUrl: "https://raw.githubusercontent.com/gilderman/AmazonAC/main/apps/AmazonACManager.groovy",
    oauth: false,
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
        }
        
        section("Create ACs") {
            input "createButton", "button", title: "Create Devices"
        }
    }
}

def installed() {
    initialize()
}

def updated() {
    initialize()
}

def initialize() {
    log.debug "App initialized"
}

def appButtonHandler(btn) {
    if (btn == "createButton") {
        createChildDevices()
    }
}

def getSelectedSpeachDevice() {
    return settings.selectedSpeachDevice
}

def createChildDevices() {
    def deviceNames = ["Rec Room", "Main Hall", "Guest Bedroom", "Office", "Master Bedroom", "Sheli's Bedroom", "Tami's Bedroom", "Neta's Bedroom"] 
	def driverName = "Control Amazon Thermostat through Alexa commands"
  
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
            dev.initialize(name)
        } else {
            log.info "⏩ Device '${name}' already exists (DNI: ${dni}) — skipping"
        }
    }
}
