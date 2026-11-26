package com.roshanai.app;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class UpdateTest {
  static String rel(String tag, String asset, String url, long size, String extra) {
    return "{\"tag_name\":\"" + tag + "\",\"name\":\"Drive <b>grid</b>\",\"body\":\"New:\\r\\n- grid\\u0007\",\"draft\":false,\"prerelease\":false" + extra
      + ",\"assets\":[{\"name\":\"" + asset + "\",\"state\":\"uploaded\",\"size\":" + size + ",\"browser_download_url\":\"" + url + "\",\"digest\":\"sha256:" + "ab".repeat(32) + "\"}]}";
  }
  static final String U = "https://github.com/rzam33r/roshan-ai-android/releases/download/v24/RoshanAI-24.apk";
  @Test public void tags() {
    assertEquals(24, Update.codeFromTag("v24")); assertEquals(24, Update.codeFromTag("24")); assertEquals(24, Update.codeFromTag("v24-drive"));
    assertEquals(-1, Update.codeFromTag("v3.7")); assertEquals(-1, Update.codeFromTag("latest")); assertEquals(-1, Update.codeFromTag(null));
  }
  @Test public void downloadHosts() {
    assertTrue(Update.downloadOk(U));
    assertTrue(Update.downloadOk("https://objects.githubusercontent.com/x/y?sig=1"));
    assertTrue(Update.downloadOk("https://release-assets.githubusercontent.com/x"));
    assertFalse(Update.downloadOk("https://github.com/someone/else/releases/download/v1/a.apk"));
    assertFalse(Update.downloadOk("http://github.com/rzam33r/roshan-ai-android/releases/download/v24/a.apk"));
    assertFalse(Update.downloadOk("https://evil.com/rzam33r/roshan-ai-android/releases/download/v24/a.apk"));
    assertFalse(Update.downloadOk("https://u@objects.githubusercontent.com/x"));
    assertFalse(Update.downloadOk("https://objects.githubusercontent.com:8443/x"));
    assertFalse(Update.downloadOk(null));
  }
  @Test public void newerReleaseIsOffered() throws Exception {
    JSONObject o = Update.parseLatest(rel("v24", "RoshanAI-24.apk", U, 4400000, ""), 23);
    assertNotNull(o); assertEquals(24, o.getInt("code")); assertEquals(U, o.getString("url")); assertEquals("ab".repeat(32), o.getString("sha"));
    assertEquals("Drive bgridb", o.getString("name")); assertEquals("New:\n- grid", o.getString("notes"));
  }
  @Test public void sameOrOlderIsIgnored() { assertNull(Update.parseLatest(rel("v23", "RoshanAI.apk", U, 10, ""), 23)); assertNull(Update.parseLatest(rel("v20", "RoshanAI.apk", U, 10, ""), 23)); }
  @Test public void badAssetsAreIgnored() {
    assertNull(Update.parseLatest(rel("v24", "Other.apk", U, 10, ""), 23));
    assertNull(Update.parseLatest(rel("v24", "RoshanAI.zip", U, 10, ""), 23));
    assertNull(Update.parseLatest(rel("v24", "RoshanAI.apk", "https://evil.com/a.apk", 10, ""), 23));
    assertNull(Update.parseLatest(rel("v24", "RoshanAI.apk", U, Update.MAX_BYTES + 1, ""), 23));
    assertNull(Update.parseLatest(rel("v24", "RoshanAI.apk", U, 0, ""), 23));
  }
  @Test public void draftsAndPrereleasesIgnored() {
    assertNull(Update.parseLatest(rel("v24", "RoshanAI.apk", U, 10, "").replace("\"prerelease\":false", "\"prerelease\":true"), 23));
    assertNull(Update.parseLatest(rel("v24", "RoshanAI.apk", U, 10, "").replace("\"draft\":false", "\"draft\":true"), 23));
    assertNull(Update.parseLatest("not json", 23));
  }
  @Test public void notesCapped() { assertEquals(600, Update.cleanNotes(new String(new char[900]).replace('\0', 'x')).length()); }
}
