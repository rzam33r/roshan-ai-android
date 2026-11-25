package com.roshanai.app;
import org.junit.Test; import org.junit.runner.RunWith; import org.robolectric.*; import org.robolectric.annotation.Config; import static org.junit.Assert.*;
import java.lang.reflect.Method;
@RunWith(RobolectricTestRunner.class) @Config(sdk = 34, manifest = Config.NONE)
public class OverloadTest {
  static boolean ov(Throwable t) throws Exception { Method m = MainActivity.class.getDeclaredMethod("isOverload", Throwable.class); m.setAccessible(true); return (Boolean) m.invoke(null, t); }
  @Test public void overload() throws Exception {
    assertTrue(ov(new java.util.concurrent.ExecutionException(new RuntimeException("This model is currently experiencing high demand. Spikes in demand are usually temporary"))));
    assertTrue(ov(new RuntimeException("503 Service Unavailable")));
    assertFalse(ov(new RuntimeException("API key not valid")));
    assertFalse(ov(new com.google.firebase.ai.type.ServerException("Internal error occurred", null)));
    assertFalse(ov(new IllegalStateException("empty_reply")));
  }
}
