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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TrackingService extends Service implements LocationListener {

    private static final String CHANNEL_ID = "gps_tracking_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final int CMD_NOTIFICATION_ID = 1002;

    private LocationManager locationManager;
    private DatabaseHelper dbHelper;
    private PowerManager.WakeLock wakeLock;

    // Vị trí phục vụ bộ lọc chống trôi
    private Location lastValidLocation = null;
    private Location latestGpsLocation = null;

    private volatile boolean isRecording = false;

    // Quản lý kết nối Bluetooth
    private static final String TARGET_BT_NAME = "LoRa_Tactical_Bridge";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket btSocket;
    private OutputStream btOutputStream;

    // KHÓA ĐỒNG BỘ VÀ HÀNG ĐỢI GỬI DỮ LIỆU ĐƠN LUỒNG (FIFO - KHÔNG TREO MÁY)
    private final Object btWriteLock = new Object();
    private final ExecutorService btSendExecutor = Executors.newSingleThreadExecutor();

    private Thread btWorkerThread;
    private Thread telemetryHeartbeatThread;
    private volatile boolean isBtConnected = false;
    private volatile boolean isRunning = true;

    private SharedPreferences sharedPreferences;

    @Override
    public void onCreate() {
        super.onCreate();
        dbHelper = new DatabaseHelper(this);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "J2Tracker:TacticalWakeLock");
            wakeLock.acquire();
        }

        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Hệ thống định vị & Cầu LoRa")
                .setContentText("Duy trì kết nối vô tuyến và giám sát đội hình 24/24...")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);
        startLocationUpdates();

        // 1. Luồng tự động quét và duy trì kết nối Bluetooth
        startBluetoothWorker();

        // 2. Luồng nhịp tim: Bơm tọa độ sang LoRa liên tục 1 giây/lần
        startTelemetryHeartbeat();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "GPS Offline Tactical Service",
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

    // --- BỘ LỌC CHỐNG TRÔI TỌA ĐỘ VÀ KHỬ NHIỄU DÃ CHIẾN ---
    @Override
    public void onLocationChanged(Location location) {
        if (location == null) return;

        // 1. LỌC BÁN KÍNH SAI SỐ: Giữ ở 25.0m để đảm bảo độ nhạy khi pin yếu hoặc thời tiết xấu
        if (location.hasAccuracy() && location.getAccuracy() > 25.0f) {
            return;
        }

        if (lastValidLocation != null) {
            float distance = location.distanceTo(lastValidLocation);
            long timeDeltaSec = (location.getTime() - lastValidLocation.getTime()) / 1000;
            if (timeDeltaSec <= 0) timeDeltaSec = 1;

            // 2. LỌC BƯỚC NHẢY ẢO: Bỏ điểm nếu tốc độ tính toán vượt quá 120 km/h (~33.3 m/s)
            if (distance / timeDeltaSec > 33.3f) {
                return;
            }

            // 3. BỘ LỌC ĐỨNG YÊN (DEADBAND FILTER):
            // Nếu dịch chuyển < 2.5m HOẶC tốc độ < 0.8 m/s (~3 km/h) và cự ly < 4.0m
            // -> Xe đang dừng, giữ nguyên tọa độ cũ, triệt tiêu hoàn toàn búi dây trôi dạt
            if (distance < 2.5f || (location.hasSpeed() && location.getSpeed() < 0.8f && distance < 4.0f)) {
                if (latestGpsLocation != null) {
                    latestGpsLocation.setSpeed(0.0f);
                    latestGpsLocation.setTime(location.getTime());
                }
                return;
            }
        }

        // --- ĐIỂM HỢP LỆ (XE ĐANG DI CHUYỂN THẬT SỰ) ---
        lastValidLocation = location;
        latestGpsLocation = location;

        // Bắn Broadcast cập nhật giao diện
        Intent intent = new Intent("GPS_LOCATION_UPDATE");
        intent.putExtra("lat", location.getLatitude());
        intent.putExtra("lng", location.getLongitude());
        intent.putExtra("speed", location.getSpeed());
        sendBroadcast(intent);

        // Ghi vào SQLite khi bật chế độ ghi hành trình
        if (isRecording) {
            dbHelper.insertPoint(
                    location.getLatitude(),
                    location.getLongitude(),
                    location.getSpeed(),
                    location.getTime()
            );
        }
    }

    // --- LUỒNG NHỊP TIM: BƠM TỌA ĐỘ SANG MẠCH ĐỀU ĐẶN 1 GIÂY/LẦN ---
    private void startTelemetryHeartbeat() {
        telemetryHeartbeatThread = new Thread(() -> {
            while (isRunning) {
                try {
                    Thread.sleep(1000);
                    if (isBtConnected && latestGpsLocation != null) {
                        int myVehicleId = sharedPreferences.getInt("CFG_VEHICLE_ID", 1);
                        String posPacket = String.format(Locale.US, "#POS,%d,%.6f,%.6f,%.1f\n",
                                myVehicleId,
                                latestGpsLocation.getLatitude(),
                                latestGpsLocation.getLongitude(),
                                latestGpsLocation.getSpeed());
                        sendBluetoothData(posPacket);
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        telemetryHeartbeatThread.start();
    }

    // --- LUỒNG QUẢN LÝ KẾT NỐI BLUETOOTH ---
    private void startBluetoothWorker() {
        if (btWorkerThread != null && btWorkerThread.isAlive()) return;

        isRunning = true;
        btWorkerThread = new Thread(() -> {
            while (isRunning) {
                if (!isBtConnected) {
                    attemptBluetoothConnect();
                }
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        btWorkerThread.start();
    }

    private void attemptBluetoothConnect() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;

        BluetoothDevice targetDevice = null;
        try {
            Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
            if (pairedDevices != null) {
                for (BluetoothDevice device : pairedDevices) {
                    if (TARGET_BT_NAME.equalsIgnoreCase(device.getName())) {
                        targetDevice = device;
                        break;
                    }
                }
            }
        } catch (SecurityException ignored) {
            return;
        }

        if (targetDevice == null) {
            broadcastBtStatus(false, "CHƯA GHÉP ĐÔI MẠCH LORA");
            return;
        }

        try {
            bluetoothAdapter.cancelDiscovery();
            btSocket = targetDevice.createRfcommSocketToServiceRecord(SPP_UUID);
            btSocket.connect();

            synchronized (btWriteLock) {
                btOutputStream = btSocket.getOutputStream();
                isBtConnected = true;
            }

            broadcastBtStatus(true, "ĐÃ THÔNG CẦU LORA");

            BufferedReader reader = new BufferedReader(new InputStreamReader(btSocket.getInputStream()));
            String line;
            while (isBtConnected && isRunning && (line = reader.readLine()) != null) {
                final String receivedPacket = line.trim();
                if (receivedPacket.isEmpty()) continue;

                if (receivedPacket.startsWith("#POS")) {
                    Intent posIntent = new Intent("LORA_POS_RECEIVED");
                    posIntent.putExtra("raw", receivedPacket);
                    sendBroadcast(posIntent);
                } else if (receivedPacket.startsWith("#CMD")) {
                    Intent cmdIntent = new Intent("LORA_CMD_RECEIVED");
                    cmdIntent.putExtra("raw", receivedPacket);
                    sendBroadcast(cmdIntent);

                    triggerTacticalAlertNotification(receivedPacket);

                } else if (receivedPacket.startsWith("#ACK")) {
                    Intent ackIntent = new Intent("LORA_ACK_RECEIVED");
                    ackIntent.putExtra("raw", receivedPacket);
                    sendBroadcast(ackIntent);
                }
            }
        } catch (Exception ignored) {
            // Mạch ngoài tầm sóng hoặc mất nguồn
        } finally {
            synchronized (btWriteLock) {
                isBtConnected = false;
                try {
                    if (btOutputStream != null) btOutputStream.close();
                    if (btSocket != null) btSocket.close();
                } catch (Exception ignored) {}
                btSocket = null;
                btOutputStream = null;
            }
            broadcastBtStatus(false, "MẤT KẾT NỐI - ĐANG THỬ LẠI...");
        }
    }

    private void triggerTacticalAlertNotification(String rawCmd) {
        String[] parts = rawCmd.split(",");
        String sender = (parts.length >= 2) ? "XE " + parts[1] : "ĐỒNG ĐỘI";
        String cmdDesc = (parts.length >= 4) ? parts[3] : "LỆNH TÁC CHIẾN";

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            Notification alertNotif = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("🚨 MỆNH LỆNH TỪ " + sender)
                    .setContentText(cmdDesc)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setDefaults(Notification.DEFAULT_ALL)
                    .setAutoCancel(true)
                    .build();
            manager.notify(CMD_NOTIFICATION_ID, alertNotif);
        }
    }

    private void broadcastBtStatus(boolean connected, String message) {
        Intent btStatusIntent = new Intent("LORA_BT_STATUS");
        btStatusIntent.putExtra("connected", connected);
        btStatusIntent.putExtra("status_text", message);
        sendBroadcast(btStatusIntent);
    }

    public void sendBluetoothData(String data) {
        if (!isBtConnected || data == null) return;

        btSendExecutor.execute(() -> {
            synchronized (btWriteLock) {
                if (isBtConnected && btOutputStream != null) {
                    try {
                        btOutputStream.write(data.getBytes());
                        btOutputStream.flush();
                    } catch (Exception e) {
                        isBtConnected = false;
                    }
                }
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (intent.hasExtra("CMD_SET_RECORDING")) {
                this.isRecording = intent.getBooleanExtra("CMD_SET_RECORDING", false);
            }
            if (intent.hasExtra("SEND_LORA_PACKET")) {
                String packet = intent.getStringExtra("SEND_LORA_PACKET");
                sendBluetoothData(packet);
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        btSendExecutor.shutdownNow();

        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (SecurityException ignored) {}
        }
        synchronized (btWriteLock) {
            isBtConnected = false;
            try {
                if (btSocket != null) btSocket.close();
            } catch (Exception ignored) {}
        }
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
