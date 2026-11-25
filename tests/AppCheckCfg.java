package com.roshanai.app;
import static org.junit.Assert.*;
import org.junit.Test; import org.junit.runner.RunWith;
import org.robolectric.*; import org.robolectric.annotation.Config;
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = "AndroidManifest.xml")
public class AppCheckCfg {
  @Test public void playIntegrityProviderCanBeCreatedFromRealOptions() throws Exception {
    reg();
    MainActivity a = Robolectric.buildActivity(MainActivity.class).create().get();
    com.google.firebase.FirebaseApp app = com.google.firebase.FirebaseApp.getInstance();
    String sid = app.getOptions().getGcmSenderId();
    assertNotNull(sid); assertTrue(app.getOptions().getApplicationId().startsWith("1:" + sid + ":"));
    // This is the call that threw NumberFormatException on the phone when the sender id was missing.
    System.out.println("ACC pos " + tok(app));
    System.out.println("ACC ok sid=" + sid);
  }

  @Test public void negativeControlWithoutSenderIdFails() throws Exception {
    reg();
    com.google.firebase.FirebaseOptions o = new com.google.firebase.FirebaseOptions.Builder().setApiKey("AIzaSyAWIBab-pb6-Bkew0_4fdgNkC_xkDFkmmI").setApplicationId("1:675436672074:android:964b37fbfabaf1e03da2b3").setProjectId("roshan-ai-app-17803").build();
    com.google.firebase.FirebaseApp app = com.google.firebase.FirebaseApp.initializeApp(RuntimeEnvironment.getApplication(), o, "neg");
    String r = tok(app); System.out.println("ACC neg " + r); assertTrue(r, r.contains("NumberFormat"));
  }
  private String tok(com.google.firebase.FirebaseApp app) throws Exception {
    final String[] out = new String[1];
    try {
      com.google.firebase.appcheck.AppCheckProvider p = com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory.getInstance().create(app);
      final com.google.android.gms.tasks.Task<com.google.firebase.appcheck.AppCheckToken> t = p.getToken();
      Thread th = new Thread(new Runnable() { public void run() {
        try { com.google.android.gms.tasks.Tasks.await(t, 5, java.util.concurrent.TimeUnit.SECONDS); out[0] = "token"; }
        catch (Throwable e) { Throwable r = e; while (r.getCause() != null) r = r.getCause(); out[0] = r.getClass().getSimpleName() + ": " + r.getMessage(); } } });
      th.start(); th.join(8000);
    } catch (Throwable e) { Throwable r = e; while (r.getCause() != null) r = r.getCause(); out[0] = "create:" + r.getClass().getSimpleName() + ": " + r.getMessage(); }
    return String.valueOf(out[0]);
  }
  private void reg() {
    android.app.Application ap = RuntimeEnvironment.getApplication();
    android.content.pm.ServiceInfo si = new android.content.pm.ServiceInfo();
    si.name = "com.google.firebase.components.ComponentDiscoveryService"; si.packageName = ap.getPackageName();
    si.applicationInfo = ap.getApplicationInfo(); si.metaData = new android.os.Bundle();
    String[] r = {"com.google.firebase.ai.FirebaseAIRegistrar","com.google.firebase.FirebaseCommonKtxRegistrar","com.google.firebase.appcheck.FirebaseAppCheckKtxRegistrar","com.google.firebase.appcheck.FirebaseAppCheckRegistrar","com.google.firebase.appcheck.playintegrity.FirebaseAppCheckPlayIntegrityRegistrar"};
    for (String x : r) si.metaData.putString("com.google.firebase.components:" + x, "com.google.firebase.components.ComponentRegistrar");
    org.robolectric.Shadows.shadowOf(ap.getPackageManager()).addOrUpdateService(si);
  }
}
