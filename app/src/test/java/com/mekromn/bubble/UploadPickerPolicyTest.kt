package com.mekromn.bubble

import org.junit.Assert.*
import org.junit.Test

class UploadPickerPolicyTest {
    @Test fun allOpenableFileTypesRemainVisibleRegardlessOfPageHints() {
        assertEquals("*/*", UploadPickerPolicy.PICKER_MIME)
        assertFalse(UploadPickerPolicy.respectsPageAllowList())
    }
}
