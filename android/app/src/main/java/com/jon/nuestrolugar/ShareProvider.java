package com.jon.nuestrolugar;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Entrega a WhatsApp/Facebook/Instagram la imagen que se va a compartir (solo archivos de la carpeta share/). */
public class ShareProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    File fileFor(Uri u) throws FileNotFoundException {
        String n = u.getLastPathSegment();
        if (n == null || !n.matches("[a-z0-9_-]+\\.jpg")) throw new FileNotFoundException();
        File f = new File(new File(getContext().getCacheDir(), "share"), n);
        if (!f.exists()) throw new FileNotFoundException();
        return f;
    }

    @Override public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(u), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri u) { return "image/jpeg"; }

    @Override public Cursor query(Uri u, String[] proj, String sel, String[] args, String sort) {
        try {
            File f = fileFor(u);
            MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            c.addRow(new Object[]{f.getName(), f.length()});
            return c;
        } catch (Exception e) { return null; }
    }

    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
