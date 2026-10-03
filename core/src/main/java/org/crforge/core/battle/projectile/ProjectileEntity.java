package org.crforge.core.battle.projectile;

import static org.crforge.core.util.ValidationUtils.checkArgument;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.EntityActions;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.ActionOwner;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.CaptureCharacter;
import org.crforge.core.battle.action.DamageType;
import org.crforge.core.battle.action.ExecutionerEvoProjectile;
import org.crforge.core.battle.action.GiantBufferBuff;
import org.crforge.core.battle.action.RollingProjectile;
import org.crforge.core.battle.spawn.SpawnArguments;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.battle.unit.AreaEffectEntity;
import org.crforge.core.battle.unit.BattleWorld;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.crforge.core.pathfinding.combat.PackedLevel;
import org.crforge.core.pathfinding.combat.RarityTable;
import org.crforge.core.pathfinding.combat.ScalingGlobals;
import org.crforge.core.pathfinding.math.FixedMath;
import org.crforge.core.pathfinding.state.FollowedObject;
import org.crforge.core.pathfinding.target.TargetView;
import org.crforge.core.pathfinding.target.TargetingState;

/**
 * A projectile in flight: an entity of its own kind in the same holder as the characters, so that
 * it is visited in the same passes and removed by the same cleanup.
 *
 * <p>A projectile has no components. Its flight is its post-hook, which the holder runs for every
 * projectile before any character's, because a projectile's id band precedes the characters'. Its
 * id is given the moment its launcher hands it to the holder, in the attack tick, and it enters the
 * live list at that tick's closing cleanup, so it first flies on the tick after its launch and
 * arrives when the distance left to its aim is no more than one step of its speed. On arrival it is
 * released, which is what makes it removable, and its impact deals its damage to its target, or,
 * for a row with a radius, to everything in the circle around its aim.
 *
 * <p>The launch fixes the start and the aim, at the row's constant height when it has one; a homing
 * projectile re-pins its aim onto its target every step, and, when the target leaves the battle,
 * the removal notice leaves the aim where the target last stood and forgets the target, so the
 * projectile flies on and lands on nothing. One that does not home keeps its target too and lands
 * on it wherever it stands, unless the target has left.
 *
 * <p>A homing projectile with a target puts the damage it will deal on the target as pending, with
 * its flight time, when the holder admits it, and again at each chained hop; it hands the damage
 * back as it arrives, or as it is released or finished early. A target the damage will kill is then
 * refused to a projectile attacker, and kept by the one whose shot it is.
 *
 * <p>A projectile that kills something is the cause of the death hooks it runs, so it has an action
 * holder too, made the first time it causes one, which carries its level and nothing else.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the projectile's kind and id band, its flight as the post-hook from the tick"
            + " after its launch, the launch geometry from the owner's launch columns, the level"
            + " re-based on the projectile row's rarity, the constant height, the homing re-aim, the"
            + " straight flight"
            + " with the arc height, the arrival on the step that reaches the aim, the release"
            + " that makes it removable, the single impact on a target that still has hit points"
            + " with the crown-tower damage for a crown tower, the area impact of a row with a"
            + " radius, and the removal notice for a target, a homing target, an owner and a root"
            + " owner that left. Held by the Musketeer and Wizard runs' launches, positions and"
            + " impacts, the constant height by the Royal Giant's and the Elite Archer's; an action holder, made when the projectile first causes an action, as the"
            + " cause of the death hooks of what it kills, held by the Tombstone's death."
            + " Supplied, not settled: the projectile's own collision radius is zero, and no buff"
            + " changes its damage. A deflection's redirect at the root owner - the damage"
            + " registered on its target handed back, the deflector's side, the relaunch from"
            + " where it stands with the deflector as launcher and owner, the damage registered"
            + " on the root owner - and the deflected share of its damage at the impact, held by"
            + " monk_ability_tower, where the princess tower's arrows come back for 27, and"
            + " monk_ability_musketeer, where the Musketeer's shots kill it; the hand-back, the"
            + " side and the registration by BattleMonkTest. A spell's cast from"
            + " the king tower, its delay, its chain and ring point, a thrown projectile's aim at"
            + " its spawned one's body height, and a spawned projectile's launch from its parent"
            + " are held by the spell runs; the limited-time homing by the Elite Archer's."
            + " The chained hop is held by the Electro Dragon's, and the pingpong launch that holds"
            + " the launcher's targeting until the projectile comes back by the Axe Man's."
            + " The random delay a unit's launch draws is held by the Hunter's. The launch at a"
            + " target indicator attack's signal, to its point, the signal kept as what it was"
            + " fired at and forgotten as it leaves, by goblin_machine_knight and"
            + " goblin_machine_tower. The pending damage"
            + " a homing shot registers as it starts, with its flight time from where it stands,"
            + " is held by every tower's re-lock after its arrow's kill in the battle references,"
            + " and the registration at a chained hop by electro_dragon_knights; the crown-tower"
            + " amount, the homing gate and the hand-backs at a release and a collision's finish"
            + " by unit tests alone, since every reference shot hands its damage back as it"
            + " arrives. The row's starting action, scheduled on the projectile's own holder as the"
            + " holder admits it, itself the cause, so it runs in the projectile's phase-1 pending"
            + " pass of the next tick before its first flight step; the listening runs of its"
            + " holder changing a hit's damage along its way and hearing of each hit before its"
            + " damage is taken off; and a swap of its row for one alike in the battle, held by"
            + " ice_axe_barbarians and axe_man_ev1_barbarians."
            + " Not modelled: a pingpong projectile, or one with a random delay, that a spell casts or an impact spawns, the angular delay, the drag-back"
            + " hook, the custom movement, the far-distance clamp with its cell pull, a redirect to"
            + " a point, and a deflection past the most a projectile takes.")
public class ProjectileEntity extends BattleEntity
    implements ActionOwner, SpawnHost, FollowedObject {

  /** Game time one flight step advances, in milliseconds. */
  static final int STEP_MS = 50;

  /** How long a hopping projectile waits after a hop before it flies on, in milliseconds. */
  static final int HOP_DELAY_MS = 150;

  private static final int PERCENT = 100;

  /** The share of its damage a deflected projectile deals a unit, in percent. */
  private static final String DEFLECTED_UNITS = "DEFLECTED_PRJ_UNITS_DMG_MUL";

  /** The share of its crown-tower damage a deflected projectile deals a crown tower. */
  private static final String DEFLECTED_TOWERS = "DEFLECTED_PRJ_TOWERS_DMG_MUL";

  /** The same share for a row that takes a spell's. */
  private static final String DEFLECTED_SPELL_TOWERS = "DEFLECTED_SPELL_TOWERS_DMG_MUL";

  private final BattleWorld world;

  /** The projectile's published columns. */
  /** Its row's columns; a data-changing action may swap the row for one alike in the battle. */
  @Getter private ProjectileData data;

  /**
   * The side of the launcher, which the projectile fights for; a deflection turns it to the
   * deflector's.
   */
  @Getter private int side;

  /**
   * How many times a deflecting area effect has turned the projectile around; from the first, its
   * impact deals the deflected share of its damage.
   */
  @Getter private int deflections;

  /**
   * True for a projectile of a volley its launcher's row links into one group, which a deflection
   * treats as one.
   */
  @Getter private boolean grouped;

  /**
   * The entity that launched the projectile, or null once it has left the battle, or for one
   * another projectile's impact spawned.
   */
  @Getter private WorldEntity owner;

  /** The launcher, or the launcher's own root for a projectile fired by a projectile. */
  @Getter private WorldEntity root;

  /**
   * The area effect that launched the projectile, which is its owner and root as well, while it is
   * in the battle; null for one a unit, a cast or an impact launched. The owner and the root are
   * then null: they hold units only.
   */
  @Getter private AreaEffectEntity areaLauncher;

  /** What the projectile was fired at, or null once it has left or was never aimed at one. */
  @Getter private WorldEntity target;

  /**
   * The area effect the projectile was fired at, as a target indicator attack fires at its signal
   * and a barrage drops its bomb onto its area, or null once it has left or for one fired at none.
   * Its flight never takes it as a live target: the projectile flies to the point it was aimed at.
   */
  @Getter private AreaEffectEntity areaTarget;

  /** The target a limited-time homing projectile still follows, or null. */
  @Getter private WorldEntity homingTarget;

  /** Milliseconds of limited-time homing left. */
  @Getter private int homingTimeMs;

  @Getter private int x;
  @Getter private int y;

  /** Height above the ground, in game units. */
  @Getter private int z;

  /** Where the flight started. */
  @Getter private int startX;

  @Getter private int startY;
  @Getter private int startZ;

  /** Where the flight ends. */
  @Getter private int aimX;

  @Getter private int aimY;

  /** The height the flight ends at. */
  @Getter private int aimZ;

  /** The projectile's level, packed against its row's rarity; see {@link PackedLevel}. */
  @Getter private int packedLevel;

  /** The heading the projectile was launched at, in degrees. */
  @Getter private int facing;

  /** True once the projectile has arrived: it is removable and takes no further step. */
  @Getter private boolean released;

  /**
   * True once a hooking projectile has hooked its target: it flies back to its owner, or holds
   * still while it drags its owner to a building.
   */
  @Getter private boolean hooked;

  /** True until the first flight step has run. */
  private boolean firstVisit = true;

  /** Milliseconds its flight still waits before it moves, 50 off each visit. */
  @Getter private int delayMs;

  /** The speed its launch gave it in place of its row's, or 0 for none. */
  @Getter private int speedOverride;

  /** The ids of the entities its flying body has hit, which it does not hit again. */
  @Getter private final List<Integer> hitIds = new ArrayList<>();

  /** How many targets a hopping projectile has been launched at, its first included. */
  @Getter private int chainedHits;

  /** How many links of spawned projectiles its impact may still launch. */
  @Getter private int spawnChain;

  /** The chain it belongs to, or null for a projectile on its own. */
  @Getter private ProjectileChain chain;

  /** The point its damage lands on as part of a chain, when the chain lands on ring points. */
  @Getter private int ringX;

  @Getter private int ringY;

  /** How far into its sweep a pingpong projectile is, in milliseconds. */
  @Getter private int pingpongTimeMs;

  /**
   * How far a pingpong projectile stood from its start before its last sweep step, which an
   * expression on it reads.
   */
  @Getter private int pingpongDistance;

  /** How much of its sweep a pingpong projectile covers each step, fixed at its launch. */
  @Getter private int pingpongStepMs;

  /** The projectile's action holder, made the first time it causes an action; null until then. */
  private ActionHolder actionHolder;

  /**
   * True while the projectile's damage is registered on its target as pending: from its start, or a
   * hop's relaunch, until it hands the damage back.
   */
  @Getter private boolean pendingRegistered;

  /**
   * Creates an unlaunched projectile. The launch places it; see {@link ProjectileLauncher}.
   *
   * @param world the battle's shared arena state, where the impact's damage goes
   * @param data the projectile's published columns
   * @param side the side that fires it
   */
  public ProjectileEntity(BattleWorld world, ProjectileData data, int side) {
    super(KIND_PROJECTILE);
    checkArgument(data != null, "a projectile needs its columns");
    this.world = world;
    this.data = data;
    this.side = side;
  }

  /** The projectile's name in trajectories and logs: its kind's prefix and its id. */
  public String name() {
    return "proj_" + getId();
  }

  /** The projectile's level, counted from 1. */
  public int level() {
    return PackedLevel.level(packedLevel);
  }

  /**
   * Places the projectile at its start and aims it: the level is the launcher's re-based on the
   * row's rarity, the aim is the hit position, and a homing projectile with a range instead flies
   * that range from the launcher toward the hit position and forgets its target.
   *
   * @param launcher the entity firing the projectile
   * @param target what the projectile is fired at, or null
   * @param sx start position along the arena's width
   * @param sy start position along the arena's length
   * @param sz start height
   * @param hx the hit position along the arena's width
   * @param hy the hit position along the arena's length
   */
  void launch(WorldEntity launcher, WorldEntity target, int sx, int sy, int sz, int hx, int hy) {
    GridEntity view = launcher.getView();
    grouped = launcher.getData().groupProjectiles();
    place(
        launcher,
        launcher,
        target,
        launcher.getPackedLevel(),
        sx,
        sy,
        sz,
        hx,
        hy,
        view.getX(),
        view.getY());
    if (data.randomDelayMs() >= 1) {
      // A row with a random delay waits a draw from the battle's random source below it.
      delayMs = world.getRandom().next(data.randomDelayMs());
    }
    if (data.pingpongVisualTimeMs() >= 1) {
      // A pingpong projectile is held by its launcher's targeting component, whose visit returns
      // early until the projectile comes back; the resume delay is started at the sweep's time.
      TargetingState t = launcher.getTargeting();
      t.setVisitSuspended(true);
      t.setResumeDelayElapsedMs(0);
      t.setResumeDelayMs(data.pingpongVisualTimeMs());
      // Its sweep advances by the step the launcher's buffs make of 50 ms, as its attack does.
      pingpongStepMs = launcher.getBuffs().hitSpeed(STEP_MS);
    } else if (data.dragBackSpeed() >= 1) {
      // A hooking projectile is held by its launcher's targeting component too, with no resume
      // delay: the visit returns early until the projectile leaves the battle.
      launcher.hold(this);
    }
  }

  /**
   * Places a spell's projectile, cast by a card play: owned by its side's king tower, with no
   * target, at the card's level re-based on the row's rarity, from the start the cast works out to
   * the placed point.
   *
   * @param king the side's king tower, the owner and the root
   * @param cardLevel the card's level, packed, as the play gives it
   * @param sx start position along the arena's width
   * @param sy start position along the arena's length
   * @param sz start height
   * @param hx the placed point along the arena's width
   * @param hy the placed point along the arena's length
   * @param delayMs how long its flight waits before it moves
   */
  public void cast(
      WorldEntity king, int cardLevel, int sx, int sy, int sz, int hx, int hy, int delayMs) {
    refuseUnitOnly("cast");
    // The cast has no launcher: a projectile that aims by its range would aim from its start.
    place(king, king, null, cardLevel, sx, sy, sz, hx, hy, sx, sy);
    this.delayMs = delayMs;
  }

  /**
   * Places a projectile an area effect launches: with the area effect as its launcher, owner and
   * root, at the area effect's level re-based on the row's rarity, from the start to the hit
   * position, at a target or none.
   *
   * @param area the area effect
   * @param target what the projectile is dropped onto, or null
   * @param sx start position along the arena's width
   * @param sy start position along the arena's length
   * @param sz start height
   * @param hx the hit position along the arena's width
   * @param hy the hit position along the arena's length
   */
  public void launchFromArea(
      AreaEffectEntity area, WorldEntity target, int sx, int sy, int sz, int hx, int hy) {
    refuseUnitOnly("launched by an area effect");
    place(null, null, target, area.getPackedLevel(), sx, sy, sz, hx, hy, area.getX(), area.getY());
    areaLauncher = area;
  }

  /**
   * Places a projectile a target indicator attack fires at its signal: with the unit as launcher
   * and owner, at its level re-based on the row's rarity, from the start to the signal's point. The
   * signal is kept as what it was fired at, which its flight never takes as a live target. Refused:
   * a homing, pingpong or hooking row, or one with a random delay.
   *
   * @param launcher the unit running the attack
   * @param signal the signal it fires at
   * @param sx start position along the arena's width
   * @param sy start position along the arena's length
   * @param sz start height
   */
  public void launchAtSignal(
      WorldEntity launcher, AreaEffectEntity signal, int sx, int sy, int sz) {
    refuseUnitOnly("fired at a signal");
    if (data.homing() || data.homingTimeMs() >= 1 || data.dragBackSpeed() >= 1) {
      throw new UnsupportedOperationException(
          data.name() + " homes or hooks and is fired at a signal, not modelled");
    }
    GridEntity view = launcher.getView();
    place(
        launcher,
        launcher,
        null,
        launcher.getPackedLevel(),
        sx,
        sy,
        sz,
        signal.getX(),
        signal.getY(),
        view.getX(),
        view.getY());
    areaTarget = signal;
  }

  /**
   * Places a bomb a barrage drops onto its area effect, as {@link #launchFromArea} does, the area
   * effect kept as what it was dropped onto, and gives it the speed the drop works out in place of
   * its row's.
   *
   * @param area the area effect it is dropped onto, its launcher
   * @param sz the height it is dropped from
   * @param speed the speed it flies at
   */
  public void dropOnto(AreaEffectEntity area, int sz, int speed) {
    launchFromArea(area, null, area.getX(), area.getY(), sz, area.getX(), area.getY());
    areaTarget = area;
    speedOverride = speed;
  }

  /** The name of what the projectile was fired at while it is in the battle, or null for none. */
  public String targetName() {
    if (target != null) {
      return target.name();
    }
    return areaTarget == null ? null : areaTarget.name();
  }

  /**
   * The name of what launched the projectile while it is in the battle: its owner, or the area
   * effect that launched it; null for none.
   */
  public String launcherName() {
    if (owner != null) {
      return owner.name();
    }
    return areaLauncher == null ? null : areaLauncher.name();
  }

  /**
   * Makes the projectile part of a chain whose damage lands on ring points: its own is the given
   * point, and the chain lands on ring points from now on.
   *
   * @param chain the wave's shared state
   * @param x the ring point along the width
   * @param y the ring point along the length
   */
  public void joinChain(ProjectileChain chain, int x, int y) {
    this.chain = chain;
    this.ringX = x;
    this.ringY = y;
    chain.markRingPoints();
  }

  /**
   * Refuses a pingpong projectile or one with a random delay that no unit launches: a pingpong one
   * would be held by a king tower's targeting component, or, spawned, by none, and a cast one would
   * draw its delay; no row either way is known.
   */
  private void refuseUnitOnly(String how) {
    if (data.pingpongVisualTimeMs() >= 1) {
      throw new UnsupportedOperationException(
          data.name() + " is a pingpong projectile " + how + " without a unit, not modelled");
    }
    if (data.randomDelayMs() >= 1) {
      throw new UnsupportedOperationException(
          data.name() + " has a random delay and is " + how + " without a unit, not modelled");
    }
  }

  /** One visit's step of the delay before the flight, which stops at zero. */
  void stepDelay() {
    delayMs = Math.max(delayMs, STEP_MS) - STEP_MS;
  }

  /**
   * Launches a projectile another one's impact spawns: from where the parent stands, at the height
   * the parent aimed at, with no target and no launcher - so a projectile that aims by its range
   * aims from its start - at the parent's level re-based on this row's rarity, with the parent's
   * root, and one link fewer of its spawn chain.
   *
   * @param parent the projectile that landed
   * @param hx the point it aims beyond the parent's aim, along the width
   * @param hy the point it aims beyond the parent's aim, along the length
   */
  public void launchSpawned(ProjectileEntity parent, int hx, int hy) {
    refuseUnitOnly("spawned");
    place(
        null,
        parent.root,
        null,
        parent.packedLevel,
        parent.x,
        parent.y,
        parent.aimZ,
        hx,
        hy,
        parent.x,
        parent.y);
    spawnChain = parent.spawnChain - 1;
  }

  /**
   * The launch body: the level re-based, the start, the owner and root, the aim from its origin,
   * its height, and the facing.
   */
  private void place(
      WorldEntity launcher,
      WorldEntity rootOwner,
      WorldEntity target,
      int launcherLevel,
      int sx,
      int sy,
      int sz,
      int hx,
      int hy,
      int originX,
      int originY) {
    this.packedLevel = PackedLevel.pack(launcherLevel, data.rarity());
    this.x = sx;
    this.y = sy;
    this.z = sz;
    this.target = target;
    this.owner = launcher;
    this.root = rootOwner;
    this.spawnChain = data.spawnChain();
    aim(originX, originY, hx, hy);
    this.startX = x;
    this.startY = y;
    this.startZ = z;
    if (target != null && data.homingTimeMs() >= 1) {
      GridEntity targetView = target.getView();
      int dx = targetView.getX() - startX;
      int dy = targetView.getY() - startY;
      if (FixedMath.guardedDistance(dx, dy) > data.homingMinDistance()) {
        homingTarget = target;
        homingTimeMs = data.homingTimeMs();
      }
    }
    if (data.minDistance() != 0) {
      int[] vec = {aimX - startX, aimY - startY};
      if (FixedMath.guardedDistance(vec[0], vec[1]) < data.minDistance()) {
        FixedMath.normalize(vec, data.minDistance());
        aimX = startX + vec[0];
        aimY = startY + vec[1];
      }
    }
    if (data.homingLike()) {
      aimZ = startZ;
    } else if (data.spawnProjectile() != null) {
      // A projectile that spawns one flying to a point aims at that one's body height.
      ProjectileData spawned = world.getRecords().projectile(data.spawnProjectile());
      if (spawned.homingLike()) {
        aimZ =
            spawned.projectileRadiusY() == 0
                ? spawned.projectileRadius()
                : Math.min(spawned.projectileRadius(), spawned.projectileRadiusY());
      }
    }
    if (data.constantHeight() != 0) {
      // A constant height replaces the launcher's height as the start and the aim: the flight's arc
      // runs from it to the aim height, which a homing projectile's re-aim still moves onto its
      // target's. No hit test reads a height, so it moves no hit and no arrival.
      z = data.constantHeight();
      startZ = z;
      aimZ = z;
    }
    if (data.chainedHitRadius() >= 1) {
      // A hopping projectile counts every launch and lists each target, which it does not hop to
      // again.
      chainedHits++;
      if (this.target != null) {
        hitIds.add(this.target.getId());
      }
    }
    boolean homingNow = this.target != null && data.homing();
    int dx = (homingNow ? this.target.getView().getX() : aimX) - x;
    int dy = (homingNow ? this.target.getView().getY() : aimY) - y;
    facing = FixedMath.guardedDistance(dx, dy) >= 1 ? FixedMath.angleOfVector(dx, dy) : 0;
    if (homingTimeMs >= 1) {
      // A limited-time homing projectile measures its flight from the launcher, not the start.
      startX = originX;
      startY = originY;
    }
  }

  /**
   * Points the flight: at the hit position, or, for a projectile that flies to a point, its range
   * from the origin toward the hit position, forgetting the target.
   */
  void aim(int originX, int originY, int hx, int hy) {
    aimX = hx;
    aimY = hy;
    if (!data.homingLike() && data.projectileRange() < 1) {
      return;
    }
    int[] vec = {hx - originX, hy - originY};
    // A row with a random distance would add a draw here; no row carried here has one.
    FixedMath.normalize(vec, data.projectileRange());
    target = null;
    aimX = originX + vec[0];
    aimY = originY + vec[1];
  }

  /**
   * The chained hop, after an impact: the projectile is launched again from where it is, at the
   * height it aimed at, at its next target, aimed at its old aim mirrored past its start - a homing
   * projectile then re-pins that aim onto the target - with its owner, root and level. The launch
   * counts it and lists the target. It is no longer released, and waits {@value #HOP_DELAY_MS} ms
   * before it flies on.
   *
   * @param next the character it hops to
   */
  void hop(WorldEntity next) {
    int hx = 2 * aimX - startX;
    int hy = 2 * aimY - startY;
    target = null;
    place(owner, root, next, packedLevel, x, y, aimZ, hx, hy, x, y);
    // The relaunch registers its damage on the next target, from where the hop starts.
    registerPending();
    released = false;
    delayMs = HOP_DELAY_MS;
  }

  /**
   * The redirect of a deflection back at the projectile's source: a homing projectile with a target
   * hands the damage registered on it back; the target and the root are forgotten, the deflections
   * counted, and the projectile takes the deflector's side. It is launched again from where it
   * stands, at its height, at the source and its position, the deflector its launcher and owner, at
   * its own level, and registers its damage, now the deflected share, on the source.
   *
   * @param deflector the deflecting area effect's parent, which sends it back
   * @param source the projectile's root owner, which it is sent back at
   */
  public void deflect(WorldEntity deflector, WorldEntity source) {
    if (data.customDeflectAction() != null) {
      throw new UnsupportedOperationException(
          name() + " is deflected, which runs " + data.customDeflectAction() + ", not modelled");
    }
    if (data.homing() && target != null) {
      handBackPending();
    }
    target = null;
    root = null;
    deflections++;
    side = deflector.side();
    GridEntity at = source.getView();
    GridEntity from = deflector.getView();
    place(
        deflector,
        deflector,
        source,
        packedLevel,
        x,
        y,
        z,
        at.getX(),
        at.getY(),
        from.getX(),
        from.getY());
    registerPending();
  }

  /** Moves the projectile. */
  void moveTo(int newX, int newY, int newZ) {
    x = newX;
    y = newY;
    z = newZ;
  }

  /** Marks the projectile as having hooked its target. */
  void hook() {
    hooked = true;
  }

  void setAim(int newAimX, int newAimY, int newAimZ) {
    aimX = newAimX;
    aimY = newAimY;
    aimZ = newAimZ;
  }

  void setStart(int newStartX, int newStartY) {
    startX = newStartX;
    startY = newStartY;
  }

  void setHomingTimeMs(int ms) {
    homingTimeMs = ms;
  }

  void setPingpongTimeMs(int ms) {
    pingpongTimeMs = ms;
  }

  void setPingpongDistance(int distance) {
    pingpongDistance = distance;
  }

  /** The battle the projectile belongs to. */
  BattleWorld world() {
    return world;
  }

  /**
   * Loses the target, handing no damage back, as a projectile whose row allows it does when its
   * target goes into a pathfinding state: it flies on to its aim and lands on nothing.
   */
  public void dropTarget() {
    target = null;
  }

  void forgetHomingTarget() {
    homingTarget = null;
    homingTimeMs = 0;
  }

  /**
   * Ends the flight: the projectile takes no further step and leaves at the next cleanup. A damage
   * still registered on its target is handed back.
   */
  void release() {
    released = true;
    releasePending();
  }

  /**
   * Ends the flight of a projectile that stops at collisions, on the first hit its body lands: it
   * takes no further hit or step and leaves at the next cleanup, without an impact, and hands back
   * a damage still registered on its target.
   */
  public void finishOnCollision() {
    released = true;
    releasePending();
  }

  /**
   * The projectile's start, as the holder admits it to its live list at the closing cleanup of its
   * launch tick: first its row's starting action is scheduled on its own holder, itself the cause,
   * so outside every pending pass it waits for the projectile's phase-1 pending pass of the next
   * tick, before its first flight step; then a homing projectile registers its damage on its
   * target, from where it stands.
   */
  @Override
  protected void onRegistered() {
    if (data.onStartingAction() != null) {
      BattleAction starting =
          world.getActions().build(data.onStartingAction(), new ProjectileBinding(world, this));
      world.projectileStarting(this, starting.name());
      actionHolder().schedule(starting, ActionHolder.OWN_DELAY, false, actionHolder());
    }
    registerPending();
  }

  /**
   * Takes another projectile row, as a data-changing action gives it: only the row changes, so a
   * row whose battle columns differ from this one's, but for its starting action, which does not
   * run again, is refused.
   *
   * @param rowName the row it takes
   */
  @Override
  public void changeProjectileData(String rowName) {
    ProjectileData next = world.getRecords().projectile(rowName);
    if (!battleColumns(next).equals(battleColumns(data))) {
      throw new UnsupportedOperationException(
          name() + " swaps " + data.name() + " for " + rowName + ", whose columns differ");
    }
    world.projectileSwapped(this, data.name(), rowName);
    data = next;
  }

  /** A row's battle columns: everything but its name and its starting action. */
  private static ProjectileData battleColumns(ProjectileData row) {
    return row.toBuilder().name("").onStartingAction(null).build();
  }

  /** Starts the evolved Executioner's axe controller on the projectile. */
  @Override
  public ActionInstance executionerController(ExecutionerEvoProjectile action, int phase) {
    world.executionerStarted(this, action.name(), phase);
    return new ExecutionerRun(action, this);
  }

  /** Starts a roll on the projectile, which moves it in place of its flight. */
  @Override
  public ActionInstance rollingProjectile(RollingProjectile action, int phase) {
    RollingRun run = new RollingRun(action, this);
    world.rollStarted(this, action.name(), phase, run.destinationX(), run.destinationY());
    return run;
  }

  /** Starts a capture on the projectile. */
  @Override
  public ActionInstance captureCharacter(CaptureCharacter action, int phase) {
    world.captureStarted(this, action.name(), phase);
    return new CaptureRun(action, this);
  }

  /**
   * Tells the projectile's listening runs, from the last listed down, of a hit it lands, before the
   * hit's damage is taken off.
   *
   * @param hitId the hit's id
   * @param target what the hit lands on
   */
  public void hitHeard(int hitId, WorldEntity target) {
    if (actionHolder == null) {
      return;
    }
    List<ActionInstance> runs = actionHolder.running();
    for (int i = runs.size() - 1; i >= 0; i--) {
      if (runs.get(i) instanceof ExecutionerRun controller) {
        controller.hit(hitId, target);
      }
    }
  }

  /**
   * Registers the projectile's damage on its target as pending, when the projectile is homing and
   * still has a target: its damage, and its flight time, the distance from where it stands to the
   * target over its speed per 50 ms.
   */
  private void registerPending() {
    if (!data.homing() || target == null) {
      return;
    }
    GridEntity at = target.getView();
    int distance = FixedMath.guardedDistance(x - at.getX(), y - at.getY());
    int flightMs = FixedMath.divOrZero(distance * STEP_MS, data.speed());
    target.addPendingDamage(pendingAmount(), flightMs);
    pendingRegistered = true;
  }

  /**
   * Hands the damage registered on the target back, as a homing projectile does as it arrives,
   * before its impact: its damage off the target, recomputed, and the pending duration left alone.
   * A projectile whose target has left hands nothing back.
   */
  void handBackPending() {
    target.addPendingDamage(-pendingAmount(), -1);
    pendingRegistered = false;
  }

  /** The release's hand-back: only while the damage is registered and the target is still there. */
  private void releasePending() {
    if (pendingRegistered && target != null) {
      handBackPending();
    }
  }

  /**
   * What the projectile will deal its target: its damage at its level, or its crown-tower damage
   * for a crown tower. The enchanting copies it carries are asked with no hit, so they change
   * nothing.
   */
  private int pendingAmount() {
    return target.getTargetView().isCrownTowerTarget() ? towerDamage() : damage();
  }

  /** True on the first flight step only. */
  boolean takeFirstVisit() {
    boolean first = firstVisit;
    firstVisit = false;
    return first;
  }

  /** The battle's scaling values, for the damage the impact computes. */
  ScalingGlobals scalingGlobals() {
    return ScalingGlobals.standard();
  }

  /**
   * The projectile's damage at its level, as the impact computes it; once deflected, the share
   * DEFLECTED_PRJ_UNITS_DMG_MUL gives of it.
   */
  public int damage() {
    int damage = ProjectileAmounts.damage(scalingGlobals(), data, packedLevel);
    if (deflections >= 1) {
      damage = world.globalNumber(DEFLECTED_UNITS) * damage / PERCENT;
    }
    return damage;
  }

  /**
   * What a crown tower takes from the projectile, as the impact computes it; once deflected, the
   * share DEFLECTED_PRJ_TOWERS_DMG_MUL gives of it, or DEFLECTED_SPELL_TOWERS_DMG_MUL for a row
   * that takes a spell's.
   */
  public int towerDamage() {
    int damage = ProjectileAmounts.towerDamage(scalingGlobals(), data, packedLevel);
    if (deflections >= 1) {
      boolean spell = (data.deflectBehaviour() & ProjectileData.USE_SPELLS_TOWER_DAMAGE_MUL) != 0;
      damage =
          world.globalNumber(spell ? DEFLECTED_SPELL_TOWERS : DEFLECTED_TOWERS) * damage / PERCENT;
    }
    return damage;
  }

  /** The projectile's damage at its level, before any deflection's share. */
  public int undeflectedDamage() {
    return ProjectileAmounts.damage(scalingGlobals(), data, packedLevel);
  }

  /**
   * The projectile as the owner of the area its impact damages: an entity of the projectile's kind
   * on its side, which is all the validator asks of an owner that is not a character.
   */
  /**
   * The projectile as it asks a target whether it may hit it: an entity of the projectile's type
   * and side.
   */
  public GridEntity askerView() {
    return areaOwner().getOwner();
  }

  public TargetingState areaOwner() {
    GridEntity view = new GridEntity();
    view.setName(name());
    view.setType(KIND_PROJECTILE);
    view.setSide(side);
    TargetingState owner = new TargetingState();
    owner.setOwner(view);
    return owner;
  }

  /**
   * The projectile's target as the impact and the flight read it, or null when it has no target
   * left.
   */
  TargetView targetView() {
    return target == null ? null : target.getTargetView();
  }

  @Override
  public int x() {
    return x;
  }

  @Override
  public int y() {
    return y;
  }

  @Override
  public int side() {
    return side;
  }

  @Override
  public int kind() {
    return getKind();
  }

  @Override
  public boolean isCharacter() {
    return false;
  }

  @Override
  public int prestige() {
    return 0;
  }

  @Override
  public int packedLevel() {
    return packedLevel;
  }

  /** A projectile's children come from its impact, not from a spawn row. */
  @Override
  public List<SpawnHost> spawnCharacters(SpawnArguments arguments) {
    throw new UnsupportedOperationException(name() + " runs no spawn row");
  }

  /** The flight: one step toward the aim, or the arrival and the impact. */
  @Override
  protected void postHook() {
    ProjectileFlight.fly(this, world);
  }

  /**
   * The projectile's notice of a removal: a homing projectile whose target left pins its aim where
   * the target last stood; the target, the homing target, the owner, the root and the area effect
   * that launched it are forgotten when they are the one that left.
   */
  @Override
  protected void entityRemoved(BattleEntity removed) {
    if (removed == areaLauncher) {
      areaLauncher = null;
    }
    if (removed == areaTarget) {
      areaTarget = null;
    }
    if (!(removed instanceof WorldEntity gone)) {
      return;
    }
    if (target == gone) {
      if (data.homing()) {
        GridEntity view = gone.getView();
        // The projectile's own collision radius, half of which would raise the aim height, is
        // zero for every row carried here.
        setAim(view.getX(), view.getY(), view.getZ());
      }
      target = null;
    }
    if (homingTarget == gone) {
      forgetHomingTarget();
    }
    if (root == gone) {
      root = null;
    }
    if (owner == gone) {
      owner = null;
    }
  }

  @Override
  public boolean isRemovable() {
    return released;
  }

  /**
   * The projectile's action holder, made on first use: what an action it causes names as its cause.
   */
  public ActionHolder actionHolder() {
    if (actionHolder == null) {
      actionHolder = new ActionHolder(this, world.getHolder()::isInPendingPass);
    }
    return actionHolder;
  }

  /**
   * The damage of a hit of the projectile as its listening runs change it, from the last listed
   * down: an enchanting copy it carries, or the evolved Executioner's axe controller; unchanged
   * without one.
   *
   * @param damage the damage so far
   * @param hitId the hit's id
   * @param crownTower true for the crown-tower damage
   * @param target what the hit lands on, or null
   */
  public int listenedDamage(int damage, int hitId, boolean crownTower, WorldEntity target) {
    if (actionHolder == null) {
      return damage;
    }
    List<ActionInstance> runs = actionHolder.running();
    int out = damage;
    for (int i = runs.size() - 1; i >= 0; i--) {
      if (runs.get(i) instanceof GiantBufferBuff.Run copy) {
        out = copy.damage(out, hitId, crownTower);
      } else if (runs.get(i) instanceof ExecutionerRun controller) {
        out = controller.damage(out, hitId, target);
      }
    }
    return out;
  }

  /** Whether the projectile has an action holder, whose runs listen to its hits. */
  public boolean hasActionHolder() {
    return actionHolder != null;
  }

  /** Whether the projectile carries an enchanting copy that changes its damage. */
  public boolean carriesListeners() {
    if (actionHolder == null) {
      return false;
    }
    for (ActionInstance run : actionHolder.running()) {
      if (run instanceof GiantBufferBuff.Run) {
        return true;
      }
    }
    return false;
  }

  @Override
  public EntityActions actions() {
    return actionHolder == null ? EntityActions.NONE : actionHolder;
  }

  /** A projectile has no hit points, so it counts as alive. */
  @Override
  public HitPoints actionHitPoints() {
    return null;
  }

  @Override
  public int actionPackedLevel() {
    return packedLevel;
  }

  @Override
  public int actionId() {
    return getId();
  }

  /** A projectile was launched by another object. */
  @Override
  public boolean actionCreated() {
    return true;
  }

  /**
   * Its launcher, a unit or an area effect, while it is still in the battle; the removal notice
   * forgets it.
   */
  @Override
  public ActionOwner actionCreator() {
    return owner != null ? owner : areaLauncher;
  }

  @Override
  public RarityTable actionRarity() {
    return data.rarity();
  }

  @Override
  public String actionRowName() {
    return data.name();
  }

  @Override
  public int variable(int key) {
    throw new UnsupportedOperationException("a projectile's variables are not modelled");
  }

  @Override
  public void setVariable(int key, int value) {
    throw new UnsupportedOperationException("a projectile's variables are not modelled");
  }

  @Override
  public void killBy(ActionOwner killer) {
    throw new UnsupportedOperationException("killing a projectile is not modelled");
  }

  @Override
  public void queueTypedHit(ActionOwner source, int amount, DamageType type) {
    throw new UnsupportedOperationException("a typed hit on a projectile is not modelled");
  }
}
