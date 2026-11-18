import { graffiti } from './graffiti-api.js';
import { buildTable } from './table.js';
import { showDialog } from './dialog.js';
import { onSectionShow } from './app.js';
const identitiesTable = document.getElementById('identities');
const peersTable = document.getElementById('peers');
export async function refreshIdentities() {
    if (!identitiesTable)
        return;
    const [{ identities }, node] = await Promise.all([
        graffiti.listIdentities(),
        graffiti.nodeInfo(),
    ]);
    buildTable(identitiesTable, identities, {
        nodeKey: node.peerKey,
        onAddToPeers: async (item) => {
            try {
                await graffiti.identityToPeer(item.key);
                await refreshPeers();
            }
            catch (err) {
                alert(`Add to peers failed: ${err.message}`);
            }
        },
        onTogglePersist: async (item) => {
            try {
                await graffiti.setIdentityPersistence(item.key, !item.persistent);
                await refreshIdentities();
            }
            catch (err) {
                alert(`Persistence update failed: ${err.message}`);
            }
        },
        onToggleIgnore: async (item) => {
            try {
                await graffiti.setIdentityIgnored(item.key, !item.ignored);
                await refreshIdentities();
            }
            catch (err) {
                alert(`Ignore update failed: ${err.message}`);
            }
        },
        onRemove: async (item) => {
            const iden = item;
            if (iden.key === node.peerKey) {
                alert("Cannot remove the server node identity.");
                return;
            }
            if (!confirm(`Remove identity "${iden.name}"? This cannot be undone.`))
                return;
            try {
                await graffiti.removeIdentity(iden.key);
                await refreshIdentities();
            }
            catch (err) {
                alert(`Remove failed: ${err.message}`);
            }
        },
    });
}
export async function refreshPeers() {
    if (!peersTable)
        return;
    const { peers } = await graffiti.listPeers();
    buildTable(peersTable, peers, {
        onRemove: async (item) => {
            if (!confirm(`Remove peer "${item.name}"?`))
                return;
            try {
                await graffiti.removePeer(item.key);
                await refreshPeers();
            }
            catch (err) {
                alert(`Remove failed: ${err.message}`);
            }
        },
        onExport: (item) => graffiti.exportPeer(item.key),
    });
}
export async function refresh() {
    await Promise.all([refreshIdentities(), refreshPeers(), refreshWhitelist()]);
}
// ── Create Identity ───────────────────────────────────────────────────────────
document.getElementById('create-identity')?.addEventListener('click', async () => {
    const data = await showDialog({
        title: 'Create Identity',
        templateId: 'tpl-identity-create',
        confirmLabel: 'Create',
        init: (body) => {
            const seedInput = body.querySelector('#dlg-iden-seed');
            const toggleBtn = body.querySelector('#toggle-seed-visibility');
            const toggleIcon = toggleBtn?.querySelector('.material-symbols-outlined');
            if (seedInput && toggleBtn && toggleIcon) {
                toggleBtn.addEventListener('click', () => {
                    const isPassword = seedInput.type === 'password';
                    seedInput.type = isPassword ? 'text' : 'password';
                    toggleIcon.textContent = isPassword ? 'visibility_off' : 'visibility';
                    const label = isPassword ? 'Hide seed phrase' : 'Show seed phrase';
                    toggleBtn.title = label;
                    toggleBtn.setAttribute('aria-label', label);
                    seedInput.focus();
                });
            }
        },
    });
    if (!data)
        return;
    const rawSeed = data.seed || '';
    const splitByComma = data.splitByComma === 'on';
    const saveOnDevice = data.saveOnDevice === 'on';
    const seeds = splitByComma
        ? rawSeed.split(',').map((s) => s.trim()).filter((s) => s.length > 0)
        : [rawSeed.trim()].filter(s => s.length > 0);
    if (seeds.length === 0) {
        alert('A seed phrase is required.');
        return;
    }
    const errors = [];
    for (const seed of seeds) {
        try {
            const res = await graffiti.createIdentity(seed);
            if (saveOnDevice && res?.key) {
                await graffiti.setIdentityPersistence(res.key, true);
            }
        }
        catch (err) {
            errors.push(`"${seed}": ${err.message}`);
        }
    }
    await refreshIdentities();
    if (errors.length > 0) {
        alert(`Some identities failed to create:\n${errors.join('\n')}`);
    }
});
// ── Import Peer ───────────────────────────────────────────────────────────────
document.getElementById('import-peer')?.addEventListener('click', () => {
    document.getElementById('import-peer-file')?.click();
});
document.getElementById('import-peer-file')?.addEventListener('change', async (e) => {
    const input = e.target;
    const file = input.files?.[0];
    if (!file)
        return;
    try {
        await graffiti.importPeer(file);
        await refreshPeers();
    }
    catch (err) {
        alert(`Import failed: ${err.message}`);
    }
    finally {
        input.value = '';
    }
});
// ── Whitelist Toggle ─────────────────────────────────────────────────────────
const whitelistToggle = document.getElementById('whitelist-toggle');
export async function refreshWhitelist() {
    if (!whitelistToggle)
        return;
    try {
        const res = await graffiti.getWhitelist();
        whitelistToggle.checked = res.enabled;
    }
    catch (_) { }
}
whitelistToggle?.addEventListener('change', async () => {
    try {
        await graffiti.setWhitelist(whitelistToggle.checked);
    }
    catch (err) {
        alert(`Failed to update whitelist: ${err.message}`);
        await refreshWhitelist();
    }
});
export async function applyContactsState(identities, peers) {
    if (identitiesTable) {
        try {
            const node = await graffiti.nodeInfo();
            buildTable(identitiesTable, identities, {
                nodeKey: node.peerKey,
                onAddToPeers: async (item) => {
                    try {
                        await graffiti.identityToPeer(item.key);
                    }
                    catch (err) {
                        alert(`Add to peers failed: ${err.message}`);
                    }
                },
                onTogglePersist: async (item) => {
                    try {
                        await graffiti.setIdentityPersistence(item.key, !item.persistent);
                    }
                    catch (err) {
                        alert(`Persistence update failed: ${err.message}`);
                    }
                },
                onToggleIgnore: async (item) => {
                    try {
                        await graffiti.setIdentityIgnored(item.key, !item.ignored);
                    }
                    catch (err) {
                        alert(`Ignore update failed: ${err.message}`);
                    }
                },
                onRemove: async (item) => {
                    const iden = item;
                    if (iden.key === node.peerKey) {
                        alert("Cannot remove the server node identity.");
                        return;
                    }
                    if (!confirm(`Remove identity "${iden.name}"? This cannot be undone.`))
                        return;
                    try {
                        await graffiti.removeIdentity(iden.key);
                    }
                    catch (err) {
                        alert(`Remove failed: ${err.message}`);
                    }
                },
            });
        }
        catch (e) {
            console.warn('Failed to render identities table:', e);
        }
    }
    if (peersTable) {
        buildTable(peersTable, peers, {
            onRemove: async (item) => {
                if (!confirm(`Remove peer "${item.name}"?`))
                    return;
                try {
                    await graffiti.removePeer(item.key);
                }
                catch (err) {
                    alert(`Remove failed: ${err.message}`);
                }
            },
            onExport: (item) => graffiti.exportPeer(item.key),
        });
    }
}
export function applyWhitelistState(enabled) {
    if (whitelistToggle) {
        whitelistToggle.checked = enabled;
    }
}
onSectionShow('section-identity', refresh);
//# sourceMappingURL=identity.js.map