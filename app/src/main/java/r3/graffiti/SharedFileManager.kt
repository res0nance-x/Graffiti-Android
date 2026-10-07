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

		if (Intent.ACTION_SEND == action || Intent.ACTION_SEND_MULTIPLE == action) {
			val sharedText = extractSharedText(intent)
			val fileUris = extractFileUris(intent)

			val isLinkWithPreview = fileUris.size == 1 && !sharedText.isNullOrBlank() &&
				(sharedText.contains("http://", ignoreCase = true) || sharedText.contains("https://", ignoreCase = true))

			if (isLinkWithPreview) {
				// Mixed link share: Web link accompanied by a single generated preview thumbnail image.
				// Extract the text only (the URL) and drop the preview thumbnail.
				newItems.add(SharedItem(text = sharedText))
			} else {
				// File share (single file or multiple files creating a pack)
				for (u in fileUris) {
					newItems.add(createSharedItemFromUri(context, u, type))
				}
				// If accompanying text is present (e.g. caption), also include it
				if (!sharedText.isNullOrBlank()) {
					newItems.add(SharedItem(text = sharedText))
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

	private fun extractSharedText(intent: Intent): String? {
		// 1. Check EXTRA_TEXT
		val extraText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()
		if (!extraText.isNullOrBlank()) {
			return extraText
		}

		// 2. Check ClipData items (text or web URIs)
		val clip = intent.clipData
		if (clip != null && clip.itemCount > 0) {
			val clipTexts = mutableListOf<String>()
			for (i in 0 until clip.itemCount) {
				val item = clip.getItemAt(i)
				val t = item.text?.toString()?.trim()
				if (!t.isNullOrBlank()) {
					clipTexts.add(t)
				} else {
					val u = item.uri
					if (u != null && (u.scheme.equals("http", ignoreCase = true) || u.scheme.equals("https", ignoreCase = true))) {
						clipTexts.add(u.toString())
					}
				}
			}
			if (clipTexts.isNotEmpty()) {
				return clipTexts.joinToString("\n")
			}
		}

		// 3. Check Intent data (web URL)
		val dataUri = intent.data
		if (dataUri != null && (dataUri.scheme.equals("http", ignoreCase = true) || dataUri.scheme.equals("https", ignoreCase = true))) {
			return dataUri.toString().trim()
		}

		return null
	}

	private fun extractFileUris(intent: Intent): List<Uri> {
		val uris = mutableListOf<Uri>()

		fun isFileUri(uri: Uri?): Boolean {
			if (uri == null) return false
			val scheme = uri.scheme?.lowercase() ?: return false
			return scheme != "http" && scheme != "https"
		}

		// 1. Single EXTRA_STREAM
		val singleUri = try {
			intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
		} catch (_: Exception) {
			null
		}
		if (isFileUri(singleUri)) {
			uris.add(singleUri!!)
		}

		// 2. Multiple EXTRA_STREAM
		val arrayUris = try {
			intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
		} catch (_: Exception) {
			null
		}
		if (!arrayUris.isNullOrEmpty()) {
			for (u in arrayUris) {
				if (isFileUri(u) && !uris.contains(u)) {
					uris.add(u)
				}
			}
		}

		// 3. ClipData URIs (if no EXTRA_STREAM found)
		if (uris.isEmpty()) {
			val clip = intent.clipData
			if (clip != null && clip.itemCount > 0) {
				for (i in 0 until clip.itemCount) {
					val u = clip.getItemAt(i).uri
					if (isFileUri(u) && !uris.contains(u)) {
						uris.add(u)
					}
				}
			}
		}

		return uris
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
			var token = GraffitiService.authToken
			var retries = 0
			while ((port == 0 || token == null) && retries < 50) {
				delay(100)
				port = GraffitiService.port
				token = GraffitiService.authToken
				retries++
			}

			if (port == 0 || token == null) {
				withContext(Dispatchers.Main) {
					Toast.makeText(appContext, "Failed to send: Node service not ready", Toast.LENGTH_SHORT).show()
				}
				return@launch
			}

			val fileItems = items.filter { it.uri != null }
			val textItems = items.filter { !it.text.isNullOrBlank() }

			if (fileItems.size == 1) {
				try {
					sendSharedFileApi(appContext, port, token, fileItems[0])
				} catch (e: Exception) {
					withContext(Dispatchers.Main) {
						Toast.makeText(appContext, "Failed to send ${fileItems[0].name}: ${e.message}", Toast.LENGTH_LONG).show()
					}
				}
			} else if (fileItems.size > 1) {
				try {
					sendSharedPackApi(appContext, port, token, fileItems)
				} catch (e: Exception) {
					withContext(Dispatchers.Main) {
						Toast.makeText(appContext, "Failed to send pack: ${e.message}", Toast.LENGTH_LONG).show()
					}
				}
			}

			for (item in textItems) {
				try {
					sendSharedTextApi(appContext, port, token, item.text!!)
				} catch (e: Exception) {
					withContext(Dispatchers.Main) {
						Toast.makeText(appContext, "Failed to send text: ${e.message}", Toast.LENGTH_LONG).show()
					}
				}
			}
		}
	}

	private suspend fun sendSharedPackApi(context: Context, port: Int, token: String, items: List<SharedItem>) {
		val firstBase = items.first().name.substringBeforeLast('.').ifBlank { "shared" }
		val packName = "${firstBase}_pack.pack"
		val encodedPackName = URLEncoder.encode(packName, "UTF-8")

		// 1. Begin pack staging session
		val beginUrl = URL("http://localhost:$port/api/pack/create/begin?name=$encodedPackName")
		val beginConn = beginUrl.openConnection() as HttpURLConnection
		beginConn.requestMethod = "PUT"
		beginConn.setRequestProperty("X-Auth-Token", token)
		val beginCode = beginConn.responseCode
		val beginText = if (beginCode in 200..299) {
			beginConn.inputStream.bufferedReader().readText()
		} else {
			beginConn.errorStream?.bufferedReader()?.readText() ?: ""
		}
		if (beginCode !in 200..299) {
			throw Exception("HTTP $beginCode: $beginText")
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
				fileConn.setRequestProperty("X-Auth-Token", token)
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
				if (fileCode !in 200..299) {
					throw Exception("HTTP $fileCode: $fileText")
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
			finishConn.setRequestProperty("X-Auth-Token", token)
			val finishCode = finishConn.responseCode
			val finishText = if (finishCode in 200..299) {
				finishConn.inputStream.bufferedReader().readText()
			} else {
				finishConn.errorStream?.bufferedReader()?.readText() ?: ""
			}
			if (finishCode !in 200..299) {
				throw Exception("HTTP $finishCode: $finishText")
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
				cancelConn.setRequestProperty("X-Auth-Token", token)
				cancelConn.responseCode
			} catch (_: Exception) {
			}
			throw e
		}
	}

	private suspend fun sendSharedFileApi(context: Context, port: Int, token: String, item: SharedItem) {
		val encodedFileName = URLEncoder.encode(item.name, "UTF-8")
		val urlStr = "http://localhost:$port/api/message/send/file?file=$encodedFileName"
		val url = URL(urlStr)
		val conn = url.openConnection() as HttpURLConnection
		conn.requestMethod = "PUT"
		conn.setRequestProperty("X-Auth-Token", token)
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
		if (responseCode !in 200..299) {
			throw Exception("HTTP $responseCode: $responseText")
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

	private suspend fun sendSharedTextApi(context: Context, port: Int, token: String, text: String) {
		val urlStr = "http://localhost:$port/api/message/send/text"
		val url = URL(urlStr)
		val conn = url.openConnection() as HttpURLConnection
		conn.requestMethod = "PUT"
		conn.setRequestProperty("X-Auth-Token", token)
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
		if (responseCode !in 200..299) {
			throw Exception("HTTP $responseCode: $responseText")
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
