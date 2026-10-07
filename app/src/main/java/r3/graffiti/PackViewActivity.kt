package r3.graffiti

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import r3.encryption.EncryptedSource
import r3.hash.hash256
import r3.math.EncryptedSequence
import r3.pack.BinaryPack
import r3.pack.Pack
import r3.pke.Password256
import r3.source.FileSource
import r3.source.Source
import java.io.File

class PackViewActivity : ComponentActivity() {

	private var intentUri: Uri? = null
	private var intentFilePath: String? = null

	private lateinit var rootLayout: FrameLayout
	private lateinit var loadingLayout: LinearLayout
	private lateinit var errorLayout: LinearLayout
	private lateinit var errorTextView: TextView
	private lateinit var webView: WebView

	private var customView: View? = null
	private var customViewCallback: WebChromeClient.CustomViewCallback? = null
	private var customViewContainer: FrameLayout? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		enableEdgeToEdge()
		super.onCreate(savedInstanceState)

		handleIntent(intent)
		setupViews()
		setupBackNavigation()
		initiatePackLoad()
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		handleIntent(intent)
		initiatePackLoad()
	}

	private fun handleIntent(intent: Intent?) {
		if (intent == null) return
		val path = intent.getStringExtra("packPath")
		if (!path.isNullOrEmpty()) {
			intentFilePath = path
			intentUri = null
			return
		}

		if (intent.action == Intent.ACTION_VIEW || intent.data != null) {
			val uri = intent.data
			if (uri != null) {
				if (uri.scheme == "file") {
					intentFilePath = uri.path
					intentUri = null
				} else {
					intentUri = uri
					intentFilePath = null
				}
			}
		}
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

			val spinner = ProgressBar(this@PackViewActivity).apply {
				isIndeterminate = true
			}
			val textView = TextView(this@PackViewActivity).apply {
				text = "Opening Pack..."
				setTextColor(Color.WHITE)
				textSize = 16f
				setPadding(0, 32, 0, 0)
			}

			addView(spinner)
			addView(textView)
		}
		rootLayout.addView(loadingLayout)

		errorLayout = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			gravity = Gravity.CENTER
			visibility = View.GONE
			layoutParams = FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT,
				FrameLayout.LayoutParams.MATCH_PARENT
			)

			errorTextView = TextView(this@PackViewActivity).apply {
				setTextColor(Color.RED)
				textSize = 16f
				gravity = Gravity.CENTER
				setPadding(32, 0, 32, 32)
			}
			val closeButton = Button(this@PackViewActivity).apply {
				text = "Close"
				setOnClickListener { finish() }
			}

			addView(errorTextView)
			addView(closeButton)
		}
		rootLayout.addView(errorLayout)

		webView = WebView(this).apply {
			layoutParams = FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT,
				FrameLayout.LayoutParams.MATCH_PARENT
			)
			setBackgroundColor(Color.BLACK)
			visibility = View.GONE
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
					customViewContainer = FrameLayout(this@PackViewActivity).apply {
						setBackgroundColor(Color.BLACK)
						addView(view, FrameLayout.LayoutParams(
							FrameLayout.LayoutParams.MATCH_PARENT,
							FrameLayout.LayoutParams.MATCH_PARENT
						))
					}
					rootLayout.addView(customViewContainer)
					webView.visibility = View.GONE
				}

				override fun onHideCustomView() {
					customViewContainer?.let { rootLayout.removeView(it) }
					customViewContainer = null
					customView = null
					customViewCallback = null
					webView.visibility = View.VISIBLE
				}
			}
		}
		rootLayout.addView(webView)

		setContentView(rootLayout)
	}

	private fun setupBackNavigation() {
		onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				if (customView != null) {
					customViewCallback?.onCustomViewHidden()
				} else {
					stopPlaybackService()
					finish()
				}
			}
		})
	}

	private fun initiatePackLoad() {
		if (intentUri != null || intentFilePath != null) {
			val fileName = getFileName(intentUri, intentFilePath)
			if (fileName.endsWith(".epack", ignoreCase = true)) {
				showPasswordPrompt(null)
			} else {
				processPackLoad(null)
			}
		} else {
			waitForExistingPackServer()
		}
	}

	private fun waitForExistingPackServer() {
		loadingLayout.visibility = View.VISIBLE
		errorLayout.visibility = View.GONE
		lifecycleScope.launch {
			var port = PackHolder.listeningPort
			var attempts = 0
			while (port == 0 && attempts < 50) {
				delay(100.milliseconds)
				port = PackHolder.listeningPort
				attempts++
			}
			if (port != 0) {
				loadingLayout.visibility = View.GONE
				webView.visibility = View.VISIBLE
				if (webView.url != "http://localhost:$port/") {
					webView.loadUrl("http://localhost:$port/")
				}
			} else {
				loadingLayout.visibility = View.GONE
				errorLayout.visibility = View.VISIBLE
				errorTextView.text = "Error: Pack playback service not responding"
			}
		}
	}

	private fun showPasswordPrompt(previousError: String?) {
		val input = EditText(this).apply {
			inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
			hint = "Password"
			setPadding(48, 24, 48, 24)
		}

		val container = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(32, 16, 32, 0)
			if (previousError != null) {
				val errView = TextView(this@PackViewActivity).apply {
					text = previousError
					setTextColor(Color.RED)
					setPadding(48, 8, 48, 16)
				}
				addView(errView)
			}
			addView(input)
		}

		AlertDialog.Builder(this)
			.setTitle("Enter Password")
			.setView(container)
			.setPositiveButton("OK") { _, _ ->
				val password = input.text.toString()
				processPackLoad(password)
			}
			.setNegativeButton("Cancel") { _, _ ->
				if (PackHolder.listeningPort == 0) {
					finish()
				}
			}
			.setOnCancelListener {
				if (PackHolder.listeningPort == 0) {
					finish()
				}
			}
			.show()
	}

	private fun processPackLoad(password: String?) {
		loadingLayout.visibility = View.VISIBLE
		errorLayout.visibility = View.GONE

		lifecycleScope.launch {
			try {
				loadPackSource(intentUri, intentFilePath, password)
				var listeningPort = PackHolder.listeningPort
				var attempts = 0
				while (listeningPort == 0 && attempts < 50) {
					delay(100.milliseconds)
					listeningPort = PackHolder.listeningPort
					attempts++
				}
				if (listeningPort != 0) {
					loadingLayout.visibility = View.GONE
					errorLayout.visibility = View.GONE
					webView.apply {
						visibility = View.VISIBLE
						loadUrl("http://localhost:$listeningPort/")
					}
				} else {
					loadingLayout.visibility = View.GONE
					errorLayout.visibility = View.VISIBLE
					errorTextView.text = "Error: Pack playback service not responding"
				}
			} catch (e: Exception) {
				val msg = e.message ?: "Failed to load pack"
				loadingLayout.visibility = View.GONE
				if (msg == "PASSWORD_REQUIRED" || msg == "INVALID_PASSWORD") {
					val errDesc = if (msg == "INVALID_PASSWORD") "Invalid password. Please try again." else null
					showPasswordPrompt(errDesc)
				} else {
					errorLayout.visibility = View.VISIBLE
					errorTextView.text = "Error: $msg"
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
				loadedPack.size
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

	override fun onDestroy() {
		super.onDestroy()
		webView.apply {
			stopLoading()
			loadUrl("about:blank")
			onPause()
			removeAllViews()
			destroy()
		}
	}
}
