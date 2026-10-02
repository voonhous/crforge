package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.EntityActions;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.target.TargetView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Golden Knight's chained dash where its runs do not take it: a request with its target beyond
 * the dash range, a chain that reaches its count, a stun that lands between the request and the
 * dash, and a chain whose target is a crown tower.
 */
class BattleGoldenKnightTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Long enough for any chain here to start and end. */
  private static final int TICKS = 300;

  /** A Golden Knight placed for the bottom side, with what its ability and chain did. */
  private static final class Scene {
    final Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    final CharacterEntity knight;
    final List<List<String>> cleansed = new ArrayList<>();
    final List<String> started = new ArrayList<>();
    final List<String> ends = new ArrayList<>();

    Scene(int x, int y) {
      knight = match.deploy(0, GameData.unit("GoldenKnight"), LEVEL, 0, x, y, "GoldenKnight");
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void abilityDashed(
                    int tick,
                    CharacterEntity unit,
                    List<CharacterEntity.DashCandidate> candidates,
                    List<String> removed,
                    WorldEntity chosen) {
                  cleansed.add(removed);
                }

                @Override
                public void chainDashStarted(
                    int tick,
                    CharacterEntity unit,
                    int fromX,
                    int fromY,
                    int aimX,
                    int aimY,
                    int radius) {
                  started.add(unit.getUnit().targeting().getReference().getEntity().getName());
                }

                @Override
                public void chainDashEnded(
                    int tick, CharacterEntity unit, int count, TargetView reference) {
                  ends.add(
                      count + " " + (reference == null ? null : reference.getEntity().getName()));
                }
              });
    }

    /** An enemy standing still, placed directly. */
    CharacterEntity enemy(String row, int x, int y, String name) {
      CharacterEntity enemy = match.deploy(0, GameData.unit(row), LEVEL, 1, x, y, name);
      enemy.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      return enemy;
    }

    /** Steps until the Golden Knight walks with a reference, and answers that reference. */
    TargetView walkingWithReference() {
      for (int tick = 0; tick < TICKS; tick++) {
        match.getBattle().step();
        TargetView reference = knight.getUnit().targeting().getReference();
        if (knight.getView().getState() == GridEntityState.MOVING && reference != null) {
          return reference;
        }
      }
      throw new AssertionError("the Golden Knight never walked with a reference");
    }

    /** Steps until its chain ends. */
    void untilTheChainEnds() {
      for (int tick = 0; tick < TICKS && ends.isEmpty(); tick++) {
        match.getBattle().step();
      }
      assertThat(ends).as("the chain's end").hasSize(1);
    }
  }

  @Test
  @DisplayName("a request with the target beyond the dash range waits, which is refused")
  void aRequestBeyondTheDashRangeIsRefused() {
    Scene scene = new Scene(3500, 8000);
    TargetView reference = scene.walkingWithReference();
    assertThat(reference.getEntity().getName()).isEqualTo("PrincessTower_1_1");
    int dy = reference.y() - scene.knight.getView().getY();
    assertThat(dy).as("the tower lies beyond the 5500 dash range").isGreaterThan(5500);

    assertThatThrownBy(scene.knight::requestAbility)
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("left pending with GoldenKnightCharge");
  }

  @Test
  @DisplayName("a chain stops at its tenth dash with targets left in reach")
  void aChainStopsAtItsCount() {
    Scene scene = new Scene(3500, 12000);
    for (int i = 0; i < 14; i++) {
      scene.enemy("Skeleton", 2900 + 400 * (i % 4), 15900 + 400 * (i / 4), "s" + i);
    }
    scene.walkingWithReference();

    scene.knight.requestAbility();
    scene.untilTheChainEnds();

    assertThat(scene.started).hasSize(10).doesNotHaveDuplicates();
    // Each landing kills its Skeleton, the tenth too, so the chain ends with no reference.
    assertThat(scene.ends).containsExactly("10 null");
  }

  @Test
  @DisplayName("a stun landing between the request and the dash is removed, and the chain runs")
  void aStunBeforeTheDashIsRemoved() {
    Scene scene = new Scene(3500, 12000);
    CharacterEntity enemy = scene.enemy("Knight", 3500, 16000, "Knight");
    scene.walkingWithReference();
    // The request, then a freeze on the Golden Knight, both in its phase-2 pass: the handler runs
    // in the post-hooks that follow.
    scene
        .knight
        .actionHolder()
        .schedule(
            new BattleAction() {
              @Override
              public String name() {
                return "request, then freeze";
              }

              @Override
              public int phase() {
                return EntityActions.PHASE_POST_COMPONENT_TICK;
              }

              @Override
              public ActionInstance start(ActionHolder holder) {
                scene.knight.requestAbility();
                scene
                    .knight
                    .getBuffs()
                    .apply(
                        scene.match.getWorld().buffData("ZapFreeze"),
                        500,
                        enemy.getPackedLevel(),
                        enemy,
                        enemy.side());
                assertThat(scene.knight.getBuffs().carries("ZapFreeze")).isTrue();
                return null;
              }
            },
            ActionHolder.OWN_DELAY);
    scene.match.getBattle().step();

    assertThat(scene.cleansed).containsExactly(List.of("ZapFreeze"));
    assertThat(scene.knight.getBuffs().carries("ZapFreeze")).isFalse();
    assertThat(scene.started).containsExactly("Knight");
    assertThat(scene.knight.getView().getState()).isEqualTo(GridEntityState.DASHING);
  }

  @Test
  @DisplayName("a chain whose target is a crown tower stops there, with another target in reach")
  void aChainStopsAtACrownTower() {
    Scene scene = new Scene(3500, 21500);
    // Farther from the Golden Knight than the tower, and within the secondary range of its landing.
    scene.enemy("Skeleton", 8000, 24000, "Skeleton");
    scene.walkingWithReference();

    scene.knight.requestAbility();
    scene.untilTheChainEnds();

    assertThat(scene.started).containsExactly("PrincessTower_1_1");
    assertThat(scene.ends).containsExactly("1 PrincessTower_1_1");
    // The dash's end drops the reference, and no targeting visit has run since.
    assertThat(scene.knight.getUnit().targeting().getReference()).isNull();
  }
}
