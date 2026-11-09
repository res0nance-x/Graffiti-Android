package r3.graffiti

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import r3.content.BinaryContent
import r3.content.Content
import r3.hash.hash256
import r3.http.AuthorizationHandler
import r3.http.ContentHandler
import r3.http.HandlerFactory
import r3.http.WebServer
import r3.io.log
import r3.key.Key128
import r3.org.json.JSONObject
import r3.pke.EncryptedMetaKey
import java.io.File

class AssetRouter(private val context: Context) : ContentHandler {
	override fun handle(header: JSONObject, content: Content?): Content? {
		val uri = header.optString("path", "/")
		if (uri.startsWith("/api/")) return null
		val mappedUri = if (uri == "/") "/index.html" else uri
		val path = mappedUri.removePrefix("/")
		return try {
			val inputStream = context.assets.open(path)
			val data = inputStream.readBytes()
			val ext = path.substringAfterLast('.', "")
			BinaryContent(data, path, ext)
		} catch (_: java.io.FileNotFoundException) {
			null
		} catch (e: Exception) {
			log("There was an error in AssetRouter $e")
			null
		}
	}
}

class GraffitiService : Service() {
	companion object {
		init {
			GraffitiApp.initLogging()
		}

		const val ACTION_STOP_SERVICE = "r3.graffiti.ACTION_STOP_SERVICE"
		private const val NOTIFICATION_ID = 1
		private const val MESSAGE_NOTIFICATION_ID = 3
		private const val CHANNEL_ID = "graffiti_service_channel_v5"
		private const val MESSAGE_CHANNEL_ID = "graffiti_message_channel"

		@Volatile
		private var instance: GraffitiService? = null

		@Volatile
		var isScreenVisible: Boolean = false
			private set

		@Volatile
		var port: Int = 0
			private set

		@Volatile
		var messageCount: Int = 0
			private set

		@Volatile
		var startupKey: Key128? = null
			private set
		private var authHandler: AuthorizationHandler? = null
		val authToken: String?
			get() = authHandler?.authSecret?.arr?.toHexString()

		fun getOrRotateStartupKey(): Key128? {
			return authHandler?.rotateStartupKey()?.also { startupKey = it } ?: startupKey
		}

		fun onScreenVisibilityChanged(visible: Boolean) {
			isScreenVisible = visible
			if (visible) {
				val svc = instance
				if (svc != null && svc.isAppScreenVisible()) {
					svc.clearUnreadMessages()
				}
			}
		}
	}

	private var webserver: WebServer? = null
	private var p2p: GraffitiP2P? = null
	private var api: GraffitiAPI? = null
	private val lockMutex = Any()
	private val notificationLock = Any()
	private val unreadKeys = HashSet<EncryptedMetaKey>()
	private val soundDebouncer = SoundDebouncer()
	private var multicastLock: WifiManager.MulticastLock? = null
	private var wifiLock: WifiManager.WifiLock? = null
	private var wakeLock: PowerManager.WakeLock? = null
	private var networkCallback: ConnectivityManager.NetworkCallback? = null
	private var powerStateReceiver: BroadcastReceiver? = null
	private fun isDevicePluggedIn(): Boolean {
		return try {
			val batteryIntent = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
			val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
			plugged != 0
		} catch (_: Exception) {
			false
		}
	}

	private fun updateBackgroundMode() {
		val p2pInstance = p2p ?: return
		try {
			val powerManager = applicationContext.getSystemService(POWER_SERVICE) as PowerManager
			val isInteractive = powerManager.isInteractive
			val isPluggedIn = isDevicePluggedIn()
			val isServerOrRelay = (p2pInstance.serverPort != null) || p2pInstance.isRelayEnabled()
			val useBackgroundMode = !isInteractive && !isPluggedIn && !isServerOrRelay
			val wasBackground = p2pInstance.isBackgroundMode()
			p2pInstance.setBackgroundMode(useBackgroundMode)
			if (wasBackground && !useBackgroundMode) {
				updateTransferLocks(graceTimeoutMs = 10_000L)
			}
		} catch (e: Exception) {
			log("Error updating background mode: ${e.message}")
		}
	}

	private fun updateMulticastLock(needed: Boolean) {
		synchronized(lockMutex) {
			try {
				if (needed) {
					if (multicastLock == null) {
						val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
						multicastLock = wifiManager.createMulticastLock("GraffitiMulticastLock").apply {
							setReferenceCounted(false)
						}
					}
					if (multicastLock?.isHeld == false) {
						multicastLock?.acquire()
						log("Multicast lock acquired")
					}
				} else {
					if (multicastLock?.isHeld == true) {
						multicastLock?.release()
						log("Multicast lock released")
					}
				}
			} catch (e: Exception) {
				log("Error updating multicast lock: ${e.message}")
			}
		}
	}

	private fun updateTransferLocks(graceTimeoutMs: Long = 0L) {
		synchronized(lockMutex) {
			val active = (p2p?.isAnyTransferActive() == true) || (api?.stateManager?.isEncoding() == true)
			try {
				if (active) {
					if (wakeLock == null) {
						val powerManager = applicationContext.getSystemService(POWER_SERVICE) as PowerManager
						wakeLock =
							powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Graffiti:TransferWakeLock").apply {
								setReferenceCounted(false)
							}
					}
					val wasHeld = wakeLock?.isHeld == true
					wakeLock?.acquire(15 * 60 * 1000L)
					if (!wasHeld) {
						log("Transfer wake lock acquired")
					}

					if (wifiLock == null) {
						val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
						val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
							WifiManager.WIFI_MODE_FULL_LOW_LATENCY
						} else {
							@Suppress("DEPRECATION")
							WifiManager.WIFI_MODE_FULL_HIGH_PERF
						}
						wifiLock = wifiManager.createWifiLock(lockMode, "GraffitiWifiLock").apply {
							setReferenceCounted(false)
						}
					}
					if (wifiLock?.isHeld == false) {
						wifiLock?.acquire()
						log("Transfer WiFi lock acquired")
					}
				} else if (graceTimeoutMs > 0L) {
					if (wakeLock == null) {
						val powerManager = applicationContext.getSystemService(POWER_SERVICE) as PowerManager
						wakeLock =
							powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Graffiti:TransferWakeLock").apply {
								setReferenceCounted(false)
							}
					}
					wakeLock?.acquire(graceTimeoutMs)
				} else {
					if (wakeLock?.isHeld == true) {
						wakeLock?.release()
						log("Transfer wake lock released")
					}
					if (wifiLock?.isHeld == true) {
						wifiLock?.release()
						log("Transfer WiFi lock released")
					}
				}
			} catch (e: Exception) {
				log("Error updating transfer locks: ${e.message}")
			}
		}
	}

	override fun onCreate() {
		super.onCreate()
		instance = this
		createNotificationChannel()
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		if (intent?.action == ACTION_STOP_SERVICE) {
			log("Stop action received via notification")
			stopForeground(STOP_FOREGROUND_REMOVE)
			stopSelf()
			return START_NOT_STICKY
		}

		synchronized(notificationLock) {
			unreadKeys.clear()
			messageCount = 0
		}
		soundDebouncer.reset()
		cancelMessageNotification()
		val notification = createServiceNotification()
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
		} else {
			startForeground(NOTIFICATION_ID, notification)
		}

		if (networkCallback == null) {
			try {
				val connectivityManager = applicationContext.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
				val callback = object : ConnectivityManager.NetworkCallback() {
					override fun onAvailable(network: Network) {
						log("Network link available - retrying pending connections")
						updateTransferLocks(graceTimeoutMs = 10_000L)
						p2p?.retryPendingConnectionsImmediately()
					}

					override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
						if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
							p2p?.retryPendingConnectionsImmediately()
						}
					}
				}
				connectivityManager.registerDefaultNetworkCallback(callback)
				networkCallback = callback
			} catch (e: Exception) {
				log("Failed to register network callback: ${e.message}")
			}
		}

		if (powerStateReceiver == null) {
			try {
				val receiver = object : BroadcastReceiver() {
					override fun onReceive(context: Context?, intent: Intent?) {
						updateBackgroundMode()
						if (isAppScreenVisible()) {
							clearUnreadMessages()
						}
					}
				}
				val filter = IntentFilter().apply {
					addAction(Intent.ACTION_SCREEN_ON)
					addAction(Intent.ACTION_SCREEN_OFF)
					addAction(Intent.ACTION_USER_PRESENT)
					addAction(Intent.ACTION_POWER_CONNECTED)
					addAction(Intent.ACTION_POWER_DISCONNECTED)
				}
				registerReceiver(receiver, filter)
				powerStateReceiver = receiver
			} catch (e: Exception) {
				log("Failed to register power state receiver: ${e.message}")
			}
		}

		if (!startServer()) {
			stopSelf()
		} else {
			updateBackgroundMode()
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
			val extMediaDir = externalMediaDirs.firstOrNull { it != null } ?: filesDir
			p2p.portalDir = File(extMediaDir, "portal").also { it.mkdirs() }
			p2p.addOnMessageReceivedListener { key ->
				onNewMessageReceived(key)
			}
			this.p2p = p2p
			p2p.addOnMulticastStateChangedListener { needed ->
				updateMulticastLock(needed)
			}
			p2p.addOnTransferStateChangedListener { _ ->
				updateTransferLocks()
			}
			p2p.addOnNodeConnectedListener { _, _ ->
				updateTransferLocks(graceTimeoutMs = 10_000L)
			}
			val server = WebServer(
				"localhost",
				0,
				p2p.tmpDir
			).apply {
				val key = Key128.randomKey()
				val auth = HandlerFactory.createAuthHandler(key)
				authHandler = auth
				startupKey = key

				handlers.add(HandlerFactory.createHostOriginHandler())
				handlers.add(auth)
				val api = GraffitiAPI(
					p2p,
					{ json ->
						updateBackgroundMode()
						updateTransferLocks()
						sendToAllWebSockets(json.toString())
					}
				).apply {
					addOnOpenPackListener { source, fileName, password ->
						val isEncrypted = fileName.endsWith(".epack", ignoreCase = true) || !password.isNullOrEmpty()
						if (isEncrypted && password.isNullOrEmpty()) {
							throw IllegalArgumentException("PASSWORD_REQUIRED")
						}
						val pack: r3.pack.Pack = if (isEncrypted) {
							val pass = r3.pke.Password256(password!!.toByteArray().hash256())
							val sequence = r3.math.EncryptedSequence.createSequence(pass)
							val encryptedSrc = r3.encryption.EncryptedSource(sequence, source)
							r3.pack.BinaryPack(encryptedSrc)
						} else if (fileName.endsWith(".mhtml", ignoreCase = true) || fileName.endsWith(
								".mht",
								ignoreCase = true
							)
						) {
							r3.pack.MhtmlPack(source)
						} else if (fileName.endsWith(".zip", ignoreCase = true)) {
							r3.pack.ZipPack(source)
						} else {
							r3.pack.BinaryPack(source)
						}

						if (isEncrypted) {
							try {
								pack.count()
							} catch (_: Exception) {
								throw IllegalArgumentException("INVALID_PASSWORD")
							}
						}

						PackHolder.currentPack = pack
						PackHolder.currentPackName = fileName
						PackHolder.listeningPort = 0
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
					addOnOpenPackInstanceListener { pack, title ->
						PackHolder.currentPack = pack
						PackHolder.currentPackName = title
						PackHolder.listeningPort = 0
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
					addOnClosePackListener { _ ->
						val stopIntent = Intent(this@GraffitiService, PackMediaPlaybackService::class.java).apply {
							action = PackMediaPlaybackService.ACTION_STOP
						}
						startService(stopIntent)
					}

					addOnOpenUrlListener { url ->
						try {
							val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
								addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
							}
							startActivity(browserIntent)
							true
						} catch (_: Exception) {
							false
						}
					}
					addOnGetVersionListener {
						try {
							assets.open("build-date.txt").bufferedReader().use { it.readText().trim() }
								.takeIf { it.isNotEmpty() }
								?: run {
									val pInfo = packageManager.getPackageInfo(packageName, 0)
									val time = pInfo.lastUpdateTime.takeIf { it > 0L }
										?: runCatching { java.io.File(packageResourcePath).lastModified() }.getOrNull()
											?.takeIf { it > 0L }
										?: System.currentTimeMillis()
									java.text.SimpleDateFormat("yyyy-MM-dd:HH", java.util.Locale.getDefault())
										.format(java.util.Date(time))
								}
						} catch (_: Exception) {
							try {
								val pInfo = packageManager.getPackageInfo(packageName, 0)
								val time = pInfo.lastUpdateTime.takeIf { it > 0L }
									?: runCatching { java.io.File(packageResourcePath).lastModified() }.getOrNull()
										?.takeIf { it > 0L }
									?: System.currentTimeMillis()
								java.text.SimpleDateFormat("yyyy-MM-dd:HH", java.util.Locale.getDefault())
									.format(java.util.Date(time))
							} catch (_: Exception) {
								"Unknown"
							}
						}
					}
				}
				this@GraffitiService.api = api
				handlers.add(HandlerFactory.createLogRouter(errorsOnly = true))
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

	private fun isAppScreenVisible(): Boolean {
		if (!isScreenVisible) return false
		return try {
			val powerManager = applicationContext.getSystemService(POWER_SERVICE) as PowerManager
			val keyguardManager = applicationContext.getSystemService(KEYGUARD_SERVICE) as KeyguardManager
			powerManager.isInteractive && !keyguardManager.isKeyguardLocked
		} catch (_: Exception) {
			isScreenVisible
		}
	}

	private fun onNewMessageReceived(key: EncryptedMetaKey) {
		if (isAppScreenVisible()) return
		var count = 0
		var added = false
		synchronized(notificationLock) {
			if (unreadKeys.add(key)) {
				messageCount = unreadKeys.size
				count = messageCount
				added = true
			}
		}
		if (added) {
			val shouldAlert = soundDebouncer.shouldTrigger()
			updateMessageNotification(count = count, silent = !shouldAlert)
		}
	}

	private fun clearUnreadMessages() {
		var shouldClear = false
		synchronized(notificationLock) {
			if (messageCount > 0 || unreadKeys.isNotEmpty()) {
				unreadKeys.clear()
				messageCount = 0
				shouldClear = true
			}
		}
		if (shouldClear) {
			soundDebouncer.reset()
			cancelMessageNotification()
		}
	}

	private fun updateMessageNotification(count: Int, silent: Boolean) {
		if (count <= 0) {
			cancelMessageNotification()
			return
		}
		try {
			val manager = getSystemService(NotificationManager::class.java)
			manager.notify(MESSAGE_NOTIFICATION_ID, createMessageNotification(count, silent))
		} catch (e: Exception) {
			log("Error updating message notification: ${e.message}")
		}
	}

	private fun cancelMessageNotification() {
		try {
			val manager = getSystemService(NotificationManager::class.java)
			manager.cancel(MESSAGE_NOTIFICATION_ID)
		} catch (e: Exception) {
			log("Error cancelling message notification: ${e.message}")
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
			wakeLock?.let {
				if (it.isHeld) {
					it.release()
				}
				wakeLock = null
				log("Wake lock released")
			}
		} catch (e: Exception) {
			log("Error releasing wake lock: ${e.message}")
		}
		try {
			powerStateReceiver?.let {
				unregisterReceiver(it)
			}
			powerStateReceiver = null
		} catch (e: Exception) {
			log("Error unregistering power state receiver: ${e.message}")
		}
		try {
			networkCallback?.let {
				val connectivityManager = applicationContext.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
				connectivityManager.unregisterNetworkCallback(it)
			}
			networkCallback = null
		} catch (e: Exception) {
			log("Error unregistering network callback: ${e.message}")
		}
		try {
			webserver?.stop()
		} catch (e: Exception) {
			log("Error stopping WebServer: ${e.message}")
		}
		try {
			p2p?.shutdown()
		} catch (e: Exception) {
			log("Error stopping P2P: ${e.message}")
		}
		synchronized(notificationLock) {
			unreadKeys.clear()
			messageCount = 0
		}
		soundDebouncer.reset()
		cancelMessageNotification()
		if (instance === this) {
			instance = null
		}
		webserver = null
		api = null
		p2p = null
		authHandler = null
		startupKey = null
		port = 0
	}

	override fun onDestroy() {
		cleanup()
		super.onDestroy()
	}

	override fun onBind(intent: Intent?): IBinder? = null
	private fun createNotificationChannel() {
		val manager = getSystemService(NotificationManager::class.java)
		try {
			manager.deleteNotificationChannel("graffiti_service_channel")
			manager.deleteNotificationChannel("graffiti_service_channel_v2")
			manager.deleteNotificationChannel("graffiti_service_channel_v3")
			manager.deleteNotificationChannel("graffiti_service_channel_v4")
		} catch (_: Exception) {
		}
		val serviceChannel = NotificationChannel(
			CHANNEL_ID,
			"Graffiti Service Channel",
			NotificationManager.IMPORTANCE_LOW
		).apply {
			description = "Graffiti background service"
			setShowBadge(false)
			setSound(null, null)
			enableVibration(false)
		}
		manager.createNotificationChannel(serviceChannel)

		val messageChannel = NotificationChannel(
			MESSAGE_CHANNEL_ID,
			"Graffiti Messages",
			NotificationManager.IMPORTANCE_DEFAULT
		).apply {
			description = "Notifications for new unread messages"
			setShowBadge(true)
		}
		manager.createNotificationChannel(messageChannel)
	}

	private fun createServiceNotification(): Notification {
		val mainActivityIntent = Intent(this, WebViewActivity::class.java)
		val mainActivityPendingIntent = PendingIntent.getActivity(
			this, 0, mainActivityIntent,
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
		)

		return NotificationCompat.Builder(this, CHANNEL_ID)
			.setContentTitle("Graffiti")
			.setContentText("P2P Node is active")
			.setSmallIcon(R.mipmap.ic_launcher_foreground)
			.setContentIntent(mainActivityPendingIntent)
			.setOngoing(true)
			.setSilent(true)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.setCategory(Notification.CATEGORY_SERVICE)
			.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
			.build()
	}

	private fun createMessageNotification(count: Int, silent: Boolean): Notification {
		val mainActivityIntent = Intent(this, WebViewActivity::class.java)
		val mainActivityPendingIntent = PendingIntent.getActivity(
			this, 0, mainActivityIntent,
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
		)
		val contentText = if (count == 1) "1 new message" else "$count new messages"

		return NotificationCompat.Builder(this, MESSAGE_CHANNEL_ID)
			.setContentTitle("Graffiti")
			.setContentText(contentText)
			.setNumber(count)
			.setSmallIcon(R.mipmap.ic_launcher_foreground)
			.setContentIntent(mainActivityPendingIntent)
			.setAutoCancel(true)
			.setSilent(silent)
			.setPriority(NotificationCompat.PRIORITY_DEFAULT)
			.setCategory(Notification.CATEGORY_MESSAGE)
			.build()
	}
}
