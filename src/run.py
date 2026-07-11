from __future__ import annotations

import argparse

from .loop import AgentLoop
from .reasoning import ReasoningEngine


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("text", nargs="?", default="hello")
    args = parser.parse_args()
    result = AgentLoop(ReasoningEngine()).run(args.text)
    print(result.decision.reply)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
