let logRefreshTimer = null;

async function refreshLogs() {
    const el = document.getElementById('log-content');
    try {
        const resp = await fetch('/logs', {cache: 'no-store'});
        const text = await resp.text();
        const stickToBottom = el.scrollTop + el.clientHeight >= el.scrollHeight - 8;
        el.textContent = text || '(no log output yet)';
        if (stickToBottom) el.scrollTop = el.scrollHeight;
    } catch (e) {
        el.textContent = 'Could not fetch the logs — is the app still running?';
    }
}

document.getElementById('debug-btn').addEventListener('click', async () => {
    document.getElementById('log-viewer').style.display = 'flex';
    await refreshLogs();
    const el = document.getElementById('log-content');
    el.scrollTop = el.scrollHeight;
    if (!logRefreshTimer) logRefreshTimer = setInterval(refreshLogs, 2000);
});

document.getElementById('log-refresh').addEventListener('click', refreshLogs);

document.getElementById('setup-open-logs').addEventListener('click', async () => {
    try {
        const resp = await fetch('/open-log-viewer', {method: 'POST'});
        if (resp.ok) return;
    } catch (e) {}
    window.open('/logs', '_blank');
});

document.getElementById('log-close').addEventListener('click', () => {
    document.getElementById('log-viewer').style.display = 'none';
    if (logRefreshTimer) {
        clearInterval(logRefreshTimer);
        logRefreshTimer = null;
    }
});
