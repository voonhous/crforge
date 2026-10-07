package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.spawn.SpawnHost;
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

    /** The centre of the Barbarians' ring: the middle of the two. */
    int centreX() {
      return (barbarians.get(0)[0] + barbarians.get(1)[0]) / 2;
    }

    int centreY() {
      return (barbarians.get(0)[1] + barbarians.get(1)[1]) / 2;
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
      battle.getBattle().step();
    }
    assertThat(recording.barbarians).as("the ram died and left its Barbarians").hasSize(2);
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
    assertThat(death.volleyPellets()).isEqualTo(10);
    assertThat(death.pelletsFlyingOn()).isBetween(1, 9);
  }
}
