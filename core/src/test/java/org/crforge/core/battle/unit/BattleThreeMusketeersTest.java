package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.expression.ExpressionCompiler;
import org.crforge.core.battle.expression.ExpressionEvaluator;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Three Musketeers' attack choice: the filter each runs as an attack starts and at every hit
 * picks the entry the next hit reads, the bayonet for a ground target inside 1600 of both radii and
 * a shot otherwise; the bayonet's action deals a typed hit on the target in the drain of the hit's
 * own tick, at the musketeer's level and with no crown-tower share. What the references do not
 * reach is held here: the buff on damage after a bayonet, target_is_ground with the targeting off
 * and on a target whose layer a tag forces, and the refusals around an entry's action.
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
      "a Knight inside the bayonet's reach takes 314 typed hits and no shot; a Minion as close"
          + " takes shots")
  void groundTakesTheBayonetAirTheShot() {
    Scene ground = new Scene(GameData.tables());
    ground.still(1, "Knight", X, Y + 2000, "K");
    ground.step(80);
    assertThat(ground.launches).isEmpty();
    assertThat(ground.typed).isNotEmpty().allMatch(hit -> hit.endsWith(" M K 314"));

    Scene air = new Scene(GameData.tables());
    air.still(1, "Minion", X, Y + 1500, "A");
    air.step(80);
    assertThat(air.typed).isEmpty();
    assertThat(air.launches).isNotEmpty();
  }

  @Test
  @DisplayName(
      "the buff on damage follows a bayonet that landed, in its hit's tick, before its typed hit"
          + " lands")
  void theBuffOnDamageFollowsTheBayonet(@TempDir Path folder) throws IOException {
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
    while (scene.typed.isEmpty() && scene.tick < 80) {
      scene.step(1);
    }
    int hit = Integer.parseInt(scene.typed.get(0).split(" ")[0]);
    assertThat(scene.buffs).first().isEqualTo(hit + " K ZapFreeze");
  }

  @Test
  @DisplayName(
      "target_is_ground answers 0 while the targeting is off, and refuses a target whose layer a"
          + " tag forces")
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

    long forced = world.gameTagMask(world.gameTagIndex("FORCE_IS_AIR"));
    knight.getView().setFlags(knight.getView().getFlags() | forced);
    assertThatThrownBy(() -> evaluate("target_is_ground", environment))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("FORCE_IS_AIR");
  }

  @Test
  @DisplayName(
      "an entry's action in a sequence of one, or beside a projectile, is refused as the unit is"
          + " made")
  void unheldEntryActionsAreRefused(@TempDir Path folder) throws IOException {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    assertThatThrownBy(
            () ->
                new CharacterEntity(
                    match.getWorld(), GameData.unit("ElectroWizard_crazy_1"), "E", 0, X, Y, LEVEL))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("in a sequence of one");

    GameTables both =
        GameData.altered(
            folder,
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
      "an enchanting buff on a musketeer, whose attack sequence replaces its attack, is refused")
  void anEnchantedMusketeerIsRefused() {
    Scene scene = new Scene(GameData.tables());
    CharacterEntity giver = scene.still(0, "Knight", X, Y - 2000, "G");
    BattleWorld world = scene.match.getWorld();
    BattleAction buff =
        world.getActions().build("giantbuffer_enchanting_buff", world.binding(scene.musketeer));
    assertThatThrownBy(() -> scene.musketeer.actionHolder().start(buff, giver.actionHolder()))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("attack sequence replaces its attack");
  }

  private static int evaluate(String text, BattleExpressionEnvironment environment) {
    return ExpressionEvaluator.evaluate(ExpressionCompiler.compile(text, environment), environment);
  }
}
