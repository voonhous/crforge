package org.crforge.core.battle.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A damage type's pipeline, as far as the battle models it: a target that takes no damage takes
 * none, the four stages run behind their switches, and with no buffs the protection and the
 * multiplier only floor the amount at zero and the on-hit damage adds nothing.
 */
class DamageTypeTest {

  private static DamageType unscaled() {
    return DamageType.builder().name("D").enableLevelScaling(false).build();
  }

  @Test
  @DisplayName("every switch is on by default, the damage id included")
  void defaults() {
    DamageType type = DamageType.builder().name("D").build();
    assertThat(type.enableLevelScaling()).isTrue();
    assertThat(type.enableProtection()).isTrue();
    assertThat(type.enableDamageMultiplier()).isTrue();
    assertThat(type.enableDamageOnHit()).isTrue();
    assertThat(type.acquireDamageId()).isTrue();
  }

  @Test
  @DisplayName("a target that takes no damage takes none; with no buffs the amount passes")
  void pipeline() {
    assertThat(unscaled().pipeline(120, true, true)).isZero();
    assertThat(unscaled().pipeline(120, false, true)).isEqualTo(120);
    assertThat(unscaled().pipeline(120, false, false)).isEqualTo(120);
    assertThat(unscaled().pipeline(-5, false, true)).as("floored at zero").isZero();
    DamageType bare =
        unscaled().toBuilder().enableProtection(false).enableDamageMultiplier(false).build();
    assertThat(bare.pipeline(-5, false, true)).as("nothing floors it").isEqualTo(-5);
  }

  @Test
  @DisplayName("a type that scales by level is refused: that stage is not established")
  void levelScalingIsRefused() {
    assertThatThrownBy(() -> DamageType.builder().name("D").build().pipeline(120, false, true))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
