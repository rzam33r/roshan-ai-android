package com.roshanai.app;
import static org.junit.Assert.*;
import org.junit.Test; import org.junit.runner.RunWith;
import org.robolectric.*; import org.robolectric.annotation.Config;
import java.lang.reflect.*; import java.util.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class MemTest {
  MainActivity a;
  Object g(String n) throws Exception { Field f = MainActivity.class.getDeclaredField(n); f.setAccessible(true); return f.get(a); }
  void s(String n, Object v) throws Exception { Field f = MainActivity.class.getDeclaredField(n); f.setAccessible(true); f.set(a, v); }
  void commit(String user, byte[] bytes, String reply) throws Exception {
    s("pendText", user); s("pendBytes", bytes); s("pendMime", bytes == null ? null : "application/pdf");
    Method m = MainActivity.class.getDeclaredMethod("commit", String.class); m.setAccessible(true); m.invoke(a, reply); }
  @SuppressWarnings("unchecked") List<Object> hist() throws Exception { return (List<Object>) g("hist"); }
  String fld(Object t, String n) throws Exception { Field f = t.getClass().getDeclaredField(n); f.setAccessible(true); Object v = f.get(t); return v == null ? null : v.toString(); }
  boolean hasBytes(Object t) throws Exception { Field f = t.getClass().getDeclaredField("bytes"); f.setAccessible(true); return f.get(t) != null; }
  @Test public void recallAndFileFollowup() throws Exception {
    a = Robolectric.buildActivity(MainActivity.class).create().get();
    commit("My favourite colour is teal.", null, "Noted: teal.");
    commit("Here is a sample sheet", new byte[]{1,2,3}, "It is a sample.");
    List<Object> h = hist(); assertEquals(4, h.size());
    assertEquals("user", fld(h.get(0), "role")); assertEquals("model", fld(h.get(1), "role"));
    assertTrue("latest file kept", hasBytes(h.get(2)));
    commit("And a second one", new byte[]{9}, "Second sample.");
    h = hist(); assertFalse("older file dropped", hasBytes(h.get(2))); assertTrue(fld(h.get(2), "text").contains("no longer in memory")); assertTrue(hasBytes(h.get(4)));
    commit("What colour did I say?", null, "Teal.");   // text turn must NOT drop the retained file
    assertTrue("file kept across text follow-ups", hasBytes(hist().get(4)));
    assertNull("pending cleared after success", g("pendText"));
    System.out.println("MEM recall/file ok turns=" + hist().size());
  }
  @Test public void trimsAndFlags() throws Exception {
    a = Robolectric.buildActivity(MainActivity.class).create().get();
    for (int i = 0; i < 25; i++) commit("q" + i, null, "a" + i);
    assertTrue(hist().size() <= MainActivity.MAX_TURNS); assertEquals(Boolean.TRUE, g("trimmedNote"));
    assertEquals("q5", fld(hist().get(0), "text"));
    StringBuilder big = new StringBuilder(); for (int i = 0; i < 30000; i++) big.append('x');
    s("hist", new ArrayList<Object>()); commit(big.toString(), null, big.toString());
    assertEquals("a single oversize pair is kept (never empties the chat)", 2, hist().size());
    System.out.println("MEM trim ok");
  }
  @Test public void resetAndBusy() throws Exception {
    a = Robolectric.buildActivity(MainActivity.class).create().get();
    commit("hello", new byte[]{1}, "hi"); s("pendText", "failed one"); s("pendBytes", new byte[]{7});
    a.new AiBridge().reset();
    assertEquals(0, hist().size()); assertNull(g("pendText")); assertNull(g("pendBytes")); assertEquals(Boolean.FALSE, g("busy"));
    s("busy", true); s("busySince", System.currentTimeMillis()); s("pendText", "keep me");
    a.new AiBridge().ask("x", "second message while busy");
    assertEquals("busy ask must not overwrite the pending message", "keep me", g("pendText"));
    a.new AiBridge().reset(); a.new AiBridge().retry("y");   // nothing failed -> refuses, no crash
    assertNull(g("pendText"));
    System.out.println("MEM reset/busy ok");
  }
}
