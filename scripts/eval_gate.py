#!/usr/bin/env python3
"""RAG 评测发布门禁：把新报告和基线对比，指标劣化时退出码为 1。

支持两种报告：
- 新版报告：顶层 summary 里有 recallAtK / mrr / accuracy / failedCases / latency.fallbackRate
- 归档报告：顶层 overall 里有 chunkRecallAt5 / chunkMrr / judgeAccuracy / failed

默认口径：检索和 MRR 允许下降 3 个百分点，评委准确率允许下降 5 个百分点（LLM 评委噪声更大），
失败用例数按 max(基线失败数, 0) 拦截，兜底率上限 20%，用例数少于 30 直接不放行。
"""
import argparse
import json
import sys
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")

DEFAULT_MAX_RECALL_DROP = 0.03
DEFAULT_MAX_MRR_DROP = 0.03
DEFAULT_MAX_ACCURACY_DROP = 0.05
DEFAULT_MAX_FAILED = 0
DEFAULT_MAX_FALLBACK_RATE = 0.20
DEFAULT_MIN_CASES = 30


def _first_number(*values, default=None):
    for value in values:
        if value is None or isinstance(value, bool):
            continue
        try:
            return float(value)
        except (TypeError, ValueError):
            continue
    return default


def _metric_block(report):
    if not isinstance(report, dict):
        raise ValueError("报告必须是 JSON 对象")
    summary = report.get("summary") if isinstance(report.get("summary"), dict) else {}
    overall = report.get("overall") if isinstance(report.get("overall"), dict) else overall_report(report)
    if not summary and not overall and any(
            key in report for key in ("recallAtK", "chunkRecallAt5", "mrr", "chunkMrr", "judgeAccuracy")):
        summary = report

    recall = _first_number(summary.get("recallAtK"), overall.get("chunkRecallAt5"), overall.get("recallAtK"))
    mrr = _first_number(summary.get("mrr"), overall.get("chunkMrr"), overall.get("mrr"))
    accuracy = _first_number(summary.get("accuracy"), overall.get("judgeAccuracy"))
    failed = _first_number(summary.get("failedCases"), overall.get("failed"), default=0.0)
    total = _first_number(summary.get("totalCases"), overall.get("cases"), report.get("totalCases"))

    latency = summary.get("latency") if isinstance(summary.get("latency"), dict) else {}
    fallback_rate = _first_number(latency.get("fallbackRate"))
    fallback_cases = _first_number(latency.get("fallbackCases"))
    if fallback_rate is None:
        modes = summary.get("retrievalModeCounts") if isinstance(summary.get("retrievalModeCounts"), dict) else {}
        mode_total = sum(int(value) for value in modes.values() if isinstance(value, (int, float)))
        if mode_total > 0:
            fallback_cases = sum(int(value) for mode, value in modes.items()
                                 if not str(mode).startswith("HYBRID") and isinstance(value, (int, float)))
            fallback_rate = fallback_cases / mode_total
        if total is None and mode_total > 0:
            total = float(mode_total)

    return {
        "recall": recall,
        "mrr": mrr,
        "accuracy": accuracy,
        "failed": int(failed or 0),
        "total": int(total) if total is not None else None,
        "fallbackRate": fallback_rate,
        "fallbackCases": int(fallback_cases) if fallback_cases is not None else None,
    }


def overall_report(report):
    overall = report.get("overall")
    return overall if isinstance(overall, dict) else {}


def _check_higher_is_better(name, baseline, current, max_drop):
    if baseline is None and current is None:
        return {"name": name, "baseline": None, "current": None, "delta": None,
                "allowed": -max_drop, "status": "SKIP", "reason": "两边都没有该指标"}
    if baseline is None:
        return {"name": name, "baseline": None, "current": current, "delta": None,
                "allowed": -max_drop, "status": "SKIP", "reason": "基线没有该指标"}
    if current is None:
        return {"name": name, "baseline": baseline, "current": None, "delta": None,
                "allowed": -max_drop, "status": "FAIL", "reason": "新报告缺少该指标"}
    delta = current - baseline
    passed = delta >= -max_drop
    return {"name": name, "baseline": baseline, "current": current, "delta": delta,
            "allowed": -max_drop, "status": "PASS" if passed else "FAIL",
            "reason": "" if passed else "下降超过阈值 %.4f" % max_drop}


def _check_lower_is_better(name, baseline, current, max_value, unit=""):
    baseline_value = 0.0 if baseline is None else float(baseline)
    allowed = max(float(max_value), baseline_value)
    if current is None:
        return {"name": name, "baseline": baseline, "current": None, "delta": None,
                "allowed": allowed, "status": "SKIP", "reason": "新报告缺少该指标"}
    current_value = float(current)
    passed = current_value <= allowed
    return {"name": name, "baseline": baseline, "current": current_value, "delta": current_value - baseline_value,
            "allowed": allowed, "status": "PASS" if passed else "FAIL",
            "reason": "" if passed else "超过允许值 %s%s" % (allowed, unit)}


def evaluate(report, baseline,
             max_recall_drop=DEFAULT_MAX_RECALL_DROP,
             max_mrr_drop=DEFAULT_MAX_MRR_DROP,
             max_accuracy_drop=DEFAULT_MAX_ACCURACY_DROP,
             max_failed=DEFAULT_MAX_FAILED,
             max_fallback_rate=DEFAULT_MAX_FALLBACK_RATE,
             min_cases=DEFAULT_MIN_CASES):
    current = _metric_block(report)
    base = _metric_block(baseline)
    checks = [
        _check_higher_is_better("recall@5", base["recall"], current["recall"], max_recall_drop),
        _check_higher_is_better("mrr", base["mrr"], current["mrr"], max_mrr_drop),
        _check_higher_is_better("accuracy", base["accuracy"], current["accuracy"], max_accuracy_drop),
        _check_lower_is_better("failed", base["failed"], current["failed"], max_failed),
        _check_lower_is_better("fallbackRate", base["fallbackRate"], current["fallbackRate"], max_fallback_rate),
    ]
    case_check = None
    if current["total"] is None:
        case_check = {"name": "cases", "baseline": base["total"], "current": None, "delta": None,
                      "allowed": min_cases, "status": "SKIP", "reason": "报告没有用例数"}
    else:
        passed = current["total"] >= min_cases
        case_check = {"name": "cases", "baseline": base["total"], "current": current["total"], "delta": None,
                      "allowed": min_cases, "status": "PASS" if passed else "FAIL",
                      "reason": "" if passed else "用例数少于 %d，证据不足" % min_cases}
    checks.append(case_check)

    passed = all(check["status"] != "FAIL" for check in checks)
    return {"passed": passed, "checks": checks, "current": current, "baseline": base}


def _fmt(value):
    if value is None:
        return "-"
    if isinstance(value, float):
        return f"{value:.4f}"
    return str(value)


def format_table(result):
    lines = ["metric           baseline    current      delta    allowed  status  reason"]
    for check in result["checks"]:
        lines.append("{:<16}{:>10}{:>11}{:>11}{:>11}  {:<6}{}".format(
            check["name"], _fmt(check["baseline"]), _fmt(check["current"]),
            _fmt(check["delta"]), _fmt(check["allowed"]), check["status"], check["reason"]))
    lines.append("")
    lines.append("PASS" if result["passed"] else "FAIL")
    return "\n".join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description="RAG 评测发布门禁")
    parser.add_argument("--report", required=True, help="新评测报告 JSON")
    parser.add_argument("--baseline", required=True, help="基线报告 JSON（归档报告或 summary）")
    parser.add_argument("--max-recall-drop", type=float, default=DEFAULT_MAX_RECALL_DROP)
    parser.add_argument("--max-mrr-drop", type=float, default=DEFAULT_MAX_MRR_DROP)
    parser.add_argument("--max-accuracy-drop", type=float, default=DEFAULT_MAX_ACCURACY_DROP)
    parser.add_argument("--max-failed", type=int, default=DEFAULT_MAX_FAILED)
    parser.add_argument("--max-fallback-rate", type=float, default=DEFAULT_MAX_FALLBACK_RATE)
    parser.add_argument("--min-cases", type=int, default=DEFAULT_MIN_CASES)
    parser.add_argument("--json", action="store_true", help="输出 JSON 而不是表格")
    args = parser.parse_args(argv)

    try:
        report = json.loads(Path(args.report).read_text(encoding="utf-8"))
        baseline = json.loads(Path(args.baseline).read_text(encoding="utf-8"))
    except Exception as error:
        print("读取报告失败: %s" % error, file=sys.stderr)
        return 2

    try:
        result = evaluate(report, baseline,
                          max_recall_drop=args.max_recall_drop,
                          max_mrr_drop=args.max_mrr_drop,
                          max_accuracy_drop=args.max_accuracy_drop,
                          max_failed=args.max_failed,
                          max_fallback_rate=args.max_fallback_rate,
                          min_cases=args.min_cases)
    except Exception as error:
        print("对比失败: %s" % error, file=sys.stderr)
        return 2

    print(json.dumps(result, ensure_ascii=False, indent=2) if args.json else format_table(result))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
