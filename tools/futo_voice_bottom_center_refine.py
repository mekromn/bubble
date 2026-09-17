from pathlib import Path
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('.')
for rel in (
    'app/src/main/java/org/futo/voiceinput/RecognizeActivity.kt',
    'app/src/main/java/org/futo/voiceinput/VoiceInputMethodService.kt',
):
    p = root / rel
    s = p.read_text()
    old = 'scaleX = 0.82f + (0.18f * revealProgress)'
    new = 'scaleX = 0.04f + (0.96f * revealProgress)'
    if old not in s:
        raise SystemExit(f'Expected scaleX anchor missing in {rel}')
    p.write_text(s.replace(old, new, 1))
print('Bottom-center grow refined to near-point origin')
