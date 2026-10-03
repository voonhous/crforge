package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.BattleCommand;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.EntityActions;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.ActionOwner;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.Berserk;
import org.crforge.core.battle.action.GhostEvo;
import org.crforge.core.battle.action.GoblinHutLifeState;
import org.crforge.core.battle.action.InertAction;
import org.crforge.core.battle.action.ShapeSelector;
import org.crforge.core.battle.action.TargetIndicatorAttack;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.match.EvolutionItem;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.match.MirrorItem;
import org.crforge.core.battle.match.VariantItem;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.crforge.core.pathfinding.math.FixedMath;
import org.crforge.core.pathfinding.move.MovementState;
import org.crforge.core.pathfinding.state.StateQueries;
import org.crforge.core.pathfinding.target.TargetView;
import org.crforge.core.pathfinding.target.TargetingState;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Plays the hundred runs in which an action, a death, a building or a unit's own spawner spawns
 * characters, or a unit charges, jumps or dashes, through {@link Battle} and holds the battle to
 * them tick for tick.
 *
 * <p>The rows are the game's own, built from its action rows. Four runs give the battle an action
 * owner: an entity with an action holder, a position, a side and a level and nothing else, on which
 * the row is scheduled in the command pass of its tick. In {@code witch_hooks} a Witch placed
 * directly runs its row's own starting action, whose rounds spawn Skeletons around it and link each
 * into its group. In {@code bush_goblins} a group spawns two Bush Goblins to either side of a
 * bottom-side owner a tick apart, the second pushed one unit off the first as it is registered. In
 * {@code brawler_goblins} a top-side owner spawns four Goblin Brawlers, the side flipping both axes
 * of the location. In {@code gift_knight} the owner spawns a Knight on itself that walks at once:
 * its registration visit takes its target and a first step. {@code abort_instigator} drops a spawn
 * that Knight caused when it dies. In {@code tombstone_death_hook} a Wizard's projectile kills a
 * Tombstone while it deploys, and the Tombstone's death action, scheduled as it dies with the
 * projectile as its cause, runs in its phase-3 pass and spawns SkeletonKing on it, a champion
 * handed over to its side. In {@code goblin_wave} the Goblin Hero's second-wave rows are scheduled
 * on a Knight of each side, which has a run of its own: each spawns four deploying goblins around
 * its Knight, placed by the Knight's team and the middle of the arena, and links each into the
 * Knight's group. In {@code golemite_convert} a baby golemite turns into an Elixir Golem, which
 * dies on tick 110 and spawns two golemites on the ring of its death spawn radius, each immune for
 * its first ticks and killed by a tower later. In {@code golemite_death_damage} a Golemite's death
 * damages a tower and a Knight around it and pushes the Knight away. In {@code archer_ev1_vs_tower}
 * and {@code archer_ev1_knight} the evolved Archer's starting-attack row picks its attack sequence
 * index by whether its target is within 4500: its long shots launch the double-damage arrow of its
 * second entry, and once a Knight closes in it goes back to its first. In {@code
 * area_effect_direct} two area effects placed directly hit a Knight and a tower, one of them
 * pushing the Knight, and leave as their countdown runs out. In {@code gift_select} two owners each
 * schedule the gift delivery's select on the same tick, from a random state of the run's own: each
 * select draws its part as it is scheduled, in the command pass, the first owner's draw first, and
 * the part it chose spawns its unit in that owner's phase-1 pass.
 *
 * <p>Four runs place a building directly with the towers fighting. In {@code cannon_knight} a
 * Cannon deploys, stands, takes the default tower as its reference on the tick after and locks on a
 * Knight that walks into range, firing until the Knight destroys it, its hit points falling by its
 * lifetime's decay meanwhile; in {@code mortar_knight} a Mortar drops the Knight once it comes
 * inside its minimum range. In {@code tombstone_life} a Tombstone spawns Skeletons in front of it
 * in waves of two from the end of its deploy, until its decay kills it and its death spawns four
 * more on the same point; {@code goblin_hut_life} does the same with a Goblin Hut's three Spear
 * Goblins and its one death spawn on its own point.
 *
 * <p>Every spawned child is registered inside the pass that made it, joins the live list at the
 * tick's closing cleanup and is first visited on the next tick. A child of an action or a death
 * cannot be targeted until its sixth state visit, so the towers lock on it six ticks late; a
 * building's spawner gives its children no such immunity.
 *
 * <p>Three runs place a spell's area effect directly on a walking or attacking Knight, and hold its
 * buffs. In {@code rage_knight} Rage, with its chained RageDamage, refreshes a 1000 ms buff on the
 * Knight every six ticks: from the tick after the first, the Knight walks at 78 and steps its
 * attack timer by 65, and it goes back to 60 and 50 once the last refresh runs out. In {@code
 * zap_knight} Zap's 500 ms stun drops the attacking Knight's reference and switches its targeting
 * off on the tick it lands, and the first gate after the buff goes switches it back on. In {@code
 * poison_knight_tower} Poison, stacking by its source, hits a red Knight for 92 and a princess
 * tower for 23 every twenty visits and slows the Knight to 51.
 *
 * <p>Six runs hold air units, created at their flying height, routed to one node, crossing water
 * and meeting only units on their side of height 0: {@code minion_musketeer}, a Minion shot down by
 * a Musketeer while a Knight cannot reach it; {@code balloon_tower}, a Balloon and its bomb, which
 * dies on the ground as its deploy ends; {@code balloon_river}, a Balloon and its bomb dying over
 * the river, the bomb no default target; {@code balloons_cross}, two Balloons crossing over a
 * Knight with the towers passive; {@code lava_hound_river}, a Lava Hound dying over the river and
 * its pups flying back to their ring points; and {@code baby_dragon_left}, a Baby Dragon firing
 * from its height. A further unit is placed at its own level where the run gives one.
 *
 * <p>Three runs play a spell by a command: {@code fireball_knight_tower}, a Fireball from the blue
 * king tower landing on a Knight and a princess tower and pushing the Knight; {@code
 * zap_knight_cast}, the Zap run with its area effect cast at the snapped point; and {@code
 * goblin_barrel_tower}, a Goblin Barrel whose Goblins stand in formation around its landing point;
 * {@code arrows_skeletons}, Arrows' three waves of chained arrows, landing on their ring points. A
 * run lasts to its last projectile position. {@code log_goblins} and {@code barb_barrel_knight}, a
 * thrown projectile whose impact launches a rolling one, which hits what its body passes. Four runs
 * hold shields - {@code recruit_tower}, {@code guards_knight}, {@code poison_guards} and {@code
 * tombstone_crazy_life} - and every hit a shield took.
 *
 * <p>Two runs place a unit whose own spawner fires while it walks and attacks: {@code
 * witch_left_lane}, a Witch whose four Skeletons stand on the ring of its spawn radius around it,
 * the first wave while it walks and the second while it attacks a princess tower; and {@code
 * night_witch}, a Night Witch whose two Bats a wave stand at its sides, the ring turned by its
 * angle shift and the angle it faces, as does the Bat of its death spawn.
 *
 * <p>{@code goblin_giant_tower} plays a Goblin Giant, whose two Spear Goblins ride on it: made as
 * the play sets it deploying and queued ahead of it, so they take the lower ids and are visited
 * first, placed behind its shoulders a tick behind it, shooting the tower from their height while
 * nothing can target them, and let go in the cleanup that removes the Giant, each leaving a Spear
 * Goblin where it rode.
 *
 * <p>Three runs hold the charge and the river jump: {@code prince_tower} and {@code
 * dark_prince_tower}, a unit charged by the steps it walks, twice as fast once full, whose first
 * hit lands at once for its special damage; and {@code hog_river}, a Hog Rider whose route crosses
 * the river and which jumps it to the first land cell beyond. One holds the dash: {@code
 * bandit_knight}, a Bandit's wind-up, its dash stopped in range of a Knight and its single landing
 * hit. Each is also held to every charge completed and lost, every state a movement pass asked for,
 * every dash started and every landing. No run holds a Mega Knight: the battle refuses it, as its
 * push as it deploys is not modelled. {@code hog_clip_cannon} plays a Hog Rider with a Cannon
 * behind it, inside its sight but beyond its sight clip, so it takes the princess tower ahead.
 *
 * <p>{@code ram_rider_tower} plays a Ram Rider: the Ram charges into the princess tower while its
 * rider, which targets troops only, takes no target; the run lists no actions, so the rider's
 * attachment and release are held from its {@code unit_spawner} log.
 *
 * <p>{@code match_elixir_150s} is a Ladder match: both decks shuffled with the battle's source, and
 * on every tick both elixirs, hands and cooldowns, the timeline and the crowns held to the
 * reference's trace, and every play's match code - a card still in the queue refused with 9, one
 * the elixir does not cover with 0xd. {@code match_knights_king} plays a match to its end: the
 * king's fall, the winner, the end timer, the fallen king's circle and its kills, nothing attacking
 * after the end, and the stop. {@code match_overtime_tiebreak} and {@code match_overtime_draw} play
 * past overtime with equal crowns into the tiebreaker: its clearing with nothing to clear, its idle
 * window, every step of its drain, and its end by a fallen princess tower, or as a draw by equal
 * towers. {@code match_building_cards} plays building cards from the hand: a Cannon snapped to its
 * tile corner, a Tombstone moved off the Cannon's tiles, a Cannon pulled back from across the river
 * and an Elixir Collector that pays its king, each living as the same building placed directly.
 * {@code mirror_knight} plays a Knight and then the Mirror, which repeats it one level up for one
 * elixir more and goes to the back of the queue itself. {@code mirror_fireball} does the same with
 * a Fireball, its first Mirror refused with 0xd, the elixir short of the item's cost. Each is held
 * to every Mirror item - the card it repeats, its level and its cost - and to the last card kept.
 * {@code merge_maiden_mounted} plays the Merge Maiden with 6 elixir or more, so it comes as the
 * mounted maiden, a flyer, for 6; {@code merge_maiden_normal} plays it after a Zap with less than
 * 6, so it comes as the maiden on foot, for 3. Each is held to the item its play carried - the
 * option, its cost and the Merge Maiden's deck index - and to both kings' elixir and hands.
 *
 * <p>{@code electro_wizard_knights} and {@code ice_wizard_knights} play a wizard onto two Knights:
 * the card names no unit, so it is cast as a spell, its area effect zapping or chilling the Knights
 * while its starting action makes the wizard in the same tick. Both end before the wizard's own
 * first attack.
 *
 * <p>{@code royal_giant_tower} and {@code elite_archer_knight} fire a projectile at a constant
 * height: the Royal Giant's cannonball starts at 1500 and descends onto the tower it homes on, and
 * the Elite Archer's arrow flies level at 2000 past a Knight, the first two arrows re-aiming at it
 * on their first two steps. {@code snowball_knights} casts a Snowball onto two Knights, whose
 * impact damages and pushes both and then slows both with its target buff. {@code
 * witch_mother_skeletons} shoots Skeletons with a curse applied before the damage, so each dies
 * carrying it and leaves a Voodoo Hog for the other side. {@code electro_dragon_knights} hops an
 * Electro Dragon's bolt across three Knights, freezing each. {@code firecracker_knight} bursts a
 * Firecracker's shell into a fan of five explosions. {@code axe_man_knights} sweeps an Axe Man's
 * axe out past two Knights and back, hitting each on the way out and again on the way back, while
 * the thrower waits for its return. {@code hunter_point_blank} fires a Hunter's ten pellets at a
 * Knight close enough for all ten to hit it as they are launched, and {@code hunter_range} fires
 * them from further off, each stopping at the first Knight it hits. {@code ram_rider_bola} has a
 * Ram Rider snare a Knight with her bola, again with each throw while the snare still holds. {@code
 * giant_buffer_knights} plays a Giant Buffer behind two Knights: it claims both through the
 * battle's target locks, casts its ability for eighteen ticks, and fires a projectile at each,
 * whose impact enchants the Knight, so every third hit either lands on the princess tower carries
 * the added damage. The looping effect the enchantment chooses only shows something; the reference
 * leaves such rows out of its runs, and so does the log here. {@code miner_princess} plays a Miner
 * that tunnels from its king tower to a point beside the enemy's princess tower, hidden from the
 * tower until it surfaces there. {@code goblin_drill_princess} plays a Goblin Drill, placed as its
 * building's footprint, whose dig tunnels there and morphs as it surfaces into the building, which
 * takes its target in its registration visit, deploys, and makes its damage area at once; the area
 * leaves at the next tick's opening cleanup.
 *
 * <p>{@code electro_wizard_tower_defence} plays an Electro Wizard behind a tower two Knights
 * attack: each attack hits its reference and then the nearest other enemy in range, or its
 * reference again when there is none, each hit a whole hit with its own hit id followed by its
 * ZapFreeze, which a later hit refreshes; it goes on to stun a princess tower between its arrows.
 * {@code mini_sparkys_knight} plays Mini Sparkys at a Knight, each hit one target with the same
 * buff, so the Knight stays stunned until 500 ms past the last of the three.
 *
 * <p>{@code inferno_tower_giant_knight}, {@code inferno_dragon_zap} and {@code
 * mighty_miner_knight_tower} play continuous-damage attackers, whose hits ramp through the windows
 * their attack timer walks: an Inferno Tower that keeps its ramp through a Giant's death and starts
 * over on a Knight, an Inferno Dragon whose ramp a Zap resets as its stun drops the target, and a
 * Mighty Miner that walks 500 closer than its range before it stops.
 *
 * <p>{@code ghost_river_wizard_tower} plays a Ghost that hovers over the river, invisible from its
 * creation: a Knight cannot take it until its first hit makes it visible, and two seconds after its
 * attacks end it is invisible again, so the princess tower that had locked on it falls back to its
 * default target while the arrow and fireball already in flight still land on it. {@code
 * battle_healer_knights} plays a Battle Healer whose area object heals the friendly Knight beside
 * it as it deploys, and whose every hit makes an area effect that heals its friends where it
 * stands.
 *
 * <p>{@code bush_princess_tower} plays a Suspicious Bush, invisible from its creation, that walks
 * past the towers untaken until its one hit on a princess tower kills it: its death makes an area
 * effect that never hits and whose starting action spawns two Bush Goblins beside where it died.
 * {@code bush_valkyrie_knight} plays one that a Valkyrie's swing kills on its way while it is still
 * invisible, its goblins coming where it died.
 *
 * <p>{@code pending_shield_guards} plays Guards at a Musketeer's range: the Musketeer's shot on its
 * way to a Guard would kill it, but the Guard's shield is up, so the princess tower that has not
 * fired yet keeps the same Guard rather than turning to another.
 *
 * <p>{@code tesla_giant_passing} plays a Tesla that takes its default target as its deploy ends,
 * hides once its counter reaches its hide time and rises when a Giant walks into its reach: it hits
 * the Giant once, loses it as the Giant walks on, and hides again. {@code tesla_hidden_spells}
 * holds who may reach a hidden Tesla: a Fireball that lands while it is going down deals its
 * damage, one still in flight as it goes down lands for nothing, a Zap passes it by, and a Freeze,
 * which reaches hidden units, damages and freezes it, the freeze holding its counter. Each is held
 * to the deploy end's targeting visit and to every change of the hide counter that shows something.
 *
 * <p>{@code earthquake_barbarians_tower} plays an Earthquake over Barbarians and a princess tower:
 * each hit of its damage over time comes on the Earthquake's own clock, 950 ms into each second of
 * its age, so every target is hit on the same ticks whenever it walked in, and a Barbarian that has
 * walked out is still hit while its instance lasts. {@code earthquake_tesla_overlap} plays two
 * Earthquakes overlapping on a hidden Tesla and a Knight that walks in late: each Earthquake lists
 * its own instance and hits on its own clock, so the hits interleave.
 *
 * <p>{@code tornado_group_off_lane} plays a Tornado over five Barbarians: each update pulls every
 * one in its circle toward its centre, and the next movement visit adds the pull to the route step,
 * so they are dragged off their lane and walk back to it once the Tornado has gone. {@code
 * tornado_heavy_light_tower} plays a Tornado over a Giant and a Knight beside a princess tower: the
 * pull is a share of each unit's own speed, so the Giant moves less than the Knight, and the tower
 * takes the damage but does not move. Each is held to every pull: the targets, the vector to the
 * centre and the push accumulators before and after.
 *
 * <p>{@code mega_knight_group} plays a Mega Knight onto three Knights: its card casts its
 * appearance before it makes the unit, which lands six ticks after the play for 430 on each Knight
 * and pushes each, and its push as it enters the deploying state finds nobody, the index being
 * empty in the command pass. {@code mega_knight_jump} plays one that jumps onto a Knight and lands
 * on it, its appearance hitting no one. Each is held to every push a unit makes as it enters its
 * deploying state: its radius and distance, what its query found and whom it pushed.
 *
 * <p>{@code lightning_defenders_tower} casts a Lightning over a group defending a princess tower:
 * its three strikes go to the tower, the defending Knight and the Musketeer, each the enemy in its
 * circle with the most hit points and shield not struck before, and each lands a tick after its
 * launch and stuns what it hits. {@code royal_delivery_group} casts a Royal Delivery whose last
 * update drops its crate onto its own point, the area effect leaving as it does; the crate lands a
 * tick later on the group around it and makes a Recruit. Each is held to every launch of an area
 * effect: its chooser's candidates, those it refused and struck before, and the projectile.
 *
 * <p>{@code heal_spirit_group} places a Heal Spirit that jumps at two enemy Knights fighting its
 * own: its projectile's impact makes the HealSpirit area effect at the impact point, whose one hit
 * on the next tick buffs the own Knights and Minion in its circle, healing the Knights four times.
 *
 * <p>{@code clone_golem_group} casts a Clone over a Golem and a Musketeer: its hit clones both in
 * the tick's last pending pass, each clone of 1 hit point copying the Clone buff its unit took, and
 * the clones step back while the units step forward for ten visits; the Golem's clone dies to
 * Arrows a tick later, its two Golemites clones too, and the Musketeer's shoots a Knight dead. Each
 * is held to every clone scheduled, made and moved apart, and to the buffs copied.
 *
 * <p>{@code clone_rage_group} casts a Clone over two Knights and a Musketeer and places a Rage over
 * the clones and their units: its buff heals nothing, so its filter passes the clones as any unit
 * and each hit buffs them with the units, asking the filter of a clone by the walk and again by the
 * apply. {@code clone_zap_poison_group} casts a Zap over the same clones, whose damage kills them
 * before its buff block, so none is stunned, and a Poison that buffs the Musketeer's clone and
 * kills it with the buff's first damage. {@code clone_heal_spirit_knight} has a Heal Spirit's area
 * heal a Knight and refuse its clone, its buff healing 157 a second. Each is held to every ask of
 * an area effect's buff test of a clone, with the path that asked.
 *
 * <p>{@code electro_giant_struck} plays an Electro Giant into two Knights and a Musketeer: every
 * Knight hit and every shot from inside its reach is struck back with 192 and a stun, which drops
 * the attacker's reference that tick, and the hit that kills it is still struck back. {@code
 * electro_giant_tower} walks one into a princess tower, whose arrows from inside its reach take 128
 * back each, the crown-tower column, until the tower falls to its own reflected arrow. Each is held
 * to every hit that reached the reflect, what it struck back with, and every reflected hit.
 *
 * <p>{@code fisherman_knight} walks a Fisherman at a Knight: in the ring past its minimum range it
 * loads its special standing still and hooks the Knight with it, pulls it back at its speed's share
 * of the drag speed and lets go short of itself, then fights it. {@code fisherman_tower} hooks a
 * princess tower, which cannot be pulled, so the Fisherman drags himself to it at the self-drag
 * speed and hits it until its arrows kill him. Each is held to every load armed, every state the
 * hook set, and the hold and the pull its projectile's leaving ended.
 *
 * <p>{@code graveyard_tower_defender} casts a Graveyard on a princess tower while a Knight walks in
 * to defend it: the area effect's group spawns a skeleton every half second or so at a point its
 * position expressions work out from the area effect's own point and side, a point beyond the
 * arena's edge going one unit right and then clamped into it. {@code graveyard_right_side1} casts
 * one for the top side on the right half, where the offsets across the width are turned over.
 *
 * <p>{@code skeleton_barrel_tower} flies a Skeleton Barrel straight at a princess tower: its hits
 * deal nothing and kill it no more, and from its first hit its state visit drains its hit points a
 * share at a time until it dies, dropping its container, which dies as its deploy ends and makes
 * seven Skeletons on a ring turned over across the width in the left lane. {@code
 * skeleton_barrel_shot_down} has a Musketeer and the tower shoot one down before it hits, and the
 * container falls where it died. Each is held to every Kamikaze end and drain, and to the lane each
 * ring child asked for.
 *
 * <p>{@code goblin_cage_knight} plays a Goblin Cage that a Knight leaves its lane for: the cage's
 * shake is listed as it starts and does nothing, the cage takes the Knight but its first hit is not
 * yet due when the Knight's third hit and the decay kill it, and its Goblin Brawler, made on its
 * point, kills the Knight. {@code goblin_cage_lifetime} leaves one alone until its decay kills it
 * and its Brawler stands on its point.
 *
 * <p>{@code berserker_knight} places a Berserker against a Knight on one lane: its starting action
 * sets its attack sequence index to 0 as it starts, and every hit it lands flips it, 0, 1, 0, 1,
 * over three equal entries, so it deals 102 every twelve ticks until the Knight kills it. {@code
 * berserker_tower} has one walk into a princess tower and hit it the same way until the arrows kill
 * it. Each is held to the index before and after every start and notice.
 *
 * <p>{@code dark_magic_knight} casts Dark Magic in a Knight's path: its laser ball fires on three
 * ticks twenty apart, each fire finding the Knight alone and putting the strongest of its buffs on
 * it, which hits for 340 two ticks later. {@code dark_magic_group} casts one on five Barbarians and
 * their princess tower: the count of what each fire finds picks the buff, the weakest for six and
 * the middle one for four and for three, and the tower takes its per-hit column. Each is held to
 * the laser ball's start and every fire, with what it found and the timer, and to the princess
 * tower's runs as to a unit's.
 *
 * <p>{@code vines_group} casts Vines over a Giant, a Knight and a Minion: its selector picks them
 * by hit points on three ticks, and the Minion, pulled down to the ground, is taken and killed by a
 * Knight that attacks only ground units. {@code vines_tower} casts it over a princess tower and a
 * Knight: the snared tower shoots nothing until the snare goes, and the third pick finds nobody.
 * Each is held to every selector step and every air-to-ground run's phase change.
 *
 * <p>{@code little_prince_giant} plays a Little Prince against a Giant: its starting-attack row
 * reads attack_count at every hit, putting its first speed-up on at the third and its fastest at
 * the sixth, each alive while its life condition holds, so its shots come 24, 12, then 8 ticks
 * apart; a princess tower kills the Giant, and the far tower, out of range, clears the ramp. {@code
 * little_prince_retarget} has it kill three Spear Goblins one after another, each new target in
 * range keeping the ramp, then take a princess tower out of range, which clears it. Each is held to
 * every read of attack_count and every ask of a buff's life condition.
 *
 * <p>{@code boss_bandit_bandit_knight} plays a Boss Bandit against a Bandit and then a Knight: the
 * two dash at each other on the same tick and both landing hits are refused, and each kill the Boss
 * Bandit makes schedules its row's killed-done check on it, with what it killed as the cause, in
 * its next pending pass; the check matches the Bandit and runs its voice line, and finds no match
 * in the Knight. {@code boss_bandit_tower_bandit} has a Bandit kill a Boss Bandit at a princess
 * tower, whose killed action checks its killer the same way and matches. Each is held to every
 * killed-done check scheduled and every check of an action's cause.
 *
 * <p>{@code goblinstein_tower} plays Goblinstein toward a princess tower: the card links its
 * monster and then its doctor, placed behind it toward the middle, into a group chain; the doctor's
 * starting action makes an area effect that follows it and never hits, whose ability run connects
 * to the monster on its first step and then waits for a cast that never comes. The tower kills the
 * monster, and the run makes its death area where the monster fell, which never hits either; the
 * tower then kills the doctor, its area effect leaves with it and ends the death area in the same
 * cleanup. {@code goblinstein_doctor_first} has a Knight kill the doctor first: the area effect
 * leaves with it, and the monster's later death makes nothing. Each is held to every link and
 * unlink of the chain and to what the ability's run did.
 *
 * <p>{@code card_run_knight_pair} plays the run's Knight on the left beside a second Knight placed
 * directly on the right, both on tick 0: each leaves the deploying state at the end of tick 19, the
 * twentieth state visit, and both walk, are shot and lock their tower on the same ticks. {@code
 * card_run_baby_dragon_pair} does the same with two Baby Dragons. Each record is read at the end of
 * its tick, so the run's own unit is held to the same timing as the one placed beside it.
 *
 * <p>{@code mighty_miner_ability_tower} has its player use the Mighty Miner's ability as it attacks
 * a princess tower: nine ticks into the cast it switches lanes, across to the mirror of its
 * position, hidden and dropped by the tower, whose arrow in flight loses it, and leaves its bomb at
 * the tower, which takes the bomb's death damage; it deploys again on arrival, its reference
 * dropped, and ramps from the start on the other tower. {@code mighty_miner_ability_walk} uses it
 * as the Miner walks at a Knight, which turns to the Miner's tower and walks into the bomb's
 * circle, hit and pushed back up the lane.
 *
 * <p>{@code monk_ability_tower} has its player use the Monk's ability as it attacks a princess
 * tower: eighteen ticks into the cast it shields itself, creates the area effect that follows it
 * and stands in its follow-up state for 79 ticks, keeping its reference; each arrow that comes
 * within the area effect's radius hits the Monk at 35 percent and flies back at the tower, which
 * takes a quarter of it. {@code monk_ability_musketeer} uses it against a Knight's hits, lowered
 * the same way, and a Musketeer's shots, which come back for their whole damage and kill it.
 *
 * <p>{@code parent_buff_goblin_giant} has a Freeze, then a Zap, land on a Goblin Giant while its
 * Spear Goblins throw at a princess tower: the area reaches only the Giant, which hands each buff
 * to both riders, so all three stop at their own combat gates and their instances run out on the
 * same tick. {@code parent_buff_ram_rider_rage} has a Rage refresh its buff on a Ram every six
 * ticks, each handed to its rider, whose throws come 17 or 18 ticks apart instead of 22. Each is
 * held to every hand-over and the rider's instances after it.
 *
 * <p>{@code valkyrie_ev1_barbarians} has an evolved Valkyrie spin among three Barbarians: each hit
 * runs its attack action on itself with the hit's target as cause, which makes a mini tornado on
 * the Valkyrie that pulls every Barbarian and hits each once, and gives the Valkyrie a buff that
 * keeps enemies from pushing it. {@code royal_giant_ev1_knights} has an evolved Royal Giant shell a
 * princess tower with two Knights at its feet: each launch makes an area at the Royal Giant that
 * hits and pushes both Knights back the next tick.
 */
class BattleActionSpawnRunTest {

  /** Writes a match's trace row as the reference lists it. */
  private static final ObjectMapper JSON = new ObjectMapper();

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "bush_goblins",
        "brawler_goblins",
        "gift_knight",
        "abort_instigator",
        "witch_hooks",
        "tombstone_death_hook",
        "gift_select",
        "goblin_wave",
        "golemite_convert",
        "golemite_death_damage",
        "archer_ev1_vs_tower",
        "archer_ev1_knight",
        "area_effect_direct",
        "area_effect_death",
        "golem_death_pushback",
        "giant_skeleton_bomb",
        "cannon_knight",
        "tombstone_life",
        "goblin_hut_life",
        "mortar_knight",
        "rage_knight",
        "zap_knight",
        "poison_knight_tower",
        "minion_musketeer",
        "balloon_tower",
        "balloons_cross",
        "balloon_river",
        "lava_hound_river",
        "baby_dragon_left",
        "fireball_knight_tower",
        "zap_knight_cast",
        "goblin_barrel_tower",
        "arrows_skeletons",
        "log_goblins",
        "barb_barrel_knight",
        "recruit_tower",
        "guards_knight",
        "poison_guards",
        "tombstone_crazy_life",
        "witch_left_lane",
        "night_witch",
        "goblin_giant_tower",
        "prince_tower",
        "dark_prince_tower",
        "hog_river",
        "hog_clip_cannon",
        "bandit_knight",
        "ram_rider_tower",
        "match_elixir_150s",
        "match_knights_king",
        "match_overtime_tiebreak",
        "match_overtime_draw",
        "match_elixir_sources",
        "match_building_cards",
        "mirror_knight",
        "mirror_fireball",
        "merge_maiden_mounted",
        "merge_maiden_normal",
        "electro_wizard_knights",
        "ice_wizard_knights",
        "royal_giant_tower",
        "elite_archer_knight",
        "snowball_knights",
        "witch_mother_skeletons",
        "electro_dragon_knights",
        "firecracker_knight",
        "axe_man_knights",
        "hunter_point_blank",
        "hunter_range",
        "ram_rider_bola",
        "kamikaze_battle_ram",
        "kamikaze_fire_spirits",
        "kamikaze_wall_breakers",
        "kamikaze_ice_spirits",
        "moving_cannon_left",
        "furnace_left",
        "giant_buffer_knights",
        "giant_buffer_musketeer",
        "miner_princess",
        "goblin_drill_princess",
        "electro_wizard_tower_defence",
        "mini_sparkys_knight",
        "inferno_tower_giant_knight",
        "inferno_dragon_zap",
        "mighty_miner_knight_tower",
        "ghost_river_wizard_tower",
        "battle_healer_knights",
        "bush_princess_tower",
        "bush_valkyrie_knight",
        "pending_shield_guards",
        "tesla_giant_passing",
        "tesla_hidden_spells",
        "earthquake_barbarians_tower",
        "earthquake_tesla_overlap",
        "tornado_group_off_lane",
        "tornado_heavy_light_tower",
        "mega_knight_group",
        "mega_knight_jump",
        "lightning_defenders_tower",
        "royal_delivery_group",
        "heal_spirit_group",
        "clone_golem_group",
        "clone_rage_group",
        "clone_zap_poison_group",
        "clone_heal_spirit_knight",
        "electro_giant_struck",
        "electro_giant_tower",
        "fisherman_knight",
        "fisherman_tower",
        "three_musketeers_pekka",
        "three_musketeers_air_building",
        "graveyard_tower_defender",
        "graveyard_right_side1",
        "phoenix_egg_hatch",
        "phoenix_egg_killed",
        "skeleton_barrel_tower",
        "skeleton_barrel_shot_down",
        "goblin_hut_passing",
        "goblin_hut_lifetime",
        "goblin_cage_knight",
        "goblin_cage_lifetime",
        "berserker_knight",
        "berserker_tower",
        "goblin_curse_knights",
        "goblin_demolisher_knight",
        "dark_magic_knight",
        "dark_magic_group",
        "vines_group",
        "vines_tower",
        "goblin_machine_knight",
        "goblin_machine_tower",
        "little_prince_giant",
        "little_prince_retarget",
        "boss_bandit_bandit_knight",
        "boss_bandit_tower_bandit",
        "goblinstein_tower",
        "goblinstein_doctor_first",
        "archer_queen_ability",
        "archer_queen_ability_refused",
        "reference_loss_knight",
        "reference_loss_musketeer_rage",
        "card_run_knight_pair",
        "card_run_baby_dragon_pair",
        "little_prince_ability_giant",
        "little_prince_ability_knights",
        "boss_bandit_ability_tower",
        "boss_bandit_ability_charges",
        "ram_rider_drop_knights",
        "ram_rider_drop_tower",
        "golden_knight_tower",
        "golden_knight_chain",
        "bandit_dash_past",
        "golden_knight_ladder_chain",
        "tower_retarget_knight",
        "tower_retarget_cannon",
        "mighty_miner_ability_tower",
        "mighty_miner_ability_walk",
        "monk_ability_tower",
        "monk_ability_musketeer",
        "skeleton_king_ability_no_souls",
        "skeleton_king_ability_souls",
        "goblinstein_ability_tower",
        "goblinstein_later_plays",
        "parent_buff_goblin_giant",
        "parent_buff_ram_rider_rage",
        "evolution_knight",
        "evolution_hero_mirror",
        "valkyrie_ev1_barbarians",
        "royal_giant_ev1_knights",
        "buff_after_hits_barbarians_bats",
        "buff_after_hits_ghost_evo",
        "shield_lost_wizard",
        "shield_lost_recruits",
        "mega_knight_ev1_uppercut",
        "baby_dragon_ev1_wind"
      })
  void theRunMatchesTheReferenceTickForTick(String name) {
    JsonNode reference = BattleMusketeerRunTest.load("/pathfinding/golden/" + name + ".json");
    // A run made without the towers fighting names no tower level; its towers stand at 11.
    Standard1v1Battle match =
        new Standard1v1Battle(
            GameData.tables(),
            reference.path("tower_level").asInt(11),
            reference.path("towers_attack").asBoolean(false));
    Battle battle = match.getBattle();
    // A run that draws starts the battle's random source from its own state.
    if (reference.has("seed")) {
      match.getWorld().seed(reference.get("seed").asInt());
    }
    // The random state each drawing tick leaves behind: the last draw's.
    Map<Integer, Long> stateAfter = new HashMap<>();
    for (JsonNode draw : reference.path("draws")) {
      stateAfter.put(draw.get("tick").asInt(), draw.get("state").get(1).asLong());
    }

    int[] currentTick = {-1};
    // A run is listed as it starts and a spawn as it is made, so the runs and the spawns are
    // compared as two lists, each in order.
    List<String> actions = new ArrayList<>();
    List<String> spawns = new ArrayList<>();
    List<String> dropping = new ArrayList<>();
    // A run with a unit of its own places it, its further units and its schedules on them as the
    // tower runs do; one without action owners places its units directly. Each unit starts its
    // row's own starting action as it is placed.
    // What each buff did, from before the placements: a unit's start buff is applied as it is
    // placed, on the tick it is placed for.
    List<String> buffLog = new ArrayList<>();
    match.getWorld().addObserver(buffLog(currentTick, buffLog));
    // Every count that applied a BuffAfterHits buff, and every buff's start or remove action, from
    // before the placements: a unit's start buff is applied as it is placed.
    List<String> buffAfterHitsLog = new ArrayList<>();
    match.getWorld().addObserver(buffAfterHitsLog(buffAfterHitsLog));
    // Every action a broken shield scheduled, and every charge reset a listed buff made.
    List<String> shieldLostLog = new ArrayList<>();
    match.getWorld().addObserver(shieldLostLog(shieldLostLog));
    // What every uppercut, knock and wind did, and every change of a watched tag word.
    List<String> uppercutWindLog = new ArrayList<>();
    match.getWorld().addObserver(uppercutWindLog(match.getWorld(), uppercutWindLog));
    List<CharacterEntity> placed = new ArrayList<>();
    if (!reference.get("card").isNull()) {
      placed.addAll(BattleTowerRunTest.deployAll(match, reference));
    } else if (!reference.has("action_owners")) {
      // A unit a card play created is listed with its command, and so is what it morphs into; the
      // command places the one, the battle makes the other, and neither is placed here. A unit an
      // action made, a Clone's clone, is listed with its action and made by the battle too.
      Set<String> commanded = new HashSet<>();
      for (JsonNode u : reference.path("units")) {
        if (u.has("command")) {
          commanded.add(u.get("name").asText());
        }
      }
      for (JsonNode u : reference.path("units")) {
        if (commanded.contains(u.get("name").asText()) || u.has("action")) {
          continue;
        }
        placed.add(
            match.deploy(
                u.get("tick").asInt(),
                GameData.unit(u.get("card").asText()),
                u.path("level").asInt(reference.get("level").asInt()),
                u.get("side").asInt(),
                u.get("deploy").get(0).asInt(),
                u.get("deploy").get(1).asInt(),
                u.get("name").asText()));
      }
    }
    for (CharacterEntity unit : placed) {
      unit.actionHolder().setListener(listener(unit.name(), currentTick, actions, dropping));
    }
    // A princess tower's runs are listed like a unit's: a row another entity schedules on it runs
    // in its own passes. The king's holder tells its activation steps instead.
    for (BattleEntity entity : battle.getHolder().entities()) {
      if (entity instanceof TowerEntity tower && !tower.getData().king()) {
        tower.actionHolder().setListener(listener(tower.name(), currentTick, actions, dropping));
      }
    }
    // What the champion slots did, every ability's buff, and every slot step; the deck pass runs
    // as a match is set up.
    List<String> championLog = new ArrayList<>();
    List<String> championTrace = new ArrayList<>();
    match.getWorld().addObserver(championLog(currentTick, championLog, championTrace));
    // A match is set up before the first step, its decks shuffled with the battle's source.
    LadderMatch ladder = null;
    Map<Integer, JsonNode> trace = new HashMap<>();
    List<String> circleKills = new ArrayList<>();
    List<String> drains = new ArrayList<>();
    List<String> elixirPaid = new ArrayList<>();
    List<Integer> endTicks = new ArrayList<>();
    if (reference.has("match")) {
      JsonNode m = reference.get("match");
      List<List<String>> decks = new ArrayList<>();
      for (JsonNode deck : m.get("decks")) {
        List<String> cards = new ArrayList<>();
        deck.forEach(card -> cards.add(card.asText()));
        decks.add(cards);
      }
      // A deck's evolution and hero slots: each card's slot flags, by its deck index.
      int[][] slots = {new int[decks.get(0).size()], new int[decks.get(1).size()]};
      for (JsonNode e : reference.path("evolution")) {
        if (e.get("event").asText().equals("slot")) {
          slots[e.get("side").asInt()][e.get("index").asInt()] = e.get("flags").asInt();
        }
      }
      ladder =
          match.startLadderMatch(
              decks.get(0),
              decks.get(1),
              m.get("avatar_words").get(0).asInt(),
              m.get("avatar_words").get(1).asInt(),
              slots[0],
              slots[1]);
      for (JsonNode row : m.get("trace")) {
        trace.put(row.get(0).asInt(), row);
      }
      assertOpeningHands(ladder, m);
      match.getWorld().addObserver(matchLog(currentTick, circleKills, drains, elixirPaid));
    }
    // A run with card plays plays each as a place-card command due on its tick.
    if (reference.has("commands")) {
      BattlePlacementRunTest.playAll(match, reference);
    }
    // Every request for a unit's ability the run supplies, in place of its player's command: in the
    // unit's phase-2 pass of its tick, after the run pass.
    List<String> goldenKnightLog = new ArrayList<>();
    for (JsonNode g : reference.path("golden_knight")) {
      if (!g.get("event").asText().equals("supplied_request")) {
        continue;
      }
      int tick = g.get("tick").asInt();
      String requested = g.get("unit").asText();
      battle.queue(
          new BattleCommand() {
            @Override
            public int tick() {
              return tick;
            }

            @Override
            public void execute(Battle target) {
              CharacterEntity unit = (CharacterEntity) named(target, requested);
              goldenKnightLog.add(tick + " supplied_request " + requested);
              unit.actionHolder().schedule(new SuppliedRequest(unit), ActionHolder.OWN_DELAY);
            }
          });
    }
    match.getWorld().addObserver(goldenKnightLog(currentTick, goldenKnightLog));
    List<String> skeletonKingLog = new ArrayList<>();
    match.getWorld().addObserver(skeletonKingLog(currentTick, skeletonKingLog));
    // The area effects a run places directly, each in the command pass of its tick.
    for (JsonNode a : reference.path("area_effects")) {
      if (a.get("event").asText().equals("created") && a.get("how").asText().equals("placed")) {
        match.placeAreaEffect(
            a.get("tick").asInt(),
            a.get("row").asText(),
            reference.get("level").asInt(),
            a.get("side").asInt(),
            a.get("x").asInt(),
            a.get("y").asInt(),
            a.get("area_effect").asText());
      }
    }
    for (JsonNode o : reference.path("action_owners")) {
      ActionOwnerEntity owner =
          match.addActionOwner(
              o.get("name").asText(),
              o.get("side").asInt(),
              o.get("x").asInt(),
              o.get("y").asInt(),
              o.get("level").asInt());
      owner.actionHolder().setListener(listener(owner.name(), currentTick, actions, dropping));
      for (JsonNode s : o.get("schedule")) {
        // The row is built from the game's own action rows, for the owner it runs on. A schedule
        // may name another entity as its cause, which is found by name when the schedule is made.
        BattleAction row = GameData.actions().build(s.get("action").asText(), owner.binding());
        if (s.has("instigator")) {
          String cause = s.get("instigator").asText();
          battle.queue(
              new BattleCommand() {
                @Override
                public int tick() {
                  return s.get("tick").asInt();
                }

                @Override
                public void execute(Battle target) {
                  WorldEntity instigator = named(target, cause);
                  owner
                      .actionHolder()
                      .schedule(row, ActionHolder.OWN_DELAY, false, instigator.actionHolder());
                }
              });
        } else {
          match.scheduleAction(s.get("tick").asInt(), owner, row);
        }
      }
    }

    List<String> events = new ArrayList<>();
    List<String> positions = new ArrayList<>();
    // The riders a card play attached, and each let go as its parent left.
    List<String> riders = new ArrayList<>();
    List<String> riderLog = new ArrayList<>();
    List<String> groups = new ArrayList<>();
    List<String> deaths = new ArrayList<>();
    Map<String, Integer> spawnTicks = new HashMap<>();
    match.getWorld().addObserver(BattleTowerRunTest.eventCollector(currentTick, events));
    List<String> areaEffects = new ArrayList<>();
    match.getWorld().addObserver(areaEffectLog(currentTick, areaEffects));
    // Every hit a shield took.
    List<String> shieldLog = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void shieldHit(
                  int tick, WorldEntity target, int damage, int shieldBefore, int shieldAfter) {
                shieldLog.add(
                    "%d shield %s %d %d %d %d %s"
                        .formatted(
                            currentTick[0],
                            target.name(),
                            damage,
                            shieldBefore,
                            shieldAfter,
                            target.getHitPoints().getHitPoints(),
                            shieldAfter == 0));
              }
            });
    // Every charge completed and lost, and every state change a unit's movement pass asked for.
    List<String> jumpChargeDash = new ArrayList<>();
    match.getWorld().addObserver(jumpChargeDashLog(currentTick, jumpChargeDash));
    // What each building's spawner and lifetime did.
    List<String> buildingLog = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void spawnerFired(
                  int tick,
                  CharacterEntity spawner,
                  String row,
                  int count,
                  int radius,
                  int timerAfter,
                  int waveMade) {
                buildingLog.add(
                    "%d spawner %s %s %d %d %d %d"
                        .formatted(
                            currentTick[0],
                            spawner.name(),
                            row,
                            count,
                            radius,
                            timerAfter,
                            waveMade));
              }

              @Override
              public void decayDied(int tick, WorldEntity entity, int hitPointsBefore) {
                buildingLog.add(
                    "%d decay_death %s %d"
                        .formatted(currentTick[0], entity.name(), hitPointsBefore));
              }
            });
    // A hiding building's deploy end, and every change of its hide counter that shows something.
    List<String> hidingLog = new ArrayList<>();
    match.getWorld().addObserver(hidingLog(currentTick, hidingLog));
    // Every pull of an attracting area effect's hit.
    List<String> pullLog = new ArrayList<>();
    match.getWorld().addObserver(pullLog(currentTick, pullLog));
    // Every push of a unit entering its deploying state.
    List<String> deployPushLog = new ArrayList<>();
    match.getWorld().addObserver(deployPushLog(currentTick, deployPushLog));
    // What every Clone did, and every area effect a projectile's impact made.
    List<String> cloneLog = new ArrayList<>();
    match.getWorld().addObserver(cloneLog(currentTick, cloneLog));
    // Every hit that reached a reflecting unit's reflect.
    List<String> reflectLog = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void reflected(int tick, Reflection r) {
                reflectLog.add(reflectLine(currentTick[0], r));
              }
            });
    // Every special load armed, every state a dragging projectile set, and the hold and the pull
    // its leaving ended.
    List<String> hookLog = new ArrayList<>();
    match.getWorld().addObserver(hookLog(currentTick, hookLog));
    // Every death projectile, every egg made untargetable and every spawner destroyed at its limit.
    List<String> phoenixLog = new ArrayList<>();
    match.getWorld().addObserver(phoenixLog(currentTick, phoenixLog));
    // Every Kamikaze end and drain, and every lane a ring's mirror asked for.
    List<String> kamikazeLog = new ArrayList<>();
    List<String> laneLog = new ArrayList<>();
    match.getWorld().addObserver(kamikazeLog(currentTick, kamikazeLog, laneLog));
    // What every Goblin Hut's life state did, and the children it made, which its log holds.
    List<String> hutLog = new ArrayList<>();
    Set<String> hutChildren = new HashSet<>();
    match.getWorld().addObserver(goblinHutLog(match, currentTick, hutLog, hutChildren));
    // Every start and notice of a Berserker's index toggle, with the index before and after.
    List<String> berserkLog = new ArrayList<>();
    match.getWorld().addObserver(berserkLog(currentTick, berserkLog));
    // Every link and unlink of a card's group chain, and what Goblinstein's ability did.
    List<String> goblinsteinLog = new ArrayList<>();
    match.getWorld().addObserver(goblinsteinLog(currentTick, goblinsteinLog));
    // What Goblinstein's tether did, and every card play a listener heard.
    List<String> tetherLog = new ArrayList<>();
    match.getWorld().addObserver(tetherLog(currentTick, tetherLog));
    // Every buff a parent handed to its riders.
    List<String> handOverLog = new ArrayList<>();
    match.getWorld().addObserver(handOverLog(currentTick, handOverLog));
    List<String> guardLog = new ArrayList<>();
    match.getWorld().addObserver(guardLog(currentTick, guardLog));
    // Every start and step of a Boss Bandit ability's run, and every warp.
    List<String> warpLog = new ArrayList<>();
    match.getWorld().addObserver(warpLog(currentTick, warpLog));
    // Every hit a unit whose row passes over buffed targets landed, and the reference it dropped.
    List<String> dropLog = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void referenceDroppedOnHit(
                  int tick,
                  CharacterEntity unit,
                  WorldEntity hit,
                  ProjectileEntity via,
                  boolean kills,
                  boolean active,
                  TargetView dropped) {
                dropLog.add(
                    "%d drop %s hit %s via %s kills %b active %b dropped %s state %d"
                        .formatted(
                            currentTick[0],
                            unit.name(),
                            hit.name(),
                            via == null ? null : via.name(),
                            kills,
                            active,
                            dropped == null ? null : dropped.name(),
                            unit.getView().getState()));
              }
            });
    // Every tick a unit holds its own lock as the post-hooks end, which a Boss Bandit ability's run
    // asks for as it starts and releases as it finishes.
    List<String> selfLocks = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void afterPostHooks(
                  int tick, List<WorldEntity> present, List<ProjectileEntity> projectiles) {
                for (WorldEntity entity : present) {
                  if (match.getWorld().locks().claim(entity.getId(), entity.getId(), 0)) {
                    selfLocks.add(currentTick[0] + " " + entity.name());
                  }
                }
              }
            });
    // Every ask of an area effect's buff test of a clone, with the path that asked.
    List<String> cloneGateLog = new ArrayList<>();
    match.getWorld().addObserver(cloneGateLog(currentTick, cloneGateLog));
    List<String> princeLog = new ArrayList<>();
    match.getWorld().addObserver(princeLog(currentTick, princeLog));
    // Every killer's killed-done check scheduled, and every check of what caused an action.
    List<String> killLog = new ArrayList<>();
    match.getWorld().addObserver(killLog(currentTick, killLog));
    List<String> areaEffectSpawnLog = new ArrayList<>();
    match.getWorld().addObserver(areaEffectSpawnLog(currentTick, areaEffectSpawnLog));
    // Every laser ball's start and fire, and every area effect's life-end action scheduled.
    List<String> laserLog = new ArrayList<>();
    match.getWorld().addObserver(laserLog(currentTick, laserLog));
    // A played unit's runs are listed from its play, before its start.
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void characterPlayed(int tick, CharacterEntity unit) {
                unit.actionHolder()
                    .setListener(listener(unit.name(), currentTick, actions, dropping));
              }
            });
    // Every shape selector's start, step and removal, and every air-to-ground run's start, phase
    // change, finish and re-trigger.
    List<String> vinesLog = new ArrayList<>();
    match.getWorld().addObserver(vinesLog(match, currentTick, vinesLog));
    // Every target indicator attack's start, find, signal, shot, signal ended, step and stop.
    List<String> indicatorLog = new ArrayList<>();
    match.getWorld().addObserver(indicatorLog(match, currentTick, indicatorLog));
    // An area effect's own runs are listed like any owner's, from its creation; the removal of a
    // shape selector's run joins the selectors' log.
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void areaEffectCreated(
                  int tick, AreaEffectEntity a, String how, String source) {
                ActionHolder.Listener runs = listener(a.name(), currentTick, actions, dropping);
                a.actionHolder()
                    .setListener(
                        new ActionHolder.Listener() {
                          @Override
                          public void starting(BattleAction action, int phase, boolean queued) {
                            runs.starting(action, phase, queued);
                          }

                          @Override
                          public void dropped(BattleAction action, int ticksLeft) {
                            runs.dropped(action, ticksLeft);
                          }

                          @Override
                          public void removed(ActionInstance instance) {
                            if (instance.getAction() instanceof GhostEvo.Summon) {
                              buffAfterHitsLog.add(
                                  "%d summon_removed %s %s"
                                      .formatted(
                                          currentTick[0], a.name(), instance.getAction().name()));
                            }
                            if (instance.getAction() instanceof ShapeSelector) {
                              vinesLog.add(
                                  "%d selector_removed %s %s"
                                      .formatted(
                                          currentTick[0], a.name(), instance.getAction().name()));
                            }
                          }
                        });
              }
            });
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void characterSpawned(
                  int tick, SpawnHost owner, CharacterEntity child, int createdX, int createdY) {
                // A child's runs are listed from its spawn, the action it runs on being spawned
                // included.
                child
                    .actionHolder()
                    .setListener(listener(child.name(), currentTick, actions, dropping));
                spawnTicks.put(child.name(), currentTick[0]);
                spawns.add(
                    "%d spawn %s %s %d at %d %d state %d deploy %d lane %d hp %d then %d %d %d"
                        .formatted(
                            currentTick[0],
                            owner.name(),
                            child.name(),
                            child.getId(),
                            createdX,
                            createdY,
                            child.getView().getState(),
                            child.getView().getDeployCountdown(),
                            child.getView().getLane(),
                            child.getHitPoints() == null ? 0 : child.getHitPoints().getHitPoints(),
                            child.getView().getX(),
                            child.getView().getY(),
                            child.getView().getState()));
              }

              @Override
              public void ghostSummonMade(int tick, AreaEffectEntity area, CharacterEntity summon) {
                // A summon is listed from its making, as a spawned child is.
                summon
                    .actionHolder()
                    .setListener(listener(summon.name(), currentTick, actions, dropping));
                spawnTicks.put(summon.name(), currentTick[0]);
              }

              @Override
              public void guardRegistered(int tick, CharacterEntity guard) {
                // A guard is listed from its making, as a spawned child is.
                guard
                    .actionHolder()
                    .setListener(listener(guard.name(), currentTick, actions, dropping));
                spawnTicks.put(guard.name(), currentTick[0]);
              }

              @Override
              public void cloned(
                  int tick,
                  CharacterEntity original,
                  CharacterEntity clone,
                  SpawnHost instigator,
                  List<Integer> registrationVisits) {
                // A clone is listed from its making, as a spawned child is.
                clone
                    .actionHolder()
                    .setListener(listener(clone.name(), currentTick, actions, dropping));
                spawnTicks.put(clone.name(), currentTick[0]);
              }

              @Override
              public void riderAttached(
                  int tick, CharacterEntity parent, CharacterEntity rider, int index, int angle) {
                // A rider is made in the command pass, ahead of the opening cleanup that folds it,
                // so no record finds it still pending.
                spawnTicks.remove(rider.name());
                riders.add(rider.name());
                riderLog.add(
                    "%d attached %s %d %s %d %d at %d %d state %d deploy %d dir %d %d"
                        .formatted(
                            currentTick[0],
                            rider.name(),
                            rider.getId(),
                            parent.name(),
                            index,
                            angle,
                            rider.getView().getX(),
                            rider.getView().getY(),
                            rider.getView().getState(),
                            rider.getView().getDeployCountdown(),
                            rider.getView().getDirX(),
                            rider.getView().getDirY()));
              }

              @Override
              public void parentLeft(int tick, CharacterEntity rider, CharacterEntity parent) {
                riderLog.add(
                    "%d parent_left %s %s at %d %d state %d"
                        .formatted(
                            currentTick[0],
                            rider.name(),
                            parent.name(),
                            rider.getView().getX(),
                            rider.getView().getY(),
                            rider.getView().getState()));
              }

              @Override
              public void groupLinked(int tick, CharacterEntity source, CharacterEntity child) {
                groups.add(groupLine(currentTick[0], "group_link", source, child));
              }

              @Override
              public void groupUnlinked(int tick, CharacterEntity source, CharacterEntity child) {
                groups.add(groupLine(currentTick[0], "group_unlink", source, child));
              }

              @Override
              public void deathHooksScheduled(
                  int tick,
                  WorldEntity dying,
                  BattleEntity attacker,
                  int side,
                  List<String> hooks,
                  boolean inPendingPass) {
                deaths.add(
                    "%d death_hooks %s %s %d %s %s"
                        .formatted(
                            currentTick[0],
                            dying.name(),
                            attackerName(attacker),
                            side,
                            hooks,
                            inPendingPass));
              }

              @Override
              public void championHandedOver(int tick, SpawnHost source, CharacterEntity child) {
                deaths.add(
                    "%d champion_handover %s %s"
                        .formatted(currentTick[0], source.name(), child.name()));
              }

              @Override
              public void afterPostHooks(
                  int tick, List<WorldEntity> present, List<ProjectileEntity> inFlight) {
                for (ProjectileEntity p : inFlight) {
                  if (!p.isReleased()) {
                    positions.add(
                        "[%d,%d,%d,%d,%d]"
                            .formatted(currentTick[0], p.getId(), p.getX(), p.getY(), p.getZ()));
                  }
                }
              }
            });

    Map<Integer, List<JsonNode>> records = BattleTowerRunTest.otherRecordsByTick(reference);
    // The run's own unit's records, when it has one, are held to its outside like the others, each
    // read at the end of its tick, the deploy-end tick's too.
    if (!reference.get("card").isNull()) {
      List<JsonNode> own = BattleMusketeerRunTest.records(reference);
      for (int i = 0; i < own.size(); i++) {
        ObjectNode named = own.get(i).deepCopy();
        named.put("name", placed.get(0).name());
        records.computeIfAbsent(named.get("tick").asInt(), t -> new ArrayList<>()).add(named);
      }
    }
    int lastTick = records.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
    for (JsonNode event : reference.get("events")) {
      lastTick = Math.max(lastTick, event.get("tick").asInt());
    }
    // A shot still in flight when the run ends is recorded past the last record and event, and an
    // area effect, a buff or a shield may be logged past them too.
    for (JsonNode p : reference.path("projectiles")) {
      lastTick = Math.max(lastTick, p.get(0).asInt());
    }
    for (String log : List.of("area_effects", "buffs", "shields")) {
      for (JsonNode entry : reference.path(log)) {
        lastTick = Math.max(lastTick, entry.get("tick").asInt());
      }
    }
    // A match runs to the end of its trace.
    for (int traced : trace.keySet()) {
      lastTick = Math.max(lastTick, traced);
    }
    Map<String, CharacterEntity> units = new HashMap<>();
    Map<String, String> towerStates = new HashMap<>();
    List<String> locks = new ArrayList<>();
    // The reference logs a tower's lock only while the tower holds none: a lock holds until a visit
    // leaves the tower with no reference at all, so a tower that falls back to its default seed and
    // takes its target again logs no second lock.
    Set<String> locked = new HashSet<>();
    // A morph's new building takes its target in its registration visit, before it is set
    // deploying; the reference logs that as its lock, on the tick it is made.
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void morphed(int tick, CharacterEntity old, CharacterEntity made) {
                if (referenceName(made) != null) {
                  locks.add(tick + " " + made.name() + " " + referenceName(made));
                }
              }

              // A tower that takes a new target in its visit and dies later in the same step has
              // it dropped by its combat gate before the step ends; the reference logs the lock
              // from the visit all the same.
              @Override
              public void combatGateDropped(
                  int tick, WorldEntity entity, TargetView reference, int hitSpeed) {
                if (!(entity instanceof TowerEntity)
                    || entity.getView().getState() != GridEntityState.ATTACKING) {
                  return;
                }
                String before = towerStates.get(entity.name());
                String beforeRef =
                    before == null ? null : before.substring(before.indexOf(' ') + 1);
                if (!reference.name().equals(beforeRef) && locked.add(entity.name())) {
                  locks.add(tick + " " + entity.name() + " " + reference.name());
                }
              }
            });
    // The reference logs a tower's or a building's reference whenever a targeting visit or a
    // removal changes it, and, for a removal, the retarget countdown it left. A combat gate's drop
    // (a freeze) is logged with the events instead, and a tower that takes the same target again
    // after it logs no change, so a tower's reference is unknown from such a drop until its next
    // change; a tower that died is not followed further.
    Map<Integer, List<JsonNode>> referenceChanges = new HashMap<>();
    for (JsonNode event : reference.path("tower_events")) {
      if (event.get("event").asText().equals("reference")) {
        referenceChanges
            .computeIfAbsent(event.get("tick").asInt(), t -> new ArrayList<>())
            .add(event);
      }
    }
    Map<Integer, List<String>> gateDrops = new HashMap<>();
    Map<Integer, List<String>> towerDeaths = new HashMap<>();
    for (JsonNode event : reference.get("events")) {
      String kind = event.get("event").asText();
      if (kind.equals("combat_gate_drop")) {
        gateDrops
            .computeIfAbsent(event.get("tick").asInt(), t -> new ArrayList<>())
            .add(event.get("unit").asText());
      } else if (kind.equals("death")) {
        towerDeaths
            .computeIfAbsent(event.get("tick").asInt(), t -> new ArrayList<>())
            .add(event.get("target").asText());
      }
    }
    Map<String, String> heldReferences = new HashMap<>();
    Set<String> unknownReferences = new HashSet<>();
    // The characters seen walking, which a swap may turn into buildings.
    Set<String> walkers = new HashSet<>();
    for (int tick = 0; tick <= lastTick; tick++) {
      currentTick[0] = tick;
      battle.step();
      if (stateAfter.containsKey(tick)) {
        assertThat(Integer.toUnsignedLong(match.getWorld().getRandom().getState()))
            .as("tick %d: the random state after its draws", tick)
            .isEqualTo(stateAfter.get(tick));
      }
      for (BattleEntity entity : battle.getHolder().entities()) {
        if (entity instanceof CharacterEntity c) {
          units.putIfAbsent(c.name(), c);
          if (!c.getData().building()) {
            walkers.add(c.name());
          }
        }
        // A building locks on as a tower does.
        if (entity instanceof TowerEntity
            || entity instanceof CharacterEntity c && c.getData().building()) {
          WorldEntity tower = (WorldEntity) entity;
          // A lock is the tower attacking a target it did not hold at the end of the last step:
          // entering the attacking state with a new target, or, still in it after its target left,
          // taking the next one. Entering it again with the target it kept is none.
          String held = tower.getView().getState() + " " + referenceName(tower);
          String before = towerStates.put(tower.name(), held);
          // The reference logs a building's lock from its visits as a building: the step a
          // walking unit becomes one, as the Moving Cannon breaks down while it attacks, lists
          // nothing, and its next visit asks for the attacking state again and is its lock.
          if (before == null && walkers.contains(tower.name())) {
            towerStates.put(tower.name(), "first seen");
            continue;
          }
          String beforeRef = before == null ? null : before.substring(before.indexOf(' ') + 1);
          if (tower.getView().getState() == GridEntityState.ATTACKING
              && referenceName(tower) != null
              && !referenceName(tower).equals(beforeRef)
              && locked.add(tower.name())) {
            locks.add(tick + " " + tower.name() + " " + referenceName(tower));
          }
          if (referenceName(tower) == null) {
            locked.remove(tower.name());
          }
        }
      }
      for (JsonNode change : referenceChanges.getOrDefault(tick, List.of())) {
        JsonNode target = change.get("target");
        heldReferences.put(change.get("tower").asText(), target.isNull() ? null : target.asText());
        unknownReferences.remove(change.get("tower").asText());
      }
      unknownReferences.addAll(gateDrops.getOrDefault(tick, List.of()));
      towerDeaths.getOrDefault(tick, List.of()).forEach(heldReferences::remove);
      for (BattleEntity entity : battle.getHolder().entities()) {
        if (entity instanceof TowerEntity tower
            && heldReferences.containsKey(tower.name())
            && !unknownReferences.contains(tower.name())) {
          assertThat(referenceName(tower))
              .as("tick %d: %s's reference", tick, tower.name())
              .isEqualTo(heldReferences.get(tower.name()));
          JsonNode change =
              referenceChanges.getOrDefault(tick, List.of()).stream()
                  .filter(c -> c.get("tower").asText().equals(tower.name()))
                  .reduce((first, second) -> second)
                  .orElse(null);
          if (change != null && change.has("target_lost_timer")) {
            assertThat(tower.getTargeting().getTargetLostTimerMs())
                .as("tick %d: %s's retarget countdown after the removal", tick, tower.name())
                .isEqualTo(change.get("target_lost_timer").asInt());
          }
        }
      }
      for (JsonNode record : records.getOrDefault(tick, List.of())) {
        CharacterEntity unit = units.get(record.get("name").asText());
        assertThat(unit).as("tick %d: %s is in the battle", tick, record.get("name")).isNotNull();
        assertRecord(battle, unit, record, tick, spawnTicks);
      }
      if (ladder != null && ladder.isEnded() && endTicks.isEmpty()) {
        endTicks.add(tick);
      }
      if (trace.containsKey(tick)) {
        assertThat(traceRow(tick, ladder))
            .as("tick %d: the match", tick)
            .isEqualTo(trace.get(tick).toString());
      }
    }
    if (ladder != null) {
      assertPlays(match, reference);
      assertAbilityUses(match, reference);
      assertEnd(match, ladder, reference.get("match"));
      assertMirror(match, ladder, reference);
      assertVariant(match, reference);
      assertEvolution(match, ladder, reference);
      List<String> expectedKills = new ArrayList<>();
      List<String> expectedDrains = new ArrayList<>();
      List<String> expectedElixir = new ArrayList<>();
      List<Integer> expectedEnd = new ArrayList<>();
      for (JsonNode entry : reference.get("match").get("log")) {
        String kind = entry.get("event").asText();
        if (kind.equals("circle_kill")) {
          expectedKills.add(
              "%d %s %d"
                  .formatted(
                      entry.get("tick").asInt(),
                      entry.get("target").asText(),
                      entry.get("radius").asInt()));
        } else if (kind.equals("collector_elixir") || kind.equals("death_elixir")) {
          boolean collector = kind.equals("collector_elixir");
          expectedElixir.add(
              "%d %s %s %d %d"
                  .formatted(
                      entry.get("tick").asInt(),
                      collector ? "collector" : "death",
                      entry.get(collector ? "building" : "unit").asText(),
                      entry.get("side").asInt(),
                      entry.get("amount").asInt()));
        } else if (kind.equals("drain")) {
          expectedDrains.add(
              "%d %s %d %d"
                  .formatted(
                      entry.get("tick").asInt(),
                      entry.get("target").asText(),
                      entry.get("damage").asInt(),
                      entry.get("hp").asInt()));
        } else if (kind.equals("end")) {
          expectedEnd.add(entry.get("tick").asInt());
          assertThat(List.of(ladder.crowns(0), ladder.crowns(1)))
              .as("the crowns at the end")
              .containsExactly(
                  entry.get("crowns").get(0).asInt(), entry.get("crowns").get(1).asInt());
        }
      }
      assertThat(circleKills).as("every kill of a fallen king's circle").isEqualTo(expectedKills);
      assertThat(drains).as("every step of the tiebreaker's drain").isEqualTo(expectedDrains);
      assertThat(elixirPaid)
          .as("every elixir a collector or a death paid a king")
          .isEqualTo(expectedElixir);
      assertThat(endTicks).as("the tick the match ended on").isEqualTo(expectedEnd);
    }

    List<String> expectedActions = new ArrayList<>();
    List<String> expectedSpawns = new ArrayList<>();
    for (JsonNode a : reference.path("actions")) {
      String kind = a.get("event").asText();
      if (kind.equals("run")) {
        expectedActions.add(
            "%d run %s %s %s"
                .formatted(
                    a.get("tick").asInt(),
                    a.get("owner").asText(),
                    a.get("action").asText(),
                    a.get("phase").isNull() ? "at once" : a.get("phase").asText()));
      } else if (kind.equals("spawn")) {
        JsonNode after = a.get("after_registration");
        expectedSpawns.add(
            "%d spawn %s %s %d at %d %d state %d deploy %d lane %d hp %d then %d %d %d"
                .formatted(
                    a.get("tick").asInt(),
                    a.get("owner").asText(),
                    a.get("unit").asText(),
                    a.get("id").asInt(),
                    a.get("created").get(0).asInt(),
                    a.get("created").get(1).asInt(),
                    a.get("state").asInt(),
                    a.get("deploy").asInt(),
                    a.get("lane").asInt(),
                    a.get("hp").asInt(),
                    after.get(0).asInt(),
                    after.get(1).asInt(),
                    after.get(2).asInt()));
      }
    }
    if (reference.has("actions")
        || !(reference.has("buff_after_hits") || reference.has("shield_lost"))) {
      assertThat(actions).as("every run of an action").containsExactlyElementsOf(expectedActions);
    } else if (reference.has("buff_after_hits")) {
      // A run that lists no runs of its own still lists every buff hook it scheduled: each run is
      // one of those, on its unit and tick.
      List<String> hookRuns = new ArrayList<>();
      for (JsonNode e : reference.get("buff_after_hits")) {
        if (e.get("event").asText().equals("buff_hook")) {
          hookRuns.add(
              "%d run %s %s"
                  .formatted(
                      e.get("tick").asInt(), e.get("unit").asText(), e.get("action").asText()));
        }
      }
      assertThat(actions.stream().map(line -> line.substring(0, line.lastIndexOf(' '))).toList())
          .as("every run of an action, each a buff hook the reference scheduled")
          .containsExactlyElementsOf(hookRuns);
    } else {
      // A run that lists no runs of its own still lists every run of a broken shield's action,
      // with its pending pass.
      List<String> shieldRuns = new ArrayList<>();
      for (JsonNode e : reference.get("shield_lost")) {
        if (e.get("event").asText().equals("run")) {
          shieldRuns.add(
              "%d run %s %s %d"
                  .formatted(
                      e.get("tick").asInt(),
                      e.get("unit").asText(),
                      e.get("action").asText(),
                      e.get("phase").asInt()));
        }
      }
      assertThat(actions)
          .as("every run of an action, each a broken shield's the reference ran")
          .containsExactlyElementsOf(shieldRuns);
    }
    List<String> expectedDrops = new ArrayList<>();
    for (JsonNode a : reference.path("actions")) {
      if (a.get("event").asText().equals("dropped")) {
        expectedDrops.add(
            "%d dropped %s %s %d"
                .formatted(
                    a.get("tick").asInt(),
                    a.get("owner").asText(),
                    a.get("action").asText(),
                    a.get("ticks_left").asInt()));
      }
    }
    assertThat(dropping)
        .as("every pending action dropped as its instigator left")
        .containsExactlyElementsOf(expectedDrops);
    if (reference.has("actions")) {
      // A Goblin Hut's children are held by its own log.
      assertThat(spawns.stream().filter(line -> !hutChildren.contains(line.split(" ")[3])))
          .as("every spawn")
          .containsExactlyElementsOf(expectedSpawns);
    } else {
      // A run that lists no actions lists its riders under unit_spawner, the children of a
      // buff's death spawn in its buff log and a Phoenix's egg, made by its death projectile's
      // impact, in its Phoenix log: they are its only spawns. The egg is made where and when the
      // log says.
      Set<String> buffChildren = new HashSet<>();
      for (JsonNode b : reference.path("buffs")) {
        if (b.get("event").asText().equals("death_spawn")) {
          b.get("units").forEach(u -> buffChildren.add(u.get(0).asText()));
        }
      }
      List<String> eggs = new ArrayList<>();
      for (JsonNode p : reference.path("phoenix")) {
        if (p.get("event").asText().equals("untargetable_when_spawned")) {
          eggs.add(
              "%d %s %d %d"
                  .formatted(
                      p.get("tick").asInt(),
                      p.get("unit").asText(),
                      p.get("x").asInt(),
                      p.get("y").asInt()));
        }
      }
      List<String> eggSpawns = new ArrayList<>();
      for (String line : spawns) {
        String[] f = line.split(" ");
        if (eggs.stream().anyMatch(egg -> egg.split(" ")[1].equals(f[3]))) {
          eggSpawns.add(f[0] + " " + f[3] + " " + f[6] + " " + f[7]);
        }
      }
      assertThat(eggSpawns).as("every egg the Phoenix log lists").containsExactlyElementsOf(eggs);
      Set<String> eggNames = new HashSet<>();
      eggs.forEach(egg -> eggNames.add(egg.split(" ")[1]));
      // An area effect's spawns are held by the Skeleton King's log.
      Set<String> areaChildren = new HashSet<>();
      for (JsonNode k : reference.path("skeleton_king_ability")) {
        if (k.get("event").asText().equals("skeleton")) {
          areaChildren.add(k.get("unit").asText());
        }
      }
      List<String> riderSpawns =
          spawns.stream()
              .filter(line -> !buffChildren.contains(line.split(" ")[3]))
              .filter(line -> !eggNames.contains(line.split(" ")[3]))
              .filter(line -> !areaChildren.contains(line.split(" ")[3]))
              .toList();
      assertThat(riderSpawns)
          .as("every spawn but a buff's death spawn, a rider each")
          .allSatisfy(line -> assertThat(riders).contains(line.split(" ")[3]))
          .hasSameSizeAs(riders);
    }
    List<String> expectedRiders = new ArrayList<>();
    for (JsonNode e : reference.path("unit_spawner")) {
      String kind = e.get("event").asText();
      if (kind.equals("attached")) {
        expectedRiders.add(
            "%d attached %s %d %s %d %d at %d %d state %d deploy %d dir %d %d"
                .formatted(
                    e.get("tick").asInt(),
                    e.get("unit").asText(),
                    e.get("id").asInt(),
                    e.get("parent").asText(),
                    e.get("index").asInt(),
                    e.get("angle").asInt(),
                    e.get("x").asInt(),
                    e.get("y").asInt(),
                    e.get("state").asInt(),
                    e.get("deploy").asInt(),
                    e.get("dir").get(0).asInt(),
                    e.get("dir").get(1).asInt()));
      } else if (kind.equals("parent_left")) {
        expectedRiders.add(
            "%d parent_left %s %s at %d %d state %d"
                .formatted(
                    e.get("tick").asInt(),
                    e.get("unit").asText(),
                    e.get("parent").asText(),
                    e.get("x").asInt(),
                    e.get("y").asInt(),
                    e.get("state").asInt()));
      }
    }
    if (reference.has("unit_spawner")) {
      assertThat(riderLog)
          .as("every rider attached and let go")
          .containsExactlyElementsOf(expectedRiders);
    }
    List<String> expectedGroups = new ArrayList<>();
    for (JsonNode a : reference.path("actions")) {
      String kind = a.get("event").asText();
      if (kind.equals("group_link") || kind.equals("group_unlink")) {
        List<String> chain = new ArrayList<>();
        a.get("chain").forEach(linked -> chain.add(linked.asText()));
        expectedGroups.add(
            "%d %s %s %s %s"
                .formatted(
                    a.get("tick").asInt(),
                    kind,
                    a.get("owner").asText(),
                    a.get("unit").asText(),
                    chain));
      }
    }
    assertThat(groups)
        .as("every child linked into its source's group and unlinked as it leaves")
        .containsExactlyElementsOf(expectedGroups);
    List<String> expectedDeaths = new ArrayList<>();
    for (JsonNode a : reference.path("actions")) {
      String kind = a.get("event").asText();
      if (kind.equals("death_hooks")) {
        List<String> hooks = new ArrayList<>();
        a.get("actions").forEach(hook -> hooks.add(hook.asText()));
        expectedDeaths.add(
            "%d death_hooks %s %s %d %s %s"
                .formatted(
                    a.get("tick").asInt(),
                    a.get("owner").asText(),
                    a.get("attacker").asText(),
                    a.get("side").asInt(),
                    hooks,
                    a.get("in_pending").asBoolean()));
      } else if (kind.equals("champion_handover")) {
        expectedDeaths.add(
            "%d champion_handover %s %s"
                .formatted(a.get("tick").asInt(), a.get("owner").asText(), a.get("unit").asText()));
      }
    }
    assertThat(deaths)
        .as("every death's hooks as they are scheduled, and every champion handed over")
        .containsExactlyElementsOf(expectedDeaths);

    List<String> expectedLocks = new ArrayList<>();
    for (JsonNode event : reference.path("tower_events")) {
      // A lock names the target the tower attacks. The reference also logs the attacking state
      // requested again while an attack runs on with no reference, as a lock of nothing; that is
      // no lock.
      if (event.get("event").asText().equals("lock") && !event.get("target").isNull()) {
        expectedLocks.add(
            event.get("tick").asInt()
                + " "
                + event.get("tower").asText()
                + " "
                + event.get("target").asText());
      }
    }
    assertThat(locks).as("every tower's lock").containsExactlyElementsOf(expectedLocks);

    assertThat(areaEffects)
        .as("what every area effect did")
        .containsExactlyElementsOf(expectedAreaEffects(reference));
    assertThat(jumpChargeDash)
        .as("every charge, its loss, and every state a movement pass asked for")
        .containsExactlyElementsOf(expectedJumpChargeDash(reference));

    List<String> expectedBuildingLog = new ArrayList<>();
    for (JsonNode b : reference.path("building_log")) {
      String kind = b.get("event").asText();
      if (kind.equals("spawner")) {
        expectedBuildingLog.add(
            "%d spawner %s %s %d %d %d %d"
                .formatted(
                    b.get("tick").asInt(),
                    b.get("building").asText(),
                    b.get("row").asText(),
                    b.get("count").asInt(),
                    b.get("radius").asInt(),
                    b.get("timer_after").asInt(),
                    b.get("burst").asInt()));
      } else if (kind.equals("decay_death")) {
        expectedBuildingLog.add(
            "%d decay_death %s %d"
                .formatted(
                    b.get("tick").asInt(), b.get("entity").asText(), b.get("hp_before").asInt()));
      }
    }
    assertThat(buildingLog)
        .as("every spawner firing and every death by a lifetime's decay")
        .containsExactlyElementsOf(expectedBuildingLog);

    assertThat(hidingLog)
        .as("every deploy end's targeting visit and every hide counter change that shows something")
        .containsExactlyElementsOf(expectedHidingLog(reference));

    assertThat(pullLog)
        .as("every pull, its targets and their push accumulators")
        .containsExactlyElementsOf(expectedPullLog(reference));

    assertThat(deployPushLog)
        .as("every push of a unit entering its deploying state, what it found and whom it pushed")
        .containsExactlyElementsOf(expectedDeployPushLog(reference));

    assertThat(cloneLog)
        .as("every clone scheduled, made and moved apart, and every area effect an impact made")
        .containsExactlyElementsOf(expectedCloneLog(reference));
    List<String> expectedReflects = new ArrayList<>();
    for (JsonNode r : reference.path("reflects")) {
      String line =
          "%d reflect %s by %s kind %s source %s struck %s speed %d"
              .formatted(
                  r.get("tick").asInt(),
                  r.get("target").asText(),
                  r.get("attacker").isNull() ? null : r.get("attacker").asText(),
                  r.get("kind").isNull() ? null : r.get("kind").asText(),
                  r.get("source").isNull() ? null : r.get("source").asText(),
                  r.get("struck").isNull() ? null : r.get("struck").asText(),
                  r.get("hit_speed").asInt());
      if (r.has("buff")) {
        line +=
            " buff %s %d %d"
                .formatted(r.get("buff").asText(), r.get("time").asInt(), r.get("level").asInt());
      }
      if (r.has("damage")) {
        line +=
            " damage %d %d %d"
                .formatted(
                    r.get("damage").asInt(),
                    r.get("hp").get(0).asInt(),
                    r.get("hp").get(1).asInt());
      }
      expectedReflects.add(line);
    }
    assertThat(reflectLog)
        .as("every hit that reached a reflect, and what it struck back with")
        .containsExactlyElementsOf(expectedReflects);

    assertThat(hookLog)
        .as("every special load armed, every state a drag set, and every hold and pull let go")
        .containsExactlyElementsOf(expectedHookLog(reference));

    assertThat(phoenixLog)
        .as("every death projectile, egg made untargetable and spawner destroyed at its limit")
        .containsExactlyElementsOf(expectedPhoenixLog(reference));

    // The runs with a drain list their Kamikaze ends and drains; a ring's lanes are listed with the
    // actions.
    if (reference.has("kamikaze")) {
      assertThat(kamikazeLog)
          .as("every Kamikaze end and every drain")
          .containsExactlyElementsOf(expectedKamikazeLog(reference));
    }
    assertThat(laneLog)
        .as("every lane a const-priority ring asked for")
        .containsExactlyElementsOf(expectedLaneLog(reference));
    assertThat(hutLog)
        .as("every start and step of a Goblin Hut's life state, its finds, points and children")
        .containsExactlyElementsOf(expectedGoblinHutLog(reference));
    assertThat(areaEffectSpawnLog)
        .as("every area effect an action spawned, and every hit action one scheduled")
        .containsExactlyElementsOf(expectedAreaEffectSpawnLog(reference));
    assertThat(berserkLog)
        .as("every start and notice of a Berserker's index toggle")
        .containsExactlyElementsOf(expectedBerserkLog(reference));
    assertThat(goblinsteinLog)
        .as("every group chain link and unlink, and what Goblinstein's ability did")
        .containsExactlyElementsOf(expectedGoblinsteinLog(reference));
    // The reference lists its tether log only for a run in which a tether ran or a heard card
    // play acted: a run without one may hear plays that did nothing.
    List<String> tetherActed =
        reference.has("tether")
            ? tetherLog
            : tetherLog.stream().filter(l -> !l.endsWith(" -")).toList();
    assertThat(
            tetherActed.stream()
                .map(l -> l.endsWith(" -") ? l.substring(0, l.length() - 2) : l)
                .toList())
        .as(
            "every activation row, damage pass, hit and hit action of a tether, and every play heard")
        .containsExactlyElementsOf(expectedTetherLog(reference));
    List<String> expectedHandOvers = new ArrayList<>();
    for (JsonNode h : reference.path("parent_buff")) {
      List<String> instances = new ArrayList<>();
      for (JsonNode i : h.get("instances")) {
        instances.add(
            "%s %d %d %b"
                .formatted(
                    i.get(0).asText(), i.get(1).asInt(), i.get(2).asInt(), i.get(3).asBoolean()));
      }
      expectedHandOvers.add(
          "%d handed_over %s %s %s %d %d %s %s"
              .formatted(
                  h.get("tick").asInt(),
                  h.get("parent").asText(),
                  h.get("rider").asText(),
                  h.get("buff").asText(),
                  h.get("time").asInt(),
                  h.get("level").asInt(),
                  h.get("source").isNull() ? null : h.get("source").asText(),
                  instances));
    }
    assertThat(handOverLog)
        .as("every buff a parent handed to its riders, and the rider's instances of it")
        .containsExactlyElementsOf(expectedHandOvers);
    assertThat(guardLog)
        .as("every guard made, and every step of a guard spawn's runs")
        .containsExactlyElementsOf(expectedGuardLog(reference));
    assertThat(warpLog)
        .as("every start and step of a Boss Bandit ability's run, and every warp")
        .containsExactlyElementsOf(expectedWarpLog(reference));
    List<String> expectedReferenceDrops = new ArrayList<>();
    for (JsonNode d : reference.path("reference_drops")) {
      expectedReferenceDrops.add(
          "%d drop %s hit %s via %s kills %b active %b dropped %s state %d"
              .formatted(
                  d.get("tick").asInt(),
                  d.get("unit").asText(),
                  d.get("hit").asText(),
                  d.get("via").isNull() ? null : d.get("via").asText(),
                  d.get("flag").asInt() == 1,
                  d.get("active").asInt() == 1,
                  d.get("dropped").isNull() ? null : d.get("dropped").asText(),
                  d.get("state").asInt()));
    }
    assertThat(dropLog)
        .as("every hit a unit that passes over buffed targets landed, and what it dropped")
        .containsExactlyElementsOf(expectedReferenceDrops);
    assertThat(selfLocks)
        .as("every tick a unit holds its own lock as the post-hooks end")
        .containsExactlyElementsOf(expectedSelfLocks(reference));
    assertThat(goldenKnightLog)
        .as("every supplied request, ability dash, stun cleanse, chained dash and chain's end")
        .containsExactlyElementsOf(expectedGoldenKnightLog(reference));
    assertThat(skeletonKingLog)
        .as("every soul counted, the souls spent, the spawner's order and every skeleton")
        .containsExactlyElementsOf(expectedSkeletonKingLog(reference));
    assertThat(championLog)
        .as("what the champion slots did, and every ability's buff")
        .containsExactlyElementsOf(expectedChampionLog(reference));
    List<String> expectedTrace = new ArrayList<>();
    reference.path("champion").path("trace").forEach(row -> expectedTrace.add(row.toString()));
    assertThat(championTrace)
        .as("every champion slot step")
        .containsExactlyElementsOf(expectedTrace);
    assertThat(cloneGateLog)
        .as("every ask of an area effect's buff test of a clone")
        .containsExactlyElementsOf(expectedCloneGateLog(reference));
    assertThat(princeLog)
        .as("every read of attack_count and every ask of a buff's life condition")
        .containsExactlyElementsOf(expectedPrinceLog(reference));
    assertThat(killLog)
        .as("every killed-done check scheduled and every check of an action's cause")
        .containsExactlyElementsOf(expectedKillLog(reference));
    assertThat(buffAfterHitsLog)
        .as("every count that applied a BuffAfterHits buff and every buff's start or remove action")
        .containsExactlyElementsOf(expectedBuffAfterHitsLog(reference));
    assertThat(uppercutWindLog)
        .as("every uppercut, knock and wind, and every watched tag word")
        .containsExactlyElementsOf(expectedUppercutWindLog(reference));
    assertThat(shieldLostLog)
        .as("every action a broken shield scheduled and every charge reset a listed buff made")
        .containsExactlyElementsOf(expectedShieldLostLog(reference));
    assertThat(vinesLog)
        .as("every shape selector's start, step and removal, and every air-to-ground run")
        .containsExactlyElementsOf(expectedVinesLog(reference));
    assertThat(laserLog)
        .as("every laser ball's start and fire, and every life-end action scheduled")
        .containsExactlyElementsOf(expectedLaserLog(reference));
    assertThat(indicatorLog)
        .as("every target indicator attack's start, find, signal, shot, step and stop")
        .containsExactlyElementsOf(expectedIndicatorLog(reference));

    if (reference.has("buffs")) {
      assertThat(buffLog)
          .as("every area buff, and every buff applied, refreshed, removed and dealing damage")
          .containsExactlyElementsOf(expectedBuffLog(reference));
    } else {
      // The reference writes its buff log only for a run in which a buff was applied, so a run
      // without one holds that none was: it lists no area buff that found nobody.
      assertThat(buffLog)
          .as("no buff applied: only area buffs that found nobody")
          .allMatch(line -> line.contains(" area_buff ") && line.endsWith(" []"));
    }
    List<String> expectedShields = new ArrayList<>();
    for (JsonNode s : reference.path("shields")) {
      expectedShields.add(
          "%d shield %s %d %d %d %d %s"
              .formatted(
                  s.get("tick").asInt(),
                  s.get("target").asText(),
                  s.get("damage").asInt(),
                  s.get("shield_before").asInt(),
                  s.get("shield").asInt(),
                  s.get("hp").asInt(),
                  s.get("broke").asBoolean()));
    }
    assertThat(shieldLog).as("every hit a shield took").containsExactlyElementsOf(expectedShields);

    List<String> expectedEvents = new ArrayList<>();
    for (JsonNode event : reference.get("events")) {
      expectedEvents.add(BattleTowerRunTest.eventLine(event));
    }
    assertThat(pushesAfterTheirArea(events))
        .as("every launch, impact, hit and death")
        .containsExactlyElementsOf(expectedEvents);
    List<String> expectedPositions = new ArrayList<>();
    for (JsonNode p : reference.path("projectiles")) {
      expectedPositions.add(p.toString());
    }
    assertThat(positions)
        .as("every projectile position")
        .containsExactlyElementsOf(expectedPositions);
  }

  /**
   * Holds a unit to its record, taken as the step ends: position, state, reference and its own hit
   * points, and for a spawned child its elapsed time and deploy countdown, whether it is still
   * immune, and whether it was spawned in this tick. A reference to an entity that left in the
   * step's closing cleanup is not shown by the record, which is taken before it; the reference of a
   * unit leaving in that cleanup is not compared.
   */
  private static void assertRecord(
      Battle battle,
      CharacterEntity unit,
      JsonNode record,
      int tick,
      Map<String, Integer> spawnTicks) {
    String where = "tick " + tick + ": " + unit.name();
    assertThat(unit.getView().getX()).as("%s x", where).isEqualTo(record.get("x").asInt());
    assertThat(unit.getView().getY()).as("%s y", where).isEqualTo(record.get("y").asInt());
    assertThat(unit.getView().getState())
        .as("%s state", where)
        .isEqualTo(record.get("state").asInt());
    String recorded = record.get("ref").isNull() ? null : record.get("ref").asText();
    boolean stillThere =
        battle.getHolder().entities().stream()
            .anyMatch(e -> e instanceof WorldEntity w && w.name().equals(recorded));
    if (battle.getHolder().entities().contains(unit)) {
      assertThat(BattleMusketeerRunTest.referenceName(unit))
          .as("%s reference", where)
          .isEqualTo(stillThere ? recorded : null);
    }
    // A run without the towers fighting does not record its own unit's hit points.
    if (record.hasNonNull("own_hp")) {
      assertThat(unit.getHitPoints() == null ? 0 : unit.getHitPoints().getHitPoints())
          .as("%s own hit points", where)
          .isEqualTo(record.get("own_hp").asInt());
    }
    if (record.path("delay").isNull() || record.path("delay").isMissingNode()) {
      // A unit the run places itself, not a spawned child, has only its outside recorded.
      return;
    }
    assertThat(unit.getView().getDelay())
        .as("%s elapsed time", where)
        .isEqualTo(record.get("delay").asInt());
    assertThat(unit.getView().getDeployCountdown())
        .as("%s deploy countdown", where)
        .isEqualTo(record.get("deploy").asInt());
    assertThat(unit.isSpawnImmune() ? 1 : 0)
        .as("%s immune", where)
        .isEqualTo(record.get("immune").asInt());
    assertThat(Integer.valueOf(tick).equals(spawnTicks.get(unit.name())))
        .as("%s spawned this tick", where)
        .isEqualTo(record.get("pending").asBoolean());
  }

  /**
   * Records the runs and drops of one holder's actions, named after its owner: a run as it starts,
   * with the pass that took it from the queue, or at once for one that did not wait there.
   */
  private static ActionHolder.Listener listener(
      String owner, int[] currentTick, List<String> actions, List<String> dropping) {
    return new ActionHolder.Listener() {
      @Override
      public void starting(BattleAction action, int phase, boolean queued) {
        // A looping effect row only shows something, and the reference leaves it out.
        if (action instanceof InertAction inert && inert.isLasting()) {
          return;
        }
        // The request a run supplies is the run's own, not the battle's.
        if (action instanceof SuppliedRequest) {
          return;
        }
        actions.add(
            "%d run %s %s %s"
                .formatted(currentTick[0], owner, action.name(), queued ? phase : "at once"));
      }

      // A singleton row's start that re-triggers its run is listed as a run too.
      @Override
      public void retriggering(BattleAction action, int phase, boolean queued) {
        starting(action, phase, queued);
      }

      @Override
      public void dropped(BattleAction action, int ticksLeft) {
        dropping.add(
            "%d dropped %s %s %d".formatted(currentTick[0], owner, action.name(), ticksLeft));
      }
    };
  }

  /** A link or unlink with the source's group as it stands after it, newest first. */
  private static String groupLine(
      int tick, String kind, CharacterEntity source, CharacterEntity child) {
    List<String> chain = source.group().stream().map(CharacterEntity::name).toList();
    return "%d %s %s %s %s".formatted(tick, kind, source.name(), child.name(), chain);
  }

  /** Lists what each area effect does, in the reference's layout. */
  static WorldObserver areaEffectLog(int[] currentTick, List<String> lines) {
    return new WorldObserver() {
      @Override
      public void areaEffectCreated(int tick, AreaEffectEntity a, String how, String source) {
        lines.add(
            "%d created %s %s %d %s %s %d %d %d %d %d"
                    .formatted(
                        currentTick[0],
                        a.name(),
                        a.getData().name(),
                        a.getId(),
                        how,
                        source,
                        a.side(),
                        a.getX(),
                        a.getY(),
                        a.getPackedLevel(),
                        a.getCountdown())
                + (a.getParent() == null ? "" : " parent " + a.getParent().name()));
      }

      @Override
      public void areaEffectAdmitted(int tick, AreaEffectEntity a) {
        lines.add("%d folded %s".formatted(currentTick[0], a.name()));
      }

      @Override
      public void areaEffectUpdated(
          int tick,
          AreaEffectEntity a,
          int before,
          int after,
          int hits,
          int radius,
          List<Integer> damages) {
        lines.add(
            "%d update %s %d %d hits %d r%d %s"
                .formatted(currentTick[0], a.name(), before, after, hits, radius, damages));
      }

      @Override
      public void areaEffectRemoved(int tick, AreaEffectEntity a) {
        lines.add("%d removed %s %d".formatted(currentTick[0], a.name(), a.getCountdown()));
      }

      @Override
      public void areaEffectLaunched(
          int tick,
          AreaEffectEntity a,
          int hit,
          int bound,
          AreaEffectEntity.Choice choice,
          ProjectileEntity p) {
        String line =
            "%d launch %s hit %d bound %d".formatted(currentTick[0], a.name(), hit, bound);
        if (choice != null) {
          line +=
              " reach %d candidates %s refused %s listed %s chosen %s"
                  .formatted(
                      choice.reach(),
                      choice.candidates().stream()
                          .map(c -> c.target().name() + " " + c.size())
                          .toList(),
                      choice.refused().stream().map(WorldEntity::name).toList(),
                      choice.struck(),
                      choice.chosen() == null ? null : choice.chosen().name());
        }
        if (p != null) {
          line +=
              " %s %s target %s at %d %d %d aim %d %d level %d side %d"
                  .formatted(
                      p.name(),
                      p.getData().name(),
                      p.getTarget() == null ? null : p.getTarget().name(),
                      p.getX(),
                      p.getY(),
                      p.getZ(),
                      p.getAimX(),
                      p.getAimY(),
                      p.getPackedLevel(),
                      p.side());
        }
        lines.add(line);
      }
    };
  }

  /** The reference's area-effect log in the same layout. */
  static List<String> expectedAreaEffects(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode a : reference.path("area_effects")) {
      int tick = a.get("tick").asInt();
      String name = a.get("area_effect").asText();
      switch (a.get("event").asText()) {
        case "created" ->
            expected.add(
                "%d created %s %s %d %s %s %d %d %d %d %d"
                        .formatted(
                            tick,
                            name,
                            a.get("row").asText(),
                            a.get("id").asInt(),
                            a.get("how").asText(),
                            a.get("source").isNull() ? null : a.get("source").asText(),
                            a.get("side").asInt(),
                            a.get("x").asInt(),
                            a.get("y").asInt(),
                            a.get("level").asInt(),
                            a.get("countdown").asInt())
                    // The battle keeps the parent of an area effect an action made, a target
                    // indicator attack made as its signal, Goblinstein's ability made as its
                    // death area, a unit's ability made at the unit, an evolved Royal Ghost's
                    // run made, or a resetable action made, and only that.
                    + (Set.of(
                                    "action",
                                    "target_indicator",
                                    "goblinstein_death",
                                    "ability",
                                    "ghost_evo",
                                    "resetable")
                                .contains(a.get("how").asText())
                            && !a.get("parent").isNull()
                        ? " parent " + a.get("parent").asText()
                        : ""));
        case "folded" -> expected.add("%d folded %s".formatted(tick, name));
        case "update" -> {
          List<Integer> damages = new ArrayList<>();
          a.get("damage").forEach(d -> damages.add(d.asInt()));
          expected.add(
              "%d update %s %d %d hits %d r%d %s"
                  .formatted(
                      tick,
                      name,
                      a.get("countdown").get(0).asInt(),
                      a.get("countdown").get(1).asInt(),
                      a.get("hits").asInt(),
                      a.get("radius").asInt(),
                      damages));
        }
        case "removed" ->
            expected.add("%d removed %s %d".formatted(tick, name, a.get("countdown").asInt()));
        case "launch" -> {
          String line =
              "%d launch %s hit %d bound %d"
                  .formatted(tick, name, a.get("hit").asInt(), a.get("bound").asInt());
          if (a.has("chosen")) {
            List<String> candidates = new ArrayList<>();
            a.get("candidates")
                .forEach(c -> candidates.add(c.get(0).asText() + " " + c.get(1).asInt()));
            List<String> refused = new ArrayList<>();
            a.get("refused").forEach(r -> refused.add(r.asText()));
            List<Integer> listed = new ArrayList<>();
            a.get("listed").forEach(l -> listed.add(l.asInt()));
            line +=
                " reach %d candidates %s refused %s listed %s chosen %s"
                    .formatted(
                        a.get("reach").asInt(),
                        candidates,
                        refused,
                        listed,
                        a.get("chosen").isNull() ? null : a.get("chosen").asText());
          }
          if (a.has("projectile")) {
            line +=
                " %s %s target %s at %d %d %d aim %d %d level %d side %d"
                    .formatted(
                        a.get("projectile").asText(),
                        a.get("config").asText(),
                        a.get("target").isNull() ? null : a.get("target").asText(),
                        a.get("x").asInt(),
                        a.get("y").asInt(),
                        a.get("z").asInt(),
                        a.get("aim").get(0).asInt(),
                        a.get("aim").get(1).asInt(),
                        a.get("level").asInt(),
                        a.get("side").asInt());
          }
          expected.add(line);
        }
        default -> throw new IllegalStateException("unknown area effect event " + a);
      }
    }
    return expected;
  }

  /** Lists what each buff does, in the reference's layout. */
  static WorldObserver buffLog(int[] currentTick, List<String> lines) {
    return new WorldObserver() {
      @Override
      public void areaBuff(
          int tick, AreaEffectEntity a, BuffData buff, int time, List<WorldEntity> targets) {
        lines.add(
            "%d area_buff %s %s %d %s"
                .formatted(
                    currentTick[0],
                    a.name(),
                    buff.name(),
                    time,
                    targets.stream().map(WorldEntity::name).toList()));
      }

      @Override
      public void projectileBuff(
          int tick, ProjectileEntity p, BuffData buff, int time, List<WorldEntity> targets) {
        lines.add(
            "%d target_buff %s %s %d %s"
                .formatted(
                    currentTick[0],
                    p.name(),
                    buff.name(),
                    time,
                    targets.stream().map(WorldEntity::name).toList()));
      }

      @Override
      public void buffDeathSpawn(
          int tick, WorldEntity dying, BuffInstance buff, List<CharacterEntity> made) {
        lines.add(
            "%d death_spawn %s %s %d %d %s"
                .formatted(
                    currentTick[0],
                    dying.name(),
                    buff.getBuff().deathSpawn(),
                    made.size(),
                    buff.getPackedLevel(),
                    made.stream()
                        .map(
                            c ->
                                "%s %d %d %d %d %d %d"
                                    .formatted(
                                        c.name(),
                                        c.side(),
                                        c.getView().getX(),
                                        c.getView().getY(),
                                        c.getView().getState(),
                                        c.getView().getDeployCountdown(),
                                        c.getHitPoints().getMaximum()))
                        .toList()));
      }

      @Override
      public void buffApplied(int tick, WorldEntity target, BuffInstance buff) {
        // A placement comes before the first step, on the tick it is placed for.
        lines.add(
            "%d applied %s %s %s %d %d %s"
                .formatted(
                    currentTick[0] < 0 ? tick : currentTick[0],
                    target.name(),
                    buff.getBuff().name(),
                    buff.getKey(),
                    buff.getRemaining(),
                    buff.getPackedLevel(),
                    buff.getSource() == null ? null : buff.getSource().name()));
      }

      @Override
      public void buffCopied(int tick, WorldEntity original, WorldEntity clone, BuffInstance copy) {
        lines.add(
            "%d copied %s %s %s %d %d %s from %s"
                .formatted(
                    currentTick[0],
                    clone.name(),
                    copy.getBuff().name(),
                    copy.getKey(),
                    copy.getRemaining(),
                    copy.getPackedLevel(),
                    copy.getSource() == null ? null : copy.getSource().name(),
                    original.name()));
      }

      @Override
      public void buffRefreshed(
          int tick, WorldEntity target, BuffInstance buff, int before, SpawnHost source) {
        // The reference names what the re-application came from.
        lines.add(
            "%d refreshed %s %s %s %d %d %s"
                .formatted(
                    currentTick[0],
                    target.name(),
                    buff.getBuff().name(),
                    buff.getKey(),
                    before,
                    buff.getRemaining(),
                    source == null ? null : source.name()));
      }

      @Override
      public void buffRemoved(int tick, WorldEntity target, BuffInstance buff) {
        lines.add(
            "%d removed %s %s %s"
                .formatted(currentTick[0], target.name(), buff.getBuff().name(), buff.getKey()));
      }

      @Override
      public void buffDamaged(
          int tick,
          WorldEntity target,
          BuffInstance buff,
          int damage,
          int hitPointsBefore,
          DamageResult result) {
        lines.add(
            "%d damage %s %d %d"
                .formatted(
                    currentTick[0], target.name(), damage, target.getTargetView().getHitPoints()));
      }

      @Override
      public void buffHealed(
          int tick, WorldEntity target, BuffInstance buff, int amount, int hitPointsBefore) {
        lines.add(
            "%d heal %s %d %d %d %d"
                .formatted(
                    currentTick[0],
                    target.name(),
                    amount,
                    hitPointsBefore,
                    target.getHitPoints().getHitPoints(),
                    target.getHitPoints().getMaximum()));
      }
    };
  }

  /** The reference's buff log in the same layout. */
  static List<String> expectedBuffLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode b : reference.path("buffs")) {
      int tick = b.get("tick").asInt();
      String source = b.path("source").isNull() ? null : b.path("source").asText();
      switch (b.get("event").asText()) {
        case "area_buff" -> {
          List<String> targets = new ArrayList<>();
          b.get("targets").forEach(t -> targets.add(t.asText()));
          expected.add(
              "%d area_buff %s %s %d %s"
                  .formatted(
                      tick,
                      b.get("area_effect").asText(),
                      b.get("buff").asText(),
                      b.get("time").asInt(),
                      targets));
        }
        case "target_buff" -> {
          List<String> targets = new ArrayList<>();
          b.get("targets").forEach(t -> targets.add(t.asText()));
          expected.add(
              "%d target_buff %s %s %d %s"
                  .formatted(
                      tick,
                      b.get("projectile").asText(),
                      b.get("buff").asText(),
                      b.get("time").asInt(),
                      targets));
        }
        case "death_spawn" -> {
          List<String> units = new ArrayList<>();
          b.get("units")
              .forEach(
                  u ->
                      units.add(
                          "%s %d %d %d %d %d %d"
                              .formatted(
                                  u.get(0).asText(),
                                  u.get(1).asInt(),
                                  u.get(2).asInt(),
                                  u.get(3).asInt(),
                                  u.get(4).asInt(),
                                  u.get(5).asInt(),
                                  u.get(6).asInt())));
          expected.add(
              "%d death_spawn %s %s %d %d %s"
                  .formatted(
                      tick,
                      b.get("target").asText(),
                      b.get("row").asText(),
                      b.get("count").asInt(),
                      b.get("level").asInt(),
                      units));
        }
        case "applied" ->
            expected.add(
                "%d applied %s %s %s %d %d %s"
                    .formatted(
                        tick,
                        b.get("target").asText(),
                        b.get("buff").asText(),
                        b.get("key").asText(),
                        b.get("time").asInt(),
                        b.get("level").asInt(),
                        source));
        case "copied" ->
            expected.add(
                "%d copied %s %s %s %d %d %s from %s"
                    .formatted(
                        tick,
                        b.get("target").asText(),
                        b.get("buff").asText(),
                        b.get("key").asText(),
                        b.get("remaining").asInt(),
                        b.get("level").asInt(),
                        source,
                        b.get("original").asText()));
        case "refreshed" ->
            expected.add(
                "%d refreshed %s %s %s %d %d %s"
                    .formatted(
                        tick,
                        b.get("target").asText(),
                        b.get("buff").asText(),
                        b.get("key").asText(),
                        b.get("remaining").get(0).asInt(),
                        b.get("remaining").get(1).asInt(),
                        source));
        case "removed" ->
            expected.add(
                "%d removed %s %s %s"
                    .formatted(
                        tick,
                        b.get("target").asText(),
                        b.get("buff").asText(),
                        b.get("key").asText()));
        case "damage" ->
            expected.add(
                "%d damage %s %d %d"
                    .formatted(
                        tick,
                        b.get("target").asText(),
                        b.get("damage").asInt(),
                        b.get("hp").asInt()));
        case "heal" ->
            expected.add(
                "%d heal %s %d %d %d %d"
                    .formatted(
                        tick,
                        b.get("target").asText(),
                        b.get("amount").asInt(),
                        b.get("hp").get(0).asInt(),
                        b.get("hp").get(1).asInt(),
                        b.get("max").asInt()));
        default -> throw new IllegalStateException("unknown buff event " + b);
      }
    }
    return expected;
  }

  /**
   * Lists what every Clone does, and every area effect an impact makes, in the reference's layout.
   */
  static WorldObserver cloneLog(int[] currentTick, List<String> lines) {
    return new WorldObserver() {
      @Override
      public void projectileAreaEffect(int tick, ProjectileEntity p, AreaEffectEntity a) {
        lines.add(
            "%d area_effect_from_projectile %s %s at %d %d level %d target %s"
                .formatted(
                    currentTick[0],
                    a.name(),
                    p.name(),
                    a.getX(),
                    a.getY(),
                    a.getPackedLevel(),
                    p.getTarget() == null ? null : p.getTarget().name()));
      }

      @Override
      public void onHitActionScheduled(
          int tick, AreaEffectEntity a, WorldEntity target, BattleAction action) {
        // The hit actions of an area effect that does not clone are in the area-effect spawn log.
        if (a.getData().cloning()) {
          lines.add(
              "%d scheduled %s %s %s"
                  .formatted(currentTick[0], a.name(), target.name(), action.name()));
        }
      }

      @Override
      public void buffSpawned(
          int tick,
          WorldEntity owner,
          String action,
          BuffData buff,
          int time,
          int packedLevel,
          SpawnHost source) {
        // The reference lists the buffs a Clone's actions spawn; every buff any spawn applies is
        // in the buff log.
        if (!(source instanceof AreaEffectEntity a && a.getData().cloning())) {
          return;
        }
        lines.add(
            "%d buff_spawn %s %s %s %d %d %s"
                .formatted(
                    currentTick[0],
                    owner.name(),
                    action,
                    buff.name(),
                    time,
                    packedLevel,
                    source.name()));
      }

      @Override
      public void cloneRefused(
          int tick, WorldEntity original, String reason, SpawnHost instigator) {
        lines.add(
            "%d refused %s %s %s"
                .formatted(currentTick[0], original.name(), reason, instigator.name()));
      }

      @Override
      public void cloned(
          int tick,
          CharacterEntity original,
          CharacterEntity clone,
          SpawnHost instigator,
          List<Integer> registrationVisits) {
        HitPoints hp = clone.getHitPoints();
        lines.add(
            "%d clone %s %s %d %s at %d %d side %d level %d hp %d shield %s state %d deploy %d"
                    .formatted(
                        currentTick[0],
                        original.name(),
                        clone.name(),
                        clone.getId(),
                        clone.getData().name(),
                        clone.getView().getX(),
                        clone.getView().getY(),
                        clone.side(),
                        clone.getPackedLevel(),
                        hp.getHitPoints(),
                        List.of(hp.getShield(), hp.getShieldMaximum()),
                        clone.getView().getState(),
                        clone.getView().getDeployCountdown())
                + " visits %s by %s".formatted(registrationVisits, instigator.name()));
      }

      @Override
      public void cloneMoveStarted(int tick, CharacterEntity unit, int targetX, int targetY) {
        lines.add(
            "%d move_start %s at %d %d target %d %d route %s"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    unit.getView().getX(),
                    unit.getView().getY(),
                    targetX,
                    targetY,
                    Arrays.toString(unit.getUnit().movement().getRoute().toArray())));
      }

      @Override
      public void cloneMoveEnded(int tick, CharacterEntity unit, boolean resumed) {
        lines.add(
            "%d move_end %s at %d %d state %d resumed %s"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    unit.getView().getX(),
                    unit.getView().getY(),
                    unit.getView().getState(),
                    resumed));
      }
    };
  }

  /**
   * Lists every area effect an action spawned and every hit action an area effect that does not
   * clone scheduled, in the reference's layout.
   */
  static WorldObserver areaEffectSpawnLog(int[] currentTick, List<String> lines) {
    return new WorldObserver() {
      @Override
      public void areaEffectSpawned(
          int tick,
          SpawnHost owner,
          String action,
          int phase,
          SpawnHost source,
          AreaEffectEntity a) {
        lines.add(
            ("%d spawn_area_effect %s %s phase %d source %s %s %d at %d %d side %d level %d parent %s"
                    + " follow %s")
                .formatted(
                    currentTick[0],
                    owner.name(),
                    action,
                    phase,
                    source.name(),
                    a.name(),
                    a.getId(),
                    a.getX(),
                    a.getY(),
                    a.side(),
                    a.getPackedLevel(),
                    a.getParent().name(),
                    a.getFollow() == null ? null : a.getFollow().name()));
      }

      @Override
      public void tauntPerformed(
          int tick,
          CharacterEntity unit,
          String action,
          int phase,
          ActionOwner instigator,
          WorldEntity forced) {
        lines.add(
            "%d taunt_perform %s %s phase %d instigator %s parent %s"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    action,
                    phase,
                    ((AreaEffectEntity) instigator).name(),
                    forced.name()));
      }

      @Override
      public void tauntStepped(
          int tick,
          CharacterEntity unit,
          WorldEntity forced,
          int durationMs,
          int falloffMs,
          List<String> calls) {
        lines.add(
            "%d taunt %s forced %s duration %d falloff %d calls %s ref %s cooldown %d"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    forced == null ? null : forced.name(),
                    durationMs,
                    falloffMs,
                    calls,
                    referenceName(unit),
                    unit.getTargeting().getRetargetCooldownMs()));
      }

      @Override
      public void onHitActionScheduled(
          int tick, AreaEffectEntity a, WorldEntity target, BattleAction action) {
        if (!a.getData().cloning()) {
          lines.add(
              "%d scheduled %s %s %s"
                  .formatted(currentTick[0], a.name(), target.name(), action.name()));
        }
      }
    };
  }

  /**
   * The reference's area-effect spawn log in the same layout. No area effect it lists carries a
   * clone byte, which the battle refuses, and every taunt it lists reaches one unit, which carries
   * no riders.
   */
  static List<String> expectedAreaEffectSpawnLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode e : reference.path("area_effect_spawn")) {
      int tick = e.get("tick").asInt();
      switch (e.get("event").asText()) {
        case "spawn_area_effect" -> {
          assertThat(e.get("b119").asInt())
              .as("tick %d: the area effect is no clone's", tick)
              .isZero();
          expected.add(
              ("%d spawn_area_effect %s %s phase %d source %s %s %d at %d %d side %d level %d"
                      + " parent %s follow %s")
                  .formatted(
                      tick,
                      e.get("owner").asText(),
                      e.get("action").asText(),
                      e.get("phase").asInt(),
                      e.get("source").asText(),
                      e.get("area_effect").asText(),
                      e.get("id").asInt(),
                      e.get("x").asInt(),
                      e.get("y").asInt(),
                      e.get("side").asInt(),
                      e.get("level").asInt(),
                      e.get("parent").asText(),
                      e.get("follow").isNull() ? null : e.get("follow").asText()));
        }
        case "taunt_perform" -> {
          assertThat(e.get("instances").asInt()).as("tick %d: one unit taunted", tick).isOne();
          expected.add(
              "%d taunt_perform %s %s phase %d instigator %s parent %s"
                  .formatted(
                      tick,
                      e.get("unit").asText(),
                      e.get("action").asText(),
                      e.get("phase").asInt(),
                      e.get("instigator").asText(),
                      e.get("parent").asText()));
        }
        case "taunt" -> {
          List<String> calls = new ArrayList<>();
          for (JsonNode c : e.get("calls")) {
            calls.add(
                switch (c.get(0).asText()) {
                  case "set_target" ->
                      "set_target %s %d %d %d"
                          .formatted(
                              c.get(2).isNull() ? null : c.get(2).asText(),
                              c.get(3).asInt(),
                              c.get(4).asInt(),
                              c.get(5).asInt());
                  case "raise" -> "raise " + c.get(2).asText();
                  case "remaining" -> "remaining " + c.get(2).asInt();
                  case "apply_buff" ->
                      "apply_buff %s %d level %d source %s side %d"
                          .formatted(
                              c.get(3).asText(),
                              c.get(4).asInt(),
                              c.get(5).asInt(),
                              c.get(6).asText(),
                              c.get(7).asInt());
                  case "remove_buff" -> "remove_buff " + c.get(3).asText();
                  case "finish" -> "finish";
                  default -> throw new IllegalStateException("unknown taunt call " + c);
                });
          }
          expected.add(
              "%d taunt %s forced %s duration %d falloff %d calls %s ref %s cooldown %d"
                  .formatted(
                      tick,
                      e.get("unit").asText(),
                      e.get("forced").isNull() ? null : e.get("forced").asText(),
                      e.get("duration").asInt(),
                      e.get("falloff").asInt(),
                      calls,
                      e.get("ref").isNull() ? null : e.get("ref").asText(),
                      e.get("f1c").asInt()));
        }
        case "scheduled" ->
            expected.add(
                "%d scheduled %s %s %s"
                    .formatted(
                        tick,
                        e.get("area_effect").asText(),
                        e.get("target").asText(),
                        e.get("action").asText()));
        default -> throw new IllegalStateException("unknown area-effect spawn event " + e);
      }
    }
    return expected;
  }

  /** The reference's Clone log in the same layout. */
  static List<String> expectedCloneLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode c : reference.path("clones")) {
      int tick = c.get("tick").asInt();
      switch (c.get("event").asText()) {
        case "area_effect_from_projectile" ->
            expected.add(
                "%d area_effect_from_projectile %s %s at %d %d level %d target %s"
                    .formatted(
                        tick,
                        c.get("area_effect").asText(),
                        c.get("projectile").asText(),
                        c.get("x").asInt(),
                        c.get("y").asInt(),
                        c.get("level").asInt(),
                        c.get("target").isNull() ? null : c.get("target").asText()));
        case "scheduled" ->
            expected.add(
                "%d scheduled %s %s %s"
                    .formatted(
                        tick,
                        c.get("area_effect").asText(),
                        c.get("target").asText(),
                        c.get("action").asText()));
        case "buff_spawn" ->
            expected.add(
                "%d buff_spawn %s %s %s %d %d %s"
                    .formatted(
                        tick,
                        c.get("owner").asText(),
                        c.get("action").asText(),
                        c.get("buff").asText(),
                        c.get("time").asInt(),
                        c.get("level").asInt(),
                        c.get("source").asText()));
        case "refused" ->
            expected.add(
                "%d refused %s %s %s"
                    .formatted(
                        tick,
                        c.get("original").asText(),
                        c.get("reason").asText(),
                        c.get("instigator").asText()));
        case "clone" -> {
          List<Integer> shield = new ArrayList<>();
          c.get("shield").forEach(v -> shield.add(v.asInt()));
          List<Integer> visits = new ArrayList<>();
          c.get("registration_visits").forEach(v -> visits.add(v.asInt()));
          expected.add(
              "%d clone %s %s %d %s at %d %d side %d level %d hp %d shield %s state %d deploy %d"
                      .formatted(
                          tick,
                          c.get("original").asText(),
                          c.get("clone").asText(),
                          c.get("id").asInt(),
                          c.get("row").asText(),
                          c.get("x").asInt(),
                          c.get("y").asInt(),
                          c.get("side").asInt(),
                          c.get("level").asInt(),
                          c.get("hp").asInt(),
                          shield,
                          c.get("state").asInt(),
                          c.get("deploy").asInt())
                  + " visits %s by %s".formatted(visits, c.get("instigator").asText()));
        }
        case "move_start" -> {
          List<Integer> route = new ArrayList<>();
          c.get("route").forEach(v -> route.add(v.asInt()));
          expected.add(
              "%d move_start %s at %d %d target %d %d route %s"
                  .formatted(
                      tick,
                      c.get("unit").asText(),
                      c.get("x").asInt(),
                      c.get("y").asInt(),
                      c.get("target").get(0).asInt(),
                      c.get("target").get(1).asInt(),
                      route));
        }
        case "move_end" ->
            expected.add(
                "%d move_end %s at %d %d state %d resumed %s"
                    .formatted(
                        tick,
                        c.get("unit").asText(),
                        c.get("x").asInt(),
                        c.get("y").asInt(),
                        c.get("state").asInt(),
                        c.get("resumed").asBoolean()));
        default -> throw new IllegalStateException("unknown clone event " + c);
      }
    }
    return expected;
  }

  /** Logs every special load armed, every state a drag set, and every hold and pull let go. */
  private static WorldObserver hookLog(int[] currentTick, List<String> log) {
    return new WorldObserver() {
      @Override
      public void specialArmed(
          int tick,
          CharacterEntity unit,
          WorldEntity reference,
          long distanceSquared,
          int ringMin,
          int ringMax,
          int loadMs,
          int afterMs) {
        log.add(
            "%d arm %s ref %s distance %d ring %d %d load %d after %d"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    reference.name(),
                    distanceSquared,
                    ringMin,
                    ringMax,
                    loadMs,
                    afterMs));
      }

      @Override
      public void dragStateSet(
          int tick, ProjectileEntity projectile, WorldEntity unit, int oldState, int newState) {
        GridEntity view = unit.getView();
        log.add(
            "%d state %s %s %d to %d now %d at %d %d"
                .formatted(
                    currentTick[0],
                    attackerName(projectile),
                    unit.name(),
                    oldState,
                    newState,
                    view.getState(),
                    view.getX(),
                    view.getY()));
      }

      @Override
      public void holdLeft(int tick, WorldEntity unit, ProjectileEntity projectile) {
        log.add(
            "%d held_left %s %s".formatted(currentTick[0], unit.name(), attackerName(projectile)));
      }

      @Override
      public void followLeft(int tick, WorldEntity unit, ProjectileEntity projectile) {
        GridEntity view = unit.getView();
        log.add(
            "%d follow_left %s %s now %d at %d %d"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    attackerName(projectile),
                    view.getState(),
                    view.getX(),
                    view.getY()));
      }
    };
  }

  /**
   * Logs every death projectile with its start and aim, every unit made with the immunity its row
   * starts it with, and every spawner destroyed at its limit with its hit points.
   */
  private static WorldObserver phoenixLog(int[] currentTick, List<String> log) {
    return new WorldObserver() {
      @Override
      public void deathProjectileLaunched(
          int tick, WorldEntity dying, ProjectileEntity projectile) {
        log.add(
            "%d death_projectile %s %s %s start %d %d %d aim %d %d"
                .formatted(
                    currentTick[0],
                    dying.name(),
                    projectile.name(),
                    projectile.getData().name(),
                    projectile.getStartX(),
                    projectile.getStartY(),
                    projectile.getStartZ(),
                    projectile.getAimX(),
                    projectile.getAimY()));
      }

      @Override
      public void characterSpawned(
          int tick, SpawnHost source, CharacterEntity child, int x, int y) {
        if (child.getData().untargetableWhenSpawned() && child.isSpawnImmune()) {
          log.add(
              "%d untargetable_when_spawned %s at %d %d"
                  .formatted(currentTick[0], child.name(), x, y));
        }
      }

      @Override
      public void destroyedAtLimit(int tick, CharacterEntity spawner) {
        GridEntity view = spawner.getView();
        log.add(
            "%d destroy_at_limit %s at %d %d hp %d"
                .formatted(
                    currentTick[0],
                    spawner.name(),
                    view.getX(),
                    view.getY(),
                    spawner.getHitPoints().getHitPoints()));
      }
    };
  }

  /** The reference's Phoenix log, in the Phoenix log's layout. */
  private static List<String> expectedPhoenixLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode p : reference.path("phoenix")) {
      int tick = p.get("tick").asInt();
      switch (p.get("event").asText()) {
        case "death_projectile" ->
            expected.add(
                "%d death_projectile %s %s %s start %d %d %d aim %d %d"
                    .formatted(
                        tick,
                        p.get("unit").asText(),
                        p.get("projectile").asText(),
                        p.get("config").asText(),
                        p.get("start").get(0).asInt(),
                        p.get("start").get(1).asInt(),
                        p.get("start").get(2).asInt(),
                        p.get("aim").get(0).asInt(),
                        p.get("aim").get(1).asInt()));
        case "untargetable_when_spawned" ->
            expected.add(
                "%d untargetable_when_spawned %s at %d %d"
                    .formatted(
                        tick, p.get("unit").asText(), p.get("x").asInt(), p.get("y").asInt()));
        case "destroy_at_limit" ->
            expected.add(
                "%d destroy_at_limit %s at %d %d hp %d"
                    .formatted(
                        tick,
                        p.get("unit").asText(),
                        p.get("x").asInt(),
                        p.get("y").asInt(),
                        p.get("hp").asInt()));
        default -> throw new IllegalStateException("unknown Phoenix event " + p);
      }
    }
    return expected;
  }

  /**
   * Logs every Kamikaze end with the hit points it found and whether it killed, every drain with
   * what it took and the hit points before and after, and every lane a const-priority ring asked
   * for at its source's point.
   */
  private static WorldObserver kamikazeLog(
      int[] currentTick, List<String> log, List<String> lanes) {
    return new WorldObserver() {
      @Override
      public void kamikazeHitEnded(int tick, WorldEntity unit, boolean kills) {
        log.add(
            "%d end %s hp %d kill %s"
                .formatted(currentTick[0], unit.name(), unit.getHitPoints().getHitPoints(), kills));
      }

      @Override
      public void kamikazeDrained(
          int tick, WorldEntity unit, int damage, int hitPointsBefore, DamageResult result) {
        log.add(
            "%d drain %s %d hp %d before %d"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    damage,
                    unit.getHitPoints().getHitPoints(),
                    hitPointsBefore));
      }

      @Override
      public void ringLaneAsked(int tick, SpawnHost source, int x, int y, int lane) {
        lanes.add(
            "%d const_priority_lane %s %d %d lane %d"
                .formatted(currentTick[0], source.name(), x, y, lane));
      }
    };
  }

  /** Logs every start and notice of a Berserker's index toggle, with the index before and after. */
  private static WorldObserver berserkLog(int[] currentTick, List<String> log) {
    return new WorldObserver() {
      @Override
      public void berserked(
          int tick, WorldEntity unit, Berserk.Event event, int before, int index) {
        log.add(
            "%d berserk %s %s %d %d"
                .formatted(
                    currentTick[0],
                    event.name().toLowerCase(Locale.ROOT),
                    unit.name(),
                    before,
                    index));
      }
    };
  }

  /**
   * Logs every ask of an area effect's buff test of a clone: the area effect, its buff, the clone,
   * the path that asked, the query and whether it refused.
   */
  private static WorldObserver cloneGateLog(int[] currentTick, List<String> log) {
    return new WorldObserver() {
      @Override
      public void cloneBuffGateAsked(
          int tick,
          AreaEffectEntity areaEffect,
          String buff,
          CharacterEntity clone,
          String path,
          int query,
          boolean refused) {
        log.add(
            "%d gate %s %s %s %s query %d refused %b"
                .formatted(
                    currentTick[0], areaEffect.name(), buff, clone.name(), path, query, refused));
      }
    };
  }

  /**
   * Logs every read of attack_count, with the attack time it divided, and every ask of a buff's
   * life condition, with its expression and answer.
   */
  private static WorldObserver princeLog(int[] currentTick, List<String> log) {
    return new WorldObserver() {
      @Override
      public void attackCountRead(int tick, WorldEntity context, int attackTimeMs, int count) {
        log.add(
            "%d attack_count %s %d %d"
                .formatted(currentTick[0], context.name(), attackTimeMs, count));
      }

      @Override
      public void lifeConditionAsked(int tick, WorldEntity carrier, BuffInstance buff, int answer) {
        log.add(
            "%d alive_if %s %s %d"
                .formatted(currentTick[0], carrier.name(), buff.getBuff().aliveIfTrue(), answer));
      }
    };
  }

  /**
   * Logs every count that applied a BuffAfterHits buff, with the counter before and after, and
   * every buff's start or remove action as it is scheduled, with whether its carrier's own pending
   * pass was running.
   */
  private static WorldObserver buffAfterHitsLog(List<String> log) {
    return new WorldObserver() {
      @Override
      public void hitCounted(
          int tick,
          WorldEntity attacker,
          WorldEntity target,
          int before,
          int after,
          String buff,
          int timeMs) {
        if (buff != null) {
          log.add(
              "%d buff_after_hits %s %s %d %d %s %d"
                  .formatted(tick, attacker.name(), target.name(), before, after, buff, timeMs));
        }
      }

      @Override
      public void ghostEvoStarted(int tick, CharacterEntity ghost, String action, int phase) {
        log.add("%d ghost_evo_start %s %s %d".formatted(tick, ghost.name(), action, phase));
      }

      @Override
      public void ghostAreaMade(
          int tick, CharacterEntity ghost, AreaEffectEntity area, WorldEntity target) {
        log.add(
            "%d area %s %s %s %d %d %s %d"
                .formatted(
                    tick,
                    ghost.name(),
                    area.getData().name(),
                    area.name(),
                    area.getX(),
                    area.getY(),
                    target == null ? null : target.name(),
                    area.packedLevel()));
      }

      @Override
      public void ghostSummoned(
          int tick, CharacterEntity ghost, WorldEntity reference, int x, int y, int countdownMs) {
        log.add(
            "%d summon %s %s %d %d %d"
                .formatted(tick, ghost.name(), reference.name(), x, y, countdownMs));
      }

      @Override
      public void ghostSummonSpawned(int tick, AreaEffectEntity area, CharacterEntity summon) {
        TargetView reference = summon.getTargeting().getReference();
        log.add(
            "%d summoned %s %s %d %d %d %d %d %d %s %d %d"
                .formatted(
                    tick,
                    area.name(),
                    summon.name(),
                    summon.getId(),
                    summon.getView().getX(),
                    summon.getView().getY(),
                    summon.getView().getState(),
                    summon.getView().getDeployCountdown(),
                    summon.getPackedLevel(),
                    reference == null ? null : reference.getEntity().getName(),
                    summon.getView().getDirX(),
                    summon.getView().getDirY()));
      }

      @Override
      public void buffHookScheduled(
          int tick, WorldEntity carrier, BuffInstance buff, String action, boolean start) {
        log.add(
            "%d buff_hook %s %s %b"
                .formatted(tick, carrier.name(), action, carrier.actionHolder().passPhase() != 0));
      }
    };
  }

  /**
   * Logs every action a broken shield scheduled, with what broke it and whether a pending pass was
   * in progress, and every charge reset a listed buff that gives a charge range made.
   */
  private static WorldObserver shieldLostLog(List<String> log) {
    return new WorldObserver() {
      @Override
      public void shieldLostScheduled(
          int tick, WorldEntity unit, String action, SpawnHost cause, boolean inPendingPass) {
        log.add(
            "%d scheduled %s %s %s %b"
                .formatted(
                    tick, unit.name(), action, cause == null ? null : cause.name(), inPendingPass));
      }

      @Override
      public void buffChargeReset(
          int tick, CharacterEntity unit, BuffInstance instance, int before, int after) {
        log.add("%d charge_reset %s %d %d".formatted(tick, unit.name(), before, after));
      }
    };
  }

  /** The names of the watched tags a word carries, in the reference's order. */
  private static String tagNames(BattleWorld world, long word) {
    List<String> names = new ArrayList<>();
    String[] all = {"NO_MOVE", "NO_ATTACK", "LOCK_TARGET", "FORCE_IS_AIR", "DISABLE_PHYSICAL"};
    long[] bits = {
      EntityFlags.NO_MOVE,
      EntityFlags.NO_ATTACK,
      EntityFlags.LOCK_TARGET,
      world.forceIsAir(),
      EntityFlags.DISABLE_PHYSICAL
    };
    for (int i = 0; i < all.length; i++) {
      if ((word & bits[i]) != 0) {
        names.add(all[i]);
      }
    }
    return names.toString();
  }

  /** A name, or null for no entity. */
  private static String nameOf(WorldEntity entity) {
    return entity == null ? null : entity.name();
  }

  /**
   * Logs what every uppercut, knock and wind did, every change of a watched tag word, every
   * rectangle's list, every choice by team and every action run at an age.
   */
  private static WorldObserver uppercutWindLog(BattleWorld world, List<String> log) {
    return new WorldObserver() {
      @Override
      public void tagWordChanged(int tick, WorldEntity entity, long word) {
        log.add("%d tags %s %s".formatted(tick, entity.name(), tagNames(world, word)));
      }

      @Override
      public void uppercutStarted(
          int tick,
          CharacterEntity unit,
          String action,
          int phase,
          WorldEntity instigator,
          WorldEntity target,
          boolean finished) {
        log.add(
            "%d uppercut_start %s %d %s %s %b"
                .formatted(tick, unit.name(), phase, nameOf(instigator), nameOf(target), finished));
      }

      @Override
      public void uppercutStepped(
          int tick,
          CharacterEntity unit,
          int delay,
          boolean finished,
          String outcome,
          int[] pushPoint) {
        log.add(
            "%d uppercut_update %s %d %b %s %s %s"
                .formatted(
                    tick,
                    unit.name(),
                    delay,
                    finished,
                    outcome,
                    pushPoint == null ? null : List.of(pushPoint[0], pushPoint[1]),
                    pushPoint == null ? null : List.of(pushPoint[2], pushPoint[3])));
      }

      @Override
      public void uppercutMarked(
          int tick, CharacterEntity unit, WorldEntity target, int priority, WorldEntity current) {
        log.add(
            "%d uppercut_mark %s %s %d %s"
                .formatted(tick, unit.name(), target.name(), priority, nameOf(current)));
      }

      @Override
      public void targetQueueFlushed(int tick, CharacterEntity unit) {
        log.add("%d queue_flushed %s".formatted(tick, unit.name()));
      }

      @Override
      public void uppercutTargetLeft(int tick, CharacterEntity unit, WorldEntity target) {
        log.add("%d uppercut_target_left %s %s".formatted(tick, unit.name(), target.name()));
      }

      @Override
      public void knockbackStarted(
          int tick,
          CharacterEntity unit,
          String action,
          int phase,
          WorldEntity instigator,
          int counter) {
        log.add(
            "%d knockback_start %s %d %s %d %b"
                .formatted(tick, unit.name(), phase, nameOf(instigator), counter, false));
      }

      @Override
      public void knockbackStepped(
          int tick,
          CharacterEntity unit,
          int before,
          int after,
          int height,
          long tags,
          boolean finished) {
        log.add(
            "%d knockback_update %s %d %d %d %s %b"
                .formatted(
                    tick, unit.name(), before, after, height, tagNames(world, tags), finished));
      }

      @Override
      public void resetableStarted(
          int tick,
          CharacterEntity unit,
          int phase,
          WorldEntity instigator,
          AreaEffectEntity areaEffect,
          int x,
          int y) {
        log.add(
            "%d wind_start %s %d %s %s %d %d"
                .formatted(tick, unit.name(), phase, nameOf(instigator), areaEffect.name(), x, y));
      }

      @Override
      public void resetableEnded(int tick, CharacterEntity unit, String areaEffect) {
        log.add("%d wind_update %s %s %b".formatted(tick, unit.name(), "area effect gone", true));
      }

      @Override
      public void resetableRetriggered(
          int tick, CharacterEntity unit, int phase, String areaEffect, Integer countdown) {
        log.add(
            "%d wind_retrigger %s %d %s %s"
                .formatted(tick, unit.name(), phase, areaEffect, countdown));
      }

      @Override
      public void resetableLeft(int tick, CharacterEntity unit, String areaEffect) {
        log.add("%d wind_left %s %s".formatted(tick, unit.name(), areaEffect));
      }

      @Override
      public void resetableReleased(
          int tick, CharacterEntity unit, String areaEffect, int before, int after) {
        log.add(
            "%d wind_release %s %s %s %d %d"
                .formatted(tick, unit.name(), "owner left", areaEffect, before, after));
      }

      @Override
      public void shapeListed(int tick, AreaEffectEntity areaEffect, List<WorldEntity> listed) {
        log.add(
            "%d wind_collect %s %d %d %d %s"
                .formatted(
                    tick,
                    areaEffect.name(),
                    areaEffect.getX(),
                    areaEffect.getY(),
                    areaEffect.getCountdown(),
                    listed.stream().map(WorldEntity::name).toList()));
      }

      @Override
      public void filteredByTeam(
          int tick,
          WorldEntity unit,
          String action,
          SpawnHost instigator,
          boolean sameTeam,
          String chosen) {
        log.add(
            "%d filter_by_enemy %s %s %d %s"
                .formatted(
                    tick,
                    unit.name(),
                    instigator == null ? null : instigator.name(),
                    sameTeam ? 1 : 0,
                    chosen));
      }

      @Override
      public void aliveTimerFired(int tick, AreaEffectEntity areaEffect, String action) {
        log.add("%d alive_timer %s %s".formatted(tick, areaEffect.name(), action));
      }
    };
  }

  /** A list of numbers in the reference as the log writes it, or null. */
  private static String numbers(JsonNode list) {
    if (list == null || list.isNull()) {
      return null;
    }
    List<Integer> out = new ArrayList<>();
    list.forEach(n -> out.add(n.asInt()));
    return out.toString();
  }

  /** A list of names in the reference as the log writes it. */
  private static String names(JsonNode list) {
    List<String> out = new ArrayList<>();
    list.forEach(n -> out.add(n.asText()));
    return out.toString();
  }

  /** The reference's uppercut, knock and wind log, in its order. */
  private static List<String> expectedUppercutWindLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode e : reference.path("uppercut_wind")) {
      int tick = e.get("tick").asInt();
      String unit = e.path("unit").asText(null);
      expected.add(
          switch (e.get("event").asText()) {
            case "tags" -> "%d tags %s %s".formatted(tick, unit, names(e.get("tags")));
            case "uppercut_start" ->
                "%d uppercut_start %s %d %s %s %b"
                    .formatted(
                        tick,
                        unit,
                        e.get("phase").asInt(),
                        e.get("instigator").asText(null),
                        e.get("target").asText(null),
                        e.get("finished").asBoolean());
            case "uppercut_update" ->
                "%d uppercut_update %s %d %b %s %s %s"
                    .formatted(
                        tick,
                        unit,
                        e.get("delay").asInt(),
                        e.get("finished").asBoolean(),
                        e.get("outcome").get(0).asText(),
                        numbers(e.get("push_point")),
                        numbers(e.get("vector")));
            case "uppercut_mark" ->
                "%d uppercut_mark %s %s %d %s"
                    .formatted(
                        tick,
                        unit,
                        e.get("target").asText(),
                        e.get("priority").asInt(),
                        e.get("current").asText(null));
            case "queue_flushed" -> "%d queue_flushed %s".formatted(tick, unit);
            case "uppercut_target_left" ->
                "%d uppercut_target_left %s %s".formatted(tick, unit, e.get("target").asText());
            case "knockback_start" ->
                "%d knockback_start %s %d %s %d %b"
                    .formatted(
                        tick,
                        unit,
                        e.get("phase").asInt(),
                        e.get("instigator").asText(null),
                        e.get("counter").asInt(),
                        e.get("finished").asBoolean());
            case "knockback_update" ->
                "%d knockback_update %s %d %d %d %s %b"
                    .formatted(
                        tick,
                        unit,
                        e.get("counter").get(0).asInt(),
                        e.get("counter").get(1).asInt(),
                        e.get("height").asInt(),
                        names(e.get("tags")),
                        e.get("finished").asBoolean());
            case "wind_start" ->
                "%d wind_start %s %d %s %s %d %d"
                    .formatted(
                        tick,
                        unit,
                        e.get("phase").asInt(),
                        e.get("instigator").asText(null),
                        e.get("area_effect").asText(),
                        e.get("x").asInt(),
                        e.get("y").asInt());
            case "wind_update" ->
                "%d wind_update %s %s %b"
                    .formatted(
                        tick, unit, e.get("outcome").asText(), e.get("finished").asBoolean());
            case "wind_retrigger" ->
                "%d wind_retrigger %s %d %s %s"
                    .formatted(
                        tick,
                        unit,
                        e.get("phase").asInt(),
                        e.get("area_effect").asText(),
                        e.get("countdown").isNull() ? null : e.get("countdown").get(0).asText());
            case "wind_left" ->
                "%d wind_left %s %s".formatted(tick, unit, e.get("area_effect").asText());
            case "wind_release" ->
                "%d wind_release %s %s %s %d %d"
                    .formatted(
                        tick,
                        unit,
                        e.get("why").asText(),
                        e.get("area_effect").asText(),
                        e.get("countdown").get(0).asInt(),
                        e.get("countdown").get(1).asInt());
            case "wind_collect" ->
                "%d wind_collect %s %d %d %d %s"
                    .formatted(
                        tick,
                        e.get("area_effect").asText(),
                        e.get("x").asInt(),
                        e.get("y").asInt(),
                        e.get("countdown").asInt(),
                        names(e.get("found")));
            case "filter_by_enemy" ->
                "%d filter_by_enemy %s %s %d %s"
                    .formatted(
                        tick,
                        unit,
                        e.get("instigator").asText(),
                        e.get("same_team").asInt(),
                        e.get("action").asText(null));
            case "alive_timer" ->
                "%d alive_timer %s %s"
                    .formatted(tick, e.get("area_effect").asText(), e.get("action").asText());
            default -> throw new IllegalStateException("unknown uppercut_wind event " + e);
          });
    }
    return expected;
  }

  /** The reference's broken shields' schedules and charge resets, in its log's order. */
  private static List<String> expectedShieldLostLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode e : reference.path("shield_lost")) {
      int tick = e.get("tick").asInt();
      switch (e.get("event").asText()) {
        case "scheduled" ->
            expected.add(
                "%d scheduled %s %s %s %b"
                    .formatted(
                        tick,
                        e.get("unit").asText(),
                        e.get("action").asText(),
                        e.get("by").isNull() ? null : e.get("by").asText(),
                        e.get("in_pending").asBoolean()));
        case "charge_reset" ->
            expected.add(
                "%d charge_reset %s %d %d"
                    .formatted(
                        tick,
                        e.get("unit").asText(),
                        e.get("charge").get(0).asInt(),
                        e.get("charge").get(1).asInt()));
        case "run" -> {
          // Each run is held by the action runs.
        }
        default -> throw new IllegalStateException("unknown shield_lost event " + e);
      }
    }
    return expected;
  }

  /** The reference's BuffAfterHits applies and buff hooks, in its log's order. */
  private static List<String> expectedBuffAfterHitsLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode e : reference.path("buff_after_hits")) {
      int tick = e.get("tick").asInt();
      switch (e.get("event").asText()) {
        case "buff_after_hits" ->
            expected.add(
                "%d buff_after_hits %s %s %d %d %s %d"
                    .formatted(
                        tick,
                        e.get("unit").asText(),
                        e.get("target").asText(),
                        e.get("counter").get(0).asInt(),
                        e.get("counter").get(1).asInt(),
                        e.get("buff").asText(),
                        e.get("time").asInt()));
        case "buff_hook" ->
            expected.add(
                "%d buff_hook %s %s %b"
                    .formatted(
                        tick,
                        e.get("unit").asText(),
                        e.get("action").asText(),
                        e.get("in_pending").asBoolean()));
        case "ghost_evo_start" ->
            expected.add(
                "%d ghost_evo_start %s %s %d"
                    .formatted(
                        tick,
                        e.get("owner").asText(),
                        e.get("action").asText(),
                        e.get("phase").asInt()));
        case "area" ->
            expected.add(
                "%d area %s %s %s %d %d %s %d"
                    .formatted(
                        tick,
                        e.get("owner").asText(),
                        e.get("row").asText(),
                        e.get("area_effect").asText(),
                        e.get("x").asInt(),
                        e.get("y").asInt(),
                        e.get("target").isNull() ? null : e.get("target").asText(),
                        e.get("level").asInt()));
        case "summon" ->
            expected.add(
                "%d summon %s %s %d %d %d"
                    .formatted(
                        tick,
                        e.get("owner").asText(),
                        e.get("reference").asText(),
                        e.get("point").get(0).asInt(),
                        e.get("point").get(1).asInt(),
                        e.get("countdown").asInt()));
        case "summoned" ->
            expected.add(
                "%d summoned %s %s %d %d %d %d %d %d %s %d %d"
                    .formatted(
                        tick,
                        e.get("area").asText(),
                        e.get("unit").asText(),
                        e.get("id").asInt(),
                        e.get("x").asInt(),
                        e.get("y").asInt(),
                        e.get("state").asInt(),
                        e.get("deploy").asInt(),
                        e.get("level").asInt(),
                        e.get("ref").isNull() ? null : e.get("ref").asText(),
                        e.get("dir").get(0).asInt(),
                        e.get("dir").get(1).asInt()));
        case "summon_removed" ->
            expected.add(
                "%d summon_removed %s %s"
                    .formatted(tick, e.get("owner").asText(), e.get("action").asText()));
        // A group's gate shows in the runs it lets through, listed with every run.
        default -> {}
      }
    }
    return expected;
  }

  /**
   * Logs every killer's killed-done check as it is scheduled, with what it killed and whether a
   * pending pass ran, and every check of what caused an action, with the cause's row and what the
   * check scheduled.
   */
  private static WorldObserver killLog(int[] currentTick, List<String> log) {
    return new WorldObserver() {
      @Override
      public void killedDoneScheduled(
          int tick, WorldEntity killer, WorldEntity killed, String action, boolean inPendingPass) {
        log.add(
            "%d killed_done %s %s %s %s"
                .formatted(currentTick[0], killer.name(), killed.name(), action, inPendingPass));
      }

      @Override
      public void instigatorChecked(
          int tick, WorldEntity owner, String action, ActionOwner instigator, String scheduled) {
        log.add(
            "%d instigator_match %s %s %s %s %s"
                .formatted(
                    currentTick[0],
                    owner.name(),
                    action,
                    instigator instanceof BattleEntity cause ? attackerName(cause) : null,
                    instigator == null ? null : instigator.actionRowName(),
                    scheduled));
      }
    };
  }

  /**
   * Logs every laser ball's start, with its pass and timer, every fire, with the count, the index
   * it picked, the targets, the action and the timer before and after, and every area effect's
   * life-end action as it is scheduled.
   */
  private static WorldObserver laserLog(int[] currentTick, List<String> log) {
    return new WorldObserver() {
      @Override
      public void laserStarted(
          int tick, AreaEffectEntity areaEffect, String action, int phase, int timerMs) {
        log.add(
            "%d start %s %s phase %d timer %d"
                .formatted(currentTick[0], areaEffect.name(), action, phase, timerMs));
      }

      @Override
      public void laserFired(
          int tick,
          AreaEffectEntity areaEffect,
          int count,
          int index,
          List<WorldEntity> targets,
          String action,
          int timerBefore,
          int timerAfter) {
        log.add(
            "%d fire %s count %d index %d targets %s action %s timer %d %d"
                .formatted(
                    currentTick[0],
                    areaEffect.name(),
                    count,
                    index,
                    targets.stream().map(WorldEntity::name).toList(),
                    action,
                    timerBefore,
                    timerAfter));
      }

      @Override
      public void lifeTimeEndScheduled(int tick, AreaEffectEntity areaEffect, String action) {
        log.add("%d lifetime_end %s %s".formatted(currentTick[0], areaEffect.name(), action));
      }
    };
  }

  /**
   * The reference's laser ball starts and fires and life-end actions, in the laser log's layout.
   */
  private static List<String> expectedLaserLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode l : reference.path("laser")) {
      int tick = l.get("tick").asInt();
      switch (l.get("event").asText()) {
        case "start" ->
            expected.add(
                "%d start %s %s phase %d timer %d"
                    .formatted(
                        tick,
                        l.get("owner").asText(),
                        l.get("action").asText(),
                        l.get("phase").asInt(),
                        l.get("timer").asInt()));
        case "fire" -> {
          List<String> targets = new ArrayList<>();
          l.get("targets").forEach(t -> targets.add(t.asText()));
          expected.add(
              "%d fire %s count %d index %d targets %s action %s timer %d %d"
                  .formatted(
                      tick,
                      l.get("owner").asText(),
                      l.get("count").asInt(),
                      l.get("index").asInt(),
                      targets,
                      l.get("action").isNull() ? null : l.get("action").asText(),
                      l.get("timer").get(0).asInt(),
                      l.get("timer").get(1).asInt()));
        }
        case "lifetime_end" ->
            expected.add(
                "%d lifetime_end %s %s"
                    .formatted(tick, l.get("area_effect").asText(), l.get("action").asText()));
        default -> throw new IllegalArgumentException("unknown laser event " + l);
      }
    }
    return expected;
  }

  /**
   * Logs every shape selector's start with its due ticks, every step that queried or finished, with
   * what it found, the scores, the picks, the entries that picked nobody and the picks and due
   * ticks before and after, and every air-to-ground run's start, phase change, finish and
   * re-trigger, with its pushes. Objects are named as they stand.
   */
  private static WorldObserver vinesLog(
      Standard1v1Battle match, int[] currentTick, List<String> log) {
    return new WorldObserver() {
      private String named(int id) {
        return ((WorldEntity) match.getWorld().liveObject(id)).name();
      }

      @Override
      public void selectorStarted(
          int tick, AreaEffectEntity areaEffect, String action, int phase, List<Integer> due) {
        log.add(
            "%d selector_start %s %s phase %d due %s"
                .formatted(currentTick[0], areaEffect.name(), action, phase, due));
      }

      @Override
      public void selectorStepped(
          int tick, AreaEffectEntity areaEffect, String action, ShapeSelector.Step step) {
        log.add(
            "%d selector_update %s found %s scores %s chosen %s empty %s none %s finished %s hit %s"
                    .formatted(
                        currentTick[0],
                        areaEffect.name(),
                        step.found().stream().map(this::named).toList(),
                        step.scores().stream().map(p -> named(p[0]) + "=" + p[1]).toList(),
                        step.chosen().stream().map(p -> p[0] + "=" + named(p[1])).toList(),
                        step.empty(),
                        step.none(),
                        step.finished(),
                        step.hit().stream().map(this::named).toList())
                + " before due %s hit %s".formatted(step.dueBefore(), step.hitBefore()));
      }

      @Override
      public void airToGroundStarted(
          int tick,
          WorldEntity unit,
          String action,
          int phase,
          int runPhase,
          int counter,
          int height) {
        log.add(
            "%d air_start %s %s phase %d counter %d height %d"
                .formatted(currentTick[0], unit.name(), action, runPhase, counter, height));
      }

      @Override
      public void airToGroundStepped(
          int tick,
          WorldEntity unit,
          int phaseBefore,
          int phaseAfter,
          int counterBefore,
          int counterAfter,
          boolean done,
          List<Integer> pushes) {
        log.add(
            "%d air_phase %s phase %d %d counter %d %d done %s pushes %s"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    phaseBefore,
                    phaseAfter,
                    counterBefore,
                    counterAfter,
                    done,
                    pushes));
      }

      @Override
      public void airToGroundRetriggered(
          int tick, WorldEntity unit, String action, int phase, int counter) {
        log.add(
            "%d air_retrigger %s %s phase %d counter %d"
                .formatted(currentTick[0], unit.name(), action, phase, counter));
      }
    };
  }

  /** The reference's shape selectors and air-to-ground runs, in the selectors' log layout. */
  private static List<String> expectedVinesLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode v : reference.path("vines")) {
      int tick = v.get("tick").asInt();
      switch (v.get("event").asText()) {
        case "selector_start" ->
            expected.add(
                "%d selector_start %s %s phase %d due %s"
                    .formatted(
                        tick,
                        v.get("owner").asText(),
                        v.get("action").asText(),
                        v.get("phase").asInt(),
                        ints(v.get("due"))));
        case "selector_update" -> {
          List<String> scores = new ArrayList<>();
          v.get("scores").forEach(p -> scores.add(p.get(0).asText() + "=" + p.get(1).asInt()));
          List<String> chosen = new ArrayList<>();
          v.get("chosen").forEach(p -> chosen.add(p.get(0).asInt() + "=" + p.get(1).asText()));
          expected.add(
              "%d selector_update %s found %s scores %s chosen %s empty %s none %s finished %s hit %s"
                      .formatted(
                          tick,
                          v.get("owner").asText(),
                          texts(v.get("found")),
                          scores,
                          chosen,
                          v.get("empty").asBoolean(),
                          ints(v.get("none")),
                          v.get("finished").asBoolean(),
                          texts(v.get("hit")))
                  + " before due %s hit %s"
                      .formatted(
                          ints(v.get("before").get("due")), ints(v.get("before").get("hit"))));
        }
        case "selector_removed" ->
            expected.add(
                "%d selector_removed %s %s"
                    .formatted(tick, v.get("owner").asText(), v.get("action").asText()));
        case "air_start" ->
            expected.add(
                "%d air_start %s %s phase %d counter %d height %d"
                    .formatted(
                        tick,
                        v.get("unit").asText(),
                        v.get("action").asText(),
                        v.get("phase").asInt(),
                        v.get("counter").asInt(),
                        v.get("height").asInt()));
        case "air_phase" ->
            expected.add(
                "%d air_phase %s phase %d %d counter %d %d done %s pushes %s"
                    .formatted(
                        tick,
                        v.get("unit").asText(),
                        v.get("phase").get(0).asInt(),
                        v.get("phase").get(1).asInt(),
                        v.get("counter").get(0).asInt(),
                        v.get("counter").get(1).asInt(),
                        v.get("done").asBoolean(),
                        ints(v.get("pushes"))));
        case "air_retrigger" ->
            expected.add(
                "%d air_retrigger %s %s phase %d counter %d"
                    .formatted(
                        tick,
                        v.get("unit").asText(),
                        v.get("action").asText(),
                        v.get("phase").asInt(),
                        v.get("counter").asInt()));
        default -> throw new IllegalArgumentException("unknown vines event " + v);
      }
    }
    return expected;
  }

  /**
   * Logs every target indicator attack's start, every object its finder found with what its query
   * listed, every signal and shot, every signal ended, every step that did more than ask the finder
   * for nobody or end no attack, with its fields before and after and its calls, and every stop.
   * Objects are named as they stand, or as they wait to be admitted.
   */
  private static WorldObserver indicatorLog(
      Standard1v1Battle match, int[] currentTick, List<String> log) {
    return new WorldObserver() {
      private String named(int id) {
        BattleEntity found = match.getWorld().liveObject(id);
        if (found == null) {
          found =
              match.getBattle().getHolder().queued().stream()
                  .filter(e -> e.getId() == id)
                  .findFirst()
                  .orElseThrow();
        }
        if (found instanceof ProjectileEntity p) {
          return p.name();
        }
        return found instanceof AreaEffectEntity a ? a.name() : ((WorldEntity) found).name();
      }

      @Override
      public void targetIndicatorLogged(
          int tick, CharacterEntity unit, TargetIndicatorAttack.Event event) {
        String owner = unit.name();
        int now = currentTick[0];
        if (event instanceof TargetIndicatorAttack.Started e) {
          log.add("%d start %s %s %d".formatted(now, owner, e.action(), e.phase()));
        } else if (event instanceof TargetIndicatorAttack.Found e) {
          log.add(
              "%d find %s %s %s"
                  .formatted(
                      now, owner, e.listed().stream().map(this::named).toList(), named(e.found())));
        } else if (event instanceof TargetIndicatorAttack.Signalled e) {
          log.add(
              "%d signal %s %s %s %d at %d %d level %d"
                  .formatted(
                      now,
                      owner,
                      named(e.target()),
                      named(e.signal()),
                      e.signal(),
                      e.x(),
                      e.y(),
                      e.packedLevel()));
        } else if (event instanceof TargetIndicatorAttack.Shot e) {
          log.add(
              "%d shoot %s %s %s at %d %d %d aim %d %d level %d facing %d %d"
                  .formatted(
                      now,
                      owner,
                      named(e.projectile()),
                      named(e.signal()),
                      e.x(),
                      e.y(),
                      e.z(),
                      e.aimX(),
                      e.aimY(),
                      e.packedLevel(),
                      e.facingX(),
                      e.facingY()));
        } else if (event instanceof TargetIndicatorAttack.SignalEnded e) {
          log.add("%d signal_ended %s %s".formatted(now, owner, named(e.signal())));
        } else if (event instanceof TargetIndicatorAttack.Stepped e) {
          log.add(
              "%d step %s %s calls %s %s"
                  .formatted(now, owner, fields(e.before()), e.calls(), fields(e.after())));
        } else if (event instanceof TargetIndicatorAttack.Stopped e) {
          log.add("%d stop %s calls %s %s".formatted(now, owner, e.calls(), fields(e.after())));
        }
      }

      private String fields(TargetIndicatorAttack.Fields f) {
        return indicatorFields(
            f.loadMs(),
            f.cooldownMs(),
            f.timesMs(),
            f.signals(),
            f.projectiles(),
            f.stopTags() ? 1 : 0);
      }
    };
  }

  /** A target indicator attack's fields, in its log's layout. */
  private static String indicatorFields(
      int load,
      int cooldown,
      List<Integer> times,
      List<Integer> signals,
      List<Integer> projectiles,
      int tags) {
    return "load %d cooldown %d times %s signals %s projectiles %s tags %d"
        .formatted(load, cooldown, times, signals, projectiles, tags);
  }

  /** The reference's target indicator attacks, in their log's layout. */
  private static List<String> expectedIndicatorLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode v : reference.path("target_indicator_attack")) {
      int tick = v.get("tick").asInt();
      String owner = v.get("owner").asText();
      switch (v.get("event").asText()) {
        case "start" ->
            expected.add(
                "%d start %s %s %d"
                    .formatted(tick, owner, v.get("action").asText(), v.get("phase").asInt()));
        case "find" ->
            expected.add(
                "%d find %s %s %s"
                    .formatted(tick, owner, texts(v.get("listed")), v.get("found").asText()));
        case "signal" ->
            expected.add(
                "%d signal %s %s %s %d at %d %d level %d"
                    .formatted(
                        tick,
                        owner,
                        v.get("target").asText(),
                        v.get("area_effect").asText(),
                        v.get("id").asInt(),
                        v.get("x").asInt(),
                        v.get("y").asInt(),
                        v.get("level").asInt()));
        case "shoot" ->
            expected.add(
                "%d shoot %s %s %s at %d %d %d aim %d %d level %d facing %d %d"
                    .formatted(
                        tick,
                        owner,
                        v.get("projectile").asText(),
                        v.get("signal").asText(),
                        v.get("start").get(0).asInt(),
                        v.get("start").get(1).asInt(),
                        v.get("start").get(2).asInt(),
                        v.get("aim").get(0).asInt(),
                        v.get("aim").get(1).asInt(),
                        v.get("level").asInt(),
                        v.get("facing").get(0).asInt(),
                        v.get("facing").get(1).asInt()));
        case "signal_ended" ->
            expected.add(
                "%d signal_ended %s %s".formatted(tick, owner, v.get("area_effect").asText()));
        case "step" ->
            expected.add(
                "%d step %s %s calls %s %s"
                    .formatted(
                        tick,
                        owner,
                        indicatorFields(v.get("before")),
                        indicatorCalls(v.get("calls")),
                        indicatorFields(v.get("after"))));
        case "stop" ->
            expected.add(
                "%d stop %s calls %s %s"
                    .formatted(
                        tick,
                        owner,
                        indicatorCalls(v.get("calls")),
                        indicatorFields(v.get("after"))));
        default -> throw new IllegalArgumentException("unknown target indicator event " + v);
      }
    }
    return expected;
  }

  /** The reference's fields of a target indicator attack, in its log's layout. */
  private static String indicatorFields(JsonNode f) {
    return indicatorFields(
        f.get("load").asInt(),
        f.get("cooldown").asInt(),
        ints(f.get("times")),
        ints(f.get("signals")),
        ints(f.get("projectiles")),
        f.get("tags").asInt());
  }

  /** The reference's calls of a target indicator attack, each its parts joined by spaces. */
  private static List<String> indicatorCalls(JsonNode calls) {
    List<String> out = new ArrayList<>();
    for (JsonNode call : calls) {
      List<String> parts = new ArrayList<>();
      call.forEach(part -> parts.add(part.isNull() ? "null" : part.asText()));
      out.add(String.join(" ", parts));
    }
    return out;
  }

  private static List<Integer> ints(JsonNode values) {
    List<Integer> out = new ArrayList<>();
    values.forEach(value -> out.add(value.asInt()));
    return out;
  }

  private static List<String> texts(JsonNode values) {
    List<String> out = new ArrayList<>();
    values.forEach(value -> out.add(value.asText()));
    return out;
  }

  /** The reference's Berserker index toggles, in the Berserker log's layout. */
  private static List<String> expectedBerserkLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode b : reference.path("berserk")) {
      expected.add(
          "%d berserk %s %s %d %d"
              .formatted(
                  b.get("tick").asInt(),
                  b.get("event").asText(),
                  b.get("owner").asText(),
                  b.get("before").asInt(),
                  b.get("index").asInt()));
    }
    return expected;
  }

  /**
   * Logs every link of a card's group chain and every unlink, and every start, connection, death
   * area made and death area ended of Goblinstein's ability.
   */
  /**
   * Logs every guard a guard spawn made, as its registration visit left it, the run's start, and
   * every step of its two runs: the first's, which finishes it, and the guard's, with whether it is
   * charging, its tags, whether it is done and what it did.
   */
  private static WorldObserver guardLog(int[] currentTick, List<String> log) {
    return new WorldObserver() {
      @Override
      public void guardRegistered(int tick, CharacterEntity guard) {
        TargetView reference = guard.getUnit().targeting().getReference();
        log.add(
            "%d guard %s %d at %d %d state %d hp %d ref %s"
                .formatted(
                    currentTick[0],
                    guard.name(),
                    guard.getId(),
                    guard.getView().getX(),
                    guard.getView().getY(),
                    guard.getView().getState(),
                    guard.getHitPoints().getHitPoints(),
                    reference == null ? null : reference.name()));
      }

      @Override
      public void guardStarted(
          int tick,
          AreaEffectEntity areaEffect,
          String action,
          int phase,
          CharacterEntity guard,
          int x,
          int y,
          int toX,
          int toY) {
        // The maker faces the guard toward the area effect's point, after its registration visit;
        // no record shows the facing, and the charge faces it anew.
        int[] facing = {
          areaEffect.getX() - guard.getView().getX(), areaEffect.getY() - guard.getView().getY()
        };
        FixedMath.normalize(facing, MovementState.DIRECTION_SCALE);
        assertThat(new int[] {guard.getView().getDirX(), guard.getView().getDirY()})
            .as("%d: %s faces the area effect's point", currentTick[0], guard.name())
            .containsExactly(facing);
        log.add(
            "%d start %s %s %d guard %s relocate %d %d to %d %d"
                .formatted(
                    currentTick[0],
                    areaEffect.name(),
                    action,
                    phase,
                    guard.name(),
                    x,
                    y,
                    toX,
                    toY));
      }

      @Override
      public void guardFirstStepped(int tick, AreaEffectEntity areaEffect, String action) {
        log.add("%d update %s first finish".formatted(currentTick[0], areaEffect.name()));
      }

      @Override
      public void guardStepped(
          int tick,
          CharacterEntity guard,
          boolean charging,
          long tags,
          boolean done,
          List<String> calls) {
        log.add(
            "%d update %s charging %b tags %s done %b calls %s"
                .formatted(currentTick[0], guard.name(), charging, tagNames(tags), done, calls));
      }
    };
  }

  /**
   * Logs every start of a Boss Bandit ability's run, with its warp and lock ticks and the answers
   * to its asks for the lock; every step that changed the run or asked again, with the unit's
   * state; and every warp, with where the unit stood and landed, the reference it dropped and its
   * state.
   */
  private static WorldObserver warpLog(int[] currentTick, List<String> log) {
    return new WorldObserver() {
      @Override
      public void bossBanditAbilityStarted(
          int tick,
          CharacterEntity unit,
          String action,
          int phase,
          int warpTick,
          int lockTick,
          List<Boolean> requests) {
        log.add(
            "%d start %s %s %d warp %d lock %d requests %s"
                .formatted(
                    currentTick[0], unit.name(), action, phase, warpTick, lockTick, requests));
      }

      @Override
      public void bossBanditAbilityStepped(
          int tick, CharacterEntity unit, boolean locked, int releaseMs, List<String> calls) {
        log.add(
            "%d step %s locked %b release %d state %d calls %s"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    locked,
                    releaseMs,
                    unit.getView().getState(),
                    calls));
      }

      @Override
      public void warped(
          int tick,
          CharacterEntity unit,
          String action,
          int phase,
          int fromX,
          int fromY,
          String referenceBefore) {
        log.add(
            "%d warp %s %s %d from %d %d to %d %d ref %s state %d"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    action,
                    phase,
                    fromX,
                    fromY,
                    unit.getView().getX(),
                    unit.getView().getY(),
                    referenceBefore,
                    unit.getView().getState()));
      }
    };
  }

  /**
   * The reference's Boss Bandit ability log, in the warp log's layout. A warp that dropped a
   * projectile's target is refused by the battle, so the reference must list none.
   */
  private static List<String> expectedWarpLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode b : reference.path("boss_bandit_ability")) {
      int tick = b.get("tick").asInt();
      switch (b.get("event").asText()) {
        case "ability_start" -> {
          List<Boolean> requests = new ArrayList<>();
          b.get("lock_requests").forEach(r -> requests.add(r.asInt() == 1));
          expected.add(
              "%d start %s %s %d warp %d lock %d requests %s"
                  .formatted(
                      tick,
                      b.get("owner").asText(),
                      b.get("action").asText(),
                      b.get("phase").asInt(),
                      b.get("warp_tick").asInt(),
                      b.get("lock_tick").asInt(),
                      requests));
        }
        case "ability_step" -> {
          List<String> calls = new ArrayList<>();
          if (b.has("claim")) {
            calls.add("claim " + (b.get("claim").asInt() == 1));
          }
          if (b.has("request")) {
            calls.add("request " + (b.get("request").asInt() == 1));
          }
          if (b.has("warp_action")) {
            calls.add("warp " + b.get("warp_action").asText());
          }
          if (b.path("finished").asBoolean(false)) {
            calls.add("finish");
          }
          expected.add(
              "%d step %s locked %b release %d state %d calls %s"
                  .formatted(
                      tick,
                      b.get("owner").asText(),
                      b.get("locked").asInt() == 1,
                      b.get("release").asInt(),
                      b.get("state").asInt(),
                      calls));
        }
        case "warp" -> {
          assertThat(b.get("dropped")).as("%d: the projectiles the warp dropped", tick).isEmpty();
          expected.add(
              "%d warp %s %s %d from %d %d to %d %d ref %s state %d"
                  .formatted(
                      tick,
                      b.get("owner").asText(),
                      b.get("action").asText(),
                      b.get("phase").asInt(),
                      b.get("start").get(0).asInt(),
                      b.get("start").get(1).asInt(),
                      b.get("to").get(0).asInt(),
                      b.get("to").get(1).asInt(),
                      b.get("ref_before").isNull() ? null : b.get("ref_before").asText(),
                      b.get("state").asInt()));
        }
        default -> throw new IllegalArgumentException("unknown Boss Bandit ability event " + b);
      }
    }
    return expected;
  }

  /**
   * The ticks each Boss Bandit ability's run holds its lock as the post-hooks end: granted by the
   * post-pass of the tick it starts in, so from the next tick, and dropped by the pre-pass after
   * the tick it finishes in, so up to that tick.
   */
  private static List<String> expectedSelfLocks(JsonNode reference) {
    Map<String, Integer> started = new HashMap<>();
    List<String> expected = new ArrayList<>();
    for (JsonNode b : reference.path("boss_bandit_ability")) {
      String owner = b.get("owner").asText();
      if (b.get("event").asText().equals("ability_start")) {
        started.put(owner, b.get("tick").asInt());
      } else if (b.path("finished").asBoolean(false)) {
        for (int t = started.remove(owner) + 1; t <= b.get("tick").asInt(); t++) {
          expected.add(t + " " + owner);
        }
      }
    }
    assertThat(started).as("a run that never finished").isEmpty();
    return expected;
  }

  /** The names of the game tags set in a word, in the order of their names. */
  private static List<String> tagNames(long tags) {
    List<String> names = new ArrayList<>();
    for (GameRow row : GameData.tables().table("game_tags").rows()) {
      if (row.index() < Long.SIZE && (tags & (1L << row.index())) != 0) {
        names.add(row.name());
      }
    }
    return names.stream().sorted().toList();
  }

  /**
   * The reference's guards and guard spawn steps, in the guard log's layout. The duration run's
   * start is held by the run log and its finish by the unit's walk; a run's removal by the holder's
   * own pass. The presentation the hit hands its view is not modelled.
   */
  private static List<String> expectedGuardLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode g : reference.path("little_prince_ability")) {
      int tick = g.get("tick").asInt();
      switch (g.get("event").asText()) {
        case "duration_start", "duration_finished", "removed" -> {}
        case "guard" ->
            expected.add(
                "%d guard %s %d at %d %d state %d hp %d ref %s"
                    .formatted(
                        tick,
                        g.get("unit").asText(),
                        g.get("id").asInt(),
                        g.get("x").asInt(),
                        g.get("y").asInt(),
                        g.get("state").asInt(),
                        g.get("hp").asInt(),
                        g.get("ref").isNull() ? null : g.get("ref").asText()));
        case "start" -> {
          JsonNode relocate = g.get("calls").get(0);
          expected.add(
              "%d start %s %s %d guard %s relocate %d %d to %d %d"
                  .formatted(
                      tick,
                      g.get("owner").asText(),
                      g.get("action").asText(),
                      g.get("phase").asInt(),
                      g.get("guard").asText(),
                      relocate.get(1).asInt(),
                      relocate.get(2).asInt(),
                      relocate.get(3).asInt(),
                      relocate.get(4).asInt()));
        }
        case "update" -> {
          if (g.get("child").asInt() == 0) {
            expected.add("%d update %s first finish".formatted(tick, g.get("owner").asText()));
            continue;
          }
          List<String> calls = new ArrayList<>();
          for (JsonNode c : g.get("calls")) {
            String kind = c.get(0).asText();
            switch (kind) {
              case "query" -> {
                List<String> found = new ArrayList<>();
                c.get(1).forEach(n -> found.add(n.asText()));
                calls.add("query " + String.join(",", found) + " " + c.get(2).asInt());
              }
              case "push" ->
                  calls.add("push " + c.get(1).asText() + " " + (c.get(2).asBoolean() ? 1 : 0));
              case "presentation_f0" -> {}
              case "hit" -> calls.add("hit " + c.get(1).asText() + " " + c.get(2).asInt());
              case "untouchable" -> calls.add("untouchable " + c.get(1).asText());
              case "deploy_cut" -> calls.add("deploy_cut");
              case "charge" -> calls.add("charge " + c.get(1).asInt() + " " + c.get(2).asInt());
              case "finish" -> calls.add("finish " + c.get(1).asText());
              default -> throw new IllegalArgumentException("unknown guard step call " + c);
            }
          }
          List<String> tags = new ArrayList<>();
          g.get("tags").forEach(t -> tags.add(t.asText()));
          expected.add(
              "%d update %s charging %b tags %s done %b calls %s"
                  .formatted(
                      tick,
                      g.get("owner").asText(),
                      g.get("charging").asInt() == 1,
                      tags.stream().sorted().toList(),
                      g.get("done").asInt() == 1,
                      calls));
        }
        default -> throw new IllegalArgumentException("unknown guard event " + g);
      }
    }
    return expected;
  }

  private static WorldObserver goblinsteinLog(int[] currentTick, List<String> log) {
    return new WorldObserver() {
      @Override
      public void chainLinked(int tick, CharacterEntity unit, CharacterEntity after) {
        log.add(
            "%d group_link %s %s"
                .formatted(currentTick[0], unit.name(), after == null ? null : after.name()));
      }

      @Override
      public void chainUnlinked(int tick, CharacterEntity unit) {
        log.add("%d group_unlink %s".formatted(currentTick[0], unit.name()));
      }

      @Override
      public void goblinsteinStarted(int tick, AreaEffectEntity owner, String action, int phase) {
        log.add("%d start %s %s %d".formatted(currentTick[0], owner.name(), action, phase));
      }

      @Override
      public void goblinsteinConnected(int tick, AreaEffectEntity owner, BattleEntity connected) {
        String name =
            connected == null
                ? null
                : connected instanceof WorldEntity w
                    ? w.name()
                    : ((AreaEffectEntity) connected).name();
        log.add("%d connect %s %s".formatted(currentTick[0], owner.name(), name));
      }

      @Override
      public void goblinsteinDeathAreaMade(
          int tick,
          AreaEffectEntity owner,
          WorldEntity left,
          AreaEffectEntity deathArea,
          int x,
          int y) {
        log.add(
            "%d death_area %s %s %s %d %d %d %d %d %d"
                .formatted(
                    currentTick[0],
                    owner.name(),
                    left.name(),
                    deathArea.name(),
                    deathArea.getId(),
                    x,
                    y,
                    deathArea.side(),
                    deathArea.getPackedLevel(),
                    deathArea.getCountdown()));
      }

      @Override
      public void goblinsteinDeathAreaEnded(
          int tick, AreaEffectEntity owner, AreaEffectEntity deathArea) {
        log.add(
            "%d death_area_ended %s %s".formatted(currentTick[0], owner.name(), deathArea.name()));
      }

      @Override
      public void goblinsteinStepped(int tick, AreaEffectEntity owner, String step) {
        log.add("%d %s %s".formatted(currentTick[0], step, owner.name()));
      }
    };
  }

  /** Lists every buff a parent handed to its riders, in the reference's layout. */
  private static WorldObserver handOverLog(int[] currentTick, List<String> log) {
    return new WorldObserver() {
      @Override
      public void buffHandedOver(
          int tick,
          WorldEntity parent,
          WorldEntity rider,
          BuffData buff,
          int time,
          int packedLevel,
          SpawnHost source,
          List<BuffInstance> instances,
          Set<String> heldBefore) {
        log.add(
            "%d handed_over %s %s %s %d %d %s %s"
                .formatted(
                    currentTick[0],
                    parent.name(),
                    rider.name(),
                    buff.name(),
                    time,
                    packedLevel,
                    source == null ? null : source.name(),
                    instances.stream()
                        .map(
                            i ->
                                "%s %d %d %b"
                                    .formatted(
                                        i.getKey(),
                                        i.getRemaining(),
                                        i.getPackedLevel(),
                                        heldBefore.contains(i.getKey())))
                        .toList()));
      }
    };
  }

  /** The name of a tether's end: a unit's or an area effect's. */
  private static String endName(BattleEntity end) {
    return end instanceof WorldEntity w ? w.name() : ((AreaEffectEntity) end).name();
  }

  private static WorldObserver tetherLog(int[] currentTick, List<String> log) {
    // The elixir each listener has counted, by owner and row.
    Map<String, Integer> totals = new HashMap<>();
    return new WorldObserver() {
      @Override
      public void cardPlayHeard(
          int tick,
          WorldEntity owner,
          String action,
          int side,
          String played,
          String deployed,
          int total,
          String scheduled) {
        List<String> calls =
            scheduled == null ? List.of() : List.of("schedule " + owner.name() + " " + scheduled);
        Integer before = totals.put(owner.name() + " " + action, total);
        // A play that scheduled nothing and left the count as it was is marked as doing nothing.
        boolean idle = scheduled == null && total == (before == null ? 0 : before);
        log.add(
            "%d card_play_heard %s %s %d %s %s %d %s%s"
                .formatted(
                    currentTick[0],
                    owner.name(),
                    action,
                    side,
                    played,
                    deployed,
                    total,
                    calls,
                    idle ? " -" : ""));
      }

      @Override
      public void tetherActivated(
          int tick, AreaEffectEntity owner, BattleEntity target, String action) {
        log.add(
            "%d schedule %s %s %s"
                .formatted(currentTick[0], endName(target), owner.name(), action));
      }

      @Override
      public void tetherDamagePass(
          int tick,
          AreaEffectEntity owner,
          int ax,
          int ay,
          int bx,
          int by,
          List<WorldEntity> found) {
        log.add(
            "%d damage_pass %s %d %d %d %d %s"
                .formatted(
                    currentTick[0],
                    owner.name(),
                    ax,
                    ay,
                    bx,
                    by,
                    found == null ? null : found.stream().map(WorldEntity::name).toList()));
      }

      @Override
      public void tetherHit(
          int tick,
          AreaEffectEntity owner,
          WorldEntity target,
          int damage,
          int directionX,
          int directionY,
          DamageResult result) {
        log.add(
            "%d hit %s %s %d %d %d"
                .formatted(
                    currentTick[0], owner.name(), target.name(), damage, directionX, directionY));
      }

      @Override
      public void tetherHitAction(
          int tick, AreaEffectEntity owner, WorldEntity target, String action) {
        log.add(
            "%d hit_action %s %s %s"
                .formatted(currentTick[0], owner.name(), target.name(), action));
      }
    };
  }

  /** The reference's tether log, in the battle's layout. */
  private static List<String> expectedTetherLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode t : reference.path("tether")) {
      int tick = t.get("tick").asInt();
      switch (t.get("event").asText()) {
        case "schedule" ->
            expected.add(
                "%d schedule %s %s %s"
                    .formatted(
                        tick,
                        t.get("owner").asText(),
                        t.get("instigator").asText(),
                        t.get("action").asText()));
        case "damage_pass" -> {
          JsonNode segment = t.get("segment");
          List<String> found = null;
          if (!t.get("found").isNull()) {
            found = new ArrayList<>();
            for (JsonNode f : t.get("found")) {
              found.add(f.asText());
            }
          }
          expected.add(
              "%d damage_pass %s %d %d %d %d %s"
                  .formatted(
                      tick,
                      t.get("owner").asText(),
                      segment.get(0).get(0).asInt(),
                      segment.get(0).get(1).asInt(),
                      segment.get(1).get(0).asInt(),
                      segment.get(1).get(1).asInt(),
                      found));
        }
        case "hit" ->
            expected.add(
                "%d hit %s %s %d %d %d"
                    .formatted(
                        tick,
                        t.get("owner").asText(),
                        t.get("target").asText(),
                        t.get("damage").asInt(),
                        t.get("direction").get(0).asInt(),
                        t.get("direction").get(1).asInt()));
        case "hit_action" ->
            expected.add(
                "%d hit_action %s %s %s"
                    .formatted(
                        tick,
                        t.get("owner").asText(),
                        t.get("target").asText(),
                        t.get("action").asText()));
        case "card_play_heard" -> {
          List<String> calls = new ArrayList<>();
          for (JsonNode c : t.get("calls")) {
            calls.add(c.get(0).asText() + " " + c.get(1).asText() + " " + c.get(2).asText());
          }
          expected.add(
              "%d card_play_heard %s %s %d %s %s %d %s"
                  .formatted(
                      tick,
                      t.get("owner").asText(),
                      t.get("action").asText(),
                      t.get("side").asInt(),
                      t.get("card").asText(),
                      t.get("effective").asText(),
                      t.get("total").asInt(),
                      calls));
        }
        default -> throw new IllegalArgumentException("unknown tether event " + t);
      }
    }
    return expected;
  }

  /**
   * A request for a unit's ability that a run supplies in place of its player's command: started in
   * the unit's phase-2 pass, after the run pass, where the reference makes it.
   */
  private record SuppliedRequest(CharacterEntity unit) implements BattleAction {

    @Override
    public String name() {
      return "supplied ability request";
    }

    @Override
    public int phase() {
      return EntityActions.PHASE_POST_COMPONENT_TICK;
    }

    @Override
    public ActionInstance start(ActionHolder holder) {
      unit.requestAbility();
      return null;
    }
  }

  /**
   * Logs every ability dash (the query's point and radius, what it found, valid and how far, the
   * winner and the dash's aim), every stun cleanse, every dash a chained dasher started with its
   * count, hit list and first vector, every next target its chain found and every chain's end.
   */
  private static WorldObserver goldenKnightLog(int[] currentTick, List<String> log) {
    return new WorldObserver() {
      @Override
      public void abilityDashed(
          int tick,
          CharacterEntity unit,
          List<CharacterEntity.DashCandidate> candidates,
          List<String> cleansed,
          WorldEntity chosen) {
        List<List<Object>> found = new ArrayList<>();
        for (CharacterEntity.DashCandidate c : candidates) {
          found.add(List.of(c.entity().name(), c.valid() ? 1 : 0, c.squaredDistance()));
        }
        log.add(
            "%d ability_handler %s query %d %d %d found %s winner %s %d %d %d"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    unit.getView().getX(),
                    unit.getView().getY(),
                    unit.getData().ability().dashRange(),
                    found,
                    chosen.name(),
                    chosen.getView().getX(),
                    chosen.getView().getY(),
                    chosen.getView().getCollisionRadius()));
        log.add("%d stun_cleanse %s %s".formatted(currentTick[0], unit.name(), cleansed));
      }

      @Override
      public void chainDashStarted(
          int tick, CharacterEntity unit, int fromX, int fromY, int aimX, int aimY, int radius) {
        TargetingState t = unit.getUnit().targeting();
        log.add(
            "%d dash_start %s %s count %d hit %s first %d %d aim %d %d radius %d at %d %d route %s"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    t.getReference().getEntity().getName(),
                    t.getDashChainCount(),
                    t.getHitTargetIds(),
                    t.getDashFirstX(),
                    t.getDashFirstY(),
                    aimX,
                    aimY,
                    radius,
                    fromX,
                    fromY,
                    Arrays.stream(unit.getUnit().movement().getRoute().toArray())
                        .boxed()
                        .toList()));
      }

      @Override
      public void chainDashed(
          int tick, CharacterEntity unit, WorldEntity next, int count, int x, int y) {
        log.add(
            "%d chain %s %s %d %d %d"
                .formatted(currentTick[0], unit.name(), next.name(), count, x, y));
      }

      @Override
      public void chainDashEnded(int tick, CharacterEntity unit, int count, TargetView reference) {
        log.add(
            "%d chain_end %s %d %s %d %d"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    count,
                    reference == null ? null : reference.getEntity().getName(),
                    unit.getView().getX(),
                    unit.getView().getY()));
      }
    };
  }

  /** The reference's chained dash log, in the battle's layout. */
  /**
   * Lists what the Skeleton King's ability did: each soul counted, the souls spent on its area
   * effect and the lifetime they bought, the order its spawner shuffled with the battle's random
   * state before and after, and each skeleton as its registration visit and its clone setter left
   * it, with the area effect's point and the random state after its draws.
   */
  private static WorldObserver skeletonKingLog(int[] currentTick, List<String> log) {
    return new WorldObserver() {
      @Override
      public void soulCounted(int tick, CharacterEntity unit, WorldEntity dying, int souls) {
        log.add(
            "%d soul %s %s side %d souls %d"
                .formatted(currentTick[0], unit.name(), dying.name(), dying.side(), souls));
      }

      @Override
      public void soulsSpent(
          int tick,
          CharacterEntity unit,
          AreaEffectEntity areaEffect,
          int souls,
          int count,
          int lifetimeMs) {
        log.add(
            "%d lifetime %s %d souls %d count %d"
                .formatted(currentTick[0], areaEffect.name(), lifetimeMs, souls, count));
      }

      @Override
      public void spawnOrdered(
          int tick, AreaEffectEntity areaEffect, int[] order, int stateBefore, int stateAfter) {
        log.add(
            "%d order %s %s state %d %d"
                .formatted(
                    currentTick[0],
                    areaEffect.name(),
                    Arrays.toString(order),
                    Integer.toUnsignedLong(stateBefore),
                    Integer.toUnsignedLong(stateAfter)));
      }

      @Override
      public void areaSpawned(
          int tick,
          AreaEffectEntity areaEffect,
          CharacterEntity child,
          int retries,
          int stateAfter) {
        log.add(
            "%d skeleton %s %s %d at %d %d state %d deploy %d hp %d level %d clone %d tries %d"
                    .formatted(
                        currentTick[0],
                        areaEffect.name(),
                        child.name(),
                        child.getId(),
                        child.getView().getX(),
                        child.getView().getY(),
                        child.getView().getState(),
                        child.getView().getDeployCountdown(),
                        child.getHitPoints().getHitPoints(),
                        child.getPackedLevel(),
                        child.isClone() ? 1 : 0,
                        retries + 1)
                + " area %d %d state %d"
                    .formatted(areaEffect.x(), areaEffect.y(), Integer.toUnsignedLong(stateAfter)));
      }
    };
  }

  private static List<String> expectedSkeletonKingLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode k : reference.path("skeleton_king_ability")) {
      int tick = k.get("tick").asInt();
      switch (k.get("event").asText()) {
        case "soul" ->
            expected.add(
                "%d soul %s %s side %d souls %d"
                    .formatted(
                        tick,
                        k.get("unit").asText(),
                        k.get("dying").asText(),
                        k.get("side").asInt(),
                        k.get("souls").asInt()));
        case "lifetime" ->
            expected.add(
                "%d lifetime %s %d souls %d count %d"
                    .formatted(
                        tick,
                        k.get("area_effect").asText(),
                        k.get("lifetime").asInt(),
                        k.get("souls").asInt(),
                        k.get("count").asInt()));
        case "order" ->
            expected.add(
                "%d order %s %s state %d %d"
                    .formatted(
                        tick,
                        k.get("area_effect").asText(),
                        ints(k.get("order")),
                        k.get("state").get(0).asLong(),
                        k.get("state").get(1).asLong()));
        case "skeleton" ->
            expected.add(
                "%d skeleton %s %s %d at %d %d state %d deploy %d hp %d level %d clone %d tries %d"
                        .formatted(
                            tick,
                            k.get("area_effect").asText(),
                            k.get("unit").asText(),
                            k.get("id").asInt(),
                            k.get("x").asInt(),
                            k.get("y").asInt(),
                            k.get("state").asInt(),
                            k.get("deploy").asInt(),
                            k.get("hp").asInt(),
                            k.get("level").asInt(),
                            k.get("clone").asInt(),
                            k.get("tries").asInt())
                    + " area %d %d state %d"
                        .formatted(
                            k.get("at").get(0).asInt(),
                            k.get("at").get(1).asInt(),
                            k.get("state_after").asLong()));
        default -> {
          // The area effect's creation is held by the area effects' own log.
        }
      }
    }
    return expected;
  }

  private static List<String> expectedGoldenKnightLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode g : reference.path("golden_knight")) {
      int tick = g.get("tick").asInt();
      String unit = g.get("unit").asText();
      switch (g.get("event").asText()) {
        case "supplied_request" -> expected.add(tick + " supplied_request " + unit);
        case "ability_handler" -> {
          List<List<Object>> found = new ArrayList<>();
          g.get("candidates")
              .forEach(
                  c -> found.add(List.of(c.get(0).asText(), c.get(1).asInt(), c.get(2).asInt())));
          Map<String, JsonNode> calls = new HashMap<>();
          g.get("calls").forEach(c -> calls.put(c.get(0).asText(), c));
          JsonNode query = calls.get("query");
          JsonNode dash = calls.get("dash_to");
          expected.add(
              "%d ability_handler %s query %d %d %d found %s winner %s %d %d %d"
                  .formatted(
                      tick,
                      unit,
                      query.get(1).asInt(),
                      query.get(2).asInt(),
                      query.get(3).asInt(),
                      found,
                      calls.get("set_target").get(1).asText(),
                      dash.get(1).asInt(),
                      dash.get(2).asInt(),
                      dash.get(3).asInt()));
        }
        case "stun_cleanse" -> {
          List<String> removed = new ArrayList<>();
          g.get("removed").forEach(r -> removed.add(r.asText()));
          expected.add("%d stun_cleanse %s %s".formatted(tick, unit, removed));
        }
        case "dash_start" -> {
          List<Integer> hit = new ArrayList<>();
          g.get("hitlist").forEach(h -> hit.add(h.asInt()));
          List<Integer> route = new ArrayList<>();
          g.get("route").forEach(r -> route.add(r.asInt()));
          expected.add(
              "%d dash_start %s %s count %d hit %s first %d %d aim %d %d radius %d at %d %d route %s"
                  .formatted(
                      tick,
                      unit,
                      g.get("ref").asText(),
                      g.get("count").asInt(),
                      hit,
                      g.get("first").get(0).asInt(),
                      g.get("first").get(1).asInt(),
                      g.get("aim").get(0).asInt(),
                      g.get("aim").get(1).asInt(),
                      g.get("w3").asInt(),
                      g.get("x").asInt(),
                      g.get("y").asInt(),
                      route));
        }
        case "chain" ->
            expected.add(
                "%d chain %s %s %d %d %d"
                    .formatted(
                        tick,
                        unit,
                        g.get("target").asText(),
                        g.get("count").asInt(),
                        g.get("x").asInt(),
                        g.get("y").asInt()));
        case "chain_end" ->
            expected.add(
                "%d chain_end %s %d %s %d %d"
                    .formatted(
                        tick,
                        unit,
                        g.get("count").asInt(),
                        g.get("ref").isNull() ? null : g.get("ref").asText(),
                        g.get("x").asInt(),
                        g.get("y").asInt()));
        default -> throw new IllegalArgumentException("a chained dash log entry " + g);
      }
    }
    return expected;
  }

  /**
   * Logs what the champion slots did - the deck pass, each follow of a play, each activation, each
   * cooldown's end and each refund - every ability's buff, and every ability command with what it
   * came to; and writes every slot step as the reference's trace row.
   */
  private static WorldObserver championLog(
      int[] currentTick, List<String> log, List<String> trace) {
    return new WorldObserver() {
      @Override
      public void championDeckPass(int tick, ChampionController slot) {
        // The pass runs as the match is set up, before the first step.
        log.add(
            "%d deck_pass %d %d %s %d %d %d"
                .formatted(
                    tick,
                    slot.side(),
                    slot.getSlot(),
                    slot.getChampion().name(),
                    slot.getDeckIndex(),
                    slot.getState(),
                    slot.getCooldownFullMs()));
      }

      @Override
      public void championFollowed(int tick, ChampionController slot, String play) {
        log.add(
            "%d follow %d %d %s %s %d %d %d %d"
                .formatted(
                    currentTick[0],
                    slot.side(),
                    slot.getSlot(),
                    slot.getChampion().name(),
                    play,
                    slot.getDeployIndex(),
                    slot.getCharges(),
                    slot.getCooldownMs(),
                    slot.getState()));
      }

      @Override
      public void championActivated(
          int tick, ChampionController slot, List<CharacterEntity> requested) {
        log.add(
            "%d activation %d %d %s %d %d %d %d %d"
                .formatted(
                    currentTick[0],
                    slot.side(),
                    slot.getSlot(),
                    requested.stream().map(CharacterEntity::name).toList(),
                    slot.getCooldownMs(),
                    slot.getCharges(),
                    slot.getTriggerMs(),
                    slot.getPaidMana(),
                    slot.getState()));
      }

      @Override
      public void championCooldownOut(int tick, ChampionController slot) {
        log.add(
            "%d cooldown_out %d %d %d"
                .formatted(currentTick[0], slot.side(), slot.getSlot(), slot.getState()));
      }

      @Override
      public void championRefunded(
          int tick, ChampionController slot, int mana, int elixirBefore, int elixirAfter) {
        log.add(
            "%d refund %d %d %d %d %d"
                .formatted(
                    currentTick[0], slot.side(), slot.getSlot(), mana, elixirBefore, elixirAfter));
      }

      @Override
      public void abilityBuffed(
          int tick, CharacterEntity unit, String buff, int timeMs, int packedLevel) {
        log.add(
            "%d ability_buff %s %s %d %d %d"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    buff,
                    timeMs,
                    packedLevel,
                    unit.getView().getState()));
      }

      @Override
      public void championStepped(
          int tick, ChampionController slot, int elixir, List<ChampionView> views) {
        List<Object> row = new ArrayList<>();
        row.add(currentTick[0]);
        row.add("step");
        row.add(slot.side());
        row.add(slot.getSlot());
        row.add(slot.getState());
        row.add(slot.getCooldownMs());
        row.add(slot.getCharges());
        row.add(slot.getTriggerMs());
        row.add(slot.getDeployIndex());
        row.add(slot.champions().stream().map(CharacterEntity::name).toList());
        row.add(elixir);
        List<List<Object>> seen = new ArrayList<>();
        for (ChampionView v : views) {
          seen.add(
              List.of(
                  v.name(),
                  v.row(),
                  v.deployIndex(),
                  v.state(),
                  v.pending() ? 1 : 0,
                  v.warning(),
                  v.tags(),
                  v.cloned() ? 1 : 0));
        }
        row.add(seen);
        trace.add(JSON.valueToTree(row).toString());
      }
    };
  }

  /**
   * The reference's champion log in the battle's layout. A command is held by the battle's own
   * record of it; the log's command entries are compared there.
   */
  private static List<String> expectedChampionLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode c : reference.path("champion").path("log")) {
      int tick = c.get("tick").asInt();
      switch (c.get("event").asText()) {
        case "deck_pass" ->
            expected.add(
                "%d deck_pass %d %d %s %d %d %d"
                    .formatted(
                        tick,
                        c.get("side").asInt(),
                        c.get("slot").asInt(),
                        c.get("champion").asText(),
                        c.get("deck_index").asInt(),
                        c.get("state").asInt(),
                        c.get("cooldown_full").asInt()));
        case "follow" ->
            expected.add(
                "%d follow %d %d %s %s %d %d %d %d"
                    .formatted(
                        tick,
                        c.get("side").asInt(),
                        c.get("slot").asInt(),
                        c.get("champion").asText(),
                        c.get("command").asText(),
                        c.get("deploy_index").asInt(),
                        c.get("charges").asInt(),
                        c.get("cooldown").asInt(),
                        c.get("state").asInt()));
        case "activation" -> {
          List<String> requests = new ArrayList<>();
          c.get("requests").forEach(r -> requests.add(r.asText()));
          expected.add(
              "%d activation %d %d %s %d %d %d %d %d"
                  .formatted(
                      tick,
                      c.get("side").asInt(),
                      c.get("slot").asInt(),
                      requests,
                      c.get("cooldown").asInt(),
                      c.get("charges").asInt(),
                      c.get("trigger").asInt(),
                      c.get("paid").asInt(),
                      c.get("state").asInt()));
        }
        case "cooldown_out" ->
            expected.add(
                "%d cooldown_out %d %d %d"
                    .formatted(
                        tick,
                        c.get("side").asInt(),
                        c.get("slot").asInt(),
                        c.get("state").asInt()));
        case "refund" ->
            expected.add(
                "%d refund %d %d %d %d %d"
                    .formatted(
                        tick,
                        c.get("side").asInt(),
                        c.get("slot").asInt(),
                        c.get("amount").asInt(),
                        c.get("elixir").get(0).asInt(),
                        c.get("elixir").get(1).asInt()));
        case "ability_buff" ->
            expected.add(
                "%d ability_buff %s %s %d %d %d"
                    .formatted(
                        tick,
                        c.get("unit").asText(),
                        c.get("buff").asText(),
                        c.get("time").asInt(),
                        c.get("level").asInt(),
                        c.get("state").asInt()));
        case "command" -> {}
        default -> throw new IllegalArgumentException("a champion log entry " + c);
      }
    }
    return expected;
  }

  /**
   * Every ability command: the tick it ran on, its code, the elixir before and after, and the units
   * it requested; and the code and elixir the reference's champion log gives it.
   */
  private static void assertAbilityUses(Standard1v1Battle match, JsonNode reference) {
    Map<String, Standard1v1Battle.AbilityUse> uses = new HashMap<>();
    for (Standard1v1Battle.AbilityUse use : match.getAbilityUses()) {
      uses.put(use.name(), use);
    }
    for (JsonNode command : reference.path("commands")) {
      if (!command.has("ability")) {
        continue;
      }
      String name = command.get("name").asText();
      Standard1v1Battle.AbilityUse use = uses.get(name);
      assertThat(use).as("the ability command %s ran", name).isNotNull();
      assertThat(use.tick()).as("%s: its tick", name).isEqualTo(command.get("tick").asInt());
      AbilityCommand.Outcome outcome = use.outcome();
      assertThat(outcome.code()).as("%s: its code", name).isEqualTo(command.get("code").asInt());
      assertThat(List.of(outcome.elixirBefore(), outcome.elixirAfter()))
          .as("%s: the elixir before and after", name)
          .containsExactly(
              command.get("elixir_before").asInt(), command.get("elixir_after").asInt());
      List<String> requests = new ArrayList<>();
      command.path("requests").forEach(r -> requests.add(r.asText()));
      assertThat(outcome.requested().stream().map(CharacterEntity::name).toList())
          .as("%s: the units requested", name)
          .isEqualTo(requests);
    }
    assertThat(uses.keySet())
        .as("every ability command")
        .hasSize(
            (int)
                StreamSupport.stream(reference.path("commands").spliterator(), false)
                    .filter(c -> c.has("ability"))
                    .count());
  }

  /**
   * The reference's group chain and Goblinstein log, in the battle's layout. The HP-bar run and the
   * card-play listener the monster's starting group lists are held by the run log, which lists each
   * as it starts; the steps that only connect are listed by what they connected to.
   */
  private static List<String> expectedGoblinsteinLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode g : reference.path("goblinstein")) {
      int tick = g.get("tick").asInt();
      switch (g.get("event").asText()) {
        case "group_link" ->
            expected.add(
                "%d group_link %s %s"
                    .formatted(
                        tick,
                        g.get("unit").asText(),
                        g.get("after").isNull() ? null : g.get("after").asText()));
        case "group_unlink" ->
            expected.add("%d group_unlink %s".formatted(tick, g.get("unit").asText()));
        case "hp_bar", "listener" -> {}
        case "start" ->
            expected.add(
                "%d start %s %s %d"
                    .formatted(
                        tick,
                        g.get("owner").asText(),
                        g.get("action").asText(),
                        g.get("phase").asInt()));
        case "step" -> {
          // The tether's activation rows and damage passes are held by the tether log.
          for (JsonNode call : g.get("calls")) {
            String owner = g.get("owner").asText();
            switch (call.get(0).asText()) {
              case "connect" -> {
                JsonNode connected = call.get(1);
                expected.add(
                    "%d connect %s %s"
                        .formatted(tick, owner, connected.isNull() ? null : connected.asText()));
              }
              case "cast_seen", "tether_end" ->
                  expected.add("%d %s %s".formatted(tick, call.get(0).asText(), owner));
              case "tether_start" ->
                  expected.add("%d tether_start %d %s".formatted(tick, call.get(1).asInt(), owner));
              case "schedule", "damage_pass" -> {}
              default ->
                  throw new IllegalArgumentException("a Goblinstein step that does more: " + g);
            }
          }
        }
        case "death_area" ->
            expected.add(
                "%d death_area %s %s %s %d %d %d %d %d %d"
                    .formatted(
                        tick,
                        g.get("owner").asText(),
                        g.get("removed").asText(),
                        g.get("area_effect").asText(),
                        g.get("id").asInt(),
                        g.get("x").asInt(),
                        g.get("y").asInt(),
                        g.get("side").asInt(),
                        g.get("level").asInt(),
                        g.get("countdown").asInt()));
        case "death_area_ended" ->
            expected.add(
                "%d death_area_ended %s %s"
                    .formatted(tick, g.get("owner").asText(), g.get("area_effect").asText()));
        default -> throw new IllegalArgumentException("unknown Goblinstein event " + g);
      }
    }
    return expected;
  }

  /** The reference's asks of an area effect's buff test of a clone, in the gate log's layout. */
  private static List<String> expectedCloneGateLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode g : reference.path("clone_buff_gate")) {
      expected.add(
          "%d gate %s %s %s %s query %d refused %b"
              .formatted(
                  g.get("tick").asInt(),
                  g.get("area_effect").asText(),
                  g.get("buff").asText(),
                  g.get("clone").asText(),
                  g.get("path").asText(),
                  g.get("query").asInt(),
                  g.get("refused").asInt() == 1));
    }
    return expected;
  }

  /**
   * The reference's reads of attack_count and asks of a buff's life condition, in its log's order.
   */
  private static List<String> expectedPrinceLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode e : reference.path("little_prince")) {
      int tick = e.get("tick").asInt();
      if (e.get("event").asText().equals("attack_count")) {
        expected.add(
            "%d attack_count %s %d %d"
                .formatted(
                    tick, e.get("owner").asText(), e.get("timer").asInt(), e.get("value").asInt()));
      } else {
        expected.add(
            "%d alive_if %s %s %d"
                .formatted(
                    tick,
                    e.get("unit").asText(),
                    e.get("expression").asText(),
                    e.get("value").asInt()));
      }
    }
    return expected;
  }

  /**
   * The reference's killed-done checks scheduled and checks of an action's cause, in its log's
   * order.
   */
  private static List<String> expectedKillLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode e : reference.path("kill_hooks")) {
      int tick = e.get("tick").asInt();
      if (e.get("event").asText().equals("killed_done")) {
        expected.add(
            "%d killed_done %s %s %s %s"
                .formatted(
                    tick,
                    e.get("owner").asText(),
                    e.get("killed").asText(),
                    e.get("action").asText(),
                    e.get("in_pending").asBoolean()));
      } else {
        expected.add(
            "%d instigator_match %s %s %s %s %s"
                .formatted(
                    tick,
                    e.get("owner").asText(),
                    e.get("action").asText(),
                    e.get("instigator").isNull() ? null : e.get("instigator").asText(),
                    e.get("row").isNull() ? null : e.get("row").asText(),
                    e.get("scheduled").isNull() ? null : e.get("scheduled").asText()));
      }
    }
    return expected;
  }

  /**
   * The reference's Kamikaze ends and drains, in the Kamikaze log's layout. An end of a unit with
   * hit points never sets the no-hit-points byte.
   */
  private static List<String> expectedKamikazeLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode k : reference.path("kamikaze")) {
      int tick = k.get("tick").asInt();
      if (k.has("drain")) {
        expected.add(
            "%d drain %s %d hp %d before %d"
                .formatted(
                    tick,
                    k.get("unit").asText(),
                    k.get("drain").asInt(),
                    k.get("hp").asInt(),
                    k.get("before").asInt()));
      } else {
        assertThat(k.get("f173").asInt()).as("%s: the no-hit-points byte", k).isZero();
        expected.add(
            "%d end %s hp %d kill %s"
                .formatted(
                    tick, k.get("unit").asText(), k.get("hp").asInt(), k.get("kill").asBoolean()));
      }
    }
    return expected;
  }

  /**
   * Logs what every Goblin Hut's life state does: its start and each step with its four words
   * before and after and what it called, each find the query answered, each child's point, each
   * child as it is made, and the notice that its target left. Objects are named as they were when
   * the run met them.
   */
  private static WorldObserver goblinHutLog(
      Standard1v1Battle match, int[] currentTick, List<String> log, Set<String> children) {
    Map<Integer, String> names = new HashMap<>();
    boolean[] childDue = {false};
    return new WorldObserver() {
      private String name(int id) {
        if (id == GoblinHutLifeState.NO_TARGET) {
          return null;
        }
        if (match.getWorld().liveObject(id) instanceof WorldEntity entity) {
          names.put(id, entity.name());
        }
        return names.get(id);
      }

      @Override
      public void goblinHutLogged(int tick, CharacterEntity hut, GoblinHutLifeState.Event event) {
        int t = currentTick[0];
        if (event instanceof GoblinHutLifeState.Stepped s) {
          log.add(
              s.start()
                  ? "%d start %s calls %s after %s"
                      .formatted(t, hut.name(), s.calls(), words(s.after()))
                  : "%d step %s before %s calls %s after %s"
                      .formatted(t, hut.name(), words(s.before()), s.calls(), words(s.after())));
        } else if (event instanceof GoblinHutLifeState.Found f) {
          log.add(
              "%d find %s listed %s found %s"
                  .formatted(
                      t,
                      hut.name(),
                      f.listed().stream().map(this::name).toList(),
                      name(f.found())));
        } else if (event instanceof GoblinHutLifeState.SpawnPoint p) {
          log.add(
              "%d spawn_point %s count %d i %d target %s point %d %d at %d %d"
                  .formatted(
                      t,
                      hut.name(),
                      p.count(),
                      p.index(),
                      name(p.target()),
                      p.x(),
                      p.y(),
                      p.atX(),
                      p.atY()));
          childDue[0] = true;
        } else if (event instanceof GoblinHutLifeState.TargetLeft l) {
          log.add("%d target_left %s %s".formatted(t, hut.name(), name(l.target())));
        }
      }

      @Override
      public void characterSpawned(
          int tick, SpawnHost source, CharacterEntity child, int x, int y) {
        if (!childDue[0]) {
          return;
        }
        childDue[0] = false;
        children.add(child.name());
        log.add(
            "%d spawn %s %s %d %s at %d %d state %d deploy %d hp %d level %d immune %d"
                .formatted(
                    currentTick[0],
                    source.name(),
                    child.name(),
                    child.getId(),
                    child.getData().name(),
                    x,
                    y,
                    child.getView().getState(),
                    child.getView().getDeployCountdown(),
                    child.getHitPoints().getHitPoints(),
                    child.getPackedLevel(),
                    child.isSpawnImmune() ? 1 : 0));
      }
    };
  }

  /** A life state's four words as the hut log lists them. */
  private static String words(GoblinHutLifeState.Memory m) {
    return "%d %d %d %d".formatted(m.timerMs(), m.target(), m.lost() ? 1 : 0, m.count());
  }

  /** The reference's Goblin Hut log, in the hut log's layout. */
  private static List<String> expectedGoblinHutLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode h : reference.path("goblin_hut")) {
      int tick = h.get("tick").asInt();
      String owner = h.get("owner").asText();
      switch (h.get("event").asText()) {
        case "start" ->
            expected.add(
                "%d start %s calls %s after %s"
                    .formatted(tick, owner, calls(h.get("calls")), jsonWords(h.get("after"))));
        case "step" ->
            expected.add(
                "%d step %s before %s calls %s after %s"
                    .formatted(
                        tick,
                        owner,
                        jsonWords(h.get("before")),
                        calls(h.get("calls")),
                        jsonWords(h.get("after"))));
        case "find" -> {
          List<String> listed = new ArrayList<>();
          h.get("listed").forEach(n -> listed.add(n.asText()));
          expected.add(
              "%d find %s listed %s found %s"
                  .formatted(
                      tick,
                      owner,
                      listed,
                      h.get("found").isNull() ? null : h.get("found").asText()));
        }
        case "spawn_point" ->
            expected.add(
                "%d spawn_point %s count %d i %d target %s point %d %d at %d %d"
                    .formatted(
                        tick,
                        owner,
                        h.get("count").asInt(),
                        h.get("i").asInt(),
                        h.get("target").asText(),
                        h.get("point").get(0).asInt(),
                        h.get("point").get(1).asInt(),
                        h.get("relocated").get(0).asInt(),
                        h.get("relocated").get(1).asInt()));
        case "spawn" ->
            expected.add(
                "%d spawn %s %s %d %s at %d %d state %d deploy %d hp %d level %d immune %d"
                    .formatted(
                        tick,
                        owner,
                        h.get("unit").asText(),
                        h.get("id").asInt(),
                        h.get("row").asText(),
                        h.get("created").get(0).asInt(),
                        h.get("created").get(1).asInt(),
                        h.get("state").asInt(),
                        h.get("deploy").asInt(),
                        h.get("hp").asInt(),
                        h.get("level").asInt(),
                        h.get("immune").asInt()));
        case "target_left" ->
            expected.add("%d target_left %s %s".formatted(tick, owner, h.get("target").asText()));
        default -> throw new IllegalStateException("unknown Goblin Hut event " + h);
      }
    }
    return expected;
  }

  /** A logged call list as the hut log lists it: each call's words joined by spaces. */
  private static List<String> calls(JsonNode calls) {
    List<String> out = new ArrayList<>();
    for (JsonNode call : calls) {
      List<String> parts = new ArrayList<>();
      call.forEach(part -> parts.add(part.isNull() ? "null" : part.asText()));
      out.add(String.join(" ", parts));
    }
    return out;
  }

  /** The reference's four words as the hut log lists them. */
  private static String jsonWords(JsonNode m) {
    return "%d %d %d %d"
        .formatted(
            m.get("timer").asInt(),
            m.get("target").asInt(),
            m.get("lost").asInt(),
            m.get("count").asInt());
  }

  /** The lanes the reference's const-priority rings asked for, in the lane log's layout. */
  private static List<String> expectedLaneLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode a : reference.path("actions")) {
      if (a.get("event").asText().equals("const_priority_lane")) {
        expected.add(
            "%d const_priority_lane %s %d %d lane %d"
                .formatted(
                    a.get("tick").asInt(),
                    a.get("owner").asText(),
                    a.get("x").asInt(),
                    a.get("y").asInt(),
                    a.get("lane").asInt()));
      }
    }
    return expected;
  }

  /** The reference's special loads, drag states, holds and pulls, in the hook log's layout. */
  private static List<String> expectedHookLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode h : reference.path("hooks")) {
      int tick = h.get("tick").asInt();
      switch (h.get("event").asText()) {
        case "arm" ->
            expected.add(
                "%d arm %s ref %s distance %d ring %d %d load %d after %d"
                    .formatted(
                        tick,
                        h.get("unit").asText(),
                        h.get("ref").asText(),
                        h.get("distance").asLong(),
                        h.get("ring").get(0).asInt(),
                        h.get("ring").get(1).asInt(),
                        h.get("load").asInt(),
                        h.get("after").asInt()));
        case "state" ->
            expected.add(
                "%d state %s %s %d to %d now %d at %d %d"
                    .formatted(
                        tick,
                        h.get("projectile").asText(),
                        h.get("unit").asText(),
                        h.get("old").asInt(),
                        h.get("new").asInt(),
                        h.get("state").asInt(),
                        h.get("x").asInt(),
                        h.get("y").asInt()));
        case "held_left" -> {
          // No owner of a dragging projectile morphs back: the battle refuses one that would.
          assertThat(h.get("morph_character").isNull()).isTrue();
          expected.add(
              "%d held_left %s %s"
                  .formatted(tick, h.get("unit").asText(), h.get("projectile").asText()));
        }
        case "follow_left" ->
            expected.add(
                "%d follow_left %s %s now %d at %d %d"
                    .formatted(
                        tick,
                        h.get("unit").asText(),
                        h.get("projectile").asText(),
                        h.get("state").asInt(),
                        h.get("x").asInt(),
                        h.get("y").asInt()));
        default -> throw new IllegalStateException("unknown hook event " + h);
      }
    }
    return expected;
  }

  /** A reflect in the reference's layout. */
  private static String reflectLine(int tick, Reflection r) {
    String line =
        "%d reflect %s by %s kind %s source %s struck %s speed %d"
            .formatted(
                tick,
                r.target().name(),
                r.attacker() instanceof AreaEffectEntity a ? a.name() : attackerName(r.attacker()),
                r.attacker() == null ? null : String.valueOf(r.attacker().getKind()),
                r.source() == null ? null : r.source().name(),
                r.struck() == null ? null : r.struck().name(),
                r.hitSpeed());
    if (r.buff() != null) {
      line += " buff %s %d %d".formatted(r.buff(), r.buffTimeMs(), r.buffLevel());
    }
    if (r.damage() != 0) {
      line += " damage %d %d %d".formatted(r.damage(), r.hitPointsBefore(), r.hitPointsAfter());
    }
    return line;
  }

  /** An attacker as the reference names it: a projectile by its id. */
  private static String attackerName(BattleEntity attacker) {
    if (attacker instanceof WorldEntity entity) {
      return entity.name();
    }
    return attacker instanceof ProjectileEntity ? "proj_" + attacker.getId() : null;
  }

  /** The arena entity of the given name. */
  private static WorldEntity named(Battle battle, String name) {
    return battle.getHolder().entities().stream()
        .filter(e -> e instanceof WorldEntity w && w.name().equals(name))
        .map(WorldEntity.class::cast)
        .findFirst()
        .orElseThrow();
  }

  private static String referenceName(WorldEntity tower) {
    TargetView reference = tower.getTargeting().getReference();
    return reference == null ? null : reference.name();
  }

  /**
   * Lists every charge a unit completed, with its progress and where it stood, every charge it lost
   * at a movement visit, with its state then, and every state its movement pass asked for; a jump
   * lists its length and its landing node.
   */
  private static WorldObserver jumpChargeDashLog(int[] currentTick, List<String> log) {
    return new WorldObserver() {
      @Override
      public void chargeCompleted(int tick, CharacterEntity unit, int progress) {
        log.add(
            "%d charged %s %d %d %d"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    progress,
                    unit.getView().getX(),
                    unit.getView().getY()));
      }

      @Override
      public void chargeLost(int tick, CharacterEntity unit) {
        log.add(
            "%d charge_lost %s %d %d %d"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    unit.getView().getState(),
                    unit.getView().getX(),
                    unit.getView().getY()));
      }

      @Override
      public void dashStarted(
          int tick,
          CharacterEntity unit,
          TargetView reference,
          int fromX,
          int fromY,
          int aimX,
          int aimY) {
        log.add(
            "%d dash_start %s %s %d %d aim %d %d route %s stop %d time %d windup %d"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    // A guard's charge has no reference.
                    reference == null ? null : reference.getEntity().getName(),
                    fromX,
                    fromY,
                    aimX,
                    aimY,
                    Arrays.stream(unit.getUnit().movement().getRoute().toArray()).boxed().toList(),
                    unit.getUnit().movement().getDashStopsInRange(),
                    unit.getUnit().movement().getDashTimeMs(),
                    unit.getUnit().targeting().getDashWindupMs()));
      }

      @Override
      public void dashLanded(
          int tick, CharacterEntity unit, WorldEntity hit, int damage, boolean area) {
        String what = "none";
        if (area) {
          what =
              "area %d %d %d %d %d"
                  .formatted(
                      unit.getView().getX(),
                      unit.getView().getY(),
                      unit.getData().dashRadius(),
                      damage,
                      unit.getData().dashPushBack());
        } else if (hit != null) {
          what = "single %s %d".formatted(hit.name(), damage);
        }
        log.add(
            "%d landing %s %d %d %s delay %d state %d"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    unit.getView().getX(),
                    unit.getView().getY(),
                    what,
                    unit.getView().getBlockCountdownMs(),
                    unit.getView().getState()));
      }

      @Override
      public void movementStateRequested(int tick, CharacterEntity unit, int from, int to) {
        String jump = "";
        if (to == GridEntityState.JUMPING) {
          jump =
              " jump %d %s"
                  .formatted(
                      unit.getUnit().movement().getJumpTotalDistance(),
                      List.of(unit.getUnit().movement().getRoute().last()));
        }
        log.add(
            "%d state %s %d %d %d %d%s"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    from,
                    to,
                    unit.getView().getX(),
                    unit.getView().getY(),
                    jump));
      }
    };
  }

  /** The reference's charges, their losses and the states movement passes asked for, as listed. */
  /**
   * Logs a hiding building's deploy end, which runs its targeting visit from the state visit, and
   * each visit of its hide handler that shows something, as the reference marks it: the effects the
   * handler plays, the counter reaching the hide time (hidden) or leaving it (visible), reaching 0
   * (up), and a change of the step from the last one logged.
   */
  private static WorldObserver hidingLog(int[] currentTick, List<String> log) {
    Map<String, Integer> lastStep = new HashMap<>();
    return new WorldObserver() {
      @Override
      public void deployEndVisited(int tick, CharacterEntity unit) {
        log.add(
            "%d deploy_end_visit %s %s %d"
                .formatted(
                    currentTick[0], unit.name(), referenceName(unit), unit.getView().getState()));
      }

      @Override
      public void hideVisited(
          int tick,
          CharacterEntity unit,
          int state,
          int before,
          int after,
          int step,
          List<String> effects) {
        int hide = unit.getData().hideTimeMs();
        List<String> marks = new ArrayList<>(effects);
        if (after == hide && before != hide) {
          marks.add("hidden");
        }
        if (before == hide && after != hide) {
          marks.add("visible");
        }
        if (after == 0 && before != 0) {
          marks.add("up");
        }
        if (step != lastStep.getOrDefault(unit.name(), StateQueries.TICK_MS)) {
          marks.add("step");
          lastStep.put(unit.name(), step);
        }
        if (!marks.isEmpty()) {
          log.add(
              "%d hide %s %d %d %d %d %s"
                  .formatted(currentTick[0], unit.name(), state, before, after, step, marks));
        }
      }
    };
  }

  /**
   * Logs each hit of an attracting area effect: its centre, and each unit it pulled with the vector
   * to the centre and its push accumulators before and after.
   */
  private static WorldObserver pullLog(int[] currentTick, List<String> log) {
    return new WorldObserver() {
      @Override
      public void areaPulled(
          int tick, AreaEffectEntity areaEffect, List<AreaEffectEntity.Pull> pulls) {
        List<String> pulled = new ArrayList<>();
        for (AreaEffectEntity.Pull pull : pulls) {
          pulled.add(
              "%s %d %d %s %s"
                  .formatted(
                      pull.target().name(),
                      pull.dx(),
                      pull.dy(),
                      Arrays.toString(pull.before()),
                      Arrays.toString(pull.after())));
        }
        log.add(
            "%d pull %s %d %d %s"
                .formatted(
                    currentTick[0],
                    areaEffect.name(),
                    areaEffect.getX(),
                    areaEffect.getY(),
                    pulled));
      }
    };
  }

  /**
   * Logs each push of a unit entering its deploying state: its radius and distance, what its query
   * found and whom it asked to push.
   */
  private static WorldObserver deployPushLog(int[] currentTick, List<String> log) {
    return new WorldObserver() {
      @Override
      public void deployPushed(
          int tick,
          CharacterEntity unit,
          int radius,
          int distance,
          List<WorldEntity> found,
          List<WorldEntity> pushed) {
        log.add(
            "%d %s %d %d %s %s"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    radius,
                    distance,
                    found.stream().map(WorldEntity::name).toList(),
                    pushed.stream().map(WorldEntity::name).toList()));
      }
    };
  }

  private static List<String> expectedDeployPushLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode e : reference.path("deploy_push")) {
      List<String> found = new ArrayList<>();
      e.get("candidates").forEach(n -> found.add(n.asText()));
      List<String> pushed = new ArrayList<>();
      e.get("requested").forEach(n -> pushed.add(n.asText()));
      expected.add(
          "%d %s %d %d %s %s"
              .formatted(
                  e.get("tick").asInt(),
                  e.get("unit").asText(),
                  e.get("radius").asInt(),
                  e.get("distance").asInt(),
                  found,
                  pushed));
    }
    return expected;
  }

  private static List<String> expectedPullLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode e : reference.path("pulls")) {
      List<String> pulled = new ArrayList<>();
      for (JsonNode p : e.get("pushed")) {
        int[] before = new int[5];
        int[] after = new int[5];
        for (int i = 0; i < 5; i++) {
          before[i] = p.get("before").get(i).asInt();
          after[i] = p.get("after").get(i).asInt();
        }
        pulled.add(
            "%s %d %d %s %s"
                .formatted(
                    p.get("target").asText(),
                    p.get("vector").get(0).asInt(),
                    p.get("vector").get(1).asInt(),
                    Arrays.toString(before),
                    Arrays.toString(after)));
      }
      expected.add(
          "%d pull %s %d %d %s"
              .formatted(
                  e.get("tick").asInt(),
                  e.get("area_effect").asText(),
                  e.get("centre").get(0).asInt(),
                  e.get("centre").get(1).asInt(),
                  pulled));
    }
    return expected;
  }

  private static List<String> expectedHidingLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode e : reference.path("hiding")) {
      if (e.get("event").asText().equals("deploy_end_visit")) {
        expected.add(
            "%d deploy_end_visit %s %s %d"
                .formatted(
                    e.get("tick").asInt(),
                    e.get("unit").asText(),
                    e.get("ref").asText(),
                    e.get("state").asInt()));
      } else {
        List<String> marks = new ArrayList<>();
        e.get("marks").forEach(mark -> marks.add(mark.asText()));
        expected.add(
            "%d hide %s %d %d %d %d %s"
                .formatted(
                    e.get("tick").asInt(),
                    e.get("unit").asText(),
                    e.get("state").asInt(),
                    e.get("before").asInt(),
                    e.get("after").asInt(),
                    e.get("step").asInt(),
                    marks));
      }
    }
    return expected;
  }

  private static List<String> expectedJumpChargeDash(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode e : reference.path("jump_charge_dash")) {
      String kind = e.get("event").asText();
      int tick = e.get("tick").asInt();
      String unit = e.get("unit").asText();
      switch (kind) {
        case "charged" ->
            expected.add(
                "%d charged %s %d %d %d"
                    .formatted(
                        tick,
                        unit,
                        e.get("charge").asInt(),
                        e.get("x").asInt(),
                        e.get("y").asInt()));
        case "charge_lost" ->
            expected.add(
                "%d charge_lost %s %d %d %d"
                    .formatted(
                        tick,
                        unit,
                        e.get("state").asInt(),
                        e.get("x").asInt(),
                        e.get("y").asInt()));
        case "state" -> {
          String jump = "";
          if (e.has("jump_total")) {
            List<Integer> target = new ArrayList<>();
            e.get("target").forEach(node -> target.add(node.asInt()));
            jump = " jump %d %s".formatted(e.get("jump_total").asInt(), target);
          }
          expected.add(
              "%d state %s %d %d %d %d%s"
                  .formatted(
                      tick,
                      unit,
                      e.get("old").asInt(),
                      e.get("state").asInt(),
                      e.get("x").asInt(),
                      e.get("y").asInt(),
                      jump));
        }
        case "dash_start" -> {
          List<Integer> route = new ArrayList<>();
          e.get("route").forEach(node -> route.add(node.asInt()));
          expected.add(
              "%d dash_start %s %s %d %d aim %d %d route %s stop %d time %d windup %d"
                  .formatted(
                      tick,
                      unit,
                      e.get("ref").isNull() ? null : e.get("ref").asText(),
                      e.get("x").asInt(),
                      e.get("y").asInt(),
                      e.get("aim").get(0).asInt(),
                      e.get("aim").get(1).asInt(),
                      route,
                      e.get("stop_check").asInt(),
                      e.get("dash_time").asInt(),
                      e.get("windup").asInt()));
        }
        case "landing" -> {
          JsonNode hit = e.get("hit");
          String what = "none";
          if (hit != null && !hit.isNull()) {
            what =
                hit.get("kind").asText().equals("area")
                    ? "area %d %d %d %d %d"
                        .formatted(
                            hit.get("centre").get(0).asInt(),
                            hit.get("centre").get(1).asInt(),
                            hit.get("radius").asInt(),
                            hit.get("damage").asInt(),
                            hit.get("push").asInt())
                    : "single %s %d"
                        .formatted(hit.get("target").asText(), hit.get("damage").asInt());
          }
          expected.add(
              "%d landing %s %d %d %s delay %d state %d"
                  .formatted(
                      tick,
                      unit,
                      e.get("x").asInt(),
                      e.get("y").asInt(),
                      what,
                      e.get("landing_delay").asInt(),
                      e.get("state").asInt()));
        }
        default -> throw new IllegalArgumentException("an event this test does not hold: " + kind);
      }
    }
    return expected;
  }

  /**
   * The events with an area's pushes listed after all of its hits. The battle pushes each victim
   * right after its damage, as the area damage does; the reference applies the pushes the area
   * asked for once its loop ends, so it lists them after the last hit. Nothing a push does reaches
   * the later victims' damage, so only the order they are listed in differs. An area's hits are an
   * area effect's or a unit's area hits of one tick, or the impacts of one projectile on one tick.
   */
  private static List<String> pushesAfterTheirArea(List<String> events) {
    List<String> ordered = new ArrayList<>();
    List<String> pushes = new ArrayList<>();
    String area = null;
    for (String event : events) {
      String[] words = event.split(" ");
      String last = ordered.isEmpty() ? null : areaOf(ordered.get(ordered.size() - 1));
      if (words[1].equals("pushback") && (!pushes.isEmpty() || last != null)) {
        if (pushes.isEmpty()) {
          area = last;
        }
        pushes.add(event);
        continue;
      }
      if (!pushes.isEmpty() && !area.equals(areaOf(event))) {
        ordered.addAll(pushes);
        pushes.clear();
      }
      ordered.add(event);
    }
    ordered.addAll(pushes);
    return ordered;
  }

  /** The area a hit event belongs to, or null for an event that is not an area's hit. */
  private static String areaOf(String event) {
    String[] words = event.split(" ");
    return switch (words[1]) {
      case "area_hit" -> words[0] + " area_hit";
      case "impact" -> words[0] + " impact " + words[2];
      default -> null;
    };
  }

  /** Both sides' opening hands and queues, and the draws that seeded their shuffles. */
  private static void assertOpeningHands(LadderMatch ladder, JsonNode m) {
    for (JsonNode entry : m.get("log")) {
      if (!entry.get("event").asText().equals("hand")) {
        continue;
      }
      int side = entry.get("side").asInt();
      MatchSide matchSide = ladder.side(side);
      assertThat(ladder.shuffleDraw(side))
          .as("side %d's shuffle draw", side)
          .isEqualTo(entry.get("seed_draw").asInt());
      List<Integer> slots = new ArrayList<>();
      entry.get("slots").forEach(slot -> slots.add(slot.asInt()));
      List<Integer> queue = new ArrayList<>();
      entry.get("queue").forEach(index -> queue.add(index.asInt()));
      assertThat(Arrays.stream(matchSide.getHand().slots()).boxed().toList())
          .as("side %d's opening hand", side)
          .isEqualTo(slots);
      assertThat(matchSide.getHand().queue()).as("side %d's queue", side).isEqualTo(queue);
      assertThat(matchSide.getElixir()).isEqualTo(entry.get("elixir").asInt());
    }
  }

  /**
   * One step of the match as the reference traces it: both elixirs, both hands, both cooldowns, the
   * timeline's time, section and rate, both crowns, the end timer, whether the battle ended, the
   * tiebreaker's time and whether the entities were ticked.
   */
  private static String traceRow(int tick, LadderMatch ladder) {
    List<Object> values = new ArrayList<>();
    values.add(tick);
    values.add(ladder.side(0).getElixir());
    values.add(ladder.side(1).getElixir());
    values.add(Arrays.stream(ladder.side(0).getHand().slots()).boxed().toList());
    values.add(Arrays.stream(ladder.side(1).getHand().slots()).boxed().toList());
    values.add(ladder.side(0).getHand().getCooldownMs());
    values.add(ladder.side(1).getHand().getCooldownMs());
    values.add(ladder.getTimeline().getTimeMs());
    values.add(ladder.getTimeline().getSection());
    values.add(ladder.getTimeline().getRate());
    values.add(ladder.crowns(0));
    values.add(ladder.crowns(1));
    values.add(ladder.getEndTimerMs());
    values.add(ladder.isEnded() ? 1 : 0);
    values.add(ladder.getTiebreakMs());
    values.add(ladder.isLastTicked() ? 1 : 0);
    return JSON.valueToTree(values).toString();
  }

  /**
   * The match's end: its winner, and the step the battle stops on - which runs nothing at all, not
   * even the tick counter.
   */
  private static void assertEnd(Standard1v1Battle match, LadderMatch ladder, JsonNode m) {
    if (m.get("end").isNull()) {
      assertThat(ladder.isEnded()).as("the match goes on").isFalse();
      return;
    }
    assertThat(ladder.getWinner()).as("the winner").isEqualTo(m.get("end").get("winner").asInt());
    int stopped = m.get("stopped_at").asInt();
    assertThat(match.getBattle().getTick()).as("the step the battle stops on").isEqualTo(stopped);
    assertThat(match.getBattle().getMode().isOver()).as("stopped").isTrue();
    match.getBattle().step();
    assertThat(match.getBattle().getTick()).as("a stopped battle runs no step").isEqualTo(stopped);
  }

  /**
   * Every Mirror item a play carried: the card it repeats, the Mirror's deck index, its level and
   * the item's, and the item's cost; the level each repeated play ran at, and the last card a side
   * keeps after its final play.
   */
  private static void assertMirror(
      Standard1v1Battle match, LadderMatch ladder, JsonNode reference) {
    Map<String, Standard1v1Battle.Play> plays = new HashMap<>();
    for (Standard1v1Battle.Play play : match.getPlays()) {
      plays.put(play.name(), play);
    }
    Map<Integer, String> lastKept = new HashMap<>();
    for (JsonNode e : reference.path("mirror")) {
      Standard1v1Battle.Play play = plays.get(e.get("command").asText());
      assertThat(play).as("the Mirror play %s", e.get("command").asText()).isNotNull();
      MirrorItem item = play.mirror();
      switch (e.get("event").asText()) {
        case "item" -> {
          assertThat(item).as("%s carries a Mirror item", play.name()).isNotNull();
          assertThat(item.repeats() == null ? null : item.repeats().name())
              .as("%s: the card repeated", play.name())
              .isEqualTo(e.get("repeats").asText(null))
              .isEqualTo(e.get("source").asText(null));
          assertThat(item.index())
              .as("%s: the Mirror's index", play.name())
              .isEqualTo(e.get("index").asInt());
          assertThat(item.mirrorLevelField())
              .as("%s: the Mirror's level", play.name())
              .isEqualTo(e.get("mirror_level").asInt());
          assertThat(item.levelField())
              .as("%s: the item's level", play.name())
              .isEqualTo(e.get("item_level").asInt());
          assertThat(item.cost())
              .as("%s: the item's cost", play.name())
              .isEqualTo(e.get("cost").asInt());
        }
        case "refused" -> {
          assertThat(play.matchCode())
              .as("%s: the code", play.name())
              .isEqualTo(e.get("code").asInt());
          assertThat(item.cost()).as("%s: the cost", play.name()).isEqualTo(e.get("cost").asInt());
        }
        case "play" -> {
          assertThat(item.level())
              .as("%s: the level played", play.name())
              .isEqualTo(e.get("level").asInt());
          assertThat(item.cost()).as("%s: the cost", play.name()).isEqualTo(e.get("cost").asInt());
          lastKept.put(e.get("side").asInt(), e.get("last_item").asText(null));
        }
        default -> throw new IllegalArgumentException("a Mirror log entry " + e);
      }
    }
    // No run plays again after its last Mirror, so the card a side keeps at the end is the one its
    // last Mirror play left.
    lastKept.forEach(
        (side, card) -> {
          MatchCard last = ladder.side(side).lastPlayed();
          assertThat(last == null ? null : last.name())
              .as("side %d's last card", side)
              .isEqualTo(card);
        });
  }

  /**
   * Every item a variant card's play carried: the option it was picked as, that option's index and
   * cost, and the card's deck index the play cycled.
   */
  private static void assertVariant(Standard1v1Battle match, JsonNode reference) {
    Map<String, Standard1v1Battle.Play> plays = new HashMap<>();
    for (Standard1v1Battle.Play play : match.getPlays()) {
      plays.put(play.name(), play);
    }
    Map<String, JsonNode> commands = new HashMap<>();
    for (JsonNode command : reference.path("commands")) {
      commands.put(command.get("name").asText(), command);
    }
    for (JsonNode e : reference.path("variant_picks")) {
      String name = e.get("command").asText();
      Standard1v1Battle.Play play = plays.get(name);
      assertThat(play).as("the variant play %s", name).isNotNull();
      assertThat(play.tick()).as("%s: the tick it ran on", name).isEqualTo(e.get("run").asInt());
      VariantItem item = play.variant();
      assertThat(item).as("%s carries a variant item", name).isNotNull();
      assertThat(item.spell()).as("%s: the option", name).isEqualTo(e.get("picked").asText());
      assertThat(item.option()).as("%s: its index", name).isEqualTo(e.get("option").asInt());
      assertThat(item.cost()).as("%s: its cost", name).isEqualTo(e.get("cost").asInt());
      assertThat(item.index())
          .as("%s: the deck index", name)
          .isEqualTo(commands.get(name).get("play").get("deck_index").asInt());
    }
  }

  /**
   * Every evolution and hero slot of a deck, with the card's evolved and hero rows, and every
   * play's item in a run with one: the deck index, the evolution field, the row cast, its cost and
   * the count the item was built from; and each count a side holds at the end, the one its last
   * play of the card left.
   */
  private static void assertEvolution(
      Standard1v1Battle match, LadderMatch ladder, JsonNode reference) {
    Map<String, Standard1v1Battle.Play> plays = new HashMap<>();
    for (Standard1v1Battle.Play play : match.getPlays()) {
      plays.put(play.name(), play);
    }
    Map<List<Integer>, Integer> lastCount = new HashMap<>();
    for (JsonNode e : reference.path("evolution")) {
      int side = e.get("side").asInt();
      int index = e.get("index").asInt();
      if (e.get("event").asText().equals("slot")) {
        MatchCard card = ladder.side(side).deck().get(index);
        assertThat(card.name())
            .as("side %d's card %d", side, index)
            .isEqualTo(e.get("card").asText());
        assertThat(ladder.side(side).slotFlags(index))
            .as("side %d's card %d: its slot flags", side, index)
            .isEqualTo(e.get("flags").asInt());
        assertThat(card.formRow(MatchCard.EVO_FORM).name())
            .as("%s's evolved row", card.name())
            .isEqualTo(e.get("evolution").asText());
        assertThat(card.formRow(MatchCard.HERO_FORM).name())
            .as("%s's hero row", card.name())
            .isEqualTo(e.get("hero").asText());
        continue;
      }
      String name = e.get("command").asText();
      Standard1v1Battle.Play play = plays.get(name);
      assertThat(play).as("the play %s", name).isNotNull();
      EvolutionItem item = play.evolution();
      assertThat(item).as("%s carries a deck card's item", name).isNotNull();
      assertThat(item.index()).as("%s: the deck index", name).isEqualTo(index);
      assertThat(item.field())
          .as("%s: the evolution field", name)
          .isEqualTo(e.get("field").asInt());
      assertThat(item.spell().name())
          .as("%s: the row cast", name)
          .isEqualTo(e.get("cast").asText());
      assertThat(item.cost()).as("%s: the cost", name).isEqualTo(e.get("cost").asInt());
      assertThat(item.count())
          .as("%s: the count the item was built from", name)
          .isEqualTo(e.get("count_before").asInt());
      lastCount.put(List.of(side, index), e.get("count_after").asInt());
    }
    lastCount.forEach(
        (key, count) ->
            assertThat(ladder.side(key.get(0)).evolutionCount(key.get(1)))
                .as("side %d's count of card %d at the end", key.get(0), key.get(1))
                .isEqualTo(count));
  }

  /** Every play refused by a match's gate with the reference's code, and every other let on. */
  private static void assertPlays(Standard1v1Battle match, JsonNode reference) {
    Map<String, Integer> codes = new HashMap<>();
    for (Standard1v1Battle.Play play : match.getPlays()) {
      codes.put(play.name(), play.matchCode());
    }
    for (JsonNode command : reference.path("commands")) {
      if (command.has("ability")) {
        continue;
      }
      String name = command.get("name").asText();
      int expected =
          command.path("stage").asText().equals("match_gates") ? command.get("code").asInt() : 0;
      assertThat(codes).as("the play %s ran", name).containsKey(name);
      assertThat(codes.get(name)).as("the play %s's match code", name).isEqualTo(expected);
    }
  }

  /**
   * Lists every kill of a fallen king's circle with its radius, every step of a tiebreaker's drain
   * with the tower's hit points after it, and every elixir a collector or a death paid a king.
   */
  private static WorldObserver matchLog(
      int[] currentTick, List<String> circle, List<String> drain, List<String> elixir) {
    return new WorldObserver() {
      @Override
      public void elixirCollected(int tick, WorldEntity collector, int side, int amount) {
        elixir.add(
            "%d collector %s %d %d".formatted(currentTick[0], collector.name(), side, amount));
      }

      @Override
      public void deathElixirPaid(int tick, WorldEntity dying, int side, int amount) {
        elixir.add("%d death %s %d %d".formatted(currentTick[0], dying.name(), side, amount));
      }

      @Override
      public void circleKilled(int tick, WorldEntity target, int radius) {
        circle.add("%d %s %d".formatted(currentTick[0], target.name(), radius));
      }

      @Override
      public void drained(int tick, WorldEntity target, int damage, int hitPoints, boolean died) {
        drain.add("%d %s %d %d".formatted(currentTick[0], target.name(), damage, hitPoints));
      }
    };
  }
}
