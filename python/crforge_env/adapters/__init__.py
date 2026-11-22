"""Adapters: a model's inputs built from declared fields of the field export.

- :class:`Adapter`: the base class, binding to a registry and observing a :class:`FieldView`
- :func:`provenance`: which fields each role of a run read, JSON-ready
- :func:`require_fair`: refuse adapters that read a field their seat is not shown
- :class:`V4Adapter`: the schema version 4 observation, bit for bit, from the fields
"""

from .base import Adapter, HiddenFieldError, provenance, require_fair
from .v4 import V4Adapter

__all__ = ["Adapter", "HiddenFieldError", "V4Adapter", "provenance", "require_fair"]
