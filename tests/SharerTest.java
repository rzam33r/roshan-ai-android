package com.roshanai.app;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Intent;
import android.net.Uri;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class SharerTest {
  private Application app() { return RuntimeEnvironment.getApplication(); }
  @Test public void namesAreSanitizedAndTypedByMime() {
    assertEquals("evil.png", Sharer.safeName("../../etc/evil.exe", "image/png"));
    assertEquals("file.pdf", Sharer.safeName("...", "application/pdf"));
    assertEquals("my_report__final_.txt", Sharer.safeName("my report (final).docx", "text/plain"));
    assertNull(Sharer.safeName("a.zip", "application/zip"));
    assertTrue(Sharer.safeName(new String(new char[200]).replace('\0','a') + ".jpg", "image/jpeg").length() <= 64);
  }
  @Test public void stageReplacesAndPurgeDeletes() {
    File a = Sharer.stage(app(), new byte[]{1,2,3}, "one.png", "image/png");
    assertTrue(a.exists()); assertEquals(Sharer.dir(app()), a.getParentFile());
    File b = Sharer.stage(app(), new byte[]{4}, "two.pdf", "application/pdf");
    assertFalse(a.exists()); assertTrue(b.exists());
    Sharer.purge(app()); assertFalse(b.exists());
    assertNull(Sharer.stage(app(), new byte[0], "x.png", "image/png"));
    assertNull(Sharer.stage(app(), new byte[]{1}, "x.zip", "application/zip"));
  }
  @Test public void chooserHasOneReadOnlyGrantAndMime() {
    Uri u = Uri.parse("content://" + Sharer.AUTHORITY + "/shared/one.png");
    Intent ch = Sharer.chooser(u, "image/png");
    Intent send = ch.getParcelableExtra(Intent.EXTRA_INTENT);
    assertEquals(Intent.ACTION_SEND, send.getAction()); assertEquals("image/png", send.getType());
    assertEquals(u, send.getParcelableExtra(Intent.EXTRA_STREAM));
    assertTrue((send.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    assertEquals(0, send.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    assertEquals(0, ch.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    assertEquals(0, ch.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    assertNull(send.getComponent());
  }
}
