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

    // Lưu mốc vị trí hợp lệ gần nhất để kiểm tra phi lý
    private Location lastRecordedLocation = null;

    @Override
    public void onCreate() {
        super.onCreate();
        dbHelper = new DatabaseHelper(this);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        // Giữ CPU luôn thức khi khóa màn hình
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "J2Tracker:GpsWakeLock");
            wakeLock.acquire();
        }

        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Hệ thống định vị tác chiến")
                .setContentText("Đang bám sát góc cua và tim đường...")
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
            channel.setDescription("Duy trì định vị chính xác khi khóa màn hình");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void startLocationUpdates() {
        try {
            if (locationManager != null) {
                // ĐẶC BIỆT QUAN TRỌNG: minDistance = 0.0f và minTime = 1000ms
                // Bắt buộc chip GPS trả về mọi điểm mốc ở đỉnh góc cua mà không bị bỏ qua
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        1000L,
                        0.0f,
                        this
                );
            }
        } catch (SecurityException ignored) {
            stopSelf();
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null) return;

        // 1. Lọc bỏ điểm có sai số vệ tinh quá lớn (> 12 mét)
        if (location.hasAccuracy() && location.getAccuracy() > 12.0f) {
            return;
        }

        if (lastRecordedLocation != null) {
            float distance = location.distanceTo(lastRecordedLocation);
            long timeDelta = (location.getTime() - lastRecordedLocation.getTime()) / 1000;
            if (timeDelta <= 0) timeDelta = 1;

            // 2. Chống trôi dạt khi đứng yên: Nếu dịch chuyển < 1.5m và tốc độ < 0.3 m/s (~1 km/h) thì bỏ qua
            if (distance < 1.5f && location.getSpeed() < 0.3f) {
                return;
            }

            // 3. Chống điểm giật văng xa bất thường (nhảy cóc > 35 m/s tương đương > 120 km/h)
            float speedCheck = distance / timeDelta;
            if (speedCheck > 35.0f) {
                return;
            }
        }

        // 4. LƯU TRỰC TIẾP TỌA ĐỘ NGUYÊN BẢN (KHÔNG DÙNG EMA LÀM MÉO GÓC RẼ)
        lastRecordedLocation = location;

        dbHelper.insertPoint(
                location.getLatitude(),
                location.getLongitude(),
                location.getSpeed(),
                location.getTime()
        );

        // Bắn broadcast cập nhật giao diện
        Intent intent = new Intent("GPS_LOCATION_UPDATE");
        intent.putExtra("lat", location.getLatitude());
        intent.putExtra("lng", location.getLongitude());
        intent.putExtra("speed", location.getSpeed());
        sendBroadcast(intent);
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
