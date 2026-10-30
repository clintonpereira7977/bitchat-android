package app.chitchat;

import android.app.*;
import android.content.*;
import android.graphics.Rect;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Native accessibility/layout checks with synthetic data, on an emulator only. */
@RunWith(AndroidJUnit4.class)
public final class NearbyUiTest {
    private final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    private final Context context=inst.getTargetContext();
    private final UiDevice device=UiDevice.getInstance(inst);
    private MainActivity activity;
    private interface Check {void run() throws Exception;}
    private void onMain(Check check){AtomicReference<Throwable> error=new AtomicReference<>();inst.runOnMainSync(()->{try{check.run();}catch(Throwable e){error.set(e);}});if(error.get()!=null)throw new AssertionError(error.get());}
    private View tag(String tag){return activity.getWindow().getDecorView().findViewWithTag(tag);}
    private void start() {
        OnlineStore.lastOnline(context,false);
        context.getSharedPreferences("MainActivity",0).edit().putString("nickname","Test Friend").commit();
        activity=(MainActivity)inst.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        inst.waitForIdleSync();
    }
    private void verifyButton(String key,String label) {
        onMain(()->{
            Button button=(Button)tag(key);assertNotNull(button);assertEquals(label,button.getText().toString());
            assertTrue(button.getTypeface().isBold());assertNotNull(button.getCompoundDrawablesRelative()[2]);
            assertNotNull(button.getLayout());assertTrue("Button text fits without vertical clipping",button.getHeight()>=button.getLayout().getHeight()+button.getCompoundPaddingTop()+button.getCompoundPaddingBottom());
            button.requestRectangleOnScreen(new Rect(0,0,button.getWidth(),button.getHeight()),true);
        });
        inst.waitForIdleSync();
        onMain(()->{View button=tag(key);Rect visible=new Rect();assertTrue(button.getGlobalVisibleRect(visible));assertEquals("A scrolled action remains fully reachable",button.getHeight(),visible.height());});
    }
    @After public void cleanup(){if(activity!=null)inst.runOnMainSync(()->activity.finish());}
    @Test public void homeUsesBoldTextAndReachableAlignedActions() throws Exception {
        start();onMain(()->assertTrue(((TextView)tag("nearby_title")).getTypeface().isBold()));
        verifyButton("nearby_host","Open a room");verifyButton("nearby_find","Find a friend");
        onMain(()->{View host=tag("nearby_host"),find=tag("nearby_find");assertEquals(host.getWidth(),find.getWidth());assertEquals(host.getLeft(),find.getLeft());});
        device.takeScreenshot(new java.io.File(context.getExternalFilesDir(null),"beta3-nearby-home.png"));
    }
    @Test public void smallScreenWithLargeTextKeepsBothActionsReachable() throws Exception {
        assertEquals("This test may resize only a disposable emulator","1",device.executeShellCommand("getprop ro.kernel.qemu").trim());
        String scale=device.executeShellCommand("settings get system font_scale").trim();if(!scale.matches("[0-9]+(\\.[0-9]+)?"))scale="1.0";
        try {
            device.executeShellCommand("wm size 960x1708");device.executeShellCommand("settings put system font_scale 1.3");
            start();verifyButton("nearby_host","Open a room");verifyButton("nearby_find","Find a friend");
            device.takeScreenshot(new java.io.File(context.getExternalFilesDir(null),"beta3-nearby-large-text.png"));
        } finally {
            if(activity!=null){inst.runOnMainSync(()->activity.finish());activity=null;}
            device.executeShellCommand("wm size reset");device.executeShellCommand("settings put system font_scale "+scale);
        }
    }
}
