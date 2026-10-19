# simul nightly 리포트 — 구현 스펙

nightly replay 결과를 모든 직군이 한눈에 보는 정적 사이트와 Slack 요약. 세 층으로 되어 있고,
`mockups/`의 HTML 세 장이 각 층의 레이아웃 기준이다. 색·여백은 참고만 하고 구조와 우선순위를
따른다.

## 원칙

1. **달라진 것이 먼저.** 모든 층에서 실패 → 화면 바뀜 → 재녹화 필요 순으로 보이고, 통과는 한 줄로
   접는다. 초록 41개를 나열하지 않는다.
2. **스크린샷은 사람용, 판정은 트리.** PASS/FAIL은 replay의 evidence 게이트가 이미 정한다.
   리포트는 그 결과를 보여 줄 뿐 다시 판정하지 않는다. "화면 바뀜"도 픽셀 diff가 아니라
   layout 트리의 구조 변화(resourceId 집합, bounds)로 잡는다.
3. **서버 없음.** CI 아티팩트에서 정적 HTML을 생성해 GitHub Pages 또는 사내 호스팅에 올린다.
   날짜별 디렉토리를 쌓고, 어제 비교는 디렉토리 비교로 한다.
4. **통과 상태의 상세 페이지는 그 기능의 "현재 동작 문서"다.** 시나리오 .md의 설명문과 영상이
   같이 보여야 한다.

## 입력 (simul이 이미 남기는 것 / 추가로 남겨야 하는 것)

한 nightly 실행이 끝나면 아래 구조가 있어야 한다. 없는 것은 simul 쪽에 추가한다.

```
runs/<app>/<YYYY-MM-DD>/
  run.json                      # 빌드, 시작/종료 시각, 에뮬레이터 수, 시나리오 수
  <feature>/<name>.<state>/
    result.json                 # 아래 스키마
    steps/01.png … 05.png       # 스텝 실행 후 스크린샷 (replay가 매 스텝 저장)
    layout/01.json … 05.json    # 스텝 실행 후 layout 트리
    logcat.txt                  # 실행 구간 logcat (threadtime + epoch)
    replay.mp4                  # 화면 녹화 (`--video`). gif 대신 mp4: result.json의 스텝·액션마다
                                # `video_ms`(mp4 안의 위치)가 있어 페이지에서 스텝을 누르면 그 장면으로 간다
```

실제 구현 메모(2026-10-19): `simul run --runs <dir>`가 위 구조를 그대로 남긴다(`<name>`은 md 파일명, state 없으면
`<name>/`). result.json은 위 스키마에 `title`·`owner`(frontmatter), `layout_hash`, `started_at_ms`, 스텝별
`started_ms`·`video_ms`·`actions[]`·`screenshot`·`layout`을 더한다. 자세한 건 README "나이틀리 운영".

`result.json`:

```json
{
  "scenario": "coupon-register",
  "feature": "billing",
  "state": "member",
  "file": "billing/coupon.md",
  "owner": "nate",
  "description": "설정에서 쿠폰 메뉴로 … (md 본문)",
  "trace_recorded_at": "2026-10-14",
  "status": "FAILED",            // PASSED | FAILED | SKIPPED_SETUP
  "failed_step": 4,
  "duration_ms": 9200,
  "steps": [
    {"n": 1, "text": "Tap the \"설정\" tab", "criterion": null,
     "status": "PASSED", "duration_ms": 800,
     "evidence_expected": ["+screen:settings"], "evidence_found": ["+screen:settings"]},
    {"n": 4, "text": "Tap the \"btn_apply\" button",
     "criterion": "the \"coupon_list_item\" item should be shown",
     "status": "FAILED", "reason": "evidence not on screen",
     "evidence_expected": ["+coupon_list_item", "+toast_success", "-input_coupon"],
     "evidence_found": ["+error_banner"]}
  ]
}
```

## 생성 단계 (report 빌더)

입력: `runs/<app>/<today>/`, `runs/<app>/<yesterday>/`, 최근 30일의 `result.json`.
출력: `site/<app>/<today>/index.html`, `site/<app>/<today>/<feature>/<name>.<state>.html`,
`site/<app>/index.html`(최신으로 리다이렉트), Slack 메시지 JSON.

1. **상태 분류** (시나리오·상태 단위)
   - `FAILED`: result.status가 FAILED.
   - `화면 바뀜`: PASSED인데, 어제 run의 같은 시나리오와 스텝별 layout을 비교해 resourceId
     집합이 다르거나 같은 resourceId의 bounds가 임계값(예: 8px) 이상 움직인 스텝이 있음.
     어제 run이 없으면 분류하지 않는다. 텍스트·콘텐츠 변화는 무시한다.
   - `재녹화 필요`: 화면 바뀜이 "의도한 변경"으로 표시됐는데(아래 상태 파일) 그 뒤로
     trace_recorded_at이 갱신되지 않음.
   - `통과`: 나머지 PASSED.
2. **"의도한 변경" 표시**는 저장소 안의 파일로 관리한다: `report-state/<app>/acknowledged.json`
   `{"<feature>/<name>.<state>": {"at": "2026-10-17", "by": "designer", "layout_hash": "…"}}`.
   리포트의 버튼은 이 파일을 고치는 PR 링크(또는 GitHub issue 템플릿 링크)로 충분하다.
   동적 백엔드를 만들지 않는다.
3. **영상**: gif를 만들지 않는다. replay가 남긴 `replay.mp4`를 그대로 쓰고, 스텝/액션의 `video_ms`로
   타임라인을 만든다(스텝 색 진행 막대, 스텝 클릭 → 그 시각으로 seek). 녹화가 없으면 `steps/*.png`.
4. **7일 추이**: 기능 카드와 상세 페이지에 최근 7일 상태를 칸 7개로.
5. **Slack**: 아래 "1층" 형식의 Block Kit JSON을 생성해 webhook으로 보낸다.
   실패·재녹화 줄에는 owner 멘션.

## 1층 — Slack (`mockups/1-slack.html`)

```
WatchaPlay nightly · 10/19 · 4.21.0-nightly.1019
실패 2        billing/쿠폰 등록 · player/이어보기   @nate @clark
화면 바뀜 3   구독 안내 · 보관함 빈 상태 · 설정     ← 디자인 확인
재녹화 필요 1 settings/알림                        @joseph
통과 41 · 38분 · 에뮬레이터 3대
[리포트 보기] [어제와 비교]
```

실패 0, 화면 바뀜 0이면 그 줄을 생략하고 "전부 통과 46" 한 줄.

## 2층 — 리포트 첫 화면 (`mockups/2-report-home.html`)

위에서 아래로:

1. 헤더: 앱 탭(3개), 날짜·빌드, 상태 칩 4개(실패/화면 바뀜/재녹화/통과).
2. **어제와 다른 것**: 실패 카드(실패 시점 스크린샷, 시나리오 제목, 깨진 스텝 문장과 reason,
   owner, 상세 링크) → 화면 바뀜 카드(어제/오늘 스크린샷 나란히, 무엇이 바뀌었는지 한 줄,
   "의도한 변경"·"이슈로 보내기" 링크) → 재녹화 카드(사유, 명령어 한 줄).
3. **기능별 상태**: feature 디렉토리마다 카드 하나. 이름, 가장 나쁜 상태 칩, 시나리오 수와
   states, 7일 칸, 마지막 전부 통과 날짜. 기본 8개 노출, 나머지 접기.
4. 통과 요약 한 줄(소요 시간, 에뮬레이터 수, 전체 목록 링크).

## 3층 — 시나리오 상세 (`mockups/3-scenario-detail.html`)

1. 헤더: 경로(앱 / feature / name), 제목(md 설명문의 첫 문장이 아니라 scenario의 사람용
   제목 — md frontmatter에 `title`이 없으면 name), 상태 칩 `FAILED · 4/5`, owner, trace 녹화일.
2. md 설명문 전문.
3. states 탭(member/nosub/guest), 각 탭에 그 상태의 결과 색 점. 7일 칸. md 파일 링크.
4. 본문 2단:
   - 왼쪽 **스텝 목록**: 스텝마다 상태 아이콘, 문장(`::` 기준은 줄 바꿔 회색), 자동 evidence
     요약과 소요 시간. 실패 스텝만 펼쳐서 기대/실제 evidence 두 칸, 탭 전후 2초 logcat,
     재녹화 명령어, 이슈 링크. 실패 이후 스텝은 SKIPPED로 흐리게.
   - 오른쪽 **mp4 플레이어**: 9:16 프레임, 하단 오버레이에 `n/N · 스텝 문장`, 스텝 단위로 색이
     다른 진행바, 재생 버튼, 어제 영상 보기, 다운로드.
5. 스텝 클릭 → 영상을 그 스텝의 `video_ms`로. 순수 JS로 충분(`data-video-ms` 속성).

## 범위 밖

- 디자인 QA(색, 간격, 문구). 스크린샷은 사람이 보는 용도.
- 실시간 갱신, 로그인, 댓글. 정적 사이트다.
- replay 판정 로직 변경. 리포트는 result.json을 읽기만 한다.

## 먼저 할 일 순서

1. simul replay가 스텝별 png/layout/logcat과 result.json을 위 구조로 남기게 한다.
2. 빌더: result.json → 3층 상세 페이지 한 장. 통과 시나리오 하나로 먼저.
3. 빌더: 2층 첫 화면. 어제 비교와 화면 바뀜 분류.
4. (gif 없음 — mp4 타임라인은 2에 포함)
5. Slack 메시지와 CI 연결.
