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
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.util.Xml;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

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
    private static final int REQUEST_PICK_GPX = 300;

    private TextView tvStatus, tvCoordinates, tvPointCount;
    private Button btnStart, btnStopExport, btnLoadGpx;
    private MapView mapView;
    
    private Polyline trackLine;       // Vệt GPS di chuyển thực tế (Xanh dương)
    private Polyline plannedGpxLine;  // Vệt lộ trình mẫu GPX nạp vào (Cam/Đỏ)
    private Marker currentMarker;     // Chấm tròn phong cách Google Maps

    private DatabaseHelper dbHelper;
    private boolean isTracking = false;

    private final BroadcastReceiver locationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            double lat = intent.getDoubleExtra("lat", 0.0);
            double lng = intent.getDoubleExtra("lng", 0.0);

            tvCoordinates.setText(String.format(Locale.US, "Tọa độ: %.5f, %.5f", lat, lng));
            tvPointCount.setText("Số điểm đã ghi: " + dbHelper.getPointCount());

            GeoPoint currentPoint = new GeoPoint(lat, lng);
            trackLine.addPoint(currentPoint);
            currentMarker.setPosition(currentPoint);
            currentMarker.setVisible(true);

            mapView.getController().animateTo(currentPoint);
            mapView.invalidate();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this));
        Configuration.getInstance().setUserAgentValue("J2_Military_Tracker");

        setContentView(R.layout.activity_main);

        dbHelper = new DatabaseHelper(this);

        tvStatus = findViewById(R.id.tvStatus);
        tvCoordinates = findViewById(R.id.tvCoordinates);
        tvPointCount = findViewById(R.id.tvPointCount);
        btnStart = findViewById(R.id.btnStart);
        btnStopExport = findViewById(R.id.btnStopExport);
        btnLoadGpx = findViewById(R.id.btnLoadGpx);
        mapView = findViewById(R.id.mapView);

        setupMapView();

        btnStart.setOnClickListener(v -> checkPermissionsAndStart());
        btnStopExport.setOnClickListener(v -> stopTrackingAndExport());
        btnLoadGpx.setOnClickListener(v -> pickGpxFile());

        updateUiState();
        checkStoragePermissionAndLoadMap();
    }

    private void setupMapView() {
        mapView.setMultiTouchControls(true);
        mapView.setUseDataConnection(false);

        // 1. Đường lộ trình kế hoạch nạp từ GPX (Màu Cam Đậm, nét dày 8px)
        plannedGpxLine = new Polyline(mapView);
        plannedGpxLine.setColor(Color.parseColor("#FF6600"));
        plannedGpxLine.setWidth(8.0f);
        mapView.getOverlays().add(plannedGpxLine);

        // 2. Đường vết GPS thực tế bạn đi (Màu Xanh Dương Đậm, nét 7px)
        trackLine = new Polyline(mapView);
        trackLine.setColor(Color.parseColor("#003399"));
        trackLine.setWidth(7.0f);
        mapView.getOverlays().add(trackLine);

        // 3. Con trỏ vị trí kiểu chấm Google Maps (Có quầng mờ + vòng trắng + lõi xanh)
        currentMarker = new Marker(mapView);
        currentMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        currentMarker.setIcon(createGoogleMapsLocationDot());
        currentMarker.setVisible(false);
        mapView.getOverlays().add(currentMarker);

        // Đặt tâm mặc định khu vực Sơn Tây
        GeoPoint centerPoint = new GeoPoint(21.135, 105.505);
        mapView.getController().setZoom(14.0);
        mapView.getController().setCenter(centerPoint);
    }

    // Hàm tạo chấm xanh Google Maps bằng Canvas (Cực nhẹ, tiết kiệm RAM J2)
    private BitmapDrawable createGoogleMapsLocationDot() {
        int size = 72; // Kích thước icon
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Lớp 1: Quầng mờ xung quanh (Halos) màu xanh nhạt
        paint.setColor(Color.parseColor("#442196F3"));
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);

        // Lớp 2: Viền tròn trắng sắc nét
        paint.setColor(Color.WHITE);
        canvas.drawCircle(size / 2f, size / 2f, 20f, paint);

        // Lớp 3: Lõi chấm tròn xanh Google Maps đậm ở giữa
        paint.setColor(Color.parseColor("#007AFF"));
        canvas.drawCircle(size / 2f, size / 2f, 15f, paint);

        return new BitmapDrawable(getResources(), bitmap);
    }

    // Mở bộ chọn file để chọn file .gpx
    private void pickGpxFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "Chọn file lộ trình .GPX"), REQUEST_PICK_GPX);
        } catch (Exception e) {
            Toast.makeText(this, "Không có ứng dụng quản lý file!", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_GPX && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                loadRouteFromGpx(uri);
            }
        }
    }

    // Giải mã XML của file GPX và vẽ lộ trình lên bản đồ
    private void loadRouteFromGpx(Uri uri) {
        List<GeoPoint> routePoints = new ArrayList<>();
        try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
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
                            routePoints.add(new GeoPoint(Double.parseDouble(latStr), Double.parseDouble(lonStr)));
                        }
                    }
                }
                eventType = parser.next();
            }

            if (!routePoints.isEmpty()) {
                plannedGpxLine.setPoints(routePoints);
                mapView.getController().animateTo(routePoints.get(0));
                mapView.getController().setZoom(15.0);
                mapView.invalidate();
                Toast.makeText(this, "Đã nạp lộ trình: " + routePoints.size() + " điểm!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "File GPX không có dữ liệu đường đi!", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Lỗi đọc GPX: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void checkStoragePermissionAndLoadMap() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            loadOfflineMap();
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
                Toast.makeText(this, "Không thể đọc bản đồ: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "Chưa tìm thấy file osmdroid/BanDoJ2.mbtiles!", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
        registerReceiver(locationReceiver, new IntentFilter("GPS_LOCATION_UPDATE"));
        tvPointCount.setText("Số điểm đã ghi: " + dbHelper.getPointCount());
        loadExistingTrackFromDb();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
        unregisterReceiver(locationReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mapView.onDetach();
    }

    private void loadExistingTrackFromDb() {
        Cursor cursor = dbHelper.getAllPoints();
        if (cursor != null) {
            trackLine.getActualPoints().clear();
            GeoPoint last = null;
            while (cursor.moveToNext()) {
                double lat = cursor.getDouble(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_LAT));
                double lng = cursor.getDouble(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_LNG));
                last = new GeoPoint(lat, lng);
                trackLine.addPoint(last);
            }
            cursor.close();

            if (last != null) {
                currentMarker.setPosition(last);
                currentMarker.setVisible(true);
            }
            mapView.invalidate();
        }
    }

    private void checkPermissionsAndStart() {
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
            startTrackingService();
        } else {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
        }
    }

    private void startTrackingService() {
        dbHelper.clearAllPoints();
        trackLine.getActualPoints().clear();
        currentMarker.setVisible(false);
        mapView.invalidate();

        Intent intent = new Intent(this, TrackingService.class);
        startService(intent);
        isTracking = true;
        updateUiState();
        Toast.makeText(this, "Đã bắt đầu ghi GPS!", Toast.LENGTH_SHORT).show();
    }

    private void stopTrackingAndExport() {
        Intent intent = new Intent(this, TrackingService.class);
        stopService(intent);
        isTracking = false;
        updateUiState();
        exportGpxFile();
    }

    private void updateUiState() {
        if (isTracking) {
            tvStatus.setText("Trạng thái: Đang theo dõi");
            btnStart.setEnabled(false);
            btnStopExport.setEnabled(true);
        } else {
            tvStatus.setText("Trạng thái: Đã dừng");
            btnStart.setEnabled(true);
            btnStopExport.setEnabled(false);
        }
    }

    private void exportGpxFile() {
        Cursor cursor = dbHelper.getAllPoints();
        if (cursor == null || cursor.getCount() == 0) {
            Toast.makeText(this, "Không có điểm nào để xuất GPX", Toast.LENGTH_SHORT).show();
            if (cursor != null) cursor.close();
            return;
        }

        File exportDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!exportDir.exists()) exportDir.mkdirs();

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File gpxFile = new File(exportDir, "Track_" + timeStamp + ".gpx");

        try (FileWriter writer = new FileWriter(gpxFile)) {
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            writer.write("<gpx version=\"1.1\" creator=\"J2OfflineTracker\">\n");
            writer.write("  <trk>\n    <name>Lộ trình " + timeStamp + "</name>\n    <trkseg>\n");

            SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);

            while (cursor.moveToNext()) {
                double lat = cursor.getDouble(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_LAT));
                double lng = cursor.getDouble(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_LNG));
                float speed = cursor.getFloat(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_SPEED));
                long time = cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_TIME));

                writer.write(String.format(Locale.US,
                        "      <trkpt lat=\"%.6f\" lon=\"%.6f\">\n        <speed>%.2f</speed>\n        <time>%s</time>\n      </trkpt>\n",
                        lat, lng, speed, isoFormat.format(new Date(time))));
            }

            writer.write("    </trkseg>\n  </trk>\n</gpx>");
            Toast.makeText(this, "Đã xuất GPX vào Download:\n" + gpxFile.getName(), Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, "Lỗi xuất: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        } finally {
            cursor.close();
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
                startTrackingService();
            } else {
                Toast.makeText(this, "Cần cấp đủ quyền!", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
