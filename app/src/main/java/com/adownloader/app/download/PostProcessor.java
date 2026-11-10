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

  public static Inspection inspect(
      File file, double expectedSeconds, boolean needVideo, boolean needAudio) throws Exception {
    return inspect(
        extractor -> extractor.setDataSource(file.getAbsolutePath()),
        expectedSeconds,
        needVideo,
        needAudio);
  }

  public static Inspection inspect(
      java.io.FileDescriptor descriptor,
      double expectedSeconds,
      boolean needVideo,
      boolean needAudio)
      throws Exception {
    return inspect(
        extractor -> extractor.setDataSource(descriptor), expectedSeconds, needVideo, needAudio);
  }

  private interface Source {
    void apply(MediaExtractor extractor) throws Exception;
  }

  private static Inspection inspect(
      Source source, double expectedSeconds, boolean needVideo, boolean needAudio)
      throws Exception {
    MediaExtractor extractor = new MediaExtractor();
    try {
      source.apply(extractor);
      if (extractor.getPsshInfo() != null && !extractor.getPsshInfo().isEmpty())
        throw new DownloadError(DownloadError.Category.DRM);
      Inspection result = new Inspection();
      for (int i = 0; i < extractor.getTrackCount(); i++) {
        MediaFormat format = extractor.getTrackFormat(i);
        String mime = format.getString(MediaFormat.KEY_MIME);
        if (mime == null || !(mime.startsWith("video/") || mime.startsWith("audio/"))) continue;
        if (mime.startsWith("video/")) result.video = true;
        else result.audio = true;
        long duration =
            format.containsKey(MediaFormat.KEY_DURATION)
                ? format.getLong(MediaFormat.KEY_DURATION)
                : 0;
        if (duration <= 0) throw new IOException("미디어 길이를 확인할 수 없습니다");
        if (expectedSeconds > 0
            && Math.abs(duration / 1_000_000.0 - expectedSeconds)
                > Math.max(2.0, expectedSeconds * 0.015))
          throw new IOException("트랙 길이가 원본 목록과 일치하지 않습니다");
        result.durationUs = Math.max(result.durationUs, duration);
        extractor.selectTrack(i);
        extractor.seekTo(duration * 3 / 4, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        if (extractor.getSampleTime() < 0 || extractor.getSampleSize() <= 0)
          throw new IOException("미디어 탐색 검증 실패");
        long sampleSize = extractor.getSampleSize();
        if (sampleSize > 64 * 1024 * 1024
            || extractor.readSampleData(ByteBuffer.allocate((int) sampleSize), 0) != sampleSize)
          throw new IOException("미디어 샘플이 잘렸습니다");
        if ((extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0)
          throw new DownloadError(DownloadError.Category.DRM);
        extractor.unselectTrack(i);
      }
      if (result.durationUs <= 0 || (needVideo && !result.video) || (needAudio && !result.audio))
        throw new IOException("필수 미디어 트랙 누락");
      return result;
    } finally {
      extractor.release();
    }
  }

  public static void mux(List<File> files, File output) throws Exception {
    mux(files, output, new TransferControl());
  }

  public static void mux(List<File> files, File output, TransferControl control) throws Exception {
    List<List<Source>> sources = new ArrayList<>();
    for (File file : files)
      sources.add(List.of(extractor -> extractor.setDataSource(file.getAbsolutePath())));
    muxPrepared(
        sources,
        () -> new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4),
        control);
  }

  /** Each input is an ordered list of timeline groups (HLS discontinuity groups). */
  public static void muxSources(
      List<List<android.media.MediaDataSource>> inputs, File output, TransferControl control)
      throws Exception {
    muxPrepared(
        groups(inputs),
        () -> new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4),
        control);
  }

  public static void muxSources(
      List<List<android.media.MediaDataSource>> inputs,
      java.io.FileDescriptor output,
      TransferControl control)
      throws Exception {
    muxPrepared(
        groups(inputs),
        () -> new MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4),
        control);
  }

  private static List<List<Source>> groups(List<List<android.media.MediaDataSource>> inputs) {
    List<List<Source>> sources = new ArrayList<>();
    for (var groups : inputs) {
      List<Source> list = new ArrayList<>();
      for (var input : groups) list.add(extractor -> extractor.setDataSource(input));
      sources.add(list);
    }
    return sources;
  }

  private interface MuxerFactory {
    MediaMuxer create() throws Exception;
  }

  public static void muxDescriptor(
      java.io.FileDescriptor input, File output, TransferControl control) throws Exception {
    muxPrepared(
        List.of(List.of(extractor -> extractor.setDataSource(input))),
        () -> new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4),
        control);
  }

  /**
   * One logical input (for example the video playlist) read group by group. A later group is
   * accepted only when its tracks match the first group's formats exactly; its timestamps are then
   * shifted to start where the previous group ended. Samples are copied, never re-encoded.
   */
  private static final class Input {
    final List<Source> groups;
    final boolean skipAudio;
    int group = -1;
    MediaExtractor extractor;
    final List<String> keys = new ArrayList<>(); // stable per-kind track keys of the first group
    final Map<String, Integer> muxerTrack = new HashMap<>();
    final Map<String, MediaFormat> formats = new HashMap<>();
    Map<Integer, String> keyByTrack = new HashMap<>();
    long offset;
    final Map<Integer, Long> lastTime = new HashMap<>(), lastStep = new HashMap<>();

    Input(List<Source> groups, boolean skipAudio) {
      this.groups = groups;
      this.skipAudio = skipAudio;
    }

    /** Opens the next group; returns false when every group is exhausted. */
    boolean open(MediaMuxer muxer, boolean registering) throws Exception {
      if (extractor != null) {
        extractor.release();
        extractor = null;
      }
      if (++group >= groups.size()) return false;
      MediaExtractor next = new MediaExtractor();
      extractor = next;
      groups.get(group).apply(next);
      if (next.getPsshInfo() != null && !next.getPsshInfo().isEmpty())
        throw new DownloadError(DownloadError.Category.DRM);
      Map<Integer, String> mapping = new HashMap<>();
      Map<String, Integer> perKind = new HashMap<>();
      for (int t = 0; t < next.getTrackCount(); t++) {
        MediaFormat format = next.getTrackFormat(t);
        String mime = format.getString(MediaFormat.KEY_MIME);
        if (mime == null || !(mime.startsWith("video/") || mime.startsWith("audio/"))) continue;
        if (skipAudio && mime.startsWith("audio/")) continue;
        String kind = mime.startsWith("video/") ? "video" : "audio";
        int n = perKind.merge(kind, 1, Integer::sum);
        String key = kind + n;
        if (registering) {
          try {
            muxerTrack.put(key, muxer.addTrack(format));
          } catch (IllegalArgumentException unsupported) {
            throw new DownloadError(DownloadError.Category.UNSUPPORTED_CODEC);
          }
          formats.put(key, format);
          keys.add(key);
        } else if (!formats.containsKey(key) || !compatible(formats.get(key), format))
          throw new DownloadError(DownloadError.Category.HLS_DISCONTINUITY);
        mapping.put(t, key);
        next.selectTrack(t);
      }
      if (mapping.isEmpty()) throw new DownloadError(DownloadError.Category.UNSUPPORTED_FORMAT);
      if (!registering && mapping.size() != keys.size())
        throw new DownloadError(DownloadError.Category.HLS_DISCONTINUITY);
      keyByTrack = mapping;
      if (!registering) {
        long first = next.getSampleTime();
        if (first < 0) return open(muxer, false); // empty group
        long end = Long.MIN_VALUE;
        for (var e : lastTime.entrySet())
          end = Math.max(end, e.getValue() + Math.max(1, lastStep.getOrDefault(e.getKey(), 0L)));
        if (end != Long.MIN_VALUE) offset = end - first;
      }
      return true;
    }

    long time() {
      return extractor == null || extractor.getSampleTrackIndex() < 0
          ? Long.MAX_VALUE
          : extractor.getSampleTime() + offset;
    }

    int muxerIndex() {
      return muxerTrack.get(keyByTrack.get(extractor.getSampleTrackIndex()));
    }

    void wrote(int track, long adjusted) {
      Long previous = lastTime.get(track);
      if (previous != null && adjusted > previous) lastStep.put(track, adjusted - previous);
      if (previous == null || adjusted > previous) lastTime.put(track, adjusted);
    }

    void release() {
      if (extractor != null) extractor.release();
      extractor = null;
    }
  }

  static boolean compatible(MediaFormat a, MediaFormat b) {
    if (!Objects.equals(a.getString(MediaFormat.KEY_MIME), b.getString(MediaFormat.KEY_MIME)))
      return false;
    for (String key :
        List.of(
            MediaFormat.KEY_WIDTH,
            MediaFormat.KEY_HEIGHT,
            MediaFormat.KEY_SAMPLE_RATE,
            MediaFormat.KEY_CHANNEL_COUNT))
      if (a.containsKey(key) != b.containsKey(key)
          || (a.containsKey(key) && a.getInteger(key) != b.getInteger(key))) return false;
    for (String csd : List.of("csd-0", "csd-1", "csd-2")) {
      ByteBuffer x = a.containsKey(csd) ? a.getByteBuffer(csd) : null,
          y = b.containsKey(csd) ? b.getByteBuffer(csd) : null;
      if (x == null && y == null) continue;
      if (x == null || y == null || !x.duplicate().rewind().equals(y.duplicate().rewind()))
        return false;
    }
    return true;
  }

  private static void muxPrepared(
      List<List<Source>> sources, MuxerFactory factory, TransferControl control) throws Exception {
    control.check();
    List<Input> inputs = new ArrayList<>();
    MediaMuxer muxer = null;
    boolean started = false;
    try {
      muxer = factory.create();
      long maxInput = 4 * 1024 * 1024;
      for (int i = 0; i < sources.size(); i++) {
        // If there is a separate audio source, avoid duplicate audio from the video source.
        Input input = new Input(sources.get(i), sources.size() > 1 && i == 0);
        inputs.add(input);
        if (!input.open(muxer, true))
          throw new DownloadError(DownloadError.Category.UNSUPPORTED_FORMAT);
        for (MediaFormat format : input.formats.values())
          if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
            maxInput = Math.max(maxInput, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
      }
      if (maxInput > 64 * 1024 * 1024)
        throw new DownloadError(DownloadError.Category.UNSUPPORTED_FORMAT);
      ByteBuffer buffer = ByteBuffer.allocateDirect((int) maxInput);
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      long firstTime = Long.MAX_VALUE;
      for (Input input : inputs) firstTime = Math.min(firstTime, input.time());
      if (firstTime == Long.MAX_VALUE)
        throw new DownloadError(DownloadError.Category.UNSUPPORTED_FORMAT);
      muxer.start();
      started = true;
      while (true) {
        control.check();
        Input selected = null;
        long earliest = Long.MAX_VALUE;
        for (Input candidate : inputs) {
          long time = candidate.time();
          while (time == Long.MAX_VALUE && candidate.extractor != null) {
            if (!candidate.open(muxer, false)) break;
            time = candidate.time();
          }
          if (time < earliest) {
            selected = candidate;
            earliest = time;
          }
        }
        if (selected == null) break;
        MediaExtractor source = selected.extractor;
        int flags = source.getSampleFlags();
        if ((flags & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0)
          throw new DownloadError(DownloadError.Category.DRM);
        long size = source.getSampleSize();
        if (size > buffer.capacity()) {
          if (size > 64 * 1024 * 1024)
            throw new DownloadError(DownloadError.Category.UNSUPPORTED_FORMAT);
          buffer = ByteBuffer.allocateDirect((int) size);
        }
        buffer.clear();
        int count = source.readSampleData(buffer, 0);
        if (count < 0) throw new IOException("미디어 샘플 읽기 실패");
        int track = selected.muxerIndex();
        info.set(
            0,
            count,
            Math.max(0, earliest - firstTime),
            (flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
        muxer.writeSampleData(track, buffer, info);
        selected.wrote(track, earliest);
        source.advance();
      }
      muxer.stop();
      started = false;
    } finally {
      if (muxer != null) {
        if (started)
          try {
            muxer.stop();
          } catch (Exception ignored) {
          }
        muxer.release();
      }
      for (Input input : inputs) input.release();
    }
  }
}
