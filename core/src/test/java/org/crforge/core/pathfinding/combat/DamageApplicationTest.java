package org.crforge.core.pathfinding.combat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** One damage event reaching a hit-points object: the guards, the amount and the subtraction. */
class DamageApplicationTest {

  private HitPoints hitPoints;

  @BeforeEach
  void setUp() {
    hitPoints = new HitPoints(1000);
  }

  /** Deals plain damage with the standard answers and no dedupe id. */
  private DamageResult damage(int amount) {
    return DamageApplication.damage(hitPoints, amount, 0, 0, 0, DamageQueries.STANDARD);
  }

  @Test
  @DisplayName("a hit lowers the hit points by what it deals")
  void aHitLowersTheHitPoints() {
    DamageResult result = damage(202);

    assertThat(result).isEqualTo(new DamageResult(true, 202, false));
    assertThat(hitPoints.getHitPoints()).isEqualTo(798);
    assertThat(hitPoints.alive()).isTrue();
  }

  @Test
  @DisplayName("a killing hit leaves zero, reports only what was left and says the target died")
  void aKillingHitClampsToZero() {
    damage(900);

    DamageResult result =
        DamageApplication.damage(hitPoints, 500, 0, 0, -1000, DamageQueries.STANDARD);

    assertThat(result.died()).isTrue();
    assertThat(result.applied()).as("the overkill is not part of what was lost").isEqualTo(100);
    assertThat(hitPoints.getHitPoints()).isZero();
    assertThat(hitPoints.alive()).isFalse();
    assertThat(hitPoints.getLastHitHeading())
        .as("the heading of the hit that killed is stored")
        .isNotZero();
  }

  @Test
  @DisplayName("an exact killing hit says the target died without any overkill")
  void anExactKillingHit() {
    DamageResult result = damage(1000);

    assertThat(result).isEqualTo(new DamageResult(true, 1000, true));
    assertThat(hitPoints.getHitPoints()).isZero();
  }

  @Test
  @DisplayName("a shield takes the damage first and the hit points take nothing")
  void aShieldTakesTheDamageFirst() {
    hitPoints.setShield(300);

    assertThat(damage(200)).isEqualTo(new DamageResult(true, 200, false));
    assertThat(hitPoints.getShield()).isEqualTo(100);
    assertThat(hitPoints.getHitPoints()).isEqualTo(1000);

    // Whatever a breaking shield cannot absorb is lost with it.
    assertThat(damage(400)).isEqualTo(new DamageResult(true, 100, false));
    assertThat(hitPoints.getShield()).isZero();
    assertThat(hitPoints.getHitPoints()).isEqualTo(1000);
  }

  @Test
  @DisplayName("damage below one lands nothing")
  void damageBelowOneLandsNothing() {
    assertThat(damage(0)).isEqualTo(DamageResult.NOTHING);
    assertThat(damage(-5)).isEqualTo(DamageResult.NOTHING);
    assertThat(hitPoints.getHitPoints()).isEqualTo(1000);
  }

  @Test
  @DisplayName("a target that takes no damage, is immune or cannot be touched takes nothing")
  void guardedTargetsTakeNothing() {
    DamageQueries forbidden =
        new DamageQueries() {
          @Override
          public boolean damageForbidden() {
            return true;
          }
        };
    DamageQueries immune =
        new DamageQueries() {
          @Override
          public boolean immune() {
            return true;
          }
        };
    DamageQueries untouchable =
        new DamageQueries() {
          @Override
          public boolean untouchable() {
            return true;
          }
        };

    for (DamageQueries queries : new DamageQueries[] {forbidden, immune, untouchable}) {
      assertThat(DamageApplication.damage(hitPoints, 202, 0, 0, 0, queries))
          .isEqualTo(DamageResult.NOTHING);
    }
    assertThat(hitPoints.getHitPoints()).isEqualTo(1000);
  }

  @Test
  @DisplayName("a held battle and an ended battle both stop the damage")
  void aHeldBattleStopsTheDamage() {
    DamageQueries held =
        new DamageQueries() {
          @Override
          public boolean damageHeld() {
            return true;
          }
        };
    DamageQueries ended =
        new DamageQueries() {
          @Override
          public boolean battleEnded() {
            return true;
          }
        };

    assertThat(DamageApplication.damage(hitPoints, 202, 0, 0, 0, held))
        .isEqualTo(DamageResult.NOTHING);
    assertThat(DamageApplication.damage(hitPoints, 202, 0, 0, 0, ended))
        .isEqualTo(DamageResult.NOTHING);
    assertThat(hitPoints.getHitPoints()).isEqualTo(1000);
  }

  @Test
  @DisplayName("a source with a dedupe id lands once, and a repeat only moves its tick on")
  void aDedupedSourceLandsOnce() {
    DamageQueries atTick =
        new DamageQueries() {
          @Override
          public int battleTick() {
            return 7;
          }
        };

    assertThat(DamageApplication.damage(hitPoints, 202, 42, 0, 0, atTick).landed()).isTrue();
    assertThat(hitPoints.dedupeIds()).containsExactly(42);
    assertThat(hitPoints.dedupeTick(42)).isEqualTo(7);

    DamageQueries later =
        new DamageQueries() {
          @Override
          public int battleTick() {
            return 9;
          }
        };
    assertThat(DamageApplication.damage(hitPoints, 202, 42, 0, 0, later))
        .isEqualTo(DamageResult.NOTHING);
    assertThat(hitPoints.getHitPoints()).as("the repeat takes nothing").isEqualTo(798);
    assertThat(hitPoints.dedupeTick(42)).as("only the tick moves on").isEqualTo(9);
  }

  @Test
  @DisplayName("the attacker's percentages scale the damage, the second only on a crown tower")
  void theAttackersPercentagesScale() {
    DamageQueries halved =
        new DamageQueries() {
          @Override
          public int attackerDamagePercent() {
            return 50;
          }

          @Override
          public int attackerCrownTowerPercent() {
            return 30;
          }
        };
    DamageQueries againstCrownTower =
        new DamageQueries() {
          @Override
          public int attackerDamagePercent() {
            return 50;
          }

          @Override
          public int attackerCrownTowerPercent() {
            return 30;
          }

          @Override
          public boolean crownTowerTarget() {
            return true;
          }
        };

    assertThat(DamageApplication.damage(hitPoints, 201, 0, 0, 0, halved).applied()).isEqualTo(100);
    assertThat(
            DamageApplication.damage(new HitPoints(1000), 201, 0, 0, 0, againstCrownTower)
                .applied())
        .as("truncated at each step: 201 -> 100 -> 30")
        .isEqualTo(30);
  }

  @Test
  @DisplayName("a buff that would take all the damage away still leaves one hit point of it")
  void theDamageIsFlooredAtOne() {
    DamageQueries noDamage =
        new DamageQueries() {
          @Override
          public int modifyDamage(int damage) {
            return 0;
          }
        };

    assertThat(DamageApplication.damage(hitPoints, 202, 0, 0, 0, noDamage).applied()).isEqualTo(1);
    assertThat(hitPoints.getHitPoints()).isEqualTo(999);
  }

  @Test
  @DisplayName("a target's own buffs may add damage on top")
  void theTargetsBuffsMayAddDamage() {
    DamageQueries extra =
        new DamageQueries() {
          @Override
          public int extraDamage() {
            return 50;
          }
        };

    assertThat(DamageApplication.damage(hitPoints, 202, 0, 0, 0, extra).applied()).isEqualTo(252);
  }

  @Test
  @DisplayName("an object that is already dead accepts the event and loses nothing more")
  void anAlreadyDeadObjectLosesNothing() {
    damage(1000);

    DamageResult result = damage(202);

    assertThat(result).isEqualTo(new DamageResult(true, 0, false));
    assertThat(hitPoints.getHitPoints()).isZero();
  }
}
