package com.example.j2offlinetracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class TrackingService extends Service implements LocationListener {

    private static final String CHANNEL_ID = "gps_tracking_channel";
    private static final int NOTIFICATION_ID = 1001;

    private LocationManager locationManager;
    private DatabaseHelper dbHelper;
    private PowerManager.WakeLock wakeLock;
    private Location lastRecordedLocation = null;

    // Quản lý Bluetooth kết nối ngầm vĩnh viễn
    private static final String TARGET_BT_NAME = "LoRa_Tactical_Bridge";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket btSocket;
    private OutputStream btOutputStream;
    private Thread btConnectThread;
    private volatile boolean isBtConnected = false;
    private long lastBtSendTime = 0;

    private SharedPreferences sharedPreferences;

    @Override
    public void onCreate() {
        super.onCreate();
        dbHelper = new DatabaseHelper(this);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // Giữ CPU luôn thức khi tắt/khóa màn hình
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "J2Tracker:TacticalWakeLock");
            wakeLock.acquire();
        }

        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Hệ thống định vị & Cầu LoRa")
                .setContentText("Duy trì kết nối vô tuyến và GPS liên tục...")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);
        startLocationUpdates();

        // Khởi động luồng Bluetooth ngầm trong Service
        startBluetoothConnection();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "GPS Offline Service",
                    NotificationManager.IMPORTANCE_HIGH
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

        if (location.hasAccuracy() && location.getAccuracy() > 12.0f) {
            return;
        }

        if (lastRecordedLocation != null) {
            float distance = location.distanceTo(lastRecordedLocation);
            long timeDelta = (location.getTime() - lastRecordedLocation.getTime()) / 1000;
            if (timeDelta <= 0) timeDelta = 1;

            if (distance < 1.5f && location.getSpeed() < 0.3f) {
                return;
            }

            float speedCheck = distance / timeDelta;
            if (speedCheck > 35.0f) {
                return;
            }
        }

        lastRecordedLocation = location;

        dbHelper.insertPoint(
                location.getLatitude(),
                location.getLongitude(),
                location.getSpeed(),
                location.getTime()
        );

        // Phát broadcast cập nhật giao diện nếu màn hình đang mở
        Intent intent = new Intent("GPS_LOCATION_UPDATE");
        intent.putExtra("lat", location.getLatitude());
        intent.putExtra("lng", location.getLongitude());
        intent.putExtra("speed", location.getSpeed());
        sendBroadcast(intent);

        // TỰ ĐỘNG BẮN TỌA ĐỘ SANG LORA NGAY CẢ KHI TẮT MÀN HÌNH HOẶC ẤN HOME
        if (System.currentTimeMillis() - lastBtSendTime > 1000) {
            lastBtSendTime = System.currentTimeMillis();
            int myVehicleId = sharedPreferences.getInt("CFG_VEHICLE_ID", 1);
            String posPacket = String.format(Locale.US, "#POS,%d,%.6f,%.6f,%.1f\n",
                    myVehicleId, location.getLatitude(), location.getLongitude(), location.getSpeed());
            sendBluetoothData(posPacket);
        }
    }

    private void startBluetoothConnection() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;

        btConnectThread = new Thread(() -> {
            BluetoothDevice targetDevice = null;
            Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
            if (pairedDevices != null) {
                for (BluetoothDevice device : pairedDevices) {
                    if (TARGET_BT_NAME.equalsIgnoreCase(device.getName())) {
                        targetDevice = device;
                        break;
                    }
                }
            }

            if (targetDevice == null) return;

            try {
                bluetoothAdapter.cancelDiscovery();
                btSocket = targetDevice.createRfcommSocketToServiceRecord(SPP_UUID);
                btSocket.connect();
                btOutputStream = btSocket.getOutputStream();
                isBtConnected = true;

                // Báo cho MainActivity biết đã thông cầu Bluetooth
                Intent btStatusIntent = new Intent("LORA_BT_STATUS");
                btStatusIntent.putExtra("connected", true);
                sendBroadcast(btStatusIntent);

                BufferedReader reader = new BufferedReader(new InputStreamReader(btSocket.getInputStream()));
                String line;
                while (isBtConnected && (line = reader.readLine()) != null) {
                    final String receivedPacket = line.trim();

                    if (receivedPacket.startsWith("#POS")) {
                        // Nhận vị trí xe đồng đội dội về từ LoRa
                        Intent posIntent = new Intent("LORA_POS_RECEIVED");
                        posIntent.putExtra("raw", receivedPacket);
                        sendBroadcast(posIntent);
                    } else if (receivedPacket.startsWith("#CMD")) {
                        // Nhận lệnh chiến thuật
                        Intent cmdIntent = new Intent("LORA_CMD_RECEIVED");
                        cmdIntent.putExtra("raw", receivedPacket);
                        sendBroadcast(cmdIntent);
                    }
                }
            } catch (Exception e) {
                isBtConnected = false;
                try {
                    if (btSocket != null) btSocket.close();
                } catch (Exception ignored) {}
            }
        });
        btConnectThread.start();
    }

    public void sendBluetoothData(String data) {
        if (isBtConnected && btOutputStream != null) {
            new Thread(() -> {
                try {
                    btOutputStream.write(data.getBytes());
                    btOutputStream.flush();
                } catch (Exception e) {
                    isBtConnected = false;
                }
            }).start();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Nhận lệnh phát từ giao diện MainActivity (ví dụ: bấm nút MỆNH LỆNH)
        if (intent != null && intent.hasExtra("SEND_LORA_PACKET")) {
            String packet = intent.getStringExtra("SEND_LORA_PACKET");
            sendBluetoothData(packet);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (SecurityException ignored) {}
        }
        isBtConnected = false;
        try {
            if (btSocket != null) btSocket.close();
        } catch (Exception ignored) {}

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override
    public void onProviderEnabled(String provider) {}
    @Override
    public void onProviderDisabled(String provider) {}
}
