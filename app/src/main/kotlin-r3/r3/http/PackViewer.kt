package r3.http

import org.nanohttpd.protocols.http.response.Response
import r3.content.BinaryContent
import r3.content.Content
import r3.org.json.JSONObject
import r3.pack.Pack
import r3.pack.RAMPack

object PackViewer {
	const val RESOURCE_PATH = "playlist/index.html"
	const val VIEWER_PATH = "/__pack_viewer__"
	const val INFO_PATH = "/__pack_viewer_info__"
	const val CSP =
		"default-src 'self' data: blob: 'unsafe-inline' 'unsafe-eval'; img-src 'self' data: blob:; media-src 'self' data: blob:; connect-src 'self'; font-src 'self' data:; object-src 'none'; base-uri 'self';"

	private val defaultViewerBytes: ByteArray by lazy {
		val stream = Thread.currentThread().contextClassLoader?.getResourceAsStream(RESOURCE_PATH)
			?: PackViewer::class.java.classLoader?.getResourceAsStream(RESOURCE_PATH)
			?: ClassLoader.getSystemResourceAsStream(RESOURCE_PATH)
		stream?.use { it.readBytes() }
			?: error("Failed to load built-in Pack Viewer resource from classpath ($RESOURCE_PATH)")
	}

	@JvmStatic
	fun loadViewerHtmlBytes(): ByteArray = defaultViewerBytes.copyOf()

	@JvmStatic
	fun loadViewerContent(): BinaryContent = BinaryContent(defaultViewerBytes, "index.html", "html")

	@JvmStatic
	fun createDefaultTemplatePack(): Pack = RAMPack(listOf(loadViewerContent()))

	@JvmOverloads
	@JvmStatic
	fun createHandlers(
		pack: Pack,
		packName: String = "Pack Viewer",
		loose: Boolean = true,
		templatePack: Pack? = null,
		overrideTemplate: Boolean = false,
		serveViewerAtRoot: Boolean = false,
		logErrorsOnly: Boolean = false,
		viewerContentProvider: () -> Content? = { loadViewerContent() }
	): List<ContentHandler> {
		val infoBytes = JSONObject().put("name", packName).toString().toByteArray(Charsets.UTF_8)
		val useRootViewer = serveViewerAtRoot && templatePack == null

		val handlers = ArrayList<ContentHandler>()
		handlers.add(HandlerFactory.createLogRouter(errorsOnly = logErrorsOnly))
		handlers.add(object : ContentHandler {
			override fun handle(header: JSONObject, content: Content?): Content? {
				return when (header.optString("path", "")) {
					VIEWER_PATH -> viewerContentProvider()
					"/" -> if (useRootViewer) viewerContentProvider() else null
					INFO_PATH -> BinaryContent(infoBytes, "pack_info.json", "json")
					else -> null
				}
			}

			override fun onResponse(header: JSONObject, response: Response) {
				val path = header.optString("path", "")
				if (path == VIEWER_PATH || (useRootViewer && path == "/")) {
					response.addHeader("Content-Security-Policy", CSP)
				}
			}
		})

		if (!useRootViewer) {
			handlers.add(HandlerFactory.createWelcomeHandler())
		}

		if (overrideTemplate && templatePack != null) {
			val templateHandler = HandlerFactory.createPackHandler(templatePack, loose = loose)
			handlers.add(object : ContentHandler {
				override fun handle(header: JSONObject, content: Content?): Content? {
					val key = header.optString("path", "").removePrefix("/")
					if (key == "pack_list") return null
					return templateHandler.handle(header, content)
				}

				override fun onResponse(header: JSONObject, response: Response) {
					templateHandler.onResponse(header, response)
				}
			})
			handlers.add(HandlerFactory.createPackHandler(pack, loose = loose))
		} else {
			handlers.add(HandlerFactory.createPackHandler(pack, loose = loose))
			if (templatePack != null) {
				handlers.add(HandlerFactory.createPackHandler(templatePack, loose = loose))
			}
		}

		handlers.add(ContentHandler { header, _ ->
			val path = header.optString("path", "/")
			if (path == "/" || path == "/index.html") {
				viewerContentProvider()
			} else {
				null
			}
		})

		return handlers
	}
}
