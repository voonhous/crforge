/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.battle.Shipped.actionNames;
import static org.crforge.core.battle.Shipped.number;
import static org.crforge.core.battle.Shipped.row;
import static org.crforge.core.battle.Shipped.text;
import static org.crforge.core.battle.Shipped.unitRow;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evolved Snowball's rolling snowball rolling into an enemy Monk's Deflect. Its row names its
 * own deflection action: the Deflect does not turn it around but runs that action on it, which
 * counts the deflection and hands it the Monk's side; the roll reads the count at its next step and
 * takes its destination again from where the snowball stands, for its new side, by its distance
 * less what it has rolled, so it rolls back by the rest of its distance.
 */
class SnowballRollDeflectTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Long enough for the Monk to cast, the Snowball to land and its snowball to roll out. */
  private static final int TICKS = 400;

  /** The area effect the Monk's ability spawns as it activates: its Deflect. */
  private static final String DEFLECT =
      actionNames(
              text(
                  row("character_abilities", text(unitRow("Monk"), "Ability")),
                  "OnActivationAction"),
              "SubActions")
          .stream()
          .filter(action -> "AreaEffectType".equals(text(action, "SpawnType")))
          .map(action -> text(action, "SpawnData"))
          .findFirst()
          .orElseThrow();

  /** The evolved Snowball's snowball, which lands and spawns the rolling one. */
  private static final String CAST = text(row("spells_evolved", "Snowball_EV1"), "Projectile");

  /** The rolling snowball's row. */
  private static final String ROLLING = text(row("projectiles", CAST), "SpawnProjectile");

  /** The roll its starting group runs. */
  private static final String ROLL =
      actionNames(text(row("projectiles", ROLLING), "OnStartingAction"), "SubActions").stream()
          .filter(action -> "ActionRollingProjectile".equals(text(action, "ClassType")))
          .findFirst()
          .orElseThrow();

  /** How far the roll goes down its side's forward. */
  private static final int DISTANCE = number(ROLL, "DistanceY");

  /** One count of a deflection by the row's own action. */
  private record Turn(int tick, ProjectileEntity projectile, WorldEntity deflector, int y) {}

  @Test
  @DisplayName(
      "the rolling snowball meeting an enemy Monk's Deflect takes the Monk's side, counts one"
          + " deflection and rolls back from where it stands by the rest of its distance")
  void theRollTurnsBackByTheRestOfItsDistance() {
    GameTables tables = GameData.tables();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    List<Turn> turns = new ArrayList<>();
    List<Integer> starts = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void rollStarted(
                  int tick,
                  ProjectileEntity projectile,
                  String action,
                  int phase,
                  int destinationX,
                  int destinationY) {
                starts.add(projectile.getY());
              }

              @Override
              public void projectileSelfDeflected(
                  int tick, ProjectileEntity projectile, String action, WorldEntity deflector) {
                turns.add(new Turn(tick, projectile, deflector, projectile.getY()));
              }
            });
    int[] ticks = {0};
    Runnable step =
        () -> {
          match.getBattle().step();
          assertThat(++ticks[0]).as("the scene plays out in time").isLessThan(TICKS);
        };
    // Side 1's Monk stands in the middle of side 0's half with its Deflect up.
    CharacterEntity monk = match.deploy(1, records.unit("Monk"), LEVEL, 1, 9000, 12500, "monk");
    while (monk.getId() == 0
        || monk.getView().getState() == GridEntityState.DEPLOYING
        || monk.getView().getState() == GridEntityState.WAITING_TO_DEPLOY) {
      step.run();
    }
    monk.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    monk.requestAbility();
    while (deflects(match).isEmpty()) {
      step.run();
    }
    // Side 0 casts the evolved Snowball short of it: the snowball rolls up the length into it.
    match.play(
        match.getBattle().getTick() + 1, records.card("Snowball_EV1"), LEVEL, 0, 9000, 9500, "S");
    while (turns.isEmpty()) {
      step.run();
    }
    Turn turn = turns.get(0);
    ProjectileEntity snowball = turn.projectile();
    assertThat(snowball.getData().name()).startsWith(ROLLING);
    assertThat(turn.deflector()).isSameAs(monk);
    assertThat(snowball.side()).as("the Monk's side").isEqualTo(monk.side());
    assertThat(snowball.getDeflections()).isEqualTo(1);
    int rolled = turn.y() - starts.get(0);
    assertThat(rolled).as("it rolled up the length before it turned").isPositive();
    assertThat(rolled).isLessThan(DISTANCE);
    List<Integer> ys = new ArrayList<>();
    while (!snowball.isReleased()) {
      step.run();
      ys.add(snowball.getY());
    }
    // It rolls back down the length, the Monk's forward, and ends the rest of its distance from
    // where it turned.
    assertThat(ys.get(0)).as("its first step after the turn").isLessThan(turn.y());
    assertThat(snowball.getY()).isEqualTo(turn.y() - (DISTANCE - rolled));
    assertThat(turns).as("one deflection").hasSize(1);
  }

  @Test
  @DisplayName(
      "the rolling snowball turned back and forth between two enemy Monks' Deflects is released"
          + " where it stands by the deflection that finds it at the most a projectile takes")
  void theRollPastTheMostDeflectionsIsReleased() {
    GameTables tables = GameData.tables();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    int most = match.getWorld().globalNumber("MAX_DEFLECTION_TIMES");
    List<Turn> turns = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void projectileSelfDeflected(
                  int tick, ProjectileEntity projectile, String action, WorldEntity deflector) {
                turns.add(new Turn(tick, projectile, deflector, projectile.getY()));
              }
            });
    int[] ticks = {0};
    Runnable step =
        () -> {
          match.getBattle().step();
          assertThat(++ticks[0]).as("the scene plays out in time").isLessThan(TICKS);
        };
    // Side 1's Monk stands in the middle of side 0's half, side 0's own Monk further down the
    // length; both stand still with their Deflects up, far enough apart that neither stands near
    // the other's Deflect.
    CharacterEntity monk1 = match.deploy(1, records.unit("Monk"), LEVEL, 1, 9000, 12500, "monk1");
    CharacterEntity monk0 = match.deploy(1, records.unit("Monk"), LEVEL, 0, 9000, 7000, "monk0");
    while (!deployed(monk1) || !deployed(monk0)) {
      step.run();
    }
    for (CharacterEntity monk : List.of(monk0, monk1)) {
      monk.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      monk.requestAbility();
    }
    while (deflects(match).size() < 2) {
      step.run();
    }
    // Side 0 casts the evolved Snowball between them: side 1's Monk turns the snowball back down,
    // side 0's turns it up again, and side 1's meets it a third time.
    match.play(
        match.getBattle().getTick() + 1, records.card("Snowball_EV1"), LEVEL, 0, 9000, 9500, "S");
    while (turns.size() <= most) {
      step.run();
    }
    assertThat(turns).extracting(Turn::deflector).containsExactly(monk1, monk0, monk1);
    Turn last = turns.get(most);
    ProjectileEntity snowball = last.projectile();
    assertThat(snowball.getData().name()).startsWith(ROLLING);
    assertThat(turns).extracting(Turn::projectile).containsOnly(snowball);
    // The deflection that finds it at the most counts and hands the side all the same, then
    // releases it where it stands: it takes no further step and has left by the step's end.
    assertThat(snowball.getDeflections()).isEqualTo(most + 1);
    assertThat(snowball.side()).as("the last Monk's side").isEqualTo(monk1.side());
    assertThat(snowball.isReleased()).isTrue();
    assertThat(snowball.getY()).isEqualTo(last.y());
    assertThat(match.getWorld().getHolder().entities()).doesNotContain(snowball);
    step.run();
    assertThat(turns).as("no deflection after the release").hasSize(most + 1);
  }

  private static boolean deployed(CharacterEntity monk) {
    return monk.getId() != 0
        && monk.getView().getState() != GridEntityState.DEPLOYING
        && monk.getView().getState() != GridEntityState.WAITING_TO_DEPLOY;
  }

  private static List<AreaEffectEntity> deflects(Standard1v1Battle match) {
    List<AreaEffectEntity> out = new ArrayList<>();
    for (BattleEntity entity : match.getWorld().getHolder().entities()) {
      if (entity instanceof AreaEffectEntity area && area.getData().name().equals(DEFLECT)) {
        out.add(area);
      }
    }
    return out;
  }
}
