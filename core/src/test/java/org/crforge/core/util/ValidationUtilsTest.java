package org.crforge.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.crforge.core.util.ValidationUtils.checkArgument;
import static org.crforge.core.util.ValidationUtils.checkState;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ValidationUtilsTest {

  @Test
  void checkArgumentPassesWhenConditionHolds() {
    assertThatCode(() -> checkArgument(true)).doesNotThrowAnyException();
    assertThatCode(() -> checkArgument(true, "unused")).doesNotThrowAnyException();
    assertThatCode(() -> checkArgument(true, () -> "unused")).doesNotThrowAnyException();
  }

  @Test
  void checkArgumentThrowsWithTheGivenMessage() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> checkArgument(false, "level must be >= 1"))
        .withMessage("level must be >= 1");
  }

  @Test
  void checkArgumentThrowsWithTheSuppliedMessage() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> checkArgument(false, () -> "level must be >= 1, got: " + 0))
        .withMessage("level must be >= 1, got: 0");
  }

  @Test
  void checkArgumentThrowsWithoutAMessageWhenNoneIsGiven() {
    assertThatIllegalArgumentException().isThrownBy(() -> checkArgument(false)).withMessage(null);
  }

  @Test
  void checkStatePassesWhenConditionHolds() {
    assertThatCode(() -> checkState(true)).doesNotThrowAnyException();
    assertThatCode(() -> checkState(true, "unused")).doesNotThrowAnyException();
    assertThatCode(() -> checkState(true, () -> "unused")).doesNotThrowAnyException();
  }

  @Test
  void checkStateThrowsWithTheGivenMessage() {
    assertThatIllegalStateException()
        .isThrownBy(() -> checkState(false, "Match not set"))
        .withMessage("Match not set");
  }

  @Test
  void checkStateThrowsWithTheSuppliedMessage() {
    assertThatIllegalStateException()
        .isThrownBy(() -> checkState(false, () -> "Circular chain detected: " + "Goblin"))
        .withMessage("Circular chain detected: Goblin");
  }

  @Test
  void checkStateThrowsWithoutAMessageWhenNoneIsGiven() {
    assertThatIllegalStateException().isThrownBy(() -> checkState(false)).withMessage(null);
  }

  /**
   * The supplier overloads exist so a passing check costs no string concatenation; a supplier that
   * runs on the happy path would defeat that.
   */
  @Test
  void messageSuppliersRunOnlyWhenTheCheckFails() {
    AtomicInteger argumentCalls = new AtomicInteger();
    checkArgument(true, () -> "built " + argumentCalls.incrementAndGet());
    assertThat(argumentCalls).hasValue(0);

    AtomicInteger stateCalls = new AtomicInteger();
    checkState(true, () -> "built " + stateCalls.incrementAndGet());
    assertThat(stateCalls).hasValue(0);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> checkArgument(false, () -> "built " + argumentCalls.incrementAndGet()));
    assertThat(argumentCalls).hasValue(1);

    assertThatIllegalStateException()
        .isThrownBy(() -> checkState(false, () -> "built " + stateCalls.incrementAndGet()));
    assertThat(stateCalls).hasValue(1);
  }
}
