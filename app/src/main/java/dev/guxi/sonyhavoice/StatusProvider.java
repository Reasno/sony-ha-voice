package dev.guxi.sonyhavoice;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/** Connection availability for a launcher signed with the same key. No configuration or speech data. */
public final class StatusProvider extends ContentProvider {
    public static final Uri URI = Uri.parse("content://dev.guxi.sonyhavoice.status/connection");
    private static volatile boolean ready;

    static void publish(Context context, boolean available) {
        ready = available;
        context.getContentResolver().notifyChange(URI, null);
    }

    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                   String[] selectionArgs, String sortOrder) {
        if (!URI.equals(uri)) throw new IllegalArgumentException("Unknown status URI");
        MatrixCursor cursor = new MatrixCursor(new String[]{"ready"});
        cursor.addRow(new Object[]{ready ? 1 : 0});
        cursor.setNotificationUri(getContext().getContentResolver(), URI);
        return cursor;
    }
    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/vnd.sonyhavoice.connection"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) {
        throw new UnsupportedOperationException();
    }
    @Override public int delete(Uri uri, String where, String[] args) {
        throw new UnsupportedOperationException();
    }
}
