package com.roshanai.app;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;

/** Temporary private copy of the retained chat file for the Android share sheet. Local only; never goes to Gemini. */
public class Sharer {
  public static final String AUTHORITY = "com.roshanai.app.fileprovider";

  /** Test seam: last URI whose grant we revoked, and how many times. */
  public static volatile Uri lastRevoked; public static volatile int revokeCount;
  public static void revoke(Context c, Uri u) {
    if (u == null) return;
    try { c.revokeUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); } catch (Throwable e) { }
    lastRevoked = u; revokeCount++;
  }

  public static File dir(Context c) { return new File(c.getCacheDir(), "shared"); }

  public static String ext(String mime) {
    if ("image/png".equals(mime)) return ".png"; if ("image/jpeg".equals(mime)) return ".jpg"; if ("image/webp".equals(mime)) return ".webp";
    if ("application/pdf".equals(mime)) return ".pdf"; if ("text/plain".equals(mime)) return ".txt"; return null;
  }

  /** Letters, digits, dot, dash, underscore only; no path parts; extension forced to match the type. */
  public static String safeName(String name, String mime) {
    String e = ext(mime); if (e == null) return null;
    String n = name == null ? "" : name;
    int s = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\')); if (s >= 0) n = n.substring(s + 1);
    int d = n.lastIndexOf('.'); if (d > 0) n = n.substring(0, d);
    n = n.replaceAll("[^A-Za-z0-9_-]", "_").replaceAll("^[._-]+", "");
    if (n.length() > 60) n = n.substring(0, 60);
    if (n.isEmpty()) n = "file";
    return n + e;
  }

  public static void purge(Context c) {
    File[] fs = dir(c).listFiles(); if (fs == null) return;
    for (File f : fs) f.delete();
  }

  /** Replaces any earlier copy. Returns the staged file or null. */
  public static File stage(Context c, byte[] bytes, String name, String mime) {
    String sn = safeName(name, mime); if (sn == null || bytes == null || bytes.length == 0) return null;
    purge(c); File d = dir(c); if (!d.exists() && !d.mkdirs()) return null;
    File f = new File(d, sn);
    try (FileOutputStream o = new FileOutputStream(f)) { o.write(bytes); return f; } catch (Exception e) { f.delete(); return null; }
  }

  /** One read-only grant, handed to whichever app the user picks. No grantUriPermission calls to anyone. */
  public static Intent chooser(Uri uri, String mime) {
    Intent send = new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    send.setClipData(ClipData.newRawUri("file", uri));
    Intent ch = Intent.createChooser(send, "Share file");
    ch.setClipData(ClipData.newRawUri("file", uri)); ch.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    return ch;
  }
}
