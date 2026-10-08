package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.math.FixedMath;
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
 * front of its left princess tower, hooks it and pulls it to him until he lets go. The scene is
 * first run without a Fireball to find the hook's hold; a Fireball is then cast so that it lands on
 * the hold's fourth tick, on the Knight (the Fisherman's side casts it) or on the Fisherman (the
 * Knight's side casts it), where the run without it shows the unit then.
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

  /** The battle counter the scene gives up waiting for the hook at. */
  private static final int LAST = 600;

  /** The ticks into the hold the Fireball lands on, three after the hold's first. */
  private static final int INTO_THE_HOLD = 3;

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

  /**
   * What the scene without a Fireball shows of the unit of a play: from the battle counter the
   * plays have run by, its state and point after each step, by counter.
   */
  private record Run(int from, List<int[]> after) {

    static Run of(int play) {
      Standard1v1Battle match = scene();
      stepTo(match, FISHERMAN_TICK + 1);
      CharacterEntity unit = unit(match, play);
      List<int[]> after = new ArrayList<>();
      int from = match.getBattle().getTick();
      while (match.getBattle().getTick() < LAST) {
        after.add(
            new int[] {unit.getView().getState(), unit.getView().getX(), unit.getView().getY()});
        match.getBattle().step();
      }
      return new Run(from, after);
    }

    int[] at(int counter) {
      return after.get(counter - from);
    }

    /** The first counter from the given one on whose state is or is not the given one. */
    int first(int state, boolean is, int from) {
      for (int counter = from; counter < this.from + after.size(); counter++) {
        if ((at(counter)[0] == state) == is) {
          return counter;
        }
      }
      throw new AssertionError("no counter with state " + (is ? "" : "other than ") + state);
    }

    int[] point(int counter) {
      return new int[] {at(counter)[1], at(counter)[2]};
    }
  }

  /**
   * The battle counters a Fireball cast by a side at a point takes to land there: the counter after
   * the step of its impact less the tick it was cast on. A Golem of the other side stands still on
   * the point for it to land on.
   */
  private static int flight(int side, int x, int y) {
    Standard1v1Battle match = scene();
    match
        .deploy(0, GameData.unit("Golem"), 1, 1 - side, x, y, "aim")
        .setActive(CharacterEntity.MOVEMENT_SLOT, false);
    int cast = FISHERMAN_TICK;
    match.play(cast, GameData.card("Fireball"), FIREBALL_LEVEL, side, x, y, "B");
    int[] landed = {-1};
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void projectileImpacted(
                  int tick,
                  ProjectileEntity projectile,
                  WorldEntity target,
                  int damage,
                  DamageResult result) {
                if (landed[0] < 0 && projectile.getData().name().equals("FireballSpell")) {
                  landed[0] = tick + 1;
                }
              }
            });
    while (landed[0] < 0 && match.getBattle().getTick() < LAST) {
      match.getBattle().step();
    }
    assertThat(landed[0]).as("the Fireball lands").isPositive();
    return landed[0] - cast;
  }

  @Test
  @DisplayName(
      "a Fireball on a pulled Knight switches its movement on: the push flies during the pull and"
          + " moves it once the hook lets go")
  void thePulledKnightIsPushedAfterTheHookLetsGo() {
    Run dry = Run.of(0);
    int hooked = dry.first(GridEntityState.FOLLOWING_REMOVED, true, dry.from());
    int released = dry.first(GridEntityState.FOLLOWING_REMOVED, false, hooked);
    int lands = hooked + INTO_THE_HOLD;
    assertThat(released).as("the hold outlasts the landing").isGreaterThan(lands + 1);
    int[] aim = dry.point(lands);

    Standard1v1Battle match = scene();
    match.play(
        lands - flight(1, aim[0], aim[1]),
        GameData.card("Fireball"),
        FIREBALL_LEVEL,
        1,
        aim[0],
        aim[1],
        "B");
    List<Integer> fireball = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void projectileImpacted(
                  int tick,
                  ProjectileEntity projectile,
                  WorldEntity target,
                  int damage,
                  DamageResult result) {
                if (projectile.getData().name().equals("FireballSpell")
                    && target == unit(match, 0)) {
                  fireball.add(tick + 1);
                  fireball.add(damage);
                }
              }
            });
    stepTo(match, lands - 1);
    CharacterEntity knight = unit(match, 0);
    int before = knight.getHitPoints().getHitPoints();
    stepTo(match, lands);
    MovementState movement = knight.getUnit().movement();
    assertThat(fireball).as("the Fireball landed on it").hasSize(2).first().isEqualTo(lands);
    assertThat(knight.getView().getState()).isEqualTo(GridEntityState.FOLLOWING_REMOVED);
    assertThat(knight.getHitPoints().getHitPoints()).isEqualTo(before - fireball.get(1));
    assertThat(knight.isActive(CharacterEntity.MOVEMENT_SLOT)).as("movement on").isTrue();
    assertThat(knight.isActive(CharacterEntity.TARGETING_SLOT)).as("targeting still off").isFalse();
    int budget = movement.getPushbackBudget();
    assertThat(budget).isPositive();

    stepTo(match, lands + 1);
    int visit = budget - movement.getPushbackBudget();
    assertThat(visit).as("a visit's share").isPositive();
    for (int counter = lands + 1; counter < released; counter++) {
      stepTo(match, counter);
      assertThat(knight.getView().getState()).isEqualTo(GridEntityState.FOLLOWING_REMOVED);
      assertThat(movement.getPushbackBudget())
          .as("one visit per held tick, counter %d", counter)
          .isEqualTo(budget - (counter - lands) * visit);
      assertThat(new int[] {knight.getView().getX(), knight.getView().getY()})
          .as("the hook holds it, counter %d", counter)
          .containsExactly(dry.point(counter));
    }

    stepTo(match, released);
    assertThat(movement.getPushbackBudget()).isEqualTo(budget - (released - lands) * visit);
    assertThat(movement.getPushbackInFlight()).as("ended with the hold").isZero();
    int[] pushed = {knight.getView().getX(), knight.getView().getY()};
    assertThat(pushed).as("the last displacement").isNotEqualTo(dry.point(released));
    stepTo(match, released + 1);
    assertThat(new int[] {knight.getView().getX(), knight.getView().getY()})
        .as("the last displacement stays")
        .containsExactly(pushed);
  }

  @Test
  @DisplayName(
      "a Fireball on the Fisherman holding his hook switches his movement on: the push moves him"
          + " while he holds it")
  void theHoldingFishermanIsPushedWhileHeHolds() {
    Run dry = Run.of(1);
    int holding = dry.first(GridEntityState.COMPONENTS_DISABLED, true, dry.from());
    int letGo = dry.first(GridEntityState.COMPONENTS_DISABLED, false, holding);
    int lands = holding + INTO_THE_HOLD;
    assertThat(letGo).as("the hold outlasts the landing").isGreaterThan(lands + 1);
    int[] aim = dry.point(lands);

    Standard1v1Battle match = scene();
    match.play(
        lands - flight(0, aim[0], aim[1]),
        GameData.card("Fireball"),
        FIREBALL_LEVEL,
        0,
        aim[0],
        aim[1],
        "B");
    stepTo(match, lands);
    CharacterEntity fisherman = unit(match, 1);
    assertThat(fisherman.getView().getState()).isEqualTo(GridEntityState.COMPONENTS_DISABLED);
    assertThat(fisherman.isActive(CharacterEntity.MOVEMENT_SLOT)).as("movement on").isTrue();
    assertThat(fisherman.getUnit().movement().getPushbackBudget()).isPositive();

    // Each held tick after it moves him further from the blast, where the run without it keeps
    // him still.
    int distance = 0;
    for (int counter = lands + 1; counter < letGo; counter++) {
      stepTo(match, counter);
      assertThat(fisherman.getView().getState()).isEqualTo(GridEntityState.COMPONENTS_DISABLED);
      int dx = fisherman.getView().getX() - aim[0];
      int dy = fisherman.getView().getY() - aim[1];
      int now = FixedMath.isqrt(dx * dx + dy * dy);
      assertThat(now).as("pushed while he holds, counter %d", counter).isGreaterThan(distance);
      assertThat(dry.point(counter)).as("still without it").containsExactly(aim);
      distance = now;
    }

    int end = letGo;
    while (fisherman.getUnit().movement().getPushbackInFlight() != 0 && end < letGo + 20) {
      stepTo(match, ++end);
    }
    assertThat(fisherman.getUnit().movement().getPushbackInFlight()).isZero();
    assertThat(new int[] {fisherman.getView().getX(), fisherman.getView().getY()})
        .isNotEqualTo(dry.point(end));
  }
}
