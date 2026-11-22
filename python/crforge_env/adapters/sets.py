"""Adapters together: :class:`AdapterSet`, the run-level guard, and :class:`OtherSeat`.

An :class:`AdapterSet` binds, resets and observes several adapters as one model's inputs, and
records what they read (:meth:`AdapterSet.provenance`). With ``forbid_hidden=True`` it is the
run-level guard: it refuses every adapter that reads a field the seat is not shown, so a run built
on the set cannot read one by mistake.

:class:`OtherSeat` observes an adapter at the other seat of each environment, as a critic that
reads the other seat's observation does. Everything it reads of a seat is what the other seat is
shown, so it is hidden from the seat observed, and the guard and the provenance record say so.
"""

from __future__ import annotations

from collections.abc import Sequence

import numpy as np

from ..fields import FieldView, Registry
from .base import Adapter, check_seat, provenance, require_fair


class AdapterSet:
    """Several adapters bound, reset and observed as one.

    The outputs are keyed ``"<adapter name>.<output>"``, in the order of the adapters and of each
    adapter's outputs; two adapters of one name, or two keys that clash, raise ValueError. Each
    adapter is handed a :class:`FieldView` of its own reads only, so an adapter cannot read what
    another declared.

    ``forbid_hidden=True`` refuses, with :class:`~crforge_env.adapters.HiddenFieldError`, every
    adapter that reads a hidden field: those already bound at construction, every one at
    :meth:`bind` (an adapter may work out its reads as it binds).
    """

    def __init__(self, adapters: Sequence[Adapter], forbid_hidden: bool = False) -> None:
        self.adapters: tuple[Adapter, ...] = tuple(adapters)
        self.forbid_hidden = bool(forbid_hidden)
        self.registry: Registry | None = None
        names = [a.name for a in self.adapters]
        twice = sorted({name for name in names if names.count(name) > 1})
        if twice:
            raise ValueError(f"an adapter set names adapters twice: {', '.join(twice)}")
        if self.forbid_hidden:
            require_fair(*(a for a in self.adapters if a.registry is not None))

    def bind(self, registry: Registry) -> None:
        """Binds every adapter to ``registry``, then holds the guard and the output keys."""
        for adapter in self.adapters:
            adapter.bind(registry)
        if self.forbid_hidden:
            require_fair(*self.adapters)
        keys: dict[str, str] = {}
        for adapter in self.adapters:
            for output in adapter.outputs:
                key = f"{adapter.name}.{output}"
                if key in keys:
                    raise ValueError(
                        f"adapters {keys[key]!r} and {adapter.name!r} both give the output {key!r}"
                    )
                keys[key] = adapter.name
        self.registry = registry

    def _bound(self) -> Registry:
        if self.registry is None:
            raise ValueError("the adapter set is not bound: call bind(registry) first")
        return self.registry

    @property
    def reads(self) -> tuple[str, ...]:
        """Every adapter's reads, each once, in the adapters' order."""
        return tuple(dict.fromkeys(name for a in self.adapters for name in a.reads))

    @property
    def outputs(self) -> dict[str, tuple[tuple[int, ...], np.dtype]]:
        """Per key ``"<adapter name>.<output>"``, the shape and dtype of one environment's array."""
        return {
            f"{a.name}.{output}": spec for a in self.adapters for output, spec in a.outputs.items()
        }

    @property
    def hidden_fields(self) -> tuple[str, ...]:
        """The reads hidden from the seat, each once, in the adapters' order."""
        self._bound()
        return tuple(dict.fromkeys(name for a in self.adapters for name in a.hidden_fields))

    @property
    def hidden(self) -> bool:
        """Whether any adapter reads a hidden field."""
        return bool(self.hidden_fields)

    def view(self, block: np.ndarray) -> FieldView:
        """The read-only :class:`FieldView` of ``block`` holding the set's reads."""
        return self._bound().view(block, self.reads)

    def reset(self, done: np.ndarray, seat: int) -> None:
        """Resets every adapter: forgets the state of the environments ``done`` marks."""
        for adapter in self.adapters:
            adapter.reset(done, seat)

    def observe(self, fields: FieldView, seat: int) -> dict[str, np.ndarray]:
        """Every adapter's outputs for ``seat``, keyed ``"<adapter name>.<output>"``."""
        check_seat(seat)
        self._bound()
        out: dict[str, np.ndarray] = {}
        for adapter in self.adapters:
            own = FieldView({name: fields[name] for name in adapter.reads})
            for output, array in adapter.observe(own, seat).items():
                out[f"{adapter.name}.{output}"] = array
        return out

    def provenance(self, role: str) -> dict:
        """The provenance record of one role reading this set (:func:`provenance`)."""
        return provenance({role: self.adapters}, self._bound())


class OtherSeat(Adapter):
    """An adapter observed at the other seat: ``observe(fields, s)`` is the inner adapter's at
    seat ``1 - s``, as a critic that reads the other seat's observation reads it.

    Its name is the inner adapter's with ``@other_seat`` after it; its reads, outputs and registry
    version are the inner adapter's. Its hidden fields are its reads tagged ``seat`` or ``hidden``:
    a seat field read at the other seat is what the other seat is shown, not this one. Public
    fields are the same for both seats and stay fair.
    """

    def __init__(self, adapter: Adapter) -> None:
        self.inner = adapter
        self.name = f"{adapter.name}@other_seat"
        self.registry_version = adapter.registry_version
        self.reads = tuple(adapter.reads)
        self.outputs = dict(adapter.outputs)

    def bind(self, registry: Registry) -> None:
        """Binds the inner adapter, takes its reads and outputs, then binds."""
        self.inner.bind(registry)
        self.reads = tuple(self.inner.reads)
        self.outputs = dict(self.inner.outputs)
        super().bind(registry)

    def hidden_reads(self, registry: Registry) -> tuple[str, ...]:
        return tuple(name for name in self.reads if registry.field(name).tag != "public")

    def reset(self, done: np.ndarray, seat: int) -> None:
        check_seat(seat)
        self.inner.reset(done, 1 - seat)

    def observe(self, fields: FieldView, seat: int) -> dict[str, np.ndarray]:
        check_seat(seat)
        return self.inner.observe(fields, 1 - seat)
