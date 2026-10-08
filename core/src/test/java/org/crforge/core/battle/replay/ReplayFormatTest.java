package org.crforge.core.battle.replay;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.battle.data.GameVersions;
import org.junit.jupiter.api.Test;

/**
 * The formats each data version's replays and generated cases are read by. The fields are the game
 * client's, so every data version a client runs is read by the same fields, whichever tables are
 * configured.
 */
class ReplayFormatTest {

  @Test
  void readsEveryDataVersionOfAClientsReplaysByTheSameFields() {
    for (String version : GameVersions.CLIENT_16_402_17_DATA) {
      ReplayFormat format = ReplayFormat.of(version).orElseThrow();

      assertThat(format.dataVersion()).isEqualTo(version);
      assertThat(format)
          .usingRecursiveComparison()
          .ignoringFields("dataVersion")
          .isEqualTo(ReplayFormat.V16_402_18);
    }
  }

  @Test
  void readsEveryDataVersionOfAClientsGeneratedCasesByTheSameFields() {
    // A generated case is written in 14.593.1's replay shape whatever its version, and the client
    // reads it the same on every data version it runs.
    for (String version : GameVersions.CLIENT_16_402_17_DATA) {
      ReplayFormat format = ReplayFormat.generated(version).orElseThrow();

      assertThat(format.dataVersion()).isEqualTo(version);
      assertThat(format)
          .usingRecursiveComparison()
          .ignoringFields("dataVersion")
          .isEqualTo(ReplayFormat.V14_593_1);
    }
  }

  @Test
  void readsTheOldestVersionsGeneratedCasesInItsOwnReplayShape() {
    assertThat(ReplayFormat.generated(GameVersions.DATA_14_593_1)).contains(ReplayFormat.V14_593_1);
  }

  @Test
  void hasNoGeneratedCaseFieldsForADataVersionNoClientIsKnownToRun() {
    assertThat(ReplayFormat.generated("9.1.0")).isEmpty();
    assertThat(ReplayFormat.generated(null)).isEmpty();
  }
}
