import { onSectionShow, showSection } from './app.js';
import { graffiti } from './graffiti-api.js';
// ── Help Documentation Navigation & Dynamic Loading ─────────────────────────
const showHelpBtn = document.getElementById('btn-show-help');
const closeHelpBtn = document.getElementById('btn-close-help');
const helpContentEl = document.getElementById('help-content');
export function parseHelpHtmlSections(htmlText) {
    const parser = new DOMParser();
    const doc = parser.parseFromString(htmlText, 'text/html');
    const body = doc.body;
    if (!body || !body.children.length) {
        return '';
    }
    // Ensure all links open safely in a new window/tab
    doc.querySelectorAll('a[href]').forEach(a => {
        a.setAttribute('target', '_blank');
        a.setAttribute('rel', 'noopener');
    });
    // Ensure images have the msg-img class for consistent styling
    doc.querySelectorAll('img').forEach(img => {
        img.classList.add('msg-img');
    });
    const cards = [];
    let currentCardNodes = [];
    for (let i = 0; i < body.children.length; i++) {
        const child = body.children[i];
        const isHeader = /^H[1-3]$/i.test(child.tagName);
        if (isHeader && currentCardNodes.length > 0) {
            cards.push(`<section class="net-card">\n${currentCardNodes.join('\n')}\n</section>`);
            currentCardNodes = [];
        }
        currentCardNodes.push(child.outerHTML);
    }
    if (currentCardNodes.length > 0) {
        cards.push(`<section class="net-card">\n${currentCardNodes.join('\n')}\n</section>`);
    }
    return cards.join('\n\n');
}
export async function loadHelpDocumentation() {
    if (!helpContentEl)
        return;
    try {
        const resp = await fetch('help.html?t=' + Date.now());
        if (!resp.ok) {
            throw new Error(`HTTP ${resp.status}`);
        }
        const text = await resp.text();
        helpContentEl.innerHTML = parseHelpHtmlSections(text);
    }
    catch (err) {
        console.error('Failed to load help.html:', err);
        helpContentEl.innerHTML = `<section class="net-card"><p style="color:var(--danger, #f44336); font-size:0.9rem;">Failed to load help documentation.</p></section>`;
    }
}
if (showHelpBtn) {
    showHelpBtn.addEventListener('click', () => {
        loadHelpDocumentation();
        showSection('section-help');
    });
}
if (closeHelpBtn) {
    closeHelpBtn.addEventListener('click', () => {
        showSection('section-settings');
    });
}
onSectionShow('section-help', () => {
    loadHelpDocumentation();
});
// ── Appearance Settings ───────────────────────────────────────────────────────
const themeSel = document.getElementById('settings-theme');
const fontSizeSel = document.getElementById('settings-font-size');
const fontFamilySel = document.getElementById('settings-font-family');
const messagesEl = document.getElementById('messages');
function applyTheme(theme) {
    if (!theme)
        theme = 'dark-sage';
    if (theme === 'dark')
        theme = 'dark-sage';
    if (theme === 'light')
        theme = 'light-purple';
    document.documentElement.setAttribute('data-theme', theme);
    if (themeSel)
        themeSel.value = theme;
    const metaTheme = document.querySelector('meta[name="theme-color"]');
    if (metaTheme) {
        metaTheme.setAttribute('content', theme.startsWith('light') ? '#ffffff' : '#000000');
    }
}
function applyMessageAppearance(size, family) {
    document.documentElement.style.setProperty('--app-font-size', size);
    if (messagesEl) {
        messagesEl.style.setProperty('--message-font-family', family);
    }
    if (fontSizeSel)
        fontSizeSel.value = size;
    if (fontFamilySel)
        fontFamilySel.value = family;
}
async function loadAppearanceSettings() {
    try {
        const storedTheme = await graffiti.getStore('graffiti:theme');
        applyTheme(storedTheme || 'dark-sage');
    }
    catch {
        applyTheme('dark-sage');
    }
    const size = await graffiti.getStore('graffiti:message-font-size') || '100%';
    const family = await graffiti.getStore('graffiti:message-font-family') || 'inherit';
    applyMessageAppearance(size, family);
}
if (themeSel) {
    themeSel.addEventListener('change', async () => {
        const selectedTheme = themeSel.value;
        applyTheme(selectedTheme);
        await graffiti.setStore('graffiti:theme', selectedTheme);
    });
}
if (fontSizeSel) {
    fontSizeSel.addEventListener('change', async () => {
        await graffiti.setStore('graffiti:message-font-size', fontSizeSel.value);
        const family = fontFamilySel?.value || 'inherit';
        applyMessageAppearance(fontSizeSel.value, family);
    });
}
if (fontFamilySel) {
    fontFamilySel.addEventListener('change', async () => {
        await graffiti.setStore('graffiti:message-font-family', fontFamilySel.value);
        const size = fontSizeSel?.value || '100%';
        applyMessageAppearance(size, fontFamilySel.value);
    });
}
// Apply settings initially
void loadAppearanceSettings();
// ── Storage Management ────────────────────────────────────────────────────────
function formatSize(bytes) {
    if (bytes == null)
        return '';
    if (bytes < 1024)
        return `${bytes} B`;
    if (bytes < 1024 * 1024)
        return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
function escHtml(str) {
    return String(str)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;')
        .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
async function loadStorageInfo() {
    try {
        const res = await graffiti.getStorage();
        const overallEl = document.getElementById('storage-total-overall');
        if (overallEl)
            overallEl.textContent = formatSize(res.overall);
        const quotaInput = document.getElementById('settings-storage-quota');
        if (quotaInput) {
            try {
                const quotaRes = await graffiti.getQuota();
                quotaInput.value = quotaRes.quota > 0 ? String(Math.round(quotaRes.quota / (1024 * 1024))) : '';
            }
            catch (e) {
                console.error('Failed to load quota:', e);
            }
        }
        const listEl = document.getElementById('storage-list');
        if (!listEl)
            return;
        listEl.replaceChildren();
        if (res.storage.length === 0) {
            const empty = document.createElement('div');
            empty.className = 'empty-row';
            empty.textContent = 'No storage statistics available.';
            listEl.appendChild(empty);
            return;
        }
        for (const item of res.storage) {
            const card = document.createElement('div');
            card.className = 'net-item-card';
            card.innerHTML = `
            <div class="net-item-header">
               <div class="net-item-user">
                  <img class="msg-avatar" src="${graffiti.avatarUrl(item.key)}" width="32" height="32" alt="">
                  <span class="net-item-name">${escHtml(item.name)}</span>
               </div>
            </div>
            <div class="net-item-details">
               <div class="net-detail-row">
                  <span class="detail-label">Storage Used:</span>
                  <span>${formatSize(item.size)}</span>
               </div>
            </div>
            <div class="net-item-actions">
               <button class="btn-export-storage" type="button" title="Export all messages for ${escHtml(item.name)} as a portable file">Export</button>
               <button class="btn-purge-half" type="button">Purge 1/2</button>
               <button class="btn-purge-all" style="color: red; border-color: red;" type="button">Purge All</button>
            </div>
         `;
            card.querySelector('.btn-export-storage')?.addEventListener('click', async () => {
                try {
                    await graffiti.exportMessages({ identity: item.key });
                }
                catch (e) {
                    alert(`Export failed: ${e.message}`);
                }
            });
            card.querySelector('.btn-purge-half').addEventListener('click', async () => {
                if (!confirm(`Purge oldest half of messages for "${item.name}"?`))
                    return;
                try {
                    await graffiti.purgeStorage(item.key, 'half');
                    await loadStorageInfo();
                }
                catch (e) {
                    alert(`Purge failed: ${e.message}`);
                }
            });
            card.querySelector('.btn-purge-all').addEventListener('click', async () => {
                if (!confirm(`Purge ALL messages for "${item.name}"? This cannot be undone.`))
                    return;
                try {
                    await graffiti.purgeStorage(item.key, 'all');
                    await loadStorageInfo();
                }
                catch (e) {
                    alert(`Purge failed: ${e.message}`);
                }
            });
            listEl.appendChild(card);
        }
    }
    catch (e) {
        console.error('Failed to load storage info:', e);
    }
}
// ── About / Version ───────────────────────────────────────────────────────────
const appVersionEl = document.getElementById('settings-app-version');
async function loadAppVersion() {
    if (!appVersionEl)
        return;
    try {
        const ver = await graffiti.getVersion();
        appVersionEl.textContent = ver;
    }
    catch {
        appVersionEl.textContent = 'Unknown';
    }
}
void loadAppVersion();
// Reload storage info and version when entering Settings tab
onSectionShow('section-settings', async () => {
    updateBatterySettingsUi();
    await Promise.all([loadStorageInfo(), loadAppVersion(), loadAndroidPackViewerSettings()]);
});
async function loadAndroidPackViewerSettings() {
    const card = document.getElementById('settings-card-android-pack-viewer');
    const toggle = document.getElementById('settings-pack-allow-external-links');
    if (!card || !toggle || !window.Android)
        return;
    card.hidden = false;
    try {
        const stored = await graffiti.getStore('graffiti:pack-allow-external-links');
        toggle.checked = stored === 'true';
    }
    catch (e) {
        console.warn('Failed to load pack external links setting:', e);
    }
}
const packAllowExternalLinksToggle = document.getElementById('settings-pack-allow-external-links');
if (packAllowExternalLinksToggle) {
    packAllowExternalLinksToggle.addEventListener('change', async () => {
        const enabled = packAllowExternalLinksToggle.checked;
        try {
            await graffiti.setStore('graffiti:pack-allow-external-links', enabled ? 'true' : 'false');
        }
        catch (e) {
            packAllowExternalLinksToggle.checked = !enabled;
            alert(`Failed to save setting: ${e.message}`);
        }
    });
}
void loadAndroidPackViewerSettings();
function updateBatterySettingsUi() {
    const card = document.getElementById('settings-card-android-battery');
    if (!card || !window.Android?.openBatterySettings)
        return;
    card.hidden = false;
    const isOptimized = window.Android?.isBatteryOptimized?.() ?? true;
    const statusEl = document.getElementById('settings-battery-status');
    const btn = document.getElementById('btn-settings-battery');
    if (statusEl) {
        statusEl.textContent = isOptimized ? 'Status: Optimized (May sleep in background)' : 'Status: Unrestricted (Optimal)';
        statusEl.style.color = isOptimized ? 'var(--warning, #ff9800)' : 'var(--success, #4caf50)';
    }
    if (btn) {
        btn.textContent = isOptimized ? 'Set Unrestricted Battery' : 'Open Battery Settings';
    }
}
window.updateBatterySettingsUi = updateBatterySettingsUi;
let batteryPollTimer = null;
function pollBatterySettings() {
    if (batteryPollTimer !== null)
        clearInterval(batteryPollTimer);
    let count = 0;
    const initialOptimized = window.Android?.isBatteryOptimized?.() ?? true;
    batteryPollTimer = window.setInterval(() => {
        count++;
        updateBatterySettingsUi();
        const currentOptimized = window.Android?.isBatteryOptimized?.() ?? true;
        if (currentOptimized !== initialOptimized || count > 30) {
            if (batteryPollTimer !== null) {
                clearInterval(batteryPollTimer);
                batteryPollTimer = null;
            }
        }
    }, 500);
}
document.getElementById('btn-settings-battery')?.addEventListener('click', () => {
    window.Android?.openBatterySettings();
    pollBatterySettings();
});
window.addEventListener('focus', () => {
    updateBatterySettingsUi();
});
document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
        updateBatterySettingsUi();
    }
});
const quotaInput = document.getElementById('settings-storage-quota');
if (quotaInput) {
    quotaInput.addEventListener('change', async () => {
        const mb = parseFloat(quotaInput.value);
        const bytes = isNaN(mb) || mb <= 0 ? 0 : Math.round(mb * 1024 * 1024);
        try {
            await graffiti.setQuota(bytes);
        }
        catch (e) {
            alert(`Failed to save quota: ${e.message}`);
        }
    });
}
// ── Portable Messages Import in Storage Settings ──────────────────────────────
const storageImportBtn = document.getElementById('btn-storage-import');
const storageImportInput = document.getElementById('input-storage-import');
if (storageImportBtn && storageImportInput) {
    storageImportBtn.addEventListener('click', () => storageImportInput.click());
    storageImportInput.addEventListener('change', async () => {
        const file = storageImportInput.files?.[0];
        if (!file)
            return;
        try {
            const res = await graffiti.importMessages(file);
            if (res.ok) {
                alert(`Successfully imported ${res.imported} message(s) (${res.skipped} skipped, ${res.peers} peer(s) imported).`);
                await loadStorageInfo();
            }
            else {
                alert(`Import failed: ${res.error || 'Unknown error'}`);
            }
        }
        catch (e) {
            alert(`Import failed: ${e.message}`);
        }
        finally {
            storageImportInput.value = '';
        }
    });
}
//# sourceMappingURL=storage.js.map