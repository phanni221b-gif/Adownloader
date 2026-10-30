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
