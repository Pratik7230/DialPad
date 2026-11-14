package com.pratikpatil.dialpad;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileNotFoundException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Where call recordings live and how to list / create / delete them.
 *
 * Android 10+ : MediaStore (scoped storage) under Music/DialPad Recordings — no
 *               storage permission needed for files this app created.
 * Android 7–9 : the same public folder via the File API (needs WRITE_EXTERNAL_STORAGE).
 */
public final class RecordingStore {

    static final String FOLDER = "DialPad Recordings";
    static final String LOCATION_LABEL = "Music / " + FOLDER;
    static final String MIME = "audio/mp4";

    private RecordingStore() {}

    /** One saved recording. */
    public static final class Recording {
        public final Uri uri;              // content:// on Q+, file:// below
        @Nullable public final String path; // only below Q
        public final String displayName;
        public final long sizeBytes;
        public final long dateMillis;
        public final long durationMs;

        Recording(Uri uri, @Nullable String path, String displayName,
                  long sizeBytes, long dateMillis, long durationMs) {
            this.uri = uri;
            this.path = path;
            this.displayName = displayName;
            this.sizeBytes = sizeBytes;
            this.dateMillis = dateMillis;
            this.durationMs = durationMs;
        }

        @Override public boolean equals(Object o) {
            return o instanceof Recording && ((Recording) o).uri.equals(uri);
        }

        @Override public int hashCode() {
            return uri.hashCode();
        }

        /** "Call_John_Doe_20261114_101500.m4a" → "John Doe" */
        public String callerLabel() {
            String n = displayName;
            if (n.endsWith(".m4a")) n = n.substring(0, n.length() - 4);
            if (n.startsWith("Call_")) n = n.substring(5);
            int ts = n.lastIndexOf('_');
            if (ts > 0) {
                int ts2 = n.lastIndexOf('_', ts - 1);
                if (ts2 > 0) n = n.substring(0, ts2);
            }
            n = n.replace('_', ' ').trim();
            return n.isEmpty() ? "Unknown" : n;
        }
    }

    /** An open, writable recording file. Call {@link #commit} or {@link #abort} when done. */
    public static final class PendingRecording {
        final Uri uri;
        @Nullable final File file;
        final ParcelFileDescriptor pfd;

        PendingRecording(Uri uri, @Nullable File file, ParcelFileDescriptor pfd) {
            this.uri = uri;
            this.file = file;
            this.pfd = pfd;
        }

        public java.io.FileDescriptor fd() { return pfd.getFileDescriptor(); }

        /** Make the finished file visible to the user. */
        public void commit(Context ctx) {
            closeQuietly();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Audio.Media.IS_PENDING, 0);
                ctx.getContentResolver().update(uri, v, null, null);
            } else if (file != null) {
                android.media.MediaScannerConnection.scanFile(ctx,
                        new String[]{file.getAbsolutePath()}, new String[]{MIME}, null);
            }
        }

        /** Throw the file away (failed / silent recording). */
        public void abort(Context ctx) {
            closeQuietly();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ctx.getContentResolver().delete(uri, null, null);
            } else if (file != null) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }

        private void closeQuietly() {
            try { pfd.close(); } catch (Exception ignored) {}
        }
    }

    // ==================== CREATE ====================

    static String buildFileName(String caller) {
        String safe = caller == null ? "" : caller.replaceAll("[^\\p{L}\\p{N}+]+", "_");
        safe = safe.replaceAll("^_+|_+$", "");
        if (safe.isEmpty()) safe = "Unknown";
        if (safe.length() > 40) safe = safe.substring(0, 40);
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return "Call_" + safe + "_" + ts + ".m4a";
    }

    /** Creates a new, still-hidden recording file and opens it for writing. */
    static PendingRecording create(Context ctx, String caller) throws FileNotFoundException {
        String name = buildFileName(caller);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Audio.Media.MIME_TYPE, MIME);
            v.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + FOLDER);
            v.put(MediaStore.Audio.Media.IS_PENDING, 1);
            v.put(MediaStore.Audio.Media.IS_MUSIC, 0);
            ContentResolver cr = ctx.getContentResolver();
            Uri uri = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            if (uri == null) throw new FileNotFoundException("MediaStore insert failed");
            ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "w");
            if (pfd == null) {
                cr.delete(uri, null, null);
                throw new FileNotFoundException("Could not open recording file");
            }
            return new PendingRecording(uri, null, pfd);
        }

        File dir = legacyDir();
        if (!dir.exists() && !dir.mkdirs()) throw new FileNotFoundException("Cannot create " + dir);
        File f = new File(dir, name);
        ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f,
                ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE
                        | ParcelFileDescriptor.MODE_WRITE_ONLY);
        return new PendingRecording(Uri.fromFile(f), f, pfd);
    }

    @SuppressWarnings("deprecation")
    private static File legacyDir() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER);
    }

    // ==================== LIST ====================

    /** All saved recordings, newest first. Does disk / provider I/O — call off the main thread. */
    static List<Recording> list(Context ctx) {
        List<Recording> out = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            String[] proj = {
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DATE_ADDED,
                    MediaStore.Audio.Media.DURATION
            };
            String sel = MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?";
            String[] args = {Environment.DIRECTORY_MUSIC + "/" + FOLDER + "%"};
            try (Cursor c = ctx.getContentResolver().query(collection, proj, sel, args,
                    MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
                if (c != null) {
                    while (c.moveToNext()) {
                        long id = c.getLong(0);
                        out.add(new Recording(
                                ContentUris.withAppendedId(collection, id), null,
                                c.getString(1), c.getLong(2), c.getLong(3) * 1000L, c.getLong(4)));
                    }
                }
            } catch (Exception ignored) {}
            return out;
        }

        File[] files = legacyDir().listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (!f.isFile()) continue;
            out.add(new Recording(Uri.fromFile(f), f.getAbsolutePath(), f.getName(),
                    f.length(), f.lastModified(), readDuration(f.getAbsolutePath())));
        }
        Collections.sort(out, (a, b) -> Long.compare(b.dateMillis, a.dateMillis));
        return out;
    }

    private static long readDuration(String path) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d != null ? Long.parseLong(d) : 0;
        } catch (Exception e) {
            return 0;
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    static long totalBytes(Context ctx) {
        long total = 0;
        for (Recording r : list(ctx)) total += r.sizeBytes;
        return total;
    }

    // ==================== DELETE ====================

    static boolean delete(Context ctx, Recording r) {
        try {
            if (r.path != null) return new File(r.path).delete();
            return ctx.getContentResolver().delete(r.uri, null, null) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** @return how many recordings were deleted */
    static int deleteAll(Context ctx) {
        int n = 0;
        for (Recording r : list(ctx)) if (delete(ctx, r)) n++;
        return n;
    }
}
