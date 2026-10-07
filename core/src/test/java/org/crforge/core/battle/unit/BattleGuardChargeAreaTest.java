package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.data.GameVersions;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.move.MovementState;
import org.crforge.core.pathfinding.target.TargetView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Little Prince's guard on data version 16.402.18. The guard spawn row of that version names an
 * area effect, ChampionGuardCleave, and its game's guard run reads none of the row's push columns:
 * on the step that starts the charge it makes the area effect at the guard's point, the guard its
 * source and the object it follows, and ends it as the run finishes. What the charge pushes and
 * hits is the area effect's: a filter form row that pushes every enemy ground character it lists to
 * the edge of its 2500 circle on every update and hits each once.
 *
 * <p>The scene: the configured tables relabelled as data version 16.402.18, with the guard spawn
 * row and the area effect as that version writes them. The guard's area effect,
 * DummySpawnLittlePrinceGuard, is placed for the bottom side; its start makes the guard 850 ms
 * later, which deploys for 300 ms and charges 4000 up the lane past a top side Knight walking down
 * toward it.
 */
class BattleGuardChargeAreaTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The tick the guard's area effect is placed on. */
  private static final int PLACED = 30;

  /** The area effect's point: the guard appears 2000 behind it and charges to 4000 ahead. */
  private static final int X = 3500;

  private static final int Y = 12000;

  /** The tick the scene runs to, well after the charge has ended. */
  private static final int END = 120;

  /** The guard's charge area effect, as data version 16.402.18 writes it. */
  private static final String CLEAVE = "ChampionGuardCleave";

  @TempDir Path folder;

  /**
   * The configured tables, relabelled as data version 16.402.18, with the guard spawn row naming
   * the charge's area effect and that area effect added, as that version writes them.
   */
  private GameTables guardTables() throws IOException {
    GameData.altered(
        folder,
        "actions",
        actions -> {
          ObjectNode fields = (ObjectNode) actions.get("Spawn_ChampionGuardCharge").get("fields");
          fields.put("SpawnAEO", CLEAVE);
          fields.put("PushBackDamage", 125);
        });
    ObjectMapper mapper = new ObjectMapper();
    Path areas = folder.resolve("area_effect_objects.json");
    ObjectNode document = (ObjectNode) mapper.readTree(areas.toFile());
    ObjectNode rows = (ObjectNode) document.get("rows");
    int index = 0;
    for (Iterator<JsonNode> it = rows.elements(); it.hasNext(); ) {
      index = Math.max(index, it.next().path("index").asInt() + 1);
    }
    ObjectNode cleave = rows.putObject(CLEAVE);
    cleave.put("index", index);
    cleave.put("class", "LogicAreaEffectObjectData");
    ObjectNode columns = cleave.putObject("columns");
    columns.put("Name", CLEAVE);
    columns.put("Rarity", "Common");
    columns.put("LifeDuration", 99999);
    columns.put("HitSpeed", 50);
    columns.put("Radius", 2500);
    columns.put("Filter", "PassiveForcedHitGroundCharacters");
    columns.put("FollowBehaviour", "FollowParent");
    columns.putObject("Damage").put("BaseDamage", 125);
    columns.put("OneHitPerTarget", true);
    columns.put("Pushback", 2500);
    columns.put("PushbackAll", true);
    columns.put("RelativePushback", true);
    columns.put("ContinuousPushback", true);
    mapper.writeValue(areas.toFile(), document);
    try (Stream<Path> files = Files.list(folder)) {
      for (Path file : files.toList()) {
        if (file.getFileName().toString().endsWith(".json")) {
          ObjectNode table = (ObjectNode) mapper.readTree(file.toFile());
          table.put("version", GameVersions.DATA_16_402_18);
          mapper.writeValue(file.toFile(), table);
        }
      }
    }
    return GameTables.load(folder);
  }

  /** What the scene saw. */
  private static final class Scene {
    final Standard1v1Battle match;
    CharacterEntity guard;
    CharacterEntity knight;
    AreaEffectEntity cleave;
    int cleaveMade = -1;
    int chargeStarted = -1;
    int runDone = -1;
    int[] guardAtCleaveMade;

    /**
     * The battle's counter after each step that left the cleave listed or queued, and where it and
     * the guard stood.
     */
    final Map<Integer, int[]> listed = new LinkedHashMap<>();

    /** Every typed hit the Knight took: the tick and the amount. */
    final List<String> knightTyped = new ArrayList<>();

    /** The ticks of every direct hit the Knight took. */
    final List<Integer> knightDirect = new ArrayList<>();

    /** The ticks the Knight was asked to be pushed on. */
    final List<Integer> knightPushes = new ArrayList<>();

    Scene(GameTables tables) {
      match = new Standard1v1Battle(tables, LEVEL, false);
      knight =
          match.deploy(0, match.getWorld().getRecords().unit("Knight"), LEVEL, 1, X, 17500, "K");
      match.placeAreaEffect(PLACED, "DummySpawnLittlePrinceGuard", LEVEL, 0, X, Y, "dummy");
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void guardRegistered(int tick, CharacterEntity made) {
                  guard = made;
                }

                @Override
                public void dashStarted(
                    int tick,
                    CharacterEntity unit,
                    TargetView reference,
                    int fromX,
                    int fromY,
                    int aimX,
                    int aimY) {
                  if (unit == guard) {
                    chargeStarted = tick;
                  }
                }

                @Override
                public void areaEffectCreated(
                    int tick, AreaEffectEntity areaEffect, String how, String source) {
                  if (areaEffect.getData().name().equals(CLEAVE)) {
                    cleave = areaEffect;
                    cleaveMade = tick;
                    guardAtCleaveMade =
                        new int[] {
                          guard.getView().getX(),
                          guard.getView().getY(),
                          areaEffect.getX(),
                          areaEffect.getY()
                        };
                  }
                }

                @Override
                public void guardStepped(
                    int tick,
                    CharacterEntity unit,
                    boolean charging,
                    long tags,
                    boolean done,
                    List<String> calls) {
                  if (done) {
                    runDone = tick;
                  }
                }

                @Override
                public void typedHitDealt(
                    int tick,
                    WorldEntity source,
                    WorldEntity target,
                    int amount,
                    int damageId,
                    DamageResult result) {
                  if (target == knight) {
                    knightTyped.add(tick + " " + amount);
                  }
                }

                @Override
                public void damageDealt(
                    int tick, WorldEntity target, int damage, DamageResult result) {
                  if (target == knight) {
                    knightDirect.add(tick);
                  }
                }

                @Override
                public void pushbackRequested(
                    int tick,
                    WorldEntity unit,
                    boolean started,
                    int fromX,
                    int fromY,
                    MovementState pushback) {
                  if (unit == knight) {
                    knightPushes.add(tick);
                  }
                }
              });
      while (match.getBattle().getTick() < END) {
        match.getBattle().step();
        if (cleave != null && match.getWorld().liveOrQueued(cleave.getId()) != null) {
          listed.put(
              match.getBattle().getTick(),
              new int[] {
                cleave.getX(), cleave.getY(), guard.getView().getX(), guard.getView().getY()
              });
        }
      }
    }
  }

  @Test
  @DisplayName(
      "on data version 16.402.18 the guard's charge makes its row's area effect at the guard's"
          + " point on the step the charge starts, which stands on the guard until the run ends")
  void theChargeMakesTheAreaEffect() throws IOException {
    Scene scene = new Scene(guardTables());

    assertThat(scene.guard).as("the guard").isNotNull();
    assertThat(scene.chargeStarted).as("the charge").isPositive();
    assertThat(scene.cleaveMade).as("made on the charge's step").isEqualTo(scene.chargeStarted);
    assertThat(scene.cleave.side()).isZero();
    // Made before the charge moves the guard: at the point it deployed on, 2000 behind Y.
    assertThat(scene.guardAtCleaveMade[2]).isEqualTo(scene.guardAtCleaveMade[0]);
    assertThat(scene.guardAtCleaveMade[3]).isEqualTo(scene.guardAtCleaveMade[1]);
    // It follows the guard: wherever the guard stands after a step, it stands there too.
    for (Map.Entry<Integer, int[]> entry : scene.listed.entrySet()) {
      int[] at = entry.getValue();
      assertThat(new int[] {at[0], at[1]})
          .as("on the guard after tick %d", entry.getKey())
          .containsExactly(at[2], at[3]);
    }
    // Listed from the step that made it; the run's finish ends it, and the cleanup of that step
    // removes it. (The battle's counter after a step is that step's tick plus one.)
    assertThat(scene.runDone).as("the run's finish").isGreaterThan(scene.chargeStarted);
    assertThat(scene.listed.keySet()).first().isEqualTo(scene.cleaveMade + 1);
    assertThat(scene.listed.keySet()).last().isEqualTo(scene.runDone);
    assertThat(scene.listed).hasSize(scene.runDone - scene.cleaveMade);
    assertThat(scene.guard.getView().getState()).isNotEqualTo(GridEntityState.DASHING);
  }

  @Test
  @DisplayName(
      "on data version 16.402.18 the guard's run neither pushes nor hits on its own: its area"
          + " effect hits the Knight once and pushes it on every update while it is reached")
  void theAreaEffectPushesAndHits() throws IOException {
    Scene scene = new Scene(guardTables());

    // 125 at the guard's level, as the 14.593.1 guard's own 100 is 256 there.
    assertThat(scene.knightTyped).as("one typed hit, the area effect's").hasSize(1);
    int hitTick = Integer.parseInt(scene.knightTyped.get(0).split(" ")[0]);
    assertThat(scene.knightTyped.get(0)).endsWith(" 320");
    assertThat(hitTick).isBetween(scene.cleaveMade, scene.runDone);
    // No hit of the guard's own during the charge: only its attacks once it fights afterwards.
    assertThat(scene.knightDirect).allMatch(tick -> tick > scene.runDone);
    assertThat(scene.knightPushes).as("pushed again while reached").hasSizeGreaterThan(1);
    assertThat(scene.knightPushes.get(0)).isGreaterThanOrEqualTo(scene.cleaveMade);
  }
}
