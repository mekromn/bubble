from pathlib import Path
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('.')


def add_after_once(text, needle, addition):
    if addition.strip() in text:
        return text
    if needle not in text:
        raise SystemExit(f'Missing import anchor: {needle!r}')
    return text.replace(needle, needle + addition, 1)

# RecognizeActivity: full-width keyboard-sized bottom panel + bottom-center reveal.
p = root / 'app/src/main/java/org/futo/voiceinput/RecognizeActivity.kt'
s = p.read_text()
s = add_after_once(s, 'import android.speech.RecognizerIntent\n', 'import android.view.Gravity\n')
s = add_after_once(s, 'import androidx.activity.result.contract.ActivityResultContracts\n', 'import androidx.compose.animation.core.FastOutSlowInEasing\nimport androidx.compose.animation.core.animateFloatAsState\nimport androidx.compose.animation.core.tween\n')
s = add_after_once(s, 'import androidx.compose.foundation.shape.RoundedCornerShape\n', 'import androidx.compose.foundation.shape.RectangleShape\n')
s = add_after_once(s, 'import androidx.compose.runtime.Composable\n', 'import androidx.compose.runtime.LaunchedEffect\nimport androidx.compose.runtime.getValue\nimport androidx.compose.runtime.mutableStateOf\nimport androidx.compose.runtime.remember\nimport androidx.compose.runtime.setValue\n')
s = add_after_once(s, 'import androidx.compose.ui.graphics.ColorFilter\n', 'import androidx.compose.ui.graphics.TransformOrigin\nimport androidx.compose.ui.graphics.graphicsLayer\n')
old = '''fun RecognizeWindow(forceNoUnpaidNotice: Boolean = false, allowClick: Boolean = false, onClose: (() -> Unit)?, onPauseVAD: (Boolean) -> Unit = { }, onFinish: () -> Unit = { }, content: @Composable ColumnScope.() -> Unit) {\n    UixThemeAuto {\n'''
new = '''fun RecognizeWindow(forceNoUnpaidNotice: Boolean = false, allowClick: Boolean = false, onClose: (() -> Unit)?, onPauseVAD: (Boolean) -> Unit = { }, onFinish: () -> Unit = { }, content: @Composable ColumnScope.() -> Unit) {\n    var revealed by remember { mutableStateOf(false) }\n    val revealProgress by animateFloatAsState(\n        targetValue = if (revealed) 1f else 0f,\n        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),\n        label = "recognizerBottomReveal"\n    )\n    LaunchedEffect(Unit) { revealed = true }\n\n    UixThemeAuto {\n'''
if old not in s:
    raise SystemExit('RecognizeWindow function anchor not found')
s = s.replace(old, new, 1)
old = '''                .recognizerSurfaceClickable(disabled = !allowClick, onPauseVAD = onPauseVAD, onFinish = onFinish)\n                .width(280.dp)\n                .wrapContentHeight(),\n            color = MaterialTheme.colorScheme.surface,\n            shape = RoundedCornerShape(8.dp)\n'''
new = '''                .recognizerSurfaceClickable(disabled = !allowClick, onPauseVAD = onPauseVAD, onFinish = onFinish)\n                .fillMaxWidth()\n                .height(336.dp)\n                .graphicsLayer {\n                    transformOrigin = TransformOrigin(0.5f, 1.0f)\n                    scaleX = 0.82f + (0.18f * revealProgress)\n                    scaleY = 0.04f + (0.96f * revealProgress)\n                    alpha = revealProgress\n                },\n            color = MaterialTheme.colorScheme.surface,\n            shape = RectangleShape\n'''
if old not in s:
    raise SystemExit('RecognizeWindow surface anchor not found')
s = s.replace(old, new, 1)
old = '        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)\n'
new = '''        window.apply {\n            setWindowAnimations(0)\n            setGravity(Gravity.BOTTOM)\n            setLayout(\n                WindowManager.LayoutParams.MATCH_PARENT,\n                WindowManager.LayoutParams.WRAP_CONTENT\n            )\n            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)\n            addFlags(\n                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or\n                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM\n            )\n        }\n'''
if old not in s:
    raise SystemExit('RecognizeActivity window flags anchor not found')
s = s.replace(old, new, 1)
p.write_text(s)

# IME path: same geometry/animation; this path supplies native keyboard insets.
p = root / 'app/src/main/java/org/futo/voiceinput/VoiceInputMethodService.kt'
s = p.read_text()
s = add_after_once(s, 'import androidx.compose.foundation.layout.wrapContentHeight\n', 'import androidx.compose.animation.core.FastOutSlowInEasing\nimport androidx.compose.animation.core.animateFloatAsState\nimport androidx.compose.animation.core.tween\n')
s = add_after_once(s, 'import androidx.compose.runtime.Composable\n', 'import androidx.compose.runtime.LaunchedEffect\nimport androidx.compose.runtime.getValue\nimport androidx.compose.runtime.mutableStateOf\nimport androidx.compose.runtime.remember\nimport androidx.compose.runtime.setValue\n')
s = add_after_once(s, 'import androidx.compose.ui.graphics.ColorFilter\n', 'import androidx.compose.ui.graphics.TransformOrigin\nimport androidx.compose.ui.graphics.graphicsLayer\n')
old = '''fun RecognizerInputMethodWindow(switchBack: (() -> Unit)? = null, allowClick: Boolean = false, onPauseVAD: (Boolean) -> Unit = { }, onFinish: () -> Unit = { }, content: @Composable ColumnScope.() -> Unit) {\n    UixThemeAuto(false) {\n'''
new = '''fun RecognizerInputMethodWindow(switchBack: (() -> Unit)? = null, allowClick: Boolean = false, onPauseVAD: (Boolean) -> Unit = { }, onFinish: () -> Unit = { }, content: @Composable ColumnScope.() -> Unit) {\n    var revealed by remember { mutableStateOf(false) }\n    val revealProgress by animateFloatAsState(\n        targetValue = if (revealed) 1f else 0f,\n        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),\n        label = "recognizerImeBottomReveal"\n    )\n    LaunchedEffect(Unit) { revealed = true }\n\n    UixThemeAuto(false) {\n'''
if old not in s:
    raise SystemExit('RecognizerInputMethodWindow function anchor not found')
s = s.replace(old, new, 1)
old = '''                .recognizerSurfaceClickable(disabled = !allowClick, onPauseVAD = onPauseVAD, onFinish = onFinish)\n                .fillMaxWidth()\n                .wrapContentHeight(),\n            color = MaterialTheme.colorScheme.surface\n'''
new = '''                .recognizerSurfaceClickable(disabled = !allowClick, onPauseVAD = onPauseVAD, onFinish = onFinish)\n                .fillMaxWidth()\n                .height(336.dp)\n                .graphicsLayer {\n                    transformOrigin = TransformOrigin(0.5f, 1.0f)\n                    scaleX = 0.82f + (0.18f * revealProgress)\n                    scaleY = 0.04f + (0.96f * revealProgress)\n                    alpha = revealProgress\n                },\n            color = MaterialTheme.colorScheme.surface\n'''
if old not in s:
    raise SystemExit('RecognizerInputMethodWindow surface anchor not found')
s = s.replace(old, new, 1)
p.write_text(s)

# Proper separate AMOLED Dark Blue preset; original AMOLED Dark Purple remains untouched.
blue = '''package org.futo.voiceinput.theme.presets

import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import org.futo.voiceinput.R
import org.futo.voiceinput.theme.ThemeOption
import org.futo.voiceinput.theme.selector.ThemePreview

private val md_theme_dark_primary = Color(0xFF5E97F6)
private val md_theme_dark_onPrimary = Color(0xFF1E3D72)
private val md_theme_dark_primaryContainer = Color(0xFF37568B)
private val md_theme_dark_onPrimaryContainer = Color(0xFFDDEAFF)
private val md_theme_dark_secondary = Color(0xFFC2CCDC)
private val md_theme_dark_onSecondary = Color(0xFF2D3541)
private val md_theme_dark_secondaryContainer = Color(0xFF444C58)
private val md_theme_dark_onSecondaryContainer = Color(0xFFDEE8F8)
private val md_theme_dark_tertiary = Color(0xFFB8CDEF)
private val md_theme_dark_onTertiary = Color(0xFF253349)
private val md_theme_dark_tertiaryContainer = Color(0xFF3B4A63)
private val md_theme_dark_onTertiaryContainer = Color(0xFFD8E7FF)
private val md_theme_dark_error = Color(0xFFF2B8B5)
private val md_theme_dark_onError = Color(0xFF601410)
private val md_theme_dark_errorContainer = Color(0xFF8C1D18)
private val md_theme_dark_onErrorContainer = Color(0xFFF9DEDC)
private val md_theme_dark_outline = Color(0xFF8F9399)
private val md_theme_dark_background = Color(0xFF000000)
private val md_theme_dark_onBackground = Color(0xFFE6E1E5)
private val md_theme_dark_surface = Color(0xFF000000)
private val md_theme_dark_onSurface = Color(0xFFE6E1E5)
private val md_theme_dark_surfaceVariant = Color(0xFF454A4F)
private val md_theme_dark_onSurfaceVariant = Color(0xFFC4CAD0)
private val md_theme_dark_inverseSurface = Color(0xFFE6E1E5)
private val md_theme_dark_inverseOnSurface = Color(0xFF313033)
private val md_theme_dark_inversePrimary = Color(0xFF5070A4)
private val md_theme_dark_shadow = Color(0xFF000000)
private val md_theme_dark_surfaceTint = Color(0xFF5E97F6)
private val md_theme_dark_outlineVariant = Color(0xFF454A4F)
private val md_theme_dark_scrim = Color(0xFF000000)

private val colorScheme = darkColorScheme(
    primary = md_theme_dark_primary, onPrimary = md_theme_dark_onPrimary,
    primaryContainer = md_theme_dark_primaryContainer, onPrimaryContainer = md_theme_dark_onPrimaryContainer,
    secondary = md_theme_dark_secondary, onSecondary = md_theme_dark_onSecondary,
    secondaryContainer = md_theme_dark_secondaryContainer, onSecondaryContainer = md_theme_dark_onSecondaryContainer,
    tertiary = md_theme_dark_tertiary, onTertiary = md_theme_dark_onTertiary,
    tertiaryContainer = md_theme_dark_tertiaryContainer, onTertiaryContainer = md_theme_dark_onTertiaryContainer,
    error = md_theme_dark_error, onError = md_theme_dark_onError,
    errorContainer = md_theme_dark_errorContainer, onErrorContainer = md_theme_dark_onErrorContainer,
    outline = md_theme_dark_outline, background = md_theme_dark_background,
    onBackground = md_theme_dark_onBackground, surface = md_theme_dark_surface,
    onSurface = md_theme_dark_onSurface, surfaceVariant = md_theme_dark_surfaceVariant,
    onSurfaceVariant = md_theme_dark_onSurfaceVariant, inverseSurface = md_theme_dark_inverseSurface,
    inverseOnSurface = md_theme_dark_inverseOnSurface, inversePrimary = md_theme_dark_inversePrimary,
    surfaceTint = md_theme_dark_surfaceTint, outlineVariant = md_theme_dark_outlineVariant,
    scrim = md_theme_dark_scrim,
)

val AMOLEDDarkBlue = ThemeOption(
    dynamic = false,
    key = "AMOLEDDarkBlue",
    name = R.string.amoled_dark_blue_theme_name,
    available = { true }
) { colorScheme }

@Composable
@Preview
private fun PreviewTheme() { ThemePreview(AMOLEDDarkBlue) }
'''
(root / 'app/src/main/java/org/futo/voiceinput/theme/presets/AMOLEDDarkBlue.kt').write_text(blue)

p = root / 'app/src/main/java/org/futo/voiceinput/theme/ThemeOptions.kt'
s = p.read_text()
if 'import org.futo.voiceinput.theme.presets.AMOLEDDarkBlue' not in s:
    s = s.replace('import org.futo.voiceinput.theme.presets.AMOLEDDarkPurple\n', 'import org.futo.voiceinput.theme.presets.AMOLEDDarkBlue\nimport org.futo.voiceinput.theme.presets.AMOLEDDarkPurple\n', 1)
if 'AMOLEDDarkBlue.key to AMOLEDDarkBlue' not in s:
    s = s.replace('    AMOLEDDarkPurple.key to AMOLEDDarkPurple,\n', '    AMOLEDDarkBlue.key to AMOLEDDarkBlue,\n    AMOLEDDarkPurple.key to AMOLEDDarkPurple,\n', 1)
if '    AMOLEDDarkBlue.key,\n' not in s:
    s = s.replace('    AMOLEDDarkPurple.key,\n', '    AMOLEDDarkBlue.key,\n    AMOLEDDarkPurple.key,\n', 1)
p.write_text(s)

p = root / 'app/src/main/res/values/strings.xml'
s = p.read_text()
if 'name="amoled_dark_blue_theme_name"' not in s:
    s = s.replace('</resources>', '    <string name="amoled_dark_blue_theme_name">AMOLED Dark Blue</string>\n</resources>', 1)
p.write_text(s)

(root / 'FUTO_BLUE_PANEL_PATCH.txt').write_text('FUTO Voice Input Moonshine v1.4.2-beta.13 custom patch\nAMOLED Dark Blue #5E97F6\n336dp full-width bottom panel\nTransformOrigin 0.5,1.0 bottom-center grow\nFLAG_ALT_FOCUSABLE_IM intent compatibility\nreal IME path preserved for native host-app insets\n')
print('Patch applied successfully')
