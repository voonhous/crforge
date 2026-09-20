package org.crforge.core.fidelity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.fidelity.FidelityStatus.GUESS;
import static org.crforge.core.fidelity.FidelityStatus.PARTIAL;
import static org.crforge.core.fidelity.FidelityStatus.TRACED;
import static org.crforge.core.fidelity.FidelityStatus.UNASSESSED;

import java.util.List;
import org.crforge.core.fidelity.FidelityLedger.Entry;
import org.junit.jupiter.api.Test;

class FidelityLedgerTest {

  private static Entry entry(String name, FidelityStatus status, String note) {
    return new Entry("org.crforge.core." + name, status, note);
  }

  /**
   * The guard that keeps the ledger honest. An unannotated class is safe: it reads as a guess,
   * which is the assumption we want by default. What is not safe is claiming something is settled
   * without saying on what basis, because that is what someone else would build on.
   */
  @Test
  void establishedClaimsMustBeJustified() {
    List<Entry> offenders = FidelityLedger.unjustified(FidelityLedger.scan());

    assertThat(offenders)
        .as("TRACED/PARTIAL without a note -- say what it is settled against, or drop the status")
        .isEmpty();
  }

  @Test
  void unjustifiedFlagsOnlyUpgradedStatusesWithoutNotes() {
    List<Entry> entries =
        List.of(
            entry("Traced", TRACED, "pinned by fixtures"),
            entry("TracedBare", TRACED, ""),
            entry("PartialBare", PARTIAL, "   "),
            entry("GuessBare", GUESS, ""),
            entry("Unassessed", UNASSESSED, ""));

    assertThat(FidelityLedger.unjustified(entries))
        .extracting(Entry::simpleName)
        .containsExactlyInAnyOrder("TracedBare", "PartialBare");
  }

  /** Every class is in scope, so nothing can be missed by naming it the wrong way. */
  @Test
  void scanCoversClassesRegardlessOfNaming() {
    List<String> scanned = FidelityLedger.scan().stream().map(Entry::className).toList();

    assertThat(scanned)
        .contains(
            "org.crforge.core.util.FormationHelper",
            "org.crforge.core.util.Vector2",
            "org.crforge.core.component.Position",
            "org.crforge.core.engine.AreaEffectFactory")
        .doesNotHaveDuplicates();
  }

  /** The ledger does not report on itself. */
  @Test
  void scanExcludesTheLedgersOwnPackage() {
    assertThat(FidelityLedger.scan())
        .extracting(Entry::className)
        .noneMatch(name -> name.startsWith("org.crforge.core.fidelity."));
  }

  @Test
  void unannotatedClassesReadAsUnassessed() {
    Entry vector2 =
        FidelityLedger.scan().stream()
            .filter(e -> e.className().equals("org.crforge.core.util.Vector2"))
            .findFirst()
            .orElseThrow();

    assertThat(vector2.status()).isEqualTo(UNASSESSED);
    assertThat(vector2.isAssessed()).isFalse();
  }

  @Test
  void annotatedClassesCarryTheirStatusAndNote() {
    Entry formationHelper =
        FidelityLedger.scan().stream()
            .filter(e -> e.className().equals("org.crforge.core.util.FormationHelper"))
            .findFirst()
            .orElseThrow();

    assertThat(formationHelper.status()).isEqualTo(PARTIAL);
    assertThat(formationHelper.note()).contains("mirroring");
    assertThat(formationHelper.isAssessed()).isTrue();
  }

  /** Unassessed classes are a count by default; listing all of them would bury the real entries. */
  @Test
  void unassessedClassesAreListedOnlyOnRequest() {
    List<Entry> entries =
        List.of(entry("Traced", TRACED, "pinned"), entry("util.Vector2", UNASSESSED, ""));

    assertThat(FidelityLedger.render(entries, false)).doesNotContain("Vector2");
    assertThat(FidelityLedger.render(entries, true)).contains("Vector2");
  }

  @Test
  void reportShowsCountsForEveryStatus() {
    String report = FidelityLedger.render(FidelityLedger.scan(), false);

    assertThat(report).startsWith("Fidelity ledger -- org.crforge.core");
    assertThat(report).contains("TRACED").contains("PARTIAL").contains("GUESS").contains("total");
  }

  @Test
  void renderHandlesAnEmptyLedger() {
    assertThat(FidelityLedger.render(List.of(), true)).contains("total").contains("  0");
  }
}
