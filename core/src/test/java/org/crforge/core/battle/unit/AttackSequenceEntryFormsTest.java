package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The rows of data version 16.402.18 whose attack sequence entries set more than their damage and
 * projectile: the evolved Princess (a custom first projectile per entry), the hero Bowler (a launch
 * start, a sight range and a pace per entry) and the hero Electro Wizard (a number of targets, a
 * remembered list, an attack action and a start delay per entry, and a column only its animation
 * reads). Each is read and made into a unit.
 */
class AttackSequenceEntryFormsTest {

  private static GameTables tables;

  @BeforeAll
  static void load() {
    tables = GameData.tables();
  }

  @ParameterizedTest
  @ValueSource(strings = {"Princess_EV1", "BowlerHero", "ElectroWizardHero"})
  @DisplayName("the row's attack sequence is read and the unit is made")
  void theRowIsMade(String row) {
    Standard1v1Battle battle = new Standard1v1Battle(tables);
    UnitData unit = battle.getWorld().getRecords().unit(row);
    assertThat(unit.attackSequence().order()).hasSizeGreaterThanOrEqualTo(2);

    CharacterEntity made = new CharacterEntity(battle.getWorld(), unit, row, 0, 3500, 10000, 11);

    assertThat(made.attackSequenceIndex()).isZero();
  }
}
