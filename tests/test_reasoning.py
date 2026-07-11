import subprocess
import sys
import unittest

from src.core import IntentType, Memory
from src.loop import AgentLoop
from src.reasoning import ReasoningConfig, ReasoningEngine, classify_intent


class ReasoningTests(unittest.TestCase):
    def test_greeting_fast_path(self):
        engine = ReasoningEngine(Memory())
        decision = engine.respond("hello")
        self.assertEqual(decision.reply, "hello")
        self.assertTrue(decision.finished)
        self.assertEqual(decision.reason, "greeting-fast-path")

    def test_intent_classification(self):
        self.assertEqual(classify_intent("hello"), IntentType.GREETING)
        self.assertEqual(classify_intent("What is this?"), IntentType.QUESTION)
        self.assertEqual(classify_intent("build the agent"), IntentType.TASK)
        self.assertEqual(classify_intent("nonsense"), IntentType.UNKNOWN)

    def test_loop_stops(self):
        loop = AgentLoop(ReasoningEngine(Memory()), max_iterations=2)
        result = loop.run("hello")
        self.assertTrue(result.stopped)
        self.assertEqual(result.iterations, 1)
        self.assertTrue(result.decision.finished)

    def test_cli_runs(self):
        out = subprocess.check_output([sys.executable, "-m", "src.run", "hello"], text=True).strip()
        self.assertEqual(out, "hello")

    def test_memory_records_observation(self):
        memory = Memory()
        engine = ReasoningEngine(memory)
        engine.respond("build the agent")
        self.assertEqual(len(memory.observations), 1)
        self.assertEqual(memory.observations[0].text, "build the agent")
        self.assertEqual(memory.observations[0].source, "user")

    def test_memory_add_goal(self):
        memory = Memory()
        memory.add_goal("rebuild the agent")
        self.assertEqual(len(memory.goals), 1)
        self.assertEqual(memory.goals[0].text, "rebuild the agent")
        self.assertEqual(memory.goals[0].status, "open")

    def test_unknown_input_falls_back(self):
        engine = ReasoningEngine(Memory())
        decision = engine.respond("gibberish")
        self.assertEqual(decision.reason, "fallback")
        self.assertTrue(decision.finished)

    def test_long_input_triggers_budget_guard(self):
        engine = ReasoningEngine(Memory(), config=ReasoningConfig(max_output_chars=5))
        decision = engine.respond("this is too long")
        self.assertEqual(decision.reason, "budget-exceeded")
        self.assertEqual(decision.reply, "Input too large.")


if __name__ == "__main__":
    unittest.main()
