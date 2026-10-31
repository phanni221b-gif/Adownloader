package com.adownloader.app.ui;

import android.content.*;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.MediaStore;
import org.json.JSONObject;
import java.util.*;

/** UI-only job telemetry. Never persists a media URL, request header, cookie or page URL. */
public final class DownloadHistory {
    private DownloadHistory(){}
    private static final Map<Integer,Long> lastProgress=new HashMap<>();
    public static synchronized void begin(Context context,int id,String host,String type){
        JSONObject data=new JSONObject();try{data.put("host",host);data.put("type",type);data.put("name","동영상 다운로드");data.put("pid",android.os.Process.myPid());data.put("created",System.currentTimeMillis());}catch(Exception ignored){}
        merge(context,id,data,"대기 중",null);
    }
    public static synchronized void progress(Context context,int id,String phase,int percent){
        long now=android.os.SystemClock.elapsedRealtime();if(percent!=100&&now-lastProgress.getOrDefault(id,0L)<500)return;lastProgress.put(id,now);
        JSONObject data=new JSONObject();try{data.put("percent",percent);data.put("phase",phase);}catch(Exception ignored){}merge(context,id,data,"다운로드 중",null);
    }
    public static synchronized void state(Context context,int id,String state,String uri){
        JSONObject data=new JSONObject();
        if(uri!=null){
            try(Cursor cursor=context.getContentResolver().query(Uri.parse(uri),new String[]{MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.SIZE,MediaStore.MediaColumns.MIME_TYPE},null,null,null)){
                if(cursor!=null&&cursor.moveToFirst()){data.put("name",cursor.getString(0));data.put("bytes",cursor.getLong(1));data.put("mime",cursor.getString(2));}
            }catch(Exception ignored){}
            try(MediaMetadataRetriever retriever=new MediaMetadataRetriever()){
                retriever.setDataSource(context,Uri.parse(uri));
                putLong(data,"duration",retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                putLong(data,"width",retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                putLong(data,"height",retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            }catch(Exception ignored){}
        }
        merge(context,id,data,state,uri);if(!state.equals("대기 중")&&!state.equals("다운로드 중"))lastProgress.remove(id);
    }
    private static void putLong(JSONObject json,String key,String value)throws Exception{if(value!=null)json.put(key,Long.parseLong(value));}
    private static void merge(Context context,int id,JSONObject updates,String state,String uri){
        SharedPreferences prefs=context.getSharedPreferences("downloads",Context.MODE_PRIVATE);
        try{
            JSONObject all=new JSONObject(prefs.getString("items","{}"));String key=Integer.toString(id);JSONObject item=all.optJSONObject(key);if(item==null)item=new JSONObject();
            Iterator<String> changes=updates.keys();while(changes.hasNext()){String field=changes.next();item.put(field,updates.get(field));}
            item.put("state",state);item.put("time",System.currentTimeMillis());if(uri!=null)item.put("uri",uri);all.put(key,item);
            List<String> keys=new ArrayList<>();Iterator<String> iterator=all.keys();while(iterator.hasNext())keys.add(iterator.next());
            keys.sort((a,b)->Long.compare(all.optJSONObject(a).optLong("time"),all.optJSONObject(b).optLong("time")));
            while(keys.size()>100)all.remove(keys.remove(0));prefs.edit().putString("items",all.toString()).apply();
        }catch(Exception ignored){}
    }
    public static String userError(String error){
        String text=error==null?"":error;
        if(text.contains("DRM")||text.contains("SAMPLE-AES"))return "보호된 영상은 다운로드할 수 없습니다.";
        if(text.contains("HTTP 401")||text.contains("HTTP 403"))return "이 영상에 접근할 수 없습니다. 사이트에서 재생 가능한지 확인하세요.";
        if(text.contains("공간")||text.contains("ENOSPC"))return "저장 공간이 부족합니다.";
        if(text.contains("HTTP")||text.contains("네트워크")||text.contains("timeout")||text.contains("Host")||text.contains("연결"))return "연결이 끊겼습니다. 네트워크 상태를 확인해 주세요.";
        if(text.contains("중단"))return "다운로드가 중단되었습니다. 다시 시작해 주세요.";
        return "이 영상 형식은 아직 지원하지 않거나 파일이 완전하지 않습니다.";
    }
    public static List<JSONObject> read(Context context){
        List<JSONObject> rows=new ArrayList<>();try{JSONObject all=new JSONObject(context.getSharedPreferences("downloads",0).getString("items","{}"));Iterator<String> keys=all.keys();while(keys.hasNext())rows.add(all.getJSONObject(keys.next()));}catch(Exception ignored){}
        rows.sort((a,b)->{boolean first=active(a),second=active(b);return first!=second?(first?-1:1):Long.compare(b.optLong("time"),a.optLong("time"));});return rows;
    }
    public static boolean active(JSONObject row){return (row.optString("state").equals("대기 중")||row.optString("state").equals("다운로드 중"))&&row.optInt("pid",-1)==android.os.Process.myPid();}
    public static boolean done(JSONObject row){return row.optString("state").equals("완료");}
    public static String size(Context context,long bytes){return bytes>0?android.text.format.Formatter.formatShortFileSize(context,bytes):"";}
    public static String duration(long millis){long seconds=millis/1000;return seconds>=3600?String.format(Locale.ROOT,"%d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60):String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60);}
}
