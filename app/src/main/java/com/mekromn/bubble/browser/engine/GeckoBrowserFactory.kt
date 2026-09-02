package com.mekromn.bubble.browser.engine

import android.content.Context
import android.content.MutableContextWrapper
import android.net.Uri
import android.view.View
import com.mekromn.bubble.BuildConfig
import com.mekromn.bubble.browser.navigation.ExternalNavigationPolicy
import com.mekromn.bubble.browser.navigation.SystemExternalNavigator
import com.mekromn.bubble.browser.session.Tab
import com.mekromn.bubble.browser.session.TabId
import com.mekromn.bubble.browser.session.UserAgentMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError

/**
 * One GeckoRuntime per Bubble process; every logical tab receives an independent GeckoSession.
 * Gecko, not Android System WebView, is the primary browser engine from Bubble 0.4 onward.
 */
class GeckoBrowserFactory(context: Context) {
    private val appContext = context.applicationContext
    private val runtime: GeckoRuntime = GeckoRuntime.create(
        appContext,
        GeckoRuntimeSettings.Builder()
            .consoleOutput(BuildConfig.DEBUG)
            .remoteDebuggingEnabled(BuildConfig.DEBUG)
            .build(),
    )

    fun create(tab: Tab, events: BrowserEngineEvents): BrowserEngineSession =
        GeckoBrowserSession(appContext, runtime, tab, events)
}

private class GeckoBrowserSession(
    private val appContext: Context,
    runtime: GeckoRuntime,
    tab: Tab,
    private val events: BrowserEngineEvents,
) : BrowserEngineSession {
    override val tabId: TabId = tab.id
    private val mutablePageState = MutableStateFlow(
        EnginePageState(
            url = tab.lastCommittedUrl,
            title = tab.title,
            secure = tab.lastCommittedUrl.startsWith("https://", ignoreCase = true),
        ),
    )
    override val pageState: StateFlow<EnginePageState> = mutablePageState

    private val contextWrapper = MutableContextWrapper(appContext)
    private val externalNavigator = SystemExternalNavigator(appContext)
    private var latestSessionState: GeckoSession.SessionState? = null
    private var destroyed = false

    private val session = GeckoSession(
        GeckoSessionSettings.Builder()
            .allowJavascript(true)
            .usePrivateMode(tab.isPrivate)
            .contextId(tab.profileId)
            .suspendMediaWhenInactive(false)
            .userAgentMode(userAgentMode(tab.userAgentMode))
            .viewportMode(viewportMode(tab.userAgentMode))
            .build(),
    )

    override val contentView: View = GeckoView(contextWrapper)

    init {
        installDelegates()
        session.open(runtime)
        (contentView as GeckoView).setSession(session)
        setLifecycle(
            active = tab.selected || tab.keepRendererAlive,
            focused = tab.selected,
            highPriority = tab.keepRendererAlive || tab.pinned,
        )
    }

    private fun installDelegates() {
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                publish(
                    mutablePageState.value.copy(
                        url = url,
                        loading = true,
                        progress = 0,
                        secure = url.startsWith("https://", ignoreCase = true),
                        firstContentfulPaint = false,
                        error = null,
                    ),
                )
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                publish(mutablePageState.value.copy(loading = false, progress = 100))
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                publish(
                    mutablePageState.value.copy(
                        progress = progress.coerceIn(0, 100),
                        loading = progress < 100,
                    ),
                )
            }

            override fun onSessionStateChange(
                session: GeckoSession,
                sessionState: GeckoSession.SessionState,
            ) {
                latestSessionState = GeckoSession.SessionState(sessionState)
            }
        }

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                val next = url.orEmpty()
                publish(
                    mutablePageState.value.copy(
                        url = next.ifBlank { mutablePageState.value.url },
                        secure = if (next.isBlank()) mutablePageState.value.secure
                        else next.startsWith("https://", ignoreCase = true),
                        error = null,
                    ),
                )
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                publish(mutablePageState.value.copy(canGoBack = canGoBack))
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                publish(mutablePageState.value.copy(canGoForward = canGoForward))
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny>? {
                if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                    events.onOpenNewTab(tabId, request.uri)
                    return GeckoResult.deny()
                }

                val decision = ExternalNavigationPolicy.classify(
                    rawUrl = request.uri,
                    hasUserGesture = request.hasUserGesture,
                )
                val handled = externalNavigator.handle(decision) { fallback ->
                    session.loadUri(fallback)
                }
                return if (handled) GeckoResult.deny() else null
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError,
            ): GeckoResult<String>? {
                publish(
                    mutablePageState.value.copy(
                        loading = false,
                        error = BrowserPageError(
                            code = error.code,
                            category = error.category,
                            failingUrl = uri,
                        ),
                    ),
                )
                return null
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                publish(mutablePageState.value.copy(title = title.orEmpty()))
            }

            override fun onFirstContentfulPaint(session: GeckoSession) {
                publish(mutablePageState.value.copy(firstContentfulPaint = true))
            }

            override fun onCrash(session: GeckoSession) {
                events.onRendererGone(tabId, true)
            }

            override fun onKill(session: GeckoSession) {
                events.onRendererGone(tabId, false)
            }
        }
    }

    override fun bindHostContext(context: Context) {
        contextWrapper.setBaseContext(context)
    }

    override fun releaseHostContext() {
        contextWrapper.setBaseContext(appContext)
    }

    override fun setLifecycle(active: Boolean, focused: Boolean, highPriority: Boolean) {
        if (destroyed) return
        session.setFocused(focused)
        session.setActive(active)
        session.setPriorityHint(
            if (highPriority) GeckoSession.PRIORITY_HIGH else GeckoSession.PRIORITY_DEFAULT,
        )
    }

    override fun loadUrl(url: String) {
        session.loadUri(url)
    }

    override fun reload() = session.reload()
    override fun stop() = session.stop()

    override fun goBack(): Boolean {
        if (!mutablePageState.value.canGoBack) return false
        session.goBack()
        return true
    }

    override fun goForward(): Boolean {
        if (!mutablePageState.value.canGoForward) return false
        session.goForward()
        return true
    }

    override fun setUserAgentMode(mode: UserAgentMode) {
        session.settings.userAgentMode = userAgentMode(mode)
        session.settings.viewportMode = viewportMode(mode)
    }

    override fun serializedState(): String? {
        session.flushSessionState()
        return latestSessionState?.toString()
    }

    override fun restoreSerializedState(serialized: String): Boolean {
        val restored = runCatching { GeckoSession.SessionState.fromString(serialized) }.getOrNull()
            ?: return false
        return runCatching {
            session.restoreState(restored)
            latestSessionState = GeckoSession.SessionState(restored)
            true
        }.getOrDefault(false)
    }

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        releaseHostContext()
        runCatching { (contentView as GeckoView).releaseSession() }
        runCatching { session.close() }
    }

    private fun publish(state: EnginePageState) {
        mutablePageState.value = state
        events.onPageState(tabId, state)
    }

    private fun userAgentMode(mode: UserAgentMode): Int = when (mode) {
        UserAgentMode.DESKTOP -> GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
        UserAgentMode.MOBILE, UserAgentMode.SYSTEM -> GeckoSessionSettings.USER_AGENT_MODE_MOBILE
    }

    private fun viewportMode(mode: UserAgentMode): Int = when (mode) {
        UserAgentMode.DESKTOP -> GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
        UserAgentMode.MOBILE, UserAgentMode.SYSTEM -> GeckoSessionSettings.VIEWPORT_MODE_MOBILE
    }
}
