# StuCGM

Two Hubitat components for getting continuous glucose monitor (CGM) data into your smart home.

| File | Type | What it does |
| --- | --- | --- |
| `StuCGM.groovy` | Driver | Exposes your latest blood glucose value from Nightscout as a Hubitat device attribute |
| `GlucoseAnnouncer.groovy` | App | A virtual switch that, when turned on, reads your current glucose level aloud on your Chromecast speakers |

The two are independent — you can install either on its own.

---

## StuCGM (driver)

StuCGM is a driver for the Hubitat smart home hub that allows users to access their most recent blood glucose value from a continuous glucose monitor (CGM) via Nightscout. The driver returns the value in mmol/L, but it can also return the value in mg/dL by removing a few lines of code. The driver also includes a few thresholds for low and high blood sugar levels, Configuration is edited in the driver code, not in device preferences.

The attributes can be used in Hubitat automations. Readings update when `refresh()` is called; the driver has no built-in polling schedule.

Based on the work of 'cfunk30' and the CariCGM project. More details about their driver here: https://community.hubitat.com/t/maker-api-driver-or-somthing-simple/26769/13

### Install and configure

1. Open [StuCGM.groovy](StuCGM.groovy) and copy it into your own Hubitat driver editor. Before saving or running it, change the code values below for your setup. The repository contains an author's endpoint and dashboard defaults; these are not shared services or device settings.

   | Code location | Default / what to change |
   | --- | --- |
   | `params.uri` in `sendSyncCmd()` | Replace the existing URL with your own Nightscout `/api/v1/entries/current.json` endpoint, reachable from the hub. The driver sends no authentication header or token by default; any access needed by your endpoint must be handled in your local copy. |
   | `SGV = SGV/18` and `SGV = SGV.round(1)` | Converts the incoming mg/dL value to mmol/L with one decimal place. To retain mg/dL, remove both lines in your local copy and also change the thresholds to the same units. There is no unit-selection flag. |
   | `low1`, `low2`, `high1`, `high2` | Defaults are `4`, `3`, `10`, `18`. The comparisons use `low2`, `low1` and `high1`; `high2` is declared but unused. These are code defaults, not recommended treatment thresholds. |
   | `#tile-63` in `tileHtml` | If using `CustomTile2`, change this selector to your dashboard tile ID. |
   | Image URL in `tileHtml` | Replace the local `https://192.168.10.1/` image host with your own reachable host. The code names `flatarrow.png`, `45uparrow.png`, `singlearrowup.png`, `doublearrowup.png`, `45downarrow.png`, `singlearrowdown.png` and `doublearrowdown.png`; these image files are not included in this repository. |

2. In the hub web interface, open **Developer Tools → Drivers Code → New Driver**, paste your locally configured code, and select **Save**. See Hubitat's [custom driver guide](https://docs2.hubitat.com/en/developer/driver/overview).
3. Open **Devices → Add Device → Virtual**, give the device a name, and choose **StuCGM** for **Type**. Complete the virtual-device creation form. See Hubitat's [Add Device guide](https://docs2.hubitat.com/en/user-interface/devices/add-device).
4. Open the new device's detail page and run **Refresh**. A successful response supplies a JSON array whose first entry has `sgv` and `direction`; the driver updates the attributes below. Check **Logs** if the request fails. This driver defines no **Preferences** inputs and no **Configure** command.

### Attributes

| Attribute | Type | Notes |
| --- | --- | --- |
| `SGV` | number | Latest glucose value, mmol/L, 1 decimal place. |
| `SGV_state` | string | `Normal` / `Low` / `VeryLow` / `High` (thresholds 4, 3 and 10 mmol/L). |
| `SGVstate1` | string | Same state word, under the attribute name the dashboard and lighting rules read. |
| `CustomTile2` | string | Ready-to-render HTML for the dashboard CGM tile (targets tile 63; red background when out of range, green when normal, plus a trend arrow). |
| `SGV_background` | string | Background colour for the value, `rgba(...)`. |
| `SGV_shadow` | string | Shadow colour for the value. |

Refresh is driven by a Rule Machine rule (`StuCGM - Refresh CGM Every 5 Mins`) that calls `refresh()` every 5 minutes and also copies `SGV` into the `SGV-global` hub variable.

> **Note (2026-08-20):** this file is the reconstructed merge of the repo's original driver and the customized variant that ran on the hub (which added `SGVstate1` and `CustomTile2`). The reconstruction was verified live against the hub's event history: the `High`-state tile output matches byte-for-byte, and the Rising arrow images (`45uparrow.png`, `singlearrowup.png`) are the observed ones. The remaining arrow filenames (`flatarrow`, `45downarrow`, `singlearrowdown`, `doublearrowup`, `doublearrowdown`) and the green Normal-state tile are symmetric extrapolations — check the dashboard the first time glucose is in range or falling.

---

## Glucose Announcer (app)

A virtual switch that speaks your glucose level out loud. Turn it on — from a dashboard tile, Google Home, Alexa, Rule Machine, or the device page — and it fetches a short pre-rendered audio clip and plays it on the Chromecast speakers you choose. The switch turns itself back off, so it behaves like a momentary button.

### What you need

- Chromecast / Google Nest speakers in Hubitat via the **Google Chromecast+** driver by jpage4500 ([repo](https://github.com/jpage4500/hubitat-drivers)), or any device with the `AudioNotification` capability and a `playTrack` command.
- **An HTTP endpoint of your own** that returns the URL of an audio file. This app does not include one — see below.

### Your endpoint

The app does one `GET` and expects the response body to be a bare URL to a playable audio file, as `text/plain`:

```
GET https://your-endpoint.example.com/levels

https://storage.example.com/glucose-a1b2c3.mp3
```

That's the whole contract. How you produce the clip is up to you — read Nightscout, render speech with a TTS service, return the URL. Anything that answers a GET with an audio URL works.

Nothing is hardcoded: set your endpoint on the app's page after installing. The `DEFAULT_ENDPOINT` constant is intentionally blank, because these endpoints are usually unauthenticated — publishing one would let anyone read your glucose level and run up costs on whatever generates the audio.

### Install

1. **Apps code → New App**, paste `GlucoseAnnouncer.groovy`, **Save**.
2. Click **OAuth** and enable it. Only needed for the trigger URLs below; the switch works without it.
3. **Apps → Add User App → Glucose Announcer.** It installs itself and creates a switch device called **Glucose Announcement**.
4. Open it and set your endpoint URL and speakers.

### Settings

| Setting | Default | Notes |
| --- | --- | --- |
| Endpoint URL | *(blank)* | Required. Returns a bare audio URL as plain text. |
| Speakers | — | Multi-select. See the warning below. |
| Announcement volume | 70 | Blank leaves each speaker at its current volume. |
| Put the volume back afterwards | on | See "Volume restore". |
| Auto-off after | 5s | Counted from when audio is dispatched, not from the press. 0 leaves the switch on. |

**Pick individual speakers, not a cast group.** Casting to a group *and* to its members at the same time makes them fight over the stream. Skipping TVs and displays is usually a good idea too, unless you want your glucose reading interrupting whatever is on screen.

### Volume restore

The Chromecast+ driver's `playTrackAndRestore()` does not restore volume, despite the name. Its `startMedia()` sets `state.ttsActive = false`, and `finishTts()` — which holds the restore logic — opens with `if (!state.ttsActive) return`. Volume is only ever restored on the driver's `speak()` / TTS path. Play a track with a volume argument and the speaker simply stays at that volume afterwards.

So this app does the restore itself: it reads each speaker's volume before playing, reads `mediaDuration` off the device once the clip has loaded, and puts the volume back just after the clip finishes.

Note that any volume change can make Google speakers emit their short confirmation tone, so an announcement may be bracketed by two of them. Turn the restore off, or leave the announcement volume blank, if that bothers you more than the volume drift does.

### Trigger URLs

With OAuth enabled the app exposes a few endpoints. The base URL and token are shown on the app's own page.

```
GET  <base>/announce?access_token=<token>          announce on the configured speakers
       &only=<deviceId>                            ...on just one of them
       &volume=<0-100>                             ...at a specific volume
GET  <base>/press?access_token=<token>             press the switch itself
       &dry=1                                      ...without casting anything (for testing)
GET  <base>/volume?access_token=<token>&dev=<id>&level=<n>
                                                   set one speaker's volume
```

`/press` goes through the switch device exactly as a dashboard tile or Google Home would, so it's the honest test of the whole path. `/announce` skips the switch and goes straight to the fetch.

### Behaviour worth knowing

- **Latency.** The switch stays on for the whole fetch and only auto-offs once audio has been dispatched, so a slow endpoint reads as "working" rather than as a failure. Presses are debounced for 20s so an impatient second press can't queue a duplicate announcement.
- **Failure handling.** A non-200 response, or a body that isn't a URL, is retried once and then logged and dropped. Nothing is ever handed to `playTrack` unvalidated.
- **The fetch is asynchronous** (`asynchttpGet`), so a slow endpoint never blocks a hub thread.
