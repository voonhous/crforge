"""The registry 1.1 adapters on real Ladder rollouts (``VectorBattle(fields=True)``).

Each adapter is held to what the battle itself says, read in the test only: the attack cooldown to
the tick each unit's next hit fires (one-tick decisions, so every tick is seen), the hit points to
the true hit points (``entity.hp.true``, a hidden field the adapter never reads), and the own plays
to the other seat's true record of them and to the commands of the battle's replay.
"""

from __future__ import annotations

import json
from collections import Counter, defaultdict

import numpy as np
import pytest

from crforge_env import LadderConfig, VectorBattle
from crforge_env.adapters import AttackCooldown, EnemyHitPoints, OwnPlays
from crforge_env.adapters.plays import COLUMNS
from crforge_env.bench import draw_actions
from crforge_env.fields import Registry
from tests.decks import BLUE, arena

pytestmark = pytest.mark.ladder

# Charging units, a load-first-hit unit, a load above the hit speed, splash and air.
ATTACKERS = ["Prince", "ZapMachine", "InfernoTower", "Valkyrie", "Wizard", "Bomber", "MegaMinion"]
ATTACKERS += ["HogRider"]
# Units whose bar shows from their first damage on, and a tunnelling one.
BARS = ["DarkPrince", "Miner", "Knight", "Archer", "Musketeer", "Giant", "Valkyrie", "Fireball"]
ATTACKING = 2
ALIVE = 1
WIDTH, LENGTH = 18000, 32000


def test_the_cooldown_is_the_tick_each_units_next_hit_fires(install, tables):
    """At every tick, each exact count of a unit that then attacks the same living target at the
    same hit speed until the count runs out is held to the tick its timer next crosses a multiple
    of its hit speed (or, for a row whose load comes first, restarts)."""
    config = LadderConfig(decision_ticks=1)
    with VectorBattle(4, 2, install=install, tables=tables, config=config, fields=True) as v:
        v.reset([41, 42, 43, 44], [BLUE, ATTACKERS] * 2, [ATTACKERS, BLUE] * 2)
        registry = Registry.from_metadata(v.metadata["fields"])
        cooldown = AttackCooldown()
        cooldown.bind(registry)
        assert cooldown.hidden is False
        view = cooldown.view(v.fields)
        f = registry.views(v.fields)
        hit_speed = registry.rows["hit_speed_ms"]
        load_first = registry.rows["attack_traits"] & 1
        rng = np.random.default_rng(2)
        # Per (battle, seat, entity id): its ticks, each (tick, state, attack_ms, target,
        # hit_speed_percent, row, ms, exact, target alive); None where an episode ended.
        seen: dict[tuple, list] = defaultdict(list)
        for _ in range(2000):
            v.step(draw_actions(v.masks, rng, 0.05))
            done = v.dones.astype(bool)
            for seat in (0, 1):
                out = cooldown.observe(view, seat)
                for i in range(v.count):
                    if done[i]:
                        for key in [k for k in seen if k[:2] == (i, seat)]:
                            seen[key].append(None)
                        continue
                    shown = f["entity.visible"][i, seat] != 0
                    alive = shown & ((f["entity.flags"][i, seat] & ALIVE) != 0)
                    living = set(f["entity.id"][i, seat][alive].tolist())
                    for r in np.flatnonzero(shown & (f["entity.row"][i, seat] >= 0)):
                        target = int(f["entity.target"][i, seat, r])
                        seen[(i, seat, int(f["entity.id"][i, seat, r]))].append((
                            int(f["game.tick"][i]), int(f["entity.state"][i, seat, r]),
                            int(f["entity.attack_ms"][i, seat, r]), target,
                            int(f["entity.hit_speed_percent"][i, seat, r]),
                            int(f["entity.row"][i, seat, r]), int(out["ms"][i, r]),
                            bool(out["exact"][i, r]), target in living,
                        ))  # fmt: skip
        agree, held = Counter(), Counter()
        for ticks in seen.values():
            episode: list = []
            for item in [*ticks, None]:
                if item is not None:
                    episode.append(item)
                    continue
                for a, (tick, _, timer, target, pace, row, ms, exact, living) in enumerate(episode):
                    n = ms // 50
                    after = episode[a + 1 : a + n + 1]
                    if not exact or not living or target < 0 or len(after) < n:
                        continue
                    if any(
                        w[0] != tick + k + 1 or w[1] != ATTACKING or w[3] != target
                        or w[4] != pace or not w[8]
                        for k, w in enumerate(after)
                    ):  # fmt: skip
                        continue
                    h, before, fired = int(hit_speed[row]), timer, None
                    for k, w in enumerate(episode[a + 1 : a + n + 3]):
                        if w[2] // h > before // h or (load_first[row] and w[2] < before):
                            fired = k + 1
                            break
                        before = w[2]
                    held[v.vocabulary[row]] += 1
                    agree[v.vocabulary[row]] += fired == n
                episode = []
        total, right = sum(held.values()), sum(agree.values())
        assert total > 10_000, held
        assert {"Prince", "InfernoTower", "PrincessTower", "KingTower"} <= set(held)
        # Measured on 16.635.8: 99.9 percent, the misses one tower whose timer restarted on a
        # target it kept, which the count does not foresee.
        assert right >= 0.995 * total, (right, total, {k: (agree[k], held[k]) for k in held})


def test_the_hit_points_known_are_the_true_hit_points(install, tables):
    with VectorBattle(4, 2, install=install, tables=tables, fields=True) as v:
        v.reset([51, 52, 53, 54], BARS, BARS)
        registry = Registry.from_metadata(v.metadata["fields"])
        hit_points = EnemyHitPoints()
        hit_points.bind(registry)
        assert hit_points.hidden is False and "entity.hp.true" not in hit_points.reads
        view = hit_points.view(v.fields)
        f = registry.views(v.fields)
        rng = np.random.default_rng(4)
        counts = Counter()
        for _ in range(1200):
            v.step(draw_actions(v.masks, rng, 0.3))
            for seat in (0, 1):
                out = hit_points.observe(view, seat)
                known = out["known"]
                truth = f["entity.hp.true"]
                bad = known & (out["hp"] != truth)
                assert not bad.any(), np.argwhere(bad)[:5]
                np.testing.assert_allclose(
                    out["fill"][known],
                    truth[known] / np.maximum(f["entity.max_hp.true"][known], 1),
                    rtol=1e-6,
                )
                units = (f["entity.visible"][:, seat] != 0) & np.isin(
                    f["entity.kind"][:, seat], (1, 2)
                )
                enemy = units & (f["entity.side"][:, seat] == 1) & (f["entity.kind"][:, seat] == 1)
                no_bar = enemy & (f["entity.hp"][:, seat] < 0)
                counts["units"] += int(units.sum())
                counts["known"] += int((known & units).sum())
                counts["enemy"] += int(enemy.sum())
                counts["enemy_known"] += int((known & enemy).sum())
                counts["no_bar"] += int(no_bar.sum())
                counts["no_bar_known"] += int((known & no_bar).sum())
                counts["checked"] += int(known.sum())
        assert counts["no_bar_known"] > 300 and counts["no_bar"] > counts["no_bar_known"], counts
        assert counts["checked"] > 50_000, counts


def test_the_own_plays_are_the_plays_the_battle_ran(install, tables):
    config = LadderConfig(arena=arena(tables))
    with VectorBattle(3, 2, install=install, tables=tables, config=config, fields=True) as v:
        v.reset([61, 62, 63], BARS, BLUE)
        registry = Registry.from_metadata(v.metadata["fields"])
        plays = OwnPlays()
        plays.bind(registry)
        assert plays.hidden is False
        view = plays.view(v.fields)
        f = registry.views(v.fields)
        rng = np.random.default_rng(6)
        shown = 0
        for _ in range(400):
            v.step(draw_actions(v.masks, rng, 0.3))
            for seat in (0, 1):
                out = plays.observe(view, seat)
                for c in ("count", *COLUMNS):
                    name = "own.plays.count" if c == "count" else f"own.plays.{c}"
                    np.testing.assert_array_equal(out[c], f[name][:, seat])
                # The other seat's own record of these plays, the frames turned.
                other = 1 - seat
                np.testing.assert_array_equal(out["count"], f["enemy.plays.count"][:, other])
                for c in ("card", "cast"):
                    np.testing.assert_array_equal(out[c], f[f"enemy.plays.{c}.true"][:, other])
                for c in ("ticks", "kind"):
                    np.testing.assert_array_equal(out[c], f[f"enemy.plays.{c}"][:, other])
                played = out["kind"] != 0
                # Past the plays the own rows' form is 0, the true record's -1.
                form = f["enemy.plays.form.true"][:, other]
                np.testing.assert_array_equal(out["form"][played], form[played])
                assert (out["form"][~played] == 0).all()
                x, y = f["enemy.plays.x.true"][:, other], f["enemy.plays.y.true"][:, other]
                x, y = (WIDTH - x, y) if seat == 0 else (x, LENGTH - y)
                np.testing.assert_array_equal(out["x"][played], x[played])
                np.testing.assert_array_equal(out["y"][played], y[played])
                shown += int(played.sum())
        assert shown > 1000
        # The battle's commands: every play's point, in the game's frame, is one its side gave.
        for i in range(v.count):
            commands = json.loads(v.replay_json(i))["cmd"]
            for seat in (0, 1):
                given = {(c["c"]["px"], c["c"]["py"]) for c in commands if c["c"]["idHi"] == seat}
                out = plays.observe(view, seat)
                rows = np.flatnonzero(out["kind"][i] != 0)
                assert rows.size, (i, seat)
                for r in rows:
                    x, y = int(out["x"][i, r]), int(out["y"][i, r])
                    point = (WIDTH - x, y) if seat == 0 else (x, LENGTH - y)
                    assert point in given, (i, seat, point)
