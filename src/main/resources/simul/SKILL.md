---
name: simul
description: 자연어 UI 시나리오를 Android 에뮬레이터에서 실행하고 trace로 재생하는 테스트 러너
  `simul`의 사용법. 앱의 UI 테스트·E2E 테스트·시나리오 작성, 기존 시나리오 수정, trace 녹화와
  리뷰, replay 실패 진단, 테스트 가능성(testTag/contentDescription) 개선 요청이 필요할 때
  사용한다. 사용자가 "시나리오", "UI 테스트", "E2E", "replay가 깨졌다", "이 화면 테스트 추가"를
  언급하면 이 스킬을 읽는다 — `simul`이라는 이름을 직접 말하지 않아도 마찬가지다.
license: Complete terms in LICENSE.txt
metadata:
  keywords:
  - simul
  - ui-test
  - e2e
  - scenario
  - trace
  - replay
  - emulator
  - accessibility
---

`simul`은 마크다운으로 쓴 자연어 시나리오를 Android 에뮬레이터에서 실행하는 UI 테스트
러너다. LLM 에이전트가 시나리오를 한 번 수행하며 trace를 녹화하고(`--mode llm`), 이후
CI는 그 trace를 LLM 없이 결정론적으로 재생한다(`--mode replay`).

이 스킬로 할 수 있는 일:

- 대상 프로젝트의 코드베이스를 읽고 **무엇을 테스트할지 정한 뒤** 시나리오를 작성한다
- 시나리오를 녹화하고, trace를 리뷰하고, 커밋 가능한지 판단한다
- replay 실패를 "정당한 화면 변경"과 "앱 회귀"로 구분한다
- 시나리오로 지목할 수 없는 요소를 찾아 앱 쪽 노출(testTag, contentDescription) 변경을 요구한다

## 시나리오 작성 전에 반드시 읽을 것

**IMPORTANT:** 시나리오를 쓰거나 고치기 전에 [references/scenarios.md](references/scenarios.md)를
읽어라. 이 문서는 에이전트가 화면을 어떻게 관찰하는지(무엇이 보이고 무엇이 안 보이는지),
어떤 동작을 테스트 대상으로 삼을지, 스텝 문장이 어떻게 PASS/FAIL로 판정되는지를 설명한다.
스텝 규칙은 전부 판정 방식에서 나오므로, 규칙만 외우고 이유를 모르면 새로운 화면에서
잘못된 시나리오를 쓰게 된다.

기기 관찰과 ad-hoc 조작은 `android` CLI를 쓴다. `android layout`과 `android screen`을
직접 쓰기 전에 android-cli 스킬의 [interact.md](../android-cli/references/interact.md)를
읽어라. simul의 에이전트가 보는 것이 바로 `android layout`의 출력이다.

## 시나리오 작성 절차

1. **경계를 정한다.** 대상 코드를 읽고 "우리 저장소의 diff로 깨질 수 있는 동작"만 고른다.
   라이브러리가 보장하는 동작은 테스트하지 않는다. (scenarios.md §2)
2. **기존 시나리오와 상태를 확인한다.** `scenarios/` 디렉토리 구조와 `.simul/states/`에
   어떤 앱 상태 스냅샷이 있는지 본다. 같은 화면 디렉토리에 추가하고, 이미 있는 상태를
   재사용한다. 없는 상태가 필요하면 `simul state save <name>`을 사용자에게 요청한다.
3. **화면이 뭘 노출하는지 본다.** 에뮬레이터에서 대상 화면을 띄우고 `android layout`으로
   실제 `text`/`contentDesc`/`resourceId`를 확인한다. 추측한 라벨로 쓰지 않는다. 지목할
   요소가 노출되지 않았으면 시나리오를 우회하지 말고 앱 변경을 제안한다. (scenarios.md §1)
4. **시나리오를 쓴다.** 진입은 딥링크 우선, 전제는 `appState:`/`clearData:`로 선언, 앵커는
   resourceId 우선. (scenarios.md §4–6)
5. **녹화한다.** `simul run <path> --mode llm`
6. **trace를 리뷰한다.** target이 의도한 요소인지, `::` 기준이 붙은 스텝의 액션이 1개인지,
   evidence에 동적 라벨이 섞이지 않았는지. 통과하면 md와 trace를 함께 커밋한다. (scenarios.md §7)

FAILED인데 앱이 맞아 보이면 시나리오가 아니라 앱을 먼저 의심한다. 시나리오를 비틀어
통과시키는 것은 테스트를 없애는 것과 같다.

## 실행

```bash
simul run --all                          # 전체 replay (CI 게이트, 전부 PASSED면 exit 0)
simul run scenarios/checkout/            # 디렉토리 단위
simul run --tag billing                  # 태그 필터
simul run --state member-basic           # 상태별 일괄 실행
simul run <path> --mode llm              # 녹화 / 재녹화
simul run <path> --mode replay|auto      # 재생 / trace 있으면 재생, 없으면 녹화
simul run <path> --dry-run               # 파싱과 setup만 확인
simul state save <name>                  # 현재 앱 데이터를 상태 스냅샷으로 저장
simul "'Settings' 눌러"                  # ad-hoc 한 스텝 실행 — 라벨 확인용
```

- 기기 2대 이상이면 `ANDROID_SERIAL=emulator-5554 simul run ...`
- 결과는 `.simul/reports/<run>/`(report.json, 스크린샷, agent.jsonl)에 쌓인다. 커밋하지 않는다.
- 나이틀리(`.github/workflows/simul-nightly.yml`)는 baseline replay → LLM 재녹화 → 재생의
  3단계를 `simul report`로 시나리오당 하나의 verdict(stable / changed / regression /
  record flake / trace flake / new)로 접는다. 뜻과 조치는 scenarios.md §9. 워크플로가 올린
  `simul/nightly-traces` PR은 "changed"만 머지 대상이고 "trace flake" trace는 머지하지 않는다.
- 설정은 프로젝트 루트 `config.yaml` — 대상 패키지, LLM 엔드포인트, `display:` size class.

## replay가 깨졌을 때

1. 리포트의 스크린샷과 agent.jsonl로 **그 시점의 관찰**을 본다.
2. `evidence not on screen`이면 화면이 실제로 달라진 것이다. 최근 diff가 그 화면을 정당하게
   바꿨으면 `--mode llm`으로 재녹화하고, 아니면 앱 회귀로 보고한다.
3. 다른 에뮬레이터에서만 깨지면 `config.yaml`의 `display:`와 로그의 "trace recorded at ..."
   경고를 확인한다.
4. 자세한 증상별 조치는 scenarios.md §10. 나이틀리 리포트의 verdict별 조치는 §9.

LLM은 replay 실패를 자동으로 덮어주지 않는다. 재녹화는 사람이 "화면 변경이 정당하다"고
판단한 뒤에만 한다.

## 하지 말 것

- 스크린샷의 색·위치·아이콘 모양으로 요소를 지목하는 스텝 — 에이전트는 스크린샷을 보지 않는다
- 콘텐츠 제목, 배너 문구, 개수 배지를 타겟하거나 검증하는 스텝 — 날마다 바뀐다
- 매 시나리오 앞에 로그인 스텝 — 상태 스냅샷으로 주입한다
- `appState:`/`clearData:` 선언 없는 시나리오 — 결과가 기기 상태 운에 좌우된다
- 한글 타이핑 스텝 — `adb shell input text`는 ASCII만 받는다
- `.simul/reports/` 커밋