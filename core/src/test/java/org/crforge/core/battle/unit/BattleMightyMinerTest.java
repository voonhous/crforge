package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.target.TargetView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Mighty Miner's lane switch where its runs do not take it: a spell landing on it as it routes
 * across the arena, a switch from the arena's edge, and the rows that would make it do what no
 * reference holds.
 */
class BattleMightyMinerTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Long enough for a placed Mighty Miner to deploy, walk and cast. */
  private static final int TICKS = 200;

  /** A Mighty Miner placed for the bottom side, with each lane switch it made. */
  private static final class Scene {
    final Standard1v1Battle match;
    final CharacterEntity miner;
    final List<int[]> switches = new ArrayList<>();
    int tick;

    Scene(GameTables tables, int x, int y) {
      match = new Standard1v1Battle(tables, LEVEL, false);
      miner =
          match.deploy(
              0, match.getWorld().getRecords().unit("MightyMiner"), LEVEL, 0, x, y, "Miner");
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void lanesSwitched(
                    int t,
                    CharacterEntity unit,
                    int mirroredX,
                    int mirroredY,
                    int toX,
                    int toY,
                    TargetView reference) {
                  switches.add(new int[] {unit.getView().getX(), mirroredX, mirroredY, toX, toY});
                }
              });
    }

    void step() {
      match.getBattle().step();
      tick++;
    }

    /**
     * Steps until the Mighty Miner walks, then requests its ability and steps until it switches.
     */
    void switchLanes() {
      while (miner.getView().getState() != GridEntityState.MOVING) {
        step();
        assertThat(tick).as("the Mighty Miner walks").isLessThan(TICKS);
      }
      miner.requestAbility();
      while (switches.isEmpty()) {
        step();
        assertThat(tick).as("the Mighty Miner switches lanes").isLessThan(TICKS);
      }
      assertThat(miner.getView().getState()).isEqualTo(GridEntityState.INGAME_PATHFIND);
    }
  }

  @Test
  @DisplayName("a spell landing on the Mighty Miner as it routes across passes it by")
  void aSpellPassesTheRoutingMinerBy() {
    Scene scene = new Scene(GameData.tables(), 3500, 12000);
    CharacterEntity knight = scene.match.deploy(0, GameData.unit("Knight"), LEVEL, 0, 4500, 12000);
    knight.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    scene.switchLanes();
    int minerFull = scene.miner.getHitPoints().getHitPoints();
    int knightFull = knight.getHitPoints().getHitPoints();
    assertThat(scene.miner.hidden()).isTrue();

    // On the Mighty Miner as it starts across, the Knight in reach too.
    int x = scene.miner.getView().getX();
    int y = scene.miner.getView().getY();
    scene.match.play(scene.tick, GameData.card("Zap"), LEVEL, 1, x, y, "Z");
    scene.step();

    assertThat(scene.miner.getView().getState()).isEqualTo(GridEntityState.INGAME_PATHFIND);
    long dx = scene.miner.getView().getX() - x;
    long dy = scene.miner.getView().getY() - y;
    assertThat(dx * dx + dy * dy)
        .as("moved, and within the Zap's 2500")
        .isBetween(1L, 2500L * 2500);
    assertThat(knight.getHitPoints().getHitPoints())
        .as("the Knight beside it")
        .isLessThan(knightFull);
    assertThat(scene.miner.getHitPoints().getHitPoints()).isEqualTo(minerFull);
  }

  @Test
  @DisplayName(
      "a filter that drops underground objects, as Vines' does, drops the Mighty Miner as it"
          + " routes across and passes it once it lands")
  void anUndergroundFilterDropsTheRoutingMiner() {
    Scene scene = new Scene(GameData.tables(), 3500, 12000);
    GameObjectFilter vines = scene.match.getWorld().getRecords().filter("enemy_troops_for_vines");
    scene.switchLanes();
    assertThat(vines.matches(scene.miner.filterSubject(), 1, "Vines")).isFalse();

    while (scene.miner.getView().getState() == GridEntityState.INGAME_PATHFIND) {
      scene.step();
      assertThat(scene.tick).as("the Mighty Miner lands").isLessThan(TICKS);
    }
    assertThat(vines.matches(scene.miner.filterSubject(), 1, "Vines")).isTrue();
  }

  @Test
  @DisplayName("a lane switch from the arena's edge aims 250 inside the other edge")
  void aSwitchFromTheEdgeIsClamped() {
    Scene scene = new Scene(GameData.tables(), 100, 12000);
    scene.switchLanes();

    int[] at = scene.switches.get(0);
    assertThat(at[0]).as("still within 250 of its edge").isLessThan(250);
    assertThat(at[1]).isEqualTo(18000 - at[0]);
    assertThat(at[3]).isEqualTo(17750);
    assertThat(at[4]).isEqualTo(at[2]);
  }

  @Test
  @DisplayName("a deploy time for the character the ability leaves behind is refused")
  void aDeployTimeForTheBombIsRefused(@TempDir Path folder) throws IOException {
    GameTables tables =
        GameData.altered(
            folder,
            "character_abilities",
            rows ->
                GameData.columns(rows, "MightyMinerLaneSwitch")
                    .put("ActivationSpawnDeployTime", 500));
    Scene scene = new Scene(tables, 3500, 12000);

    assertThatThrownBy(scene.miner::requestAbility)
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("[ActivationSpawnDeployTime]");
  }

  @Test
  @DisplayName("a lane switch for a row that stays visible as it routes across is refused")
  void aVisibleSwitchIsRefused(@TempDir Path folder) throws IOException {
    GameTables tables =
        GameData.altered(
            folder,
            "characters",
            rows -> GameData.columns(rows, "MightyMiner").put("IngamePathfindVisible", true));
    Scene scene = new Scene(tables, 3500, 12000);

    assertThatThrownBy(scene.miner::requestAbility)
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("stays visible");
  }
}
