package org.crforge.core.battle.projectile;

import static org.crforge.core.util.ValidationUtils.checkArgument;

import lombok.Getter;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.unit.BattleWorld;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.combat.PackedLevel;
import org.crforge.core.pathfinding.combat.ScalingGlobals;
import org.crforge.core.pathfinding.math.FixedMath;
import org.crforge.core.pathfinding.target.TargetView;

/**
 * A projectile in flight: an entity of its own kind in the same holder as the characters, so that
 * it is visited in the same passes and removed by the same cleanup.
 *
 * <p>A projectile has no components. Its flight is its post-hook, which the holder runs for every
 * projectile before any character's, because a projectile's id band precedes the characters'. Its
 * id is given the moment its launcher hands it to the holder, in the attack tick, and it enters the
 * live list at that tick's closing cleanup, so it first flies on the tick after its launch and
 * arrives when the distance left to its aim is no more than one step of its speed. On arrival it is
 * released, which is what makes it removable, and its impact deals its damage to its target.
 *
 * <p>The launch fixes the start and the aim; a homing projectile re-pins its aim onto its target
 * every step, and, when the target leaves the battle, the removal notice leaves the aim where the
 * target last stood and forgets the target, so the projectile flies on and lands on nothing.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the projectile's kind and id band, its flight as the post-hook from the tick"
            + " after its launch, the launch geometry from the owner's launch columns, the level"
            + " re-based on the projectile row's rarity, the homing re-aim, the straight flight"
            + " with the arc height, the arrival on the step that reaches the aim, the release"
            + " that makes it removable, the single impact on a target that still has hit points"
            + " with the crown-tower damage for a crown tower, and the removal notice for a target,"
            + " a homing target, an owner and a root owner that left. Held by the Musketeer run's"
            + " launches, positions and impacts. Supplied, not settled: the deflection pass finds"
            + " nothing, the projectile's own collision radius is zero, and no buff changes its"
            + " damage. Not modelled: the area impact of a projectile with a radius, the hits on"
            + " what a flying body passes, the pushback on impact, the on-impact spawns and the"
            + " chained hop, the limited-time homing beyond the columns carried, the pingpong sweep,"
            + " the ring scatter, the drag-back hook, the delays before the flight, the custom"
            + " movement, and the far-distance clamp with its cell pull.")
public class ProjectileEntity extends BattleEntity {

  /** Game time one flight step advances, in milliseconds. */
  static final int STEP_MS = 50;

  private final BattleWorld world;

  /** The projectile's published columns. */
  @Getter private final ProjectileData data;

  /** The side of the launcher, which the projectile fights for. */
  @Getter private final int side;

  /** The entity that launched the projectile, or null once it has left the battle. */
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
    this.packedLevel = PackedLevel.pack(launcher.getPackedLevel(), data.rarity());
    this.x = sx;
    this.y = sy;
    this.z = sz;
    this.target = target;
    this.owner = launcher;
    // A projectile fired by a character has that character as its root; one fired by another
    // projectile would take the launcher's root, which nothing here does yet.
    this.root = launcher;
    GridEntity launcherView = launcher.getView();
    aim(launcherView.getX(), launcherView.getY(), hx, hy);
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
    }
    boolean homingNow = this.target != null && data.homing();
    int dx = (homingNow ? this.target.getView().getX() : aimX) - x;
    int dy = (homingNow ? this.target.getView().getY() : aimY) - y;
    facing = FixedMath.guardedDistance(dx, dy) >= 1 ? FixedMath.angleOfVector(dx, dy) : 0;
    if (homingTimeMs >= 1) {
      // A limited-time homing projectile measures its flight from the launcher, not the start.
      startX = launcherView.getX();
      startY = launcherView.getY();
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
   * The projectile's target as the impact and the flight read it, or null when it has no target
   * left.
   */
  TargetView targetView() {
    return target == null ? null : target.getTargetView();
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
}
