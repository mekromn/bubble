#!/usr/bin/env python3
"""One-time, fail-closed integration against the reviewed Build-7 probe.

Only renderer-probe/, tools/*probe* and renderer documentation are changed.
The staging workflow commits the generated source diff before compiling it.
No device exports or user profiles are read or uploaded by this script.
"""
from pathlib import Path
import base64, hashlib, json, os, urllib.request
ROOT=Path(__file__).resolve().parents[1]
FOUNDATION='dcb85ae37ddee79e8eb91740877e65d943646361'
def api(path):
    req=urllib.request.Request('https://api.github.com/repos/mekromn/bubble/'+path,headers={'Authorization':'Bearer '+os.environ['GH_TOKEN'],'Accept':'application/vnd.github+json'})
    with urllib.request.urlopen(req,timeout=45) as stream:return json.load(stream)
def write(path,text):
    p=ROOT/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
def replace(path,old,new):
    p=ROOT/path;s=p.read_text();n=s.count(old)
    if n!=1:raise RuntimeError(f'{path}: expected exactly one integration anchor, got {n}: {old[:100]}')
    p.write_text(s.replace(old,new,1))
entries=api('git/trees/'+FOUNDATION+'?recursive=1')
if entries.get('truncated'):raise RuntimeError('Incomplete source foundation')
lookup={e['path']:e for e in entries['tree'] if e['type']=='blob'}
for path in ['renderer-probe/src/main/java/com/mekromn/bubble/probe/Measurements.java','renderer-probe/src/main/assets/probe-analysis.js','tools/test-probe-v2.cjs']:
    e=lookup[path];blob=api('git/blobs/'+e['sha']);data=base64.b64decode(blob['content']);actual=hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
    if actual!=e['sha']:raise RuntimeError('Blob identity mismatch')
    write(path,data.decode('utf8'))
J='renderer-probe/src/main/java/com/mekromn/bubble/probe/'
P=J+'TrialActivity.java'
replace(P,'private final ArrayList<Double> uiIntervals=new ArrayList<>();','private final ArrayList<Double> uiIntervals=new ArrayList<>();\n    private Measurements measurements;\n    private Button visibleButton;')
replace(P,'signal(1,"starting");','measurements=new Measurements(this,token);\n        signal(1,"starting");')
replace(P,'buttons.addView(button("Visible",()->{visualConfirmed=true;progress.setText(label()+"\\nVisual confirmation recorded");}));','visibleButton=button("Visible",()->{visualConfirmed=true;progress.setText(label()+"\\nVisual confirmation recorded");});buttons.addView(visibleButton);')
replace(P,'page=new FrameLayout(this);page.setBackgroundColor(Color.BLACK);page.setRequestedFrameRate(vote);','page=new FrameLayout(this);page.setBackgroundColor(Color.TRANSPARENT);page.setRequestedFrameRate(vote);')
replace(P,'geckoView.setRequestedFrameRate(vote);page.addView(geckoView,new FrameLayout.LayoutParams(-1,-1));geckoView.setSession(session);','geckoView.setRequestedFrameRate(vote);page.addView(geckoView,new FrameLayout.LayoutParams(-1,-1));')
replace(P,'voteTree(page);geckoView.requestFocus();session.setActive(true);session.setFocused(true);','if(geckoView.getSession()==null)geckoView.setSession(session);\n        voteTree(page);geckoView.requestFocus();session.setActive(true);session.setFocused(true);')
replace(P,'if(ending||started)return;started=true;signal(3,"loading-workload");','if(ending||started)return;started=true;measurements.attach(page);signal(3,"loading-workload");')
replace(P,'"measureMs",spec.optInt("measureMs"));','"measureMs",spec.optInt("measureMs"),"intensity",spec.optInt("intensity",1));')
replace(P,'case "waiting-for-paint":','case "workload-failed":end("WORKLOAD_ERROR",packet);break;\n                case "waiting-for-paint":')
replace(P,'Choreographer.getInstance().postFrameCallback(uiMeter);','measurements.begin();visibleButton.setEnabled(false);')
replace(P,'measuring=false;main.removeCallbacks(apzInput);measureEndNs=SystemClock.elapsedRealtimeNanos();','measuring=false;measurements.stop();main.removeCallbacks(apzInput);measureEndNs=SystemClock.elapsedRealtimeNanos();')
replace(P,'if(ending)return;ending=true;measuring=false;main.removeCallbacks(apzInput);','if(ending)return;ending=true;measuring=false;if(measurements!=null)measurements.stop();main.removeCallbacks(apzInput);')
replace(P,'if(geckoView!=null)geckoView.dispatchTouchEvent(event);else session.getPanZoomController().onTouchEvent(event);','long dispatchStart=System.nanoTime();\n        if(geckoView!=null)geckoView.dispatchTouchEvent(event);else session.getPanZoomController().onTouchEvent(event);\n        measurements.input(now,dispatchStart,System.nanoTime(),action);')
replace(P,'JSONObject nativeReport=handle==0?null:new JSONObject(NativeProbe.finish(handle));','JSONObject nativeReport=handle==0?null:new JSONObject(NativeProbe.finish(handle));\n                report.put("measurements",measurements==null?JSONObject.NULL:measurements.report());\n                if(measurements!=null)measurements.close();')
replace(P,'JSONObject report=TrialPlan.json("schema",1,','JSONObject report=TrialPlan.json("schema",2,')
# Keep the primary native/lifecycle status. Never replace it with a missing secondary metric.
replace(P,'try{\n            // Legal Gecko lifecycle thread.','String partial=report.toString();\n        worker.execute(()->{try{ProbeActivity.write(new File(new File(new File(getFilesDir(),"benchmarks"),suite),token+".partial.json"),partial);}catch(Exception ignored){}});\n        try{\n            // Legal Gecko lifecycle thread.')
# Native relay timing additions preserve original sample columns and fence ownership.
P='renderer-probe/src/main/cpp/probe.cpp'
replace(P,'uint64_t bufferId=0;','uint64_t bufferId=0;\n    int64_t acquireCallNs=0, applyCallNs=0, releaseCallbackNs=0;\n    int outstandingDepth=0;')
replace(P,'struct Lease { std::shared_ptr<State> state; AImage* image; };','struct Lease { std::shared_ptr<State> state; AImage* image; std::shared_ptr<Sample> sample; };')
replace(P,'// Transfer ownership of the compositor\'s release fence back to ImageReader.','if(lease->sample){std::lock_guard<std::mutex> lock(lease->state->dataMutex);lease->sample->releaseCallbackNs=nowNs();}\n    // Transfer ownership of the compositor\'s release fence back to ImageReader.')
replace(P,'int64_t acquiredAt=0;','int64_t acquiredAt=0, acquireDuration=0;')
replace(P,'const media_status_t status=AImageReader_acquireNextImageAsync(s->reader,&image,&fence);','const int64_t acquireStart=nowNs();\n        const media_status_t status=AImageReader_acquireNextImageAsync(s->reader,&image,&fence);\n        acquireDuration+=nowNs()-acquireStart;')
replace(P,'sample->acquired=acquiredAt;','sample->acquired=acquiredAt;sample->acquireCallNs=acquireDuration;')
replace(P,'s->outstanding++;\n    ASurfaceTransaction_setBufferWithRelease(tx,s->output,buffer,newestFence,new Lease{s,newest},releaseFrame);','s->outstanding++;if(sample)sample->outstandingDepth=s->outstanding.load();\n    ASurfaceTransaction_setBufferWithRelease(tx,s->output,buffer,newestFence,new Lease{s,newest,sample},releaseFrame);')
replace(P,'ASurfaceTransaction_apply(tx);\n    ASurfaceTransaction_delete(tx);\n    s->submitted++;','const int64_t applyStart=nowNs();ASurfaceTransaction_apply(tx);\n    if(sample){std::lock_guard<std::mutex> lock(s->dataMutex);sample->applyCallNs=nowNs()-applyStart;}\n    ASurfaceTransaction_delete(tx);\n    s->submitted++;')
replace(P,'\\\"hardwareBufferId\\\"],\\\"samples\\\":[','\\\"hardwareBufferId\\\",\\\"acquireCallNs\\\",\\\"applyCallNs\\\",\\\"releaseCallbackNs\\\",\\\"outstandingDepth\\\"],\\\"samples\\\":[')
replace(P,"<<f->bufferId<<']';","<<f->bufferId<<','<<f->acquireCallNs<<','<<f->applyCallNs<<','<<f->releaseCallbackNs<<','<<f->outstandingDepth<<']';")
# Preserve failed-run partial measurements if teardown or a renderer blocks.
P=J+'ProbeActivity.java'
replace(P,'if(failure!=null)report=TrialPlan.json("schema",1,"spec",spec,"status",failure,"controllerElapsedMs",elapsed,"lastObservedPhase",terminalPhase);','if(failure!=null){File partial=new File(dir,token+".partial.json");report=partial.isFile()?new JSONObject(new String(Files.readAllBytes(partial.toPath()),StandardCharsets.UTF_8)):TrialPlan.json("schema",2,"spec",spec);report.put("status",failure);report.put("controllerElapsedMs",elapsed);report.put("lastObservedPhase",terminalPhase);}')
replace(P,'quick=button("Quick A/B · 4 cases",','ui.addView(button("Renderer Lab · choose workloads and repeated blocks",this::configureLab));\n        ui.addView(button("Open latest detailed report",()->startActivity(new Intent(this,ReportActivity.class))));\n        quick=button("Quick A/B · 4 cases",')
replace(P,'private void stopSuite(){','private void configureLab(){\n        if(active||saving||!queue.isEmpty())return;\n        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);\n        java.util.List<android.widget.CheckBox> candidates=new java.util.ArrayList<>(),workloads=new java.util.ArrayList<>();\n        for(String name:TrialPlan.VARIANTS){android.widget.CheckBox b=new android.widget.CheckBox(this);b.setText(name);b.setChecked(name.equals("raw_max")||name.equals("relay_latest_no_bp"));form.addView(b);candidates.add(b);}\n        for(String name:TrialPlan.WORKLOADS){android.widget.CheckBox b=new android.widget.CheckBox(this);b.setText(name);b.setChecked(name.equals("apz"));form.addView(b);workloads.add(b);}\n        android.widget.Spinner blocks=choice(form,"Independent paired blocks",new String[]{"1","3","6","8","12"},3),duration=choice(form,"Measurement seconds per trial",new String[]{"10","20","30","60"},1),intensity=choice(form,"Workload intensity",new String[]{"1","2","3","4","6"},0);\n        ScrollView scroll=new ScrollView(this);scroll.addView(form);\n        new android.app.AlertDialog.Builder(this).setTitle("Controlled repeated comparisons").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Review plan",(d,w)->{java.util.List<String> vs=new java.util.ArrayList<>(),ws=new java.util.ArrayList<>();for(android.widget.CheckBox b:candidates)if(b.isChecked())vs.add(b.getText().toString());for(android.widget.CheckBox b:workloads)if(b.isChecked())ws.add(b.getText().toString());if(vs.isEmpty()||ws.isEmpty()){status.setText("Select at least one renderer and workload");return;}int repeats=Integer.parseInt(blocks.getSelectedItem().toString()),seconds=Integer.parseInt(duration.getSelectedItem().toString()),level=Integer.parseInt(intensity.getSelectedItem().toString());int count=vs.size()*ws.size()*repeats*2;new android.app.AlertDialog.Builder(this).setTitle(count+" full/floating trials").setMessage("At least "+((count*(seconds+5)+59)/60)+" minutes of warm-up and measurement, plus startup/cooldown. Each block contains all selected variants. Compare one hypothesis first; do not treat frames as independent repeats.").setNegativeButton("Cancel",null).setPositiveButton("Run",(a,b)->startSuite(TrialPlan.design(vs,ws,repeats,seconds,level,System.currentTimeMillis(),"matched"))).show();}).show();\n    }\n    private android.widget.Spinner choice(LinearLayout form,String label,String[] values,int selected){TextView t=new TextView(this);t.setText(label);form.addView(t);android.widget.Spinner s=new android.widget.Spinner(this);s.setAdapter(new android.widget.ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values));s.setSelection(selected);form.addView(s);return s;}\n    private void stopSuite(){')
replace(P,'ScrollView scroll=new ScrollView(this);scroll.addView(results);ui.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(ui);','ui.addView(results);ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(ui);setContentView(scroll);')
replace(P,'File root=new File(getFilesDir(),"benchmarks");\n                    if(root.isDirectory())','File root=new File(getFilesDir(),"benchmarks");\n                    File[] suites=root.listFiles(File::isDirectory);if(suites!=null)for(File suite:suites){File rendered=File.createTempFile("probe-report-",".html",getCacheDir());try{ReportWriter.write(this,suite,rendered);zip.putNextEntry(new ZipEntry(suite.getName()+"/report.html"));Files.copy(rendered.toPath(),zip);zip.closeEntry();}finally{rendered.delete();}}\n                    if(root.isDirectory())')
replace('renderer-probe/src/main/AndroidManifest.xml','<activity android:name=".TrialActivity"','<activity android:name=".ReportActivity" android:exported="false" />\n        <activity android:name=".TrialActivity"')
replace('renderer-probe/build.gradle.kts','versionName = "0.1.0-b${versionCode}"','versionName = "0.2.0-b${versionCode}"')
replace('renderer-probe/build.gradle.kts','testImplementation("junit:junit:4.13.2")','testImplementation("junit:junit:4.13.2")\n    testImplementation("org.json:json:20240303")')
print('Integrated v2 timings and UI into the isolated probe only. No production Bubble files changed.')
