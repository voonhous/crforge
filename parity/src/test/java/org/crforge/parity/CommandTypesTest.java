package org.crforge.parity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The command type numbers of each data version, established from that version's replays. */
class CommandTypesTest {

  @Test
  void dataVersion14_593_1PlaysWith124AndTapsAbilitiesWith178() {
    CommandTypes types = CommandTypes.of("14.593.1").orElseThrow();

    assertThat(types.play()).isEqualTo(124);
    assertThat(types.ability()).isEqualTo(178);
    assertThat(types.dataVersion()).isEqualTo("14.593.1");
  }

  @Test
  void dataVersion16_402_18PlaysWith153AndTapsAbilitiesWith189() {
    CommandTypes types = CommandTypes.of("16.402.18").orElseThrow();

    assertThat(types.play()).isEqualTo(153);
    assertThat(types.ability()).isEqualTo(189);
    // The numbers of 14.593.1 are no command of this version.
    assertThat(types.describe(124)).isNull();
    assertThat(types.describe(178)).isNull();
  }

  @Test
  void dataVersion16_426_22OfTheSameClientPlaysWith153AndTapsAbilitiesWith189() {
    // Client 16.402.17 ran 16.402.18 and then 16.426.22: the numbers are the client's.
    CommandTypes types = CommandTypes.of("16.426.22").orElseThrow();

    assertThat(types.play()).isEqualTo(153);
    assertThat(types.ability()).isEqualTo(189);
    assertThat(types.dataVersion()).isEqualTo("16.426.22");
    assertThat(types.describe(124)).isNull();
  }

  @Test
  void aDataVersionWhoseCommandTypesAreNotEstablishedHasNone() {
    assertThat(CommandTypes.of("9.1.0")).isEmpty();
    assertThat(CommandTypes.of(null)).isEmpty();
  }

  @Test
  void namesWhatEachTypeIs() {
    CommandTypes types = CommandTypes.of("14.593.1").orElseThrow();

    assertThat(types.describe(124)).isEqualTo("a card play");
    assertThat(types.describe(178)).isEqualTo("an ability command");
    assertThat(types.describe(153)).isNull();
  }
}
