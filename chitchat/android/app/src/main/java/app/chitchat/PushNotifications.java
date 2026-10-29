package app.chitchat;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;

final class PushNotifications {
    static final String CHANNEL = "chitchat-online";
    static final int ID = 2201;
    static final String OPEN_ONLINE = "app.chitchat.OPEN_ONLINE";
    static void show(Context context, String kind) {
        String body = PushPolicy.body(OnlinePush.enabled(context), kind);
        if (body == null) return;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (!manager.areNotificationsEnabled()) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Online chat", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Generic chat alerts with no message previews");
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        manager.createNotificationChannel(channel);
        Intent intent = new Intent(context, MainActivity.class).setAction(OPEN_ONLINE)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(context, ID, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(app.chitchat.R.drawable.notification_icon)
                .setContentTitle("ChitChat").setContentText(body)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setLocalOnly(true)
                .setAutoCancel(true).setOnlyAlertOnce(true).setContentIntent(open)
                .setTimeoutAfter(24 * 60 * 60 * 1000L)
                .setCategory(Notification.CATEGORY_MESSAGE);
        // Recheck consent immediately before displaying a queued wake-up hint.
        synchronized (OnlinePush.class) {
            if (OnlinePush.enabled(context)) {
                try { manager.notify(ID, builder.build()); } catch (SecurityException ignored) {}
            }
        }
    }
    static void clear(Context context) { context.getSystemService(NotificationManager.class).cancel(ID); }
    private PushNotifications() {}
}
