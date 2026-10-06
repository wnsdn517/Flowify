package com.eza.spicyex.hooks;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;

import com.eza.spicyex.R;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.xposed.XpHooks;
import com.eza.spicyex.xposed.XpLog;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Adds folder-aware grouping and hiding to Spotify's own MediaStore-backed Local Files list. */
public final class LocalFilesFolderHook {
    private static final String TAG = NativeSpicyLyricsHook.TAG;
    private static final String PREF_HIDDEN_FOLDERS = "spotify_local_files_hidden_folders";
    private static volatile Context appContext;

    private LocalFilesFolderHook() {
    }

    public static void install(Context context, ClassLoader classLoader) {
        Context app = context.getApplicationContext();
        appContext = app != null ? app : context;
        try {
            Class<?> reader = Class.forName(
                    "com.spotify.localfiles.mediastore.MediaStoreReader", false, classLoader);
            Method query = reader.getDeclaredMethod("query", Uri.class, boolean.class);
            XpHooks.hookAfter(query, "local-files:folder-aware-query", LocalFilesFolderHook::afterQuery);
            XpLog.log(TAG + " local files folder hook ready: " + query);
        } catch (ReflectiveOperationException | LinkageError error) {
            XpLog.log(TAG + " local files folder hook unavailable: " + error);
        } catch (RuntimeException error) {
            XpLog.log(TAG + " local files folder hook failed to install: " + error);
        }
    }

    public static void showFolderPicker(Context context) {
        final Map<String, Integer> folderCounts;
        try {
            folderCounts = readFolderCounts(context.getContentResolver());
        } catch (RuntimeException error) {
            XpLog.log(TAG + " local files folder list failed: " + error);
            new AlertDialog.Builder(context)
                    .setTitle(R.string.settings_local_files_title)
                    .setMessage(R.string.settings_local_files_error)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        List<String> folders = new ArrayList<>(folderCounts.keySet());
        Collections.sort(folders, String.CASE_INSENSITIVE_ORDER);
        if (folders.isEmpty()) {
            new AlertDialog.Builder(context)
                    .setTitle(R.string.settings_local_files_title)
                    .setMessage(R.string.settings_local_files_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        Set<String> hidden = hiddenFolders(context);
        String[] labels = new String[folders.size()];
        boolean[] checked = new boolean[folders.size()];
        for (int i = 0; i < folders.size(); i++) {
            String folder = folders.get(i);
            labels[i] = folder + " (" + folderCounts.get(folder) + ")";
            checked[i] = LocalFilesFolderPolicy.isHidden(hidden, folder);
        }

        new AlertDialog.Builder(context)
                .setTitle(R.string.settings_local_files_title)
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) ->
                        checked[which] = isChecked)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.settings_local_files_save, (dialog, which) -> {
                    Set<String> updated = new HashSet<>();
                    for (int i = 0; i < folders.size(); i++) {
                        if (checked[i]) updated.add(folders.get(i));
                    }
                    saveHiddenFolders(context, updated);
                    Toast.makeText(context, R.string.settings_local_files_changed,
                            Toast.LENGTH_LONG).show();
                })
                .setNeutralButton(R.string.settings_local_files_unhide_all,
                        (dialog, which) -> {
                            saveHiddenFolders(context, Collections.emptySet());
                            Toast.makeText(context, R.string.settings_local_files_changed,
                                    Toast.LENGTH_LONG).show();
                        })
                .show();
    }

    private static void afterQuery(XpHooks.XpParam param) {
        Context context = appContext;
        if (context == null || param.args.length < 1
                || !(param.args[0] instanceof Uri)
                || !isExternalAudioMedia((Uri) param.args[0])
                || !(param.getResult() instanceof Cursor)) {
            return;
        }

        Cursor source = (Cursor) param.getResult();
        try {
            MatrixCursor filtered = filterCursor(context.getContentResolver(), source,
                    hiddenFolders(context));
            if (filtered != null) {
                source.close();
                param.setResult(filtered);
            }
        } catch (RuntimeException error) {
            XpLog.log(TAG + " local files folder filtering failed: " + error);
        }
    }

    private static MatrixCursor filterCursor(ContentResolver resolver, Cursor source,
                                             Set<String> hiddenFolders) {
        int idColumn = source.getColumnIndex(MediaStore.Audio.Media._ID);
        if (idColumn < 0) {
            XpLog.log(TAG + " local files folder filtering skipped: MediaStore _id column missing");
            return null;
        }

        Map<Long, String> folderById = readFolderPaths(resolver);
        String[] columns = source.getColumnNames();
        int albumColumn = source.getColumnIndex(MediaStore.Audio.Media.ALBUM);
        MatrixCursor result = new MatrixCursor(columns, source.getCount());
        int kept = 0;
        int originalPosition = source.getPosition();
        try {
            while (source.moveToNext()) {
                long id = source.getLong(idColumn);
                String folder = folderById.get(id);
                if (LocalFilesFolderPolicy.isHidden(hiddenFolders, folder)) continue;

                Object[] row = readRow(source, columns.length);
                if (albumColumn >= 0) {
                    row[albumColumn] = LocalFilesFolderPolicy.folderLabel(folder);
                }
                result.addRow(row);
                kept++;
            }
            result.moveToPosition(-1);
            XpLog.log(TAG + " local files folders applied: " + kept + "/" + source.getCount()
                    + " tracks, hidden=" + hiddenFolders.size());
            return result;
        } catch (RuntimeException error) {
            result.close();
            throw error;
        } finally {
            source.moveToPosition(originalPosition);
        }
    }

    private static Object[] readRow(Cursor cursor, int columnCount) {
        Object[] row = new Object[columnCount];
        for (int i = 0; i < columnCount; i++) {
            switch (cursor.getType(i)) {
                case Cursor.FIELD_TYPE_INTEGER:
                    row[i] = cursor.getLong(i);
                    break;
                case Cursor.FIELD_TYPE_FLOAT:
                    row[i] = cursor.getDouble(i);
                    break;
                case Cursor.FIELD_TYPE_BLOB:
                    row[i] = cursor.getBlob(i);
                    break;
                case Cursor.FIELD_TYPE_STRING:
                    row[i] = cursor.getString(i);
                    break;
                default:
                    row[i] = null;
                    break;
            }
        }
        return row;
    }

    private static Map<String, Integer> readFolderCounts(ContentResolver resolver) {
        Map<String, Integer> counts = new HashMap<>();
        String[] projection = folderProjection();
        try (Cursor cursor = resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("MediaStore returned no cursor");
            int folderColumn = folderColumn(cursor);
            if (folderColumn < 0) {
                throw new IllegalStateException("MediaStore folder metadata is unavailable");
            }
            while (cursor.moveToNext()) {
                String folder = folderPath(cursor.getString(folderColumn));
                Integer previous = counts.get(folder);
                counts.put(folder, previous == null ? 1 : previous + 1);
            }
        }
        return counts;
    }

    private static Map<Long, String> readFolderPaths(ContentResolver resolver) {
        Map<Long, String> folders = new HashMap<>();
        String[] projection = folderProjection();
        try (Cursor cursor = resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("MediaStore returned no cursor");
            int idColumn = cursor.getColumnIndex(MediaStore.Audio.Media._ID);
            int folderColumn = folderColumn(cursor);
            if (idColumn < 0 || folderColumn < 0) {
                throw new IllegalStateException("MediaStore folder metadata is unavailable");
            }
            while (cursor.moveToNext()) {
                folders.put(cursor.getLong(idColumn),
                        folderPath(cursor.getString(folderColumn)));
            }
        }
        return folders;
    }

    private static String[] folderProjection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new String[] {
                    MediaStore.Audio.Media._ID,
                    MediaStore.MediaColumns.RELATIVE_PATH
            };
        }
        return new String[] {
                MediaStore.Audio.Media._ID,
                MediaStore.MediaColumns.DATA
        };
    }

    private static int folderColumn(Cursor cursor) {
        return cursor.getColumnIndex(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? MediaStore.MediaColumns.RELATIVE_PATH : MediaStore.MediaColumns.DATA);
    }

    private static String folderPath(String value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return LocalFilesFolderPolicy.normalizeFolderPath(value);
        }
        return LocalFilesFolderPolicy.parentFolderPath(value,
                Environment.getExternalStorageDirectory().getAbsolutePath());
    }

    private static boolean isExternalAudioMedia(Uri uri) {
        return "content".equals(uri.getScheme())
                && "media".equals(uri.getAuthority())
                && uri.getPath() != null
                && uri.getPath().contains("/audio/media");
    }

    private static Set<String> hiddenFolders(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                SpotifyPlusConfig.PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> stored = prefs.getStringSet(PREF_HIDDEN_FOLDERS, Collections.emptySet());
        return stored == null ? Collections.emptySet() : new HashSet<>(stored);
    }

    private static void saveHiddenFolders(Context context, Set<String> folders) {
        context.getSharedPreferences(SpotifyPlusConfig.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(PREF_HIDDEN_FOLDERS, new HashSet<>(folders))
                .apply();
    }
}
