"""按题型（用例 id 前缀）拆分离线 RAG 评测报告，并把多份报告并排对比。

用法：python3 scripts/eval_compare.py eval-reports/A.json eval-reports/B.json ...
"""
import json
import sys
from collections import defaultdict

TYPES = ["fact", "para", "multi", "conflict", "rustfs", "resume", "neg"]


def by_type(report):
    groups = defaultdict(list)
    for case in report["cases"]:
        groups[case["caseId"].split("-")[0]].append(case)
    out = {}
    for t in TYPES + ["ALL"]:
        cases = report["cases"] if t == "ALL" else groups.get(t, [])
        if not cases:
            continue
        labeled = [c for c in cases if c["retrievalHit"] is not None]
        judged = [c for c in cases if c["judgeCorrect"] is not None]
        out[t] = {
            "n": len(cases),
            "recall": sum(c["retrievalHit"] for c in labeled) / len(labeled) if labeled else None,
            "mrr": sum(c["reciprocalRank"] or 0 for c in labeled) / len(labeled) if labeled else None,
            "empty": sum(1 for c in cases if not c["retrieved"]),
            "judged": len(judged),
            "acc": sum(c["judgeCorrect"] for c in judged) / len(judged) if judged else None,
            "score": sum(c["judgeScore"] for c in judged) / len(judged) if judged else None,
            "failed": sum(1 for c in cases if c["diagnosis"] in ("JUDGE_FAILED", "GENERATION_FAILED", "RETRIEVAL_FAILED")),
        }
    return out


def fmt(v, pct=True):
    if v is None:
        return "-"
    return f"{v * 100:.1f}%" if pct else f"{v:.2f}"


def main(paths):
    reports = [json.load(open(p, encoding="utf-8")) for p in paths]
    for r in reports:
        s = r["summary"]
        print(f"== {r['label']} (rerank 阈值 {r['config']['rerankScoreThreshold']}, topK {r['topK']}) ==")
        print(f"   Recall@K {fmt(s['recallAtK'])}  MRR {fmt(s['mrr'], False)}  评委准确率 {fmt(s['accuracy'])}"
              f"  平均分 {fmt(s['meanJudgeScore'], False)}  失败 {s['failedCases']}  检索模式 {s['retrievalModeCounts']}")
        print(f"   归因 {s['diagnosisCounts']}")
    print()
    print("题型      | " + " | ".join(f"{r['label']:^44}" for r in reports))
    print("          | " + " | ".join(f"{'Recall':>7} {'MRR':>5} {'空':>3} {'准确率':>7} {'均分':>5} {'失败':>3}" for _ in reports))
    tables = [by_type(r) for r in reports]
    for t in TYPES + ["ALL"]:
        cells = []
        for tab in tables:
            row = tab.get(t)
            if row is None:
                cells.append(" " * 44)
                continue
            cells.append(f"{fmt(row['recall']):>7} {fmt(row['mrr'], False):>5} {row['empty']:>3} "
                         f"{fmt(row['acc']):>7} {fmt(row['score'], False):>5} {row['failed']:>3}")
        n = tables[0].get(t, {}).get("n", 0)
        print(f"{t:<6}({n:>2}) | " + " | ".join(cells))


if __name__ == "__main__":
    main(sys.argv[1:])
