/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.GiantBufferBuff;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.expression.ExpressionCompiler;
import org.crforge.core.battle.expression.ExpressionEvaluator;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Three Musketeers' attack choice: the filter each runs as an attack starts and at every hit
 * picks the entry the next hit reads, the bayonet for a ground target inside the filter's range of
 * both radii and a shot otherwise; the bayonet's action deals a typed hit on the target in the
 * drain of the hit's own tick, at the musketeer's level and with no crown-tower share. What the
 * references do not reach is held here: the buff on damage after a bayonet, target_is_ground with
 * the targeting off and on a target whose layer a tag forces, and the refusals around an entry's
 * action and a forced target that is not a character.
 */
class BattleThreeMusketeersTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String MUSKETEER = "ThreeMusketeer_Rework_Character_1";

  private static final int X = 3500;

  private static final int Y = 11000;

  /** One musketeer standing still before a target that never walks either. */
  private static final class Scene {
    final Standard1v1Battle match;
    final List<String> typed = new ArrayList<>();
    final List<String> buffs = new ArrayList<>();
    final List<String> launches = new ArrayList<>();
    final List<String> ownLaunches = new ArrayList<>();
    final CharacterEntity musketeer;
    int tick;

    Scene(GameTables tables) {
      match = new Standard1v1Battle(tables, LEVEL, false);
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void typedHitDealt(
                    int t,
                    WorldEntity source,
                    WorldEntity target,
                    int amount,
                    int damageId,
                    DamageResult result) {
                  typed.add(t + " " + source.name() + " " + target.name() + " " + amount);
                }

                @Override
                public void buffApplied(int t, WorldEntity target, BuffInstance buff) {
                  buffs.add(t + " " + target.name() + " " + buff.getBuff().name());
                }

                @Override
                public void projectileLaunched(int t, ProjectileEntity projectile) {
                  launches.add(t + " " + projectile.getData().name());
                  if (projectile.side() == 0) {
                    ownLaunches.add(t + " " + projectile.getData().name());
                  }
                }
              });
      musketeer = still(0, MUSKETEER, X, Y, "M");
    }

    CharacterEntity still(int side, String row, int x, int y, String name) {
      CharacterEntity unit =
          match.deploy(0, match.getWorld().getRecords().unit(row), LEVEL, side, x, y, name);
      unit.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      return unit;
    }

    void step(int ticks) {
      for (int i = 0; i < ticks; i++) {
        match.getBattle().step();
        tick++;
      }
    }
  }

  @Test
  @DisplayName(
      "a Knight inside the bayonet's reach takes typed hits of the bayonet's damage and no shot; a"
          + " Minion as close takes shots")
  void groundTakesTheBayonetAirTheShot() {
    Scene ground = new Scene(GameData.tables());
    ground.still(1, "Knight", X, Y + 2000, "K");
    ground.step(80);
    assertThat(ground.launches).isEmpty();
    // The bayonet's BaseDamage at the musketeer's level and rarity.
    int bayonet =
        Shipped.scaled(
            Shipped.fields("ThreeMusketeer_Rework_Bayonet_Attack_Deal_Damage")
                .path("Damage")
                .path("BaseDamage")
                .asInt(),
            Shipped.unitRow(MUSKETEER),
            LEVEL);
    assertThat(ground.typed).isNotEmpty().allMatch(hit -> hit.endsWith(" M K " + bayonet));

    Scene air = new Scene(GameData.tables());
    air.still(1, "Minion", X, Y + 1500, "A");
    air.step(80);
    assertThat(air.typed).isEmpty();
    assertThat(air.launches).isNotEmpty();
  }

  @Test
  @DisplayName(
      "a bayonet whose row sets a buff on damage is refused as its damage-taking hit lands: the"
          + " buff would follow the hit to the damage drain")
  void theBuffOnDamageOfABayonetIsRefused(@TempDir Path folder) throws IOException {
    GameTables freezing =
        GameData.altered(
            folder,
            "characters",
            rows -> {
              GameData.columns(rows, MUSKETEER).put("BuffOnDamage", "ZapFreeze");
              GameData.columns(rows, MUSKETEER).put("BuffOnDamageTime", 500);
            });
    Scene scene = new Scene(freezing);
    scene.still(1, "Knight", X, Y + 2000, "K");
    assertThatThrownBy(
            () -> {
              while (scene.tick < 80) {
                scene.step(1);
              }
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("deals a damage-taking action's hit with a BuffOnDamage");
  }

  @Test
  @DisplayName(
      "target_is_ground answers 0 while the targeting is off, and reads a character target's force"
          + " tags before its row: FORCE_IS_GROUND ground, FORCE_IS_AIR not, both its live"
          + " height 0")
  void targetIsGround() {
    Scene scene = new Scene(GameData.tables());
    CharacterEntity knight = scene.still(1, "Knight", X, Y + 2000, "K");
    scene.step(30);
    assertThat(scene.musketeer.getTargeting().getReference()).isSameAs(knight.getTargetView());
    BattleWorld world = scene.match.getWorld();
    BattleExpressionEnvironment environment =
        new BattleExpressionEnvironment(scene.musketeer, world);
    assertThat(evaluate("target_is_ground", environment)).isEqualTo(1);

    scene.musketeer.setActive(CharacterEntity.TARGETING_SLOT, false);
    assertThat(evaluate("target_is_ground", environment)).as("targeting off").isZero();
    scene.musketeer.setActive(CharacterEntity.TARGETING_SLOT, true);

    GridEntity view = knight.getView();
    long untagged = view.getFlags();
    view.setFlags(untagged | world.forceIsAir());
    assertThat(evaluate("target_is_ground", environment)).as("FORCE_IS_AIR").isZero();
    view.setFlags(untagged | world.forceIsGround());
    assertThat(evaluate("target_is_ground", environment)).as("FORCE_IS_GROUND").isEqualTo(1);
    view.setFlags(untagged | world.forceIsAir() | world.forceIsGround());
    assertThat(evaluate("target_is_ground", environment)).as("both at height 0").isEqualTo(1);
    view.setHeightOffset(view.getHeightOffset() + 1);
    assertThat(evaluate("target_is_ground", environment)).as("both above height 0").isZero();
  }

  @Test
  @DisplayName(
      "a Minion an air-to-ground hold keeps under FORCE_IS_GROUND takes the bayonet inside its"
          + " reach; a Knight a knock keeps under FORCE_IS_AIR takes shots")
  void theForceTagsChooseTheAttack() {
    Scene air = new Scene(GameData.tables());
    CharacterEntity minion = air.still(1, "Minion", X, Y + 1500, "A");
    for (int i = 0; i < 80; i++) {
      minion.raiseForceIsGround();
      air.step(1);
    }
    assertThat(air.typed).isNotEmpty().allMatch(hit -> hit.contains(" M A "));
    assertThat(air.ownLaunches).as("the musketeer's shots").isEmpty();

    Scene ground = new Scene(GameData.tables());
    CharacterEntity knight = ground.still(1, "Knight", X, Y + 2000, "K");
    BattleWorld world = ground.match.getWorld();
    for (int i = 0; i < 80; i++) {
      knight.raiseWatched(world.forceIsAir());
      ground.step(1);
    }
    assertThat(ground.typed).isEmpty();
    assertThat(ground.ownLaunches).as("the musketeer's shots").isNotEmpty();
  }

  @Test
  @DisplayName(
      "target_is_ground on a target that is not a character and carries a force tag is refused")
  void aForcedTowerIsRefused() {
    Scene scene = new Scene(GameData.tables());
    TowerEntity tower = scene.match.getWorld().princessTowers(1).get(0);
    CharacterEntity musketeer =
        scene.still(0, MUSKETEER, tower.getView().getX(), tower.getView().getY() - 4000, "N");
    int ticks = 0;
    while (musketeer.getTargeting().getReference() != tower.getTargetView() && ticks++ < 100) {
      scene.step(1);
    }
    assertThat(musketeer.getTargeting().getReference()).isSameAs(tower.getTargetView());
    BattleWorld world = scene.match.getWorld();
    BattleExpressionEnvironment environment = new BattleExpressionEnvironment(musketeer, world);
    assertThat(evaluate("target_is_ground", environment)).isEqualTo(1);
    tower.getView().setFlags(tower.getView().getFlags() | world.forceIsAir());
    assertThatThrownBy(() -> evaluate("target_is_ground", environment))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("which is not a character and carries FORCE_IS_AIR");
  }

  @Test
  @DisplayName(
      "an entry's action in a sequence of one on a multi-target attacker, or beside a projectile,"
          + " is refused as the unit is made")
  void unheldEntryActionsAreRefused(@TempDir Path folder) throws IOException {
    // The Electro Wizard, which hits two targets, given a sequence of one entry with an action.
    GameTables single =
        GameData.altered(
            Files.createDirectories(folder.resolve("single")),
            "characters",
            rows ->
                GameData.columns(rows, "ElectroWizard")
                    .putArray("AttackSequenceList")
                    .addObject()
                    .put("DoAttackAction", "ThreeMusketeer_Rework_Bayonet_Attack"));
    Standard1v1Battle match = new Standard1v1Battle(single);
    assertThatThrownBy(
            () ->
                new CharacterEntity(
                    match.getWorld(),
                    match.getWorld().getRecords().unit("ElectroWizard"),
                    "E",
                    0,
                    X,
                    Y,
                    LEVEL))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("in a sequence of one on a multi-target attacker");

    GameTables both =
        GameData.altered(
            Files.createDirectories(folder.resolve("both")),
            "characters",
            rows ->
                ((ObjectNode) GameData.columns(rows, MUSKETEER).get("AttackSequenceList").get(1))
                    .put("Projectile", "ThreeMusketeer_Rework_Projectile"));
    Standard1v1Battle altered = new Standard1v1Battle(both);
    assertThatThrownBy(
            () ->
                new CharacterEntity(
                    altered.getWorld(),
                    altered.getWorld().getRecords().unit(MUSKETEER),
                    "M",
                    0,
                    X,
                    Y,
                    LEVEL))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("action with a projectile");
  }

  @Test
  @DisplayName(
      "an enchanting buff on a musketeer, whose attack sequence launches a projectile or runs an"
          + " action in place of the hit, starts and is listed on it")
  void anEnchantedMusketeerRuns() {
    Scene scene = new Scene(GameData.tables());
    CharacterEntity giver = scene.still(0, "Knight", X, Y - 2000, "G");
    BattleWorld world = scene.match.getWorld();
    BattleAction buff =
        world.getActions().build("giantbuffer_enchanting_buff", world.binding(scene.musketeer));
    scene.musketeer.actionHolder().start(buff, giver.actionHolder());
    assertThat(scene.musketeer.actionHolder().running())
        .anyMatch(GiantBufferBuff.Run.class::isInstance);
  }

  private static int evaluate(String text, BattleExpressionEnvironment environment) {
    return ExpressionEvaluator.evaluate(ExpressionCompiler.compile(text, environment), environment);
  }
}
