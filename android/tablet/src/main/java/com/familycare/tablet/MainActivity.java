package com.familycare.tablet;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity implements SurfaceHolder.Callback,LocationListener {
    final Handler ui=new Handler(Looper.getMainLooper());
    final ExecutorService commands=Executors.newSingleThreadExecutor();
    final ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor();
    final AtomicBoolean gpsSending=new AtomicBoolean();
    final java.util.concurrent.atomic.AtomicInteger queuedTouches=new java.util.concurrent.atomic.AtomicInteger();
    EditText link;TextView status;Button connect,gps,fold;LinearLayout form;FrameLayout frame;SurfaceView surface;
    volatile BridgeClient client;volatile VideoDecoder decoder;
    LocationManager locations;volatile boolean gpsOn=false,foreground=false;volatile String generation="",gpsHint="",gpsSource="";Location lastSent;long sequence=0,lastMove=0;int videoWidth=0,videoHeight=0;
    void message(String s){ui.post(()->status.setText(s));}
    int dp(int n){return (int)(n*getResources().getDisplayMetrics().density);}
    Button button(String text,LinearLayout row){Button b=new Button(this);b.setText(text);row.addView(b);return b;}
    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        locations=(LocationManager)getSystemService(LOCATION_SERVICE);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(10,19,29));
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);
        connect=button("차량 연결",bar);gps=button("탭 GPS 시작",bar);gps.setEnabled(false);fold=button("연결 설정",bar);
        status=new TextView(this);status.setTextColor(Color.WHITE);status.setTextSize(13);status.setPadding(dp(8),0,0,0);bar.addView(status,new LinearLayout.LayoutParams(0,-2,1));root.addView(bar);
        form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(12),0,dp(12),dp(8));
        TextView help=new TextView(this);help.setText("노트북 시연 보조 화면 → ‘탭으로 차량 화면’ QR → 앱에서 연결\n같은 Wi-Fi에서 사용 · 소리와 마이크는 노트북 · 위치(GPS·Wi-Fi)는 이 앱이 열린 동안에만 전달");help.setTextColor(Color.LTGRAY);form.addView(help);
        link=new EditText(this);link.setSingleLine(true);link.setTextSize(14);link.setTextColor(Color.WHITE);link.setHintTextColor(Color.LTGRAY);link.setHint("QR 연결 링크 붙여넣기");link.setSaveEnabled(false);link.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);form.addView(link);root.addView(form);
        frame=new FrameLayout(this);frame.setBackgroundColor(Color.BLACK);surface=new SurfaceView(this);frame.addView(surface,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));root.addView(frame,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);surface.getHolder().addCallback(this);frame.addOnLayoutChangeListener((v,l,t,r,bt,ol,ot,or,ob)->resize());surface.setOnTouchListener(this::touch);
        connect.setOnClickListener(v->{if(client!=null)disconnect("연결을 종료했어요");else start();});
        gps.setOnClickListener(v->{if(gpsOn)stopGps();else requestGps();});
        fold.setOnClickListener(v->form.setVisibility(form.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));
        receive(getIntent());message("노트북과 같은 Wi-Fi에 연결해 주세요");
        heartbeat.scheduleWithFixedDelay(()->{BridgeClient c=client;if(c==null||c.session.isEmpty())return;try{
            JSONObject s=c.post("/heartbeat",new JSONObject());
            if(gpsOn)message(s.optBoolean("gpsFresh")?"탭 위치 연결됨("+gpsSource+") · 차량 화면 조작 가능":!gpsHint.isEmpty()?gpsHint:"위치 수신 대기 · 창가로 옮기거나 탭의 'Google 위치 정확도'를 켜 주세요");
        }catch(Exception e){ui.post(()->{if(client==c)disconnect("Wi-Fi 연결이 끊겼어요. 다시 연결해 주세요");});}},2,2,TimeUnit.SECONDS);
    }
    void receive(Intent intent){
        if(intent!=null&&intent.getData()!=null && "familycare-tablet".equals(intent.getData().getScheme())){
            String value=intent.getData().getQueryParameter("url");if(value!=null){link.setText(value);form.setVisibility(View.VISIBLE);}
            intent.setData(null);
        }
    }
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);receive(i);}
    @Override protected void onStart(){super.onStart();foreground=true;}
    @Override protected void onStop(){foreground=false;disconnect("앱을 벗어나 연결과 GPS 전달을 중지했어요");super.onStop();}
    @Override protected void onDestroy(){heartbeat.shutdownNow();commands.shutdown();super.onDestroy();}
    void start(){
        try{
            if(!surface.getHolder().getSurface().isValid())throw new IllegalArgumentException("화면 준비를 기다려 주세요");
            BridgeClient c=new BridgeClient(Pairing.parse(link.getText().toString()));client=c;
            VideoDecoder d=new VideoDecoder();decoder=d;
            connect.setText("연결 종료");form.setVisibility(View.GONE);link.clearFocus();
            ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(link.getWindowToken(),0);
            message("차량 영상을 연결하고 있어요…");
            new Thread(()->{try(java.io.DataInputStream in=c.stream()){
                if(client!=c||!foreground){c.closeVideo();return;}
                d.play(in,surface.getHolder().getSurface(),(w,h)->ui.post(()->{if(client==c){videoWidth=w;videoHeight=h;resize();gps.setEnabled(true);message("차량 화면 연결됨 · 탭으로 조작할 수 있어요");}}));
            }catch(Exception e){if(client==c)message(e.getMessage()==null?"차량 화면을 다시 연결하세요":e.getMessage());}
            finally{ui.post(()->{if(client==c)disconnect("차량 화면 연결 종료 · 다시 연결해 주세요");});}},"tablet-video").start();
        }catch(Exception e){message(e.getMessage());}
    }
    void disconnect(String reason){
        locations.removeUpdates(this);gpsOn=false;generation="";gpsSending.set(false);
        BridgeClient c=client;client=null;VideoDecoder d=decoder;decoder=null;if(d!=null)d.stop();
        if(c!=null)new Thread(()->{try{if(!c.session.isEmpty())c.post("/disconnect",new JSONObject());}catch(Exception ignored){}finally{c.closeVideo();}},"tablet-disconnect").start();
        gps.setText("탭 GPS 시작");gps.setEnabled(false);connect.setText("차량 연결");message(reason);
    }
    void resize(){
        if(videoWidth<=0||videoHeight<=0||frame.getWidth()==0||frame.getHeight()==0)return;
        float scale=Math.min((float)frame.getWidth()/videoWidth,(float)frame.getHeight()/videoHeight);
        int w=Math.max(1,(int)(videoWidth*scale)),h=Math.max(1,(int)(videoHeight*scale));
        if(surface.getWidth()!=w||surface.getHeight()!=h)surface.setLayoutParams(new FrameLayout.LayoutParams(w,h,Gravity.CENTER));
    }
    boolean touch(View view,MotionEvent e){
        BridgeClient c=client;if(c==null||c.session.isEmpty()||videoWidth==0)return true;
        int action=e.getActionMasked();long now=SystemClock.elapsedRealtime();
        if(action==MotionEvent.ACTION_MOVE&&now-lastMove<33)return true;if(action==MotionEvent.ACTION_MOVE)lastMove=now;
        if(action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_CANCEL){for(int i=0;i<e.getPointerCount();i++)sendTouch(c,e,i,action==MotionEvent.ACTION_CANCEL?3:2);}
        else if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN)sendTouch(c,e,e.getActionIndex(),0);
        else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP)sendTouch(c,e,e.getActionIndex(),1);
        return true;
    }
    void sendTouch(BridgeClient c,MotionEvent e,int i,int action){
        float x=Math.max(0,Math.min(surface.getWidth()-1,e.getX(i))),y=Math.max(0,Math.min(surface.getHeight()-1,e.getY(i)));
        int[] p=Pairing.point(x,y,surface.getWidth(),surface.getHeight(),videoWidth,videoHeight);if(p==null)return;
        int pointer=e.getPointerId(i);if(pointer>=10)return;
        if(action==2&&queuedTouches.get()>2)return;queuedTouches.incrementAndGet();
        commands.execute(()->{try{if(client==c)c.post("/touch",new JSONObject().put("x",p[0]).put("y",p[1]).put("pointer",pointer).put("action",action));}catch(Exception ex){message("터치 연결을 확인하세요");}finally{queuedTouches.decrementAndGet();}});
    }
    void requestGps(){
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},12);return;}
        if(!locations.isLocationEnabled()){message("탭 설정에서 위치 사용을 켜 주세요");startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));return;}
        if(gpsProviders().isEmpty()){message("이 탭에서는 위치를 받을 수 없어요. 노트북의 실내 자동 주행을 이용하세요");return;}
        BridgeClient c=client;if(c==null||c.session.isEmpty())return;gps.setEnabled(false);
        commands.execute(()->{try{String gen=c.post("/gps/start",new JSONObject()).getString("generation");ui.post(()->{
            if(client!=c||!foreground)return;
            generation=gen;sequence=0;lastSent=null;gpsHint="";gpsSource="";gpsOn=true;gps.setEnabled(true);gps.setText("GPS 중지");
            try{
                java.util.List<String> started=listenGps();
                if(started.isEmpty()){stopGps();message("탭 위치를 시작하지 못했어요. 탭의 위치 설정을 확인해 주세요");return;}
                StringBuilder names=new StringBuilder();for(String p:started)names.append(names.length()==0?"":" + ").append(GpsChoice.label(p));
                message("위치 수신 대기 · "+names);
            }catch(SecurityException e){stopGps();message("정확한 위치 권한을 허용해 주세요");}
        });}catch(Exception e){ui.post(()->gps.setEnabled(client!=null));message(e.getMessage());}});
    }
    /** GPS alone gets no fix indoors; map apps also use Wi-Fi/cell (network) and fused locations. */
    java.util.List<String> gpsProviders(){
        java.util.List<String> all=locations.getAllProviders(),use=new java.util.ArrayList<>();
        if(Build.VERSION.SDK_INT>=31)use.add(LocationManager.FUSED_PROVIDER);
        use.add(LocationManager.GPS_PROVIDER);use.add(LocationManager.NETWORK_PROVIDER);
        use.removeIf(p->!all.contains(p)||!locations.isProviderEnabled(p));
        return use;
    }
    java.util.List<String> listenGps(){
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("location permission");
        java.util.List<String> started=new java.util.ArrayList<>();
        for(String p:gpsProviders()){
            try{
                if(Build.VERSION.SDK_INT>=31&&LocationManager.FUSED_PROVIDER.equals(p))
                    locations.requestLocationUpdates(p,new LocationRequest.Builder(1000).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).setMinUpdateIntervalMillis(500).build(),getMainExecutor(),this);
                else locations.requestLocationUpdates(p,500,0,this,Looper.getMainLooper());
                started.add(p);
            }catch(IllegalArgumentException ignored){}
        }
        return started;
    }
    void stopGps(){
        locations.removeUpdates(this);gpsOn=false;generation="";gps.setText("탭 GPS 시작");
        BridgeClient c=client;if(c!=null)commands.execute(()->{try{c.post("/gps/stop",new JSONObject());message("GPS 전달 중지 · 노트북 실내 자동 주행 사용 가능");}catch(Exception e){message("GPS 중지 요청을 확인하세요. 연결을 다시 확인해 주세요");}});
    }
    @Override public void onLocationChanged(Location l){
        BridgeClient c=client;String gen=generation;
        if(!gpsOn||!foreground||c==null||gen.isEmpty())return;
        long age=(SystemClock.elapsedRealtimeNanos()-l.getElapsedRealtimeNanos())/1_000_000;
        if(!GpsChoice.usable(l.hasAccuracy(),l.getAccuracy(),age)){
            if(!l.hasAccuracy()||l.getAccuracy()>GpsChoice.MAX_ACCURACY_M)gpsHint="위치 오차 "+(l.hasAccuracy()?Math.round(l.getAccuracy())+"m":"알 수 없음")+" · 50m 이내일 때 전달해요. 창가로 옮겨 보세요";
            return;
        }
        Location last=lastSent;
        if(last!=null&&!GpsChoice.prefer(l.getProvider(),l.getElapsedRealtimeNanos(),l.getAccuracy(),last.getProvider(),last.getElapsedRealtimeNanos(),last.getAccuracy()))return;
        if(!gpsSending.compareAndSet(false,true))return;
        lastSent=l;gpsHint="";gpsSource=GpsChoice.label(l.getProvider());
        long seq=++sequence;
        commands.execute(()->{try{
            if(client!=c||!gpsOn||!generation.equals(gen))return;
            long freshAge=(SystemClock.elapsedRealtimeNanos()-l.getElapsedRealtimeNanos())/1_000_000;
            if(freshAge>GpsChoice.MAX_AGE_MS)return;
            c.post("/gps",new JSONObject().put("generation",gen).put("sequence",seq).put("latitude",l.getLatitude()).put("longitude",l.getLongitude()).put("accuracy",l.getAccuracy()).put("ageMs",freshAge).put("speed",l.hasSpeed()?l.getSpeed():0));
        }catch(Exception e){message(e.getMessage());}finally{gpsSending.set(false);}});
    }
    @Override public void onRequestPermissionsResult(int code,String[] p,int[] results){super.onRequestPermissionsResult(code,p,results);if(code==12){if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)requestGps();else message("실제 GPS 사용에는 정확한 위치 허용이 필요해요");}}
    @Override public void onProviderDisabled(String p){if(!locations.isLocationEnabled())message("위치 사용이 꺼졌어요. 위치 전달 대기 중");}
    @Override public void onProviderEnabled(String p){}
    @Override public void onStatusChanged(String p,int s,Bundle b){}
    @Override public void surfaceCreated(SurfaceHolder h){}
    @Override public void surfaceChanged(SurfaceHolder h,int f,int w,int he){}
    @Override public void surfaceDestroyed(SurfaceHolder h){disconnect("화면 연결을 중지했어요");}
}
