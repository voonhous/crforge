package org.crforge.core.pathfinding.move;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.pathfinding.GridEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the aligned static neighbours check to 1500 recorded cases: an owner, the entities the
 * query lists around it in order, some with a movement component, some on the other layer, the
 * owner itself among them in some lists, and the answer.
 */
class AlignedStaticCheckTest {

  @Test
  @DisplayName("every recorded case gets its recorded answer")
  void everyRecordedCase() throws IOException {
    JsonNode cases;
    try (InputStream in = getClass().getResourceAsStream("/battle/aligned_static.json")) {
      cases = new ObjectMapper().readTree(in).get("cases");
    }
    assertThat(cases).hasSize(1500);
    int ones = 0;
    for (int i = 0; i < cases.size(); i++) {
      JsonNode c = cases.get(i);
      GridEntity owner = entity(c.get(0).asInt(), c.get(1).asInt(), c.get(2).asInt(), true);
      List<GridEntity> listed = new ArrayList<>();
      for (JsonNode e : c.get(3)) {
        listed.add(
            e.get(4).asInt() == 1
                ? owner
                : entity(
                    e.get(0).asInt(), e.get(1).asInt(), e.get(2).asInt(), e.get(3).asInt() == 1));
      }
      int answer = AlignedStaticCheck.answer(owner, listed);
      assertThat(answer).as("case %d", i).isEqualTo(c.get(4).asInt());
      ones += answer;
    }
    assertThat(ones).as("cases answering 1").isEqualTo(492);
  }

  @Test
  @DisplayName("one aligned static entity is not enough")
  void oneAlignedStaticEntityIsNotEnough() {
    GridEntity owner = entity(3500, 10000, 0, true);
    assertThat(AlignedStaticCheck.answer(owner, List.of(entity(3500, 10800, 0, false)))).isZero();
    assertThat(
            AlignedStaticCheck.answer(
                owner, List.of(entity(3500, 10800, 0, false), entity(2900, 10000, 0, false))))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("a unit waiting to deploy keeps its movement component, so it is never static here")
  void anEntityWithAnInactiveMovementComponentIsNotStatic() {
    GridEntity owner = entity(3500, 10000, 0, true);
    GridEntity waiting = entity(3500, 10800, 0, true);
    waiting.setMovementActive(false);
    assertThat(AlignedStaticCheck.answer(owner, List.of(waiting, entity(2900, 10000, 0, false))))
        .isZero();
  }

  private static GridEntity entity(int x, int y, int height, boolean hasMovementComponent) {
    GridEntity entity = new GridEntity();
    entity.setX(x);
    entity.setY(y);
    entity.setZTotal(height);
    entity.setMovementComponent(hasMovementComponent);
    entity.setMovementActive(hasMovementComponent);
    return entity;
  }
}
