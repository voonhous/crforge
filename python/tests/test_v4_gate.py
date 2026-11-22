"""The migration gate: the v4 adapter on the exported fields equals the Java v4 observation.

At every decision of every battle, for both seats, ``V4Adapter.observe_both`` on ``battle.fields``
must equal ``battle.observations`` bit for bit, and the export must have kept every entity
(``entity.export_overflow`` is 0): no decision is filtered out. The battles are random, seeded and
mask-legal, on several decks with evolution and hero slots and a champion, long enough that
episodes end and autoreset starts new ones; and the other ways a battle's state is replaced are
gated the same way: a fork moved in (``fork_from``), a replay's start (``start_from_replays``, on
replays the vector wrote and on the example battles), ``reset_env`` mid-run and a fault.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pytest

from crforge_env import LadderConfig, VectorBattle
from crforge_env.adapters.v4 import V4Adapter
from crforge_env.bench import draw_actions
from crforge_env.fields import Registry
from tests.decks import BLUE, RED, arena

pytestmark = pytest.mark.ladder

EXAMPLES = Path(__file__).parents[2] / "examples"
"""The example battles, ``<data version>/battles/<name>/scenario.json``."""
# The example battles a replay start takes: the generated "knight" battle has no side setups
# (srq), which a start needs, so it is not among them.
EXAMPLE_REPLAYS = ("real_ladder", "scripted_ladder")

OTHER = ["Valkyrie", "HogRider", "Wizard", "Bomber", "Barbarians", "MegaMinion", "Minions"]
OTHER += ["SpearGoblins"]
CHAMPION = ["ArcherQueen", "Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball"]
CHAMPION += ["Arrows"]


def deck_pool(schema):
    """The decks the battles cycle through, with their slot flags: none, an evolution, a hero."""
    none = [0] * schema.deck_size
    evolution = [0, schema.slot_evolution] + [0] * (schema.deck_size - 2)
    hero = [0, 0, 0, schema.slot_hero] + [0] * (schema.deck_size - 4)
    return [(BLUE, none), (RED, evolution), (OTHER, none), (CHAMPION, hero), (BLUE, hero)]


def reset_mixed(v, seeds):
    """Resets ``v`` with each battle on decks of the pool, both seats on different ones."""
    pool = deck_pool(v.schema)
    picks = [(pool[i % len(pool)], pool[(i + 2) % len(pool)]) for i in range(v.count)]
    v.reset(
        seeds,
        [a[0] for a, _ in picks],
        [b[0] for _, b in picks],
        slots0=[a[1] for a, _ in picks],
        slots1=[b[1] for _, b in picks],
    )


class Gate:
    """Holds one vector's observations to the v4 adapter's rebuild, and counts what it held."""

    def __init__(self, v: VectorBattle):
        assert v.fields is not None
        self.registry = Registry.from_metadata(v.metadata["fields"])
        self.adapter = V4Adapter(v.schema)
        self.adapter.bind(self.registry)
        self.overflow = self.registry.field("entity.export_overflow").offset
        self.out = np.zeros(v.observations.shape, dtype=np.int32)
        self.decisions = 0
        self.ended = 0

    def check(self, v: VectorBattle, where: str) -> None:
        overflow = v.fields[:, self.overflow]
        assert not overflow.any(), f"{where}: the export dropped entities, {overflow.tolist()}"
        view = self.registry.view(v.fields, self.adapter.reads)
        rebuilt = self.adapter.observe_both(view, out=self.out)
        if not np.array_equal(rebuilt, v.observations):
            env, seat, index = np.argwhere(rebuilt != v.observations)[0]
            raise AssertionError(
                f"{where}: battle {env} seat {seat} int {index} is {rebuilt[env, seat, index]} "
                f"rebuilt, {v.observations[env, seat, index]} in the observation"
            )
        self.decisions += v.count

    def step(self, v: VectorBattle, rng: np.random.Generator, where: str, play_rate=0.5) -> None:
        v.step(draw_actions(v.masks, rng, play_rate))
        self.ended += int(v.dones.sum())
        self.check(v, where)


@pytest.mark.parametrize(
    ("count", "threads", "max_ticks", "steps"),
    [
        # Full battles, played to their end (overtime included) and restarted.
        (16, 1, None, 1300),
        # Battles cut at a tick limit, so each battle ends and restarts several times.
        (18, 3, 400, 400),
    ],
)
def test_every_decision_of_random_battles_is_rebuilt(
    install, tables, count, threads, max_ticks, steps
):
    config = LadderConfig(max_ticks=max_ticks)
    with VectorBattle(
        count, threads, install=install, tables=tables, config=config, fields=True
    ) as v:
        gate = Gate(v)
        reset_mixed(v, list(range(100, 100 + count)))
        gate.check(v, "reset")
        rng = np.random.default_rng(count * 10 + threads)
        restarted = 0
        for step in range(steps):
            gate.step(v, rng, f"step {step}")
            restarted += int(v.infos[:, v.schema.info["reset"]].sum())
            if step in (37, 211):
                # A restart mid-run, on the battle's own decks.
                for i in (1, count - 2):
                    v.reset_env(i, 7_000 + step + i)
                gate.check(v, f"reset_env at step {step}")
        assert gate.decisions == count * (steps + 3)
        assert gate.ended >= count, f"only {gate.ended} episodes ended"
        assert restarted >= count


def test_forks_are_rebuilt_as_they_move_in_and_step_on(install, tables):
    # A tick limit ends the forks soon after they move in, so autoreset restarts them too.
    config = LadderConfig(max_ticks=1500)
    with (
        VectorBattle(2, 1, install=install, tables=tables, config=config, fields=True) as source,
        VectorBattle(6, 3, install=install, tables=tables, config=config, fields=True) as forks,
    ):
        source_gate, gate = Gate(source), Gate(forks)
        reset_mixed(source, [41, 42])
        reset_mixed(forks, list(range(51, 57)))
        rng = np.random.default_rng(8)
        for step in range(160):
            source_gate.step(source, rng, f"source step {step}")
            gate.step(forks, rng, f"fork vector step {step}")
        moved = 0
        # The battle as it is, then as each seat knows it, its unseen enemy cards dealt again.
        for index, seat, targets in ((0, -1, [0, 1]), (1, 0, [2, 3]), (0, 1, [4, 5])):
            if seat < 0:
                forks.fork_from(source, index, seat, targets)
            else:
                n = len(targets)
                forks.fork_from(
                    source, index, seat, targets, shuffles=[3 + t for t in targets], seeds=[9] * n
                )
            moved += len(targets)
            gate.check(forks, f"fork of battle {index} seat {seat} moved in")
        for step in range(240):
            gate.step(forks, rng, f"forks step {step}")
        assert moved == 6
        assert gate.ended >= 6


def test_replay_starts_are_rebuilt_before_and_after_stepping(install, tables):
    config = LadderConfig(arena=arena(tables))
    decision = 5
    with VectorBattle(6, 3, install=install, tables=tables, config=config, fields=True) as v:
        decision = int(v.metadata["config"]["decision_ticks"])
        gate = Gate(v)
        reset_mixed(v, list(range(61, 67)))
        rng = np.random.default_rng(4)
        for step in range(500):
            gate.step(v, rng, f"step {step}")
        # Replays the vector wrote, started at several decisions of each (autoreset restarted any
        # battle that ended, so each replay is its battle's episode so far).
        written = [json.loads(v.replay_json(i)) for i in range(v.count)]
        battles = EXAMPLES / v.data_version / "battles"
        examples = [
            json.loads((battles / n / "scenario.json").read_text())
            for n in EXAMPLE_REPLAYS
            if (battles / n).is_dir()
        ]
        starts = 0
        for fraction in (0.0, 0.3, 0.9):
            ticks = [int(r["endTick"] * fraction) // decision * decision for r in written]
            ticks = [min(t, r["endTick"] - decision) for t, r in zip(ticks, written, strict=True)]
            v.start_from_replays(range(v.count), written, ticks)
            starts += v.count
            gate.check(v, f"start of the written replays at {ticks}")
            for step in range(40):
                gate.step(v, rng, f"step {step} after the written replays at {ticks}")
        # The example battles, each at several ticks, the last of them a decision before its end.
        for replay in examples:
            last = (replay["endTick"] - 1) // decision * decision - decision
            ticks = [0, decision, 500, 1500, last - 100, last]
            v.start_from_replays(range(v.count), [replay] * v.count, ticks)
            starts += v.count
            gate.check(v, f"start of an example battle at {ticks}")
            for step in range(60):
                gate.step(v, rng, f"step {step} after an example battle at {ticks}")
        assert starts == 3 * v.count + len(examples) * v.count
        assert gate.ended >= 1
        if not examples:
            pytest.skip(f"no example battles of data version {v.data_version} to start on")


# Three champion cards in one deck: a battle whose setup the battle core refuses, a fault.
THREE_CHAMPIONS = ["ArcherQueen", "GoldenKnight", "SkeletonKing", "Knight", "Archer", "Musketeer"]
THREE_CHAMPIONS += ["Giant", "MiniPekka"]


def test_a_faulted_battle_is_rebuilt_as_its_cleared_observations(install, tables):
    with VectorBattle(3, 1, install=install, tables=tables, fields=True) as v:
        gate = Gate(v)
        v.reset([1, 2, 3], [BLUE, THREE_CHAMPIONS, BLUE], RED)
        gate.check(v, "reset")
        rng = np.random.default_rng(12)
        faults = 0
        for step in range(30):
            gate.step(v, rng, f"step {step}")
            faulted = v.infos[:, v.schema.info["fault"]] != 0
            faults += int(faulted.sum())
            # A fault clears the battle's observations and its block with them.
            assert not v.fields[faulted].any() and not v.observations[faulted].any()
        assert faults > 0
        assert not v.infos[[0, 2], v.schema.info["fault"]].any()
