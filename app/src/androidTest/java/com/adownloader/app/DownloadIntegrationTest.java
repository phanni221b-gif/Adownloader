package com.adownloader.app;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.view.*;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.adownloader.app.download.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Exercises actual Android extractors/muxer and the bundled Gecko native bridge. */
@RunWith(AndroidJUnit4.class)
public class DownloadIntegrationTest {
    private ServerSocket server;
    private ExecutorService workers;
    private String base;
    @Before public void start()throws Exception {
        server=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));base="http://127.0.0.1:"+server.getLocalPort()+"/";
        workers=Executors.newCachedThreadPool();workers.execute(()->{while(!server.isClosed())try{Socket client=server.accept();workers.execute(()->serve(client));}catch(IOException ignored){}});
    }
    @After public void stop()throws Exception {server.close();workers.shutdownNow();}
    private byte[] fixture(String name)throws IOException {
        try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("fixtures/"+name);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);return out.toByteArray();
        }
    }
    private void serve(Socket socket){
        try(Socket client=socket){
            BufferedReader reader=new BufferedReader(new InputStreamReader(client.getInputStream()));String first=reader.readLine();if(first==null)return;
            String path=first.split(" ")[1].substring(1).split("\\?")[0];String line,range=null;
            while((line=reader.readLine())!=null&&!line.isEmpty())if(line.toLowerCase(Locale.ROOT).startsWith("range:"))range=line.substring(6).trim();
            byte[] body;String mime;
            if(path.equals("index.html")){body=("<!doctype html><title>Adownloader test</title><video preload='auto' controls src='"+base+"direct.mp4'></video>").getBytes(java.nio.charset.StandardCharsets.UTF_8);mime="text/html";}
            else{body=fixture(path);mime=path.endsWith("mp4")?"video/mp4":path.endsWith("m3u8")?"application/vnd.apple.mpegurl":path.endsWith("mpd")?"application/dash+xml":"application/octet-stream";}
            if(path.endsWith(".ts"))Thread.sleep(100);
            int start=0,end=body.length-1;
            if(range!=null){String[] bounds=range.replace("bytes=","").split("-");start=Integer.parseInt(bounds[0]);if(bounds.length>1)end=Math.min(end,Integer.parseInt(bounds[1]));}
            String header="HTTP/1.1 "+(range==null?"200 OK":"206 Partial Content")+"\r\nContent-Type: "+mime+"\r\nContent-Length: "+(end-start+1)+"\r\nAccept-Ranges: bytes\r\n"+(range==null?"":"Content-Range: bytes "+start+"-"+end+"/"+body.length+"\r\n")+"Connection: close\r\n\r\n";
            OutputStream output=client.getOutputStream();output.write(header.getBytes(java.nio.charset.StandardCharsets.US_ASCII));output.write(body,start,end-start+1);output.flush();
        }catch(Exception ignored){}
    }
    private DownloadEngine engine(){return new DownloadEngine(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),(text,percent)->{});}
    @Test public void directMp4IsBitExact()throws Exception {
        DownloadEngine engine=engine();try{
            DownloadEngine.Result result=engine.download(base+"direct.mp4",MediaCandidate.MediaType.VIDEO,Collections.emptyMap());
            byte[] saved;try(InputStream in=new FileInputStream(result.file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);saved=out.toByteArray();}
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(fixture("direct.mp4")),MessageDigest.getInstance("SHA-256").digest(saved));
        }finally{engine.cleanup();}
    }
    @Test public void hlsTsRemuxHasBothTracksAndSeeks()throws Exception {
        DownloadEngine engine=engine();try{DownloadEngine.Result result=engine.download(base+"playlist.m3u8",MediaCandidate.MediaType.HLS,Collections.emptyMap());PostProcessor.inspect(result.file,3,true,true);}finally{engine.cleanup();}
    }
    @Test public void dashSeparateTracksMuxAndSeek()throws Exception {
        DownloadEngine engine=engine();try{DownloadEngine.Result result=engine.download(base+"manifest.mpd",MediaCandidate.MediaType.DASH,Collections.emptyMap());PostProcessor.inspect(result.file,3,true,true);}finally{engine.cleanup();}
    }
    @Test public void hlsExternalAudioIsIncluded()throws Exception {
        DownloadEngine engine=engine();try{DownloadEngine.Result result=engine.download(base+"master.m3u8",MediaCandidate.MediaType.HLS,Collections.emptyMap());PostProcessor.inspect(result.file,3,true,true);}finally{engine.cleanup();}
    }
    @Test public void queuedForegroundDownloadsFinishAfterBrowserCloses()throws Exception {
        android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();Context context=instrumentation.getTargetContext();
        instrumentation.getUiAutomation().executeShellCommand("pm grant com.adownloader.app android.permission.POST_NOTIFICATIONS").close();
        android.content.SharedPreferences prefs=context.getSharedPreferences("downloads",0);prefs.edit().clear().commit();
        Activity activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).setData(Uri.parse(base+"index.html")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
        for(int i=0;i<2;i++)context.startForegroundService(new Intent(context,DownloadService.class).setAction(DownloadService.ACTION_DOWNLOAD).putExtra(DownloadService.EXTRA_URL,base+"playlist.m3u8").putExtra(DownloadService.EXTRA_TYPE,"HLS"));
        instrumentation.runOnMainSync(activity::finish);
        long deadline=System.currentTimeMillis()+60_000;int complete=0;
        while(System.currentTimeMillis()<deadline){org.json.JSONObject items=new org.json.JSONObject(prefs.getString("items","{}"));complete=0;Iterator<String> keys=items.keys();while(keys.hasNext())if("완료".equals(items.getJSONObject(keys.next()).optString("state")))complete++;if(complete==2)break;Thread.sleep(250);}
        assertEquals("Both queued foreground jobs must finish after Activity closure",2,complete);
        org.json.JSONObject items=new org.json.JSONObject(prefs.getString("items","{}"));Iterator<String> keys=items.keys();while(keys.hasNext()){String uri=items.getJSONObject(keys.next()).optString("uri");if(!uri.isEmpty())context.getContentResolver().delete(Uri.parse(uri),null,null);}
    }
    @Test public void geckoLaunchAndNativeMediaBridge()throws Exception {
        android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();Context context=instrumentation.getTargetContext();
        instrumentation.getUiAutomation().executeShellCommand("pm grant com.adownloader.app android.permission.POST_NOTIFICATIONS").close();
        Activity activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).setData(Uri.parse(base+"index.html")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            long deadline=System.currentTimeMillis()+60_000;boolean[] found={false};
            while(System.currentTimeMillis()<deadline&&!found[0]){instrumentation.runOnMainSync(()->found[0]=hasCandidate(activity.getWindow().getDecorView()));Thread.sleep(250);}
            assertTrue("Gecko WebExtension must detect the actual media response",found[0]);
        }finally{instrumentation.runOnMainSync(activity::finish);}
    }
    private boolean hasCandidate(View view){
        if(view instanceof TextView && "감지된 미디어".contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return !((TextView)view).getText().toString().equals("▶ 0");
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(hasCandidate(((ViewGroup)view).getChildAt(i)))return true;return false;
    }
}
