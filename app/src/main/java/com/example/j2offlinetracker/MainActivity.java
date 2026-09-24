package com.example.j2offlinetracker;

import android.Manifest;
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
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.location.Location;
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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 200;
    private static final float CONVOY_SPACING_LIMIT_METERS = 50.0f;

    // Chế độ hoạt động
    private static final int MODE_STANDBY = 0;
    private static final int MODE_RECORDING = 1;
    private static final int MODE_FOLLOW_ROUTE = 2;

    private int currentMode = MODE_STANDBY;
    private boolean isFirstGpsFix = true;
    private boolean pendingStartFlag = false;

    // Định danh vai trò xe
    private int myVehicleId = 1;
    private int teammateId = 2;
    private SharedPreferences sharedPreferences;

    // Giao diện
    private DrawerLayout drawerLayout;
    private MapView mapView;
    private TextView tvQuickStatus, tvStats;
    private Button btnOpenMenu, btnCloseMenu;
    private Button btnStartRecord, btnStopRecord;
    private Button btnLoadFollowGpx, btnClearRoute;

    private Button btnCmdStop, btnCmdResume, btnCmdSpeedUp, btnCmdSlowDown;
    private Button btnCmdCloseSpacing, btnCmdOpenSpacing, btnCmdEmergency;

    // Bản đồ & Lớp phủ hiển thị
    private Polyline trackLine;
    private Polyline plannedGpxLine;
    private Marker currentMarker;
    private Marker teammateMarker;
    private Marker startFlagMarker;
    private Marker finishFlagMarker;

    // Tọa độ & Đo cự ly
    private GeoPoint myCurrentPoint = null;
    private GeoPoint teammatePoint = null;
    private float teammateSpeed = 0.0f;
    private long lastSpacingAlertTime = 0;

    // Cơ sở dữ liệu & Cảnh báo âm thanh/rung
    private DatabaseHelper dbHelper;
    private Vibrator vibrator;
    private Ringtone alertRingtone;
    private Ringtone warningBeep;

    // BỘ THU PHÁT TOÀN DIỆN TỪ TRACKING SERVICE (GPS, BLUETOOTH LORA, MỆNH LỆNH)
    private final BroadcastReceiver tacticalServiceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            switch (action) {
                case "GPS_LOCATION_UPDATE":
                    double lat = intent.getDoubleExtra("lat", 0.0);
                    double lng = intent.getDoubleExtra("lng", 0.0);
                    float speed = intent.getFloatExtra("speed", 0.0f);

                    myCurrentPoint = new GeoPoint(lat, lng);
                    currentMarker.setPosition(myCurrentPoint);
                    currentMarker.setVisible(true);

                    if (isFirstGpsFix) {
                        mapView.getController().animateTo(myCurrentPoint);
                        mapView.getController().setZoom(16.0);
                        isFirstGpsFix = false;
                    }

                    if (currentMode == MODE_RECORDING) {
                        trackLine.addPoint(myCurrentPoint);
                        // Cắm cờ Xuất phát nếu lúc bấm BẮT ĐẦU chưa có GPS fix
                        if (pendingStartFlag) {
                            plantStartFlag(myCurrentPoint);
                        }
                        tvStats.setText(String.format(Locale.US, "Đã ghi: %d điểm\nTọa độ: %.5f, %.5f",
                                dbHelper.getPointCount(), lat, lng));
                    } else if (currentMode == MODE_FOLLOW_ROUTE) {
                        mapView.getController().animateTo(myCurrentPoint);
                    }

                    evaluateConvoySpacing();
                    mapView.invalidate();
                    break;

                case "LORA_BT_STATUS":
                    boolean btConnected = intent.getBooleanExtra("connected", false);
                    String btStatusText = intent.getStringExtra("status_text");
                    if (btConnected) {
                        updateRoleDisplay();
                    } else {
                        tvQuickStatus.setText(btStatusText != null ? btStatusText : "LoRa: MẤT KẾT NỐI");
                    }
                    break;

                case "LORA_POS_RECEIVED":
                    String posPacket = intent.getStringExtra("raw");
                    if (posPacket != null && posPacket.startsWith("#POS")) {
                        parseTeammatePosition(posPacket);
                    }
                    break;

                case "LORA_CMD_RECEIVED":
                    String cmdPacket = intent.getStringExtra("raw");
                    if (cmdPacket != null && cmdPacket.startsWith("#CMD")) {
                        parseTacticalCommand(cmdPacket);
                    }
                    break;
            }
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
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (alarmUri == null) alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        alertRingtone = RingtoneManager.getRingtone(this, alarmUri);

        Uri notifUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        warningBeep = RingtoneManager.getRingtone(this, notifUri);

        initViews();
        setupMapView();
        setupMenuEvents();
        setupCommandButtons();
        updateRoleDisplay();
        checkPermissionsAndInit();

        // Nhấn giữ thanh trạng thái đỉnh để chuyển đổi vai trò Xe 1 / Xe 2
        tvQuickStatus.setOnLongClickListener(v -> {
            showRoleSelectionDialog();
            return true;
        });

        // Khởi động TrackingService ngay lập tức để duy trì Bluetooth & GPS liên tục
        Intent serviceIntent = new Intent(this, TrackingService.class);
        ContextCompat.startForegroundService(this, serviceIntent);
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

        // Lộ trình GPX mẫu màu hồng dạ quang (#E91E63)
        plannedGpxLine = new Polyline(mapView);
        plannedGpxLine.setColor(Color.parseColor("#E91E63"));
        plannedGpxLine.setWidth(9.0f);
        plannedGpxLine.getOutlinePaint().setStrokeJoin(Paint.Join.ROUND);
        plannedGpxLine.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
        mapView.getOverlays().add(plannedGpxLine);

        // Vệt thực tế màu xanh dương (#003399)
        trackLine = new Polyline(mapView);
        trackLine.setColor(Color.parseColor("#003399"));
        trackLine.setWidth(7.0f);
        trackLine.getOutlinePaint().setStrokeJoin(Paint.Join.ROUND);
        trackLine.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
        mapView.getOverlays().add(trackLine);

        // Marker vị trí xe mình
        currentMarker = new Marker(mapView);
        currentMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        currentMarker.setIcon(createVehicleDot(myVehicleId == 1 ? Color.parseColor("#007AFF") : Color.parseColor("#00C853")));
        currentMarker.setInfoWindow(null);
        currentMarker.setVisible(false);
        mapView.getOverlays().add(currentMarker);

        // Marker xe đồng đội (LoRa Telemetry) màu cam dã chiến
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

    // --- BỘ VẼ CỜ DÃ CHIẾN VÀ ĐIỂM ĐỊNH VỊ CHUẨN TỌA ĐỘ ---
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

    private BitmapDrawable createTacticalFlagDrawable(int flagColor, String label) {
        int w = 64;
        int h = 64;
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // Vẽ cán cờ
        Paint polePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        polePaint.setColor(Color.DKGRAY);
        polePaint.setStrokeWidth(4.0f);
        canvas.drawLine(14f, 6f, 14f, 58f, polePaint);

        // Chân đế cắm cờ
        Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        basePaint.setColor(Color.BLACK);
        canvas.drawCircle(14f, 58f, 3.5f, basePaint);

        // Thân lá cờ đuôi nheo
        Paint flagPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        flagPaint.setColor(flagColor);
        flagPaint.setStyle(Paint.Style.FILL);

        Path path = new Path();
        path.moveTo(14f, 8f);
        path.lineTo(60f, 8f);
        path.lineTo(60f, 34f);
        path.lineTo(14f, 34f);
        path.close();
        canvas.drawPath(path, flagPaint);

        // Viền cờ trắng
        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1.5f);
        borderPaint.setColor(Color.WHITE);
        canvas.drawPath(path, borderPaint);

        // Chữ định danh mốc (XP / ĐÍCH)
        if (label != null && !label.isEmpty()) {
            Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(13f);
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(label, 37f, 26f, textPaint);
        }

        return new BitmapDrawable(getResources(), bitmap);
    }

    private void plantStartFlag(GeoPoint point) {
        if (startFlagMarker == null) {
            startFlagMarker = new Marker(mapView);
            startFlagMarker.setAnchor(0.22f, 0.91f);
            startFlagMarker.setIcon(createTacticalFlagDrawable(Color.parseColor("#00C853"), "XP"));
            startFlagMarker.setInfoWindow(null);
            mapView.getOverlays().add(startFlagMarker);
        }
        startFlagMarker.setPosition(point);
        startFlagMarker.setEnabled(true);
        pendingStartFlag = false;
        mapView.invalidate();
    }

    private void plantFinishFlag(GeoPoint point) {
        if (finishFlagMarker == null) {
            finishFlagMarker = new Marker(mapView);
            finishFlagMarker.setAnchor(0.22f, 0.91f);
            finishFlagMarker.setIcon(createTacticalFlagDrawable(Color.parseColor("#D50000"), "ĐÍCH"));
            finishFlagMarker.setInfoWindow(null);
            mapView.getOverlays().add(finishFlagMarker);
        }
        finishFlagMarker.setPosition(point);
        finishFlagMarker.setEnabled(true);
        mapView.invalidate();
    }

    private void updateRoleDisplay() {
        teammateId = (myVehicleId == 1) ? 2 : 1;
        String roleStr = (myVehicleId == 1) ? "XE 01 (CHỈ HUY)" : "XE 02 (PHÂN ĐỘI)";
        tvQuickStatus.setText(String.format(Locale.US, "Vai trò: %s | Cầu LoRa: THÔNG", roleStr));
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

    // --- GIÁM SÁT DÃN CỰ LY ĐỘI HÌNH 50M ---
    private void evaluateConvoySpacing() {
        if (myCurrentPoint == null || teammatePoint == null) return;

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

    private void parseTeammatePosition(String rawPacket) {
        String[] parts = rawPacket.split(",");
        if (parts.length >= 5) {
            try {
                int senderId = Integer.parseInt(parts[1].trim());
                if (senderId != myVehicleId) {
                    double rLat = Double.parseDouble(parts[2].trim());
                    double rLng = Double.parseDouble(parts[3].trim());
                    float rSpeed = Float.parseFloat(parts[4].trim());

                    teammatePoint = new GeoPoint(rLat, rLng);
                    teammateSpeed = rSpeed;
                    teammateMarker.setPosition(teammatePoint);
                    teammateMarker.setVisible(true);
                    evaluateConvoySpacing();
                    mapView.invalidate();
                }
            } catch (Exception ignored) {}
        }
    }

    private void parseTacticalCommand(String rawPacket) {
        String[] parts = rawPacket.split(",");
        if (parts.length >= 4) {
            String sender = "XE " + parts[1];
            String cmdDesc = parts[3];
            showCommandAlert(sender, cmdDesc);
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
        
        Intent intent = new Intent(this, TrackingService.class);
        intent.putExtra("SEND_LORA_PACKET", packet);
        ContextCompat.startForegroundService(this, intent);

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
                        
                        Intent ackIntent = new Intent(this, TrackingService.class);
                        ackIntent.putExtra("SEND_LORA_PACKET", String.format(Locale.US, "#ACK,%d,RECEIVED\n", myVehicleId));
                        ContextCompat.startForegroundService(this, ackIntent);
                    })
                    .show();
        });
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
            trackLine.getActualPoints().clear();
            
            if (startFlagMarker != null) {
                startFlagMarker.setEnabled(false);
                mapView.getOverlays().remove(startFlagMarker);
                startFlagMarker = null;
            }
            if (finishFlagMarker != null) {
                finishFlagMarker.setEnabled(false);
                mapView.getOverlays().remove(finishFlagMarker);
                finishFlagMarker = null;
            }

            currentMode = MODE_STANDBY;
            updateRoleDisplay();
            mapView.invalidate();
            drawerLayout.closeDrawer(GravityCompat.START);

            Intent intent = new Intent(this, TrackingService.class);
            intent.putExtra("CMD_SET_RECORDING", false);
            ContextCompat.startForegroundService(this, intent);

            Toast.makeText(this, "Đã dọn sạch bản đồ tác chiến", Toast.LENGTH_SHORT).show();
        });
    }

    private void startModeRecord() {
        currentMode = MODE_RECORDING;
        tvQuickStatus.setText("Chế độ: ĐANG GHI");

        dbHelper.clearAllPoints();
        trackLine.getActualPoints().clear();
        trackLine.setVisible(true);

        // Gỡ cờ đích cũ nếu còn lưu từ lần chạy trước
        if (finishFlagMarker != null) {
            finishFlagMarker.setEnabled(false);
            mapView.getOverlays().remove(finishFlagMarker);
            finishFlagMarker = null;
        }

        // CẮM CỜ XUẤT PHÁT NGAY TẠI TỌA ĐỘ BẮT ĐẦU
        if (myCurrentPoint != null) {
            plantStartFlag(myCurrentPoint);
        } else {
            pendingStartFlag = true;
        }

        mapView.invalidate();

        // Ra lệnh cho Service ngầm bắt đầu ghi điểm SQLite (không ngắt LoRa)
        Intent intent = new Intent(this, TrackingService.class);
        intent.putExtra("CMD_SET_RECORDING", true);
        ContextCompat.startForegroundService(this, intent);

        btnStartRecord.setEnabled(false);
        btnStopRecord.setEnabled(true);
        Toast.makeText(this, "Bắt đầu ghi vết & Cắm cờ Xuất phát!", Toast.LENGTH_SHORT).show();
    }

    private void stopModeRecordAndExport() {
        // Dừng ghi dữ liệu vào SQLite nhưng DUY TRÌ 100% CẦU NỐI LORA/BLUETOOTH
        Intent intent = new Intent(this, TrackingService.class);
        intent.putExtra("CMD_SET_RECORDING", false);
        ContextCompat.startForegroundService(this, intent);

        currentMode = MODE_STANDBY;
        updateRoleDisplay();
        btnStartRecord.setEnabled(true);
        btnStopRecord.setEnabled(false);

        // CẮM CỜ ĐÍCH ĐẾN TẠI VỊ TRÍ DỪNG VÀ XUẤT TỆP GPX
        if (myCurrentPoint != null) {
            plantFinishFlag(myCurrentPoint);
        }

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
        GeoPoint gpxStartPoint = null;

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
                    } else if ("wpt".equalsIgnoreCase(name)) {
                        String latStr = parser.getAttributeValue(null, "lat");
                        String lonStr = parser.getAttributeValue(null, "lon");
                        if (latStr != null && lonStr != null && gpxStartPoint == null) {
                            gpxStartPoint = new GeoPoint(Double.parseDouble(latStr), Double.parseDouble(lonStr));
                        }
                    }
                }
                eventType = parser.next();
            }

            if (!points.isEmpty()) {
                trackLine.getActualPoints().clear();
                trackLine.setVisible(false);

                plannedGpxLine.setPoints(points);
                mapView.getController().animateTo(points.get(0));
                mapView.getController().setZoom(16.0);

                // Cắm cờ Xuất phát đầu tuyến dẫn đường
                plantStartFlag(gpxStartPoint != null ? gpxStartPoint : points.get(0));

                mapView.invalidate();

                currentMode = MODE_FOLLOW_ROUTE;
                tvQuickStatus.setText("LỘ TRÌNH: BÁM ĐƯỜNG GPX");
                drawerLayout.closeDrawer(GravityCompat.START);

                Toast.makeText(this, "Đang dẫn đường: " + gpxFile.getName(), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Lỗi nạp GPX: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"J2OfflineTracker\">\n");

            // ĐÓNG GÓI MỐC CỜ VÀO ĐẦU FILE (<wpt>)
            if (startFlagMarker != null && startFlagMarker.isEnabled() && startFlagMarker.getPosition() != null) {
                writer.write(String.format(Locale.US, "  <wpt lat=\"%.6f\" lon=\"%.6f\">\n    <name>XP</name>\n    <type>GREEN</type>\n  </wpt>\n",
                        startFlagMarker.getPosition().getLatitude(), startFlagMarker.getPosition().getLongitude()));
            }
            if (finishFlagMarker != null && finishFlagMarker.isEnabled() && finishFlagMarker.getPosition() != null) {
                writer.write(String.format(Locale.US, "  <wpt lat=\"%.6f\" lon=\"%.6f\">\n    <name>DICH</name>\n    <type>RED</type>\n  </wpt>\n",
                        finishFlagMarker.getPosition().getLatitude(), finishFlagMarker.getPosition().getLongitude()));
            }

            writer.write("  <trk>\n    <name>Track " + timeStamp + "</name>\n    <trkseg>\n");
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
        if (cursor != null) {
            trackLine.getActualPoints().clear();
            GeoPoint firstPoint = null;
            GeoPoint lastPoint = null;
            while (cursor.moveToNext()) {
                double lat = cursor.getDouble(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_LAT));
                double lng = cursor.getDouble(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_LNG));
                lastPoint = new GeoPoint(lat, lng);
                if (firstPoint == null) firstPoint = lastPoint;
                trackLine.addPoint(lastPoint);
            }
            cursor.close();

            if (firstPoint != null) {
                plantStartFlag(firstPoint);
            }
            if (lastPoint != null) {
                currentMarker.setPosition(lastPoint);
                currentMarker.setVisible(true);
            }
            tvStats.setText(String.format(Locale.US, "Đã ghi: %d điểm", dbHelper.getPointCount()));
            mapView.invalidate();
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
            } else {
                Toast.makeText(this, "Cần cấp đủ quyền Vị trí & Bộ nhớ!", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
        
        IntentFilter filter = new IntentFilter();
        filter.addAction("GPS_LOCATION_UPDATE");
        filter.addAction("LORA_BT_STATUS");
        filter.addAction("LORA_POS_RECEIVED");
        filter.addAction("LORA_CMD_RECEIVED");
        registerReceiver(tacticalServiceReceiver, filter);

        if (currentMode == MODE_RECORDING) {
            loadExistingTrackFromDb();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
        try {
            unregisterReceiver(tacticalServiceReceiver);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mapView.onDetach();
        if (alertRingtone != null && alertRingtone.isPlaying()) alertRingtone.stop();
        if (warningBeep != null && warningBeep.isPlaying()) warningBeep.stop();
        if (vibrator != null) vibrator.cancel();
    }
}
