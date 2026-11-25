/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A knock on a Fisherman a hook on a building drags (following a removed building): the knock's own
 * code does not test that state, so a knock started on him then runs as on any unit, and so does a
 * knock already running when his hook lands on a building. The hook drags him on under it, on its
 * own step, as long as he can act.
 */
class BattleKnockDraggedFishermanTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /**
   * The push away from its cause the hero Giant's slap runs on the enemy it picks. On its success
   * it runs the slap on the target: the knock, the stun and an effect, all at once.
   */
  private static final String SLAP_PUSH =
      Shipped.actionNames("GiantHero_Target_Selector", "Actions").get(0);

  /** The slap on its target, the push's success action. */
  private static final String SLAP = Shipped.actionName(SLAP_PUSH, "SuccessAction");

  /** A knock with no stun and no cause to pass on: the evolved Mega Knight's. */
  private static final String UPPERCUT = "MegaKnight_EV1_uppercut_send_flying";

  /** The slap's knock, whose duration the tests step through. */
  private static final String SLAP_KNOCK = Shipped.actionNames(SLAP, "SubActions").get(0);

  /** The steps of the slap's knock, its landing one included. */
  private static final int SLAP_STEPS = (Shipped.number(SLAP_KNOCK, "Duration") + 49) / 50 + 1;

  /** The Fisherman's row: the ring his special loads in. */
  private static final GameRow FISHERMAN = Shipped.unitRow("Fisherman");

  /** The Fisherman's point, on the bottom side's left, away from every tower. */
  private static final int X = 3500;

  private static final int Y = 11000;

  /** The Cannon's distance from him, inside the ring his special loads in. */
  private static final int CANNON_DISTANCE =
      Shipped.number(Shipped.unitRow("Cannon"), "CollisionRadius")
          + Shipped.number(FISHERMAN, "SpecialMinRange")
          + 1500;

  /**
   * A battle with the towers passive, a Fisherman that never walks, an enemy Cannon he hooks and an
   * enemy Knight far off, the slap's cause.
   */
  private static final class Scene {
    final Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    final List<ProjectileEntity> hooks = new ArrayList<>();
    final List<Integer> knocks = new ArrayList<>();
    final List<Integer> knockStates = new ArrayList<>();
    final List<Integer> hookedAt = new ArrayList<>();
    final CharacterEntity fisherman;
    final CharacterEntity cause;
    int tick;

    Scene() {
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void projectileLaunched(int t, ProjectileEntity projectile) {
                  if (projectile.getData().dragBackSpeed() >= 1) {
                    hooks.add(projectile);
                  }
                }

                @Override
                public void knockbackStarted(
                    int t,
                    CharacterEntity unit,
                    String action,
                    int phase,
                    WorldEntity instigator,
                    int counter) {
                  knocks.add(t);
                  knockStates.add(unit.getView().getState());
                }

                @Override
                public void dragStateSet(
                    int t, ProjectileEntity projectile, WorldEntity unit, int oldState, int now) {
                  if (now == GridEntityState.FOLLOWING_REMOVED_BUILDING) {
                    hookedAt.add(t);
                  }
                }
              });
      fisherman = match.deploy(0, GameData.unit("Fisherman"), LEVEL, 0, X, Y, "F");
      fisherman.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      CharacterEntity cannon =
          match.deploy(0, GameData.unit("Cannon"), LEVEL, 1, X, Y + CANNON_DISTANCE, "C");
      cannon.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      cause = match.deploy(0, GameData.unit("Knight"), LEVEL, 1, 14500, 24000, "K");
      cause.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    }

    void step(int ticks) {
      for (int i = 0; i < ticks; i++) {
        match.getBattle().step();
        tick++;
      }
    }

    /** Steps until the hook is launched. */
    ProjectileEntity stepToTheLaunch() {
      while (hooks.isEmpty() && tick < 200) {
        step(1);
      }
      assertThat(hooks).isNotEmpty();
      return hooks.get(0);
    }

    /** Steps until the hook has hooked the Cannon, and through that tick. */
    ProjectileEntity stepToTheHook() {
      ProjectileEntity hook = stepToTheLaunch();
      while (!hook.isHooked() && tick < 200) {
        step(1);
      }
      assertThat(hook.isHooked()).isTrue();
      return hook;
    }

    /** Starts the slap's push on the Fisherman, the Knight its cause. */
    void slap() {
      BattleAction row =
          match.getWorld().getActions().build(SLAP_PUSH, match.getWorld().binding(fisherman));
      fisherman.actionHolder().start(row, cause.actionHolder());
    }

    /** Starts the uppercut's knock on the Fisherman, with no cause. */
    void uppercut() {
      BattleAction row =
          match.getWorld().getActions().build(UPPERCUT, match.getWorld().binding(fisherman));
      fisherman.actionHolder().start(row, null);
    }
  }

  @Test
  @DisplayName(
      "a slap started on a Fisherman a hook on a building drags knocks him: the hook lets him go"
          + " once the stun takes hold, and he stands there under the knock")
  void aSlapStartedOnADraggedFisherman() {
    Scene scene = new Scene();
    ProjectileEntity hook = scene.stepToTheHook();
    GridEntity f = scene.fisherman.getView();
    assertThat(f.getState()).isEqualTo(GridEntityState.FOLLOWING_REMOVED_BUILDING);

    scene.slap();
    while (scene.knocks.isEmpty() && scene.tick < 200) {
      scene.step(1);
    }
    scene.step(3);
    assertThat(scene.knockStates)
        .as("the knock started as the hook dragged him")
        .containsExactly(GridEntityState.FOLLOWING_REMOVED_BUILDING);
    assertThat(hook.isReleased()).as("the hook let him go").isTrue();
    assertThat(f.getState()).isNotEqualTo(GridEntityState.FOLLOWING_REMOVED_BUILDING);
    assertThat(f.getHeightOffset()).as("lifted by the knock").isPositive();
    int stoodAt = f.getY();
    scene.step(SLAP_STEPS);
    assertThat(f.getY()).as("where the hook let him go").isEqualTo(stoodAt);
  }

  @Test
  @DisplayName(
      "a slap whose knock starts on the step the Fisherman's hook lands on a building goes on: the"
          + " hook lets him go once the stun takes hold")
  void aSlapStartingAsTheHookLands() {
    // The step the hook lands, from a battle without the slap.
    Scene plain = new Scene();
    plain.stepToTheHook();
    int landed = plain.hookedAt.get(0);

    // The slap's knock starts after the push's delay: try each start until the knock and the
    // landing fall on the same step.
    Scene scene = null;
    for (int lead = 1; lead <= 12; lead++) {
      Scene tried = new Scene();
      while (tried.match.getBattle().getTick() < landed - lead) {
        tried.step(1);
      }
      tried.slap();
      tried.step(1 + lead);
      if (!tried.knocks.isEmpty()
          && !tried.hookedAt.isEmpty()
          && tried.knocks.get(0).equals(tried.hookedAt.get(0))) {
        scene = tried;
        break;
      }
    }
    assertThat(scene).as("a slap whose knock starts on the landing step").isNotNull();
    scene.step(3);

    assertThat(scene.hooks.get(0).isReleased()).as("the hook let him go").isTrue();
    assertThat(scene.fisherman.getView().getState())
        .isNotEqualTo(GridEntityState.FOLLOWING_REMOVED_BUILDING);
    scene.step(SLAP_STEPS);
  }

  @Test
  @DisplayName(
      "a knock with no stun started on a Fisherman a hook on a building drags is refused at its"
          + " update, the hook dragging him on with his targeting on")
  void anUppercutStartedOnADraggedFishermanIsRefused() {
    Scene scene = new Scene();
    scene.stepToTheHook();
    scene.step(1);
    assertThat(scene.fisherman.getView().getState())
        .isEqualTo(GridEntityState.FOLLOWING_REMOVED_BUILDING);

    scene.uppercut();
    assertThat(scene.knocks).hasSize(1);
    assertThatThrownBy(() -> scene.step(1))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("while a hook on a building drags it on, its targeting on");
  }

  @Test
  @DisplayName(
      "a knock with no stun running on a Fisherman when his hook lands on a building is refused at"
          + " its next update")
  void anUppercutRunningWhenTheHookLandsIsRefused() {
    Scene scene = new Scene();
    ProjectileEntity hook = scene.stepToTheLaunch();
    scene.step(1);
    assertThat(hook.isHooked()).isFalse();
    scene.uppercut();

    assertThatThrownBy(
            () -> {
              while (scene.tick < 200) {
                scene.step(1);
              }
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("while a hook on a building drags it on, its targeting on");
    assertThat(hook.isHooked()).isTrue();
  }
}
