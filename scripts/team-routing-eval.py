#!/usr/bin/env python3
"""팀 자동 배정 평가 (PROJECT.md 7.35 TR-06, M41). 질문 → 기대 팀(없으면 None) 표로 점수·여유·판정을 보고 정확도를 낸다.
router/TeamRouter.kt와 같은 식(점수 = 팀 글 유사도 최댓값, 여유 = 점수 − 기준점 최댓값)과 설정 기본값을 쓴다. 기준을 바꾸면 다시 돌린다.
쓰는 법: 서버·Ollama가 떠 있을 때 python3 -I scripts/team-routing-eval.py (주인 API 토큰을 키체인에서 읽는다)
"""
import json, math, subprocess, urllib.request

AUTO, AUTO_MARGIN, SUGGEST, SUGGEST_MARGIN = 0.70, 0.15, 0.60, 0.05
MODEL = "bge-m3:567m"
# TeamRouter.ANCHORS와 같게
ANCHORS = ["대화: 일반 대화·설명·글쓰기·번역", "웹 리서치: 웹을 검색해 출처와 함께 답한다", "코드 도우미: 등록한 저장소에서 찾아 파일·줄과 함께 답하고 수정안을 낸다",
           "개인 비서: 일정·메일·미리 알림 확인과 등록", "이미지: 그림을 만든다",
           "오늘 날씨 알려 줘", "이거 무슨 뜻이야? 설명해 줘", "맛집 추천해 줘", "영어로 번역해 줘", "그림 그려 줘", "이 코드 무슨 뜻이야", "요즘 뉴스 알려 줘"]
CASES = [
    ("통계청 국내 여행 자료를 조사해서 구글 시트로 만들어 줘", "구글팀"), ("안 읽은 메일 요약하고 답장 초안 써 줘", "구글팀"),
    ("회의록을 구글 문서로 정리해 줘", "구글팀"), ("독서 기록 앱 첫 버전 기획해 줘", "기획팀"),
    ("이 저장소 최근 변경 코드 리뷰하고 보안 문제 찾아 줘", "리뷰보안팀"), ("로그인 버그 재현하고 실패하는 테스트 만들어 줘", "품질팀"),
    ("README를 코드에 맞게 고쳐 줘", "문서팀"), ("출시 전에 빌드랑 테스트 다 돌려서 점검해 줘", "릴리스팀"),
    ("가계부 앱에 월별 그래프 기능 구현하고 테스트까지 해 줘", "개발팀"),
    ("~/Documents/회의록 폴더 파일들 읽고 요약해 줘", "research"), ("바탕화면 폴더에 뭐가 있는지 정리해 줘", "research"),
    ("오늘 날씨 어때?", None), ("파이썬에서 리스트 정렬하는 법 알려 줘", None), ("고양이 그림 그려 줘", None),
    ("내일 오후 3시에 회의 일정 잡아 줘", None), ("요즘 볼만한 영화 추천해 줘", None), ("김치찌개 레시피 알려줘", None),
    ("이 함수가 뭐 하는지 설명해 줘", None), ("Kotlin 코루틴이 뭐야?", None), ("이 문장 영어로 번역해 줘", None),
]

TOKEN = subprocess.run(["security", "find-generic-password", "-s", "dev.vesta", "-a", "api-token", "-w"], capture_output=True, text=True).stdout.strip()
req = urllib.request.Request("http://127.0.0.1:8787/api/agents", headers={"Authorization": f"Bearer {TOKEN}"})
studio = json.load(urllib.request.urlopen(req))

def embed(texts):
    r = urllib.request.Request("http://127.0.0.1:11434/api/embed", data=json.dumps({"model": MODEL, "input": texts}).encode(), headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(r))["embeddings"]

def cos(a, b):
    return sum(x * y for x, y in zip(a, b)) / math.sqrt(sum(x * x for x in a) * sum(y * y for y in b))

teams = {t["name"]: [f'{t["name"]}: {t["description"]}'] + t.get("examples", []) for t in studio["teams"]}
texts = [s for v in teams.values() for s in v] + ANCHORS
vec = dict(zip(texts, embed(texts)))
right = 0
for (q, expected), qv in zip(CASES, embed([q for q, _ in CASES])):
    scores = sorted(((max(cos(qv, vec[s]) for s in v), n) for n, v in teams.items()), reverse=True)
    score, name = scores[0]
    margin = score - max(cos(qv, vec[a]) for a in ANCHORS)
    verdict = name if score >= AUTO and margin >= AUTO_MARGIN else (f"(추천 {name})" if score >= SUGGEST and margin >= SUGGEST_MARGIN else "-")
    ok = verdict == expected or (expected is None and verdict.startswith(("-", "(")))
    right += ok
    print(f"{'✓' if ok else '✗'} {expected or '-':6} → {verdict:10} {score:.3f} 여유 {margin:+.3f}  {q}")
print(f"\n맞음 {right}/{len(CASES)} (팀이 아닌 질문은 추천까지 맞음으로)")
