"""vesta search 결과로 검색 적중률(hit@1, hit@5, MRR)을 잰다. 사용: python3 eval/retrieval_eval.py <컬렉션> [tsv]"""
import re, subprocess, sys
collection = sys.argv[1]
tsv = sys.argv[2] if len(sys.argv) > 2 else "eval/retrieval-project-md.tsv"
vesta = "app-cli/build/install/vesta/bin/vesta"
rows = [l.rstrip("\n").split("\t") for l in open(tsv, encoding="utf-8") if l.strip() and not l.startswith("#")]
hit1 = hit5 = 0; rr = 0.0
for question, expected in rows:
    out = subprocess.run([vesta, "search", "-c", collection, "-n", "5", question], capture_output=True, text=True).stdout
    out = re.sub(r"\x1b\[[0-9;]*m", "", out)
    titles = [l for l in out.splitlines() if re.match(r"^\d+\. ", l)]
    rank = next((i + 1 for i, t in enumerate(titles) if any(e in t for e in expected.split("|"))), None)
    hit1 += rank == 1; hit5 += rank is not None; rr += 1 / rank if rank else 0
    print(f"{'✓' if rank else '✗'} {rank or '-'}  {question}")
n = len(rows)
print(f"{collection}: hit@1 {hit1}/{n}  hit@5 {hit5}/{n}  MRR {rr / n:.2f}")
