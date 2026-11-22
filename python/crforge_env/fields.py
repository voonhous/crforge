"""The field registry, read from the vector's metadata (``fields``) or from its snapshot file.

The registry names every field of the field export: one block of ``ints`` 32-bit integers per
environment, a field being ``shape`` integers at ``offset``, row-major. Each field is tagged:

- ``public``: the same for both seats and shown to both (the battle clock);
- ``seat``: what a seat is shown, with a leading axis of 2 for the seat, in that seat's frame;
- ``hidden``: a value a seat is not shown, or not in full. A seat field's true value has the seat
  field's name with ``.true`` after it (``entity.hp.true``), so a plain name is always what the seat
  sees and a hidden value always has a name of its own.

An alias (``alias_of``) holds no integers: it is the seat field it names read at the other seat,
``enemy.elixir.true`` at seat s being ``own.elixir`` at seat 1 - s. Its view is the target's with
the seat axis flipped.

:meth:`Registry.views` gives every field as a view of the block, never a copy, so views made once
follow a block the vector refreshes in place. :meth:`Registry.view` gives a :class:`FieldView` of
a declared set of fields only, read-only, which is what an adapter is handed.

The version is ``major.minor``: adding a field moves the minor version; renaming or removing one,
or changing its meaning, moves the major version. A reader states the version it needs
(:meth:`Registry.check`) and refuses another major version or an older minor one.
"""

from __future__ import annotations

import json
import math
import re
from collections.abc import Iterable, Iterator, Mapping
from dataclasses import dataclass, field
from itertools import pairwise
from pathlib import Path
from typing import Any

import numpy as np
from numpy.lib.stride_tricks import as_strided

TAGS = ("public", "seat", "hidden")
"""The visibility tags a field may carry."""

SEATS = 2
"""The length of a seat field's leading axis."""

_VERSION = re.compile(r"(\d+)\.(\d+)")
_INT32 = np.dtype(np.int32)


class RegistryVersionError(ValueError):
    """The registry's version is not one the reader accepts (another major, or an older minor)."""


class UndeclaredFieldError(KeyError):
    """A field read through a :class:`FieldView` that did not declare it."""

    def __init__(self, name: str) -> None:
        super().__init__(name)
        self.name = name

    def __str__(self) -> str:
        # KeyError's own text is the key's repr; this one says what went wrong.
        return (
            f"field {self.name!r} was not declared: a field view gives only the fields its "
            "reader declared (an adapter's reads)"
        )


@dataclass(frozen=True)
class Field:
    """One field of the registry, as its metadata gives it."""

    name: str
    offset: int
    shape: tuple[int, ...]
    tag: str
    """``public``, ``seat`` or ``hidden``."""
    units: str
    frame: str
    alias_of: str | None
    v4: dict | None
    """Where a seat or public field sits in the v4 observation: ``region`` and ``indices``."""
    size: int = field(init=False)
    """The integers the field takes in the block: the product of its shape, 0 for an alias."""

    def __post_init__(self) -> None:
        object.__setattr__(self, "size", 0 if self.alias_of is not None else math.prod(self.shape))

    @staticmethod
    def from_node(node: Mapping[str, Any]) -> Field:
        return Field(
            name=node["name"],
            offset=int(node["offset"]),
            shape=tuple(int(n) for n in node["shape"]),
            tag=node["tag"],
            units=node["units"],
            frame=node["frame"],
            alias_of=node.get("alias_of"),
            v4=node.get("v4"),
        )


def _parse_version(version: str, what: str) -> tuple[int, int]:
    match = _VERSION.fullmatch(version) if isinstance(version, str) else None
    if match is None:
        raise ValueError(f"{what} {version!r} is not a version of the form M.m (major.minor)")
    return int(match.group(1)), int(match.group(2))


class Registry:
    """The field registry of one version, validated on load."""

    version: str
    ints: int
    entity_capacity: int
    enums: dict[str, dict[int, str]]
    """Per ``enum:<name>`` or ``flags:<name>`` unit: code value (or bit index) to label."""
    fields: dict[str, Field]
    """Every field by name, in registry order."""

    def __init__(
        self,
        version: str,
        ints: int,
        entity_capacity: int,
        enums: Mapping[str, Mapping[int, str]],
        fields: Iterable[Field],
    ) -> None:
        _parse_version(version, "the field registry's version")
        self.version = version
        self.ints = int(ints)
        self.entity_capacity = int(entity_capacity)
        self.enums = {name: dict(labels) for name, labels in enums.items()}
        self.fields = {}
        for f in fields:
            if f.name in self.fields:
                raise ValueError(f"the field registry names {f.name!r} twice")
            self.fields[f.name] = f
        self._validate()

    @classmethod
    def from_metadata(cls, node: Mapping[str, Any]) -> Registry:
        """The registry of the vector's metadata node ``fields`` (``vector.metadata["fields"]``)."""
        return cls(
            version=node["version"],
            ints=node["ints"],
            entity_capacity=node["entity_capacity"],
            enums={
                name: {int(code): label for code, label in labels.items()}
                for name, labels in node["enums"].items()
            },
            fields=(Field.from_node(item) for item in node["fields"]),
        )

    @classmethod
    def from_file(cls, path: str | Path) -> Registry:
        """The registry of a JSON file holding the metadata node, as the committed snapshot."""
        return cls.from_metadata(json.loads(Path(path).read_text()))

    def _validate(self) -> None:
        if self.ints < 0:
            raise ValueError(f"the field registry's ints is {self.ints}, below 0")
        stored: list[Field] = []
        for f in self.fields.values():
            if f.tag not in TAGS:
                raise ValueError(
                    f"field {f.name!r} has tag {f.tag!r}, not one of {', '.join(TAGS)}"
                )
            if any(n < 1 for n in f.shape):
                raise ValueError(f"field {f.name!r} has shape {list(f.shape)}, an axis below 1")
            if f.tag == "seat" and (not f.shape or f.shape[0] != SEATS):
                raise ValueError(
                    f"seat field {f.name!r} has shape {list(f.shape)}: a seat field's leading "
                    f"axis is the seat, of {SEATS}"
                )
            kind, _, enum = f.units.partition(":")
            if kind in ("enum", "flags") and enum not in self.enums:
                raise ValueError(f"field {f.name!r} has units {f.units!r}, but no enum {enum!r}")
            if f.alias_of is None:
                if f.offset < 0 or f.offset + f.size > self.ints:
                    raise ValueError(
                        f"field {f.name!r} takes ints {f.offset} to {f.offset + f.size - 1}, "
                        f"outside the block of {self.ints}"
                    )
                stored.append(f)
            else:
                self._validate_alias(f)
        stored.sort(key=lambda f: f.offset)
        for before, after in pairwise(stored):
            if after.offset < before.offset + before.size:
                raise ValueError(
                    f"fields {before.name!r} (ints {before.offset} to "
                    f"{before.offset + before.size - 1}) and {after.name!r} (from int "
                    f"{after.offset}) overlap"
                )

    def _validate_alias(self, alias: Field) -> None:
        target = self.fields.get(alias.alias_of)
        if target is None:
            raise ValueError(f"alias {alias.name!r} names {alias.alias_of!r}, which is no field")
        if target.alias_of is not None:
            raise ValueError(
                f"alias {alias.name!r} names {target.name!r}, itself an alias of "
                f"{target.alias_of!r}: an alias names a stored seat field"
            )
        if target.tag != "seat":
            raise ValueError(
                f"alias {alias.name!r} names {target.name!r}, tagged {target.tag!r}: an alias "
                "names a seat field"
            )
        if alias.tag != "hidden":
            raise ValueError(
                f"alias {alias.name!r} is tagged {alias.tag!r}: an alias reads the other seat's "
                "values, so it is tagged hidden"
            )
        if alias.shape != target.shape:
            raise ValueError(
                f"alias {alias.name!r} has shape {list(alias.shape)}, its target "
                f"{target.name!r} shape {list(target.shape)}"
            )
        if alias.offset != target.offset:
            raise ValueError(
                f"alias {alias.name!r} has offset {alias.offset}, its target {target.name!r} "
                f"offset {target.offset}"
            )

    def field(self, name: str) -> Field:
        """The field of a name; KeyError naming it if the registry has none."""
        try:
            return self.fields[name]
        except KeyError:
            raise KeyError(f"the field registry {self.version} has no field {name!r}") from None

    def check(self, required: str) -> None:
        """Refuse this registry unless its major version is ``required``'s and its minor at least.

        ``required`` is ``"M.m"``. A malformed one raises ValueError.
        """
        major, minor = _parse_version(required, "the required version")
        have_major, have_minor = _parse_version(self.version, "the field registry's version")
        if have_major != major or have_minor < minor:
            raise RegistryVersionError(
                f"the field registry is version {self.version}; the reader needs {required} "
                f"(major {major}, minor {minor} or later)"
            )

    def _check_block(self, block: Any) -> np.ndarray:
        if not isinstance(block, np.ndarray):
            raise ValueError(f"a field block is a numpy array, not {type(block).__name__}")
        if block.dtype != _INT32:
            raise ValueError(
                f"a field block holds int32 in the machine's byte order, not {block.dtype.str}"
            )
        if block.ndim != 2 or block.shape[1] != self.ints:
            raise ValueError(f"a field block has shape (count, {self.ints}), not {block.shape}")
        if not block.flags.c_contiguous:
            raise ValueError("a field block must be C-contiguous (one row of ints per environment)")
        return block

    def _view_of(self, block: np.ndarray, f: Field) -> np.ndarray:
        if f.alias_of is not None:
            return self._view_of(block, self.fields[f.alias_of])[:, ::-1]
        # The field's ints are contiguous within a row, so the view is a strided window on the
        # block: rows a block row apart, the field's own axes row-major.
        inner = []
        stride = block.itemsize
        for n in reversed(f.shape):
            inner.append(stride)
            stride *= n
        strides = (block.strides[0], *reversed(inner))
        start = block[:, f.offset : f.offset + f.size]
        return as_strided(
            start,
            shape=(block.shape[0], *f.shape),
            strides=strides,
            writeable=block.flags.writeable,
        )

    def views(self, block: np.ndarray) -> dict[str, np.ndarray]:
        """Every field of ``block`` as a ``(count, *shape)`` view of it, in registry order.

        ``block`` is a C-contiguous int32 array in the machine's byte order, of shape
        ``(count, ints)``; anything else raises ValueError. Nothing is copied: the views follow the
        block in place, and are writable when the block is. An alias is its target's view with the
        seat axis (axis 1) flipped.
        """
        block = self._check_block(block)
        return {name: self._view_of(block, f) for name, f in self.fields.items()}

    def view(self, block: np.ndarray, names: Iterable[str]) -> FieldView:
        """A read-only :class:`FieldView` of ``block`` holding the fields ``names`` only.

        A name the registry lacks raises KeyError.
        """
        # The views hang off a read-only view of the block, so their flag cannot be turned back on.
        readonly = self._check_block(block).view()
        readonly.flags.writeable = False
        return FieldView({name: self._view_of(readonly, self.field(name)) for name in names})


class FieldView(Mapping[str, np.ndarray]):
    """The declared fields of a block, read-only, and nothing else.

    Each array is a view of the block that cannot be written. Reading a name the view was not
    made with raises :class:`UndeclaredFieldError`, also through :meth:`get`; ``in`` says False.
    """

    def __init__(self, arrays: Mapping[str, np.ndarray]) -> None:
        self._arrays: dict[str, np.ndarray] = {}
        for name, array in arrays.items():
            view = array.view()
            view.flags.writeable = False
            self._arrays[name] = view

    def __getitem__(self, name: str) -> np.ndarray:
        try:
            return self._arrays[name]
        except KeyError:
            raise UndeclaredFieldError(name) from None

    def get(self, name: str, default: Any = None) -> np.ndarray:
        """As ``view[name]``: an undeclared name raises rather than giving ``default``."""
        return self[name]

    def __contains__(self, name: object) -> bool:
        return name in self._arrays

    def __iter__(self) -> Iterator[str]:
        return iter(self._arrays)

    def __len__(self) -> int:
        return len(self._arrays)

    def __repr__(self) -> str:
        return f"FieldView({list(self._arrays)})"


__all__ = [
    "SEATS",
    "TAGS",
    "Field",
    "FieldView",
    "Registry",
    "RegistryVersionError",
    "UndeclaredFieldError",
]
