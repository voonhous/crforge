package org.crforge.core.card;

import org.crforge.core.battle.unit.UnitData;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.crforge.core.pathfinding.combat.RarityTable;

/**
 * Turns the card library's unit stats into the raw columns the battle reads.
 *
 * <p>The card library stores durations as float seconds and the battle reads whole milliseconds.
 * Every published duration is a whole number of milliseconds, so rounding the product recovers the
 * column exactly. The speed column is carried through untouched as {@link TroopStats#getRawSpeed}.
 *
 * <p>The card library keeps the rarity on the card, not on the unit, so the unit is scaled by the
 * rarity of the card that deploys it. A unit's own rarity column is not carried by the library; for
 * a unit another unit spawns, the two may differ.
 */
public final class UnitDataMapper {

  private UnitDataMapper() {}

  /** The battle's view of the unit a card deploys, scaled by the card's rarity. */
  public static UnitData toUnitData(Card card) {
    return toUnitData(card.getUnitStats(), rarityTable(card.getRarity()));
  }

  /** The battle's view of one unit of the card library. */
  public static UnitData toUnitData(TroopStats stats, RarityTable rarity) {
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
        .rarity(rarity)
        .build();
  }

  /**
   * The published scaling row of a card rarity. A card without a rarity is scaled as a Common one,
   * which is how the card library scales it too.
   */
  public static RarityTable rarityTable(Rarity rarity) {
    return switch (rarity) {
      case COMMON, UNKNOWN -> RarityTable.COMMON;
      case RARE -> RarityTable.RARE;
      case EPIC -> RarityTable.EPIC;
      case LEGENDARY -> RarityTable.LEGENDARY;
      case CHAMPION -> RarityTable.CHAMPION;
    };
  }

  private static int toMs(float seconds) {
    return Math.round(seconds * 1000f);
  }
}
