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
 * its own point and level on its first update. When the countdown reaches 0 its life-end action is
 * scheduled on itself; it leaves at the cleanup that finds the countdown below 1.
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
            + " boosting one target or lasting longer by level, clones, the hit action, the shape,"
            + " the filter, the spawns, the launches, the life condition, following, tags,"
            + " deflection, a lifetime that grows by level, the push's floor and gate lift and one"
            + " hit per target; hidden units are not modelled. Not created yet by a spell, a projectile"
            + " or an action.")
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
    int hits;
    if (speed >= 1) {
      hits = (data.hitSpeedOffsetMs() + end) / speed - (data.hitSpeedOffsetMs() + start) / speed;
    } else if (speed == 0) {
      hits = start > 0 ? 0 : 1;
    } else {
      hits = 0;
    }
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
    for (int i = 0; i < hits; i++) {
      if (damage >= 1) {
        hit(radius, damage);
      }
      if (data.buff() != null) {
        int time = data.buffTimeMs();
        if (data.capBuffTimeToAreaEffectTime()) {
          time = Math.min(time, countdown + speed);
        }
        if (time >= 1) {
          world.areaBuff(this, radius, time);
        }
      }
    }
    if (countdown <= 0 && data.onLifeTimeEndAction() != null) {
      BattleAction ending = world.getActions().build(data.onLifeTimeEndAction(), binding());
      actionHolder.schedule(ending, ActionHolder.OWN_DELAY, false, actionHolder);
    }
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
    // A hidden unit is passed by first, where an effect that reaches hidden units is refused.
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

  /** What an action row built for the area effect reads from it. */
  public ActionBinding binding() {
    return new RandOnlyBinding(world, name);
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
