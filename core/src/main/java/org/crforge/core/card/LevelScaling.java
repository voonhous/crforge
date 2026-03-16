package org.crforge.core.card;

/**
 * Stat scaling by card level and rarity, based on: https://royaleapi.com/blog/secret-stats and
 * LEVEL_SCALING.md
 *
 * <p>The multiplier is tracked as an integer in hundredths (e.g. 108 = 1.08) and advanced with
 * floor each level: {@code m = floor(m * growth)}. The final stat is then {@code floor(baseStat * m
 * / 100)}. This matches the game's own rounding behaviour and produces the exact values in the
 * LEVEL_SCALING.md tables.
 *
 * <ul>
 *   <li>Cards: ×1.10 per level, starting from the rarity's minimum level
 *   <li>Tower HP: ×1.07 (King) or ×1.08 (Princess) for levels 1–9, then ×1.10 for 10+
 *   <li>Tower damage: ×1.08 for levels 1–9, then ×1.10 for 10+
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
   * Scales a base stat by rarity and card level. Growth rate is 1.10 per level, applied iteratively
   * with floor. Levels below the rarity's minimum are clamped to it.
   *
   * @param baseStat base value at the rarity's minimum level
   * @param rarity card rarity
   * @param level target card level
   * @return scaled stat value
   */
  public static int scaleCard(int baseStat, Rarity rarity, int level) {
    int minLevel = getMinLevel(rarity);
    int clampedLevel = Math.max(minLevel, Math.min(level, MAX_CARD_LEVEL));
    return applyMultiplier(baseStat, clampedLevel - minLevel, 1.10);
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
    // Multiplier tracked in hundredths; growth threshold switches at level 10 (i.e. step 9+)
    int m = 100;
    for (int i = 1; i < level; i++) {
      double growth = i < 9 ? earlyGrowth : lateGrowth;
      m = (int) Math.floor(m * growth);
    }
    return (int) Math.floor(base * m / 100.0);
  }

  /**
   * Advances a multiplier (starting at 100 = 1.00) by {@code steps} levels, then applies it to
   * {@code base}: {@code floor(base * m / 100)}.
   */
  private static int applyMultiplier(int base, int steps, double growth) {
    int m = 100;
    for (int i = 0; i < steps; i++) {
      m = (int) Math.floor(m * growth);
    }
    return (int) Math.floor(base * m / 100.0);
  }

  public static int getMinLevel(Rarity rarity) {
    return switch (rarity) {
      case COMMON, UNKNOWN -> COMMON_MIN_LEVEL;
      case RARE -> RARE_MIN_LEVEL;
      case EPIC -> EPIC_MIN_LEVEL;
      case LEGENDARY -> LEGENDARY_MIN_LEVEL;
      case CHAMPION -> CHAMPION_MIN_LEVEL;
    };
  }
}
