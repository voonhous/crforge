package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.data.GameVersions;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.crforge.core.pathfinding.target.TargetView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * When a Kamikaze unit's kill of itself lands within its tick. The game of data version 16.402.18
 * hands the kill that ends a Kamikaze hit to the damage entry, which queues it for the holder's
 * damage drain after every post-hook, so a unit listed after it that targets it still sees it alive
 * in its own visits of that tick; the game of 14.593.1 kills it inside its hit, and the later unit
 * finds its target gone in the same tick.
 *
 * <p>The scene: side 0's Fire Spirit and side 1's Valkyrie, played on the same tick in the left
 * lane, meet at the river. The Valkyrie, listed after the Fire Spirit, walks at it; the Fire Spirit
 * jumps at the Valkyrie and its hit, which launches its projectile, ends with its own kill. The
 * same battle runs on the configured tables and on those tables relabelled as data version
 * 16.402.18, which differ only in the version's rule.
 */
class BattleKamikazeKillDrainTest {

  /** The battle stream's seed of the scene. */
  private static final int SEED = 1131;

  /** The tick of the Fire Spirit's hit, which ends with its kill. */
  private static final int KILL = 128;

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

  /** The towers at the first level, fighting; the Fire Spirit and the Valkyrie played. */
  private static Standard1v1Battle scene(GameTables tables) {
    Standard1v1Battle match = new Standard1v1Battle(tables, 1, true);
    match.getWorld().seed(SEED);
    match.play(100, GameData.card("FireSpirits"), 1, 0, 3500, 14500, "F");
    match.play(100, GameData.card("Valkyrie"), 1, 1, 3500, 18500, "V");
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
      "on data version 16.402.18 the Kamikaze kill lands at the damage drain, so the Valkyrie"
          + " still walks at the Fire Spirit on the tick of its hit")
  void theValkyrieStillWalksAtTheSpirit() throws IOException {
    Standard1v1Battle match = beforeTheKill(relabelled(folder, GameVersions.DATA_16_402_18));
    CharacterEntity valkyrie = match.getPlays().get(1).units().get(0);
    assertThat(valkyrie.getView().getX()).as("one more step at the spirit").isEqualTo(3416);
    assertThat(valkyrie.getView().getY()).isEqualTo(18034);
    assertThat(valkyrie.getTargeting().getReference()).as("dropped at its death").isNull();
  }

  @Test
  @DisplayName(
      "on data version 14.593.1 the Kamikaze kill lands inside the hit, and the Valkyrie turns to"
          + " a tower in the same tick")
  void theValkyrieTurnsOnTheOlderVersion() {
    assertThat(GameData.tables().version()).isEqualTo(GameVersions.DATA_14_593_1);
    Standard1v1Battle match = beforeTheKill(GameData.tables());
    CharacterEntity valkyrie = match.getPlays().get(1).units().get(0);
    TargetView reference = valkyrie.getTargeting().getReference();
    assertThat(reference).as("a new target at once").isNotNull();
    assertThat(match.getWorld().liveObject(reference.id())).isInstanceOf(TowerEntity.class);
  }
}
