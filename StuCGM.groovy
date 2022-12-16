// StuCGM is a driver for the Hubitat smart home hub that allows you to access your most recent blood glucose value from your CGM (via Nightscout) directly within Hubitat. This driver returns a value in mmol/L but can also return mg/dL by removing the 2 lines as indicated in the code.

// Based on CariCGM by 'cfunk30'. More details about their driver here: https://community.hubitat.com/t/maker-api-driver-or-somthing-simple/26769/13

import groovy.transform.Field
metadata {
definition (
name: "StuCGM",
namespace: "StuCGM",
author: "Stuart Gardoll" )
{
capability "Refresh"
attribute "SGV", "number"
attribute "SGV_state", "string"
}
}

def refresh() { ( sendSyncCmd() ) }

// Change [NIGHTSCOUT_URL] to your own Nightscout URL
def sendSyncCmd() {
def params = [
uri: "http://stucgm.chickenkiller.com/api/v1/entries/current.json",
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
def SGV1 = jsontext[0].sgv
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
// remove these 2 lines if you want the value to show as mg/dL
SGV = SGV/18
SGV = SGV.round(1)
    

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
           
             sendEvent(name: "SGV_state", value: SGVstate1, display: true , displayed: true)
            if(SGVdir == "NOT COMPUTABLE") {
               SGVdir1 = 'DATA Received '
               SGVstate1 = "NO"
               SGVcolor = " #DD0048 "
               SGVshadow = " #DD0048 "
            }
            SGVcolor = "#FFFFFF"
            if(SGVdir == "Flat") {
               SGVdir1 = 'Steady '
            }
            if(SGVdir == "FortyFiveUp") {
               SGVdir1 = 'Rising '
            }
            if(SGVdir == "SingleUp") {
               SGVdir1 = 'Rising '
            }
            if(SGVdir == "DoubleUp") {
               SGVdir1 = 'Rising '
            }
            if(SGVdir == "FortyFiveDown") {
               SGVdir1 = 'Falling '
            }
            if(SGVdir == "SingleDown") {
               SGVdir1 = 'Falling '
            }
            if(SGVdir == "DoubleDown") {
               SGVdir1 = 'Falling '
            }
        }
}
}
catch (Exception e) {
log.debug "HttpGet Error: ${e}"
}
}
