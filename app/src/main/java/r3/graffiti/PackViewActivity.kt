package r3.graffiti

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import r3.encryption.EncryptedSource
import r3.graffiti.ui.theme.GraffitiTheme
import r3.hash.hash256
import r3.math.EncryptedSequence
import r3.pack.BinaryPack
import r3.pack.Pack
import r3.pke.Password256
import r3.source.FileSource
import r3.source.Source
import java.io.File
import android.graphics.Color as AndroidColor

class PackViewActivity : ComponentActivity() {
	private var intentUri = mutableStateOf<Uri?>(null)
	private var intentFilePath = mutableStateOf<String?>(null)
	override fun onCreate(savedInstanceState: Bundle?) {
		enableEdgeToEdge()
		super.onCreate(savedInstanceState)

		handleIntent(intent)

		setContent {
			GraffitiTheme {
				Surface(
					modifier = Modifier.fillMaxSize(),
					color = MaterialTheme.colorScheme.background
				) {
					Box(modifier = Modifier.safeDrawingPadding()) {
						PackViewerScreen(
							uri = intentUri.value,
							filePath = intentFilePath.value,
							onClose = { finish() }
						)
					}
				}
			}
		}
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		handleIntent(intent)
	}

	private fun handleIntent(intent: Intent?) {
		if (intent == null) return
		val path = intent.getStringExtra("packPath")
		if (!path.isNullOrEmpty()) {
			intentFilePath.value = path
			intentUri.value = null
			return
		}

		if (intent.action == Intent.ACTION_VIEW || intent.data != null) {
			val uri = intent.data
			if (uri != null) {
				if (uri.scheme == "file") {
					intentFilePath.value = uri.path
					intentUri.value = null
				} else {
					intentUri.value = uri
					intentFilePath.value = null
				}
			}
		}
	}

	@SuppressLint("SetJavaScriptEnabled")
	@Composable
	fun PackViewerScreen(
		uri: Uri?,
		filePath: String?,
		onClose: () -> Unit
	) {
		val context = LocalContext.current
		var showPasswordDialog by remember { mutableStateOf(false) }
		var errorMessage by remember { mutableStateOf<String?>(null) }
		var isLoading by remember { mutableStateOf(false) }
		val coroutineScope = rememberCoroutineScope()
		var customView by remember { mutableStateOf<View?>(null) }
		var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
		val listeningPort = PackHolder.listeningPort
		val currentUrl = if (listeningPort != 0) "http://localhost:$listeningPort/" else null
		fun processPackLoad(password: String? = null) {
			coroutineScope.launch {
				isLoading = true
				errorMessage = null
				try {
					loadPackSource(uri, filePath, password)
				} catch (e: Exception) {
					val msg = e.message ?: "Failed to load pack"
					if (msg == "PASSWORD_REQUIRED" || msg == "INVALID_PASSWORD") {
						showPasswordDialog = true
						if (msg == "INVALID_PASSWORD") {
							errorMessage = "Invalid password. Please try again."
						}
					} else {
						errorMessage = msg
					}
				} finally {
					isLoading = false
				}
			}
		}

		LaunchedEffect(uri, filePath) {
			if (uri != null || filePath != null) {
				val fileName = getFileName(uri, filePath)
				if (fileName.endsWith(".epack", ignoreCase = true)) {
					showPasswordDialog = true
				} else {
					processPackLoad(null)
				}
			}
		}

		if (showPasswordDialog) {
			var password by remember { mutableStateOf("") }
			AlertDialog(
				onDismissRequest = {
					showPasswordDialog = false
					if (listeningPort == 0) {
						onClose()
					}
				},
				title = { Text("Enter Password") },
				text = {
					Column {
						OutlinedTextField(
							value = password,
							onValueChange = { password = it },
							label = { Text("Password") },
							singleLine = true
						)
						if (errorMessage != null) {
							Spacer(modifier = Modifier.height(8.dp))
							Text(
								text = errorMessage!!,
								color = MaterialTheme.colorScheme.error,
								style = MaterialTheme.typography.bodySmall
							)
						}
					}
				},
				confirmButton = {
					TextButton(onClick = {
						showPasswordDialog = false
						processPackLoad(password)
					}) {
						Text("OK")
					}
				},
				dismissButton = {
					TextButton(onClick = {
						showPasswordDialog = false
						if (listeningPort == 0) {
							onClose()
						}
					}) {
						Text("Cancel")
					}
				}
			)
		}

		if (currentUrl != null) {
			BackHandler {
				if (customView != null) {
					customViewCallback?.onCustomViewHidden()
				} else {
					stopPlaybackService()
					onClose()
				}
			}
			Box(
				modifier = Modifier
					.fillMaxSize()
					.background(Color.Black)
			) {
				AndroidView(
					factory = { ctx ->
						WebView(ctx).apply {
							layoutParams = ViewGroup.LayoutParams(
								ViewGroup.LayoutParams.MATCH_PARENT,
								ViewGroup.LayoutParams.MATCH_PARENT
							)
							setBackgroundColor(AndroidColor.BLACK)
							settings.apply {
								javaScriptEnabled = true
								allowFileAccess = true
								domStorageEnabled = true
								mediaPlaybackRequiresUserGesture = false
							}
							webViewClient = WebViewClient()
							webChromeClient = object : WebChromeClient() {
								override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
									customView = view
									customViewCallback = callback
								}

								override fun onHideCustomView() {
									customView = null
									customViewCallback = null
								}
							}
						}
					},
					update = { webView ->
						if (webView.url != currentUrl) {
							webView.loadUrl(currentUrl)
						}
					},
					onRelease = { webView ->
						webView.stopLoading()
						webView.loadUrl("about:blank")
						webView.onPause()
						webView.removeAllViews()
						webView.destroy()
					},
					modifier = Modifier.fillMaxSize()
				)

				if (customView != null) {
					AndroidView(
						factory = { ctx ->
							FrameLayout(ctx).apply {
								setBackgroundColor(AndroidColor.BLACK)
								(customView?.parent as? ViewGroup)?.removeView(customView)
								addView(customView)
							}
						},
						modifier = Modifier.fillMaxSize()
					)
				}
			}
		} else {
			Box(
				modifier = Modifier.fillMaxSize(),
				contentAlignment = Alignment.Center
			) {
				if (isLoading) {
					Column(horizontalAlignment = Alignment.CenterHorizontally) {
						CircularProgressIndicator()
						Spacer(modifier = Modifier.height(16.dp))
						Text("Opening Pack...")
					}
				} else if (errorMessage != null && !showPasswordDialog) {
					Column(horizontalAlignment = Alignment.CenterHorizontally) {
						Text(
							text = "Error: $errorMessage",
							color = MaterialTheme.colorScheme.error,
							style = MaterialTheme.typography.bodyMedium
						)
						Spacer(modifier = Modifier.height(16.dp))
						Button(onClick = { onClose() }) {
							Text("Close")
						}
					}
				}
			}
		}
	}

	private suspend fun loadPackSource(uri: Uri?, filePath: String?, passwordStr: String?) {
		val pack = withContext(Dispatchers.IO) {
			val source: Source = when {
				filePath != null -> FileSource(File(filePath))
				uri != null -> UriSource(contentResolver, uri)
				else -> throw IllegalArgumentException("No file source provided")
			}
			val fileName = getFileName(uri, filePath)
			val isEncrypted = fileName.endsWith(".epack", ignoreCase = true) || !passwordStr.isNullOrEmpty()

			if (isEncrypted && passwordStr.isNullOrEmpty()) {
				throw IllegalArgumentException("PASSWORD_REQUIRED")
			}
			val loadedPack: Pack = if (isEncrypted) {
				val pass = Password256(passwordStr!!.toByteArray().hash256())
				val sequence = EncryptedSequence.createSequence(pass)
				val encryptedSrc = EncryptedSource(sequence, source)
				BinaryPack(encryptedSrc)
			} else {
				BinaryPack(source)
			}

			try {
				loadedPack.keys.size
			} catch (e: Exception) {
				throw IllegalArgumentException("INVALID_PASSWORD")
			}

			loadedPack
		}

		PackHolder.currentPack = pack
		PackHolder.currentPackName = getFileName(uri, filePath)
		val serviceIntent = Intent(this, PackMediaPlaybackService::class.java)
		ContextCompat.startForegroundService(this, serviceIntent)
	}

	private fun stopPlaybackService() {
		val intent = Intent(this, PackMediaPlaybackService::class.java).apply {
			action = PackMediaPlaybackService.ACTION_STOP
		}
		startService(intent)
	}

	private fun getFileName(uri: Uri?, filePath: String?): String {
		if (filePath != null) {
			return File(filePath).name
		}
		if (uri != null) {
			if (uri.scheme == "content") {
				try {
					contentResolver.query(uri, null, null, null, null)?.use { cursor ->
						if (cursor.moveToFirst()) {
							val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
							if (idx != -1) {
								val name = cursor.getString(idx)
								if (!name.isNullOrEmpty()) return name
							}
						}
					}
				} catch (_: Exception) {
				}
			}
			val path = uri.path
			if (path != null) {
				val idx = path.lastIndexOf('/')
				if (idx != -1) return path.substring(idx + 1)
				return path
			}
		}
		return "pack"
	}
}
