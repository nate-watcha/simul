# simul — layout 기반 Android 자연어 UI 테스트 러너

자연어 커맨드/시나리오를 받아 연결된 에뮬레이터에서 수행한다.

- **판단**: 로컬 llama-server(OpenAI 호환) + 자체 tool-calling 루프. 판정 근거는 액션이 만든 LAYOUT DIFF
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
simul list                       # 시나리오 목록 + 트레이스 유무/신선도
simul state save <name>          # 현재 앱 데이터를 .simul/states/<name>.tar 스냅샷으로 저장
                                 #   → 시나리오의 setup.appState: <name>으로 로그인 등 전제 주입
simul "Tap the \"웹툰\" tab"     # ad-hoc 단일 커맨드 (규약 없이 동작)

./gradlew runAgent --args='"설정 탭을 눌러"'   # 개발용 ad-hoc 직접 실행
```

종료 코드: 전부 PASSED=0, 실패=1.

## 구조 (`dev.nate.uiagent`)

| 패키지 | 역할 |
|---|---|
| (root) | `Core.kt` 공용 타입(geometry, `LogicalElement/Layout`), `LayoutAdapter.kt` flat dump→논리 요소 병합, `Trace.kt` JSONL 트레이스, `Process.kt` |
| `device` | `Device`/`AdbDevice` 관찰·제스처, `DeviceController` grounding·가드·diff 검증·wait-for-stable(+replay용 fastObserve), `Layout.kt` 모델용 렌더링/diff, `SwipeGeometry` |
| `agent` | 자체 LLM 스택: `Chat.kt` OpenAI-호환 클라이언트, `Session.kt` 시나리오당 단일 대화 + tool 디스패치, `Agent.kt` 시스템 프롬프트, ad-hoc `main` |
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

