package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The id list of a projectile's flying body over a Goblin Giant and its riders. The body that hits
 * a carrier lists its riders as hit too. When the carrier dies its riders are let go and each
 * leaves its death spawn, a Spear Goblin; the rider's row sets the inherited ignore list, so every
 * list that holds the rider lists the Spear Goblin as well, and the body that hit the carrier
 * passes over the Spear Goblins without hitting them.
 */
class BattleRiderIgnoreListTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  @Test
  @DisplayName(
      "a rolling Log that hit a Goblin Giant lists its riders and passes over the Spear Goblins"
          + " they leave")
  void theLogPassesOverTheRidersSpawns() {
    GameTables tables = GameData.tables();
    check(tables, new BattleRecords(tables));
  }

  private static void check(GameTables tables, BattleRecords records) {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    // A blue Goblin Giant walking up the left lane, and a red Log rolling down the lane at it.
    match.play(0, records.card("GoblinGiant"), LEVEL, 0, 3500, 10000, "GoblinGiant");
    match.play(30, records.card("Log"), LEVEL, 1, 3500, 13000, "Log");
    List<WorldEntity> hits = new ArrayList<>();
    List<ProjectileEntity> bodies = new ArrayList<>();
    List<CharacterEntity> spawns = new ArrayList<>();
    match
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
                  hits.add(target);
                  if (!bodies.contains(projectile)) {
                    bodies.add(projectile);
                  }
                }
              }

              @Override
              public void characterSpawned(
                  int tick, SpawnHost source, CharacterEntity spawned, int x, int y) {
                if (source instanceof CharacterEntity rider
                    && rider.getData().name().equals("SpearGoblinGiant")) {
                  spawns.add(spawned);
                }
              }
            });
    match.getBattle().step();
    CharacterEntity giant = match.getPlays().get(0).units().get(0);
    List<CharacterEntity> riders = List.copyOf(giant.riders());
    assertThat(riders).hasSize(2);
    // Step until the Log's body has hit the Giant, then kill the Giant between two steps: the next
    // step's opening cleanup removes it and lets its riders go, each leaving its Spear Goblin.
    int step = 0;
    while (!hits.contains(giant) && step++ < 200) {
      match.getBattle().step();
    }
    assertThat(hits).as("the body hit the Giant").contains(giant);
    ProjectileEntity body = bodies.get(0);
    assertThat(body.getHitIds())
        .as("the body lists the riders with their carrier")
        .contains(giant.getId(), riders.get(0).getId(), riders.get(1).getId());
    match.getWorld().kill(giant, null);
    match.getBattle().step();
    assertThat(spawns).as("each rider left its Spear Goblin").hasSize(2);
    assertThat(body.getHitIds())
        .as("the Spear Goblins join the body's list beside their riders")
        .contains(spawns.get(0).getId(), spawns.get(1).getId());
    for (int i = 0; i < 20; i++) {
      match.getBattle().step();
    }

    assertThat(hits).as("the body never hits a Spear Goblin").doesNotContainAnyElementsOf(spawns);
    for (CharacterEntity spawn : spawns) {
      assertThat(spawn.getHitPoints().getHitPoints())
          .as(spawn.name() + " is not hit")
          .isEqualTo(spawn.getHitPoints().getMaximum());
    }
  }
}
