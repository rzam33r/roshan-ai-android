package com.roshanai.app;

import android.net.Uri;
import android.os.ParcelFileDescriptor;
import androidx.core.content.FileProvider;
import java.io.FileNotFoundException;

/** FileProvider that only ever opens files for reading, whatever mode a caller asks for. Delete, insert and update are refused. */
public class SafeShareProvider extends FileProvider {
  @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("read-only provider"); }

  @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (!"r".equals(mode)) throw new SecurityException("read-only provider");
    return super.openFile(uri, mode);
  }
}
