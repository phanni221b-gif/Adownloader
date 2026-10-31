package com.adownloader.app;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import com.adownloader.app.ui.DownloadHistory;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoView;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import static org.junit.Assert.*;

/** Real UI navigation and screenshots backed by actual downloaded synthetic video, no invented media facts. */
@RunWith(AndroidJUnit4.class)
public class UiFlowTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final UiDevice device=UiDevice.getInstance(instrumentation);
    private final Context context=instrumentation.getTargetContext();
    private DownloadIntegrationTest server;
    private String base;
    private MainActivity activity;
    @Before public void setup()throws Exception{
        server=new DownloadIntegrationTest();server.start();Field field=DownloadIntegrationTest.class.getDeclaredField("base");field.setAccessible(true);base=(String)field.get(server);
        context.getSharedPreferences("popup_policy",0).edit().clear().commit();context.getSharedPreferences("appearance",0).edit().putInt("theme",AppCompatDelegate.MODE_NIGHT_NO).commit();
        context.getSharedPreferences("downloads",0).edit().clear().commit();
        instrumentation.getUiAutomation().executeShellCommand("pm grant com.adownloader.app android.permission.POST_NOTIFICATIONS").close();
        activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).setData(Uri.parse(base+"index.html")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));instrumentation.waitForIdleSync();
        waitDetected();
    }
    @After public void teardown()throws Exception{
        if(activity!=null)instrumentation.runOnMainSync(activity::finish);server.stop();
        if(Build.FINGERPRINT.contains("generic")||Build.HARDWARE.contains("ranchu")){device.executeShellCommand("wm size reset");device.executeShellCommand("wm density reset");}
    }
    private UiObject2 view(String id){UiObject2 object=device.wait(Until.findObject(By.res("com.adownloader.app",id)),15000);assertNotNull("Missing UI control "+id,object);return object;}
    private void tab(String name){UiObject2 item=device.wait(Until.findObject(By.res("com.adownloader.app",name)),10000);assertNotNull(item);item.click();device.waitForIdle();}
    private void waitDetected()throws Exception{long deadline=System.currentTimeMillis()+60000;boolean[] found={false};while(System.currentTimeMillis()<deadline){instrumentation.runOnMainSync(()->{TextView action=activity.findViewById(R.id.media_action);found[0]=action!=null&&action.getText().toString().matches("[1-9][0-9]*");});if(found[0])return;Thread.sleep(200);}fail("No Gecko media detection");}
    private Object session(){final Object[] session={null};instrumentation.runOnMainSync(()->session[0]=((GeckoView)((android.widget.FrameLayout)activity.findViewById(R.id.gecko_host)).getChildAt(0)).getSession());return session[0];}
    private void navigate(String url)throws Exception{view("address_bar").setText(url);device.pressEnter();long deadline=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<deadline){if(view("address_bar").getText().equals(url)){Thread.sleep(250);return;}Thread.sleep(100);}fail("Address navigation failed");}
    private void snapshot(String name)throws Exception{device.waitForIdle();Thread.sleep(300);File folder=new File(context.getExternalFilesDir(null),"ui-proof");folder.mkdirs();Bitmap bitmap=instrumentation.getUiAutomation().takeScreenshot();assertNotNull(bitmap);try(OutputStream out=new FileOutputStream(new File(folder,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();}
    private void safeInsets(){instrumentation.runOnMainSync(()->{View root=activity.findViewById(R.id.app_root);android.graphics.Insets safe=activity.getWindow().getDecorView().getRootWindowInsets().getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());assertTrue(root.getPaddingTop()>=safe.top);assertTrue(root.getPaddingBottom()>=safe.bottom);assertTrue(root.getPaddingLeft()>=safe.left);assertTrue(root.getPaddingRight()>=safe.right);});}
    private void theme(String label)throws Exception{tab("nav_settings");view("setting_theme").click();UiObject2 choice=device.wait(Until.findObject(By.text(label)),5000);assertNotNull(choice);choice.click();device.waitForIdle();Thread.sleep(500);}
    @Test public void screensKeepSessionDownloadAndLibraryPersistWithAdaptiveThemes()throws Exception{
        Object original=session();snapshot("phone-light-browser");safeInsets();
        tab("nav_downloads");snapshot("phone-light-downloads-empty");tab("nav_browser");assertSame(original,session());assertEquals(base+"index.html",view("address_bar").getText());
        navigate(base+"second.html");assertTrue(view("browser_back").isEnabled());view("browser_back").click();device.waitForIdle();Thread.sleep(500);assertEquals(base+"index.html",view("address_bar").getText());view("browser_forward").click();device.waitForIdle();Thread.sleep(500);assertEquals(base+"second.html",view("address_bar").getText());view("browser_refresh").click();waitDetected();
        view("media_action").click();view("sheet_title");snapshot("phone-light-media-sheet");view("media_download").click();view("action_confirm").click();
        long deadline=System.currentTimeMillis()+45000;JSONObject completed=null;while(System.currentTimeMillis()<deadline){for(JSONObject item:DownloadHistory.read(context))if(DownloadHistory.done(item)&&item.optLong("created")>System.currentTimeMillis()-60000)completed=item;if(completed!=null)break;Thread.sleep(250);}assertNotNull("UI download must complete",completed);
        tab("nav_downloads");Thread.sleep(500);snapshot("phone-light-downloads");assertTrue(device.hasObject(By.textContains(completed.optString("name"))));
        tab("nav_library");Thread.sleep(500);snapshot("phone-light-library");
        Instrumentation.ActivityMonitor monitor=instrumentation.addMonitor(new IntentFilter(Intent.ACTION_VIEW),new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null),true);view("library_thumbnail").click();Thread.sleep(300);assertTrue("External ACTION_VIEW must be used",monitor.getHits()>0);instrumentation.removeMonitor(monitor);
        tab("nav_settings");snapshot("phone-light-settings");theme("다크");instrumentation.runOnMainSync(()->assertEquals(Configuration.UI_MODE_NIGHT_YES,activity.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK));assertSame(original,session());snapshot("phone-dark-settings");
        tab("nav_downloads");snapshot("phone-dark-downloads");tab("nav_library");snapshot("phone-dark-library");tab("nav_browser");assertSame(original,session());snapshot("phone-dark-browser");
        view("address_bar").click();device.waitForIdle();Thread.sleep(400);instrumentation.runOnMainSync(()->{android.graphics.Rect rect=new android.graphics.Rect();activity.findViewById(R.id.address_bar).getGlobalVisibleRect(rect);assertTrue(rect.top>=activity.findViewById(R.id.app_root).getPaddingTop());assertTrue(rect.height()>0);});snapshot("phone-dark-keyboard");device.pressBack();
        if(Build.HARDWARE.contains("ranchu")||Build.FINGERPRINT.contains("generic")){
            device.executeShellCommand("wm size 1768x2208");device.executeShellCommand("wm density 320");Thread.sleep(800);assertSame(original,session());safeInsets();
            tab("nav_downloads");snapshot("fold-dark-downloads");tab("nav_library");snapshot("fold-dark-library");tab("nav_settings");snapshot("fold-dark-settings");tab("nav_browser");snapshot("fold-dark-browser");view("media_action").click();snapshot("fold-dark-media-sheet");device.pressBack();
            device.executeShellCommand("wm size 2208x1768");Thread.sleep(800);safeInsets();snapshot("fold-dark-landscape");assertSame(original,session());
            theme("라이트");tab("nav_library");snapshot("fold-light-library");tab("nav_browser");snapshot("fold-light-browser");
        }
        String uri=completed.optString("uri");instrumentation.runOnMainSync(activity::finish);activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).setData(Uri.parse(base+"index.html")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));tab("nav_library");Thread.sleep(500);assertTrue("History must persist across Activity restart",DownloadHistory.read(context).stream().anyMatch(row->uri.equals(row.optString("uri"))));snapshot("library-after-relaunch");
    }
    @Test public void popupBlockOnceAndInitiatingSiteGrant()throws Exception{
        Object original=session();
        for(int action=0;action<3;action++){
            if(action>0)Thread.sleep(2600); // Respect the UI's repeated-popup debounce window.
            UiObject2 link=device.wait(Until.findObject(By.text("새 창 테스트")),10000);assertNotNull(link);link.click();view("action_title");
            if(action==0){snapshot("popup-block");view("action_cancel").click();assertEquals(base+"index.html",view("address_bar").getText());}
            else{view(action==1?"action_confirm":"action_extra").click();Thread.sleep(500);assertEquals(base+"popup.html",view("address_bar").getText());view("browser_back").click();Thread.sleep(500);}
        }
        assertTrue(context.getSharedPreferences("popup_policy",0).getBoolean("http://127.0.0.1:"+Uri.parse(base).getPort(),false));
        UiObject2 link=device.wait(Until.findObject(By.text("새 창 테스트")),10000);assertNotNull(link);link.click();Thread.sleep(500);assertEquals(base+"popup.html",view("address_bar").getText());assertFalse(device.hasObject(By.res("com.adownloader.app","action_title")));assertSame(original,session());
        tab("nav_settings");view("setting_popups").click();snapshot("popup-site-management");
    }
}
