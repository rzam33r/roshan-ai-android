package com.roshanai.app;
import static org.junit.Assert.*;
import org.junit.Test; import org.junit.runner.RunWith;
import org.robolectric.*; import org.robolectric.annotation.Config;
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = "AndroidManifest.xml")
public class InitSmoke {
  @Test public void initTwice() throws Exception {
    reg();
    Robolectric.buildActivity(MainActivity.class).create().get();
    MainActivity b = Robolectric.buildActivity(MainActivity.class).create().get();
    java.lang.reflect.Field f = MainActivity.class.getDeclaredField("initError"); f.setAccessible(true);
    System.out.println("SMOKE2 initError=" + f.get(b));
  }
  private void reg() {
    android.app.Application ap = org.robolectric.RuntimeEnvironment.getApplication();
    android.content.pm.ServiceInfo si = new android.content.pm.ServiceInfo();
    si.name = "com.google.firebase.components.ComponentDiscoveryService"; si.packageName = ap.getPackageName();
    si.applicationInfo = ap.getApplicationInfo(); si.metaData = new android.os.Bundle();
    String[] r = {"com.google.firebase.ai.FirebaseAIRegistrar","com.google.firebase.FirebaseCommonKtxRegistrar","com.google.firebase.appcheck.FirebaseAppCheckKtxRegistrar","com.google.firebase.appcheck.FirebaseAppCheckRegistrar","com.google.firebase.appcheck.playintegrity.FirebaseAppCheckPlayIntegrityRegistrar"};
    for (String x : r) si.metaData.putString("com.google.firebase.components:" + x, "com.google.firebase.components.ComponentRegistrar");
    org.robolectric.Shadows.shadowOf(ap.getPackageManager()).addOrUpdateService(si);
  }
  @Test public void logs() throws Exception {
    reg();
    android.content.pm.ServiceInfo si = null;
    try { si = org.robolectric.RuntimeEnvironment.getApplication().getPackageManager().getServiceInfo(new android.content.ComponentName(org.robolectric.RuntimeEnvironment.getApplication(),"com.google.firebase.components.ComponentDiscoveryService"), android.content.pm.PackageManager.GET_META_DATA);} catch(Throwable t){System.out.println("SVC "+t);}
    System.out.println("SVC meta="+(si==null?null:si.metaData));
    Robolectric.buildActivity(MainActivity.class).create().get();
    for (org.robolectric.shadows.ShadowLog.LogItem i : org.robolectric.shadows.ShadowLog.getLogs()) if(i.type>=5) System.out.println("LOG "+i.tag+" "+i.msg+" "+(i.throwable==null?"":i.throwable));
  }
  @Test public void init() throws Exception {
    reg();
    MainActivity a = Robolectric.buildActivity(MainActivity.class).create().get();
    java.lang.reflect.Field f = MainActivity.class.getDeclaredField("initError"); f.setAccessible(true);
    java.lang.reflect.Field m = MainActivity.class.getDeclaredField("model"); m.setAccessible(true);
    System.out.println("SMOKE initError=" + f.get(a) + " modelNull=" + (m.get(a)==null));
  }
}
