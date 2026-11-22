"""The shared derived adapters on real Ladder rollouts (``VectorBattle(fields=True)``).

Each adapter is held to what the battle itself says, read in the test only: the elixir lead to the
v4 header's arithmetic, the enemy cycle to the vector's own record of the enemy cards each seat saw
(``enemy_seen``, ``enemy_unseen_slots``) and to the enemy's real queue and hand (hidden fields the
adapter never reads), and the unit ages to the decision ticks.
"""

from __future__ import annotations

import numpy as np
import pytest

from crforge_env import LadderConfig, VectorBattle
from crforge_env.adapters import AdapterSet, ElixirLead, EnemyCycle, UnitAge
from crforge_env.bench import draw_actions
from crforge_env.fields import Registry
from tests.decks import BLUE

pytestmark = pytest.mark.ladder

# A tunnelling card (its play shows no card until its unit comes up) and a Mirror.
TUNNEL_MIRROR = ["Knight", "Archer", "Miner", "Giant", "MiniPekka", "Fireball", "Arrows", "Mirror"]


def rollout(v: VectorBattle, steps: int, seed: int, play_rate: float):
    """Steps ``v`` with random open plays, yielding after each step."""
    rng = np.random.default_rng(seed)
    for _ in range(steps):
        v.step(draw_actions(v.masks, rng, play_rate))
        yield


def test_the_elixir_lead_is_the_v4_headers_elixir_less_the_enemys(install, tables):
    with VectorBattle(4, 2, install=install, tables=tables, fields=True) as v:
        v.reset([11, 12, 13, 14], BLUE, BLUE)
        registry = Registry.from_metadata(v.metadata["fields"])
        lead = ElixirLead()
        lead.bind(registry)
        assert lead.hidden is False
        view = lead.view(v.fields)
        elixir, enemy = v.schema.header["elixir"][0], v.schema.header["enemy_elixir"][0]
        differs = 0
        for _ in rollout(v, 300, 5, 0.3):
            for seat in (0, 1):
                header = v.observations[:, seat]
                expected = header[:, elixir] - header[:, enemy]
                np.testing.assert_array_equal(lead.observe(view, seat)["lead"], expected)
                differs += int((expected != 0).sum())
        assert differs > 100


def test_the_enemy_cycle_is_the_enemys_real_queue_and_hand_as_far_as_known(install, tables):
    with VectorBattle(4, 2, install=install, tables=tables, fields=True) as v:
        v.reset([21, 22, 23, 24], TUNNEL_MIRROR, TUNNEL_MIRROR)
        registry = Registry.from_metadata(v.metadata["fields"])
        cycle = EnemyCycle()
        cycle.bind(registry)
        assert cycle.hidden is False
        view = cycle.view(v.fields)
        # The enemy's own fields, read by the test alone to hold the adapter to the truth.
        truth = registry.views(v.fields)
        names = v.vocabulary
        counts = dict(checked=0, queue=0, hand=0, whole=0, unsettled=0, seen=0)
        for _ in rollout(v, 900, 7, 0.2):
            for seat in (0, 1):
                out = cycle.observe(view, seat)
                for i in range(v.count):
                    seen = [names[c] for c in truth["enemy.seen.card"][i, seat] if c >= 0]
                    assert seen == v.enemy_seen(i, seat)
                    unseen = v.enemy_unseen_slots(i, seat)
                    assert len(unseen) == 8 - len(seen)
                    assert bool(out["all_seen"][i]) == (not unseen)
                    counts["seen"] += len(seen)
                    counts["unsettled"] += int(not out["settled"][i])
                    if (truth["enemy.pending.card.true"][i, seat] >= 0).any():
                        continue  # placed and not run: the enemy's slot is not yet cycled
                    if (truth["enemy.hand.card.true"][i, seat] < 0).any():
                        continue  # a slot refilling: the played card joins the queue as it refills
                    queue = out["queue"][i].tolist()
                    real_queue = truth["enemy.queue.true"][i, seat].tolist()
                    for place, card in enumerate(queue):
                        if card >= 0:
                            assert card == real_queue[place], (queue, real_queue)
                            counts["queue"] += 1
                    hand = {c for c in out["hand"][i].tolist() if c >= 0}
                    assert len(hand) == out["hand_known"][i]
                    real_hand = set(truth["enemy.hand.card.true"][i, seat].tolist())
                    assert hand <= real_hand, (hand, real_hand)
                    counts["hand"] += len(hand)
                    counts["checked"] += 1
                    if out["all_seen"][i] and out["settled"][i] and min(queue) >= 0:
                        assert hand == real_hand
                        counts["whole"] += 1
        assert counts["checked"] > 2000, counts
        assert counts["queue"] > 2000 and counts["hand"] > 1000 and counts["whole"] > 100, counts
        assert counts["unsettled"] > 0, counts  # a tunnel among the last plays was met


def test_the_unit_ages_grow_by_the_decision_ticks(install, tables):
    # A tick limit ends the episodes within the run, so the restarts are met too.
    config = LadderConfig(max_ticks=1200)
    with VectorBattle(4, 2, install=install, tables=tables, config=config, fields=True) as v:
        v.reset([31, 32, 33, 34], BLUE, TUNNEL_MIRROR)
        registry = Registry.from_metadata(v.metadata["fields"])
        adapters = AdapterSet([UnitAge()], forbid_hidden=True)
        adapters.bind(registry)
        view = adapters.view(v.fields)
        fields = registry.views(v.fields)
        decision = int(fields["game.decision_ticks"][0])
        first = [[{}, {}] for _ in range(v.count)]  # per battle and seat: id -> first tick shown
        before = [[{}, {}] for _ in range(v.count)]  # the last observation's ages by id
        restarting = np.zeros(v.count, dtype=bool)
        grown = ended = 0
        for _ in rollout(v, 700, 9, 0.3):
            tick = fields["game.tick"]
            for seat in (0, 1):
                age = adapters.observe(view, seat)["unit_age.age"]
                shown = fields["entity.visible"][:, seat] != 0
                assert (age[~shown] == -1).all()
                for i in range(v.count):
                    if restarting[i]:
                        first[i][seat], before[i][seat] = {}, {}
                    ids = fields["entity.id"][i, seat][shown[i]].tolist()
                    now = dict(zip(ids, age[i][shown[i]].tolist(), strict=True))
                    for entity, value in now.items():
                        since = first[i][seat].setdefault(entity, int(tick[i]))
                        assert value == tick[i] - since
                        if entity in before[i][seat] and not v.dones[i]:
                            assert value == before[i][seat][entity] + decision
                            grown += 1
                    before[i][seat] = now
            done = v.dones.astype(bool)
            ended += int(done.sum())
            for seat in (0, 1):
                adapters.reset(done, seat)
            restarting = done
        assert grown > 10_000 and ended > 0, (grown, ended)
