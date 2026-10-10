/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.battle.Shipped.flag;
import static org.crforge.core.battle.Shipped.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.GameRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What a unit does when its reference goes during its first, preloaded windup. */
class BattleReferenceLossTest {

  /**
   * The character rows whose attack runs on to a hit with no target, with the shipped global and no
   * burst, worked out from the rows as the tables write them: an attack centred on the unit itself
   * (SelfAsAoeCenter), or a projectile row that does not home. Every other row stops. The standard
   * game answers so for every shipped row.
   */
  private static Set<String> runOn() {
    Set<String> rows = new TreeSet<>();
    for (GameRow row : GameData.tables().table("characters").rows()) {
      String projectile = text(row, "Projectile");
      if (flag(row, "SelfAsAoeCenter")
          || (projectile != null && !flag(Shipped.row("projectiles", projectile), "Homing"))) {
        rows.add(row.name());
      }
    }
    return rows;
  }

  /** Every character row the records build; a row they refuse is left out. */
  private static List<UnitData> characters() {
    List<UnitData> rows = new ArrayList<>();
    for (GameRow row : GameData.tables().table("characters").rows()) {
      try {
        rows.add(GameData.records().unit(row.name()));
      } catch (UnsupportedOperationException refused) {
        // A row the battle does not build is never asked.
      }
    }
    return rows;
  }

  @Test
  @DisplayName(
      "an attack centred on itself or with a projectile that does not home runs on; every other"
          + " stops")
  void theQueryAnswersByTheRow() {
    List<UnitData> rows = characters();
    assertThat(rows).isNotEmpty();
    List<String> runOn = new ArrayList<>();
    Set<String> derived = runOn();
    Set<String> expected = new TreeSet<>();
    for (UnitData row : rows) {
      if (!WorldEntity.stopsWithoutTarget(row, 0, true)) {
        runOn.add(row.name());
      }
      if (derived.contains(row.name())) {
        expected.add(row.name());
      }
    }
    assertThat(runOn).isNotEmpty().containsExactlyInAnyOrderElementsOf(expected);
  }

  @Test
  @DisplayName("a running burst runs on, and without the global every row stops")
  void theBurstAndTheGlobal() {
    for (UnitData row : characters()) {
      assertThat(WorldEntity.stopsWithoutTarget(row, 50, true)).as(row.name()).isFalse();
      assertThat(WorldEntity.stopsWithoutTarget(row, 0, false)).as(row.name()).isTrue();
      assertThat(WorldEntity.stopsWithoutTarget(row, 50, false)).as(row.name()).isTrue();
    }
    assertThat(GameData.records().globalBoolean("ALLOW_AOE_ATTACKS_WITHOUT_TARGET"))
        .isEqualTo(
            flag(Shipped.row("globals", "ALLOW_AOE_ATTACKS_WITHOUT_TARGET"), "BooleanValue"));
  }
}
