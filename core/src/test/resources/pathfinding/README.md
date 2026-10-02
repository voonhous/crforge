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
- `tower_events` lists what the towers' own targeting did: a `reference` each time a tower's reference changes, with the new one and whether it is in range, a `lock` when a tower starts attacking, and, in the cleanup that removes an entity, the `reference` it left each tower holding with the target-lost countdown it started (`target_lost_timer`), a `reference_dropped` for a tower that had locked on, and the `removed` entity itself. A reference the combat gate drops as a tower dies is not a change its targeting made and is not listed. The level-1 run adds the steps of the king's activation: `activation_condition` (with whether the king was `damaged` and whether its side had a `tower_destroyed`), `activating_started` and `activation_effect` with the pending pass (`phase`) they started in, `activating_finished` and `activating_removed`. Every tower holds the opposing tower its default selection gives from the first tick; once the unit has gone, the towers that were shooting it take it again on the next tick: a tower keeps a target its own shot in flight will kill, and that target's removal starts no countdown (`target_lost_timer` 0).
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

The movement visit of 70 already walks at 60. Three more arrows kill it on 110, at (3740, 22930). Its new row spawns two ElixirGolem4 as it dies, inside the arrow's impact: on its death spawn ring of 750 at angles 180 and 0, (2990, 22930) and (4490, 22930), each 360 hit points, walking at once, registered in the impact's post-hook pass and moved to (2904, 22902) and (4540, 23005) by their registration visits (`spawn` action events with the phase `death`). They are untargetable through 115 and walk at PrincessTower_1_1, which kills them on 184 and 248; the run ends when every unit is dead. The header's `damage` is the generator's reading of the unit's row at the end of the run, after the swap; `card` is the row placed.

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
- `tombstone_life`: a Tombstone at (5500, 11000) with no enemy in reach, for its whole life. Its spawner fires from the end of its deploy: a Skeleton on 19 and 28, then two every 80 ticks, on 98 and 108 up to 578 and 588, each in front of it at (5500, 12500), walking at once (state 1), with no first-tick immunity. Its decay, 88 hundredths a visit, takes its last hit point on 621, in the hit-points pass; its death slot spawns four Skeletons on the same in-front point, and it leaves at that tick's closing cleanup. It has no death damage. The towers kill every Skeleton, the last on 729.
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

- `minions_left`: a Minions card played at (3500, 10000) for the bottom side on tick 0, placed at (3500, 10500): an air card takes no symmetric snap. Its three Minions fly at 1500, one deploying and two waiting. PrincessTower_1_1 locks each in turn and kills them on 135, 183 and 231; two lock the tower and spit from 1950. It is a placement file, played by `BattlePlacementRunTest`.
- `minion_musketeer`: a top-side Minion from (3500, 21000) meets a bottom-side Musketeer at (3500, 9000) and Knight at (4500, 9000) (in `units`). The Musketeer takes the Minion on 54 and kills it on 92 with shots that climb to it; the Knight, which does not attack air, only ever references PrincessTower_1_1. The Minion locks the Knight on 78 and spits on 87.
- `balloon_tower`: a Balloon locks PrincessTower_1_1 on 256 at (3298, 23927) and hits it for 640 four times. It dies on 389, and its death spawn, BalloonBomb, stands on its point on the ground (height 0, deploying 3000 ms) and dies as its deploy ends on 449, dealing 240 to the tower.
- `balloons_cross`: with the towers passive, a bottom-side Balloon from (3500, 12000) and a top-side one from (3500, 20000) cross over a top-side Knight from (3500, 17500). They meet on 78 and push each other apart, the Knight moves neither and neither moves it, and neither Balloon references the other. A run without the towers fighting records no hit points of its own unit.
- `balloon_river`: a level-1 Balloon from (2000, 14500) meets four level-11 top-side Musketeers at (1000..4000, 21500) and dies on 42 at (2141, 15857), over the river. Its BalloonBomb stands one unit to the right, at (2142, 15857), on the water - every in-front try is water - and dies as its deploy ends on 102, dealing 94 to each Musketeer. Once the Balloon is gone the Musketeers take PrincessTower_0_1 on 48: the bomb, a building placed during the battle, is no default target.
- `lava_hound_river`: a level-1 Lava Hound from (2000, 14500) meets four level-11 top-side Musketeers at (1000..4000, 21500) (each unit with its own `level`) and dies on 58 at (1961, 16216), over the river. Its six LavaPups, at 3500, are made on its point and fly back to their ring points over the water, 250 a step, and die on 81, 93, 103 and 108.
- `baby_dragon_left`: a Baby Dragon locks PrincessTower_1_1 on 138 at (3288, 20521) and fires from 5400 on 143 and every 30 ticks, 161 on the tower, before it dies on 277.

`BattleActionSpawnRunTest` plays all but `minions_left` with the spawn runs.

## `golden/fireball_knight_tower.json`, `golden/zap_knight_cast.json` and `golden/goblin_barrel_tower.json` - spells played by a command

The towers fight at level 11, and every spell is played by a place-card command (in `commands`, with the requested `point`, the `placed` point, the outcome `cast` and what the cast `made`). A spell summons nothing: its point is clamped and snapped to the tile centre, and in the command pass of its tick its area effect is created at the placed point, or its projectile starts from its side's king tower at (9000, 3000) or (9000, 29000), 4200 up, with no target. Either first acts in that tick's post-hooks, at the card's level (10 for level 11). A cast projectile has no `launch` event; its positions are in `projectiles`.

- `fireball_knight_tower`: the Knight of `tower_vs_knight_left`, and a top-side Knight, `KnightRed`, placed at (3500, 24500) on 150 below PrincessTower_1_1 (in `units`; the generator did not list it, and the file takes it from the run's definition). A Fireball played for the bottom side at (3500, 24000) on 150 lands at (3500, 24500). It flies 600 a visit from the blue king and lands on 187: 207 on the tower (its crown-tower percent of -70), 688 on KnightRed, which is pushed 1000 away from the impact point (a `pushback` event).
- `zap_knight_cast`: `zap_knight` with Zap played by the top side's command on 254 at (3731, 22854), snapped to (3500, 22500), where the area effect is created (`how` "cast", its source the play). Everything after is `zap_knight`'s.
- `goblin_barrel_tower`: a Goblin Barrel played for the bottom side at (3500, 23500) on 20 is searched for its Goblin, so it lands at (3499, 23500), with the symmetric adjustment. It lands on 73 and makes three Goblins in formation around the point, at (3499, 24077), (3000, 23212) and (3998, 23212), deploying for 1100 ms (`spawn` actions with the projectile as owner). Each registration visit pushes a Goblin by a unit, and the relocation off the water that follows puts it back on its created point. They deploy until 95 and attack PrincessTower_1_1, which kills them on 107, 140 and 172.

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
- `guards_knight`: Guards (SkeletonWarriors) played for the bottom side at (3500, 10000) on tick 0, and a top-side Knight at (3500, 16000) on tick 0 (in `units`; the generator did not list it, and the file takes it from the run's definition). The Knight's 202 leaves Guards_0's shield at 54 on 50 and breaks it on 74 (148 lost); Guards_0 dies on 98. The Knight dies on 121. The tower's arrows break Guards_1's shield on 199 and kill it on 213, and take Guards_2's shield in three, on 227, 241 and 257, before the fourth arrow kills it.
- `poison_guards`: the Guards without the Knight, and Poison placed for the top side at (3500, 11000) on 20. Its 92 a hit takes each shield to 164 on 44, 72 on 64, and breaks it on 84 (20 lost); the next hit kills two Guards on 104, and the third falls to an arrow on 113.
- `tombstone_crazy_life`: Tombstone_crazy_1 placed for the bottom side at (5500, 11000) on tick 0, its whole life: tombstone_life's schedule, each child a SkeletonWarrior with a 256 shield that the tower's arrows take in three before the fourth kills. The decay kills the Tombstone on 621, and its death action, scheduled on it with itself as the cause (`death_hooks`), runs in its phase-2 pass and spawns SkeletonKing on its point, handed over to its side. The run stops on 657, with the king tower still standing.

`BattleActionSpawnRunTest` plays all four with the spawn runs.

## `golden/witch_left_lane.json` and `golden/night_witch.json` - a unit's own spawner

The towers fight at level 11. The run's unit is placed directly for the bottom side at (3500, 10000) on tick 0, at level 11, and walks up the left lane at PrincessTower_1_1. Its spawner runs in its state visit from the end of its deploy on 19, whether it walks or attacks: each visit takes 50 ms off its timer, which starts at its row's 1000, so the first wave comes on 38 and the next every 140 or 100 ticks. The children stand on the ring of its spawn radius around where it stands as the wave fires, the ring untested for passability, walking at once (state 1) in lane 1, with no first-tick immunity, so the towers may lock on them at once. `building_log` lists each firing (`spawner`, as for a building) with the timer after it; `actions` lists each child (`spawn`, phase `live`, or `death` for a death spawn).

- `witch_left_lane`: a Witch. Four Skeletons on 38, at (3651, 9112), (1651, 11112), (3651, 13112) and (5651, 11112) while it walks; four more on 178, centred on (3716, 18552), while it attacks the tower. The towers kill it on 263, before its third wave, and the tower then kills its Skeletons one by one, the last on 334.
- `night_witch`: a Night Witch (DarkWitch). Two Bats a wave, on 38, 138 and 238, the ring of 1500 turned by its row's angle shift of 90 plus the angle it faces, so each pair stands at its sides: on 38, facing (22, 255), a heading of 85, at (5145, 10982) and (2157, 11242); later, facing straight up, level with it. It dies on 278, before a fourth wave, and its death spawn's one Bat stands at (3231, 22434), 500 from it on a ring turned the same way. The towers kill the Bats one by one, the last on 341.

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

## `golden/bandit_knight.json` - the dash

The towers fight at level 11. The run places its dasher for the bottom side on tick 0 and red Knights ahead of it, and lists, under `jump_charge_dash`, every dash started - its reference, where it stood, the point it aimed at, its route, its stop-in-range byte, its dash time and its wind-up - every state its movement pass asked for, and every landing with its hit, its landing hold and its state.

- `bandit_knight`: a Bandit at (3500, 10000) and a Knight at (3500, 18000). The Knight is inside the Bandit's ring from tick 30; the wind-up of 800 holds the Bandit still from 31, and on 46 it dashes from (3665, 10965) toward the Knight at (3687, 16486), its route the single node 1087, short of the Knight by both radii. It flies 500 a visit and, after the second step of 53, at (3727, 14465), finds the Knight in range: it lands 389 on it, walks on at once, and its reset attack hits for 194 on 72, 92 and every 20 ticks. The Knight first hits it on 62; it dies on 158, and the reference its death drops asks for a resume. The reference was regenerated after its generator was corrected to test the range after each step at the position that step left the Bandit, as the game does; it first stopped the Bandit a tick later.

`BattleActionSpawnRunTest` plays `bandit_knight` with the spawn runs. A Mega Knight run, `mega_knight_group`, was withdrawn with its reference: its push as it deploys (SpawnPushback 1000 over SpawnPushbackRadius 1000) was never composed, and the battle refuses the Mega Knight. `BattleDashTest` holds the Mega Knight's dash, landing and hold with those columns taken off its row.

## `golden/ram_rider_tower.json` - the Ram Rider

The towers fight at level 11. A Ram Rider is played for the bottom side at (3500, 10000) on tick 0 (`commands`) and placed at (3499, 10500). The play sets the Ram deploying, which makes its one rider first, id 5000006 before the Ram's 5000007, on the Ram's point (its spawn radius is 0), deploying for 1000 ms; `unit_spawner` lists it attached and, on 350, let go. The Ram charges on 61 at 10080, crosses by the bridge without a jump, and hits PrincessTower_1_1 for its charged 501 on 147, then 250 every 34 ticks, until it dies on 350; the rider, which targets troops only, takes no target all run, and is let go and removed in the cleanup of the Ram's death tick. The rider's records list its `ref` as null throughout.

`BattleActionSpawnRunTest` plays it with the spawn runs, holding the rider's attachment and release from `unit_spawner`, since the run lists no actions.

## `golden/match_elixir_150s.json` - a Ladder match's clock, elixir and hands

The towers fight at level 11 in a Ladder match between two decks of the same eight cards (Knight, Archer, Giant, MiniPekka, Musketeer, Valkyrie, Barbarians, Minions), both players' words 0. Side 0's shuffle draws 270369 from the battle's source and deals Valkyrie, Giant, Archer, Minions, then Barbarians, MiniPekka, Knight, Musketeer; side 1's draws 67601921 and deals Knight, Musketeer, Archer, MiniPekka, then Minions, Giant, Barbarians, Valkyrie (`match.log`, the `hand` entries). Both start with 6 elixir.

Eleven plays run on fixed ticks (`commands`). Side 0's Knight on 20 is refused with code 9: it waits in the queue. Side 1's Knight on 25 is placed and pays 3; its slot takes Minions on the same tick, the cooldown having run out. Side 0's Valkyrie on 300 is refused with 0xd, with 3.34 elixir. `match.trace` holds every tick of the first 3000: both elixirs in ten-thousandths, both hands as deck indices, both cooldowns, the timeline's time, section and rate, both crowns, and the end's four fields at a match that goes on. Between plays each tick adds 178, and 357 from 2400; side 1 takes a princess tower. The units the plays make are held as the other card runs hold theirs.

`BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/match_knights_king.json` - a match to its end

A Ladder match at level 11 between two decks of eight Knights, both players' words 0. A Knight each on tick 20 meet at the left bridge and both die on 324; side 0 plays waves of three Knights on 340..342 and side 1 on 900..902 (`commands`). PrincessTower_1_1 falls on 700 and KingTower_1_0 on 1225: the match ends there, side 0 the winner with crowns 3 : 0 (`match.end`). From the update of 1226 a circle grows from the fallen king, 800 an update, and takes PrincessTower_1_2 on 1234 at a radius of 7200 (`match.log`, `circle_kill`). The entities are ticked while the end timer runs from 51 to 3951, with every attack timer held at zero and every ordinary hit refused, so nothing attacks; the update of 1305 takes it to 4001 and only cleans the holder up (holder tick 0 in the trace), and from 1306 the battle runs no step (`stopped_at`). `match.trace` holds every step, the end's fields included.

`BattleActionSpawnRunTest` plays it with the spawn runs, and holds the circle's kills, the end's tick, crowns and winner, and the stop.

## `golden/match_overtime_tiebreak.json` - a tiebreaker decided by a fallen tower

A Ladder match at level 11, side 0's deck eight Knights and side 1's eight Archers, both players' words 0. A Knight and two Archers are played on tick 20 (`commands`); they damage PrincessTower_1_1 and PrincessTower_0_2 by unequal amounts and all three die by 416. The crowns stay 0 : 0: overtime from 3600, and the time is up on 6000 with equal crowns, so the tiebreaker replaces the step from 6001. Its clearing on 6001..6030 finds nothing and runs no update; nothing runs until the step that begins at 3250 ms, 6066, when the drain starts: every tower of both sides takes 50 a step, then 40, 20, 10 and 1 as the lowest tower falls through the bands (`match.log`, `drain`, with the hit points after each step). PrincessTower_1_1, the lower, falls on 6140; the holder is cleaned up, and on 6141 the match ends, side 0 the winner with crowns 1 : 0 (`match.end`). The battle stops on 6221. `match.trace` holds every twentieth step and every step without an entity tick, the tiebreaker's time included.

## `golden/match_overtime_draw.json` - a tiebreaker drawn

A Ladder match at level 11 between two decks of eight Knights with no play. Every tower is whole when the time is up on 6000; the tiebreaker's first drain step on 6066 leaves both sides' lowest towers equal, and on 6067 the match ends a draw (winner -1, crowns 0 : 0). The battle stops on 6147.

`BattleActionSpawnRunTest` plays both with the spawn runs, and holds every drain step, the trace's rows, the end's tick, crowns and winner, and the stop.

## `golden/match_elixir_sources.json` - elixir from units

A Ladder match at level 11, side 0's deck eight Elixir Golems and side 1's eight Knights, both players' words 0. Side 0's ElixirCollector is placed directly at (14500, 8000) on tick 10 (`units`). It reaches its 13000 ms while side 0's king is full, so its timer is held and it tries each visit; side 0 plays an Elixir Golem on tick 300 (`commands`), and in that step's post-hooks the king regenerates to 70178 and the collector pays it one elixir, 80178 (`match.log`, `collector_elixir`). Side 1 plays three Knights on 400, 420 and 440. They and the princess tower kill the golem on 529, which pays side 1's king 10000 (one elixir), and its two ElixirGolem2 and four ElixirGolem4 children by 674, 5000 each (`death_elixir`). Side 1's Knights take PrincessTower_0_1 on 1024, and side 0's towers kill them on 1233, 1250 and 1407 before they bring the king down, so the match does not end, and the run stops on 1499 with the crowns 0 : 1. `match.trace` holds every step.

`BattleActionSpawnRunTest` plays it with the spawn runs, and holds both kings' elixir on every tick and every payout by tick, unit, side and amount.

## `golden/match_building_cards.json` - building cards played from the hand

A Ladder match at level 11, side 0's deck Cannons, Tombstones and Elixir Collectors and side 1's eight Knights, both players' words 0, run for 2000 ticks. A building card is played as a troop card is (`commands`): the map check, the placement search, the column and the construction, its one unit a building. Side 0's Cannon requested at (3500, 10000) on tick 20 is placed at (3500, 10500), its even footprint snapped to the tile corner. The Tombstone requested at the same point on 200 finds the Cannon's tiles taken and is moved to (6500, 9500). A Cannon requested across the river at (14500, 20000) on 390 is pulled back to (14500, 13500). The Elixir Collector requested at (9000, 5000) on 520 lands clear of the king tower at (8500, 6500). Each deploys at once and lives as the same building placed directly does: the Tombstone sends a Skeleton twice each period, 219 to 788 (`building_log`, `spawner`), and the Cannons and the Tombstone decay on 346, 1011 and 821, the Tombstone dropping its death Skeletons. The collector pays its king one elixir on 798, 52222 to 62222 (`match.log`, `collector_elixir`). Side 1's Knights come down the left lane; on 825 one lands a hit whose target, the Skeleton tomb_0_14, was killed during its windup on 819, and the hit lands on nothing. PrincessTower_0_1 falls on 1318, and the match goes on. `match.trace` holds every step.

`BattleActionSpawnRunTest` plays it with the spawn runs, and holds every play's placed point and units, both kings' elixir on every tick, every spawner firing and decay, and the collector's payout.

## `golden/mirror_knight.json` and `golden/mirror_fireball.json` - the Mirror

A Ladder match at level 11, side 1's deck eight Knights, both players' words 0. A Mirror plays its side's last card again: the king's copy of the last card that was not a Mirror, one level above the Mirror's, for the Mirror's 1 plus the card's cost. The gates read the Mirror's own hand slot and that cost; the card is placed or cast as itself, the play takes the cost, and the Mirror goes to the back of the queue. The last card stays the one repeated. The shuffle keeps the Mirror out of the opening hand; it enters with the refill after the first play.

- `mirror_knight`: side 0's deck Archer, Giant, Knight, MiniPekka, Musketeer, Valkyrie, Mirror and Minions. The Knight on 20 at (3500, 10000) takes 3 (63560 to 33560), and the Mirror enters the hand in that step's refill. The Mirror on 100 at (14500, 10000) repeats the Knight at level 12 for 4 (47800 to 7800): m_0 has 1938 hit points to the first Knight's 1766 and hits PrincessTower_1_2 for 221. The run stops on 400, before the first death.
- `mirror_fireball`: side 0's deck Fireball, Archer, Giant, MiniPekka, Musketeer, Valkyrie, Mirror and Minions. The Fireball on 20 at PrincessTower_1_1 takes 4 and deals the tower 207. The Mirror on 130 is refused with 0xd: 4 whole elixir for a cost of 5, and nothing moves. The Mirror on 200 casts the Fireball again at level 12 for 5 (55600 to 5600), 227 to the tower.

The `mirror` list holds every item a Mirror play carried (the source, the card it repeats, the Mirror's deck index, its level field and the item's, the cost), every Mirror play the gates refused (the code, the cost, the elixir) and every Mirror play (the level, the cost, the elixir before and after, the last card kept, the hand and the queue). `BattleActionSpawnRunTest` plays both with the spawn runs, and holds every item, the code and cost of a refused one, the level and cost of each play, the last card each side keeps, and both kings' elixir and hands on every tick.

## `golden/merge_maiden_mounted.json` and `golden/merge_maiden_normal.json` - the Merge Maiden

A Ladder match at level 11, both players' words 0. The Merge Maiden is played as one of its options, picked by the player's client from the king's elixir as it gives the play, after the step 21 before the play's run: the mounted maiden from 6 elixir, else the maiden on foot. The play carries the option and the option row's cost, 6 or 3; the gates read the Merge Maiden's hand slot and that cost, the option's row is placed as a troop card, the play takes the cost and the Merge Maiden goes to the back of the queue.

- `merge_maiden_mounted`: side 0's deck eight Merge Maidens, side 1's eight Musketeers. The Merge Maiden on 21 at (3500, 10000) is picked after step 0, with 60178: the mounted maiden for 6 (63738 to 3738). M_0 flies with 1177 hit points, kills the Musketeer played on 21 at (3500, 22000) on 172, flies on to PrincessTower_1_1 and dies to its arrows on 272.
- `merge_maiden_normal`: side 0's deck Zap and Merge Maiden four times over, side 1's eight Knights. The Zap on 21 at the river's middle takes 2 (63738 to 43738). The Merge Maiden on 42 at (3500, 10000) is picked after step 21, with 43916: the maiden on foot for 3 (47476 to 17476), and the hand's slot 0 takes index 3 in its place. M_0 walks with 1152 hit points and kills the Knight played on 42 at (3500, 22000) on 243; it dies on 291.

The `variant_picks` list holds each item a variant play carried: the step it was picked after, the elixir and the free hundredths it read, the full bar, each option's test, and the option picked with its index and cost. `BattleActionSpawnRunTest` plays both with the spawn runs, and holds each item - the option, its index, its cost and the deck index the play cycled - and both kings' elixir and hands on every tick. Neither run applies a buff, and the reference writes its buff log only for a run that does: the Zap that finds nobody is held to have applied none.

## `golden/electro_wizard_knights.json` and `golden/ice_wizard_knights.json` - a card that deploys as a spell

The towers fight at level 11. Two side-1 Knights are played at (3000, 17500) and (4000, 17500) on tick 0 and walk down the left lane; on 110 side 0 plays a wizard at (3300, 12100) (`commands`). The card names no unit, so it is cast as a spell: the search snaps the point to the tile centre (3500, 12500), with no unit and so no step left, and the cast creates the card's area effect there in the command pass (`area_effects`, `how` "cast"), at the card's level. The area effect's starting action makes the wizard in its first pending pass of the same tick (`actions`: ElectroWizardZap_3000000_0 or IceWizardCold_3000000_0, state 4, deploy 1000), before the area effect's own hit.

- `electro_wizard_knights`: ElectroWizardZap hits both Knights for 192 and stuns them with ZapFreeze from 110 to 120 (`buffs`). The wizard, 714 hit points, deploys until 130, when both Knights hit it. The run ends on 141, before the wizard's first hit on 142, which reaches two targets and stuns them.
- `ice_wizard_knights`: IceWizardCold hits both Knights for 84 and slows them with IceWizardCold for 1000 ms. The wizard, 688 hit points, is hit by both Knights on 125. The run ends on 139, before its first shot, whose impact slows its target.

`BattleActionSpawnRunTest` plays both with the spawn runs.

## `golden/royal_giant_tower.json` and `golden/elite_archer_knight.json` - a projectile at a constant height

The towers fight at level 11, and the unit is played by a command on tick 0.

- `royal_giant_tower`: a Royal Giant played for side 0 at (3500, 10000) walks to PrincessTower_1_1 and shoots it from 248. RoyalGiantProjectile (homing, ConstantHeight 1500) starts 1200 ahead of the Giant at z 1500, not at the Giant's height plus its launch height, and aims at 1500 too. Each step the homing re-aim pins the aim height onto the tower's, 0, so the arc descends 300 a step: 1500, 1200, 900, 600, 300 on 249..253 (`projectiles`). On 254 it arrives, placed at 1500 over its aim, and deals 307. The constant height moves neither the arrival nor the hit. The tower falls on 578, and the king's arrows kill the Giant on 730.
- `elite_archer_knight`: an Elite Archer for side 0 at (3500, 12000) against a Knight for side 1 at (3500, 20000). EliteArcherArrow flies to a point (a body of 250, ProjectileRange 11000, no homing), so its aim is its range from the Archer and its target is forgotten; ConstantHeight 2000 replaces its start height, and it keeps z 2000 on every step. It hits the Knight as its body passes, 143 each (on 39, 60, 80, 101, ...), and flies on. HomingTime 100 with HomingMinDistance 5000: an arrow launched more than 5000 from the Knight re-aims from the Archer at the Knight on its first two steps, so the first two bend toward it (x 3468 and 3449 on 34 and 35) and the later ones, launched nearer, fly straight. The Archer dies on 173 and the Knight on 239.

`BattleActionSpawnRunTest` plays both with the spawn runs.

## `golden/snowball_knights.json` - a projectile's target buff on its circle

The towers fight at level 11. Two side-1 Knights are played at (3500, 22000) and (4500, 22000) on tick 0, and side 0 casts a Snowball at (3500, 17000) on 60, placed at (3500, 17500) (`commands`). SnowballSpell (Radius 2500, Pushback 1800, TargetBuff IceWizardSlowDown, BuffTime 3000) arrives on 79. Its area impact damages both Knights for 179 and pushes them, and then, after the damage, its target buff reaches the same circle: both Knights take IceWizardSlowDown for 3000 ms, with the projectile as the source, at its level (`buffs`: the `target_buff` event lists what the circle reached, then each instance applied). The slow cuts speed, hit speed and spawn speed by 30 until the instances are removed on 139.

`BattleActionSpawnRunTest` plays it with the spawn runs and holds its buff log. The run lists both Knights' damage before either push; the battle pushes each right after its damage, and the test lists a projectile's pushes after all of its impacts of the tick.

## `golden/witch_mother_skeletons.json` - a curse before the damage, and what it leaves at a death

The towers fight at level 11. A Witch Mother is played for side 0 at (3500, 12000) and Skeletons for side 1 at (3500, 19000), both on tick 0. VoodooProjectile (homing, no radius, ConstantHeight 1000, TargetBuff VoodooCurse, BuffTime 5000, ApplyBuffBeforeDamage) applies the curse to its one target before the damage. Each Skeleton (81 hit points) is killed by the 133 that follows on 31, 52 and 96, and dies carrying the curse (`buffs`: `applied`, then `target_buff`). As its death slot runs, the curse's death spawn makes one VoodooHog in front of it, toward its enemy (`death_spawn`): for side 0, the opponent of the Skeleton's side, at the curse's level, deploying for its DeployTime of 200 and facing the way the Skeleton faced. The hogs are k0_0_0, k0_1_0 and k0_2_0, numbered after the Skeleton that died; each walks four ticks later toward PrincessTower_1_1. The curse on PrincessTower_1_1 from 183 is reached (`target_buff` names it) and refused by the buff, which ignores buildings. The Witch Mother's own BuffOnDamage is never applied: her hit is her projectile's.

`BattleActionSpawnRunTest` plays it with the spawn runs and holds its buff log, the hogs' creation in it, and every unit's records.

## `golden/electro_dragon_knights.json` - a chained hop

The towers fight at level 11. An Electro Dragon is played for side 0 at (3500, 12000) and three Knights for side 1 at (3500, 19000), (4500, 19000) and (2500, 19500), all on tick 0. ElectroDragonProjectile (homing, Speed 2000, ChainedHitRadius 4000, ChainedHitCount 3, TargetBuff ZapFreeze for 500 ms) counts its launch and lists its target. Its impact deals 192 and freezes the target after the damage, then hops: to the nearest Knight strictly inside 4000 of where it landed that it has not hit, launched again from there, waiting 150 ms. The visits of the next three ticks end on the delay, and it lands on the fourth: on k0_0 on 56, k2_0 on 60 and k1_0 on 64. After the third impact its count is 3 and it stays released. Each freeze lasts ten visits (`buffs`: removed on 66, 70, 74). The Knights die on 224, 310 and 351, the Dragon on 596.

`BattleActionSpawnRunTest` plays it with the spawn runs; every projectile position holds the hops.

## `golden/firecracker_knight.json` - a spawned fan

The towers fight at level 11. A Firecracker is played for side 0 at (3500, 12000) and a Knight for side 1 at (3500, 20000), both on tick 0. FirecrackerProjectile (no radius, no damage, SpawnProjectile FirecrackerExplosion) is launched on 39 from z 2500 and lands on 55. Its impact launches max(SpawnCount, 1) = 5 FirecrackerExplosion from where it landed at the height it aimed at, each aimed beyond its aim along the line it came, turned by the explosion row's SpawnRadius 80 times its step (-2 to 2) over 5: -32, -16, 0, 16 and 32 degrees. Each flies to a point (a body of 400, ProjectileRange 5000, MinDistance 5000) at ConstantHeight 1000 and runs its first pass at once, widened by its ProjectileStartExtraRadius 650, so all five hit the Knight on 55 for 64 each. They fly on and hit what they pass; two reach PrincessTower_1_1 on 64. Later shells burst on 107 and 162, each fan hitting the Knight as it is launched. The Firecracker dies on 170, and the last fan's explosions fly on after it. The Knight dies on 238.

`BattleActionSpawnRunTest` plays it with the spawn runs; every projectile position holds the fan.

## `golden/axe_man_knights.json` - a pingpong sweep

The towers fight at level 11. An Axe Man is played for side 0 at (3500, 12000) and two Knights for side 1 at (3500, 20000) and (3500, 21500), all on tick 0. AxeManProjectile (PingpongVisualTime 1500, a body of 1000, ProjectileRange 7500, MinDistance 4500, ConstantHeight 3000) is launched on 50 at z 3000 and holds the Axe Man's targeting, whose visit returns before its reference check while the axe is out. The axe's time moves on by 50 a step, and it stands at its start plus the sine of 180 degrees times the time gone over 1500 of the way to its aim. Its body hits each Knight once on the way out, k0_0 on 55 and k1_0 on 57, for 179 each. On 65, the step that crosses 750, it does not move and forgets both, so it hits them again on the way back, k1_0 on 73 and k0_0 on 75. On 81, the step after its time is up, it lands back at its start, lets the Axe Man's targeting go on and impacts, which hits nothing at its aim. The next throws are launched on 99 and 148, each a hit speed of 900 after the last return. The Axe Man dies on 177 with its third axe still out, which flies back without it; the Knights die on 238 and 344, to the princess tower.

`BattleActionSpawnRunTest` plays it with the spawn runs; every impact and projectile position holds the sweep. Each impact names the projectile where it stood for the hit, before that step's move.

## `golden/hunter_point_blank.json` - a volley of pellets at point blank

The towers fight at level 11. A Hunter is played for side 0 at (3500, 14000) and two Knights for side 1 at (3500, 17500) and (4500, 17500), all on tick 0. The Hunter (MultipleProjectiles 10, AreaDamageRadius 70, CustomFirstProjectile HunterProjectile) fires ten HunterProjectile pellets (Line scatter, CheckCollisions, a body of 300, ProjectileRange 6500, ProjectileStartExtraRadius 650, RandomDelay 200, ConstantHeight 1000) on 33 and 77. Each pellet after the first draws the battle's random source below 53, the spread less its quarter (`draws`, `scatter`); the line scatter throws that point away and fans the pellet about the line to the Knight, alternately to one side and the other, 7 degrees a step. Each launch draws the pellet's delay below 200 (`RandomDelay`), so the draws alternate, nineteen a volley. As each pellet is registered its first pass runs at once, widened to 950, whatever its delay: all ten hit k0_0 on 33, 84 each, and each is finished by that hit, so k1_0 takes none. The second volley does the same on 77. The Hunter dies on 90; the Knights die on 393 and 409.

`BattleActionSpawnRunTest` plays it with the spawn runs and holds the random state after each volley's draws.

## `golden/hunter_range.json` - pellets that stop at the first Knight they hit

The towers fight at level 11. A Hunter is played for side 0 at (3500, 12000) and two Knights for side 1 at (3000, 17500) and (4000, 18500), all on tick 0. The first volley, on 33, draws as in `hunter_point_blank`. Its pellets wait their delays, fly at Speed 550 and hit what they pass: k0_0 on 39, 40, 40 and 41, and k1_0 on 41. Each pellet stops at its first hit; the rest fly out to their range and are released without an impact. The volley on 77 hits k0_0 ten times as the pellets are launched. On 121 the volley kills k0_0 with its fourth pellet; the next six still hit it, at 0 hit points, which lands nothing and finishes none of them, so three of them fly on to hit k1_0 on 124 and 127. The Hunter dies on 148 and k1_0 on 354.

`BattleActionSpawnRunTest` plays it with the spawn runs; every impact and projectile position holds the volleys.

## `golden/ram_rider_bola.json` - the rider's bola

The towers fight at level 11. A Ram Rider is played for side 0 at (3500, 10000) and two Knights for side 1 at (3500, 20000) and (4500, 20000), all on tick 0. The rider, which targets troops only, takes k0_0 and throws RamRiderBola (homing, ConstantHeight 3000, TargetBuff BolaSnare for 2000 ms, PingpongMovingShooter 200) on 57, then every 22 ticks to 167, at z 3000. Each bola deals 104 and then snares k0_0: the snare is applied on 65 and refreshed by each later bola while it holds, on 83, 102, 125, 151 and 174, each refresh naming the bola that made it (`buffs`: `refreshed`, the remaining time before and after). The Ram dies on 175 and the rider goes with it; the snare runs out and is removed on 214. PingpongMovingShooter is never read. The rider ranks a snared troop lower, but it never has to choose between a snared and an unsnared Knight.

`BattleActionSpawnRunTest` plays it with the spawn runs and holds its buff log.

## `golden/kamikaze_battle_ram.json`, `kamikaze_fire_spirits.json`, `kamikaze_wall_breakers.json`, `kamikaze_ice_spirits.json` - a hit that kills its unit

The towers fight at level 11 in each. A Kamikaze row's hit ends in the unit killing itself: its whole hit points as one kill (`kamikaze_kill`, the hit points it had and 0), with itself as the attacker on its own side, in the pass of the hit, whether the hit was direct or a launch. It leaves at the closing cleanup of that tick.

- `kamikaze_battle_ram`: the Battle Ram charges on 69, hits PrincessTower_1_1 for its charged 573 on 152 and kills itself for 640 in the same visit. Its death slot makes its two Barbarians in that pass; they die on 272 and 386.
- `kamikaze_fire_spirits`: a Fire Spirit launches at k0_0 from (3283, 13032) and kills itself on 76; the projectile flies on without it and lands on 83 on both Knights for 207.
- `kamikaze_wall_breakers`: a Wall Breaker's row fires a projectile that aims one unit ahead and lands on the next tick on its own spot. wb_0 explodes on 130 and the tower and the Knight beside it take 350 on 131; wb_1 explodes on 141 and the tower takes 350 on 142.
- `kamikaze_ice_spirits`: an Ice Spirit launches and kills itself on 75; its projectile lands on 82 on three Knights for 110 and freezes them for 1100 ms with the projectile as the source, removed on 104.

`BattleActionSpawnRunTest` plays them with the spawn runs; the events list every self-kill with its death, and the buff log holds the Ice Spirit's freeze.

## `golden/moving_cannon_left.json` - a card play's starting action and a swap into a building

The towers fight at level 11. A Moving Cannon is played for side 0 at (3500, 10000) and placed at (3499, 10500). The play starts it: its OnStartingAction, MovingCannon_trigger_at_health (a run-action-at-health over three rows at 50 per cent), runs in its phase-1 pass of tick 0 (`actions`: `start`, `run`) and steps in every run pass after. It walks up the left lane and fires at PrincessTower_1_1. The tower's arrow of 275 leaves it 828 of 1809; the run pass of 276 finds it at half its hit points and schedules the three rows, which run in its phase-2 pass of 276: the swap into BrokenCannon (`change_data`), then a sound and an effect. The swap keeps its 828 hit points, its maximum 1809, its state and its target, frees its movement component and starts its drain over BrokenCannon's LifeTime of 30000, 301 hundredths a visit. It fires on every 18 ticks, the next shot from BrokenCannon's lower start, 12 hits of 212 on the tower in all, and dies of the drain on 371 (`building_log`: `decay_death`).

`BattleActionSpawnRunTest` plays it with the spawn runs, holding its action runs and its building log.

## `golden/furnace_left.json` - a walking unit's interval that spawns

The towers fight at level 11. A Furnace (card FirespiritHut, unit Furnace_rework: a walking troop with its own projectile) is played for side 0 at (3500, 10000) and placed at (3499, 10500). The play starts its interval, Furnace_rework_continuous_spawn, in its phase-1 pass of tick 0; it fires on 38 and every 140 steps after while the Furnace lives. Each firing spawns one Fire Spirit 1500 ahead of where the Furnace stands, deploying for 500 ms with the first-tick immunity, and the spawn row's action runs on the spirit at once (`run Furnace_0_0 Furnace_rework_spawning_effect`). The spirits, Furnace_0_0 on 38 and Furnace_0_1 on 178, each launch and kill themselves, on 122 and 210, and their projectiles land. The Furnace shoots on 154, 188 and 222 and dies on 252, before a third firing.

`BattleActionSpawnRunTest` plays it with the spawn runs, holding every run of the Furnace's and the spirits' actions.

## `golden/giant_buffer_knights.json` - a friend collector, a cast and enchanted hits

The towers fight at level 11. Two Knights are played for side 0 on tick 0, placed at (3499, 12500) and (4499, 12500), and a Giant Buffer behind them, placed at (3499, 9500). The play starts its collector, giantbuffer_collect_friend_troops, whose own delay of 1000 ms has it run in its phase-1 pass of tick 20 (`actions`: `start`, then `run`). Its first step finds both Knights within 7000, asks the battle's target locks for each with its squared distance flipped against the largest int as the priority, and requests the Giant Buffer's ability: the gate is open, so it enters the casting state at once, 18 ticks of CastTime 933 with a TriggerDelay of 1, its targeting component switched off with its reference kept. Its effect fires in that tick's state visit and schedules the ability's empty activation action, which runs in the phase-3 pass (`run GiantBuffer_0 giantbuffer_ability_OnActivationAction 3`); the locks grant both Knights in the post-pass. The buff delay of 280 ends on 26 (`run ... OnBuffAction 2`), and on 27 it fires a GiantBuffProjectile at each Knight. It stands on 37 and walks again from 38. Both projectiles land on 34 and schedule their on-hit group on their Knight, the projectile as its cause; in the phase-3 pass the group's enchanting buff starts on each, and its effect select, a tick behind, is dropped as the projectile leaves in that tick's cleanup (`dropped`). The looping effect the select chooses only shows something and is not a run of the reference. Each Knight's third hit on PrincessTower_1_1, on 252, deals 422 - 202 and the 220 of AddedDamage 86 at the Giant Buffer's level - and runs the count's action; KnightA's next third hit kills the tower on 324.

`BattleActionSpawnRunTest` plays it with the spawn runs, holding every action run, the drops, every hit and every position.

## `golden/giant_buffer_musketeer.json` - an enchanted projectile

The same set-up with a Musketeer placed at (3499, 12500) in place of the Knights. The Giant Buffer collects and enchants it as above. The Musketeer's third shot, launched on 166, is registered while its count stands one short, so the projectile takes a copy of the buff primed one short, and the count's action runs; the copy adds the 220 at the impact, so the shot lands 437 on the tower on 173 against 217 for the others. The Musketeer dies on 208, and the locks drop its lock in the next pre-pass.

`BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/miner_princess.json` - a unit that tunnels to its placement

The towers fight at level 11. A Miner is played for side 0 at (3500, 25500), beside the enemy's left princess tower, and placed at (3500, 23500). The play hands it to its tunnel in place of a start: it is moved onto its own king tower at (9000, 3000), aimed at the placed point, and set to the spawn-pathfinding state; its registration visit in the command pass searches its route and takes its first step, to (9144, 3630). It walks at its SpawnPathfindSpeed round the river over the left bridge, hidden: no tower takes it. It surfaces on the point on 33, deploying for 1000 ms, the princess tower locks on it on 34, and it hits the tower for 49 from 62 until it dies on 228.

`BattleActionSpawnRunTest` plays it with the spawn runs, holding every position, state and hit.

## `golden/goblin_drill_princess.json` - a tunnel that morphs into a building

The towers fight at level 11. A Goblin Drill is played for side 0 at (3500, 25500). Its search is for the building its dig morphs into, a 2 x 2 footprint on a tile corner, which places it at (1000, 26000). The dig, GoblinDrillDig, is handed to its tunnel as the Miner is, first step (9094, 3284), and walks at 300. It surfaces on 86 and morphs there into the building GoblinDrill, Drill_0_GoblinDrill: made standing with the dig's share of the hit points (1313), its registration visit takes PrincessTower_1_1 as its target (its lock on 86) and one LifeTime step (1307); then it is set deploying for 1000 ms, and that entry makes GoblinDrillDamage at its point and updates it at once: the tower takes 26, the crown-tower share of its 84. The spent area is admitted at that tick's closing cleanup and leaves at the next opening cleanup, on 87. The tower locks on the building on 87. The building attacks for no damage from 123 every 1100 ms, makes a Goblin on 125 and 185, and falls to the tower on 202, leaving two Goblins; the tower locks on each Goblin in turn and the last dies on 329.

`BattleActionSpawnRunTest` plays it with the spawn runs. The hits the building lands for no damage are listed, as the reference lists every hit it hands the damage entry.

## `golden/electro_wizard_tower_defence.json` and `golden/mini_sparkys_knight.json` - a hit on several targets, and a buff on damage

The towers fight at level 11.

- `electro_wizard_tower_defence`: two side-1 Knights, KnightA placed at (3499, 17499) and KnightB at (4499, 17499), attack PrincessTower_0_1 from 170. On 200 side 0 plays an Electro Wizard behind the tower at (3000, 4000); it is cast at (3500, 4500), where its zap reaches no Knight. The wizard attacks from 232 every 36 ticks, each attack two whole hits of 117 with consecutive hit ids: its target KnightA, then the lookup's pick, KnightB, on 232 and 268. Each hit is followed by ZapFreeze for 500 ms on that Knight, which a later hit refreshes (`buffs`). The tower kills KnightA on 297; from 304 the wizard finds no other target and hits KnightB twice an attack, as its row has AllTargetsHit, until it dies on 412, still taking its ZapFreeze. The wizard then walks to PrincessTower_1_1 and from 599 hits it twice an attack, stunning it nine ticks each time, so the tower's arrows come 25 ticks apart across a stun instead of 16. The wizard dies on 709; the run ends on 716.
- `mini_sparkys_knight`: a side-1 Knight placed at (3499, 17499); Mini Sparkys played for side 0 at (3500, 9000) on 60, placed at (3499, 9500). Each Sparky hits one target, 117, followed by ZapFreeze for 500 ms. The three hit the Knight on 95, 97 and 99, the second and third refreshing the one instance, so the Knight's combat is off from 95 to 107 and it lands only two hits on Sparkys_0 (120 and 157). It dies on 183. Two Sparkys go on to stun PrincessTower_1_1 from 379 and die to the towers, the last on 509.

`BattleActionSpawnRunTest` plays both with the spawn runs, and holds every buff applied, refreshed and removed.

## `golden/inferno_tower_giant_knight.json`, `golden/inferno_dragon_zap.json` and `golden/mighty_miner_knight_tower.json` - continuous damage

The towers fight at level 11. Each attacker's row makes a Hittime sequence from its numbered VariableDamage columns: three windows, the first two 2000 ms each, whose damage the hit takes from the window its attack timer has reached.

- `inferno_tower_giant_knight`: side 0 plays an Inferno Tower at (3500, 10000), placed at (3500, 10500), and side 1 a Giant at (3500, 17500) on 0 and a Knight there on 120. The tower locks on the Giant on 20 and hits it from 27 every 8 ticks: 43 four times, 158 from 59, 847 from 99. The Giant dies on 123 with the tower's ramp in its last window; the ramp is kept, and the target-lost countdown holds the tower's selection until it locks on the Knight, in range, on 129. The first attack step walks the reset timer back to the first window: 43 again from 146, 158 from 178, and 847 on 218 kills the Knight. On 224 the tower takes PrincessTower_1_1 as its default reference, out of range, and its decay kills it on 620.
- `inferno_dragon_zap`: side 0 plays an Inferno Dragon at (3500, 14500). It takes PrincessTower_1_1 on 20 and flies at it, 500 closer than its range while it moves, and hits it from 138: 35 four times, 120 from 170. On 190 side 1's Zap at (3500, 21500) deals it 192, and its stun drops the tower and resets the ramp at the combat gate; the dragon's combat is off from 190 and back on 200, it locks on the tower again on 201, and its hits start over at 35 on 219. The tower's arrows kill it on 236.
- `mighty_miner_knight_tower`: side 0 plays a Mighty Miner at (3500, 13000), placed at (3499, 13500), and side 1 a Knight at (3500, 17500). The Miner locks on the Knight on 20 and stops to attack it on 37, 500 inside its reach; it hits from 44: 40 four times, 204 from 76, 409 from 116, and the Knight dies on 124. It locks on PrincessTower_1_1 on 130, reaches it on 271 and hits it from 278, 40, 204 and 409 again, until the tower falls on 382; it locks on KingTower_1_0 on 388 and dies to the towers on 465. It is played without its ability, whose lane switch the battle refuses.

`BattleActionSpawnRunTest` plays all three with the spawn runs.

## `golden/hog_clip_cannon.json` - a sight clip

The towers fight at level 11. A Hog Rider is placed for side 0 at (3500, 13500), and a side-1 Cannon is placed directly behind it at (3500, 6000). The Cannon is 7,500 down the lane: inside the Hog Rider's sight, whose reach to it is 600 + 9500 = 10,100, but more than 10,100 less its SightClip of 4000 behind it, so the selector skips it. The Hog Rider's first targeting visit, on 20, takes PrincessTower_1_1, 12,000 away within its reach of 12,500; the tower locks on it on 45 and kills it on 304, and no record of the Hog Rider names the Cannon. Side 0's towers kill the Cannon on 112. With the clip of 1000 every unit carried before, the Hog Rider turns back to the Cannon on 20.

`BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/ghost_river_wizard_tower.json` - an invisible hovering unit

The towers fight at level 11. A level-1 Knight is placed directly for side 1 at (9000, 19500) on tick 0, listed in `units` with its own `level`; the reference lists only the units a card play made, so it is added to the fixture by hand, as in `guards_knight`. Side 0 plays a Ghost at (9000, 12500) on tick 0 (placed at (9500, 12500)) and side 1 a Wizard at (9000, 26000) on tick 100. The Ghost takes Invisibility for 100,000 ms as it is created (`buffs`, `applied` on 0) and hovers straight over the centre water in the moving state from 49 to 160. The Knight cannot take it and walks on. The Ghost locks it on 61, and its first hit on 72 sets its countdown to 50, which that tick's state visit counts to 0, taking Invisibility off (`removed` on 72); the Knight hits back from 83, and the Ghost's third hit kills it on 143. With the Wizard as its reference, in sight but out of reach, the countdown its exit from the attacking state loaded runs out, and on 188 the Ghost takes Invisibility again, for 180,000 ms at its level. PrincessTower_1_2, which locked on it on 166, fired on 181 and the Wizard on 183: both land on the invisible Ghost, on 191 and 193. On 189 the tower drops it for PrincessTower_0_2 (a `reference` tower event) and takes it again on 231, after the Ghost's hit on the Wizard on 230 made it visible; the reference logs no second lock, since a lock holds until a visit leaves the tower with no reference. The Ghost dies on 269, and the Wizard to PrincessTower_0_2 on 450.

## `golden/battle_healer_knights.json` - heals as a unit deploys and as it hits

The towers fight at level 11. Knights are played for both sides on tick 0, side 1's at (3500, 17500) and side 0's `Friend` at (3500, 13000), and meet on the left lane from 45, 202 a hit. Side 0 plays a Battle Healer at (3500, 11000) on 30 (placed at (3499, 11500)). Entering the deploying state, before it is queued, it makes BattleHealerSpawnHeal at its point (`area_effects`, `created` with `how` "spawn_area_object"), whose update at once applies BattleHealerSpawnBuff to the friendly Knight, not to the healer; the play tick's opening cleanup admits it with the healer, so it updates again that tick. The buff heals 50 every 250 ms: on 34, 39 and 44 at full hit points and on 49 after the fight began (`buffs`, `heal`, with the hit points before and after and the maximum). The healer hits the enemy Knight on 104, 134 and 164, 148 each. Each hit makes BattleHealerHeal where the healer stands (`how` "area_effect_on_hit"), admitted at that tick's closing cleanup, whose one update the next tick applies BattleHealerAll to the friend and the healer; each heals 25 every 250 ms, four times a hit, the healer nothing while it is whole. The enemy Knight dies on 189; the friend walks on to PrincessTower_1_1 and dies to it on 341, and the healer's hits on the tower on 345 and 375 heal only itself.

## `golden/bush_princess_tower.json` - a unit invisible until the hit that kills it

The towers fight at level 11. Side 0 plays a Suspicious Bush at (3500, 14000) on tick 0 (placed at (3499, 14500)). It takes BushInvisibility for 100,000 ms as it is created (`buffs`, `applied` on 0) and walks up the left lane over the bridge, and no tower takes it. Its row has no range gate, so outside the attacking state its countdown is held by the touch test rather than the attack range; the buff stays listed, so the test's answer changes nothing. Its one hit, on PrincessTower_1_1 on 171, deals 0, and its Kamikaze kills it (a `kamikaze_kill` of 81). Its death makes SuspiciousBush_DummyAEO where it stood, at (3269, 23514) (`area_effects`, `how` "death"), and the state visit of that tick takes BushInvisibility off the dead Bush (`removed`, `why` "not_attacking_hit"). The area effect, admitted at the closing cleanup, never hits: it updates on 172..191 and leaves as its countdown runs out. Its starting action spawns a Bush Goblin 500 to its left on 183 and one 500 to its right on 184, each deploying for 1000 ms. They hit the tower for 256 from 209, and it kills them on 241 and 288.

## `golden/bush_valkyrie_knight.json` - an invisible unit an area's damage kills

The towers fight at level 11. Side 0 plays a Knight at (3000, 13000) and a Suspicious Bush at (4000, 13000) on tick 0 (placed at (3499, 13500) and (4499, 13500)), and side 1 a Valkyrie at (3500, 21000) (placed at (3499, 21499)). The Knight and the Bush walk up the left lane side by side, and the Valkyrie meets the Knight past the bridge. Her swing on 70, centred on herself, leaves the Bush out of its circle. The swing on 100 reaches it while it is invisible, since its row lets an area's damage in, and kills it: 266 against its 81. Its death makes the area effect at (4158, 17817). With no hit of its own that tick, the Bush dies still carrying its buff. The goblins come on 112 and 113, 500 either side of that point. The Valkyrie's swing on 130 hits both; PrincessTower_1_1 kills the first on 147, and the Valkyrie's swing kills the second on 160. The Knight kills the Valkyrie on 222 and dies to the tower on 272.

## `golden/pending_shield_guards.json` - a shield that keeps a tower's target

The towers fight at level 11. Side 0 plays Guards at (3500, 15000) on tick 0 (placed at (3499, 14500)), three SkeletonWarriors of 81 hit points behind a shield of 256, and side 1 a Musketeer at (3500, 23500) (placed at (3499, 23499)). PrincessTower_1_1 locks on Guards_0 on 40 and has not fired when the Musketeer launches at the same Guard on 46: from the cleanup of that tick the shot's 217 is pending on Guards_0, enough to kill it through its hit points, but its shield is up, so the damage is not lethal and the tower, asking to keep its target, keeps Guards_0 rather than turning to Guards_1. The tower fires at it on 55; the Musketeer's shot takes the shield down to 39 on 51 and the tower's arrow breaks it on 66, and the Musketeer's second shot kills the Guard on 70. Each Guard the tower's arrow or the Musketeer kills leaves the tower re-selecting on the next tick: Guards_1 on 71, killed on 96, and Guards_2 on 97, killed on 128. PrincessTower_0_1 kills the Musketeer on 342.

`BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/tesla_giant_passing.json` - a Tesla that hides, rises and hides again

The towers fight at level 11. Side 0 plays a Tesla at (10000, 12000) on tick 0, and side 1 a Giant at (3500, 17500) on tick 60 (placed at (3499, 17499)). The Tesla's deploy ends on 19: its state visit runs the combat gate and its targeting visit right after the resume, so it takes PrincessTower_1_2, its default target, on that tick, and its hide handler starts its counter (HideEffect). The counter climbs 50 a visit and reaches 800, hidden, on 34. The Giant walks down x 3273, past the Tesla, and never takes it. The Tesla takes the Giant on 207, 6,743 away against its reach of 6,750, and rises (AppearEffect, visible at 850); it hits the Giant once, for 220 on 214, and is up (0) on 222. On 234 the Giant is out of its reach: as a building, the Tesla keeps a target with no range extension, so it lets the Giant go for its default target and starts to hide again, hidden on 249. PrincessTower_0_1 falls on 654 and the Giant on 758.

The file's `hiding` list holds the deploy end's targeting visit, with the reference it took and the state it left, and every visit of the hide handler that shows something: the effects it plays, the counter reaching 800 (`hidden`) or leaving it (`visible`), reaching 0 (`up`), and a change of its step (`step`). `BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/tesla_hidden_spells.json` - what reaches a hidden Tesla

The towers fight at level 11. Side 0 plays a Tesla at (10000, 12000) on tick 0; side 1 casts, each at (10000, 12000) and placed at (10500, 12500), a Fireball on 0, another on 10, a Zap on 60 and a Freeze on 100. The first Fireball, cast on the first tick from the king tower, lands on 27 while the Tesla is going down (its counter at 400) and deals 688. The second is in flight when the Tesla is hidden on 34 and lands on 37 for nothing: its area asks for the Tesla and is refused. The Zap on the hidden Tesla neither damages nor stuns it. The Freeze reaches hidden units: its area takes the Tesla, deals 148 and freezes it for 4000 ms, and while the freeze lasts the hide handler's step is 0 and the counter holds at 800; the step is 50 again when the freeze goes on 180.

`BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/earthquake_barbarians_tower.json` - an Earthquake's hits on its own clock

The towers fight at level 11. Side 1 plays Barbarians at (3500, 22000) on tick 0, and side 0 casts an Earthquake at (3500, 23500) on 20 over them and PrincessTower_1_1. The Earthquake's buff sets HitTickFromSource: on every visit an instance's count toward its next hit is set from the area effect's age as its last update left it, so every target is hit when that age is 950 of a second, on 39, 59 and 79, however late its instance was listed. A Barbarian takes 81 a hit and the princess tower 53 (CrownTowerDamagePercent -35). Barbarians_0 walks out of the circle and is still hit on 79, while its instance lasts. The area effect leaves and its instances, their source forgotten, run two more visits without a hit and go on 81.

`BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/earthquake_tesla_overlap.json` - two Earthquakes on a hidden Tesla

The towers fight at level 11. Side 1 plays a Tesla at (3500, 20000) on tick 0 (placed at (3000, 20000)) and a Knight at (5500, 25000) on 30 (placed at (5499, 25499)); side 0 casts an Earthquake at (3500, 19500) on 40 and another at (4500, 19500) on 50. Each reaches hidden units, so each buffs the hidden Tesla. The buff stacks by its source: the Tesla lists one instance of each, and each is hit on its own Earthquake's clock, 283 a hit (BuildingDamagePercent 350), on 59 and 79 for the first and on 69 and 89 for the second, which kills it. A buff's damage over time lands on a hidden Tesla, as the damage entry's hidden test is not on its way. The Knight walks in late: the second Earthquake's instance is listed on 87 and hits on 89, the first's on 91 and hits on 99, and the second's again on 109. Both slows hold the Knight at the one strongest, 30.

`BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/tornado_group_off_lane.json` - a Tornado drags Barbarians off their lane

The towers fight at level 11. Side 1 plays Barbarians at (3500, 22000) on tick 0 (placed at (3499, 22499)), and side 0 casts a Tornado at (7500, 21500) on 25. The Tornado is one area effect at the point, updated as it is cast; its 21 hits, from 25 to 45, each pull every Barbarian in its circle toward its centre before the buff is applied. A pull is 360 percent of a Barbarian's configured speed of 60 a step of about 216 along the way to the centre once each axis is truncated, and waits in the unit's push accumulators for its next movement visit, which adds it to the route step with the 150 cap lifted. The Barbarians are dragged off their lane; their reference, PrincessTower_0_1, and their routes are kept. The buff stacks with the Tornado as its parent, refreshed to 500 by each hit: each Barbarian takes 84 and PrincessTower_1_1 25 on 36, and the tower is not moved. The Tornado leaves at the closing cleanup of 45, its instances with it; the last pull moves the Barbarians on 46, and they walk back toward their lane.

The file's `pulls` list holds every hit's pull: the area effect, its centre, and each unit pulled with the vector to the centre and its push accumulators (x, y, count, the water clamp and the lifted cap) before and after. `BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/tornado_heavy_light_tower.json` - a Tornado over a Giant and a Knight

The towers fight at level 11. Side 1 plays a Giant at (3000, 22500) and a Knight at (5500, 23500) on tick 0 (placed at (3499, 22499) and (5499, 23499)), and side 0 casts a Tornado at (7500, 21500) on 30. The pull is a share of each unit's own configured speed, not of its mass: the Giant's first is (160, -20) and the Knight's (183, -114), 162 and 216 along their ways to the centre. The pulls run from 30 to 50; on 41 the Giant and the Knight take 84 and PrincessTower_1_1 25, which is not moved. The Tornado and its instances go at the closing cleanup of 50, and the last pull moves the units on 51.

`BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/mega_knight_group.json` - a Mega Knight played onto three Knights

The towers fight at level 11. Side 1 places three Knights directly at (3500, 13000), (2700, 13000) and (4300, 13000) on tick 0, and side 0 plays a Mega Knight at (3500, 11000) on 25, placed at (3499, 11500). The card casts MegaKnightAppear before it makes the unit: from (3499, 4500) at 4200, the placed point less five times the king tower's collision radius of 1400 along the length, onto the placed point, 1000 a visit. It lands on 31 for 430 on each Knight and pushes each away from the point, a budget of 225. The unit's push as it enters the deploying state, on 25, finds nobody: the play runs in the command pass, while the spatial index is empty. Its deploy ends on 44, and its first area attack is on 54.

The reference lists a run's played units only; the three Knights are listed in `units` as the run placed them. The file's `deploy_push` list holds each push of a unit entering its deploying state: the unit, the query's radius, the distance, what the query found and whom it asked to push. `BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/mega_knight_jump.json` - a Mega Knight jumps onto a Knight

The towers fight at level 11. Side 1 places a Knight directly at (3500, 17500) on tick 0, and side 0 plays a Mega Knight at (3500, 9000) on the same tick, placed at (3499, 9500). Its appearance lands on 6 and hits no one, its deploy ends on 19, it starts its jump on 59 and lands on 75 at (3750, 13750) for 537 with a push of 1000, resumes on 79 and hits for 268 on 112. Its push as it deploys finds nobody, as in `mega_knight_group`, and the Knight is listed in `units` the same way. `BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/lightning_defenders_tower.json` - a Lightning over a group defending a princess tower

The towers fight at level 11. Side 0 plays a Knight at (3500, 14500) on tick 0, placed at (3499, 14500), which walks up the left lane; PrincessTower_1_1 locks it on 55 and shoots on 70, 86 and 102. Side 1 plays on 70 a Musketeer at (4500, 24500), placed at (4499, 23499), a Knight named Guard at (2500, 23500), placed at (2499, 23499), and Minions at (3500, 23500), which take on the Knight. Side 0 casts Lightning at (3500, 23500) on 100: Lightning_3000000, level 11, whose first update is 100. Its 10th update (109) chooses among the six enemies in its circle the princess tower (3052 hit points), its 19th (118) the Guard (1766) and its 28th (127) the Musketeer (721); the Minions (230) are never struck, and side 0's own Knight in the circle is refused by the team test each time. Each LighningSpell starts on its target at height 10, aims at the same point and lands the next tick: 286 on the tower on 110 (1057 at level 11, CrownTowerDamagePercent -73), 1057 on the Guard on 119 and 1057 on the Musketeer on 128, which kills it, each followed by ZapFreeze for 500 ms. The stun resets what it lands on: the tower drops its reference on 110, locks the Knight again on 121 and shoots on 128 and 144, and the Guard's hit due on 122 lands on 132. The Knight, which dies on 143 without the Lightning, dies on 153. The area effect leaves at the cleanup of 129.

The `area_effects` list holds each launch after its update's entry: the hit count and its bound, and for a row with HitBiggestTargets the radius searched, the candidates with their hit points and shield, the units refused, the ids struck before and the one chosen; then the projectile, its target, start and aim, level and side. `BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/royal_delivery_group.json` - a Royal Delivery onto a group

The towers fight at level 11. Side 1 plays a Knight at (3500, 17500) and Archers at (4500, 18500) on tick 0, placed at (3499, 17499) and (4499, 18499), which walk down the left lane. Side 0 casts Royal Delivery at (3500, 12500) on 40: RoyalDeliveryArea_3000000, level 11, while all three are outside its circle of 3000. It does nothing for 39 updates; its 40th, on 79, launches RoyalDeliveryProjectile at its own point, at height 0 with no target, and the same cleanup removes it, so the projectile has no owner when it lands. It lands on 80: its area impact deals 437 to the Knight and kills both Archers, now inside, and then makes DeliveryRecruit (proj_4000001_0, 547 hit points and a shield of 240) at the point for side 0, deploying for 250 ms. The Recruit locks the Knight on 86 and hits it on 95 and 121; the Knight's hits on 90 and 114 go into its shield. `BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/heal_spirit_group.json` - a Heal Spirit heals a group at the river

The towers fight at level 11. Side 0 plays Knights T1 at (3500, 14000) and T2 at (4500, 14000) and Minions at (4000, 13000) on tick 0; side 1 plays Knights E1 at (3500, 17500) and E2 at (4500, 17500) and a Musketeer at (4000, 19000) on the same tick, and the Knights meet at the river. A Heal Spirit for side 0 is placed directly at (4000, 12000) on 60 and listed in `units`. It launches its projectile at E2 on 98 from (4215, 13774) and dies with its hit. The projectile lands on 107 at (4399, 17099): 110 on E1 and E2, then HealSpirit_3000000 is made there - the area effect `created` with `how` "projectile" and the projectile as its source, side 0, level 10 as packed - and folded at the same cleanup as the projectile leaves. Its one hit on 108 puts HealSpiritBuff for 1000 ms on T1, T2 and Minions_0; the other Minions are outside its 2500, and the enemies are not its own. T1 and T2 are healed 100 on 113, 118, 123 and 128, Minions_0 stays at its maximum of 230, the area effect leaves on 127 and the buffs on 128. T2, at 90 hit points as the buff came, dies on 157 rather than 118. Its `clones` list holds the one area effect the impact made, with the projectile, the point, the level and the projectile's target. `BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/clone_golem_group.json` - a Clone over a Golem and a Musketeer under Arrows

The towers fight at level 11. Side 0 plays a Golem at (4000, 13500), placed at (4499, 13500), and a Musketeer at (4300, 9500), placed at (4499, 9500), on tick 0; side 1 plays Knights E1 at (3500, 17500) and E2 at (4500, 17500) on the same tick, which walk down and hit the Golem while the Musketeer shoots them. Side 1 casts Arrows at (4400, 15200) on 73 and side 0 a Clone at (4300, 12000), placed at (4500, 12500), on 85, both listed in `commands`.

The Clone's area effect, Clone_3000000 at level 10 as packed, hits once on 85, the tick it is cast. Its hit schedules CloneAction on the Musketeer and the Golem, in the index query's order, with the area effect as the cause, and the tick's last pending pass runs it on each, the Golem first by its id. Each run puts the Clone buff on its unit for 500 ms at once, then makes the clone on the unit's point - Golem_0_clone0 at (4297, 14495) and Musketeer_0_clone0 at (4402, 10083), each of its unit's row and side, at level 10 as packed, 1 hit point of 1 with no shield, in the clone state 8 with its targeting off - registers it with its movement visit only, and copies the Clone buff onto it with its 500 ms left. Then each clone starts its move back toward y 0 and its unit, set to 8 too, its move toward y 31999, each a single-cell route. The `clones` list holds each schedule, buff, clone, move start and move end; the `buffs` list each copy.

The first Arrows volley kills the Golem's clone on 86, one step back at (4297, 14370): its death damage hits E1 and E2 for 225 and pushes them 1800, and its two Golemites are clones of 1 hit point, which the next volley kills on 89, their own death damage hitting E1 for 99. The Musketeer's clone steps back 125 a visit, its unit and the Golem forward, ten visits each; the three are resumed on 95, as the Clone buff runs out. The Musketeer's clone takes E1 on 99 and shoots it on 112, 132, 152 and 172; E1 dies on 180. The Golem's burst clock stands still under the Clone buff's speed of -100, so it walks on from 95 to 113 before its next pause, where it would have paused on 104. `BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/clone_rage_group.json` - a Rage over clones and their units

The towers fight at level 11. Side 0 plays Knights K1 at (3500, 12000) and K2 at (4500, 12000) and a Musketeer M at (4000, 10000) on tick 0; side 1 plays a Knight E at (3500, 19000). Side 0 casts a Clone at (4000, 12500), placed at (4500, 12500), on 40: K1_0_clone0, K2_0_clone0 and M_0_clone0, at level 10 as packed, each of 1 hit point. K1 meets E from 65. A Rage area effect of side 0, Rage_3000001, is placed at (3500, 15500) on 70, as the area effect alone; its chained RageDamage deals 179 to E on 71, the only unit of its circle it validates.

On 75, 81, 87 and 93 the Rage buffs K1, K2, M and the two Knights' clones; the Musketeer's clone stands outside its circle and is not asked. Its buff, Rage, heals nothing, so its filter's buff test passes each clone as any unit. The `clone_buff_gate` list holds every ask of that test of a clone - the tick, the area effect, its buff, the clone, the path that asked, the buff row's HealPerSecond as the query and whether it refused - 16 here: two clones, four hits, each asked by the buff's walk and again by its apply. E dies on 95, the last tick. `BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/clone_zap_poison_group.json` - a Zap and a Poison over clones

The towers fight at level 11. The Knights, the Musketeer and the Clone of `clone_rage_group`, with no enemy unit; the clones are made at (3340, 13732), (4341, 13732) and (4341, 11732). Side 1 casts a Zap at (3500, 14500) on 52 and a Poison at (4500, 13500) on 80.

On 52 the Zap's area damage of 192 hits K1, K2, M and the two Knights' clones, which die there; its buff block then puts ZapFreeze on K1, K2 and M only, the walk testing alive before its filter. The area damage's validator asks the filter before its circle test, so the Musketeer's clone, outside the Zap, is asked too. On 84 the Poison's first hit buffs K1, K2, M and the Musketeer's clone, and the buff's first damage on 104 kills that clone. The `clone_buff_gate` list holds three asks by the Zap's area damage and eight by the Poison's buff, each query 0 and none refused. `BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/clone_heal_spirit_knight.json` - a Heal Spirit's area refusing a clone

The towers fight at level 11. Side 0 plays a Knight K1 at (3500, 12000) and side 1 a Knight E at (3500, 19000) on tick 0, and a Heal Spirit, Spirit, is placed directly for side 0 at (3500, 8500) on the same tick, listed in `units`. Side 0 casts a Clone at (4000, 12500) on 20, which clones K1; the Spirit, 4000 behind, stands outside it. K1 and E fight from 63. The Spirit dies onto E on 76; its projectile lands on 85 and makes HealSpirit_3000001 there.

The area effect's one hit on 86 puts HealSpiritBuff on K1, healed 100 on 91, 96, 101 and 106. K1's clone stands in its reach, but the buff heals 157 a second, so the filter's buff test refuses it: the `clone_buff_gate` list holds the one ask, query 157, refused, and the clone gets nothing. `BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/electro_giant_struck.json` - an Electro Giant struck by two Knights and a Musketeer

The towers fight at level 11. Side 0 plays an Electro Giant at (3500, 13000), placed at (3499, 13500), on tick 0; side 1 plays Knights K1 at (3500, 17500) and K2 at (4500, 17500) and a Musketeer at (4000, 20000), placed at (4499, 20499), on the same tick. The Giant walks up toward the princess tower and the Knights and the Musketeer take it on.

Every hit that reaches the Giant alive runs its reflect, at level 10 as packed. The Musketeer's shots on 39, 58, 78 and 97 come from outside the reflect radius of 2000 plus both collision radii and are struck back at nothing; from 116 it stands inside, and each shot on 116, 145 and 174 puts ZapFreeze on the Musketeer for 500 ms and deals it 192. Each Knight hit, K1 on 45, 78, 111, 144 and 177 and K2 on 47, 80, 113 and 146, puts ZapFreeze on its Knight and deals it 192, which stuns it and drops its target. PrincessTower_1_1's shots from 111 on come from outside the reach. K1's hit on 177 kills the Giant and is still struck back, before the Giant's own death; K2's next hit finds no Giant.

The `reflects` list holds every hit the reflect saw: the tick, the Giant, the attacker - a unit, or a projectile with its root as the source - and the one struck, the Giant's hit speed scaled from 100, and for a hit struck back the buff, its time and level, and the damage with the hit points before and after it; the events list the reflected hits. `BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/electro_giant_tower.json` - an Electro Giant at a princess tower and the king

The towers fight at level 11. Side 0 plays an Electro Giant at (3500, 15000), placed at (3499, 14500), on tick 0, which walks up the left lane to PrincessTower_1_1. The tower shoots it from 88; its shots until 178 come from outside the reach. From 192 each shot is struck back: ZapFreeze on the tower for 500 ms, which holds its next shot back so that it shoots every 26 ticks rather than 15, and 128, the crown tower column at level 10, on 192, 218, 244 and on to 530, where the reflected damage takes the tower's last 84 and kills it, between the Giant's own hits. The Giant walks on to the king tower, which shoots it from 615 and is struck back from 653, 128 a shot, while PrincessTower_1_2's shots come from outside the reach. The Giant dies on 766. `BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/fisherman_knight.json` - a Fisherman hooking a Knight

The towers fight at level 11. Side 0 plays a Fisherman at (3500, 12000), placed at (3499, 12500), and side 1 a Knight at (3500, 20500), placed at (3499, 20499), on tick 0. The Fisherman walks up toward the Knight and arms his special on 25, the Knight 7425 away, inside its ring of 4000 to 7500: its radius plus SpecialMinRange and plus SpecialRange. He stands loading 1300, 50 a visit, and fires FishermanProjectile on 51. It lands on the Knight on 58 at (3298, 18032), hooks it and puts IceWizardSlowDown on it for 1500 ms: the Knight goes to state 12 and the Fisherman, waiting, to 14. The hook flies back at 510 a visit, DragBackSpeed 850 times the Knight's Speed of 60 over 100, the Knight on it each tick, and is let go on 66 at (3386, 14464), short of the Fisherman by DragMargin and both radii, where it impacts again and refreshes the slow. In that tick's cleanup the Fisherman forgets the hold and the Knight the hook; the Fisherman hits on 67 for 194 and every 26 ticks after, and the Knight asks to stand on 67, takes the Fisherman on 68 and hits him from 82. The Fisherman dies on 182, the Knight to the towers on 203.

The `hooks` list holds every special load armed - the unit, its reference, their squared distance, the ring, the load and what the arming visit left of it - every state the hook asked of a unit, with the state it left it in and its position, and the hold and the pull its leaving ended. `BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/fisherman_tower.json` - a Fisherman hooking a princess tower

The towers fight at level 11. Side 0 plays a Fisherman at (3500, 15500), placed at (3499, 14500), on tick 0, which walks up the left lane at PrincessTower_1_1. He arms his special on 71 with the tower 7989 away, inside its ring of 4500 to 8000, and fires on 97, the tower's arrows hitting him meanwhile. The hook lands on the tower on 107 and slows it for 1500 ms; a tower cannot be pulled, so the Fisherman goes to state 13, his reference dropped, and the hook stands and drags him from (3281, 17514) at DragSelfSpeed 450 a visit until he is within both radii, at (3450, 23802) on 121. On 122 the hook is let go, with no second impact, and the Fisherman asked to move; he hits the tower on 123, 149 and 175, and its arrows kill him on 194. `BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/three_musketeers_pekka.json` - the Three Musketeers' bayonet on a P.E.K.K.A

The towers fight at level 11. Side 0 plays the Three Musketeers at (3500, 12000), placed at (3499, 12500), and side 1 a P.E.K.K.A at (3500, 17500), placed at (3499, 17499), on tick 0. The card lists its three characters with offsets of their own, and the bottom side negates both: TM_0, ThreeMusketeer_Rework_Character_1, at (3499, 13500) deploys at once; TM_1 at (4499, 11500) and TM_2 at (2499, 11500) wait 100 and 200 ms first. Each musketeer's filter runs as its attack starts and at every hit, and sets the entry its next hit reads: the bayonet for a ground target within 1600 plus both radii, a shot otherwise. They shoot the P.E.K.K.A from 31, 34 and 35, TM_1 a tick later than its wait alone for its LoadTime of 650. TM_0's filter at its hit of 57 finds the P.E.K.K.A within 2850, so its hit of 83 is the bayonet: its action runs on TM_0 with the P.E.K.K.A as the cause and queues 123 on the P.E.K.K.A, which the drain of that tick deals at TM_0's level, 314 (2536 to 2222). The P.E.K.K.A kills TM_0 on 101 and walks to TM_1, whose filter on 164 sets the bayonet; its hit of 190 queues 314 and the drain kills the P.E.K.K.A with its last 154. TM_2 dies to the princess tower's arrows on 310.

The `actions` list holds every run of an action - each filter, the index it set, the bayonet's action and the damage it ran on its target - and the events list every typed hit with its damage id and source. `BattleActionSpawnRunTest` plays it with the spawn runs, and `CardPlacementTest` works its plays out.

## `golden/three_musketeers_air_building.json` - the Three Musketeers on the right half, at Minions and a Cannon

The towers fight at level 11. Side 1 plays the Three Musketeers at (14500, 17500), placed at (14500, 17499), and side 0 Minions at (14000, 9000) on tick 0. On the right half the card turns its offsets across the width over for the top side: TM_0 stands level with the point, TM_1 at (15500, 18499) and TM_2 at (13500, 18499). The Minions fly in within 1600 of the musketeers, but they are not on the ground, so every hit is a shot; they die on 48, 64 and 74. Side 0 plays a Cannon at (14500, 13500) on 95. A building is ground, and it stands within TM_0's 2700: TM_0's filter as its attack starts on 95 sets the bayonet, which deals 314 on 106 and the Cannon's last 78 on 132. TM_1 and TM_2, farther off, shoot it. The princess tower, ground but beyond 1600, takes shots only and falls on 338; TM_0 dies on 269.

The lists are those of `three_musketeers_pekka`. `BattleActionSpawnRunTest` plays it with the spawn runs, and `CardPlacementTest` works its plays out.

## `golden/graveyard_tower_defender.json` - a Graveyard on a princess tower, a Knight defending it

The towers fight at level 11. Side 1 plays a Knight at (5500, 30500), placed at (5499, 30499), on tick 0, and side 0 casts a Graveyard on PrincessTower_1_1 at (3500, 25500) on 40. The card's area effect, Graveyard_rework, lives 9000 ms; as it is admitted its starting group queues its thirteen skeleton spawns at once, each with its own delay, 2200 to 8700 ms. Each spawn runs in the area effect's phase-1 pass and makes one Graveyard_rework_Skeleton of side 0 at a point its position expressions work out from the area effect's own point and side: (3500, 29000) on 84, then (250, 25500), (1000, 28000), (3500, 22000), (7000, 25500), (6000, 23000) and on to 214. The -3500 row's x of 0 is beyond the arena's edge, so the skeleton is made one unit right and clamped to 250. Each deploys for the spawn row's 500 ms, not the character's 1000, and cannot be targeted until its sixth visit. The Knight kills five, PrincessTower_1_1 shoots seven and PrincessTower_1_2 two; the skeletons hit the tower from 154 and the Knight on 234 and 257. The area effect leaves at the cleanup of 219.

The `actions` list holds the group's run and every spawn with its point, and `area_effects` the area effect's life. `BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/graveyard_right_side1.json` - a top-side Graveyard on the right half

The towers fight at level 11. Side 1 casts a Graveyard at (14500, 7500), near PrincessTower_0_2, on tick 0. Right of the middle the offsets across the width are turned over, and for side 1 the offsets along the length too: the skeletons come at (14500, 4000) on 44, then (17750, 7500), (17000, 5000), (14500, 11000), (11000, 7500), (12000, 10000) and on to 174, the x + 3500 of 18000 beyond the right edge clamped to 17750. PrincessTower_0_2 shoots ten of them and PrincessTower_0_1 the three at x 11000 and 12000; six hits land on PrincessTower_0_2. The area effect leaves at the cleanup of 179.

The lists are those of `graveyard_tower_defender`. `BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/phoenix_egg_hatch.json` - a Phoenix's fireball, its egg and the hatch

The towers fight at level 11. Side 0 plays a Phoenix at (3500, 13000), placed at (3500, 13500), and side 1 Skeletons at (3500, 21000), placed at (3499, 21499), on tick 0. The Phoenix meets the Skeletons and stands at (3372, 15388) from 52. Side 1 casts a Rocket on 18, placed at (3500, 15500), which kills the Phoenix on 59 (1484). Its death slot launches PhoenixFireball from its point at its height of 3000, aimed at that same point, as it has no spawn radius; the fireball lands on its first flight visit, on 60: 163 at level 11 over 2500 kills the three Skeletons, and its impact makes PhoenixEgg, proj_4000001_0, at its point with no deploy. The egg is immune for six visits; from 61 its row's tags keep it standing in state 0, its reference the princess tower out of its range. Its spawner counts its start time of 3800 down from its first visit and on 136 hatches PhoenixNoRespawn, proj_4000001_0_0, one collision-radius sum in front at (3372, 16488), deploying for its 733 ms until 151. On 137 the egg's row destroys it at its limit, and the closing cleanup removes it with no death. The reborn Phoenix flies on at PrincessTower_1_1, whose arrows hit it on 180 and 195.

The `phoenix` list holds every death projectile with its start and aim, every unit made with the immunity its row starts it with, and every spawner destroyed at its limit with its hit points; the `actions` list the egg's spawn by the impact and the reborn Phoenix's by the spawner. `BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/phoenix_egg_killed.json` - a Phoenix shot down by a tower, and its egg

The towers fight at level 11. Side 0 plays a Phoenix at (3500, 15000), placed at (3500, 14500), on tick 0, which flies up the left lane and attacks PrincessTower_1_1 from (3301, 22465) from 155. The tower's arrows kill it on 218; its fireball, aimed at its own point, lands on 219 and deals the tower 163, as the row has no crown-tower percent, and makes the egg, proj_4000010_0. The egg, immune for six visits, hits the tower for 0 on 228, 248 and 268; the tower takes it on 226 and its third arrow kills it on 278, before its hatch. The run lists no actions: the egg's spawn is held by its entry in the `phoenix` list, where and when it was made. `BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/skeleton_barrel_tower.json` - a Skeleton Barrel's drain at a tower, and its container

The towers fight at level 11. Side 0 plays a Knight at (3500, 10000) on tick 0 and a Skeleton Barrel at (3500, 10000), placed at (3500, 10500), on 60, whose starting action, the pop, runs in its phase-1 pass of 60 and does nothing from then on. PrincessTower_1_1 takes the Knight on 121 and keeps it. The barrel flies its direct path: straight at the point at its attack range from the tower, at its usual speed, which it reaches at (3500, 23730) on 226. Its hits on 228 and 234 deal 0 and kill nothing; from its first hit's tick its state visit drains 53 a visit - its maximum of 532 over its Kamikaze time of 500 ms, ten visits - with itself as the attacker, and the eleventh drain kills it on 238. Its death drops SkeletonContainerNew, S_0_0, at its point, deploying for 600 ms; on 250 the container dies as its deploy ends: 145 on the tower over 2000, and seven Skeletons on its 1480 ring, the lane of its point asked before each, the ring turned over across the width in lane 1, each flying back from the container's point. They hit the tower with the Knight from 270.

The `kamikaze` list holds each Kamikaze end, with the hit points it found and that it did not kill, and each drain, with what it took and the hit points before and after; the `actions` list the lane each ring child asked for. `BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/skeleton_barrel_shot_down.json` - a Skeleton Barrel shot down before its hit

The towers fight at level 11. Side 0 plays a Skeleton Barrel at (3500, 10000), placed at (3500, 10500), on tick 0, and side 1 a Musketeer at (3500, 21500), placed at (3499, 21499), on 40. The Musketeer's shots land on 81 and 100 for 217 each, and the tower's arrow kills the barrel on 113 at (3500, 18960), before any hit, so it never drains. Its container falls there and dies as its deploy ends on 125: 145 on the Musketeer, which it pushes 1000 away, and seven Skeletons on the lane-1 ring. They kill the Musketeer on 197, by which time it and the tower have killed six of them; the tower's arrow kills the last on 204. `BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/goblin_hut_passing.json` - a Goblin Hut's life state at units passing by

The towers fight at level 11. Side 0 plays a Goblin Hut at (10500, 12000), placed at (10500, 12500), on tick 0; side 1 plays a Battle Ram on 60 and a Knight on 300, both at (3500, 17500). The hut's deploy ends on 19, and its life state starts in its phase-1 pass of 20 and waits. The Ram comes within the hut's reach - its collision radius 1000 plus its range 6000, and the Ram's own radius - on 116: the start-spawning action, and one SpearGoblin_Dummy at once, 1200 toward the Ram turned by 20 degrees; another on 158, 42 run passes later. On 170 the Ram is beyond the keep reach and the run is lost; nobody is found by 200, when the timer reaches 2100, and it goes back to waiting. The Knight comes into reach on 351, with a spawn at once, then on 393, 435 and 477, each turned the other way from the last; the goblins kill it on 515, its leaving marks the run lost, and on 519 it waits again. The decay kills the hut on 622, and its death spawn makes a SpearGoblin on its point.

The `goblin_hut` list holds the life state's start and every step, with its four words before and after - the timer, the target's id, the lost byte and the spawn count - and what it called, every find the query answered, every child's point before and after the relocation off water, every child as it was made, and the notice that its target left. The hut's children are held there, and the `actions` list holds the rest. `BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/goblin_hut_lifetime.json` - a Goblin Hut alone

The towers fight at level 11. Side 0 plays a Goblin Hut at (3500, 11500) on tick 0. Its life state starts on 20 and waits through 603 steps with nobody in reach, and the decay kills the hut on 622, its death spawn a SpearGoblin on its point. `BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/goblin_cage_knight.json` - a Goblin Cage pulling a Knight

The towers fight at level 11. Side 0 plays a Goblin Cage at (7500, 12500) on tick 0; side 1 plays a Knight at (3500, 17500), placed at (3499, 17499), on the same tick. The cage's shake, its starting action, runs in its phase-1 pass of 0 and is stepped doing nothing from then on. Both deploys end on 19. On 20 the Knight takes the cage, 6348 away and within its sight and the cage's radius, and leaves its lane for it, reaching (5405, 14154) on 87; the cage takes the Knight on 20 too, but its first hit, which would deal nothing, is not due before 219. PrincessTower_0_1 shoots the Knight from 73. The Knight hits the cage on 96, 120 and 144, 202 each, and the third, with the decay of 195 hundredths a visit from 20, kills it: the cage leaves on 144 and its GoblinBrawler is made on its point, 1080 hit points, deploying for 500 and immune to 149. The Brawler takes the Knight on 155 and kills it on 209, then walks to PrincessTower_1_1, hits it on 349 and 371 and dies to its arrows on 386. `BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/goblin_cage_lifetime.json` - a Goblin Cage alone

The towers fight at level 11. Side 0 plays a Goblin Cage at (3500, 11500) on tick 0 and nothing else. Its shake runs from 0, doing nothing; its deploy ends on 19 and from 20 it holds PrincessTower_1_1, out of its range, decaying 195 hundredths of a hit point a visit from 780. The decay kills it in the second pass of 419, and its GoblinBrawler stands on its point, deploying to 429, then takes PrincessTower_1_1 on 430 and walks to it. `BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/berserker_knight.json` - a Berserker against a Knight

The towers fight at level 11. Side 0 plays a Berserker at (3500, 12000), placed at (3499, 12500), and side 1 a Knight at (3500, 20000), placed at (3499, 20499), both on tick 0 in the left lane. The Berserker's starting action runs in its phase-1 pass of 0 and sets its attack sequence index to 0. The two walk up the lane and take each other on 31. The Berserker hits from 67, every 12 ticks, 102 each, and each hit flips the index, 0 to 1 and back. The Knight's hits, 202 every 24, kill it on 165, after its ninth hit (the Knight 1766 to 848). The Knight then walks on at PrincessTower_0_1.

The `berserk` list holds the start and every notice of a landed attack, with the index before and after. `BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/berserker_tower.json` - a Berserker at a princess tower

The towers fight at level 11. Side 0 plays a Berserker at (3500, 15500), placed at (3499, 14500), on tick 0 and nothing else. Its starting action sets the index to 0. PrincessTower_1_1 locks it on 44 and its arrows land from 70. It reaches the tower and hits it from 121, every 12 ticks, 102 each (3052 to 2440), the index flipping on each hit, until the ninth arrow kills it on 191. `BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/goblin_curse_knights.json` - a Goblin Curse on two Knights in a fight

The towers fight at level 11. Side 1 plays Knights at (3500, 20500) and (4500, 21000), and side 0 Knights at (3500, 12000) and (4500, 11500), all on tick 0 in the left lane; the four meet and fight in pairs from 79. On 130 side 0 casts a Goblin Curse at (4000, 17500), placed at (4500, 17500): its area effect hits nothing, and its starting group spawns GoblinCurseBase at its own point in its phase-1 pass, at its level, the curse its parent. From 131 the base's hit, every tick, schedules its group on both enemy Knights in its circle, and each takes GoblinCurse and GoblinCurseDamage for 100 ms from the base, refreshed every tick; its own Knights are spared. The damage over time takes 35 every 20 ticks from 151. The Knight at (3500, 20500) dies to a hit on 247, cursed, and leaves a GoblinCurseGoblin for side 0 on its point, deploying for 1000 ms, which PrincessTower_1_1's arrows kill on 288. The curse leaves at the cleanup of 249 and its base at 250; the other Knight takes one more 35 on 251 from the buff left over, which goes on 252, and dies to a hit on 266.

The `area_effect_spawn` list holds the spawn of the base and every hit action it scheduled. `BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/goblin_demolisher_knight.json` - a Goblin Demolisher under half its hit points

The towers fight at level 11. Side 0 plays a Goblin Demolisher at (3500, 12000), placed at (3499, 12500), and side 1 a Knight at (3500, 20000), placed at (3499, 20499), both on tick 0. The Demolisher shoots the Knight from 46, 186 a shot, and the Knight reaches it and hits it from 109, 202 every 24 ticks. The hit on 181 leaves it 492 of 1300, under half: its trigger runs its group in that tick's phase-2 pass with the Demolisher as the cause, and SpawnCancelTauntAEO makes CancelTauntAEO on its point at level 10, the Demolisher its parent and the object it follows. On 182 the area effect's one update schedules ResetTauntEffect on the Demolisher; in its phase-3 pass the taunt forces it onto itself, raises LOCK_TARGET, makes its re-selection wait 50 ms and puts GoblinDemolisher_ResetTargetBuff on it, so it ends the tick referencing itself; the area effect leaves at that cleanup. On 183 the swap into GoblinDemolisher_kamikaze_form, in the phase-1 pass, keeps its 492 hit points and its level and gives up its reference; the taunt's step clears the wait and removes the buff. From 184 it walks at PrincessTower_1_1 at the new speed, its hit points draining 3.25 a visit, while its last shot lands on the Knight on 183. A hit kills it on 229 at (3179, 18458), with 141 hit points left; its death projectile lands on 230 on the Knight for 404 and pushes it. PrincessTower_0_1's arrows kill the Knight on 261.

The `area_effect_spawn` list holds the spawn of the area effect, the hit action it scheduled, the taunt's perform and its arming and step, each with the calls it made. `BattleActionSpawnRunTest` plays it with the spawn runs.

## `golden/dark_magic_knight.json` and `golden/dark_magic_group.json` - Dark Magic's laser ball

The towers fight at level 11, and both runs stop on tick 99. Side 0 casts Dark Magic on tick 0, its area effect DarkMagicAOE made at level 10, its starting group written inline. The group's laser ball starts in the area effect's phase-1 pass of 10 with its timer at 0, and fires on 30, 50 and 70, each time scheduling on every enemy within 2500 of its point the buff spawn their count picks. Each buff spawn runs in its target's phase-2 pass of the same tick and puts a 100 ms buff on it, at level 10 with the area effect as its source, which deals one hit two ticks later and goes. The area effect's last update, on 79, schedules its life-end action, an effect, which runs in its phase-3 pass; it leaves at that cleanup.

- `dark_magic_knight`: side 1 plays a Knight at (3500, 20000), placed at (3499, 20499), and the Dark Magic lands at (3500, 19500) in its path. Each fire finds the Knight alone and puts the strongest buff on it: it takes 340 on 32, 52 and 72, from 1766 to 746.
- `dark_magic_group`: side 1 plays Barbarians at (3500, 23000), placed at (3499, 23499), and the Dark Magic lands at (3500, 24500), over them and their princess tower. The fire on 30 finds all five and the tower and puts the weakest buff on each: 76 on a Barbarian, 17 on the tower, its per-hit column. The fires on 50 and 70 find four and three, the tower each time, and put the middle buff on each: 160 on a Barbarian and 25 on the tower.

The `laser` list holds the laser ball's start, with its pass and timer, each fire, with the count, the index it picked, the targets in the query's order, the action and the timer before and after, and the life-end action as it is scheduled. `BattleActionSpawnRunTest` plays both with the spawn runs.

## `golden/vines_group.json` and `golden/vines_tower.json` - Vines' selector and its hold on the ground

The towers fight at level 11. Vines' area effect Vines_AeO, level 10, starts its selector 900 ms after it is cast, due on that tick, the next and the third after; each due tick picks the enemy in its 2500 circle with the most hit points and shield not picked before, and runs Vines_Action_Group on it in its phase-2 pass: the air-to-ground run and the snare for its size.

- `vines_group`: side 1 plays a Giant at (3500, 22000) and a Knight at (2500, 22000) on tick 0, and a Minion at (4500, 22000) and a Skeleton at (2500, 21000) are placed for it (`units`); a Knight KA is placed for side 0 at (3500, 16000), which kills the Skeleton on 50. Side 0 casts Vines at (3500, 19500) on 30. The selector picks the Giant on 48 (3968), the Knight on 49 (1766) and on 51 the Minion (230), the Skeleton gone. The Minion is pulled down on 52 and 53 and is a ground unit from 55, so KA, which attacks only ground units, takes it on 56 and kills it on 83, after the snare's 153 on 71. The Giant and the Knight take 153 on their snare's 20th and 40th visit, and their runs end on 89 and 90.
- `vines_tower`: side 1 plays a Knight at (4500, 24000) on 0, and KA is placed for side 0 at (3000, 21000), where PrincessTower_1_1 shoots it and the Knight fights it. Side 0 casts Vines at (3500, 24500) on 60, over the tower. The selector picks the tower on 78 (3052) and the Knight on 79 (1362), and its third entry on 81 picks nobody, which finishes the run. The snared tower drops its target on 78 and launches nothing until its snare goes on 118; it takes 38 on 98 and 118 and locks KA again on 119. The Knight lands no hit from 61 to 123.

The `vines` list holds the selector's start with its due ticks, each step (what it found, the scores, each entry's pick, the entries that picked nobody, the finish, and the due ticks and picks before), its removal, and every air-to-ground run's start and phase change with the height changes it pushed. `BattleActionSpawnRunTest` plays both with the spawn runs.

## `golden/goblin_machine_knight.json` and `golden/goblin_machine_tower.json` - the Goblin Machine's rocket

The towers fight at level 11. The Goblin Machine's starting action, goblin_machine_rocket, keeps a run on it from its play: the load counts from the deploy's end and passes LoadTime 1500 on the 31st step, after which, with no attack and the cooldown out, the finder takes the nearest enemy whose centre lies 2500 to 5000 beyond both radii. The signal, goblin_machine_rocket_target_signal at level 10, stands at the target's point and does nothing; AttackDelay 1000 later the rocket leaves from 1200 behind the machine's facing at height 5000 for the signal's point, and its area hit deals 304 to a troop and 152 to a crown tower. The step after the rocket has gone ends the attack and the signal.

- `goblin_machine_knight`: side 0 plays a Goblin Machine at (3500, 12000) on tick 0, placed at (3499, 12500); side 1 plays a Knight at (3500, 16500) and a Musketeer at (3500, 19000). On 50 the finder lists the Knight and the Musketeer and takes the Musketeer, the Knight being inside the ring's inner edge; the signal stands at (3499, 19499). The machine fights the Knight from 51, 212 every 24 ticks, turning toward it at each attack start, so its rocket of 70 leaves from (3315, 12584), behind it as it faces the Knight. It lands on 98, 304 on the Musketeer, and the attack ends on 99. The second signal comes on 120 and the second rocket on 140, in flight as the run ends on 146.
- `goblin_machine_tower`: side 0 plays a Goblin Machine at (3500, 14500) on tick 0, which walks the left lane. On 91 PrincessTower_1_1 enters the ring and is marked at (3500, 25500); the rocket leaves on 111 from (3283, 18774), behind the machine's walking facing, and lands on 138, 152 on the tower. From 165 the machine hits the tower, 212 every 24 ticks; the tower is inside the inner edge and nothing else is in the ring, so no second rocket comes. The tower kills the machine on 374, and its run stops with no attack to end.

The `target_indicator_attack` list holds the run's start, each find (what the query listed and whom the ring kept), each signal (its target, point and level), each shot (the rocket, its start, aim, level and the facing), each signal ended, each step that did more than ask the finder for nobody (the load, the cooldown, the attacks' times, signals and rockets, and the stop tag, before and after, with its calls) and each stop. `BattleActionSpawnRunTest` plays both with the spawn runs.

## `golden/little_prince_giant.json` and `golden/little_prince_retarget.json` - the Little Prince's ramp

The towers fight at level 11. The Little Prince's starting-attack row runs at its attack start and at every hit, in its phase-2 pass, and reads attack_count, the hit count of the current attack: at 0 it sets the index to 0, at 3 it puts LittlePrinceLvl1 (HitSpeedMultiplier 200) on the unit, at 6 LittlePrinceLvlMax (300) and the index 1, at 7 the index 2. Each buff lives while its AliveIfTrue holds, asked on every buff visit, so the shots come 24, 12, then 8 ticks apart, 104 each.

- `little_prince_giant`: side 0 plays a Little Prince at (3500, 12000), placed at (3499, 12500), and side 1 a Giant at (3500, 20000), placed at (3499, 20499), both on tick 0. The Little Prince takes the Giant on 33 and shoots on 40, 64 and 88; Lvl1 goes on with the third shot, on 88, and the shots come on 100, 112 and 124, where Lvl1 ends and LvlMax goes on with the index 1; the index is 2 from 132 and the shots come every 8 ticks. PrincessTower_0_1 locks the Giant on 142 and kills it on 306. On 307 the Little Prince takes PrincessTower_1_1, out of its range, which clears its attack time, and LvlMax ends in that tick's buff visit.
- `little_prince_retarget`: side 0 plays a Little Prince at (3500, 15000), placed at (3499, 14500), and side 1 Spear Goblins at (3500, 22000), placed at (3499, 22499), both on tick 0. The Little Prince takes S_0 on 26 and kills it on 64; S_2, taken on 65, and then S_1, taken on 101, are in range, so the ramp goes on: Lvl1 on 81, LvlMax and the index 1 on 117. S_1 dies on 124 and on 125 the Little Prince takes PrincessTower_1_1, out of range, which clears the ramp; LvlMax ends that tick. It walks to the tower, which locks it on 154, starts over with the index 0 on 187, shoots on 194 and dies to the tower's arrows on 197.

The `little_prince` list holds every read of attack_count (the owner, its attack time and the count) and every ask of a buff's life condition (the unit, the expression and its answer). `BattleActionSpawnRunTest` plays both with the spawn runs.

## `golden/boss_bandit_bandit_knight.json` and `golden/boss_bandit_tower_bandit.json` - the Boss Bandit's kill checks

The towers fight at level 11, and every unit is placed directly on tick 0. The Boss Bandit dashes as the Bandit does, its landing hit 491. Each kill it makes schedules its killed-done check on it, with what it killed as the cause, and the check runs in its next pending pass: phase 2 after a melee hit or a dash landing. Its killed action checks its killer the same way. Each check matches only the Bandit and ends in a voice line, which only shows something. A unit dashing under a row with a dash immunity, and one whose immunity still lasts after its dash, takes nothing: the damage entry refuses the hit, which the run still lists.

- `boss_bandit_bandit_knight`: side 0 places a Boss Bandit at (3500, 10000), and side 1 a Bandit at (3500, 18000) and a Knight at (3500, 24000). The two dash at each other on 44, and on 49 both landing hits are refused: the Boss Bandit lands first, on the Bandit still dashing, and the Bandit then on the Boss Bandit, still immune. The Boss Bandit kills the Bandit on 114, and its check matches and runs its voice line in that tick's phase-2 pass. It dashes at the Knight on 135, lands 491 on 139 and kills it on 270, where the check finds no match and schedules nothing.
- `boss_bandit_tower_bandit`: side 0 places a Boss Bandit at (3500, 20000) and side 1 a Bandit at (6000, 26000). Both dash on 35, the Boss Bandit at PrincessTower_1_1 and the Bandit at it: the tower's arrow on 38 and the Bandit's landing hit on 40 are refused while it dashes, and it lands 491 on the tower on 42. The Bandit kills it on 199, and its killed action checks the Bandit, matches and runs its voice line in that tick's phase-2 pass.

The `kill_hooks` list holds every killed-done check scheduled (the killer, what it killed, the row and whether a pending pass was running) and every check of an action's cause (the owner, the row, the cause, its data row and what the check scheduled). `BattleActionSpawnRunTest` plays both with the spawn runs.

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

The same layout, two Skeleton Army plays of the bottom side on tick 0, at level 11 with the towers fighting, each run until its last skeleton dies: one requested at (3500, 10000) and placed at (3499, 10500), in front of the left bridge, and one requested at (500, 1500) and placed at (499, 1500), near the corner. The skeletons walk up the left lane as a crowd, push each other and are steered around each other and the towers, and fight the enemy princess tower, which kills all fifteen and stands: the last skeleton dies on tick 298 (bridge) and 383 (corner). They hold what a crowd does in the standard game: at the bridge a skeleton pushed sideways walks on the water beside it from tick 60, and at the tower the crowd presses some of its own inside the tower's collision circle from tick 163; the corner run passes its own princess tower close enough to push a skeleton inside that one too, on tick 57.

## `golden/sparky_river.json` - a unit's own recoil, and the relocation off the river

The same layout. A Knight of the top side requested at (3500, 20000) on tick 0 walks down the left lane and hits PrincessTower_0_1 from tick 219. A Sparky of the bottom side requested at (1500, 14500) on tick 190 is placed at (1499, 14500), locks onto the Knight on 210 and launches on 229. After the launch it asks for its own pushback away from the aim: target (1264, 15212), budget 200, then displacements of 175, 150, 125 and 100 over the next visits. On 232 it stands on a river cell, and on 233 the pushback visit moves it off to (1079, 14770) before that visit's step. Later it walks across the river cells beside the bridge and nothing moves it; its pushes on 365, 444 and 523 end on land, and it dies on 524; its last shot destroys PrincessTower_1_1 at 528. Besides the usual events, the file lists each `pushback` request (the point pushed away from, where the unit stood, the target, the budget and whether it started) and each `relocate` (where the unit stood and where it was moved to).

## `golden/barbarians_pocket.json` - units created on the river

The same layout. The Barbarians of `barbarians_left` destroy PrincessTower_1_1 on tick 363. A second Barbarians card requested at (3500, 16000) on tick 400 is placed at (3499, 16500), on the bridge: with the tower gone, the pocket behind it leaves no column interval to clamp the formation into, and two of its units are created on river cells, at (4745, 16904) and (2253, 16904). They stay on the water through waiting, deploying and their first steps, and walk off it; nothing moves them. The king tower dies on 708 and stays in the holder: the units attacking it drop it through their own targeting and walk on, and the combat gate at the tail of its state visit switches it off on that tick, so it fires no more.

## `golden/bush_goblins.json`, `golden/brawler_goblins.json`, `golden/gift_knight.json`, `golden/abort_instigator.json`, `golden/witch_hooks.json`, `golden/tombstone_death_hook.json`, `golden/gift_select.json` and `golden/goblin_wave.json` - characters an action spawns

The towers fight at level 11. No object the battle has yet runs these rows from its hooks, so each file lists its `action_owners`: an object with a name, id (3000000, the area-effect band), side, position and packed level, and the rows scheduled on it in the command pass of their tick. `actions` lists every schedule, every run of an action with the pending pass it ran in, and every spawn: the child's name, id, where it was created, its state, deploy countdown, lane and hit points, and its position and state after its registration visit. The children's records follow in `unit_records`, with each one's elapsed time (`delay`), deploy countdown (`deploy`), whether it is still untargetable (`immune`), and `pending` on the record of the tick it was spawned in; `records` is empty.

- `bush_goblins`: a group on a bottom-side owner at (3500, 21500), scheduled on tick 0, spawns a Bush Goblin at (3000, 21500) on tick 12 and one at (4000, 21500) on tick 13, both deploying. The second is pushed to (4001, 21500) as it is registered. Each stays untargetable for six ticks, so PrincessTower_1_1 locks on the first on tick 19; they die on 70 and 118.
- `brawler_goblins`: the same on a top-side owner at (14500, 10500), four Goblin Brawlers on ticks 12 to 15 at (15000, 10500), (14000, 10500), (15000, 10000) and (14000, 10000): the side flips both axes of the location. The last two are pushed 150 as they are registered. They destroy PrincessTower_0_2 on 97 and the king tower on 236, which the combat gate switches off as it dies; PrincessTower_0_1 kills one on 262 and another on 415, and the other two destroy it on 436.
- `gift_knight`: a Knight spawned on its owner at (14500, 12000) on tick 5, walking at once: its registration visit takes PrincessTower_1_2 as its target and steps to (14518, 12056). The tower locks on 81, the Knight hits seven times from 195 and dies on 356.
- `abort_instigator`: the `gift_knight` run with three more `SpawnBrawler` schedules on the owner, two of them caused by the Knight (`instigator` on the schedule). The one scheduled on 346 runs on 356 before the Knight's cleanup and spawns a Goblin Brawler; the one the Knight caused on 347 is still waiting when the Knight dies on 356 and is dropped in that cleanup with one tick left (a `dropped` action event, with `ticks_left` and the queue after it); the owner's own, also from 347, runs on 357.
- `witch_hooks`: no action owner. A Witch_crazy_1 is placed directly for the bottom side at (14500, 3000) on tick 0, listed in `units`, and its row's starting action runs in its phase-1 pass of that tick (a `start` action event marks the schedule at placement). Its spawn group runs on 40 in phase 1, before the Witch moves, and from its interval on 180 and 319 in phase 2, after it (`interval` events with the counter after the reload). Each round spawns four Skeleton_EV1 around the Witch's position at that moment, deploying and targetable at once, and links each into the Witch's group right after it (`group_link`, with the group newest first); PrincessTower_1_2 kills six of them and each is unlinked as it leaves (`group_unlink`). The children are named from `Witch_0` as the battle names them; the generator numbered them from `Witch_1`. The Witch's own records carry only its outside: `delay`, `deploy`, `immune` and `pending` are null.
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
