package org.crforge.core.battle.unit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.ToIntFunction;
import org.crforge.core.battle.BattleComponent;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.combat.LevelScaling;
import org.crforge.core.pathfinding.combat.PackedLevel;
import org.crforge.core.pathfinding.combat.ScalingGlobals;
import org.crforge.core.pathfinding.combat.ScalingMode;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * The buffs listed on a character or a tower, in slot 3, and what they make of its speeds.
 *
 * <p><b>Apply.</b> A buff reaches an entity from an area effect's hit or a projectile's impact. A
 * building takes nothing of a buff that ignores buildings, an entity tagged against buffs takes
 * nothing, and a crown tower nothing of one that spares them. Otherwise an instance of the same row
 * is refreshed instead of a second one listed: any instance of the row, or with stacking only the
 * one the same source applied, or for a buff specific to a player only one from the same side. A
 * refresh keeps the longer time, growing the whole by the difference, and the higher level. With
 * nothing to refresh a new instance is listed last, its level packed against the buff's rarity. A
 * buff with a death spawn that would make another with a death spawn give way is refused.
 *
 * <p><b>Visit.</b> In the holder tick's pass 3 each instance, from the last to the first, loses 50
 * ms and is removed once its time is 0; the damage and the heal over time due on the visit land
 * once it is over, each instance's damage before its heal. An instance counts visits toward its
 * next hit, which comes as the count reaches the hit frequency; for a buff whose hits follow their
 * source, as the Earthquake's do, each visit first sets the count from the age of the area effect
 * that applied it, so every target of one area effect is hit on the same ticks whenever it entered,
 * and two of them hit on their own clocks. The damage is the damage per second at the instance's
 * level, rounded down to a multiple of what one hit can deal, for the period of the hit; a crown
 * tower takes the per-hit column or that share raised by the crown-tower percent, and a building
 * the building percent of it. The heal is the heal per second at the instance's level for the
 * period of the hit, a crown tower's that heal raised by the crown-tower percent.
 *
 * <p><b>Invisibility.</b> Each listed instance of a buff that makes its carrier invisible counts
 * once, from its listing to its removal; the carrier is invisible while the count is 1 or more.
 *
 * <p><b>Parents.</b> An instance keeps the parent it was applied with only for a buff that stacks:
 * an area effect applies a buff its parent controls, the Tornado's, with itself as the parent. An
 * instance with the same parent already listed, of any row, stops a new one from being listed. The
 * visit removes an instance whose parent is removable as it removes one whose time has run out, and
 * a parent that leaves the battle removes its instances at once, where a source that leaves is only
 * forgotten. The not-attacking section removes only the instances of its row without a parent.
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
            + " effect leaves. Translated but held by"
            + " no run: a player-specific refresh, the per-hit crown"
            + " tower column, a crown tower's heal and a negative hit frequency. A death spawn is left by the dying carrier (see the battle's death slot),"
            + " held by witch_mother_skeletons; one giving way to another is refused. Refused by"
            + " the row: projectiles, chains, spawns, morphs, actions, tags, switching team,"
            + " shields, hit point and damage multipliers and damage reduction. Held by"
            + " tornado_group_off_lane and tornado_heavy_light_tower: the parent an instance"
            + " keeps, a refresh keeping the damage counter, and the removal of a parent's"
            + " instances as it leaves. Translated but held by no run: an instance with the same"
            + " parent stopping a new one, the removal by a parent that is removable at the visit,"
            + " and the not-attacking section sparing an instance with a parent.")
public final class BuffComponent implements BattleComponent {

  /** The slot of the buff component on every character and tower. */
  public static final int SLOT = 3;

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

  /** The listed instances, oldest first. */
  public List<BuffInstance> items() {
    return Collections.unmodifiableList(items);
  }

  /**
   * Applies a buff to the entity: refreshes the instances it matches, or lists a new one.
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
    if (entity.getTargetView().building() && buff.ignoreBuildings()) {
      return;
    }
    if ((entity.getView().getFlags() & NO_BUFFS) != 0) {
      return;
    }
    // A buff on a parent is handed to its riders; neither is modelled.
    if (entity instanceof CharacterEntity c && (c.getParent() != null || !c.riders().isEmpty())) {
      throw new UnsupportedOperationException(
          entity.name() + " rides or carries riders, whose share of a buff is not modelled");
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
    for (int i = items.size() - 1; i >= 0; i--) {
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
      if (buff.invisible()) {
        invisibleCount++;
      }
      world.buffApplied(entity, instance);
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
   * What the removal of an instance undoes at once: its share of the invisible count, and its
   * parent.
   */
  private void onRemoved(BuffInstance instance) {
    if (instance.getBuff().invisible()) {
      invisibleCount--;
    }
    instance.forgetParent();
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
      followSource(instance);
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
    if (damage >= 1 && entity.getHitPoints() != null) {
      world.dealBuffDamage(entity, instance, damage);
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

  /** The movement speed a base speed scales to. */
  public int speed(int base) {
    return scale(BuffData::speedMultiplier, base);
  }

  /** The attack time step a base step scales to; 0 under a stun. */
  public int hitSpeed(int base) {
    return scale(BuffData::hitSpeedMultiplier, base);
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
