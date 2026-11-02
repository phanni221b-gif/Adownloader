package com.adownloader.app.diagnostics;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.net.*;
import android.os.Build;
import com.adownloader.app.BuildConfig;
import com.adownloader.app.download.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Private, bounded failure-only persistence. This class has no outbound network capability. */
public final class FailureJournal {
  public static final int MAX_REPORTS = 100, MAX_BYTES = 10 * 1024 * 1024, MAX_REPORT_BYTES = 65536;
  private static FailureJournal instance;

  public static synchronized FailureJournal get(Context context) {
    if (instance == null) instance = new FailureJournal(context);
    return instance;
  }

  private final Context context;
  private final ExecutorService io =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "failure-journal");
            t.setDaemon(true);
            return t;
          });
  private final Database helper;

  private FailureJournal(Context raw) {
    context = raw.getApplicationContext();
    helper = new Database(context);
  }

  private boolean initialized;

  public synchronized void install() {
    FailureDiagnostics.install(this::record);
    if (!initialized) {
      initialized = true;
      io.execute(this::cleanupExports);
    }
  }

  public void record(Map<String, Object> report) {
    Map<String, Object> safe = FailureDiagnostics.sanitize(report);
    safe.put("network", route(context));
    io.execute(
        () -> {
          try {
            persist(safe);
          } catch (Exception ignored) {
          }
        });
  }

  public Future<?> barrier() {
    return io.submit(() -> {});
  }

  private static final class Database extends SQLiteOpenHelper {
    Database(Context c) {
      super(c, "failure_journal.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase d) {
      d.execSQL(
          "CREATE TABLE reports(id TEXT PRIMARY KEY,job INTEGER NOT NULL,stamp TEXT NOT"
              + " NULL,fingerprint TEXT NOT NULL,payload TEXT NOT NULL,bytes INTEGER NOT NULL)");
      d.execSQL("CREATE INDEX reports_job ON reports(job,stamp)");
      d.execSQL(
          "CREATE TABLE aggregate(fingerprint TEXT PRIMARY KEY,occurrences INTEGER NOT"
              + " NULL,firstSeen TEXT NOT NULL,lastSeen TEXT NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase d, int old, int next) {
      throw new IllegalStateException("UNSUPPORTED_DIAGNOSTIC_SCHEMA");
    }
  }

  public static Map<String, Object> route(Context context) {
    Map<String, Object> out = new LinkedHashMap<>();
    try {
      ConnectivityManager m = context.getSystemService(ConnectivityManager.class);
      NetworkCapabilities c = m.getNetworkCapabilities(m.getActiveNetwork());
      out.put("available", c != null);
      if (c != null) {
        out.put("vpnTransport", c.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
        out.put("validated", c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
        out.put("metered", m.isActiveNetworkMetered());
      }
      out.put("proxyConfigured", m.getDefaultProxy() != null);
    } catch (Exception ignored) {
    }
    return out;
  }

  private void environment(Map<String, Object> report) {
    report.put(
        "app",
        Map.of(
            "versionName",
            BuildConfig.VERSION_NAME,
            "versionCode",
            BuildConfig.VERSION_CODE,
            "commit",
            BuildConfig.COMMIT_SHA));
    report.put(
        "device",
        Map.of(
            "android",
            Build.VERSION.RELEASE,
            "api",
            Build.VERSION.SDK_INT,
            "model",
            Build.MANUFACTURER + " " + Build.MODEL,
            "abi",
            Build.SUPPORTED_ABIS.length == 0 ? "UNKNOWN" : Build.SUPPORTED_ABIS[0],
            "geckoVersion",
            BuildConfig.GECKO_VERSION));
    if (!report.containsKey("network")) report.put("network", route(context));
    var provider = MediaNetworkSession.provider();
    if (provider != null) report.put("manifestEvidence", provider.captureDiagnostics());
    report.put(
        "privacy",
        Map.of(
            "containsRawCookies",
            false,
            "containsAuthorizationValues",
            false,
            "containsUrlQuery",
            false,
            "safeForDiagnosticSharing",
            true));
  }

  private void persist(Map<String, Object> report) throws Exception {
    environment(report);
    report = FailureDiagnostics.sanitize(report);
    String fingerprint = FailureDiagnostics.fingerprint(report),
        id = (String) report.get("failureId"),
        stamp = (String) report.get("timestamp");
    if (id == null || id.isEmpty()) return;
    report.put("fingerprint", fingerprint);
    String payload = new JSONObject(report).toString();
    @SuppressWarnings("unchecked")
    List<Object> events = (List<Object>) report.get("eventHistory");
    while (payload.getBytes(StandardCharsets.UTF_8).length > MAX_REPORT_BYTES
        && events != null
        && !events.isEmpty()) {
      events.remove(0);
      payload = new JSONObject(report).toString();
    }
    int bytes = payload.getBytes(StandardCharsets.UTF_8).length;
    if (bytes > MAX_REPORT_BYTES) return;
    SQLiteDatabase d = helper.getWritableDatabase();
    d.beginTransaction();
    try {
      ContentValues row = new ContentValues();
      row.put("id", id);
      row.put("job", ((Number) report.getOrDefault("jobId", 0)).longValue());
      row.put("stamp", stamp);
      row.put("fingerprint", fingerprint);
      row.put("payload", payload);
      row.put("bytes", bytes);
      d.insertOrThrow("reports", null, row);
      d.execSQL(
          "INSERT OR IGNORE INTO aggregate VALUES(?,0,?,?)",
          new Object[] {fingerprint, stamp, stamp});
      d.execSQL(
          "UPDATE aggregate SET occurrences=MIN(occurrences+1,1000000000),lastSeen=? WHERE"
              + " fingerprint=?",
          new Object[] {stamp, fingerprint});
      while (count(d) > MAX_REPORTS || totalBytes(d) > MAX_BYTES)
        d.execSQL(
            "DELETE FROM reports WHERE id IN (SELECT id FROM reports ORDER BY stamp,rowid LIMIT"
                + " 1)");
      d.execSQL(
          "DELETE FROM aggregate WHERE fingerprint IN (SELECT fingerprint FROM aggregate ORDER BY"
              + " lastSeen DESC LIMIT -1 OFFSET 100)");
      d.setTransactionSuccessful();
    } finally {
      d.endTransaction();
    }
    cleanupExports();
  }

  private static int count(SQLiteDatabase d) {
    try (Cursor c = d.rawQuery("SELECT COUNT(*) FROM reports", null)) {
      return c.moveToFirst() ? c.getInt(0) : 0;
    }
  }

  private static long totalBytes(SQLiteDatabase d) {
    try (Cursor c = d.rawQuery("SELECT COALESCE(SUM(bytes),0) FROM reports", null)) {
      return c.moveToFirst() ? c.getLong(0) : 0;
    }
  }

  private static Map<String, Object> map(JSONObject json) throws JSONException {
    Map<String, Object> m = new LinkedHashMap<>();
    for (Iterator<String> keys = json.keys(); keys.hasNext(); ) {
      String key = keys.next();
      Object value = json.get(key);
      if (value instanceof JSONObject j) value = map(j);
      else if (value instanceof JSONArray a) {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
          Object item = a.get(i);
          list.add(item instanceof JSONObject j ? map(j) : item);
        }
        value = list;
      }
      m.put(key, value);
    }
    return m;
  }

  /** Call on this journal's IO executor or a test worker; never the UI thread. */
  public JSONObject latest(long job) throws Exception {
    SQLiteDatabase d = helper.getReadableDatabase();
    String where = job > 0 ? " WHERE job=?" : "";
    try (Cursor c =
        d.rawQuery(
            "SELECT payload,fingerprint FROM reports" + where + " ORDER BY rowid DESC LIMIT 1",
            job > 0 ? new String[] {Long.toString(job)} : null)) {
      if (!c.moveToFirst()) return null;
      Map<String, Object> report = FailureDiagnostics.sanitize(map(new JSONObject(c.getString(0))));
      String fingerprint = FailureDiagnostics.fingerprint(report);
      Map<String, Object> aggregate = new LinkedHashMap<>();
      try (Cursor a =
          d.rawQuery(
              "SELECT occurrences,firstSeen,lastSeen FROM aggregate WHERE fingerprint=?",
              new String[] {fingerprint})) {
        if (a.moveToFirst()) {
          aggregate.put("occurrenceCount", a.getInt(0));
          aggregate.put("firstSeen", a.getString(1));
          aggregate.put("lastSeen", a.getString(2));
        }
      }
      report.put("fingerprint", fingerprint);
      report.put("aggregate", aggregate);
      return new JSONObject(FailureDiagnostics.sanitize(report));
    }
  }

  public Future<JSONObject> latestAsync(long job) {
    return io.submit(() -> latest(job));
  }

  public Future<Integer> countAsync() {
    return io.submit(() -> count(helper.getReadableDatabase()));
  }

  public void deleteJob(long job) {
    io.execute(
        () -> {
          try {
            SQLiteDatabase database = helper.getWritableDatabase();
            try (Cursor rows =
                database.rawQuery(
                    "SELECT id FROM reports WHERE job=?", new String[] {Long.toString(job)})) {
              while (rows.moveToNext()) {
                String id = rows.getString(0);
                if (id.matches("F-[a-f0-9-]{36}"))
                  new File(exportDirectory(), "Adownloader-Failure-" + id + ".json").delete();
              }
            }
            database.delete("reports", "job=?", new String[] {Long.toString(job)});
            cleanupExports();
          } catch (Exception ignored) {
          }
        });
  }

  public Future<?> clear() {
    return io.submit(
        () -> {
          SQLiteDatabase d = helper.getWritableDatabase();
          d.beginTransaction();
          try {
            d.delete("reports", null, null);
            d.delete("aggregate", null, null);
            d.setTransactionSuccessful();
          } finally {
            d.endTransaction();
          }
          File[] files = exportDirectory().listFiles();
          if (files != null) for (File f : files) if (f.isFile()) f.delete();
          context.getSharedPreferences("device_diagnostics", 0).edit().clear().apply();
        });
  }

  private File exportDirectory() {
    return new File(context.getCacheDir(), "failure-exports");
  }

  private void cleanupExports() {
    File[] files = exportDirectory().listFiles();
    if (files == null) return;
    Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
    long now = System.currentTimeMillis();
    for (int i = 0; i < files.length; i++)
      if (files[i].isFile() && (i >= 10 || now - files[i].lastModified() > 86400000L))
        files[i].delete();
  }

  public File export(long job) throws Exception {
    JSONObject report = latest(job);
    if (report == null) return null;
    // Decode and rebuild through the same explicit schema, independently of stored contents.
    JSONObject clean = new JSONObject(FailureDiagnostics.sanitize(map(report)));
    String id = clean.optString("failureId");
    if (!id.matches("F-[a-f0-9-]{36}")) throw new IOException("DIAGNOSTIC_EXPORT_INVALID");
    File dir = exportDirectory();
    if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("DIAGNOSTIC_EXPORT_STORAGE");
    cleanupExports();
    File output = new File(dir, "Adownloader-Failure-" + id + ".json");
    if (!output.exists()) {
      File[] previous = dir.listFiles(File::isFile);
      if (previous != null && previous.length >= 10) {
        Arrays.sort(previous, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i <= previous.length - 10; i++) previous[i].delete();
      }
    }
    byte[] bytes = clean.toString(2).getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_REPORT_BYTES * 2) throw new IOException("DIAGNOSTIC_EXPORT_LIMIT");
    try (FileOutputStream out = new FileOutputStream(output)) {
      out.write(bytes);
    }
    return output;
  }

  public Future<File> exportAsync(long job) {
    return io.submit(() -> export(job));
  }

  public void execute(Runnable work) {
    io.execute(work);
  }
}
