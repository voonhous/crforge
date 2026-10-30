# The battle core's known assumptions

These are supplied answers, recorded on the classes that carry them and listed here so they are not mistaken for settled behaviour.

- A deploying character's targeting component is not visited. A card play's change into the deploying state ends with the combat gate, which switches it off until the gate that ends the deploy switches it on, after the moving state's entry.

  So a reference a taunt forces onto a troop that is still deploying makes it no route, and its first walking step searches one from where it was placed. Its movement component is visited but asks for no route and gets no speed, so only a push moves it.

  The registration visit is modelled for a spawned child. The one a card play's unit gets, over an index that is still empty, is not.

- The spawn runs schedule their rows on an action owner, an object that stands in for the area effects and event buildings that run them in the data. It has no update, lifetime or removal of its own.

  The spawner refuses a morph, a spawn for the other side, the ring's lane mirror and pushback, a ring around a character, a unit that paths to its spawn point, and a unit without hit points. A creation that ignores effects is the same creation, less the spawn effect, which is presentation.

  A spawn row that validates its point as a building's moves a blocked point to the first of the next nine half-tile rows of its column that frees it (the search steps down the arena for side 0 and up for side 1). When none does, the child is spawned from the point as it was asked for, unclamped, as a row that does not validate its point would spawn it: nothing checks the point again, and the spawner's own rules apply.

  The spawn action refuses every call after the spawn but the group link and the champion hand-over, which changes nothing about the champion.

  A spawned child that is still immune refuses every attacker, which is what a character asks. Whether an area of a projectile or an area effect is refused too is not established.

- A king tower's activation runs from its own starting row, built from the data. Its wait's condition is the data's expression, of whose functions the battle answers only the five it uses, the two co-op ones and the one that asks for a battle against the game's own opponent answering 0 in a battle of two players.

  The combat gate runs as the standard game's does, with the hit speed the king's buffs scale. The rest of the king's own state visit is not modelled.

  A building with no reference resets its attack only when it has hit points at the first level, as every tower has. No reference run reaches that branch, because a tower always has a default tower to fall back on.

- An air unit is created at its row's flying height and keeps it. Its layer is answered from that height, so it:

  - prepares a route of one node, the nearest cell within its attack range of its target
  - crosses water
  - meets in the push and avoidance passes only units on its side of height 0

  Its targets are paired by the validator's air and ground rules, and its shots start at its height plus the row's launch height.

  A row that flies direct paths heads, while it holds a reference, for the point at its attack range from the reference on the line to where it stands, instead of its route's cell, at its usual speed. A row that hovers is refused, and so is a swap to a row of the other layer.

- A unit whose row has a charge range tracks a charge from 0. Each step it walks in the moving state adds the step times 1000 over the range, so a full charge of 10000 takes ten times the range, whether or not the grid lets it cover the step.

  From the step that completes it the unit is charging. From the next, its targeting component's strike-now byte is set, and its budget is its speed times the row's charge multiplier from the next visit.

  Any other step - standing, attacking, stunned - drops the charge to 0 and clears the byte, except a jump's and an attack pushback's. Every switch-off of the targeting component - a stun's, a deploy's, a cast's - clears the byte too and leaves the charge as it is.

  On client 16.635.1 a pushback in flight no longer keeps the charge of a unit that cannot move. After the visit of a pushback in flight, under the global FIX_RESET_CHARGE_DURING_PUSHBACK_WHEN_MOVEMENT_DISABLED, which 16.635.3 sets, a unit with a charge whose movement is disabled loses the charge: its tag word holds NO_MOVE or NO_MOVE_ALLOW_ATTRACT, or its buffs scale its row's Speed of at least 1 to 0, as a stun does. A stun lands in the area effect's update, after every movement visit of its tick, so the reset comes on the next tick's visit. So a Zap during the evolved Battle Ram's recoil ends its charge. The 16.402.x data versions do not publish the global and keep the charge until the pushback ends. No reference battle holds the reset yet.

  So a Prince stunned in its river jump lands with its charge but strikes no charged hit at once. If it attacks before another charged step in the moving state, its standing step drops the charge and the hit is an ordinary one.

  The byte makes the attack-timer advance round up to a whole hit, so the first attack visit hits. A direct hit on a full charge deals the row's DamageSpecial at the level, its area carrying it, and resets the charge unless the row keeps it.

  A buff that gives a charge range, RecruitsCharge_EV1's OverrideChargeRange 250, starts the charge of a row without one:

  - Listing it resets the charge, from none to 0.
  - Every later reset leaves 0 while it is listed.
  - Each step adds the step times 1000 over the range of the first listed instance that sets one.

  Refused:

  - a completed charge's action
  - a charging row that fires
  - the charge buff on a unit that fires, its removal and a clone's copy of it
  - a swap to or from a row with a charge or a jump, or while the buff is listed

- A Kamikaze row's hit ends, after its direct hit or its launch and whether or not it landed, in the unit killing itself. It takes its whole hit points as one kill, with itself as the attacker on its own side.

  So its death action runs alone, and its death slot - the Battle Ram's two Barbarians - runs in the same pass. It leaves at the closing cleanup of the hit's tick, and a projectile it launched flies on without it.

  Only a hit the unit's tags forbid does not end so, and, on client 16.635.1, a hit discarded because its target left the attack range during the wind-up: under the global LOGIC_SKIP_KAMIKAZE_ON_DISCARDED_HIT, which 16.635.3 sets, such a hit skips its end and the unit lives on. The 16.402.x data versions do not publish the global, so their hits always end. No reference battle holds the skip yet.

  A child its death spawn makes with a deploy time faces the way it faced, which no run holds.

  A row with a Kamikaze time, the Skeleton Barrel, only marks its hit ended. It deletes no buff and kills nothing, and its hits go on landing.

  From the state visit of that tick, right after the not-attacking section, each visit drains its maximum hit points over its time in visits, at least one, with itself as the attacker. The drain is refused only where damage is forbidden and passes the battle's holds. The step that takes the last hit point runs its death as its own killer.

  Refused, either way: a Kamikaze row carrying a death-spawn buff, which the end would delete unspawned, and one with a shield of its own, or without hit points.

- A Goblin Hut's life state, its starting action, keeps a run of four words - a timer, the target's id, a lost byte and a spawn count. The run is started in the pending pass that starts it, twenty ticks after the play, and stepped by every run pass from that tick on.

  Its finder queries the index around the hut within its collision radius and range, testing a building by its square, through the row's filter. It takes the strictly nearest the hut's validator accepts, the earlier of equals. A target found while waiting is spawned at once.

  Holding one, each step adds what the hut's spawn speed makes of 50 to the timer. Then the keep test - the same reach plus the target's radius, at most, and the validator - lets it go as lost. At 2100 a spawn, the interval carried.

  Lost, a target found is taken without a spawn, and nobody by the interval sends the run back to waiting. A stun lets the target go and holds the timer. A target that leaves the battle marks the run lost through the notice every running instance hears, before the holder drops what the leaving object caused.

  Each spawn makes one SpearGoblin_Dummy 1200 toward the target:

  - turned 20 degrees one way for a target on the hut's right and the other on its left, and flipped every other spawn
  - kept inside the arena and off water
  - at the hut's level
  - deploying for its row's 500 ms, untargetable at first and queued with no registration visit

  A spawn due on the tick the hut's decay kills it still comes. The tag it sets and its effect rows only show something. Refused: a child that is a building, paths to its point or has a starting action of its own.

- A Goblin Cage's starting action, its shake while it has a target, has no perform but keeps a run. The run is started in the pending pass that starts it and stepped by every run pass while the cage stands, doing nothing and never finishing by itself.

  Which frames it plays, and at what priority, only its view reads. A row of its class that sets tags, a stop gate, a singleton or a chained action is refused.

  The cage is otherwise an ordinary building. It takes the nearest enemy ground unit or building in its sight, hits it for nothing every hit speed in range, is taken by enemy units as any building, decays, and leaves its Goblin Brawler on its point.

- An action's spawn row of the area-effect type, the Goblin Curse's GoblinCurseCore, creates its area effect at the point of the holder's owner. It does so for the side and at the level of the action's cause, re-based on the area effect's own rarity, and keeps the cause as the area effect's parent.

  It is handed to the holder in the pass that ran the action, so it is admitted at that tick's closing cleanup and first updates on the next tick.

  An area effect's hit action that is a group of buff spawns, GoblinCurseBase's, is scheduled with each hit on every unit its circle reaches, the area effect the cause. It runs in the tick's last pending pass. Each buff goes on the unit for its spawn time, at the area effect's level and for its side, so the curse and its damage over time are refreshed every tick while a unit stays in the circle.

  A unit that dies under the curse leaves its death spawn, GoblinCurseGoblin, for the other side, the caster's.

  Refused:

  - a spawn to a location
  - one that sets any other spawn column (the owner as the source, a level index, the offsets)
  - a spawn with no cause
  - an area effect that follows its target
  - a hit action that does not clone on an area effect no action made

- The Goblin Demolisher's trigger runs its group at half its hit points with the Demolisher as the cause: SpawnCancelTauntAEO at once, and the swap into its kamikaze form 100 ms later.

  CancelTauntAEO follows its parent. At each update it stands on the point of the owner whose action made it, and it ends at the cleanup where that owner leaves. Its Filter is read only on the Shape path, so it filters nothing, and its hit action reaches each unit once.

  Its one update schedules ResetTauntEffect, a taunt, on every own ground unit whose circle holds its point, the Demolisher among them. The taunt forces each onto the area effect's parent, the Demolisher itself. When the unit can attack it and is not dashing:

  - the Demolisher becomes its reference
  - LOCK_TARGET locks its selector from its next pre-hook and its re-selection waits 50 ms
  - GoblinDemolisher_ResetTargetBuff, whose LockTarget the selector also reads, goes on it with the Demolisher as its source

  A unit that cannot attack it, one that attacks only buildings, gets nothing and its run finishes at once. Whether a unit can attack the forced object reads the object's layer as it is then. One in the air is judged by the unit's air attack, unless FORCE_IS_AIR holds it there (a Demolisher an uppercut lifted), which is judged as on the ground.

  The run's next step clears the wait and, unless the unit is attacking, its reference, and every finish removes the buff.

  The kamikaze form is a walking row with a lifetime. The swap keeps the hit points and the level, and the hit points drain over the new row's lifetime from the next hit-points visit.

  Refused:

  - a taunt with any column but its valid duration and buff, or longer than one step
  - a taunted rider or carrier
  - a taunt that starts on a pathfinding unit, or steps on one routing across visible (a step on an underground unit, as a Mighty Miner switching lanes, lets it go at once)
  - an object to force onto that is not in the battle
  - a following area effect placed by no action, or one that chains another or pulls
  - one hit per target without a hit action
  - a swap away from a lifetime

- Dark Magic's area effect writes its starting and life-end actions inline, and its laser ball's three buff spawns write their buffs inline. An inline action is the actions table's row named after the row and the column, and an inline buff the buff row of its Name.

  The area effect's starting group runs the laser ball 500 ms after it is admitted. It is a run on its holder whose timer starts at the hit frequency less the first hit's delay, 0.

  Each step lists afresh the objects its query finds around the area effect's point - within 2500, a building by its square - through ForcedCharacterTargets for the area effect's team, in the query's order. Once the timer has reached 1000, it goes back to 0 and the run schedules on each listed object, the area effect as the cause, the buff spawn the count picks:

  - the strongest for one
  - the middle one for two to four
  - the weakest for five or more

  Every step then adds 50. So it fires on the 30th, 50th and 70th tick after the area effect is made, which leaves after the 79th.

  Each buff spawn runs in its target's phase-2 pending pass of the same tick, a princess tower's too. Its 100 ms buff, at the area effect's level, deals one hit two ticks later, on its second visit, which removes it: 340, 160 or 76 at level 11, and on a crown tower the per-hit column, 48, 25 or 17.

  The buffs are added as individual ones, so two Dark Magics firing on one unit on the same tick deal both hits. Its effects, and the area effect's own update, which never hits, change nothing the battle reads.

  A laser ball that keeps its detection, resets it after a hit, cools down after one, runs an action list on its owner, has no filter or runs on anything but an area effect is refused.

- Vines' area effect runs its selector 900 ms after it is admitted. It is a run on its holder that records the battle tick and is due on that tick, the next and the third after.

  On each due tick it queries its circle once - Vines_AOE_Shape, 2500 around its point, a building by its square - through enemy_troops_for_vines for its team. It picks the object with the most hit points and shield not picked before, an equal score going to the lower id. With nobody left the entry picks nobody, and an empty circle ends the step at once.

  The pick's Vines_Action_Group is built for it and scheduled with the area effect as the cause, and runs in its phase-2 pass: the air-to-ground run and the snare the pick's size selects, the five rows alike in the battle. The run finishes on its last due tick.

  The air-to-ground run:

  - holds a ground unit, a tower too, for 2000 ms, raising FORCE_IS_GROUND every step
  - pulls an air unit down in two steps, holds it at height 0 for 1900 ms raising the tag, and lets it climb back in one

  As the run finishes, an air unit's route is emptied (ResetPathAtEnd). So a route a ground search gave it while it was held, after a push for one, is not flown along, and its next route preparation makes it a straight air route again.

  A unit carrying riders, the Goblin Giant, is held like any ground unit. Its riders, which nothing targets, get no run and follow it.

  A hovering unit (the Ghost and its evolution, the Battle Healer) is held from the start for the whole 2000 ms, raising FORCE_IS_GROUND on every step, the last one too, and finishes there with no climb. It has no height, and the 1 up its hold pushes each step is clamped away by the fold. Under the tag it no longer hovers, so a push that leaves it on the river moves it off the water.

  The height changes it pushes are folded into the unit's height offset at its next pre-hook. From then on the pre-hook reads the unit's layer from its tag word. Under FORCE_IS_GROUND it is a ground unit to every reader, so a unit that attacks only ground units takes it. A second pick of the same unit starts its run's phase over.

  An air-to-ground row's action once on the ground (the evolved Royal Hog's fall: its grounded row, its landing area and a path reset) is scheduled on the unit, the unit its cause. It is scheduled at the end of the descent, or at the start when the unit is already on the ground, as an evolved Royal Hog Vines or an evolved Hunter's net holds is when a hit makes it fall, so it lands in the step the fall starts. A run that starts on the ground never descends, so it never schedules it again.

  A clone's run started by a cause, a character or a tower, whose own holder lists a run of the same singleton row copies the first such run, its phase and its counter. It runs at the height the clone's own row flies at, never a hovering unit's hold, and schedules no action once on the ground. That is the run a Clone's creator starts on the clone of a held unit (see the Clone below).

  Any other clone's run starts as any unit's does, whatever the clone's height or layer. Vines' cause is its area effect and the evolved Hunter's net's its projectile, neither a character, so a clone they reach is held as any unit is: one on the ground held where it is, one in the air (a cloned Baby Dragon or Skeleton Balloon) pulled down, held and let climb back, a hovering one held from the start. A clone dies at its first damage, so Vines' snare kills a clone it holds about 20 steps into its hold, before any climb.

  A clone has the row's action once on the ground, at the start or at the end of its descent, only when the row lets a clone trigger its landing actions (CloneTriggersLandingActions, true unless the row says not). The evolved Royal Hog's fall says not.

  The snare stops movement, attacks and spawns for 2000 ms and deals 153 twice at level 11, 38 on a crown tower, which drops its target and shoots nothing until the snare goes.

  Refused:

  - a selector that waits, pauses, runs actions on its owner, scores by maximum or distance or has a shape other than a circle
  - an air-to-ground row with landing actions or a path reset at landing
  - a held rider
  - a clone's action once on the ground when its row lets a clone trigger it, which no row does
  - a hovering unit a force tag already holds as its run starts, which the run would hold as a plain ground unit

- The Goblin Machine's starting action, goblin_machine_rocket, keeps a run on the machine, with its own load, cooldown and attacks. The run is started in its phase-1 pass of the play tick and stepped by every run pass. It asks the targeting component only whether it is on, which it is not while the machine deploys or waits to.

  Each step adds the step its hit speed makes of 50 to the load while the component is on. NO_ATTACK or the component off ends every attack, ending its signal, and holds a started cooldown at 0.

  Past LoadTime 1500, with no attack and the cooldown out, the finder queries 5750 around the machine's point, a building by its square, through DefaultCharacterTargets. It keeps the objects whose centre lies 2500 to 5000 beyond both radii, ends included, and takes the least squared distance less the const-priority offset, the earlier of equals. It asks no validator, so air units and buildings are taken too.

  The signal, an area effect that does nothing, stands at the target's point for the machine's side and level with the machine as parent, and goblin_machine_rocket_load runs on the machine. AttackDelay 1000 later:

  - the rocket leaves from the machine's point plus its facing scaled to -1200, at height 5000, for the signal's point, which its flight never re-aims
  - the cooldown restarts
  - UNIT_CUSTOM_TAG_1 is on the run until its next step, read only by that effect's stop

  The step after the rocket has gone ends the attack and its signal.

  The targeting visit turns a unit running such an attack toward its reference as each attack starts, as its special loads and as its dash winds up, the facing the shot reads. Every other unit's turn is left out, as the reference does.

  The machine leaving the battle stops the run after every notice of it: its attacks end and a rocket in flight still lands.

  The signal is handed over with the machine's id filed beside it. So a machine that leaves in the cleanup that would admit its signal, killed in the step it made it, has the signal let go by the fold: never admitted or started, its id spent, nobody told and no rocket (goblinmachine-signal-left-v1).

  Refused: a turn toward no reference, and a clone running the attack. The step under a hit-speed buff is translated but held by no reference.

- The Berserker's starting action is written inline in its row, as an ActionBerserk with no column, and is the actions table's row named after the unit and the column, `Berserker_OnStartingAction`.

  Its run, started in the pending pass that starts it, sets the attack sequence index to 0. Every attack that lands then tells each running instance of the unit, first to last, that it ended. The Berserker's run sets the index to 0 when it is above 0, else one up, so it runs 0, 1, 0, 1.

  Both stores keep the index below the length of the order and ignore whether the targeting component is on. Its step does nothing and it never finishes by itself.

  The Berserker's three entries are equal, Damage 40 and HitSpeedMultiplier 100, so the damage of each hit and the timer of the next attack are a plain attack's. Its row's own Damage is absent. Its AnimationsKeepLastFrame only shows something.

  Refused: a row of the class that sets any column besides its class, a run started beside an enchanting buff, and any other starting action written inline.

- The Little Prince's starting-attack row, LittlePrinceChooseAttack, reads attack_count: the unit's attack time over its row's HitSpeed, toward zero, whether its targeting component is on or not. So it is the hit count of the current attack however fast the hits come.

  - At 0 the row sets the index to 0.
  - At 3 it puts LittlePrinceLvl1 (HitSpeedMultiplier 200) on the unit.
  - At 6 it puts LittlePrinceLvlMax (300) on it and sets the index to 1.
  - At 7 it sets the index to 2.

  Each buff goes on for 999999 ms with the unit itself as its source, and a second spawn of a row still listed refreshes it. Each lives while its AliveIfTrue holds. The buff visit asks it of the carrier after the step of an instance with time left, and an answer of 0 ends the instance on that visit.

  So Lvl1 ends at the sixth hit and LvlMax when the attack time is reset, and at HitSpeed 1200 the hits come 24, 12, then 8 ticks apart. The three entries are equal, so the index changes no hit.

  A kill keeps the ramp when the next target is in range. A target out of range clears the attack time, and so does a stun, the row restarting its hit timer when it gives its reference up (ResetHitTimerWhenNoTarget).

  A stun also starts the ramp over. Every combat gate that switches an entity's targeting off, whatever the reason, raises COMBAT_DISABLED for one step, and the Little Prince's constant ticker (LP_CheckCombatDisabled) sets LP_AttackCount to 0 while he carries it. tv_replay_013 holds it (a stunned Little Prince's fourth shot after the stun comes at his base hit speed).

  Its AnimationsKeepLastFrame only shows something, and a card play never requests its ability.

- Goblinstein is a group card. Its construction links each unit it makes after the one made before it, the monster first and the doctor after it, and a unit leaves the chain as it is released.

  The doctor's starting action, written inline as a spawn of AOE_goblinstein_ability, is the actions table's row `goblinstein_doctor_OnStartingAction`. The area effect follows the doctor and never hits, and its starting action, goblinstein_ability_action, keeps a run on its holder.

  - The run's first step connects to the first unit of the doctor's chain, or the one after it when the doctor is the first.
  - Every later step waits for the doctor to cast its ability, then for the cast to end, and then runs the tether for TetherDuration, eighty updates.

  The tether's first update schedules the activation rows on the area effect and on the connected unit, the area effect their cause. A damage pass runs on it and every TetherHitInterval after, along the segment from the area effect to the connected unit.

  The pass's query walks the index's buckets over the segment's box widened by TetherWidth, visiting each object once. It takes those the TetherDamageTargets filter passes that lie within the width of the segment, a building by its square and anything else by its circle.

  Each touchable one with hit points takes TetherDamage, or TetherCrownTowerDamage on a crown tower, at the area effect's level and rarity, as an area effect's hit with the hidden test lifted. The hit is queued for the damage drain after the post-hooks, as the evolved Battle Ram's carried push queues its hit, so an object it kills dies at the drain and its death action runs in the tick's last pending pass. Each also takes TetherHitAction once until the list of objects it reached is emptied every TetherHitActionInterval.

  The tags the tether sets on both ends are read by no battle code and are not set.

  When the connected unit leaves, the run makes dead_goblinstein at its point, once, for the area effect's side and at its level, the area effect its parent, and holds it, so a later tether runs to it. It never hits, and its looping effect only shows something.

  As the area effect leaves, before any notice of it, a death area it holds ends and leaves in the next round of the same cleanup. The doctor's leaving ends the area effect the same way, and the monster is untouched.

  The monster's starting group lists a card-play listener, made after the play that made it, so it never hears that play, and a health-bar run with a duration, which shows something.

  The listener hears every later play of its side. The card the play put down, a Mirror's repeated card or the option a Merge Maiden was played as, is tested against the playable cards of its CardGroup, Goblinstein's being Goblinstein alone.

  - With its count at ElixirCost, 0 for the monster, the play schedules OnActivateAction on the monster, goblinstein_set_custom_tag, a tag no battle code reads, and takes the cost off the count.
  - Below it, the play adds the card's cost.

  A listener without EvaluateDeployedCard tests the card played, the Mirror or the Merge Maiden itself. No shipped card group lists the Merge Maiden or either of its options, so no listener hears its play: the Goblins hero banner stands and the hero goblins stay untagged through it.

  The champion of the card is the doctor.

  A Mirror of Goblinstein places a monster and a doctor of its own, one level above the Mirror, the doctor deploying at once as the Mirror's rows stagger nothing. The construction chains the units of each play apart, the item's repeated card being the card it makes, so the copy's doctor ties to the copy's monster whether the first pair lives or not.

  The first monster's listener hears the play as a play of Goblinstein and schedules its tag. The champion's slot follows the copy's doctor, so a command naming the first doctor is refused.

  Refused:

  - a variant card's play heard by a listener whose count is below its cost, whose elixir added to the count is not established
  - an inline starting action other than a bare Berserker's or a bare spawn of an area effect

- A champion's ability is used by its player's command, which names one unit and runs, as a card play does, in the command pass 20 ticks after it is given, in order with the plays.

  Each king has two champion slots, runs of the row SUMMONER_CHAMPION_ABILITY_ACTION names, made as a match is set up. The deck pass then walks the deck from its last card and gives the first champion it finds to the first slot, with its full cooldown.

  A play of a card that summons that champion makes the slot follow it, by the king's count of card plays before it, which the play's units carry. Its charges are refilled, unlimited for a row with none, its cooldown cleared and its state deploying.

  The command's gates, in order:

  | Gate | Code |
  | --- | --- |
  | the king alive | 0x3ec |
  | the unit found, in the live list or as the side's first character of its row and play, and followed by a slot | 0x3ee |
  | the cooldown out | 0x3ed |
  | a charge left | 0x3f6 |
  | a champion's ability | 8 |
  | nothing pending | 0x3f1 |
  | no clone | 0xa |
  | not disabled | 0x3f5 |
  | the whole elixir covering the cost | 0x41a |

  A frozen or deploying champion passes. A refused command changes nothing.

  One that passes takes the cost from the king, then every run of the king, last to first, hears it. The slot following the unit's row:

  - requests the ability of each live copy
  - opens the refund window for the ability's RefundWindow (its trigger delay for a row without a positive one, as the loader sets it)
  - starts the full cooldown
  - spends a charge if it counts them

  Each king run pass steps each slot:

  - Its live copies are rebuilt from the live list, last to first - the side's characters of the followed play, not clones, whose row names the champion's ability row, so a hero's other form with the same ability stays a copy.
  - Its state is worked out.
  - Its cooldown goes down 50 unless a copy holds its ability pending or carries the pausing tag.
  - Its refund window goes down 50 while a copy casts (in the casting state or carrying CASTING_ABILITY), held while none does.

  With none left inside the window the cost is given back once, however long the window held. A copy whose ability waits and never casts keeps it open until it dies.

  An ability row with RefundUntilDelivered (client 16.635.1; no row of the 16.402 tables has it) opens no window: the slot's window is shut at the use and the cost rides on the unit paid for. An ActionAbilityDelivered on a character takes it off. A unit that leaves the live list with it still on (the cleanup's removal or the removal at once) gives it back to the king of its side, capped as the slot's refund is, before its running actions hear that it leaves. A building paid for such an ability is refused. Traced, held by no reference yet.

  The Archer Queen's ability fires TriggerDelay / 50 - 1 visits after her cast starts and applies ArcherQueenRapid to herself for 3500 ms, at her level, she its parent and source. Her attack restarts from its load time after the cast and steps 140 a visit, and she walks at 75 percent. While it lasts every character asker drops her at its next check, though an arrow in flight lands and area damage reaches her.

  Its NotCloned is read only by the clone creator. A clone of a unit carrying such a buff is refused.

  A deck with two champion cards, which only another mode deals, is refused.

- A player's command given through `PlayerCommands`: the step that drops the promise of a play refused at its run is not traced to the tick. So a giving whose outcome a promise within two ticks of its expiry decides is refused as not modelled, and so is a Mirror whose item would repeat a waiting Mirror's or variant card's play.

  The screen's placement search at the drop is handed the row of the dragged hand card, inferred. An evolved or hero play's row and a variant card's option are taken as the run places them. A Mirror is taken as its own row, which the search places as the king's last card, so a Mirror finds no tile while its king has none.

  A Mirror whose item repeats a play its client lists (waiting, or refused at its run with its promise kept) is refused as not modelled when that play's card and the king's last card would be sent on different tiles, a Mirror given while its king has no last card and a play of its side waits among them.

  The screen's cancel of a card dragged back over the hand is a gesture, not a point, and is not modelled. No recording holds the giving: recordings carry only the points the clients sent. The screen's refusal of a card whose play waits is held by the recorded Ladder battles, which never play one, not by the client's try.

- The Little Prince's ability keeps his target through the cast, so his attack time and his speed-ups wait out the 18 visits.

  Its handler runs his wait, LittlePrinceWaitGuard, which carries NO_MOVE for 1500 ms. With it, as its next action does not wait, the handler runs the area effect DummySpawnLittlePrinceGuard at his point, for 1000 ms, never hitting. 850 ms later its starting action, a guard spawn, makes ChampionGuard.

  The guard appears 2000 behind the area effect's point, toward its own side, moved off water and kept inside the arena, at the area effect's level re-based on its rarity. Its run is listed on it, carrying NO_CHECKCOLLISIONS, NO_CHECKAVOIDANCE and NO_BUFFS and, while more than one step of the deploy is left, NO_SHADOW.

  It is registered with its registration visit while still walking, faced toward the area effect's point and put into its 300 ms deploy, whose entry ends with the combat gate, dropping what the registration visit selected.

  On the step that finds 50 ms left the run asks the object query around the guard and cuts the deploy short. It dashes to the area effect's point 4000 ahead, toward the enemy side, clamped into the arena, with no reference and stopping by its collision radius alone.

  On that step and every one after, until the step after its dash, it pushes every enemy ground character within 2500 away from the guard to the edge of that circle. The request's gates are skipped and a longer push in flight is kept. It hits each once for the push damage at the guard's level. The guard is then an ordinary unit.

  Refused: a guard spawn with tags, a singleton, a next action, a gate or a phase of its own, or no filter, one on anything but an area effect, and a guard that starts an action.

  While the run is listed its NO_BUFFS turns every buff away from the guard, as the tag does for any entity, though none reaches it in the runs. ChampionGuard's IngamePathfindVisible and DeployTimeChangesDeployAnim are read by no path the battle takes.

- The Boss Bandit's ability has two charges a deploy. Each use spends one and starts the full 3000 ms cooldown, and nothing gives one back but a new play of its card. So a command during the cast is refused with 0x3ed for the cooldown and one after the second cooldown with 0x3f6.

  Its handler, three visits after the request, runs its activation group in the phase-3 pass: BossBandit_ability_buff, Invisible for 1000 ms, and its ability run. The run reads the battle tick and warps 14 ticks later. The run:

  - asks the battle's target locks at once for the unit on itself, on channel 0, granted after phase 3, and claims the lock on its next step
  - on the warp tick schedules its warp row on the unit, which the unit's phase-2 pass of the same tick runs, on the last tick of the cast
  - one step later finishes and releases the lock, which the next pre-pass drops

  The lock locks nothing of the unit's own. A Fisherman's hook reaching the Boss Bandit while it holds it does not hook it, as a hook on any unit another holds does not: it ends as an ordinary arrival.

  The warp moves the unit 6000 toward its own side in one write, clamped into the arena. A water or blocked landing cell is replaced by the nearest allowed cell of its column within four rows, the nearer half-row neighbour first, at that cell's centre. Then its pending damage is reset, its route emptied and its reference dropped, and the cast ends on the same visit.

  The reset, the flying warp's start's alike, drops the unit as the target of every projectile aimed at it whose row allows it, which flies on to where the unit stood and lands on nothing. A projectile whose row keeps its target through the reset, the Royal Chef's pancake, keeps the unit, goes on re-aiming at it wherever the warp takes it and lands on it. It hands back its part of the emptied amount, 0 for the rows that keep their target, which deal no damage.

  Refused:

  - an ability run with tags, a next action, a gate, a speed byte clear, no warp row or a release in the warp's own step, or on anything but a character
  - a warp in another mode, with a speed or the flying warp's columns, on anything but a character, or with a projectile aimed at the unit whose row keeps its target and has damage on its way, whose hand-back from the emptied amount no reference holds

  The wait while the unit dashes is read but never reached in the runs: the warp tick falls inside the cast.

- The Boss Bandit's row runs its killed-done action, BossBandit_won_against_bandit_check, on each kill it makes, and its killed action, BossBandit_defeated_by_bandit_check, as it is killed. Each checks its cause against the Bandit and ends in a voice line, which only shows something, so nothing reaches the battle.

  Its dash is the Bandit's, with DashDamage 192 (491 at level 11), and its DashFilter only shows something. Its starting group checks the friendly troops for its greeting and ends in a voice line too, and a card play never requests its ability.

- The Golden Knight is placed as a plain melee unit: its dash columns are read only on a path its ability opens.

  Its ability, GoldenKnightChain, has one charge and an activation action. The tap runs the activation group, whose selector, GoldenKnight_Charge_Target, waits under the charge buff for the closest enemy ground character within 5500. It then runs the dashing attack chain, which dashes the knight at that character, stopping short by its collision radius.

  Each dash start counts the chain, lists the reference among the chain's hits and keeps the first dash's vector. Each landing hits the reference for DashDamage with no push.

  As the unit leaves the dashing state two visits after a landing, with fewer than DashCount 10 dashes made, the chain's next dash starts at the nearest valid neighbour of the landing point within DashSecondaryRange 5500. The current reference and every target already hit are refused, unless the current reference is a crown tower. The unit stays dashing, and stops at the target's point.

  With no next dash the dash end clears the chain's count and first vector, empties the hit list and drops the reference. The same reset runs as its targeting component is switched off.

  The unit is untouchable throughout, and the push pass neither pushes it nor lets it push.

  Translated but held by no reference: the later of two equally near objects, and the forward test a back-dash radius below the query radius would apply, which the Golden Knight's never is.

- A unit whose row jumps the river routes over water at the water cost, 7 against the land's 8. When the node after the one it reaches is water, its route becomes the one node of the first land cell beyond it, the direction pointed there, and it asks for the jumping state, which its setter applies at once.

  It flies there at its jump speed, whatever its own, with its arc kept in the movement component's samples and its height left at 0. It asks for the moving state once fewer than two steps remain, whose entry prepares a route from where it landed.

  While it jumps it takes no part in contact, attacks nothing, and only an attacker that attacks air may target it.

- A battle played as a Ladder match reads the Ladder game mode's battle timeline and start cooldown and the published MAX_MANA.

  Set up before the first step, side 0 first, each king starts with the timeline's starting elixir, in ten-thousandths. Its deck of eight is dealt in the order a Mersenne-twister variant draws, seeded with one draw of the battle's source below 0x0fffffff plus the player's word. Four cards are drawn first, a card kept out of the opening hand held back until they are drawn. The first four are the hand.

  Each step the update sets whether the crowns are equal and advances the timeline to the battle tick, before the entity tick. In its post-hook each king first refills its hand - once the cooldown has run out, the first empty slot takes the queue's front and the cooldown restarts - then regenerates `MAX_MANA * 500000 / ElixirFullBarMS` a step, 178, 357 or 537, up to the cap.

  A card play is refused by the match before its placement with:

  | Code | Refused for |
  | --- | --- |
  | 8 | a dead king |
  | 9 | a card not in the four slots |
  | 0x16 | a tick below the game mode's start cooldown (the row's BattleStartCooldown, or LOGIC_BATTLE_START_COOLDOWN where Ladder leaves it empty, over 50: 90) |
  | 0xd | a cost above the whole elixir |

  A play placed or cast takes its cost and sends its card to the back of the queue before anything is made.

  The crowns a side has taken are 3 when the other king is dead, else the princess towers the other side has lost, from the cleanup that removes one.

  The match is decided when a king has fallen, overtime sees a crown or the time is up, asked at the head of each step and after the entity tick. The first time, it ends: the timeline freezes, the winner is the side with more crowns, and the end timer starts at 1.

  Every play is then refused with code 4. The entities are ticked while the timer gains 50 an update, the update that takes it to the location's end screen delay of 4000 only cleans the holder up, and from the next step the battle runs nothing.

  From the update after a king falls, a circle grows from it to the arena's length over 2000 ms and kills every living entity of its side it reaches, told to observers as the circle's kill. From the end every attack timer is held at zero and every ordinary hit is refused, so nothing fights on. Only the circle kills.

  The end screen delay is the one every location shares, since the battle's location is not read.

  A match decided with equal crowns - the time up, or both kings fallen together - goes to the tiebreaker, which replaces the step. Each of its steps adds 50 to its time, and from its first every ordinary hit is refused. While its time before the step is at most 1450 it clears the field and runs the update, with its entity tick, only on a step whose clearing removed or killed something.

  The clearing walks the holder's live list in id order once:

  - A projectile is removed from the holder at once.
  - An area effect has its life ended and is removed at once.
  - A character without hit points is removed at once.
  - A crown tower is left standing.
  - Every other character is resumed at once and its kill queued for the damage drain, as a fallen king's circle's is.

  Each removal is taken as a cleanup's removal takes one, every listed entity told of it.

  So a character whose kill is queued lives through the passes of the update the clearing runs. It takes that update's step (unless its speed budget is 0, as for an Executioner whose axe is out), a tower aiming at it still attacks it, and its death spawns are made where the step left it. It dies at that update's drain.

  The kill carries the hit points the character had as the walk reached it. The drain's damage entry lowers it by the character's damage reduction, floored at 1, as it lowers every queued hit. So a character with a reduction listed (the evolved Knight's, the hero Valkyrie's, the Monk's) lives through it with the reduction's share of its hit points. One a heal over time raises in that update's passes keeps what the heal gave (the evolved Bats). The next step's clearing reaches it again.

  The other queued kills - a fallen king's circle's, a Kamikaze unit's of itself, a kill action's - carry their target's hit points as they are queued and pass the same entry.

  A removal moves every object after it up one place while the walk steps on, so the object right after a removed one waits for the next step: a volley in the air goes every other projectile a step.

  The tiebreaker then runs only the commands, every play refused, until the step that begins at 3250 ms. From then each step drains every princess tower and both kings, side 0 first, by the lowest tower's hit points:

  | Lowest tower's hit points | Drain a step |
  | --- | --- |
  | below 21 | 1 |
  | below 200 | 10 |
  | below 500 | 20 |
  | below 1000 | 40 |
  | not below 1000 | 50 |

  When a tower reaches 0 or both sides' lowest are equal, the holder is cleaned up, and the next step ends the match by crowns. Equal lowest towers end it a draw although Ladder allows none. The commands run last in each of its steps.

  Refused: a character of the neutral side, which the clearing only counts, and the stand-in owner of actions, which stands for an object the battle does not have.

  A Mirror plays its side's last card again. The king keeps the last card played that was not a Mirror, and its post-hook copies it after the refill and the regeneration.

  A Mirror's play carries an item the player's client builds from that copy: the card, at the Mirror's level plus MIRROR_LEVEL_OFFSET (1, not capped), for the Mirror's ManaCost plus the card's, at most MAX_MANA. The gates read the Mirror's own hand slot and the item's cost, so a Mirror after a Knight needs 4.

  The card is then placed or cast as itself at the item's level, and the play takes the item's cost, starts the card's production stop and sends the Mirror to the back of the queue. The last card stays the one repeated, so a Mirror after a Mirror repeats it again.

  A Mirror with nothing to repeat finds no position (0x17), nothing taken. A Ladder deck never holds one before a play, as the shuffle keeps it out of the opening hand.

  The battle builds the item as the play runs. The player's client builds it as the play is given, from the play of its side given last of those it still lists as promised, else from the king's copy of the last card. A play is listed from the step after it is given until its run places it, or, refused at its run, until 60 ticks after its giving. An ability command's promise names no card.

  So a play given the tick before the Mirror is listed. The client files its promise in a staged list as it is given, and the step that moves the counter on to the Mirror's giving appends it to the list the item reads. The battle's own update runs its steps and then the hand, which builds each card's item again, so the item the Mirror is given with has seen that step. A play given in the Mirror's own tick is still staged.

  A listed play the match placed is the last card by the Mirror's run, so a Mirror given while it is pending repeats it. A listed play the match refused is repeated in place of the last card, its card and evolution field.

  Refused:

  - a Mirror given in the tick of another play of its side, which no recording holds
  - one given within two ticks of the drop of a refused play's promise
  - one whose client would repeat a Mirror's play or a refused variant play
  - a Mirror outside a match
  - one that repeats a champion

  A variant card, the Merge Maiden, is played as one of its options, in their order: the mounted maiden from an AvailableManaTrigger of 6000, the maiden on foot from 3000, each stored ten times over in ten-thousandths.

  The player's client picks the option as it gives the play, from the king's elixir less the costs it has promised, held to MAX_MANA and truncated to hundredths. It picks the first whose trigger it reaches, else the last, so the mounted maiden from 6 elixir and the maiden on foot below. UseProjectedTimeSummon adds the option's PrecastPendingTime over the milliseconds an elixir takes, 0 at 1x and 2x and 1 at 3x, which moves no pick.

  The play carries the option and the option row's ManaCost, 6 or 3. The gates read the Merge Maiden's own hand slot and that cost. The option's row is placed and cast as any troop card, and the play takes the cost, starts the option's production stop and sends the Merge Maiden to the back of the queue. Nothing changes a maiden once it is made.

  A play that carries its item, as every replay's play does, runs as the option the item names, as the game's battle plays it. The client picks it at its last hand refresh before it gives the play, an elixir reading the replay does not time. A few captured plays (three of the 16.402.21 coverage battles) carry the maiden on foot where the elixir after the step 22 before the run picks it mounted, read up to five steps earlier.

  A play with no item, as the debug screen gives it, is picked by the battle after the step 22 before the play's run, one step older than the step before the play's tick. All other captured Merge Maiden plays agree with a pick there. Three picked on foot rule out the step after it (the elixir it left had reached the mounted trigger), and two picked mounted rule out the step before it.

  Each play the client gives promises its item's cost, held apart until the next step and then counted until the play's run pays it, or until 60 ticks after its giving for a play refused at its run. So the pick sets aside the item's cost of each play of its side given two ticks or more before the pick's tick and not run before it.

  A play that runs in the pick's own tick runs before the pick, queued first. Placed, it has paid its cost, which the client, reading the king before that run, held back instead, so it is not set aside again. Refused at its run, it paid nothing and is set aside.

  The promises and the refusals below are the pick's, so they hold for a play with no item only. An ability command promises its ability's cost the same way as it is given, and its run releases the promise before its gates, so a command that has run holds nothing back, whatever it came to. A variant play whose option the cost of a command of its side still pending would change is refused, as no recording holds that cost set aside.

  Also refused:

  - a variant play given in the tick of another play or ability command of its side or within the two ticks after, where the client's moment decides whether that cost is counted yet
  - one given in the tick after an ability command of its side runs, which runs before the pick
  - one with a Mirror's play of its side pending
  - one within 62 ticks of a play of its side refused at its run
  - one outside a match
  - one run before tick 22

  A Mirror after a Merge Maiden repeats the option it was played as. The Merge Maiden's row sets MirrorUsesRootSpell false, so the Mirror repeats the row its play was cast as rather than the card itself, as it does after a hero play.

  The item names the option's row, at the Mirror's level plus the level offset, for the Mirror's cost and the option row's, and the option's row is placed and cast as any troop card. Unset, the column leaves the card itself. A variant card repeated as itself, whose row casts no option, and an evolved play repeated as its evolved row are in no shipped row and are refused.

  A deck marks its evolution and hero slots, which are where the deck puts them. Each deck card comes with two flags, the evolution slot and the hero slot, and the battle only reads them. The king keeps one count per deck index, 0 at the battle's start.

  A play of a deck card carries an item built from that count:

  - evolved when the card's evolved row - the first row of its EvolvedSpells in the evolved form, a row of the evolved cards unless it names another form - has a DarkElixirCost of at least 1 and the count has reached it
  - else in its hero form for a hero slot's card
  - else neither

  The play is gated on and spends the ManaCost of the row it is cast as, the card's first in that form, starts that row's production stop, and places and casts it as any troop card at the card's level. The hand cycles the deck card, and the card-play listeners hear the deck card played and the row put down.

  A play of an evolution slot's card then resets its count after an evolved or hero play and otherwise adds one while it is below DarkElixirCost. So a Knight whose Knight_EV1 costs 2 is evolved on its third play and plain on its fourth. DarkElixirCost is never spent, and every evolved and hero row costs its card's ManaCost.

  A card in both slots is played as its hero form while its count is short, and that play resets it, so it never evolves. The champion deck pass asks a hero slot's card in its hero form, so its king's slot follows the hero, and a hero play is followed as any champion's.

  A Mirror after an evolved play repeats the deck card itself, after a hero play the hero row, which is a champion and refused. The marks an evolved or hero play leaves on its objects and its king are not carried: no battle code reads them.

  A spell or building card in the evolution slot is counted and cast as a troop card is. The item, the count and the row cast do not ask which kind of card it is, and the evolved row is refused as it is cast when it sets something its kind's cast does not model.

  A spell's action on its cast (OnExecuteAction, only the evolved Goblin Barrel's) runs as the cast's last step on the side's king tower at once, with the first object the cast made as its cause. Its mirrored extra spell throws its projectile for the cause's side and level from where the cause stands, at its height, to the cause's aim (its target's position when it homes onto one) turned over across the arena's width. It throws with no delay, handed to the holder after the cast's own. A mirrored extra spell whose cause is not a projectile is refused.

  A spell card in the hero slot is played as a troop card there is: its hero row is cast, and the champion slot follows the champion that row links. The Barbarian Barrel's hero projectiles hold the slot's button state ready while they fly and roll. A projectile's override is performed through the king that cast it, and its run writes by its own side. Its expressions read its own variables.

  The barbarian its roll leaves carries the play, so the slot follows it, and starts its actions as the holder admits it. The barbarian's ability rerolls it. It backs off toward its own side over the spawn delay, then rolls forward in a barrel it launches and follows a step behind, healed by half its missing hit points and raising NO_DAMAGE, UNTARGETABLE and NO_ATTACK among its rolling tags each step. It deploys again where the barrel ends, its path and target reset.

  The damage entry refuses an entity whose tag word holds NO_DAMAGE. A deflected roll is refused.

  Refused:

  - a hero slot on a building card or on a spell card with no hero form
  - a deflected projectile whose impact would make its deflected spawn
  - slot flags on the Mirror or a variant card
  - two copies of an evolution slot's card in a deck, which no reference holds
  - a play of an evolution slot's card while another play of it is due, as the client builds the item from the count before that play runs and the battle builds it as the play runs

  An evolved buff's action on a reduced hit is carried when it only plays an effect that keeps no run, the evolved Knight's protection: such a row makes no run, so it is not scheduled.

  Units pay into the kings' elixir:

  - An Elixir Collector's state visit, right after its goal-row test, counts half the spawn step a visit from its deploy's end. At its generation time it pays its own king the row's amount - but only while that amount and the king's whole elixir come to at most MAX_MANA. Otherwise its timer is held at the generation time and it tries again each visit.
  - A player's unit's death pays ManaOnDeathForOpponent, which the loader stores times ten, in ten-thousandths to the king of the side of the killing hit - the attacker's, or for damage over time the side its buff was applied for. A kill with no side pays nothing, and a lifetime's decay does not reach the death handler.
  - The death slot pays a dying object's ManaOnDeath, in whole elixir, to its own side's king, whatever killed it, a lifetime's end included: the Elixir Collector's 1 as it dies.

  Every add clamps at the cap and count the rest as wasted. Outside a match no king holds elixir: a death pays nothing and a collector is refused where its block first runs.

  Refused: a row with a limit on its generation (ManaGenerateLimit), whose remaining count's writer is not read.

  A building card is played from the hand as a troop card is, its unit a building, so a collector may be played or placed directly.

  The players' word is supplied as 0 and the battle's source starts from its default state. What a server battle starts them from is not established.

- A Path of Legends replay, game mode Ranked1v1_NewArena, is played as a Ladder match. Its game mode row differs from the Ladder row only in the card level adjustment (Tournament), the fixed arena and whether it is a Ladder mode, and the replay mapping checks that the tables still say so.

  Its replays write game mode type 13, level cap 16 and trail -1 in the battle header, which the mapping pins. They also carry three fields with no battle input:

  - the header's `rkd`
  - each player data's `sa` (the battle's arena)
  - each avatar's `rnk_sc` (the player's ranked score)

  Recorded battles of a Ladder replay with that header play exactly as the Ladder replay: cards, princess towers and kings at levels below and at the highest, 16.

  The header is a battle input all the same. In the recordings:

  - a level cap of 11 brings the princess towers down to level 11 under either adjustment, and the kings too under Tournament
  - game mode type 1 with trail 170000000 brings a Tournament king down to level 11
  - the cards are played at the level their play's item names

  So a level cap other than 0 for Ladder and 16 for Path of Legends, and any other game mode type or trail, stays unsupported.

- A challenge, game mode Challenge_AllCards_EventDeck_NoSet, is played as a Ladder match under its event (client 16.635.1). Its game mode row differs from the Ladder row in the card level adjustment (Friendly) and in columns the battle reads nothing of, which the replay mapping checks the tables still say:

  - the deck selection, EventDeck: every battle rule that reads the deck selection tells apart only a draft, a pick and predefined decks, so it answers EventDeck as it answers Collection, and the event the battle is played under is read only by a draft
  - the icon, the text id and whether trophies are shown, presentation
  - whether it is a challenge mode, matchmaking

  Its replays write game mode type 16, level cap 0, tps 2 and the event's trail and id, and `seb` true. The game reads tps only to ask whether it is 0, and `seb` only to write the header out again.

  Under the Friendly card level adjustment the game sets every level itself, whatever levels the replay writes. Each side's king and towers, princesses or a tower card alike, stand at the SummonerLevel of the exp level the global TOURNAMENT_MAX_EXP_LEVEL names, and each deck card at its rarity's TournamentLevelIndex (plus its RelativeLevel, plus 1). In the recordings, changing the replay's king levels, tower selection levels or deck card levels leaves the battle unchanged, and the same battle under the Ladder row puts the kings back at their own levels. A card level minimum other than 0 stays unsupported, as does a challenge without an event or under another card level adjustment.

- A unit takes its row's TargetOnlyTroops into the validator, which passes over a building, and its IgnoreTargetsWithBuff and DeprioritizeTargetsWithBuff into the validator and the selector's priority.

  The one buff a row names there - the Ram Rider's BolaSnare - is one the rider ranks lower rather than passes over. Its priority asks each candidate's buff list for an instance of that row, and prefers a troop without one. Every hit such a row lands drops its reference, so the rider ranks the candidates again on its next targeting visit and turns from the Knight it just snared to one that is not.

  A bola landing on a target another hit killed earlier in the same damage drain drops nothing. The hit points take nothing from a target already below 1, and the attacker's hook is not called. The rider then still holds the dead target when the cleanup removes it. Unless it kept that target only for the lethal damage of its own bola in flight, it holds its attack for the attack finish time like any other attacker.

  A buff some character passes over outright would be read by the validator, and is refused wherever it is applied. No row does.

  The rider's bola, a homing shot that slows its one target after its damage, names a column for the flight back to a shooter that walks on. The battle logic never reads that column: it is presentation.

- A unit whose row has a dash cooldown dashes at a reference inside its dash ring - beyond its own collision radius plus its least dash range, within its greatest dash range of the reference's edge - while its speed through its buffs is at least 1.

  Its wind-up runs for its cooldown, 50 ms a visit scaled by its buffs, and holds it still. A reference that leaves its greatest dash range plus 1000 meanwhile is dropped.

  A pushback that lands during the wind-up resumes the unit - moving when it may hold a route, else standing - so a unit still in the attacking state of its last hit walks while it is pushed, and ends its dash chain. The wind-up itself runs on and the dash starts when it runs out (`BattleDashWindupPushedStateTest`).

  At the end it dashes toward the reference, stopping short of it by both radii - or of the point where their edges would touch, for a row that dashes to the edge - on that cell's single node, facing the reference. The dashing state's entry resets its charge, loads its constant dash time and clears its landing hold.

  It flies at its jump speed in steps of 250, with no contact - the push pass neither pushes it nor lets it push, as no row sets DashingDamage - and no attack.

  - A dash with a constant time follows its height profile and ends as the time runs out.
  - One without ends when less than a step remains or, without a jump height, when its reference is in range, tested after each step at the position that step left it.

  It lands on the point it stopped at, moved off water. The landing deals its dash damage at its level over its dash radius with its push, or on its reference alone when the validator accepts it and it is within its range widened by 500. The attack is then reset and the wind-up reloaded.

  With no landing time it walks on at once. With one it holds, gaining 50 a visit, and walks on at the landing time with its attack reset and its wind-up reloaded when its targeting component is on, both before the moving state is asked for.

  The component is read as the gate of the visit before left it. So a freeze that lands on the hold's last step finds the wind-up reloaded before the moving state's gate switches the component off, and the load runs down only once the freeze is over (`BattleLandingFreezeReloadTest`).

  A row with a dash immunity is untouchable while it dashes and for that time after. The damage entry refuses every hit on it then - a direct hit, a dash landing, a projectile's impact and a buff's damage alike - and its hits are never cancelled for distance.

  A projectile's area, a dash landing's area and the area of a direct hit test it once more, as they collect their victims at the impact, the landing or the hit. A dasher with any immunity left then is passed by, though its own visit of that step counts the immunity to 0 before the damage drain would deal the share (tv_replay_005, `BattleProjectileHitDrainTest`, `BattleDashLandingAreaGateTest`, `BattleDirectHitAreaGateTest`). The death damage leaves it to the drain.

  A game object filter that leaves out dash immune units (DashImmune) leaves out a character in the dashing state whose row has a dash immunity, a Golden Knight in its chain's landing hold too. So an area effect with such a filter neither pushes, pulls nor damages it (`BattleDashImmuneFilterTest`). It also leaves out a character whose immunity after its dash still counts, unless the filter writes RespectDodgingDamageCooldown off: a row that leaves the column out takes the default of client 16.635.1's loader, on, and no row writes it. So the explosion of an evolved Wizard's shield that a Bandit's dash broke, made after the dash has landed, does not push the Bandit (`BattleDashShieldLostPushTest`). This is client 16.635.1's rule, and no reference holds it yet; client 16.402.17 counted the dash alone, and its recordings of an area made just after a dash (area-effect-gate-v1, dash-immune-filter-v1) list the unit.

  A dasher that loses its reference resumes. Refused:

  - a dash's contact damage, fixed distance, area effect and closing action
  - a swap to or from a dashing row
  - a single landing hit that leaves the unit on a cell it may not stand on

- A unit's default target, and an idle tower's, is chosen among the other side's princess towers, registered at its first selection, with that side's king as the seed. The king is never a candidate.

  A princess tower that leaves the battle is dropped from them at the cleanup of the tick it dies. The king never leaves. A building placed during the battle never enters the candidates.

  The tower the rule picks replaces the king only when the square of its approximate distance is below the square of the king's true distance. So a tower nearer only by the approximation leaves the unit on the king: a Mighty Miner that switched lanes keeps the lane it was made in, and across the arena, its own lane's princess tower gone, it walks at the king.

- A unit whose row walks in bursts - the Giant and the Golem families - stops for its WaitMS after every StopMovementAfterMS of walking.

- Every tower, king or princess, is a crown tower: the selector notices it from 2000 units farther, the index orders it last and a hit on it deals the crown-tower damage. Only the king fills its side's tower slot, which the validator's tower filters read.

- Every entity carries hit points and damage at its level, packed against its own row's rarity - the rows are published at the first level and the card's rarity only positions the level - and the towers scale as Common.

  - A hit of a unit without a projectile lands on its target and lowers it.
  - A unit with a projectile row launches it, and the projectile's impact lowers its one target by the row's damage at the projectile's level.
  - A projectile row with a radius damages the circle around its aim instead. Its target limit is not carried and taken as none, and a row that flies to a point or only heals does nothing on arrival.
  - A unit with an area radius and no projectile damages the circle instead of its target, with no push, as no row of the data pushes with a hit. The circle is around the unit for a row that centres its area on itself, else around where its reference stood at the start of the visit.

  An entity whose hit points reach zero is dead and leaves the holder in the closing cleanup of the tick it dies, as the reference run has it. What its death does beyond that - its death slot and death spawns, the elixir it pays, a fallen tower's crown - is described in its own entries.

- A row's columns the battle does not model refuse it where an entity is made of it, and the rest are carried.

  Some are read only to be refused where they would act. A spawned row that limits its spawn group is refused wherever it is spawned, except by an action from a character of another row, which leaves the limit unused.

  A unit's sight clips are its row's, as the loader leaves them: a SightClip of 0 becomes 1000 and a building's is 0, and SightClipSide is the row's. The selector and the extra-target lookup skip a candidate beyond them. Its LoadFirstHit is its row's too.

  These are inert:

  - the columns only the view reads: the walking speed tweak, a sprite's rotation, the attack animation's states, the filters, an attached character such as the Ghost's overlay, of which nothing is made
  - those nothing reads: a turret's movement, an area effect's buff number
  - AttackDashTime, which times only a turn toward the target that is modelled for no unit
  - the resurrect gate, read only by a resurrecting ability's soul count
  - the in-game pathfinding speed, read only in the state a refused lane switch enters

  A spawner's SpawnLimit is read by its firing and its destruction at the limit. A death spawn or a death projectile that would count by it is refused.

- A hit that reaches several targets hits its reference first, then, for each further target, the lookup's pick. That is the nearest valid enemy other than the reference within the attack range, found by a query of its own that leaves the king towers in place, or the reference again when the row has AllTargetsHit and nothing is found.

  Each is a whole hit with its own hit id, so the targets of one attack take consecutive ids, and each lookup sees what the hits before it did. The extra targets leave the attack timer, the reference and the target locks alone. A third target, whose lookup skips the second's pick, has no reference run and is refused, and so are unique targets, which no row sets.

  After a direct hit that was not cancelled, a row's BuffOnDamage goes onto the target for its BuffOnDamageTime at the attacker's level, the attacker as its source, unless the target is untouchable. There is no alive test, so a target the hit killed takes it too, and a later hit refreshes the one instance.

  On client 16.635.1 an attack sequence entry may give the buff on damage of its own (CustomBuffOnDamage, CustomBuffOnDamageTime). The damage drain reads them from the entry the attacker's index selects at that moment, in an order of two or more: the entry's buff when it names one, its time when it is 0 or more, else the row's BuffOnDamage and BuffOnDamageTime. An entry that leaves them out keeps the row's. No buff, or a time of 0, applies nothing. In 16.635.3 the hero Electro Wizard's two surge attacks set a time of 600 and no buff of their own, so the row's stun lasts 600 ms on those hits. No reference battle holds it yet.

  A buff on damage over an area is refused, as no row with one has an area, and so is one from a hit on nothing. The buff on damage every so many hits is not modelled, as no row with a buff on damage sets an interval.

- A projectile's own collision radius is taken as zero. That is supplied, not settled.

- A homing projectile with a target puts the damage it will deal, its crown-tower damage on a crown tower, on the target as pending. It does so as the holder admits it and at each chained hop.

  It also raises the target's pending duration to its flight time: the distance over its speed per 50 ms, rounded up to 50 and at most 1000. The target's state visit counts the duration down by 50.

  The projectile hands the damage back as it arrives, before its impact, or as it is released or finished early. A target that leaves is handed nothing back, one entering a pathfinding state drops it, and a morph passes it to the new object.

  A projectile attacker asking to take or keep a target refuses one the pending damage will kill within 600 ms, no shield up and the target not untouchable, unless the target:

  - will dash out of its way
  - lives for a time without being a building
  - is being healed while its full hit points exceed the damage

  An attacker that has hit keeps such a reference all the same, and its removal then skips the retarget load.

- A dead king tower fails the validator's alive test although every targeting component bypasses it. A fallen king ends the battle, and what a validator answers for it while the end timer runs is not established, so the battle keeps the refusal the references were made with.

- A removable entity - one that has asked to be removed or has no hit points left, or a projectile that has arrived - leaves the holder in the closing cleanup of the tick it becomes removable.

  Every remaining entity is told inside that cleanup, those handed over that tick before the live list:

  - A character drops a reference to it, starting the target-lost countdown when an attack was running unless it held the reference through a killing shot of its own in flight. Its default targets lose it.
  - A projectile aimed at it keeps its aim where it stood and forgets it.

  Each entity's action holder is told after its components. It drops every queued action the removed entity caused whose row aborts when its cause does, which a row does unless it sets `AbortIfInstigatorDies` false. It walks its queue by swapping the last entry into a dropped one's place. An action already running is kept, and the removed entity's own queue leaves with it.

  Whether the removed entity is told of its own removal is not settled. A held projectile is forgotten by its holder itself, beside the notice: a hooking projectile, and a pingpong projectile that came back to an owner whose targeting was off. The morph back a forgotten hooking projectile starts for a row with a morph is not modelled. What the removal does to an entity that follows it is told with the follower: an area effect that follows its parent ends at the cleanup where the parent leaves, and an attached rider forgets its parent and leaves in the same cleanup.

- An expression names only the battle's functions, the variables and game tags registered with the battle, the character and building rows, and the builtins, in that order.

  A row's name stands for its global id, and one whose id is negative answers 0, as the game's own dispatch leaves it. The variables and tags are registered by hand until the data is loaded. An entity's tag word is recomputed from its one-step word, its actions' tags, its listed buffs' tags and its row's.

  Of the 47 functions the battle answers the four the king's condition uses, `x` and `y`, `team_index`, `team_y_direction`, `map_width`, `map_height`, `has_data`, `rand` and `is_clone`, a character's clone byte, and refuses the rest. An action owner's expressions answer `rand` alone.

  The random source draws as recorded, and an expression's draw falls where the expression is evaluated, so draws follow the battle's own call order: a select draws as it is scheduled. The battle's seed is not established. It is 1 unless a run gives its own.

- A building that hides while it does not attack, the Tesla, keeps a hide counter its state visit steps from the end of its deploy, after the elixir block. It steps by 50 a visit, 0 under a stun that stops time.

  - Outside the attacking and deploying states the counter climbs to HideTimeMs and stays there.
  - Attacking, it climbs on through HideTimeMs plus UpTimeMs and wraps to 0.
  - Attacking while still going down, it turns back into a negative count towards 0, rising at once from more than half-way.

  It is hidden exactly while the counter stands at HideTimeMs:

  - no attacker takes it
  - an arrow already in flight lands on it for nothing
  - every area and buff passes it by but an area effect that reaches hidden units, which takes it, damages it and buffs it

  A buff's damage over time does not land on it hidden either: the bookkeeping a buff's hit goes through asks the hidden test as the entry does. Hidden is not untouchable.

  At its deploy end the state visit runs the combat gate and its targeting visit right after the resume. So it takes its reference, and a target in reach, a tick before its targeting component's own first visit. Its targeting is a building's, so it keeps a target with no range extension and lets it go as it leaves its reach.

  Its hide and rise effects only show something. A rise's or a hide start's action, area object and push, and hiding before the first hit, are refused with the row that sets them.

- An area effect whose buff attracts, the Tornado, pulls with each hit, before its buff is applied. It pulls every entity of the battle's live list inside its circle that is not a building, that the shared validator accepts as an enemy of the area effect in an area's query, and whose movement is on.

  The pull is added to the unit's push accumulators and waits for its next movement visit. That visit averages it with the collision pushes, adds it to the route step with the 150 cap lifted, keeps a ground unit off the water, and clears the accumulators.

  It is AttractPercentage of the unit's configured speed, scaled by PushSpeedFactor, along the way to the centre: 216 a step for a Knight or a Barbarian, 162 for a Giant. The Tornado's PushMassFactor of 0 leaves the mass out. Nothing resets the unit's route, reference or state, so it walks its old route on from where it was dragged.

  The area effect is the parent of the buff it applies, which stacks. It is refreshed to 500 without its damage counter moving, and as the area effect leaves at its closing cleanup its instances go with it. The slot that ControlsBuff gates is reached by no path the battle models.

  The evolved Valkyrie's mini tornado follows the Valkyrie and pulls from where it stands at each update, three times a Barbarian's speed, 180 a step.

  The pull is handed the area effect's move since the start of the tick but reads it only for its angle window, which neither row asks for. An area effect whose pull has an angle window is refused. A unit that died earlier in the tick has its movement switched off and is passed over.

- An area effect with a Projectile, Lightning or Royal Delivery, launches it in its update after the hits and before its life-end action. It launches one projectile at most, on an update whose hit count rose: Lightning's tenth, nineteenth and twenty-eighth updates, Royal Delivery's fortieth and last.

  A row with HitBiggestTargets, Lightning, drops it onto the object its chooser picks. Over the battle's live list, in its order, the chooser takes a character or tower:

  - it has not struck before
  - that the shared validator accepts as its target - of the other side, not untargetable, passing the area effect's own test, with hit points and accepting it as an asker, so a hidden Tesla is passed by
  - whose air or ground it reaches
  - that stands in its circle

  Of those it picks the one with the most current hit points and shield. The first of equals stays, and nothing tests whether it is alive. The chosen one is listed and never chosen again, whether or not the strike lands. With nobody to choose, nothing is launched, the hit is lost and the update ends without its life-end action.

  The projectile starts on the chosen object at the row's ProjectileStartHeight and aims at the same point, keeping the object as its target. Without HitBiggestTargets it starts and aims at the area effect's own point, with no target.

  It fights for the area effect's side at the area effect's level re-based on its row's rarity. The area effect is its launcher, owner and root and the cause it names, until the area effect leaves.

  It is handed to the holder, which gives it its id at once and admits it at that tick's closing cleanup. So it flies on the next tick, after every area effect's update, and lands then:

  - Lightning's strike on its target wherever it stands, the crown-tower damage on a tower and its stun after the damage, nothing on a target that has left
  - Royal Delivery's crate over its circle, then its Recruit, after the area effect has gone

  Refused (no shipped card has them):

  - a launch from the area effect's source (a start height of -1)
  - a first hit kept for a first target
  - a spread of projectiles from the second hit of a row without HitBiggestTargets - two battle draws

  SpawnInitialDelay and SpawnTime are read only by the character spawner, which a row without a SpawnCharacter never enters, so on Royal Delivery's row they change nothing. A card's TouchdownLimitedDeploy is read only with the game mode's touchdown query, which is off in a battle of two players, and is carried unused.

- A projectile with a SpawnAreaEffectObject, the Heal Spirit's, makes that area effect in its impact. It is made after the impact's damage, its target buff and its spawned characters, and before its spawned projectiles, at the impact point, for the projectile's side, at the projectile's level re-based on the area effect's rarity.

  The holder gives it its id at once and admits it at the closing cleanup of the impact tick, where the projectile leaves too, so it first updates on the next tick.

  The projectile is its parent and the projectile's target its own. The deflection is the only reader of the one known, and only of an area effect that deflects projectiles, which none made this way is, and none of the other, so neither is carried.

  The Heal Spirit's area has one hit, which buffs the own troops in 2500 with HealSpiritBuff for a second. It takes air units and deploying ones too, while IgnoreBuildings leaves out every building and crown tower and OnlyOwnTroops the enemies. The buff heals 100 a time at level 11 on its sixth, eleventh, sixteenth and twenty-first visit, up to the maximum.

  A row whose area effect follows the projectile is made on its first flight visit instead, and one that follows its target follows it. Both are refused, with any area effect row the battle refuses.

- A unit whose row sets both SpawnPushback and SpawnPushbackRadius, the Mega Knight, pushes the enemies around it each time it enters the deploying state through its setter.

  It runs the object query of the spatial index, as wide as SpawnPushback. Each entity found takes a pushback of SpawnPushbackRadius away from the unit when it:

  - is alive and of another side - its side as it is
  - is not passed by as a flying one (a row that does not attack both air and ground passes them) or a hidden one
  - has its movement on, which no building has

  The pushback comes with the gates lifted: IgnorePushback, a buff and the no-pushback flag do not refuse it. A pushback in flight still does, and its mass is not read.

  A card play enters the state in the command pass, between the holder's post-pass that empties the index and the pre-pass that fills it, so the play's push finds nobody. A unit that waits its turn enters it inside the tick, with the index filled, and pushes.

- The placement of a card play covers troop cards, building cards and spells cast as one projectile or one area effect in a battle of two players.

  A building card is read from the buildings card table and played as a troop card is. Its unit is a buildings-table row, whose even footprint the search snaps to the tile corner and searches over, and it deploys at once. A building card that waits to deploy is refused.

  A card whose first row has a SpawnPathfindSpeed hands its units to the tunnel in place of a start. Each is moved onto its own king tower at its flying height, aimed at the placed point, and set to the spawn-pathfinding state, then queued with its registration visit, which searches its route and takes its first step.

  It walks at its SpawnPathfindSpeed, which is also its reached radius, surfaces on the point deploying, and is hidden meanwhile:

  - no attacker takes it
  - the damage entry refuses it
  - every hit, area and buff passes it by

  An area effect that reaches hidden units, the one exception, is refused when it reaches such a unit.

  A unit that morphs as it surfaces, the Goblin Drill's dig, is searched for as the row it morphs into, may cross water, and as it surfaces is replaced by that row. The replacement is made on its point at its level, in its lane, with its share of the hit points and its facing. It is queued with its registration visit in the state it is made in - a building stands, so it takes a target and a LifeTime step there - and then set deploying through its setter. The dig leaves at that tick's closing cleanup.

  The new object is started at that cleanup's fold like any object the holder admits, so a row with a starting action has it scheduled then: the Goblin Drill's damage area, or the evolved Goblin Drill's relocation:

  - It makes its first-appear damage area in the next tick's pending pass.
  - At each hit-point threshold (66 and 33 percent) the building goes underground for its hide time. Its hide group spawns two Goblins a half tile either side of it, which face where the drill faces (a spawn with a deploy time of its own hands the child a character source's facing).
  - With two steps of the hide time left, it is moved five ring steps (one tile each) around the enemy princess tower it stands by, forward or, when the forward point would lie nearer the enemy king, backward, and deploys again there.
  - Its spawner holds its timer while it is underground.

  Only the rings of the enemy princess towers still listed are looked at: not the king's, and not a destroyed tower's once the cleanup has taken it out. So a building that surfaces there, or on no ring point at all, comes up again where it stood. The arena-quarter rule and character spawns or reappear actions of the relocation are refused.

  A morph into a character, of another rarity, of a unit riding another or carrying buffs, or out of the deploying and waiting states is refused.

  A card that names no unit - no summoned character, second group or list - but casts a projectile or an area effect is a spell whichever card table it is in.

  A card with a unit and an area effect that deploys as a spell (SpellAsDeploy), the Electro Wizard and the Ice Wizard and their hero forms, is played as a troop card. Its search does not snap to its unit, as a thrown spell's does not. It lists its wizard (SummonCharactersList), which waits the entry of the card's delay list at its index before it deploys, and makes the card's area effect at the placed point at the card's level after its units.

  Deploying as a spell changes nothing else: Heal and the Goblin Drill are troop plays. A card with a unit and a projectile that deploys as a spell is refused.

  A troop card that also casts a projectile, the Mega Knight's MegaKnightAppear, launches it before its units are made. It is launched as a spell's is but from the placed point less five times the king tower's collision radius along the length, whichever side plays, at three times that radius, onto the placed point. It lands six ticks after the play, before the unit's deploy ends, on the ground enemies around the point, buildings and crown towers too. It pushes them as an area hit does, so a Giant, which ignores pushback, stays.

  A card with a unit and an area effect that does not deploy as a spell, or casts a projectile as well, is refused. No card has one. A Mega Knight placed directly starts deploying without its setter's entry, so it pushes nobody, and its card's cast is a play's, which a placement makes none of.

  A card whose units tunnel to their point (SpawnPathfindSpeed) is refused where it is played only when it is a group, which no card is. A unit's hit that reaches a third target, or several unique targets (MultipleTargets, or an attack sequence entry's CustomMultipleTargets), is refused, and so is a buff on damage (BuffOnDamage) over an area without a projectile or from a hit on nothing. A unit that fires hands its hit to its projectile and never applies its own, so the Witch Mother's curse is her projectile's target buff.

  Not modelled:

  - a spell's several projectiles other than Arrows' ring
  - a spell thrown as a projectile without one to spawn
  - a touchdown mode, a capture tower and the dummy rows
  - the elixir and the other gates that come before the map check

  A spell's level is the level it is played at less one, the card item's level field, as the hand is not modelled. A projectile the cast launches is not reported as a launch.

  A spell whose projectile, or the projectile that one spawns, does any of these is refused as it is cast or spawned:

  - buffs through its hits on the way
  - spawns an area effect or projectiles along an axis
  - pushes along its flight
  - stops at the first entity it touches
  - sweeps back and forth
  - has a push floor

  A projectile whose row fixes its spawn priority, the Goblin Barrel's, gives the k-th character its impact makes (20k)^2 off its squared distance as a candidate in every selection.

- A typed hit with no source at all, whose type scales by level, is refused: the level it would read is not established.

  A source replaced by another object (a morph) is not modelled. The hit would scale by the replacement's row and level. A projectile has an action holder only as the cause of what it kills' death hooks, so it is never a typed hit's source. No buff protects, multiplies or adds on hit, and the drain runs before the observers are told the post-hooks are done.

- A level change moves an entity's packed level, its damage from the next hit, its maximum and team pools, and on a rise its hit points by their share of the old maximum, never lowering them. A fall keeps them as they stand.

  The growth percentage the share is taken at is the usual 100, as no unit that grows is modelled. A king tower takes a level change as any unit does, its maximum worked out by the king's own rule at the new level, as the Royal Chef's pancake turned back at its king by a Monk's Deflect gives it (chef-king-level-v1). A princess-slot tower's level change and one on an entity carrying a shield are refused.

  A clone's level changes as any unit's, its damage and team pools following the new level, but the level-changing action then sets its hit points and maximum back to 1 each and holds its shield and the shield's maximum to at most 1 each (both 0 when the shield's maximum was 0 before the change). So a Skeleton King graveyard skeleton, or a cloned Dark Prince with its shield up, that the evolved Electro Giant's pulse lowers a level stays 1 of 1, its shield 1 (graveyard_clone_pulse_delevel_a, _b, clone_shield_delevel). A clone's level rising is refused: no recording holds one (the Royal Chef's pancake passes over a unit whose maximum is below 2, and the evolved Electro Giant's debuff gives the level back only as it is taken off).

  A row swap reads the new row from then on: the maximum hit points at the unchanged level, the hit points kept, the damage, the collision radius, mass and speed, and the targeting columns. The reference is cleared as it stands and a kept target stored again through the setter.

  A unit may take a row with neither a charge nor a river jump, as the hero Dark Prince takes its walking row as it dismounts. The swap leaves the movement component as it stands, and its next displacement reads the new row: a charge under way ends there and no jump starts. A full charge is given up too while the unit casts, as when the tap waited out a river jump and the cast starts on the landing step with the charge full (dismount-charge-cast-v1). The casting unit does not walk, and it strikes only when its ability keeps its target. Its next displacement outside the walking state, or at the latest its leaving the casting state, sets the charge again from the new row, which has none. Refused: such a swap mid-jump, a full charge outside the casting state or under an ability that keeps its target, and any other swap to or from a row with a charge or a jump.

  A walking unit may take a building row that stands still and drains over a lifetime, as the Moving Cannon breaks into BrokenCannon below half its hit points:

  - its movement component is freed
  - it counts as a building from then on
  - its hit points drain over the new row's lifetime from its next hit-points visit
  - it keeps its state, position, lane, level and target

  The drain's death is the decay death. That the freed movement and the building flags change what others do is held by no run.

  Any other swap that builds or frees the movement component, or reaches a lifetime, a building or a flying row, a champion, a row with a shield taken by a unit without a shield maximum, or another deploy time, is refused, and so is a projectile's swap. `max_hp` reads the maximum at the usual growth percentage of 100, as no unit that grows is modelled.

- A unit's ability is requested by a friend collector, or by its player's command through a champion's controller.

  The gate is open with:

  - the targeting component on
  - the unit in none of the states from dashing to the follow-up's, neither postponed nor disabled
  - the ability running an activation action, buffing the unit, creating an area effect, switching lanes, leaving a character behind or, dashing only, with the unit's reference within its dash range

  With the gate open, the request enters the casting state through the setter, which seeds the cast time and the trigger delay in whole ticks and ends with the combat gate. With it shut, the ability is left pending, and the state visit casts it on the first visit the gate opens.

  The effect fires on the visit the trigger delay reaches zero and schedules the activation action. The unit stands on the visit the cast time does, and leaving before the effect leaves the ability pending again. With KeepCurrentTarget the combat gate switches the targeting component off during the cast and keeps the reference.

  A lane switch, the Mighty Miner's, fires nine ticks into its cast. The unit is aimed at the mirror of its position across the arena's width, its own length kept, moved 250 inside the arena and off water, and sent there in the in-game pathfinding state, which ends the cast. Every projectile aimed at it whose row allows it loses it and lands on nothing.

  A projectile launched in that step joins the battle after the drop. Its first flight step finds the unit routing across and runs the same reset, emptying the damage on its way to the unit and dropping it from every projectile aimed at it that allows it, and it flies on to where the unit stood.

  A projectile aimed at the unit whose row keeps its target through that reset, the Royal Chef's pancake, keeps the unit, follows it across and lands on it. It hands back its part of the emptied amount, 0 for the rows that keep their target, which deal no damage. One with damage on its way is refused.

  The ability then leaves its character on the unit's spot, the Mighty Miner's bomb, for the unit's side at its level, deploying for its own deploy time and untargetable on its first tick, queued for the closing cleanup. The bomb dies at the end of its deploy into its death damage and push.

  In the in-game pathfinding state the unit:

  - is hidden, refused by every validator and dropped by whoever holds it at their next targeting visit
  - is spared the damage over time of the buffs it carries (which deal again once it lands)
  - is out of the push pass
  - routes on the spawn-pathfinding cell cost at its IngamePathfindSpeed, keeping its own reference

  On arrival it deploys again for its deploy time, the setter's combat gate on the way out dropping its reference, and then selects anew, a ramp starting over from its first window.

  The Monk's ability fires eighteen ticks into its cast, inside the state visit, so the cast's end that the same visit tests finds it out of the casting state. It:

  - buffs the Monk with ShieldBoostMonk
  - creates the area effect Deflect at the Monk, which follows it as its parent
  - holds it in the follow-up state for AbilityStateDuration over 50 visits, counted from that visit: 79 ticks

  In those ticks the combat gate keeps its targeting component off, its reference kept, and it carries the ability's GameTagsWhileAbilityActive, AVOIDANCE_AS_OBSTACLE and NO_MOVE_ALLOW_ATTRACT, from the next tick to the tick it leaves. It stands, and its next targeting visit takes the attack up again from its load time.

  A buff's DamageReduction cuts what reaches its carrier. The largest at or above 0 plus the smallest at or below 0 over the listed buffs, held within PROTECTION_CAP_PERCENTAGE, takes that percent off the amount, truncated:

  - at the hit-points entry before its floor at 1
  - in the pending damage's lethal test
  - in a typed hit's protection, floored at 0
  - on a buff's damage over time

  IgnorePushBack refuses every push that does not lift the gates.

  An area effect that deflects projectiles stays in the live list's order and deflects until the cleanup would remove it (its countdown below 0, so on its last step, at 0, it still deflects). Each flying projectile, after each move at its new position and height and at its arrival at its aim, is measured against it as a point within its radius in three dimensions, the area effect on the ground.

  An enemy projectile it touches hits the area effect's parent with its damage at its level, a fresh hit id, through the hit-points entry and the shared validator. The hit is queued for the damage drain with the projectile as its source. So the drain follows a hit it lets through with the projectile's after-damage target buff at the projectile's level (the Monk takes the stun or the slow of a shot it turns; a buff applied before the damage is not given).

  The projectile is also sent back at its root owner, a crown tower's shot at its tower, the king's as a princess tower's:

  - the damage registered on its target handed back
  - its target and root forgotten
  - the deflections counted
  - the deflector's side
  - relaunched from where it stands with the parent as launcher and owner

  It registers on the root owner the deflected share it deals on impact: DEFLECTED_PRJ_TOWERS_DMG_MUL percent of its crown-tower damage on a crown tower, DEFLECTED_PRJ_UNITS_DMG_MUL of its damage on anything else.

  A projectile with no deflection, NoDeflect, passes untouched. These are refused:

  - one with a deflection behaviour of its own
  - a second deflection of a Goblin Barrel
  - a body, a hop, a flight to a point without a stop at collisions on a row with a least distance, a box or a deflection radius
  - a timed homing on any other row but a hook's
  - a sweep
  - a hook already pulling what it hooked
  - an impact spawning projectiles along the length (the Fire Wall's)
  - a chain other than a wave of Arrows
  - a Goblin Barrel the search would send at a king tower
  - one without a root owner that is spell-like (a bomb whose area effect has left) or has an action on reaching its target
  - a deflection past MAX_DEFLECTION_TIMES

  A projectile without a root owner, its shooter gone (a Witch's or a Little Prince's shot, a fallen princess tower's arrow), is not sent back. The parent takes its damage, it is turned at its start with no target for the parent's side, and its flight ends there. So it leaves at the next cleanup without an impact on its target.

  A hook on its way to its target, the Fisherman's, is not turned either. The parent takes its damage (it has none) and the hook's flight ends where it is, still its owner's, for his side and aimed at its target, uncounted as a deflection. So it leaves at the next cleanup without hooking anything, and its action on reaching its target, if it had one, is scheduled.

  The evolved Bomber's bouncing bomb is a chain. Each impact spawns the next bounce, one link shorter, and every launch, a deflection's relaunch included, counts the links from the row's spawn chain again.

  - A turned throw is sent back at the Bomber and, landing there, bounces on for the deflector's side, the deflector's parent its root, along the line from where it was turned.
  - A turned bounce, which has a range and no body, is aimed its range from where it stands toward the Bomber, with no target, and bounces on as often from where it lands.

  A spell-like projectile, whose only deflection behaviour is UseSpellsTowerDamageMul (a Fireball, a Rocket, a Snowball), cast with no target, is sent at a point with no target, for the deflector's side: the position of the crown tower of the caster's side nearest the area effect, a princess tower or the king tower alike (the Royal Chef's king tower too), among those the projectile has not hit and the shared validator lets it reach, the first in the live list on a tie. With none it goes back at its root owner. So a spell turned nearer the caster's king tower than any princess tower of his still standing lands on the king tower.

  A cast Goblin Barrel (the evolved one and its decoy too) has its king tower as its root and neither a target nor a deflection behaviour. It is sent as a cast spell is, with no target, at the crown tower of the caster's side nearest the area effect, for the deflector's side, so its impact there makes its Goblins for that side.

  A king tower's own shot whose target has left in its flight has the king tower as its root and no target too. It is sent the same way, with no target, at the crown tower of the king's side nearest the area effect, the king tower itself when it is the nearest, for the deflector's side, and lands on nothing there (king-shot-deflect-captured-v1). With a target still set it goes back at the king, as above.

  A deflection radius of a projectile's own only widens the reach of the measure.

  Arrows' arrows are measured only at their target's point, with no measure on their way. At their arrival the measure is taken at the arrow's ring point, the unjittered point of the spell's ring the cast gave it, at the aim's height. Only an area effect whose radius also overlaps the wave's circle (the placed point and the spell's radius) turns it.

  A turned arrow is sent as a cast spell is, with no target, at the crown tower of the caster's side nearest the area effect. The parent takes the arrows' damage once a wave: the wave's list of what it has hit holds the parent after its first turn (and an arrow landing on the parent before it), so the wave's arrows turned or landing after pass it over.

  A turned arrow lands at the point it was sent at, not its ring point, and its victims need not stand in the wave's circle. It still hits none the wave has hit. Once turned it is measured on its way as any other.

  A volley whose launcher links it into one group and whose last projectile's row leaves DeflectGroup empty (true as the loader stores it), the Princess's, is turned as one:

  - Only the first of the group still in the battle is deflected, the others passing untouched.
  - The parent takes its damage unless the group lists the parent, and is listed after.
  - Every projectile of the group still flying is then turned the same way, the first launched first, and the deflected one once more, so it counts two deflections.
  - Without a root owner the others are turned at the deflected one's start and fly on.

  Deflected again, by an area effect of the other side, the group is turned the same way. The first, at two deflections, counts a third with the group, past MAX_DEFLECTION_TIMES, so its flight ends. The others, turned a second time, fly on at the first one's root, the parent of the turn before. A parent the group has not listed takes the first one's damage.

  A volley's group not turned as one, the Hunter's pellets (DeflectGroup False), is deflected pellet by pellet. Each pellet the measure reaches is turned on its own, the group's list giving the parent the damage of the first only.

  A pellet flies to a point and stops at collisions. Turned, it:

  - is aimed at its root owner's position itself, not stretched to its range
  - keeps the root owner as its target
  - draws a fresh random delay below its row's from the battle's random source and waits it out where it was turned
  - then hits what its body passes on its way back and stops at the first hit

  A piercing body, the Magic Archer's arrows (the hero's Power Shot and Triple Shot arrows too), flies to a point and does not stop at collisions. Turned, it is sent back at its root owner, aimed from where it stands its range toward it, as a first launch is aimed from its launcher, with no target. A row that homes for a time homes on the root owner again when it stands farther than the row's least homing distance, its flight then measured from the parent. It hits what its body passes on its way back.

  A rolling body that flies to a point and inverts its direction, the Bowler's ball or a rolling log, is turned all the same:

  - A log is measured by its box, the point of it nearest the area effect within the deflection radius.
  - The parent takes its damage, and it rolls back from where it stands by its flight from its start to its aim, for the deflector's side with no target, so a Barbarian Barrel lets its barbarian out for that side.
  - A constant height is kept, so the Firecracker's sparks roll back at theirs.
  - The hero barrel's roll is turned too. The run of its starting action, which holds the ability button ready as it rolls, goes on, writing into the slots of the side the turn gave it. The roll lets out the row's deflected spawn, the barbarian without the hero's ability, for the deflector's side, which no champion slot follows.

  A row's action on its deflector, the Firecracker's rocket's, is scheduled as the redirect starts on the area effect's parent, the projectile its cause, so it waits for the next pending pass. The rocket's damage action lands on the parent at the drain of the step after the turn, scaled by the rocket's own row and level, through the parent's protection, and no buff of the rocket changes it. A rocket that has left by then, as one turned after its Firecracker has left does at the next cleanup, is no source: the game clears every reference to an object as it leaves, so the hit lands unscaled, as a hit without a source, through the parent's protection. An area effect leaves before its queued hit is drained only as the tiebreaker's clearing removes it; that hit is dealt as one without a source and, as the tiebreaker holds every hit from its first step, lands nothing (recorded with Earthquake's hidden damage). Any other damage-taking action's hit with an amount whose character or area effect source has left is refused: no recorded battle holds the step such a source leaves at. An action on the deflector that is not a damage-taking action is refused.

  The rocket itself, sent back at the Firecracker, deals nothing to the parent, and its impact fans its sparks out for the deflector's side along the line from where it was turned to its new aim.

  A projectile whose deflection behaviour ignores the height, the Royal Giant's shot, its evolved form's and the hero Bowler's close siege shot, is measured on the ground plane, its height left out. So a shot flying at its constant height is reached as if it flew on the ground. Nothing else of its deflection reads the flag, and a round body on a shot that does not fly to a point, the evolved Royal Giant's, only widens that reach.

  The Skeleton King's ability fires nine ticks into its cast and creates the area effect SkeletonKingGraveyard at the King, which follows it. An ability that collects souls - ResurrectBaseCount set - then gives that area effect a lifetime of its own and clears the souls. The lifetime is SpawnInterval for each character after the first and its SpawnInitialDelay, for ResurrectBaseCount characters and one more for each soul, at most SpawnLimit.

  A soul is counted as the death slot's notice, after its projectiles, tells every character of the live list of a death, the dying one included. It is counted:

  - by a unit whose ability collects souls, not a clone, the copy a champion controller of its side follows
  - for the death of a character of its own side under ResurrectOwnTroops or of the other side under ResurrectEnemies that is not a clone, a building or a row that ignores resurrection
  - while ResurrectBaseCount and its souls stay below SpawnLimit
  - wherever the death falls

  The King's own death counts on itself as it dies. A unit no controller follows - placed directly, or an older copy - collects none.

  An area effect with a spawner makes its characters from its update, after its counters and radius. It makes one each SpawnInterval after the SpawnInitialDelay, on the row's own lifetime even when an ability gave it another, so the Skeleton King's graveyard makes one on every fifth update, from the fifth.

  The directions are shared out over the characters its lifetime holds after the delay, in an order shuffled on its first update by fifty pairs of draws of the battle's random source for each. Each character is placed at SpawnMinRadius and a draw below what the radius leaves after its collision radius and that distance, moved off water and 250 inside the arena. It is drawn again anywhere in the circle, up to five times, while a building overlaps it or the standing test refuses its cell.

  It is made on the area effect's side at its level, deploying for SpawnTime, registered at once, and, for a row that spawns clones, a clone of 1 hit point, whose death counts no soul. A clone takes no part in collision while it deploys. The graveyard stays on its last point as the King leaves, StayAfterParentDies, and makes the rest there.

  Refused:

  - a spawner made by anything but an ability
  - one that also hits, chains or runs an action
  - one that turns its directions by a fixed step
  - a child that is a building, paths to its point, starts an action or is not a clone

  Any other effect of an ability - a buff over a radius, a morph, a deploy time for the character it leaves, or a buff kept while it is pending - is refused as the ability is requested. So are a cast with no countdowns and a lane switch for a row that stays visible as it routes across.

- A friend collector, the Giant Buffer's, asks the battle's target locks for its nearest friends that its filter passes, as the object query answers them from the index. The priority is the squared distance flipped against the largest int.

  Its filter is read as any game object filter is, its list of rows to exclude among its tests, so the friends of an excluded row are passed over (`BattleAbilityTest`). A filter row the tables do not hold is refused as the collector is built, naming the row. The game's loader reads such a name as no filter, whose query lists nothing, but the game's own data may define the row outside the files the tables are made from: the Rune Giant's collector names `friendly_troops_for_rune_giant`, which the 16.635.3 tables do not hold, so a battle with a Rune Giant is refused on them.

  As its buff delay ends it runs its row's buff action on the unit. The Rune Giant's holds the unit for 600 ms only while hit_timer is not 0; hit_timer answers the unit's attack time, the field attack_count divides, so a Rune Giant that buffs while it walks walks on.

  The locks are made by its first step. The post-pass after the phase-3 pending pass grants each target to its highest priority, the first of equals keeping it. The pre-pass drops a lock whose target or holder is no longer listed alive, then acts on the queued releases. A collector that unbuffs a friend beyond a distance is refused.

  Its projectile's impact schedules the on-hit action on the friend, the projectile as the cause. The enchanting buff it starts walks back from the projectile to the collector for its level and rarity; when the collector has left before the projectile lands, the walk stops at the projectile, which is then the giver, so the buff ends its finish time after the projectile leaves. It listens to its unit's hits:

  - the damage of the hit that completes its count
  - the end of each landed single-target attack, which counts it and at the count runs its action
  - the landing of a dash, as the Bandit's and the Boss Bandit's: its damage passes the hook as one that is not a crown tower's, and its end counts as an attack's end, whether or not its hit landed (`BattleEnchantedDashTest`)

  A projectile the unit registers on that hit carries a copy of the buff to its impact. The enemy-target visual the buff hands a landed hit to only shows it and is not modelled. A unit whose attack sequence is damage entries alone, as the Inferno Dragon's ramp, takes the buff on its direct hits: every third landed hit gains the added damage, the ramp's stages unchanged. A unit whose entries launch its projectiles, as the Little Prince's, gives copies to the completing hit's projectiles as a row's launch does, and the hit is counted. An entry that runs an action in place of the hit, as the reworked Three Musketeers' bayonet, is neither counted nor enchanted, and the damage its action deals reaches no hook of the buff (`BattleEnchantedSequenceTest`). An entry's first projectile, as the evolved Princess's, gets a copy as the others do, and a hit whose entry names its own attack action, as the hero Electro Wizard's ability entry, is counted as any other. A projectile carrying a copy keeps it when it is deflected: the deflector's parent, the Monk, takes the added damage on its hit, and the turned projectile lands on its shooter with the added damage again, after the deflected share, at the giver's level, the count untouched. A unit whose row attaches its children, as the Goblin Giant's spear goblins, the Ram Rider and the hero Ice Wizard's cube, gives each child still in the battle a run of its own as its run starts: listed on the child past the singleton walk and started with the same cause, so with the same giver, it counts and enchants the child's hits and ends after the giver leaves, whatever becomes of the unit's run (`BattleEnchantedRidersTest`). No shipped child's hit gains damage from it: the row's multiplier is 0 for the spear goblins' and the Ram Rider's projectiles, and the cube deals none. A start in the run pass that would hand a run on to a child that pass has already stepped, which the game steps at once, and the Berserker's sequence step are refused, and so is a walk back that meets an area effect, which the game follows to the area effect's parent, or ends on one, which the game keeps as the giver by its id and rarity (`BattleEnchantAreaCauseTest`); no shipped row starts the buff from an area effect or from a projectile one launched.

  A singleton row found running re-triggers by its name, whichever entity's tree built it.

- A character placed directly starts its row's starting action as it is placed, with itself as the cause, so it runs in its phase-1 pending pass of the placement tick.

  A card play's unit is started the same way, deploying or still waiting its turn, so its starting action runs in its phase-1 pending pass of the play tick, or its row's delay later. The Moving Cannon's watch on its hit points and the Furnace's interval both start on the play tick. A spawn whose child has a starting action is refused, as that start is not held yet.

  A unit whose hook is written inline, as a row with no name, is refused rather than read as having none. An ability's activation action and a projectile's on-hit action written inline are the actions table's row named after their row and column, as the reference names them.

  What a spawned child's group is read for, and what becomes of the group when its source leaves first, is not modelled.

- Of the action rows, a class the battle does not have is refused when the row is built, and so is a column it does not model: an optional action delay passed on by a select, and the spawn columns of the other spawn types.

  A buff spawn row puts its buff on its owner for its spawn time. It does so at the level and for the side of its cause, which is the buff's source, or of its owner for a row that takes its owner as the source (ParentGOAsSource). One that writes its buff inline, names a buff the battle does not model or one its parent controls, or sets any other spawn column, is refused.

  An interval affected by the spawn speed steps at its owner's spawn rate, which its buffs set, and any other at the usual 100. What it fires has its owner as the cause.

  A variable is keyed by its row's index, which only has to agree between the actions that write it and the expressions that read it.

  A check whether objects exist asks a projectile or an action owner in the live list only its kind and side, which every shipped filter's gates reject. A filter that lets one through and asks more is refused.

- A dying entity schedules its death action and, unless it killed itself, its killed action on itself, what killed it as their cause.

  What killed it is:

  - the unit, for a direct hit or its area
  - the projectile itself, for an impact
  - a typed hit's source, while it is still in the battle
  - the killer of a kill

  The cause is the attacker's own holder, where the game hands the hook a copy of the attacker made at the kill. A hook reads no more of it than its presence and its row, which the copy shares.

  A buff's damage over time is dealt with the buff's source as the attacker, as the instance held it when the hit was queued, for the side the buff was applied for. The source is what killed the carrier, the cause of its killed action and the one whose killer's hook hears the kill. A hit that lands and does not kill reaches the source's reference drop as any other hit does. The Boss Bandit's check of its killer finds a Poison, a Tornado or a projectile, which match no name.

  A death with a killing side and no attacker is handed in the game a cause that carries the side alone and no object, which the battle does not hold. Such a death is the damage of a buff applied with no source or whose source has left the battle, which the buff then forgets, or a hit of the evolved Dart Goblin's poison run.

  Its killed action runs with no cause when no action of its chain reads more of its cause than the object:

  - a spawn whose source is its owner, as the evolved Wall Breaker's small Wall Breaker and its barrel, for the dying unit's side
  - the Boss Bandit's check of its killer, which finds no object and runs nothing

  These are refused:

  - a killed action with no attacker whose chain reads the side, level or point of its cause
  - one whose attacker is an object of another kind than a unit, a projectile or an area effect
  - a death hook with no pending pass of the tick ahead of it, which would leave with the entity

  Every hit that lands calls the killer's hook on its attacker, a projectile's on its shooter while the shooter is in the battle and a character. Its first block, on a kill or not, drops the reference of a row that both passes over and ranks lower the targets carrying a buff (the Ram Rider's rider). It does so through the setter's null path while its targeting component is on, before the kill's blocks, the death handler and the projectile's target buff. Its attack time runs on.

  On a kill the hook comes between the dying entity's death slot and its hooks. An arena entity whose hit killed, and whose row has a killed-done action, schedules it on itself with the row's own delay and the entity it killed as the cause. So it runs in the killer's next pending pass, phase 2 after a melee hit or a dash landing. A projectile's kill for a launcher with a killed-done action, which the game hands on to the launcher, is refused, as is such a kill with no pending pass ahead.

  A check of an action's cause schedules its action to run on its owner, the owner its cause, when the cause's character or building row is one it names, or for any cause when it names none. Otherwise it schedules its action for no match. A projectile or an area effect matches no name, a dead cause matches as a living one, and without a cause it does nothing at all. A check through an object filter is refused, as no row sets one.

  A death runs the row's death slot inside the killing hit, before its hooks. First the death damage: the area damage around the dying entity at its level, scaled as a card's damage, with the row's crown-tower percent, air and ground reach and death pushback. Its buildings-only column does not narrow it.

  Then the death spawn, as many as its count on the ring of its radius. A row that gives its children a fixed priority, the Skeleton Barrel's container, asks the lane of the entity's point before each child and turns the ring over across the width in lane 1 and along the length for the top team. Its i-th child is taken as (80i)^2 nearer by a selection.

  With no radius, a single child goes on the entity's point and several together in front of it, the entity's collision radius and the child's away toward the enemy.

  The children are at the entity's level re-based. Each walks at once when it has a speed and stands when it has none, or deploys for its own deploy time when it has no hit points. It is registered in the pass of the kill and untargetable at first.

  Then come its death projectiles, as many as the death spawn's count, which the row keeps at one or more under a death projectile as under a death spawn.

  Each is launched from the dying entity's point and height with it as launcher and owner, at its level re-based, with no target. It is aimed at the row's spawn radius from that point, at an angle that starts at the row's angle shift and steps by an equal share of the circle, the bottom side's turned across the width and the top side's along the length. Each has its id at once and joins the live list at the tick's closing cleanup.

  The Phoenix's fireball, with no spawn radius, is aimed at its own start, so its first flight visit, the next tick, lands it at once. Its area damage and push come first, then its impact makes the egg.

  A row that pushes its children makes each on the dying entity's point and flies it back to its ring point. It flies one step of 250 per movement visit for as many whole steps as fit in the distance, the first in its registration visit.

  A child without hit points - a bomb - stands where it is made. As its deploy ends its state visit runs its death slot without the death hooks and asks for its removal, and it leaves at the next tick's closing cleanup.

  A death whose row sets a second or third death spawn, a starting buff taken back, or a death spawn's limit, is refused. So are:

  - a least radius below the radius, which draws each ring radius from the battle's random source
  - a child without hit points that has a range
  - a child that is a building with hit points, paths to its point or starts an action of its own

  A death projectile drawn from a least radius, counted by a spawner's limit, held by its launcher's targeting component as a sweeping or hooking one is, or launched by a clone, which would hand what its impact makes its clone answer, is refused.

  The elixir a death pays is described with the kings' elixir above. The battle tells its observers of a hit before the death it causes runs, except a typed hit, whose death runs before its type's actions.

  A building stands without a movement component while it deploys. Its deploy ends in the standing state through the resume, and its targeting component visits it from the next tick. It targets and attacks as a princess tower does, the owner's building tests being the same for both, with its row's minimum range plus its collision radius as the closest a target may stand.

  A character with a lifetime loses its hit points by the decay in its hit-points visit, the third component pass, from the tick after its deploy ends. Each visit adds the maximum times 100,000 over the lifetime, over 20, to a carry of hundredths, and the whole hundreds leave its hit points. The visit that takes the last one runs its death slot without the death handler, and it leaves at that tick's closing cleanup. Its death action alone, not its killed action, is then scheduled on it with itself as the cause, and runs in the tick's next pending pass.

  A building's spawner runs in its state visit from the end of its deploy. Each visit takes 50 ms off its timer, which starts at its row's start time. At 0 or below it fires one child for a row with an interval, or the whole wave at its spawn radius for one without. The next firing is the interval away within a wave or the pause away after it, never less than 1 ms, a timer below 0 carrying.

  A spawner with a limit fires only while it has firings left, each firing taking one. One whose row destroys it at its limit asks to leave on its visit after the last. The tick's closing cleanup removes it with no death slot and no death handler, as the Phoenix's egg leaves once it hatches.

  A row that gives its spawner's children their deploy sets each deploying for its own deploy time. Each child stands in front of the building, the building's collision radius and its own away toward the enemy, at the building's level re-based, walking at once, with no first-tick immunity, registered in the post-hook pass.

  A unit's own spawner runs the same block from the end of its deploy whether it walks, attacks or is pushed. A stun holds its timer, and a wave due on the tick the unit dies still comes. Without a spawn radius its children stand in front of it toward the enemy, not where it walks, which no run holds. A row that sets an angle shift turns its spawner's ring, and its death spawn's, by that shift plus the angle the unit faces.

  A row that attaches its spawner's children makes them as it enters the deploying state, on the ring of its spawn radius, deploying for its deploy time and facing as it faces. In a card play that is before the unit is queued, so they take the lower ids and every pass visits them first. After its registration visit each is attached.

  From then on a rider's movement visit places it at the parent's spawn radius behind the parent's heading, turned by its own angle shift and its share of its arc, at its own flying height, and it faces as the parent does. It targets and attacks with its own columns. An attached rider is untouchable, answers no attacker and takes no part in collision.

  When the parent leaves the holder, each rider hears of it last in the removal notice and forgets it. It runs its death slot without the death handler and is removed in the same cleanup, whose removals repeat until a round removes nothing.

  A buff applied to a parent, made or refreshed, is then handed to each of its riders in the order they were made (every row but a Clone one, the row its AttachedInheritAs names in its place), with the parent's time, level, source, side and parent. Each rider's apply is its own, with its own refusals, its own refresh or instance, which its own visit steps. So with the same time the instances run out on the same tick, and removing the parent's touches none of them.

  The rider then acts on its instances as any unit: a freeze switches its targeting off at its own combat gate and a rage speeds its attack step, while its position stays the attached placement's.

  A buff an action of the rider's own puts on its owner, the rider, is applied as on any unit. The Ice Wizard hero's freeze, handed to a Ram Rider's or a Goblin Giant's riders, runs its remove action on each rider as its instance ends. The slow it spawns lands on the rider itself, refreshing the slow the parent's own remove action handed over on the same tick.

  Refused:

  - a row that attaches riders placed directly, spawned or waiting its turn
  - any other buff applied to a rider other than through its parent
  - a rider whose parent may not attack
  - a parent that lets its riders go under an inherited ignore list

  A death spawn's inherited ignore list changes nothing, as no id list it would join is ever written. A spawner's drawn ring, a spawner that gives its children a fixed priority, and a spawner child without hit points, a building, pathing to its point or starting an action are refused.

  A unit whose row pushes or runs an action as its shield breaks, hiding, a buff at a share of its hit points, elixir, a spawner's launches, its second and third characters or its push is refused as it is created.

  A buff scales the spawner's step as it does the speed. The flag that holds a spawner's timer, and the hit-points visit's dedupe expiry and shield tag, are not modelled. A spawned champion is handed over to its side's champion controllers, which do nothing with it, as no ability is modelled.

  A row untargetable when spawned starts every unit of it with a spawned child's immunity, whatever makes it. The Phoenix's egg refuses every character that asks for it until its sixth state visit, while a spell's area or impact still reaches it.

  A character's own row's tags join its tag word at each pre-hook, from the first after it is made. The egg's keep it standing unless something pulls it, make it an obstacle to the others' avoidance with no push pass of its own, and pass it by under a filter that excludes the Giant Buffer's and the Chef's enchantment. A row that sets any other tag is refused as it is created.

- A unit whose row sets a reflected-attack buff, the Electro Giant, strikes back inside a hit that reached its hit points alive. It does so after the hit points (or the shield) took it and before its death runs, so a hit that kills it is still struck back.

  Nothing is struck back while its own hit-speed scale of 100 is below 1, under a stun, or for a hit without an attacker.

  The attacker must be a character, building or tower - a melee hit, a character's area hit, a dash's landing - or a projectile whose root is one and whose row does not ignore the reflect. An area effect, a buff from one and what an area effect launched are never struck back. A source riding on a parent is replaced by the parent.

  The one struck must not be untouchable, must be of the other team, and must stand strictly inside the reflect radius plus both collision radii, the squares compared unsigned.

  It takes ZapFreeze for 500 ms at the Giant's level on every reflected hit, so its combat gate drops its reference that tick and its attack timer stands still until the stun ends.

  The reflected damage, 192 at level 11, or 128 against a crown tower, comes once per attack. That is keyed by the attack count, raised by every hit not cancelled for distance, and the id of the one struck, the parent for a rider's shot, listed on the Giant and never cleared. So the shots of a Goblin Giant's Spear Goblins or the Ram Rider's bola deal the damage once until the parent attacks itself.

  The damage is not dealt inside the hit but appended to the damage queue, which the drain reads again after every hit. So it lands in the same drain after every hit queued before it (an arrow or a shot that lands on the attacker that step meets its shield first), and the attacker it kills dies there. It is dealt through the damage entry with the Giant as the attacker and the battle's holds and the hidden test lifted.

  The hit does not pierce immunity:

  - an unkillable one struck (the Berserker hero under its ability) is held at 1 hit point
  - one whose tag word holds NO_DAMAGE (an evolved Skeleton Army's spectral soldier, a ghost of the evolved Minion Horde, the evolved Lumberjack's ghost) keeps the buff and takes none of the damage

  A damage-taking action's hit has the action's cause as its attacker. The character, building or tower that caused it is struck back as at any hit (the Three Musketeers' bayonet), the area effect that ran it never (the evolved Electro Giant's pulse).

  Refused:

  - a typed hit a character deals or a kill by a killer on a reflecting unit
  - a damage-taking action's Reflected hit on a reflecting unit
  - a character's damaging buff on a reflecting unit
  - a reflect from a Giant whose buffs scale its damage (no Ladder card gives it one)
  - a reflect onto another reflecting unit or inside another reflect

  A reflect onto a unit that carries riders hands the buff to them.

- A troop whose row sets a special range and a special projectile, the Fisherman, loads its special in its targeting visit's attack part.

  With no load pending and no tag forbidding it, a reference arms the load at SpecialLoadTime when it is:

  - one whose centre stands between its own radius plus SpecialMinRange and its radius plus SpecialRange from the unit's centre, both bounds inclusive and the unit's own radius left out
  - not in its hit list
  - not a building under SpecialIgnoreBuildings

  Each visit, the arming one included, takes its hit-speed scale of 50 off the load and ends there. So the unit neither attacks nor dashes and its speed is 0: it stands. While the load runs its attack range is SpecialRange, and a reference beyond it is dropped with the load. Once the load is spent the next visit is an attack tick, whose hit fires the special projectile at no damage.

  A special projectile without a ring, a ring without a special projectile, a building's special and a special that lists its targets are refused with their row.

  The hook, a projectile with a drag-back speed, holds its owner's targeting visit from its launch until it leaves the battle, and the attack timer stands still meanwhile. Arriving at its target it hooks. It turns around where it stands, on the ground, its start that point and its aim its owner's position pulled in by DragMargin and both radii.

  - A building or a crown tower sends the owner to state 13, following the hook, which stands still and drags him toward itself at DragSelfSpeed until he is within both radii, then is let go and asks him to move.
  - Anything else goes to state 12, following the hook, and the owner to 14. The hook flies back at DragBackSpeed times the target's Speed column, at least 30, over 100, the target's state visit putting it on the hook each tick. When its next step would reach the aim, it is let go there and impacts again.

  Neither impact deals damage. Each puts the row's target buff on the target.

  Entering 12 or 13 drops a reference out of attack range and switches both components off. Entering 14 switches both off. Every change into or out of the three ends with the combat gate, which switches the targeting component back on in 13 and 14.

  The hook's leaving makes its owner forget the hold, and makes the unit that followed it forget it and resume. That does nothing in 12, whose next state visit asks to stand.

  Leaving 12 or 13:

  - switches both components on
  - resets the route, the point aimed at, the move time (so a unit that walks and waits by turns, a Golem or a Giant, starts a whole stretch of walking wherever in its stretch it was hooked), the charge and the pushback's bytes
  - drops a reference out of range when the targeting component was on as the change was asked (the Fisherman in 13, never a troop let go from 12, which keeps its reference however far it was pulled and goes on with the attack it was part way through)
  - moves a unit off a cell it may not stand on

  Leaving 14 to attack switches the movement component on.

  A hook whose owner has left or has its targeting switched off ends where it is, with no impact. One whose target left arrives at once where the target stood.

  A hook arriving at a dashing troop that the untouchable test passes by (a dash under a row with a dash immunity, the immunity left after one, a tunnel or a parent) does not hook it. Its arrival is an ordinary one, at its aim, with its impact. A dashing troop the test does not pass by is hooked.

  A troop hooked in a jump, or dashing under a row with a jump height (a jumping Mega Knight), is put down first: on the ground, asked to stand, which ends its dash, and its route emptied. Then it is pulled.

  A unit let go on a cell it may not stand on while it still follows the hook (the Fisherman a hook on a building drags onto the river) is moved off it with the hook's owner's y as the reference row.

  A unit a Clone reaches while pulled leaves 12 or 13 for the clone state 8 as it leaves them for any state, but with no standing test: it stays where the hook had it, on the river too, and its move apart starts from there. A troop set up so no longer follows the hook, which flies on toward its aim and, arriving, is let go and impacts, asking no state of anyone; the Fisherman in 14 goes on as the hook leaves. A Fisherman cloned in 13 has his targeting switched off by the setup's combat gate, so the hook's next step finds its owner lost and ends it where it stands, with no flight back and no impact.

  A hook arriving at a target someone else holds a lock on, on channel 0 (a capture, the Boss Bandit's or a rerolling Barbarian's lock on itself), does not hook it either. It makes an ordinary arrival, at its aim, with its impact. The lock is asked again on each step and only the arrival's answer counts.

  Refused:

  - a hook arriving after a lock it met during its flight is gone
  - a pulled target someone else then holds (the hook lets it go at once)

  A row with a MorphCharacter, which the hook's leaving would morph back into, is refused with its row.

- A troop whose row sets a special projectile without a special range, the evolved Firecracker, never fires it. Only the range loads a special hit, and the other columns that would make one due are set by no row. A unit that fires is refused the charge whose complete charged shot would take the special projectile instead. Every shot is the row's own projectile.

- A Clone's area effect hits once, on its first update, and its hit schedules its hit action, CloneAction, on each unit the index query of its circle finds, in the query's order and before any damage.

  Each such unit is alive, not a building, not hidden, of its own side, not untouchable, untagged against targeting and clones, no unit a Clone passes by (IgnoreClone) and no clone, the standard game cloning no clone. The area effect is the cause, and the action runs in the tick's last pending pass, unit by unit in id order.

  Its perform tests the unit again - no unit a Clone passes by, no clone, alive, riding nothing. Then it puts the Clone buff on it for 500 ms at once, at the Clone's level, and makes the clone:

  - on the unit's point, kept inside the arena
  - of the unit's side and of its row, or of the row its row names as its cloned version (an evolved unit's plain row, Ghost_EV1_Clone for the evolved Ghost)
  - at the Clone's level re-based on that row's rarity
  - with 1 hit point of 1, a shield of 1 of 1 while the unit's is up and none of 1 once it is broken
  - facing as the unit faces
  - in the clone state 8, whose combat gate switches its targeting off and keeps its reference

  It is registered at once, its registration visit moving nothing. It joins the live list at the tick's closing cleanup, whose fold starts it as any spawn: its own row's starting action is scheduled then.

  It copies every buff its unit carries with the time left, the Clone buff among them. The copy is the unit's list read back into the clone's, so a buff the clone's row put on it as it was made (a Ghost's buff while not attacking) is written over, and the clone lists the copies alone. No copy schedules its start or stacked action or resets the charge for a charge range, which only an apply does. A copy runs its remove action as any instance does, as it runs out or is removed.

  Then the creator walks the clone's list from the first. At each index whose row is not cloned (NotCloned, the evolved Minion Horde's ghost buff among them) it removes every instance of that row with the same parent, from the last to the first, as any removal, the remove action scheduled on the clone. It goes on at the next index, so an instance moved down onto the index just walked is kept.

  The runs its unit has listed stay the unit's. The creator tells each of them of the clone, and only an air-to-ground run (Vines, the evolved Hunter's net, the evolved Royal Hog's fall) does anything with it. It schedules its row on the clone, the unit its cause, so the clone's run copies the unit's and the clone is held, pulled down or let go with its unit. A clone that ends a falling evolved Royal Hog's descent has no landing actions, its row saying a clone triggers none.

  Then the clone moves back toward its own side and the unit, set to 8 too unless it dashes, forward. Each move is a single-cell route to the point 250 steps of 500 away, kept inside the arena, at a budget of 125 a visit whatever the row's speed or buffs, for ten visits of the run pass. After those each still in 8 is resumed. Leaving 8 empties the route and forgets the point.

  The unit leaves the attacking state for 8 as any change does, which clears its target-lost timer only while its targeting component is switched on. The state setter reaches the component through its switch. So a unit stunned or held when the Clone reaches it (an evolved Hunter's net) keeps the timer, and once the hold ends it stands until the timer reaches the attack finish time before it takes a target and walks.

  While its targeting is off the movement reads no reference, whatever the component keeps. So a unit that flies direct paths (the Skeleton Balloon) heads for its route's node, the centre of the point's cell, rather than for its reference.

  A unit that carries riders is cloned with them. After its own clone and the two moves, each rider in their order is cloned the same way - its clone made on the rider's point, the rider set to 8 and moving apart too. Its clone is attached to the unit's clone on the rider's angle, so it rides there from its next movement visit. The unit's clone never enters the deploying state and makes no riders of its own.

  A clone dies at its first damage. Its death slot runs as any, its death damage at its level. Its death spawns, as every spawn of a clone, are clones of 1 hit point. So is an action's spawn, whose spawner asks its source, so the Skeletons a cloned Skeleton Balloon's barrel spawns as it dies are clones.

  A building without hit points (that barrel) and a unit whose hit destroys it without a projectile (the Skeleton Balloon) are cloned as any unit. The setter only marks a building without hit points.

  The game marks a projectile a clone's in two cases: the hit of a clone whose hit destroys it (the Fire Spirit, the Wall Breaker), and a clone's death projectile (the Goblin Demolisher's). Only what the projectile spawns reads that mark.

  Such a clone launches its projectile at its own level as the original's. One that only deals its damage and push, applies its target buff (the Ice Spirit's freeze), hops on (the Electro Spirit's) or makes an area effect whose row spawns no character (the Heal Spirit's, which carries the mark in the game and reads it nowhere) lands as the original's would.

  A clone's death projectile that also spawns characters where it lands (the Phoenix's fireball) makes each of them a clone of 1 hit point of 1, set up before the add that registers it. The Phoenix's egg is a clone, and so is the Phoenix it hatches, as every spawn of a clone.

  An area effect takes the clone answer of what makes it. These are a clone's:

  - one a clone's action spawns (the landing area of a cloned hero Balloon's trooper, the ability area a cloned Goblinstein doctor starts with)
  - one a dying clone leaves (a cloned Suspicious Bush's)
  - one such an area effect's own action or Goblinstein's ability run on it makes

  Its hits read that for nothing, and its damage and buff land as the original's would. But a character an action's spawn makes from it is a clone of 1 hit point, its spawner asking its source as it asks any, with no original for the setter: the Bush Goblins of a cloned Suspicious Bush are clones. The clone doctor's ability area connects to nothing, the clone being in no card group chain, and its doctor never casts, so it never tethers.

  The clone setter runs on each child last, after its deploying state and its deploy countdown, and sets nothing else. So a death spawn with a deploy time (a rider clone's Spear Goblin) is a clone made deploying. Its countdown runs as any unit's, it takes no part in collision until the countdown ends, and an area hits it while it deploys.

  A unit still deploying as the Clone reaches it is cloned with its countdown. The countdown setter puts the clone into the deploying state with the unit's countdown, and the clone then leaves it for state 8 with the countdown kept. The unit goes to 8 with its own.

  The clone mark is set before the countdown, and the deploying state's entry asks it before its push: a Mega Knight's clone, made while the Mega Knight deploys, enters the state and pushes nobody. The clone of a unit that carries riders is set up first and its countdown stored after, so it never enters the deploying state and makes no riders of its own. Each rider still deploying is cloned through the creator with its own countdown, its clone entering the deploying state and leaving it for 8, and attached to the carrier's clone.

  The hit points of a clone are built from its row at the Clone's level before the clone setter cuts them to 1 of 1, so a row with a lifetime drains its clone by the step of its full maximum. A clone whose drain takes 1 hit point or more a visit (the Goblin Demolisher's kamikaze form) dies in its registration visit: its death runs as any, its death projectile launched. The creator lists its move apart all the same; the run's start takes the movement component only while it is switched on, so for the dead clone it sets no point and moves nothing. The unit moves apart alone.

  In 8 both countdowns run down 50 a visit with no resume at 0. A move apart that reaches its ten visits while its unit's countdown still runs sets NO_MOVE on itself, which the unit carries while the run is listed, holding it still, and asks again each visit. Once the countdown has ended it resumes the unit and finishes.

  The burst clock of a unit that walks in bursts steps by half the buff-scaled 100 a visit, so under the Clone buff's speed of -100 it stands still.

  Refused:

  - a clone made by anything but an area effect
  - a clone of a unit whose rider runs an air-to-ground action
  - a clone of a building with hit points, whose lifetime the hit points' visit counts differently for a clone
  - a unit whose hit destroys it and launches a projectile that spawns a character or a projectile, makes an area effect that spawns a character, or runs an action (the evolved Ice Spirit's), whose spawns would be clones and whose action no recording shows for a clone

  A clone's death projectile that spawns an area effect or a projectile, runs an action, applies a buff or hops on is refused as it is launched.

  A clone's area effect is refused when it launches or drops a projectile, chains an area effect that does more than hit, or makes a guard or a summon, where no recording shows what the mark does. One that only hits is chained unmarked, as a cloned Rage Barbarian's rage chains its damage.

  A champion, a hero form among them, is cloned as any unit, of its own row with its ability. The champion slots follow no clone, so a tap naming the clone is refused and pays nothing. A paid ability is requested of the champion alone, the clone walking on.

  A clone is otherwise tested as its unit by every area effect. The area effect's own filter asks of a clone, after the tags, IgnoreClone and untouchability, its buff row's HealPerSecond at level 0, the column itself. It refuses the clone when it is 1 or more - the ten healing rows, HealSpirit, Heal and the like - and passes it as any unit otherwise.

  The same filter answers the area damage's validator, the buff's walk and its apply, which asks the test again as the walk reaches each target, the pull, the hit action loop and the chooser. The update deals its damage before its buff block, and the buff's walk asks alive before the filter, so a clone a Zap or a Freeze kills is not stunned by it.

- An area effect is an object of its own kind in the area-effect band of ids. It is created by a death's DeathAreaEffect, a unit's direct hit, a unit entering the deploying state or placed directly, at the level of what made it re-based on its own rarity. It is admitted at the next cleanup, where its row's starting action is scheduled on it.

  Its update is its post-hook:

  - 50 ms off its countdown
  - the hits its hit speed and offset put in the step, one on the first update for a hit speed of 0. Each is the area damage around it with itself as the owner, its own side spared when it hits enemies only, its own test of a target, the crown-tower percent and its pushback.
  - then its buff

  It leaves once its countdown is below 1. On its first update it creates the area effect its row chains, at its point and level.

  Its buff reaches each character of the tick in its circle, in the order they joined, that is alive, not untouchable, not waiting to deploy, of the side the row names and passing its own test of a target. Of the king-class towers only the first takes a buff that deals damage. The buff lasts the row's buff time, capped by the life left and one hit speed more when the row says so.

  A projectile with a radius and a target buff, the Snowball's, buffs its circle the same way after its impact's damage, so a victim the damage killed takes nothing. It buffs each character inside the circle around the impact point, in the order they joined, of the side the row names, alive, not untouchable and not waiting to deploy. There is no test of its own, so air and ground alike, while the row's target limit lasts (1000 for an empty column).

  The projectile is the source, and the buff is applied at its level for the row's buff time plus its increase for each step of that level. Only the sparing of the launcher's own side is held by no run.

  A projectile without a radius buffs its one target, after its damage or before it when the row says so, with no alive test. A target riding or dashing under a dash immunity gets nothing unless the row applies it even then. The lingering immunity after a dash does not count.

  A buff applied before the damage is on the target when the damage kills it, so its death sees it. As the death slot runs, after the dying object's own area effect and before its death damage, each listed instance with a death spawn makes its characters, unless the dying object is a building. They are made:

  - in front of it toward its enemy, or on its point for a row that spawns on the same location
  - at the instance's level, for the opponent when the row spawns for the enemy
  - deploying for the child's own deploy time and facing the way the dying object faced, without a first-tick immunity

  The Witch Mother's curse is held this way. Also held:

  - the Electro Dragon's freeze after its damage on each target it hops to
  - the target buff before the damage on a circle, which passes a hidden unit by as the damage does (a unit the evolved Goblin Cage holds on its point keeps walking at its own speed once released, `ProjectileBuffHiddenTest`), by the reference battles of a hero Ice Wizard shooting around an evolved Goblin Cage that holds a Giant

  A buff with a death spawn that would make another listed one give way, a death spawn on a ring and a child that is a building, paths or starts an action are refused.

  On the entity the buff component, in slot 3, refreshes an instance of the same row instead of listing a second: by source for a stacking row, by side for one specific to a player, and, on client 16.635.1, by cast for one specific to a cast (CastIdSpecificBuff), the evolved Cannon's barrage damage buff and the evolved Firecracker's fireworks buff in 16.635.3.

  A cast is the king's count of card plays before the play. The play stamps it on its units, its spell's area effect and its projectiles, and every object hands it on to the objects it makes. So a cast-specific row refreshes only an instance a source of the same cast listed, and each new instance keeps its source's cast. The side test is made first and decides alone when a row sets both. The counts are per king, so the same count from the other side matches too. A placement and a play outside a match each stand for a cast of their own, which no other object shares. A cast-specific row applied from a source whose cast is not carried is refused.

  It keeps the longer time and the higher level. Otherwise it lists a new instance at the level re-based on the buff's rarity.

  Its visit, in the fourth component pass:

  - takes 50 ms off each instance from the last
  - deals the damage over time due: every hit frequency over 50 visits, the damage per second at the instance's level rounded down to what one hit can deal, a crown tower's per-hit column or that raised by its percent, a building's percent
  - then the heal over time due: the heal per second at the instance's level for the hit's period, a crown tower's whole heal per second raised by its percent, capped at the maximum or the row's over-heal share of it
  - removes an instance whose time is gone

  The speed budget, the follower's step, the attack timer, the dash and special loads, the combat gate, the deploy step and the spawner's step read the largest boost of the listed rows times what the largest slow leaves. A stun of -100 makes the hit speed 0, so the combat gate drops the reference and switches the targeting off while it lasts.

  A buff that sets a projectile, a chain, spawns, a morph, actions, tags, a team switch, a shield, a hit point or damage multiplier, an action on a damage reduction, a pull or push, a parent that controls it or an inherited row is refused.

  An area effect whose buff boosts one target or lasts longer by level, a hit action but a Clone's, a shape, a filter, spawns, launches, a life condition, following a target, tags, a lifetime that grows by level, a push floor or gate lift or one hit per target, is refused. An area effect that reaches hidden units is refused when it reaches one, and none is yet created by an action.

  What kills with an area effect's hit is the area effect.

- A unit whose row has a shield is created with it full, at the row's ShieldHitpoints at its level by the card hit-points rule on the row's own rarity (256 for a SkeletonWarrior at level 11, 240 for a Recruit).

  Every hit meets it in the subtraction - a direct hit, a projectile, an area, an area effect, damage over time, a typed hit, a kill - and it takes the whole hit up to its value. The rest is lost, and the hit points take nothing.

  A hit that brings it to 0 breaks it in the hit's own pass. Every character with an attack sequence mode that is attacking the unit has its attack reset, as an inferno's ramp is. The hit-points visit tags a shield that is up (HAS_SHIELD, read only by data expressions).

  A level change recomputes the shield's maximum, and a shield that is up keeps its share on a rise. A swap keeps the shield. Its maximum is the new row's when that row has a shield and stays as it was when it has none. The hero Dark Prince on foot keeps the maximum of its mounted row, so a heal fills a shield still up back to it (changedata-shield-v1), and a level change of a row without a shield keeps that maximum too. A broken shield stays at 0.

  No pending damage is modelled, so a shielded target is never judged doomed.

  After the attack resets, a hit that breaks it schedules the row's ShieldLostAction on the unit's own holder, with the row's own delay. Its cause is what the hit came from:

  - the unit of a direct hit or its area
  - the projectile of an impact
  - the area effect
  - the buff's source
  - a typed hit's source
  - the killer of a kill
  - nothing for the king's circle, a clearing or a tiebreaker

  With no delay it is queued for the battle's next pending pass of the tick, phase 2 after a direct hit and phase 3 after an impact. It is started at once when the break comes inside a pending pass.

  The evolved Wizard's makes Wizard_EV1_ShieldLostExplosion on the Wizard for its side and level, which hits every enemy within 3000 once the next tick and pushes it 3000. The evolved Recruit's gives the Recruit RecruitsCharge_EV1. The Wizard's start group gives it a buff that only lives while HAS_SHIELD is in its tag word, gone the tick after the break, and a looping effect that only shows something.

  The shield's pushback, set by no row, is refused.

- Every unit carries its attack sequence as the loader builds it, a row without one keeping a single element. For an order of two or more, the entry at the index gives the next hit's projectile and damage. An action moves the index, only below the order's length.

  A continuous-damage attacker, whose row's numbered VariableDamage columns make a Hittime sequence (the Inferno Tower, the Inferno Dragon, the Mighty Miner), moves it by itself. Every attack step stores the window its attack timer has reached, each entry's VariableDamageTime taken off the timer in turn, the last window open-ended, so the ramp is the timer.

  - A new reference, in range or not, or none - the visit dropping one, a stun's gate - resets the attack timer but not the index or the load countdown. The next attack step walks the reset timer back to the first window. The timer moves again only once the load countdown, reloaded by every hit, is at or below the hit speed, so an Inferno starts over its LoadTime less its HitSpeed after its last hit.
  - While the timer runs, a reference the validator still accepts but that stands beyond the plain range is dropped at once, a building keeping no extension and a unit 25, and the target-lost countdown starts.
  - The reference's death leaves the timer and the index as they are for the target-lost countdown to clear. The hit that breaks the reference's shield resets the timer in its own pass.
  - While it walks, such a unit's range is 500 less, both what it stops at and what it tells the rest of the battle, so it routes to where it stops.

  An entry may name an action in place of a projectile, the Three Musketeers' bayonet. The hit that reads it neither launches nor deals a direct hit, but schedules the action on the unit with its target as the cause, cancelled for distance or not. The buff on damage follows a hit that was not. The hit does not tell the unit's listening actions that its attack ended.

  The bayonet's action runs in the unit's pending pass and turns round onto the target, which queues 123 for the drain: 314 at level 11 by the musketeer's own row, with no crown-tower share.

  The starting-attack row is queued on the unit at the first attack step, at each hit and on a new reference already in range, and runs in its phase-2 pass. It picks the entry:

  - target_is_ground is the reference's row's flying height 0, so buildings and crown towers are ground and a flying row is not, and none while the unit's targeting is off.
  - A character reference answers from its tag word first, as every reader of its layer does: ground under FORCE_IS_GROUND alone, not under FORCE_IS_AIR alone, at height 0 under both. So a grounded evolved Royal Hog inside the musketeers' reach takes the bayonet.
  - A force tag on a reference that is not a character is refused.

  Refused:

  - The other modes - StaticLoop, HittimeLoop, Manual.
  - An entry that sets a column the loader neither reads nor counts as presentation. Beside its projectile, damage, action and a continuous-damage attacker's window, it reads its timing, range and pushback overrides, its first projectile, its number of targets and whether it remembers them, the action its hit runs in place of the row's attack action, its start delay, and its buff on damage and that buff's time.
  - An entry's action beside a projectile, in a sequence of one, in a continuous-damage attacker or on a charging row.
  - An entry with neither a projectile nor an action on a unit that fires.
  - A morph of a unit a continuous-damage attacker references, whose replacement is not established.

  The Mighty Miner's lane switch, its ability, is refused when it is requested. A card play never requests it.

- A unit's row may name an action it runs as it attacks. Every hit not cancelled for distance schedules it on the unit after the hit's own action, direct hit or launch, with the hit's target as the cause. That is once a hit, at the launch for a unit that fires and never at the impact.

  A hit with no target schedules nothing. The action runs in the unit's pending pass of the same tick, its next action right after it. A spawn row, the evolved Mega Knight's uppercut and the evolved Baby Dragon's wind are read. A unit whose row names any other is refused, as is a row's action run on the unit as its own cause.

  The evolved Valkyrie's spawns its mini tornado and the evolved Royal Giant's its push area. Each takes the unit itself as the source, so the side, level and parent are the unit's rather than the target's.

  - The Valkyrie's next action gives it a buff for 500 ms whose tag keeps its enemies from pushing it. A buff's tags join its carrier's tag word at each pre-hook while it is listed, and a buff that sets any other tag is refused.
  - The Royal Giant's area hits enemy ground units within 3000 and pushes each 1000 from its centre. Its one tag only hides the pushback's presentation, and an area effect with any other tag is refused.

- The evolved Mega Knight's uppercut is a run on the unit. While it is listed the unit carries the row's GameTagsToSet, as for any run; the run raises no tags of its own as it starts (client 16.402.17 raised NO_ATTACK and NO_MOVE there; client 16.635.1 leaves them to the row, whose 16.635.3 row sets NO_ATTACK and NO_MOVE_ALLOW_ATTRACT, so a Tornado pulls the Mega Knight through its uppercut). As it starts the run keeps its targeting component's target. A target without a movement component, a tower, ends it at once.

  Its first update, the next tick, pushes the target 4000 through the pushback entry, past every gate of the request. The push is away from a point 50 behind it on the line from the nearest tower of its own side - its side's princess towers, then the king facing the Mega Knight - so toward that tower. The update also schedules the uppercut's group on it with the Mega Knight as the cause. A target already switching lanes, the only state the push point tests, is not pushed, nothing is scheduled on it and the run ends.

  The Mega Knight then carries LOCK_TARGET for a step, while the row's LockTargetAfterUppercut is on (the default), and the run waits out its follow-up delay of 1000 ms, the unit held only by the row's tags (16.402.17 raised NO_ATTACK, NO_MOVE and LOCK_TARGET after the push and NO_ATTACK and NO_MOVE on each step of the wait). The run ends sooner when it has no target or attacks another one in range. While its target is another one out of range, it marks the uppercut's target in its targeting queue at priority 0, which the next pre-hook's flush empties without taking. A target that leaves is forgotten and the next update ends the run.

  There is no follow-up dash: a row with one is refused, as is a push through the request's gates and a start with no current target.

  The group knocks the target up: a run of 21 steps raising DISABLE_PHYSICAL_INTERACTIONS_WITH_OBJECTS and, while more than 149 ms are left, FORCE_IS_AIR, under which the pre-hook makes it an air unit. So the Mega Knight, which attacks only ground units, drops it. Each step pushes the arc's height, 1900 on the first, 10000 at the top, -2100 on the last, with that height as the push's floor. The run lands it with its route reset.

  While airborne it prepares its route as an air unit does, a single node to its goal. The movement visit's ground test reads the unit's layer from its tag word, never its row, and so do its hovering tests: a hovering unit under either force tag hovers no longer. A second later the root holds it 400 ms with NO_MOVE.

  - Each step of a knock, the landing one too, resets the charge of a unit that tracks one, as the state setter resets it. The progress goes back to 0 (or to no charge for a unit with no charge range of its own or from a buff) and the charged strike is dropped. So a charging unit loses its charge's speed and its charged hit in the air and lands with no charge built.
  - A knock postpones the unit's ability while the run is listed: a request in the air is cast once the run has left. A cast already begun and the ability's follow-up state go on through the knock as on the ground, the activation in the air included. The knock neither reads nor sets the state, and the cast reads no tag, height or layer.

  So the hero Wizard's ability can start its ground-to-air run while a knock (the hero Giant's slap) holds the hero in the air. The run reads the layer from the tag word and the live height.

  - Below the run's flying height it climbs only the rest of the way, its counter the transition less the live height's share of it. The hero's height 0 on the knock's first step still counts as in the air, a full climb. The climb's turn, the swap to the flying row and the rest of the run are as from the ground.
  - At or above the run's flying height (the evolved Mega Knight's uppercut throws the hero higher, and a tap on the knock's starting step is still cast), a ground row starts held at once, its counter the whole less one transition. It never climbs, so its path is not reset and the action at the height, with the swap to the flying row, is not scheduled. The hold pushes the run's height through the knock and after it lands, and the descent is as from a climb.
  - A flying row there would only wait the whole, which no recording holds: it is refused.

  A knock started on a unit in a jump, or dashing under a row with a jump height (a jumping Mega Knight), puts it down first, as a hook or a capture's completed drag does. It is put on the ground, asked to stand, which ends the jump or dash, and its route emptied. The knock then lifts it as any other.

  A unit dashing under a row with no jump height (a Golden Knight's chain dash) is not put down: the knock's own code tests no dash. So it is knocked as it dashes, its dash going on under the knock's tags and arc, and the push that came with the knock (the hero Giant's slap, the uppercut) carries it as it dashes.

  A knock, an uppercut and a push away from the cause take a unit that carries riders (a Goblin Giant, the Ram Rider's Ram, the hero Ice Wizard) as any unit. None of them asks whether it carries riders, and nothing is started on the riders. Each rider, which never moves on its own, is put back on its ring around the carrier on every movement visit, so the riders go up, along and down with their carrier and keep attacking from there.

  A knock and a push away from the cause take a clone as any unit: none of the action, its run or the push asks whether the unit is a clone (the hero Giant's slap of a Skeleton King graveyard skeleton, which pushes and then knocks it: graveyard_clone_slap_push). An uppercut by a clone, an uppercut's push reaching a clone, and a knock, an uppercut or a push on a rider are refused, as is a knock row with the no-collision tag. The tags an uppercut or a knock raise are told to the observers as the word changes.

- The evolved Baby Dragon's wind is a run on the dragon that makes BabyDragon_EV1_wind_aeo 1500 ahead of it toward the enemy side, whichever side it is on, for its side and level, the dragon its parent. The area effect follows the dragon with the same offsets.

  The row is a singleton. A later hit while the run is listed and not finished gives the wind its whole six seconds back and makes no second one. Once the run has finished, on the update after the wind has left, the next hit starts a new run. As the dragon leaves, after every notice of it, a live wind keeps at most two seconds of its life. The run looks its wind up among the live objects only, for that cut as for the re-trigger. So a wind made in the step the dragon dies, still waiting to join the battle when the dragon leaves at that step's cleanup, is not found and keeps its whole six seconds.

  Its shape is an 8000 by 9000 rectangle. Each update lists the characters of both sides that its filter passes - no building, tower, hidden, jumping, dragged or cloning unit - whose circle meets the rectangle. It schedules on each a choice by team with the wind as the cause, which runs in the unit's phase-3 pass. The choice gives a friend a 130 speed buff and an enemy a -30 one, both for 100 ms, from the unit itself. The dragon's own IgnoreBuff refuses its friend buff.

  A shaped row whose hits would do more than schedule their hit action, or whose shape is not a rectangle, is refused. The wind's starting group plays two effects, one at an age of 5950 ms through an action run at its ages.

- A hiding building schedules its row's action beside each effect of its hide handler, on itself with itself as the cause: its OnDisappearAction as it starts to hide, its OnAppearAction as it rises, the turn back from more than half-way down included. The state visit runs outside every pending pass, so a row with no delay runs in the building's phase-3 pending pass of the tick.

  The evolved Tesla's appear row makes Tesla_EV1_AppearStun at its point, the Tesla its parent. It is a ring whose radius grows from 1 to 6000 over its 30 updates and whose hit action, a buff spawn, reaches every enemy once - a troop strictly within the radius plus its collision radius, a building by its square. It stuns each enemy for 500 ms with one hit of 174 on the buff's first visit, 53 on a crown tower.

  Its disappear row is a looping effect, which changes nothing in the battle. The rise's area object and push are refused with the row that sets them.

- An action spawn row of the location class and the projectile type launches one projectile from its owner's point at the row's start height, with the owner as its launcher and owner, at no target. It aims toward the point its two target expressions give, each evaluated on the owner as it starts.

  A row of the plain spawn class does the same from the row's start height above the owner's live height. Aimed by neither expression it would launch at the owner's current target, which is refused while the owner holds one.

  Killed by an enemy, the evolved Wall Breaker leaves a mini Wall Breaker where it stood and launches its barrel explosion on its own point. Its combat gate has switched its targeting off before its killed action runs, so the barrel has no target.

  A select that passes its delay on schedules its chosen part with the delay it was itself scheduled with.

  is_moving() answers 1 for a character whose movement component is on and whose speed budget is above 0, so it reads the state: walking yes, deploying, attacking or held by a tag no.

  The evolved Furnace's start group lists its normal spawn, paused while any of its three pause tags is in its tag word, and a flip-flop on is_moving(). After 20 moving steps the flip-flop raises FURNACE_STOP_QUICK_SPAWN for 50 ms and FURNACE_DELAY_NORMAL_SPAWN for 400 ms.

  Its attack group, at the attack's start and before each hit, lists a pause that holds UNIT_CUSTOM_TAG_1 and starts the singleton quick spawn. The quick spawn fires on its first step and every 2400 ms after: a side choice by a variable it toggles, which 200 ms later launches a spirit 1500 to one side and 1000 behind the Furnace that lands as one Fire Spirit. Walking again for a second, the run pass force-stops every pause, and the next one the quick spawn.

- The evolved Cannon's start group runs its barrage, whose first update makes all nine bomb areas and finishes. Each area is at its absolute offset times 500 across the arena and the Cannon's y plus its vertical offset times 500, forward for its side, for the Cannon's side and level with no parent.

  Each area's starting action, on the next tick, drops a bomb onto it from its row's height, with the area as its launcher and as what it was dropped onto, at the height over the area's lifetime in steps as its speed. The bomb's row counts the height above its aim into the distance left and steps its height straight toward the aim's, so it lands 23, 25 or 27 visits after its launch.

  Its area impact deals no damage but pushes every unit inside by 1000, as its row always pushes, and applies a 400 ms buff that hits once for 281. A bomb off the arena, which would end the barrage, a bomb placed by its relative offset, and a projectile under the height column that homes are refused.

- A projectile's impact may make an area effect that follows the projectile's target, a unit or a tower, when it still has one. The area stands on that object's point at each update and, as it leaves, lets it go and stays on its last point for a row that stays after its parent dies.

  The evolved Ice Spirit's area hits once, on its sixtieth update, for 110 and a 1100 ms freeze around the unit it followed. A row with its follow behaviour set to the target on an area effect no impact made is refused.

- A projectile with a starting action schedules it on its own action holder as the holder admits it, itself the cause, so it runs in the projectile's phase-1 pending pass of the next tick, before its first flight step. The listening runs of that holder, from the last listed down, change the damage of each hit along its way and of its impact, and hear of each hit before its damage is taken off.

  The evolved Executioner's axe controller keeps the axe's level and rarity. It answers a hit's damage with the strong amount, 268 at level 11, when the target's distance from the axe's start less its radius is below 3500, else 179, whatever the crown-tower rule. On a strong hit it schedules its effect on the target and, while the axe's sweep is in its first half, asks for a push of 1000 away from the axe's start.

  A projectile's expressions read only get_ping_pong_projectile_distance, the distance from its start its sweep stored before its last step. The axe's waits swap its row on it, for rows alike in the battle.

  Refused: a plain hit's action, which the class reads under another name than the row writes, a push below 1, a projectile swap that also sets a character's row and a swap to a row whose battle columns differ.

- A projectile whose row lays its spawn along the length, the evolved Snowball's, aims the projectile its impact spawns at its own minimum distance straight beyond its aim, up the length for side 0 and down it for side 1, in place of the fan's line. The spawned projectile starts from where the parent landed, at the height it aimed at, as any spawned projectile does.

- A projectile whose row has a custom movement, the evolved Snowball's rolling snowball, does nothing in its flight visit after the first visit's work: a run on it moves it. Its starting group lists a roll and a capture on it, the roll first, in its phase-1 pass of the tick after its spawn.

  The roll takes its destination as it starts: its distance forward for the projectile's side, and its distance across toward the middle of the arena. The destination is walked back toward the projectile 100 at a time while the point is off the map or a cell of the not-placeable value alone lies in its two by two block.

  Each of its steps buffs, once per object, what its query finds within its radius - a building tested by its square - under its filter, the projectile as the source at its level. It then moves the projectile its speed toward the destination. The step that reaches it puts the projectile there, buffs again and releases it, and it leaves at that tick's cleanup with no impact.

  The rolling snowball's buff deals 179 at level 11 on its first visit, 53 to a crown tower, and slows by 35 percent.

  The roll runs the deflection pass at the projectile's point and height as it starts and after each step. The rolling snowball's row names its own deflection action.

  - An enemy Deflect that touches it deals its parent the projectile's damage as for any spell-like projectile, but does not turn it. It schedules that action on the projectile, the parent its cause, which counts one more deflection and hands the projectile the parent's side as it starts.
  - At its next step the roll, seeing a count above the one it last turned for, takes its destination again from where the projectile stands, for its new side, by its distances less what it has rolled along each. So a roll turned partway rolls back by the rest of its distance.
  - A deflection that finds the projectile already at the most it takes (MAX_DEFLECTION_TIMES, so its third) counts and hands the side all the same, then releases the projectile where it stands. It leaves at that tick's cleanup with no impact, and its captures are let go as at the roll's end.

- The capture claims its units through the battle's target locks. Each step it forgets the captures that left the battle or died. It then captures each unit it claimed on its last step, from the last, whose lock on channel 0 the projectile now holds and that still passes its filter.

  - The first capture of the run schedules its first-capture action on the projectile, the unit its cause. The rolling snowball swaps its row for one alike.
  - Every capture schedules its captured-object action on the unit, the projectile its cause, and gives the unit its capture buff for 99999 ms, the projectile its parent and source, which stops it.

  Below its count it then asks a lock on each unit its query finds within its capture radius, nearest first, with its priority shifted 16 up less the unit's distance. The post-pass grants them.

  Every captured unit is dragged each step:

  - Its one-step tag word gains NO_DASH, ABILITY_DISABLED, CAPTURED and two tags whose readers are not modelled, which are refused for a unit that would reach them.
  - It moves toward the projectile by its distance at the capture, less the hide distance, times the sine of its share of the drag time in a quarter turn, times 50 over 1048, over the drag time, and turns to it.
  - Once the drag time has passed, or it is within the hide distance, it is put on the projectile's point with its route reset. The hide action is scheduled on it while its word does not hold the hidden tag. The captured units ride the projectile and stay where it stops.

  A unit in a jump, or dashing under a row with a jump height (a Mega Knight's jump, a Hog Rider's jump over the river), is put down there as a hook puts down what it hooks: on the ground and asked to stand, which ends the jump or the dash.

- The evolved Goblin Cage runs the same capture on itself, a character. It claims only while its targeting component is on, grants its claims and measures its captures from its pull centre, and waits the drag delay and turns the unit to it through the pause before the drag. After a capture leaves it waits its cooldown before it claims again.

  Its capture gives the unit its capture buff, as the snowball's does: for 99999 ms, the cage its parent and source at the cage's level and side, which stops the unit, its attack and its spawner. With a capture buff the drag adds only the capture's tags, never the NO_MOVE, NO_SUMMON and NO_ATTACK it adds for a capture without one.

  Each completed capture raises HAS_CAPTURE in the cage's word for the step. Once per hit frequency, its timer stepping at the cage's hit speed while a capture has reached its drag delay, the capture takes the damage per hit at the cage's level through the damage entry, hidden as it is.

  The buff stacks, so it keeps the cage as its parent. When the cage leaves, its life run out or destroyed, the buff goes with it, the hiding run finishes and the unit walks on.

- A hiding run sets the hidden tag on its object every step, which the next pre-hook folds into the object's word. No attacker takes it, the damage entry refuses it and a filter that drops hidden objects drops it.

  It keeps its cause's id and finishes as its cause leaves the battle, and its tag goes with the next run pass. A run that waits for its cause keeps its id too: once the id is no longer in the live list, it schedules its action on its object, the object its cause, and finishes.

  When the rolling snowball leaves, the capture buff goes with its parent and the locks with their holder at the next pre-pass. Each hiding run finishes and, the tick after, each released unit takes the 4000 ms slow its waiting run schedules.

- Every hit the hit-points bookkeeping lets through - past the battle's hold, the untouchable test and the dedupe list, before the subtraction - is counted by the character or tower that dealt it, once per target per damage.

  - An area hit counts once for each victim.
  - A projectile counts for its shooter at its impact, and a reflected attack for the reflecting unit.
  - A Kamikaze unit's drain and kill count for itself.
  - An area effect's hit counts for nobody.

  The counter goes up by one, and the unit's BuffAfterHits entries are walked in order:

  - An entry whose count is not above the old counter is skipped.
  - The walk ends at the first above the new one.
  - The entry picked is the one whose count the counter just reached.
  - The last entry, picked, sets the counter back to 0, and nothing else does.

  The buff picked is applied to the unit itself, with no parent, for its BuffAfterHitsTime, at the unit's level, the unit its source and its side the side. It is applied inside the hit, so it is first visited in that tick's pass 3.

  A typed hit and a buff's damage over time count too, but a BuffAfterHits row reached by either is refused, as is a list of buffs, counts and times of different lengths.

  The evolved Barbarian's Rage, refreshed by every hit, runs out 59 ticks after the last. The evolved Bat's heal, 1000 ms against a 1200 ms hit, lists a new instance each hit and heals 38 on its tenth and twentieth visit, up to twice the Bat's maximum, as AllowedOverHealPerc 200 makes the heal's cap.

- A buff with a spawn object runs a spawner of its own on each instance, in the buff visit after the step of the instance's time and before its damage over time.

  - The instance starts with the row's spawn start time on its timer and its spawn limit as the firings it has left.
  - While it has firings left and an interval of at least 1, each visit takes half the carrier's spawn rate off the timer.
  - At 0 or below it makes one child, takes one firing off a limited spawner and puts the interval (within a wave of SpawnNumber firings) or the pause (after one) on the timer, never leaving it below 1.

  The child stands in front of the carrier as a character's spawner places it, the two collision radii away toward the enemy at the first quarter turn the in-front test accepts. It is at the carrier's level, with no deploy and no first-tick immunity.

  A child of the carrier's own row whose row limits its group is linked into the carrier's chain right after it when the carrier is in a group. When that chain already holds the row's GroupMaxSize units, counted from its first unit to its last (a unit that died counts until it is released), the firing makes nothing and still counts as one of the instance's firings.

  A row with SpawnerAliveRequired files the carrier's id beside the child, and the cleanup's fold admits the child only while the carrier is listed and not removable. Otherwise the fold releases the child where it stands in the queue: it leaves the chain it was linked into and is dropped, never admitted, with no notice to any other entity, action or side list. A carrier killed in the tick its duplication fires loses that child.

  The evolved Skeleton's SkeletonDuplication_EV1, 500 ms from each hit, makes one evolved Skeleton on the tick of the hit.

  A Clone's copy of the instance carries its spawner as the original left it: its timer, the firings of its wave and the firings it has left. A copy that stays fires on the clone as the original's fires on the unit, and its child, as every spawn of a clone, is a clone of 1 hit point. A row that is not cloned is taken off the clone as it is made, before any visit; 16.635.3 marks SkeletonDuplication_EV1 so (16.402.22 does not).

  Refused: a carrier that is a tower or a building, a child without hit points, a building, and a child that paths to its point or starts an action.

- The buff visit walks the listed instances from the last to the first, and each instance's hit of damage over time and its heal come in its own turn. The hit is lowered by the carrier's damage reduction as the list stands then and queued for the damage drain, whose entry lowers it once more by what is listed there.

  So a reduction listed before the hitting instance whose life condition fails on the same visit, as the Valkyrie hero's whirlwind reduction does on the visit its spin ends, lowers the hit once. One listed after it is removed before the hit's turn and lowers nothing.

- A holder lists a run at the end of its runs before it calls the run's own start, so a run that start begins at once is listed, and stepped in the run pass, after it. The attack chain's pick and begin are such a start, and its OnChainBegan interval comes after the chain.

  With the swap-with-last removals of the earlier runs, this decides whether a taunt's run steps before or after the chain's end on the step the Valkyrie hero's whirlwind ends. Before it, as the game does, nothing is queued and the hero selects anew once it may attack again, for one step before the taunt forces it back. Other classes' starts are kept where the row's start puts them, not read class by class.

- The evolved Skeleton Balloon's starting group runs a health trigger and its singleton pop, whose run counts the row's TotalBalloons.

  A second start re-triggers the run and drops a container: the area effect of ContainerAeoList at the index of the list's length less the balloons left. It is made at the owner's point moved by that index's OffsetXList and OffsetYList entries - the one along the length times the team's direction - for the owner's side and level, the owner its parent. Then one balloon fewer is left.

  Falling to 75% re-triggers it while the balloon lives, dropping the first container. Its death action re-triggers it as it dies, which drops every balloon left in turn:

  - With one left, the last container.
  - With both (a balloon shot down from above 75% or ended by its Kamikaze hit, whose health trigger stops), the first container and then the last, each at its offsets.

  Each container's life end spawns seven Skeletons. The action spawner's ring for a fixed priority asks the lane of the point before each child and is turned over across the width in lane 1 and along the length for the top team. The i-th child is taken as (80i)^2 nearer, and a ring that pushes its children puts each on the point and flies it back.

  A child of a source other than a character made with a deploy time faces along the length toward the enemy. A row that neither spawns nor launches on its death keeps no death spawn count, whatever it sets. is_kamikazing answers a character's Kamikaze hit's end.

  Refused: a re-trigger with no balloon left while the owner lives, and one with two or more left as it is dead when the row names a double container (no row does).

- A buff's start action is scheduled as a new instance is listed, never on a refresh. Its remove action is scheduled as any instance is removed - the expiry, the removal of the not-attacking buff, a parent leaving - but not at a death, which removes nothing.

  Each is scheduled on the carrier's own action holder with the carrier as its cause and the row's own delay, as any action with no request to start now. It starts at once inside any pending pass of the battle, another entity's included, and is queued anywhere else.

  So a rider handed a buff in its parent's pending pass runs the start action there, on the parent's step, though a card play's riders are listed before their parent. An evolved Electro Giant's pulse debuff lowers a Goblin Giant's or Ram Rider's riders on the step it lowers the carrier (`BattleElectroGiantTest`).

  A start or remove action written inline is not modelled. A clone's copies of its unit's buffs schedule no start action. Their remove actions run as any instance's, and as the creator takes a copy of a row that is not cloned off the clone.

  A group's ExecuteIfTrue gates its parts where it is scheduled, so the evolved Royal Ghost's invisibility groups, gated on `!is_clone()`, schedule their effects and the 50 ms custom tag on the Ghost and nothing on a clone.

- The evolved Royal Ghost's starting group lists a run on the Ghost that keeps a latch. The latch is set from its start, and each step in the run pass, after the damage area's countdown, set to whether the Ghost is invisible.

  The notice of a landed hit reads the latch the last run pass left, so only the first hit after each invisibility summons. The run keeps the Ghost's reference and its position as the point. It makes two summon areas 2000 to either side of the point across the line from the Ghost to it, by the Ghost for its side and at its level, the Ghost their parent. Each lists a run that spawns the area's summon on its point on the area's first update, the tick after the hit, then finishes.

  The summon - Ghost_EV1_Summon_Left or _Right - is made deploying for its row's 800 ms at the area's level, named after its row and the count of characters made before it. It takes the Ghost's reference through the validator's take mode when that is alive, faces the point, is queued with no registration visit and runs its combat gate. Its starting effect is presentation.

  The damage area is made at the point by the Ghost's run pass three ticks after the hit, and an object that leaves is forgotten as the runs' reference.

  A clone Ghost, a hit with no reference, a summon row that hits at once, runs an action on its summons or spawns them without their deploy, a summon with a starting action that does more than show something and a summon area that follows its parent are refused.

- A unit whose row has a buff while it is not attacking, the Ghost's Invisibility, takes it for 100,000 ms as it is created, unless its row starts without it, which loads the countdown with the row's time instead.

  Every hit its tags let through sets the countdown to 50. The state visit of that tick, while it attacks, counts it to 0 and takes the buff's instances off, so the unit is visible from the tick of its first hit and while it attacks.

  Leaving the attacking state loads the row's time, 2000 for the Ghost and 50 for the evolved Knight, whose Fortify is back in the state visit of the tick its attack ends. Outside that state the state visit counts it down while the buff is not listed and no reference is within the attack range, a reference in sight but out of reach included. At 0 it applies the buff for 180,000 ms.

  A row without that range gate, the Suspicious Bush's, holds the countdown instead while the unit stands within its reference's radius plus half its own sight range of it. The Bush's buff stays listed until the state visit of the tick its one hit kills it, so for the Bush the answer changes nothing.

  While a listed buff makes a unit invisible, it refuses every character or tower that would select, keep or hit it, a building without hit points aside.

  - The selector passes it by, and an attacker that held it drops it and falls back to its default target.
  - An area's damage reaches it when its row lets it.
  - A projectile or area effect asks it as it would a visible unit, and a projectile already in flight lands on its stored target without asking.

  The same answer, asked with the asker, is what a spawned child's first-tick immunity refuses: a character or tower, or no asker, but not a projectile's hop or an area effect.

- A hovering unit's route may cross water, at the water cost, and a push never moves it off water: the Ghost walks over the river in the moving state, with no jump.

- A unit whose row has an area effect on its hits makes it after each direct hit's damage to its one target, at its own point, for its side and at its level. It is admitted at the tick's closing cleanup, so it first updates on the next tick, and an area hit makes none.

  A hit's buff on damage that stacks is refused: its instance would keep the attacker as its parent. No row has one.

- The command pass runs every command whose tick has come, a late one at once, as the networked and local regimes do. The replay regime, which drops a late command, is not modelled.

- The mode never ends the match and always lets the entity tick run.

- The game's phase names other than PostGameObjectTick (Any, PostTickInit, PostComponentTick) are refused. The one row that writes PostComponentTick is named by no other row.

- The assumptions of the movement and targeting rules themselves are listed in [Troop Pathfinding](pathfinding.md#assumptions) and apply unchanged.
