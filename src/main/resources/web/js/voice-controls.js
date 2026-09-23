document.getElementById('start-btn').addEventListener('click', async () => {
    if (!livekitConfigured) {
        showSetupScreen();
        return;
    }
    if (connected || !trackerSocket || trackerSocket.readyState !== WebSocket.OPEN) return;
    if (!audioCtx) {
        audioCtx = new (window.AudioContext || window.webkitAudioContext)();
        routeToSpeaker(audioCtx);
    }
    try { await audioCtx.resume(); } catch (e) {}

    const btn = document.getElementById('start-btn');
    if (!isWaitingForMatch) {
        isWaitingForMatch = true;
        btn.classList.add('is-waiting');
        btn.innerText = 'Waiting for match… (click to cancel)';
        trackerSocket.send("REQUEST_JOIN");
        startPotgVideoCapture();
    } else {
        isWaitingForMatch = false;
        btn.classList.remove('is-waiting');
        btn.innerText = 'Connect to Audio';
        trackerSocket.send("CANCEL_JOIN");
    }
});

function showVoiceControls(visible) {
    document.getElementById('voice-controls').style.display = visible ? 'flex' : 'none';
}

function updateVoiceButtonsUI() {
    const muteBtn = document.getElementById('mute-btn');
    muteBtn.innerText = micMuted ? '🎤 Unmute' : '🎤 Mute';
    muteBtn.classList.toggle('active', micMuted);

    const deafenBtn = document.getElementById('deafen-btn');
    deafenBtn.innerText = isDeafened ? '🎧 Undeafen' : '🎧 Deafen';
    deafenBtn.classList.toggle('active', isDeafened);
}

async function applyMicState() {
    const enabled = !micMuted && !positionGateActive;
    try { if (room) await room.localParticipant.setMicrophoneEnabled(enabled); } catch (e) {}
}

function applyOutputState() {
    if (masterGain && audioCtx) {
        const silenced = isDeafened || positionGateActive;
        masterGain.gain.setTargetAtTime(silenced ? 0 : 1, audioCtx.currentTime, 0.05);
    }
}

async function setMicMuted(muted) {
    micMuted = muted;
    await applyMicState();
    updateVoiceButtonsUI();
}

async function setDeafened(deafened) {
    isDeafened = deafened;
    applyOutputState();

    if (deafened) {
        micMutedBeforeDeafen = micMuted;
        await setMicMuted(true);
    } else {
        await setMicMuted(micMutedBeforeDeafen);
    }
}

function updatePositionGateStatus() {
    if (!connected) return;
    const lk = document.getElementById('lk-status');
    lk.className = positionGateActive ? 'status loading' : 'status success';
    lk.innerText = positionGateActive
        ? 'Audio Server: Connected - Position not yet detected'
        : 'Audio Server: Connected';
}

document.getElementById('mute-btn').addEventListener('click', async () => {
    if (!connected || !room) return;
    if (isDeafened) {
        micMutedBeforeDeafen = false;
        await setDeafened(false);
        return;
    }
    await setMicMuted(!micMuted);
});

document.getElementById('deafen-btn').addEventListener('click', async () => {
    if (!connected || !room) return;
    await setDeafened(!isDeafened);
});

function toggleLocalMute(identity) {
    if (locallyMuted.has(identity)) {
        locallyMuted.delete(identity);
    } else {
        locallyMuted.add(identity);
    }

    const btn = document.getElementById(`localmute-${identity}`);
    if (btn) {
        const muted = locallyMuted.has(identity);
        btn.innerText = muted ? '🔇' : '🔊';
        btn.classList.toggle('muted', muted);
        btn.title = muted ? 'Unmute (for you only)' : 'Mute (for you only)';
    }

    const nodeData = remoteAudioNodes[identity];
    if (nodeData && audioCtx) {
        if (locallyMuted.has(identity)) {
            nodeData.gain.gain.setTargetAtTime(0, audioCtx.currentTime, 0.05);
        } else {
            updateRemoteAudio(identity);
        }
    }
}

function setKrispStatus(text) {
    const el = document.getElementById('krisp-status');
    if (el) el.innerText = text;
}

async function applyKrispState() {
    localStorage.setItem('krispEnabled', krispEnabled);

    if (!connected || !room) {
        setKrispStatus(krispEnabled ? '(applies on connect)' : '(off)');
        return;
    }

    try {
        const audioPublications = Array.from(room.localParticipant.audioTrackPublications.values());
        const track = (audioPublications.length > 0) ? audioPublications[0].track : null;
        if (!track) {
            console.warn('[Krisp] No local audio track found - noise cancellation not applied.');
            setKrispStatus('(no mic track)');
            return;
        }

        if (krispEnabled) {
            if (krispProcessor && typeof krispProcessor.setEnabled === 'function') {
                await krispProcessor.setEnabled(true);
            } else if (!krispProcessor) {
                const {
                    KrispNoiseFilter,
                    isKrispNoiseFilterSupported
                } = await import('https://cdn.jsdelivr.net/npm/@livekit/krisp-noise-filter/+esm');
                if (!isKrispNoiseFilterSupported()) {
                    console.warn('[Krisp] Not supported in this browser - noise cancellation disabled.');
                    setKrispStatus('(unsupported)');
                    return;
                }
                krispProcessor = KrispNoiseFilter();
                await track.setProcessor(krispProcessor);
            }
            console.log('[Krisp] Noise cancellation active.');
            setKrispStatus('(active)');
        } else {
            if (krispProcessor) {
                if (typeof krispProcessor.setEnabled === 'function') {
                    await krispProcessor.setEnabled(false);
                } else {
                    await track.stopProcessor();
                    krispProcessor = null;
                }
            }
            console.log('[Krisp] Noise cancellation off.');
            setKrispStatus('(off)');
        }
    } catch (e) {
        console.error('[Krisp] Failed to update noise filter:', e);
        setKrispStatus('(error)');
    }
}

const krispToggle = document.getElementById('krisp-toggle');
krispToggle.checked = krispEnabled;
setKrispStatus(krispEnabled ? '(applies on connect)' : '(off)');
krispToggle.addEventListener('change', async () => {
    krispEnabled = krispToggle.checked;
    await applyKrispState();
});
