package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * When the damage of a character's area hit lands within its tick. The game queues each victim's
 * share for the holder's damage drain, as it queues a direct hit, so a unit the area kills still
 * takes its movement visit of that tick.
 *
 * <p>The scene: the bottom side's Valkyrie walks up the left lane and meets the top side's Giant
 * coming down it. The Giant, which targets buildings only, walks on while the Valkyrie's area hits
 * it, and the area of one hit kills it while it walks. The same scene with the Giant's row written
 * with hit points no hit takes is the control: it shows where the Giant's walking step of each tick
 * takes it.
 */
class BattleAreaHitDrainTest {

  /** The battle stream's seed of the scene. */
  private static final int SEED = 1131;

  /** The levels the cards are played at: the Valkyrie strong, the Giant at its first. */
  private static final int VALKYRIE_LEVEL = 13;

  private static final int GIANT_LEVEL = 1;

  /** The tick the scene gives up looking for the killing hit. */
  private static final int LAST = 1000;

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
  void theGiantKilledByTheAreaStillWalksItsStep(@TempDir Path folder) throws IOException {
    // The tick of the killing hit: the step that leaves the Giant without hit points.
    Standard1v1Battle match = scene(GameData.tables());
    stepTo(match, 241);
    CharacterEntity giant = giant(match);
    int lastX = -1;
    int lastY = -1;
    int alive = -1;
    while (giant.getHitPoints().getHitPoints() > 0 && match.getBattle().getTick() < LAST) {
      lastX = giant.getView().getX();
      lastY = giant.getView().getY();
      alive = giant.getHitPoints().getHitPoints();
      match.getBattle().step();
    }
    int death = match.getBattle().getTick();
    assertThat(death).as("the Valkyrie kills the Giant").isLessThan(LAST);
    assertThat(alive).as("alive before the hit").isPositive();
    assertThat(giant.getHitPoints().getHitPoints()).as("killed").isZero();

    // The control's Giant takes every hit and lives: where its step of that tick takes it.
    Standard1v1Battle control =
        scene(
            GameData.altered(
                folder,
                "characters",
                rows -> GameData.columns(rows, "Giant").put("Hitpoints", 1_000_000)));
    stepTo(control, death - 1);
    CharacterEntity walker = giant(control);
    assertThat(new int[] {walker.getView().getX(), walker.getView().getY()})
        .as("the same walk up to the killing hit's tick")
        .containsExactly(lastX, lastY);
    stepTo(control, death);
    assertThat(new int[] {walker.getView().getX(), walker.getView().getY()})
        .as("the control walks a step on that tick")
        .isNotEqualTo(new int[] {lastX, lastY});

    assertThat(new int[] {giant.getView().getX(), giant.getView().getY()})
        .as("one walking step on")
        .containsExactly(walker.getView().getX(), walker.getView().getY());
  }
}
