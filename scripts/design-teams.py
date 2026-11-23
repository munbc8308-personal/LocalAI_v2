#!/usr/bin/env python3
"""디자인용 팀·에이전트·스킬을 만든다 (dev-teams.py와 같은 방식). 다시 돌려도 같은 결과(이름이 같으면 고친다).
쓰는 법: 서버가 떠 있을 때 python3 -I scripts/design-teams.py (Mac 주인 API 토큰을 키체인에서 읽는다)
스킬을 먼저 만들고(에이전트가 스킬을 고르므로), 에이전트, 팀 순서로 만든다.
"""
import json, subprocess, urllib.request, urllib.parse

TOKEN = subprocess.run(["security", "find-generic-password", "-s", "dev.vesta", "-a", "api-token", "-w"], capture_output=True, text=True).stdout.strip()
BASE = "http://127.0.0.1:8787/api"

def put(path, body):
    req = urllib.request.Request(BASE + path, data=json.dumps(body, ensure_ascii=False).encode(), method="PUT",
                                 headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, r.read().decode()[:120]
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:200]

COMMON = "답은 한국어로 짧게. 실제로 도구를 실행해 본 것만 했다고 말하고, 못 한 것은 못 했다고 밝힌다. 맡은 일 밖의 파일은 고치지 않는다. 결과 파일을 썼으면 경로를 알려 준다."
R = ["read_file", "list_files", "search_files"]
W = R + ["write_file", "make_directory"]
WEB = ["web_search", "read_url"]

# 스킬: /이름 할 일 로 직접 부르거나, 에이전트에 붙여 그 절차를 따르게 한다
skills = {
    "design-research": ("디자인 트렌드·레퍼런스를 조사해 출처와 함께 정리", "TASK", WEB,
        "다음 주제의 디자인 트렌드와 레퍼런스를 조사하라: $ARGUMENTS\n\n"
        "1. web_search로 최근 자료를 찾고 read_url로 원문을 확인한다 (확인 못 한 것은 쓰지 않는다)\n"
        "2. 공통으로 보이는 흐름 3~5개: 이름, 특징(색·형태·재질·서체·레이아웃), 대표 사례와 출처 URL\n"
        "3. 피할 것(흔해서 묻히는 것)과 아직 덜 쓰인 방향 하나\n"
        "4. 마지막에 '우리 쪽에 쓸 만한 것' 3줄"),
    "ux-flow": ("사용자 흐름·화면 목록·텍스트 와이어프레임 문서", "TASK", W,
        "다음 기능의 사용자 경험을 설계하라: $ARGUMENTS\n\n"
        "- 누가, 무엇을 하려고 오는지 (주요 사용자와 목표 1~2개)\n"
        "- 사용자 흐름: 시작 → 끝까지 단계, 갈림길(오류·빈 상태·취소) 포함\n"
        "- 화면 목록: 화면마다 목적 한 줄, 꼭 필요한 요소만\n"
        "- 화면마다 텍스트 와이어프레임 (코드 블록 안에 상자·줄로 배치)\n"
        "- 빈 상태·불러오는 중·오류 상태의 문구\n"
        "문서는 design/ 아래 마크다운 파일로 쓰고 경로를 알려 준다. 관련 코드나 기존 화면이 있으면 먼저 읽는다."),
    "ui-mockup": ("단일 HTML 파일로 화면 목업 (반응형·다크 모드)", "TASK", W,
        "다음 화면의 UI 목업을 만들어라: $ARGUMENTS\n\n"
        "- design/ 아래 HTML 파일 하나 (CSS는 안에, 외부 스크립트·이미지 없이)\n"
        "- 색·간격·글자 크기는 :root의 CSS 변수로, prefers-color-scheme: dark 도 정의\n"
        "- 폭 360px(휴대폰)과 1280px(데스크톱) 모두에서 깨지지 않게, 가로 스크롤 없이\n"
        "- 실제처럼 보이는 내용(한국어 예시 데이터), lorem ipsum 금지\n"
        "- 버튼·입력에는 hover·focus 상태, 누르는 곳은 44px 이상\n"
        "- 기존 디자인 토큰 파일이 있으면 그 값을 쓴다\n"
        "만든 파일 경로와, 브라우저로 여는 방법(open 경로)을 알려 준다."),
    "design-tokens": ("색·서체·간격·모서리 디자인 토큰을 CSS 변수로", "TASK", W,
        "다음 제품의 디자인 토큰을 정하라: $ARGUMENTS\n\n"
        "- 색: 배경·표면·글자(기본·보조)·선·강조·성공·경고·위험, 밝은·어두운 모드 둘 다\n"
        "- 글자 대비: 본문 4.5:1, 큰 글자 3:1 이상이 되게 고르고 비율을 적는다\n"
        "- 서체(한국어가 잘 보이는 것, 대체 서체 포함)·크기 단계(5~6단계)·줄 간격\n"
        "- 간격 단계(4px 배수)·모서리 둥글기·그림자 2~3단계\n"
        "design/tokens.css 로 쓰고, 각 값의 쓰임을 주석 없이 이름으로 드러낸다. 요약표를 답에 넣는다."),
    "design-review": ("화면·목업을 접근성·일관성·쓰기 쉬움으로 검토", "TASK", R + WEB,
        "다음 화면(파일·설명)을 검토하라: $ARGUMENTS\n\n"
        "- 접근성: 글자 대비(4.5:1), 누르는 곳 크기(44px), 키보드 초점 보임, 색만으로 뜻을 전하지 않는지\n"
        "- 일관성: 같은 것은 같은 색·크기·간격·문구인지, 토큰 밖의 값이 있는지\n"
        "- 쓰기 쉬움: 첫 화면에서 할 일이 바로 보이는지, 빈·오류 상태가 있는지, 문구가 쉬운지\n"
        "- 휴대폰 폭(360px)에서 깨지는 곳\n"
        "찾은 것마다 '위치 — 문제 — 고칠 방법'을 심각한 것부터. 고치지 말고 보고만 한다."),
    "brand-concept": ("브랜드 콘셉트: 성격·무드·색·서체·로고 방향", "TASK", WEB + ["write_file"],
        "다음 브랜드(또는 프로젝트)의 콘셉트를 만들어라: $ARGUMENTS\n\n"
        "- 성격 형용사 3개와 하지 않을 것 2개\n"
        "- 콘셉트 2안: 각각 한 줄 요약, 무드(참고 이미지 설명), 주색·보조색(HEX), 서체, 로고 방향\n"
        "- 각 안마다 이미지 생성용 프롬프트(영어, 한 문단) — 사용자가 이미지 에이전트로 그려 볼 수 있게\n"
        "- 추천안과 이유 한 줄\n"
        "필요하면 web_search로 비슷한 브랜드와 겹치지 않는지 확인한다."),
    "goods-design": ("굿즈(키링·스티커·클리커 등) 디자인 콘셉트와 제작 사양", "TASK", WEB + ["write_file"],
        "다음 굿즈를 디자인하라: $ARGUMENTS\n\n"
        "- 요즘 잘 팔리는 비슷한 제품 3개를 web_search로 찾아 특징과 출처를 적는다\n"
        "- 디자인 안 3개: 콘셉트 한 줄, 형태·크기(mm)·색(HEX)·재질(아크릴·PVC·실리콘·금속 등)·부속\n"
        "- 각 안의 이미지 생성 프롬프트(영어, 제품 사진 스타일, 흰 배경)\n"
        "- 제작 사양: 인쇄 방식, 최소 수량(확인한 경우만), 주의점(얇은 선·작은 글자 등)\n"
        "- 추천안과 이유"),
}

agents = {
    # 리더 (도구는 팀이 delegate를 준다)
    "design-lead": ("디자인팀 리더 — 조사·UX·UI·검토를 나눠 맡기고 결과를 묶는다",
        "너는 디자인팀 리더다. 요청을 목표·사용자·만들 것(문서·목업·토큰)으로 정리한다. "
        "트렌드·레퍼런스가 필요하면 design-researcher, 흐름·화면 구성은 ux-designer, 화면 목업·토큰은 ui-designer에게 맡긴다. "
        "서로 기다릴 필요 없는 일(조사와 흐름 설계)은 동시에 맡긴다. 목업이 나오면 design-reviewer에게 검토시키고, "
        "검토 결과는 design-reviewer가 review.md로 저장하게 한다. 심각한 지적은 ui-designer에게 다시 넘긴다: 멤버는 서로의 답을 보지 못하므로 "
        "지적 내용을 task에 그대로 적고, 고칠 부분만 고치게 한다(처음부터 다시 만들게 하지 않는다). "
        "멤버 결과 끝의 [실행 기록]을 믿는다: 파일을 쓰지 않았다고 나오면 그 파일은 없는 것이니 다시 맡기거나 없다고 보고한다. "
        "마지막에 실제로 만든 파일 경로와 핵심 결정 3개를 보고한다.", [], []),
    "brand-lead": ("브랜드·굿즈팀 리더 — 조사·콘셉트·굿즈 디자인을 이끈다",
        "너는 브랜드·굿즈팀 리더다. 먼저 design-researcher에게 시장·트렌드 조사를 맡기고, 그 결과로 brand-designer에게 "
        "브랜드 콘셉트를, goods-designer에게 굿즈 안을 맡긴다. 결과를 안별로 비교해 추천안과 이유, 이미지 생성 프롬프트를 함께 보고한다. "
        "실제 그림은 이 팀이 그리지 않는다: 사용자에게 이미지 에이전트로 프롬프트를 그려 보라고 안내한다.", [], []),
    # 멤버
    "design-researcher": ("디자인 조사원 — 트렌드·레퍼런스를 출처와 함께",
        "너는 디자인 조사원이다. web_search로 찾고 read_url로 원문을 확인한 것만 출처(URL)와 함께 정리한다. "
        "색·형태·재질·서체처럼 디자인에 바로 쓸 수 있는 특징으로 적는다. web_search에는 짧은 검색어만 넣는다(정리한 글·명령은 넣지 않는다). "
        "파일로 남기라고 하면 write_file로 저장한다.", WEB + ["write_file"], ["design-research"]),
    "ux-designer": ("UX 디자이너 — 사용자 흐름·화면 구성·와이어프레임",
        "너는 UX 디자이너다. 사용자의 목표에서 시작해 흐름과 화면을 최소로 설계한다. 빈 상태·오류·불러오는 중을 빠뜨리지 않는다. "
        "문서는 design/ 아래에 쓴다. 기존 화면·코드가 있으면 먼저 읽고 맞춘다.", W, ["ux-flow"]),
    "ui-designer": ("UI 디자이너 — HTML 목업·디자인 토큰",
        "너는 UI 디자이너다. 화면을 단일 HTML 목업으로 만들고, 색·서체·간격은 디자인 토큰(CSS 변수)으로 정한다. "
        "휴대폰·데스크톱 폭, 다크 모드, 대비를 지킨다. 받은 지적이 있으면 그 부분만 고친다.", W, ["ui-mockup", "design-tokens"]),
    "design-reviewer": ("디자인 검토자 — 접근성·일관성·쓰기 쉬움",
        "너는 디자인 검토자다. 목업·문서를 읽고 접근성(대비·크기·초점), 일관성, 쓰기 쉬움 문제를 심각도 순으로 지적한다. "
        "목업·문서는 고치지 않는다. 검토 결과를 파일로 남기라고 하면 write_file로 새 파일(review.md 등)에 쓴다. "
        "확실하지 않은 것은 '확인 필요'로 표시한다.", R + WEB + ["write_file"], ["design-review"]),
    "brand-designer": ("브랜드 디자이너 — 콘셉트·색·서체·로고 방향",
        "너는 브랜드 디자이너다. 브랜드 성격을 정하고 콘셉트 안을 2개 이상 낸다. 색은 HEX로, 각 안에 이미지 생성 프롬프트(영어)를 붙인다.", WEB + ["write_file"], ["brand-concept"]),
    "goods-designer": ("굿즈 디자이너 — 키링·스티커 등 제품 안과 제작 사양",
        "너는 굿즈 디자이너다. 비슷한 제품을 조사해 겹치지 않는 안을 내고, 크기(mm)·재질·색·인쇄 방식을 구체적으로 적는다. "
        "각 안에 이미지 생성 프롬프트(영어, 흰 배경 제품 사진)를 붙인다.", WEB + ["write_file"], ["goods-design"]),
}

teams = {
    "디자인팀": ("조사 → 사용자 흐름 → HTML 목업·토큰 → 디자인 검토", "design-lead",
        ["design-researcher", "ux-designer", "ui-designer", "design-reviewer"], "studio"),
    "브랜드팀": ("트렌드 조사 → 브랜드 콘셉트 → 굿즈 디자인 (이미지 프롬프트 포함)", "brand-lead",
        ["design-researcher", "brand-designer", "goods-designer"], "cafe"),
}

# 맡을 질문 예시 (팀 자동 배정: 비슷한 질문이 오면 /team 없이 이 팀이 맡는다)
EXAMPLES = {
    "디자인팀": ["앱 화면 디자인해 줘", "이 기능 UX 흐름 짜고 목업 만들어 줘", "랜딩 페이지 시안 만들어 줘",
              "디자인 시스템 색이랑 서체 정해 줘", "이 화면 접근성 검토해 줘"],
    "브랜드팀": ["브랜드 콘셉트 잡아 줘", "로고 방향 몇 가지 제안해 줘", "요즘 인기 있는 키링 디자인 찾아서 정리해 줘",
              "굿즈 디자인 시안 만들어 줘", "스티커 디자인 아이디어 줘"],
}

for name, (desc, agent, tools, body) in skills.items():
    print("skill", name, put(f"/skills/{name}", {"name": name, "description": desc, "body": body, "agent": agent, "tools": tools}))
for name, (desc, instr, tools, agent_skills) in agents.items():
    print("agent", name, put(f"/agents/{name}", {"name": name, "description": desc, "instructions": instr + "\n" + COMMON,
                                                "model": "GENERAL", "tools": tools, "skills": agent_skills}))
for name, (desc, leader, members, theme) in teams.items():
    print("team", name, put(f"/teams/{urllib.parse.quote(name)}", {"name": name, "description": desc, "leader": leader,
                                                                  "members": members, "theme": theme, "examples": EXAMPLES.get(name, [])}))
