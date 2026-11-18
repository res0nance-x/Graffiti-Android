/**
 * Reusable modal dialog utility — ES module (TypeScript).
 */
/** The lazily-created <dialog> element. */
let dialog = null;
function ensureDialog() {
    if (dialog)
        return;
    dialog = document.createElement('dialog');
    dialog.className = 'app-dialog';
    dialog.innerHTML = `
        <form method="dialog" class="app-dialog-form">
            <h2 class="app-dialog-title"></h2>
            <div class="app-dialog-body"></div>
            <div class="app-dialog-actions">
                <button type="button"   class="app-dialog-cancel">Cancel</button>
                <button type="submit"   class="app-dialog-confirm" value="confirm">OK</button>
            </div>
        </form>`;
    document.body.appendChild(dialog);
    dialog.addEventListener('click', (e) => {
        if (e.target === dialog)
            dialog.close('cancel');
    });
    dialog.querySelector('.app-dialog-cancel').addEventListener('click', () => {
        dialog.close('cancel');
    });
}
/**
 * Show a modal dialog built from an HTML <template>.
 * Returns a plain object of { inputName: value } or null if cancelled.
 */
export function showDialog({ title, templateId, confirmLabel = 'OK', init }) {
    ensureDialog();
    const tpl = document.getElementById(templateId);
    if (!tpl)
        throw new Error(`showDialog: <template id="${templateId}"> not found`);
    dialog.querySelector('.app-dialog-title').textContent = title;
    dialog.querySelector('.app-dialog-confirm').textContent = confirmLabel;
    const cancelBtn = dialog.querySelector('.app-dialog-cancel');
    if (cancelBtn)
        cancelBtn.style.display = '';
    const body = dialog.querySelector('.app-dialog-body');
    body.replaceChildren(tpl.content.cloneNode(true));
    if (init)
        init(body);
    dialog.returnValue = '';
    dialog.showModal();
    const first = body.querySelector('input, textarea, select');
    if (first)
        requestAnimationFrame(() => first.focus());
    return new Promise((resolve) => {
        function onClose() {
            dialog.removeEventListener('close', onClose);
            if (dialog.returnValue === 'confirm') {
                const form = dialog.querySelector('form');
                resolve(Object.fromEntries(new FormData(form)));
            }
            else {
                resolve(null);
            }
        }
        dialog.addEventListener('close', onClose);
    });
}
let progressDialog = null;
function ensureProgressDialog() {
    if (progressDialog)
        return progressDialog;
    progressDialog = document.createElement('dialog');
    progressDialog.className = 'app-dialog progress-modal';
    progressDialog.innerHTML = `
        <div class="app-dialog-form">
            <h2 class="app-dialog-title" id="progress-dialog-title">Creating Pack Archive</h2>
            <div class="app-dialog-body">
                <div class="dialog-field" style="text-align: center; padding: 0.5rem 0;">
                    <div id="progress-dialog-status" style="font-weight: 600; margin-bottom: 0.75rem;">Preparing...</div>
                    <div style="background: rgba(255,255,255,0.12); border-radius: 6px; overflow: hidden; height: 12px; margin-bottom: 0.75rem;">
                        <div id="progress-dialog-bar" style="width: 0%; height: 100%; background: var(--accent, #38bdf8); transition: width 0.15s ease;"></div>
                    </div>
                    <div id="progress-dialog-detail" style="font-size: 0.85rem; opacity: 0.7; word-break: break-all;"></div>
                </div>
            </div>
        </div>`;
    document.body.appendChild(progressDialog);
    // Prevent closing on escape while in progress
    progressDialog.addEventListener('cancel', (e) => e.preventDefault());
    return progressDialog;
}
export function showProgressModal(title, initialStatus = 'Preparing...') {
    const pd = ensureProgressDialog();
    const titleEl = pd.querySelector('#progress-dialog-title');
    const statusEl = pd.querySelector('#progress-dialog-status');
    const barEl = pd.querySelector('#progress-dialog-bar');
    const detailEl = pd.querySelector('#progress-dialog-detail');
    if (titleEl)
        titleEl.textContent = title;
    if (statusEl)
        statusEl.textContent = initialStatus;
    if (barEl)
        barEl.style.width = '0%';
    if (detailEl)
        detailEl.textContent = '';
    if (!pd.open) {
        pd.showModal();
    }
    return {
        update(statusText, percent, detailText) {
            if (statusEl)
                statusEl.textContent = statusText;
            if (barEl)
                barEl.style.width = `${Math.max(0, Math.min(100, percent))}%`;
            if (detailEl)
                detailEl.textContent = detailText ?? '';
        },
        close() {
            if (pd.open) {
                pd.close();
            }
        }
    };
}
//# sourceMappingURL=dialog.js.map