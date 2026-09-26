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

- One unit and the six crown towers are the only things on the arena, so no run here exercises
  unit-to-unit pushing or steering. The towers do take part in both: a tower is a static neighbour
  of the push pass, with a mass of 0, and an obstacle of the avoidance handler. Two of the runs are
  the ones that reaches: `knight_right_rear` is steered around its own right princess tower from
  tick 40, and `knight_behind_king` is pushed 1 unit a tick by its own king while it deploys.
- The unit is a Knight: speed 60 units per tick, attack range 1200, sight range 5500, collision
  radius 500, hit speed 1200 ms, wind-up 700 ms, deploy time 1000 ms. It attacks the ground only.
- The deployment lasts twenty ticks. Ticks 0 to 19 are the deploying state, at the deploy position
  unless a tower pushes the unit, and the unit leaves the countdown in the moving state, because it
  has a movement component.
- The towers of the bottom side stand at (9000, 3000), (3500, 6500) and (14500, 6500) in game units;
  the top side's are the same mirrored along the arena's length. King towers have a collision radius
  of 1400 and princess towers 1000.
- Levels and damage do not affect the path and are not carried here; no tower is destroyed in any of
  the six lock runs. The two kill runs below carry both and run to a tower's death.
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

## `golden/musketeer_left_kill.json` - a unit that fires

The left deployment again, at level 11 on both sides, but a Musketeer, and the towers stand passive:
they hold their references and never attack, so only the Musketeer's shots move anything. It runs
457 ticks, to the closing cleanup of the tick the princess tower dies. The Musketeer walks the same
lane as the Knight, stops at (3731, 18054) and locks on at tick 155; its first shot leaves at 168,
the fourteenth attack visit after the lock (a load of 300 ms against a hit speed of 1000 ms), and
one more every 20 ticks. A shot flies eight ticks and lands for 217; the fifteenth kills the tower
at 456. The layout is the kill run's, with three additions:

- `events` carries three kinds beside `hit` and `death`. A `launch` names the projectile
  (`proj_` and its id), its row (`config`), its owner and target, where it starts (`x`, `y`, `z`:
  450 units along the line to the target and 450 units up, the Musketeer's launch columns) and
  where it is aimed (`aim`, `aim_z`: the tower's centre on the ground). An `impact` names the
  projectile and its target, the damage, the target's remaining hit points and where the projectile
  stands, which is its aim. A death follows the impact that kills, on the same tick.
- `projectiles` lists one `[tick, id, x, y, z]` per projectile per tick it flew: the position
  after each of its seven flight steps, the arrival tick excluded because the projectile is released
  on it. The positions descend in a straight line, because the row's gravity is zero. A run without
  projectiles has no such key.
- `damage` is the unit's damage getter: the row's own damage at the level, which for a unit that
  fires falls back to its projectile's, 217 here.

Ids are the game's: the six towers are 5000000 to 5000005, the Musketeer 5000006, and every
projectile 4000000 upward in launch order, so a projectile precedes every character in each pass.
The shots are the only projectiles of the run, so their ids are consecutive from 4000000.

`BattleMusketeerRunTest` holds the battle to the run as the Musketeer's outside shows it,
`BattleProjectileFlightTest` to every launch, position and impact, and `TrajectoryRecorderTest`
writes it out and holds the file to this one byte for byte.

## `golden/tower_vs_knight_left.json`, `golden/musketeer_vs_tower.json`, `golden/wizard_vs_tower.json` and `golden/tower_vs_knight_left_level1.json` - the towers fight back

The left deployment of a Knight, of a Musketeer and of a Wizard, at level 11 on both sides, with the towers fighting, and the Knight once more with the towers at the first level. In the first three only the princess tower in front of the unit ever has it in range; neither king tower fights, because nothing damages a king tower and no princess tower falls, which is what would wake it.

- In the Knight's run the princess tower locks on at 130, when the Knight comes within its range of 7500 plus both radii, and fires its first arrow at 145, the sixteenth attack visit after the lock (no load, a hit speed of 800 ms), then one every 16 ticks. An arrow leaves 300 units along the line to its aim and 3000 units up, homes onto the Knight and lands for 109, the princess tower's damage rule at level 11. The Knight still reaches the tower, locks on at 235 and lands seven hits, from 244 to 388, before the seventeenth arrow kills it at 405.
- In the Musketeer's run the tower locks on at 130 too; the Musketeer stops short at (3731, 18054), locks on at 155 and fires five shots, which land from 176 to 256, before the tower's seventh arrow kills it at 253; its last shot is still in flight then and lands three ticks after it has left, so the run goes on to that impact.
- In the Wizard's run the tower locks on at 130 as well. The Wizard fires from 170, every 28 ticks; its fireball is a row with a radius of 1500, so its impact damages everything in that circle around the aim rather than its target alone. Only the tower is inside it, and it takes 281 at 181, 209 and 237; the tower's seventh arrow kills the Wizard at 253. The impact events of such a projectile are one per victim.
- In the level-1 run the towers are at the first level (arrows of 50, a princess tower of 1400 hit points, a king of 2400), so the Knight destroys the princess tower at 388 with its seventh hit. Its side is left with one princess tower, which is the king's condition to wake: the king's wait sees it at 389 and ends, the 3300 ms activating run starts in the same tick, finishes at 456 and is removed at 457, and the king locks on at 459, fires from 468 and first hits at 471. The other princess tower locks on at 447 as the Knight passes within its reach; the Knight locks on the king at 480, lands seven hits from 489 to 633 and dies at 635 under three towers' fire.

Each file runs to the unit's removal, and the tower events go on for twenty ticks more. The layout is the Musketeer run's, with four additions:

- `tower_level`, the level the towers are at, and `towers_attack`, true.
- `events` carries the towers' launches and impacts beside the unit's, in the order the battle made them.
- `tower_events` lists what the towers' own targeting did: a `reference` each time a tower's reference changes, with the new one and whether it is in range, a `lock` when a tower starts attacking, and, in the cleanup that removes an entity, the `reference` it left each tower holding with the target-lost countdown it started (`target_lost_timer`), a `reference_dropped` for a tower that had locked on, and the `removed` entity itself. The level-1 run adds the steps of the king's activation: `activation_condition` (with whether the king was `damaged` and whether its side had a `tower_destroyed`), `activating_started` and `activation_effect` with the pending pass (`phase`) they started in, `activating_finished` and `activating_removed`. Every tower holds the opposing tower its default selection gives from the first tick; once the unit has gone and the countdown has run out, the princess tower takes it again.
- `fields` ends with `own_hp`, the unit's own hit points after the tick; a deploying tick carries it too.

`BattleTowerRunTest` holds the battle to the records, the events and the projectile positions, `BattleTowerTargetingTest` to the tower events, and `TrajectoryRecorderTest` writes the four runs out and holds each file to these byte for byte. The older runs above were made with the towers passive, and the tests play them that way.

## `golden/valkyrie_vs_tower.json`, `golden/valkyrie_two_victims.json` and `golden/valkyrie_own_tower.json` - a unit that splashes with a direct hit

A Valkyrie at level 11, with the towers fighting. She has no projectile; every hit she lands damages the circle of 2000 around herself, and the validator, run with her own columns, spares her and her side.

- `valkyrie_vs_tower`: the left deployment. She locks on at 235 at (3731, 22854) and hits from 236 every 30 ticks; the circle holds the princess tower and herself, and the tower alone takes 266 each time, down to 1190 after seven hits. Eighteen arrows kill her at 421.
- `valkyrie_two_victims`: the same, with an enemy Knight placed on tick 190 at (6000, 26000). She turns to the Knight and stands at (3822, 22944); the hits at 238, 268 and 298 damage the tower and then the Knight, 266 each, in id order. She dies at 318.
- `valkyrie_own_tower`: the Valkyrie at (7500, 4500) and an enemy Knight at (3500, 17500), both on tick 0. They meet beside her own princess tower, which stands in her circle on all three hits (122, 152, 182) and takes nothing; the Knight falls to 1173, 689 and 205, and her tower's arrow finishes it at 206. She walks on to the enemy tower, hits it three times and dies at 507.

The layout is the Musketeer run's with three additions:

- `units` lists the further units: name, row (`card`), side, deploy position, the tick they are placed on, id, lane and starting hit points. The reference's own unit is 5000006 and a further unit 5000007.
- `unit_fields` and `unit_records` give each further unit one compact record per tick it is in the holder, from its placement: position, state, reference and its own hit points, taken after its state visit.
- `events` carries two more kinds. An `area_hit` is one victim's share: the attacker, the victim, the damage, the victim's remaining hit points and the hit's id; a death follows it when the share kills. An `area` follows a hit's shares: its owner, centre, radius, damage and crown-tower damage, the hit's id, the push (0, as no row of the data pushes with a hit), and the entities in the circle, those the validator accepted, and those it damaged.

The two runs with two units are the first where a unit walks at a unit that moves: the enemy Knight steers at the Valkyrie where she stands at the moment of its movement visit, after hers. Neither run has two units close enough to push or steer around each other.

## `golden/barbarians_left.json`, `golden/barbarians_edge.json`, `golden/skeleton_army_edge.json`, `golden/knight_side1.json` and `golden/deploy_refused.json` - placing a card

Units placed the way a player places them: by a place-card command carrying the card, the requested point, the side and the tick it runs on, at level 11 with the towers fighting. The requested point is not where a unit stands: the play clamps it to the arena, snaps it to a tile, moves it to the nearest tile the card may be placed on, and lays the card's units out around it.

- `barbarians_left`: Barbarians requested at (3500, 10000) for the bottom side on tick 0 are placed at (3499, 10500) - the tile centre, then one unit left of it, as a unit left of the arena's middle is - and stand in a ring of five around it. The first starts deploying at once; each later one waits 100 ms more than the one before.
- `barbarians_edge`: the same card requested in the corner at (0, 1000), placed at (499, 1500); two units are moved in to 250 from the edge, and one lands on another that is still waiting and is pushed off it on its first tick.
- `skeleton_army_edge`: fifteen Skeletons requested in the corner; the spiral puts three on the placed point and the inset puts two more on one spot, and every stack is split on the first tick.
- `knight_side1`: a Knight of the top side requested at (14500, 22000) on tick 7, placed at (14500, 22499): the top side's point is one unit short of the tile centre along the length.
- `deploy_refused`: a Knight requested on the river is refused with code 0x13, one right of the arena with 0x11, and neither creates anything or takes an id; one requested in the enemy half is moved to the nearest tile of its own half, (8500, 14500).

Each file lists its `commands`: the play's name, card, side, requested `point` and `tick`, the `outcome` and its `code`, and for a placed play the `placed` point, the `interval` along the length its units are clamped into, the `origin_lane` of the placed point, and each unit's name, id, row, index, `formation` offset, position, lane, starting `state` (4 deploying, 11 waiting), its wait (`delay`, 0 for none) and its deploy time. The units' records follow in `unit_records`, one per tick from their placement; there is no single unit, so `records` is empty. The `towers` list of these files also carries the placed units, with their positions at the end of the run.

`CardPlacementTest` works every play out and holds it to its command. `BattlePlacementRunTest` plays each file through the battle and holds it to every unit record, every event and every projectile position. A shot still in flight when the run ends is recorded past the last unit record and event, so the run goes on to the last projectile position.

## `golden/two_knights.json` - two units that kill each other

The same layout: a Knight of the bottom side requested at (3500, 12000) and one of the top side at (3500, 20000), both on tick 0, at level 11 with the towers fighting. They meet on the left lane, lock onto each other on tick 70 and hit on the same ticks from 79, every 24 ticks. On tick 271 the bottom Knight, visited first, kills the other, whose hit is due on the same tick and still lands, and both leave the battle in that tick's cleanup; the run ends at 291. On ticks 66 and 68 each holds an empty route for one tick while walking at the other, as the follower empties it.

## `golden/skeleton_army_bridge.json` and `golden/skeleton_army_corner.json` - several units at once

The same layout, two Skeleton Army plays of the bottom side on tick 0, at level 11 with the towers fighting, each run until 20 ticks after its last skeleton leaves: one requested at (3500, 10000) and placed at (3499, 10500), in front of the left bridge, and one requested at (500, 1500) and placed at (499, 1500), near the corner. The skeletons walk up the left lane as a crowd, push each other and are steered around each other and the towers, and fight the enemy princess tower, which dies on tick 256 (bridge) and 393 (corner). They hold what a crowd does in the standard game: at the bridge a skeleton pushed sideways walks on the water beside it from tick 60, and at the tower the crowd presses some of its own inside the tower's collision circle from tick 153; the corner run passes its own princess tower close enough to push a skeleton inside that one too, on tick 57.

## `golden/sparky_river.json` - a unit's own recoil, and the relocation off the river

The same layout. A Knight of the top side requested at (3500, 20000) on tick 0 walks down the left lane and hits PrincessTower_0_1 from tick 219. A Sparky of the bottom side requested at (1500, 14500) on tick 190 is placed at (1499, 14500), locks onto the Knight on 210 and launches on 229. After the launch it asks for its own pushback away from the aim: target (1264, 15212), budget 200, then displacements of 175, 150, 125 and 100 over the next visits. On 232 it stands on a river cell, and on 233 the pushback visit moves it off to (1079, 14770) before that visit's step. Later it walks across the river cells beside the bridge and nothing moves it; its pushes on 365, 444 and 523 end on land, and it dies on 524; its last shot destroys PrincessTower_1_1 at 528. Besides the usual events, the file lists each `pushback` request (the point pushed away from, where the unit stood, the target, the budget and whether it started) and each `relocate` (where the unit stood and where it was moved to).

## `golden/barbarians_pocket.json` - units created on the river

The same layout. The Barbarians of `barbarians_left` destroy PrincessTower_1_1 on tick 363. A second Barbarians card requested at (3500, 16000) on tick 400 is placed at (3499, 16500), on the bridge: with the tower gone, the pocket behind it leaves no column interval to clamp the formation into, and two of its units are created on river cells, at (4745, 16904) and (2253, 16904). They stay on the water through waiting, deploying and their first steps, and walk off it; nothing moves them. The king tower dies on 708 and stays in the holder: the units attacking it drop it through their own targeting and walk on, and it keeps firing at them to the end of the run.

## `golden/bush_goblins.json`, `golden/brawler_goblins.json`, `golden/gift_knight.json` and `golden/abort_instigator.json` - characters an action spawns

The towers fight at level 11. No object the battle has yet runs these rows from its hooks, so each file lists its `action_owners`: an object with a name, id (3000000, the area-effect band), side, position and packed level, and the rows scheduled on it in the command pass of their tick. `actions` lists every schedule, every run of an action with the pending pass it ran in, and every spawn: the child's name, id, where it was created, its state, deploy countdown, lane and hit points, and its position and state after its registration visit. The children's records follow in `unit_records`, with each one's elapsed time (`delay`), deploy countdown (`deploy`), whether it is still untargetable (`immune`), and `pending` on the record of the tick it was spawned in; `records` is empty.

- `bush_goblins`: a group on a bottom-side owner at (3500, 21500), scheduled on tick 0, spawns a Bush Goblin at (3000, 21500) on tick 12 and one at (4000, 21500) on tick 13, both deploying. The second is pushed to (4001, 21500) as it is registered. Each stays untargetable for six ticks, so PrincessTower_1_1 locks on the first on tick 19; they die on 70 and 127.
- `brawler_goblins`: the same on a top-side owner at (14500, 10500), four Goblin Brawlers on ticks 12 to 15 at (15000, 10500), (14000, 10500), (15000, 10000) and (14000, 10000): the side flips both axes of the location. The last two are pushed 150 as they are registered.
- `gift_knight`: a Knight spawned on its owner at (14500, 12000) on tick 5, walking at once: its registration visit takes PrincessTower_1_2 as its target and steps to (14518, 12056). The tower locks on 81, the Knight hits seven times from 195 and dies on 356.
- `abort_instigator`: the `gift_knight` run with three more `SpawnBrawler` schedules on the owner, two of them caused by the Knight (`instigator` on the schedule). The one scheduled on 346 runs on 356 before the Knight's cleanup and spawns a Goblin Brawler; the one the Knight caused on 347 is still waiting when the Knight dies on 356 and is dropped in that cleanup with one tick left (a `dropped` action event, with `ticks_left` and the queue after it); the owner's own, also from 347, runs on 357.

`BattleActionSpawnRunTest` plays each file through the battle and holds it to every action run, spawn and drop, every unit record, every tower's lock, every event and every projectile position.

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
| `knight_right_rear`  | (16500, 5000)  | PrincessTower_1_2                                  | tick 326    |
| `knight_behind_king` | (9000, 4600)   | KingTower_1_0, then PrincessTower_1_2 from tick 85 | tick 361    |
| `knight_left_inner`  | (8000, 10000)  | PrincessTower_1_1 on ticks 20 to 29, KingTower_1_0 from tick 30, PrincessTower_1_1 from tick 68 | tick 259 |

The centre case is the interesting one: the king tower is the closest in x from the deploy point, so
the unit walks at it until a princess tower becomes closer in x, which happens at tick 82.

The inner case pins the lane rule of the default selection. A unit is kept to the towers of its own
lane while its elapsed time, which starts at zero and grows by one step per state visit outside the
deploying states, is below 500 ms: its first ten walking ticks. From (8000, 10000) the king tower is
the closest in x but lies in the other lane, so the unit takes the left princess tower on tick 20,
switches to the king on tick 30 when the rule lets go, and comes back to the princess tower on tick
68 when it is the closer in x. In the other five cases the closest tower in x is in the unit's own
lane, so the rule never shows.

The last two cases deploy the unit beside one of its own towers - behind the right princess tower
and behind the king tower - so that the trajectory runs through the part of the arena where a
building is a neighbour. `knight_behind_king` is the sharpest of the six: the unit starts inside
its own king tower's collision circle, and a change to how a building takes part in pushing or
steering sends it down the other lane. Only `knight_left`, `knight_right` and `knight_centre` have a
`movement_replay` file; the two new cases are replayed through the engine only.
