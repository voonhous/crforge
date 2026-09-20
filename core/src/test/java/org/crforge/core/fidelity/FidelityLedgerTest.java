package org.crforge.core.fidelity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.fidelity.FidelityStatus.GUESS;
import static org.crforge.core.fidelity.FidelityStatus.PARTIAL;
import static org.crforge.core.fidelity.FidelityStatus.TRACED;

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
            entry("GuessNoted", GUESS, "hit speed inferred"));

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

  /** No "not looked at yet" state: code with nothing to point to is simply a guess. */
  @Test
  void unannotatedClassesReadAsGuesses() {
    Entry vector2 =
        FidelityLedger.scan().stream()
            .filter(e -> e.className().equals("org.crforge.core.util.Vector2"))
            .findFirst()
            .orElseThrow();

    assertThat(vector2.status()).isEqualTo(GUESS);
    assertThat(vector2.isNoteworthy()).isFalse();
  }

  @Test
  void annotatedClassesCarryTheirStatusAndNote() {
    Entry formationHelper =
        FidelityLedger.scan().stream()
            .filter(e -> e.className().equals("org.crforge.core.util.FormationHelper"))
            .findFirst()
            .orElseThrow();

    // Assert the annotation round-trips, not its wording: notes get reworded as understanding
    // improves, and a test that pins the prose only ever fails for the wrong reason.
    assertThat(formationHelper.status()).isEqualTo(PARTIAL);
    assertThat(formationHelper.note()).isNotBlank();
    assertThat(formationHelper.isNoteworthy()).isTrue();
  }

  /** A bare guess adds nothing to the default, so it is a count unless the caller asks for all. */
  @Test
  void bareGuessesAreListedOnlyOnRequest() {
    List<Entry> entries =
        List.of(
            entry("Traced", TRACED, "pinned"),
            entry("util.Vector2", GUESS, ""),
            entry("engine.AreaEffectFactory", GUESS, "buff precedence inferred"));

    String terse = FidelityLedger.render(entries, false);
    assertThat(terse).doesNotContain("Vector2").contains("AreaEffectFactory");
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
