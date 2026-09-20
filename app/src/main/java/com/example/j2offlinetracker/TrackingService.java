package com.example.j2offlinetracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;

public class TrackingService extends Service implements LocationListener {

    private static final String CHANNEL_ID = "gps_tracking_channel";
    private static final int NOTIFICATION_ID = 1001;

    private LocationManager locationManager;
    private DatabaseHelper dbHelper;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        dbHelper = new DatabaseHelper(this);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        // 1. Kích hoạt WakeLock giữ CPU thức khi tắt/khóa màn hình
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "J2Tracker:GpsWakeLock");
            wakeLock.acquire();
        }

        // 2. Chạy dịch vụ ưu tiên cao (Foreground Service)
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Hệ thống định vị tác chiến")
                .setContentText("Đang duy trì khóa GPS ngầm liên tục...")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);
        startLocationUpdates();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "GPS Offline Service",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Duy trì định vị khi khóa màn hình");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void startLocationUpdates() {
        try {
            if (locationManager != null) {
                // Cập nhật tọa độ mỗi 2 giây hoặc dịch chuyển từ 1 mét trở lên
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        2000L,
                        1.0f,
                        this
                );
            }
        } catch (SecurityException ignored) {
            stopSelf();
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location != null) {
            // Lưu trực tiếp vào cơ sở dữ liệu SQLite
            dbHelper.insertPoint(
                    location.getLatitude(),
                    location.getLongitude(),
                    location.getSpeed(),
                    location.getTime()
            );

            // Gửi broadcast cập nhật màn hình
            Intent intent = new Intent("GPS_LOCATION_UPDATE");
            intent.putExtra("lat", location.getLatitude());
            intent.putExtra("lng", location.getLongitude());
            intent.putExtra("speed", location.getSpeed());
            sendBroadcast(intent);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (SecurityException ignored) {}
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override
    public void onProviderEnabled(String provider) {}
    @Override
    public void onProviderDisabled(String provider) {}
}
