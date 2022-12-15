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
attribute "CustomTile2", "string"
}
}

def refresh() { ( sendSyncCmd() ) }

// URL for API endpoint
def API_URL = "http://stucgm.herokuapp.com/api/v1/entries/current.json"

// Thresholds for low and high blood sugar levels
def LOW_THRESHOLD_1 = 4
def LOW_THRESHOLD_2 = 3
def HIGH_THRESHOLD_1 = 10
def HIGH_THRESHOLD_2 = 15

// Flag to indicate whether to use mmol/L or mg/dL
def USE_MMOL_PER_L = true

def sendSyncCmd() {
  // Send request to API endpoint
  def params = [
    uri: API_URL,
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
        // Convert SGV from mg/dL to mmol/L if needed
        if (USE_MMOL_PER_L) {
          SGV = SGV/18
        }
        // Display SGV to 1 decimal place
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

        sendEvent(name: "CustomTile2", value: SGVarrow, display: true, displayed: true)
        sendEvent(name: "CustomTile3", value: SGV, display: true, displayed: true)
        sendEvent(name: "CustomTile4", value: SGVdir1, display: true, displayed: true)
        sendEvent(name: "CustomTile5", value: SGVstate1, display: true, displayed: true)
      }
    }
  }
  catch (e) {
    log.error "Error: " + e
  }
}
