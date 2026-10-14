# simul — layout 기반 Android 자연어 UI 테스트 러너

자연어 커맨드/시나리오를 받아 연결된 에뮬레이터에서 수행한다.

- **판단**: OpenAI 호환 엔드포인트(기본은 로컬 llama-server, `llm.apiKey`로 호스티드 API도 가능) +
  자체 tool-calling 루프. 판정 근거는 액션이 만든 LAYOUT DIFF
- **관찰**: `android layout` 출력 + WebView 내부는 CDP로 병합 (스크린샷/vision 없음)
- **조작**: `adb shell input ...` — 웹 요소도 병합 시 화면 좌표로 변환되어 동일 경로

## 사용

앱 레포가 `.simul/` 규약(config.yaml + scenarios/*.md)을 갖추면 CLI로 실행한다.
시나리오 작성법·워크플로·트러블슈팅은 init이 설치하는
**`.claude/skills/simul-scenarios/references/scenarios.md`** 참조.

```bash
./gradlew installDist            # → build/install/simul/bin/simul
ln -s "$(pwd)/scripts/simul" ~/.local/bin/simul   # PATH 래퍼

simul                            # 인터랙티브 모드: 시나리오 목록/실행/녹화/상태 저장/ad-hoc REPL
simul status                     # 시나리오 × trace × 최근 결과 × states 한눈에
simul init --app com.example     # 타겟 레포에 .simul/ 스캐폴드 + 작성 가이드 + Claude 스킬 설치
simul run foo.md --mode llm      # authoring: LLM 실행 + <이름>.trace.json 기록
simul run --all                  # CI: 기본 auto = 트레이스 재생(LLM 0회), 없으면 SKIP
simul run foo.md --mode replay   # 순수 재생 — 깨진 스텝은 FAILED (replay는 LLM 0회 보장)
simul run --tag billing          # 태그 필터 (--state <name>도 동일 형식)
simul run --all --summary r.json --label verify   # 배치 요약 JSON (simul report 입력)
simul run foo.md --mode replay --gif   # 화면 녹화 → 리포트 폴더에 run.gif + run.mp4 (TUI에선 v)
simul report r/*.json --format md|slack|junit|html [--check]   # 나이틀리 3단계 병합 리포트 (아래)
simul list                       # 시나리오 목록 + 트레이스 유무/신선도
simul state save <name>          # 현재 앱 데이터를 .simul/states/<name>.tar 스냅샷으로 저장
                                 #   → 시나리오의 setup.appState: <name>으로 로그인 등 전제 주입
simul "Tap the \"웹툰\" tab"     # ad-hoc 단일 커맨드 (규약 없이 동작)

./gradlew runAgent --args='"설정 탭을 눌러"'   # 개발용 ad-hoc 직접 실행
```

종료 코드: 전부 PASSED=0, 실패=1.

### 나이틀리 운영 (baseline → record → verify → report)

PR 게이트는 replay만 돌리고(LLM 0회, `simul run --all`) 끝이다. 나이틀리는 세 번 돌고 한 번 묶는다.
워크플로 파일은 **앱 레포가 가진다** — 이 레포는 커맨드만 제공하고, 조립 순서는 이것이다:

```bash
R=.simul/reports/nightly; rm -rf $R; mkdir -p $R
simul run --all --mode replay --gif --summary $R/baseline.json --label baseline   # 커밋된 trace 재생 (+화면 녹화)
simul run --all --mode llm          --summary $R/record.json   --label record     # 전 시나리오 재녹화 (LLM 키 필요)
simul run --all --mode replay --gif --summary $R/verify.json   --label verify     # 방금 녹화한 trace 재생 (+화면 녹화)

J="$R/baseline.json $R/record.json $R/verify.json"     # 없는 파일은 빼도 된다 — 있는 phase만으로 판정
simul report $J --title "simul nightly $(date +%F)" --link "Run=$RUN_URL" --format html  --out $R/report.html
simul report $J --title "..." --link "Run=$RUN_URL" --format md    --out $R/report.md     # $GITHUB_STEP_SUMMARY / PR 본문
simul report $J --title "..." --link "Run=$RUN_URL" --format slack --out $R/slack.json    # curl -d @slack.json $SLACK_WEBHOOK_URL
simul report $J --format junit --out $R/junit.xml                                        # 테스트 리포터용
simul report $J --check >/dev/null                                                       # 게이트: regression/trace flake/crash → exit 1
```

각 `simul run`은 실패해도 다음 단계를 막지 않게 돌린다(GitHub Actions면 `continue-on-error: true`).
phase 역할은 라벨이 아니라 **mode와 순서**로 정해진다: 첫 llm 런이 record, 그 직전 replay가 baseline,
직후 replay가 verify.

**리포트가 담는 것**

- `--format html`: verdict 표 + 시나리오별 상세. 시나리오를 펼치면 phase마다 **왼쪽에 스텝 목록
  (PASSED/FAILED, 사유, 실패 화면의 라벨과 스크린샷 썸네일), 오른쪽에 그 replay의 gif**가 같이 보인다.
  gif는 시나리오당 수 MB라 인라인하지 않고 `--out` 옆 `media/<시나리오>-<phase>.gif`로 복사해 참조한다 —
  **아티팩트는 `report.html` + `media/` 폴더를 함께** 올린다. 스크린샷 썸네일은 data URI라 폴더 밖이어도 보인다.
- `--format md`: GitHub 잡 서머리·PR 본문용. 표 + "Needs attention"에 실패 사유·스크린샷·리포트 폴더·gif 경로.
- `--format slack`: Block Kit payload(incoming webhook). 주의 항목 20개까지.
- `--format junit`: phase=testsuite, 시나리오=testcase.
- 입력 summary.json의 시나리오 항목에 `gif`가 있어야 html에 gif가 붙는다 — 즉 **replay에 `--gif`를 붙인 런만**.
  gif는 `adb shell screenrecord`(180s 세그먼트 자동 이어붙임) → `run.mp4` → ffmpeg(2-pass 팔레트, 360px·10fps)
  → `run.gif`. 러너에 ffmpeg이 없으면 mp4만 남고 리포트엔 gif가 빠진다(FAILED 아님).

세 결과가 시나리오당 하나의 verdict로 접힌다:

| baseline | record | verify | verdict | 뜻 |
|---|---|---|---|---|
| ✅ | ✅ | ✅ | ✅ stable | |
| ❌ | ✅ | ✅ | 🔁 changed | 화면이 바뀌었고 새 녹화는 재생됨 → 재녹화 trace PR 리뷰·머지 |
| ❌ | ❌ | – | ❌ regression | 커밋 trace도 새 녹화도 실패 → 앱 회귀 의심, baseline gif/스크린샷부터 |
| ✅ | ❌ | – | ⚠️ record flake | 앱은 커밋대로인데 에이전트가 못 함 → record 로그(문구·AMBIGUOUS) |
| – | ✅ | ❌ | ⚠️ trace flake | 녹화는 됐는데 재생 불가 → 비결정 evidence(동적 라벨) |
| ⏭ | ✅ | ✅ | 🆕 new | 첫 녹화 → 커밋 |

`--check`는 regression/trace flake/crash에서만 exit 1 — changed/new는 사람이 PR로 판단한다.
record가 다시 쓴 `.simul/scenarios/**/*.trace.json`은 워크플로가 PR로 올리고(예: peter-evans/create-pull-request,
본문에 `report.md`) 사람이 diff를 보고 머지한다.

**러너 요구사항** (self-hosted — 호스티드 러너엔 에뮬레이터도 LLM도 없다)

- PATH에 `simul`(이 레포 `./gradlew installDist` + `scripts/simul`), `adb`·`emulator`·`android`(CLI), `ffmpeg`(gif용, 선택)
- 에뮬레이터 1대: 매번 콜드 부팅·스냅샷 저장 안 함(기기 드리프트가 앱 회귀로 둔갑하지 않게), `ANDROID_SERIAL` 고정.
  화면은 config.yaml `display:`가 실행 중 강제하므로 AVD 해상도는 무관
- debug 빌드 설치(WebView 채널이 debuggable 전제), `.simul/config.yaml llm.url`에 닿는 OpenAI 호환 LLM —
  키는 `llm.apiKey: ${VAR}` + 러너 시크릿. **replay 두 단계는 키 없이 돈다**
- 로그인 등 상태 스냅샷(`.simul/states/*.tar`, gitignored)은 러너 로컬 디렉터리에서 체크아웃 뒤 복사
- 끝나면 `adb emu kill`; 아티팩트로 `report.html + media/`(30일)와 `.simul/reports/` 전체(스크린샷·agent.jsonl·mp4, 14일)

## 구조 (`dev.nate.uiagent`)

| 패키지 | 역할 |
|---|---|
| (root) | `Core.kt` 공용 타입(geometry, `LogicalElement/Layout`), `LayoutAdapter.kt` dump(flat/트리)→논리 요소 트리 병합, `Trace.kt` JSONL 트레이스, `Process.kt` |
| `device` | `Device`/`AdbDevice` 관찰·제스처, `DeviceController` grounding·가드·diff 검증·wait-for-stable(+replay용 fastObserve), `Layout.kt` 모델용 렌더링/diff, `SwipeGeometry` |
| `agent` | Koog 1.3 기반 LLM 스택: `Llm.kt` OpenAI-호환 executor/모델/고정 파라미터, `Tools.kt` 디바이스 툴 + 기준 게이트, `Session.kt` GraphAIAgent + ChatMemory(시나리오당 단일 대화), `Agent.kt` 시스템 프롬프트, ad-hoc `main` |
| `cli` | `simul` CLI, `.simul/` 규약, 시나리오 러너(스텝 격리·라이브 로그), Record & Replay(`TraceFormat`/`Recorder`/`Replayer`), 리포트 |
| `web` | WebView 관찰 채널: `CdpClient`(devtools socket discover + Runtime.evaluate), `WebLayout`(요소 추출 JS·좌표 변환), `WebAwareDevice`(관찰 병합) |

핵심 설계 불변:

- **모델에 좌표 비노출** (라벨/synthetic handle grounding — 좌표 환각 방지)
- **모델은 웹/네이티브를 구분하지 않는다** — 관찰 병합·좌표 변환은 전부 harness가 흡수
- **replay는 LLM 0회 (예외 없음)** — resourceId→label→좌표 재grounding + evidence(따옴표 라벨 +
  신규 노드 resourceId 시그니처 + selected/checked 상태) 판정. 깨진 스텝은 FAILED — 화면이 실제로
  바뀐 것이므로 재녹화(--mode llm)냐 앱 수정이냐는 사람이 결정. fastObserve(단일 관찰 +
  실패 시에만 재안정화)로 스텝당 ~3.5s
- 트레이스는 결정론 직렬화(고정 키 순서, 휘발값 배제) — 같은 실행 2회면 diff 없음
- **display 프로필 강제** — config의 `display:`(sizeClass `small|medium|large` — 대상 앱 디자인
  시스템(경계 600/800dp) 정렬 — 또는 size+density 명시)를
  실행 동안 `wm size/density`로 강제해 어떤 에뮬레이터에서든 같은 size class로 trace를 재생.
  에뮬레이터에서만 동작(실기기는 경고 후 무시), 종료 시 이전 상태 복원. 녹화 trace에
  `recordedWith.display` 스탬프, replay 시 불일치 경고
- serialization 컴파일러 플러그인 미사용 — kotlinx-serialization **런타임**(JsonElement)만

## 빌드 & 테스트

```bash
./gradlew test          # 유닛 테스트 (device/cli/web 전 계층, fake 기반)
./gradlew installDist   # simul 바이너리
```

## 제약 / 스코프 밖

- **비ASCII 타이핑 미지원**(`adb shell input text` 한계) → 즉시 FAILED
- WebView 관찰은 debug 빌드(`setWebContentsDebuggingEnabled`) 전제, cross-origin iframe 미지원
- 스크린샷/vision 판단, 웹 성능 계측 — 스코프 밖

