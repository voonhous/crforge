# Knight walks of the early grid port

The six files under `golden/` are **not** captures of the shipped game. Each one is the
tick-by-tick output of a model of the game's movement and targeting rules, made for the early grid
port, run for one deployment of a Knight on the standard 36 by 64 cell arena at 20 ticks per
second. A disagreement between the simulator and a file here means the simulator disagrees with the
model, not necessarily with the game.

Only `GridGoldenTrajectoryTest` reads them: it drives each walk through the original engine,
`GameEngine` in grid mode. They stay for that test until the original engine is removed. The battle
core is held to the recorded reference battles of the game data repository instead; two of them,
`golden-gaps-v1/walk_knight_left_inner` and `golden-gaps-v1/walk_knight_right_rear`, play a Knight
at the inner and the rear deploy points below.

| File | Deploy point (side 0) | Locks onto | On tick |
| --- | --- | --- | --- |
| `knight_left.json` | (3500, 10000) | PrincessTower_1_1 | 235 |
| `knight_right.json` | (14500, 10000) | PrincessTower_1_2 | 235 |
| `knight_centre.json` | (9000, 12000) | PrincessTower_1_2 | 245 |
| `knight_right_rear.json` | (16500, 5000) | PrincessTower_1_2 | 326 |
| `knight_behind_king.json` | (9000, 4600) | PrincessTower_1_2 | 369 |
| `knight_left_inner.json` | (8000, 10000) | PrincessTower_1_1 | 259 |

## Conditions the walks were produced under

- One unit and the six crown towers are the only things on the arena, so no walk here exercises
  unit-to-unit pushing or steering. The towers do take part in both: a tower is a static neighbour
  of the push pass, with a mass of 0, and an obstacle of the avoidance handler. Two of the walks are
  the ones that reaches: `knight_right_rear` is steered around its own right princess tower from
  tick 40, and `knight_behind_king` is pushed 1 unit a tick by its own king while it deploys. The
  game loads a crown tower at a mass of 20, not 0, so these two in particular are the model's
  reading, not the game's.
- The unit is a Knight: speed 60 units per tick, attack range 1200, sight range 5500, collision
  radius 500, hit speed 1200 ms, wind-up 700 ms, deploy time 1000 ms. It attacks the ground only.
- The deployment lasts twenty ticks. Ticks 0 to 19 are the deploying state, at the deploy position
  unless a tower pushes the unit, and the unit leaves the countdown in the moving state, because it
  has a movement component.
- The towers of the bottom side stand at (9000, 3000), (3500, 6500) and (14500, 6500) in game units;
  the top side's are the same mirrored along the arena's length. King towers have a collision radius
  of 1400 and princess towers 1000. The towers do not fight.
- Levels and damage do not affect the path and are not carried here; no tower is destroyed.
- For its first ten walking ticks a unit only considers the towers of its own lane:
  `knight_left_inner` is the walk that shows it, taking its lane's princess tower from (8000, 10000)
  although the king is the closer tower in x.
- The follower advances to the next route node as soon as the remaining distance projected on its
  route direction drops to 1000 units, so the unit effectively aims two nodes ahead and clips the
  corner of the cell it enters a bridge through. These files encode the behaviour the model
  implements.

## `golden/<case>.json` - the walk

One entry per 50 ms tick of the whole deployment, from placement to the tick the unit locks onto a
tower.

```json
{
  "card": "Knight",
  "deploy": [
    3500,
    10000
  ],
  "side": 0,
  "lane": 1,
  "records": [
    {
      "tick": 20,
      "x": 3518,
      "y": 10056,
      "state": 1,
      "ref": "PrincessTower_1_1",
      "route": 27
    }
  ]
}
```

- `card`, `deploy`, `side`, `lane` - the deployment. `deploy` is in game units, 1000 per arena tile
  and 500 per routing cell.
- `x` and `y` - the unit's position.
- `state` - the entity state: 4 while it is being placed, 1 while it walks, 2 once it stands and
  attacks.
- `ref` - the tower the unit is heading for, or null while it has none.
- `route` - how many cells are left on its route. It is 0 on the lock tick: entering the attacking
  state empties the route, so a unit that stands holds none.

An entry is written after the movement pass of its tick. For a unit that is walking or attacking
that is also after the entity state visit, so the entry holds the state the unit ends the tick with.
For a unit that is still being placed the entry is written **before** the state visit, so the last
deploying tick is written as deploying although the visit that ends the deployment runs immediately
afterwards. A test that observes the unit only at the end of a whole tick has to allow for that one
tick.
