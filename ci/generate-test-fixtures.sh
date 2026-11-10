#!/usr/bin/env bash
set -euo pipefail
# Synthetic test-only media. FFmpeg is never bundled with or invoked by the app.
DEST=app/src/androidTest/assets/fixtures
mkdir -p "$DEST"
ffmpeg -y -v error -f lavfi -i 'testsrc2=size=160x90:rate=24' -f lavfi -i 'sine=frequency=440:sample_rate=48000' -t 3 -c:v libx264 -preset ultrafast -g 24 -bf 0 -pix_fmt yuv420p -c:a aac -b:a 64k -movflags +faststart "$DEST/direct.mp4"
ffmpeg -y -v error -i "$DEST/direct.mp4" -map 0 -c copy -hls_time 1 -hls_playlist_type vod "$DEST/playlist.m3u8"
ffmpeg -y -v error -i "$DEST/direct.mp4" -map 0:v -c copy -hls_time 1 -hls_playlist_type vod "$DEST/video.m3u8"
ffmpeg -y -v error -i "$DEST/direct.mp4" -map 0:a -c copy -hls_time 1 -hls_playlist_type vod "$DEST/audio.m3u8"
printf '#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="default",DEFAULT=YES,URI="audio.m3u8"\n#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=160x90,FRAME-RATE=24,AUDIO="audio"\nvideo.m3u8\n' > "$DEST/master.m3u8"
ffmpeg -y -v error -i "$DEST/direct.mp4" -map 0:v -map 0:a -c copy -seg_duration 1 -use_template 1 -use_timeline 1 -f dash "$DEST/manifest.mpd"

mkdir -p "$DEST/stress"
ffmpeg -y -v error -f lavfi -i 'color=c=pink:size=64x64:rate=1' -t 1000 -an -c:v libx264 -preset ultrafast -g 1 -bf 0 -pix_fmt yuv420p -hls_time 1 -hls_playlist_type vod -hls_segment_type fmp4 -hls_segment_filename "$DEST/stress/part%04d.m4s" "$DEST/stress/playlist.m3u8"

mkdir -p "$DEST/fmp4"
ffmpeg -y -v error -i "$DEST/direct.mp4" -map 0 -c copy -hls_time 1 -hls_playlist_type vod -hls_segment_type fmp4 "$DEST/fmp4/playlist.m3u8"

# HLS discontinuity: two independently encoded clips with identical codec settings (their
# timestamps restart), joined by #EXT-X-DISCONTINUITY. A third clip with another resolution must be
# refused rather than produce a broken file.
mkdir -p "$DEST/disc"
ffmpeg -y -v error -f lavfi -i 'testsrc=size=160x90:rate=24' -f lavfi -i 'sine=frequency=660:sample_rate=48000' -t 2 -c:v libx264 -preset ultrafast -g 24 -bf 0 -pix_fmt yuv420p -c:a aac -b:a 64k "$DEST/disc/second.mp4"
ffmpeg -y -v error -f lavfi -i 'testsrc=size=320x180:rate=24' -f lavfi -i 'sine=frequency=880:sample_rate=48000' -t 2 -c:v libx264 -preset ultrafast -g 24 -bf 0 -pix_fmt yuv420p -c:a aac -b:a 64k "$DEST/disc/other.mp4"
ffmpeg -y -v error -i "$DEST/direct.mp4" -map 0 -c copy -hls_time 1 -hls_playlist_type vod -hls_segment_filename "$DEST/disc/a%d.ts" "$DEST/disc/a.m3u8"
ffmpeg -y -v error -i "$DEST/disc/second.mp4" -map 0 -c copy -hls_time 1 -hls_playlist_type vod -hls_segment_filename "$DEST/disc/b%d.ts" "$DEST/disc/b.m3u8"
ffmpeg -y -v error -i "$DEST/disc/other.mp4" -map 0 -c copy -hls_time 1 -hls_playlist_type vod -hls_segment_filename "$DEST/disc/c%d.ts" "$DEST/disc/c.m3u8"
media_lines() { grep -E '^(#EXTINF|[^#])' "$1"; }
{ printf '#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-PLAYLIST-TYPE:VOD\n'; media_lines "$DEST/disc/a.m3u8"; echo '#EXT-X-DISCONTINUITY'; media_lines "$DEST/disc/b.m3u8"; echo '#EXT-X-ENDLIST'; } > "$DEST/disc/joined.m3u8"
{ printf '#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-PLAYLIST-TYPE:VOD\n'; media_lines "$DEST/disc/a.m3u8"; echo '#EXT-X-DISCONTINUITY'; media_lines "$DEST/disc/c.m3u8"; echo '#EXT-X-ENDLIST'; } > "$DEST/disc/mismatch.m3u8"
rm -f "$DEST/disc/second.mp4" "$DEST/disc/other.mp4"
