/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.unit.CharacterEntity;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An enemy unit's hit points and shield as its health bar shows them on the player's screen: a row
 * that sets HideHealthbar shows them from the unit's first damage on and for its life, and never
 * when its starting client actions name no health bar; any other row always. Seat 1 plays the unit,
 * seat 0 is the player looking at it. Damage, a heal and a shield's growth are made by hand on the
 * battle core's hit points, so the rule is held whatever the unit meets.
 */
class HealthBarTest {

  private static final List<String> BASIC =
      List.of("Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows", "Zap");

  private static final List<String> QUEEN_DECK =
      List.of(
          "ArcherQueen", "Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Zap");

  private static final List<String> SHIELDED =
      List.of(
          "DarkPrince", "Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Zap");

  private static Standard1v1Battle battle(VectorEnv env) {
    return ((LadderEnv) env.env(0)).battle();
  }

  private static int header(VectorEnv env, int seat, int field) {
    return env.observations().asIntBuffer().get(seat * Schema.SEAT_INTS + field);
  }

  private static boolean open(VectorEnv env, int seat, int action) {
    return env.masks().get(seat * Schema.ACTIONS + action) == 1;
  }

  private static void act(VectorEnv env, int action0, int action1) {
    IntBuffer actions = env.actions().asIntBuffer();
    actions.put(0, action0);
    actions.put(1, action1);
    env.step();
  }

  /** A ladder battle on the tables whose seat 1 holds a card in its hand at the start. */
  private static VectorEnv withCardInHand(GameTables tables, List<String> deck1, String card) {
    VectorEnv env = VectorEnv.ladder(tables, EnvConfig.DEFAULT, 1, 1);
    env.setAutoreset(false);
    int id = VectorEnv.vocabulary(tables).id(card);
    for (int seed = 100; seed < 300; seed++) {
      env.reset(new int[] {seed}, BASIC, deck1);
      for (int k = 0; k < Schema.HAND_SLOTS; k++) {
        if (header(env, 1, Schema.H_HAND_CARD + k) == id) {
          return env;
        }
      }
    }
    env.close();
    throw new AssertionError("no seed deals " + card + " to seat 1");
  }

  /**
   * Seat 1 plays a card of its hand near the middle of its own half once the play is open, seat 0
   * waiting, and the battle steps until the play has run.
   */
  private static Standard1v1Battle.Play play(VectorEnv env, GameTables tables, String card) {
    int id = VectorEnv.vocabulary(tables).id(card);
    Standard1v1Battle battle = battle(env);
    int plays = battle.getPlays().size();
    boolean given = false;
    for (int step = 0; step < 200 && battle.getPlays().size() == plays; step++) {
      int action = Schema.WAIT;
      for (int k = 0; k < Schema.HAND_SLOTS && !given; k++) {
        if (header(env, 1, Schema.H_HAND_CARD + k) == id) {
          action = nearestOpen(env, k, Schema.COLS / 2, 4);
        }
      }
      given |= action != Schema.WAIT;
      act(env, Schema.WAIT, action);
    }
    assertThat(battle.getPlays().size())
        .as("seat 1's play of " + card + " ran")
        .isGreaterThan(plays);
    Standard1v1Battle.Play play = battle.getPlays().get(plays);
    assertThat(play.side()).isEqualTo(1);
    return play;
  }

  /** Seat 1's open play of a hand slot nearest a tile of its frame, else the wait. */
  private static int nearestOpen(VectorEnv env, int slot, int col, int row) {
    int best = Schema.WAIT;
    int bestDistance = Integer.MAX_VALUE;
    for (int r = 0; r < Schema.ROWS; r++) {
      for (int c = 0; c < Schema.COLS; c++) {
        int distance = (c - col) * (c - col) + (r - row) * (r - row);
        if (distance < bestDistance && open(env, 1, Schema.play(slot, c, r))) {
          best = Schema.play(slot, c, r);
          bestDistance = distance;
        }
      }
    }
    return best;
  }

  /** Steps until seat 0 has the unit's row, the unit on the field: the row. */
  private static int[] onField(VectorEnv env, CharacterEntity unit) {
    for (int step = 0; step < 60; step++) {
      int[] row = SeatObservationTest.rowOf(env, 0, unit.getId());
      if (row != null
          && unit.getView().getState() != GridEntityState.WAITING_TO_DEPLOY
          && !unit.underground()) {
        return row;
      }
      act(env, Schema.WAIT, Schema.WAIT);
    }
    throw new AssertionError("the unit never came on the field");
  }

  /** Steps one decision, both seats waiting: seat 0's row of the unit. */
  private static int[] next(VectorEnv env, CharacterEntity unit) {
    act(env, Schema.WAIT, Schema.WAIT);
    int[] row = SeatObservationTest.rowOf(env, 0, unit.getId());
    assertThat(row).as("seat 0's row of the unit").isNotNull();
    return row;
  }

  private static void assertShown(int[] row, HitPoints points) {
    assertThat(row[Schema.E_HP]).isEqualTo(points.getHitPoints());
    assertThat(row[Schema.E_SHIELD]).isEqualTo(points.getShield());
    assertThat(row[Schema.E_MAX_HP]).isEqualTo(points.getMaximum());
  }

  private static void assertHidden(int[] row, HitPoints points) {
    assertThat(row[Schema.E_HP]).isEqualTo(Schema.UNKNOWN);
    assertThat(row[Schema.E_SHIELD]).isEqualTo(Schema.UNKNOWN);
    assertThat(row[Schema.E_MAX_HP]).isEqualTo(points.getMaximum());
  }

  /** Whether a row's starting client actions name a health bar, the bar of its own it builds. */
  private static boolean buildsHealthBar(GameRow row) {
    return row.has("OnStartingClientActions")
        && row.value("OnStartingClientActions")
            .toString()
            .toLowerCase(Locale.ROOT)
            .contains("healthbar");
  }

  @Test
  void aUnitThatHidesItsBarKeepsShowingItOnceDamagedHealedBackToFull() {
    GameTables tables = GameData.tables();
    try (VectorEnv env = withCardInHand(tables, QUEEN_DECK, "ArcherQueen")) {
      CharacterEntity unit = play(env, tables, "ArcherQueen").units().get(0);
      GameRow row = tables.table("characters").row(unit.getData().name());
      assertThat(row.bool("HideHealthbar")).as("the unit's row sets HideHealthbar").isTrue();
      assertThat(buildsHealthBar(row)).as("the unit's row builds its own bar").isTrue();
      HitPoints points = unit.getHitPoints();
      int[] seen = onField(env, unit);
      assertThat(points.getHitPoints()).isEqualTo(points.getMaximum());
      assertHidden(seen, points);
      // Damaged: its bar shows.
      points.setHitPoints(points.getMaximum() - 1);
      assertShown(next(env, unit), points);
      // Healed back to full: the bar stays, its hit points their maximum.
      points.setHitPoints(points.getMaximum());
      for (int step = 0; step < 3; step++) {
        int[] after = next(env, unit);
        assertThat(points.getHitPoints()).isEqualTo(points.getMaximum());
        assertShown(after, points);
      }
    }
  }

  @Test
  void aUnitDamagedAndHealedBetweenTwoDecisionsShowsItsBar() {
    GameTables tables = GameData.tables();
    try (VectorEnv env = withCardInHand(tables, QUEEN_DECK, "ArcherQueen")) {
      CharacterEntity unit = play(env, tables, "ArcherQueen").units().get(0);
      HitPoints points = unit.getHitPoints();
      assertHidden(onField(env, unit), points);
      // Hurt for one tick inside the decision, then healed back to full before it ends.
      LadderEnv ladder = (LadderEnv) env.env(0);
      points.setHitPoints(points.getMaximum() - 1);
      ladder.advance();
      points.setHitPoints(points.getMaximum());
      assertShown(next(env, unit), points);
    }
  }

  @Test
  void aShieldThatGrowsShowsTheBarOfAUnitThatHidesIt() {
    GameTables tables = GameData.tables();
    try (VectorEnv env = withCardInHand(tables, SHIELDED, "DarkPrince")) {
      CharacterEntity unit = play(env, tables, "DarkPrince").units().get(0);
      GameRow row = tables.table("characters").row(unit.getData().name());
      assertThat(row.bool("HideHealthbar")).as("the unit's row sets HideHealthbar").isTrue();
      assertThat(buildsHealthBar(row)).as("the unit's row builds its own bar").isTrue();
      HitPoints points = unit.getHitPoints();
      assertThat(points.getShieldMaximum()).as("the unit's shield").isPositive();
      assertHidden(onField(env, unit), points);
      // The shield grows, its hit points and shield both at their maximum.
      points.setShieldMaximum(points.getShieldMaximum() + 1);
      points.setShield(points.getShield() + 1);
      int[] after = next(env, unit);
      assertThat(points.getHitPoints()).isEqualTo(points.getMaximum());
      assertThat(points.getShield()).isEqualTo(points.getShieldMaximum());
      assertShown(after, points);
    }
  }

  @Test
  void aRowThatHidesItsBarAndBuildsNoneNeverShowsHitPoints(@TempDir Path folder)
      throws IOException {
    // The Knight as a row that sets HideHealthbar and names no health bar among its starting
    // client actions, as the game's dummies and ghosts do.
    GameTables tables =
        GameData.altered(
            folder,
            "characters",
            rows -> {
              ObjectNode columns = GameData.columns(rows, "Knight");
              columns.put("HideHealthbar", true);
              columns.remove("OnStartingClientActions");
            });
    try (VectorEnv env = withCardInHand(tables, BASIC, "Knight")) {
      CharacterEntity unit = play(env, tables, "Knight").units().get(0);
      HitPoints points = unit.getHitPoints();
      assertHidden(onField(env, unit), points);
      points.setHitPoints(points.getMaximum() / 2);
      for (int step = 0; step < 3; step++) {
        assertHidden(next(env, unit), points);
      }
    }
  }

  @Test
  void anUnhurtUnitWhoseRowNamesNoHealthBarShowsItsHitPoints(@TempDir Path folder)
      throws IOException {
    // The Knight naming no health bar row: the game gives it a default one by its hit points,
    // which shows to the enemy always.
    GameTables tables =
        GameData.altered(
            folder,
            "characters",
            rows -> {
              ObjectNode columns = GameData.columns(rows, "Knight");
              columns.remove("HealthBar");
              columns.remove("HideHealthbar");
            });
    try (VectorEnv env = withCardInHand(tables, BASIC, "Knight")) {
      CharacterEntity unit = play(env, tables, "Knight").units().get(0);
      HitPoints points = unit.getHitPoints();
      int[] row = onField(env, unit);
      assertThat(points.getHitPoints()).isEqualTo(points.getMaximum());
      assertShown(row, points);
    }
  }
}
