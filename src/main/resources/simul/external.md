# 외부 요소 목록

시나리오의 둘째 칸에 들어갈 것. 여기서 고른다. 없는 것을 새로 쓰려면 "앱 프로세스
밖에서 일어나는가"를 먼저 확인한다.

각 항목에 유발 방법과 layout에서 확인하는 방법을 적었다. `Run "..."`은 adb shell 스텝이다.

## 시스템 UI (별도 윈도우로 layout에 찍힌다)

| 요소 | 유발 | 확인 |
|---|---|---|
| PiP 창 | 재생 중 `Press home` | `type: APPLICATION`이 아닌 PiP 윈도우 존재, 앱 패키지 |
| 미디어 알림 | 재생 중 `Press home` → `Open notifications` | 알림 셰이드에 재생/일시정지 컨트롤 |
| 푸시 알림 | 테스트 푸시 발송 또는 `Run "cmd notification post ..."` | 셰이드의 알림 → 탭 → 도착 화면 |
| 권한 다이얼로그 | 신규 설치 첫 실행, 다운로드 첫 시도 | `com.android.permissioncontroller` 윈도우 |
| Google Play 결제 시트 | 구독/개별 구매 버튼 | `com.android.vending` 윈도우 전면 |
| 공유 시트 | 앱의 공유 버튼 | 시스템 chooser 윈도우에 타겟 존재 |
| 화면 분할 | `Run "am start --windowingMode 3 ..."` 또는 최근 앱에서 | 두 APPLICATION 윈도우, 앱 bounds 절반 |

## 설정 앱 (유저가 실제로 바꾸는 것. 설정 앱 UI를 거친다)

| 요소 | 경로 | 앱에서 확인할 것 |
|---|---|---|
| PiP 권한 | 설정 → 앱 → 왓챠 → PiP | 꺼진 뒤 홈 이동 시 PiP 없음, 앱 내 토글 상태 |
| 알림 권한 | 설정 → 앱 → 왓챠 → 알림 | 앱 내 알림 설정 화면의 토글 상태 |
| 글꼴 크기 / 디스플레이 크기 최대 | 설정 → 디스플레이 | 하단 탭·버튼 bounds 겹침·잘림 없음 |
| 다크 모드 | 설정 → 디스플레이 | 전환 중 플레이어가 재시작되지 않음 |
| 언어 | 설정 → 시스템 → 언어 | 재시작 없이 탭 라벨 변경, region별 탭 유무 |
| 저장공간: 캐시 삭제 / 데이터 삭제 | 설정 → 앱 → 왓챠 → 저장공간 | 캐시 삭제 후 로그인 유지, 다운로드 유지 |
| 데이터 절약 / 배터리 세이버 | 설정 → 네트워크 / 배터리 | 재생 품질 설정 반영 |
| 기본 브라우저 / 링크 열기 | 설정 → 앱 → 왓챠 → 기본으로 열기 | App Links 해제 시 URL이 브라우저로 |

## 다른 앱

| 요소 | 유발 | 확인 |
|---|---|---|
| Chrome에서 URL | Chrome 열어 watcha URL 입력 | 브라우저가 아니라 앱이 전면 |
| 다른 앱의 공유 | 갤러리/메모 공유 → 왓챠 | 앱 도착 화면 |
| 오디오 포커스 | YouTube 재생, 시계 알람 울림 | 왓챠 재생이 멈춤, 포커스 반환 후 상태 |
| 전화 수신 | `Run "emu gsm call 5551234"` → 끊기 `Run "emu gsm cancel 5551234"` | 일시정지 → 재개 여부 |

## 기기 이벤트 (adb)

| 요소 | 명령 | 확인 |
|---|---|---|
| 프로세스 종료 | `Press home` → `Run "am kill <pkg>"` → 복귀 | 같은 화면, 재생 위치, 입력 중 검색어 |
| 회전 | `Run "settings put system user_rotation 1"` | 플레이어 전체화면, 복귀 시 스크롤 위치 |
| 이어폰 버튼 | `Run "input keyevent KEYCODE_MEDIA_PAUSE"` | 일시정지 |
| 이어폰 분리 | `Run "emu ..."` 미지원 시 생략 | 일시정지 |
| 비행기 모드 | `Run "cmd connectivity airplane-mode enable"` | 오프라인 화면, 다운로드 탭 접근 가능, 해제 후 복구 |
| 메모리 압박 | `Run "am send-trim-memory <pkg> RUNNING_CRITICAL"` | 화면 유지 |
| 토큰 만료 | 스냅샷의 토큰을 손상시킨 appState | 로그인 화면으로 유도, 로그인 후 원래 목적지 |
| 시간 변경 | `Run "date ..."` | 구독 만료 표시 (기기 시간 의존 로직만) |

## 하네스 지원 여부

위 스텝 중 `Press home`, `Open notifications`, `Run "..."`, 앱 윈도우 밖 앵커,
`setup.device`(시작 조건 선언과 종료 시 원복)는 하네스가 지원해야 쓸 수 있다.
지원되지 않는 항목은 시나리오를 쓰지 말고 "하네스에 X 필요"로 보고한다.
