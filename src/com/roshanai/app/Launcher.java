package com.roshanai.app;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.CalendarContract;
import android.webkit.JavascriptInterface;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Open installed apps by name and hand off to other apps. Matching is local; nothing here goes to Gemini. */
public class Launcher {
  public static String norm(String s) {
    return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
  }

  /** apps: {label, package}. Returns {exact:boolean, items:[{label,pkg}]} (max 6). exact=true only for one whole-name match. */
  public static JSONObject rank(List<String[]> apps, String query) {
    JSONObject out = new JSONObject();
    try {
      String q = norm(query);
      List<String[]> ex = new ArrayList<String[]>(), pre = new ArrayList<String[]>(), con = new ArrayList<String[]>();
      if (q.length() > 0) for (String[] a : apps) {
        String l = norm(a[0]);
        if (l.equals(q)) ex.add(a); else if (l.startsWith(q)) pre.add(a); else if (l.contains(q)) con.add(a);
      }
      List<String[]> res = new ArrayList<String[]>();
      boolean exact = ex.size() == 1;
      if (!ex.isEmpty()) res.addAll(ex); else { res.addAll(pre); res.addAll(con); }
      JSONArray arr = new JSONArray();
      for (int i = 0; i < res.size() && i < 6; i++) arr.put(new JSONObject().put("label", res.get(i)[0]).put("pkg", res.get(i)[1]));
      out.put("exact", exact).put("items", arr).put("total", res.size());
    } catch (Exception e) { }
    return out;
  }

  public static boolean validPhone(String n) { return n != null && n.matches("\\+?[0-9][0-9 ()\\-]{2,19}"); }
  public static boolean validEmail(String e) { return e != null && e.matches("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}"); }
  public static boolean webOk(String u) {
    try { Uri x = Uri.parse(u); String s = x.getScheme(); return ("http".equals(s) || "https".equals(s)) && x.getHost() != null && x.getHost().length() > 0; }
    catch (Exception e) { return false; }
  }

  public static class Bridge {
    private final Context c;
    public Bridge(Context c) { this.c = c; }

    private JSONObject res(boolean ok, String err) {
      JSONObject o = new JSONObject();
      try { o.put("ok", ok); if (err != null) o.put("error", err); } catch (Exception e) { }
      return o;
    }

    private List<ResolveInfo> launchables() {
      Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
      return c.getPackageManager().queryIntentActivities(i, 0);
    }

    /** Read at the moment of the command; never stored, never sent anywhere. */
    @JavascriptInterface public String find(String name) {
      List<String[]> apps = new ArrayList<String[]>();
      java.util.HashSet<String> seen = new java.util.HashSet<String>();
      for (ResolveInfo r : launchables()) {
        String pkg = r.activityInfo.packageName;
        if (pkg.equals(c.getPackageName()) || !seen.add(pkg)) continue;
        apps.add(new String[] { String.valueOf(r.loadLabel(c.getPackageManager())), pkg });
      }
      JSONObject o = rank(apps, name);
      try { o.put("ok", true).put("seen", apps.size()); } catch (Exception e) { }
      return o.toString();
    }

    /** Only packages that currently have a launcher entry can be opened from the page. */
    @JavascriptInterface public String open(String pkg) {
      try {
        for (ResolveInfo r : launchables()) {
          if (r.activityInfo.packageName.equals(pkg) && !pkg.equals(c.getPackageName())) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(pkg, r.activityInfo.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return res(true, null).toString();
          }
        }
        return res(false, "not_found").toString();
      } catch (SecurityException e) { return res(false, "os_refused").toString();
      } catch (Throwable e) { return res(false, "error_" + e.getClass().getSimpleName()).toString(); }
    }

    private String go(Intent i) {
      try { c.startActivity(i); return null; }
      catch (ActivityNotFoundException e) { return "no_app"; }
      catch (SecurityException e) { return "os_refused"; }
      catch (Throwable e) { return "error_" + e.getClass().getSimpleName(); }
    }

    /** kind: youtube|web|dial|sms|email|map|share|calendar. Opens the other app's own screen; the user finishes there. */
    @JavascriptInterface public String handoff(String kind, String a, String b) {
      if (a == null) a = ""; if (b == null) b = "";
      Intent i; String via = null;
      try {
        if ("youtube".equals(kind)) {
          if (a.trim().isEmpty()) return res(false, "empty").toString();
          Intent s = new Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
          String e = go(s);
          if (e == null) return res(true, null).put("via", "youtube_app").toString();
          i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(a))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
          e = go(i);
          return e == null ? res(true, null).put("via", "web_link").toString() : res(false, e).toString();
        } else if ("web".equals(kind)) {
          if (!webOk(a)) return res(false, "bad_url").toString();
          i = new Intent(Intent.ACTION_VIEW, Uri.parse(a));
        } else if ("dial".equals(kind)) {
          if (!validPhone(a)) return res(false, "bad_number").toString();
          i = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(a.trim())));
        } else if ("sms".equals(kind)) {
          if (!validPhone(a)) return res(false, "bad_number").toString();
          i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(a.trim()))).putExtra("sms_body", b);
        } else if ("email".equals(kind)) {
          if (!validEmail(a)) return res(false, "bad_email").toString();
          i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + a.trim())).putExtra(Intent.EXTRA_TEXT, b);
        } else if ("map".equals(kind)) {
          if (a.trim().isEmpty()) return res(false, "empty").toString();
          i = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(a)));
        } else if ("share".equals(kind)) {
          if (a.trim().isEmpty()) return res(false, "empty").toString();
          i = Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, a), "Share");
        } else if ("calendar".equals(kind)) {
          if (a.trim().isEmpty()) return res(false, "empty").toString();
          i = new Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, a);
        } else return res(false, "bad_kind").toString();
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String e = go(i);
        return e == null ? res(true, null).toString() : res(false, e).toString();
      } catch (Exception e) { return res(false, "error_" + e.getClass().getSimpleName()).toString(); }
    }
  }
}
