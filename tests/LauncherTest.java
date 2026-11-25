package com.roshanai.app;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class LauncherTest {
  private Application app() { return RuntimeEnvironment.getApplication(); }
  private void addApp(String pkg, String label) {
    PackageInfo pi = new PackageInfo(); pi.packageName = pkg;
    ApplicationInfo ai = new ApplicationInfo(); ai.packageName = pkg; ai.name = pkg; ai.nonLocalizedLabel = label; pi.applicationInfo = ai;
    Shadows.shadowOf(app().getPackageManager()).installPackage(pi);
    ActivityInfo a = new ActivityInfo(); a.packageName = pkg; a.name = pkg + ".Main"; a.applicationInfo = ai; a.nonLocalizedLabel = label;
    ResolveInfo ri = new ResolveInfo(); ri.activityInfo = a; ri.nonLocalizedLabel = label;
    Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
    Shadows.shadowOf(app().getPackageManager()).addResolveInfoForIntent(q, ri);
  }
  private List<String[]> sample() { List<String[]> l = new ArrayList<String[]>();
    l.add(new String[]{"YouTube","com.google.android.youtube"}); l.add(new String[]{"YouTube Music","com.google.android.apps.youtube.music"});
    l.add(new String[]{"WhatsApp","com.whatsapp"}); l.add(new String[]{"WhatsApp Business","com.whatsapp.w4b"}); l.add(new String[]{"Maps","com.maps"}); return l; }

  @Test public void rankExactBeatsPrefixAndTolerantOfSpaces() throws Exception {
    JSONObject r = Launcher.rank(sample(), "you tube");
    assertTrue(r.getBoolean("exact")); assertEquals(1, r.getJSONArray("items").length());
    assertEquals("com.google.android.youtube", r.getJSONArray("items").getJSONObject(0).getString("pkg"));
    r = Launcher.rank(sample(), "WHATSAPP"); assertTrue(r.getBoolean("exact"));
  }
  @Test public void ambiguousAndMissing() throws Exception {
    JSONObject r = Launcher.rank(sample(), "youtu"); assertFalse(r.getBoolean("exact")); assertEquals(2, r.getJSONArray("items").length());
    r = Launcher.rank(sample(), "whats"); assertFalse(r.getBoolean("exact")); assertEquals(2, r.getJSONArray("items").length());
    r = Launcher.rank(sample(), "zzz"); assertEquals(0, r.getJSONArray("items").length());
    r = Launcher.rank(sample(), ""); assertEquals(0, r.getJSONArray("items").length());
  }
  @Test public void findReadsLauncherEntriesAndOpenLaunchesOnlyThose() throws Exception {
    addApp("com.demo.alpha", "Alpha Notes"); addApp("com.demo.beta", "Beta");
    Launcher.Bridge b = new Launcher.Bridge(app());
    JSONObject f = new JSONObject(b.find("alpha notes"));
    assertTrue(f.getBoolean("ok")); assertTrue(f.getBoolean("exact")); assertEquals(2, f.getInt("seen"));
    JSONObject o = new JSONObject(b.open("com.demo.alpha")); assertTrue(o.toString(), o.getBoolean("ok"));
    Intent i = Shadows.shadowOf(app()).getNextStartedActivity();
    assertEquals("com.demo.alpha", i.getComponent().getPackageName());
    assertFalse(new JSONObject(b.open("com.not.listed")).getBoolean("ok"));
    assertNull(Shadows.shadowOf(app()).getNextStartedActivity());
  }
  @Test public void handoffsBuildExpectedIntents() throws Exception {
    Launcher.Bridge b = new Launcher.Bridge(app());
    JSONObject r = new JSONObject(b.handoff("youtube", "hindi song", ""));
    assertTrue(r.getBoolean("ok")); assertEquals("youtube_app", r.getString("via"));
    Intent i = Shadows.shadowOf(app()).getNextStartedActivity();
    assertEquals(Intent.ACTION_SEARCH, i.getAction()); assertEquals("com.google.android.youtube", i.getPackage()); assertEquals("hindi song", i.getStringExtra("query"));
    b.handoff("dial", "+971 50 123", ""); i = Shadows.shadowOf(app()).getNextStartedActivity();
    assertEquals(Intent.ACTION_DIAL, i.getAction()); assertTrue(i.getDataString().startsWith("tel:"));
    b.handoff("sms", "0501234567", "hi there"); i = Shadows.shadowOf(app()).getNextStartedActivity();
    assertEquals(Intent.ACTION_SENDTO, i.getAction()); assertEquals("hi there", i.getStringExtra("sms_body"));
    b.handoff("email", "a@b.com", "body"); i = Shadows.shadowOf(app()).getNextStartedActivity();
    assertEquals("mailto:a@b.com", i.getDataString());
    b.handoff("map", "Dubai Mall", ""); i = Shadows.shadowOf(app()).getNextStartedActivity();
    assertTrue(i.getDataString().startsWith("geo:0,0?q=Dubai"));
    b.handoff("calendar", "Dentist", ""); i = Shadows.shadowOf(app()).getNextStartedActivity();
    assertEquals(Intent.ACTION_INSERT, i.getAction()); assertEquals("Dentist", i.getStringExtra("title"));
    b.handoff("share", "hello", ""); i = Shadows.shadowOf(app()).getNextStartedActivity();
    assertEquals(Intent.ACTION_CHOOSER, i.getAction());
  }
  @Test public void rejectsBadInputAndUnsafeSchemes() throws Exception {
    Launcher.Bridge b = new Launcher.Bridge(app());
    assertEquals("bad_url", new JSONObject(b.handoff("web", "javascript:alert(1)", "")).getString("error"));
    assertEquals("bad_url", new JSONObject(b.handoff("web", "file:///sdcard/x", "")).getString("error"));
    assertEquals("bad_url", new JSONObject(b.handoff("web", "intent://x#Intent;end", "")).getString("error"));
    assertEquals("bad_number", new JSONObject(b.handoff("dial", "12;*#", "")).getString("error"));
    assertEquals("bad_email", new JSONObject(b.handoff("email", "nope", "")).getString("error"));
    assertEquals("empty", new JSONObject(b.handoff("youtube", " ", "")).getString("error"));
    assertEquals("bad_kind", new JSONObject(b.handoff("x", "a", "")).getString("error"));
    assertNull(Shadows.shadowOf(app()).getNextStartedActivity());
    assertTrue(new JSONObject(b.handoff("web", "https://example.com/a?b=1", "")).getBoolean("ok"));
  }
}
