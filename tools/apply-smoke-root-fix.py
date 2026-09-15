from pathlib import Path

p = Path('app/src/main/java/com/mekromn/bubble/FloatingBrowserActivity.kt')
s = p.read_text()
start_token = '    private val root = object : FrameLayout(this) {'
end_token = '\n\n    override fun onCreate(state: Bundle?) {'
start = s.index(start_token)
end = s.index(end_token, start)
block = s[start:end]
block = block.replace(
    start_token,
    '    private lateinit var root: FrameLayout\n\n    private fun createRoot(): FrameLayout = object : FrameLayout(this) {',
    1,
)
s = s[:start] + block + s[end:]
marker = '    override fun onCreate(state: Bundle?) {\n        super.onCreate(state)\n'
replacement = marker + (
    '        // Activity field initializers run before the Activity base context/theme is attached.\n'
    '        // Build the ViewRoot only after super.onCreate() so View construction is valid.\n'
    '        root = createRoot()\n'
)
if marker not in s:
    raise SystemExit('onCreate marker not found')
s = s.replace(marker, replacement, 1)
p.write_text(s)

assert 'private lateinit var root: FrameLayout' in s
assert 'private val root = object : FrameLayout(this)' not in s
assert 'root = createRoot()' in s
print('Applied candidate post-attach FloatingBrowserActivity root construction fix')
