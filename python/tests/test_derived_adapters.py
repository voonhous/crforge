"""The shared derived adapters (``ElixirLead``, ``EnemyCycle``, ``UnitAge``) on synthetic blocks.

No JVM: the registry is the committed snapshot and each block is written through
``Registry.views`` on a writable array, field by field.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pytest

from crforge_env.adapters import ElixirLead, EnemyCycle, UnitAge, require_fair
from crforge_env.fields import Registry, UndeclaredFieldError

SNAPSHOT = Path(__file__).parent / "data" / "fields_v1.json"
UNKNOWN = -1


@pytest.fixture(scope="module")
def registry() -> Registry:
    return Registry.from_file(SNAPSHOT)


def block_of(registry: Registry, count: int) -> tuple[np.ndarray, dict[str, np.ndarray]]:
    """A writable block of ``count`` environments and its writable views."""
    block = np.zeros((count, registry.ints), dtype=np.int32)
    return block, registry.views(block)


def bound(adapter, registry):
    adapter.bind(registry)
    return adapter


def check_outputs(adapter, out: dict[str, np.ndarray], count: int) -> None:
    """The outputs are those declared: every name, its shape after the environments and dtype."""
    assert set(out) == set(adapter.outputs)
    for name, (shape, dtype) in adapter.outputs.items():
        assert out[name].shape == (count, *shape), name
        assert out[name].dtype == dtype, name


@pytest.mark.parametrize("make", [ElixirLead, EnemyCycle, UnitAge])
def test_every_shared_adapter_reads_only_what_its_seat_is_shown(registry, make):
    adapter = bound(make(), registry)
    assert adapter.hidden is False
    assert adapter.hidden_fields == ()
    require_fair(adapter)
    assert all(registry.field(name).tag in ("seat", "public") for name in adapter.reads)


@pytest.mark.parametrize("make", [ElixirLead, EnemyCycle, UnitAge])
def test_every_shared_adapter_refuses_a_seat_out_of_range(registry, make):
    adapter = bound(make(), registry)
    block, _ = block_of(registry, 1)
    with pytest.raises(ValueError, match="seat is 0 or 1"):
        adapter.observe(adapter.view(block), 2)


# The elixir lead.


def test_the_elixir_lead_is_the_seats_elixir_less_the_enemys_as_it_counts_it(registry):
    block, views = block_of(registry, 3)
    views["own.elixir"][:] = [[50_000, 30_000], [10_000, 100_000], [0, 0]]
    views["enemy.elixir"][:] = [[20_000, 40_000], [90_000, 0], [0, 0]]
    lead = bound(ElixirLead(), registry)
    view = lead.view(block)
    out0, out1 = lead.observe(view, 0), lead.observe(view, 1)
    check_outputs(lead, out0, 3)
    np.testing.assert_array_equal(out0["lead"], [30_000, -80_000, 0])
    np.testing.assert_array_equal(out1["lead"], [-10_000, 100_000, 0])
    # The enemy's own elixir (the other seat's) is 30_000 in environment 0 and never read.
    assert "enemy.elixir.true" not in lead.reads


# The enemy's card cycle.


def write_plays(views, env, seat, cards, shown=None):
    """The enemy's plays ``cards``, the oldest first; a card of ``shown`` False is a tunnel whose
    unit has not come up (no card, no point). The rows are newest first, as the export has them."""
    shown = [True] * len(cards) if shown is None else shown
    rows = list(zip(cards, shown, strict=True))[::-1][:8]
    views["enemy.plays.count"][env, seat] = len(cards)
    for k in range(8):
        if k < len(rows):
            card, show = rows[k]
            views["enemy.plays.kind"][env, seat, k] = 1 if show else 5
            views["enemy.plays.card"][env, seat, k] = card if show else UNKNOWN
            views["enemy.plays.cast"][env, seat, k] = card if show else UNKNOWN
        else:
            views["enemy.plays.kind"][env, seat, k] = 0
            views["enemy.plays.card"][env, seat, k] = UNKNOWN
            views["enemy.plays.cast"][env, seat, k] = UNKNOWN


def write_seen(views, env, seat, cards):
    views["enemy.seen.card"][env, seat] = [*cards, *[UNKNOWN] * (8 - len(cards))]


def cycle_of(registry, plays, seen, shown=None, seat=0):
    """The cycle seat ``seat`` of one environment tracks, as plain values."""
    block, views = block_of(registry, 1)
    write_plays(views, 0, seat, plays, shown)
    write_seen(views, 0, seat, seen)
    cycle = bound(EnemyCycle(), registry)
    out = cycle.observe(cycle.view(block), seat)
    check_outputs(cycle, out, 1)
    return {name: out[name][0].tolist() for name in out}


def test_before_any_play_nothing_of_the_cycle_is_known(registry):
    out = cycle_of(registry, [], [])
    assert out == {
        "queue": [-1, -1, -1, -1],
        "hand": [-1, -1, -1, -1],
        "hand_known": 0,
        "all_seen": False,
        "settled": True,
    }


def test_the_queue_is_the_last_four_plays_oldest_first(registry):
    # Two plays: the queue's first two places hold cards never played, then the two plays.
    out = cycle_of(registry, [10, 11], [10, 11])
    assert out["queue"] == [-1, -1, 10, 11]
    assert out["hand"] == [-1, -1, -1, -1] and out["hand_known"] == 0
    assert out["settled"]


def test_a_card_seen_and_not_in_the_queue_is_in_the_hand(registry):
    out = cycle_of(registry, [10, 11, 12, 13, 14, 15], [10, 11, 12, 13, 14, 15])
    assert out["queue"] == [12, 13, 14, 15]
    assert out["hand"] == [10, 11, -1, -1] and out["hand_known"] == 2
    assert not out["all_seen"]


def test_all_eight_cards_seen_give_the_whole_hand(registry):
    # Ten plays: 10 and 11 came back into the hand and were played again.
    plays = [10, 11, 12, 13, 14, 15, 16, 17, 10, 11]
    out = cycle_of(registry, plays, [10, 11, 12, 13, 14, 15, 16, 17])
    assert out["queue"] == [16, 17, 10, 11]
    assert out["hand"] == [12, 13, 14, 15] and out["hand_known"] == 4
    assert out["all_seen"] and out["settled"]


def test_a_recent_play_not_shown_leaves_the_hand_unknown(registry):
    # The newest play is a tunnel not come up: its queue place is unknown, and it may have been
    # any card, so no seen card is known to be in the hand.
    plays = [10, 11, 12, 13, 14, 15]
    out = cycle_of(registry, plays, [10, 11, 12, 13, 14], shown=[True] * 5 + [False])
    assert out["queue"] == [12, 13, 14, -1]
    assert out["hand"] == [-1, -1, -1, -1] and out["hand_known"] == 0
    assert not out["settled"]


def test_a_play_not_shown_before_the_last_four_does_not_matter(registry):
    plays = [10, 11, 12, 13, 14, 15]
    shown = [True, False, True, True, True, True]
    out = cycle_of(registry, plays, [10, 12, 13, 14, 15], shown=shown)
    assert out["queue"] == [12, 13, 14, 15]
    assert out["hand"] == [10, -1, -1, -1] and out["hand_known"] == 1
    assert out["settled"]


def test_a_mirror_cycles_the_mirror_not_the_card_it_repeated(registry):
    # Card 17 is a Mirror that repeated 15: the play row's card is the Mirror, its cast the card.
    block, views = block_of(registry, 1)
    write_plays(views, 0, 0, [10, 11, 12, 13, 14, 15, 17])
    views["enemy.plays.cast"][0, 0, 0] = 15
    write_seen(views, 0, 0, [10, 11, 12, 13, 14, 15, 17])
    cycle = bound(EnemyCycle(), registry)
    out = cycle.observe(cycle.view(block), 0)
    assert out["queue"][0].tolist() == [13, 14, 15, 17]
    assert out["hand"][0].tolist() == [10, 11, 12, -1]


def test_the_cycle_is_per_seat_and_per_environment(registry):
    block, views = block_of(registry, 2)
    write_plays(views, 0, 0, [10, 11, 12, 13, 14])
    write_seen(views, 0, 0, [10, 11, 12, 13, 14])
    write_plays(views, 0, 1, [20])
    write_seen(views, 0, 1, [20])
    write_plays(views, 1, 0, [30, 31, 32, 33, 34, 35, 36, 37])
    write_seen(views, 1, 0, [30, 31, 32, 33, 34, 35, 36, 37])
    write_plays(views, 1, 1, [])
    write_seen(views, 1, 1, [])
    cycle = bound(EnemyCycle(), registry)
    view = cycle.view(block)
    seat0, seat1 = cycle.observe(view, 0), cycle.observe(view, 1)
    np.testing.assert_array_equal(seat0["queue"], [[11, 12, 13, 14], [34, 35, 36, 37]])
    np.testing.assert_array_equal(seat0["hand"], [[10, -1, -1, -1], [30, 31, 32, 33]])
    np.testing.assert_array_equal(seat0["hand_known"], [1, 4])
    np.testing.assert_array_equal(seat0["all_seen"], [False, True])
    np.testing.assert_array_equal(seat1["queue"], [[-1, -1, -1, 20], [-1, -1, -1, -1]])
    np.testing.assert_array_equal(seat1["hand_known"], [0, 0])


def test_the_cycle_never_reads_the_enemys_own_hand(registry):
    cycle = bound(EnemyCycle(), registry)
    block, views = block_of(registry, 1)
    views["enemy.queue.true"][0, 0] = [1, 2, 3, 4]
    with pytest.raises(UndeclaredFieldError):
        cycle.view(block)["enemy.queue.true"]
    assert cycle.observe(cycle.view(block), 0)["queue"][0].tolist() == [-1, -1, -1, -1]


# The age of each entity.


def write_entities(views, env, seat, tick, rows):
    """Entity table rows ``{row: seat id}`` shown to ``seat`` at ``tick``; every other row not."""
    views["game.tick"][env] = tick
    views["entity.visible"][env, seat] = 0
    views["entity.id"][env, seat] = 0
    for row, entity in rows.items():
        views["entity.visible"][env, seat, row] = 1
        views["entity.id"][env, seat, row] = entity


def test_an_entitys_age_counts_the_ticks_since_its_seat_was_first_shown_it(registry):
    block, views = block_of(registry, 1)
    age = bound(UnitAge(), registry)
    view = age.view(block)
    write_entities(views, 0, 0, 10, {0: 0, 1: 1})
    out = age.observe(view, 0)
    check_outputs(age, out, 1)
    assert out["age"][0, :3].tolist() == [0, 0, -1]
    assert (out["age"][0, 2:] == -1).all()
    # Five ticks on: a new entity at age 0, the two others 5 ticks older. Rows move, ids do not.
    write_entities(views, 0, 0, 15, {0: 1, 1: 2, 4: 0})
    out = age.observe(view, 0)
    assert out["age"][0, [0, 1, 4]].tolist() == [5, 0, 5]
    # An entity not shown for a while keeps its age: the seat remembers it.
    write_entities(views, 0, 0, 20, {3: 2})
    assert age.observe(view, 0)["age"][0, [0, 3]].tolist() == [-1, 5]
    write_entities(views, 0, 0, 30, {0: 0, 3: 2})
    out = age.observe(view, 0)
    assert out["age"][0, [0, 3]].tolist() == [20, 15]


def test_the_ages_are_per_seat_and_reset_clears_ended_environments(registry):
    block, views = block_of(registry, 2)
    age = bound(UnitAge(), registry)
    view = age.view(block)
    for env in (0, 1):
        write_entities(views, env, 0, 0, {0: 0})
        write_entities(views, env, 1, 0, {0: 0})
    age.observe(view, 0)
    for env in (0, 1):
        write_entities(views, env, 0, 50, {0: 0})
        write_entities(views, env, 1, 50, {0: 0})
    # Seat 1 is shown entity 0 first at tick 50: its age starts there, seat 0's at 0.
    np.testing.assert_array_equal(age.observe(view, 1)["age"][:, 0], [0, 0])
    np.testing.assert_array_equal(age.observe(view, 0)["age"][:, 0], [50, 50])
    # Environment 0 ended and its battle starts again: its ids are numbered afresh.
    age.reset(np.array([True, False]), 0)
    for env in (0, 1):
        write_entities(views, env, 0, 60, {0: 0})
    np.testing.assert_array_equal(age.observe(view, 0)["age"][:, 0], [0, 60])
    np.testing.assert_array_equal(age.observe(view, 1)["age"][:, 0], [10, 10])


def test_an_age_follows_ids_past_any_number_kept_so_far(registry):
    block, views = block_of(registry, 1)
    age = bound(UnitAge(), registry)
    view = age.view(block)
    write_entities(views, 0, 0, 0, {0: 5000})
    age.observe(view, 0)
    write_entities(views, 0, 0, 7, {0: 5000, 1: 100_000})
    assert age.observe(view, 0)["age"][0, :2].tolist() == [7, 0]


def test_a_battle_whose_clock_goes_back_starts_its_ages_again(registry):
    # A battle restarted (an autoreset, reset_env) with no reset call: the clock going back
    # tells the adapter the ids are numbered afresh.
    block, views = block_of(registry, 1)
    age = bound(UnitAge(), registry)
    view = age.view(block)
    write_entities(views, 0, 0, 300, {0: 0})
    age.observe(view, 0)
    write_entities(views, 0, 0, 0, {0: 0})
    assert age.observe(view, 0)["age"][0, 0] == 0


def test_bind_starts_the_ages_afresh(registry):
    block, views = block_of(registry, 1)
    age = bound(UnitAge(), registry)
    write_entities(views, 0, 0, 10, {0: 0})
    age.observe(age.view(block), 0)
    age.bind(registry)
    write_entities(views, 0, 0, 40, {0: 0})
    assert age.observe(age.view(block), 0)["age"][0, 0] == 0


def test_the_ages_refuse_another_number_of_environments(registry):
    age = bound(UnitAge(), registry)
    age.observe(age.view(block_of(registry, 2)[0]), 0)
    with pytest.raises(ValueError, match="2 environments"):
        age.observe(age.view(block_of(registry, 3)[0]), 0)
