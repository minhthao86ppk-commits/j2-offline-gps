package com.example.j2offlinetracker;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 200;

    private TextView tvStatus, tvCoordinates, tvPointCount;
    private Button btnStart, btnStopExport;

    private DatabaseHelper dbHelper;
    private boolean isTracking = false;

    private final BroadcastReceiver locationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            double lat = intent.getDoubleExtra("lat", 0.0);
            double lng = intent.getDoubleExtra("lng", 0.0);
            tvCoordinates.setText(String.format(Locale.US, "Tọa độ: %.5f, %.5f", lat, lng));
            tvPointCount.setText("Số điểm đã ghi: " + dbHelper.getPointCount());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dbHelper = new DatabaseHelper(this);

        tvStatus = findViewById(R.id.tvStatus);
        tvCoordinates = findViewById(R.id.tvCoordinates);
        tvPointCount = findViewById(R.id.tvPointCount);
        btnStart = findViewById(R.id.btnStart);
        btnStopExport = findViewById(R.id.btnStopExport);

        btnStart.setOnClickListener(v -> checkPermissionsAndStart());
        btnStopExport.setOnClickListener(v -> stopTrackingAndExport());

        updateUiState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(locationReceiver, new IntentFilter("GPS_LOCATION_UPDATE"));
        tvPointCount.setText("Số điểm đã ghi: " + dbHelper.getPointCount());
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(locationReceiver);
    }

    private void checkPermissionsAndStart() {
        String[] permissions = {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
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
        Intent intent = new Intent(this, TrackingService.class);
        startService(intent);
        isTracking = true;
        updateUiState();
        Toast.makeText(this, "Đã bắt đầu ghi nhận lộ trình GPS", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "Không có điểm nào được ghi để xuất GPX", Toast.LENGTH_SHORT).show();
            if (cursor != null) cursor.close();
            return;
        }

        File exportDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!exportDir.exists()) {
            exportDir.mkdirs();
        }

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
            Toast.makeText(this, "Đã xuất file GPX vào thư mục Download:\n" + gpxFile.getName(), Toast.LENGTH_LONG).show();
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
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startTrackingService();
            } else {
                Toast.makeText(this, "Cần cấp đủ quyền GPS để ghi nhận lộ trình!", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
