/*
 * AI 지팡이 — ESP32-CAM 카메라 서버
 *
 * 역할: 지팡이에 부착된 카메라로 사진을 찍어
 *       WiFi로 스마트폰 앱에 전송
 *
 * 하드웨어: AI-Thinker ESP32-CAM 모듈
 *   - 내장 OV2640 카메라 (2MP)
 *   - WiFi 2.4GHz
 *   - 크기: 27mm × 40mm
 *   - 가격: 약 8,000~12,000원
 *
 * 동작:
 *   1. ESP32-CAM이 WiFi 핫스팟(AP)을 만듦
 *   2. 폰이 그 WiFi에 접속
 *   3. 앱이 http://192.168.4.1/capture 호출 → JPEG 이미지 받음
 *   4. 앱에서 ML Kit로 물체 인식 → TTS 출력
 *
 * 업로드 방법:
 *   Arduino IDE → 보드: "AI Thinker ESP32-CAM"
 *   업로드 시 GPIO0을 GND에 연결 → 업로드 후 분리
 */

#include "esp_camera.h"
#include <WiFi.h>
#include "esp_http_server.h"

// ── WiFi 핫스팟 설정 ─────────────────────────────────────
#define WIFI_SSID "BlindCane_Cam"
#define WIFI_PASS "blindcane123"

// ── AI-Thinker ESP32-CAM 핀 맵 (변경 금지) ───────────────
#define PWDN_GPIO_NUM   32
#define RESET_GPIO_NUM  -1
#define XCLK_GPIO_NUM    0
#define SIOD_GPIO_NUM   26
#define SIOC_GPIO_NUM   27
#define Y9_GPIO_NUM     35
#define Y8_GPIO_NUM     34
#define Y7_GPIO_NUM     39
#define Y6_GPIO_NUM     36
#define Y5_GPIO_NUM     21
#define Y4_GPIO_NUM     19
#define Y3_GPIO_NUM     18
#define Y2_GPIO_NUM      5
#define VSYNC_GPIO_NUM  25
#define HREF_GPIO_NUM   23
#define PCLK_GPIO_NUM   22

// ── 내장 LED (플래시) ─────────────────────────────────────
#define LED_PIN          4

httpd_handle_t server = NULL;

// ── /capture 요청 처리 → 현재 프레임 JPEG 반환 ────────────
static esp_err_t capture_handler(httpd_req_t *req) {
  camera_fb_t *fb = esp_camera_fb_get();
  if (!fb) {
    httpd_resp_send_500(req);
    return ESP_FAIL;
  }

  httpd_resp_set_type(req, "image/jpeg");
  httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
  esp_err_t res = httpd_resp_send(req, (const char *)fb->buf, fb->len);
  esp_camera_fb_return(fb);
  return res;
}

// ── /status 요청 처리 → 간단한 상태 확인용 ────────────────
static esp_err_t status_handler(httpd_req_t *req) {
  httpd_resp_set_type(req, "text/plain");
  httpd_resp_sendstr(req, "OK");
  return ESP_OK;
}

// ── HTTP 서버 시작 ────────────────────────────────────────
void startHTTPServer() {
  httpd_config_t config = HTTPD_DEFAULT_CONFIG();
  config.server_port = 80;

  httpd_uri_t capture_uri = {
    .uri     = "/capture",
    .method  = HTTP_GET,
    .handler = capture_handler,
    .user_ctx = NULL
  };
  httpd_uri_t status_uri = {
    .uri     = "/status",
    .method  = HTTP_GET,
    .handler = status_handler,
    .user_ctx = NULL
  };

  if (httpd_start(&server, &config) == ESP_OK) {
    httpd_register_uri_handler(server, &capture_uri);
    httpd_register_uri_handler(server, &status_uri);
    Serial.println("[HTTP] 서버 시작: http://192.168.4.1/capture");
  }
}

// ── 카메라 초기화 ─────────────────────────────────────────
bool initCamera() {
  camera_config_t config;
  config.ledc_channel  = LEDC_CHANNEL_0;
  config.ledc_timer    = LEDC_TIMER_0;
  config.pin_d0        = Y2_GPIO_NUM;
  config.pin_d1        = Y3_GPIO_NUM;
  config.pin_d2        = Y4_GPIO_NUM;
  config.pin_d3        = Y5_GPIO_NUM;
  config.pin_d4        = Y6_GPIO_NUM;
  config.pin_d5        = Y7_GPIO_NUM;
  config.pin_d6        = Y8_GPIO_NUM;
  config.pin_d7        = Y9_GPIO_NUM;
  config.pin_xclk      = XCLK_GPIO_NUM;
  config.pin_pclk      = PCLK_GPIO_NUM;
  config.pin_vsync     = VSYNC_GPIO_NUM;
  config.pin_href      = HREF_GPIO_NUM;
  config.pin_sscb_sda  = SIOD_GPIO_NUM;
  config.pin_sscb_scl  = SIOC_GPIO_NUM;
  config.pin_pwdn      = PWDN_GPIO_NUM;
  config.pin_reset     = RESET_GPIO_NUM;
  config.xclk_freq_hz  = 20000000;
  config.pixel_format  = PIXFORMAT_JPEG;
  config.frame_size    = FRAMESIZE_VGA; // 640×480 (품질 vs 속도 균형)
  config.jpeg_quality  = 12;            // 낮을수록 고화질 (10~15 권장)
  config.fb_count      = 2;

  esp_err_t err = esp_camera_init(&config);
  if (err != ESP_OK) {
    Serial.printf("[CAM] 초기화 실패: 0x%x\n", err);
    return false;
  }

  // 이미지 밝기/대비 조정 (실내 환경 최적화)
  sensor_t *s = esp_camera_sensor_get();
  s->set_brightness(s, 1);   // -2~2
  s->set_contrast(s, 1);     // -2~2
  s->set_saturation(s, 0);
  s->set_whitebal(s, 1);     // 자동 화이트밸런스
  s->set_exposure_ctrl(s, 1);// 자동 노출

  Serial.println("[CAM] 초기화 완료");
  return true;
}

// ─────────────────────────────────────────────────────────
void setup() {
  Serial.begin(115200);
  Serial.println("\n[BOOT] AI 지팡이 카메라 모듈 시작");

  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LOW); // 플래시 OFF

  // 카메라 초기화
  if (!initCamera()) {
    Serial.println("[ERROR] 카메라 실패 — 재시작합니다");
    delay(2000);
    ESP.restart();
  }

  // WiFi 핫스팟(AP) 시작 — 폰이 여기 접속
  WiFi.softAP(WIFI_SSID, WIFI_PASS);
  IPAddress ip = WiFi.softAPIP();
  Serial.printf("[WiFi] SSID: %s  PW: %s\n", WIFI_SSID, WIFI_PASS);
  Serial.printf("[WiFi] AP IP: %s\n", ip.toString().c_str());

  startHTTPServer();

  // 준비 완료 — LED 2번 깜빡
  for (int i = 0; i < 2; i++) {
    digitalWrite(LED_PIN, HIGH); delay(150);
    digitalWrite(LED_PIN, LOW);  delay(150);
  }
  Serial.println("[READY] 폰에서 WiFi 'BlindCane_Cam' 접속 후 앱 실행");
}

void loop() {
  delay(10);
}
