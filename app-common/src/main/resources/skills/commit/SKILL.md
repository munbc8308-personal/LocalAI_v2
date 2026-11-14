---
name: commit
description: 지금 변경분을 읽고 Conventional Commits 형식의 커밋 메시지를 쓴다. 커밋은 요청할 때만 한다. (Vesta 기본)
agent: task
tools: read_file, list_files, search_files, run_shell
---
# 커밋 메시지

## 순서
1. `git status --short`와 `git diff --staged`를 본다. 스테이징된 것이 없으면 `git diff`(작업 중 변경)를 본다.
2. `git log --oneline -10`으로 이 저장소의 메시지 말투·언어·형식을 따른다.
3. 변경을 한 문장 목적으로 줄인다. 서로 관계없는 변경이 섞였으면 나눠 커밋하자고 알린다.

## 형식
```
<type>(<scope>): <무엇을 왜, 72자 안>

<필요할 때만 본문: 왜 바꿨는지, 깨지는 변경, 옮기는 방법>
```
- type: feat, fix, refactor, perf, test, docs, build, ci, chore
- scope는 모듈·폴더 이름, 모르면 뺀다.
- 제목은 무엇을 했는지보다 결과·의도. 파일 목록을 나열하지 않는다.
- 깨지는 변경은 `!`와 본문 `BREAKING CHANGE:`.

## 커밋
- 요청에 "커밋해"가 있을 때만 `git commit -m`을 실행한다. 아니면 메시지만 보여 준다.
- `git push`, `--amend`, `--no-verify`, 강제 옵션은 사용자가 명시할 때만.
- 비밀값(.env, 키, 토큰)이 변경에 보이면 커밋하지 말고 먼저 알린다.
