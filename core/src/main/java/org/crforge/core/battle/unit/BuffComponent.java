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
 * <p><b>Apply.</b> A buff reaches an entity from an area effect's hit. A building takes nothing of
 * a buff that ignores buildings, an entity tagged against buffs takes nothing, and a crown tower
 * nothing of one that spares them. Otherwise an instance of the same row is refreshed instead of a
 * second one listed: any instance of the row, or with stacking only the one the same source
 * applied, or for a buff specific to a player only one from the same side. A refresh keeps the
 * longer time, growing the whole by the difference, and the higher level. With nothing to refresh a
 * new instance is listed last, its level packed against the buff's rarity.
 *
 * <p><b>Visit.</b> In the holder tick's pass 3 each instance, from the last to the first, loses 50
 * ms, deals its damage over time when one is due, and is removed once its time is 0. The damage is
 * the damage per second at the instance's level, rounded down to a multiple of what one hit can
 * deal, for the period of the hit; a crown tower takes the per-hit column or that share raised by
 * the crown-tower percent, and a building the building percent of it.
 *
 * <p><b>Scales.</b> The speed, the attack time step and the spawn time step each take the largest
 * boost of the listed rows, from 100, times what the largest slow leaves of 100: Rage makes a step
 * of 50 one of 65, and a stun of -100 makes it 0.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Held by rage_knight, zap_knight and poison_knight_tower: the apply with its refresh by"
            + " row, by source under stacking, the new instance and its level; the visit in pass"
            + " 3, the 50 ms step and the removal, the damage over time on a unit and a crown"
            + " tower; the speed, hit speed and spawn speed scales. Translated but held by no"
            + " run: a building's damage percent, a player-specific refresh, the per-hit crown"
            + " tower column, a negative hit frequency and the forgotten source. Refused by the"
            + " row: projectiles, chains, death spawns, spawns, morphs, actions, tags, switching"
            + " team, invisibility, shields, hit point and damage multipliers, damage reduction,"
            + " heal over time, pull and push, and a parent that controls the buff.")
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

  BuffComponent(WorldEntity entity, BattleWorld world) {
    this.entity = entity;
    this.world = world;
  }

  @Override
  public int index() {
    return SLOT;
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
    if (entity.getTargetView().building() && buff.ignoreBuildings()) {
      return;
    }
    if ((entity.getView().getFlags() & NO_BUFFS) != 0) {
      return;
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
          world.buffRefreshed(entity, instance, before);
        }
        create = false;
      }
    }
    if (create) {
      BuffInstance instance =
          new BuffInstance(world.nextBuffKey(), buff, time, level, source, side);
      items.add(instance);
      world.buffApplied(entity, instance);
    }
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
    for (int k = snapshot.size() - 1; k >= 0; k--) {
      BuffInstance instance = snapshot.get(k);
      instance.step(STEP_MS);
      int period = instance.countHit(STEP_MS);
      if (period != 0) {
        overTime(instance, period);
      }
      if (instance.getRemaining() == 0 && items.remove(instance)) {
        removed.add(instance);
      }
    }
    for (BuffInstance instance : removed) {
      world.buffRemoved(entity, instance);
    }
  }

  /** One hit of an instance's damage over time, for the period it covers. */
  private void overTime(BuffInstance instance, int period) {
    BuffData buff = instance.getBuff();
    int level = instance.getPackedLevel();
    int damage;
    if (entity.getTargetView().crownTower()) {
      damage = crownTowerDamage(buff, level);
    } else {
      damage = damagePerSecond(buff, level) * period / 1000;
      if (entity.getTargetView().building() && buff.buildingDamagePercent() != 0) {
        damage = buff.buildingDamagePercent() * damage / PERCENT;
      }
    }
    if (damage >= 1 && entity.getHitPoints() != null) {
      world.dealBuffDamage(entity, instance, damage);
    }
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

  /** A source that left the battle is forgotten by the instances it applied. */
  void entityRemoved(BattleEntity removed) {
    for (int i = items.size() - 1; i >= 0; i--) {
      BuffInstance instance = items.get(i);
      if (instance.getSource() == removed) {
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
