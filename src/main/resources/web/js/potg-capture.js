// play of the game
const potgAudio = { node: null, tapBus: null, micSrc: null, chunks: [], clips: new Map() };

function retapPotgMic() {
    if (!audioCtx || !potgAudio.tapBus || !room) return;
    try { if (potgAudio.micSrc) potgAudio.micSrc.disconnect(); } catch (e) {}
    potgAudio.micSrc = null;
    try {
        const pubs = Array.from(room.localParticipant.audioTrackPublications.values());
        const track = pubs.length > 0 ? pubs[0].track : null;
        if (track) {
            potgAudio.micSrc = audioCtx.createMediaStreamSource(new MediaStream([track.mediaStreamTrack]));
            potgAudio.micSrc.connect(potgAudio.tapBus);
        }
    } catch (e) {}
    tapLocalVoice();
}

function startPotgRecording() {
    if (lowPerformanceMode) return;
    if (!audioCtx || potgAudio.node) return;
    try {
        const bus = audioCtx.createGain();
        if (masterGain) masterGain.connect(bus);
        potgAudio.tapBus = bus;
        retapPotgMic();


        const proc = audioCtx.createScriptProcessor(4096, 2, 2);
        proc.onaudioprocess = (e) => {
            const ib = e.inputBuffer;
            const l = ib.getChannelData(0);
            const r = ib.numberOfChannels > 1 ? ib.getChannelData(1) : l;
            potgAudio.chunks.push({
                t: Date.now() - (l.length / audioCtx.sampleRate) * 1000,
                l: new Float32Array(l),
                r: new Float32Array(r)
            });
            const cutoff = Date.now() - 120000;
            while (potgAudio.chunks.length && potgAudio.chunks[0].t < cutoff) potgAudio.chunks.shift();
        };
        bus.connect(proc);
        proc.connect(audioCtx.destination);
        potgAudio.node = proc;
        potgAudio.tapBus = bus;
    } catch (e) {}
}

function stopPotgRecording() {
    try { if (potgAudio.node) potgAudio.node.disconnect(); } catch (e) {}
    try { if (potgAudio.tapBus) potgAudio.tapBus.disconnect(); } catch (e) {}
    try { if (potgAudio.micSrc) potgAudio.micSrc.disconnect(); } catch (e) {}
    potgAudio.node = null;
    potgAudio.tapBus = null;
    potgAudio.micSrc = null;
    potgAudio.chunks = [];
}

function encodeWavPCM16(left, right, sampleRate) {
    const frames = left.length;
    const dataBytes = frames * 4;
    const buf = new ArrayBuffer(44 + dataBytes);
    const dv = new DataView(buf);
    const writeStr = (o, s) => { for (let i = 0; i < s.length; i++) dv.setUint8(o + i, s.charCodeAt(i)); };
    writeStr(0, 'RIFF');
    dv.setUint32(4, 36 + dataBytes, true);
    writeStr(8, 'WAVE');
    writeStr(12, 'fmt ');
    dv.setUint32(16, 16, true);
    dv.setUint16(20, 1, true);
    dv.setUint16(22, 2, true);
    dv.setUint32(24, sampleRate, true);
    dv.setUint32(28, sampleRate * 4, true);
    dv.setUint16(32, 4, true);
    dv.setUint16(34, 16, true);
    writeStr(36, 'data');
    dv.setUint32(40, dataBytes, true);
    let off = 44;
    for (let i = 0; i < frames; i++) {
        const sl = Math.max(-1, Math.min(1, left[i]));
        const sr2 = Math.max(-1, Math.min(1, right[i]));
        dv.setInt16(off, sl < 0 ? sl * 0x8000 : sl * 0x7FFF, true); off += 2;
        dv.setInt16(off, sr2 < 0 ? sr2 * 0x8000 : sr2 * 0x7FFF, true); off += 2;
    }
    return new Blob([buf], {type: 'audio/wav'});
}

function sliceVoiceClip(startMs, endMs) {
    if (!audioCtx || potgAudio.chunks.length === 0) return null;
    const sr = audioCtx.sampleRate;
    const parts = potgAudio.chunks.filter(c => (c.t + (c.l.length / sr) * 1000) >= startMs && c.t <= endMs);
    if (parts.length === 0) return null;

    let total = 0;
    parts.forEach(c => total += c.l.length);
    const allL = new Float32Array(total);
    const allR = new Float32Array(total);
    let o = 0;
    parts.forEach(c => { allL.set(c.l, o); allR.set(c.r, o); o += c.l.length; });

    const firstT = parts[0].t;
    const startIdx = Math.max(0, Math.round(((startMs - firstT) / 1000) * sr));
    const endIdx = Math.min(total, Math.round(((endMs - firstT) / 1000) * sr));
    if (endIdx <= startIdx) return null;

    return encodeWavPCM16(allL.subarray(startIdx, endIdx), allR.subarray(startIdx, endIdx), sr);
}

const potgVideo = { stream: null, recorders: [], segments: [], lockedClips: new Map(), pendingMarks: [], active: false, onSegmentFinalized: null };

const POTG_SEG_MS = 40000;
const POTG_SEG_OVERLAP_MS = 1500;
const POTG_EDGE_SLACK_MS = 300;

const POTG_GPU_CHECK_WIDTH = 1920;
const POTG_GPU_CHECK_HEIGHT = 1080;
const POTG_GPU_CODECS = [
    ['vp09.00.40.08', 'video/webm;codecs=vp9'],
    ['avc1.640028', 'video/webm;codecs=h264'],
    ['av01.0.08M.08', 'video/webm;codecs=av1']
];
let potgPlan = null;

async function planPotgRecording() {
    if (potgPlan) return potgPlan;
    const gpu = [];
    if (window.VideoEncoder) {
        for (const [codec, mime] of POTG_GPU_CODECS) {
            if (!MediaRecorder.isTypeSupported(mime)) continue;
            try {
                const check = await VideoEncoder.isConfigSupported({
                    codec, width: POTG_GPU_CHECK_WIDTH, height: POTG_GPU_CHECK_HEIGHT, bitrate: 14000000, framerate: 60,
                    hardwareAcceleration: 'prefer-hardware'
                });
                if (check.supported) gpu.push(mime);
            } catch (e) {}
        }
    }
    const bigScreen = screen.width * devicePixelRatio > 1920 || screen.height * devicePixelRatio > 1080;
    potgPlan = gpu.length > 0
        ? {codecs: [...gpu, 'video/webm;codecs=vp8', ''], gpu: true, frameRate: 60,
            maxWidth: bigScreen ? 2560 : 1920, maxHeight: bigScreen ? 1440 : 1080, bitrate: bigScreen ? 20000000 : 14000000}
        : {codecs: ['video/webm;codecs=vp8', 'video/webm;codecs=vp9', ''], gpu: false, frameRate: 30,
            maxWidth: 1920, maxHeight: 1080, bitrate: 8000000};
    potgLog(`recording plan: ${potgPlan.gpu ? `GPU encoder (${gpu.join(', ')})` : 'software encoder'}, `
        + `up to ${potgPlan.maxWidth}x${potgPlan.maxHeight}@${potgPlan.frameRate}fps, ${potgPlan.bitrate / 1e6} Mbps`);
    return potgPlan;
}

function reportScreenRecording(active) {
    try {
        if (trackerSocket && trackerSocket.readyState === WebSocket.OPEN) {
            trackerSocket.send(JSON.stringify({type: 'SCREEN_RECORDING', active}));
        }
    } catch (e) {}
}

function potgLog(msg) {
    console.log('[PotG] ' + msg);
    try {
        if (trackerSocket && trackerSocket.readyState === WebSocket.OPEN) {
            trackerSocket.send(JSON.stringify({type: 'CLIENT_LOG', msg: '[PotG] ' + msg}));
        }
    } catch (e) {}
}

function requestStreamWithTimeout(factory, ms) {
    return new Promise((resolve, reject) => {
        let timedOut = false;
        const timer = setTimeout(() => {
            timedOut = true;
            reject(new DOMException('timeout', 'TimeoutError'));
        }, ms);
        factory().then(s => {
            clearTimeout(timer);
            if (timedOut) {
                try { s.getTracks().forEach(t => t.stop()); } catch (e) {}
            } else {
                resolve(s);
            }
        }).catch(err => {
            clearTimeout(timer);
            reject(err);
        });
    });
}

const CAPTURE_METHODS = [
    ['getDisplayMedia', plan => navigator.mediaDevices.getDisplayMedia({
        video: {width: {max: plan.maxWidth}, height: {max: plan.maxHeight}, frameRate: {ideal: plan.frameRate, max: plan.frameRate}},
        audio: false
    })],
    ['legacy screen capture', plan => navigator.mediaDevices.getUserMedia({
        audio: false,
        video: {mandatory: {chromeMediaSource: 'screen', maxWidth: plan.maxWidth, maxHeight: plan.maxHeight, maxFrameRate: plan.frameRate}}
    })],
    ['legacy desktop capture', plan => navigator.mediaDevices.getUserMedia({
        audio: false,
        video: {mandatory: {chromeMediaSource: 'desktop', maxWidth: plan.maxWidth, maxHeight: plan.maxHeight, maxFrameRate: plan.frameRate}}
    })]
];

async function acquireScreenStream(startIndex, plan) {
    let lastError = null;
    for (let i = 0; i < CAPTURE_METHODS.length; i++) {
        const idx = (startIndex + i) % CAPTURE_METHODS.length;
        const [label, factory] = CAPTURE_METHODS[idx];
        try {
            const stream = await requestStreamWithTimeout(() => factory(plan), 4000);
            potgLog(`capture method: ${label}`);
            return {stream, methodIndex: idx};
        } catch (e) {
            lastError = e;
            potgLog(`${label} failed (${e && e.name ? e.name : e}) - trying next capture method`);
        }
    }
    throw lastError || new DOMException('no capture method', 'NotSupportedError');
}

let potgRetryMs = 5000;
const POTG_METHOD_KEY = 'potgCaptureMethodIdx';

function savedCaptureMethodIndex() {
    try {
        const v = parseInt(localStorage.getItem(POTG_METHOD_KEY), 10);
        if (v >= 0 && v < CAPTURE_METHODS.length) return v;
    } catch (e) {
    }
    return 0;
}

async function startPotgVideoCapture() {
    if (lowPerformanceMode) {
        potgLog('low performance mode is on - skipping highlight screen capture');
        return;
    }
    if (potgVideo.active || potgVideo.stream) return;

    try {
        const plan = await planPotgRecording();
        let acq = await acquireScreenStream(savedCaptureMethodIndex(), plan);
        potgVideo.stream = acq.stream;
        potgVideo.active = true;
        acq.stream.getVideoTracks()[0].addEventListener('ended', stopPotgVideoCapture);

        const st = acq.stream.getVideoTracks()[0].getSettings();
        potgLog(`screen capture running: ${st.width || '?'}x${st.height || '?'}@${Math.round(st.frameRate || 0)}fps`);

        let tries = 0;
        while (potgVideo.workingMime === undefined && potgVideo.active) {
            await selectValidatedCodec(tries === 0);
            if (potgVideo.workingMime !== undefined) break;
            tries++;
            if (tries >= 24) {
                potgLog('no playable recording codec found - clips will use the backend frame buffer');
                stopPotgVideoCapture();
                return;
            }
            if (tries === 1) potgLog('codec validation inconclusive (static screen?) - will keep retrying');
            if (tries % 2 === 0) {
                potgLog('capture still producing nothing - switching capture method');
                try { potgVideo.stream.getTracks().forEach(t => t.stop()); } catch (e) {}
                try {
                    acq = await acquireScreenStream((acq.methodIndex + 1) % CAPTURE_METHODS.length, plan);
                    potgVideo.stream = acq.stream;
                    acq.stream.getVideoTracks()[0].addEventListener('ended', stopPotgVideoCapture);
                } catch (e) {
                    potgLog('capture re-acquisition failed - clips will use the backend frame buffer');
                    stopPotgVideoCapture();
                    return;
                }
            }
            await new Promise(r => setTimeout(r, potgRetryMs));
        }
        if (!potgVideo.active || potgVideo.workingMime === undefined) return;

        try { localStorage.setItem(POTG_METHOD_KEY, String(acq.methodIndex)); } catch (e) {}

        spawnPotgSegment();
        reportScreenRecording(true);
    } catch (e) {
        potgLog(`screen capture unavailable (${e && e.name ? e.name : e}) - clips will use the backend frame buffer instead`);
    }
}

function validateCodecPlayback(mime) {
    return new Promise(resolve => {
        let rec;
        try {
            const opts = {videoBitsPerSecond: 2000000};
            if (mime) opts.mimeType = mime;
            rec = new MediaRecorder(potgVideo.stream, opts);
        } catch (e) {
            resolve({ok: false, why: 'not constructible'});
            return;
        }

        const parts = [];
        rec.ondataavailable = ev => { if (ev.data && ev.data.size > 0) parts.push(ev.data); };
        rec.onstop = () => {
            const blob = new Blob(parts, {type: rec.mimeType || 'video/webm'});
            if (blob.size < 1024) {
                resolve({ok: false, why: `empty recording (${blob.size}B)`});
                return;
            }
            const v = document.createElement('video');
            v.muted = true;
            const url = URL.createObjectURL(blob);
            let settled = false;
            const done = (ok, why) => {
                if (settled) return;
                settled = true;
                try { URL.revokeObjectURL(url); } catch (e) {}
                resolve({ok, why: why || ''});
            };
            v.onerror = () => done(false, `decode error code ${v.error ? v.error.code : '?'}`);
            v.onloadeddata = () => done(true);
            setTimeout(() => done(false, `decode timeout (${Math.round(blob.size / 1024)}KB)`), 4000);
            v.src = url;
        };

        try {
            rec.start(250);
        } catch (e) {
            resolve({ok: false, why: 'start failed'});
            return;
        }
        setTimeout(() => {
            try { rec.stop(); } catch (e) { resolve({ok: false, why: 'stop failed'}); }
        }, 1500);
    });
}

async function selectValidatedCodec(verbose) {
    const candidates = potgPlan ? potgPlan.codecs : ['video/webm;codecs=vp8', 'video/webm;codecs=vp9', ''];
    for (const mime of candidates) {
        const res = await validateCodecPlayback(mime);
        if (res.ok) {
            potgVideo.workingMime = mime;
            potgLog(`recording codec validated: ${mime || 'browser default'}`);
            return;
        }
        if (verbose) potgLog(`codec ${mime || 'browser default'} skipped: ${res.why}`);
        if (res.why.startsWith('empty recording')) return;
    }
}

function spawnPotgSegment() {
    if (!potgVideo.active || !potgVideo.stream || potgVideo.workingMime === undefined) return;

    const startMs = Date.now();
    const parts = [];
    let rec;
    try {
        const opts = {videoBitsPerSecond: potgPlan ? potgPlan.bitrate : 8000000};
        if (potgVideo.workingMime) opts.mimeType = potgVideo.workingMime;
        rec = new MediaRecorder(potgVideo.stream, opts);
    } catch (e) {
        potgLog('recorder construction failed - video clips disabled, using backend frames');
        stopPotgVideoCapture();
        return;
    }

    rec.ondataavailable = ev => { if (ev.data && ev.data.size > 0) parts.push(ev.data); };
    rec.onstop = () => {
        potgVideo.recorders = potgVideo.recorders.filter(r => r !== rec);
        potgVideo.segments.push({startMs, endMs: Date.now(), blob: new Blob(parts, {type: rec.mimeType || 'video/webm'})});
        const cutoff = Date.now() - 60000;
        potgVideo.segments = potgVideo.segments.filter(s => s.endMs >= cutoff);
        tryResolvePotgMarks();
        if (potgVideo.onSegmentFinalized) potgVideo.onSegmentFinalized();
    };

    try {
        rec.start();
    } catch (e) {
        potgLog('recorder start failed - video clips disabled, using backend frames');
        stopPotgVideoCapture();
        return;
    }
    potgVideo.recorders.push(rec);
    setTimeout(() => { if (potgVideo.active && rec.state === 'recording') spawnPotgSegment(); }, POTG_SEG_MS - POTG_SEG_OVERLAP_MS);
    setTimeout(() => { try { if (rec.state === 'recording' && potgVideo.active) rec.stop(); } catch (e) {} }, POTG_SEG_MS);
}

async function finalizePotgVideoCapture() {
    if (!potgVideo.active) {
        tryResolvePotgMarks();
        return;
    }
    potgVideo.active = false;
    reportScreenRecording(false);

    const running = potgVideo.recorders.filter(r => r && r.state === 'recording');
    if (running.length > 0) {
        await new Promise(resolve => {
            let left = running.length;
            const timer = setTimeout(resolve, 3000);
            potgVideo.onSegmentFinalized = () => {
                if (--left <= 0) {
                    clearTimeout(timer);
                    potgVideo.onSegmentFinalized = null;
                    resolve();
                }
            };
            running.forEach(r => { try { r.stop(); } catch (e) {} });
        });
    }
    try { if (potgVideo.stream) potgVideo.stream.getTracks().forEach(t => t.stop()); } catch (e) {}
    potgVideo.stream = null;
    potgVideo.recorders = [];
    tryResolvePotgMarks();
}

function stopPotgVideoCapture() {
    potgVideo.active = false;
    reportScreenRecording(false);
    potgVideo.recorders.forEach(r => { try { if (r && r.state === 'recording') r.stop(); } catch (e) {} });
    potgVideo.recorders = [];
    try { if (potgVideo.stream) potgVideo.stream.getTracks().forEach(t => t.stop()); } catch (e) {}
    potgVideo.stream = null;
}

const clipPart = (seg, startMs, endMs) => ({blob: seg.blob, segStartMs: seg.startMs, startMs, endMs});

function coverMark(m) {
    const segs = potgVideo.segments;
    const whole = segs.find(s => s.startMs <= m.startMs + POTG_EDGE_SLACK_MS && s.endMs >= m.endMs - POTG_EDGE_SLACK_MS);
    if (whole) return [clipPart(whole, m.startMs, m.endMs)];

    for (const a of segs) {
        if (a.startMs > m.startMs + POTG_EDGE_SLACK_MS) continue;
        for (const b of segs) {
            if (b.startMs <= a.startMs || b.startMs > a.endMs || b.endMs < m.endMs - POTG_EDGE_SLACK_MS) continue;
            const cut = Math.min(Math.max((b.startMs + a.endMs) / 2, m.startMs), m.endMs);
            return [clipPart(a, m.startMs, cut), clipPart(b, cut, m.endMs)];
        }
    }
    return null;
}

function tryResolvePotgMarks() {
    for (let i = potgVideo.pendingMarks.length - 1; i >= 0; i--) {
        const m = potgVideo.pendingMarks[i];
        let parts = coverMark(m);
        let partial = false;

        if (!parts && !potgVideo.active) {
            let best = null;
            let bestOverlap = 0;
            for (const s of potgVideo.segments) {
                const overlap = Math.min(s.endMs, m.endMs) - Math.max(s.startMs, m.startMs);
                if (overlap > bestOverlap) {
                    bestOverlap = overlap;
                    best = s;
                }
            }
            if (best) parts = [clipPart(best, Math.max(m.startMs, best.startMs), Math.min(m.endMs, best.endMs))];
            partial = true;
        }
        if (!parts) continue;

        potgVideo.lockedClips.set(m.id, {parts});
        potgVideo.pendingMarks.splice(i, 1);
        const seconds = parts.reduce((sum, p) => sum + p.endMs - p.startMs, 0) / 1000;
        potgLog(`video clip locked: ${seconds.toFixed(1)}s from ${parts.length} chunk${parts.length > 1 ? 's' : ''}`
            + `${partial ? ' (partial coverage, capture ended mid-window)' : ''}`);
    }
}

function handlePotgMarks(marks) {
    const ids = new Set();
    for (const m of marks) {
        const id = String(m.id);
        ids.add(id);
        if (!potgAudio.clips.has(id)) {
            const wav = sliceVoiceClip(m.startEpochMs, m.endEpochMs);
            if (wav) potgAudio.clips.set(id, wav);
        }
        if (!potgVideo.lockedClips.has(id) && !potgVideo.pendingMarks.some(p => p.id === id)) {
            potgVideo.pendingMarks.push({id, startMs: m.startEpochMs, endMs: m.endEpochMs});
        }
    }

    for (const id of [...potgVideo.lockedClips.keys()]) if (!ids.has(id)) potgVideo.lockedClips.delete(id);
    for (const id of [...potgAudio.clips.keys()]) if (!ids.has(id)) potgAudio.clips.delete(id);
    potgVideo.pendingMarks = potgVideo.pendingMarks.filter(p => ids.has(p.id));
    tryResolvePotgMarks();
}
