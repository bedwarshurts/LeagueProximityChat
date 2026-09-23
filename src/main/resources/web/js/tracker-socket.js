function reportPlayerStateToJava(type, participant) {
    if (trackerSocket && trackerSocket.readyState === WebSocket.OPEN) {
        trackerSocket.send(JSON.stringify({
            type: type,
            identity: participant.identity,
            name: participant.name || "Unknown"
        }));
    }
}

const trackerMessageHandlers = {
    REPLACED: () => { isReplaced = true; },
    SHUTDOWN: () => closeApplicationTab(),
    GAME_ENDED: () => {
        maybeShowPlayOfGame();
        resetToIdleState();
    },
    WARNING: data => showWarning(data),
    GAME_PAUSED: data => setGamePaused(Boolean(data.paused)),
    TOGGLE_MUTE: () => document.getElementById('mute-btn').click(),
    TOGGLE_DEAFEN: () => document.getElementById('deafen-btn').click(),
    POTG_MARKS: data => handlePotgMarks(data.marks || []),
    MATCH_ROSTER: data => buildRosterUI(data.players, data.localIdentity, data.roomLeader, data.debug),
    CONNECT_LIVEKIT: data => connectToLiveKit(data.token),
    PLAYER_BANNED: data => setPlayerBanned(data.identity, true),
    PLAYER_UNBANNED: data => setPlayerBanned(data.identity, false)
};

function handleTrackerMessage(event) {
    if (event.data === "REQUEST_JOIN" || event.data === "CANCEL_JOIN") return;
    const data = JSON.parse(event.data);

    if (Object.hasOwn(trackerMessageHandlers, data.type)) {
        trackerMessageHandlers[data.type](data);
    } else {
        handlePositionUpdate(data);
    }
}

function handlePositionUpdate(data) {
    if (data.x === undefined || data.y === undefined) return;

    if (data.detected === false) {
        const coordEl = document.getElementById('local-coords');
        if (coordEl) {
            coordEl.innerText = 'Running initial detection';
            coordEl.style.color = '';
        }
        return;
    }

    const wasDead = localPosition.isDead;
    localPosition.x = Number(data.x);
    localPosition.y = Number(data.y);
    localPosition.isDead = Boolean(data.isDead);

    if (localPosition.isDead && data.listenX !== undefined && data.listenY !== undefined) {
        deadListen = {x: Number(data.listenX), y: Number(data.listenY)};
        visibleEnemyIcons = (data.visibleEnemies || []).map(p => ({x: Number(p[0]), y: Number(p[1])}));
        refreshEnemyVisibility();
    } else {
        deadListen = null;
        visibleEnemyIcons = [];
    }
    if (localPosition.isDead || wasDead) {
        Object.keys(remoteAudioNodes).forEach(updateRemoteAudio);
    }

    const coordEl = document.getElementById('local-coords');
    if (coordEl) {
        coordEl.innerText = `X: ${localPosition.x.toFixed(1)}, Y: ${localPosition.y.toFixed(1)}`;
        if (localPosition.isDead) {
            coordEl.innerText += " (DEAD)";
            coordEl.style.color = "#e8455a";
        } else {
            coordEl.style.color = "#0ac8b9";
        }
    }

    updateListenerPosition();

    if (!hasReceivedPosition) {
        hasReceivedPosition = true;
        if (positionGateActive) {
            positionGateActive = false;

            applyMicState();
            applyOutputState();
            updatePositionGateStatus();
        }
    }

    if (room && room.state === LivekitClient.ConnectionState.Connected) {
        const payload = new TextEncoder().encode(JSON.stringify({
            x: localPosition.x,
            y: localPosition.y,
            isDead: localPosition.isDead
        }));
        room.localParticipant.publishData(payload, LivekitClient.DataPacket_Kind.LOSSY);
    }
}

function connectToLocalJavaTracker() {
    if (trackerSocket && trackerSocket.readyState === WebSocket.OPEN) return;

    isReplaced = false;

    trackerSocket = new WebSocket('ws://localhost:8887');
    trackerSocket.onopen = () => {
        document.getElementById('ws-status').className = 'status success';
        document.getElementById('ws-status').innerText = 'Position Tracker: Connected';
    };

    trackerSocket.onmessage = (event) => {
        try {
            handleTrackerMessage(event);
        } catch (e) {
            console.warn('[Tracker] Could not handle a message from the app:', e);
        }
    };

    trackerSocket.onclose = () => {
        trackerSocket = null;

        if (isReplaced) {
            document.getElementById('ws-status').className = 'status error';
            document.getElementById('ws-status').innerText = 'Connection yielded to a newer tab.';
            return;
        }

        document.getElementById('ws-status').className = 'status error';
        document.getElementById('ws-status').innerText = 'Position Tracker: Disconnected';

        document.getElementById('local-coords').innerText = "Waiting for data...";
        document.getElementById('local-coords').style.color = "#6b7585";

        setTimeout(connectToLocalJavaTracker, 2000);
    };
}
