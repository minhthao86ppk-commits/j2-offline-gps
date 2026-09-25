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
import android.graphics.drawable.BitmapDrawable;
import android.location.Location;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.VibrationEffect;
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

    // Các chế độ vận hành
    private static final int MODE_STANDBY = 0;
    private static final int MODE_RECORDING = 1;
    private static final int MODE_FOLLOW_ROUTE = 2;

    private int currentMode = MODE_STANDBY;
    private boolean isFirstGpsFix = true;
    private boolean pendingStartFlag = false;

    // Định danh xe tác chiến
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

    // 7 nút lệnh tác chiến LoRa (#CMD)
    private Button btnCmdStop, btnCmdResume, btnCmdSpeedUp, btnCmdSlowDown;
    private Button btnCmdCloseSpacing, btnCmdOpenSpacing, btnCmdEmergency;

    // Cụm cờ mốc dã chiến
    private Button btnFlagYellow, btnFlagPurple, btnClearFlags;

    // Bản đồ & Lớp phủ
    private Polyline trackLine;
    private Polyline plannedGpxLine;
    private Marker currentMarker;
    private Marker teammateMarker;
    private Marker startFlagMarker;
    private Marker finishFlagMarker;
    private final List<Marker> tacticalFlagsList = new ArrayList<>();

    // Tọa độ & Vận tốc
    private GeoPoint myCurrentPoint = null;
    private GeoPoint teammatePoint = null;
    private float teammateSpeed = 0.0f;

    // CSDL & Chuông / Rung tác chiến (#CMD)
    private DatabaseHelper dbHelper;
    private Vibrator vibrator;
    private Ringtone alertRingtone;

    // Class phụ trợ bóc tách waypoint từ GPX
    private static class ParsedWaypoint {
        GeoPoint point;
        String name;
        String type;

        ParsedWaypoint(GeoPoint point, String name, String type) {
            this.point = point;
            this.name = name;
            this.type = type;
        }
    }

    // --- BỘ THU PHÁT BROADCAST TỪ TRACKING SERVICE ---
    private final BroadcastReceiver tacticalServiceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            switch (action) {
                case "GPS_LOCATION_UPDATE":
                    double lat = intent.getDoubleExtra("lat", 0.0);
                    double lng = intent.getDoubleExtra("lng", 0.0);

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
                        if (pendingStartFlag) {
                            plantStartFlag(myCurrentPoint);
                        }
                    } else if (currentMode == MODE_FOLLOW_ROUTE) {
                        mapView.getController().animateTo(myCurrentPoint);
                    }

                    // Cập nhật thông số cự ly liên tục
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

        initViews();
        setupMapView();
        setupMenuEvents();
        setupCommandButtons();
        setupTacticalFlagButtons();
        updateRoleDisplay();
        checkPermissionsAndInit();

        // CHỐNG BẤM NHẦM: NHẤN GIỮ 2 GIÂY ĐỂ ĐỔI VAI TRÒ XE
        tvQuickStatus.setOnLongClickListener(v -> {
            showRoleSelectionDialog();
            return true;
        });

        // Chạm vào thông số để đổi file bản đồ ngoại tuyến tức thời
        tvStats.setOnClickListener(v -> pickOfflineMap());

        // KÍCH HOẠT SERVICE NGẦM GIỮ KẾT NỐI VĨNH VIỄN
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

        btnFlagYellow = findViewById(R.id.btnFlagYellow);
        btnFlagPurple = findViewById(R.id.btnFlagPurple);
        btnClearFlags = findViewById(R.id.btnClearFlags);
    }

    private void setupMapView() {
        mapView.setMultiTouchControls(true);
        mapView.setUseDataConnection(false);

        // Tuyến GPX mẫu: Hồng dạ quang (#E91E63)
        plannedGpxLine = new Polyline(mapView);
        plannedGpxLine.setColor(Color.parseColor("#E91E63"));
        plannedGpxLine.setWidth(9.0f);
        plannedGpxLine.getOutlinePaint().setStrokeJoin(Paint.Join.ROUND);
        plannedGpxLine.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
        mapView.getOverlays().add(plannedGpxLine);

        // Vệt thực tế: Xanh dương đậm (#003399)
        trackLine = new Polyline(mapView);
        trackLine.setColor(Color.parseColor("#003399"));
        trackLine.setWidth(7.0f);
        trackLine.getOutlinePaint().setStrokeJoin(Paint.Join.ROUND);
        trackLine.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
        mapView.getOverlays().add(trackLine);

        // Con trỏ vị trí xe ta
        currentMarker = new Marker(mapView);
        currentMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        currentMarker.setIcon(createVehicleDot(myVehicleId == 1 ? Color.parseColor("#007AFF") : Color.parseColor("#00C853")));
        currentMarker.setInfoWindow(null);
        currentMarker.setVisible(false);
        mapView.getOverlays().add(currentMarker);

        // Con trỏ xe bạn (LoRa Telemetry) màu cam dã chiến
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

    // CỜ TRƠN HOÀN TOÀN: KHÔNG KÝ HIỆU, KHÔNG CHỮ VIẾT
    private BitmapDrawable createPlainTacticalFlag(int flagColor) {
        int w = 64;
        int h = 64;
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint polePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        polePaint.setColor(Color.DKGRAY);
        polePaint.setStrokeWidth(4.0f);
        canvas.drawLine(14f, 6f, 14f, 58f, polePaint);

        Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        basePaint.setColor(Color.BLACK);
        canvas.drawCircle(14f, 58f, 3.5f, basePaint);

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

        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1.5f);
        borderPaint.setColor(Color.WHITE);
        canvas.drawPath(path, borderPaint);

        return new BitmapDrawable(getResources(), bitmap);
    }

    private void plantStartFlag(GeoPoint point) {
        if (startFlagMarker == null) {
            startFlagMarker = new Marker(mapView);
            startFlagMarker.setAnchor(0.22f, 0.91f);
            startFlagMarker.setIcon(createPlainTacticalFlag(Color.parseColor("#00C853")));
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
            finishFlagMarker.setIcon(createPlainTacticalFlag(Color.parseColor("#D50000")));
            finishFlagMarker.setInfoWindow(null);
            mapView.getOverlays().add(finishFlagMarker);
        }
        finishFlagMarker.setPosition(point);
        finishFlagMarker.setEnabled(true);
        mapView.invalidate();
    }

    // Hàm tạo và gắn cờ mốc tác chiến lên bản đồ
    private void addTacticalFlagMarker(GeoPoint point, int flagColor, String typeTag) {
        Marker flag = new Marker(mapView);
        flag.setAnchor(0.22f, 0.91f);
        flag.setIcon(createPlainTacticalFlag(flagColor));
        flag.setPosition(point);
        flag.setSubDescription(typeTag);
        flag.setInfoWindow(null);

        mapView.getOverlays().add(flag);
        tacticalFlagsList.add(flag);
    }

    private void plantCustomTacticalFlag(int flagColor, String typeTag) {
        if (myCurrentPoint == null) {
            Toast.makeText(this, "Chưa khóa được vị trí GPS!", Toast.LENGTH_SHORT).show();
            return;
        }

        addTacticalFlagMarker(myCurrentPoint, flagColor, typeTag);
        mapView.invalidate();
        Toast.makeText(this, "Đã cắm cờ mốc: " + typeTag, Toast.LENGTH_SHORT).show();
    }

    private void setupTacticalFlagButtons() {
        if (btnFlagYellow != null) {
            btnFlagYellow.setOnClickListener(v -> {
                drawerLayout.closeDrawer(GravityCompat.START);
                plantCustomTacticalFlag(Color.parseColor("#FFD600"), "YELLOW");
            });
        }
        if (btnFlagPurple != null) {
            btnFlagPurple.setOnClickListener(v -> {
                drawerLayout.closeDrawer(GravityCompat.START);
                plantCustomTacticalFlag(Color.parseColor("#AA00FF"), "PURPLE");
            });
        }
        if (btnClearFlags != null) {
            btnClearFlags.setOnClickListener(v -> {
                drawerLayout.closeDrawer(GravityCompat.START);
                for (Marker m : tacticalFlagsList) {
                    mapView.getOverlays().remove(m);
                }
                tacticalFlagsList.clear();
                mapView.invalidate();
                Toast.makeText(this, "Đã xóa toàn bộ cờ mốc dã chiến!", Toast.LENGTH_SHORT).show();
            });
        }
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

    // --- HIỂN THỊ XE BẠN VÀ TÍNH CỰ LY THỰC TẾ ---
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

    private void evaluateConvoySpacing() {
        if (myCurrentPoint == null || teammatePoint == null) {
            if (myCurrentPoint != null && currentMode != MODE_RECORDING) {
                tvStats.setTextColor(Color.DKGRAY);
                tvStats.setText(String.format(Locale.US, "GPS xe mình: TỐT | Đang đợi Xe %02d...", teammateId));
            } else if (currentMode == MODE_RECORDING) {
                tvStats.setTextColor(Color.DKGRAY);
                tvStats.setText(String.format(Locale.US, "Đã ghi: %d điểm | Chờ tín hiệu Xe %02d...",
                        dbHelper.getPointCount(), teammateId));
            }
            return;
        }

        float[] results = new float[1];
        Location.distanceBetween(
                myCurrentPoint.getLatitude(), myCurrentPoint.getLongitude(),
                teammatePoint.getLatitude(), teammatePoint.getLongitude(),
                results
        );
        float distanceMeters = results[0];

        tvStats.setTextColor(Color.parseColor("#00897B"));
        tvStats.setText(String.format(Locale.US, "Cự ly đến Xe %02d: %.1f m\nĐồng đội: %.1f km/h",
                teammateId, distanceMeters, teammateSpeed));
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                } else {
                    vibrator.vibrate(pattern, 0);
                }
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

            for (Marker m : tacticalFlagsList) {
                mapView.getOverlays().remove(m);
            }
            tacticalFlagsList.clear();

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

        // Dọn cờ kết thúc cũ và cờ mốc cũ trước khi ghi mới
        if (finishFlagMarker != null) {
            finishFlagMarker.setEnabled(false);
            mapView.getOverlays().remove(finishFlagMarker);
            finishFlagMarker = null;
        }

        if (startFlagMarker != null) {
            startFlagMarker.setEnabled(false);
            mapView.getOverlays().remove(startFlagMarker);
            startFlagMarker = null;
        }

        for (Marker m : tacticalFlagsList) {
            mapView.getOverlays().remove(m);
        }
        tacticalFlagsList.clear();

        if (myCurrentPoint != null) {
            plantStartFlag(myCurrentPoint);
        } else {
            pendingStartFlag = true;
        }

        mapView.invalidate();

        Intent intent = new Intent(this, TrackingService.class);
        intent.putExtra("CMD_SET_RECORDING", true);
        ContextCompat.startForegroundService(this, intent);

        btnStartRecord.setEnabled(false);
        btnStopRecord.setEnabled(true);
        Toast.makeText(this, "Bắt đầu ghi vết & Cắm cờ Xuất phát!", Toast.LENGTH_SHORT).show();
    }

    // SỬA LỖI 1: Đảm bảo giữ cờ xanh, cắm cờ đỏ khi kết thúc và lưu đủ vào GPX
    private void stopModeRecordAndExport() {
        Intent intent = new Intent(this, TrackingService.class);
        intent.putExtra("CMD_SET_RECORDING", false);
        ContextCompat.startForegroundService(this, intent);

        currentMode = MODE_STANDBY;
        updateRoleDisplay();
        btnStartRecord.setEnabled(true);
        btnStopRecord.setEnabled(false);

        // 1. Phục hồi cờ xanh xuất phát nếu bị mất trong quá trình ứng dụng chạy nền
        if (startFlagMarker == null || startFlagMarker.getPosition() == null) {
            Cursor c = dbHelper.getAllPoints();
            if (c != null && c.moveToFirst()) {
                GeoPoint startPoint = new GeoPoint(
                        c.getDouble(c.getColumnIndexOrThrow(DatabaseHelper.COLUMN_LAT)),
                        c.getDouble(c.getColumnIndexOrThrow(DatabaseHelper.COLUMN_LNG))
                );
                plantStartFlag(startPoint);
                c.close();
            } else if (myCurrentPoint != null) {
                plantStartFlag(myCurrentPoint);
            }
        }

        // 2. Cắm cờ đỏ tại đích đến kết thúc
        GeoPoint finishPoint = myCurrentPoint;
        if (finishPoint == null) {
            Cursor c = dbHelper.getAllPoints();
            if (c != null && c.moveToLast()) {
                finishPoint = new GeoPoint(
                        c.getDouble(c.getColumnIndexOrThrow(DatabaseHelper.COLUMN_LAT)),
                        c.getDouble(c.getColumnIndexOrThrow(DatabaseHelper.COLUMN_LNG))
                );
                c.close();
            }
        }
        if (finishPoint != null) {
            plantFinishFlag(finishPoint);
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

    // SỬA LỖI 2: Đọc toàn bộ các waypoint và khôi phục cờ xanh, cờ đỏ, cờ vàng, cờ tím
    private void loadPlannedGpx(File gpxFile) {
        List<GeoPoint> points = new ArrayList<>();
        List<ParsedWaypoint> gpxWaypoints = new ArrayList<>();

        try (InputStream inputStream = new FileInputStream(gpxFile)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(inputStream, null);
            int eventType = parser.getEventType();

            Double currentWptLat = null;
            Double currentWptLon = null;
            StringBuilder currentWptName = new StringBuilder();
            StringBuilder currentWptType = new StringBuilder();
            boolean insideWpt = false;
            String currentTag = "";

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    String tagName = parser.getName();
                    if ("trkpt".equalsIgnoreCase(tagName) || "rtept".equalsIgnoreCase(tagName)) {
                        String latStr = parser.getAttributeValue(null, "lat");
                        String lonStr = parser.getAttributeValue(null, "lon");
                        if (latStr != null && lonStr != null) {
                            points.add(new GeoPoint(Double.parseDouble(latStr), Double.parseDouble(lonStr)));
                        }
                    } else if ("wpt".equalsIgnoreCase(tagName)) {
                        insideWpt = true;
                        String latStr = parser.getAttributeValue(null, "lat");
                        String lonStr = parser.getAttributeValue(null, "lon");
                        if (latStr != null && lonStr != null) {
                            currentWptLat = Double.parseDouble(latStr);
                            currentWptLon = Double.parseDouble(lonStr);
                        } else {
                            currentWptLat = null;
                            currentWptLon = null;
                        }
                        currentWptName.setLength(0);
                        currentWptType.setLength(0);
                    } else if (insideWpt) {
                        currentTag = tagName.toLowerCase();
                    }
                } else if (eventType == XmlPullParser.TEXT) {
                    if (insideWpt && currentTag != null && !currentTag.isEmpty()) {
                        String text = parser.getText();
                        if (text != null) {
                            if ("name".equals(currentTag)) {
                                currentWptName.append(text.trim());
                            } else if ("type".equals(currentTag) || "sym".equals(currentTag)) {
                                currentWptType.append(text.trim());
                            }
                        }
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    String tagName = parser.getName();
                    if ("wpt".equalsIgnoreCase(tagName)) {
                        if (currentWptLat != null && currentWptLon != null) {
                            gpxWaypoints.add(new ParsedWaypoint(
                                    new GeoPoint(currentWptLat, currentWptLon),
                                    currentWptName.toString(),
                                    currentWptType.toString()
                            ));
                        }
                        insideWpt = false;
                        currentTag = "";
                    } else if (insideWpt) {
                        currentTag = "";
                    }
                }
                eventType = parser.next();
            }

            if (!points.isEmpty() || !gpxWaypoints.isEmpty()) {
                trackLine.getActualPoints().clear();
                trackLine.setVisible(false);

                // Dọn sạch các cờ cũ trước khi tải lộ trình mới
                if (finishFlagMarker != null) {
                    finishFlagMarker.setEnabled(false);
                    mapView.getOverlays().remove(finishFlagMarker);
                    finishFlagMarker = null;
                }
                if (startFlagMarker != null) {
                    startFlagMarker.setEnabled(false);
                    mapView.getOverlays().remove(startFlagMarker);
                    startFlagMarker = null;
                }
                for (Marker m : tacticalFlagsList) {
                    mapView.getOverlays().remove(m);
                }
                tacticalFlagsList.clear();

                GeoPoint parsedStartPoint = null;
                GeoPoint parsedFinishPoint = null;

                // Cắm lại đầy đủ các màu cờ dựa theo dữ liệu waypoint
                for (ParsedWaypoint wpt : gpxWaypoints) {
                    String nameUpper = wpt.name.toUpperCase();
                    String typeUpper = wpt.type.toUpperCase();

                    if (nameUpper.contains("XP") || typeUpper.contains("GREEN") || nameUpper.contains("START")) {
                        parsedStartPoint = wpt.point;
                        plantStartFlag(wpt.point);
                    } else if (nameUpper.contains("DICH") || nameUpper.contains("ĐÍCH") || typeUpper.contains("RED") || nameUpper.contains("FINISH") || nameUpper.contains("END")) {
                        parsedFinishPoint = wpt.point;
                        plantFinishFlag(wpt.point);
                    } else if (typeUpper.contains("YELLOW") || nameUpper.contains("YELLOW") || nameUpper.contains("VÀNG")) {
                        addTacticalFlagMarker(wpt.point, Color.parseColor("#FFD600"), "YELLOW");
                    } else if (typeUpper.contains("PURPLE") || nameUpper.contains("PURPLE") || nameUpper.contains("TÍM")) {
                        addTacticalFlagMarker(wpt.point, Color.parseColor("#AA00FF"), "PURPLE");
                    } else {
                        addTacticalFlagMarker(wpt.point, Color.parseColor("#FFD600"), wpt.name.isEmpty() ? "FLAG" : wpt.name);
                    }
                }

                // Dự phòng nếu file GPX không có thẻ <wpt> riêng: Lấy điểm đầu và điểm cuối của đường track
                if (!points.isEmpty()) {
                    if (parsedStartPoint == null) {
                        plantStartFlag(points.get(0));
                    }
                    if (parsedFinishPoint == null && points.size() > 1) {
                        plantFinishFlag(points.get(points.size() - 1));
                    }

                    plannedGpxLine.setPoints(points);
                    mapView.getController().animateTo(points.get(0));
                    mapView.getController().setZoom(16.0);
                } else if (parsedStartPoint != null) {
                    mapView.getController().animateTo(parsedStartPoint);
                    mapView.getController().setZoom(16.0);
                }

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

    // SỬA LỖI 1: Xuất đầy đủ cả cờ xanh (XP), cờ đỏ (DICH) và cờ mốc tác chiến
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

            // Tọa độ cờ xanh (Xuất phát)
            GeoPoint startPoint = (startFlagMarker != null && startFlagMarker.getPosition() != null)
                    ? startFlagMarker.getPosition() : null;

            // Tọa độ cờ đỏ (Đích đến)
            GeoPoint finishPoint = (finishFlagMarker != null && finishFlagMarker.getPosition() != null)
                    ? finishFlagMarker.getPosition() : null;

            if (cursor.moveToFirst()) {
                if (startPoint == null) {
                    startPoint = new GeoPoint(
                            cursor.getDouble(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_LAT)),
                            cursor.getDouble(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_LNG))
                    );
                }
                if (cursor.moveToLast()) {
                    if (finishPoint == null) {
                        finishPoint = new GeoPoint(
                                cursor.getDouble(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_LAT)),
                                cursor.getDouble(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_LNG))
                        );
                    }
                }
            }

            // Ghi cờ xanh (XP)
            if (startPoint != null) {
                writer.write(String.format(Locale.US, "  <wpt lat=\"%.6f\" lon=\"%.6f\">\n    <name>XP</name>\n    <type>GREEN</type>\n  </wpt>\n",
                        startPoint.getLatitude(), startPoint.getLongitude()));
            }

            // Ghi cờ đỏ (DICH)
            if (finishPoint != null) {
                writer.write(String.format(Locale.US, "  <wpt lat=\"%.6f\" lon=\"%.6f\">\n    <name>DICH</name>\n    <type>RED</type>\n  </wpt>\n",
                        finishPoint.getLatitude(), finishPoint.getLongitude()));
            }

            // Ghi các cờ mốc tác chiến (Vàng, Tím)
            for (Marker m : tacticalFlagsList) {
                if (m != null && m.getPosition() != null) {
                    String tag = m.getSubDescription() != null ? m.getSubDescription() : "FLAG";
                    writer.write(String.format(Locale.US, "  <wpt lat=\"%.6f\" lon=\"%.6f\">\n    <name>%s</name>\n    <type>%s</type>\n  </wpt>\n",
                            m.getPosition().getLatitude(), m.getPosition().getLongitude(), tag, tag));
                }
            }

            // Ghi chuỗi vết trkpt
            writer.write("  <trk>\n    <name>Track " + timeStamp + "</name>\n    <trkseg>\n");
            SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);

            cursor.moveToPosition(-1); // Đưa con trỏ về trước điểm đầu tiên để ghi đủ toàn bộ các điểm
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

    private void pickOfflineMap() {
        File osmdroidDir = new File(Environment.getExternalStorageDirectory(), "osmdroid");
        if (!osmdroidDir.exists() || !osmdroidDir.isDirectory()) {
            Toast.makeText(this, "Thư mục osmdroid/ không tồn tại!", Toast.LENGTH_SHORT).show();
            return;
        }

        File[] mapFiles = osmdroidDir.listFiles((dir, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".mbtiles") || lower.endsWith(".sqlite") || lower.endsWith(".zip");
        });

        if (mapFiles == null || mapFiles.length == 0) {
            Toast.makeText(this, "Không có file bản đồ trong thư mục osmdroid/!", Toast.LENGTH_LONG).show();
            return;
        }

        String[] mapNames = new String[mapFiles.length];
        for (int i = 0; i < mapFiles.length; i++) {
            mapNames[i] = mapFiles[i].getName();
        }

        new AlertDialog.Builder(this)
                .setTitle("Chọn bản đồ dã chiến")
                .setItems(mapNames, (dialog, which) -> loadSelectedMap(mapFiles[which]))
                .setNegativeButton("Đóng", null)
                .show();
    }

    private void loadSelectedMap(File mapFile) {
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
            Toast.makeText(this, "Đã tải bản đồ: " + mapFile.getName(), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Lỗi nạp bản đồ: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void loadOfflineMap() {
        File mapFile = new File(Environment.getExternalStorageDirectory(), "osmdroid/BanDoJ2.mbtiles");
        if (mapFile.exists()) {
            loadSelectedMap(mapFile);
        } else {
            pickOfflineMap();
        }
    }

    private void checkPermissionsAndInit() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        List<String> missingPermissions = new ArrayList<>();
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(perm);
            }
        }

        if (missingPermissions.isEmpty()) {
            loadOfflineMap();
        } else {
            ActivityCompat.requestPermissions(this, missingPermissions.toArray(new String[0]), PERMISSION_REQUEST_CODE);
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
                Toast.makeText(this, "Cần cấp đủ quyền Vị trí, LoRa & Bộ nhớ!", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ĐỒNG BỘ LẠI ĐƯỜNG VẼ VÀ CỜ XUẤT PHÁT KHI MỞ LẠI ỨNG DỤNG
    private void reloadTrackFromDatabase() {
        Cursor cursor = dbHelper.getAllPoints();
        if (cursor == null) return;
        try {
            trackLine.getActualPoints().clear();
            GeoPoint firstPoint = null;
            while (cursor.moveToNext()) {
                double lat = cursor.getDouble(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_LAT));
                double lng = cursor.getDouble(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_LNG));
                GeoPoint pt = new GeoPoint(lat, lng);
                if (firstPoint == null) {
                    firstPoint = pt;
                }
                trackLine.addPoint(pt);
            }
            // Khôi phục lại cờ xanh nếu bị mất khi ứng dụng vào chạy nền
            if (startFlagMarker == null && firstPoint != null) {
                plantStartFlag(firstPoint);
            }
            mapView.invalidate();
        } catch (Exception ignored) {
        } finally {
            cursor.close();
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(tacticalServiceReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(tacticalServiceReceiver, filter);
        }

        if (currentMode == MODE_RECORDING) {
            reloadTrackFromDatabase();
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
        if (vibrator != null) vibrator.cancel();
    }
}
