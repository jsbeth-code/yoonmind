# FSD 감속 버튼 보드 (1단계 시험용)

일반 ESP32 보드(ESP32-WROOM-32, 예: "ESP32 DEVKIT V1")를 S3XY 버튼("ENH_BTN")으로 동작시킨다.
폰은 블루투스 주소가 바뀌어 커맨더가 차를 다시 켤 때 못 찾는 것으로 보여(2026-11-11 실차, 추정),
주소가 고정인 보드로 **차를 껐다 켜도 커맨더가 저절로 다시 붙는지** 시험하는 것이 1단계의 목적이다.
폰 앱과의 연결(2단계)은 아직 없다.

## 프로그램 넣기 (PC, 크롬 또는 엣지)
1. 받기: https://github.com/jsbeth-code/yoonmind/releases/download/cam-slowdown-board-latest/cam-slowdown-board.bin
2. 보드를 **데이터 되는 USB A to C 케이블**로 PC에 연결(C to C 케이블은 이 보드에서 안 됨).
3. Espressif 공식 웹 도구 https://espressif.github.io/esptool-js/ 를 연다.
4. Baudrate 921600 그대로 → [Connect] → 뜨는 창에서 보드 포트(USB Serial / CH340 / CP210x 등) 선택.
   - 연결이 안 되면 보드의 BOOT 버튼을 누른 채 [Connect], 연결되면 뗀다.
   - 포트가 안 보이면 PC에 USB 드라이버(CH340 또는 CP210x — 보드의 USB 옆 칩 이름)가 필요할 수 있다.
5. Flash Address **0x0**, 파일에 `cam-slowdown-board.bin` 선택 → [Program]. 1분쯤.
6. 끝나면 보드의 EN(RST) 버튼을 한 번 누른다. 파란 불이 2초마다 짧게 깜빡이면 정상(커맨더 기다리는 중).

## 커맨더에 등록
1. 차에서 FSD 감속 앱의 커맨더 사용을 잠시 끈다(폰도 ENH_BTN이라 헷갈리지 않게).
2. 보드를 차 USB에 꽂는다(차 단자가 C뿐이면 C→A 변환 젠더 + A to C 케이블, 또는 시거잭 USB-A 충전기).
3. 엔하우토 앱 → Buttons → Add S3XY Button. 보드를 등록하고 클릭 = 크루징 속도 내림, 더블클릭 = 올림으로 지정.
4. 파란 불이 **계속 켜져 있으면** 커맨더 연결·준비 완료.

## 보드 조작
| 조작 | 동작 |
|---|---|
| BOOT 짧게 | 클릭(감속 시험) |
| BOOT 1초 넘게 | 더블클릭(복귀 시험) |
| BOOT 누른 채 전원 넣고 5초 | 저장된 페어링 모두 지움(불이 빠르게 깜빡인 뒤) |

| 파란 불 | 뜻 |
|---|---|
| 계속 켜짐 | 커맨더 연결·준비 |
| 빠르게 깜빡 | 접속은 됐지만 준비 전 |
| 2초마다 짧게 | 커맨더 기다리는 중 |

## 핵심 시험
차를 끄고 내려 잠들게 둔 뒤(1시간 이상) 다시 탄다. 파란 불이 저절로 계속 켜지면(커맨더가 다시 붙으면) 성공.
며칠 몇 번 반복. 보드도 안 붙으면 원인은 주소가 아니다 — 멈추고 엔하우토에 문의.

## 기록 보기(필요할 때)
PC에 연결하고 https://espressif.github.io/esptool-js/ 의 Console 탭에서 115200으로 [Start]하면 동작 기록이 나온다.

## 프로토콜
앱(cam-slowdown/.../S3xyButton.kt)과 같다. 원래 출처: Beat-YT/s3xy-virtual-button (MIT, Gen2 커맨더 확인).
