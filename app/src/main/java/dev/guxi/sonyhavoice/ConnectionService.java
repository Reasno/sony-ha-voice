package dev.guxi.sonyhavoice;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.util.Log;
import java.util.ArrayList;
import org.json.JSONObject;

public final class ConnectionService extends Service implements AssistClient.Listener {
    interface Observer extends AssistClient.Listener {}
    final class LocalBinder extends Binder {
        ConnectionService service() { return ConnectionService.this; }
    }
    private final LocalBinder binder = new LocalBinder();
    private final ArrayList<Observer> observers = new ArrayList<>();
    private AssistClient client;
    private AssistantBinding assistantBinding;
    private String status = "正在启动";
    private boolean ready;
    static void start(Context context) {
        context.startForegroundService(new Intent(context, ConnectionService.class));
    }
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("connection",
            "Home Assistant 连接", NotificationManager.IMPORTANCE_LOW));
        Notification notification = notification(status);
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, notification);
        assistantBinding = new AssistantBinding(this);
        assistantBinding.start();
        client = new AssistClient(this, this);
        client.connect();
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        assistantBinding.reconcile();
        if (intent != null && intent.getBooleanExtra("reload", false)) {
            client.close();
            client = new AssistClient(this, this);
        }
        client.connect();
        return START_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public void onDestroy() {
        StatusProvider.publish(this, false);
        assistantBinding.close();
        client.close(); observers.clear(); super.onDestroy();
    }
    AssistClient client() { return client; }
    void observe(Observer observer) {
        if (!observers.contains(observer)) observers.add(observer);
        observer.status(status, ready);
    }
    void remove(Observer observer) { observers.remove(observer); }
    @Override public void status(String text, boolean available) {
        status = text; ready = available;
        StatusProvider.publish(this, available);
        Log.i("SonyHAVoice", "connection=" + text + " ready=" + available);
        getSystemService(NotificationManager.class).notify(1, notification(text));
        for (Observer observer : new ArrayList<>(observers)) observer.status(text, available);
    }
    @Override public void event(String type, JSONObject data) {
        Log.i("SonyHAVoice", "pipeline_event=" + type);
        for (Observer observer : new ArrayList<>(observers)) observer.event(type, data);
    }
    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "connection").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Sony HA Voice").setContentText(text).setContentIntent(open)
            .setOngoing(true).build();
    }
}
