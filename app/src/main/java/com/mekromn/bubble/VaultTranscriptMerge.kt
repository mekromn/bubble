package com.mekromn.bubble

/**
 * Merges a virtualized/live ChatGPT DOM snapshot into the durable local transcript.
 *
 * A rendered page is not authoritative: after refresh ChatGPT may expose only the newest few turns.
 * We therefore align the incoming ordered window against the saved transcript, preserve everything
 * before that window, prefer the longer version of a still-streaming matching turn, and append only
 * genuinely new turns. A server full-history snapshot is authoritative for the current branch and
 * may replace the saved branch (for example after edit/regenerate).
 */
internal object VaultTranscriptMerge {
    fun merge(saved: List<VaultMessage>, incoming: List<VaultMessage>, authoritative: Boolean): List<VaultMessage> {
        if (incoming.isEmpty()) return saved
        if (saved.isEmpty() || authoritative) return incoming

        var bestStart = -1
        var bestCount = 0
        for (start in saved.indices) {
            var count = 0
            while (start + count < saved.size && count < incoming.size &&
                compatible(saved[start + count], incoming[count])) count++
            if (count == 0) continue
            val touchesSavedTail = start + count == saved.size
            val incomingContained = count == incoming.size
            if ((touchesSavedTail || incomingContained) && count > bestCount) {
                bestStart = start
                bestCount = count
            }
        }

        if (bestStart >= 0) {
            val result = saved.toMutableList()
            for (i in 0 until bestCount) {
                val index = bestStart + i
                result[index] = preferred(result[index], incoming[i])
            }
            if (bestStart + bestCount == saved.size && bestCount < incoming.size) {
                result += incoming.drop(bestCount)
            }
            return result
        }

        val last = saved.last()
        val first = incoming.first()
        if (compatible(last, first)) {
            return saved.dropLast(1) + preferred(last, first) + incoming.drop(1)
        }

        // No overlap can legitimately happen when the virtualized DOM contains only the brand-new
        // user turn after a fully saved assistant turn (or vice versa). Alternating roles are enough
        // evidence to append; same-role/no-overlap is kept conservative to avoid duplicating an old
        // detached DOM fragment.
        return if (last.role != first.role) saved + incoming else saved
    }

    private fun compatible(a: VaultMessage, b: VaultMessage): Boolean {
        if (a.role != b.role) return false
        if (a.text == b.text) return true
        return a.text.startsWith(b.text) || b.text.startsWith(a.text)
    }

    private fun preferred(a: VaultMessage, b: VaultMessage): VaultMessage =
        if (b.text.length >= a.text.length) b else a
}
