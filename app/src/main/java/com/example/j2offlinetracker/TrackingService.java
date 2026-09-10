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
import androidx.core.app.NotificationCompat;

public class TrackingService extends Service implements LocationListener {

    private static final String CHANNEL_ID = "gps_tracking_channel";
    private static final int NOTIFICATION_ID = 1001;

    private LocationManager locationManager;
    private DatabaseHelper dbHelper;

    @Override
    public void onCreate() {
        super.onCreate();
        dbHelper = new DatabaseHelper(this);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GPS Tracker Offline")
                .setContentText("Đang ghi nhận vị trí phần cứng...")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        startForeground(NOTIFICATION_ID, notification);
        startLocationUpdates();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "GPS Offline Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void startLocationUpdates() {
        try {
            if (locationManager != null) {
                // Nhận cập nhật sau mỗi 5 giây hoặc khi di chuyển ít nhất 10 mét
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        5000L,
                        10.0f,
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
            dbHelper.insertPoint(
                    location.getLatitude(),
                    location.getLongitude(),
                    location.getSpeed(),
                    location.getTime()
            );

            // Gửi broadcast cập nhật màn hình chính
            Intent intent = new Intent("GPS_LOCATION_UPDATE");
            intent.putExtra("lat", location.getLatitude());
            intent.putExtra("lng", location.getLongitude());
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
