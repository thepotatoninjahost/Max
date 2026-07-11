from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import List


class IntentType(str, Enum):
    UNKNOWN = "unknown"
    GREETING = "greeting"
    QUESTION = "question"
    TASK = "task"


@dataclass(slots=True)
class PlanStep:
    action: str
    detail: str
    done: bool = False


@dataclass(slots=True)
class Plan:
    intent: IntentType
    objective: str
    steps: List[PlanStep] = field(default_factory=list)
    needs_clarification: bool = False


@dataclass(slots=True)
class Memory:
    observations: List[str] = field(default_factory=list)
    goals: List[str] = field(default_factory=list)
    plan_history: List[Plan] = field(default_factory=list)

    def add_observation(self, text: str) -> None:
        self.observations.append(text)

    def add_goal(self, text: str) -> None:
        self.goals.append(text)

    def remember_plan(self, plan: Plan) -> None:
        self.plan_history.append(plan)


@dataclass(slots=True)
class Decision:
    reply: str
    finished: bool
    turns_used: int = 1
    reason: str = ""
    plan: Plan | None = None
