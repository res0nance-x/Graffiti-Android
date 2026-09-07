package r3.graffiti

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class SharedItem(
	val uri: Uri? = null,
	val name: String = "shared_file",
	val mimeType: String = "*/*",
	val size: Long = 0L,
	val text: String? = null
)

object SharedFileManager {
	private val pendingItems = mutableListOf<SharedItem>()

	@Synchronized
	fun handleIntent(context: Context, intent: Intent?): Boolean {
		if (intent == null) return false
		val action = intent.action ?: return false

		// Ignore standard MAIN launcher intents
		if (Intent.ACTION_MAIN == action) return false

		val type = intent.type ?: "*/*"
		val newItems = mutableListOf<SharedItem>()

		if (Intent.ACTION_SEND == action) {
			val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
			val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()

			if (uri != null) {
				newItems.add(createSharedItemFromUri(context, uri, type))
			} else if (!text.isNullOrBlank()) {
				newItems.add(SharedItem(text = text))
			}
		} else if (Intent.ACTION_SEND_MULTIPLE == action) {
			val uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
			if (uris != null) {
				for (u in uris) {
					newItems.add(createSharedItemFromUri(context, u, type))
				}
			}
		}

		if (newItems.isNotEmpty()) {
			pendingItems.addAll(newItems)
			sendPendingSharedItems(context)
			return true
		}
		return false
	}

	private fun createSharedItemFromUri(context: Context, uri: Uri, fallbackType: String): SharedItem {
		val cr = context.contentResolver
		var name = "shared_file"
		var size = 0L
		val mimeType = cr.getType(uri) ?: fallbackType

		try {
			cr.query(uri, null, null, null, null)?.use { cursor ->
				if (cursor.moveToFirst()) {
					val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
					if (nameIndex != -1) {
						val displayName = cursor.getString(nameIndex)
						if (!displayName.isNullOrBlank()) name = displayName
					}
					val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
					if (sizeIndex != -1) {
						size = cursor.getLong(sizeIndex)
					}
				}
			}
		} catch (_: Exception) {
			// fallback if query fails
		}

		return SharedItem(
			uri = uri,
			name = name,
			mimeType = mimeType,
			size = size
		)
	}

	private fun sendPendingSharedItems(context: Context) {
		val items = synchronized(this) {
			val list = ArrayList(pendingItems)
			pendingItems.clear()
			list
		}
		if (items.isEmpty()) return

		val appContext = context.applicationContext
		CoroutineScope(Dispatchers.IO).launch {
			var port = GraffitiService.port
			var retries = 0
			while (port == 0 && retries < 50) {
				delay(100)
				port = GraffitiService.port
				retries++
			}

			if (port == 0) {
				withContext(Dispatchers.Main) {
					Toast.makeText(appContext, "Failed to send: Node service not ready", Toast.LENGTH_SHORT).show()
				}
				return@launch
			}

			for (item in items) {
				try {
					if (item.uri != null) {
						sendSharedFileApi(appContext, port, item)
					} else if (!item.text.isNullOrBlank()) {
						sendSharedTextApi(appContext, port, item.text)
					}
				} catch (e: Exception) {
					withContext(Dispatchers.Main) {
						Toast.makeText(appContext, "Failed to send ${item.name}: ${e.message}", Toast.LENGTH_LONG).show()
					}
				}
			}
		}
	}

	private suspend fun sendSharedFileApi(context: Context, port: Int, item: SharedItem) {
		val encodedFileName = URLEncoder.encode(item.name, "UTF-8")
		val urlStr = "http://localhost:$port/api/message/send/file?file=$encodedFileName"
		val url = URL(urlStr)
		val conn = url.openConnection() as HttpURLConnection
		conn.requestMethod = "PUT"
		conn.doOutput = true

		if (item.size > 0) {
			conn.setRequestProperty("Content-Length", item.size.toString())
			conn.setFixedLengthStreamingMode(item.size)
		}
		conn.setRequestProperty("Content-Type", item.mimeType)

		context.contentResolver.openInputStream(item.uri!!)?.use { input ->
			conn.outputStream.use { output ->
				input.copyTo(output)
			}
		} ?: throw Exception("Cannot open file stream")

		val responseCode = conn.responseCode
		val responseText = if (responseCode in 200..299) {
			conn.inputStream.bufferedReader().readText()
		} else {
			conn.errorStream?.bufferedReader()?.readText() ?: ""
		}

		val json = JSONObject(responseText)
		if (json.optBoolean("ok", false)) {
			withContext(Dispatchers.Main) {
				Toast.makeText(context, "Sent ${item.name}", Toast.LENGTH_SHORT).show()
			}
		} else {
			val errorMsg = json.optString("error", "Unknown error")
			withContext(Dispatchers.Main) {
				Toast.makeText(context, "Failed to send ${item.name}: $errorMsg", Toast.LENGTH_LONG).show()
			}
		}
	}

	private suspend fun sendSharedTextApi(context: Context, port: Int, text: String) {
		val urlStr = "http://localhost:$port/api/message/send/text"
		val url = URL(urlStr)
		val conn = url.openConnection() as HttpURLConnection
		conn.requestMethod = "PUT"
		conn.doOutput = true
		conn.setRequestProperty("Content-Type", "text/plain; charset=UTF-8")

		val bytes = text.toByteArray(Charsets.UTF_8)
		conn.setRequestProperty("Content-Length", bytes.size.toString())
		conn.setFixedLengthStreamingMode(bytes.size.toLong())

		conn.outputStream.use { it.write(bytes) }

		val responseCode = conn.responseCode
		val responseText = if (responseCode in 200..299) {
			conn.inputStream.bufferedReader().readText()
		} else {
			conn.errorStream?.bufferedReader()?.readText() ?: ""
		}

		val json = JSONObject(responseText)
		if (json.optBoolean("ok", false)) {
			withContext(Dispatchers.Main) {
				Toast.makeText(context, "Text message sent", Toast.LENGTH_SHORT).show()
			}
		} else {
			val errorMsg = json.optString("error", "Unknown error")
			withContext(Dispatchers.Main) {
				Toast.makeText(context, "Failed to send text: $errorMsg", Toast.LENGTH_LONG).show()
			}
		}
	}
}
