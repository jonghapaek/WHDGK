package com.cane.blindcane;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "BlindCane";
    private static final UUID   SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // ── 영어 → 한국어 물체 이름 번역표 ─────────────────────────────────
    private static final Map<String, String> KOREAN = new LinkedHashMap<>();
    static {
        // 사람
        KOREAN.put("Person",       "사람");
        KOREAN.put("Man",          "남성");
        KOREAN.put("Woman",        "여성");
        KOREAN.put("Child",        "어린이");
        // 탈 것
        KOREAN.put("Car",          "자동차");
        KOREAN.put("Truck",        "트럭");
        KOREAN.put("Bus",          "버스");
        KOREAN.put("Bicycle",      "자전거");
        KOREAN.put("Motorcycle",   "오토바이");
        KOREAN.put("Vehicle",      "차량");
        // 동물
        KOREAN.put("Dog",          "개");
        KOREAN.put("Cat",          "고양이");
        // 가구/실내
        KOREAN.put("Chair",        "의자");
        KOREAN.put("Table",        "테이블");
        KOREAN.put("Desk",         "책상");
        KOREAN.put("Bench",        "벤치");
        KOREAN.put("Sofa",         "소파");
        KOREAN.put("Furniture",    "가구");
        // 구조물
        KOREAN.put("Door",         "문");
        KOREAN.put("Wall",         "벽");
        KOREAN.put("Stairs",       "계단");
        KOREAN.put("Pole",         "기둥");
        KOREAN.put("Column",       "기둥");
        KOREAN.put("Fence",        "울타리");
        KOREAN.put("Sign",         "표지판");
        KOREAN.put("Building",     "건물");
        // 자연
        KOREAN.put("Tree",         "나무");
        KOREAN.put("Plant",        "식물");
        // 기타
        KOREAN.put("Trash can",    "쓰레기통");
        KOREAN.put("Box",          "박스");
        KOREAN.put("Bag",          "가방");
        KOREAN.put("Bottle",       "병");
        KOREAN.put("Bicycle rack", "자전거 거치대");
    }

    // ── 높은 우선순위 물체 (사람·차 등 위험도 높은 것 먼저) ─────────────
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
    private ExecutorService    cameraExecutor;

    // 카메라 스레드 ↔ BT 스레드 간 공유
    private final AtomicReference<String> latestObject = new AtomicReference<>("장애물");
    private volatile boolean connected  = false;
    private long lastSpeakMs = 0;
    private static final long MIN_INTERVAL_MS = 800;

    // UI
    private TextView  tvStatus, tvMessage, tvObject, tvConfidence;
    private ListView  lvDevices;
    private Button    btnConnect;
    private PreviewView previewView;

    private final ArrayList<String>        deviceNames  = new ArrayList<>();
    private final ArrayList<BluetoothDevice> deviceList = new ArrayList<>();
    private ArrayAdapter<String>           deviceAdapter;

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
        lvDevices    = findViewById(R.id.lvDevices);
        btnConnect   = findViewById(R.id.btnConnect);
        previewView  = findViewById(R.id.previewView);

        // TTS 초기화 (한국어)
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS)
                tts.setLanguage(new Locale("ko", "KR"));
        });

        // ML Kit 이미지 라벨러 (신뢰도 60% 이상만 사용)
        labeler = ImageLabeling.getClient(
            new ImageLabelerOptions.Builder().setConfidenceThreshold(0.60f).build()
        );

        cameraExecutor = Executors.newSingleThreadExecutor();

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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.CAMERA);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            needed.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        if (needed.isEmpty()) {
            startCamera();
            loadPairedDevices();
        } else {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        startCamera();
        loadPairedDevices();
    }

    // ── 카메라 + ML Kit ──────────────────────────────────────────────────
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();

                // 미리보기
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                // 이미지 분석 (최신 프레임만 유지)
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();

                analysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    @SuppressLint("UnsafeOptInUsageError")
                    android.media.Image mediaImage = imageProxy.getImage();
                    if (mediaImage == null) { imageProxy.close(); return; }

                    InputImage img = InputImage.fromMediaImage(
                        mediaImage, imageProxy.getImageInfo().getRotationDegrees());

                    labeler.process(img)
                        .addOnSuccessListener(this::onLabelsDetected)
                        .addOnCompleteListener(t -> imageProxy.close());
                });

                provider.unbindAll();
                provider.bindToLifecycle(
                    this,
                    new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK).build(),
                    preview, analysis
                );

            } catch (Exception e) {
                Log.e(TAG, "카메라 오류", e);
            }
        }, ContextCompat.getMainExecutor(this));
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

    // 우선순위 기반으로 가장 적합한 한국어 라벨 선택
    private String pickBestLabel(List<ImageLabel> labels) {
        // 1단계: 우선순위 목록에서 검색
        for (String key : PRIORITY) {
            for (ImageLabel lbl : labels) {
                if (lbl.getText().toLowerCase().contains(key.toLowerCase())
                        && lbl.getConfidence() >= 0.55f) {
                    return KOREAN.getOrDefault(key, key);
                }
            }
        }
        // 2단계: 신뢰도 1위 라벨 번역 시도
        String top = labels.get(0).getText();
        for (Map.Entry<String, String> e : KOREAN.entrySet()) {
            if (top.toLowerCase().contains(e.getKey().toLowerCase()))
                return e.getValue();
        }
        // 3단계: 번역 없으면 "장애물"
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

                    // "장애물" → AI가 감지한 실제 물체로 교체
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
        String obj = latestObject.get(); // 최신 AI 인식 결과
        if (arduinoMsg.contains("장애물")) {
            return arduinoMsg.replace("장애물", obj + "가");
        }
        // "위험!" 메시지는 뒤에 물체 추가
        if (arduinoMsg.contains("이내")) {
            return arduinoMsg + " (" + obj + ")";
        }
        return arduinoMsg;
    }

    // 너무 빠른 반복 발화 방지
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
        disconnect();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        cameraExecutor.shutdown();
        if (labeler != null) labeler.close();
    }
}
