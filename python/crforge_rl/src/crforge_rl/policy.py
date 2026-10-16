"""The policy: an encoder of a seat's observation, a factored masked action head and a value head;
its checkpoints, which carry the vector's schema and vocabulary and refuse a vector that reads
them differently.

The action head is factored the way the action is built: it first picks the wait, one of the hand
slots (a slot is open when the mask opens any of its tiles) or an action past the plays (none in
schema version 1), then, for a slot, one of its tiles (the closed tiles masked). The probability
of an action is the product of the two, and any distribution over the open actions is such a
product, so the factoring loses nothing against one categorical over every action. What it gains:
a sensible start (the wait has about a fifth of the probability, not one in the action count) and
one tile head whose spatial choice is shared by the slots, conditioned on the slot's card. Every
size is read from the vector's metadata, none is typed here.

The encoder reads the header's amounts as scaled numbers and its card ids through an embedding,
and the entity rows through a shared network pooled by mean and max over the rows written (a deep
set), so the order of the rows does not matter.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

import numpy as np
import torch
from crforge_env import VectorBattle
from torch import nn
from torch.nn import functional

CHECKPOINT_FORMAT = 1
"""The checkpoint layout's version; a checkpoint of another is refused."""

NEG = -1e9
"""The logit of a closed choice: its probability is exactly 0 in float32."""


def pick_device(name: str = "auto") -> torch.device:
    """``auto``: CUDA when present, else MPS (Apple silicon), else the CPU; any other name as
    torch reads it (``cpu``, ``mps``, ``cuda:1``)."""
    if name != "auto":
        return torch.device(name)
    if torch.cuda.is_available():
        return torch.device("cuda")
    if torch.backends.mps.is_available():
        return torch.device("mps")
    return torch.device("cpu")


@dataclass(frozen=True)
class Layout:
    """Where the encoder finds each field, read from a vector's metadata export (its ``schema``),
    so a checkpoint rebuilds its model without a JVM."""

    header_ints: int
    entity_ints: int
    max_entities: int
    actions: int
    hand_slots: int
    tiles: int
    vocabulary_size: int
    header: dict[str, tuple[int, ...]]
    entity: dict[str, int]

    @property
    def play_end(self) -> int:
        """The first action past the plays (the wait, then each slot's tiles)."""
        return 1 + self.hand_slots * self.tiles

    @property
    def extras(self) -> int:
        """The actions past the plays (none in schema version 1); the policy picks them in its
        first choice, next to the wait and the slots."""
        return self.actions - self.play_end

    @staticmethod
    def from_metadata(metadata: dict[str, Any], vocabulary_size: int) -> Layout:
        s = metadata["schema"]
        header: dict[str, tuple[int, ...]] = {}
        for index, name in enumerate(s["header_fields"]):
            if name != "reserved":
                header[name] = (*header.get(name, ()), index)
        entity = {name: i for i, name in enumerate(s["entity_fields"]) if name != "reserved"}
        return Layout(
            header_ints=int(s["header_ints"]),
            entity_ints=int(s["entity_ints"]),
            max_entities=int(s["max_entities"]),
            actions=int(s["actions"]),
            hand_slots=int(s["hand_slots"]),
            tiles=int(s["cols"]) * int(s["rows"]),
            vocabulary_size=vocabulary_size,
            header=header,
            entity=entity,
        )

    @staticmethod
    def of(vector: VectorBattle) -> Layout:
        return Layout.from_metadata(vector.metadata, vector.vocabulary_size)


@dataclass(frozen=True)
class ModelConfig:
    """The network's sizes."""

    embedding: int = 32
    entity_hidden: int = 64
    trunk: int = 256
    tile_hidden: int = 128


# The header amounts the encoder reads, each with its scale (the value divided by it is about 0
# to 1). Towers are read as hit point shares, below.
_SCALED = {
    "elixir": 100_000.0,  # ten-thousandths of an elixir bar, 10 bars
    "elixir_reserved": 100_000.0,
    "crowns": 3.0,
    "crowns_enemy": 3.0,
    "hand_cost": 10.0,
    "hand_pending": 1.0,
    "hand_refill_ms": 3_000.0,
    "time_ms": 300_000.0,
    "section": 4.0,
    "section_type": 4.0,
    "section_left_ms": 60_000.0,
    "full_bar_ms": 30_000.0,
    "overtime": 1.0,
    "play_open_in_ticks": 100.0,
    "pending_count": 4.0,
    "pending_ticks": 20.0,
    "pending_x": 18_000.0,
    "pending_y": 32_000.0,
}
_TOWERS = tuple(
    f"{owner}_{tower}" for owner in ("own", "enemy") for tower in ("king", "left", "right")
)
_KINDS = 5  # entity kinds 1 to 4, and 0 for none
_SIDES = 3
_FLAG_BITS = 7


def _layer(inputs: int, outputs: int, gain: float = float(np.sqrt(2))) -> nn.Linear:
    layer = nn.Linear(inputs, outputs)
    nn.init.orthogonal_(layer.weight, gain)
    nn.init.zeros_(layer.bias)
    return layer


class Policy(nn.Module):
    """The policy and value network of one seat."""

    def __init__(self, layout: Layout, config: ModelConfig | None = None):
        super().__init__()
        self.layout = layout
        self.config = config or ModelConfig()
        c = self.config
        d = c.embedding

        def indices(names: tuple[str, ...]) -> torch.Tensor:
            found = [i for name in names for i in layout.header[name]]
            return torch.tensor(found, dtype=torch.long)

        self.register_buffer("scaled_at", indices(tuple(_SCALED)), persistent=False)
        scales = [scale for name, scale in _SCALED.items() for _ in layout.header[name]]
        self.register_buffer("scales", torch.tensor(scales), persistent=False)
        self.register_buffer("tower_hp_at", indices(tuple(f"{t}_hp" for t in _TOWERS)), False)
        self.register_buffer("tower_max_at", indices(tuple(f"{t}_max_hp" for t in _TOWERS)), False)
        for name in ("hand_card", "next_card", "deck", "enemy_seen", "pending_card"):
            self.register_buffer(f"{name}_at", indices((name,)), persistent=False)
        self.entity_count_at = layout.header["entity_count"][0]
        self.register_buffer("flag_bits", 1 << torch.arange(_FLAG_BITS), persistent=False)

        self.cards = nn.Embedding(layout.vocabulary_size + 1, d)  # id + 1: row 0 is "none"
        entity_in = d + _KINDS + _SIDES + 8 + _FLAG_BITS
        self.entity_net = nn.Sequential(
            _layer(entity_in, c.entity_hidden),
            nn.ReLU(),
            _layer(c.entity_hidden, c.entity_hidden),
            nn.ReLU(),
        )
        scalars = len(scales) + len(_TOWERS) + 2
        trunk_in = scalars + (layout.hand_slots + 4) * d + 2 * c.entity_hidden
        self.trunk = nn.Sequential(
            nn.LayerNorm(trunk_in),
            _layer(trunk_in, c.trunk),
            nn.ReLU(),
            _layer(c.trunk, c.trunk),
            nn.ReLU(),
        )
        self.value_head = _layer(c.trunk, 1, 1.0)
        self.wait_head = _layer(c.trunk, 1, 0.01)
        self.slot_head = nn.Sequential(
            _layer(c.trunk + d + 1, c.tile_hidden), nn.ReLU(), _layer(c.tile_hidden, 1, 0.01)
        )
        self.extra_head = _layer(c.trunk, layout.extras, 0.01) if layout.extras > 0 else None
        self.tile_head = nn.Sequential(
            _layer(c.trunk + d, c.tile_hidden), nn.ReLU(), _layer(c.tile_hidden, layout.tiles, 0.01)
        )

    # -- encoding ------------------------------------------------------------------------------

    def _embed(self, ids: torch.Tensor) -> torch.Tensor:
        """Vocabulary ids (-1 for none) as embeddings; ids out of range read as none."""
        ids = ids.long() + 1
        ids = torch.where((ids < 0) | (ids > self.layout.vocabulary_size), 0, ids)
        return self.cards(ids)

    def _mean_of_set(self, ids: torch.Tensor) -> torch.Tensor:
        """The mean embedding of a field of several ids, over those that are not -1."""
        present = (ids >= 0).float().unsqueeze(-1)
        total = (self._embed(ids) * present).sum(1)
        return total / present.sum(1).clamp(min=1.0)

    def encode(self, observations: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        """The trunk features ``(B, trunk)`` and the hand's embeddings ``(B, slots, d)`` of a batch
        of seat observations ``(B, header + K * entity)`` (entity rows may be cut to the most any
        observation of the batch wrote, ``K``)."""
        layout = self.layout
        batch = observations.shape[0]
        header = observations[:, : layout.header_ints]
        h = header.float()
        scaled = h[:, self.scaled_at] / self.scales
        hp = h[:, self.tower_hp_at]
        top = h[:, self.tower_max_at]
        towers = torch.where(top > 0, hp / top.clamp(min=1.0), torch.zeros_like(hp))
        refused = torch.stack(
            [
                (h[:, layout.header["last_refusal"][0]] != 0).float(),
                (h[:, layout.header["last_run_refusal"][0]] != 0).float(),
            ],
            dim=1,
        )
        hand = self._embed(header[:, self.hand_card_at])
        sets = [
            self._embed(header[:, self.next_card_at]).squeeze(1),
            self._mean_of_set(header[:, self.deck_at]),
            self._mean_of_set(header[:, self.enemy_seen_at]),
            self._mean_of_set(header[:, self.pending_card_at]),
        ]

        rows = observations[:, layout.header_ints :].reshape(batch, -1, layout.entity_ints)
        k = rows.shape[1]
        count = header[:, self.entity_count_at].long().clamp(0, k)
        written = torch.arange(k, device=rows.device).unsqueeze(0) < count.unsqueeze(1)
        e = layout.entity
        r = rows.float()
        hp_e = r[..., e["hp"]]
        top_e = r[..., e["max_hp"]]
        flags = rows[..., e["flags"]].long().unsqueeze(-1)
        bits = (flags & self.flag_bits) != 0
        numbers = torch.stack(
            [
                r[..., e["x"]] / 18_000.0,
                r[..., e["y"]] / 32_000.0,
                torch.where(top_e > 0, hp_e / top_e.clamp(min=1.0), torch.zeros_like(hp_e)),
                torch.log1p(hp_e.clamp(min=0.0)) / 10.0,
                torch.log1p(top_e.clamp(min=0.0)) / 10.0,
                torch.log1p(r[..., e["shield"]].clamp(min=0.0)) / 10.0,
                (r[..., e["shield"]] > 0).float(),
                r[..., e["state"]].clamp(0.0, 20.0) / 20.0,
            ],
            dim=-1,
        )
        features = torch.cat(
            [
                self._embed(rows[..., e["row"]]),
                functional.one_hot(rows[..., e["kind"]].long().clamp(0, _KINDS - 1), _KINDS),
                functional.one_hot(rows[..., e["side"]].long().clamp(0, _SIDES - 1), _SIDES),
                numbers,
                bits.float(),
            ],
            dim=-1,
        )
        encoded = self.entity_net(features)
        weight = written.unsqueeze(-1).float()
        mean = (encoded * weight).sum(1) / weight.sum(1).clamp(min=1.0)
        # The encoded rows are not negative (ReLU), so 0 stands for "no row" in the max.
        biggest = (encoded * weight).amax(1) if k else torch.zeros_like(mean)
        trunk_in = torch.cat(
            [scaled, towers, refused, hand.flatten(1), *sets, mean, biggest], dim=1
        )
        return self.trunk(trunk_in), hand

    # -- heads ---------------------------------------------------------------------------------

    def heads(
        self, observations: torch.Tensor, masks: torch.Tensor
    ) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        """The masked log-probabilities of the first choice ``(B, 1 + slots + extras)`` (the wait,
        each hand slot, then any action past the plays) and of the tile choice of each slot
        ``(B, slots, tiles)``, and the value ``(B,)``.

        ``masks`` is the seat's action mask ``(B, actions)``, bool or 0/1.
        """
        trunk, hand = self.encode(observations)
        layout = self.layout
        slots = layout.hand_slots
        masks = masks.bool()
        tile_open = masks[:, 1 : layout.play_end].reshape(-1, slots, layout.tiles)
        open_parts = [masks[:, :1], tile_open.any(-1)]
        trunk_slots = trunk.unsqueeze(1).expand(-1, slots, -1)
        costs = observations[:, list(layout.header["hand_cost"])].float().unsqueeze(-1) / 10.0
        logit_parts = [
            self.wait_head(trunk),
            self.slot_head(torch.cat([trunk_slots, hand, costs], dim=-1)).squeeze(-1),
        ]
        if self.extra_head is not None:
            open_parts.append(masks[:, layout.play_end :])
            logit_parts.append(self.extra_head(trunk))
        first_open = torch.cat(open_parts, dim=1)
        first_logits = torch.cat(logit_parts, dim=1)
        tile_logits = self.tile_head(torch.cat([trunk_slots, hand], dim=-1))
        first_logp = functional.log_softmax(torch.where(first_open, first_logits, NEG), dim=-1)
        tile_logp = functional.log_softmax(torch.where(tile_open, tile_logits, NEG), dim=-1)
        return first_logp, tile_logp, self.value_head(trunk).squeeze(-1)

    def split(self, actions: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        """An action's first choice (0 the wait, 1 + slot for a play, 1 + slots + k for the k-th
        action past the plays) and its tile (0 for any action that is not a play)."""
        layout = self.layout
        play = (actions > 0) & (actions < layout.play_end)
        extra = actions >= layout.play_end
        first = torch.where(play, (actions - 1) // layout.tiles + 1, 0)
        first = torch.where(extra, 1 + layout.hand_slots + actions - layout.play_end, first)
        tile = torch.where(play, (actions - 1) % layout.tiles, 0)
        return first, tile

    def join(self, first: torch.Tensor, tile: torch.Tensor) -> torch.Tensor:
        """The action of a first choice and a tile (the inverse of :meth:`split`)."""
        layout = self.layout
        slots = layout.hand_slots
        play = (first > 0) & (first <= slots)
        actions = torch.where(play, 1 + (first - 1) * layout.tiles + tile, 0)
        return torch.where(first > slots, layout.play_end + first - 1 - slots, actions)

    def _log_prob(
        self,
        first_logp: torch.Tensor,
        tile_logp: torch.Tensor,
        first: torch.Tensor,
        tile: torch.Tensor,
    ) -> torch.Tensor:
        """The log-probability of an action: its first choice's, plus its tile's for a play."""
        slots = self.layout.hand_slots
        play = (first > 0) & (first <= slots)
        slot = (first - 1).clamp(0, slots - 1)
        tiles_of_slot = tile_logp.gather(
            1, slot.view(-1, 1, 1).expand(-1, 1, tile_logp.shape[-1])
        ).squeeze(1)
        tile_part = tiles_of_slot.gather(1, tile.unsqueeze(1)).squeeze(1)
        return first_logp.gather(1, first.unsqueeze(1)).squeeze(1) + torch.where(
            play, tile_part, 0.0
        )

    def evaluate(
        self, observations: torch.Tensor, masks: torch.Tensor, actions: torch.Tensor
    ) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        """The log-probability and entropy of the given actions, and the value."""
        first_logp, tile_logp, value = self.heads(observations, masks)
        first, tile = self.split(actions)
        logp = self._log_prob(first_logp, tile_logp, first, tile)
        first_p = first_logp.exp()
        tile_entropy = -(tile_logp.exp() * tile_logp).sum(-1)  # (B, slots)
        slots = self.layout.hand_slots
        entropy = -(first_p * first_logp).sum(-1) + (first_p[:, 1 : 1 + slots] * tile_entropy).sum(
            -1
        )
        return logp, entropy, value

    @torch.no_grad()
    def sample(
        self, observations: torch.Tensor, masks: torch.Tensor, greedy: bool = False
    ) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        """An action for each seat, drawn from the masked policy (its most likely with
        ``greedy``), with its log-probability and the value."""
        first_logp, tile_logp, value = self.heads(observations, masks)

        def draw(logp: torch.Tensor) -> torch.Tensor:
            if greedy:
                return logp.argmax(-1)
            uniform = torch.rand_like(logp).clamp(1e-10, 1.0 - 1e-7)
            return (logp - torch.log(-torch.log(uniform))).argmax(-1)  # Gumbel-max

        first = draw(first_logp)
        tiles = draw(tile_logp)  # (B, slots): a tile drawn for every slot, the chosen one kept
        slot = (first - 1).clamp(0, self.layout.hand_slots - 1)
        tile = tiles.gather(1, slot.unsqueeze(1)).squeeze(1)
        actions = self.join(first, tile)
        return actions, self._log_prob(first_logp, tile_logp, first, tile), value


def trim(observations: np.ndarray, layout: Layout) -> np.ndarray:
    """A batch of seat observations cut after the most entity rows any of them wrote (at least
    one row), so the encoder does not read 200 rows of zeros."""
    counts = observations[:, layout.header["entity_count"][0]]
    k = int(np.clip(counts.max(initial=0), 1, layout.max_entities))
    return observations[:, : layout.header_ints + k * layout.entity_ints]


class PolicyPlayer:
    """A policy as a :class:`crforge_rl.opponents.Player`: it acts for a batch of seats."""

    def __init__(
        self,
        model: Policy,
        device: torch.device,
        name: str = "policy",
        greedy: bool = False,
        checkpoint: dict[str, Any] | None = None,
    ):
        self.model = model
        self.device = device
        self.name = name
        self.greedy = greedy
        self.checkpoint = checkpoint or {}

    def act(
        self, observations: np.ndarray, masks: np.ndarray, rng: np.random.Generator
    ) -> np.ndarray:
        if len(observations) == 0:
            return np.zeros(0, dtype=np.int32)
        obs = torch.from_numpy(trim(observations, self.model.layout)).to(self.device)
        mask = torch.from_numpy(np.ascontiguousarray(masks)).to(self.device)
        self.model.eval()
        actions, _, _ = self.model.sample(obs, mask, self.greedy)
        return actions.cpu().numpy().astype(np.int32)


# -- checkpoints -------------------------------------------------------------------------------


def save_checkpoint(path: Path, model: Policy, vector: VectorBattle, **extra: Any) -> None:
    """Writes the model with what it needs to be read again: the vector's metadata export (the
    schema with its version, sizes and field names, the configuration, and for Ladder battles the
    vocabulary by name with the data version, content sha and hash), the model's sizes, and the
    run's own fields (``extra``: deck, step counts, training settings)."""
    payload = {
        "format": CHECKPOINT_FORMAT,
        "model": asdict(model.config),
        "vocabulary_size": model.layout.vocabulary_size,
        "metadata": vector.metadata,
        "engine": vector.engine,
        "state_dict": {k: v.detach().cpu() for k, v in model.state_dict().items()},
        **extra,
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    torch.save(payload, temporary)
    temporary.replace(path)


def check_checkpoint(payload: dict[str, Any], vector: VectorBattle) -> None:
    """Refuses a checkpoint the vector would read differently.

    The schema and the vocabulary are checked by the vector itself
    (:meth:`VectorBattle.check_metadata`: the version, sizes and field names, and every name's
    id); then the engine, the vocabulary size and the configuration (decision interval, levels,
    tick limit) must be the same.

    :raises ValueError: naming the first difference
    """
    if payload.get("format") != CHECKPOINT_FORMAT:
        raise ValueError(
            f"a checkpoint of format {payload.get('format')}, this package reads "
            f"{CHECKPOINT_FORMAT}"
        )
    vector.check_metadata(payload["metadata"])
    if payload["engine"] != vector.engine:
        raise ValueError(f"the checkpoint plays {payload['engine']}, the vector {vector.engine}")
    if payload["vocabulary_size"] != vector.vocabulary_size:
        raise ValueError(
            f"the checkpoint's vocabulary has {payload['vocabulary_size']} names, the vector's "
            f"{vector.vocabulary_size}"
        )
    theirs = payload["metadata"].get("config")
    ours = vector.metadata.get("config")
    if theirs != ours:
        raise ValueError(f"the checkpoint was trained with config {theirs}, the vector has {ours}")


def load_checkpoint(
    path: str | Path, vector: VectorBattle | None = None, device: str | torch.device = "cpu"
) -> tuple[Policy, dict[str, Any]]:
    """Reads a checkpoint, checked against ``vector`` when one is given
    (:func:`check_checkpoint`), and rebuilds its model on ``device``."""
    payload = torch.load(Path(path), map_location="cpu", weights_only=True)
    if not isinstance(payload, dict) or "state_dict" not in payload:
        raise ValueError(f"{path} is not a checkpoint of this package")
    if vector is not None:
        check_checkpoint(payload, vector)
    elif payload.get("format") != CHECKPOINT_FORMAT:
        raise ValueError(f"a checkpoint of format {payload.get('format')}")
    layout = Layout.from_metadata(payload["metadata"], payload["vocabulary_size"])
    model = Policy(layout, ModelConfig(**payload["model"]))
    model.load_state_dict(payload["state_dict"])
    device = pick_device(device) if isinstance(device, str) else device
    return model.to(device), payload


def load_player(
    path: str | Path,
    device: str | torch.device = "auto",
    vector: VectorBattle | None = None,
    greedy: bool = False,
) -> PolicyPlayer:
    """A checkpoint as a player (for evaluation, or as a frozen opponent)."""
    device = pick_device(device) if isinstance(device, str) else device
    model, payload = load_checkpoint(path, vector, device)
    return PolicyPlayer(model, device, f"checkpoint:{Path(path).name}", greedy, payload)
