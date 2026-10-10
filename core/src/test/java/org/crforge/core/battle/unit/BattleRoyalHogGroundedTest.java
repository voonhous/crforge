package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.math.FixedMath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The evolved Royal Hog after its fall, as its grounded row: the battle reads the row a unit has
 * now on every visit, so the grounded hog jumps the river at that row's jump speed, and a filter
 * that leaves out flying objects asks the hog's layer, which its fall holds on the ground, not its
 * row's flying height.
 *
 * <p>The scene writes the hit-point share the fall waits for as 100%, so the hog falls as soon as
 * it has deployed, on its own side of the river.
 */
class BattleRoyalHogGroundedTest {

  private static final String HOG = "RoyalHog_EV1";

  /** The grounded row the hog takes as it lands. */
  private static final String GROUNDED = "RoyalHog_EV1_Grounded";

  /** The hit-point share at or below which the hog falls, as the scene writes it. */
  private static final int FALL_AT_PERCENT = 100;

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  @TempDir static Path folder;

  /** The configured tables with the scene's columns written. */
  private static GameTables tables;

  @BeforeAll
  static void writeTheScene() throws IOException {
    GameData.altered(folder, "characters", rows -> {});
    GameData.alterLoaded(
        folder,
        "actions",
        rows ->
            ((ObjectNode) rows.get("RoyalHog_EV1_Health_Threshold").get("fields"))
                .putArray("HealthPercentages")
                .add(FALL_AT_PERCENT));
    tables = GameTables.load(folder);
  }

  @Test
  @DisplayName(
      "a hog grounded on its own side jumps the water at its grounded row's jump speed and walks"
          + " on beyond the river")
  void theGroundedHogJumpsTheRiver() {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    Battle battle = match.getBattle();
    // Between the bridges, so its route to side 1's tower crosses the water.
    CharacterEntity hog =
        match.deploy(0, match.getWorld().getRecords().unit(HOG), LEVEL, 0, 9000, 11000);
    int jumpSpeed = Shipped.number(Shipped.unitRow(tables, GROUNDED), "JumpSpeed");
    assertThat(jumpSpeed).as("the grounded row jumps").isPositive();

    int jumpSteps = 0;
    int landedAt = -1;
    for (int tick = 0; tick < 400 && landedAt < 0; tick++) {
      int state = hog.getView().getState();
      int x = hog.getView().getX();
      int y = hog.getView().getY();
      battle.step();
      if (state == GridEntityState.JUMPING) {
        assertThat(hog.getData().name()).as("grounded before the jump").isEqualTo(GROUNDED);
        jumpSteps++;
        if (hog.getView().getState() == GridEntityState.JUMPING) {
          int moved =
              FixedMath.isqrt(
                  FixedMath.guardedSumOfSquares(
                      hog.getView().getX() - x, hog.getView().getY() - y));
          assertThat(moved)
              .as("a step of the jump at the row's jump speed")
              .isBetween(jumpSpeed - 2, jumpSpeed);
        } else {
          landedAt = tick;
        }
      }
    }
    assertThat(jumpSteps).as("the hog jumped").isPositive();
    assertThat(landedAt).as("the jump ends").isNotNegative();
    // It walks on from where it landed, over the river toward the tower.
    for (int tick = 0; tick < 200; tick++) {
      battle.step();
    }
    assertThat(hog.getView().getY()).as("beyond the river").isGreaterThan(17000);
  }

  @Test
  @DisplayName(
      "the evolved Goblin Cage, whose capture leaves out flying objects, captures a grounded hog")
  void theCageCapturesAGroundedHog() {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    Battle battle = match.getBattle();
    CharacterEntity hog =
        match.deploy(0, match.getWorld().getRecords().unit(HOG), LEVEL, 1, 3500, 13000);
    // Late enough that the hog has landed and walks on toward the cage.
    CharacterEntity cage =
        match.deploy(
            40,
            match.getWorld().getRecords().unit("GoblinCage_EV1_TEMPNAME"),
            LEVEL,
            0,
            3500,
            9500);
    assertThat(Shipped.number(Shipped.unitRow(tables, GROUNDED), "FlyingHeight"))
        .as("the grounded row keeps a flying height")
        .isPositive();

    int snapped = -1;
    for (int tick = 0; tick < 300 && snapped < 0; tick++) {
      battle.step();
      if (cage.getView().isAlive()
          && hog.getView().getX() == cage.getView().getX()
          && hog.getView().getY() == cage.getView().getY()) {
        snapped = tick;
      }
    }
    assertThat(hog.getData().name()).isEqualTo(GROUNDED);
    assertThat(snapped).as("the hog is put on the cage's point").isNotNegative();
  }
}
