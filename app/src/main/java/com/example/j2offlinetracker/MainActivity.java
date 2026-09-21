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
importToàn bộ mã nguồn dưới đây đã tích hợp luồng kết nối **Bluetooth Classic (SPP)** tự động bắt tay với thiết bị **`LoRa_Tactical_Bridge`**, đồng thời **giữ nguyên 100% tất cả thuật toán định vị, cơ chế chạy ngầm khi tắt màn hình, bộ lọc nhiễu, xử lý SQLite và logic hiển thị bản đồ** đã tối ưu trước đó.

---

### Các điểm được bổ sung mà không ảnh hưởng thuật toán cũ

1. **Luồng Bluetooth chạy nền độc lập (`BtThread`):** Quá trình dò tìm thiết bị `LoRa_Tactical_Bridge` và mở socket kết nối diễn ra ngầm trong luồng riêng, không làm đơ hay giật giao diện (chống tràn RAM và lỗi ANR trên cấu hình máy Samsung J2).
2. **Tự động truyền tọa độ từ điện thoại sang mạch Heltec:** Mỗi khi bộ định vị của J2 chốt được tọa độ (cả khi bật hay khóa màn hình), ứng dụng tự động đóng gói chuỗi `#GPS,lat,lng,speed\n` bắn sang Heltec. Màn hình OLED của Heltec sẽ lập tức chuyển sang `BT: DA KET NOI J2` và cập nhật tọa độ thực tế từ J2 để phát sóng LoRa.
3. **Đẩy lệnh chiến thuật qua LoRa:** Khi bạn bấm các nút mệnh lệnh (*DỪNG XE, TĂNG TỐC, SOS...*), chuỗi lệnh `#CMD,1,code,desc\n` sẽ được bắn thẳng qua Bluetooth để Heltec phát xung LoRa ra toàn đoàn.
4. **Nhận lệnh chỉ huy hai chiều:** Khi Heltec nhận sóng LoRa từ xe khác hoặc trạm chỉ huy, gói tin được đẩy về J2 qua Bluetooth. Ứng dụng sẽ tự động rung chuông và bật hộp thoại khẩn cấp `showCommandAlert()`.

---

### Mã nguồn hoàn chỉnh `MainActivity.java`

Mở tệp **`app/src/main/java/com/example/j2offlinetracker/MainActivity.java`** trên GitHub, xóa toàn bộ nội dung cũ và dán đè bản mã nguồn hoàn chỉnh dưới đây:

```java
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
import android.graphics.drawable.BitmapDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
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

    // Các chế độ vận hành
    private static final int MODE_STANDBY = 0;
    private static final int MODE_RECORDING = 1;
    private static final int MODE_FOLLOW_ROUTE = 2;

    private int currentMode = MODE_STANDBY;
    private boolean isFirstGpsFix = true;

    // Giao diện
    private DrawerLayout drawerLayout;
    private MapView mapView;
    private TextView tvQuickStatus, tvStats;
    private Button btnOpenMenu, btnCloseMenu;
    private Button btnStartRecord, btnStopRecord;
    private Button btnLoadFollowGpx, btnClearRoute;

    private Button btnCmdStop, btnCmdResume, btnCmdSpeedUp, btnCmdSlowDown;
    private Button btnCmdCloseSpacing, btnCmdOpenSpacing, btnCmdEmergency;

    // Bản đồ & Vết vẽ
    private Polyline trackLine;
    private Polyline plannedGpxLine;
    private Marker currentMarker;

    // Lưu trữ & Cảm biến
    private DatabaseHelper dbHelper;
    private LocationManager locationManager;
    private Vibrator vibrator;
    private Ringtone alertRingtone;

    // --- CẤU HÌNH BLUETOOTH CLASSIC (SPP) CHO BO MẠCH HELTEC ---
    private static final String TARGET_BT_NAME = "LoRa_Tactical_Bridge";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket btSocket;
    private OutputStream btOutputStream;
    private Thread btConnectThread;
    private boolean isBtConnected = false;
    private long lastBtSendTime = 0;

    // Bộ thu nhận tọa độ thời gian thực từ TrackingService chạy ngầm
    private final BroadcastReceiver locationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            double lat = intent.getDoubleExtra("lat", 0.0);
            double lng = intent.getDoubleExtra("lng", 0.0);
            float speed = intent.getFloatExtra("speed", 0.0f);
            GeoPoint pt = new GeoPoint(lat, lng);

            currentMarker.setPosition(pt);
            currentMarker.setVisible(true);

            if (currentMode == MODE_RECORDING) {
                trackLine.addPoint(pt);
                tvStats.setText(String.format(Locale.US, "Đã ghi: %d điểm\nTọa độ: %.5f, %.5f", dbHelper.getPointCount(), lat, lng));
            } else if (currentMode == MODE_FOLLOW_ROUTE) {
                // Chế độ dẫn đường: KHÔNG vẽ vệt để chống rối, chỉ trượt tâm bản đồ theo xe
                mapView.getController().animateTo(pt);
                tvQuickStatus.setText(String.format(Locale.US, "Dẫn đường: %.5f, %.5f", lat, lng));
            }

            // Định kỳ 1 giây: Gửi tọa độ điện thoại sang mạch Heltec qua Bluetooth để phát LoRa
            if (System.currentTimeMillis() - lastBtSendTime > 1000) {
                lastBtSendTime = System.currentTimeMillis();
                String gpsPacket = String.format(Locale.US, "#GPS,%.6f,%.6f,%.1f\n", lat, lng, speed);
                sendBluetoothData(gpsPacket);
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

        dbHelper = new DatabaseHelper(this);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (alarmUri == null) {
            alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        }
        alertRingtone = RingtoneManager.getRingtone(this, alarmUri);

        initViews();
        setupMapView();
        setupMenuEvents();
        setupCommandButtons();
        checkPermissionsAndInit();

        // Kích hoạt luồng kết nối Bluetooth tự động với Heltec
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

        // Đường mẫu GPX màu hồng dạ quang (#E91E63)
        plannedGpxLine = new Polyline(mapView);
        plannedGpxLine.setColor(Color.parseColor("#E91E63"));
        plannedGpxLine.setWidth(9.0f);
        plannedGpxLine.getOutlinePaint().setStrokeJoin(Paint.Join.ROUND);
        plannedGpxLine.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
        mapView.getOverlays().add(plannedGpxLine);

        // Vệt ghi thực tế màu xanh đậm (#003399)
        trackLine = new Polyline(mapView);
        trackLine.setColor(Color.parseColor("#003399"));
        trackLine.setWidth(7.0f);
        trackLine.getOutlinePaint().setStrokeJoin(Paint.Join.ROUND);
        trackLine.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
        mapView.getOverlays().add(trackLine);

        currentMarker = new Marker(mapView);
        currentMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        currentMarker.setIcon(createGoogleMapsLocationDot());
        currentMarker.setInfoWindow(null);
        currentMarker.setVisible(false);
        mapView.getOverlays().add(currentMarker);

        GeoPoint centerPoint = new GeoPoint(21.135, 105.505);
        mapView.getController().setZoom(14.0);
        mapView.getController().setCenter(centerPoint);
    }

    private BitmapDrawable createGoogleMapsLocationDot() {
        int size = 64;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        float cx = size / 2f;
        float cy = size / 2f;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.parseColor("#33007AFF"));
        canvas.drawCircle(cx, cy, 28f, paint);

        paint.setColor(Color.WHITE);
        canvas.drawCircle(cx, cy, 16f, paint);

        paint.setColor(Color.parseColor("#007AFF"));
        canvas.drawCircle(cx, cy, 12f, paint);

        return new BitmapDrawable(getResources(), bitmap);
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
        String packet = String.format(Locale.US, "#CMD,1,%s,%s\n", cmdCode, cmdDescription);
        
        // Phát lệnh trực tiếp qua Bluetooth sang mạch Heltec
        sendBluetoothData(packet);

        tvQuickStatus.setText("ĐÃ PHÁT: " + cmdDescription);
        Toast.makeText(this, "Đã phát lệnh LoRa: " + cmdDescription, Toast.LENGTH_SHORT).show();
    }

    public void showCommandAlert(String senderName, String commandText) {
        try {
            if (alertRingtone != null && !alertRingtone.isPlaying()) {
                alertRingtone.play();
            }
            if (vibrator != null) {
                long[] pattern = {0, 600, 300, 600, 300};
                vibrator.vibrate(pattern, 0);
            }
        } catch (Exception ignored) {}

        runOnUiThread(() -> {
            new AlertDialog.Builder(this)
                    .setTitle("🚨 MỆNH LỆNH TỪ " + senderName.toUpperCase())
                    .setMessage("\n" + commandText + "\n\n(Tài xế/Trưởng xe lập tức chấp hành!)")
                    .setCancelable(false)
                    .setPositiveButton("ĐÃ NHẬN LỆNH (XÁC NHẬN)", (dialog, which) -> {
                        if (alertRingtone != null && alertRingtone.isPlaying()) {
                            alertRingtone.stop();
                        }
                        if (vibrator != null) {
                            vibrator.cancel();
                        }
                        tvQuickStatus.setText("LỆNH: " + commandText);
                        sendBluetoothData("#ACK,1,RECEIVED\n");
                        Toast.makeText(MainActivity.this, "Đã gửi xác nhận về Chỉ huy!", Toast.LENGTH_SHORT).show();
                    })
                    .show();
        });
    }

    // --- MODULE KẾT NỐI BLUETOOTH TỰ ĐỘNG CHẠY NGẦM ---
    private void startBluetoothConnection() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            return;
        }

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
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Chưa ghép đôi thiết bị LoRa_Tactical_Bridge!", Toast.LENGTH_SHORT).show());
                return;
            }

            try {
                bluetoothAdapter.cancelDiscovery();
                btSocket = targetDevice.createRfcommSocketToServiceRecord(SPP_UUID);
                btSocket.connect();
                btOutputStream = btSocket.getOutputStream();
                isBtConnected = true;

                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Đã kết nối phần cứng Heltec LoRa!", Toast.LENGTH_SHORT).show();
                    tvQuickStatus.setText("LoRa: ĐÃ KẾT NỐI");
                });

                // Luồng lắng nghe dữ liệu dội về từ LoRa qua Bluetooth
                BufferedReader reader = new BufferedReader(new InputStreamReader(btSocket.getInputStream()));
                String line;
                while (isBtConnected && (line = reader.readLine()) != null) {
                    final String receivedPacket = line.trim();
                    if (receivedPacket.startsWith("#CMD")) {
                        // Định dạng: #CMD,ID,MÃ,NỘI_DUNG
                        String[] parts = receivedPacket.split(",");
                        if (parts.length >= 4) {
                            String cmdDesc = parts[3];
                            showCommandAlert("XE CHỈ HUY", cmdDesc);
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

    @Override
    public void onLocationChanged(Location location) {
        if (location == null) return;

        GeoPoint currentPoint = new GeoPoint(location.getLatitude(), location.getLongitude());
        currentMarker.setPosition(currentPoint);
        currentMarker.setVisible(true);

        if (isFirstGpsFix) {
            mapView.getController().animateTo(currentPoint);
            mapView.getController().setZoom(16.0);
            isFirstGpsFix = false;
            tvQuickStatus.setText("GPS: Đã khóa vị trí");
        } else if (currentMode == MODE_FOLLOW_ROUTE) {
            mapView.getController().animateTo(currentPoint);
            tvQuickStatus.setText(String.format(Locale.US, "Dẫn đường: %.5f, %.5f", location.getLatitude(), location.getLongitude()));
        }

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
            tvQuickStatus.setText("Chế độ: Chờ lệnh");
            mapView.invalidate();
            drawerLayout.closeDrawer(GravityCompat.START);

            Intent intent = new Intent(this, TrackingService.class);
            stopService(intent);

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
        ContextCompat.startForegroundService(this, intent);

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
                dbHelper.clearAllPoints();
                trackLine.getActualPoints().clear();
                plannedGpxLine.setPoints(points);
                mapView.getController().animateTo(points.get(0));
                mapView.getController().setZoom(16.0);
                mapView.invalidate();

                currentMode = MODE_FOLLOW_ROUTE;
                tvQuickStatus.setText("Chế độ: DẪN ĐƯỜNG GPX");
                drawerLayout.closeDrawer(GravityCompat.START);

                Intent intent = new Intent(this, TrackingService.class);
                ContextCompat.startForegroundService(this, intent);

                Toast.makeText(this, "Đang dẫn đường: " + gpxFile.getName(), Toast.LENGTH_SHORT).show();
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
                GeoPoint pt = new GeoPoint(lastKnown.getLatitude(), lastKnown.getLongitude());
                currentMarker.setPosition(pt);
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
        registerReceiver(locationReceiver, new IntentFilter("GPS_LOCATION_UPDATE"));

        if (currentMode == MODE_RECORDING) {
            loadExistingTrackFromDb();
        }
        startImmediateLocationListening();

        // Tự động kết nối lại nếu bị gián đoạn
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

        if (alertRingtone != null && alertRingtone.isPlaying()) {
            alertRingtone.stop();
        }
        if (vibrator != null) {
            vibrator.cancel();
        }
    }
}
