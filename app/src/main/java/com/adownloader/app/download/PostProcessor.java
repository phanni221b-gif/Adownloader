package com.adownloader.app.download;

import android.media.*;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

/** Sample copy only. No decoder, encoder or MediaCodec is instantiated. */
public final class PostProcessor {
    public static final class Inspection {
        public boolean video, audio;
        public long durationUs;
    }
    public static Inspection inspect(File file, double expectedSeconds, boolean needVideo, boolean needAudio) throws Exception {
        MediaExtractor extractor=new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            if(extractor.getPsshInfo()!=null && !extractor.getPsshInfo().isEmpty()) throw new IOException("DRM 미디어는 지원하지 않습니다");
            Inspection result=new Inspection();
            for(int i=0;i<extractor.getTrackCount();i++) {
                MediaFormat format=extractor.getTrackFormat(i);String mime=format.getString(MediaFormat.KEY_MIME);
                if(mime==null || !(mime.startsWith("video/") || mime.startsWith("audio/")))continue;
                if(mime.startsWith("video/"))result.video=true;else result.audio=true;
                long duration=format.containsKey(MediaFormat.KEY_DURATION)?format.getLong(MediaFormat.KEY_DURATION):0;
                if(duration<=0)throw new IOException("미디어 길이를 확인할 수 없습니다");
                if(expectedSeconds>0 && Math.abs(duration/1_000_000.0-expectedSeconds)>Math.max(2.0,expectedSeconds*0.015))throw new IOException("트랙 길이가 원본 목록과 일치하지 않습니다");
                result.durationUs=Math.max(result.durationUs,duration);
                extractor.selectTrack(i);
                extractor.seekTo(duration*3/4,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                if(extractor.getSampleTime()<0 || extractor.getSampleSize()<=0)throw new IOException("미디어 탐색 검증 실패");
                long sampleSize=extractor.getSampleSize();
                if(sampleSize>64*1024*1024 || extractor.readSampleData(ByteBuffer.allocate((int)sampleSize),0)!=sampleSize)throw new IOException("미디어 샘플이 잘렸습니다");
                if((extractor.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_ENCRYPTED)!=0)throw new IOException("DRM 트랙은 지원하지 않습니다");
                extractor.unselectTrack(i);
            }
            if(result.durationUs<=0 || (needVideo&&!result.video) || (needAudio&&!result.audio))throw new IOException("필수 미디어 트랙 누락");
            return result;
        } finally {extractor.release();}
    }
    public static void mux(List<File> files, File output) throws Exception {
        List<MediaExtractor> extractors=new ArrayList<>();
        List<Map<Integer,Integer>> maps=new ArrayList<>();
        MediaMuxer muxer=null;boolean started=false;
        try {
            muxer=new MediaMuxer(output.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            long maxInput=4*1024*1024;
            for(File file:files) {
                MediaExtractor extractor=new MediaExtractor();extractors.add(extractor);
                extractor.setDataSource(file.getAbsolutePath());
                if(extractor.getPsshInfo()!=null && !extractor.getPsshInfo().isEmpty())throw new IOException("DRM 트랙은 지원하지 않습니다");
                Map<Integer,Integer> map=new HashMap<>();maps.add(map);
                for(int t=0;t<extractor.getTrackCount();t++) {
                    MediaFormat format=extractor.getTrackFormat(t);String mime=format.getString(MediaFormat.KEY_MIME);
                    if(mime==null || !(mime.startsWith("video/") || mime.startsWith("audio/")))continue;
                    // If there is a separate audio source, avoid duplicate audio from the video source.
                    if(files.size()>1 && files.indexOf(file)==0 && mime.startsWith("audio/"))continue;
                    map.put(t,muxer.addTrack(format));extractor.selectTrack(t);
                    if(format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))maxInput=Math.max(maxInput,format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                }
                if(map.isEmpty())throw new IOException("병합할 트랙이 없습니다");
            }
            if(maxInput>64*1024*1024)throw new IOException("미디어 샘플 크기 제한 초과");
            ByteBuffer buffer=ByteBuffer.allocateDirect((int)maxInput);MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            long firstTime=Long.MAX_VALUE;
            for(MediaExtractor extractor:extractors)if(extractor.getSampleTrackIndex()>=0)firstTime=Math.min(firstTime,extractor.getSampleTime());
            if(firstTime==Long.MAX_VALUE)throw new IOException("미디어 샘플 없음");
            muxer.start();started=true;
            while(true) {
                int selected=-1;long earliest=Long.MAX_VALUE;
                for(int i=0;i<extractors.size();i++){MediaExtractor candidate=extractors.get(i);long time=candidate.getSampleTime();if(candidate.getSampleTrackIndex()>=0&&time<earliest){selected=i;earliest=time;}}
                if(selected<0)break;
                MediaExtractor source=extractors.get(selected);int flags=source.getSampleFlags();
                if((flags&MediaExtractor.SAMPLE_FLAG_ENCRYPTED)!=0)throw new IOException("DRM 샘플은 지원하지 않습니다");
                long size=source.getSampleSize();if(size>buffer.capacity()) {
                    if(size>64*1024*1024)throw new IOException("미디어 샘플 크기 제한 초과");buffer=ByteBuffer.allocateDirect((int)size);
                }
                buffer.clear();int count=source.readSampleData(buffer,0);if(count<0)throw new IOException("미디어 샘플 읽기 실패");
                info.set(0,count,Math.max(0,earliest-firstTime),(flags&MediaExtractor.SAMPLE_FLAG_SYNC)!=0?MediaCodec.BUFFER_FLAG_KEY_FRAME:0);
                muxer.writeSampleData(maps.get(selected).get(source.getSampleTrackIndex()),buffer,info);source.advance();
            }
            muxer.stop();started=false;
        } finally {
            if(muxer!=null){if(started)try{muxer.stop();}catch(Exception ignored){}muxer.release();}
            for(MediaExtractor extractor:extractors)extractor.release();
        }
    }
}
