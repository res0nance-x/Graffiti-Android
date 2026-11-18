/**
 * Network section — discovery, active connections, server management.
 */
import { onSectionShow, onWsEvent, onWsOpen } from './app.js';
import { graffiti } from './graffiti-api.js';
/** Map of nodeKey (host:port) → ConnectionData */
const connections = new Map();
/** Map of relayKey → DiscoveredNode */
const discovered = new Map();
// ── Helpers ───────────────────────────────────────────────────────────────────
const nodeKey = (host, port) => `${host}:${port}`;
function avatarImg(key, size = 32) {
    const img = document.createElement('img');
    img.src = key ? graffiti.avatarUrl(key) : '';
    img.alt = '';
    img.width = size;
    img.height = size;
    img.style.borderRadius = '4px';
    return img;
}
// ── My Node card ────────────────────────────────────────────────────────
async function loadNodeIdentity() {
    try {
        const info = await graffiti.nodeInfo();
        updateNodeDisplay(info.peerKey, info.peerName, info.ips || [], info.portalDir);
        const portInput = document.getElementById('server-port-input');
        if (portInput) {
            portInput.value = String(info.defaultP2PPort ?? 0);
        }
    }
    catch (e) {
        console.error('[network] loadNodeIdentity:', e);
    }
}
function updateNodeDisplay(peerKey, peerName, ips, portalDir) {
    document.getElementById('node-avatar').src = graffiti.avatarUrl(peerKey);
    document.getElementById('node-name').textContent = peerName;
    document.getElementById('node-key').textContent = ips.join(', ');
    const portalDirEl = document.getElementById('node-portal-dir');
    if (portalDirEl && portalDir) {
        portalDirEl.textContent = portalDir;
    }
}
function relayInputs() {
    return [
        document.getElementById('relay-mode-off'),
        document.getElementById('relay-mode-on'),
    ];
}
function setRelayUi(relay) {
    const [off, on] = relayInputs();
    off.checked = !relay;
    on.checked = relay;
}
function setRelayStatus(text, isError = false) {
    const el = document.getElementById('relay-mode-status');
    el.textContent = text;
    el.hidden = false;
    el.style.color = isError ? '#ff6b6b' : '';
}
function clearRelayStatus() {
    const el = document.getElementById('relay-mode-status');
    el.hidden = true;
    el.textContent = '';
    el.style.color = '';
}
async function loadRelayMode() {
    try {
        const status = await graffiti.nodeRelayStatus();
        setRelayUi(status.relay);
        clearRelayStatus();
    }
    catch (e) {
        setRelayStatus(`Failed to load relay mode: ${e.message}`, true);
    }
}
function bridgeInputs() {
    return [
        document.getElementById('bridge-mode-off'),
        document.getElementById('bridge-mode-on'),
    ];
}
function setBridgeUi(bridge) {
    const [off, on] = bridgeInputs();
    if (!off || !on)
        return;
    off.checked = !bridge;
    on.checked = bridge;
}
function setBridgeStatus(text, isError = false) {
    const el = document.getElementById('bridge-mode-status');
    if (!el)
        return;
    el.textContent = text;
    el.hidden = false;
    el.style.color = isError ? '#ff6b6b' : '';
}
function clearBridgeStatus() {
    const el = document.getElementById('bridge-mode-status');
    if (!el)
        return;
    el.hidden = true;
    el.textContent = '';
    el.style.color = '';
}
async function loadBridgeMode() {
    try {
        const status = await graffiti.nodeBridgeStatus();
        setBridgeUi(status.bridge);
        clearBridgeStatus();
    }
    catch (e) {
        setBridgeStatus(`Failed to load bridge mode: ${e.message}`, true);
    }
}
// ── Connections list ─────────────────────────────────────────────────────────
const connBody = () => document.getElementById('tbl-connections-body');
const connEmpty = () => document.getElementById('connections-empty');
function updateNetworkNavIcon() {
    const icon = document.getElementById('nav-network-icon');
    if (!icon)
        return;
    const isConnected = connections.size > 0;
    icon.classList.toggle('nav-icon-connected', isConnected);
    icon.classList.toggle('nav-icon-disconnected', !isConnected);
}
export function updateNetTransferIndicator(transferring) {
    const indicator = document.getElementById('nav-net-indicator');
    if (indicator) {
        indicator.classList.toggle('is-active', transferring);
    }
}
function renderConnections() {
    const tbody = connBody();
    [...tbody.querySelectorAll('.net-item-card[data-node-key]')].forEach(r => r.remove());
    if (connections.size === 0) {
        connEmpty().hidden = false;
        updateNetworkNavIcon();
        return;
    }
    connEmpty().hidden = true;
    for (const [key, c] of connections) {
        tbody.appendChild(buildConnectionCard(key, c));
    }
    updateNetworkNavIcon();
}
function buildConnectionCard(key, c) {
    const div = document.createElement('div');
    div.className = 'net-item-card';
    div.dataset.nodeKey = key;
    const transferLabel = c.isSending && c.isReceiving ? ' (transferring ↑↓)' :
        c.isSending ? ' (sending ↑)' :
            c.isReceiving ? ' (receiving ↓)' : '';
    const dirLabel = (c.inbound ? '↓ Inbound' : '↑ Outbound') + transferLabel;
    const name = c.peerName ?? '…';
    const avatarKey = c.peerKey ?? null;
    const relayBadgeHtml = c.relay ? ` <span class="badge-relay">Relay</span>` : '';
    const bridgeBadgeHtml = c.bridge ? ` <span class="badge-bridge">Bridge</span>` : '';
    const bridgedBadgeHtml = (c.bridged || c.port < 0) ? ` <span class="badge-bridge">Bridged</span>` : '';
    div.innerHTML = `
        <div class="net-item-header">
            <div class="net-item-user">
                <span class="net-item-name">${escHtml(name)}${relayBadgeHtml}${bridgeBadgeHtml}${bridgedBadgeHtml}</span>
            </div>
        </div>
        <div class="net-item-details">
            <div class="net-detail-row">
                <span class="detail-label">Address:</span>
                <code>${escHtml(key)}</code>
            </div>
            <div class="net-detail-row">
                <span class="detail-label">Direction:</span>
                <span>${dirLabel}</span>
            </div>
        </div>
        <div class="net-item-actions">
            <button class="btn-portal" type="button">Portal</button>
            <button class="btn-leave" type="button">Leave</button>
        </div>`;
    div.querySelector('.net-item-user').prepend(avatarImg(avatarKey));
    div.querySelector('.btn-portal').addEventListener('click', async (e) => {
        const btn = e.currentTarget;
        btn.disabled = true;
        try {
            await graffiti.openPortal({ host: c.host, port: c.port, peerKey: c.peerKey });
        }
        catch (err) {
            alert(`Failed to open portal: ${err.message}`);
        }
        finally {
            btn.disabled = false;
        }
    });
    div.querySelector('.btn-leave').addEventListener('click', async () => {
        try {
            await graffiti.disconnect(c.host, c.port);
        }
        catch (e) {
            alert(`Disconnect failed: ${e.message}`);
        }
    });
    return div;
}
function upsertConnectionRow(key) {
    const c = connections.get(key);
    const existing = connBody().querySelector(`.net-item-card[data-node-key="${CSS.escape(key)}"]`);
    if (existing)
        existing.replaceWith(buildConnectionCard(key, c));
    else {
        connEmpty().hidden = true;
        connBody().appendChild(buildConnectionCard(key, c));
    }
}
function removeConnectionRow(key) {
    connBody().querySelector(`.net-item-card[data-node-key="${CSS.escape(key)}"]`)?.remove();
    if (connections.size === 0)
        connEmpty().hidden = false;
}
// ── Discovery list ───────────────────────────────────────────────────────────
const discBody = () => document.getElementById('tbl-discovered-body');
const discEmpty = () => document.getElementById('discover-empty');
const discStatus = () => document.getElementById('discover-status');
const btnDiscover = () => document.getElementById('btn-discover');
function buildDiscoverCard(d) {
    const div = document.createElement('div');
    div.className = 'net-item-card';
    div.dataset.discoverKey = d.key;
    const ipStr = d.ips.join(', ');
    const isConn = [...connections.values()].some(c => d.ips.includes(c.host) && c.port === d.port);
    const actionHtml = isConn
        ? `<span class="status-connected">● Connected</span>`
        : `<button class="btn-connect" type="button">Connect</button>`;
    const relayBadgeHtml = d.relay ? ` <span class="badge-relay">Relay</span>` : '';
    const bridgeBadgeHtml = d.bridge ? ` <span class="badge-bridge">Bridge</span>` : '';
    const bridgedBadgeHtml = d.port < 0 ? ` <span class="badge-bridge">Bridged</span>` : '';
    div.innerHTML = `
        <div class="net-item-header">
            <div class="net-item-user">
                <span class="net-item-name">${escHtml(d.name)}${relayBadgeHtml}${bridgeBadgeHtml}${bridgedBadgeHtml}</span>
            </div>
        </div>
        <div class="net-item-details">
            <div class="net-detail-row">
                <span class="detail-label">Addresses:</span>
                <code>${escHtml(ipStr)}</code>
            </div>
            <div class="net-detail-row">
                <span class="detail-label">Port:</span>
                <span>${d.port}</span>
            </div>
        </div>
        <div class="net-item-actions">
            ${actionHtml}
        </div>`;
    div.querySelector('.net-item-user').prepend(avatarImg(d.key));
    div.querySelector('.btn-connect')?.addEventListener('click', async (e) => {
        const btn = e.currentTarget;
        btn.disabled = true;
        btn.textContent = 'Connecting…';
        try {
            await graffiti.connect(d.ips[0], d.port);
            btn.textContent = 'Connected';
        }
        catch (err) {
            btn.disabled = false;
            btn.textContent = 'Connect';
            alert(`Connect failed: ${err.message}`);
        }
    });
    return div;
}
function renderDiscoverRow(d) {
    const existing = discBody().querySelector(`.net-item-card[data-discover-key="${CSS.escape(d.key)}"]`);
    if (existing)
        existing.replaceWith(buildDiscoverCard(d));
    else {
        discEmpty().hidden = true;
        discBody().appendChild(buildDiscoverCard(d));
    }
}
function refreshDiscoverConnectedState() {
    for (const [, d] of discovered)
        renderDiscoverRow(d);
}
// ── Server card ───────────────────────────────────────────────────────────────
function setServerStopped() {
    document.getElementById('server-stopped').hidden = false;
    document.getElementById('server-running').hidden = true;
}
function setServerRunning(port) {
    document.getElementById('server-stopped').hidden = true;
    document.getElementById('server-running').hidden = false;
    document.getElementById('server-port-display').textContent = String(port);
}
async function loadServerStatus() {
    try {
        const s = await graffiti.serverStatus();
        if (s.running)
            setServerRunning(s.port);
        else
            setServerStopped();
    }
    catch (_) {
        setServerStopped();
    }
}
// ── Initial load ──────────────────────────────────────────────────────────────
async function loadConnections() {
    try {
        const res = await graffiti.listConnections();
        connections.clear();
        for (const c of (res.connections ?? [])) {
            const key = nodeKey(c.host, c.port);
            connections.set(key, c);
        }
        renderConnections();
    }
    catch (e) {
        console.error('[network] loadConnections error:', e);
        updateNetworkNavIcon();
    }
}
// ── State Synchronization ─────────────────────────────────────────────────────
export function applyNodesState(nodes) {
    connections.clear();
    for (const c of nodes) {
        connections.set(nodeKey(c.host, c.port), c);
    }
    renderConnections();
    refreshDiscoverConnectedState();
}
export function applyRelayState(relay) {
    setRelayUi(relay);
    setRelayStatus(`Relay mode is ${relay ? 'ON' : 'OFF'}.`);
}
export function applyBridgeState(bridge) {
    setBridgeUi(bridge);
    setBridgeStatus(`Bridge mode is ${bridge ? 'ON' : 'OFF'}.`);
}
export function applyServerState(running, port) {
    if (running)
        setServerRunning(port);
    else
        setServerStopped();
}
// ── WebSocket event handlers ──────────────────────────────────────────────────
onWsEvent('discover_result', (msg) => {
    const d = {
        key: msg.key,
        ips: msg.ips,
        port: msg.port,
        name: msg.name ?? peerNameFromKey(msg.key),
        relay: Boolean(msg.relay),
        bridge: Boolean(msg.bridge),
    };
    discovered.set(msg.key, d);
    renderDiscoverRow(d);
});
onWsEvent('discover_done', () => {
    btnDiscover().disabled = false;
    btnDiscover().innerHTML = '<span class="material-symbols-outlined" style="font-size: 1.1rem;">search</span> Discover';
    const status = discStatus();
    status.textContent = `Found ${discovered.size} node${discovered.size !== 1 ? 's' : ''}.`;
    status.hidden = false;
});
async function triggerDiscovery(showAlertOnError = true) {
    const btn = btnDiscover();
    if (!btn)
        return;
    btn.disabled = true;
    btn.innerHTML = '<span class="material-symbols-outlined" style="font-size: 1.1rem;">hourglass_empty</span> Scanning…';
    discStatus().hidden = true;
    discovered.clear();
    const body = discBody();
    if (body) {
        [...body.querySelectorAll('.net-item-card[data-discover-key]')].forEach(r => r.remove());
    }
    discEmpty().hidden = false;
    try {
        const scanFull = document.getElementById('discover-scan-full')?.checked ?? false;
        await graffiti.discover(scanFull);
    }
    catch (e) {
        btn.disabled = false;
        btn.innerHTML = '<span class="material-symbols-outlined" style="font-size: 1.1rem;">search</span> Discover';
        if (showAlertOnError) {
            alert(`Discovery failed: ${e.message}`);
        }
        else {
            console.warn(`Initial discovery failed: ${e.message}`);
        }
    }
}
// ── Button wiring ─────────────────────────────────────────────────────────────
function wireButtons() {
    Promise.all([
        graffiti.getStore('graffiti:last-connect-host'),
        graffiti.getStore('graffiti:last-connect-port')
    ]).then(([savedHost, savedPort]) => {
        if (savedHost) {
            document.getElementById('manual-connect-host').value = savedHost;
        }
        if (savedPort) {
            document.getElementById('manual-connect-port').value = savedPort;
        }
    }).catch(e => console.error('Failed to load manual connection settings:', e));
    let activeConnectTarget = null;
    const performConnect = async () => {
        const host = document.getElementById('manual-connect-host').value.trim();
        const port = parseInt(document.getElementById('manual-connect-port').value, 10);
        const statusEl = document.getElementById('manual-connect-status');
        if (!host) {
            statusEl.textContent = 'Enter a host address.';
            statusEl.hidden = false;
            return;
        }
        if (!port || port < -65535 || port > 65535) {
            statusEl.textContent = 'Enter a valid port (1–65535, or negative virtual port).';
            statusEl.hidden = false;
            return;
        }
        const target = `${host}:${port}`;
        if (activeConnectTarget === target) {
            return;
        }
        activeConnectTarget = target;
        statusEl.textContent = `Connecting to ${target}…`;
        statusEl.hidden = false;
        try {
            await graffiti.connect(host, port);
            if (activeConnectTarget === target) {
                activeConnectTarget = null;
                statusEl.textContent = `Connected to ${target}.`;
                statusEl.hidden = false;
                await graffiti.setStore('graffiti:last-connect-host', host);
                await graffiti.setStore('graffiti:last-connect-port', String(port));
            }
        }
        catch (e) {
            if (activeConnectTarget === target) {
                activeConnectTarget = null;
                statusEl.textContent = `Failed: ${e.message}`;
                statusEl.hidden = false;
            }
        }
    };
    document.getElementById('btn-manual-connect').addEventListener('click', performConnect);
    const onConnectKeyDown = (e) => {
        if (e.key === 'Enter') {
            e.preventDefault();
            void performConnect();
        }
    };
    document.getElementById('manual-connect-host')?.addEventListener('keydown', onConnectKeyDown);
    document.getElementById('manual-connect-port')?.addEventListener('keydown', onConnectKeyDown);
    document.getElementById('btn-discover').addEventListener('click', () => {
        void triggerDiscovery(true);
    });
    document.getElementById('btn-open-my-portal')?.addEventListener('click', async (e) => {
        const btn = e.currentTarget;
        btn.disabled = true;
        try {
            await graffiti.openPortal({ self: true });
        }
        catch (err) {
            alert(`Failed to open portal: ${err.message}`);
        }
        finally {
            btn.disabled = false;
        }
    });
    document.getElementById('btn-server-start').addEventListener('click', async () => {
        const port = parseInt(document.getElementById('server-port-input').value, 10);
        if (isNaN(port) || port < 0 || port > 65535) {
            alert('Enter a valid port (0–65535).');
            return;
        }
        try {
            const res = await graffiti.startServer(port);
            const actualPort = res.port ?? port;
            setServerRunning(actualPort);
        }
        catch (e) {
            alert(`Failed to start server: ${e.message}`);
        }
    });
    document.getElementById('btn-server-stop').addEventListener('click', async () => {
        try {
            await graffiti.stopServer();
            setServerStopped();
        }
        catch (e) {
            alert(`Failed to stop server: ${e.message}`);
        }
    });
    const [relayOff, relayOn] = relayInputs();
    const setRelay = async (enabled) => {
        relayOff.disabled = true;
        relayOn.disabled = true;
        setRelayStatus('Updating relay mode...');
        try {
            const status = await graffiti.setNodeRelay(enabled);
            setRelayUi(status.relay);
            setRelayStatus(`Relay mode is ${status.relay ? 'ON' : 'OFF'}.`);
        }
        catch (e) {
            setRelayStatus(`Failed to update relay mode: ${e.message}`, true);
            await loadRelayMode();
        }
        finally {
            relayOff.disabled = false;
            relayOn.disabled = false;
        }
    };
    relayOff.addEventListener('change', () => {
        if (relayOff.checked)
            void setRelay(false);
    });
    relayOn.addEventListener('change', () => {
        if (relayOn.checked)
            void setRelay(true);
    });
    const [bridgeOff, bridgeOn] = bridgeInputs();
    if (bridgeOff && bridgeOn) {
        const setBridge = async (enabled) => {
            bridgeOff.disabled = true;
            bridgeOn.disabled = true;
            setBridgeStatus('Updating bridge mode...');
            try {
                const status = await graffiti.setNodeBridge(enabled);
                setBridgeUi(status.bridge);
                setBridgeStatus(`Bridge mode is ${status.bridge ? 'ON' : 'OFF'}.`);
            }
            catch (e) {
                setBridgeStatus(`Failed to update bridge mode: ${e.message}`, true);
                await loadBridgeMode();
            }
            finally {
                bridgeOff.disabled = false;
                bridgeOn.disabled = false;
            }
        };
        bridgeOff.addEventListener('change', () => {
            if (bridgeOff.checked)
                void setBridge(false);
        });
        bridgeOn.addEventListener('change', () => {
            if (bridgeOn.checked)
                void setBridge(true);
        });
    }
    const androidServiceField = document.getElementById('field-android-service');
    if (androidServiceField && window.Android?.stopService) {
        androidServiceField.hidden = false;
        document.getElementById('btn-service-stop')?.addEventListener('click', () => {
            window.Android?.stopService();
        });
    }
}
// ── Utility ───────────────────────────────────────────────────────────────────
function escHtml(str) {
    return String(str)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;')
        .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
function peerNameFromKey(key) {
    if (!key)
        return 'Unknown';
    return key.substring(0, 8) + '…';
}
// ── Section lifecycle ─────────────────────────────────────────────────────────
let buttonsWired = false;
let hasDiscoveredInitial = false;
onSectionShow('section-network', async () => {
    if (!buttonsWired) {
        wireButtons();
        buttonsWired = true;
    }
    const androidServiceField = document.getElementById('field-android-service');
    if (androidServiceField && window.Android?.stopService) {
        androidServiceField.hidden = false;
    }
    await Promise.all([loadNodeIdentity(), loadRelayMode(), loadBridgeMode(), loadConnections(), loadServerStatus()]);
});
// Run discover on app start (when WebSocket connection is open)
onWsOpen(() => {
    if (!hasDiscoveredInitial) {
        hasDiscoveredInitial = true;
        void triggerDiscovery(false);
    }
    void loadConnections();
});
// Initial icon status and connection fetch on module load
updateNetworkNavIcon();
void loadConnections();
async function handleForegroundNetwork() {
    await Promise.all([loadConnections(), loadServerStatus(), loadRelayMode(), loadBridgeMode()]);
}
document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
        void handleForegroundNetwork();
    }
});
window.addEventListener('focus', () => {
    void handleForegroundNetwork();
});
window.addEventListener('pageshow', () => {
    void handleForegroundNetwork();
});
//# sourceMappingURL=network.js.map