package com.adownloader.app.download;

import com.adownloader.app.MediaCandidate;
import java.io.*;
import java.net.HttpURLConnection;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

/** Browser-independent VOD engine. Output is always validated before publication. */
public final class DownloadEngine {
    public interface Progress { void update(String text,int percent); }
    public static final class Result {
        public final File file;public final String mime,extension;
        Result(File file,String mime,String extension){this.file=file;this.mime=mime;this.extension=extension;}
    }
    private final File directory;
    private final Progress progress;
    private final List<File> temporary=new ArrayList<>();
    public DownloadEngine(File directory,Progress progress){this.directory=directory;this.progress=progress;}
    private File temp(String suffix)throws IOException{File f=File.createTempFile("adownloader_",suffix,directory);temporary.add(f);return f;}
    public void cleanup(){for(File file:temporary)file.delete();temporary.clear();}
    public Result download(String url,MediaCandidate.MediaType type,Map<String,String> headers)throws Exception {
        HttpSource source=new HttpSource(url,headers);
        if(type==MediaCandidate.MediaType.HLS || type==MediaCandidate.MediaType.DASH) {
            List<ManifestParser.Track> tracks;
            HttpSource.Document document=source.document(url);
            if(type==MediaCandidate.MediaType.HLS) {
                ManifestParser.HlsSelection selection=ManifestParser.hlsSelection(document.url,document.text);
                HttpSource.Document video=selection.video.equals(document.url)?document:source.document(selection.video);
                tracks=new ArrayList<>();tracks.add(ManifestParser.hlsTrack(video.url,video.text));
                if(selection.audio!=null) {HttpSource.Document audio=source.document(selection.audio);ManifestParser.Track a=ManifestParser.hlsTrack(audio.url,audio.text);a.audio=true;tracks.add(a);}
            } else tracks=ManifestParser.dash(document.url,document.text);
            List<File> inputs=new ArrayList<>();double duration=0;boolean video=false,audio=false;
            for(ManifestParser.Track track:tracks) {
                File input=temp(".media");inputs.add(input);duration=Math.max(duration,track.duration);
                if(track.parts.size()==1 && track.parts.get(0).length<0 && track.parts.get(0).keyUrl==null) {
                    source.copy(track.parts.get(0).url,input,progress);audio|=track.audio;video|=!track.audio;continue;
                }
                try(OutputStream output=new BufferedOutputStream(new FileOutputStream(input))) {
                    byte[] keyBytes=null;String lastKey=null;
                    for(int i=0;i<track.parts.size();i++) {
                        ManifestParser.Part part=track.parts.get(i);
                        byte[] bytes=source.bytes(part.url,part.offset,part.length,64*1024*1024);
                        if(part.keyUrl!=null) {
                            if(!part.keyUrl.equals(lastKey)){keyBytes=source.bytes(part.keyUrl,0,-1,1024);lastKey=part.keyUrl;}
                            if(keyBytes.length!=16)throw new IOException("잘못된 HLS AES-128 키");
                            byte[] iv=new byte[16];
                            if(part.iv!=null) {
                                String hex=part.iv.replaceFirst("(?i)^0x","");if(hex.length()>32)throw new IOException("잘못된 HLS IV");
                                hex="0".repeat(32-hex.length())+hex;for(int j=0;j<16;j++)iv[j]=(byte)Integer.parseInt(hex.substring(j*2,j*2+2),16);
                            } else {long sequence=part.sequence;for(int j=15;j>=0;j--){iv[j]=(byte)sequence;sequence>>>=8;}}
                            Cipher cipher=Cipher.getInstance("AES/CBC/PKCS5Padding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(keyBytes,"AES"),new IvParameterSpec(iv));bytes=cipher.doFinal(bytes);
                        }
                        output.write(bytes);progress.update((track.audio?"오디오":"영상")+" 조각 "+(i+1)+"/"+track.parts.size(),(i+1)*100/track.parts.size());
                    }
                    if(keyBytes!=null)Arrays.fill(keyBytes,(byte)0);
                }
                // Fragmented MP4/TS often lacks final duration until remuxed; validate the final indexed file below.
                audio|=track.audio;video|=!track.audio;
            }
            File output=temp(".mp4");progress.update("원본 트랙 무손실 병합 및 검증 중",100);
            PostProcessor.mux(inputs,output);PostProcessor.inspect(output,duration,video,audio);
            return new Result(output,"video/mp4",".mp4");
        }
        File file=temp(".media");source.copy(url,file,progress);
        PostProcessor.Inspection inspection=PostProcessor.inspect(file,0,type==MediaCandidate.MediaType.VIDEO,type==MediaCandidate.MediaType.AUDIO);
        byte[] signature=new byte[16];try(InputStream in=new FileInputStream(file)){in.read(signature);}
        boolean webm=(signature[0]&255)==0x1a&&(signature[1]&255)==0x45&&(signature[2]&255)==0xdf&&(signature[3]&255)==0xa3;
        boolean mp4=signature[4]=='f'&&signature[5]=='t'&&signature[6]=='y'&&signature[7]=='p';
        if(webm)return new Result(file,inspection.video?"video/webm":"audio/webm",".webm");
        if(mp4)return new Result(file,inspection.video?"video/mp4":"audio/mp4",inspection.video?".mp4":".m4a");
        // Repack other supported containers rather than assigning them a false .mp4 extension.
        File output=temp(".mp4");PostProcessor.mux(Collections.singletonList(file),output);PostProcessor.inspect(output,inspection.durationUs/1_000_000.0,inspection.video,inspection.audio);
        return new Result(output,inspection.video?"video/mp4":"audio/mp4",inspection.video?".mp4":".m4a");
    }
}
