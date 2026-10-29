package app.chitchat;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Shares only this app's installed base APK through a temporary URI grant. */
public final class ApkProvider extends ContentProvider {
    private File apk(Uri uri) throws FileNotFoundException {
        if(!"/ChitChat.apk".equals(uri.getPath())) throw new FileNotFoundException("Unknown file");
        return new File(getContext().getApplicationInfo().sourceDir);
    }
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if(!"r".equals(mode)) throw new FileNotFoundException("Read only"); return ParcelFileDescriptor.open(apk(uri),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {
        try {
            File f=apk(uri); String[] cols=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
            MatrixCursor c=new MatrixCursor(cols); Object[] row=new Object[cols.length];
            for(int i=0;i<cols.length;i++) { if(OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i]="ChitChat.apk"; else if(OpenableColumns.SIZE.equals(cols[i])) row[i]=f.length(); }
            c.addRow(row); return c;
        } catch(FileNotFoundException e) { return null; }
    }
    @Override public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException("Read only"); }
    @Override public int delete(Uri uri,String selection,String[] args) { throw new UnsupportedOperationException("Read only"); }
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args) { throw new UnsupportedOperationException("Read only"); }
}
