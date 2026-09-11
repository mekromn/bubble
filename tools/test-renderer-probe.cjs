'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const path=require('node:path');
const root=path.resolve(__dirname,'..');
const html=fs.readFileSync(path.join(root,'renderer-probe/src/main/assets/workload.html'),'utf8');
const native=fs.readFileSync(path.join(root,'renderer-probe/src/main/cpp/probe.cpp'),'utf8');
const controller=fs.readFileSync(path.join(root,'renderer-probe/src/main/java/com/mekromn/bubble/probe/ProbeActivity.java'),'utf8');
const trial=fs.readFileSync(path.join(root,'renderer-probe/src/main/java/com/mekromn/bubble/probe/TrialActivity.java'),'utf8');
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
assert(controller.includes('lastObservedPhase'));
assert(trial.includes('GECKOVIEW_SURFACE_READINESS_TIMEOUT'));
assert(trial.includes('NO_FIRST_CONTENTFUL_PAINT')); // Do not weaken the native paint gate.
assert(trial.includes('report.optString("status").startsWith("OK")'));

function run(workload,{longtask=false,neverPaint=false}={}) {
  let callback,title='',clock=0,observes=0,scrollWrites=0;
  const packets=[];
  let scroll=0;
  const pane={scrollHeight:50000,clientHeight:600,get scrollTop(){return scroll;},set scrollTop(v){assert(clock>=350,'motion before first paint');scroll=v;scrollWrites++;}};
  const nodes={viewport:pane,messages:{appendChild(){}},marker:{textContent:''},paint:{getContext(){return {fillRect(){assert(clock>=350,'animated canvas before paint');}}}}};
  class Observer {
    static supportedEntryTypes=longtask?['paint','longtask']:['paint'];
    observe(){assert(longtask,'unsupported longtask was observed');observes++;}
    disconnect(){}
  }
  const context={console,innerWidth:400,innerHeight:600,devicePixelRatio:2,navigator:{userAgent:'SYNTHETIC_TEST'},
    performance:{now:()=>clock,getEntriesByType:type=>type==='paint'&&!neverPaint&&clock>=350?[{name:'first-contentful-paint',startTime:350}]:[]},
    requestAnimationFrame:f=>{callback=f},
    document:{visibilityState:'visible',createElement:()=>({}),addEventListener(){},getElementById:id=>nodes[id],set title(t){title=t;packets.push(JSON.parse(t.slice('BPROBE:'.length)));}},
    PerformanceObserver:Observer};
  const js=html.match(/<script>([\s\S]*?)<\/script>/)[1].replace('__CONFIG__',JSON.stringify({token:'unit-test',label:'unit-test',workload,warmupMs:100,measureMs:1000}));
  vm.runInNewContext(js,context);
  const limit=neverPaint?2000:240;
  for(let i=0;i<limit&&callback;i++){
    clock=100+i*1000/120;const f=callback;callback=null;f(clock);
    if(clock<350)assert.equal(scrollWrites,0);
  }
  const result=JSON.parse(title.slice('BPROBE:'.length));
  assert.equal(packets[0].phase,'waiting-for-paint');
  if(neverPaint){
    assert.equal(result.phase,'readiness-failed');assert.equal(result.reason,'PAGE_FIRST_PAINT_TIMEOUT');
    assert.equal(scrollWrites,0);assert(!packets.some(p=>['warmup','measure','done'].includes(p.phase)));return;
  }
  assert.equal(result.phase,'done');assert.equal(result.token,'unit-test');
  assert(result.durationMs>=1000);assert(Math.abs(result.raf.hz-120)<.01);
  assert.equal(result.initialContentfulPaintMs,350);
  assert.equal(result.longTaskApiSupported,longtask);assert.equal(observes,longtask?1:0);
  assert.equal(result.dpr,2);assert.equal(result.cssWidth,400);
  assert.deepEqual(packets.map(p=>p.phase),['waiting-for-paint','warmup','measure','done']);
  if(workload==='apz')assert.equal(pane.scrollTop,(50000-600)*.5);
}
for(const workload of ['scroll','repaint','apz'])for(const longtask of [false,true])run(workload,{longtask});
run('apz',{neverPaint:true});
console.log('Probe source guards, paint-readiness, capability detection, and synthetic JS tests passed. NOT Android runtime/performance results.');
