package com.roshanai.app;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class DriveTest {
  @Test public void scopeIsFullDrive() { assertEquals("https://www.googleapis.com/auth/drive", Drive.SCOPE); }
  @Test public void queryEscapesQuotesAndBackslashes() {
    assertEquals("name contains 'it\\'s' and trashed = false", Drive.buildQuery("it's"));
    assertEquals("name contains 'a\\\\b' and trashed = false", Drive.buildQuery("a\\b"));
    assertEquals("name contains '\\' or name contains \\'x' and trashed = false", Drive.buildQuery("' or name contains 'x"));
  }
  @Test public void emptyAndControlInput() {
    assertNull(Drive.buildQuery(null)); assertNull(Drive.buildQuery("   ")); assertNull(Drive.buildQuery("\n\t"));
    assertEquals("name contains 'a b' and trashed = false", Drive.buildQuery("a\nb"));
  }
  @Test public void termIsCapped() { assertEquals(Drive.MAX_Q, Drive.cleanTerm(new String(new char[500]).replace('\0', 'x')).length()); }
  @Test public void linkAllowList() {
    assertTrue(Drive.linkOk("https://drive.google.com/file/d/1/view"));
    assertTrue(Drive.linkOk("https://docs.google.com/document/d/1/edit"));
    assertFalse(Drive.linkOk("http://drive.google.com/x"));
    assertFalse(Drive.linkOk("https://evil.com/x"));
    assertFalse(Drive.linkOk("https://drive.google.com.evil.com/x"));
    assertFalse(Drive.linkOk("https://u@drive.google.com/x"));
    assertFalse(Drive.linkOk("intent://x")); assertFalse(Drive.linkOk(null));
  }
  @Test public void parseKeepsOnlyAllowedFieldsAndLinks() throws Exception {
    String body = "{\"files\":[{\"id\":\"SECRET\",\"name\":\"a.pdf\",\"mimeType\":\"application/pdf\",\"modifiedTime\":\"2026-01-02T03:04:05.000Z\",\"webViewLink\":\"https://drive.google.com/file/d/1/view\",\"owners\":[{\"emailAddress\":\"x@y.z\"}]},"
      + "{\"name\":\"bad\",\"webViewLink\":\"https://evil.com/\"},{\"name\":\"\",\"webViewLink\":\"https://drive.google.com/x\"}]}";
    JSONArray a = Drive.parse(body);
    assertEquals(1, a.length());
    JSONObject o = a.getJSONObject(0);
    assertEquals("a.pdf", o.getString("name"));
    assertFalse(a.toString().contains("SECRET")); assertFalse(a.toString().contains("x@y.z"));
    assertEquals(4, o.length());
  }
  @Test public void parseCapsItemsAndSurvivesGarbage() throws Exception {
    StringBuilder b = new StringBuilder("{\"files\":[");
    for (int i = 0; i < 30; i++) b.append(i > 0 ? "," : "").append("{\"name\":\"f").append(i).append("\",\"webViewLink\":\"https://drive.google.com/file/d/").append(i).append("/view\"}");
    assertEquals(Drive.MAX_ITEMS, Drive.parse(b.append("]}").toString()).length());
    assertEquals(0, Drive.parse("not json").length()); assertEquals(0, Drive.parse("{}").length());
  }

  @Test public void idsAreValidated() {
    assertTrue(Drive.validId("root")); assertTrue(Drive.validId("1AbC_-9xyz"));
    assertFalse(Drive.validId(null)); assertFalse(Drive.validId("")); assertFalse(Drive.validId("a")); assertFalse(Drive.validId("x' or '1'='1")); assertFalse(Drive.validId("../x/yyyy")); assertFalse(Drive.validId("abc def ghi"));
    assertNull(Drive.listQuery("a' or 'b")); assertEquals("'root' in parents and trashed = false", Drive.listQuery("root"));
  }
  @Test public void nameQueryEscapes() {
    assertEquals("name = 'it\\'s' and 'root' in parents and trashed = false", Drive.nameQuery("it's", "root"));
    assertNull(Drive.nameQuery("x", "bad id"));
  }
  @Test public void namesAreCleaned() {
    assertNull(Drive.cleanName("..")); assertNull(Drive.cleanName("  ")); assertEquals("a b", Drive.cleanName("a/b")); assertEquals("a b", Drive.cleanName("a\\b"));
    assertEquals("drive-file", Drive.safeFileName(null)); assertEquals("a_b_.pdf", Drive.safeFileName("a:b*.pdf").replace(" ", "_").replace("__", "_"));
    assertTrue(Drive.safeFileName(new String(new char[500]).replace('\0', 'x')).length() <= 120);
  }
  @Test public void uniqueNamesNeverReplace() {
    java.util.Set<String> t = new java.util.HashSet<String>(); t.add("a.pdf"); t.add("a (2).pdf");
    assertEquals("a (3).pdf", Drive.uniqueName("a.pdf", t)); assertEquals("b.pdf", Drive.uniqueName("b.pdf", t));
    t.add("noext"); assertEquals("noext (2)", Drive.uniqueName("noext", t));
  }
  @Test public void exportsMatchGoogleTable() {
    assertEquals(".docx", Drive.exportExt("application/vnd.google-apps.document")); assertEquals(".xlsx", Drive.exportExt("application/vnd.google-apps.spreadsheet"));
    assertEquals(".pptx", Drive.exportExt("application/vnd.google-apps.presentation")); assertEquals(".pdf", Drive.exportExt("application/vnd.google-apps.drawing"));
    assertNull(Drive.exportMime("application/vnd.google-apps.form")); assertNull(Drive.exportMime("application/pdf"));
    assertTrue(Drive.isGoogleNative("application/vnd.google-apps.form")); assertFalse(Drive.isGoogleNative("application/pdf"));
  }
  @Test public void createJsonUsesOneParent() throws Exception {
    JSONObject o = new JSONObject(Drive.createJson("My \"x\"", "root", true));
    assertEquals("My \"x\"", o.getString("name")); assertEquals(1, o.getJSONArray("parents").length()); assertEquals(Drive.FOLDER, o.getString("mimeType"));
    assertFalse(new JSONObject(Drive.createJson("f.txt", "abc12345", false)).has("mimeType"));
  }
  @Test public void parseListKeepsIdsOnlyWhenValidAndSkipsSharedDrives() throws Exception {
    String body = "{\"files\":[{\"id\":\"abc12345\",\"name\":\"F\",\"mimeType\":\"application/vnd.google-apps.folder\",\"shared\":true,\"owners\":[\"x\"]},"
      + "{\"id\":\"bad id\",\"name\":\"X\"},{\"id\":\"sd123456\",\"name\":\"SD\",\"driveId\":\"d1\"},{\"id\":\"file12345\",\"name\":\"a.pdf\",\"size\":\"1234\",\"mimeType\":\"application/pdf\",\"webViewLink\":\"https://evil.com/\"}]}";
    JSONArray a = Drive.parseList(body);
    assertEquals(2, a.length());
    assertTrue(a.getJSONObject(0).getBoolean("folder")); assertTrue(a.getJSONObject(0).getBoolean("shared")); assertFalse(a.getJSONObject(0).has("owners"));
    assertEquals(1234, a.getJSONObject(1).getLong("size")); assertEquals("", a.getJSONObject(1).getString("link"));
  }
  @Test public void accessNeverAssumesPrivate() throws Exception {
    assertEquals("private", Drive.access("{\"name\":\"a\",\"shared\":false,\"permissions\":[{\"type\":\"user\",\"role\":\"owner\"}]}").getString("level"));
    assertEquals("shared", Drive.access("{\"shared\":true,\"permissions\":[{\"type\":\"anyone\",\"role\":\"reader\"}]}").getString("level"));
    assertEquals("shared", Drive.access("{\"shared\":true,\"permissions\":[{\"type\":\"user\"},{\"type\":\"user\"}]}").getString("level"));
    assertEquals("unknown", Drive.access("{\"shared\":true}").getString("level"));
    assertEquals("unknown", Drive.access("{\"shared\":false}").getString("level"));
    assertEquals("unknown", Drive.access("not json").getString("level"));
    assertEquals("unsupported", Drive.access("{\"driveId\":\"x\",\"shared\":false}").getString("level"));
  }
  /** The app must never contain delete, trash, empty-trash or sharing calls. */
  @Test public void noDeleteTrashOrShareCallsInCode() throws Exception {
    String[] files = { "src/com/roshanai/app/Drive.java", "src/com/roshanai/app/MainActivity.java", "assets/app.html" };
    String[] banned = { "/permissions", "emptyTrash", "\"DELETE\"", "'DELETE'", "files.delete", "trashed\":true", "\"trashed\", true", "AndroidDrive.delete", "AndroidDrive.share", "AndroidDrive.trash", "webContentLink" };
    for (String f : files) {
      String src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(f)), "UTF-8");
      for (String b : banned) assertFalse(f + " contains " + b, src.contains(b));
    }
  }
}
