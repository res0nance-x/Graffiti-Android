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

			val fileItems = items.filter { it.uri != null }
			val textItems = items.filter { !it.text.isNullOrBlank() }

			if (fileItems.size == 1) {
				try {
					sendSharedFileApi(appContext, port, fileItems[0])
				} catch (e: Exception) {
					withContext(Dispatchers.Main) {
						Toast.makeText(appContext, "Failed to send ${fileItems[0].name}: ${e.message}", Toast.LENGTH_LONG).show()
					}
				}
			} else if (fileItems.size > 1) {
				try {
					sendSharedPackApi(appContext, port, fileItems)
				} catch (e: Exception) {
					withContext(Dispatchers.Main) {
						Toast.makeText(appContext, "Failed to send pack: ${e.message}", Toast.LENGTH_LONG).show()
					}
				}
			}

			for (item in textItems) {
				try {
					sendSharedTextApi(appContext, port, item.text!!)
				} catch (e: Exception) {
					withContext(Dispatchers.Main) {
						Toast.makeText(appContext, "Failed to send text: ${e.message}", Toast.LENGTH_LONG).show()
					}
				}
			}
		}
	}

	private suspend fun sendSharedPackApi(context: Context, port: Int, items: List<SharedItem>) {
		val firstBase = items.first().name.substringBeforeLast('.').ifBlank { "shared" }
		val packName = "${firstBase}_pack.pack"
		val encodedPackName = URLEncoder.encode(packName, "UTF-8")

		// 1. Begin pack staging session
		val beginUrl = URL("http://localhost:$port/api/pack/create/begin?name=$encodedPackName")
		val beginConn = beginUrl.openConnection() as HttpURLConnection
		beginConn.requestMethod = "PUT"
		val beginCode = beginConn.responseCode
		val beginText = if (beginCode in 200..299) {
			beginConn.inputStream.bufferedReader().readText()
		} else {
			beginConn.errorStream?.bufferedReader()?.readText() ?: ""
		}
		val beginJson = JSONObject(beginText)
		if (!beginJson.optBoolean("ok", false)) {
			val err = beginJson.optString("error", "Failed to start pack creation")
			throw Exception(err)
		}
		val sessionId = beginJson.getString("sessionId")

		try {
			// 2. Upload each file with collision-free relative names
			val usedNames = mutableSetOf<String>()
			for (item in items) {
				val rawName = item.name.ifBlank { "file" }
				var uniqueName = rawName
				var counter = 1
				val dotIdx = rawName.lastIndexOf('.')
				val base = if (dotIdx > 0) rawName.substring(0, dotIdx) else rawName
				val ext = if (dotIdx > 0) rawName.substring(dotIdx) else ""
				while (usedNames.contains(uniqueName)) {
					uniqueName = "$base ($counter)$ext"
					counter++
				}
				usedNames.add(uniqueName)

				val encodedPath = URLEncoder.encode(uniqueName, "UTF-8")
				val fileUrl = URL("http://localhost:$port/api/pack/create/file?sessionId=$sessionId&filePath=$encodedPath")
				val fileConn = fileUrl.openConnection() as HttpURLConnection
				fileConn.requestMethod = "PUT"
				fileConn.doOutput = true

				if (item.size > 0) {
					fileConn.setRequestProperty("Content-Length", item.size.toString())
					fileConn.setFixedLengthStreamingMode(item.size)
				}
				fileConn.setRequestProperty("Content-Type", "application/octet-stream")

				context.contentResolver.openInputStream(item.uri!!)?.use { input ->
					fileConn.outputStream.use { output ->
						input.copyTo(output)
					}
				} ?: throw Exception("Cannot open stream for ${item.name}")

				val fileCode = fileConn.responseCode
				val fileText = if (fileCode in 200..299) {
					fileConn.inputStream.bufferedReader().readText()
				} else {
					fileConn.errorStream?.bufferedReader()?.readText() ?: ""
				}
				val fileJson = JSONObject(fileText)
				if (!fileJson.optBoolean("ok", false)) {
					val err = fileJson.optString("error", "Failed to stage file $uniqueName")
					throw Exception(err)
				}
			}

			// 3. Finish pack creation and send message
			val finishUrl = URL("http://localhost:$port/api/pack/create/finish?sessionId=$sessionId")
			val finishConn = finishUrl.openConnection() as HttpURLConnection
			finishConn.requestMethod = "POST"
			val finishCode = finishConn.responseCode
			val finishText = if (finishCode in 200..299) {
				finishConn.inputStream.bufferedReader().readText()
			} else {
				finishConn.errorStream?.bufferedReader()?.readText() ?: ""
			}
			val finishJson = JSONObject(finishText)
			if (!finishJson.optBoolean("ok", false)) {
				val err = finishJson.optString("error", "Failed to compile pack archive")
				throw Exception(err)
			}

			withContext(Dispatchers.Main) {
				Toast.makeText(context, "Sent pack $packName (${items.size} files)", Toast.LENGTH_SHORT).show()
			}
		} catch (e: Exception) {
			// Cancel pack staging on failure
			try {
				val cancelUrl = URL("http://localhost:$port/api/pack/create/cancel?sessionId=$sessionId")
				val cancelConn = cancelUrl.openConnection() as HttpURLConnection
				cancelConn.requestMethod = "POST"
				cancelConn.responseCode
			} catch (_: Exception) {
			}
			throw e
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
