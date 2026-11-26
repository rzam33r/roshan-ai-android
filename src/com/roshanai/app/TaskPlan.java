package com.roshanai.app;

import com.google.firebase.ai.type.FunctionDeclaration;
import com.google.firebase.ai.type.Schema;
import com.google.firebase.ai.type.Tool;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Task mode: turns the model's proposed tool calls into a checked plan. Model output is untrusted data.
 * Only the tools listed here exist; arguments are validated here; nothing runs until the user taps confirm.
 */
public class TaskPlan {
  public static final int MAX_STEPS = 6;
  public static final int MAX_TEXT = 500;
  public static final String SET_ALARM = "set_alarm", OPEN_APP = "open_app", OPEN_LINK = "open_link", COMPOSE = "compose_message";

  public static final String PLANNER_PROMPT =
      "You turn one request from Roshan into a short list of phone actions by calling the tools provided. "
    + "Call only the tools you need, at most " + MAX_STEPS + " calls. Never invent phone numbers, email addresses or links the request did not give. "
    + "Alarms and reminders can be several. Opening an app, opening a link or preparing a message each open another screen, so use at most ONE of those, and make it the last call. "
    + "A prepared message is only opened for him to read and send himself; you cannot send anything. "
    + "If the request cannot be done with these tools, call none and reply with one short plain sentence saying what is missing. ";

  public static Tool tools() {
    Map<String, Schema> alarm = new HashMap<String, Schema>();
    alarm.put("kind", Schema.enumeration(Arrays.asList("alarm", "reminder"), "alarm rings; reminder shows a notification", false, null));
    alarm.put("when", Schema.str("Local phone time as YYYY-MM-DDTHH:MM (24 hour), in the future"));
    alarm.put("title", Schema.str("Short label"));
    Map<String, Schema> app = new HashMap<String, Schema>();
    app.put("name", Schema.str("Name of an installed app as it appears on the phone"));
    Map<String, Schema> link = new HashMap<String, Schema>();
    link.put("url", Schema.str("A full http or https link given in the request"));
    Map<String, Schema> msg = new HashMap<String, Schema>();
    msg.put("channel", Schema.enumeration(Arrays.asList("sms", "email", "share"), "sms or email to one address, or share to pick an app", false, null));
    msg.put("to", Schema.str("Phone number or email address; omit for share"));
    msg.put("body", Schema.str("The message text"));
    List<FunctionDeclaration> d = new ArrayList<FunctionDeclaration>();
    d.add(new FunctionDeclaration(SET_ALARM, "Set an alarm or reminder on this phone.", alarm, new ArrayList<String>(Arrays.asList("title"))));
    d.add(new FunctionDeclaration(OPEN_APP, "Open an installed app (opens its home screen only).", app, new ArrayList<String>()));
    d.add(new FunctionDeclaration(OPEN_LINK, "Open a web link in the browser.", link, new ArrayList<String>()));
    d.add(new FunctionDeclaration(COMPOSE, "Open a message screen with the text filled in. He must tap Send himself.", msg, new ArrayList<String>(Arrays.asList("to"))));
    return Tool.functionDeclarations(d);
  }

  static String s(JSONObject a, String k, int max) {
    Object v = a == null ? null : a.opt(k);
    if (!(v instanceof String)) return null;
    String t = ((String) v).trim();
    return t.length() > max ? null : t;
  }
  static boolean clean(String t) { return t != null && t.indexOf('\0') < 0; }

  /** calls: [{name, args:{}}]. Returns {ok, steps:[{tool, args, text}], dropped:[reason]}; any invalid call rejects the whole plan (ok=false, error). */
  public static JSONObject validate(JSONArray calls, long nowMs) {
    JSONObject out = new JSONObject();
    try {
      JSONArray steps = new JSONArray(); JSONObject launch = null;
      if (calls == null || calls.length() == 0) return out.put("ok", false).put("error", "no_actions");
      if (calls.length() > MAX_STEPS) return out.put("ok", false).put("error", "too_many_steps");
      for (int i = 0; i < calls.length(); i++) {
        JSONObject c = calls.optJSONObject(i); if (c == null) return out.put("ok", false).put("error", "bad_call");
        String n = c.optString("name", ""); JSONObject a = c.optJSONObject("args"); if (a == null) a = new JSONObject();
        JSONObject st = new JSONObject(); JSONObject args = new JSONObject();
        if (SET_ALARM.equals(n)) {
          String kind = s(a, "kind", 12), when = s(a, "when", 20), title = s(a, "title", 120); if (title == null) title = "";
          if (!"alarm".equals(kind) && !"reminder".equals(kind)) return out.put("ok", false).put("error", "bad_kind");
          if (when == null || !when.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}")) return out.put("ok", false).put("error", "bad_time");
          int y = Integer.parseInt(when.substring(0, 4)), mo = Integer.parseInt(when.substring(5, 7)), d = Integer.parseInt(when.substring(8, 10)), h = Integer.parseInt(when.substring(11, 13)), mi = Integer.parseInt(when.substring(14, 16));
          long at;
          try { at = Sched.epoch(y, mo, d, h, mi); } catch (IllegalArgumentException e) { return out.put("ok", false).put("error", "bad_time"); }
          if (at <= nowMs + 5000) return out.put("ok", false).put("error", "in_the_past");
          if (at > nowMs + 366L * 24 * 3600 * 1000) return out.put("ok", false).put("error", "too_far");
          args.put("kind", kind).put("title", title).put("y", y).put("mo", mo).put("d", d).put("h", h).put("mi", mi);
          st.put("tool", n).put("args", args).put("text", kind + (title.isEmpty() ? "" : " \"" + title + "\"") + " at " + when.replace('T', ' '));
          steps.put(st);
        } else {
          if (launch != null) return out.put("ok", false).put("error", "more_than_one_screen");
          if (OPEN_APP.equals(n)) {
            String nm = s(a, "name", 60); if (nm == null || nm.isEmpty() || !clean(nm)) return out.put("ok", false).put("error", "bad_app");
            args.put("name", nm); st.put("text", "open the app " + nm);
          } else if (OPEN_LINK.equals(n)) {
            String u = s(a, "url", 2000); if (u == null || !Launcher.webOk(u)) return out.put("ok", false).put("error", "bad_url");
            args.put("url", u); st.put("text", "open " + u);
          } else if (COMPOSE.equals(n)) {
            String ch = s(a, "channel", 8), to = s(a, "to", 120), body = s(a, "body", MAX_TEXT); if (to == null) to = ""; if (body == null || body.isEmpty() || !clean(body)) return out.put("ok", false).put("error", "bad_body");
            if ("sms".equals(ch)) { if (!Launcher.validPhone(to)) return out.put("ok", false).put("error", "bad_number"); }
            else if ("email".equals(ch)) { if (!Launcher.validEmail(to)) return out.put("ok", false).put("error", "bad_email"); }
            else if ("share".equals(ch)) { to = ""; }
            else return out.put("ok", false).put("error", "bad_channel");
            args.put("channel", ch).put("to", to).put("body", body);
            st.put("text", "prepare " + ("share".equals(ch) ? "a share" : ch) + (to.isEmpty() ? "" : " to " + to) + ": \"" + body + "\" (you tap Send)");
          } else return out.put("ok", false).put("error", "unknown_tool");
          st.put("tool", n).put("args", args); launch = st;
        }
      }
      if (launch != null) steps.put(launch);   // screen-opening step always runs last
      return out.put("ok", true).put("steps", steps);
    } catch (Exception e) { try { return out.put("ok", false).put("error", "error_" + e.getClass().getSimpleName()); } catch (Exception x) { return out; } }
  }
}
