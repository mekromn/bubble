package com.mekromn.bubble.probe;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.util.Base64;
import android.view.Choreographer;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoDisplay;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One clean-profile case per process. All Gecko lifecycle calls remain on this process's main thread. */
public final class TrialActivity extends Activity implements GeckoDisplay.NewSurfaceProvider {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ArrayList<Double> uiIntervals=new ArrayList<>();
    private final JSONArray refreshEvents=new JSONArray();
    private final ArrayList<JSONObject> lifecycleEvents=new ArrayList<>();
    private JSONObject spec,capabilities,startEnvironment,endEnvironment,pageReport,engineIdentity;
    private ResultReceiver receiver;
    private String token,suite,html;
    private TextView progress;
    private FrameLayout root,slot,page;
    private View chrome;
    private SurfaceView raw;
    private GeckoView geckoView;
    private GeckoRuntime runtime;
    private GeckoSession session;
    private GeckoDisplay display;
    private SurfaceControl output;
    private Surface producer;
    private long nativeHandle,lastUiNs;
    private boolean ending,started,measuring,firstPaint,firstComposite,visualConfirmed,controlCreating,published;
    private int viewReadyRetries;
    private boolean touchedDuringMeasurement;
    private int viewportWidth,viewportHeight,retries;
    private float maxRate,vote;
    private long measureBeginNs,measureEndNs;
    private long apzStart,gestureDown,lastGesture=-1;
    private boolean gestureActive;
    private int injectedInputEvents;
    private WindowManager manager;
    private DisplayManager displays;
    private final Choreographer.FrameCallback uiMeter=new Choreographer.FrameCallback(){
        @Override public void doFrame(long ns){
            if(!measuring)return;
            if(lastUiNs!=0&&uiIntervals.size()<16384)uiIntervals.add((ns-lastUiNs)/1e6);
            lastUiNs=ns;
            Choreographer.getInstance().postFrameCallback(this);
        }
    };
    private final DisplayManager.DisplayListener displayListener=new DisplayManager.DisplayListener(){
        public void onDisplayAdded(int id){}
        public void onDisplayRemoved(int id){if(getDisplay()!=null&&getDisplay().getDisplayId()==id)end("DISPLAY_REMOVED",null);}
        public void onDisplayChanged(int id){recordRefresh();}
    };
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        try{
            spec=new JSONObject(getIntent().getStringExtra("spec"));token=spec.getString("token");suite=getIntent().getStringExtra("suite");
            receiver=getIntent().getParcelableExtra("receiver",ResultReceiver.class);
            if(receiver==null||!token.matches("[0-9a-f-]{36}")||suite==null||!suite.matches("[0-9A-Za-z-]+")){finish();return;}
        }catch(Exception e){finish();return;}
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> end("CANCELLED", null));
        signal(1,"starting");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        manager=getSystemService(WindowManager.class);displays=getSystemService(DisplayManager.class);
        root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);
        root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets i=insets.getInsets(android.view.WindowInsets.Type.systemBars());v.setPadding(i.left,i.top,i.right,i.bottom);return insets;});
        LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.VERTICAL);controls.setPadding(18,48,18,0);
        progress=new TextView(this);progress.setTextColor(0xff80e5c8);progress.setTextSize(16);progress.setText(label()+"\nPreparing isolated renderer…");controls.addView(progress);
        LinearLayout buttons=new LinearLayout(this);
        buttons.addView(button("Visible",()->{visualConfirmed=true;progress.setText(label()+"\nVisual confirmation recorded");}));
        buttons.addView(button("Black / broken",()->end("VISUAL_FAIL",null)));
        buttons.addView(button("Stop",()->end("CANCELLED",null)));
        controls.addView(buttons);root.addView(controls,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        slot=new FrameLayout(this);root.addView(slot);setContentView(root);
        displays.registerDisplayListener(displayListener,main);
        root.post(this::prepare);
    }
    private String label(){return spec.optString("variant")+" · "+(spec.optBoolean("floating")?"FLOATING":"FULLSCREEN WINDOW")+" · "+spec.optString("workload");}
    private Button button(String text,Runnable action){Button b=new Button(this);b.setText(text);b.setTextSize(11);b.setOnClickListener(v->{touchedDuringMeasurement|=measuring;action.run();});return b;}
    private void signal(int code,String phase){
        lifecycleEvents.add(TrialPlan.json("elapsedNs",SystemClock.elapsedRealtimeNanos(),"phase",phase));
        android.util.Log.i("BubbleProbe",token+" "+phase);
        Bundle b=new Bundle();b.putString("token",token);b.putInt("pid",Process.myPid());b.putString("phase",phase);receiver.send(code,b);
    }
    private void prepare(){
        if(ending)return;
        Display d=getDisplay();if(d==null){end("NO_DISPLAY",null);return;}
        Display.Mode current=d.getMode();maxRate=current.getRefreshRate();
        for(Display.Mode m:d.getSupportedModes())if(m.getPhysicalWidth()==current.getPhysicalWidth()&&m.getPhysicalHeight()==current.getPhysicalHeight())maxRate=Math.max(maxRate,m.getRefreshRate());
        vote=spec.optBoolean("voteMax")?maxRate:0;
        WindowManager.LayoutParams attributes=getWindow().getAttributes();attributes.preferredDisplayModeId=0;attributes.preferredRefreshRate=vote;getWindow().setAttributes(attributes);
        root.setRequestedFrameRate(vote);
        // Match physical page pixels, not entire window dimensions. No up/downsampling or text stretching.
        viewportWidth=Math.max(1,(int)(root.getWidth()*.88f));
        viewportHeight=Math.max(1,(int)(root.getHeight()*.55f));
        FrameLayout.LayoutParams position=new FrameLayout.LayoutParams(viewportWidth,viewportHeight,Gravity.CENTER);position.topMargin=(int)(40*getResources().getDisplayMetrics().density);slot.setLayoutParams(position);
        startEnvironment=environment();recordRefresh();
        if(getSystemService(PowerManager.class).getCurrentThermalStatus()>=PowerManager.THERMAL_STATUS_SEVERE){end("THERMAL_GUARD_SKIPPED",null);return;}
        worker.execute(()->{
            try{
                capabilities=new JSONObject(NativeProbe.capabilities(viewportWidth,viewportHeight));
                try(java.io.InputStream in=getAssets().open("engine-identity.json")){engineIdentity=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
                try(java.io.InputStream in=getAssets().open("workload.html")){html=new String(in.readAllBytes(),StandardCharsets.UTF_8);}
                File profile=new File(getCacheDir(),"trial-profiles/"+token);if(!profile.isDirectory()&&!profile.mkdirs())throw new java.io.IOException("Cannot create isolated profile");
                main.post(()->startEngine(profile));
            }catch(Exception e){main.post(()->end("PREFLIGHT_ERROR: "+e.getClass().getSimpleName(),null));}
        });
    }
    private void startEngine(File profile){
        if(ending)return;
        try{
            signal(3,"creating-engine");
            runtime=GeckoRuntime.create(this,new GeckoRuntimeSettings.Builder().arguments(new String[]{"-profile",profile.getAbsolutePath()}).build());
            session=new GeckoSession(new GeckoSessionSettings.Builder().allowJavascript(true)
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE).viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE).suspendMediaWhenInactive(false).build());
            session.setContentDelegate(new GeckoSession.ContentDelegate(){
                @Override public void onTitleChange(GeckoSession s,String title){receivePage(title);}
                @Override public void onFirstContentfulPaint(GeckoSession s){firstPaint=true;lifecycleEvents.add(TrialPlan.json("elapsedNs",SystemClock.elapsedRealtimeNanos(),"phase","gecko-first-contentful-paint"));}
                @Override public void onFirstComposite(GeckoSession s){firstComposite=true;lifecycleEvents.add(TrialPlan.json("elapsedNs",SystemClock.elapsedRealtimeNanos(),"phase","gecko-first-composite"));}
                @Override public void onCrash(GeckoSession s){end("GECKO_CONTENT_CRASH",null);}
                @Override public void onKill(GeckoSession s){end("GECKO_CONTENT_KILLED",null);}
            });
            session.setNavigationDelegate(new GeckoSession.NavigationDelegate(){
                @Override public GeckoResult<org.mozilla.geckoview.AllowOrDeny> onLoadRequest(GeckoSession s,GeckoSession.NavigationDelegate.LoadRequest request){
                    return request.uri.startsWith("data:text/html")||request.uri.equals("about:blank")?GeckoResult.allow():GeckoResult.deny();
                }
            });
            session.open(runtime);session.setActive(true);session.setFocused(true);
            page=new FrameLayout(this);page.setBackgroundColor(Color.BLACK);page.setRequestedFrameRate(vote);
            String renderer=spec.optString("renderer");
            if(renderer.equals("geckoview")||renderer.equals("texture")){
                geckoView=new GeckoView(this);geckoView.setViewBackend(renderer.equals("texture")?GeckoView.BACKEND_TEXTURE_VIEW:GeckoView.BACKEND_SURFACE_VIEW);
                geckoView.setRequestedFrameRate(vote);page.addView(geckoView,new FrameLayout.LayoutParams(-1,-1));geckoView.setSession(session);
            }else if(renderer.equals("raw")){
                raw=new SurfaceView(this){
                    @Override public boolean onTouchEvent(MotionEvent event){touchedDuringMeasurement|=measuring;session.getPanZoomController().onTouchEvent(event);return true;}
                };
                raw.setZOrderOnTop(true);raw.getHolder().setFormat(PixelFormat.OPAQUE);raw.setRequestedFrameRate(vote);raw.setFocusableInTouchMode(true);
                page.addView(raw,new FrameLayout.LayoutParams(-1,-1));
                raw.getHolder().addCallback(new SurfaceHolder.Callback(){
                    public void surfaceCreated(SurfaceHolder holder){}
                    public void surfaceChanged(SurfaceHolder holder,int format,int width,int height){publish(holder.getSurface(),raw.getSurfaceControl(),width,height);}
                    public void surfaceDestroyed(SurfaceHolder holder){if(published&&display!=null){display.surfaceDestroyed();published=false;}}
                });
            }else{
                View input=new View(this){@Override public boolean onTouchEvent(MotionEvent event){touchedDuringMeasurement|=measuring;session.getPanZoomController().onTouchEvent(event);return true;}};
                input.setFocusableInTouchMode(true);input.setRequestedFrameRate(vote);page.addView(input,new FrameLayout.LayoutParams(-1,-1));
            }
            attachPage();
            if(geckoView!=null)page.postOnAnimation(this::awaitViewSurface);
            else{
                session.getAccessibility().setView(page); // A real ViewParent, not the SurfaceView.
                session.getTextInput().setView(page.getChildAt(0));
                if(raw==null)page.postOnAnimation(this::createRelay);
            }
        }catch(RuntimeException e){end("SETUP_ERROR: "+e,null);}
    }
    private void awaitViewSurface(){
        if(ending||started)return;
        if(!page.isAttachedToWindow()||page.getWidth()!=viewportWidth||page.getHeight()!=viewportHeight||!hasReadySurface(geckoView)){
            if(++viewReadyRetries>240){end("GECKOVIEW_SURFACE_READINESS_TIMEOUT",null);return;}
            page.postOnAnimation(this::awaitViewSurface);return;
        }
        // Let GeckoView publish its own display normally; never acquire a second display here.
        voteTree(page);geckoView.requestFocus();session.setActive(true);session.setFocused(true);
        signal(3,"geckoview-surface-ready");startWorkload();
    }
    private boolean hasReadySurface(View view){
        if(view instanceof SurfaceView){
            SurfaceView surface=(SurfaceView)view;
            return surface.getWidth()>0&&surface.getHeight()>0&&surface.getHolder().getSurface().isValid();
        }
        if(view instanceof android.view.TextureView)return ((android.view.TextureView)view).isAvailable();
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)
            if(hasReadySurface(((ViewGroup)view).getChildAt(i)))return true;
        return false;
    }
    private void voteTree(View view){
        view.setRequestedFrameRate(vote);
        if(view instanceof SurfaceView){
            SurfaceHolder holder=((SurfaceView)view).getHolder();
            if(holder.getSurface().isValid()&&vote>0)holder.getSurface().setFrameRate(vote,Surface.FRAME_RATE_COMPATIBILITY_AT_LEAST,Surface.CHANGE_FRAME_RATE_ALWAYS);
            holder.addCallback(new SurfaceHolder.Callback(){
                public void surfaceCreated(SurfaceHolder h){}
                public void surfaceChanged(SurfaceHolder h,int f,int w,int height){if(vote>0)h.getSurface().setFrameRate(vote,Surface.FRAME_RATE_COMPATIBILITY_AT_LEAST,Surface.CHANGE_FRAME_RATE_ALWAYS);}
                public void surfaceDestroyed(SurfaceHolder h){}
            });
        }
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)voteTree(((ViewGroup)view).getChildAt(i));
    }
    private final Runnable apzInput=new Runnable(){
        @Override public void run(){
            if(ending||session==null)return;
            long now=SystemClock.uptimeMillis(),elapsed=now-apzStart,index=elapsed/1500,phase=elapsed%1500;
            if(index!=lastGesture){lastGesture=index;gestureDown=now;gestureActive=true;sendInput(MotionEvent.ACTION_DOWN,now,phase,index);}
            else if(gestureActive){
                if(phase>=280){sendInput(MotionEvent.ACTION_UP,now,280,index);gestureActive=false;}
                else sendInput(MotionEvent.ACTION_MOVE,now,phase,index);
            }
            main.postAtTime(this,now+8);
        }
    };
    private void sendInput(int action,long now,long phase,long index){
        float fraction=Math.min(1f,phase/280f);float from=(index%2==0)?.8f:.2f,to=1f-from;
        MotionEvent event=MotionEvent.obtain(gestureDown,now,action,viewportWidth*.5f,viewportHeight*(from+(to-from)*fraction),0);
        // Synthetic input is confined to this generated, network-blocked document. Never sent to a real tab.
        if(geckoView!=null)geckoView.dispatchTouchEvent(event);else session.getPanZoomController().onTouchEvent(event);
        event.recycle();injectedInputEvents++;
    }
    private void attachPage(){
        if(spec.optBoolean("floating")){
            int[] xy=new int[2];slot.getLocationOnScreen(xy);
            WindowManager.LayoutParams p=new WindowManager.LayoutParams(viewportWidth,viewportHeight,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,PixelFormat.OPAQUE);
            p.gravity=Gravity.TOP|Gravity.LEFT;p.x=xy[0];p.y=xy[1];p.preferredRefreshRate=vote;p.setTitle("BubbleProbe_"+token+"_"+spec.optString("variant"));manager.addView(page,p);
            if(spec.optBoolean("extraWindow")){
                TextView t=new TextView(this);t.setText("Separate chrome window · no page blur");t.setTextColor(Color.WHITE);t.setBackgroundColor(0xff182329);chrome=t;
                WindowManager.LayoutParams c=new WindowManager.LayoutParams(viewportWidth,(int)(40*getResources().getDisplayMetrics().density),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,PixelFormat.OPAQUE);
                c.gravity=Gravity.TOP|Gravity.LEFT;c.x=xy[0];c.y=xy[1]+viewportHeight+8;c.preferredRefreshRate=vote;c.setTitle("BubbleProbeChrome_"+token);manager.addView(chrome,c);
            }
        }else slot.addView(page,new FrameLayout.LayoutParams(-1,-1));
    }
    private void createRelay(){
        if(ending||controlCreating||nativeHandle!=0)return;
        if(!page.isAttachedToWindow()||page.getRootSurfaceControl()==null){retryRelay();return;}
        SurfaceControl control=null;
        try{
            control=new SurfaceControl.Builder().setName("BubbleProbeAHB_"+token).setBufferSize(viewportWidth,viewportHeight).build();
            SurfaceControl.Transaction tx=page.getRootSurfaceControl().buildReparentTransaction(control);
            if(tx==null){control.release();retryRelay();return;}
            int[] xy=new int[2];page.getLocationInWindow(xy);
            tx.setLayer(control,1).setOpaque(control,true).setVisibility(control,true).setPosition(control,xy[0],xy[1])
                .setCrop(control,new Rect(0,0,viewportWidth,viewportHeight)).apply();tx.close();
            output=control;controlCreating=true;SurfaceControl nativeOutput=control;
            worker.execute(()->{
                long handle=0;Surface surface=null;String failure=null;
                try{
                    handle=NativeProbe.create(viewportWidth,viewportHeight,nativeOutput,vote,spec.optInt("maxImages"),spec.optInt("drainLimit"),spec.optBoolean("backpressure"));
                    if(handle!=0)surface=NativeProbe.surface(handle);
                    if(handle==0||surface==null||!surface.isValid())failure="NATIVE_CREATE_OR_SURFACE_FAILED";
                }catch(Throwable e){failure="NATIVE_SETUP_ERROR: "+e.getClass().getSimpleName();}
                final long h=handle;final Surface s=surface;final String error=failure;
                main.post(()->{
                    controlCreating=false;
                    if(ending){if(s!=null)s.release();if(h!=0)worker.execute(()->NativeProbe.finish(h));return;}
                    nativeHandle=h;producer=s;
                    if(error!=null){end(error,null);return;}
                    publish(s,null,viewportWidth,viewportHeight);
                });
            });
        }catch(RuntimeException e){if(control!=null&&control!=output)control.release();end("SURFACECONTROL_SETUP_ERROR: "+e,null);}
    }
    private void retryRelay(){if(++retries>120){end("SURFACECONTROL_PARENT_TIMEOUT",null);return;}page.postOnAnimation(this::createRelay);}
    private void publish(Surface surface,SurfaceControl sc,int width,int height){
        if(ending||session==null||surface==null||!surface.isValid())return;
        try{
            if(display==null)display=session.acquireDisplay();
            if(vote>0)surface.setFrameRate(vote,Surface.FRAME_RATE_COMPATIBILITY_AT_LEAST,Surface.CHANGE_FRAME_RATE_ALWAYS);
            GeckoDisplay.SurfaceInfo.Builder info=new GeckoDisplay.SurfaceInfo.Builder(surface).newSurfaceProvider(this).size(width,height);
            if(sc!=null)info.surfaceControl(sc);
            display.surfaceChanged(info.build());published=true;
            int[] xy=new int[2];page.getLocationOnScreen(xy);display.screenOriginChanged(xy[0],xy[1]);
            session.setActive(true);session.setFocused(true);startWorkload();
        }catch(RuntimeException e){end("GECKO_SURFACE_ERROR: "+e,null);}
    }
    @Override public void requestNewSurface(){main.post(()->end("ENGINE_REQUESTED_NEW_SURFACE",null));}
    private void startWorkload(){
        if(ending||started)return;started=true;signal(3,"loading-workload");
        JSONObject config=TrialPlan.json("token",token,"label",spec.optString("variant"),"workload",spec.optString("workload"),"warmupMs",spec.optInt("warmupMs"),"measureMs",spec.optInt("measureMs"));
        String content=html.replace("__CONFIG__",config.toString());
        session.loadUri("data:text/html;charset=utf-8;base64,"+Base64.encodeToString(content.getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP));
    }
    private void receivePage(String title){
        if(ending||title==null||!title.startsWith("BPROBE:"))return;
        try{
            JSONObject packet=new JSONObject(title.substring(7));if(!token.equals(packet.optString("token")))return;
            switch(packet.optString("phase")){
                case "waiting-for-paint":progress.setText(label()+"\nWaiting for initial content paint");signal(3,"waiting-for-paint");break;
                case "readiness-failed":end("PAGE_FIRST_PAINT_TIMEOUT",packet);break;
                case "warmup":progress.setText(label()+"\nWarm-up · verify that the page is moving");signal(3,"warm-up");
                    if(spec.optString("workload").equals("apz")){apzStart=SystemClock.uptimeMillis();main.post(apzInput);}break;
                case "measure":
                    measuring=true;measureBeginNs=SystemClock.elapsedRealtimeNanos();lastUiNs=0;startEnvironment=environment();recordRefresh();
                    if(nativeHandle!=0)NativeProbe.measuring(nativeHandle,true);
                    Choreographer.getInstance().postFrameCallback(uiMeter);
                    progress.setText(label()+"\nMeasuring · do not touch or move the window");signal(3,"measuring");break;
                case "done":
                    measuring=false;main.removeCallbacks(apzInput);measureEndNs=SystemClock.elapsedRealtimeNanos();pageReport=packet;endEnvironment=environment();
                    Choreographer.getInstance().removeFrameCallback(uiMeter);if(nativeHandle!=0)NativeProbe.measuring(nativeHandle,false);
                    progress.setText(label()+"\nSaving measured results…");main.postDelayed(()->end(visualConfirmed?"OK_VISIBLE":"OK_VISUAL_UNCONFIRMED",pageReport),400);break;
                default:break;
            }
        }catch(Exception e){end("PAGE_REPORT_PARSE_ERROR",null);}
    }
    private JSONObject environment(){
        Display d=getDisplay();PowerManager power=getSystemService(PowerManager.class);
        Intent b=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        JSONArray modes=new JSONArray();if(d!=null)for(Display.Mode m:d.getSupportedModes())modes.put(TrialPlan.json("id",m.getModeId(),"width",m.getPhysicalWidth(),"height",m.getPhysicalHeight(),"reportedHz",m.getRefreshRate()));
        return TrialPlan.json("elapsedRealtimeNs",SystemClock.elapsedRealtimeNanos(),"model",android.os.Build.MODEL,"device",android.os.Build.DEVICE,
            "sdk",android.os.Build.VERSION.SDK_INT,"build",android.os.Build.DISPLAY,"powerSave",power.isPowerSaveMode(),"thermalStatus",power.getCurrentThermalStatus(),
            "batteryTemperatureC",b==null?JSONObject.NULL:b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,0)/10.0,
            "charging",b!=null&&b.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0,"runnerPssKiB",Debug.getPss(),"runnerNativeHeapBytes",Debug.getNativeHeapAllocatedSize(),
            "reportedDisplayHz",d==null?JSONObject.NULL:d.getRefreshRate(),"displayModeId",d==null?JSONObject.NULL:d.getMode().getModeId(),"supportedModes",modes);
    }
    private void recordRefresh(){Display d=getDisplay();if(d!=null)refreshEvents.put(TrialPlan.json("elapsedNs",SystemClock.elapsedRealtimeNanos(),"reportedHz",d.getRefreshRate(),"mode",d.getMode().getModeId()));}
    private void end(String result,JSONObject pageData){
        if(ending)return;ending=true;measuring=false;main.removeCallbacks(apzInput);
        Choreographer.getInstance().removeFrameCallback(uiMeter);
        if(displays!=null)displays.unregisterDisplayListener(displayListener);
        long handle=nativeHandle;nativeHandle=0;
        if(handle!=0)NativeProbe.measuring(handle,false);
        JSONObject report=TrialPlan.json("schema",1,"probeVersion",BuildConfig.VERSION_NAME,"spec",spec,"status",result,"engine",BuildConfig.GECKO_VERSION,"engineRepository",BuildConfig.GECKO_MAVEN,
            "source",BuildConfig.SOURCE_SHA,"engineArtifact",engineIdentity==null?JSONObject.NULL:engineIdentity,"engineProfile","new stock profile per trial; no Bubble extensions or production load",
            "viewportWidthPx",viewportWidth,"viewportHeightPx",viewportHeight,"requestedHz",vote,"firstContentfulPaint",firstPaint,"firstComposite",firstComposite,"lifecycleEvents",new JSONArray(lifecycleEvents),
            "visualConfirmed",visualConfirmed,"touchedDuringMeasurement",touchedDuringMeasurement,"injectedInputEvents",injectedInputEvents,"measureBeginAndroidNs",measureBeginNs,"measureEndAndroidNs",measureEndNs,
            "uiChoreographerIntervalsMs",new JSONArray(uiIntervals),"reportedRefreshEvents",refreshEvents,
            "environmentStart",startEnvironment==null?JSONObject.NULL:startEnvironment,"environmentEnd",endEnvironment==null?environment():endEnvironment,
            "capabilities",capabilities==null?JSONObject.NULL:capabilities,"page",pageData==null?JSONObject.NULL:pageData,
            "hwcComposition","NOT_MEASURED_requires_SurfaceFlinger_or_Perfetto_trace","touchToPhotonMs",JSONObject.NULL,
            "clockDomains","native frame timestamps and Choreographer: monotonic; Android elapsed timestamps: boottime; JS: performance time origin",
            "limitations","APZ input is synthetic, not hardware touch-to-photon. Scripted scrolling and repaint are separate workloads. rAF/UI callbacks are not physical-frame counts. TextureView is comparator only; front-buffer capability is not implementation.");
        try{
            // Legal Gecko lifecycle thread. If Gecko blocks here the other process's watchdog records it.
            if(display!=null){if(published)display.surfaceDestroyed();published=false;session.releaseDisplay(display);display=null;}
            if(geckoView!=null)geckoView.releaseSession();
            if(session!=null){session.getAccessibility().setView(null);session.getTextInput().setView(null);session.close();}
            if(producer!=null){producer.release();producer=null;}
            // Drop the Java reference before native teardown, so the native reference is the last owner.
            if(output!=null&&!controlCreating){output.release();output=null;}
            if(page!=null&&spec.optBoolean("floating")&&page.isAttachedToWindow())manager.removeViewImmediate(page);
            if(chrome!=null&&chrome.isAttachedToWindow())manager.removeViewImmediate(chrome);
        }catch(RuntimeException e){try{report.put("lifecycleError",e.toString());report.put("status","LIFECYCLE_ERROR");}catch(Exception ignored){}}
        final boolean paintObserved=firstPaint;
        worker.execute(()->{
            try{
                JSONObject nativeReport=handle==0?null:new JSONObject(NativeProbe.finish(handle));
                report.put("native",nativeReport==null?JSONObject.NULL:nativeReport);
                if(nativeReport!=null&&report.optString("status").startsWith("OK")){
                    if(nativeReport.optLong("submittedTotal")==0)report.put("status","NO_NATIVE_FRAMES");
                    else if(nativeReport.optInt("outstandingAtTeardown")>0)report.put("status","TEARDOWN_LEASE_TIMEOUT");
                    else if(nativeReport.optLong("errorsTotal")>0)report.put("status","NATIVE_FRAME_ERRORS");
                }
                if(!paintObserved){
                    report.put("paintReadinessFailure","NO_FIRST_CONTENTFUL_PAINT");
                    if(report.optString("status").startsWith("OK"))report.put("status","NO_FIRST_CONTENTFUL_PAINT");
                }
                File file=new File(new File(new File(getFilesDir(),"benchmarks"),suite),token+".json");
                ProbeActivity.write(file,report.toString(2));
            }catch(Exception e){
                try{report.put("status","REPORT_OR_NATIVE_FINISH_ERROR");report.put("error",e.toString());ProbeActivity.write(new File(new File(new File(getFilesDir(),"benchmarks"),suite),token+".json"),report.toString());}catch(Exception ignored){}
            }
            main.post(()->{ finish(); signal(2,"done"); });
        });
    }
    @Override public void onUserLeaveHint(){super.onUserLeaveHint();end("CANCELLED",null);}
    @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);end("CONFIGURATION_CHANGED",null);}
}
