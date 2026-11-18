/**
 * App shell — section routing, navigation, and WebSocket event bus.
 */
const sectionHandlers = new Map();
const wsEventHandlers = new Map();
const wsOpenHandlers = [];
let isWsOpen = false;
/**
 * Register a callback that runs when the WebSocket connection is opened.
 * If the connection is already open, the callback is executed immediately.
 */
export function onWsOpen(fn) {
    if (isWsOpen) {
        Promise.resolve(fn()).catch(e => console.error('Error in WS open handler:', e));
    }
    else {
        wsOpenHandlers.push(fn);
    }
}
/**
 * Register a callback that runs every time the given section is shown.
 */
export function onSectionShow(id, fn) {
    if (!sectionHandlers.has(id))
        sectionHandlers.set(id, []);
    sectionHandlers.get(id).push(fn);
}
/**
 * Register a callback for a server-pushed WebSocket event name.
 * Multiple handlers can be registered for the same event.
 */
export function onWsEvent(event, fn) {
    if (!wsEventHandlers.has(event))
        wsEventHandlers.set(event, []);
    wsEventHandlers.get(event).push(fn);
}
let currentActiveSectionId = '';
const sectionScrollPositions = new Map();
/**
 * Show the section with the given id and hide all others.
 * Scroll positions are preserved across section switches, except for
 * section-messages which defaults to scrolled to the bottom.
 */
export function showSection(id) {
    // 1. Save scroll position of currently leaving section (except messages view)
    if (currentActiveSectionId && currentActiveSectionId !== 'section-messages') {
        sectionScrollPositions.set(currentActiveSectionId, window.scrollY);
    }
    // 2. Toggle active classes
    for (const section of document.querySelectorAll('.app-section')) {
        section.classList.toggle('is-active', section.id === id);
    }
    for (const link of document.querySelectorAll('.app-nav-link')) {
        const active = link.dataset.section === id;
        link.classList.toggle('is-active', active);
        link.setAttribute('aria-current', active ? 'page' : 'false');
    }
    document.body.classList.toggle('in-fullscreen-viewer', id === 'section-view-content');
    currentActiveSectionId = id;
    // 3. Restore scroll position for entering section (or scroll to bottom for messages)
    if (id === 'section-messages') {
        window.scrollTo(0, Math.max(document.documentElement.scrollHeight, document.body.scrollHeight));
    }
    else {
        const savedScrollY = sectionScrollPositions.get(id) ?? 0;
        window.scrollTo(0, savedScrollY);
    }
    // 4. Run section handlers
    for (const fn of (sectionHandlers.get(id) ?? []))
        fn();
    // 5. Re-apply scroll position after layout updates
    requestAnimationFrame(() => {
        if (id === 'section-messages') {
            window.scrollTo(0, Math.max(document.documentElement.scrollHeight, document.body.scrollHeight));
            requestAnimationFrame(() => {
                window.scrollTo(0, Math.max(document.documentElement.scrollHeight, document.body.scrollHeight));
            });
        }
        else {
            const savedScrollY = sectionScrollPositions.get(id) ?? 0;
            window.scrollTo(0, savedScrollY);
        }
    });
}
/**
 * Attach click handlers to the nav links already in the HTML.
 */
export function initNav(defaultSection = 'section-network') {
    for (const link of document.querySelectorAll('.app-nav-link')) {
        link.addEventListener('click', (e) => {
            e.preventDefault();
            showSection(link.dataset.section ?? '');
        });
    }
    showSection(defaultSection);
}
// ── WebSocket client ──────────────────────────────────────────────────────────
let activeSocket = null;
let reconnectTimer = null;
export function initWebSocketClient() {
    if (reconnectTimer !== null) {
        clearTimeout(reconnectTimer);
        reconnectTimer = null;
    }
    if (activeSocket && (activeSocket.readyState === WebSocket.OPEN || activeSocket.readyState === WebSocket.CONNECTING)) {
        return;
    }
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const socket = new WebSocket(`${proto}//${location.host}/api/notify`);
    activeSocket = socket;
    socket.addEventListener('open', () => {
        console.log('WebSocket connection opened');
        isWsOpen = true;
        for (const handler of wsOpenHandlers) {
            Promise.resolve(handler()).catch(e => console.error('Error in WS open handler:', e));
        }
    });
    socket.addEventListener('message', (event) => {
        let msg;
        try {
            msg = JSON.parse(event.data);
        }
        catch (e) {
            console.warn('WebSocket: non-JSON message ignored', event.data);
            return;
        }
        const handlers = wsEventHandlers.get(msg.event);
        if (handlers) {
            for (const handler of handlers) {
                Promise.resolve(handler(msg)).catch(e => console.error(`WS handler [${String(msg.event)}] error:`, e));
            }
        }
        else {
            console.warn('WebSocket: unknown event type:', msg.event);
        }
    });
    socket.addEventListener('error', (event) => {
        console.error('WebSocket error:', event);
    });
    socket.addEventListener('close', () => {
        console.warn('WebSocket closed — reconnecting in 3 s');
        isWsOpen = false;
        if (activeSocket === socket)
            activeSocket = null;
        if (reconnectTimer === null) {
            reconnectTimer = window.setTimeout(() => {
                reconnectTimer = null;
                initWebSocketClient();
            }, 3000);
        }
    });
}
function checkWebSocketOnForeground() {
    if (!activeSocket || activeSocket.readyState === WebSocket.CLOSED || activeSocket.readyState === WebSocket.CLOSING) {
        console.log('Foreground event: reconnecting WebSocket');
        initWebSocketClient();
    }
}
document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
        checkWebSocketOnForeground();
    }
});
window.addEventListener('focus', () => {
    checkWebSocketOnForeground();
});
window.addEventListener('pageshow', () => {
    checkWebSocketOnForeground();
});
initWebSocketClient();
//# sourceMappingURL=app.js.map