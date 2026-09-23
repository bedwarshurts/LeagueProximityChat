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

async function listAudioDevices(kind) {
    if (!navigator.mediaDevices || !navigator.mediaDevices.enumerateDevices) return [];
    const devicesOfKind = async () => (await navigator.mediaDevices.enumerateDevices()).filter(d => d.kind === kind);
    let devices = await devicesOfKind();
    if (devices.length > 0 && devices.every(d => !d.label)) {
        try {
            const probe = await navigator.mediaDevices.getUserMedia({audio: true});
            probe.getTracks().forEach(t => t.stop());
            devices = await devicesOfKind();
        } catch (e) {}
    }
    return devices.filter(d => d.deviceId && d.deviceId !== 'default' && d.deviceId !== 'communications');
}

function matchSavedDevice(devices, savedId, savedLabel) {
    if (!savedId) return null;
    return devices.find(d => d.deviceId === savedId)
        || (savedLabel ? devices.find(d => d.label === savedLabel) : null)
        || null;
}

async function resolveSavedDevice(kind, savedId, savedLabel) {
    if (!savedId) return '';
    try {
        const match = matchSavedDevice(await listAudioDevices(kind), savedId, savedLabel);
        return match ? match.deviceId : '';
    } catch (e) {
        return '';
    }
}

async function populateDeviceSelect(selectId, kind, savedId, savedLabel, deviceNoun) {
    const select = document.getElementById(selectId);
    const pending = select.options.length ? select.value : null;
    let devices = [];
    try { devices = await listAudioDevices(kind); } catch (e) {}
    const saved = matchSavedDevice(devices, savedId, savedLabel);

    select.innerHTML = '';
    select.add(new Option('System default', ''));
    devices.forEach((d, i) => {
        const opt = new Option(d.label || `${deviceNoun} ${i + 1}`, d.deviceId);
        opt.dataset.label = d.label || '';
        select.add(opt);
    });
    if (savedId && !saved) {
        const opt = new Option(`${savedLabel || `Saved ${deviceNoun.toLowerCase()}`} (not connected)`, savedId);
        opt.dataset.label = savedLabel;
        select.add(opt);
    }

    const values = Array.from(select.options).map(o => o.value);
    if (pending !== null && values.includes(pending)) select.value = pending;
    else select.value = saved ? saved.deviceId : savedId;
}

function readDeviceSelect(selectId) {
    const select = document.getElementById(selectId);
    if (!select.options.length) return null;
    const option = select.selectedOptions[0];
    const id = select.value;
    return {id, label: id && option ? (option.dataset.label || '') : ''};
}

async function resolveMicDeviceId() {
    return resolveSavedDevice('audioinput', micDeviceId, micDeviceLabel);
}

async function populateMicSelect() {
    await populateDeviceSelect('setup-mic', 'audioinput', micDeviceId, micDeviceLabel, 'Microphone');
}

async function saveMicrophoneChoice() {
    const choice = readDeviceSelect('setup-mic');
    if (!choice) return;
    const changed = choice.id !== micDeviceId;
    micDeviceId = choice.id;
    micDeviceLabel = choice.label;
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

function speakerSelectionSupported() {
    return Boolean(window.AudioContext && typeof AudioContext.prototype.setSinkId === 'function');
}

async function populateSpeakerSelect() {
    const row = document.getElementById('setup-speaker').closest('.setup-toggle');
    row.style.display = speakerSelectionSupported() ? '' : 'none';
    await populateDeviceSelect('setup-speaker', 'audiooutput', speakerDeviceId, speakerDeviceLabel, 'Speaker');
}

async function saveSpeakerChoice() {
    const choice = readDeviceSelect('setup-speaker');
    if (!choice) return;
    const changed = choice.id !== speakerDeviceId;
    speakerDeviceId = choice.id;
    speakerDeviceLabel = choice.label;
    try {
        localStorage.setItem(SPEAKER_DEVICE_KEY, speakerDeviceId);
        localStorage.setItem(SPEAKER_LABEL_KEY, speakerDeviceLabel);
    } catch (e) {}
    if (changed) await applySpeakerOutput();
}

async function routeToSpeaker(output) {
    if (!output || typeof output.setSinkId !== 'function') return;
    try {
        const id = await resolveSavedDevice('audiooutput', speakerDeviceId, speakerDeviceLabel);
        if (output.sinkId !== id) await output.setSinkId(id);
    } catch (e) {
        console.warn('[Speaker] Could not switch the output device:', e);
    }
}

async function applySpeakerOutput() {
    await routeToSpeaker(audioCtx);
    await routeToSpeaker(potgAudioEl);
}

if (navigator.mediaDevices && navigator.mediaDevices.addEventListener) {
    navigator.mediaDevices.addEventListener('devicechange', () => {
        if (document.getElementById('setup-screen').style.display === 'flex') {
            populateMicSelect();
            populateSpeakerSelect();
        }

        applySpeakerOutput();
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
    if (!onboarding) {
        populateMicSelect();
        populateSpeakerSelect();
    }
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
        await saveSpeakerChoice();
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
