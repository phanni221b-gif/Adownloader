package com.adownloader.app.download;

import java.io.*;
import java.util.*;

/** Persistence adapter. The engine never depends on Android services or databases. */
public interface EngineCheckpoint {
  List<ManifestParser.Track> loadPlan() throws Exception;

  default void sourceMetadata(String disposition) throws Exception {}

  void savePlan(List<ManifestParser.Track> tracks) throws Exception;

  boolean verified(File file, int track, int part) throws Exception;

  void saved(File file, int track, int part) throws Exception;

  void copy(HttpSource source, String url, File file, TransferControl control) throws Exception;
}
