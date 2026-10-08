package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.nio.file.Path;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.move.MovementState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
 *
 * <p>The scene writes every column its outcome is read from, so its ticks, budgets and points are
 * its own and not a version's: the Knight's, the Fisherman's and his hook's rows, the Fireball's
 * flight, circle, damage and push, the towers' places and the columns of theirs the walk, the hook
 * and the cast read. The Fireball is played at the first level, where its damage is its row's.
 */
class BattleHookedAreaPushTest {

  /** The levels a replay's level index 0 gives the three cards: Common, Legendary and Rare. */
  private static final int KNIGHT_LEVEL = 1;

  private static final int FISHERMAN_LEVEL = 9;

  private static final int FIREBALL_LEVEL = 1;

  /** The Knight's hit points, as the scene writes them. */
  private static final int KNIGHT_HIT_POINTS = 690;

  /** The Fireball's damage and push, as the scene writes them. */
  private static final int FIREBALL_DAMAGE = 325;

  private static final int FIREBALL_PUSHBACK = 1000;

  /** How far a pushback's budget falls per visit, in game units: the battle's own step. */
  private static final int BUDGET_STEP = 25;

  /**
   * The budget a pushback of the given length starts with: the smallest multiple of the step whose
   * triangular sum of steps covers it.
   */
  private static int budget(int distance) {
    int step = 0;
    int total = 0;
    do {
      step += BUDGET_STEP;
      total += step;
    } while (total < distance);
    return step;
  }

  @TempDir static Path tablesFolder;

  /** The configured tables with the columns the scene reads written. */
  private static GameTables tables;

  @BeforeAll
  static void writeTheScene() throws IOException {
    GameData.altered(
        tablesFolder,
        "characters",
        rows -> {
          GameData.columns(rows, "Knight")
              .put("Hitpoints", KNIGHT_HIT_POINTS)
              .put("Damage", 79)
              .put("Speed", 60)
              .put("Mass", 6)
              .put("CollisionRadius", 500)
              .put("Range", 1200)
              .put("SightRange", 5500)
              .put("HitSpeed", 1200)
              .put("LoadTime", 700)
              .put("DeployTime", 1000);
          GameData.columns(rows, "Fisherman")
              .put("Hitpoints", 340)
              .put("Damage", 76)
              .put("Speed", 60)
              .put("Mass", 10)
              .put("CollisionRadius", 500)
              .put("Range", 1200)
              .put("SightRange", 7500)
              .put("HitSpeed", 1300)
              .put("LoadTime", 1200)
              .put("DeployTime", 1000)
              .put("DeployDelay", 300)
              .put("SpecialLoadTime", 1300)
              .put("SpecialMinRange", 3500)
              .put("SpecialRange", 7000)
              .put("ProjectileStartRadius", 450)
              .put("ProjectileStartZ", 450);
        });
    GameData.alterLoaded(
        tablesFolder,
        "projectiles",
        rows -> {
          GameData.columns(rows, "FishermanProjectile")
              .put("Speed", 800)
              .put("DragBackSpeed", 850)
              .put("DragMargin", 200)
              .put("DragSelfSpeed", 450)
              .put("HomingMinDistance", 5000)
              .put("HomingTime", 100);
          GameData.columns(rows, "FireballSpell")
              .put("Speed", 600)
              .put("Radius", 2500)
              .put("Damage", FIREBALL_DAMAGE)
              .put("Pushback", FIREBALL_PUSHBACK);
        });
    GameData.alterLoaded(
        tablesFolder,
        "buildings",
        rows -> {
          GameData.columns(rows, "PrincessTower")
              .put("CollisionRadius", 1000)
              .put("Range", 7500)
              .put("SightRange", 7500)
              .put("HitSpeed", 800)
              .put("Hitpoints", 1400)
              .put("ProjectileStartRadius", 300)
              .put("ProjectileStartZ", 3000)
              .put("NoDeploySizeW", 11)
              .put("NoDeploySizeH", 21);
          GameData.columns(rows, "KingTower")
              .put("CollisionRadius", 1400)
              .put("Range", 7000)
              .put("SightRange", 7000)
              .put("HitSpeed", 1000)
              .put("LoadTime", 500)
              .put("Hitpoints", 2400)
              .put("ProjectileStartRadius", 750)
              .put("ProjectileStartZ", 3500)
              .put("ProjectileYOffset", 400)
              .put("NoDeploySizeW", 18)
              .put("NoDeploySizeH", 16);
        });
    GameData.alterLoaded(
        tablesFolder,
        "spells_characters",
        rows -> GameData.columns(rows, "Fisherman").put("SummonNumber", 1));
    GameData.alterLoaded(
        tablesFolder,
        "spawn_groups",
        rows -> {
          ArrayNode towers = GameData.columns(rows, "King_PrincessTowers").putArray("Objects");
          towers.addObject().put("Data", "KingTower").put("x", 18).put("y", 6);
          towers.addObject().put("Data", "PrincessTower").put("x", 7).put("y", 13);
          towers.addObject().put("Data", "PrincessTower").put("x", 29).put("y", 13);
        });
    tables = GameTables.load(tablesFolder);
  }

  /** The battle stream's seed of the scene. */
  private static final int SEED = 1131;

  /** The Knight's play, then the Fisherman's. */
  private static final int KNIGHT_TICK = 220;

  private static final int FISHERMAN_TICK = 230;

  /** The towers at the first level, fighting; the Knight and the Fisherman played. */
  private static Standard1v1Battle scene() {
    Standard1v1Battle match = new Standard1v1Battle(tables, 1, true);
    match.getWorld().seed(SEED);
    match.play(KNIGHT_TICK, card(match, "Knight"), KNIGHT_LEVEL, 0, 3500, 14000, "K");
    match.play(FISHERMAN_TICK, card(match, "Fisherman"), FISHERMAN_LEVEL, 1, 3500, 22000, "F");
    return match;
  }

  /** A card of the scene's tables. */
  private static DeployCard card(Standard1v1Battle match, String name) {
    return match.getWorld().getRecords().card(name);
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
    match.play(267, card(match, "Fireball"), FIREBALL_LEVEL, 1, 3350, 18800, "B");
    stepTo(match, 287);
    CharacterEntity knight = unit(match, 0);
    MovementState movement = knight.getUnit().movement();
    assertThat(knight.getView().getState()).isEqualTo(GridEntityState.FOLLOWING_REMOVED);
    assertThat(knight.getHitPoints().getHitPoints())
        .as("the Fireball landed")
        .isEqualTo(KNIGHT_HIT_POINTS - FIREBALL_DAMAGE);
    assertThat(knight.isActive(CharacterEntity.MOVEMENT_SLOT)).as("movement on").isTrue();
    assertThat(knight.isActive(CharacterEntity.TARGETING_SLOT)).as("targeting still off").isFalse();
    // The push's whole budget, not yet flown: 225 for the written 1000.
    int budget = budget(FIREBALL_PUSHBACK);
    assertThat(movement.getPushbackBudget()).isEqualTo(budget);

    stepTo(match, 292);
    assertThat(knight.getView().getState()).isEqualTo(GridEntityState.FOLLOWING_REMOVED);
    assertThat(movement.getPushbackBudget())
        .as("one visit per held tick")
        .isEqualTo(budget - 5 * BUDGET_STEP);
    // The points below are the scene's own: every column they follow from is written above.
    assertThat(knight.getView().getX()).as("the hook holds it").isEqualTo(3433);
    assertThat(knight.getView().getY()).isEqualTo(20820);

    stepTo(match, 293);
    assertThat(movement.getPushbackBudget()).isEqualTo(budget - 6 * BUDGET_STEP);
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
    match.play(253, card(match, "Fireball"), FIREBALL_LEVEL, 0, 3500, 22500, "B");
    stepTo(match, 287);
    CharacterEntity fisherman = unit(match, 1);
    assertThat(fisherman.getView().getState()).isEqualTo(GridEntityState.COMPONENTS_DISABLED);
    assertThat(fisherman.isActive(CharacterEntity.MOVEMENT_SLOT)).as("movement on").isTrue();
    assertThat(fisherman.getUnit().movement().getPushbackBudget())
        .isEqualTo(budget(FIREBALL_PUSHBACK));

    // The points below are the scene's own: every column they follow from is written above.
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
