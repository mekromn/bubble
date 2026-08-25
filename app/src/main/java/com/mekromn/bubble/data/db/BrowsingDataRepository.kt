package com.mekromn.bubble.data.db

import com.mekromn.bubble.browser.session.PresentationState
import com.mekromn.bubble.browser.session.Tab
import com.mekromn.bubble.browser.session.TabId
import com.mekromn.bubble.browser.session.UserAgentMode
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class HistoryEntry(
    val url: String,
    val title: String,
    val lastVisitedAt: Long,
    val visitCount: Int,
)

data class Bookmark(
    val url: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class ClosedTab(
    val id: String,
    val originalTabId: TabId,
    val url: String,
    val title: String,
    val closedAt: Long,
    val presentationState: PresentationState,
    val userAgentMode: UserAgentMode,
    val zoomPercent: Int,
    val pinned: Boolean,
    val keepRendererAlive: Boolean,
    val groupId: String?,
)

class RoomBrowsingDataRepository(
    private val dao: BrowsingDataDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val history: Flow<List<HistoryEntry>> = dao.observeHistory().map { rows ->
        rows.map { row ->
            HistoryEntry(row.url, row.title, row.lastVisitedAt, row.visitCount)
        }
    }

    val bookmarks: Flow<List<Bookmark>> = dao.observeBookmarks().map { rows ->
        rows.map { row ->
            Bookmark(row.url, row.title, row.createdAt, row.updatedAt)
        }
    }

    val closedTabs: Flow<List<ClosedTab>> = dao.observeClosedTabs().map { rows ->
        rows.mapNotNull { row ->
            runCatching {
                ClosedTab(
                    id = row.id,
                    originalTabId = TabId(row.originalTabId),
                    url = row.url,
                    title = row.title,
                    closedAt = row.closedAt,
                    presentationState = PresentationState.valueOf(row.presentationState),
                    userAgentMode = UserAgentMode.valueOf(row.userAgentMode),
                    zoomPercent = row.zoomPercent,
                    pinned = row.pinned,
                    keepRendererAlive = row.keepRendererAlive,
                    groupId = row.groupId,
                )
            }.getOrNull()
        }
    }

    suspend fun recordVisit(tab: Tab) {
        if (tab.isPrivate || !isWebUrl(tab.lastCommittedUrl)) return
        dao.recordHistoryVisit(
            url = tab.lastCommittedUrl,
            title = tab.title,
            visitedAt = clock(),
        )
    }

    suspend fun recordClosed(tab: Tab) {
        if (tab.isPrivate || !isWebUrl(tab.lastCommittedUrl)) return
        dao.recordClosedTab(
            ClosedTabEntity(
                id = UUID.randomUUID().toString(),
                originalTabId = tab.id.value,
                url = tab.lastCommittedUrl,
                title = tab.title,
                closedAt = clock(),
                presentationState = tab.presentationState.name,
                userAgentMode = tab.userAgentMode.name,
                zoomPercent = tab.zoomPercent,
                pinned = tab.pinned,
                keepRendererAlive = tab.keepRendererAlive,
                groupId = tab.groupId,
            ),
        )
    }

    suspend fun setBookmarked(url: String, title: String, bookmarked: Boolean) {
        if (!isWebUrl(url)) return
        if (!bookmarked) {
            dao.deleteBookmark(url)
            return
        }
        val now = clock()
        val previous = dao.bookmarkForUrl(url)
        dao.upsertBookmark(
            BookmarkEntity(
                url = url,
                title = title.ifBlank { previous?.title.orEmpty() },
                createdAt = previous?.createdAt ?: now,
                updatedAt = now,
            ),
        )
    }

    suspend fun clearHistory() = dao.clearHistory()
    suspend fun clearClosedTabs() = dao.clearClosedTabs()
    suspend fun deleteClosedTab(id: String) = dao.deleteClosedTab(id)

    private fun isWebUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
            !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}
