package org.crforge.core.battle.unit;

import static org.crforge.core.util.ValidationUtils.checkArgument;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.projectile.ProjectileLauncher;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.combat.AreaDamage;
import org.crforge.core.pathfinding.combat.DamageApplication;
import org.crforge.core.pathfinding.combat.DamageQueries;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.crforge.core.pathfinding.combat.LevelScaling;
import org.crforge.core.pathfinding.combat.PackedLevel;
import org.crforge.core.pathfinding.combat.ScalingGlobals;
import org.crforge.core.pathfinding.target.HitApplication;
import org.crforge.core.pathfinding.target.HitQueries;
import org.crforge.core.pathfinding.target.RemovalNotice;
import org.crforge.core.pathfinding.target.SelectionChain;
import org.crforge.core.pathfinding.target.TargetView;
import org.crforge.core.pathfinding.target.TargetingConfig;
import org.crforge.core.pathfinding.target.TargetingState;
import org.crforge.core.pathfinding.target.ValidatorQueries;

/**
 * An entity that stands on the arena: it has a position, a collision circle and a side, the spatial
 * index lists it, the building overlay may stamp it, and other entities may target it. Every troop
 * and every building is one, so they are all of the character kind and share one band of ids.
 *
 * <p>The entity's position and state live in its {@link GridEntity}; there is no second copy to
 * keep in step. Its {@link TargetView} is the identity other entities hold a reference by, so it is
 * created once and kept for the entity's whole life.
 *
 * <p>An entity is created at a level. Its hit points and its damage are the published columns
 * scaled to that level once, at creation, and the level itself is kept packed against the entity's
 * rarity, as the scaling reads it. An entity whose hit points at its level are not positive carries
 * no hit-points object and counts as alive.
 *
 * <p>Every one of them, a tower as much as a troop, carries a targeting component: the working
 * state of its target choice and its attack, and the selection chain that answers which target it
 * should have. Whether the component runs, and what drives it, is the subclass's to decide; what
 * the component needs from the battle is kept here once. Every arena entity of a tick is made known
 * to it before any visit, the opposing side's towers become its default targets, a hit it lands
 * goes through the hit application, and an entity that leaves the battle is dropped from it at
 * once.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the level packed against the rarity at creation, the hit points and the damage"
            + " at that level, the alive answer, the removal test, and that a removable entity"
            + " leaves the holder at the next cleanup, which tells every other entity at once; a"
            + " targeting component on every entity, seeded with the opposing side's towers, whose"
            + " hits go through the hit application, whose area, for a row with an area radius,"
            + " takes the entity as its owner, and whose reference to an entity that left is"
            + " dropped by the removal notice. Not modelled yet: the shield's hit points at the"
            + " level, and what a death does beyond the entity becoming removable.")
public abstract class WorldEntity extends BattleEntity {

  /** Side of the player at the low end of the arena. */
  public static final int SIDE_BOTTOM = 0;

  /** Side of the player at the high end of the arena. */
  public static final int SIDE_TOP = 1;

  /** The battle's shared arena state. */
  protected final BattleWorld world;

  @Getter private final UnitData data;

  /** The entity as the routing grid, the spatial index and the overlay see it. */
  @Getter private final GridEntity view;

  /** The entity as a target: what every other entity's selection and validation look at. */
  @Getter private final TargetView targetView;

  /** The entity's level, packed against its rarity; see {@link PackedLevel}. */
  @Getter private final int packedLevel;

  /** The entity's hit points, or null when its hit points at its level are not positive. */
  @Getter private final HitPoints hitPoints;

  /** Damage of one hit at the entity's level. */
  @Getter private final int damage;

  /** The working state of the entity's targeting component: its reference and attack timing. */
  @Getter private final TargetingState targeting;

  /** Answers which target the targeting component should have now. */
  @Getter private final SelectionChain selection;

  /** True once the opposing side's towers have been registered as default targets. */
  private boolean towersRegistered;

  /**
   * @param world the battle's shared arena state
   * @param data the entity's published columns
   * @param view the entity as the grid sees it
   * @param targetingConfig the entity's targeting columns
   * @param level the entity's level, counted from 1
   */
  protected WorldEntity(
      BattleWorld world,
      UnitData data,
      GridEntity view,
      TargetingConfig targetingConfig,
      int level) {
    super(KIND_CHARACTER);
    checkArgument(data.rarity() != null, () -> data.name() + " has no rarity to scale by");
    this.world = world;
    this.data = data;
    this.view = view;
    this.targetView = new TargetView(view, targetingConfig);
    this.targeting = new TargetingState();
    targeting.setOwner(view);
    targeting.setConfig(targetingConfig);
    this.selection = new SelectionChain(world.getIndex(), targeting, world.getTileMap().height());
    selection.setHitSink(
        (target, sequenceIndex, extraTargets, last) ->
            HitApplication.apply(targeting, target, sequenceIndex, hitQueries()));
    this.packedLevel = PackedLevel.fromLevel(level, data.rarity());
    ScalingGlobals globals = ScalingGlobals.standard();
    int maximum =
        LevelScaling.hitpoints(
            globals,
            data.hitpoints(),
            packedLevel,
            data.rarity(),
            data.king(),
            data.summonerTower());
    this.hitPoints = maximum > 0 ? new HitPoints(maximum) : null;
    this.damage =
        LevelScaling.damage(
            globals,
            data.damage(),
            packedLevel,
            data.rarity(),
            data.king(),
            data.summonerTower(),
            null);
    // A candidate advertises its current hit points to an attacker that prefers the weakest.
    targetView.setHitPointsPresent(hitPoints != null);
    targetView.setCrownTowerTarget(data.king() || data.summonerTower());
    refreshHitPoints();
  }

  /**
   * Takes one damage event.
   *
   * <p>An entity without hit points takes nothing. Everything the rest of the battle reads about
   * this entity's hit points - whether it is alive, and what an attacker that prefers the weakest
   * candidate sees - is brought back into step here, so no pass can read a stale answer.
   *
   * @param damage hit points the source is dealing, before the two sides' buffs
   * @param dedupeId id of a source that must land on this entity only once; 0 for a direct hit
   * @param directionX direction of the hit along the arena's width
   * @param directionY direction of the hit along the arena's length
   */
  public DamageResult takeDamage(int damage, int dedupeId, int directionX, int directionY) {
    if (hitPoints == null) {
      return DamageResult.NOTHING;
    }
    DamageResult result =
        DamageApplication.damage(
            hitPoints, damage, dedupeId, directionX, directionY, damageQueries());
    refreshHitPoints();
    if (result.died()) {
      died();
    }
    return result;
  }

  /**
   * Runs when a hit takes the entity's hit points to zero, in the pass that lands it. The entity is
   * still visited by the rest of the tick and leaves the holder only in its closing cleanup; its
   * own death handler switches off what it no longer does.
   */
  protected void died() {}

  /**
   * Makes every arena entity of this tick known to the entity's selection. The first call also
   * registers the opposing side's towers as the default targets, in creation order - king first,
   * then the princess towers along the arena's width - and seeds the selection with the king.
   */
  void registerCandidates(List<WorldEntity> present) {
    if (!towersRegistered) {
      towersRegistered = true;
      int enemy = opposing(side());
      for (WorldEntity entity : present) {
        if (entity instanceof TowerEntity tower && tower.side() == enemy) {
          selection.registerTower(tower.getTargetView());
          if (tower.getData().king() && selection.getSeed() == null) {
            selection.setSeed(tower.getTargetView());
          }
        }
      }
    }
    for (WorldEntity entity : present) {
      if (selection.view(entity.getView()) == null) {
        selection.register(entity.getTargetView());
      }
    }
  }

  /** Drops an entity that has left the battle from the entity's default targets. */
  void forget(GridEntity departed) {
    selection.unregister(departed);
  }

  /** The targeting component's notice: a reference to the entity that left is dropped at once. */
  @Override
  protected void entityRemoved(BattleEntity removed) {
    if (removed instanceof WorldEntity gone) {
      RemovalNotice.entityRemoved(targeting, gone.getTargetView(), null);
    }
  }

  /**
   * What one of the entity's hits needs from the battle: the damage of a hit at the entity's level,
   * the battle's hit ids, the target the damage is dealt to, and the launch of the projectiles of
   * an entity that fires.
   */
  private HitQueries hitQueries() {
    return new HitQueries() {
      @Override
      public int damage() {
        return getDamage();
      }

      @Override
      public int nextHitId() {
        return world.nextHitId();
      }

      @Override
      public void dealDamage(
          TargetView target, int damage, int hitId, int directionX, int directionY) {
        world.dealDamage(target, damage, directionX, directionY);
      }

      @Override
      public void launchProjectiles(TargetingState t, TargetView target, int sequenceIndex) {
        ProjectileLauncher.launch(WorldEntity.this, t, target, sequenceIndex, world);
      }

      @Override
      public void areaDamage(int x, int y, int radius, int damage, int towerDamage, int hitId) {
        damageArea(x, y, radius, damage, towerDamage, hitId);
      }
    };
  }

  /**
   * The area of one of the entity's hits: every arena entity in the circle that the entity's own
   * targeting may hit, its own side spared, takes the damage or the crown-tower damage, with no
   * limit and no split.
   */
  private void damageArea(int x, int y, int radius, int damage, int towerDamage, int hitId) {
    List<TargetView> entities = new ArrayList<>();
    for (WorldEntity entity : world.present()) {
      entities.add(entity.getTargetView());
    }
    AreaDamage.Area area =
        new AreaDamage.Area(x, y, radius, damage, towerDamage, hitId, 0, false, true, true, false);
    AreaDamage.Outcome outcome =
        AreaDamage.damage(
            targeting,
            entities,
            area,
            ValidatorQueries.standard1v1(),
            (victim, dealt, id) ->
                world.dealAreaDamage(this, world.entityOf(victim.getEntity()), dealt, id));
    world.areaDamaged(this, area, outcome);
  }

  /** What the damage chain asks about this entity as a target. */
  protected DamageQueries damageQueries() {
    return new DamageQueries() {
      @Override
      public boolean crownTowerTarget() {
        return targetView.isCrownTowerTarget();
      }
    };
  }

  /** Brings the alive answer and the advertised hit points back into step with the object. */
  private void refreshHitPoints() {
    view.setAlive(HitPoints.alive(hitPoints));
    targetView.setHitPoints(hitPoints == null ? 0 : hitPoints.getHitPoints());
  }

  /** The entity's unique name within the battle, as trajectories and logs refer to it. */
  public String name() {
    return view.getName();
  }

  public int side() {
    return view.getSide();
  }

  /** The entity's level, counted from 1. */
  public int level() {
    return PackedLevel.level(packedLevel);
  }

  /** The side facing the given one. */
  public static int opposing(int side) {
    return side == SIDE_BOTTOM ? SIDE_TOP : SIDE_BOTTOM;
  }

  /**
   * Refreshes the previous-position copy from the current position at the head of a tick, before
   * anything has moved, so a pass that reads it during the tick sees where the entity stood when
   * the tick began.
   */
  void beginTick() {
    view.setPrevX(view.getX());
    view.setPrevY(view.getY());
    view.setPrevZ(view.getZ());
  }

  /** True once the entity has asked to be removed regardless of its hit points. */
  protected boolean removalRequested() {
    return false;
  }

  @Override
  protected void onRegistered() {
    view.setId(getId());
  }

  @Override
  public boolean isRemovable() {
    return HitPoints.removable(removalRequested(), hitPoints);
  }
}
