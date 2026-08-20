// StuCGM is a driver for the Hubitat smart home hub that allows you to access your most recent blood glucose value from your CGM (via Nightscout) directly within Hubitat. This driver returns a value in mmol/L but can also return mg/dL by removing the 2 lines as indicated in the code.

// In addition to the base attributes (SGV, SGV_state, SGV_background, SGV_shadow), this version
// emits the attributes the live dashboard depends on:
//   - SGVstate1: the plain state word (Normal / Low / VeryLow / High)
//   - CustomTile2: ready-to-render HTML for the dashboard CGM tile (tile 63)

// Based on CariCGM by 'cfunk30'. More details about their driver here: https://community.hubitat.com/t/maker-api-driver-or-somthing-simple/26769/13

import groovy.transform.Field
metadata {
  definition (
    name: "StuCGM",
    namespace: "StuCGM",
    author: "Stuart Gardoll"
  ) {
    capability "Refresh"
    attribute "SGV", "number" // SGV stands for "sensor glucose value" and is the most recent blood glucose value from the CGM
    attribute "SGV_state", "string" // This attribute holds the current state of the SGV (e.g. normal, low, very low, etc.)
    attribute "SGVstate1", "string" // Same state word, under the attribute name the dashboard and lighting rules read
    attribute "CustomTile2", "string" // Rendered HTML for the dashboard CGM tile
    attribute "SGV_background", "string" // This attribute holds the background color for the SGV
    attribute "SGV_shadow", "string" // This attribute holds the shadow color for the SGV
  }
}

def refresh() {
  // This function is called when the "refresh" capability is triggered (e.g. manually by the user or automatically on a schedule)
  ( sendSyncCmd() )
}

def sendSyncCmd() {
  // This function sends a request to the Nightscout API to retrieve the most recent SGV
  def params = [
    // These parameters define the request that will be sent to the Nightscout API
    uri: "http://stucgm.chickenkiller.com/api/v1/entries/current.json",
    contentType: "application/json",
    requestContentType: "application/json"
  ]
  try {
    // We wrap the httpGet function in a try-catch block to handle any errors that may occur
    httpGet(params) { response ->
      // This is the callback function that is executed when the response is received
      if(response.status != 200) {
        // If the response status is not 200 (OK), log a warning message
        log.warn "Did not received valid data!"
      } else {
        // If the response status is 200, parse the response data as JSON
        def jsontext = response.data
        def SGV1 = jsontext[0].sgv
        def float SGV = SGV1

        def SGVdir = jsontext[0].direction
        // These variables define the thresholds for low and high SGV values
        def int low1 = "4" as Integer
        def int low2 = "3" as Integer
        def int high1 = "10" as Integer
        def int high2 = "18" as Integer
        def SGVbackground = "rgba(255, 0, 0, 0.65)"
        def SGVshadow = "#39ff14 "
        def SGVdir1 = ''
        def SGV_state = ""
        def arrowImg = "flatarrow.png"

        // convert SGV from mg/dL to mmol/L and display it to 1 decimal place
        // remove these 2 lines if you want the value to show as mg/dL
        SGV = SGV/18
        SGV = SGV.round(1)

        // Map the Nightscout trend direction to a display word and a tile arrow image
        if(SGVdir == "Flat") {
           SGVdir1 = 'Steady '
           arrowImg = "flatarrow.png"
        }
        if(SGVdir == "FortyFiveUp") {
           SGVdir1 = 'Rising '
           arrowImg = "45uparrow.png"
        }
        if(SGVdir == "SingleUp") {
           SGVdir1 = 'Rising '
           arrowImg = "singlearrowup.png"
        }
        if(SGVdir == "DoubleUp") {
           SGVdir1 = 'Rising '
           arrowImg = "doublearrowup.png"
        }
        if(SGVdir == "FortyFiveDown") {
           SGVdir1 = 'Falling '
           arrowImg = "45downarrow.png"
        }
        if(SGVdir == "SingleDown") {
           SGVdir1 = 'Falling '
           arrowImg = "singlearrowdown.png"
        }
        if(SGVdir == "DoubleDown") {
           SGVdir1 = 'Falling '
           arrowImg = "doublearrowdown.png"
        }

        if (SGV < low2) {
           SGV_state = "VeryLow"
           SGVbackground = "rgba(255, 0, 0, 0.65)"
           SGVshadow = " #DD0048 "
        } else if (SGV < low1) {
           SGV_state = "Low"
           SGVbackground = "rgba(255, 0, 0, 0.65)"
           SGVshadow = " #DD0048 "
        } else if (SGV > high1) {
           SGV_state = "High"
           SGVbackground = "rgba(255, 0, 0, 0.65)"
           SGVshadow = " #DD0048 "
        } else {
           SGV_state = "Normal"
           SGVbackground = "rgba(0, 255, 0, 0.65)"
           SGVshadow = " #39ff14 "
        }

        // Send the SGV, SGV_state, SGVstate1, CustomTile2, SGV_background, and SGV_shadow values to Hubitat
        sendEvent(name: "SGV", value: SGV, display: true , displayed: true)
        sendEvent(name: "SGV_state", value: SGV_state, display: true , displayed: true)
        sendEvent(name: "SGVstate1", value: SGV_state, display: true , displayed: true)

        def tileHtml = "<style>.CGMInfo { font-size: 15px; color: #FFFFFF}.CGMrow { font-size: 40px; color: #FFFFFF;}#tile-63 { background-color:  ${SGVbackground} }</style>" +
                       "<div class=\"CGMrow\">${SGV} <img src=\"https://192.168.10.1/${arrowImg}\"></img><div class=\"CGMInfo\"> ${SGV_state} ${SGVdir1.trim()} </div></div>"
        sendEvent(name: "CustomTile2", value: tileHtml, display: true , displayed: true)

        sendEvent(name: "SGV_background", value: SGVbackground, display: true , displayed: true)
        sendEvent(name: "SGV_shadow", value: SGVshadow, display: true , displayed: true)
      }
    }
  }

  catch (Exception e) {
    log.debug "HttpGet Error: ${e}"
  }
}
