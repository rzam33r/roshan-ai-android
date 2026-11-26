package com.roshanai.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.ai.FirebaseAI;
import com.google.firebase.ai.GenerativeModel;
import com.google.firebase.ai.java.GenerativeModelFutures;
import com.google.firebase.ai.type.Content;
import com.google.firebase.ai.type.GenerateContentResponse;
import com.google.firebase.ai.type.GenerativeBackend;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory;

import android.content.Intent;
import android.speech.RecognitionListener;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.Executor;

public class MainActivity extends Activity {
  private WebView wv;
  private GenerativeModelFutures model, model2, planner;
  private SpeechRecognizer sr;
  private String initError = null;
  private byte[] attBytes; private String attMime;
  static final int MAX_ATTACH = 10 * 1024 * 1024;   // request limit is 20 MB incl. base64 growth
  static final String[] ATTACH_TYPES = {"image/png", "image/jpeg", "image/webp", "application/pdf", "text/plain"};

  public class ClipboardBridge {
    @JavascriptInterface public void copy(final String text) {
      runOnUiThread(new Runnable() { public void run() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("roshan-ai command", text));
      }});
    }
  }

  // ---- Session memory (RAM only, this process). Never written to disk, cleared by reset() or when the app process ends. ----
  static final int MAX_TURNS = 40;          // 20 user+model pairs
  static final int MAX_TEXT_CHARS = 40000;  // text kept in memory, oldest pairs dropped first
  static class Turn { String role, text, mime, name; byte[] bytes; }
  private final ArrayList<Turn> hist = new ArrayList<Turn>();
  private String pendText, pendMime, pendName, attName; private byte[] pendBytes; private android.net.Uri shareUri;   // the message being sent (kept after a failure so RETRY can resend it exactly)
  private boolean busy, trimmedNote; private long busySince; private int gen = 0;

  private static Content toContent(Turn t) {
    Content.Builder cb = new Content.Builder(); cb.setRole(t.role);
    if (t.bytes != null) cb.addInlineData(t.bytes, t.mime);
    return cb.addText(t.text).build();
  }

  // Keeps only the most recent file's bytes in memory; an earlier file is replaced by a text note so the model is not told it can still see it.
  private void commit(String reply) {
    for (Turn t : hist) if (t.bytes != null && pendBytes != null) { t.bytes = null; t.text = t.text + " [an earlier attached file is no longer in memory]"; }
    Turn u = new Turn(); u.role = "user"; u.text = pendText; u.bytes = pendBytes; u.mime = pendMime; u.name = pendName; hist.add(u);
    Turn m = new Turn(); m.role = "model"; m.text = reply; hist.add(m);
    pendText = null; pendBytes = null; pendMime = null; pendName = null;
    int chars = 0; for (Turn t : hist) chars += t.text.length();
    while (hist.size() > 2 && (hist.size() > MAX_TURNS || chars > MAX_TEXT_CHARS)) {
      chars -= hist.get(0).text.length() + hist.get(1).text.length(); hist.remove(0); hist.remove(0); trimmedNote = true; }
  }

  public class AiBridge {
    // Sends the typed text (and an attached file, if any) together with this session's earlier turns. Result goes to window.onAiResult(id, ok, text).
    @JavascriptInterface public boolean available() { return model != null; }
    @JavascriptInterface public void ask(final String id, final String text) {
      if (model == null) { deliver0(id, false, initError == null ? "AI is not set up on this device" : initError); return; }
      if (busy && System.currentTimeMillis() - busySince < 120000) { deliver0(id, false, "Still working on your previous message. Wait for its reply first."); return; }
      pendText = text; pendBytes = attBytes; pendMime = attMime; pendName = attName; attBytes = null; attMime = null; attName = null;   // the file is sent only now, with this message
      start(id);
    }
    // Resends the last message that FAILED (same text and file). Nothing is retried that did not fail.
    @JavascriptInterface public void retry(final String id) {
      if (model == null) { deliver0(id, false, "AI is not set up on this device"); return; }
      if (busy && System.currentTimeMillis() - busySince < 120000) { deliver0(id, false, "Still working on your previous message. Wait for its reply first."); return; }
      if (pendText == null) { deliver0(id, false, "There is no failed message to retry. Type or attach it again."); return; }
      start(id);
    }
    // New chat: forgets the conversation and any attached or pending file.
    @JavascriptInterface public void reset() { gen++; hist.clear(); pendText = null; pendBytes = null; pendMime = null; pendName = null; attBytes = null; attMime = null; attName = null; busy = false; trimmedNote = false; dropShare(); }
    @JavascriptInterface public int turns() { return hist.size() / 2; }
  }


  static final String PLAN_MODEL = "gemini-3.5-flash-lite";
  private boolean planBusy;
  /** Task mode. Sends ONLY the request text and the phone's local date/time to Gemini (no history, memory, files, contacts or app list). Result: window.onPlanResult(id, json). */
  public class PlanBridge {
    @JavascriptInterface public boolean available() { return planner != null; }
    @JavascriptInterface public void plan(final String id, final String goal) {
      if (planner == null) { planOut(id, new JSONObject(), "AI is not set up on this device"); return; }
      if (planBusy) { planOut(id, new JSONObject(), "Still planning the previous task."); return; }
      if (goal == null || goal.trim().isEmpty() || goal.length() > 1500) { planOut(id, new JSONObject(), "Describe the task in a sentence or two."); return; }
      planBusy = true;
      try {
        java.util.Calendar c = java.util.Calendar.getInstance();
        String now = String.format(java.util.Locale.US, "Now (phone local time, %s): %04d-%02d-%02dT%02d:%02d, weekday %d (1=Sunday).", java.util.TimeZone.getDefault().getID(), c.get(1), c.get(2) + 1, c.get(5), c.get(11), c.get(12), c.get(7));
        final ListenableFuture<GenerateContentResponse> f = planner.generateContent(new Content.Builder().setRole("user").addText(now + "\nRequest: " + goal.trim()).build());
        f.addListener(new Runnable() { public void run() {
          planBusy = false;
          try {
            GenerateContentResponse r = f.get();
            JSONArray calls = new JSONArray();
            java.util.List<com.google.firebase.ai.type.FunctionCallPart> fc = r.getFunctionCalls();
            if (fc != null) for (com.google.firebase.ai.type.FunctionCallPart p : fc) {
              JSONObject args = new JSONObject();
              for (java.util.Map.Entry<String, kotlinx.serialization.json.JsonElement> e : p.getArgs().entrySet()) args.put(e.getKey(), new org.json.JSONTokener(e.getValue().toString()).nextValue());
              calls.put(new JSONObject().put("name", p.getName()).put("args", args));
            }
            if (calls.length() == 0) { String t = null; try { t = r.getText(); } catch (Throwable x) { }
              planOut(id, new JSONObject().put("ok", false).put("error", "no_actions").put("model_text", t == null ? "" : t.length() > 400 ? t.substring(0, 400) : t), null); return; }
            JSONObject v = TaskPlan.validate(calls, System.currentTimeMillis());
            planOut(id, v.put("model", PLAN_MODEL), null);
          } catch (Throwable e) { planOut(id, new JSONObject(), "The planner call failed (" + PLAN_MODEL + "): " + errText(e)); }
        }}, new Executor() { public void execute(Runnable r) { runOnUiThread(r); } });
      } catch (Throwable e) { planBusy = false; planOut(id, new JSONObject(), "Could not start planning (" + PLAN_MODEL + "): " + errText(e)); }
    }
    private void planOut(String id, JSONObject o, String err) {
      try { if (err != null) o.put("ok", false).put("failed", err); } catch (Exception e) { }
      evalJs("window.onPlanResult&&window.onPlanResult(" + JSONObject.quote(id) + "," + JSONObject.quote(o.toString()) + ")");
    }
  }

  private void start(final String id) {
    busy = true; busySince = System.currentTimeMillis(); trimmedNote = false;
    run(id, model, "m1", gen);
  }

  // Tries the main model; if it fails, the lighter model; if that also looks like temporary overload, the main model once more after 4 s.
  // Every attempt sends the same snapshot: earlier turns + this message. A failed attempt never adds anything to memory.
  private void run(final String id, final GenerativeModelFutures m, final String tag, final int g) {
    try {
      Turn cur = new Turn(); cur.role = "user"; cur.text = pendText; cur.bytes = pendBytes; cur.mime = pendMime;
      ArrayList<Content> all = new ArrayList<Content>();
      for (Turn t : hist) all.add(toContent(t));
      all.add(toContent(cur));
      Content first = all.get(0); Content[] rest = all.subList(1, all.size()).toArray(new Content[0]);
      final ListenableFuture<GenerateContentResponse> f = m.generateContent(first, rest);
      Executor ex = new Executor() { public void execute(Runnable r) { runOnUiThread(r); } };
      f.addListener(new Runnable() { public void run() {
        if (g != gen) return;   // a new chat was started meanwhile: drop this reply
        try {
          GenerateContentResponse r = f.get();
          String t = r.getText();
          if (t == null || t.length() == 0) throw new IllegalStateException("empty_reply");
          commit(t);
          deliver(id, true, t);
          if (trimmedNote) { trimmedNote = false; evalJs("window.onMemNote&&window.onMemNote(" + JSONObject.quote("Memory limit reached: the oldest messages were dropped from the AI's memory. They are still on screen, but the AI no longer sees them.") + ")"); }
        } catch (Throwable e) { fail(id, tag, e, g); }
      }}, ex);
    } catch (Throwable e) { fail(id, tag, e, g); }
  }

  private void fail(final String id, final String tag, Throwable e, final int g) {
    if (g != gen) return;
    if ("m1".equals(tag) && model2 != null) { run(id, model2, "m2", g); return; }
    if (!"m3".equals(tag) && model != null && isOverload(e)) {
      wv.postDelayed(new Runnable() { public void run() { if (g == gen) MainActivity.this.run(id, model, "m3", g); } }, 4000);
      return;
    }
    final String base = errText(e) + ("m1".equals(tag) ? " [tried: main model only]" : " [tried: main model, lighter model" + ("m3".equals(tag) ? ", main model again after a short wait" : "") + "]")
        + " This message was NOT added to the conversation memory; RETRY sends it again.";
    String all = String.valueOf(e.getMessage()) + (e.getCause() == null ? "" : String.valueOf(e.getCause().getMessage()));
    if (all.toLowerCase().contains("app check")) { probeAppCheck(id, base); return; }
    deliver(id, false, base);
  }

  // Safe diagnostic: asks App Check itself for a token and reports only the exception type and a short sanitized message (never the token).
  private void probeAppCheck(final String id, final String base) {
    try {
      FirebaseAppCheck.getInstance().getAppCheckToken(true).addOnCompleteListener(new com.google.android.gms.tasks.OnCompleteListener<com.google.firebase.appcheck.AppCheckToken>() {
        public void onComplete(com.google.android.gms.tasks.Task<com.google.firebase.appcheck.AppCheckToken> t) {
          String d;
          if (t.isSuccessful()) d = "token obtained on device; rejected by server";
          else { Exception x = t.getException(); String m = x == null ? "unknown" : String.valueOf(x.getMessage()).replaceAll("AIza[0-9A-Za-z_\\-]{20,}", "[key]").replaceAll("\\s+", " ");
            if (m.length() > 300) m = m.substring(0, 300);
            d = (x == null ? "?" : x.getClass().getSimpleName()) + ": " + m; }
          deliver(id, false, base + " | AppCheck probe: " + d);
        }
      });
    } catch (Throwable x) { deliver(id, false, base + " | AppCheck probe failed: " + x.getClass().getSimpleName()); }
  }

  public class SchedBridge {
    private JSONObject res(boolean ok, String err) {
      JSONObject o = new JSONObject();
      try { o.put("ok", ok); if (err != null) o.put("error", err); } catch (Exception e) { }
      return o;
    }
    // kind: alarm|reminder. Wall-clock fields in the phone's current timezone.
    @JavascriptInterface public String add(String kind, String title, int y, int mo, int d, int h, int mi) {
      try {
        if (!"alarm".equals(kind) && !"reminder".equals(kind)) return res(false, "bad_kind").toString();
        long at;
        try { at = Sched.epoch(y, mo, d, h, mi); } catch (IllegalArgumentException e) { return res(false, "invalid_time").toString(); }
        if (at <= System.currentTimeMillis() + 5000) return res(false, "in_the_past").toString();
        if (!Sched.notificationsOn(MainActivity.this)) { askNotifications(); return res(false, "notifications_off").toString(); }
        JSONObject it = new JSONObject();
        String id = Long.toString(System.currentTimeMillis()) + Integer.toString((int) (Math.random() * 1000));
        it.put("id", id).put("kind", kind).put("title", title).put("y", y).put("mo", mo).put("d", d).put("h", h).put("mi", mi);
        Sched.ensureChannels(MainActivity.this);
        Sched.arm(MainActivity.this, it);                 // OS accepts (throws otherwise)
        JSONArray a = Sched.load(MainActivity.this);
        a.put(it);
        if (!Sched.save(MainActivity.this, a)) { Sched.disarm(MainActivity.this, id); return res(false, "save_failed").toString(); }
        return res(true, null).put("id", id).put("at", it.getLong("at")).toString();
      } catch (SecurityException e) {
        return res(false, "os_refused").toString();
      } catch (Throwable e) {
        return res(false, "error_" + e.getClass().getSimpleName()).toString();
      }
    }
    @JavascriptInterface public String list() {
      JSONArray a = Sched.load(MainActivity.this);
      try { for (int i = 0; i < a.length(); i++) { JSONObject it = a.getJSONObject(i);
        it.put("at", Sched.epoch(it.getInt("y"), it.getInt("mo"), it.getInt("d"), it.getInt("h"), it.getInt("mi"))); } } catch (Exception e) { }
      return a.toString();
    }
    @JavascriptInterface public boolean cancel(String id) {
      JSONArray in = Sched.load(MainActivity.this), out = new JSONArray(); boolean found = false;
      try { for (int i = 0; i < in.length(); i++) { JSONObject it = in.getJSONObject(i);
        if (id.equals(it.optString("id"))) found = true; else out.put(it); } } catch (Exception e) { }
      if (!found) return false;
      Sched.disarm(MainActivity.this, id);
      return Sched.save(MainActivity.this, out);
    }
    @JavascriptInterface public String status() {
      JSONObject o = new JSONObject();
      try { o.put("notifications", Sched.notificationsOn(MainActivity.this)).put("sdk", android.os.Build.VERSION.SDK_INT); } catch (Exception e) { }
      return o.toString();
    }
    @JavascriptInterface public void openNotificationSettings() {
      runOnUiThread(new Runnable() { public void run() {
        Intent i = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
        startActivity(i);
      }});
    }
  }




  // Revokes the grant and deletes the temporary copy.
  private void dropShare() {
    Sharer.revoke(this, shareUri);
    shareUri = null; Sharer.purge(this);
  }

  public class ShareBridge {
    // Local only: shares the retained chat file (pending attachment, else a failed message's file, else the latest sent file). No AI call, no memory change.
    @JavascriptInterface public String shareFile() {
      JSONObject o = new JSONObject();
      try {
        byte[] b = attBytes; String m = attMime, n = attName; String src = "attached";
        if (b == null && pendBytes != null) { b = pendBytes; m = pendMime; n = pendName; src = "in the last message"; }
        if (b == null) for (int i = hist.size() - 1; i >= 0; i--) { Turn t = hist.get(i); if (t.bytes != null) { b = t.bytes; m = t.mime; n = t.name; src = "sent earlier in this chat"; break; } }
        if (b == null) return o.put("ok", false).put("error", "none").toString();
        dropShare();
        java.io.File f = Sharer.stage(MainActivity.this, b, n, m);
        if (f == null) return o.put("ok", false).put("error", "stage_failed").toString();
        android.net.Uri u = androidx.core.content.FileProvider.getUriForFile(MainActivity.this, Sharer.AUTHORITY, f);
        shareUri = u;
        startActivity(Sharer.chooser(u, m));
        return o.put("ok", true).put("name", f.getName()).put("source", src).toString();
      } catch (Throwable e) { dropShare(); try { return o.put("ok", false).put("error", "error_" + e.getClass().getSimpleName()).toString(); } catch (Exception x) { return "{}"; } }
    }
  }


  /** Drive file manager. Each action needs the user's tap in the app; the token stays in memory for one call. Names and results go only to the screen: never to Gemini, chat history, the planner or attachments. No delete, trash or sharing calls exist in this code. */
  private String drvId, drvOp, drvA, drvB, drvC; private android.net.Uri drvUri; private boolean drvBusy; private long drvAt;
  public class DriveBridge {
    @JavascriptInterface public boolean available() { return true; }
    private boolean begin(String id, String op, String a, String b, String c) {
      if (drvBusy && System.currentTimeMillis() - drvAt < 300000) { driveOut(id, "busy", null, null); return false; }
      drvBusy = true; drvAt = System.currentTimeMillis(); drvId = id; drvOp = op; drvA = a; drvB = b; drvC = c; drvUri = null;
      return true;
    }
    @JavascriptInterface public void search(String id, String term) {
      String q = Drive.buildQuery(term);
      if (q == null) { driveOut(id, "empty", null, null); return; }
      if (begin(id, "search", q, null, null)) driveAuth();
    }
    @JavascriptInterface public void list(String id, String folderId) {
      if (!Drive.validId(folderId)) { driveOut(id, "bad_request", null, null); return; }
      if (begin(id, "list", folderId, null, null)) driveAuth();
    }
    @JavascriptInterface public void access(String id, String folderId) {
      if (!Drive.validId(folderId)) { driveOut(id, "bad_request", null, null); return; }
      if (begin(id, "access", folderId, null, null)) driveAuth();
    }
    @JavascriptInterface public void download(String id, String fileId, String mime) {
      if (!Drive.validId(fileId) || "root".equals(fileId)) { driveOut(id, "bad_request", null, null); return; }
      if (begin(id, "dl", fileId, mime, null)) driveAuth();
    }
    @JavascriptInterface public void mkdir(String id, String parentId, String name) {
      String n = Drive.cleanName(name);
      if (!Drive.validId(parentId) || n == null) { driveOut(id, "bad_request", null, null); return; }
      if (begin(id, "mkdir", parentId, n, null)) driveAuth();
    }
    @JavascriptInterface public void move(String id, String fileId, String fromId, String toId) {
      if (!Drive.validId(fileId) || "root".equals(fileId) || !Drive.validId(fromId) || !Drive.validId(toId)) { driveOut(id, "bad_request", null, null); return; }
      if (begin(id, "mv", fileId, fromId, toId)) driveAuth();
    }
    @JavascriptInterface public void upload(final String id, final String folderId) {
      if (!Drive.validId(folderId)) { driveOut(id, "bad_request", null, null); return; }
      if (!begin(id, "up", folderId, null, null)) return;
      runOnUiThread(new Runnable() { public void run() {
        try {
          Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
          startActivityForResult(i, 32);
        } catch (Throwable e) { driveFinish("no_picker", null); }
      }});
    }
    @JavascriptInterface public boolean open(String link) {
      if (!Drive.linkOk(link)) return false;
      try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true; }
      catch (Throwable e) { return false; }
    }
  }
  private void driveAuth() {
    runOnUiThread(new Runnable() { public void run() {
      try {
        java.util.List<com.google.android.gms.common.api.Scope> sc = new ArrayList<com.google.android.gms.common.api.Scope>();
        sc.add(new com.google.android.gms.common.api.Scope(Drive.SCOPE));
        com.google.android.gms.auth.api.identity.AuthorizationRequest rq = com.google.android.gms.auth.api.identity.AuthorizationRequest.builder().setRequestedScopes(sc).build();
        com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(MainActivity.this).authorize(rq)
          .addOnSuccessListener(new com.google.android.gms.tasks.OnSuccessListener<com.google.android.gms.auth.api.identity.AuthorizationResult>() {
            public void onSuccess(com.google.android.gms.auth.api.identity.AuthorizationResult r) {
              if (r.hasResolution() && r.getPendingIntent() != null) {
                try { startIntentSenderForResult(r.getPendingIntent().getIntentSender(), 31, null, 0, 0, 0); }
                catch (Throwable e) { driveFinish("auth_failed", null); }
              } else driveCall(r.getAccessToken());
            }})
          .addOnFailureListener(new com.google.android.gms.tasks.OnFailureListener() {
            public void onFailure(Exception e) { driveFinish("auth_failed", null); }});
      } catch (Throwable e) { driveFinish("auth_failed", null); }
    }});
  }
  private static final String DAPI = "https://www.googleapis.com/drive/v3/files";
  /** Small JSON request. Returns {code, body}. Body capped at 1 MB. */
  private Object[] driveHttp(String method, String url, String token, String body) throws Exception {
    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
    try {
      c.setConnectTimeout(10000); c.setReadTimeout(20000);
      c.setRequestProperty("Authorization", "Bearer " + token);
      if ("PATCH".equals(method)) { c.setRequestMethod("POST"); c.setRequestProperty("X-HTTP-Method-Override", "PATCH"); } else c.setRequestMethod(method);
      if (body != null) {
        c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        byte[] bb = body.getBytes("UTF-8"); c.setFixedLengthStreamingMode(bb.length);
        try (java.io.OutputStream o = c.getOutputStream()) { o.write(bb); }
      }
      int code = c.getResponseCode();
      java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
      java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n, tot = 0;
      if (in != null) try { while ((n = in.read(buf)) > 0) { tot += n; if (tot > 1000000) break; bo.write(buf, 0, n); } } finally { in.close(); }
      return new Object[] { code, bo.toString("UTF-8") };
    } finally { c.disconnect(); }
  }
  private static String httpErr(int code) { return code == 401 || code == 403 ? "denied" : code == 404 ? "notfound" : "http_error"; }
  private void driveCall(final String token) {
    if (token == null || token.isEmpty()) { driveFinish("auth_failed", null); return; }
    final String op = drvOp, a = drvA, b = drvB, cc = drvC; final android.net.Uri pick = drvUri;
    new Thread(new Runnable() { public void run() {
      try {
        if ("search".equals(op)) {
          String u = DAPI + "?pageSize=" + Drive.MAX_ITEMS + "&orderBy=modifiedTime%20desc&spaces=drive&fields=" + android.net.Uri.encode("files(id,name,mimeType,size,modifiedTime,shared,driveId,webViewLink)") + "&q=" + android.net.Uri.encode(a);
          Object[] r = driveHttp("GET", u, token, null);
          if ((Integer) r[0] != 200) { driveFinish(httpErr((Integer) r[0]), null); return; }
          driveFinish(null, Drive.parseList((String) r[1]));
        } else if ("list".equals(op)) {
          String u = DAPI + "?pageSize=" + Drive.MAX_LIST + "&orderBy=folder,name&spaces=drive&fields=" + android.net.Uri.encode("files(id,name,mimeType,size,modifiedTime,shared,driveId,webViewLink)") + "&q=" + android.net.Uri.encode(Drive.listQuery(a));
          Object[] r = driveHttp("GET", u, token, null);
          if ((Integer) r[0] != 200) { driveFinish(httpErr((Integer) r[0]), null); return; }
          driveFinish(null, Drive.parseList((String) r[1]));
        } else if ("access".equals(op)) {
          Object[] r = driveHttp("GET", DAPI + "/" + a + "?supportsAllDrives=true&fields=" + android.net.Uri.encode("id,name,shared,driveId,permissions(type,role)"), token, null);
          if ((Integer) r[0] != 200) { driveFinish(httpErr((Integer) r[0]), null); return; }
          JSONObject info = Drive.access((String) r[1]);
          if ("root".equals(a)) info.put("name", "My Drive");
          driveDone(null, info);
        } else if ("dl".equals(op)) { driveDownload(token, a); }
        else if ("mkdir".equals(op)) {
          String nm = Drive.uniqueName(b, driveNames(token, b, a));
          Object[] r = driveHttp("POST", DAPI + "?fields=id,name", token, Drive.createJson(nm, a, true));
          if ((Integer) r[0] != 200) { driveFinish(httpErr((Integer) r[0]), null); return; }
          driveDone(null, new JSONObject().put("name", nm).put("renamed", !nm.equals(b)));
        } else if ("up".equals(op)) { driveUpload(token, a, pick); }
        else if ("mv".equals(op)) { driveMove(token, a, b, cc); }
        else driveFinish("bad_request", null);
      } catch (Throwable e) { driveFinish("network", null); }
    }}).start();
  }
  private java.util.Set<String> driveNames(String token, String name, String folder) throws Exception {
    java.util.Set<String> s = new java.util.HashSet<String>();
    String base = name; int dot = base.lastIndexOf('.'); String stem = dot > 0 ? base.substring(0, dot) : base;
    String q = Drive.nameQuery(name, folder);
    Object[] r = driveHttp("GET", DAPI + "?pageSize=100&fields=files(name)&q=" + android.net.Uri.encode("name contains '" + stem.replace("\\", "\\\\").replace("'", "\\'") + "' and '" + folder + "' in parents and trashed = false"), token, null);
    if ((Integer) r[0] == 200) { JSONArray f = new JSONObject((String) r[1]).optJSONArray("files"); if (f != null) for (int i = 0; i < f.length(); i++) s.add(f.getJSONObject(i).optString("name")); }
    return s;
  }
  private void driveDownload(String token, String fileId) throws Exception {
    if (android.os.Build.VERSION.SDK_INT < 29) { driveFinish("unsupported_android", null); return; }
    Object[] m = driveHttp("GET", DAPI + "/" + fileId + "?supportsAllDrives=true&fields=id,name,mimeType,size,driveId", token, null);
    if ((Integer) m[0] != 200) { driveFinish(httpErr((Integer) m[0]), null); return; }
    JSONObject mo = new JSONObject((String) m[1]);
    String mime = mo.optString("mimeType", ""), name = Drive.safeFileName(mo.optString("name", "drive-file"));
    if (Drive.FOLDER.equals(mime)) { driveFinish("is_folder", null); return; }
    String url, outMime;
    if (Drive.isGoogleNative(mime)) {
      String em = Drive.exportMime(mime);
      if (em == null) { driveFinish("no_export", null); return; }
      String ext = Drive.exportExt(mime); if (!name.toLowerCase().endsWith(ext)) name = name + ext;
      url = DAPI + "/" + fileId + "/export?mimeType=" + android.net.Uri.encode(em); outMime = em;
    } else {
      if (mo.optLong("size", 0) > Drive.MAX_BYTES) { driveFinish("too_big", null); return; }
      url = DAPI + "/" + fileId + "?alt=media&supportsAllDrives=true"; outMime = mime.isEmpty() ? "application/octet-stream" : mime;
    }
    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
    android.net.Uri dest = null;
    try {
      c.setConnectTimeout(10000); c.setReadTimeout(30000); c.setRequestProperty("Authorization", "Bearer " + token);
      int code = c.getResponseCode();
      if (code != 200) { driveFinish(code == 403 && Drive.isGoogleNative(mime) ? "export_too_big" : httpErr(code), null); return; }
      android.content.ContentValues cv = new android.content.ContentValues();
      cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name); cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, outMime);
      cv.put(android.provider.MediaStore.Downloads.IS_PENDING, 1);
      dest = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
      if (dest == null) { driveFinish("save_failed", null); return; }
      long tot = 0; byte[] buf = new byte[16384]; int n; boolean big = false;
      try (java.io.InputStream in = c.getInputStream(); java.io.OutputStream o = getContentResolver().openOutputStream(dest)) {
        while ((n = in.read(buf)) > 0) { tot += n; if (tot > Drive.MAX_BYTES) { big = true; break; } o.write(buf, 0, n); }
      }
      if (big) { getContentResolver().delete(dest, null, null); driveFinish("too_big", null); return; }
      android.content.ContentValues done = new android.content.ContentValues(); done.put(android.provider.MediaStore.Downloads.IS_PENDING, 0);
      getContentResolver().update(dest, done, null, null);
      driveDone(null, new JSONObject().put("name", name).put("bytes", tot));
    } catch (Throwable e) {
      if (dest != null) try { getContentResolver().delete(dest, null, null); } catch (Throwable x) { }
      driveFinish("network", null);
    } finally { c.disconnect(); }
  }
  private void driveUpload(String token, String folder, android.net.Uri src) throws Exception {
    if (src == null) { driveFinish("cancelled", null); return; }
    String name = null; long size = -1;
    try (android.database.Cursor q = getContentResolver().query(src, new String[] { android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE }, null, null, null)) {
      if (q != null && q.moveToFirst()) { name = q.getString(0); if (!q.isNull(1)) size = q.getLong(1); }
    }
    name = Drive.safeFileName(name);
    if (size < 0) { driveFinish("size_unknown", null); return; }
    if (size > Drive.MAX_BYTES) { driveFinish("too_big", null); return; }
    String finalName = Drive.uniqueName(name, driveNames(token, name, folder));
    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,name").openConnection();
    String loc;
    try {
      c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(10000); c.setReadTimeout(20000);
      c.setRequestProperty("Authorization", "Bearer " + token); c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
      c.setRequestProperty("X-Upload-Content-Length", String.valueOf(size));
      byte[] mb = Drive.createJson(finalName, folder, false).getBytes("UTF-8"); c.setFixedLengthStreamingMode(mb.length);
      try (java.io.OutputStream o = c.getOutputStream()) { o.write(mb); }
      int code = c.getResponseCode(); loc = c.getHeaderField("Location");
      if (code != 200 || loc == null || !loc.startsWith("https://www.googleapis.com/upload/")) { driveFinish(httpErr(code), null); return; }
    } finally { c.disconnect(); }
    java.net.HttpURLConnection p = (java.net.HttpURLConnection) new java.net.URL(loc).openConnection();
    try {
      p.setRequestMethod("PUT"); p.setDoOutput(true); p.setConnectTimeout(10000); p.setReadTimeout(120000);
      p.setRequestProperty("Authorization", "Bearer " + token); p.setFixedLengthStreamingMode(size);
      long sent = 0; byte[] buf = new byte[16384]; int n;
      try (java.io.InputStream in = getContentResolver().openInputStream(src); java.io.OutputStream o = p.getOutputStream()) {
        while ((n = in.read(buf)) > 0) { sent += n; if (sent > size) break; o.write(buf, 0, n); }
      }
      int code = p.getResponseCode();
      if (code != 200 && code != 201) { driveFinish(httpErr(code), null); return; }
      driveDone(null, new JSONObject().put("name", finalName).put("renamed", !finalName.equals(name)).put("bytes", size));
    } finally { p.disconnect(); }
  }
  private void driveMove(String token, String fileId, String fromId, String toId) throws Exception {
    if (fromId.equals(toId)) { driveFinish("same_folder", null); return; }
    Object[] g = driveHttp("GET", DAPI + "/" + fileId + "?supportsAllDrives=true&fields=id,name,mimeType,parents,driveId", token, null);
    if ((Integer) g[0] != 200) { driveFinish(httpErr((Integer) g[0]), null); return; }
    JSONObject fo = new JSONObject((String) g[1]);
    if (fo.has("driveId")) { driveFinish("unsupported", null); return; }
    JSONArray ps = fo.optJSONArray("parents");
    boolean has = false; if (ps != null) for (int i = 0; i < ps.length(); i++) if (fromId.equals(ps.optString(i)) || ("root".equals(fromId) && ps.length() == 1 && !ps.optString(i).isEmpty() && driveIsRoot(token, ps.optString(i)))) has = true;
    if (!has) { driveFinish("changed", null); return; }
    String realFrom = ps.optString(0);
    // a folder must not be moved into itself or one of its own sub-folders
    if (Drive.FOLDER.equals(fo.optString("mimeType"))) {
      String cur = toId;
      for (int k = 0; k < 40 && cur != null && !cur.isEmpty(); k++) {
        if (cur.equals(fileId)) { driveFinish("cycle", null); return; }
        Object[] pr = driveHttp("GET", DAPI + "/" + cur + "?supportsAllDrives=true&fields=parents", token, null);
        if ((Integer) pr[0] != 200) break;
        JSONArray pp = new JSONObject((String) pr[1]).optJSONArray("parents"); cur = pp == null ? null : pp.optString(0);
      }
    }
    // a file with the same name already in the destination: stop, do not create confusing duplicates
    String nm = fo.optString("name", "");
    java.util.Set<String> taken = driveNames(token, Drive.cleanName(nm) == null ? "x" : nm, toId);
    if (taken.contains(nm)) { driveFinish("exists", null); return; }
    Object[] r = driveHttp("PATCH", DAPI + "/" + fileId + "?supportsAllDrives=true&addParents=" + toId + "&removeParents=" + realFrom + "&fields=id,name", token, "{}");
    if ((Integer) r[0] != 200) { driveFinish(httpErr((Integer) r[0]), null); return; }
    driveDone(null, new JSONObject().put("name", nm));
  }
  private boolean driveIsRoot(String token, String id) {
    try { Object[] r = driveHttp("GET", DAPI + "/root?fields=id", token, null); return (Integer) r[0] == 200 && id.equals(new JSONObject((String) r[1]).optString("id")); } catch (Throwable e) { return false; }
  }
  private void driveFinish(String err, JSONArray items) {
    JSONObject w = null; try { if (items != null) w = new JSONObject().put("list", items); } catch (Exception e) { }
    driveDone(err, w);
  }
  private void driveDone(String err, JSONObject info) {
    String id = drvId, op = drvOp; drvBusy = false; drvId = null; drvOp = null; drvA = drvB = drvC = null; drvUri = null;
    driveOut(id, err, info == null ? null : info.optJSONArray("list"), info == null || info.has("list") ? null : info, op);
  }
  private void driveOut(String id, String err, JSONArray items, JSONObject info) { driveOut(id, err, items, info, null); }
  private void driveOut(String id, String err, JSONArray items, JSONObject info, String op) {
    try {
      JSONObject o = new JSONObject().put("ok", err == null).put("error", err == null ? "" : err).put("op", op == null ? "" : op).put("items", items == null ? new JSONArray() : items);
      if (info != null) o.put("info", info);
      final String js = "window.onDriveResult&&window.onDriveResult(" + JSONObject.quote(id == null ? "" : id) + "," + JSONObject.quote(o.toString()) + ")";
      runOnUiThread(new Runnable() { public void run() { if (wv != null) wv.evaluateJavascript(js, null); } });
    } catch (Exception e) { }
  }

  public class AttachBridge {
    @JavascriptInterface public void pick() {
      runOnUiThread(new Runnable() { public void run() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, ATTACH_TYPES).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        try { startActivityForResult(i, 21); } catch (Throwable e) { attachDone(false, "no_picker", null, null, 0, null); }
      }});
    }
    @JavascriptInterface public void clear() { attBytes = null; attMime = null; attName = null; dropShare(); }
  }

  private void attachDone(boolean ok, String err, String name, String mime, long size, String thumb) {
    try {
      JSONObject o = new JSONObject().put("ok", ok).put("error", err == null ? "" : err).put("name", name == null ? "" : name)
          .put("mime", mime == null ? "" : mime).put("size", size).put("thumb", thumb == null ? "" : thumb);
      final String js = "window.onAttach&&window.onAttach(" + JSONObject.quote(o.toString()) + ")";
      runOnUiThread(new Runnable() { public void run() { if (wv != null) wv.evaluateJavascript(js, null); } });
    } catch (Exception e) { }
  }

  static String mimeFor(String type, String name) {
    String t = type == null ? "" : type.toLowerCase();
    String n = name == null ? "" : name.toLowerCase();
    if (t.equals("image/jpg")) t = "image/jpeg";
    for (String a : ATTACH_TYPES) if (a.equals(t)) return a;
    if (t.length() == 0 || t.equals("application/octet-stream")) {
      if (n.endsWith(".png")) return "image/png"; if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
      if (n.endsWith(".webp")) return "image/webp"; if (n.endsWith(".pdf")) return "application/pdf"; if (n.endsWith(".txt")) return "text/plain";
    }
    return null;
  }

  @Override protected void onActivityResult(int req, int res, Intent data) {
    super.onActivityResult(req, res, data);
    if (req == 31) {
      String tok = null;
      try { if (res == RESULT_OK && data != null) tok = com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data).getAccessToken(); } catch (Throwable e) { }
      if (tok == null) driveFinish(res == RESULT_OK ? "auth_failed" : "cancelled", null); else driveCall(tok);
      return;
    }
    if (req == 32) {
      if (res != RESULT_OK || data == null || data.getData() == null) { driveFinish("cancelled", null); return; }
      drvUri = data.getData(); driveAuth(); return;
    }
    if (req != 21) return;
    if (res != RESULT_OK || data == null || data.getData() == null) return;   // cancelled: nothing selected
    android.net.Uri u = data.getData();
    String name = u.getLastPathSegment() == null ? "file" : u.getLastPathSegment(); long size = -1;
    try (android.database.Cursor c = getContentResolver().query(u, null, null, null, null)) {
      if (c != null && c.moveToFirst()) {
        int ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME), si = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
        if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni);
        if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
      }
    } catch (Throwable e) { }
    String mime = mimeFor(getContentResolver().getType(u), name);
    if (mime == null) { attachDone(false, "unsupported", name, null, size, null); return; }
    if (size > MAX_ATTACH) { attachDone(false, "too_large", name, mime, size, null); return; }
    try (java.io.InputStream in = getContentResolver().openInputStream(u)) {
      java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
      byte[] buf = new byte[16384]; int n; long tot = 0;
      while ((n = in.read(buf)) > 0) { tot += n; if (tot > MAX_ATTACH) { attachDone(false, "too_large", name, mime, tot, null); return; } bo.write(buf, 0, n); }
      byte[] b = bo.toByteArray();
      if (b.length == 0) { attachDone(false, "empty", name, mime, 0, null); return; }
      String thumb = null;
      if (mime.startsWith("image/")) {
        try {
          android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options(); o.inJustDecodeBounds = true;
          android.graphics.BitmapFactory.decodeByteArray(b, 0, b.length, o);
          int ss = 1; while (o.outWidth / (ss * 2) >= 128 && o.outHeight / (ss * 2) >= 128) ss *= 2;
          android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options(); o2.inSampleSize = ss;
          android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeByteArray(b, 0, b.length, o2);
          if (bm != null) { java.io.ByteArrayOutputStream tb = new java.io.ByteArrayOutputStream(); bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, tb);
            thumb = "data:image/jpeg;base64," + android.util.Base64.encodeToString(tb.toByteArray(), android.util.Base64.NO_WRAP); }
        } catch (Throwable e) { }
      }
      attBytes = b; attMime = mime; attName = name;
      attachDone(true, null, name, mime, b.length, thumb);
    } catch (Throwable e) { attachDone(false, "unreadable", name, mime, size, null); }
  }

  public class VoiceBridge {
    @JavascriptInterface public boolean supported() { return SpeechRecognizer.isRecognitionAvailable(MainActivity.this); }
    @JavascriptInterface public boolean offlineHint() { return false; }
    @JavascriptInterface public void start() {
      runOnUiThread(new Runnable() { public void run() {
        if (!SpeechRecognizer.isRecognitionAvailable(MainActivity.this)) { voiceDone(false, "no_recognizer"); return; }
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission("android.permission.RECORD_AUDIO") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
          requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, 8);
          voiceDone(false, "mic_permission_requested"); return;
        }
        if (sr != null) { sr.destroy(); }
        sr = SpeechRecognizer.createSpeechRecognizer(MainActivity.this);
        sr.setRecognitionListener(new RecognitionListener() {
          public void onResults(Bundle r) {
            ArrayList<String> l = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (l == null || l.isEmpty()) voiceDone(false, "no_speech"); else voiceDone(true, l.get(0));
          }
          public void onError(int e) { voiceDone(false, e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ? "no_speech" : "error_" + e); }
          public void onReadyForSpeech(Bundle b) {}
          public void onBeginningOfSpeech() {}
          public void onRmsChanged(float v) {}
          public void onBufferReceived(byte[] b) {}
          public void onEndOfSpeech() {}
          public void onPartialResults(Bundle b) {}
          public void onEvent(int t, Bundle b) {}
        });
        Intent i = new Intent("android.speech.action.RECOGNIZE_SPEECH")
            .putExtra("android.speech.extra.LANGUAGE_MODEL", "free_form").putExtra("android.speech.extra.MAX_RESULTS", 1);
        sr.startListening(i);
      }});
    }
    @JavascriptInterface public void stop() {
      runOnUiThread(new Runnable() { public void run() { if (sr != null) sr.stopListening(); } });
    }
  }

  private void voiceDone(boolean ok, String text) {
    final String js = "window.onVoiceResult&&window.onVoiceResult(" + ok + "," + JSONObject.quote(text) + ")";
    runOnUiThread(new Runnable() { public void run() { if (wv != null) wv.evaluateJavascript(js, null); } });
  }

  @Override protected void onDestroy() {
    if (sr != null) { sr.destroy(); sr = null; }
    super.onDestroy();
  }

  static final String SYSTEM_PROMPT =
      "You are Roshan AI, the personal assistant of Roshan Zameer, inside his private Android app. "
    + "When asked who or what you are, or to introduce yourself, say you are Roshan AI, his personal assistant. "
    + "Do not introduce yourself as Gemini or say that Google trained you as your identity. "
    + "If he sincerely asks what technology or model powers you, answer honestly that you run on Google's Gemini AI; never deny it. "
    + "Be accurate about how you work: the app runs on his phone, but your answers are produced online by Google's Gemini service. "
    + "What he types, and any file he attaches with the FILE button and then sends, is sent over the internet to that service. You are not an official Google product. "
    + "You can only see what he types and the photos, PDFs or text files he attaches. Within the current chat you can see his earlier messages, your earlier replies and the most recently attached file, until he starts a new chat or the app is closed. Never say he closed the app unless he told you so. You keep nothing after that. A message that shows an error was not saved to your memory, so do not pretend you saw it. "
    + "In this version you are not connected to his Gmail, Drive, Calendar, contacts, phone files or any account, so you cannot read or change them. "
    + "Say it is not connected in this version when asked; do not say it is impossible, and do not promise it will be added. "
    + "Reminders and alarms are set by the app itself when he asks for one in plain words; do not claim you set one yourself in your reply. "
    + "Keep replies clear and reasonably short.";

  private void askNotifications() {
    if (android.os.Build.VERSION.SDK_INT >= 33) {
      runOnUiThread(new Runnable() { public void run() {
        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
      }});
    }
  }

  // Non-secret support code: exception type plus a short sanitized message. Not a diagnosis.
  private static boolean isOverload(Throwable e) {
    for (Throwable t = e; t != null; t = (t.getCause() == t ? null : t.getCause())) {
      String m = String.valueOf(t.getMessage()).toLowerCase();
      if (m.contains("high demand") || m.contains("overloaded") || m.contains("unavailable") || m.contains("503")) return true;
    }
    return false;
  }

  private static String errText(Throwable e) {
    Throwable r = e; while (r.getCause() != null && r.getCause() != r) r = r.getCause();
    String m = String.valueOf(r.getMessage()).replaceAll("AIza[0-9A-Za-z_\\-]{20,}", "[key]").replaceAll("\\s+", " ");
    if (m.length() > 140) m = m.substring(0, 140);
    return "Couldn't reach Gemini. Support code: " + e.getClass().getSimpleName() + (r != e ? "/" + r.getClass().getSimpleName() : "") + " - " + m;
  }

  private void deliver(String id, boolean ok, String text) { busy = false; deliver0(id, ok, text); }
  private void deliver0(String id, boolean ok, String text) {
    evalJs("window.onAiResult&&window.onAiResult(" + JSONObject.quote(id) + "," + ok + "," + JSONObject.quote(text) + ")");
  }
  private void evalJs(final String js) { runOnUiThread(new Runnable() { public void run() { wv.evaluateJavascript(js, null); } }); }

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    Sharer.purge(this);
    getWindow().setStatusBarColor(Color.parseColor("#04070b"));
    getWindow().setNavigationBarColor(Color.parseColor("#04070b"));
    try {
      FirebaseOptions opts = new FirebaseOptions.Builder()
          .setApiKey("AIzaSyAWIBab-pb6-Bkew0_4fdgNkC_xkDFkmmI").setApplicationId("1:675436672074:android:964b37fbfabaf1e03da2b3").setProjectId("roshan-ai-app-17803").setGcmSenderId("675436672074").build();
      FirebaseApp app;
      try { app = FirebaseApp.getInstance(); }               // activity re-created in the same process
      catch (IllegalStateException none) { app = FirebaseApp.initializeApp(this, opts); }
      if (app == null) throw new IllegalStateException("Firebase config missing");
      FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
          PlayIntegrityAppCheckProviderFactory.getInstance());
      Content sys = new Content.Builder().addText(SYSTEM_PROMPT).build();
      GenerativeModel gm = FirebaseAI.getInstance(GenerativeBackend.googleAI())
          .generativeModel("gemini-3.8-flash", new com.google.firebase.ai.type.GenerationConfig.Builder().setThinkingConfig(new com.google.firebase.ai.type.ThinkingConfig.Builder().setThinkingLevel(com.google.firebase.ai.type.ThinkingLevel.LOW).build()).build(), null, null, null, sys);
      model = GenerativeModelFutures.from(gm);
      model2 = GenerativeModelFutures.from(FirebaseAI.getInstance(GenerativeBackend.googleAI()).generativeModel("gemini-3.5-flash-lite", null, null, null, null, sys));
      try { planner = GenerativeModelFutures.from(FirebaseAI.getInstance(GenerativeBackend.googleAI()).generativeModel(PLAN_MODEL, null, null, java.util.Arrays.asList(TaskPlan.tools()), null, new Content.Builder().addText(TaskPlan.PLANNER_PROMPT).build())); } catch (Throwable pe) { planner = null; }
    } catch (Throwable e) {
      initError = errText(e);
      model = null;
    }
    wv = new WebView(this);
    WebSettings s = wv.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(false);
    s.setAllowContentAccess(false);
    s.setMediaPlaybackRequiresUserGesture(true);
    wv.addJavascriptInterface(new ClipboardBridge(), "AndroidClipboard");
    wv.addJavascriptInterface(new AiBridge(), "AndroidAI");
    wv.addJavascriptInterface(new PlanBridge(), "AndroidPlan");
    wv.addJavascriptInterface(new SchedBridge(), "AndroidSched");
    wv.addJavascriptInterface(new VoiceBridge(), "AndroidVoice");
    wv.addJavascriptInterface(new AttachBridge(), "AndroidAttach");
    wv.addJavascriptInterface(new Launcher.Bridge(this), "AndroidApps");
    wv.addJavascriptInterface(new ShareBridge(), "AndroidShare");
    wv.addJavascriptInterface(new DriveBridge(), "AndroidDrive");
    Sched.ensureChannels(this);
    wv.setBackgroundColor(Color.parseColor("#04070b"));
    setContentView(wv);
    wv.loadUrl("file:///android_asset/app.html");
  }

  @Override public void onBackPressed() {
    if (wv != null && wv.canGoBack()) wv.goBack(); else super.onBackPressed();
  }
}
