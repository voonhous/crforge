# Reference trajectories used by the visualizer's golden scenarios

These files are **not** captures of the shipped game. No such capture exists yet. Each one is the
tick-by-tick output of a model of the game's movement and targeting rules, run for one deployment of
a Knight on the standard 36 by 64 cell arena at 20 ticks per second. They pin the behaviour the
simulator is expected to reproduce.

Checking these trajectories against captures of real matches is still an open task. Until that is
done, a disagreement between the simulator and a file here means the simulator disagrees with the
model, not necessarily with the game.

They are a copy of the files the core grid pathfinding tests replay, trimmed to the fields the
visualizer reads. The core copies stay where they are; this copy exists only because the desktop
module cannot see the core module's test resources.

## Format

```json
{"card": "Knight", "deploy": [3500, 10000], "side": 0, "lane": 1,
 "records": [{"tick": 20, "x": 3518, "y": 10056, "state": 1, "ref": "PrincessTower_1_1", "route": 27}]}
```

- `card`, `deploy`, `side`, `lane` - the deployment. `deploy` is in game units, 1000 per arena tile
  and 500 per routing cell. `side` is 0 for the blue player and 1 for the red one.
- `tick` - the tick the record was written on, counted from the tick the unit first exists in.
- `x` and `y` - the unit's position in game units.
- `state` - the entity state: 4 while it is being placed, 1 while it walks, 2 once it stands and
  attacks.
- `ref` - the tower the unit is heading for, or null while it has none.
- `route` - how many cells are left on its route.

A record is written after the movement pass of its tick. For a unit that is walking or attacking
that is also after the entity state visit, so the record holds the state the unit ends the tick
with. For a unit that is still being placed the record is written **before** the state visit, so the
last deploying tick is written as deploying although the visit that ends the deployment runs
immediately afterwards.

## Cases

| Case | Deploy | Target | Attack lock |
| --- | --- | --- | --- |
| `knight_left` | (3500, 10000) | PrincessTower_1_1 | tick 235 |
| `knight_right` | (14500, 10000) | PrincessTower_1_2 | tick 235 |
| `knight_centre` | (9000, 12000) | KingTower_1_0, then PrincessTower_1_2 from tick 82 | tick 242 |
