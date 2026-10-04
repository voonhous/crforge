package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.battle.GameData;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.move.MovementState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An area's push on a unit a Fisherman's hook holds: the hook has switched the unit's movement
 * component off (the pulled troop, and the Fisherman holding the hook), and the push switches it
 * back on as one component, so its pushback flies from the next visit while the hook still holds
 * it.
 *
 * <p>The scene: the bottom side's Knight walks up the left lane, the top side's Fisherman stands in
 * front of its left princess tower and hooks it on tick 284, the hook pulls the Knight to the
 * Fisherman until it lets go on tick 293; a Fireball lands on tick 287 on the Knight (the
 * Fisherman's side casts it) or on the Fisherman (the Knight's side casts it).
 */
class BattleHookedAreaPushTest {

  /** The levels a replay's level index 0 gives the three cards: Common, Legendary and Rare. */
  private static final int KNIGHT_LEVEL = 1;

  private static final int FISHERMAN_LEVEL = 9;

  private static final int FIREBALL_LEVEL = 3;

  /** The battle stream's seed of the scene. */
  private static final int SEED = 1131;

  /** The Knight's play, then the Fisherman's. */
  private static final int KNIGHT_TICK = 220;

  private static final int FISHERMAN_TICK = 230;

  /** The towers at the first level, fighting; the Knight and the Fisherman played. */
  private static Standard1v1Battle scene() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), 1, true);
    match.getWorld().seed(SEED);
    match.play(KNIGHT_TICK, GameData.card("Knight"), KNIGHT_LEVEL, 0, 3500, 14000, "K");
    match.play(FISHERMAN_TICK, GameData.card("Fisherman"), FISHERMAN_LEVEL, 1, 3500, 22000, "F");
    return match;
  }

  /** Steps the battle until its tick is the given one. */
  private static void stepTo(Standard1v1Battle match, int tick) {
    while (match.getBattle().getTick() < tick) {
      match.getBattle().step();
    }
  }

  /** The first unit of the play at the given index. */
  private static CharacterEntity unit(Standard1v1Battle match, int play) {
    return match.getPlays().get(play).units().get(0);
  }

  @Test
  @DisplayName(
      "a Fireball on a pulled Knight switches its movement on: the push flies during the pull and"
          + " moves it once the hook lets go")
  void thePulledKnightIsPushedAfterTheHookLetsGo() {
    Standard1v1Battle match = scene();
    match.play(267, GameData.card("Fireball"), FIREBALL_LEVEL, 1, 3350, 18800, "B");
    stepTo(match, 287);
    CharacterEntity knight = unit(match, 0);
    MovementState movement = knight.getUnit().movement();
    assertThat(knight.getView().getState()).isEqualTo(GridEntityState.FOLLOWING_REMOVED);
    assertThat(knight.getHitPoints().getHitPoints()).as("the Fireball landed").isEqualTo(365);
    assertThat(knight.isActive(CharacterEntity.MOVEMENT_SLOT)).as("movement on").isTrue();
    assertThat(knight.isActive(CharacterEntity.TARGETING_SLOT)).as("targeting still off").isFalse();
    assertThat(movement.getPushbackBudget()).isEqualTo(225);

    stepTo(match, 292);
    assertThat(knight.getView().getState()).isEqualTo(GridEntityState.FOLLOWING_REMOVED);
    assertThat(movement.getPushbackBudget()).as("one visit per held tick").isEqualTo(100);
    assertThat(knight.getView().getX()).as("the hook holds it").isEqualTo(3433);
    assertThat(knight.getView().getY()).isEqualTo(20820);

    stepTo(match, 293);
    assertThat(movement.getPushbackBudget()).isEqualTo(75);
    assertThat(movement.getPushbackInFlight()).as("ended with the hold").isZero();
    assertThat(knight.getView().getX()).as("the last displacement stays").isEqualTo(3419);
    assertThat(knight.getView().getY()).isEqualTo(20747);
  }

  @Test
  @DisplayName(
      "a Fireball on the Fisherman holding his hook switches his movement on: the push moves him"
          + " while he holds it")
  void theHoldingFishermanIsPushedWhileHeHolds() {
    Standard1v1Battle match = scene();
    match.play(253, GameData.card("Fireball"), FIREBALL_LEVEL, 0, 3500, 22500, "B");
    stepTo(match, 287);
    CharacterEntity fisherman = unit(match, 1);
    assertThat(fisherman.getView().getState()).isEqualTo(GridEntityState.COMPONENTS_DISABLED);
    assertThat(fisherman.isActive(CharacterEntity.MOVEMENT_SLOT)).as("movement on").isTrue();
    assertThat(fisherman.getUnit().movement().getPushbackBudget()).isEqualTo(225);

    stepTo(match, 292);
    assertThat(fisherman.getView().getState()).isEqualTo(GridEntityState.COMPONENTS_DISABLED);
    assertThat(fisherman.getView().getX()).isEqualTo(2971);
    assertThat(fisherman.getView().getY()).isEqualTo(21971);

    stepTo(match, 297);
    assertThat(fisherman.getUnit().movement().getPushbackInFlight()).isZero();
    assertThat(fisherman.getView().getX()).isEqualTo(2883);
    assertThat(fisherman.getView().getY()).isEqualTo(21883);
  }
}
