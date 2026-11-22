"""Adapters: a model's inputs built from declared fields of the field export.

- :class:`Adapter`: the base class, binding to a registry and observing a :class:`FieldView`
- :func:`provenance`: which fields each role of a run read, JSON-ready
- :func:`require_fair`: refuse adapters that read a field their seat is not shown
- :class:`V4Adapter`: the schema version 4 observation, bit for bit, from the fields
- :class:`ElixirLead`: the seat's elixir less the enemy's as the seat counts it
- :class:`EnemyCycle`: the enemy's queue and known hand, as the seat's player tracks them
- :class:`UnitAge`: per entity row, the ticks since the seat was first shown the entity
- :class:`AdapterSet`: several adapters as one, with the run-level guard ``forbid_hidden``
- :class:`OtherSeat`: an adapter observed at the other seat, recorded as hidden
"""

from .base import Adapter, HiddenFieldError, provenance, require_fair
from .cycle import EnemyCycle
from .elixir import ElixirLead
from .sets import AdapterSet, OtherSeat
from .units import UnitAge
from .v4 import V4Adapter

__all__ = [
    "Adapter",
    "AdapterSet",
    "ElixirLead",
    "EnemyCycle",
    "HiddenFieldError",
    "OtherSeat",
    "UnitAge",
    "V4Adapter",
    "provenance",
    "require_fair",
]
