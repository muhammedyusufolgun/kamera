package com.olgun.guvcam;

import android.Manifest;
import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.*;
import android.hardware.Camera;
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
  private volatile boolean running=true, cameraOn=false, front=false, monitorOn=false;
  private volatile String candidateIp="", candidateId="", peerIp="";
  private String myId, pairedId="";
  private long pairingUntil=0, closeTap=0;
  private Camera camera;
  private SurfaceTexture surface;
  private ImageView screen;
  private Bitmap lastImage;
  private TextView status;
  private Button close, switchCam;
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
  }
  private void buildUi(){
    boolean landscape=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
    if(landscape){
      getWindow().getDecorView().setSystemUiVisibility(5894|1024|512);
      ImageView fullscreen=new ImageView(this);
      fullscreen.setBackgroundColor(0xff000000);
      fullscreen.setScaleType(ImageView.ScaleType.FIT_XY);
      if(lastImage!=null)fullscreen.setImageBitmap(lastImage);
      screen=fullscreen;
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
    LinearLayout row1=new LinearLayout(this),row2=new LinearLayout(this);
    for(Button b:new Button[]{monitor,cam,switchCam})row1.addView(b,new LinearLayout.LayoutParams(0,80,1));
    for(Button b:new Button[]{pair,close})row2.addView(b,new LinearLayout.LayoutParams(0,44,1));
    bar.addView(row1);bar.addView(row2);
    LinearLayout.LayoutParams controls=new LinearLayout.LayoutParams(-1,-2);controls.topMargin=16;root.addView(bar,controls);
    screen=new ImageView(this);screen.setBackgroundColor(0xffffffff);screen.setScaleType(ImageView.ScaleType.FIT_CENTER);
    int displayWidth=getResources().getDisplayMetrics().widthPixels;
    int horizontalPadding=(int)(12*getResources().getDisplayMetrics().density);
    LinearLayout.LayoutParams s=new LinearLayout.LayoutParams(-1,displayWidth-horizontalPadding);s.topMargin=16;screen.setPadding(2,2,2,2);screen.setBackground(createFrame());root.addView(screen,s);if(lastImage!=null)screen.setImageBitmap(lastImage);
    status=new TextView(this);status.setText("Eşleşme bekleniyor");status.setTextColor(0xff777777);
    status.setGravity(Gravity.CENTER);root.addView(status);
    TextView signature=new TextView(this);signature.setText("Muhammed Yusuf OLGUN");
    signature.setGravity(Gravity.CENTER);signature.setTextColor(0xff888888);root.addView(signature);
    android.view.View bottomSpace=new android.view.View(this);root.addView(bottomSpace,new LinearLayout.LayoutParams(-1,64));
    setContentView(root);
    monitor.setOnClickListener(v->startMonitor());
    cam.setOnClickListener(v->startCamera());
    switchCam.setOnClickListener(v->{front=!front;if(cameraOn)startCamera();else showStatus(front?"Ön kamera seçildi":"Arka kamera seçildi");});
    pair.setOnClickListener(v->pair());
    close.setOnClickListener(v->{long now=System.currentTimeMillis();
      if(closeTap>0 && now-closeTap<=3000){closeTap=0;stopCamera();finish();}
      else {closeTap=now;close.setText("Onayla");ui.postDelayed(()->{if(System.currentTimeMillis()-closeTap>=3000)close.setText("Kapat");},3100);}
    });
  }
  private android.graphics.drawable.Drawable createFrame(){
    android.graphics.drawable.GradientDrawable border=new android.graphics.drawable.GradientDrawable();
    border.setColor(0xffffffff);border.setStroke(3,0xff000000);return border;
  }
  private Button button(String label,int color){
    Button b=new Button(this);b.setText(label);b.setTextSize(10);b.setAllCaps(false);
    b.setPadding(0,0,0,0);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));return b;
  }
  private void showStatus(String text){ui.post(()->status.setText(text));}
  private synchronized void stopCamera(){
    cameraOn=false;
    if(camera!=null){try{camera.setPreviewCallback(null);camera.stopPreview();camera.release();}catch(Exception ignored){}camera=null;}
    if(surface!=null){surface.release();surface=null;}
  }
  private synchronized void startCamera(){
    if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){showStatus("Kamera izni gerekli");return;}
    stopCamera();monitorOn=false;
    try{
      int selected=0;
      int count=Camera.getNumberOfCameras();
      for(int i=0;i<count;i++){Camera.CameraInfo info=new Camera.CameraInfo();Camera.getCameraInfo(i,info);
        if(info.facing==(front?Camera.CameraInfo.CAMERA_FACING_FRONT:Camera.CameraInfo.CAMERA_FACING_BACK)){selected=i;break;}}
      camera=Camera.open(selected);
      Camera.Parameters p=camera.getParameters();
      p.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
      Camera.Size size=p.getSupportedPreviewSizes().get(0);
      for(Camera.Size z:p.getSupportedPreviewSizes())if(z.width<=1280 && z.width>size.width || size.width>1280 && z.width<=1280)size=z;
      p.setPreviewSize(size.width,size.height);camera.setParameters(p);
      final int w=size.width,ht=size.height;
      surface=new SurfaceTexture(11);camera.setPreviewTexture(surface);
      camera.setPreviewCallback((data,c)->{
        if(!cameraOn)return;
        try{ByteArrayOutputStream out=new ByteArrayOutputStream();
          new YuvImage(data,ImageFormat.NV21,w,ht,null).compressToJpeg(new Rect(0,0,w,ht),65,out);
          byte[] jpg=out.toByteArray();frame.set(jpg);
          if(!monitorOn)ui.post(()->{if(cameraOn){lastImage=BitmapFactory.decodeByteArray(jpg,0,jpg.length);screen.setImageBitmap(lastImage);}});
        }catch(Exception ignored){}
      });
      cameraOn=true;camera.startPreview();showStatus(front?"Ön kamera açık":"Arka kamera açık");
    }catch(Exception e){stopCamera();showStatus("Kamera açılamadı: "+e.getMessage());}
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
    stopCamera();monitorOn=true;ui.post(()->screen.setImageDrawable(null));showStatus("Görüntü bağlanıyor");
    final String ip=peerIp;
    new Thread(()->{
      try{
        URL command=new URL("http://"+ip+":"+port+"/start?id="+URLEncoder.encode(myId,"UTF-8"));
        HttpURLConnection conn=(HttpURLConnection)command.openConnection();conn.setConnectTimeout(3000);
        if(conn.getResponseCode()!=200)throw new IOException("Erişim reddedildi");conn.disconnect();
        HttpURLConnection stream=(HttpURLConnection)new URL("http://"+ip+":"+port+"/stream?id="+URLEncoder.encode(myId,"UTF-8")).openConnection();
        stream.setConnectTimeout(4000);stream.setReadTimeout(6000);
        InputStream in=stream.getInputStream();ByteArrayOutputStream jpg=new ByteArrayOutputStream();
        int prev=-1,b;boolean collecting=false;
        while(monitorOn && (b=in.read())!=-1){
          if(!collecting){if(prev==255&&b==216){collecting=true;jpg.reset();jpg.write(255);jpg.write(216);}}
          else {jpg.write(b);if(prev==255&&b==217){
            byte[] bytes=jpg.toByteArray();Bitmap bmp=BitmapFactory.decodeByteArray(bytes,0,bytes.length);
            if(bmp!=null)ui.post(()->{if(monitorOn){lastImage=bmp;screen.setImageBitmap(bmp);}});
            collecting=false;
          } if(jpg.size()>3000000){collecting=false;jpg.reset();}}
          prev=b;
        }
        in.close();stream.disconnect();
      }catch(Exception e){showStatus("Bağlantı kesildi: "+e.getMessage());}
    },"monitor").start();
  }
  private void serverLoop(){
    try(ServerSocket server=new ServerSocket(port)){
      while(running){Socket client=server.accept();new Thread(()->serve(client)).start();}
    }catch(Exception e){showStatus("Sunucu açılamadı");}
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
        ui.post(()->{front=false;startCamera();});
        out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".getBytes());return;
      }
      if(path.startsWith("/stream")){
        out.write("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\nCache-Control: no-store\r\n\r\n".getBytes());
        while(running&&!s.isClosed()){
          byte[] bytes=frame.get();
          if(bytes!=null){
            out.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: "+bytes.length+"\r\n\r\n").getBytes());
            out.write(bytes);out.write("\r\n".getBytes());out.flush();
          }
          Thread.sleep(130);
        }
      }
    }catch(Exception ignored){}
  }
  @Override protected void onDestroy(){running=false;monitorOn=false;stopCamera();
    if(multicastLock!=null&&multicastLock.isHeld())multicastLock.release();super.onDestroy();}
}
