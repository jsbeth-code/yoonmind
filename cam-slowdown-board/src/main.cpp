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
//  - BOOT 3초 넘게 누름 = 페어링 창 1분(파란 불 두 번씩 깜빡). 새 기기(커맨더·폰)는 이때만 페어링된다(창 밖 새 페어링은 지우고 끊음).
//    커맨더는 엔하우토 Add S3XY Button, 폰은 앱 설정 ⑥ [보드 페어링]
//  - BOOT 10초 넘게 누름 = 저장된 페어링 모두 지우고 다시 시작(커맨더·폰 모두)
//  - 암호화(페어링된 키) 안 된 접속은 커맨더로 보지 않고 30초 뒤 끊는다
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
struct PhoneCmd { uint16_t conn; uint8_t len; uint8_t b[20]; };

struct Conn {
  bool used;
  uint16_t id;
  uint8_t addr[6];
  bool phone;       // 폰 서비스에 써 온 접속
  bool authed;      // 토큰이 맞은 폰
  bool subscribed;  // 커맨더 알림 구독(재접속 때는 저장된 구독을 살림)
  bool confirmed;   // 버튼 ID 특성을 쓰거나 구독을 쓴 접속 = 커맨더 확정
  bool encrypted;   // 페어링된 키로 암호화됨 — 암호화 안 된 접속은 커맨더로 보지 않는다(낯선 기기)
  bool restore;     // 암호화되면 저장된 구독을 살림
  int8_t pendingSub;  // 암호화 전에 온 구독 쓰기(-1 없음)
  bool kicked;
  bool windowAtConnect;  // 접속할 때 페어링 창이 열려 있었음(새 페어링을 받아도 됨)
  bool authOk;      // 보안 완료(AUTH_CMPL)를 받아들임 — 폰 명령은 이 뒤에만 처리
  bool hasPend;     // 보안 완료 전에 온 폰 명령(하나 보관)
  PhoneCmd pend;
  uint8_t encRetries;  // 암호화 다시 요청한 횟수(동시 접속 때 보안 요청이 겹쳐 빠지는 경우)
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
static QueueHandle_t phoneCmds;
static QueueHandle_t kickQ;   // 끊을 접속 id
static int knownBonds = 0;    // 받아들인 페어링 수 — 창 밖에서 늘면 낯선 기기의 새 페어링이라 지운다
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

// 받아들인 페어링 주소(창 밖 새 페어링 판별용). 수만 보면 낯선 기기 페어링이 지워지기 전 커맨더 재암호화를 새 페어링으로 오인한다.
static const int MAX_KNOWN = 16;
static uint8_t knownAddrs[MAX_KNOWN][6];
static int knownN = 0;
static void refreshKnown() {
  int n = esp_ble_get_bond_device_num();
  if (n > MAX_KNOWN) n = MAX_KNOWN;
  knownN = 0;
  if (n <= 0) return;
  esp_ble_bond_dev_t* list = (esp_ble_bond_dev_t*)malloc(sizeof(esp_ble_bond_dev_t) * n);
  if (!list) return;
  esp_ble_get_bond_device_list(&n, list);
  for (int i = 0; i < n && i < MAX_KNOWN; i++) memcpy(knownAddrs[i], list[i].bd_addr, 6);
  knownN = n < MAX_KNOWN ? n : MAX_KNOWN;
  free(list);
}
static bool wasKnown(const uint8_t* a) {
  for (int i = 0; i < knownN; i++) if (memcmp(knownAddrs[i], a, 6) == 0) return true;
  return false;
}

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
    if (!c.encrypted) continue;
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
  knownBonds = 0; knownN = 0;
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

/** 접속이 암호화됐다고 표시(connMux 안에서 부른다). 커맨더면 미뤄 둔 구독·저장된 구독을 적용. 결과: pending(-1/0/1), restored. */
static void markEncrypted(Conn* c, int* pending, bool* restored) {
  if (c->encrypted) return;
  c->encrypted = true;
  if (c->phone) return;
  if (c->pendingSub >= 0) { *pending = c->pendingSub; c->subscribed = *pending == 1; c->confirmed = true; c->pendingSub = -1; }
  else if (c->restore) { c->subscribed = true; *restored = true; }
}

static void afterEncrypted(int pending, bool restored) {
  if (pending >= 0) prefs.putBool("sub", pending == 1);
  if (restored) cccd->setNotifications(true);
  connChanged = true;
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
      c.subscribed = false; c.restore = restore; c.pendingSub = -1; c.at = millis();
      c.windowAtConnect = pairWindowOpen();
      break;
    }
    portEXIT_CRITICAL(&connMux);
    connChanged = true;
    logf("접속 %s id=%d (페어링 %d개, 접속 %d개) — 암호화 기다림", addrStr(p->connect.remote_bda).c_str(), p->connect.conn_id, bondCount(), connCount());
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
    bool phone = false, enc = false;
    portENTER_CRITICAL(&connMux);
    Conn* c = findConn(param->write.conn_id);
    if (c) {
      phone = c->phone; enc = c->encrypted;
      if (!phone && enc) { c->subscribed = on; c->confirmed = true; }
      else if (!phone) c->pendingSub = on ? 1 : 0;
    }
    portEXIT_CRITICAL(&connMux);
    if (phone || !c) return;
    if (!enc) { logf("구독 쓰기 id=%d — 암호화 뒤 적용", param->write.conn_id); return; }
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
    int pend = -1; bool rest = false;
    // 이 특성은 암호화가 필요하다 — 쓰기가 왔다는 것 자체가 암호화된 접속이라는 증거(보안 완료 이벤트가 빠진 경우 대비)
    if (cc && !cc->phone) { markEncrypted(cc, &pend, &rest); cc->confirmed = true; }
    portEXIT_CRITICAL(&connMux);
    if (pend >= 0 || rest) afterEncrypted(pend, rest);
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
      xQueueSend(kickQ, &conn, 0);
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
    if (cc) {
      cc->phone = true; cc->subscribed = false;
      // 이미 페어링돼 있던 주소면(지울 대상이 아님) 보안 완료 이벤트가 빠져도 명령 특성 쓰기(암호화 필수)로 충분하다 — 시동 직후 동시 접속 경합 대비
      if (!cc->authOk && wasKnown(cc->addr)) { cc->authOk = true; cc->encrypted = true; }
    }
    portEXIT_CRITICAL(&connMux);
    connChanged = true;
    xQueueSend(phoneCmds, &m, 0);
  }
};

class SecCb : public BLESecurityCallbacks {
  uint32_t onPassKeyRequest() override { return 0; }
  void onPassKeyNotify(uint32_t) override {}
  bool onConfirmPIN(uint32_t) override { return true; }
  // 새 기기의 페어링(본딩) 요청: 페어링 창(BOOT 3초)이 열려 있을 때만 받는다. 이미 페어링된 커맨더·폰은 저장된 키로 암호화돼 여기를 거치지 않는다.
  bool onSecurityRequest() override {
    bool ok = pairWindowOpen();
    logf(ok ? "새 기기 페어링 요청 — 받음(페어링 창)" : "새 기기 페어링 요청 — 거절(보드 BOOT 3초로 페어링 창을 먼저 여세요)");
    return ok;
  }
  void onAuthenticationComplete(esp_ble_auth_cmpl_t cmpl) override {
    if (!cmpl.success) { logf("페어링·암호화 실패 %s reason=0x%02X", addrStr(cmpl.bd_addr).c_str(), cmpl.fail_reason); return; }
    // 보드가 접속마다 먼저 보안 요청을 보내므로 스택은 낯선 기기와도 Just Works 페어링을 끝낸다(위 onSecurityRequest를 안 거침).
    // 페어링 수가 늘었는데 창이 닫혀 있으면 그 새 페어링을 지우고 끊는다.
    int nb = bondCount();
    // 창 판정은 접속할 때 기준(토큰을 주며 창을 닫은 뒤 보안 완료가 와도 방금 페어링한 폰을 지우지 않게 — 실차 2026-11-13)
    // 주소가 같은 접속이 있으면 그 접속의 기준만, 없으면(주소가 바뀌는 기기) 보안 완료 전인 접속 중 하나라도
    bool windowOk = pairWindowOpen();
    portENTER_CRITICAL(&connMux);
    int match = -1;
    for (int i = 0; i < MAX_CONN; i++) if (conns[i].used && !conns[i].authOk && memcmp(conns[i].addr, cmpl.bd_addr, 6) == 0) match = i;
    if (match >= 0) { if (conns[match].windowAtConnect) windowOk = true; }
    else for (int i = 0; i < MAX_CONN; i++) if (conns[i].used && !conns[i].authOk && conns[i].windowAtConnect) windowOk = true;
    portEXIT_CRITICAL(&connMux);
    if (nb > knownBonds && !wasKnown(cmpl.bd_addr) && !windowOk) {
      esp_ble_remove_bond_device(cmpl.bd_addr);
      uint16_t kid = 0xFFFF;
      portENTER_CRITICAL(&connMux);
      for (int i = 0; i < MAX_CONN; i++) if (conns[i].used && !conns[i].encrypted && memcmp(conns[i].addr, cmpl.bd_addr, 6) == 0) { conns[i].kicked = true; kid = conns[i].id; }
      portEXIT_CRITICAL(&connMux);
      if (kid != 0xFFFF) xQueueSend(kickQ, &kid, 0);
      logf("페어링 창 밖의 새 기기 %s — 페어링 지우고 끊음(등록하려면 BOOT 3초 먼저)", addrStr(cmpl.bd_addr).c_str());
      return;
    }
    if (nb != knownBonds || !wasKnown(cmpl.bd_addr)) { knownBonds = nb; refreshKnown(); }
    // 어느 접속인지: 주소가 같은 접속, 없으면(주소가 바뀌는 기기) 아직 암호화 안 된 가장 최근 접속
    int pending = -1; bool restored = false; uint16_t id = 0;
    portENTER_CRITICAL(&connMux);
    Conn* c = nullptr;
    for (int i = 0; i < MAX_CONN; i++) if (conns[i].used && !conns[i].encrypted && memcmp(conns[i].addr, cmpl.bd_addr, 6) == 0) c = &conns[i];
    if (!c) for (int i = 0; i < MAX_CONN; i++) if (conns[i].used && !conns[i].encrypted && (!c || conns[i].at > c->at)) c = &conns[i];
    if (c) { id = c->id; c->authOk = true; markEncrypted(c, &pending, &restored); }
    portEXIT_CRITICAL(&connMux);
    afterEncrypted(pending, restored);
    logf("암호화 됨 %s id=%d (페어링 %d개)%s", addrStr(cmpl.bd_addr).c_str(), id, bondCount(),
         restored ? " — 이전 구독 유지" : pending == 1 ? " — 구독 적용, 버튼 준비" : "");
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
  if (!c || c->kicked) return;
  // 보안 완료(창 밖 새 페어링이면 지우고 끊음) 전엔 처리하지 않고 보관한다 — 낯선 기기가 지워지기 전에 명령을 넣지 못하게
  if (!c->authOk) { c->pend = m; c->hasPend = true; logf("폰 명령 %02X 보관 id=%d — 보안 완료 기다림", m.b[0], m.conn); return; }
  uint8_t op = m.b[0];
  if (op == 0x01 && m.len == 17) {
    bool ok = hasToken && memcmp(token, m.b + 1, 16) == 0;
    c->authed = ok;
    uint8_t r[2] = {0x81, (uint8_t)(ok ? 1 : 0)};
    toPhone(m.conn, r, 2);
    logf(ok ? "폰 인증됨 id=%d" : "폰 인증 실패 id=%d — 페어링한 폰이 아님(BOOT 3초 후 앱에서 [보드 페어링])", m.conn);
    if (ok) { uint8_t s[2] = {0x83, commanderState()}; toPhone(m.conn, s, 2); }
  } else if (op == 0x02 && m.len == 1) {
    // 방금 페어링한 같은 폰이 요청을 다시 보내면(승인 뒤 안드로이드 재전송 + 앱 재시도) 같은 토큰을 다시 준다
    if (c->authed && hasToken) {
      uint8_t r[17]; r[0] = 0x82; memcpy(r + 1, token, 16);
      toPhone(m.conn, r, 17);
      logf("같은 폰의 페어링 요청 다시 옴 id=%d — 같은 토큰 다시 보냄", m.conn);
      return;
    }
    // 여기까지 온 접속은 보안 완료를 받아들인 기기다(창 밖 새 페어링은 그 전에 지우고 끊음) = 창에서 페어링해 둔 기기.
    // 그러니 토큰 요청은 창과 무관하게 받는다. 창은 토큰을 줘도 닫지 않는다(1분 지나면 닫힘).
    // (실차 2026-11-13: 토큰을 주며 창을 닫은 직후 보안 완료가 와서 방금 페어링한 폰을 "창 밖 새 페어링"으로 지웠다 — Fable 점검, 가능성 높음)
    logf("폰 페어링 요청 id=%d (페어링 창 %s)", m.conn, pairWindowOpen() ? "열림" : "닫힘");
    for (int i = 0; i < 16; i += 4) { uint32_t x = esp_random(); memcpy(token + i, &x, 4); }
    prefs.putBytes("tok", token, 16);
    hasToken = true;
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
  kickQ = xQueueCreate(8, sizeof(uint16_t));
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

  knownBonds = bondCount();
  refreshKnown();
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
  uint16_t dc;
  while (xQueueReceive(kickQ, &dc, 0) == pdTRUE) server->disconnect(dc);

  // 폰 명령
  PhoneCmd m;
  while (xQueueReceive(phoneCmds, &m, 0) == pdTRUE) handlePhone(m);
  for (int i = 0; i < MAX_CONN; i++) {
    Conn& c = conns[i];
    if (c.used && c.hasPend && c.authOk && !c.kicked) { c.hasPend = false; PhoneCmd pm = c.pend; handlePhone(pm); }
  }

  // 커맨더 상태가 바뀌면 폰에 알린다
  static uint8_t lastState = 255;
  uint8_t st = commanderState();
  if (connChanged || st != lastState) {
    connChanged = false;
    if (st != lastState) logf(st == 2 ? "커맨더 준비" : st == 1 ? "커맨더 접속 · 준비 전" : "커맨더 없음");
    lastState = st;
    sendStateToPhones();
  }

  if (pairWindowUntil != 0 && !pairWindowOpen()) { pairWindowUntil = 0; logf("페어링 창 닫힘(1분)"); }

  // 30초 넘게 암호화 안 된 접속(페어링 안 된 낯선 기기)은 끊는다 — 접속 자리를 차지하지 않게
  for (int i = 0; i < MAX_CONN; i++) {
    Conn& c = conns[i];
    // 동시에 붙으면 보안 요청이 겹쳐 하나가 빠질 수 있다 — 5초·15초에 다시 요청
    if (c.used && !c.encrypted && !c.kicked && c.encRetries < 2 && millis() - c.at > (c.encRetries == 0 ? 5000UL : 15000UL)) {
      c.encRetries++;
      logf("접속 id=%d 암호화 다시 요청(%d)", c.id, c.encRetries);
      esp_ble_set_encryption(c.addr, ESP_BLE_SEC_ENCRYPT);
    }
    if (c.used && !c.encrypted && !c.kicked && millis() - c.at > 30000) {
      c.kicked = true;
      logf("암호화 안 된 접속 id=%d 끊음(페어링 안 된 기기)", c.id);
      server->disconnect(c.id);
    }
  }

  // BOOT 버튼: 1초 미만 = 클릭, 1~3초 = 더블클릭, 3초 넘게 = 폰 페어링 창
  static bool wasDown = false;
  static bool longFired = false;
  static unsigned long downAt = 0;
  bool down = digitalRead(BOOT_PIN) == LOW;
  if (down && !wasDown) { downAt = millis(); longFired = false; }
  if (down && longFired && millis() - downAt >= 10000) {
    // 10초 = 페어링 모두 지우고 다시 시작
    for (int i = 0; i < 10; i++) { digitalWrite(LED_PIN, i % 2); delay(80); }
    eraseBonds();
    logf("다시 시작");
    delay(200);
    ESP.restart();
  }
  if (down && !longFired && millis() - downAt >= 3000) {
    longFired = true;
    pairWindowUntil = millis() + PAIR_WINDOW_MS;
    if (pairWindowUntil == 0) pairWindowUntil = 1;
    logf("페어링 창 열림(1분) — 엔하우토 Add S3XY Button 또는 앱 설정 ⑥ [보드 페어링]. 10초까지 누르고 있으면 페어링 모두 지움");
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
