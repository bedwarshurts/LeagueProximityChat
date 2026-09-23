function getMasterLimiter() {
    if (!audioCtx) return null;
    if (!masterLimiter) {
        masterLimiter = audioCtx.createDynamicsCompressor();
        masterLimiter.threshold.value = -6;
        masterLimiter.knee.value = 0;
        masterLimiter.ratio.value = 20;
        masterLimiter.attack.value = 0.003;
        masterLimiter.release.value = 0.25;
        masterGain = audioCtx.createGain();
        masterGain.gain.value = (isDeafened || positionGateActive) ? 0 : 1;
        masterLimiter.connect(masterGain);
        masterGain.connect(audioCtx.destination);
    }
    return masterLimiter;
}

function listeningPoint() {
    return (localPosition.isDead && deadListen) ? deadListen : localPosition;
}

function isEnemy(identity) {
    return rosterLocalTeam !== null && rosterTeams[identity] !== rosterLocalTeam;
}

function refreshEnemyVisibility() {
    const now = performance.now();
    Object.keys(remotePositions).forEach(identity => {
        if (!isEnemy(identity)) return;
        const pos = remotePositions[identity];
        if (visibleEnemyIcons.some(icon => Math.hypot(icon.x - pos.x, icon.y - pos.y) <= DEAD_VISION_MATCH_DIST)) {
            enemyLastSeen[identity] = now;
        }
    });
}

function deadVisionAllows(identity) {
    if (!localPosition.isDead || !deadListen || !isEnemy(identity)) return true;
    const seen = enemyLastSeen[identity];
    return seen !== undefined && performance.now() - seen <= DEAD_VISION_GRACE_MS;
}

function updateListenerPosition() {
    if (!audioCtx) return;
    const now = audioCtx.currentTime;
    const listener = listeningPoint();
    audioCtx.listener.positionX.setTargetAtTime(listener.x, now, 0.1);
    audioCtx.listener.positionY.setTargetAtTime(0, now, 0.1);
    audioCtx.listener.positionZ.setTargetAtTime(listener.y, now, 0.1);
}

function updateRemoteAudio(identity) {
    if (!audioCtx) return;
    const nodeData = remoteAudioNodes[identity];
    if (!nodeData) return;

    const maxDist = 11;
    const fullDist = 3.0;
    const maxVolume = 2.0;
    const falloffExp = 0.6;

    if (gamePaused) {
        const t0 = audioCtx.currentTime;
        const listener = listeningPoint();
        nodeData.panner.positionX.setTargetAtTime(listener.x, t0, 0.1);
        nodeData.panner.positionY.setTargetAtTime(0, t0, 0.1);
        nodeData.panner.positionZ.setTargetAtTime(listener.y, t0, 0.1);
        const pausedVolume = (locallyMuted.has(identity) || !remotePositions[identity]) ? 0 : maxVolume * (playerVolumes[identity] ?? 1);
        nodeData.gain.gain.setTargetAtTime(pausedVolume, t0, 0.1);
        return;
    }

    const remotePos = remotePositions[identity];
    if (!remotePos) {
        nodeData.gain.gain.setTargetAtTime(0, audioCtx.currentTime, 0.1);
        return;
    }

    const listener = listeningPoint();
    const distance = Math.hypot(remotePos.x - listener.x, remotePos.y - listener.y);

    let volume;
    if (distance >= maxDist) {
        volume = 0;
    } else if (distance <= fullDist) {
        volume = maxVolume;
    } else {
        const t = (distance - fullDist) / (maxDist - fullDist);
        volume = maxVolume * Math.pow(1 - t, falloffExp);
    }
    volume *= playerVolumes[identity] ?? 1;
    if (remotePos.isDead) volume = 0;
    if (locallyMuted.has(identity)) volume = 0;
    if (!deadVisionAllows(identity)) volume = 0;

    const now = audioCtx.currentTime;
    nodeData.panner.positionX.setTargetAtTime(remotePos.x, now, 0.1);
    nodeData.panner.positionY.setTargetAtTime(0, now, 0.1);
    nodeData.panner.positionZ.setTargetAtTime(remotePos.y, now, 0.1);
    nodeData.gain.gain.setTargetAtTime(volume, now, 0.1);

    const distSpan = document.getElementById(`dist-${identity}`);
    if (distSpan) {
        if (remotePos.isDead) {
            distSpan.innerText = "DEAD";
            distSpan.style.color = "#e8455a";
        } else {
            distSpan.innerText = `${distance.toFixed(1)}m`;
            distSpan.style.color = (distance <= maxDist) ? "#0ac8b9" : "#6b7585";
        }
    }
}

function setGamePaused(paused) {
    gamePaused = paused;
    document.getElementById('pause-status').style.display = paused ? '' : 'none';
    Object.keys(remoteAudioNodes).forEach(updateRemoteAudio);
}

function cleanupRemoteAudio(identity) {
    const nodeData = remoteAudioNodes[identity];
    if (!nodeData) return;
    try {
        nodeData.source.disconnect();
        nodeData.panner.disconnect();
        nodeData.gain.disconnect();
        if (nodeData.hiddenElement) nodeData.hiddenElement.remove();
    } catch (e) {}
    delete remoteAudioNodes[identity];
    delete remotePositions[identity];

    const distSpan = document.getElementById(`dist-${identity}`);
    if (distSpan) distSpan.innerText = "0.0m";
}

async function ensurePlaybackUnlocked() {
    try {
        if (room && typeof room.canPlaybackAudio === 'boolean' && !room.canPlaybackAudio) await room.startAudio();
    } catch (e) {}
}
