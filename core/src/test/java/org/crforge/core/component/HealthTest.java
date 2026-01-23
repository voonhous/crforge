package org.crforge.core.component;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HealthTest {

  @Test
  void newHealth_shouldStartAtMax() {
    Health health = new Health(100);

    assertThat(health.getCurrent()).isEqualTo(100);
    assertThat(health.getMax()).isEqualTo(100);
    assertThat(health.isAlive()).isTrue();
  }

  @Test
  void takeDamage_shouldReduceHealth() {
    Health health = new Health(100);

    int actualDamage = health.takeDamage(30);

    assertThat(actualDamage).isEqualTo(30);
    assertThat(health.getCurrent()).isEqualTo(70);
    assertThat(health.isAlive()).isTrue();
  }

  @Test
  void takeDamage_shouldNotGoBelowZero() {
    Health health = new Health(100);

    int actualDamage = health.takeDamage(150);

    assertThat(actualDamage).isEqualTo(100);
    assertThat(health.getCurrent()).isEqualTo(0);
    assertThat(health.isAlive()).isFalse();
    assertThat(health.isDead()).isTrue();
  }

  @Test
  void shield_shouldAbsorbDamageFirst() {
    Health health = new Health(100, 50);

    int actualDamage = health.takeDamage(30);

    assertThat(actualDamage).isEqualTo(0);
    assertThat(health.getShield()).isEqualTo(20);
    assertThat(health.getCurrent()).isEqualTo(100);
  }

  @Test
  void shield_shouldOverflowToHealth() {
    Health health = new Health(100, 50);

    int actualDamage = health.takeDamage(80);

    assertThat(actualDamage).isEqualTo(30);
    assertThat(health.getShield()).isEqualTo(0);
    assertThat(health.getCurrent()).isEqualTo(70);
  }

  @Test
  void heal_shouldRestoreHealth() {
    Health health = new Health(100);
    health.takeDamage(50);

    health.heal(30);

    assertThat(health.getCurrent()).isEqualTo(80);
  }

  @Test
  void heal_shouldNotExceedMax() {
    Health health = new Health(100);
    health.takeDamage(20);

    health.heal(50);

    assertThat(health.getCurrent()).isEqualTo(100);
  }

  @Test
  void percentage_shouldReturnCorrectValue() {
    Health health = new Health(100);
    health.takeDamage(25);

    assertThat(health.percentage()).isEqualTo(0.75f);
  }
}
