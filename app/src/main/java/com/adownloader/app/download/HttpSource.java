package com.adownloader.app.download;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Bounded manifest/segment requests, checked byte counts, explicit safe redirects. */
public final class HttpSource {
    private final RequestContext context;
    public HttpSource(String source, Map<String, String> headers) { context = new RequestContext(source, headers); }
    public HttpURLConnection open(String url, long offset, long length) throws Exception {
        String current = url;
        for (int hop = 0; hop < 8; hop++) {
            Map<String,String> headers = context.forUrl(current);
            HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(45_000);
            connection.setRequestProperty("Accept-Encoding", "identity");
            headers.forEach(connection::setRequestProperty);
            if (length > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-" + (offset + length - 1));
            int code = connection.getResponseCode();
            if (code >= 300 && code <= 399) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new IOException("리다이렉트 주소 없음");
                String next = URI.create(current).resolve(location).toString();
                if (current.startsWith("https:") && !next.startsWith("https:")) throw new IOException("안전하지 않은 리다이렉트");
                current = next;
                continue;
            }
            if (code < 200 || code > 299 || (length > 0 && code != 206) || (length <= 0 && code == 206)) {
                connection.disconnect();
                throw new IOException("HTTP " + code + " · 완전한 응답을 받지 못했습니다");
            }
            if (length > 0) {
                String range = connection.getHeaderField("Content-Range");
                if (range == null || !range.startsWith("bytes " + offset + "-" + (offset + length - 1) + "/")) {
                    connection.disconnect();
                    throw new IOException("세그먼트 범위 불일치");
                }
            }
            return connection;
        }
        throw new IOException("리다이렉트 횟수 초과");
    }
    public byte[] bytes(String url, long offset, long length, int limit) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpURLConnection connection = null;
            try {
                connection = open(url, offset, length);
                long expected = connection.getContentLengthLong();
                if (expected > limit || length > limit) throw new IOException("세그먼트 크기 제한 초과");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = connection.getInputStream()) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("다운로드 중단");
                        if (out.size() + (long) count > limit) throw new IOException("응답 크기 제한 초과");
                        out.write(buffer, 0, count);
                    }
                }
                byte[] data = out.toByteArray();
                if (data.length == 0 || (expected >= 0 && data.length != expected) || (length > 0 && data.length != length))
                    throw new IOException("누락되거나 잘린 세그먼트");
                return data;
            } catch (Exception error) {
                last = error;
                if (attempt < 2) Thread.sleep(300L * (attempt + 1));
            } finally { if (connection != null) connection.disconnect(); }
        }
        throw last;
    }
    public static final class Document {
        public final String url,text;
        Document(String url,String text){this.url=url;this.text=text;}
    }
    public Document document(String url) throws Exception {
        HttpURLConnection connection=open(url,0,-1);
        try {String effective=connection.getURL().toString();return new Document(effective,new String(bytes(effective,0,-1,8*1024*1024),StandardCharsets.UTF_8));}
        finally{connection.disconnect();}
    }
    public void copy(String url, File file, DownloadEngine.Progress progress) throws Exception {
        HttpURLConnection connection = open(url, 0, -1);
        try (InputStream in = connection.getInputStream(); OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
            long total = connection.getContentLengthLong(), done = 0;
            byte[] buffer = new byte[128 * 1024];
            int n, previous = -1;
            while ((n = in.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("다운로드 중단");
                out.write(buffer, 0, n); done += n;
                int percent = total > 0 ? (int)(done * 100 / total) : 0;
                if (percent != previous) { progress.update("원본 저장 중", percent); previous = percent; }
            }
            if (done == 0 || (total >= 0 && done != total)) throw new IOException("파일이 완전히 내려받아지지 않았습니다");
        } finally { connection.disconnect(); }
    }
}
