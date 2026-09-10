package com.example.j2offlinetracker;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 200;

    private TextView tvStatus, tvCoordinates, tvPointCount;
    private Button btnStart, btnStopExport;
    private MapView mapView;
    private Polyline trackLine;
    private Marker currentMarker;

    private DatabaseHelper dbHelper;
    private boolean isTracking = false;

    private final BroadcastReceiver locationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            double lat = intent.getDoubleExtra("lat", 0.0);
            double lng = intent.getDoubleExtra("lng", 0.0);

            tvCoordinates.setText(String.format(Locale.US, "Tọa độ: %.5f, %.5f", lat, lng));
            tvPointCount.setText("Số điểm đã ghi: " + dbHelper.getPointCount());

            // Cập nhật vị trí và vẽ đường lên bản đồ
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

        // Khởi tạo cấu hình Osmdroid
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this));
        Configuration.getInstance().setUserAgentValue(getPackageName());

        setContentView(R.layout.activity_main);

        dbHelper = new DatabaseHelper(this);

        tvStatus = findViewById(R.id.tvStatus);
        tvCoordinates = findViewById(R.id.tvCoordinates);
        tvPointCount = findViewById(R.id.tvPointCount);
        btnStart = findViewById(R.id.btnStart);
        btnStopExport = findViewById(R.id.btnStopExport);
        mapView = findViewById(R.id.mapView);

        setupMapView();

        btnStart.setOnClickListener(v -> checkPermissionsAndStart());
        btnStopExport.setOnClickListener(v -> stopTrackingAndExport());

        updateUiState();
        checkStoragePermissionAndLoadMap();
    }

    private void setupMapView() {
        mapView.setMultiTouchControls(true);
        mapView.setUseDataConnection(false); // Ngắt hoàn toàn kết nối mạng ngoài

        // Thiết lập đường vệt màu đỏ nổi bật
        trackLine = new Polyline(mapView);
        trackLine.setColor(Color.RED);
        trackLine.setWidth(7.0f);
        mapView.getOverlays().add(trackLine);

        // Con trỏ vị trí hiện tại
        currentMarker = new Marker(mapView);
        currentMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        currentMarker.setTitle("Vị trí hiện tại");
        currentMarker.setVisible(false);
        mapView.getOverlays().add(currentMarker);

        // Tâm mặc định: Sơn Tây (21.135°B, 105.505°Đ), mức zoom 13
        GeoPoint centerPoint = new GeoPoint(21.135, 105.505);
        mapView.getController().setZoom(13.0);
        mapView.getController().setCenter(centerPoint);
    }

    private void checkStoragePermissionAndLoadMap() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            loadOfflineMap();
        }
    }

    private void loadOfflineMap() {
        File mapFile = new File(Environment.getExternalStorageDirectory(), "osmdroid/BanDoJ2.sqlite");
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
                Toast.makeText(this, "Không thể đọc file bản đồ: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "Chưa tìm thấy file osmdroid/BanDoJ2.sqlite!", Toast.LENGTH_LONG).show();
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
            Toast.makeText(this, "Đã xuất GPX vào thư mục Download:\n" + gpxFile.getName(), Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, "Lỗi xuất file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
                Toast.makeText(this, "Cần cấp đủ quyền Vị trí và Bộ nhớ!", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
