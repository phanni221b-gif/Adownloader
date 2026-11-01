package com.adownloader.app;

import static org.junit.Assert.*;

import android.content.*;
import android.database.sqlite.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.adownloader.app.jobs.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SchemaMigrationIntegrationTest {
  private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
  private static final String NAME = "migration-fixture.db";

  private SQLiteOpenHelper fixture() {
    return new SQLiteOpenHelper(context, NAME, null, 1) {
      public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new AssertionError();
      }

      public void onCreate(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE jobs(id INTEGER PRIMARY KEY AUTOINCREMENT,identity TEXT NOT NULL,type"
                + " TEXT NOT NULL,name TEXT NOT NULL,host TEXT NOT NULL,state TEXT NOT NULL,created"
                + " INTEGER NOT NULL,updated INTEGER NOT NULL,bytes INTEGER NOT NULL DEFAULT"
                + " 0,total INTEGER,percent INTEGER NOT NULL DEFAULT 0,segment INTEGER NOT NULL"
                + " DEFAULT 0,segments INTEGER,phase TEXT NOT NULL DEFAULT '',error TEXT NOT NULL"
                + " DEFAULT '',has_page INTEGER NOT NULL DEFAULT 0,priority INTEGER NOT NULL"
                + " DEFAULT 0,pending TEXT,uri TEXT,pid INTEGER NOT NULL DEFAULT 0,etag"
                + " TEXT,modified TEXT,hash TEXT,owner TEXT NOT NULL DEFAULT '',secret BLOB)");
        db.execSQL("CREATE INDEX jobs_queue ON jobs(state,created)");
        db.execSQL("CREATE INDEX jobs_refresh ON jobs(state,identity)");
        db.execSQL(
            "CREATE TABLE parts(job INTEGER NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,track"
                + " INTEGER NOT NULL,part INTEGER NOT NULL,bytes INTEGER NOT NULL,hash TEXT NOT"
                + " NULL,PRIMARY KEY(job,track,part))");
        db.execSQL(
            "CREATE TABLE contexts(job INTEGER NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,part"
                + " INTEGER NOT NULL,data BLOB NOT NULL,PRIMARY KEY(job,part))");
        db.execSQL("CREATE TABLE flags(name TEXT PRIMARY KEY)");
      }
    };
  }

  @Test
  public void realV1UpgradePreservesJobsHistoryPartsAndBothEncryptedContextLayouts()
      throws Exception {
    context.deleteDatabase(NAME);
    try {
      try (SQLiteOpenHelper legacy = fixture()) {
        SQLiteDatabase db = legacy.getWritableDatabase();
        for (int id = 1; id <= 3; id++) {
          ContentValues job = new ContentValues();
          job.put("id", id);
          job.put("identity", "fixture-" + id);
          job.put("type", "VIDEO");
          job.put("name", "preserved-" + id);
          job.put("host", "example.invalid");
          job.put("state", id == 3 ? "COMPLETED" : "PAUSED");
          job.put("created", 42);
          job.put("updated", 43);
          if (id == 3) job.put("uri", "content://media/external/video/media/123");
          byte[] encrypted =
              new JobSecrets()
                  .seal(
                      id,
                      new JSONObject()
                          .put("url", "https://example.invalid/video?token=PRIVATE")
                          .put("headers", new JSONObject().put("Cookie", "MIGRATION_PRIVATE"))
                          .toString());
          if (id == 1) job.put("secret", encrypted);
          db.insertOrThrow("jobs", null, job);
          if (id == 2) {
            int half = encrypted.length / 2;
            for (int part = 0; part < 2; part++) {
              ContentValues piece = new ContentValues();
              piece.put("job", id);
              piece.put("part", part);
              piece.put(
                  "data",
                  java.util.Arrays.copyOfRange(
                      encrypted, part == 0 ? 0 : half, part == 0 ? half : encrypted.length));
              db.insertOrThrow("contexts", null, piece);
            }
          }
        }
        db.execSQL("INSERT INTO parts VALUES(1,0,0,1024,'preserved-hash')");
        db.execSQL("INSERT INTO flags VALUES('v050')");
        assertEquals(1, db.getVersion());
      }
      try (DownloadJobStore store = new DownloadJobStore(context, NAME)) {
        assertEquals(2, store.getReadableDatabase().getVersion());
        assertEquals(3, store.list().size());
        assertEquals("preserved-3", store.get(3).name);
        assertEquals(DownloadJobStore.State.COMPLETED, store.get(3).state);
        assertEquals("content://media/external/video/media/123", store.get(3).uri);
        for (int id : new int[] {1, 2})
          assertEquals(
              "MIGRATION_PRIVATE", store.request(id).getJSONObject("headers").getString("Cookie"));
        assertEquals("preserved-hash", store.partHash(1, 0, 0, 1024));
        store.finalizedMedia(1, 2048, "output-hash", "video/mp4", ".mp4");
        assertEquals(2048, store.finalizedMedia(1).getLong("bytes"));
      }
      try (SQLiteDatabase future =
          SQLiteDatabase.openDatabase(
              context.getDatabasePath(NAME).getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
        future.setVersion(3);
      }
      try (DownloadJobStore downgrade = new DownloadJobStore(context, NAME)) {
        try {
          downgrade.getWritableDatabase();
          fail();
        } catch (SQLiteException expected) {
          assertTrue(expected.getMessage().contains("JOB_SCHEMA_DOWNGRADE_UNSUPPORTED"));
        }
      }
      try (SQLiteDatabase unchanged =
          SQLiteDatabase.openDatabase(
              context.getDatabasePath(NAME).getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
        assertEquals(3, unchanged.getVersion());
        unchanged.setVersion(2);
      }
      try (DownloadJobStore reopened = new DownloadJobStore(context, NAME)) {
        assertEquals(3, reopened.list().size());
        assertEquals(
            "MIGRATION_PRIVATE", reopened.request(1).getJSONObject("headers").getString("Cookie"));
      }
    } finally {
      context.deleteDatabase(NAME);
    }
  }

  @Test
  public void failedUpgradeRollsBackAndDoesNotResetV1() throws Exception {
    context.deleteDatabase(NAME);
    try {
      try (SQLiteOpenHelper legacy = fixture()) {
        SQLiteDatabase db = legacy.getWritableDatabase();
        db.execSQL("CREATE TABLE finalized_media(conflicting INTEGER)");
        db.execSQL("INSERT INTO flags VALUES('KEEP_ON_FAILURE')");
      }
      try (DownloadJobStore store = new DownloadJobStore(context, NAME)) {
        try {
          store.getWritableDatabase();
          fail();
        } catch (SQLiteException expected) {
          assertTrue(expected.getMessage().contains("JOB_SCHEMA_UPGRADE_FAILED"));
        }
      }
      try (SQLiteOpenHelper legacy = fixture()) {
        SQLiteDatabase db = legacy.getReadableDatabase();
        assertEquals(1, db.getVersion());
        try (var cursor =
            db.rawQuery("SELECT name FROM flags WHERE name='KEEP_ON_FAILURE'", null)) {
          assertTrue(cursor.moveToFirst());
        }
      }
    } finally {
      context.deleteDatabase(NAME);
    }
  }
}
