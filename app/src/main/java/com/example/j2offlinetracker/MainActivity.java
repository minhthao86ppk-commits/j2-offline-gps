package com.example.j2offlinetracker;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.BitmapDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.util.Xml;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.modules.IArchiveFile;
import org.osmdroid.tileprovider.modules.OfflineTileProvider;
import org.osmdroid.tileprovider.tilesource.FileBasedTileSource;
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity implements LocationListener {

    private static final int PERMISSION_REQUEST_CODE = 200;
    private static final float CONVOY_SPACING_LIMIT_METERS = 50.0f; // Ngưỡng dãn cự ly cảnh báo: 50 mét

    // Chế độ vận hành
    private static final int MODE_STANDBY = 0;
    private static final int MODE_RECORDING = 1;
    private static final int MODE_FOLLOW_ROUTE = 2;

    private int currentMode = MODE_STANDBY;
    private boolean isFirstGpsFix = true;
    private boolean isWaitingFirstStartPoint = false;

    // Định danh xe tác chiến
    private int myVehicleId = 1; // 1: Xe Chỉ huy, 2: Xe Phân đội
    private int teammateId = 2;
    private SharedPreferences sharedPreferences;

    // Giao diện
    private DrawerLayout drawerLayout;
    private MapView mapView;
    private TextView tvQuickStatus, tvStats;
    private Button btnOpenMenu, btnCloseMenu;
    private Button btnStartRecord, btnStopRecord;
    private Button btnFlagYellow, btnFlagPurple, btnClearFlags;
    private Button btnLoadFollowGpx, btnClearRoute;

    // Cụm 7 nút lệnh tác chiến
    private Button btnCmdStop, btnCmdResume, btnCmdSpeedUp, btnCmdSlowDown;
    private Button btnCmdCloseSpacing, btnCmdOpenSpacing, btnCmdEmergency;

    // Lớp vẽ bản đồ & Marker xe
    private Polyline trackLine;
    private Polyline plannedGpxLine;
    private Marker currentMarker;
    private Marker teammateMarker;

    // Cờ Xuất phát, Cờ Đích đến & Cờ mốc tác chiến trơn không chữ
    private Marker startFlagMarker;
    private Marker finishFlagMarker;
    private final List<Marker> tacticalFlagMarkers = new ArrayList<>();

    // Quản lý vị trí & Giám sát cự ly
    private GeoPoint myCurrentPoint = null;
    private GeoPoint teammatePoint = null;
    private float teammateSpeed = 0.0f;
    private long lastSpacingAlertTime = 0;

    // Cảm biến & CSDL
    private DatabaseHelper dbHelper;
    private LocationManager locationManager;
    private Vibrator vibrator;
    private Ringtone alertRingtone;
    private Ringtone warningBeep;

    // Cấu hình Bluetooth Classic SPP kết nối Heltec LoRa
    private static final String TARGET_BT_NAME = "LoRa_Tactical_Bridge";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket btSocket;
    private OutputStream btOutputStream;
    private Thread btConnectThread;
    private volatile boolean isBtConnected = false;
    private long lastBtSendTime = 0;

    // Bộ thu nhận tọa độ ngầm từ TrackingService
    private final BroadcastReceiver locationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            double lat = intent.getDoubleExtra("lat", 0.0);
            double lng = intent.getDoubleExtra("lng", 0.0);
            float speed = intent.getFloatExtra("speed", 0.0f);

            myCurrentPoint = new GeoPoint(lat, lng);
            currentMarker.setPosition(myCurrentPoint);
            currentMarker.setVisible(true);

            // Cắm cờ xuất phát tại điểm nhận đầu tiên nếu vừa bấm bắt đầu ghi
            if (isWaitingFirstStartPoint && currentMode == MODE_RECORDING) {
                startFlagMarker.setPosition(myCurrentPoint);
                startFlagMarker.setVisible(true);
                isWaitingFirstStartPoint = false;
            }

            if (currentMode == MODE_RECORDING) {
                trackLine.addPoint(myCurrentPoint);
                tvStats.setText(String.format(Locale.US, "Đã ghi: %d điểm\nTọa độ: %.5f, %.5f", dbHelper.getPointCount(), lat, lng));
            } else if (currentMode == MODE_FOLLOW_ROUTE) {
                // Chế độ hành quân bám GPX: Không vẽ đè vệt xanh
                mapView.getController().animateTo(myCurrentPoint);
            }

            // Đánh giá cự ly giãn cách thời gian thực giữa 2 xe
            evaluateConvoySpacing();

            // Định kỳ 1 giây: Gửi gói #POS có kèm Vehicle ID sang Heltec để phát sóng LoRa
            if (System.currentTimeMillis() - lastBtSendTime > 1000) {
                lastBtSendTime = System.currentTimeMillis();
                String posPacket = String.format(Locale.US, "#POS,%d,%.6f,%.6f,%.1f\n", myVehicleId, lat, lng, speed);
                sendBluetoothData(posPacket);
            }

            mapView.invalidate();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this));
        Configuration.getInstance().setUserAgentValue("J2_Military_Tactical_Tracker");

        setContentView(R.layout.activity_main);

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        myVehicleId = sharedPreferences.getInt("CFG_VEHICLE_ID", 1);
        teammateId = (myVehicleId == 1) ? 2 : 1;

        dbHelper = new DatabaseHelper(this);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // Chuông khẩn cấp (#CMD)
        Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (alarmUri == null) alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        alertRingtone = RingtoneManager.getRingtone(this, alarmUri);

        // Chuông cảnh báo vượt ngưỡng dãn cách 50m
        Uri notifUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        warningBeep = RingtoneManager.getRingtone(this, notifUri);

        initViews();
        setupMapView();
        setupMenuEvents();
        setupCommandButtons();
        updateRoleDisplay();
        checkPermissionsAndInit();

        // Cho phép bấm hoặc nhấn giữ thanh trạng thái đỉnh để chọn vai trò Xe 1 / Xe 2
        tvQuickStatus.setOnClickListener(v -> showRoleSelectionDialog());
        tvQuickStatus.setOnLongClickListener(v -> {
            showRoleSelectionDialog();
            return true;
        });

        startBluetoothConnection();
    }

    private void initViews() {
        drawerLayout = findViewById(R.id.drawer_layout);
        mapView = findViewById(R.id.mapView);
        tvQuickStatus = findViewById(R.id.tvQuickStatus);
        tvStats = findViewById(R.id.tvStats);
        btnOpenMenu = findViewById(R.id.btnOpenMenu);
        btnCloseMenu = findViewById(R.id.btnCloseMenu);
        btnStartRecord = findViewById(R.id.btnStartRecord);
        btnStopRecord = findViewById(R.id.btnStopRecord);
        btnFlagYellow = findViewById(R.id.btnFlagYellow);
        btnFlagPurple = findViewById(R.id.btnFlagPurple);
        btnClearFlags = findViewById(R.id.btnClearFlags);
        btnLoadFollowGpx = findViewById(R.id.btnLoadFollowGpx);
        btnClearRoute = findViewById(R.id.btnClearRoute);

        btnCmdStop = findViewById(R.id.btnCmdStop);
        btnCmdResume = findViewById(R.id.btnCmdResume);
        btnCmdSpeedUp = findViewById(R.id.btnCmdSpeedUp);
        btnCmdSlowDown = findViewById(R.id.btnCmdSlowDown);
        btnCmdCloseSpacing = findViewById(R.id.btnCmdCloseSpacing);
        btnCmdOpenSpacing = findViewById(R.id.btnCmdOpenSpacing);
        btnCmdEmergency = findViewById(R.id.btnCmdEmergency);
    }

    private void setupMapView() {
        mapView.setMultiTouchControls(true);
        mapView.setUseDataConnection(false);

        // Tuyến GPX dẫn đường: Màu xanh lục dạ quang (#0EDA4B)
        plannedGpxLine = new Polyline(mapView);
        plannedGpxLine.setColor(Color.parseColor("#0EDA4B"));
        plannedGpxLine.setWidth(9.0f);
        plannedGpxLine.getOutlinePaint().setStrokeJoin(Paint.Join.ROUND);
        plannedGpxLine.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
        mapView.getOverlays().add(plannedGpxLine);

        // Vệt thực tế màu xanh đậm (chế độ Ghi vết)
        trackLine = new Polyline(mapView);
        trackLine.setColor(Color.parseColor("#003399"));
        trackLine.setWidth(7.0f);
        trackLine.getOutlinePaint().setStrokeJoin(Paint.Join.ROUND);
        trackLine.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
        mapView.getOverlays().add(trackLine);

        // 1. CỜ XUẤT PHÁT: MÀU XANH LÁ TRƠN (KHÔNG CHỮ)
        startFlagMarker = new Marker(mapView);
        startFlagMarker.setAnchor(22f / 72f, 66f / 72f);
        startFlagMarker.setIcon(createTacticalFlagIcon(Color.parseColor("#00C853")));
        startFlagMarker.setTitle("XUẤT PHÁT");
        startFlagMarker.setVisible(false);
        mapView.getOverlays().add(startFlagMarker);

        // 2. CỜ ĐÍCH ĐẾN: MÀU ĐỎ TRƠN (KHÔNG CHỮ) - MẶC ĐỊNH ẨN HOÀN TOÀN
        finishFlagMarker = new Marker(mapView);
        finishFlagMarker.setAnchor(22f / 72f, 66f / 72f);
        finishFlagMarker.setIcon(createTacticalFlagIcon(Color.parseColor("#D50000")));
        finishFlagMarker.setTitle("ĐÍCH ĐẾN");
        finishFlagMarker.setVisible(false);
        mapView.getOverlays().add(finishFlagMarker);

        // Con trỏ xe bản thân
        currentMarker = new Marker(mapView);
        currentMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        currentMarker.setIcon(createVehicleDot(myVehicleId == 1 ? Color.parseColor("#007AFF") : Color.parseColor("#00C853")));
        currentMarker.setInfoWindow(null);
        currentMarker.setVisible(false);
        mapView.getOverlays().add(currentMarker);

        // Con trỏ xe đồng đội (LoRa Telemetry)
        teammateMarker = new Marker(mapView);
        teammateMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        teammateMarker.setIcon(createVehicleDot(Color.parseColor("#FF9100")));
        teammateMarker.setInfoWindow(null);
        teammateMarker.setVisible(false);
        mapView.getOverlays().add(teammateMarker);

        GeoPoint centerPoint = new GeoPoint(21.135, 105.505);
        mapView.getController().setZoom(14.0);
        mapView.getController().setCenter(centerPoint);
    }

    // Vẽ cờ tác chiến đuôi nheo bằng Canvas: CỜ TRƠN HOÀN TOÀN, KHÔNG CHỮ / KÝ HIỆU
    private BitmapDrawable createTacticalFlagIcon(int flagColor) {
        int width = 72;
        int height = 72;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        float poleX = 22f;

        // Cột cờ
        paint.setColor(Color.parseColor("#263238"));
        paint.setStrokeWidth(4.5f);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawLine(poleX, 8f, poleX, 66f, paint);

        // Đỉnh cột mạ vàng
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.parseColor("#FFD600"));
        canvas.drawCircle(poleX, 8f, 4.5f, paint);

        // Chân đế cột cờ
        paint.setColor(Color.parseColor("#212121"));
        canvas.drawCircle(poleX, 66f, 5.5f, paint);

        // Thân lá cờ (đuôi nheo tác chiến trơn)
        Path flagPath = new Path();
        flagPath.moveTo(poleX, 10f);
        flagPath.lineTo(poleX + 46f, 10f);
        flagPath.lineTo(poleX + 38f, 26f);
        flagPath.lineTo(poleX + 46f, 42f);
        flagPath.lineTo(poleX, 42f);
        flagPath.close();

        // Đổ màu lá cờ
        paint.setColor(flagColor);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(flagPath, paint);

        // Viền cờ màu trắng
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        canvas.drawPath(flagPath, paint);

        // Tuyệt đối không vẽ bất kỳ ký hiệu hay chữ nào lên cờ
        return new BitmapDrawable(getResources(), bitmap);
    }

    private BitmapDrawable createVehicleDot(int coreColor) {
        int size = 64;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        float cx = size / 2f;
        float cy = size / 2f;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(60, Color.red(coreColor), Color.green(coreColor), Color.blue(coreColor)));
        canvas.drawCircle(cx, cy, 28f, paint);

        paint.setColor(Color.WHITE);
        canvas.drawCircle(cx, cy, 16f, paint);

        paint.setColor(coreColor);
        canvas.drawCircle(cx, cy, 11f, paint);

        return new BitmapDrawable(getResources(), bitmap);
    }

    private void updateRoleDisplay() {
        teammateId = (myVehicleId == 1) ? 2 : 1;
        String roleStr = (myVehicleId == 1) ? "XE 01 (CHỈ HUY)" : "XE 02 (PHÂN ĐỘI)";
        tvQuickStatus.setText(String.format(Locale.US, "Vai trò: %s", roleStr));
        if (currentMarker != null) {
            currentMarker.setIcon(createVehicleDot(myVehicleId == 1 ? Color.parseColor("#007AFF") : Color.parseColor("#00C853")));
        }
    }

    private void showRoleSelectionDialog() {
        String[] roles = {"Xe 01 - Xe Chỉ Huy", "Xe 02 - Xe Phân Đội"};
        new AlertDialog.Builder(this)
                .setTitle("Cấu hình vai trò thiết bị")
                .setSingleChoiceItems(roles, myVehicleId - 1, (dialog, which) -> {
                    myVehicleId = which + 1;
                    sharedPreferences.edit().putInt("CFG_VEHICLE_ID", myVehicleId).apply();
                    updateRoleDisplay();
                    dialog.dismiss();
                    Toast.makeText(this, "Đã thiết lập: " + roles[which], Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    // --- GIÁM SÁT CỰ LY GIÃN CÁCH VÀ CẢNH BÁO > 50M ---
    private void evaluateConvoySpacing() {
        if (myCurrentPoint == null || teammatePoint == null) {
            return;
        }

        float[] results = new float[1];
        Location.distanceBetween(
                myCurrentPoint.getLatitude(), myCurrentPoint.getLongitude(),
                teammatePoint.getLatitude(), teammatePoint.getLongitude(),
                results
        );
        float distanceMeters = results[0];

        if (distanceMeters > CONVOY_SPACING_LIMIT_METERS) {
            tvStats.setTextColor(Color.RED);
            tvStats.setText(String.format(Locale.US, "⚠️ DÃN CỰ LY NGUY HIỂM: %.1f m (>50m)!\nĐồng đội (Xe %02d): %.1f km/h",
                    distanceMeters, teammateId, teammateSpeed));
            triggerSpacingAlert();
        } else {
            tvStats.setTextColor(Color.parseColor("#00897B"));
            tvStats.setText(String.format(Locale.US, "✅ CỰ LY ĐỘI HÌNH: %.1f m (Chuẩn <= 50m)\nĐồng đội (Xe %02d): %.1f km/h",
                    distanceMeters, teammateId, teammateSpeed));
        }
    }

    private void triggerSpacingAlert() {
        long now = System.currentTimeMillis();
        if (now - lastSpacingAlertTime > 6000) {
            lastSpacingAlertTime = now;
            try {
                if (vibrator != null) {
                    vibrator.vibrate(new long[]{0, 300, 150, 300}, -1);
                }
                if (warningBeep != null && !warningBeep.isPlaying()) {
                    warningBeep.play();
                }
            } catch (Exception ignored) {}
        }
    }

    // --- KẾT NỐI BLUETOOTH & BÓC TÁCH GÓI TIN ĐA XE ---
    @SuppressLint("MissingPermission")
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

            if (targetDevice == null) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Chưa ghép đôi LoRa_Tactical_Bridge!", Toast.LENGTH_SHORT).show());
                return;
            }

            try {
                bluetoothAdapter.cancelDiscovery();
                btSocket = targetDevice.createRfcommSocketToServiceRecord(SPP_UUID);
                btSocket.connect();
                btOutputStream = btSocket.getOutputStream();
                isBtConnected = true;

                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Đã kết nối cầu LoRa!", Toast.LENGTH_SHORT).show();
                    updateRoleDisplay();
                });

                BufferedReader reader = new BufferedReader(new InputStreamReader(btSocket.getInputStream()));
                String line;
                while (isBtConnected && (line = reader.readLine()) != null) {
                    final String receivedPacket = line.trim();

                    // Bóc tách tọa độ đồng đội qua LoRa: #POS,id,lat,lng,speed
                    if (receivedPacket.startsWith("#POS")) {
                        String[] parts = receivedPacket.split(",");
                        if (parts.length >= 5) {
                            try {
                                int senderId = Integer.parseInt(parts[1].trim());
                                if (senderId != myVehicleId) {
                                    double rLat = Double.parseDouble(parts[2].trim());
                                    double rLng = Double.parseDouble(parts[3].trim());
                                    float rSpeed = Float.parseFloat(parts[4].trim());

                                    runOnUiThread(() -> {
                                        teammatePoint = new GeoPoint(rLat, rLng);
                                        teammateSpeed = rSpeed;
                                        teammateMarker.setPosition(teammatePoint);
                                        teammateMarker.setVisible(true);
                                        evaluateConvoySpacing();
                                        mapView.invalidate();
                                    });
                                }
                            } catch (Exception ignored) {}
                        }
                    } else if (receivedPacket.startsWith("#CMD")) {
                        String[] parts = receivedPacket.split(",");
                        if (parts.length >= 4) {
                            String sender = "XE " + parts[1];
                            String cmdDesc = parts[3];
                            showCommandAlert(sender, cmdDesc);
                        }
                    }
                }
            } catch (IOException e) {
                isBtConnected = false;
                try {
                    if (btSocket != null) btSocket.close();
                } catch (Exception ignored) {}
            }
        });
        btConnectThread.start();
    }

    private void sendBluetoothData(String data) {
        if (isBtConnected && btOutputStream != null) {
            new Thread(() -> {
                try {
                    btOutputStream.write(data.getBytes());
                    btOutputStream.flush();
                } catch (IOException e) {
                    isBtConnected = false;
                }
            }).start();
        }
    }

    private void setupCommandButtons() {
        btnCmdStop.setOnClickListener(v -> sendTacticalCommand("0x01", "DỪNG XE"));
        btnCmdResume.setOnClickListener(v -> sendTacticalCommand("0x02", "TIẾP TỤC HÀNH QUÂN"));
        btnCmdSpeedUp.setOnClickListener(v -> sendTacticalCommand("0x03", "TĂNG TỐC"));
        btnCmdSlowDown.setOnClickListener(v -> sendTacticalCommand("0x04", "GIẢM TỐC"));
        btnCmdCloseSpacing.setOnClickListener(v -> sendTacticalCommand("0x05", "THU CỰ LY"));
        btnCmdOpenSpacing.setOnClickListener(v -> sendTacticalCommand("0x06", "DÃN CỰ LY"));
        btnCmdEmergency.setOnClickListener(v -> sendTacticalCommand("0x99", "TÌNH HUỐNG KHẨN CẤP"));
    }

    private void sendTacticalCommand(String cmdCode, String cmdDescription) {
        drawerLayout.closeDrawer(GravityCompat.START);
        String packet = String.format(Locale.US, "#CMD,%d,%s,%s\n", myVehicleId, cmdCode, cmdDescription);
        sendBluetoothData(packet);

        tvQuickStatus.setText("ĐÃ PHÁT: " + cmdDescription);
        Toast.makeText(this, "Đã phát lệnh LoRa: " + cmdDescription, Toast.LENGTH_SHORT).show();
    }

    public void showCommandAlert(String senderName, String commandText) {
        try {
            if (alertRingtone != null && !alertRingtone.isPlaying()) alertRingtone.play();
            if (vibrator != null) {
                long[] pattern = {0, 600, 300, 600, 300};
                vibrator.vibrate(pattern, 0);
            }
        } catch (Exception ignored) {}

        runOnUiThread(() -> {
            new AlertDialog.Builder(this)
                    .setTitle("🚨 MỆNH LỆNH TỪ " + senderName)
                    .setMessage("\n" + commandText + "\n\n(Tài xế/Trưởng xe lập tức chấp hành!)")
                    .setCancelable(false)
                    .setPositiveButton("ĐÃ NHẬN LỆNH", (dialog, which) -> {
                        if (alertRingtone != null && alertRingtone.isPlaying()) alertRingtone.stop();
                        if (vibrator != null) vibrator.cancel();
                        tvQuickStatus.setText("LỆNH: " + commandText);
                        sendBluetoothData(String.format(Locale.US, "#ACK,%d,RECEIVED\n", myVehicleId));
                    })
                    .show();
        });
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null) return;
        GeoPoint currentPoint = new GeoPoint(location.getLatitude(), location.getLongitude());
        myCurrentPoint = currentPoint;
        currentMarker.setPosition(currentPoint);
        currentMarker.setVisible(true);

        if (isFirstGpsFix) {
            mapView.getController().animateTo(currentPoint);
            mapView.getController().setZoom(16.0);
            isFirstGpsFix = false;
        }
        evaluateConvoySpacing();
        mapView.invalidate();
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override
    public void onProviderEnabled(String provider) {}
    @Override
    public void onProviderDisabled(String provider) {}

    private void setupMenuEvents() {
        btnOpenMenu.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
        btnCloseMenu.setOnClickListener(v -> drawerLayout.closeDrawer(GravityCompat.START));

        btnStartRecord.setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            startModeRecord();
        });

        btnStopRecord.setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            stopModeRecordAndExport();
        });

        // 1. CẮM CỜ VÀNG (GHI NHỚ): CỜ TRƠN KHÔNG CHỮ
        btnFlagYellow.setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            if (myCurrentPoint != null) {
                Marker flag = new Marker(mapView);
                flag.setPosition(myCurrentPoint);
                flag.setAnchor(22f / 72f, 66f / 72f);
                flag.setIcon(createTacticalFlagIcon(Color.parseColor("#F57F17")));
                flag.setTitle("GHI NHỚ");
                mapView.getOverlays().add(flag);
                tacticalFlagMarkers.add(flag);
                mapView.invalidate();
                Toast.makeText(this, "Đã cắm Cờ Vàng (Ghi nhớ)", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Chưa có GPS!", Toast.LENGTH_SHORT).show();
            }
        });

        // 2. CẮM CỜ TÍM (KIỂM TRA): CỜ TRƠN KHÔNG CHỮ
        btnFlagPurple.setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            if (myCurrentPoint != null) {
                Marker flag = new Marker(mapView);
                flag.setPosition(myCurrentPoint);
                flag.setAnchor(22f / 72f, 66f / 72f);
                flag.setIcon(createTacticalFlagIcon(Color.parseColor("#7B1FA2")));
                flag.setTitle("KIỂM TRA");
                mapView.getOverlays().add(flag);
                tacticalFlagMarkers.add(flag);
                mapView.invalidate();
                Toast.makeText(this, "Đã cắm Cờ Tím (Kiểm tra)", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Chưa có GPS!", Toast.LENGTH_SHORT).show();
            }
        });

        // 3. XÓA TOÀN BỘ CỜ MỐC ĐÃ CẮM
        btnClearFlags.setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            for (Marker m : tacticalFlagMarkers) {
                mapView.getOverlays().remove(m);
            }
            tacticalFlagMarkers.clear();
            mapView.invalidate();
            Toast.makeText(this, "Đã xóa toàn bộ cờ mốc", Toast.LENGTH_SHORT).show();
        });

        btnLoadFollowGpx.setOnClickListener(v -> pickGpxForNavigation());

        btnClearRoute.setOnClickListener(v -> {
            plannedGpxLine.getActualPoints().clear();
            trackLine.getActualPoints().clear();

            if (startFlagMarker != null) startFlagMarker.setVisible(false);
            if (finishFlagMarker != null) finishFlagMarker.setVisible(false);

            currentMode = MODE_STANDBY;
            updateRoleDisplay();
            mapView.invalidate();
            drawerLayout.closeDrawer(GravityCompat.START);

            Intent intent = new Intent(this, TrackingService.class);
            stopService(intent);
            Toast.makeText(this, "Đã xóa lộ trình hành quân", Toast.LENGTH_SHORT).show();
        });
    }

    // --- BẮT ĐẦU GHI HÀNH TRÌNH -> CẮM CỜ XUẤT PHÁT (ẨN CỜ ĐÍCH) ---
    private void startModeRecord() {
        currentMode = MODE_RECORDING;
        tvQuickStatus.setText("Chế độ: ĐANG GHI");

        dbHelper.clearAllPoints();
        trackLine.getActualPoints().clear();
        trackLine.setVisible(true);

        // Ẩn cờ Đích khi bắt đầu hành trình mới
        if (finishFlagMarker != null) finishFlagMarker.setVisible(false);

        if (myCurrentPoint != null) {
            startFlagMarker.setPosition(myCurrentPoint);
            startFlagMarker.setVisible(true);
            isWaitingFirstStartPoint = false;
        } else {
            isWaitingFirstStartPoint = true;
        }

        mapView.invalidate();

        Intent intent = new Intent(this, TrackingService.class);
        ContextCompat.startForegroundService(this, intent);

        btnStartRecord.setEnabled(false);
        btnStopRecord.setEnabled(true);
        Toast.makeText(this, "Bắt đầu ghi vết & Cắm cờ Xuất phát!", Toast.LENGTH_SHORT).show();
    }

    // --- CHỈ KHI BẤM DỪNG & XUẤT THÌ MỚI CẮM CỜ ĐÍCH ĐẾN ---
    private void stopModeRecordAndExport() {
        Intent intent = new Intent(this, TrackingService.class);
        stopService(intent);

        // CỜ ĐÍCH CHỈ XUẤT HIỆN DUY NHẤT TẠI ĐÂY
        if (myCurrentPoint != null) {
            finishFlagMarker.setPosition(myCurrentPoint);
            finishFlagMarker.setVisible(true);
            mapView.invalidate();
        }

        currentMode = MODE_STANDBY;
        updateRoleDisplay();
        btnStartRecord.setEnabled(true);
        btnStopRecord.setEnabled(false);
        exportGpxFile();
    }

    private void pickGpxForNavigation() {
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!downloadDir.exists()) {
            Toast.makeText(this, "Chưa có thư mục Download!", Toast.LENGTH_SHORT).show();
            return;
        }

        File[] gpxFiles = downloadDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".gpx"));
        if (gpxFiles == null || gpxFiles.length == 0) {
            Toast.makeText(this, "Không có file .gpx trong Download!", Toast.LENGTH_LONG).show();
            return;
        }

        String[] fileNames = new String[gpxFiles.length];
        for (int i = 0; i < gpxFiles.length; i++) {
            fileNames[i] = gpxFiles[i].getName();
        }

        new AlertDialog.Builder(this)
                .setTitle("Chọn lộ trình dẫn đường mẫu")
                .setItems(fileNames, (dialog, which) -> loadPlannedGpx(gpxFiles[which]))
                .setNegativeButton("Hủy", null)
                .show();
    }

    private void loadPlannedGpx(File gpxFile) {
        List<GeoPoint> points = new ArrayList<>();
        try (InputStream inputStream = new FileInputStream(gpxFile)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(inputStream, null);
            int eventType = parser.getEventType();

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    String name = parser.getName();
                    if ("trkpt".equalsIgnoreCase(name) || "rtept".equalsIgnoreCase(name)) {
                        String latStr = parser.getAttributeValue(null, "lat");
                        String lonStr = parser.getAttributeValue(null, "lon");
                        if (latStr != null && lonStr != null) {
                            points.add(new GeoPoint(Double.parseDouble(latStr), Double.parseDouble(lonStr)));
                        }
                    }
                }
                eventType = parser.next();
            }

            if (!points.isEmpty()) {
                trackLine.getActualPoints().clear();
                trackLine.setVisible(false);

                // Tuyến lộ trình xanh lục dạ quang #0EDA4B
                plannedGpxLine.setPoints(points);

                // Chỉ cắm cờ Xuất phát, TUYỆT ĐỐI KHÔNG CẮM CỜ ĐÍCH TẠI ĐÂY
                startFlagMarker.setPosition(points.get(0));
                startFlagMarker.setVisible(true);
                finishFlagMarker.setVisible(false);

                mapView.getController().animateTo(points.get(0));
                mapView.getController().setZoom(16.0);
                mapView.invalidate();

                currentMode = MODE_FOLLOW_ROUTE;
                tvQuickStatus.setText("LỘ TRÌNH: BÁM ĐƯỜNG GPX");
                drawerLayout.closeDrawer(GravityCompat.START);

                Intent intent = new Intent(this, TrackingService.class);
                ContextCompat.startForegroundService(this, intent);

                Toast.makeText(this, "Đang dẫn đường: " + gpxFile.getName(), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Lỗi nạp GPX: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void loadOfflineMap() {
        File mapFile = new File(Environment.getExternalStorageDirectory(), "osmdroid/BanDoJ2.mbtiles");
        if (mapFile.exists()) {
            try {
                File[] archives = new File[]{mapFile};
                OfflineTileProvider tileProvider = new OfflineTileProvider(new SimpleRegisterReceiver(this), archives);
                mapView.setTileProvider(tileProvider);

                IArchiveFile[] archiveFiles = tileProvider.getArchives();
                if (archiveFiles.length > 0) {
                    Set<String> tileSources = archiveFiles[0].getTileSources();
                    if (!tileSources.isEmpty()) {
                        String source = tileSources.iterator().next();
                        mapView.setTileSource(FileBasedTileSource.getSource(source));
                    }
                }
                mapView.invalidate();
            } catch (Exception e) {
                Toast.makeText(this, "Lỗi đọc map: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "Thiếu file osmdroid/BanDoJ2.mbtiles", Toast.LENGTH_SHORT).show();
        }
    }

    private void exportGpxFile() {
        Cursor cursor = dbHelper.getAllPoints();
        if (cursor == null || cursor.getCount() == 0) {
            Toast.makeText(this, "Không có tọa độ để xuất!", Toast.LENGTH_SHORT).show();
            if (cursor != null) cursor.close();
            return;
        }

        File exportDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!exportDir.exists()) exportDir.mkdirs();

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File gpxFile = new File(exportDir, "Track_" + timeStamp + ".gpx");

        try (FileWriter writer = new FileWriter(gpxFile)) {
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"J2OfflineTracker\">\n  <trk>\n    <name>Track " + timeStamp + "</name>\n    <trkseg>\n");
            SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);

            while (cursor.moveToNext()) {
                double lat = cursor.getDouble(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_LAT));
                double lng = cursor.getDouble(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_LNG));
                float speed = cursor.getFloat(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_SPEED));
                long time = cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_TIME));

                writer.write(String.format(Locale.US, "      <trkpt lat=\"%.6f\" lon=\"%.6f\">\n        <speed>%.2f</speed>\n        <time>%s</time>\n      </trkpt>\n",
                        lat, lng, speed, isoFormat.format(new Date(time))));
            }
            writer.write("    </trkseg>\n  </trk>\n</gpx>");
            Toast.makeText(this, "Đã lưu vết vào Download:\n" + gpxFile.getName(), Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, "Lỗi xuất GPX: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        } finally {
            cursor.close();
        }
    }

    private void loadExistingTrackFromDb() {
        Cursor cursor = dbHelper.getAllPoints();
        if (cursor != null && cursor.getCount() > 0) {
            trackLine.getActualPoints().clear();
            GeoPoint firstPoint = null;
            GeoPoint lastPoint = null;

            while (cursor.moveToNext()) {
                double lat = cursor.getDouble(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_LAT));
                double lng = cursor.getDouble(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_LNG));
                GeoPoint pt = new GeoPoint(lat, lng);

                if (firstPoint == null) {
                    firstPoint = pt;
                }
                lastPoint = pt;
                trackLine.addPoint(pt);
            }
            cursor.close();

            if (firstPoint != null) {
                startFlagMarker.setPosition(firstPoint);
                startFlagMarker.setVisible(true);
            }
            // Không tự động cắm cờ Đích khi nạp lại vết cũ
            if (lastPoint != null) {
                currentMarker.setPosition(lastPoint);
                currentMarker.setVisible(true);
            }

            tvStats.setText(String.format(Locale.US, "Đã ghi: %d điểm", dbHelper.getPointCount()));
            mapView.invalidate();
        }
    }

    private void startImmediateLocationListening() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        try {
            Location lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (lastKnown != null) {
                myCurrentPoint = new GeoPoint(lastKnown.getLatitude(), lastKnown.getLongitude());
                currentMarker.setPosition(myCurrentPoint);
                currentMarker.setVisible(true);
            }
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0.0f, this);
        } catch (Exception ignored) {}
    }

    private void checkPermissionsAndInit() {
        String[] permissions = {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        };

        boolean allGranted = true;
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) {
            loadOfflineMap();
            startImmediateLocationListening();
        } else {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                loadOfflineMap();
                startImmediateLocationListening();
            } else {
                Toast.makeText(this, "Cần cấp đủ quyền Vị trí & Bộ nhớ!", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();

        // Đăng ký BroadcastReceiver tương thích tuyệt đối mọi phiên bản Android
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(locationReceiver, new IntentFilter("GPS_LOCATION_UPDATE"), Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(locationReceiver, new IntentFilter("GPS_LOCATION_UPDATE"));
            }
        } catch (Exception ignored) {}

        if (currentMode == MODE_RECORDING) {
            loadExistingTrackFromDb();
        }
        startImmediateLocationListening();

        if (!isBtConnected) {
            startBluetoothConnection();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
        try {
            unregisterReceiver(locationReceiver);
        } catch (Exception ignored) {}

        if (currentMode != MODE_RECORDING && currentMode != MODE_FOLLOW_ROUTE) {
            try {
                locationManager.removeUpdates(this);
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mapView.onDetach();
        isBtConnected = false;
        try {
            if (btSocket != null) btSocket.close();
        } catch (Exception ignored) {}

        if (alertRingtone != null && alertRingtone.isPlaying()) alertRingtone.stop();
        if (warningBeep != null && warningBeep.isPlaying()) warningBeep.stop();
        if (vibrator != null) vibrator.cancel();
    }
}
