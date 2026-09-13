'use strict';
const fs=require('node:fs'), assert=require('node:assert/strict');
const read=p=>fs.readFileSync(p,'utf8');
const dir='app/src/main/java/com/mekromn/bubble/';
const policy=read(dir+'PageTouchDispatch.kt');
assert.match(policy,/action == MotionEvent.ACTION_DOWN/);
assert.match(policy,/view.isAttachedToWindow/);
assert.match(policy,/view.requestUnbufferedDispatch\(event\)/);
assert(!/post\(|postDelayed\(|postOnAnimation\(|MotionEvent.obtain|setAction\(|setLocation\(|Log\./.test(policy));
for(const file of ['LiveGeckoView.kt','FloatingGeckoWindow.kt']) {
 const text=read(dir+file);
 const touch=text.split('override fun onTouchEvent(event: MotionEvent): Boolean {')[1].split('\n        }')[0];
 assert.match(touch,/PageTouchDispatch.request\(this, event, (session != null|true)\)/,file);
 assert(touch.includes('onTouchEvent(event)'),file+' must forward the same event');
 assert(!/MotionEvent.obtain|post\(|postDelayed\(/.test(touch),file+' must not create an input relay');
}
const runtime=read('app/src/androidTest/java/com/mekromn/bubble/PageTouchChecks.kt');
for(const word of ['ACTION_CANCEL','ACTION_POINTER_DOWN','ACTION_POINTER_UP','pointercancel','event.timeStamp','touch-action:none'])assert(runtime.includes(word));
const all=read('app/src/androidTest/java/com/mekromn/bubble/RelayLatestBpRuntimeTest.kt');
assert(all.includes('PageTouchChecks(inst).run("fullscreen"'));
assert(all.includes('PageTouchChecks(inst).run("floating"'));
console.log('PASS: gesture-scoped page input in both real hosts; original Gecko event path retained. Not a latency measurement.');
