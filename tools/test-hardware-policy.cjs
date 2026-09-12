const fs=require('node:fs'),assert=require('node:assert/strict');
const read=p=>fs.readFileSync(p,'utf8');
const config=read('app/src/main/assets/gecko-hardware.yaml');
const prefs=[...config.matchAll(/^  ([a-z][a-z0-9.-]+): (true|false)$/gm)];
assert.equal(prefs.length,13);
assert.equal(new Set(prefs.map(x=>x[1])).size,13);
for(const [_,name] of prefs)assert(!/security|sandbox|fission|force-disabled|forbid-software/.test(name));
assert(!config.includes('layout.frame_rate:'));
assert(!config.includes('gfx.webrender.compositor.force-enabled:'));
assert.match(read('app/src/main/AndroidManifest.xml'),/android:hardwareAccelerated="true"/);
for(const f of fs.readdirSync('app/src/main/java/com/mekromn/bubble').filter(x=>x.endsWith('.kt'))){
 const code=read('app/src/main/java/com/mekromn/bubble/'+f);
 assert(!code.includes('LAYER_TYPE_SOFTWARE'),f);
 if(f!=='DiagnosticLog.kt')for(const m of code.matchAll(/Log\.[vdiew]\(/g))assert(code.slice(Math.max(0,m.index-26),m.index).includes('if (BuildConfig.DEBUG)'),f);
}
const native=read('app/src/main/cpp/bubble_ahb.cpp');
assert(!native.includes('__android_log_print('));
assert.match(read('app/src/main/cpp/relay_logging.h'),/#define BUBBLE_RELAY_LOG\(\.\.\.\) \(\(void\)0\)/);
const cmake=read('app/src/main/cpp/CMakeLists.txt');
assert.match(cmake,/option\(BUBBLE_RELAY_LOGGING "Compile native renderer logs" OFF\)/);
assert(cmake.indexOf('find_library(log-lib log)')>cmake.indexOf('if(BUBBLE_RELAY_LOGGING)'));
console.log('Hardware/log guards passed: 13 named overrides, fallback/security preserved, hardware windows, no app log calls when disabled.');
