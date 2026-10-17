"""라우팅 판정 방식 비교 (PROJECT.md M4). 결정 모델(Ollaya)과 메인 모델(JSON 판정)의 D1·D2·D3 정확도와 지연을 잰다.

사용:
  python3 eval/routing_explore.py ollaya <모델> <en|ko>     예) ollaya laya en
  python3 eval/routing_explore.py llm <ollama 모델>          예) llm gemma4:26b-mxfp8
"""
import json, sys, time, urllib.request
from collections import Counter

ROWS = [l.rstrip("\n").split("\t") for l in open("eval/routing-ko.tsv", encoding="utf-8") if l.strip() and not l.startswith("#")]

CRITERIA = {
    "en": {
        "agent": "Which assistant should handle the user's request?",
        "choices": {
            "CHAT": "General conversation, explanations of well-known facts or concepts, writing, translation, advice. No tools, no documents, no internet.",
            "DOCS": "Answer from the user's own saved documents, notes, meeting minutes, manuals or PDFs that were registered earlier.",
            "WEB": "Needs current or recent information from the internet: news, prices, exchange rates, stock, sports results, latest versions, releases.",
            "CODE": "Programming help: write, explain, debug or review code, error messages, SQL, git, build tools.",
            "TASK": "Perform actions on files or the computer: organize, move, convert or save files, run commands or builds, schedule jobs.",
            "PERSONAL": "Personal assistant on the user's Mac apps: calendar, schedule, reminders, to-dos, mail, text messages, contacts, notes app, weather, music.",
            "IMAGE": "Create or draw a new image, picture, illustration, logo or artwork.",
        },
        "web": "Does answering require current information from the internet?",
        "local": "Does answering require the user's own saved documents or code repository?",
    },
    "ko": {
        "agent": "사용자 요청을 어느 담당자가 처리해야 하나?",
        "choices": {
            "CHAT": "일반 대화, 잘 알려진 사실·개념 설명, 글쓰기, 번역, 조언. 도구·문서·인터넷 불필요.",
            "DOCS": "사용자가 미리 등록한 자기 문서·노트·회의록·매뉴얼·PDF를 근거로 답하기.",
            "WEB": "인터넷의 최신 정보가 필요: 뉴스, 가격, 환율, 주가, 경기 결과, 최신 버전, 출시 소식.",
            "CODE": "프로그래밍 도움: 코드 작성·설명·디버깅·리뷰, 오류 메시지, SQL, git, 빌드 도구.",
            "TASK": "파일이나 컴퓨터에 작업 수행: 파일 정리·이동·변환·저장, 명령·빌드 실행, 예약 작업.",
            "PERSONAL": "Mac 앱 개인 비서: 캘린더 일정, 미리 알림, 할 일, 메일, 문자, 연락처, 메모 앱, 날씨, 음악.",
            "IMAGE": "새 이미지·그림·일러스트·로고 생성.",
        },
        "web": "답하려면 인터넷의 최신 정보가 필요한가?",
        "local": "답하려면 사용자가 저장한 자기 문서나 코드 저장소가 필요한가?",
    },
}


def post(url, body, timeout=120):
    req = urllib.request.Request(url, json.dumps(body).encode(), {"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=timeout))


def ollaya(model, lang, question):
    c = CRITERIA[lang]
    body = {
        "model": model,
        "state": question,
        "questions": {
            "agent": {"type": "choice", "instructions": c["agent"], "criteria": c["choices"]},
            "needsWeb": {"type": "noul", "instructions": c["web"]},
            "needsLocal": {"type": "noul", "instructions": c["local"]},
        },
    }
    a = post("http://127.0.0.1:11435/api/decide", body)["answers"]
    return a["agent"]["choice"], a["agent"]["confidence"], a["needsWeb"]["noul"], a["needsLocal"]["noul"]


LLM_PROMPT = """사용자 요청을 분류한다. JSON 한 줄로만 답한다.
agent 값:
""" + "\n".join(f"- {k}: {v}" for k, v in CRITERIA["ko"]["choices"].items()) + """
needsWeb: 인터넷 최신 정보가 필요하면 true
needsLocal: 사용자가 저장한 자기 문서나 코드 저장소가 필요하면 true
형식: {"agent": "CHAT", "confidence": 0.0~1.0, "needsWeb": false, "needsLocal": false}

요청: """


def llm(model, question):
    body = {"model": model, "temperature": 0, "max_tokens": 80, "reasoning_effort": "none",
            "messages": [{"role": "user", "content": LLM_PROMPT + question}]}
    text = post("http://127.0.0.1:11434/v1/chat/completions", body)["choices"][0]["message"]["content"]
    j = json.loads(text[text.index("{"): text.rindex("}") + 1])
    return j["agent"], float(j.get("confidence", 1)), 1.0 if j.get("needsWeb") else 0.0, 1.0 if j.get("needsLocal") else 0.0


def main():
    kind = sys.argv[1]
    judge = (lambda q: ollaya(sys.argv[2], sys.argv[3], q)) if kind == "ollaya" else (lambda q: llm(sys.argv[2], q))
    judge(ROWS[0][0])  # 로드 시간 제외
    ok = web_ok = local_ok = 0
    latencies, confusion, confident_ok, confident_n = [], Counter(), 0, 0
    for question, agent, web, local in ROWS:
        start = time.time()
        got, conf, p_web, p_local = judge(question)
        latencies.append((time.time() - start) * 1000)
        ok += got == agent
        web_ok += (p_web >= 0.5) == (web == "1")
        local_ok += (p_local >= 0.5) == (local == "1")
        if conf >= 0.6:
            confident_n += 1
            confident_ok += got == agent
        if got != agent:
            confusion[f"{agent}->{got}"] += 1
    n = len(ROWS)
    latencies.sort()
    print(f"{' '.join(sys.argv[1:])}: D1 {ok}/{n} ({ok / n:.0%})  D2 {web_ok / n:.0%}  D3 {local_ok / n:.0%}  "
          f"신뢰도≥0.6: {confident_n}건 중 {confident_ok} 정답  지연 중앙 {latencies[n // 2]:.0f}ms")
    print("  오답:", dict(confusion.most_common(8)))


main()
