import subprocess
import sys
import unittest

from src.core import IntentType, Memory
from src.loop import AgentLoop
from src.reasoning import ReasoningEngine, classify_intent


class ReasoningTests(unittest.TestCase):
    def test_intent_classification(self):
        self.assertEqual(classify_intent("hello"), IntentType.GREETING)
        self.assertEqual(classify_intent("what is this?"), IntentType.QUESTION)
        self.assertEqual(classify_intent("build this"), IntentType.TASK)
        self.assertEqual(classify_intent(""), IntentType.UNKNOWN)

    def test_plan_generation(self):
        engine = ReasoningEngine(Memory())
        decision = engine.respond("build an agent")
        self.assertFalse(decision.finished)
        self.assertIsNotNone(decision.plan)
        self.assertGreaterEqual(len(decision.plan.steps), 3)

    def test_loop_is_bounded(self):
        loop = AgentLoop(ReasoningEngine(Memory()), max_iterations=9)
        result = loop.run("build an agent")
        self.assertEqual(result.iterations, 1)
        self.assertTrue(result.stopped)

    def test_cli_runs(self):
        out = subprocess.check_output([sys.executable, "-m", "src.run", "hello"], text=True).strip()
        self.assertTrue(out)


if __name__ == "__main__":
    unittest.main()
