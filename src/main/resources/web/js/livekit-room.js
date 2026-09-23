function initializeFreshRoom(micId) {
    if (room) {
        room.removeAllListeners();
        try { room.disconnect(); } catch (e) {}
    }

    room = new LivekitClient.Room({
        adaptiveStream: true,
        dynacast: true,
        audioCaptureDefaults: {
            echoCancellation: false,
            noiseSuppression: false,
            voiceIsolation: false,
            autoGainControl: false,
            ...(micId ? {deviceId: micId} : {})
        }
    });

    room.on(LivekitClient.RoomEvent.AudioPlaybackStatusChanged, async () => {
        await ensurePlaybackUnlocked();
    });

    room.on(LivekitClient.RoomEvent.ActiveSpeakersChanged, (speakers) => {
        const speaking = new Set(speakers.map(p => p.identity));
        document.querySelectorAll('.player-item[id^="roster-"]').forEach(row => {
            row.classList.toggle('speaking', speaking.has(row.id.slice('roster-'.length)));
        });
    });

    room.on(LivekitClient.RoomEvent.Disconnected, () => {
        if (window.voiceInterval) { clearInterval(window.voiceInterval); window.voiceInterval = null; }

        document.querySelectorAll('.player-item.speaking').forEach(row => row.classList.remove('speaking'));
        stopPotgRecording();

        document.getElementById('lk-status').className = 'status error';
        document.getElementById('lk-status').innerText = 'Audio Server: Disconnected - ready to retry.';

        const sb = document.getElementById('start-btn');
        sb.style.display = 'block';
        sb.classList.remove('is-waiting');
        sb.innerText = 'Connect to Audio';
        showVoiceControls(false);

        connected = false;
        isWaitingForMatch = false;
        krispProcessor = null;
        setKrispStatus(krispEnabled ? '(applies on connect)' : '(off)');

        if (localUserIdentity) {
            updatePlayerStatus(localUserIdentity, "Not Connected", "status-offline");
        }

        if (trackerSocket && trackerSocket.readyState === WebSocket.OPEN) {
            trackerSocket.send("CANCEL_JOIN");
        }
    });

    room.on(LivekitClient.RoomEvent.ParticipantConnected, (participant) => {
        reportPlayerStateToJava("PLAYER_JOINED", participant);
        updatePlayerStatus(participant.identity, "Connected", "status-connected");
    });

    room.on(LivekitClient.RoomEvent.ParticipantDisconnected, (participant) => {
        cleanupRemoteAudio(participant.identity);
        reportPlayerStateToJava("PLAYER_LEFT", participant);

        const statusSpan = document.getElementById(`status-${participant.identity}`);
        if (statusSpan && statusSpan.innerText !== "Banned") {
            updatePlayerStatus(participant.identity, "Not Connected", "status-offline");
        }
    });

    room.on(LivekitClient.RoomEvent.TrackSubscribed, async (track, publication, participant) => {
        if (track.kind !== 'audio') return;
        const hiddenElement = track.attach();
        hiddenElement.volume = 0;
        document.getElementById('hidden-audio-container').appendChild(hiddenElement);
        const mediaStream = new MediaStream([track.mediaStreamTrack]);
        const source = audioCtx.createMediaStreamSource(mediaStream);
        const panner = audioCtx.createPanner();
        const gain = audioCtx.createGain();
        panner.panningModel = 'HRTF';
        panner.distanceModel = 'linear';
        panner.refDistance = 100;
        panner.maxDistance = 100000;
        panner.rolloffFactor = 0;
        panner.coneInnerAngle = 360;
        source.connect(panner);
        panner.connect(gain);
        gain.connect(getMasterLimiter() || audioCtx.destination);
        remoteAudioNodes[participant.identity] = {source, panner, gain, hiddenElement};
        await ensurePlaybackUnlocked();
        updateRemoteAudio(participant.identity);
    });

    room.on(LivekitClient.RoomEvent.TrackUnsubscribed, (track, publication, participant) => {
        cleanupRemoteAudio(participant.identity);
    });

    room.on(LivekitClient.RoomEvent.DataReceived, (payload, participant) => {
        const decoded = new TextDecoder().decode(payload);
        try {
            const data = JSON.parse(decoded);
            remotePositions[participant.identity] = {
                x: Number(data.x),
                y: Number(data.y),
                isDead: Boolean(data.isDead)
            };
            updateRemoteAudio(participant.identity);
        } catch (e) {}
    });

    if (window.voiceInterval) clearInterval(window.voiceInterval);

    let localAnalyser = null;
    let localAnalyserSource = null;
    let localAnalyserTrack = null;
    let localDataArray = null;

    window.voiceInterval = setInterval(() => {
        if (!room || !connected) return;

        if (room.localParticipant) {
            let isTalkingLocally = room.localParticipant.isSpeaking;

            if (!isTalkingLocally && audioCtx && !micMuted) {
                const pubs = Array.from(room.localParticipant.audioTrackPublications.values());
                if (pubs.length > 0 && pubs[0].track && pubs[0].track.mediaStreamTrack) {
                    try {
                        const micTrack = pubs[0].track.mediaStreamTrack;
                        if (!localAnalyser || localAnalyserTrack !== micTrack) {
                            try { if (localAnalyserSource) localAnalyserSource.disconnect(); } catch (e) {}
                            localAnalyserSource = audioCtx.createMediaStreamSource(new MediaStream([micTrack]));
                            localAnalyserTrack = micTrack;
                            localAnalyser = audioCtx.createAnalyser();
                            localAnalyser.fftSize = 256;
                            localAnalyserSource.connect(localAnalyser);
                            localDataArray = new Uint8Array(localAnalyser.frequencyBinCount);
                        }
                        localAnalyser.getByteFrequencyData(localDataArray);

                        let sum = 0;
                        for (let i = 0; i < localDataArray.length; i++) sum += localDataArray[i];
                        let avgVolume = sum / localDataArray.length;

                        if (avgVolume > 8) isTalkingLocally = true;
                    } catch (e) {}
                }
            }

            if (micMuted || isDeafened) isTalkingLocally = false;

            const localRow = document.getElementById(`roster-${room.localParticipant.identity}`);
            if (localRow) {
                if (isTalkingLocally) localRow.classList.add('speaking');
                else localRow.classList.remove('speaking');
            }
        }

        room.remoteParticipants.forEach((participant) => {
            const playerRow = document.getElementById(`roster-${participant.identity}`);
            if (playerRow) {
                let isAudible = false;

                if (participant.isSpeaking && !locallyMuted.has(participant.identity) && !isDeafened && gamePaused && remotePositions[participant.identity]) {
                    isAudible = true;
                } else if (participant.isSpeaking && !locallyMuted.has(participant.identity) && !isDeafened) {

                    const remotePos = remotePositions[participant.identity];
                    if (remotePos) {
                        const listener = listeningPoint();
                        const distance = Math.hypot(remotePos.x - listener.x, remotePos.y - listener.y);
                        const canListen = !localPosition.isDead || deadListen !== null;

                        if (distance < 12.2 && !remotePos.isDead && canListen && deadVisionAllows(participant.identity)) {
                            isAudible = true;
                        }
                    }
                }

                if (isAudible) {
                    playerRow.classList.add('speaking');
                } else {
                    playerRow.classList.remove('speaking');
                }
            }
        });
    }, 80);
}

async function connectToLiveKit(token) {
    try {
        const statusEl = document.getElementById('lk-status');
        statusEl.className = 'status loading';
        statusEl.innerText = 'Audio Server: Connecting…';

        hasReceivedPosition = false;
        positionGateActive = true;
        applyOutputState();

        const micId = await resolveMicDeviceId();
        initializeFreshRoom(micId);

        await room.connect(LIVEKIT_URL, token);
        connected = true;

        room.remoteParticipants.forEach((participant) => {
            reportPlayerStateToJava("PLAYER_JOINED", participant);
            updatePlayerStatus(participant.identity, "Connected", "status-connected");
        });

        reportPlayerStateToJava("PLAYER_JOINED", room.localParticipant);

        statusEl.className = 'status success';
        statusEl.innerText = 'Audio Server: Connected';
        document.getElementById('start-btn').style.display = 'none';

        micMuted = false;
        isDeafened = false;
        micMutedBeforeDeafen = false;
        applyOutputState();
        updateVoiceButtonsUI();
        showVoiceControls(true);

        if (localUserIdentity) updatePlayerStatus(localUserIdentity, "Connected", "status-connected");

        await ensurePlaybackUnlocked();
        try {
            await room.localParticipant.setMicrophoneEnabled(true);
        } catch (e) {
            if (!micId) throw e;
            console.warn('[Mic] Selected microphone could not be opened - using the system default.', e);
            await room.localParticipant.setMicrophoneEnabled(true, {deviceId: 'default'});
        }

        await applyKrispState();
        startPotgRecording();

        if (positionGateActive) {
            updatePositionGateStatus();
            await applyMicState();
            applyOutputState();
            if (!positionGateActive) {
                await applyMicState();
                applyOutputState();
            }
        }
        return true;

    } catch (error) {
        document.getElementById('lk-status').className = 'status error';
        document.getElementById('lk-status').innerText = 'Audio Server: Connection failed!';

        const sb = document.getElementById('start-btn');
        sb.style.display = 'block';
        sb.classList.remove('is-waiting');
        sb.innerText = 'Connect to Audio';
        showVoiceControls(false);

        isWaitingForMatch = false;
        connected = false;

        if (localUserIdentity) updatePlayerStatus(localUserIdentity, "Not Connected", "status-offline");

        if (trackerSocket && trackerSocket.readyState === WebSocket.OPEN) {
            trackerSocket.send("CANCEL_JOIN");
        }

        return false;
    }
}
