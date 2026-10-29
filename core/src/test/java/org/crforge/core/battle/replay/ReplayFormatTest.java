/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.replay;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
  void readsEveryDataVersionOfClient16_635_1sReplaysBy16_402_18sFieldsAndTheExcludedEvolutions() {
    for (String version : GameVersions.CLIENT_16_635_1_DATA) {
      ReplayFormat format = ReplayFormat.of(version).orElseThrow();

      assertThat(format.dataVersion()).isEqualTo(version);
      assertThat(format)
          .usingRecursiveComparison()
          .ignoringFields("dataVersion")
          .isEqualTo(ReplayFormat.V16_635_1);
      assertThat(format.excludedEvolutions()).isTrue();
      assertThat(format.ladderEvents()).isTrue();
      // Beside the excluded evolutions, the Ladder battles under an event and seb, the fields of
      // client 16.402.17's replays.
      assertThat(format)
          .usingRecursiveComparison()
          .ignoringFields(
              "dataVersion",
              "excludedEvolutions",
              "ladderEvents",
              "battlePins",
              "battleCarried",
              "playerData")
          .isEqualTo(ReplayFormat.V16_402_18);
      // The card level minimum is read and seb carried, neither pinned any more; the trail progress
      // is carried.
      Map<String, String> pins = new LinkedHashMap<>(ReplayFormat.V16_402_18.battlePins());
      pins.remove(ReplayFormat.CARD_LEVEL_MIN_FIELD);
      pins.remove(ReplayFormat.SEB_FIELD);
      assertThat(format.battlePins()).isEqualTo(pins);
      assertThat(format.battleCarried())
          .containsAllEntriesOf(ReplayFormat.V16_402_18.battleCarried())
          .containsKey(ReplayFormat.SEB_FIELD)
          .hasSize(ReplayFormat.V16_402_18.battleCarried().size() + 1);
      assertThat(format.playerData())
          .containsExactlyElementsOf(
              with(ReplayFormat.V16_402_18.playerData(), ReplayFormat.TRAIL_PROGRESS_FIELD));
    }
    assertThat(ReplayFormat.V16_402_18.excludedEvolutions()).isFalse();
    assertThat(ReplayFormat.V14_593_1.excludedEvolutions()).isFalse();
    assertThat(ReplayFormat.V16_402_18.ladderEvents()).isFalse();
    assertThat(ReplayFormat.V14_593_1.ladderEvents()).isFalse();
  }

  /** A list with one more element. */
  private static List<String> with(List<String> list, String more) {
    List<String> all = new ArrayList<>(list);
    all.add(more);
    return all;
  }

  @Test
  void hasNoGeneratedCaseFieldsForClient16_635_1UntilRecordedBattlesEstablishThem() {
    for (String version : GameVersions.CLIENT_16_635_1_DATA) {
      assertThat(ReplayFormat.generated(version)).isEmpty();
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
