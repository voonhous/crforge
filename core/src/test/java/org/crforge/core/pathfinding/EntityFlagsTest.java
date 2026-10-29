/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.pathfinding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTable;
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
    assertThat(bits.dashing()).isEqualTo(tagBit("DASHING"));
    assertThat(bits.charging()).isEqualTo(tagBit("CHARGING"));
    assertThat(bits.attacking()).isEqualTo(tagBit("ATTACKING"));
    assertThat(bits.noMove()).isEqualTo(tagBit("NO_MOVE"));
    assertThat(bits.noDash()).isEqualTo(tagBit("NO_DASH"));
    assertThat(bits.noAttack()).isEqualTo(tagBit("NO_ATTACK"));
    assertThat(bits.lockTarget()).isEqualTo(tagBit("LOCK_TARGET"));
    assertThat(bits.noSpawnTimer()).isEqualTo(tagBit("NO_SPAWNTIMER"));
    assertThat(bits.noCheckCollisions()).isEqualTo(tagBit("NO_CHECKCOLLISIONS"));
    assertThat(bits.noCheckAvoidance()).isEqualTo(tagBit("NO_CHECKAVOIDANCE"));
    assertThat(bits.noBuffs()).isEqualTo(tagBit("NO_BUFFS"));
    assertThat(bits.noPushedByEnemy()).isEqualTo(tagBit("NO_PUSHED_BY_ENEMY"));
    assertThat(bits.inactive()).isEqualTo(tagBit("INACTIVE"));
    assertThat(bits.activating()).isEqualTo(tagBit("ACTIVATING"));
    assertThat(bits.hasShield()).isEqualTo(tagBit("HAS_SHIELD"));
    assertThat(bits.hidden()).isEqualTo(tagBit("HIDDEN"));
    assertThat(bits.abilityDisabled()).isEqualTo(tagBit("ABILITY_DISABLED"));
    assertThat(bits.buildingDeathSpawnFindLocation())
        .isEqualTo(tagBit("BUILDING_DEATH_SPAWN_FIND_LOCATION"));
    assertThat(bits.abilityPostponed()).isEqualTo(tagBit("ABILITY_POSTPONED"));
    assertThat(bits.noSummon()).isEqualTo(tagBit("NO_SUMMON"));
    assertThat(bits.noSpecialAttack()).isEqualTo(tagBit("NO_SPECIAL_ATTACK"));
    assertThat(bits.hasCapture()).isEqualTo(tagBit("HAS_CAPTURE"));
    assertThat(bits.forceIsGround()).isEqualTo(tagBit("FORCE_IS_GROUND"));
    assertThat(bits.noPushback()).isEqualTo(tagBit("NO_PUSHBACK"));
    assertThat(bits.noDamage()).isEqualTo(tagBit("NO_DAMAGE"));
    assertThat(bits.noGiantbufferChefEnchantment())
        .isEqualTo(tagBit("NO_GIANTBUFFER_CHEF_ENCHANTMENT"));
    assertThat(bits.forceIsAir()).isEqualTo(tagBit("FORCE_IS_AIR"));
    assertThat(bits.captured()).isEqualTo(tagBit("CAPTURED"));
    assertThat(bits.disablePhysical())
        .isEqualTo(tagBit("DISABLE_PHYSICAL_INTERACTIONS_WITH_OBJECTS"));
    assertThat(bits.castingAbility()).isEqualTo(tagBit("CASTING_ABILITY"));
    assertThat(bits.untargetable()).isEqualTo(tagBit("UNTARGETABLE"));
    assertThat(bits.noPushedByAlly()).isEqualTo(tagBit("NO_PUSHED_BY_ALLY"));
    assertThat(bits.avoidanceAsObstacle()).isEqualTo(tagBit("AVOIDANCE_AS_OBSTACLE"));
    assertThat(bits.abilityCooldownPaused()).isEqualTo(tagBit("ABILITY_COOLDOWN_PAUSED"));
    assertThat(bits.warp()).isEqualTo(tagBit("WARP"));
    assertThat(bits.noClone()).isEqualTo(tagBit("NO_CLONE"));
    assertThat(bits.noMoveAllowAttract()).isEqualTo(tagBit("NO_MOVE_ALLOW_ATTRACT"));
    assertThat(bits.keepsTargetingOff()).isEqualTo(tagBit("INACTIVE") | tagBit("ACTIVATING"));
  }

  @Test
  @DisplayName(
      "a table that drops a tag no flag names moves every later flag down one bit and keeps the"
          + " earlier ones")
  void aDroppedTagMovesTheLaterBits() throws IOException {
    // The first tag of the table that no flag names, with a named tag before and after it.
    GameTable tags = GameData.tables().table("game_tags");
    String dropped = null;
    for (GameRow row : tags.rows()) {
      if (!EntityFlags.TAGS.contains(row.name())
          && row.index() > tags.row("DASHING").index()
          && row.index() < tags.row("UNTARGETABLE").index()) {
        dropped = row.name();
        break;
      }
    }
    assertThat(dropped).as("an unnamed tag between two named ones").isNotNull();
    EntityFlags configured = EntityFlags.of(GameData.tables());
    EntityFlags shifted = EntityFlags.of(withoutTag(folder, dropped));
    assertThat(shifted.dashing()).as("before the dropped row").isEqualTo(configured.dashing());
    assertThat(shifted.untargetable()).isEqualTo(bitWithout("UNTARGETABLE", dropped));
    GameTable shiftedTags = withoutTag(folder.resolve("again"), dropped).table("game_tags");
    for (String tag : EntityFlags.TAGS) {
      assertThat(EntityFlags.bit(shiftedTags, tag)).as(tag).isEqualTo(bitWithout(tag, dropped));
    }
  }

  @Test
  @DisplayName("a table that drops a tag a flag names is refused, naming the tag")
  void aDroppedNamedTagIsRefused() throws IOException {
    GameTables tables = withoutTag(folder, "HAS_CAPTURE");
    assertThatThrownBy(() -> EntityFlags.of(tables))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("HAS_CAPTURE");
  }

  @Test
  @DisplayName("every tag a flag names is a row of the configured game tags table")
  void everyNamedTagIsListed() {
    GameTable tags = GameData.tables().table("game_tags");
    assertThat(EntityFlags.TAGS).allMatch(tags::has);
  }

  @Test
  @DisplayName("no flag has a bit under NONE")
  void noneHasNoBits() {
    assertThat(EntityFlags.NONE.untargetable()).isZero();
    assertThat(EntityFlags.NONE.noMove()).isZero();
    assertThat(EntityFlags.NONE.keepsTargetingOff()).isZero();
  }

  /**
   * A tag's bit as the configured game tags table numbers it: one shifted by its row's index, read
   * from the table's own rows; 0 for a tag the table leaves out.
   */
  private static long tagBit(String tag) {
    GameTable tags = GameData.tables().table("game_tags");
    return tags.has(tag) ? 1L << Shipped.row("game_tags", tag).index() : 0;
  }

  /**
   * A tag's bit once another tag is dropped: one index lower when it came after the dropped one.
   */
  private static long bitWithout(String tag, String dropped) {
    int index = Shipped.row("game_tags", tag).index();
    return 1L << (index > Shipped.row("game_tags", dropped).index() ? index - 1 : index);
  }

  /** The configured tables copied into a folder, one game tag dropped and the later ones moved. */
  private static GameTables withoutTag(Path folder, String tag) throws IOException {
    Files.createDirectories(folder);
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
