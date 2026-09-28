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
- `ref` - the tower the unit is heading for, or null while it has none. It is null on the tick the
  unit dies too: the combat gate at the tail of its state visit drops it.
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
- `tower_events` lists what the towers' own targeting did: a `reference` each time a tower's reference changes, with the new one and whether it is in range, a `lock` when a tower starts attacking, and, in the cleanup that removes an entity, the `reference` it left each tower holding with the target-lost countdown it started (`target_lost_timer`), a `reference_dropped` for a tower that had locked on, and the `removed` entity itself. A reference the combat gate drops as a tower dies is not a change its targeting made and is not listed. The level-1 run adds the steps of the king's activation: `activation_condition` (with whether the king was `damaged` and whether its side had a `tower_destroyed`), `activating_started` and `activation_effect` with the pending pass (`phase`) they started in, `activating_finished` and `activating_removed`. Every tower holds the opposing tower its default selection gives from the first tick; once the unit has gone and the countdown has run out, the princess tower takes it again.
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

## `golden/knight_level_up.json` - a level change on a standing unit

The Knight's run with the towers fighting, `tower_vs_knight_left`, with the Royal Chef's level-up row, `ChefTower_increase_level_action` (a relative adjustment of 1), scheduled on the Knight on tick 250. `unit_schedules` lists it: the unit, the tick and the row, scheduled in the command pass of that tick on the unit's own action holder, the unit as its cause, as a buff's starting action would. The run is the Knight's through 249. In phase 1 of 250 the Knight goes from level 11 to 12 and its hit points from 1003 of 1766 to 1100 of 1938, keeping their share; its hits from 268 deal 221, not 202, and it dies on 421 instead of 405. `actions` records the schedule, the run and the level change with the levels, hit points and maxima before and after.

`BattleTowerRunTest` plays it with the other tower runs.

## `golden/chef_filter.json` - a row that names a data row

The towers fight at level 11. A MiniPekka is the run's unit at (3500, 10000), and a Knight at (4500, 10000) and a SuperMiniPekka at (2500, 10000) stand beside it for the bottom side, all placed on tick 0 and listed in `units`. The Royal Chef's pancake filter, `ChefTower_pancake_filter_mini_pekka` (an ActionFilter whose condition is `has_data(MiniPekka)`), is scheduled on each of the three on tick 5 (`unit_schedules`). The condition holds on the MiniPekka alone, the exact row: its branch, the effect row `ChefTower_pancake_reaction_mini_pekka`, runs at once inside the filter in the MiniPekka's phase-1 pass, listed in `actions` as a run without a phase; the Knight and the SuperMiniPekka take no branch. `filter` action events give each condition's value and the branch taken. The run stops on tick 11. The unit's records follow the kill-run layout; the other two follow in `unit_records`.

`BattleScheduledRowRunTest` plays it and holds every unit's position, state and hit points and every run of an action.

## `golden/bandit_greeting.json` - a row that checks whether objects exist

The towers fight at level 11. A Knight is the run's unit at (3500, 10000); a RascalGirl at (4500, 10000) for the bottom side and an Assassin at (3500, 22000) for the top side are placed on tick 0, and a Firecracker at (2500, 10000) for the bottom side on tick 6, each listed in `units` (the Assassin as `Assassin_enemy`). The Boss Bandit's greeting check, `BossBandit_rascals_only_check`, is scheduled on the Knight on ticks 5 and 8 (`unit_schedules`). It filters the battle's live objects with `friendly_troop_no_buildings`, which keeps the Knight's side's living troops, the Knight among them. On tick 5 it finds the Knight and the RascalGirl: no excluded row, a RascalGirl matched, so `BossBandit_greet_rascals` runs at once inside it. On tick 8 it also finds the Firecracker, an excluded row, which vetoes the check: `BossBandit_at_least_2_forestgangers_check` runs at once instead, counts the RascalGirl and the Firecracker, two as needed, and `BossBandit_greet_forest_gang` runs at once inside that. `run_if_exists` action events list what each check's filter found; a run without a phase started inside the one before it. The run stops on tick 11.

`BattleScheduledRowRunTest` plays it with `chef_filter`.

## `golden/golemite_convert.json` - a unit that takes another row

The towers fight at level 11. `ElixirGolem4_crazy_babyGolemite`, the crazy arena's baby golemite, is placed directly for the bottom side at (3500, 16000) on tick 0, level 11, and its row's starting group runs in its phase-1 pass of that tick (a `start` action event marks the schedule at placement). The group queues `ElixirGolem4_crazy_babyGolemite_storeMaxHp` 3500 ms later. PrincessTower_1_1's arrows land on 53 and 67, leaving 142 of 360. In phase 1 of 70, in one pass:
- the store runs, and writes `(hp * 100) / max_hp`, 39, to `ElixirGolem2_crazy_maxHP` (a `set_variable` action event);
- its waited next action swaps the unit's row for `ElixirGolem2` at once (a `change_data` event with the rows, hit points, maxima and radii before and after, the speed and the target): its hit points stay 142, its maximum becomes 762, its radius 500 and its speed 60, and PrincessTower_1_1 is kept as its target;
- the swap's own next action heals `(max_hp * ElixirGolem2_crazy_maxHP / 100) - hp`, 155, to 297 (a `heal` event).

The movement visit of 70 already walks at 60. Three more arrows kill it on 110, at (3740, 22930). Its new row spawns two ElixirGolem4 as it dies, inside the arrow's impact: on its death spawn ring of 750 at angles 180 and 0, (2990, 22930) and (4490, 22930), each 360 hit points, walking at once, registered in the impact's post-hook pass and moved to (2904, 22902) and (4540, 23005) by their registration visits (`spawn` action events with the phase `death`). They are untargetable through 115 and walk at PrincessTower_1_1, which kills them on 184 and 257; the run ends when every unit is dead. The header's `damage` is the generator's reading of the unit's row at the end of the run, after the swap; `card` is the row placed.

`BattleActionSpawnRunTest` plays it with the spawn runs, and `BattleChangeDataRunTest` looks at the swap on its tick.

## `golden/archer_ev1_vs_tower.json` and `golden/archer_ev1_knight.json` - an attack sequence

The towers fight at level 11. The evolved Archer (`Archer_EV1`) is the run's unit at (3500, 10000). Its row's order is [0, 1] with the mode None, so only an action moves its index: entry 0 launches its own arrow, entry 1 the double-damage one. Its starting-attack row `Archer_EV1_AttackSelect`, an ActionFilter on `!target_in_range(4500)`, is queued on it as each attack starts and at each hit, runs in its phase-2 pass, and sets the index to 1 for a target beyond 4500 or 0 for one within (`run`, `filter` and `set_attack_sequence_index` action events, with the index before and after).
- `archer_ev1_vs_tower`: it locks PrincessTower_1_1 on 155, the row sets 1 in phase 2 of 155, and the hits on 164 and 182 launch the double-damage arrow, 168 each; the towers kill it on 189.
- `archer_ev1_knight`: a top-side Knight walks down from (3500, 17000). The Archer shoots it with the double-damage arrow while it stands beyond 4500; the row run at the hit of 47 finds it within and sets 0, so from the next hit the arrows deal 112.

`BattleActionSpawnRunTest` plays both.

## `golden/area_effect_direct.json` and `golden/area_effect_death.json` - area effects

The towers fight at level 11. `area_effects` lists what each area effect did: `created` (its name, row, id, how it came about, its source, side, point, packed level and countdown), `folded` when a cleanup admits it, each `update` (the countdown before and after, the hits in the step, the radius and the damage of each hit that dealt one) and `removed`. `area_effect_hit` events give each victim's share, and `area` events the whole of a hit, as a unit's area does.
- `area_effect_direct`: a top-side Knight (`KnightRed`) at (3500, 24000) and a bottom-side one (`KnightBlue`) at (4500, 24000) placed on tick 0. GoblinDrillDamage is placed for the bottom side at (3500, 24500) on tick 10, in the command pass, so the opening cleanup admits it and it updates in that tick: its countdown goes from 1 to -49, its one hit (a hit speed of 0) deals 84 to the red Knight and 26 to PrincessTower_1_1 (its crown-tower percent of -70), spares the blue Knight on its own side, and pushes the red Knight 1000; it leaves at that tick's closing cleanup. Ghost_EV1_Summon_Damage_Area, placed at (3500, 24000) on tick 20 (hit speed 150, life 250), hits on its third update, 22, for 204 on the tower and the red Knight, and leaves on 24.
- `area_effect_death`: a Rage Barbarian, the run's unit, dies to PrincessTower_1_1 on 220 at (3739, 23412). Its DeathAreaEffect, RageBarbarianDummyForSpawn, is created there inside the killing arrow's impact (id 3000000, packed level 10, countdown 50) and admitted at 220's closing cleanup. On 221 its starting action spawns the Rage Barbarian's bottle in its phase-1 pass, and its one update takes it to 0 with a hit of no damage; it leaves at 221's closing cleanup. The bottle, `RageBarbarianDummyForSpawn_3000000_0`, a unit without hit points or a speed, stands on the area effect's point (state 0) and takes PrincessTower_1_1 as its reference in its registration visit. The run stops on 221.

`BattleActionSpawnRunTest` plays both.

## `golden/golem_death_pushback.json` and `golden/giant_skeleton_bomb.json` - death spawns that fly back and bombs

The towers fight at level 11, and a red Knight (`KnightRed`, in `units`) walks down the left lane in each. `death_pushback` action events give each child that flies back: the point it is moved to, the ring point it flies to, its step countdown and its budget. `deploy_end_death` events mark a bomb's death as its deploy ends.
- `golem_death_pushback`: a Golem, the run's unit at (3500, 10000), walks in bursts - it stops for 200 ms after every 1000 ms of walking - and locks on PrincessTower_1_1 on 390 at (3727, 23039); the Knight is placed at (3500, 21000) on 700. The tower kills the Golem on 830. Its death damage, 225 in a radius of 2000, lands on the Knight and pushes it 1800. Its two Golemites, DeathSpawnPushback set, are made on the Golem's point and aimed back at their ring points 1500 away, (2227, 23039) and (5227, 23039), with 6 steps of 250 (`death_pushback`); the first step falls in their registration visit. They destroy PrincessTower_1_1 on 879, walk at the king tower, and die on 1046 and 1060, each dealing its own death damage, 99 with a push of 900.
- `giant_skeleton_bomb`: a Giant Skeleton, the run's unit at (3500, 10000), destroys PrincessTower_1_1 on 524 and dies on 650 at (5706, 26376). Its death spawn, GiantSkeletonBomb, a building row without hit points or a range, stands on its point, deploying for its own 3000 ms (state 4), with no registration visit. As its deploy ends on 710 its state visit finds no hit points and runs its death slot, without the death hooks: its death damage, 535 and 1070 on a crown tower in a radius of 3000, lands on KingTower_1_0 and the Knight and pushes the Knight 1800. It leaves at 711's closing cleanup.

`BattleActionSpawnRunTest` plays both with the spawn runs.

## `golden/cannon_knight.json`, `golden/mortar_knight.json`, `golden/tombstone_life.json` and `golden/goblin_hut_life.json` - buildings once deployed

The towers fight at level 11, and each building is placed directly for the bottom side on tick 0, at level 11, listed in `units` with its row as `card`. A building's deploy ends in state 0 on tick 19 (69 for the Mortar's 3500 ms), and its targeting visits it from the next tick. Its lifetime's decay takes its hit points down from that tick: the maximum times 100,000 over the lifetime, over 20, in hundredths a visit. `building_log` lists what each building did: `placed` (its row, point, level, hit points, decay step, deploy time and spawner timer), `deploy_end`, each `spawner` firing (the row, the count, the radius, the timer after and the children made of the wave, 0 once a wave is complete) and `decay_death` (the hit points before the last step). Building locks are in `tower_events` with the towers'.
- `cannon_knight`: a Cannon at (4500, 11500) and a top-side Knight at (3500, 22000). The Cannon takes PrincessTower_1_1 as its reference on 20, locks on the Knight on 87 and fires TowerCannonball on 104 and every 20 ticks, 212 each. Its decay takes 137 hundredths a visit, so it has 620 left when the Knight's first hit lands on 169; three hits of 202 destroy it on 217, and PrincessTower_0_1 kills the Knight on 221.
- `mortar_knight`: a Mortar at (3500, 11500) and the same Knight. It locks on the Knight on 70 and launches MortarProjectile on 89, which lands on 109 for 266. On 130 the Knight, at (3734, 15450), is inside the Mortar's MinimumRange of 2900 plus its collision radius of 600 plus the Knight's 500, so the reference is dropped and the default PrincessTower_1_1 taken. The Knight destroys the Mortar on 263 and PrincessTower_0_1 kills it on 357.
- `tombstone_life`: a Tombstone at (5500, 11000) with no enemy in reach, for its whole life. Its spawner fires from the end of its deploy: a Skeleton on 19 and 28, then two every 80 ticks, on 98 and 108 up to 578 and 588, each in front of it at (5500, 12500), walking at once (state 1), with no first-tick immunity. Its decay, 88 hundredths a visit, takes its last hit point on 621, in the hit-points pass; its death slot spawns four Skeletons on the same in-front point, and it leaves at that tick's closing cleanup. It has no death damage. The towers kill every Skeleton, the last on 782.
- `goblin_hut_life`: a Goblin Hut at (12500, 11000), for its whole life. Its spawner fires three Spear Goblins a wave, on 19, 28 and 38, 238, 248 and 258, and 458, 468 and 478, each at (12500, 12500), and they walk at and shoot PrincessTower_1_2. Its decay, 146 hundredths a visit, kills it on 600, and its one death spawn stands on its own point.

`BattleActionSpawnRunTest` plays all four with the spawn runs, and holds them to the spawner firings and the decay's death as well.

## `golden/rage_knight.json`, `golden/zap_knight.json` and `golden/poison_knight_tower.json` - buffs

The towers fight at level 11, and the run's unit is a Knight walking up the left lane from (3500, 10000). A spell's area effect is placed directly, at level 11, in the command pass of its tick, as `area_effect_direct` places one (a `created` area effect event with `how` "placed"). `buffs` lists what each buff did: `area_buff` (the area effect, the buff row, the time and the characters it reached), `applied` (a new instance: its target, row, `key`, time, level and source), `refreshed` (a re-application, with the time left before and after), `removed` (an instance whose time ran out, in the buff pass) and `damage` (a hit of damage over time, with the hit points after). `events` adds a `buff_hit` for each hit of damage over time (with the hit points before it and the source), and, for a stun, a `combat_gate_drop` (the reference the combat gate dropped) and a `combat_component` each time the gate switched the unit's targeting off or back on.

- `rage_knight`: Rage for the bottom side at (3731, 21500) on tick 190. On its first update it creates RageDamage at its point, which hits on 191 and harms nothing: its only character in reach is the Knight, on its own side. From 195 every hit of Rage, six ticks apart, applies a 1000 ms Rage buff to the Knight (its own troops only), refreshing the one instance, the last on 279, as Rage leaves. From 196 the Knight walks at 78 instead of 60 and steps its attack timer by 65 instead of 50; it locks on PrincessTower_1_1 on 226 and hits on 233, 252, 270 and 289. The instance runs out on 285, and the next hits come every 24 ticks again, from 313. The tower kills the Knight on 405.
- `zap_knight`: Zap for the top side at (3731, 22854) on tick 254, on the Knight attacking PrincessTower_1_1. Its one hit deals 192 to the Knight and applies a 500 ms stun, ZapFreeze, with a hit speed of -100: at the gate of that tick the Knight's reference is dropped and its targeting switched off. The instance runs out on 264, the gate of that tick switches the targeting on, the Knight takes the tower again on 265, and the hit due on 268 lands on 278. The tower kills the Knight on 373.
- `poison_knight_tower`: a top-side Knight (`KnightRed`, in `units`) is placed at (3500, 24500) on tick 150, and the two Knights fight in front of PrincessTower_1_1. Poison for the bottom side at (3700, 23500) on tick 240 applies its buff with every hit, 1000 ms, to the tower and the red Knight, refreshing one instance on each (stacking by its source). Its damage over time lands every 20 visits from 264: 92 on the Knight and 23 on the tower (its crown-tower percent of -75), and it slows the Knight's walk to 51 once it walks again. Poison leaves on 399, and the instances run out on 374 on the Knight and 419 on the tower.

`BattleActionSpawnRunTest` plays all three with the spawn runs, and holds them to the buff log as well.

## `golden/minions_left.json`, `golden/minion_musketeer.json`, `golden/balloon_tower.json`, `golden/balloons_cross.json`, `golden/balloon_river.json`, `golden/lava_hound_river.json` and `golden/baby_dragon_left.json` - air units

The towers fight at level 11 unless stated. An air unit is created at its row's flying height and keeps it. It runs the ground unit's code with other layer answers: its route is one node, the nearest cell within its attack range of the target, with no search; it crosses water; the push and avoidance passes meet only units on its side of height 0; a target in the air needs an attacker that attacks air. Its shots start at its height plus the row's launch height, and a shot aimed at it climbs to its height.

- `minions_left`: a Minions card played at (3500, 10000) for the bottom side on tick 0, placed at (3500, 10500): an air card takes no symmetric snap. Its three Minions fly at 1500, one deploying and two waiting. PrincessTower_1_1 locks each in turn and kills them on 135, 195 and 255; two lock the tower and spit from 1950. It is a placement file, played by `BattlePlacementRunTest`.
- `minion_musketeer`: a top-side Minion from (3500, 21000) meets a bottom-side Musketeer at (3500, 9000) and Knight at (4500, 9000) (in `units`). The Musketeer takes the Minion on 54 and kills it on 92 with shots that climb to it; the Knight, which does not attack air, only ever references PrincessTower_1_1. The Minion locks the Knight on 78 and spits on 87.
- `balloon_tower`: a Balloon locks PrincessTower_1_1 on 256 at (3298, 23927) and hits it for 640 four times. It dies on 389, and its death spawn, BalloonBomb, stands on its point on the ground (height 0, deploying 3000 ms) and dies as its deploy ends on 449, dealing 240 to the tower.
- `balloons_cross`: with the towers passive, a bottom-side Balloon from (3500, 12000) and a top-side one from (3500, 20000) cross over a top-side Knight from (3500, 17500). They meet on 78 and push each other apart, the Knight moves neither and neither moves it, and neither Balloon references the other. A run without the towers fighting records no hit points of its own unit.
- `balloon_river`: a level-1 Balloon from (2000, 14500) meets four level-11 top-side Musketeers at (1000..4000, 21500) and dies on 42 at (2141, 15857), over the river. Its BalloonBomb stands one unit to the right, at (2142, 15857), on the water - every in-front try is water - and dies as its deploy ends on 102, dealing 94 to each Musketeer. Once the Balloon is gone the Musketeers take PrincessTower_0_1 on 48: the bomb, a building placed during the battle, is no default target.
- `lava_hound_river`: a level-1 Lava Hound from (2000, 14500) meets four level-11 top-side Musketeers at (1000..4000, 21500) (each unit with its own `level`) and dies on 58 at (1961, 16216), over the river. Its six LavaPups, at 3500, are made on its point and fly back to their ring points over the water, 250 a step, and die on 81, 103 and 126.
- `baby_dragon_left`: a Baby Dragon locks PrincessTower_1_1 on 138 at (3288, 20521) and fires from 5400 on 143 and every 30 ticks, 161 on the tower, before it dies on 277.

`BattleActionSpawnRunTest` plays all but `minions_left` with the spawn runs.

## `golden/fireball_knight_tower.json`, `golden/zap_knight_cast.json` and `golden/goblin_barrel_tower.json` - spells played by a command

The towers fight at level 11, and every spell is played by a place-card command (in `commands`, with the requested `point`, the `placed` point, the outcome `cast` and what the cast `made`). A spell summons nothing: its point is clamped and snapped to the tile centre, and in the command pass of its tick its area effect is created at the placed point, or its projectile starts from its side's king tower at (9000, 3000) or (9000, 29000), 4200 up, with no target. Either first acts in that tick's post-hooks, at the card's level (10 for level 11). A cast projectile has no `launch` event; its positions are in `projectiles`.

- `fireball_knight_tower`: the Knight of `tower_vs_knight_left`, and a top-side Knight, `KnightRed`, placed at (3500, 24500) on 150 below PrincessTower_1_1 (in `units`; the generator did not list it, and the file takes it from the run's definition). A Fireball played for the bottom side at (3500, 24000) on 150 lands at (3500, 24500). It flies 600 a visit from the blue king and lands on 187: 207 on the tower (its crown-tower percent of -70), 688 on KnightRed, which is pushed 1000 away from the impact point (a `pushback` event).
- `zap_knight_cast`: `zap_knight` with Zap played by the top side's command on 254 at (3731, 22854), snapped to (3500, 22500), where the area effect is created (`how` "cast", its source the play). Everything after is `zap_knight`'s.
- `goblin_barrel_tower`: a Goblin Barrel played for the bottom side at (3500, 23500) on 20 is searched for its Goblin, so it lands at (3499, 23500), with the symmetric adjustment. It lands on 73 and makes three Goblins in formation around the point, at (3499, 24077), (3000, 23212) and (3998, 23212), deploying for 1100 ms (`spawn` actions with the projectile as owner). Each registration visit pushes a Goblin by a unit, and the relocation off the water that follows puts it back on its created point. They deploy until 95 and attack PrincessTower_1_1, which kills them on 107, 147 and 187.

`BattleActionSpawnRunTest` plays all three with the spawn runs.

## `golden/arrows_skeletons.json` - Arrows

The towers fight at level 11. Skeletons played for the top side at (3500, 22000) on tick 0 (a placement, with its units in `commands` and `units`), and Arrows played for the bottom side at (3500, 17000) on 60, placed at (3500, 17500). The cast makes 30 arrows (in `commands`: each one's start, aim, delay and `ring_point`): three waves of ten, delays 0, 200 and 400, each wave a chain. A wave's first arrow has the placed point as its ring point and the others stand 2660 out at 40-degree steps; each aims at its ring point plus 840 turned by a battle random below 359 (the 30 `draws`, from the battle's start state), and starts from the blue king shifted by that offset. The later waves first move on 64 and 68. The first wave's arrows land on 73 and 74, each dealing 122 on its ring point, and kill the three Skeletons; no Skeleton is hit twice by a wave. `projectiles` holds every arrow's position to its arrival, past the last event.

`BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/log_goblins.json` and `golden/barb_barrel_knight.json` - a thrown projectile that spawns a rolling one

The towers fight at level 11. The Log and the Barbarian Barrel are thrown: the point snaps to the tile centre, the thrown projectile starts 3000 behind it toward the caster's side, 7000 up, and falls to the point at 600, the rolling one's height; it lands eight ticks after the cast. Its impact launches the rolling projectile from there, aimed its range beyond along the line (10100 for The Log, 4500 for the barrel), moving 200 a visit from the next tick at 600. After every step the rolling projectile hits what its body covers (1950 or 1300 across, 600 along), each entity once; a hit is an `impact` event at the rolling projectile's position.

- `log_goblins`: Goblins played for the top side at (3500, 14000) on tick 0; The Log played for the bottom side at (3500, 9000) on 30, placed at (3500, 9500), thrown from (3500, 6500) and landing on 38. The rolling log moves from 39, hits the four Goblins on 53, 55, 56 and 60 for 268 each, killing each, so none is pushed, and arrives at (3500, 19600) on 89. After Goblins_3 dies, PrincessTower_0_1's attack runs on with no reference until its shot at 74; the file logs that as a `lock` of nothing followed by a `reference_dropped` on every tick from 61, which is no lock.
- `barb_barrel_knight`: a top-side Knight, `KnightRed`, at (3500, 13000) on tick 0 (in `units`; the generator did not list it, and the file takes it from the run's definition), and the Barbarian Barrel played for the bottom side at (3500, 9000) on 20. The rolling barrel hits the Knight once on 36 for 232 and passes it on 37 to 43 without hitting it again; it has no pushback. It arrives at (3500, 14000), and its impact spawns one Barbarian there (`spawn` action, owner the rolling projectile), deploying for 1000 ms.

`BattleActionSpawnRunTest` plays both with the spawn runs.

## `golden/recruit_tower.json`, `golden/guards_knight.json`, `golden/poison_guards.json` and `golden/tombstone_crazy_life.json` - shields

The towers fight at level 11. A shielded unit is created with its shield full, at its row's ShieldHitpoints at its level (240 for a Recruit, 256 for a SkeletonWarrior). Every hit meets the shield first; it takes the whole hit up to its value, the rest is lost, and the hit points take nothing. `shields` lists every hit a shield took: the tick, the target, what hit it (`by`) and how (`kind`), the damage, the shield before and after, the hit points, whether it broke, and the attackers whose attack the break reset (`resets`, none here).

- `recruit_tower`: a Recruit walking up the left lane. PrincessTower_1_1's arrows of 109 take its shield from 240 to 131, 22 and 0 on 157, 172 and 186 (87 lost), its 547 hit points untouched; then the arrows take the hit points, and it dies on 278.
- `guards_knight`: Guards (SkeletonWarriors) played for the bottom side at (3500, 10000) on tick 0, and a top-side Knight at (3500, 16000) on tick 0 (in `units`; the generator did not list it, and the file takes it from the run's definition). The Knight's 202 leaves Guards_0's shield at 54 on 50 and breaks it on 74 (148 lost); Guards_0 dies on 98. The Knight dies on 121. The tower's arrows break Guards_1's shield on 199 and kill it on 213, and take Guards_2's shield in three, on 239, 255 and 271, before the fourth arrow kills it.
- `poison_guards`: the Guards without the Knight, and Poison placed for the top side at (3500, 11000) on 20. Its 92 a hit takes each shield to 164 on 44, 72 on 64, and breaks it on 84 (20 lost); the next hit kills two Guards on 104, and the third falls to an arrow on 113.
- `tombstone_crazy_life`: Tombstone_crazy_1 placed for the bottom side at (5500, 11000) on tick 0, its whole life: tombstone_life's schedule, each child a SkeletonWarrior with a 256 shield that the tower's arrows take in three before the fourth kills. The decay kills the Tombstone on 621, and its death action, scheduled on it with itself as the cause (`death_hooks`), runs in its phase-2 pass and spawns SkeletonKing on its point, handed over to its side. The run stops at the king tower's death on 657.

`BattleActionSpawnRunTest` plays all four with the spawn runs.

## `golden/witch_left_lane.json` and `golden/night_witch.json` - a unit's own spawner

The towers fight at level 11. The run's unit is placed directly for the bottom side at (3500, 10000) on tick 0, at level 11, and walks up the left lane at PrincessTower_1_1. Its spawner runs in its state visit from the end of its deploy on 19, whether it walks or attacks: each visit takes 50 ms off its timer, which starts at its row's 1000, so the first wave comes on 38 and the next every 140 or 100 ticks. The children stand on the ring of its spawn radius around where it stands as the wave fires, the ring untested for passability, walking at once (state 1) in lane 1, with no first-tick immunity, so the towers may lock on them at once. `building_log` lists each firing (`spawner`, as for a building) with the timer after it; `actions` lists each child (`spawn`, phase `live`, or `death` for a death spawn).

- `witch_left_lane`: a Witch. Four Skeletons on 38, at (3651, 9112), (1651, 11112), (3651, 13112) and (5651, 11112) while it walks; four more on 178, centred on (3716, 18552), while it attacks the tower. The towers kill it on 306, before its third wave; its Skeletons take the tower on 375, and the last dies on 441.
- `night_witch`: a Night Witch (DarkWitch). Two Bats a wave, on 38, 138, 238 and 338, the ring of 1500 turned by its row's angle shift of 90 plus the angle it faces, so each pair stands at its sides: on 38, facing (22, 255), a heading of 85, at (5145, 10982) and (2157, 11242); later, facing straight up, level with it. It dies on 343, and its death spawn's one Bat stands at (3231, 22434), 500 from it on a ring turned the same way. The Bats take the tower on 458, and the last dies on 492.

`BattleActionSpawnRunTest` plays both with the spawn runs.

## `golden/goblin_giant_tower.json` - riders

The towers fight at level 11. A Goblin Giant is played for the bottom side at (3500, 10000) on tick 0 (`commands`); it is placed at (3499, 10500). The play sets it deploying, which makes its two SpearGoblinGiant riders first: they take ids 5000006 and 5000007 and the Giant 5000008, so every pass visits them before it. Each is made on the ring of the Giant's 900, at (2599, 10500) and (4399, 10500), deploying for the Giant's 1000 ms and facing as it faces; its registration visit sees no parent, and it is attached after it (`spawn` actions, phase `attach`). From then on each rider is placed at 900 behind the Giant's heading, turned by its own -22 and its share of its 90 arc (45 and 0), at 4000 high: on tick 0 at (3850, 9672) and (3162, 9666). Visited before the Giant, it sits where the Giant stood before that tick's move, so it trails a tick behind.

The Giant stops for two visits after every 640 ms walked, hits PrincessTower_1_1 for 176, and the riders shoot it for 81 each from their height; no tower ever targets a rider. The tower falls on 523, and the king tower kills the Giant on 637. At that tick's closing cleanup the Giant leaves; each rider hears of it, is let go, runs its death slot - a SpearGoblin where it rode, deploying for 700 ms, immune at first (`spawn` actions, phase `death`) - and is removed in the same cleanup. The towers kill the two SpearGoblins on 677 and 689, and the run lasts to its last projectile position.

`BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/prince_tower.json`, `golden/dark_prince_tower.json` and `golden/hog_river.json` - the charge and the river jump

The towers fight at level 11, and each run places its unit for the bottom side on tick 0. Each lists, under `jump_charge_dash`, every charge completed, with its progress and where the unit stood, every charge lost at a movement visit, with the unit's state then, and every state its movement pass asked for.

- `prince_tower`: a Prince at (3500, 10000). Its charge grows by its step times 1000 over its range of 250, 240 a visit at its speed of 60, and completes on tick 61 at 10080; its budget is 120 from the next visit. The visit of 144 finds PrincessTower_1_1 in range and, its strike-now byte set, hits at once for its charged 783; the charge is lost there. It then hits for 391 every 28 ticks, and the tower falls on 312. Walking on toward the king, it charges again (359) and hits the king for 783 on 382; it dies on 428.
- `dark_prince_tower`: a Dark Prince at (3500, 10000), with its range of 300 charged on 69. The tower's arrows break its 240 shield on 151; on 152 its charged 532 reaches the tower through its area of 1100, then 266 every 26 ticks, until it dies on 343.
- `hog_river`: a Hog Rider at (9000, 13000). Its route may cross water at the water cost of 7, so from the middle of the arena it runs straight over the river. On 32, at (10092, 14092), the node after the one it reached is water: its route becomes node 1250, the first land cell beyond, 4466 units away, and it jumps, at 160 a visit, its height left at 0. PrincessTower_1_2 locks on it mid-jump on 56. On 58, with fewer than two steps left, it asks for the moving state at (13030, 17030), and its route is prepared from there. It hits the tower for 317 from 123 until it dies on 315.

`BattleActionSpawnRunTest` plays them with the spawn runs.

## `golden/bandit_knight.json` and `golden/mega_knight_group.json` - the dash

The towers fight at level 11. Each run places its dasher for the bottom side on tick 0 and red Knights ahead of it, and lists, under `jump_charge_dash`, every dash started - its reference, where it stood, the point it aimed at, its route, its stop-in-range byte, its dash time and its wind-up - every state its movement pass asked for, and every landing with its hit, its landing hold and its state.

- `bandit_knight`: a Bandit at (3500, 10000) and a Knight at (3500, 18000). The Knight is inside the Bandit's ring from tick 30; the wind-up of 800 holds the Bandit still from 31, and on 46 it dashes from (3665, 10965) toward the Knight at (3687, 16486), its route the single node 1087, short of the Knight by both radii. It flies 500 a visit and, after the second step of 53, at (3727, 14465), finds the Knight in range: it lands 389 on it, walks on at once, and its reset attack hits for 194 on 72, 92 and every 20 ticks. The Knight first hits it on 62; it dies on 158, and the reference its death drops asks for a resume. The reference was regenerated after its generator was corrected to test the range after each step at the position that step left the Bandit, as the game does; it first stopped the Bandit a tick later.
- `mega_knight_group`: a Mega Knight at (3500, 9000) and three Knights at (3500, 17500), (2700, 18000) and (4300, 18000). The wind-up of 900 holds it from 43; on 60 it dashes toward Knight_0 on node 979 for its constant 800 ms, 250 a visit, its height following the profile up to 3000 and back. It lands on 76 at (3750, 13750): 537 on each Knight over its radius of 2200, each pushed 1000 away, and a Knight's hit on it the same tick, since it has no immunity. Its landing hold keeps it standing until it walks on from 80. The run lists an area's pushes after all of its hits, where the battle pushes each victim right after its own; the test orders them the same way.

`BattleActionSpawnRunTest` plays them with the spawn runs.

## `golden/ram_rider_tower.json` - the Ram Rider

The towers fight at level 11. A Ram Rider is played for the bottom side at (3500, 10000) on tick 0 (`commands`) and placed at (3499, 10500). The play sets the Ram deploying, which makes its one rider first, id 5000006 before the Ram's 5000007, on the Ram's point (its spawn radius is 0), deploying for 1000 ms; `unit_spawner` lists it attached and, on 350, let go. The Ram charges on 61 at 10080, crosses by the bridge without a jump, and hits PrincessTower_1_1 for its charged 501 on 147, then 250 every 34 ticks, until it dies on 350; the rider, which targets troops only, takes no target all run, and is let go and removed in the cleanup of the Ram's death tick. The rider's records list its `ref` as null throughout.

`BattleActionSpawnRunTest` plays it with the spawn runs, holding the rider's attachment and release from `unit_spawner`, since the run lists no actions.

## `golden/match_elixir_150s.json` - a Ladder match's clock, elixir and hands

The towers fight at level 11 in a Ladder match between two decks of the same eight cards (Knight, Archer, Giant, MiniPekka, Musketeer, Valkyrie, Barbarians, Minions), both players' words 0. Side 0's shuffle draws 270369 from the battle's source and deals Valkyrie, Giant, Archer, Minions, then Barbarians, MiniPekka, Knight, Musketeer; side 1's draws 67601921 and deals Knight, Musketeer, Archer, MiniPekka, then Minions, Giant, Barbarians, Valkyrie (`match.log`, the `hand` entries). Both start with 6 elixir.

Eleven plays run on fixed ticks (`commands`). Side 0's Knight on 20 is refused with code 9: it waits in the queue. Side 1's Knight on 25 is placed and pays 3; its slot takes Minions on the same tick, the cooldown having run out. Side 0's Valkyrie on 300 is refused with 0xd, with 3.34 elixir. `match.trace` holds every tick of the first 3000: both elixirs in ten-thousandths, both hands as deck indices, both cooldowns, the timeline's time, section and rate, both crowns, and the end's four fields at a match that goes on. Between plays each tick adds 178, and 357 from 2400; side 1 takes a princess tower. The units the plays make are held as the other card runs hold theirs.

`BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/match_knights_king.json` - a match to its end

A Ladder match at level 11 between two decks of eight Knights, both players' words 0. A Knight each on tick 20 meet at the left bridge and both die on 324; side 0 plays waves of three Knights on 340..342 and side 1 on 900..902 (`commands`). PrincessTower_1_1 falls on 700 and KingTower_1_0 on 1220: the match ends there, side 0 the winner with crowns 3 : 0 (`match.end`). From the update of 1221 a circle grows from the fallen king, 800 an update, and takes PrincessTower_1_2 on 1229 at a radius of 7200 (`match.log`, `circle_kill`). The entities are ticked while the end timer runs from 51 to 3951; the update of 1300 takes it to 4001 and only cleans the holder up (holder tick 0 in the trace), and from 1301 the battle runs no step (`stopped_at`). `match.trace` holds every step, the end's fields included.

`BattleActionSpawnRunTest` plays it with the spawn runs, and holds the circle's kills, the end's tick, crowns and winner, and the stop.

## `golden/golemite_death_damage.json` - a death that damages and pushes

The towers fight at level 11. A Golemite, level 11, is the run's unit at (3500, 16000); it attacks buildings only and walks at PrincessTower_1_1. A red Knight (`KnightRed`, in `units`) is placed at (3500, 24000) on tick 140 and kills the Golemite with a direct hit on 169 at (3727, 22649). The Golemite's death damage, 39 at the first level and 99 at level 11, lands inside that hit on everything the shared validator accepts within 2000: PrincessTower_1_1 and the Knight each take 99 (`area_hit` and `area` events, the area's `push` 900 and `pushed` listing the Knight). The Knight is pushed 900 away from the Golemite's point (a `pushback` event) and flies from (3500, 23999) to (3388, 24684) by 175. It then walks on, and the towers kill it on 602.

`BattleActionSpawnRunTest` plays it with the spawn runs.

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

The same layout. The Barbarians of `barbarians_left` destroy PrincessTower_1_1 on tick 363. A second Barbarians card requested at (3500, 16000) on tick 400 is placed at (3499, 16500), on the bridge: with the tower gone, the pocket behind it leaves no column interval to clamp the formation into, and two of its units are created on river cells, at (4745, 16904) and (2253, 16904). They stay on the water through waiting, deploying and their first steps, and walk off it; nothing moves them. The king tower dies on 708 and stays in the holder: the units attacking it drop it through their own targeting and walk on, and the combat gate at the tail of its state visit switches it off on that tick, so it fires no more.

## `golden/bush_goblins.json`, `golden/brawler_goblins.json`, `golden/gift_knight.json`, `golden/abort_instigator.json`, `golden/witch_hooks.json`, `golden/tombstone_death_hook.json`, `golden/gift_select.json` and `golden/goblin_wave.json` - characters an action spawns

The towers fight at level 11. No object the battle has yet runs these rows from its hooks, so each file lists its `action_owners`: an object with a name, id (3000000, the area-effect band), side, position and packed level, and the rows scheduled on it in the command pass of their tick. `actions` lists every schedule, every run of an action with the pending pass it ran in, and every spawn: the child's name, id, where it was created, its state, deploy countdown, lane and hit points, and its position and state after its registration visit. The children's records follow in `unit_records`, with each one's elapsed time (`delay`), deploy countdown (`deploy`), whether it is still untargetable (`immune`), and `pending` on the record of the tick it was spawned in; `records` is empty.

- `bush_goblins`: a group on a bottom-side owner at (3500, 21500), scheduled on tick 0, spawns a Bush Goblin at (3000, 21500) on tick 12 and one at (4000, 21500) on tick 13, both deploying. The second is pushed to (4001, 21500) as it is registered. Each stays untargetable for six ticks, so PrincessTower_1_1 locks on the first on tick 19; they die on 70 and 127.
- `brawler_goblins`: the same on a top-side owner at (14500, 10500), four Goblin Brawlers on ticks 12 to 15 at (15000, 10500), (14000, 10500), (15000, 10000) and (14000, 10000): the side flips both axes of the location. The last two are pushed 150 as they are registered. They destroy PrincessTower_0_2 on 97 and the king tower on 236, which the combat gate switches off as it dies; PrincessTower_0_1 kills one on 262 and the other three destroy it on 426.
- `gift_knight`: a Knight spawned on its owner at (14500, 12000) on tick 5, walking at once: its registration visit takes PrincessTower_1_2 as its target and steps to (14518, 12056). The tower locks on 81, the Knight hits seven times from 195 and dies on 356.
- `abort_instigator`: the `gift_knight` run with three more `SpawnBrawler` schedules on the owner, two of them caused by the Knight (`instigator` on the schedule). The one scheduled on 346 runs on 356 before the Knight's cleanup and spawns a Goblin Brawler; the one the Knight caused on 347 is still waiting when the Knight dies on 356 and is dropped in that cleanup with one tick left (a `dropped` action event, with `ticks_left` and the queue after it); the owner's own, also from 347, runs on 357.
- `witch_hooks`: no action owner. A Witch_crazy_1 is placed directly for the bottom side at (14500, 3000) on tick 0, listed in `units`, and its row's starting action runs in its phase-1 pass of that tick (a `start` action event marks the schedule at placement). Its spawn group runs on 40 in phase 1, before the Witch moves, and from its interval on 180 and 319 in phase 2, after it (`interval` events with the counter after the reload). Each round spawns four Skeleton_EV1 around the Witch's position at that moment, deploying and targetable at once, and links each into the Witch's group right after it (`group_link`, with the group newest first); PrincessTower_1_2 kills four of them and each is unlinked as it leaves (`group_unlink`). The children are named from `Witch_0` as the battle names them; the generator numbered them from `Witch_1`. The Witch's own records carry only its outside: `delay`, `deploy`, `immune` and `pending` are null.
- `tombstone_death_hook`: no action owner. A Wizard (level 11) is placed directly for the top side at (14500, 23000) on tick 0 and a Tombstone_crazy_1 (level 1, 207 hit points) for the bottom side at (14500, 17600) on tick 30, each with its own `level` in `units`. The Wizard locks the Tombstone on 30 and its first projectile kills it on 45 while it is still deploying, so neither its spawner nor its lifetime runs. Its death damage, 500 over 3000 at ground units, lands inside the impact and finds nothing to hit: the Wizard stands outside it (an `area` event with no victims). A `death_hooks` action event lists what the death scheduled (the attacker, the side the kill is credited to, the rows, and whether it was inside a pending pass); the death action runs in the Tombstone's phase-3 pass of 45 with the projectile as its cause and spawns SkeletonKing on the Tombstone's point, immune through 50, and a `champion_handover` event records it handed to its side. The Tombstone leaves in the closing cleanup of 45; the Wizard and PrincessTower_1_2 kill SkeletonKing on 124 after its area hits the Wizard twice, and the run stops on 144. The child is named `Tombstone_0` as the battle names it; the generator named it `Tombstone_2`.
- `gift_select`: two bottom-side owners, `Gift` at (14500, 12000) and `Gift2` at (3500, 12000), each scheduled the gift delivery's select, `Gift_Delivery_Spawner` (an ActionSelect whose condition is `rand(10)` over ten spawn rows), on tick 5. `seed` is the battle's random state at the start, 0x37, and `draws` lists every draw with its tick, owner, bound, value and the state before and after. Each select draws as it is scheduled, in the command pass of 5, Gift first: 8, the Knight, then 6, the MiniPekka (`select` action events give the choice and its draws). Each owner's phase-1 pass of 5 runs the select's own row, which does nothing, then the chosen spawn, queued with no delay. The Knight's record is `gift_knight`'s; the MiniPekka takes the left lane and dies on 267. The children are named `Gift_0` and `Gift2_0` as the battle names them, each source's children counted from 0; the generator named the second `Gift2_1`.
- `goblin_wave`: a run with a unit of its own, a bottom-side Knight at (4000, 10000) whose records follow the kill-run layout, and a top-side Knight, `KnightRed`, at (14000, 22000), both placed on tick 0. The Goblin Hero's second-wave rows `GoblinHero_Spawn_Second_Wave_0..3` are scheduled on each Knight on tick 30 (`unit_schedules`) and run in its phase-1 pass in the swap-with-last order 0, 3, 2, 1, each working out its point on the Knight as it stands before its movement: `x` plus or minus 1000 by `select(x > map_width / 2, -1, 1)` and `y` minus or plus 1000 by `team_y_direction(team_index)`, so the wave stands ahead of and behind its Knight, mirrored for the top side. Each spawns a Goblin_dummy deploying (state 4, 1000 ms) and links it into its Knight's group, newest first. The rows set IgnoreEffects, which only skips the spawn effect. The children are named `Knight_0..3` and `KnightRed_0..3`, as the battle names each source's children; the generator counted on from its earlier objects. The run stops on tick 99.

`BattleActionSpawnRunTest` plays each file through the battle and holds it to every action run, spawn, drop, group link and unlink, death's hooks and champion hand-over, every unit record, every tower's lock, every event and every projectile position.

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
| `knight_centre`      | (9000, 12000)  | PrincessTower_1_2                                  | tick 245    |
| `knight_right_rear`  | (16500, 5000)  | PrincessTower_1_2                                  | tick 326    |
| `knight_behind_king` | (9000, 4600)   | PrincessTower_1_2                                  | tick 369    |
| `knight_left_inner`  | (8000, 10000)  | PrincessTower_1_1                                  | tick 259    |

The default target is chosen among the other side's princess towers only: the king seeds the choice
and is never a candidate itself. So the centre and inner cases, whose closest tower in x would be the
king, take their lane's princess tower from their first walking tick and keep it.

The lane rule of the default selection keeps a unit to the towers of its own lane while its elapsed
time, which starts at zero and grows by one step per state visit outside the deploying states, is
below 500 ms: its first ten walking ticks. With both princess towers standing, a unit in its own
half always finds its lane's tower the closer in x, so no case here shows the rule.

The last two cases deploy the unit beside one of its own towers - behind the right princess tower
and behind the king tower - so that the trajectory runs through the part of the arena where a
building is a neighbour. `knight_behind_king` is the sharpest of the six: the unit starts inside
its own king tower's collision circle, and a change to how a building takes part in pushing or
steering sends it down the other lane. Only `knight_left`, `knight_right` and `knight_centre` have a
`movement_replay` file; the two new cases are replayed through the engine only.
