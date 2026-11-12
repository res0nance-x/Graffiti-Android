package r3.graffiti

import org.junit.Assert.*
import org.junit.Test
import r3.content.FileContent
import r3.net.discover.PeerAddressInfo
import r3.org.json.JSONObject
import r3.source.readString
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class BridgeTest {

	@Test
	fun testBridgeDiscoveryAndTunnelLifecycle() {
		val dirBridge = Files.createTempDirectory("graffiti_bridge_").toFile()
		val dirA = Files.createTempDirectory("graffiti_node_a_").toFile()
		val dirB = Files.createTempDirectory("graffiti_node_b_").toFile()

		val p2pBridge = GraffitiP2P(dirBridge, relayEnabledAtStartup = false, bridgeEnabledAtStartup = true)
		val p2pA = GraffitiP2P(dirA)
		val p2pB = GraffitiP2P(dirB)

		val apiA = GraffitiAPI(p2pA) {}
		val apiB = GraffitiAPI(p2pB) {}

		p2pBridge.startTCPServer(0)
		val bridgePort = p2pBridge.serverPort!!

		try {
			// Connect Node A and Node B to Bridge
			p2pA.getTCPNode(InetSocketAddress("127.0.0.1", bridgePort))
			p2pB.getTCPNode(InetSocketAddress("127.0.0.1", bridgePort))

			val bridgePeerKey = p2pBridge.serverIdentity.asPeer().key
			val peerKeyA = p2pA.serverIdentity.asPeer().key
			val peerKeyB = p2pB.serverIdentity.asPeer().key

			val deadline = System.currentTimeMillis() + 5_000L
			while (System.currentTimeMillis() < deadline &&
				(!p2pA.isConnectedToPeer(bridgePeerKey) ||
					!p2pB.isConnectedToPeer(bridgePeerKey) ||
					!p2pBridge.isConnectedToPeer(peerKeyA) ||
					!p2pBridge.isConnectedToPeer(peerKeyB))
			) {
				Thread.sleep(50)
			}

			assertTrue("Node A should be connected to Bridge", p2pA.isConnectedToPeer(bridgePeerKey))
			assertTrue("Node B should be connected to Bridge", p2pB.isConnectedToPeer(bridgePeerKey))

			// Verify Node A sees the Bridge flagged as a bridge in listConnections()
			val bridgeConnOnA = p2pA.listConnections().firstOrNull { it.peerKey == bridgePeerKey.toString() }
			assertNotNull(bridgeConnOnA)
			assertTrue("Bridge connection should have isBridge = true", bridgeConnOnA!!.isBridge)
			assertFalse("Bridge connection should have isRelay = false", bridgeConnOnA.isRelay)

			// Node A discovers bridged peers via Bridge
			val discovered = CopyOnWriteArrayList<PeerAddressInfo>()
			p2pA.discoverBridgedPeers { info -> discovered.add(info) }

			assertEquals("Node A should discover Node B through the Bridge", 1, discovered.size)
			val discoveredB = discovered[0]
			assertEquals("Discovered peer key should match Node B", peerKeyB, discoveredB.serverKey)
			assertTrue("Discovered virtual port must be negative, got ${discoveredB.port}", discoveredB.port < 0)

			// Connect Node A -> Node B using the negative virtual port via GraffitiAPI
			val connectResp = apiA.handle(JSONObject().apply {
				put("path", "/api/client/connect")
				put("node", "127.0.0.1")
				put("port", discoveredB.port)
			}, null)
			assertNotNull(connectResp)
			assertTrue(JSONObject(connectResp!!.readString()).getBoolean("ok"))

			// Wait for end-to-end mutual authentication over the virtual BridgeTCPNode
			val tunnelDeadline = System.currentTimeMillis() + 5_000L
			while (System.currentTimeMillis() < tunnelDeadline &&
				(!p2pA.isConnectedToPeer(peerKeyB) || !p2pB.isConnectedToPeer(peerKeyA))
			) {
				Thread.sleep(50)
			}

			assertTrue("Node A should be authenticated with Node B over Bridge", p2pA.isConnectedToPeer(peerKeyB))
			assertTrue("Node B should be authenticated with Node A over Bridge", p2pB.isConnectedToPeer(peerKeyA))

			val connBOnA = p2pA.listConnections().firstOrNull { it.peerKey == peerKeyB.toString() }
			assertNotNull(connBOnA)
			assertTrue("Connection to B on A should be marked isBridged", connBOnA!!.isBridged)
			assertEquals(discoveredB.port, connBOnA.port)

			val connAOnB = p2pB.listConnections().firstOrNull { it.peerKey == peerKeyA.toString() }
			assertNotNull(connAOnB)
			assertTrue("Connection to A on B should be marked isBridged", connAOnB!!.isBridged)
			assertTrue("Connection to A on B should have negative virtual port", connAOnB.port < 0)

			// Disconnect the virtual tunnel from Node A
			val discResp = apiA.handle(JSONObject().apply {
				put("path", "/api/client/disconnect")
				put("node", "127.0.0.1")
				put("port", discoveredB.port)
			}, null)
			assertNotNull(discResp)
			assertTrue(JSONObject(discResp!!.readString()).getBoolean("ok"))

			val closeDeadline = System.currentTimeMillis() + 5_000L
			while (System.currentTimeMillis() < closeDeadline &&
				(p2pA.isConnectedToPeer(peerKeyB) || p2pB.isConnectedToPeer(peerKeyA))
			) {
				Thread.sleep(50)
			}

			assertFalse("Virtual connection on Node A should be closed", p2pA.isConnectedToPeer(peerKeyB))
			assertFalse("Virtual connection on Node B should be closed via CLOSE signal", p2pB.isConnectedToPeer(peerKeyA))
			// Physical connections to Bridge must still be alive
			assertTrue("Node A should still be connected to Bridge", p2pA.isConnectedToPeer(bridgePeerKey))
			assertTrue("Node B should still be connected to Bridge", p2pB.isConnectedToPeer(bridgePeerKey))
		} finally {
			p2pA.shutdown()
			p2pB.shutdown()
			p2pBridge.shutdown()
			dirBridge.deleteRecursively()
			dirA.deleteRecursively()
			dirB.deleteRecursively()
		}
	}

	@Test
	fun testPortalAndLargeContentOverBridgeWithZeroBridgeStorage() {
		val dirBridge = Files.createTempDirectory("graffiti_bridge2_").toFile()
		val dirA = Files.createTempDirectory("graffiti_node_a2_").toFile()
		val dirB = Files.createTempDirectory("graffiti_node_b2_").toFile()

		val p2pBridge = GraffitiP2P(dirBridge, relayEnabledAtStartup = false, bridgeEnabledAtStartup = true)
		val p2pA = GraffitiP2P(dirA)
		val p2pB = GraffitiP2P(dirB).apply {
			autoDownloadThresholdBytes = 1024L // Force on-demand p2plink download for large files
		}

		val apiA = GraffitiAPI(p2pA) {}
		val apiB = GraffitiAPI(p2pB) {}

		p2pBridge.startTCPServer(0)
		val bridgePort = p2pBridge.serverPort!!

		try {
			p2pA.getTCPNode(InetSocketAddress("127.0.0.1", bridgePort))
			p2pB.getTCPNode(InetSocketAddress("127.0.0.1", bridgePort))

			val peerKeyA = p2pA.serverIdentity.asPeer().key
			val peerKeyB = p2pB.serverIdentity.asPeer().key

			val deadline = System.currentTimeMillis() + 5_000L
			while (System.currentTimeMillis() < deadline &&
				(!p2pBridge.isConnectedToPeer(peerKeyA) || !p2pBridge.isConnectedToPeer(peerKeyB))
			) {
				Thread.sleep(50)
			}

			// Discover B on A and connect through Bridge
			val discovered = CopyOnWriteArrayList<PeerAddressInfo>()
			p2pA.discoverBridgedPeers { info -> discovered.add(info) }
			assertEquals(1, discovered.size)
			val virtualPortB = discovered[0].port

			val bridgedNodeToB = p2pA.connectBridgedPeer("127.0.0.1", virtualPortB)
			val tunnelDeadline = System.currentTimeMillis() + 5_000L
			while (System.currentTimeMillis() < tunnelDeadline &&
				(!p2pA.isConnectedToPeer(peerKeyB) || !p2pB.isConnectedToPeer(peerKeyA))
			) {
				Thread.sleep(50)
			}
			assertTrue(p2pA.isConnectedToPeer(peerKeyB))
			assertTrue(p2pB.isConnectedToPeer(peerKeyA))

			// 1. Test Node Portal over Bridge (150 KB file spanning multiple chunks)
			val portalBytes = "Portal over bridge multi-block test data! ".repeat(4000).toByteArray()
			File(p2pB.portalDir, "bridged_portal_doc.txt").writeBytes(portalBytes)

			val portalPack = p2pA.createPortalPack(bridgedNodeToB)
			val portalItems = portalPack.toList()
			val portalContent = portalItems.firstOrNull { it.path.contains("bridged_portal_doc.txt") }
			assertNotNull("Portal items should contain bridged_portal_doc.txt", portalContent)
			val fetchedPortalBytes = portalContent!!.createInputStream().use { it.readBytes() }
			assertArrayEquals("Portal file bytes over bridge must match exactly", portalBytes, fetchedPortalBytes)

			// 2. Test Large File / p2plink transfer over Bridge (200 KB > 64 KB MAXBLOCKSIZE)
			val alice = p2pA.createIdentity("alice-bridge")
			val bob = p2pB.createIdentity("bob-bridge")
			p2pA.savePeer(bob.asPeer())
			p2pB.savePeer(alice.asPeer())

			val largeFileBytes = "Large file streamed through BridgeTCPNode: ".repeat(5000).toByteArray()
			val uploadFile = File(dirA, "large_bridge_video.mp4").apply { writeBytes(largeFileBytes) }

			val sendResp = apiA.handle(JSONObject().apply {
				put("path", "/api/message/send/file")
				put("identityKey", alice.key.toString())
				put("peerKey", bob.asPeer().key.toString())
				put("file", "large_bridge_video.mp4")
			}, FileContent(uploadFile))
			assertNotNull(sendResp)
			assertTrue(JSONObject(sendResp!!.readString()).getBoolean("ok"))

			val aliceMetas = p2pA.listAllMeta().toList()
			assertEquals(1, aliceMetas.size)
			val msgKey = aliceMetas[0].key

			// Wait for Node B to receive the metadata over the bridged connection
			val metaDeadline = System.currentTimeMillis() + 10_000L
			while (System.currentTimeMillis() < metaDeadline && p2pB.listAllMeta().none { it.key == msgKey }) {
				Thread.sleep(50)
			}
			assertTrue("Node B should receive message metadata over Bridge", p2pB.listAllMeta().any { it.key == msgKey })
			assertFalse("Node B should not auto-download 200 KB file (> 1 KB threshold)", p2pB.hasContent(msgKey))

			// Node B triggers on-demand p2plink download over the Bridge
			val dlResp = apiB.handle(JSONObject().apply {
				put("path", "/api/message/download")
				put("key", msgKey.toString())
			}, null)
			assertNotNull(dlResp)
			assertTrue(JSONObject(dlResp!!.readString()).getBoolean("ok"))

			val dlDeadline = System.currentTimeMillis() + 10_000L
			while (System.currentTimeMillis() < dlDeadline && !p2pB.hasContent(msgKey)) {
				Thread.sleep(50)
			}
			assertTrue("Node B should complete on-demand download over Bridge", p2pB.hasContent(msgKey))
			val downloadedBytes = p2pB.getContent(msgKey).createInputStream().use { it.readBytes() }
			assertArrayEquals("Downloaded 200 KB file over Bridge must match original bytes", largeFileBytes, downloadedBytes)

			// 3. Verify Bridge stored ZERO metadata or content files on disk
			val bridgeMetas = p2pBridge.listAllMeta()
			val bridgeContentFiles = p2pBridge.contentDir.listFiles { f: File -> f.isFile } ?: emptyArray()
			assertEquals("Bridge must store 0 metadata entries", 0, bridgeMetas.size)
			assertEquals("Bridge must store 0 content files", 0, bridgeContentFiles.size)
		} finally {
			p2pA.shutdown()
			p2pB.shutdown()
			p2pBridge.shutdown()
			dirBridge.deleteRecursively()
			dirA.deleteRecursively()
			dirB.deleteRecursively()
		}
	}
}
