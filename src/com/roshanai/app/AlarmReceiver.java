package com.roshanai.app;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

public class AlarmReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context c, Intent i) {
    String a = i.getAction();
    if (Sched.ACT_DISMISS.equals(a)) {
      ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(i.getIntExtra("nid", 0));
      return;
    }
    if (Sched.ACT_FIRE.equals(a)) {
      String id = i.getStringExtra("id");
      JSONArray in = Sched.load(c), out = new JSONArray();
      JSONObject hit = null;
      try {
        for (int k = 0; k < in.length(); k++) {
          JSONObject it = in.getJSONObject(k);
          if (id != null && id.equals(it.optString("id"))) hit = it; else out.put(it);
        }
      } catch (Exception e) { }
      if (hit != null) { Sched.save(c, out); Sched.notifyFire(c, hit, false); }
      return;
    }
    // BOOT_COMPLETED, LOCKED_BOOT, TIME_SET, TIMEZONE_CHANGED, MY_PACKAGE_REPLACED
    Sched.rearmAll(c);
  }
}
