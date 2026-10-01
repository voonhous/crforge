package org.crforge.core.battle.unit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.EntityActions;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionOwner;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.DamageType;
import org.crforge.core.battle.data.ActionBinding;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.spawn.SpawnArguments;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.AreaDamage;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.crforge.core.pathfinding.combat.LevelScaling;
import org.crforge.core.pathfinding.combat.ScalingGlobals;
import org.crforge.core.pathfinding.combat.ScalingMode;
import org.crforge.core.pathfinding.index.ShapeTests;
import org.crforge.core.pathfinding.index.SpatialQuery;
import org.crforge.core.pathfinding.move.BuffPush;
import org.crforge.core.pathfinding.move.MovementState;
import org.crforge.core.pathfinding.target.ReferenceValidator;
import org.crforge.core.pathfinding.target.TargetView;
import org.crforge.core.pathfinding.target.TargetingState;
import org.crforge.core.pathfinding.target.ValidatorQueries;

/**
 * An area effect: an object of its own kind that stands at a point for its life and hits the area
 * around it on a schedule.
 *
 * <p>It is created at a point for a side and a level - re-based on its own rarity - and handed to
 * the holder, which gives it its id at once in the area-effect band and admits it at the next
 * cleanup. As it is admitted its row's starting action is scheduled on its own holder, with itself
 * as the cause. It has no component and nothing targets it; its update is its post-hook, which the
 * holder runs in id order, so every area effect updates before every projectile and character.
 *
 * <p>Each update takes 50 ms off its countdown and works out how many hits fall in that step: with
 * a hit speed, how many multiples of it the offset time passed; with a hit speed of 0, one hit on
 * the first update; with a negative one, none. The radius is its row's, or, with a larger maximum,
 * the one shrinking from the maximum toward it as the countdown falls. Each hit deals the row's
 * damage at its level through the area damage around it, with itself as the owner: its own side
 * spared when it hits enemies only, crown towers taking the damage raised by the row's crown-tower
 * percent, no hit id, the row's target limit, the damage shared out when the row shares it, its
 * victims pushed away from its point, and its own test of a target in place of a character's - no
 * building for a row that ignores them, and air or ground as the row reaches them. A hit of no
 * damage deals nothing. A row with a buff applies it with each hit, after the damage, to every
 * character in the circle it reaches, for the row's buff time - no longer than the life it has left
 * and one hit speed more when the row caps it. A row that chains another area effect creates it at
 * its own point and level on its first update. A row whose buff attracts, the Tornado's, pulls
 * every enemy unit in its circle toward its centre with each hit, before the buff, and is the
 * parent of the buff it applies when the buff says so. A row with a projectile launches one after
 * the hits of a step whose hit count rose, onto the enemy with the most hit points and shield in
 * its circle that it has not struck before, or onto its own point; with nobody to strike, the
 * update ends there. A Clone's hit, before any damage, schedules its hit action on every unit of
 * its own side in its circle that the index finds - alive, not hidden, not untouchable, not a
 * building, no unit a Clone passes by and no clone - with itself as the cause, which clones it in
 * the tick's last pending pass. When the countdown reaches 0 its life-end action is scheduled on
 * itself; it leaves at the cleanup that finds the countdown below 1.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled and held by area_effect_direct and area_effect_death: the creation by a death"
            + " and by a direct placement, the level re-based, the id band, the admission and the"
            + " starting action scheduled then, the update as the post-hook, the countdown, the hit"
            + " schedule, the radius, a hit as the area damage with the area effect as its owner"
            + " and its own target test, the pushback, and the removal. Held by rage_knight,"
            + " zap_knight and poison_knight_tower: the buff each hit applies to the characters in"
            + " its circle it reaches, its time capped by its own life, and the area effect its row"
            + " chains, created on its first update; its own-troops test, which no run meets. Not"
            + " modelled, and refused by its row: a buff"
            + " boosting one target or lasting longer by level, a hit action but a Clone's, the"
            + " shape,"
            + " the filter, the spawns, a launch from its source or spread about its point, the"
            + " life condition, following, tags,"
            + " deflection, a lifetime that grows by level, the push's floor and gate lift and one"
            + " hit per target. An area that reaches hidden units takes, damages and buffs a hidden"
            + " Tesla, held by tesla_hidden_spells; reaching a unit in its tunnel is refused. The"
            + " pull of an attracting buff before the buff, and the area effect as the parent of"
            + " a buff it controls, held by tornado_group_off_lane and tornado_heavy_light_tower;"
            + " the slot ControlsBuff gates is reached by no path the battle models. The launch of"
            + " its projectile after its hits, one on an update whose hit count rose, the chooser"
            + " of a row with HitBiggestTargets, the start on the target or on its own point, and"
            + " the area effect as the projectile's launcher, held by lightning_defenders_tower and"
            + " royal_delivery_group; the update ending without its life-end action when the"
            + " chooser finds nobody, which no run meets. A Clone's hit action on the units its"
            + " index query finds, in the query's order, and its filter, held by clone_golem_group;"
            + " an area effect with a buff reaching a clone, whose filter asks an untraced query of"
            + " the buff, is refused. Its starting action's expressions reading the area effect"
            + " itself, its point and its side, held by graveyard_tower_defender and"
            + " graveyard_right_side1. Not created yet by an action.")
public final class AreaEffectEntity extends BattleEntity implements ActionOwner, SpawnHost {

  /** Milliseconds one update takes off the countdown. */
  private static final int STEP_MS = 50;

  private final BattleWorld world;

  @Getter private final AreaEffectData data;

  /** The area effect's name in logs, given once its id is known when none is given. */
  private String name;

  private final int side;

  @Getter private final int x;
  @Getter private final int y;

  /** Its level, packed against its own rarity. */
  @Getter private final int packedLevel;

  /** What is left of its life, in milliseconds; it leaves once this is below 1. */
  @Getter private int countdown;

  /** True once the area effect its row chains has been created, on its first update. */
  private boolean chained;

  /**
   * The ids of the objects its projectiles were dropped onto, listed as each is chosen, which its
   * chooser passes by.
   */
  private final List<Integer> struck = new ArrayList<>();

  private final ActionHolder actionHolder;

  /** The variables its actions write. */
  private final Map<Integer, Integer> variables = new HashMap<>();

  /** Its hits' owner as the validator sees it: an area effect of its side at its point. */
  private final TargetingState owner;

  /** Its own test of a target, which the validator asks of an owner that is not a character. */
  private final ValidatorQueries validatorQueries;

  /**
   * @param world the battle it belongs to
   * @param data its row
   * @param side its side
   * @param x its point along the width
   * @param y its point along the length
   * @param packedLevel its level, packed against its own rarity
   */
  AreaEffectEntity(
      BattleWorld world, AreaEffectData data, int side, int x, int y, int packedLevel) {
    super(KIND_AREA_EFFECT);
    this.world = world;
    this.data = data;
    this.side = side;
    this.x = x;
    this.y = y;
    this.packedLevel = packedLevel;
    this.countdown = data.lifeDurationMs();
    this.actionHolder = new ActionHolder(this, world.getHolder()::isInPendingPass);
    GridEntity view = new GridEntity();
    view.setType(ReferenceValidator.TYPE_CONTACT);
    view.setSide(side);
    view.setX(x);
    view.setY(y);
    this.owner = new TargetingState();
    owner.setOwner(view);
    this.validatorQueries =
        new ValidatorQueries() {
          @Override
          public boolean nonCharacterOwnerAccepts(TargetView target) {
            return accepts(target);
          }
        };
  }

  /** Whether the entity that asks the validator is this area effect. */
  boolean asks(GridEntity asker) {
    return owner.getOwner() == asker;
  }

  /** Names the area effect once its id is known. */
  void setName(String name) {
    this.name = name;
    owner.getOwner().setName(name);
  }

  /**
   * Its own test of a target: no building for a row that ignores buildings, and a character only
   * when it reaches the character's air or ground.
   */
  private boolean accepts(TargetView target) {
    if (target.building() && data.ignoreBuildings()) {
      return false;
    }
    // For a clone the filter asks a query of the row's buff, which is not traced.
    if (data.buff() != null
        && world.entityOf(target.getEntity()) instanceof CharacterEntity unit
        && unit.isClone()) {
      throw new UnsupportedOperationException(
          "the area effect "
              + name
              + " with a buff reaches the clone "
              + unit.name()
              + ", whose filter's test of the buff is not modelled");
    }
    if (target.getEntity().getType() != ReferenceValidator.TYPE_CHARACTER) {
      return true;
    }
    if (data.hitsAir()) {
      return data.hitsGround() || target.air();
    }
    return !target.air() && data.hitsGround();
  }

  /** As it is admitted, its row's starting action is scheduled on itself, itself the cause. */
  @Override
  protected void onRegistered() {
    world.areaEffectAdmitted(this);
    if (data.onStartingAction() != null) {
      BattleAction starting = world.getActions().build(data.onStartingAction(), binding());
      actionHolder.schedule(starting, ActionHolder.OWN_DELAY, false, actionHolder);
    }
  }

  /**
   * Runs its update at once, as a unit's entry into deploying does for the area object it makes.
   */
  void updateAtOnce() {
    postHook();
  }

  /**
   * The update: the countdown and the hits of the step, told to the observers first; then, on the
   * first update, the area effect its row chains; then each hit, its damage and its buff; then the
   * life-end action.
   */
  @Override
  protected void postHook() {
    int life = data.lifeDurationMs();
    int before = countdown;
    int start = life - countdown;
    countdown -= STEP_MS;
    int end = life - countdown;
    int speed = data.hitSpeedMs();
    // The hits due by the end of the step, and by its start.
    int hit;
    int bound;
    if (speed >= 1) {
      hit = (data.hitSpeedOffsetMs() + end) / speed;
      bound = (data.hitSpeedOffsetMs() + start) / speed;
    } else if (speed == 0) {
      hit = 1;
      bound = start > 0 ? 1 : 0;
    } else {
      hit = 0;
      bound = 0;
    }
    int hits = hit - bound;
    int radius = radiusNow(life);
    int damage =
        LevelScaling.scale(
            ScalingGlobals.standard(),
            data.damage(),
            packedLevel,
            ScalingMode.CARD_DAMAGE,
            data.rarity());
    List<Integer> dealt = new ArrayList<>();
    for (int i = 0; i < hits && damage >= 1; i++) {
      dealt.add(damage);
    }
    world.areaEffectUpdated(this, before, countdown, hits, radius, dealt);
    if (!chained && data.spawnAreaEffectObject() != null) {
      chained = true;
      world.createAreaEffect(
          data.spawnAreaEffectObject(), x, y, side, packedLevel, null, "chained", name);
    }
    if (data.onHitAction() != null && hits >= 1) {
      onHitActions(radius, hits);
    }
    for (int i = 0; i < hits; i++) {
      if (damage >= 1) {
        hit(radius, damage);
      }
      if (data.buff() != null) {
        int time = data.buffTimeMs();
        if (data.capBuffTimeToAreaEffectTime()) {
          time = Math.min(time, countdown + speed);
        }
        BuffData buff = world.buffData(data.buff());
        // An attracting buff pulls before it is applied, whatever its time.
        if (buff.attracts()) {
          pull(radius, buff);
        }
        if (time >= 1) {
          world.areaBuff(this, radius, time);
        }
      }
    }
    // The launch, after the hits: one projectile at most, on a step whose hit count rose. With
    // nobody to drop it on, the update ends there, without its life-end action.
    if (data.projectile() != null && hit > bound && !launch(hit, bound, radius)) {
      return;
    }
    if (countdown <= 0 && data.onLifeTimeEndAction() != null) {
      BattleAction ending = world.getActions().build(data.onLifeTimeEndAction(), binding());
      actionHolder.schedule(ending, ActionHolder.OWN_DELAY, false, actionHolder);
    }
  }

  /** The type bit of a character in the index's type mask: the query keeps characters alone. */
  private static final int CHARACTER_TYPE_MASK = 1 << ReferenceValidator.TYPE_CHARACTER;

  /**
   * The hit action of each hit: the index's query of its circle, buildings by their square and
   * characters alone, in the query's order, each object on where it stands now. For every hit, each
   * object that is alive, not a building when the row ignores them, not hidden unless it reaches
   * hidden units, on the side the row reaches, in the air or on the ground as the row reaches it,
   * not untouchable and passing its own filter gets the row's hit action scheduled, the area effect
   * as the cause: from the post-hook, so it starts in the phase-3 pending pass of the tick.
   */
  private void onHitActions(int radius, int hits) {
    List<GridEntity> views =
        world
            .getIndex()
            .query(new SpatialQuery(x, y, radius, 0, false, true, CHARACTER_TYPE_MASK, -1));
    if (views == null) {
      return;
    }
    List<WorldEntity> found = new ArrayList<>();
    for (GridEntity view : views) {
      found.add(world.entityOf(view));
    }
    for (int i = 0; i < hits; i++) {
      for (WorldEntity target : found) {
        if (!HitPoints.alive(target.getHitPoints())) {
          continue;
        }
        if (data.ignoreBuildings() && target.getTargetView().building()) {
          continue;
        }
        if (!data.affectsHidden() && target.hidden()) {
          continue;
        }
        boolean sameTeam = (target.side() & 1) == (side & 1);
        if (sameTeam ? data.onlyEnemies() : data.onlyOwnTroops()) {
          continue;
        }
        boolean air = target.getTargetView().air();
        if (!data.hitsAir() && air || !data.hitsGround() && !air) {
          continue;
        }
        if (target.untouchable(true) || !onHitFilter(target)) {
          continue;
        }
        BattleAction action = world.getActions().build(data.onHitAction(), world.binding(target));
        world.onHitActionScheduled(this, target, action);
        target.actionHolder().schedule(action, ActionHolder.OWN_DELAY, false, actionHolder);
      }
    }
    world.getIndex().release(views);
  }

  /**
   * The area effect's own filter of a target its hit action reaches: no building when the row
   * ignores them; nothing untargetable; for a Clone nothing tagged against clones, no unit a Clone
   * passes by, no clone and no dead unit; then the air and the ground it reaches.
   */
  private boolean onHitFilter(WorldEntity target) {
    if (target.getTargetView().building() && data.ignoreBuildings()) {
      return false;
    }
    long flags = target.getView().getFlags();
    if ((flags & EntityFlags.UNTARGETABLE) != 0
        || data.cloning() && (flags & EntityFlags.NO_CLONE) != 0) {
      return false;
    }
    if (data.cloning()
        && (target.getData().ignoreClone()
            || target instanceof CharacterEntity unit && unit.isClone()
            || !HitPoints.alive(target.getHitPoints()))) {
      return false;
    }
    if (!data.cloning() && target.untouchable(true)) {
      return false;
    }
    return accepts(target.getTargetView());
  }

  /**
   * One object a launch's chooser could drop the projectile onto, and its size: its hit points and
   * its shield.
   *
   * @param target the object
   * @param size its hit points and shield
   */
  public record Candidate(WorldEntity target, int size) {}

  /**
   * What the chooser of one launch saw.
   *
   * @param reach the radius it searched
   * @param candidates every object it could choose, in the order of the battle's live list
   * @param refused the objects in its circle the validator refused, not counting those already
   *     struck
   * @param struck the ids it had struck before
   * @param chosen the object it chose, or null for none
   */
  public record Choice(
      int reach,
      List<Candidate> candidates,
      List<WorldEntity> refused,
      List<Integer> struck,
      WorldEntity chosen) {}

  /**
   * The launch of the row's projectile. With HitBiggestTargets it is dropped onto the object the
   * chooser picks, at the row's start height, the object kept as its target and listed so that it
   * is never chosen again, whether the projectile lands or not; without one there is no launch.
   * Without HitBiggestTargets it is dropped onto the area effect's own point at that height, with
   * no target. The projectile's side is the area effect's, and the area effect is its launcher,
   * owner and root, at the area effect's level re-based on the projectile's rarity. It is handed to
   * the holder, which gives it its id at once and admits it at this tick's closing cleanup, so it
   * first flies on the next tick, after every area effect's update.
   *
   * @return false when the chooser found nobody
   */
  private boolean launch(int hit, int bound, int radius) {
    Choice choice = null;
    int sx = x;
    int sy = y;
    WorldEntity target = null;
    if (data.hitBiggestTargets()) {
      choice = choose(radius);
      target = choice.chosen();
      if (target == null) {
        world.areaEffectLaunched(this, hit, bound, choice, null);
        return false;
      }
      struck.add(target.getId());
      sx = target.getView().getX();
      sy = target.getView().getY();
    }
    ProjectileData row = world.getRecords().projectile(data.projectile());
    ProjectileEntity projectile = new ProjectileEntity(world, row, side);
    projectile.launchFromArea(this, target, sx, sy, data.projectileStartHeight(), sx, sy);
    world.launch(projectile);
    world.areaEffectLaunched(this, hit, bound, choice, projectile);
    return true;
  }

  /**
   * The chooser: over the battle's live list, in its order, the character or tower not struck
   * before that the shared validator accepts as the area effect's target - of the other side, the
   * team test skipped for a row for its own troops; not untargetable; passing its own test; with
   * hit points and accepting the area effect as an asker - whose air or ground it reaches and that
   * stands in its circle, with the most hit points and shield; the first of equals stays. Nothing
   * tests whether it is alive, so a unit killed earlier in the step is still a candidate with no
   * hit points left.
   */
  private Choice choose(int radius) {
    List<Integer> before = List.copyOf(struck);
    List<Candidate> candidates = new ArrayList<>();
    List<WorldEntity> refused = new ArrayList<>();
    WorldEntity best = null;
    int most = -1;
    for (BattleEntity live : new ArrayList<>(world.getHolder().entities())) {
      if (!(live instanceof WorldEntity entity) || struck.contains(entity.getId())) {
        continue;
      }
      boolean inCircle = ShapeTests.withinCircleShape(entity.getView(), x, y, radius);
      if (!ReferenceValidator.sharedValidate(
          owner,
          entity.getTargetView(),
          data.onlyOwnTroops(),
          false,
          false,
          true,
          validatorQueries)) {
        if (inCircle) {
          refused.add(entity);
        }
        continue;
      }
      boolean air = entity.getTargetView().air();
      if (!data.hitsAir() && air || !data.hitsGround() && !air || !inCircle) {
        continue;
      }
      HitPoints hitPoints = entity.getHitPoints();
      int size = hitPoints.getHitPoints() + hitPoints.getShield();
      candidates.add(new Candidate(entity, size));
      if (best == null || most < size) {
        best = entity;
        most = size;
      }
    }
    return new Choice(radius, candidates, refused, before, best);
  }

  /**
   * One unit an area effect's hit pulled: the vector from it to the centre, and its push
   * accumulators before and after - the push along each axis, the count, the water clamp asked of
   * the grid move and the lifted cap.
   *
   * @param target the unit pulled
   * @param dx the centre less its position, along the width
   * @param dy the centre less its position, along the length
   * @param before its accumulators before the pull
   * @param after its accumulators after it
   */
  public record Pull(WorldEntity target, int dx, int dy, int[] before, int[] after) {}

  /**
   * The pull of an attracting buff, once per hit and before the buff is applied: every entity of
   * the battle's live list, in order, inside the circle, not a building, that the shared validator
   * accepts as an enemy of the area effect in an area's query, and whose movement is on, is pulled
   * toward the centre. Nothing moves now: the pull waits in the unit's push accumulators for its
   * next movement visit. The area effect never moves, so the angle window of a moving one is not
   * asked.
   */
  private void pull(int radius, BuffData buff) {
    List<Pull> pulls = new ArrayList<>();
    // The live list as it stands, its length read once.
    for (BattleEntity live : new ArrayList<>(world.getHolder().entities())) {
      if (!(live instanceof WorldEntity entity)) {
        continue;
      }
      if (!ShapeTests.withinCircleShape(entity.getView(), x, y, radius)) {
        continue;
      }
      if (entity.getTargetView().building()) {
        continue;
      }
      if (!ReferenceValidator.sharedValidate(
          owner, entity.getTargetView(), false, false, true, false, validatorQueries)) {
        continue;
      }
      if (!(entity instanceof CharacterEntity unit)
          || !unit.isActive(CharacterEntity.MOVEMENT_SLOT)) {
        continue;
      }
      MovementState movement = unit.getUnit().movement();
      int dx = x - unit.getView().getX();
      int dy = y - unit.getView().getY();
      int[] before = accumulators(movement);
      UnitData row = unit.getData();
      BuffPush.push(
          movement,
          dx,
          dy,
          buff.attractPercentage(),
          buff.lateralPushPercentage(),
          buff.pushMassFactor(),
          buff.pushSpeedFactor(),
          unit.getView().getState(),
          row.speed(),
          row.jumpHeight(),
          unit.side(),
          row.mass(),
          row.air(),
          row.hovering());
      pulls.add(new Pull(unit, dx, dy, before, accumulators(movement)));
    }
    world.areaPulled(this, pulls);
  }

  /** A unit's push accumulators: x, y, the count, the water clamp and the lifted cap. */
  private static int[] accumulators(MovementState movement) {
    return new int[] {
      movement.getPushX(),
      movement.getPushY(),
      movement.getPushCount(),
      movement.getPushStuck(),
      movement.getPushUnclamped()
    };
  }

  /**
   * Its age in milliseconds: its lifetime less the countdown as its last update left it, so 0
   * before its first update and 50 more after each.
   */
  int age() {
    return data.lifeDurationMs() - countdown;
  }

  /**
   * Whether its buff may reach a character: one of its own side when the buff is for its own troops
   * only, of the other side when it hits enemies only; alive; not untouchable; not waiting to
   * deploy; not a building when it ignores buildings; and passing its own test of a target, which
   * adds the air and ground it reaches.
   */
  boolean buffReaches(WorldEntity target) {
    boolean sameTeam = ((side & 1) == 0) == ((target.side() & 1) == 0);
    if (!sameTeam && data.onlyOwnTroops()) {
      return false;
    }
    if (sameTeam && data.onlyEnemies()) {
      return false;
    }
    if (!HitPoints.alive(target.getHitPoints())) {
      return false;
    }
    // A hidden unit is passed by first, unless the effect reaches hidden units.
    if (target.passedBy(data.affectsHidden()) || target.untouchable()) {
      return false;
    }
    if (target.getView().getState() == GridEntityState.WAITING_TO_DEPLOY) {
      return false;
    }
    if (target.getData().building() && data.ignoreBuildings()) {
      return false;
    }
    if ((target.getView().getFlags() & EntityFlags.UNTARGETABLE) != 0) {
      return false;
    }
    return accepts(target.getTargetView());
  }

  /** The radius now: the row's, or shrinking from its maximum toward it as the countdown falls. */
  private int radiusNow(int life) {
    int big = data.maxRadius();
    int small = data.radius();
    if (big == 0 || life == 0) {
      return small;
    }
    int t = big - countdown * (big - small) / life;
    return t > small ? Math.min(t, big) : small;
  }

  /** One hit: the area damage around it, with itself as the owner. */
  private void hit(int radius, int damage) {
    int tower = ((Math.max(data.crownTowerDamagePercent(), -100) + 100) * damage + 99) / 100;
    AreaDamage.Area area =
        new AreaDamage.Area(
            x,
            y,
            radius,
            damage,
            tower,
            0,
            data.maximumTargets(),
            !data.onlyEnemies(),
            data.hitsAir(),
            data.hitsGround(),
            data.sharedDamage(),
            data.pushback(),
            x,
            y);
    List<TargetView> entities = new ArrayList<>();
    for (WorldEntity entity : world.present()) {
      entities.add(entity.getTargetView());
    }
    AreaDamage.Outcome outcome =
        AreaDamage.damage(
            owner,
            entities,
            area,
            validatorQueries,
            new AreaDamage.Queries() {
              @Override
              public boolean untouchable(TargetView victim) {
                return world.entityOf(victim.getEntity()).passedBy(data.affectsHidden());
              }

              @Override
              public DamageResult damage(TargetView victim, int amount, int hitId) {
                return world.dealAreaEffectDamage(
                    AreaEffectEntity.this, world.entityOf(victim.getEntity()), amount);
              }

              @Override
              public boolean push(TargetView victim, int fromX, int fromY, int distance) {
                return world.entityOf(victim.getEntity()) instanceof CharacterEntity character
                    && character.pushedByArea(fromX, fromY, distance);
              }
            });
    world.areaEffectDamaged(this, area, outcome);
  }

  @Override
  public boolean isRemovable() {
    return countdown < 1;
  }

  /**
   * What an action row built for the area effect reads from it: its expressions start from the area
   * effect itself, its point and its side.
   */
  public ActionBinding binding() {
    return new AreaEffectBinding(world, this);
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public int side() {
    return side;
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

  @Override
  public ActionHolder actionHolder() {
    return actionHolder;
  }

  @Override
  public EntityActions actions() {
    return actionHolder;
  }

  @Override
  public List<SpawnHost> spawnCharacters(SpawnArguments arguments) {
    return world.spawnCharacters(this, arguments);
  }

  @Override
  public void handOverChampion(SpawnHost child) {
    world.handOverChampion(this, child);
  }

  /** An area effect has no hit points, so it counts as alive. */
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
    return variables.getOrDefault(key, 0);
  }

  @Override
  public void setVariable(int key, int value) {
    variables.put(key, value);
  }

  @Override
  public void killBy(ActionOwner killer) {
    throw new UnsupportedOperationException("killing an area effect is not modelled");
  }

  @Override
  public void queueTypedHit(ActionOwner source, int amount, DamageType type) {
    throw new UnsupportedOperationException("a typed hit on an area effect is not modelled");
  }
}
