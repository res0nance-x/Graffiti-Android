package r3.graffiti

import org.junit.Assert.*
import org.junit.Test
import r3.source.readString
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

class ExampleUnitTest {
	@Test
	fun addition_isCorrect() {
		assertEquals(4, 2 + 2)
	}

	@Test
	fun portalOverTCPNode_andStatelessRangeSeek() {
		val tempDir = Files.createTempDirectory("graffiti_portal_test").toFile()
		try {
			val hostDir = File(tempDir, "host").also { it.mkdirs() }
			val clientDir = File(tempDir, "client").also { it.mkdirs() }

			val hostP2P = GraffitiP2P(hostDir)
			val clientP2P = GraffitiP2P(clientDir)

			try {
				hostP2P.startTCPServer(0)
				val port = hostP2P.serverPort!!

				val clientNode = clientP2P.getTCPNode(InetSocketAddress("127.0.0.1", port))

				// 1. Default portal when host portalDir is empty
				val portalPack = clientP2P.createPortalPack(clientNode)
				val initialItems = portalPack.toList()
				assertEquals(1, initialItems.size)
				assertEquals("index.html", initialItems[0].path)
				val defaultHtml = initialItems[0].readString()
				assertTrue(defaultHtml.contains("This node has not customized their portal yet."))

				// 2. Host adds custom files to portalDir (including a 150 KB file to test multi-64KB chunks and skip/seek)
				File(hostP2P.portalDir, "index.html").writeText("<html><body>Custom Portal</body></html>")
				val subDir = File(hostP2P.portalDir, "media/video").also { it.mkdirs() }
				val largeData = ByteArray(150_000) { i -> (i % 251).toByte() }
				File(subDir, "clip.mp4").writeBytes(largeData)

				// Hidden files should not be exposed
				File(hostP2P.portalDir, ".hidden_auth").writeText("secret")

				val updatedItems = portalPack.toList().associateBy { it.path }
				assertEquals(2, updatedItems.size)
				assertTrue(updatedItems.containsKey("index.html"))
				assertTrue(updatedItems.containsKey("media/video/clip.mp4"))
				assertFalse(updatedItems.containsKey(".hidden_auth"))

				assertEquals("<html><body>Custom Portal</body></html>", updatedItems["index.html"]!!.readString())

				// 3. Test reading full 150 KB file across multiple 64 KB chunks
				val clipContent = updatedItems["media/video/clip.mp4"]!!
				assertEquals(150_000L, clipContent.length)
				val fullRead = clipContent.createInputStream().use { it.readBytes() }
				assertArrayEquals(largeData, fullRead)

				// 4. Test stateless skip() (HTTP Range seek simulation to offset 100,000)
				val seekOffset = 100_000L
				val slicedRead = clipContent.createInputStream().use { stream ->
					val skipped = stream.skip(seekOffset)
					assertEquals(seekOffset, skipped)
					stream.readBytes()
				}
				assertEquals(50_000, slicedRead.size)
				assertArrayEquals(largeData.copyOfRange(100_000, 150_000), slicedRead)
			} finally {
				clientP2P.shutdown()
				hostP2P.shutdown()
			}
		} finally {
			tempDir.deleteRecursively()
		}
	}
}