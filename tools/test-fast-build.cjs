'use strict';
const fs=require('node:fs'),assert=require('node:assert/strict'),path=require('node:path');
const read=p=>fs.readFileSync(p,'utf8');
const native=read('app/src/main/cpp/bubble_ahb.cpp');
const consume=native.split('void consume(')[1].split('void run(')[0];
assert(!consume.includes('ASurfaceTransaction_create('));
assert(!consume.includes('new Lease'));
assert(!consume.includes('ASurfaceTransaction_setBufferTransparency('));
assert(consume.includes('if (reachedDrainLimit) s->wake();'));
assert(consume.includes('space != s->appliedDataSpace'));
assert(native.includes('std::array<Lease, kMaxImages> leaseSlots'));
assert(native.includes('AImage_deleteAsync(image, releaseFenceFd)'));
const gradle=read('app/build.gradle.kts');
assert.match(gradle,/create\("performance"\)/);
assert.match(gradle,/isDebuggable = false/);
assert.match(gradle,/isMinifyEnabled = true/);
assert.match(gradle,/testBuildType = "performance"/);
assert.match(gradle,/applicationIdSuffix = "\.debug"/);
assert.match(read('app/src/main/cpp/CMakeLists.txt'),/-O3 -flto=thin/);
assert(!read('app/src/main/cpp/CMakeLists.txt').includes('-ffast-math'));
assert.match(read('app/src/performance/AndroidManifest.xml'),/<profileable android:shell="true"/);
let calls=0;
for(const name of fs.readdirSync('app/src/main/java/com/mekromn/bubble')) {
 if(!name.endsWith('.kt')||name==='DiagnosticLog.kt')continue;
 const text=read(path.join('app/src/main/java/com/mekromn/bubble',name));
 for(const match of text.matchAll(/DiagnosticLog\.(event|error|snapshotMemory)\(/g)) {
   assert.equal(text.slice(Math.max(0,match.index-27),match.index),'if (DiagnosticLog.ENABLED) ',name+' eager diagnostic argument');calls++;
 }
}
assert(calls>=67);
console.log('Fast-build guards passed: optimized/profileable packaging, JNI boundary, no per-submission transaction/Lease allocation, guarded diagnostic calls:',calls);
