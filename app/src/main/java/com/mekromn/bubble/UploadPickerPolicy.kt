package com.mekromn.bubble

/**
 * Browser-side visibility policy for attachment picking.
 *
 * Web `accept`/Gecko mimeTypes are advisory for Bubble's chooser: the user requested that every
 * Android-openable file remain selectable. The destination site can still reject unsupported
 * formats after selection; this policy does not alter or spoof the selected file's actual MIME.
 */
internal object UploadPickerPolicy {
    const val PICKER_MIME = "*/*"
    fun respectsPageAllowList(): Boolean = false
}
