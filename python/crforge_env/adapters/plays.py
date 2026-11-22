"""The ``own_plays`` adapter: the seat's own last plays (registry 1.1).

``own.plays.*`` and ``own.plays.count`` are the seat's own last 8 plays with the columns and
conventions of the enemy's (``enemy.plays.*``): newest first, the point in the seat's frame, the
card, cast and form from the run, -1 for the card and cast and 0 for every other column past the
plays. A seat sees all of its own plays, so none is hidden.
"""

from __future__ import annotations

import numpy as np

from ..fields import FieldView, Registry
from .base import Adapter, check_seat

COLUMNS = ("card", "cast", "form", "ticks", "x", "y", "kind", "flags", "point_ticks")
"""The columns of a play row, each a field ``own.plays.<column>``."""


class OwnPlays(Adapter):
    """The seat's own last plays, as the fields give them, for one seat.

    Outputs, per environment: ``count`` (int32, the seat's plays placed in the episode) and per
    column of :data:`COLUMNS` an int32 array of the play rows (8), newest first. Stateless.
    """

    name = "own_plays"
    registry_version = "1.1"
    reads = ("own.plays.count", *(f"own.plays.{c}" for c in COLUMNS))

    def __init__(self) -> None:
        self.outputs = self._outputs(8)

    @staticmethod
    def _outputs(rows: int) -> dict[str, tuple[tuple[int, ...], np.dtype]]:
        out = {"count": ((), np.dtype(np.int32))}
        out.update({c: ((rows,), np.dtype(np.int32)) for c in COLUMNS})
        return out

    def bind(self, registry: Registry) -> None:
        """Binds, then takes the play rows from the registry."""
        super().bind(registry)
        self.outputs = self._outputs(registry.field("own.plays.card").shape[1])

    def observe(self, fields: FieldView, seat: int) -> dict[str, np.ndarray]:
        """``count`` ``(count,)`` and each column ``(count, rows)``, for seat ``seat``."""
        check_seat(seat)
        out = {"count": fields["own.plays.count"][:, seat].astype(np.int32)}
        for c in COLUMNS:
            out[c] = fields[f"own.plays.{c}"][:, seat].astype(np.int32)
        return out
