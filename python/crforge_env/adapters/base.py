"""The adapter base: a model's inputs built from declared fields of the field export.

An adapter states the registry version it is written against (``registry_version``), the fields it
reads (``reads``) and the arrays it gives per environment (``outputs``). :meth:`Adapter.bind`
checks both against a registry; :meth:`Adapter.observe` is then handed a :class:`FieldView` of its
reads only, so a field it did not declare cannot be read by mistake.

Whether an adapter plays fair follows from its reads: one that reads a ``hidden`` field reads
something its seat is not shown (``hidden_fields``). :func:`require_fair` refuses such adapters
where only fair inputs are allowed (a model to be played against people, say), and
:func:`provenance` records which fields each role of a run read, to be kept with the run.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from collections.abc import Mapping, Sequence

import numpy as np

from ..fields import FieldView, Registry


class HiddenFieldError(ValueError):
    """An adapter that must play fair reads a hidden field."""


class Adapter(ABC):
    """Builds a model's input arrays from declared fields, vectorised over the environments.

    Subclasses set ``name`` and ``registry_version`` as class attributes, ``reads`` and ``outputs``
    as class or instance attributes (an adapter may compute ``reads`` from the registry in its own
    :meth:`bind` before calling this one), and implement :meth:`observe`.
    """

    name: str
    """The adapter's name, as provenance and errors give it."""
    registry_version: str
    """The registry version it is written against, ``"M.m"`` (:meth:`Registry.check`)."""
    reads: tuple[str, ...]
    """The fields it reads; :meth:`observe` is given these only."""
    outputs: dict[str, tuple[tuple[int, ...], np.dtype]]
    """Per output name, the shape and dtype of one environment's array."""
    registry: Registry | None = None
    """The registry it is bound to, None before :meth:`bind`."""

    def bind(self, registry: Registry) -> None:
        """Bind to ``registry``: its version satisfies ``registry_version`` and every read exists.

        Raises :class:`~crforge_env.fields.RegistryVersionError` for a version it does not accept
        and ValueError naming the adapter and the fields the registry lacks.
        """
        registry.check(self.registry_version)
        _check_reads(self, registry)
        self.registry = registry

    def _bound(self) -> Registry:
        if self.registry is None:
            raise RuntimeError(f"adapter {self.name!r} is not bound: call bind(registry) first")
        return self.registry

    def hidden_reads(self, registry: Registry) -> tuple[str, ...]:
        """The reads that are hidden from the seat observed, in read order: by default those
        ``registry`` tags ``hidden``. An adapter that reads fields at another seat than the one it
        observes (:class:`~crforge_env.adapters.OtherSeat`) says so here."""
        return tuple(name for name in self.reads if registry.field(name).tag == "hidden")

    @property
    def hidden_fields(self) -> tuple[str, ...]:
        """The reads hidden from the seat (:meth:`hidden_reads`), in read order; RuntimeError
        before :meth:`bind`."""
        return self.hidden_reads(self._bound())

    @property
    def hidden(self) -> bool:
        """Whether the adapter reads any hidden field."""
        return bool(self.hidden_fields)

    def view(self, block: np.ndarray) -> FieldView:
        """The read-only :class:`FieldView` of ``block`` holding this adapter's reads."""
        return self._bound().view(block, self.reads)

    def reset(self, done: np.ndarray, seat: int) -> None:  # noqa: B027 (a no-op by default)
        """Forget the state of the environments ``done`` marks, ``(count,)`` bool.

        A stateless adapter keeps this no-op.
        """

    @abstractmethod
    def observe(self, fields: FieldView, seat: int) -> dict[str, np.ndarray]:
        """The outputs for ``seat`` of every environment: per name an array ``(count, *shape)``."""


def check_seat(seat: int) -> None:
    """Refuse a seat other than 0 or 1 (ValueError)."""
    if seat not in (0, 1):
        raise ValueError(f"seat is 0 or 1, not {seat}")


def _check_reads(adapter: Adapter, registry: Registry) -> None:
    missing = [name for name in adapter.reads if name not in registry.fields]
    if missing:
        raise ValueError(
            f"adapter {adapter.name!r} reads fields the field registry {registry.version} "
            f"lacks: {', '.join(missing)}"
        )


def _check_bound_to(adapter: Adapter, registry: Registry) -> None:
    if adapter.registry is None:
        raise ValueError(f"adapter {adapter.name!r} is not bound: call bind(registry) first")
    if adapter.registry.version != registry.version:
        raise ValueError(
            f"adapter {adapter.name!r} is bound to the field registry {adapter.registry.version}, "
            f"not {registry.version}"
        )


def provenance(roles: Mapping[str, Sequence[Adapter]], registry: Registry) -> dict:
    """What each role of a run read, as JSON-ready data to keep with the run.

    ``roles`` maps a role (``"learner"``, ``"opponent"``) to its adapters. The record is
    deterministic: the roles and every role's reads and hidden fields are sorted; the adapters stay
    in the order given, the order their outputs are built in. Tags are the ``registry``'s. Every
    adapter must be bound to a registry of the same version (an adapter may work out its reads at
    :meth:`Adapter.bind`, so an unbound one could not say what it reads); one that is not, or a read
    the registry lacks, raises ValueError.

        {"registry": "1.0", "roles": {"learner": {"adapters": ["v4"], "reads": [...],
         "hidden": false, "hidden_fields": []}}}
    """
    record: dict[str, dict] = {}
    for role in sorted(roles):
        adapters = list(roles[role])
        reads: set[str] = set()
        hidden: set[str] = set()
        for adapter in adapters:
            _check_bound_to(adapter, registry)
            _check_reads(adapter, registry)
            reads.update(adapter.reads)
            hidden.update(adapter.hidden_reads(registry))
        record[role] = {
            "adapters": [adapter.name for adapter in adapters],
            "reads": sorted(reads),
            "hidden": bool(hidden),
            "hidden_fields": sorted(hidden),
        }
    return {"registry": registry.version, "roles": record}


def require_fair(*adapters: Adapter) -> None:
    """Refuse any of ``adapters`` that reads a hidden field, naming each and its hidden fields.

    The adapters must be bound. Raises :class:`HiddenFieldError`.
    """
    unfair = [f"{a.name}: {', '.join(a.hidden_fields)}" for a in adapters if a.hidden]
    if unfair:
        raise HiddenFieldError(
            "adapters reading fields their seat is not shown (hidden): " + "; ".join(unfair)
        )
