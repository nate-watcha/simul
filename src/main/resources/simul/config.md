# 환경과 계정을 설정으로 맞추기

시나리오가 전제하는 환경(기기 설정, 앱 언어, 화면 크기)과 계정이 누구인지는 스텝이 아니라 설정으로 선언한다. 러너가 실행 전에 그 상태로 맞추고
끝나면 되돌린다.

**시나리오를 쓰다가 환경이 맞지 않으면 직접 설정을 고쳐서 시나리오를 완성한다.** 시스템 시트가 화면을 가린다, 앱이 다른 언어로 뜬다, 모델이
계정의 사정을 몰라 정상 화면을 오류로 본다. 이런 것은 못 쓴 이유가 아니라 설정 한 줄로 푸는 일이다. 스텝으로 돌아가거나 모델이 설정 앱을
조작하게 하지 않는다.

설정할 자리는 둘이다.

- **그 시나리오의 frontmatter `setup.device`** — 이 시나리오만 필요한 것. 먼저 여기를 본다.
- **`.simul/config.yaml`** — 모든 시나리오가 전제하는 것. 여기를 바꾸면 다른 시나리오 전부의 전제가 바뀐다.

## 시나리오별로: `setup.device`

```yaml
setup:
  appState: member
  deeplink: example://settings
  device:
    locale: en-US
    settings:
      secure.autofill_service: null
```

- `settings` — 에뮬레이터의 `settings` 값이면 무엇이든 된다. 키는 `<namespace>.<key>`(`system|secure|global`), 값은
  `settings put`에 그대로 들어가고 `null`은 키 삭제다. 이 시나리오의 launch 전에 바꾸고 끝나면 되돌린다. 에뮬레이터에서만 동작한다.
- `locale` — 앱의 언어(Android 13+ 앱별 언어). BCP-47 태그 하나(`en-US`) 또는 선호 목록(`ko-KR,en-US`). 시스템 언어는 건드리지
  않는다. 앱이 자체 설정값만 읽는 독자 구현이면 효과가 없다.

config.yaml의 `device` 블록도 같은 모양이고, 거기 적은 값이 모든 시나리오의 기본값이 된다. 시나리오의 `setup.device`는 그 위에 덮인다.

## 전체에: `config.yaml`

```yaml
context: |
  구독형 영상 앱의 debug 빌드다. 게스트와 비구독자는 재생에서 로그인·구독 화면을 만날 수 있다.
states:
  guest: 로그인하지 않은 사용자
  member: 로그인한 구독 계정
defaults:
  maxTurns: 12
  scrollLimit: 3
display:
  sizeClass: small
device:
  locale: ko-KR
```

- `context` — 앱에 대한 사실 몇 줄. 모든 시나리오의 모델 세션에 들어간다. 화면에는 안 보이지만 모델이 알아야 판정할 수 있는 것을 적는다.
- `states` — 상태(`.simul/states/<이름>.tar`)마다 그 계정이 누구인지 한 줄. 시나리오가 그 상태로 돌 때 그 줄만 모델 세션에 들어간다.
  모델이 자기가 누구인지 아는 길은 이 줄뿐이다. 어떤 계정인지를 짧게 설명하는 줄이지 계정의 데이터를 기록하는 곳이 아니다. 스냅샷을 새로 만들면
  줄도 같이 추가한다.
- `defaults` — `maxTurns`는 한 스텝에 모델이 쓸 수 있는 턴 수, `scrollLimit`은 "스크롤해 찾는다"가 포기하기 전에 스크롤하는 횟수. 턴이
  모자라면 값을 올리기 전에 스텝을 쪼갠다.
- `display` — 실행 동안 에뮬레이터를 맞출 화면 크기. `sizeClass`는 `small`(360dp) / `medium`(640dp) / `large`(1066dp). 실행
  전체에 걸려서 시나리오마다 바꿀 수 없다.

`context`와 `states`에는 **사실만 적는다.** "감상하기를 누르면 구독 화면이 떠야 한다" 같은 기대를 적으면 모델이 그것을 정상으로 알고
시작해서, 앱이 틀려도 통과한다. 기대는 스텝의 `::`에 쓴다.

`agentVersion`, `app`, `llm`은 `simul init`이 만든 그대로 둔다.
