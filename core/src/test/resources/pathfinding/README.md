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
