#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]

fw = root / "app/src/main/java/com/mekromn/bubble/FloatingWindow.kt"
text = fw.read_text()

replacements = [
    (
        "private var glassBlur=OverlayGlass.available(manager)",
        "private var glassBlur=false",
    ),
    (
        "OverlayGlass.apply(context,manager,params,directPanel)\n        build(initialMode)",
        "glassBlur=OverlayGlass.apply(context,manager,params,directPanel)\n        build(initialMode)",
    ),
    (
        """    private fun render() {\n        if(destroyed)return\n        val nowBlur=OverlayGlass.available(manager)\n        if(nowBlur!=glassBlur) {\n            glassBlur=nowBlur\n            if(mode!=FloatingMode.BUBBLE)setPanelBackground(mode)\n            place(rectangle,true)\n        }\n""",
        """    internal fun crossWindowBlurChanged(enabled:Boolean) {\n        if(destroyed || glassBlur==enabled)return\n        glassBlur=enabled\n        if(mode!=FloatingMode.BUBBLE)setPanelBackground(mode)\n        // The platform listener is the source of truth; applying here changes only the\n        // tiny compositor blur regions and does not fan out through Workspace listeners.\n        OverlayGlass.apply(context,manager,params,mode!=FloatingMode.BUBBLE)\n    }\n    private fun render() {\n        if(destroyed)return\n""",
    ),
]

for old, new in replacements:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"FloatingWindow patch anchor count {count}, expected 1: {old[:90]!r}")
    text = text.replace(old, new, 1)

fw.write_text(text)

print("Applied Bubble 146 FloatingWindow blur-state patch")
