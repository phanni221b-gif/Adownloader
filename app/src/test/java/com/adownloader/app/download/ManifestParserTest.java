package com.adownloader.app.download;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class ManifestParserTest {
    private final String base="https://media.example/path/master.m3u8";
    @Test public void resolutionThenFpsThenBitrateAndAudio() {
        String text="#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"Main, English\",DEFAULT=YES,URI=\"audio/list.m3u8\"\n"+
            "#EXT-X-STREAM-INF:RESOLUTION=1280x720,FRAME-RATE=60,BANDWIDTH=99000000\n720.m3u8\n"+
            "#EXT-X-STREAM-INF:RESOLUTION=3840x2160,FRAME-RATE=30,BANDWIDTH=9000000\n4k30.m3u8\n"+
            "#EXT-X-STREAM-INF:RESOLUTION=3840x2160,FRAME-RATE=60,BANDWIDTH=10000000,AUDIO=\"a\"\n4k60.m3u8\n";
        ManifestParser.HlsSelection selection=ManifestParser.hlsSelection(base,text);
        assertEquals("https://media.example/path/4k60.m3u8",selection.video);
        assertEquals("https://media.example/path/audio/list.m3u8",selection.audio);
    }
    @Test public void hlsRangeAndSequenceAndKeyRotation() {
        ManifestParser.Track track=ManifestParser.hlsTrack(base,"#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:100\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:5,\n#EXT-X-BYTERANGE:10@0\nfile.ts\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:5,\n#EXT-X-BYTERANGE:12\nfile.ts\n#EXT-X-ENDLIST");
        assertEquals(2,track.parts.size());assertEquals(100,track.parts.get(0).sequence);assertEquals("https://media.example/path/key",track.parts.get(0).keyUrl);
        assertNull(track.parts.get(1).keyUrl);assertEquals(10,track.parts.get(1).offset);assertEquals(12,track.parts.get(1).length);assertEquals(10,track.duration,0);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectDrmHls(){ManifestParser.hlsTrack(base,"#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\"\n#EXTINF:5,\nx.ts\n#EXT-X-ENDLIST");}
    @Test(expected=IllegalArgumentException.class) public void rejectLiveHls(){ManifestParser.hlsTrack(base,"#EXTM3U\n#EXTINF:5,\nx.ts");}
    @Test(expected=IllegalArgumentException.class) public void rejectGap(){ManifestParser.hlsTrack(base,"#EXTM3U\n#EXT-X-GAP\n#EXTINF:5,\nx.ts\n#EXT-X-ENDLIST");}
    @Test public void dashInheritedTemplateAndSeparateAudio()throws Exception {
        String xml="<MPD mediaPresentationDuration='PT6S'><BaseURL>https://cdn.example/</BaseURL><Period>"+
            "<AdaptationSet mimeType='video/mp4'><SegmentTemplate timescale='10' duration='20' initialization='$RepresentationID$/init.mp4' media='$RepresentationID$/$Number%03d$.m4s'/>"+
            "<Representation id='low' width='1280' height='720' bandwidth='99000000'/><Representation id='high' width='3840' height='2160' frameRate='60000/1001' bandwidth='8000000'/></AdaptationSet>"+
            "<AdaptationSet mimeType='audio/mp4'><Representation id='audio' bandwidth='256000'><BaseURL>sound.m4a</BaseURL></Representation></AdaptationSet></Period></MPD>";
        List<ManifestParser.Track> tracks=ManifestParser.dash(base,xml);
        assertEquals(2,tracks.size());assertEquals(4,tracks.get(0).parts.size());assertEquals("https://cdn.example/high/001.m4s",tracks.get(0).parts.get(1).url);assertTrue(tracks.get(1).audio);assertEquals("https://cdn.example/sound.m4a",tracks.get(1).parts.get(0).url);
    }
    @Test public void dashTimelineNegativeRepeatTerminates()throws Exception {
        String xml="<MPD mediaPresentationDuration='PT6S'><Period><AdaptationSet mimeType='video/mp4'><SegmentTemplate timescale='10' initialization='init.mp4' media='$Time$.m4s'><SegmentTimeline><S t='0' d='20' r='-1'/></SegmentTimeline></SegmentTemplate><Representation id='v' bandwidth='1'/></AdaptationSet></Period></MPD>";
        ManifestParser.Track track=ManifestParser.dash(base,xml).get(0);assertEquals(4,track.parts.size());assertEquals("https://media.example/path/40.m4s",track.parts.get(3).url);
    }
    @Test public void dashSegmentListRanges()throws Exception {
        String xml="<MPD mediaPresentationDuration='PT2S'><Period><AdaptationSet mimeType='video/mp4'><Representation><BaseURL>full.mp4</BaseURL><SegmentList><Initialization range='0-99'/><SegmentURL mediaRange='100-199'/></SegmentList></Representation></AdaptationSet></Period></MPD>";
        ManifestParser.Track track=ManifestParser.dash(base,xml).get(0);assertEquals(100,track.parts.get(1).offset);assertEquals(100,track.parts.get(1).length);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectDashDrm()throws Exception {ManifestParser.dash(base,"<MPD><Period><AdaptationSet><ContentProtection schemeIdUri='cenc'/></AdaptationSet></Period></MPD>");}
    @Test(expected=IllegalArgumentException.class) public void rejectXmlEntities()throws Exception {ManifestParser.dash(base,"<!DOCTYPE MPD [<!ENTITY secret SYSTEM 'file:///secret'>]><MPD>&secret;</MPD>");}
    @Test(expected=IllegalArgumentException.class) public void rejectDynamicDash()throws Exception {ManifestParser.dash(base,"<MPD type='dynamic'><Period/></MPD>");}
    @Test public void credentialsNeverCrossOriginOrPort() {
        Map<String,String> headers=new HashMap<>();headers.put("Cookie","session=secret");headers.put("Authorization","secret");headers.put("User-Agent","test");headers.put("X-Unwanted","x");
        RequestContext context=new RequestContext("https://media.example/a",headers);
        assertTrue(context.forUrl("https://media.example/b").containsKey("Cookie"));
        assertFalse(context.forUrl("https://evil.example/").containsKey("Cookie"));assertFalse(context.forUrl("https://media.example:444/").containsKey("Cookie"));
        assertFalse(context.forUrl("http://media.example/").containsKey("Authorization"));assertFalse(context.forUrl("https://media.example/").containsKey("X-Unwanted"));
    }
}
