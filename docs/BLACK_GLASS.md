# Black-glass appearance — September 5, 2026

The user explicitly changed the requested accent to GREY and requested a black-glass treatment for the bubble and all native browser UI. The centralized palette is neutral (R=G=B), including the previous BLUE/MINT compatibility aliases. The bubble uses cached grey gradients, translucent charcoal, a fine highlight rim and a small neutral badge. A white dot marks unread state without a green accent. The reversible hide target captures with a neutral bright ring. Launcher/adaptive icon art and Android control colors match.

Native surfaces use black gradients, restrained transparency, subtle rims and high-contrast white/grey text. Menus, local editors, the full tab chooser, floating chooser and controls reuse these tokens. Webpage content is not recolored, blurred or downsampled by the theme. ChatGPT continues to receive the existing dark color preference.

This is a lightweight smoked-glass material, not a claim of universal backdrop blur. No fullscreen FLAG_BLUR_BEHIND, screenshot sampling, software blur, idle shimmer or permanent page bitmap layer is introduced. Android background blur requires suitable windows and can be disabled by the platform; the user still gets the visual treatment without adding that platform/performance dependency. Hardware-backed gradient and transform paths are retained; physical 120fps remains a measurement gate.

Pure palette tests reject coloured base tokens. An Android offscreen pixel test verifies neutral-grey shaded bubble pixels and transparent edges/material; it is explicitly NOT a GPU benchmark. Real interaction screenshots are collected by the existing runtime suite.
