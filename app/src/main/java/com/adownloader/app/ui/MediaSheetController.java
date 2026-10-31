package com.adownloader.app.ui;

import android.app.Activity;
import android.net.Uri;
import android.view.*;
import android.widget.TextView;
import androidx.recyclerview.widget.*;
import com.adownloader.app.*;
import com.adownloader.app.download.*;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.*;
import java.util.concurrent.*;

/** Read-only metadata preview. Download selection remains exclusively in the frozen engine. */
public final class MediaSheetController {
    public interface Downloader{void start(MediaCandidate candidate);}
    private final Activity activity;private final Downloader downloader;
    private final ExecutorService io=Executors.newFixedThreadPool(2);
    private final Map<String,String> information=new ConcurrentHashMap<>();
    private BottomSheetDialog dialog;private boolean closed;
    public MediaSheetController(Activity activity,Downloader downloader){this.activity=activity;this.downloader=downloader;}
    public static String type(MediaCandidate item){
        if(item.getType()==MediaCandidate.MediaType.HLS)return "HLS";if(item.getType()==MediaCandidate.MediaType.DASH)return "DASH";
        String path=Uri.parse(item.getUrl()).getPath();if(path!=null){String low=path.toLowerCase(Locale.ROOT);if(low.endsWith(".mp4"))return "MP4";if(low.endsWith(".webm"))return "WEBM";}
        return item.getType()==MediaCandidate.MediaType.AUDIO?"오디오":"동영상";
    }
    public static String name(MediaCandidate item,int index){
        String segment=Uri.parse(item.getUrl()).getLastPathSegment();
        if(segment!=null&&segment.length()<70&&segment.matches("(?i).+\\.(mp4|webm|m4a|mp3|mov)$"))return String.format(Locale.ROOT,"%02d",index+1)+" · "+segment;
        return "영상 "+String.format(Locale.ROOT,"%02d",index+1);
    }
    public void show(List<MediaCandidate> candidates){
        if(dialog!=null)dialog.dismiss();
        View content=activity.getLayoutInflater().inflate(R.layout.sheet_list,null);
        ((TextView)content.findViewById(R.id.sheet_title)).setText("감지된 미디어"+(candidates.isEmpty()?"":" "+candidates.size()));
        RecyclerView list=content.findViewById(R.id.sheet_list);list.setLayoutManager(new LinearLayoutManager(activity));
        MediaAdapter adapter=new MediaAdapter(candidates);list.setAdapter(adapter);
        if(candidates.isEmpty()){
            View empty=content.findViewById(R.id.sheet_empty);empty.setVisibility(View.VISIBLE);
            ((TextView)empty.findViewById(R.id.empty_title)).setText("아직 감지된 미디어가 없습니다.");
            ((TextView)empty.findViewById(R.id.empty_message)).setText("페이지에서 영상을 재생하면 자동으로 감지합니다.");
            ((android.widget.ImageView)empty.findViewById(R.id.empty_icon)).setImageResource(R.drawable.ic_media);
        }
        dialog=UiSheets.show(activity,content,true);content.findViewById(R.id.sheet_close).setOnClickListener(v->dialog.dismiss());
        for(MediaCandidate item:candidates)if(!information.containsKey(item.getUrl())){
            information.put(item.getUrl(),"정보 분석 중");
            io.execute(()->{String info=preview(item);if(closed)return;information.put(item.getUrl(),info);activity.runOnUiThread(()->{if(!closed)adapter.notifyDataSetChanged();});});
        }
    }
    private String preview(MediaCandidate item){
        if(item.getType()!=MediaCandidate.MediaType.HLS&&item.getType()!=MediaCandidate.MediaType.DASH)return "원본 파일 그대로 저장";
        try{
            HttpSource source=new HttpSource(item.getUrl(),item.getHeaders());HttpSource.Document document=source.document(item.getUrl());
            List<String> fields=new ArrayList<>();
            if(item.getType()==MediaCandidate.MediaType.HLS){
                ManifestParser.HlsSelection selected=ManifestParser.hlsSelection(document.url,document.text);String[] lines=document.text.split("\\r?\\n");
                for(int i=0;i<lines.length;i++)if(lines[i].trim().startsWith("#EXT-X-STREAM-INF:")){
                    int next=i+1;while(next<lines.length&&lines[next].trim().isEmpty())next++;
                    if(next<lines.length&&ManifestParser.resolve(document.url,lines[next].trim()).equals(selected.video)){
                        Map<String,String> attrs=ManifestParser.attributes(lines[i].trim());String resolution=attrs.get("RESOLUTION");if(resolution!=null){String[] size=resolution.split("x");if(size.length==2)fields.add(size[1]+"p");}
                        if(attrs.containsKey("FRAME-RATE"))fields.add(attrs.get("FRAME-RATE")+"fps");
                        if(attrs.containsKey("CODECS"))fields.add(attrs.get("CODECS"));break;
                    }
                }
                if(selected.audio!=null)fields.add("별도 오디오 포함");
            }else{
                ManifestParser.dash(document.url,document.text); // Validate with exactly the same read-only planner.
                if(document.text.toUpperCase(Locale.ROOT).contains("<!DOCTYPE"))return "미디어 정보 확인 불가";
                DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setExpandEntityReferences(false);
                Element root=factory.newDocumentBuilder().parse(new InputSource(new StringReader(document.text))).getDocumentElement();
                NodeList reps=root.getElementsByTagName("Representation");Element best=null;long pixels=-1,bandwidth=-1;double fps=-1;
                for(int i=0;i<reps.getLength();i++){
                    Element rep=(Element)reps.item(i),parent=(Element)rep.getParentNode();String mime=attribute(rep,parent,"mimeType"),kind=attribute(rep,parent,"contentType");if(!mime.startsWith("video/")&&!kind.equals("video"))continue;
                    long p=number(attribute(rep,parent,"width"))*number(attribute(rep,parent,"height")),b=number(rep.getAttribute("bandwidth"));double f=frameRate(attribute(rep,parent,"frameRate"));
                    if(p>pixels||(p==pixels&&(f>fps||(f==fps&&b>bandwidth)))){best=rep;pixels=p;fps=f;bandwidth=b;}
                }
                if(best!=null){Element parent=(Element)best.getParentNode();String height=attribute(best,parent,"height");if(!height.isEmpty())fields.add(height+"p");if(fps>0)fields.add(String.format(Locale.ROOT,"%.2f",fps).replaceAll("\\.?0+$","")+"fps");String codec=attribute(best,parent,"codecs");if(!codec.isEmpty())fields.add(codec);}
            }
            return fields.isEmpty()?"최고 원본 자동 선택":android.text.TextUtils.join(" · ",fields);
        }catch(Exception error){return "세부 정보 확인 불가 · 원본 자동 선택";}
    }
    private String attribute(Element rep,Element parent,String name){String value=rep.getAttribute(name);return value.isEmpty()?parent.getAttribute(name):value;}
    private long number(String value){try{return Long.parseLong(value);}catch(Exception ignored){return 0;}}
    private double frameRate(String value){try{String[] parts=value.split("/");return Double.parseDouble(parts[0])/(parts.length==2?Double.parseDouble(parts[1]):1);}catch(Exception ignored){return 0;}}
    private void confirm(MediaCandidate item,int index){
        if(dialog!=null)dialog.dismiss();String info=information.getOrDefault(item.getUrl(),"");if(info.equals("정보 분석 중"))info="";
        UiSheets.action(activity,R.drawable.ic_download,name(item,index),"최고 원본 화질 · "+type(item)+(info.isEmpty()?"":"\n"+info)+"\n"+Uri.parse(item.getUrl()).getHost(),"취소","다운로드",()->downloader.start(item),null,null);
    }
    private final class MediaAdapter extends RecyclerView.Adapter<MediaHolder>{
        private final List<MediaCandidate> items;private final Set<String> expanded=new HashSet<>();
        MediaAdapter(List<MediaCandidate> items){this.items=items;}
        @Override public int getItemCount(){return items.size();}
        @Override public MediaHolder onCreateViewHolder(ViewGroup parent,int type){return new MediaHolder(activity.getLayoutInflater().inflate(R.layout.item_media,parent,false));}
        @Override public void onBindViewHolder(MediaHolder holder,int position){MediaCandidate item=items.get(position);holder.title.setText(name(item,position));holder.type.setText(type(item));holder.host.setText(Uri.parse(item.getUrl()).getHost());holder.info.setText(information.getOrDefault(item.getUrl(),"정보 분석 중"));holder.url.setText(item.getUrl());holder.url.setVisibility(expanded.contains(item.getUrl())?View.VISIBLE:View.GONE);holder.details.setOnClickListener(v->{if(!expanded.add(item.getUrl()))expanded.remove(item.getUrl());notifyItemChanged(position);});holder.download.setOnClickListener(v->confirm(item,position));}
    }
    private static final class MediaHolder extends RecyclerView.ViewHolder{final TextView title,type,host,info,url;final View details,download;MediaHolder(View view){super(view);title=view.findViewById(R.id.media_title);type=view.findViewById(R.id.media_type);host=view.findViewById(R.id.media_host);info=view.findViewById(R.id.media_metadata);url=view.findViewById(R.id.media_url);details=view.findViewById(R.id.media_details);download=view.findViewById(R.id.media_download);}}
    public void close(){closed=true;if(dialog!=null)dialog.dismiss();io.shutdownNow();}
}
