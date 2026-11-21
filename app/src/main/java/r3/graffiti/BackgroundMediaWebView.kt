package r3.graffiti

import android.content.Context
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout

class BackgroundMediaWebView(context: Context) : WebView(context) {
	@Volatile
	var keepAliveInBackground: Boolean = false
		private set

	private var actualWindowVisibility: Int = View.VISIBLE
	private var isWindowVisibilitySuppressed: Boolean = false

	var onBackgroundMediaStopped: (() -> Unit)? = null

	override fun onWindowVisibilityChanged(visibility: Int) {
		actualWindowVisibility = visibility
		if (keepAliveInBackground && visibility != View.VISIBLE) {
			isWindowVisibilitySuppressed = true
			super.onWindowVisibilityChanged(View.VISIBLE)
		} else {
			isWindowVisibilitySuppressed = false
			super.onWindowVisibilityChanged(visibility)
		}
	}

	fun setKeepAliveInBackground(active: Boolean) {
		if (keepAliveInBackground == active) return
		keepAliveInBackground = active
		if (!active) {
			if (isWindowVisibilitySuppressed && actualWindowVisibility != View.VISIBLE) {
				isWindowVisibilitySuppressed = false
				super.onWindowVisibilityChanged(actualWindowVisibility)
			}
			onBackgroundMediaStopped?.invoke()
		}
	}

	companion object {
		fun buildMediaTrackerJs(bridgeName: String): String {
			return """
				(function() {
					if (window.__graffitiMediaTrackerInstalled) {
						if (typeof window.__graffitiReportMediaState === 'function') {
							window.__graffitiReportMediaState();
						}
						return;
					}
					window.__graffitiMediaTrackerInstalled = true;

					const bridgeName = '$bridgeName';
					let lastReported = null;
					let stopTimer = null;

					function hasPlayingMediaInDoc(doc) {
						if (!doc) return false;
						const media = doc.querySelectorAll('video, audio');
						for (let i = 0; i < media.length; i++) {
							const el = media[i];
							if (!el.paused && !el.ended) return true;
						}
						const frames = doc.querySelectorAll('iframe');
						for (let i = 0; i < frames.length; i++) {
							try {
								if (frames[i].contentDocument && hasPlayingMediaInDoc(frames[i].contentDocument)) {
									return true;
								}
							} catch (_) {}
						}
						return false;
					}

					function callBridge(playing) {
						const bridge = window[bridgeName];
						if (bridge && typeof bridge.setMediaPlaying === 'function') {
							bridge.setMediaPlaying(playing);
						}
					}

					function reportState() {
						const playing = hasPlayingMediaInDoc(document);
						if (playing) {
							if (stopTimer !== null) {
								clearTimeout(stopTimer);
								stopTimer = null;
							}
							if (lastReported !== true) {
								lastReported = true;
								callBridge(true);
							}
						} else if (lastReported === true && stopTimer === null) {
							stopTimer = setTimeout(function() {
								stopTimer = null;
								const stillPlaying = hasPlayingMediaInDoc(document);
								if (!stillPlaying) {
									lastReported = false;
									callBridge(false);
								}
							}, 1500);
						}
					}

					function hookFrame(frame) {
						if (!frame || frame.__graffitiFrameHooked) return;
						frame.__graffitiFrameHooked = true;
						frame.addEventListener('load', function() {
							try {
								if (frame.contentDocument) {
									attachToDocument(frame.contentDocument);
								}
							} catch (_) {}
							reportState();
						});
						try {
							if (frame.contentDocument) {
								attachToDocument(frame.contentDocument);
							}
						} catch (_) {}
					}

					function attachToDocument(doc) {
						if (!doc || doc.__graffitiMediaTracked) return;
						doc.__graffitiMediaTracked = true;

						const events = ['play', 'playing', 'pause', 'ended', 'emptied', 'abort'];
						for (let i = 0; i < events.length; i++) {
							doc.addEventListener(events[i], reportState, true);
						}

						const frames = doc.querySelectorAll('iframe');
						for (let i = 0; i < frames.length; i++) {
							hookFrame(frames[i]);
						}

						const target = doc.documentElement || doc;
						if (target && typeof MutationObserver !== 'undefined') {
							const observer = new MutationObserver(function(mutations) {
								let hadRemoved = false;
								for (let i = 0; i < mutations.length; i++) {
									const m = mutations[i];
									if (m.removedNodes && m.removedNodes.length > 0) {
										hadRemoved = true;
									}
									if (m.addedNodes) {
										for (let j = 0; j < m.addedNodes.length; j++) {
											const node = m.addedNodes[j];
											if (node && node.nodeType === 1) {
												if (node.tagName === 'IFRAME') {
													hookFrame(node);
												} else if (node.querySelectorAll) {
													const nested = node.querySelectorAll('iframe');
													for (let k = 0; k < nested.length; k++) {
														hookFrame(nested[k]);
													}
												}
											}
										}
									}
								}
								if (hadRemoved && lastReported === true) {
									reportState();
								}
							});
							observer.observe(target, { childList: true, subtree: true });
						}
					}

					window.__graffitiReportMediaState = reportState;
					attachToDocument(document);
					reportState();
				})();
			""".trimIndent()
		}

		val CAPTURE_AND_RESUME_AFTER_FULLSCREEN_JS: String = """
			(function() {
				const playingVideos = [];
				function collect(doc) {
					if (!doc) return;
					const vids = doc.querySelectorAll('video');
					for (let i = 0; i < vids.length; i++) {
						if (!vids[i].paused && !vids[i].ended) {
							playingVideos.push(vids[i]);
						}
					}
					const frames = doc.querySelectorAll('iframe');
					for (let i = 0; i < frames.length; i++) {
						try {
							if (frames[i].contentDocument) collect(frames[i].contentDocument);
						} catch (_) {}
					}
				}
				collect(document);
				window.__graffitiResumeVideos = playingVideos;
				function tryResume() {
					const list = window.__graffitiResumeVideos;
					if (!list || !list.length) return;
					for (let i = 0; i < list.length; i++) {
						const v = list[i];
						if (v && v.isConnected && v.paused && !v.ended) {
							v.play().catch(function() {});
						}
					}
				}
				setTimeout(tryResume, 150);
				setTimeout(function() {
					tryResume();
					window.__graffitiResumeVideos = null;
				}, 400);
			})();
		""".trimIndent()
	}
}

class BackgroundMediaFrameLayout(
	context: Context,
	private val keepAliveProvider: () -> Boolean
) : FrameLayout(context) {
	override fun dispatchWindowVisibilityChanged(visibility: Int) {
		if (keepAliveProvider() && visibility != View.VISIBLE) {
			super.dispatchWindowVisibilityChanged(View.VISIBLE)
		} else {
			super.dispatchWindowVisibilityChanged(visibility)
		}
	}
}
