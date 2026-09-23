function closeApplicationTab() {
    try { if (room) room.disconnect(); } catch (e) {}

    document.body.innerHTML = `
        <div style="display:flex; min-height:100vh; align-items:center; justify-content:center; padding:24px;">
            <div style="background:linear-gradient(180deg,#0c1626,#060d17); border:1px solid rgba(200,170,110,0.35); border-radius:3px; box-shadow:0 18px 50px -12px rgba(0,0,0,0.7); padding:38px; max-width:420px; width:100%; text-align:center;">
                <div style="width:54px; height:54px; margin:0 auto 18px; display:flex; align-items:center; justify-content:center; font-size:24px; border-radius:3px; color:#f0e6d2; background:linear-gradient(135deg,#e8455a,#8e1f2c); box-shadow:0 8px 22px -6px rgba(232,69,90,0.45);">⏻</div>
                <h2 style="margin:0 0 8px; font-family:Cinzel,serif; letter-spacing:0.08em; font-size:18px; color:#f0e6d2;">Application Closed</h2>
                <p style="margin:0 0 22px; color:#a8a290; font-size:14px; line-height:1.5;">LeagueProximityChat has shut down and your microphone is disconnected.</p>
                <p style="margin:0; font-size:12.5px; color:#6b7585; line-height:1.55;">
                    Your browser won't let this tab close itself -<br>
                    <strong style="color:#a8a290;">you can safely close this window.</strong>
                </p>
            </div>
        </div>
    `;

    setTimeout(() => {
        window.open('', '_self', '');
        window.close();
    }, 100);
}

function resetToIdleState() {
    if (window.voiceInterval) { clearInterval(window.voiceInterval); window.voiceInterval = null; }
    stopPotgRecording();

    try { if (room) room.disconnect(); } catch (e) {}

    Object.keys(remoteAudioNodes).forEach(cleanupRemoteAudio);
    Object.keys(remotePositions).forEach(identity => delete remotePositions[identity]);
    Object.keys(enemyLastSeen).forEach(identity => delete enemyLastSeen[identity]);
    deadListen = null;
    visibleEnemyIcons = [];
    localPosition.x = 0;
    localPosition.y = 0;
    localPosition.isDead = false;

    isWaitingForMatch = false;
    connected = false;
    micMuted = false;
    isDeafened = false;
    micMutedBeforeDeafen = false;
    hasReceivedPosition = false;
    positionGateActive = false;
    krispProcessor = null;
    locallyMuted.clear();
    setGamePaused(false);
    if (masterGain && audioCtx) masterGain.gain.setTargetAtTime(1, audioCtx.currentTime, 0.05);

    const startBtn = document.getElementById('start-btn');
    startBtn.style.display = 'block';
    startBtn.classList.remove('is-waiting');
    startBtn.innerText = 'Connect to Audio';
    showVoiceControls(false);
    updateVoiceButtonsUI();
    setKrispStatus(krispEnabled ? '(applies on connect)' : '(off)');

    const lkStatus = document.getElementById('lk-status');
    lkStatus.className = 'status error';
    lkStatus.innerText = 'Audio Server: Waiting for token...';

    localUserIdentity = null;
    matchLeaderIdentity = null;
    document.getElementById('debug-btn').style.display = 'none';
    document.getElementById('roster-container').innerHTML =
        '<div class="player-item empty-row">Waiting for match to begin…</div>';
    document.getElementById('roster-count').innerText = '-';

    const coordEl = document.getElementById('local-coords');
    if (coordEl) {
        coordEl.innerText = "Waiting for data...";
        coordEl.style.color = "#6b7585";
    }
}
