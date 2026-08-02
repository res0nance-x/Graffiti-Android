package r3.graffiti

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import r3.source.Source
import java.io.InputStream

class UriSource(
	private val contentResolver: ContentResolver,
	private val uri: Uri
) : Source {
	override fun createInputStream(): InputStream {
		return contentResolver.openInputStream(uri)
			?: throw IllegalArgumentException("Cannot open input stream for $uri")
	}

	override val length: Long
		get() {
			var size = 0L
			try {
				contentResolver.query(uri, null, null, null, null)?.use { cursor ->
					if (cursor.moveToFirst()) {
						val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
						if (sizeIndex != -1) {
							size = cursor.getLong(sizeIndex)
						}
					}
				}
			} catch (_: Exception) {
				// Ignore
			}

			if (size == 0L) {
				try {
					contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
						size = fd.length
					}
				} catch (_: Exception) {
					// Ignore
				}
			}
			return size
		}
}
