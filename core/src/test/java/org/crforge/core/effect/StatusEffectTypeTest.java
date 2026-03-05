package org.crforge.core.effect;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StatusEffectTypeTest {

  @Test
  void fromBuffName_shouldMapZapFreezeToStun() {
    assertThat(StatusEffectType.fromBuffName("ZapFreeze")).isEqualTo(StatusEffectType.STUN);
  }

  @Test
  void fromBuffName_shouldMapIceWizardSlowDownToSlow() {
    assertThat(StatusEffectType.fromBuffName("IceWizardSlowDown")).isEqualTo(StatusEffectType.SLOW);
  }

  @Test
  void fromBuffName_shouldMapIceWizardColdToSlow() {
    assertThat(StatusEffectType.fromBuffName("IceWizardCold")).isEqualTo(StatusEffectType.SLOW);
  }

  @Test
  void fromBuffName_shouldMapFreezeToFreeze() {
    assertThat(StatusEffectType.fromBuffName("Freeze")).isEqualTo(StatusEffectType.FREEZE);
  }

  @Test
  void fromBuffName_shouldMapPoisonToPoison() {
    assertThat(StatusEffectType.fromBuffName("Poison")).isEqualTo(StatusEffectType.POISON);
  }

  @Test
  void fromBuffName_shouldMapRageToRage() {
    assertThat(StatusEffectType.fromBuffName("Rage")).isEqualTo(StatusEffectType.RAGE);
  }

  @Test
  void fromBuffName_shouldMapVoodooCurseToCurse() {
    assertThat(StatusEffectType.fromBuffName("VoodooCurse")).isEqualTo(StatusEffectType.CURSE);
  }

  @Test
  void fromBuffName_shouldMapEarthquake() {
    assertThat(StatusEffectType.fromBuffName("Earthquake")).isEqualTo(StatusEffectType.EARTHQUAKE);
  }

  @Test
  void fromBuffName_shouldMapTornado() {
    assertThat(StatusEffectType.fromBuffName("Tornado")).isEqualTo(StatusEffectType.TORNADO);
  }

  @Test
  void fromBuffName_shouldReturnNullForUnknown() {
    assertThat(StatusEffectType.fromBuffName("SomeNewBuff")).isNull();
  }

  @Test
  void fromBuffName_shouldReturnNullForNull() {
    assertThat(StatusEffectType.fromBuffName(null)).isNull();
  }
}
