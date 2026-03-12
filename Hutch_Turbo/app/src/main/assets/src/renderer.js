// Helper to get Android Bridge dynamically
const getBridge = () => typeof TurboBridge !== 'undefined' ? TurboBridge : null;

// Replacement for Electron's ipcRenderer shim
const ipcRenderer = {
    send: (channel, data) => {
        const bridge = getBridge();
        if (bridge) {
            try {
                if (channel === 'start-turbo') bridge.startTurbo(JSON.stringify(data));
                else if (channel === 'stop-turbo') bridge.stopTurbo();
                else if (channel === 'get-adapter') bridge.getAdapter();
                else if (channel === 'window-control' && data === 'close') bridge.closeApp();
            } catch (e) {
                console.error(`BRIDGE_ERROR: Failed to call ${channel}`, e);
            }
        }
    },
    invoke: (channel) => {
        const bridge = getBridge();
        if (bridge) {
            try {
                if (channel === 'is-service-running') return bridge.isServiceRunning();
            } catch (e) {
                console.error(`BRIDGE_INVOKE_ERROR: ${channel}`, e);
            }
        }
        return false;
    },
    on: (channel, callback) => {
        window.addEventListener(`android-${channel}`, (event) => {
            callback(event, event.detail);
        });
    }
};

// Global callback for Android Native code
window.sendToJS = (channel, data) => {
    try {
        const event = new CustomEvent(`android-${channel}`, { detail: data });
        window.dispatchEvent(event);
    } catch (e) {
        console.error("EVENT_ERROR: Failed to dispatch " + channel, e);
    }
};

// UI Elements
const safeGet = (id) => document.getElementById(id);
const dlSpeedEl = safeGet('dl-speed');
const ulSpeedEl = safeGet('ul-speed');
const peakDlEl = safeGet('peak-dl');
const peakUlEl = safeGet('peak-ul');
const dlBarEl = safeGet('dl-bar');
const ulBarEl = safeGet('ul-bar');
const updateCounterEl = safeGet('update-counter');
const totalDlEl = safeGet('total-dl');
const totalUlEl = safeGet('total-ul');
const logOutputEl = safeGet('log-output');
const currentTimeEl = safeGet('current-time');
const adapterNameEl = safeGet('adapter-name');
const startBtn = safeGet('start-turbo');
const stopBtn = safeGet('stop-turbo');
const sidebar = safeGet('sidebar');
const collapseBtn = safeGet('sidebar-collapse-btn');
const sidebarCloseBtn = safeGet('sidebar-close-btn');
const mobileMenuBtn = safeGet('mobile-menu-toggle');
const minimizeBtn = safeGet('minimize');
const closeBtn = safeGet('close');
const clearLogBtn = safeGet('clear-log-btn');
const settingsBtn = safeGet('settings-btn');
const settingsModal = safeGet('settings-modal');
const closeModalBtns = document.querySelectorAll('.close-modal');
const turboIndicator = safeGet('turbo-active');
const refreshIndicator = safeGet('refresh-running');

// State
let peakDl = 0;
let peakUl = 0;
let totalDl = 0;
let totalUl = 0;
let updateCount = 0;
let isRunning = false;

console.log("Hutch Turbo: Renderer initializing...");

// Speed Graph Initialization - Robust against CDN failure
const graphCanvas = safeGet('speed-graph');
let speedChart = null;

if (graphCanvas && typeof Chart !== 'undefined') {
    try {
        const ctx = graphCanvas.getContext('2d');
        speedChart = new Chart(ctx, {
            type: 'line',
            data: {
                labels: Array(30).fill(''),
                datasets: [
                    {
                        label: 'Download',
                        borderColor: '#39ff14',
                        backgroundColor: 'rgba(57, 255, 20, 0.1)',
                        data: Array(30).fill(0),
                        borderWidth: 2,
                        pointRadius: 0,
                        fill: true,
                        tension: 0.4
                    },
                    {
                        label: 'Upload',
                        borderColor: '#ff8c00',
                        backgroundColor: 'rgba(255, 140, 0, 0.1)',
                        data: Array(30).fill(0),
                        borderWidth: 2,
                        pointRadius: 0,
                        fill: true,
                        tension: 0.4
                    }
                ]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                scales: {
                    x: { display: false },
                    y: {
                        beginAtZero: true,
                        grid: { color: 'rgba(255, 255, 255, 0.05)' },
                        ticks: { color: '#6c7a8e', font: { size: 10 } }
                    }
                },
                plugins: {
                    legend: { display: false }
                },
                animation: false
            }
        });
    } catch (e) {
        console.warn("Chart initialization failed:", e);
    }
}

function updateChart(value, type) {
    if (!speedChart) return;
    try {
        const index = type === 'dl' ? 0 : 1;
        speedChart.data.datasets[index].data.push(value);
        speedChart.data.datasets[index].data.shift();
        speedChart.update('none');
    } catch (e) {
        console.error("Chart update error:", e);
    }
}

// ── Sync State with Background Service ──
const syncServiceState = () => {
    try {
        const running = ipcRenderer.invoke('is-service-running');
        if (running) {
            isRunning = true;
            if (startBtn) startBtn.disabled = true;
            if (stopBtn) stopBtn.disabled = false;
            if (turboIndicator) turboIndicator.classList.add('on');
            if (refreshIndicator) refreshIndicator.classList.add('on');
            addLogEntry("Turbo session detected in background. Syncing UI...", 'system');
        }
    } catch (e) {
        console.error("SYNC_ERROR:", e);
    }
};

// Initial state sync
syncServiceState();

// Update Clock
setInterval(() => {
    if (currentTimeEl) {
        const now = new Date();
        currentTimeEl.textContent = now.toLocaleTimeString();
    }
}, 1000);

// Window Controls
if (minimizeBtn) minimizeBtn.addEventListener('click', () => ipcRenderer.send('window-control', 'minimize'));
if (closeBtn) closeBtn.addEventListener('click', () => ipcRenderer.send('window-control', 'close'));

// Adapter Detection
function refreshAdapter() {
    ipcRenderer.send('get-adapter');
}

ipcRenderer.on('adapter-found', (event, name) => {
    if (adapterNameEl) {
        const span = adapterNameEl.querySelector('span');
        if (span) {
            span.textContent = name || 'No connection';
        }
    }
});

// Initial Detection
refreshAdapter();
setInterval(refreshAdapter, 5000);

// Turbo Logic
if (startBtn) {
    startBtn.addEventListener('click', () => {
        try {
            const intervalInput = safeGet('interval-input');
            const interval = intervalInput ? parseInt(intervalInput.value) : 1000;

            isRunning = true;
            startBtn.disabled = true;
            if (stopBtn) stopBtn.disabled = false;
            if (turboIndicator) turboIndicator.classList.add('on');
            if (refreshIndicator) refreshIndicator.classList.add('on');

            ipcRenderer.send('start-turbo', { interval: interval });
            addLogEntry(`Turbo activation sequence initiated...`, 'system');
        } catch (e) {
            console.error("START_ERROR:", e);
        }
    });
}

if (stopBtn) {
    stopBtn.addEventListener('click', () => {
        ipcRenderer.send('stop-turbo');
        handleStop();
    });
}

ipcRenderer.on('turbo-stopped', () => {
    handleStop();
});

function handleStop() {
    isRunning = false;
    if (startBtn) startBtn.disabled = false;
    if (stopBtn) stopBtn.disabled = true;
    if (turboIndicator) turboIndicator.classList.remove('on');
    if (refreshIndicator) refreshIndicator.classList.remove('on');
    addLogEntry('Turbo sequence terminated.', 'system');
}

// IPC Handlers
ipcRenderer.on('script-output', (event, data) => {
    addLogEntry(data);
    parseOutput(data);
});

// Output Parsing
function parseOutput(data) {
    try {
        // Extract Adapter
        const adapterMatch = data.match(/Adapter\s*:\s*(.+)/);
        if (adapterMatch && adapterNameEl) {
            const span = adapterNameEl.querySelector('span');
            if (span) span.textContent = adapterMatch[1].trim();
        }

        // Extract Speed Metrics
        const dlMatch = data.match(/Download Speed\s*:\s*[\d.]+\s*KB\/s\s*\(([\d.]+)\s*MB\/s\)\s*Peak:\s*([\d.]+)\s*KB\/s/);
        const ulMatch = data.match(/Upload Speed\s*:\s*[\d.]+\s*KB\/s\s*\(([\d.]+)\s*MB\/s\)\s*Peak:\s*([\d.]+)\s*KB\/s/);

        if (dlMatch && dlSpeedEl) {
            const currentDl = parseFloat(dlMatch[1]);
            const peakDlKB = parseFloat(dlMatch[2]);
            dlSpeedEl.textContent = currentDl.toFixed(1);
            peakDl = Math.max(peakDl, currentDl);
            if (peakDlEl) peakDlEl.textContent = (peakDlKB / 1024).toFixed(1);
            if (dlBarEl) {
                const dlPercent = Math.min((currentDl / 20) * 100, 100);
                dlBarEl.style.width = `${dlPercent}%`;
            }
            updateChart(currentDl, 'dl');
        }

        if (ulMatch && ulSpeedEl) {
            const currentUl = parseFloat(ulMatch[1]);
            const peakUlKB = parseFloat(ulMatch[2]);
            ulSpeedEl.textContent = currentUl.toFixed(1);
            peakUl = Math.max(peakUl, currentUl);
            if (peakUlEl) peakUlEl.textContent = (peakUlKB / 1024).toFixed(1);
            if (ulBarEl) {
                const ulPercent = Math.min((currentUl / 10) * 100, 100);
                ulBarEl.style.width = `${ulPercent}%`;
            }
            updateChart(currentUl, 'ul');
        }

        // Totals
        const totalDlMatch = data.match(/Total Download\s*:\s*([\d.]+)\s*KB/);
        const totalUlMatch = data.match(/Total Upload\s*:\s*([\d.]+)\s*KB/);
        const updatesMatch = data.match(/Updates\s*:\s*(\d+)/);

        if (totalDlMatch && totalDlEl) {
            totalDl = parseFloat(totalDlMatch[1]) / 1024;
            totalDlEl.textContent = `${totalDl.toFixed(2)} MB`;
        }
        if (totalUlMatch && totalUlEl) {
            totalUl = parseFloat(totalUlMatch[1]) / 1024;
            totalUlEl.textContent = `${totalUl.toFixed(2)} MB`;
        }
        if (updatesMatch && updateCounterEl) {
            updateCount = parseInt(updatesMatch[1]);
            updateCounterEl.textContent = updateCount;
        }
    } catch (e) {
        console.error("PARSE_ERROR:", e);
    }
}

// Utils
function addLogEntry(text, type = '') {
    if (!logOutputEl) return;
    try {
        const entry = document.createElement('div');
        entry.className = `log-entry ${type}`;
        const ts = new Date().toLocaleTimeString();
        entry.innerHTML = `<span class="ts">[${ts}]</span> <span class="src">HUTCH:</span> ${text}`;
        logOutputEl.appendChild(entry);
        logOutputEl.scrollTop = logOutputEl.scrollHeight;
    } catch (e) {
        console.error("LOG_ERROR:", e);
    }
}

if (clearLogBtn) {
    clearLogBtn.addEventListener('click', () => {
        if (logOutputEl) logOutputEl.innerHTML = '';
    });
}

// Settings Modal
if (settingsBtn) {
    settingsBtn.addEventListener('click', () => {
        if (settingsModal) settingsModal.classList.add('active');
    });
}

closeModalBtns.forEach(btn => {
    btn.addEventListener('click', () => {
        if (settingsModal) settingsModal.classList.remove('active');
    });
});

// Theme Toggle
document.querySelectorAll('.theme-btn:not(.modal-tab-btn)').forEach(btn => {
    btn.addEventListener('click', () => {
        const theme = btn.dataset.theme;
        document.documentElement.setAttribute('data-theme', theme);
        document.documentElement.setAttribute('data-bs-theme', theme);
        document.querySelectorAll('.theme-btn:not(.modal-tab-btn)').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
    });
});

// Modal Tab Logic
document.querySelectorAll('.modal-tab-btn').forEach(btn => {
    btn.addEventListener('click', () => {
        const targetView = btn.dataset.modalView;
        document.querySelectorAll('.modal-tab-btn').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
        document.querySelectorAll('.modal-tab-content').forEach(view => {
            view.classList.toggle('d-none', view.id !== targetView);
        });
    });
});

// Sidebar Controls
if (collapseBtn) {
    collapseBtn.addEventListener('click', () => {
        if (!sidebar) return;
        sidebar.classList.toggle('collapsed');
        const icon = collapseBtn.querySelector('i');
        if (icon) {
            icon.className = sidebar.classList.contains('collapsed') ?
                'bi bi-chevron-double-right' : 'bi bi-chevron-double-left';
        }
    });
}

if (sidebarCloseBtn) {
    sidebarCloseBtn.addEventListener('click', () => {
        if (sidebar) sidebar.classList.remove('mobile-active');
    });
}

// Navigation Auto-Close
document.querySelectorAll('.nav-item').forEach(item => {
    item.addEventListener('click', () => {
        if (window.innerWidth <= 768 && sidebar) {
            sidebar.classList.remove('mobile-active');
        }
    });
});

