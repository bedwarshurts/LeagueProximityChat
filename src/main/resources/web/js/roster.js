function buildRosterUI(players, localId, leaderId, debugEnabled) {
    localUserIdentity = localId;
    matchLeaderIdentity = leaderId;
    const container = document.getElementById('roster-container');
    container.innerHTML = '';

    const isAdmin = localUserIdentity &&
        ["only forward#star", "stab enchanters#star"].includes(localUserIdentity.toLowerCase());

    const debugMode = debugEnabled === true;
    const showDistances = isAdmin && debugMode;

    document.getElementById('debug-btn').style.display = debugMode ? '' : 'none';

    const localPlayer = players.find(p => p.identity === localId);
    const localTeam = localPlayer ? localPlayer.team : undefined;

    Object.keys(rosterTeams).forEach(identity => delete rosterTeams[identity]);
    players.forEach(p => { rosterTeams[p.identity] = p.team; });
    rosterLocalTeam = localTeam || null;

    let groups;
    if (localTeam) {
        const allies = [
            ...players.filter(p => p.identity === localId),
            ...players.filter(p => p.team === localTeam && p.identity !== localId)
        ];
        const enemies = players.filter(p => p.team !== localTeam);
        groups = [
            {key: 'allies', label: 'Allies', players: allies},
            {key: 'enemies', label: 'Enemies', players: enemies}
        ].filter(g => g.players.length > 0);
    } else {
        groups = [{
            key: 'allies',
            label: 'Players',
            players: [
                ...players.filter(p => p.identity === localId),
                ...players.filter(p => p.identity !== localId)
            ]
        }];
    }

    groups.forEach(group => {
        const section = document.createElement('div');
        section.className = 'team-section' + (rosterCollapsed[group.key] ? ' collapsed' : '');

        const head = document.createElement('div');
        head.className = `team-head ${group.key}`;
        head.innerHTML = `<span class="team-chevron">▼</span><span>${group.label}</span><span class="team-count">${group.players.length}</span>`;
        head.onclick = () => {
            rosterCollapsed[group.key] = !rosterCollapsed[group.key];
            section.classList.toggle('collapsed', rosterCollapsed[group.key]);
        };
        section.appendChild(head);

        const body = document.createElement('div');
        body.className = 'team-body';
        section.appendChild(body);

        group.players.forEach(player => {
        const div = document.createElement('div');
        div.className = 'player-item';
        div.id = `roster-${player.identity}`;

        if (player.champion && player.champion !== 'Unknown') {
            const champ = CHAMP_NAME_FIXES[player.champion] || player.champion;
            const splash = document.createElement('div');
            splash.className = 'player-splash';
            div.appendChild(splash);

            const trySplash = (skin) => {
                const url = SPLASH_URL(champ, skin);
                const probe = new Image();
                probe.onload = () => {
                    splash.style.backgroundImage = `url("${url}")`;
                    div.classList.add('has-splash');
                };
                probe.onerror = () => { if (skin !== 0) trySplash(0); };
                probe.src = url;
            };
            resolveSkinNum(champ, Number(player.skinId) || 0).then(trySplash);
        }

        const meta = document.createElement('div');
        meta.className = 'player-meta';

        const avatar = document.createElement('div');
        avatar.className = 'avatar';
        const initialMatch = (player.name || player.identity || '?').match(/[A-Za-z0-9]/);
        avatar.innerText = initialMatch ? initialMatch[0].toUpperCase() : '?';
        const hasIconData = typeof player.profileIconData === 'string' && player.profileIconData.startsWith('data:');

        if (hasIconData || player.profileIconId > 0) {
            const icon = document.createElement('img');
            icon.className = 'avatar-img';
            icon.alt = '';
            const sources = [];
            if (hasIconData) sources.push(player.profileIconData);
            if (player.profileIconId > 0) {
                sources.push(`/profile-icon/${player.profileIconId}`, PROFILE_ICON_URL(player.profileIconId));
            }
            let attempt = 0;
            let ddragonAdded = false;
            icon.onerror = async () => {
                if (!ddragonAdded && player.profileIconId > 0) {
                    const ver = await ddragonVersionPromise;
                    if (ver) {
                        ddragonAdded = true;
                        sources.push(`https://ddragon.leagueoflegends.com/cdn/${ver}/img/profileicon/${player.profileIconId}.png`);
                    }
                }
                attempt++;
                if (attempt < sources.length) {
                    icon.src = sources[attempt];
                } else {
                    console.warn(`[Icon] Could not load profile icon ${player.profileIconId} for ${player.identity} from any source`);
                    icon.remove();
                }
            };
            icon.src = sources[0];
            avatar.appendChild(icon);
        } else {
            console.warn(`[Icon] No profile icon id received for ${player.identity} (got ${player.profileIconId})`);
        }
        meta.appendChild(avatar);

        const nameSpan = document.createElement('span');
        nameSpan.className = 'player-name';

        if (player.identity === matchLeaderIdentity) {
            nameSpan.innerText = `👑 ${player.name}`;
        } else {
            nameSpan.innerText = player.name;
        }
        meta.appendChild(nameSpan);

        if (player.identity === localUserIdentity) {
            const youTag = document.createElement('span');
            youTag.className = 'you-tag';
            youTag.innerText = 'YOU';
            meta.appendChild(youTag);
        }

        const centerDiv = document.createElement('div');
        centerDiv.className = 'player-center-content';
        centerDiv.innerHTML = `
            <div class="voice-wave">
                <div class="wave-bar"></div>
                <div class="wave-bar"></div>
                <div class="wave-bar"></div>
                <div class="wave-bar"></div>
                <div class="wave-bar"></div>
                <div class="wave-bar"></div>
                <div class="wave-bar"></div>
                <div class="wave-bar"></div>
                <div class="wave-bar"></div>
            </div>
        `;

        const controlsContainer = document.createElement('div');
        controlsContainer.style.display = 'flex';
        controlsContainer.style.alignItems = 'center';
        controlsContainer.style.gap = '10px';

        if (player.identity !== localUserIdentity && showDistances) {
            const distSpan = document.createElement('span');
            distSpan.className = 'distance-badge';
            distSpan.id = `dist-${player.identity}`;
            distSpan.innerText = "0.0m";
            controlsContainer.appendChild(distSpan);
        }

        const statusSpan = document.createElement('span');
        statusSpan.className = 'player-status status-offline';
        statusSpan.id = `status-${player.identity}`;
        statusSpan.innerText = "Not Connected";

        controlsContainer.appendChild(statusSpan);

        if (player.identity !== localUserIdentity) {
            const volSlider = document.createElement('input');
            volSlider.type = 'range';
            volSlider.className = 'volume-slider';
            volSlider.min = '0';
            volSlider.max = '2';
            volSlider.step = '0.05';
            volSlider.value = playerVolumes[player.identity] ?? 1;
            volSlider.title = `Volume: ${Math.round(volSlider.value * 100)}%`;
            volSlider.oninput = () => {
                playerVolumes[player.identity] = Number(volSlider.value);
                localStorage.setItem('playerVolumes', JSON.stringify(playerVolumes));
                volSlider.title = `Volume: ${Math.round(volSlider.value * 100)}%`;
                updateRemoteAudio(player.identity);
            };
            controlsContainer.appendChild(volSlider);
        }

        if (player.identity !== localUserIdentity) {
            const localMuteBtn = document.createElement('button');
            const muted = locallyMuted.has(player.identity);
            localMuteBtn.className = 'action-btn local-mute-btn' + (muted ? ' muted' : '');
            localMuteBtn.id = `localmute-${player.identity}`;
            localMuteBtn.innerText = muted ? '🔇' : '🔊';
            localMuteBtn.title = muted ? 'Unmute (for you only)' : 'Mute (for you only)';
            localMuteBtn.onclick = () => toggleLocalMute(player.identity);
            controlsContainer.appendChild(localMuteBtn);
        }

        if (localUserIdentity === matchLeaderIdentity && player.identity !== localUserIdentity) {
            const actionBtn = document.createElement('button');
            actionBtn.className = 'action-btn kick-btn';
            actionBtn.id = `action-${player.identity}`;
            actionBtn.innerText = 'Kick';
            actionBtn.style.display = 'none';
            actionBtn.onclick = () => handleModerationAction(player.identity, player.name);
            controlsContainer.appendChild(actionBtn);
        }

        div.appendChild(meta);
        div.appendChild(centerDiv);
        div.appendChild(controlsContainer);

            body.appendChild(div);
        });

        container.appendChild(section);
    });

    document.getElementById('roster-count').innerText = players.length;
}

function handleModerationAction(identity, name) {
    const btn = document.getElementById(`action-${identity}`);
    if (!btn || !trackerSocket) return;

    if (btn.innerText === 'Kick') {
        trackerSocket.send(JSON.stringify({type: "KICK_USER", identity: identity, name: name}));
    } else {
        trackerSocket.send(JSON.stringify({type: "REVOKE_BAN", identity: identity, name: name}));
    }
}

function updatePlayerStatus(identity, statusText, cssClass) {
    const statusSpan = document.getElementById(`status-${identity}`);
    if (statusSpan) {
        statusSpan.innerText = statusText;
        statusSpan.className = `player-status ${cssClass}`;
    }

    const actionBtn = document.getElementById(`action-${identity}`);
    if (actionBtn) {
        if (statusText === "Connected" || statusText === "Banned") {
            actionBtn.style.display = 'block';
        } else {
            actionBtn.style.display = 'none';
        }
    }

    const distSpan = document.getElementById(`dist-${identity}`);
    if (distSpan) {
        distSpan.style.display = (statusText === "Connected") ? 'inline-block' : 'none';
    }
}

function setPlayerBanned(identity, banned) {
    const row = document.getElementById(`roster-${identity}`);
    if (row) {
        const nameSpan = row.querySelector('.player-name');
        if (nameSpan) nameSpan.classList.toggle('banned-name', banned);
    }

    const btn = document.getElementById(`action-${identity}`);
    if (btn) {
        btn.className = banned ? 'action-btn revoke-btn' : 'action-btn kick-btn';
        btn.innerText = banned ? 'Revoke Ban' : 'Kick';
    }

    updatePlayerStatus(identity, banned ? "Banned" : "Not Connected", "status-offline");
}
