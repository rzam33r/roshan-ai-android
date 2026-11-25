package com.roshanai.app;

import static org.junit.Assert.*;
import android.app.AlarmManager;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowNotificationManager;
import java.util.Calendar;
import java.util.TimeZone;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class SchedTest {
  private Application app() { return RuntimeEnvironment.getApplication(); }
  private String add(MainActivity a, String kind, String title, int mins) throws Exception {
    Calendar c = Calendar.getInstance(); c.add(Calendar.MINUTE, mins);
    return a.new SchedBridge().add(kind, title, c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
  }
  private MainActivity act() { org.robolectric.Shadows.shadowOf((AlarmManager) app().getSystemService(Context.ALARM_SERVICE)).setCanScheduleExactAlarms(true); org.robolectric.Shadows.shadowOf(app()).grantPermissions("android.permission.POST_NOTIFICATIONS"); return Robolectric.buildActivity(MainActivity.class).create().get(); }

  @Test public void addIsAcceptedByOsAndPersisted() throws Exception {
    MainActivity a = act();
    org.robolectric.Shadows.shadowOf(app()).grantPermissions("android.permission.POST_NOTIFICATIONS");
    JSONObject r = new JSONObject(add(a, "alarm", "wake", 30));
    assertTrue(r.toString(), r.getBoolean("ok"));
    AlarmManager am = (AlarmManager) app().getSystemService(Context.ALARM_SERVICE);
    ShadowAlarmManager sam = Shadows.shadowOf(am);
    assertNotNull("OS alarm armed", sam.peekNextScheduledAlarm());
    assertEquals(1, Sched.load(app()).length());
  }

  @Test public void pastTimeIsRefusedAndNothingScheduled() throws Exception {
    MainActivity a = act();
    JSONObject r = new JSONObject(add(a, "reminder", "x", -5));
    assertFalse(r.getBoolean("ok")); assertEquals("in_the_past", r.getString("error"));
    assertEquals(0, Sched.load(app()).length());
  }

  @Test public void invalidTimeRefused() throws Exception {
    MainActivity a = act();
    JSONObject r = new JSONObject(a.new SchedBridge().add("alarm", "", 2026, 2, 30, 8, 0));
    assertFalse(r.getBoolean("ok")); assertEquals("invalid_time", r.getString("error"));
  }

  @Test public void notificationsOffRefusesWithoutScheduling() throws Exception {
    MainActivity a = act();
    NotificationManager nm = (NotificationManager) app().getSystemService(Context.NOTIFICATION_SERVICE);
    Shadows.shadowOf(nm).setNotificationsEnabled(false);
    JSONObject r = new JSONObject(add(a, "alarm", "x", 30));
    assertFalse(r.getBoolean("ok")); assertEquals("notifications_off", r.getString("error"));
    assertEquals(0, Sched.load(app()).length());
  }

  @Test public void fireAlarmPostsInsistentNotificationAndDismissClearsIt() throws Exception {
    MainActivity a = act();
    JSONObject r = new JSONObject(add(a, "alarm", "wake up", 10));
    assertTrue(r.toString(), r.getBoolean("ok"));
    String id = r.getString("id");
    new AlarmReceiver().onReceive(app(), new Intent(Sched.ACT_FIRE).putExtra("id", id));
    NotificationManager nm = (NotificationManager) app().getSystemService(Context.NOTIFICATION_SERVICE);
    ShadowNotificationManager snm = Shadows.shadowOf(nm);
    assertEquals(1, snm.size());
    Notification n = snm.getAllNotifications().get(0);
    assertTrue("insistent flag", (n.flags & Notification.FLAG_INSISTENT) != 0);
    assertEquals(Sched.CH_ALARM, n.getChannelId());
    assertEquals(0, Sched.load(app()).length()); // fired items leave the list
    new AlarmReceiver().onReceive(app(), new Intent(Sched.ACT_DISMISS).putExtra("nid", id.hashCode()));
    assertEquals(0, snm.size());
  }

  @Test public void reminderIsNotInsistent() throws Exception {
    MainActivity a = act();
    JSONObject r = new JSONObject(add(a, "reminder", "call mom", 10));
    new AlarmReceiver().onReceive(app(), new Intent(Sched.ACT_FIRE).putExtra("id", r.getString("id")));
    NotificationManager nm = (NotificationManager) app().getSystemService(Context.NOTIFICATION_SERVICE);
    Notification n = Shadows.shadowOf(nm).getAllNotifications().get(0);
    assertEquals(0, n.flags & Notification.FLAG_INSISTENT);
    assertEquals(Sched.CH_REM, n.getChannelId());
  }

  @Test public void cancelRemovesFromStoreAndOs() throws Exception {
    MainActivity a = act();
    JSONObject r = new JSONObject(add(a, "alarm", "", 30));
    assertTrue(a.new SchedBridge().cancel(r.getString("id")));
    assertEquals(0, Sched.load(app()).length());
    AlarmManager am = (AlarmManager) app().getSystemService(Context.ALARM_SERVICE);
    assertNull(Shadows.shadowOf(am).peekNextScheduledAlarm());
    assertFalse(a.new SchedBridge().cancel("nope"));
  }

  @Test public void bootRearmsFutureItemsAndMarksPastAsMissed() throws Exception {
    MainActivity a = act();
    JSONObject f = new JSONObject(add(a, "alarm", "future", 60));
    // inject a past item directly into the store (phone was off when it was due)
    JSONArray arr = Sched.load(app());
    Calendar p = Calendar.getInstance(); p.add(Calendar.MINUTE, -20);
    arr.put(new JSONObject().put("id", "past1").put("kind", "reminder").put("title", "old").put("y", p.get(Calendar.YEAR)).put("mo", p.get(Calendar.MONTH) + 1).put("d", p.get(Calendar.DAY_OF_MONTH)).put("h", p.get(Calendar.HOUR_OF_DAY)).put("mi", p.get(Calendar.MINUTE)));
    Sched.save(app(), arr);
    AlarmManager am = (AlarmManager) app().getSystemService(Context.ALARM_SERVICE);
    Shadows.shadowOf(am).getScheduledAlarms().clear(); // simulate reboot wiping alarms
    new AlarmReceiver().onReceive(app(), new Intent(Intent.ACTION_BOOT_COMPLETED));
    assertNotNull("future re-armed", Shadows.shadowOf(am).peekNextScheduledAlarm());
    assertEquals(1, Sched.load(app()).length());
    NotificationManager nm = (NotificationManager) app().getSystemService(Context.NOTIFICATION_SERVICE);
    Notification n = Shadows.shadowOf(nm).getAllNotifications().get(0);
    assertTrue(n.extras.getCharSequence(Notification.EXTRA_TEXT).toString().startsWith("Missed:"));
  }

  @Test public void timezoneChangeKeepsWallClock() throws Exception {
    MainActivity a = act();
    TimeZone orig = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("Asia/Dubai"));
      JSONObject r = new JSONObject(add(a, "alarm", "", 120));
      long at1 = r.getLong("at");
      TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"));
      new AlarmReceiver().onReceive(app(), new Intent(Intent.ACTION_TIMEZONE_CHANGED));
      JSONObject it = Sched.load(app()).getJSONObject(0);
      long at2 = Sched.epoch(it.getInt("y"), it.getInt("mo"), it.getInt("d"), it.getInt("h"), it.getInt("mi"));
      assertNotEquals("instant moved with tz (wall-clock semantics)", at1, at2);
      Calendar c1 = Calendar.getInstance(TimeZone.getTimeZone("Asia/Dubai")); c1.setTimeInMillis(at1);
      Calendar c2 = Calendar.getInstance(TimeZone.getTimeZone("Europe/London")); c2.setTimeInMillis(at2);
      assertEquals(c1.get(Calendar.HOUR_OF_DAY), c2.get(Calendar.HOUR_OF_DAY));
    } finally { TimeZone.setDefault(orig); }
  }
}
