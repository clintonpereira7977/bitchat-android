package app.chitchat;

import android.app.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.*;
import androidx.test.uiautomator.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Synthetic UI and real local relay; never uses a pilot invitation or FCM
 * registration token from the owner. Screenshots contain only test content. */
@RunWith(AndroidJUnit4.class)
public final class OnlineUiTest {
    private final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    private final Context context=inst.getTargetContext();
    private OnlineActivity activity;
    private interface Condition {boolean test()throws Exception;}
    private void waitFor(Condition condition,String label)throws Exception{long end=android.os.SystemClock.uptimeMillis()+20000;while(android.os.SystemClock.uptimeMillis()<end){if(condition.test())return;Thread.sleep(100);}throw new AssertionError("Timed out: "+label);}
    private View tag(String tag){AtomicReference<View> found=new AtomicReference<>();inst.runOnMainSync(()->{if(activity!=null)found.set(activity.getWindow().getDecorView().findViewWithTag(tag));});return found.get();}
    private void click(String tag)throws Exception{waitFor(()->tag(tag)!=null,"control "+tag);inst.runOnMainSync(()->tagOnMain(tag).performClick());inst.waitForIdleSync();}
    private View tagOnMain(String tag){return activity.getWindow().getDecorView().findViewWithTag(tag);}
    private void type(String tag,String value)throws Exception{waitFor(()->tag(tag)!=null,"field "+tag);inst.runOnMainSync(()->((EditText)tagOnMain(tag)).setText(value));}
    private String value(String tag){AtomicReference<String> text=new AtomicReference<>("");inst.runOnMainSync(()->{View v=tagOnMain(tag);if(v instanceof TextView)text.set(((TextView)v).getText().toString());});return text.get();}
    private boolean text(String expected){AtomicReference<Boolean> found=new AtomicReference<>(false);inst.runOnMainSync(()->found.set(hasText(activity.getWindow().getDecorView(),expected)));return found.get();}
    private boolean hasText(View root,String expected){if(root instanceof TextView&&((TextView)root).getText().toString().contains(expected))return true;if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++)if(hasText(group.getChildAt(i),expected))return true;}return false;}
    private void capture(String name){UiDevice.getInstance(inst).takeScreenshot(new java.io.File(context.getExternalFilesDir(null),name+".png"));}
    private void resumedOnline(){inst.runOnMainSync(()->{for(Activity a:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))if(a instanceof OnlineActivity)activity=(OnlineActivity)a;});}
    @Test public void signupApproveChatDeliveryDraftLeaveAndBackgroundAreUsable()throws Exception{
        OnlineCrypto friend=OnlineProtocolTest.actor();OnlineApi peer=new OnlineApi(friend,OnlineProtocolTest.RELAY);OnlineProtocolTest.register(peer,"Test Friend");
        try{
            OnlineStore.lastOnline(context,true);
            Intent intent=new Intent(context,OnlineActivity.class).putExtra("online_test_base",OnlineProtocolTest.RELAY).putExtra("nickname","Test Owner").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity=(OnlineActivity)inst.startActivitySync(intent);
            waitFor(()->tag("register")!=null,"private pilot signup");capture("online-01-join");type("nickname","Test Owner");type("invite",OnlineProtocolTest.INVITE);click("register");waitFor(()->tag("add_friend")!=null,"registered lobby");
            OnlineIdentity own=new OnlineIdentity(context);OnlineCrypto.Contact recipient=new OnlineCrypto.Contact(own.crypto.profile("Test Owner"));
            peer.request("POST","/v1/requests",new JSONObject().put("code",own.crypto.code));waitFor(()->tag("accept_"+friend.id)!=null,"incoming approval request");capture("online-02-request");click("accept_"+friend.id);waitFor(()->tag("chat_"+friend.id)!=null,"approved contact");capture("online-03-people");
            OnlinePush.enableForRegisteredAccount(context,own.crypto.id);
            String lobbyId=UUID.randomUUID().toString();peer.request("POST","/v1/messages",new JSONObject().put("to",own.crypto.id).put("id",lobbyId).put("envelope",friend.seal(recipient,lobbyId,"SYNTHETIC_LOBBY_ALERT")));
            android.app.NotificationManager notifications=context.getSystemService(android.app.NotificationManager.class);
            waitFor(()->notifications.getActiveNotifications().length==1,"foreground lobby incoming-message alert");
            assertEquals("A new message is waiting.",notifications.getActiveNotifications()[0].getNotification().extras.getString(Notification.EXTRA_TEXT));
            click("chat_"+friend.id);waitFor(()->tag("composer")!=null,"conversation");waitFor(()->notifications.getActiveNotifications().length==0,"opening chat clears alert");
            String first="Synthetic hello नमस्ते 👋";String id=UUID.randomUUID().toString();peer.request("POST","/v1/messages",new JSONObject().put("to",own.crypto.id).put("id",id).put("envelope",friend.seal(recipient,id,first)));waitFor(()->text(first),"inbound decrypted message");
            assertEquals("An already-visible conversation does not need a duplicate alert",0,notifications.getActiveNotifications().length);
            OnlinePush.disable(context);
            type("composer","SYNTHETIC_OUTGOING_NOTE");click("send");type("composer","KEEP-THIS-DRAFT");
            AtomicReference<JSONObject> inbox=new AtomicReference<>();waitFor(()->{JSONArray list=peer.request("GET","/v1/inbox",null).getJSONArray("messages");if(list.length()>0)inbox.set(list.getJSONObject(0));if(inbox.get()==null)Thread.sleep(400);return inbox.get()!=null;},"outbound relay delivery");
            JSONObject message=inbox.get();OnlineCrypto.Contact sender=new OnlineCrypto.Contact(own.crypto.profile("Owner"));assertEquals("SYNTHETIC_OUTGOING_NOTE",friend.open(sender,message.getString("id"),message.getString("envelope")));peer.request("POST","/v1/messages/"+message.getString("id")+"/ack",new JSONObject());waitFor(()->text("delivered to phone"),"live delivery receipt");assertEquals("KEEP-THIS-DRAFT",value("composer"));capture("online-04-chat");
            click("leave");androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(android.R.id.button1))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(androidx.test.espresso.action.ViewActions.click());
            waitFor(()->tag("chat_"+friend.id)!=null&&tag("composer")==null,"leave clears conversation");click("chat_"+friend.id);assertFalse(text(first));assertEquals("",value("composer"));
            String third="SYNTHETIC_CLEAR_ON_BACKGROUND";String mid=UUID.randomUUID().toString();peer.request("POST","/v1/messages",new JSONObject().put("to",own.crypto.id).put("id",mid).put("envelope",friend.seal(recipient,mid,third)));waitFor(()->text(third),"second session inbound");type("composer","PRIVATE-DRAFT-MUST-CLEAR");
            assertTrue("HOME key injection failed",UiDevice.getInstance(inst).pressHome());waitFor(()->!OnlineActivity.foreground,"HOME pauses the Online screen");
            context.startActivity(new Intent(context,OnlineActivity.class).putExtra("online_test_base",OnlineProtocolTest.RELAY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));
            waitFor(()->{resumedOnline();return tag("chat_"+friend.id)!=null&&tag("composer")==null;},"foreground returns to clean lobby");click("chat_"+friend.id);assertFalse(text(third));assertFalse(text("PRIVATE-DRAFT-MUST-CLEAR"));assertEquals("",value("composer"));
            for(String pref:new String[]{OnlineStore.PREFS,OnlinePush.PREFS,"MainActivity"}){String saved=context.getSharedPreferences(pref,0).getAll().toString();assertFalse(saved.contains(first));assertFalse(saved.contains(third));assertFalse(saved.contains("PRIVATE-DRAFT-MUST-CLEAR"));}
            capture("online-05-fresh-chat");
        }finally{
            if(activity!=null){capture("online-last-state");UiDevice.getInstance(inst).dumpWindowHierarchy(new java.io.File(context.getExternalFilesDir(null),"online-last-state.xml"));}
            try{peer.request("DELETE","/v1/me",null);}catch(Exception ignored){}
            if(activity!=null)inst.runOnMainSync(()->activity.finish());
            OnlinePush.disable(context);OnlineIdentity own=new OnlineIdentity(context);try{new OnlineApi(own.crypto,OnlineProtocolTest.RELAY).request("DELETE","/v1/me",null);}catch(Exception ignored){}new OnlineStore(context,own.storage).clear();own.destroy();OnlineStore.lastOnline(context,false);
        }
    }
}
