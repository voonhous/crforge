package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.ShapeSelector;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Vines' selector where the reference runs do not reach: a circle that holds nobody, two objects of
 * equal score, a shield that changes the pick, a row that may pick an object again, and a selector
 * on a unit.
 */
class BattleShapeSelectorTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String SELECTOR = "Vines_Target_Selector";

  /** A point on the top side, away from every tower. */
  private static final int X = 9000;

  private static final int Y = 20000;

  /**
   * A battle with the towers holding fire, and every selector step and air-to-ground re-trigger.
   */
  private static final class Scene {
    final Standard1v1Battle match;
    final List<String> steps = new ArrayList<>();

    Scene(GameTables tables) {
      match = new Standard1v1Battle(tables, LEVEL, false);
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void selectorStepped(
                    int tick, AreaEffectEntity areaEffect, String action, ShapeSelector.Step step) {
                  List<String> chosen = new ArrayList<>();
                  for (int[] pick : step.chosen()) {
                    chosen.add(pick[0] + "=" + named(pick[1]));
                  }
                  steps.add(
                      "%d chosen %s none %s empty %s finished %s"
                          .formatted(tick, chosen, step.none(), step.empty(), step.finished()));
                }

                @Override
                public void airToGroundRetriggered(
                    int tick, WorldEntity unit, String action, int phase, int counter) {
                  steps.add(
                      "%d retrigger %s phase %d counter %d"
                          .formatted(tick, unit.name(), phase, counter));
                }
              });
    }

    String named(int id) {
      return ((WorldEntity) match.getWorld().liveObject(id)).name();
    }

    void vines() {
      match.placeAreaEffect(0, "Vines_AeO", LEVEL, 0, X, Y, "vines");
    }

    void steps(int count) {
      for (int i = 0; i < count; i++) {
        match.getBattle().step();
      }
    }
  }

  @Test
  @DisplayName(
      "a circle that holds nobody ends each due step before its finish's test, so the run finishes"
          + " a tick after its last due tick")
  void anEmptyCircle() {
    Scene scene = new Scene(GameData.tables());
    scene.vines();
    scene.steps(25);
    assertThat(scene.steps)
        .containsExactly(
            "18 chosen [] none [] empty true finished false",
            "19 chosen [] none [] empty true finished false",
            "21 chosen [] none [] empty true finished false",
            "22 chosen [] none [] empty false finished true");
  }

  @Test
  @DisplayName(
      "of two Knights of equal hit points the lower id is picked first, though the query finds"
          + " the other first; the third entry picks nobody")
  void equalScoresGoToTheLowerId() {
    Scene scene = new Scene(GameData.tables());
    // The query walks its buckets from the left, so it finds the second Knight first.
    scene.match.deploy(0, GameData.unit("Knight"), LEVEL, 1, X + 2000, Y, "first");
    scene.match.deploy(0, GameData.unit("Knight"), LEVEL, 1, X - 2000, Y, "second");
    scene.vines();
    scene.steps(22);
    assertThat(scene.steps)
        .containsExactly(
            "18 chosen [0=first] none [] empty false finished false",
            "19 chosen [1=second] none [] empty false finished false",
            "21 chosen [] none [2] empty false finished true");
  }

  @Test
  @DisplayName(
      "a Guard's shield lifts it over a Minion when shields count; by hit points alone the Minion"
          + " is picked first")
  void aShieldCounts(@TempDir Path folder) throws IOException {
    Scene shields = new Scene(GameData.tables());
    shields.match.deploy(0, GameData.unit("SkeletonWarrior"), LEVEL, 1, X - 500, Y, "guard");
    shields.match.deploy(0, GameData.unit("Minion"), LEVEL, 1, X + 500, Y, "minion");
    shields.vines();
    shields.steps(19);
    assertThat(shields.steps)
        .first()
        .isEqualTo("18 chosen [0=guard] none [] empty false finished false");

    Files.createDirectories(folder);
    GameTables plain =
        GameData.altered(
            folder,
            "actions",
            rows ->
                ((ObjectNode) rows.get(SELECTOR).get("fields"))
                    .put("TargetSelectionMode", "HighestCurrentHp"));
    Scene hitPoints = new Scene(plain);
    hitPoints.match.deploy(0, GameData.unit("SkeletonWarrior"), LEVEL, 1, X - 500, Y, "guard");
    hitPoints.match.deploy(0, GameData.unit("Minion"), LEVEL, 1, X + 500, Y, "minion");
    hitPoints.vines();
    hitPoints.steps(19);
    assertThat(hitPoints.steps)
        .first()
        .isEqualTo("18 chosen [0=minion] none [] empty false finished false");
  }

  @Test
  @DisplayName(
      "a row that may pick an object again picks a lone Knight with every entry, and each later"
          + " pick starts its air-to-ground run over")
  void pickingAgain(@TempDir Path folder) throws IOException {
    Files.createDirectories(folder);
    GameTables again =
        GameData.altered(
            folder,
            "actions",
            rows -> ((ObjectNode) rows.get(SELECTOR).get("fields")).put("OncePerTarget", false));
    Scene scene = new Scene(again);
    scene.match.deploy(0, GameData.unit("Knight"), LEVEL, 1, X, Y, "knight");
    scene.vines();
    scene.steps(22);
    assertThat(scene.steps)
        .containsExactly(
            "18 chosen [0=knight] none [] empty false finished false",
            "19 chosen [1=knight] none [] empty false finished false",
            "19 retrigger knight phase 0 counter 2000",
            "21 chosen [2=knight] none [] empty false finished true",
            "21 retrigger knight phase 0 counter 2000");
  }

  @Test
  @DisplayName(
      "Vines' filter drops underground objects, though not hidden ones: a Miner tunnelling through"
          + " its circle is never picked")
  void aTunnellingMinerIsNotPicked() {
    Scene scene = new Scene(GameData.tables());
    // The top side's Miner tunnels from its king to the bottom side's left lane: it crosses the
    // circle about (5000, 17500) from tick 17 to tick 21, every due step of the selector, and
    // surfaces at tick 33.
    scene.match.play(0, GameData.card("Miner"), LEVEL, 1, 3500, 8000, "miner");
    scene.match.placeAreaEffect(0, "Vines_AeO", LEVEL, 0, 5000, 17500, "vines");
    scene.steps(25);
    CharacterEntity miner = scene.match.getPlays().get(0).units().get(0);
    assertThat(miner.getView().getState()).isEqualTo(GridEntityState.SPAWN_PATHFIND);
    assertThat(scene.steps)
        .containsExactly(
            "18 chosen [] none [] empty true finished false",
            "19 chosen [] none [] empty true finished false",
            "21 chosen [] none [] empty true finished false",
            "22 chosen [] none [] empty false finished true");
  }

  @Test
  @DisplayName(
      "a shape selector run on a crown tower, neither an area effect nor a character, is refused")
  void onATowerIsRefused() {
    Scene scene = new Scene(GameData.tables());
    scene.steps(1);
    TowerEntity king = scene.match.getWorld().kingTower(0);
    BattleAction selector =
        GameData.actions().build(SELECTOR, scene.match.getWorld().binding(king));
    assertThatThrownBy(() -> king.actionHolder().start(selector))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining(
            "a shape selector on an owner other than an area effect or a character");
  }
}
