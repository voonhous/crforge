package org.crforge.core.card;

import org.crforge.core.battle.unit.UnitData;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;

/**
 * Turns the card library's unit stats into the raw columns the battle reads.
 *
 * <p>The card library stores durations as float seconds and the battle reads whole milliseconds.
 * Every published duration is a whole number of milliseconds, so rounding the product recovers the
 * column exactly. The speed column is carried through untouched as {@link TroopStats#getRawSpeed}.
 */
public final class UnitDataMapper {

  private UnitDataMapper() {}

  /** The battle's view of one unit of the card library. */
  public static UnitData toUnitData(TroopStats stats) {
    TargetType targets = stats.getTargetType();
    return UnitData.builder()
        .name(stats.getName())
        .speed(stats.getRawSpeed())
        .range(stats.getRange())
        .sightRange(stats.getSightRange())
        .collisionRadius(stats.getCollisionRadius())
        .mass(Math.round(stats.getMass()))
        .hitSpeedMs(toMs(stats.getAttackCooldown()))
        .loadTimeMs(toMs(stats.getLoadTime()))
        .deployTimeMs(toMs(stats.getDeployTime()))
        .attacksGround(targets == TargetType.GROUND || targets == TargetType.ALL)
        .attacksAir(targets == TargetType.AIR || targets == TargetType.ALL)
        .air(stats.getMovementType() == MovementType.AIR)
        .building(stats.getMovementType() == MovementType.BUILDING)
        .hitpoints(stats.getHealth())
        .damage(stats.getDamage())
        .build();
  }

  private static int toMs(float seconds) {
    return Math.round(seconds * 1000f);
  }
}
