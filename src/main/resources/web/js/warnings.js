const warningQueue = [];
let activeWarning = null;

function showWarning(warning) {
    if (!warning || !warning.message) return;
    const sameId = w => w && warning.id && w.id === warning.id;
    if (sameId(activeWarning) || warningQueue.some(sameId)) return;
    warningQueue.push(warning);
    if (!activeWarning) showNextWarning();
}

function showNextWarning() {
    const popup = document.getElementById('warning-popup');
    activeWarning = warningQueue.shift() || null;
    if (!activeWarning) {
        popup.style.display = 'none';
        return;
    }

    document.getElementById('warning-title').innerText = activeWarning.title || 'Warning';
    document.getElementById('warning-message').innerText = activeWarning.message;

    const list = document.getElementById('warning-items');
    list.innerHTML = '';
    (activeWarning.items || []).forEach(item => {
        const li = document.createElement('li');
        const split = item.indexOf(':');
        if (split > 0) {
            li.append(item.slice(0, split + 1) + ' ');
            const value = document.createElement('strong');
            value.textContent = item.slice(split + 1).trim();
            li.append(value);
        } else {
            li.textContent = item;
        }
        list.append(li);
    });
    list.style.display = list.children.length ? '' : 'none';

    const footer = document.getElementById('warning-footer');
    footer.innerText = activeWarning.footer || '';
    footer.style.display = activeWarning.footer ? '' : 'none';

    popup.style.display = 'flex';
    document.getElementById('warning-ok').focus();
}

document.getElementById('warning-ok').addEventListener('click', () => {
    if (activeWarning && activeWarning.id && trackerSocket && trackerSocket.readyState === WebSocket.OPEN) {
        trackerSocket.send(JSON.stringify({type: 'WARNING_ACK', id: activeWarning.id}));
    }
    showNextWarning();
});
