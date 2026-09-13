from pathlib import Path

p=Path('app/src/main/java/com/mekromn/bubble/DirectGeckoWindow.kt')
t=p.read_text()
old='''        fun prepareFrameRate(rate: Float) {
            requestedRate = rate.takeIf { it > 0f } ?: 120f
            applyFrameRate(holder.surface)
        }

        private fun applyFrameRate(surface: Surface?) {
            if (Build.VERSION.SDK_INT < 31 || requestedRate <= 0f || surface?.isValid != true) return
            runCatching {
                surface.setFrameRate(
                    requestedRate,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ALWAYS
                )
            }
        }
'''
new='''        fun prepareFrameRate(rate: Float) {
            requestedRate = rate.takeIf { it > 0f } ?: 120f
            // Use Bubble's single Android-16 UI/scroll contract. It chooses
            // FRAME_RATE_COMPATIBILITY_AT_LEAST and avoids a second conflicting vote.
            RenderPolicy.voteTree(this, requestedRate)
        }
'''
if old in t:
    assert t.count(old)==1
    t=t.replace(old,new)
else:
    assert new in t
# Surface lifecycle and late session binding should re-apply the same policy, never a
# DEFAULT/ALWAYS override that could undo the UI-scrolling AT_LEAST contract.
t=t.replace('            applyFrameRate(holder.surface)\n            publishSurfaceIfReady(true)',
            '            if (requestedRate > 0f) RenderPolicy.voteTree(this, requestedRate)\n            publishSurfaceIfReady(true)')
t=t.replace('                applyFrameRate(holder.surface)\n                publishSurfaceIfReady(false)',
            '                if (requestedRate > 0f) RenderPolicy.voteTree(this, requestedRate)\n                publishSurfaceIfReady(false)')
p.write_text(t)
print('direct frame-rate policy normalized')
