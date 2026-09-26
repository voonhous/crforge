package org.crforge.core.battle.spawn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.pathfinding.grid.TileMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the spawner's placement to 600 recorded cases on the standard arena, for either side: one
 * child with no radius stands on the point, or one unit right of it when the in-front test refuses
 * the point, and a radius spreads the children evenly on a ring, the last one at angle 0.
 */
class SpawnPlacementTest {

  @Test
  @DisplayName("every recorded case places its children where the spawner does")
  void everyRecordedCase() throws IOException {
    JsonNode cases;
    try (InputStream in = getClass().getResourceAsStream("/battle/spawn_placement.json")) {
      cases = new ObjectMapper().readTree(in).get("cases");
    }
    assertThat(cases).hasSize(600);
    TileMap arena = TileMap.standard1v1();
    for (int i = 0; i < cases.size(); i++) {
      JsonNode c = cases.get(i);
      int x = c.get(0).asInt();
      int y = c.get(1).asInt();
      int count = c.get(2).asInt();
      int radius = c.get(3).asInt();
      int collisionRadius = c.get(4).asInt();
      List<String> placed = new ArrayList<>();
      for (int child = 0; child < Math.max(count, 0); child++) {
        int[] at =
            SpawnPlacement.position(
                x,
                y,
                child,
                count,
                count == 1,
                radius,
                (px, py) -> SpawnPassable.passable(arena, px, py, collisionRadius));
        placed.add(at[0] + "," + at[1]);
      }
      List<String> expected = new ArrayList<>();
      for (JsonNode at : c.get(6)) {
        expected.add(at.get(0).asInt() + "," + at.get(1).asInt());
      }
      assertThat(placed).as("case %d", i).containsExactlyElementsOf(expected);
    }
  }

  @Test
  @DisplayName("one child on land stands on the point, on the river one unit right of it")
  void oneChild() {
    TileMap arena = TileMap.standard1v1();
    SpawnPlacement.Passable passable = (px, py) -> SpawnPassable.passable(arena, px, py, 500);
    assertThat(SpawnPlacement.position(3000, 21500, 0, 1, true, 0, passable))
        .containsExactly(3000, 21500);
    assertThat(SpawnPlacement.position(9000, 16000, 0, 1, true, 0, passable))
        .containsExactly(9001, 16000);
  }

  @Test
  @DisplayName("four children on a ring of 1000 sit at 270, 180, 90 and 0 degrees")
  void ring() {
    List<String> placed = new ArrayList<>();
    for (int child = 0; child < 4; child++) {
      int[] at = SpawnPlacement.position(9000, 20000, child, 4, false, 1000, null);
      placed.add(at[0] + "," + at[1]);
    }
    assertThat(placed).containsExactly("9000,19000", "8000,20000", "9000,21000", "10000,20000");
  }

  @Test
  @DisplayName("several children with no radius are refused: the source's own offset is not held")
  void severalWithoutARadius() {
    assertThatThrownBy(() -> SpawnPlacement.position(9000, 20000, 0, 2, false, 0, (x, y) -> true))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
