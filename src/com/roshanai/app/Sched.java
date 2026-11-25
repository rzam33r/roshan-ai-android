package com.roshanai.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

/** Local, persisted alarms and reminders. Wall-clock semantics: "7:00" stays 7:00 local after a timezone change. */
final class Sched {
  static final String PREFS = "sched", KEY = "items";
  static final String CH_ALARM = "alarm_v1", CH_REM = "reminder_v1";
  static final String ACT_FIRE = "com.roshanai.app.FIRE", ACT_DISMISS = "com.roshanai.app.DISMISS";

  static synchronized JSONArray load(Context c) {
    try { return new JSONArray(c.getSharedPreferences(PREFS, 0).getString(KEY, "[]")); }
    catch (Exception e) { return new JSONArray(); }
  }
  static synchronized boolean save(Context c, JSONArray a) {
    return c.getSharedPreferences(PREFS, 0).edit().putString(KEY, a.toString()).commit();
  }

  static long epoch(int y, int mo, int d, int h, int mi) {
    Calendar cal = Calendar.getInstance();
    cal.setLenient(false);
    cal.clear();
    cal.set(y, mo - 1, d, h, mi, 0);
    return cal.getTimeInMillis(); // throws IllegalArgumentException on invalid wall time
  }

  static void ensureChannels(Context c) {
    if (Build.VERSION.SDK_INT < 26) return;
    NotificationManager nm = c.getSystemService(NotificationManager.class);
    Uri snd = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
    if (snd == null) snd = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
    NotificationChannel a = new NotificationChannel(CH_ALARM, "Alarms", NotificationManager.IMPORTANCE_HIGH);
    a.setSound(snd, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
    a.enableVibration(true);
    a.setVibrationPattern(new long[]{0, 600, 400, 600, 400, 600});
    a.setBypassDnd(false);
    nm.createNotificationChannel(a);
    NotificationChannel r = new NotificationChannel(CH_REM, "Reminders", NotificationManager.IMPORTANCE_HIGH);
    r.enableVibration(true);
    nm.createNotificationChannel(r);
  }

  static PendingIntent firePI(Context c, String id) {
    Intent i = new Intent(c, AlarmReceiver.class).setAction(ACT_FIRE).putExtra("id", id);
    i.setData(Uri.parse("roshanai://sched/" + id));
    return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }
  static PendingIntent showPI(Context c) {
    Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
    return PendingIntent.getActivity(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  /** Arms with the OS. setAlarmClock is exempt from the exact-alarm permission and survives Doze. */
  static void arm(Context c, JSONObject it) throws Exception {
    AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
    long at = epoch(it.getInt("y"), it.getInt("mo"), it.getInt("d"), it.getInt("h"), it.getInt("mi"));
    it.put("at", at);
    am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, showPI(c)), firePI(c, it.getString("id")));
  }

  static void disarm(Context c, String id) {
    AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
    PendingIntent p = firePI(c, id);
    am.cancel(p);
    p.cancel();
  }

  static boolean notificationsOn(Context c) {
    NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    if (!nm.areNotificationsEnabled()) return false;
    if (Build.VERSION.SDK_INT >= 26) {
      NotificationChannel ch = nm.getNotificationChannel(CH_ALARM);
      if (ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
    }
    return true;
  }

  static void notifyFire(Context c, JSONObject it, boolean missed) {
    ensureChannels(c);
    boolean alarm = "alarm".equals(it.optString("kind"));
    String title = alarm ? "Alarm" : "Reminder";
    String text = it.optString("title", "");
    if (text.length() == 0) text = alarm ? "Time's up" : "Reminder";
    if (missed) text = "Missed: " + text;
    Notification.Builder b = Build.VERSION.SDK_INT >= 26
        ? new Notification.Builder(c, alarm ? CH_ALARM : CH_REM) : new Notification.Builder(c);
    b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(title).setContentText(text)
        .setStyle(new Notification.BigTextStyle().bigText(text))
        .setContentIntent(showPI(c)).setAutoCancel(true)
        .setCategory(alarm ? Notification.CATEGORY_ALARM : Notification.CATEGORY_REMINDER)
        .setWhen(System.currentTimeMillis());
    if (alarm && !missed) {
      Intent d = new Intent(c, AlarmReceiver.class).setAction(ACT_DISMISS).putExtra("nid", it.optString("id").hashCode());
      d.setData(Uri.parse("roshanai://dismiss/" + it.optString("id")));
      b.addAction(new Notification.Action.Builder(null, "Dismiss",
          PendingIntent.getBroadcast(c, 2, d, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build());
    }
    if (Build.VERSION.SDK_INT < 26) {
      b.setPriority(Notification.PRIORITY_MAX).setDefaults(Notification.DEFAULT_ALL);
      if (alarm) b.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioAttributes.USAGE_ALARM == 4 ? android.media.AudioManager.STREAM_ALARM : 0);
    }
    Notification n = b.build();
    if (alarm && !missed) n.flags |= Notification.FLAG_INSISTENT; // repeat sound until dismissed
    ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(it.optString("id").hashCode(), n);
  }

  /** Re-arm everything after reboot / time change / timezone change / app update. Past items fire once as "Missed". */
  static void rearmAll(Context c) {
    JSONArray in = load(c), out = new JSONArray();
    long now = System.currentTimeMillis();
    for (int i = 0; i < in.length(); i++) {
      try {
        JSONObject it = in.getJSONObject(i);
        long at = epoch(it.getInt("y"), it.getInt("mo"), it.getInt("d"), it.getInt("h"), it.getInt("mi"));
        if (at <= now) { notifyFire(c, it, true); continue; }
        arm(c, it);
        out.put(it);
      } catch (Exception e) { /* drop corrupt entry */ }
    }
    save(c, out);
  }
}
