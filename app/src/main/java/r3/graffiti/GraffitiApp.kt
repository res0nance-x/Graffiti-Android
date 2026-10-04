package r3.graffiti

import android.app.Application
import android.util.Log

class GraffitiApp : Application() {
	companion object {
		const val TAG = "Graffiti"

		fun initLogging() {
			r3.io.log = { msg -> Log.i(TAG, msg) }
			r3.io.debug = { msg -> Log.d(TAG, msg) }
			r3.io.logError = { msg, tr ->
				if (tr != null) {
					Log.e(TAG, msg, tr)
				} else {
					Log.e(TAG, msg)
				}
			}
		}

		init {
			initLogging()
		}
	}

	override fun onCreate() {
		super.onCreate()
		initLogging()
	}
}
