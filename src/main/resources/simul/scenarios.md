# simul 시나리오 작성 레퍼런스

`simul`은 자연어로 쓴 UI 시나리오를 Android 에뮬레이터에서 실행하는 테스트 러너다.

- `--mode llm` — LLM 에이전트가 시나리오를 한 번 수행하며 trace를 녹화한다.
- `--mode replay` — CI가 그 trace를 LLM 없이 결정론적으로 재생한다.

시나리오는 사람이 읽는 테스트 케이스이자 에이전트가 실행하는 스펙이다. 좋은 시나리오를
쓰기 위해 알아야 할 것은 하나다: **에이전트가 화면을 어떻게 보는가.** 아래 규칙은 전부
거기서 따라 나온다.

---

## 1. 관찰 모델 — 에이전트가 보는 것

에이전트는 스크린샷을 보지 않는다. 매 스텝마다 `android layout`이 돌려주는 UI 요소
목록을 관찰한다 (`android layout --help` 참고). 요소 하나는 다음 속성을 가질 수 있다:

| 속성 | 의미 | 시나리오에서의 역할 |
|---|---|---|
| `text` | 요소가 표시하는 텍스트 | 따옴표 라벨의 매칭 대상 |
| `contentDesc` | 접근성 설명(`contentDescription`) | 따옴표 라벨의 매칭 대상 |
| `resourceId` | View id, Compose `testTag`(`testTagsAsResourceId` 설정 시), 웹뷰 DOM `id` | 따옴표 라벨의 매칭 대상. 표시 문구와 무관해 가장 안정적 |
| `interactions` | `clickable`, `checkable`, `focusable`, `scrollable`, `long-clickable`, `password` | 어떤 동작이 가능한지 |
| `state` | `checked`, `focused`, `selected` | 상태 검증의 근거 |
| `bounds`, `center` | 화면 좌표 | 에이전트 내부용. 시나리오에서 쓰지 않는다 |
| `off-screen` | 트리에는 있지만 화면 밖 | 스크롤이 필요하다는 신호 |

요소는 트리다. 에이전트는 자식을 부모 아래 들여쓰기해서 본다 — 카드의 제목·부제·버튼은 카드
아래에, 목록 항목은 목록 아래에 놓인다. 라벨 없는 탭·행이 텍스트 자식을 하나만 가지면 그
텍스트가 그 요소의 라벨이 된다(`Tap "감상하기"`는 버튼을 지목한다). 텍스트가 여럿인 카드는
라벨 없이 남고 텍스트들이 자식으로 보인다.

스텝의 따옴표 문자열은 `text`, `contentDesc`, `resourceId` 세 속성에 동등하게 매칭된다.
`Tap the "Settings" tab`과 `Tap the "tab_settings" tab`은 같은 방식으로 동작한다.

WebView 내부(debug 빌드)는 CDP로 읽어 같은 목록에 병합된다 — DOM `id`는 `resourceId`로,
`checked`/`aria-selected`/`aria-checked`는 `state`로. 시나리오는 네이티브와 웹을 구분하지
않는다.

### 보이지 않는 것

세 매칭 속성 중 아무것도 없는 요소는 좌표로만 존재하며 시나리오에서 지목할 수 없다.
자주 걸리는 경우:

- `contentDescription` 없는 아이콘 전용 버튼
- 색·이미지·위치 같은 시각 정보 — "빨간 버튼", "오른쪽 위 아이콘"은 동작하지 않는다
- `selected`/`checked`를 내보내지 않는 커스텀 탭·토글 — "선택됐는지"를 검증할 수 없다
- cross-origin iframe 내부

이런 문제를 시나리오에서 우회하려 하지 마라. 이웃 요소나 상대 위치로 돌아가면 다음
레이아웃 변경에서 깨진다. **앱이 정보를 노출하게 고치는 것이 답이다** — 테스트 가능성은
곧 접근성이다:

- 아이콘 전용 버튼에 `contentDescription`
- Compose: 루트에 `Modifier.semantics { testTagsAsResourceId = true }`, 핵심 요소에
  `Modifier.testTag("...")` — 문구가 바뀌어도 시나리오가 살아남는다
- 탭·토글류에 `selected`/`checked` 상태 노출
- 웹: 인터랙티브 요소에 `id`, 커스텀 탭/토글에 `aria-selected`/`aria-checked`

---

## 2. 무엇을 테스트할까

시나리오는 **유저 여정**을 테스트한다. 코드 분기·경계값·위젯 동작은 테스트하지 않는다.

> 유저가 앱을 탐색해 원하는 결과를 이루는가?

- **시나리오 하나 = 여정 하나.** 진입(유저가 앱을 켜는 이유) → 탐색(실제 유저 경로) →
  결과(그 이유가 충족됐는지 `Verify` 또는 `::` 기준으로 고정). 결과 확인이 없으면
  여정이 아니다.
- **여정 목록 = 유저가 이 앱을 켜는 이유 목록.** 가입·로그인, 콘텐츠 찾기, 재생, 결제,
  설정 변경. 막히면 앱을 못 쓰는 여정부터.
- **이유마다 가장 흔한 경로 하나.** 다른 경로는 유저가 실제로 많이 쓸 때만.
- **상태 variant는 결과가 달라질 때만.** 게스트가 보관함에 가면 로그인 유도 — 별도 시나리오.
  결과가 같으면 만들지 않는다.
- **엣지 케이스는 유닛 테스트로.** 위젯 동작은 여정에 등장할 때 그 안에서 검증된다.
- **라이브러리 vs 우리 코드 경계는 묻지 않는다.** 통합 경계(Compose ↔ Fragment, 네이티브 ↔
  WebView)는 핵심 여정이 한 번씩 지나가면 된다.

```
"보고 싶은 작품을 찾아 재생한다"
  런치 → 검색 탭 → 검색어 입력 → 결과 탭 → Verify 플레이어 화면
"쿠폰을 등록한다"
  설정 → 쿠폰 메뉴 → 코드 입력 → 적용 :: 등록된 쿠폰이 목록에 보임
```

시나리오 수의 기준은 코드 커버리지가 아니라 여정 목록이다.

---

## 3. 진입 전략

```yaml
setup:
  launch: true                          # 콜드 스타트 (force-stop → 런처 실행)
  # deeplink: example://settings/coupons  # 딥링크 진입 (config의 app 패키지로 고정)
```

진입은 여정이 실제로 시작되는 곳에서 한다:

- **런치 진입 (기본)** — `launch: true`로 시작해 실제 유저 경로로 이동한다. 대부분의 여정은
  앱을 켜는 데서 시작하고, "그 화면까지 도달할 수 있는가"가 여정의 일부다.
- **딥링크 진입** — 유저가 실제로 푸시·공유 링크로 들어오는 여정, 또는 긴 여정을 앞 시나리오가
  이미 보장한 지점부터 이어 쓸 때 `setup.deeplink`로 들어간다. 화면 기능을 열거하는 용도로
  쓰지 않는다.
- **도착 확인 시나리오** (`deeplink/` 그룹) — 액션 없이 도착 화면의 랜드마크 하나만
  Verify한다. 앱이 URI를 못 받으면 하니스가 setup 단계에서 실패시키고, 받았지만 아무
  화면도 안 열면 Verify가 잡는다. `:id` 같은 파라미터가 있는 스킴은 실데이터가 있어야
  검증된다.

딥링크를 쓰기 전에 그 딥링크가 목적지까지 실제로 도달하는지 한 번은 손으로 확인하라.
로그인·프로필 선택 같은 중간 게이트가 목적지를 삼키는 일이 실제로 있다.

### 사전조건은 시나리오가 아니라 상태로 주입한다

매 시나리오 앞에 로그인 스텝을 붙이면 느리고 잘 깨진다. 대신 앱 데이터 스냅샷을 쓴다:

```bash
simul state save member-basic     # 원하는 상태를 손으로 만든 뒤 1회 → .simul/states/member-basic.tar
```

```yaml
setup:
  appState: member-basic   # pm clear → 스냅샷 복원 → 콜드 스타트
  launch: true
```

- `clearData: true`는 스냅샷 없이 pm clear만 한다 — 게스트(신규 설치) 상태 보장.
- **모든 시나리오는 `appState:` 또는 `clearData: true`를 선언하라.** 선언이 없으면
  결과가 기기의 현재 상태에 따라 달라진다. TUI는 이런 시나리오를 ⚠ 그룹으로 모아
  보여주고, 실행 시 note를 찍는다.
- 이름과 태그에 전제를 드러내라: `guest-library-login-prompt`, `tags: [member]`.
  `simul run --state member-basic`으로 상태별 일괄 실행이 된다.
- 복원은 debuggable 빌드(`run-as`) 전제다. 토큰이 Android Keystore로 암호화된 앱은
  복원이 깨진다 — 그 경우 로그인된 AVD 스냅샷으로 대체한다.
- pm clear가 런타임 권한도 리셋하므로 알림 권한은 하니스가 재부여한다.
- 스냅샷 tar에는 테스트 계정 토큰이 들어간다. 커밋 여부는 팀 보안 기준으로 정하고,
  만료되면 `state save`를 다시 한다.

---

## 4. 시나리오 형식

```markdown
---
name: coupon-invalid-code           # 고유 이름. trace 파일명과 리포트에 쓰인다
tags: [billing, member]             # simul run --tag 필터
setup:
  appState: member-basic
  deeplink: example://settings/coupons
---

쿠폰 화면에서 잘못된 코드를 입력하면 오류 메시지가 뜬다.   ← 자유 서술. 실행에는 무시된다

1. Verify the "screen:coupons" screen is shown
2. Tap the "Register coupon" button
3. Type "INVALID" into the "Coupon code" field
4. Tap the "Apply" button :: the "Invalid code" message should be shown
```

번호 리스트(`1.` 또는 `1)`)만 스텝으로 실행된다. 나머지 본문은 사람을 위한 설명이다.

---

## 5. 스텝 작성 규칙

스텝은 `Tap` / `Type` / `Scroll` / `Go back` / `Verify`로 시작하는 영어 문장이고, 화면의
라벨은 **보이는 그대로** 따옴표로 쓴다. 규칙은 전부 판정 방식에서 나오니 먼저 그걸 보자.

### 판정 모델

녹화 시 각 스텝은 실행 후 다음을 trace에 evidence로 저장한다:

- 스텝 문장의 따옴표 라벨 중 실행 후 화면에 있는 것
- 액션으로 새로 나타난 노드의 `resourceId` 집합
- 액션으로 얻은 `selected`/`checked` 상태

replay는 이 evidence의 존재 여부로 스텝을 PASS/FAIL한다. 따라서 **따옴표에 무엇을
넣느냐가 곧 테스트가 무엇을 검증하느냐다.** 액션 스텝은 실행 전후 레이아웃 차이가 없으면
그 자체로 FAILED다.

### 규칙

1. **한 스텝 = 한 동작.** 스텝 단위로 판정되고, FAILED 이후 스텝은 전부 SKIPPED다.
   스텝들은 한 세션에서 이어지지만 각 문장은 자기완결적으로 쓴다 — "아까 그 버튼" 같은
   지시어는 없다.

2. **판정 기준은 액션 스텝 뒤에 `::`로 붙인다.** 뻔한 전환("Settings 탭 → Settings 화면")
   에는 아무것도 붙이지 않는다 — 자동 evidence가 이미 게이트한다. 마지막 액션의 성공만으로는
   알 수 없는 것만 기준이 된다: 복원, 불변, 액션과 무관한 상태 조건.

   ```
   Tap the "Library" tab :: the "Downloads" screen should be restored
   Go back :: the "Cart" badge should still be shown
   ```

   기준은 액션이 끝난 뒤에야 에이전트에 전달된다(blind action). 앱이 깨졌을 때 에이전트가
   기준을 향해 경로를 수리하거나, 앱 정책(복원 등)을 오판해 FAILED를 내는 것을 막기
   위해서다. 기준 속 따옴표 앵커는 녹화 시 화면에 실재해야 하고(없으면 FAILED), 녹화를
   통과한 기준은 replay도 반드시 게이트한다. 예외는 부재 기준(`not`이 든 문장)으로, replay
   게이트 없이 녹화 시 LLM 판단만 받는다.

3. **독립 `Verify` 스텝은 붙일 액션이 없을 때만.** 런치·딥링크 직후의 도착·초기 상태
   검증이 그 경우다. 따옴표 앵커가 있으면 러너가 `is shown` / `is not shown` /
   `is selected|checked` 술어로 즉시 판정한다 — LLM 0회. 앵커 없는 Verify만 LLM 판단으로
   폴백되므로 항상 앵커를 넣어라.

4. **앵커는 화면의 정체성으로, 내용으로 하지 않는다.** 우선순위:
    1. `resourceId`/testTag: `Verify the "screen:downloads" screen is shown`. 문구가
       바뀌어도 살아남는다.
    2. 도착 화면에 유일한 고정 라벨. `Verify the "Coupons" title is shown`은 이전 화면의
       메뉴 항목 "Coupons"와 겹쳐 약하다 — 겹치면 다른 문구를 찾지 말고 1로 내려가라.
    3. 데이터에 따라 갈리는 문구(빈 상태 메시지 등)는 그 데이터 상태 자체가 검증 대상일
       때만. 아니면 테스트가 스냅샷 내용에 결합된다.

5. **동적 콘텐츠를 타겟하거나 검증하지 않는다.** 콘텐츠 제목, 배너 문구, 개수 배지는
   날마다 바뀐다. 탭 이름, 섹션 헤더, 고정 버튼 라벨, resourceId만 쓴다.

6. **같은 라벨이 여러 개면 AMBIGUOUS로 멈춘다.** 더 구체적인 라벨을 쓰거나 앱에 testTag를
   요구한다. 에이전트가 알아서 고르게 두지 않는 이유는, 그 선택이 trace에 굳어 replay를
   불안정하게 만들기 때문이다.

7. **스크롤은 명시한다.** `Scroll down to find "Settings"` — 최대 `scrollLimit`회
   (기본 3). 이미 보이면 스크롤 없이 FOUND되므로, 기기·해상도에 따라 보일 수도 있는
   타겟에 써도 안전하다. `off-screen` 요소는 트리에 있어도 스크롤 없이는 탭할 수 없다.

8. **타이핑은 ASCII만.** `adb shell input text`의 한계다.
   `Type "refund" into the "Search" field`. 한글 입력 스텝은 만들지 않는다.

---

## 6. 워크플로

```
1. 시나리오 md 작성
2. simul run <path> --mode llm       # 에이전트 수행 + <name>.trace.json 녹화
3. trace 리뷰 후 md와 함께 커밋
4. CI: simul run --all               # replay, LLM 0회. 깨진 스텝 = FAILED
```

trace는 커밋 전에 반드시 읽는다. 확인할 것:

- 각 액션의 `target`(resourceId/label)이 의도한 요소인가.
- `::` 기준이 붙은 스텝의 액션 개수가 기대(보통 1)와 같은가. 더 많으면 에이전트가 기준을
  향해 경로를 수리한 것이다. 그 trace를 커밋하지 말고 앱을 의심하라.
- `evidence`가 스텝 완료를 실제로 증명하는가. 동적 라벨이 섞였으면 스텝 문장에 고정
  라벨을 따옴표로 넣고 재녹화한다.

replay가 깨지면 그것이 테스트 결과다. 화면이 정당하게 바뀌었으면 재녹화(`--mode llm`)하고,
아니면 앱 회귀다. LLM이 자동으로 덮어주지 않는다.

**FAILED인데 앱이 맞아 보이면 앱을 의심하라.** 시나리오를 비틀어 통과시키지 말 것.
에이전트가 실제 앱 버그를 먼저 잡은 사례가 있다.

---

## 7. 트러블슈팅

| 증상 | 원인 / 조치 |
|---|---|
| `no element matching '...'` | 라벨 오타, 화면 밖, 또는 앱이 노출 안 함. 리포트 스크린샷·agent.jsonl로 당시 관찰을 확인하고, `android layout`이나 `simul "'라벨' 눌러"` ad-hoc으로 화면이 실제로 뭘 노출하는지 본다 |
| `AMBIGUOUS: N elements match` | 라벨 중복. 더 구체적 라벨, 또는 앱에 testTag 요구 |
| replay `evidence not on screen` | 화면이 실제로 달라짐. 정당한 변경이면 재녹화, 아니면 앱 회귀 |
