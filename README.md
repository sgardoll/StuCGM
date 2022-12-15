StuCGM is a driver for the Hubitat smart home hub that allows users to access their most recent blood glucose value from a continuous glucose monitor (CGM) via Nightscout. The driver returns the value in mmol/L, but it can also return the value in mg/dL by removing a few lines of code. The driver also includes a few thresholds for low and high blood sugar levels, and it has a flag that indicates whether to use mmol/L or mg/dL.

This driver allows you to easily monitor your blood glucose levels in real-time and build automations based on the data. Simply add your Nightscout details into the code at the places indicated and you'll be able to access your latest readings directly from within Hubitat.



Heavily ased on the work of 'cfunk30' and the CariCGM project. More details about their driver here: https://community.hubitat.com/t/maker-api-driver-or-somthing-simple/26769/13
