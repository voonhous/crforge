package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.grid.CellGrid;
import org.crforge.core.pathfinding.grid.CellTests;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What a dash does to its dasher beyond its path, and what the battle refuses of a dash. */
class BattleDashTest {

  /**
   * A dasher's row. The Mega Knight is played by its card, which pushes as it deploys and casts its
   * appearance; placed directly here, with nothing in its reach, it is placed without its push.
   */
  private static UnitData dasherRow(String row) {
    UnitData data = GameData.unit(row);
    if (!row.equals("MegaKnight")) {
      return data;
    }
    assertThat(data.pushesOnDeploy()).isTrue();
    return data.toBuilder().spawnPushback(0).build();
  }

  /** A dasher placed for the bottom side with a red Knight ahead of it, the towers passive. */
  private static final class Scene {
    final Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
    final CharacterEntity dasher;

    Scene(String row) {
      dasher = match.deploy(0, dasherRow(row), 11, 0, 3500, 10000, "Dasher");
      match.deploy(0, GameData.unit("Knight"), 11, 1, 3500, 18000, "Knight");
    }

    /** Steps until the dasher is in the given state, and answers the tick it got there. */
    int stepUntil(int state) {
      for (int tick = 0; tick < 200; tick++) {
        match.getBattle().step();
        if (dasher.getView().getState() == state) {
          return tick;
        }
      }
      throw new AssertionError("the dasher never reached state " + state);
    }
  }

  @Test
  @DisplayName("a Bandit is untouchable while it dashes and for its immunity after, then not")
  void theBanditIsImmuneWhileItDashesAndJustAfter() {
    Scene scene = new Scene("Assassin");
    scene.stepUntil(GridEntityState.DASHING);
    assertThat(scene.dasher.untouchable()).isTrue();

    scene.stepUntil(GridEntityState.MOVING);
    // The immunity was topped up to the row's DashImmuneToDamageTime on its last dashing visit and
    // counts down 50 a visit: it holds on the visits that leave some of it, the walking visit's
    // among them, and is gone on the next.
    int immunity = Shipped.number(Shipped.unitRow("Assassin"), "DashImmuneToDamageTime");
    int held = (immunity + 49) / 50 - 1;
    for (int visit = 0; visit < held; visit++) {
      assertThat(scene.dasher.untouchable()).as("visit %d", visit).isTrue();
      scene.match.getBattle().step();
    }
    assertThat(scene.dasher.untouchable()).isFalse();
  }

  @Test
  @DisplayName("a Mega Knight, with no dash immunity, can be hurt while it dashes")
  void theMegaKnightCanBeHurtWhileItDashes() {
    Scene scene = new Scene("MegaKnight");
    scene.stepUntil(GridEntityState.DASHING);

    assertThat(scene.dasher.untouchable()).isFalse();
  }

  @Test
  @DisplayName(
      "a rolling Log passes under a Mega Knight in its jump, which has a jump height, and still"
          + " hits a walking unit behind it")
  void theLogPassesUnderAJumpingMegaKnight() {
    Scene scene = new Scene("MegaKnight");
    // A blue Knight behind the Mega Knight, which the Log reaches after it has passed the jump.
    CharacterEntity walker =
        scene.match.deploy(0, GameData.unit("Knight"), 11, 0, 3500, 8000, "Walker");
    // Red's Log is cast so that its rolling body sweeps down the lane while the Mega Knight jumps
    // at the red Knight: the Mega Knight leaves the ground at tick 60 and lands at tick 84, and the
    // body meets it on the way, about 1000 apart along the length, at tick 66.
    scene.match.play(40, GameData.card("Log"), 11, 1, 3500, 16000, "Log");
    List<String> hits = new ArrayList<>();
    List<Integer> dashingTicks = new ArrayList<>();
    scene
        .match
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
                if (projectile.getData().name().equals("LogProjectileRolling")) {
                  hits.add(
                      (target == scene.dasher ? "dasher" : target == walker ? "walker" : "other")
                          + " "
                          + target.getView().getState());
                }
              }
            });
    for (int tick = 0; tick < 120; tick++) {
      scene.match.getBattle().step();
      if (scene.dasher.getView().getState() == GridEntityState.DASHING) {
        dashingTicks.add(tick);
      }
    }

    assertThat(dashingTicks).as("the Mega Knight jumped").isNotEmpty();
    assertThat(scene.dasher.getData().jumpHeight()).isPositive();
    // The body's hit spares a dashing character whose row has a jump height, the Log not reaching
    // the air, and does not list it as hit; it lands out of the body's reach, so it is never hit.
    assertThat(hits).noneMatch(hit -> hit.startsWith("dasher"));
    assertThat(hits).anyMatch(hit -> hit.startsWith("walker"));
  }

  @Test
  @DisplayName("a Mega Knight's landing holds it in the dashing state until its landing time")
  void theMegaKnightIsHeldAfterItLands() {
    Scene scene = new Scene("MegaKnight");
    scene.stepUntil(GridEntityState.DASHING);
    while (scene.dasher.getView().getBlockCountdownMs() == 0) {
      scene.match.getBattle().step();
    }
    // The landing's own state visit takes the hold from 50 to 100; the visits after stand still
    // while the hold is short of the row's landing time and the one that reaches it walks on.
    int landing = Shipped.number(Shipped.unitRow("MegaKnight"), "DashLandingTime");
    int held = 0;
    while (true) {
      scene.match.getBattle().step();
      held++;
      if (scene.dasher.getView().getState() != GridEntityState.DASHING) {
        break;
      }
      assertThat(scene.dasher.getSpeedBudget()).isZero();
    }
    assertThat(held).isEqualTo((landing + 49) / 50 - 2);
    assertThat(scene.dasher.getView().getState()).isEqualTo(GridEntityState.MOVING);
    assertThat(scene.dasher.getView().getBlockCountdownMs()).isZero();
  }

  @Test
  @DisplayName("a Bandit whose dash stops over the river is moved off the water before it walks on")
  void theBanditLandingOnWaterIsMovedOffIt() {
    Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
    // In the middle of the arena, far from both bridges: a red Knight across the river, in the
    // Bandit's dash range, so the dash goes straight over the water and stops in reach of it.
    CharacterEntity bandit =
        match.deploy(0, GameData.unit("Assassin"), 11, 0, 9000, 12500, "Bandit");
    CharacterEntity knight = match.deploy(0, GameData.unit("Knight"), 11, 1, 9000, 18000, "Knight");
    CellGrid grid = match.getWorld().getGrid();
    List<int[]> landings = new ArrayList<>();
    List<int[]> requests = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void dashLanded(
                  int tick, CharacterEntity unit, WorldEntity hit, int damage, boolean area) {
                if (unit == bandit) {
                  landings.add(new int[] {tick, hit == knight ? 1 : 0});
                }
              }

              @Override
              public void movementStateRequested(int tick, CharacterEntity unit, int from, int to) {
                if (unit == bandit
                    && from == GridEntityState.DASHING
                    && to == GridEntityState.MOVING) {
                  GridEntity view = unit.getView();
                  requests.add(new int[] {tick, view.getX(), view.getY()});
                }
              }
            });

    int landedOn = -1;
    for (int tick = 0; tick < 200 && landedOn < 0; tick++) {
      match.getBattle().step();
      if (!landings.isEmpty()) {
        landedOn = landings.get(0)[0];
      }
    }

    assertThat(landedOn).as("the Bandit landed its dash").isNotNegative();
    assertThat(landings.get(0)[1]).as("its landing hit the Knight").isEqualTo(1);
    // The landing moves it off the water before it asks for the moving state, whose entry prepares
    // its route from where it stands.
    assertThat(requests).hasSize(1);
    int[] request = requests.get(0);
    assertThat(request[0]).isEqualTo(landedOn);
    assertThat(CellTests.cellBlocked(grid, request[1], request[2])).isZero();
    // The dash's own end writes the stop point back, moved off the water the same way.
    GridEntity view = bandit.getView();
    assertThat(view.getState()).isEqualTo(GridEntityState.MOVING);
    assertThat(new int[] {view.getX(), view.getY()}).containsExactly(request[1], request[2]);
    assertThat(view.getZ()).isZero();
  }

  @Test
  @DisplayName("a dash ability left pending under its pending buff is refused as it is requested")
  void aPendingDashAbilityIsRefused() {
    Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
    // The one configured ability with a pending buff is SuperHogJump's.
    CharacterEntity hog =
        match.deploy(0, GameData.unit("SuperHogRider_Terry"), 11, 0, 3500, 10000, "Hog");
    // Deploying, it has no reference: its gate is shut, and the request would wait under its
    // pending buff, which no reference holds.
    assertThat(hog.getView().getState()).isEqualTo(GridEntityState.DEPLOYING);
    assertThatThrownBy(hog::requestAbility)
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("SuperHogJumpCharge");
  }
}
