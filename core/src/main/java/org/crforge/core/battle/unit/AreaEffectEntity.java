package org.crforge.core.battle.unit;

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
import org.crforge.core.battle.action.AliveTimer;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.CannonProjectileSpawn;
import org.crforge.core.battle.action.DamageType;
import org.crforge.core.battle.action.GhostEvo;
import org.crforge.core.battle.action.GoblinsteinAbility;
import org.crforge.core.battle.action.LaserBall;
import org.crforge.core.battle.action.LaserBallHost;
import org.crforge.core.battle.action.ShapeSelector;
import org.crforge.core.battle.action.ShapeSelectorHost;
import org.crforge.core.battle.action.SpawnGuard;
import org.crforge.core.battle.data.ActionBinding;
import org.crforge.core.battle.filter.GameObjectFilter;
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
import org.crforge.core.pathfinding.index.SpatialIndex;
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
 * its own point and level on its first update. A row whose buff attracts, the Tornado's and the
 * evolved Valkyrie's mini tornado's, pulls every enemy unit in its circle whose movement is on
 * toward its centre with each hit, before the buff, and is the parent of the buff it applies when
 * the buff says so. A row with a projectile launches one after the hits of a step whose hit count
 * rose, onto the enemy with the most hit points and shield in its circle that it has not struck
 * before, or onto its own point; with nobody to strike, the update ends there. A Clone's hit,
 * before any damage, schedules its hit action on every unit of its own side in its circle that the
 * index finds - alive, not hidden, not untouchable, not a building, no unit a Clone passes by and
 * no clone - with itself as the cause, which clones it in the tick's last pending pass. An area
 * effect an action's spawn made keeps the action's cause as its parent, or the holder's owner for a
 * row that takes the owner as the source, forgotten as the parent leaves; its hit action may be a
 * buff spawn, the evolved Tesla's ring's, a group of buff spawns, the Goblin Curse's, or a taunt,
 * the Goblin Demolisher's, scheduled the same way on every unit in its circle it reaches, each unit
 * once for a row that reaches each target once. One whose row follows its parent, or that a
 * projectile's impact made following the projectile's target, stands on the point of the object it
 * follows first thing in each update, moved by the follow offsets a resetable action gave it - the
 * one along the length toward the enemy side of its own side - and its life ends as that object
 * leaves, unless its row stays after its parent dies: it then stands on its last point. A shaped
 * row, the evolved Baby Dragon's wind, lists in each update the characters its filter passes in the
 * rectangle about its point, a building by its square and anything else by its circle, and each hit
 * schedules its hit action, a choice by team, on every one of them with itself as the cause, and
 * does nothing else. When the countdown reaches 0 its life-end action is scheduled on itself; it
 * leaves at the cleanup that finds the countdown below 1.
 *
 * <p>A row with a spawner, created by an ability, makes its characters about its point from its
 * update, after the counters and the radius: one each spawn interval after the initial delay,
 * counted on its row's lifetime, in directions shuffled once by the battle's random source, each
 * placed through the battle; an ability that counts souls gives it a lifetime of its own.
 *
 * <p>Its holder may run a laser ball, Dark Magic's, whose run asks the area effect for the objects
 * around its point, testing buildings by their squares, and schedules what it picks on each of
 * them, built for that object, the area effect as the cause.
 *
 * <p>Its holder may run Goblinstein's ability, on an area effect that follows the doctor: the run
 * connects to the unit the doctor is grouped with, makes a death area where that unit leaves, the
 * area effect its parent, and ends it as the area effect itself leaves.
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
            + " boosting one target or lasting longer by level, a hit action but a Clone's, a"
            + " buff spawn, a group of buff spawns, a taunt or a shaped row's choice by team, a"
            + " shape but a"
            + " rectangle with a filter whose hits only schedule their hit action, a launch from"
            + " its source"
            + " or spread about its point, the life condition, following a target on an area"
            + " effect no projectile's impact made, tags other"
            + " than the one that only hides the pushback's presentation, a"
            + " lifetime that grows by level, the push's floor and gate lift and one hit per"
            + " target without a hit action. Created by a unit's ability at the unit, the unit its"
            + " parent and the object it follows, and the deflection radius it measures"
            + " projectiles against, held by monk_ability_tower and monk_ability_musketeer. A"
            + " lifetime an ability gives it, on which its radius and its spawner's count run"
            + " while its hit schedule and its spawner's clock keep its row's, and its spawner:"
            + " the counts, the order shuffled by the battle's random source, the distance drawn,"
            + " the relocation, the standing test and the retries, each character on its side at"
            + " its level, deploying and a clone, held by skeleton_king_ability_no_souls and"
            + " skeleton_king_ability_souls; a building over the point by BattleSkeletonKingTest;"
            + " staying on its last point as the object it follows leaves, by"
            + " BattleSkeletonKingTest; its age on that lifetime, which no row with a source clock"
            + " reaches, and a limit on its spawns, which no row sets, by no run. A spawner made"
            + " by anything but an ability, or turning its directions by a fixed step, is"
            + " refused. An area that reaches hidden units takes, damages and buffs a hidden"
            + " Tesla, held by tesla_hidden_spells; reaching a unit in its tunnel is refused. The"
            + " pull of an attracting buff before the buff, and the area effect as the parent of"
            + " a buff it controls, held by tornado_group_off_lane and tornado_heavy_light_tower;"
            + " the slot ControlsBuff gates is reached by no path the battle models. The pull of"
            + " an area effect that follows its parent, from where it stands at each update, and a"
            + " projectile's area that follows its target, staying on that target's last point"
            + " when it leaves, held by ice_axe_barbarians and BattleIceSpiritEvoTest; and a"
            + " unit that died earlier in the tick passed over, held by valkyrie_ev1_barbarians;"
            + " a pull with an angle window is refused. The launch of"
            + " its projectile after its hits, one on an update whose hit count rose, the chooser"
            + " of a row with HitBiggestTargets, the start on the target or on its own point, and"
            + " the area effect as the projectile's launcher, held by lightning_defenders_tower and"
            + " royal_delivery_group; the update ending without its life-end action when the"
            + " chooser finds nobody, which no run meets. A target indicator attack's signal at its"
            + " target's point, for the maker's side and level with the maker as parent, ended by"
            + " its attack, held by goblin_machine_knight and goblin_machine_tower; one admitted"
            + " after its maker left, which would be destroyed unread, is refused. A Clone's hit action on the units its"
            + " index query finds, in the query's order, and its filter, held by clone_golem_group."
            + " Its filter's buff test of a clone, the buff row's HealPerSecond, a healing buff"
            + " refusing the clone and any other passing it as any unit, asked by the area damage's"
            + " validator, by the buff's walk and again by its apply, held by clone_rage_group,"
            + " clone_zap_poison_group and clone_heal_spirit_knight; asked by the pull, held by"
            + " BattleCloneTest; by the hit action loop and the chooser, which no shipped row"
            + " with a buff reaches. Its starting action's expressions reading the area effect"
            + " itself, its point and its side, held by graveyard_tower_defender and"
            + " graveyard_right_side1. Created by an action's spawn at the point of the holder's"
            + " owner, for the side and at the level of its cause, the cause kept as its parent,"
            + " and a hit action that is a group of buff spawns, held by goblin_curse_knights; for"
            + " the owner's side and level, the owner its parent, when the row takes the owner as"
            + " the source, held by valkyrie_ev1_barbarians and royal_giant_ev1_knights; a"
            + " growing ring whose hit action spawns a buff, each enemy reached once, a building"
            + " by its square and a crown tower hit for its own damage, held by tesla_ev1_knights"
            + " and BattleBuildingEvoTest;"
            + " such a hit action on an area effect no action made is refused. A taunt as its hit"
            + " action and the following of its parent, held by goblin_demolisher_knight; the end"
            + " as the followed object leaves, held by goblinstein_tower and"
            + " goblinstein_doctor_first; the following of a moving object, held by"
            + " valkyrie_ev1_barbarians; one hit per target over several hits is translated but"
            + " held by no run. Goblinstein's ability run on"
            + " its holder and the death area it makes, its parent the area effect, ended as the"
            + " area effect leaves, held by goblinstein_tower. Its starting and"
            + " life-end actions written inline, a laser ball's run on its holder and the query"
            + " it answers, held by dark_magic_knight and dark_magic_group; a building found by"
            + " its square alone is held by BattleLaserBallTest. The rectangle's list through its"
            + " filter, the hit action on each listed character, the follow offsets turned by its"
            + " side, its life given back and cut by the resetable action that made it, and the"
            + " actions run at its ages, held by baby_dragon_ev1_wind; the offsets of the top side"
            + " and the ages' repeats by BattleUppercutWindTest.")
public final class AreaEffectEntity extends BattleEntity implements ActionOwner, SpawnHost {

  /** Milliseconds one update takes off the countdown. */
  private static final int STEP_MS = 50;

  private final BattleWorld world;

  @Getter private final AreaEffectData data;

  /** The area effect's name in logs, given once its id is known when none is given. */
  private String name;

  private final int side;

  /** Its point along the width; one that follows its parent moves with it at each update. */
  @Getter private int x;

  /** Its point along the length. */
  @Getter private int y;

  /** Its level, packed against its own rarity. */
  @Getter private final int packedLevel;

  /** What is left of its life, in milliseconds; it leaves once this is below 1. */
  @Getter private int countdown;

  /**
   * The lifetime an ability gave it in place of its row's, in milliseconds, or -1 for none. Its hit
   * schedule and its spawner's clock still run on the row's lifetime.
   */
  @Getter private int lifetimeOverride = -1;

  /**
   * The order its spawner takes the directions in, shuffled by the battle's random source on its
   * first update with a spawn due in its life; null before.
   */
  private int[] spawnOrder;

  /**
   * The object an action's spawn made it from, which it keeps as its parent; null for every other
   * area effect, and once that object has left.
   */
  @Getter private SpawnHost parent;

  /**
   * The object it stands on at each update, for a row that follows its parent: the owner of the
   * holder whose action made it; null for none, and once that object has left.
   */
  @Getter private SpawnHost follow;

  /** The ids of the objects its hit action has reached, for a row that reaches each once. */
  private final List<Integer> reached = new ArrayList<>();

  /** True once the area effect its row chains has been created, on its first update. */
  private boolean chained;

  /**
   * The ids of the objects its projectiles were dropped onto, listed as each is chosen, which its
   * chooser passes by.
   */
  private final List<Integer> struck = new ArrayList<>();

  private final ActionHolder actionHolder;

  /**
   * The id of the object that made it as a target indicator attack's signal, which must still be in
   * the battle as the area effect is admitted; -1 for one made otherwise.
   */
  private int creatorId = -1;

  /** The variables its actions write. */
  private final Map<Integer, Integer> variables = new HashMap<>();

  /** Its hits' owner as the validator sees it: an area effect of its side at its point. */
  private final TargetingState owner;

  /** Its own test of a target, which the validator asks of an owner that is not a character. */
  private final ValidatorQueries validatorQueries;

  /**
   * The path that asks its test now, for the observers of the buff test of a clone: "area_damage",
   * "area_buff", "pull", "on_hit_action" or "chooser".
   */
  private String asking;

  /**
   * @param world the battle it belongs to
   * @param data its row
   * @param side its side
   * @param x its point along the width
   * @param y its point along the length
   * @param packedLevel its level, packed against its own rarity
   * @param parent the object an action's spawn made it from, or null
   * @param follow the object it follows, or null
   */
  AreaEffectEntity(
      BattleWorld world,
      AreaEffectData data,
      int side,
      int x,
      int y,
      int packedLevel,
      SpawnHost parent,
      SpawnHost follow) {
    super(KIND_AREA_EFFECT);
    this.world = world;
    this.data = data;
    this.side = side;
    this.x = x;
    this.y = y;
    this.packedLevel = packedLevel;
    this.parent = parent;
    this.follow = follow;
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
   * Its own test of a target: no building for a row that ignores buildings; for a row with a buff,
   * no clone when the buff row's HealPerSecond is 1 or more, its value at level 0, which is the
   * column itself; and a character only when it reaches the character's air or ground.
   */
  private boolean accepts(TargetView target) {
    if (target.building() && data.ignoreBuildings()) {
      return false;
    }
    // The buff test: a row whose buff heals refuses a clone, any other passes it as any unit.
    if (data.buff() != null
        && world.entityOf(target.getEntity()) instanceof CharacterEntity unit
        && unit.isClone()) {
      int query = world.getRecords().buff(data.buff()).healPerSecond();
      boolean refused = query >= 1;
      world.cloneBuffGateAsked(this, data.buff(), unit, asking, query, refused);
      if (refused) {
        return false;
      }
    }
    if (target.getEntity().getType() != ReferenceValidator.TYPE_CHARACTER) {
      return true;
    }
    if (data.hitsAir()) {
      return data.hitsGround() || target.air();
    }
    return !target.air() && data.hitsGround();
  }

  /**
   * Keeps the id of the object that made it, which its admission tests.
   *
   * @param id the maker's id
   */
  void setCreator(int id) {
    creatorId = id;
  }

  /** Ends it: its countdown goes to 0, so the next cleanup after its update removes it. */
  void end() {
    countdown = 0;
  }

  /**
   * Gives it a lifetime of its own in place of its row's, as an ability that counts souls does: its
   * countdown starts again from it.
   *
   * @param lifetimeMs the lifetime, in milliseconds
   */
  void overrideLifetime(int lifetimeMs) {
    lifetimeOverride = lifetimeMs;
    countdown = lifetimeMs;
  }

  /** Its lifetime: the one an ability gave it, or its row's. */
  private int lifetime() {
    return lifetimeOverride >= 0 ? lifetimeOverride : data.lifeDurationMs();
  }

  /**
   * As it is admitted, its row's starting action is scheduled on itself, itself the cause. A signal
   * whose maker left the battle before it was admitted would be destroyed unread, which is refused.
   */
  @Override
  protected void onRegistered() {
    if (creatorId != -1 && world.liveObject(creatorId) == null) {
      throw new UnsupportedOperationException(
          "the area effect "
              + name
              + " is admitted after its maker left, which would destroy it unread, not modelled");
    }
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
    // An area effect that follows stands on the followed object's point, moved by its follow
    // offsets, before anything else.
    if (follow != null) {
      x = followOffsetX + follow.x();
      y = yDirection(side) * followOffsetY + follow.y();
      owner.getOwner().setX(x);
      owner.getOwner().setY(y);
    }
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
    int radius = radiusNow(lifetime());
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
    if (data.spawnCharacter() != null) {
      spawn(start, end, radius);
    }
    if (!chained && data.spawnAreaEffectObject() != null) {
      chained = true;
      world.createAreaEffect(
          data.spawnAreaEffectObject(), x, y, side, packedLevel, null, "chained", name);
    }
    if (data.shaped()) {
      shapeHits(hits);
    } else if (!circleHits(hits, radius, damage, speed, hit, bound)) {
      return;
    }
    if (countdown <= 0 && data.onLifeTimeEndAction() != null) {
      BattleAction ending = world.getActions().build(data.onLifeTimeEndAction(), binding());
      world.lifeTimeEndScheduled(this, ending.name());
      actionHolder.schedule(ending, ActionHolder.OWN_DELAY, false, actionHolder);
    }
  }

  /**
   * The hits of a row without a shape: the hit action on every unit in its circle it reaches, then
   * each hit's damage and buff; then the launch, after the hits: one projectile at most, on a step
   * whose hit count rose.
   *
   * @return false when the launch found nobody to drop its projectile on, which ends the update
   *     without its life-end action
   */
  private boolean circleHits(int hits, int radius, int damage, int speed, int hit, int bound) {
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
    // The launch, after the hits: one projectile at most, on a step whose hit count rose.
    return data.projectile() == null || hit <= bound || launch(hit, bound, radius);
  }

  /**
   * The hits of a shaped row: the objects in its rectangle, centred on its point, that pass its
   * filter, listed once for the update; each hit schedules its hit action on every one of them,
   * built for that object, with the area effect as the cause. The row's load refuses a shaped row
   * whose hits would do more.
   */
  private void shapeHits(int hits) {
    List<WorldEntity> listed =
        world.rectangleQuery(
            this,
            x,
            y,
            half(data.shapeWidth()),
            half(data.shapeHeight()),
            world.getRecords().filter(data.filter()));
    world.shapeListed(this, listed);
    if (data.onHitAction() == null) {
      return;
    }
    for (int i = 0; i < hits; i++) {
      for (WorldEntity target : listed) {
        BattleAction action = world.getActions().build(data.onHitAction(), world.binding(target));
        target.actionHolder().schedule(action, ActionHolder.OWN_DELAY, false, actionHolder);
      }
    }
  }

  /** Half a size, rounded toward zero. */
  private static int half(int size) {
    return size / 2;
  }

  /**
   * The direction along the length a side's area effect moves its follow offset: toward the top for
   * side 0, toward the bottom for the other.
   *
   * @param side the side
   */
  static int yDirection(int side) {
    return (side & 1) == 0 ? 1 : -1;
  }

  /** The offsets it keeps from the object it follows, the one along the length by its side. */
  private int followOffsetX;

  private int followOffsetY;

  /**
   * Sets the offsets it keeps from the object it follows at each update, as the resetable action
   * that made it does.
   *
   * @param offsetX the offset along the width
   * @param offsetY the offset along the length, turned by its side's direction
   */
  void followWithOffsets(int offsetX, int offsetY) {
    followOffsetX = offsetX;
    followOffsetY = offsetY;
  }

  /** Gives it its whole lifetime back. */
  void restartLife() {
    countdown = lifetime();
  }

  /**
   * Cuts what is left of its life.
   *
   * @param countdownMs the countdown it keeps
   */
  void cutLife(int countdownMs) {
    countdown = countdownMs;
  }

  /** Its age, for the actions run at its ages: its lifetime less its countdown. */
  @Override
  public AliveTimer.Age aliveAge() {
    return () -> lifetime() - countdown;
  }

  @Override
  public int actionTeam() {
    return SpatialIndex.team(owner.getOwner());
  }

  @Override
  public void aliveTimerFired(String action) {
    world.aliveTimerFired(this, action);
  }

  /**
   * An object that left the battle: a parent that leaves is forgotten, and an object it follows
   * that leaves ends its life, so the same cleanup removes it, unless its row stays after its
   * parent dies: it then stands on its last point for the rest of its life.
   */
  @Override
  protected void entityRemoved(BattleEntity removed) {
    if (parent == removed) {
      parent = null;
    }
    if (follow == removed) {
      if (!data.stayAfterParentDies()) {
        countdown = 0;
      }
      follow = null;
    }
  }

  /**
   * The spawner, after the update's counters and radius: the characters due between the two elapsed
   * times of the step, each SpawnInterval after the SpawnInitialDelay, both elapsed times on the
   * row's lifetime, at most SpawnMaxCount. The directions are shared out over the spawns its
   * lifetime - an ability's, when it gave one - holds after the delay; their order is shuffled on
   * the first update with at least one in its life, and spawn k takes the direction at k modulo
   * that count. Every spawn of one update takes the direction of the first.
   *
   * @param before the elapsed time at the start of the step
   * @param after the elapsed time at its end
   * @param radius the radius of its hits now, within which the characters are placed
   */
  private void spawn(int before, int after, int radius) {
    int delay = data.spawnInitialDelayMs();
    int interval = data.spawnIntervalMs();
    int top = data.spawnMaxCount();
    int done = Math.max(0, (before - delay) / interval);
    int due = Math.max(0, (after - delay) / interval);
    if (top > 0) {
      done = Math.min(top, done);
      due = Math.min(top, due);
    }
    int total = (lifetime() - delay) / interval;
    if (top != 0) {
      total = Math.min(top, total);
    }
    if (total < 1) {
      return;
    }
    if (spawnOrder == null) {
      spawnOrder = world.spawnOrder(this, total);
    }
    for (int i = 0; i < due - done; i++) {
      int angle = spawnOrder[done % total] * 360 / total;
      world.areaSpawn(this, angle, radius);
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
    asking = "on_hit_action";
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
        if (target.untouchable(true)) {
          continue;
        }
        if (data.oneHitPerTarget() && reached.contains(target.getId())) {
          continue;
        }
        if (!onHitFilter(target)) {
          continue;
        }
        BattleAction action = world.getActions().build(data.onHitAction(), world.binding(target));
        world.onHitActionScheduled(this, target, action);
        target.actionHolder().schedule(action, ActionHolder.OWN_DELAY, false, actionHolder);
        if (data.oneHitPerTarget()) {
          reached.add(target.getId());
        }
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
    asking = "chooser";
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
    asking = "pull";
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
   * Its age in milliseconds: its lifetime - the one an ability gave it, or its row's - less the
   * countdown as its last update left it, so 0 before its first update and 50 more after each.
   */
  int age() {
    return lifetime() - countdown;
  }

  /**
   * Whether its buff may reach a character: one of its own side when the buff is for its own troops
   * only, of the other side when it hits enemies only; alive; not untouchable; not waiting to
   * deploy; not a building when it ignores buildings; and passing its own test of a target, which
   * adds the air and ground it reaches.
   */
  boolean buffReaches(WorldEntity target) {
    asking = "area_buff";
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
  /**
   * The radius a projectile is measured against for a deflection: for a row that deflects
   * projectiles, the radius of its hits as it stands now; 0 for any other.
   */
  public int deflectRadius() {
    return data.deflectsProjectiles() ? radiusNow(lifetime()) : 0;
  }

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
    asking = "area_damage";
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

  /** What a summon run on the area effect asks of the battle: its summon, made on its point. */
  @Override
  public GhostEvo.SummonHost ghostSummonHost() {
    return (row, reference, x, y) ->
        world.ghostSummon(AreaEffectEntity.this, row, (WorldEntity) reference, x, y);
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

  @Override
  public void spawnAreaEffect(String action, String areaEffect, SpawnHost source, int phase) {
    world.spawnAreaEffect(this, action, areaEffect, source, phase);
  }

  /** An area effect has no hit points, so it counts as alive. */
  /**
   * What a shape selector's run on the area effect asks of the battle: the battle tick, the circle
   * around its point that tests buildings by their squares, an object's hit points and shield, and
   * the actions it schedules on what it picked, each built for that object, the area effect its
   * cause.
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
        for (WorldEntity entity : world.shapeQuery(AreaEffectEntity.this, radius, filter)) {
          ids.add(entity.getId());
        }
        return ids;
      }

      @Override
      public int score(int id, int mode) {
        HitPoints hitPoints = ((WorldEntity) world.liveObject(id)).getHitPoints();
        if (hitPoints == null) {
          return 0;
        }
        return mode == ShapeSelector.HIGHEST_CURRENT_HP_INCLUDE_SHIELDS
            ? hitPoints.getHitPoints() + hitPoints.getShield()
            : hitPoints.getHitPoints();
      }

      @Override
      public void schedule(int targetId, String action) {
        WorldEntity target = (WorldEntity) world.liveObject(targetId);
        BattleAction built = world.getActions().build(action, world.binding(target));
        target.actionHolder().schedule(built, ActionHolder.OWN_DELAY, false, actionHolder);
      }

      @Override
      public void selectorStarted(ShapeSelector action, int phase, List<Integer> due) {
        world.selectorStarted(AreaEffectEntity.this, action.name(), phase, due);
      }

      @Override
      public void selectorStepped(ShapeSelector action, ShapeSelector.Step step) {
        world.selectorStepped(AreaEffectEntity.this, action.name(), step);
      }
    };
  }

  /**
   * Makes the run of Goblinstein's ability on the area effect, which must follow its parent: the
   * run connects through the object it follows.
   */
  @Override
  public ActionInstance goblinsteinAbility(GoblinsteinAbility action, int phase) {
    if (!data.followsParent()) {
      throw new UnsupportedOperationException(
          action.name() + " on " + name + ", which follows nothing, is not modelled");
    }
    world.goblinsteinStarted(this, action.name(), phase);
    return new GoblinsteinRun(world, action, this);
  }

  /**
   * What a laser ball's run on the area effect asks of the battle: the object query around its
   * point that tests buildings by their squares, and the actions it schedules on what it found,
   * each built for that object, the area effect its cause.
   */
  @Override
  public LaserBallHost laserBallHost() {
    return new LaserBallHost() {
      @Override
      public List<Integer> detect(int radius, GameObjectFilter filter) {
        List<Integer> ids = new ArrayList<>();
        for (WorldEntity entity : world.shapeQuery(AreaEffectEntity.this, radius, filter)) {
          ids.add(entity.getId());
        }
        return ids;
      }

      @Override
      public void schedule(int targetId, BattleAction action) {
        WorldEntity target = (WorldEntity) world.liveObject(targetId);
        BattleAction built = world.getActions().build(action.name(), world.binding(target));
        target.actionHolder().schedule(built, ActionHolder.OWN_DELAY, false, actionHolder);
      }

      @Override
      public void laserStarted(LaserBall action, int phase, int timerMs) {
        world.laserStarted(AreaEffectEntity.this, action.name(), phase, timerMs);
      }

      @Override
      public void laserFired(
          LaserBall action,
          int count,
          int index,
          List<Integer> targets,
          BattleAction scheduled,
          int timerBefore,
          int timerAfter) {
        List<WorldEntity> objects = new ArrayList<>();
        for (int id : targets) {
          objects.add((WorldEntity) world.liveObject(id));
        }
        world.laserFired(
            AreaEffectEntity.this,
            count,
            index,
            objects,
            scheduled == null ? null : scheduled.name(),
            timerBefore,
            timerAfter);
      }
    };
  }

  /**
   * What a guard-spawning run on the area effect asks of the battle: the guard made behind its
   * point, and the first run's step told.
   */
  @Override
  public SpawnGuard.Maker guardMaker(SpawnGuard action) {
    return new SpawnGuard.Maker() {
      @Override
      public void makeGuard(SpawnGuard guardAction, int phase) {
        world.spawnGuard(AreaEffectEntity.this, guardAction, phase);
      }

      @Override
      public void firstStepped(SpawnGuard guardAction) {
        world.guardFirstStepped(AreaEffectEntity.this, guardAction);
      }
    };
  }

  @Override
  public void cannonBomb(CannonProjectileSpawn action, int phase) {
    world.cannonBomb(this, action, phase);
  }

  @Override
  public ActionOwner areaEffectParent() {
    return parent instanceof ActionOwner owner ? owner : null;
  }

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
