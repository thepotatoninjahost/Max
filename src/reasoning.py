from __future__ import annotations

import re
from dataclasses import dataclass

from .core import Decision, IntentType, Memory, Plan, PlanStep


_GREETINGS = {"hi", "hello", "hey", "yo", "howdy"}
_TODO_PATTERNS = (
    r"\bdo\b",
    r"\bbuild\b",
    r"\bmake\b",
    r"\bcreate\b",
    r"\bfix\b",
    r"\bwrite\b",
    r"\bstart\b",
    r"\bcommit\b",
    r"\bpush\b",
    r"\btest\b",
    r"\bplan\b",
)


def classify_intent(text: str) -> IntentType:
    normalized = text.strip().lower()
    if not normalized:
        return IntentType.UNKNOWN
    if normalized in _GREETINGS:
        return IntentType.GREETING
    if normalized.endswith("?"):
        return IntentType.QUESTION
    if any(re.search(pattern, normalized) for pattern in _TODO_PATTERNS):
        return IntentType.TASK
    return IntentType.UNKNOWN


@dataclass(slots=True)
class ReasoningConfig:
    max_thought_steps: int = 5
    max_output_chars: int = 256
    max_goal_chars: int = 160


class ReasoningEngine:
    def __init__(self, memory: Memory | None = None, config: ReasoningConfig | None = None):
        self.memory = memory or Memory()
        self.config = config or ReasoningConfig()

    def respond(self, text: str) -> Decision:
        self.memory.add_observation(text)
        intent = classify_intent(text)
        objective = text.strip()
        plan = self._build_plan(intent, objective)

        if intent == IntentType.GREETING:
            return Decision(reply="hello", finished=True, turns_used=1, reason="greeting-fast-path", plan=plan)
        if intent == IntentType.QUESTION:
            return Decision(reply="I need the exact target.", finished=True, turns_used=1, reason="question-clarify", plan=plan)
        if intent == IntentType.TASK:
            if len(objective) > self.config.max_goal_chars:
                return Decision(reply="Input too large.", finished=True, turns_used=1, reason="budget-exceeded", plan=plan)
            return Decision(reply=f"Acknowledged: {objective}", finished=False, turns_used=1, reason="task-acknowledged", plan=plan)
        if len(objective) > self.config.max_output_chars:
            return Decision(reply="Input too large.", finished=True, turns_used=1, reason="budget-exceeded", plan=plan)
        return Decision(reply="I need a clearer instruction.", finished=True, turns_used=1, reason="fallback", plan=plan)

    def _build_plan(self, intent: IntentType, objective: str) -> Plan:
        objective = objective[: self.config.max_goal_chars]
        if intent == IntentType.GREETING:
            return Plan(
                intent=intent,
                objective=objective,
                steps=[PlanStep(action="acknowledge", detail="respond briefly and stop", done=True)],
            )
        if intent == IntentType.QUESTION:
            return Plan(
                intent=intent,
                objective=objective,
                needs_clarification=True,
                steps=[PlanStep(action="clarify", detail="ask for the exact target", done=True)],
            )
        if intent == IntentType.TASK:
            steps = [
                PlanStep(action="inspect", detail="understand the task", done=True),
                PlanStep(action="execute", detail="carry out the task with bounded output", done=False),
                PlanStep(action="verify", detail="test the result and stop", done=False),
            ]
            return Plan(intent=intent, objective=objective, steps=steps)
        return Plan(
            intent=intent,
            objective=objective,
            steps=[PlanStep(action="clarify", detail="ask for a clearer instruction", done=True)],
            needs_clarification=True,
        )
