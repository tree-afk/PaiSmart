import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("eval_gate", SCRIPT / "eval_gate.py")
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def new_report(recall=0.76, mrr=0.59, accuracy=0.86, failed=0, total=50, fallback=None):
    summary = {
        "recallAtK": recall,
        "mrr": mrr,
        "accuracy": accuracy,
        "failedCases": failed,
        "totalCases": total,
    }
    if fallback is not None:
        summary["latency"] = {"fallbackRate": fallback, "fallbackCases": int(fallback * total)}
    return {"summary": summary}


def archived_report(recall=0.761, mrr=0.5902, accuracy=0.86, failed=0, cases=50):
    return {"overall": {"chunkRecallAt5": recall, "chunkMrr": mrr,
                        "judgeAccuracy": accuracy, "failed": failed, "cases": cases}}


def check(result, name):
    return next(item for item in result["checks"] if item["name"] == name)


class EvalGateTests(unittest.TestCase):
    def test_passes_when_metrics_are_within_threshold(self):
        result = gate.evaluate(new_report(recall=0.75, accuracy=0.83), archived_report())
        self.assertTrue(result["passed"], result)
        self.assertEqual("PASS", check(result, "recall@5")["status"])
        self.assertEqual("PASS", check(result, "accuracy")["status"])

    def test_fails_when_recall_drops_more_than_threshold(self):
        result = gate.evaluate(new_report(recall=0.70), archived_report())
        self.assertFalse(result["passed"])
        self.assertEqual("FAIL", check(result, "recall@5")["status"])

    def test_fails_on_new_failed_case(self):
        result = gate.evaluate(new_report(failed=1), archived_report(failed=0))
        self.assertFalse(result["passed"])
        self.assertEqual("FAIL", check(result, "failed")["status"])

    def test_fails_when_fallback_rate_exceeds_limit(self):
        result = gate.evaluate(new_report(fallback=0.5), archived_report())
        self.assertFalse(result["passed"])
        self.assertEqual("FAIL", check(result, "fallbackRate")["status"])

    def test_existing_baseline_failures_are_tolerated_but_new_ones_are_not(self):
        passed = gate.evaluate(new_report(failed=3), archived_report(failed=3))
        failed = gate.evaluate(new_report(failed=4), archived_report(failed=3))
        self.assertTrue(passed["passed"], passed)
        self.assertFalse(failed["passed"], failed)

    def test_missing_current_metric_fails(self):
        report = new_report()
        report["summary"].pop("recallAtK")
        result = gate.evaluate(report, archived_report())
        self.assertFalse(result["passed"])
        self.assertEqual("FAIL", check(result, "recall@5")["status"])

    def test_small_dataset_is_not_enough_for_release(self):
        result = gate.evaluate(new_report(total=5), archived_report(cases=50), min_cases=30)
        self.assertFalse(result["passed"])
        self.assertEqual("FAIL", check(result, "cases")["status"])

    def test_supports_archived_report_as_current(self):
        result = gate.evaluate(archived_report(recall=0.75), archived_report())
        self.assertTrue(result["passed"], result)

    def test_fallback_rate_can_be_derived_from_modes(self):
        report = new_report()
        report["summary"].pop("latency", None)
        report["summary"]["retrievalModeCounts"] = {"HYBRID": 8, "TEXT_ONLY": 2}
        result = gate.evaluate(report, archived_report())
        self.assertAlmostEqual(0.2, result["current"]["fallbackRate"], places=4)


if __name__ == "__main__":
    unittest.main()
