// FSD 감속 — 커맨더용 BLE 버튼 보드 (2단계: 폰 앱 경유)
//
// 일반 ESP32 보드를 S3XY 버튼("ENH_BTN")처럼 동작시킨다. 폰과 달리 보드의 블루투스 주소는 고정이라,
// 차를 껐다 켜도 커맨더가 같은 버튼으로 다시 찾아 붙는다(1단계 실차 2026-11 확인).
// 2단계: 폰 앱(FSD 감속)이 보드에 따로 접속해 "내림/올림 N번"을 보내면 보드가 커맨더에 누름을 전하고 결과를 돌려준다.
//
// 커맨더 쪽 프로토콜(UUID·바이트)은 앱(S3xyButton.kt)과 같고, 원래 출처는 Beat-YT/s3xy-virtual-button (MIT, Gen2 커맨더 확인):
//  - 서비스 3d46, 알림 특성 3d50(READ|NOTIFY + CCCD), ID 특성 3d49(READ|WRITE, 암호화 필요, 10바이트 ID)
//  - 커맨더 → ID 쓰기: B6 = 핸드셰이크(응답 C7 00 01), A1 = 연결 해제(페어링 삭제) 요청, A4 xx yy zz = 이름표(응답 A4 00 xx yy)
//  - 버튼 → 알림: 클릭 = 01, 00, C1 01, 00 / 더블클릭 = C1 02
//
// 폰 쪽 프로토콜(이 보드와 앱 BoardLink.kt만 씀): 서비스 c5a1f000-…, 명령 특성 c5a1f001(WRITE, 암호화 필요), 결과 특성 c5a1f002(NOTIFY)
//  폰 → 보드: 01 + 토큰16 = 인사(인증) / 02 = 페어링 요청(보드 페어링 창이 열려 있을 때만) / 03 = 상태 요청
//             10 종류(1 클릭·2 더블클릭) 횟수 간격ms(하위·상위) 번호 = 누름
//  보드 → 폰: 81 결과(1 됨·0 토큰 틀림) / 82 + 토큰16 = 페어링 됨, 82 = 거절(창 닫힘)
//             83 커맨더(0 없음·1 접속만·2 준비) / 90 번호 i N = i번째 누름 나감 / 91 번호 나간수 N 사유(0 다 나감·1 인증 안 됨·2 커맨더 준비 안 됨·3 잘못된 명령)
//  토큰은 페어링한 폰 하나만 가진다(새로 페어링하면 이전 폰은 못 씀).
//
// 보드 조작:
//  - BOOT 짧게(1초 미만) = 클릭(감속 시험), 1~3초 = 더블클릭(복귀 시험)
//  - BOOT 3초 넘게 누름 = 폰 페어링 창 1분(파란 불 두 번씩 깜빡). 이때 앱 설정 ⑥ [보드 페어링]
//  - BOOT 누른 채 전원 넣고 5초 = 저장된 페어링 모두 지움(커맨더·폰 모두)
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
#include <esp_gatts_api.h>
#include <freertos/queue.h>

static const char* SERVICE_UUID = "00003d46-87d2-479e-7e45-8551415a6de1";
static const char* NOTIFY_UUID = "00003d50-87d2-479e-7e45-8551415a6de1";
static const char* ID_UUID = "00003d49-87d2-479e-7e45-8551415a6de1";
static const char* PHONE_SERVICE_UUID = "c5a1f000-3b7e-4c2a-9f61-2d8e7b40a1c5";
static const char* PHONE_CMD_UUID = "c5a1f001-3b7e-4c2a-9f61-2d8e7b40a1c5";
static const char* PHONE_EVT_UUID = "c5a1f002-3b7e-4c2a-9f61-2d8e7b40a1c5";
static const char* NAME = "ENH_BTN";
static uint8_t BUTTON_ID[10] = {'C', 'A', 'M', 'S', 'L', 'O', 'W', 'B', 'R', 'D'};

static const int LED_PIN = 2;   // DevKit V1 파란 불
static const int BOOT_PIN = 0;  // BOOT 버튼(누르면 LOW)
static const unsigned long PAIR_WINDOW_MS = 60000;

static BLEServer* server = nullptr;
static BLECharacteristic* notifyChar = nullptr;
static BLECharacteristic* idChar = nullptr;
static BLECharacteristic* evtChar = nullptr;
static BLE2902* cccd = nullptr;
static BLE2902* evtCccd = nullptr;
static Preferences prefs;
static volatile esp_gatt_if_t gattsIf = ESP_GATT_IF_NONE;

// 접속 표(커맨더와 폰이 함께 붙는다). 블루투스 작업에서 바꾸고 loop에서 읽는다.
struct Conn {
  bool used;
  uint16_t id;
  uint8_t addr[6];
  bool phone;       // 폰 서비스에 써 온 접속
  bool authed;      // 토큰이 맞은 폰
  bool subscribed;  // 커맨더 알림 구독(재접속 때는 저장된 구독을 살림)
  bool confirmed;   // 버튼 ID 특성을 쓰거나 구독을 쓴 접속 = 커맨더 확정
  unsigned long at;
};
static const int MAX_CONN = 4;
static Conn conns[MAX_CONN];
static portMUX_TYPE connMux = portMUX_INITIALIZER_UNLOCKED;

static uint8_t token[16];
static bool hasToken = false;
static volatile unsigned long pairWindowUntil = 0;

// 콜백(블루투스 작업)에서 바로 보내지 않고 loop에서 처리한다.
struct Reply { uint16_t conn; uint8_t len; uint8_t b[4]; };
static QueueHandle_t replies;
struct PhoneCmd { uint16_t conn; uint8_t len; uint8_t b[20]; };
static QueueHandle_t phoneCmds;
static volatile int disconnectConn = -1;
static volatile bool connChanged = false;

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

static bool pairWindowOpen() { return pairWindowUntil != 0 && (long)(pairWindowUntil - millis()) > 0; }

static Conn* findConn(uint16_t id) {
  for (int i = 0; i < MAX_CONN; i++) if (conns[i].used && conns[i].id == id) return &conns[i];
  return nullptr;
}

/** 커맨더로 볼 접속: 폰이 아닌 접속 중 구독된 것(확정된 것 우선, 그다음 최근). 없으면 -1. */
static int commanderConn(bool* ready) {
  int best = -1; bool bestConfirmed = false; unsigned long bestAt = 0; bool anyOther = false;
  portENTER_CRITICAL(&connMux);
  for (int i = 0; i < MAX_CONN; i++) {
    Conn& c = conns[i];
    if (!c.used || c.phone) continue;
    anyOther = true;
    if (!c.subscribed) continue;
    if (best < 0 || (c.confirmed && !bestConfirmed) || (c.confirmed == bestConfirmed && c.at > bestAt)) {
      best = c.id; bestConfirmed = c.confirmed; bestAt = c.at;
    }
  }
  portEXIT_CRITICAL(&connMux);
  if (ready) *ready = best >= 0;
  if (best < 0 && anyOther) return -2;  // 접속은 있는데 준비 전
  return best;
}

/** 커맨더 상태: 0 없음, 1 접속만, 2 준비. */
static uint8_t commanderState() {
  bool r; int c = commanderConn(&r);
  return r ? 2 : (c == -2 ? 1 : 0);
}

static int connCount() {
  int n = 0;
  for (int i = 0; i < MAX_CONN; i++) if (conns[i].used) n++;
  return n;
}

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
  prefs.remove("tok");
  hasToken = false;
  logf("저장된 페어링 %d개 지움(커맨더·폰)", n);
}

static void startAdv() {
  // 커맨더가 붙어 있어도 폰이 접속할 수 있게 광고를 계속한다(접속 수가 남아 있을 때)
  if (connCount() >= 3) return;
  BLEDevice::startAdvertising();
}

static bool sendTo(uint16_t conn, BLECharacteristic* ch, const uint8_t* b, size_t n) {
  if (gattsIf == ESP_GATT_IF_NONE || !ch) return false;
  return esp_ble_gatts_send_indicate(gattsIf, conn, ch->getHandle(), n, (uint8_t*)b, false) == ESP_OK;
}

class ServerCb : public BLEServerCallbacks {
  void onConnect(BLEServer* s, esp_ble_gatts_cb_param_t* p) override {
    // 실제 버튼처럼 접속하자마자 보안(본딩·암호화)을 요청한다. 이미 페어링된 기기면 저장된 키로 암호화된다.
    esp_ble_set_encryption(p->connect.remote_bda, ESP_BLE_SEC_ENCRYPT);
    // 본딩된 기기의 알림 구독은 다시 접속해도 유지되는 것이 BLE 규칙이라, 커맨더는 재접속 때 구독을 다시 쓰지 않는다.
    // → 전에 구독했으면 그대로 살린다(폰이면 인사가 오는 순간 폰으로 바뀐다).
    bool restore = bondCount() > 0 && prefs.getBool("sub", false);
    portENTER_CRITICAL(&connMux);
    for (int i = 0; i < MAX_CONN; i++) {
      if (conns[i].used) continue;
      Conn& c = conns[i];
      c = Conn{};
      c.used = true; c.id = p->connect.conn_id; memcpy(c.addr, p->connect.remote_bda, 6);
      c.subscribed = restore; c.at = millis();
      break;
    }
    portEXIT_CRITICAL(&connMux);
    if (restore) cccd->setNotifications(true);
    connChanged = true;
    logf("접속 %s id=%d (페어링 %d개, 접속 %d개)%s", addrStr(p->connect.remote_bda).c_str(), p->connect.conn_id, bondCount(), connCount(),
         restore ? " — 이전 구독 유지" : "");
    startAdv();
  }
  void onDisconnect(BLEServer* s, esp_ble_gatts_cb_param_t* p) override {
    bool wasPhone = false;
    portENTER_CRITICAL(&connMux);
    Conn* c = findConn(p->disconnect.conn_id);
    if (c) { wasPhone = c->phone; c->used = false; }
    portEXIT_CRITICAL(&connMux);
    connChanged = true;
    logf("%s 연결 끊김 id=%d (남은 접속 %d개)", wasPhone ? "폰" : "커맨더(또는 다른 기기)", p->disconnect.conn_id, connCount());
    startAdv();
  }
};

// 커맨더 알림 구독(CCCD) 쓰기는 어느 접속이 썼는지가 BLE2902 콜백에 없어서 GATT 이벤트에서 직접 본다.
static void gattsHandler(esp_gatts_cb_event_t event, esp_gatt_if_t gif, esp_ble_gatts_cb_param_t* param) {
  if (event == ESP_GATTS_REG_EVT || event == ESP_GATTS_CONNECT_EVT) gattsIf = gif;
  // 폰 결과 특성의 구독을 쓴 접속 = 폰(커맨더로 보지 않음)
  if (event == ESP_GATTS_WRITE_EVT && evtCccd && param->write.handle == evtCccd->getHandle()) {
    portENTER_CRITICAL(&connMux);
    Conn* c = findConn(param->write.conn_id);
    if (c) { c->phone = true; c->subscribed = false; }
    portEXIT_CRITICAL(&connMux);
    connChanged = true;
    return;
  }
  if (event == ESP_GATTS_WRITE_EVT && cccd && param->write.handle == cccd->getHandle() && param->write.len >= 1) {
    bool on = (param->write.value[0] & 1) != 0;
    bool phone = false;
    portENTER_CRITICAL(&connMux);
    Conn* c = findConn(param->write.conn_id);
    if (c) { phone = c->phone; if (!phone) { c->subscribed = on; c->confirmed = true; } }
    portEXIT_CRITICAL(&connMux);
    if (phone) return;
    prefs.putBool("sub", on);
    connChanged = true;
    logf(on ? "알림 구독됨 id=%d — 버튼 준비" : "알림 구독 해제 id=%d", param->write.conn_id);
  }
}

class IdCb : public BLECharacteristicCallbacks {
  void onRead(BLECharacteristic* c, esp_ble_gatts_cb_param_t* p) override { c->setValue(BUTTON_ID, sizeof(BUTTON_ID)); }
  void onWrite(BLECharacteristic* c, esp_ble_gatts_cb_param_t* p) override {
    std::string v = c->getValue();
    c->setValue(BUTTON_ID, sizeof(BUTTON_ID));  // 쓴 값으로 바뀐 ID를 되돌린다
    if (v.empty()) return;
    uint16_t conn = p->write.conn_id;
    portENTER_CRITICAL(&connMux);
    Conn* cc = findConn(conn);
    if (cc && !cc->phone) cc->confirmed = true;
    portEXIT_CRITICAL(&connMux);
    String hex;
    for (size_t i = 0; i < v.size(); i++) { char h[4]; snprintf(h, sizeof(h), "%02X ", (uint8_t)v[i]); hex += h; }
    logf("커맨더 명령 %s(id=%d)", hex.c_str(), conn);
    uint8_t b0 = (uint8_t)v[0];
    Reply r{};
    r.conn = conn;
    if (v.size() == 1 && b0 == 0xB6) {
      r.len = 3; r.b[0] = 0xC7; r.b[1] = 0x00; r.b[2] = 0x01;
      xQueueSend(replies, &r, 0);
    } else if (v.size() == 1 && b0 == 0xA1) {
      logf("커맨더가 연결 해제(페어링 삭제) 요청");
      prefs.putBool("sub", false);
      disconnectConn = conn;
    } else if (v.size() == 4 && b0 == 0xA4) {
      r.len = 4; r.b[0] = 0xA4; r.b[1] = 0x00; r.b[2] = (uint8_t)v[1]; r.b[3] = (uint8_t)v[2];
      xQueueSend(replies, &r, 0);
    }
  }
};

class PhoneCmdCb : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c, esp_ble_gatts_cb_param_t* p) override {
    std::string v = c->getValue();
    if (v.empty() || v.size() > sizeof(PhoneCmd::b)) return;
    PhoneCmd m{};
    m.conn = p->write.conn_id; m.len = v.size();
    memcpy(m.b, v.data(), v.size());
    // 폰 서비스에 써 온 접속은 커맨더가 아니다
    portENTER_CRITICAL(&connMux);
    Conn* cc = findConn(m.conn);
    if (cc) { cc->phone = true; cc->subscribed = false; }
    portEXIT_CRITICAL(&connMux);
    xQueueSend(phoneCmds, &m, 0);
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

static bool notifyCommander(const uint8_t* b, size_t n) {
  bool ready; int conn = commanderConn(&ready);
  if (!ready) return false;
  return sendTo((uint16_t)conn, notifyChar, b, n);
}

static bool sendAll(std::initializer_list<std::initializer_list<uint8_t>> parts) {
  bool first = true;
  for (auto& p : parts) {
    if (!first) delay(5);
    first = false;
    uint8_t buf[4]; size_t n = 0;
    for (uint8_t x : p) buf[n++] = x;
    if (!notifyCommander(buf, n)) return false;
  }
  return true;
}

static bool pressSingle() { return sendAll({{0x01}, {0x00}, {0xC1, 0x01}, {0x00}}); }
static bool pressDouble() { return sendAll({{0xC1, 0x02}}); }

// ---- 폰 ----

static void toPhone(uint16_t conn, const uint8_t* b, size_t n) { sendTo(conn, evtChar, b, n); }

static void sendStateToPhones() {
  uint8_t st = commanderState();
  uint8_t b[2] = {0x83, st};
  for (int i = 0; i < MAX_CONN; i++) if (conns[i].used && conns[i].phone && conns[i].authed) toPhone(conns[i].id, b, 2);
}

static void handlePhone(const PhoneCmd& m) {
  Conn* c = findConn(m.conn);
  if (!c) return;
  uint8_t op = m.b[0];
  if (op == 0x01 && m.len == 17) {
    bool ok = hasToken && memcmp(token, m.b + 1, 16) == 0;
    c->authed = ok;
    uint8_t r[2] = {0x81, (uint8_t)(ok ? 1 : 0)};
    toPhone(m.conn, r, 2);
    logf(ok ? "폰 인증됨 id=%d" : "폰 인증 실패 id=%d — 페어링한 폰이 아님(BOOT 3초 후 앱에서 [보드 페어링])", m.conn);
    if (ok) { uint8_t s[2] = {0x83, commanderState()}; toPhone(m.conn, s, 2); }
  } else if (op == 0x02 && m.len == 1) {
    if (!pairWindowOpen()) {
      uint8_t r[1] = {0x82};
      toPhone(m.conn, r, 1);
      logf("폰 페어링 거절 — 보드 BOOT를 3초 넘게 눌러 페어링 창을 먼저 여세요");
      return;
    }
    for (int i = 0; i < 16; i += 4) { uint32_t x = esp_random(); memcpy(token + i, &x, 4); }
    prefs.putBytes("tok", token, 16);
    hasToken = true;
    pairWindowUntil = 0;
    // 이전에 인증된 다른 폰은 더 이상 못 쓴다
    for (int i = 0; i < MAX_CONN; i++) if (conns[i].used && conns[i].id != m.conn) conns[i].authed = false;
    c->authed = true;
    uint8_t r[17]; r[0] = 0x82; memcpy(r + 1, token, 16);
    toPhone(m.conn, r, 17);
    logf("폰 페어링 됨 id=%d — 이 폰만 신호를 보낼 수 있음", m.conn);
    uint8_t s[2] = {0x83, commanderState()}; toPhone(m.conn, s, 2);
  } else if (op == 0x03) {
    if (!c->authed) return;
    uint8_t s[2] = {0x83, commanderState()}; toPhone(m.conn, s, 2);
  } else if (op == 0x10 && m.len == 6) {
    uint8_t kind = m.b[1], n = m.b[2], seq = m.b[5];
    unsigned gap = m.b[3] | (m.b[4] << 8);
    uint8_t reason = 0, sent = 0;
    if (!c->authed) reason = 1;
    else if ((kind != 1 && kind != 2) || n == 0 || n > 20 || gap > 3000) reason = 3;
    else if (commanderState() != 2) reason = 2;
    else {
      for (uint8_t i = 0; i < n; i++) {
        bool ok = kind == 1 ? pressSingle() : pressDouble();
        if (!ok) { reason = 2; break; }
        sent = i + 1;
        uint8_t pr[4] = {0x90, seq, sent, n};
        toPhone(m.conn, pr, 4);
        if (sent < n) delay(gap);
      }
    }
    uint8_t r[5] = {0x91, seq, sent, n, reason};
    toPhone(m.conn, r, 5);
    logf("폰 요청 %s %d번 → %d번 나감%s", kind == 1 ? "클릭" : "더블클릭", n, sent,
         reason == 0 ? "" : reason == 1 ? " (인증 안 된 폰)" : reason == 2 ? " (커맨더 준비 안 됨)" : " (잘못된 명령)");
  }
}

void setup() {
  Serial.begin(115200);
  pinMode(LED_PIN, OUTPUT);
  pinMode(BOOT_PIN, INPUT_PULLUP);
  delay(200);
  logf("FSD 감속 버튼 보드 시작 (2단계: 폰 앱 경유)");

  replies = xQueueCreate(8, sizeof(Reply));
  phoneCmds = xQueueCreate(8, sizeof(PhoneCmd));
  prefs.begin("s3xy", false);
  hasToken = prefs.getBytes("tok", token, 16) == 16;

  BLEDevice::init(NAME);
  BLEDevice::setCustomGattsHandler(gattsHandler);
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
  notifyChar->addDescriptor(cccd);
  uint8_t zero = 0;
  notifyChar->setValue(&zero, 1);
  idChar = svc->createCharacteristic(ID_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE);
  idChar->setAccessPermissions(ESP_GATT_PERM_READ_ENCRYPTED | ESP_GATT_PERM_WRITE_ENCRYPTED);
  idChar->setCallbacks(new IdCb());
  idChar->setValue(BUTTON_ID, sizeof(BUTTON_ID));
  svc->start();

  BLEService* psvc = server->createService(PHONE_SERVICE_UUID);
  BLECharacteristic* cmdChar = psvc->createCharacteristic(PHONE_CMD_UUID, BLECharacteristic::PROPERTY_WRITE);
  cmdChar->setAccessPermissions(ESP_GATT_PERM_WRITE_ENCRYPTED);
  cmdChar->setCallbacks(new PhoneCmdCb());
  evtChar = psvc->createCharacteristic(PHONE_EVT_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  evtCccd = new BLE2902();
  evtChar->addDescriptor(evtCccd);
  psvc->start();

  // 광고는 1단계와 같게 둔다(커맨더가 알아보는 모양). 폰은 처음 페어링 때 이름 ENH_BTN으로 찾고 그 뒤로는 주소로 접속한다.
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

  logf("보드 블루투스 주소 %s (고정), 폰 페어링 %s", BLEDevice::getAddress().toString().c_str(), hasToken ? "있음" : "없음");
  startAdv();
  logf("ENH_BTN 광고 중 (페어링 %d개)", bondCount());
}

void loop() {
  // 커맨더 명령에 대한 응답(써 온 접속으로)
  Reply r;
  while (xQueueReceive(replies, &r, 0) == pdTRUE) {
    if (sendTo(r.conn, notifyChar, r.b, r.len)) {
      if (r.b[0] == 0xC7) logf("핸드셰이크 응답 — 커맨더가 버튼으로 인식");
    } else {
      logf("응답 못 보냄");
    }
  }
  int dc = disconnectConn;
  if (dc >= 0) { disconnectConn = -1; server->disconnect((uint16_t)dc); }

  // 폰 명령
  PhoneCmd m;
  while (xQueueReceive(phoneCmds, &m, 0) == pdTRUE) handlePhone(m);

  // 커맨더 상태가 바뀌면 폰에 알린다
  static uint8_t lastState = 255;
  uint8_t st = commanderState();
  if (connChanged || st != lastState) {
    connChanged = false;
    if (st != lastState) logf(st == 2 ? "커맨더 준비" : st == 1 ? "커맨더 접속 · 준비 전" : "커맨더 없음");
    lastState = st;
    sendStateToPhones();
  }

  if (pairWindowUntil != 0 && !pairWindowOpen()) { pairWindowUntil = 0; logf("폰 페어링 창 닫힘(1분)"); }

  // BOOT 버튼: 1초 미만 = 클릭, 1~3초 = 더블클릭, 3초 넘게 = 폰 페어링 창
  static bool wasDown = false;
  static bool longFired = false;
  static unsigned long downAt = 0;
  bool down = digitalRead(BOOT_PIN) == LOW;
  if (down && !wasDown) { downAt = millis(); longFired = false; }
  if (down && !longFired && millis() - downAt >= 3000) {
    longFired = true;
    pairWindowUntil = millis() + PAIR_WINDOW_MS;
    if (pairWindowUntil == 0) pairWindowUntil = 1;
    logf("폰 페어링 창 열림(1분) — 앱 설정 ⑥ [보드 페어링]");
  }
  if (!down && wasDown && !longFired) {
    unsigned long held = millis() - downAt;
    if (held >= 1000) logf(pressDouble() ? "더블클릭 보냄" : "더블클릭 못 보냄 — 커맨더 연결·준비 안 됨");
    else if (held >= 30) logf(pressSingle() ? "클릭 보냄" : "클릭 못 보냄 — 커맨더 연결·준비 안 됨");
  }
  wasDown = down;

  // 파란 불
  unsigned long t = millis();
  bool led;
  if (pairWindowOpen()) { unsigned long k = t % 1000; led = k < 100 || (k >= 200 && k < 300); }
  else if (st == 2) led = true;
  else if (st == 1) led = (t / 150) % 2;
  else led = (t % 2000) < 80;
  digitalWrite(LED_PIN, led);

  delay(10);
}
