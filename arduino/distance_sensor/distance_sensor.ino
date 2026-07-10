/*
 * 시각 장애인용 AI 지팡이 - 거리 감지 모듈
 *
 * 하드웨어:
 *   - Arduino Uno
 *   - HC-SR04 초음파 센서
 *   - HC-05 블루투스 모듈 (이어폰 연결된 폰으로 TTS 전송)
 *   - 부저 (선택, 가까울수록 빠른 비프음)
 *
 * 핀 연결:
 *   HC-SR04  TRIG → D9
 *   HC-SR04  ECHO → D10
 *   HC-05    TX   → D2  (SoftwareSerial RX)
 *   HC-05    RX   → D3  (SoftwareSerial TX) ← 1kΩ 저항 직렬 연결
 *   부저          → D8  (선택)
 */

#include <SoftwareSerial.h>

// ── 핀 설정 ──────────────────────────────────────────────
#define TRIG_PIN  9
#define ECHO_PIN  10
#define BT_RX     2
#define BT_TX     3
#define BUZZER    8

// ── 거리 구간 (cm) ───────────────────────────────────────
#define ZONE_DANGER  50    // 50cm 이하 - 위험
#define ZONE_NEAR   100    // 1m 이하 - 주의
#define ZONE_MID    200    // 2m 이하 - 인식
#define ZONE_FAR    300    // 3m 이하 - 알림

// ── 전송 주기 (ms) ───────────────────────────────────────
#define SEND_INTERVAL_DANGER  300
#define SEND_INTERVAL_NEAR    700
#define SEND_INTERVAL_MID    1500
#define SEND_INTERVAL_FAR    3000
#define SEND_INTERVAL_CLEAR  5000

SoftwareSerial bluetooth(BT_RX, BT_TX);

unsigned long lastSendTime = 0;
int          lastZone      = -1;   // 구간 변경 시에만 즉시 알림
unsigned long lastBuzzTime = 0;
bool          buzzState    = false;

// ── 거리 측정 ─────────────────────────────────────────────
long measureDistanceCm() {
  digitalWrite(TRIG_PIN, LOW);
  delayMicroseconds(2);
  digitalWrite(TRIG_PIN, HIGH);
  delayMicroseconds(10);
  digitalWrite(TRIG_PIN, LOW);

  long duration = pulseIn(ECHO_PIN, HIGH, 30000); // 최대 30ms 대기 (~5m)
  if (duration == 0) return 999; // 범위 초과
  return duration * 17 / 1000;  // cm 환산 (duration/2 * 0.0343)
}

// ── 거리 → 구간 번호 ──────────────────────────────────────
int getZone(long dist) {
  if (dist <= ZONE_DANGER) return 0;
  if (dist <= ZONE_NEAR)   return 1;
  if (dist <= ZONE_MID)    return 2;
  if (dist <= ZONE_FAR)    return 3;
  return 4; // 안전
}

// ── 구간별 전송 주기 ──────────────────────────────────────
unsigned long intervalForZone(int zone) {
  switch (zone) {
    case 0: return SEND_INTERVAL_DANGER;
    case 1: return SEND_INTERVAL_NEAR;
    case 2: return SEND_INTERVAL_MID;
    case 3: return SEND_INTERVAL_FAR;
    default: return SEND_INTERVAL_CLEAR;
  }
}

// ── 블루투스로 메시지 전송 ────────────────────────────────
//    앱에서 이 문자열을 TTS로 읽음
void sendAlert(long dist, int zone) {
  String msg;
  switch (zone) {
    case 0: msg = "위험! 50센티미터 이내 장애물"; break;
    case 1: msg = String(dist) + "센티미터 앞 장애물"; break;
    case 2: msg = "1." + String((dist - 100) / 10) + "미터 앞 장애물"; break;
    case 3: msg = String(dist / 100) + "." + String((dist % 100) / 10) + "미터 앞 장애물"; break;
    case 4: msg = ""; break; // 안전 구간은 전송 안 함 (주기적 확인만)
  }
  if (msg.length() > 0) {
    bluetooth.println(msg);
    Serial.println("[BT] " + msg); // 디버그용 시리얼 모니터
  }
}

// ── 부저: 구간별 비프음 패턴 ─────────────────────────────
void updateBuzzer(int zone, unsigned long now) {
  if (zone >= 4) {
    digitalWrite(BUZZER, LOW);
    return;
  }

  // 구간별 ON/OFF 주기 (ms)
  unsigned long period[4] = {150, 400, 800, 1500};
  unsigned long onTime[4] = { 80, 150, 200,  300};

  unsigned long p = period[zone];
  unsigned long t = (now - lastBuzzTime) % p;

  if (t < onTime[zone]) {
    if (!buzzState) { digitalWrite(BUZZER, HIGH); buzzState = true; }
  } else {
    if (buzzState)  { digitalWrite(BUZZER, LOW);  buzzState = false; }
  }
}

// ─────────────────────────────────────────────────────────
void setup() {
  pinMode(TRIG_PIN, OUTPUT);
  pinMode(ECHO_PIN, INPUT);
  pinMode(BUZZER,   OUTPUT);

  Serial.begin(9600);
  bluetooth.begin(9600); // HC-05 기본 보레이트

  Serial.println("시각장애인 AI 지팡이 시작");
  lastBuzzTime = millis();
}

void loop() {
  unsigned long now  = millis();
  long          dist = measureDistanceCm();
  int           zone = getZone(dist);

  // 구간이 바뀌면 즉시 전송
  bool zoneChanged = (zone != lastZone);
  if (zoneChanged) {
    lastZone = zone;
    sendAlert(dist, zone);
    lastSendTime = now;
  } else {
    // 같은 구간이면 주기마다 전송
    if (now - lastSendTime >= intervalForZone(zone)) {
      sendAlert(dist, zone);
      lastSendTime = now;
    }
  }

  updateBuzzer(zone, now);

  // 디버그: 시리얼 모니터에 거리 출력
  if (now % 500 < 50) {
    Serial.print("거리: ");
    Serial.print(dist);
    Serial.print("cm  구간: ");
    Serial.println(zone);
  }

  delay(50); // 센서 안정화
}
