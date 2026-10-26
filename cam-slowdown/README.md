# 카메라 감속 (cam-slowdown)

FSD 주행 중 과속 카메라에 가까워지면 S3XY 커맨더로 **오른쪽 휠 아래**(속도 프로필 낮춤)를 보내고, 지나가면 **휠 위**(원래 프로필)로 되돌리는 안드로이드 앱.

## 동작 원리

```
GPS(1초) ─▶ 앞쪽 카메라 판별 ─▶ 감속거리 안? ─▶ ▼ 클릭 N번 (휠 아래)
                                   통과+100m ─▶ ▲ 더블클릭 M번 (휠 위)
```

- **FSD v14에서는 휠이 최고속도가 아니라 속도 프로필**(Sloth → Chill → Standard → Hurry → Mad Max)을 바꾼다. Sloth는 제한속도를 지키므로 "Sloth로 내렸다가 원래 프로필로 올리기"가 사실상 속도 조절이다.
- 기본값: 감속 때 휠 아래 **4번**(어느 프로필이든 Sloth까지), 복귀 때 휠 위 **3번**(Sloth → Hurry). 평소 쓰는 프로필에 맞춰 설정에서 바꾼다(Standard면 2).
- 감속 시작 거리 = (현재속도² − 제한속도²) / (2 × 0.7) + 4초 주행거리 + 150m. 110km/h → 100km/h 카메라면 약 350m 전.
- 반대 차로 카메라는 "진행 방향 왼쪽 12m 밖"이면 무시한다(우측통행 기준).
- 구간단속: 시점에서 내리고, 구간 길이(데이터에 있으면) 또는 종점 카메라를 지나고 100m 후 올린다.
- 연속 카메라: 통과 직후 다음 카메라가 이미 감속거리 안이면 올리지 않고 이어간다.

## 설치

1. 폰에서 [최신 빌드 받기](https://github.com/jsbeth-code/yoonmind/releases/tag/cam-slowdown-latest) → `cam-slowdown.apk` 받아 설치 (출처를 알 수 없는 앱 허용 필요).
2. 처음 실행 때 위치·블루투스·알림 권한 허용.
3. **카메라 데이터**: [공공데이터포털에서 '무인교통단속카메라' 검색](https://www.data.go.kr/tcs/dss/selectDataSetList.do?keyword=%EB%AC%B4%EC%9D%B8%EA%B5%90%ED%86%B5%EB%8B%A8%EC%86%8D%EC%B9%B4%EB%A9%94%EB%9D%BC) → 「전국무인교통단속카메라표준데이터」 CSV 다운로드 → 앱에서 **카메라 데이터 불러오기**.

## 단계별 진행

### 1단계 — 안내 전용으로 검증 (차는 건드리지 않음)
- 설정에서 "가상 S3XY 버튼 신호"를 끈 상태(기본)로 **주행 시작**.
- 카메라 앞에서 "과속 카메라, 제한속도 OO. 감속합니다", 지나서 "복귀합니다"가 적절한 타이밍에 나오는지 며칠 확인.
- 너무 이르거나 늦으면 설정의 감속도·여유거리 조정. 고속도로에서만 쓰려면 "제한속도 미만 무시"를 80으로.

### 2단계 — S3XY 버튼 신호 캡처
버튼이 커맨더로 보내는 블루투스 신호는 공개되어 있지 않아서 직접 기록해야 한다.

1. 차에서 떨어진 곳(커맨더와 연결 안 되게)에서 버튼을 폰에 붙여 둔다.
2. 앱 **버튼 신호 캡처** → 스캔 시작.
3. [클릭 표시] 누르고 버튼 1번 클릭, 몇 초 쉬고 반복 3~5번. [더블 표시] + 더블클릭도 3~5번.
4. **공유**로 결과를 Claude에게 보낸다 → 표시 직후에 바뀌는 패킷을 찾아 BLE 프로필(JSON)을 만들어 준다.

캡처에 아무것도 안 잡히면(버튼이 광고 대신 커맨더와 직접 연결해서 통신하는 방식이면) 스니퍼가 필요하다.
- [nRF Connect (Play 스토어)](https://play.google.com/store/apps/details?id=no.nordicsemi.android.mcp) — 버튼에 직접 연결해 서비스·특성(GATT) 목록 확인
- [nRF Sniffer for Bluetooth LE](https://www.nordicsemi.com/Products/Development-tools/nRF-Sniffer-for-Bluetooth-LE) + [nRF52840 Dongle](https://www.nordicsemi.com/Products/Development-hardware/nRF52840-Dongle)(약 2만 원) + Wireshark — 버튼↔커맨더 통신을 공중에서 기록
- 일반적인 방법론: [Reverse Engineering BLE Devices](https://reverse-engineering-ble-devices.readthedocs.io/)

### 3단계 — 커맨더 설정
enhauto 앱에서 새 버튼(폰)을 추가하고, 원글처럼 **클릭 = 오른쪽 휠 아래 / 더블클릭 = 오른쪽 휠 위** 로 지정.

### 4단계 — 연동
설정에 BLE 프로필 붙여넣기 → "가상 S3XY 버튼 신호" 켜기 → 주차 상태에서 **테스트: 감속/복귀 신호**로 화면의 프로필이 바뀌는지 확인 → **가상 주행 테스트** → 실제 주행.

## 한계·주의
- 카메라 방향 정보가 데이터에 없어서 위치로 추정한다. 고가도로·병행도로·급커브에서 오판할 수 있다.
- 지도상 제한속도와 카메라 제한속도가 다르면 Sloth도 지도 기준으로 달린다.
- 앱을 감속 상태에서 정지하면 자동 복귀하지 않는다(안전 쪽). 휠로 직접 올릴 것.
- 운전 책임은 운전자에게 있다. FSD(Supervised)는 감독 하에 쓰는 기능이다.

## 개발
- 판단 로직: `app/src/main/java/com/jsbeth/camslow/SlowdownEngine.kt` (안드로이드 의존성 없음, `SlowdownEngineTest`로 검증)
- 빌드: `.github/workflows/cam-slowdown.yml` — `cam-slowdown/` 변경을 푸시하면 테스트 → APK → 위 릴리스에 자동 게시
