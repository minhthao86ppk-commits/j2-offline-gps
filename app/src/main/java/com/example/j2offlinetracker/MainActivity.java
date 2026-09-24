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

public class MainActivity extends AppCompatActivity implements LocationListener {

    private static final int PERMISSION_REQUEST_CODE = 200;
    private static final float CONVOY_SPACING_LIMIT_METERS = 50.0f;

    private static final int MODE_STANDBY = 0;
    private static final int MODE_RECORDING = 1;
    private static final int MODE_FOLLOW_ROUTE = 2;

    private int currentMode = MODE_STANDBY;
    private boolean isFirstGpsFix = true;

    private int myVehicleId = 1;
    private int teammateId = 2;
    private SharedPreferences sharedPreferences;

    private DrawerLayout drawerLayout;
    private MapView mapView;
    private TextView tvQuickStatus, tvStats;
    private Button btnOpenMenu, btnCloseMenu;
    private Button btnStartRecord, btnStopRecord;
    private Button btnLoadFollowGpx, btnClearRoute;

    private Button btnCmdStop, btnCmdResume, btnCmdSpeedUp, btnCmdSlowDown;
    private Button btnCmdCloseSpacing, btnCmdOpenSpacing, btnCmdEmergency;

    private Polyline trackLine;
    private Polyline plannedGpxLine;
    private Marker currentMarker;
    private Marker teammateMarker;

    private GeoPoint myCurrentPoint = null;
    private GeoPoint teammatePoint = null;
    private float teammateSpeed = 0.0f;
    private long lastSpacingAlertTime = 0;

    private DatabaseHelper dbHelper;
    private LocationManager locationManager;
    private Vibrator vibrator;
    private Ringtone alertRingtone;
    private Ringtone warningBeep;

    // Bộ thu nhận sự kiện từ TrackingService
    private final BroadcastReceiver serviceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            if ("GPS_LOCATION_UPDATE".equals(action)) {
                double lat = intent.getDoubleExtra("lat", 0.0);
                double lng = intent.getDoubleExtra("lng", 0.0);
                myCurrentPoint = new GeoPoint(lat, lng);
                currentMarker.setPosition(myCurrentPoint);
                currentMarker.setVisible(true);

                if (currentMode == MODE_RECORDING) {
                    trackLine.addPoint(myCurrentPoint);
                    tvStats.setText(String.format(Locale.US, "Đã ghi: %d điểm\nTọa độ: %.5f, %.5f", dbHelper.getPointCount(), lat, lng));
                } else if (currentMode == MODE_FOLLOW_ROUTE) {
                    mapView.getController().animateTo(myCurrentPoint);
                }
                evaluateConvoySpacing();
                mapView.invalidate();
            } 
            else if ("LORA_BT_STATUS".equals(action)) {
                boolean connected = intent.getBooleanExtra("connected", false);
                if (connected) {
                    tvQuickStatus.setText("LoRa: ĐÃ THÔNG CẦU NỐI");
                }
            } 
            else if ("LORA_POS_RECEIVED".equals(action)) {
                String raw = intent.getStringExtra("raw");
                if (raw != null && raw.startsWith("#POS")) {
                    String[] parts = raw.split(",");
                    if (parts.length >= 5) {
                        try {
                            int senderId = Integer.parseInt(parts[1].trim());
                            if (senderId != myVehicleId) {
                                double rLat = Double.parseDouble(parts[2].trim());
                                double rLng = Double.parseDouble(parts[3].trim());
                                teammateSpeed = Float.parseFloat(parts[4].trim());
                                teammatePoint = new GeoPoint(rLat, rLng);

                                teammateMarker.setPosition(teammatePoint);
                                teammateMarker.setVisible(true);
                                evaluateConvoySpacing();
                                mapView.invalidate();
                            }
                        } catch (Exception ignored) {}
                    }
                }
            } 
            else if ("LORA_CMD_RECEIVED".equals(action)) {
                String raw = intent.getStringExtra("raw");
                if (raw != null && raw.startsWith("#CMD")) {
                    String[] parts = raw.split(",");
                    if (parts.length >= 4) {
                        showCommandAlert("XE " + parts[1], parts[3]);
                    }
                }
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
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
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

        tvQuickStatus.setOnLongClickListener(v -> {
            showRoleSelectionDialog();
            return true;
        });

        // Khởi động TrackingService ngay khi mở app để tự kết nối Bluetooth
        Intent sIntent = new Intent(this, TrackingService.class);
        ContextCompat.startForegroundService(this, sIntent);
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

        plannedGpxLine = new Polyline(mapView);
        plannedGpxLine.setColor(Color.parseColor("#E91E63"));
        plannedGpxLine.setWidth(9.0f);
        plannedGpxLine.getOutlinePaint().setStrokeJoin(Paint.Join.ROUND);
        plannedGpxLine.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
        mapView.getOverlays().add(plannedGpxLine);

        trackLine = new Polyline(mapView);
        trackLine.setColor(Color.parseColor("#003399"));
        trackLine.setWidth(7.0f);
        trackLine.getOutlinePaint().setStrokeJoin(Paint.Join.ROUND);
        trackLine.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
        mapView.getOverlays().add(trackLine);

        currentMarker = new Marker(mapView);
        currentMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        currentMarker.setIcon(createVehicleDot(myVehicleId == 1 ? Color.parseColor("#007AFF") : Color.parseColor("#00C853")));
        currentMarker.setInfoWindow(null);
        currentMarker.setVisible(false);
        mapView.getOverlays().add(currentMarker);

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
                if (vibrator != null) vibrator.vibrate(new long[]{0, 300, 150, 300}, -1);
                if (warningBeep != null && !warningBeep.isPlaying()) warningBeep.play();
            } catch (Exception ignored) {}
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

        // Bắn gói tin sang TrackingService để phát đi
        Intent intent = new Intent(this, TrackingService.class);
        intent.putExtra("SEND_LORA_PACKET", packet);
        startService(intent);

        tvQuickStatus.setText("ĐÃ PHÁT: " + cmdDescription);
        Toast.makeText(this, "Đã phát lệnh LoRa: " + cmdDescription, Toast.LENGTH_SHORT).show();
    }

    public void showCommandAlert(String senderName, String commandText) {
        try {
            if (alertRingtone != null && !alertRingtone.isPlaying()) alertRingtone.play();
            if (vibrator != null) vibrator.vibrate(new long[]{0, 600, 300, 600, 300}, -1);
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
                        
                        Intent intent = new Intent(this, TrackingService.class);
                        intent.putExtra("SEND_LORA_PACKET", String.format(Locale.US, "#ACK,%d,RECEIVED\n", myVehicleId));
                        startService(intent);
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

        btnLoadFollowGpx.setOnClickListener(v -> pickGpxForNavigation());

        btnClearRoute.setOnClickListener(v -> {
            plannedGpxLine.getActualPoints().clear();
            trackLine.getActualPoints().clear();
            currentMode = MODE_STANDBY;
            updateRoleDisplay();
            mapView.invalidate();
            drawerLayout.closeDrawer(GravityCompat.START);
            Toast.makeText(this, "Đã xóa lộ trình hành quân", Toast.LENGTH_SHORT).show();
        });
    }

    private void startModeRecord() {
        currentMode = MODE_RECORDING;
        tvQuickStatus.setText("Chế độ: ĐANG GHI");

        dbHelper.clearAllPoints();
        trackLine.getActualPoints().clear();
        trackLine.setVisible(true);
        mapView.invalidate();

        btnStartRecord.setEnabled(false);
        btnStopRecord.setEnabled(true);
        Toast.makeText(this, "Bắt đầu ghi vệt hành trình!", Toast.LENGTH_SHORT).show();
    }

    private void stopModeRecordAndExport() {
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

                plannedGpxLine.setPoints(points);
                mapView.getController().animateTo(points.get(0));
                mapView.getController().setZoom(16.0);
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

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();

        IntentFilter filter = new IntentFilter();
        filter.addAction("GPS_LOCATION_UPDATE");
        filter.addAction("LORA_BT_STATUS");
        filter.addAction("LORA_POS_RECEIVED");
        filter.addAction("LORA_CMD_RECEIVED");
        registerReceiver(serviceReceiver, filter);

        if (currentMode == MODE_RECORDING) {
            loadExistingTrackFromDb();
        }
        startImmediateLocationListening();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
        try {
            unregisterReceiver(serviceReceiver);
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
        if (alertRingtone != null && alertRingtone.isPlaying()) alertRingtone.stop();
        if (warningBeep != null && warningBeep.isPlaying()) warningBeep.stop();
        if (vibrator != null) vibrator.cancel();
    }
}
