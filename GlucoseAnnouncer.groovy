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
// A speaker that is rebooting takes far longer than the old single 5s probe. Poll until it is back.
@Field static final Long VERIFY_DEADLINE_MS = 120000
@Field static final Integer VERIFY_PASSES_REQUIRED = 2

// Device IDs pre-selected on first install, so the app works before anyone opens its page.
// These are specific to one hub - on any other hub just pick the speakers on the app's page.
// The principle to copy: pick the audio speakers, not TVs or displays, and never a cast group
// as well as its members (casting to both at once makes them fight over the stream).
@Field static final List<String> DEFAULT_SPEAKER_IDS = ["902", "903", "905"]

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
    unsubscribe()
    unschedule()
    initialize()
}

def uninstalled() {
    getChildDevices()?.each { deleteChildDevice(it.deviceNetworkId) }
}

def initialize() {
    // Keep the hub's _googlecast._tcp mDNS cache warm for this app, and re-check the speakers' stored IPs
    // on a timer, so a DHCP change is corrected before anyone presses the button (see healSpeakers).
    // A hub reboot clears the listener, so it is also re-registered on systemStart.
    subscribe(location, "systemStart", "bootHandler")
    registerMdns()
    runEvery15Minutes("reconcileSpeakers")
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
    // Remember what was configured, so playOnSpeakers can tell when a speaker has since been deleted.
    state.configuredSpeakerIds = settings.speakers?.collect { it.id.toString() } ?: []
}

// The mDNS listener is cleared on hub reboot; re-register so speaker healing keeps working.
void bootHandler(evt) { registerMdns() }

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
    boolean dry = (state.dryRun == true)   // announce() consumes the flag, so report it before the press
    child.on()   // goes through the driver, which calls back into componentOn()
    render contentType: "text/plain", data: "pressed ${child.displayName} (dryRun=${dry})"
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
    // Consume the dry-run flag here so it cannot leak into a later press.
    boolean dry = (state.dryRun == true)
    state.dryRun = false
    state.dry = dry
    Long last = state.lastFire as Long
    if (last && (now() - last) < DEBOUNCE_MS) {
        logDebug "announce: debounced (last fired ${now() - last}ms ago)"
        scheduleAutoOff()
        return
    }
    state.lastFire = now()
    state.attempt = 1
    state.opts = opts ?: [:]
    // The endpoint takes 14-45s, so heal the speakers now and let their reconnect overlap the wait.
    healSpeakers(selectTargets(opts), true)
    fetchLevels()
    // Leave the switch on while we fetch, so it reads as "working" rather than flipping off
    // ~10s before any sound arrives. The real auto-off is scheduled once the audio is dispatched;
    // this is only a safety net so a failed fetch can't leave it stuck on.
    runIn(90, "autoOff", [overwrite: true])
}

void fetchLevels() {
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
        scheduleAutoOff()
    }
}

void levelsResponse(resp, data) {
    Integer status = null
    String body = null
    String url = null

    try { status = resp?.getStatus() } catch (ignored) { }

    // The platform pre-parses a JSON content-type into a Map, and hands over text/plain as a String.
    def raw = null
    try { raw = resp?.getData() } catch (ignored) { }
    if (raw instanceof Map) {
        if (raw.url) url = raw.url as String
        logDebug "levelsResponse: voiceResponse=${raw.voiceResponse}"
    } else {
        body = raw?.toString()?.trim()
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
    }

    if (status != 200) {
        logDebug "levelsResponse: HTTP ${status} body=${body}"
        retryOrFail("HTTP ${status}")
        return
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

private def selectTargets(Map opts = [:]) {
    def targets = settings.speakers
    if (opts?.only) targets = targets?.findAll { it.id?.toString() == opts.only.toString() }
    return targets
}

private void playOnSpeakers(String trackUrl, Map opts = [:]) {
    def targets = selectTargets(opts)
    if (opts?.only && !targets) {
        log.warn "Glucose Announcer: device ${opts.only} is not one of the selected speakers"
        state.lastResult = "failed: device ${opts.only} is not a selected speaker"
        scheduleAutoOff()
        return
    }
    if (!targets) {
        log.warn "Glucose Announcer: no speakers selected"
        state.lastResult = "failed: no speakers selected (a re-added speaker has a new device id - reselect it on the app's page)"
        scheduleAutoOff()
        return
    }

    // A deleted speaker silently drops out of the selection, and a re-added one comes back with a new
    // device id. Comparing what was configured against what is present is the only trace it leaves.
    List<String> configured = (state.configuredSpeakerIds ?: []) as List
    List<String> present = settings.speakers?.collect { it.id.toString() } ?: []
    List<String> missing = configured.findAll { !present.contains(it) }
    if (missing) log.warn "Glucose Announcer: ${missing.size()} selected speaker(s) are no longer in Hubitat (a re-added speaker gets a new device id) - reselect them on the app's page"

    if (state.dry) {
        state.dry = false
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

    // Re-check right before sending: the speaker may have dropped or moved during the 14-45s fetch.
    healSpeakers(targets, true)

    state.lastTrack = trackUrl
    state.lastVol = vol
    state.sentTo = targets.collect { it.id.toString() }
    targets.each { dev -> sendTrack(dev, trackUrl, vol) }
    // Poll for up to VERIFY_DEADLINE_MS: see verifyPlayback. needResend tracks speakers written off as
    // offline, so the clip can be sent again once they are back rather than assumed played.
    state.needResend = [:]
    state.verifyPasses = 0
    state.verifyDeadline = now() + VERIFY_DEADLINE_MS
    state.durationProbes = 0

    scheduleAutoOff()
    if (state.volSnapshot) runIn(4, "planVolumeRestore", [overwrite: true])
    runIn(5, "verifyPlayback", [overwrite: true])
}

private void sendTrack(dev, String trackUrl, Integer vol) {
    try {
        if (dev.hasCommand("playTrack")) {
            if (vol != null) dev.playTrack(trackUrl, vol) else dev.playTrack(trackUrl)
        } else if (dev.hasCommand("playTrackAndRestore")) {
            if (vol != null) dev.playTrackAndRestore(trackUrl, vol) else dev.playTrackAndRestore(trackUrl)
        } else {
            log.warn "Glucose Announcer: ${dev.displayName} has no playTrack command"
            return
        }
        logDebug "sendTrack: sent to ${dev.displayName}"
    } catch (e) {
        log.error "Glucose Announcer: ${dev.displayName} failed - ${e.message}"
    }
}

// ============================================================================
// speaker self-healing
//
// The Chromecast+ driver keeps each speaker's IP in a data value that only its own app updates, and
// that app only scans for 30 minutes after you open it. So once DHCP moves a speaker, the driver
// connects to the old address, marks itself offline, DROPS the queued playTrack, and retries the same
// dead address every 5 minutes forever. Nothing in the announcer noticed, because playTrack returns
// normally either way. This section does what the driver's own app does on "Done" (correct the IP and
// initialize the child), but on every press and on a timer instead of only while the app page is open.
// ============================================================================

private void registerMdns() {
    try {
        registerMDNSListener("_googlecast._tcp")
        state.mdnsWarned = false
    } catch (e) {
        if (!state.mdnsWarned) {
            state.mdnsWarned = true
            log.warn "Glucose Announcer: mDNS listener unavailable (needs firmware 2.4.1.151+) - speaker IP self-healing is disabled: ${e.message}"
        }
    }
}

private String normId(String s) { (s ?: "").replaceAll("[^A-Za-z0-9]", "").toLowerCase() }

// RFC1918 only: a speaker never legitimately moves to a public or loopback address.
private boolean isPrivateIp(String ip) {
    def m = (ip ?: "") =~ /^(\d{1,3})\.(\d{1,3})\.\d{1,3}\.\d{1,3}$/
    if (!m.matches()) return false
    int a = m[0][1] as Integer, b = m[0][2] as Integer
    return a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168)
}

// dev.id -> [ip, port] for each selected speaker the hub's mDNS cache currently sees. Matched on the cast
// uuid the driver stores (falling back to the uuid embedded in its DNI, then to a unique friendly name).
// An empty or failed read is no evidence about any speaker, so it returns nothing rather than guessing.
private Map lookupLiveSpeakers(devs) {
    Map out = [:]
    Map entries = null
    try {
        entries = hubitat.helper.NetworkUtils.getRawMDNSEndpointsByMACForServiceType("_googlecast._tcp.local.")
    } catch (e) {
        logDebug "mDNS lookup unavailable - ${e.message}"
        return out
    }
    if (!entries) return out

    List<Map> live = []
    entries.each { mac, v ->
        try {
            if (v.ip4Address) {
                live << [ip: v.ip4Address.toString(), port: (v.port ?: 8009).toString(),
                         uuid: normId(v.deviceId?.toString()), name: v.friendlyName?.toString()]
            }
        } catch (ignored) { }
    }

    devs.each { dev ->
        try {
            // mDNS is unauthenticated, and a match decides where the speaker is told to connect. So a stored
            // cast uuid must match exactly (full length); the weaker DNI / name fallbacks only apply to a
            // device whose recorded uuid is missing or too short to match on, never to one whose uuid simply
            // isn't in the cache.
            String uuid = normId(dev.getDataValue("uuid"))
            Map hit = null
            if (uuid?.length() >= 32) {
                hit = live.find { it.uuid == uuid }
            } else {
                String dni = normId(dev.deviceNetworkId)
                hit = live.find { it.uuid.length() >= 32 && dni.endsWith(it.uuid) }
                if (!hit) {
                    String nm = dev.getDataValue("discoveredName")
                    List byName = nm ? live.findAll { it.name == nm } : []
                    if (byName.size() == 1) hit = byName[0]
                }
            }
            if (hit && !isPrivateIp(hit.ip)) {
                log.warn "Glucose Announcer: ignoring mDNS address ${hit.ip} for ${dev.displayName} - not a private LAN address"
                hit = null
            }
            if (hit) out[dev.id.toString()] = hit
        } catch (e) {
            logDebug "lookupLiveSpeakers: ${dev.displayName} - ${e.message}"
        }
    }
    return out
}

// Gets the speakers into a state playTrack can reach: corrects a stale IP, and (resetOffline) resets any
// driver parked offline, which clears its dropped queue and dead-connection bookkeeping. Returns the ids
// it reset. Must run BEFORE playTrack, because the driver's initialize() empties its pending actions.
private List<String> healSpeakers(devs, boolean resetOffline) {
    List<String> reset = []
    if (!devs) return reset
    Map fresh = lookupLiveSpeakers(devs)
    devs.each { dev ->
        try {
            String id = dev.id.toString()
            Map hit = fresh[id]
            String oldIp = dev.getDataValue("ip")
            boolean ipFixed = false
            if (hit?.ip && hit.ip != oldIp) {
                // initialize() does not close an open socket, so drop the live connection first or the
                // driver keeps talking to the old address.
                try { if (dev.hasCommand("disconnect")) dev.disconnect() } catch (ignored) { }
                dev.updateDataValue("ip", hit.ip)
                if (hit.port) dev.updateDataValue("port", hit.port)
                log.warn "Glucose Announcer: ${dev.displayName} moved ${oldIp} -> ${hit.ip}, updated its driver"
                ipFixed = true
            }
            boolean offline = isUnreachable(dev)
            if (ipFixed || (resetOffline && offline)) {
                if (dev.hasCommand("initialize")) dev.initialize()
                else if (dev.hasCommand("reconnect")) dev.reconnect()
                reset << id
                logDebug "healSpeakers: reset ${dev.displayName} (${ipFixed ? 'ip changed' : 'was offline'})"
            }
        } catch (e) {
            log.warn "Glucose Announcer: could not heal ${dev.displayName} - ${e.message}"
        }
    }
    return reset
}

// Timer: fix moved speakers ahead of time. Offline-but-same-IP speakers are left to the driver's own retry.
void reconcileSpeakers() {
    registerMdns()   // cheap and idempotent; a hub reboot clears the listener
    // An in-flight announcement owns the children's pending actions; initialize() would wipe them.
    if (state.verifyDeadline && now() < (state.verifyDeadline as Long)) {
        logDebug "reconcileSpeakers: announcement in flight - deferring"
        return
    }
    healSpeakers(settings.speakers, false)
}

private boolean isUnreachable(dev) {
    String conn = dev.currentValue("connectionStatus")?.toString()
    String play = dev.currentValue("playbackStatus")?.toString()
    return "offline".equalsIgnoreCase(conn) || "disconnected".equalsIgnoreCase(conn) ||
           "OFFLINE".equalsIgnoreCase(play)
}

// playTrack returns normally even when the driver then fails to connect, and the driver defers
// connect(), so one sample at 5s is not evidence. Poll until every speaker has either taken the
// track or been written off. The driver drops queued actions when it goes offline, so a speaker
// that comes back is sent the clip again rather than assumed to have played it.
void verifyPlayback() {
    long deadline = (state.verifyDeadline ?: 0) as Long
    List<String> ids = (state.sentTo ?: []) as List
    def targets = (settings.speakers ?: []).findAll { ids.contains(it.id.toString()) }
    if (!targets) return
    Map needResend = (state.needResend ?: [:]) as Map
    List<String> offline = []
    targets.each { dev ->
        String id = dev.id.toString()
        if (isUnreachable(dev)) {
            offline << id
            needResend[id] = true
        } else if (needResend[id]) {
            log.warn "Glucose Announcer: ${dev.displayName} is reachable again - sending the announcement"
            sendTrack(dev, state.lastTrack as String, state.lastVol as Integer)
            needResend.remove(id)
            if (state.volSnapshot) runIn(4, "planVolumeRestore", [overwrite: true])
        }
    }
    state.needResend = needResend
    if (offline) {
        log.warn "Glucose Announcer: ${offline.size()} speaker(s) offline - healing and waiting for reconnect"
        healSpeakers(targets.findAll { offline.contains(it.id.toString()) }, true)
    }
    boolean waiting = offline || needResend
    if (!waiting) {
        state.verifyPasses = ((state.verifyPasses ?: 0) as Integer) + 1
        if ((state.verifyPasses as Integer) >= VERIFY_PASSES_REQUIRED) {
            logDebug "verifyPlayback: all speakers took the announcement"
            return
        }
    } else {
        state.verifyPasses = 0
    }
    if (now() >= deadline) {
        String names = targets.findAll { needResend.containsKey(it.id.toString()) }*.displayName.join(", ")
        log.error "Glucose Announcer: ${names} still unreachable after ${VERIFY_DEADLINE_MS / 1000}s"
        state.lastResult = "failed: ${names} unreachable"
        state.needResend = [:]
        return
    }
    runIn(5, "verifyPlayback", [overwrite: true])
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
    // mediaDuration only arrives with the speaker's MEDIA_STATUS, and a speaker that is still connecting
    // has not reported it yet. Committing to the 6s fallback here would restore the volume into the clip,
    // so re-probe a few times first.
    if (maxDur <= 0 && ((state.durationProbes ?: 0) as Integer) < 3) {
        state.durationProbes = ((state.durationProbes ?: 0) as Integer) + 1
        runIn(4, "planVolumeRestore", [overwrite: true])
        return
    }
    state.durationProbes = 0
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
