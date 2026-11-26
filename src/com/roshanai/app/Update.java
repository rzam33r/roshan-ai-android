package com.roshanai.app;

import org.json.JSONArray;
import org.json.JSONObject;

/** In-app updates from this repo's GitHub Releases. Pure functions, so they can be tested without a device.
 *  A release is: tag "v<versionCode>" (example v24) with one signed APK asset named RoshanAI*.apk.
 *  Android itself refuses an update signed with a different key, and the app checks package, version and certificate before handing it over. */
public final class Update {
  private Update() { }
  public static final String OWNER = "rzam33r", REPO = "roshan-ai-android", PKG = "com.roshanai.app";
  public static final String LATEST = "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases/latest";
  public static final String RELEASES = "https://github.com/" + OWNER + "/" + REPO + "/releases/latest";
  public static final long MAX_BYTES = 100L * 1024 * 1024;
  public static final long CHECK_EVERY_MS = 6L * 3600 * 1000;

  /** versionCode from a tag like "v24", "v24-drive" or "24". -1 if none. */
  public static int codeFromTag(String tag) {
    if (tag == null) return -1;
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("^[vV]?(\\d{1,6})(?:-.*)?$").matcher(tag.trim());
    return m.matches() ? Integer.parseInt(m.group(1)) : -1;
  }

  /** Release downloads only from this repo's release links on GitHub's own hosts. */
  public static boolean downloadOk(String u) {
    if (u == null || u.length() > 2000) return false;
    try {
      java.net.URI x = new java.net.URI(u);
      String h = x.getHost(), p = x.getPath();
      if (!"https".equals(x.getScheme()) || x.getUserInfo() != null || h == null || x.getPort() != -1) return false;
      if (h.equals("github.com")) return p != null && p.startsWith("/" + OWNER + "/" + REPO + "/releases/download/");
      return h.equals("objects.githubusercontent.com") || h.equals("release-assets.githubusercontent.com");
    } catch (Exception e) { return false; }
  }

  /** Release notes as plain text: no control characters except new lines, capped. */
  public static String cleanNotes(String s) {
    if (s == null) return "";
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < s.length() && b.length() < 600; i++) {
      char c = s.charAt(i);
      if (c == '\r') continue;
      b.append(c != '\n' && Character.isISOControl(c) ? ' ' : c);
    }
    return b.toString().trim();
  }

  /** Reads /releases/latest. Returns {code, name, notes, url, size, sha} when it is newer than current, else null. */
  public static JSONObject parseLatest(String body, int current) {
    try {
      JSONObject r = new JSONObject(body);
      if (r.optBoolean("draft", false) || r.optBoolean("prerelease", false)) return null;
      int code = codeFromTag(r.optString("tag_name", ""));
      if (code <= current) return null;
      JSONArray as = r.optJSONArray("assets");
      if (as == null) return null;
      for (int i = 0; i < as.length(); i++) {
        JSONObject a = as.optJSONObject(i); if (a == null) continue;
        String n = a.optString("name", ""), u = a.optString("browser_download_url", "");
        long size = a.optLong("size", -1);
        if (!n.matches("(?i)roshan[A-Za-z0-9._-]*\\.apk") || !downloadOk(u) || size <= 0 || size > MAX_BYTES) continue;
        if (!"uploaded".equals(a.optString("state", "uploaded"))) continue;
        String d = a.optString("digest", ""), sha = d.matches("sha256:[0-9a-fA-F]{64}") ? d.substring(7).toLowerCase(java.util.Locale.ROOT) : "";
        String name = r.optString("name", "").replaceAll("[^A-Za-z0-9 ._()\\-]", "");
        if (name.length() > 60) name = name.substring(0, 60);
        return new JSONObject().put("code", code).put("name", name.isEmpty() ? "v" + code : name)
          .put("notes", cleanNotes(r.optString("body", ""))).put("url", u).put("size", size).put("sha", sha);
      }
    } catch (Exception e) { }
    return null;
  }

  public static String hex(byte[] b) {
    StringBuilder s = new StringBuilder();
    for (byte x : b) s.append(String.format("%02x", x & 255));
    return s.toString();
  }
}
