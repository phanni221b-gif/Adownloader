package com.adownloader.app.download;

import java.io.*;
import java.util.*;

/** Persistence adapter. The engine never depends on Android services or databases. */
public interface EngineCheckpoint {
  List<ManifestParser.Track> loadPlan() throws Exception;

  default String cookieState() throws Exception {
    return "";
  }

  default void cookieState(String state) throws Exception {}

  default boolean previouslyAccepted() {
    return false;
  }

  default long committedSegmentBytes() {
    return 0;
  }

  default DownloadEngine.Result loadDestinationFinal(FileDescriptor fd, TransferControl control)
      throws Exception {
    return null;
  }

  default void saveDestinationFinal(
      FileDescriptor fd, String mime, String extension, TransferControl control) throws Exception {}

  default void sourceMetadata(String disposition) throws Exception {}

  default DownloadEngine.Result loadFinal() throws Exception {
    return null;
  }

  default void saveFinal(File file, String mime, String extension) throws Exception {}

  default void copy(
      HttpSource source, String url, FileDescriptor descriptor, TransferControl control)
      throws Exception {
    throw new DownloadError(DownloadError.Category.STORAGE);
  }

  void savePlan(List<ManifestParser.Track> tracks) throws Exception;

  boolean verified(File file, int track, int part) throws Exception;

  void saved(File file, int track, int part) throws Exception;

  void copy(HttpSource source, String url, File file, TransferControl control) throws Exception;
}
