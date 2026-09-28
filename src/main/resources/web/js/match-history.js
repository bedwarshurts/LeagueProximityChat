// match history

const MH_QUEUE_NAMES = {0: 'Custom Game', 400: 'Normal Draft', 420: 'Ranked Solo/Duo', 430: 'Normal Blind', 440: 'Ranked Flex', 450: 'ARAM', 490: 'Quickplay', 700: 'Clash', 1700: 'Arena', 1900: 'URF'};

const MH_REPLAY_STATES = {
    watch: {label: 'Watch Replay', enabled: true},
    download: {label: 'Download Replay', enabled: true},
    retryDownload: {label: 'Download Replay', enabled: true},
    downloading: {label: 'Downloading Replay…', poll: true},
    checking: {label: 'Checking Replay…', poll: true},
    found: {label: 'Checking Replay…', poll: true},
    clientClosed: {label: 'Watch Replay', note: 'Open the League client to watch or download this replay.', poll: true},
    incompatible: {label: 'Replay Unavailable', note: 'This replay was recorded on an older patch, so League can no longer play it.'},
    missingOrExpired: {label: 'Replay Unavailable', note: 'This replay has expired and is no longer available from Riot.'},
    lost: {label: 'Replay Unavailable', note: 'This replay is no longer available.'},
    unsupported: {label: 'Replay Unavailable', note: 'Replays are not available for this game.'},
    error: {label: 'Replay Unavailable', note: 'The League client could not find this replay.'}
};

let mhOpenMatch = null;
let mhReplayTimer = null;

function formatMatchDate(ms) {
    const d = new Date(ms);
    return `${d.toLocaleDateString(undefined, {month: 'short', day: 'numeric'})}, ${d.toLocaleTimeString(undefined, {hour: '2-digit', minute: '2-digit'})}`;
}

function formatMatchDuration(seconds) {
    const s = Math.max(0, Math.round(seconds || 0));
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

function matchModeName(m) {
    if (Object.hasOwn(MH_QUEUE_NAMES, m.queueId)) return MH_QUEUE_NAMES[m.queueId];
    return EG_MODE_NAMES[String(m.gameMode || '').toUpperCase()] || EG_MAP_NAMES[m.mapNumber] || 'Match';
}

async function loadMatchHistory() {
    const list = document.getElementById('mh-list');
    list.innerHTML = '<div class="mh-empty">Loading match history…</div>';
    let matches;
    try {
        matches = (await (await fetch('/matches', {cache: 'no-store'})).json()).matches || [];
    } catch (e) {
        list.innerHTML = '<div class="mh-empty">Could not load the match history. Is the app still running?</div>';
        return;
    }
    if (matches.length === 0) {
        list.innerHTML = '<div class="mh-empty">No matches yet. Your last 20 games on this PC will show up here.</div>';
        return;
    }
    const ver = await ddragonVersionPromise;
    const champIds = await Promise.all(matches.map(m => m.local ? getChampionIdByAlias(m.local.champion).catch(() => null) : null));
    list.replaceChildren(...matches.map((m, i) => matchCard(m, champIds[i], ver)));
}

function matchCard(m, champId, ver) {
    const local = m.local || null;
    const card = document.createElement('button');
    card.type = 'button';
    card.className = `mh-card ${m.result === 'Win' ? 'win' : m.result === 'Lose' ? 'loss' : ''}`;
    const img = champId ? `https://raw.communitydragon.org/latest/plugins/rcp-be-lol-game-data/global/default/v1/champion-icons/${champId}.png` : '';
    const result = m.result === 'Win' ? 'Victory' : m.result === 'Lose' ? 'Defeat' : 'Game Complete';
    const clips = m.highlightCount > 0 ? `<span class="mh-clips">${m.highlightCount} highlight${m.highlightCount === 1 ? '' : 's'}</span>` : '';
    const items = (ver && local && Array.isArray(local.items))
        ? local.items.filter(id => id > 0).map(id => `<img src="https://ddragon.leagueoflegends.com/cdn/${ver}/img/item/${id}.png" alt="" onerror="this.remove()">`).join('')
        : '';
    card.innerHTML = `
        <div class="eg-splash"></div>
        <div class="mh-champ">${img ? `<img src="${img}" alt="">` : ''}</div>
        <div>
            <div class="mh-result">${result}</div>
            <div class="mh-mode">${escHtml(matchModeName(m))} · ${formatMatchDuration(m.durationSeconds)}</div>
        </div>
        <div class="mh-kda">${local ? `${local.kills} / ${local.deaths} / ${local.assists}` : ''}<span>${escHtml(local ? local.champion : '')}</span></div>
        <div class="eg-stat">${local && local.creepScore !== undefined ? local.creepScore : '—'}<span>CS</span></div>
        <div class="mh-items">${items}</div>
        <div class="mh-meta">${escHtml(formatMatchDate(m.startedAt))}${clips}</div>`;
    if (local && local.champion) {
        loadChampionSplash(card, CHAMP_NAME_FIXES[local.champion] || local.champion, Number(local.skinId) || 0);
    }
    card.addEventListener('click', () => openMatch(m.id));
    return card;
}

async function openMatch(id) {
    try {
        const resp = await fetch(`/matches/${encodeURIComponent(id)}`, {cache: 'no-store'});
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        mhOpenMatch = await resp.json();
    } catch (e) {
        return;
    }
    showEndgameScreen(mhOpenMatch);
}

function historyFooterHtml(match) {
    const clips = (match.highlights || []).filter(h => h.available).length;
    return `
        <div class="eg-footer mh-footer">
            <button id="mh-back" class="potg-btn">Back</button>
            ${clips > 0 ? `<button id="mh-highlights" class="potg-btn">Highlights (${clips})</button>` : ''}
            <button id="mh-replay" class="potg-btn potg-btn-primary" disabled>Checking Replay…</button>
        </div>
        <div class="mh-replay-note" id="mh-replay-note"></div>`;
}

function bindHistoryFooter(match) {
    mhOpenMatch = match;
    document.getElementById('mh-back').addEventListener('click', closeMatch);
    const highlights = document.getElementById('mh-highlights');
    if (highlights) highlights.addEventListener('click', () => openMatchHighlights(match));
    document.getElementById('mh-replay').addEventListener('click', () => onReplayClicked(match));
    refreshReplayState(match);
}

function closeMatch() {
    clearTimeout(mhReplayTimer);
    mhOpenMatch = null;
    fadeOutOverlay(document.getElementById('endgame-screen'));
}

function openMatchHighlights(match) {
    clearTimeout(mhReplayTimer);
    potgHighlights = match.highlights.filter(h => h.available).map((h, i) => ({
        index: i, id: h.id, headline: h.headline, gameClock: h.gameClock, file: h.url, frames: []
    }));
    if (potgHighlights.length === 0) return;
    fadeOutOverlay(document.getElementById('endgame-screen'));
    openHighlightViewer(match);
}

async function refreshReplayState(match) {
    clearTimeout(mhReplayTimer);
    let info;
    try {
        info = await (await fetch(`/matches/${encodeURIComponent(match.id)}/replay`, {cache: 'no-store'})).json();
    } catch (e) {
        info = {state: 'error'};
    }
    const btn = document.getElementById('mh-replay');
    if (mhOpenMatch !== match || !btn) return;

    match.replay = info;
    const view = MH_REPLAY_STATES[info.state] || MH_REPLAY_STATES.error;
    btn.textContent = info.state === 'downloading' && info.downloadProgress > 0
        ? `Downloading Replay… ${info.downloadProgress}%`
        : view.label;
    btn.disabled = !view.enabled;
    btn.dataset.state = info.state;
    document.getElementById('mh-replay-note').textContent = view.note || '';
    if (view.poll) mhReplayTimer = setTimeout(() => refreshReplayState(match), 2000);
}

async function onReplayClicked(match) {
    const btn = document.getElementById('mh-replay');
    const note = document.getElementById('mh-replay-note');
    const state = btn.dataset.state;

    if (state === 'download' || state === 'retryDownload') {
        btn.disabled = true;
        btn.textContent = 'Downloading Replay…';
        await fetch(`/matches/${encodeURIComponent(match.id)}/replay/download`, {method: 'POST'}).catch(() => null);
        mhReplayTimer = setTimeout(() => refreshReplayState(match), 1000);
        return;
    }
    if (state !== 'watch') return;

    if (!match.replay || !match.replay.replayApiEnabled) {
        const enabled = await askToEnableReplayApi(match.replay ? match.replay.gameCfgPath : '');
        if (!enabled) return;
        match.replay.replayApiEnabled = true;
    }

    btn.disabled = true;
    btn.textContent = 'Opening Replay…';
    const resp = await fetch(`/matches/${encodeURIComponent(match.id)}/replay/watch`, {method: 'POST'}).catch(() => null);
    note.textContent = resp && resp.ok ? 'The replay is opening in League.' : 'League could not open this replay.';
    mhReplayTimer = setTimeout(() => refreshReplayState(match), 4000);
}

function askToEnableReplayApi(cfgPath) {
    return new Promise(resolve => {
        const popup = document.getElementById('replay-api-popup');
        const close = document.getElementById('replay-api-close');
        const agree = document.getElementById('replay-api-agree');
        document.getElementById('replay-api-text').textContent =
            `The Replay API needs to be turned on for this feature to work. By pressing I Understand below, `
            + `you agree for LeagueProximityChat to write "EnableReplayApi=1" in ${cfgPath}`;

        const finish = async agreed => {
            close.onclick = null;
            agree.onclick = null;
            let enabled = false;
            if (agreed) {
                const result = await fetch('/replay-api/enable', {method: 'POST'})
                    .then(r => r.json()).catch(() => ({ok: false}));
                enabled = Boolean(result.ok);
                if (!enabled) {
                    const note = document.getElementById('mh-replay-note');
                    if (note) note.textContent = `Could not write to ${cfgPath}${result.error ? ` (${result.error})` : ''}.`;
                }
            }
            popup.style.display = 'none';
            resolve(enabled);
        };
        close.onclick = () => finish(false);
        agree.onclick = () => finish(true);
        popup.style.display = 'flex';
    });
}
