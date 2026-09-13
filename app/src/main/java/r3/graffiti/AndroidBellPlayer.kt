package r3.graffiti

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import r3.io.log

class AndroidBellPlayer(private val context: Context) {

	private val soundPool = SoundPool.Builder()
		.setMaxStreams(3)
		.setAudioAttributes(
			AudioAttributes.Builder()
				.setUsage(AudioAttributes.USAGE_NOTIFICATION)
				.setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
				.build()
		)
		.build()

	private val soundMap = mutableMapOf<String, Int>()

	init {
		preloadSounds()
	}

	private fun preloadSounds() {
		val names = listOf("chime", "knock", "ping", "harp")
		for (name in names) {
			try {
				val afd = context.assets.openFd("sounds/$name.wav")
				val soundId = soundPool.load(afd, 1)
				soundMap[name] = soundId
			} catch (e: Exception) {
				log("Failed to preload sound $name: ${e.message}")
			}
		}
	}

	fun play(soundName: String) {
		val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
		val ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL

		when (ringerMode) {
			AudioManager.RINGER_MODE_NORMAL -> {
				val effectiveName = if (soundName.isBlank()) "chime" else soundName
				val soundId = soundMap[effectiveName] ?: soundMap["chime"]
				if (soundId != null) {
					soundPool.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f)
				}
			}
			AudioManager.RINGER_MODE_VIBRATE -> {
				vibrate()
			}
			AudioManager.RINGER_MODE_SILENT -> {
				// Muted in silent mode
			}
		}
	}

	private fun vibrate() {
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
				val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
				val vibrator = vibratorManager?.defaultVibrator
				vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 140, 100, 140), -1))
			} else {
				@Suppress("DEPRECATION")
				val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
					vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 140, 100, 140), -1))
				} else {
					@Suppress("DEPRECATION")
					vibrator?.vibrate(longArrayOf(0, 140, 100, 140), -1)
				}
			}
		} catch (e: Exception) {
			log("Vibrate failed: ${e.message}")
		}
	}

	fun release() {
		try {
			soundPool.release()
		} catch (_: Exception) {}
	}
}
