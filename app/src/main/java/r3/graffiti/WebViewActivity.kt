package r3.graffiti

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

		@JavascriptInterface
		fun openUrl(url: String) {
			runOnUiThread {
				try {
					val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
						addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
					}
					startActivity(intent)
				} catch (e: Exception) {
					Toast.makeText(this@WebViewActivity, "Failed to open link: ${e.message}", Toast.LENGTH_SHORT).show()
				}
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

	private val multipleFilePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
		if (!uris.isNullOrEmpty()) {
			filePathCallback?.onReceiveValue(uris.toTypedArray())
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
	private lateinit var rootLayout: FrameLayout
	private lateinit var loadingLayout: LinearLayout

	override fun onCreate(savedInstanceState: Bundle?) {
		enableEdgeToEdge()
		super.onCreate(savedInstanceState)

		SharedFileManager.handleIntent(this, intent)

		requestPermissions()
		startGraffitiService()

		setupViews()
		setupBackNavigation()
		waitForServiceAndLoad()
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
		SharedFileManager.handleIntent(this, intent)
	}

	@SuppressLint("SetJavaScriptEnabled")
	private fun setupViews() {
		rootLayout = FrameLayout(this).apply {
			layoutParams = ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT
			)
			setBackgroundColor(Color.BLACK)
		}

		ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
			val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
			view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
			insets
		}

		loadingLayout = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			gravity = Gravity.CENTER
			layoutParams = FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT,
				FrameLayout.LayoutParams.MATCH_PARENT
			)

			val spinner = ProgressBar(this@WebViewActivity).apply {
				isIndeterminate = true
			}
			val textView = TextView(this@WebViewActivity).apply {
				text = "Starting Graffiti Node..."
				setTextColor(Color.WHITE)
				textSize = 16f
				setPadding(0, 32, 0, 0)
			}

			addView(spinner)
			addView(textView)
		}
		rootLayout.addView(loadingLayout)

		val wv = WebView(this).apply {
			layoutParams = FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT,
				FrameLayout.LayoutParams.MATCH_PARENT
			)
			setBackgroundColor(Color.BLACK)
			visibility = View.GONE
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
					if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
						multipleFilePickerLauncher.launch(arrayOf("*/*"))
					} else {
						filePickerLauncher.launch(arrayOf("*/*"))
					}
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
		}

		this.webView = wv
		rootLayout.addView(wv)
		setContentView(rootLayout)
	}

	private fun setupBackNavigation() {
		onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				val wv = webView
				if (customView != null) {
					customViewCallback?.onCustomViewHidden()
				} else if (wv != null && wv.canGoBack()) {
					wv.goBack()
				} else {
					isEnabled = false
					onBackPressedDispatcher.onBackPressed()
				}
			}
		})
	}

	private fun waitForServiceAndLoad() {
		lifecycleScope.launch {
			while (GraffitiService.port == 0) {
				delay(100.milliseconds)
			}
			val key = GraffitiService.getOrRotateStartupKey()
			val url = if (key != null) {
				"http://localhost:${GraffitiService.port}/$key"
			} else {
				"http://localhost:${GraffitiService.port}/"
			}
			loadingLayout.visibility = View.GONE
			webView?.apply {
				visibility = View.VISIBLE
				loadUrl(url)
			}
		}
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

	override fun onDestroy() {
		super.onDestroy()
		webView?.apply {
			stopLoading()
			loadUrl("about:blank")
			onPause()
			removeAllViews()
			destroy()
		}
		webView = null
	}
}
