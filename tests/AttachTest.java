package com.roshanai.app;
import static org.junit.Assert.*;
import org.junit.Test; import org.junit.runner.RunWith;
import org.robolectric.*; import org.robolectric.annotation.Config; import org.robolectric.shadows.*;
import android.content.Intent; import android.net.Uri; import android.webkit.WebView; import org.json.JSONObject;
import java.io.ByteArrayInputStream;
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class AttachTest {
  private MainActivity a;
  private Object f(String n) throws Exception { java.lang.reflect.Field x = MainActivity.class.getDeclaredField(n); x.setAccessible(true); return x.get(a); }
  private JSONObject pickFile(String name, byte[] data) throws Exception {
    Uri u = Uri.parse("content://test.docs/doc/" + name);
    Shadows.shadowOf(RuntimeEnvironment.getApplication().getContentResolver()).registerInputStream(u, new ByteArrayInputStream(data));
    Intent r = new Intent().setData(u);
    java.lang.reflect.Method m = MainActivity.class.getDeclaredMethod("onActivityResult", int.class, int.class, Intent.class); m.setAccessible(true);
    m.invoke(a, 21, android.app.Activity.RESULT_OK, r); ShadowLooper.idleMainLooper();
    String js = Shadows.shadowOf((WebView) f("wv")).getLastEvaluatedJavascript();
    String inner = js.substring(js.indexOf('(') + 1, js.lastIndexOf(')'));
    return new JSONObject(new JSONObject("{\"v\":" + inner + "}").getString("v"));
  }
  private byte[] png() throws Exception { android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(300, 200, android.graphics.Bitmap.Config.ARGB_8888); java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(); b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o); return o.toByteArray(); }
  @Test public void formatsAndLimits() throws Exception {
    a = Robolectric.buildActivity(MainActivity.class).create().get();
    assertNull(f("attBytes"));
    JSONObject r = pickFile("pic.png", png()); System.out.println("AT png " + r.getBoolean("ok") + " " + r.getString("mime") + " thumb=" + (r.getString("thumb").startsWith("data:image/jpeg;base64,")));
    assertTrue(r.getBoolean("ok")); assertEquals("image/png", r.getString("mime")); assertTrue(r.getString("thumb").startsWith("data:image/jpeg"));
    assertNotNull(f("attBytes")); assertEquals("image/png", f("attMime"));
  }
  @Test public void typesAndRefusals() throws Exception {
    a = Robolectric.buildActivity(MainActivity.class).create().get();
    JSONObject t = pickFile("n.txt", "hello".getBytes()); assertTrue(t.toString(), t.getBoolean("ok")); assertEquals("text/plain", t.getString("mime"));
    JSONObject p = pickFile("d.pdf", "%PDF-1.4 x".getBytes()); assertTrue(p.getBoolean("ok")); assertEquals("application/pdf", p.getString("mime"));
    JSONObject j = pickFile("x.JPEG", new byte[]{1,2,3}); assertTrue(j.getBoolean("ok")); assertEquals("image/jpeg", j.getString("mime"));
    a.new AttachBridge().clear(); assertNull(f("attBytes"));
    for (String bad : new String[]{"a.docx","b.xlsx","c.zip","d.mp4","e.mp3","f.heic"}) { JSONObject o = pickFile(bad, new byte[]{1,2,3}); assertFalse(bad, o.getBoolean("ok")); assertEquals(bad, "unsupported", o.getString("error")); assertNull(bad, f("attBytes")); }
    JSONObject big = pickFile("big.pdf", new byte[10 * 1024 * 1024 + 1]); assertFalse(big.getBoolean("ok")); assertEquals("too_large", big.getString("error")); assertNull(f("attBytes"));
    JSONObject ok = pickFile("edge.pdf", new byte[10 * 1024 * 1024]); assertTrue(ok.getBoolean("ok")); a.new AttachBridge().clear();
    JSONObject em = pickFile("e.txt", new byte[0]); assertFalse(em.getBoolean("ok")); assertEquals("empty", em.getString("error"));
    System.out.println("AT types ok");
  }
  @Test public void cancelKeepsNothingAndManifestUnchanged() throws Exception {
    a = Robolectric.buildActivity(MainActivity.class).create().get();
    java.lang.reflect.Method m = MainActivity.class.getDeclaredMethod("onActivityResult", int.class, int.class, Intent.class); m.setAccessible(true);
    m.invoke(a, 21, android.app.Activity.RESULT_CANCELED, null); assertNull(f("attBytes"));
    assertNotNull(Shadows.shadowOf((WebView) f("wv")).getJavascriptInterface("AndroidAttach"));
    assertNotNull(MainActivity.AttachBridge.class.getMethod("pick").getAnnotation(android.webkit.JavascriptInterface.class));
    assertNotNull(MainActivity.AttachBridge.class.getMethod("clear").getAnnotation(android.webkit.JavascriptInterface.class));
    System.out.println("AT cancel ok");
  }
}
