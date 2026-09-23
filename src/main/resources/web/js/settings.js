async function fetchLivekitSettings() {
    const resp = await fetch('/settings', {cache: 'no-store'});
    const s = await resp.json();
    livekitConfigured = Boolean(s.configured);
    LIVEKIT_URL = s.url || '';
    document.getElementById('setup-url').value = s.url || '';
    document.getElementById('setup-key').value = s.apiKey || '';
    document.getElementById('setup-secret').value = s.apiSecret || '';
    lowPerformanceMode = Boolean(s.lowPerformanceMode);
    document.getElementById('setup-lowperf').checked = lowPerformanceMode;
    document.getElementById('setup-debug').checked = Boolean(s.debugMode);
    const versionEl = document.getElementById('setup-version');
    versionEl.replaceChildren();
    if (s.version) {
        const versionLine = document.createElement('span');
        versionLine.textContent = `Version: ${s.version}`;
        versionEl.append(versionLine);
        if (s.build) {
            const buildLine = document.createElement('span');
            buildLine.textContent = s.build;
            versionEl.append(buildLine);
        }
    }
}

async function listMicrophones() {
    if (!navigator.mediaDevices || !navigator.mediaDevices.enumerateDevices) return [];
    const audioInputs = async () => (await navigator.mediaDevices.enumerateDevices()).filter(d => d.kind === 'audioinput');
    let inputs = await audioInputs();
    if (inputs.length > 0 && inputs.every(d => !d.label)) {
        try {
            const probe = await navigator.mediaDevices.getUserMedia({audio: true});
            probe.getTracks().forEach(t => t.stop());
            inputs = await audioInputs();
        } catch (e) {}
    }
    return inputs.filter(d => d.deviceId && d.deviceId !== 'default' && d.deviceId !== 'communications');
}

function matchSavedMic(mics) {
    if (!micDeviceId) return null;
    return mics.find(d => d.deviceId === micDeviceId)
        || (micDeviceLabel ? mics.find(d => d.label === micDeviceLabel) : null)
        || null;
}

async function resolveMicDeviceId() {
    if (!micDeviceId) return '';
    try {
        const match = matchSavedMic(await listMicrophones());
        return match ? match.deviceId : '';
    } catch (e) {
        return '';
    }
}

async function populateMicSelect() {
    const select = document.getElementById('setup-mic');
    const pending = select.options.length ? select.value : null;
    let mics = [];
    try { mics = await listMicrophones(); } catch (e) {}
    const saved = matchSavedMic(mics);

    select.innerHTML = '';
    select.add(new Option('System default', ''));
    mics.forEach((d, i) => {
        const opt = new Option(d.label || `Microphone ${i + 1}`, d.deviceId);
        opt.dataset.label = d.label || '';
        select.add(opt);
    });
    if (micDeviceId && !saved) {
        const opt = new Option(`${micDeviceLabel || 'Saved microphone'} (not connected)`, micDeviceId);
        opt.dataset.label = micDeviceLabel;
        select.add(opt);
    }

    const values = Array.from(select.options).map(o => o.value);
    if (pending !== null && values.includes(pending)) select.value = pending;
    else select.value = saved ? saved.deviceId : micDeviceId;
}

async function saveMicrophoneChoice() {
    const select = document.getElementById('setup-mic');
    if (!select.options.length) return;
    const option = select.selectedOptions[0];
    const id = select.value;
    const changed = id !== micDeviceId;
    micDeviceId = id;
    micDeviceLabel = id && option ? (option.dataset.label || '') : '';
    try {
        localStorage.setItem(MIC_DEVICE_KEY, micDeviceId);
        localStorage.setItem(MIC_LABEL_KEY, micDeviceLabel);
    } catch (e) {}
    if (changed && room && connected) await switchLiveMicrophone();
}

async function switchLiveMicrophone() {
    try {
        const id = await resolveMicDeviceId();
        await room.switchActiveDevice('audioinput', id || 'default');
        retapPotgMic();
        console.log('[Mic] Switched microphone.');
    } catch (e) {
        console.warn('[Mic] Could not switch microphone:', e);
    }
}

if (navigator.mediaDevices && navigator.mediaDevices.addEventListener) {
    navigator.mediaDevices.addEventListener('devicechange', () => {
        if (document.getElementById('setup-screen').style.display === 'flex') populateMicSelect();
    });
}

function selectSettingsCategory(cat) {
    document.querySelectorAll('.setup-tab').forEach(t => t.classList.toggle('active', t.dataset.cat === cat));
    document.querySelectorAll('.setup-cat').forEach(p => p.classList.toggle('active', p.id === `setup-cat-${cat}`));
}

document.querySelectorAll('.setup-tab').forEach(tab => {
    tab.addEventListener('click', () => selectSettingsCategory(tab.dataset.cat));
});

function showSetupScreen() {
    const screen = document.getElementById('setup-screen');

    const onboarding = !livekitConfigured;
    screen.classList.toggle('onboarding', onboarding);
    document.getElementById('setup-title').innerText = onboarding ? 'Voice Server Setup' : 'Settings';
    document.getElementById('setup-error').style.display = 'none';

    selectSettingsCategory(onboarding ? 'voice' : 'general');
    document.getElementById('main-ui').style.display = 'none';
    screen.style.display = 'flex';
    if (!onboarding) populateMicSelect();
}

function hideSetupScreen() {
    document.getElementById('setup-screen').style.display = 'none';
    document.getElementById('main-ui').style.display = 'grid';
}

async function initLivekitSettings() {
    try {
        await fetchLivekitSettings();
    } catch (e) {
        livekitConfigured = false;
    }
    if (!livekitConfigured) showSetupScreen();
}

document.getElementById('settings-btn').addEventListener('click', () => showSetupScreen());

document.getElementById('setup-save').addEventListener('click', async () => {
    const url = document.getElementById('setup-url').value.trim();
    const apiKey = document.getElementById('setup-key').value.trim();
    const apiSecret = document.getElementById('setup-secret').value.trim();
    const lowPerf = document.getElementById('setup-lowperf').checked;
    const debugOn = document.getElementById('setup-debug').checked;
    const errorEl = document.getElementById('setup-error');

    if (!url || !apiKey || !apiSecret) {
        selectSettingsCategory('voice');
        errorEl.innerText = 'All three fields are required.';
        errorEl.style.display = 'block';
        return;
    }

    try {
        const resp = await fetch('/settings', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({url, apiKey, apiSecret, lowPerformanceMode: lowPerf, debugMode: debugOn})
        });
        const out = await resp.json();
        if (!out.ok) throw new Error('rejected');
        await fetchLivekitSettings();
        await saveMicrophoneChoice();
        // mid session switch
        if (lowPerformanceMode) {
            stopPotgVideoCapture();
            stopPotgRecording();
        }
        hideSetupScreen();
    } catch (e) {
        errorEl.innerText = 'Could not save the settings is the app still running?';
        console.log(e);
        errorEl.style.display = 'block';
    }
});
