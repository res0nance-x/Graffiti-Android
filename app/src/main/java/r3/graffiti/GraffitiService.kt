package r3.graffiti

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import r3.content.BinaryContent
import r3.content.Content
import r3.http.ContentHandler
import r3.http.HandlerFactory
import r3.http.WebServer
import r3.io.log
import r3.hash.hash256
import r3.org.json.JSONObject
import java.io.File

class AssetRouter(private val context: Context) : ContentHandler {
	override fun handle(header: JSONObject, content: Content?): Content? {
		val uri = header.optString("path", "/")
		val mappedUri = if (uri == "/") "/index.html" else uri
		val path = mappedUri.removePrefix("/")
		return try {
			val inputStream = context.assets.open(path)
			val data = inputStream.readBytes()
			val ext = path.substringAfterLast('.', "")
			BinaryContent(data, path, ext)
		} catch (e: Exception) {
			log("There was an error in AssetRouter $e")
			null
		}
	}
}

class GraffitiService : Service() {
	companion object {
		const val ACTION_STOP_SERVICE = "r3.graffiti.ACTION_STOP_SERVICE"
		private const val NOTIFICATION_ID = 1
		private const val CHANNEL_ID = "graffiti_service_channel"

		@Volatile
		private var running = false
		fun isRunning() = running

		@Volatile
		var port: Int = 0
			private set

		@Volatile
		var messageCount: Int = 0
			private set
	}

	private var webserver: WebServer? = null
	private var p2p: GraffitiP2P? = null
	private var multicastLock: WifiManager.MulticastLock? = null
	private var wifiLock: WifiManager.WifiLock? = null

	override fun onCreate() {
		super.onCreate()
		createNotificationChannel()
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		if (intent?.action == ACTION_STOP_SERVICE) {
			log("Stop action received via notification")
			stopForeground(STOP_FOREGROUND_REMOVE)
			stopSelf()
			return START_NOT_STICKY
		}

		messageCount = 0

		val notification = createNotification()
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
		} else {
			startForeground(NOTIFICATION_ID, notification)
		}

		if (multicastLock == null) {
			try {
				val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
				multicastLock = wifiManager.createMulticastLock("GraffitiMulticastLock").apply {
					setReferenceCounted(false)
					acquire()
				}
				log("Multicast lock acquired")
			} catch (e: Exception) {
				log("Failed to acquire multicast lock: ${e.message}")
			}
		}

		if (wifiLock == null) {
			try {
				val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
				val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
					WifiManager.WIFI_MODE_FULL_HIGH_PERF
				} else {
					@Suppress("DEPRECATION")
					WifiManager.WIFI_MODE_FULL
				}
				wifiLock = wifiManager.createWifiLock(lockMode, "GraffitiWifiLock").apply {
					setReferenceCounted(false)
					acquire()
				}
				log("WiFi lock acquired")
			} catch (e: Exception) {
				log("Failed to acquire WiFi lock: ${e.message}")
			}
		}

		if (startServer()) {
			running = true
		} else {
			running = false
			stopSelf()
		}
		return START_STICKY
	}

	private fun startServer(): Boolean {
		if (webserver != null) return true

		return try {
			val dataDir = File(filesDir, "graffiti")
			if (!dataDir.exists() && !dataDir.mkdirs()) {
				log("Failed to create data directory")
				return false
			}
			val p2p = GraffitiP2P(dataDir, false)
			this.p2p = p2p
			val apiListener = p2p.onMessageReceived
			p2p.onMessageReceived = { encKey ->
				apiListener?.invoke(encKey)
				messageCount++
			}
			val server = WebServer(
				"localhost",
				0,
				p2p.tmpDir
			).apply {
				handlers.add(HandlerFactory.createLogRouter())
				val api = GraffitiAPI(
					p2p,
					{ json ->
						sendToAllWebSockets(json.toString())
					}
				).apply {
					onOpenPack = { source, fileName, password ->
						val isEncrypted = fileName.endsWith(".epack", ignoreCase = true) || !password.isNullOrEmpty()
						if (isEncrypted && password.isNullOrEmpty()) {
							throw IllegalArgumentException("PASSWORD_REQUIRED")
						}

						val pack: r3.pack.Pack = if (isEncrypted) {
							val pass = r3.pke.Password256(password!!.toByteArray().hash256())
							val sequence = r3.math.EncryptedSequence.createSequence(pass)
							val encryptedSrc = r3.encryption.EncryptedSource(sequence, source)
							r3.pack.BinaryPack(encryptedSrc)
						} else {
							r3.pack.BinaryPack(source)
						}

						try {
							pack.keys.size
						} catch (e: Exception) {
							throw IllegalArgumentException("INVALID_PASSWORD")
						}

						PackHolder.currentPack = pack
						PackHolder.currentPackName = fileName

						val serviceIntent = Intent(this@GraffitiService, PackMediaPlaybackService::class.java)
						androidx.core.content.ContextCompat.startForegroundService(this@GraffitiService, serviceIntent)

						val intent = Intent(this@GraffitiService, PackViewActivity::class.java).apply {
							addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
						}
						startActivity(intent)

						var port = PackHolder.listeningPort
						var waitCount = 0
						while (port == 0 && waitCount < 30) {
							Thread.sleep(100)
							port = PackHolder.listeningPort
							waitCount++
						}
						Pair(java.util.UUID.randomUUID().toString(), port)
					}
					onClosePack = { _ ->
						val stopIntent = Intent(this@GraffitiService, PackMediaPlaybackService::class.java).apply {
							action = PackMediaPlaybackService.ACTION_STOP
						}
						startService(stopIntent)
					}
				}
				handlers.add(api)
				handlers.add(AssetRouter(this@GraffitiService))
				tempFileManagerFactory = CustomTempFileManagerFactory { p2p.tmpDir }
				start(0, true)
			}
			port = server.listeningPort
			webserver = server
			log("WebServer started on port $port")
			true
		} catch (e: Exception) {
			log("Failed to start server components: ${e.message}")
			cleanup()
			false
		}
	}

	private fun cleanup() {
		try {
			multicastLock?.let {
				if (it.isHeld) {
					it.release()
				}
				multicastLock = null
				log("Multicast lock released")
			}
		} catch (e: Exception) {
			log("Error releasing multicast lock: ${e.message}")
		}
		try {
			wifiLock?.let {
				if (it.isHeld) {
					it.release()
				}
				wifiLock = null
				log("WiFi lock released")
			}
		} catch (e: Exception) {
			log("Error releasing WiFi lock: ${e.message}")
		}
		try {
			webserver?.stop()
		} catch (e: Exception) {
			log("Error stopping WebServer: ${e.message}")
		}
		try {
			p2p?.stopTCPServer()
		} catch (e: Exception) {
			log("Error stopping P2P: ${e.message}")
		}
		webserver = null
		p2p = null
		running = false
		port = 0
	}

	override fun onDestroy() {
		cleanup()
		super.onDestroy()
	}

	override fun onBind(intent: Intent?): IBinder? = null

	private fun createNotificationChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val serviceChannel = NotificationChannel(
				CHANNEL_ID,
				"Graffiti Service Channel",
				NotificationManager.IMPORTANCE_LOW
			)
			val manager = getSystemService(NotificationManager::class.java)
			manager.createNotificationChannel(serviceChannel)
		}
	}

	private fun createNotification(): Notification {
		val stopIntent = Intent(this, GraffitiService::class.java).apply {
			action = ACTION_STOP_SERVICE
		}
		val stopPendingIntent = PendingIntent.getService(
			this, 0, stopIntent,
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
		)

		val mainActivityIntent = Intent(this, WebViewActivity::class.java)
		val mainActivityPendingIntent = PendingIntent.getActivity(
			this, 0, mainActivityIntent,
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
		)

		return NotificationCompat.Builder(this, CHANNEL_ID)
			.setContentTitle("Graffiti")
			.setContentText("P2P Node is active")
			.setSmallIcon(android.R.drawable.stat_notify_sync)
			.setContentIntent(mainActivityPendingIntent)
			.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
			.setOngoing(true)
			.setCategory(Notification.CATEGORY_SERVICE)
			.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
			.build()
	}
}
