// end-game stats screen

const escHtml = s => String(s).replace(/[&<>"']/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));

function returnToLobby() {
    fadeOutOverlay(document.getElementById('endgame-screen'));
}

async function showEndgameScreen() {
    const scr = document.getElementById('endgame-screen');
    const wrap = document.getElementById('eg-wrap');
    wrap.innerHTML = '<div class="eg-sub" style="margin-top: 40vh">Loading match stats…</div>';

    fadeInOverlay(scr, 'block');
    haltPotgPlayback();
    fadeOutOverlay(document.getElementById('potg-viewer'));

    try {
        const s = await (await fetch('/potg/stats', {cache: 'no-store'})).json();
        if (!s.available) throw new Error('unavailable');
        await renderEndgame(s);
    } catch (e) {
        wrap.innerHTML = `
            <div class="eg-result">Game Complete</div>
            <div class="eg-sub">Match stats are unavailable for this game</div>
            <div class="eg-footer"><button id="eg-return" class="potg-btn potg-btn-primary">Return to Lobby</button></div>`;
        document.getElementById('eg-return').addEventListener('click', returnToLobby);
    }
}

const EG_MAP_NAMES = {8: 'Crystal Scar', 10: 'Twisted Treeline', 11: 'Summoner’s Rift', 12: 'Howling Abyss', 21: 'Nexus Blitz', 22: 'Convergence', 30: 'Rings of Wrath', 33: 'The Bandlewood', 35: 'Swarm'};
const EG_MODE_NAMES = {CLASSIC: 'Summoner’s Rift', ARAM: 'Howling Abyss', TUTORIAL: 'Summoner’s Rift', PRACTICETOOL: 'Practice Tool', URF: 'Ultra Rapid Fire', ARURF: 'ARURF', ONEFORALL: 'One for All', NEXUSBLITZ: 'Nexus Blitz', CHERRY: 'Arena', ULTBOOK: 'Ultimate Spellbook'};
const DOT = '<span class="eg-dot">◆</span>';
const fmtBigNum = n => n >= 1000 ? (n / 1000).toFixed(1) + 'k' : String(n);

async function renderEndgame(s) {
    const wrap = document.getElementById('eg-wrap');
    const ver = await ddragonVersionPromise;
    const players = s.players || [];
    const local = players.find(p => p.riotId && p.riotId.toLowerCase() === String(s.localIdentity || '').toLowerCase());
    const localTeam = local ? local.team : 'ORDER';
    const champIds = await Promise.all(players.map(p => getChampionIdByAlias(p.champion).catch(() => null)));

    const resultClass = s.result === 'Lose' ? 'defeat' : '';
    const resultText = s.result === 'Win' ? 'Victory' : (s.result === 'Lose' ? 'Defeat' : 'Game Complete');
    const mapName = EG_MAP_NAMES[s.mapNumber]
        || EG_MODE_NAMES[String(s.gameMode || '').toUpperCase()]
        || (s.gameMode ? s.gameMode.charAt(0).toUpperCase() + s.gameMode.slice(1).toLowerCase() : 'Summoner’s Rift');

    const maxDmg = Math.max(0, ...players.map(p => p.damage > 0 ? p.damage : 0));
    const champIdOf = new Map(players.map((p, i) => [p, champIds[i]]));

    const rowHtml = (p) => {
        const cid = champIdOf.get(p);
        const champImg = cid
            ? `https://raw.communitydragon.org/latest/plugins/rcp-be-lol-game-data/global/default/v1/champion-icons/${cid}.png`
            : (ver ? `https://ddragon.leagueoflegends.com/cdn/${ver}/img/champion/${encodeURIComponent(p.champion)}.png` : '');
        const items = (ver && Array.isArray(p.items))
            ? p.items.filter(id => id > 0).map(id => `<img src="https://ddragon.leagueoflegends.com/cdn/${ver}/img/item/${id}.png" alt="" onerror="this.remove()">`).join('')
            : '';
        const kdaRatio = ((p.kills + p.assists) / Math.max(1, p.deaths)).toFixed(1);
        const champFix = CHAMP_NAME_FIXES[p.champion] || p.champion;
        const dmgCell = (p.damage >= 0 && maxDmg > 0)
            ? `<div class="eg-dmg">
                    <div class="eg-dmg-num">${fmtBigNum(p.damage)}</div>
                    <div class="eg-dmg-bar"><div class="eg-dmg-fill" data-fill="${Math.round(p.damage / maxDmg * 100)}" style="width:0"></div></div>
               </div>`
            : `<div class="eg-dmg-none">—</div>`;
        return `
            <div class="eg-row${p === local ? ' eg-local' : ''}" data-champ="${escHtml(champFix)}" data-skin="${Number(p.skinId) || 0}">
                <div class="eg-splash"></div>
                <div class="eg-champ">${champImg ? `<img src="${champImg}" alt="">` : ''}<span class="eg-level">${p.level}</span></div>
                <div><div class="eg-name">${escHtml(p.riotId || p.champion)}</div><div class="eg-champname">${escHtml(p.champion)}</div></div>
                <div class="eg-kda">${p.kills} / ${p.deaths} / ${p.assists}<span>${kdaRatio} KDA</span></div>
                <div class="eg-stat">${p.creepScore}<span>CS</span></div>
                <div class="eg-stat">${p.wardScore}<span>Wards</span></div>
                ${dmgCell}
                <div class="eg-items">${items}</div>
            </div>`;
    };

    const teamHtml = (label, cls, list) => {
        const k = list.reduce((a, p) => a + p.kills, 0);
        const d = list.reduce((a, p) => a + p.deaths, 0);
        const a2 = list.reduce((a, p) => a + p.assists, 0);
        const hasGold = list.length > 0 && list.every(p => p.gold >= 0);
        const gold = list.reduce((acc, p) => acc + Math.max(0, p.gold), 0);
        const goldPart = hasGold ? `${DOT}<span class="team-stat">${fmtBigNum(gold)}</span>` : '';
        return `
            <div>
                <div class="eg-team-label">
                    <span class="team-name ${cls}">${label}</span>
                    ${DOT}<span class="team-stat">${k} / ${d} / ${a2}</span>
                    ${goldPart}
                </div>
                <div class="eg-rows">${list.map(rowHtml).join('')}</div>
            </div>`;
    };

    const allies = players.filter(p => p.team === localTeam);
    const enemies = players.filter(p => p.team !== localTeam);

    wrap.innerHTML = `
        <div class="eg-result ${resultClass}">${resultText}</div>
        <div class="eg-sub">${escHtml(mapName)}${DOT}${escHtml(s.gameClock || '')}</div>
        ${teamHtml('Allies', 'allies', allies)}
        ${teamHtml('Enemies', 'enemies', enemies)}
        <div class="eg-footer"><button id="eg-return" class="potg-btn potg-btn-primary">Return to Lobby</button></div>`;

    wrap.querySelectorAll('.eg-row').forEach((row, idx) => {
        row.style.animationDelay = `${0.14 + idx * 0.05}s`;

        const fill = row.querySelector('.eg-dmg-fill');
        if (fill) requestAnimationFrame(() => { fill.style.width = `${fill.dataset.fill}%`; });

        const champ = row.dataset.champ;
        if (!champ) return;
        const splash = row.querySelector('.eg-splash');
        const trySplash = (skin) => {
            const url = SPLASH_URL(champ, skin);
            const probe = new Image();
            probe.onload = () => {
                splash.style.backgroundImage = `url("${url}")`;
                row.classList.add('has-splash');
            };
            probe.onerror = () => { if (skin !== 0) trySplash(0); };
            probe.src = url;
        };
        resolveSkinNum(champ, Number(row.dataset.skin) || 0).then(trySplash);
    });

    document.getElementById('eg-return').addEventListener('click', returnToLobby);
}
