package com.example.j2offlinetracker;

import android.Manifest;
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
import android.graphics.Path;
import android.graphics.drawable.BitmapDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Environment;
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

    private static final int MODE_STANDBY = 0;
    private static final int MODE_RECORDING = 1;     // Chế độ 1: Ghi lộ trình
    private static final int MODE_FOLLOW_ROUTE = 2;  // Chế độ 2: Dẫn đường GPX

    private int currentMode = MODE_STANDBY;
    private boolean isFirstGpsFix = true; // Cờ theo dõi lần đầu bắt được GPS để dời tâm

    private DrawerLayout drawerLayout;
    private MapView mapView;
    private TextView tvQuickStatus, tvStats;
    private Button btnOpenMenu, btnCloseMenu;
    private Button btnStartRecord, btnStopRecord;
    private Button btnLoadFollowGpx, btnClearRoute;

    private Polyline trackLine;       // Vệt GPS di chuyển thực tế (Xanh dương)
    private Polyline plannedGpxLine;  // Tuyến đường lộ trình GPX mẫu (Cam)
    private Marker currentMarker;     // Con trỏ chấm tròn xanh tích hợp mũi tên chỉ hướng

    private DatabaseHelper dbHelper;
    private LocationManager locationManager;
    private GeoPoint lastPoint = null;

    // Bộ nhận Broadcast khi chạy ngầm từ TrackingService (Chế độ 1)
    private final BroadcastReceiver locationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            double lat = intent.getDoubleExtra("lat", 0.0);
            double lng = intent.getDoubleExtra("lng", 0.0);
            GeoPoint pt = new GeoPoint(lat, lng);

            if (currentMode == MODE_RECORDING) {
                trackLine.addPoint(pt);
                tvStats.setText(String.format(Locale.US, "Đã ghi: %d điểm\nTọa độ: %.5f, %.5f", dbHelper.getPointCount(), lat, lng));
            }
            updateCurrentPosition(pt, 0f, false);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this));
        Configuration.getInstance().setUserAgentValue("J2_Military_Tactical_Tracker");

        setContentView(R.layout.activity_main);

        dbHelper = new DatabaseHelper(this);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

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

        setupMapView();
        setupMenuEvents();
        checkPermissionsAndInit();
    }

    private void setupMapView() {
        mapView.setMultiTouchControls(true);
        mapView.setUseDataConnection(false);

        // Tuyến đường GPX dẫn đường: Màu cam đậm nét 8px
        plannedGpxLine = new Polyline(mapView);
        plannedGpxLine.setColor(Color.parseColor("#FF6600"));
        plannedGpxLine.setWidth(8.0f);
        mapView.getOverlays().add(plannedGpxLine);

        // Vệt thực tế: Màu xanh dương đậm nét 7px
        trackLine = new Polyline(mapView);
        trackLine.setColor(Color.parseColor("#003399"));
        trackLine.setWidth(7.0f);
        mapView.getOverlays().add(trackLine);

        // Con trỏ vị trí: Chấm xanh + Mũi tên định hướng xoay
        currentMarker = new Marker(mapView);
        currentMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        currentMarker.setIcon(createNavArrowIcon());
        currentMarker.setFlat(true);
        currentMarker.setVisible(false);
        mapView.getOverlays().add(currentMarker);

        // Tâm mặc định ban đầu
        GeoPoint centerPoint = new GeoPoint(21.135, 105.505);
        mapView.getController().setZoom(14.0);
        mapView.getController().setCenter(centerPoint);
    }

    // Vẽ biểu tượng Chấm xanh kết hợp Mũi tên chỉ hướng di chuyển
    private BitmapDrawable createNavArrowIcon() {
        int size = 80;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        float cx = size / 2f;
        float cy = size / 2f;

        // 1. Vẽ quầng mờ xanh
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.parseColor("#33007AFF"));
        canvas.drawCircle(cx, cy, 38f, paint);

        // 2. Vẽ mũi tên điều hướng nhọn nhô về phía trước (Hướng 12 giờ)
        Path arrowPath = new Path();
        arrowPath.moveTo(cx, 4f);       // Đầu nhọn mũi tên
        arrowPath.lineTo(cx + 18f, 32f); // Cánh phải
        arrowPath.lineTo(cx, 24f);       // Rãnh hõm giữa
        arrowPath.lineTo(cx - 18f, 32f); // Cánh trái
        arrowPath.close();

        // Viền trắng cho mũi tên
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(5f);
        canvas.drawPath(arrowPath, paint);

        // Ruột mũi tên màu xanh
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.parseColor("#007AFF"));
        canvas.drawPath(arrowPath, paint);

        // 3. Vành tròn trắng bảo vệ chấm tâm
        paint.setColor(Color.WHITE);
        canvas.drawCircle(cx, cy, 18f, paint);

        // 4. Lõi tròn màu xanh đậm Google Maps
        paint.setColor(Color.parseColor("#007AFF"));
        canvas.drawCircle(cx, cy, 13f, paint);

        return new BitmapDrawable(getResources(), bitmap);
    }

    // Cập nhật vị trí, xoay mũi tên và tự dời tâm bản đồ
    private void updateCurrentPosition(GeoPoint point, float bearing, boolean hasBearing) {
        currentMarker.setPosition(point);
        currentMarker.setVisible(true);

        // Tính góc xoay mũi tên theo hướng di chuyển thực tế
        if (hasBearing && bearing != 0f) {
            currentMarker.setRotation(bearing);
        } else if (lastPoint != null) {
            double dLat = point.getLatitude() - lastPoint.getLatitude();
            double dLng = point.getLongitude() - lastPoint.getLongitude();
            if (Math.abs(dLat) > 0.00002 || Math.abs(dLng) > 0.00002) {
                float calculatedBearing = (float) Math.toDegrees(Math.atan2(dLng, dLat));
                if (calculatedBearing < 0) calculatedBearing += 360;
                currentMarker.setRotation(calculatedBearing);
            }
        }
        lastPoint = point;

        // Tự động kéo tâm bản đồ về vị trí GPS khi vừa bắt được sóng lần đầu
        if (isFirstGpsFix) {
            mapView.getController().animateTo(point);
            mapView.getController().setZoom(15.5);
            isFirstGpsFix = false;
            tvQuickStatus.setText("GPS: Đã khóa vị trí");
        }

        mapView.invalidate();
    }

    // Lắng nghe phần cứng GPS trực tiếp ngay khi mở app
    private void startImmediateLocationListening() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        try {
            // Kiểm tra vị trí lưu gần nhất
            Location lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (lastKnown != null) {
                GeoPoint pt = new GeoPoint(lastKnown.getLatitude(), lastKnown.getLongitude());
                updateCurrentPosition(pt, lastKnown.getBearing(), lastKnown.hasBearing());
            }

            // Kích hoạt nhận sóng GPS liên tục mỗi 1 giây hoặc 1 mét di chuyển
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1.0f, this);
        } catch (Exception ignored) {}
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null) return;
        GeoPoint pt = new GeoPoint(location.getLatitude(), location.getLongitude());

        if (currentMode == MODE_STANDBY) {
            tvQuickStatus.setText(String.format(Locale.US, "GPS: %.4f, %.4f", location.getLatitude(), location.getLongitude()));
        } else if (currentMode == MODE_FOLLOW_ROUTE) {
            // CHẾ ĐỘ 2: Chỉ cập nhật con trỏ mũi tên di chuyển, không ghi vệt
            tvQuickStatus.setText(String.format(Locale.US, "Dẫn đường: %.4f, %.4f", location.getLatitude(), location.getLongitude()));
            mapView.getController().animateTo(pt);
        }

        updateCurrentPosition(pt, location.getBearing(), location.hasBearing());
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
            currentMode = MODE_STANDBY;
            tvQuickStatus.setText("Chế độ: Chờ lệnh");
            mapView.invalidate();
            drawerLayout.closeDrawer(GravityCompat.START);
            Toast.makeText(this, "Đã dọn sạch lộ trình dẫn đường", Toast.LENGTH_SHORT).show();
        });
    }

    private void startModeRecord() {
        currentMode = MODE_RECORDING;
        tvQuickStatus.setText("Chế độ: ĐANG GHI");

        dbHelper.clearAllPoints();
        trackLine.getActualPoints().clear();
        mapView.invalidate();

        Intent intent = new Intent(this, TrackingService.class);
        startService(intent);

        btnStartRecord.setEnabled(false);
        btnStopRecord.setEnabled(true);
        Toast.makeText(this, "Bắt đầu ghi nhận vệt hành quân!", Toast.LENGTH_SHORT).show();
    }

    private void stopModeRecordAndExport() {
        Intent intent = new Intent(this, TrackingService.class);
        stopService(intent);

        currentMode = MODE_STANDBY;
        tvQuickStatus.setText("Chế độ: Đã dừng");
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
            Toast.makeText(this, "Không có file .gpx trong thư mục Download!", Toast.LENGTH_LONG).show();
            return;
        }

        String[] fileNames = new String[gpxFiles.length];
        for (int i = 0; i < gpxFiles.length; i++) {
            fileNames[i] = gpxFiles[i].getName();
        }

        new AlertDialog.Builder(this)
                .setTitle("Chọn lộ trình dẫn đường")
                .setItems(fileNames, (dialog, which) -> {
                    loadPlannedGpx(gpxFiles[which]);
                })
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
                plannedGpxLine.setPoints(points);
                mapView.getController().animateTo(points.get(0));
                mapView.getController().setZoom(15.0);
                mapView.invalidate();

                currentMode = MODE_FOLLOW_ROUTE;
                tvQuickStatus.setText("Chế độ: DẪN ĐƯỜNG GPX");
                drawerLayout.closeDrawer(GravityCompat.START);
                Toast.makeText(this, "Đã nạp lộ trình (" + points.size() + " điểm). Không ghi vết.", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Lỗi nạp file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "Lỗi xuất: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        } finally {
            cursor.close();
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
        registerReceiver(locationReceiver, new IntentFilter("GPS_LOCATION_UPDATE"));
        startImmediateLocationListening();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
        unregisterReceiver(locationReceiver);
        try {
            locationManager.removeUpdates(this);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mapView.onDetach();
    }
}
