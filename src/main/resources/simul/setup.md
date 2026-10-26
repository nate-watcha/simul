# 전제 상태와 진입

## 사전조건은 스텝이 아니라 상태로 주입한다

매 시나리오 앞에 로그인 스텝을 붙이면 느리고 잘 깨진다. 앱 데이터 스냅샷을 쓴다.

```bash
simul state save member-basic     # 원하는 상태를 손으로 만든 뒤 1회 → .simul/states/member-basic.tar
```

```yaml
setup:
  appState: member-basic   # pm clear → 스냅샷 복원 → 콜드 스타트
  launch: true
```

- `clearData: true`는 스냅샷 없이 pm clear만 한다. 게스트(신규 설치) 상태 보장.
- 모든 시나리오는 `appState:` 또는 `clearData: true`를 선언한다. 없으면 결과가 기기의
  현재 상태에 따라 달라진다.
- 이름과 태그에 전제를 드러낸다: `guest-library-login-prompt`, `tags: [member]`.
  `simul run --state member-basic`으로 상태별 일괄 실행이 된다.
- 스냅샷 복원은 debuggable 빌드(`run-as`) 전제다. 토큰이 Keystore로 암호화된 앱은
  복원이 깨지므로 로그인된 AVD 스냅샷으로 대체한다.
- 스냅샷 tar에는 테스트 계정 토큰이 들어간다. 커밋 여부는 팀 보안 기준으로 정하고,
  만료되면 `state save`를 다시 한다.

## 여러 상태를 한 파일로: `states:`

같은 흐름을 여러 상태에서 돌릴 때는 `setup.appState` 대신 frontmatter에 `states:`를 쓴다
(둘을 같이 쓰면 파싱 에러). 상태마다 별도의 실행 단위가 된다.

```yaml
states: [member, nosub, guest]
setup:
  launch: true
```

- 상태 이름 = `.simul/states/<이름>.tar` 스냅샷 이름. 실행 전 pm clear → 스냅샷 복원 → launch.
  `guest`만 예외로 스냅샷이 없어도 되며, 그때는 pm clear만 한다(신규 설치).
- 실행 단위 이름은 `<name>@<state>`, trace는 `<파일명>@<state>.trace.json`으로 md 옆에
  상태 수만큼 생긴다. `simul status`/`simul list`/리포트에도 상태별로 한 줄씩 나온다.
- `simul run <path>`는 모든 상태를 돌리고, `--state <이름>`을 붙이면 그 상태만 돈다.
- 스텝 아래 `:: <state> → 기준` 줄은 그 상태에만 적용되고, 접두 없는 `::`(같은 줄이든 아래
  줄이든)는 모든 상태에 적용된다. 한 상태에 접두 기준이 있으면 그 상태에서는 접두 없는
  기준을 대신한다 — "기본 기대"를 접두 없이 쓰고, 다른 상태에만 접두로 덮어쓴다.

## 앱 데이터가 아닌 전제: 에뮬레이터 설정

Google 비밀번호 관리자의 자격 증명 제공자, 자동완성 서비스, 전체화면 "Got it" 안내 같은 것은
기기 설정이라 pm clear로도 스냅샷 복원으로도 바뀌지 않는다. 이메일 로그인을 한 번 녹화하면 그 뒤
모든 게스트 콜드 런치에 "Use your saved password" 시트가 뜨는 식이다. 이런 전제는 **시나리오
스텝이 아니라 설정으로 선언**하고 러너가 adb(`settings get/put`)로 맞춘다 — 모델이 설정 앱을
조작하게 하지 않는다(adb가 같은 결과를 결정론적으로 내고, replay는 LLM 0회여야 한다).

두 층이다. `.simul/config.yaml`의 `device.settings`는 모든 시나리오가 전제하는 기본값이고,
시나리오 `setup.device.settings`는 그 시나리오만 반대 상태가 필요할 때(저장된 비밀번호 시트
자체를 검증할 때) 덮어쓰는 값이다.

```yaml
# .simul/config.yaml — 실행 시작에 맞추고 끝나면 이전 값으로 되돌린다
device:
  settings:
    secure.credential_service: ""              # 제공자 없음 → 시트 안 뜸
    secure.credential_service_primary: ""
    secure.autofill_service: null              # null = 키 삭제(settings delete)
    secure.immersive_mode_confirmations: confirmed
```

```yaml
# 시나리오 frontmatter — 이 시나리오의 launch 전에 바꾸고, 끝나면 기본값으로 복귀
setup:
  appState: guest
  launch: true
  device:
    settings:
      secure.credential_service: com.google.android.gms/com.google.android.gms.auth.api.credentials.credman.service.PasswordAndPasskeyService
```

- 키는 `<namespace>.<key>`(`system|secure|global`), 값은 `settings put`에 그대로 들어간다.
  `""`는 빈 문자열, `null`은 키 삭제다. 현재 값을 읽어 다를 때만 쓰고, 바꾼 것만 되돌린다.
- 에뮬레이터에서만 동작한다. 실기기는 경고 한 줄 뒤 무시한다(display와 같은 규칙).
- 설정을 켠다고 시트가 뜨는 건 아니다 — 저장된 자격 증명이 Google 계정 데이터로 **AVD 안에**
  있어야 한다. 그 전제는 앞 시나리오의 부수효과에 기대지 말고 러너 AVD 이미지에 한 번 저장해 둔다.
- `states:` variant는 같은 `device` 블록을 상속한다.

### 앱 언어

같은 `device` 블록의 `locale`은 **앱의 언어**다(Android 13+ 앱별 언어, `cmd locale set-app-locales <app>`).
시나리오의 따옴표 라벨은 한 언어로 쓰이므로 러너 AVD나 개발자 에뮬레이터의 시스템 언어와 무관하게 앱을
그 언어로 띄운다. 시스템 언어는 건드리지 않는다(root와 프레임워크 재시작이 필요하다).

```yaml
# .simul/config.yaml — 모든 시나리오의 기본 언어
device:
  locale: ko-KR           # BCP-47 태그, 또는 선호 목록 `ko-KR,en-US`
  settings: …
```

```yaml
# 시나리오 frontmatter — 이 시나리오만 다른 언어로
setup:
  appState: guest
  launch: true
  device:
    locale: en-US
```

- `settings`와 달리 앱 상태라 **pm clear가 지운다**(실측). 그래서 setup 안에서 pm clear·스냅샷 복원 **뒤**,
  launch **앞**에 걸고, 시나리오가 끝나면 이전 값(보통 "시스템 따름")으로 되돌린다.
- 앱이 매니페스트에 `localeConfig`를 선언하지 않아도 동작한다(설정 앱의 "앱 언어" 메뉴에만 안 뜬다). 앱이
  자체 설정값(SharedPreferences)만 읽는 독자 구현이면 효과가 없다 — `AppCompatDelegate.setApplicationLocales`는
  API 33+에서 같은 시스템 값으로 위임되므로 괜찮다.
- 앱 하나만 건드리므로 pm clear와 같이 실기기에서도 적용한다(display/settings는 에뮬레이터 전용).

## 진입

```yaml
setup:
  launch: true                          # 콜드 스타트 (force-stop → 런처 실행)
  # deeplink: example://settings/coupons  # 딥링크 진입
```

- **런치 진입 (기본)**: 대부분의 여정은 앱을 켜는 데서 시작하고, "그 화면까지 도달할
  수 있는가"가 여정의 일부다.
- **딥링크 진입**: 유저가 실제로 푸시·공유 링크로 들어오는 여정이거나, 긴 여정을 앞
  시나리오가 이미 보장한 지점부터 이어 쓸 때만. 화면 기능을 열거하는 용도로 쓰지 않는다.
- 딥링크를 쓰기 전에 목적지까지 실제로 도달하는지 한 번은 손으로 확인한다. 로그인·프로필
  선택 같은 중간 게이트가 목적지를 삼키는 일이 실제로 있다.
- 첫 스텝은 목적지 액티비티가 `reportFullyDrawn()`을 부른 뒤에 시작한다(러너가 logcat의 `Fully drawn`을
  기다림 — 스플래시 액티비티 위에서 관찰하지 않게). 런 로그에 "app did not report fully drawn" 경고가
  보이면 그 액티비티에 호출이 빠진 것이다 — 시나리오에 대기 스텝을 넣지 말고 앱에 호출을 넣는다.

## 도착 확인 시나리오 (`deeplink/` 그룹)

액션 없이 도착 화면의 랜드마크 하나만 Verify한다.

```markdown
---
name: deeplink-coupons
tags: [deeplink, member]
setup:
  appState: member-basic
  deeplink: example://settings/coupons
---

1. Verify the "screen:coupons" screen is shown
```

앱이 URI를 못 받으면 하니스가 setup 단계에서 실패시키고, 받았지만 아무 화면도 안 열면
Verify가 잡는다. `:id` 같은 파라미터가 있는 스킴은 실데이터가 있어야 검증된다.
