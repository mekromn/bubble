from pathlib import Path
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('voice-input')

audio = root / 'app/src/main/java/org/futo/voiceinput/AudioRecognizer.kt'
s = audio.read_text()

anchor = '    private var forcedLanguage: String? = null\n'
insert = '''    private var forcedBackendType: SpeechBackendType? = null\n\n    fun forceBackend(backend: SpeechBackendType?) {\n        forcedBackendType = backend\n    }\n\n    private suspend fun selectedBackendType(): SpeechBackendType {\n        return forcedBackendType ?: context.getSetting(SPEECH_BACKEND).toSpeechBackendType()\n    }\n\n'''
if 'private var forcedBackendType: SpeechBackendType?' not in s:
    if anchor not in s:
        raise SystemExit('forcedLanguage anchor not found')
    s = s.replace(anchor, insert + anchor, 1)

s = s.replace('runtimeId = context.getSetting(SPEECH_BACKEND),', 'runtimeId = selectedBackendType().id,')
s = s.replace('val backendType = context.getSetting(SPEECH_BACKEND).toSpeechBackendType()', 'val backendType = selectedBackendType()')
s = s.replace('context.getSetting(SPEECH_BACKEND).toSpeechBackendType(),', 'selectedBackendType(),')

# All engine backend lookups in this file should now respect the session override.
if 'context.getSetting(SPEECH_BACKEND).toSpeechBackendType()' in s:
    raise SystemExit('unpatched SPEECH_BACKEND lookup remains')

audio.write_text(s)

service = root / 'app/src/main/java/org/futo/voiceinput/MoonshineRecognitionService.kt'
s = service.read_text()
if 'import org.futo.voiceinput.settings.SpeechBackendType' not in s:
    s = s.replace('import org.futo.voiceinput.recognition.RecognitionModel\n', 'import org.futo.voiceinput.recognition.RecognitionModel\nimport org.futo.voiceinput.settings.SpeechBackendType\n', 1)
if 'session.forceBackend(SpeechBackendType.Moonshine)' not in s:
    s = s.replace('        session.reset()\n        clientCallback = callback\n', '        session.reset()\n        session.forceBackend(SpeechBackendType.Moonshine)\n        clientCallback = callback\n', 1)
service.write_text(s)

print('Forced Moonshine backend for headless MeBoard recognition sessions')
