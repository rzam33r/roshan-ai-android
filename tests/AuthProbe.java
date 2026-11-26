package com.roshanai.app;
import org.junit.Test; import org.junit.runner.RunWith;
import org.robolectric.*; import org.robolectric.annotation.Config;
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class AuthProbe {
  @Test public void probe() throws Exception {
    android.app.Activity a = Robolectric.buildActivity(android.app.Activity.class).create().get();
    try {
      java.util.List<com.google.android.gms.common.api.Scope> sc = new java.util.ArrayList<com.google.android.gms.common.api.Scope>();
      sc.add(new com.google.android.gms.common.api.Scope(Drive.SCOPE));
      System.out.println("PROBE step1 ok");
      com.google.android.gms.auth.api.identity.AuthorizationRequest rq = com.google.android.gms.auth.api.identity.AuthorizationRequest.builder().setRequestedScopes(sc).build();
      System.out.println("PROBE step2 request built");
      com.google.android.gms.auth.api.identity.AuthorizationClient c = com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(a);
      System.out.println("PROBE step3 client ok");
      c.authorize(rq);
      System.out.println("PROBE step4 authorize called");
    } catch (Throwable t) { System.out.println("PROBE FAIL " + t); for (StackTraceElement e : t.getStackTrace()) { System.out.println("PROBE   at " + e); if (e.getClassName().startsWith("com.roshanai")) break; } org.junit.Assert.fail("auth linkage: " + t.getClass().getName()); }
  }
}
