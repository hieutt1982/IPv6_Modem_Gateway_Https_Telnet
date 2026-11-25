package vn.gateway.ipv6test;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.app.PendingIntent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.net.wifi.WifiManager;
import androidx.core.app.NotificationCompat;

/** Keeps the app process important while the user closes the dashboard Activity. */
public class GatewayService extends Service {
    private static final String CHANNEL_ID = "ipv6_gateway_running";
    private static final int NOTIFICATION_ID = 5108;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IPv6Gateway:CpuLock");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "IPv6Gateway:WifiLock");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // The networking engine remains owned by the existing Activity instance.
        // START_NOT_STICKY avoids claiming an automatic recovery we do not implement.
        return START_NOT_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "IPv6 Modem Gateway", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Thông báo khi Gateway đang chạy nền");
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentIntent(contentIntent)
                .setContentTitle("IPv6 Modem Gateway đang hoạt động")
                .setContentText("Giữ kết nối IPv6 4G và đường truyền Wi-Fi tới modem")
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // Người dùng vuốt ứng dụng khỏi màn hình đa nhiệm: yêu cầu dừng foreground service.
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
