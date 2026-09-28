let potgReplayData = null;
let potgAnimHandle = null;
let potgAudioEl = null;
let potgHighlights = [];
let potgCurrent = null;
let potgSaving = false;
let potgHistoryMatch = null;

let potgClipVolume = 1;
try { const v = parseFloat(localStorage.getItem('potgClipVolume')); if (v >= 0 && v <= 1) potgClipVolume = v; } catch (e) {}

async function loadFramesFor(h) {
    if (h.frames) return h.frames;
    let frames = [];
    if (Array.isArray(h.frameTimes) && h.frameTimes.length > 0) {
        const imgs = await Promise.all(h.frameTimes.map((relMs, i) => new Promise(res => {
            const im = new Image();
            im.onload = () => res({im, t: relMs});
            im.onerror = () => res(null);
            im.src = `/potg/frame/${h.index}/${i}`;
        })));
        frames = imgs.filter(Boolean);
    }
    h.frames = frames;
    return frames;
}

async function maybeShowPlayOfGame(matchId = null) {
    try {
        await finalizePotgVideoCapture();

        const meta = await (await fetch('/potg/meta', {cache: 'no-store'})).json();
        if (!meta.available || !Array.isArray(meta.clips) || meta.clips.length === 0) return;

        potgHighlights = meta.clips.map((c, i) => {
            const id = String(c.startEpochMs);
            return {
                index: i, id,
                headline: c.headline, score: c.score, gameClock: c.gameClock,
                startEpochMs: c.startEpochMs, endEpochMs: c.endEpochMs,
                frameTimes: c.frames, frames: null,
                video: potgVideo.lockedClips.get(id) || null,
                audio: potgAudio.clips.get(id) || null
            };
        });
        if (saveAllHighlights && matchId) autoSaveHighlights(matchId, potgHighlights.slice());

        const best = potgHighlights[0];
        await loadFramesFor(best);
        if (!best.video && best.frames.length === 0) return;

        potgLog(`showing ${potgHighlights.length} highlight(s), best via `
            + `${best.video ? `video (${best.video.parts.length} piece${best.video.parts.length > 1 ? 's' : ''})` : 'frame-flip fallback'} path`
            + `${best.audio ? ' with voice' : ''}`);

        showPotgIntro();
    } catch (e) {}
}

function openHighlightViewer(history = null) {
    potgHistoryMatch = history;
    const best = potgHighlights[0];
    if (!best) return;
    buildHighlightsStrip();
    document.getElementById('potg-highlights').style.display = 'none';
    const moreBtn = document.getElementById('potg-more');
    moreBtn.style.display = potgHighlights.length > 1 ? '' : 'none';
    moreBtn.innerText = 'Show More Highlights';
    document.getElementById('potg-save').style.display = history ? 'none' : '';
    document.getElementById('potg-save-status').innerText = '';
    const viewer = document.getElementById('potg-viewer');
    viewer.classList.remove('controls-hidden');
    fadeInOverlay(viewer, 'flex');
    showHighlight(best);
}

const POTG_INTRO_TEXT = 'Thanks for playing.\nWe hope you had fun, here are a few moments worth reliving.';
let potgIntroTimer = null;

function showPotgIntro() {
    const intro = document.getElementById('potg-intro');
    const textEl = document.getElementById('potg-intro-text');
    const btn = document.getElementById('potg-intro-btn');

    clearTimeout(potgIntroTimer);
    textEl.innerHTML = '<span class="intro-caret"></span>';
    btn.classList.remove('shown');
    fadeInOverlay(intro, 'flex');

    let i = 0;
    const render = (done) => {
        const shown = POTG_INTRO_TEXT.slice(0, i).replace(/\n/g, '<br>');
        textEl.innerHTML = shown + (done ? '' : '<span class="intro-caret"></span>');
    };
    const step = () => {
        if (i >= POTG_INTRO_TEXT.length) {
            render(true);
            btn.classList.add('shown');
            return;
        }
        i++;
        render(false);
        const ch = POTG_INTRO_TEXT[i - 1];
        const delay = (ch === '.' || ch === '\n') ? 420 : ch === ',' ? 240 : 42;
        potgIntroTimer = setTimeout(step, delay);
    };
    potgIntroTimer = setTimeout(step, 500);
}

function enterHighlightsFromIntro() {
    clearTimeout(potgIntroTimer);
    openHighlightViewer();
    fadeOutOverlay(document.getElementById('potg-intro'));
}

function buildHighlightsStrip() {
    const strip = document.getElementById('potg-highlights');
    strip.innerHTML = '';
    potgHighlights.forEach(h => {
        const card = document.createElement('div');
        card.className = 'potg-hl';
        card.id = `potg-hl-${h.index}`;
        const title = document.createElement('div');
        title.className = 'hl-title';
        title.innerText = h.headline;
        const meta = document.createElement('div');
        meta.className = 'hl-meta';
        meta.innerText = h.gameClock;
        card.appendChild(title);
        card.appendChild(meta);
        card.addEventListener('click', () => { if (!potgSaving) showHighlight(h); });
        strip.appendChild(card);
    });
}

async function showHighlight(h) {
    potgCurrent = h;
    document.querySelectorAll('.potg-hl').forEach(el => el.classList.remove('active'));
    const card = document.getElementById(`potg-hl-${h.index}`);
    if (card) card.classList.add('active');
    document.getElementById('potg-save-status').innerText = '';
    document.getElementById('potg-headline').innerText = `${h.headline}  ·  ${h.gameClock}`;
    await loadFramesFor(h);
    potgReplayData = {video: h.video, frames: h.frames, audio: h.audio, file: h.file};
    playPotg();
}

function potgPlayers() {
    return [document.getElementById('potg-video'), document.getElementById('potg-video-b')];
}

let potgPlayToken = 0;

function playPotg() {
    if (!potgReplayData) return;
    const {video, frames, audio, file} = potgReplayData;
    if (!file && !video && (!frames || frames.length === 0)) return;
    const canvas = document.getElementById('potg-canvas');
    const players = potgPlayers();

    potgPlaybackEnded = false;
    showPotgControls();

    potgPlayToken++;
    if (potgAnimHandle) cancelAnimationFrame(potgAnimHandle);
    if (potgAudioEl) { try { potgAudioEl.pause(); } catch (e) {} }
    players.forEach(p => { try { p.pause(); } catch (e) {} });
    potgAudioEl = audio ? new Audio(URL.createObjectURL(audio)) : null;
    if (potgAudioEl) {
        potgAudioEl.volume = potgClipVolume;
        routeToSpeaker(potgAudioEl);
    }

    if (file) {
        canvas.style.display = 'none';
        players[1].style.display = 'none';
        players[0].style.display = '';
        playPotgFile(players[0], file);
        return;
    }

    if (video) {
        canvas.style.display = 'none';
        playPotgVideo(video.parts);
        return;
    }

    players.forEach(p => { p.style.display = 'none'; });
    canvas.style.display = '';
    const ctx2d = canvas.getContext('2d');
    canvas.width = frames[0].im.naturalWidth;
    canvas.height = frames[0].im.naturalHeight;
    if (potgAudioEl) potgAudioEl.play().catch(() => {});

    const t0 = performance.now();
    const draw = () => {
        const elapsed = performance.now() - t0;
        let frame = frames[0];
        for (const f of frames) {
            if (f.t <= elapsed) frame = f; else break;
        }
        ctx2d.drawImage(frame.im, 0, 0);
        if (elapsed < frames[frames.length - 1].t + 500) {
            potgAnimHandle = requestAnimationFrame(draw);
        } else {
            onPotgPlaybackEnded();
        }
    };
    draw();
}

function fallbackToFrames() {
    potgPlayToken++;
    potgPlayers().forEach(p => { try { p.pause(); } catch (e) {} });
    if (potgCurrent) potgCurrent.video = null;
    if (potgReplayData && potgReplayData.video
        && potgReplayData.frames && potgReplayData.frames.length > 0) {
        potgReplayData = {video: null, frames: potgReplayData.frames, audio: potgReplayData.audio};
        playPotg();
    }
}

const partFrom = part => Math.max(0, (part.startMs - part.segStartMs) / 1000);
const partTo = part => Math.max(partFrom(part) + 0.2, (part.endMs - part.segStartMs) / 1000);

async function preparePotgVideoSrc(videoEl, part) {
    const key = String(part.segStartMs);
    if (videoEl.dataset.segmentFor === key) return;
    if (videoEl.src && videoEl.src.startsWith('blob:')) { try { URL.revokeObjectURL(videoEl.src); } catch (e) {} }
    videoEl.src = URL.createObjectURL(part.blob);
    videoEl.dataset.segmentFor = key;
    await new Promise(res => { videoEl.onloadedmetadata = res; setTimeout(res, 2000); });
    if (!isFinite(videoEl.duration)) {
        await new Promise(res => {
            videoEl.ondurationchange = () => { if (isFinite(videoEl.duration)) res(); };
            videoEl.currentTime = Number.MAX_SAFE_INTEGER;
            setTimeout(res, 2000);
        });
    }
}

async function cuePart(videoEl, part) {
    videoEl.muted = true;
    videoEl.ontimeupdate = null;
    videoEl.onended = null;
    await preparePotgVideoSrc(videoEl, part);
    let seekTo = partFrom(part);
    if (isFinite(videoEl.duration) && seekTo >= videoEl.duration - 0.3) seekTo = 0;
    videoEl.currentTime = seekTo;
    await new Promise(res => { videoEl.onseeked = res; setTimeout(res, 1500); });
}

function playPartToEnd(videoEl, part, {nextTick, onFrame = () => {}, isCurrent = () => true}) {
    const end = partTo(part);
    return new Promise((resolve, reject) => {
        let lastTime = -1;
        let lastMove = performance.now();
        const step = () => {
            if (!isCurrent()) { videoEl.pause(); resolve(false); return; }
            onFrame(videoEl);
            if (videoEl.currentTime >= end || videoEl.ended) { videoEl.pause(); resolve(true); return; }
            if (videoEl.currentTime !== lastTime) {
                lastTime = videoEl.currentTime;
                lastMove = performance.now();
            } else if (performance.now() - lastMove > 4000) {
                videoEl.pause();
                reject(new Error('video stalled'));
                return;
            }
            nextTick(step, videoEl);
        };
        videoEl.play().catch(() => {});
        step();
    });
}

function onNextVideoFrame(frameMs) {
    return (step, videoEl) => {
        let ran = false;
        const run = () => {
            if (ran) return;
            ran = true;
            step();
        };
        if (videoEl.requestVideoFrameCallback) videoEl.requestVideoFrameCallback(run);
        setTimeout(run, frameMs * 2.5);
    };
}

async function playPieces(parts, players, options) {
    await cuePart(players[0], parts[0]);
    const secondCued = parts.length > 1 ? cuePart(players[1], parts[1]) : null;
    for (let i = 0; i < parts.length; i++) {
        const player = players[i % 2];
        if (i > 0) await secondCued;
        if (options.onStart) options.onStart(player, i);
        if (!(await playPartToEnd(player, parts[i], options))) return false;
    }
    return true;
}

function playPotgFile(videoEl, url) {
    videoEl.ontimeupdate = null;
    videoEl.onerror = () => potgLog(`saved highlight could not be played (${url})`);
    videoEl.onended = onPotgPlaybackEnded;
    delete videoEl.dataset.segmentFor;
    videoEl.muted = false;
    videoEl.volume = potgClipVolume;
    routeToSpeaker(videoEl);
    videoEl.src = url;
    videoEl.play().catch(() => {});
}

async function playPotgVideo(parts) {
    const token = ++potgPlayToken;
    const isCurrent = () => token === potgPlayToken;
    const players = potgPlayers();
    players.forEach(p => { p.onerror = null; });
    try {
        const finished = await playPieces(parts, players, {
            isCurrent,
            nextTick: step => requestAnimationFrame(step),
            onStart: (player, index) => {
                players.forEach(p => { p.style.display = p === player ? '' : 'none'; });
                if (index === 0 && potgAudioEl) potgAudioEl.play().catch(() => {});
            }
        });
        if (finished && isCurrent()) onPotgPlaybackEnded();
    } catch (e) {
        if (!isCurrent()) return;
        potgLog(`video playback failed (${e && e.message ? e.message : e}) - falling back to frames`);
        fallbackToFrames();
    }
}

function haltPotgPlayback() {
    potgPlayToken++;
    if (potgAnimHandle) cancelAnimationFrame(potgAnimHandle);
    if (potgAudioEl) { try { potgAudioEl.pause(); } catch (e) {} }
    potgPlayers().forEach(p => { try { p.pause(); } catch (e) {} });
}

function fadeInOverlay(el, display) {
    el.classList.remove('anim-out');
    el.style.display = display;
    void el.offsetWidth;
    el.classList.add('anim-in');
}

function fadeOutOverlay(el) {
    return new Promise(resolve => {
        if (el.style.display === 'none' || !el.style.display) { resolve(); return; }
        el.classList.remove('anim-in');
        el.classList.add('anim-out');
        let done = false;
        const finish = () => {
            if (done) return;
            done = true;
            el.style.display = 'none';
            el.classList.remove('anim-out');
            resolve();
        };
        el.addEventListener('transitionend', finish, {once: true});
        setTimeout(finish, 520);
    });
}

let potgControlsTimer = null;
let potgPlaybackEnded = false;

function schedulePotgControlsHide() {
    clearTimeout(potgControlsTimer);
    if (potgPlaybackEnded || potgSaving) return;
    potgControlsTimer = setTimeout(() => {
        document.getElementById('potg-viewer').classList.add('controls-hidden');
    }, 2600);
}

function showPotgControls() {
    document.getElementById('potg-viewer').classList.remove('controls-hidden');
    schedulePotgControlsHide();
}

function onPotgPlaybackEnded() {
    potgPlaybackEnded = true;
    clearTimeout(potgControlsTimer);
    document.getElementById('potg-viewer').classList.remove('controls-hidden');
}

function exportClipBitrate() {
    return Math.max(12000000, Math.round((potgPlan ? potgPlan.bitrate : 10000000) * 1.25));
}

function historyClipBitrate(fps) {
    return fps >= 60 ? 10000000 : 6000000;
}

const HISTORY_CLIP_MAX_WIDTH = 1920;

function buildSaveRecorder(stream, bitrate) {
    const live = potgVideo.workingMime;
    const recorded = live ? [live.includes('codecs=') ? `${live},opus` : live] : [];
    const candidates = [...recorded, 'video/webm;codecs=vp8,opus', 'video/webm;codecs=vp9,opus', 'video/webm', ''];
    for (const m of candidates) {
        try {
            const opts = {videoBitsPerSecond: bitrate};
            if (m) opts.mimeType = m;
            return new MediaRecorder(stream, opts);
        } catch (e) {}
    }
    return null;
}

async function recordHighlight(h, useVideo, background = false) {
    const canvas = background ? document.createElement('canvas') : document.getElementById('potg-canvas');
    const players = background ? [document.createElement('video'), document.createElement('video')] : potgPlayers();
    const ctx2d = canvas.getContext('2d');
    const fps = useVideo && potgPlan ? potgPlan.frameRate : 30;
    const nextFrame = background
        ? step => setTimeout(step, 1000 / fps)
        : step => { potgAnimHandle = requestAnimationFrame(step); };
    let warmUpFrame = null;
    players.forEach(p => { p.muted = true; });
    if (!background) {
        players.forEach(p => { p.style.display = 'none'; });
        canvas.style.display = '';
    }

    let drawLoop;
    if (useVideo) {
        const parts = h.video.parts;
        await cuePart(players[0], parts[0]);
        const sourceW = players[0].videoWidth || 1280;
        const sourceH = players[0].videoHeight || 720;
        const scale = background ? Math.min(1, HISTORY_CLIP_MAX_WIDTH / sourceW) : 1;
        canvas.width = Math.round(sourceW * scale);
        canvas.height = Math.round(sourceH * scale);
        const drawFrom = player => { try { ctx2d.drawImage(player, 0, 0, canvas.width, canvas.height); } catch (e) {} };
        warmUpFrame = () => drawFrom(players[0]);
        drawLoop = () => playPieces(parts, players, {nextTick: onNextVideoFrame(1000 / fps), onFrame: drawFrom});
    } else {
        const frames = h.frames;
        canvas.width = frames[0].im.naturalWidth;
        canvas.height = frames[0].im.naturalHeight;
        drawLoop = () => new Promise(res => {
            const t0 = performance.now();
            const step = () => {
                const elapsed = performance.now() - t0;
                let frame = frames[0];
                for (const f of frames) {
                    if (f.t <= elapsed) frame = f; else break;
                }
                ctx2d.drawImage(frame.im, 0, 0);
                if (elapsed < frames[frames.length - 1].t + 400) nextFrame(step);
                else res();
            };
            step();
        });
    }

    const stream = canvas.captureStream(fps);
    let voiceSrc = null;
    if (h.audio && audioCtx) {
        try {
            const dest = audioCtx.createMediaStreamDestination();
            const buf = await audioCtx.decodeAudioData(await h.audio.arrayBuffer());
            voiceSrc = audioCtx.createBufferSource();
            voiceSrc.buffer = buf;

            const vol = audioCtx.createGain();
            vol.gain.value = background ? 1 : potgClipVolume;
            voiceSrc.connect(vol);
            vol.connect(dest);
            if (!background) vol.connect(audioCtx.destination);
            stream.addTrack(dest.stream.getAudioTracks()[0]);
        } catch (e) {
            voiceSrc = null;
        }
    }

    const bitrate = background ? historyClipBitrate(fps) : exportClipBitrate();
    const rec = buildSaveRecorder(stream, bitrate);
    if (!rec) throw new Error('no recorder available');
    const parts = [];
    rec.ondataavailable = ev => { if (ev.data && ev.data.size > 0) parts.push(ev.data); };
    const stopped = new Promise(res => { rec.onstop = res; });
    rec.start(250);
    if (warmUpFrame) {
        const hold = setInterval(warmUpFrame, 1000 / fps);
        warmUpFrame();
        await new Promise(res => setTimeout(res, 300));
        clearInterval(hold);
    }
    if (voiceSrc) { try { voiceSrc.start(); } catch (e) {} }

    let err = null;
    try {
        await drawLoop();
    } catch (e) {
        err = e;
    }
    if (voiceSrc) { try { voiceSrc.stop(); } catch (e) {} }
    try { rec.stop(); } catch (e) {}
    await stopped;
    stream.getTracks().forEach(t => t.stop());
    if (background) {
        players.forEach(p => {
            if (!p.src) return;
            try { URL.revokeObjectURL(p.src); } catch (e) {}
            p.removeAttribute('src');
        });
    }
    if (err) throw err;
    return new Blob(parts, {type: rec.mimeType || 'video/webm'});
}

async function savePotgHighlight() {
    if (potgSaving || !potgCurrent) return;
    const h = potgCurrent;
    const btn = document.getElementById('potg-save');
    const status = document.getElementById('potg-save-status');
    potgSaving = true;
    btn.disabled = true;
    status.innerText = '● Exporting highlight…';
    haltPotgPlayback();
    onPotgPlaybackEnded();

    try {
        await loadFramesFor(h);
        let blob;
        try {
            blob = await recordHighlight(h, !!h.video);
        } catch (e) {
            if (h.video && h.frames && h.frames.length > 0) {
                potgLog('save via video path failed - retrying with backend frames');
                blob = await recordHighlight(h, false);
            } else {
                throw e;
            }
        }
        if (!blob || blob.size < 1024) throw new Error('empty recording');

        status.innerText = 'Writing to disk…';
        const query = new URLSearchParams({
            name: `${h.headline} ${h.gameClock.replace(':', '.')}`,
            hid: h.id, headline: h.headline, clock: h.gameClock
        });
        const resp = await fetch(`/potg/save?${query}`, {method: 'POST', body: blob});
        const j = await resp.json().catch(() => ({}));
        if (!resp.ok || !j.path) throw new Error(j.error || `HTTP ${resp.status}`);
        status.innerText = `Exported to ${j.path}`;
        potgLog(`highlight saved to ${j.path}`);
    } catch (e) {
        status.innerText = `Export failed (${e && e.message ? e.message : e})`;
        potgLog(`highlight save failed (${e && e.message ? e.message : e})`);
    } finally {
        potgSaving = false;
        btn.disabled = false;
    }
}

let potgAutoSaveChain = Promise.resolve();

function autoSaveHighlights(matchId, highlights) {
    potgAutoSaveChain = potgAutoSaveChain.then(async () => {
        for (const h of highlights) {
            try {
                await loadFramesFor(h);
                if (!h.video && h.frames.length === 0) continue;
                let blob;
                try {
                    blob = await recordHighlight(h, !!h.video, true);
                } catch (e) {
                    if (!h.video || h.frames.length === 0) throw e;
                    blob = await recordHighlight(h, false, true);
                }
                if (!blob || blob.size < 1024) throw new Error('empty recording');

                const query = new URLSearchParams({hid: h.id, headline: h.headline, clock: h.gameClock, score: String(h.score || 0)});
                const resp = await fetch(`/matches/${encodeURIComponent(matchId)}/highlights?${query}`, {method: 'POST', body: blob});
                if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
                potgLog(`kept ${h.headline} in match history (${Math.round(blob.size / 1024)}KB)`);
            } catch (e) {
                potgLog(`could not keep ${h.headline} in match history (${e && e.message ? e.message : e})`);
            }
        }
    });
}

document.getElementById('potg-replay').addEventListener('click', () => { if (!potgSaving) playPotg(); });
document.getElementById('potg-save').addEventListener('click', savePotgHighlight);
document.getElementById('potg-viewer').addEventListener('mousemove', showPotgControls);
document.getElementById('potg-more').addEventListener('click', () => {
    const strip = document.getElementById('potg-highlights');
    const hidden = strip.style.display === 'none' || strip.style.display === '';
    strip.style.display = hidden ? 'flex' : 'none';
    document.getElementById('potg-more').innerText = hidden ? 'Hide Highlights' : 'Show More Highlights';
});
document.getElementById('potg-continue').addEventListener('click', () => { if (!potgSaving) showEndgameScreen(potgHistoryMatch); });
document.getElementById('potg-intro-btn').addEventListener('click', enterHighlightsFromIntro);

const potgVolSlider = document.getElementById('potg-vol');
function refreshPotgVolumeUI() {
    const pct = Math.round(potgClipVolume * 100);
    potgVolSlider.value = pct;
    potgVolSlider.style.setProperty('--vol', pct + '%');
    document.getElementById('potg-vol-icon').textContent = pct === 0 ? '🔇' : (pct < 50 ? '🔉' : '🔊');
}
potgVolSlider.addEventListener('input', () => {
    potgClipVolume = potgVolSlider.value / 100;
    if (potgAudioEl) potgAudioEl.volume = potgClipVolume;
    if (potgReplayData && potgReplayData.file) document.getElementById('potg-video').volume = potgClipVolume;
    try { localStorage.setItem('potgClipVolume', String(potgClipVolume)); } catch (e) {}
    refreshPotgVolumeUI();
});
refreshPotgVolumeUI();
