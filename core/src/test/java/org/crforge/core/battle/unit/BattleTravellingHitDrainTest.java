/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.battle.Shipped.number;
import static org.crforge.core.battle.Shipped.unitRow;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.TakeDamage;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * When the travelling hit of a projectile flying to a point lands its damage, seen through a Hunter
 * volley that kills a walking Battle Ram. The game queues each pellet's damage for the holder's
 * damage drain, after every movement visit of the tick: the ram walks its step of that tick first
 * and dies at the drain, so its Barbarians ring the point it walked to. The pellets of one volley
 * are one group, and a pellet whose queued damage takes the ram's queued total to its hit points
 * and shield marks it in the group: the volley's later pellets pass over it and fly on.
 *
 * <p>The scene: the bottom side's Hunter stands in the left lane, and the top side's Battle Ram
 * walks down that lane at the bottom side's left princess tower; the towers do not fight.
 *
 * <p>The ram's queued total also holds the other hits queued for it in the same tick: a
 * projectile's hit on its one target adds its damage, as the travelling hits do, while a
 * damage-taking action's hit adds nothing, its amount being worked out only at the drain. The scene
 * is played again with one such hit queued for the ram just before the tick of its death.
 */
class BattleTravellingHitDrainTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Long enough for the ram to walk into the Hunter's reach and die. */
  private static final int TICKS = 600;

  /** What one scene records about the ram's death. */
  private record Death(
      int tick,
      int beforeX,
      int beforeY,
      List<int[]> barbarians,
      int volleyPellets,
      int pelletsFlyingOn) {

    /** The centre of the Barbarians' ring: the mean of their points. */
    int centreX() {
      return barbarians.stream().mapToInt(b -> b[0]).sum() / barbarians.size();
    }

    int centreY() {
      return barbarians.stream().mapToInt(b -> b[1]).sum() / barbarians.size();
    }
  }

  /** Records where the ram's Barbarians are made and which pellets hit the ram. */
  private static final class Recording implements WorldObserver {
    private final List<int[]> barbarians = new ArrayList<>();
    private final List<ProjectileEntity> pellets = new ArrayList<>();
    private final List<Integer> launchTicks = new ArrayList<>();
    private CharacterEntity ram;

    @Override
    public void characterSpawned(
        int tick, SpawnHost source, CharacterEntity child, int createdX, int createdY) {
      if (source == ram) {
        barbarians.add(new int[] {createdX, createdY, tick});
      }
    }

    @Override
    public void projectileLaunched(int tick, ProjectileEntity projectile) {
      if (projectile.getData().name().equals("HunterProjectile")) {
        pellets.add(projectile);
        launchTicks.add(tick);
      }
    }
  }

  private static Death scene(GameTables tables) {
    return scene(tables, -1, null);
  }

  /**
   * Plays the scene.
   *
   * @param queueAt the tick before whose step {@code queue} runs, or -1 for none
   * @param queue what queues a further hit for the ram, handed the world and the recording
   */
  private static Death scene(
      GameTables tables, int queueAt, BiConsumer<BattleWorld, Recording> queue) {
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, false);
    Recording recording = new Recording();
    battle.getWorld().addObserver(recording);
    battle.deploy(0, records.unit("Hunter"), LEVEL, 0, 3500, 11000, "hunter");
    recording.ram = battle.deploy(0, records.unit("BattleRam"), LEVEL, 1, 3500, 21000, "ram");
    int beforeX = 0;
    int beforeY = 0;
    for (int step = 0; step < TICKS && recording.barbarians.isEmpty(); step++) {
      beforeX = recording.ram.getView().getX();
      beforeY = recording.ram.getView().getY();
      // The step about to run is the world's next tick.
      if (queue != null && battle.getWorld().tick() + 1 == queueAt) {
        queue.accept(battle.getWorld(), recording);
      }
      battle.getBattle().step();
    }
    assertThat(recording.barbarians)
        .as("the ram died and left its Barbarians")
        .hasSize(number(unitRow("BattleRam"), "DeathSpawnCount"));
    int deathTick = recording.barbarians.get(0)[2];
    // The pellets of the death tick's volley still in the battle at its end: those that did not
    // stop at the ram.
    int volley = 0;
    int flyingOn = 0;
    for (int i = 0; i < recording.pellets.size(); i++) {
      if (recording.launchTicks.get(i) == deathTick) {
        volley++;
        if (!recording.pellets.get(i).isRemovable()) {
          flyingOn++;
        }
      }
    }
    return new Death(deathTick, beforeX, beforeY, recording.barbarians, volley, flyingOn);
  }

  @Test
  @DisplayName(
      "a Hunter volley's damage lands at the drain: the walking ram"
          + " dies after its step and its Barbarians ring the point it walked to")
  void theRamDiesAfterItsStep() {
    Death death = scene(GameData.tables());
    // The ring is centred where the ram stood after its step of the death tick, not before it.
    assertThat(death.centreY()).isLessThan(death.beforeY() - 50);
    // The volley killed it in one tick, and its later pellets passed over it.
    int pellets = number(unitRow("Hunter"), "MultipleProjectiles");
    assertThat(death.volleyPellets()).isEqualTo(pellets);
    assertThat(death.pelletsFlyingOn()).isBetween(1, pellets - 1);
  }

  /** The ram's hit points and shield as they stand: a hit this large kills it on its own. */
  private static int whole(CharacterEntity ram) {
    HitPoints hitPoints = ram.getHitPoints();
    return hitPoints.getHitPoints() + hitPoints.getShield();
  }

  @Test
  @DisplayName(
      "a projectile's hit queued for the ram counts in its queued total: the volley's first"
          + " pellet marks it and every later one passes over it")
  void aQueuedProjectileHitCounts() {
    GameTables tables = GameData.tables();
    Death plain = scene(tables);
    List<ProjectileEntity> shooter = new ArrayList<>();
    Death queued =
        scene(
            tables,
            plain.tick(),
            (world, recording) -> {
              // A projectile's hit on its one target, the ram, large enough to kill it alone, from
              // the Hunter's first pellet.
              assertThat(recording.pellets).as("the Hunter has shot before").isNotEmpty();
              ProjectileEntity projectile = recording.pellets.get(0);
              shooter.add(projectile);
              world.dealProjectileHit(
                  projectile, recording.ram, whole(recording.ram), world.nextHitId(), 0, 0);
            });
    assertThat(shooter).hasSize(1);
    assertThat(queued.tick()).isEqualTo(plain.tick());
    int pellets = number(unitRow("Hunter"), "MultipleProjectiles");
    assertThat(queued.volleyPellets()).isEqualTo(pellets);
    // Without the queued hit the volley's later pellets hit the ram before one marks it.
    assertThat(plain.pelletsFlyingOn()).isLessThan(pellets - 1);
    assertThat(queued.pelletsFlyingOn()).isEqualTo(pellets - 1);
  }

  @Test
  @DisplayName(
      "a damage-taking action's hit queued for the ram adds nothing to its queued total: the"
          + " volley's pellets hit it as they do without it")
  void aQueuedActionHitDoesNotCount() {
    GameTables tables = GameData.tables();
    Death plain = scene(tables);
    Death queued =
        scene(
            tables,
            plain.tick(),
            (world, recording) ->
                // A damage-taking action's hit on the ram with no source, large enough to kill it
                // alone.
                world.queueActionDamage(
                    null,
                    recording.ram,
                    new TakeDamage.Damage(
                        whole(recording.ram),
                        TakeDamage.NO_TOWER_DAMAGE,
                        false,
                        false,
                        false,
                        false,
                        false),
                    0));
    assertThat(queued.tick()).isEqualTo(plain.tick());
    assertThat(queued.volleyPellets()).isEqualTo(plain.volleyPellets());
    assertThat(queued.pelletsFlyingOn()).isEqualTo(plain.pelletsFlyingOn());
  }
}
