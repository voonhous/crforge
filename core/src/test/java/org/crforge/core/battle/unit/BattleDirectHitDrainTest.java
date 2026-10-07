package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * When a direct hit's damage lands within its tick. The game queues it for the holder's damage
 * drain, which runs after every post-hook, so a unit the hit kills still takes its movement visit
 * of that tick.
 *
 * <p>The scene: the bottom side's Mini P.E.K.K.A. walks up the left lane and meets the top side's
 * Giant coming down it. The Giant, which targets buildings only, walks on while the Mini P.E.K.K.A.
 * hits it, with 40 hit points left from tick 353 on, and the hit that lands on tick 358 kills it
 * while it walks.
 */
class BattleDirectHitDrainTest {

  /** The battle stream's seed of the scene. */
  private static final int SEED = 1131;

  /** The levels the cards are played at: the Mini P.E.K.K.A. strong, the Giant at its first. */
  private static final int MINI_PEKKA_LEVEL = 11;

  private static final int GIANT_LEVEL = 1;

  /** The tick of the killing hit. */
  private static final int DEATH = 358;

  /** Where the Giant stands after its last walking step before the killing hit's tick. */
  private static final int LAST_X = 3733;

  private static final int LAST_Y = 17251;

  /** The towers at the first level, fighting; the Mini P.E.K.K.A. and the Giant played. */
  private static Standard1v1Battle scene(GameTables tables) {
    Standard1v1Battle match = new Standard1v1Battle(tables, 1, true);
    match.getWorld().seed(SEED);
    match.play(220, GameData.card("MiniPekka"), MINI_PEKKA_LEVEL, 0, 3500, 14000, "P");
    match.play(240, GameData.card("Giant"), GIANT_LEVEL, 1, 3500, 21500, "G");
    return match;
  }

  /** Steps the battle until its tick is the given one. */
  private static void stepTo(Standard1v1Battle match, int tick) {
    while (match.getBattle().getTick() < tick) {
      match.getBattle().step();
    }
  }

  /** The Giant, the second play's unit. */
  private static CharacterEntity giant(Standard1v1Battle match) {
    return match.getPlays().get(1).units().get(0);
  }

  @Test
  @DisplayName(
      "a direct hit lands at the damage drain, so the Giant it kills still takes its walking step"
          + " of that tick")
  void theKilledGiantStillWalksItsStep() {
    Standard1v1Battle match = scene(GameData.tables());
    stepTo(match, DEATH - 1);
    CharacterEntity giant = giant(match);
    assertThat(giant.getHitPoints().getHitPoints()).as("alive before the hit").isEqualTo(40);
    assertThat(giant.getView().getX()).isEqualTo(LAST_X);
    assertThat(giant.getView().getY()).isEqualTo(LAST_Y);

    stepTo(match, DEATH);
    assertThat(giant.getHitPoints().getHitPoints()).as("killed").isZero();
    assertThat(giant.getView().getX()).as("one walking step on").isEqualTo(LAST_X);
    assertThat(giant.getView().getY()).isEqualTo(LAST_Y - 52);
  }
}
