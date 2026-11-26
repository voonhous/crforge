/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionRow;
import org.crforge.core.battle.action.Heal;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.replay.ReplayScenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The hits on a team's king of a team battle that reach it by a projectile: a projectile's impact
 * on its one target, a chain of projectiles' areas, and a reflected attack. A king of side 2 or 3
 * is struck as it is; the dealer moves the subtraction to the pool, the hit points of its partner's
 * king of side 0 or 1, while what else the hit does stays on the half struck. Every expected value
 * is read from the tables.
 */
class TeamKingHitsTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final List<String> DECK =
      List.of(
          "Knight",
          "Archer",
          "Giant",
          "MiniPekka",
          "Musketeer",
          "Valkyrie",
          "Barbarians",
          "Minions");

  /** The area effect whose strikes are projectiles that land on one target with a target buff. */
  private static final String LIGHTNING = "Lightning";

  /** The card whose waves are chains of projectiles sharing one id list a wave. */
  private static final String ARROWS = "Arrows";

  /** How many steps a cast of these tests is followed for: long enough for all of it to land. */
  private static final int STEPS = 80;

  /** A team battle on the team map, played as the team match, every player of one deck. */
  private static Standard1v1Battle teamMatch() {
    List<Standard1v1Battle.Towers> towers = new ArrayList<>();
    String group =
        GameData.tables()
            .table("support_cards")
            .row(ReplayScenario.TEAM_TOWER_SELECTION)
            .string(ReplayScenario.TEAM_SPAWN_GROUP);
    for (int side = 0; side < BattleWorld.TEAM_BATTLE; side++) {
      towers.add(new Standard1v1Battle.Towers(group, LEVEL, LEVEL));
    }
    Standard1v1Battle battle =
        new Standard1v1Battle(
            GameData.tables(),
            towers,
            true,
            GameData.tables().tileMap(ReplayScenario.TEAM_TILE_MAP));
    battle.startTeamMatch(
        Collections.nCopies(4, DECK),
        new int[4],
        Collections.nCopies(4, new int[DECK.size()]),
        Collections.nCopies(4, List.of()));
    battle.getBattle().step();
    return battle;
  }

  private static int lost(TowerEntity king) {
    return king.getHitPoints().getMaximum() - king.getHitPoints().getHitPoints();
  }

  @Test
  @DisplayName(
      "a projectile landing on a king of side 2 or 3 strikes that half: the pool takes its damage,"
          + " the half its target buff, and the holder none of it")
  void aProjectileStrikesTheHalfItLandsOn() {
    Standard1v1Battle battle = teamMatch();
    BattleWorld world = battle.getWorld();
    TowerEntity holder = world.kingTower(0);
    TowerEntity half = world.kingTower(2);
    BattleRecords records = GameData.records();
    ProjectileData strike = records.projectile(records.areaEffect(LIGHTNING).projectile());
    String buff = strike.targetBuff();
    assertThat(buff).isNotNull();
    assertThat(strike.applyBuffBeforeDamage()).isFalse();
    // Cast on the far side of the half, so that its circle holds the half and not the holder.
    int x = half.x() - (holder.x() - half.x()) / 2;
    assertThat(holder.x() - x)
        .isGreaterThan(records.areaEffect(LIGHTNING).radius() + holder.getData().collisionRadius());
    world.createAreaEffect(LIGHTNING, x, half.y(), 1, holder.getPackedLevel(), null, "test", null);

    boolean struck = false;
    for (int step = 0; step < STEPS && !struck; step++) {
      battle.getBattle().step();
      struck = lost(holder) > 0;
    }
    assertThat(struck).as("the strike lands").isTrue();
    assertThat(half.getHitPoints().getHitPoints()).isEqualTo(half.getHitPoints().getMaximum());
    assertThat(half.getBuffs().carries(buff)).as("the half struck carries the buff").isTrue();
    assertThat(holder.getBuffs().carries(buff)).as("the holder carries nothing").isFalse();
  }

  /** What a cast of a card of side 1 at a point takes off the given king in the steps followed. */
  private static int castLoss(Standard1v1Battle battle, TowerEntity king, int x, int y) {
    DeployCard card = GameData.records().card(ARROWS);
    battle.getWorld().castSpell(card, LEVEL - 1, 1, x, y, "test", -1, 0);
    for (int step = 0; step < STEPS; step++) {
      battle.getBattle().step();
    }
    return lost(king);
  }

  @Test
  @DisplayName(
      "a wave of Arrows over both halves of a team's king takes the pool once, as a wave takes a"
          + " king of a battle of two: the half it strikes lists its partner's king for the wave")
  void anArrowsWaveTakesThePoolOnce() {
    Standard1v1Battle oneASide = new Standard1v1Battle(GameData.tables(), LEVEL);
    TowerEntity king = oneASide.getWorld().kingTower(0);
    int expected = castLoss(oneASide, king, king.x(), king.y());
    assertThat(expected).isPositive();

    Standard1v1Battle battle = teamMatch();
    TowerEntity holder = battle.getWorld().kingTower(0);
    TowerEntity half = battle.getWorld().kingTower(2);
    int middle = (holder.x() + half.x()) / 2;
    assertThat(castLoss(battle, holder, middle, holder.y())).isEqualTo(expected);
    assertThat(half.getHitPoints().getHitPoints()).isEqualTo(half.getHitPoints().getMaximum());
  }

  @Test
  @DisplayName(
      "a reflected attack on a king of side 2 or 3 lowers the pool, the half's own hit points kept")
  void aReflectedAttackLowersThePool() {
    Standard1v1Battle battle = teamMatch();
    BattleWorld world = battle.getWorld();
    TowerEntity holder = world.kingTower(1);
    TowerEntity half = world.kingTower(3);
    int pool = holder.getHitPoints().getHitPoints();

    half.takeReflectedDamage(world.kingTower(0), 100, 0, 1);

    assertThat(holder.getHitPoints().getHitPoints()).isEqualTo(pool - 100);
    assertThat(holder.getTargetView().getHitPoints()).isEqualTo(pool - 100);
    assertThat(half.getHitPoints().getHitPoints()).isEqualTo(half.getHitPoints().getMaximum());
  }

  @Test
  @DisplayName(
      "the on-hit action of a projectile on a king of a team battle stays refused, as does the"
          + " travelling hit on a king of side 2 or 3 of a flying body with a target buff")
  void theUnheldPathsAreRefused() {
    Standard1v1Battle battle = teamMatch();
    BattleWorld world = battle.getWorld();
    BattleRecords records = GameData.records();
    ProjectileData withAction = null;
    for (GameRow row : GameData.tables().table("projectiles").rows()) {
      ProjectileData data = records.projectile(row.name());
      if (data.onHitTargetAction() != null && data.radius() < 1) {
        withAction = data;
        break;
      }
    }
    assertThat(withAction).isNotNull();
    ProjectileEntity action = new ProjectileEntity(world, withAction, 1);
    assertThatThrownBy(() -> world.refuseTeamKingImpact(action, world.kingTower(2)))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("on-hit action");

    // A flying body whose row sets a target buff is refused on a half king: no recorded team
    // battle shows where the buff of a swapped hit lands.
    ProjectileData buffed = null;
    for (GameRow row : GameData.tables().table("projectiles").rows()) {
      ProjectileData data = records.projectile(row.name());
      if (data.targetBuff() != null && data.onHitTargetAction() == null) {
        buffed = data;
        break;
      }
    }
    assertThat(buffed).isNotNull();
    TowerEntity half = world.kingTower(2);
    ProjectileEntity body = new ProjectileEntity(world, buffed, 1);
    assertThatThrownBy(
            () -> world.getHits().travellingHit(body, half, half.x(), half.y(), world.nextHitId()))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("travelling hit")
        .hasMessageContaining("target buff");
  }

  @Test
  @DisplayName(
      "a flying body's travelling hit on a king of side 2 or 3 strikes the pool's holder in its"
          + " place: the holder is listed and takes the hit, the half's own hit points kept")
  void aTravellingHitStrikesTheHolder() {
    Standard1v1Battle battle = teamMatch();
    BattleWorld world = battle.getWorld();
    TowerEntity holder = world.kingTower(0);
    TowerEntity half = world.kingTower(2);
    ProjectileEntity body = plainBody(world);
    int pool = holder.getHitPoints().getHitPoints();

    world.getHits().travellingHit(body, half, half.x(), half.y(), world.nextHitId());
    assertThat(body.getHitIds()).containsExactly(holder.getId());
    assertThat(world.getHits().travellingHit(body, half, half.x(), half.y(), world.nextHitId()))
        .as("the same half again is passed over, as by either rule")
        .isFalse();
    world.getHits().drainTypedHits();

    assertThat(pool - holder.getHitPoints().getHitPoints()).as("one hit on the pool").isPositive();
    assertThat(half.getHitPoints().getHitPoints()).isEqualTo(half.getHitPoints().getMaximum());
    assertThat(half.getHitPoints().dedupeIds()).isEmpty();
  }

  @Test
  @DisplayName(
      "a travelling hit the swap alone passes over is refused: the holder after its half, the half"
          + " after its holder, and a swapped hit of a projectile of a group")
  void aSwapThatDecidesIsRefused() throws ReflectiveOperationException {
    Standard1v1Battle battle = teamMatch();
    BattleWorld world = battle.getWorld();
    TowerEntity holder = world.kingTower(0);
    TowerEntity half = world.kingTower(2);

    ProjectileEntity halfFirst = plainBody(world);
    world.getHits().travellingHit(halfFirst, half, half.x(), half.y(), world.nextHitId());
    assertThatThrownBy(
            () ->
                world
                    .getHits()
                    .travellingHit(halfFirst, holder, holder.x(), holder.y(), world.nextHitId()))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("found listed through its partner's king");

    ProjectileEntity holderFirst = plainBody(world);
    world.getHits().travellingHit(holderFirst, holder, holder.x(), holder.y(), world.nextHitId());
    assertThatThrownBy(
            () ->
                world
                    .getHits()
                    .travellingHit(holderFirst, half, half.x(), half.y(), world.nextHitId()))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("found listed through its partner's king");

    ProjectileEntity grouped = plainBody(world);
    Field group = ProjectileEntity.class.getDeclaredField("groupId");
    group.setAccessible(true);
    group.setInt(grouped, world.nextProjectileGroupId());
    assertThatThrownBy(
            () ->
                world.getHits().travellingHit(grouped, half, half.x(), half.y(), world.nextHitId()))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("of a group of projectiles");
  }

  /** A flying body of side 1 whose row has no target buff and no on-hit action. */
  private static ProjectileEntity plainBody(BattleWorld world) {
    BattleRecords records = GameData.records();
    ProjectileData row = records.projectile(records.card(ARROWS).projectile());
    assertThat(row.targetBuff()).isNull();
    assertThat(row.onHitTargetAction()).isNull();
    return new ProjectileEntity(world, row, 1);
  }

  @Test
  @DisplayName(
      "a heal that reaches a king of a team battle, either half, is refused, a buff's heal and a"
          + " heal action alike; a king of a battle of two still takes it")
  void aHealOnATeamKingIsRefused() {
    Standard1v1Battle battle = teamMatch();
    BattleWorld world = battle.getWorld();
    for (int side = 0; side < BattleWorld.TEAM_BATTLE; side++) {
      TowerEntity king = world.kingTower(side);
      assertThatThrownBy(() -> king.takeHeal(100, 0))
          .as("buff heal, side " + side)
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessageContaining("a heal on " + king.name());
      Heal heal = new Heal(ActionRow.named("heal"), () -> 100, 0);
      assertThatThrownBy(() -> new ActionHolder(king).start(heal))
          .as("heal action, side " + side)
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessageContaining("a heal on " + king.name());
    }

    Standard1v1Battle oneASide = new Standard1v1Battle(GameData.tables(), LEVEL);
    TowerEntity king = oneASide.getWorld().kingTower(0);
    king.takeDamage(500, 0, 0, 1);
    int before = king.getHitPoints().getHitPoints();
    king.takeHeal(100, 0);
    new ActionHolder(king).start(new Heal(ActionRow.named("heal"), () -> 100, 0));
    assertThat(king.getHitPoints().getHitPoints()).isEqualTo(before + 200);
  }
}
