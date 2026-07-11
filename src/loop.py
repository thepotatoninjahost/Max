from __future__ import annotations

from dataclasses import dataclass
from typing import Optional

from .core import Decision
from .reasoning import ReasoningEngine


@dataclass
class LoopResult:
    decision: Decision
    iterations: int
    stopped: bool


class AgentLoop:
    def __init__(self, engine: Optional[ReasoningEngine] = None, max_iterations: int = 1):
        self.engine = engine or ReasoningEngine()
        self.max_iterations = max_iterations

    def run(self, text: str) -> LoopResult:
        decision = self.engine.respond(text)
        return LoopResult(decision=decision, iterations=1, stopped=True)
