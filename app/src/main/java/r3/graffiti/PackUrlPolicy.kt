package r3.graffiti

import java.net.URI

object PackUrlPolicy {
	private val OPENABLE_EXTERNAL_SCHEMES = setOf("http", "https", "mailto", "tel")

	fun isInternalPackUrl(url: String?, allowedPort: Int): Boolean {
		if (url.isNullOrBlank()) return false
		val trimmed = url.trim()
		val colonIdx = trimmed.indexOf(':')
		if (colonIdx <= 0) return false
		val scheme = trimmed.substring(0, colonIdx).lowercase()

		return when (scheme) {
			"about" -> trimmed.equals("about:blank", ignoreCase = true)
			"data", "blob", "javascript" -> true
			"http" -> {
				if (allowedPort <= 0) return false
				try {
					val uri = URI(trimmed)
					if (uri.userInfo != null || uri.rawUserInfo != null) return false
					val rawHost = uri.host ?: return false
					val host = rawHost.lowercase().removePrefix("[").removeSuffix("]")
					val isLoopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
					isLoopback && uri.port == allowedPort
				} catch (_: Exception) {
					false
				}
			}
			else -> false
		}
	}

	fun isPackViewerUrl(
		url: String?,
		allowedPort: Int,
		viewerPath: String = "/__pack_viewer__"
	): Boolean {
		if (!isInternalPackUrl(url, allowedPort)) return false
		val trimmed = url!!.trim()
		if (!trimmed.lowercase().startsWith("http:")) return false
		return try {
			URI(trimmed).path == viewerPath
		} catch (_: Exception) {
			false
		}
	}

	fun isOpenableExternalUrl(url: String?): Boolean {
		if (url.isNullOrBlank()) return false
		val trimmed = url.trim()
		val colonIdx = trimmed.indexOf(':')
		if (colonIdx <= 0) return false
		val scheme = trimmed.substring(0, colonIdx).lowercase()
		if (scheme !in OPENABLE_EXTERNAL_SCHEMES) return false

		if (scheme == "http" || scheme == "https") {
			return try {
				val uri = URI(trimmed)
				!uri.host.isNullOrBlank()
			} catch (_: Exception) {
				false
			}
		}

		return trimmed.substring(colonIdx + 1).isNotBlank()
	}
}
