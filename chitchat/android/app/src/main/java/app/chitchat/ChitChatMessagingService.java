package app.chitchat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/** Receives only hints. Chat plaintext and envelopes never pass through FCM. */
public final class ChitChatMessagingService extends FirebaseMessagingService {
    @Override public void onNewToken(String token) { OnlinePush.recordRotatedToken(this, token); }
    @Override public void onMessageReceived(RemoteMessage message) {
        dispatch(this, message.getData());
    }
    static void dispatch(android.content.Context context, java.util.Map<String,String> data) {
        if(OnlineActivity.foreground && !"test".equals(data.get("kind"))){context.sendBroadcast(new android.content.Intent(OnlineActivity.SYNC).setPackage(context.getPackageName()));return;}
        PushNotifications.show(context, data.get("kind"));
    }
    @Override public void onDeletedMessages() {
        if (OnlineActivity.foreground) sendBroadcast(new android.content.Intent(OnlineActivity.SYNC).setPackage(getPackageName()));
        else PushNotifications.show(this, "inbox");
        // Fetch the signed inbox when Online opens; never decrypt in the background.
    }
}
