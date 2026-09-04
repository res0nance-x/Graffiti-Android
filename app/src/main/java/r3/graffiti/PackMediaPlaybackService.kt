package r3.graffiti

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import r3.content.BinaryContent
import r3.http.ContentHandler
import r3.http.HandlerFactory
import r3.http.WebServer
import r3.pack.Pack
import java.io.File

object PackHolder {
	var currentPack: Pack? = null
	var currentPackName: String = "Pack Viewer"
	var listeningPort by mutableIntStateOf(0)
}

class PackMediaPlaybackService : Service() {
	private var webServer: WebServer? = null

	companion object {
		private const val CHANNEL_ID = "pack_media_playback_channel"
		private const val NOTIFICATION_ID = 2
		const val ACTION_STOP = "r3.graffiti.ACTION_STOP_PACK_SERVICE"
	}

	override fun onBind(intent: Intent?): IBinder? = null
	override fun onCreate() {
		super.onCreate()
		createNotificationChannel()
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		val action = intent?.action
		if (action == ACTION_STOP) {
			stopSelf()
			return START_NOT_STICKY
		}
		val pack = PackHolder.currentPack
		if (pack != null) {
			val notification = createNotification()
			startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
			startWebServer(pack)
		} else {
			stopSelf()
		}

		return START_STICKY
	}

	override fun onTaskRemoved(rootIntent: Intent?) {
		super.onTaskRemoved(rootIntent)
		stopSelf()
	}

	private fun startWebServer(pack: Pack) {
		try {
			webServer?.stop()
		} catch (_: Exception) {
		}
		val tmpDir = File(cacheDir, "pack_server_tmp")
		if (!tmpDir.exists()) {
			tmpDir.mkdirs()
		}
		val ws = WebServer(null, 0, tmpDir)
		ws.handlers.add(HandlerFactory.createLogRouter())
		ws.handlers.add(HandlerFactory.createWelcomeHandler())
		ws.handlers.add(HandlerFactory.createPackHandler(pack))
		ws.handlers.add(ContentHandler { header, _ ->
			val path = header.optString("path", "/")
			if (path == "/" || path == "/index.html") {
				try {
					val bytes = assets.open("playlist/index.html").use { it.readBytes() }
					BinaryContent(bytes, "index.html", "html")
				} catch (_: Exception) {
					try {
						val bytes = assets.open("index.html").use { it.readBytes() }
						BinaryContent(bytes, "index.html", "html")
					} catch (_: Exception) {
						null
					}
				}
			} else null
		})

		ws.start(0, false)
		webServer = ws
		PackHolder.listeningPort = ws.listeningPort
	}

	private fun createNotificationChannel() {
		val serviceChannel = NotificationChannel(
			CHANNEL_ID,
			"Pack Media Playback Channel",
			NotificationManager.IMPORTANCE_LOW
		)
		val manager = getSystemService(NotificationManager::class.java)
		manager.createNotificationChannel(serviceChannel)
	}

	private fun createNotification(): Notification {
		val stopIntent = Intent(this, PackMediaPlaybackService::class.java).apply {
			action = ACTION_STOP
		}
		val stopPendingIntent = PendingIntent.getService(
			this,
			0,
			stopIntent,
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
		)
		val packName = PackHolder.currentPackName

		return NotificationCompat.Builder(this, CHANNEL_ID)
			.setContentTitle("Pack Viewer: $packName")
			.setContentText("Serving pack media content...")
			.setSmallIcon(android.R.drawable.ic_media_play)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.setOngoing(true)
			.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
			.build()
	}

	override fun onDestroy() {
		super.onDestroy()
		try {
			webServer?.stop()
		} catch (_: Exception) {
		}
		webServer = null
		PackHolder.currentPack = null
		PackHolder.listeningPort = 0
	}
}
