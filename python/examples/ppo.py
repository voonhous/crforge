"""A minimal PPO trainer on the vector of battles, in one file.

It is an example to read and to copy, not a framework: one policy, one deck played by both sides,
the result of the match as the reward. Run it from ``python/`` (torch is not a dependency of
``crforge-env``, so it is added for the run):

    uv run --with torch python examples/ppo.py --install ../env/build/install/env \
        --tables ~/.crforge/tables/<data version> --out runs/ppo --minutes 20

What it does, in the order of the file:

- **The policy** (:class:`Policy`): the header's amounts scaled to about 0 to 1, the card ids
  through one embedding of the vocabulary, and the entity rows through a shared network pooled by
  mean and max (the order of the rows does not matter). One masked categorical over every action,
  whose logits are built the way the action is: the wait, each hand slot's logit plus a tile's
  (one tile head for the four slots, given the slot's card), then the champion abilities.
- **The rollout** (:func:`train`): the learner plays one seat of each battle, seat 0 in even
  battles and seat 1 in odd ones, against ``--opponent`` (``random``, ``scripted`` or ``wait``),
  or both seats of every battle with ``--opponent self``. The seat frames make the two sides the
  same game for a policy, so one policy plays either.
- **The reward**: the match's result from the environment (+1, -1, 0 for a draw), plus
  ``--shaping`` times the change of the tower balance (the seat's share of tower hit points left
  less the enemy's), which is a potential: over an episode it sums to the final balance.
- **Episode ends**: the vector restarts a finished battle on the step after its end and ignores
  that step's actions, so that step's sample is left out of the loss. An episode cut short (by
  a fault or a tick limit) has no result: its last step takes no shaping and bootstraps from the
  value of its own observation, so it is never learned from as a win or a loss.
- **The update**: the advantages by GAE, then clipped policy and value losses over minibatches,
  as in CleanRL's PPO.
- **The evaluation**: every ``--eval-every`` updates ``crforge_env.evaluate`` plays the policy
  against ``--eval-opponent`` on battles of its own, and the win rate goes to ``curve.csv``. The
  checkpoint (``policy.pt``) holds the weights, the settings and the vector's metadata, the
  schema and vocabulary a model must be read with, and the run's provenance record: which fields
  each role read (``crforge_env.adapters.provenance``). The policy and its value head read the
  learner seat's own v4 observation and nothing else, so both are recorded as the ``v4``
  adapter's reads; neither reads a field the seat is not shown.
"""

from __future__ import annotations

import argparse
import csv
import time
from pathlib import Path

import numpy as np
import torch
from torch import nn
from torch.distributions import Categorical

from crforge_env import ScriptedBot, VectorBattle, evaluate, random_opponent, wait_opponent
from crforge_env.adapters import V4Adapter, provenance
from crforge_env.evaluation import policy_adapters
from crforge_env.fields import Registry

DECK = "Knight,Archer,Musketeer,Giant,MiniPekka,Fireball,Arrows,Zap"
OPPONENTS = {
    "random": lambda: random_opponent,
    "wait": lambda: wait_opponent,
    "scripted": ScriptedBot,
}
TOWERS = [f"{owner}_{tower}" for owner in ("own", "enemy") for tower in ("king", "left", "right")]
# The header amounts the encoder reads, each with the scale that brings it to about 0 to 1.
SCALED = {
    "elixir": 100_000.0,  # ten-thousandths of an elixir, 10 at most
    "elixir_reserved": 100_000.0,
    "enemy_elixir": 100_000.0,
    "crowns": 3.0,
    "crowns_enemy": 3.0,
    "hand_cost": 10.0,
    "hand_pending": 1.0,
    "hand_refill_ms": 3_000.0,
    "time_ms": 300_000.0,
    "section_left_ms": 180_000.0,
    "full_bar_ms": 30_000.0,
    "overtime": 1.0,
    "play_open_in_ticks": 100.0,
    "pending_count": 4.0,
}
KINDS, SIDES, FLAG_BITS = 5, 3, 12


def layer(inputs: int, outputs: int, gain: float = 2**0.5) -> nn.Linear:
    linear = nn.Linear(inputs, outputs)
    nn.init.orthogonal_(linear.weight, gain)
    nn.init.zeros_(linear.bias)
    return linear


class Policy(nn.Module):
    """The policy and value network of one seat. Every size and index is read from the schema."""

    def __init__(self, schema, vocabulary_size: int, entities: int, width: int = 256, d: int = 32):
        super().__init__()
        self.header_ints, self.entity_ints, self.entities = (
            schema.header_ints,
            schema.entity_ints,
            entities,
        )
        self.slots, self.tiles = schema.hand_slots, schema.cols * schema.rows
        self.vocabulary_size = vocabulary_size
        self.e = dict(schema.entity)
        self.units = (float(schema.width_units), float(schema.height_units))

        def at(*names: str) -> torch.Tensor:
            return torch.tensor([i for name in names for i in schema.header[name]])

        self.register_buffer("scaled_at", at(*SCALED), persistent=False)
        scales = [scale for name, scale in SCALED.items() for _ in schema.header[name]]
        self.register_buffer("scales", torch.tensor(scales), persistent=False)
        self.register_buffer("hp_at", at(*(f"{t}_hp" for t in TOWERS)), persistent=False)
        self.register_buffer("max_hp_at", at(*(f"{t}_max_hp" for t in TOWERS)), persistent=False)
        for name in ("hand_card", "hand_cost", "next_card", "deck", "enemy_seen"):
            self.register_buffer(f"{name}_at", at(name), persistent=False)
        self.count_at = schema.header["entity_count"][0]
        self.register_buffer("flag_bits", 1 << torch.arange(FLAG_BITS), persistent=False)

        self.cards = nn.Embedding(vocabulary_size + 1, d)  # id + 1, so 0 is "none"
        self.entity_net = nn.Sequential(
            layer(d + KINDS + SIDES + 8 + FLAG_BITS, 64), nn.ReLU(), layer(64, 64), nn.ReLU()
        )
        trunk_in = len(scales) + len(TOWERS) + (self.slots + 3) * d + 2 * 64
        self.trunk = nn.Sequential(
            nn.LayerNorm(trunk_in),
            layer(trunk_in, width),
            nn.ReLU(),
            layer(width, width),
            nn.ReLU(),
        )
        self.value_head = layer(width, 1, 1.0)
        self.wait_head = layer(width, 1, 0.01)
        self.slot_head = nn.Sequential(layer(width + d + 1, 128), nn.ReLU(), layer(128, 1, 0.01))
        self.tile_head = nn.Sequential(
            layer(width + d, 128), nn.ReLU(), layer(128, self.tiles, 0.01)
        )
        self.ability_head = layer(width, schema.actions - schema.first_ability, 0.01)

    def embed(self, ids: torch.Tensor) -> torch.Tensor:
        """Vocabulary ids (-1 for none) as embeddings."""
        return self.cards((ids.long() + 1).clamp(0, self.vocabulary_size))

    def embed_set(self, ids: torch.Tensor) -> torch.Tensor:
        """The mean embedding of a field of several ids, over those that are not -1."""
        present = (ids >= 0).float().unsqueeze(-1)
        return (self.embed(ids) * present).sum(1) / present.sum(1).clamp(min=1.0)

    def forward(self, observations: torch.Tensor, masks: torch.Tensor):
        """The masked logits ``(B, actions)`` and the value ``(B,)`` of a batch of seat
        observations cut after ``entities`` rows, and their masks (bool)."""
        batch = observations.shape[0]
        header = observations[:, : self.header_ints]
        h = header.float()
        hp, top = h[:, self.hp_at], h[:, self.max_hp_at]
        towers = hp / top.clamp(min=1.0)
        hand = self.embed(header[:, self.hand_card_at])  # (B, slots, d)

        rows = observations[:, self.header_ints :].reshape(batch, self.entities, self.entity_ints)
        e, r = self.e, rows.float()
        written = torch.arange(self.entities, device=rows.device) < header[:, self.count_at, None]
        numbers = torch.stack(
            [
                r[..., e["x"]] / self.units[0],
                r[..., e["y"]] / self.units[1],
                r[..., e["hp"]].clamp(min=0.0) / r[..., e["max_hp"]].clamp(min=1.0),
                (r[..., e["hp"]] < 0).float(),  # an enemy whose health bar the screen does not draw
                torch.log1p(r[..., e["max_hp"]].clamp(min=0.0)) / 10.0,
                r[..., e["dx"]] / 1000.0,
                r[..., e["dy"]] / 1000.0,
                r[..., e["state"]].clamp(min=0.0) / 16.0,
            ],
            dim=-1,
        )
        features = torch.cat(
            [
                self.embed(rows[..., e["row"]]),
                nn.functional.one_hot(rows[..., e["kind"]].long().clamp(0, KINDS - 1), KINDS),
                nn.functional.one_hot(rows[..., e["side"]].long().clamp(0, SIDES - 1), SIDES),
                numbers,
                (rows[..., e["flags"], None].long() & self.flag_bits) != 0,
            ],
            dim=-1,
        )
        weight = written.unsqueeze(-1).float()
        encoded = self.entity_net(features) * weight  # not negative (ReLU): 0 stands for "no row"
        pooled = [encoded.sum(1) / weight.sum(1).clamp(min=1.0), encoded.amax(1)]

        trunk = self.trunk(
            torch.cat(
                [
                    h[:, self.scaled_at] / self.scales,
                    towers,
                    hand.flatten(1),
                    self.embed(header[:, self.next_card_at]).squeeze(1),
                    self.embed_set(header[:, self.deck_at]),
                    self.embed_set(header[:, self.enemy_seen_at]),
                    *pooled,
                ],
                dim=1,
            )
        )
        per_slot = trunk.unsqueeze(1).expand(-1, self.slots, -1)
        costs = h[:, self.hand_cost_at].unsqueeze(-1) / 10.0
        slot = self.slot_head(torch.cat([per_slot, hand, costs], dim=-1))  # (B, slots, 1)
        tile = self.tile_head(torch.cat([per_slot, hand], dim=-1))  # (B, slots, tiles)
        logits = torch.cat(
            [self.wait_head(trunk), (slot + tile).flatten(1), self.ability_head(trunk)], 1
        )
        return logits.masked_fill(~masks, -1e9), self.value_head(trunk).squeeze(-1)


def tower_balance(schema, observations: np.ndarray) -> np.ndarray:
    """The seat's share of tower hit points left less the enemy's, between -1 and 1."""
    shares = [
        observations[:, schema.header[f"{t}_hp"][0]]
        / np.maximum(observations[:, schema.header[f"{t}_max_hp"][0]], 1)
        for t in TOWERS
    ]
    return (sum(shares[:3]) - sum(shares[3:])) / 3.0


def as_player(model: Policy, obs_ints: int, device: torch.device):
    """The model as a policy of ``crforge_env``'s shape, ``(observation, mask, rng) -> action``,
    drawing from its probabilities with the caller's generator."""

    def play(observation: np.ndarray, mask: np.ndarray, rng: np.random.Generator) -> int:
        with torch.no_grad():
            logits, _ = model(
                torch.from_numpy(observation[None, :obs_ints].copy()).to(device),
                torch.from_numpy(mask[None].astype(bool)).to(device),
            )
        odds = np.cumsum(torch.softmax(logits[0], 0).double().cpu().numpy())
        return int(min(np.searchsorted(odds, rng.random() * odds[-1], side="right"), len(odds) - 1))

    return play


def train(args: argparse.Namespace) -> None:
    torch.manual_seed(args.seed)
    rng = np.random.default_rng(args.seed)
    device = torch.device(args.device)
    deck = args.deck.split(",")
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    engine = dict(install=args.install, tables=args.tables, engine=args.engine)
    if args.engine == "synthetic":
        engine["synthetic_episode_steps"] = args.synthetic_episode_steps

    with VectorBattle(args.envs, args.threads, **engine) as vector:
        s = vector.schema
        vector.write_metadata(out / "metadata.json")
        entities = min(args.entities, s.max_entities)
        obs_ints = s.header_ints + entities * s.entity_ints
        model = Policy(s, vector.vocabulary_size, entities).to(device)
        optimizer = torch.optim.Adam(model.parameters(), lr=args.lr, eps=1e-5)

        # The learner's seats: (battle, seat) pairs. Against an opponent, one seat of each battle.
        self_play = args.opponent == "self"
        env_at = np.repeat(np.arange(args.envs), 2) if self_play else np.arange(args.envs)
        seat_at = np.tile([0, 1], args.envs) if self_play else np.arange(args.envs) % 2
        opponent = None if self_play else OPPONENTS[args.opponent]()
        if hasattr(opponent, "bind"):
            opponent.bind(vector)
        opponent_rngs = [np.random.default_rng([args.seed, i]) for i in range(args.envs)]
        eval_opponent = OPPONENTS[args.eval_opponent]()

        # What each role reads, kept in the checkpoint. The model is handed the learner seat's
        # own v4 observation (its first `entities` rows), the policy and the value head alike,
        # so both read the v4 adapter's fields; the opponent in self-play is the same model on
        # the other seat's own observation. The other opponents name what they read.
        registry = Registry.from_metadata(vector.metadata["fields"])
        v4 = V4Adapter(s)
        v4.bind(registry)
        record = provenance(
            {
                "learner": [v4],
                "critic": [v4],
                "opponent": [v4] if self_play else policy_adapters(opponent, registry),
                "eval_opponent": policy_adapters(eval_opponent, registry),
            },
            registry,
        )

        def read() -> tuple[torch.Tensor, torch.Tensor, np.ndarray]:
            """The learner seats' observations and masks now, copied out of the JVM's buffers."""
            observations = vector.observations[env_at, seat_at, :obs_ints]
            masks = vector.masks[env_at, seat_at].astype(bool)
            return (
                torch.from_numpy(observations).to(device),
                torch.from_numpy(masks).to(device),
                observations,
            )

        def save(update: int, decisions: int) -> None:
            torch.save(
                {"model": model.state_dict(), "args": vars(args), "metadata": vector.metadata,
                 "entities": entities, "update": update, "decisions": decisions,
                 "provenance": record},
                out / "policy.pt",
            )  # fmt: skip

        def evaluation(update: int, decisions: int, seconds: float) -> None:
            model.eval()
            result = evaluate(
                as_player(model, obs_ints, device), eval_opponent, deck=deck,
                episodes=args.eval_episodes, seed=args.seed + 1, **engine,
            )  # fmt: skip
            model.train()
            row = [update, decisions, round(seconds), result.episodes, result.wins, result.losses,
                   result.draws, result.truncated, f"{result.win_rate:.3f}"]  # fmt: skip
            with (out / "curve.csv").open("a", newline="") as file:
                csv.writer(file).writerow(row)
            print(
                f"eval   update {update:4d}  vs {args.eval_opponent}: {result.wins} of "
                f"{result.episodes} ({result.win_rate:.2f}), {result.losses} lost, "
                f"{result.draws} drawn, {result.truncated} cut short",
                flush=True,
            )
            save(update, decisions)

        with (out / "curve.csv").open("w", newline="") as file:
            csv.writer(file).writerow(
                "update decisions seconds episodes wins losses draws truncated win_rate".split()
            )

        steps, seats = args.steps, len(env_at)
        b_obs = torch.zeros((steps, seats, obs_ints), dtype=torch.int32, device=device)
        b_masks = torch.zeros((steps, seats, s.actions), dtype=torch.bool, device=device)
        b_actions = torch.zeros((steps, seats), dtype=torch.long, device=device)
        b_logp, b_values, b_rewards, b_dones, b_valid = (
            torch.zeros((steps, seats), device=device) for _ in range(5)
        )

        vector.reset([int(x) for x in rng.integers(0, 2**31, args.envs)], deck, deck)
        resetting = np.zeros(args.envs, dtype=bool)  # battles the next step restarts
        start, decisions, update = time.time(), 0, 0
        evaluation(0, 0, 0.0)
        while update < args.updates and time.time() - start < args.minutes * 60:
            update += 1
            wins = losses = episodes = faults = 0
            for t in range(steps):
                observations, masks, raw = read()
                with torch.no_grad():
                    logits, values = model(observations, masks)
                    distribution = Categorical(logits=logits)
                    actions = distribution.sample()
                b_obs[t], b_masks[t], b_actions[t] = observations, masks, actions
                b_logp[t], b_values[t] = distribution.log_prob(actions), values
                b_valid[t] = torch.from_numpy(~resetting[env_at]).to(device)
                before = tower_balance(s, raw)

                vector.actions[env_at, seat_at] = actions.cpu().numpy()
                if opponent is not None:
                    for i in np.flatnonzero(~resetting):
                        seat = 1 - seat_at[i]
                        vector.actions[i, seat] = opponent(
                            vector.observations[i, seat], vector.masks[i, seat], opponent_rngs[i]
                        )
                vector.step()

                done = vector.dones.astype(bool)
                # An episode cut short: done with no winner. Its observation may be a cleared one.
                cut = (done & (vector.infos[:, s.info["winner"]] == s.winner_none))[env_at]
                after = tower_balance(s, vector.observations[env_at, seat_at, : s.header_ints])
                rewards = vector.rewards[env_at, seat_at] + args.shaping * (after - before) * ~cut
                rewards = torch.from_numpy(rewards.astype(np.float32)).to(device)
                rewards += args.gamma * b_values[t] * torch.from_numpy(cut).to(device)
                b_rewards[t] = rewards * b_valid[t]
                b_dones[t] = torch.from_numpy(done[env_at].astype(np.float32)).to(device)
                for i in np.flatnonzero(done & ~resetting):
                    episodes += 1
                    faults += int(vector.infos[i, s.info["fault"]] != 0)
                    winner = int(vector.infos[i, s.info["winner"]])
                    if not self_play and winner in (0, 1):
                        wins += winner == seat_at[i]
                        losses += winner != seat_at[i]
                decisions += int((~resetting[env_at]).sum())
                resetting = done

            # The advantages by GAE. A finished episode's value does not flow back over its end.
            with torch.no_grad():
                next_value = model(*read()[:2])[1]
            advantages, last = torch.zeros_like(b_rewards), torch.zeros(seats, device=device)
            for t in reversed(range(steps)):
                running = 1.0 - b_dones[t]
                following = next_value if t == steps - 1 else b_values[t + 1]
                delta = b_rewards[t] + args.gamma * following * running - b_values[t]
                advantages[t] = last = delta + args.gamma * args.gae_lambda * running * last
            returns = advantages + b_values

            # The clipped update, over the samples that are not a restart's.
            kept = b_valid.flatten().nonzero().squeeze(1)
            flat = [
                x.flatten(0, 1) for x in (b_obs, b_masks, b_actions, b_logp, advantages, returns)
            ]
            for _ in range(args.epochs):
                for index in kept[torch.randperm(len(kept), device=device)].chunk(args.minibatches):
                    obs, mask, action, old_logp, advantage, target = (x[index] for x in flat)
                    logits, value = model(obs, mask)
                    distribution = Categorical(logits=logits)
                    ratio = (distribution.log_prob(action) - old_logp).exp()
                    advantage = (advantage - advantage.mean()) / (advantage.std() + 1e-8)
                    policy_loss = -torch.min(
                        ratio * advantage, ratio.clamp(1 - args.clip, 1 + args.clip) * advantage
                    ).mean()
                    value_loss = 0.5 * (value - target).pow(2).mean()
                    entropy = distribution.entropy().mean()
                    loss = policy_loss + args.value_coef * value_loss - args.entropy_coef * entropy
                    optimizer.zero_grad()
                    loss.backward()
                    nn.utils.clip_grad_norm_(model.parameters(), args.max_grad_norm)
                    optimizer.step()

            seconds = time.time() - start
            games = "" if self_play else f"  won {wins} lost {losses}"
            print(
                f"update {update:4d}  decisions {decisions:9d}  {decisions / seconds:6.0f}/s  "
                f"episodes {episodes:3d}{games}  faults {faults}  "
                f"policy {policy_loss.item():+.4f}  value {value_loss.item():.4f}  "
                f"entropy {entropy.item():.3f}",
                flush=True,
            )
            if update % args.eval_every == 0:
                evaluation(update, decisions, seconds)
        if update % args.eval_every != 0:
            evaluation(update, decisions, time.time() - start)


def main(argv: list[str] | None = None) -> None:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--install", required=True, help="the env module's installed distribution")
    p.add_argument("--tables", help="the game tables folder of a data version (ladder engine)")
    p.add_argument("--engine", default="ladder", choices=["ladder", "synthetic"])
    p.add_argument("--out", default="runs/ppo", help="the run folder: curve.csv, policy.pt")
    p.add_argument("--deck", default=DECK, help="eight card row names, played by both sides")
    p.add_argument("--opponent", default="random", choices=[*OPPONENTS, "self"])
    p.add_argument("--eval-opponent", default="random", choices=list(OPPONENTS))
    p.add_argument("--eval-every", type=int, default=20, help="updates between evaluations")
    p.add_argument("--eval-episodes", type=int, default=32)
    p.add_argument("--updates", type=int, default=10**9, help="stop after this many updates")
    p.add_argument("--minutes", type=float, default=30.0, help="or after this many minutes")
    p.add_argument("--envs", type=int, default=64, help="battles stepped together")
    p.add_argument("--threads", type=int, default=4, help="JVM threads stepping them")
    p.add_argument("--steps", type=int, default=128, help="decisions of each battle a rollout")
    p.add_argument("--entities", type=int, default=64, help="entity rows the encoder reads")
    p.add_argument("--shaping", type=float, default=0.5, help="weight of the tower balance")
    p.add_argument("--lr", type=float, default=3e-4)
    p.add_argument("--gamma", type=float, default=0.999)
    p.add_argument("--gae-lambda", type=float, default=0.95)
    p.add_argument("--clip", type=float, default=0.2)
    p.add_argument("--epochs", type=int, default=3)
    p.add_argument("--minibatches", type=int, default=4)
    p.add_argument("--value-coef", type=float, default=0.5)
    p.add_argument("--entropy-coef", type=float, default=0.01)
    p.add_argument("--max-grad-norm", type=float, default=0.5)
    p.add_argument("--seed", type=int, default=0)
    p.add_argument("--device", default="cpu", help="cpu, mps or cuda")
    p.add_argument("--synthetic-episode-steps", type=int, default=900, help="synthetic engine only")
    args = p.parse_args(argv)
    if args.engine == "ladder" and args.tables is None:
        p.error("--tables is required for the ladder engine")
    if args.engine == "synthetic" and "scripted" in (args.opponent, args.eval_opponent):
        p.error("the scripted bot reads the game tables: use the ladder engine")
    train(args)


if __name__ == "__main__":
    main()
