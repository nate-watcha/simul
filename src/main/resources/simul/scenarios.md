---
name: simul-scenario
description: simul(Android UI 테스트 러너)용 자연어 시나리오 .md를 새로 쓰거나 고치거나 리뷰할 때 사용한다. 무엇을 시나리오로 만들지 고르는 기준, 파일 형식, 스텝 작성 규칙을 담는다.
---

simul 시나리오는 자연어로 쓴 유저 여정이다. LLM 에이전트가 한 번 수행하며 trace를
녹화하고(`--mode llm`), CI는 그 trace를 LLM 없이 재생한다(`--mode replay`).

replay는 "어제 되던 여정이 오늘도 같은 구조로 되는가"만 답한다. 판정 근거는 화면에
나타난 `resourceId`와 `selected`/`checked` 상태, 그리고 스텝에 쓴 따옴표 앵커다.
매번 바뀌는 콘텐츠는 근거가 되지 못한다. 아래 규칙은 전부 여기서 나온다.

## 무엇을 시나리오로 쓰나

기준은 하나다: **이게 실패하면 유저가 뭘 못 하게 되는가.**

- 시나리오 하나 = 여정 하나. 진입(앱을 켠 이유) → 실제 유저 경로 → 결과 확인.
  결과 확인이 없으면 여정이 아니다.
- 여정 목록 = 유저가 앱을 켜는 이유 목록. 재생, 결제, 검색, 보관, 로그인처럼 막히면
  앱을 못 쓰는 것부터 쓴다.
- 이유마다 가장 흔한 경로 하나. 다른 경로는 유저가 실제로 많이 쓸 때만.
- 화면을 넘나드는 여정을 우선한다. A에서 한 행동이 B에 반영되는지가 가장 값지다.
- 상태 variant(guest, 비구독, 키즈 등)는 결과가 달라질 때만 별도 시나리오로 만든다.
  쓰지 않는 것:

- 한 화면 안에서 끝나는 토글·위젯 동작 확인. 유닛/Compose 테스트의 몫이다.
  여정이 지나가는 길에 있으면 그 안에서 같이 검증된다.
- 코드 분기, 경계값, 엣지 케이스.
- 콘텐츠가 맞는지("영화만 나오는가", 특정 작품 제목). replay가 판정할 수 없다.
- 화면 기능을 열거하는 시나리오. 수의 기준은 커버리지가 아니라 여정 목록이다.

## 쓰기 전에

1. 여정을 한 문장으로 쓴다: "보고 싶은 작품을 찾아 재생한다". 안 써지면 여정이 아니다.
2. 전제 상태를 정한다 (`appState` 또는 `clearData`).
3. 경로의 각 화면에서 `android layout`을 실행해 실제로 노출되는 `text`,
   `contentDesc`, `resourceId`, `state`를 확인한다. 라벨을 추측해서 쓰지 않는다.
   에이전트는 스크린샷을 보지 않는다. 세 매칭 속성이 없는 요소(설명 없는 아이콘 버튼),
   색·위치 같은 시각 정보, `selected`/`checked`를 내보내지 않는 커스텀 탭은 지목하거나
   검증할 수 없다. 이런 요소를 만나면 이웃 요소나 상대 위치로 우회하지 말고, 시나리오
   작성을 멈추고 "앱에 contentDescription / testTag / 상태 노출 필요"로 보고한다.

## 형식

```markdown
---
name: coupon-invalid-code        # 고유 이름. trace 파일명이 된다
tags: [billing, member]
setup:
  appState: member-basic         # 또는 clearData: true (게스트)
  launch: true                   # 또는 deeplink: example://settings/coupons
---
 
쿠폰 화면에서 잘못된 코드를 입력하면 오류 메시지가 뜬다.
 
1. Tap the "Settings" tab
2. Tap the "Coupons" menu
3. Type "INVALID" into the "Coupon code" field
4. Tap the "Apply" button :: the "coupon_error" message should be shown
```

- 번호 리스트만 실행된다. 나머지 본문은 사람을 위한 설명이다.
- 모든 시나리오는 `appState` 또는 `clearData: true`를 선언한다.
- 기본은 `launch: true`다. 도달 가능한지가 여정의 일부다. `deeplink`는 유저가 실제로
  링크로 들어오는 여정이거나, 앞 구간을 다른 시나리오가 이미 보장할 때만 쓴다.
- 이름과 태그에 전제를 드러낸다: `guest-library-login-prompt`, `tags: [guest]`.
  상태 스냅샷 만들기와 딥링크 도착 확인 시나리오는 [setup.md](references/setup.md)를 본다.

## 스텝 규칙

스텝은 `Tap` / `Type` / `Scroll` / `Go back` / `Verify`로 시작하는 영어 문장이고,
화면의 라벨은 보이는 그대로 따옴표에 넣는다. 따옴표 문자열은 `text`, `contentDesc`,
`resourceId`에 동등하게 매칭된다.

1. **한 스텝 = 한 동작.** FAILED 이후 스텝은 전부 SKIPPED다. 각 문장은 자기완결적으로
   쓴다. "아까 그 버튼" 같은 지시어는 없다.
2. **앵커는 고정된 것만.** 우선순위는 resourceId/testTag → 도착 화면에 유일한 고정
   라벨 순이다. 콘텐츠 제목, 배너 문구, 개수 배지는 타겟으로도 검증으로도 쓰지 않는다.
3. **뻔한 전환에는 기준을 붙이지 않는다.** 액션으로 새로 나타난 resourceId와 상태가
   자동으로 게이트된다. 액션 스텝은 전후 레이아웃 차이가 없으면 그 자체로 FAILED다.
4. **액션만으로 알 수 없는 것은 `::`로 붙인다.** 복원, 불변, 다른 화면에 반영된 결과.
   ```
   Tap the "Library" tab :: the "screen:downloads" screen should be restored
   Go back :: the "Cart" badge should still be shown
   ```
5. **독립 `Verify`는 붙일 액션이 없을 때만.** 런치·딥링크 직후의 도착 확인이 그 경우다.
   반드시 따옴표 앵커를 넣는다. 앵커 없는 Verify는 LLM 판단으로 떨어진다.
6. **같은 라벨이 여러 개면 AMBIGUOUS로 멈춘다.** 더 구체적인 라벨이나 resourceId를 쓴다.
7. **스크롤은 명시한다.** `Scroll down to find "Settings"`. 이미 보이면 스크롤 없이
   넘어가므로 해상도에 따라 보일 수도 있는 타겟에 써도 된다.
8. **타이핑은 ASCII만.** 한글 입력 스텝은 만들지 않는다.

## 공허한 통과를 막는다

이 구조에서 가장 위험한 건 실패가 아니라, 액션이 안 먹혔는데도 통과하는 스텝이다.
시나리오를 다 쓴 뒤 스텝마다 묻는다: **이 액션이 실패했다면 이 스텝은 FAILED가 됐을까?**

- 마지막 스텝은 여정의 결과를 고정 앵커로 확인해야 한다.
- 이전 화면에도 있던 라벨은 도착의 증거가 못 된다("Coupons" 메뉴 → "Coupons" 제목).
  resourceId로 내려간다.
- 부재 기준(`not`이 든 문장)은 replay에서 게이트되지 않는다. 가능하면 대신 나타나는
  것을 기준으로 쓴다.

## 녹화 후

FAILED인데 시나리오가 맞아 보이면 시나리오를 비틀어 통과시키지 말고 앱을 의심한다.