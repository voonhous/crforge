package org.crforge.core.pathfinding.combat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A typed hit's entry: refused by the battle's hold and by an id already listed, which does not
 * move its tick on; otherwise the id is listed, the shield and then the hit points are lowered as
 * by an ordinary hit, and the entry answers what the shield, if it was up, or the hit points lost.
 */
class TypedHitTest {

  private static final class Queries implements DamageQueries {
    boolean held;
    boolean ended;
    int tick = 7;

    @Override
    public boolean damageHeld() {
      return held;
    }

    @Override
    public boolean battleEnded() {
      return ended;
    }

    @Override
    public int battleTick() {
      return tick;
    }
  }

  private static HitPoints hitPoints(int current, int shield) {
    HitPoints hp = new HitPoints(1000);
    hp.setHitPoints(current);
    hp.setShield(shield);
    return hp;
  }

  @Test
  @DisplayName(
      "a typed hit lowers the hit points and answers what they lost, the overkill left out")
  void aTypedHitLowersTheHitPoints() {
    HitPoints hp = hitPoints(500, 0);
    DamageResult result = DamageApplication.typedHit(hp, 120, 3, 0, 0, new Queries());
    assertThat(hp.getHitPoints()).isEqualTo(380);
    assertThat(result.applied()).isEqualTo(120);
    assertThat(result.died()).isFalse();

    HitPoints dying = hitPoints(100, 0);
    DamageResult death = DamageApplication.typedHit(dying, 300, 4, 0, 0, new Queries());
    assertThat(dying.getHitPoints()).isZero();
    assertThat(death.applied()).isEqualTo(100);
    assertThat(death.died()).isTrue();
  }

  @Test
  @DisplayName("a shield that is up takes the hit, and the entry answers the shield's drop")
  void aShieldTakesTheTypedHit() {
    HitPoints hp = hitPoints(500, 200);
    DamageResult result = DamageApplication.typedHit(hp, 120, 3, 0, 0, new Queries());
    assertThat(new int[] {hp.getHitPoints(), hp.getShield()}).containsExactly(500, 80);
    assertThat(result.applied()).isEqualTo(120);
  }

  @Test
  @DisplayName("the battle's hold refuses a typed hit, and so does the battle's end")
  void theHoldsRefuse() {
    Queries held = new Queries();
    held.held = true;
    HitPoints hp = hitPoints(500, 0);
    assertThat(DamageApplication.typedHit(hp, 120, 3, 0, 0, held).applied()).isZero();
    assertThat(hp.getHitPoints()).isEqualTo(500);

    Queries ended = new Queries();
    ended.ended = true;
    assertThat(DamageApplication.typedHit(hp, 120, 3, 0, 0, ended).applied()).isZero();
    assertThat(hp.getHitPoints()).isEqualTo(500);
  }

  @Test
  @DisplayName("an id already listed is refused without moving its tick; id 0 lists nothing")
  void theIdIsListedOnce() {
    HitPoints hp = hitPoints(500, 0);
    Queries q = new Queries();
    DamageApplication.typedHit(hp, 100, 9, 0, 0, q);
    assertThat(hp.isDedupeListed(9)).isTrue();
    q.tick = 20;
    DamageResult again = DamageApplication.typedHit(hp, 100, 9, 0, 0, q);
    assertThat(again.applied()).isZero();
    assertThat(hp.getHitPoints()).isEqualTo(400);
    assertThat(hp.dedupeTick(9)).as("the tick is left as it was").isEqualTo(7);

    DamageApplication.typedHit(hp, 50, 0, 0, 0, q);
    DamageApplication.typedHit(hp, 50, 0, 0, 0, q);
    assertThat(hp.getHitPoints()).as("id 0 is never refused").isEqualTo(300);
  }
}
