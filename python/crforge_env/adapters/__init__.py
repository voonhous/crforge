"""Adapters: a model's inputs built from declared fields of the field export.

- :class:`Adapter`: the base class, binding to a registry and observing a :class:`FieldView`
- :func:`provenance`: which fields each role of a run read, JSON-ready
- :func:`require_fair`: refuse adapters that read a field their seat is not shown
- :class:`V4Adapter`: the schema version 4 observation, bit for bit, from the fields
- :class:`ElixirLead`: the seat's elixir less the enemy's as the seat counts it
- :class:`EnemyCycle`: the enemy's queue and known hand, as the seat's player tracks them
- :class:`UnitAge`: per entity row, the ticks since the seat was first shown the entity
- :class:`AttackCooldown`: per entity row, the ms until its next hit, from the row facts
- :class:`EnemyHitPoints`: per entity row, the hit points the seat can know, and the bar's fill
- :class:`OwnPlays`: the seat's own last plays
- :class:`AdapterSet`: several adapters as one, with the run-level guard ``forbid_hidden``
- :class:`OtherSeat`: an adapter observed at the other seat, recorded as hidden
"""

from .base import Adapter, HiddenFieldError, provenance, require_fair
from .cycle import EnemyCycle
from .elixir import ElixirLead
from .plays import OwnPlays
from .sets import AdapterSet, OtherSeat
from .units import AttackCooldown, EnemyHitPoints, UnitAge
from .v4 import V4Adapter

__all__ = [
    "Adapter",
    "AdapterSet",
    "AttackCooldown",
    "ElixirLead",
    "EnemyCycle",
    "EnemyHitPoints",
    "HiddenFieldError",
    "OtherSeat",
    "OwnPlays",
    "UnitAge",
    "V4Adapter",
    "provenance",
    "require_fair",
]
