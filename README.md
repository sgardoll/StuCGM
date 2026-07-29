# StuCGM

Two Hubitat components for getting continuous glucose monitor (CGM) data into your smart home.

| File | Type | What it does |
| --- | --- | --- |
| `StuCGM.groovy` | Driver | Exposes your latest blood glucose value from Nightscout as a Hubitat device attribute |
| `GlucoseAnnouncer.groovy` | App | A virtual switch that, when turned on, reads your current glucose level aloud on your Chromecast speakers |

The two are independent — you can install either on its own.

---

## StuCGM (driver)

StuCGM is a driver for the Hubitat smart home hub that allows users to access their most recent blood glucose value from a continuous glucose monitor (CGM) via Nightscout. The driver returns the value in mmol/L, but it can also return the value in mg/dL by removing a few lines of code. The driver also includes a few thresholds for low and high blood sugar levels, and it has a flag that indicates whether to use mmol/L or mg/dL.

This driver allows you to easily monitor your blood glucose levels in real-time and build automations based on the data. Simply add your Nightscout details into the code at the places indicated and you'll be able to access your latest readings directly from within Hubitat.

Based on the work of 'cfunk30' and the CariCGM project. More details about their driver here: https://community.hubitat.com/t/maker-api-driver-or-somthing-simple/26769/13

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
