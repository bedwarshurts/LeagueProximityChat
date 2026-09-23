let potgReplayData = null;
let potgAnimHandle = null;
let potgAudioEl = null;
let potgHighlights = [];
let potgCurrent = null;
let potgSaving = false;

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

async function maybeShowPlayOfGame() {
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

        const best = potgHighlights[0];
        await loadFramesFor(best);
        if (!best.video && best.frames.length === 0) return;

        potgLog(`showing ${potgHighlights.length} highlight(s), best via `
            + `${best.video ? 'video (60fps)' : 'frame-flip fallback'} path${best.audio ? ' with voice' : ''}`);

        showPotgIntro();
    } catch (e) {}
}

function openHighlightViewer() {
    const best = potgHighlights[0];
    if (!best) return;
    buildHighlightsStrip();
    document.getElementById('potg-highlights').style.display = 'none';
    const moreBtn = document.getElementById('potg-more');
    moreBtn.style.display = potgHighlights.length > 1 ? '' : 'none';
    moreBtn.innerText = 'Show More Highlights';
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
    potgReplayData = {video: h.video, frames: h.frames, audio: h.audio};
    playPotg();
}

function playPotg() {
    if (!potgReplayData) return;
    const {video, frames, audio} = potgReplayData;
    if (!video && (!frames || frames.length === 0)) return;
    const canvas = document.getElementById('potg-canvas');
    const videoEl = document.getElementById('potg-video');

    potgPlaybackEnded = false;
    showPotgControls();

    if (potgAnimHandle) cancelAnimationFrame(potgAnimHandle);
    if (potgAudioEl) { try { potgAudioEl.pause(); } catch (e) {} }
    try { videoEl.pause(); } catch (e) {}
    potgAudioEl = audio ? new Audio(URL.createObjectURL(audio)) : null;
    if (potgAudioEl) {
        potgAudioEl.volume = potgClipVolume;
        routeToSpeaker(potgAudioEl);
    }

    if (video) {
        canvas.style.display = 'none';
        videoEl.style.display = '';
        playPotgVideo(videoEl, video);
        return;
    }

    videoEl.style.display = 'none';
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
    try { document.getElementById('potg-video').pause(); } catch (e) {}
    if (potgCurrent) potgCurrent.video = null;
    if (potgReplayData && potgReplayData.video
        && potgReplayData.frames && potgReplayData.frames.length > 0) {
        potgReplayData = {video: null, frames: potgReplayData.frames, audio: potgReplayData.audio};
        playPotg();
    }
}

async function preparePotgVideoSrc(videoEl, clip) {
    if (videoEl.dataset.clipUrlFor === String(clip.startMs)) return;
    if (videoEl.src) { try { URL.revokeObjectURL(videoEl.src); } catch (e) {} }
    videoEl.src = URL.createObjectURL(clip.blob);
    videoEl.dataset.clipUrlFor = String(clip.startMs);
    await new Promise(res => { videoEl.onloadedmetadata = res; setTimeout(res, 2000); });
    if (!isFinite(videoEl.duration)) {
        await new Promise(res => {
            videoEl.ondurationchange = () => { if (isFinite(videoEl.duration)) res(); };
            videoEl.currentTime = Number.MAX_SAFE_INTEGER;
            setTimeout(res, 2000);
        });
    }
}

async function playPotgVideo(videoEl, clip) {
    try {
        videoEl.onerror = () => {
            const err = videoEl.error;
            potgLog(`video playback error (code ${err ? err.code : '?'}) - falling back to frames`);
            fallbackToFrames();
        };

        await preparePotgVideoSrc(videoEl, clip);

        let seekTo = Math.max(0, (clip.startMs - clip.segStartMs) / 1000);
        const endOffset = Math.max(seekTo + 0.5, (clip.endMs - clip.segStartMs) / 1000);

        if (isFinite(videoEl.duration) && seekTo >= videoEl.duration - 0.3) seekTo = 0;

        videoEl.currentTime = seekTo;
        await new Promise(res => { videoEl.onseeked = res; setTimeout(res, 1500); });

        const from = seekTo;
        videoEl.ontimeupdate = () => {
            if (videoEl.currentTime >= endOffset && videoEl.currentTime > from + 0.2) {
                videoEl.pause();
                onPotgPlaybackEnded();
            }
        };
        videoEl.onended = onPotgPlaybackEnded;
        videoEl.play().catch(() => {});
        if (potgAudioEl) potgAudioEl.play().catch(() => {});

        const probeStart = videoEl.currentTime;
        setTimeout(() => {
            if (!potgReplayData || !potgReplayData.video) return;
            const advanced = videoEl.currentTime > probeStart + 0.2;
            if (!advanced || videoEl.readyState < 2) {
                potgLog('video not advancing - retrying from segment start');
                try { videoEl.currentTime = 0; videoEl.play().catch(() => {}); } catch (e) {}
                setTimeout(() => {
                    if (!potgReplayData || !potgReplayData.video) return;
                    if (videoEl.currentTime < 0.2 || videoEl.readyState < 2) {
                        potgLog('video still not playing - falling back to frames');
                        fallbackToFrames();
                    }
                }, 1500);
            }
        }, 1500);
    } catch (e) {
        fallbackToFrames();
    }
}

function haltPotgPlayback() {
    if (potgAnimHandle) cancelAnimationFrame(potgAnimHandle);
    if (potgAudioEl) { try { potgAudioEl.pause(); } catch (e) {} }
    try { document.getElementById('potg-video').pause(); } catch (e) {}
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

function buildSaveRecorder(stream) {
    const candidates = ['video/webm;codecs=vp9,opus', 'video/webm;codecs=vp8,opus', 'video/webm', ''];
    for (const m of candidates) {
        try {
            const opts = {videoBitsPerSecond: 8000000};
            if (m) opts.mimeType = m;
            return new MediaRecorder(stream, opts);
        } catch (e) {}
    }
    return null;
}

async function recordHighlight(h, useVideo) {
    const canvas = document.getElementById('potg-canvas');
    const videoEl = document.getElementById('potg-video');
    const ctx2d = canvas.getContext('2d');
    videoEl.style.display = 'none';
    canvas.style.display = '';

    let drawLoop;
    if (useVideo) {
        const clip = h.video;
        videoEl.ontimeupdate = null;
        videoEl.onerror = null;
        await preparePotgVideoSrc(videoEl, clip);
        let seekTo = Math.max(0, (clip.startMs - clip.segStartMs) / 1000);
        if (isFinite(videoEl.duration) && seekTo >= videoEl.duration - 0.3) seekTo = 0;
        const endOffset = Math.max(seekTo + 0.5, (clip.endMs - clip.segStartMs) / 1000);
        videoEl.currentTime = seekTo;
        await new Promise(res => { videoEl.onseeked = res; setTimeout(res, 1500); });
        canvas.width = videoEl.videoWidth || 1280;
        canvas.height = videoEl.videoHeight || 720;
        drawLoop = () => new Promise((res, rej) => {
            let lastT = -1;
            let stalledSince = performance.now();
            const step = () => {
                try { ctx2d.drawImage(videoEl, 0, 0, canvas.width, canvas.height); } catch (e) {}
                if (videoEl.currentTime !== lastT) { lastT = videoEl.currentTime; stalledSince = performance.now(); }
                if (videoEl.currentTime >= endOffset || videoEl.ended) { videoEl.pause(); res(); return; }
                if (performance.now() - stalledSince > 4000) { videoEl.pause(); rej(new Error('video stalled')); return; }
                potgAnimHandle = requestAnimationFrame(step);
            };
            videoEl.play().catch(() => {});
            step();
        });
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
                if (elapsed < frames[frames.length - 1].t + 400) potgAnimHandle = requestAnimationFrame(step);
                else res();
            };
            step();
        });
    }

    const stream = canvas.captureStream(30);
    let voiceSrc = null;
    if (h.audio && audioCtx) {
        try {
            const dest = audioCtx.createMediaStreamDestination();
            const buf = await audioCtx.decodeAudioData(await h.audio.arrayBuffer());
            voiceSrc = audioCtx.createBufferSource();
            voiceSrc.buffer = buf;

            const vol = audioCtx.createGain();
            vol.gain.value = potgClipVolume;
            voiceSrc.connect(vol);
            vol.connect(dest);
            vol.connect(audioCtx.destination);
            stream.addTrack(dest.stream.getAudioTracks()[0]);
        } catch (e) {
            voiceSrc = null;
        }
    }

    const rec = buildSaveRecorder(stream);
    if (!rec) throw new Error('no recorder available');
    const parts = [];
    rec.ondataavailable = ev => { if (ev.data && ev.data.size > 0) parts.push(ev.data); };
    const stopped = new Promise(res => { rec.onstop = res; });
    rec.start(250);
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
    status.innerText = '● Recording highlight…';
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
        const name = `${h.headline} ${h.gameClock.replace(':', '.')}`;
        const resp = await fetch(`/potg/save?name=${encodeURIComponent(name)}`, {method: 'POST', body: blob});
        const j = await resp.json().catch(() => ({}));
        if (!resp.ok || !j.path) throw new Error(j.error || `HTTP ${resp.status}`);
        status.innerText = `Saved to ${j.path}`;
        potgLog(`highlight saved to ${j.path}`);
    } catch (e) {
        status.innerText = `Save failed (${e && e.message ? e.message : e})`;
        potgLog(`highlight save failed (${e && e.message ? e.message : e})`);
    } finally {
        potgSaving = false;
        btn.disabled = false;
    }
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
document.getElementById('potg-continue').addEventListener('click', () => { if (!potgSaving) showEndgameScreen(); });
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
    try { localStorage.setItem('potgClipVolume', String(potgClipVolume)); } catch (e) {}
    refreshPotgVolumeUI();
});
refreshPotgVolumeUI();
