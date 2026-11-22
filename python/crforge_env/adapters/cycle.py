"""The ``enemy_cycle`` adapter: the enemy's card cycle as the seat's player tracks it.

A card played goes to the back of the queue, and the queue's first card fills its hand slot. So
the queue is always the last 4 cards played, the oldest of them coming back first, and the hand
is the deck's other 4 cards. A player who watches the plays knows both as far as the screen showed
the cards:

- a queue place holds the card of its play when the screen has shown that card; it is unknown
  while the screen has not (a tunnelling card before its unit comes up) and, before the enemy's
  4th play, for a place holding a card the enemy has not played yet;
- a card seen (``enemy.seen.card``) is known to be in the hand when it is not in the queue and
  every one of the last 4 plays is shown: every card seen was played, and a played card is either
  among the last 4 plays or back in the hand. While one of the last 4 plays is not shown, it may
  have been any of the cards seen, so none is known to be in the hand.

With all 8 cards seen and the last 4 plays shown, the whole hand is known.

A Mirror's play cycles the Mirror (the play's ``enemy.plays.card``), not the card it repeated (its
``cast``). A play enters the play rows on the tick it runs, so a card the enemy has placed and
that has not run yet still counts in its hand, as it does on the player's screen. The enemy's own
hand and queue fields refill a played card's slot a moment after the run; the adapter gives the
cycle that refill settles.

It reads the seat fields ``enemy.plays.kind``, ``enemy.plays.card`` and ``enemy.seen.card`` only:
nothing of the enemy's own hand, queue or deck.
"""

from __future__ import annotations

import numpy as np

from ..fields import FieldView, Registry
from .base import Adapter, check_seat

KIND = "enemy.plays.kind"
CARD = "enemy.plays.card"
SEEN = "enemy.seen.card"
UNKNOWN = -1
"""A card not known (the fields' own value for a card not shown)."""
_BOOL = np.dtype(np.bool_)
_INT = np.dtype(np.int32)


class EnemyCycle(Adapter):
    """The enemy's queue and the cards known to be in its hand, from the plays the seat is shown.

    Outputs, per environment:

    - ``queue``: ``(4,)`` int32, the queue's cards by vocabulary id in the order they come back
      (the first is the enemy's next card), -1 for a place not known;
    - ``hand``: ``(4,)`` int32, the cards known to be in the hand by vocabulary id, in the order
      of ``enemy.seen.card``, -1 beyond;
    - ``hand_known``: int32, how many cards of the hand are known (the ids of ``hand``);
    - ``all_seen``: bool, all 8 of the enemy's cards have been seen;
    - ``settled``: bool, every one of the enemy's last 4 plays is shown (or was never made), the
      condition for any card to be known in the hand.

    The sizes come from the registry at :meth:`bind`: the deck is ``enemy.seen.card``'s 8 places,
    the hand ``own.hand.card``'s 4, the queue the rest. Stateless.
    """

    name = "enemy_cycle"
    registry_version = "1.0"
    reads = (KIND, CARD, SEEN)

    def __init__(self) -> None:
        self.hand_slots, self.queue_slots = 4, 4
        self.outputs = self._outputs()

    def _outputs(self) -> dict[str, tuple[tuple[int, ...], np.dtype]]:
        return {
            "queue": ((self.queue_slots,), _INT),
            "hand": ((self.hand_slots,), _INT),
            "hand_known": ((), _INT),
            "all_seen": ((), _BOOL),
            "settled": ((), _BOOL),
        }

    def bind(self, registry: Registry) -> None:
        """Takes the deck's, hand's and queue's sizes from the registry, then binds."""
        super().bind(registry)
        deck = registry.field(SEEN).shape[1]
        hand = registry.field("own.hand.card").shape[1]
        rows = registry.field(KIND).shape[1]
        if not 0 < hand < deck or deck - hand > rows:
            raise ValueError(
                f"{self.name}: a deck of {deck} cards, a hand of {hand} and {rows} play rows "
                "leave no queue the play rows hold"
            )
        self.hand_slots, self.queue_slots = hand, deck - hand
        self.outputs = self._outputs()

    def observe(self, fields: FieldView, seat: int) -> dict[str, np.ndarray]:
        """The outputs for seat ``seat``, each ``(count, *shape)``."""
        check_seat(seat)
        q = self.queue_slots
        # The play rows are newest first; the queue is the last q plays, the oldest first.
        kind = fields[KIND][:, seat, :q]
        card = fields[CARD][:, seat, :q]
        played = kind != 0
        shown = played & (card >= 0)
        settled = (shown | ~played).all(axis=1)
        queue = np.where(shown, card, UNKNOWN)[:, ::-1].astype(np.int32)
        seen = fields[SEEN][:, seat]
        known = queue >= 0
        in_queue = ((seen[:, :, None] == queue[:, None, :]) & known[:, None, :]).any(axis=2)
        in_hand = (seen >= 0) & ~in_queue & settled[:, None]
        # The cards known in the hand, packed to the front in the order seen.
        rank = np.cumsum(in_hand, axis=1)
        keep = in_hand & (rank <= self.hand_slots)
        hand = np.full((seen.shape[0], self.hand_slots), UNKNOWN, dtype=np.int32)
        env, place = np.nonzero(keep)
        hand[env, rank[env, place] - 1] = seen[env, place]
        return {
            "queue": queue,
            "hand": hand,
            "hand_known": keep.sum(axis=1).astype(np.int32),
            "all_seen": (seen >= 0).all(axis=1),
            "settled": settled,
        }
