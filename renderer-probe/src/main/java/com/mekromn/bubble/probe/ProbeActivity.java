package com.mekromn.bubble.probe;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.AtomicFile;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Controller stays out of the renderer process so a renderer deadlock cannot take its watchdog with it. */
public final class ProbeActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService files=Executors.newSingleThreadExecutor();
    private final ArrayDeque<JSONObject> queue=new ArrayDeque<>();
    private TextView status,results;
    private boolean resumed,active,saving;
    private int trialPid,total,completed;
    private JSONObject current;
    private File suiteDir;
    private String suiteId;
    private Runnable deadline;
    private long launchTime;
    private String lastPhase="not-started";
    private Button quick,matrix,extended;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout ui=new LinearLayout(this);ui.setOrientation(LinearLayout.VERTICAL);ui.setPadding(24,48,24,24);ui.setBackgroundColor(Color.BLACK);
        ui.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets i=insets.getInsets(android.view.WindowInsets.Type.systemBars());v.setPadding(i.left+24,i.top+24,i.right+24,i.bottom+24);return insets;});
        TextView title=new TextView(this);title.setText("Bubble Renderer Probe");title.setTextColor(Color.WHITE);title.setTextSize(25);ui.addView(title);
        TextView explanation=new TextView(this);explanation.setTextColor(0xffb5c3d3);explanation.setText(
            "Separate app and clean test profile. No Bubble tabs or account data are opened.\n\n"+
            "Same page pixels in fullscreen-window and floating-window tests. rAF is page callback cadence, NOT proof of displayed FPS.\n\n"+
            "Keep the phone cool. Stop screen recording and other floating apps. Do not touch a successful test. Press Black / broken only on failure, or Stop to cancel. Results stay on this device.");
        ui.addView(explanation);
        Button permission=button("Allow floating-window permission",()->startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName()))));ui.addView(permission);
        ui.addView(button("Renderer Lab · choose workloads and repeated blocks",this::configureLab));
        ui.addView(button("Open latest detailed report",()->startActivity(new Intent(this,ReportActivity.class))));
        quick=button("Quick A/B · 4 cases",()->startSuite(TrialPlan.quick(System.currentTimeMillis())));ui.addView(quick);
        matrix=button("Matrix · "+(TrialPlan.VARIANTS.size()*2)+" cases",()->startSuite(TrialPlan.matrix(1,false,System.currentTimeMillis())));ui.addView(matrix);
        extended=button("Repeated matrix · 6 workloads × 3 rounds",()->startSuite(TrialPlan.matrix(3,true,System.currentTimeMillis())));ui.addView(extended);
        ui.addView(button("Stop suite",this::stopSuite));ui.addView(button("Export all saved reports to Downloads",this::export));
        status=new TextView(this);status.setTextColor(0xff78e1c4);status.setText("Ready · engine "+BuildConfig.GECKO_VERSION);ui.addView(status);
        results=new TextView(this);results.setTextColor(Color.WHITE);results.setTextSize(13);results.setTextIsSelectable(true);
        ui.addView(results);ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(ui);setContentView(scroll);
        // An interrupted controller does not erase completed/partial runs. Never kill a persisted stale PID.
        files.execute(()->{
            File root=new File(getFilesDir(),"benchmarks");File[] dirs=root.listFiles();int count=0;
            if(dirs!=null)for(File dir:dirs){File[] reports=dir.listFiles((d,n)->n.endsWith(".json")&&!n.equals("plan.json"));if(reports!=null)count+=reports.length;}
            final int saved=count;main.post(()->results.setText(saved+" saved report/checkpoint files available for export.\n"));
        });
        String auto=getIntent().getStringExtra("automation");
        if("quick".equals(auto))main.postDelayed(()->startSuite(TrialPlan.quick(115134L)),1000);
    }
    private Button button(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setOnClickListener(v->action.run());return b;}
    @Override public void onResume(){super.onResume();resumed=true;scheduleNext();}
    @Override public void onPause(){resumed=false;super.onPause();}
    @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);stopSuite();}
    private void startSuite(List<JSONObject> plan){
        if(active||saving||!queue.isEmpty())return;
        if(!Settings.canDrawOverlays(this)){status.setText("Grant floating-window permission, then start the suite.");return;}
        queue.addAll(plan);completed=0;total=plan.size();
        suiteId=new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date())+"-"+UUID.randomUUID().toString().substring(0,8);
        suiteDir=new File(new File(getFilesDir(),"benchmarks"),suiteId);
        JSONObject manifest=TrialPlan.json("schema",1,"suite",suiteId,"engine",BuildConfig.GECKO_VERSION,"engineRepository",BuildConfig.GECKO_MAVEN,
            "source",BuildConfig.SOURCE_SHA,"scope","isolated renderer harness; not full production Bubble chrome/session load","trials",new JSONArray(plan));
        saving=true;files.execute(()->{try{write(new File(suiteDir,"plan.json"),manifest.toString(2));}catch(Exception e){main.post(()->{status.setText("Save failed: "+e);queue.clear();});}
            main.post(()->{saving=false;scheduleNext();});});
        results.setText("");quick.setEnabled(false);matrix.setEnabled(false);extended.setEnabled(false);
    }
    private void scheduleNext(){
        if(!resumed||active||saving)return;
        if(queue.isEmpty()){quick.setEnabled(true);matrix.setEnabled(true);extended.setEnabled(true);return;}
        main.postDelayed(()->{if(resumed&&!active&&!saving&&!queue.isEmpty())launchNext();},1500);
    }
    private void launchNext(){
        current=queue.removeFirst();active=true;trialPid=0;lastPhase="launching";launchTime=SystemClock.elapsedRealtime();
        String token=current.optString("token");status.setText((completed+1)+" / "+total+" · "+current.optString("variant")+" · "+(current.optBoolean("floating")?"floating":"fullscreen window"));
        File checkpoint=new File(suiteDir,token+".pending.json");
        JSONObject checkpointData=TrialPlan.json("status","PENDING_OR_INTERRUPTED","spec",current,"controllerStartElapsedMs",launchTime);
        JSONObject spec=current;
        files.execute(()->{try{write(checkpoint,checkpointData.toString());}catch(Exception ignored){}});
        ResultReceiver receiver=new ResultReceiver(main){
            @Override protected void onReceiveResult(int code,Bundle data){
                if(!active||current!=spec||!token.equals(data.getString("token")))return;
                if(code==1){trialPid=data.getInt("pid");lastPhase=data.getString("phase","starting");return;}
                if(code==3){lastPhase=data.getString("phase","unknown");status.setText((completed+1)+" / "+total+" · "+spec.optString("variant")+" · "+data.getString("phase"));return;}
                if(code==2)finishTrial(null);
            }
        };
        deadline=()->{if(active&&current==spec)finishTrial("WATCHDOG_TIMEOUT_OR_NATIVE_FREEZE");};
        main.postDelayed(deadline,35000L+spec.optInt("warmupMs")+spec.optInt("measureMs"));
        Intent intent=new Intent(this,TrialActivity.class).putExtra("spec",spec.toString()).putExtra("suite",suiteId).putExtra("receiver",receiver);
        try{startActivity(intent);}catch(RuntimeException e){finishTrial("LAUNCH_ERROR: "+e.getClass().getSimpleName());}
    }
    private void killTrial(){
        if(trialPid<=0||trialPid==Process.myPid())return;
        ActivityManager am=getSystemService(ActivityManager.class);
        List<ActivityManager.RunningAppProcessInfo> list=am.getRunningAppProcesses();
        if(list!=null)for(ActivityManager.RunningAppProcessInfo p:list)
            if(p.pid==trialPid&&p.uid==Process.myUid()&&p.processName.equals(getPackageName()+":trial")){Process.killProcess(trialPid);break;}
        trialPid=0;
    }
    private void finishTrial(String failure){
        if(!active)return;
        main.removeCallbacks(deadline);active=false;saving=true;
        JSONObject spec=current;File dir=suiteDir;String token=spec.optString("token");long elapsed=SystemClock.elapsedRealtime()-launchTime;
        String terminalPhase=lastPhase;
        killTrial();
        files.execute(()->{
            JSONObject report;
            try{
                File file=new File(dir,token+".json");
                if(failure!=null){File partial=new File(dir,token+".partial.json");report=partial.isFile()?new JSONObject(new String(Files.readAllBytes(partial.toPath()),StandardCharsets.UTF_8)):TrialPlan.json("schema",2,"spec",spec);report.put("status",failure);report.put("controllerElapsedMs",elapsed);report.put("lastObservedPhase",terminalPhase);}
                else report=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
                report.put("controllerElapsedMs",elapsed);write(file,report.toString(2));
                new File(dir,token+".pending.json").delete();
            }catch(Exception e){report=TrialPlan.json("status","REPORT_READ_ERROR","spec",spec,"error",e.toString());}
            JSONObject record=report;
            main.post(()->{
                completed++;saving=false;
                if(record.optString("status").equals("CANCELLED"))queue.clear();
                JSONObject page=record.optJSONObject("page");JSONObject raf=page==null?null:page.optJSONObject("raf");
                String rate=raf==null?"n/a":String.format(Locale.US,"%.1f Hz rAF, p95 %.2f ms",raf.optDouble("hz"),raf.optDouble("p95"));
                results.append(spec.optString("variant")+" / "+(spec.optBoolean("floating")?"floating":"full")+"\n"+record.optString("status")+" · "+rate+"\n\n");
                status.setText(completed+" / "+total+" saved"+(queue.isEmpty()?" · export reports when ready":""));
                JSONObject progress=TrialPlan.json("suite",suiteId,"completed",completed,"total",total,"complete",queue.isEmpty()&&completed==total);
                files.execute(()->{try{write(new File(getFilesDir(),"latest-suite.json"),progress.toString());}catch(Exception ignored){}});
                scheduleNext();
            });
        });
    }
    private void configureLab(){
        if(active||saving||!queue.isEmpty())return;
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);
        java.util.List<android.widget.CheckBox> candidates=new java.util.ArrayList<>(),workloads=new java.util.ArrayList<>();
        for(String name:TrialPlan.VARIANTS){android.widget.CheckBox b=new android.widget.CheckBox(this);b.setText(name);b.setChecked(name.equals("raw_max")||name.equals("relay_latest_no_bp"));form.addView(b);candidates.add(b);}
        for(String name:TrialPlan.WORKLOADS){android.widget.CheckBox b=new android.widget.CheckBox(this);b.setText(name);b.setChecked(name.equals("apz"));form.addView(b);workloads.add(b);}
        android.widget.Spinner blocks=choice(form,"Independent paired blocks",new String[]{"1","3","6","8","12"},3),duration=choice(form,"Measurement seconds per trial",new String[]{"10","20","30","60"},1),intensity=choice(form,"Workload intensity",new String[]{"1","2","3","4","6"},0);
        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        new android.app.AlertDialog.Builder(this).setTitle("Controlled repeated comparisons").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Review plan",(d,w)->{java.util.List<String> vs=new java.util.ArrayList<>(),ws=new java.util.ArrayList<>();for(android.widget.CheckBox b:candidates)if(b.isChecked())vs.add(b.getText().toString());for(android.widget.CheckBox b:workloads)if(b.isChecked())ws.add(b.getText().toString());if(vs.isEmpty()||ws.isEmpty()){status.setText("Select at least one renderer and workload");return;}int repeats=Integer.parseInt(blocks.getSelectedItem().toString()),seconds=Integer.parseInt(duration.getSelectedItem().toString()),level=Integer.parseInt(intensity.getSelectedItem().toString());int count=vs.size()*ws.size()*repeats*2;new android.app.AlertDialog.Builder(this).setTitle(count+" full/floating trials").setMessage("At least "+((count*(seconds+5)+59)/60)+" minutes of warm-up and measurement, plus startup/cooldown. Each block contains all selected variants. Compare one hypothesis first; do not treat frames as independent repeats.").setNegativeButton("Cancel",null).setPositiveButton("Run",(a,b)->startSuite(TrialPlan.design(vs,ws,repeats,seconds,level,System.currentTimeMillis(),"matched"))).show();}).show();
    }
    private android.widget.Spinner choice(LinearLayout form,String label,String[] values,int selected){TextView t=new TextView(this);t.setText(label);form.addView(t);android.widget.Spinner s=new android.widget.Spinner(this);s.setAdapter(new android.widget.ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values));s.setSelection(selected);form.addView(s);return s;}
    private void stopSuite(){queue.clear();if(active)finishTrial("CANCELLED");status.setText("Stopped. Completed reports are preserved.");}
    static void write(File file,String value)throws Exception{
        if(!file.getParentFile().isDirectory()&&!file.getParentFile().mkdirs())throw new java.io.IOException("Cannot create report folder");
        AtomicFile atomic=new AtomicFile(file);FileOutputStream output=null;
        try{output=atomic.startWrite();output.write(value.getBytes(StandardCharsets.UTF_8));atomic.finishWrite(output);}
        catch(Exception e){if(output!=null)atomic.failWrite(output);throw e;}
    }
    private void export(){
        if(active||saving){status.setText("Stop the suite or let the current case finish before exporting.");return;}
        status.setText("Writing benchmark ZIP to Downloads…");
        files.execute(()->{
            Uri uri=null;
            try{
                String name="BubbleProbe-"+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date())+".zip";
                ContentValues cv=new ContentValues();cv.put(MediaStore.Downloads.DISPLAY_NAME,name);cv.put(MediaStore.Downloads.MIME_TYPE,"application/zip");
                cv.put(MediaStore.Downloads.RELATIVE_PATH,"Download/Bubble Benchmarks/");cv.put(MediaStore.Downloads.IS_PENDING,1);
                uri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,cv);if(uri==null)throw new java.io.IOException("MediaStore refused export");
                try(OutputStream stream=getContentResolver().openOutputStream(uri);ZipOutputStream zip=new ZipOutputStream(stream)){
                    File root=new File(getFilesDir(),"benchmarks");
                    File[] suites=root.listFiles(File::isDirectory);if(suites!=null)for(File suite:suites){File rendered=File.createTempFile("probe-report-",".html",getCacheDir());try{ReportWriter.write(this,suite,rendered);zip.putNextEntry(new ZipEntry(suite.getName()+"/report.html"));Files.copy(rendered.toPath(),zip);zip.closeEntry();}finally{rendered.delete();}}
                    if(root.isDirectory())try(java.util.stream.Stream<java.nio.file.Path> paths=Files.walk(root.toPath())){
                        for(java.nio.file.Path p:(Iterable<java.nio.file.Path>)paths.filter(Files::isRegularFile)::iterator){
                            if(!p.toString().endsWith(".json"))continue;
                            zip.putNextEntry(new ZipEntry(root.toPath().relativize(p).toString()));Files.copy(p,zip);zip.closeEntry();
                        }
                    }
                }
                cv.clear();cv.put(MediaStore.Downloads.IS_PENDING,0);getContentResolver().update(uri,cv,null,null);
                main.post(()->status.setText("Saved: Downloads / Bubble Benchmarks / "+name));
            }catch(Exception e){if(uri!=null)getContentResolver().delete(uri,null,null);main.post(()->status.setText("Export failed: "+e));}
        });
    }
}
