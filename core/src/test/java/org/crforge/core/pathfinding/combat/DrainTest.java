/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.pathfinding.combat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A tiebreaker's drain step passes the battle's two holds, which refuse every ordinary hit: the
 * tiebreaker's, from its first step, and the end's. A Kamikaze unit's drain over its time passes
 * them too, but is refused where damage is forbidden.
 */
class DrainTest {

  /** The tiebreaker's hold. */
  private static final DamageQueries TIEBREAKER =
      new DamageQueries() {
        @Override
        public boolean damageHeld() {
          return true;
        }
      };

  /** A match that has ended. */
  private static final DamageQueries ENDED =
      new DamageQueries() {
        @Override
        public boolean battleEnded() {
          return true;
        }
      };

  @Test
  @DisplayName("a drain step lands under both holds, where an ordinary hit is refused")
  void theDrainPassesTheHolds() {
    for (DamageQueries holds : new DamageQueries[] {TIEBREAKER, ENDED}) {
      HitPoints hp = new HitPoints(3052);
      assertThat(DamageApplication.damage(hp, 400, 0, 0, 0, holds).landed()).isFalse();
      assertThat(hp.getHitPoints()).isEqualTo(3052);

      DamageResult step = DamageApplication.drain(hp, 50, holds);
      assertThat(step.landed()).isTrue();
      assertThat(hp.getHitPoints()).isEqualTo(3002);
    }
  }

  @Test
  @DisplayName("the step that takes the last hit points kills, and an untouchable target is spared")
  void theLastStepKills() {
    HitPoints last = new HitPoints(3052);
    last.setHitPoints(1);
    assertThat(DamageApplication.drain(last, 1, TIEBREAKER).died()).isTrue();
    assertThat(last.getHitPoints()).isZero();

    HitPoints spared = new HitPoints(3052);
    DamageQueries untouchable =
        new DamageQueries() {
          @Override
          public boolean untouchable() {
            return true;
          }
        };
    assertThat(DamageApplication.drain(spared, 50, untouchable).landed()).isFalse();
    assertThat(spared.getHitPoints()).isEqualTo(3052);
  }

  @Test
  @DisplayName(
      "a Kamikaze drain passes both holds as a tiebreaker's does, but not a target that takes no"
          + " damage")
  void aKamikazeDrainIsRefusedWhereDamageIsForbidden() {
    for (DamageQueries holds : new DamageQueries[] {TIEBREAKER, ENDED}) {
      HitPoints hp = new HitPoints(532);
      assertThat(DamageApplication.kamikazeDrain(hp, 53, holds).landed()).isTrue();
      assertThat(hp.getHitPoints()).isEqualTo(479);
    }
    HitPoints forbidden = new HitPoints(532);
    DamageQueries noDamage =
        new DamageQueries() {
          @Override
          public boolean damageForbidden() {
            return true;
          }
        };
    assertThat(DamageApplication.kamikazeDrain(forbidden, 53, noDamage).landed()).isFalse();
    assertThat(forbidden.getHitPoints()).isEqualTo(532);
    // The tiebreaker's drain does not ask.
    assertThat(DamageApplication.drain(forbidden, 53, noDamage).landed()).isTrue();
  }
}
