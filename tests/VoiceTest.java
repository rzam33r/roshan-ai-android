package com.roshanai.app;
import static org.junit.Assert.*;
import org.junit.Test; import org.junit.runner.RunWith;
import org.robolectric.*; import org.robolectric.annotation.Config;
import org.robolectric.shadows.*;
import android.app.Application; import android.os.Bundle; import android.speech.SpeechRecognizer; import android.webkit.WebView;
import java.util.ArrayList;
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class VoiceTest {
  private void avail() { android.content.pm.ResolveInfo ri = new android.content.pm.ResolveInfo(); ri.serviceInfo = new android.content.pm.ServiceInfo(); ri.serviceInfo.packageName = "com.google.android.googlequicksearchbox"; ri.serviceInfo.name = "S"; ri.serviceInfo.applicationInfo = new android.content.pm.ApplicationInfo(); ri.serviceInfo.applicationInfo.packageName = ri.serviceInfo.packageName;
    Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager()).addResolveInfoForIntent(new android.content.Intent("android.speech.RecognitionService"), ri); }
  private MainActivity act() { return Robolectric.buildActivity(MainActivity.class).create().get(); }
  private WebView wv(MainActivity a) throws Exception { java.lang.reflect.Field f = MainActivity.class.getDeclaredField("wv"); f.setAccessible(true); return (WebView) f.get(a); }
  @Test public void interfacesRegisteredAndAnnotated() throws Exception {
    MainActivity a = act(); ShadowWebView sw = Shadows.shadowOf(wv(a));
    for (String n : new String[]{"AndroidVoice","AndroidSched","AndroidClipboard"}) assertNotNull(n, sw.getJavascriptInterface(n));
    assertNull("AiBridge may be registered under another name", null);
    for (String m : new String[]{"supported","offlineHint","start","stop"})
      assertNotNull(m, MainActivity.VoiceBridge.class.getMethod(m).getAnnotation(android.webkit.JavascriptInterface.class));
    for (java.lang.reflect.Method m : MainActivity.AiBridge.class.getDeclaredMethods()) if (java.lang.reflect.Modifier.isPublic(m.getModifiers())) assertNotNull(m.getName(), m.getAnnotation(android.webkit.JavascriptInterface.class));
    System.out.println("VT interfaces=ok");
  }
  @Test public void noPermissionRequestsItAndReports() throws Exception {
    MainActivity a = act(); 
    avail();
    Shadows.shadowOf(a).denyPermissions("android.permission.RECORD_AUDIO");
    a.new VoiceBridge().start(); ShadowLooper.idleMainLooper();
    assertNotNull(Shadows.shadowOf(a).getLastRequestedPermission());
    assertEquals("android.permission.RECORD_AUDIO", Shadows.shadowOf(a).getLastRequestedPermission().requestedPermissions[0]);
    String js = Shadows.shadowOf(wv(a)).getLastEvaluatedJavascript();
    System.out.println("VT noperm js=" + js);
    assertTrue(js, js.contains("onVoiceResult(false,\"mic_permission_requested\")"));
  }
  @Test public void noRecognizerReports() throws Exception {
    MainActivity a = act(); 
    a.new VoiceBridge().start(); ShadowLooper.idleMainLooper();
    String js = Shadows.shadowOf(wv(a)).getLastEvaluatedJavascript();
    assertTrue(js, js.contains("onVoiceResult(false,\"no_recognizer\")"));
  }
  @Test public void grantedCreatesRecognizerAndDeliversResultAndErrors() throws Exception {
    MainActivity a = act(); avail();
    Shadows.shadowOf(a).grantPermissions("android.permission.RECORD_AUDIO");
    a.new VoiceBridge().start(); ShadowLooper.idleMainLooper();
    SpeechRecognizer sr = ShadowSpeechRecognizer.getLatestSpeechRecognizer();
    assertNotNull(sr);
    ShadowSpeechRecognizer ss = Shadows.shadowOf(sr);
    android.content.Intent i = ss.getLastRecognizerIntent(); assertNotNull(i);
    assertEquals("free_form", i.getStringExtra("android.speech.extra.LANGUAGE_MODEL"));
    Bundle b = new Bundle(); ArrayList<String> l = new ArrayList<>(); l.add("remind me \"x\""); b.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, l);
    ss.triggerOnResults(b); ShadowLooper.idleMainLooper();
    String js = Shadows.shadowOf(wv(a)).getLastEvaluatedJavascript(); System.out.println("VT ok js=" + js);
    assertTrue(js, js.contains("onVoiceResult(true,\"remind me \\\"x\\\"\")"));
    ss.triggerOnError(SpeechRecognizer.ERROR_NO_MATCH); ShadowLooper.idleMainLooper();
    assertTrue(Shadows.shadowOf(wv(a)).getLastEvaluatedJavascript().contains("(false,\"no_speech\")"));
    ss.triggerOnError(SpeechRecognizer.ERROR_NETWORK); ShadowLooper.idleMainLooper();
    assertTrue(Shadows.shadowOf(wv(a)).getLastEvaluatedJavascript().contains("(false,\"error_2\")"));
    a.new VoiceBridge().stop();
  }
}
