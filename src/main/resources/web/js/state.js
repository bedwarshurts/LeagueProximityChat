let LIVEKIT_URL = '';
let livekitConfigured = false;

let lowPerformanceMode = false;

let room = null;
let audioCtx;
let masterLimiter = null;
const remoteAudioNodes = {};
const remotePositions = {};
const localPosition = {x: 0, y: 0, isDead: false};

const rosterTeams = {};
let rosterLocalTeam = null;
let deadListen = null;
let visibleEnemyIcons = [];
const enemyLastSeen = {};
const DEAD_VISION_MATCH_DIST = 6;
const DEAD_VISION_GRACE_MS = 750;

let hasReceivedPosition = false;
let positionGateActive = false;
let gamePaused = false;

let micMuted = false;
let isDeafened = false;
let micMutedBeforeDeafen = false;
let masterGain = null;
let krispProcessor = null;
let krispEnabled = localStorage.getItem('krispEnabled') !== 'false';

const MIC_DEVICE_KEY = 'micDeviceId';
const MIC_LABEL_KEY = 'micDeviceLabel';
let micDeviceId = '';
let micDeviceLabel = '';
try {
    micDeviceId = localStorage.getItem(MIC_DEVICE_KEY) || '';
    micDeviceLabel = localStorage.getItem(MIC_LABEL_KEY) || '';
} catch (e) {}
const locallyMuted = new Set();
const playerVolumes = (() => {
    try { return JSON.parse(localStorage.getItem('playerVolumes')) || {}; } catch (e) { return {}; }
})();

const rosterCollapsed = { allies: false, enemies: false };
let connected = false;
let isWaitingForMatch = false;
let trackerSocket = null;
let localUserIdentity = null;
let matchLeaderIdentity = null;
let isReplaced = false;
