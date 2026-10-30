package com.adownloader.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Browser-independent download engine.
 * Direct media is copied bit-for-bit. HLS is downloaded at the highest available
 * master-playlist quality and remuxed without video/audio re-encoding.
 */
public final class DownloadService extends Service {
    public static final String ACTION_DOWNLOAD = "com.adownloader.app.DOWNLOAD";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TYPE = "type";
    public static final String EXTRA_HEADERS = "headers";
    public static final String EXTRA_FILENAME = "filename";

    private static final String CHANNEL_ID = "adownloader_downloads";
    private static final AtomicInteger NEXT_ID = new AtomicInteger(4000);
    private static final int BUFFER_SIZE = 128 * 1024;
    private static final int SEGMENT_RETRIES = 3;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID,
                "Adownloader 다운로드",
                NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_DOWNLOAD.equals(intent.getAction())) return START_NOT_STICKY;
        String url = intent.getStringExtra(EXTRA_URL);
        if (url == null || url.isBlank()) return START_NOT_STICKY;

        String type = intent.getStringExtra(EXTRA_TYPE);
        if (type == null) type = MediaCandidate.MediaType.UNKNOWN.name();
        String headersJson = intent.getStringExtra(EXTRA_HEADERS);
        String requestedName = intent.getStringExtra(EXTRA_FILENAME);
        int notificationId = NEXT_ID.getAndIncrement();

        startForeground(notificationId, buildNotification("다운로드 준비 중", 0, true));
        final String finalType = type;
        executor.execute(() -> {
            try {
                Map<String, String> headers = jsonToMap(headersJson);
                Uri result;
                if (MediaCandidate.MediaType.HLS.name().equals(finalType)) {
                    result = downloadHls(url, headers, requestedName, notificationId);
                } else if (MediaCandidate.MediaType.DASH.name().equals(finalType)) {
                    throw new IllegalStateException("DASH 다운로드 엔진은 아직 연결되지 않았습니다");
                } else {
                    result = downloadDirect(url, headers, requestedName, notificationId);
                }
                notifyDone(notificationId, result);
            } catch (Throwable t) {
                notifyFailed(notificationId, t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            } finally {
                stopSelf(startId);
            }
        });
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    private Notification buildNotification(String text, int progress, boolean indeterminate) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Adownloader")
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(100, Math.max(0, Math.min(100, progress)), indeterminate)
                .build();
    }

    private void updateProgress(int id, String text, int progress, boolean indeterminate) {
        getSystemService(NotificationManager.class).notify(id, buildNotification(text, progress, indeterminate));
    }

    private void notifyDone(int id, Uri uri) {
        Intent openIntent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        PendingIntent pending = PendingIntent.getActivity(
                this,
                id,
                Intent.createChooser(openIntent, "영상 열기"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("다운로드 완료")
                .setContentText("탭해서 영상 열기")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(id, notification);
    }

    private void notifyFailed(int id, String reason) {
        String safe = reason == null ? "알 수 없는 오류" : reason;
        if (safe.length() > 120) safe = safe.substring(0, 120);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("다운로드 실패")
                .setContentText(safe)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(id, notification);
    }

    private Uri downloadDirect(String url,
                               Map<String, String> headers,
                               String requestedName,
                               int notificationId) throws Exception {
        File temp = File.createTempFile("adownloader_", ".part", getCacheDir());
        HttpURLConnection conn = null;
        try {
            conn = openConnection(url, headers);
            long total = conn.getContentLengthLong();
            String mime = contentType(conn.getContentType());
            if (mime.isBlank()) mime = "video/mp4";
            String name = requestedName != null && !requestedName.isBlank()
                    ? requestedName
                    : fileNameFromConnection(conn, url, mime);

            try (BufferedInputStream input = new BufferedInputStream(conn.getInputStream(), BUFFER_SIZE);
                 BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(temp), BUFFER_SIZE)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                long done = 0;
                int lastProgress = -1;
                while (true) {
                    int n = input.read(buffer);
                    if (n < 0) break;
                    output.write(buffer, 0, n);
                    done += n;
                    if (total > 0) {
                        int progress = (int) ((done * 100L) / total);
                        if (progress != lastProgress) {
                            lastProgress = progress;
                            updateProgress(notificationId, "다운로드 중 · " + progress + "%", progress, false);
                        }
                    }
                }
            }
            return publishVideo(temp, ensureExtension(name, mime), mime);
        } finally {
            if (conn != null) conn.disconnect();
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    private Uri downloadHls(String entryUrl,
                            Map<String, String> headers,
                            String requestedName,
                            int notificationId) throws Exception {
        updateProgress(notificationId, "HLS 목록 확인 중", 0, true);
        String masterText = fetchText(entryUrl, headers);
        String mediaUrl = chooseBestVariant(entryUrl, masterText);
        String playlistText = mediaUrl.equals(entryUrl) ? masterText : fetchText(mediaUrl, headers);
        HlsPlaylist playlist = parseMediaPlaylist(mediaUrl, playlistText);
        if (playlist.segments.isEmpty()) throw new IllegalStateException("HLS 세그먼트를 찾지 못했습니다");
        if (playlist.unsupportedEncryption) throw new IllegalStateException("DRM/SAMPLE-AES 스트림은 저장할 수 없습니다");

        boolean fragmentedMp4 = playlist.initSegment != null;
        if (!fragmentedMp4) {
            for (HlsSegment segment : playlist.segments) {
                if (pathWithoutQuery(segment.url).toLowerCase(Locale.ROOT).endsWith(".m4s")) {
                    fragmentedMp4 = true;
                    break;
                }
            }
        }

        String tempExt = fragmentedMp4 ? ".mp4" : ".ts";
        File joined = File.createTempFile("adownloader_hls_", tempExt, getCacheDir());
        try {
            try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(joined), 256 * 1024)) {
                if (playlist.initSegment != null) {
                    output.write(fetchBytesWithRetry(playlist.initSegment, headers));
                }
                for (int i = 0; i < playlist.segments.size(); i++) {
                    HlsSegment segment = playlist.segments.get(i);
                    byte[] raw = fetchBytesWithRetry(segment.url, headers);
                    byte[] bytes = segment.key == null
                            ? raw
                            : decryptAes128(raw, segment.key, segment.sequence, headers);
                    output.write(bytes);
                    int p = (int) (((i + 1L) * 100L) / playlist.segments.size());
                    updateProgress(notificationId,
                            "HLS 조각 " + (i + 1) + "/" + playlist.segments.size(),
                            p,
                            false);
                }
            }

            String baseName = sanitizeName(
                    requestedName != null && !requestedName.isBlank()
                            ? requestedName
                            : "Adownloader_" + System.currentTimeMillis());

            if (fragmentedMp4) {
                return publishVideo(joined, ensureExtension(baseName, "video/mp4"), "video/mp4");
            }

            updateProgress(notificationId, "MP4로 무손실 정리 중", 100, true);
            File remuxed = File.createTempFile("adownloader_remux_", ".mp4", getCacheDir());
            try {
                remuxToMp4(joined, remuxed);
                return publishVideo(remuxed, ensureExtension(baseName, "video/mp4"), "video/mp4");
            } catch (Throwable remuxFailure) {
                return publishVideo(joined, ensureExtension(baseName, "video/mp2t"), "video/mp2t");
            } finally {
                //noinspection ResultOfMethodCallIgnored
                remuxed.delete();
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            joined.delete();
        }
    }

    /**
     * Selects the highest source variant by resolution first, then frame rate,
     * average/peak bandwidth. This avoids choosing a lower-resolution stream
     * merely because its encoder reported a slightly larger bitrate.
     */
    private String chooseBestVariant(String baseUrl, String text) {
        if (text == null || !text.contains("#EXT-X-STREAM-INF")) return baseUrl;
        String[] lines = text.split("\\r?\\n");
        Variant best = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue;

            long bandwidth = parseLongAttribute(line, "AVERAGE-BANDWIDTH", -1);
            if (bandwidth < 0) bandwidth = parseLongAttribute(line, "BANDWIDTH", 0);
            double frameRate = parseDoubleAttribute(line, "FRAME-RATE", 0.0);
            long pixels = 0;
            Matcher resolution = Pattern.compile("(?:^|,)RESOLUTION=(\\d+)x(\\d+)", Pattern.CASE_INSENSITIVE)
                    .matcher(line.substring(line.indexOf(':') + 1));
            if (resolution.find()) {
                try {
                    pixels = Long.parseLong(resolution.group(1)) * Long.parseLong(resolution.group(2));
                } catch (NumberFormatException ignored) {
                }
            }

            String next = null;
            for (int j = i + 1; j < lines.length; j++) {
                String candidate = lines[j].trim();
                if (candidate.isEmpty()) continue;
                if (candidate.startsWith("#")) continue;
                next = candidate;
                break;
            }
            if (next == null) continue;

            Variant variant = new Variant(resolve(baseUrl, next), pixels, frameRate, bandwidth);
            if (best == null || variant.isBetterThan(best)) best = variant;
        }
        return best == null ? baseUrl : best.url;
    }

    private long parseLongAttribute(String line, String name, long fallback) {
        Matcher matcher = Pattern.compile("(?:^|,)" + Pattern.quote(name) + "=(\\d+)", Pattern.CASE_INSENSITIVE)
                .matcher(line.substring(line.indexOf(':') + 1));
        if (!matcher.find()) return fallback;
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private double parseDoubleAttribute(String line, String name, double fallback) {
        Matcher matcher = Pattern.compile("(?:^|,)" + Pattern.quote(name) + "=([0-9.]+)", Pattern.CASE_INSENSITIVE)
                .matcher(line.substring(line.indexOf(':') + 1));
        if (!matcher.find()) return fallback;
        try {
            return Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private HlsPlaylist parseMediaPlaylist(String baseUrl, String text) {
        long mediaSequence = 0;
        Matcher seqMatcher = Pattern.compile("#EXT-X-MEDIA-SEQUENCE:(\\d+)").matcher(text);
        if (seqMatcher.find()) {
            try { mediaSequence = Long.parseLong(seqMatcher.group(1)); } catch (NumberFormatException ignored) {}
        }

        HlsKey currentKey = null;
        boolean unsupported = false;
        String init = null;
        List<HlsSegment> segments = new ArrayList<>();

        String[] lines = text.split("\\r?\\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-MAP:")) {
                String uri = quotedAttribute(line, "URI");
                if (uri != null) init = resolve(baseUrl, uri);
            } else if (line.startsWith("#EXT-X-KEY:")) {
                String method = simpleAttribute(line, "METHOD");
                if (method == null || method.equalsIgnoreCase("NONE")) {
                    currentKey = null;
                } else if (method.equalsIgnoreCase("AES-128")) {
                    String uri = quotedAttribute(line, "URI");
                    String iv = simpleAttribute(line, "IV");
                    if (uri != null) currentKey = new HlsKey(resolve(baseUrl, uri), iv);
                } else {
                    unsupported = true;
                }
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                segments.add(new HlsSegment(resolve(baseUrl, line), currentKey, mediaSequence));
                mediaSequence++;
            }
        }
        return new HlsPlaylist(init, segments, unsupported);
    }

    private String quotedAttribute(String line, String name) {
        Matcher matcher = Pattern.compile(Pattern.quote(name) + "=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String simpleAttribute(String line, String name) {
        Matcher matcher = Pattern.compile(Pattern.quote(name) + "=([^,]+)", Pattern.CASE_INSENSITIVE).matcher(line);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private byte[] decryptAes128(byte[] data,
                                 HlsKey key,
                                 long sequence,
                                 Map<String, String> headers) throws Exception {
        byte[] keyBytes = fetchBytesWithRetry(key.uri, headers);
        if (keyBytes.length != 16) throw new IllegalStateException("AES-128 키 길이가 올바르지 않습니다");
        byte[] iv = key.ivHex != null && !key.ivHex.isBlank()
                ? hexTo16Bytes(key.ivHex)
                : sequenceIv(sequence);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(data);
    }

    private byte[] sequenceIv(long sequence) {
        byte[] bytes = new byte[16];
        long value = sequence;
        for (int i = 15; i >= 0; i--) {
            bytes[i] = (byte) (value & 0xFF);
            value >>>= 8;
        }
        return bytes;
    }

    private byte[] hexTo16Bytes(String value) {
        String clean = value.replaceFirst("^(?i)0x", "");
        clean = "00000000000000000000000000000000" + clean;
        clean = clean.substring(clean.length() - 32);
        byte[] result = new byte[16];
        for (int i = 0; i < 16; i++) {
            result[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    private String fetchText(String url, Map<String, String> headers) throws Exception {
        return new String(fetchBytesWithRetry(url, headers), StandardCharsets.UTF_8);
    }

    private byte[] fetchBytesWithRetry(String url, Map<String, String> headers) throws Exception {
        Throwable last = null;
        for (int attempt = 1; attempt <= SEGMENT_RETRIES; attempt++) {
            try {
                return fetchBytesOnce(url, headers);
            } catch (Throwable t) {
                last = t;
                if (attempt < SEGMENT_RETRIES) {
                    try { Thread.sleep(250L * attempt); } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw interrupted;
                    }
                }
            }
        }
        if (last instanceof Exception) throw (Exception) last;
        throw new IllegalStateException(last == null ? "네트워크 오류" : last.getMessage(), last);
    }

    private byte[] fetchBytesOnce(String url, Map<String, String> headers) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = openConnection(url, headers);
            try (InputStream input = new BufferedInputStream(conn.getInputStream(), BUFFER_SIZE);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int n;
                while ((n = input.read(buffer)) >= 0) output.write(buffer, 0, n);
                return output.toByteArray();
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private HttpURLConnection openConnection(String url, Map<String, String> headers) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Encoding", "identity");
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isBlank()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
        }
        int code = conn.getResponseCode();
        if (code < 200 || code > 299) {
            conn.disconnect();
            throw new IllegalStateException("HTTP " + code);
        }
        return conn;
    }

    private String resolve(String base, String child) {
        return URI.create(base).resolve(child).toString();
    }

    private String contentType(String raw) {
        if (raw == null) return "";
        int semicolon = raw.indexOf(';');
        return semicolon >= 0 ? raw.substring(0, semicolon).trim() : raw.trim();
    }

    private String fileNameFromConnection(HttpURLConnection conn, String url, String mime) {
        String disposition = conn.getHeaderField("Content-Disposition");
        if (disposition != null) {
            Matcher matcher = Pattern.compile("filename\\*?=(?:UTF-8''|\")?([^\";]+)", Pattern.CASE_INSENSITIVE)
                    .matcher(disposition);
            if (matcher.find()) {
                try {
                    return sanitizeName(URLDecoder.decode(matcher.group(1).trim(), StandardCharsets.UTF_8));
                } catch (Throwable ignored) {
                }
            }
        }
        try {
            String path = URI.create(url).getPath();
            if (path != null) {
                String tail = path.substring(path.lastIndexOf('/') + 1);
                if (tail.contains(".")) return sanitizeName(tail);
            }
        } catch (Throwable ignored) {
        }
        return sanitizeName("Adownloader_" + System.currentTimeMillis() + extensionForMime(mime));
    }

    private String sanitizeName(String name) {
        String clean = name == null ? "Adownloader" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
        return clean.length() > 180 ? clean.substring(0, 180) : clean;
    }

    private String ensureExtension(String name, String mime) {
        String clean = sanitizeName(name);
        String ext = extensionForMime(mime);
        if (clean.toLowerCase(Locale.ROOT).endsWith(ext)) return clean;
        int dot = clean.lastIndexOf('.');
        if (dot > 0) clean = clean.substring(0, dot);
        return clean + ext;
    }

    private String extensionForMime(String mime) {
        String lower = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (lower.contains("webm")) return ".webm";
        if (lower.contains("mp2t")) return ".ts";
        if (lower.contains("quicktime")) return ".mov";
        if (lower.startsWith("audio/")) return ".m4a";
        return ".mp4";
    }

    private Uri publishVideo(File source, String fileName, String mime) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Video.Media.MIME_TYPE, mime);
        values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Adownloader");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);

        Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("저장 위치를 만들 수 없습니다");
        try {
            try (FileInputStream input = new FileInputStream(source);
                 java.io.OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
                if (output == null) throw new IllegalStateException("저장 파일을 열 수 없습니다");
                byte[] buffer = new byte[256 * 1024];
                int n;
                while ((n = input.read(buffer)) >= 0) output.write(buffer, 0, n);
            }
            values.clear();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
            return uri;
        } catch (Throwable t) {
            getContentResolver().delete(uri, null, null);
            if (t instanceof Exception) throw (Exception) t;
            throw new IllegalStateException(t);
        }
    }

    private void remuxToMp4(File input, File output) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(input.getAbsolutePath());
        MediaMuxer muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        boolean started = false;
        try {
            Map<Integer, Integer> trackMap = new HashMap<>();
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/"))) {
                    extractor.selectTrack(i);
                    trackMap.put(i, muxer.addTrack(format));
                }
            }
            if (trackMap.isEmpty()) throw new IllegalStateException("미디어 트랙을 찾지 못했습니다");
            muxer.start();
            started = true;

            ByteBuffer buffer = ByteBuffer.allocate(2 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                int track = extractor.getSampleTrackIndex();
                if (track < 0) break;
                Integer muxTrack = trackMap.get(track);
                if (muxTrack == null) {
                    extractor.advance();
                    continue;
                }
                buffer.clear();
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) break;
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = extractor.getSampleTime();
                info.flags = extractor.getSampleFlags();
                muxer.writeSampleData(muxTrack, buffer, info);
                extractor.advance();
            }
        } finally {
            if (started) {
                try { muxer.stop(); } catch (Throwable ignored) {}
            }
            muxer.release();
            extractor.release();
        }
    }

    private Map<String, String> jsonToMap(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return result;
        try {
            JSONObject obj = new JSONObject(json);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = obj.optString(key, "");
                if (!value.isBlank()) result.put(key, value);
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    private String pathWithoutQuery(String url) {
        try {
            String path = URI.create(url).getPath();
            return path == null ? url : path;
        } catch (Throwable ignored) {
            return url;
        }
    }

    private static final class Variant {
        final String url;
        final long pixels;
        final double frameRate;
        final long bandwidth;

        Variant(String url, long pixels, double frameRate, long bandwidth) {
            this.url = url;
            this.pixels = pixels;
            this.frameRate = frameRate;
            this.bandwidth = bandwidth;
        }

        boolean isBetterThan(Variant other) {
            if (pixels != other.pixels) return pixels > other.pixels;
            if (Double.compare(frameRate, other.frameRate) != 0) return frameRate > other.frameRate;
            return bandwidth > other.bandwidth;
        }
    }

    private static final class HlsKey {
        final String uri;
        final String ivHex;
        HlsKey(String uri, String ivHex) {
            this.uri = uri;
            this.ivHex = ivHex;
        }
    }

    private static final class HlsSegment {
        final String url;
        final HlsKey key;
        final long sequence;
        HlsSegment(String url, HlsKey key, long sequence) {
            this.url = url;
            this.key = key;
            this.sequence = sequence;
        }
    }

    private static final class HlsPlaylist {
        final String initSegment;
        final List<HlsSegment> segments;
        final boolean unsupportedEncryption;
        HlsPlaylist(String initSegment, List<HlsSegment> segments, boolean unsupportedEncryption) {
            this.initSegment = initSegment;
            this.segments = segments;
            this.unsupportedEncryption = unsupportedEncryption;
        }
    }
}
