package com.mekromn.bubble

/** App-private, explicitly authored data. No page text is automatically copied into these. */
internal data class PromptSnippet(val id: String, val title: String, val body: String)
internal enum class TabFilter(val label: String) { ALL("All"), UNREAD("Unread"), GENERATING("Working"), PINNED("Pinned") }

internal object StarterPrompts {
    fun items(): List<PromptSnippet> = listOf(
        "Conversation handoff" to "Create a self-contained handoff for a new chat. Preserve my goals, hard constraints, decisions, exact identifiers, current state, unresolved issues, and next actions. Separate verified results from assumptions. Do not invent missing context.",
        "Code review" to "Review the code I provide for correctness, security, performance, and maintainability. Prioritize concrete defects. Explain the failure conditions, propose the smallest sound fixes, and include regression tests. Do not claim tests ran unless they actually ran.",
        "Implementation plan" to "Turn the following goal into an implementation plan. Identify constraints, interfaces, dependencies, edge cases, test gates, and a useful first deliverable. Make assumptions explicit and distinguish required work from optional improvements.",
        "Debug systematically" to "Help debug this problem. Separate observations from hypotheses, rank likely causes, and propose tests that distinguish them. Avoid changing many unrelated variables at once. Preserve existing working behavior.",
        "Compare alternatives" to "Compare the alternatives below against my actual requirements. Explain tradeoffs, failure modes, ongoing costs, and uncertainty. End with a justified recommendation and the conditions that would change it.",
        "Explain deeply" to "Explain this from first principles, then work through a concrete example. Define unfamiliar terms, show the important reasoning, and highlight the common mistakes. Do not omit the difficult parts.",
        "Challenge assumptions" to "Critically examine the idea below. Identify unsupported assumptions, counterexamples, hidden tradeoffs, and what evidence would disprove the proposal. Improve the idea rather than merely agreeing with it.",
        "Research with sources" to "Research the question below using reliable primary sources where possible. Check dates, cite the claims that matter, distinguish direct evidence from inference, and explain disagreements or missing evidence.",
        "Actionable summary" to "Summarize the material below into decisions, open questions, and concrete next actions. Preserve important details, caveats, and exact numbers. Do not fill gaps with invented information.",
        "Test the edge cases" to "Design a thorough test plan for the feature below. Cover happy paths, invalid inputs, lifecycle changes, concurrency, persistence, accessibility, security, and regressions. Separate automated checks from device or user testing.",
        "Improve this writing" to "Improve the writing below while preserving its intent, facts, and voice. Remove repetition, clarify structure, and make the language natural. Flag ambiguous facts instead of silently changing them.",
        "Decision log" to "Extract a decision log from this conversation: each decision, its rationale, rejected alternatives, unresolved dependencies, and what would trigger reconsideration. Keep confirmed decisions separate from suggestions."
    ).mapIndexed { index, pair -> PromptSnippet("starter-$index", pair.first, pair.second) }
}

internal object QuickTabPolicy {
    fun accepts(filter: TabFilter, unread: Boolean, generating: Boolean, pinned: Boolean): Boolean = when (filter) {
        TabFilter.ALL -> true
        TabFilter.UNREAD -> unread
        TabFilter.GENERATING -> generating
        TabFilter.PINNED -> pinned
    }
    fun nextIndex(current: Int, count: Int, backwards: Boolean): Int =
        if (count <= 0) -1 else Math.floorMod(current.coerceAtLeast(0) + if (backwards) -1 else 1, count)
    fun localName(value: String): String = value.trim().replace('\n', ' ').replace('\r', ' ').take(120)
}
