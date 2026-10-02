package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What a unit does when its reference goes during its first, preloaded windup. */
class BattleReferenceLossTest {

  /**
   * The character rows whose attack runs on to a hit with no target, with the shipped global and no
   * burst: an attack centred on the unit itself, or a projectile that does not home. Every other
   * row stops. The standard game's own answers for every shipped row.
   */
  private static final Set<String> RUN_ON =
      Set.of(
          "Valkyrie",
          "Bomber",
          "Princess",
          "Bowler",
          "AxeMan",
          "Hunter",
          "Wallbreaker",
          "EliteArcher",
          "Firecracker",
          "SuperEliteArcher",
          "SuperArcher",
          "Wallbreaker_mini",
          "EliteArcher_Chess",
          "Bomber_Chess",
          "BlowdartGoblin_crazy_2",
          "Wizard_crazy_2",
          "Princess_crazy_2",
          "Princess_crazy_3",
          "Hunter_crazy_2",
          "Hunter_crazy_3",
          "Xbow_crazy_2",
          "AxeMan_crazy_1",
          "AxeMan_crazy_2",
          "AxeMan_crazy_3",
          "BombTower_crazy_2",
          "BombTower_crazy_3",
          "RoyalGiant_crazy_1",
          "RoyalGiant_crazy_3",
          "Mortar_crazy_1",
          "Mortar_crazy_2",
          "Mortar_crazy_3",
          "BabyDragon_crazy_2",
          "RamRider_crazy",
          "Musketeer_crazy_3",
          "GoblinDemolisher",
          "Hunter_EV1",
          "AxeMan_EV1",
          "AxeMan_Small",
          "Furnace_rework",
          "Furnace_EV1",
          "EliteArcherHero",
          "Furnace_rework_crazy_1",
          "Furnace_rework_crazy_3",
          "GoblinDemolisher_crazy_1",
          "GoblinDemolisher_crazy_2",
          "GoblinDemolisher_crazy_3",
          "Hunter_crazy_1",
          "Valkyrie_EV1",
          "Bomber_EV1",
          "Wallbreaker_EV1",
          "Firecracker_EV1");

  /** The character rows the records build, of the 387 shipped: all but ChefTowerKing. */
  private static final int BUILT = 386;

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
    assertThat(rows).hasSize(BUILT);
    List<String> runOn = new ArrayList<>();
    for (UnitData row : rows) {
      if (!WorldEntity.stopsWithoutTarget(row, 0, true)) {
        runOn.add(row.name());
      }
    }
    assertThat(runOn).containsExactlyInAnyOrderElementsOf(RUN_ON);
  }

  @Test
  @DisplayName("a running burst runs on, and without the global every row stops")
  void theBurstAndTheGlobal() {
    for (UnitData row : characters()) {
      assertThat(WorldEntity.stopsWithoutTarget(row, 50, true)).as(row.name()).isFalse();
      assertThat(WorldEntity.stopsWithoutTarget(row, 0, false)).as(row.name()).isTrue();
      assertThat(WorldEntity.stopsWithoutTarget(row, 50, false)).as(row.name()).isTrue();
    }
    assertThat(GameData.records().globalBoolean("ALLOW_AOE_ATTACKS_WITHOUT_TARGET")).isTrue();
  }
}
