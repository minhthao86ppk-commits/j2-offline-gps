package com.example.j2offlinetracker;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Vibrator;
import android.preference.PreferenceManager;

import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class TrackingService extends Service implements LocationListener {

    private static final String BT_DEVICE_NAME = "LoRa_Tactical_Bridge";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int NOTIFICATION_ID = 1001;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket btSocket;
    private OutputStream btOutputStream;
    private InputStream btInputStream;
    private Thread btWorkerThread;
    private final Object btWriteLock = new Object();

    private volatile boolean isBtConnected = false;
    private volatile boolean isRunning = true;
    private boolean isRecording = false;

    private LocationManager locationManager;
    private PowerManager.WakeLock wakeLock;
    private PowerManager.WakeLock screenWakeLock;
    private Vibrator serviceVibrator;
    private Ringtone serviceAlertRingtone;

    private DatabaseHelper dbHelper;
    private SharedPreferences sharedPreferences;

    private Location lastRecordedLocation = null;
    private long lastBtSendTime = 0;
    private long lastSpacingWarningTime = 0;

    private double myLastLat = 0.0;
    private double myLastLng = 0.0;
    private float myLastSpeed = 0.0f;
    private int myVehicleId = 1;

    @Override
    public void onCreate() {
        super.onCreate();
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        dbHelper = new DatabaseHelper(this);
        myVehicleId = sharedPreferences.getInt("CFG_VEHICLE_ID", 1);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "J2Tracker:CpuWakeLock");
            wakeLock.acquire();

            screenWakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE,
                    "J2Tracker:EmergencyWakeLock"
            );
        }

        serviceVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        Uri alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (alertUri == null) {
            alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        }
        serviceAlertRingtone = RingtoneManager.getRingtone(this, alertUri);

        startForeground(NOTIFICATION_ID, buildTacticalNotification("Hệ thống Định vị Tác chiến", "Khởi tạo dịch vụ ngầm...", false));

        initLocationManager();
        initBluetoothWorker();
    }

    private void initLocationManager() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager != null) {
            try {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0.0f, this);
            } catch (SecurityException ignored) {}
        }
    }

    private void initBluetoothWorker() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
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
        Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
        if (bondedDevices != null) {
            for (BluetoothDevice device : bondedDevices) {
                if (BT_DEVICE_NAME.equals(device.getName())) {
                    targetDevice = device;
                    break;
                }
            }
        }
        if (targetDevice == null) return;

        try {
            btSocket = targetDevice.createRfcommSocketToServiceRecord(SPP_UUID);
            btSocket.connect();
            synchronized (btWriteLock) {
                btOutputStream = btSocket.getOutputStream();
                btInputStream = btSocket.getInputStream();
                isBtConnected = true;
            }

            Intent connIntent = new Intent("BT_CONNECTION_STATUS");
            connIntent.putExtra("connected", true);
            sendBroadcast(connIntent);

            BufferedReader reader = new BufferedReader(new InputStreamReader(btInputStream));
            String line;
            while (isRunning && isBtConnected && (line = reader.readLine()) != null) {
                String packet = line.trim();
                if (packet.startsWith("#POS")) {
                    handleIncomingPosPacket(packet);
                } else if (packet.startsWith("#CMD")) {
                    handleIncomingEmergencyCommand(packet);
                }
            }
        } catch (Exception e) {
            closeBtSocket();
        }
    }

    private void closeBtSocket() {
        synchronized (btWriteLock) {
            isBtConnected = false;
            try {
                if (btInputStream != null) btInputStream.close();
            } catch (Exception ignored) {}
            try {
                if (btOutputStream != null) btOutputStream.close();
            } catch (Exception ignored) {}
            try {
                if (btSocket != null) btSocket.close();
            } catch (Exception ignored) {}
            btInputStream = null;
            btOutputStream = null;
            btSocket = null;
        }
        Intent connIntent = new Intent("BT_CONNECTION_STATUS");
        connIntent.putExtra("connected", false);
        sendBroadcast(connIntent);
    }

    public void sendBluetoothData(String data) {
        new Thread(() -> {
            synchronized (btWriteLock) {
                if (isBtConnected && btOutputStream != null) {
                    try {
                        btOutputStream.write(data.getBytes());
                        btOutputStream.flush();
                    } catch (Exception e) {
                        closeBtSocket();
                    }
                }
            }
        }).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (intent.hasExtra("CMD_SET_RECORDING")) {
                this.isRecording = intent.getBooleanExtra("CMD_SET_RECORDING", false);
                if (this.isRecording) {
                    this.lastRecordedLocation = null;
                }
            }
            if (intent.getBooleanExtra("STOP_EMERGENCY_ALARM", false)) {
                if (serviceAlertRingtone != null && serviceAlertRingtone.isPlaying()) {
                    serviceAlertRingtone.stop();
                }
                if (serviceVibrator != null) {
                    serviceVibrator.cancel();
                }
            }
            if (intent.hasExtra("SEND_LORA_PACKET")) {
                String packet = intent.getStringExtra("SEND_LORA_PACKET");
                if (packet != null) {
                    sendBluetoothData(packet);
                }
            }
        }
        return START_STICKY;
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null) return;
        if (location.hasAccuracy() && location.getAccuracy() > 25.0f) {
            return;
        }

        myLastLat = location.getLatitude();
        myLastLng = location.getLongitude();
        myLastSpeed = location.getSpeed();

        Intent bIntent = new Intent("GPS_LOCATION_UPDATE");
        bIntent.putExtra("lat", myLastLat);
        bIntent.putExtra("lng", myLastLng);
        bIntent.putExtra("speed", myLastSpeed);
        sendBroadcast(bIntent);

        long now = System.currentTimeMillis();
        if (now - lastBtSendTime >= 1000) {
            lastBtSendTime = now;
            myVehicleId = sharedPreferences.getInt("CFG_VEHICLE_ID", 1);
            String posPacket = String.format(Locale.US, "#POS,%d,%.6f,%.6f,%.1f\n",
                    myVehicleId, myLastLat, myLastLng, myLastSpeed);
            sendBluetoothData(posPacket);
        }

        if (isRecording) {
            if (lastRecordedLocation != null) {
                float dist = location.distanceTo(lastRecordedLocation);
                long timeDelta = (location.getTime() - lastRecordedLocation.getTime()) / 1000;
                if (timeDelta <= 0) timeDelta = 1;

                if (dist < 1.2f && location.getSpeed() < 0.3f) {
                    return;
                }
                if ((dist / timeDelta) > 33.3f) {
                    return;
                }
            }
            lastRecordedLocation = location;
            dbHelper.insertPoint(myLastLat, myLastLng, myLastSpeed, location.getTime());
        }
    }

    private void handleIncomingPosPacket(String rawPacket) {
        try {
            String[] parts = rawPacket.split(",");
            if (parts.length >= 5) {
                int senderId = Integer.parseInt(parts[1].trim());
                myVehicleId = sharedPreferences.getInt("CFG_VEHICLE_ID", 1);
                if (senderId != myVehicleId && myLastLat != 0.0 && myLastLng != 0.0) {
                    double teamLat = Double.parseDouble(parts[2].trim());
                    double teamLng = Double.parseDouble(parts[3].trim());
                    float teamSpeed = Float.parseFloat(parts[4].trim());

                    float[] res = new float[1];
                    Location.distanceBetween(myLastLat, myLastLng, teamLat, teamLng, res);
                    float calculatedDist = res[0];

                    if (myLastSpeed < 0.3f && teamSpeed < 0.3f && calculatedDist < 12.0f) {
                        calculatedDist = Math.min(calculatedDist, 3.0f);
                    }

                    if (calculatedDist > 50.0f) {
                        long now = System.currentTimeMillis();
                        if (now - lastSpacingWarningTime > 6000) {
                            lastSpacingWarningTime = now;
                            triggerConvoyWarningAlarm();
                        }
                    }

                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) {
                        String content = String.format(Locale.US, "Cự ly: %.1fm | Tốc độ: %.1f km/h",
                                calculatedDist, myLastSpeed * 3.6f);
                        nm.notify(NOTIFICATION_ID, buildTacticalNotification("Hệ thống Định vị Tác chiến", content, false));
                    }

                    Intent posIntent = new Intent("LORA_POS_UPDATE");
                    posIntent.putExtra("senderId", senderId);
                    posIntent.putExtra("lat", teamLat);
                    posIntent.putExtra("lng", teamLng);
                    posIntent.putExtra("speed", teamSpeed);
                    posIntent.putExtra("distance", calculatedDist);
                    sendBroadcast(posIntent);
                }
            }
        } catch (Exception ignored) {}
    }

    private void handleIncomingEmergencyCommand(String rawPacket) {
        try {
            String[] parts = rawPacket.split(",");
            if (parts.length >= 4) {
                String sender = "XE " + parts[1].trim();
                String cmdDesc = parts[3].trim();

                if (serviceVibrator != null) {
                    serviceVibrator.vibrate(new long[]{0, 600, 300, 600, 300}, 0);
                }
                if (serviceAlertRingtone != null && !serviceAlertRingtone.isPlaying()) {
                    serviceAlertRingtone.play();
                }

                if (screenWakeLock != null && !screenWakeLock.isHeld()) {
                    screenWakeLock.acquire(15000);
                }

                Intent dialogIntent = new Intent(this, MainActivity.class);
                dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                dialogIntent.putExtra("TRIGGER_ALERT_DIALOG", true);
                dialogIntent.putExtra("CMD_SENDER", sender);
                dialogIntent.putExtra("CMD_DESC", cmdDesc);
                startActivity(dialogIntent);
            }
        } catch (Exception ignored) {}
    }

    private void triggerConvoyWarningAlarm() {
        try {
            if (serviceVibrator != null) {
                serviceVibrator.vibrate(new long[]{0, 400, 200, 400}, -1);
            }
            if (serviceAlertRingtone != null && !serviceAlertRingtone.isPlaying()) {
                serviceAlertRingtone.play();
            }
        } catch (Exception ignored) {}
    }

    private Notification buildTacticalNotification(String title, String content, boolean isEmergency) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle(title)
                .setContentText(content)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(isEmergency ? NotificationCompat.PRIORITY_MAX : NotificationCompat.PRIORITY_HIGH);

        if (isEmergency) {
            builder.setCategory(NotificationCompat.CATEGORY_ALARM);
            builder.setFullScreenIntent(pendingIntent, true);
        }

        return builder.build();
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (SecurityException ignored) {}
        }
        closeBtSocket();
        if (btWorkerThread != null) {
            btWorkerThread.interrupt();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (screenWakeLock != null && screenWakeLock.isHeld()) {
            screenWakeLock.release();
        }
        super.onDestroy();
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
