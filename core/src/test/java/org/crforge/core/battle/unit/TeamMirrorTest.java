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
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.action.ActionRow;
import org.crforge.core.battle.action.BarbBarrelHeroReRoll;
import org.crforge.core.battle.action.GoblinDrillEvoRelocate;
import org.crforge.core.battle.action.WarpCharacter;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.replay.ReplayScenario;
import org.crforge.core.pathfinding.grid.TileMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules a team battle mirrors by team where a battle of two mirrors by side: sides 0 and 2 are
 * team 0, sides 1 and 3 team 1, so side 2 plays as side 0 and side 3 as side 1. Each test runs the
 * rule for all four sides on synthetic rows.
 */
class TeamMirrorTest {

  /** The level every tower and unit of these battles stands at. */
  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** A team battle on its recorded map, its four players' towers standing. */
  private static Standard1v1Battle teamBattle() {
    String group =
        Shipped.text(
            Shipped.row("support_cards", ReplayScenario.TEAM_TOWER_SELECTION),
            ReplayScenario.TEAM_SPAWN_GROUP);
    List<Standard1v1Battle.Towers> towers = new ArrayList<>();
    for (int side = 0; side < BattleWorld.TEAM_BATTLE; side++) {
      towers.add(new Standard1v1Battle.Towers(group, LEVEL, LEVEL));
    }
    TileMap map = GameData.tables().tileMap(ReplayScenario.TEAM_TILE_MAP);
    return new Standard1v1Battle(GameData.tables(), towers, true, map);
  }

  /** A Knight of the side standing at the point in a team battle, its movement off. */
  private static CharacterEntity standing(Standard1v1Battle battle, int side, int x, int y) {
    CharacterEntity knight =
        battle.deploy(0, GameData.unit("Knight"), LEVEL, side, x, y, "Knight" + side);
    knight.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    battle.getBattle().step();
    knight.getView().setX(x);
    knight.getView().setY(y);
    return knight;
  }

  /** Where an instant warp by the offsets puts a unit of the side standing at the point. */
  private static int[] instantWarp(int side, int x, int y, int warpX, int warpY) {
    Standard1v1Battle battle = teamBattle();
    CharacterEntity unit = standing(battle, side, x, y);
    WarpCharacter warp =
        new WarpCharacter(
            ActionRow.named("warp"),
            WarpCharacter.Columns.builder().warpX(warpX).warpY(warpY).build());
    unit.warp(warp, 0);
    return new int[] {unit.getView().getX(), unit.getView().getY()};
  }

  @Test
  @DisplayName(
      "an instant warp's offsets are negated by team in a team battle: side 2 warps as side 0,"
          + " side 3 as side 1")
  void anInstantWarpMirrorsByTeam() {
    int x = 9000;
    int y = 16000;
    int warpX = 1500;
    int warpY = -3000;
    int[] forward = {x + warpX, y + warpY};
    int[] back = {x - warpX, y - warpY};

    assertThat(instantWarp(0, x, y, warpX, warpY)).containsExactly(forward);
    assertThat(instantWarp(2, x, y, warpX, warpY)).containsExactly(forward);
    assertThat(instantWarp(1, x, y, warpX, warpY)).containsExactly(back);
    assertThat(instantWarp(3, x, y, warpX, warpY)).containsExactly(back);
  }

  /**
   * Where a flying warp to a recorded point, fast enough to arrive on its first step, puts a unit
   * of the side.
   */
  private static int[] flyingWarp(int side, int lastX, int lastY, int offsetX, int offsetY) {
    Standard1v1Battle battle = teamBattle();
    CharacterEntity unit = standing(battle, side, 9000, 16000);
    WarpCharacter warp =
        new WarpCharacter(
            ActionRow.named("warp"),
            WarpCharacter.Columns.builder().build(),
            WarpCharacter.Flight.builder()
                .speedPerStep(100000)
                .acceleration(100000)
                .offsetX(offsetX)
                .offsetY(offsetY)
                .build());
    WarpRun run = new WarpRun(warp, unit, null, lastX, lastY);
    run.update(unit.actionHolder());
    return new int[] {unit.getView().getX(), unit.getView().getY()};
  }

  @Test
  @DisplayName(
      "a flying warp's offset along the length is negated for side 1 and side 3 in a team battle,"
          + " added for side 0, and refused for side 2, whose team adds it and whose side takes it"
          + " off; no recording holds the sign")
  void aFlyingWarpsOffsetIsRefusedWhereTheReadingsDiffer() {
    int lastX = 9000;
    int lastY = 14000;
    int offsetX = 400;
    int offsetY = 2000;

    assertThat(flyingWarp(0, lastX, lastY, offsetX, offsetY))
        .containsExactly(lastX + offsetX, lastY + offsetY);
    assertThat(flyingWarp(1, lastX, lastY, offsetX, offsetY))
        .containsExactly(lastX + offsetX, lastY - offsetY);
    assertThat(flyingWarp(3, lastX, lastY, offsetX, offsetY))
        .containsExactly(lastX + offsetX, lastY - offsetY);
    assertThatThrownBy(() -> flyingWarp(2, lastX, lastY, offsetX, offsetY))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("offset along the length");
  }

  @Test
  @DisplayName(
      "a side 2 flying warp with no offset along the length plays in a team battle, the offset"
          + " across the width added unmirrored")
  void aSideTwoFlyingWarpWithoutALengthOffsetPlays() {
    assertThat(flyingWarp(2, 9000, 14000, 400, 0)).containsExactly(9400, 14000);
    assertThat(flyingWarp(2, 9000, 14000, 0, 0)).containsExactly(9000, 14000);
  }

  /** The side a unit of the side is turned over to as its reroll's roll is deflected. */
  private static int turnedOver(int side) {
    Standard1v1Battle battle = teamBattle();
    CharacterEntity unit = standing(battle, side, 9000, 16000);
    BarbBarrelHeroReRoll reroll =
        new BarbBarrelHeroReRoll(
            ActionRow.named("reroll"), BarbBarrelHeroReRoll.Columns.builder().build());
    BarbReRollRun run = new BarbReRollRun(reroll, unit);
    run.turnOver();
    assertThat(unit.castId())
        .as("the facing king's cast")
        .isEqualTo(battle.getWorld().kingTower(unit.side()).castId());
    return unit.side();
  }

  @Test
  @DisplayName(
      "a deflected reroll turns its unit over to the other team's king of side 0 or 1: side 0's"
          + " and side 2's to side 1, side 1's and side 3's to side 0")
  void aTurnedRerollGoesOverToTheOtherTeamsMainKing() {
    assertThat(turnedOver(0)).isEqualTo(1);
    assertThat(turnedOver(2)).isEqualTo(1);
    assertThat(turnedOver(1)).isZero();
    assertThat(turnedOver(3)).isZero();
  }

  /**
   * The push point and vector of an evolved Mega Knight's uppercut in a team battle: the Mega
   * Knight of the side at (3500, 18000), a Knight of the given side walking at it from (3500,
   * 20500), on the left lane, where team 1's nearest princess tower is side 3's.
   */
  private static int[] uppercutPush(int side, int knightSide) {
    return uppercutPush(side, 3500, 18000, knightSide, 3500, 20500);
  }

  /** The push point and vector of the first uppercut of a Mega Knight on a Knight. */
  private static int[] uppercutPush(int side, int x, int y, int knightSide, int kx, int ky) {
    Standard1v1Battle battle = teamBattle();
    List<int[]> pushes = new ArrayList<>();
    battle
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void uppercutStepped(
                  int tick,
                  CharacterEntity unit,
                  int delay,
                  boolean finished,
                  String outcome,
                  int[] pushPoint) {
                if (pushPoint != null) {
                  pushes.add(pushPoint.clone());
                }
              }
            });
    battle.deploy(0, GameData.unit("Knight"), LEVEL, knightSide, kx, ky, "knight");
    battle.deploy(0, GameData.unit("MegaKnight_EV1"), LEVEL, side, x, y, "mk");
    for (int tick = 0; tick < 300 && pushes.isEmpty(); tick++) {
      battle.getBattle().step();
    }
    assertThat(pushes).as("an uppercut's push").isNotEmpty();
    return pushes.get(0);
  }

  @Test
  @DisplayName(
      "an uppercut pushes away from the nearest of the pushed unit's team's princess towers and"
          + " the king facing the Mega Knight's team: a side 2 Mega Knight pushes as side 0's, a"
          + " side 3 one as side 1's")
  void anUppercutPushesByTeam() {
    int[] sideZero = uppercutPush(0, 1);

    assertThat(uppercutPush(2, 1)).containsExactly(sideZero);
    assertThat(uppercutPush(3, 0)).containsExactly(uppercutPush(1, 0));
    // The vector from the tower: from side 3's princess tower straight down the left lane, not
    // from side 1's across the arena, which is not on its team's list by side.
    assertThat(Math.abs(sideZero[2])).as("along the width").isLessThan(500);
    assertThat(sideZero[3]).as("along the length").isLessThan(-4000);
  }

  @Test
  @DisplayName(
      "a side 2 Mega Knight's uppercut of a unit nearer to a king than to its team's princess"
          + " towers is refused: its team faces side 1's king, its side side 0's")
  void aSideTwoUppercutAKingWouldDecideIsRefused() {
    int kingX = teamBattle().getWorld().kingTower(1).getView().getX();
    int kingY = teamBattle().getWorld().kingTower(1).getView().getY();

    assertThatThrownBy(() -> uppercutPush(2, kingX, kingY - 3000, 1, kingX, kingY - 1800))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("nearer to the king of side 1");
  }

  /**
   * The projectile a mirrored extra spell throws in a team battle for a projectile a unit of the
   * side shot at the point, then the shot itself.
   */
  private static List<ProjectileEntity> mirroredExtraSpell(int side, int aimX, int aimY) {
    Standard1v1Battle battle = teamBattle();
    BattleWorld world = battle.getWorld();
    CharacterEntity unit = standing(battle, side, 9000, 16000);
    ProjectileEntity shot =
        world.launchFromUnit(
            unit, world.getRecords().projectile("GoblinBarrelSpell"), 9000, 16000, 0, aimX, aimY);
    world.castMirroredExtraSpell(shot, "GoblinBarrelSpell_EV1_Decoy", "decoy");
    // The holder admits both at the step's closing cleanup.
    battle.getBattle().step();
    List<ProjectileEntity> out = new ArrayList<>();
    for (BattleEntity entity : world.getHolder().entities()) {
      if (entity instanceof ProjectileEntity projectile && projectile != shot) {
        out.add(projectile);
      }
    }
    assertThat(out).as("the extra spell's projectile").hasSize(1);
    out.add(shot);
    return out;
  }

  @Test
  @DisplayName(
      "a mirrored extra spell in a team battle throws its projectile from where the shot stands"
          + " to the shot's aim turned over across the width, for its side, whatever the side")
  void aMirroredExtraSpellIsThrownAcrossTheWidthForAnySide() {
    int width = teamBattle().getWorld().getTileMap().widthUnits();
    for (int side = 0; side < BattleWorld.TEAM_BATTLE; side++) {
      List<ProjectileEntity> thrown = mirroredExtraSpell(side, 14500, 23000);
      ProjectileEntity extra = thrown.get(0);
      ProjectileEntity shot = thrown.get(1);
      assertThat(extra.side()).as("side " + side).isEqualTo(side);
      assertThat(List.of(extra.getAimX(), extra.getAimY()))
          .as("side " + side)
          .containsExactly(width - 14500, 23000);
      assertThat(List.of(extra.getStartX(), extra.getStartY()))
          .as("side " + side)
          .containsExactly(shot.getStartX(), shot.getStartY());
    }
  }

  /** Starts an evolved Goblin Drill's relocation on a unit of the side standing at the point. */
  private static void relocate(int side, int x, int y) {
    Standard1v1Battle battle = teamBattle();
    CharacterEntity unit = standing(battle, side, x, y);
    GoblinDrillEvoRelocate row =
        new GoblinDrillEvoRelocate(
            ActionRow.named("relocate"),
            new GoblinDrillEvoRelocate.Columns(
                true, 2, 1000, List.of(), null, List.of(), List.of()));
    new GoblinDrillRelocateRun(row, unit, unit.actionHolder());
  }

  /** A princess tower of the side in a team battle, its half tile times 500 as x and y. */
  private static int[] princessTower(int side) {
    TowerEntity tower = teamBattle().getWorld().princessTowers(side).get(0);
    return new int[] {tower.getView().getX() / 500 * 500, tower.getView().getY() / 500 * 500};
  }

  @Test
  @DisplayName(
      "a relocation standing on the ring of the enemy partner's princess tower is refused in a"
          + " team battle, the enemy side's list leaving it out and the team's taking it in; one"
          + " on the enemy side's own tower's ring plays")
  void aRelocationOnTheEnemyPartnersTowerIsRefused() {
    // The ring's first point: five half tiles left of the tower and five up.
    int[] partner = princessTower(3);
    int[] own = princessTower(1);

    assertThatThrownBy(() -> relocate(0, partner[0] - 2500, partner[1] + 2500))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("princess tower of side 3");
    relocate(0, own[0] - 2500, own[1] + 2500);
  }
}
