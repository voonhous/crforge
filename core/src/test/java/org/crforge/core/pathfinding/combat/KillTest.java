package org.crforge.core.pathfinding.combat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A kill is an ordinary hit of the whole hit points that ignores the battle's holds: a shield that
 * is up takes it, and otherwise the object dies.
 */
class KillTest {

  /** Queries under which an ordinary hit would be refused: the battle holds and has ended. */
  private static final DamageQueries HELD =
      new DamageQueries() {
        @Override
        public boolean damageHeld() {
          return true;
        }

        @Override
        public boolean battleEnded() {
          return true;
        }
      };

  private static HitPoints hitPoints(int current, int shield) {
    HitPoints hp = new HitPoints(1000);
    hp.setHitPoints(current);
    hp.setShield(shield);
    return hp;
  }

  @Test
  @DisplayName("the worked kills: death at 452, a shield of 300 broken, a shield of 900 dented")
  void workedKills() {
    HitPoints dies = hitPoints(452, 0);
    DamageResult result = DamageApplication.kill(dies, HELD);
    assertThat(result.died()).isTrue();
    assertThat(dies.getHitPoints()).isZero();

    HitPoints broken = hitPoints(452, 300);
    assertThat(DamageApplication.kill(broken, HELD).died()).isFalse();
    assertThat(new int[] {broken.getHitPoints(), broken.getShield()}).containsExactly(452, 0);

    HitPoints dented = hitPoints(452, 900);
    assertThat(DamageApplication.kill(dented, HELD).died()).isFalse();
    assertThat(new int[] {dented.getHitPoints(), dented.getShield()}).containsExactly(452, 448);
  }
}
