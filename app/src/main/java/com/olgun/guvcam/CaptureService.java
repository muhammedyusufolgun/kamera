package com.olgun.guvcam;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;

public class CaptureService extends Service {
  public static volatile boolean active=false;
  private static final String CHANNEL="guvcam_capture";
  @Override public void onCreate(){
    super.onCreate();
    NotificationManager manager=getSystemService(NotificationManager.class);
    NotificationChannel channel=new NotificationChannel(CHANNEL,"Guv-Cam kamera ve mikrofon",NotificationManager.IMPORTANCE_LOW);
    manager.createNotificationChannel(channel);
  }
  @Override public int onStartCommand(Intent intent,int flags,int startId){
    Intent launch=new Intent(this,MainActivity.class);
    PendingIntent pending=PendingIntent.getActivity(this,0,launch,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    Notification notification=new Notification.Builder(this,CHANNEL)
      .setContentTitle("Guv-Cam etkin")
      .setContentText("Kamera ve mikrofon aktarımı arka planda devam edebilir")
      .setSmallIcon(android.R.drawable.presence_video_online)
      .setContentIntent(pending).setOngoing(true).build();
    try{
      startForeground(7001,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA|ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
      active=true;
    }catch(Exception e){
      stopSelf();
    }
    return START_STICKY;
  }
  @Override public void onDestroy(){active=false;super.onDestroy();}
  @Override public IBinder onBind(Intent intent){return null;}
}
