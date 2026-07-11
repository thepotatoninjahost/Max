from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Iterable, List

from .core import IntentType, Memory, Decision


_GREETINGS = {"hi", "hello", "hey", "yo", "howdy"}


def classify_intent(text: str) -> IntentType:
    normalized = text.strip().lower()
    if not normalized:
        return IntentType.UNKNOWN
    if normalized in _GREETINGS:
        return IntentType.GREETING
    if normalized.endswith("?"):
        return IntentType.QUESTION
    if re.search(r"\b(do|build|make|create|fix|write|start)\b", normalized):
        return IntentType.TASK
    return IntentType.UNKNOWN


@dataclass
class ReasoningConfig:
    max_thought_steps: int = 3
    max_output_chars: int = 256


class ReasoningEngine:
    def __init__(self, memory: Memory | None = None, config: ReasoningConfig | None = None):
        self.memory = memory or Memory()
        self.config = config or ReasoningConfig()

    def respond(self, text: str) -> Decision:
        self.memory.add_observation(text)
        intent = classify_intent(text)
        if intent == IntentType.GREETING:
            return Decision(reply="hello", finished=True, turns_used=1, reason="greeting-fast-path")
        if intent == IntentType.QUESTION:
            return Decision(reply="I need the exact target.", finished=True, turns_used=1, reason="question-clarify")
        if intent == IntentType.TASK:
            return Decision(reply=f"Acknowledged: {text.strip()}", finished=True, turns_used=1, reason="task-acknowledged")
        if len(text.strip()) > self.config.max_output_chars:
            return Decision(reply="Input too large.", finished=True, turns_used=1, reason="budget-exceeded")
        return Decision(reply="I need a clearer instruction.", finished=True, turns_used=1, reason="fallback")
