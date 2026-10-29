"""The decks and the arena of the tests: row names of the tables."""

import json
from pathlib import Path

BLUE = ["Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows", "Zap"]
RED = ["Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows", "Goblins"]


def arena(tables: Path) -> str:
    """The arena a test's fresh battle names for both players when it is written as a replay: the
    first row of the tables' arenas table that is not a training camp, read from the tables."""
    rows = json.loads((Path(tables) / "arenas.json").read_text(encoding="utf-8"))["rows"]
    return next(name for name, row in rows.items() if not row["columns"].get("TrainingCamp"))
