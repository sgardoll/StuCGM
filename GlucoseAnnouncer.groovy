/**
 * Glucose Announcer
 *
 * Creates a virtual switch. Turning it on polls a BuildShip endpoint that returns the URL of an mp3
 * reading out the current blood glucose level, then plays that mp3 on the selected Chromecast+ speakers.
 * The switch turns itself back off after a few seconds, so it behaves like a momentary button.
 *
 *   switch on -> GET <endpoint>  ->  body is JSON { url, voiceResponse }  ->  playTrackAndRestore(url) on each speaker
 */

definition(
    name: "Glucose Announcer",
    namespace: "sgardoll",
    author: "Stuart Gardoll",
    description: "Virtual switch that announces your current blood glucose on the Chromecast speakers.",
    category: "My Apps",
    iconUrl: "",
    iconX2Url: "",
    installOnOpen: true,
    singleInstance: true,
    oauth: true
)

import groovy.transform.Field

// Your endpoint goes here, or just fill it in on the app's page after installing. Deliberately blank
// in the repo: the endpoint is unauthenticated, so publishing it would let anyone read your glucose
// level and run up TTS costs on the workflow behind it.
@Field static final String DEFAULT_ENDPOINT = ""
// The endpoint takes ~14s warm and up to ~45s cold, so the debounce has to outlast a round trip.
// A 3s debounce let an impatient second press queue a duplicate announcement.
@Field static final Integer DEBOUNCE_MS     = 20000

// Device IDs pre-selected on first install, so the app works before anyone opens its page.
// These are specific to one hub - on any other hub just pick the speakers on the app's page.
// The principle to copy: pick the audio speakers, not TVs or displays, and never a cast group
// as well as its members (casting to both at once makes them fight over the stream).
@Field static final List<String> DEFAULT_SPEAKER_IDS = ["568", "569", "573", "574", "575", "580"]

preferences {
    page(name: "mainPage")
}

mappings {
    // GET /announce            -> announce on the configured speakers
    // GET /announce?only=574   -> announce on just that device
    // GET /announce?volume=15  -> announce at that volume instead of the configured one
    path("/announce") { action: [GET: "announceEndpoint"] }
    // GET /volume?dev=574&level=90 -> set one speaker's volume (handy after a test that
    // left a speaker turned down; the driver only restores volume on its TTS path)
    path("/volume")   { action: [GET: "volumeEndpoint"] }
    // GET /press -> press the virtual switch itself, i.e. exercise exactly the path a dashboard
    // tile / Google Home / Rule Machine takes. ?dry=1 runs it without casting anything.
    path("/press")    { action: [GET: "pressEndpoint"] }
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Glucose Announcer", install: true, uninstall: true) {

        section("Endpoint") {
            input name: "endpointUrl", type: "text", title: "Glucose audio endpoint",
                description: "Returns a bare mp3 URL as plain text",
                defaultValue: DEFAULT_ENDPOINT, required: true
        }

        section("Speakers") {
            input name: "speakers", type: "capability.audioNotification",
                title: "Play the announcement on", multiple: true, required: false
            paragraph "Pick the individual Chromecast speakers, not a cast group. Casting to a group " +
                      "and to its members at the same time makes them fight over the stream."
            input name: "announceVolume", type: "number", title: "Announcement volume (0-100)",
                description: "Blank = leave each speaker at its current volume", required: false, range: "0..100"
            input name: "restoreVolume", type: "bool", title: "Put the volume back afterwards",
                description: "The Chromecast+ driver only restores volume on its speak() path, never on " +
                             "playTrack, so this app snapshots and restores it instead.",
                defaultValue: true
        }

        section("Button behaviour") {
            input name: "autoOffSecs", type: "number", title: "Turn the switch back off after (seconds)",
                description: "0 = leave it on", defaultValue: 5, required: false, range: "0..300"
        }

        section("Test") {
            input name: "btnTest", type: "button", title: "Announce now"
            paragraph statusText()
        }

        section("Logging") {
            input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        }
    }
}

private String statusText() {
    def child = getChildDevice(childDni())
    String s = child ? "Switch device: <b>${child.displayName}</b>" : "Switch device will be created when you hit Done."
    if (state.lastResult) s += "<br>Last run: ${state.lastResult}"
    if (state.localEndpoint) s += "<br>Trigger URL: <code>${state.localEndpoint}</code>"
    return s
}

// ============================================================================
// lifecycle
// ============================================================================

def installed() {
    bootstrapDefaults()
    initialize()
}

def updated() {
    unschedule()
    initialize()
}

def uninstalled() {
    getChildDevices()?.each { deleteChildDevice(it.deviceNetworkId) }
}

def initialize() {
    if (!state.accessToken) {
        try { createAccessToken() } catch (e) { log.warn "Glucose Announcer: could not create access token - ${e.message}" }
    }
    def child = getChildDevice(childDni())
    if (!child) {
        child = addChildDevice("hubitat", "Generic Component Switch", childDni(),
            [name: "Glucose Announcement", label: "Glucose Announcement", isComponent: false])
        log.info "Glucose Announcer: created child switch '${child.displayName}'"
    }
    child.parse([[name: "switch", value: "off", descriptionText: "initialised"]])
    state.localEndpoint = "${getFullLocalApiServerUrl()}/announce?access_token=${state.accessToken}"
}

// First install only: pick sensible defaults so the app works before anyone opens its page.
private void bootstrapDefaults() {
    if (settings.endpointUrl == null && DEFAULT_ENDPOINT) {
        app.updateSetting("endpointUrl", [type: "text", value: DEFAULT_ENDPOINT])
    }
    if (settings.autoOffSecs == null) {
        app.updateSetting("autoOffSecs", [type: "number", value: 5])
    }
    if (settings.announceVolume == null) {
        app.updateSetting("announceVolume", [type: "number", value: 70])
    }
    if (settings.restoreVolume == null) {
        app.updateSetting("restoreVolume", [type: "bool", value: true])
    }
    if (!settings.speakers) {
        app.updateSetting("speakers", [type: "capability.audioNotification", value: DEFAULT_SPEAKER_IDS])
        log.info "Glucose Announcer: defaulted speakers to ${DEFAULT_SPEAKER_IDS}"
    }
}

private String childDni() { "glucose-announcer-${app.id}" }

// ============================================================================
// child switch callbacks (Generic Component Switch routes on()/off() here)
// ============================================================================

void componentOn(cd) {
    setSwitch("on", "turned on")
    announce()
}

void componentOff(cd) {
    unschedule("autoOff")
    setSwitch("off", "turned off")
}

void componentRefresh(cd) { /* nothing to refresh */ }

private void setSwitch(String value, String why) {
    getChildDevice(childDni())?.parse([[name: "switch", value: value,
        descriptionText: "Glucose Announcement was ${why}"]])
}

// ============================================================================
// app UI button
// ============================================================================

void appButtonHandler(String btn) {
    if (btn == "btnTest") announce()
}

// ============================================================================
// local/cloud API endpoint
// ============================================================================

def announceEndpoint() {
    Map opts = [:]
    if (params?.only)   opts.only   = params.only
    if (params?.volume) opts.volume = params.volume
    log.info "Glucose Announcer: triggered via API ${opts}"
    setSwitch("on", "triggered via API")
    announce(opts)
    render contentType: "text/plain", data: "ok ${opts}"
}

def pressEndpoint() {
    def child = getChildDevice(childDni())
    if (!child) {
        render contentType: "text/plain", data: "no child switch device"
        return
    }
    state.dryRun = (params?.dry == "1")
    child.on()   // goes through the driver, which calls back into componentOn()
    render contentType: "text/plain", data: "pressed ${child.displayName} (dryRun=${state.dryRun})"
}

def volumeEndpoint() {
    def dev = settings.speakers?.find { it.id?.toString() == params?.dev?.toString() }
    if (!dev) {
        render contentType: "text/plain", data: "no selected speaker with id ${params?.dev}"
        return
    }
    Integer level = (params?.level ?: 50) as Integer
    dev.setVolume(level)
    log.info "Glucose Announcer: set ${dev.displayName} volume to ${level}"
    render contentType: "text/plain", data: "set ${dev.displayName} to ${level}"
}

// ============================================================================
// announcement
// ============================================================================

private void announce(Map opts = [:]) {
    Long last = state.lastFire as Long
    if (last && (now() - last) < DEBOUNCE_MS) {
        logDebug "announce: debounced (last fired ${now() - last}ms ago)"
        scheduleAutoOff()
        return
    }
    state.lastFire = now()
    state.attempt = 1
    state.opts = opts ?: [:]
    fetchLevels()
    // Leave the switch on while we fetch, so it reads as "working" rather than flipping off
    // ~10s before any sound arrives. The real auto-off is scheduled once the audio is dispatched;
    // this is only a safety net so a failed fetch can't leave it stuck on.
    runIn(90, "autoOff", [overwrite: true])
}

private void fetchLevels() {
    String url = (settings.endpointUrl ?: DEFAULT_ENDPOINT)?.trim()
    if (!url) {
        log.error "Glucose Announcer: no endpoint configured - set one on the app's page"
        state.lastResult = "no endpoint configured"
        scheduleAutoOff()
        return
    }
    logDebug "fetchLevels: GET ${url} (attempt ${state.attempt})"
    try {
        asynchttpGet("levelsResponse", [uri: url, timeout: 60], (state.opts ?: [:]))
    } catch (e) {
        log.error "Glucose Announcer: request failed to start - ${e.message}"
        state.lastResult = "request failed to start: ${e.message}"
    }
}

void levelsResponse(resp, data) {
    Integer status = null
    String body = null

    try { status = resp?.getStatus() } catch (ignored) { }
    try { body = resp?.getData()?.toString()?.trim() } catch (ignored) { }

    if (status != 200) {
        logDebug "levelsResponse: HTTP ${status} body=${body}"
        retryOrFail("HTTP ${status}")
        return
    }

    String url = null
    if (body) {
        try {
            def parsed = new groovy.json.JsonSlurper().parseText(body)
            if (parsed instanceof Map && parsed.url) {
                url = parsed.url as String
                logDebug "levelsResponse: voiceResponse=${parsed.voiceResponse}"
            }
        } catch (ignored) { }
        // fall back to a bare URL string for backward compatibility
        if (!url && (body ==~ /(?i)^https?:\/\/\S+$/)) {
            url = body
        }
    }
    if (!url) {
        logDebug "levelsResponse: no url in body '${body}'"
        retryOrFail("endpoint did not return a URL")
        return
    }

    log.info "Glucose Announcer: got audio ${url}"
    state.lastResult = "played ${new Date().format('HH:mm:ss', location.timeZone)}"
    playOnSpeakers(url, (data instanceof Map) ? data : [:])
}

private void retryOrFail(String reason) {
    Integer attempt = (state.attempt ?: 1) as Integer
    if (attempt < 2) {
        state.attempt = attempt + 1
        log.warn "Glucose Announcer: ${reason} - retrying in 3s"
        runIn(3, "fetchLevels", [overwrite: true])
    } else {
        log.error "Glucose Announcer: ${reason} - giving up"
        state.lastResult = "failed: ${reason}"
        scheduleAutoOff()
    }
}

private void playOnSpeakers(String trackUrl, Map opts = [:]) {
    def targets = settings.speakers
    if (opts?.only) {
        targets = targets?.findAll { it.id?.toString() == opts.only.toString() }
        if (!targets) {
            log.warn "Glucose Announcer: device ${opts.only} is not one of the selected speakers"
            return
        }
    }
    if (!targets) {
        log.warn "Glucose Announcer: no speakers selected"
        return
    }
    if (state.dryRun) {
        state.dryRun = false
        log.info "Glucose Announcer: DRY RUN - would play ${trackUrl} on ${targets*.displayName}"
        state.lastResult = "dry run ${new Date().format('HH:mm:ss', location.timeZone)}"
        scheduleAutoOff()
        return
    }

    Integer vol = (opts?.volume != null) ? (opts.volume as Integer)
                : ((settings.announceVolume != null) ? (settings.announceVolume as Integer) : null)

    // The driver's playTrackAndRestore does NOT restore volume (startMedia clears the ttsActive
    // flag that finishTts gates on), so snapshot it here and put it back once the clip has played.
    if (vol != null && settings.restoreVolume != false) {
        Map snap = [:]
        targets.each { dev ->
            def prior = dev.currentValue("volume")
            if (prior != null && (prior as Integer) != vol) snap[dev.id.toString()] = prior as Integer
        }
        state.volSnapshot = snap
        logDebug "playOnSpeakers: volume snapshot ${snap}"
    } else {
        state.volSnapshot = [:]
    }

    targets.each { dev ->
        try {
            if (dev.hasCommand("playTrack")) {
                if (vol != null) dev.playTrack(trackUrl, vol) else dev.playTrack(trackUrl)
            } else if (dev.hasCommand("playTrackAndRestore")) {
                if (vol != null) dev.playTrackAndRestore(trackUrl, vol) else dev.playTrackAndRestore(trackUrl)
            } else {
                log.warn "Glucose Announcer: ${dev.displayName} has no playTrack command"
                return
            }
            logDebug "playOnSpeakers: sent to ${dev.displayName}"
        } catch (e) {
            log.error "Glucose Announcer: ${dev.displayName} failed - ${e.message}"
        }
    }

    scheduleAutoOff()
    if (state.volSnapshot) runIn(4, "planVolumeRestore", [overwrite: true])
}

// The clip length is only known once the speaker reports MEDIA_STATUS back, so wait a beat,
// read the real duration off the device, then put the volume back just after it finishes.
void planVolumeRestore() {
    BigDecimal maxDur = 0
    settings.speakers?.each { dev ->
        if (state.volSnapshot?.containsKey(dev.id.toString())) {
            def d = dev.currentValue("mediaDuration")
            if (d != null) { BigDecimal bd = d as BigDecimal; if (bd > maxDur) maxDur = bd }
        }
    }
    Integer wait = Math.max(6, maxDur.intValue() + 3)
    logDebug "planVolumeRestore: clip ${maxDur}s -> restoring volume in ${wait}s"
    runIn(wait, "restoreVolumes", [overwrite: true])
}

void restoreVolumes() {
    Map snap = state.volSnapshot ?: [:]
    state.volSnapshot = [:]
    if (!snap) return
    settings.speakers?.each { dev ->
        def prior = snap[dev.id.toString()]
        if (prior == null) return
        try {
            dev.setVolume(prior as Integer)
            logDebug "restoreVolumes: ${dev.displayName} back to ${prior}"
        } catch (e) {
            log.warn "Glucose Announcer: could not restore ${dev.displayName} volume - ${e.message}"
        }
    }
}

// ============================================================================
// auto-off
// ============================================================================

private void scheduleAutoOff() {
    Integer secs = (settings.autoOffSecs != null) ? (settings.autoOffSecs as Integer) : 5
    if (secs > 0) runIn(secs, "autoOff", [overwrite: true])
}

void autoOff() { setSwitch("off", "turned off (auto)") }

// ============================================================================

private void logDebug(String msg) { if (settings.logEnable) log.debug "Glucose Announcer: ${msg}" }
