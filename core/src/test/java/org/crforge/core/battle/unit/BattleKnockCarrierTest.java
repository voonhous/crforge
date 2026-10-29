/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.pathfinding.math.FixedMath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A knock and a push away from the cause started on a unit that carries riders, a Goblin Giant
 * played by its card: they run on the carrier as on any unit, and the riders, which never move on
 * their own, keep their places around it, so they go up, along and down with it. A clone is knocked
 * and pushed as any unit; a knock or a push started on a rider, and an uppercut's push reaching a
 * clone, are still refused.
 */
class BattleKnockCarrierTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The knock the evolved Mega Knight's uppercut gives. */
  private static final String KNOCK = "MegaKnight_EV1_uppercut_send_flying";

  /** The knock's updates: one a step until its duration is used up, and the landing one. */
  private static final int KNOCK_UPDATES = (Shipped.number(KNOCK, "Duration") + 49) / 50 + 1;

  /** The push away from its cause the Giant hero form's slap runs on the enemy it picks. */
  private static final String PUSH =
      Shipped.actionNames("GiantHero_Target_Selector", "Actions").get(0);

  /** The steps from the push's start to its push, its delay counted in steps. */
  private static final int PUSH_STEPS = Shipped.ticks(Shipped.number(PUSH, "PushbackDelay"));

  /** A battle with the towers passive, a Goblin Giant of side 1 and a Knight of side 0 by it. */
  private static final class Scene {
    final Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);

    /** The Goblin Giant, played by its card, as it makes its riders then. */
    final CharacterEntity carrier;

    /** The cause of a push, beside the Goblin Giant along the width. */
    final CharacterEntity knight;

    Scene() {
      match.play(0, GameData.card("GoblinGiant"), LEVEL, 1, 9000, 22000, "enemy");
      knight = match.deploy(0, GameData.unit("Knight"), LEVEL, 0, 7000, 22000, "knight");
      step(30);
      carrier =
          match.getPlays().get(0).units().stream()
              .filter(unit -> !unit.riders().isEmpty())
              .findFirst()
              .orElseThrow();
    }

    void step(int ticks) {
      for (int i = 0; i < ticks; i++) {
        match.getBattle().step();
      }
    }

    BattleAction row(String name, WorldEntity entity) {
      return match.getWorld().getActions().build(name, match.getWorld().binding(entity));
    }
  }

  /** How far a rider stands from a point. */
  private static int distance(CharacterEntity rider, int x, int y) {
    int dx = rider.getView().getX() - x;
    int dy = rider.getView().getY() - y;
    return FixedMath.isqrt(dx * dx + dy * dy);
  }

  /**
   * Steps once and checks each rider is still attached to the carrier and was put on its ring
   * around the carrier: the riders are visited before their carrier, so a rider stands around the
   * carrier's point as the step began.
   */
  private static void stepAndAssertRidersAround(Scene scene, int ring) {
    CharacterEntity carrier = scene.carrier;
    int x = carrier.getView().getX();
    int y = carrier.getView().getY();
    scene.step(1);
    for (CharacterEntity rider : carrier.riders()) {
      assertThat(rider.getParent()).isSameAs(carrier);
      assertThat(distance(rider, x, y)).as(rider.name() + " on its ring").isEqualTo(ring);
    }
  }

  /** The ring the riders stand on, from a step of the carrier walking. */
  private static int ring(Scene scene) {
    CharacterEntity carrier = scene.carrier;
    int x = carrier.getView().getX();
    int y = carrier.getView().getY();
    scene.step(1);
    List<CharacterEntity> riders = carrier.riders();
    assertThat(riders).isNotEmpty();
    int ring = distance(riders.get(0), x, y);
    assertThat(ring).isPositive();
    return ring;
  }

  @Test
  @DisplayName(
      "a knock started on a carrier lifts and lands it as any unit, its riders keeping their"
          + " places around it")
  void aKnockOnACarrierTakesItsRiders() {
    Scene scene = new Scene();
    CharacterEntity carrier = scene.carrier;
    int ring = ring(scene);

    carrier.actionHolder().start(scene.row(KNOCK, carrier), null);
    stepAndAssertRidersAround(scene, ring);
    stepAndAssertRidersAround(scene, ring);
    assertThat(carrier.getView().getHeightOffset()).as("lifted by the knock").isPositive();
    assertThat(carrier.getView().isAir()).isTrue();
    for (int k = 2; k < KNOCK_UPDATES; k++) {
      stepAndAssertRidersAround(scene, ring);
    }
    assertThat(carrier.getView().isAir()).as("landed").isFalse();
    assertThat(carrier.getUnit().movement().getRoute().isEmpty())
        .as("the route at landing")
        .isTrue();
  }

  @Test
  @DisplayName(
      "a push away from its cause started on a carrier pushes it, and its riders go along with it")
  void aPushOnACarrierTakesItsRiders() {
    Scene scene = new Scene();
    CharacterEntity carrier = scene.carrier;
    int ring = ring(scene);
    int startX = carrier.getView().getX();

    carrier.actionHolder().start(scene.row(PUSH, carrier), scene.knight.actionHolder());
    for (int k = 0; k < PUSH_STEPS + 6; k++) {
      stepAndAssertRidersAround(scene, ring);
    }
    // The cause stands on the carrier's left along the width: the push throws it to the right.
    assertThat(carrier.getView().getX()).as("pushed away from the Knight").isGreaterThan(startX);
  }

  @Test
  @DisplayName(
      "a knock started on a rider is refused: a rider knocked alone is held by no recording")
  void aKnockOnARiderIsRefused() {
    Scene scene = new Scene();
    CharacterEntity rider = scene.carrier.riders().get(0);

    assertThatThrownBy(() -> rider.actionHolder().start(scene.row(KNOCK, rider), null))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("a rider, not modelled");
  }

  /** A battle with the towers passive, a Knight clone of side 1 and a Knight of side 0 by it. */
  private static final class CloneScene {
    final Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    final CharacterEntity clone;
    final CharacterEntity cause;

    CloneScene() {
      clone = match.deploy(0, GameData.unit("Knight"), LEVEL, 1, 9000, 22000, "clone");
      cause = match.deploy(0, GameData.unit("Knight"), LEVEL, 0, 7000, 22000, "cause");
      step(30);
      clone.markClone(null);
    }

    void step(int ticks) {
      for (int i = 0; i < ticks; i++) {
        match.getBattle().step();
      }
    }

    BattleAction row(String name, WorldEntity entity) {
      return match.getWorld().getActions().build(name, match.getWorld().binding(entity));
    }
  }

  @Test
  @DisplayName("a knock started on a clone lifts it and puts it down as any unit, at 1 hit point")
  void aKnockOnACloneLiftsIt() {
    CloneScene scene = new CloneScene();
    CharacterEntity clone = scene.clone;

    clone.actionHolder().start(scene.row(KNOCK, clone), null);
    scene.step(2);
    assertThat(clone.getView().getHeightOffset()).as("lifted by the knock").isPositive();
    assertThat(clone.getView().isAir()).isTrue();
    scene.step(KNOCK_UPDATES);
    assertThat(clone.getView().isAir()).as("landed").isFalse();
    assertThat(clone.getHitPoints().getHitPoints()).isEqualTo(1);
    assertThat(clone.getHitPoints().getMaximum()).isEqualTo(1);
  }

  @Test
  @DisplayName("a push away from its cause started on a clone pushes it as any unit")
  void aPushOnACloneMovesIt() {
    CloneScene scene = new CloneScene();
    CharacterEntity clone = scene.clone;
    int startX = clone.getView().getX();

    clone.actionHolder().start(scene.row(PUSH, clone), scene.cause.actionHolder());
    scene.step(PUSH_STEPS + 6);
    // The cause stands on the clone's left along the width: the push throws it to the right.
    assertThat(clone.getView().getX()).as("pushed away from the cause").isGreaterThan(startX);
  }

  @Test
  @DisplayName(
      "an evolved Mega Knight's uppercut whose push reaches a clone is refused: no recording holds"
          + " it")
  void anUppercutOnACloneIsRefused() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    BattleWorld world = match.getWorld();
    CharacterEntity megaKnight =
        match.deploy(0, world.getRecords().unit("MegaKnight_EV1"), LEVEL, 0, 3500, 14000, "mk");
    CharacterEntity clone = match.deploy(0, GameData.unit("Knight"), LEVEL, 1, 3500, 15600, "k");
    for (int i = 0; i < 22; i++) {
      match.getBattle().step();
    }
    clone.markClone(null);
    megaKnight.getUnit().targeting().setReference(clone.getTargetView());
    megaKnight.setVariable(world.variableKey("MegaKnight_EV1_Do_Uppercut_Counter"), 0);
    megaKnight
        .actionHolder()
        .start(
            world.getActions().build("MegaKnight_EV1_uppercut", world.binding(megaKnight)),
            clone.actionHolder());

    assertThatThrownBy(
            () -> {
              for (int i = 0; i < 40; i++) {
                match.getBattle().step();
              }
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("pushes the clone");
  }
}
