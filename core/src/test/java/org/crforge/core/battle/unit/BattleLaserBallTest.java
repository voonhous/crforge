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
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Dark Magic's laser ball where the reference runs do not reach: a fire that finds nobody, a
 * building found by its square, two Dark Magics striking one unit on the same tick, and a laser
 * ball on a unit.
 */
class BattleLaserBallTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String LASER = "DarkMagicAOE_OnStartingAction_SubActions1";

  private static final String STRONGEST =
      "DarkMagicAOE_OnStartingAction_SubActions1_OnDetectedUnitActionList0";

  /** A point on the top side, away from every tower. */
  private static final int X = 9000;

  private static final int Y = 20000;

  /** A battle with the towers holding fire, and every fire and buff hit the observers hear. */
  private static final class Scene {
    final Standard1v1Battle match;
    final List<String> fires = new ArrayList<>();
    final List<String> hits = new ArrayList<>();

    Scene(GameTables tables) {
      match = new Standard1v1Battle(tables, LEVEL, false);
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void laserFired(
                    int tick,
                    AreaEffectEntity areaEffect,
                    int count,
                    int index,
                    List<WorldEntity> targets,
                    String action,
                    int timerBefore,
                    int timerAfter) {
                  fires.add(
                      "%s count %d index %d targets %s action %s"
                          .formatted(
                              areaEffect.name(),
                              count,
                              index,
                              targets.stream().map(WorldEntity::name).toList(),
                              action));
                }

                @Override
                public void buffDamaged(
                    int tick,
                    WorldEntity target,
                    BuffInstance buff,
                    int damage,
                    int hitPointsBefore,
                    DamageResult result) {
                  hits.add(target.name() + " " + damage);
                }
              });
    }

    void steps(int count) {
      for (int i = 0; i < count; i++) {
        match.getBattle().step();
      }
    }
  }

  @Test
  @DisplayName(
      "a fire that finds nobody picks the first list and schedules nothing; it fires every twenty"
          + " ticks from the twentieth after its start")
  void nobodyInReach() {
    Scene scene = new Scene(GameData.tables());
    scene.match.placeAreaEffect(0, "DarkMagicAOE", LEVEL, 0, X, Y, "dark");
    // The laser ball starts ten ticks after the area effect, and fires twenty ticks later.
    scene.steps(30);
    assertThat(scene.fires).isEmpty();
    scene.steps(1);
    assertThat(scene.fires).containsExactly("dark count 0 index 0 targets [] action null");
    scene.steps(20);
    assertThat(scene.fires).hasSize(2);
  }

  @Test
  @DisplayName(
      "a building is found by its square: a Cannon whose corner lies within the radius though its"
          + " centre lies beyond the radius plus its collision radius")
  void aBuildingByItsSquare() {
    Scene scene = new Scene(GameData.tables());
    // 2300 off along both axes: the square's corner is 1700 off along each, 2404 away; the
    // centre is 3252 away, beyond 2500 and the Cannon's 600.
    scene.match.deploy(0, GameData.unit("Cannon"), LEVEL, 1, X + 2300, Y + 2300, "cannon");
    scene.match.placeAreaEffect(0, "DarkMagicAOE", LEVEL, 0, X, Y, "dark");
    scene.steps(31);
    assertThat(scene.fires)
        .containsExactly("dark count 1 index 0 targets [cannon] action " + STRONGEST);
  }

  @Test
  @DisplayName(
      "two Dark Magics striking one Knight on the same tick each list their own buff and deal both"
          + " hits, as their buffs are added as individual ones")
  void twoDarkMagicsStack() {
    Scene scene = new Scene(GameData.tables());
    scene.match.deploy(0, GameData.unit("Knight"), LEVEL, 1, X, Y, "knight");
    scene.match.placeAreaEffect(0, "DarkMagicAOE", LEVEL, 0, X, Y, "first");
    scene.match.placeAreaEffect(0, "DarkMagicAOE", LEVEL, 0, X, Y, "second");
    scene.steps(33);
    assertThat(scene.hits).containsExactly("knight 340", "knight 340");
  }

  @Test
  @DisplayName("with the buff not added as an individual one, the second application refreshes")
  void aSharedBuffRefreshes(@TempDir Path folder) throws IOException {
    Files.createDirectories(folder);
    GameTables shared =
        GameData.altered(
            folder,
            "actions",
            rows ->
                ((ObjectNode) rows.get(STRONGEST).get("fields").get("SpawnData"))
                    .put("AddAsIndividualBuff", false));
    Scene scene = new Scene(shared);
    scene.match.deploy(0, GameData.unit("Knight"), LEVEL, 1, X, Y, "knight");
    scene.match.placeAreaEffect(0, "DarkMagicAOE", LEVEL, 0, X, Y, "first");
    scene.match.placeAreaEffect(0, "DarkMagicAOE", LEVEL, 0, X, Y, "second");
    scene.steps(33);
    assertThat(scene.hits).containsExactly("knight 340");
  }

  @Test
  @DisplayName("a laser ball run on a unit rather than an area effect is refused")
  void onAUnitIsRefused() {
    Scene scene = new Scene(GameData.tables());
    CharacterEntity knight =
        scene.match.deploy(0, GameData.unit("Knight"), LEVEL, 0, X, Y, "knight");
    scene.steps(1);
    BattleAction laser = GameData.actions().build(LASER, scene.match.getWorld().binding(knight));
    ActionHolder holder = knight.actionHolder();
    assertThatThrownBy(() -> holder.start(laser))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("a laser ball on an owner other than an area effect");
  }
}
