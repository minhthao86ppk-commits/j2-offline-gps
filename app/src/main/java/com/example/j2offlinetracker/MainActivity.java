package com.example.j2offlinetracker;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
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

    // Chế độ vận hành
    private static final int MODE_STANDBY = 0;
    private static final int MODE_RECORDING = 1;
    private static final int MODE_FOLLOW_ROUTE = 2;

    private int currentMode = MODE_STANDBY;
    private boolean isFirstGpsFix = true;

    // Vai trò xe (Supervisory Role)
    private int myVehicleId = 1; // Mặc định 1: Chỉ huy, 2: Phân đội

    // Giao diện
    private DrawerLayout drawerLayout;
    private MapView mapView;
    private TextView tvQuickStatus, tvStats;
    private Button btnOpenMenu, btnCloseMenu;
    private Button btnStartRecord, btnStopRecord;
    private Button btnFlagYellow, btnFlagPurple, btnClearFlags;
    private Button btnLoadFollowGpx, btnClearRoute;

    private Button btnCmdStop, btnCmdResume, btnCmdSpeedUp, btnCmdSlowDown;
    private Button btnCmdCloseSpacing, btnCmdOpenSpacing, btnCmdEmergency;

    // Bản đồ & Vết vẽ
    private Polyline trackLine;
    private Polyline plannedGpxLine;
    private Marker currentMarker;
    private Marker teammateMarker;
    private Marker startFlagMarker;
    private Marker destFlagMarker;
    private final List<Marker> tacticalFlagMarkers = new ArrayList<>();

    // Cảm biến & Lưu trữ
    private DatabaseHelper dbHelper;
    private LocationManager locationManager;
    private Vibrator vibrator;
    private Ringtone alertRingtone;
    private GeoPoint myLastGeoPoint = null;
    private GeoPoint teammateLastGeoPoint = null;
    private long lastWarningSoundTime = 0;

    // Kết nối LoRa qua Bluetooth Classic SPP
    private static final String TARGET_BT_NAME = "LoRa_Tactical_Bridge";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket btSocket;
    private OutputStream btOutputStream;
    private Thread btConnectThread;
    private boolean isBtConnected = false;
    private long lastBtSendTime = 0;

    // Bộ nhận tọa độ từ TrackingService ngầm
    private final BroadcastReceiver locationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            double lat = intent.getDoubleExtra("lat", 0.0);
            double lng = intent.getDoubleExtra("lng", 0.0);
            float speed = intent.getFloatExtra("speed", 0.0f);
            GeoPoint pt = new GeoPoint(lat, lng);
            myLastGeoPoint = pt;

            currentMarker.setPosition(pt);
            currentMarker.setVisible(true);

            if (currentMode == MODE_RECORDING) {
                trackLine.addPoint(pt);
                updateStatsDisplay();
            } else if (currentMode == MODE_FOLLOW_ROUTE) {
                // Tuyến dẫn đường GPX: Chỉ trượt tâm theo vị trí, không vẽ đè vệt
                mapView.getController().animateTo(pt);
            }

            evaluateConvoySpacing();

            // Định kỳ 1 giây: Truyền tọa độ sang Heltec để phát sóng LoRa
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
        Configuration.getInstance().setUserAgentValue("J2_Tactical_Tracker");

        setContentView(R.layout.activity_main);

        dbHelper = new DatabaseHelper(this);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (alarmUri == null) alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        alertRingtone = RingtoneManager.getRingtone(this, alarmUri);

        initViews();
        setupMapView();
        setupMenuEvents();
        setupTacticalFlagEvents();
        setupCommandButtons();
        setupRoleToggle();
        checkPermissionsAndInit();

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

        // Tuyến GPX mẫu dạ quang (#0EDA4B)
        plannedGpxLine = new Polyline(mapView);
        plannedGpxLine.setColor(Color.parseColor("#0EDA4B"));
        plannedGpxLine.setWidth(9.0f);
        plannedGpxLine.getOutlinePaint().setStrokeJoin(Paint.Join.ROUND);
        plannedGpxLine.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
        mapView.getOverlays().add(plannedGpxLine);

        // Vệt thực tế màu xanh đậm (#003399)
        trackLine = new Polyline(mapView);
        trackLine.setColor(Color.parseColor("#003399"));
        trackLine.setWidth(7.0f);
        trackLine.getOutlinePaint().setStrokeJoin(Paint.Join.ROUND);
        trackLine.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
        mapView.getOverlays().add(trackLine);

        // Marker vị trí xe mình
        currentMarker = new Marker(mapView);
        currentMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        currentMarker.setIcon(createVehicleIcon(myVehicleId == 1 ? Color.parseColor("#007AFF") : Color.parseColor("#00E676"), String.format(Locale.US, "%02d", myVehicleId)));
        currentMarker.setInfoWindow(null);
        currentMarker.setVisible(false);
        mapView.getOverlays().add(currentMarker);

        // Marker xe đồng đội (LoRa)
        teammateMarker = new Marker(mapView);
        teammateMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        teammateMarker.setIcon(createVehicleIcon(Color.parseColor("#FF9800"), myVehicleId == 1 ? "02" : "01"));
        teammateMarker.setInfoWindow(null);
        teammateMarker.setVisible(false);
        mapView.getOverlays().add(teammateMarker);

        mapView.getController().setZoom(15.0);
    }

    // Biểu tượng Marker dập số xe
    private BitmapDrawable createVehicleIcon(int mainColor, String label) {
        int size = 68;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        float cx = size / 2f;
        float cy = size / 2f;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(cx, cy, 26f, paint);

        paint.setColor(mainColor);
        canvas.drawCircle(cx, cy, 22f, paint);

        paint.setColor(Color.WHITE);
        paint.setTextSize(16f);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextAlign(Paint.Align.CENTER);
        float yPos = cy - ((paint.descent() + paint.ascent()) / 2);
        canvas.drawText(label, cx, yPos, paint);

        return new BitmapDrawable(getResources(), bitmap);
    }

    // Biểu tượng Cờ mốc dã chiến trơn hoặc có chữ XP/ĐÍCH
    private BitmapDrawable createFlagDotIcon(int color, String text) {
        int size = 60;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        float cx = size / 2f;
        float cy = size / 2f;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(cx, cy, 22f, paint);

        paint.setColor(color);
        canvas.drawCircle(cx, cy, 18f, paint);

        if (text != null && !text.isEmpty()) {
            paint.setColor(Color.WHITE);
            paint.setTextSize(12f);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            paint.setTextAlign(Paint.Align.CENTER);
            float yPos = cy - ((paint.descent() + paint.ascent()) / 2);
            canvas.drawText(text, cx, yPos, paint);
        }

        return new BitmapDrawable(getResources(), bitmap);
    }

    private void setupRoleToggle() {
        tvQuickStatus.setOnLongClickListener(v -> {
            myVehicleId = (myVehicleId == 1) ? 2 : 1;
            updateRoleDisplay();
            currentMarker.setIcon(createVehicleIcon(myVehicleId == 1 ? Color.parseColor("#007AFF") : Color.parseColor("#00E676"), String.format(Locale.US, "%02d", myVehicleId)));
            teammateMarker.setIcon(createVehicleIcon(Color.parseColor("#FF9800"), myVehicleId == 1 ? "02" : "01"));
            mapView.invalidate();
            Toast.makeText(this, "Đã chuyển vai trò: XE " + String.format(Locale.US, "%02d", myVehicleId), Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    private void updateRoleDisplay() {
        String roleTitle = (myVehicleId == 1) ? "XE 01 (CHỈ HUY)" : "XE 02 (PHÂN ĐỘI)";
        tvQuickStatus.setText(roleTitle + " - " + (isBtConnected ? "LoRa SẴN SÀNG" : "CHỜ LORA..."));
        tvQuickStatus.setTextColor(isBtConnected ? Color.parseColor("#00FF66") : Color.YELLOW);
    }

    private void setupTacticalFlagEvents() {
        // Cờ Vàng trơn
        btnFlagYellow.setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            if (myLastGeoPoint != null) {
                dropTacticalFlag(myLastGeoPoint, Color.parseColor("#F57F17"), "");
                Toast.makeText(this, "Đã cắm Cờ Vàng (Ghi nhớ)", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Chưa có GPS!", Toast.LENGTH_SHORT).show();
            }
        });

        // Cờ Tím trơn
        btnFlagPurple.setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            if (myLastGeoPoint != null) {
                dropTacticalFlag(myLastGeoPoint, Color.parseColor("#6A1B9A"), "");
                Toast.makeText(this, "Đã cắm Cờ Tím (Kiểm tra)", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Chưa có GPS!", Toast.LENGTH_SHORT).show();
            }
        });

        // Xóa toàn bộ cờ mốc
        btnClearFlags.setOnClickListener(v -> {
            for (Marker m : tacticalFlagMarkers) mapView.getOverlays().remove(m);
            tacticalFlagMarkers.clear();

            if (startFlagMarker != null) {
                mapView.getOverlays().remove(startFlagMarker);
                startFlagMarker = null;
            }
            if (destFlagMarker != null) {
                mapView.getOverlays().remove(destFlagMarker);
                destFlagMarker = null;
            }
            mapView.invalidate();
            drawerLayout.closeDrawer(GravityCompat.START);
            Toast.makeText(this, "Đã xóa toàn bộ cờ mốc tác chiến", Toast.LENGTH_SHORT).show();
        });
    }

    private void dropTacticalFlag(GeoPoint point, int color, String label) {
        Marker flag = new Marker(mapView);
        flag.setPosition(point);
        flag.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        flag.setIcon(createFlagDotIcon(color, label));
        flag.setInfoWindow(null);
        mapView.getOverlays().add(flag);
        tacticalFlagMarkers.add(flag);
        mapView.invalidate();
    }

    private void evaluateConvoySpacing() {
        if (myLastGeoPoint == null || teammateLastGeoPoint == null) return;

        // Tính khoảng cách giữa 2 xe bằng thuật toán gốc Android
        float[] results = new float[1];
        Location.distanceBetween(
                myLastGeoPoint.getLatitude(), myLastGeoPoint.getLongitude(),
                teammateLastGeoPoint.getLatitude(), teammateLastGeoPoint.getLongitude(),
                results);
        float distance = results[0];

        tvStats.setText(String.format(Locale.US, "Điểm: %d | Cự ly Xe bạn: %.1fm", dbHelper.getPointCount(), distance));

        if (distance > 50.0f) {
            tvStats.setTextColor(Color.RED);
            if (System.currentTimeMillis() - lastWarningSoundTime > 6000) {
                lastWarningSoundTime = System.currentTimeMillis();
                try {
                    if (vibrator != null) vibrator.vibrate(500);
                } catch (Exception ignored) {}
            }
        } else {
            tvStats.setTextColor(Color.WHITE);
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
        Toast.makeText(this, "Đã phát lệnh LoRa: " + cmdDescription, Toast.LENGTH_SHORT).show();
    }

    public void showCommandAlert(String senderName, String commandText) {
        try {
            if (alertRingtone != null && !alertRingtone.isPlaying()) alertRingtone.play();
            if (vibrator != null) vibrator.vibrate(new long[]{0, 500, 200, 500}, 0);
        } catch (Exception ignored) {}

        runOnUiThread(() -> {
            new AlertDialog.Builder(this)
                    .setTitle("🚨 MỆNH LỆNH TỪ " + senderName.toUpperCase())
                    .setMessage("\n" + commandText + "\n\n(Chấp hành mệnh lệnh tác chiến!)")
                    .setCancelable(false)
                    .setPositiveButton("ĐÃ NHẬN LỆNH", (dialog, which) -> {
                        if (alertRingtone != null && alertRingtone.isPlaying()) alertRingtone.stop();
                        if (vibrator != null) vibrator.cancel();
                        sendBluetoothData(String.format(Locale.US, "#ACK,%d,RECEIVED\n", myVehicleId));
                    })
                    .show();
        });
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

                runOnUiThread(this::updateRoleDisplay);

                BufferedReader reader = new BufferedReader(new InputStreamReader(btSocket.getInputStream()));
                String line;
                while (isBtConnected && (line = reader.readLine()) != null) {
                    final String packet = line.trim();
                    if (packet.startsWith("#CMD")) {
                        String[] parts = packet.split(",");
                        if (parts.length >= 4) {
                            int senderId = Integer.parseInt(parts[1]);
                            String desc = parts[3];
                            showCommandAlert("XE " + String.format(Locale.US, "%02d", senderId), desc);
                        }
                    } else if (packet.startsWith("#POS")) {
                        String[] parts = packet.split(",");
                        if (parts.length >= 5) {
                            int partnerId = Integer.parseInt(parts[1]);
                            if (partnerId != myVehicleId) {
                                double pLat = Double.parseDouble(parts[2]);
                                double pLng = Double.parseDouble(parts[3]);
                                teammateLastGeoPoint = new GeoPoint(pLat, pLng);
                                runOnUiThread(() -> {
                                    teammateMarker.setPosition(teammateLastGeoPoint);
                                    teammateMarker.setVisible(true);
                                    evaluateConvoySpacing();
                                    mapView.invalidate();
                                });
                            }
                        }
                    }
                }
            } catch (IOException e) {
                isBtConnected = false;
                runOnUiThread(this::updateRoleDisplay);
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

        btnLoadFollowGpx.setOnClickListener(v -> pickGpxForNavigation());

        btnClearRoute.setOnClickListener(v -> {
            plannedGpxLine.getActualPoints().clear();
            currentMode = MODE_STANDBY;
            mapView.invalidate();
            drawerLayout.closeDrawer(GravityCompat.START);
            Toast.makeText(this, "Đã hủy tuyến dẫn đường", Toast.LENGTH_SHORT).show();
        });
    }

    private void startModeRecord() {
        currentMode = MODE_RECORDING;
        dbHelper.clearAllPoints();
        trackLine.getActualPoints().clear();

        // Cắm Cờ Xuất Phát (Xanh lá)
        if (myLastGeoPoint != null) {
            if (startFlagMarker != null) mapView.getOverlays().remove(startFlagMarker);
            startFlagMarker = new Marker(mapView);
            startFlagMarker.setPosition(myLastGeoPoint);
            startFlagMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            startFlagMarker.setIcon(createFlagDotIcon(Color.parseColor("#2E7D32"), "XP"));
            startFlagMarker.setInfoWindow(null);
            mapView.getOverlays().add(startFlagMarker);
        }

        mapView.invalidate();

        Intent intent = new Intent(this, TrackingService.class);
        ContextCompat.startForegroundService(this, intent);

        btnStartRecord.setEnabled(false);
        btnStopRecord.setEnabled(true);
        Toast.makeText(this, "Bắt đầu hành quân & cắm cờ XP!", Toast.LENGTH_SHORT).show();
    }

    private void stopModeRecordAndExport() {
        Intent intent = new Intent(this, TrackingService.class);
        stopService(intent);

        currentMode = MODE_STANDBY;
        btnStartRecord.setEnabled(true);
        btnStopRecord.setEnabled(false);

        // Cắm Cờ Đích Đến (Đỏ) CHỈ khi bấm Dừng & Xuất
        if (myLastGeoPoint != null) {
            if (destFlagMarker != null) mapView.getOverlays().remove(destFlagMarker);
            destFlagMarker = new Marker(mapView);
            destFlagMarker.setPosition(myLastGeoPoint);
            destFlagMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            destFlagMarker.setIcon(createFlagDotIcon(Color.parseColor("#C62828"), "ĐÍCH"));
            destFlagMarker.setInfoWindow(null);
            mapView.getOverlays().add(destFlagMarker);
            mapView.invalidate();
        }

        exportGpxFile();
    }

    private void pickGpxForNavigation() {
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!downloadDir.exists()) return;

        File[] gpxFiles = downloadDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".gpx"));
        if (gpxFiles == null || gpxFiles.length == 0) {
            Toast.makeText(this, "Không có file .gpx trong Download!", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] fileNames = new String[gpxFiles.length];
        for (int i = 0; i < gpxFiles.length; i++) fileNames[i] = gpxFiles[i].getName();

        new AlertDialog.Builder(this)
                .setTitle("Chọn tuyến GPX")
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
                plannedGpxLine.setPoints(points);
                mapView.getController().animateTo(points.get(0));
                currentMode = MODE_FOLLOW_ROUTE;
                drawerLayout.closeDrawer(GravityCompat.START);
                mapView.invalidate();
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
            } catch (Exception ignored) {}
        }
    }

    private void exportGpxFile() {
        Cursor cursor = dbHelper.getAllPoints();
        if (cursor == null || cursor.getCount() == 0) {
            Toast.makeText(this, "Không có dữ liệu vệt để xuất!", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "Đã xuất GPX:\n" + gpxFile.getName(), Toast.LENGTH_LONG).show();
        } catch (IOException ignored) {} finally {
            cursor.close();
        }
    }

    private void updateStatsDisplay() {
        tvStats.setText(String.format(Locale.US, "Đã ghi: %d điểm | Cự ly: --", dbHelper.getPointCount()));
    }

    private void loadExistingTrackFromDb() {
        Cursor cursor = dbHelper.getAllPoints();
        if (cursor != null) {
            trackLine.getActualPoints().clear();
            GeoPoint lastPoint = null;
            while (cursor.moveToNext()) {
                double lat = cursor.getDouble(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_LAT));
                double lng = cursor.getDouble(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_LNG));
                lastPoint = new GeoPoint(lat, lng);
                trackLine.addPoint(lastPoint);
            }
            cursor.close();
            if (lastPoint != null) {
                currentMarker.setPosition(lastPoint);
                currentMarker.setVisible(true);
            }
            updateStatsDisplay();
            mapView.invalidate();
        }
    }

    private void startImmediateLocationListening() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        try {
            Location lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (lastKnown != null) {
                onLocationChanged(lastKnown);
            }
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1.0f, this);
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
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadOfflineMap();
            startImmediateLocationListening();
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null) return;
        GeoPoint pt = new GeoPoint(location.getLatitude(), location.getLongitude());
        myLastGeoPoint = pt;
        currentMarker.setPosition(pt);
        currentMarker.setVisible(true);

        if (isFirstGpsFix) {
            mapView.getController().animateTo(pt);
            mapView.getController().setZoom(16.0);
            isFirstGpsFix = false;
        }
        mapView.invalidate();
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override
    public void onProviderEnabled(String provider) {}
    @Override
    public void onProviderDisabled(String provider) {}

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
        registerReceiver(locationReceiver, new IntentFilter("GPS_LOCATION_UPDATE"));

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
        if (vibrator != null) vibrator.cancel();
    }
}
