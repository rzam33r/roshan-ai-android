package com.roshanai.app;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.XmlResourceParser;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import androidx.core.content.FileProvider;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.lang.reflect.*;

/** Real androidx FileProvider + the real res/xml/share_paths.xml from the source tree, configured like the manifest entry. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ProviderTest {
  static final String XML = "res/xml/share_paths.xml";
  private Application app() { return RuntimeEnvironment.getApplication(); }

  static XmlResourceParser parserFor(String path) throws Exception {
    final XmlPullParser p = android.util.Xml.newPullParser();
    p.setInput(new FileInputStream(path), "UTF-8");
    return (XmlResourceParser) Proxy.newProxyInstance(ProviderTest.class.getClassLoader(), new Class[]{XmlResourceParser.class}, new InvocationHandler() {
      public Object invoke(Object o, Method m, Object[] a) throws Throwable {
        if (m.getName().equals("close")) return null;
        try { return m.invoke(p, a); } catch (InvocationTargetException e) { throw e.getCause(); }
      }});
  }

  private ProviderInfo info(boolean exported, boolean grant) {
    ProviderInfo pi = new ProviderInfo() {
      @Override public XmlResourceParser loadXmlMetaData(PackageManager pm, String name) {
        if (!"android.support.FILE_PROVIDER_PATHS".equals(name)) return null;
        try { return parserFor(XML); } catch (Exception e) { throw new RuntimeException(e); }
      }
    };
    pi.authority = Sharer.AUTHORITY; pi.exported = exported; pi.grantUriPermissions = grant;
    pi.packageName = app().getPackageName(); pi.name = "com.roshanai.app.SafeShareProvider";
    return pi;
  }
  /** Robolectric hands FileProvider a copy of ProviderInfo that loses xml access, so the strategy is built from the real xml file and put in FileProvider's own cache. Enforcement below is FileProvider's real code. */
  private void primeStrategyFromRealXml() throws Exception {
    XmlResourceParser x = parserFor(XML); int tags = 0;
    Class<?> sc = Class.forName("androidx.core.content.FileProvider$SimplePathStrategy");
    Constructor<?> ctor = sc.getDeclaredConstructor(String.class); ctor.setAccessible(true);
    Object strat = ctor.newInstance(Sharer.AUTHORITY);
    Method add = sc.getDeclaredMethod("addRoot", String.class, File.class); add.setAccessible(true);
    int ev;
    while ((ev = x.next()) != XmlPullParser.END_DOCUMENT) {
      if (ev != XmlPullParser.START_TAG || x.getName().equals("paths")) continue;
      assertEquals("only cache-path is allowed", "cache-path", x.getName()); tags++;
      String name = x.getAttributeValue(null, "name"), path = x.getAttributeValue(null, "path");
      assertEquals("shared", name); assertEquals("shared/", path);
      File root = new File(app().getCacheDir(), path); add.invoke(strat, name, root.getCanonicalFile());
    }
    assertEquals(1, tags);
    Field cf = FileProvider.class.getDeclaredField("sCache"); cf.setAccessible(true);
    ((java.util.HashMap<String, Object>) cf.get(null)).put(Sharer.AUTHORITY, strat);
  }
  private SafeShareProvider provider() throws Exception {
    ProviderInfo pi = info(false, true);
    Shadows.shadowOf(app().getPackageManager()).addOrUpdateProvider(pi);
    SafeShareProvider sp = new SafeShareProvider(); sp.attachInfo(app(), pi);
    primeStrategyFromRealXml(); /* newer androidx clears the strategy cache in attachInfo, so prime after it */ return sp;
  }
  private File shared(String name, String content) throws Exception {
    File d = Sharer.dir(app()); d.mkdirs(); File f = new File(d, name);
    try (FileOutputStream o = new FileOutputStream(f)) { o.write(content.getBytes("UTF-8")); } return f;
  }
  private String read(ParcelFileDescriptor pfd) throws Exception {
    try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) { ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] x = new byte[512]; int n; while ((n = in.read(x)) > 0) b.write(x, 0, n); return b.toString("UTF-8"); }
  }

  @Test public void providerMustBeNotExportedAndGrantBased() throws Exception {
    primeStrategyFromRealXml();
    try { new SafeShareProvider().attachInfo(app(), info(true, true)); fail("exported provider accepted"); } catch (SecurityException e) { }
    try { new SafeShareProvider().attachInfo(app(), info(false, false)); fail("no-grant provider accepted"); } catch (SecurityException e) { }
    provider();
  }
  @Test public void uriMapsToSharedFolderOnly() throws Exception {
    provider();
    File f = shared("one.txt", "hello");
    Uri u = FileProvider.getUriForFile(app(), Sharer.AUTHORITY, f);
    assertEquals("content://" + Sharer.AUTHORITY + "/shared/one.txt", u.toString());
    File other = new File(app().getCacheDir(), "other.txt"); new FileOutputStream(other).close();
    File inFiles = new File(app().getFilesDir(), "secret.txt"); app().getFilesDir().mkdirs(); new FileOutputStream(inFiles).close();
    for (File bad : new File[]{other, inFiles, new File(Sharer.dir(app()), "../other.txt"), new File("/etc/hosts")}) {
      try { FileProvider.getUriForFile(app(), Sharer.AUTHORITY, bad); fail("mapped outside root: " + bad); } catch (IllegalArgumentException e) { }
    }
  }
  @Test public void opensForReadOnly() throws Exception {
    SafeShareProvider sp = provider(); shared("one.txt", "hello");
    Uri u = Uri.parse("content://" + Sharer.AUTHORITY + "/shared/one.txt");
    assertEquals("hello", read(sp.openFile(u, "r")));
    for (String m : new String[]{"w", "rw", "rwt", "wa", "wt", ""}) {
      try { sp.openFile(u, m); fail("mode allowed: " + m); } catch (SecurityException e) { }
    }
    assertEquals("hello", read(sp.openFile(u, "r")));
    try { sp.delete(u, null, null); fail(); } catch (UnsupportedOperationException e) { }
    try { sp.insert(u, new android.content.ContentValues()); fail(); } catch (UnsupportedOperationException e) { }
    try { sp.update(u, new android.content.ContentValues(), null, null); fail(); } catch (UnsupportedOperationException e) { }
  }
  @Test public void traversalAndOutsideUrisAreRejected() throws Exception {
    SafeShareProvider sp = provider(); shared("one.txt", "hello");
    File other = new File(app().getCacheDir(), "other.txt"); try (FileOutputStream o = new FileOutputStream(other)) { o.write("SECRET".getBytes()); }
    String[] paths = {"/shared/../other.txt", "/shared/%2e%2e/other.txt", "/shared/..%2Fother.txt", "/other.txt", "/shared/sub/../../other.txt", "/files/secret.txt", "/"};
    for (String p : paths) {
      Uri u = Uri.parse("content://" + Sharer.AUTHORITY + p);
      try { String got = read(sp.openFile(u, "r")); fail("opened " + p + " -> " + got); } catch (RuntimeException e) { } catch (FileNotFoundException e) { }
    }
  }
  private void setField(Object o, String n, Object v) throws Exception { Field f = o.getClass().getDeclaredField(n); f.setAccessible(true); f.set(o, v); }
  private Object getField(Object o, String n) throws Exception { Field f = o.getClass().getDeclaredField(n); f.setAccessible(true); return f.get(o); }

  @Test public void shareLifecycleRevokesAndDeletes() throws Exception {
    primeStrategyFromRealXml();
    Shadows.shadowOf(app().getPackageManager()).addOrUpdateProvider(info(false, true));
    shared("stale.txt", "old");
    MainActivity a = Robolectric.buildActivity(MainActivity.class).create().get();
    assertFalse("stale cache not purged at start", new File(Sharer.dir(app()), "stale.txt").exists());
    // nothing retained: nothing shared, nothing launched
    JSONObject r = new JSONObject(a.new ShareBridge().shareFile());
    assertFalse(r.getBoolean("ok")); assertEquals("none", r.getString("error"));
    assertNull(Shadows.shadowOf(app()).getNextStartedActivity());
    // retained pending attachment
    setField(a, "attBytes", "PNGDATA".getBytes()); setField(a, "attMime", "image/png"); setField(a, "attName", "My Photo (1).png");
    r = new JSONObject(a.new ShareBridge().shareFile());
    assertTrue(r.toString(), r.getBoolean("ok")); assertEquals("My_Photo__1_.png", r.getString("name"));
    File f1 = new File(Sharer.dir(app()), "My_Photo__1_.png"); assertTrue(f1.exists());
    Intent ch = Shadows.shadowOf(app()).getNextStartedActivity();
    Intent send = ch.getParcelableExtra(Intent.EXTRA_INTENT);
    Uri u1 = send.getParcelableExtra(Intent.EXTRA_STREAM);
    assertEquals("content://" + Sharer.AUTHORITY + "/shared/My_Photo__1_.png", u1.toString());
    assertTrue((send.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    assertEquals(0, send.getFlags() & (Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION));
    assertEquals("image/png", send.getType());
    assertEquals("PNGDATA", read(provider().openFile(u1, "r")));
    assertEquals(0, ((AiTurns) () -> a.new AiBridge().turns()).n());   // no chat turn added
    // sharing another file replaces: old copy gone, old grant revoked
    int rc = Sharer.revokeCount;
    setField(a, "attBytes", "PDFDATA".getBytes()); setField(a, "attMime", "application/pdf"); setField(a, "attName", "doc.pdf");
    assertTrue(new JSONObject(a.new ShareBridge().shareFile()).getBoolean("ok"));
    assertFalse(f1.exists()); assertTrue(Sharer.revokeCount > rc); assertEquals(u1, Sharer.lastRevoked);
    Uri u2 = (Uri) getField(a, "shareUri"); assertNotEquals(u1, u2);
    // clear (remove attachment) revokes the current grant and deletes the copy
    rc = Sharer.revokeCount; a.new AttachBridge().clear();
    assertEquals(u2, Sharer.lastRevoked); assertTrue(Sharer.revokeCount > rc);
    assertEquals(0, Sharer.dir(app()).listFiles().length); assertNull(getField(a, "shareUri"));
    // NEW CHAT revokes too
    setField(a, "attBytes", "x".getBytes()); setField(a, "attMime", "text/plain"); setField(a, "attName", "n.txt");
    assertTrue(new JSONObject(a.new ShareBridge().shareFile()).getBoolean("ok"));
    Uri u3 = (Uri) getField(a, "shareUri"); rc = Sharer.revokeCount;
    a.new AiBridge().reset();
    assertEquals(u3, Sharer.lastRevoked); assertTrue(Sharer.revokeCount > rc);
    assertEquals(0, Sharer.dir(app()).listFiles().length); assertNull(getField(a, "shareUri"));
    assertNull(getField(a, "attBytes"));
  }
  interface AiTurns { int n(); }
}
