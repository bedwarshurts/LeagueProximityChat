const RV_LOOKAHEAD_S = 1.5;
const RV_PREFETCH_S = 8;
const RV_MAX_UTTERANCE_S = 125;
const RV_RESYNC_S = 0.35;
const RV_SEEK_S = 1.0;
const RV_REANCHOR_S = 0.08;
const RV_MIN_SPEED = 0.25;
const RV_MAX_SPEED = 2;
const RV_MAX_CACHED = 80;
const RV_POSITION_GAP_S = 3;
const RV_POSITION_STALE_S = 10;
const RV_MAX_LOOK_AHEAD = 4000;

const RV_MAP_BOUNDS = {11: [-120, -120, 14870, 14980], 12: [-28, -19, 12849, 12858]};

const replayVoice = {
    matchId: null, token: 0, data: null, bytes: null, ctx: null, out: null, speakers: [],
    buffers: new Map(), decoding: new Set(), playing: new Map(), played: new Set(),
    anchor: null, topYaw: null, listener: null
};

let replayStatus = null;

function setReplayStatus(text, kind = 'success') {
    replayStatus = text ? {text, kind} : null;
    if (!connected) showIdleAudioStatus();
}

async function onReplayStarted(data) {
    if (!data.apiAvailable) {
        setReplayStatus('Replay API is off', 'error');
    }
    if (data.matchId === replayVoice.matchId) {
        if (data.apiAvailable && replayVoice.data) setReplayStatus(replayVoiceStatusText());
        return;
    }
    stopReplayVoice();
    replayVoice.matchId = data.matchId;
    const token = ++replayVoice.token;
    if (!data.hasVoice) {
        setReplayStatus('Nothing recorded', 'error');
        return;
    }
    if (data.apiAvailable) setReplayStatus('Loading…', 'loading');

    try {
        const id = encodeURIComponent(data.matchId);
        const voice = await (await fetch(`/matches/${id}/voice`, {cache: 'no-store'})).json();
        const bytes = voice.available ? await (await fetch(`/matches/${id}/voice/data`, {cache: 'no-store'})).arrayBuffer() : null;
        if (token !== replayVoice.token) return;
        if (!voice.available || !bytes) {
            setReplayStatus('Nothing recorded', 'error');
            return;
        }
        startReplayVoice(voice, bytes);
        if (data.apiAvailable) setReplayStatus(replayVoiceStatusText());
        potgLog(`replay voice ready: ${voice.utterances.length} clips from ${voice.speakers.length} players`);
    } catch (e) {
        if (token === replayVoice.token) setReplayStatus("Couldn't load voice", 'error');
    }
}

function replayVoiceStatusText() {
    const n = replayVoice.speakers.length;
    return `${n} Player${n === 1 ? '' : 's'}`;
}

function startReplayVoice(voice, bytes) {
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    routeToSpeaker(ctx);
    const limiter = ctx.createDynamicsCompressor();
    limiter.threshold.value = -6;
    limiter.knee.value = 0;
    limiter.ratio.value = 20;
    limiter.attack.value = 0.003;
    limiter.release.value = 0.25;
    limiter.connect(ctx.destination);

    replayVoice.data = voice;
    replayVoice.bytes = bytes;
    replayVoice.ctx = ctx;
    replayVoice.out = limiter;
    replayVoice.speakers = voice.speakers.map((sp, i) => {
        const panner = ctx.createPanner();
        panner.panningModel = 'HRTF';
        panner.distanceModel = 'linear';
        panner.refDistance = 100;
        panner.maxDistance = 100000;
        panner.rolloffFactor = 0;
        panner.coneInnerAngle = 360;
        const gain = ctx.createGain();
        gain.gain.value = 0;
        panner.connect(gain);
        gain.connect(limiter);
        return {id: sp.id, champion: sp.champion || '', panner, gain, positions: voice.positions[String(i)] || []};
    });
}

function stopReplayVoice() {
    replayVoice.token++;
    stopReplayUtterances();
    if (replayVoice.ctx) {
        try { replayVoice.ctx.close(); } catch (e) {}
    }
    replayVoice.matchId = null;
    replayVoice.data = null;
    replayVoice.bytes = null;
    replayVoice.ctx = null;
    replayVoice.out = null;
    replayVoice.speakers = [];
    for (const clip of replayVoice.buffers.values()) {
        if (clip) URL.revokeObjectURL(clip.url);
    }
    replayVoice.buffers.clear();
    replayVoice.decoding.clear();
    replayVoice.anchor = null;
    replayVoice.topYaw = null;
    replayVoice.listener = null;
}

function onReplayEnded() {
    stopReplayVoice();
    setReplayStatus(null);
}

function stopReplayUtterances() {
    for (const clip of replayVoice.playing.values()) releaseReplayClip(clip);
    replayVoice.playing.clear();
    replayVoice.played.clear();
}

function releaseReplayClip(clip) {
    clearTimeout(clip.timer);
    try { clip.el.pause(); } catch (e) {}
    try { clip.source.disconnect(); } catch (e) {}
    clip.el.removeAttribute('src');
}

function onReplayState(s) {
    const rv = replayVoice;
    if (!rv.data || !rv.ctx) return;
    const ctx = rv.ctx;
    if (ctx.state === 'suspended') ctx.resume().catch(() => {});
    const now = ctx.currentTime;
    const wall = performance.now() / 1000;

    const running = !s.paused && !s.seeking;
    const t = s.time + (running ? Math.max(0, (Date.now() - s.at) / 1000) * s.speed : 0);

    const listener = replayListener(s.cam, t) || rv.listener;
    rv.listener = listener;
    if (listener) {
        ctx.listener.positionX.setTargetAtTime(listener.x, now, 0.05);
        ctx.listener.positionY.setTargetAtTime(0, now, 0.05);
        ctx.listener.positionZ.setTargetAtTime(listener.y, now, 0.05);
    }
    rv.speakers.forEach(sp => updateReplaySpeaker(sp, t, listener, now));

    const speed = s.speed;
    if (!running || speed < RV_MIN_SPEED - 0.001 || speed > RV_MAX_SPEED + 0.001) {
        stopReplayUtterances();
        rv.anchor = null;
        return;
    }
    if (rv.anchor) {
        const drift = t - replayTimeAt(wall);
        const speedChanged = Math.abs(speed - rv.anchor.speed) > 0.001;
        if (Math.abs(drift) > (speedChanged ? RV_SEEK_S : RV_RESYNC_S)) {
            stopReplayUtterances();
            rv.anchor = null;
        } else if (speedChanged) {
            rv.anchor = {wall, game: t, speed};
            retimeReplayClips(speed);
        } else if (Math.abs(drift) > RV_REANCHOR_S) {
            rv.anchor = {wall, game: t, speed};
        }
    }
    if (!rv.anchor) rv.anchor = {wall, game: t, speed};
    scheduleReplayUtterances(t);
}

function replayTimeAt(wall) {
    const a = replayVoice.anchor;
    return a.game + (wall - a.wall) * a.speed;
}

function retimeReplayClips(speed) {
    const rv = replayVoice;
    for (const [i, clip] of [...rv.playing]) {
        if (clip.started) {
            clip.el.defaultPlaybackRate = speed;
            clip.el.playbackRate = speed;
        } else {
            releaseReplayClip(clip);
            rv.playing.delete(i);
            rv.played.delete(i);
        }
    }
}

function updateReplaySpeaker(sp, t, listener, now) {
    const pos = positionAt(sp.positions, t);
    let volume = 0;
    if (pos && listener && !pos.dead) {
        const distance = Math.hypot(pos.x - listener.x, pos.y - listener.y);
        const maxDist = 11, fullDist = 3, maxVolume = 2, falloffExp = 0.6;
        if (distance <= fullDist) volume = maxVolume;
        else if (distance < maxDist) volume = maxVolume * Math.pow(1 - (distance - fullDist) / (maxDist - fullDist), falloffExp);
        volume *= playerVolumes[sp.id] ?? 1;
    }
    if (pos) {
        sp.panner.positionX.setTargetAtTime(pos.x, now, 0.05);
        sp.panner.positionY.setTargetAtTime(0, now, 0.05);
        sp.panner.positionZ.setTargetAtTime(pos.y, now, 0.05);
    }
    sp.gain.gain.setTargetAtTime(volume, now, 0.1);
}

function positionAt(track, t) {
    if (!track || track.length === 0 || t < track[0][0] - 1) return null;
    let lo = 0, hi = track.length - 1;
    while (lo < hi) {
        const mid = (lo + hi + 1) >> 1;
        if (track[mid][0] <= t) lo = mid; else hi = mid - 1;
    }
    const a = track[lo];
    const b = track[lo + 1];
    if (!b) return t - a[0] > RV_POSITION_STALE_S ? null : {x: a[1], y: a[2], dead: a[3] === 1};
    if (b[0] - a[0] > RV_POSITION_GAP_S || t <= a[0]) return {x: a[1], y: a[2], dead: a[3] === 1};
    const k = (t - a[0]) / (b[0] - a[0]);
    return {x: a[1] + (b[1] - a[1]) * k, y: a[2] + (b[2] - a[2]) * k, dead: a[3] === 1};
}

function replayListener(cam, t) {
    if (!cam) return null;
    if (cam.attached && cam.selection) {
        const sp = speakerForSelection(cam.selection);
        const pos = sp ? positionAt(sp.positions, t) : null;
        if (pos) return pos;
    }
    return cameraFocus(cam);
}

function speakerForSelection(selection) {
    const key = String(selection).toLowerCase().replace(/[^a-z0-9]/g, '');
    if (!key) return null;
    return replayVoice.speakers.find(sp => {
        const name = sp.id.split('#')[0].toLowerCase().replace(/[^a-z0-9]/g, '');
        const champ = sp.champion.toLowerCase().replace(/[^a-z0-9]/g, '');
        return key === name || (champ && key === champ);
    }) || null;
}

function cameraFocus(cam) {
    const rv = replayVoice;
    const pitch = cam.pitch * Math.PI / 180;
    const height = Math.max(0, cam.y);
    const ahead = pitch > 0.05 ? Math.min(height / Math.tan(pitch), RV_MAX_LOOK_AHEAD) : RV_MAX_LOOK_AHEAD;

    let dx = 0, dz = 1;
    if (cam.mode === 'top') {
        rv.topYaw = cam.yaw;
    } else if (rv.topYaw !== null) {
        const turn = (cam.yaw - rv.topYaw) * Math.PI / 180;
        dx = Math.sin(turn);
        dz = Math.cos(turn);
    }
    const [minX, minZ, maxX, maxZ] = RV_MAP_BOUNDS[rv.data.mapNumber] || RV_MAP_BOUNDS[11];
    return {
        x: (cam.x + dx * ahead - minX) / (maxX - minX) * 100,
        y: (cam.z + dz * ahead - minZ) / (maxZ - minZ) * 100
    };
}

function scheduleReplayUtterances(t) {
    const rv = replayVoice;
    const list = rv.data.utterances;
    let lo = 0, hi = list.length;
    while (lo < hi) {
        const mid = (lo + hi) >> 1;
        if (list[mid].g < t - RV_MAX_UTTERANCE_S) lo = mid + 1; else hi = mid;
    }

    for (let i = lo; i < list.length && list[i].g < t + RV_PREFETCH_S; i++) {
        const u = list[i];
        if (u.g + u.d <= t || rv.played.has(i)) continue;
        if (!rv.buffers.has(i)) {
            decodeReplayUtterance(i);
            continue;
        }
        const clip = rv.buffers.get(i);
        if (!clip || u.g > t + RV_LOOKAHEAD_S) continue;
        rv.played.add(i);
        if (t - u.g >= clip.duration - 0.05) continue;
        startReplayClip(i, u, clip);
    }
}

function startReplayClip(i, u, clip) {
    const rv = replayVoice;
    const speed = rv.anchor.speed;
    const el = new Audio();
    el.preservesPitch = true;
    el.preload = 'auto';
    el.src = clip.url;
    el.defaultPlaybackRate = speed;
    el.playbackRate = speed;
    const source = rv.ctx.createMediaElementSource(el);
    source.connect(rv.speakers[u.s].panner);

    const entry = {el, source, timer: null, started: false};
    rv.playing.set(i, entry);
    el.onended = () => {
        if (rv.playing.get(i) !== entry) return;
        releaseReplayClip(entry);
        rv.playing.delete(i);
    };
    const begin = () => {
        if (rv.playing.get(i) !== entry || !rv.anchor) return;
        const late = replayTimeAt(performance.now() / 1000) - u.g;
        if (late > 0.03) el.currentTime = late;
        entry.started = true;
        el.play().catch(() => {});
    };
    const wait = (u.g - replayTimeAt(performance.now() / 1000)) / speed;
    if (wait > 0.005) entry.timer = setTimeout(begin, wait * 1000);
    else begin();
}

function decodeReplayUtterance(i) {
    const rv = replayVoice;
    if (rv.decoding.has(i)) return;
    rv.decoding.add(i);
    const token = rv.token;
    const u = rv.data.utterances[i];
    decodeOpusUtterance(new Uint8Array(rv.bytes, u.o, u.n), u.sr)
        .then(pcm => pcm ? {url: URL.createObjectURL(monoWav(pcm.samples, pcm.rate)), duration: pcm.samples.length / pcm.rate} : null)
        .catch(() => null)
        .then(clip => {
            if (token !== rv.token) {
                if (clip) URL.revokeObjectURL(clip.url);
                return;
            }
            rv.decoding.delete(i);
            rv.buffers.set(i, clip);
            if (rv.buffers.size > RV_MAX_CACHED) {
                const oldest = rv.buffers.keys().next().value;
                if (!rv.playing.has(oldest)) {
                    const old = rv.buffers.get(oldest);
                    if (old) URL.revokeObjectURL(old.url);
                    rv.buffers.delete(oldest);
                }
            }
        });
}

function monoWav(samples, rate) {
    const buffer = new ArrayBuffer(44 + samples.length * 2);
    const view = new DataView(buffer);
    const text = (o, str) => { for (let k = 0; k < str.length; k++) view.setUint8(o + k, str.charCodeAt(k)); };
    text(0, 'RIFF');
    view.setUint32(4, 36 + samples.length * 2, true);
    text(8, 'WAVE');
    text(12, 'fmt ');
    view.setUint32(16, 16, true);
    view.setUint16(20, 1, true);
    view.setUint16(22, 1, true);
    view.setUint32(24, rate, true);
    view.setUint32(28, rate * 2, true);
    view.setUint16(32, 2, true);
    view.setUint16(34, 16, true);
    text(36, 'data');
    view.setUint32(40, samples.length * 2, true);
    for (let k = 0; k < samples.length; k++) {
        const v = Math.max(-1, Math.min(1, samples[k]));
        view.setInt16(44 + k * 2, v < 0 ? v * 0x8000 : v * 0x7fff, true);
    }
    return new Blob([buffer], {type: 'audio/wav'});
}

async function decodeOpusUtterance(bytes, sampleRate) {
    const packets = [];
    for (let o = 0; o + 2 <= bytes.length;) {
        const len = bytes[o] | (bytes[o + 1] << 8);
        o += 2;
        if (o + len > bytes.length) break;
        packets.push(bytes.subarray(o, o + len));
        o += len;
    }
    if (packets.length === 0) return null;

    const chunks = [];
    let rate = sampleRate;
    let failed = null;
    const decoder = new AudioDecoder({
        output: audio => {
            const samples = new Float32Array(audio.numberOfFrames);
            audio.copyTo(samples, {planeIndex: 0, format: 'f32-planar'});
            rate = audio.sampleRate;
            chunks.push(samples);
            audio.close();
        },
        error: e => { failed = e; }
    });
    decoder.configure({codec: 'opus', sampleRate, numberOfChannels: 1});
    packets.forEach((p, k) => decoder.decode(new EncodedAudioChunk({type: 'key', timestamp: k * 20000, data: p})));
    await decoder.flush();
    decoder.close();
    if (failed) throw failed;

    const total = chunks.reduce((sum, c) => sum + c.length, 0);
    if (total === 0) return null;
    const samples = new Float32Array(total);
    let o = 0;
    for (const c of chunks) {
        samples.set(c, o);
        o += c.length;
    }
    return {samples, rate};
}
