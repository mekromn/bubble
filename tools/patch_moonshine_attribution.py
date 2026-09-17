from pathlib import Path
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('voice-input')
service = root / 'app/src/main/java/org/futo/voiceinput/MoonshineRecognitionService.kt'
s = service.read_text()

if 'import android.content.ContextParams' not in s:
    s = s.replace('import android.content.Context\n', 'import android.content.Context\nimport android.content.ContextParams\n', 1)
if 'import android.os.Build' not in s:
    s = s.replace('import android.os.Bundle\n', 'import android.os.Build\nimport android.os.Bundle\n', 1)

s = s.replace(
    '    private var clientCallback: Callback? = null\n',
    '    private var clientCallback: Callback? = null\n    private var sessionContext: Context? = null\n',
    1,
)

s = s.replace(
    '        override val context: Context\n            get() = this@MoonshineRecognitionService\n',
    '        override val context: Context\n            get() = sessionContext ?: this@MoonshineRecognitionService\n',
    1,
)

old = '''    override fun onStartListening(recognizerIntent: Intent?, callback: Callback) {\n        session.reset()\n        clientCallback = callback\n'''
new = '''    override fun onStartListening(recognizerIntent: Intent?, callback: Callback) {\n        session.reset()\n        sessionContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {\n            createContext(\n                ContextParams.Builder()\n                    .setNextAttributionSource(callback.callingAttributionSource)\n                    .build()\n            )\n        } else {\n            this\n        }\n        clientCallback = callback\n'''
if old not in s:
    raise SystemExit('onStartListening anchor not found')
s = s.replace(old, new, 1)

service.write_text(s)

gradle = root / 'app/build.gradle'
g = gradle.read_text()
g = g.replace('versionCode 49', 'versionCode 50', 1)
g = g.replace('versionName "1.4.2-beta.13-meboard1"', 'versionName "1.4.2-beta.13-meboard2-attribution"', 1)
gradle.write_text(g)

print('Moonshine RecognitionService caller attribution patch applied')
