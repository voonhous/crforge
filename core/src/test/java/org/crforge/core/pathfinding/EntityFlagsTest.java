package org.crforge.core.pathfinding;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The flag bits are the game tags table's row indices, read by name. */
class EntityFlagsTest {

  @TempDir Path folder;

  @Test
  @DisplayName("on the configured tables every flag has its tag row's index as its bit")
  void theConfiguredBitsAreTheRowIndices() {
    EntityFlags bits = EntityFlags.of(GameData.tables());
    assertThat(bits.dashing()).isEqualTo(1L << 2);
    assertThat(bits.charging()).isEqualTo(1L << 3);
    assertThat(bits.attacking()).isEqualTo(1L << 5);
    assertThat(bits.noMove()).isEqualTo(1L << 6);
    assertThat(bits.noDash()).isEqualTo(1L << 8);
    assertThat(bits.noAttack()).isEqualTo(1L << 10);
    assertThat(bits.lockTarget()).isEqualTo(1L << 12);
    assertThat(bits.noSpawnTimer()).isEqualTo(1L << 13);
    assertThat(bits.noCheckCollisions()).isEqualTo(1L << 14);
    assertThat(bits.noCheckAvoidance()).isEqualTo(1L << 15);
    assertThat(bits.noBuffs()).isEqualTo(1L << 16);
    assertThat(bits.noPushedByEnemy()).isEqualTo(1L << 17);
    assertThat(bits.inactive()).isEqualTo(1L << 20);
    assertThat(bits.activating()).isEqualTo(1L << 21);
    assertThat(bits.hasShield()).isEqualTo(1L << 22);
    assertThat(bits.hidden()).isEqualTo(1L << 24);
    assertThat(bits.abilityDisabled()).isEqualTo(1L << 25);
    assertThat(bits.buildingDeathSpawnFindLocation()).isEqualTo(1L << 26);
    assertThat(bits.abilityPostponed()).isEqualTo(1L << 29);
    assertThat(bits.noSummon()).isEqualTo(1L << 31);
    assertThat(bits.noSpecialAttack()).isEqualTo(1L << 35);
    assertThat(bits.hasCapture()).isEqualTo(1L << 37);
    assertThat(bits.forceIsGround()).isEqualTo(1L << 39);
    assertThat(bits.noPushback()).isEqualTo(1L << 40);
    assertThat(bits.noDamage()).isEqualTo(1L << 41);
    assertThat(bits.noGiantbufferChefEnchantment()).isEqualTo(1L << 43);
    assertThat(bits.forceIsAir()).isEqualTo(1L << 44);
    assertThat(bits.captured()).isEqualTo(1L << 45);
    assertThat(bits.disablePhysical()).isEqualTo(1L << 46);
    assertThat(bits.castingAbility()).isEqualTo(1L << 50);
    assertThat(bits.untargetable()).isEqualTo(1L << 51);
    assertThat(bits.noPushedByAlly()).isEqualTo(1L << 52);
    assertThat(bits.avoidanceAsObstacle()).isEqualTo(1L << 53);
    assertThat(bits.abilityCooldownPaused()).isEqualTo(1L << 54);
    assertThat(bits.warp()).isEqualTo(1L << 55);
    assertThat(bits.noClone()).isEqualTo(1L << 56);
    assertThat(bits.noMoveAllowAttract()).isEqualTo(1L << 57);
    // The tables hold no NO_REFLECTED_ATTACK row, so the flag has no bit.
    assertThat(bits.noReflectedAttack()).isZero();
    assertThat(bits.keepsTargetingOff()).isEqualTo((1L << 20) | (1L << 21));
  }

  @Test
  @DisplayName(
      "a table that drops a tag moves every later flag down one bit, the dropped one has none")
  void aDroppedTagMovesTheLaterBits() throws IOException {
    EntityFlags configured = EntityFlags.of(GameData.tables());
    EntityFlags shifted = EntityFlags.of(withoutTag(folder, "HAS_CAPTURE"));
    assertThat(shifted.hasCapture()).isZero();
    assertThat(shifted.noSpecialAttack())
        .as("before the dropped row")
        .isEqualTo(configured.noSpecialAttack());
    assertThat(shifted.noMove()).isEqualTo(configured.noMove());
    assertThat(shifted.forceIsGround()).isEqualTo(configured.forceIsGround() >>> 1);
    assertThat(shifted.noDamage()).isEqualTo(1L << 40);
    assertThat(shifted.untargetable()).isEqualTo(1L << 50);
    assertThat(shifted.noMoveAllowAttract()).isEqualTo(1L << 56);
  }

  @Test
  @DisplayName("no flag has a bit under NONE")
  void noneHasNoBits() {
    assertThat(EntityFlags.NONE.untargetable()).isZero();
    assertThat(EntityFlags.NONE.noMove()).isZero();
    assertThat(EntityFlags.NONE.keepsTargetingOff()).isZero();
  }

  /** The configured tables copied into a folder, one game tag dropped and the later ones moved. */
  private static GameTables withoutTag(Path folder, String tag) throws IOException {
    Path source = GameTables.configuredDirectory().orElseThrow();
    try (Stream<Path> files = Files.list(source)) {
      for (Path file : files.toList()) {
        Files.copy(file, folder.resolve(file.getFileName()));
      }
    }
    Path file = folder.resolve("game_tags.json");
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode document = (ObjectNode) mapper.readTree(file.toFile());
    ObjectNode rows = (ObjectNode) document.get("rows");
    int dropped = rows.get(tag).get("index").asInt();
    rows.remove(tag);
    for (Map.Entry<String, JsonNode> entry : rows.properties()) {
      int index = entry.getValue().get("index").asInt();
      if (index > dropped) {
        ((ObjectNode) entry.getValue()).put("index", index - 1);
      }
    }
    mapper.writeValue(file.toFile(), document);
    return GameTables.load(folder);
  }
}
