package org.crforge.core.entity.base;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.card.TroopStats;
import org.crforge.core.effect.AppliedEffect;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AbstractEntityTest {

  private Troop entity;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    // Using Troop as a concrete implementation of AbstractEntity for testing
    entity = Troop.builder()
        .name("TestTroop")
        .team(Team.BLUE)
        .build();
  }

  @Test
  void addEffect_shouldAddNewEffect() {
    AppliedEffect effect = new AppliedEffect(StatusEffectType.SLOW, 5.0f, "TestSlow");

    entity.addEffect(effect);

    assertThat(entity.getAppliedEffects()).hasSize(1);
    assertThat(entity.getAppliedEffects().get(0)).isEqualTo(effect);
    assertThat(entity.getAppliedEffects().get(0).getRemainingDuration()).isEqualTo(5.0f);
  }

  @Test
  void addEffect_shouldRefreshExistingEffect() {
    // Add initial effect: 2.0s duration
    AppliedEffect initialEffect = new AppliedEffect(StatusEffectType.SLOW, 2.0f, "TestSlow");
    entity.addEffect(initialEffect);

    // Apply same effect type with longer duration (5.0s)
    AppliedEffect newEffect = new AppliedEffect(StatusEffectType.SLOW, 5.0f, "TestSlow");
    entity.addEffect(newEffect);

    // Should still have only 1 effect, but duration should be updated to 5.0s
    assertThat(entity.getAppliedEffects()).hasSize(1);
    assertThat(entity.getAppliedEffects().get(0).getType()).isEqualTo(StatusEffectType.SLOW);
    assertThat(entity.getAppliedEffects().get(0).getRemainingDuration()).isEqualTo(5.0f);
  }

  @Test
  void addEffect_shouldNotShortenDurationOnRefresh() {
    // Add initial effect: 5.0s duration
    AppliedEffect initialEffect = new AppliedEffect(StatusEffectType.SLOW, 5.0f, "TestSlow");
    entity.addEffect(initialEffect);

    // Apply same effect type with shorter duration (2.0s)
    AppliedEffect newEffect = new AppliedEffect(StatusEffectType.SLOW, 2.0f, "TestSlow");
    entity.addEffect(newEffect);

    // Duration should remain 5.0s (Math.max logic)
    assertThat(entity.getAppliedEffects()).hasSize(1);
    assertThat(entity.getAppliedEffects().get(0).getRemainingDuration()).isEqualTo(5.0f);
  }

  @Test
  void addEffect_shouldAllowDifferentTypesToStack() {
    // Add Slow
    AppliedEffect slow = new AppliedEffect(StatusEffectType.SLOW, 5.0f, "TestSlow");
    entity.addEffect(slow);

    // Add Poison
    AppliedEffect poison = new AppliedEffect(StatusEffectType.POISON, 5.0f, "TestPoison");
    entity.addEffect(poison);

    // Should have both
    assertThat(entity.getAppliedEffects()).hasSize(2);
    assertThat(entity.getAppliedEffects())
        .extracting(AppliedEffect::getType)
        .containsExactlyInAnyOrder(StatusEffectType.SLOW, StatusEffectType.POISON);
  }

  @Test
  void addEffect_shouldHandleCurseEffect() {
    TroopStats hogStats = TroopStats.builder().name("Voodoo Hog").build();
    AppliedEffect curse = new AppliedEffect(StatusEffectType.CURSE, 5.0f, (String) null, hogStats);

    entity.addEffect(curse);

    assertThat(entity.getAppliedEffects()).hasSize(1);
    AppliedEffect applied = entity.getAppliedEffects().get(0);
    assertThat(applied.getType()).isEqualTo(StatusEffectType.CURSE);
    assertThat(applied.getSpawnSpecies()).isEqualTo(hogStats);
  }
}
