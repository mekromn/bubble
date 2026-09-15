package com.mekromn.bubble

import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.util.WeakHashMap

/**
 * Benchmark-only bidirectional bridge to a dormant top-frame content script.
 *
 * The script has no touch listeners, so merely installing this bridge does not put webpage touch
 * handling onto the main thread or change APZ's preventDefault contract. During an explicit pinch
 * run, native code asks it to sample VisualViewport once per rAF and batch those samples. Outside a
 * run it owns only an idle native-messaging Port.
 */
internal object PinchBridge {
    private const val APP = "bubblePinchBench"
    private val ports = WeakHashMap<GeckoSession, WebExtension.Port>()

    fun install(session: GeckoSession, addon: WebExtension) {
        session.webExtensionController.setMessageDelegate(addon, object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                val sender = port.sender
                if (port.name != APP || sender.session !== session || !sender.isTopLevel ||
                    sender.environmentType != WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT ||
                    !Policy.isWeb(sender.url)) {
                    runCatching { port.disconnect() }
                    return
                }
                ports[session]?.takeIf { it !== port }?.let { old -> runCatching { old.disconnect() } }
                ports[session] = port
                port.setDelegate(object : WebExtension.PortDelegate {
                    override fun onPortMessage(message: Any, source: WebExtension.Port) {
                        if (ports[session] !== source) return
                        val obj = message as? JSONObject ?: return
                        when (obj.optString("event")) {
                            "sync" -> {
                                val run = obj.optString("run").take(128)
                                val seq = obj.optInt("seq", -1)
                                val t0 = obj.optString("t0").toLongOrNull() ?: return
                                val jsMs = obj.optDouble("jsMs", Double.NaN)
                                if (seq >= 0 && jsMs.isFinite()) {
                                    PinchBenchmark.onClockSync(session, run, seq, t0, jsMs)
                                }
                            }
                            "samples" -> {
                                val run = obj.optString("run").take(128)
                                val array = obj.optJSONArray("samples") ?: return
                                val decoded = ArrayList<PinchBenchmark.ViewportSample>(array.length())
                                for (i in 0 until array.length()) {
                                    val row = array.optJSONArray(i) ?: continue
                                    if (row.length() < 7) continue
                                    val jsMs = row.optDouble(0, Double.NaN)
                                    val scale = row.optDouble(1, Double.NaN)
                                    val pageLeft = row.optDouble(2, Double.NaN)
                                    val pageTop = row.optDouble(3, Double.NaN)
                                    val width = row.optDouble(4, Double.NaN)
                                    val height = row.optDouble(5, Double.NaN)
                                    val dpr = row.optDouble(6, Double.NaN)
                                    if (jsMs.isFinite() && scale.isFinite() && scale > 0.0 &&
                                        pageLeft.isFinite() && pageTop.isFinite() && width.isFinite() &&
                                        height.isFinite() && dpr.isFinite() && dpr > 0.0) {
                                        decoded += PinchBenchmark.ViewportSample(
                                            jsMs, scale, pageLeft, pageTop, width, height, dpr
                                        )
                                    }
                                }
                                if (decoded.isNotEmpty()) PinchBenchmark.onViewportSamples(session, run, decoded)
                            }
                        }
                    }

                    override fun onDisconnect(source: WebExtension.Port) {
                        if (ports[session] === source) {
                            ports.remove(session)
                            PinchBenchmark.onBridgeDisconnected(session)
                        }
                    }
                })
                PinchBenchmark.onBridgeReady(session)
            }
        }, APP)
    }

    fun ready(session: GeckoSession): Boolean = ports[session] != null

    fun arm(session: GeckoSession, runId: String): Boolean {
        val port = ports[session] ?: return false
        return runCatching {
            port.postMessage(JSONObject().put("cmd", "arm").put("run", runId))
            true
        }.getOrDefault(false)
    }

    fun disarm(session: GeckoSession, runId: String) {
        val port = ports[session] ?: return
        runCatching { port.postMessage(JSONObject().put("cmd", "disarm").put("run", runId)) }
    }

    fun sync(session: GeckoSession, runId: String, seq: Int, t0Ns: Long): Boolean {
        val port = ports[session] ?: return false
        return runCatching {
            // String avoids losing nanosecond integer precision in JavaScript Number.
            port.postMessage(JSONObject().put("cmd", "sync").put("run", runId)
                .put("seq", seq).put("t0", t0Ns.toString()))
            true
        }.getOrDefault(false)
    }
}
