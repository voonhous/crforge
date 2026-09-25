package org.crforge.core.battle.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The formation offset against recorded cases over every branch it takes. */
class FormationTest {

  @Test
  @DisplayName(
      "every recorded case: one to sixteen units, two groups, lines, both sides, the three lanes"
          + " and angle shifts")
  void everyRecordedCase() throws IOException {
    JsonNode cases;
    try (InputStream stream = FormationTest.class.getResourceAsStream("/battle/formation.json")) {
      cases = new ObjectMapper().readTree(stream).get("cases");
    }
    assertThat(cases.size()).isGreaterThan(4000);
    for (JsonNode c : cases) {
      int[] got =
          Formation.offset(
              c.get(0).asInt(),
              c.get(1).asInt(),
              c.get(2).asInt(),
              c.get(3).asInt(),
              c.get(4).asInt(),
              c.get(5).asInt(),
              c.get(6).asInt(),
              c.get(7).asInt(),
              c.get(8).asInt() != 0,
              c.get(9).asInt() != 0);
      assertThat(got).as("case %s", c).containsExactly(c.get(10).asInt(), c.get(11).asInt());
    }
  }
}
