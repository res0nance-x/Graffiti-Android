/**
 * Graffiti API Client — ES module (TypeScript)
 *
 * Import: import { graffiti } from './graffiti-api.js';
 *
 * Thin async wrapper around the GraffitiAPI HTTP endpoints served by the
 * embedded NanoHTTPD server. All methods return Promises. On an application-
 * level error (ok: false) the Promise is rejected with the server's error
 * message. On a network/HTTP error the Promise is rejected with a generic
 * message.
 */
// ── Internal helpers ──────────────────────────────────────────────────────────
async function parseJson(res) {
    let json;
    let text = '';
    try {
        text = await res.clone().text();
        json = await res.json();
    }
    catch (_) {
        throw new Error(`HTTP ${res.status}: response was not JSON. Content: "${text}"`);
    }
    if (!json.ok)
        throw new Error(json.error || `Request failed (HTTP ${res.status})`);
    return json;
}
async function get(path, params = {}) {
    const url = new URL(path, window.location.origin);
    for (const [k, v] of Object.entries(params))
        url.searchParams.set(k, v);
    return parseJson(await fetch(url.toString()));
}
async function putJson(path, body) {
    const url = new URL(path, window.location.origin);
    const res = await fetch(url.toString(), {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
    });
    return parseJson(res);
}
async function postJson(path, body) {
    const url = new URL(path, window.location.origin);
    const res = await fetch(url.toString(), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
    });
    return parseJson(res);
}
async function download(path, params = {}, filename) {
    const url = new URL(path, window.location.origin);
    for (const [k, v] of Object.entries(params))
        url.searchParams.set(k, v);
    if (window.Android && window.Android.download) {
        window.Android.download(url.toString(), filename);
        return;
    }
    const a = document.createElement('a');
    a.href = url.toString();
    if (filename)
        a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
}
// ── Public API ────────────────────────────────────────────────────────────────
export const graffiti = {
    // ── State ─────────────────────────────────────────────────────────────
    getState() {
        return get('/api/state');
    },
    // ── Identity ──────────────────────────────────────────────────────────
    listIdentities() {
        return get('/api/identities');
    },
    createIdentity(seed) {
        return putJson('/api/identity/create', { seed });
    },
    removeIdentity(key) {
        return get('/api/identity/remove', { key });
    },
    setIdentityPersistence(key, persistent) {
        return get('/api/identity/persist', { key, persistent: String(persistent) });
    },
    setIdentityIgnored(key, ignored) {
        const params = { key };
        if (typeof ignored === 'boolean')
            params.ignored = String(ignored);
        return get('/api/identity/ignore', params);
    },
    identityToPeer(key) {
        return get('/api/identity/to-peer', { key });
    },
    getWhitelist() {
        return get('/api/whitelist');
    },
    setWhitelist(enabled) {
        return get('/api/whitelist', { enabled: String(enabled) });
    },
    // ── Peer ──────────────────────────────────────────────────────────────
    listPeers() {
        return get('/api/peers');
    },
    importPeer(file) {
        const fd = new FormData();
        fd.append('file', file);
        // TODO
        return {};
        // return postMultipart('/api/peer/import', fd);
    },
    removePeer(key) {
        return get('/api/peer/remove', { key });
    },
    exportPeer(key) {
        return download('/api/peer/export', { key });
    },
    // ── Message ───────────────────────────────────────────────────────────
    listMessages() {
        return get('/api/messages');
    },
    getMessageMeta(key) {
        return putJson('/api/message/meta', { key });
    },
    getMessagesMeta(keys) {
        if (keys.length === 0)
            return Promise.resolve({ ok: true, metas: [] });
        return putJson('/api/messages/meta', { keys });
    },
    refreshMessages() {
        return get('/api/messages/refresh');
    },
    contentUrl(key) {
        return `/api/content?key=${encodeURIComponent(key)}`;
    },
    removeMessage(key) {
        return get('/api/message/remove', { key });
    },
    setMessageIgnored(key, ignored) {
        const params = { key };
        if (typeof ignored === 'boolean')
            params.ignored = String(ignored);
        return get('/api/message/ignore', params);
    },
    forwardMessage(key, identityKey, peerKey, async = true) {
        const params = { key };
        if (identityKey)
            params.identityKey = identityKey;
        if (peerKey)
            params.peerKey = peerKey;
        if (async)
            params.async = 'true';
        return get('/api/message/forward', params);
    },
    async sendText(identityKey, peerKey, text) {
        const url = new URL(`/api/message/send/text?identityKey=${encodeURIComponent(identityKey)}&peerKey=${encodeURIComponent(peerKey)}`, window.location.origin);
        const res = await fetch(url.toString(), {
            method: 'PUT',
            headers: { 'Content-Type': 'text/plain' },
            body: text
        });
        return parseJson(res);
    },
    async sendFile(identityKey, peerKey, file, async = true, onProgress) {
        const url = new URL(`/api/message/send/file?identityKey=${encodeURIComponent(identityKey)}&peerKey=${encodeURIComponent(peerKey)}&file=${encodeURIComponent(file.name)}${async ? '&async=true' : ''}`, window.location.origin);
        return new Promise((resolve, reject) => {
            const xhr = new XMLHttpRequest();
            xhr.open('PUT', url.toString(), true);
            xhr.setRequestHeader('Content-Type', file.type || 'application/octet-stream');
            xhr.setRequestHeader('file', encodeURIComponent(file.name));
            if (xhr.upload && onProgress) {
                xhr.upload.onprogress = (evt) => {
                    if (evt.lengthComputable && evt.total > 0) {
                        const percent = Math.round((evt.loaded * 100) / evt.total);
                        onProgress(percent, evt.loaded, evt.total);
                    }
                };
            }
            xhr.onload = () => {
                if (xhr.status >= 200 && xhr.status < 300) {
                    try {
                        resolve(JSON.parse(xhr.responseText));
                    }
                    catch (e) {
                        reject(e);
                    }
                }
                else {
                    reject(new Error(xhr.statusText || `Upload failed with status ${xhr.status}`));
                }
            };
            xhr.onerror = () => reject(new Error('Network error during upload'));
            xhr.send(file);
        });
    },
    async downloadMessageContent(key) {
        return get('/api/message/download', { key });
    },
    async createPackBegin(identityKey, peerKey, packName) {
        const url = new URL(`/api/pack/create/begin?identityKey=${encodeURIComponent(identityKey)}&peerKey=${encodeURIComponent(peerKey)}&name=${encodeURIComponent(packName)}`, window.location.origin);
        const res = await fetch(url.toString(), {
            method: 'PUT'
        });
        return parseJson(res);
    },
    async uploadPackFile(sessionId, relativePath, file) {
        const url = new URL(`/api/pack/create/file?sessionId=${encodeURIComponent(sessionId)}&filePath=${encodeURIComponent(relativePath)}`, window.location.origin);
        const res = await fetch(url.toString(), {
            method: 'PUT',
            headers: {
                'Content-Type': 'application/octet-stream',
                'file-path': encodeURIComponent(relativePath)
            },
            body: file
        });
        return parseJson(res);
    },
    async createPackFinish(sessionId, async = true) {
        const url = new URL(`/api/pack/create/finish?sessionId=${encodeURIComponent(sessionId)}${async ? '&async=true' : ''}`, window.location.origin);
        const res = await fetch(url.toString(), {
            method: 'POST'
        });
        return parseJson(res);
    },
    async createPackCancel(sessionId) {
        const url = new URL(`/api/pack/create/cancel?sessionId=${encodeURIComponent(sessionId)}`, window.location.origin);
        const res = await fetch(url.toString(), {
            method: 'POST'
        });
        return parseJson(res);
    },
    refresh() {
        return get('/api/messages/refresh');
    },
    // ── Background Commands ───────────────────────────────────────────────
    listActiveCommands() {
        return get('/api/commands/active');
    },
    getCommandStatus(commandId) {
        return get('/api/command/status', { commandId });
    },
    cancelCommand(commandId) {
        return get('/api/command/cancel', { commandId });
    },
    // ── Avatar ────────────────────────────────────────────────────────────
    avatarUrl(key) {
        return `/api/avatar?key=${encodeURIComponent(key)}`;
    },
    // ── Network ───────────────────────────────────────────────────────────
    listConnections() {
        return get('/api/connections');
    },
    serverStatus() {
        return get('/api/server/status');
    },
    startServer(port) {
        return get('/api/server/start', { port: String(port) });
    },
    stopServer() {
        return get('/api/server/stop');
    },
    discover(scan) {
        return get('/api/discover', scan ? { scan: 'true' } : {});
    },
    connect(host, port) {
        return get('/api/client/connect', { node: host, port: String(port) });
    },
    disconnect(host, port) {
        return get('/api/client/disconnect', { node: host, port: String(port) });
    },
    // ── Settings ──────────────────────────────────────────────────────────
    getStorage() {
        return get('/api/storage');
    },
    purgeStorage(key, type) {
        return get('/api/storage/purge', { key, type });
    },
    getQuota() {
        return get('/api/storage/quota');
    },
    setQuota(quotaBytes) {
        return get('/api/storage/quota', { quota: String(quotaBytes) });
    },
    // ── Portable Messages Export & Import ──────────────────────────────────────────
    exportMessages(params) {
        const q = {};
        if (params.key)
            q.key = params.key;
        if (params.identity)
            q.identity = params.identity;
        if (params.keys && params.keys.length > 0)
            q.keys = JSON.stringify(params.keys);
        return download('/api/messages/export', q);
    },
    async importMessages(file) {
        const url = new URL('/api/messages/import', window.location.origin);
        const res = await fetch(url.toString(), {
            method: 'POST',
            headers: { 'Content-Type': 'application/octet-stream' },
            body: file
        });
        return parseJson(res);
    },
    // ── Node identity ─────────────────────────────────────────────────────
    nodeInfo() {
        return get('/api/node/info');
    },
    nodeRelayStatus() {
        return get('/api/node/relay');
    },
    setNodeRelay(enabled) {
        let e = "";
        if (enabled) {
            e = "true";
        }
        else {
            e = "false";
        }
        return get('/api/node/relay', { enabled: e });
    },
    nodeBridgeStatus() {
        return get('/api/node/bridge');
    },
    setNodeBridge(enabled) {
        const e = enabled ? 'true' : 'false';
        return get('/api/node/bridge', { enabled: e });
    },
    async getStore(key) {
        try {
            const res = await get('/api/store', { key });
            return res.value || '';
        }
        catch (_) {
            return '';
        }
    },
    async getAllStore() {
        try {
            const res = await get('/api/store');
            return res.settings || {};
        }
        catch (_) {
            return {};
        }
    },
    async setStore(key, value) {
        const url = new URL(`/api/store?key=${encodeURIComponent(key)}`, window.location.origin);
        const res = await fetch(url.toString(), {
            method: 'PUT',
            body: value
        });
        return parseJson(res);
    },
    async deleteStore(key) {
        const url = new URL(`/api/store?key=${encodeURIComponent(key)}`, window.location.origin);
        const res = await fetch(url.toString(), {
            method: 'DELETE'
        });
        return parseJson(res);
    },
    // ── Pack & Portal Viewing ───────────────────────────────────────────────
    openPack(params) {
        return putJson('/api/pack/open', params);
    },
    openPortal(params = {}) {
        return putJson('/api/portal/open', params);
    },
    closePack(sessionId) {
        return get('/api/pack/close', { sessionId });
    },
    // ── Version ─────────────────────────────────────────────────────────────
    async getVersion() {
        try {
            const res = await get('/api/version');
            return res.version || 'Unknown';
        }
        catch (_) {
            return 'Unknown';
        }
    },
    // ── External URL ────────────────────────────────────────────────────────
    openExternalUrl(url) {
        if (window.Android && typeof window.Android.openUrl === 'function') {
            try {
                window.Android.openUrl(url);
                return Promise.resolve({ ok: true });
            }
            catch (_e) {
                // fallback to service API
            }
        }
        return postJson('/api/open-url', { url });
    },
};
export async function openPackFile(params) {
    try {
        await graffiti.openPack(params);
    }
    catch (err) {
        const errMsg = err?.message || String(err);
        if (errMsg.includes('PASSWORD_REQUIRED') || errMsg.includes('INVALID_PASSWORD')) {
            const pass = prompt(`Enter password for ${params.name || 'pack'}:`);
            if (pass) {
                await openPackFile({ ...params, password: pass });
            }
        }
        else {
            alert(`Failed to open pack: ${errMsg}`);
        }
    }
}
//# sourceMappingURL=graffiti-api.js.map