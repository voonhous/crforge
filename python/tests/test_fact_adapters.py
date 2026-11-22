"""The registry 1.1 adapters (``AttackCooldown``, ``EnemyHitPoints``, ``OwnPlays``) on synthetic
blocks.

No JVM: the registry is the committed snapshot with hand-written row facts for a small vocabulary,
and each block is written through ``Registry.views`` on a writable array.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pytest

from crforge_env.adapters import AttackCooldown, EnemyHitPoints, OwnPlays, require_fair
from crforge_env.adapters.plays import COLUMNS
from crforge_env.fields import Registry

SNAPSHOT = Path(__file__).parent / "data" / "fields_v1.json"

# Bar rules and attack traits by code, as the registry's enums give them.
NOT_UNIT, NEVER, ALWAYS, DAMAGED, ROLLING = 0, 1, 2, 3, 4
CHARGE, DASH = 1 << 1, 1 << 2
# The hand-written rows: name -> (bar rule, H, T, b, D, traits).
ROWS = {
    "spell": (NOT_UNIT, 0, 0, 0, 0, 0),
    "knight": (ALWAYS, 1200, 700, 50, 0, 0),
    "tower": (ALWAYS, 800, 0, 50, 0, 0),
    "prince": (DAMAGED, 1400, 900, 50, 0, CHARGE),
    "inferno": (ALWAYS, 400, 1200, 50, 0, 0),
    "delayed": (ALWAYS, 1200, 700, 50, 100, 0),
    "steps_differ": (ALWAYS, 1200, 700, -1, -1, 0),
    "delays_differ": (ALWAYS, 1200, 700, 50, -1, 0),
    "dasher": (ALWAYS, 1000, 500, 50, 0, DASH),
    "refused": (ALWAYS, -1, -1, -1, -1, -1),
    "rolling": (ROLLING, 1000, 500, 50, 0, 0),
    "ghost": (NEVER, 1000, 500, 50, 0, 0),
}
ID = {name: k for k, name in enumerate(ROWS)}
FACTS = ("bar_rule", "hit_speed_ms", "load_time_ms", "attack_step_ms", "attack_start_delay_ms")
FACTS += ("attack_traits",)
CHARACTER, TOWER, PROJECTILE = 1, 2, 3
MOVING, ATTACKING = 1, 2
ALIVE, UNDERGROUND, KING, AWAKE, NO_ATTACK, CHARGED = 1, 1 << 3, 1 << 6, 1 << 8, 1 << 10, 1 << 11


@pytest.fixture(scope="module")
def registry() -> Registry:
    node = json.loads(SNAPSHOT.read_text())
    for k, fact in enumerate(FACTS):
        node["rows"][fact]["values"] = [row[k] for row in ROWS.values()]
    return Registry.from_metadata(node)


def bound(adapter, registry):
    adapter.bind(registry)
    return adapter


def block_of(registry: Registry, count: int = 1):
    block = np.zeros((count, registry.ints), dtype=np.int32)
    return block, registry.views(block)


def unit(views, r, row, *, kind=CHARACTER, seat=0, env=0, **columns):
    """Table row ``r`` shown to ``seat``: an alive entity of vocabulary row ``row`` (a name of
    ``ROWS``, or -1 for a trail), its other columns ``entity.<name>`` as given."""
    views["entity.visible"][env, seat, r] = 1
    views["entity.kind"][env, seat, r] = kind
    views["entity.row"][env, seat, r] = ID[row] if isinstance(row, str) else row
    views["entity.flags"][env, seat, r] = columns.pop("flags", ALIVE)
    views["entity.hit_speed_percent"][env, seat, r] = columns.pop("hit_speed_percent", 100)
    for name, value in columns.items():
        views[f"entity.{name}"][env, seat, r] = value


@pytest.mark.parametrize("make", [AttackCooldown, EnemyHitPoints, OwnPlays])
def test_every_1_1_adapter_reads_only_what_its_seat_is_shown(registry, make):
    adapter = bound(make(), registry)
    assert adapter.registry_version == "1.1"
    assert adapter.hidden is False
    require_fair(adapter)


@pytest.mark.parametrize("make", [AttackCooldown, EnemyHitPoints])
def test_an_adapter_on_row_facts_needs_their_values(make):
    with pytest.raises(ValueError, match="row facts"):
        make().bind(Registry.from_file(SNAPSHOT))


def test_a_registry_1_0_is_refused_by_the_1_1_adapters():
    from crforge_env.fields import RegistryVersionError

    old = Registry.from_file(Path(__file__).parent / "data" / "fields_v1_0.json")
    for make in (AttackCooldown, EnemyHitPoints, OwnPlays):
        with pytest.raises(RegistryVersionError):
            make().bind(old)


# The attack cooldown.

COOLDOWN_CASES = [
    # (what, row, columns, ms): ms -1 for no count.
    ("an attack under way", "knight", dict(state=ATTACKING, attack_ms=750, load_ms=700), 450),
    ("a hit just fired", "knight", dict(state=ATTACKING, attack_ms=1200, load_ms=700), 1200),
    ("a load to run first", "knight", dict(state=MOVING), 500),
    ("a tower's first hit", "tower", dict(state=MOVING, kind=TOWER), 800),
    ("half hit speed", "knight", dict(state=ATTACKING, attack_ms=750, hit_speed_percent=50), 900),
    ("a stun", "knight", dict(state=ATTACKING, attack_ms=750, hit_speed_percent=0), -1),
    ("charged", "prince", dict(state=MOVING, flags=ALIVE | CHARGED), 50),
    ("charged, on its hit", "prince", dict(state=ATTACKING, attack_ms=1400, flags=ALIVE | CHARGED),
     1400),
    ("a load above H", "inferno", dict(state=MOVING, load_ms=1000), 950),
    ("a start delay", "delayed", dict(state=MOVING), 600),
    ("a start delay, attacking", "delayed", dict(state=ATTACKING), 500),
    ("steps that differ", "steps_differ", dict(state=ATTACKING, attack_ms=750), -1),
    ("delays that differ, from 0", "delays_differ", dict(state=MOVING), -1),
    ("delays that differ, attacking", "delays_differ", dict(state=ATTACKING), 500),
    ("a refused row", "refused", dict(state=ATTACKING, attack_ms=750), -1),
    ("a row with no attack", "spell", dict(state=ATTACKING), -1),
    ("a trail", -1, dict(state=MOVING, flags=ALIVE | UNDERGROUND), -1),
    ("a projectile", "knight", dict(kind=PROJECTILE), -1),
]  # fmt: skip


@pytest.mark.parametrize(("row", "columns", "ms"), [c[1:] for c in COOLDOWN_CASES],
                         ids=[c[0] for c in COOLDOWN_CASES])  # fmt: skip
def test_the_cooldown_follows_the_timer_rule(registry, row, columns, ms):
    block, views = block_of(registry)
    unit(views, 3, row, **columns)
    cooldown = bound(AttackCooldown(), registry)
    out = cooldown.observe(cooldown.view(block), 0)
    assert set(out) == set(cooldown.outputs)
    for name, (shape, dtype) in cooldown.outputs.items():
        assert out[name].shape == (1, *shape) and out[name].dtype == dtype
    assert out["ms"][0, 3] == ms
    assert out["known"][0, 3] == (ms >= 0)
    # Every other row is not shown.
    assert (out["ms"][0, np.arange(256) != 3] == -1).all()
    assert not out["known"][0, np.arange(256) != 3].any()


EXACT_CASES = [
    ("a plain attack", "knight", {}, True),
    ("a dash row", "dasher", {}, False),
    ("deploying", "knight", dict(deploy_ms=400), False),
    ("underground", "knight", dict(flags=ALIVE | UNDERGROUND), False),
    ("a king asleep", "tower", dict(kind=TOWER, flags=ALIVE | KING), False),
    ("a king awake", "tower", dict(kind=TOWER, flags=ALIVE | KING | AWAKE), True),
    ("tags forbid attacks", "knight", dict(flags=ALIVE | NO_ATTACK), False),
    ("fallen", "tower", dict(kind=TOWER, flags=0), False),
]


@pytest.mark.parametrize(("row", "columns", "exact"), [c[1:] for c in EXACT_CASES],
                         ids=[c[0] for c in EXACT_CASES])  # fmt: skip
def test_the_cooldown_marks_the_cases_its_rule_does_not_cover(registry, row, columns, exact):
    block, views = block_of(registry)
    unit(views, 1, "knight", id=7)  # the target, alive and shown
    unit(views, 0, row, state=ATTACKING, attack_ms=100, id=1, target=7, **columns)
    cooldown = bound(AttackCooldown(), registry)
    out = cooldown.observe(cooldown.view(block), 0)
    assert out["known"][0, 0]
    assert out["exact"][0, 0] == exact


TARGET_CASES = [
    # (what, state, the unit's target, the target row's flags, exact)
    ("attacking a living target", ATTACKING, 7, ALIVE, True),
    ("attacking with no target", ATTACKING, -1, ALIVE, False),
    ("attacking a target not shown", ATTACKING, 9, ALIVE, False),
    ("attacking a fallen target", ATTACKING, 7, 0, False),
    ("walking with no target", MOVING, -1, ALIVE, True),
]


@pytest.mark.parametrize(("state", "target", "flags", "exact"), [c[1:] for c in TARGET_CASES],
                         ids=[c[0] for c in TARGET_CASES])  # fmt: skip
def test_an_attack_on_no_living_target_shown_is_not_exact(registry, state, target, flags, exact):
    # Such a unit holds its timers, so its count would never run down.
    block, views = block_of(registry, 2)
    unit(views, 4, "knight", id=7, flags=flags)
    unit(views, 0, "knight", state=state, attack_ms=100, id=1, target=target)
    # Environment 1 shows a living entity 9 to the other seat only, and an id 9 not on this seat.
    unit(views, 0, "knight", env=1, seat=1, id=9)
    unit(views, 2, "knight", env=1, state=ATTACKING, attack_ms=100, id=3, target=9)
    cooldown = bound(AttackCooldown(), registry)
    out = cooldown.observe(cooldown.view(block), 0)
    assert out["known"][0, 0] and out["ms"][0, 0] == 1100
    assert out["exact"][0, 0] == exact
    assert out["known"][1, 2] and not out["exact"][1, 2]


def test_no_count_is_exact_once_the_match_is_over(registry):
    block, views = block_of(registry, 2)
    for env in (0, 1):
        unit(views, 1, "knight", env=env, id=7)
        unit(views, 0, "knight", env=env, state=ATTACKING, attack_ms=100, id=1, target=7)
    views["own.result"][1, 0] = 1  # won
    cooldown = bound(AttackCooldown(), registry)
    out = cooldown.observe(cooldown.view(block), 0)
    assert out["known"][:, 0].tolist() == [True, True]
    assert out["exact"][:, 0].tolist() == [True, False]


def test_the_cooldown_is_per_seat(registry):
    block, views = block_of(registry)
    unit(views, 0, "knight", seat=0, state=ATTACKING, attack_ms=750)
    unit(views, 0, "knight", seat=1, state=ATTACKING, attack_ms=1150)
    cooldown = bound(AttackCooldown(), registry)
    view = cooldown.view(block)
    assert cooldown.observe(view, 0)["ms"][0, 0] == 450
    assert cooldown.observe(view, 1)["ms"][0, 0] == 50


# The hit points a seat can know.

HP_CASES = [
    # (what, row, columns, hp): hp -1 for not known.
    ("shown", "knight", dict(hp=300, max_hp=1000), 300),
    ("a fallen tower", "tower", dict(kind=TOWER, hp=0, max_hp=3000, flags=0), 0),
    ("no bar, rule damaged", "prince", dict(hp=-1, max_hp=1500), 1500),
    ("no bar, damaged, underground", "prince", dict(hp=-1, max_hp=1500, flags=ALIVE | UNDERGROUND),
     -1),
    ("no bar, damaged while rolling", "rolling", dict(hp=-1, max_hp=900), -1),
    ("no bar, rule never", "ghost", dict(hp=-1, max_hp=500), -1),
    ("a trail", -1, dict(hp=-1, max_hp=-1, flags=ALIVE | UNDERGROUND), -1),
    ("a projectile", "knight", dict(kind=PROJECTILE, hp=0, max_hp=0), -1),
]  # fmt: skip


@pytest.mark.parametrize(("row", "columns", "hp"), [c[1:] for c in HP_CASES],
                         ids=[c[0] for c in HP_CASES])  # fmt: skip
def test_the_hit_points_are_known_where_shown_or_where_no_bar_means_unhurt(
    registry, row, columns, hp
):
    block, views = block_of(registry)
    unit(views, 5, row, **columns)
    hit_points = bound(EnemyHitPoints(), registry)
    out = hit_points.observe(hit_points.view(block), 0)
    assert set(out) == set(hit_points.outputs)
    for name, (shape, dtype) in hit_points.outputs.items():
        assert out[name].shape == (1, *shape) and out[name].dtype == dtype
    assert out["hp"][0, 5] == hp
    assert out["known"][0, 5] == (hp >= 0)
    top = columns["max_hp"]
    fill = hp / top if hp >= 0 and top > 0 else -1.0
    assert out["fill"][0, 5] == pytest.approx(fill)
    others = np.arange(256) != 5
    assert (out["hp"][0, others] == -1).all() and not out["known"][0, others].any()


def test_the_hit_points_are_per_seat(registry):
    block, views = block_of(registry)
    unit(views, 0, "prince", seat=0, hp=700, max_hp=1500)
    unit(views, 0, "prince", seat=1, hp=-1, max_hp=1500)
    hit_points = bound(EnemyHitPoints(), registry)
    view = hit_points.view(block)
    assert hit_points.observe(view, 0)["hp"][0, 0] == 700
    assert hit_points.observe(view, 1)["hp"][0, 0] == 1500
    assert "entity.hp.true" not in hit_points.reads


# The seat's own plays.


def test_the_own_plays_are_the_seats_play_rows(registry):
    block, views = block_of(registry, 2)
    rng = np.random.default_rng(0)
    for c in COLUMNS:
        views[f"own.plays.{c}"][:] = rng.integers(-1, 1000, views[f"own.plays.{c}"].shape)
    views["own.plays.count"][:] = [[3, 9], [0, 12]]
    plays = bound(OwnPlays(), registry)
    view = plays.view(block)
    for seat in (0, 1):
        out = plays.observe(view, seat)
        assert set(out) == set(plays.outputs) == {"count", *COLUMNS}
        np.testing.assert_array_equal(out["count"], views["own.plays.count"][:, seat])
        for c in COLUMNS:
            np.testing.assert_array_equal(out[c], views[f"own.plays.{c}"][:, seat])
            assert out[c].shape == (2, 8) and out[c].dtype == np.int32
