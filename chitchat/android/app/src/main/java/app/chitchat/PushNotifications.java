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
    static final String TAG_PREFIX = "chitchat-online:";
    static final int MAX_VISIBLE = 6;
    static String problem(Context context) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            return "Android notification permission is off for ChitChat.";
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (!manager.areNotificationsEnabled()) return "Android has blocked ChitChat notifications.";
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return "The Online chat notification category is blocked in Android settings.";
        if (channel != null && channel.getImportance() < NotificationManager.IMPORTANCE_HIGH) return "Online chat alerts are quiet in Android settings. Enable pop-up alerts there if you want a banner.";
        return null;
    }
    static void show(Context context, String kind) {
        show(context, kind, null);
    }
    static void show(Context context, String kind, String deliveryId) {
        String body = PushPolicy.body(OnlinePush.enabled(context), kind);
        if (body == null) return;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (!manager.areNotificationsEnabled()) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Online chat", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Generic chat alerts with no message previews");
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        channel.enableVibration(true);
        channel.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
                new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT).build());
        manager.createNotificationChannel(channel);
        Intent intent = new Intent(context, OnlineActivity.class).setAction(OPEN_ONLINE)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(context, ID, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(app.chitchat.R.drawable.notification_icon)
                .setContentTitle("ChitChat").setContentText(body)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setLocalOnly(true)
                .setAutoCancel(true).setOnlyAlertOnce(false).setContentIntent(open)
                .setTimeoutAfter(24 * 60 * 60 * 1000L)
                .setCategory(Notification.CATEGORY_MESSAGE);
        // Recheck consent immediately before displaying a queued wake-up hint.
        synchronized (OnlinePush.class) {
            if (OnlinePush.enabled(context)) {
                String key = deliveryKey(deliveryId);
                if (key != null && wasAlerted(context, key)) return;
                try {
                    // Reusing one notification ID is an update to an existing
                    // alert on several Android versions. Give each delivery a
                    // fresh tag, retain a small bounded tray, and deduplicate
                    // provider redelivery without keeping any message content.
                    manager.cancel(ID);
                    java.util.List<String> tags=visibleTags(context);
                    while(tags.size()>=MAX_VISIBLE)manager.cancel(tags.remove(0),ID);
                    String tag=TAG_PREFIX+java.util.UUID.randomUUID();tags.add(tag);
                    // Android's active-notification snapshot can lag notify /
                    // cancel. Track pending posts too, so a burst stays bounded.
                    context.getSharedPreferences(OnlinePush.PREFS,0).edit().putString("visible_alerts",String.join("\n",tags)).apply();
                    manager.notify(tag, ID, builder.build());
                    if (key != null) rememberAlert(context, key);
                } catch (SecurityException ignored) {}
            }
        }
    }
    private static String deliveryKey(String value) {
        if (value == null || value.isEmpty() || value.length() > 4096) return null;
        try { return OnlineCrypto.b64(OnlineCrypto.hash(OnlineCrypto.utf8(value))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static boolean wasAlerted(Context context, String key) {
        return java.util.Arrays.asList(context.getSharedPreferences(OnlinePush.PREFS,0).getString("alerted_deliveries", "").split("\n")).contains(key);
    }
    private static void rememberAlert(Context context, String key) {
        String saved=context.getSharedPreferences(OnlinePush.PREFS,0).getString("alerted_deliveries", "");
        java.util.List<String> keys=new java.util.ArrayList<>();
        for(String old:saved.split("\n"))if(!old.isEmpty())keys.add(old);
        keys.add(key);while(keys.size()>64)keys.remove(0);
        context.getSharedPreferences(OnlinePush.PREFS,0).edit().putString("alerted_deliveries",String.join("\n",keys)).apply();
    }
    private static java.util.List<String> visibleTags(Context context) {
        java.util.List<String> tags=new java.util.ArrayList<>();
        for(String tag:context.getSharedPreferences(OnlinePush.PREFS,0).getString("visible_alerts", "").split("\n"))
            if(tag.startsWith(TAG_PREFIX))tags.add(tag);
        return tags;
    }
    static void clear(Context context) {
        synchronized(OnlinePush.class){
            NotificationManager manager=context.getSystemService(NotificationManager.class);
            manager.cancel(ID);
            for(String tag:visibleTags(context))manager.cancel(tag,ID);
            for(android.service.notification.StatusBarNotification n:manager.getActiveNotifications())
                if(n.getTag()!=null&&n.getTag().startsWith(TAG_PREFIX))manager.cancel(n.getTag(),ID);
            context.getSharedPreferences(OnlinePush.PREFS,0).edit().remove("visible_alerts").apply();
        }
    }
    private PushNotifications() {}
}
