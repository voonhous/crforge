package org.crforge.core.battle.action;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.pathfinding.combat.PackedLevel;
import org.crforge.core.pathfinding.combat.RarityTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A damage type's pipeline, as far as the battle models it: a target that takes no damage takes
 * none, the four stages run behind their switches, the level scaling scales the amount by the row
 * and level it is given, and with no buffs the protection and the multiplier only floor the amount
 * at zero and the on-hit damage adds nothing.
 */
class DamageTypeTest {

  /** A Common card at level 11, as a source carries it. */
  private static final int LEVEL_ELEVEN = PackedLevel.fromLevel(11, RarityTable.COMMON);

  private static DamageType unscaled() {
    return DamageType.builder().name("D").enableLevelScaling(false).build();
  }

  /** A type with only the level scaling on, so the stage's own result comes through. */
  private static DamageType scalingOnly() {
    return DamageType.builder()
        .name("D")
        .enableProtection(false)
        .enableDamageMultiplier(false)
        .enableDamageOnHit(false)
        .build();
  }

  @Test
  @DisplayName("every switch is on by default, the damage id included")
  void defaults() {
    DamageType type = DamageType.builder().name("D").build();
    assertThat(type.enableLevelScaling()).isTrue();
    assertThat(type.enableProtection()).isTrue();
    assertThat(type.enableDamageMultiplier()).isTrue();
    assertThat(type.enableDamageOnHit()).isTrue();
    assertThat(type.acquireDamageId()).isTrue();
  }

  @Test
  @DisplayName("a target that takes no damage takes none; with no buffs the amount passes")
  void pipeline() {
    RarityTable common = RarityTable.COMMON;
    assertThat(unscaled().pipeline(120, true, true, common, LEVEL_ELEVEN)).isZero();
    assertThat(unscaled().pipeline(120, false, true, common, LEVEL_ELEVEN)).isEqualTo(120);
    assertThat(unscaled().pipeline(120, false, false, common, LEVEL_ELEVEN)).isEqualTo(120);
    assertThat(unscaled().pipeline(-5, false, true, common, LEVEL_ELEVEN))
        .as("floored at zero")
        .isZero();
    DamageType bare =
        unscaled().toBuilder().enableProtection(false).enableDamageMultiplier(false).build();
    assertThat(bare.pipeline(-5, false, true, common, LEVEL_ELEVEN))
        .as("nothing floors it")
        .isEqualTo(-5);
    DamageType multiplierOnly = unscaled().toBuilder().enableProtection(false).build();
    assertThat(multiplierOnly.pipeline(-5, false, true, common, LEVEL_ELEVEN))
        .as("the multiplier floors it with a source object")
        .isZero();
    assertThat(multiplierOnly.pipeline(-5, false, false, common, LEVEL_ELEVEN))
        .as("and is skipped without one")
        .isEqualTo(-5);
  }

  @Test
  @DisplayName("every recorded case of the level scaling gets its recorded amount")
  void everyRecordedLevelScalingCase() throws IOException {
    JsonNode root;
    try (InputStream in = getClass().getResourceAsStream("/battle/typed_hit_scaling.json")) {
      root = new ObjectMapper().readTree(in);
    }
    Map<String, RarityTable> rows = new HashMap<>();
    root.get("rarities")
        .fields()
        .forEachRemaining(
            e -> {
              List<Integer> multipliers = new ArrayList<>();
              e.getValue().get(1).forEach(m -> multipliers.add(m.asInt()));
              rows.put(
                  e.getKey(),
                  new RarityTable(
                      e.getKey(), e.getValue().get(0).asInt(), multipliers.size(), multipliers));
            });
    JsonNode cases = root.get("cases");
    assertThat(cases).hasSize(165);
    for (int i = 0; i < cases.size(); i++) {
      JsonNode c = cases.get(i);
      DamageType type = scalingOnly().toBuilder().enableLevelScaling(c.get(0).asInt() == 1).build();
      RarityTable rarity = c.get(1).isNull() ? null : rows.get(c.get(1).asText());
      assertThat(type.pipeline(c.get(3).asInt(), false, true, rarity, c.get(2).asInt()))
          .as("case %d", i)
          .isEqualTo(c.get(4).asInt());
    }
  }

  @Test
  @DisplayName("the shipped deal-damage rows at level 11 from a Common source")
  void shippedRowsAtLevelEleven() {
    int[][] cases = {{170, 435}, {340, 870}, {123, 314}, {76, 194}, {25, 64}};
    DamageType type = DamageType.builder().name("D").build();
    for (int[] c : cases) {
      assertThat(type.pipeline(c[0], false, true, RarityTable.COMMON, LEVEL_ELEVEN))
          .as("%d at level 11", c[0])
          .isEqualTo(c[1]);
    }
    assertThat(type.pipeline(76, false, true, RarityTable.COMMON, 0))
        .as("the first level leaves the amount as it is")
        .isEqualTo(76);
    assertThat(type.pipeline(76, false, true, null, LEVEL_ELEVEN))
        .as("no rarity row leaves it too")
        .isEqualTo(76);
  }
}
