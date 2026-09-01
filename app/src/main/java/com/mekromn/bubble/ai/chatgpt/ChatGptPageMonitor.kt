package com.mekromn.bubble.ai.chatgpt

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.mekromn.bubble.ai.monitor.AiChatSignal

/**
 * Minimal ChatGPT lifecycle observer. The native bridge and script are injected only into the
 * exact trusted ChatGPT HTTPS origin. No prompt, response text, cookies, URLs, or account data
 * cross this bridge; only coarse generation lifecycle signals do.
 */
class ChatGptPageMonitor(
    private val webView: WebView,
    private val onSignal: (AiChatSignal) -> Unit,
) {
    private var scriptHandler: ScriptHandler? = null
    private var installed = false

    fun install(): Boolean {
        if (installed) return true
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false

        return runCatching {
            WebViewCompat.addWebMessageListener(
                webView,
                BRIDGE_NAME,
                ALLOWED_ORIGINS,
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(
                        view: WebView,
                        message: WebMessageCompat,
                        sourceOrigin: Uri,
                        isMainFrame: Boolean,
                        replyProxy: JavaScriptReplyProxy,
                    ) {
                        if (!isMainFrame || !isTrustedSource(sourceOrigin)) return
                        if (message.type != WebMessageCompat.TYPE_STRING) return
                        parseSignal(message.data)?.let(onSignal)
                    }
                },
            )
            scriptHandler = WebViewCompat.addDocumentStartJavaScript(
                webView,
                MONITOR_SCRIPT,
                ALLOWED_ORIGINS,
            )
            installed = true
            true
        }.getOrDefault(false)
    }

    fun destroy() {
        if (!installed) return
        scriptHandler?.let { handler -> runCatching { handler.remove() } }
        scriptHandler = null
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            runCatching { WebViewCompat.removeWebMessageListener(webView, BRIDGE_NAME) }
        }
        installed = false
    }

    private fun isTrustedSource(origin: Uri): Boolean {
        if (!origin.scheme.equals("https", ignoreCase = true)) return false
        if (!origin.host.equals(ChatGptAdapter.TRUSTED_HOST, ignoreCase = true)) return false
        return origin.port == -1 || origin.port == 443
    }

    companion object {
        private const val BRIDGE_NAME = "__bubbleAiLifecycleV1"
        private val ALLOWED_ORIGINS = setOf(ChatGptAdapter.TRUSTED_ORIGIN)

        internal fun parseSignal(raw: String?): AiChatSignal? = when (raw) {
            "user_submitted" -> AiChatSignal.USER_SUBMITTED
            "generation_started" -> AiChatSignal.GENERATION_STARTED
            "generation_finished" -> AiChatSignal.GENERATION_FINISHED
            "generation_error" -> AiChatSignal.ERROR
            else -> null
        }

        /**
         * This intentionally observes only coarse UI lifecycle signals. It computes a local
         * signature of the latest assistant element solely to determine that it changed; that
         * signature never leaves the page and therefore answer text is never sent to Android.
         */
        private val MONITOR_SCRIPT = """
            (() => {
              const bridge = globalThis.__bubbleAiLifecycleV1;
              if (!bridge || globalThis.__bubbleAiLifecycleMonitorInstalled) return;
              globalThis.__bubbleAiLifecycleMonitorInstalled = true;

              let phase = 'idle';
              let sawAssistantChange = false;
              let assistantSignature = '';
              let settleTimer = 0;

              const emit = (name) => {
                try { bridge.postMessage(name); } catch (_) {}
              };

              const visible = (element) => {
                if (!element) return false;
                const style = globalThis.getComputedStyle ? getComputedStyle(element) : null;
                if (style && (style.display === 'none' || style.visibility === 'hidden')) return false;
                return element.getClientRects ? element.getClientRects().length > 0 : true;
              };

              const hasStopControl = () => {
                const candidates = document.querySelectorAll('button');
                for (const button of candidates) {
                  if (!visible(button)) continue;
                  const testId = (button.getAttribute('data-testid') || '').toLowerCase();
                  const aria = (button.getAttribute('aria-label') || '').toLowerCase();
                  if (testId === 'stop-button') return true;
                  if (testId.includes('stop') && (testId.includes('generat') || testId.includes('stream'))) return true;
                  if (aria.includes('stop generating') || aria.includes('stop streaming')) return true;
                }
                return false;
              };

              const latestAssistantSignature = () => {
                const messages = document.querySelectorAll('[data-message-author-role="assistant"]');
                const last = messages.length ? messages[messages.length - 1] : null;
                if (!last) return '';
                const text = last.textContent || '';
                const tail = text.slice(-96);
                return String(text.length) + ':' + tail;
              };

              const finishIfSettled = () => {
                globalThis.clearTimeout(settleTimer);
                settleTimer = globalThis.setTimeout(() => {
                  if (phase !== 'generating' || hasStopControl() || !sawAssistantChange) return;
                  const current = latestAssistantSignature();
                  if (current && current === assistantSignature) {
                    phase = 'idle';
                    sawAssistantChange = false;
                    emit('generation_finished');
                  }
                }, 1200);
              };

              const sample = () => {
                const stop = hasStopControl();
                const currentSignature = latestAssistantSignature();

                if (stop && phase !== 'generating') {
                  phase = 'generating';
                  emit('generation_started');
                }

                if (currentSignature && currentSignature !== assistantSignature) {
                  assistantSignature = currentSignature;
                  if (phase === 'submitted' || phase === 'generating') {
                    sawAssistantChange = true;
                    if (phase !== 'generating') {
                      phase = 'generating';
                      emit('generation_started');
                    }
                  }
                }

                if (phase === 'generating' && !stop && sawAssistantChange) finishIfSettled();
              };

              const submitted = () => {
                phase = 'submitted';
                sawAssistantChange = false;
                assistantSignature = latestAssistantSignature();
                emit('user_submitted');
                globalThis.setTimeout(sample, 0);
              };

              const start = () => {
                document.addEventListener('submit', () => submitted(), true);
                document.addEventListener('click', (event) => {
                  const button = event.target && event.target.closest ? event.target.closest('button') : null;
                  if (!button) return;
                  const testId = (button.getAttribute('data-testid') || '').toLowerCase();
                  const aria = (button.getAttribute('aria-label') || '').toLowerCase();
                  if (testId === 'send-button' || aria === 'send prompt' || aria === 'send message') submitted();
                }, true);
                document.addEventListener('keydown', (event) => {
                  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return;
                  const target = event.target;
                  if (target && (target.tagName === 'TEXTAREA' || target.getAttribute('contenteditable') === 'true')) submitted();
                }, true);

                const observer = new MutationObserver(() => {
                  if (globalThis.queueMicrotask) queueMicrotask(sample); else globalThis.setTimeout(sample, 0);
                });
                observer.observe(document.documentElement, {
                  subtree: true,
                  childList: true,
                  characterData: true,
                  attributes: true,
                  attributeFilter: ['aria-label', 'data-testid', 'disabled']
                });
                assistantSignature = latestAssistantSignature();
                sample();
              };

              if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', start, { once: true });
              } else {
                start();
              }
            })();
        """.trimIndent()
    }
}
