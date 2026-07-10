package com.cane.blindcane;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "BlindCane";
    // SPP(Serial Port Profile) UUID - HC-05 표준값
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private BluetoothAdapter btAdapter;
    private BluetoothSocket  btSocket;
    private TextToSpeech     tts;
    private Handler          uiHandler;

    private TextView         tvStatus;
    private TextView         tvLastMsg;
    private ListView         lvDevices;
    private Button           btnConnect;

    private ArrayList<String>       deviceNames = new ArrayList<>();
    private ArrayList<BluetoothDevice> deviceList = new ArrayList<>();
    private ArrayAdapter<String>    deviceAdapter;

    private volatile boolean connected = false;
    private Thread readerThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        uiHandler = new Handler(Looper.getMainLooper());
        tvStatus  = findViewById(R.id.tvStatus);
        tvLastMsg = findViewById(R.id.tvLastMsg);
        lvDevices = findViewById(R.id.lvDevices);
        btnConnect= findViewById(R.id.btnConnect);

        // TTS 초기화 (한국어)
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(new Locale("ko", "KR"));
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "한국어 TTS 지원 안됨");
                }
            }
        });

        // 블루투스 어댑터 초기화
        btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter == null) {
            toast("이 기기는 블루투스를 지원하지 않습니다");
            return;
        }

        // 페어링된 장치 목록 불러오기
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceNames);
        lvDevices.setAdapter(deviceAdapter);
        loadPairedDevices();

        lvDevices.setOnItemClickListener((parent, view, pos, id) -> {
            connectToDevice(deviceList.get(pos));
        });

        btnConnect.setOnClickListener(v -> {
            if (connected) disconnect();
            else loadPairedDevices();
        });
    }

    // 페어링된 블루투스 기기 목록 로드
    private void loadPairedDevices() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.BLUETOOTH_CONNECT,
                             Manifest.permission.BLUETOOTH_SCAN}, 1);
            return;
        }
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

    // HC-05에 연결
    private void connectToDevice(BluetoothDevice device) {
        tvStatus.setText("연결 중: " + device.getName() + "...");
        new Thread(() -> {
            try {
                if (ActivityCompat.checkSelfPermission(this,
                        Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
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
                uiHandler.post(() -> {
                    tvStatus.setText("연결 실패 - 다시 시도하세요");
                    toast("연결 실패: " + e.getMessage());
                });
            }
        }).start();
    }

    // 아두이노에서 오는 메시지 읽기 (백그라운드 스레드)
    private void startReading() {
        readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(btSocket.getInputStream()))) {
                String line;
                while (connected && (line = reader.readLine()) != null) {
                    final String msg = line.trim();
                    if (msg.isEmpty()) continue;
                    Log.d(TAG, "수신: " + msg);
                    uiHandler.post(() -> {
                        tvLastMsg.setText(msg);
                        speak(msg);
                    });
                }
            } catch (IOException e) {
                if (connected) {
                    Log.e(TAG, "읽기 오류", e);
                    uiHandler.post(() -> tvStatus.setText("연결 끊김"));
                }
            }
            connected = false;
        });
        readerThread.start();
    }

    // TTS 음성 출력 (이전 말 중단 후 즉시)
    private void speak(String text) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    private void disconnect() {
        connected = false;
        try { if (btSocket != null) btSocket.close(); } catch (IOException ignored) {}
        tvStatus.setText("연결 해제됨");
        btnConnect.setText("장치 목록 새로고침");
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnect();
        if (tts != null) { tts.stop(); tts.shutdown(); }
    }
}
