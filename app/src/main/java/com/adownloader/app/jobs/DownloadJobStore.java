package com.adownloader.app.jobs;

import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import android.net.Uri;
import android.os.Process;
import android.provider.MediaStore;
import com.adownloader.app.download.*;
import java.io.*;
import java.util.*;
import org.json.*;

/** SQLite is authoritative. Request context and manifests are one AES-GCM envelope per job. */
public final class DownloadJobStore extends SQLiteOpenHelper {
  public enum State {
    QUEUED,
    PREPARING,
    DOWNLOADING,
    PAUSED,
    FINALIZING,
    COMPLETED,
    FAILED,
    CANCELED,
    NEEDS_REFRESH
  }

  public static final class Job {
    public long id, created, updated, bytes, total = -1;
    public int percent, segment, segments, pid;
    public String identity, type, name, host, phase, error, pending, uri, etag, modified, hash;
    public State state;
    public boolean hasPage;
    public String owner;
  }

  private static final java.util.concurrent.CopyOnWriteArraySet<Runnable> listeners =
      new java.util.concurrent.CopyOnWriteArraySet<>();

  public static void observe(Runnable listener) {
    listeners.add(listener);
  }

  public static void unobserve(Runnable listener) {
    listeners.remove(listener);
  }

  private static void changed() {
    for (Runnable listener : listeners) listener.run();
  }

  private int update(ContentValues values, String where, String[] args) {
    SQLiteDatabase database = getWritableDatabase();
    int count = database.update("jobs", values, where, args);
    changed();
    return count;
  }

  private static final String PROCESS_TOKEN = java.util.UUID.randomUUID().toString();
  private final Context context;
  private final JobSecrets secrets = new JobSecrets();

  public DownloadJobStore(Context context) {
    this(context, "download-jobs.db");
  }

  public DownloadJobStore(Context context, String database) {
    super(context.getApplicationContext(), database, null, 1);
    this.context = context.getApplicationContext();
    setWriteAheadLoggingEnabled(true);
  }

  @Override
  public void onConfigure(SQLiteDatabase db) {
    db.setForeignKeyConstraintsEnabled(true);
    db.execSQL("PRAGMA synchronous=FULL");
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE jobs(id INTEGER PRIMARY KEY AUTOINCREMENT,identity TEXT NOT NULL,type TEXT"
            + " NOT NULL,name TEXT NOT NULL,host TEXT NOT NULL,state TEXT NOT NULL,created INTEGER"
            + " NOT NULL,updated INTEGER NOT NULL,bytes INTEGER NOT NULL DEFAULT 0,total"
            + " INTEGER,percent INTEGER NOT NULL DEFAULT 0,segment INTEGER NOT NULL DEFAULT"
            + " 0,segments INTEGER,phase TEXT NOT NULL DEFAULT '',error TEXT NOT NULL DEFAULT"
            + " '',has_page INTEGER NOT NULL DEFAULT 0,priority INTEGER NOT NULL DEFAULT 0,pending"
            + " TEXT,uri TEXT,pid INTEGER NOT NULL DEFAULT 0,etag TEXT,modified TEXT,hash"
            + " TEXT,owner TEXT NOT NULL DEFAULT '',secret BLOB)");
    db.execSQL("CREATE INDEX jobs_queue ON jobs(state,created)");
    db.execSQL(
        "CREATE TABLE parts(job INTEGER NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,track"
            + " INTEGER NOT NULL,part INTEGER NOT NULL,bytes INTEGER NOT NULL,hash TEXT NOT"
            + " NULL,PRIMARY KEY(job,track,part))");
    db.execSQL("CREATE TABLE flags(name TEXT PRIMARY KEY)");
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    throw new IllegalStateException("Unknown job schema migration");
  }

  public synchronized long enqueue(
      String identity, String type, String name, String host, JSONObject request) throws Exception {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      ContentValues values = new ContentValues();
      long now = System.currentTimeMillis();
      values.put("identity", identity);
      values.put("type", type);
      values.put("name", name);
      values.put("host", host == null ? "" : host);
      values.put("has_page", request.has("page") ? 1 : 0);
      values.put("state", State.QUEUED.name());
      values.put("created", now);
      values.put("updated", now);
      long id = db.insertOrThrow("jobs", null, values);
      writeContext(id, request);
      db.setTransactionSuccessful();
      return id;
    } finally {
      db.endTransaction();
      changed();
    }
  }

  public synchronized JSONObject request(long id) throws Exception {
    try (Cursor cursor =
        getReadableDatabase()
            .rawQuery("SELECT secret FROM jobs WHERE id=?", new String[] {"" + id})) {
      if (!cursor.moveToFirst()) throw new IOException("JOB_MISSING");
      return new JSONObject(secrets.open(id, cursor.getBlob(0)));
    }
  }

  public synchronized void writeContext(long id, JSONObject request) throws Exception {
    String text = request.toString();
    if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8 * 1024 * 1024)
      throw new DownloadError(DownloadError.Category.MANIFEST_INVALID);
    ContentValues values = new ContentValues();
    byte[] encrypted = secrets.seal(id, text);
    if (encrypted.length > 1024 * 1024)
      throw new DownloadError(DownloadError.Category.MANIFEST_INVALID);
    values.put("secret", encrypted);
    update(values, "id=?", new String[] {"" + id});
  }

  public synchronized Job get(long id) {
    try (Cursor cursor =
        getReadableDatabase().rawQuery("SELECT * FROM jobs WHERE id=?", new String[] {"" + id})) {
      return cursor.moveToFirst() ? read(cursor) : null;
    }
  }

  public synchronized List<Job> list() {
    List<Job> jobs = new ArrayList<>();
    try (Cursor cursor =
        getReadableDatabase()
            .rawQuery(
                "SELECT * FROM jobs ORDER BY CASE state WHEN 'PREPARING' THEN 0 WHEN 'DOWNLOADING'"
                    + " THEN 0 WHEN 'FINALIZING' THEN 0 WHEN 'QUEUED' THEN 1 ELSE 2 END,CASE WHEN"
                    + " state='QUEUED' THEN priority END DESC,CASE WHEN state='QUEUED' THEN created"
                    + " END ASC,created DESC",
                null)) {
      while (cursor.moveToNext()) jobs.add(read(cursor));
    }
    return jobs;
  }

  private static Job read(Cursor c) {
    Job j = new Job();
    j.id = c.getLong(c.getColumnIndexOrThrow("id"));
    j.identity = str(c, "identity");
    j.type = str(c, "type");
    j.name = str(c, "name");
    j.host = str(c, "host");
    j.state = State.valueOf(str(c, "state"));
    j.created = num(c, "created");
    j.updated = num(c, "updated");
    j.bytes = num(c, "bytes");
    int total = c.getColumnIndexOrThrow("total");
    j.total = c.isNull(total) ? -1 : c.getLong(total);
    j.percent = (int) num(c, "percent");
    j.segment = (int) num(c, "segment");
    j.segments = (int) num(c, "segments");
    j.phase = str(c, "phase");
    j.error = str(c, "error");
    j.pending = str(c, "pending");
    j.uri = str(c, "uri");
    j.etag = str(c, "etag");
    j.modified = str(c, "modified");
    j.hash = str(c, "hash");
    j.pid = (int) num(c, "pid");
    j.hasPage = num(c, "has_page") != 0;
    j.owner = str(c, "owner");
    return j;
  }

  private static long num(Cursor c, String name) {
    return c.getLong(c.getColumnIndexOrThrow(name));
  }

  private static String str(Cursor c, String name) {
    String s = c.getString(c.getColumnIndexOrThrow(name));
    return s == null ? "" : s;
  }

  public synchronized Job claim() {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      long id = -1;
      try (Cursor c =
          db.rawQuery(
              "SELECT id FROM jobs WHERE state='QUEUED' ORDER BY priority DESC,created,id LIMIT 1",
              null)) {
        if (c.moveToFirst()) id = c.getLong(0);
      }
      if (id < 0) return null;
      ContentValues v = new ContentValues();
      v.put("state", State.PREPARING.name());
      v.put("pid", Process.myPid());
      v.put("owner", PROCESS_TOKEN);
      v.put("updated", System.currentTimeMillis());
      db.update("jobs", v, "id=? AND state='QUEUED'", new String[] {"" + id});
      db.setTransactionSuccessful();
      return get(id);
    } finally {
      db.endTransaction();
    }
  }

  public synchronized boolean transition(long id, State from, State to) {
    ContentValues values = new ContentValues();
    values.put("state", to.name());
    values.put("updated", System.currentTimeMillis());
    return update(values, "id=? AND state=?", new String[] {"" + id, from.name()}) == 1;
  }

  public synchronized void prioritize(long id) {
    getWritableDatabase()
        .execSQL(
            "UPDATE jobs SET priority=(SELECT COALESCE(MAX(priority),0)+1 FROM jobs) WHERE id=? AND"
                + " state='QUEUED'",
            new Object[] {id});
    changed();
  }

  public synchronized void clearTerminal(State state) {
    if (state != State.COMPLETED && state != State.FAILED && state != State.CANCELED)
      throw new IllegalArgumentException("Not terminal");
    for (Job job : list())
      if (job.state == state) {
        cleanup(job.id);
        getWritableDatabase().delete("jobs", "id=?", new String[] {"" + job.id});
      }
    changed();
  }

  public synchronized boolean state(long id, State state, String error) {
    ContentValues v = new ContentValues();
    v.put("state", state.name());
    v.put("error", error == null ? "" : error);
    v.put("updated", System.currentTimeMillis());
    return update(v, "id=? AND state NOT IN ('COMPLETED','CANCELED')", new String[] {"" + id}) > 0;
  }

  public synchronized void progress(
      long id, String phase, int percent, long bytes, long total, int segment, int segments) {
    ContentValues v = new ContentValues();
    v.put("phase", phase);
    v.put("percent", Math.max(0, Math.min(100, percent)));
    v.put("bytes", bytes);
    if (total >= 0) v.put("total", total);
    else v.putNull("total");
    v.put("segment", segment);
    if (segments > 0) v.put("segments", segments);
    v.put("updated", System.currentTimeMillis());
    update(v, "id=? AND state IN ('PREPARING','DOWNLOADING','FINALIZING')", new String[] {"" + id});
  }

  public synchronized void directCheckpoint(long id, DirectTransfer.State s) throws Exception {
    JSONObject request = request(id);
    request.put(
        "direct",
        new JSONObject()
            .put("bytes", s.bytes)
            .put("total", s.total)
            .put("etag", s.etag)
            .put("modified", s.modified)
            .put("hash", s.sha256)
            .put("complete", s.complete));
    writeContext(id, request);
    progress(
        id,
        s.restarted ? "파일 변경 · 처음부터 다시 받는 중" : "원본 저장 중",
        s.total > 0 ? (int) (s.bytes * 100 / s.total) : 0,
        s.bytes,
        s.total,
        0,
        0);
  }

  public synchronized void pending(long id, String uri) {
    ContentValues v = new ContentValues();
    v.put("pending", uri);
    update(v, "id=?", new String[] {"" + id});
  }

  public synchronized void complete(long id, String uri, String name) {
    ContentValues v = new ContentValues();
    v.put("state", State.COMPLETED.name());
    v.put("uri", uri);
    v.put("name", name);
    v.put("percent", 100);
    v.put("updated", System.currentTimeMillis());
    v.putNull("secret");
    update(v, "id=? AND state='FINALIZING'", new String[] {"" + id});
  }

  public File directory(long id) throws IOException {
    File directory =
        new File(context.getFilesDir(), getDatabaseName().replace(".db", "") + "/" + id);
    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("JOB_DIRECTORY");
    return directory;
  }

  public synchronized void part(long id, int track, int part, long bytes, String hash) {
    ContentValues v = new ContentValues();
    v.put("job", id);
    v.put("track", track);
    v.put("part", part);
    v.put("bytes", bytes);
    v.put("hash", hash);
    getWritableDatabase().insertWithOnConflict("parts", null, v, SQLiteDatabase.CONFLICT_REPLACE);
  }

  public synchronized String partHash(long id, int track, int part, long length) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT hash FROM parts WHERE job=? AND track=? AND part=? AND bytes=?",
                new String[] {"" + id, "" + track, "" + part, "" + length})) {
      return c.moveToFirst() ? c.getString(0) : null;
    }
  }

  public synchronized void cleanup(long id) {
    Job j = get(id);
    if (j != null && !j.pending.isEmpty() && j.state != State.COMPLETED)
      try {
        context.getContentResolver().delete(Uri.parse(j.pending), null, null);
      } catch (Exception ignored) {
      }
    try {
      delete(directory(id));
    } catch (IOException ignored) {
    }
    getWritableDatabase().delete("parts", "job=?", new String[] {"" + id});
    if (j != null && j.state != State.COMPLETED) {
      ContentValues v = new ContentValues();
      if (j.state == State.CANCELED) v.putNull("secret");
      v.putNull("pending");
      update(v, "id=?", new String[] {"" + id});
    }
  }

  private static void delete(File f) throws IOException {
    File[] children = f.listFiles();
    if (children != null) for (File child : children) delete(child);
    if (f.exists() && !f.delete()) throw new IOException("CLEANUP_FAILED");
  }

  public static boolean ownedHere(Job job) {
    return job.pid == Process.myPid() && PROCESS_TOKEN.equals(job.owner);
  }

  public synchronized void reconcile() {
    for (Job j : list()) {
      if (j.state == State.CANCELED) {
        cleanup(j.id);
        continue;
      }
      if (j.state != State.PREPARING && j.state != State.DOWNLOADING && j.state != State.FINALIZING)
        continue;
      if (j.pid == Process.myPid() && PROCESS_TOKEN.equals(j.owner)) continue;
      boolean published = false;
      if (!j.pending.isEmpty())
        try (Cursor c =
            context
                .getContentResolver()
                .query(
                    Uri.parse(j.pending),
                    new String[] {
                      MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.DISPLAY_NAME
                    },
                    null,
                    null,
                    null)) {
          if (c != null && c.moveToFirst() && c.getInt(0) == 0) {
            ContentValues v = new ContentValues();
            v.put("state", State.FINALIZING.name());
            update(v, "id=?", new String[] {"" + j.id});
            try (android.os.ParcelFileDescriptor descriptor =
                context.getContentResolver().openFileDescriptor(Uri.parse(j.pending), "r")) {
              if (descriptor == null) throw new IOException("MISSING");
              PostProcessor.inspect(
                  descriptor.getFileDescriptor(),
                  0,
                  j.type.equals("VIDEO"),
                  j.type.equals("AUDIO"));
            }
            complete(j.id, j.pending, c.getString(1));
            published = true;
          }
        } catch (Exception ignored) {
        }
      if (!published)
        try {
          request(j.id);
          new JobJournal(this, j.id).verifyAll();
          state(j.id, State.PAUSED, "");
        } catch (DownloadError damaged) {
          state(j.id, State.FAILED, damaged.category.name());
        } catch (Exception unreadable) {
          state(j.id, State.NEEDS_REFRESH, "AUTH");
        }
    }
  }

  public synchronized void migrate() {
    SQLiteDatabase db = getWritableDatabase();
    try (Cursor c = db.rawQuery("SELECT name FROM flags WHERE name='v050'", null)) {
      if (c.moveToFirst()) return;
    }
    db.beginTransaction();
    try {
      JSONObject old =
          new JSONObject(context.getSharedPreferences("downloads", 0).getString("items", "{}"));
      Iterator<String> keys = old.keys();
      while (keys.hasNext()) {
        JSONObject row = old.optJSONObject(keys.next());
        if (row == null) continue;
        ContentValues v = new ContentValues();
        long time = row.optLong("time", System.currentTimeMillis());
        v.put("identity", "legacy");
        v.put("type", row.optString("type", "VIDEO"));
        v.put("name", row.optString("name", "Adownloader"));
        v.put("host", row.optString("host"));
        v.put("created", time);
        v.put("updated", time);
        boolean done = "완료".equals(row.optString("state")) && !row.optString("uri").isEmpty();
        v.put("state", done ? State.COMPLETED.name() : State.FAILED.name());
        v.put("error", done ? "" : "LEGACY_INTERRUPTED");
        if (done) v.put("uri", row.optString("uri"));
        db.insertOrThrow("jobs", null, v);
      }
      db.execSQL("INSERT INTO flags VALUES('v050')");
      db.setTransactionSuccessful();
    } catch (JSONException invalid) {
      throw new IllegalStateException("Legacy history invalid", invalid);
    } finally {
      db.endTransaction();
    }
  }
}
