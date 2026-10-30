package app.chitchat;

import android.app.*;
import android.content.Context;
import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class PushIntegrationTest {
    private Context context;
    private static final String ACCOUNT = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    @Before public void setup() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        OnlinePush.disable(context);
        notifications(0);
    }
    @After public void cleanup() { OnlinePush.disable(context); notifications(0); }
    private android.service.notification.StatusBarNotification[] notifications(int expected) {
        // Android queues notify/cancel in the system service. Check the actual
        // resulting state rather than assuming either Binder call is a barrier.
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        long deadline = android.os.SystemClock.uptimeMillis() + 3000;
        android.service.notification.StatusBarNotification[] current;
        do {
            current = manager.getActiveNotifications();
            if (current.length == expected) return current;
            android.os.SystemClock.sleep(25);
        } while (android.os.SystemClock.uptimeMillis() < deadline);
        assertEquals("Android notification state did not settle", expected, current.length);
        return current;
    }
    @Test public void suppliedConfigIsPackagedButAutomaticRegistrationIsOff() {
        FirebaseOptions options = FirebaseOptions.fromResource(context);
        assertNotNull(options);
        assertEquals("chitchat-2faca", options.getProjectId());
        assertEquals("1:1086455516054:android:3ff25c803fb6e51491ebb7", options.getApplicationId());
        assertEquals("1086455516054", options.getGcmSenderId());
        if (FirebaseApp.getApps(context).isEmpty()) FirebaseApp.initializeApp(context);
        assertFalse(FirebaseMessaging.getInstance().isAutoInitEnabled());
        // Initialization is local only. This test never calls getToken().
        assertFalse(new java.io.File(context.getFilesDir(), "PersistedInstallation." + FirebaseApp.getInstance().getPersistenceKey() + ".json").exists());
    }
    @Test public void noRegistrationOrAlertsWithoutOnlineConsent() {
        final boolean[] callback = {false};
        OnlinePush.requestToken(context, (token, available) -> {
            assertNull(token); assertFalse(available); callback[0] = true;
        });
        assertTrue(callback[0]);
        OnlinePush.recordRotatedToken(context, "synthetic-registration-token-00001");
        assertEquals("", OnlinePush.pendingToken(context));
        PushNotifications.show(context, "inbox");
        notifications(0);
    }
    @Test public void tokenRotationIsAccountBoundAndOptOutClearsEverything() {
        OnlinePush.enableForRegisteredAccount(context, ACCOUNT);
        assertFalse(OnlinePush.ready(context));
        OnlinePush.recordRotatedToken(context, "synthetic-registration-token-00001");
        assertEquals("synthetic-registration-token-00001", OnlinePush.pendingToken(context));
        OnlinePush.recordRotatedToken(context, "synthetic-registration-token-00002");
        OnlinePush.acknowledgeToken(context, "synthetic-registration-token-00001");
        assertEquals("synthetic-registration-token-00002", OnlinePush.pendingToken(context));
        assertFalse(OnlinePush.ready(context));
        OnlinePush.acknowledgeToken(context,"synthetic-registration-token-00002");assertTrue(OnlinePush.ready(context));
        OnlinePush.recordRotatedToken(context,"synthetic-registration-token-00004");assertFalse(OnlinePush.ready(context));
        OnlinePush.enableForRegisteredAccount(context, "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");
        assertEquals("", OnlinePush.pendingToken(context));
        OnlinePush.recordRotatedToken(context, "synthetic-registration-token-00003");
        OnlinePush.disable(context);
        assertFalse(OnlinePush.enabled(context)); assertEquals("", OnlinePush.pendingToken(context));
        assertEquals(0, context.getSharedPreferences(OnlinePush.PREFS, 0).getAll().size());
        assertFalse(FirebaseMessaging.getInstance().isAutoInitEnabled());
    }
    @Test public void backgroundPushHintsCreateRealAndroidNotificationsButOptOutSuppressesThem() {
        boolean foreground=OnlineActivity.foreground;
        try{
            OnlineActivity.foreground=false;OnlinePush.enableForRegisteredAccount(context,ACCOUNT);
            java.util.Map<String,String> data=new java.util.HashMap<>();data.put("kind","inbox");data.put("body","PRIVATE_UNTRUSTED_CHAT_TEXT");
            ChitChatMessagingService.dispatch(context,data);
            Notification first=notifications(1)[0].getNotification();assertEquals("A new message is waiting.",first.extras.getString(Notification.EXTRA_TEXT));
            assertEquals(0,first.flags&Notification.FLAG_ONLY_ALERT_ONCE);
            PushNotifications.clear(context);notifications(0);OnlinePush.disable(context);
            ChitChatMessagingService.dispatch(context,data);notifications(0);
        }finally{OnlineActivity.foreground=foreground;}
    }
    @Test public void foregroundTestHintCanDisplayAndAndroidAlertSettingsAreChecked() {
        boolean foreground=OnlineActivity.foreground;NotificationManager manager=context.getSystemService(NotificationManager.class);
        try{
            OnlineActivity.foreground=true;OnlinePush.enableForRegisteredAccount(context,ACCOUNT);
            java.util.Map<String,String> data=new java.util.HashMap<>();data.put("kind","test");ChitChatMessagingService.dispatch(context,data);
            assertEquals("This is a ChitChat notification test.",notifications(1)[0].getNotification().extras.getString(Notification.EXTRA_TEXT));
            assertNull(PushNotifications.problem(context));
            assertEquals(NotificationManager.IMPORTANCE_HIGH,manager.getNotificationChannel(PushNotifications.CHANNEL).getImportance());
            assertTrue(manager.getNotificationChannel(PushNotifications.CHANNEL).shouldVibrate());
        }finally{OnlineActivity.foreground=foreground;}
    }
    @Test public void notificationsNeverContainUntrustedContentAndHaveImmutableOpenIntent() throws Exception {
        OnlinePush.enableForRegisteredAccount(context, ACCOUNT);
        PushNotifications.show(context, "PRIVATE_TEXT_MUST_NOT_APPEAR");
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        notifications(0);
        PushNotifications.show(context, "inbox");
        android.service.notification.StatusBarNotification[] notifications = notifications(1);
        assertEquals(1, notifications.length);
        Notification n = notifications[0].getNotification();
        assertEquals("ChitChat", n.extras.getString(Notification.EXTRA_TITLE));
        assertEquals("A new message is waiting.", n.extras.getString(Notification.EXTRA_TEXT));
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility);
        assertNotNull(n.contentIntent); assertTrue(n.contentIntent.isImmutable());
        OnlinePush.disable(context);
        notifications(0);
    }
}
