/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A building whose row is in the characters table, as the evolved Cannon's is, takes no part in
 * contact while the nearest other building stands within 500 of it edge to edge: a unit flying out
 * of a death spawn over it is not pushed by it.
 *
 * <p>The scenes write every size they rely on: the two buildings' 600 and the Knight's 500. The
 * Knight is put on its point and flown back to its ring point as a death spawn that pushes its
 * children flies them, so its first step is the flight's step with the push pass's average added.
 * The other building stands on the far side of the first, out of the Knight's reach, either 100
 * into it edge to edge or 500 clear of it.
 */
class BattleBuildingBesideBuildingContactTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** A building of the characters table: the evolved Cannon's row. */
  private static final String CHARACTER_BUILDING = "Cannon_EV1";

  /** A building of the buildings table. */
  private static final String BUILDING = "Cannon";

  private static final int CANNON_X = 9000;
  private static final int CANNON_Y = 10000;

  @TempDir static Path folder;

  private static GameTables tables;

  private static BattleRecords records;

  @BeforeAll
  static void writeTheScenes() throws IOException {
    GameData.altered(
        folder,
        "characters",
        rows -> {
          GameData.columns(rows, "Knight").put("CollisionRadius", 500);
          GameData.columns(rows, CHARACTER_BUILDING)
              .put("CollisionRadius", 600)
              .put("IsBuilding", true);
        });
    GameData.alterLoaded(
        folder, "buildings", rows -> GameData.columns(rows, BUILDING).put("CollisionRadius", 600));
    tables = GameTables.load(folder);
    records = new BattleRecords(tables);
  }

  /** A building placed on tick 0 that neither walks nor targets. */
  private static CharacterEntity standing(
      Standard1v1Battle match, String row, int x, int y, String name) {
    CharacterEntity unit = match.deploy(0, records.unit(row), LEVEL, 1, x, y, name);
    unit.setActive(CharacterEntity.TARGETING_SLOT, false);
    return unit;
  }

  /**
   * Where the Knight stands after the first step of its flight, flown from a point 781 from the
   * first building toward a ring point 2000 to the left: with the first building (of the given row)
   * or without it, and the other building the given distance below the first.
   */
  private static int[] firstStep(String row, boolean first, int otherDistance) {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    if (first) {
      standing(match, row, CANNON_X, CANNON_Y, "C");
    }
    standing(match, BUILDING, CANNON_X, CANNON_Y + otherDistance, "B");
    CharacterEntity knight =
        match.deploy(0, records.unit("Knight"), LEVEL, 0, CANNON_X - 2500, CANNON_Y - 600, "K");
    knight.setActive(CharacterEntity.TARGETING_SLOT, false);
    // Admitted on the first step, then put on the point and flown back from the second.
    match.getBattle().step();
    knight.flyBackFrom(CANNON_X - 500, CANNON_Y - 600);
    match.getBattle().step();
    return new int[] {knight.getView().getX(), knight.getView().getY()};
  }

  @Test
  @DisplayName(
      "a building of the characters table with another building 100 into it edge to edge does"
          + " not push a death spawn's child flying over it: its step is the one it takes alone")
  void besideAnotherBuildingItPushesNobody() {
    int[] alone = firstStep(CHARACTER_BUILDING, false, 1100);

    assertThat(firstStep(CHARACTER_BUILDING, true, 1100)).containsExactly(alone);
  }

  @Test
  @DisplayName(
      "the same building with the other building 500 clear of it, and a building of the"
          + " buildings table beside another, push the child off its flight")
  void clearOfOthersOrOfTheBuildingsTableItPushes() {
    assertThat(firstStep(CHARACTER_BUILDING, true, 1700))
        .isNotEqualTo(firstStep(CHARACTER_BUILDING, false, 1700));
    assertThat(firstStep(BUILDING, true, 1100)).isNotEqualTo(firstStep(BUILDING, false, 1100));
  }
}
