package org.crforge.core.pathfinding.combat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the heal to 2000 recorded cases and to the worked heals of the shipped rows: a shield that
 * is up takes the whole heal, a dead object is not healed, and the hit points rise to a cap that
 * keeps existing over-heal, is one less for a king tower below its maximum, and is replaced by the
 * over-heal percentage.
 */
class HealingTest {

  private static HitPoints hitPoints(int current, int maximum, int shield, int shieldMaximum) {
    HitPoints hp = new HitPoints(1);
    hp.setHitPoints(current);
    hp.setMaximum(maximum);
    hp.setShield(shield);
    hp.setShieldMaximum(shieldMaximum);
    return hp;
  }

  @Test
  @DisplayName("every recorded case gets its recorded hit points and shield")
  void everyRecordedCase() throws IOException {
    JsonNode cases;
    try (InputStream in = getClass().getResourceAsStream("/battle/heal.json")) {
      cases = new ObjectMapper().readTree(in).get("cases");
    }
    assertThat(cases).hasSize(2000);
    for (int i = 0; i < cases.size(); i++) {
      JsonNode c = cases.get(i);
      HitPoints hp =
          hitPoints(c.get(0).asInt(), c.get(1).asInt(), c.get(2).asInt(), c.get(3).asInt());
      Healing.heal(hp, c.get(4).asInt(), c.get(5).asInt(), c.get(6).asInt() == 1);
      assertThat(hp.getHitPoints()).as("case %d hit points", i).isEqualTo(c.get(7).asInt());
      assertThat(hp.getShield()).as("case %d shield", i).isEqualTo(c.get(8).asInt());
    }
  }

  @Test
  @DisplayName("the worked heals of the shipped rows")
  void workedHeals() {
    int[][] cases = {
      // hit points, maximum, shield, shield maximum, amount, percent -> hit points, shield
      {600, 1000, 0, 0, 120, 0, 720, 0},
      {1, 1000, 0, 0, 499, 0, 500, 0},
      {900, 1000, 0, 0, 400, 0, 1000, 0},
      {300, 1000, 100, 400, 1000, 0, 300, 400},
      {900, 1000, 0, 0, 1000, 150, 1500, 0},
      {1000, 1000, 0, 0, 100, 50, 500, 0}
    };
    for (int[] c : cases) {
      HitPoints hp = hitPoints(c[0], c[1], c[2], c[3]);
      Healing.heal(hp, c[4], c[5], false);
      assertThat(new int[] {hp.getHitPoints(), hp.getShield()}).containsExactly(c[6], c[7]);
    }
    HitPoints king = hitPoints(900, 1000, 0, 0);
    Healing.heal(king, 400, 0, true);
    assertThat(king.getHitPoints()).as("a king tower never heals back to full").isEqualTo(999);
  }
}
