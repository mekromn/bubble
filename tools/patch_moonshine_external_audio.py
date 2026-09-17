from pathlib import Path
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("voice-input")

service = root / "app/src/main/java/org/futo/voiceinput/MoonshineRecognitionService.kt"
s = service.read_text()

if "import android.content.ContextParams" not in s:
    s = s.replace("import android.content.Context\n", "import android.content.Context\nimport android.content.ContextParams\n", 1)
if "import android.os.Build" not in s:
    s = s.replace("import android.os.Bundle\n", "import android.os.Build\nimport android.os.Bundle\n", 1)
if "import android.os.ParcelFileDescriptor" not in s:
    s = s.replace("import android.os.Bundle\n", "import android.os.Bundle\nimport android.os.ParcelFileDescriptor\n", 1)

s = s.replace(
    "    private var clientCallback: Callback? = null\n",
    "    private var clientCallback: Callback? = null\n    private var sessionContext: Context? = null\n",
    1,
)
s = s.replace(
    "        override val context: Context\n            get() = this@MoonshineRecognitionService\n",
    "        override val context: Context\n            get() = sessionContext ?: this@MoonshineRecognitionService\n",
    1,
)

old_start = """    override fun onStartListening(recognizerIntent: Intent?, callback: Callback) {
        session.reset()
        clientCallback = callback
        speechBegan = false
        speechEnded = false
        status("Initializing…")
        session.create()
    }
"""
new_start = """    override fun onStartListening(recognizerIntent: Intent?, callback: Callback) {
        session.reset()
        sessionContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            createContext(
                ContextParams.Builder()
                    .setNextAttributionSource(callback.callingAttributionSource)
                    .build()
            )
        } else {
            this
        }
        clientCallback = callback
        speechBegan = false
        speechEnded = false
        status("Initializing…")

        @Suppress("DEPRECATION")
        val injectedAudio =
            recognizerIntent?.getParcelableExtra<ParcelFileDescriptor>(RecognizerIntent.EXTRA_AUDIO_SOURCE)
        if (injectedAudio != null) {
            val sampleRate = recognizerIntent.getIntExtra(
                RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,
                16000
            )
            val channelCount = recognizerIntent.getIntExtra(
                RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT,
                1
            )
            val encoding = recognizerIntent.getIntExtra(
                RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            )
            session.createFromAudioSource(injectedAudio, sampleRate, channelCount, encoding)
        } else {
            session.create()
        }
    }
"""
if old_start not in s:
    raise SystemExit("Moonshine onStartListening anchor not found")
s = s.replace(old_start, new_start, 1)
service.write_text(s)

audio = root / "app/src/main/java/org/futo/voiceinput/AudioRecognizer.kt"
a = audio.read_text()

if "import android.os.ParcelFileDescriptor" not in a:
    a = a.replace("import android.os.Build\n", "import android.os.Build\nimport android.os.ParcelFileDescriptor\n", 1)
if "import java.io.FileInputStream" not in a:
    a = a.replace("import java.nio.FloatBuffer\n", "import java.io.FileInputStream\nimport java.nio.FloatBuffer\n", 1)

a = a.replace(
    "    private var recorder: AudioRecord? = null\n",
    "    private var recorder: AudioRecord? = null\n    private var externalSource: ParcelFileDescriptor? = null\n",
    1,
)

reset_anchor = """        cancelCapture()
        stopAndReleaseRecorder()
        val recorderJobToJoin: Job?
"""
reset_repl = """        cancelCapture()
        stopAndReleaseRecorder()
        try {
            externalSource?.close()
        } catch (_: Throwable) {
        }
        externalSource = null
        val recorderJobToJoin: Job?
"""
if reset_anchor not in a:
    raise SystemExit("reset anchor not found")
a = a.replace(reset_anchor, reset_repl, 1)

old_recorder = """            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                AUDIO_SAMPLE_RATE * 2 * 5
            )
"""
new_recorder = """            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(AUDIO_SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(AUDIO_SAMPLE_RATE * 2 * 5)
                    .setContext(context)
                    .build()
            } else {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    AUDIO_SAMPLE_RATE * 2 * 5
                )
            }
"""
if old_recorder not in a:
    raise SystemExit("AudioRecord constructor anchor not found")
a = a.replace(old_recorder, new_recorder, 1)

insert_anchor = """    fun permissionResultGranted() {
        startRecording()
    }
"""
external_method = r'''    fun createFromAudioSource(
        source: ParcelFileDescriptor,
        sampleRate: Int,
        channelCount: Int,
        encoding: Int
    ) {
        loading()

        lifecycleScope.launch {
            if (
                sampleRate != AUDIO_SAMPLE_RATE ||
                channelCount != 1 ||
                encoding != AudioFormat.ENCODING_PCM_16BIT
            ) {
                try { source.close() } catch (_: Throwable) { }
                failed(
                    IllegalArgumentException(
                        "Expected 16 kHz mono PCM16 injected audio, got " +
                            "$sampleRate Hz, channels=$channelCount, encoding=$encoding"
                    )
                )
                return@launch
            }

            val backendType = context.getSetting(SPEECH_BACKEND).toSpeechBackendType()
            personalVocabulary = context.getSetting(PERSONAL_DICTIONARY)
            val readiness = RecognitionModelLifecycle.create(
                context.filesDir,
                BuildConfig.BUNDLE_PARAKEET_MODEL
            ).readiness(
                RecognitionModelSelection(
                    runtimeId = backendType.id,
                    moonshineVariantId = context.getSetting(MOONSHINE_MODEL_VARIANT),
                    nemotronVariantId = context.getSetting(NEMOTRON_PROFILE)
                )
            )
            selectedManagedModel = readiness?.model
            if (readiness != null && !readiness.isReady) {
                try { source.close() } catch (_: Throwable) { }
                needRecognitionModelDownload(readiness.model)
                return@launch
            }

            streamingAudio.reset(
                backendType == SpeechBackendType.ParakeetUnified ||
                    backendType == SpeechBackendType.Nemotron ||
                    backendType == SpeechBackendType.Moonshine
            )

            if (backendType == SpeechBackendType.WhisperGGML) {
                val requiredModels = context.selectedWhisperModelsForCurrentSettings(forcedLanguage)
                if (requiredModels.any { context.modelNeedsDownloading(it) }) {
                    try { source.close() } catch (_: Throwable) { }
                    needWhisperModelDownload(requiredModels)
                    return@launch
                }
            }

            startExternalAudio(source)
        }
    }

    private fun startExternalAudio(source: ParcelFileDescriptor) {
        if (isRecording) {
            throw IllegalStateException("Start external audio when already recording")
        }

        isVADPaused = false
        stopReason = null
        clearCapturedSamples()
        canExpandSpace = true
        externalSource = source
        isRecording = true

        val captureGeneration = recognitionGeneration
        recorderJob = lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                canExpandSpace = context.getSetting(ENABLE_30S_LIMIT) == false
                val bytes = ByteArray(AUDIO_READ_SIZE * 2)
                val samples = ShortArray(AUDIO_READ_SIZE)
                var hasTalked = false

                try {
                    FileInputStream(source.fileDescriptor).use { input ->
                        while (stopReason == null) {
                            val nBytes = input.read(bytes)
                            if (nBytes <= 0) break

                            val evenBytes = nBytes and -2
                            val nRead = evenBytes / 2
                            if (nRead <= 0) continue

                            var sumSquares = 0.0
                            for (i in 0 until nRead) {
                                val lo = bytes[i * 2].toInt() and 0xff
                                val hi = bytes[i * 2 + 1].toInt()
                                val value = ((hi shl 8) or lo).toShort()
                                samples[i] = value
                                val normalized = value.toFloat() / Short.MAX_VALUE.toFloat()
                                sumSquares += (normalized * normalized).toDouble()
                            }

                            when (appendSamples(samples, nRead, captureGeneration)) {
                                AppendResult.Accepted -> Unit
                                AppendResult.SessionEnded -> break
                                AppendResult.DurationLimit -> {
                                    stopReason = StopReason.DurationLimit
                                    break
                                }
                            }

                            val rms = sqrt(sumSquares / nRead).toFloat()
                            val startSoundPassed = floatSamples.position() > AUDIO_SAMPLE_RATE * 0.6
                            if (startSoundPassed && rms > 0.01f) {
                                hasTalked = true
                            }
                            val magnitude = 1.0f - 0.1f.pow(24.0f * rms)
                            val state = if (hasTalked) {
                                MagnitudeState.TALKING
                            } else {
                                MagnitudeState.NOT_TALKED_YET
                            }
                            withContext(Dispatchers.Main) {
                                if (isRecording) {
                                    updateMagnitude(magnitude, state)
                                }
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (isRecording && stopReason == null) {
                        withContext(Dispatchers.Main) {
                            if (isRecording) {
                                isRecording = false
                                failed(error)
                            }
                        }
                    }
                    return@withContext
                } finally {
                    try { source.close() } catch (_: Throwable) { }
                    if (externalSource === source) {
                        externalSource = null
                    }
                }

                withContext(Dispatchers.Main) {
                    if (isRecording) {
                        if (stopReason == null) {
                            stopReason = StopReason.Manual
                        }
                        appendFinalSilence(captureGeneration)
                        finishRecognizer()
                    }
                }
            }
        }

        loadModel()
        recordingStarted()
    }

'''
if insert_anchor not in a:
    raise SystemExit("permissionResultGranted anchor not found")
a = a.replace(insert_anchor, external_method + insert_anchor, 1)
audio.write_text(a)

gradle = root / "app/build.gradle"
g = gradle.read_text()
g = g.replace("versionCode 49", "versionCode 53", 1)
g = g.replace(
    'versionName "1.4.2-beta.13-meboard1"',
    'versionName "1.4.2-beta.13-meboard5-injected-audio"',
    1,
)
gradle.write_text(g)

print("Moonshine injected PCM RecognitionService patch applied")
