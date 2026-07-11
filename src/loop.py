from __future__ import annotations

from dataclasses import dataclass

from .core import Decision
from .reasoning import ReasoningEngine


@dataclass(slots=True)
class LoopResult:
    decision: Decision
    iterations: int
    stopped: bool = True


class AgentLoop:
    def __init__(self, engine: ReasoningEngine, max_iterations: int = 1):
        self.engine = engine
        self.max_iterations = max(1, max_iterations)

    def run(self, text: str) -> LoopResult:
        decision = self.engine.respond(text)
        return LoopResult(decision=decision, iterations=1, stopped=True)
