package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.pathfinding.EntityFlags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Phoenix and its egg. The references hold one fireball aimed at the Phoenix's own point, the
 * egg's tags keeping it standing, its immunity against a tower and its hatch and removal; what they
 * do not reach is held here: a death projectile's ring and both sides' turns, the refusals around
 * it, a spell reaching an immune egg, the tick the row's tags join the tag word, and a limited
 * spawner that stays once its firings are spent.
 */
class BattlePhoenixTest {

  /** Which bit of a tag word each flag is, as the configured tables number the game tags. */
  private static final EntityFlags BITS = EntityFlags.of(GameData.tables());

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final int X = 3500;

  private static final int Y = 20000;

  /**
   * One battle with every death projectile, spawner firing, destruction at the limit, death and
   * spawned child logged.
   */
  private static final class Scene {
    final Standard1v1Battle match;
    final List<String> shots = new ArrayList<>();
    final List<String> firings = new ArrayList<>();
    final List<String> destroyed = new ArrayList<>();
    final List<String> deaths = new ArrayList<>();
    final List<CharacterEntity> spawned = new ArrayList<>();
    int tick;

    Scene(GameTables tables) {
      match = new Standard1v1Battle(tables, LEVEL, false);
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void deathProjectileLaunched(
                    int t, WorldEntity dying, ProjectileEntity projectile) {
                  shots.add(
                      "%s %d %d %d aim %d %d"
                          .formatted(
                              projectile.getData().name(),
                              projectile.getStartX(),
                              projectile.getStartY(),
                              projectile.getStartZ(),
                              projectile.getAimX(),
                              projectile.getAimY()));
                }

                @Override
                public void spawnerFired(
                    int t,
                    CharacterEntity spawner,
                    String row,
                    int count,
                    int radius,
                    int timerAfter,
                    int waveMade) {
                  firings.add(tick + " " + spawner.name() + " " + row);
                }

                @Override
                public void destroyedAtLimit(int t, CharacterEntity spawner) {
                  destroyed.add(tick + " " + spawner.name());
                }

                @Override
                public void deathHooksScheduled(
                    int t,
                    WorldEntity dying,
                    BattleEntity attacker,
                    int side,
                    List<String> hooks,
                    boolean inPendingPass) {
                  deaths.add(dying.name());
                }

                @Override
                public void characterSpawned(
                    int t, SpawnHost source, CharacterEntity child, int x, int y) {
                  spawned.add(child);
                }
              });
    }

    /** Places a unit of a row that stands still and steps once, so that it is in the battle. */
    CharacterEntity place(int side, String row, int x, int y) {
      CharacterEntity unit =
          match.deploy(tick, match.getWorld().getRecords().unit(row), LEVEL, side, x, y, row);
      step(1);
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

  /**
   * The Phoenix's row with a ring of three fireballs, 1000 out, turned by 30 degrees, flying at
   * 3000, where its fireballs start.
   */
  private static GameTables ringOfThree(Path folder) throws IOException {
    return GameData.altered(
        folder,
        "characters",
        rows -> {
          GameData.columns(rows, "Phoenix").put("SpawnRadius", 1000);
          GameData.columns(rows, "Phoenix").put("SpawnAngleShift", 30);
          GameData.columns(rows, "Phoenix").put("DeathSpawnCount", 3);
          GameData.columns(rows, "Phoenix").put("FlyingHeight", 3000);
        });
  }

  /**
   * The egg's row with its spawner written: one Phoenix once, 3800 ms after a deploy of 1000 ms,
   * removed at its limit unless asked to stay.
   */
  private static GameTables egg(Path folder, boolean destroyAtLimit) throws IOException {
    return GameData.altered(
        folder,
        "characters",
        rows ->
            GameData.columns(rows, "PhoenixEgg")
                .put("SpawnCharacter", "PhoenixNoRespawn")
                .put("SpawnNumber", 1)
                .put("SpawnLimit", 1)
                .put("DeployTime", 1000)
                .put("SpawnStartTime", 3800)
                .put("SpawnPauseTime", 4300)
                .put("DestroyAtLimit", destroyAtLimit));
  }

  @Test
  @DisplayName(
      "a ring of death projectiles starts at the angle shift and steps a third of the circle, the"
          + " bottom side's turned across the width and the top side's along the length")
  void theRingTurnsBySide(@TempDir Path folder) throws IOException {
    GameTables ring = ringOfThree(folder);
    Scene bottom = new Scene(ring);
    CharacterEntity phoenix = bottom.place(0, "Phoenix", X, Y);
    bottom.match.getWorld().kill(phoenix, null);
    // The kill lands at the next step's damage drain.
    bottom.step(1);
    assertThat(bottom.shots)
        .containsExactly(
            "PhoenixFireball 3500 20000 3000 aim 3000 20866",
            "PhoenixFireball 3500 20000 3000 aim 3000 19134",
            "PhoenixFireball 3500 20000 3000 aim 4500 20000");

    Scene top = new Scene(ring);
    CharacterEntity other = top.place(1, "Phoenix", X, Y);
    top.match.getWorld().kill(other, null);
    top.step(1);
    assertThat(top.shots)
        .containsExactly(
            "PhoenixFireball 3500 20000 3000 aim 4000 19134",
            "PhoenixFireball 3500 20000 3000 aim 4000 20866",
            "PhoenixFireball 3500 20000 3000 aim 2500 20000");
  }

  @Test
  @DisplayName(
      "a death projectile drawn from a least radius, counted by a spawner's limit, held by its"
          + " launcher's targeting or launched by a clone is refused")
  void unheldDeathProjectilesAreRefused(@TempDir Path folder) throws IOException {
    GameTables drawn =
        GameData.altered(
            Files.createDirectories(folder.resolve("drawn")),
            "characters",
            rows -> GameData.columns(rows, "Phoenix").put("DeathSpawnMinRadius", 500));
    Scene least = new Scene(drawn);
    CharacterEntity first = least.place(0, "Phoenix", X, Y);
    assertThatThrownBy(
            () -> {
              least.match.getWorld().kill(first, null);
              least.step(1);
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("draws each one's radius");

    GameTables limited =
        GameData.altered(
            Files.createDirectories(folder.resolve("limited")),
            "characters",
            rows -> GameData.columns(rows, "Phoenix").put("SpawnLimit", 2));
    Scene limit = new Scene(limited);
    CharacterEntity second = limit.place(0, "Phoenix", X, Y);
    assertThatThrownBy(
            () -> {
              limit.match.getWorld().kill(second, null);
              limit.step(1);
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("what its spawner's limit has left");

    GameTables sweeping =
        GameData.altered(
            Files.createDirectories(folder.resolve("sweeping")),
            "projectiles",
            rows -> GameData.columns(rows, "PhoenixFireball").put("PingpongVisualTime", 500));
    Scene sweep = new Scene(sweeping);
    CharacterEntity third = sweep.place(0, "Phoenix", X, Y);
    assertThatThrownBy(
            () -> {
              sweep.match.getWorld().kill(third, null);
              sweep.step(1);
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("its targeting component would hold");

    Scene cloned = new Scene(GameData.tables());
    CharacterEntity original = cloned.place(0, "Knight", X, Y - 3000);
    CharacterEntity clone = cloned.place(0, "Phoenix", X, Y);
    clone.markClone(original);
    assertThatThrownBy(
            () -> {
              cloned.match.getWorld().kill(clone, null);
              cloned.step(1);
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("is a clone");
  }

  @Test
  @DisplayName(
      "an egg is immune as the fireball's impact makes it, but a spell still reaches it; its row's"
          + " tags are in its tag word from the moment the holder takes it, and every tick after")
  void theEggsImmunityAndTags() {
    Scene scene = new Scene(GameData.tables());
    CharacterEntity phoenix = scene.place(0, "Phoenix", X, Y);
    scene.match.getWorld().kill(phoenix, null);
    // The kill lands at the next step's damage drain; the fireball, aimed at its own start, lands
    // on its first flight visit, in the step after.
    scene.step(2);
    assertThat(scene.spawned).hasSize(1);
    CharacterEntity egg = scene.spawned.get(0);
    assertThat(egg.getData().name()).isEqualTo("PhoenixEgg");
    assertThat(egg.isSpawnImmune()).isTrue();
    long rowTags =
        BITS.noGiantbufferChefEnchantment()
            | BITS.avoidanceAsObstacle()
            | BITS.noMoveAllowAttract();
    // Made after the pre-hooks, but the holder's add folds its row's tags as it takes the egg.
    assertThat(egg.getView().getFlags()).as("folded by the holder's add").isEqualTo(rowTags);
    scene.step(1);
    assertThat(egg.getView().getFlags()).isEqualTo(rowTags);

    int full = egg.getHitPoints().getHitPoints();
    scene.match.play(scene.tick, GameData.card("Zap"), LEVEL, 1, X, Y, "Z");
    scene.step(1);
    assertThat(egg.isSpawnImmune()).as("still immune").isTrue();
    assertThat(egg.getHitPoints().getHitPoints()).isLessThan(full);
  }

  @Test
  @DisplayName(
      "an egg hatches once and leaves the visit after, with no death; one whose row keeps it stays"
          + " and fires no more")
  void aLimitedSpawnerFiresOnce(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(egg(Files.createDirectories(folder.resolve("removed")), true));
    CharacterEntity egg = scene.place(0, "PhoenixEgg", X, Y);
    scene.step(200);
    assertThat(scene.firings).hasSize(1);
    int hatch = Integer.parseInt(scene.firings.get(0).split(" ")[0]);
    assertThat(scene.destroyed).containsExactly((hatch + 1) + " " + egg.name());
    assertThat(scene.match.getWorld().liveObject(egg.getId())).isNull();
    assertThat(scene.deaths).doesNotContain(egg.name());

    GameTables kept = egg(Files.createDirectories(folder.resolve("kept")), false);
    Scene stays = new Scene(kept);
    CharacterEntity same = stays.place(0, "PhoenixEgg", X, Y);
    stays.step(400);
    assertThat(stays.firings).hasSize(1);
    assertThat(stays.destroyed).isEmpty();
    assertThat(stays.match.getWorld().liveObject(same.getId())).isSameAs(same);
  }
}
