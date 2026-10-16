"""PPO on the vector of battles, and the command ``crforge-rl-train``.

One file, in the shape of CleanRL's PPO: a rollout of ``steps`` decisions of every battle, the
advantages by GAE, then ``epochs`` passes of clipped policy and value updates over minibatches.
The policy acts for the learner's seats of every battle at once (one forward pass a decision for
the whole vector); the opponent's seats are played by the training opponent.

Modes (``--mode``):

- ``random`` (the default): the learner plays one seat of each battle, the training opponent
  (``--opponent``, ``random`` by default) the other; the learner's side alternates by battle
- ``self``: the learner plays both seats of every battle against itself

Rewards: the match's result (+1, -1, 0 for a draw) from the environment, plus a shaping term
``shaping * (phi' - phi)`` with ``phi`` the tower balance
(:func:`crforge_rl.rollout.tower_potential`), less ``leak`` for each decision the learner waits at
full elixir with a play open (:func:`crforge_rl.rollout.leaking`), as a player who lets elixir go
to waste. A fault or a tick limit ends an episode as truncated with no reward, never as a loss:
the advantage bootstraps there. Every fault is counted by kind and signature (``faults.json``),
and the first time each signature is met is printed: a fault is the battle core's refusal of a
behaviour it does not model, or its bug, and self-play is one way to find them.

A run folder holds ``config.json``, ``metadata.json`` (the vector's export: schema, configuration,
vocabulary), ``log.jsonl`` (one line an update), ``curve.csv`` (one row an evaluation and
opponent), ``faults.json``, the checkpoints ``latest.pt`` and ``update-<n>.pt``, and with the
plot extra ``curve.png``. Runs are local: ``runs/`` is ignored by git.
"""

from __future__ import annotations

import argparse
import functools
import json
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

import numpy as np
import torch
from crforge_env import LadderConfig, VectorBattle
from torch import nn

from .evaluate import eval_seeds, evaluate
from .opponents import load_opponent
from .policy import Layout, ModelConfig, Policy, PolicyPlayer, pick_device, save_checkpoint, trim
from .records import Curve, FaultLog
from .rollout import MODES, Seats, advantages, leaking, tower_potential

DEFAULT_DECK = ("Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows", "Zap")
"""The mirror deck both seats play unless told otherwise: eight common Ladder cards."""


@dataclass
class TrainConfig:
    """Everything a run is made of; written to the run folder as ``config.json``."""

    install: str
    tables: str | None = None
    engine: str = "ladder"
    run_dir: str = "runs/latest"
    deck: tuple[str, ...] = DEFAULT_DECK
    mode: str = "random"
    opponent: str = "random"
    battles: int = 128
    threads: int = 4
    steps: int = 128
    updates: int = 0
    """updates to run, 0 for no limit (then ``minutes`` must be set)"""
    minutes: float = 0.0
    """the run's time box in minutes of wall time, 0 for none"""
    epochs: int = 4
    minibatches: int = 4
    learning_rate: float = 3e-4
    gamma: float = 0.99
    lam: float = 0.95
    clip: float = 0.2
    entropy: float = 0.01
    value: float = 0.5
    max_grad_norm: float = 0.5
    shaping: float = 0.5
    leak: float = 0.0
    eval_every: int = 10
    eval_games: int = 64
    eval_opponents: str = "random,scripted"
    device: str = "auto"
    seed: int = 0
    decision_ticks: int | None = None
    level: int | None = None
    synthetic_episode_steps: int = 900
    synthetic_fault_at: int = -1
    synthetic_fault_kind: str = "bug"
    model: dict[str, int] = field(default_factory=dict)

    def ladder_config(self) -> LadderConfig | None:
        if self.engine != "ladder":
            return None
        return LadderConfig(decision_ticks=self.decision_ticks, level=self.level)

    def vector(self, count: int) -> VectorBattle:
        kwargs: dict[str, Any] = {}
        if self.engine == "synthetic":
            kwargs = dict(
                synthetic_episode_steps=self.synthetic_episode_steps,
                synthetic_fault_at=self.synthetic_fault_at,
                synthetic_fault_kind=self.synthetic_fault_kind,
            )
        return VectorBattle(
            count,
            self.threads,
            install=self.install,
            engine=self.engine,
            tables=self.tables,
            config=self.ladder_config(),
            **kwargs,
        )


@dataclass
class TrainResult:
    """What a run did, for its caller and its tests."""

    updates: int
    learner_steps: int
    wall_s: float
    steps_per_s: float
    evaluations: list[dict[str, Any]]
    faults: dict[str, Any]
    last_losses: dict[str, float]


class Rollout:
    """The samples of one rollout: ``steps`` decisions of ``seats`` learner seats."""

    def __init__(self, steps: int, seats: int, seat_ints: int, actions: int):
        self.observations = np.zeros((steps, seats, seat_ints), dtype=np.int32)
        self.masks = np.zeros((steps, seats, actions), dtype=np.uint8)
        self.actions = np.zeros((steps, seats), dtype=np.int64)
        self.logp = np.zeros((steps, seats), dtype=np.float32)
        self.values = np.zeros((steps, seats), dtype=np.float32)
        self.rewards = np.zeros((steps, seats), dtype=np.float32)
        self.terminated = np.zeros((steps, seats), dtype=bool)
        self.truncated = np.zeros((steps, seats), dtype=bool)
        self.valid = np.zeros((steps, seats), dtype=bool)


def _check_mask(masks: np.ndarray, actions: np.ndarray) -> None:
    """Halts on a sampled action its seat's mask closed: the head's masking is broken."""
    taken = masks[np.arange(len(actions)), actions]
    if not taken.all():
        bad = int(np.flatnonzero(taken == 0)[0])
        raise RuntimeError(f"the policy drew action {int(actions[bad])}, closed in its mask")


_print = functools.partial(print, flush=True)


def train(cfg: TrainConfig, log=_print) -> TrainResult:
    """Runs PPO as ``cfg`` says, writing the run folder; returns what it did."""
    if cfg.mode not in MODES:
        raise ValueError(f"a mode is one of {MODES}")
    if cfg.updates <= 0 and cfg.minutes <= 0:
        raise ValueError("give updates or minutes: a run needs an end")
    run_dir = Path(cfg.run_dir)
    run_dir.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(cfg.seed)
    torch.manual_seed(cfg.seed)
    device = pick_device(cfg.device)
    deck = list(cfg.deck)

    vector = cfg.vector(cfg.battles)
    eval_vector = cfg.vector(cfg.eval_games) if cfg.eval_games > 0 else None
    try:
        return _train(cfg, vector, eval_vector, run_dir, rng, device, deck, log)
    finally:
        vector.close()
        if eval_vector is not None:
            eval_vector.close()


def _train(cfg, vector, eval_vector, run_dir, rng, device, deck, log) -> TrainResult:
    schema = vector.schema
    vector.write_metadata(run_dir / "metadata.json")
    (run_dir / "config.json").write_text(
        json.dumps({**asdict(cfg), "device": str(device)}, indent=1) + "\n", encoding="utf-8"
    )
    model = Policy(Layout.of(vector), ModelConfig(**cfg.model)).to(device)
    optimizer = torch.optim.Adam(model.parameters(), lr=cfg.learning_rate, eps=1e-5)
    seats = Seats.of(vector.count, cfg.mode)
    learner, opponent_seats = seats.learner, seats.opponent
    learner_battle = seats.battle(learner)
    opponent = load_opponent(cfg.opponent, vector, device=device) if cfg.mode == "random" else None
    eval_opponents = [
        load_opponent(spec, device=device, vector=eval_vector)
        for spec in cfg.eval_opponents.split(",")
        if spec and eval_vector is not None
    ]
    log(
        f"device {device}, {vector.count} battles on {vector.threads} threads, mode {cfg.mode}, "
        f"{len(learner)} learner seats, deck {deck}, data version {vector.data_version}"
    )

    count = vector.count
    flat_obs = vector.observations.reshape(2 * count, schema.seat_ints)
    flat_masks = vector.masks.reshape(2 * count, schema.actions)
    flat_actions = vector.actions.reshape(2 * count)
    flat_rewards = vector.rewards.reshape(2 * count)
    fault_at, truncated_at, winner_at = (
        schema.info["fault"],
        schema.info["truncated"],
        schema.info["winner"],
    )
    vector.set_autoreset(True)
    vector.reset([int(s) for s in rng.integers(0, 2**31 - 1, count)], deck, deck)
    after_end = vector.dones.astype(bool).copy()  # the next step of these battles is a reset
    potential = tower_potential(schema, flat_obs[learner])

    buffer = Rollout(cfg.steps, len(learner), schema.seat_ints, schema.actions)
    faults = FaultLog()
    curve = Curve(run_dir / "curve.csv")
    evaluations: list[dict[str, Any]] = []
    learner_steps = 0
    start = time.monotonic()
    rollout_time = 0.0
    losses: dict[str, float] = {}
    update = 0
    games = {"won": 0, "lost": 0, "draw": 0, "no_result": 0}
    plays = decisions = 0

    def run_evaluation() -> None:
        if eval_vector is None:
            return
        player = PolicyPlayer(model, device, "learner")
        elapsed = time.monotonic() - start
        for opp in eval_opponents:
            result = evaluate(eval_vector, player, opp, deck, eval_seeds(eval_vector.count), rng)
            row = {
                "update": update,
                "learner_steps": learner_steps,
                "wall_s": round(elapsed, 1),
                "steps_per_s": round(learner_steps / rollout_time, 1) if rollout_time else 0.0,
                "opponent": result.opponent,
                "games": result.games,
                "wins": result.wins,
                "losses": result.losses,
                "draws": result.draws,
                "cut": result.cut,
                "faults": result.faults,
                "win_rate": round(result.win_rate, 4),
                "plays_per_game": round(result.plays_per_game, 1),
            }
            curve.append(row)
            evaluations.append(row)
            log(
                f"eval update {update} vs {result.opponent}: win rate {result.win_rate:.3f} "
                f"({result.wins}-{result.losses}-{result.draws}, cut {result.cut}, faults "
                f"{result.faults}), {result.plays_per_game:.1f} plays a game"
            )

    def checkpoint(name: str) -> None:
        save_checkpoint(
            run_dir / name,
            model,
            vector,
            deck=deck,
            ladder_config=asdict(cfg.ladder_config() or LadderConfig()),
            mode=cfg.mode,
            update=update,
            learner_steps=learner_steps,
        )

    run_evaluation()
    while True:
        update += 1
        model.eval()
        began = time.monotonic()
        for t in range(cfg.steps):
            obs = flat_obs[learner]
            masks = flat_masks[learner]
            buffer.observations[t] = obs
            buffer.masks[t] = masks
            buffer.valid[t] = ~after_end[learner_battle]
            actions, logp, value = model.sample(
                torch.from_numpy(trim(obs, model.layout)).to(device),
                torch.from_numpy(masks).to(device),
            )
            actions = actions.cpu().numpy()
            _check_mask(masks, actions)
            buffer.actions[t] = actions
            buffer.logp[t] = logp.cpu().numpy()
            buffer.values[t] = value.cpu().numpy()
            flat_actions[learner] = actions
            if opponent is not None:
                flat_actions[opponent_seats] = opponent.act(
                    flat_obs[opponent_seats], flat_masks[opponent_seats], rng
                )
            vector.step()

            done = vector.dones.astype(bool)
            fault = vector.infos[:, fault_at] != 0
            cut = vector.infos[:, truncated_at] != 0
            seat_done = done[learner_battle]
            seat_fault = fault[learner_battle]
            seat_cut = (cut | fault)[learner_battle]
            valid = buffer.valid[t]
            new_potential = tower_potential(schema, flat_obs[learner])
            shaped = cfg.shaping * (new_potential - potential)
            shaped -= cfg.leak * leaking(schema, obs, masks, actions)
            shaped = np.where(valid & ~seat_fault, shaped, 0.0)
            potential = new_potential
            buffer.rewards[t] = flat_rewards[learner] + shaped
            buffer.terminated[t] = valid & seat_done & ~seat_cut
            buffer.truncated[t] = valid & seat_done & seat_cut
            plays += int((actions[valid] != 0).sum())
            decisions += int(valid.sum())

            # A fault shows on the step that ends its episode (a reset that faults included).
            for signature in faults.observe(vector, np.flatnonzero(done & fault), update):
                record = faults.by_signature[signature]
                log(f"new fault ({record.kind}) at tick {record.first_tick}: {signature}")
            ended = np.flatnonzero(done & ~after_end)
            if ended.size:
                for flat in learner[np.isin(learner_battle, ended)]:
                    battle = flat // 2
                    winner = vector.infos[battle, winner_at]
                    if fault[battle] or cut[battle]:
                        games["no_result"] += 1
                    elif winner == schema.winner_draw:
                        games["draw"] += 1
                    elif winner == flat % 2:
                        games["won"] += 1
                    else:
                        games["lost"] += 1
            after_end = done.copy()
            learner_steps += int(valid.sum())
        with torch.no_grad():
            obs = flat_obs[learner]
            _, _, next_value = model.sample(
                torch.from_numpy(trim(obs, model.layout)).to(device),
                torch.from_numpy(flat_masks[learner]).to(device),
            )
        rollout_time += time.monotonic() - began

        advantage, returns = advantages(
            buffer.rewards,
            buffer.values,
            buffer.terminated,
            buffer.truncated,
            buffer.valid,
            next_value.cpu().numpy(),
            cfg.gamma,
            cfg.lam,
        )
        losses = _update(cfg, model, optimizer, buffer, advantage, returns, device, rng)

        elapsed = time.monotonic() - start
        decided = games["won"] + games["lost"] + games["draw"]
        line = {
            "update": update,
            "learner_steps": learner_steps,
            "wall_s": round(elapsed, 1),
            "rollout_steps_per_s": round(learner_steps / rollout_time, 1),
            "steps_per_s": round(learner_steps / elapsed, 1),
            "train_games": dict(games),
            "train_win_rate": round(games["won"] / decided, 4) if decided else None,
            # the learner's plays over its decisions of this update's rollout
            "plays_per_1000_steps": round(1000 * plays / max(decisions, 1), 1),
            "faults": faults.total,
            "fault_kinds": dict(faults.by_kind),
            **{k: round(v, 5) for k, v in losses.items()},
        }
        with (run_dir / "log.jsonl").open("a", encoding="utf-8") as out:
            out.write(json.dumps(line) + "\n")
        log(
            f"update {update}: {learner_steps} steps, {line['steps_per_s']} steps/s, train games "
            f"{games}, entropy {losses['entropy']:.3f}, kl {losses['approx_kl']:.4f}, value loss "
            f"{losses['value_loss']:.4f}, faults {faults.total}"
        )
        games = dict.fromkeys(games, 0)
        plays = decisions = 0
        faults.write(run_dir / "faults.json")

        finished = (cfg.updates > 0 and update >= cfg.updates) or (
            cfg.minutes > 0 and elapsed >= cfg.minutes * 60
        )
        if update % cfg.eval_every == 0 or finished:
            run_evaluation()
            checkpoint(f"update-{update}.pt")
            checkpoint("latest.pt")
        if finished:
            break

    _plot(run_dir, log)
    elapsed = time.monotonic() - start
    return TrainResult(
        updates=update,
        learner_steps=learner_steps,
        wall_s=elapsed,
        steps_per_s=learner_steps / rollout_time if rollout_time else 0.0,
        evaluations=evaluations,
        faults=faults.summary(),
        last_losses=losses,
    )


def _update(cfg, model, optimizer, buffer, advantage, returns, device, rng) -> dict[str, float]:
    """The PPO epochs over the rollout's valid samples."""
    model.train()
    valid = np.flatnonzero(buffer.valid.reshape(-1))
    if valid.size == 0:
        return {"policy_loss": 0.0, "value_loss": 0.0, "entropy": 0.0, "approx_kl": 0.0}
    seat_ints = buffer.observations.shape[-1]
    obs = buffer.observations.reshape(-1, seat_ints)
    masks = buffer.masks.reshape(-1, buffer.masks.shape[-1])
    actions = buffer.actions.reshape(-1)
    old_logp = buffer.logp.reshape(-1)
    adv = advantage.reshape(-1)
    ret = returns.reshape(-1)
    size = max(1, valid.size // cfg.minibatches)
    totals = {"policy_loss": 0.0, "value_loss": 0.0, "entropy": 0.0, "approx_kl": 0.0}
    batches = 0
    for _ in range(cfg.epochs):
        order = rng.permutation(valid)
        for begin in range(0, valid.size, size):
            index = order[begin : begin + size]
            if index.size < 2:
                continue
            o = torch.from_numpy(trim(obs[index], model.layout)).to(device)
            m = torch.from_numpy(masks[index]).to(device)
            a = torch.from_numpy(actions[index]).to(device)
            lp_old = torch.from_numpy(old_logp[index]).to(device)
            a_adv = torch.from_numpy(adv[index]).to(device)
            a_adv = (a_adv - a_adv.mean()) / (a_adv.std() + 1e-8)
            r = torch.from_numpy(ret[index]).to(device)
            logp, entropy, value = model.evaluate(o, m, a)
            log_ratio = logp - lp_old
            ratio = log_ratio.exp()
            policy_loss = torch.max(
                -a_adv * ratio, -a_adv * ratio.clamp(1 - cfg.clip, 1 + cfg.clip)
            ).mean()
            value_loss = 0.5 * (value - r).pow(2).mean()
            entropy_mean = entropy.mean()
            loss = policy_loss + cfg.value * value_loss - cfg.entropy * entropy_mean
            if not torch.isfinite(loss):
                raise FloatingPointError(f"the loss is not finite: {float(loss)}")
            optimizer.zero_grad()
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), cfg.max_grad_norm)
            optimizer.step()
            with torch.no_grad():
                kl = ((ratio - 1) - log_ratio).mean()
            totals["policy_loss"] += float(policy_loss.detach())
            totals["value_loss"] += float(value_loss.detach())
            totals["entropy"] += float(entropy_mean.detach())
            totals["approx_kl"] += float(kl)
            batches += 1
    return {k: v / max(batches, 1) for k, v in totals.items()}


def _plot(run_dir: Path, log) -> None:
    """Draws ``curve.png`` when matplotlib is installed (the plot extra)."""
    try:
        from .curve import plot
    except ImportError:
        return
    try:
        plot(run_dir / "curve.csv", run_dir / "curve.png")
    except ImportError:
        log("matplotlib is not installed (the plot extra): no curve.png")


def main(argv: list[str] | None = None) -> None:
    defaults = TrainConfig(install="")
    parser = argparse.ArgumentParser(
        prog="crforge-rl-train",
        description="Train a policy with PPO on Ladder battles of one mirror deck.",
    )
    parser.add_argument("--install", required=True, help="env/build/install/env")
    parser.add_argument("--tables", help="the tables folder of a data version (ladder engine)")
    parser.add_argument("--engine", choices=("ladder", "synthetic"), default=defaults.engine)
    parser.add_argument("--run-dir", default=defaults.run_dir)
    parser.add_argument("--deck", default=",".join(defaults.deck), help="8 card row names")
    parser.add_argument("--mode", choices=MODES, default=defaults.mode)
    parser.add_argument("--opponent", default=defaults.opponent, help="the training opponent")
    parser.add_argument(
        "--eval-opponents",
        default=defaults.eval_opponents,
        help="comma-separated: random, wait, checkpoint:<path>, <module>:<name>",
    )
    for name in (
        "battles",
        "threads",
        "steps",
        "updates",
        "epochs",
        "minibatches",
        "eval_every",
        "eval_games",
        "seed",
    ):
        flag = "--" + name.replace("_", "-")
        parser.add_argument(flag, type=int, default=getattr(defaults, name))
    for name in (
        "minutes",
        "learning_rate",
        "gamma",
        "lam",
        "clip",
        "entropy",
        "value",
        "max_grad_norm",
        "shaping",
        "leak",
    ):
        parser.add_argument(
            "--" + name.replace("_", "-"), type=float, default=getattr(defaults, name)
        )
    parser.add_argument("--decision-ticks", type=int)
    parser.add_argument("--level", type=int)
    parser.add_argument("--device", default=defaults.device, help="auto, cpu, mps, cuda")
    args = parser.parse_args(argv)
    values = vars(args)
    values["deck"] = tuple(card.strip() for card in values["deck"].split(","))
    cfg = TrainConfig(**values)
    result = train(cfg)
    print(
        json.dumps(
            {
                "updates": result.updates,
                "learner_steps": result.learner_steps,
                "wall_s": round(result.wall_s, 1),
                "steps_per_s": round(result.steps_per_s, 1),
                "faults": result.faults["by_kind"],
                "last_evaluations": result.evaluations[-len(cfg.eval_opponents.split(",")) :],
            }
        )
    )


if __name__ == "__main__":
    main()
