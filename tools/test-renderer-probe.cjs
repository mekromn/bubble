'use strict';
const assert=require('node:assert/strict'), fs=require('node:fs'),vm=require('node:vm');
const path=require('node:path');
const root=path.resolve(__dirname,'..');
const html=fs.readFileSync(path.join(root,'renderer-probe/src/main/assets/workload.html'),'utf8');
const native=fs.readFileSync(path.join(root,'renderer-probe/src/main/cpp/probe.cpp'),'utf8');
const controller=fs.readFileSync(path.join(root,'renderer-probe/src/main/java/com/mekromn/bubble/probe/ProbeActivity.java'),'utf8');
assert(!native.includes('ANativeWindow_setSharedBufferMode'));
assert(!native.includes('ANativeWindow_setAutoRefresh'));
assert(!native.includes('AImageReader_acquireLatestImageAsync('));
assert(native.includes('i<s->drainLimit'));
assert(native.includes('AImage_deleteAsync(lease->image,fence)'));
assert(native.includes('ASurfaceTransaction_setBufferWithRelease'));
assert(native.includes('sync_file_info_free(info)'));
assert(controller.includes('p.uid==Process.myUid()'));
assert(controller.includes('p.processName.equals(getPackageName()+":trial")'));
assert(controller.includes('WATCHDOG_TIMEOUT_OR_NATIVE_FREEZE'));
for(const workload of ['scroll','repaint','apz']) {
  let callback,title='',clock=0;
  const pane={scrollHeight:50000,clientHeight:600,scrollTop:0};
  const nodes={viewport:pane,messages:{appendChild(){}},marker:{textContent:''},paint:{getContext(){return {fillRect(){}}}}};
  const context={console,innerWidth:400,innerHeight:600,devicePixelRatio:2,navigator:{userAgent:'SYNTHETIC_TEST'},
    performance:{now:()=>clock},requestAnimationFrame:f=>{callback=f},
    document:{visibilityState:'visible',createElement:()=>({}),addEventListener(){},getElementById:id=>nodes[id],set title(t){title=t}},
    PerformanceObserver:class{constructor(){throw Error('unsupported in this test')}}};
  let js=html.match(/<script>([\s\S]*?)<\/script>/)[1].replace('__CONFIG__',JSON.stringify({token:'unit-test',label:'unit-test',workload,warmupMs:100,measureMs:1000}));
  vm.runInNewContext(js,context);
  for(let i=0;i<100;i++){clock=100+i*1000/120;const f=callback;callback=null;if(f)f(clock);}
  // Continue until fixed elapsed workload duration, independent of its callback count.
  for(let i=100;i<200&&callback;i++){clock=100+i*1000/120;const f=callback;callback=null;f(clock);}
  const result=JSON.parse(title.slice('BPROBE:'.length));
  assert.equal(result.phase,'done');assert.equal(result.token,'unit-test');
  assert(result.durationMs>=1000);assert(Math.abs(result.raf.hz-120)<.01);
  assert.equal(result.dpr,2);assert.equal(result.cssWidth,400);
  if(workload==='apz')assert.equal(pane.scrollTop,(50000-600)*.5);
}
console.log('Probe source guards and synthetic JS workload tests passed. These are NOT Android runtime/performance results.');
