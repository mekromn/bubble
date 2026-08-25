package com.mekromn.bubble.data.db

import androidx.room.TypeConverter
import com.mekromn.bubble.browser.session.PresentationState
import com.mekromn.bubble.browser.session.ResidencyState
import com.mekromn.bubble.browser.session.UserAgentMode

class TabTypeConverters {
    @TypeConverter
    fun presentationToString(value: PresentationState): String = value.name

    @TypeConverter
    fun presentationFromString(value: String): PresentationState = PresentationState.valueOf(value)

    @TypeConverter
    fun residencyToString(value: ResidencyState): String = value.name

    @TypeConverter
    fun residencyFromString(value: String): ResidencyState = ResidencyState.valueOf(value)

    @TypeConverter
    fun userAgentToString(value: UserAgentMode): String = value.name

    @TypeConverter
    fun userAgentFromString(value: String): UserAgentMode = UserAgentMode.valueOf(value)
}
