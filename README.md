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
simul run --all --mode replay --video --runs runs/<app>/$(date +%F)   # 나이틀리: 리포트 구조로 결과 남기기 (아래)
simul run foo.md --mode replay --video   # 화면 녹화 → replay.mp4 + 스텝별 video_ms (TUI에선 v)
simul run --all --summary r.json --label verify   # 배치 요약 JSON (record/verify 단계 리포트 입력)
simul report                             # 커밋된 trace 전부 replay(녹화) → .simul/reports/<stamp>/index.html — 이걸 연다
simul run --all --mode replay --video --runs runs/today   # 같은 것, 디렉토리를 정해서 (나이틀리가 쓰는 형태)
simul report r/*.json --format md|slack|junit [--check]   # record/verify 단계 병합 리포트 (아래)
simul list                       # 시나리오 목록 + 트레이스 유무/신선도
simul state save <name>          # 현재 앱 데이터를 .simul/states/<name>.tar 스냅샷으로 저장
                                 #   → 시나리오의 setup.appState: <name>으로 로그인 등 전제 주입
simul "Tap the \"웹툰\" tab"     # ad-hoc 단일 커맨드 (규약 없이 동작)

./gradlew runAgent --args='"설정 탭을 눌러"'   # 개발용 ad-hoc 직접 실행
```

종료 코드: 전부 PASSED=0, 실패=1.

### 나이틀리 운영 (record → verify → report)

PR 게이트는 replay만 돌리고(LLM 0회, `simul run --all`) 끝이다. 나이틀리는 **그날의 앱을 기준으로 두 번** 돈다:
먼저 LLM이 전 시나리오를 다시 녹화(record)하고, `simul report`가 방금 녹화한 trace를 replay(verify)하면서 **HTML 리포트**(모두가 보는
결과물 — 구조는 아래)를 쓴다. **trace PR 본문(md)·junit·게이트**는 record의 `--summary` JSON과 그 리포트 디렉토리의 `run.json`에서 나온다.
어제 trace를 다시 재생하는 단계는 없다.
워크플로 파일은 **앱 레포가 가진다** — 이 레포는 커맨드만 제공하고, 조립 순서는 이것이다:

```bash
APP=com.example; D=$(date +%F); R=.simul/reports/nightly; rm -rf $R; mkdir -p $R
simul run --all --mode llm --summary $R/record.json --label record   # 전 시나리오 재녹화 (LLM 키 필요)
simul report                                                        # 방금 녹화한 trace 재생(+녹화) → .simul/reports/<stamp>/ (index.html + run.json) — 이 디렉토리가 아티팩트
V=$(ls -d .simul/reports/[0-9]*/ | sort | tail -1)

J="$R/record.json $V/run.json"        # 없는 파일은 빼도 된다 — 있는 phase만으로 판정
simul report $J --title "simul nightly $D" --link "Run=$RUN_URL" --format md --out $R/report.md   # $GITHUB_STEP_SUMMARY / trace PR 본문
simul report $J --format junit --out $R/junit.xml                                        # 테스트 리포터용
simul report $J --check >/dev/null                                                       # 게이트: regression/trace flake/crash → exit 1
```

각 `simul run`은 실패해도 다음 단계를 막지 않게 돌린다(GitHub Actions면 `continue-on-error: true`).
phase 역할은 라벨이 아니라 **mode와 순서**로 정해진다: 첫 llm 런이 record, 그 직후 replay가 verify(그 외 replay는 열로만 보인다).
`simul report`가 남기는 `run.json`이 verify의 `--summary`와 같은 것이다 — md 리포트는 record의 `--summary` JSON과 그것을 읽는다.
record가 FAILED인 시나리오는 trace를 건드리지 않으므로 verify는 커밋된 trace를 재생한다 — 그 결과가 어떻든 verdict는 regression이다.

**replay가 남기는 것** (`--runs <dir>`; 없으면 종전대로 `.simul/reports/<시나리오>-<stamp>/`에 같은 내용)

```
runs/<app>/<YYYY-MM-DD>/
  run.json                      # 배치: 빌드(appVersionName), 시작/종료 시각, 시나리오 목록 (RunSummary)
  <feature>/<name>.<state>/     # feature = 시나리오 디렉토리, name = md 파일명, state 없으면 <name>/
    result.json                 # 아래 스키마
    steps/01.png … NN.png       # 스텝이 끝난 순간의 화면
    layout/01.json … NN.json    # 그 순간의 layout 트리 (resourceId, bounds, state)
    logcat.txt                  # 시나리오 구간 logcat (threadtime + epoch 타임스탬프)
    replay.mp4                  # 화면 녹화 (--video)
```

`result.json`에는 `title`/`owner`(md frontmatter), `trace_recorded_at`(trace 파일의
마지막 커밋 날짜, 수정 중이면 mtime), `layout_hash`(스텝별 resourceId·bounds 지문), `started_at_ms`,
스텝마다 `started_ms`·`video_ms`·`actions[{tool,target,at_ms,video_ms}]`·`evidence_expected`/`evidence_found`·
`screenshot`·`layout`. `video_ms`는 replay.mp4 안의 위치다 — screenrecord는 시작이 늦고 끝은 정확하므로 mp4의 t=0을
"중단 시각 − 길이(ffprobe)"로 잡아 스텝 시각을 환산한다. setup이 깨진 시나리오는 `SKIPPED_SETUP`(앱 판정 아님).
replay는 액션마다 전후 스크린샷을 찍지 않는다(스텝 끝 프레임 하나 + 녹화); `--mode llm`(녹화)은 종전처럼 찍는다.

**HTML 리포트** — 로컬에서는 `simul report` 한 줄이다: 커밋된 trace를 전부 replay(녹화)하고 `.simul/reports/<stamp>/index.html`을 쓴다.
`simul run --runs <dir>`도 끝나면 같은 디렉토리 안에 페이지를 쓴다(시나리오 디렉토리가 곧 그 페이지의 자산 디렉토리 — mp4는 그 자리, 360px JPEG
스텝 썸네일만 `steps/`에 추가). 다른 곳에 두거나 앱 이름을 붙이려면 `simul report --runs <dir> [--out <dir>] [--title <앱 이름>] [--check]`
(`--out`이면 페이지·mp4·썸네일을 거기로 복사). PASS/FAIL은 result.json 그대로이고 리포트는 다시 판정하지 않는다. `--check`는 FAILED가
하나라도 있으면 exit 1.

- `index.html`: 헤더(앱, 날짜·빌드, 상태 칩 실패/안 돌림/통과) → **실패 카드**(실패 시점 스크린샷·깨진 스텝·reason·owner) + setup 실패 카드
  (`SKIPPED_SETUP`, 앱 판정 아님) → **기능별 상태** 카드(가장 나쁜 상태, 시나리오 수·states) → 통과 한 줄 → 전체 목록(접힘).
- `<feature>/<name>.<state>.html`: 제목·상태 칩·owner, md 설명문, states 탭, **스텝 목록**(실패 스텝만 펼쳐서 기대/실제 evidence, 탭 전후
  2초 logcat; 이후 스텝은 SKIPPED로 흐리게)과 **replay.mp4 플레이어**: 스텝별로 색이 다른 진행 막대, 하단 오버레이 `n/N · 스텝 문장`,
  스텝이나 막대를 누르면 영상이 그 스텝의 `video_ms`로 간다(순수 JS). 녹화가 없으면 스텝 스크린샷이 그 자리에 뜬다.

**record/verify 리포트** (`simul report <summary.json>...`)

두 결과가 시나리오당 하나의 verdict로 접힌다:

| record | verify | verdict | 뜻 |
|---|---|---|---|
| ✅ | ✅ | ✅ stable | 오늘 앱에서 녹화되고 그 trace가 재생됨 → trace PR 리뷰·머지 |
| ❌ | – | ❌ regression | 에이전트가 오늘 앱에서 시나리오를 못 함 → 앱 회귀 의심(또는 시나리오 문구), record 스크린샷·agent.jsonl부터. 커밋 trace는 그대로 |
| ✅ | ❌ | ⚠️ trace flake | 녹화는 됐는데 재생 불가 → 비결정 evidence(동적 라벨) |
| ⏭ | ✅/❌ | ✅/❌ | 녹화 안 함(스텝 없음 등) → 재생 결과 그대로 |

`--check`는 regression/trace flake/crash에서 exit 1.
record가 다시 쓴 `.simul/scenarios/**/*.trace.json`은 워크플로가 PR로 올리고(예: peter-evans/create-pull-request,
본문에 `report.md`) 사람이 diff를 보고 머지한다.

**러너 요구사항** (self-hosted — 호스티드 러너엔 에뮬레이터도 LLM도 없다)

- PATH에 `simul`(이 레포 `./gradlew installDist` + `scripts/simul`), `adb`·`emulator`·`android`(CLI), `ffprobe`/`ffmpeg`(녹화
  타임라인 보정·180s 세그먼트 이어붙임용, 선택)
- 에뮬레이터 1대: 매번 콜드 부팅·스냅샷 저장 안 함(기기 드리프트가 앱 회귀로 둔갑하지 않게), `ANDROID_SERIAL` 고정.
  화면은 config.yaml `display:`가, 자격 증명 제공자·자동완성 같은 기기 설정은 `device.settings`가 실행 중
  강제하므로 AVD 해상도·설정은 무관. 단 "저장된 비밀번호" 시트처럼 Google 계정 데이터가 전제인 시나리오는
  그 데이터가 AVD 이미지에 있어야 한다
- debug 빌드 설치(WebView 채널이 debuggable 전제), `.simul/config.yaml llm.url`에 닿는 OpenAI 호환 LLM —
  키는 `llm.apiKey: ${VAR}` + 러너 시크릿. **replay 두 단계는 키 없이 돈다**
- 로그인 등 상태 스냅샷(`.simul/states/*.tar`, gitignored)은 러너 로컬 디렉터리에서 체크아웃 뒤 복사
- 끝나면 `adb emu kill`; 아티팩트로 `report.html` + `report_files/`(받아서 열면 끝)와 원본 `runs/<app>/<date>/`

## 구조 (`dev.nate.uiagent`)

| 패키지 | 역할 |
|---|---|
| (root) | `Core.kt` 공용 타입(geometry, `LogicalElement/Layout`, JSON 헬퍼, `Defaults`), `LayoutAdapter.kt` dump(flat/트리)→논리 요소 트리 병합, `Trace.kt` JSONL 트레이스, `Process.kt`, `Format.kt` 시간 포맷 |
| `device` | `Device`/`AdbDevice` 관찰·제스처, `DeviceController` grounding·가드·diff 검증·wait-for-stable(+replay용 fastObserve), `Layout.kt` 모델용 렌더링/diff, `SwipeGeometry`, `AdbOps` 러너용 adb 조작(setup/스크린샷/wm/settings), `DisplayProfile`, `DeviceSettings` |
| `agent` | Koog 1.3 기반 LLM 스택: `Llm.kt` OpenAI-호환 executor/모델/고정 파라미터, `Tools.kt` 디바이스 툴 + 기준 게이트, `Session.kt` GraphAIAgent + ChatMemory(시나리오당 단일 대화), `Prompt.kt` 시스템 프롬프트, `LlmStats.kt` 모델 비용 집계 |
| `cli` | `simul` CLI(`Cli`/`RunCommand`/`InitCommand`/`Adhoc`), `.simul/` 규약(`Project`/`Config`), `BatchSession`(CLI·TUI 공용 display/settings 핀), 시나리오 러너(스텝 격리·라이브 로그), Record & Replay(`TraceFormat`/`Recorder`/`Evidence`/`Replayer`), 리포트(`ResultJson`/`LayoutJson`/`RunSummary`/`Report`/`Site`/`SitePages`), `Tui` |
| `web` | WebView 관찰 채널: `CdpClient`(devtools socket discover + Runtime.evaluate), `WebLayout`(요소 추출 JS·좌표 변환), `WebAwareDevice`(관찰 병합), `DeviceStack`(디바이스 스택 조립) |

핵심 설계 불변:

- **모델에 좌표 비노출** (라벨/synthetic handle grounding — 좌표 환각 방지)
- **모델은 웹/네이티브를 구분하지 않는다** — 관찰 병합·좌표 변환은 전부 harness가 흡수
- **replay는 LLM 0회 (예외 없음)** — resourceId→label→좌표 재grounding + evidence(따옴표 라벨 +
  신규 노드 resourceId 시그니처 + selected/checked 상태) 판정. 깨진 스텝은 FAILED — 화면이 실제로
  바뀐 것이므로 재녹화(--mode llm)냐 앱 수정이냐는 사람이 결정. fastObserve(단일 관찰 + 미스 시
  target/evidence가 보일 때까지 폴링 — settle 확인 없음)로 액션당 관찰 1회
- 트레이스는 결정론 직렬화(고정 키 순서, 휘발값 배제) — 같은 실행 2회면 diff 없음
- **display 프로필 강제** — config의 `display:`(sizeClass `small|medium|large` — 대상 앱 디자인
  시스템(경계 600/800dp) 정렬 — 또는 size+density 명시)를
  실행 동안 `wm size/density`로 강제해 어떤 에뮬레이터에서든 같은 size class로 trace를 재생.
  에뮬레이터에서만 동작(실기기는 경고 후 무시), 종료 시 이전 상태 복원. 녹화 trace에
  `recordedWith.display` 스탬프, replay 시 불일치 경고
- **기기 설정 핀** — config의 `device.settings`(`<namespace>.<key>: value`, `null`=삭제)를 실행 동안
  `settings put`으로 강제하고, 시나리오 `setup.device.settings`는 그 시나리오의 launch 전에 덮고
  끝나면 기본값으로 되돌린다(`DeviceSettings`). 앱 데이터가 아닌 전제(Google 자격 증명 제공자 →
  "Use your saved password" 시트, 자동완성, 전체화면 안내)를 모델이 설정 앱을 조작하는 대신 adb로
  결정론적으로 맞춘다. display와 같이 에뮬레이터 전용·변경분만·종료 시 복원
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

