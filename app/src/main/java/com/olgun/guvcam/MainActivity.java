package com.olgun.guvcam;

import android.Manifest;
import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.*;
import android.hardware.Camera;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.net.wifi.WifiManager;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings("deprecation")
public class MainActivity extends Activity {
  private final int port=8787, discoveryPort=8788;
  private final AtomicReference<byte[]> frame=new AtomicReference<>();
  private volatile boolean running=true, cameraOn=false, front=false, monitorOn=false, audioEnabled=false;
  private volatile int monitorSession=0;
  private volatile boolean remoteAudioAllowed=false;
  private volatile float zoomRatio=1f;
  private ScaleGestureDetector zoomGesture;
  private final java.util.concurrent.ExecutorService zoomExecutor=java.util.concurrent.Executors.newSingleThreadExecutor();
  private final java.util.concurrent.atomic.AtomicBoolean zoomSending=new java.util.concurrent.atomic.AtomicBoolean(false);
  private volatile float pendingZoom=1f;
  private volatile String candidateIp="", candidateId="", peerIp="";
  private String myId, pairedId="";
  private long pairingUntil=0, closeTap=0;
  private volatile boolean switchingCamera=false;
  private final Object cameraLock=new Object();
  private volatile boolean remoteCamera=false;
  private Camera camera;
  private SurfaceTexture surface;
  private ImageView screen;
  private Bitmap lastImage;
  private TextView status;
  private Button close, switchCam, audioButton;
  private final Handler ui=new Handler(Looper.getMainLooper());
  private WifiManager.MulticastLock multicastLock;

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    android.content.SharedPreferences prefs=getSharedPreferences("pair",0);
    myId=prefs.getString("id","");
    if(myId.isEmpty()){myId=UUID.randomUUID().toString();prefs.edit().putString("id",myId).apply();}
    pairedId=prefs.getString("peer","");
    buildUi();
    if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[]{Manifest.permission.CAMERA},1);
    try {
      WifiManager wifi=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
      multicastLock=wifi.createMulticastLock("guvcam-discovery");multicastLock.setReferenceCounted(false);multicastLock.acquire();
    } catch(Exception ignored){}
    new Thread(this::discoveryLoop,"discovery").start();
    new Thread(this::serverLoop,"server").start();
  }
  @Override public void onConfigurationChanged(Configuration config){
    super.onConfigurationChanged(config);
    buildUi();
    // Keep camera and audio sockets alive during monitor orientation changes.
  }
  private void buildUi(){
    boolean landscape=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
    if(landscape){
      getWindow().getDecorView().setSystemUiVisibility(5894|1024|512);
      ImageView fullscreen=new ImageView(this);
      fullscreen.setBackgroundColor(0xff000000);
      fullscreen.setScaleType(ImageView.ScaleType.CENTER_CROP);
      if(lastImage!=null)fullscreen.setImageBitmap(orientForScreen(lastImage));
      screen=fullscreen;
      installZoomGesture();
      setContentView(fullscreen);
      return;
    }
    getWindow().getDecorView().setSystemUiVisibility(0);
    LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(-1);
    root.setPadding(6,0,6,0);
    TextView heading=new TextView(this);heading.setText("Güvenlik Kamerası");heading.setTextColor(-1);
    heading.setBackgroundColor(0xff000000);heading.setGravity(Gravity.CENTER);heading.setTypeface(null,1);
    heading.setTextSize(22);
    LinearLayout.LayoutParams h=new LinearLayout.LayoutParams(-1,110);h.topMargin=112;root.addView(heading,h);
    LinearLayout bar=new LinearLayout(this);bar.setOrientation(LinearLayout.VERTICAL);
    Button monitor=button("Monitör",0xffb3e5fc),cam=button("Kamera",0xffc8e6c9);
    switchCam=button("Arka/Ön",0xffffcc80);Button pair=button("Eşleştir",0xffffeb3b);
    close=button("Kapat",0xffffcdd2);
    audioButton=button(audioEnabled?"Ses: Açık":"Ses: Kapalı",audioEnabled?0xff777777:0xffdddddd);
    LinearLayout row1=new LinearLayout(this),row2=new LinearLayout(this);
    for(Button b:new Button[]{monitor,cam,switchCam})row1.addView(b,new LinearLayout.LayoutParams(0,80,1));
    for(Button b:new Button[]{pair,audioButton,close})row2.addView(b,new LinearLayout.LayoutParams(0,80,1));
    bar.addView(row1);bar.addView(row2);
    LinearLayout.LayoutParams controls=new LinearLayout.LayoutParams(-1,-2);controls.topMargin=16;root.addView(bar,controls);
    screen=new ImageView(this);screen.setBackgroundColor(0xffffffff);screen.setScaleType(ImageView.ScaleType.FIT_CENTER);
    int displayWidth=getResources().getDisplayMetrics().widthPixels;
    int horizontalPadding=(int)(12*getResources().getDisplayMetrics().density);
    LinearLayout.LayoutParams s=new LinearLayout.LayoutParams(-1,displayWidth-horizontalPadding);s.topMargin=16;screen.setPadding(2,2,2,2);screen.setBackground(createFrame());root.addView(screen,s);installZoomGesture();if(lastImage!=null)screen.setImageBitmap(orientForScreen(lastImage));
    status=new TextView(this);status.setText("Eşleşme bekleniyor");status.setTextColor(0xff777777);
    status.setGravity(Gravity.CENTER);root.addView(status);
    TextView signature=new TextView(this);signature.setText("Muhammed Yusuf OLGUN");
    signature.setGravity(Gravity.CENTER);signature.setTextColor(0xff888888);root.addView(signature);
    android.view.View bottomSpace=new android.view.View(this);root.addView(bottomSpace,new LinearLayout.LayoutParams(-1,64));
    setContentView(root);
    monitor.setOnClickListener(v->startMonitor());
    cam.setOnClickListener(v->startCamera());
    switchCam.setOnClickListener(v->{
      front=!front;
      if(monitorOn && !peerIp.isEmpty()){
        final String endpoint="http://"+peerIp+":"+port+"/switch?id="+myId;
        new Thread(()->{try{HttpURLConnection c=(HttpURLConnection)new URL(endpoint).openConnection();c.setConnectTimeout(5000);c.setReadTimeout(15000);int result=c.getResponseCode();c.disconnect();if(result!=200)showStatus("Kamera değiştirilemedi: "+result);}catch(Exception e){showStatus("Kamera değiştirme hatası");}}).start();
      }else if(cameraOn)startCamera();
      else showStatus(front?"Ön kamera seçildi":"Arka kamera seçildi");
    });
    pair.setOnClickListener(v->pair());
    audioButton.setOnClickListener(v->{
      if(!audioEnabled && !monitorOn && checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},2);return;
      }
      audioEnabled=!audioEnabled;
      audioButton.setText(audioEnabled?"Ses: Açık":"Ses: Kapalı");
      audioButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(audioEnabled?0xff777777:0xffdddddd));
      showStatus(audioEnabled?"Ses etkin":"Ses kapalı");
      if(monitorOn){if(audioEnabled)startAudioMonitor(monitorSession);else sendRemoteAudioState(false);}
    });
    close.setOnClickListener(v->{long now=System.currentTimeMillis();
      if(closeTap>0 && now-closeTap<=3000){closeTap=0;stopCamera();finish();}
      else {closeTap=now;close.setText("Onayla");ui.postDelayed(()->{if(System.currentTimeMillis()-closeTap>=3000)close.setText("Kapat");},3100);}
    });
  }
  private Bitmap orientForScreen(Bitmap source){
    if(source==null)return null;
    boolean landscape=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
    // Stream frames are upright in portrait; turn portrait frames when the monitor is sideways.
    if(landscape && source.getHeight()>source.getWidth()){
      Matrix matrix=new Matrix();matrix.postRotate(90);
      return Bitmap.createBitmap(source,0,0,source.getWidth(),source.getHeight(),matrix,true);
    }

    return source;
  }
  private android.graphics.drawable.Drawable createFrame(){
    android.graphics.drawable.GradientDrawable border=new android.graphics.drawable.GradientDrawable();
    border.setColor(0xffffffff);border.setStroke(3,0xff000000);return border;
  }
  private Button button(String label,int color){
    Button b=new Button(this);b.setText(label);b.setTextSize(10);b.setAllCaps(false);
    b.setPadding(0,0,0,0);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));return b;
  }
  private void showStatus(String text){ui.post(()->{if(status!=null)status.setText(text);});}
  private synchronized void stopCamera(){
    cameraOn=false;
    if(camera!=null){try{camera.setPreviewCallback(null);camera.stopPreview();camera.release();}catch(Exception ignored){}camera=null;}
    if(surface!=null){surface.release();surface=null;}
  }
  private synchronized void startCamera(){
    if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){showStatus("Kamera izni gerekli");return;}
    switchingCamera=true;
    frame.set(null);
    stopCamera();
    try{
      int selected=0;
      int count=Camera.getNumberOfCameras();
      for(int i=0;i<count;i++){Camera.CameraInfo info=new Camera.CameraInfo();Camera.getCameraInfo(i,info);
        if(info.facing==(front?Camera.CameraInfo.CAMERA_FACING_FRONT:Camera.CameraInfo.CAMERA_FACING_BACK)){selected=i;break;}}
      try{camera=Camera.open(selected);}catch(RuntimeException first){
        try{Thread.sleep(350);}catch(InterruptedException ignored){}
        camera=Camera.open(selected);
      }
      Camera.Parameters p=camera.getParameters();
      if(p.getSupportedFlashModes()!=null && p.getSupportedFlashModes().contains(Camera.Parameters.FLASH_MODE_OFF))p.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
      Camera.Size size=p.getSupportedPreviewSizes().get(0);
      for(Camera.Size z:p.getSupportedPreviewSizes())if(z.width<=1280 && z.width>size.width || size.width>1280 && z.width<=1280)size=z;
      p.setPreviewSize(size.width,size.height);camera.setParameters(p);
      final int w=size.width,ht=size.height;
      Camera.CameraInfo cameraInfo=new Camera.CameraInfo();
      Camera.getCameraInfo(selected,cameraInfo);
      int rotation=getWindowManager().getDefaultDisplay().getRotation();
      int degrees=rotation==Surface.ROTATION_90?90:rotation==Surface.ROTATION_180?180:rotation==Surface.ROTATION_270?270:0;
      final int jpegRotation=(cameraInfo.facing==Camera.CameraInfo.CAMERA_FACING_FRONT)
          ?(cameraInfo.orientation+degrees)%360
          :(cameraInfo.orientation-degrees+360)%360;
      surface=new SurfaceTexture(11);camera.setPreviewTexture(surface);
      camera.setPreviewCallback((data,c)->{
        if(!cameraOn)return;
        try{ByteArrayOutputStream out=new ByteArrayOutputStream();
          new YuvImage(data,ImageFormat.NV21,w,ht,null).compressToJpeg(new Rect(0,0,w,ht),65,out);
          byte[] jpg=out.toByteArray();
          if(jpegRotation!=0){
            Bitmap original=BitmapFactory.decodeByteArray(jpg,0,jpg.length);
            if(original!=null){
              Matrix matrix=new Matrix();matrix.postRotate(jpegRotation);
              Bitmap upright=Bitmap.createBitmap(original,0,0,original.getWidth(),original.getHeight(),matrix,true);
              ByteArrayOutputStream rotated=new ByteArrayOutputStream();
              upright.compress(Bitmap.CompressFormat.JPEG,65,rotated);
              jpg=rotated.toByteArray();
              if(upright!=original)upright.recycle();
              original.recycle();
            }
          }
          frame.set(jpg);
          if(!monitorOn && !switchingCamera){final byte[] displayJpeg=jpg;ui.post(()->{if(cameraOn && !monitorOn && screen!=null){lastImage=BitmapFactory.decodeByteArray(displayJpeg,0,displayJpeg.length);screen.setImageBitmap(orientForScreen(lastImage));}});}
        }catch(Exception ignored){}
      });
      cameraOn=true;camera.startPreview();applyCameraZoom(zoomRatio);switchingCamera=false;showStatus(front?"Ön kamera açık":"Arka kamera açık");
    }catch(Exception e){stopCamera();switchingCamera=false;showStatus("Kamera açılamadı: "+e.getMessage());}
  }
  private void pair(){
    pairingUntil=System.currentTimeMillis()+60000;
    if(candidateIp.isEmpty()){showStatus("Aynı Wi-Fi ağında diğer telefonu açın ve iki cihazda Eşleştir'e basın");return;}
    showStatus("Eşleşme isteği gönderiliyor");
    sendUdp("PAIR|"+myId,candidateIp);
  }
  private void acceptPair(String id,String ip){
    pairedId=id;peerIp=ip;
    getSharedPreferences("pair",0).edit().putString("peer",id).apply();
    showStatus("Eşleşti: "+ip);
  }
  private void sendUdp(String message,String ip){
    new Thread(()->{try(DatagramSocket sock=new DatagramSocket()){
      sock.setBroadcast(true);byte[] bytes=message.getBytes("UTF-8");
      sock.send(new DatagramPacket(bytes,bytes.length,InetAddress.getByName(ip),discoveryPort));
    }catch(Exception ignored){} }).start();
  }
  private void discoveryLoop(){
    new Thread(()->{while(running){
      try{sendUdp("HELLO|"+myId,"255.255.255.255");Thread.sleep(2500);}
      catch(Exception ignored){}
    }}).start();
    try(DatagramSocket sock=new DatagramSocket(null)){
      sock.setReuseAddress(true);sock.bind(new InetSocketAddress(discoveryPort));sock.setSoTimeout(2000);
      byte[] buf=new byte[1024];
      while(running){
        try{DatagramPacket packet=new DatagramPacket(buf,buf.length);sock.receive(packet);
          String msg=new String(packet.getData(),0,packet.getLength(),"UTF-8");
          String ip=packet.getAddress().getHostAddress();String[] parts=msg.split("\\|");
          if(parts.length<2 || parts[1].equals(myId))continue;
          if(parts[0].equals("HELLO")){candidateIp=ip;candidateId=parts[1];
            if(pairedId.equals(parts[1])){peerIp=ip;showStatus("Eşleşmiş telefon bulundu");}}
          else if(parts[0].equals("PAIR") && System.currentTimeMillis()<pairingUntil){
            acceptPair(parts[1],ip);sendUdp("ACK|"+myId,ip);
          }else if(parts[0].equals("ACK") && System.currentTimeMillis()<pairingUntil){
            acceptPair(parts[1],ip);
          }
        }catch(SocketTimeoutException ignored){}catch(Exception ignored){}
      }
    }catch(Exception e){showStatus("Ağ keşfi hatası");}
  }
  private void startMonitor(){
    if(pairedId.isEmpty()||peerIp.isEmpty()){ui.post(()->{lastImage=null;screen.setImageDrawable(null);});showStatus("Önce iki telefonu eşleştirin");return;}
    stopCamera();monitorOn=true;zoomRatio=1f;monitorSession++;final int session=monitorSession;ui.post(()->screen.setImageDrawable(null));showStatus("Görüntü bağlanıyor");
    final String ip=peerIp;
    if(audioEnabled)startAudioMonitor(session);
    new Thread(()->{
      try{
        URL command=new URL("http://"+ip+":"+port+"/start?id="+URLEncoder.encode(myId,"UTF-8"));
        HttpURLConnection conn=(HttpURLConnection)command.openConnection();conn.setConnectTimeout(3000);
        if(conn.getResponseCode()!=200)throw new IOException("Erişim reddedildi");conn.disconnect();
        HttpURLConnection stream=(HttpURLConnection)new URL("http://"+ip+":"+port+"/stream?id="+URLEncoder.encode(myId,"UTF-8")).openConnection();
        stream.setConnectTimeout(4000);stream.setReadTimeout(20000);
        InputStream in=stream.getInputStream();ByteArrayOutputStream jpg=new ByteArrayOutputStream();
        int prev=-1,b;boolean collecting=false;
        while(monitorOn && session==monitorSession && (b=in.read())!=-1){
          if(!collecting){if(prev==255&&b==216){collecting=true;jpg.reset();jpg.write(255);jpg.write(216);}}
          else {jpg.write(b);if(prev==255&&b==217){
            byte[] bytes=jpg.toByteArray();Bitmap bmp=BitmapFactory.decodeByteArray(bytes,0,bytes.length);
            if(bmp!=null)ui.post(()->{if(monitorOn && session==monitorSession){lastImage=bmp;screen.setImageBitmap(orientForScreen(bmp));}});
            collecting=false;
          } if(jpg.size()>3000000){collecting=false;jpg.reset();}}
          prev=b;
        }
        in.close();stream.disconnect();
      }catch(Exception e){if(monitorOn && session==monitorSession)showStatus("Bağlantı kesildi: "+e.getMessage());}
    },"monitor").start();
  }
  private void serverLoop(){
    try(ServerSocket server=new ServerSocket()){
      server.setReuseAddress(true);
      server.bind(new InetSocketAddress(port));
      while(running){Socket client=server.accept();new Thread(()->serve(client)).start();}
    }catch(Exception e){showStatus("Port "+port+" açılamadı: "+e.getMessage());}
  }
  private void serve(Socket client){
    try(Socket s=client){
      s.setSoTimeout(5000);
      BufferedReader reader=new BufferedReader(new InputStreamReader(s.getInputStream()));
      String request=reader.readLine();if(request==null)return;
      String path=request.split(" ")[1];
      String id="";int idx=path.indexOf("id=");if(idx>=0)id=URLDecoder.decode(path.substring(idx+3).split("&")[0],"UTF-8");
      OutputStream out=s.getOutputStream();
      if(pairedId.isEmpty()||!pairedId.equals(id)){
        out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".getBytes());return;
      }
      if(path.startsWith("/start")){
        ui.post(()->{front=false;remoteCamera=true;startCamera();});
        out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".getBytes());return;
      }
      if(path.startsWith("/switch")){
        ui.post(()->{
          if(switchingCamera)return;
          front=!front;
          startCamera();
        });
        out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".getBytes());return;
      }
      if(path.startsWith("/audio/start")){
        remoteAudioAllowed=checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;
        if(!remoteAudioAllowed)ui.post(()->requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},2));
        out.write((remoteAudioAllowed?"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n":"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n").getBytes());return;
      }
      if(path.startsWith("/audio/stop")){
        remoteAudioAllowed=false;
        out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes());return;
      }
      if(path.startsWith("/zoom")){
        String z=path.contains("level=")?path.substring(path.indexOf("level=")+6).split("&")[0]:"1";
        try{float requested=Float.parseFloat(z);ui.post(()->applyCameraZoom(requested));}catch(Exception ignored){}
        out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes());return;
      }
      if(path.startsWith("/audio")){s.setSoTimeout(0);streamAudio(out);return;}
      if(path.startsWith("/stream")){
        s.setSoTimeout(0);
        out.write("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\nCache-Control: no-store\r\n\r\n".getBytes());
        while(running&&!s.isClosed()){
          byte[] bytes=frame.get();
          if(bytes!=null && cameraOn){
            out.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: "+bytes.length+"\r\n\r\n").getBytes());
            out.write(bytes);out.write("\r\n".getBytes());out.flush();
          }
          Thread.sleep(130);
        }
      }
    }catch(Exception ignored){}
  }
  private void streamAudio(OutputStream out) throws IOException {
    if(!remoteAudioAllowed || checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
      out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".getBytes());return;
    }
    final int rate=16000;
    int min=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
    if(min<=0)return;
    AudioRecord recorder=null;
    try{
      recorder=new AudioRecord(MediaRecorder.AudioSource.MIC,rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min,4096));
      if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)return;
      out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nCache-Control: no-store\r\n\r\n".getBytes());
      recorder.startRecording();
      byte[] buf=new byte[2048];
      while(running && remoteAudioAllowed){
        int n=recorder.read(buf,0,buf.length);
        if(n>0){out.write(buf,0,n);out.flush();}
        else if(n<0)break;
      }
    }catch(Exception e){showStatus("Mikrofon hatası: "+e.getMessage());}
    finally{if(recorder!=null){try{recorder.stop();}catch(Exception ignored){}recorder.release();}}
  }
  private void sendRemoteAudioState(boolean enabled){
    if(peerIp.isEmpty())return;
    final String endpoint="http://"+peerIp+":"+port+(enabled?"/audio/start":"/audio/stop")+"?id="+myId;
    new Thread(()->{try{
      HttpURLConnection c=(HttpURLConnection)new URL(endpoint).openConnection();
      c.setConnectTimeout(3000);c.setReadTimeout(3000);
      int code=c.getResponseCode();c.disconnect();
      if(enabled && code!=200)showStatus("Kamera telefonunda mikrofon izni gerekli");
    }catch(Exception e){showStatus("Ses komutu gönderilemedi");}},"audio-control").start();
  }
  private void installZoomGesture(){
    zoomGesture=new ScaleGestureDetector(this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
      @Override public boolean onScaleBegin(ScaleGestureDetector detector){return monitorOn;}
      @Override public boolean onScale(ScaleGestureDetector detector){
        if(!monitorOn)return false;
        zoomRatio=Math.max(1f,Math.min(5f,zoomRatio*detector.getScaleFactor()));
        sendZoom(zoomRatio);
        return true;
      }
      @Override public void onScaleEnd(ScaleGestureDetector detector){
        if(monitorOn)sendZoom(zoomRatio);
      }
    });
    screen.setOnTouchListener((v,event)->{
      if(!monitorOn)return false;
      zoomGesture.onTouchEvent(event);
      return true; // Consume ACTION_DOWN so Android delivers subsequent pinch events.
    });
  }
  private void sendZoom(float ratio){
    pendingZoom=ratio;
    if(peerIp.isEmpty() || !zoomSending.compareAndSet(false,true))return;
    zoomExecutor.execute(()->{
      try{
        while(running && monitorOn){
          float target=pendingZoom;
          try{
            HttpURLConnection c=(HttpURLConnection)new URL("http://"+peerIp+":"+port+
                "/zoom?id="+URLEncoder.encode(myId,"UTF-8")+"&level="+target).openConnection();
            c.setConnectTimeout(2500);c.setReadTimeout(2500);
            int response=c.getResponseCode();c.disconnect();
            if(response!=200)showStatus("Yakınlaştırma komutu reddedildi: "+response);
          }catch(Exception e){showStatus("Yakınlaştırma bağlantısı: "+e.getMessage());}
          lastZoomApplied=target;
          if(Math.abs(target-pendingZoom)<0.01f)break;
        }
      }finally{
        zoomSending.set(false);
        if(running && monitorOn && Math.abs(pendingZoom-lastZoomApplied)>0.01f){
          // A newer gesture value arrived while the worker was completing.
          sendZoom(pendingZoom);
        }
      }
    });
  }
  private volatile float lastZoomApplied=1f;
  private synchronized void applyCameraZoom(float ratio){
    if(camera==null)return;
    try{
      Camera.Parameters p=camera.getParameters();
      if(!p.isZoomSupported())return;
      java.util.List<Integer> ratios=p.getZoomRatios();
      int target=0;
      if(ratios!=null && !ratios.isEmpty()){
        int wanted=Math.round(Math.max(1f,Math.min(5f,ratio))*100);
        for(int i=0;i<ratios.size();i++){
          if(Math.abs(ratios.get(i)-wanted)<Math.abs(ratios.get(target)-wanted))target=i;
        }
      }else target=Math.round((ratio-1f)/4f*p.getMaxZoom());
      p.setZoom(Math.max(0,Math.min(p.getMaxZoom(),target)));
      camera.setParameters(p);
    }catch(Exception e){showStatus("Kamera yakınlaştırma hatası: "+e.getMessage());}
  }
  private void startAudioMonitor(int session){
    new Thread(()->{
      AudioTrack player=null;HttpURLConnection conn=null;
      try{
        if(peerIp.isEmpty())return;
        HttpURLConnection enable=(HttpURLConnection)new URL("http://"+peerIp+":"+port+"/audio/start?id="+URLEncoder.encode(myId,"UTF-8")).openConnection();
        enable.setConnectTimeout(4000);enable.setReadTimeout(4000);
        int enableCode=enable.getResponseCode();enable.disconnect();
        if(enableCode!=200){showStatus("Kamera telefonunda mikrofon izni gerekli");return;}
        conn=(HttpURLConnection)new URL("http://"+peerIp+":"+port+"/audio?id="+URLEncoder.encode(myId,"UTF-8")).openConnection();
        conn.setConnectTimeout(4000);conn.setReadTimeout(0);
        if(conn.getResponseCode()!=200){showStatus("Kamera telefonunda mikrofon izni gerekli");return;}
        int min=AudioTrack.getMinBufferSize(16000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
        player=new AudioTrack(AudioManager.STREAM_MUSIC,16000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min,8192),AudioTrack.MODE_STREAM);
        player.play();
        try(InputStream in=conn.getInputStream()){
          byte[] buf=new byte[2048];int n;
          while(running && monitorOn && audioEnabled && session==monitorSession && (n=in.read(buf))!=-1)
            if(n>0)player.write(buf,0,n);
        }
      }catch(Exception e){if(monitorOn && audioEnabled && session==monitorSession)showStatus("Ses bağlantısı kesildi: "+e.getMessage());}
      finally{if(player!=null){try{player.stop();}catch(Exception ignored){}player.release();}if(conn!=null)conn.disconnect();}
    },"audio-monitor").start();
  }
  @Override protected void onDestroy(){running=false;monitorOn=false;monitorSession++;stopCamera();
    zoomExecutor.shutdownNow();
    if(multicastLock!=null&&multicastLock.isHeld())multicastLock.release();super.onDestroy();}
}
