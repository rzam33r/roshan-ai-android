package com.roshanai.app;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.Calendar;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class TaskPlanTest {
  static JSONObject call(String n, String json) throws Exception { return new JSONObject().put("name", n).put("args", new JSONObject(json)); }
  static String fut(int mins) { Calendar c = Calendar.getInstance(); c.add(Calendar.MINUTE, mins); return String.format("%04d-%02d-%02dT%02d:%02d", c.get(1), c.get(2) + 1, c.get(5), c.get(11), c.get(12)); }
  static JSONObject v(JSONObject... cs) { JSONArray a = new JSONArray(); for (JSONObject c : cs) a.put(c); return TaskPlan.validate(a, System.currentTimeMillis()); }
  static String err(JSONObject... cs) throws Exception { JSONObject r = v(cs); assertFalse(r.toString(), r.getBoolean("ok")); return r.getString("error"); }

  @Test public void toolsDeclared() { assertNotNull(TaskPlan.tools()); }
  @Test public void goodPlanLaunchLast() throws Exception {
    JSONObject r = v(call("open_app", "{\"name\":\"YouTube\"}"), call("set_alarm", "{\"kind\":\"alarm\",\"when\":\"" + fut(90) + "\",\"title\":\"x\"}"));
    assertTrue(r.toString(), r.getBoolean("ok"));
    JSONArray s = r.getJSONArray("steps");
    assertEquals("set_alarm", s.getJSONObject(0).getString("tool")); assertEquals("open_app", s.getJSONObject(1).getString("tool"));
  }
  @Test public void rejects() throws Exception {
    assertEquals("no_actions", err());
    assertEquals("unknown_tool", err(call("delete_all", "{}")));
    assertEquals("unknown_tool", err(call("send_sms", "{\"to\":\"+1555\",\"body\":\"hi\"}")));
    assertEquals("in_the_past", err(call("set_alarm", "{\"kind\":\"alarm\",\"when\":\"2020-01-01T10:00\"}")));
    assertEquals("bad_time", err(call("set_alarm", "{\"kind\":\"alarm\",\"when\":\"tomorrow\"}")));
    assertEquals("bad_time", err(call("set_alarm", "{\"kind\":\"alarm\",\"when\":\"2030-02-31T10:00\"}")));
    assertEquals("too_far", err(call("set_alarm", "{\"kind\":\"alarm\",\"when\":\"2099-01-01T10:00\"}")));
    assertEquals("bad_kind", err(call("set_alarm", "{\"kind\":\"call\",\"when\":\"" + fut(60) + "\"}")));
    assertEquals("bad_url", err(call("open_link", "{\"url\":\"javascript:alert(1)\"}")));
    assertEquals("bad_url", err(call("open_link", "{\"url\":\"intent://x#Intent;end\"}")));
    assertEquals("bad_url", err(call("open_link", "{\"url\":\"file:///data/x\"}")));
    assertEquals("bad_number", err(call("compose_message", "{\"channel\":\"sms\",\"to\":\"abc\",\"body\":\"hi\"}")));
    assertEquals("bad_email", err(call("compose_message", "{\"channel\":\"email\",\"to\":\"nope\",\"body\":\"hi\"}")));
    assertEquals("bad_body", err(call("compose_message", "{\"channel\":\"share\",\"body\":\"\"}")));
    assertEquals("bad_channel", err(call("compose_message", "{\"channel\":\"call\",\"to\":\"+971561935270\",\"body\":\"hi\"}")));
    assertEquals("bad_app", err(call("open_app", "{\"name\":123}")));
    assertEquals("more_than_one_screen", err(call("open_app", "{\"name\":\"A\"}"), call("open_link", "{\"url\":\"https://a.com\"}")));
    JSONObject[] many = new JSONObject[7]; for (int i = 0; i < 7; i++) many[i] = call("set_alarm", "{\"kind\":\"alarm\",\"when\":\"" + fut(60 + i) + "\"}");
    assertEquals("too_many_steps", err(many));
    StringBuilder big = new StringBuilder(); for (int i = 0; i < 600; i++) big.append('a');
    assertEquals("bad_body", err(call("compose_message", "{\"channel\":\"share\",\"body\":\"" + big + "\"}")));
  }
  @Test public void composeNeverSends() throws Exception {
    JSONObject r = v(call("compose_message", "{\"channel\":\"sms\",\"to\":\"+971561935270\",\"body\":\"hi\"}"));
    assertTrue(r.getBoolean("ok")); assertTrue(r.getJSONArray("steps").getJSONObject(0).getString("text").contains("you tap Send"));
  }
  @Test public void timezoneAndRelativeDates() throws Exception {
    java.util.TimeZone old = java.util.TimeZone.getDefault();
    try {
      java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Dubai"));
      long now = java.time.ZonedDateTime.of(2026, 11, 26, 9, 0, 0, 0, java.time.ZoneId.of("Asia/Dubai")).toInstant().toEpochMilli();
      JSONArray a = new JSONArray().put(call("set_alarm", "{\"kind\":\"alarm\",\"when\":\"2026-11-27T07:00\"}"));
      assertTrue(TaskPlan.validate(a, now).getBoolean("ok"));
      long e = Sched.epoch(2026, 11, 27, 7, 0);
      assertEquals(java.time.ZonedDateTime.of(2026, 11, 27, 7, 0, 0, 0, java.time.ZoneId.of("Asia/Dubai")).toInstant().toEpochMilli(), e);   // 07:00 means Dubai local
      assertEquals("in_the_past", TaskPlan.validate(new JSONArray().put(call("set_alarm", "{\"kind\":\"alarm\",\"when\":\"2026-11-26T08:59\"}")), now).getString("error"));
      java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Los_Angeles"));
      long nowLa = java.time.ZonedDateTime.of(2026, 3, 7, 12, 0, 0, 0, java.time.ZoneId.of("America/Los_Angeles")).toInstant().toEpochMilli();
      // 2026-03-08 02:30 does not exist in Los Angeles (spring forward): rejected, not shifted silently
      assertEquals("bad_time", TaskPlan.validate(new JSONArray().put(call("set_alarm", "{\"kind\":\"alarm\",\"when\":\"2026-03-08T02:30\"}")), nowLa).getString("error"));
    } finally { java.util.TimeZone.setDefault(old); }
  }
  @Test public void htmlGatesEffectsBehindConfirmTap() throws Exception {
    String h = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("assets/app.html")), "UTF-8");
    int i = h.indexOf("function execPlan"); assertTrue(i > 0);
    // execPlan is only called from the CONFIRM click handler
    int calls = 0, at = 0; while ((at = h.indexOf("execPlan(", at)) >= 0) { calls++; at++; }
    assertEquals(2, calls);   // the definition and the one click handler
    assertTrue(h.contains("ok.addEventListener('click',function(){if(taskPlan!==o)return;taskPlan=null;"));
    assertFalse(h.contains("eval("));
  }
}
