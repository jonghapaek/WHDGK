package com.cane.blindcane;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends AppCompatActivity {

    private static final String TAG         = "BlindCane";
    private static final UUID   SPP_UUID    = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String ESP_CAM_URL = "http://192.168.4.1/capture";
    private static final long   CAM_POLL_MS = 1000; // 1초마다 프레임 요청

    // ── 영어 → 한국어 번역표 ──────────────────────────────────────────────
    private static final Map<String, String> KOREAN = new LinkedHashMap<>();
    static {
        KOREAN.put("Person",       "사람");
        KOREAN.put("Man",          "남성");
        KOREAN.put("Woman",        "여성");
        KOREAN.put("Child",        "어린이");
        KOREAN.put("Car",          "자동차");
        KOREAN.put("Truck",        "트럭");
        KOREAN.put("Bus",          "버스");
        KOREAN.put("Bicycle",      "자전거");
        KOREAN.put("Motorcycle",   "오토바이");
        KOREAN.put("Vehicle",      "차량");
        KOREAN.put("Dog",          "개");
        KOREAN.put("Cat",          "고양이");
        KOREAN.put("Chair",        "의자");
        KOREAN.put("Table",        "테이블");
        KOREAN.put("Desk",         "책상");
        KOREAN.put("Bench",        "벤치");
        KOREAN.put("Sofa",         "소파");
        KOREAN.put("Furniture",    "가구");
        KOREAN.put("Door",         "문");
        KOREAN.put("Wall",         "벽");
        KOREAN.put("Stairs",       "계단");
        KOREAN.put("Pole",         "기둥");
        KOREAN.put("Column",       "기둥");
        KOREAN.put("Fence",        "울타리");
        KOREAN.put("Sign",         "표지판");
        KOREAN.put("Building",     "건물");
        KOREAN.put("Tree",         "나무");
        KOREAN.put("Plant",        "식물");
        KOREAN.put("Trash can",    "쓰레기통");
        KOREAN.put("Box",          "박스");
        KOREAN.put("Bag",          "가방");
        KOREAN.put("Bottle",       "병");
        KOREAN.put("Bicycle rack", "자전거 거치대");
    }

    // ── 위험도 우선순위 ───────────────────────────────────────────────────
    private static final String[] PRIORITY = {
        "Person","Man","Woman","Child",
        "Car","Truck","Bus","Motorcycle","Bicycle","Vehicle",
        "Dog","Cat",
        "Stairs","Door","Pole","Column","Bench","Chair","Table",
        "Tree","Fence","Sign","Trash can","Box","Bag","Building"
    };

    // ── 필드 ────────────────────────────────────────────────────────────
    private BluetoothAdapter   btAdapter;
    private BluetoothSocket    btSocket;
    private TextToSpeech       tts;
    private Handler            uiHandler;
    private ImageLabeler       labeler;

    // ESP32-CAM 폴링 스레드
    private HandlerThread      espThread;
    private Handler            espHandler;
    private volatile boolean   espRunning = false;

    private final AtomicReference<String> latestObject = new AtomicReference<>("장애물");
    private volatile boolean connected  = false;
    private long lastSpeakMs = 0;
    private static final long MIN_INTERVAL_MS = 800;

    // UI
    private TextView  tvStatus, tvMessage, tvObject, tvConfidence, tvEspStatus;
    private ListView  lvDevices;
    private Button    btnConnect;
    private ImageView ivFrame;

    private final ArrayList<String>          deviceNames  = new ArrayList<>();
    private final ArrayList<BluetoothDevice> deviceList   = new ArrayList<>();
    private ArrayAdapter<String>             deviceAdapter;

    // ── onCreate ────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        uiHandler    = new Handler(Looper.getMainLooper());
        tvStatus     = findViewById(R.id.tvStatus);
        tvMessage    = findViewById(R.id.tvMessage);
        tvObject     = findViewById(R.id.tvObject);
        tvConfidence = findViewById(R.id.tvConfidence);
        tvEspStatus  = findViewById(R.id.tvEspStatus);
        lvDevices    = findViewById(R.id.lvDevices);
        btnConnect   = findViewById(R.id.btnConnect);
        ivFrame      = findViewById(R.id.ivFrame);

        // TTS 초기화 (한국어)
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS)
                tts.setLanguage(new Locale("ko", "KR"));
        });

        // ML Kit 이미지 라벨러 (신뢰도 60% 이상)
        labeler = ImageLabeling.getClient(
            new ImageLabelerOptions.Builder().setConfidenceThreshold(0.60f).build()
        );

        // 블루투스
        btAdapter     = BluetoothAdapter.getDefaultAdapter();
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceNames);
        lvDevices.setAdapter(deviceAdapter);
        lvDevices.setOnItemClickListener((p, v, pos, id) -> connectToDevice(deviceList.get(pos)));
        btnConnect.setOnClickListener(v -> {
            if (connected) disconnect();
            else loadPairedDevices();
        });

        requestPermissions();
    }

    // ── 권한 요청 ────────────────────────────────────────────────────────
    private void requestPermissions() {
        List<String> needed = new ArrayList<>();
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            needed.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        if (needed.isEmpty()) {
            startEspCamPolling();
            loadPairedDevices();
        } else {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        startEspCamPolling();
        loadPairedDevices();
    }

    // ── ESP32-CAM HTTP 폴링 ──────────────────────────────────────────────
    private void startEspCamPolling() {
        espThread  = new HandlerThread("EspCamThread");
        espThread.start();
        espHandler = new Handler(espThread.getLooper());
        espRunning = true;
        espHandler.post(this::fetchFrame);
    }

    private void fetchFrame() {
        if (!espRunning) return;
        try {
            URL url = new URL(ESP_CAM_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");
            conn.connect();

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                Bitmap bmp = BitmapFactory.decodeStream(conn.getInputStream());
                conn.disconnect();
                if (bmp != null) {
                    uiHandler.post(() -> {
                        ivFrame.setImageBitmap(bmp);
                        tvEspStatus.setText("카메라 ESP32-CAM: 연결됨 ✓");
                        tvEspStatus.setTextColor(0xFF4ADE80);
                    });
                    runMlKit(bmp);
                }
            } else {
                conn.disconnect();
                showEspDisconnected();
            }
        } catch (Exception e) {
            showEspDisconnected();
        }

        if (espRunning) {
            espHandler.postDelayed(this::fetchFrame, CAM_POLL_MS);
        }
    }

    private void showEspDisconnected() {
        uiHandler.post(() -> {
            tvEspStatus.setText("카메라: WiFi 'BlindCane_Cam' 연결 필요");
            tvEspStatus.setTextColor(0xFFF59E0B);
        });
    }

    private void runMlKit(Bitmap bitmap) {
        InputImage img = InputImage.fromBitmap(bitmap, 0);
        labeler.process(img)
            .addOnSuccessListener(this::onLabelsDetected)
            .addOnFailureListener(e -> Log.e(TAG, "ML Kit 오류", e));
    }

    // ML Kit 결과 처리
    private void onLabelsDetected(List<ImageLabel> labels) {
        if (labels.isEmpty()) return;

        String korean = pickBestLabel(labels);
        float  conf   = labels.get(0).getConfidence();
        latestObject.set(korean);

        uiHandler.post(() -> {
            tvObject.setText("AI 인식: " + korean);
            tvConfidence.setText(String.format("신뢰도: %.0f%%", conf * 100));
        });
    }

    private String pickBestLabel(List<ImageLabel> labels) {
        for (String key : PRIORITY) {
            for (ImageLabel lbl : labels) {
                if (lbl.getText().toLowerCase().contains(key.toLowerCase())
                        && lbl.getConfidence() >= 0.55f) {
                    return KOREAN.getOrDefault(key, key);
                }
            }
        }
        String top = labels.get(0).getText();
        for (Map.Entry<String, String> e : KOREAN.entrySet()) {
            if (top.toLowerCase().contains(e.getKey().toLowerCase()))
                return e.getValue();
        }
        return "장애물";
    }

    // ── 블루투스 ─────────────────────────────────────────────────────────
    private void loadPairedDevices() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) return;
        Set<BluetoothDevice> paired = btAdapter.getBondedDevices();
        deviceNames.clear();
        deviceList.clear();
        for (BluetoothDevice d : paired) {
            deviceNames.add(d.getName() + "\n" + d.getAddress());
            deviceList.add(d);
        }
        deviceAdapter.notifyDataSetChanged();
        tvStatus.setText("기기를 선택하세요 (HC-05)");
    }

    private void connectToDevice(BluetoothDevice device) {
        tvStatus.setText("연결 중...");
        new Thread(() -> {
            try {
                if (ActivityCompat.checkSelfPermission(this,
                        Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                    return;
                btSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                btSocket.connect();
                connected = true;
                uiHandler.post(() -> {
                    tvStatus.setText("연결됨: " + device.getName());
                    btnConnect.setText("연결 끊기");
                });
                startReading();
            } catch (IOException e) {
                Log.e(TAG, "연결 실패", e);
                uiHandler.post(() -> tvStatus.setText("연결 실패 — 다시 시도하세요"));
            }
        }).start();
    }

    private void startReading() {
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(btSocket.getInputStream()))) {
                String line;
                while (connected && (line = reader.readLine()) != null) {
                    String raw = line.trim();
                    if (raw.isEmpty()) continue;

                    String smart = buildSmartMessage(raw);
                    Log.d(TAG, "원본: " + raw + " → 변환: " + smart);

                    uiHandler.post(() -> {
                        tvMessage.setText(smart);
                        speakThrottled(smart);
                    });
                }
            } catch (IOException e) {
                if (connected) uiHandler.post(() -> tvStatus.setText("연결 끊김"));
            }
            connected = false;
        }).start();
    }

    // 아두이노 메시지의 "장애물"을 AI 인식 결과로 교체
    private String buildSmartMessage(String arduinoMsg) {
        String obj = latestObject.get();
        if (arduinoMsg.contains("장애물")) {
            return arduinoMsg.replace("장애물", obj + "가");
        }
        if (arduinoMsg.contains("이내")) {
            return arduinoMsg + " (" + obj + ")";
        }
        return arduinoMsg;
    }

    private void speakThrottled(String text) {
        long now = System.currentTimeMillis();
        if (now - lastSpeakMs < MIN_INTERVAL_MS) return;
        lastSpeakMs = now;
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    private void disconnect() {
        connected = false;
        try { if (btSocket != null) btSocket.close(); } catch (IOException ignored) {}
        tvStatus.setText("연결 해제됨");
        btnConnect.setText("장치 목록 새로고침");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        espRunning = false;
        if (espThread != null) espThread.quitSafely();
        disconnect();
        if (tts    != null) { tts.stop(); tts.shutdown(); }
        if (labeler != null) labeler.close();
    }
}
