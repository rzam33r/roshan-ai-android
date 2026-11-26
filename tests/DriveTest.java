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
  @Test public void scopeIsMetadataReadOnly() { assertEquals("https://www.googleapis.com/auth/drive.metadata.readonly", Drive.SCOPE); }
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
}
