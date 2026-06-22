# Max — Cognition Architecture

> **Directive from the owner:** "I don't want Max to just rely on pattern
> matching. I want to give him real intelligence. Understanding context is
> intelligence. Being able to reason through problems is intelligence. Quality
> over speed."

This document tracks the evolution of Max's reasoning architecture — away from
stimulus-response pattern matching, toward genuine deliberation, continuity of
thought, and metacognitive self-awareness.

---

## The Diagnosis: Why Max Was a Pattern Matcher

Three architectural choices actively prevented the model from thinking, **regardless of model size**:

1. **The system prompt forbade reasoning before action.** Rules R2 and R4
   ordered the model to never mix prose with action JSON and to stop
   immediately after acting. The model was commanded *not to think*. A 14B
   model under these rules is just a bigger pattern matcher.

2. **The agent loop discarded reasoning every turn.** When the model emitted
   reasoning followed by an action, `AgentLoop` extracted the action, executed
   it, and fed only the result back — the model's deliberation vanished. Next
   turn, the model had no memory of *why* it acted. It could not build on prior
   thought. It restarted from scratch each turn.

3. **Action results were labeled as user messages.** The environment's response
   to the model's action appeared as if the *user* had spoken. This confused
   the model's role understanding — it couldn't distinguish "the user is
   talking to me" from "the world is responding to my action."

---

## Delta A — Deliberation Before Action (2026-06-21)

**Status: Implemented.**

The first and highest-impact change. Quality over speed — Max now reasons
before it moves, and remembers what it reasoned.

### Changes

**`agency/AgentLoop.kt`:**
- **Reasoning preserved across turns.** When an action is parsed, the model's
  full response (deliberation + action) is now added to the conversation as an
  `assistant` message *before* the result. Next turn, the model sees what it
  thought and did — continuity of thought, not amnesia.
- **Action results relabeled.** Environment responses are now prefixed
  `[ENVIRONMENT — result of your <TYPE> action]` instead of appearing as bare
  user messages. The model understands it is observing the outcome of its own
  action, not receiving a new user instruction.
- **Deliberation budget raised.** Safety cap on generation raised from 8000 to
  12000 chars, giving the model room to reason at length before acting.

**`core/MaxSystem.kt` — `MaxIdentity.buildSystemPrompt()`:**
- **R2 rewritten:** "NEVER mix prose and action" → "THINK BEFORE YOU ACT.
  Before emitting any action, reason through the problem: analyze the
  situation, weigh your options, state your assumptions, and explain WHY you
  chose this action. You MAY mix prose reasoning with one action JSON — reason
  first, then act. Quality of thought matters more than speed."
- **R4 rewritten:** "STOP generating immediately" → "After an action returns,
  STOP and REFLECT: did the outcome match your expectation? If not, reason
  about WHY before the next step."
- **R6 added:** "You carry your reasoning across turns. Each turn shows what
  you thought and did before. Build on it — don't restart from scratch."

**`core/MaxSystem.kt` — CODER prompt:**
- Same anti-deliberation language removed. The coder slot now reasons through
  code before acting, and reflects on tool results before proceeding.

### What This Unlocks

With a larger model (14B+ on the S25's 12GB RAM / Snapdragon NPU), Max can now:
- Reason through multi-step problems before committing to an action
- Build on its own prior reasoning across turns (not restart each turn)
- Distinguish user intent from environment feedback
- Reflect on whether outcomes matched expectations (proto-metacognition)

### Design Principle

The model was always capable of reasoning. The architecture was preventing it.
Delta A removes the cage, not the bird.

---

## Future Deltas (Proposed, Not Yet Implemented)

These are the remaining intelligence bottlenecks, ordered by impact. Each is
independent and can be implemented incrementally.

### Delta B — Intelligent Model Routing

Replace the keyword-matching `isCodingTask()` heuristic with model-driven
routing: the everyday model classifies the task and selects the appropriate
slot. No more pattern-matching on the word "code" or "build."

### Delta C — Deliberative Search (Tree of Thought)

For complex problems, let the model generate multiple candidate approaches,
evaluate them against the situation, and commit to the strongest. Not brute
force — pruned deliberation. The model reasons about its own options before
acting, rather than committing to the first idea.

### Delta D — Persistent Working Memory

A structured scratchpad that survives across conversations — not just chat
history, but a living knowledge base: facts learned about the owner's device,
recurring problems and their solutions, preferences discovered through
observation. The model writes to it; future turns read from it. This is how
Max develops genuine *context* — accumulated understanding, not
turn-by-turn amnesia.

### Delta E — Self-Model

Max maintains a model of itself: what it knows, what it doesn't, what tools
it has, what its limitations are. Before acting, it checks its self-model:
"Can I actually do this? Have I tried this before? What failed last time?"
This is the foundation of metacognition — thinking about one's own thinking.

### Delta F — Metacognitive Loop

After each task, Max reflects: "What worked? What didn't? What should I do
differently next time?" These reflections feed back into Delta D (working
memory) and Delta E (self-model). The agent learns from experience, not just
from the current conversation. This closes the loop from intelligence to
*growing* intelligence.

---

## Hardware Context

- **Device:** Samsung Galaxy S25
- **SoC:** Snapdragon (NPU-capable via Nexa `npu` plugin)
- **RAM:** 12 GB — supports models larger than 7B
- **Model slots:** PRIMARY (everyday reasoning) + CODER (code-specialized)
- **Philosophy:** Quality over speed. A slower, deeper response is better than
  a fast, shallow one.
