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
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.location.Location;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
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
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private SharedPreferences sharedPreferences;

    // Quản lý trạng thái mệnh lệnh & xác nhận
    private String lastCommandSentDesc = "";

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
    private Marker startFlagMarker;
    private Marker finishFlagMarker;
    private final List<Marker> tacticalFlagsList = new ArrayList<>();

    // --- QUẢN LÝ ĐỘI HÌNH ĐA XE (MULTI-VEHICLE STATE) ---
    public static class TeammateState {
        public int id;
        public GeoPoint point;
        public float speedKmh;
        public long lastSeenTime;
        public Marker marker;
        public boolean hasAcked;

        public TeammateState(int id) {
            this.id = id;
            this.hasAcked = false;
        }
    }

    private final Map<Integer, TeammateState> teammatesMap = new ConcurrentHashMap<>();
    private GeoPoint myCurrentPoint = null;

    // Luồng nền I/O xử lý file và CSDL để chống đơ giao diện (ANR)
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // CSDL & Chuông / Rung tác chiến (#CMD)
    private DatabaseHelper dbHelper;
    private Vibrator vibrator;
    private Ringtone alertRingtone;

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

                case "LORA_ACK_RECEIVED":
                    String ackPacket = intent.getStringExtra("raw");
                    if (ackPacket != null && ackPacket.startsWith("#ACK")) {
                        parseTacticalAck(ackPacket);
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

        // KÍCH HOẠT SERVICE NGẦM GIỮ KẾT NỐI VĨNH VIỄN (Chỉ gọi startForegroundService 1 lần tại đây)
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
        currentMarker.setIcon(createVehicleDot(myVehicleId == 1 ? Color.parseColor("#007AFF") : Color.parseColor("#00C853"), String.format(Locale.US, "%02d", myVehicleId)));
        currentMarker.setInfoWindow(null);
        currentMarker.setVisible(false);
        mapView.getOverlays().add(currentMarker);

        GeoPoint centerPoint = new GeoPoint(21.135, 105.505);
        mapView.getController().setZoom(14.0);
        mapView.getController().setCenter(centerPoint);
    }

    // BẢNG MÀU TÁC CHIẾN ĐA XE
    private int getVehicleColor(int vehicleId) {
        switch (vehicleId) {
            case 1: return Color.parseColor("#007AFF"); // Xe 01: Xanh chỉ huy
            case 2: return Color.parseColor("#FF9100"); // Xe 02: Cam dã chiến
            case 3: return Color.parseColor("#AA00FF"); // Xe 03: Tím trinh sát
            case 4: return Color.parseColor("#00BCD4"); // Xe 04: Xanh cyan
            case 5: return Color.parseColor("#FFC107"); // Xe 05: Hổ phách
            default: return Color.parseColor("#E91E63"); // Xe khác: Đỏ hồng
        }
    }

    // VẼ CON TRỎ XE KÈM SỐ HIỆU XE TRÊN TÂM MARKER
    private BitmapDrawable createVehicleDot(int coreColor, String label) {
        int size = 72;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        float cx = size / 2f;
        float cy = size / 2f;

        // Vòng hào quang ngoài
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(70, Color.red(coreColor), Color.green(coreColor), Color.blue(coreColor)));
        canvas.drawCircle(cx, cy, 34f, paint);

        // Vành trắng nhận diện
        paint.setColor(Color.WHITE);
        canvas.drawCircle(cx, cy, 22f, paint);

        // Lõi màu định danh
        paint.setColor(coreColor);
        canvas.drawCircle(cx, cy, 18f, paint);

        // Chữ số hiệu xe in giữa tâm
        if (label != null && !label.isEmpty()) {
            Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(14f);
            textPaint.setFakeBoldText(true);
            textPaint.setTextAlign(Paint.Align.CENTER);

            Rect textBounds = new Rect();
            textPaint.getTextBounds(label, 0, label.length(), textBounds);
            canvas.drawText(label, cx, cy - textBounds.exactCenterY(), textPaint);
        }

        return new BitmapDrawable(getResources(), bitmap);
    }

    // CỜ TRƠN DÃ CHIẾN
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

    private void plantCustomTacticalFlag(int flagColor, String typeTag) {
        if (myCurrentPoint == null) {
            Toast.makeText(this, "Chưa khóa được vị trí GPS!", Toast.LENGTH_SHORT).show();
            return;
        }

        Marker flag = new Marker(mapView);
        flag.setAnchor(0.22f, 0.91f);
        flag.setIcon(createPlainTacticalFlag(flagColor));
        flag.setPosition(myCurrentPoint);
        flag.setSubDescription(typeTag);
        flag.setInfoWindow(null);

        mapView.getOverlays().add(flag);
        tacticalFlagsList.add(flag);
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
        String roleStr = (myVehicleId == 1) ? "XE 01 (CHỈ HUY)" : String.format(Locale.US, "XE %02d (PHÂN ĐỘI)", myVehicleId);
        tvQuickStatus.setText(String.format(Locale.US, "Vai trò: %s | Cầu LoRa: THÔNG", roleStr));
        if (currentMarker != null) {
            currentMarker.setIcon(createVehicleDot(getVehicleColor(myVehicleId), String.format(Locale.US, "%02d", myVehicleId)));
        }
    }

    private void showRoleSelectionDialog() {
        String[] roles = {
                "Xe 01 - Xe Chỉ Huy",
                "Xe 02 - Xe Phân Đội",
                "Xe 03 - Xe Phân Đội",
                "Xe 04 - Xe Phân Đội",
                "Xe 05 - Xe Phân Đội"
        };
        int selectedIndex = Math.min(Math.max(0, myVehicleId - 1), roles.length - 1);

        new AlertDialog.Builder(this)
                .setTitle("Cấu hình vai trò thiết bị")
                .setSingleChoiceItems(roles, selectedIndex, (dialog, which) -> {
                    myVehicleId = which + 1;
                    sharedPreferences.edit().putInt("CFG_VEHICLE_ID", myVehicleId).apply();
                    updateRoleDisplay();
                    dialog.dismiss();
                    Toast.makeText(this, "Đã thiết lập: " + roles[which], Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    // --- TIẾP NHẬN TỌA ĐỘ VÀ QUẢN LÝ XE BẠN LINH HOẠT ---
    private void parseTeammatePosition(String rawPacket) {
        String[] parts = rawPacket.split(",");
        if (parts.length >= 5) {
            try {
                int senderId = Integer.parseInt(parts[1].trim());
                if (senderId != myVehicleId) {
                    double rLat = Double.parseDouble(parts[2].trim());
                    double rLng = Double.parseDouble(parts[3].trim());
                    float rSpeed = Float.parseFloat(parts[4].trim());

                    TeammateState state = teammatesMap.get(senderId);
                    if (state == null) {
                        state = new TeammateState(senderId);
                        state.marker = new Marker(mapView);
                        state.marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
                        state.marker.setIcon(createVehicleDot(getVehicleColor(senderId), String.format(Locale.US, "%02d", senderId)));
                        state.marker.setInfoWindow(null);
                        mapView.getOverlays().add(state.marker);
                        teammatesMap.put(senderId, state);
                    }

                    state.point = new GeoPoint(rLat, rLng);
                    state.speedKmh = rSpeed * 3.6f; // Chuyển từ m/s sang km/h chuẩn xác
                    state.lastSeenTime = System.currentTimeMillis();

                    state.marker.setPosition(state.point);
                    state.marker.setVisible(true);

                    evaluateConvoySpacing();
                    mapView.invalidate();
                }
            } catch (Exception ignored) {}
        }
    }

    // --- TÍNH TOÁN CỰ LY ĐỘI HÌNH ĐA XE DÃ CHIẾN ---
    private void evaluateConvoySpacing() {
        if (myCurrentPoint == null) {
            tvStats.setTextColor(Color.DKGRAY);
            tvStats.setText("Đang chờ khóa vị trí GPS vệ tinh...");
            return;
        }

        if (teammatesMap.isEmpty()) {
            tvStats.setTextColor(Color.DKGRAY);
            if (currentMode == MODE_RECORDING) {
                tvStats.setText(String.format(Locale.US, "Đã ghi: %d điểm | Đang đợi xe đồng đội...", dbHelper.getPointCount()));
            } else {
                tvStats.setText("GPS: TỐT | Chưa phát hiện xe đồng đội trên mạng LoRa");
            }
            return;
        }

        long now = System.currentTimeMillis();
        float minDistance = Float.MAX_VALUE;
        int nearestVehicleId = -1;
        float nearestSpeed = 0.0f;
        int activeVehiclesCount = 0;

        for (TeammateState state : teammatesMap.values()) {
            if (state.point == null) continue;

            // Nếu quá 20 giây không có tín hiệu -> Đánh dấu mất liên lạc tạm thời
            if (now - state.lastSeenTime > 20000) {
                state.marker.setAlpha(0.4f);
                continue;
            } else {
                state.marker.setAlpha(1.0f);
                activeVehiclesCount++;
            }

            float[] res = new float[1];
            Location.distanceBetween(
                    myCurrentPoint.getLatitude(), myCurrentPoint.getLongitude(),
                    state.point.getLatitude(), state.point.getLongitude(),
                    res
            );

            if (res[0] < minDistance) {
                minDistance = res[0];
                nearestVehicleId = state.id;
                nearestSpeed = state.speedKmh;
            }
        }

        tvStats.setTextColor(Color.parseColor("#00897B"));
        if (nearestVehicleId != -1) {
            tvStats.setText(String.format(Locale.US, "Đội hình hoạt động: %d xe | Gần nhất: Xe %02d (%.1f m)\nVận tốc Xe %02d: %.1f km/h",
                    activeVehiclesCount, nearestVehicleId, minDistance, nearestVehicleId, nearestSpeed));
        } else {
            tvStats.setText(String.format(Locale.US, "Mất liên lạc toàn bộ %d xe đồng đội!", teammatesMap.size()));
        }
    }

    // --- XỬ LÝ PHẢN HỒI XÁC NHẬN LỆNH (#ACK) ĐA XE ---
    private void parseTacticalAck(String rawPacket) {
        String[] parts = rawPacket.split(",");
        if (parts.length >= 2) {
            try {
                int senderId = Integer.parseInt(parts[1].trim());
                if (senderId != myVehicleId) {
                    TeammateState state = teammatesMap.get(senderId);
                    if (state != null) {
                        state.hasAcked = true;
                    }

                    // Đếm tiến độ xác nhận lệnh của toàn phân đội
                    int ackCount = 0;
                    for (TeammateState t : teammatesMap.values()) {
                        if (t.hasAcked) ackCount++;
                    }

                    String ackNotify = String.format(Locale.US, "✅ XE %02d ĐÃ NHẬN LỆNH! (%d/%d xe)",
                            senderId, ackCount, teammatesMap.size());
                    tvQuickStatus.setText(ackNotify);
                    Toast.makeText(this, ackNotify, Toast.LENGTH_SHORT).show();

                    // Rung nhẹ 1 nhịp 150ms báo cho Chỉ huy/Lái xe
                    if (vibrator != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE));
                        } else {
                            vibrator.vibrate(150);
                        }
                    }
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
        lastCommandSentDesc = cmdDescription;

        // Reset trạng thái ACK của các xe
        for (TeammateState t : teammatesMap.values()) {
            t.hasAcked = false;
        }

        String packet = String.format(Locale.US, "#CMD,%d,%s,%s\n", myVehicleId, cmdCode, cmdDescription);

        // GỬI LỆNH QUA START SERVICE THÔNG THƯỜNG (TRÁNH LỖI CRASH KHI GỌI STARTFOREGROUNDSERVICE LẶP LẠI)
        Intent intent = new Intent(this, TrackingService.class);
        intent.putExtra("SEND_LORA_PACKET", packet);
        startService(intent);

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
            if (isFinishing() || isDestroyed()) return;

            new AlertDialog.Builder(this)
                    .setTitle("🚨 MỆNH LỆNH TỪ " + senderName)
                    .setMessage("\n" + commandText + "\n\n(Tài xế/Trưởng xe lập tức chấp hành!)")
                    .setCancelable(false)
                    .setPositiveButton("ĐÃ NHẬN LỆNH", (dialog, which) -> {
                        if (alertRingtone != null && alertRingtone.isPlaying()) alertRingtone.stop();
                        if (vibrator != null) vibrator.cancel();
                        tvQuickStatus.setText("LỆNH: " + commandText);

                        // Áp dụng độ trễ Backoff theo ID để triệt tiêu hiện tượng các xe phát đè gói tin #ACK
                        long backoffDelay = (myVehicleId * 180L);
                        mainHandler.postDelayed(() -> {
                            Intent ackIntent = new Intent(this, TrackingService.class);
                            ackIntent.putExtra("SEND_LORA_PACKET", String.format(Locale.US, "#ACK,%d,RECEIVED\n", myVehicleId));
                            startService(ackIntent);
                        }, backoffDelay);
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
            startService(intent);

            Toast.makeText(this, "Đã dọn sạch bản đồ tác chiến", Toast.LENGTH_SHORT).show();
        });
    }

    private void startModeRecord() {
        currentMode = MODE_RECORDING;
        tvQuickStatus.setText("Chế độ: ĐANG GHI");

        dbHelper.clearAllPoints();
        trackLine.getActualPoints().clear();
        trackLine.setVisible(true);

        if (finishFlagMarker != null) {
            finishFlagMarker.setEnabled(false);
            mapView.getOverlays().remove(finishFlagMarker);
            finishFlagMarker = null;
        }

        if (myCurrentPoint != null) {
            plantStartFlag(myCurrentPoint);
        } else {
            pendingStartFlag = true;
        }

        mapView.invalidate();

        Intent intent = new Intent(this, TrackingService.class);
        intent.putExtra("CMD_SET_RECORDING", true);
        startService(intent);

        btnStartRecord.setEnabled(false);
        btnStopRecord.setEnabled(true);
        Toast.makeText(this, "Bắt đầu ghi vết & Cắm cờ Xuất phát!", Toast.LENGTH_SHORT).show();
    }

    private void stopModeRecordAndExport() {
        Intent intent = new Intent(this, TrackingService.class);
        intent.putExtra("CMD_SET_RECORDING", false);
        startService(intent);

        currentMode = MODE_STANDBY;
        updateRoleDisplay();
        btnStartRecord.setEnabled(true);
        btnStopRecord.setEnabled(false);

        if (myCurrentPoint != null) {
            plantFinishFlag(myCurrentPoint);
        }

        exportGpxFile();
    }

    // TÌM TẬP TIN GPX TRONG CẢ THƯ MỤC CÔNG CỘNG LẪN THƯ MỤC NỘI BỘ
    private void pickGpxForNavigation() {
        List<File> gpxFilesList = new ArrayList<>();

        File publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (publicDir != null && publicDir.exists()) {
            File[] files = publicDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".gpx"));
            if (files != null) {
                for (File f : files) gpxFilesList.add(f);
            }
        }

        File appSpecificDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (appSpecificDir != null && appSpecificDir.exists()) {
            File[] files = appSpecificDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".gpx"));
            if (files != null) {
                for (File f : files) gpxFilesList.add(f);
            }
        }

        if (gpxFilesList.isEmpty()) {
            Toast.makeText(this, "Không có file .gpx trong Download!", Toast.LENGTH_LONG).show();
            return;
        }

        String[] fileNames = new String[gpxFilesList.size()];
        for (int i = 0; i < gpxFilesList.size(); i++) {
            fileNames[i] = gpxFilesList.get(i).getName();
        }

        new AlertDialog.Builder(this)
                .setTitle("Chọn lộ trình dẫn đường mẫu")
                .setItems(fileNames, (dialog, which) -> loadPlannedGpx(gpxFilesList.get(which)))
                .setNegativeButton("Hủy", null)
                .show();
    }

    // --- NẠP LỘ TRÌNH GPX TRÊN LUỒNG NỀN (CHỐNG TREO MÁY ANR) ---
    private void loadPlannedGpx(File gpxFile) {
        Toast.makeText(this, "Đang xử lý dữ liệu lộ trình...", Toast.LENGTH_SHORT).show();

        ioExecutor.execute(() -> {
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

                final GeoPoint finalStartPoint = (gpxStartPoint != null) ? gpxStartPoint : (!points.isEmpty() ? points.get(0) : null);

                mainHandler.post(() -> {
                    if (!points.isEmpty()) {
                        trackLine.getActualPoints().clear();
                        trackLine.setVisible(false);

                        if (finishFlagMarker != null) {
                            finishFlagMarker.setEnabled(false);
                            mapView.getOverlays().remove(finishFlagMarker);
                            finishFlagMarker = null;
                        }

                        plannedGpxLine.setPoints(points);
                        mapView.getController().animateTo(points.get(0));
                        mapView.getController().setZoom(16.0);

                        if (finalStartPoint != null) {
                            plantStartFlag(finalStartPoint);
                        }
                        mapView.invalidate();

                        currentMode = MODE_FOLLOW_ROUTE;
                        tvQuickStatus.setText("LỘ TRÌNH: BÁM ĐƯỜNG GPX");
                        drawerLayout.closeDrawer(GravityCompat.START);

                        Toast.makeText(this, "Đang dẫn đường: " + gpxFile.getName(), Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "File GPX không có tọa độ hợp lệ!", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(this, "Lỗi nạp GPX: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    // --- XUẤT TẬP TIN GPX TRÊN LUỒNG NỀN AN TOÀN VỚI SCOPED STORAGE ---
    private void exportGpxFile() {
        Toast.makeText(this, "Đang trích xuất dữ liệu hành trình...", Toast.LENGTH_SHORT).show();

        ioExecutor.execute(() -> {
            Cursor cursor = dbHelper.getAllPoints();
            if (cursor == null || cursor.getCount() == 0) {
                if (cursor != null) cursor.close();
                mainHandler.post(() -> Toast.makeText(this, "Không có tọa độ để xuất!", Toast.LENGTH_SHORT).show());
                return;
            }

            File exportDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (exportDir == null || !exportDir.exists() || !exportDir.canWrite()) {
                // Dự phòng bộ nhớ nội bộ ứng dụng nếu quyền thẻ nhớ bị hạn chế
                exportDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            }
            if (exportDir != null && !exportDir.exists()) {
                exportDir.mkdirs();
            }

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File gpxFile = new File(exportDir, "Track_" + timeStamp + ".gpx");

            try (FileWriter writer = new FileWriter(gpxFile)) {
                writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"J2OfflineTracker\">\n");

                if (startFlagMarker != null && startFlagMarker.isEnabled() && startFlagMarker.getPosition() != null) {
                    writer.write(String.format(Locale.US, "  <wpt lat=\"%.6f\" lon=\"%.6f\">\n    <name>XP</name>\n    <type>GREEN</type>\n  </wpt>\n",
                            startFlagMarker.getPosition().getLatitude(), startFlagMarker.getPosition().getLongitude()));
                }
                if (finishFlagMarker != null && finishFlagMarker.isEnabled() && finishFlagMarker.getPosition() != null) {
                    writer.write(String.format(Locale.US, "  <wpt lat=\"%.6f\" lon=\"%.6f\">\n    <name>DICH</name>\n    <type>RED</type>\n  </wpt>\n",
                            finishFlagMarker.getPosition().getLatitude(), finishFlagMarker.getPosition().getLongitude()));
                }

                for (Marker m : tacticalFlagsList) {
                    if (m != null && m.getPosition() != null) {
                        String tag = m.getSubDescription() != null ? m.getSubDescription() : "FLAG";
                        writer.write(String.format(Locale.US, "  <wpt lat=\"%.6f\" lon=\"%.6f\">\n    <name>%s</name>\n    <type>%s</type>\n  </wpt>\n",
                                m.getPosition().getLatitude(), m.getPosition().getLongitude(), tag, tag));
                    }
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

                final String savedPath = gpxFile.getAbsolutePath();
                mainHandler.post(() -> Toast.makeText(this, "Đã lưu vết tác chiến:\n" + savedPath, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(this, "Lỗi xuất GPX: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            } finally {
                cursor.close();
            }
        });
    }

    private void pickOfflineMap() {
        File osmdroidDir = new File(Environment.getExternalStorageDirectory(), "osmdroid");
        if (!osmdroidDir.exists() || !osmdroidDir.isDirectory()) {
            osmdroidDir = new File(getExternalFilesDir(null), "osmdroid");
        }

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

    // ĐỒNG BỘ LẠI ĐƯỜNG VẼ TỪ SQLITE
    private void reloadTrackFromDatabase() {
        ioExecutor.execute(() -> {
            Cursor cursor = dbHelper.getAllPoints();
            if (cursor == null) return;
            List<GeoPoint> points = new ArrayList<>();
            try {
                while (cursor.moveToNext()) {
                    double lat = cursor.getDouble(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_LAT));
                    double lng = cursor.getDouble(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_LNG));
                    points.add(new GeoPoint(lat, lng));
                }
                mainHandler.post(() -> {
                    trackLine.getActualPoints().clear();
                    trackLine.getActualPoints().addAll(points);
                    mapView.invalidate();
                });
            } catch (Exception ignored) {
            } finally {
                cursor.close();
            }
        });
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
        filter.addAction("LORA_ACK_RECEIVED"); // LẮNG NGHE GÓI XÁC NHẬN LỆNH #ACK

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
        ioExecutor.shutdown();
        if (alertRingtone != null && alertRingtone.isPlaying()) alertRingtone.stop();
        if (vibrator != null) vibrator.cancel();
    }
}
