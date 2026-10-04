package org.crforge.core.battle.unit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;
import org.crforge.core.battle.BattleComponent;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.expression.Expression;
import org.crforge.core.battle.expression.ExpressionCompiler;
import org.crforge.core.battle.expression.ExpressionEvaluator;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.combat.LevelScaling;
import org.crforge.core.pathfinding.combat.PackedLevel;
import org.crforge.core.pathfinding.combat.ScalingGlobals;
import org.crforge.core.pathfinding.combat.ScalingMode;
import org.crforge.core.pathfinding.math.FixedMath;
import org.crforge.core.pathfinding.target.TargetingState;

/**
 * The buffs listed on a character or a tower, in slot 3, and what they make of its speeds.
 *
 * <p><b>Apply.</b> A buff reaches an entity from an area effect's hit or a projectile's impact.
 * First the entity's running actions, if it has a holder, see the buff from the last listed to the
 * first, each handed what the one after it answered, and may put another buff in its place; only
 * the evolved Rage Barbarian's ghost wait does. Then a building takes nothing of a buff that
 * ignores buildings, an entity tagged against buffs takes nothing, and a crown tower nothing of one
 * that spares them. Otherwise an instance of the same row is refreshed instead of a second one
 * listed: any instance of the row, or with stacking only the one the same source applied, or for a
 * buff specific to a player only one from the same side. A refresh keeps the longer time, growing
 * the whole by the difference, and the higher level. A buff added as an individual one, as Dark
 * Magic's are, refreshes nothing. With nothing to refresh a new instance is listed last, its level
 * packed against the buff's rarity. A buff with a death spawn that would make another with a death
 * spawn give way is refused.
 *
 * <p><b>Riders.</b> An apply on a parent that carries riders, the Goblin Giant or the Ram, that was
 * not refused ends by handing the buff to each rider in the order they were made: every row but a
 * Clone one, the row it names for riders in its place, with the parent's time, level, source, side
 * and parent. Each rider's apply is its own, under its own refusals, refreshing or listing its own
 * instance, which its own visit steps, so with the same time the instances run out on the same
 * tick; removing the parent's touches none of them. A rider takes a buff in no other way.
 *
 * <p><b>Visit.</b> In the holder tick's pass 3 each instance, from the last to the first, loses 50
 * ms and is removed once its time is 0; a buff with a life condition asks it of the carrier after
 * the step, while the instance has time left, and an answer of 0 spends that time; the damage and
 * the heal over time due on the visit land once it is over, each instance's damage before its heal.
 * An instance counts visits toward its next hit, which comes as the count reaches the hit
 * frequency; for a buff whose hits follow their source, as the Earthquake's do, each visit first
 * sets the count from the age of the area effect that applied it, so every target of one area
 * effect is hit on the same ticks whenever it entered, and two of them hit on their own clocks. The
 * damage is the damage per second at the instance's level, rounded down to a multiple of what one
 * hit can deal, for the period of the hit; a crown tower takes the per-hit column or that share
 * raised by the crown-tower percent, and a building the building percent of it. The heal is the
 * heal per second at the instance's level for the period of the hit, a crown tower's that heal
 * raised by the crown-tower percent.
 *
 * <p><b>Invisibility.</b> Each listed instance of a buff that makes its carrier invisible counts
 * once, from its listing to its removal; the carrier is invisible while the count is 1 or more.
 * Each instance of a buff that locks the carrier's reference counts the same way, into the count
 * its selector reads: while it is 1 or more and the carrier holds a reference, the selector keeps
 * it.
 *
 * <p><b>Parents.</b> An instance keeps the parent it was applied with only for a buff that stacks:
 * an area effect applies a buff its parent controls, the Tornado's, with itself as the parent. An
 * instance with the same parent already listed, of any row, stops a new one from being listed. The
 * visit removes an instance whose parent is removable as it removes one whose time has run out, and
 * a parent that leaves the battle removes its instances at once, where a source that leaves is only
 * forgotten. The not-attacking section removes only the instances of its row without a parent.
 *
 * <p><b>Hooks.</b> A new instance schedules its row's start action as it is listed, a refresh
 * nothing; every removal - the expiry, the not-attacking section's, the stun cleanse, a parent
 * leaving - schedules its row's remove action before the counts drop, and a death removes nothing.
 * Each goes on the carrier's own holder with the carrier as its cause and the row's own delay,
 * starting at once only inside that holder's own pending pass. A clone's copy of an instance with
 * either is refused.
 *
 * <p><b>Tags.</b> The tags every listed instance's row sets join the carrier's tag word at its
 * pre-hook, from the one after the instance is listed to the last before it is removed.
 *
 * <p><b>Scales.</b> The speed, the attack time step and the spawn time step each take the largest
 * boost of the listed rows, from 100, times what the largest slow leaves of 100: Rage makes a step
 * of 50 one of 65, and a stun of -100 makes it 0.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Held by rage_knight, zap_knight, poison_knight_tower and snowball_knights, the last"
            + " with a projectile as the source: the apply with its refresh by"
            + " row, by source under stacking, the new instance and its level; the visit in pass"
            + " 3, the 50 ms step and the removal, the damage over time on a unit and a crown"
            + " tower; the speed, hit speed and spawn speed scales. Held by"
            + " battle_healer_knights and ghost_river_wizard_tower: the heal over time on a unit,"
            + " the invisible count and the removal of a row's instances. Held by"
            + " earthquake_barbarians_tower and earthquake_tesla_overlap: hits that follow their"
            + " source area effect's age, one instance per source, a building's damage percent"
            + " and the damage over time on a hidden Tesla, and the source forgotten as its area"
            + " effect leaves. Held by dark_magic_knight and dark_magic_group: a 100 ms buff's one"
            + " hit on its second visit and the per-hit crown tower column. Held by"
            + " BattleLaserBallTest alone: a buff added as an individual one, which refreshes"
            + " nothing. Translated but held by no run: a player-specific refresh, a crown"
            + " tower's heal and a negative hit frequency. A death spawn is left by the dying carrier (see the battle's death slot),"
            + " held by witch_mother_skeletons; one giving way to another is refused. Held by"
            + " parent_buff_goblin_giant and parent_buff_ram_rider_rage: a buff on a parent"
            + " handed to each rider, made and refreshed, as an instance of the rider's own that"
            + " its own visit steps; by RiderTest: a row named for riders handed in its place, a"
            + " Clone row kept from them, a rider's own refusal and the parent's removal leaving"
            + " the riders' instances. A buff applied to a rider other than through its parent is"
            + " refused. Refused by"
            + " the row: projectiles, chains, morphs, an action other than a start or"
            + " remove action that names its row, tags other than the one"
            + " that keeps enemies from pushing the carrier, switching team,"
            + " shields, hit point and damage multipliers, and an action on a reduction. The"
            + " damage reduction, the largest at or above 0 and the smallest at or below 0 under"
            + " the protection cap, at the hit-points entry, held by monk_ability_tower,"
            + " monk_ability_musketeer, knight_ev1_tower_knight and knight_ev1_fireball_valkyrie;"
            + " two reducing buffs on one carrier by BuffComponentTest; on the damage over time,"
            + " the pending damage's lethal test"
            + " and the refusal of a push, by BattleMonkTest; on a typed hit by no run. Held by"
            + " tornado_group_off_lane and tornado_heavy_light_tower: the parent an instance"
            + " keeps, a refresh keeping the damage counter, and the removal of a parent's"
            + " instances as it leaves. Translated but held by no run: an instance with the same"
            + " parent stopping a new one, the removal by a parent that is removable at the visit,"
            + " and the not-attacking section sparing an instance with a parent. A buff that"
            + " locks its carrier's reference is counted for the selector while it is listed;"
            + " goblin_demolisher_knight lists one, but no run reaches a selection it changes."
            + " Held by little_prince_giant and little_prince_retarget: a buff's life condition,"
            + " asked of the carrier after the step of an instance with time left, an answer of 0"
            + " ending it on that visit; BuffComponentTest holds that it is not asked of an"
            + " instance its step spends or of one that never runs out. The tags of the listed"
            + " rows in the carrier's tag word from its next pre-hook while they are listed, held"
            + " by valkyrie_ev1_barbarians and BattleAttackActionTest. The start action on a new"
            + " instance and the remove action on a removal, held by buff_after_hits_ghost_evo"
            + " and buff_after_hits_barbarians_bats; none on a refresh, the cleanse's and none at"
            + " a death by BattleBuffAfterHitsTest. Held by evo_skeletons_vs_musketeer: an"
            + " instance's spawner, one child in front of its carrier on the instance's first"
            + " visit, linked into its carrier's group chain; translated but held by no run: an"
            + " interval, a wave and a pause past the first firing; refused: a firing with the"
            + " chain at the group's limit, a child released at the fold, a tower or building"
            + " carrier.")
public final class BuffComponent implements BattleComponent {

  /** The slot of the buff component on every character and tower. */
  public static final int SLOT = 3;

  /** The hit speed multiplier at or below which a buff stops its carrier's attacks: a stun. */
  private static final int STUN_HIT_SPEED_MULTIPLIER = -100;

  /** Milliseconds one visit takes off each instance. */
  private static final int STEP_MS = 50;

  /** The tag under which an entity takes no buff. */
  private static final long NO_BUFFS = 1L << 16;

  /** The tag under which an entity's spawner time does not step. */
  private static final long NO_SPAWNTIMER = 1L << 13;

  private static final int PERCENT = 100;

  private final WorldEntity entity;
  private final BattleWorld world;

  /** The listed instances, oldest first. */
  private final List<BuffInstance> items = new ArrayList<>();

  /** How many listed instances make the carrier invisible. */
  private int invisibleCount;

  /** The start and remove actions of the rows listed so far, built for the carrier, by name. */
  private final Map<String, BattleAction> hookRows = new HashMap<>();

  /** The life conditions of the rows listed so far, compiled for the carrier, by their text. */
  private final Map<String, Expression> lifeConditions = new HashMap<>();

  BuffComponent(WorldEntity entity, BattleWorld world) {
    this.entity = entity;
    this.world = world;
  }

  @Override
  public int index() {
    return SLOT;
  }

  /**
   * Whether an instance of the named buff row is listed, as the priority rule of a row that ranks
   * that buff's carriers lower asks.
   *
   * @param buff the buff row's name
   */
  public boolean carries(String buff) {
    for (BuffInstance instance : items) {
      if (instance.getBuff().name().equals(buff)) {
        return true;
      }
    }
    return false;
  }

  /** How many listed instances make the carrier invisible; it is invisible at 1 or more. */
  public int invisibleCount() {
    return invisibleCount;
  }

  /** The tags every listed instance's row sets, together: the carrier's pre-hook adds them. */
  public long tags() {
    long tags = 0;
    for (BuffInstance instance : items) {
      tags |= instance.getBuff().gameTagsToSet();
    }
    return tags;
  }

  /** The listed instances, oldest first. */
  public List<BuffInstance> items() {
    return Collections.unmodifiableList(items);
  }

  /**
   * Applies a buff to the entity: refreshes the instances it matches, or lists a new one; a buff
   * added as an individual one matches none.
   *
   * @param buff the buff's row
   * @param time how long it lasts, in milliseconds
   * @param packedLevel the level it is applied at, packed against the source's rarity
   * @param source what applies it
   * @param side the side it is applied for
   */
  void apply(BuffData buff, int time, int packedLevel, SpawnHost source, int side) {
    apply(buff, time, packedLevel, source, side, null);
  }

  /**
   * Applies a buff to the entity with a parent: refreshes the instances it matches, or lists a new
   * one, unless an instance with the same parent is listed already.
   *
   * @param buff the buff's row
   * @param time how long it lasts, in milliseconds
   * @param packedLevel the level it is applied at, packed against the source's rarity
   * @param source what applies it
   * @param side the side it is applied for
   * @param parent the entity whose removal removes the instance, kept only for a buff that stacks;
   *     null for none
   */
  void apply(
      BuffData buff, int time, int packedLevel, SpawnHost source, int side, BattleEntity parent) {
    apply(buff, time, packedLevel, source, side, parent, false);
  }

  /**
   * The apply, from a source or from a parent handing the buff to this rider.
   *
   * @param handedOver true when a parent hands the buff to this rider
   */
  private void apply(
      BuffData buff,
      int time,
      int packedLevel,
      SpawnHost source,
      int side,
      BattleEntity parent,
      boolean handedOver) {
    // The entity's running actions see the buff before any gate, from the last listed to the
    // first, and may put another buff in its place.
    ActionHolder holder = entity.madeActionHolder();
    if (holder != null) {
      String offered = holder.offerBuff(buff.name());
      if (!offered.equals(buff.name())) {
        buff = world.getRecords().buff(offered);
      }
    }
    if (entity.getTargetView().building() && buff.ignoreBuildings()) {
      return;
    }
    if ((entity.getView().getFlags() & NO_BUFFS) != 0) {
      return;
    }
    // A rider takes a buff only from its parent: an area effect's buff test refuses it, and a hit's
    // buff goes to the parent.
    if (!handedOver && entity instanceof CharacterEntity c && c.getParent() != null) {
      throw new UnsupportedOperationException(
          buff.name()
              + " is applied to "
              + entity.name()
              + ", a rider, other than through its parent, which is not modelled");
    }
    // A carrier of a buff some character passes over, rather than ranks lower, is not modelled:
    // the validator would read it.
    if (world.passedOverBySomeone(buff.name())) {
      throw new UnsupportedOperationException(
          buff.name() + " is a buff a character's targeting reads, which is not modelled");
    }
    if (buff.noEffectToCrownTowers() && entity.getTargetView().crownTower()) {
      return;
    }
    if (entity.getData().ignoreBuffs().contains(buff.name())) {
      return;
    }
    int level = PackedLevel.pack(packedLevel, buff.rarity());
    boolean create = true;
    // A buff added as an individual one matches nothing: every application lists a new instance.
    for (int i = items.size() - 1; i >= 0 && !buff.addAsIndividualBuff(); i--) {
      BuffInstance instance = items.get(i);
      boolean same = instance.getBuff().name().equals(buff.name());
      boolean refresh;
      if (buff.enableStacking()) {
        refresh = same && instance.getSource() == source;
      } else if (!same) {
        refresh = false;
      } else if (!buff.playerSpecificBuff()) {
        refresh = true;
      } else {
        refresh = source != null && instance.getSide() == source.side();
      }
      if (refresh) {
        int before = instance.getRemaining();
        int totalBefore = instance.getTotal();
        int levelBefore = instance.getPackedLevel();
        instance.refresh(time, level);
        if (instance.getRemaining() != before
            || instance.getTotal() != totalBefore
            || instance.getPackedLevel() != levelBefore) {
          world.buffRefreshed(entity, instance, before, source);
        }
        create = false;
      }
    }
    // A buff with a death spawn removes every listed one with a death spawn, unless both allow
    // another; no run holds the removal.
    if (buff.deathSpawn() != null) {
      for (BuffInstance instance : items) {
        BuffData old = instance.getBuff();
        if (old.deathSpawn() != null
            && !(old.otherBuffDeathSpawnAllowed() && buff.otherBuffDeathSpawnAllowed())) {
          throw new UnsupportedOperationException(
              buff.name()
                  + " would remove "
                  + old.name()
                  + " from "
                  + entity.name()
                  + ", one death spawn buff giving way to another, which is not modelled");
        }
      }
    }
    // An instance with the same parent already listed, of any row, stops a new one.
    if (parent != null) {
      for (BuffInstance instance : items) {
        if (instance.getParent() == parent) {
          create = false;
          break;
        }
      }
    }
    if (create) {
      BuffInstance instance =
          new BuffInstance(world.nextBuffKey(), buff, time, level, source, side, parent);
      items.add(instance);
      onListed(instance);
      // A buff that gives a charge range resets the charge, now that it is listed.
      if (buff.overrideChargeRange() != 0 && entity instanceof CharacterEntity character) {
        character.buffChargeReset(instance);
      }
      hook(instance, instance.getBuff().onStartAction(), true);
      world.buffApplied(entity, instance);
    }
    handOver(buff, time, packedLevel, source, side, parent);
  }

  /**
   * The end of an apply on a parent that carries riders: every buff but a Clone one is handed to
   * each rider in the order they were made, the row it names for riders in its place, with the
   * apply's own time, level, source, side and parent. Each rider's apply is its own: its refusals,
   * its refresh or its own instance, which its own visit steps; removing the parent's instance
   * touches none of them.
   */
  private void handOver(
      BuffData buff, int time, int packedLevel, SpawnHost source, int side, BattleEntity parent) {
    if (!(entity instanceof CharacterEntity carrier)
        || !carrier.getData().spawnAttach()
        || buff.cloneBuff()) {
      return;
    }
    BuffData handed =
        buff.attachedInheritAs() == null ? buff : world.buffData(buff.attachedInheritAs());
    for (CharacterEntity rider : List.copyOf(carrier.riders())) {
      BuffComponent buffs = rider.getBuffs();
      Set<String> held = new HashSet<>();
      for (BuffInstance instance : buffs.items) {
        held.add(instance.getKey());
      }
      buffs.apply(handed, time, packedLevel, source, side, parent, true);
      List<BuffInstance> instances = new ArrayList<>();
      for (BuffInstance instance : buffs.items) {
        if (instance.getBuff().name().equals(handed.name())) {
          instances.add(instance);
        }
      }
      world.buffHandedOver(carrier, rider, handed, time, packedLevel, source, instances, held);
    }
  }

  /**
   * Lists a copy of each instance another entity's component lists, in its order, as a clone takes
   * its original's buffs: each with the time it has left. The component must list nothing yet.
   *
   * @param original the original's component
   */
  void copyFrom(BuffComponent original) {
    if (!items.isEmpty()) {
      throw new UnsupportedOperationException(
          entity.name() + " takes a copy of buffs while it carries some, which is not modelled");
    }
    // The clone creator leaves a buff that is not cloned off the clone, which no reference holds,
    // and whether a copy runs a buff's start action is not established.
    for (BuffInstance instance : original.items) {
      if (instance.getBuff().onStartAction() != null
          || instance.getBuff().onRemoveAction() != null) {
        throw new UnsupportedOperationException(
            original.entity.name()
                + " is cloned carrying "
                + instance.getBuff().name()
                + ", which runs an action as it is listed or removed, not modelled");
      }
      if (instance.getBuff().overrideChargeRange() != 0) {
        throw new UnsupportedOperationException(
            original.entity.name()
                + " is cloned carrying "
                + instance.getBuff().name()
                + ", which gives a charge range, not modelled");
      }
      if (instance.getBuff().spawnObject() != null) {
        throw new UnsupportedOperationException(
            original.entity.name()
                + " is cloned carrying "
                + instance.getBuff().name()
                + ", whose spawner's copy is not modelled");
      }
      if (instance.getBuff().notCloned()) {
        throw new UnsupportedOperationException(
            original.entity.name()
                + " is cloned carrying "
                + instance.getBuff().name()
                + ", which is not cloned, not modelled");
      }
    }
    for (BuffInstance instance : original.items) {
      BuffInstance copy = instance.copy(world.nextBuffKey());
      items.add(copy);
      onListed(copy);
      world.buffCopied(original.entity, entity, copy);
    }
  }

  /**
   * Removes every listed instance of a row without a parent, from the last to the first, as the
   * not-attacking section asks for its buff's instances.
   *
   * @param buff the buff row's name
   */
  void removeRow(String buff) {
    for (int i = items.size() - 1; i >= 0; i--) {
      BuffInstance instance = items.get(i);
      if (instance.getBuff().name().equals(buff) && instance.getParent() == null) {
        items.remove(i);
        onRemoved(instance);
        world.buffRemoved(entity, instance);
      }
    }
  }

  /**
   * The stun cleanse: every listed instance whose buff stops its carrier's attacks - a hit speed
   * multiplier of -100 or below - removed, from the last to the first.
   *
   * @return the removed instances' buffs, in removal order
   */
  List<String> cleanseStuns() {
    List<String> removed = new ArrayList<>();
    for (int i = items.size() - 1; i >= 0; i--) {
      BuffInstance instance = items.get(i);
      if (instance.getBuff().hitSpeedMultiplier() <= STUN_HIT_SPEED_MULTIPLIER) {
        items.remove(i);
        onRemoved(instance);
        world.buffRemoved(entity, instance);
        removed.add(instance.getBuff().name());
      }
    }
    return removed;
  }

  /**
   * What listing an instance does at once: its share of the invisible count, and of the count of
   * instances that lock the carrier's reference, which its selector reads.
   */
  private void onListed(BuffInstance instance) {
    if (instance.getBuff().invisible()) {
      invisibleCount++;
    }
    if (instance.getBuff().lockTarget()) {
      TargetingState targeting = entity.getTargeting();
      targeting.setTargetLockingBuffs(targeting.getTargetLockingBuffs() + 1);
    }
  }

  /**
   * What the removal of an instance undoes at once: its share of the invisible count and of the
   * locking count, and its parent.
   */
  private void onRemoved(BuffInstance instance) {
    // The removal resets the charge again, with the instance taken out or not: no reference holds
    // which.
    if (instance.getBuff().overrideChargeRange() != 0) {
      throw new UnsupportedOperationException(
          entity.name()
              + " loses "
              + instance.getBuff().name()
              + ", which gives a charge range, not modelled");
    }
    hook(instance, instance.getBuff().onRemoveAction(), false);
    if (instance.getBuff().invisible()) {
      invisibleCount--;
    }
    if (instance.getBuff().lockTarget()) {
      TargetingState targeting = entity.getTargeting();
      targeting.setTargetLockingBuffs(targeting.getTargetLockingBuffs() - 1);
    }
    instance.forgetParent();
  }

  /**
   * A buff's start action, as a new instance is listed, or its remove action, as one is removed:
   * scheduled on the carrier's own holder with the carrier as its cause and the row's own delay,
   * starting at once only inside that holder's own pending pass. A refresh lists nothing and a
   * death removes nothing, so neither runs one.
   *
   * @param instance the instance listed or removed
   * @param action the row, or null for none
   * @param start true for the start action
   */
  private void hook(BuffInstance instance, String action, boolean start) {
    if (action == null) {
      return;
    }
    BattleAction row =
        hookRows.computeIfAbsent(
            action, name -> world.getActions().build(name, world.binding(entity)));
    world.buffHookScheduled(entity, instance, action, start);
    entity.actionHolder().scheduleInOwnPass(row, entity.actionHolder());
  }

  /**
   * The visit: each instance, from the last listed to the first, steps its time and deals its
   * damage over time when one is due, and is removed once its time runs out. The removals are
   * reported as the visit ends.
   */
  @Override
  public void visit() {
    List<BuffInstance> snapshot = new ArrayList<>(items);
    List<BuffInstance> removed = new ArrayList<>();
    List<BuffInstance> hitting = new ArrayList<>();
    List<Integer> periods = new ArrayList<>();
    for (int k = snapshot.size() - 1; k >= 0; k--) {
      BuffInstance instance = snapshot.get(k);
      instance.step(STEP_MS);
      askLifeCondition(instance);
      followSource(instance);
      // The spawner comes after the source's clock and before the damage over time
      // (0xe2c6f4..0xe2c820); its child is made at once, inside the visit.
      if (instance.stepSpawner(spawnRate())) {
        world.buffSpawn(entity, instance);
        instance.spawnerFired();
      }
      int period = instance.countHit(STEP_MS);
      if (period != 0) {
        hitting.add(instance);
        periods.add(period);
      }
      if (instance.finished() && items.remove(instance)) {
        onRemoved(instance);
        removed.add(instance);
      }
    }
    for (BuffInstance instance : removed) {
      world.buffRemoved(entity, instance);
    }
    // The hits the visit found due land once it is over, in the order it found them.
    for (int i = 0; i < hitting.size(); i++) {
      overTime(hitting.get(i), periods.get(i));
    }
  }

  /**
   * A buff's life condition, asked of the carrier as it stands after the step of an instance's
   * time, when the instance runs out and has time left: an answer of 0 spends its time, and the
   * visit removes it. Each condition is compiled once for the carrier.
   */
  private void askLifeCondition(BuffInstance instance) {
    String condition = instance.getBuff().aliveIfTrue();
    if (condition == null || !instance.asksLifeCondition()) {
      return;
    }
    BattleExpressionEnvironment environment = new BattleExpressionEnvironment(entity, world);
    Expression expression =
        lifeConditions.computeIfAbsent(
            condition, text -> ExpressionCompiler.compile(text, environment));
    int answer = ExpressionEvaluator.evaluate(expression, environment);
    world.lifeConditionAsked(entity, instance, answer);
    if (answer == 0) {
      instance.expire();
    }
  }

  /**
   * For a buff whose hits follow their source, sets the instance's count toward its next hit from
   * the age of the area effect that applied it, after the step of its time; an instance that never
   * runs out, or whose source has left, counts on as a plain one.
   */
  private void followSource(BuffInstance instance) {
    if (!instance.getBuff().hitTickFromSource()
        || instance.getSource() == null
        || instance.getRemaining() == BuffInstance.FOREVER) {
      return;
    }
    // Only an area effect applies the one row that follows its source; the source is read as one.
    if (!(instance.getSource() instanceof AreaEffectEntity areaEffect)) {
      throw new UnsupportedOperationException(
          instance.getBuff().name()
              + " follows the clock of a source that is not an area effect, which is not modelled");
    }
    instance.followSource(areaEffect.age(), STEP_MS);
  }

  /**
   * One hit of an instance's damage and heal over time, for the period it covers: the damage first,
   * then the heal. The heal is dropped for a unit whose Kamikaze hit has landed; the battle
   * destroys such a unit at the end of that hit, so none is left to take one.
   */
  private void overTime(BuffInstance instance, int period) {
    BuffData buff = instance.getBuff();
    int level = instance.getPackedLevel();
    int damage;
    int heal;
    if (entity.getTargetView().crownTower()) {
      damage = crownTowerDamage(buff, level);
      heal = crownTowerHeal(buff, level);
    } else {
      damage = damagePerSecond(buff, level) * period / 1000;
      heal = atLevel(buff, buff.healPerSecond(), level) * period / 1000;
      if (entity.getTargetView().building() && buff.buildingDamagePercent() != 0) {
        damage = buff.buildingDamagePercent() * damage / PERCENT;
      }
    }
    // The damage goes through the carrier's own damage reduction first.
    if (damage >= 1) {
      damage = damageReduction(damage);
      if (entity.getHitPoints() != null) {
        world.dealBuffDamage(entity, instance, damage);
      }
    }
    if (heal >= 1 && entity.getHitPoints() != null) {
      world.dealBuffHeal(entity, instance, heal);
    }
  }

  /** A crown tower's heal per hit: the heal per second at the level raised by the percent. */
  static int crownTowerHeal(BuffData buff, int packedLevel) {
    int value = atLevel(buff, buff.healPerSecond(), packedLevel);
    int percent = Math.max(buff.crownTowerDamagePercent(), -100) + 100;
    return (percent * value + 99) / 100;
  }

  /**
   * The damage per second at a level, rounded down to a multiple of what one hit deals when the hit
   * frequency is up to a second.
   */
  static int damagePerSecond(BuffData buff, int packedLevel) {
    int value = atLevel(buff, buff.damagePerSecond(), packedLevel);
    int frequency = buff.hitFrequency();
    if (frequency >= 1 && frequency <= 1000) {
      int step = 1000 / (frequency & 0xffff);
      value = value / step * step;
    }
    return value;
  }

  /**
   * A crown tower's damage per hit: the per-hit column at the level, or the rounded damage per
   * second raised by the crown-tower percent to a whole percent, for one hit frequency.
   */
  static int crownTowerDamage(BuffData buff, int packedLevel) {
    if (buff.crownTowerDamagePerHit() >= 1) {
      return atLevel(buff, buff.crownTowerDamagePerHit(), packedLevel);
    }
    int value = damagePerSecond(buff, packedLevel);
    int percent = Math.max(buff.crownTowerDamagePercent(), -100) + 100;
    value = (percent * value + 99) / 100;
    return value * Math.max(buff.hitFrequency(), 0) / 1000;
  }

  private static int atLevel(BuffData buff, int value, int packedLevel) {
    return LevelScaling.scale(
        ScalingGlobals.standard(), value, packedLevel, ScalingMode.CARD_DAMAGE, buff.rarity());
  }

  /**
   * An entity that left the battle: from the last instance to the first, one it is the parent of is
   * removed, and one it applied otherwise forgets it.
   */
  void entityRemoved(BattleEntity removed) {
    for (int i = items.size() - 1; i >= 0; i--) {
      BuffInstance instance = items.get(i);
      if (instance.getParent() == removed) {
        items.remove(i);
        onRemoved(instance);
        world.buffRemoved(entity, instance);
      } else if (instance.getSource() == removed) {
        instance.forgetSource();
      }
    }
  }

  /**
   * The damage reduction: an amount that reaches the carrier, less the percent its listed buffs
   * take off - the largest DamageReduction at or above 0 plus the smallest at or below 0, held
   * within the protection cap either way - truncated. A negative percent raises the amount. Asked
   * at the hit-points entry, by the pending damage's lethal test, by a typed hit that the target's
   * protection lowers and by a buff's damage over time; a row that exempts a buff's own damage is
   * refused with its row.
   *
   * @param amount the amount that reaches the carrier
   * @return the amount after the reduction
   */
  public int damageReduction(int amount) {
    int high = 0;
    int low = 0;
    for (BuffInstance instance : items) {
      int value = instance.getBuff().damageReduction();
      high = Math.max(high, value);
      low = Math.min(low, value);
    }
    int total = high + low;
    if (total == 0) {
      return amount;
    }
    int cap = world.protectionCapPercent();
    int percent = Math.max(Math.min(total, cap), -cap);
    return (PERCENT - percent) * amount / PERCENT;
  }

  /** Whether a listed buff keeps its carrier from being pushed back. */
  public boolean ignoresPushBack() {
    for (BuffInstance instance : items) {
      if (instance.getBuff().ignorePushBack()) {
        return true;
      }
    }
    return false;
  }

  /** The movement speed a base speed scales to. */
  public int speed(int base) {
    return scale(BuffData::speedMultiplier, base);
  }

  /** The attack time step a base step scales to; 0 under a stun. */
  public int hitSpeed(int base) {
    return scale(BuffData::hitSpeedMultiplier, base);
  }

  /** A spawn time step a base step scales to, as a Goblin Hut's life state advances its timer. */
  public int spawnSpeed(int base) {
    return scale(BuffData::spawnSpeedMultiplier, base);
  }

  /**
   * The charge range the listed buffs give their carrier: the first listed instance's whose row
   * sets one, whatever comes after it; 0 for none.
   */
  public int overrideChargeRange() {
    for (BuffInstance instance : items) {
      if (instance.getBuff().overrideChargeRange() != 0) {
        return instance.getBuff().overrideChargeRange();
      }
    }
    return 0;
  }

  /** The speed percents of the listed rows, which the speed budget scales by. */
  public int[] speedPercents() {
    return items.stream().mapToInt(i -> i.getBuff().speedMultiplier()).toArray();
  }

  /**
   * The spawn time percent: 100 without a buff, 0 under the tag that holds a spawner's time,
   * otherwise what the largest slow leaves of the largest boost.
   */
  public int spawnRate() {
    if ((entity.getView().getFlags() & NO_SPAWNTIMER) != 0) {
      return 0;
    }
    int[] extremes = extremes(BuffData::spawnSpeedMultiplier);
    return FixedMath.divOrZero(keep(extremes[1]) * extremes[0], PERCENT);
  }

  /** The largest boost times what the largest slow leaves, each division truncating. */
  private int scale(ToIntFunction<BuffData> column, int base) {
    int[] extremes = extremes(column);
    return FixedMath.divOrZero(
        FixedMath.divOrZero(extremes[0] * base, PERCENT) * keep(extremes[1]), PERCENT);
  }

  /** The largest value of at least 1, from 100, and the largest magnitude of a negative one. */
  private int[] extremes(ToIntFunction<BuffData> column) {
    int boost = PERCENT;
    int slow = 0;
    for (BuffInstance instance : items) {
      int value = column.applyAsInt(instance.getBuff());
      if (value >= 1) {
        boost = Math.max(boost, value);
      } else if (value < 0) {
        slow = Math.max(slow, -value);
      }
    }
    return new int[] {boost, slow};
  }

  private static int keep(int slow) {
    return Math.max(Math.min(PERCENT - slow, PERCENT), 0);
  }
}
