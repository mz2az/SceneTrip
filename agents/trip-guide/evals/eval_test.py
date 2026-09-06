"""평가를 테스트로 취급한다 (CLAUDE.md §6 — 「평가는 테스트다」).

    just agent-eval trip-guide

`plan_eval` 이 재는 다섯 지표가 하나라도 100% 아래로 내려가면 이 테스트가 깨진다.
게이트에 걸어 두는 이유는, 계수를 만지다가 동선이 나빠지는 것을 사람이 눈으로
알아채기 어렵기 때문이다.

**실제 모델을 부르지 않는다.** 대본 클라이언트와 고정 픽스처만 쓴다.
"""

from __future__ import annotations

import unittest

from evals.plan_eval import eval_book, report, run


class 평가지표(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.result = run(eval_book())

    def test_모든_지표가_100퍼센트다(self):
        failed = [m for m in self.result["metrics"] if m.rate < 100.0]
        self.assertFalse(failed, "\n" + report(self.result))

    def test_실현_가능률이_100퍼센트다(self):
        """설계의 핵심 주장. 순수 LLM 이 약 4% 인 자리(MIT)에서 우리는 100% 여야 한다."""
        feasibility = self.result["metrics"][0]
        self.assertEqual(feasibility.rate, 100.0, feasibility.detail)
        self.assertGreaterEqual(
            feasibility.total, 10, "사례가 너무 적으면 지표가 의미 없다"
        )

    def test_2opt_이_동선을_늘리지_않는다(self):
        self.assertGreaterEqual(self.result["route_gain_percent"], 0.0)


if __name__ == "__main__":
    unittest.main()
