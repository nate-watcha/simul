# simul — layout 기반 Android 자연어 UI 테스트 러너

자연어 커맨드/시나리오를 받아 연결된 에뮬레이터에서 수행한다.

- **판단**: OpenAI 호환 엔드포인트(기본은 로컬 llama-server, `llm.apiKey`로 호스티드 API도 가능) +
  자체 tool-calling 루프. 판정 근거는 액션이 만든 LAYOUT DIFF
- **관찰**: `android layout` 출력 + WebView 내부는 CDP로 병합 (스크린샷/vision 없음)
- **조작**: `adb shell input ...` — 웹 요소도 병합 시 화면 좌표로 변환되어 동일 경로

## 사용

앱 레포가 `.simul/` 규약(config.yaml + scenarios/*.md)을 갖추면 CLI로 실행한다.
시나리오 작성법·워크플로·트러블슈팅은 init이 설치하는
**`.claude/skills/simul-scenarios/references/scenarios.md`** 참조. 내부 구조·리포트 형식·설계 불변은 `CLAUDE.md`.

```bash
./gradlew installDist            # → build/install/simul/bin/simul
ln -s "$(pwd)/scripts/simul" ~/.local/bin/simul   # PATH 래퍼

simul                            # 개발자는 이것 하나: 시나리오 목록/실행/녹화/상태 저장/ad-hoc REPL (TUI)
simul init --app com.example     # 타겟 레포에 .simul/ 스캐폴드 + 작성 가이드 + Claude 스킬 설치
simul "Tap the \"웹툰\" tab"     # ad-hoc 단일 커맨드 (규약 없이 동작)

./gradlew runAgent --args='"설정 탭을 눌러"'   # 개발용 ad-hoc 직접 실행
```

그 외 커맨드는 나이틀리(아래) 전용이다. 종료 코드: 전부 PASSED=0, 실패=1.

### 나이틀리 운영

나이틀리는 `simul report` 한 줄이다. LLM이 전 시나리오를 다시 녹화하고(산출물은 trace), 그 trace를 LLM 없이 replay하면서(그래서 빠르다)
**HTML 리포트**를 쓴다. 녹화가 실패한 시나리오(에이전트가 오늘 앱에서 못 했거나 LLM 게이트웨이 오류)는 커밋된 trace가 재생되더라도
리포트에 **실패**로 올라오고, 그 페이지는 녹화의 타임라인(통과한 스텝, 멈춘 스텝에서 에이전트가 한 액션과 그때마다의 화면, 이후 SKIPPED)과 녹화 영상을 보여준다 —
커밋 trace의 재생은 그 아래 접혀 있다. 워크플로 파일은 **앱 레포가 가진다** — 이 레포는 커맨드만 제공하고, 조립 순서는 이것이다:

```bash
simul report                                   # 녹화 → 재생 → .simul/reports/<stamp>/ (index.html + record.json + record/ + run.json) — 이 디렉토리가 아티팩트
V=$(ls -d .simul/reports/[0-9]*/ | sort | tail -1)
simul report $V/record.json $V/run.json --title "simul nightly $(date +%F)" --link "Run=$RUN_URL" --format md --out $V/report.md   # $GITHUB_STEP_SUMMARY / trace PR 본문
simul report $V/record.json $V/run.json --format junit --out $V/junit.xml   # 테스트 리포터용
simul report $V/record.json $V/run.json --check >/dev/null                  # 게이트: regression/trace flake/crash → exit 1
```

`simul report`는 실패해도 종료 코드만 1이고 리포트는 쓴다(GitHub Actions면 `continue-on-error: true`). 녹화가 다시 쓴
`.simul/scenarios/**/*.trace.json`은 워크플로가 PR로 올리고(본문에 `report.md`) 사람이 diff를 보고 머지한다.

새로 쓴 시나리오 하나를 나이틀리에 넣기 전에 돌려 보는 것도 같은 커맨드다: `simul report auth/login.md`는 그 파일만 녹화 → 재생하고
마지막 줄에 그 시나리오의 상세 페이지(`.simul/reports/<stamp>/auth/login.html`, `states:`면 상태마다 하나)를 찍는다 — 요청한 사람에게
돌려줄 피드백이 그 페이지(와 같은 디렉토리의 `replay.mp4`·스텝 스크린샷)다. `record.json`/`run.json`도 같이 남으므로 `--format slack`으로
Slack 메시지 블록을 뽑을 수 있다.

**러너 요구사항** (self-hosted — 호스티드 러너엔 에뮬레이터도 LLM도 없다)

- PATH에 `simul`(이 레포 `./gradlew installDist` + `scripts/simul`), `adb`·`emulator`·`android`(CLI), `ffprobe`/`ffmpeg`(녹화
  타임라인 보정·180s 세그먼트 이어붙임용, 선택)
- 에뮬레이터 1대: 매번 콜드 부팅·스냅샷 저장 안 함(기기 드리프트가 앱 회귀로 둔갑하지 않게), `ANDROID_SERIAL` 고정.
  화면은 config.yaml `display:`가, 자격 증명 제공자·자동완성 같은 기기 설정은 `device.settings`가 실행 중
  강제하므로 AVD 해상도·설정은 무관. 단 "저장된 비밀번호" 시트처럼 Google 계정 데이터가 전제인 시나리오는
  그 데이터가 AVD 이미지에 있어야 한다
- debug 빌드 설치(WebView 채널이 debuggable 전제), `.simul/config.yaml llm.url`에 닿는 OpenAI 호환 LLM —
  키는 `llm.apiKey: ${VAR}` + 러너 시크릿
- 앱의 목적지 액티비티(런치 홈·딥링크가 도착하는 곳)가 첫 프레임에 `reportFullyDrawn()`을 부른다(예: `onCreate`의
  `doOnPreDraw`) — setup은 그 `Fully drawn` 신호를 기다려 첫 스텝을 시작한다(스플래시·광고 인터스티셜 액티비티 위에서 관찰하지
  않게). 런치당 첫 호출만 유효하다. 신호가 15s 안에 없으면 경고만 남기고 진행한다
- 로그인 등 상태 스냅샷(`.simul/states/*.tar`, gitignored)은 러너 로컬 디렉터리에서 체크아웃 뒤 복사
- 끝나면 `adb emu kill`; 아티팩트는 `.simul/reports/<stamp>/` 디렉토리 통째(받아서 `index.html`을 열면 끝)

## 빌드 & 테스트

```bash
./gradlew test          # 유닛 테스트 (device/cli/web 전 계층, fake 기반)
./gradlew installDist   # simul 바이너리
```

## 제약 / 스코프 밖

- **비ASCII 타이핑 미지원**(`adb shell input text` 한계) → 즉시 FAILED
- WebView 관찰은 debug 빌드(`setWebContentsDebuggingEnabled`) 전제, cross-origin iframe 미지원
- 스크린샷/vision 판단, 웹 성능 계측 — 스코프 밖

