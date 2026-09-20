package org.crforge.core.fidelity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FidelityLedgerTest {

  /**
   * The guard that keeps the ledger honest: a new system cannot be added without stating how well
   * its behaviour is known. If this fails, annotate the named class rather than relaxing the test.
   */
  @Test
  void everySimulationClassDeclaresItsFidelity() {
    List<String> undeclared =
        FidelityLedger.scan().stream()
            .filter(entry -> entry.status() == FidelityStatus.UNDECLARED)
            .map(FidelityLedger.Entry::className)
            .toList();

    assertThat(undeclared)
        .as("simulation classes missing @Fidelity -- add one stating what is known")
        .isEmpty();
  }

  @Test
  void scanFindsTheKnownSystems() {
    List<String> scanned =
        FidelityLedger.scan().stream().map(FidelityLedger.Entry::className).toList();

    assertThat(scanned)
        .contains(
            "org.crforge.core.combat.CombatSystem",
            "org.crforge.core.physics.PhysicsSystem",
            "org.crforge.core.engine.TransformationSystem")
        .doesNotHaveDuplicates();
  }

  /** Only systems and services are tracked; components and utilities would be noise. */
  @Test
  void onlySystemsAndServicesAreInScope() {
    assertThat(FidelityLedger.isSimulationClass("org.crforge.core.combat.CombatSystem")).isTrue();
    assertThat(FidelityLedger.isSimulationClass("org.crforge.core.combat.AoeDamageService"))
        .isTrue();
    assertThat(FidelityLedger.isSimulationClass("org.crforge.core.util.GameUnits")).isFalse();
    assertThat(FidelityLedger.isSimulationClass("org.crforge.core.component.Health")).isFalse();
  }

  /** Nothing is TRACED yet, so a passing report is not evidence the simulator is correct. */
  @Test
  void reportShowsCountsAndGroupsByStatus() {
    String report = FidelityLedger.render(FidelityLedger.scan());

    assertThat(report).startsWith("Fidelity ledger -- org.crforge.core");
    assertThat(report).contains("GUESS").contains("total");
  }

  @Test
  void renderHandlesAnEmptyLedger() {
    String report = FidelityLedger.render(List.of());

    assertThat(report).contains("total").contains("  0");
  }
}
