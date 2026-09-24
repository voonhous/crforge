# Reference trajectories of a single unit on the standard arena

These files are **not** captures of the shipped game. No such capture exists yet. Each one is the
tick-by-tick output of a model of the game's movement and targeting rules, run for one deployment of
a Knight on the standard 36 by 64 cell arena at 20 ticks per second. They pin the behaviour the
simulator is expected to reproduce, and they are the yardstick the grid pathfinding tests measure
against.

Checking these trajectories against captures of real matches is still an open task. Until that is
done, a disagreement between the simulator and a file here means the simulator disagrees with the
model, not necessarily with the game.

## Conditions the trajectories were produced under

- One unit and the six crown towers are the only things on the arena. With a single unit the push
  and avoidance rules have nothing else to act on, so no run here exercises unit-to-unit pushing or
  steering. Two of the runs do walk the unit right past one of its own towers, which is what pins
  how a building takes part in those two passes.
- The unit is a Knight: speed 60 units per tick, attack range 1200, sight range 5500, collision
  radius 500, hit speed 1200 ms, wind-up 700 ms, deploy time 1000 ms. It attacks the ground only.
- The deployment lasts twenty ticks. Ticks 0 to 19 are the deploying state at the deploy position,
  and the unit leaves the countdown in the moving state, because it has a movement component.
- The towers of the bottom side stand at (9000, 3000), (3500, 6500) and (14500, 6500) in game units;
  the top side's are the same mirrored along the arena's length. King towers have a collision radius
  of 1400 and princess towers 1000.
- Levels and damage do not affect the path and are not carried here; no tower is destroyed in any of
  the runs.
- A destroyed building would leave the entity list at the end of the tick in which it dies.
- The follower advances to the next route node as soon as the remaining distance projected on its
  route direction drops to 1000 units, so the unit effectively aims two nodes ahead and clips the
  corner of the cell it enters a bridge through. Whether the shipped game does exactly this is not
  settled; these files encode the behaviour the simulator implements.

## `golden/<case>.json` - the trajectory

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

## `golden/knight_left_kill.json` - the run that goes on to the end

The left deployment, run past the lock until the king tower is destroyed: 1258 ticks, at level 11 on
both sides. Beside the fields above it carries:

- `level` and `damage` - the level everything is at and the damage of one Knight hit, 202.
- `towers` - the six towers with their positions, sides and starting hit points: 3052 for a princess
  tower, 4824 for the king tower.
- `events` - every hit, with its tick, target, damage and the target's remaining hit points, and
  every death. The damage is what the hit dealt, which the hit that kills overshoots: the last hit
  on the princess tower deals 202 against the 22 it has left. The first hit lands nine ticks after the lock: an attack that starts from zero is
  credited the whole of a load that has run down, 700 ms of the 1200 ms hit speed, and takes one
  50 ms step on top. Later hits follow at the hit speed, 24 ticks apart. When the princess tower
  dies its removal takes the Knight's reference with it, and the Knight stands five more ticks
  without a target while the attack finish time runs down, so the walk to the king tower starts on
  tick 610.
- `fields` and `records` - one compact record per tick, in the order `fields` gives: the fields
  above plus `speed`, the movement budget the tick was given, and `hp`, the hit points of the
  reference after the tick. Both are null on the deploying ticks, which have no movement visit and
  no reference.

The engine writes this layout itself. `TrajectoryRecorder` in `org.crforge.core.battle.unit`,
attached to a battle's world, records one character's run with the same header, tower lines, event
lines and records, ticks counted from the character's first tick in the holder, and
`TrajectoryRecorderTest` holds what it writes for the kill run to this file byte for byte. A run the
engine plays can therefore be compared with a reference directly, or become a fixture here.

## `movement_replay/<case>.json` - the routing answers of the same run

Every question the movement pass put to the routing grid during the same run, in order, with the
inputs it asked with and the answer it got.

```json
{
  "case": "knight_left",
  "calls": [
    {
      "tick": 20,
      "query": "endpoint",
      "inputs": [
        7,
        51,
        1700
      ],
      "output": 393264
    },
    {
      "tick": 20,
      "query": "search",
      "inputs": [
        7,
        20,
        6,
        48,
        1
      ],
      "outputs": [
        1734,
        1699
      ]
    }
  ]
}
```

- `attackRange` - the unit's attack range in game units, which sizes the endpoint scan.
- `endpoint` - takes the reference's cell and that range, and answers the cell the unit should stop
  at, packed as `(column << 16) | row`.
- `search` - takes a start cell, a goal cell and the goal-adjust flag, and answers the route, goal
  first, as row-major cell ids (`row * 36 + column`).
- `farther` - answers whether the route leads past the reference.
- `relocate` and `cellTest` - do not occur in those three runs; the format is listed for
  completeness.

The movement replay test feeds these answers back and asserts that the same inputs are asked for, so
a change in how the movement pass phrases its questions fails as loudly as a change in where the
unit ends up.

Not every preparation in the log comes from the movement visit. Storing a reference prepares the
route to it at once, so the tick a unit takes or changes its target holds two preparations: the
reference setter's, with a search, and then the movement visit's, which finds the goal unchanged and
stops after the endpoint scan. The tick a unit resumes moving prepares a route too, but with no
reference and no goal that preparation asks the grid nothing and leaves no entry.

## Cases

| Case                 | Deploy         | Target                                             | Attack lock |
|----------------------|----------------|----------------------------------------------------|-------------|
| `knight_left`        | (3500, 10000)  | PrincessTower_1_1                                  | tick 235    |
| `knight_right`       | (14500, 10000) | PrincessTower_1_2                                  | tick 235    |
| `knight_centre`      | (9000, 12000)  | KingTower_1_0, then PrincessTower_1_2 from tick 82 | tick 245    |
| `knight_right_rear`  | (16500, 5000)  | PrincessTower_1_2                                  | tick 323    |
| `knight_behind_king` | (9000, 4600)   | KingTower_1_0, then PrincessTower_1_2 from tick 73 | tick 368    |

The centre case is the interesting one: the king tower is the closest in x from the deploy point, so
the unit walks at it until a princess tower becomes closer in x, which happens at tick 82.

The last two cases deploy the unit beside one of its own towers - behind the right princess tower
and behind the king tower - so that the trajectory runs through the part of the arena where a
building is a neighbour. `knight_behind_king` is the sharpest of the five: the unit starts inside
its own king tower's collision circle, and a change to how a building takes part in pushing or
steering sends it down the other lane. Only `knight_left`, `knight_right` and `knight_centre` have a
`movement_replay` file; the two new cases are replayed through the engine only.
