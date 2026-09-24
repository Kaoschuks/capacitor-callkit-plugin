# @capacitor/push-notifications is called by reflection from CallMessagingService.
-keep class com.capacitorjs.plugins.pushnotifications.PushNotificationsPlugin {
    public static void sendRemoteMessage(com.google.firebase.messaging.RemoteMessage);
    public static void onNewToken(java.lang.String);
}
