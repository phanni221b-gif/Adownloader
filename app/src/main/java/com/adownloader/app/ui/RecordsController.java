package com.adownloader.app.ui;

import android.app.Activity;
import android.content.*;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.util.*;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.*;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.adownloader.app.R;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.*;

/** Downloads and owned-file library. Only exact app-owned MediaStore URIs are queried. */
public final class RecordsController implements SharedPreferences.OnSharedPreferenceChangeListener {
    private final Activity activity;
    private final RecyclerView downloads,library;
    private final View downloadEmpty,libraryEmpty;
    private final SwipeRefreshLayout downloadRefresh,libraryRefresh;
    private final ExecutorService io=Executors.newSingleThreadExecutor(),images=Executors.newFixedThreadPool(2);
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<String,JSONObject> metadata=new HashMap<>();
    private final LruCache<String,Bitmap> thumbnails=new LruCache<String,Bitmap>(8*1024*1024){@Override protected int sizeOf(String key,Bitmap value){return value.getByteCount();}};
    private final DownloadAdapter downloadAdapter=new DownloadAdapter();
    private final LibraryAdapter libraryAdapter=new LibraryAdapter();
    private final SharedPreferences prefs;
    private List<JSONObject> all=new ArrayList<>();
    private List<JSONObject> ownedVideos=new ArrayList<>();
    private boolean disposed,loading,pendingRefresh;
    private int filter=R.id.filter_all;
    public RecordsController(Activity activity){
        this.activity=activity;downloads=activity.findViewById(R.id.download_list);library=activity.findViewById(R.id.library_list);
        downloadEmpty=activity.findViewById(R.id.download_empty);libraryEmpty=activity.findViewById(R.id.library_empty);
        ((TextView)libraryEmpty.findViewById(R.id.empty_title)).setText("다운로드한 영상이 여기에 표시됩니다.");
        ((TextView)libraryEmpty.findViewById(R.id.empty_message)).setText("영상을 선택하면 기본 플레이어로 열립니다.");
        ((ImageView)libraryEmpty.findViewById(R.id.empty_icon)).setImageResource(R.drawable.ic_play);
        downloadRefresh=activity.findViewById(R.id.download_refresh);libraryRefresh=activity.findViewById(R.id.library_refresh);
        downloadRefresh.setOnRefreshListener(()->refresh(true));libraryRefresh.setOnRefreshListener(()->refresh(true));
        downloads.setLayoutManager(new GridLayoutManager(activity,1));library.setLayoutManager(new GridLayoutManager(activity,2));
        downloads.setAdapter(downloadAdapter);library.setAdapter(libraryAdapter);
        downloads.setItemAnimator(null);library.setItemAnimator(null);
        ((ChipGroup)activity.findViewById(R.id.download_filters)).setOnCheckedStateChangeListener((group,ids)->{if(!ids.isEmpty()){filter=ids.get(0);render();}});
        activity.findViewById(R.id.app_root).addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol)resize();});
        prefs=activity.getSharedPreferences("downloads",0);prefs.registerOnSharedPreferenceChangeListener(this);resize();refresh(false);
    }
    public void resize(){
        float width=activity.getResources().getConfiguration().screenWidthDp;
        ((GridLayoutManager)downloads.getLayoutManager()).setSpanCount(width>=840?2:1);
        ((GridLayoutManager)library.getLayoutManager()).setSpanCount(width>=1000?4:width>=700?3:2);
    }
    @Override public void onSharedPreferenceChanged(SharedPreferences shared,String key){main.removeCallbacks(reload);main.postDelayed(reload,250);}
    private final Runnable reload=()->refresh(false);
    public void refresh(boolean force){
        if(disposed)return;if(loading){pendingRefresh=true;return;}loading=true;if(force)metadata.clear();
        io.execute(()->{
            List<JSONObject> rows=DownloadHistory.read(activity);
            for(JSONObject row:rows)if(DownloadHistory.done(row))enrich(row,force);
            List<JSONObject> videos=loadOwnedVideos(rows);
            main.post(()->{if(disposed)return;all=rows;ownedVideos=videos;loading=false;render();downloadRefresh.setRefreshing(false);libraryRefresh.setRefreshing(false);if(pendingRefresh){pendingRefresh=false;refresh(false);}});
        });
    }
    private List<JSONObject> loadOwnedVideos(List<JSONObject> history){
        Map<String,JSONObject> videos=new LinkedHashMap<>();
        for(JSONObject row:history)if(DownloadHistory.done(row)&&row.optBoolean("available")&&!row.optString("mime").startsWith("audio/"))videos.put(row.optString("uri"),row);
        String[] columns={MediaStore.Video.Media._ID,MediaStore.Video.Media.DISPLAY_NAME,MediaStore.Video.Media.SIZE,MediaStore.Video.Media.MIME_TYPE,MediaStore.Video.Media.DATE_ADDED,MediaStore.Video.Media.DURATION,MediaStore.Video.Media.WIDTH,MediaStore.Video.Media.HEIGHT};
        String selection=MediaStore.MediaColumns.OWNER_PACKAGE_NAME+"=? AND "+MediaStore.MediaColumns.RELATIVE_PATH+" LIKE ?";
        try(Cursor cursor=activity.getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,columns,selection,new String[]{activity.getPackageName(),android.os.Environment.DIRECTORY_MOVIES+"/Adownloader/%"},MediaStore.Video.Media.DATE_ADDED+" DESC")){
            while(cursor!=null&&cursor.moveToNext()){
                String uri=ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,cursor.getLong(0)).toString();
                if(videos.containsKey(uri))continue;
                JSONObject row=new JSONObject();row.put("uri",uri);row.put("name",cursor.getString(1));row.put("bytes",cursor.getLong(2));row.put("mime",cursor.getString(3));row.put("time",cursor.getLong(4)*1000);row.put("duration",cursor.getLong(5));row.put("width",cursor.getInt(6));row.put("height",cursor.getInt(7));row.put("available",true);videos.put(uri,row);
            }
        }catch(Exception ignored){} // Own-URI history remains available on providers with a restricted owner query.
        List<JSONObject> result=new ArrayList<>(videos.values());result.sort((a,b)->Long.compare(b.optLong("time"),a.optLong("time")));return result;
    }
    private void enrich(JSONObject row,boolean force){
        String uri=row.optString("uri");if(uri.isEmpty())return;
        try{
            JSONObject facts=metadata.get(uri);
            if(facts==null){
                facts=new JSONObject();facts.put("available",false);
                try(Cursor cursor=activity.getContentResolver().query(Uri.parse(uri),new String[]{MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.SIZE,MediaStore.MediaColumns.MIME_TYPE},null,null,null)){
                    if(cursor!=null&&cursor.moveToFirst()){facts.put("available",true);facts.put("name",cursor.getString(0));facts.put("bytes",cursor.getLong(1));facts.put("mime",cursor.getString(2));}
                }
                if(facts.optBoolean("available")&&row.optLong("duration")==0)try(MediaMetadataRetriever retriever=new MediaMetadataRetriever()){
                    retriever.setDataSource(activity,Uri.parse(uri));String duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION),width=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),height=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                    if(duration!=null)facts.put("duration",Long.parseLong(duration));if(width!=null)facts.put("width",Long.parseLong(width));if(height!=null)facts.put("height",Long.parseLong(height));
                }catch(Exception ignored){}
                metadata.put(uri,facts);
            }
            Iterator<String> keys=facts.keys();while(keys.hasNext()){String key=keys.next();row.put(key,facts.get(key));}
        }catch(Exception ignored){try{row.put("available",false);}catch(Exception impossible){}}
    }
    private void render(){
        if(disposed)return;
        List<JSONObject> visible=new ArrayList<>(),videos=ownedVideos;int active=0,complete=0,failed=0;
        for(JSONObject row:all){boolean running=DownloadHistory.active(row),done=DownloadHistory.done(row);if(running)active++;else if(done)complete++;else failed++;
            if(filter==R.id.filter_all||(filter==R.id.filter_active&&running)||(filter==R.id.filter_done&&done)||(filter==R.id.filter_failed&&!running&&!done))visible.add(row);
        }
        downloadAdapter.rows=visible;libraryAdapter.rows=videos;downloadAdapter.notifyDataSetChanged();libraryAdapter.notifyDataSetChanged();
        downloadEmpty.setVisibility(visible.isEmpty()?View.VISIBLE:View.GONE);libraryEmpty.setVisibility(videos.isEmpty()?View.VISIBLE:View.GONE);
        ((TextView)activity.findViewById(R.id.download_summary)).setText(all.isEmpty()?"원본 그대로, 내 기기에":"진행 중 "+active+" · 완료 "+complete+" · 실패 "+failed);
        ((TextView)activity.findViewById(R.id.library_summary)).setText(videos.isEmpty()?"좋아하는 플레이어로 이어서 감상하세요":videos.size()+"개의 영상 · 기본 플레이어로 열기");
        ((TextView)downloadEmpty.findViewById(R.id.empty_title)).setText(filter==R.id.filter_all?"아직 다운로드한 영상이 없습니다.":"해당하는 다운로드가 없습니다.");
    }
    private void thumbnail(ImageView view,JSONObject row){
        String uri=row.optString("uri");view.setTag(uri);view.setImageResource(R.drawable.ic_media);view.setImageTintList(android.content.res.ColorStateList.valueOf(activity.getColor(R.color.ink_muted)));
        view.setPadding(0,0,0,0);
        if(uri.isEmpty())return;Bitmap cached=thumbnails.get(uri);
        if(cached!=null){view.setImageTintList(null);view.setImageBitmap(cached);return;}
        images.execute(()->{try{Bitmap bitmap=activity.getContentResolver().loadThumbnail(Uri.parse(uri),new Size(480,270),null);thumbnails.put(uri,bitmap);main.post(()->{if(!disposed&&uri.equals(view.getTag())){view.setImageTintList(null);view.setImageBitmap(bitmap);}});}catch(Exception ignored){}});
    }
    private String date(JSONObject row){return android.text.format.DateFormat.format("MM.dd HH:mm",row.optLong("time")).toString();}
    private String facts(JSONObject row){List<String> values=new ArrayList<>();if(row.optLong("height")>0)values.add(row.optLong("height")+"p");if(row.optLong("bytes")>0)values.add(DownloadHistory.size(activity,row.optLong("bytes")));values.add(date(row));return android.text.TextUtils.join(" · ",values);}
    private final class DownloadAdapter extends RecyclerView.Adapter<DownloadHolder>{
        List<JSONObject> rows=new ArrayList<>();
        @Override public DownloadHolder onCreateViewHolder(ViewGroup parent,int type){return new DownloadHolder(activity.getLayoutInflater().inflate(R.layout.item_download,parent,false));}
        @Override public int getItemCount(){return rows.size();}
        @Override public void onBindViewHolder(DownloadHolder holder,int position){
            JSONObject row=rows.get(position);boolean active=DownloadHistory.active(row),done=DownloadHistory.done(row);
            holder.title.setText(row.optString("name","동영상 다운로드"));
            String host=row.optString("host"),type=row.optString("type");
            if(type.equals("VIDEO"))type=row.optString("mime").contains("mp4")?"MP4":row.optString("mime").contains("webm")?"WEBM":"동영상";else if(type.equals("AUDIO"))type="오디오";
            String state=active?row.optString("state"):done?"완료":"실패";
            holder.subtitle.setText(state+(type.isEmpty()?"":" · "+type)+(host.isEmpty()?"":" · "+host));
            holder.subtitle.setTextColor(activity.getColor(!active&&!done?R.color.danger:R.color.ink_muted));
            holder.progress.setVisibility(active?View.VISIBLE:View.GONE);holder.progress.setIndeterminate(active&&!row.has("percent"));if(row.has("percent"))holder.progress.setProgressCompat(row.optInt("percent"),true);
            String phase=row.optString("phase","준비 중");holder.detail.setText(active?phase+(row.has("percent")?" · "+row.optInt("percent")+"%":""):done?facts(row):DownloadHistory.userError(row.optString("state").startsWith("실패")?row.optString("state"):"중단"));
            holder.open.setVisibility(done&&row.optBoolean("available")?View.VISIBLE:View.GONE);
            holder.open.setOnClickListener(v->MediaOpener.open(activity,row.optString("uri"),row.optString("mime")));
            if(done&&!row.optBoolean("available"))holder.detail.setText("파일을 찾을 수 없습니다. 기기에서 삭제되었을 수 있습니다.");
            thumbnail(holder.image,row);
        }
    }
    private static final class DownloadHolder extends RecyclerView.ViewHolder{
        final TextView title,subtitle,detail;final View open;final ImageView image;final LinearProgressIndicator progress;
        DownloadHolder(View view){super(view);title=view.findViewById(R.id.item_title);subtitle=view.findViewById(R.id.item_subtitle);detail=view.findViewById(R.id.item_detail);open=view.findViewById(R.id.item_open);image=view.findViewById(R.id.item_thumbnail);progress=view.findViewById(R.id.item_progress);}
    }
    private final class LibraryAdapter extends RecyclerView.Adapter<LibraryHolder>{
        List<JSONObject> rows=new ArrayList<>();
        @Override public LibraryHolder onCreateViewHolder(ViewGroup parent,int type){return new LibraryHolder(activity.getLayoutInflater().inflate(R.layout.item_library,parent,false));}
        @Override public int getItemCount(){return rows.size();}
        @Override public void onBindViewHolder(LibraryHolder holder,int position){JSONObject row=rows.get(position);holder.title.setText(row.optString("name"));holder.detail.setText(DownloadHistory.size(activity,row.optLong("bytes"))+" · "+date(row));holder.duration.setText(row.optLong("duration")>0?DownloadHistory.duration(row.optLong("duration")):"");holder.duration.setVisibility(row.optLong("duration")>0?View.VISIBLE:View.GONE);thumbnail(holder.image,row);holder.itemView.setContentDescription(row.optString("name")+" 재생");holder.itemView.setOnClickListener(v->MediaOpener.open(activity,row.optString("uri"),row.optString("mime")));}
    }
    private static final class LibraryHolder extends RecyclerView.ViewHolder{final ImageView image;final TextView title,detail,duration;LibraryHolder(View view){super(view);image=view.findViewById(R.id.library_thumbnail);title=view.findViewById(R.id.library_title);detail=view.findViewById(R.id.library_detail);duration=view.findViewById(R.id.library_duration);}}
    public void close(){disposed=true;prefs.unregisterOnSharedPreferenceChangeListener(this);main.removeCallbacksAndMessages(null);io.shutdownNow();images.shutdownNow();}
}
