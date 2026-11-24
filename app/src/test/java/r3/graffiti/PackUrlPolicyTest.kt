package r3.graffiti

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackUrlPolicyTest {
	@Test
	fun allowsMatchingLoopbackPackUrls() {
		assertTrue(PackUrlPolicy.isInternalPackUrl("http://localhost:12345/", 12345))
		assertTrue(PackUrlPolicy.isInternalPackUrl("http://localhost:12345/index.html", 12345))
		assertTrue(PackUrlPolicy.isInternalPackUrl("http://localhost:12345/sub/video.mp4?t=1#frag", 12345))
		assertTrue(PackUrlPolicy.isInternalPackUrl("http://127.0.0.1:12345/a.html", 12345))
		assertTrue(PackUrlPolicy.isInternalPackUrl("http://[::1]:12345/a.html", 12345))
		assertTrue(PackUrlPolicy.isInternalPackUrl("HTTP://LOCALHOST:12345/page.html", 12345))
	}

	@Test
	fun blocksCrossPortAndNonLoopbackHttpUrls() {
		assertFalse(PackUrlPolicy.isInternalPackUrl("http://localhost:9999/api/state", 12345))
		assertFalse(PackUrlPolicy.isInternalPackUrl("http://localhost/foo", 12345))
		assertFalse(PackUrlPolicy.isInternalPackUrl("http://localhost:12345/", 0))
		assertFalse(PackUrlPolicy.isInternalPackUrl("http://localhost:12345/", -1))
		assertFalse(PackUrlPolicy.isInternalPackUrl("https://localhost:12345/", 12345))
		assertFalse(PackUrlPolicy.isInternalPackUrl("http://evil@localhost:12345/", 12345))
		assertFalse(PackUrlPolicy.isInternalPackUrl("http://user:pass@127.0.0.1:12345/", 12345))
		assertFalse(PackUrlPolicy.isInternalPackUrl("https://example.com", 12345))
		assertFalse(PackUrlPolicy.isInternalPackUrl("http://example.com:12345/", 12345))
	}

	@Test
	fun handlesSpecialSchemesForInternalPackUrls() {
		assertTrue(PackUrlPolicy.isInternalPackUrl("about:blank", 12345))
		assertTrue(PackUrlPolicy.isInternalPackUrl("ABOUT:BLANK", 12345))
		assertFalse(PackUrlPolicy.isInternalPackUrl("about:srcdoc", 12345))
		assertFalse(PackUrlPolicy.isInternalPackUrl("about:config", 12345))

		assertTrue(PackUrlPolicy.isInternalPackUrl("data:image/png;base64,abc", 12345))
		assertTrue(PackUrlPolicy.isInternalPackUrl("blob:http://localhost:12345/uuid", 12345))
		assertTrue(PackUrlPolicy.isInternalPackUrl("javascript:void(0)", 12345))

		assertFalse(PackUrlPolicy.isInternalPackUrl("file:///etc/passwd", 12345))
		assertFalse(PackUrlPolicy.isInternalPackUrl("content://com.example/foo", 12345))
		assertFalse(PackUrlPolicy.isInternalPackUrl("mailto:a@b.com", 12345))
		assertFalse(PackUrlPolicy.isInternalPackUrl("tel:123", 12345))
		assertFalse(PackUrlPolicy.isInternalPackUrl("intent://scan/#Intent;end", 12345))
		assertFalse(PackUrlPolicy.isInternalPackUrl("", 12345))
		assertFalse(PackUrlPolicy.isInternalPackUrl("   ", 12345))
		assertFalse(PackUrlPolicy.isInternalPackUrl(null, 12345))
	}

	@Test
	fun detectsPackViewerUrlsOnlyOnMatchingLoopbackAndPath() {
		assertTrue(PackUrlPolicy.isPackViewerUrl("http://localhost:12345/__pack_viewer__", 12345))
		assertTrue(PackUrlPolicy.isPackViewerUrl("http://127.0.0.1:12345/__pack_viewer__?q=1#top", 12345))
		assertTrue(PackUrlPolicy.isPackViewerUrl("http://[::1]:12345/__pack_viewer__", 12345))

		assertFalse(PackUrlPolicy.isPackViewerUrl("http://localhost:12345/", 12345))
		assertFalse(PackUrlPolicy.isPackViewerUrl("http://localhost:12345/index.html", 12345))
		assertFalse(PackUrlPolicy.isPackViewerUrl("http://localhost:12345/__pack_viewer__/sub", 12345))
		assertFalse(PackUrlPolicy.isPackViewerUrl("http://localhost:9999/__pack_viewer__", 12345))
		assertFalse(PackUrlPolicy.isPackViewerUrl("http://example.com:12345/__pack_viewer__", 12345))
		assertFalse(PackUrlPolicy.isPackViewerUrl("about:blank", 12345))
		assertFalse(PackUrlPolicy.isPackViewerUrl("data:text/html,hi", 12345))
		assertFalse(PackUrlPolicy.isPackViewerUrl(null, 12345))
	}

	@Test
	fun openableExternalUrlAllowsOnlySafeSchemes() {
		assertTrue(PackUrlPolicy.isOpenableExternalUrl("https://example.com/page"))
		assertTrue(PackUrlPolicy.isOpenableExternalUrl("http://example.com"))
		assertTrue(PackUrlPolicy.isOpenableExternalUrl("mailto:test@example.com"))
		assertTrue(PackUrlPolicy.isOpenableExternalUrl("tel:+15551234567"))

		assertFalse(PackUrlPolicy.isOpenableExternalUrl("file:///sdcard/a"))
		assertFalse(PackUrlPolicy.isOpenableExternalUrl("content://foo"))
		assertFalse(PackUrlPolicy.isOpenableExternalUrl("intent://scan/#Intent;end"))
		assertFalse(PackUrlPolicy.isOpenableExternalUrl("javascript:alert(1)"))
		assertFalse(PackUrlPolicy.isOpenableExternalUrl("http:///nohost"))
		assertFalse(PackUrlPolicy.isOpenableExternalUrl("mailto:"))
		assertFalse(PackUrlPolicy.isOpenableExternalUrl(""))
		assertFalse(PackUrlPolicy.isOpenableExternalUrl(null))
	}
}
