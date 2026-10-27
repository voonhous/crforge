/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.spawn.SpawnHost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A buff whose row sets CastIdSpecificBuff: an application refreshes only the instance listed for
 * the same cast - the king's count of card plays its play carries, which everything the play makes
 * carries on - and lists an instance of its own for any other cast. A hand-written Poison row is
 * cast twice onto the enemy's left princess tower by the same side.
 */
class BattleCastIdSpecificBuffTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The buff and area effect the hand-written rows alter. */
  private static final String POISON = "Poison";

  @TempDir Path folder;

  /**
   * The configured tables with the Poison buff refreshed by its own rules alone: no stacking, the
   * two specific columns as given, and the Poison card made to cost one elixir so two casts follow
   * each other at once.
   */
  private GameTables poisonTables(boolean castIdSpecific, boolean playerSpecific)
      throws IOException {
    GameTables tables =
        GameData.altered(
            folder,
            "character_buffs",
            rows -> {
              ObjectNode columns = GameData.columns(rows, POISON);
              columns.remove("EnableStacking");
              columns.remove("PlayerSpecificBuff");
              columns.remove("CastIdSpecificBuff");
              if (castIdSpecific) {
                columns.put("CastIdSpecificBuff", true);
              }
              if (playerSpecific) {
                columns.put("PlayerSpecificBuff", true);
              }
            });
    GameData.alterLoaded(
        folder, "spells_other", rows -> GameData.columns(rows, POISON).put("ManaCost", 1));
    return GameTables.load(folder);
  }

  /** The enemy's princess tower nearest the arena's left edge. */
  private static WorldEntity leftEnemyPrincess(Standard1v1Battle battle) {
    WorldEntity best = null;
    for (BattleEntity entity : battle.getWorld().getHolder().entities()) {
      if (entity instanceof TowerEntity tower
          && !tower.getData().king()
          && tower.side() == 1
          && (best == null || tower.getView().getX() < best.getView().getX())) {
        best = tower;
      }
    }
    return best;
  }

  /** The instances of the Poison row the entity lists, in listing order. */
  private static List<BuffInstance> poisons(WorldEntity entity) {
    return entity.getBuffs().items().stream()
        .filter(instance -> instance.getBuff().name().equals(POISON))
        .toList();
  }

  private static void run(Standard1v1Battle battle, int lastTick) {
    while (battle.getBattle().getTick() <= lastTick) {
      battle.getBattle().step();
    }
  }

  /**
   * Side 0 casts the altered Poison onto the enemy's left princess tower twice, the second while
   * the first still hits: the instances the tower lists after the first cast's hits, and after both
   * casts' hits.
   */
  private record TwoCasts(List<BuffInstance> afterFirst, List<BuffInstance> afterBoth) {}

  private static TwoCasts castTwiceInAMatch(GameTables tables) {
    Standard1v1Battle battle = new Standard1v1Battle(tables);
    battle.startLadderMatch(Collections.nCopies(8, POISON), Collections.nCopies(8, "Knight"), 0, 0);
    WorldEntity tower = leftEnemyPrincess(battle);
    int x = tower.getView().getX();
    int y = tower.getView().getY();
    int first = Shipped.startCooldownTicks() + 20;
    // The first cast hits several times before the second is played.
    int second = first + 40;
    DeployCard card = battle.getWorld().getRecords().card(POISON);
    battle.play(first, card, LEVEL, 0, x, y, "first");
    run(battle, second - 1);
    List<BuffInstance> afterFirst = List.copyOf(poisons(tower));
    battle.play(second, card, LEVEL, 0, x, y, "second");
    run(battle, second + 30);
    assertThat(battle.getPlays()).hasSize(2);
    assertThat(battle.getPlays()).allSatisfy(play -> assertThat(play.matchCode()).isZero());
    return new TwoCasts(afterFirst, List.copyOf(poisons(tower)));
  }

  @Test
  @DisplayName(
      "a cast-specific buff is refreshed by the hits of the cast that listed it and listed again"
          + " for another cast of the same side: each instance keeps its play's count of card plays")
  void anotherCastListsItsOwnInstance() throws IOException {
    TwoCasts casts = castTwiceInAMatch(poisonTables(true, false));

    assertThat(casts.afterFirst())
        .as("the first cast's repeated hits refresh its one instance")
        .hasSize(1);
    assertThat(casts.afterBoth()).hasSize(2);
    assertThat(casts.afterBoth())
        .extracting(BuffInstance::getCastId)
        .as("the king's count of card plays before each play")
        .containsExactly(0, 1);
    assertThat(casts.afterBoth().get(0)).isSameAs(casts.afterFirst().get(0));
  }

  @Test
  @DisplayName("without the column, the second cast refreshes the first cast's instance")
  void aPlainRowIsRefreshedByAnyCast() throws IOException {
    TwoCasts casts = castTwiceInAMatch(poisonTables(false, false));

    assertThat(casts.afterFirst()).hasSize(1);
    assertThat(casts.afterBoth()).containsExactlyElementsOf(casts.afterFirst());
  }

  @Test
  @DisplayName(
      "a row that also sets PlayerSpecificBuff is matched by side first: the same side's second"
          + " cast refreshes the first's instance")
  void thePlayerRuleComesFirst() throws IOException {
    TwoCasts casts = castTwiceInAMatch(poisonTables(true, true));

    assertThat(casts.afterFirst()).hasSize(1);
    assertThat(casts.afterBoth()).containsExactlyElementsOf(casts.afterFirst());
  }

  @Test
  @DisplayName(
      "outside a match, which counts no card plays, each play is a cast of its own: the second"
          + " cast lists its own instance")
  void outsideAMatchEachPlayIsItsOwnCast() throws IOException {
    GameTables tables = poisonTables(true, false);
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, false);
    WorldEntity tower = leftEnemyPrincess(battle);
    int x = tower.getView().getX();
    int y = tower.getView().getY();
    DeployCard card = battle.getWorld().getRecords().card(POISON);
    battle.play(10, card, LEVEL, 0, x, y, "first");
    run(battle, 40);
    assertThat(poisons(tower)).hasSize(1);
    battle.play(50, card, LEVEL, 0, x, y, "second");
    run(battle, 80);

    List<BuffInstance> both = poisons(tower);
    assertThat(both).hasSize(2);
    assertThat(both.get(0).getCastId()).isNotEqualTo(both.get(1).getCastId());
  }

  /**
   * Steps a battle until a predicate holds, at most the given number of steps, collecting the cast
   * id of every area effect and projectile of side 0 in the battle after each step.
   */
  private static List<Integer> sideZeroCasts(
      Standard1v1Battle battle, BooleanSupplier done, int most) {
    List<Integer> casts = new ArrayList<>();
    for (int step = 0; step < most && !done.getAsBoolean(); step++) {
      battle.getBattle().step();
      for (BattleEntity entity : battle.getWorld().getHolder().entities()) {
        if (entity instanceof AreaEffectEntity area && area.side() == 0) {
          casts.add(area.castId());
        } else if (entity instanceof ProjectileEntity projectile && projectile.side() == 0) {
          casts.add(projectile.castId());
        }
      }
    }
    return casts;
  }

  @Test
  @DisplayName(
      "the evolved Cannon's bomb area effects, the bombs they drop and the area effects the bombs'"
          + " actions spawn all carry the Cannon's cast")
  void theBarrageCarriesTheCannonsCast() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity cannon =
        battle.deploy(0, GameData.unit("Cannon_EV1"), LEVEL, 0, 8500, 10500, "C");
    // The bombs are made, dropped and landed well within three seconds.
    List<Integer> casts = sideZeroCasts(battle, () -> false, 60);

    assertThat(cannon.castId()).isNotEqualTo(SpawnHost.UNMODELLED_CAST_ID);
    assertThat(casts).isNotEmpty().containsOnly(cannon.castId());
  }

  @Test
  @DisplayName(
      "the evolved Firecracker's shot, its shrapnel and their fireworks all carry the"
          + " Firecracker's cast, so the fireworks of one shot keep one cast-specific instance on the"
          + " unit they reach")
  void theFireworksCarryTheFirecrackersCast() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity firecracker =
        battle.deploy(0, GameData.unit("Firecracker_EV1"), LEVEL, 0, 6500, 14000, "F");
    CharacterEntity target = battle.deploy(0, GameData.unit("Barbarian"), LEVEL, 1, 1500, 14000);
    target.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    target.setActive(CharacterEntity.TARGETING_SLOT, false);
    // The buff of the area effect the Firecracker's shot makes as it lands.
    String buff =
        GameData.records()
            .areaEffect(firecracker.getData().projectile().spawnAreaEffectObject())
            .buff();
    assertThat(GameData.records().buff(buff).castIdSpecificBuff())
        .as("the shipped row sets the column")
        .isTrue();
    List<Integer> casts =
        sideZeroCasts(
            battle,
            () -> target.getBuffs().items().stream().anyMatch(i -> i.getBuff().name().equals(buff)),
            400);
    // The shot's own fireworks and its shrapnel's go on hitting the unit for a second.
    casts.addAll(sideZeroCasts(battle, () -> false, 20));

    assertThat(casts).isNotEmpty().containsOnly(firecracker.castId());
    assertThat(target.getBuffs().items())
        .filteredOn(i -> i.getBuff().name().equals(buff))
        .extracting(BuffInstance::getCastId)
        .containsExactly(firecracker.castId());
  }
}
