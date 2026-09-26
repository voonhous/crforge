package org.crforge.core.battle.spawn;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import org.crforge.core.pathfinding.grid.TileMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the in-front test to 3000 recorded cases, on the standard arena and on small grids of their
 * own: a point off the arena or over a water cell is refused, and the test looks at the cell under
 * the point whatever the collision radius, unless the radius is below 1.
 */
class SpawnPassableTest {

  @Test
  @DisplayName("every recorded case gets its recorded answer")
  void everyRecordedCase() throws IOException {
    JsonNode cases;
    try (InputStream in = getClass().getResourceAsStream("/battle/spawn_passable.json")) {
      cases = new ObjectMapper().readTree(in).get("cases");
    }
    assertThat(cases).hasSize(3000);
    TileMap arena = TileMap.standard1v1();
    for (int i = 0; i < cases.size(); i++) {
      JsonNode c = cases.get(i);
      int x = c.get(1).asInt();
      int y = c.get(2).asInt();
      int radius = c.get(3).asInt();
      boolean passable;
      if (c.get(0).isTextual()) {
        passable = SpawnPassable.passable(arena, x, y, radius);
      } else {
        JsonNode grid = c.get(0);
        int width = grid.get(0).asInt();
        int height = grid.get(1).asInt();
        JsonNode flags = grid.get(2);
        passable =
            SpawnPassable.passable(
                width, height, (cx, cy) -> flags.get(cy * width + cx).asInt(), x, y, radius);
      }
      assertThat(passable).as("case %d", i).isEqualTo(c.get(4).asInt() == 1);
    }
  }

  @Test
  @DisplayName("on the arena: land passes, the river and the edges do not")
  void onTheArena() {
    TileMap arena = TileMap.standard1v1();
    assertThat(SpawnPassable.passable(arena, 3500, 21500, 500)).isTrue();
    assertThat(SpawnPassable.passable(arena, 9000, 16000, 500)).as("the river").isFalse();
    assertThat(SpawnPassable.passable(arena, 0, 21500, 500)).as("the left edge").isFalse();
    assertThat(SpawnPassable.passable(arena, 18000, 21500, 500)).as("the right edge").isFalse();
  }
}
