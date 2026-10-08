package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.BattleRandom;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.spawn.SpawnHost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Graveyard's area effect: its starting group queues twelve skeleton spawns, each at a point
 * its position expressions work out from the area effect's own point and side. What the references
 * do not reach is held here: the middle of the arena, which mirrors the offsets across the width
 * only strictly right of it; the end of the area effect, which drops a spawn still queued; a point
 * on water, which goes one unit right and stays on water; and the names an area effect's
 * expressions may not read.
 */
class BattleGraveyardTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The card's area effect. */
  private static final String AREA = "Graveyard_rework";

  /** The tick the area effect is placed on. */
  private static final int PLACED = 5;

  /** A Graveyard placed directly, with the towers passive, logging every skeleton it spawns. */
  private static final class Scene {
    final Standard1v1Battle match;
    final List<String> spawns = new ArrayList<>();
    final List<AreaEffectEntity> areas = new ArrayList<>();
    int tick;

    Scene(GameTables tables) {
      match = new Standard1v1Battle(tables, LEVEL, false);
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void characterSpawned(
                    int t, SpawnHost source, CharacterEntity child, int createdX, int createdY) {
                  spawns.add(
                      (t - PLACED)
                          + " "
                          + child.getData().name()
                          + " "
                          + child.getView().getX()
                          + " "
                          + child.getView().getY());
                }

                @Override
                public void areaEffectAdmitted(int t, AreaEffectEntity areaEffect) {
                  areas.add(areaEffect);
                }
              });
    }

    Scene graveyard(int side, int x, int y) {
      match.placeAreaEffect(PLACED, AREA, LEVEL, side, x, y, "G");
      return this;
    }

    Scene step(int ticks) {
      for (int i = 0; i < ticks; i++) {
        match.getBattle().step();
        tick++;
      }
      return this;
    }
  }

  @Test
  @DisplayName(
      "the offsets across the width are turned over only strictly right of the middle: at 9000 the"
          + " -3500 row goes left, at 9500 right")
  void theMiddleMirrorsOnlyStrictlyRightOfIt() {
    // Graveyard_rework_Spawn_Skeleton_2, x + (-3500 * select(x > (map_width / 2), -1, 1)), is the
    // group's first sub-action, 2200 ms after the start.
    Scene middle = new Scene(GameData.tables()).graveyard(0, 9000, 12000).step(PLACED + 60);
    assertThat(middle.spawns).element(0).isEqualTo("44 Graveyard_rework_Skeleton 5500 12000");

    Scene right = new Scene(GameData.tables()).graveyard(0, 9500, 12000).step(PLACED + 60);
    assertThat(right.spawns).element(0).isEqualTo("44 Graveyard_rework_Skeleton 13000 12000");
  }

  @Test
  @DisplayName(
      "the area effect's end drops a spawn still queued: with a life of 2200, which keeps the area"
          + " effect one update past it, the first spawn runs on its last update's tick, with 2150"
          + " never")
  void theEndDropsWhatIsStillQueued(@TempDir Path folder) throws IOException {
    Path longer = folder.resolve("longer");
    Path shorter = folder.resolve("shorter");
    longer.toFile().mkdirs();
    shorter.toFile().mkdirs();

    // The first spawn, Graveyard_rework_Spawn_Skeleton_2 at 2200 ms, 3500 left of the point.
    Scene lasting = new Scene(life(longer, 2200)).graveyard(0, 6000, 12000).step(PLACED + 80);
    assertThat(lasting.spawns).containsExactly("44 Graveyard_rework_Skeleton 2500 12000");

    Scene ending = new Scene(life(shorter, 2150)).graveyard(0, 6000, 12000).step(PLACED + 80);
    assertThat(ending.areas).hasSize(1);
    assertThat(ending.spawns).isEmpty();
  }

  @Test
  @DisplayName(
      "a point on water is refused, so the skeleton is made one unit right of it, still on the"
          + " water")
  void aPointOnWaterGoesOneUnitRight() {
    // Graveyard_rework_Spawn_Skeleton_4, the third sub-action at 3300 ms, puts the bottom side's
    // skeleton 3500 behind the point: (6000, 16000), in the river.
    Scene river = new Scene(GameData.tables()).graveyard(0, 6000, 19500).step(PLACED + 80);
    assertThat(river.spawns).element(2).isEqualTo("66 Graveyard_rework_Skeleton 6001 16000");
  }

  @Test
  @DisplayName(
      "an area effect's expression reads its point, its side, the arena's width and the battle's"
          + " random source; any other name is refused as it is built")
  void anAreaEffectAnswersItsPointAndSide() {
    Scene scene = new Scene(GameData.tables()).graveyard(1, 14500, 7500).step(PLACED + 1);
    AreaEffectEntity area = scene.areas.get(0);

    assertThat(area.binding().expression("x + y").getAsInt()).isEqualTo(22000);
    assertThat(area.binding().expression("team_y_direction(team_index)").getAsInt()).isEqualTo(1);
    assertThat(area.binding().expression("map_width").getAsInt()).isEqualTo(18000);
    // rand draws from the battle's one source as the expression is evaluated.
    scene.match.getWorld().seed(0x37);
    assertThat(area.binding().expression("rand(1000)").getAsInt())
        .isEqualTo(new BattleRandom(0x37).next(1000));
    assertThatThrownBy(() -> area.binding().expression("hp"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("is an area effect, whose expressions answer only");
  }

  /** The tables with the Graveyard's area effect living the given milliseconds. */
  private static GameTables life(Path folder, int lifeMs) throws IOException {
    return GameData.altered(
        folder,
        "area_effect_objects",
        rows -> GameData.columns(rows, AREA).put("LifeDuration", lifeMs));
  }
}
