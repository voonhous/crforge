package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.crforge.core.battle.GameData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The hits of several targets and the buffs on damage that no reference holds, refused. */
class BattleMultipleTargetsTest {

  /**
   * An Electro Wizard, its row changed as asked, standing at a Knight: its first hit is refused.
   *
   * @param change a third target, a list of unique targets, or its buff on damage over an area
   */
  @ParameterizedTest
  @ValueSource(strings = {"third target", "unique targets", "area buff"})
  @DisplayName("a third target, unique targets and a buff on damage over an area are refused")
  void theHitIsRefused(String change) {
    UnitData wizard = GameData.unit("ElectroWizard");
    UnitData changed =
        switch (change) {
          case "third target" -> wizard.toBuilder().multipleTargets(3).build();
          case "unique targets" -> wizard.toBuilder().uniqueMultipleTargets(true).build();
          default -> wizard.toBuilder().areaDamageRadius(1000).build();
        };
    Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
    match.deploy(0, changed, Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 12000, "Wizard");
    match.deploy(0, GameData.unit("Knight"), Standard1v1Battle.DEFAULT_LEVEL, 1, 3500, 16000);

    assertThatThrownBy(
            () -> {
              for (int tick = 0; tick < 200; tick++) {
                match.getBattle().step();
              }
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("Wizard hits with MultipleTargets");
  }
}
