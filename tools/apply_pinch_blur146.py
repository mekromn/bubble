#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]

def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label} anchor count {count}, expected 1: {old[:110]!r}")
    path.write_text(text.replace(old, new, 1))

fw = root / "app/src/main/java/com/mekromn/bubble/FloatingWindow.kt"
replace_once(fw,
    "private var glassBlur=OverlayGlass.available(manager)",
    "private var glassBlur=false",
    "FloatingWindow glass init")
replace_once(fw,
    "OverlayGlass.apply(context,manager,params,directPanel)\n        build(initialMode)",
    "glassBlur=OverlayGlass.apply(context,manager,params,directPanel)\n        build(initialMode)",
    "FloatingWindow initial apply")
replace_once(fw,
    """    private fun render() {\n        if(destroyed)return\n        val nowBlur=OverlayGlass.available(manager)\n        if(nowBlur!=glassBlur) {\n            glassBlur=nowBlur\n            if(mode!=FloatingMode.BUBBLE)setPanelBackground(mode)\n            place(rectangle,true)\n        }\n""",
    """    internal fun crossWindowBlurChanged(enabled:Boolean) {\n        if(destroyed || glassBlur==enabled)return\n        glassBlur=enabled\n        if(mode!=FloatingMode.BUBBLE)setPanelBackground(mode)\n        // The platform listener is the source of truth; applying here changes only the\n        // tiny compositor blur regions and does not fan out through Workspace listeners.\n        OverlayGlass.apply(context,manager,params,mode!=FloatingMode.BUBBLE)\n    }\n    private fun render() {\n        if(destroyed)return\n""",
    "FloatingWindow render poll")

# Record both benchmark families around exactly the same pre-Gecko input-policy section.
pd = root / "app/src/main/java/com/mekromn/bubble/PageTouchDispatch.kt"
replace_once(pd,
    """    fun request(view: View, event: MotionEvent, hasSession: Boolean) {\n        val benchmark = RendererBenchmark.beforePage(view, event)\n        try {\n            if (!eligible(event.actionMasked, event.source, hasSession) || !view.isAttachedToWindow) return\n            if (shouldUnbuffer(arm, event.source)) view.requestUnbufferedDispatch(event)\n        } finally {\n            RendererBenchmark.afterPage(benchmark, event)\n        }\n    }\n""",
    """    fun request(view: View, event: MotionEvent, hasSession: Boolean) {\n        val rendererBenchmark = RendererBenchmark.beforePage(view, event)\n        val pinchBenchmark = PinchBenchmark.beforePage(view, event)\n        try {\n            if (!eligible(event.actionMasked, event.source, hasSession) || !view.isAttachedToWindow) return\n            if (shouldUnbuffer(arm, event.source)) view.requestUnbufferedDispatch(event)\n        } finally {\n            RendererBenchmark.afterPage(rendererBenchmark, event)\n            PinchBenchmark.afterPage(pinchBenchmark, event)\n        }\n    }\n""",
    "PageTouchDispatch benchmark hooks")

# The already-installed privileged extension supplies the dormant pinch viewport Port.
ws = root / "app/src/main/java/com/mekromn/bubble/Workspace.kt"
replace_once(ws,
    """    private fun installMonitor(tab: ChatTab, session: GeckoSession, addon: WebExtension) {\n        val blobRuntime = engine()\n        PageAppearance.bind(app, tab.id, session, addon)\n""",
    """    private fun installMonitor(tab: ChatTab, session: GeckoSession, addon: WebExtension) {\n        val blobRuntime = engine()\n        PinchBridge.install(session, addon)\n        PageAppearance.bind(app, tab.id, session, addon)\n""",
    "Workspace pinch bridge")

# Benchmark families are mutually exclusive so they cannot instrument one another.
rb = root / "app/src/main/java/com/mekromn/bubble/RendererBenchmark.kt"
replace_once(rb,
    """        if (run != null) return false\n        if (Build.VERSION.SDK_INT < 36) {\n""",
    """        if (run != null || PinchBenchmark.status(context).running) return false\n        if (Build.VERSION.SDK_INT < 36) {\n""",
    "RendererBenchmark mutual exclusion")

# PinchBenchmark needs its own build-fingerprint seed; RendererBenchmark's helper is file-private.
pb = root / "app/src/main/java/com/mekromn/bubble/PinchBenchmark.kt"
replace_once(pb,
    "val seed = SystemClock.elapsedRealtimeNanos() xor BuildFingerprint.seed()",
    "val seed = SystemClock.elapsedRealtimeNanos() xor buildFingerprintSeed()",
    "PinchBenchmark seed call")
replace_once(pb,
    """    private fun bootstrapMedianCi(values: List<Double>, seed: Long): Pair<Double, Double> {\n""",
    """    private fun buildFingerprintSeed(): Long {\n        var value = 0xcbf29ce484222325UL.toLong()\n        for (c in android.os.Build.FINGERPRINT) value = (value xor c.code.toLong()) * 0x100000001b3L\n        return value\n    }\n\n    private fun bootstrapMedianCi(values: List<Double>, seed: Long): Pair<Double, Double> {\n""",
    "PinchBenchmark seed helper")

print("Applied Bubble 146 listener blur + pinch benchmark hooks")
