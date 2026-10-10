package org.crforge.core.battle.unit;

import static org.crforge.core.util.ValidationUtils.checkArgument;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.crforge.core.battle.BattleComponent;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.TargetLocks;
import org.crforge.core.battle.action.ActionContext;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.ActionOwner;
import org.crforge.core.battle.action.AirToGround;
import org.crforge.core.battle.action.AttackChain;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.BlowdartDartSelect;
import org.crforge.core.battle.action.BossBanditAbility;
import org.crforge.core.battle.action.CannonBarrage;
import org.crforge.core.battle.action.CaptureCharacter;
import org.crforge.core.battle.action.ChainAttackHost;
import org.crforge.core.battle.action.Clone;
import org.crforge.core.battle.action.ConeShape;
import org.crforge.core.battle.action.Counter;
import org.crforge.core.battle.action.DamagingPushBack;
import org.crforge.core.battle.action.DashingAttackChain;
import org.crforge.core.battle.action.DoPushbackFromInstigator;
import org.crforge.core.battle.action.FriendCollecting;
import org.crforge.core.battle.action.GhostEvo;
import org.crforge.core.battle.action.GoblinDrillEvoRelocate;
import org.crforge.core.battle.action.GoblinHutLife;
import org.crforge.core.battle.action.GoblinHutLifeState;
import org.crforge.core.battle.action.GroupChain;
import org.crforge.core.battle.action.GuardHost;
import org.crforge.core.battle.action.Knockback;
import org.crforge.core.battle.action.LumberjackGhostWait;
import org.crforge.core.battle.action.MegaKnightUppercut;
import org.crforge.core.battle.action.MusketeerSnipe;
import org.crforge.core.battle.action.NetAttackHost;
import org.crforge.core.battle.action.PopBalloons;
import org.crforge.core.battle.action.ReadyChampionAbility;
import org.crforge.core.battle.action.RunOnResolvedObjects;
import org.crforge.core.battle.action.SetIndicatorOnTarget;
import org.crforge.core.battle.action.ShapeSelector;
import org.crforge.core.battle.action.ShapeSelectorHost;
import org.crforge.core.battle.action.SpawnResetableAreaEffect;
import org.crforge.core.battle.action.TargetIndicatorAttack;
import org.crforge.core.battle.action.TargetIndicatorHost;
import org.crforge.core.battle.action.Taunt;
import org.crforge.core.battle.action.WarpCharacter;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.battle.projectile.CaptureHost;
import org.crforge.core.battle.projectile.CaptureRun;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.GridMovementQueries;
import org.crforge.core.pathfinding.GridStateSetter;
import org.crforge.core.pathfinding.GridUnitState;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.crforge.core.pathfinding.combat.LevelScaling;
import org.crforge.core.pathfinding.combat.PackedLevel;
import org.crforge.core.pathfinding.combat.ScalingGlobals;
import org.crforge.core.pathfinding.combat.ScalingMode;
import org.crforge.core.pathfinding.grid.CellGrid;
import org.crforge.core.pathfinding.grid.CellTests;
import org.crforge.core.pathfinding.grid.LaneAssignment;
import org.crforge.core.pathfinding.grid.Relocation;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.math.FixedMath;
import org.crforge.core.pathfinding.move.AttachedParent;
import org.crforge.core.pathfinding.move.DashStart;
import org.crforge.core.pathfinding.move.MovementChain;
import org.crforge.core.pathfinding.move.MovementConfig;
import org.crforge.core.pathfinding.move.MovementRequests;
import org.crforge.core.pathfinding.move.MovementState;
import org.crforge.core.pathfinding.move.MovementVisit;
import org.crforge.core.pathfinding.move.PushbackQueries;
import org.crforge.core.pathfinding.move.PushbackRequest;
import org.crforge.core.pathfinding.move.SingleNodeRoute;
import org.crforge.core.pathfinding.move.SpeedBudget;
import org.crforge.core.pathfinding.move.SpeedConfig;
import org.crforge.core.pathfinding.move.SpeedGlobals;
import org.crforge.core.pathfinding.state.EntityStateVisit;
import org.crforge.core.pathfinding.state.HideHandler;
import org.crforge.core.pathfinding.state.ResumeHelper;
import org.crforge.core.pathfinding.state.StateQueries;
import org.crforge.core.pathfinding.state.StateTimers;
import org.crforge.core.pathfinding.state.StateVisitConfig;
import org.crforge.core.pathfinding.state.StateVisitGlobals;
import org.crforge.core.pathfinding.target.HitQueries;
import org.crforge.core.pathfinding.target.RangeTest;
import org.crforge.core.pathfinding.target.ReferenceSetter;
import org.crforge.core.pathfinding.target.ReferenceValidator;
import org.crforge.core.pathfinding.target.SelectionChain;
import org.crforge.core.pathfinding.target.TargetView;
import org.crforge.core.pathfinding.target.TargetingConfig;
import org.crforge.core.pathfinding.target.TargetingOutcome;
import org.crforge.core.pathfinding.target.TargetingState;
import org.crforge.core.pathfinding.target.TargetingVisit;
import org.crforge.core.pathfinding.target.ValidatorQueries;

/**
 * A walking ground unit: a targeting component in slot 0, a movement component in slot 1 and the
 * entity state visit as its post-hook. A building is one too, without the movement component: it
 * stands where it is placed, and other units treat it as the obstacle a tower is. An air unit is
 * one too, created at its row's flying height, which it keeps: the same code with other layer
 * answers, so it routes to one node, crosses water, and meets in the contact passes only units on
 * its side of height 0.
 *
 * <p>Because the holder runs one whole-list pass per slot, every character has chosen its target
 * for the tick before any character moves, and every character has moved before any character's
 * state visit runs. A character is created deploying and takes no decisions until its deploy
 * countdown, stepped by the state visit, runs out: its targeting component is not visited, and its
 * movement component is, but asks for no route and gets no speed, so only a push can move it.
 *
 * <p>Every state change - the lock the targeting visit requests, the resume, the state visit's
 * transitions - goes through the character's own {@link GridStateSetter}, so stopping empties the
 * route and resuming prepares one at once; taking a reference prepares its route through the same
 * setter, at the moment the reference is stored.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: targeting in slot 0, movement in slot 1, the state visit as the post-hook, the"
            + " deploy countdown stepping 50 ms per state visit, every state change and route"
            + " preparation going through the unit's own setter, each hit recorded on the"
            + " attacker and landing on its target directly or as the projectiles it launches in"
            + " the same tick, the hit points and damage at the character's level, the removal"
            + " notice dropping a reference to an entity that left and starting the target-lost"
            + " countdown, and the resume that follows; while deploying, no targeting visit, and"
            + " a movement visit that asks for no route and no speed, so only a push moves it;"
            + " while waiting its turn to deploy, neither visit, the state visit counting the"
            + " wait down into the deploying state, which starts the deploy countdown; its death"
            + " switching its movement component off for the rest of the tick; the lane its"
            + " placement works out; a spawned child walking at once or set deploying, its"
            + " registration visit inside the spawning pass and its first-tick immunity; a"
            + " building standing without a movement component while it deploys, held by the"
            + " building reference battles, among them card_Tombstone; a row swap taking the new"
            + " row's radius, mass, speed and targeting columns at once, the reference cleared"
            + " raw and a kept target stored again through the setter, held by"
            + " BattleChangeDataTest; the starting-attack row queued on the character at the"
            + " first attack step, at each hit and on a new reference in range, running in its"
            + " phase-2 pass, and the attack sequence entry at the index launching in place of"
            + " the row's projectile, held by the reference battle evo_archer_vs_musketeer; a"
            + " continuous-damage attacker's ramp, the index the window its attack timer has"
            + " reached on every attack step, reset for a new reference or none, as a stun drops"
            + " it, and as its reference's shield breaks, kept through its reference's death, and"
            + " its range 500 less while it walks, to itself and to the rest of the battle, held"
            + " by status_inferno_tower_retarget_skeletons_giant,"
            + " status_inferno_tower_ramp_zap_reset, status_inferno_dragon_ramp_retarget_stun and"
            + " card_MightyMiner_until_stop; a spawned child without a speed standing, and one"
            + " without hit points deploying, as the bottle of card_RageBarbarian does; a death"
            + " spawn child put on the dying unit and flown back to its ring point in steps of"
            + " 250, held by golem_death_pushes_minipekka_pekka; and an object without hit points"
            + " running its death slot as its deploy ends and leaving at the next cleanup, held"
            + " by giant_skeleton_bomb_pushes_knight; a building's deploy ending in the standing"
            + " state through the resume, its targeting from the next tick and its attacks as a"
            + " tower's, the minimum range with the collision radius added, the lifetime decay in"
            + " the third component pass from the tick after the deploy and its death with the"
            + " death slot and no death handler, and the live spawner from the deploy end - its"
            + " timer, its waves and its children in front - held by card_Cannon, card_Tombstone,"
            + " card_GoblinHut and card_Mortar; a walking unit's own spawner, stepping from its"
            + " deploy end whether it walks or attacks, its waves on the ring turned by its angle"
            + " shift and facing, held by card_Witch and card_DarkWitch; a card play's riders,"
            + " made as the unit starts deploying and queued ahead of it, placed around it a tick"
            + " behind, untouchable, untargetable and out of collisions, targeting and attacking"
            + " on their own, and let go in the cleanup that removes their parent, their death"
            + " slot run and themselves removed in that cleanup, held by card_GoblinGiant; the"
            + " combat gate at the state visit's tail, which drops a dead character's reference"
            + " and switches its targeting off, held by every reference battle's death ticks; its"
            + " buffs scaling its speed budget and its attack timer, held by card_Rage,"
            + " spell_rage_into_push, card_Zap and card_Poison, and the follower's step, held by"
            + " card_Clone and spell_clone_into_push, the deploy step and the spawner's rate,"
            + " which no run holds; a clone made by a Clone - 1 hit point of 1, the shield it"
            + " keeps, the Clone's level, the buffs it copies, its clone state with its targeting"
            + " off and its reference kept, its move apart from its original and the resume that"
            + " ends it - held by card_Clone, spell_clone_into_push and"
            + " card_item_clone_goblins_minions, and a clone's death spawns made clones, not held"
            + " by a recorded battle; an air unit created at its flying height and keeping it,"
            + " its layer answered from it - one route node, the flat endpoint rank, flight over"
            + " water, contact only with units on its side of height 0, targets by the"
            + " validator's air and ground pairing, projectiles from its height - held by"
            + " card_Minions, troops_air_vs_air_targeting, card_Balloon,"
            + " troops_hog_and_balloon_pass_defenders, troops_lavahound_and_giant_skeleton_deaths"
            + " and card_BabyDragon; a charge built by the steps it walks, its doubled speed, its"
            + " strike-now byte landing the first hit at once and that hit's special damage, held"
            + " by card_Prince, card_DarkPrince and status_zap_stuns_prince_charge; a river jump"
            + " over the water its route crosses, from the node before the water to the first"
            + " land cell beyond it at its jump speed, held by card_HogRider and"
            + " grid_zap_on_hog_river_jump; a dash, its wind-up standing it still inside its"
            + " ring, its start short of its reference by both radii, its flight at its jump"
            + " speed with its stop in range or its constant time and height, its landing hit on"
            + " its reference or over its radius with a push, its landing hold, its immunity"
            + " while it dashes and after, and the resume when it loses its reference, held by"
            + " card_Assassin, grid_clone_over_bandit_dash and grid_log_over_bandit_dash_end; the"
            + " Golden Knight's chained dash - its ability's gate within its dash range, each"
            + " dash start's count, hit list and first vector, the next dash as it leaves the"
            + " dashing state, the dash end's reset and no push while it dashes - held by the"
            + " reference battles ability_golden_knight, card_GoldenKnight and tv_replay_019, its"
            + " tenth dash and the stop at a crown tower by GoldenKnightChargeTest, while the"
            + " later of two equally near objects, the forward test and the reset as the"
            + " targeting component is switched off are held by no run; a card play's rider that"
            + " targets troops only, its buff priority fed, held by card_RamRider and"
            + " golden-gaps-v1/ram_rider_vs_knights; an elixir collector's payout to its king,"
            + " held at the cap and paid once the king can take it, and an Elixir Golem's death"
            + " paying the killing side, held by cg_elixir_collector_played,"
            + " card_Elixir_Collector and card_ElixirGolem (the carry a payout leaves when the"
            + " spawn step does not divide the generation time is held by no run); the building's"
            + " targeting and attack there rest on the verified translations, not on a recorded"
            + " battle. The Kamikaze hit's end, the unit's kill of itself after its hit, is held"
            + " by card_BattleRam, card_FireSpirits, card_Wallbreakers and card_IceSpirits; a"
            + " Kamikaze row with a time, its hit killing nothing and its state visit draining it"
            + " from that tick, and a flying row's direct path to its attack range from its"
            + " reference, held by card_SkeletonBalloon and evo_skeletonballoon_vs_musketeer."
            + " Hovering over the river, the buff while not attacking - taken at its creation,"
            + " off in the state visit of a hit's tick, back after its row's time once its attack"
            + " ends - and its answer to an asker while invisible, held by card_Ghost and"
            + " evo_ghost_vs_musketeer; the same with a row time of 50, the buff back the tick"
            + " its attack ends and the hits it takes before its first hit reduced, held by"
            + " knight_evolved_third_play; a troop's area object as a card play deploys it and an"
            + " area effect after each direct hit, held by card_BattleHealer and"
            + " random_battle16_s0025. A buff while not attacking whose row has no range gate,"
            + " taken off in the state visit of the hit that kills the unit and kept by a unit an"
            + " area kills, held by card_SuspiciousBush and random_battle16_s0004. A hiding"
            + " building's deploy end running the combat gate and its targeting visit, its hide"
            + " counter, hidden only at its hide time, its targets kept with no range extension"
            + " as a building's, and its hidden answer to every asker but an area effect that"
            + " reaches hidden units, whose damage and buff get through, held by card_Tesla and"
            + " grid_snowball_on_hidden_tesla. A hiding building's actions as it starts to hide"
            + " and as it rises, scheduled on itself and run in its phase-3 pending pass, held by"
            + " evo_tesla_vs_giant and evo_tesla_ring_giant_musketeer; their cause, itself, which"
            + " the evolved Tesla's ring does not read, by no run. Refused for a clone: a"
            + " building, a unit whose hit destroys it, a champion, and one still deploying or"
            + " running an air-to-ground action. A clone of a row with a cloned version, of that"
            + " row at its rarity, its runs left to the unit and the unit's buffs written over its"
            + " own, is held by four recorded battles not yet in the reference set: the evolved"
            + " Ghost, the evolved Wizard with its shield up and broken, and the evolved Barbarians;"
            + " its own row's starting action, scheduled as the fold starts it, by none. A clone's"
            + " death spawn with a"
            + " deploy time, a clone made deploying - 1 hit point of 1, its countdown as any"
            + " unit's, out of collision until it ends, hit by an area while it deploys - is held"
            + " by two recorded battles not yet in the reference set, a Goblin Giant's clone"
            + " killed over its riders by Arrows and by a Zap. Held by no run: a spawner's start time other than 0, the not-attacking countdown held"
            + " outside the attacking state by a reference within the attack range or by the"
            + " touch test, a top-side building's in-front point, a Kamikaze end after a"
            + " cancelled hit, and the facing a death-spawned child takes with a deploy time. A"
            + " reflecting unit's reflect, the Electro Giant's: the buff on every reflected hit"
            + " and the damage once per attack, onto the character, building or tower behind a"
            + " hit, or a projectile's root, inside its reach, held by"
            + " golden-gaps-v1/electro_giant_struck_by_knights and card_ElectroGiant; an area"
            + " effect's typed hit on it (a Zap, a Freeze, an Electro Wizard's deploy zap),"
            + " which strikes nothing back, held by recorded battles not yet in the reference"
            + " set; a typed hit a character deals, a kill by a killer or a character's buff"
            + " damage on it, and a reflect inside a reflect, are refused. A target indicator attack's run, the Goblin Machine's rocket: its"
            + " targeting component off while it deploys, its ring query, signals and shots, and"
            + " its turn toward its reference at each attack start, which only such a unit takes,"
            + " held by card_GoblinMachine and random_battle16_s0025; a clone running one, and a"
            + " turn toward no reference, are refused. A lane switch, the Mighty Miner's: across"
            + " to the mirror of its position, hidden and routing at its own speed, the"
            + " projectiles aimed at it dropped, its re-deploy on arrival dropping its reference,"
            + " and the bomb it leaves on its spot, held by ability_mighty_miner,"
            + " filter_freeze_over_mightyminer_lane_switch and"
            + " grid_rage_over_miner_tunnel_and_lane_switch; a spell passing it by and the edge's"
            + " clamp by unit tests alone; a row that stays visible across, and a deploy time for"
            + " the character left behind, are refused. The Monk's ability: its effect run inside"
            + " the state visit, before the cast's end is tested, its buff against damage and"
            + " pushes, the area effect that follows it and deflects enemy projectiles, and its"
            + " follow-up state, the targeting component off with the reference kept and the"
            + " ability's tags on from the next tick, held by ability_monk and"
            + " random_battle16_s0048; the tags and the refused push by BattleMonkTest. The"
            + " Skeleton King's souls: one for each death the death slot's notice tells it of, of"
            + " either side, not a clone, a building or a row that ignores resurrection, wherever"
            + " it falls, spent with its area effect's lifetime as the ability fires, held by"
            + " ability_skeleton_king, card_SkeletonKing and random_battle16_s0035; a King no"
            + " controller follows collecting none, held by every reference battle with deaths"
            + " and no Skeleton King, among them card_Tombstone; the cap and the deaths that"
            + " count none by BattleSkeletonKingTest; a clone of a King, which collects none, by"
            + " no run. Refused: the columns its row sets that the battle does not model (a"
            + " shield's push or action as it breaks, hiding before its first hit, the actions as"
            + " a hiding row rises and starts to hide, a buff at a share of its hit points, a"
            + " completed charge's action, a dash's contact damage, fixed distance, area effect"
            + " or closing action, a limit on the elixir it makes, a spawner's launches, second"
            + " and third characters, limit, push and deploy for its children, a fixed priority"
            + " for them), a charge on a unit that fires, a Kamikaze hit's end on a unit carrying"
            + " a death-spawn buff or a shield, a Kamikaze drain on a unit without hit points or"
            + " with a shield, a lifetime's death with a death action, an attack sequence whose"
            + " mode moves the index itself other than a continuous-damage attacker's or whose"
            + " entries set more than a projectile, a damage and such an attacker's windows, a"
            + " morph of a unit such an attacker references, an action run as it attacks, and a"
            + " row that attaches riders placed directly, spawned or waiting its turn, a buff"
            + " applied to a rider other than through its parent, a rider whose parent may not"
            + " attack, and a swap that builds or frees the movement component or reaches a"
            + " lifetime, a spawner, a building or a flying row (but one flying at the same"
            + " height), a champion, another deploy time, a charge, a river jump (but one alike)"
            + " or a dash; a shot whose projectile sets a column its flight or impact does not"
            + " model; and a buff a character's targeting passes over, applied to anyone. Not"
            + " modelled yet: the registration visit of a unit a card play creates, which meets"
            + " an empty index, the hit-points visit's dedupe expiry, and the columns its data"
            + " does not carry: the stop time after an attack and the ones that restrict what a"
            + " unit may target beyond buildings only, which it carries.")
public class CharacterEntity extends WorldEntity {

  /** Slot of the targeting component. */
  public static final int TARGETING_SLOT = 0;

  /** Slot of the movement component. */
  public static final int MOVEMENT_SLOT = 1;

  /** Slot of the hit-points component, whose visit runs the lifetime decay. */
  public static final int HIT_POINTS_SLOT = 2;

  /** The length the targeting visit's turn scales the facing to. */
  private static final int FACING_LENGTH = 256;

  /** How far beyond its attack range a dash's single landing hit still reaches its reference. */
  private static final int DASH_HIT_EXTENSION = 500;

  /** Where a landing hold starts; the state visit adds 50 a visit until the landing time. */
  private static final int LANDING_HOLD_START_MS = 50;

  /** The follower's time step before the buffs scale it. */
  private static final int FOLLOWER_STEP = 100;

  /** The deploy countdown's step before the buffs scale it, for a row whose speed scales it. */
  private static final int DEPLOY_STEP_MS = 50;

  /** The working state of the character's two components and its state visit. */
  @Getter private GridUnitState unit;

  /**
   * What a pushback request asks of the character: whether its row ignores pushback, and whether a
   * buff it carries does.
   */
  private final PushbackQueries pushbackQueries =
      new PushbackQueries() {
        @Override
        public boolean ignoresPushback() {
          return getData().ignorePushback();
        }

        @Override
        public boolean buffRefusesPushback() {
          return getBuffs().ignoresPushBack();
        }
      };

  /** Applies every state change the character asks for, with the actions the change carries. */
  private final GridStateSetter setter;

  /** The targeting component, which a hiding row's deploy end also visits from the state visit. */
  private final TargetingComponent targetingComponent;

  /**
   * The movement budget the last movement visit asked for, in game units per tick; zero when it
   * asked for none, as a standing or deploying character's visit does.
   */
  @Getter private int speedBudget;

  /** True for a spawned character the holder's fold starts as it admits it. */
  private boolean startOnAdmission;

  /** The children linked into this character's group, newest first. */
  private final List<CharacterEntity> group = new ArrayList<>();

  /** The character whose group this one is linked into, or null for none. */
  private CharacterEntity groupSource;

  /**
   * Whether the character is in the group chain of the card that made it: a card that is a group
   * links every unit it makes after the one made before it.
   */
  private boolean chained;

  /** The unit before this one in its card's group chain, or null for the first. */
  private CharacterEntity chainPrevious;

  /** The unit after this one in its card's group chain, or null for the last. */
  private CharacterEntity chainNext;

  /**
   * The spawner's timer: what is left before its next firing, in milliseconds, from its row's start
   * time at placement.
   */
  private int spawnTimer;

  /** An elixir collector's timer: what it has counted toward its next payout, in milliseconds. */
  private int collectorTimerMs;

  /** How many children of the current wave the spawner has made. */
  private int spawnWaveMade;

  /**
   * Which of its row's spawn characters the spawner's current wave makes: 0 for SpawnCharacter, 1
   * for SpawnCharacter2, 2 for SpawnCharacter3. It moves on, in turn over the rows set, as a wave
   * is complete.
   */
  private int spawnRowTurn;

  /**
   * How many firings a limited spawner has left, from its row's limit at placement; 0 for a spawner
   * without a limit, which never counts it.
   */
  private int spawnsLeft;

  /** True once a limited spawner's row has it leave as its firings are spent. */
  private boolean destroyedAtLimit;

  /** The character this one rides on, or null while it rides on none. */
  @Getter private CharacterEntity parent;

  /** Its angle on its parent's ring as it was made, which gives its share of the arc it sits on. */
  @Getter(AccessLevel.PACKAGE)
  private int attachAngle;

  /** The characters riding on this one, in the order they were made. */
  private final List<CharacterEntity> riders = new ArrayList<>();

  /** True once it has been let go by its parent, which removes it at the same cleanup. */
  private boolean released;

  /** True when its charge was complete at the end of its last movement visit. */
  private boolean charged;

  /**
   * The countdown to its buff while it is not attacking, in milliseconds: one tick from each hit,
   * its row's time from the end of each attack, counted down by the state visit while it neither
   * attacks nor has a reference in its attack range.
   */
  @Getter private int notAttackingTimerMs;

  /**
   * True once a Kamikaze row's hit has ended: from then on the state visit of a row with a Kamikaze
   * time drains its hit points.
   */
  @Getter private boolean kamikazeHitEnded;

  /** Milliseconds its buff while it is not attacking lasts when it is created with it. */
  private static final int START_BUFF_TIME_MS = 100_000;

  /** Milliseconds its buff while it is not attacking lasts when its countdown gives it. */
  private static final int NOT_ATTACKING_BUFF_TIME_MS = 180_000;

  /** Milliseconds its countdown is set to by a hit, one tick. */
  private static final int HIT_NOT_ATTACKING_MS = 50;

  /**
   * The character's answers to its movement pass's requests: a state change goes to its state
   * setter at once, and a completed charge is told to the world's observers and schedules the
   * action the row runs on it.
   */
  private final MovementRequests movementRequests =
      new MovementRequests() {
        @Override
        public void requestState(int state) {
          int from = getView().getState();
          setter.setState(getView(), state);
          world.movementStateRequested(CharacterEntity.this, from, getView().getState());
        }

        @Override
        public void chargeCompleted() {
          world.chargeCompleted(CharacterEntity.this, unit.movement().getChargeProgress());
          runChargeAction();
        }

        @Override
        public void dashLanded() {
          landDash();
        }
      };

  /**
   * The character's dash, for a row with a dash cooldown: its range check and its start. A buff
   * that pulls, which would hold the dash back, is refused with its row.
   */
  private final SelectionChain.Dasher dasher =
      new SelectionChain.Dasher() {
        @Override
        public boolean inDashRange(TargetView reference) {
          UnitData data = getData();
          if (unit.targeting().getDashWindupMs() > 0) {
            return true;
          }
          if (SpeedBudget.speedModifier(getBuffs().speedPercents(), data.speed()) < 1) {
            return false;
          }
          return RangeTest.rangeTest(
              reference,
              getView().getX(),
              getView().getY(),
              data.dashMaxRange(),
              data.collisionRadius() + data.dashMinRange(),
              false);
        }

        @Override
        public void startDash(int x, int y, int radius) {
          GridEntity view = getView();
          int fromX = view.getX();
          int fromY = view.getY();
          TargetView reference = unit.targeting().getReference();
          DashStart.start(
              view,
              isActive(MOVEMENT_SLOT) ? unit.movement() : null,
              unit.movementConfig(),
              x,
              y,
              radius,
              1,
              world.getGrid().getWidth(),
              world.getGrid().getHeight(),
              setter);
          world.dashStarted(CharacterEntity.this, reference, fromX, fromY, x, y);
        }
      };

  /**
   * Creates a character at its deploy position, deploying.
   *
   * @param world the battle's shared arena state
   * @param data the character's published columns; only ground units and buildings are supported
   * @param name the character's unique name within the battle
   * @param side the side that owns the character
   * @param x deploy position in game units
   * @param y deploy position in game units
   * @param level the character's level, counted from 1
   */
  public CharacterEntity(
      BattleWorld world, UnitData data, String name, int side, int x, int y, int level) {
    this(world, data, name, side, x, y, level, -1, -1);
  }

  /**
   * Creates a character as a card play places it: in the lane the play gives it, and either
   * deploying or waiting its turn to deploy.
   *
   * @param world the battle's shared arena state
   * @param data the character's published columns; only ground units and buildings are supported
   * @param name the character's unique name within the battle
   * @param side the side that owns the character
   * @param x position in game units
   * @param y position in game units
   * @param level the character's level, counted from 1
   * @param lane the lane the play gives it, or -1 for the lane of the road nearest to it
   * @param waitMs how long it waits before it starts deploying, or -1 to deploy at once
   */
  public CharacterEntity(
      BattleWorld world,
      UnitData data,
      String name,
      int side,
      int x,
      int y,
      int level,
      int lane,
      int waitMs) {
    super(
        world,
        data,
        createView(world.getTileMap(), data, name, side, x, y),
        targetingConfig(data),
        level);
    if (!data.unmodelledColumns().isEmpty()) {
      throw new UnsupportedOperationException(
          data.name() + " sets columns the battle does not model: " + data.unmodelledColumns());
    }
    refuseAttack(data);
    world.characterMade();
    // The level setter copies the spawner's start time into its timer, and its limit into the
    // count of firings it has left.
    this.spawnTimer = data.spawnStartTimeMs();
    this.spawnsLeft = data.spawnLimit();

    GridEntity view = getView();
    TargetingState targeting = getTargeting();
    targeting.setMovementComponentActive(data.hasMovementComponent());
    this.unit =
        new GridUnitState(
            view,
            MovementState.forSide(side, x, y),
            targeting,
            new StateTimers(),
            // The movement config's flying height is read only with direct paths, which send a
            // flying unit straight at the point at its attack range from its reference; its stop
            // and wait make the follower walk in bursts, its charge range builds the charge, its
            // jump leaps the river and gives a dash its height, its constant dash time times a
            // dash, and hovering lets its route cross water.
            MovementConfig.forGroundUnit(data.stopMovementAfterMs(), data.waitMs())
                .withFlight(data.flyingHeight(), data.flyDirectPaths())
                .withCharge(data.chargeRange())
                .withJump(data.jumpEnabled(), data.jumpHeight())
                .withDashConstantTime(data.dashConstantTimeMs())
                .withSpawnPathfindSpeed(data.spawnPathfindSpeed())
                .withIngamePathfindSpeed(data.ingamePathfindSpeed())
                .withEntersWaterWhileSpawnPathfinding(data.spawnPathfindMorph() != null)
                .withHovering(data.hovering()),
            SpeedConfig.forGroundUnit(data.speed())
                .withChargeMultiplier(data.chargeSpeedMultiplier())
                .withJumpSpeed(data.jumpSpeed())
                .withSpawnPathfindSpeed(data.spawnPathfindSpeed())
                .withIngamePathfind(data.ingamePathfindSpeed(), data.ingamePathfindVisible()),
            StateVisitConfig.forGroundUnit(data.deployTimeMs())
                .withDash(data.dashLandingTimeMs(), data.dashImmuneToDamageTimeMs())
                .withSpawnPathfindMorph(data.spawnPathfindMorph() != null)
                .withIngamePathfindArrival(data.ingamePathfindStopDeploys())
                .withHidesWhenNotAttacking(data.hidesWhenNotAttacking())
                .withAbility(
                    data.ability() != null ? data.ability().gameTagsWhileAbilityActive() : 0L),
            getSelection(),
            getTargetView());
    this.setter =
        new GridStateSetter(
            view,
            unit.movement(),
            targeting,
            this::movementChain,
            data.deployTimeMs(),
            unit.movementConfig());
    // A buff may give a row without a charge range one, which the setter's charge reset reads.
    setter.setChargeRangeFromModifiers(() -> getBuffs().overrideChargeRange());
    // A unit with an ability casts through its setter, which seeds the cast's countdowns and ends a
    // change into or out of the cast with the combat gate.
    // A unit whose dashes chain starts its chain's next dash, or ends the dash, as it leaves the
    // dashing state.
    if (data.dashCount() >= 1) {
      setter.setDashExit(
          new GridStateSetter.DashExit() {
            @Override
            public boolean chain(int newState) {
              return chainDash(newState);
            }

            @Override
            public void end() {
              dashReset();
            }
          });
    }
    if (data.ability() != null) {
      setter.setCasting(
          new GridStateSetter.Casting(
              unit.timers(),
              data.ability().castTimeMs(),
              data.ability().triggerDelayMs(),
              false,
              this::stateTailGate,
              data.ability().abilityStateDurationMs(),
              this::abilityFired));
    }
    // Going underground or across the arena, the unit is dropped by every projectile aimed at it.
    setter.setPathfindEntry(() -> world.pathfindEntered(this));
    // An ability that switches lanes sends the unit into the in-game pathfinding state, whose
    // arrival ends with the combat gate on the targeting component's own switch.
    if (data.ability() != null && data.ability().switchLanes()) {
      setter.setIngamePathfindExitGate(
          () -> combatGate(isActive(TARGETING_SLOT), setter::prepareRoute));
    }
    // A row with riders, an area object or a push makes them each time it enters the deploying
    // state through its setter, a troop's as a building's: a unit that waited its turn enters it
    // there.
    if (data.spawnAttach() || data.spawnAreaObject() != null || data.pushesOnDeploy()) {
      setter.setDeployingEntry(this::enteredDeploying);
    }
    // A hook's states switch its components, check its reference and its cell, and end with the
    // combat gate.
    setter.setFollowing(new HookStates());
    // Who may select, hit or buff it is its own answer, asked with the asker.
    getTargetView().setAcceptance(this::accepts);
    // A row untargetable when spawned starts with the immunity of a death spawn's children, set by
    // the constructor whatever makes it.
    if (data.untargetableWhenSpawned()) {
      startSpawnImmunity();
    }
    // Leaving the attacking state starts the countdown to a row's buff while it is not attacking.
    if (data.buffWhenNotAttacking() != null) {
      setter.setAttackingExit(() -> notAttackingTimerMs = data.buffWhenNotAttackingTimeMs());
    }
    // The movement component starts tracking a charge for a row with a charge range.
    if (data.chargeRange() != 0) {
      unit.movement().setChargeProgress(0);
    }
    if (lane >= 0) {
      view.setLane(lane);
    }
    if (waitMs >= 0) {
      // Waiting its turn: the elapsed-time field holds the wait, which the state visit counts
      // down; at zero the unit enters the deploying state, whose countdown the setter seeds.
      // Its movement component is switched off until then.
      view.setState(GridEntityState.WAITING_TO_DEPLOY);
      view.setDeployCountdown(0);
      view.setDelay(waitMs);
      view.setMovementActive(false);
    }
    checkArgument(
        !data.building() || waitMs < 0,
        () -> data.name() + " is a building, whose wait to deploy is not modelled");
    unit.selection().setStateSetter(setter);
    if (data.onStartingAttackAction() != null) {
      // The visit asks for it on the first attack step and on each step that completes a hit, the
      // setter on a new reference already in range; either way it is queued on the character, the
      // character as its cause, and runs in its phase-2 pending pass of the tick.
      unit.selection().setOnStartingAttack(this::startingAttack);
    }
    // The visit's turn writes the facing a target indicator attack's shot reads.
    unit.selection().setTurn(this::turnToward);
    unit.selection().getOutcome().setRoutePreparer(setter::prepareRoute);
    if (data.dashCooldown() > 0) {
      unit.selection().setDasher(dasher);
    }

    targetingComponent = new TargetingComponent();
    attach(targetingComponent);
    if (data.hasMovementComponent()) {
      attach(new MovementComponent());
    }
    if (getHitPoints() != null) {
      attach(new HitPointsComponent());
    }
    // The level setter's tail: the row's starting buff, then its buff while not attacking.
    if (data.startingBuff() != null) {
      world.startingBuff(this);
    }
    startNotAttacking();
  }

  /**
   * The level setter's tail for a row with a buff while it is not attacking: the buff at once, for
   * 100,000 ms, from the character itself at its level and for its side, when the row starts with
   * it; otherwise the countdown loaded with the row's time.
   */
  private void startNotAttacking() {
    UnitData data = getData();
    if (data.buffWhenNotAttacking() == null) {
      return;
    }
    if (data.startWithBuffWhenNotAttacking()) {
      world.notAttackingBuff(this, START_BUFF_TIME_MS);
    } else {
      notAttackingTimerMs = data.buffWhenNotAttackingTimeMs();
    }
  }

  /**
   * The state visit's section for a row with a buff while it is not attacking. While it attacks, a
   * countdown a hit set is counted down and at 0 the buff's instances are taken off: a hitting unit
   * loses the buff in the state visit of its hit's tick. Otherwise, unless its reference is within
   * its attack range, the countdown runs while the buff is not listed - on its first visit too -
   * and at 0 the buff is applied for 180,000 ms, from the character itself at its level.
   */
  private void notAttackingSection() {
    String buff = getData().buffWhenNotAttacking();
    if (buff == null) {
      return;
    }
    GridEntity view = getView();
    if (view.getState() == GridEntityState.ATTACKING) {
      if (getBuffs().carries(buff) && notAttackingTimerMs >= 1) {
        notAttackingTimerMs -= HIT_NOT_ATTACKING_MS;
        if (notAttackingTimerMs <= 0) {
          notAttackingTimerMs = 0;
          getBuffs().removeRow(buff);
        }
      }
      return;
    }
    TargetingState t = getTargeting();
    TargetView reference = isActive(TARGETING_SLOT) ? t.getReference() : null;
    // A row with the range gate holds the countdown while the reference is within its attack
    // range; a row without it, while the unit touches the reference within half its sight range.
    if (reference != null
        && (getData().buffWhenNotAttackingUseAttackRange()
            ? RangeTest.referenceInRange(t, reference, 0)
            : RangeTest.touch(t, reference))) {
      return;
    }
    if (getBuffs().carries(buff)) {
      return;
    }
    if (notAttackingTimerMs > 0 || view.getDelay() == 0) {
      notAttackingTimerMs -= HIT_NOT_ATTACKING_MS;
      if (notAttackingTimerMs <= 0) {
        notAttackingTimerMs = 0;
        world.notAttackingBuff(this, NOT_ATTACKING_BUFF_TIME_MS);
      }
    }
  }

  /**
   * A hit its tags let through sets the countdown of a row's buff while not attacking to a tick.
   */
  @Override
  protected void hitAllowed() {
    if (getData().buffWhenNotAttacking() != null) {
      notAttackingTimerMs = HIT_NOT_ATTACKING_MS;
    }
  }

  /**
   * Creates a character as a spawn creates it: in the lane of its own position and, as the level
   * setter leaves a unit with a speed, walking at once with every component on. The spawner then
   * sets it deploying when its row asks.
   *
   * @param world the battle's shared arena state
   * @param data the child's published columns; only ground units are supported
   * @param name the child's unique name within the battle
   * @param side the side that owns the child, its source's
   * @param x position in game units, already inside the arena
   * @param y position in game units, already inside the arena
   * @param level the child's level, counted from 1
   */
  static CharacterEntity spawned(
      BattleWorld world, UnitData data, String name, int side, int x, int y, int level) {
    return spawned(world, data, name, side, x, y, level, -1);
  }

  /**
   * Creates a character as a spawn creates it, in a lane its spawner works out for it, and
   * otherwise as {@link #spawned(BattleWorld, UnitData, String, int, int, int, int)} does.
   *
   * @param world the battle's shared arena state
   * @param data the child's published columns; only ground units are supported
   * @param name the child's unique name within the battle
   * @param side the side that owns the child, its source's
   * @param x position in game units, already inside the arena
   * @param y position in game units, already inside the arena
   * @param level the child's level, counted from 1
   * @param lane the lane the spawner gives it, or -1 for the lane of the road nearest to it
   */
  static CharacterEntity spawned(
      BattleWorld world, UnitData data, String name, int side, int x, int y, int level, int lane) {
    if (data.spawnAttach()) {
      throw new UnsupportedOperationException(
          data.name() + " is spawned and would make its riders as it deploys, which is not held");
    }
    return made(world, data, name, side, x, y, level, lane);
  }

  /**
   * Creates a Clone's clone of a unit as a spawn creates a character, a row with riders too: a
   * clone never enters the deploying state, whose entry makes a row's riders, so a carrier's clone
   * makes none of its own and rides the clones of its original's riders instead.
   *
   * @param world the battle's shared arena state
   * @param data the clone's row
   * @param name the clone's unique name within the battle
   * @param side the side that owns the clone
   * @param x position in game units, already inside the arena
   * @param y position in game units, already inside the arena
   * @param level the clone's level, counted from 1
   */
  static CharacterEntity cloned(
      BattleWorld world, UnitData data, String name, int side, int x, int y, int level) {
    return made(world, data, name, side, x, y, level, -1);
  }

  /** A character made as a spawn makes it: walking with a speed, standing without, deployed. */
  private static CharacterEntity made(
      BattleWorld world, UnitData data, String name, int side, int x, int y, int level, int lane) {
    CharacterEntity child = new CharacterEntity(world, data, name, side, x, y, level, lane, -1);
    // The level setter leaves a unit with a speed walking and one without standing.
    child.getView().setState(data.speed() >= 1 ? GridEntityState.MOVING : GridEntityState.STANDING);
    child.getView().setDeployCountdown(0);
    return child;
  }

  /**
   * Puts a death spawn's child on the dying unit's point and aims it back at its own point on the
   * ring, as a death spawn that pushes its children does: its movement component flies it there in
   * steps of 250, one per movement visit, for as many steps as whole 250s fit in the distance, the
   * first in its registration visit; meanwhile it may not attack.
   *
   * @param fromX the dying unit's point, along the width
   * @param fromY the dying unit's point, along the length
   */
  void flyBackFrom(int fromX, int fromY) {
    MovementState movement = unit.movement();
    GridEntity view = getView();
    int ringX = view.getX();
    int ringY = view.getY();
    movement.setTargetX(ringX);
    movement.setTargetY(ringY);
    view.setX(fromX);
    view.setY(fromY);
    view.setPrevX(fromX);
    view.setPrevY(fromY);
    long dx = ringX - fromX;
    long dy = ringY - fromY;
    movement.setBlockCountdown((int) (FixedMath.isqrt(dx * dx + dy * dy) / DEATH_PUSHBACK_STEP));
    movement.setPushbackBudget(DEATH_PUSHBACK_STEP);
  }

  /** One step of a death spawn child's flight back to its ring point, in game units. */
  private static final int DEATH_PUSHBACK_STEP = 250;

  /**
   * Hands a played unit to its tunnel, as the construction does in place of its start: the level
   * setter has left it walking with no deploy time; it is moved onto its own king tower at its
   * flying height, its movement aimed at the placed point, and set to the spawn-pathfinding state,
   * where it walks its route hidden until it surfaces on the point.
   *
   * @param kingX its king tower's position along the width
   * @param kingY its king tower's position along the length
   * @param pointX the placed point along the width
   * @param pointY the placed point along the length
   */
  void tunnelFrom(int kingX, int kingY, int pointX, int pointY) {
    GridEntity view = getView();
    view.setState(GridEntityState.MOVING);
    view.setDeployCountdown(0);
    view.setX(kingX);
    view.setY(kingY);
    view.setZ(getData().flyingHeight());
    unit.movement().setExplicitX(pointX);
    unit.movement().setExplicitY(pointY);
    setter.setState(view, GridEntityState.SPAWN_PATHFIND);
  }

  /** Sets the character deploying through its own setter, with its row's deploy time. */
  void startDeploying() {
    setter.setState(getView(), GridEntityState.DEPLOYING);
  }

  /**
   * Gives the character a deploy time of the spawn row's own: deploying, unless it is being set up
   * as a clone, and the countdown set to that time exactly.
   */
  void deployFor(int deployTimeMs) {
    if (getView().getState() != GridEntityState.CLONE_SETUP) {
      setter.setState(getView(), GridEntityState.DEPLOYING);
    }
    getView().setDeployCountdown(deployTimeMs);
  }

  /**
   * Starts the first-tick immunity of a spawned child: until its state visit has counted past the
   * attack-finish time, the sixth visit, it refuses every character that asks to target it.
   */
  void startSpawnImmunity() {
    unit.timers().setSpawnImmune(true);
    unit.timers().setSpawnImmuneElapsedMs(0);
  }

  /** True while the character is a spawned child that may not be targeted yet. */
  public boolean isSpawnImmune() {
    return unit.timers().isSpawnImmune();
  }

  /** Whether a clone keeps a shield its original still has up: so in the standard game. */
  private static final boolean CLONE_PRESERVE_SHIELD = true;

  /**
   * Whether a clone and its original move apart, rather than both one way: so in the standard game.
   */
  private static final boolean CLONE_MOVE_PARENT = true;

  /** True for a clone: one a Clone made, or a child a clone spawned. */
  @Getter private boolean clone;

  /**
   * The souls the unit has collected for its ability's area effect: one for each death it may
   * count, spent and cleared as the ability fires.
   */
  @Getter private int souls;

  /**
   * The play that made the unit, by its king's count of card plays before it; -1 for a unit no card
   * play of a match made. A champion's controller follows the copies of its champion one play made,
   * and an ability command finds a copy by it.
   */
  @Getter @Setter private int deployIndex = -1;

  /**
   * The clone setter: the character becomes a clone of 1 hit point of 1. Its shield follows the
   * unit it stands for: none when that unit's shield is broken or it has none, its maximum 1 for a
   * row with a shield; otherwise 1 of 1 while its own shield is up, the standard game preserving a
   * clone's shield, and none without one.
   *
   * <p>Refused rather than guessed: a building, whose lifetime a clone counts differently, a unit
   * whose hit destroys it, whose projectile's spawns would be clones, and a champion, which the
   * ability controller leaves out.
   *
   * @param original the unit it stands for, or null for none
   */
  void markClone(WorldEntity original) {
    UnitData data = getData();
    if (data.building() || data.kamikaze() || data.champion()) {
      throw new UnsupportedOperationException(
          name()
              + " would be a clone of a building, a unit whose hit destroys it or a champion,"
              + " which is not modelled");
    }
    clone = true;
    getView().setClone(true);
    HitPoints hp = getHitPoints();
    if (hp == null) {
      return;
    }
    hp.setHitPoints(1);
    hp.setMaximum(1);
    if (original != null
        && original.getHitPoints() != null
        && original.getHitPoints().getShield() == 0) {
      hp.setShield(0);
      hp.setShieldMaximum(hp.getShieldMaximum() > 0 ? 1 : 0);
      return;
    }
    boolean kept = hp.getShield() != 0 && CLONE_PRESERVE_SHIELD;
    hp.setShield(kept ? 1 : 0);
    hp.setShieldMaximum(kept ? 1 : 0);
  }

  /**
   * The perform's tests of a Clone's action: a unit a Clone passes by, a dead unit and a rider are
   * refused, and the refusal told. A clone the battle does not model is refused outright: one made
   * by anything but an area effect, of a unit still deploying, or of a unit running an
   * air-to-ground action, which the game starts again on the clone - for a carrier, of any of its
   * riders too, each cloned with it. A row with a cloned version and the runs of every other action
   * class are modelled: the clone is made of the cloned version, and the runs stay the unit's.
   *
   * <p>The perform does not ask whether the unit is a clone itself: a clone it reaches is cloned
   * again. A Clone's area never reaches one, its filter dropping clones, so a clone the action
   * reaches is refused outright too: what the game makes of the clone of a clone is not modelled.
   */
  @Override
  public boolean mayBeCloned(ActionOwner instigator) {
    String reason = null;
    if (getData().ignoreClone()) {
      reason = "ignore clone";
    } else if (!HitPoints.alive(getHitPoints())) {
      reason = "dead";
    } else if (parent != null) {
      reason = "attached";
    }
    if (!(instigator instanceof AreaEffectEntity cause)) {
      throw new UnsupportedOperationException(
          name() + " is cloned by something other than an area effect, which is not modelled");
    }
    if (reason == null && clone) {
      throw new UnsupportedOperationException(
          name()
              + " is a clone a Clone's action reaches, which the game clones again; that is not"
              + " modelled");
    }
    if (reason != null) {
      world.cloneRefused(this, reason, cause);
      return false;
    }
    // A carrier's riders are cloned with it, each as a clone of its own, so each is held to the
    // same tests.
    List<CharacterEntity> cloned = new ArrayList<>();
    cloned.add(this);
    if (getData().spawnAttach()) {
      cloned.addAll(riders);
    }
    for (CharacterEntity unit : cloned) {
      if (unit.getView().getDeployCountdown() > 0) {
        throw new UnsupportedOperationException(
            unit.name() + " is cloned with a deploy countdown, which is not modelled");
      }
      // The creator tells each run the unit has listed of the clone; every class but the
      // air-to-ground one does nothing with it, so the runs stay the unit's. That one starts its
      // action again on the clone.
      for (ActionInstance run : unit.actionHolder().running()) {
        if (run.getAction() instanceof AirToGround) {
          throw new UnsupportedOperationException(
              unit.name()
                  + " is cloned with an air-to-ground action running, which the game starts again"
                  + " on the clone; that is not modelled");
        }
      }
    }
    return true;
  }

  @Override
  public void makeClone(ActionOwner instigator, Clone action) {
    world.makeClone(this, (AreaEffectEntity) instigator, action);
  }

  /**
   * Sets the character up as a clone, through its setter, and runs the combat gate that ends the
   * change: its targeting component goes off and keeps its reference.
   */
  void enterCloneSetup() {
    setter.setState(getView(), GridEntityState.CLONE_SETUP);
    stateTailGate();
  }

  /**
   * Starts the character's move apart from its clone or original: a run listed on its own holder,
   * toward the point the clone distances away along each axis, in steps of 500 - back toward its
   * own side for a clone, forward for the original - kept inside the arena. The point is its
   * explicit destination and its route the point's single cell. The run steps 50 ms a visit of the
   * run pass, from the next tick on; at the clone duration it resumes the character, still set up
   * as a clone, and finishes.
   *
   * <p>Refused rather than guessed: a character without its movement component on, which keeps no
   * route.
   *
   * @param fromX the original's position, along the width
   * @param fromY the original's position, along the length
   * @param action the Clone's action
   */
  void startCloneMove(int fromX, int fromY, Clone action) {
    actionHolder().list(new CloneMove(action));
    if (!getView().isMovementComponent() || !getView().isMovementActive()) {
      throw new UnsupportedOperationException(
          name() + " moves apart from its clone without its movement component, not modelled");
    }
    int direction = CLONE_MOVE_PARENT && !clone ? -1 : 1;
    // The side whose team answers 1, the bottom one, moves the other way.
    if ((side() & 1) == 0) {
      direction = -direction;
    }
    int step = direction * TileMap.CELL_UNITS;
    int width = world.getGrid().getWidth();
    int height = world.getGrid().getHeight();
    SpeedGlobals globals = SpeedGlobals.standard();
    int x = clampInto(globals.cloneDistanceX() * step + fromX, width * TileMap.CELL_UNITS - 1);
    int y = clampInto(globals.cloneDistanceY() * step + fromY, height * TileMap.CELL_UNITS - 1);
    MovementState movement = unit.movement();
    movement.setExplicitX(x);
    movement.setExplicitY(y);
    SingleNodeRoute.set(movement, getView(), unit.movementConfig(), x, y, 1, width, height);
    world.cloneMoveStarted(this, x, y);
  }

  /** A coordinate kept between 0 and the arena's last unit. */
  private static int clampInto(int value, int last) {
    return value > 0 ? Math.min(value, last) : 0;
  }

  /** The run of a clone's or its original's move apart, stepped by the run pass. */
  private final class CloneMove extends ActionInstance {

    /** Milliseconds one step adds. */
    private static final int STEP_MS = 50;

    private final int durationMs;

    /** What the steps have counted. */
    private int elapsedMs;

    CloneMove(Clone action) {
      super(action);
      this.durationMs = action.getCloneDurationMs();
    }

    @Override
    protected void update(ActionHolder holder) {
      elapsedMs += STEP_MS;
      if (elapsedMs < durationMs) {
        return;
      }
      // With its deploy countdown still running, the run holds the unit still and waits.
      if (getView().getDeployCountdown() > 0) {
        throw new UnsupportedOperationException(
            name() + "'s move apart waits on its deploy countdown, which is not modelled");
      }
      boolean resumed = getView().getState() == GridEntityState.CLONE_SETUP;
      if (resumed) {
        resume();
      }
      finish();
      world.cloneMoveEnded(CharacterEntity.this, resumed);
    }
  }

  /**
   * Starts the character as a direct placement does: its row's starting action, when it has one,
   * built for it and scheduled with the row's own delay, the character as its cause. The placement
   * is outside every pending pass, so an action with no delay waits for the character's phase-1
   * pending pass of the tick, which runs before its first component visit.
   */
  public void start() {
    if (getData().onStartingAction() == null) {
      return;
    }
    startingAction();
  }

  /**
   * Marks a character a spawn made, which the holder admits at its next cleanup's fold: the fold
   * starts it, scheduling its row's starting action then.
   */
  void startOnAdmission() {
    startOnAdmission = getData().onStartingAction() != null;
  }

  @Override
  protected void onRegistered() {
    super.onRegistered();
    if (startOnAdmission) {
      startOnAdmission = false;
      startingAction();
    }
  }

  /** Schedules the row's starting action, built for the character, the character as its cause. */
  private void startingAction() {
    BattleAction starting =
        world.getActions().build(getData().onStartingAction(), world.binding(this));
    actionHolder().schedule(starting, ActionHolder.OWN_DELAY, false, actionHolder());
  }

  /**
   * Takes another character row, as a data-changing action gives it. The target the character's
   * targeting component holds is read first. Then the row is swapped: the maximum hit points come
   * from the new row at the unchanged level and the hit points are kept; the reference is cleared
   * as it stands, with no timer reset and no route; and the collision radius, mass, speed and
   * targeting columns are the new row's from here on, so the next movement visit already moves at
   * the new speed. Those that target the unit read the new row too: a lethal damage on its way no
   * longer drops it for an attacker once its row has a lifetime, as the Goblin Demolisher's
   * kamikaze form has. Last, a target it had is kept unless the row resets it or the validator
   * refuses it, and is then stored again through the setter, which prepares its route; a target
   * given up leaves the attack timing as it was. The new row's starting action does not run.
   *
   * <p>A unit whose targeting component is switched off, as the hero Wizard's is through its cast,
   * reads no target and its component hears of no swap: the reference it holds survives as it
   * stands, whether or not the row resets the target, and the targeting columns are the new row's
   * all the same. A target that dies while it is held is then given up as any other.
   *
   * <p>A walking unit may take a building row without a speed that drains over a lifetime, as the
   * Moving Cannon breaks down: its movement component is freed, it counts as a building, and its
   * hit points drain over the new row's lifetime from its next hit-points visit, what the drain
   * carried kept. A walking unit without a lifetime may take a walking row with one, as the Goblin
   * Demolisher becomes its kamikaze form: it keeps walking, and its hit points drain the same way.
   *
   * <p>A ground unit a ground-to-air run holds in the air may take a row that flies, as the hero
   * Wizard takes its flying row at the height: its layer is the run's FORCE_IS_AIR and its height
   * the run's push, neither of which the swap touches.
   *
   * <p>A champion's slot is found before the swap, on the old row, as the slot that follows the
   * unit; once the row is swapped, a slot whose live copies held the unit works its state out at
   * once, its copies rebuilt from the live list, so a unit on a row the slot does not follow is no
   * copy of it from then on.
   *
   * <p>A ground unit without a movement component may take a ground row with a speed, as the
   * Tombstone hero's passive monster takes its active row as its ability fires: it is given a new
   * movement component, switched on, as a unit with a speed is created with one, and from the next
   * movement visit it walks at the new speed. A unit draining over a lifetime may take a row
   * without one, the same swap: the drain ends at once, the hit points kept. A building without a
   * speed or a lifetime may take another such building row, as the Tombstone hero's dummy breaks.
   * Once a unit has deployed its new row's deploy time is not read, and a row without an ability
   * may follow one with an ability while the unit is not casting.
   *
   * <p>Refused rather than guessed: any other building row, a flying row either side but those, any
   * other swap that builds or frees the movement component or reaches a lifetime, a different
   * rarity, a different deploy time while the unit deploys, and another ability. A shield keeps its
   * value, its maximum taken from the new row.
   *
   * @param rowName the name of the new character row
   * @param resetTarget true to give up the target rather than keep it
   */
  @Override
  public void changeData(String rowName, boolean resetTarget) {
    UnitData next = world.getRecords().unit(rowName);
    refuseSwap(next);
    // The slot of the side's king that follows the unit, asked on the old row: none for a clone.
    ChampionController slot = followingSlot();
    TargetingState targeting = getTargeting();
    boolean targetingOn = isActive(TARGETING_SLOT);
    TargetView target = targetingOn ? targeting.getReference() : null;
    boolean becomesBuilding = !getData().building() && next.building();
    boolean startsLifetime = getData().lifeTimeMs() == 0 && next.lifeTimeMs() > 0;
    boolean endsLifetime = getData().lifeTimeMs() > 0 && next.lifeTimeMs() == 0;
    boolean buildsMovement = !hasMovementComponent() && next.hasMovementComponent();
    swapRow(next);
    if (endsLifetime) {
      // The hit points' data hook recomputes the step from the new row, 0 without a lifetime, and
      // zeroes the carried hundredths: the drain ends at once, the hit points left as they are.
      getHitPoints().endDecay();
    }
    if (buildsMovement) {
      // A unit without a movement component that takes a row with a speed is given a new one,
      // switched on: as its creation gives one, with nothing carried over, and nothing else
      // told of it.
      attach(new MovementComponent());
      getView().setMovementComponent(true);
      getView().setMovementActive(true);
      targeting.setMovementComponentActive(true);
    }
    if (startsLifetime && !becomesBuilding) {
      // The drain from the new row's lifetime at the unchanged level, run by the hit-points visit
      // from the next pass.
      getHitPoints()
          .setDecayStep(HitPoints.decayStep(getHitPoints().getMaximum(), next.lifeTimeMs()));
    }
    if (becomesBuilding) {
      // A walking unit that takes a building row without a speed: its movement component is
      // freed, and its hit points drain over the new row's lifetime from the next hit-points
      // visit, what the old drain carried kept. It keeps its state, position, lane and level.
      getHitPoints()
          .setDecayStep(HitPoints.decayStep(getHitPoints().getMaximum(), next.lifeTimeMs()));
      detach(MOVEMENT_SLOT);
      targeting.setMovementComponentActive(false);
      getView().setMovementComponent(false);
      getView().setMovementActive(false);
      // Everything that asks whether it is a building reads the new row from here on.
      getView().setBuilding(true);
      getView().setOccludes(true);
    }
    // The targeting component hears of the swap first, when it is switched on: its reference is
    // cleared as it stands. A component that is off hears of nothing, so the reference survives
    // the swap, a target the row resets included (none was read above, so none is stored again).
    if (targetingOn) {
      targeting.setReference(null);
      targeting.setKeptByPendingDamageCheck(false);
    }
    TargetingConfig swapped = targetingConfig(next);
    targeting.setConfig(swapped);
    // What the unit's attackers read of it, its lifetime among them, is the new row's from here on.
    getTargetView().setConfig(swapped);
    getView().setCollisionRadius(next.collisionRadius());
    getView().setMass(next.mass());
    // The occlusion query reads the row the unit has now.
    getView().setOccluder(next.occluder());
    // The slot that followed the unit hears of the swap once the row is the new one.
    if (slot != null) {
      slot.dataChanged(this);
    }
    // The movement visits read the charge range and the river jump of the row the unit has now.
    MovementConfig movementConfig =
        unit.movementConfig()
            .withCharge(next.chargeRange())
            .withJump(next.jumpEnabled(), next.jumpHeight());
    setter.setMovementConfig(movementConfig);
    unit =
        new GridUnitState(
            unit.entity(),
            unit.movement(),
            unit.targeting(),
            unit.timers(),
            movementConfig,
            SpeedConfig.forGroundUnit(next.speed()),
            StateVisitConfig.forGroundUnit(next.deployTimeMs()),
            unit.selection(),
            unit.view());
    if (target != null) {
      SelectionChain selection = unit.selection();
      TargetView kept =
          !resetTarget && selection.validate(target, ReferenceValidator.MODE_RECHECK)
              ? target
              : null;
      ReferenceSetter.setReference(
          targeting, kept, false, false, false, selection, selection.getOutcome());
    }
  }

  /**
   * Taunts the unit onto an object, as a taunt's perform does: the run, armed at once, which the
   * holder lists. The perform is told to the observers first.
   *
   * <p>Refused rather than guessed: a building, which no reference holds, a unit that rides on
   * another or carries riders, whose riders the perform would taunt too, a unit in a pathfinding
   * state, and an object to force onto that is not in the battle or flies, which a tag may count as
   * on the ground.
   */
  @Override
  public ActionInstance taunt(Taunt action, ActionOwner instigator, ActionOwner forced, int phase) {
    String refused = null;
    if (getData().building()) {
      refused = "a building that is not a crown tower, which";
    } else if (parent != null || !riders().isEmpty()) {
      refused = "a rider or a carrier, whose riders it would taunt too, which";
    } else if (getView().getState() == GridEntityState.SPAWN_PATHFIND
        || getView().getState() == GridEntityState.INGAME_PATHFIND) {
      refused = "a pathfinding unit, which";
    } else if (!(forced instanceof WorldEntity onto) || onto.getTargetView().air()) {
      refused = "a flying object or one not in the battle, which";
    }
    if (refused != null) {
      throw new UnsupportedOperationException(
          action.name() + " taunts " + name() + " onto " + refused + " is not modelled");
    }
    WorldEntity onto = (WorldEntity) forced;
    world.tauntPerformed(this, action.name(), phase, instigator, onto);
    TauntRun run = new TauntRun(action, this, onto);
    run.start();
    return run;
  }

  /**
   * The champion slot of the side's king that follows the character, the first before the second;
   * null for none, a clone, or a battle without a king's slots.
   */
  ChampionController followingSlot() {
    TowerEntity king = world.kingTower(side());
    if (king == null) {
      return null;
    }
    for (int n = 1; n <= 2; n++) {
      ChampionController slot = king.championSlot(n);
      if (slot != null && slot.follows(this)) {
        return slot;
      }
    }
    return null;
  }

  /**
   * Whether a ground-to-air run on the character holds it in the air: climbing or held, it raises
   * FORCE_IS_AIR on each step and pushes the character's height itself.
   */
  private boolean liftedByRun() {
    for (ActionInstance instance : actionHolder().running()) {
      if (instance instanceof GroundToAirRun run
          && (run.phase() == GroundToAirRun.CLIMBING || run.phase() == GroundToAirRun.HELD)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether a ground-to-air run on the character is bringing it back down: descending, it pushes
   * the character's height itself and raises FORCE_IS_AIR while more than 149 ms are left.
   */
  private boolean landedByRun() {
    for (ActionInstance instance : actionHolder().running()) {
      if (instance instanceof GroundToAirRun run && run.phase() == GroundToAirRun.DESCENDING) {
        return true;
      }
    }
    return false;
  }

  /** Refuses a swap whose effect is not established. */
  private void refuseSwap(UnitData next) {
    UnitData current = getData();
    // A walking unit may take a building row that stands still and drains over a lifetime, as the
    // Moving Cannon breaks into its broken cannon; no other swap to a building is established.
    boolean breaksDown =
        !current.building()
            && current.speed() != 0
            && current.lifeTimeMs() == 0
            && next.building()
            && next.speed() == 0
            && next.lifeTimeMs() > 0;
    // A walking unit may take a walking row that drains over a lifetime, as the Goblin Demolisher
    // takes its kamikaze form.
    boolean startsLifetime =
        !current.building()
            && !next.building()
            && current.speed() != 0
            && next.speed() != 0
            && current.lifeTimeMs() == 0
            && next.lifeTimeMs() > 0;
    // A flying unit may take a row that flies at the same height, as the evolved Royal Hog takes
    // its grounded row as it lands: the layer and the height are read through the row on demand,
    // and the swap touches neither the movement component nor what a run pushed.
    boolean sameFlight =
        current.air() && next.air() && current.flyingHeight() == next.flyingHeight();
    // A ground unit a ground-to-air run holds in the air may take a flying row, as the hero Wizard
    // takes its flying row at the height: its layer is the run's FORCE_IS_AIR and its height the
    // run's push, which the swap leaves alone. The movement config built from the old row reads its
    // flying height only with direct paths, so a row that flies by them is refused.
    boolean liftedToFlight =
        !current.air() && next.air() && !next.flyDirectPaths() && liftedByRun();
    // And a flying row taken that way may give way to a ground row while the run brings the unit
    // back down, as the hero Wizard takes its ground row again as its descent starts: the layer is
    // the run's FORCE_IS_AIR until the run stops raising it, then the ground row's, and the height
    // the run's push; the movement config was built from a ground row.
    boolean landedFromFlight =
        current.air() && !next.air() && !current.flyDirectPaths() && landedByRun();
    // A building without a speed or a lifetime may take another such building row, as the
    // Tombstone hero's dummy takes its broken row: neither has a movement component, and the swap
    // only recomputes the hit points' maximum.
    boolean buildingToBuilding =
        current.building()
            && next.building()
            && !current.hasMovementComponent()
            && !next.hasMovementComponent()
            && current.lifeTimeMs() == 0
            && next.lifeTimeMs() == 0;
    // A ground unit without a movement component may take a ground row with a speed, as the
    // Tombstone hero's passive monster takes its active row: the swap builds it a new movement
    // component.
    boolean buildsMovement =
        !current.building()
            && !next.building()
            && !current.air()
            && !next.air()
            && !hasMovementComponent()
            && next.hasMovementComponent();
    // A unit draining over a lifetime may take a row without one, the same monster again: the hit
    // points' data hook ends the drain.
    boolean endsLifetime =
        !current.building()
            && !next.building()
            && current.lifeTimeMs() > 0
            && next.lifeTimeMs() == 0;
    // A row with an ability may give way to one without, once the unit is not casting: the battle
    // object keeps no copy of the ability, every reader asks the row it has.
    boolean dropsAbility =
        current.ability() != null
            && next.ability() == null
            && getView().getState() != GridEntityState.CASTING;
    // The deploy time is read only as the unit enters the deploying state, so it may change once
    // the unit has deployed.
    int state = getView().getState();
    boolean deployed =
        state != GridEntityState.DEPLOYING
            && state != GridEntityState.WAITING_TO_DEPLOY
            && state != GridEntityState.CLONE_SETUP;
    // Likewise a unit that jumps the river may take a row that jumps it alike.
    boolean sameJump =
        current.jumpEnabled()
            && next.jumpEnabled()
            && current.jumpHeight() == next.jumpHeight()
            && current.jumpSpeed() == next.jumpSpeed();
    // A unit may give up its charge and its river jump for a row with neither, as the hero Dark
    // Prince takes its walking row: the swap leaves the movement component as it stands, and every
    // later visit reads the new row, so a charge under way ends at the next displacement and no
    // jump starts. Refused mid-jump, and once charged, whose strike the new row would carry.
    boolean dropsChargeAndJump =
        next.chargeRange() == 0
            && !next.jumpEnabled()
            && getBuffs().overrideChargeRange() == 0
            && state != GridEntityState.JUMPING
            && chargeProgress() < MovementState.CHARGE_COMPLETE;
    String refused = null;
    if ((next.air() || current.air()) && !sameFlight && !liftedToFlight && !landedFromFlight
        || (current.building() || next.building()) && !breaksDown && !buildingToBuilding) {
      refused = "a building or a flying row";
    } else if (!breaksDown && !buildsMovement && (current.speed() == 0) != (next.speed() == 0)) {
      refused = "a movement component built or freed";
    } else if (!breaksDown
        && !startsLifetime
        && !endsLifetime
        && (current.lifeTimeMs() != 0 || next.lifeTimeMs() != 0)) {
      refused = "a lifetime";
    } else if (current.spawnCharacter() != null || next.spawnCharacter() != null) {
      refused = "a spawner";
    } else if (current.rarity() != next.rarity()) {
      refused = "a level packed against another rarity";
    } else if (current.deployTimeMs() != next.deployTimeMs() && !deployed) {
      refused = "another deploy time";
    } else if (!Objects.equals(current.ability(), next.ability()) && !dropsAbility) {
      refused = "another ability";
    } else if (!dropsChargeAndJump
        && (current.chargeRange() != 0
            || next.chargeRange() != 0
            || getBuffs().overrideChargeRange() != 0
            || (current.jumpEnabled() || next.jumpEnabled()) && !sameJump)) {
      refused = "a charge or a river jump";
    } else if (current.dashCooldown() != 0 || next.dashCooldown() != 0) {
      refused = "a dash";
    }
    if (refused != null) {
      throw new UnsupportedOperationException(
          name() + " taking " + next.name() + " asks for " + refused + ", which is not modelled");
    }
  }

  /** The row the character runs as it starts an attack, built on first use. */
  private BattleAction startingAttackRow;

  /** Schedules the character's starting-attack row on itself, the character as its cause. */
  private void startingAttack() {
    if (startingAttackRow == null) {
      startingAttackRow =
          world.getActions().build(getData().onStartingAttackAction(), world.binding(this));
    }
    actionHolder().schedule(startingAttackRow, ActionHolder.OWN_DELAY, false, actionHolder());
  }

  /**
   * Refuses the parts of a character's attack that are not established: an attack sequence whose
   * mode moves the index by itself other than a continuous-damage attacker's and a static loop's
   * (the Manual mode, whose index only actions move, is accepted), and an entry's variable damage
   * time outside a continuous-damage attacker, where no window is walked. An entry without a
   * projectile or an action on a unit that fires is not refused: in an order of two or more the
   * entry's projectile decides the hit, so its hit is a direct hit. An entry's action is
   * established in place of a projectile, read from an order of two or more by an index only
   * actions move, and in a sequence of one beside the row's own projectile; one with a projectile,
   * in a continuous-damage attacker's or on a charging row is refused, and in a sequence of one
   * also on a multi-target attacker or beside a buff on damage, which the hit would apply only
   * without a projectile.
   */
  private static void refuseAttack(UnitData data) {
    AttackSequence sequence = data.attackSequence();
    boolean windowed = sequence.mode() == AttackSequence.MODE_HITTIME;
    boolean looped = sequence.mode() == AttackSequence.MODE_STATIC_LOOP;
    boolean manual = sequence.mode() == AttackSequence.MODE_MANUAL;
    String refused = null;
    if (sequence.mode() != AttackSequence.MODE_NONE && !windowed && !looped && !manual) {
      refused = "an attack sequence whose mode " + sequence.mode() + " moves the index itself";
    } else if (!sequence.replacesAttack()) {
      // In a sequence of one the entry's action is still read, beside the row's own projectile.
      for (AttackSequence.Entry entry : sequence.entries()) {
        if (entry.doAttackAction() == null) {
          continue;
        }
        if (windowed || manual || data.chargeRange() != 0) {
          refused =
              "an attack sequence entry's action in a continuous-damage attacker or on a"
                  + " charging row";
        } else if (data.multipleTargets() >= 2 || data.buffOnDamage() != null) {
          refused =
              "an attack sequence entry's action in a sequence of one on a multi-target attacker"
                  + " or beside a buff on damage";
        }
      }
    } else {
      for (int index = 0; index < sequence.order().size(); index++) {
        AttackSequence.Entry entry = sequence.entryAt(index);
        boolean acts = entry.doAttackAction() != null;
        if (!windowed && entry.overridesMore()) {
          refused = "an attack sequence entry's variable damage time outside a timer-driven mode";
        } else if (acts
            && (windowed || manual || entry.projectile() != null || data.chargeRange() != 0)) {
          refused =
              "an attack sequence entry's action with a projectile, in a continuous-damage"
                  + " attacker or on a charging row";
        }
      }
    }
    if (refused == null && data.chargeRange() != 0 && data.hasProjectile()) {
      refused = "a charge on a unit that fires, whose charged shot is not established";
    }
    if (refused != null) {
      throw new UnsupportedOperationException(data.name() + " has " + refused + ", not modelled");
    }
  }

  /** The children linked into this character's group, newest first. */
  public List<CharacterEntity> group() {
    return Collections.unmodifiableList(group);
  }

  /**
   * Links a child into this character's group right after the character, ahead of every child
   * linked before it.
   */
  @Override
  public void linkIntoGroup(SpawnHost child) {
    CharacterEntity linked = (CharacterEntity) child;
    group.add(0, linked);
    linked.groupSource = this;
    // The same link the card's construction makes: the child right after this character in its
    // chain, ahead of the one that followed it, and the child marked as in a group. This character
    // keeps its own mark, which a lone source never gets.
    linked.chained = true;
    linked.chainPrevious = this;
    linked.chainNext = chainNext;
    if (chainNext != null) {
      chainNext.chainPrevious = linked;
    }
    chainNext = linked;
    world.groupLinked(this, linked);
  }

  /**
   * The character's group chain for the group checks: its mark, and the chain from its first unit,
   * walking back along the links, then forward to the last.
   */
  @Override
  public GroupChain groupChain() {
    List<GroupChain.Member> members = new ArrayList<>();
    if (chained) {
      CharacterEntity head = this;
      while (head.chainPrevious != null) {
        head = head.chainPrevious;
      }
      for (CharacterEntity member = head; member != null; member = member.chainNext) {
        CharacterEntity built = member;
        members.add(
            new GroupChain.Member(
                new EntityFilterSubject(member),
                member.actionHolder(),
                member == this,
                row -> world.getActions().build(row, world.binding(built))));
      }
    }
    return new GroupChain(chained, members, side() & 1, getData().name());
  }

  /**
   * Links the character into its card's group chain after the unit the card made before it, as the
   * card's construction does right after making it.
   *
   * @param previous the unit made before it, or null for the first
   */
  void linkAfter(CharacterEntity previous) {
    chained = true;
    chainPrevious = previous;
    chainNext = null;
    if (previous != null) {
      previous.chainNext = this;
    }
    world.chainLinked(this, previous);
  }

  /**
   * The first unit of the character's card group chain, walking back from it; null for a character
   * in no chain.
   */
  CharacterEntity chainHead() {
    if (!chained) {
      return null;
    }
    CharacterEntity head = this;
    while (head.chainPrevious != null) {
      head = head.chainPrevious;
    }
    return head;
  }

  /** Whether the character carries the group mark: it is in a card's or a spawn's group chain. */
  boolean inChain() {
    return chained;
  }

  /**
   * How many units the character's chain holds, from its first unit, walking back along the links,
   * to its last.
   */
  int chainSize() {
    CharacterEntity head = this;
    while (head.chainPrevious != null) {
      head = head.chainPrevious;
    }
    int size = 0;
    for (CharacterEntity member = head; member != null; member = member.chainNext) {
      size++;
    }
    return size;
  }

  /**
   * Links a child the character's spawner made into its chain right after it, ahead of the unit
   * that followed it, and marks the child as in a group. Unlike a spawn row's group link, nothing
   * else is recorded.
   *
   * @param child the child just made
   */
  void linkSpawnIntoChain(CharacterEntity child) {
    child.chained = true;
    child.chainPrevious = this;
    child.chainNext = chainNext;
    if (chainNext != null) {
      chainNext.chainPrevious = child;
    }
    chainNext = child;
    world.chainLinked(child, this);
  }

  /**
   * The leave reset of a character the fold lets go of instead of admitting, as a buff's child
   * whose carrier left before it: it leaves the card group chain it was linked into, joining the
   * units on either side of it, and nothing else hears of it.
   */
  @Override
  protected void releasedAtFold() {
    super.releasedAtFold();
    if (leaveChain()) {
      world.chainUnlinked(this);
    }
  }

  /** The unit after the character in its card group chain, or null for none. */
  CharacterEntity chainNext() {
    return chainNext;
  }

  /**
   * Takes the character out of its card group chain as it is released, joining the units on either
   * side of it.
   *
   * @return true when it was in a chain
   */
  boolean leaveChain() {
    // A lone source a child was linked after holds links without a group mark of its own.
    if (!chained && chainPrevious == null && chainNext == null) {
      return false;
    }
    if (chainPrevious != null) {
      chainPrevious.chainNext = chainNext;
    }
    if (chainNext != null) {
      chainNext.chainPrevious = chainPrevious;
    }
    chainPrevious = null;
    chainNext = null;
    chained = false;
    return true;
  }

  /**
   * Unlinks the character from the group it was linked into, as it is released.
   *
   * @return the character whose group it left, or null when it was in none
   */
  CharacterEntity leaveGroup() {
    CharacterEntity source = groupSource;
    if (source != null) {
      source.group.remove(this);
      groupSource = null;
    }
    return source;
  }

  /**
   * The character's view at placement: deploying, facing up the arena from the bottom side and down
   * it from the top side, in the lane of the road nearest to the deploy position; a building has no
   * movement component and occludes the cells it stands on.
   */
  private static GridEntity createView(
      TileMap tileMap, UnitData data, String name, int side, int x, int y) {
    GridEntity view = new GridEntity();
    view.setName(name);
    view.setSide(side);
    view.setCollisionRadius(data.collisionRadius());
    view.setMass(data.mass());
    // An air unit is created at its row's flying height and keeps it; the layer tests of the
    // contact passes compare that height, the validator the air answer.
    view.setAir(data.air());
    view.setZ(data.flyingHeight());
    view.setZTotal(data.flyingHeight());
    // A building has no movement component, and stands in the overlay as an obstacle; an
    // occluder stands in it too while it stands still. A row without a speed has no movement
    // component either, a building or not.
    view.setBuilding(data.building());
    view.setOccludes(data.building());
    view.setOccluder(data.occluder());
    view.setMovementComponent(data.hasMovementComponent());
    view.setMovementActive(data.hasMovementComponent());
    view.setTargetable(1);
    view.setX(x);
    view.setY(y);
    view.setLane(
        LaneAssignment.lane(
            tileMap.width(), tileMap.height(), tileMap.width(), x, y, -1, 0, tileMap::bits));
    view.setState(GridEntityState.DEPLOYING);
    view.setDeployCountdown(data.deployTimeMs());
    view.setDirX(0);
    view.setDirY(
        side == SIDE_BOTTOM ? MovementState.DIRECTION_SCALE : -MovementState.DIRECTION_SCALE);
    return view;
  }

  /**
   * Asks the unit's setter for a state, as a hooking projectile does of its target and its owner.
   *
   * @param state the state asked for
   */
  void requestState(int state) {
    setter.setState(getView(), state);
  }

  /**
   * Has the unit follow a hooking projectile: its state visit puts it where the projectile is each
   * tick while it is pulled, and a hook on a building drags it while it follows.
   *
   * @param projectile the hooking projectile
   */
  void follow(ProjectileEntity projectile) {
    unit.timers().setFollowTarget(projectile);
  }

  /**
   * The character's notice of a removal: the components hear first; then a projectile it followed
   * is forgotten and, outside a clone's setup, the unit is resumed. The resume does nothing in a
   * pulled state, whose next state visit, finding nothing to follow, asks for standing; an owner a
   * hook dragged to a building has moved on already, and stays moving.
   */
  @Override
  protected void entityRemoved(BattleEntity removed) {
    super.entityRemoved(removed);
    if (removed instanceof ProjectileEntity projectile
        && unit.timers().getFollowTarget() == projectile) {
      unit.timers().setFollowTarget(null);
      world.followLeft(this, projectile);
      if (getView().getState() != GridEntityState.CLONE_SETUP) {
        ResumeHelper.resume(
            getView(), unit.stateConfig(), stateQueries(), new ArrayList<>(), setter);
      }
    }
  }

  /** Tells the battle of a special load the visit armed, with the ring its reference stood in. */
  private void specialArmed() {
    TargetView reference = unit.targeting().getReference();
    long dx = reference.x() - getView().getX();
    long dy = reference.y() - getView().getY();
    UnitData data = getData();
    world.specialArmed(
        this,
        world.entityOf(reference.getEntity()),
        dx * dx + dy * dy,
        reference.radius() + data.specialMinRange(),
        reference.radius() + data.specialRange(),
        data.specialLoadTimeMs(),
        unit.targeting().getSpecialLoadTimerMs());
  }

  /** A unit whose dashes chain ends its chain as its targeting component is switched off. */
  @Override
  protected void targetingSwitchedOff() {
    if (getData().dashCount() >= 1) {
      dashReset();
    }
  }

  /** Switches a component on or off, the movement component's view and targeting bits with it. */
  void switchComponent(int slot, boolean on) {
    setActive(slot, on);
    if (slot == TARGETING_SLOT && !on) {
      targetingSwitchedOff();
    }
    if (slot == MOVEMENT_SLOT && getView().isMovementComponent()) {
      getView().setMovementActive(on);
      unit.targeting().setMovementComponentActive(on);
    }
  }

  /** What a hook's states do on the unit beyond its setter's fields. */
  private final class HookStates implements GridStateSetter.Following {

    @Override
    public void components(boolean on) {
      switchComponent(MOVEMENT_SLOT, on);
      switchComponent(TARGETING_SLOT, on);
    }

    @Override
    public void movementOn() {
      switchComponent(MOVEMENT_SLOT, true);
    }

    /** The raw clear of a reference the range test no longer passes: no setter, no resume. */
    @Override
    public void dropReferenceOutOfRange() {
      TargetingState t = unit.targeting();
      TargetView reference = t.getReference();
      if (reference != null && !RangeTest.referenceInRange(t, reference, 0)) {
        t.setReference(null);
        t.setKeptByPendingDamageCheck(false);
      }
    }

    /**
     * A unit let go on a cell it may not stand on is moved to the nearest cell off the water, on
     * any row: the projectile it followed, whose owner would decide the side, is gone by then.
     */
    @Override
    public void standOrRelocate() {
      GridEntity view = getView();
      CellGrid grid = world.getGrid();
      if (CellTests.cellBlocked(grid, view.getX(), view.getY()) == 0) {
        return;
      }
      if (unit.timers().getFollowTarget() != null) {
        throw new UnsupportedOperationException(
            name() + " leaves a pulled state still following, not modelled");
      }
      int packed =
          Relocation.relocate(
              grid.getWidth(), grid.getHeight(), view.getX(), view.getY(), -1, grid::water);
      world.relocated(
          CharacterEntity.this,
          view.getX(),
          view.getY(),
          Relocation.unpackX(packed),
          Relocation.unpackY(packed));
      view.setX(Relocation.unpackX(packed));
      view.setY(Relocation.unpackY(packed));
    }

    @Override
    public void tailGate() {
      stateTailGate();
    }
  }

  private static TargetingConfig targetingConfig(UnitData data) {
    return TargetingConfig.forUnit(
            data.range(),
            data.sightRange(),
            data.collisionRadius(),
            data.hitSpeedMs(),
            data.loadTimeMs(),
            data.attacksGround(),
            data.attacksAir())
        .toBuilder()
        .configKey(data.name())
        .isBuilding(data.building())
        .targetOnlyBuildings(data.targetOnlyBuildings())
        .multipleTargets(data.multipleTargets())
        .uniqueMultipleTargets(data.uniqueMultipleTargets())
        .rememberMultipleTargets(data.rememberMultipleTargets())
        .allTargetsHit(data.allTargetsHit())
        .attackSequenceMode(data.attackSequence().mode())
        .attackSequenceLength(data.attackSequence().order().size())
        // The range helpers read the entry the index selects: its attack range and minimum range
        // replace the row's at any length of the order.
        .attackSequenceStepIds(data.attackSequence().order())
        .attackSequenceEntries(data.attackSequence().targetingEntries())
        .hasOnStartingAttackAction(data.onStartingAttackAction() != null)
        .crownTowerDamagePercent(data.crownTowerDamagePercent())
        .hasProjectile(data.hasProjectile())
        .hasSpecialProjectile(data.projectileSpecial() != null)
        .areaDamageRadius(data.areaDamageRadius())
        .selfAsAoeCenter(data.selfAsAoeCenter())
        .overrideAttackFinishTime(data.overrideAttackFinishTime())
        .attackFinishTime(data.attackFinishTimeMs())
        .resetHitTimerWhenNoTarget(data.resetHitTimerWhenNoTarget())
        .minimumRange(data.minimumRange())
        .sightClip(data.sightClip())
        .sightClipSide(data.sightClipSide())
        .loadFirstHit(data.loadFirstHit())
        .keepChargingAfterAttack(data.keepChargingAfterAttack())
        .jumpHeight(data.jumpHeight())
        .dashCooldown(data.dashCooldown())
        .dashMinRange(data.dashMinRange())
        .dashMaxRange(data.dashMaxRange())
        .dashLandingTime(data.dashLandingTimeMs())
        .dashStopsAtContact(data.dashToTargetRadius())
        .dashCount(data.dashCount())
        .dashImmuneToDamageTime(data.dashImmuneToDamageTimeMs())
        .targetOnlyTroops(data.targetOnlyTroops())
        .ignoreTargetsWithBuff(data.ignoreTargetsWithBuff() != null)
        .deprioritizeTargetsWithBuff(data.deprioritizeTargetsWithBuff())
        .keepTargetWithPendingDamage(data.keepTargetWithPendingDamage())
        .lifeTime(data.lifeTimeMs())
        .specialRange(data.specialRange())
        .specialMinRange(data.specialMinRange())
        .specialLoadTime(data.specialLoadTimeMs())
        .specialIgnoreBuildings(data.specialIgnoreBuildings())
        .build();
  }

  private boolean deploying() {
    return getView().getState() == GridEntityState.DEPLOYING;
  }

  /**
   * A character's death switches its movement component off, so the rest of the tick skips its
   * movement visit and an area effect's pull passes it over. Its targeting component stays on, so a
   * hit it has due in the same tick still lands; the standard game switches that off too only under
   * a global setting it leaves off. The death slot does this after the buffs have ended: a goblin a
   * Goblin Curse leaves, visited as it is made, still sees the dying character moving, and takes
   * the side its avoidance blend steers by.
   */
  @Override
  protected void deathSwitches() {
    if (getView().isMovementComponent()) {
      switchComponent(MOVEMENT_SLOT, false);
    } else {
      getView().setMovementActive(false);
    }
  }

  /**
   * After each projectile it launches, a unit whose row pushes it back asks for that pushback, away
   * from the projectile's aim: with the gates lifted, so even a unit whose row ignores pushback
   * recoils, as an attack's pushback, the whole distance whatever the separation, and refused only
   * while a pushback is still in flight. The pushback visit then flies it from the movement pass.
   */
  @Override
  public void launched(int aimX, int aimY) {
    recoil(aimX, aimY);
  }

  /**
   * The unit's recoil by its row's attack pushback, away from a point: after each projectile it
   * launches, away from the projectile's aim, and after a direct hit, away from where its reference
   * stood. The same request either way: the gates lifted, as an attack's pushback, the whole
   * distance, and refused while a pushback is still in flight. A row without an attack pushback
   * does not recoil.
   *
   * @param aimX the point it recoils from, along the width
   * @param aimY the point it recoils from, along the length
   */
  void recoil(int aimX, int aimY) {
    int distance = getData().attackPushBack();
    if (distance < 1) {
      return;
    }
    MovementState movement = unit.movement();
    int ran =
        PushbackRequest.request(
            movement,
            getView(),
            pushbackQueries,
            aimX,
            aimY,
            distance,
            true,
            true,
            false,
            false,
            false);
    world.pushbackRequested(
        this, ran == 1 && movement.getPushbackInFlight() == 1, aimX, aimY, movement);
  }

  /**
   * An area's push on the character, after the area's damage: a character that stands without a
   * movement component, whose row or one of whose buffs ignores pushback or that the damage killed
   * is left where it is. Otherwise its movement component is switched on and a pushback is asked
   * for, away from the point, with every gate in place and nothing lifted.
   *
   * <p>The switch is the whole component's, as {@link #switchComponent} makes it: the slot, the
   * view's flag and the targeting's bit together. For a unit a hook holds with its movement off
   * (pulled, or the hook's owner) this lets the pushback fly from the next movement visit on while
   * the hold lasts.
   *
   * @param x the point it is pushed away from, along the width
   * @param y the point it is pushed away from, along the length
   * @param distance how far
   * @return true when the pushback was asked for
   */
  boolean pushedByArea(int x, int y, int distance) {
    if (!getView().isMovementComponent()
        || getData().ignorePushback()
        || getBuffs().ignoresPushBack()
        || !HitPoints.alive(getHitPoints())) {
      return false;
    }
    switchComponent(MOVEMENT_SLOT, true);
    MovementState movement = unit.movement();
    int ran =
        PushbackRequest.request(
            movement,
            getView(),
            pushbackQueries,
            x,
            y,
            distance,
            false,
            false,
            false,
            false,
            false);
    world.pushbackRequested(this, ran == 1 && movement.getPushbackInFlight() == 1, x, y, movement);
    return true;
  }

  /** Whether the character's movement component is on, which a push request asks first. */
  boolean movementOn() {
    return hasMovementComponent() && isActive(MOVEMENT_SLOT);
  }

  /**
   * A push request away from a point, as the evolved Executioner's strong hit asks it: every gate
   * in place and nothing lifted, the whole distance, refused while a pushback is in flight. Its
   * movement is not switched on: each caller asks first that it is on.
   *
   * @param x the point it is pushed away from, along the width
   * @param y the point it is pushed away from, along the length
   * @param distance how far
   */
  void pushedFrom(int x, int y, int distance) {
    MovementState movement = unit.movement();
    int ran =
        PushbackRequest.request(
            movement,
            getView(),
            pushbackQueries,
            x,
            y,
            distance,
            false,
            false,
            false,
            false,
            false);
    world.pushbackRequested(this, ran == 1 && movement.getPushbackInFlight() == 1, x, y, movement);
  }

  /**
   * A push request away from a point, as the filter form of an area effect asks it with its push
   * columns: the gates lifted when asked, the separation taken off when asked, and accepted with a
   * pushback in flight, keeping the longer one, when asked; never as the character's own attack and
   * never while it is hidden. Its movement is not switched on: the caller asks first that it is on.
   *
   * @param x the point it is pushed away from, along the width
   * @param y the point it is pushed away from, along the length
   * @param distance how far
   * @param liftGates true to push it whatever its row, buffs, flags or being dragged say
   * @param subtract true to take the current separation off the distance first
   * @param keepLonger true to accept it with a pushback in flight, keeping the longer one
   */
  void pushedByArea(
      int x, int y, int distance, boolean liftGates, boolean subtract, boolean keepLonger) {
    MovementState movement = unit.movement();
    int ran =
        PushbackRequest.request(
            movement,
            getView(),
            pushbackQueries,
            x,
            y,
            distance,
            liftGates,
            false,
            subtract,
            keepLonger,
            false);
    world.pushbackRequested(this, ran == 1 && movement.getPushbackInFlight() == 1, x, y, movement);
  }

  /**
   * The push of an attack sequence step's direct hit on the character, away from where the attacker
   * stands: the whole distance, not counted as the character's own attack, refused while a pushback
   * is in flight or the character is hidden, and refused by its row, its buffs, its no-pushback
   * flag or its being dragged unless the step lifts those gates. Its movement is not switched on.
   *
   * @param x the attacker's position along the width
   * @param y the attacker's position along the length
   * @param distance how far
   * @param liftGates true when the step's push lifts the gates (IsMeleePushbackAll)
   */
  void pushedByStep(int x, int y, int distance, boolean liftGates) {
    MovementState movement = unit.movement();
    int ran =
        PushbackRequest.request(
            movement,
            getView(),
            pushbackQueries,
            x,
            y,
            distance,
            liftGates,
            false,
            false,
            false,
            false);
    world.pushbackRequested(this, ran == 1 && movement.getPushbackInFlight() == 1, x, y, movement);
  }

  /**
   * The push of a unit entering the deploying state near the character: a pushback away from the
   * unit with the gates lifted, so even a row that ignores pushback is pushed, refused only while a
   * pushback is in flight or the character is hidden. Its movement is not switched on: only one
   * whose movement is on is asked.
   *
   * @param x the unit's position along the width
   * @param y the unit's position along the length
   * @param distance how far
   */
  void pushedOnDeploy(int x, int y, int distance) {
    MovementState movement = unit.movement();
    int ran =
        PushbackRequest.request(
            movement, getView(), pushbackQueries, x, y, distance, true, false, false, false, false);
    world.pushbackRequested(this, ran == 1 && movement.getPushbackInFlight() == 1, x, y, movement);
  }

  /**
   * A push from a hit along a projectile's way: only a character whose movement is on and that is
   * not waiting to deploy is pushed, away from the projectile, with the gates that would refuse it
   * lifted when the projectile's row pushes all.
   *
   * @param x the projectile's position along the width
   * @param y the projectile's position along the length
   * @param distance how far
   * @param liftGates true when the row pushes all
   */
  void pushedByTravellingHit(int x, int y, int distance, boolean liftGates) {
    if (!getView().isMovementComponent() || !getView().isMovementActive() || waiting()) {
      return;
    }
    MovementState movement = unit.movement();
    int ran =
        PushbackRequest.request(
            movement,
            getView(),
            pushbackQueries,
            x,
            y,
            distance,
            liftGates,
            false,
            false,
            false,
            false);
    world.pushbackRequested(this, ran == 1 && movement.getPushbackInFlight() == 1, x, y, movement);
  }

  /**
   * A guard's push, or a carried push's: asked only of a character whose movement component is on
   * and that is not waiting to deploy; refused while a pushback is in flight unless the longer one
   * is to be kept; otherwise the pushback setter itself, every gate the request has skipped, so
   * neither the row's ignoring of pushback nor a flag or state stops it.
   *
   * @param x the point it is pushed away from, along the width
   * @param y the point it is pushed away from, along the length
   * @param distance how far
   * @param subtract true to take the current separation off the distance first
   * @param keepLonger true to accept the push with a pushback in flight, keeping the longer one
   * @return -1 when the character is not asked, else 1 when the setter ran and 0 when it was
   *     refused
   */
  int pushedByGuard(int x, int y, int distance, boolean subtract, boolean keepLonger) {
    if (!getView().isMovementComponent() || !isActive(MOVEMENT_SLOT) || waiting()) {
      return -1;
    }
    return pushEntry(x, y, distance, false, subtract, keepLonger);
  }

  /**
   * The pushback entry: refused while a pushback is in flight unless the longer one is to be kept;
   * otherwise the pushback setter itself, every gate the request has skipped, so neither the row's
   * ignoring of pushback nor a flag or state stops it.
   *
   * @param x the point it is pushed away from, along the width
   * @param y the point it is pushed away from, along the length
   * @param distance how far
   * @param attack true when the push counts as an attack's
   * @param subtract true to take the current separation off the distance first
   * @param keepLonger true to accept the push with a pushback in flight, keeping the longer one
   * @return 1 when the setter ran and 0 when it was refused
   */
  int pushEntry(int x, int y, int distance, boolean attack, boolean subtract, boolean keepLonger) {
    MovementState movement = unit.movement();
    if (movement.getPushbackInFlight() != 0 && !keepLonger) {
      return 0;
    }
    PushbackRequest.set(
        movement, getView(), pushbackQueries, x, y, distance, attack, subtract, keepLonger);
    world.pushbackRequested(this, movement.getPushbackInFlight() == 1, x, y, movement);
    return 1;
  }

  /**
   * Whether the targeting component is on and holds a reference, whether or not that reference is
   * still in the battle: whether the unit has a current target an action could launch at.
   */
  boolean referenceHeld() {
    return isActive(TARGETING_SLOT) && unit.targeting().getReference() != null;
  }

  /** The object the targeting component has as its target while the component is on, or null. */
  WorldEntity currentTarget() {
    if (!isActive(TARGETING_SLOT)) {
      return null;
    }
    TargetView reference = unit.targeting().getReference();
    return reference == null ? null : world.entityOf(reference.getEntity());
  }

  /**
   * The targeting queue: the ids marked since the last pre-hook, each with the highest priority it
   * was marked at, in the order first marked.
   */
  private final List<int[]> targetQueue = new ArrayList<>();

  /**
   * Marks an object in the targeting queue, as an uppercut (priority 0) and a unit's taunt step
   * (priority 1) do with the battle's target queueing on: an id already marked keeps the higher of
   * its priorities.
   *
   * @param target the object
   * @param priority its priority
   */
  void markTarget(WorldEntity target, int priority) {
    world.uppercutMarked(this, target, priority);
    for (int[] entry : targetQueue) {
      if (entry[0] == target.getId()) {
        entry[1] = Math.max(entry[1], priority);
        return;
      }
    }
    targetQueue.add(new int[] {target.getId(), priority});
  }

  /** A character's jump samples are its movement component's, while it has one. */
  @Override
  MovementState heightSamples() {
    return unit != null && hasMovementComponent() ? unit.movement() : null;
  }

  /**
   * The pre-hook, with the targeting queue's flush at its tail while the targeting component is on:
   * the last entry with a priority of 1 or more whose object is still listed becomes the reference,
   * set without the setter's re-check, and the next targeting visit skips its candidate selection;
   * then the queue is emptied. A listed object that is not a battle entity, which only a taunt's
   * forced object or an uppercut's target is marked as, is refused.
   */
  @Override
  protected void preHook() {
    super.preHook();
    if (targetQueue.isEmpty() || !isActive(TARGETING_SLOT)) {
      return;
    }
    BattleEntity taken = null;
    for (int[] entry : targetQueue) {
      if (entry[1] >= 1) {
        BattleEntity listed = world.liveObject(entry[0]);
        if (listed != null) {
          taken = listed;
        }
      }
    }
    targetQueue.clear();
    if (taken != null) {
      if (!(taken instanceof WorldEntity onto)) {
        throw new UnsupportedOperationException(
            name() + " takes " + taken + " from its targeting queue, which is not modelled");
      }
      tauntReference(onto, true);
      unit.targeting().setSkipSelectionNextVisit(true);
    }
    world.targetQueueFlushed(this);
  }

  /**
   * Starts the evolved Mega Knight's uppercut on the character. A clone, a rider and a carrier are
   * refused.
   */
  @Override
  public ActionInstance uppercut(MegaKnightUppercut action, int phase, ActionOwner instigator) {
    refuseRun(action.name());
    return new UppercutRun(action, this, phase, instigator(instigator));
  }

  /**
   * Knocks the character into the air. A clone, a rider and a carrier are refused.
   *
   * <p>A unit with an ability is knocked as any other: the run postpones the ability while it is
   * listed, which the ability gate alone reads, so a request in the air is left pending and cast
   * once the run has left. A unit knocked while it casts its ability or holds its follow-up state
   * is refused: the postponing does not reach a cast already begun, and no reference holds a cast
   * going on through a knock.
   */
  @Override
  public ActionInstance knockback(Knockback action, int phase, ActionOwner instigator) {
    refuseRun(action.name());
    int state = getView().getState();
    if (state == GridEntityState.CASTING || state == GridEntityState.ABILITY_FOLLOW_UP) {
      throw new UnsupportedOperationException(
          action.name()
              + " knocks "
              + name()
              + " while it casts its ability, which no reference holds");
    }
    return new KnockbackRun(action, this, phase, instigator(instigator));
  }

  /**
   * Starts an attack chain on the character: its first object picked and the chain begun on it, or
   * the run left waiting. A clone, a rider and a carrier are refused, whose chain no reference
   * holds.
   */
  @Override
  public ActionInstance attackChain(AttackChain action, ActionHolder holder) {
    if (isClone() || parent != null || !riders().isEmpty()) {
      throw new UnsupportedOperationException(
          action.name() + " on " + name() + ", a clone, a rider or a carrier, not modelled");
    }
    AttackChainRun run = new AttackChainRun(action, this, holder);
    run.start();
    return run;
  }

  /**
   * Starts a dashing attack chain on the character: its first object picked and the dash begun on
   * it, or the run left waiting. A clone, a rider and a carrier are refused, whose chain no
   * reference holds.
   */
  @Override
  public ActionInstance dashingAttackChain(DashingAttackChain action, ActionHolder holder) {
    if (isClone() || parent != null || !riders().isEmpty()) {
      throw new UnsupportedOperationException(
          action.name() + " on " + name() + ", a clone, a rider or a carrier, not modelled");
    }
    DashingAttackChainRun run = new DashingAttackChainRun(action, this);
    run.start();
    return run;
  }

  /** Starts the evolved Goblin Drill's relocation on the character, which must be a building. */
  @Override
  public ActionInstance goblinDrillRelocate(GoblinDrillEvoRelocate action, ActionHolder holder) {
    if (!getData().building()) {
      throw new UnsupportedOperationException(
          action.name() + " on " + name() + ", which is not a building, not modelled");
    }
    return new GoblinDrillRelocateRun(action, this, holder);
  }

  /**
   * Starts a push of the character away from what caused it. A clone, a rider and a carrier are
   * refused, as is a cause that is not an object of the battle.
   */
  @Override
  public ActionInstance pushbackFromInstigator(
      DoPushbackFromInstigator action, int phase, ActionOwner instigator) {
    refuseRun(action.name());
    WorldEntity cause = instigator(instigator);
    if (cause == null) {
      throw new UnsupportedOperationException(
          action.name() + " on " + name() + " has a cause that is not an object, not modelled");
    }
    return new PushbackFromInstigatorRun(action, this, cause);
  }

  /**
   * The push request of a push away from its cause, as the Giant hero form's slap asks it: the
   * row's switches as the request's, the whole strength, and on its success nothing more here.
   *
   * @param x the point it is pushed away from, along the width
   * @param y the point it is pushed away from, along the length
   * @param columns the push's columns
   * @return 1 when the setter ran, else 0
   */
  int pushedFromInstigator(int x, int y, DoPushbackFromInstigator.Columns columns) {
    MovementState movement = unit.movement();
    int ran =
        PushbackRequest.request(
            movement,
            getView(),
            pushbackQueries,
            x,
            y,
            columns.strength(),
            columns.forced(),
            columns.attack(),
            columns.proportional(),
            columns.resetIfStronger(),
            columns.invisible());
    world.pushbackRequested(this, ran == 1 && movement.getPushbackInFlight() == 1, x, y, movement);
    return ran;
  }

  /** Starts the evolved Dart Goblin's dart choice on the character. */
  @Override
  public ActionInstance blowdartDartSelect(BlowdartDartSelect action, ActionHolder instigator) {
    return new BlowdartDartSelectRun(action, this, instigator);
  }

  /** Starts a carried push's run on the character. A clone, a rider and a carrier are refused. */
  @Override
  public ActionInstance damagingPushBack(DamagingPushBack action, int phase) {
    refuseRun(action.name());
    return new DamagingPushBackRun(action, this);
  }

  /** The row the character's completed charge runs, built once; null before the first. */
  private BattleAction chargeActionRow;

  /**
   * Schedules the row's action for a completed charge on the character, the character its own
   * cause, queued as the row's own delay asks and never started at once: each time the charge
   * progress reaches complete from below. Nothing for a row without one.
   */
  private void runChargeAction() {
    String name = getData().onStartChargingAction();
    if (name == null) {
      return;
    }
    if (chargeActionRow == null) {
      chargeActionRow = world.getActions().build(name, world.binding(this));
    }
    actionHolder().schedule(chargeActionRow, ActionHolder.OWN_DELAY, false, actionHolder());
  }

  /**
   * A damage at the character's level, as its own row's rarity scales a card's damage.
   *
   * @param base the damage at the first level
   */
  int damageAtLevel(int base) {
    return LevelScaling.scale(
        ScalingGlobals.standard(),
        base,
        getPackedLevel(),
        ScalingMode.CARD_DAMAGE,
        getData().rarity());
  }

  /** Starts a barrage's run on the character. A clone, a rider and a carrier are refused. */
  @Override
  public ActionInstance cannonBarrage(CannonBarrage action, int phase) {
    refuseRun(action.name());
    return new CannonBarrageRun(action, this, phase);
  }

  /**
   * Starts a resetable area effect's run on the character. A clone, a rider and a carrier are
   * refused.
   */
  @Override
  public ActionInstance resetableAreaEffect(
      SpawnResetableAreaEffect action, int phase, ActionOwner instigator) {
    refuseRun(action.name());
    return new ResetableAreaEffectRun(action, this, phase, instigator(instigator));
  }

  /**
   * Drops a container of a balloon pop at the character's point moved by the offsets, the one along
   * the length times the team's direction, whether the character lives or has just died.
   */
  @Override
  public void dropContainer(PopBalloons action, String areaEffect, int offsetX, int offsetY) {
    int x = getView().getX() + offsetX;
    int y = AreaEffectEntity.yDirection(side()) * offsetY + getView().getY();
    world().containerAreaEffect(this, action.name(), areaEffect, x, y);
  }

  /** Refuses a run of the evolved Mega Knight's or Baby Dragon's on a clone, rider or carrier. */
  private void refuseRun(String action) {
    if (isClone() || getParent() != null || !riders().isEmpty()) {
      throw new UnsupportedOperationException(
          action + " runs on " + name() + ", a clone, a rider or a carrier, not modelled");
    }
  }

  /** The entity behind a cause, or null. */
  private static WorldEntity instigator(ActionOwner instigator) {
    return instigator instanceof WorldEntity entity ? entity : null;
  }

  /**
   * Puts a guard just made and registered into its deploy, as its maker does after the registration
   * visit: through the setter, then the combat gate the setter's entry ends with, which drops
   * whatever the registration visit selected and switches the targeting component off.
   */
  void deployAfterRegistration() {
    startDeploying();
    combatGate(isActive(TARGETING_SLOT) && !waiting(), setter::prepareRoute);
  }

  /**
   * Faces the character toward a point: the line to it scaled to the facing's length, whatever row
   * or column the point is on.
   */
  void faceToward(int x, int y) {
    GridEntity view = getView();
    int[] facing = {x - view.getX(), y - view.getY()};
    FixedMath.normalize(facing, MovementState.DIRECTION_SCALE);
    view.setDirX(facing[0]);
    view.setDirY(facing[1]);
  }

  /**
   * What a guard's run on the character asks of the battle: its state and deploy, the area effect
   * it makes, and its charge.
   */
  GuardHost guardHost() {
    return new GuardHost() {
      @Override
      public int state() {
        return getView().getState();
      }

      @Override
      public int deployCountdownMs() {
        return getView().getDeployCountdown();
      }

      @Override
      public void cutDeploy() {
        getView().setDeployCountdown(0);
        setter.setState(getView(), GridEntityState.STANDING);
      }

      @Override
      public int teamSign() {
        return (side() & 1) == 0 ? 1 : -1;
      }

      @Override
      public int spawnArea(String action, String row, int phase) {
        return world
            .spawnAreaEffect(CharacterEntity.this, action, row, CharacterEntity.this, 0, 0, phase)
            .getId();
      }

      @Override
      public void endArea(int id) {
        if (world.liveOrQueued(id) instanceof AreaEffectEntity areaEffect) {
          areaEffect.end();
        }
      }

      @Override
      public boolean hasTargeting() {
        // Every character has a targeting component, whether it is switched on or not.
        return true;
      }

      @Override
      public int[] clamp(int x, int y) {
        int top = world.getGrid().getWidth() * TileMap.CELL_UNITS - 1;
        int bottom = world.getGrid().getHeight() * TileMap.CELL_UNITS - 1;
        return new int[] {x > 0 ? Math.min(x, top) : 0, y > 0 ? Math.min(y, bottom) : 0};
      }

      @Override
      public void charge(int x, int y) {
        GridEntity view = getView();
        int fromX = view.getX();
        int fromY = view.getY();
        TargetView reference = unit.targeting().getReference();
        DashStart.start(
            view,
            isActive(MOVEMENT_SLOT) ? unit.movement() : null,
            unit.movementConfig(),
            x,
            y,
            0,
            0,
            world.getGrid().getWidth(),
            world.getGrid().getHeight(),
            setter);
        world.dashStarted(CharacterEntity.this, reference, fromX, fromY, x, y);
      }

      @Override
      public void stepped(boolean charging, long tags, boolean done, List<String> calls) {
        world.guardStepped(CharacterEntity.this, charging, tags, done, calls);
      }
    };
  }

  /**
   * Whether an asker may select, hit or buff the character, as the character answers it. Never
   * while it rides on a parent. While it is a spawned child still immune, no character or tower,
   * and no asker at all. While it is invisible, a character or tower only when it is a building
   * without hit points, and an area's damage from anyone when its row lets that reach it invisible,
   * each while it is not hidden. Otherwise an area effect that reaches hidden units, hidden or not,
   * and whoever else asks while it is not hidden.
   *
   * @param asker the entity that asks, or null for none
   * @param areaQuery true when an area's damage asks about one of its victims
   */
  private boolean accepts(GridEntity asker, boolean areaQuery) {
    if (parent != null) {
      return false;
    }
    boolean character = asker != null && asker.getType() == ReferenceValidator.TYPE_CHARACTER;
    if (unit.timers().isSpawnImmune() && (asker == null || character)) {
      return false;
    }
    if (invisible()) {
      if (character
          && !(areaQuery && getData().allowAreaDamageWhenInvisible())
          && !buildingWithoutHitPoints(asker)) {
        return false;
      }
      return !hidden();
    }
    if (world.reachesHidden(asker)) {
      return true;
    }
    return !hidden();
  }

  /** Whether an asker is a building whose row has no hit points, as the bombs and bottles are. */
  private boolean buildingWithoutHitPoints(GridEntity asker) {
    WorldEntity entity = world.entityOf(asker);
    return entity != null && entity.getData().building() && entity.getData().hitpoints() == 0;
  }

  /** Whether the character is invisible: a listed buff makes it so. */
  public boolean invisible() {
    return getBuffs().invisibleCount() >= 1;
  }

  /**
   * Hidden while it waits its turn to deploy, which the test asks first, while it tunnels to its
   * placement, in the spawn-pathfinding state, while it routes to a point its ability sent it to,
   * unless its row keeps it visible there, for a row that hides while it does not attack, while its
   * hide counter stands exactly at its hide time, and while its tag word holds the hidden tag,
   * which a hiding run sets. So every hit, area and filter that asks this test passes a unit of a
   * play that still waits by, and the damage entry refuses it, but for an area effect that reaches
   * hidden units.
   */
  @Override
  public boolean hidden() {
    return waiting()
        || underground()
        || getData().hidesWhenNotAttacking()
            && HideHandler.hidden(unit.timers().getHideCounterMs(), getData().hideTimeMs())
        || taggedHidden();
  }

  /**
   * Underground while it tunnels to its placement, in the spawn-pathfinding state, and while it
   * routes to a point its ability sent it to, unless its row keeps it visible there. A game object
   * filter that drops underground objects asks this test, so such a filter drops a tunnelling or
   * routing unit even when it does not drop hidden ones; the hidden test asks it too.
   */
  @Override
  public boolean underground() {
    return tunnelling() || ingamePathfinding() && !getData().ingamePathfindVisible();
  }

  /** Whether its tag word holds the hidden tag. */
  private boolean taggedHidden() {
    return (getView().getFlags() & getView().getFlagBits().hidden()) != 0;
  }

  /** Whether it routes to a point its ability sent it to, in the in-game pathfinding state. */
  private boolean ingamePathfinding() {
    return getView().getState() == GridEntityState.INGAME_PATHFIND;
  }

  /** Whether it tunnels to its placement, in the spawn-pathfinding state. */
  @Override
  protected boolean tunnelling() {
    return getView().getState() == GridEntityState.SPAWN_PATHFIND;
  }

  /**
   * An area effect that reaches hidden units reaches it hidden by its hide counter, and while it
   * waits its turn to deploy. One reaching it in its tunnel, routing to a point its ability sent it
   * to, or hidden by its tag, is not modelled.
   */
  @Override
  protected boolean reachableWhileHidden() {
    return !tunnelling() && !ingamePathfinding() && !taggedHidden();
  }

  /**
   * Untouchable while it tunnels, while it rides on a parent, while it dashes under a row with a
   * dash immunity, and, when asked, while that immunity lasts after the dash. Hiding by its hide
   * counter does not make it untouchable.
   */
  @Override
  boolean untouchable(boolean dashImmunity) {
    return tunnelling()
        || parent != null
        || getView().getState() == GridEntityState.DASHING
            && getData().dashImmuneToDamageTimeMs() > 0
        || dashImmunity && unit.timers().getDashImmunityRemainingMs() >= 1;
  }

  /**
   * Whether it dodges damage, as is_dodging_damage asks: dashing under a row with a dash immunity,
   * or with that immunity still counting after the dash. Unlike {@link #untouchable(boolean)} it
   * does not count a tunnel or a parent.
   */
  boolean dodgingDamage() {
    return getView().getState() == GridEntityState.DASHING
            && getData().dashImmuneToDamageTimeMs() > 0
        || unit.timers().getDashImmunityRemainingMs() >= 1;
  }

  /** A dasher that lost its reference resumes, as the state visit's resume does. */
  @Override
  protected void resumeAfterDrop() {
    resume();
  }

  /**
   * Drops the reference through the setter's null path, as the killer's hook does for a row that
   * passes over buffed targets on every hit it lands: the reference kept as the previous one, its
   * bytes cleared and a route prepared; a dasher asked to resume does. The attack time runs on.
   *
   * @return the reference it held, or null for none
   */
  TargetView dropReferenceOnHit() {
    TargetingState t = unit.targeting();
    TargetView before = t.getReference();
    TargetingOutcome outcome = new TargetingOutcome();
    outcome.setRoutePreparer(setter::prepareRoute);
    TargetingVisit.clearReference(t, getView(), outcome);
    if (outcome.isResumeRequested()) {
      resumeAfterDrop();
    }
    return before;
  }

  /**
   * The resume: the state the character should be in now that what held it has ended - moving,
   * standing, or nothing. A dasher that lost its reference asks for it, and so does a tiebreaker's
   * clearing before its kill.
   */
  void resume() {
    ResumeHelper.resume(getView(), unit.stateConfig(), stateQueries(), new ArrayList<>(), setter);
  }

  /**
   * The landing of a dash, as the follower asks for it where the dash ends. Its damage is the row's
   * dash damage at the character's level, under a hit id of its own. With a dash radius it hits the
   * area around the landing point and pushes what it hits; without, it hits the reference alone,
   * when the shared validator accepts it and it is within the attack range widened by 500, along
   * the character's facing, and then resets the attack and reloads the wind-up, so the next hit
   * comes a whole hit speed later; a character that stopped on a cell it may not stand on is moved
   * to the nearest cell off the water, at height 0. Then the moving state is asked for, or, for a
   * row with a landing time, the landing hold starts.
   *
   * <p>The single-target hit goes to the same damage entry as a direct hit, which queues it for the
   * holder's damage drain (see {@link BattleWorld#dealDirectHit}): its damage lands after every
   * post-hook, so a unit the landing kills keeps its movement visit of that tick, and a unit
   * visited after the dasher still finds it moving. Whether the drain gives a dash's hit the
   * dasher's buff on damage, as it gives a direct hit's, is not traced, and no row has a dash and a
   * buff on damage; such a row is refused.
   */
  private void landDash() {
    UnitData data = getData();
    GridEntity view = getView();
    int damage =
        LevelScaling.scale(
            ScalingGlobals.standard(),
            data.dashDamage(),
            getPackedLevel(),
            ScalingMode.CARD_DAMAGE,
            data.rarity());
    int hitId = world.nextHitId();
    WorldEntity hit = null;
    boolean area = data.dashRadius() >= 1;
    if (area) {
      world.dashLandingArea(this, damage, hitId);
    } else {
      TargetingState t = unit.targeting();
      TargetView reference = isActive(TARGETING_SLOT) ? t.getReference() : null;
      if (reference != null
          && ReferenceValidator.sharedValidate(
              t,
              reference,
              false,
              data.targetOnlyBuildings(),
              false,
              false,
              ValidatorQueries.standard1v1())
          && RangeTest.referenceInRange(t, reference, DASH_HIT_EXTENSION)) {
        if (data.buffOnDamage() != null) {
          throw new UnsupportedOperationException(
              name()
                  + " landed its dash's hit with a buff on damage, whose drain for a dash's hit"
                  + " no run holds");
        }
        hit = world.entityOf(reference.getEntity());
        world.dealDirectHit(this, reference, damage, view.getDirX(), view.getDirY());
        t.clearAttack();
        t.setLoadTimerMs(data.loadTimeMs());
      }
      // Hit or not, a stop on a cell it may not stand on moves it to the nearest cell off the
      // water, at height 0, before it asks for its next state: the moving state's entry prepares
      // its route from where it stands. The dash's end then writes its stop point back, moved off
      // water the same way, so of a stop beside the water or at the arena's edge it keeps no move.
      CellGrid grid = world.getGrid();
      if ((CellTests.cellBlocked(grid, view.getX(), view.getY()) & 1) != 0) {
        int packed =
            Relocation.relocate(
                grid.getWidth(), grid.getHeight(), view.getX(), view.getY(), -1, grid::water);
        view.setX(Relocation.unpackX(packed));
        view.setY(Relocation.unpackY(packed));
        view.setZ(0);
      }
    }
    if (data.dashLandingTimeMs() < 1) {
      movementRequests.requestState(GridEntityState.MOVING);
    } else if (view.getBlockCountdownMs() == 0) {
      view.setBlockCountdownMs(LANDING_HOLD_START_MS);
    }
    world.dashLanded(this, hit, hit != null || area ? damage : 0, area);
  }

  /**
   * Attaches the character to a parent it rides on, as its parent's spawner makes it: from its next
   * movement visit on it is placed around the parent, and meanwhile nothing can hurt, target or
   * push it.
   *
   * @param parent the character it rides on
   * @param angle its angle on the parent's ring
   */
  void attachTo(CharacterEntity parent, int angle) {
    this.parent = parent;
    this.attachAngle = angle;
    parent.riders.add(this);
    getView().setAttached(true);
    unit.timers().setAttached(true);
  }

  /** The characters riding on this one, in the order they were made. */
  public List<CharacterEntity> riders() {
    return Collections.unmodifiableList(riders);
  }

  /**
   * The last part of the removal notice: a rider whose parent left is let go. It forgets the
   * parent, runs its death slot - not the death handler - and is removed in the same cleanup.
   */
  @Override
  protected void parentRemoved(BattleEntity removed) {
    if (parent == null || parent != removed) {
      return;
    }
    CharacterEntity left = parent;
    parent = null;
    getView().setAttached(false);
    unit.timers().setAttached(false);
    world.parentLeft(this, left);
    released = true;
  }

  /**
   * The state visit's removal request - its own, or a limited spawner's at its limit - or the
   * release by a parent that left.
   */
  @Override
  protected boolean removalRequested() {
    return released || morphed || destroyedAtLimit || unit.timers().isRemovalRequested();
  }

  /** A character's row tags: those its own row sets. */
  @Override
  protected long rowTags() {
    return getData().gameTagsToSet();
  }

  /** True once it has been morphed into another object, which removes it at the next cleanup. */
  private boolean morphed;

  /** Marks the character morphed into another object: it leaves at the closing cleanup. */
  void morphedAway() {
    morphed = true;
  }

  /**
   * Makes the object a surfaced unit morphs into: on its point, at its level and in its lane, in
   * the state its row is made in - a building stands, with no deploy time yet - with the unit's
   * share of its hit points, and, for a building, facing as the unit did. Refused rather than
   * guessed: a unit that rides on another, one that carries buffs, one out of the deploying and
   * waiting states, a row of another rarity, and a morph into anything but a building.
   *
   * @param old the unit that surfaced
   * @param data the row it morphs into
   * @return the new object, not yet handed to the holder
   */
  static CharacterEntity morphedFrom(CharacterEntity old, UnitData data) {
    GridEntity from = old.getView();
    String refused = null;
    if (old.parent != null) {
      refused = "a unit that rides on another";
    } else if (!old.getBuffs().items().isEmpty()) {
      refused = "a unit carrying buffs, which the new object would copy";
    } else if (from.getState() != GridEntityState.DEPLOYING
        && from.getState() != GridEntityState.WAITING_TO_DEPLOY) {
      refused = "a unit out of the deploying and waiting states";
    } else if (old.getData().rarity() != data.rarity()) {
      refused = "a row of another rarity";
    } else if (!data.building()) {
      refused = "a morph into a character";
    }
    if (refused != null) {
      throw new UnsupportedOperationException(
          old.name() + " morphing into " + data.name() + " asks for " + refused + ", not modelled");
    }
    CharacterEntity made =
        new CharacterEntity(
            old.world,
            data,
            old.name() + "_" + data.name(),
            old.side(),
            from.getX(),
            from.getY(),
            PackedLevel.level(old.getPackedLevel()),
            from.getLane(),
            -1);
    GridEntity view = made.getView();
    view.setState(GridEntityState.STANDING);
    view.setDeployCountdown(0);
    view.setDirX(from.getDirX());
    view.setDirY(from.getDirY());
    made.takeHitPointShare(old);
    // The damage on its way to the old unit passes to the new object through its pending-damage
    // slot, with what is left of its flight.
    made.addPendingDamage(from.getPendingDamageAmount(), from.getPendingDamageDurationMs());
    return made;
  }

  /**
   * Sets a morph's new object deploying through its setter, as the morph sets it to the unit's
   * state: the entry seeds its deploy time and makes its row's area object.
   */
  void startDeployingAfterMorph() {
    setter.setState(getView(), GridEntityState.DEPLOYING);
  }

  /**
   * What the setter's entry to the deploying state makes of the character's row, in its order: the
   * riders of a row that attaches its spawner's children, the row's area object, then its push on
   * the enemies around it. A card play's construction enters the state before it hands the
   * character to the holder, and runs this then; a unit that waits its turn enters it in its state
   * visit once the wait has run out, from whatever state it was in.
   */
  void enteredDeploying() {
    UnitData data = getData();
    if (data.spawnAttach()) {
      world.attachRiders(this);
    }
    if (data.spawnAreaObject() != null) {
      world.spawnAreaObject(this);
    }
    if (data.pushesOnDeploy()) {
      world.spawnPush(this);
    }
  }

  /** True while the character waits its turn to deploy: none of its components is visited. */
  private boolean waiting() {
    return getView().getState() == GridEntityState.WAITING_TO_DEPLOY;
  }

  /**
   * What the state visit asks of the character: its team, whether it can hold a route - only with a
   * movement component - and whether it has hit points.
   */
  private StateQueries stateQueries() {
    StateQueries queries = StateQueries.forUnitWithRoute(side() & 1);
    return new StateQueries(
        queries.team(),
        getView().isMovementComponent(),
        queries.gridAllowsRoute(),
        queries.gridRouteFlag(),
        getHitPoints() != null,
        queries.abilityCastActive(),
        this::abilityGate,
        queries.protectedFromDamage(),
        queries.protectionApplies(),
        queries.goalRow(),
        getBuffs().speed(DEPLOY_STEP_MS),
        this::notAttackingSection,
        this::kamikazeDrain,
        this::deployEndVisit,
        this::hideVisit,
        this::abilityFired);
  }

  /**
   * A hiding row's deploy end, right after its resume: the combat gate, then, with its targeting
   * component on, its targeting visit, one tick before that component's own first visit. The hide
   * handler that follows in the same state visit reads the state the targeting visit leaves.
   */
  private void deployEndVisit() {
    stateTailGate();
    if (isActive(TARGETING_SLOT)) {
      targetingComponent.visit();
    }
    world.deployEndVisited(this);
  }

  /**
   * The hide handler: one step of the hide counter, by the step the character's speed buffs make of
   * 50, 0 under a stun that stops time.
   */
  private void hideVisit() {
    int state = getView().getState();
    int before = unit.timers().getHideCounterMs();
    int step = getBuffs().speed(StateQueries.TICK_MS);
    List<String> effects = new ArrayList<>();
    int after =
        HideHandler.visit(
            before,
            state,
            step,
            getData().hidesWhenNotAttacking(),
            getData().hideTimeMs(),
            getData().upTimeMs(),
            getData().building(),
            effects);
    unit.timers().setHideCounterMs(after);
    world.hideVisited(this, state, before, after, step, effects);
    // A building schedules its row's action beside each effect, on itself with itself as the
    // cause. The state visit runs outside every pending pass, so a row with no delay waits for the
    // building's phase-3 pending pass of the tick.
    for (String effect : effects) {
      String column =
          effect.equals(HideHandler.HIDE_EFFECT) ? "OnDisappearAction" : "OnAppearAction";
      String action =
          effect.equals(HideHandler.HIDE_EFFECT)
              ? getData().onDisappearAction()
              : getData().onAppearAction();
      if (action == null) {
        continue;
      }
      BattleAction row = world.getActions().build(action, world.binding(this));
      world.hidingHookScheduled(this, column, row.name());
      actionHolder().schedule(row, ActionHolder.OWN_DELAY, false, actionHolder());
    }
  }

  /**
   * What is_moving() answers for the character: 1 with its movement component on and a speed budget
   * above 0 as it stands now, so a test of its state rather than of its motion - walking yes,
   * attacking, deploying, idle, held by a tag or stunned no.
   */
  int movingAnswer() {
    if (!hasMovementComponent() || !isActive(MOVEMENT_SLOT)) {
      return 0;
    }
    return movementQueries().speedBudget() > 0 ? 1 : 0;
  }

  /** The movement pass's answers for the character as it stands now, reference included. */
  private GridMovementQueries movementQueries() {
    return new GridMovementQueries(unit, world.getGrid(), world.getCosts(), world::unitStateOf)
        .withBuffs(
            getBuffs().speedPercents(),
            getBuffs().speed(FOLLOWER_STEP),
            getBuffs().overrideChargeRange());
  }

  /** A movement chain over the character's current reference, for one visit or one preparation. */
  private MovementChain movementChain() {
    return movementChain(movementQueries());
  }

  private MovementChain movementChain(GridMovementQueries queries) {
    return new MovementChain(
            unit.movement(),
            getView(),
            world.getGrid(),
            unit.movementConfig(),
            world.getMovementGlobals(),
            queries.reference(),
            world.getNeighbourQuery(),
            queries)
        .withRequests(movementRequests);
  }

  /**
   * Refuses the hits of several targets and the buffs on damage no reference holds: a third target,
   * whose lookup skips the second's pick; a list of unique targets, which no row sets; and a buff
   * on damage over an area, which no row with one has. A unit that fires hands its hit to its
   * projectile, so its own buff on damage is never applied: the Witch Mother's curse comes from her
   * projectile's target buff.
   */
  @Override
  protected void refuseHit() {
    UnitData data = getData();
    // An attack sequence entry's number of targets stands in for the row's while it is selected.
    for (AttackSequence.Entry entry : data.attackSequence().entries()) {
      int targets = entry.customMultipleTargets();
      if (targets >= 3 || targets >= 2 && data.uniqueMultipleTargets()) {
        throw new UnsupportedOperationException(
            name()
                + " has an attack sequence entry that hits with CustomMultipleTargets "
                + targets
                + (data.uniqueMultipleTargets() ? " unique" : "")
                + ", which is not modelled");
      }
    }
    if (data.multipleTargets() >= 3
        || data.multipleTargets() >= 2 && data.uniqueMultipleTargets()
        || data.buffOnDamage() != null
            && data.projectile() == null
            && data.areaDamageRadius() >= 1) {
      throw new UnsupportedOperationException(
          name()
              + " hits with MultipleTargets "
              + data.multipleTargets()
              + (data.uniqueMultipleTargets() ? " unique" : "")
              + " and BuffOnDamage "
              + data.buffOnDamage()
              + " over "
              + data.areaDamageRadius()
              + ", which are not modelled");
    }
  }

  /**
   * The end of a Kamikaze row's hit, after its direct hit or its launch, landed or not: the unit
   * kills itself with its whole hit points, as its own attacker on its own side, so its death slot
   * runs in the same pass and it leaves at the tick's closing cleanup; a projectile it launched
   * flies on without it. A death-spawn buff it carries would be deleted without its death spawn,
   * and a shield of its own would take the kill; neither is modelled.
   *
   * <p>A row with a Kamikaze time only marks its hit ended: it deletes no buff, drains no shield
   * and kills nothing, and its state visit drains its hit points from this tick on, while its hits
   * go on landing.
   */
  @Override
  protected void hitEnded() {
    if (!getData().kamikaze()) {
      return;
    }
    kamikazeHitEnded = true;
    if (getData().kamikazeTimeMs() != 0) {
      world.kamikazeHitEnded(this, false);
      return;
    }
    for (BuffInstance instance : getBuffs().items()) {
      if (instance.getBuff().deathSpawn() != null) {
        throw new UnsupportedOperationException(
            name()
                + " ends a Kamikaze hit carrying "
                + instance.getBuff().name()
                + ", whose deletion without its death spawn is not modelled");
      }
    }
    if (getHitPoints() == null || getHitPoints().getShield() > 0) {
      throw new UnsupportedOperationException(
          name() + " ends a Kamikaze hit without hit points or with a shield, not modelled");
    }
    world.kamikazeHitEnded(this, true);
    world.kamikazeKill(this);
  }

  /**
   * The drain of a Kamikaze row with a time, once its hit has ended: each state visit takes its
   * maximum hit points over its time in visits, at least one, with itself as the attacker, until it
   * dies, which runs its death as its own killer. A row without hit points would be removed instead
   * and one carrying a shield would have it taken first; no row does either, so both are refused.
   */
  private void kamikazeDrain() {
    if (getData().kamikazeTimeMs() < 1 || !kamikazeHitEnded) {
      return;
    }
    if (getHitPoints() == null || getHitPoints().getShield() > 0) {
      throw new UnsupportedOperationException(
          name() + " drains as a Kamikaze row without hit points or with a shield, not modelled");
    }
    int visits = getData().kamikazeTimeMs() / StateQueries.TICK_MS;
    int share = FixedMath.divOrZero(getHitPoints().getMaximum(), visits);
    // At least one, as an unsigned comparison takes it.
    world.kamikazeDrain(this, Integer.compareUnsigned(share, 1) > 0 ? share : 1);
  }

  /** The progress of the character's charge, while its movement component is on. */
  @Override
  protected int chargeProgress() {
    return isActive(MOVEMENT_SLOT) ? unit.movement().getChargeProgress() : HitQueries.NO_CHARGE;
  }

  /** The damage of the character's charged hit: its row's special damage at its level. */
  @Override
  protected int chargedDamage() {
    return LevelScaling.scale(
        ScalingGlobals.standard(),
        getData().damageSpecial(),
        getPackedLevel(),
        ScalingMode.CARD_DAMAGE,
        getData().rarity());
  }

  /**
   * The charge reset a buff that gives a charge range makes as its instance is listed: the charge
   * starts from 0 for a row without a charge range of its own, which tracked none. A character that
   * fires, whose charged shot is not established, is refused.
   *
   * @param instance the instance just listed
   */
  void buffChargeReset(BuffInstance instance) {
    if (getData().hasProjectile()) {
      throw new UnsupportedOperationException(
          name()
              + " takes "
              + instance.getBuff().name()
              + ", a charge range on a unit that fires, whose charged shot is not established");
    }
    int before = unit.movement().getChargeProgress();
    resetCharge();
    world.buffChargeReset(this, instance, before, unit.movement().getChargeProgress());
  }

  /**
   * The charge reset: the progress back to 0 for a row with a charge range or a character a listed
   * buff gives one, and to no charge otherwise, and the targeting component's strike-now byte
   * cleared.
   */
  @Override
  protected void resetCharge() {
    boolean charges = getData().chargeRange() != 0 || getBuffs().overrideChargeRange() != 0;
    unit.movement().setChargeProgress(charges ? 0 : MovementState.CHARGE_INACTIVE);
    unit.targeting().setChargeStrike(false);
  }

  /**
   * The entity state visit: the deploy countdown and every other per-tick state transition. A
   * spawned child's immunity is counted here, and once it clears the child accepts attackers again.
   * A building's deploy ends in the standing state through the resume, and its targeting component
   * visits it from the next tick. Where the visit reaches its spawner block, the spawner runs, and
   * where it reaches its tail, the combat gate: a dead character drops its reference there.
   */
  @Override
  protected void postHook() {
    List<String> calls = new ArrayList<>();
    EntityStateVisit.stateVisit(
        getView(),
        unit.timers(),
        unit.movement(),
        unit.stateConfig(),
        StateVisitGlobals.standard(),
        stateQueries(),
        calls,
        setter);
    // The visit's removal of an object without hit points - the resume at the end of a bomb's
    // deploy - calls its death slot, without the death handler.
    if (calls.contains("remove")) {
      world.deathAtRemoval(this);
    }
    if (calls.contains("elixir")) {
      collectElixir();
    }
    // A unit that surfaced morphs into its row's morph, which is made, queued and deploying.
    if (calls.contains("spawn_pathfind_morph")) {
      world.morph(this);
    }
    if (calls.contains("spawner")) {
      spawner();
    }
    // The landing hold's end resets the attack and reloads the wind-up, with the targeting
    // component on, as the moving state is asked for.
    if (calls.contains("dash_landed") && isActive(TARGETING_SLOT)) {
      unit.targeting().clearAttack();
      unit.targeting().setLoadTimerMs(getData().loadTimeMs());
    }
    // The visit's tail call: the combat gate.
    if (!calls.isEmpty() && calls.get(calls.size() - 1).equals("visit_tail")) {
      stateTailGate();
    }
  }

  /** The combat gate, as the state visit's tail and a change into or out of a cast run it. */
  private void stateTailGate() {
    combatGate(isActive(TARGETING_SLOT) && !deploying() && !waiting(), setter::prepareRoute);
  }

  /**
   * The ability gate: whether a requested ability may start now. It may when the unit has an
   * ability, is not a champion's clone, can act - its targeting component on - is in none of the
   * states from dashing to the follow-up's, carries neither the postponing nor the disabling tag,
   * and the ability does something: with every other effect refused as it is requested, it buffs
   * the unit, creates an area effect, runs an activation action, switches lanes or leaves a
   * character behind, or it only dashes and the unit's reference lies within its dash range.
   */
  private boolean abilityGate() {
    AbilityData ability = getData().ability();
    if (ability == null || !isActive(TARGETING_SLOT)) {
      return false;
    }
    int state = getView().getState();
    if (state >= GridEntityState.DASHING && state <= GridEntityState.COMPONENTS_DISABLED) {
      return false;
    }
    if ((getView().getFlags()
            & (getView().getFlagBits().abilityPostponed()
                | getView().getFlagBits().abilityDisabled()))
        != 0) {
      return false;
    }
    if (ability.buff() != null
        || ability.areaEffectObject() != null
        || ability.onActivationAction() != null
        || ability.switchLanes()
        || ability.activationSpawnCharacter() != null) {
      return true;
    }
    // An ability that only dashes waits for a reference within its dash range.
    TargetView reference = unit.targeting().getReference();
    if (ability.dashRange() < 1 || reference == null) {
      return false;
    }
    int squared =
        FixedMath.squaredDistance(getView().getX(), getView().getY(), reference.x(), reference.y());
    return squared <= ability.dashRange() * ability.dashRange();
  }

  /** Whether a requested ability waits on the unit for its gate to open. */
  public boolean abilityPending() {
    return unit.timers().isAbilityReady();
  }

  /**
   * The visits left before the ability's effect fires, counted on through the cast and kept after
   * it: below zero once it has fired.
   */
  public int abilityWarningCountdown() {
    return unit.timers().getAbilityWarningCountdown();
  }

  /**
   * Requests the unit's ability, as a friend collector does: with the gate open the unit enters the
   * casting state now, through its setter; shut, the ability is left pending, which the state
   * visit's pending branch turns into the cast on the first visit the gate opens. A unit without an
   * ability does nothing. An ability whose columns the battle does not model, or that keeps a buff
   * on a unit waiting to cast, is refused, and so is a lane switch for a row that stays visible
   * while it routes across, which no reference holds.
   */
  public void requestAbility() {
    AbilityData ability = getData().ability();
    if (ability == null) {
      return;
    }
    if (!ability.unmodelledColumns().isEmpty()) {
      throw new UnsupportedOperationException(
          name()
              + " casts "
              + ability.name()
              + ", which sets columns the battle does not model: "
              + ability.unmodelledColumns());
    }
    // A building casts as a troop does: the Goblins hero's banner, the only building with an
    // ability, enters the casting state through the same setter, and its trigger delay fires its
    // activation group, the second wave of goblins and the banner's own kill.
    if (ability.switchLanes() && getData().ingamePathfindVisible()) {
      throw new UnsupportedOperationException(
          name()
              + " casts "
              + ability.name()
              + " and stays visible as it routes across, which is not modelled");
    }
    boolean now = abilityGate();
    world.abilityRequested(this, now);
    if (now) {
      setter.setState(getView(), GridEntityState.CASTING);
      return;
    }
    // Left pending, a dash ability puts its pending buff on the unit until its gate opens.
    if (ability.pendingBuff() != null) {
      throw new UnsupportedOperationException(
          name()
              + "'s "
              + ability.name()
              + " is left pending with "
              + ability.pendingBuff()
              + ", which no reference holds");
    }
    getView()
        .setPendingFlags(
            getView().getPendingFlags() | getView().getFlagBits().abilityCooldownPaused());
    unit.timers().setAbilityReady(true);
  }

  /**
   * The ability's effect, on the visit its trigger delay reaches zero. It does not dash: the game
   * reads the dash range only in the gate that lets the ability start, and the Golden Knight, whose
   * ability row names both a dash range and an activation action, dashes through that action's
   * dashing attack chain instead. Its activation action, scheduled on the unit, the unit as its
   * cause, which from the post-hooks waits for the phase-3 pending pass; its buff, applied to the
   * unit itself for its time, at the unit's level, the unit its parent and its source; its lane
   * switch, which ends the cast; the character it leaves on the unit's spot; the area effect it
   * creates at the unit, given the lifetime its souls buy for an ability that collects them; then
   * its follow-up state, which ends the cast too. Its other effects are refused as it is requested.
   *
   * <p>It runs inside the state visit, after the cast's two countdowns step and before the cast's
   * end is tested, so the rest of the visit sees the state it leaves: a unit it took out of the
   * casting state is not stood up by the cast's end.
   */
  private void abilityFired() {
    AbilityData ability = getData().ability();
    world.abilityFired(this);
    if (ability.onActivationAction() != null) {
      BattleAction action =
          world.getActions().build(ability.onActivationAction(), world.binding(this));
      actionHolder().schedule(action, ActionHolder.OWN_DELAY, false, actionHolder());
    }
    if (ability.buff() != null) {
      world.abilityBuffed(this, ability.buff(), ability.buffTimeMs(), getPackedLevel());
      getBuffs()
          .apply(
              world.buffData(ability.buff()),
              ability.buffTimeMs(),
              getPackedLevel(),
              this,
              side(),
              this);
    }
    if (ability.switchLanes()) {
      switchLanes();
    }
    if (ability.activationSpawnCharacter() != null) {
      world.activationSpawn(this, ability.activationSpawnCharacter());
    }
    if (ability.areaEffectObject() != null) {
      AreaEffectEntity areaEffect = world.abilityAreaEffect(this, ability.areaEffectObject());
      if (ability.soulsFromDeaths()) {
        if (ability.resurrectBaseCount() >= 1) {
          spendSouls(ability, areaEffect);
        }
      } else if (ability.resurrectChargesExpression() != null) {
        spendCharges(ability, areaEffect);
      }
    }
    if (ability.abilityStateDurationMs() >= 1) {
      enterFollowUp();
    }
  }

  /**
   * The souls an ability that collects them spends on the area effect it created: the area effect
   * makes ResurrectBaseCount characters and one more for each soul, at most SpawnLimit, so its
   * lifetime is one SpawnInterval for each after the first and its SpawnInitialDelay; the souls are
   * cleared.
   */
  private void spendSouls(AbilityData ability, AreaEffectEntity areaEffect) {
    int count = Math.min(souls + ability.resurrectBaseCount(), ability.spawnLimit());
    AreaEffectData row = areaEffect.getData();
    int lifetime = row.spawnIntervalMs() * (count - 1) + row.spawnInitialDelayMs();
    areaEffect.overrideLifetime(lifetime);
    world.soulsSpent(this, areaEffect, souls, count, lifetime);
    souls = 0;
  }

  /**
   * The charges an ability with a charges expression spends on the area effect it created: its
   * charges expression, read in the unit's context (the Skeleton King's
   * SkeletonKing_ResurrectCharges, the souls its drains have brought in), adds to
   * ResurrectBaseCount, at most SpawnLimit in all, and the area effect's lifetime is one
   * SpawnInterval for each character after the first and its SpawnInitialDelay. Then the ability's
   * reset action is scheduled on the unit, the unit its cause, as its activation action is: the
   * Skeleton King's writes the variable back to 0.
   */
  private void spendCharges(AbilityData ability, AreaEffectEntity areaEffect) {
    int charges = world.binding(this).expression(ability.resurrectChargesExpression()).getAsInt();
    int count = Math.min(ability.resurrectBaseCount() + charges, ability.spawnLimit());
    AreaEffectData row = areaEffect.getData();
    int lifetime = row.spawnIntervalMs() * (count - 1) + row.spawnInitialDelayMs();
    areaEffect.overrideLifetime(lifetime);
    world.soulsSpent(this, areaEffect, charges, count, lifetime);
    if (ability.spawnCountResetAction() != null) {
      BattleAction reset =
          world.getActions().build(ability.spawnCountResetAction(), world.binding(this));
      actionHolder().schedule(reset, ActionHolder.OWN_DELAY, false, actionHolder());
    }
  }

  /**
   * The soul count, as the death notice tells the unit of a death. It counts one for a unit whose
   * ability collects souls - ResurrectBaseCount set - that is not a clone and is the copy a
   * champion controller of its side follows, for the death of a unit of its own side under
   * ResurrectOwnTroops or of the other side under ResurrectEnemies, that does not ignore
   * resurrection and is not a building, while the base count and its souls stay below SpawnLimit.
   * Its own death counts too, as it dies. Only an ability row without a charges expression counts
   * (soulsFromDeaths).
   *
   * @param dying the object dying
   */
  void countSoul(WorldEntity dying) {
    AbilityData ability = getData().ability();
    // An ability with a charges expression counts no death on the unit: its souls are drained by
    // actions.
    if (ability == null
        || !ability.soulsFromDeaths()
        || ability.resurrectBaseCount() <= 0
        || clone) {
      return;
    }
    if (!world.followedByController(this)) {
      return;
    }
    boolean sameTeam = (dying.side() & 1) == (side() & 1);
    boolean eligible = sameTeam ? ability.resurrectOwnTroops() : ability.resurrectEnemies();
    if (!eligible
        || dying.getData().ignoreResurrect()
        || dying.getTargetView().building()
        || ability.resurrectBaseCount() + souls >= ability.spawnLimit()) {
      return;
    }
    souls++;
    world.soulCounted(this, dying, souls);
  }

  /**
   * The ability's follow-up state: the unit enters it, its countdown seeded, and the state visit
   * that ran the effect takes its first step. While it lasts the combat gate keeps the targeting
   * component off, the reference kept, and the ability's tags are on the unit from the next tick.
   */
  private void enterFollowUp() {
    setter.setState(getView(), GridEntityState.ABILITY_FOLLOW_UP);
    world.abilityStateEntered(this, unit.timers().getAbilityCountdown());
  }

  /**
   * The lane switch: the unit is aimed at the mirror of its position across the arena's width, its
   * own length kept, moved 250 inside the arena and off water by the relocation, and sent there in
   * the in-game pathfinding state, its movement component switched on. Entering that state ends the
   * cast, whose cast time never runs out. The unit does not move on this tick.
   */
  private void switchLanes() {
    GridEntity view = getView();
    int x = world.getGrid().getWidth() * TileMap.CELL_UNITS - view.getX();
    int y = view.getY();
    int packed =
        Relocation.relocate(
            world.getGrid().getWidth(),
            world.getGrid().getHeight(),
            x,
            y,
            -1,
            world.getGrid()::water);
    int toX = Relocation.unpackX(packed);
    int toY = Relocation.unpackY(packed);
    switchComponent(MOVEMENT_SLOT, true);
    unit.movement().setExplicitX(toX);
    unit.movement().setExplicitY(toY);
    TargetView reference = unit.targeting().getReference();
    setter.setState(view, GridEntityState.INGAME_PATHFIND);
    world.lanesSwitched(this, x, y, toX, toY, reference);
  }

  /**
   * Whether a chain's next target beyond the back-dash radius must lie ahead along the side's
   * direction, rather than along the chain's first dash: so in the standard game.
   */
  private static final boolean GOLDEN_KNIGHT_ONLY_DASH_FORWARD = true;

  /** Whether a chain stops when its current target is a crown tower: so in the standard game. */
  private static final boolean LOGIC_CHAIN_DASH_STOP_AT_TOWER = true;

  /**
   * The neighbour query: every character of the live list, in its order, the unit itself among
   * them, whose centre lies within the radius of the point.
   */
  private List<WorldEntity> neighbours(int x, int y, int radius) {
    List<WorldEntity> found = new ArrayList<>();
    long reach = (long) radius * radius;
    for (BattleEntity entity : world.getHolder().entities()) {
      if (entity instanceof WorldEntity other) {
        long dx = Math.abs((long) other.getView().getX() - x);
        long dy = Math.abs((long) other.getView().getY() - y);
        if (dx <= radius && dy <= radius && dx * dx + dy * dy <= reach) {
          found.add(other);
        }
      }
    }
    return found;
  }

  /**
   * The unit's dash onto an object, as a dashing attack chain begins it: its movement component
   * switched on, its dash ended, the object taken as its reference through the setter's re-check,
   * and its dash started at the object's point, stopping short of it by the object's collision
   * radius.
   */
  void dashOnto(WorldEntity target) {
    switchComponent(MOVEMENT_SLOT, true);
    dashReset();
    SelectionChain selection = unit.selection();
    ReferenceSetter.setReference(
        unit.targeting(),
        target.getTargetView(),
        false,
        false,
        false,
        selection,
        selection.getOutcome());
    chainedDashStart(
        target.getView().getX(), target.getView().getY(), target.getView().getCollisionRadius());
  }

  /**
   * A dash's start for a unit whose dashes chain, as a dashing attack chain or its own chain makes
   * it: unless the unit may not dash, the chain's count goes up, its reference joins the hit list,
   * and the first dash's vector is kept; then the dash itself starts, toward the point, stopping
   * short of it by the radius and in reach of the reference.
   */
  private void chainedDashStart(int x, int y, int radius) {
    GridEntity view = getView();
    if ((view.getFlags() & view.getFlagBits().noDash()) != 0) {
      return;
    }
    TargetingState t = unit.targeting();
    TargetView reference = t.getReference();
    int fromX = view.getX();
    int fromY = view.getY();
    if (getData().dashCount() >= 1) {
      t.setDashChainCount(t.getDashChainCount() + 1);
      t.getHitTargetIds().add(reference.id());
      if ((t.getDashFirstX() | t.getDashFirstY()) == 0) {
        t.setDashFirstX(x - fromX);
        t.setDashFirstY(y - fromY);
      }
    }
    DashStart.start(
        view,
        isActive(MOVEMENT_SLOT) ? unit.movement() : null,
        unit.movementConfig(),
        x,
        y,
        radius,
        1,
        world.getGrid().getWidth(),
        world.getGrid().getHeight(),
        setter);
    world.dashStarted(this, reference, fromX, fromY, x, y);
    world.chainDashStarted(this, fromX, fromY, x, y, radius);
  }

  /**
   * The dash's end, and the targeting component's reset as it is switched off: the chain's count
   * and first vector cleared and, for a unit whose dashes chain, the hit list emptied and the
   * reference given up.
   */
  private void dashReset() {
    TargetingState t = unit.targeting();
    t.setDashChainCount(0);
    t.setDashFirstX(0);
    t.setDashFirstY(0);
    if (getData().dashCount() >= 1) {
      t.getHitTargetIds().clear();
      SelectionChain selection = unit.selection();
      ReferenceSetter.setReference(t, null, false, false, false, selection, selection.getOutcome());
    }
  }

  /**
   * A chain's next dash, as the unit leaves the dashing state for the moving state with fewer
   * dashes made than its count. The neighbour query around the landing point over the secondary
   * range, or the greatest dash range without one: each object but the current reference asked of
   * the validator, which refuses every target the chain has hit, and measured; the nearest is taken
   * within the back-dash radius, and beyond it only ahead along the side's direction, or anywhere
   * before a first dash, or, with the forward-only global clear, along the first dash. A crown
   * tower as the current reference stops the chain. The winner becomes the reference and the next
   * dash starts at its point, stopping at it.
   *
   * @return true when a next dash started
   */
  private boolean chainDash(int newState) {
    TargetingState t = unit.targeting();
    UnitData data = getData();
    if (newState != GridEntityState.MOVING) {
      return false;
    }
    int count = t.getDashChainCount();
    TargetView reference = t.getReference();
    if (count < 1 || count >= data.dashCount()) {
      world.chainDashEnded(this, count, reference);
      return false;
    }
    GridEntity view = getView();
    int x = view.getX();
    int y = view.getY();
    int radius = data.dashSecondaryRange() > 0 ? data.dashSecondaryRange() : data.dashMaxRange();
    int backSquared = data.backDashRadius() * data.backDashRadius();
    SelectionChain selection = unit.selection();
    WorldEntity best = null;
    int bestSquared = Integer.MAX_VALUE;
    for (WorldEntity other : neighbours(x, y, radius)) {
      if (other.getTargetView() == reference) {
        continue;
      }
      if (!selection.validate(other.getTargetView(), ReferenceValidator.MODE_TAKE)) {
        continue;
      }
      int squared = FixedMath.squaredDistance(x, y, other.getView().getX(), other.getView().getY());
      if (squared > bestSquared) {
        continue;
      }
      if (squared > backSquared) {
        int dy = other.getView().getY() - y;
        if (!GOLDEN_KNIGHT_ONLY_DASH_FORWARD) {
          int dx = other.getView().getX() - x;
          if (dx * t.getDashFirstX() + t.getDashFirstY() * dy < 0) {
            continue;
          }
        } else if ((t.getDashFirstX() | t.getDashFirstY()) != 0) {
          int sign = (side() & 1) == 0 ? 1 : -1;
          if (sign * dy < 0) {
            continue;
          }
        }
      }
      best = other;
      bestSquared = squared;
    }
    if (best == null
        || reference != null && reference.crownTower() && LOGIC_CHAIN_DASH_STOP_AT_TOWER) {
      world.chainDashEnded(this, count, reference);
      return false;
    }
    world.chainDashed(this, best, count, x, y);
    ReferenceSetter.setReference(
        t, best.getTargetView(), false, false, false, selection, selection.getOutcome());
    chainedDashStart(best.getView().getX(), best.getView().getY(), 0);
    return true;
  }

  /**
   * What a friend-collecting run of the unit asks of the battle: the object query around it, the
   * live list by id, the guarded squared distance from it, its tag and hit speed, the target locks,
   * its ability, and the hooks and projectiles the run schedules and fires.
   */
  @Override
  public FriendCollecting friendCollecting() {
    return new FriendCollecting() {
      @Override
      public int ownerId() {
        return getId();
      }

      @Override
      public List<Integer> query(int radius, GameObjectFilter filter) {
        List<Integer> ids = new ArrayList<>();
        for (WorldEntity entity : world.objectQuery(CharacterEntity.this, radius, filter)) {
          ids.add(entity.getId());
        }
        return ids;
      }

      @Override
      public boolean found(int id) {
        return world.liveObject(id) instanceof CharacterEntity;
      }

      @Override
      public int squaredDistance(int id) {
        GridEntity other = ((WorldEntity) world.liveObject(id)).getView();
        return FixedMath.squaredDistance(
            getView().getX(), getView().getY(), other.getX(), other.getY());
      }

      @Override
      public boolean noAttack() {
        return (getView().getFlags() & getView().getFlagBits().noAttack()) != 0;
      }

      @Override
      public int hitSpeed(int stepMs) {
        return getBuffs().hitSpeed(stepMs);
      }

      @Override
      public TargetLocks locks() {
        return world.locks();
      }

      @Override
      public void requestAbility() {
        CharacterEntity.this.requestAbility();
      }

      @Override
      public void schedule(int targetId, BattleAction action) {
        WorldEntity target = (WorldEntity) world.liveObject(targetId);
        BattleAction built = world.getActions().build(action.name(), world.binding(target));
        target.actionHolder().schedule(built, ActionHolder.OWN_DELAY, false, actionHolder());
      }

      @Override
      public void launch(ProjectileData projectile, int friendId) {
        world.launchAt(CharacterEntity.this, projectile, (WorldEntity) world.liveObject(friendId));
      }
    };
  }

  /**
   * What the evolved Musketeer's snipe asks of the battle about the character: its targeting
   * component, tags and buffs, the box it looks in, the validator, where objects stand, its
   * reference and the reach of its row; the reference it takes through the setter or drops as it
   * stands, and LOCK_TARGET it raises.
   */
  @Override
  public MusketeerSnipe.Host snipeHost(MusketeerSnipe action) {
    return new MusketeerSnipe.Host() {
      @Override
      public boolean targetingOn() {
        // The deploy, and the wait before it, keep the targeting component off.
        return isActive(TARGETING_SLOT) && !deploying() && !waiting();
      }

      @Override
      public boolean lockTargetTagged() {
        GridEntity view = getView();
        return (view.getFlags() & view.getFlagBits().lockTarget()) != 0;
      }

      @Override
      public boolean referenceLockedByBuff() {
        TargetingState t = unit.targeting();
        return t.getReference() != null && t.getTargetLockingBuffs() > 0;
      }

      @Override
      public boolean hitsStopped() {
        return getBuffs().hitSpeed(SNIPE_HIT_STEP) == 0;
      }

      @Override
      public List<Integer> candidates(
          int halfWidth, int halfLength, int minimumRange, GameObjectFilter filter) {
        GridEntity at = getView();
        long reach = (long) at.getCollisionRadius() + minimumRange;
        long reachSquared = reach * reach;
        List<long[]> kept = new ArrayList<>();
        for (WorldEntity object :
            world.rectangleQuery(
                side(), getData().name(), at.getX(), at.getY(), halfWidth, halfLength, filter)) {
          GridEntity view = object.getView();
          long dx = view.getX() - at.getX();
          long dy = view.getY() - at.getY();
          long radius = view.getCollisionRadius();
          long beyond = Math.max(0, dx * dx + dy * dy - radius * radius);
          if (minimumRange >= 1 && reachSquared > beyond) {
            continue;
          }
          kept.add(new long[] {dx * dx + dy * dy, object.getId()});
        }
        // Nearest first; the sort is stable, so objects at one distance keep the box's order.
        kept.sort(Comparator.comparingLong(candidate -> candidate[0]));
        List<Integer> ids = new ArrayList<>();
        for (long[] candidate : kept) {
          ids.add((int) candidate[1]);
        }
        return ids;
      }

      @Override
      public boolean validates(int id, boolean takeMode) {
        return unit.selection()
            .validate(
                object(id).getTargetView(),
                takeMode ? ReferenceValidator.MODE_TAKE : ReferenceValidator.MODE_RECHECK);
      }

      @Override
      public int x(int id) {
        return object(id).getView().getX();
      }

      @Override
      public int radius(int id) {
        return object(id).getView().getCollisionRadius();
      }

      @Override
      public int x() {
        return getView().getX();
      }

      @Override
      public int radius() {
        return getView().getCollisionRadius();
      }

      @Override
      public int squaredDistance(int id) {
        GridEntity view = object(id).getView();
        return FixedMath.squaredDistance(
            view.getX(), view.getY(), getView().getX(), getView().getY());
      }

      @Override
      public int reference() {
        TargetView reference = unit.targeting().getReference();
        return reference == null ? MusketeerSnipe.NONE : reference.id();
      }

      @Override
      public boolean inRowReach(int id) {
        int reach = getData().range() + getData().collisionRadius();
        return RangeTest.rangeTest(
            object(id).getTargetView(), getView().getX(), getView().getY(), reach, 0, false);
      }

      @Override
      public void take(int id) {
        SelectionChain selection = unit.selection();
        ReferenceSetter.setReference(
            unit.targeting(),
            object(id).getTargetView(),
            false,
            false,
            false,
            selection,
            selection.getOutcome());
      }

      @Override
      public void dropReference() {
        TargetingState t = unit.targeting();
        t.setReference(null);
        t.setKeptByPendingDamageCheck(false);
      }

      @Override
      public void raiseLockTarget() {
        CharacterEntity.this.raiseLockTarget();
      }

      private WorldEntity object(int id) {
        return (WorldEntity) world.liveObject(id);
      }
    };
  }

  /**
   * What a Goblin Hut's life state on the character asks of the battle: the object query around it
   * testing buildings by their squares, the live list by id, where objects stand and their radii,
   * its own validator, its targeting component, its spawn speed, the actions it schedules on itself
   * and the children it makes.
   */
  @Override
  public GoblinHutLife goblinHutLife() {
    return new GoblinHutLife() {
      @Override
      public int ownerX() {
        return getView().getX();
      }

      @Override
      public int ownerY() {
        return getView().getY();
      }

      @Override
      public int reach() {
        return getData().collisionRadius() + getData().range();
      }

      @Override
      public List<Integer> query(int radius, GameObjectFilter filter) {
        List<Integer> ids = new ArrayList<>();
        for (WorldEntity entity : world.shapeQuery(CharacterEntity.this, radius, filter)) {
          ids.add(entity.getId());
        }
        return ids;
      }

      @Override
      public boolean live(int id) {
        return world.liveObject(id) instanceof WorldEntity;
      }

      @Override
      public int x(int id) {
        return object(id).getView().getX();
      }

      @Override
      public int y(int id) {
        return object(id).getView().getY();
      }

      @Override
      public int radius(int id) {
        return object(id).getView().getCollisionRadius();
      }

      @Override
      public boolean valid(int id) {
        return ReferenceValidator.sharedValidate(
            unit.targeting(),
            object(id).getTargetView(),
            false,
            getData().targetOnlyBuildings(),
            false,
            false,
            world.getValidatorQueries());
      }

      @Override
      public boolean active() {
        return isActive(TARGETING_SLOT);
      }

      @Override
      public int spawnSpeed(int stepMs) {
        return getBuffs().spawnSpeed(stepMs);
      }

      @Override
      public void schedule(BattleAction action) {
        if (action != null) {
          actionHolder().schedule(action, ActionHolder.OWN_DELAY, false, actionHolder());
        }
      }

      @Override
      public int arenaWidth() {
        return world.getGrid().getWidth();
      }

      @Override
      public int arenaHeight() {
        return world.getGrid().getHeight();
      }

      @Override
      public int[] relocated(int x, int y) {
        int packed =
            Relocation.relocate(
                world.getGrid().getWidth(),
                world.getGrid().getHeight(),
                x,
                y,
                -1,
                world.getGrid()::water);
        return new int[] {Relocation.unpackX(packed), Relocation.unpackY(packed)};
      }

      @Override
      public void spawn(String row, int x, int y) {
        world.spawnOne(CharacterEntity.this, row, x, y);
      }

      @Override
      public void log(GoblinHutLifeState.Event event) {
        world.goblinHutLogged(CharacterEntity.this, event);
      }

      private WorldEntity object(int id) {
        return (WorldEntity) world.liveObject(id);
      }
    };
  }

  /**
   * The targeting visit's turn toward the reference: the facing becomes the vector from the unit to
   * it, scaled to 256; a reference on the unit's own spot leaves the zero vector. A turn toward no
   * reference changes nothing, and is refused for a character running a target indicator attack,
   * whose shot reads the facing and where that turn is not established.
   */
  private void turnToward(TargetView reference) {
    if (reference == null) {
      for (ActionInstance instance : actionHolder().running()) {
        if (instance.getAction() instanceof TargetIndicatorAttack) {
          throw new UnsupportedOperationException(
              name() + " turns toward no reference with a target indicator attack, not modelled");
        }
      }
      return;
    }
    int[] vector = {reference.x() - getView().getX(), reference.y() - getView().getY()};
    FixedMath.normalize(vector, FACING_LENGTH);
    getView().setDirX(vector[0]);
    getView().setDirY(vector[1]);
  }

  /**
   * What the evolved Rage Barbarian's ghost wait asks of the battle about the unit: whether its row
   * sets NO_DAMAGE, its spawned-child immunity renewed, and its buff instances.
   */
  @Override
  public LumberjackGhostWait.Host lumberjackGhostHost(LumberjackGhostWait action) {
    return new LumberjackGhostWait.Host() {
      @Override
      public String name() {
        return CharacterEntity.this.name();
      }

      @Override
      public boolean rowSetsNoDamage() {
        return (getData().gameTagsToSet() & getView().getFlagBits().noDamage()) != 0;
      }

      @Override
      public void renewSpawnImmunity() {
        startSpawnImmunity();
      }

      @Override
      public int firstRemainingMs(String buff) {
        for (BuffInstance instance : getBuffs().items()) {
          if (instance.getBuff().name().equals(buff)) {
            return instance.getRemaining();
          }
        }
        return -1;
      }

      @Override
      public boolean carries(String buff) {
        return getBuffs().carries(buff);
      }
    };
  }

  /**
   * What the evolved Royal Ghost's run asks of the battle about the unit: its invisibility, its
   * reference, where things stand, and the areas it makes. Refused: a clone, whose clone byte the
   * areas would copy, and a tower.
   */
  @Override
  public GhostEvo.Host ghostEvoHost(GhostEvo action) {
    if (isClone()) {
      throw new UnsupportedOperationException(
          name() + " is a clone running " + action.name() + ", which is not modelled");
    }
    CharacterEntity ghost = this;
    return new GhostEvo.Host() {
      @Override
      public void started(int phase) {
        world.ghostEvoStarted(ghost, action.name(), phase);
      }

      @Override
      public boolean invisible() {
        return getBuffs().invisibleCount() > 0;
      }

      @Override
      public BattleEntity reference() {
        TargetView reference = getTargeting().getReference();
        return reference == null ? null : world.entityOf(reference.getEntity());
      }

      @Override
      public int x(BattleEntity entity) {
        return ((WorldEntity) entity).getView().getX();
      }

      @Override
      public int y(BattleEntity entity) {
        return ((WorldEntity) entity).getView().getY();
      }

      @Override
      public int x() {
        return getView().getX();
      }

      @Override
      public int y() {
        return getView().getY();
      }

      @Override
      public ActionHolder makeArea(String row, int x, int y, BattleEntity target) {
        return world.ghostArea(ghost, row, x, y, (WorldEntity) target).actionHolder();
      }

      @Override
      public void summoned(BattleEntity reference, int x, int y, int countdownMs) {
        world.ghostSummoned(ghost, (WorldEntity) reference, x, y, countdownMs);
      }
    };
  }

  /**
   * Takes the reference a summon area hands the unit as it is made: through the validator's take
   * mode and, accepted, the setter, for an object still alive; nothing for one that is not.
   *
   * @param reference the reference handed over, or null for none
   */
  void takeSummonReference(WorldEntity reference) {
    if (reference == null || !HitPoints.alive(reference.getHitPoints())) {
      return;
    }
    SelectionChain selection = unit.selection();
    if (!selection.validate(reference.getTargetView(), ReferenceValidator.MODE_TAKE)) {
      return;
    }
    ReferenceSetter.setReference(
        unit.targeting(),
        reference.getTargetView(),
        false,
        false,
        false,
        selection,
        selection.getOutcome());
  }

  /**
   * The reveal a summon's maker runs as it is made: the combat gate, as the state visit's tail runs
   * it, for a row that does not hide before its first hit.
   */
  void summonReveal() {
    combatGate(isActive(TARGETING_SLOT) && !deploying() && !waiting(), setter::prepareRoute);
  }

  /** The hit step a snipe asks the character's buffs to scale, which a stun scales to 0. */
  private static final int SNIPE_HIT_STEP = 100;

  /** The step a counter scales by the character's hit speed, which below 1 means a stun. */
  private static final int COUNTER_SCALE_STEP_MS = 50;

  /**
   * What a counter's run on the character asks as a hit reaches it, the gates in the game's order:
   * the source must be a character, a building or a tower (a projectile or an area effect is never
   * countered: no row counters projectiles); one the included filter passes, asked for the
   * character's team, skips the next two tests; else one in the air is countered only under
   * CounterFlying, and one whose row's Range is beyond the threshold never. Then the character must
   * not carry the no-attack tag, must have its targeting on or be deploying under DeployActive, and
   * its hit speed must scale a step to at least 1. A counter clears the attack in progress on its
   * targeting and raises the no-pushback tag in the one-step word.
   */
  @Override
  public Counter.Host counterHost(Counter action) {
    return (columns, source) -> {
      if (!(source instanceof WorldEntity attacker)) {
        return null;
      }
      GameObjectFilter included = columns.includedFilter();
      if (included == null || !included.matches(attacker.filterSubject(), side() & 1, name())) {
        if (!columns.counterFlying() && attacker.layerAir()) {
          return null;
        }
        if (attacker.getData().range() > columns.attackerRangeThreshold()) {
          return null;
        }
      }
      GridEntity view = getView();
      if ((view.getFlags() & view.getFlagBits().noAttack()) != 0) {
        return null;
      }
      if (!isActive(TARGETING_SLOT)
          && !(view.getState() == GridEntityState.DEPLOYING && columns.deployActive())) {
        return null;
      }
      if (getBuffs().hitSpeed(COUNTER_SCALE_STEP_MS) < 1) {
        return null;
      }
      // The attacker's buff on damage is applied as its hit is dealt, before the drain counters
      // the hit, where the game applies it only to a hit the entry lets through.
      if (attacker.getData().buffOnDamage() != null) {
        throw new UnsupportedOperationException(
            action.name()
                + " counters a hit of "
                + attacker.name()
                + ", whose BuffOnDamage on a countered hit is not modelled");
      }
      getTargeting().clearAttack();
      view.setPendingFlags(view.getPendingFlags() | view.getFlagBits().noPushback());
      return attacker.actionHolder();
    };
  }

  /**
   * What a Boss Bandit ability's run on the character asks of the battle: the battle tick, its id,
   * the target locks and its state; its start and steps are told to the battle's observers.
   */
  @Override
  public BossBanditAbility.Host bossBanditHost(BossBanditAbility action) {
    return new BossBanditAbility.Host() {
      @Override
      public int tick() {
        return world.tick();
      }

      @Override
      public int id() {
        return getId();
      }

      @Override
      public TargetLocks locks() {
        return world.locks();
      }

      @Override
      public int state() {
        return getView().getState();
      }

      @Override
      public void started(
          BossBanditAbility row, int phase, int warpTick, int lockTick, List<Boolean> requests) {
        world.bossBanditAbilityStarted(
            CharacterEntity.this, row, phase, warpTick, lockTick, requests);
      }

      @Override
      public void stepped(boolean locked, int releaseMs, List<String> calls) {
        world.bossBanditAbilityStepped(CharacterEntity.this, locked, releaseMs, calls);
      }
    };
  }

  /**
   * The ready action on the character: with ForceCooldown, the champion slot of its side's king
   * that follows it, a clone followed by none, restarts its cooldown at the full cooldown; nothing
   * without such a slot.
   */
  @Override
  public void readyChampionAbility(ReadyChampionAbility action) {
    ChampionController slot = followingSlot();
    if (slot != null && action.isForceCooldown()) {
      slot.forceCooldown();
    }
  }

  /**
   * What a mark's run, or its hand-over's, on the character asks of the battle: the objects its
   * resolver's filter lets through, the character's position, the cooldown of the champion slot
   * that follows the character, its state, whether its ability is pending and the steps left before
   * its ability's effect fires.
   */
  @Override
  public SetIndicatorOnTarget.Host markHost(BattleAction action) {
    return new SetIndicatorOnTarget.Host() {
      @Override
      public List<SetIndicatorOnTarget.Candidate> candidates(GameObjectFilter filter) {
        List<SetIndicatorOnTarget.Candidate> out = new ArrayList<>();
        for (WorldEntity entity :
            world.resolverCandidates(filter, side() & 1, getData().name(), action.name())) {
          out.add(new MarkCandidate(entity, action.name()));
        }
        return out;
      }

      @Override
      public int x() {
        return getView().getX();
      }

      @Override
      public int y() {
        return getView().getY();
      }

      @Override
      public int abilityCooldownMs() {
        return world.championCooldownMs(CharacterEntity.this);
      }

      @Override
      public int state() {
        return getView().getState();
      }

      @Override
      public boolean abilityPending() {
        return CharacterEntity.this.abilityPending();
      }

      @Override
      public int abilityWarningCountdown() {
        return CharacterEntity.this.abilityWarningCountdown();
      }
    };
  }

  /**
   * What an action that runs another on what its resolver finds asks of the battle about the
   * character: the objects the filter lets through, asked by the character itself, its position,
   * and a row built for and scheduled on what it found or on itself.
   */
  @Override
  public RunOnResolvedObjects.Host resolvedObjectsHost(BattleAction action) {
    return new RunOnResolvedObjects.Host() {
      @Override
      public List<SetIndicatorOnTarget.Candidate> candidates(GameObjectFilter filter) {
        List<SetIndicatorOnTarget.Candidate> out = new ArrayList<>();
        for (WorldEntity entity :
            world.resolverCandidates(
                filter, side() & 1, getData().name(), action.name(), CharacterEntity.this)) {
          out.add(new MarkCandidate(entity, action.name()));
        }
        return out;
      }

      @Override
      public List<SetIndicatorOnTarget.Candidate> candidates(
          GameObjectFilter filter, ConeShape cone) {
        // A Cone turns with the heading the character faces, as a ring spawn's angle shift does.
        List<SetIndicatorOnTarget.Candidate> out = new ArrayList<>();
        for (WorldEntity entity :
            world.resolverConeCandidates(
                getView().getX(),
                getView().getY(),
                FixedMath.angleOfVector(getView().getDirX(), getView().getDirY()),
                cone,
                filter,
                side() & 1,
                getData().name(),
                CharacterEntity.this)) {
          out.add(new MarkCandidate(entity, action.name()));
        }
        return out;
      }

      @Override
      public int x() {
        return getView().getX();
      }

      @Override
      public int y() {
        return getView().getY();
      }

      @Override
      public void schedule(
          SetIndicatorOnTarget.Candidate target,
          String row,
          ActionHolder cause,
          ActionContext context) {
        WorldEntity entity = ((MarkCandidate) target).entity();
        BattleAction built = world.getActions().build(row, world.binding(entity));
        entity.actionHolder().schedule(built, ActionHolder.OWN_DELAY, false, cause, context);
      }

      @Override
      public void scheduleOnOwner(String row, ActionHolder cause, ActionContext context) {
        BattleAction built = world.getActions().build(row, world.binding(CharacterEntity.this));
        actionHolder().schedule(built, ActionHolder.OWN_DELAY, false, cause, context);
      }
    };
  }

  /**
   * Schedules a row on each rider of the character, in the order it made them, each built for its
   * rider, with the given cause and no context.
   */
  @Override
  public void runOnAttached(BattleAction action, String actionToRun, ActionHolder instigator) {
    for (CharacterEntity rider : List.copyOf(riders)) {
      BattleAction built = world.getActions().build(actionToRun, world.binding(rider));
      rider.actionHolder().schedule(built, ActionHolder.OWN_DELAY, false, instigator);
    }
  }

  @Override
  public void warp(WarpCharacter action, int phase) {
    world.warp(this, action, phase);
  }

  /**
   * Builds and starts the flying warp a hand-over launches on the character: the warp's row built
   * for it, which must be a flying warp, its run given the target the mark gave the hand-over and
   * that target's last recorded position.
   */
  @Override
  public ActionInstance launchWarp(
      BattleAction handOver,
      String warpRow,
      SetIndicatorOnTarget.Candidate target,
      int lastX,
      int lastY) {
    if (warpRow == null) {
      throw new UnsupportedOperationException(
          handOver.name() + " is re-triggered with no warp to launch, not modelled");
    }
    BattleAction built = world.getActions().build(warpRow, world.binding(this));
    if (!(built instanceof WarpCharacter warp) || warp.getFlight() == null) {
      throw new UnsupportedOperationException(
          handOver.name() + " launches " + warpRow + ", which is not a flying warp, not modelled");
    }
    WorldEntity entity = null;
    if (target != null) {
      if (!(target instanceof MarkCandidate candidate)) {
        throw new UnsupportedOperationException(
            handOver.name() + " launches " + warpRow + " at an object not in the battle");
      }
      entity = candidate.entity();
    }
    return new WarpRun(warp, this, entity, lastX, lastY);
  }

  /** Raises a flying warp's step tags for one step. */
  void raiseWarpTags(long tags) {
    getView().setPendingFlags(getView().getPendingFlags() | tags);
  }

  /**
   * Moves the character to a warp's landing in one write, as a warp's perform does: no route, no
   * displacement pass.
   */
  void warpTo(int x, int y) {
    getView().setX(x);
    getView().setY(y);
  }

  /** Empties the route and clears its route-leads-away bit, as a warp and a landing knock do. */
  void resetRoute() {
    MovementState movement = unit.movement();
    if (movement == null) {
      return;
    }
    movement.getRoute().clear();
    movement.setRouteLeadsAway(0);
  }

  /** Drops the reference and its pending-damage keep byte, as a warp's target reset does. */
  void resetTargetAfterWarp() {
    unit.targeting().setReference(null);
    unit.targeting().setKeptByPendingDamageCheck(false);
  }

  /** Starts a capture on the character, as the evolved Goblin Cage holds what it catches. */
  @Override
  public ActionInstance captureCharacter(CaptureCharacter action, int phase) {
    world.captureStarted(this, action.name(), phase);
    return new CaptureRun(action, captureHost());
  }

  /**
   * What a capture on the character asks of it: it claims only while its targeting component is on,
   * which a deploy or a stun switches off; it queries around its point and is asked its own side by
   * the filter; its hit timer steps at its hit speed; each hit is its damage per hit at its level
   * and rarity through the damage entry, the character the attacker, passing a hidden unit; a
   * completed drag raises HAS_CAPTURE in its tag word for one step; the capture buff, as the
   * evolved Goblin Cage's row gives it, goes on each capture with the character its parent and
   * source at its level and side, as on a projectile.
   */
  private CaptureHost captureHost() {
    CharacterEntity owner = this;
    return new CaptureHost() {
      @Override
      public BattleEntity owner() {
        return owner;
      }

      @Override
      public int id() {
        return getId();
      }

      @Override
      public String name() {
        return owner.name();
      }

      @Override
      public int x() {
        return getView().getX();
      }

      @Override
      public int y() {
        return getView().getY();
      }

      @Override
      public BattleWorld world() {
        return world;
      }

      @Override
      public ActionHolder actionHolder() {
        return owner.actionHolder();
      }

      // The component is off while the character deploys or waits to, as the combat gate has it.
      @Override
      public boolean claims() {
        return isActive(TARGETING_SLOT) && !deploying() && !waiting();
      }

      @Override
      public List<WorldEntity> query(int radius, GameObjectFilter filter) {
        return world.objectQuery(owner, radius, filter);
      }

      @Override
      public boolean passes(WorldEntity unit, GameObjectFilter filter) {
        return filter.matches(unit.filterSubject(), side() & 1, getData().name());
      }

      @Override
      public void buff(WorldEntity unit, String buff, int timeMs) {
        world.captureBuff(owner, unit, buff, timeMs);
      }

      @Override
      public void scheduleOnOwner(WorldEntity cause, String action) {
        owner
            .actionHolder()
            .schedule(
                world.getActions().build(action, world.binding(owner)),
                ActionHolder.OWN_DELAY,
                false,
                cause.actionHolder());
      }

      @Override
      public void hasCapture() {
        getView()
            .setPendingFlags(getView().getPendingFlags() | getView().getFlagBits().hasCapture());
      }

      @Override
      public int hitStep(int stepMs) {
        return getBuffs().hitSpeed(stepMs);
      }

      @Override
      public void hit(WorldEntity unit, int damagePerHit) {
        int damage =
            LevelScaling.scale(
                ScalingGlobals.standard(),
                damagePerHit,
                packedLevel(),
                ScalingMode.CARD_DAMAGE,
                getData().rarity());
        world.captureHit(owner, unit, damage);
      }
    };
  }

  /**
   * What a shape selector's run on the character asks of the battle, as the Giant hero form's slap
   * selector runs on itself: the battle tick, the circle around its point that tests buildings by
   * their squares, an object's hit points and shield or its squared distance from the character,
   * its own tag word, side and point and where a pick stands, the holder of a pick, and the actions
   * it schedules, each with the cause and the context the run hands it: on what it picked, and on
   * itself.
   */
  @Override
  public ShapeSelectorHost shapeSelectorHost() {
    return new ShapeSelectorHost() {
      @Override
      public int tick() {
        return world.tick();
      }

      @Override
      public List<Integer> collect(int radius, GameObjectFilter filter) {
        List<Integer> ids = new ArrayList<>();
        for (WorldEntity entity : world.shapeQuery(CharacterEntity.this, radius, filter)) {
          ids.add(entity.getId());
        }
        return ids;
      }

      @Override
      public int score(int id, int mode) {
        WorldEntity object = (WorldEntity) world.liveObject(id);
        if (mode == ShapeSelector.CLOSEST) {
          // The guarded squared distance from the owner's point, its bits flipped.
          return FixedMath.INT_MAX
              ^ FixedMath.squaredDistance(
                  getView().getX(), getView().getY(), object.x(), object.y());
        }
        HitPoints hitPoints = object.getHitPoints();
        if (hitPoints == null) {
          return 0;
        }
        return mode == ShapeSelector.HIGHEST_CURRENT_HP_INCLUDE_SHIELDS
            ? hitPoints.getHitPoints() + hitPoints.getShield()
            : hitPoints.getHitPoints();
      }

      @Override
      public ActionHolder holder(int id) {
        return ((WorldEntity) world.liveObject(id)).actionHolder();
      }

      @Override
      public void schedule(int targetId, String action, ActionHolder cause, ActionContext context) {
        WorldEntity target = (WorldEntity) world.liveObject(targetId);
        BattleAction built = world.getActions().build(action, world.binding(target));
        target.actionHolder().schedule(built, ActionHolder.OWN_DELAY, false, cause, context);
      }

      @Override
      public long ownerTags() {
        return getView().getFlags();
      }

      @Override
      public int ownerSide() {
        return side();
      }

      @Override
      public int ownerX() {
        return getView().getX();
      }

      @Override
      public int ownerY() {
        return getView().getY();
      }

      @Override
      public int x(int id) {
        return ((WorldEntity) world.liveObject(id)).x();
      }

      @Override
      public int y(int id) {
        return ((WorldEntity) world.liveObject(id)).y();
      }

      @Override
      public void scheduleOnOwner(String action, ActionHolder cause, ActionContext context) {
        BattleAction built = world.getActions().build(action, world.binding(CharacterEntity.this));
        actionHolder().schedule(built, ActionHolder.OWN_DELAY, false, cause, context);
      }
    };
  }

  /**
   * What a target indicator attack's run on the character asks of the battle: its targeting
   * component, its hit speed, its flags, its point, row radius and facing, the object query around
   * it testing buildings by their squares, the live list by id, where objects stand, their radii
   * and const-priority offsets, the signals and projectiles it makes, and the actions it schedules
   * on itself. Refused: a clone, whose clone byte the signal and the projectile would copy.
   */
  @Override
  public TargetIndicatorHost targetIndicatorHost() {
    if (isClone()) {
      throw new UnsupportedOperationException(
          name() + " is a clone running a target indicator attack, which is not modelled");
    }
    return new TargetIndicatorHost() {
      @Override
      public int timeStep(int stepMs) {
        return getBuffs().hitSpeed(stepMs);
      }

      // The component is off while the character deploys or waits to, as the combat gate has it.
      @Override
      public boolean active() {
        return isActive(TARGETING_SLOT) && !deploying() && !waiting();
      }

      @Override
      public boolean noAttack() {
        return (getView().getFlags() & getView().getFlagBits().noAttack()) != 0;
      }

      @Override
      public int ownerX() {
        return getView().getX();
      }

      @Override
      public int ownerY() {
        return getView().getY();
      }

      @Override
      public int ownerRadius() {
        return getData().collisionRadius();
      }

      @Override
      public int[] facing() {
        return new int[] {getView().getDirX(), getView().getDirY()};
      }

      @Override
      public List<Integer> query(int radius, GameObjectFilter filter) {
        List<Integer> ids = new ArrayList<>();
        for (WorldEntity entity : world.shapeQuery(CharacterEntity.this, radius, filter)) {
          ids.add(entity.getId());
        }
        return ids;
      }

      @Override
      public boolean live(int id) {
        return world.liveObject(id) != null;
      }

      @Override
      public int x(int id) {
        return object(id).getView().getX();
      }

      @Override
      public int y(int id) {
        return object(id).getView().getY();
      }

      @Override
      public int radius(int id) {
        return object(id).getView().getCollisionRadius();
      }

      @Override
      public int priority(int id) {
        return object(id).getView().getSquaredDistanceReduction();
      }

      @Override
      public int signal(String row, int targetId) {
        WorldEntity target = object(targetId);
        AreaEffectEntity signal = world.indicate(CharacterEntity.this, row, target);
        log(
            new TargetIndicatorAttack.Signalled(
                targetId, signal.getId(), signal.getX(), signal.getY(), signal.getPackedLevel()));
        return signal.getId();
      }

      @Override
      public int launch(String projectile, int signalId, int x, int y, int z) {
        AreaEffectEntity signal = (AreaEffectEntity) world.liveObject(signalId);
        ProjectileEntity launched =
            world.launchAtSignal(CharacterEntity.this, projectile, signal, x, y, z);
        log(
            new TargetIndicatorAttack.Shot(
                launched.getId(),
                signalId,
                launched.getX(),
                launched.getY(),
                launched.getZ(),
                launched.getAimX(),
                launched.getAimY(),
                launched.getPackedLevel(),
                getView().getDirX(),
                getView().getDirY()));
        return launched.getId();
      }

      @Override
      public void endSignal(int signalId) {
        ((AreaEffectEntity) world.liveObject(signalId)).end();
        log(new TargetIndicatorAttack.SignalEnded(signalId));
      }

      @Override
      public void schedule(BattleAction action, int instigatorId) {
        // The signal and the projectile are still waiting to be admitted as they cause it.
        BattleEntity cause = world.liveOrQueued(instigatorId);
        ActionHolder instigator =
            cause instanceof ProjectileEntity projectile
                ? projectile.actionHolder()
                : ((AreaEffectEntity) cause).actionHolder();
        actionHolder().schedule(action, ActionHolder.OWN_DELAY, false, instigator);
      }

      @Override
      public void log(TargetIndicatorAttack.Event event) {
        world.targetIndicatorLogged(CharacterEntity.this, event);
      }

      private WorldEntity object(int id) {
        return (WorldEntity) world.liveObject(id);
      }
    };
  }

  /**
   * What a chain projectile attack's run on the character asks of the battle: its point, height and
   * row radius, its tag word and targeting component, its hit speed, the live list by id, where
   * objects stand and what a projectile flies to, the centre query around a point, the objects'
   * const-priority offsets, the hops it launches and the notice to its listening actions. Refused:
   * a clone, whose clone byte the hops would copy.
   */
  @Override
  public ChainAttackHost chainAttackHost() {
    if (isClone()) {
      throw new UnsupportedOperationException(
          name() + " is a clone running a chain projectile attack, which is not modelled");
    }
    return new ChainAttackHost() {
      @Override
      public int ownerX() {
        return getView().getX();
      }

      @Override
      public int ownerY() {
        return getView().getY();
      }

      @Override
      public int ownerZ() {
        return getView().getZ();
      }

      @Override
      public int ownerRadius() {
        return getData().collisionRadius();
      }

      @Override
      public boolean attackingOrNoAttack() {
        return (getView().getFlags()
                & (getView().getFlagBits().attacking() | getView().getFlagBits().noAttack()))
            != 0;
      }

      @Override
      public boolean targetingOn() {
        return isActive(TARGETING_SLOT);
      }

      @Override
      public int timeStep(int stepMs) {
        return getBuffs().hitSpeed(stepMs);
      }

      @Override
      public boolean live(int id) {
        return world.liveObject(id) != null;
      }

      @Override
      public int x(int id) {
        return object(id).getView().getX();
      }

      @Override
      public int y(int id) {
        return object(id).getView().getY();
      }

      @Override
      public int z(int id) {
        return object(id).getView().getZ();
      }

      @Override
      public int[] projectileAim(int id) {
        ProjectileEntity projectile = (ProjectileEntity) world.liveObject(id);
        WorldEntity target = projectile.getTarget();
        if (target != null && projectile.getData().homing()) {
          GridEntity view = target.getView();
          return new int[] {view.getX(), view.getY(), view.getZ()};
        }
        return new int[] {projectile.getAimX(), projectile.getAimY(), projectile.getAimZ()};
      }

      @Override
      public List<Integer> centreQuery(int x, int y, int radius, GameObjectFilter filter) {
        List<Integer> ids = new ArrayList<>();
        for (WorldEntity entity : world.centreQuery(CharacterEntity.this, x, y, radius, filter)) {
          ids.add(entity.getId());
        }
        return ids;
      }

      @Override
      public int priority(int id) {
        return object(id).getView().getSquaredDistanceReduction();
      }

      @Override
      public int launchFromOwner(String projectile, int targetId) {
        ProjectileData data = world.getRecords().projectile(projectile);
        return world.launchChainHop(CharacterEntity.this, data, object(targetId)).getId();
      }

      @Override
      public int launchFrom(String projectile, int targetId, int x, int y, int z) {
        ProjectileData data = world.getRecords().projectile(projectile);
        return world.launchChainHop(CharacterEntity.this, data, object(targetId), x, y, z).getId();
      }

      @Override
      public void attackEnded() {
        actionHolder().attackEnded();
      }

      private WorldEntity object(int id) {
        return (WorldEntity) world.liveObject(id);
      }
    };
  }

  /**
   * What a net attack's run on the character asks of the battle: its tag word, hit speed and
   * targeting component with its load and attack time, its point and row radius, the object query
   * around it testing buildings by their squares, where objects stand, the target locks, the nets
   * it launches and the actions it schedules on itself. Refused: a clone, whose clone byte the net
   * would copy.
   */
  @Override
  public NetAttackHost netAttackHost() {
    if (isClone()) {
      throw new UnsupportedOperationException(
          name() + " is a clone running a net attack, which is not modelled");
    }
    return new NetAttackHost() {
      @Override
      public boolean noAttack() {
        return (getView().getFlags() & getView().getFlagBits().noAttack()) != 0;
      }

      @Override
      public int timeStep(int stepMs) {
        return getBuffs().hitSpeed(stepMs);
      }

      // The component is off while the character deploys or waits to, as the combat gate has it.
      @Override
      public boolean targetingOn() {
        return isActive(TARGETING_SLOT) && !deploying() && !waiting();
      }

      @Override
      public int loadedMs() {
        return getData().loadTimeMs() - getTargeting().getLoadTimerMs();
      }

      @Override
      public int hitSpeedMs() {
        return getData().hitSpeedMs();
      }

      @Override
      public int attackTimeMs() {
        return getTargeting().getAttackTimerMs();
      }

      @Override
      public int ownerX() {
        return getView().getX();
      }

      @Override
      public int ownerY() {
        return getView().getY();
      }

      @Override
      public int ownerRadius() {
        return getData().collisionRadius();
      }

      @Override
      public List<Integer> query(int radius, GameObjectFilter filter) {
        List<Integer> ids = new ArrayList<>();
        for (WorldEntity entity : world.shapeQuery(CharacterEntity.this, radius, filter)) {
          ids.add(entity.getId());
        }
        return ids;
      }

      @Override
      public int x(int id) {
        return object(id).getView().getX();
      }

      @Override
      public int y(int id) {
        return object(id).getView().getY();
      }

      @Override
      public boolean heldByOther(int id, int channel) {
        return world.lockHeldByOther(getId(), id, channel);
      }

      @Override
      public int launch(String projectile, int targetId, int x, int y, int z) {
        ProjectileData data = world.getRecords().projectile(projectile);
        return world.launchChainHop(CharacterEntity.this, data, object(targetId), x, y, z).getId();
      }

      @Override
      public void schedule(BattleAction action) {
        actionHolder().schedule(action, ActionHolder.OWN_DELAY, false, actionHolder());
      }

      private WorldEntity object(int id) {
        return (WorldEntity) world.liveObject(id);
      }
    };
  }

  @Override
  protected boolean keepsTargetWhileCasting() {
    return getData().ability() != null && getData().ability().keepCurrentTarget();
  }

  /**
   * The spawner block of the state visit, for a row with a spawn character, a building's or a
   * walking unit's alike: from the end of its deploy, each visit that reaches the block - walking,
   * attacking or pushed - takes half the spawn rate - 50 ms without a buff, none under a stun - off
   * its timer, and at 0 or below it fires: one child for a row with an interval, the whole wave at
   * once at the row's spawn radius for one without. The next firing is the interval away within a
   * wave, or the pause away once the wave is made, and never less than 1 ms; a timer that went
   * below 0 carries.
   */
  /**
   * The elixir block of an elixir collector's state visit: its timer gains half the spawn step a
   * visit from the end of its deploy, and once it reaches the row's generation time the collector
   * pays its king the row's amount - but only while that amount and the king's whole elixir come to
   * no more than MAX_MANA. Otherwise the timer is held at the generation time and it tries again
   * the next visit. A payout takes the generation time off the timer.
   */
  private void collectElixir() {
    UnitData data = getData();
    if (data.manaCollectAmount() <= 0) {
      return;
    }
    KingElixir kings = world.getKingElixir();
    if (kings == null) {
      throw new UnsupportedOperationException(
          name() + " collects elixir outside a match, where no king holds any");
    }
    int period = data.manaGenerateTimeMs();
    collectorTimerMs += getBuffs().spawnRate() / 2;
    if (collectorTimerMs < period) {
      return;
    }
    int amount = data.manaCollectAmount();
    if (amount + kings.wholeElixir(side()) > kings.maxMana()) {
      collectorTimerMs = Math.min(collectorTimerMs, period);
      return;
    }
    collectorTimerMs -= period;
    kings.add(side(), amount * KingElixir.SCALE);
    world.elixirCollected(this, amount);
  }

  /**
   * The spawner block of the state visit. A limited spawner whose firings are spent and whose row
   * destroys it at its limit asks to leave: the tick's closing cleanup removes it, with no death
   * slot and no death handler. Then a spawner fires while it has firings left, or, with no limit,
   * whenever it has a time between its firings; its timer runs only then. Each firing of a limited
   * spawner takes one from what it has left.
   */
  private void spawner() {
    UnitData data = getData();
    int interval = data.spawnIntervalMs();
    int pause = data.spawnPauseTimeMs();
    int limit = data.spawnLimit();
    if (limit >= 1 && spawnsLeft <= 0 && data.destroyAtLimit() && !destroyedAtLimit) {
      destroyedAtLimit = true;
      world.destroyedAtLimit(this);
    }
    boolean due = spawnsLeft > 0 ? interval + pause >= 1 : limit <= 0 && interval + pause > 0;
    if (data.spawnCharacter() == null || !due) {
      return;
    }
    // A spawner under NO_SUMMON holds its timer; a firing already due still fires.
    if ((getView().getFlags() & getView().getFlagBits().noSummon()) == 0) {
      spawnTimer -= getBuffs().spawnRate() / 2;
    }
    if (spawnTimer > 0) {
      return;
    }
    int number = data.spawnNumber();
    int count = interval != 0 ? 1 : number;
    int radius = interval != 0 ? 0 : data.spawnRadius();
    String row = spawnRow(data);
    world.liveSpawn(this, row, count, radius);
    if (limit >= 1) {
      spawnsLeft--;
    }
    spawnWaveMade += count;
    int next;
    if (spawnWaveMade < number) {
      next = interval;
    } else {
      // A complete wave hands the turn to the next row set, back to the first after the last.
      spawnWaveMade = 0;
      int rows = 1 + (data.spawnCharacter2() != null ? 1 : 0);
      rows += data.spawnCharacter3() != null ? 1 : 0;
      spawnRowTurn = (spawnRowTurn + 1) % rows;
      next = pause;
    }
    spawnTimer = spawnTimer + next > 1 ? spawnTimer + next : 1;
    world.spawnerFired(this, row, count, radius, spawnTimer, spawnWaveMade);
  }

  /** The row the spawner's current wave makes, by its turn. */
  private String spawnRow(UnitData data) {
    return switch (spawnRowTurn) {
      case 1 -> data.spawnCharacter2();
      case 2 -> data.spawnCharacter3();
      default -> data.spawnCharacter();
    };
  }

  /** Chooses, keeps or drops the character's target and decides whether it attacks this tick. */
  private final class TargetingComponent implements BattleComponent {

    @Override
    public int index() {
      return TARGETING_SLOT;
    }

    @Override
    public void visit() {
      if (deploying() || waiting()) {
        return;
      }
      if (parent != null
          && (parent.getView().getFlags() & parent.getView().getFlagBits().noAttack()) != 0) {
        throw new UnsupportedOperationException(
            name() + " rides on a parent that may not attack, whose hold on its hit no run holds");
      }
      unit.targeting().setRouteLeadsAway(unit.movement().getRouteLeadsAway() != 0);
      SelectionChain selection = unit.selection();
      selection.beginTick();
      boolean loading = unit.targeting().isSpecialLoadPending();
      TargetingVisit.targetingVisit(
          unit.targeting(), getView(), unit.movement(), selection, selection.getOutcome());
      if (!loading && unit.targeting().isSpecialLoadPending()) {
        specialArmed();
      }
      if (selection.getOutcome().isResumeRequested()) {
        ResumeHelper.resume(
            getView(), unit.stateConfig(), stateQueries(), new ArrayList<>(), setter);
      }
    }
  }

  /**
   * The hit points' own visit, in the third component pass: outside the deploying, waiting and
   * spawn-pathfinding states, and while it has hit points, the lifetime decay takes its step; the
   * step that takes the last hit point is a death of its own.
   */
  private final class HitPointsComponent implements BattleComponent {

    @Override
    public int index() {
      return HIT_POINTS_SLOT;
    }

    @Override
    public void visit() {
      // A shield that is up is tagged in every state and at any hit points, from the next step.
      if (getHitPoints().getShield() >= 1) {
        getView()
            .setPendingFlags(getView().getPendingFlags() | getView().getFlagBits().hasShield());
      }
      int state = getView().getState();
      if (getHitPoints().getHitPoints() < 1
          || state == GridEntityState.DEPLOYING
          || state == GridEntityState.WAITING_TO_DEPLOY
          || state == GridEntityState.SPAWN_PATHFIND) {
        return;
      }
      int before = getHitPoints().getHitPoints();
      if (decay()) {
        world.decayDeath(CharacterEntity.this, before);
      }
    }
  }

  /** Prepares and follows the character's route, steers it around others and applies pushes. */
  private final class MovementComponent implements BattleComponent {

    @Override
    public int index() {
      return MOVEMENT_SLOT;
    }

    /**
     * Before every movement pass, active or not: records whether the unit stands anywhere but where
     * the tick began, its position copy, which the next tick's overlay build reads for an occluder.
     */
    @Override
    public void refresh() {
      GridEntity view = getView();
      view.setMovedSinceCopy(view.getX() != view.getPrevX() || view.getY() != view.getPrevY());
    }

    @Override
    public void visit() {
      speedBudget = 0;
      // Switched off while the unit waits its turn to deploy, and from its death on.
      if (!getView().isMovementActive()) {
        return;
      }
      // A deploying unit is visited too: it asks for no route and gets no speed, so only a push
      // from another unit can move it. A rider is placed around its parent and nothing else.
      GridMovementQueries queries = movementQueries();
      if (parent != null) {
        UnitData data = getData();
        MovementVisit.movementVisit(
            unit.movement(),
            getView(),
            new AttachedParent(parent.getView(), attachAngle),
            MovementConfig.forRider(
                data.spawnAngleShift(),
                data.spawnMaxAngle(),
                data.spawnAttachMaxRotation(),
                data.flyingHeight()),
            MovementConfig.forParent(parent.getData().spawnRadius()),
            queries,
            false,
            movementChain(queries));
        return;
      }
      MovementVisit.movementVisit(
          unit.movement(),
          getView(),
          null,
          unit.movementConfig(),
          null,
          queries,
          false,
          movementChain(queries));
      speedBudget = queries.lastSpeedBudget();
      for (int[] r : queries.relocations()) {
        world.relocated(CharacterEntity.this, r[0], r[1], r[2], r[3]);
      }
      boolean full = unit.movement().getChargeProgress() >= MovementState.CHARGE_COMPLETE;
      if (charged && !full) {
        world.chargeLost(CharacterEntity.this);
      }
      charged = full;
    }
  }
}
