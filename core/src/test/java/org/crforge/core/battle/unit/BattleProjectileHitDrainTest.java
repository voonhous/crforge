package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.data.GameVersions;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * When the damage of a projectile's hit on its one target lands within its tick. The game of data
 * version 16.402.18 queues it for the holder's damage drain, as it queues a direct hit, and the
 * damage entry's refusals, the immunity left after a dash among them, are tested as the drain deals
 * it: after the target's own state visit of that tick has counted its immunity down. The game of
 * 14.593.1 deals the hit inside the impact, before the target's visit, and tests the immunity as it
 * stands then.
 *
 * <p>The scene: the bottom side's Bandit runs up the left lane at the top side's Knight, held where
 * it stands in reach of the top side's left princess tower, and dashes at it. One of the tower's
 * arrows reaches the Bandit while it dashes, refused on both versions; the next reaches it on the
 * step after its dash lands, with 50 ms of its 100 ms immunity left as the step begins; the
 * Bandit's visit of that step counts it to 0. The same battle runs on the configured tables and on
 * those tables relabelled as data version 16.402.18, which differ only in the version's rule.
 */
class BattleProjectileHitDrainTest {

  /** The level of the towers and of both units. */
  private static final int LEVEL = 1;

  /** Where the Bandit is placed, on the bottom side's half of the left lane. */
  private static final int BANDIT_X = 3500;

  private static final int BANDIT_Y = 16000;

  /** Where the Knight is held, in reach of the top side's left princess tower. */
  private static final int KNIGHT_Y = 23850;

  /** Long enough for the Bandit's run, its dash and the arrow after it. */
  private static final int TICKS = 200;

  @TempDir Path folder;

  /**
   * The configured tables copied into a folder with every file labelled as another data version.
   *
   * @param folder the folder to copy them into
   * @param version the data version the copy is labelled with
   */
  private static GameTables relabelled(Path folder, String version) throws IOException {
    Path source = GameTables.configuredDirectory().orElseThrow();
    ObjectMapper mapper = new ObjectMapper();
    try (Stream<Path> files = Files.list(source)) {
      for (Path file : files.toList()) {
        Path copy = folder.resolve(file.getFileName());
        if (file.getFileName().toString().endsWith(".json")) {
          ObjectNode document = (ObjectNode) mapper.readTree(file.toFile());
          document.put("version", version);
          mapper.writeValue(copy.toFile(), document);
        } else {
          Files.copy(file, copy);
        }
      }
    }
    return GameTables.load(folder);
  }

  /**
   * What the scene records: the tick the dash landed on and every arrow that reached the Bandit.
   */
  private static final class Record {
    int landing = -1;
    final List<String> arrows = new ArrayList<>();
    CharacterEntity bandit;
  }

  /**
   * Runs the scene to two steps past the Bandit's dash landing, recording each arrow on the Bandit
   * as its tick, whether it landed, and the Bandit's immunity left as it was dealt.
   */
  private static Record run(GameTables tables) {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, true);
    Record record = new Record();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void dashLanded(
                  int tick, CharacterEntity unit, WorldEntity hit, int damage, boolean area) {
                if (unit == record.bandit && record.landing < 0) {
                  record.landing = tick;
                }
              }

              @Override
              public void projectileImpacted(
                  int tick,
                  ProjectileEntity projectile,
                  WorldEntity target,
                  int damage,
                  DamageResult result) {
                if (target == record.bandit) {
                  record.arrows.add(
                      tick
                          + " "
                          + result.landed()
                          + " "
                          + record.bandit.getUnit().timers().getDashImmunityRemainingMs());
                }
              }
            });
    record.bandit =
        match.deploy(0, GameData.unit("Assassin"), LEVEL, 0, BANDIT_X, BANDIT_Y, "Bandit");
    CharacterEntity knight =
        match.deploy(0, GameData.unit("Knight"), LEVEL, 1, BANDIT_X, KNIGHT_Y, "Knight");
    knight.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    for (int tick = 0; tick < TICKS; tick++) {
      match.getBattle().step();
      if (record.landing >= 0 && match.getBattle().getTick() > record.landing + 2) {
        break;
      }
    }
    assertThat(record.landing).as("the Bandit dashed").isPositive();
    return record;
  }

  @Test
  @DisplayName(
      "on data version 16.402.18 an arrow one step after the Bandit's dash lands is dealt at the"
          + " damage drain, once the Bandit's visit has counted its immunity down to 0")
  void theArrowLandsAtTheDrain() throws IOException {
    Record record = run(relabelled(folder, GameVersions.DATA_16_402_18));
    assertThat(record.arrows).hasSize(2);
    assertThat(record.arrows.get(0)).as("an arrow during the dash").endsWith(" false 100");
    assertThat(record.arrows.get(1)).isEqualTo((record.landing + 1) + " true 0");
  }

  @Test
  @DisplayName(
      "on data version 14.593.1 the same arrow is refused inside the impact, the Bandit's 50 ms of"
          + " immunity still left")
  void theArrowIsRefusedOnTheOlderVersion() {
    assertThat(GameData.tables().version()).isEqualTo(GameVersions.DATA_14_593_1);
    Record record = run(GameData.tables());
    assertThat(record.arrows).hasSize(2);
    assertThat(record.arrows.get(0)).as("an arrow during the dash").endsWith(" false 100");
    assertThat(record.arrows.get(1)).isEqualTo((record.landing + 1) + " false 50");
  }
}
