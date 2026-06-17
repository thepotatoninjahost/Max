import math
import sqlite3
import hashlib
from dataclasses import dataclass
from enum import Enum
from typing import List, Optional, Dict, Any, Sequence, Tuple

try:
    import torch
    import torch.nn as nn
    import torch.nn.functional as F
except Exception:
    torch = None
    nn = None
    F = None


class IntentType(Enum):
    QUESTION = "question"
    COMMAND = "command"
    PROBLEM_SOLVING = "problem_solving"
    REFLECTION = "reflection"


@dataclass
class StructuredIntent:
    type: IntentType
    subject: str
    predicate: str
    objects: List[str]
    constraints: List[str]
    uncertainty: float


@dataclass
class Assertion:
    subject: str
    predicate: str
    objects: List[str]
    confidence: float
    source: Optional[str]
    derivation: List[str]


@dataclass
class Rule:
    name: str
    condition: str
    consequence: str
    priority: int
    domain: str


@dataclass
class Evidence:
    text: str
    source: str
    relevance_score: float
    page_reference: Optional[str] = None


@dataclass
class Verdict:
    assertion: Assertion
    is_valid: bool
    grounded: bool
    consistent: bool
    missing_evidence: List[str]
    confidence_adjustment: float
    metacognitive_state: Dict[str, float]


def json_dumps(value):
    import json
    return json.dumps(value, ensure_ascii=False)


class MemoryLayer:
    def __init__(self, db_path: str = "synthetic_brain.db"):
        self.db_path = db_path
        self._init_db()

    def _connect(self):
        return sqlite3.connect(self.db_path)

    def _init_db(self):
        with self._connect() as conn:
            c = conn.cursor()
            c.execute("""
                CREATE TABLE IF NOT EXISTS assertions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    subject TEXT, predicate TEXT, objects TEXT,
                    confidence REAL, source TEXT, derivation TEXT
                )
            """)
            c.execute("""
                CREATE TABLE IF NOT EXISTS rules (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT UNIQUE, condition TEXT, consequence TEXT,
                    priority INTEGER, domain TEXT
                )
            """)
            c.execute("""
                CREATE TABLE IF NOT EXISTS knowledge_references (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    title TEXT, content TEXT, source TEXT,
                    page_reference TEXT, content_hash TEXT, cloud_path TEXT
                )
            """)
            c.execute("""
                CREATE TABLE IF NOT EXISTS episodic_memory (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    event TEXT, context TEXT, source TEXT, timestamp DATETIME DEFAULT CURRENT_TIMESTAMP
                )
            """)
            c.execute("""
                CREATE TABLE IF NOT EXISTS traces (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    intent TEXT, steps TEXT, verdict TEXT, timestamp DATETIME DEFAULT CURRENT_TIMESTAMP
                )
            """)

    def store_rule(self, rule: Rule):
        with self._connect() as conn:
            c = conn.cursor()
            c.execute("""
                INSERT OR REPLACE INTO rules (name, condition, consequence, priority, domain)
                VALUES (?, ?, ?, ?, ?)
            """, (rule.name, rule.condition, rule.consequence, rule.priority, rule.domain))

    def store_reference(self, title: str, content: str, source: str, cloud_path: str, page_reference: Optional[str] = None):
        content_hash = hashlib.sha256(content.encode("utf-8")).hexdigest()
        with self._connect() as conn:
            c = conn.cursor()
            c.execute("""
                INSERT INTO knowledge_references (title, content, source, page_reference, content_hash, cloud_path)
                VALUES (?, ?, ?, ?, ?, ?)
            """, (title, content, source, page_reference, content_hash, cloud_path))

    def store_episode(self, event: str, context: str, source: str):
        with self._connect() as conn:
            c = conn.cursor()
            c.execute("""
                INSERT INTO episodic_memory (event, context, source)
                VALUES (?, ?, ?)
            """, (event, context, source))

    def store_assertion(self, assertion: Assertion):
        with self._connect() as conn:
            c = conn.cursor()
            c.execute("""
                INSERT INTO assertions (subject, predicate, objects, confidence, source, derivation)
                VALUES (?, ?, ?, ?, ?, ?)
            """, (
                assertion.subject,
                assertion.predicate,
                json_dumps(assertion.objects),
                assertion.confidence,
                assertion.source,
                json_dumps(assertion.derivation),
            ))

    def retrieve_relevant(self, query: str, max_results: int = 5) -> List[Evidence]:
        tokens = {tok.lower() for tok in query.split() if tok.strip()}
        with self._connect() as conn:
            c = conn.cursor()
            c.execute("SELECT content, source, page_reference FROM knowledge_references")
            rows = c.fetchall()
        out: List[Evidence] = []
        for content, source, page_ref in rows:
            content_l = content.lower()
            overlap = sum(1 for tok in tokens if tok in content_l)
            score = min(1.0, 0.2 + 0.2 * overlap) if tokens else 0.2
            if overlap > 0 or not tokens:
                out.append(Evidence(text=content, source=source, relevance_score=score, page_reference=page_ref))
        out.sort(key=lambda e: e.relevance_score, reverse=True)
        return out[:max_results]


class RuleLayer:
    def __init__(self, memory: MemoryLayer):
        self.memory = memory
        self.load_core_rules()

    def load_core_rules(self):
        rules = [
            Rule("contradiction_avoidance", "A and not A", "reject_one", 1000, "logic"),
            Rule("evidence_requirement", "assertion without evidence", "mark_uncertain", 900, "epistemology"),
            Rule("unknown_acknowledgment", "query without knowledge", "say_i_dont_know", 800, "knowledge"),
            Rule("causality_order", "cause B before effect", "time_cause_before_effect", 700, "causality"),
            Rule("homeostatic_stability", "overactivity or underactivity", "rebalance", 600, "stability"),
        ]
        for r in rules:
            self.memory.store_rule(r)


class STDPPlasticity:
    def __init__(self, size: int, lr_ltp: float = 0.01, lr_ltd: float = 0.01):
        self.size = size
        self.lr_ltp = lr_ltp
        self.lr_ltd = lr_ltd
        self.weights = [[0.0 for _ in range(size)] for _ in range(size)]

    def update(self, pre_times: Sequence[float], post_times: Sequence[float]):
        n = min(self.size, len(pre_times), len(post_times))
        for i in range(n):
            for j in range(n):
                dt = post_times[j] - pre_times[i]
                if dt > 0:
                    self.weights[i][j] += self.lr_ltp * math.exp(-dt)
                else:
                    self.weights[i][j] -= self.lr_ltd * math.exp(dt)
        return self.weights


class PredictiveCodingLayer:
    def __init__(self, dim: int):
        self.dim = dim

    def compute(self, lower_state: Sequence[float], higher_prediction: Sequence[float]):
        lower = list(lower_state)[: self.dim]
        higher = list(higher_prediction)[: self.dim]
        if len(lower) < self.dim:
            lower += [0.0] * (self.dim - len(lower))
        if len(higher) < self.dim:
            higher += [0.0] * (self.dim - len(higher))
        error = [l - h for l, h in zip(lower, higher)]
        return {
            "prediction": higher,
            "error": error,
            "error_magnitude": sum(abs(e) for e in error) / max(1, len(error)),
        }


class HomeostaticLayer:
    def __init__(self, target_activity: float = 1.0, tolerance: float = 0.25):
        self.target_activity = target_activity
        self.tolerance = tolerance
        self.health = 1.0

    def regulate(self, activity: float):
        deviation = abs(activity - self.target_activity)
        if deviation > self.tolerance:
            self.health = max(0.0, self.health * 0.95)
            activity = activity * 0.9
        else:
            self.health = min(1.0, self.health + 0.01)
        return activity, self.health


class MetacognitiveLayer:
    def assess(self, confidence: float, evidence_count: int, contradiction_score: float, uncertainty: float) -> Dict[str, float]:
        return {
            "confidence": max(0.0, min(1.0, confidence)),
            "evidence": min(1.0, evidence_count / 5.0),
            "contradiction": max(0.0, min(1.0, contradiction_score)),
            "uncertainty": max(0.0, min(1.0, uncertainty)),
            "need_more_data": 1.0 if evidence_count < 1 or uncertainty > 0.7 else 0.0,
        }


class NeuroSymbolicBridge:
    def __init__(self, memory: MemoryLayer):
        self.memory = memory
        self.symbolic_facts: List[str] = []

    def add_fact(self, fact: str):
        self.symbolic_facts.append(fact.lower())

    def verify_fact(self, assertion_text: str) -> bool:
        text = assertion_text.lower()
        return any(fact in text for fact in self.symbolic_facts)


class NeuroplasticGrowth:
    def __init__(self, min_units: int = 4):
        self.min_units = min_units
        self.units = min_units

    def prune(self, weakness: float):
        if weakness > 0.8 and self.units > self.min_units:
            self.units -= 1

    def grow(self, novelty: float):
        if novelty > 0.7:
            self.units += 1


class CognitiveExperts:
    def route(self, text: str) -> str:
        t = text.lower()
        if any(k in t for k in ["logic", "reason", "proof", "true", "false"]):
            return "logic"
        if any(k in t for k in ["who", "what", "where", "when", "reference"]):
            return "language"
        if any(k in t for k in ["feel", "emotion", "mood", "social"]):
            return "social"
        return "world"


class ReasoningCore:
    def __init__(self, memory: MemoryLayer, rules: RuleLayer):
        self.memory = memory
        self.rules = rules
        self.experts = CognitiveExperts()
        self.predictive = PredictiveCodingLayer(dim=8)
        self.metacog = MetacognitiveLayer()
        self.homeostasis = HomeostaticLayer()
        self.neuro_symbolic = NeuroSymbolicBridge(memory)
        self.plasticity = STDPPlasticity(size=8)
        self.growth = NeuroplasticGrowth(min_units=4)

    def parse_intent(self, text: str) -> StructuredIntent:
        expert = self.experts.route(text)
        intent_type = IntentType.QUESTION if text.strip().endswith("?") else IntentType.PROBLEM_SOLVING
        if any(x in text.lower() for x in ["i think", "i feel", "reflect", "wonder"]):
            intent_type = IntentType.REFLECTION
        if any(x in text.lower() for x in ["do", "build", "fix", "rewrite", "create"]):
            intent_type = IntentType.COMMAND
        return StructuredIntent(
            type=intent_type,
            subject=expert,
            predicate="analyze",
            objects=[text],
            constraints=[],
            uncertainty=0.5,
        )

    def reason(self, intent: StructuredIntent) -> Tuple[Assertion, List[Evidence]]:
        evidence = self.memory.retrieve_relevant(intent.objects[0], max_results=5)
        grounded_source = evidence[0].source if evidence else None
        base_confidence = 0.6 if evidence else 0.2
        assertion = Assertion(
            subject=intent.subject,
            predicate=intent.predicate,
            objects=intent.objects,
            confidence=base_confidence,
            source=grounded_source,
            derivation=["intent_parse", "retrieval", "rule_check"],
        )
        return assertion, evidence

    def verify(self, assertion: Assertion, evidence: List[Evidence], uncertainty: float) -> Verdict:
        grounded = assertion.source is not None and len(evidence) > 0
        consistent = True
        contradiction_score = 0.0 if grounded else 0.5
        metacog = self.metacog.assess(assertion.confidence, len(evidence), contradiction_score, uncertainty)
        confidence_adjustment = 0.0
        if not grounded:
            confidence_adjustment -= 0.3
        if not consistent:
            confidence_adjustment -= 0.3
        if metacog["need_more_data"] > 0:
            confidence_adjustment -= 0.2
        is_valid = grounded and consistent and assertion.confidence + confidence_adjustment >= 0.3
        return Verdict(
            assertion=assertion,
            is_valid=is_valid,
            grounded=grounded,
            consistent=consistent,
            missing_evidence=[] if grounded else ["No supporting source"],
            confidence_adjustment=confidence_adjustment,
            metacognitive_state=metacog,
        )

    def answer(self, text: str) -> Dict[str, Any]:
        intent = self.parse_intent(text)
        assertion, evidence = self.reason(intent)
        verdict = self.verify(assertion, evidence, intent.uncertainty)

        pc = self.predictive.compute([1.0] * 8, [0.8] * 8)
        _, health = self.homeostasis.regulate(activity=assertion.confidence)
        self.plasticity.update([0.1 * i for i in range(8)], [0.1 * i + 0.05 for i in range(8)])

        novelty = 1.0 if not evidence else 0.2
        weakness = 1.0 - assertion.confidence
        self.growth.grow(novelty)
        self.growth.prune(weakness)

        return {
            "intent": intent,
            "assertion": assertion,
            "evidence": evidence,
            "verification": verdict,
            "predictive_coding": pc,
            "homeostasis_health": health,
            "cognitive_units": self.growth.units,
        }


class SyntheticBrain:
    def __init__(self):
        self.memory = MemoryLayer()
        self.rules = RuleLayer(self.memory)
        self.core = ReasoningCore(self.memory, self.rules)
        self.load_knowledge_base()

    def load_knowledge_base(self):
        self.memory.store_reference(
            "Physics: Conservation Laws",
            "Energy cannot be created or destroyed.",
            "Physics Textbook",
            "cloud://physics/ch3",
        )
        self.memory.store_reference(
            "Logic: Implication",
            "If A implies B and A is true, then B is true.",
            "Logic Notes",
            "cloud://logic/implication",
        )
        self.memory.store_reference(
            "Metacognition",
            "Good systems monitor confidence, contradiction, and uncertainty.",
            "AI Research Notes",
            "cloud://ai/metacognition",
        )
        self.memory.store_reference(
            "Predictive Coding",
            "Brains minimize prediction error via hierarchical feedback loops.",
            "Neuroscience Review",
            "cloud://neuro/predictive",
        )
        self.core.neuro_symbolic.add_fact("energy")
        self.core.neuro_symbolic.add_fact("logic")
        self.core.neuro_symbolic.add_fact("confidence")

    def ask(self, question: str) -> Dict[str, Any]:
        return self.core.answer(question)

    def learn(self, title: str, content: str, source: str, cloud_path: str):
        self.memory.store_reference(title, content, source, cloud_path)

    def store_episode(self, event: str, context: str, source: str):
        self.memory.store_episode(event, context, source)


if torch is not None:
    class TorchSTDPPlasticity(nn.Module):
        def __init__(self, num_neurons: int):
            super().__init__()
            self.weights = nn.Parameter(torch.randn(num_neurons, num_neurons) * 0.1)
            self.register_buffer("pre_spikes", torch.zeros(num_neurons))
            self.register_buffer("post_spikes", torch.zeros(num_neurons))

        def update(self, pre_activity, post_activity, dt: float = 0.001):
            pre_activity = pre_activity.detach().float()
            post_activity = post_activity.detach().float()
            time_diff = dt * (pre_activity.unsqueeze(1) - self.pre_spikes.unsqueeze(1))
            delta = torch.where(time_diff > 0, 0.01 * torch.exp(-time_diff.abs()), -0.01 * torch.exp(-time_diff.abs()))
            with torch.no_grad():
                self.weights.add_(delta)
                self.pre_spikes.copy_(pre_activity)
                self.post_spikes.copy_(post_activity)
            return self.weights

    class NeuroplasticNetwork(nn.Module):
        def __init__(self, input_dim: int, hidden_dim: int):
            super().__init__()
            self.input_dim = input_dim
            self.hidden_dim = hidden_dim
            self.layers = nn.Sequential(
                nn.Linear(input_dim, hidden_dim),
                nn.ReLU(),
                nn.Linear(hidden_dim, hidden_dim),
            )

        def forward(self, x):
            return self.layers(x)

        def prune_dead_neurons(self, threshold: float = 0.01):
            layer0 = self.layers[0]
            keep = layer0.weight.data.abs().mean(dim=1) >= threshold
            if keep.all():
                return
            idx = keep.nonzero(as_tuple=True)[0]
            new_hidden = int(idx.numel())
            if new_hidden == 0:
                return
            new_layer = nn.Linear(layer0.in_features, new_hidden)
            new_layer.weight.data.copy_(layer0.weight.data[idx])
            new_layer.bias.data.copy_(layer0.bias.data[idx])
            self.layers[0] = new_layer
            self.layers[2] = nn.Linear(new_hidden, new_hidden)
            self.hidden_dim = new_hidden

        def add_new_neurons(self, num_new: int = 5):
            layer0 = self.layers[0]
            new_hidden = self.hidden_dim + num_new
            new_layer = nn.Linear(layer0.in_features, new_hidden)
            with torch.no_grad():
                new_layer.weight[: self.hidden_dim].copy_(layer0.weight)
                new_layer.bias[: self.hidden_dim].copy_(layer0.bias)
            self.layers[0] = new_layer
            self.layers[2] = nn.Linear(new_hidden, new_hidden)
            self.hidden_dim = new_hidden

    class PredictiveCodingTorchLayer(nn.Module):
        def __init__(self, dim: int):
            super().__init__()
            self.prediction_neurons = nn.Linear(dim, dim)
            self.error_neurons = nn.Linear(dim, dim)

        def forward(self, lower_input, higher_prediction):
            error = lower_input - higher_prediction
            new_prediction = self.prediction_neurons(higher_prediction)
            error_signal = self.error_neurons(error)
            return new_prediction, error_signal

    class HomeostaticTorchLayer(nn.Module):
        def __init__(self, dim: int):
            super().__init__()
            self.neurons = nn.Linear(dim, dim)
            self.register_buffer("calcium_monitor", torch.zeros(dim))
            self.stability_threshold = 1.0

        def forward(self, x):
            activity = self.neurons(x)
            with torch.no_grad():
                self.calcium_monitor.copy_(activity.mean(dim=0))
            if self.calcium_monitor.abs().mean().item() > self.stability_threshold:
                activity = activity * 0.9
            return activity

    class MetacognitiveState(nn.Module):
        def __init__(self, dim: int):
            super().__init__()
            self.emotional_awareness = nn.Linear(dim, 1)
            self.correctness_eval = nn.Linear(dim, 1)
            self.experience_match = nn.Linear(dim, 1)
            self.conflict_detect = nn.Linear(dim, 1)
            self.problem_importance = nn.Linear(dim, 1)

        def forward(self, x):
            return torch.cat([
                self.emotional_awareness(x),
                self.correctness_eval(x),
                self.experience_match(x),
                self.conflict_detect(x),
                self.problem_importance(x),
            ], dim=-1)

    class SelfAdaptingAI(nn.Module):
        """AI that modifies its own code"""
        def __init__(self, base_model):
            super().__init__()
            self.model = base_model
            self.recursion_loop = self._adaptive_recursion
            self.performance_history: List[float] = []
            self.code_edit_log: List[Dict[str, Any]] = []

        def _detect_drift(self, data: Any, current_accuracy: float) -> float:
            """Detect when model performance drifts using rolling comparison"""
            self.performance_history.append(current_accuracy)
            if len(self.performance_history) < 3:
                return 0.0
            recent = self.performance_history[-3:]
            baseline = sum(self.performance_history[:-3]) / (len(self.performance_history) - 3)
            return abs(sum(recent) / len(recent) - baseline)

        def _generate_self_edit(self, drift: float) -> Dict[str, Any]:
            """Generate code to fix drift"""
            if drift > 0.2:
                edit = {
                    "action": "increase_capacity",
                    "drift": drift,
                    "suggestion": "add_neurons_or_layers",
                }
            elif drift > 0.05:
                edit = {
                    "action": "stabilize",
                    "drift": drift,
                    "suggestion": "normalize_weights_or_reduce_lr",
                }
            else:
                edit = {
                    "action": "noop",
                    "drift": drift,
                    "suggestion": "maintain",
                }
            self.code_edit_log.append(edit)
            return edit

        def _apply_edit(self, edit: Dict[str, Any]):
            """Apply self-generated edit to the model"""
            action = edit["action"]
            if action == "increase_capacity" and hasattr(self.model, "add_new_neurons"):
                self.model.add_new_neurons(num_new=5)
            elif action == "stabilize" and hasattr(self.model, "prune_dead_neurons"):
                self.model.prune_dead_neurons(threshold=0.01)

        def _adaptive_recursion(self, training_loop: List[Tuple[Any, float]]):
            """Detect → Collapse → Rewrite → Stabilize"""
            threshold = 0.1
            results = []
            for data, current_accuracy in training_loop:
                drift = self._detect_drift(data, current_accuracy)
                edit = self._generate_self_edit(drift)
                if drift > threshold:
                    self._apply_edit(edit)
                self.model.eval()
                results.append(edit)
            return results

        def forward(self, x):
            return self.model(x)
else:
    TorchSTDPPlasticity = None
    NeuroplasticNetwork = None
    PredictiveCodingTorchLayer = None
    HomeostaticTorchLayer = None
    MetacognitiveState = None
    SelfAdaptingAI = None
