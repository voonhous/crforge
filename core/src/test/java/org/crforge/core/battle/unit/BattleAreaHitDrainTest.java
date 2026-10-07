package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * When the damage of a character's area hit lands within its tick. The game queues each victim's
 * share for the holder's damage drain, as it queues a direct hit, so a unit the area kills still
 * takes its movement visit of that tick.
 *
 * <p>The scene: the bottom side's Valkyrie walks up the left lane and meets the top side's Giant
 * coming down it. The Giant, which targets buildings only, walks on while the Valkyrie's area hits
 * it, with 266 hit points left from tick 385 on, and the area of the hit that lands on tick 415
 * kills it while it walks.
 */
class BattleAreaHitDrainTest {

  /** The battle stream's seed of the scene. */
  private static final int SEED = 1131;

  /** The levels the cards are played at: the Valkyrie strong, the Giant at its first. */
  private static final int VALKYRIE_LEVEL = 13;

  private static final int GIANT_LEVEL = 1;

  /** The tick of the killing hit. */
  private static final int DEATH = 415;

  /** Where the Giant stands after its last walking step before the killing hit's tick. */
  private static final int LAST_X = 3200;

  private static final int LAST_Y = 14819;

  /** Where its walking step of the killing hit's tick takes it. */
  private static final int STEP_X = 3202;

  private static final int STEP_Y = 14768;

  /** The towers at the first level, fighting; the Valkyrie and the Giant played. */
  private static Standard1v1Battle scene(GameTables tables) {
    Standard1v1Battle match = new Standard1v1Battle(tables, 1, true);
    match.getWorld().seed(SEED);
    match.play(220, GameData.card("Valkyrie"), VALKYRIE_LEVEL, 0, 3500, 14000, "V");
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
      "an area hit lands at the damage drain, so the Giant it kills"
          + " still takes its walking step of that tick")
  void theGiantKilledByTheAreaStillWalksItsStep() {
    Standard1v1Battle match = scene(GameData.tables());
    stepTo(match, DEATH - 1);
    CharacterEntity giant = giant(match);
    assertThat(giant.getHitPoints().getHitPoints()).as("alive before the hit").isEqualTo(266);
    assertThat(giant.getView().getX()).isEqualTo(LAST_X);
    assertThat(giant.getView().getY()).isEqualTo(LAST_Y);

    stepTo(match, DEATH);
    assertThat(giant.getHitPoints().getHitPoints()).as("killed").isZero();
    assertThat(giant.getView().getX()).as("one walking step on").isEqualTo(STEP_X);
    assertThat(giant.getView().getY()).isEqualTo(STEP_Y);
  }
}
