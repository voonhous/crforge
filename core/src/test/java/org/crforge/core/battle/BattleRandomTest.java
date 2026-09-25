package org.crforge.core.battle;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The battle's random source against recorded draws. */
class BattleRandomTest {

  @Test
  @DisplayName(
      "every recorded draw: seven states, six ranges, four draws each, the zero state and the"
          + " lowest integer among them")
  void everyRecordedDraw() throws IOException {
    JsonNode cases;
    try (InputStream stream =
        BattleRandomTest.class.getResourceAsStream("/battle/xorshift32.json")) {
      cases = new ObjectMapper().readTree(stream).get("cases");
    }
    assertThat(cases).hasSize(42);
    for (JsonNode c : cases) {
      BattleRandom random = new BattleRandom(c.get("seed").asInt());
      int range = c.get("range").asInt();
      int k = 0;
      for (JsonNode draw : c.get("draws")) {
        String where = "seed " + c.get("seed").asInt() + " range " + range + " draw " + k++;
        assertThat(random.next(range)).as("%s value", where).isEqualTo(draw.get(0).asInt());
        assertThat(random.getState()).as("%s state", where).isEqualTo(draw.get(1).asInt());
      }
    }
  }
}
