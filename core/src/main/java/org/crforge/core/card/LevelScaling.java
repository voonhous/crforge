package org.crforge.core.card;

/**
 * Stat scaling by card level and rarity, based on:
 * https://royaleapi.com/blog/secret-stats and LEVEL_SCALING.md
 * <p>
 * Formula: each level applies {@code floor(prev * growth)} to the base stat.
 * <ul>
 *   <li>Cards: 1.10 per level, starting from the rarity's minimum level</li>
 *   <li>Tower HP: 1.07 (King) or 1.08 (Princess) for levels 1–9, then 1.10 for 10+</li>
 *   <li>Tower damage: 1.08 for levels 1–9, then 1.10 for 10+</li>
 * </ul>
 */
public final class LevelScaling {

  // Minimum card level per rarity (real-game unlock levels)
  public static final int COMMON_MIN_LEVEL = 1;
  public static final int RARE_MIN_LEVEL = 3;
  public static final int EPIC_MIN_LEVEL = 6;
  public static final int LEGENDARY_MIN_LEVEL = 9;
  public static final int CHAMPION_MIN_LEVEL = 11;
  public static final int MAX_CARD_LEVEL = 16;

  public static final int MAX_PRINCESS_LEVEL = 16;
  public static final int MAX_KING_LEVEL = 16;

  /** Default tower level matching the previously hardcoded stats (King HP=4824, damage=109). */
  public static final int DEFAULT_TOWER_LEVEL = 11;

  // Tower base stats (level 1)
  private static final int PRINCESS_BASE_HP = 1400;
  private static final int PRINCESS_BASE_DAMAGE = 50;
  private static final int KING_BASE_HP = 2400;
  private static final int KING_BASE_DAMAGE = 50;

  private LevelScaling() {}

  /**
   * Scales a base stat by rarity and card level.
   * Growth rate is 1.10 per level, applied iteratively with floor.
   * Levels below the rarity's minimum are clamped to it.
   *
   * @param baseStat base value at the rarity's minimum level
   * @param rarity   card rarity
   * @param level    target card level
   * @return scaled stat value
   */
  public static int scaleCard(int baseStat, Rarity rarity, int level) {
    int minLevel = getMinLevel(rarity);
    int clampedLevel = Math.max(minLevel, Math.min(level, MAX_CARD_LEVEL));
    return applyGrowth(baseStat, clampedLevel - minLevel, 1.10);
  }

  public static int scalePrincessHp(int level) {
    int clamped = Math.max(1, Math.min(level, MAX_PRINCESS_LEVEL));
    return scaleTowerStat(PRINCESS_BASE_HP, clamped, 1.08, 1.10);
  }

  public static int scalePrincessDamage(int level) {
    int clamped = Math.max(1, Math.min(level, MAX_PRINCESS_LEVEL));
    return scaleTowerStat(PRINCESS_BASE_DAMAGE, clamped, 1.08, 1.10);
  }

  public static int scaleKingHp(int level) {
    int clamped = Math.max(1, Math.min(level, MAX_KING_LEVEL));
    return scaleTowerStat(KING_BASE_HP, clamped, 1.07, 1.10);
  }

  public static int scaleKingDamage(int level) {
    int clamped = Math.max(1, Math.min(level, MAX_KING_LEVEL));
    return scaleTowerStat(KING_BASE_DAMAGE, clamped, 1.08, 1.10);
  }

  private static int scaleTowerStat(int base, int level, double earlyGrowth, double lateGrowth) {
    // Levels 1–9: earlyGrowth per level; levels 10+: lateGrowth per level
    int earlySteps = Math.min(level - 1, 8);        // levels 2–9
    int lateSteps  = Math.max(0, level - 9);         // levels 10+
    int val = applyGrowth(base, earlySteps, earlyGrowth);
    return applyGrowth(val, lateSteps, lateGrowth);
  }

  /** Applies {@code floor(prev * growth)} for {@code steps} iterations. */
  private static int applyGrowth(int base, int steps, double growth) {
    int val = base;
    for (int i = 0; i < steps; i++) {
      val = (int) Math.floor(val * growth);
    }
    return val;
  }

  private static int getMinLevel(Rarity rarity) {
    return switch (rarity) {
      case COMMON -> COMMON_MIN_LEVEL;
      case RARE -> RARE_MIN_LEVEL;
      case EPIC -> EPIC_MIN_LEVEL;
      case LEGENDARY -> LEGENDARY_MIN_LEVEL;
      case CHAMPION -> CHAMPION_MIN_LEVEL;
    };
  }
}
