package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A curse on a Kamikaze unit as its hit ends. The game deletes every buff instance whose row has a
 * death spawn (the Witch Mother's curse, the Goblin Curse) from the unit before the unit kills
 * itself, so the kill makes no death spawn: no curse hog or curse goblin is made for a spirit that
 * jumps cursed.
 *
 * <p>The scene: side 0's Fire Spirit and side 1's Valkyrie, played on the same tick in the left
 * lane, meet at the river, and the Fire Spirit's hit ends with its own kill. A few ticks before its
 * hit the spirit is given a curse of side 1, a buff written for the test whose only columns are a
 * death spawn of one Skeleton for the enemy. The scene writes every column its outcome is read
 * from, so its ticks are its own and not a version's.
 */
class BattleKamikazeCurseDeleteTest {

  /** The battle stream's seed of the scene. */
  private static final int SEED = 1131;

  /** The tick of the Fire Spirit's hit, which ends with its kill. */
  private static final int KILL = 128;

  /** The tick the curse is put on the spirit, after its deploy and before its hit. */
  private static final int CURSED = KILL - 5;

  /** The curse's time, far past the scene's end. */
  private static final int CURSE_MS = 10_000;

  /** The last tick the scene is played to. */
  private static final int LAST_TICK = KILL + 20;

  /** The curse written for the test. */
  private static final String CURSE = "Test_Kamikaze_Curse";

  /** The configured tables with the scene's columns and the curse written. */
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
    GameData.alterLoaded(
        folder,
        "character_buffs",
        rows -> {
          ObjectNode row = rows.putObject(CURSE);
          row.put("index", rows.size() - 1);
          row.put("class", "LogicCharacterBuffData");
          ObjectNode columns = row.putObject("columns");
          columns.put("DeathSpawn", "Skeleton");
          columns.put("DeathSpawnCount", 1);
          columns.put("DeathSpawnIsEnemy", true);
          columns.put("Rarity", "Common");
        });
    GameData.writeTowers(folder);
    return GameTables.load(folder);
  }

  @Test
  @DisplayName(
      "a cursed Kamikaze spirit's hit deletes its curse before its kill, so its death leaves no"
          + " death spawn")
  void theKamikazeKillLeavesNoCurseSpawn(@TempDir Path folder) throws IOException {
    GameTables tables = written(folder);
    Standard1v1Battle match = new Standard1v1Battle(tables, 1, true);
    match.getWorld().seed(SEED);
    List<String> events = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void buffRemoved(int tick, WorldEntity target, BuffInstance buff) {
                if (buff.getBuff().name().equals(CURSE)) {
                  events.add(tick + " removed from " + target.name());
                }
              }

              @Override
              public void buffDeathSpawn(
                  int tick, WorldEntity dying, BuffInstance buff, List<CharacterEntity> spawned) {
                events.add(tick + " death spawn of " + dying.name());
              }
            });
    match.play(100, match.getWorld().getRecords().card("FireSpirits"), 1, 0, 3500, 14500, "F");
    match.play(100, match.getWorld().getRecords().card("Valkyrie"), 1, 1, 3500, 18500, "V");
    while (match.getBattle().getTick() < CURSED) {
      match.getBattle().step();
    }
    CharacterEntity spirit = match.getPlays().get(0).units().get(0);
    spirit.getBuffs().apply(match.getWorld().getRecords().buff(CURSE), CURSE_MS, 0, null, 1);
    assertThat(spirit.getBuffs().carries(CURSE)).isTrue();

    while (match.getBattle().getTick() < KILL) {
      match.getBattle().step();
    }
    assertThat(HitPoints.alive(spirit.getHitPoints())).as("its hit killed it").isFalse();
    assertThat(spirit.getBuffs().carries(CURSE)).as("deleted before the kill").isFalse();
    while (match.getBattle().getTick() < LAST_TICK) {
      match.getBattle().step();
    }

    assertThat(events).containsExactly((KILL - 1) + " removed from " + spirit.name());
  }
}
