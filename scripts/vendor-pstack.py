#!/usr/bin/env python3
"""pstack 스킬을 Vesta 내장 스킬로 가져온다 (PROJECT.md 7.27 SK-17, M33).

쓰는 법: python3 -I scripts/vendor-pstack.py <cursor/plugins 체크아웃>
- 고른 스킬만 app-common/src/main/resources/skills/<이름>/SKILL.md 로 복사한다
- 머리말은 name·description에 Vesta의 agent·tools를 붙여 다시 쓴다 (disable-model-invocation 등 Cursor 전용 키는 버린다)
- 본문의 [글](../이름/SKILL.md) 링크는 글(/이름) 으로 바꾼다 (Vesta에는 다른 스킬 파일을 여는 길이 없다)
- index.txt(이름 목록)·PSTACK-LICENSE·PSTACK-SOURCE(원본 커밋)를 남긴다. 다시 돌려도 같은 결과
"""
import pathlib
import re
import shutil
import subprocess
import sys

# 서브에이전트·node 스크립트·Cursor·references 폴더 없이 돌아가는 것만 (K-16)
TASK = [
    "principle-attack-the-premise", "principle-boundary-discipline", "principle-build-the-lever",
    "principle-encode-lessons-in-structure", "principle-exhaust-the-design-space", "principle-experience-first",
    "principle-explain-the-number", "principle-fix-root-causes", "principle-foundational-thinking",
    "principle-guard-the-context-window", "principle-laziness-protocol", "principle-make-operations-idempotent",
    "principle-migrate-callers-then-delete-legacy-apis", "principle-minimize-reader-load", "principle-model-the-domain",
    "principle-never-block-on-the-human", "principle-outcome-oriented-execution", "principle-prove-it-works",
    "principle-redesign-from-first-principles", "principle-separate-before-serializing-shared-state",
    "principle-sequence-verifiable-units", "principle-subtract-before-you-add",
    "principle-test-behavior-not-implementation", "principle-type-system-discipline",
    "tdd", "blast-radius", "correct",
]
CHAT = ["unslop", "sharpen", "bro", "in-your-own-words"]
TOOLS = "read_file, list_files, search_files, write_file, apply_patch, run_shell"
LINK = re.compile(r"\[([^\]]+)\]\(\.\./([A-Za-z0-9_-]+)/SKILL\.md\)")


def convert(name: str, text: str, agent: str) -> str:
    m = re.match(r"^---\n(.*?)\n---\n(.*)$", text, re.S)
    if not m:
        sys.exit(f"{name}: 머리말이 없다")
    meta, body = m.group(1), m.group(2)
    desc = re.search(r"^description:\s*(.*)$", meta, re.M)
    if not desc:
        sys.exit(f"{name}: description이 없다")
    description = desc.group(1).strip().strip('"').strip("'")
    head = ["---", f"name: {name}", f"description: {description} (pstack)", f"agent: {agent}"]
    if agent == "task":
        head.append(f"tools: {TOOLS}")
    head.append("---")
    return "\n".join(head) + "\n" + LINK.sub(r"\1(/\2)", body).strip() + "\n"


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    checkout = pathlib.Path(sys.argv[1]).resolve()
    src = checkout / "pstack"
    if not (src / "skills").is_dir():
        sys.exit(f"pstack/skills가 없다: {checkout}")
    dest = pathlib.Path(__file__).resolve().parent.parent / "app-common/src/main/resources/skills"
    if dest.exists():
        shutil.rmtree(dest)
    dest.mkdir(parents=True)
    names = []
    for agent, group in (("task", TASK), ("chat", CHAT)):
        for name in group:
            text = (src / "skills" / name / "SKILL.md").read_text(encoding="utf-8")
            (dest / name).mkdir()
            (dest / name / "SKILL.md").write_text(convert(name, text, agent), encoding="utf-8")
            names.append(name)
    (dest / "index.txt").write_text("\n".join(sorted(names)) + "\n", encoding="utf-8")
    shutil.copyfile(src / "LICENSE", dest / "PSTACK-LICENSE")
    commit = subprocess.run(["git", "-C", str(checkout), "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
    (dest / "PSTACK-SOURCE").write_text(f"https://github.com/cursor/plugins/tree/{commit}/pstack\n", encoding="utf-8")
    print(f"{len(names)}개 → {dest}")


if __name__ == "__main__":
    main()
