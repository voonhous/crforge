package org.crforge.core.battle.spawn;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.unit.UnitData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the perform of a character spawn row to 1000 recorded cases: which object is the source,
 * where the two classes place the spawn and how the side flips it, what the spawner is handed, and
 * the calls the perform makes on the children after the spawn.
 */
class SpawnPerformTest {

  private static final UnitData CONFIGURATION = UnitData.builder().name("C1").build();
  private static final UnitData CLONE = UnitData.builder().name("clone").build();

  private static final BattleAction ON_SPAWN =
      new BattleAction() {
        @Override
        public String name() {
          return "ONSPAWN";
        }

        @Override
        public ActionInstance start(ActionHolder holder) {
          return null;
        }
      };

  /** A game object as the recorded case describes it. */
  private record TestObject(
      int x,
      int y,
      int side,
      int kind,
      boolean isCharacter,
      int characterPrestige,
      int areaEffectPrestige)
      implements SpawnObject {

    static TestObject of(JsonNode o) {
      return new TestObject(
          o.get(0).asInt(),
          o.get(1).asInt(),
          o.get(2).asInt(),
          o.get(3).asInt(),
          o.get(4).asInt() == 1,
          o.get(5).asInt(),
          o.get(6).asInt());
    }

    @Override
    public int prestige() {
      return kind == BattleEntity.KIND_CHARACTER ? characterPrestige : areaEffectPrestige;
    }

    @Override
    public int packedLevel() {
      return 0;
    }
  }

  private static int column(JsonNode columns, String name) {
    return columns.path(name).asInt();
  }

  private static boolean flag(JsonNode columns, String name) {
    return columns.path(name).asBoolean();
  }

  private static SpawnRow row(JsonNode c, JsonNode expression) {
    JsonNode columns = c.get("columns");
    SpawnRow.SpawnRowBuilder row =
        SpawnRow.builder()
            .toLocation(c.get("cls").asText().equals("ActionSpawnToLocation"))
            .spawnData(CONFIGURATION)
            .spawnDataClone(c.get("clone").asInt() == 1 ? CLONE : null)
            .useDeploy(flag(columns, "UseDeploy"))
            .isEnemy(flag(columns, "IsEnemy"))
            .isSpawnConstPriority(flag(columns, "IsSpawnConstPriority"))
            .spawnPushback(flag(columns, "SpawnPushback"))
            .ignoreEffects(flag(columns, "IgnoreEffects"))
            .useMorph(flag(columns, "UseMorph"))
            .addToSourceGroup(flag(columns, "AddToSourceGroup"))
            .spawnAsClone(flag(columns, "SpawnAsClone"))
            .inheritPrestigeFromParent(flag(columns, "InheritPrestigeFromParent"))
            .parentGoAsSource(flag(columns, "ParentGOAsSource"))
            .shareContext(flag(columns, "ShareContext"))
            .validatePlacementAsBuilding(flag(columns, "ValidatePlacementAsBuilding"))
            .spawnRadius(column(columns, "SpawnRadius"))
            .deployTimeMs(column(columns, "DeployTime"))
            .absoluteX(column(columns, "AbsoluteX"))
            .absoluteY(column(columns, "AbsoluteY"))
            .relativeX(column(columns, "RelativeX"))
            .relativeY(column(columns, "RelativeY"))
            .mirroredX(column(columns, "MirroredX"))
            .mirroredY(column(columns, "MirroredY"));
    if (columns.has("Count")) {
      row.count(column(columns, "Count"));
    }
    if (columns.has("SpawnLevelIndex")) {
      row.spawnLevelIndex(column(columns, "SpawnLevelIndex"));
    }
    if (columns.has("IsDeathSpawn")) {
      row.isDeathSpawn(flag(columns, "IsDeathSpawn"));
    }
    if (columns.has("ActionToRunOnSpawned")) {
      row.actionToRunOnSpawned(ON_SPAWN);
    }
    int exprX = expression.get(0).asInt();
    int exprY = expression.get(1).asInt();
    if (columns.has("XPositionExpression")) {
      row.xPositionExpression(() -> exprX);
    }
    if (columns.has("YPositionExpression")) {
      row.yPositionExpression(() -> exprY);
    }
    return row.build();
  }

  @Test
  @DisplayName("every recorded case hands the spawner its recorded block and makes its calls after")
  void everyRecordedCase() throws IOException {
    JsonNode cases;
    try (InputStream in = getClass().getResourceAsStream("/battle/spawn_perform.json")) {
      cases = new ObjectMapper().readTree(in).get("cases");
    }
    assertThat(cases).hasSize(1000);
    for (int i = 0; i < cases.size(); i++) {
      JsonNode c = cases.get(i);
      SpawnRow row = row(c, c.get("expression"));
      TestObject owner = TestObject.of(c.get("owner"));
      TestObject instigator = TestObject.of(c.get("source"));
      boolean hasTarget = c.get("target").asInt() == 1;
      JsonNode placed = c.get("placed");
      List<int[]> searched = new ArrayList<>();
      SpawnArguments args =
          SpawnPerform.arguments(
              row,
              owner,
              instigator,
              hasTarget,
              c.get("two_v_two").asInt() == 1,
              (x, y) -> {
                searched.add(new int[] {x, y});
                return new int[] {placed.get(0).asInt(), placed.get(1).asInt()};
              });
      JsonNode block = c.get("block");
      String where = "case " + i;
      assertThat(args.configuration().name())
          .as("%s configuration", where)
          .isEqualTo(block.get(0).asText());
      assertThat(new int[] {args.x(), args.y()})
          .as("%s position", where)
          .containsExactly(block.get(1).asInt(), block.get(2).asInt());
      assertThat(args.count()).as("%s count", where).isEqualTo(block.get(3).asInt());
      assertThat(args.source())
          .as("%s source", where)
          .isSameAs(block.get(4).asText().equals("owner") ? owner : instigator);
      // The rest of the block, in the fixture's order.
      int[] want = new int[13];
      for (int k = 0; k < want.length; k++) {
        want[k] = block.get(k + 5).asInt();
      }
      int[] got = {
        args.noOffset() ? 1 : 0,
        args.radius(),
        args.deployTimeMs(),
        args.morph() ? 1 : 0,
        args.constPriority() ? 1 : 0,
        args.deathSpawn() ? 1 : 0,
        args.ignoreEffects() ? 1 : 0,
        args.enemy() ? 1 : 0,
        args.level(),
        args.useDeploy() ? 1 : 0,
        args.action() == null ? 0 : 1,
        args.spawnPushback() ? 1 : 0,
        args.prestige()
      };
      assertThat(got).as("%s block", where).containsExactly(want);
      assertThat(searched)
          .as("%s the placement search asked", where)
          .hasSize(row.validatePlacementAsBuilding() ? 1 : 0);

      List<String> after = new ArrayList<>();
      for (JsonNode call : c.get("after")) {
        after.add(call.get(0).asText() + " " + call.get(1).asInt());
      }
      // The champion test reads the unit actually spawned; the clone rows here are not champions.
      boolean champion = c.get("champion").asInt() == 1 && args.configuration() == CONFIGURATION;
      List<String> calls =
          SpawnPerform.afterSpawn(
                  row, args.source(), hasTarget, champion, c.get("children").asInt())
              .stream()
              .map(call -> call.kind().label() + " " + call.child())
              .toList();
      assertThat(calls).as("%s calls after the spawn", where).containsExactlyElementsOf(after);
    }
  }

  @Test
  @DisplayName("a relative location moves the spawn half a tile per unit, flipped for the top side")
  void relativeLocation() {
    SpawnRow row =
        SpawnRow.builder().toLocation(true).spawnData(CONFIGURATION).relativeX(-1).build();
    TestObject bottom = new TestObject(3500, 21500, 0, 3, false, 0, 0);
    TestObject top = new TestObject(14500, 10500, 1, 3, false, 0, 0);
    assertThat(SpawnPerform.arguments(row, bottom, bottom, false, false, null).x()).isEqualTo(4000);
    assertThat(SpawnPerform.arguments(row, top, top, false, false, null).x()).isEqualTo(14000);
    SpawnRow down = row.toBuilder().relativeX(0).relativeY(1).build();
    assertThat(SpawnPerform.arguments(down, bottom, bottom, false, false, null).y())
        .isEqualTo(22000);
    assertThat(SpawnPerform.arguments(down, top, top, false, false, null).y()).isEqualTo(10000);
  }
}
