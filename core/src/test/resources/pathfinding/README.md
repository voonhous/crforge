# Recorded trajectories of a single unit on the standard arena

Each file holds one recorded deployment of a Knight of the top side on the standard 36 by 64 cell
arena, tick by tick at 20 ticks per second. They are used to check that the simulation reproduces
the behaviour of the standard game on a path where every rule involved is understood.

## Conditions

- One unit and the six crown towers are on the arena, nothing else. With a single unit the push
  and avoidance rules have nothing to act on, so the recordings do not exercise them.
- The unit is a Knight: speed 60 units per tick, attack range 1200, sight range 5500, collision
  radius 500, hit speed 1200 ms, wind-up 700 ms, deploy time 1000 ms. It attacks the ground only.
- The deployment lasts twenty ticks; ticks 0 to 19 are the deploying state at the deploy position
  and the unit leaves the countdown in the moving state, because it has a movement component.
- The towers of the top side are at (9000, 3000), (3500, 6500) and (14500, 6500); the bottom side's
  are the same mirrored along the arena's length. King towers have a collision radius of 1400 and
  princess towers 1000.
- Levels and damage do not affect the path and are not recorded; no tower is destroyed in any of
  these three recordings.

## Fields

- `card`, `deploy`, `side`, `lane` - the deployment.
- `towers` - `name`, `x`, `y`, `side` of the six crown towers.
- `records` - one entry per tick with `tick`, `x`, `y`, `state`, `ref` (the target's name or null),
  `route` (the number of route nodes left) and `speed` (the movement budget of that tick).

A record is written after every pass of the tick has run, so the entry for tick `n` holds the
position and the state the unit ends tick `n` with.

## Cases

| File | Deploy | Target | Attack lock |
| --- | --- | --- | --- |
| `knight_left.json` | (3500, 10000) | PrincessTower_1_1 | tick 235 |
| `knight_right.json` | (14500, 10000) | PrincessTower_1_2 | tick 235 |
| `knight_centre.json` | (9000, 12000) | KingTower_1_0, then PrincessTower_1_2 from tick 82 | tick 242 |

The centre case is the interesting one: the king tower is the closest in x from the deploy point,
so the unit walks at it until a princess tower becomes closer in x, which happens at tick 82.

---

# Recorded Knight trajectories

Two sets of fixtures for the grid movement tests, for three deployments of a Knight on the standard 36x64 arena.

## `golden/<case>.json`

One record per 50 ms tick of the whole deployment, from placement to the tick the unit locks onto a tower.

```json
{"card": "Knight", "deploy": [3500, 10000], "side": 0, "lane": 1,
 "records": [{"tick": 20, "x": 3518, "y": 10056, "state": 1, "ref": "PrincessTower_1_1", "route": 27}]}
```

- `deploy` is the placement position in game units, 1000 per arena tile and 500 per routing cell.
- `x` and `y` are the unit's position at the end of that tick.
- `state` is the entity state: 4 while it is being placed, 1 while it walks, 2 once it stands and attacks.
- `ref` is the tower the unit is heading for, or null while it has none.
- `route` is how many cells are left on its route.

The three cases are `knight_left` (deployed at 3500, 10000), `knight_right` (14500, 10000) and `knight_centre`
(9000, 12000). The centre deployment takes the enemy king tower as its first target and switches to the right
princess tower once that tower is closer in x, which is why its `ref` changes partway through.

## `movement_replay/<case>.json`

Every question the movement pass asked the routing grid during the same run, in order, with the inputs it was
asked with and the answer it got.

```json
{"case": "knight_left",
 "calls": [{"tick": 20, "query": "endpoint", "inputs": [7, 51, 1700], "output": 393264},
           {"tick": 20, "query": "search", "inputs": [7, 20, 6, 48, 1], "outputs": [1734, 1699]}]}
```

- `attackRange` is the unit's attack range in game units, which sizes the endpoint scan.
- `endpoint` takes the reference's cell and that range and answers the cell the unit should stop at,
  packed as `(column << 16) | row`.
- `search` takes a start cell, a goal cell and the goal-adjust flag and answers the route, goal first,
  as row-major cell ids (`row * 36 + column`).
- `farther` answers whether the route leads past the reference.
- `relocate` and `cellTest` do not occur in these three runs; the format is listed for completeness.

The replay test feeds the recorded answers back and asserts that the same inputs are asked for, so a change in
how the movement pass phrases its questions fails as loudly as a change in where the unit ends up.

## Conditions the recordings assume

- One unit and the six crown towers are the only things on the arena, so nothing pushes the unit and nothing
  steers it around a neighbour.
- Towers stand at (3500, 6500), (9000, 3000) and (14500, 6500) for side 0, mirrored down the arena for side 1.
- The unit leaves its deployment countdown in the moving state, which is what a unit with a movement component
  does.
- A destroyed building would leave the entity list at the end of the tick in which it dies; no building dies in
  these three runs.
- The follower advances to the next route node as soon as the remaining distance projected on its route
  direction drops to 1000 units, so the unit effectively aims two nodes ahead and clips the corner of the cell
  it enters a bridge through. Whether the shipped game does exactly this is not settled; these recordings
  encode the behaviour the simulator implements.
