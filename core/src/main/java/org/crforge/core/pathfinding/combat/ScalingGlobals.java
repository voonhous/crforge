package org.crforge.core.pathfinding.combat;

import lombok.Builder;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The balance values that scale the crown towers by level.
 *
 * <p>A tower stat is compounded once per level above the first: by the per-level percentage below
 * the tournament cap, by the at-cap percentage on the cap level and by the after-cap percentage
 * beyond it. The cap is a level index, {@link #towerScalingStartExpLevel} less the rarity's
 * relative level. Every value is the published one.
 *
 * @param towerScalingStartExpLevel the level index the tournament cap sits at
 * @param damageIncreasePercentPerKingLevel king tower damage, per level below the cap
 * @param damageIncreasePercentPerKingLevelAtTournamentCap king tower damage, on the cap level
 * @param damageIncreasePercentPerKingLevelAfterTournamentCap king tower damage, beyond the cap
 * @param hitpointIncreasePercentPerKingLevel king tower hit points, per level below the cap
 * @param hitpointIncreasePercentPerKingLevelAtTournamentCap king tower hit points, on the cap level
 * @param hitpointIncreasePercentPerKingLevelAfterTournamentCap king tower hit points, beyond the
 *     cap
 * @param damageIncreasePercentPerTowerLevel princess tower damage, per level below the cap
 * @param damageIncreasePercentPerTowerLevelAtTournamentCap princess tower damage, on the cap level
 * @param damageIncreasePercentPerTowerLevelAfterTournamentCap princess tower damage, beyond the cap
 * @param hitpointIncreasePercentPerTowerLevel princess tower hit points, per level below the cap
 * @param hitpointIncreasePercentPerTowerLevelAtTournamentCap princess tower hit points, on the cap
 *     level
 * @param hitpointIncreasePercentPerTowerLevelAfterTournamentCap princess tower hit points, beyond
 *     the cap
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: every value is the published one and the mode picks the three percentages the"
            + " compounding reads. Not settled: the two-against-two raise of a tower's maximum,"
            + " which is not carried here.")
@Builder(toBuilder = true)
public record ScalingGlobals(
    int towerScalingStartExpLevel,
    int damageIncreasePercentPerKingLevel,
    int damageIncreasePercentPerKingLevelAtTournamentCap,
    int damageIncreasePercentPerKingLevelAfterTournamentCap,
    int hitpointIncreasePercentPerKingLevel,
    int hitpointIncreasePercentPerKingLevelAtTournamentCap,
    int hitpointIncreasePercentPerKingLevelAfterTournamentCap,
    int damageIncreasePercentPerTowerLevel,
    int damageIncreasePercentPerTowerLevelAtTournamentCap,
    int damageIncreasePercentPerTowerLevelAfterTournamentCap,
    int hitpointIncreasePercentPerTowerLevel,
    int hitpointIncreasePercentPerTowerLevelAtTournamentCap,
    int hitpointIncreasePercentPerTowerLevelAfterTournamentCap) {

  /**
   * The three percentages one tower mode compounds by.
   *
   * @param perLevel below the cap
   * @param atCap on the cap level
   * @param afterCap beyond the cap
   */
  public record Percentages(int perLevel, int atCap, int afterCap) {}

  private static final Percentages UNSCALED = new Percentages(0, 0, 0);

  /** The published values. */
  public static ScalingGlobals standard() {
    return ScalingGlobals.builder()
        .towerScalingStartExpLevel(9)
        .damageIncreasePercentPerKingLevel(8)
        .damageIncreasePercentPerKingLevelAtTournamentCap(10)
        .damageIncreasePercentPerKingLevelAfterTournamentCap(10)
        .hitpointIncreasePercentPerKingLevel(7)
        .hitpointIncreasePercentPerKingLevelAtTournamentCap(10)
        .hitpointIncreasePercentPerKingLevelAfterTournamentCap(10)
        .damageIncreasePercentPerTowerLevel(8)
        .damageIncreasePercentPerTowerLevelAtTournamentCap(10)
        .damageIncreasePercentPerTowerLevelAfterTournamentCap(10)
        .hitpointIncreasePercentPerTowerLevel(8)
        .hitpointIncreasePercentPerTowerLevelAtTournamentCap(10)
        .hitpointIncreasePercentPerTowerLevelAfterTournamentCap(10)
        .build();
  }

  /**
   * The percentages a mode compounds by. The card modes and {@link ScalingMode#NONE} compound by
   * nothing, which is how a card stat without a rarity is left at its base.
   */
  public Percentages percentages(ScalingMode mode) {
    return switch (mode) {
      case KING_DAMAGE ->
          new Percentages(
              damageIncreasePercentPerKingLevel,
              damageIncreasePercentPerKingLevelAtTournamentCap,
              damageIncreasePercentPerKingLevelAfterTournamentCap);
      case KING_HITPOINTS ->
          new Percentages(
              hitpointIncreasePercentPerKingLevel,
              hitpointIncreasePercentPerKingLevelAtTournamentCap,
              hitpointIncreasePercentPerKingLevelAfterTournamentCap);
      case TOWER_DAMAGE ->
          new Percentages(
              damageIncreasePercentPerTowerLevel,
              damageIncreasePercentPerTowerLevelAtTournamentCap,
              damageIncreasePercentPerTowerLevelAfterTournamentCap);
      case TOWER_HITPOINTS ->
          new Percentages(
              hitpointIncreasePercentPerTowerLevel,
              hitpointIncreasePercentPerTowerLevelAtTournamentCap,
              hitpointIncreasePercentPerTowerLevelAfterTournamentCap);
      case NONE, CARD_DAMAGE, CARD_HITPOINTS -> UNSCALED;
    };
  }
}
