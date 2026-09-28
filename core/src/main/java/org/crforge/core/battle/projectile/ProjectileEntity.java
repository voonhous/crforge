package org.crforge.core.battle.projectile;

import static org.crforge.core.util.ValidationUtils.checkArgument;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.EntityActions;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionOwner;
import org.crforge.core.battle.action.DamageType;
import org.crforge.core.battle.spawn.SpawnArguments;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.battle.unit.BattleWorld;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.crforge.core.pathfinding.combat.PackedLevel;
import org.crforge.core.pathfinding.combat.ScalingGlobals;
import org.crforge.core.pathfinding.math.FixedMath;
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
 * projectile flies on and lands on nothing.
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
            + " Supplied, not settled: the deflection pass finds nothing, the projectile's"
            + " own collision radius is zero, and no buff changes its damage. A spell's cast from"
            + " the king tower, its delay, its chain and ring point, a thrown projectile's aim at"
            + " its spawned one's body height, and a spawned projectile's launch from its parent"
            + " are held by the spell runs; the limited-time homing by the Elite Archer's."
            + " Not modelled: the chained hop, the pingpong sweep, the random delays, the drag-back"
            + " hook, the custom movement, and the far-distance clamp with its cell pull.")
public class ProjectileEntity extends BattleEntity implements ActionOwner, SpawnHost {

  /** Game time one flight step advances, in milliseconds. */
  static final int STEP_MS = 50;

  private final BattleWorld world;

  /** The projectile's published columns. */
  @Getter private final ProjectileData data;

  /** The side of the launcher, which the projectile fights for. */
  @Getter private final int side;

  /**
   * The entity that launched the projectile, or null once it has left the battle, or for one
   * another projectile's impact spawned.
   */
  @Getter private WorldEntity owner;

  /** The launcher, or the launcher's own root for a projectile fired by a projectile. */
  @Getter private WorldEntity root;

  /** What the projectile was fired at, or null once it has left or was never aimed at one. */
  @Getter private WorldEntity target;

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

  /** True until the first flight step has run. */
  private boolean firstVisit = true;

  /** Milliseconds its flight still waits before it moves, 50 off each visit. */
  @Getter private int delayMs;

  /** The ids of the entities its flying body has hit, which it does not hit again. */
  @Getter private final List<Integer> hitIds = new ArrayList<>();

  /** How many links of spawned projectiles its impact may still launch. */
  @Getter private int spawnChain;

  /** The chain it belongs to, or null for a projectile on its own. */
  @Getter private ProjectileChain chain;

  /** The point its damage lands on as part of a chain, when the chain lands on ring points. */
  @Getter private int ringX;

  @Getter private int ringY;

  /** The projectile's action holder, made the first time it causes an action; null until then. */
  private ActionHolder actionHolder;

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
    // The cast has no launcher: a projectile that aims by its range would aim from its start.
    place(king, king, null, cardLevel, sx, sy, sz, hx, hy, sx, sy);
    this.delayMs = delayMs;
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

  /** One visit's step of the delay before the flight. */
  void stepDelay() {
    delayMs -= STEP_MS;
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

  /** Moves the projectile. */
  void moveTo(int newX, int newY, int newZ) {
    x = newX;
    y = newY;
    z = newZ;
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

  void forgetHomingTarget() {
    homingTarget = null;
    homingTimeMs = 0;
  }

  /** Ends the flight: the projectile takes no further step and leaves at the next cleanup. */
  void release() {
    released = true;
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

  /** The projectile's damage at its level, as the impact computes it. */
  public int damage() {
    return ProjectileAmounts.damage(scalingGlobals(), data, packedLevel);
  }

  /** What a crown tower takes from the projectile, as the impact computes it. */
  public int towerDamage() {
    return ProjectileAmounts.towerDamage(scalingGlobals(), data, packedLevel);
  }

  /**
   * The projectile as the owner of the area its impact damages: an entity of the projectile's kind
   * on its side, which is all the validator asks of an owner that is not a character.
   */
  TargetingState areaOwner() {
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
   * the target last stood; the target, the homing target, the owner and the root are forgotten when
   * they are the one that left.
   */
  @Override
  protected void entityRemoved(BattleEntity removed) {
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
