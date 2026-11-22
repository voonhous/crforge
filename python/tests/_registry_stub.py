"""A minimal stand-in of the field registry and adapter API, for the v4 adapter's tests alone.

It holds exactly the signatures of ``crforge_env.fields`` (``Registry``, ``FieldView``) and
``crforge_env.adapters.base`` (``Adapter``) that the v4 adapter uses, so its tests run before the
real modules are in place. The tests import the real modules when they exist and fall back to this
one only when they do not; nothing in the package imports it.
"""

from __future__ import annotations

import json
from abc import ABC, abstractmethod
from collections.abc import Iterable, Iterator, Mapping
from dataclasses import dataclass
from math import prod
from pathlib import Path

import numpy as np


class RegistryVersionError(ValueError):
    """A registry of another major version, or of an older minor version, than a reader needs."""


class UndeclaredFieldError(KeyError):
    """A field read through a view that does not declare it."""


class HiddenFieldError(ValueError):
    """An adapter reads a hidden field where only fair ones are allowed."""


@dataclass(frozen=True)
class Field:
    name: str
    offset: int
    shape: tuple[int, ...]
    tag: str
    units: str
    frame: str
    alias_of: str | None
    v4: dict | None
    size: int


class Registry:
    def __init__(self, node: Mapping):
        self.version = str(node["version"])
        self.ints = int(node["ints"])
        self.entity_capacity = int(node["entity_capacity"])
        self.enums = {
            name: {int(k): v for k, v in labels.items()} for name, labels in node["enums"].items()
        }
        self.fields: dict[str, Field] = {}
        for f in node["fields"]:
            shape = tuple(int(n) for n in f["shape"])
            self.fields[f["name"]] = Field(
                name=f["name"],
                offset=int(f["offset"]),
                shape=shape,
                tag=f["tag"],
                units=f["units"],
                frame=f["frame"],
                alias_of=f["alias_of"],
                v4=f["v4"],
                size=0 if f["alias_of"] is not None else prod(shape),
            )

    @classmethod
    def from_metadata(cls, node: Mapping) -> Registry:
        return cls(node)

    @classmethod
    def from_file(cls, path) -> Registry:
        return cls(json.loads(Path(path).read_text()))

    def field(self, name: str) -> Field:
        return self.fields[name]

    def check(self, required: str) -> None:
        major, minor = (int(p) for p in required.split("."))
        have_major, have_minor = (int(p) for p in self.version.split("."))
        if have_major != major or have_minor < minor:
            raise RegistryVersionError(f"registry {self.version}, {required} needed")

    def _one(self, block: np.ndarray, f: Field) -> np.ndarray:
        count = block.shape[0]
        if f.alias_of is not None:
            return self._one(block, self.fields[f.alias_of])[:, ::-1]
        return block[:, f.offset : f.offset + f.size].reshape(count, *f.shape)

    def views(self, block: np.ndarray) -> dict[str, np.ndarray]:
        return {name: self._one(block, f) for name, f in self.fields.items()}

    def view(self, block: np.ndarray, names: Iterable[str]) -> FieldView:
        return FieldView({name: self._one(block, self.fields[name]) for name in names})


class FieldView(Mapping[str, np.ndarray]):
    def __init__(self, arrays: Mapping[str, np.ndarray]):
        self._arrays = dict(arrays)

    def __getitem__(self, name: str) -> np.ndarray:
        try:
            return self._arrays[name]
        except KeyError:
            raise UndeclaredFieldError(name) from None

    def __iter__(self) -> Iterator[str]:
        return iter(self._arrays)

    def __len__(self) -> int:
        return len(self._arrays)


class Adapter(ABC):
    name: str
    registry_version: str
    reads: tuple[str, ...] = ()
    outputs: dict[str, tuple[tuple[int, ...], np.dtype]] = {}  # noqa: RUF012

    def bind(self, registry: Registry) -> None:
        registry.check(self.registry_version)
        for name in self.reads:
            registry.field(name)
        self.registry = registry

    @property
    def hidden_fields(self) -> tuple[str, ...]:
        return tuple(n for n in self.reads if self.registry.field(n).tag == "hidden")

    @property
    def hidden(self) -> bool:
        return bool(self.hidden_fields)

    def reset(self, done: np.ndarray, seat: int) -> None:  # noqa: B027
        """Stateless by default."""

    @abstractmethod
    def observe(self, fields: FieldView, seat: int) -> dict[str, np.ndarray]: ...
