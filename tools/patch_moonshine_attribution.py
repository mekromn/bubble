from pathlib import Path
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('voice-input')
service = root / 'app/src/main/java/org/futo/voiceinput/MoonshineRecognitionService.kt'
s = service.read_text()

# RecognitionService caller-attribution plumbing.
if 'import android.content.ContextParams' not in s:
    s = s.replace('import android.content.Context\n', 'import android.content.Context\nimport android.content.ContextParams\n', 1)
if 'import android.os.Build' not in s:
    s = s.replace('import android.os.Bundle\n', 'import android.os.Build\nimport android.os.Bundle\n', 1)

# Foreground microphone capture. A bound/background recognizer can otherwise
# successfully enter RECORDSTATE_RECORDING while Android delivers silence.
for imp, anchor in [
    ('import android.app.Notification\n', 'import android.content.Context\n'),
    ('import android.app.NotificationChannel\n', 'import android.app.Notification\n'),
    ('import android.app.NotificationManager\n', 'import android.app.NotificationChannel\n'),
    ('import android.content.pm.ServiceInfo\n', 'import android.content.Intent\n'),
]:
    if imp.strip() not in s:
        s = s.replace(anchor, anchor + imp, 1)

s = s.replace(
    '        const val KEY_LANGUAGE = "org.futo.voiceinput.moonshine.LANGUAGE"\n',
    '        const val KEY_LANGUAGE = "org.futo.voiceinput.moonshine.LANGUAGE"\n'
    '        private const val NOTIFICATION_CHANNEL = "meboard_headless_voice"\n'
    '        private const val NOTIFICATION_ID = 1451\n',
    1,
)

s = s.replace(
    '    private var clientCallback: Callback? = null\n',
    '    private var clientCallback: Callback? = null\n    private var sessionContext: Context? = null\n',
    1,
)

# Insert foreground helpers before metadataBundle.
anchor = '    private fun metadataBundle(key: String, value: String): Bundle = Bundle().apply {\n'
if 'private fun enterMicrophoneForeground()' not in s:
    helper = '''    private fun enterMicrophoneForeground() {\n        val manager = getSystemService(NotificationManager::class.java)\n        manager.createNotificationChannel(\n            NotificationChannel(\n                NOTIFICATION_CHANNEL,\n                "Voice recognition",\n                NotificationManager.IMPORTANCE_LOW\n            ).apply {\n                setSound(null, null)\n                setShowBadge(false)\n            }\n        )\n        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL)\n            .setSmallIcon(android.R.drawable.ic_btn_speak_now)\n            .setContentTitle("Moonshine voice input")\n            .setContentText("Listening")\n            .setOngoing(true)\n            .setCategory(Notification.CATEGORY_SERVICE)\n            .build()\n        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {\n            startForeground(\n                NOTIFICATION_ID,\n                notification,\n                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE\n            )\n        } else {\n            startForeground(NOTIFICATION_ID, notification)\n        }\n    }\n\n    private fun leaveMicrophoneForeground() {\n        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {\n            stopForeground(STOP_FOREGROUND_REMOVE)\n        } else {\n            @Suppress("DEPRECATION")\n            stopForeground(true)\n        }\n    }\n\n'''
    if anchor not in s:
        raise SystemExit('metadataBundle anchor not found')
    s = s.replace(anchor, helper + anchor, 1)

s = s.replace(
    '        override val context: Context\n            get() = this@MoonshineRecognitionService\n',
    '        override val context: Context\n            get() = sessionContext ?: this@MoonshineRecognitionService\n',
    1,
)

# Always drop foreground state when a session ends, errors, or is cancelled.
s = s.replace(
    '    private fun finishClient() {\n        clientCallback = null\n',
    '    private fun finishClient() {\n        leaveMicrophoneForeground()\n        clientCallback = null\n',
    1,
)

old = '''    override fun onStartListening(recognizerIntent: Intent?, callback: Callback) {\n        session.reset()\n        clientCallback = callback\n'''
new = '''    override fun onStartListening(recognizerIntent: Intent?, callback: Callback) {\n        session.reset()\n        sessionContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {\n            createContext(\n                ContextParams.Builder()\n                    .setNextAttributionSource(callback.callingAttributionSource)\n                    .build()\n            )\n        } else {\n            this\n        }\n        enterMicrophoneForeground()\n        clientCallback = callback\n'''
if old not in s:
    raise SystemExit('onStartListening anchor not found')
s = s.replace(old, new, 1)
service.write_text(s)

# RecognitionService requires the microphone itself to be opened through the
# caller-attribution context. Merely checking permission through that context is
# insufficient because the legacy AudioRecord constructor does not consume it.
audio = root / 'app/src/main/java/org/futo/voiceinput/AudioRecognizer.kt'
a = audio.read_text()
old_recorder = '''            recorder = AudioRecord(\n                MediaRecorder.AudioSource.VOICE_RECOGNITION,\n                AUDIO_SAMPLE_RATE,\n                AudioFormat.CHANNEL_IN_MONO,\n                AudioFormat.ENCODING_PCM_16BIT,\n                AUDIO_SAMPLE_RATE * 2 * 5\n            )\n'''
new_recorder = '''            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {\n                AudioRecord.Builder()\n                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)\n                    .setAudioFormat(\n                        AudioFormat.Builder()\n                            .setSampleRate(AUDIO_SAMPLE_RATE)\n                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)\n                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)\n                            .build()\n                    )\n                    .setBufferSizeInBytes(AUDIO_SAMPLE_RATE * 2 * 5)\n                    .setContext(context)\n                    .build()\n            } else {\n                AudioRecord(\n                    MediaRecorder.AudioSource.VOICE_RECOGNITION,\n                    AUDIO_SAMPLE_RATE,\n                    AudioFormat.CHANNEL_IN_MONO,\n                    AudioFormat.ENCODING_PCM_16BIT,\n                    AUDIO_SAMPLE_RATE * 2 * 5\n                )\n            }\n'''
if old_recorder not in a:
    raise SystemExit('AudioRecord constructor anchor not found')
a = a.replace(old_recorder, new_recorder, 1)
audio.write_text(a)

# Mark the RecognitionService itself as a microphone FGS. The app already
# declares FOREGROUND_SERVICE and FOREGROUND_SERVICE_MICROPHONE upstream.
manifest = root / 'app/src/main/AndroidManifest.xml'
m = manifest.read_text()
old_service = '''<service\n            android:name=".MoonshineRecognitionService"\n            android:exported="true"\n            android:permission="android.permission.BIND_SPEECH_RECOGNITION_SERVICE">'''
new_service = '''<service\n            android:name=".MoonshineRecognitionService"\n            android:exported="true"\n            android:foregroundServiceType="microphone"\n            android:permission="android.permission.BIND_SPEECH_RECOGNITION_SERVICE">'''
if old_service not in m:
    raise SystemExit('MoonshineRecognitionService manifest anchor not found')
m = m.replace(old_service, new_service, 1)
manifest.write_text(m)

gradle = root / 'app/build.gradle'
g = gradle.read_text()
g = g.replace('versionCode 49', 'versionCode 52', 1)
g = g.replace('versionName "1.4.2-beta.13-meboard1"', 'versionName "1.4.2-beta.13-meboard4-headless-fgs"', 1)
gradle.write_text(g)

print('Moonshine attributed AudioRecord + microphone foreground service patch applied')
