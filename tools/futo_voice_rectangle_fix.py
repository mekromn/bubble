from pathlib import Path
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('.')
p = root / 'app/src/main/java/org/futo/voiceinput/RecognizeActivity.kt'
s = p.read_text()
s = s.replace('import androidx.compose.foundation.shape.RectangleShape\n', '')
s = s.replace('shape = RectangleShape', 'shape = RoundedCornerShape(0.dp)')
p.write_text(s)
print('Replaced RectangleShape with RoundedCornerShape(0.dp)')
