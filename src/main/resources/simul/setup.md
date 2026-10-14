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
