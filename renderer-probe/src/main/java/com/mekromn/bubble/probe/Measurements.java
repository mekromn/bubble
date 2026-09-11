package com.mekromn.bubble.probe;

import android.app.Activity;
import android.app.ActivityManager;
import android.os.*;
import android.view.*;
import org.json.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Root-window, page, native-relay and GPU-workload measurements are different scopes. */
final class Measurements {
    private static final int LIMIT=32768;
    private final Activity activity;
    private final String token;
    private final HandlerThread thread=new HandlerThread("ProbeMeasurements",android.os.Process.THREAD_PRIORITY_BACKGROUND);
    private final Handler handler;
    private final ArrayList<long[]> vsync=new ArrayList<>(),hwui=new ArrayList<>(),jank=new ArrayList<>(),input=new ArrayList<>();
    private final ArrayList<JSONObject> resources=new ArrayList<>();
    private final ArrayList<String> errors=new ArrayList<>();
    private volatile long beginNs,endNs,firstVsync=Long.MAX_VALUE,lastVsync;
    private volatile boolean measuring,closed,truncated;
    private long listenerDrops;
    private SurfaceControl.OnJankDataListenerRegistration registration;
    private boolean frameListenerInstalled;
    private final Window.OnFrameMetricsAvailableListener frameListener=(window,m,drops)->{
        long intended=m.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP);
        synchronized(this){
            if(beginNs==0||intended<beginNs||(endNs>0&&intended>endNs))return;
            listenerDrops+=drops;
            add(hwui,new long[]{intended,frameTimelineId(m),m.getMetric(FrameMetrics.TOTAL_DURATION),m.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION),m.getMetric(FrameMetrics.INPUT_HANDLING_DURATION),m.getMetric(FrameMetrics.ANIMATION_DURATION),m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),m.getMetric(FrameMetrics.DRAW_DURATION),m.getMetric(FrameMetrics.SYNC_DURATION),m.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION),m.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION),m.getMetric(FrameMetrics.GPU_DURATION),m.getMetric(FrameMetrics.DEADLINE),m.getMetric(FrameMetrics.FIRST_DRAW_FRAME)});
        }
    };
    // Public API 36 metric; the SDK's getMetric @IntDef omits it even though the
    // constant is documented. Keep this workaround local; -1 remains unavailable.
    @android.annotation.SuppressLint("WrongConstant")
    private static long frameTimelineId(FrameMetrics metrics) {
        return metrics.getMetric(FrameMetrics.FRAME_TIMELINE_VSYNC_ID);
    }
    private final Choreographer.VsyncCallback callback=new Choreographer.VsyncCallback(){
        @Override public void onVsync(Choreographer.FrameData d){
            if(!measuring)return;
            long entry=System.nanoTime();Choreographer.FrameTimeline timeline=d.getPreferredFrameTimeline();
            long id=timeline.getVsyncId();firstVsync=Math.min(firstVsync,id);lastVsync=Math.max(lastVsync,id);
            synchronized(Measurements.this){add(vsync,new long[]{d.getFrameTimeNanos(),entry,id,timeline.getDeadlineNanos(),timeline.getExpectedPresentationTimeNanos(),System.nanoTime()-entry});}
            Choreographer.getInstance().postVsyncCallback(this);
        }
    };
    Measurements(Activity activity,String token){this.activity=activity;this.token=token;thread.start();handler=new Handler(thread.getLooper());}
    private void add(ArrayList<long[]> target,long[] row){if(target.size()<LIMIT)target.add(row);else truncated=true;}
    private synchronized void error(String message){errors.add(message);}
    void attach(View page){
        try{activity.getWindow().addOnFrameMetricsAvailableListener(frameListener,handler);frameListenerInstalled=true;}catch(RuntimeException e){error("FrameMetrics:"+e);}
        try{
            AttachedSurfaceControl root=page.getRootSurfaceControl();
            if(root==null){error("windowJank:NO_ATTACHED_ROOT");return;}
            registration=root.registerOnJankDataListener(task->handler.post(task),batch->{
                synchronized(Measurements.this){for(SurfaceControl.JankData d:batch){long id=d.getVsyncId();if(id>=firstVsync&&id<=lastVsync)add(jank,new long[]{id,d.getJankType(),d.getActualAppFrameTimeNanos(),d.getScheduledAppFrameTimeNanos()});}}
            });
        }catch(RuntimeException e){error("windowJank:"+e);}
    }
    void begin(){if(measuring||beginNs!=0)return;beginNs=System.nanoTime();measuring=true;Trace.beginAsyncSection("BPROBE.measure."+token,1);Choreographer.getInstance().postVsyncCallback(callback);handler.post(sample);}
    void stop(){
        if(!measuring)return;endNs=System.nanoTime();measuring=false;
        Trace.endAsyncSection("BPROBE.measure."+token,1);Choreographer.getInstance().removeVsyncCallback(callback);handler.removeCallbacks(sample);handler.post(()->sampleResources("end"));
        if(registration!=null)try{registration.removeAfter(lastVsync);}catch(RuntimeException e){error("jankStop:"+e);}
    }
    void input(long scheduledUptime,long before,long after,int action){synchronized(this){if(measuring)add(input,new long[]{scheduledUptime,before,after,action});}}
    private final Runnable sample=new Runnable(){public void run(){if(!measuring||closed)return;sampleResources("measure");handler.postDelayed(this,1000);}};
    private JSONObject process(int pid){
        JSONObject out=TrialPlan.json("pid",pid);
        try{
            String stat=new String(Files.readAllBytes(new File("/proc/"+pid+"/stat").toPath()),StandardCharsets.UTF_8);
            String[] f=stat.substring(stat.lastIndexOf(')')+2).split(" +");
            out.put("userTicks",Long.parseLong(f[11]));out.put("systemTicks",Long.parseLong(f[12]));out.put("minorFaults",Long.parseLong(f[7]));out.put("majorFaults",Long.parseLong(f[9]));out.put("threads",Long.parseLong(f[17]));out.put("startTimeTicks",Long.parseLong(f[19]));out.put("rssPages",Long.parseLong(f[21]));out.put("readable",true);
        }catch(Exception e){try{out.put("readable",false);out.put("error",e.getClass().getSimpleName());}catch(Exception ignored){}}
        return out;
    }
    private void sampleResources(String phase){
        long start=System.nanoTime();
        JSONObject o=TrialPlan.json("phase",phase,"monotonicNs",start,"boottimeNs",SystemClock.elapsedRealtimeNanos(),"processCpuMs",android.os.Process.getElapsedCpuTime(),"javaHeapBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory(),"nativeHeapBytes",Debug.getNativeHeapAllocatedSize(),"thermalStatus",activity.getSystemService(PowerManager.class).getCurrentThermalStatus());
        try{
            String[] fds=new File("/proc/self/fd").list(),tasks=new File("/proc/self/task").list();o.put("fdCount",fds==null?JSONObject.NULL:fds.length);o.put("threadCount",tasks==null?JSONObject.NULL:tasks.length);
            for(String line:Files.readAllLines(new File("/proc/self/status").toPath(),StandardCharsets.UTF_8))if(line.startsWith("VmRSS:"))o.put("rssKiB",Long.parseLong(line.replaceAll("[^0-9]","")));
            for(String key:new String[]{"art.gc.gc-count","art.gc.gc-time","art.gc.bytes-allocated","art.gc.blocking-gc-count","art.gc.blocking-gc-time"}){String value=Debug.getRuntimeStat(key);o.put(key,value==null?JSONObject.NULL:value);}
            JSONArray processes=new JSONArray();List<ActivityManager.RunningAppProcessInfo> live=activity.getSystemService(ActivityManager.class).getRunningAppProcesses();
            if(live!=null)for(ActivityManager.RunningAppProcessInfo p:live)if(p.uid==android.os.Process.myUid()&&p.processName.startsWith(activity.getPackageName())){JSONObject row=process(p.pid);row.put("name",p.processName);processes.put(row);}
            o.put("observedProcesses",processes);o.put("processCoverage","best-effort same-UID enumeration; denied/missing subprocesses are not zero CPU");
        }catch(Exception e){try{o.put("error",e.toString());}catch(Exception ignored){}}
        try{o.put("sampleCostNs",System.nanoTime()-start);}catch(Exception ignored){}
        synchronized(this){if(resources.size()<1024)resources.add(o);else truncated=true;}
    }
    private static JSONArray rows(ArrayList<long[]> source) throws JSONException {JSONArray result=new JSONArray();for(long[] row:source)result.put(new JSONArray(row));return result;}
    synchronized JSONObject report() throws JSONException {
        return TrialPlan.json("clock","CLOCK_MONOTONIC; resource samples also carry CLOCK_BOOTTIME","beginNs",beginNs,"endNs",endNs,
            "vsyncColumns",new JSONArray(new String[]{"frameTimeNs","callbackEntryNs","vsyncId","deadlineNs","expectedPresentNs","collectorCostNs"}),"vsync",rows(vsync),
            "hwuiScope","ACTIVITY_ROOT_ONLY_NOT_GECKO_WEBRENDER_GPU_OR_FLOATING_PAGE","hwuiColumns",new JSONArray(new String[]{"intendedVsyncNs","vsyncId","totalNs","unknownDelayNs","inputNs","animationNs","layoutNs","drawNs","syncNs","commandNs","swapNs","gpuNs","deadlineNs","firstDraw"}),"hwui",rows(hwui),"hwuiListenerDrops",listenerDrops,
            "windowJankScope","PAGE_HOST_VIEWROOT_ONLY_NOT_INDEPENDENT_SURFACEVIEW_OR_RELAY_CHILD; batched best-effort delivery","windowJankColumns",new JSONArray(new String[]{"vsyncId","jankMask","actualAppNs","scheduledAppNs"}),"windowJank",rows(jank),
            "inputColumns",new JSONArray(new String[]{"scheduledUptimeMs","dispatchBeforeMonoNs","dispatchAfterMonoNs","action"}),"input",rows(input),"resources",new JSONArray(resources),"errors",new JSONArray(errors),"truncated",truncated);
    }
    void close(){closed=true;handler.removeCallbacks(sample);activity.runOnUiThread(()->{if(frameListenerInstalled)activity.getWindow().removeOnFrameMetricsAvailableListener(frameListener);});thread.quitSafely();}
}
