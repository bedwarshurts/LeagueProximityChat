const VA_BITRATE = 24000;
const VA_MIN_UTTERANCE_S = 0.15;
const VA_UPLOAD_MS = 8000;
const VA_UPLOAD_BYTES = 96 * 1024;
const VA_POS_MIN_MS = 200;
const VA_POS_IDLE_MS = 2000;
const VA_FINISH_WAIT_MS = 1500;

const voiceArchive = {
    matchId: null,
    module: null,
    moduleCtx: null,
    taps: new Map(),
    pending: [],
    pendingBytes: 0,
    positions: {},
    positionCount: 0,
    lastPos: {},
    lastUpload: 0,
    chain: Promise.resolve(),
    warned: false
};

function voiceArchiveSupported() {
    return Boolean(window.AudioWorkletNode && window.AudioEncoder && window.AudioData);
}

function beginVoiceArchive(matchId) {
    if (voiceArchive.matchId === (matchId || null)) return;
    voiceArchive.matchId = matchId || null;
    voiceArchive.pending = [];
    voiceArchive.pendingBytes = 0;
    voiceArchive.positions = {};
    voiceArchive.positionCount = 0;
    voiceArchive.lastPos = {};
    voiceArchive.lastUpload = Date.now();
    if (voiceArchive.matchId && !voiceArchiveSupported() && !voiceArchive.warned) {
        voiceArchive.warned = true;
        potgLog('this browser cannot record voice for replays (no AudioWorklet/WebCodecs)');
    }
}

function loadVoiceTapModule(ctx) {
    if (!voiceArchive.module || voiceArchive.moduleCtx !== ctx) {
        voiceArchive.moduleCtx = ctx;
        voiceArchive.module = ctx.audioWorklet.addModule('js/voice-tap-worklet.js').then(() => true, e => {
            potgLog(`voice recording unavailable (${e && e.message ? e.message : e})`);
            return false;
        });
    }
    return voiceArchive.module;
}

async function tapVoice(identity, source, track, ownsSource = false) {
    if (!voiceArchive.matchId || !audioCtx || !identity || !source || !voiceArchiveSupported()) return;
    const existing = voiceArchive.taps.get(identity);
    if (existing && existing.track === track) return;
    if (existing) untapVoice(identity);

    const tap = {identity, source, track, ownsSource, node: null, open: null, finishing: Promise.resolve(), closed: null};
    voiceArchive.taps.set(identity, tap);
    if (!(await loadVoiceTapModule(audioCtx)) || voiceArchive.taps.get(identity) !== tap) return;

    try {
        tap.node = new AudioWorkletNode(audioCtx, 'lpc-voice-tap', {
            numberOfInputs: 1, numberOfOutputs: 0, channelCount: 1, channelCountMode: 'explicit'
        });
    } catch (e) {
        potgLog(`voice recording could not start for ${identity} (${e && e.message ? e.message : e})`);
        voiceArchive.taps.delete(identity);
        return;
    }
    tap.closed = new Promise(res => { tap.onClosed = res; });
    tap.node.port.onmessage = e => onVoiceTapMessage(tap, e.data);
    source.connect(tap.node);
}

function untapVoice(identity) {
    const tap = voiceArchive.taps.get(identity);
    if (!tap) return Promise.resolve();
    voiceArchive.taps.delete(identity);
    if (tap.ownsSource) {
        try { tap.source.disconnect(); } catch (e) {}
    }
    if (!tap.node) return Promise.resolve();
    try { tap.source.disconnect(tap.node); } catch (e) {}
    tap.node.port.postMessage('stop');
    return tap.closed;
}

function tapLocalVoice() {
    if (!room || !audioCtx || !localUserIdentity || !voiceArchive.matchId) return;
    try {
        const pubs = Array.from(room.localParticipant.audioTrackPublications.values());
        const track = pubs.length > 0 && pubs[0].track ? pubs[0].track.mediaStreamTrack : null;
        if (!track) return;
        const existing = voiceArchive.taps.get(localUserIdentity);
        if (existing && existing.track === track) return;
        tapVoice(localUserIdentity, audioCtx.createMediaStreamSource(new MediaStream([track])), track, true);
    } catch (e) {}
}

function onVoiceTapMessage(tap, msg) {
    if (msg.type === 'start') {
        const startMs = Date.now() - (audioCtx.currentTime - msg.time) * 1000;
        tap.open = newUtterance(tap.identity, startMs);
    } else if (msg.type === 'audio') {
        if (tap.open) encodeUtteranceAudio(tap.open, msg.samples);
    } else if (msg.type === 'end') {
        if (tap.open) tap.finishing = finishUtterance(tap.open);
        tap.open = null;
    } else if (msg.type === 'stopped') {
        tap.finishing.then(() => tap.onClosed());
    }
}

function newUtterance(identity, startMs) {
    const u = {s: identity, t: Math.round(startMs), sr: audioCtx.sampleRate, outRate: 48000, frames: 0, packets: [], bytes: 0, failed: false, encoder: null};
    try {
        u.encoder = new AudioEncoder({
            output: (chunk, meta) => {
                const packet = new Uint8Array(chunk.byteLength);
                chunk.copyTo(packet);
                u.packets.push(packet);
                u.bytes += packet.length + 2;
                if (meta && meta.decoderConfig && meta.decoderConfig.sampleRate) u.outRate = meta.decoderConfig.sampleRate;
            },
            error: () => { u.failed = true; }
        });
        u.encoder.configure({codec: 'opus', sampleRate: u.sr, numberOfChannels: 1, bitrate: VA_BITRATE});
    } catch (e) {
        u.failed = true;
    }
    return u;
}

function encodeUtteranceAudio(u, samples) {
    if (u.failed) return;
    try {
        const data = new AudioData({
            format: 'f32-planar', sampleRate: u.sr, numberOfFrames: samples.length, numberOfChannels: 1,
            timestamp: Math.round(u.frames * 1e6 / u.sr), data: samples
        });
        u.encoder.encode(data);
        data.close();
        u.frames += samples.length;
    } catch (e) {
        u.failed = true;
    }
}

async function finishUtterance(u) {
    if (!u.failed) {
        try { await u.encoder.flush(); } catch (e) { u.failed = true; }
    }
    try { u.encoder.close(); } catch (e) {}
    if (u.failed || u.packets.length === 0 || u.frames < u.sr * VA_MIN_UTTERANCE_S) return;
    voiceArchive.pending.push({s: u.s, t: u.t, d: Math.round(u.frames * 1000 / u.sr), sr: u.outRate, packets: u.packets});
    voiceArchive.pendingBytes += u.bytes;
    uploadVoiceArchive();
}

function noteArchivePosition(identity, pos) {
    if (!voiceArchive.matchId || !identity || !pos || !isFinite(pos.x) || !isFinite(pos.y)) return;
    const now = Date.now();
    const x = Math.round(pos.x * 100) / 100;
    const y = Math.round(pos.y * 100) / 100;
    const dead = pos.isDead ? 1 : 0;
    const last = voiceArchive.lastPos[identity];
    if (last && last.dead === dead) {
        const moved = Math.abs(last.x - x) + Math.abs(last.y - y) > 0.05;
        if (now - last.t < VA_POS_MIN_MS || (!moved && now - last.t < VA_POS_IDLE_MS)) return;
    }
    voiceArchive.lastPos[identity] = {t: now, x, y, dead};
    (voiceArchive.positions[identity] ||= []).push([now, x, y, dead]);
    voiceArchive.positionCount++;
    uploadVoiceArchive();
}

function uploadVoiceArchive(force = false) {
    const va = voiceArchive;
    if (!va.matchId || (va.pending.length === 0 && va.positionCount === 0)) return va.chain;
    if (!force && va.pendingBytes < VA_UPLOAD_BYTES && Date.now() - va.lastUpload < VA_UPLOAD_MS) return va.chain;

    const body = buildVoiceUpload(va.pending, va.positions);
    const matchId = va.matchId;
    va.pending = [];
    va.pendingBytes = 0;
    va.positions = {};
    va.positionCount = 0;
    va.lastUpload = Date.now();
    va.chain = va.chain.then(() => postVoiceUpload(matchId, body));
    return va.chain;
}

function buildVoiceUpload(utterances, positions) {
    const header = {utterances: [], positions};
    const payloads = utterances.map(u => {
        const size = u.packets.reduce((sum, p) => sum + 2 + p.length, 0);
        const out = new Uint8Array(size);
        let o = 0;
        for (const p of u.packets) {
            out[o] = p.length & 0xff;
            out[o + 1] = p.length >> 8;
            out.set(p, o + 2);
            o += 2 + p.length;
        }
        header.utterances.push({s: u.s, t: u.t, d: u.d, sr: u.sr, n: size});
        return out;
    });
    const headerBytes = new TextEncoder().encode(JSON.stringify(header));
    const length = new Uint8Array(4);
    new DataView(length.buffer).setUint32(0, headerBytes.length, true);
    return new Blob([length, headerBytes, ...payloads]);
}

async function postVoiceUpload(matchId, body) {
    for (let attempt = 0; attempt < 3; attempt++) {
        try {
            const resp = await fetch(`/matches/${encodeURIComponent(matchId)}/voice`, {method: 'POST', body});
            if (resp.ok || resp.status === 409) return;
        } catch (e) {}
        await new Promise(r => setTimeout(r, 1000 * (attempt + 1)));
    }
    potgLog('some recorded voice could not be saved for the replay');
}

async function finishVoiceArchive() {
    const matchId = voiceArchive.matchId;
    if (!matchId) return;
    const closing = [...voiceArchive.taps.keys()].map(untapVoice);
    await Promise.race([Promise.all(closing), new Promise(r => setTimeout(r, VA_FINISH_WAIT_MS))]);
    await uploadVoiceArchive(true);
    if (voiceArchive.matchId === matchId) voiceArchive.matchId = null;
}
