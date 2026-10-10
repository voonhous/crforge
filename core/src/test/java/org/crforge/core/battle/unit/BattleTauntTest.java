/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

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
import org.crforge.core.battle.action.ActionOwner;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.target.TargetView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Goblin Demolisher's cancelling area effect where the reference run does not reach. Its
 * filter, OnlyGoblinDemolisher, reaches the Demolisher alone; written to reach its whole side, it
 * holds an own unit beside the Demolisher, one that attacks only buildings, the Demolisher leaving
 * while a unit is taunted onto it. Also an area effect that moves with the object it follows or
 * outlives it, a longer taunt, and the refusal.
 */
class BattleTauntTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String BUFF = "GoblinDemolisher_ResetTargetBuff";

  /**
   * The taunt's duration the tests write into ResetTauntEffect's ValidDuration where they read it:
   * one step.
   */
  private static final int DURATION = 50;

  /** A point on the bottom side's left, away from every tower. */
  private static final int X = 3500;

  private static final int Y = 11500;

  /** A battle, a Goblin Demolisher in it, and every taunt line the observers hear. */
  private static final class Scene {
    final Standard1v1Battle match;
    final CharacterEntity demolisher;
    final List<String> taunts = new ArrayList<>();

    Scene(GameTables tables) {
      match = new Standard1v1Battle(tables, LEVEL, false);
      demolisher = match.deploy(0, GameData.unit("GoblinDemolisher"), LEVEL, 0, X, Y, "demolisher");
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void tauntPerformed(
                    int tick,
                    WorldEntity unit,
                    String action,
                    int phase,
                    ActionOwner instigator,
                    WorldEntity forced) {
                  taunts.add(unit.name() + " performed onto " + forced.name());
                }

                @Override
                public void tauntStepped(
                    int tick,
                    WorldEntity unit,
                    WorldEntity forced,
                    int durationMs,
                    int falloffMs,
                    List<String> calls) {
                  taunts.add(unit.name() + " " + calls);
                }
              });
    }

    /**
     * Runs the Demolisher's cancelling spawn, the Demolisher its own cause, as its trigger does.
     */
    void cancel() {
      BattleAction spawn =
          GameData.actions().build("SpawnCancelTauntAEO", match.getWorld().binding(demolisher));
      demolisher.actionHolder().start(spawn, demolisher.actionHolder());
    }
  }

  /**
   * The configured tables with the cancelling area effect's filter, OnlyGoblinDemolisher, widened
   * to every character and building of its own side, so its filter-form hit pass reaches the units
   * beside the Demolisher too; and the taunt lasting {@link #DURATION}.
   */
  private static GameTables ownSide(Path folder) throws IOException {
    Files.createDirectories(folder);
    GameData.altered(
        folder,
        "game_object_filters",
        rows -> {
          ObjectNode columns = GameData.columns(rows, "OnlyGoblinDemolisher");
          columns.remove("IncludeCharactersWithData");
          columns.put("MatchTypeBuildings", true);
        });
    lasting(folder, DURATION);
    return GameTables.load(folder);
  }

  /** The configured tables with the taunt lasting {@link #DURATION}. */
  private static GameTables oneStepTaunt(Path folder) throws IOException {
    Files.createDirectories(folder);
    GameData.altered(folder, "actions", rows -> {});
    lasting(folder, DURATION);
    return GameTables.load(folder);
  }

  /** Writes the taunt's duration into a folder the configured tables were copied into. */
  private static void lasting(Path folder, int durationMs) throws IOException {
    GameData.alterLoaded(
        folder,
        "actions",
        rows ->
            ((ObjectNode) rows.get("ResetTauntEffect").get("fields"))
                .put("ValidDuration", durationMs));
  }

  private static String reference(WorldEntity unit) {
    TargetView reference = unit.getTargeting().getReference();
    return reference == null ? null : reference.name();
  }

  @Test
  @DisplayName(
      "an own unit whose circle holds the Demolisher's point is taunted onto it too, with the buff that"
          + " locks its reference, which its next step ends")
  void anOwnUnitBesideIt(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(ownSide(folder));
    CharacterEntity pekka = scene.match.deploy(0, GameData.unit("Pekka"), LEVEL, 0, X, Y, "pekka");
    scene.match.getBattle().step();
    scene.cancel();
    scene.match.getBattle().step();

    assertThat(reference(pekka)).isEqualTo("demolisher");
    assertThat(reference(scene.demolisher)).isEqualTo("demolisher");
    assertThat(pekka.getBuffs().carries(BUFF)).isTrue();
    assertThat(pekka.getTargeting().getTargetLockingBuffs()).isOne();
    assertThat(pekka.getTargeting().getRetargetCooldownMs()).isEqualTo(DURATION);
    String armed =
        "[set_target demolisher 0 0 1, raise LOCK_TARGET, remaining %d, apply_buff %s %d level %d"
                .formatted(DURATION, BUFF, DURATION, scene.demolisher.getPackedLevel())
            + " source demolisher side 0]";
    assertThat(scene.taunts)
        .containsExactlyInAnyOrder(
            "demolisher performed onto demolisher",
            "demolisher " + armed,
            "pekka performed onto demolisher",
            "pekka " + armed);

    scene.taunts.clear();
    scene.match.getBattle().step();
    assertThat(scene.taunts)
        .contains(
            "pekka [remaining 0, remaining 0, set_target null 0 1 0, finish, remove_buff "
                + BUFF
                + "]");
    assertThat(pekka.getBuffs().carries(BUFF)).isFalse();
    assertThat(pekka.getTargeting().getTargetLockingBuffs()).isZero();
  }

  @Test
  @DisplayName(
      "a unit attacking as its taunt steps keeps the reference it was forced onto; the step only"
          + " clears its re-selection wait")
  void anAttackingUnitKeepsItsReference(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(oneStepTaunt(folder));
    scene.match.deploy(0, GameData.unit("Knight"), LEVEL, 1, X, Y + 1300, "enemy");
    for (int i = 0; i < 60; i++) {
      scene.match.getBattle().step();
    }
    assertThat(scene.demolisher.getView().getState()).isEqualTo(GridEntityState.ATTACKING);
    scene.cancel();
    scene.match.getBattle().step();
    assertThat(reference(scene.demolisher)).isEqualTo("demolisher");

    scene.taunts.clear();
    scene.match.getBattle().step();
    assertThat(scene.taunts)
        .containsExactly("demolisher [remaining 0, finish, remove_buff " + BUFF + "]");
  }

  @Test
  @DisplayName(
      "an own unit that attacks only buildings cannot attack the Demolisher: no reference, no buff,"
          + " and its run finishes at once")
  void aBuildingAttackerBesideIt(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(ownSide(folder));
    CharacterEntity giant = scene.match.deploy(0, GameData.unit("Giant"), LEVEL, 0, X, Y, "giant");
    scene.match.getBattle().step();
    scene.cancel();
    scene.match.getBattle().step();

    assertThat(reference(giant)).isNotEqualTo("demolisher");
    assertThat(giant.getBuffs().carries(BUFF)).isFalse();
    assertThat(scene.taunts)
        .contains("giant performed onto demolisher", "giant [finish, remove_buff " + BUFF + "]");
  }

  @Test
  @DisplayName(
      "the Demolisher leaving while a unit is taunted onto it ends that unit's run: no reference"
          + " and no buff")
  void theForcedObjectLeaves(@TempDir Path folder) throws IOException {
    // The taunt lasts 100 ms, so the run is still on when the kill lands at the damage drain of
    // the step after the cancel's.
    ownSide(folder);
    lasting(folder, 100);
    Scene scene = new Scene(GameTables.load(folder));
    CharacterEntity pekka = scene.match.deploy(0, GameData.unit("Pekka"), LEVEL, 0, X, Y, "pekka");
    scene.match.getBattle().step();
    scene.cancel();
    scene.match.getBattle().step();
    assertThat(reference(pekka)).isEqualTo("demolisher");

    // Both runs step first, the Demolisher's own too; the kill lands at the damage drain after
    // them, and the Pekka's run, its forced object gone, ends in the same step.
    scene.taunts.clear();
    scene.demolisher.killBy(null);
    scene.match.getBattle().step();
    assertThat(scene.taunts)
        .containsExactly(
            "demolisher [raise LOCK_TARGET]",
            "pekka [raise LOCK_TARGET]",
            "pekka [remaining 0, set_target null 0 0 0, finish, remove_buff " + BUFF + "]");
    assertThat(pekka.getBuffs().carries(BUFF)).isFalse();
  }

  @Test
  @DisplayName("the cancelling area effect stands on the Demolisher's point as it walks")
  void itFollows() {
    Scene scene = new Scene(GameData.tables());
    // Past its deploy, walking toward the bridge.
    for (int i = 0; i < 30; i++) {
      scene.match.getBattle().step();
    }
    List<String> points = new ArrayList<>();
    scene
        .match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void areaEffectCreated(
                  int tick, AreaEffectEntity areaEffect, String how, String source) {
                points.add("created " + areaEffect.getX() + " " + areaEffect.getY());
              }

              @Override
              public void areaEffectUpdated(
                  int tick,
                  AreaEffectEntity areaEffect,
                  int before,
                  int after,
                  int hits,
                  int radius,
                  List<Integer> damages) {
                points.add("updated " + areaEffect.getX() + " " + areaEffect.getY());
                points.add(
                    "demolisher "
                        + scene.demolisher.getView().getX()
                        + " "
                        + scene.demolisher.getView().getY());
              }
            });
    String before =
        "created " + scene.demolisher.getView().getX() + " " + scene.demolisher.getView().getY();
    scene.cancel();
    scene.match.getBattle().step();

    assertThat(points).hasSize(3);
    assertThat(points.get(0)).isEqualTo(before);
    assertThat(points.get(1)).isEqualTo(points.get(2).replace("demolisher", "updated"));
    assertThat(points.get(1))
        .as("the Demolisher walked")
        .isNotEqualTo(before.replace("created", "updated"));
  }

  @Test
  @DisplayName(
      "an area effect reaches each target once, and ends at the cleanup where its followed object"
          + " leaves")
  void theFollowedObjectLeaves(@TempDir Path folder) throws IOException {
    Files.createDirectories(folder);
    GameTables longer =
        GameData.altered(
            folder,
            "area_effect_objects",
            rows -> GameData.columns(rows, "CancelTauntAEO").put("LifeDuration", 1000));
    Scene scene = new Scene(longer);
    List<String> removed = new ArrayList<>();
    scene
        .match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void areaEffectRemoved(int tick, AreaEffectEntity areaEffect) {
                removed.add(areaEffect.getData().name() + " " + areaEffect.getCountdown());
              }

              @Override
              public void entityRemoved(int tick, WorldEntity entity) {
                removed.add(entity.name());
              }
            });
    scene.match.getBattle().step();
    scene.cancel();
    scene.match.getBattle().step();
    scene.match.getBattle().step();
    assertThat(removed).isEmpty();
    assertThat(scene.taunts)
        .as("a hit on each update, but one hit per target")
        .containsOnlyOnce("demolisher performed onto demolisher");

    // The kill lands at the next step's damage drain; the cleanup that removes the Demolisher ends
    // the area effect, its countdown below 0, and removes it too.
    scene.demolisher.killBy(null);
    scene.match.getBattle().step();
    assertThat(removed).containsExactly("demolisher", "CancelTauntAEO -1");
  }

  @Test
  @DisplayName(
      "a unit's taunt that outlasts its first step keeps the reference on the forced object, then"
          + " lets it go as the duration runs out")
  void aLongerTauntOnAUnit(@TempDir Path folder) throws IOException {
    Files.createDirectories(folder);
    GameTables longer =
        GameData.altered(
            folder,
            "actions",
            rows ->
                ((ObjectNode) rows.get("ResetTauntEffect").get("fields"))
                    .put("ValidDuration", 100));
    Scene scene = new Scene(longer);
    scene.match.getBattle().step();
    BattleAction spawn =
        scene
            .match
            .getWorld()
            .getActions()
            .build("SpawnCancelTauntAEO", scene.match.getWorld().binding(scene.demolisher));
    scene.demolisher.actionHolder().start(spawn, scene.demolisher.actionHolder());
    scene.match.getBattle().step();
    assertThat(scene.taunts).contains("demolisher performed onto demolisher");
    scene.taunts.clear();
    scene.match.getBattle().step();
    assertThat(scene.taunts).containsExactly("demolisher [raise LOCK_TARGET]");
    scene.taunts.clear();
    scene.match.getBattle().step();
    assertThat(scene.taunts).hasSize(1);
    assertThat(scene.taunts.get(0)).startsWith("demolisher [remaining 0").contains("finish");
  }

  @Test
  @DisplayName("an area effect that follows its parent and was placed by no action is refused")
  void refusals(@TempDir Path folder) throws IOException {
    Files.createDirectories(folder);
    GameTables noHit =
        GameData.altered(
            folder,
            "area_effect_objects",
            rows -> {
              ObjectNode columns = GameData.columns(rows, "CancelTauntAEO");
              columns.remove("OnHitAction");
              columns.remove("OneHitPerTarget");
            });
    Standard1v1Battle placed = new Standard1v1Battle(noHit, LEVEL, false);
    placed.placeAreaEffect(0, "CancelTauntAEO", LEVEL, 0, X, Y, "cancel");
    assertThatThrownBy(() -> placed.getBattle().step())
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("follows its parent and was not made by an action");
  }
}
