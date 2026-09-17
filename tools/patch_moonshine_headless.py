from pathlib import Path
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('voice-input')

service = r'''package org.futo.voiceinput

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.coroutineScope
import org.futo.voiceinput.ml.RunState
import org.futo.voiceinput.recognition.RecognitionModel

/**
 * Headless recognition endpoint for MeBoard integration.
 * No Activity or Compose UI is launched. Moonshine's normal settings continue
 * to select the active speech backend/model.
 */
class MoonshineRecognitionService : RecognitionService(), LifecycleOwner {
    companion object {
        const val KEY_STATUS = "org.futo.voiceinput.moonshine.STATUS"
        const val KEY_LANGUAGE = "org.futo.voiceinput.moonshine.LANGUAGE"
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private var clientCallback: Callback? = null
    private var speechBegan = false
    private var speechEnded = false

    private fun metadataBundle(key: String, value: String): Bundle = Bundle().apply {
        putStringArrayList(RecognizerIntent.EXTRA_RESULTS, arrayListOf())
        putString(key, value)
    }

    private fun status(text: String) {
        try { clientCallback?.partialResults(metadataBundle(KEY_STATUS, text)) } catch (_: Throwable) { }
    }

    private fun language(language: String) {
        try { clientCallback?.partialResults(metadataBundle(KEY_LANGUAGE, language)) } catch (_: Throwable) { }
    }

    private fun transcriptBundle(text: String): Bundle = Bundle().apply {
        putStringArrayList(RecognizerIntent.EXTRA_RESULTS, arrayListOf(text))
    }

    private fun finishClient() {
        clientCallback = null
        speechBegan = false
        speechEnded = false
    }

    private val session = object : RecordingSession() {
        override val context: Context
            get() = this@MoonshineRecognitionService
        override val lifecycleScope: LifecycleCoroutineScope
            get() = this@MoonshineRecognitionService.lifecycle.coroutineScope

        override fun cancelled() {
            status("Cancelled")
            finishClient()
        }

        override fun finished(result: String) {
            status("Done")
            try { clientCallback?.results(transcriptBundle(result)) } catch (_: Throwable) { }
            finishClient()
        }

        override fun failed(error: Throwable) {
            status(error.message ?: "Recognition error")
            val code = if (error is NoSpeechRecognizedException) {
                SpeechRecognizer.ERROR_NO_MATCH
            } else {
                SpeechRecognizer.ERROR_SERVER
            }
            try { clientCallback?.error(code) } catch (_: Throwable) { }
            finishClient()
        }

        override fun languageDetected(result: String) = language(result)

        override fun partialResult(result: String) {
            if (result.isBlank()) return
            try { clientCallback?.partialResults(transcriptBundle(result)) } catch (_: Throwable) { }
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
            status("Model download required")
            try { clientCallback?.error(SpeechRecognizer.ERROR_SERVER) } catch (_: Throwable) { }
            finishClient()
        }

        override fun needParakeetModelDownload() = modelUnavailable()
        override fun needRecognitionModelDownload(model: RecognitionModel) = modelUnavailable()
        override fun needMoonshineModelDownload() = modelUnavailable()
        override fun needWhisperModelDownload(models: List<ModelData>) = modelUnavailable()

        override fun needPermission() {
            status("Microphone permission required")
            try { clientCallback?.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) } catch (_: Throwable) { }
            finishClient()
        }

        override fun permissionRejected() = needPermission()

        override fun recordingStarted() {
            status("Listening…")
            try { clientCallback?.readyForSpeech(Bundle()) } catch (_: Throwable) { }
        }

        override fun updateMagnitude(magnitude: Float, state: MagnitudeState) {
            try { clientCallback?.rmsChanged(magnitude.coerceIn(0f, 1f) * 10f) } catch (_: Throwable) { }
            if (!speechBegan && state == MagnitudeState.TALKING) {
                speechBegan = true
                try { clientCallback?.beginningOfSpeech() } catch (_: Throwable) { }
            }
        }

        override fun processing() {
            status("Processing…")
            if (!speechEnded) {
                speechEnded = true
                try { clientCallback?.endOfSpeech() } catch (_: Throwable) { }
            }
        }

        override fun cleaning() = status("Cleaning transcript…")
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onStartListening(recognizerIntent: Intent?, callback: Callback) {
        session.reset()
        clientCallback = callback
        speechBegan = false
        speechEnded = false
        status("Initializing…")
        session.create()
    }

    override fun onStopListening(callback: Callback) {
        if (clientCallback == null) clientCallback = callback
        status("Processing…")
        session.finishRecognizerIfRecording()
    }

    override fun onCancel(callback: Callback) {
        if (clientCallback == null) clientCallback = callback
        session.cancelRecognizer()
        finishClient()
    }

    override fun onDestroy() {
        session.reset()
        finishClient()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }
}
'''
path = root / 'app/src/main/java/org/futo/voiceinput/MoonshineRecognitionService.kt'
path.write_text(service)

manifest = root / 'app/src/main/AndroidManifest.xml'
s = manifest.read_text()
marker = '<service\n            android:name=".VoiceInputMethodService"'
if 'android:name=".MoonshineRecognitionService"' not in s:
    idx = s.find(marker)
    if idx < 0:
        raise SystemExit('VoiceInputMethodService manifest anchor not found')
    service_xml = '''<service
            android:name=".MoonshineRecognitionService"
            android:exported="true"
            android:permission="android.permission.BIND_SPEECH_RECOGNITION_SERVICE">
            <intent-filter>
                <action android:name="android.speech.RecognitionService" />
            </intent-filter>
        </service>
        '''
    s = s[:idx] + service_xml + s[idx:]
manifest.write_text(s)

blue = r'''package org.futo.voiceinput.theme.presets

import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import org.futo.voiceinput.R
import org.futo.voiceinput.theme.ThemeOption
import org.futo.voiceinput.theme.selector.ThemePreview

private val colorScheme = darkColorScheme(
    primary = Color(0xFF5E97F6), onPrimary = Color(0xFF1E3D72),
    primaryContainer = Color(0xFF37568B), onPrimaryContainer = Color(0xFFDDEAFF),
    secondary = Color(0xFFC2CCDC), onSecondary = Color(0xFF2D3541),
    secondaryContainer = Color(0xFF444C58), onSecondaryContainer = Color(0xFFDEE8F8),
    tertiary = Color(0xFFB8CDEF), onTertiary = Color(0xFF253349),
    tertiaryContainer = Color(0xFF3B4A63), onTertiaryContainer = Color(0xFFD8E7FF),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFF8F9399), background = Color(0xFF000000),
    onBackground = Color(0xFFE6E1E5), surface = Color(0xFF000000),
    onSurface = Color(0xFFE6E1E5), surfaceVariant = Color(0xFF454A4F),
    onSurfaceVariant = Color(0xFFC4CAD0), inverseSurface = Color(0xFFE6E1E5),
    inverseOnSurface = Color(0xFF313033), inversePrimary = Color(0xFF5070A4),
    surfaceTint = Color(0xFF5E97F6), outlineVariant = Color(0xFF454A4F),
    scrim = Color(0xFF000000)
)

val AMOLEDDarkBlue = ThemeOption(
    dynamic = false,
    key = "AMOLEDDarkBlue",
    name = R.string.amoled_dark_blue_theme_name,
    available = { true }
) { colorScheme }

@Composable @Preview
private fun PreviewTheme() { ThemePreview(AMOLEDDarkBlue) }
'''
(root / 'app/src/main/java/org/futo/voiceinput/theme/presets/AMOLEDDarkBlue.kt').write_text(blue)

opts = root / 'app/src/main/java/org/futo/voiceinput/theme/ThemeOptions.kt'
s = opts.read_text()
if 'import org.futo.voiceinput.theme.presets.AMOLEDDarkBlue' not in s:
    s = s.replace('import org.futo.voiceinput.theme.presets.AMOLEDDarkPurple\n', 'import org.futo.voiceinput.theme.presets.AMOLEDDarkBlue\nimport org.futo.voiceinput.theme.presets.AMOLEDDarkPurple\n', 1)
if 'AMOLEDDarkBlue.key to AMOLEDDarkBlue' not in s:
    s = s.replace('    AMOLEDDarkPurple.key to AMOLEDDarkPurple,\n', '    AMOLEDDarkBlue.key to AMOLEDDarkBlue,\n    AMOLEDDarkPurple.key to AMOLEDDarkPurple,\n', 1)
if '    AMOLEDDarkBlue.key,\n' not in s:
    s = s.replace('    AMOLEDDarkPurple.key,\n', '    AMOLEDDarkBlue.key,\n    AMOLEDDarkPurple.key,\n', 1)
opts.write_text(s)

strings = root / 'app/src/main/res/values/strings.xml'
s = strings.read_text()
if 'name="amoled_dark_blue_theme_name"' not in s:
    s = s.replace('</resources>', '    <string name="amoled_dark_blue_theme_name">AMOLED Dark Blue</string>\n</resources>', 1)
strings.write_text(s)

gradle = root / 'app/build.gradle'
s = gradle.read_text()
s = s.replace('versionCode 48', 'versionCode 49', 1)
s = s.replace('versionName "1.4.2-beta.13"', 'versionName "1.4.2-beta.13-meboard1"', 1)
gradle.write_text(s)

print('Moonshine headless RecognitionService patch applied')
