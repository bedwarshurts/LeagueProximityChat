const PROFILE_ICON_URL = (id) => `https://raw.communitydragon.org/latest/plugins/rcp-be-lol-game-data/global/default/v1/profile-icons/${id}.jpg`;
const SPLASH_URL = (champ, skin) => `https://ddragon.leagueoflegends.com/cdn/img/champion/centered/${champ}_${skin}.jpg`;
const CHAMP_NAME_FIXES = { FiddleSticks: 'Fiddlesticks' };

const ddragonVersionPromise = fetch('https://ddragon.leagueoflegends.com/api/versions.json')
    .then(r => r.json())
    .then(v => v[0])
    .catch(() => null);

let champSummaryPromise = null;
function getChampionIdByAlias(alias) {
    if (!champSummaryPromise) {
        champSummaryPromise = fetch('https://raw.communitydragon.org/latest/plugins/rcp-be-lol-game-data/global/default/v1/champion-summary.json')
            .then(r => r.json())
            .catch(() => null);
    }
    return champSummaryPromise.then(list => {
        if (!list) return null;
        const hit = list.find(c => c.alias && c.alias.toLowerCase() === alias.toLowerCase());
        return hit ? hit.id : null;
    });
}

const champSkinDataCache = {};
async function resolveSkinNum(champ, skinId) {
    if (!skinId) return 0;
    try {
        const champId = await getChampionIdByAlias(champ);
        if (!champId) return skinId;
        if (!champSkinDataCache[champ]) {
            champSkinDataCache[champ] = fetch(`https://raw.communitydragon.org/latest/plugins/rcp-be-lol-game-data/global/default/v1/champions/${champId}.json`)
                .then(r => r.json())
                .then(d => {

                    const chromaToParent = {};
                    (d.skins || []).forEach(s => {
                        const num = s.id - champId * 1000;
                        (s.chromas || []).forEach(c => { chromaToParent[c.id - champId * 1000] = num; });
                    });
                    return chromaToParent;
                });
        }
        const chromaToParent = await champSkinDataCache[champ];
        if (chromaToParent[skinId] !== undefined) return chromaToParent[skinId];
        return skinId;
    } catch (e) {
        return skinId;
    }
}
