from pathlib import Path
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("voice-input")

service = r'''package org.futo.voiceinput

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
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
import java.security.MessageDigest

/**
 * Private MeBoard <-> Moonshine inference endpoint.
 *
 * MeBoard owns microphone capture, visible keyboard UI, editor transaction,
 * and START/STOP/CANCEL lifecycle. Moonshine owns model selection/loading and
 * inference. Moonshine's existing settings remain the single source of truth.
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

        private const val TRUSTED_PACKAGE = "com.mekromn.meboard"
        private const val TRUSTED_CERT_SHA256 =
            "23e8720f08b5975b28fdda85586ab1e7e8422c64082e6a0e221f657c0b7a4e15"
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private var client: Messenger? = null

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    @Suppress("DEPRECATION")
    private fun isTrustedCaller(uid: Int): Boolean {
        if (uid <= 0) return false
        val packages = packageManager.getPackagesForUid(uid) ?: return false
        if (!packages.contains(TRUSTED_PACKAGE)) return false

        return try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
            val info = packageManager.getPackageInfo(TRUSTED_PACKAGE, flags)
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners
            } else {
                info.signatures
            } ?: return false

            signatures.any { sha256Hex(it.toByteArray()) == TRUSTED_CERT_SHA256 }
        } catch (_: Throwable) {
            false
        }
    }

    private fun reject(msg: Message) {
        try {
            val out = Message.obtain(null, EVT_ERROR)
            out.data = Bundle().apply {
                putInt(KEY_ERROR_CODE, 9)
                putString(KEY_ERROR_MESSAGE, "Untrusted MeBoard client")
            }
            msg.replyTo?.send(out)
        } catch (_: Throwable) {
        }
    }

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
            // The active Moonshine backend/model remains authoritative.
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
            if (!isTrustedCaller(msg.sendingUid)) {
                reject(msg)
                return
            }

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

# The previous generic RecognitionService bridge is intentionally removed.
# The MeBoard engine service is the only cross-app integration endpoint now.
generic = root / "app/src/main/java/org/futo/voiceinput/MoonshineRecognitionService.kt"
if generic.exists():
    generic.unlink()

manifest = root / "app/src/main/AndroidManifest.xml"
m = manifest.read_text()

old_generic_service = '''<service
            android:name=".MoonshineRecognitionService"
            android:exported="true"
            android:permission="android.permission.BIND_SPEECH_RECOGNITION_SERVICE">
            <intent-filter>
                <action android:name="android.speech.RecognitionService" />
            </intent-filter>
        </service>
        '''
m = m.replace(old_generic_service, "", 1)

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
g = g.replace("versionCode 53", "versionCode 55", 1)
g = g.replace(
    'versionName "1.4.2-beta.13-meboard5-injected-audio"',
    'versionName "1.4.2-beta.13-meboard7-secure-session-ipc"',
    1,
)
gradle.write_text(g)

print("Secure MeBoard engine Messenger service patch applied")
