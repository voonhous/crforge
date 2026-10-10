/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.action.ActionOwner;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.grid.PathfindingGlobals;
import org.crforge.core.pathfinding.target.SightRange;
import org.crforge.core.pathfinding.target.TargetView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The hero Knight's taunt on an enemy building that is not a crown tower, a Cannon: the arming
 * forces the Cannon's reference onto the Knight for the row's valid duration whether or not the
 * Cannon reaches it (the Knight is not a building), and each step tests the reach as for any
 * building owner: reached, the reference is forced again under building retargeting; out of reach,
 * a reference on the Knight is given up, and forced again once the Knight comes back within reach.
 * A Cannon whose taunting Knight leaves holds no reference while its selector stays locked, and
 * like any building with hit points it resets its attack and stands until it may pick again.
 */
class BattleKnightHeroTauntBuildingTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The ability's spawn of the taunting area effect. */
  private static final String SPAWN = "Knight_hero_CreateTauntAEO";

  /** The taunt the area effect's hit group runs. */
  private static final String TAUNT =
      Shipped.actionNames(
              Shipped.text(
                  Shipped.row("area_effect_objects", Shipped.text(SPAWN, "SpawnData")),
                  "OnHitAction"),
              "SubActions")
          .get(0);

  private static final String BUFF = Shipped.text(TAUNT, "ValidTargetBuff");

  /** How long a building that can attack the Knight is taunted, in ms. */
  private static final int DURATION = Shipped.number(TAUNT, "ValidDuration");

  /** Whether a reached building has its reference forced again on every step. */
  private static final boolean RETARGET =
      Shipped.fields(TAUNT).path("AllowBuildingRetargeting").asBoolean(false);

  /** The Cannon's point, in the top side's half. */
  private static final int CANNON_X = 9000;

  private static final int CANNON_Y = 22000;

  /** A battle with passive towers, an enemy Cannon and the hero Knight, and every taunt line. */
  private static final class Scene {
    final Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    final CharacterEntity cannon =
        match.deploy(0, GameData.unit("Cannon"), LEVEL, 1, CANNON_X, CANNON_Y, "cannon");
    final CharacterEntity knight;
    final List<String> taunts = new ArrayList<>();

    Scene(int knightX, int knightY) {
      knight = match.deploy(0, GameData.unit("KnightHero"), LEVEL, 0, knightX, knightY, "knight");
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                // Only the Cannon's lines: a princess tower the circle reaches is taunted too.
                @Override
                public void tauntPerformed(
                    int tick,
                    WorldEntity unit,
                    String action,
                    int phase,
                    ActionOwner instigator,
                    WorldEntity forced) {
                  if (unit == cannon) {
                    taunts.add(unit.name() + " performed onto " + forced.name());
                  }
                }

                @Override
                public void tauntStepped(
                    int tick,
                    WorldEntity unit,
                    WorldEntity forced,
                    int durationMs,
                    int falloffMs,
                    List<String> calls) {
                  if (unit == cannon) {
                    taunts.add(unit.name() + " " + durationMs + " " + calls);
                  }
                }
              });
    }

    /** Runs the ability's spawn of the taunting area effect, the Knight its cause. */
    void taunt() {
      BattleAction spawn = GameData.actions().build(SPAWN, match.getWorld().binding(knight));
      knight.actionHolder().start(spawn, knight.actionHolder());
    }

    void step(int ticks) {
      for (int i = 0; i < ticks; i++) {
        match.getBattle().step();
      }
    }

    /**
     * Whether the Cannon reaches the Knight as the taunt's step tests it: the Knight's centre
     * within the Cannon's sight range, its collision radius added, out from the Knight's edge.
     */
    boolean reached() {
      int outer = SightRange.sightRange(cannon.getTargeting()) + knight.getData().collisionRadius();
      if (PathfindingGlobals.ADD_CHARACTER_RANGE_TO_RADIUS) {
        outer += cannon.getData().collisionRadius();
      }
      long dx = knight.getView().getX() - cannon.getView().getX();
      long dy = knight.getView().getY() - cannon.getView().getY();
      return dx * dx + dy * dy <= (long) outer * outer;
    }

    /** The arming line of a taunt that forces the Cannon onto the Knight. */
    String armed() {
      return "cannon %d [set_target knight 0 0 1, raise LOCK_TARGET, remaining %d, apply_buff %s %d"
              .formatted(DURATION, DURATION, BUFF, DURATION)
          + " level %d source knight side 0]".formatted(knight.getPackedLevel());
    }
  }

  private static String reference(WorldEntity entity) {
    TargetView reference = entity.getTargeting().getReference();
    return reference == null ? null : reference.name();
  }

  /** The line of a step that finds the Knight within the Cannon's reach. */
  private static String reachedStep(int durationMs) {
    return "cannon "
        + durationMs
        + (RETARGET
            ? " [set_target knight 0 0 1, raise LOCK_TARGET, raise LOCK_TARGET]"
            : " [raise LOCK_TARGET]");
  }

  @Test
  @DisplayName(
      "a Cannon shooting a Giant is taunted onto the Knight within its reach: forced at the arming"
          + " and again on every step under building retargeting")
  void aReachedCannonIsForced() {
    Scene scene = new Scene(CANNON_X + 4000, CANNON_Y - 3000);
    CharacterEntity giant =
        scene.match.deploy(0, GameData.unit("Giant"), LEVEL, 0, CANNON_X, CANNON_Y - 3000, "giant");
    scene.step(30);
    assertThat(reference(scene.cannon)).isEqualTo("giant");
    assertThat(scene.reached()).isTrue();

    scene.taunt();
    scene.step(1);
    assertThat(scene.taunts).containsExactly("cannon performed onto knight", scene.armed());
    assertThat(reference(scene.cannon)).isEqualTo("knight");
    assertThat(scene.cannon.getBuffs().carries(BUFF)).isTrue();
    assertThat(scene.cannon.getTargeting().getRetargetCooldownMs()).isEqualTo(DURATION);

    scene.taunts.clear();
    scene.step(1);
    assertThat(scene.taunts).containsExactly(reachedStep(DURATION - 50));
    assertThat(reference(scene.cannon)).isEqualTo("knight");
  }

  @Test
  @DisplayName(
      "a Cannon taunted from beyond its reach is forced at the arming, gives the Knight up on the"
          + " next step, and is forced again once the Knight walks back within reach")
  void aCannonOutOfReachLetsGoUntilReached() {
    // The Knight stands straight below the Cannon, deploys and walks up at it; the taunt is
    // spawned on the last step it is still beyond the Cannon's reach.
    Scene scene = new Scene(CANNON_X, CANNON_Y - 9000);
    int ticks = 0;
    while (!scene.reached() && ticks < 400) {
      scene.step(1);
      ticks++;
    }
    assertThat(scene.reached()).isTrue();
    // Again, to the step before the Knight comes within reach.
    scene = new Scene(CANNON_X, CANNON_Y - 9000);
    scene.step(ticks - 3);
    assertThat(scene.reached()).isFalse();

    scene.taunt();
    scene.step(1);
    assertThat(scene.taunts).containsExactly("cannon performed onto knight", scene.armed());
    assertThat(reference(scene.cannon)).isEqualTo("knight");

    scene.taunts.clear();
    scene.step(1);
    assertThat(scene.reached()).isFalse();
    assertThat(scene.taunts)
        .containsExactly(
            "cannon "
                + (DURATION - 50)
                + (RETARGET ? " [remaining 0, set_target null 0 0 0]" : " [raise LOCK_TARGET]"));

    // Out of reach, a step forces nothing; the first one that finds the Knight within reach
    // forces the reference back onto it.
    int left = DURATION - 50;
    List<String> lines = new ArrayList<>();
    for (int i = 0; i < 40 && !lines.contains(reachedStep(left)); i++) {
      scene.taunts.clear();
      scene.step(1);
      left -= 50;
      lines.addAll(scene.taunts);
    }
    assertThat(lines).last().isEqualTo(reachedStep(left));
    assertThat(lines.subList(0, lines.size() - 1)).allMatch(line -> line.endsWith(" []"));
    assertThat(reference(scene.cannon)).isEqualTo("knight");
  }

  @Test
  @DisplayName(
      "a Cannon whose taunting Knight leaves stands, with no reference, while the lock the taunt"
          + " raised holds its selector, then takes the Giant in its range")
  void aCannonWhoseKnightLeavesStandsWhileLocked() {
    Scene scene = new Scene(CANNON_X + 4000, CANNON_Y - 3000);
    scene.match.deploy(0, GameData.unit("Giant"), LEVEL, 0, CANNON_X, CANNON_Y - 3000, "giant");
    scene.step(30);
    scene.taunt();
    for (int i = 0; i < 100 && !attacking(scene.cannon, "knight"); i++) {
      scene.step(1);
    }
    assertThat(attacking(scene.cannon, "knight")).isTrue();

    // The Cannon's own shot kills the Knight. The shot's damage is lethal while it flies, so the
    // Cannon keeps the Knight for it and the removal starts no attack finish wait: the Cannon is
    // left with no reference and nothing to finish, and the lock keeps the Giant from it.
    scene.knight.getHitPoints().setHitPoints(1);
    List<String> lines = new ArrayList<>();
    for (int i = 0; i < 60 && !attacking(scene.cannon, "giant"); i++) {
      scene.step(1);
      if (reference(scene.cannon) == null || !lines.isEmpty()) {
        lines.add(scene.cannon.getView().getState() + " " + reference(scene.cannon));
      }
    }
    assertThat(attacking(scene.cannon, "giant")).isTrue();
    assertThat(scene.cannon.getTargeting().getTargetLostTimerMs()).isZero();
    // The Knight leaves after the Cannon's visit of that step. On every later step until the
    // selector is free, the Cannon, a building with hit points, resets its attack and stands.
    List<String> locked = lines.subList(1, lines.size() - 1);
    assertThat(locked)
        .isNotEmpty()
        .allMatch(line -> line.equals(GridEntityState.STANDING + " null"));
  }

  /** Whether the entity attacks the named reference. */
  private static boolean attacking(WorldEntity entity, String name) {
    return entity.getView().getState() == GridEntityState.ATTACKING
        && name.equals(reference(entity));
  }
}
