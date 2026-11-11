// FSD 감속 — 커맨더용 BLE 버튼 보드 (1단계 시험용)
//
// 일반 ESP32 보드를 S3XY 버튼("ENH_BTN")처럼 동작시킨다. 폰과 달리 보드의 블루투스 주소는 고정이라,
// 차를 껐다 켜도 커맨더가 같은 버튼으로 다시 찾아 붙는지 시험하는 것이 목적이다.
//
// 프로토콜(UUID·바이트)은 앱(S3xyButton.kt)과 같고, 원래 출처는 Beat-YT/s3xy-virtual-button (MIT, Gen2 커맨더 확인):
//  - 서비스 3d46, 알림 특성 3d50(READ|NOTIFY + CCCD), ID 특성 3d49(READ|WRITE, 암호화 필요, 10바이트 ID)
//  - 커맨더 → ID 쓰기: B6 = 핸드셰이크(응답 C7 00 01), A1 = 연결 해제(페어링 삭제) 요청, A4 xx yy zz = 이름표(응답 A4 00 xx yy)
//  - 버튼 → 알림: 클릭 = 01, 00, C1 01, 00 / 더블클릭 = C1 02 / 길게 = C3 01
//
// 보드 조작:
//  - BOOT 버튼 짧게 = 클릭(감속 시험), 1초 넘게 누름 = 더블클릭(복귀 시험)
//  - BOOT 버튼을 누른 채 전원을 넣고 5초 = 저장된 페어링 모두 지움
//  - 파란 불: 켜져 있음 = 커맨더 연결·준비 / 빠르게 깜빡 = 접속은 됐지만 준비 전 / 2초마다 짧게 = 커맨더 기다리는 중
//  - USB 시리얼(115200)에 동작 기록

#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <BLESecurity.h>
#include <Preferences.h>
#include <esp_gap_ble_api.h>
#include <freertos/queue.h>

static const char* SERVICE_UUID = "00003d46-87d2-479e-7e45-8551415a6de1";
static const char* NOTIFY_UUID = "00003d50-87d2-479e-7e45-8551415a6de1";
static const char* ID_UUID = "00003d49-87d2-479e-7e45-8551415a6de1";
static const char* NAME = "ENH_BTN";
static uint8_t BUTTON_ID[10] = {'C', 'A', 'M', 'S', 'L', 'O', 'W', 'B', 'R', 'D'};

static const int LED_PIN = 2;   // DevKit V1 파란 불
static const int BOOT_PIN = 0;  // BOOT 버튼(누르면 LOW)

static BLEServer* server = nullptr;
static BLECharacteristic* notifyChar = nullptr;
static BLECharacteristic* idChar = nullptr;
static BLE2902* cccd = nullptr;
static Preferences prefs;

static volatile bool connected = false;
static volatile bool subscribed = false;
static volatile uint16_t connId = 0;
static volatile bool advertisingNow = false;

// 콜백(블루투스 작업)에서 바로 알림을 보내지 않고 loop에서 보낸다.
struct Reply { uint8_t len; uint8_t b[4]; };
static QueueHandle_t replies;
static volatile bool disconnectRequested = false;

static void logf(const char* fmt, ...) {
  char buf[200];
  va_list ap; va_start(ap, fmt); vsnprintf(buf, sizeof(buf), fmt, ap); va_end(ap);
  Serial.printf("[%8lu] %s\n", (unsigned long)millis(), buf);
}

static String addrStr(const uint8_t* a) {
  char s[18];
  snprintf(s, sizeof(s), "%02X:%02X:%02X:%02X:%02X:%02X", a[0], a[1], a[2], a[3], a[4], a[5]);
  return String(s);
}

static int bondCount() { return esp_ble_get_bond_device_num(); }

static void eraseBonds() {
  int n = esp_ble_get_bond_device_num();
  if (n > 0) {
    esp_ble_bond_dev_t* list = (esp_ble_bond_dev_t*)malloc(sizeof(esp_ble_bond_dev_t) * n);
    if (list) {
      esp_ble_get_bond_device_list(&n, list);
      for (int i = 0; i < n; i++) esp_ble_remove_bond_device(list[i].bd_addr);
      free(list);
    }
  }
  prefs.putBool("sub", false);
  logf("저장된 페어링 %d개 지움", n);
}

static void startAdv() {
  BLEDevice::startAdvertising();
  advertisingNow = true;
  logf("ENH_BTN 광고 중 — 커맨더 기다림 (페어링 %d개)", bondCount());
}

class ServerCb : public BLEServerCallbacks {
  void onConnect(BLEServer* s, esp_ble_gatts_cb_param_t* p) override {
    connected = true;
    advertisingNow = false;
    connId = p->connect.conn_id;
    // 실제 버튼처럼 접속하자마자 보안(본딩·암호화)을 요청한다. 이미 페어링된 커맨더면 저장된 키로 암호화된다.
    esp_ble_set_encryption(p->connect.remote_bda, ESP_BLE_SEC_ENCRYPT);
    // 본딩된 기기의 알림 구독은 다시 접속해도 유지되는 것이 BLE 규칙이라, 커맨더는 재접속 때 구독을 다시 쓰지 않는다.
    // → 전에 구독했으면 그대로 살린다(앱과 같은 방식).
    if (bondCount() > 0 && prefs.getBool("sub", false)) {
      cccd->setNotifications(true);
      subscribed = true;
    }
    logf("접속 %s (페어링 %d개)%s", addrStr(p->connect.remote_bda).c_str(), bondCount(),
         subscribed ? " — 이전 구독 유지, 버튼 준비" : "");
  }
  void onDisconnect(BLEServer* s) override {
    connected = false;
    subscribed = false;
    logf("연결 끊김");
    startAdv();
  }
};

class CccdCb : public BLEDescriptorCallbacks {
  void onWrite(BLEDescriptor* d) override {
    bool on = ((BLE2902*)d)->getNotifications();
    subscribed = on;
    prefs.putBool("sub", on);
    logf(on ? "알림 구독됨 — 버튼 준비" : "알림 구독 해제");
  }
};

class IdCb : public BLECharacteristicCallbacks {
  void onRead(BLECharacteristic* c) override { c->setValue(BUTTON_ID, sizeof(BUTTON_ID)); }
  void onWrite(BLECharacteristic* c) override {
    std::string v = c->getValue();
    c->setValue(BUTTON_ID, sizeof(BUTTON_ID));  // 쓴 값으로 바뀐 ID를 되돌린다
    if (v.empty()) return;
    String hex;
    for (size_t i = 0; i < v.size(); i++) { char h[4]; snprintf(h, sizeof(h), "%02X ", (uint8_t)v[i]); hex += h; }
    logf("커맨더 명령 %s", hex.c_str());
    uint8_t b0 = (uint8_t)v[0];
    Reply r{};
    if (v.size() == 1 && b0 == 0xB6) {
      r.len = 3; r.b[0] = 0xC7; r.b[1] = 0x00; r.b[2] = 0x01;
      xQueueSend(replies, &r, 0);
    } else if (v.size() == 1 && b0 == 0xA1) {
      logf("커맨더가 연결 해제(페어링 삭제) 요청");
      prefs.putBool("sub", false);
      disconnectRequested = true;
    } else if (v.size() == 4 && b0 == 0xA4) {
      r.len = 4; r.b[0] = 0xA4; r.b[1] = 0x00; r.b[2] = (uint8_t)v[1]; r.b[3] = (uint8_t)v[2];
      xQueueSend(replies, &r, 0);
    }
  }
};

class SecCb : public BLESecurityCallbacks {
  uint32_t onPassKeyRequest() override { return 0; }
  void onPassKeyNotify(uint32_t) override {}
  bool onConfirmPIN(uint32_t) override { return true; }
  bool onSecurityRequest() override { return true; }
  void onAuthenticationComplete(esp_ble_auth_cmpl_t cmpl) override {
    if (cmpl.success) logf("페어링·암호화 됨 %s (페어링 %d개)", addrStr(cmpl.bd_addr).c_str(), bondCount());
    else logf("페어링 실패 reason=0x%02X", cmpl.fail_reason);
  }
};

static bool notifyBytes(const uint8_t* b, size_t n) {
  if (!connected || !subscribed || !notifyChar) return false;
  notifyChar->setValue((uint8_t*)b, n);
  notifyChar->notify();
  return true;
}

static bool sendAll(std::initializer_list<std::initializer_list<uint8_t>> parts) {
  bool first = true;
  for (auto& p : parts) {
    if (!first) delay(5);
    first = false;
    uint8_t buf[4]; size_t n = 0;
    for (uint8_t x : p) buf[n++] = x;
    if (!notifyBytes(buf, n)) return false;
  }
  return true;
}

static void pressSingle() {
  bool ok = sendAll({{0x01}, {0x00}, {0xC1, 0x01}, {0x00}});
  logf(ok ? "클릭 보냄" : "클릭 못 보냄 — 커맨더 연결·준비 안 됨");
}

static void pressDouble() {
  bool ok = sendAll({{0xC1, 0x02}});
  logf(ok ? "더블클릭 보냄" : "더블클릭 못 보냄 — 커맨더 연결·준비 안 됨");
}

void setup() {
  Serial.begin(115200);
  pinMode(LED_PIN, OUTPUT);
  pinMode(BOOT_PIN, INPUT_PULLUP);
  delay(200);
  logf("FSD 감속 버튼 보드 시작 (1단계 시험용)");

  replies = xQueueCreate(8, sizeof(Reply));
  prefs.begin("s3xy", false);

  BLEDevice::init(NAME);
  BLEDevice::setSecurityCallbacks(new SecCb());
  BLESecurity* sec = new BLESecurity();
  sec->setAuthenticationMode(ESP_LE_AUTH_REQ_SC_BOND);
  sec->setCapability(ESP_IO_CAP_NONE);  // Just Works
  sec->setInitEncryptionKey(ESP_BLE_ENC_KEY_MASK | ESP_BLE_ID_KEY_MASK);
  sec->setRespEncryptionKey(ESP_BLE_ENC_KEY_MASK | ESP_BLE_ID_KEY_MASK);

  // BOOT를 누른 채 켜서 5초 유지하면 페어링을 모두 지운다
  if (digitalRead(BOOT_PIN) == LOW) {
    logf("BOOT 누름 — 5초 유지하면 페어링을 모두 지웁니다");
    unsigned long t0 = millis();
    while (digitalRead(BOOT_PIN) == LOW && millis() - t0 < 5000) {
      digitalWrite(LED_PIN, (millis() / 100) % 2);
      delay(10);
    }
    if (millis() - t0 >= 5000) {
      eraseBonds();
      for (int i = 0; i < 6; i++) { digitalWrite(LED_PIN, i % 2); delay(150); }
      while (digitalRead(BOOT_PIN) == LOW) delay(10);
    }
    digitalWrite(LED_PIN, LOW);
  }

  server = BLEDevice::createServer();
  server->setCallbacks(new ServerCb());
  BLEService* svc = server->createService(SERVICE_UUID);

  notifyChar = svc->createCharacteristic(NOTIFY_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  cccd = new BLE2902();
  cccd->setCallbacks(new CccdCb());
  notifyChar->addDescriptor(cccd);
  uint8_t zero = 0;
  notifyChar->setValue(&zero, 1);

  idChar = svc->createCharacteristic(ID_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE);
  idChar->setAccessPermissions(ESP_GATT_PERM_READ_ENCRYPTED | ESP_GATT_PERM_WRITE_ENCRYPTED);
  idChar->setCallbacks(new IdCb());
  idChar->setValue(BUTTON_ID, sizeof(BUTTON_ID));

  svc->start();

  BLEAdvertising* adv = BLEDevice::getAdvertising();
  BLEAdvertisementData advData;
  advData.setFlags(0x06);  // 일반 검색 가능, BR/EDR 없음
  advData.setName(NAME);
  BLEAdvertisementData scanResp;
  scanResp.setCompleteServices(BLEUUID(SERVICE_UUID));
  adv->setAdvertisementData(advData);
  adv->setScanResponseData(scanResp);
  adv->setMinInterval(0x20);  // 20 ms
  adv->setMaxInterval(0x40);  // 40 ms

  logf("보드 블루투스 주소 %s (고정)", BLEDevice::getAddress().toString().c_str());
  startAdv();
}

void loop() {
  // 커맨더 명령에 대한 응답
  Reply r;
  while (xQueueReceive(replies, &r, 0) == pdTRUE) {
    if (notifyBytes(r.b, r.len)) {
      if (r.b[0] == 0xC7) logf("핸드셰이크 응답 — 커맨더가 버튼으로 인식");
    } else {
      logf("응답 못 보냄(구독 전)");
    }
  }
  if (disconnectRequested) {
    disconnectRequested = false;
    if (connected) server->disconnect(connId);
  }

  // BOOT 버튼: 짧게 = 클릭, 1초 넘게 = 더블클릭
  static bool wasDown = false;
  static unsigned long downAt = 0;
  bool down = digitalRead(BOOT_PIN) == LOW;
  if (down && !wasDown) downAt = millis();
  if (!down && wasDown) {
    unsigned long held = millis() - downAt;
    if (held >= 1000) pressDouble();
    else if (held >= 30) pressSingle();
  }
  wasDown = down;

  // 파란 불
  unsigned long t = millis();
  bool led;
  if (connected && subscribed) led = true;
  else if (connected) led = (t / 150) % 2;
  else led = (t % 2000) < 80;
  digitalWrite(LED_PIN, led);

  delay(10);
}
