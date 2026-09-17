from pathlib import Path
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("voice-input")

service = r'''package org.futo.voiceinput

import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.coroutineScope
import org.futo.voiceinput.ml.RunState
import org.futo.voiceinput.recognition.RecognitionModel

/**
 * Private integration endpoint used by MeBoard.
 *
 * MeBoard owns microphone capture and the keyboard/editor lifecycle.
 * This service owns Moonshine model selection/inference and returns recognition
 * callbacks over Messenger. Moonshine's normal settings remain authoritative.
 */
class MeboardEngineService : Service(), LifecycleOwner {
    companion object {
        const val CMD_START = 1
        const val CMD_STOP = 2
        const val CMD_CANCEL = 3

        const val EVT_STATUS = 100
        const val EVT_READY = 101
        const val EVT_RMS = 102
        const val EVT_PARTIAL = 103
        const val EVT_FINAL = 104
        const val EVT_ERROR = 105
        const val EVT_END = 106
        const val EVT_CANCELLED = 107

        const val KEY_AUDIO = "audio"
        const val KEY_SAMPLE_RATE = "sample_rate"
        const val KEY_CHANNELS = "channels"
        const val KEY_ENCODING = "encoding"
        const val KEY_TEXT = "text"
        const val KEY_STATUS = "status"
        const val KEY_RMS = "rms"
        const val KEY_ERROR_CODE = "error_code"
        const val KEY_ERROR_MESSAGE = "error_message"
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private var client: Messenger? = null

    private fun send(what: Int, data: Bundle = Bundle()) {
        try {
            val msg = Message.obtain(null, what)
            msg.data = data
            client?.send(msg)
        } catch (_: Throwable) {
        }
    }

    private fun status(text: String) {
        send(EVT_STATUS, Bundle().apply { putString(KEY_STATUS, text) })
    }

    private fun error(code: Int, text: String) {
        send(EVT_ERROR, Bundle().apply {
            putInt(KEY_ERROR_CODE, code)
            putString(KEY_ERROR_MESSAGE, text)
        })
    }

    private val session = object : RecordingSession() {
        override val context
            get() = this@MeboardEngineService

        override val lifecycleScope: LifecycleCoroutineScope
            get() = this@MeboardEngineService.lifecycle.coroutineScope

        override fun cancelled() {
            status("Cancelled")
            send(EVT_CANCELLED)
            client = null
        }

        override fun finished(result: String) {
            status("Done")
            send(EVT_FINAL, Bundle().apply { putString(KEY_TEXT, result) })
            client = null
        }

        override fun failed(error: Throwable) {
            val code = if (error is NoSpeechRecognizedException) 7 else 5
            val text = if (error is NoSpeechRecognizedException) {
                "No speech recognized"
            } else {
                error.message ?: "Recognition error"
            }
            this@MeboardEngineService.error(code, text)
            client = null
        }

        override fun languageDetected(result: String) {
            // MeBoard currently follows the active editor language.
        }

        override fun partialResult(result: String) {
            if (result.isNotBlank()) {
                send(EVT_PARTIAL, Bundle().apply { putString(KEY_TEXT, result) })
            }
        }

        override fun decodingStatus(status: RunState) {
            val text = when (status.name) {
                "Streaming" -> "Listening…"
                "CatchingUp" -> "Catching up…"
                "SwitchingModel" -> "Switching model…"
                "OOMError" -> "Out of memory"
                else -> "Processing…"
            }
            status(text)
        }

        override fun loading() = status("Initializing…")

        private fun modelUnavailable() {
            error(5, "Model download required")
            client = null
        }

        override fun needParakeetModelDownload() = modelUnavailable()
        override fun needRecognitionModelDownload(model: RecognitionModel) = modelUnavailable()
        override fun needMoonshineModelDownload() = modelUnavailable()
        override fun needWhisperModelDownload(models: List<ModelData>) = modelUnavailable()

        override fun needPermission() {
            // MeBoard owns microphone capture, so Moonshine should never request mic permission here.
            error(9, "Unexpected microphone permission request")
            client = null
        }

        override fun permissionRejected() = needPermission()

        override fun recordingStarted() {
            status("Listening…")
            send(EVT_READY)
        }

        override fun updateMagnitude(magnitude: Float, state: MagnitudeState) {
            send(EVT_RMS, Bundle().apply { putFloat(KEY_RMS, magnitude) })
        }

        override fun processing() {
            status("Processing…")
            send(EVT_END)
        }

        override fun cleaning() = status("Cleaning transcript…")
    }

    @Suppress("DEPRECATION")
    private fun parcelAudio(data: Bundle): ParcelFileDescriptor? =
        if (Build.VERSION.SDK_INT >= 33) {
            data.getParcelable(KEY_AUDIO, ParcelFileDescriptor::class.java)
        } else {
            data.getParcelable(KEY_AUDIO)
        }

    private fun startSession(msg: Message) {
        session.reset()
        client = msg.replyTo

        val data = msg.data ?: Bundle()
        val audio = parcelAudio(data)
        if (audio == null) {
            error(5, "MeBoard audio stream missing")
            client = null
            return
        }

        val sampleRate = data.getInt(KEY_SAMPLE_RATE, 16000)
        val channels = data.getInt(KEY_CHANNELS, 1)
        val encoding = data.getInt(KEY_ENCODING, AudioFormat.ENCODING_PCM_16BIT)

        session.createFromAudioSource(audio, sampleRate, channels, encoding)
    }

    private val incoming = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                CMD_START -> startSession(msg)
                CMD_STOP -> {
                    status("Processing…")
                    session.finishRecognizerIfRecording()
                }
                CMD_CANCEL -> {
                    session.cancelRecognizer()
                    client = null
                }
                else -> super.handleMessage(msg)
            }
        }
    }

    private val messenger = Messenger(incoming)

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        session.reset()
        client = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        session.reset()
        client = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }
}
'''

path = root / "app/src/main/java/org/futo/voiceinput/MeboardEngineService.kt"
path.write_text(service)

manifest = root / "app/src/main/AndroidManifest.xml"
m = manifest.read_text()
if 'android:name=".MeboardEngineService"' not in m:
    anchor = '<service\n            android:name=".VoiceInputMethodService"'
    idx = m.find(anchor)
    if idx < 0:
        raise SystemExit("VoiceInputMethodService manifest anchor not found")
    block = '''<service
            android:name=".MeboardEngineService"
            android:exported="true"
            android:stopWithTask="false" />
        '''
    m = m[:idx] + block + m[idx:]
manifest.write_text(m)

gradle = root / "app/build.gradle"
g = gradle.read_text()
g = g.replace("versionCode 53", "versionCode 54", 1)
g = g.replace(
    'versionName "1.4.2-beta.13-meboard5-injected-audio"',
    'versionName "1.4.2-beta.13-meboard6-session-ipc"',
    1,
)
gradle.write_text(g)

print("MeBoard engine Messenger service patch applied")
