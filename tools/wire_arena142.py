from pathlib import Path


def once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if old in text:
        if text.count(old) != 1:
            raise RuntimeError(f"unexpected count in {path}: {old!r}")
        p.write_text(text.replace(old, new, 1))
    elif new not in text:
        raise RuntimeError(f"missing source shape in {path}: {old!r}")


p = "app/src/main/java/com/mekromn/bubble/FloatingGeckoWindow.kt"
once(
    p,
    "internal class FloatingGeckoWindow(private val context: Context) {\n    private val host = NativeBufferHost(context)",
    "internal class FloatingGeckoWindow(private val context: Context) : FloatingPageHost {\n    private val host = NativeBufferHost(context)\n    override val transport = RendererArena.Transport.RELAY_LATEST_BP",
)
once(p, "    val view: LiveGeckoView = RawSessionBridge(context, host).apply {", "    override val view: LiveGeckoView = RawSessionBridge(context, host).apply {")
once(p, "    fun show(parent: FrameLayout): Boolean {", "    override fun show(parent: FrameLayout): Boolean {")
once(p, "    fun geometryChanged() { host.rootView.invalidate() }", "    override fun geometryChanged() { host.rootView.invalidate() }")
once(p, "    fun coverForReveal(covered: Boolean) { host.coveredForReveal = covered; geometryChanged() }", "    override fun coverForReveal(covered: Boolean) { host.coveredForReveal = covered; geometryChanged() }")
once(p, "    fun backgroundCutout(): View? = host.takeIf { it.hasLiveLayer && !it.coveredForReveal }", "    override fun backgroundCutout(): View? = host.takeIf { it.hasLiveLayer && !it.coveredForReveal }")
once(p, "    fun hide() {", "    override fun hide() {")
once(p, "    fun destroy() = hide()", "    override fun destroy() = hide()")

p = "app/src/main/java/com/mekromn/bubble/FloatingWindow.kt"
once(p, "    private var geckoWindow: FloatingGeckoWindow?=null", "    private var geckoWindow: FloatingPageHost?=null")
once(p, "            if(geckoWindow==null)geckoWindow=FloatingGeckoWindow(context)", "            if(geckoWindow==null)geckoWindow=RendererArena.createHost(context)")
method = """    internal fun setRendererTransportForArena(next: RendererArena.Transport) {
        RendererArena.transport=next
        if(destroyed)return
        val current=geckoWindow
        if(current?.transport==next) { render(); return }
        current?.view?.let { workspace.detachSurface(it) }
        current?.destroy()
        geckoWindow=null
        if(mode==FloatingMode.CHAT) {
            geckoWindow=RendererArena.createHost(context)
            setPanelBackground(FloatingMode.CHAT)
            render()
        }
    }
"""
fw = Path(p)
text = fw.read_text()
if method not in text:
    marker = "    fun configurationChanged() {"
    if marker not in text:
        raise RuntimeError("configuration marker missing")
    fw.write_text(text.replace(marker, method + marker, 1))

print("arena wiring applied")
