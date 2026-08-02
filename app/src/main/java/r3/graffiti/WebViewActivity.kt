package r3.graffiti

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ContextMenu
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import r3.graffiti.ui.theme.GraffitiTheme
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import kotlin.time.Duration.Companion.milliseconds

class WebViewActivity : ComponentActivity() {

	private var filePathCallback: ValueCallback<Array<Uri>>? = null

	inner class AndroidBridge {
		@JavascriptInterface
		fun download(url: String) {
			runOnUiThread {
				triggerSaveAs(url)
			}
		}
	}

	private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		if (uri != null) {
			filePathCallback?.onReceiveValue(arrayOf(uri))
		} else {
			filePathCallback?.onReceiveValue(null)
		}
		filePathCallback = null
	}

	private val permissionLauncher = registerForActivityResult(
		ActivityResultContracts.RequestMultiplePermissions()
	) { _ ->
		// Permissions handled by system, service re-checks
	}

	private var pendingDownloadUrl: String? = null
	private val fileSaverLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
		val sourceUrl = pendingDownloadUrl
		if (uri != null && sourceUrl != null) {
			downloadFileToUri(sourceUrl, uri)
		}
		pendingDownloadUrl = null
	}

	private var customView: View? = null
	private var customViewCallback: WebChromeClient.CustomViewCallback? = null
	private var webView: WebView? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		enableEdgeToEdge()
		super.onCreate(savedInstanceState)

		requestPermissions()
		startGraffitiService()

		setContent {
			GraffitiTheme {
				Surface(
					modifier = Modifier
						.fillMaxSize()
						.safeDrawingPadding(),
					color = MaterialTheme.colorScheme.background
				) {
					GraffitiApp()
				}
			}
		}

		onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				val wv = webView
				if (customView != null) {
					wv?.webChromeClient?.onHideCustomView()
				} else if (wv != null && wv.canGoBack()) {
					wv.goBack()
				} else {
					isEnabled = false
					onBackPressedDispatcher.onBackPressed()
				}
			}
		})
	}

	@Composable
	fun GraffitiApp() {
		var isReady by remember { mutableStateOf(GraffitiService.port != 0) }

		LaunchedEffect(Unit) {
			while (GraffitiService.port == 0) {
				delay(100.milliseconds)
			}
			isReady = true
		}

		if (isReady) {
			GraffitiWebView("http://localhost:${GraffitiService.port}/")
		} else {
			LoadingScreen()
		}
	}

	@Composable
	fun LoadingScreen() {
		Box(
			modifier = Modifier.fillMaxSize(),
			contentAlignment = Alignment.Center
		) {
			Column(horizontalAlignment = Alignment.CenterHorizontally) {
				CircularProgressIndicator()
				Spacer(modifier = Modifier.height(16.dp))
				Text(text = "Starting Graffiti Node...")
			}
		}
	}

	@SuppressLint("SetJavaScriptEnabled")
	@Composable
	fun GraffitiWebView(url: String) {
		AndroidView(
			factory = { ctx ->
				WebView(ctx).apply {
					layoutParams = ViewGroup.LayoutParams(
						ViewGroup.LayoutParams.MATCH_PARENT,
						ViewGroup.LayoutParams.MATCH_PARENT
					)
					settings.apply {
						javaScriptEnabled = true
						domStorageEnabled = true
						loadWithOverviewMode = true
						useWideViewPort = true
						cacheMode = WebSettings.LOAD_NO_CACHE
						mediaPlaybackRequiresUserGesture = false
					}

					clearCache(true)
					addJavascriptInterface(AndroidBridge(), "Android")
					webViewClient = WebViewClient()
					webChromeClient = object : WebChromeClient() {
						override fun onShowFileChooser(
							webView: WebView?,
							filePathCallback: ValueCallback<Array<Uri>>?,
							fileChooserParams: FileChooserParams?
						): Boolean {
							this@WebViewActivity.filePathCallback?.onReceiveValue(null)
							this@WebViewActivity.filePathCallback = filePathCallback
							filePickerLauncher.launch(arrayOf("*/*"))
							return true
						}

						override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
							if (customView != null) {
								callback?.onCustomViewHidden()
								return
							}
							customView = view
							customViewCallback = callback

							val decorView = window.decorView as FrameLayout
							decorView.addView(
								view,
								FrameLayout.LayoutParams(
									FrameLayout.LayoutParams.MATCH_PARENT,
									FrameLayout.LayoutParams.MATCH_PARENT
								)
							)

							this@WebViewActivity.webView?.visibility = View.GONE

							WindowCompat.getInsetsController(window, window.decorView).apply {
								hide(WindowInsetsCompat.Type.systemBars())
								systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
							}
						}

						override fun onHideCustomView() {
							if (customView == null) return

							val decorView = window.decorView as FrameLayout
							decorView.removeView(customView)
							customView = null
							customViewCallback?.onCustomViewHidden()

							this@WebViewActivity.webView?.visibility = View.VISIBLE

							WindowCompat.getInsetsController(window, window.decorView).apply {
								show(WindowInsetsCompat.Type.systemBars())
							}
						}
					}
					setDownloadListener { downloadUrl, _, _, _, _ ->
						triggerSaveAs(downloadUrl)
					}
					registerForContextMenu(this)
					this@WebViewActivity.webView = this
					loadUrl(url)
				}
			},
			modifier = Modifier.fillMaxSize()
		)
	}

	private fun requestPermissions() {
		val permissions = mutableListOf<String>()
		permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
		permissions.add(Manifest.permission.POST_NOTIFICATIONS)
		if (Build.VERSION.SDK_INT >= 37) { // Android 17
			permissions.add("android.permission.ACCESS_LOCAL_NETWORK")
		}

		val toRequest = permissions.filter {
			ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
		}

		if (toRequest.isNotEmpty()) {
			permissionLauncher.launch(toRequest.toTypedArray())
		}
	}

	private fun startGraffitiService() {
		val intent = Intent(this, GraffitiService::class.java)
		startService(intent)
	}

	override fun onCreateContextMenu(menu: ContextMenu?, v: View?, menuInfo: ContextMenu.ContextMenuInfo?) {
		super.onCreateContextMenu(menu, v, menuInfo)
		val wv = webView ?: return
		val result = wv.hitTestResult

		when (result.type) {
			WebView.HitTestResult.IMAGE_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
				val url = result.extra ?: return
				menu?.add("Save image as...")?.setOnMenuItemClickListener {
					triggerSaveAs(url)
					true
				}
			}

			WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
				val url = result.extra ?: return
				menu?.add("Save link as...")?.setOnMenuItemClickListener {
					triggerSaveAs(url)
					true
				}
			}
		}
	}

	private fun triggerSaveAs(url: String) {
		if (!url.startsWith("http://") && !url.startsWith("https://")) {
			Toast.makeText(this, "Unsupported protocol: ${url.substringBefore(':')}", Toast.LENGTH_SHORT).show()
			return
		}
		lifecycleScope.launch(Dispatchers.IO) {
			try {
				val connection = URL(url).openConnection() as HttpURLConnection
				connection.requestMethod = "HEAD"
				connection.connectTimeout = 3000
				connection.connect()
				val contentType = connection.contentType ?: "*/*"
				val contentDisposition = connection.getHeaderField("Content-Disposition")

				var fileName = extractFilename(contentDisposition)
				if (fileName == null) {
					fileName = URLUtil.guessFileName(url, contentDisposition, contentType)
				}

				withContext(Dispatchers.Main) {
					pendingDownloadUrl = url
					fileSaverLauncher.launch(fileName)
				}
			} catch (e: Exception) {
				withContext(Dispatchers.Main) {
					pendingDownloadUrl = url
					fileSaverLauncher.launch(URLUtil.guessFileName(url, null, null))
				}
			}
		}
	}

	private fun extractFilename(contentDisposition: String?): String? {
		if (contentDisposition == null) return null
		val regex = """filename\s*=\s*"?([^"\s;]+)"?""".toRegex(RegexOption.IGNORE_CASE)
		val matchResult = regex.find(contentDisposition)
		val encodedName = matchResult?.groups?.get(1)?.value ?: return null
		return try {
			URLDecoder.decode(encodedName, "UTF-8")
		} catch (e: Exception) {
			encodedName
		}
	}

	private fun downloadFileToUri(url: String, destination: Uri) {
		lifecycleScope.launch(Dispatchers.IO) {
			try {
				val connection = URL(url).openConnection() as HttpURLConnection
				connection.connectTimeout = 5000
				connection.connect()

				if (connection.responseCode == HttpURLConnection.HTTP_OK) {
					contentResolver.openOutputStream(destination)?.use { output ->
						connection.inputStream.use { input ->
							input.copyTo(output)
						}
					}
					withContext(Dispatchers.Main) {
						Toast.makeText(this@WebViewActivity, "File saved successfully", Toast.LENGTH_SHORT).show()
					}
				} else {
					throw Exception("Server returned code ${connection.responseCode}")
				}
			} catch (e: Exception) {
				withContext(Dispatchers.Main) {
					Toast.makeText(this@WebViewActivity, "Failed to save file: ${e.message}", Toast.LENGTH_LONG).show()
				}
			}
		}
	}
}
