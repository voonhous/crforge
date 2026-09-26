package org.crforge.core.pathfinding.move;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.GridEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the pushback request and its setter to 2000 recorded cases: the gates and the flags that
 * lift them, the distance clamped or less the separation, a longer pushback kept, the budget, the
 * target, the zero vector and a dash wind-up resumed.
 */
class PushbackRequestTest {

  @Test
  @DisplayName("every recorded case gets its recorded answer and leaves the recorded pushback")
  void everyRecordedCase() throws IOException {
    JsonNode cases;
    try (InputStream in = getClass().getResourceAsStream("/battle/pushback_request.json")) {
      cases = new ObjectMapper().readTree(in).get("cases");
    }
    assertThat(cases).hasSize(2000);
    int started = 0;
    for (int i = 0; i < cases.size(); i++) {
      int[] c = new int[cases.get(i).size()];
      for (int k = 0; k < c.length; k++) {
        c[k] = cases.get(i).get(k).asInt();
      }
      GridEntity owner = new GridEntity();
      owner.setX(c[0]);
      owner.setY(c[1]);
      owner.setId(c[2] == 1 ? 5000007 : 5000006);
      owner.setFlags(c[3] == 1 ? EntityFlags.NO_PUSHBACK : 0);
      owner.setState(c[4]);
      boolean[] resumed = {false};
      PushbackQueries queries =
          new PushbackQueries() {
            @Override
            public boolean ignoresPushback() {
              return c[5] == 1;
            }

            @Override
            public boolean buffRefusesPushback() {
              return c[6] == 1;
            }

            @Override
            public boolean hidden() {
              return c[7] == 1;
            }

            @Override
            public int dashWindupMs() {
              return c[8];
            }

            @Override
            public void resumeDashWindup() {
              resumed[0] = true;
            }

            @Override
            public int maxPushbackLength() {
              return c[17];
            }
          };
      MovementState m = MovementState.forSide(0, c[0], c[1]);
      m.setPushbackInFlight(c[9]);
      m.setAttackPushback(c[10]);
      m.setPushbackBudget(c[11]);
      m.setTargetX(c[12]);
      m.setTargetY(c[13]);

      int answer =
          PushbackRequest.request(
              m,
              owner,
              queries,
              c[14],
              c[15],
              c[16],
              c[18] == 1,
              c[19] == 1,
              c[20] == 1,
              c[21] == 1,
              c[22] == 1);

      String where = "case " + i;
      assertThat(answer).as(where).isEqualTo(c[23]);
      assertThat(m.getPushbackInFlight() != 0 ? 1 : 0).as("%s in flight", where).isEqualTo(c[24]);
      assertThat(m.getAttackPushback()).as("%s attack flag", where).isEqualTo(c[25]);
      assertThat(m.getPushbackBudget()).as("%s budget", where).isEqualTo(c[26]);
      assertThat(m.getTargetX()).as("%s target x", where).isEqualTo(c[27]);
      assertThat(m.getTargetY()).as("%s target y", where).isEqualTo(c[28]);
      assertThat(resumed[0] ? 1 : 0).as("%s resumed", where).isEqualTo(c[29]);
      started += answer;
    }
    assertThat(started).as("requests that reached the setter").isEqualTo(748);
  }
}
