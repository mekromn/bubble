package com.mekromn.bubble.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mekromn.bubble.browser.session.PresentationState
import com.mekromn.bubble.browser.session.ResidencyState
import com.mekromn.bubble.browser.session.Tab
import com.mekromn.bubble.browser.session.TabId
import com.mekromn.bubble.browser.session.UserAgentMode

@Entity(
    tableName = "tabs",
    indices = [
        Index(value = ["sortIndex"]),
        Index(value = ["lastActivatedAt"]),
        Index(value = ["selected"]),
    ],
)
data class TabEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val createdAt: Long,
    val lastActivatedAt: Long,
    val sortIndex: Long,
    val lastCommittedUrl: String,
    val title: String,
    val faviconKey: String?,
    val presentationState: PresentationState,
    val residencyState: ResidencyState,
    val pinned: Boolean,
    val keepRendererAlive: Boolean,
    val isPrivate: Boolean,
    val userAgentMode: UserAgentMode,
    val zoomPercent: Int,
    val groupId: String?,
    val restoreStateKey: String?,
    val crashRecoveryCount: Int,
    val selected: Boolean,
)

internal fun TabEntity.toDomain(): Tab = Tab(
    id = TabId(id),
    profileId = profileId,
    createdAt = createdAt,
    lastActivatedAt = lastActivatedAt,
    sortIndex = sortIndex,
    lastCommittedUrl = lastCommittedUrl,
    title = title,
    faviconKey = faviconKey,
    presentationState = presentationState,
    residencyState = residencyState,
    pinned = pinned,
    keepRendererAlive = keepRendererAlive,
    isPrivate = isPrivate,
    userAgentMode = userAgentMode,
    zoomPercent = zoomPercent,
    groupId = groupId,
    restoreStateKey = restoreStateKey,
    crashRecoveryCount = crashRecoveryCount,
    selected = selected,
)

internal fun Tab.toEntity(): TabEntity = TabEntity(
    id = id.value,
    profileId = profileId,
    createdAt = createdAt,
    lastActivatedAt = lastActivatedAt,
    sortIndex = sortIndex,
    lastCommittedUrl = lastCommittedUrl,
    title = title,
    faviconKey = faviconKey,
    presentationState = presentationState,
    residencyState = residencyState,
    pinned = pinned,
    keepRendererAlive = keepRendererAlive,
    isPrivate = isPrivate,
    userAgentMode = userAgentMode,
    zoomPercent = zoomPercent,
    groupId = groupId,
    restoreStateKey = restoreStateKey,
    crashRecoveryCount = crashRecoveryCount,
    selected = selected,
)
