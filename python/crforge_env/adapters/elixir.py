"""The ``elixir_lead`` adapter: the seat's elixir less the enemy's, as the seat counts the enemy's.

It reads two seat fields: ``own.elixir`` and ``enemy.elixir``, the enemy's elixir as the seat's
player counts it from what the screen shows (never the enemy's own count, ``enemy.elixir.true``).
"""

from __future__ import annotations

import numpy as np

from ..fields import FieldView
from .base import Adapter, check_seat


class ElixirLead(Adapter):
    """The seat's elixir lead over the enemy, in ten-thousandths of an elixir (the fields' unit).

    Output ``lead``: int32 per environment, positive when the seat holds more elixir than it counts
    for the enemy. Stateless.
    """

    name = "elixir_lead"
    registry_version = "1.0"
    reads = ("own.elixir", "enemy.elixir")
    outputs = {"lead": ((), np.dtype(np.int32))}  # noqa: RUF012

    def observe(self, fields: FieldView, seat: int) -> dict[str, np.ndarray]:
        """``{"lead": (count,) int32}`` for seat ``seat``."""
        check_seat(seat)
        lead = fields["own.elixir"][:, seat] - fields["enemy.elixir"][:, seat]
        return {"lead": lead.astype(np.int32)}
