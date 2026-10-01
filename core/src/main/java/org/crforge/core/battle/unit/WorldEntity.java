package org.crforge.core.battle.unit;

import static org.crforge.core.util.ValidationUtils.checkArgument;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.EntityActions;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.ActionOwner;
import org.crforge.core.battle.action.DamageType;
import org.crforge.core.battle.action.GiantBufferBuff;
import org.crforge.core.battle.filter.FilterSubject;
import org.crforge.core.battle.filter.ObjectCensus;
import org.crforge.core.battle.projectile.ProjectileAmounts;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.projectile.ProjectileLauncher;
import org.crforge.core.battle.spawn.SpawnArguments;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.combat.AreaDamage;
import org.crforge.core.pathfinding.combat.DamageApplication;
import org.crforge.core.pathfinding.combat.DamageQueries;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.combat.Healing;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.crforge.core.pathfinding.combat.LevelScaling;
import org.crforge.core.pathfinding.combat.PackedLevel;
import org.crforge.core.pathfinding.combat.RarityTable;
import org.crforge.core.pathfinding.combat.ScalingGlobals;
import org.crforge.core.pathfinding.combat.ScalingMode;
import org.crforge.core.pathfinding.target.DefaultSelectionQueries;
import org.crforge.core.pathfinding.target.DefaultTargetSelection;
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
 * scaled to that level at creation, and again when an action changes its level; the level itself is
 * kept packed against the entity's rarity, as the scaling reads it. An entity whose hit points at
 * its level are not positive carries no hit-points object and counts as alive.
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
            + " at that level, a unit that fires with no damage of its own taking its projectile's"
            + " damage at its level, the alive answer, the removal test, the tag word recomputed at the"
            + " pre-hook from the one-step word and the actions' tags, and that a removable entity"
            + " leaves the holder at the next cleanup, which tells every other entity at once; a"
            + " targeting component on every entity, seeded with the opposing side's towers, whose"
            + " hits go through the hit application, whose area, for a row with an area radius,"
            + " takes the entity as its owner, and whose reference to an entity that left is"
            + " dropped by the removal notice; a level change moving the level, the damage from"
            + " the next hit, the maxima and, on a rise only, the hit points by their share; a"
            + " death handing what killed it - the unit, the projectile, the typed hit's source"
            + " still in the battle, the killer - to the battle's death handler; a row swap reading"
            + " the new row from then on, the maxima recomputed at the kept level and the hit"
            + " points kept; the attack sequence's entry at the index giving the next hit's"
            + " projectile and damage for an order of two or more, and an index-setting action"
            + " storing only below the order's length; a shield created full at its value at the"
            + " level by the card hit-points rule, every hit it takes reported, the excess of a"
            + " hit lost, and its break resetting an attacker with an attack sequence, held by"
            + " recruit_tower, guards_knight, poison_guards and tombstone_crazy_life - the reset,"
            + " and a shield through a level change or a swap, held by no run; the pending-damage"
            + " slot summing the damage of the shots on their way and raising the duration to a"
            + " shot's flight rounded up to 50 ms, at most 1000, and the level the pending-damage"
            + " rule reads the row's full hit points at, held by the battle references' re-locks"
            + " and drops, the rounding, the raise, the cap and the level by unit tests alone.")
public abstract class WorldEntity extends BattleEntity implements ActionOwner, SpawnHost {

  /** Side of the player at the low end of the arena. */
  public static final int SIDE_BOTTOM = 0;

  /** Side of the player at the high end of the arena. */
  public static final int SIDE_TOP = 1;

  /** The slot of the targeting component the combat gate switches, on a character and a tower. */
  private static final int GATED_SLOT = 0;

  /** The longest flight the pending duration is raised to, in milliseconds. */
  private static final int MAX_PENDING_DURATION_MS = 1000;

  /** The battle's shared arena state. */
  protected final BattleWorld world;

  /** The entity's data row, which a data-changing action may swap for another. */
  @Getter private UnitData data;

  /** The entity as the routing grid, the spatial index and the overlay see it. */
  @Getter private final GridEntity view;

  /** The entity as a target: what every other entity's selection and validation look at. */
  @Getter private final TargetView targetView;

  /** The entity's level, packed against its rarity; see {@link PackedLevel}. */
  @Getter private int packedLevel;

  /** The entity's hit points, or null when its hit points at its level are not positive. */
  @Getter private final HitPoints hitPoints;

  /**
   * True once the entity has left the battle. A reference that outlives it, such as a typed hit's
   * source, then answers the level it had but no longer its own rarity row.
   */
  @Getter private boolean left;

  /** The entity's action holder, made the first time anything schedules on it; null until then. */
  private ActionHolder actionHolder;

  /** The entity's variables, which its actions write and expressions from it read. */
  private final Map<Integer, Integer> variables = new HashMap<>();

  /** Damage of one hit at the entity's level, which a level change moves. */
  @Getter private int damage;

  /** The working state of the entity's targeting component: its reference and attack timing. */
  @Getter private final TargetingState targeting;

  /** Answers which target the targeting component should have now. */
  @Getter private final SelectionChain selection;

  /** The buffs listed on the entity, in slot 3, and what they make of its speeds. */
  @Getter private final BuffComponent buffs;

  /** Whether the hit speed was 0 at the last combat gate: a stun was holding the entity. */
  private boolean gateStunned;

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
    // The selection asks the battle the validator's pending-damage questions about a candidate.
    this.selection =
        new SelectionChain(
            world.getIndex(),
            targeting,
            world.getTileMap().height(),
            world.getValidatorQueries(),
            DefaultSelectionQueries.standard1v1(),
            DefaultTargetSelection.Rules.standard());
    // A match's end holds every attack timer at zero.
    selection.setAttackTimersHeld(world::isMatchEnded);
    selection.setHitSink(
        (target, sequenceIndex, extraTargets, last) -> {
          refuseHit();
          return HitApplication.apply(targeting, target, sequenceIndex, last, hitQueries());
        });
    // The attack timer, the dash and the special loads step by the time the buffs scale.
    this.buffs = new BuffComponent(this, world);
    selection.setTimeScaler(buffs::hitSpeed);
    // A timer-driven sequence's index is the window its attack timer has reached.
    selection.setWindowWalk(attackTimerMs -> this.data.attackSequence().windowAt(attackTimerMs));
    attach(buffs);
    // Every entity carries a buff component, which the priority rule asks about.
    targetView.setBuffComponentPresent(true);
    if (data.ignoreTargetsWithBuff() != null) {
      // A row that ranks the carriers of a buff lower asks each candidate's buff list for it.
      String ranked = data.ignoreTargetsWithBuff();
      selection.setDeprioritizingBuffCarrier(
          candidate -> world.entityOf(candidate.getEntity()).getBuffs().carries(ranked));
    }
    this.packedLevel = PackedLevel.fromLevel(level, data.rarity());
    targetView.setPendingDamageKey(packedLevel);
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
    if (hitPoints != null) {
      hitPoints.setDecayStep(HitPoints.decayStep(maximum, data.lifeTimeMs()));
      // The shield starts full, at its value at the level by the card rule.
      int shield = shieldAt(data, packedLevel);
      hitPoints.setShieldMaximum(shield);
      hitPoints.setShield(shield);
    }
    this.damage = damageAt(packedLevel);
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
   * <p>A death the event causes is not run here: the battle tells its observers of the event first,
   * then runs the death with what dealt it.
   *
   * @param damage hit points the source is dealing, before the two sides' buffs
   * @param dedupeId id of a source that must land on this entity only once; 0 for a direct hit
   * @param directionX direction of the hit along the arena's width
   * @param directionY direction of the hit along the arena's length
   */
  public DamageResult takeDamage(int damage, int dedupeId, int directionX, int directionY) {
    return takeDamage(damage, dedupeId, directionX, directionY, false);
  }

  /**
   * Deals one damage event to the entity, as {@link #takeDamage(int, int, int, int)} does.
   *
   * @param passesHidden true for a hit the damage entry lets through while the entity is hidden: an
   *     area effect's that reaches hidden units
   */
  public DamageResult takeDamage(
      int damage, int dedupeId, int directionX, int directionY, boolean passesHidden) {
    if (hitPoints == null) {
      return DamageResult.NOTHING;
    }
    int shieldBefore = hitPoints.getShield();
    DamageResult result =
        DamageApplication.damage(
            hitPoints, damage, dedupeId, directionX, directionY, damageQueries(passesHidden));
    shieldHit(damage, shieldBefore);
    refreshHitPoints();
    return result;
  }

  /**
   * Runs when a hit takes the entity's hit points to zero, in the pass that lands it. The entity is
   * still visited by the rest of the tick and leaves the holder only in its closing cleanup; its
   * own death handler switches off what it no longer does.
   */
  protected void died() {}

  /**
   * The death of the entity, in the pass whose hit took its hit points to zero, once the battle has
   * told its observers of that hit: first what the entity itself switches off, then the battle's
   * death slot and death handler. The killing side is the attacker's, and none without one.
   *
   * @param attacker what killed it, or null for nothing
   */
  void die(BattleEntity attacker) {
    die(attacker, sideOf(attacker));
  }

  /**
   * The death of the entity, with the side of the hit that killed it given apart from the attacker,
   * as damage over time gives the side its buff was applied for.
   *
   * @param attacker what killed it, or null for nothing
   * @param killingSide the side of the killing hit, or -1 for none
   */
  void die(BattleEntity attacker, int killingSide) {
    died();
    world.entityDied(this, attacker, killingSide);
  }

  /** The side of what killed an entity: an arena entity's, a projectile's or an area effect's. */
  private static int sideOf(BattleEntity attacker) {
    if (attacker instanceof WorldEntity entity) {
      return entity.side();
    }
    if (attacker instanceof ProjectileEntity projectile) {
      return projectile.getSide();
    }
    if (attacker instanceof AreaEffectEntity areaEffect) {
      return areaEffect.side();
    }
    return -1;
  }

  /**
   * Makes every arena entity of this tick known to the entity's selection. The first call that
   * finds the opposing side's towers also registers its princess towers as the default targets, in
   * creation order along the arena's width, and seeds the selection with its king, which is no
   * candidate itself. A rider registered in the command pass of the battle's first tick, before any
   * pre-pass has listed the towers, has them registered by that tick's pre-pass.
   */
  void registerCandidates(List<WorldEntity> present) {
    int enemy = opposing(side());
    if (!towersRegistered
        && present.stream().anyMatch(e -> e instanceof TowerEntity && e.side() == enemy)) {
      towersRegistered = true;
      for (WorldEntity entity : present) {
        if (entity instanceof TowerEntity tower && tower.side() == enemy) {
          if (tower.getData().king()) {
            // The king fills the side's tower slot: it seeds the selection and is no candidate.
            selection.register(tower.getTargetView());
            if (selection.getSeed() == null) {
              selection.setSeed(tower.getTargetView());
            }
          } else {
            selection.registerTower(tower.getTargetView());
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
    // The buff component hears of it after the targeting component.
    buffs.entityRemoved(removed);
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
        return attackDamage();
      }

      @Override
      public int chargeProgress() {
        return WorldEntity.this.chargeProgress();
      }

      @Override
      public int chargedDamage() {
        return WorldEntity.this.chargedDamage();
      }

      @Override
      public void resetCharge() {
        WorldEntity.this.resetCharge();
      }

      @Override
      public void buffOnDamage(TargetView target) {
        WorldEntity.this.buffOnDamage(target);
      }

      @Override
      public void hitEnded() {
        WorldEntity.this.hitEnded();
      }

      @Override
      public void hitAllowed() {
        WorldEntity.this.hitAllowed();
      }

      @Override
      public void directHitDealt() {
        // A row with an area effect on its hits makes it where the entity stands.
        if (data.areaEffectOnHit() != null) {
          world.areaEffectOnHit(WorldEntity.this);
        }
      }

      @Override
      public int nextHitId() {
        return world.nextHitId();
      }

      @Override
      public void dealDamage(
          TargetView target, int damage, int hitId, int directionX, int directionY) {
        world.dealDamage(WorldEntity.this, target, damage, directionX, directionY);
      }

      @Override
      public void launchProjectiles(TargetingState t, TargetView target, int sequenceIndex) {
        ProjectileLauncher.launch(WorldEntity.this, t, target, sequenceIndex, world);
      }

      @Override
      public void areaDamage(int x, int y, int radius, int damage, int towerDamage, int hitId) {
        damageArea(x, y, radius, damage, towerDamage, hitId);
      }

      @Override
      public boolean hitListeners() {
        return !hitListenerRuns().isEmpty();
      }

      @Override
      public int listenedDamage(int damage, int hitId, boolean crownTower) {
        List<GiantBufferBuff.Run> runs = hitListenerRuns();
        int out = damage;
        for (int i = runs.size() - 1; i >= 0; i--) {
          out = runs.get(i).damage(out, hitId, crownTower);
        }
        return out;
      }

      @Override
      public void attackEnded() {
        for (GiantBufferBuff.Run run : hitListenerRuns()) {
          run.attackEnded(actionHolder());
        }
      }
    };
  }

  /**
   * Tells the entity's listening actions, from the last listed down, of a projectile it is
   * registering: an enchanting buff on its completing hit hands the projectile a copy, listed on
   * the projectile's own holder.
   *
   * @param projectile the projectile being registered
   */
  public void projectileRegistered(ProjectileEntity projectile) {
    List<GiantBufferBuff.Run> runs = hitListenerRuns();
    for (int i = runs.size() - 1; i >= 0; i--) {
      GiantBufferBuff.Run copy = runs.get(i).projectileCopy(projectile);
      if (copy != null) {
        projectile.actionHolder().list(copy);
      }
    }
  }

  /**
   * The entity's running actions that listen to its hits, in list order: its enchanting buffs.
   * Every other class keeps the base hit slots, which hand a damage on unchanged and do nothing at
   * an attack's end, so the chains are those of the enchanting buffs alone. The king tower's own
   * actions are never listed as listeners.
   */
  private List<GiantBufferBuff.Run> hitListenerRuns() {
    if (actionHolder == null || data.king()) {
      return List.of();
    }
    List<GiantBufferBuff.Run> runs = new ArrayList<>();
    for (ActionInstance instance : actionHolder.running()) {
      if (instance instanceof GiantBufferBuff.Run run) {
        runs.add(run);
      }
    }
    return runs;
  }

  /**
   * The buff the entity's row applies to what its direct hit reached, for its BuffOnDamageTime.
   * Nothing for a row without one. A hit on a target the entity has given up would apply it to
   * nothing the reference shows, so it is refused.
   *
   * @param target what the hit was aimed at, or null
   */
  private void buffOnDamage(TargetView target) {
    if (data.buffOnDamage() == null) {
      return;
    }
    if (target == null) {
      throw new UnsupportedOperationException(
          name() + " applies its BuffOnDamage with a hit on nothing, not modelled");
    }
    world.buffOnDamage(this, world.entityOf(target.getEntity()));
  }

  /** Each of the entity's hits its tags let through; a tower's does nothing. */
  protected void hitAllowed() {
    // A tower takes no buff while it is not attacking.
  }

  /** The end of each of the entity's hits; a tower's does nothing. */
  protected void hitEnded() {
    // Only a Kamikaze character's hit does anything at its end.
  }

  /** Refuses a hit whose effect on the entity itself is not established; a tower's has none. */
  protected void refuseHit() {
    // A tower's hit is fully modelled.
  }

  /** The progress of the entity's charge; a tower tracks none. */
  protected int chargeProgress() {
    return HitQueries.NO_CHARGE;
  }

  /** The damage of the entity's charged hit at its level; a tower's ordinary damage. */
  protected int chargedDamage() {
    return attackDamage();
  }

  /** Resets the entity's charge after a hit; a tower has none to reset. */
  protected void resetCharge() {
    // A tower tracks no charge.
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
            new AreaDamage.Queries() {
              @Override
              public boolean untouchable(TargetView victim) {
                return world.entityOf(victim.getEntity()).passedBy(false);
              }

              @Override
              public DamageResult damage(TargetView victim, int dealt, int id) {
                return world.dealAreaDamage(
                    WorldEntity.this, world.entityOf(victim.getEntity()), dealt, id);
              }
            });
    world.areaDamaged(this, area, outcome);
  }

  /**
   * What the damage chain asks about this entity as a target, and about the battle: a match's
   * tiebreaker holds every hit from its first step, and its end refuses every hit's subtraction.
   */
  protected DamageQueries damageQueries() {
    return damageQueries(false);
  }

  /**
   * What the damage chain asks about the entity.
   *
   * @param passesHidden true for a hit the damage entry lets through while the entity is hidden: an
   *     area effect's that reaches hidden units
   */
  protected DamageQueries damageQueries(boolean passesHidden) {
    return new DamageQueries() {
      @Override
      public boolean crownTowerTarget() {
        return targetView.isCrownTowerTarget();
      }

      @Override
      public boolean damageHeld() {
        return world.isHitsHeld();
      }

      // The damage entry refuses a hidden entity, unless the hit passes it.
      @Override
      public boolean hidden() {
        return !passesHidden && WorldEntity.this.hidden();
      }

      // The entry and the bookkeeping refuse an entity in its tunnel.
      @Override
      public boolean untouchable() {
        return tunnelling();
      }

      @Override
      public boolean battleEnded() {
        return world.isMatchEnded();
      }
    };
  }

  /**
   * One step of the lifetime decay on the entity's hit points, advertised at once to attackers that
   * prefer the weakest.
   *
   * @return true when the step took the last hit point
   */
  protected boolean decay() {
    boolean last = hitPoints.decay();
    refreshHitPoints();
    return last;
  }

  /**
   * The combat gate at the tail of the state visit: switches the targeting component on or off and
   * tells the battle of a reference it dropped.
   *
   * @param targetingOn whether the targeting component is on as the gate reaches it
   * @param routePreparer prepares a route when the dropped reference asks for one
   */
  protected void combatGate(boolean targetingOn, Runnable routePreparer) {
    TargetView before = targeting.getReference();
    boolean alive = HitPoints.alive(hitPoints);
    int hitSpeed = buffs.hitSpeed(CombatGate.HIT_SPEED_STEP_MS);
    boolean on =
        CombatGate.targetingOn(
            view,
            targeting,
            targetingOn,
            alive,
            hitSpeed,
            data.hitpoints() != 0,
            routePreparer,
            this::resumeAfterDrop,
            keepsTargetWhileCasting());
    if (before != null && targeting.getReference() == null) {
      world.combatGateDropped(this, before, hitSpeed);
    }
    // Only a switch a stun causes, or the first after one, is told.
    boolean stunned = alive && hitSpeed == 0;
    if (on != isActive(GATED_SLOT) && (stunned || gateStunned)) {
      world.combatComponentSwitched(this, on, hitSpeed);
    }
    gateStunned = stunned;
    setActive(GATED_SLOT, on);
  }

  /**
   * Whether the entity is hidden: no attacker takes it, the damage entry refuses it, and every hit,
   * area and buff passes it by but an area effect's that reaches hidden units. A tower never hides.
   */
  public boolean hidden() {
    return false;
  }

  /** Whether the entity tunnels to its placement, untouchable there. A tower never tunnels. */
  protected boolean tunnelling() {
    return false;
  }

  /**
   * Whether an area or a buff passes the entity by: while it is hidden, unless it comes from an
   * area effect that reaches hidden units. Such an area effect reaching an entity hidden in a way
   * the battle does not model is refused.
   *
   * @param reachesHidden true for an area effect's that reaches hidden units
   */
  public boolean passedBy(boolean reachesHidden) {
    if (!hidden()) {
      return false;
    }
    if (reachesHidden) {
      if (!reachableWhileHidden()) {
        throw new UnsupportedOperationException(
            "an area effect that reaches hidden units reaches "
                + name()
                + ", which is not modelled");
      }
      return false;
    }
    return true;
  }

  /**
   * Whether an area effect that reaches hidden units reaches the entity while it is hidden, as the
   * battle models it. A tower never hides.
   */
  protected boolean reachableWhileHidden() {
    return false;
  }

  /** Whether the entity's ability keeps its target while it casts; a tower has no ability. */
  protected boolean keepsTargetWhileCasting() {
    return false;
  }

  /**
   * Resumes the entity after its reference was dropped, as a dashing row's null path asks. A tower
   * never dashes, so it never asks.
   */
  protected void resumeAfterDrop() {
    throw new UnsupportedOperationException(name() + " asked to resume, which only a dasher does");
  }

  /**
   * Whether the entity is untouchable: attached to another, or still immune from a dash. A tower is
   * never attached and never dashes.
   */
  boolean untouchable() {
    return untouchable(true);
  }

  /**
   * Whether the entity is untouchable, counting the immunity that lingers after a dash only when
   * asked: a projectile's buff on its one target does not count it.
   *
   * @param dashImmunity true to count the immunity left after a dash
   */
  boolean untouchable(boolean dashImmunity) {
    return false;
  }

  /**
   * The entity's pending-damage slot, which a homing shot calls on its target: the shot's damage is
   * added to what is on its way to the entity, and a shot starting its flight raises the pending
   * duration to its flight time rounded up to 50 ms, at most 1000. A shot handing its damage back
   * passes the damage negated and a time of -1, which leaves the duration alone. A sum below zero
   * is kept, as the game keeps it after reporting it.
   *
   * @param amount the damage to add, negative to hand it back
   * @param flightMs the shot's flight time in milliseconds, or -1 for none
   */
  public void addPendingDamage(int amount, int flightMs) {
    view.setPendingDamageAmount(view.getPendingDamageAmount() + amount);
    if (flightMs < 0) {
      return;
    }
    int rounded = (flightMs + 49) / 50 * 50;
    view.setPendingDamageDurationMs(
        Math.min(Math.max(view.getPendingDamageDurationMs(), rounded), MAX_PENDING_DURATION_MS));
  }

  /**
   * Takes one hit of a buff's damage over time: refused only where damage is forbidden, with no
   * dedupe id and no heading.
   */
  DamageResult takeDamageOverTime(int damage) {
    if (hitPoints == null) {
      return DamageResult.NOTHING;
    }
    int shieldBefore = hitPoints.getShield();
    DamageResult result = DamageApplication.overTime(hitPoints, damage, damageQueries());
    shieldHit(damage, shieldBefore);
    refreshHitPoints();
    return result;
  }

  /** A shield's value at a level: its row's ShieldHitpoints by the card hit-points rule. */
  private static int shieldAt(UnitData row, int packedLevel) {
    if (row.shieldHitpoints() <= 0) {
      return 0;
    }
    return LevelScaling.scale(
        ScalingGlobals.standard(),
        row.shieldHitpoints(),
        packedLevel,
        ScalingMode.CARD_HITPOINTS,
        row.rarity());
  }

  /**
   * After a hit that met the shield: the battle hears of it, and a hit that brought the shield to 0
   * broke it, in the hit's own pass.
   */
  private void shieldHit(int damage, int shieldBefore) {
    if (shieldBefore < 1 || damage < 1 || hitPoints.getShield() == shieldBefore) {
      return;
    }
    world.shieldHit(this, damage, shieldBefore, hitPoints.getShield());
    if (hitPoints.getShield() == 0) {
      world.shieldBroken(this);
    }
  }

  /** Brings the alive answer and the advertised hit points back into step with the object. */
  /**
   * Takes its share of another object's hit points, as a morph gives it: the other's hit points
   * times this maximum over the other's, rounded toward zero.
   *
   * @param from the object it replaces
   */
  void takeHitPointShare(WorldEntity from) {
    HitPoints old = from.getHitPoints();
    long share = (long) old.getHitPoints() * hitPoints.getMaximum() / old.getMaximum();
    hitPoints.setHitPoints((int) share);
    refreshHitPoints();
  }

  /**
   * Takes one heal of a buff's heal over time, capped as the hit-point object caps it: a king tower
   * below its maximum stops one short of it.
   *
   * @param amount the heal
   * @param overHealPercent the share of the maximum the heal may reach; 0 for the maximum
   */
  void takeHeal(int amount, int overHealPercent) {
    Healing.heal(hitPoints, amount, overHealPercent, data.king());
    refreshHitPoints();
  }

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

  /**
   * Runs after each projectile the entity launches, with that projectile's aim. By default nothing;
   * a character whose row pushes it back asks for its pushback here.
   *
   * @param aimX where the projectile is aimed
   * @param aimY where the projectile is aimed
   */
  public void launched(int aimX, int aimY) {}

  /**
   * The tag recompute every entity runs first thing in its pre-hook: the tag word every reader sees
   * becomes the one-step word handlers wrote since the last recompute, which is then cleared,
   * together with the tags of every action the entity runs. A tag a handler sets therefore lasts
   * one step, and a tag an action sets lasts as long as the action is listed.
   */
  @Override
  protected void preHook() {
    GridEntity view = getView();
    view.setFlags(view.getPendingFlags() | actionTags());
    view.setPendingFlags(0);
  }

  /** The tags of every action the entity lists, finished ones included. */
  protected long actionTags() {
    return actionHolder == null ? 0 : actionHolder.tags();
  }

  /**
   * The entity's action holder, made on first use as the standard game makes it. Its pending passes
   * are the battle's, so it reads the battle's in-pass flag.
   */
  @Override
  public ActionHolder actionHolder() {
    if (actionHolder == null) {
      actionHolder = new ActionHolder(this, world.getHolder()::isInPendingPass);
    }
    return actionHolder;
  }

  @Override
  public int x() {
    return view.getX();
  }

  @Override
  public int y() {
    return view.getY();
  }

  @Override
  public int kind() {
    return getKind();
  }

  /** Every arena entity answers as a character, whose own columns a spawner may read. */
  @Override
  public boolean isCharacter() {
    return true;
  }

  /** No entity carries a prestige yet, so a spawn that inherits it inherits none. */
  @Override
  public int prestige() {
    return 0;
  }

  @Override
  public int packedLevel() {
    return packedLevel;
  }

  @Override
  public List<SpawnHost> spawnCharacters(SpawnArguments arguments) {
    return world.spawnCharacters(this, arguments);
  }

  @Override
  public void handOverChampion(SpawnHost child) {
    world.handOverChampion(this, child);
  }

  /**
   * Before its registration visit, a spawned entity takes its id into its view and learns this
   * tick's arena entities as its candidates, the opposing towers among them, as the pre-pass would
   * have given it.
   */
  @Override
  protected void beforeRegistrationVisit() {
    view.setId(getId());
    registerCandidates(world.present());
  }

  @Override
  public EntityActions actions() {
    return actionHolder == null ? EntityActions.NONE : actionHolder;
  }

  /** Marks the entity as gone from the battle, at the cleanup that removes it. */
  void leave() {
    left = true;
  }

  /** The entity as a game object filter asks about it. */
  public FilterSubject filterSubject() {
    return new EntityFilterSubject(this);
  }

  /** The battle's live objects as a filter asks about them, for this entity's team and row. */
  @Override
  public ObjectCensus census() {
    return new ObjectCensus(world.filterSubjects(), side() & 1, data.name());
  }

  @Override
  public HitPoints actionHitPoints() {
    return hitPoints;
  }

  @Override
  public int actionPackedLevel() {
    return packedLevel;
  }

  @Override
  public int actionId() {
    return getId();
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
  public boolean actionSpawnsAttached() {
    return data.spawnAttach();
  }

  @Override
  public boolean liveObject(int id) {
    return world.liveObject(id) != null;
  }

  /**
   * Takes a new level, as a level-changing action gives it. Nothing happens when the level it
   * stands for is the one the entity has. Otherwise the level is re-based on the entity's rarity,
   * its damage follows from the next hit on, and its hit points take the change: the maximum and
   * both team pools are the new level's, and on a rise the hit points keep their share of the old
   * maximum, in hundred-thousandths and truncated, but are never lowered; on a fall they are kept
   * as they stand, even above the new maximum. A projectile already in flight keeps the level it
   * was launched at. Nothing else of the entity changes.
   *
   * <p>Refused rather than guessed: a tower, whose maximum is worked out on a branch of its own,
   * The shield's maximum follows the level too, and a shield that is up keeps its share on a rise
   * as the hit points do; a broken one stays at 0. The growth percentage the share is taken at is
   * the usual 100, as no unit that grows is modelled.
   *
   * @param packed the new level, packed
   */
  @Override
  public void changeLevel(int packed) {
    int delta = effectiveLevel(packed) - effectiveLevel(packedLevel);
    if (delta == 0) {
      return;
    }
    if (data.king() || data.summonerTower()) {
      throw new UnsupportedOperationException(
          "changing the level of " + name() + ", a tower, is not established");
    }
    if (hitPoints != null && hitPoints.getDecayStep() != 0) {
      throw new UnsupportedOperationException(
          "changing the level of " + name() + ", whose hit points decay, is not established");
    }
    packedLevel = PackedLevel.pack(packed, data.rarity());
    targetView.setPendingDamageKey(packedLevel);
    damage = damageAt(packedLevel);
    if (hitPoints == null) {
      return;
    }
    int oldMaximum = hitPoints.getMaximum();
    int maximum =
        LevelScaling.hitpoints(
            ScalingGlobals.standard(),
            data.hitpoints(),
            packedLevel,
            data.rarity(),
            data.king(),
            data.summonerTower());
    hitPoints.setMaximum(maximum);
    hitPoints.setTeamPool(0, maximum);
    hitPoints.setTeamPool(1, maximum);
    if (delta >= 1) {
      // Both steps are 32-bit and truncate, as the game's own arithmetic does.
      int share = hitPoints.getHitPoints() * 100_000 / oldMaximum;
      int rescaled = maximum * share / 100_000;
      hitPoints.setHitPoints(Math.max(hitPoints.getHitPoints(), rescaled));
    }
    // The shield's maximum follows the level; a shield that is up keeps its share on a rise, and a
    // broken one stays broken.
    int oldShieldMaximum = hitPoints.getShieldMaximum();
    int shieldMaximum = shieldAt(data, packedLevel);
    hitPoints.setShieldMaximum(shieldMaximum);
    if (delta >= 1 && hitPoints.getShield() >= 1 && oldShieldMaximum >= 1) {
      int share = hitPoints.getShield() * 100_000 / oldShieldMaximum;
      int rescaled = shieldMaximum * share / 100_000;
      hitPoints.setShield(Math.max(hitPoints.getShield(), rescaled));
    }
    refreshHitPoints();
  }

  /**
   * Takes another data row, as a data-changing action gives it. Only a character can: see its own
   * swap.
   *
   * @param rowName the name of the new character row
   * @param resetTarget true to give up the target rather than keep it
   */
  @Override
  public void changeData(String rowName, boolean resetTarget) {
    throw new UnsupportedOperationException(name() + " cannot take another data row");
  }

  /**
   * The part of a row swap every entity shares: the row itself, read from here on wherever the
   * entity reads a column, the damage of its next hit, and the hit points' maximum and both team
   * pools recomputed from the new row at the level the entity has, which is not re-based. The hit
   * points themselves are kept, even above the new maximum.
   *
   * @param next the new row
   */
  protected void swapRow(UnitData next) {
    if (hitPoints == null ? next.hitpoints() > 0 : next.hitpoints() <= 0) {
      throw new UnsupportedOperationException(
          name() + " would gain or lose its hit points by taking " + next.name());
    }
    data = next;
    damage = damageAt(packedLevel);
    if (hitPoints != null) {
      int maximum =
          LevelScaling.hitpoints(
              ScalingGlobals.standard(),
              next.hitpoints(),
              packedLevel,
              next.rarity(),
              next.king(),
              next.summonerTower());
      hitPoints.setMaximum(maximum);
      hitPoints.setTeamPool(0, maximum);
      hitPoints.setTeamPool(1, maximum);
      // The shield's maximum comes from the new row; the shield itself is kept as it is.
      hitPoints.setShieldMaximum(shieldAt(next, packedLevel));
    }
    refreshHitPoints();
  }

  /**
   * The projectile the entity's next hit launches: the attack sequence's entry at the index, when
   * the sequence has two or more in its order, otherwise the row's.
   */
  public ProjectileData attackProjectile() {
    AttackSequence sequence = data.attackSequence();
    return sequence.replacesAttack()
        ? sequence.entryAt(targeting.getAttackSequenceIndex()).projectile()
        : data.projectile();
  }

  /**
   * The damage of the entity's next direct hit: the attack sequence's entry at the index, at the
   * entity's level and falling back to the entry's projectile as the row's damage does, when the
   * sequence has two or more in its order; otherwise the row's.
   */
  int attackDamage() {
    AttackSequence sequence = data.attackSequence();
    if (!sequence.replacesAttack()) {
      return damage;
    }
    AttackSequence.Entry entry = sequence.entryAt(targeting.getAttackSequenceIndex());
    ScalingGlobals globals = ScalingGlobals.standard();
    ProjectileData projectile = entry.projectile();
    return LevelScaling.damage(
        globals,
        entry.damage(),
        packedLevel,
        data.rarity(),
        data.king(),
        data.summonerTower(),
        projectile == null
            ? null
            : () -> ProjectileAmounts.damage(globals, projectile, packedLevel));
  }

  /**
   * Lets the entity's targeting go on after a pingpong projectile it launched came back: the hold
   * the launch set is cleared, so the visit passes its reference check again once the resume delay
   * has run. The return tells only a targeting component that is on; one that is off would keep the
   * hold until the projectile's removal cleared it, which is not modelled.
   *
   * @param projectile the name of the projectile that came back, for the refusal
   */
  public void pingpongReturned(String projectile) {
    if (!isActive(GATED_SLOT)) {
      throw new UnsupportedOperationException(
          projectile + " came back to " + name() + " with its targeting off, not modelled");
    }
    targeting.setVisitSuspended(false);
  }

  /**
   * Stores an attack sequence index, as an index-setting action does: only below the length of the
   * order, a longer one dropped and the old one kept, and, unless the action asks otherwise, only
   * while the targeting component is on.
   *
   * @param index the index
   * @param evenIfCombatDisabled true to store it with the targeting component off too
   */
  @Override
  public void setAttackSequenceIndex(int index, boolean evenIfCombatDisabled) {
    if (!evenIfCombatDisabled && !isActive(0)) {
      return;
    }
    if (index < 0) {
      throw new UnsupportedOperationException(
          name() + " was given the attack sequence index " + index + ", which is not established");
    }
    if (data.attackSequence().order().size() > index) {
      targeting.setAttackSequenceIndex(index);
    }
  }

  /** The level a packed value stands for: the relative level plus the signed steps. */
  private static int effectiveLevel(int packed) {
    return ((packed >> 8) & 0xff) + (byte) packed;
  }

  /**
   * Damage of one hit at a level. A unit that fires and has no damage of its own deals its
   * projectile's damage at its level.
   */
  private int damageAt(int packed) {
    ScalingGlobals globals = ScalingGlobals.standard();
    ProjectileData projectile = data.projectile();
    return LevelScaling.damage(
        globals,
        data.damage(),
        packed,
        data.rarity(),
        data.king(),
        data.summonerTower(),
        projectile == null ? null : () -> ProjectileAmounts.damage(globals, projectile, packed));
  }

  @Override
  public boolean kingTower() {
    return getData().king();
  }

  @Override
  public void spawnBuff(String action, String buff, int timeMs, ActionOwner source) {
    if (!(source instanceof SpawnHost host)) {
      throw new UnsupportedOperationException(
          action + " puts a buff on " + name() + " from no object, which is not modelled");
    }
    world.spawnBuff(this, action, buff, timeMs, host);
  }

  @Override
  public void killBy(ActionOwner killer) {
    world.kill(this, killer instanceof WorldEntity entity ? entity : null);
  }

  @Override
  public void queueTypedHit(ActionOwner source, int amount, DamageType type) {
    world.queueTypedHit(source instanceof WorldEntity entity ? entity : null, this, type, amount);
  }

  /**
   * Takes a typed hit from the drain, its pipeline already run.
   *
   * @return what the hit did; a death it causes is the battle's to run
   */
  DamageResult takeTypedHit(int amount, int damageId, int directionX, int directionY) {
    int shieldBefore = hitPoints.getShield();
    DamageResult result =
        DamageApplication.typedHit(
            hitPoints, amount, damageId, directionX, directionY, damageQueries());
    shieldHit(amount, shieldBefore);
    refreshHitPoints();

    return result;
  }

  /**
   * Takes a kill: the whole hit points as one hit that ignores the battle's holds.
   *
   * @return what the kill did; the death it causes is the battle's to run
   */
  DamageResult takeKill() {
    if (hitPoints == null) {
      return DamageResult.NOTHING;
    }
    int shieldBefore = hitPoints.getShield();
    int whole = hitPoints.getHitPoints();
    DamageResult result = DamageApplication.kill(hitPoints, damageQueries());
    shieldHit(whole, shieldBefore);
    refreshHitPoints();

    return result;
  }

  /**
   * Takes one step of a tiebreaker's drain, which passes the battle's holds.
   *
   * @param damage the drain's step
   * @return what the step did; the death it causes is the battle's to run
   */
  DamageResult takeDrain(int damage) {
    if (hitPoints == null) {
      return DamageResult.NOTHING;
    }
    int shieldBefore = hitPoints.getShield();
    DamageResult result = DamageApplication.drain(hitPoints, damage, damageQueries());
    shieldHit(damage, shieldBefore);
    refreshHitPoints();
    return result;
  }

  @Override
  public int variable(int key) {
    return variables.getOrDefault(key, 0);
  }

  @Override
  public void setVariable(int key, int value) {
    variables.put(key, value);
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
