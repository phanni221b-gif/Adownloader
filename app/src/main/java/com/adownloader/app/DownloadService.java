package com.adownloader.app;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import com.adownloader.app.download.DownloadEngine;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns foreground lifecycle and publication; engine never depends on an Activity. */
public final class DownloadService extends Service {
    public static final String ACTION_DOWNLOAD="com.adownloader.app.DOWNLOAD";
    public static final String EXTRA_URL="url",EXTRA_TYPE="type",EXTRA_HEADERS="headers",EXTRA_FILENAME="filename";
    private static final String CHANNEL="adownloader_downloads";
    private static final int FOREGROUND_ID=3999;
    private static final AtomicInteger NEXT_ID=new AtomicInteger((int)(System.currentTimeMillis()/1000 % 1_000_000_000));
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final AtomicInteger pending=new AtomicInteger();
    private final Handler main=new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onCreate(){
        super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"Adownloader 다운로드",NotificationManager.IMPORTANCE_LOW));
        wakeLock=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Adownloader:downloads");
        wakeLock.acquire(6L*60*60*1000);
    }
    private Notification notification(String title,String text,boolean ongoing,int percent,PendingIntent action){
        Notification.Builder b=new Notification.Builder(this,CHANNEL).setSmallIcon(ongoing?android.R.drawable.stat_sys_download:android.R.drawable.stat_sys_download_done)
            .setContentTitle(title).setContentText(text).setOnlyAlertOnce(true).setOngoing(ongoing).setAutoCancel(!ongoing);
        if(ongoing)b.setProgress(100,percent,percent==0);if(action!=null)b.setContentIntent(action);return b.build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null || !ACTION_DOWNLOAD.equals(intent.getAction())){if(pending.get()==0)stopSelf();return START_NOT_STICKY;}
        String url=intent.getStringExtra(EXTRA_URL),type=intent.getStringExtra(EXTRA_TYPE),json=intent.getStringExtra(EXTRA_HEADERS);
        if(url==null || type==null){if(pending.get()==0)stopSelf();return START_NOT_STICKY;}
        int id=NEXT_ID.getAndIncrement();pending.incrementAndGet();
        startForeground(FOREGROUND_ID,notification("Adownloader","다운로드 준비 중",true,0,null));
        com.adownloader.app.ui.DownloadHistory.begin(this,id,android.net.Uri.parse(url).getHost(),type);
        executor.execute(()->{
            DownloadEngine engine=new DownloadEngine(getCacheDir(),(text,percent)->{
                getSystemService(NotificationManager.class).notify(FOREGROUND_ID,notification("Adownloader",text,true,percent,null));
                com.adownloader.app.ui.DownloadHistory.progress(this,id,text,percent);
            });
            try{
                Map<String,String> headers=new LinkedHashMap<>();
                if(json!=null){JSONObject object=new JSONObject(json);Iterator<String> keys=object.keys();while(keys.hasNext()){String key=keys.next();headers.put(key,object.getString(key));}}
                save(id,"다운로드 중",null);
                DownloadEngine.Result result=engine.download(url,MediaCandidate.MediaType.valueOf(type),headers);
                Uri uri=publish(result,id);save(id,"완료",uri.toString());
                Intent open=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,result.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                PendingIntent action=PendingIntent.getActivity(this,id,Intent.createChooser(open,"미디어 열기"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
                getSystemService(NotificationManager.class).notify(id,notification("다운로드 완료","탭해서 미디어 열기",false,100,action));
            }catch(Exception error){
                // Do not put signed URLs, cookies or server messages in notifications or logs.
                save(id,"실패 · "+safeReason(error),null);
                getSystemService(NotificationManager.class).notify(id,notification("다운로드 실패",safeReason(error),false,0,null));
            }finally{
                engine.cleanup();
                main.post(()->{if(pending.decrementAndGet()==0){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}});
            }
        });
        return START_NOT_STICKY;
    }
    private String safeReason(Exception error){ return com.adownloader.app.ui.DownloadHistory.userError(error.getMessage()); }
    private Uri publish(DownloadEngine.Result result,int id)throws Exception{
        boolean audio=result.mime.startsWith("audio/");
        ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,"Adownloader_"+System.currentTimeMillis()+"_"+id+result.extension);
        values.put(MediaStore.MediaColumns.MIME_TYPE,result.mime);values.put(MediaStore.MediaColumns.RELATIVE_PATH,(audio?Environment.DIRECTORY_MUSIC:Environment.DIRECTORY_MOVIES)+"/Adownloader");values.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri collection=audio?MediaStore.Audio.Media.EXTERNAL_CONTENT_URI:MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        Uri uri=getContentResolver().insert(collection,values);if(uri==null)throw new IOException("저장 위치 생성 실패");
        try{
            try(InputStream input=new FileInputStream(result.file);OutputStream output=getContentResolver().openOutputStream(uri,"w")){
                if(output==null)throw new IOException("저장 파일 열기 실패");byte[] buffer=new byte[128*1024];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);
            }
            values.clear();values.put(MediaStore.MediaColumns.IS_PENDING,0);getContentResolver().update(uri,values,null,null);return uri;
        }catch(Exception error){getContentResolver().delete(uri,null,null);throw error;}
    }
    private void save(int id,String state,String uri){ com.adownloader.app.ui.DownloadHistory.state(this,id,state,uri); }
    @Override public void onTimeout(int startId,int fgsType){executor.shutdownNow();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    @Override public void onDestroy(){executor.shutdown();if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();super.onDestroy();}
}
