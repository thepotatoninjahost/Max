# Max Plan

## Goal
Build a bounded on-device agent that never loops indefinitely and always returns a decision in one pass.

## Finished scope
- Deterministic intent classification
- Greeting fast-path
- One-pass agent loop
- Minimal in-memory state model
- Tests for greeting, intent classification, and loop termination

## Next steps
- Add a real planner/executor split
- Add persistent memory storage
- Add action routing for real tasks
- Add stronger test coverage around long prompts and malformed inputs
