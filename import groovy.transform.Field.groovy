import groovy.transform.Field
metadata {
definition (
name: "CariCGM",
namespace: "CariCGM",
author: "Chris" )
{
capability "Refresh"
attribute "SGV", "number"
attribute "CustomTile2", "string"
}
}

def refresh() { ( sendSyncCmd() ) }

def sendSyncCmd() {
def params = [
uri: "http://stucgm.herokuapp.com/api/v1/entries/current.json",
contentType: "application/json",
requestContentType: "application/json",
]
try{
httpGet(params){response ->
if(response.status != 200) {
log.warn "Did not received valid data!"
}
else {
def jsontext = response.data

// log.debug "Received: " + jsontext
// log.debug "Cari's number is: " + jsontext[0].sgv
def SGV1 = jsontext[0].sgv
def SGV2 = "65"
def float SGV = SGV1

def SGVdir = jsontext [0].direction
// log.debug SGVdir
def int low1 = "4" as Integer
def int low2 = "3" as Integer
def int high1 = "10" as Integer
def int high2 = "15" as Integer
def SGVstate1 = "unset"
def SGVarrow = ""

// convert SGV from mg/dL to mmol/L
// display it to 1 decimal place
SGV = SGV/18
SGV = SGV.round(1)
    
// def last = device.currentValue("last_num2")
// log.debug SGV
sendEvent(name: "SGV", value: SGV, display: true , displayed: true)
if(SGV < low2) {
SGVstate1 = "VeryLow"
SGVbackground = " rgba(255, 0, 0, 0.65) }"
SGVshadow = " #DD0048 "
} else {
if(SGV < low1) {
SGVstate1 = "Low"
SGVbackground = " rgba(255, 0, 0, 0.65) }"
SGVshadow = " #DD0048 "
} else {
if(SGV > high1) {
SGVstate1 = "High"
SGVbackground = " rgba(255, 0, 0, 0.65) }"
SGVshadow = " #DD0048 "
} else {
SGVstate1 = "Normal"
SGVbackground = " #39ff14 "
SGVbackground = " rgba(0, 255, 0, 0.5) }"
SGVshadow = " #39ff14 "

                }
                }
           }
           
            if(SGVdir == "NOT COMPUTABLE") {
               SGVdir1 = 'DATA Received '
               SGVstate1 = "NO"
               SGVcolor = " #DD0048 "
               SGVshadow = " #DD0048 "
 //              SGV = 000
                //  SGVarrow = '<img src="https://192.168.10.1/singlearrowright.png"></img>'
            }
            SGVcolor = "#FFFFFF"
            if(SGVdir == "Flat") {
               SGVdir1 = 'Steady '
               SGVarrow = '<img src="https://192.168.10.1/singlearrowright.png"></img>'
            }
            if(SGVdir == "FortyFiveUp") {
               SGVdir1 = 'Rising '
                SGVarrow = '<img src="https://192.168.10.1/45uparrow.png"></img>'
            }
            if(SGVdir == "SingleUp") {
               SGVdir1 = 'Rising '
                SGVarrow = '<img src="https://192.168.10.1/singlearrowup.png"></img>'
            }
            if(SGVdir == "DoubleUp") {
               SGVdir1 = 'Rising '
                SGVarrow = '<img src="https://192.168.10.1/doublearrowup.png"></img>'
            }
            if(SGVdir == "FortyFiveDown") {
               SGVdir1 = 'Falling '
                SGVarrow = '<img src="https://192.168.10.1/45downarrow.png"></img>'
            }
            if(SGVdir == "SingleDown") {
               SGVdir1 = 'Falling '
                SGVarrow = '<img src="https://192.168.10.1/singlearrowdown.png"></img>'
            }
            if(SGVdir == "DoubleDown") {
               SGVdir1 = 'Falling '
                SGVarrow = '<img src="https://192.168.10.1/doublearrowdown.png"></img>'
            }
             sendEvent(name: "SGVstate1", value: SGVstate1, display: true , displayed: true)

            def CustomTile1Text = '<style>'

        CustomTile1Text += '.CGMInfo {'
   CustomTile1Text += ' font-size: 15px;'
        CustomTile1Text += ' color: ' + SGVcolor
        CustomTile1Text += '}'
       
        CustomTile1Text += '.CGMrow {'
        CustomTile1Text += ' font-size: 40px;'
       // CustomTile1Text += '    text-shadow: 0px 0px 0px ' + SGVshadow + ';'
        CustomTile1Text += ' color: ' + SGVcolor + ';'
        CustomTile1Text += '}'
   CustomTile1Text += '#tile-63 { background-color: ' + SGVbackground
            //+ '; text-shadow: none;}'
           
        CustomTile1Text += '</style>'
           
   CustomTile1Text += '<div class="CGMrow">'
        CustomTile1Text += '' + SGV + ' ' + SGVarrow
        CustomTile1Text += '<div class="CGMInfo">'
   CustomTile1Text += ' ' + SGVstate1
   CustomTile1Text += ' ' + SGVdir1  
   CustomTile1Text += '</div>'
   CustomTile1Text += '</div>'

   sendEvent(name:"CustomTile2", value:CustomTile1Text, descriptionText:"CustomTile2")
        }
}
}
catch (Exception e) {
log.debug "HttpGet Error: ${e}"
}
}