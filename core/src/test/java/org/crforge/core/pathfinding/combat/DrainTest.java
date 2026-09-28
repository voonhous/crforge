package org.crforge.core.pathfinding.combat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A tiebreaker's drain step passes the battle's two holds, which refuse every ordinary hit: the
 * tiebreaker's, from its first step, and the end's.
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
}
