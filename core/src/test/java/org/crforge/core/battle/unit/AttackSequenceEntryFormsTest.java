package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.data.GameVersions;
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
 *
 * <p>Run when the configured game tables are those of 16.402.18, or sit beside a folder of them as
 * in a checkout of the game data repository; skipped otherwise.
 */
class AttackSequenceEntryFormsTest {

  /** The data version whose forms these are. */
  private static final String VERSION = GameVersions.DATA_16_402_18;

  private static GameTables tables;

  @BeforeAll
  static void load() {
    Optional<Path> folder = folder();
    assumeTrue(folder.isPresent(), "no game tables of " + VERSION + " configured");
    tables = GameTables.load(folder.get());
    assertThat(tables.version()).isEqualTo(VERSION);
  }

  /** The configured folder when it is of the version, else a folder of the version beside it. */
  private static Optional<Path> folder() {
    Optional<Path> configured = GameTables.configuredDirectory();
    if (configured.isEmpty()) {
      return Optional.empty();
    }
    Path folder = configured.get().toAbsolutePath();
    if (folder.getFileName().toString().equals(VERSION)) {
      return Optional.of(folder);
    }
    Path beside = folder.resolveSibling(VERSION);
    return Files.isDirectory(beside) ? Optional.of(beside) : Optional.empty();
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
