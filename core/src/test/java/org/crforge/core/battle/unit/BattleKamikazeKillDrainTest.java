package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.nio.file.Path;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * When a Kamikaze unit's kill of itself lands within its tick. The game hands the kill that ends a
 * Kamikaze hit to the damage entry, which queues it for the holder's damage drain after every
 * post-hook, so a unit listed after it that targets it still sees it alive in its own visits of
 * that tick.
 *
 * <p>The scene: side 0's Fire Spirit and side 1's Valkyrie, played on the same tick in the left
 * lane, meet at the river. The Valkyrie, listed after the Fire Spirit, walks at it; the Fire Spirit
 * jumps at the Valkyrie and its hit, which launches its projectile, ends with its own kill. The
 * scene writes every column its outcome is read from - the Fire Spirit's card, row and projectile,
 * the Valkyrie's row, the towers' places, the columns of theirs the walk reads and their shots - so
 * its ticks and points are its own and not a version's.
 */
class BattleKamikazeKillDrainTest {

  /** The battle stream's seed of the scene. */
  private static final int SEED = 1131;

  /** The tick of the Fire Spirit's hit, which ends with its kill. */
  private static final int KILL = 128;

  /** The configured tables with the scene's columns written. */
  private static GameTables written(Path folder) throws IOException {
    GameData.altered(
        folder,
        "characters",
        rows -> {
          GameData.columns(rows, "FireSpirits")
              .put("Hitpoints", 84)
              .put("HitSpeed", 300)
              .put("LoadTime", 100)
              .put("Speed", 120)
              .put("Mass", 1)
              .put("CollisionRadius", 400)
              .put("Range", 2500)
              .put("SightRange", 5500)
              .put("DeployTime", 1000)
              .put("DeployDelay", 400)
              .put("SpawnRadius", 650)
              .put("Kamikaze", true);
          GameData.columns(rows, "Valkyrie")
              .put("Hitpoints", 745)
              .put("Damage", 104)
              .put("AreaDamageRadius", 2000)
              .put("AttackFinishTime", 100)
              .put("HitSpeed", 1500)
              .put("LoadTime", 1400)
              .put("Speed", 60)
              .put("Mass", 5)
              .put("CollisionRadius", 500)
              .put("Range", 1200)
              .put("SightRange", 5500)
              .put("DeployTime", 1000)
              .put("ProjectileStartRadius", 450)
              .put("ProjectileStartZ", 450);
        });
    GameData.alterLoaded(
        folder,
        "projectiles",
        rows ->
            GameData.columns(rows, "FireSpiritsProjectile")
                .put("Damage", 84)
                .put("Radius", 2300)
                .put("Speed", 400)
                .put("Gravity", 400));
    GameData.alterLoaded(
        folder,
        "spells_characters",
        rows ->
            GameData.columns(rows, "FireSpirits")
                .put("SummonNumber", 1)
                .put("SummonDeployDelay", 100));
    writeTowers(folder);
    return GameTables.load(folder);
  }

  /** Writes the towers' columns, their shots and their places into an altered copy. */
  private static void writeTowers(Path folder) throws IOException {
    GameData.alterLoaded(
        folder,
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
              .put("NoDeploySizeW", 18)
              .put("NoDeploySizeH", 16);
        });
    GameData.alterLoaded(
        folder,
        "projectiles",
        rows -> {
          GameData.columns(rows, "TowerPrincessProjectile")
              .put("Damage", 50)
              .put("Speed", 600)
              .put("Gravity", 60);
          GameData.columns(rows, "KingProjectile")
              .put("Damage", 50)
              .put("Speed", 1000)
              .put("Gravity", 50);
        });
    GameData.alterLoaded(
        folder,
        "spawn_groups",
        rows -> {
          ArrayNode towers = GameData.columns(rows, "King_PrincessTowers").putArray("Objects");
          towers.addObject().put("Data", "KingTower").put("x", 18).put("y", 6);
          towers.addObject().put("Data", "PrincessTower").put("x", 7).put("y", 13);
          towers.addObject().put("Data", "PrincessTower").put("x", 29).put("y", 13);
        });
  }

  /** The towers at the first level, fighting; the Fire Spirit and the Valkyrie played. */
  private static Standard1v1Battle scene(GameTables tables) {
    Standard1v1Battle match = new Standard1v1Battle(tables, 1, true);
    match.getWorld().seed(SEED);
    match.play(100, match.getWorld().getRecords().card("FireSpirits"), 1, 0, 3500, 14500, "F");
    match.play(100, match.getWorld().getRecords().card("Valkyrie"), 1, 1, 3500, 18500, "V");
    return match;
  }

  /** Steps the battle until its tick is the given one. */
  private static void stepTo(Standard1v1Battle match, int tick) {
    while (match.getBattle().getTick() < tick) {
      match.getBattle().step();
    }
  }

  /** Plays the scene to the tick before the kill and checks the Valkyrie walks at the spirit. */
  private static Standard1v1Battle beforeTheKill(GameTables tables) {
    Standard1v1Battle match = scene(tables);
    stepTo(match, KILL - 1);
    CharacterEntity spirit = match.getPlays().get(0).units().get(0);
    CharacterEntity valkyrie = match.getPlays().get(1).units().get(0);
    assertThat(valkyrie.getId()).as("listed after the spirit").isGreaterThan(spirit.getId());
    assertThat(HitPoints.alive(spirit.getHitPoints())).isTrue();
    assertThat(valkyrie.getTargeting().getReference().id()).isEqualTo(spirit.getId());
    assertThat(valkyrie.getView().getX()).isEqualTo(3423);
    assertThat(valkyrie.getView().getY()).isEqualTo(18093);
    stepTo(match, KILL);
    assertThat(HitPoints.alive(spirit.getHitPoints())).as("the hit killed it").isFalse();
    return match;
  }

  @Test
  @DisplayName(
      "the Kamikaze kill lands at the damage drain, so the Valkyrie"
          + " still walks at the Fire Spirit on the tick of its hit")
  void theValkyrieStillWalksAtTheSpirit(@TempDir Path folder) throws IOException {
    Standard1v1Battle match = beforeTheKill(written(folder));
    CharacterEntity valkyrie = match.getPlays().get(1).units().get(0);
    assertThat(valkyrie.getView().getX()).as("one more step at the spirit").isEqualTo(3416);
    assertThat(valkyrie.getView().getY()).isEqualTo(18034);
    assertThat(valkyrie.getTargeting().getReference()).as("dropped at its death").isNull();
  }
}
