package com.roshanai.app;

import org.json.JSONArray;
import org.json.JSONObject;

/** Drive file manager helpers (browse, download, upload, new folder, move). Never delete, trash or change sharing. Pure functions, so they can be tested without a device. */
public final class Drive {
  private Drive() { }
  public static final String SCOPE = "https://www.googleapis.com/auth/drive";
  public static final int MAX_Q = 100, MAX_ITEMS = 10, MAX_LIST = 200;
  public static final long MAX_BYTES = 200L * 1024 * 1024;
  public static final String FOLDER = "application/vnd.google-apps.folder";

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

  /** Maps a Google status code to a key made only of digits. Never uses exception text. */
  public static String authKey(int code) { return code >= 0 && code <= 99999 ? "auth_c" + code : "auth_failed"; }
  /** Where the sign-in step failed plus the exception class name (letters and digits only, capped). Never message text. */
  public static String authPath(String where, Throwable t) {
    String cls = t == null ? "none" : t.getClass().getSimpleName().replaceAll("[^A-Za-z0-9]", "");
    if (cls.length() > 40) cls = cls.substring(0, 40);
    if (cls.isEmpty()) cls = "anon";
    return "auth_p_" + where + "_" + cls;
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

  /** Drive ids are letters, digits, - and _ ; "root" is the My Drive root. Anything else is refused. */
  public static boolean validId(String id) { return id != null && (id.equals("root") || id.matches("[A-Za-z0-9_-]{5,120}")); }

  public static String listQuery(String folderId) { return validId(folderId) ? "'" + folderId + "' in parents and trashed = false" : null; }

  public static String nameQuery(String name, String folderId) {
    String t = cleanName(name);
    if (t == null || !validId(folderId)) return null;
    return "name = '" + t.replace("\\", "\\\\").replace("'", "\\'") + "' and '" + folderId + "' in parents and trashed = false";
  }

  /** Folder or file name typed by the user: no control chars, no slashes, trimmed, max 120. */
  public static String cleanName(String s) {
    if (s == null) return null;
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < s.length() && b.length() < 120; i++) {
      char c = s.charAt(i);
      if (Character.isISOControl(c) || c == '/' || c == '\\') c = ' ';
      b.append(c);
    }
    String t = b.toString().trim().replaceAll("\\s+", " ");
    return t.isEmpty() || t.equals(".") || t.equals("..") ? null : t;
  }

  /** Name that is safe as a phone file name. */
  public static String safeFileName(String s) {
    String t = cleanName(s == null ? null : s.replace(':', '_').replace('*', '_').replace('?', '_').replace('"', '_').replace('<', '_').replace('>', '_').replace('|', '_'));
    return t == null ? "drive-file" : t;
  }

  /** "a.pdf" with "a.pdf" taken becomes "a (2).pdf". Never returns a name in taken. */
  public static String uniqueName(String name, java.util.Set<String> taken) {
    if (taken == null || !taken.contains(name)) return name;
    int dot = name.lastIndexOf('.');
    String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
    for (int i = 2; i < 1000; i++) { String c = base + " (" + i + ")" + ext; if (!taken.contains(c)) return c; }
    return name + " (copy)";
  }

  /** Export type for a native Google file, or null when Drive has no export for it. */
  public static String exportMime(String m) {
    if (m == null) return null;
    switch (m) {
      case "application/vnd.google-apps.document": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
      case "application/vnd.google-apps.spreadsheet": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
      case "application/vnd.google-apps.presentation": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
      case "application/vnd.google-apps.drawing": return "application/pdf";
      default: return null;
    }
  }
  public static String exportExt(String m) {
    String e = exportMime(m);
    if (e == null) return null;
    if (e.endsWith("wordprocessingml.document")) return ".docx";
    if (e.endsWith("spreadsheetml.sheet")) return ".xlsx";
    if (e.endsWith("presentationml.presentation")) return ".pptx";
    return ".pdf";
  }
  public static boolean isGoogleNative(String m) { return m != null && m.startsWith("application/vnd.google-apps."); }

  /** Metadata JSON for creating a folder or file under a parent. */
  public static String createJson(String name, String parentId, boolean folder) {
    try {
      JSONObject o = new JSONObject().put("name", name).put("parents", new JSONArray().put(parentId));
      if (folder) o.put("mimeType", FOLDER);
      return o.toString();
    } catch (Exception e) { return null; }
  }

  /** Browse list: id, name, type, size, date, folder flag, shared flag, allow-listed link. Ids are kept only if valid. */
  public static JSONArray parseList(String body) {
    JSONArray out = new JSONArray();
    try {
      JSONArray f = new JSONObject(body).optJSONArray("files");
      if (f == null) return out;
      for (int i = 0; i < f.length() && out.length() < MAX_LIST; i++) {
        JSONObject o = f.optJSONObject(i);
        if (o == null) continue;
        String id = o.optString("id", ""), name = o.optString("name", ""), mime = o.optString("mimeType", "");
        if (!validId(id) || name.isEmpty() || o.has("driveId")) continue;
        if (name.length() > 160) name = name.substring(0, 160);
        String link = o.optString("webViewLink", "");
        JSONObject r = new JSONObject().put("id", id).put("name", name)
          .put("type", mime.replaceAll("[^A-Za-z0-9./+\\-]", ""))
          .put("folder", FOLDER.equals(mime)).put("size", o.optLong("size", -1))
          .put("modified", o.optString("modifiedTime", "").replaceAll("[^0-9TZ:.\\-]", ""))
          .put("shared", o.optBoolean("shared", false)).put("link", linkOk(link) ? link : "");
        out.put(r);
      }
    } catch (Exception e) { }
    return out;
  }

  /** Reads a files.get answer (shared flag, shared drive, permission types) and says who can see the item. Never assumes private. */
  public static JSONObject access(String body) {
    JSONObject r = new JSONObject();
    try {
      JSONObject o = new JSONObject(body);
      boolean sharedDrive = o.has("driveId");
      boolean shared = o.optBoolean("shared", false);
      JSONArray p = o.optJSONArray("permissions");
      int anyone = 0, users = 0, groups = 0, domains = 0;
      if (p != null) for (int i = 0; i < p.length(); i++) {
        JSONObject q = p.optJSONObject(i); if (q == null) continue;
        String t = q.optString("type", "");
        if (t.equals("anyone")) anyone++; else if (t.equals("user")) users++; else if (t.equals("group")) groups++; else if (t.equals("domain")) domains++;
      }
      String level, text;
      if (sharedDrive) { level = "unsupported"; text = "This is in a shared drive, which this app does not change."; }
      else if (anyone > 0) { level = "shared"; text = "Anyone with the link can see this folder."; }
      else if (domains > 0 || groups > 0 || users > 1) { level = "shared"; text = "Other people can see this folder (" + Math.max(0, users - 1) + " people, " + groups + " groups, " + domains + " domains)."; }
      else if (shared) { level = "unknown"; text = "This folder is marked shared but Google did not list who can see it."; }
      else if (p != null && users <= 1) { level = "private"; text = "Only you can see this folder."; }
      else { level = "unknown"; text = "Google did not say who can see this folder, so it may be shared."; }
      r.put("level", level).put("text", text).put("name", cleanForShow(o.optString("name", "")));
    } catch (Exception e) {
      try { r.put("level", "unknown").put("text", "Could not check who can see this folder.").put("name", ""); } catch (Exception x) { }
    }
    return r;
  }
  private static String cleanForShow(String s) { return s.length() > 160 ? s.substring(0, 160) : s; }
}
