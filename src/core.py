from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import List


class IntentType(str, Enum):
    GREETING = "greeting"
    QUESTION = "question"
    TASK = "task"
    UNKNOWN = "unknown"


@dataclass
class Observation:
    text: str
    source: str = "user"


@dataclass
class Goal:
    text: str
    status: str = "open"


@dataclass
class Memory:
    observations: List[Observation] = field(default_factory=list)
    goals: List[Goal] = field(default_factory=list)

    def add_observation(self, text: str, source: str = "user") -> None:
        self.observations.append(Observation(text=text, source=source))

    def add_goal(self, text: str) -> None:
        self.goals.append(Goal(text=text))


@dataclass
class Decision:
    reply: str
    finished: bool
    turns_used: int = 1
    reason: str = ""
