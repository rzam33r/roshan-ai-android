package com.roshanai.app;

import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only Drive file-name search helpers. Pure functions, so they can be tested without a device. */
public final class Drive {
  private Drive() { }
  public static final String SCOPE = "https://www.googleapis.com/auth/drive.metadata.readonly";
  public static final int MAX_Q = 100, MAX_ITEMS = 10;

  /** Cleans the user's words for a Drive name search. Returns null if nothing usable is left. */
  public static String cleanTerm(String s) {
    if (s == null) return null;
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < s.length() && b.length() < MAX_Q; i++) {
      char c = s.charAt(i);
      if (Character.isISOControl(c)) c = ' ';
      b.append(c);
    }
    String t = b.toString().trim().replaceAll("\\s+", " ");
    return t.isEmpty() ? null : t;
  }

  /** Drive query string: name contains the term, not trashed. ' and \ are escaped. */
  public static String buildQuery(String term) {
    String t = cleanTerm(term);
    if (t == null) return null;
    return "name contains '" + t.replace("\\", "\\\\").replace("'", "\\'") + "' and trashed = false";
  }

  public static boolean linkOk(String u) {
    if (u == null || u.length() > 600) return false;
    try {
      java.net.URI x = new java.net.URI(u);
      String h = x.getHost();
      return "https".equals(x.getScheme()) && x.getUserInfo() == null && h != null && (h.equals("drive.google.com") || h.equals("docs.google.com"));
    } catch (Exception e) { return false; }
  }

  /** Keeps only name, type, modified time and an allow-listed link. Never any other field. */
  public static JSONArray parse(String body) {
    JSONArray out = new JSONArray();
    try {
      JSONArray f = new JSONObject(body).optJSONArray("files");
      if (f == null) return out;
      for (int i = 0; i < f.length() && out.length() < MAX_ITEMS; i++) {
        JSONObject o = f.optJSONObject(i);
        if (o == null) continue;
        String name = o.optString("name", ""), link = o.optString("webViewLink", "");
        if (name.isEmpty() || !linkOk(link)) continue;
        if (name.length() > 160) name = name.substring(0, 160);
        out.put(new JSONObject().put("name", name).put("type", o.optString("mimeType", "").replaceAll("[^A-Za-z0-9./+\\-]", "")).put("modified", o.optString("modifiedTime", "").replaceAll("[^0-9TZ:.\\-]", "")).put("link", link));
      }
    } catch (Exception e) { }
    return out;
  }
}
