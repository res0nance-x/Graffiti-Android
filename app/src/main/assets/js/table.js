import { graffiti } from './graffiti-api.js';
/**
 * Builds (or rebuilds) the <tbody> of a data table.
 */
export function buildTable(table, items, { onRemove, onExport, onTogglePersist, onToggleIgnore, onAddToPeers, nodeKey } = {}) {
    const tbody = table.tBodies[0] ?? table.createTBody();
    tbody.replaceChildren();
    const hasStorageColumn = (table.tHead?.rows[0]?.cells.length ?? 2) >= 3;
    if (items.length === 0) {
        const row = tbody.insertRow();
        const cell = row.insertCell();
        cell.colSpan = hasStorageColumn ? 3 : 2;
        cell.style.opacity = '0.5';
        cell.style.fontSize = '0.85rem';
        cell.style.padding = '0.75rem 10px';
        cell.textContent = table.id === 'peers' ? 'No peers imported yet.' : 'No identities found.';
        return;
    }
    for (const item of items) {
        const row = tbody.insertRow();
        // User (Avatar + Name in figure/figcaption)
        const userCell = row.insertCell();
        const figure = document.createElement('figure');
        figure.className = 'user-profile-figure';
        const img = document.createElement('img');
        img.src = graffiti.avatarUrl(item.key);
        img.width = 32;
        img.height = 32;
        img.alt = `Avatar for ${item.name}`;
        const figcaption = document.createElement('figcaption');
        figcaption.textContent = item.name;
        if (!hasStorageColumn) {
            if (nodeKey && item.key === nodeKey) {
                const badge = document.createElement('span');
                badge.className = 'badge-relay';
                badge.style.background = '#2d2dff';
                badge.style.cursor = 'default';
                badge.textContent = 'Node';
                figcaption.appendChild(badge);
            }
            else if ('persistent' in item) {
                const isPersistent = item.persistent;
                const badge = document.createElement('span');
                badge.className = isPersistent ? 'badge-relay' : 'badge-session';
                badge.textContent = isPersistent ? 'Saved' : 'Session';
                figcaption.appendChild(badge);
                if (item.ignored) {
                    const ignoredBadge = document.createElement('span');
                    ignoredBadge.className = 'badge-session';
                    ignoredBadge.style.background = '#444';
                    ignoredBadge.style.color = '#bbb';
                    ignoredBadge.style.marginLeft = '4px';
                    ignoredBadge.textContent = 'Ignored';
                    figcaption.appendChild(ignoredBadge);
                }
            }
        }
        figure.append(img, figcaption);
        userCell.append(figure);
        // Storage Column (if table header defines 3 columns)
        if (hasStorageColumn) {
            const storageCell = row.insertCell();
            if (nodeKey && item.key === nodeKey) {
                const badge = document.createElement('span');
                badge.className = 'badge-relay';
                badge.style.background = '#2d2dff';
                badge.style.cursor = 'default';
                badge.textContent = 'Node';
                storageCell.appendChild(badge);
            }
            else if ('persistent' in item) {
                const isPersistent = item.persistent;
                const badge = document.createElement('span');
                badge.className = isPersistent ? 'badge-relay' : 'badge-session';
                badge.textContent = isPersistent ? 'Saved' : 'Session';
                storageCell.appendChild(badge);
                if (item.ignored) {
                    const ignoredBadge = document.createElement('span');
                    ignoredBadge.className = 'badge-session';
                    ignoredBadge.style.background = '#444';
                    ignoredBadge.style.color = '#bbb';
                    ignoredBadge.style.marginLeft = '4px';
                    ignoredBadge.textContent = 'Ignored';
                    storageCell.appendChild(ignoredBadge);
                }
            }
            else {
                storageCell.textContent = '—';
            }
        }
        // Actions
        const actionsCell = row.insertCell();
        if (onAddToPeers && 'persistent' in item) {
            const addPeerBtn = document.createElement('button');
            addPeerBtn.textContent = 'Add to Peers';
            addPeerBtn.style.marginRight = '8px';
            addPeerBtn.addEventListener('click', () => onAddToPeers(item));
            actionsCell.append(addPeerBtn);
        }
        if (onTogglePersist && 'persistent' in item && item.key !== nodeKey) {
            const persistBtn = document.createElement('button');
            const isPersistent = item.persistent;
            persistBtn.textContent = isPersistent ? 'Make Ephemeral' : 'Save';
            persistBtn.style.marginRight = '8px';
            persistBtn.addEventListener('click', () => onTogglePersist(item));
            actionsCell.append(persistBtn);
        }
        if (onToggleIgnore && 'persistent' in item) {
            const ignoreBtn = document.createElement('button');
            const isIgnored = Boolean(item.ignored);
            ignoreBtn.textContent = isIgnored ? 'Watch' : 'Ignore';
            ignoreBtn.title = isIgnored ? 'Watch identity (resume uploading to relays)' : 'Ignore identity (do not upload to relays)';
            ignoreBtn.style.marginRight = '8px';
            ignoreBtn.addEventListener('click', () => onToggleIgnore(item));
            actionsCell.append(ignoreBtn);
        }
        if (onRemove && item.key !== nodeKey) {
            const removeBtn = document.createElement('button');
            removeBtn.textContent = 'Remove';
            removeBtn.style.color = 'red';
            removeBtn.style.marginRight = '8px';
            removeBtn.addEventListener('click', () => onRemove(item));
            actionsCell.append(removeBtn);
        }
        if (onExport) {
            const exportBtn = document.createElement('button');
            exportBtn.textContent = 'Export';
            exportBtn.addEventListener('click', () => onExport(item));
            actionsCell.append(exportBtn);
        }
    }
}
//# sourceMappingURL=table.js.map