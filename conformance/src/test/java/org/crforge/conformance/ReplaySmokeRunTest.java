package org.crforge.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.replay.ContentFields;
import org.crforge.core.battle.replay.ScenarioItems;
import org.crforge.core.battle.replay.ScenarioShape;
import org.crforge.core.battle.replay.Scenarios;
import org.crforge.core.pathfinding.combat.LevelScaling;
import org.crforge.core.pathfinding.combat.PackedLevel;
import org.crforge.core.pathfinding.combat.RarityTable;
import org.crforge.core.pathfinding.combat.ScalingGlobals;
import org.crforge.core.pathfinding.combat.ScalingMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplaySmokeRunTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path folder;

  private Path tablesFolder;
  private GameTables tables;
  private Path identity;

  private Path terminalIdentity;

  /** The tables' documents a test has written columns into, by table name. */
  private final Map<String, ObjectNode> written = new LinkedHashMap<>();

  @BeforeEach
  void writeTheIdentities() throws IOException {
    useTables(GameTables.configuredDirectory().orElseThrow());
  }

  /** Runs the test on a folder of tables: reads them and writes the identities of their content. */
  private void useTables(Path tablesIn) throws IOException {
    tablesFolder = tablesIn;
    tables = GameTables.load(tablesFolder);
    identity = identity("identity.json", SmokeSchema.V1.id(), SmokeSchema.V1.observationScope());
    terminalIdentity =
        identity("identity-v2.json", SmokeSchema.V2.id(), SmokeSchema.V2.observationScope());
  }

  private Path identity(String name, String schema, String scope) throws IOException {
    GameTables tables = GameTables.load(tablesFolder);
    Path file = folder.resolve(name);
    ObjectNode fields = MAPPER.createObjectNode();
    fields.put("schema", schema);
    fields.put("observation_scope", scope);
    fields.put(ContentFields.CONTENT_VERSION, tables.version());
    fields.put(ContentFields.CONTENT_SHA, tables.contentSha());
    MAPPER.writeValue(file.toFile(), fields);
    return file;
  }

  /** A table's document, read once from the tables in use, for a test to write columns into. */
  private ObjectNode document(String table) {
    return written.computeIfAbsent(
        table,
        name -> {
          try {
            return (ObjectNode) MAPPER.readTree(tablesFolder.resolve(name + ".json").toFile());
          } catch (IOException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  /** A table's rows by name, to write into. */
  private ObjectNode rowsOf(String table) {
    return (ObjectNode) document(table).get("rows");
  }

  /** The columns of a table's row, to write into. */
  private ObjectNode columns(String table, String row) {
    return (ObjectNode) rowsOf(table).get(row).get("columns");
  }

  /** The fields of an action, to write into. */
  private ObjectNode fields(String action) {
    return (ObjectNode) document("actions").get("actions").get(action).get("fields");
  }

  /** Writes the place of each of a spawn group's objects, in cells of 500: x, then y, in turn. */
  private void place(String group, int... cells) {
    JsonNode objects = columns("spawn_groups", group).get("Objects");
    for (int i = 0; i < cells.length / 2; i++) {
      ((ObjectNode) objects.get(i)).put("x", cells[2 * i]).put("y", cells[2 * i + 1]);
    }
  }

  /**
   * Runs the rest of the test on the tables with what it wrote: the tables in use copied into the
   * test's folder, each written document in place of its file, and the identities written again for
   * the copy.
   */
  private void useWrittenTables() throws IOException {
    Path copy = folder.resolve("tables");
    Files.createDirectory(copy);
    try (Stream<Path> files = Files.list(tablesFolder)) {
      for (Path file : files.toList()) {
        String name = file.getFileName().toString();
        ObjectNode document = written.get(name.replaceFirst("\\.json$", ""));
        if (document != null) {
          MAPPER.writeValue(copy.resolve(name).toFile(), document);
        } else {
          Files.copy(file, copy.resolve(name));
        }
      }
    }
    useTables(copy);
  }

  /**
   * Runs the rest of the test on the costs and the elixir rate the scenarios' play ticks were
   * planned against ({@link Scenarios#writePlannedColumns}), so each play is given when its elixir
   * is there whatever the configured tables hold.
   */
  private void usePlannedSchedule() throws IOException {
    Scenarios.writePlannedColumns(this::rowsOf);
    useWrittenTables();
  }

  /** A scenario with its plays' items fitted to the tables: the costs and levels of their rows. */
  private ObjectNode fit(ObjectNode scenario) {
    return ScenarioItems.fitted(scenario, tables);
  }

  /** A unit's row as the tables write it: a character's, else a building's. */
  private GameRow unitRow(String name) {
    return tables.table("characters").has(name)
        ? tables.table("characters").row(name)
        : tables.table("buildings").row(name);
  }

  /** The published rarity of a row's Rarity column, Common when it names none. */
  private static RarityTable rarity(GameRow row) {
    JsonNode name = row.columns().get("Rarity");
    String rarity = name == null || name.isNull() ? "Common" : name.asText();
    return RarityTable.PUBLISHED.stream()
        .filter(table -> table.name().equals(rarity))
        .findFirst()
        .orElseThrow();
  }

  private static boolean flag(GameRow row, String column) {
    JsonNode value = row.columns().get(column);
    return value != null && value.asBoolean();
  }

  /**
   * A unit row's Hitpoints at a level counted from 1, scaled by the level scaling's published rule
   * for the row's mode: the king tower's for a summoner, a princess tower's for a summoner tower,
   * else a card's.
   */
  private int hitpointsAt(String unit, int level) {
    GameRow row = unitRow(unit);
    return LevelScaling.hitpoints(
        ScalingGlobals.standard(),
        ScenarioItems.number(row, "Hitpoints"),
        PackedLevel.fromLevel(level, rarity(row)),
        rarity(row),
        flag(row, "IsSummoner"),
        flag(row, "IsSummonerTower"));
  }

  /**
   * The damage of a unit's Projectile fired at a level counted from 1: the projectile row's Damage
   * at the launcher's level re-based on the projectile's own rarity, by its DamageScalingMode.
   */
  private int projectileDamageAt(String unit, int level) {
    GameRow launcher = unitRow(unit);
    GameRow projectile =
        tables.table("projectiles").row(launcher.columns().get("Projectile").asText());
    JsonNode mode = projectile.columns().get("DamageScalingMode");
    ScalingMode scaling =
        mode == null || mode.isNull()
            ? ScalingMode.CARD_DAMAGE
            : switch (mode.asText()) {
              case "KingTower" -> ScalingMode.KING_DAMAGE;
              case "PrincessTower" -> ScalingMode.TOWER_DAMAGE;
              default -> ScalingMode.CARD_DAMAGE;
            };
    return LevelScaling.scale(
        ScalingGlobals.standard(),
        ScenarioItems.number(projectile, "Damage"),
        PackedLevel.pack(PackedLevel.fromLevel(level, rarity(launcher)), rarity(projectile)),
        scaling,
        rarity(projectile));
  }

  /** A card's level counted from 1 at a level index: plus its rarity's RelativeLevel plus 1. */
  private int cardLevel(String card, int levelIndex) {
    return ScenarioItems.levelField(tables, ScenarioItems.card(tables, card), levelIndex) + 1;
  }

  /** A tower selection's princess level at a level index: plus its RelativeLevel plus 1. */
  private int towerLevel(String selection, int levelIndex) {
    String name = tables.table("support_cards").row(selection).columns().get("Rarity").asText();
    return levelIndex + ScenarioItems.relativeLevel(tables, "support_rarities", name) + 1;
  }

  /** A card's cost in ten-thousandths of an elixir, as the elixir bar counts it. */
  private int costOf(String card) {
    return ScenarioItems.cost(tables, card) * 10000;
  }

  /** The packed item a scenario's command carries. */
  private static int item(ObjectNode scenario, int command) {
    return scenario.path("cmd").get(command).path("c").path("sel").path("pd").asInt();
  }

  /** A packed item's fields, by name, as a refusal names them. */
  private static String describe(int packed) {
    return "evolution field "
        + (packed & 0xf)
        + ", option field "
        + ((packed >>> 4) & 0x7)
        + ", count field "
        + ((packed >>> 7) & 0x7)
        + ", level field "
        + ((packed >>> 10) & 0x7f)
        + ", cosmetic field "
        + ((packed >>> 17) & 0x3)
        + ", slot flags field "
        + ((packed >>> 19) & 0x7)
        + ", deck index field "
        + ((packed >>> 22) & 0x3f)
        + ", cost "
        + (packed >>> 28);
  }

  @Test
  void aCompletedRunWritesItsObservationsItsManifestAndItsMarker() throws IOException {
    Path out = folder.resolve("run");

    int exit = run(fit(Scenarios.knight()), out, identity, 30);

    assertThat(exit).isEqualTo(ReplaySmokeRun.COMPLETED);
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    byte[] trace = Files.readAllBytes(out.resolve("observations.jsonl"));
    assertThat(manifest.path("status").asText()).isEqualTo("completed");
    assertThat(manifest.path("engine").asText()).isEqualTo("java");
    assertThat(manifest.path("schema").asText()).isEqualTo(SmokeSchema.V1.id());
    // The exact-horizon schema keeps its fields: no stop predicate, no termination record.
    assertThat(manifest.has("executed_ticks")).isFalse();
    assertThat(manifest.has("termination")).isFalse();
    assertThat(manifest.path("ticks").asInt()).isEqualTo(30);
    assertThat(manifest.path("observations").asInt()).isEqualTo(31);
    assertThat(manifest.path("trace_sha256").asText()).isEqualTo(sha256(trace));
    assertThat(Files.readString(out.resolve("COMPLETE"))).isEqualTo(sha256(trace) + "\n");
    assertThat(manifest.path("scenario_sha256").asText())
        .isEqualTo(sha256(Files.readAllBytes(out.resolve("scenario.json"))));
    assertThat(manifest.path("data").path("files").size()).isGreaterThan(0);

    List<String> lines = Files.readAllLines(out.resolve("observations.jsonl"));
    assertThat(lines).hasSize(31);
    JsonNode first = MAPPER.readTree(lines.get(0));
    assertThat(first.path("tick").asInt()).isZero();
    assertThat(first.path("avatar_count").asInt()).isEqualTo(2);
    assertThat(first.path("entities")).hasSize(6);
    // The towers in creation order, side 0's king first, at the towers' level.
    assertThat(first.path("entities").get(0).path("id").asInt()).isEqualTo(5000000);
    assertThat(first.path("entities").get(0).path("row").asText()).isEqualTo("KingTower");
    assertThat(first.path("entities").get(0).path("hp").asInt())
        .isEqualTo(hitpointsAt("KingTower", 1));
    assertThat(first.path("entities").get(4).path("row").asText()).isEqualTo("PrincessTower");
    assertThat(first.path("entities").get(4).path("side").asInt()).isEqualTo(1);
    // The Ladder timeline's StartingElixir, in ten-thousandths.
    GameRow timeline =
        tables
            .table("battle_timelines")
            .row(tables.table("game_modes").row("Ladder").columns().get("BattleTimeline").asText());
    assertThat(first.path("sides").get(0).path("elixir").asInt())
        .isEqualTo(ScenarioItems.number(timeline, "StartingElixir") * 10000);
    // The recorded battle's opening hand and random state: the players' data draw first.
    assertThat(first.path("sides").get(0).path("hand").toString()).isEqualTo("[7,1,0,2]");
    assertThat(first.path("sides").get(1).path("queue").toString()).isEqualTo("[0,1,4,2]");
    assertThat(first.path("rng").asLong()).isEqualTo(4153772180L);
    assertThat(MAPPER.readTree(lines.get(30)).path("tick").asInt()).isEqualTo(30);
    assertThat(first.has("stopped")).isFalse();
  }

  @Test
  void aCannoneerTowerSelectionBuildsItsSidesTowersAndTheyFireAtTheirLevel() throws IOException {
    // The first hit's tick comes from the whole scene, so the scene writes every column it
    // depends on: the Knight's walk, the Cannoneer's places, footprint, ranges and attack, and its
    // shot's flight.
    ObjectNode knight = columns("characters", "Knight");
    knight.put("Speed", 60).put("DeployTime", 1000).put("CollisionRadius", 500);
    ObjectNode tower = columns("buildings", "Cannoneer");
    tower.put("Range", 7500).put("SightRange", 7500).put("HitSpeed", 2200).put("LoadTime", 1400);
    tower.put("CollisionRadius", 1000).put("NoDeploySizeW", 11).put("NoDeploySizeH", 21);
    columns("projectiles", "CannoneerProjectile").put("Speed", 1000);
    place("King_CannonTowers", 18, 6, 7, 13, 29, 13);
    useWrittenTables();
    ObjectNode scenario = fit(Scenarios.knight());
    ((ObjectNode) scenario.path("battle").path("deck1").path("sc").get(0)).put("d", 159000001);
    Path out = folder.resolve("run");

    int exit = run(scenario, out, identity, 300);

    assertThat(exit).isEqualTo(ReplaySmokeRun.COMPLETED);
    List<String> lines = Files.readAllLines(out.resolve("observations.jsonl"));
    JsonNode first = MAPPER.readTree(lines.get(0)).path("entities");
    // Side 0 keeps the princess towers; side 1 stands its king and two Cannoneer rows in the
    // princess slots, the Epic selection's RelativeLevel above their first.
    int cannoneerLevel = towerLevel("King_CannonTowers", 0);
    assertThat(cannoneerLevel).isGreaterThan(1);
    assertThat(first.get(1).path("row").asText()).isEqualTo("PrincessTower");
    assertThat(first.get(1).path("hp").asInt()).isEqualTo(hitpointsAt("PrincessTower", 1));
    assertThat(first.get(3).path("row").asText()).isEqualTo("KingTower");
    assertThat(first.get(3).path("hp").asInt()).isEqualTo(hitpointsAt("KingTower", 1));
    JsonNode slots = tables.table("spawn_groups").row("King_CannonTowers").columns().get("Objects");
    for (int i = 4; i <= 5; i++) {
      JsonNode cannoneer = first.get(i);
      // The spawn group's slot, in cells of 500, mirrored along the arena's 64 cells for side 1.
      JsonNode slot = slots.get(i - 3);
      assertThat(cannoneer.path("row").asText()).isEqualTo("Cannoneer");
      assertThat(cannoneer.path("side").asInt()).isEqualTo(1);
      assertThat(cannoneer.path("x").asInt()).isEqualTo(slot.path("x").asInt() * 500);
      assertThat(cannoneer.path("y").asInt()).isEqualTo((64 - slot.path("y").asInt()) * 500);
      assertThat(cannoneer.path("hp").asInt()).isEqualTo(hitpointsAt("Cannoneer", cannoneerLevel));
    }
    // Side 0's Knight walks up the left lane into the low Cannoneer's range: its first shot takes
    // the projectile's damage at the tower's level, on tick 299 with the columns written above.
    int knightHp = hitpointsAt("Knight", cardLevel("Knight", 0));
    int firstHit = -1;
    for (String line : lines) {
      JsonNode observation = MAPPER.readTree(line);
      for (JsonNode entity : observation.path("entities")) {
        if (entity.path("row").asText().equals("Knight") && entity.path("hp").asInt() < knightHp) {
          assertThat(entity.path("hp").asInt())
              .isEqualTo(knightHp - projectileDamageAt("Cannoneer", cannoneerLevel));
          firstHit = observation.path("tick").asInt();
          break;
        }
      }
      if (firstHit >= 0) {
        break;
      }
    }
    assertThat(firstHit).isEqualTo(299);
  }

  @Test
  void aTowerSelectionLevelRaisesItsSidesPrincessTowersButNotItsKing() throws IOException {
    ObjectNode scenario = fit(Scenarios.knight());
    // Side 0 selects the princess towers one level up, side 1 eight levels up.
    ((ObjectNode) scenario.path("battle").path("deck0").path("sc").get(0)).put("l", 1);
    ((ObjectNode) scenario.path("battle").path("deck1").path("sc").get(0)).put("l", 8);
    Path out = folder.resolve("run");

    int exit = run(scenario, out, identity, 330);

    assertThat(exit).isEqualTo(ReplaySmokeRun.COMPLETED);
    List<String> lines = Files.readAllLines(out.resolve("observations.jsonl"));
    JsonNode first = MAPPER.readTree(lines.get(0)).path("entities");
    // Each king stands at the avatar's level (exp level 1: the first level), whatever its side's
    // selection level is; the princess towers stand at their own side's selection level.
    int low = towerLevel("King_PrincessTowers", 1);
    int high = towerLevel("King_PrincessTowers", 8);
    for (int i : new int[] {0, 3}) {
      assertThat(first.get(i).path("row").asText()).isEqualTo("KingTower");
      assertThat(first.get(i).path("hp").asInt()).isEqualTo(hitpointsAt("KingTower", 1));
    }
    for (int i : new int[] {1, 2}) {
      assertThat(first.get(i).path("row").asText()).isEqualTo("PrincessTower");
      assertThat(first.get(i).path("hp").asInt()).isEqualTo(hitpointsAt("PrincessTower", low));
    }
    for (int i : new int[] {4, 5}) {
      assertThat(first.get(i).path("row").asText()).isEqualTo("PrincessTower");
      assertThat(first.get(i).path("hp").asInt()).isEqualTo(hitpointsAt("PrincessTower", high));
    }
    // Side 0's Knight walks up the left lane into side 1's low princess tower's range: its first
    // arrow takes the projectile's damage at the tower's level.
    int knightHp = hitpointsAt("Knight", cardLevel("Knight", 0));
    int firstHp = -1;
    for (String line : lines) {
      for (JsonNode entity : MAPPER.readTree(line).path("entities")) {
        if (entity.path("row").asText().equals("Knight") && entity.path("hp").asInt() < knightHp) {
          firstHp = entity.path("hp").asInt();
          break;
        }
      }
      if (firstHp >= 0) {
        break;
      }
    }
    assertThat(firstHp).isEqualTo(knightHp - projectileDamageAt("PrincessTower", high));
  }

  @Test
  void aRoyalChefTowerSelectionCooksAPancakeThatRaisesAFriendlyTroopsLevel() throws IOException {
    // The pancake's tick and point and the level-up's tick come from the whole scene, so the scene
    // writes every column they depend on: the cooking's delay, contributions and full bar, the
    // throw; the Chef towers' places, footprint, ranges and attack, and the levels and hit points
    // that decide how long the low tower shoots side 0's Knight; the Giant's walk; the flights.
    ObjectNode cooking = fields("ChefTower_CookingAction");
    cooking.put("StartCookingDelay", 7000).put("ContributionNeeded", 23000);
    cooking.put("ContributionBaseline", 600).put("ContributionIdle", 200);
    cooking.put("ContributionAttacking", 0);
    cooking.put("PancakeThrowDelay", 250).put("PancakeStartOffset", 200);
    ObjectNode tower = columns("buildings", "ChefTower");
    tower.put("Range", 7500).put("SightRange", 7500).put("HitSpeed", 1000).put("LoadTime", 200);
    tower.put("CollisionRadius", 1000).put("NoDeploySizeW", 11).put("NoDeploySizeH", 21);
    columns("projectiles", "ChefTower_spatula_projectile").put("Speed", 600).put("Damage", 50);
    columns("projectiles", "ChefTower_pancake_projectile").put("Speed", 600);
    columns("support_rarities", "Legendary").put("RelativeLevel", 8);
    columns("rarities", "Common").put("RelativeLevel", 0);
    ObjectNode knight = columns("characters", "Knight");
    knight.put("Speed", 60).put("DeployTime", 1000).put("CollisionRadius", 500);
    knight.put("Range", 1200).put("Hitpoints", 690);
    ObjectNode giant = columns("characters", "Giant");
    giant.put("Speed", 45).put("DeployTime", 1000).put("CollisionRadius", 750).put("Range", 1200);
    giant.put("StopMovementAfterMS", 640).put("WaitMS", 100);
    place("King_ChefTowers", 18, 6, 7, 13, 29, 13);
    place("King_PrincessTowers", 18, 6, 7, 13, 29, 13);
    useWrittenTables();
    Path out = folder.resolve("run");

    int exit = run(fit(Scenarios.knightAgainstTheRoyalChef()), out, identity, 700);

    assertThat(exit).isEqualTo(ReplaySmokeRun.COMPLETED);
    List<String> lines = Files.readAllLines(out.resolve("observations.jsonl"));
    JsonNode first = MAPPER.readTree(lines.get(0)).path("entities");
    // Side 1 stands the Royal Chef's king row and two ChefTower rows, the Legendary selection's
    // RelativeLevel above their first.
    int chefLevel = towerLevel("King_ChefTowers", 0);
    assertThat(first.get(3).path("row").asText()).isEqualTo("ChefTowerKing");
    assertThat(first.get(3).path("hp").asInt()).isEqualTo(hitpointsAt("ChefTowerKing", 1));
    for (int i = 4; i <= 5; i++) {
      assertThat(first.get(i).path("row").asText()).isEqualTo("ChefTower");
      assertThat(first.get(i).path("hp").asInt()).isEqualTo(hitpointsAt("ChefTower", chefLevel));
    }
    // The Giant, a Rare at level index 0, and one level above it once the pancake lands.
    int giantLevel = cardLevel("Giant", 0);
    int giantHp = hitpointsAt("Giant", giantLevel);
    int raisedHp = hitpointsAt("Giant", giantLevel + 1);
    assertThat(raisedHp).isGreaterThan(giantHp);
    // The cooking starts 7 s in; each step adds the baseline, 600, and 200 for each idle tower (0
    // for one shooting): 800 while the low tower shoots side 0's Knight, 1000 while both idle. The
    // full bar, 20 times 23000, throws a pancake from the tower nearer side 1's Giant, 200 toward
    // it, on tick 638. Its landing raises the Giant one level: its hit points and its maximum to
    // the next level's on tick 644.
    JsonNode pancake = null;
    int pancakeTick = -1;
    int levelUpTick = -1;
    for (String line : lines) {
      JsonNode observation = MAPPER.readTree(line);
      int tick = observation.path("tick").asInt();
      for (JsonNode entity : observation.path("entities")) {
        if (pancake == null
            && tick > 450
            && !entity.has("row")
            && entity.path("side").asInt() == 1) {
          pancake = entity;
          pancakeTick = tick;
        }
        if (levelUpTick < 0
            && entity.path("row").asText().equals("Giant")
            && entity.path("max_hp").asInt() != giantHp) {
          assertThat(entity.path("max_hp").asInt()).isEqualTo(raisedHp);
          assertThat(entity.path("hp").asInt()).isEqualTo(raisedHp);
          levelUpTick = tick;
        }
      }
    }
    assertThat(pancakeTick).isEqualTo(638);
    assertThat(pancake.path("x").asInt()).isEqualTo(3440);
    assertThat(pancake.path("y").asInt()).isEqualTo(25310);
    assertThat(levelUpTick).isEqualTo(644);
  }

  @Test
  void aDaggerDuchessSpendsItsEightChargesThenAttacksOnlyAsItRecharges() throws IOException {
    // The hit ticks come from the whole scene, so the scene writes every column they depend on: the
    // burst's charges, recharge and attack entries, the Duchess's places, footprint, ranges, pace
    // and knife, its level, and the Giant's walk, level and hit points, which end the list.
    ObjectNode burst = fields("DaggerDuchess_OnStartingAction");
    burst.put("MaxChargeCount", 8).put("RechargeIncrement", 1).put("RechargeTime", 900);
    burst.put("DepletedAttackSequenceIndex", 3);
    burst.putArray("AttackSequenceIndices").add(2).add(0).add(1).add(0).add(1).add(0).add(1).add(0);
    ObjectNode tower = columns("buildings", "DaggerDuchess");
    tower.put("Range", 7500).put("SightRange", 7500).put("HitSpeed", 500);
    tower.put("CollisionRadius", 1000).put("NoDeploySizeW", 11).put("NoDeploySizeH", 21);
    tower.put("ProjectileStartRadius", 300);
    tower.putArray("AttackSequence").add(0).add(1).add(2).add(3);
    int[] multipliers = {100, 100, 70, 90};
    for (int entry = 0; entry < multipliers.length; entry++) {
      ((ObjectNode) tower.get("AttackSequenceList").get(entry))
          .put("HitSpeedMultiplier", multipliers[entry]);
    }
    columns("projectiles", "TowerKnifeThrowerProjectile").put("Speed", 1000).put("Damage", 42);
    columns("support_rarities", "Legendary").put("RelativeLevel", 8);
    columns("rarities", "Rare").put("RelativeLevel", 2);
    ObjectNode giant = columns("characters", "Giant");
    giant.put("Speed", 45).put("DeployTime", 1000).put("CollisionRadius", 750);
    giant.put("StopMovementAfterMS", 640).put("WaitMS", 100).put("Hitpoints", 1550);
    place("King_KnifeTowers", 18, 6, 7, 13, 29, 13);
    useWrittenTables();
    Path out = folder.resolve("run");

    int exit = run(fit(Scenarios.giantVsDuchessTower()), out, identity, 820);

    assertThat(exit).isEqualTo(ReplaySmokeRun.COMPLETED);
    List<String> lines = Files.readAllLines(out.resolve("observations.jsonl"));
    JsonNode first = MAPPER.readTree(lines.get(0)).path("entities");
    int duchessLevel = towerLevel("King_KnifeTowers", 0);
    for (int i = 4; i <= 5; i++) {
      assertThat(first.get(i).path("row").asText()).isEqualTo("DaggerDuchess");
      assertThat(first.get(i).path("hp").asInt())
          .isEqualTo(hitpointsAt("DaggerDuchess", duchessLevel));
    }
    int knife = projectileDamageAt("DaggerDuchess", duchessLevel);
    // The low Duchess's knives take their damage at its level off the Giant. Its first seven hits
    // come at the full pace,
    // every nine or ten ticks; the eighth, its last charge's, at the slower pace of its entry 2;
    // with no charge left it cannot attack until a charge comes back, 900 ms later, and then
    // throws it at the depleted entry's pace: one hit every 31 ticks until the Giant dies.
    List<Integer> hits = new ArrayList<>();
    int hp = -1;
    for (String line : lines) {
      JsonNode observation = MAPPER.readTree(line);
      for (JsonNode entity : observation.path("entities")) {
        if (entity.path("row").asText().equals("Giant")) {
          int now = entity.path("hp").asInt();
          if (hp >= 0 && now != hp) {
            assertThat(hp - now).isEqualTo(knife);
            hits.add(observation.path("tick").asInt());
          }
          hp = now;
        }
      }
    }
    assertThat(hits)
        .containsExactly(
            298, 307, 317, 326, 336, 345, 355, 369, 399, 429, 460, 491, 522, 553, 584, 615, 646,
            677, 708, 739, 770);
  }

  @Test
  void anUnknownSchemaOrAScopeOfAnotherSchemaIsAnInvalidRun() throws IOException {
    Path unknown = identity("unknown.json", "test-schema", SmokeSchema.V1.observationScope());
    Path crossed = identity("crossed.json", SmokeSchema.V2.id(), SmokeSchema.V1.observationScope());

    assertThat(run(fit(Scenarios.knight()), folder.resolve("unknown"), unknown, 30))
        .isEqualTo(ReplaySmokeRun.INVALID);
    assertThat(run(fit(Scenarios.knight()), folder.resolve("crossed"), crossed, 30))
        .isEqualTo(ReplaySmokeRun.INVALID);
    assertThat(folder.resolve("unknown").resolve("COMPLETE")).doesNotExist();
    assertThat(folder.resolve("crossed").resolve("COMPLETE")).doesNotExist();
  }

  @Test
  void aTerminalRunThatReachesItsHorizonStepsEveryTickAndSaysSo() throws IOException {
    Path out = folder.resolve("run");

    int exit = run(fit(Scenarios.knight()), out, terminalIdentity, 30);

    assertThat(exit).isEqualTo(ReplaySmokeRun.COMPLETED);
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    assertThat(manifest.path("schema").asText()).isEqualTo(SmokeSchema.V2.id());
    assertThat(manifest.path("ticks").asInt()).isEqualTo(30);
    assertThat(manifest.path("executed_ticks").asInt()).isEqualTo(30);
    assertThat(manifest.path("observations").asInt()).isEqualTo(31);
    assertThat(manifest.path("termination").path("reason").asText()).isEqualTo("horizon");
    assertThat(manifest.path("termination").path("tick").asInt()).isEqualTo(30);
    for (String line : Files.readAllLines(out.resolve("observations.jsonl"))) {
      assertThat(MAPPER.readTree(line).path("stopped").isBoolean()).isTrue();
      assertThat(MAPPER.readTree(line).path("stopped").asBoolean()).isFalse();
    }
  }

  /**
   * A horizon past an idle battle's own stop, from the tables in use: the battle runs the sections
   * of the Ladder mode's battle timeline, then the tiebreaker of its equal crowns and the end
   * screen's delay (the locations' EndScreenDelay). The tiebreaker takes seconds, far less than the
   * sections, so twice the sections and the delay, in ticks of 50 ms, lie past the stop.
   */
  private int idleHorizon() {
    String timeline =
        tables
            .table("game_modes")
            .row(LadderMatch.GAME_MODE)
            .columns()
            .get("BattleTimeline")
            .asText();
    int sectionsMs = 0;
    for (JsonNode seconds :
        tables.table("battle_timelines").row(timeline).columns().get("SectionLength")) {
      sectionsMs += seconds.asInt() * 1000;
    }
    int endScreenMs = 0;
    for (GameRow location : tables.table("locations").rows()) {
      endScreenMs = Math.max(endScreenMs, ScenarioItems.number(location, "EndScreenDelay"));
    }
    return 2 * (sectionsMs + endScreenMs) / 50;
  }

  @Test
  void aTerminalRunStopsAtTheBattlesOwnStopAndKeepsTheEndDelay() throws IOException {
    ObjectNode idle = fit(Scenarios.knight());
    idle.putArray("cmd");
    Path out = folder.resolve("run");
    int horizon = idleHorizon();

    int exit = run(idle, out, terminalIdentity, horizon);

    assertThat(exit).isEqualTo(ReplaySmokeRun.COMPLETED);
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    List<String> lines = Files.readAllLines(out.resolve("observations.jsonl"));
    int executed = manifest.path("executed_ticks").asInt();
    assertThat(executed).isLessThan(horizon);
    assertThat(lines).hasSize(executed + 1);
    assertThat(manifest.path("observations").asInt()).isEqualTo(executed + 1);
    assertThat(manifest.path("termination").path("reason").asText()).isEqualTo("battle_stopped");
    assertThat(manifest.path("termination").path("tick").asInt()).isEqualTo(executed);
    // Only the last observation is stopped; the match was ended for a while before it.
    int firstEnded = -1;
    for (int i = 0; i < lines.size(); i++) {
      JsonNode observation = MAPPER.readTree(lines.get(i));
      assertThat(observation.path("tick").asInt()).isEqualTo(i);
      assertThat(observation.path("stopped").asBoolean()).isEqualTo(i == executed);
      if (firstEnded < 0 && observation.path("ended").asBoolean()) {
        firstEnded = i;
      }
    }
    assertThat(firstEnded).isPositive().isLessThan(executed);
    // The exact-horizon schema cannot represent that battle over the same horizon.
    assertThat(run(idle, folder.resolve("exact"), identity, horizon))
        .isEqualTo(ReplaySmokeRun.INVALID);
  }

  @Test
  void anInProcessRunGivesTheTraceAndTheOutcomeOfARunFromTheCommandLine() throws IOException {
    GameTables tables = GameTables.load(tablesFolder);
    ObjectNode idle = fit(Scenarios.knight());
    idle.putArray("cmd");
    int horizon = idleHorizon();
    run(idle, folder.resolve("terminal"), terminalIdentity, horizon);
    ObjectNode unsupported = fit(Scenarios.knight());
    ((ObjectNode) unsupported.path("battle").path("deck0").path("sc").get(0)).put("d", 159000003);
    run(unsupported, folder.resolve("unsupported"), identity, 30);
    run(idle, folder.resolve("invalid"), identity, horizon);

    ReplaySmokeRun.InProcessRun terminal =
        ReplaySmokeRun.runInProcess(
            SmokeSchema.V2, horizon, MAPPER.writeValueAsBytes(idle), tables);
    ReplaySmokeRun.InProcessRun refused =
        ReplaySmokeRun.runInProcess(
            SmokeSchema.V1, 30, MAPPER.writeValueAsBytes(unsupported), tables);
    ReplaySmokeRun.InProcessRun stopped =
        ReplaySmokeRun.runInProcess(
            SmokeSchema.V1, horizon, MAPPER.writeValueAsBytes(idle), tables);

    // The same trace, digest, steps and termination as the run written to disk.
    JsonNode manifest = MAPPER.readTree(folder.resolve("terminal/manifest.json").toFile());
    assertThat(terminal.status()).isEqualTo("completed");
    assertThat(terminal.trace())
        .isEqualTo(Files.readAllBytes(folder.resolve("terminal/observations.jsonl")));
    JsonNode inProcess = MAPPER.valueToTree(terminal.manifest());
    for (String field :
        List.of(
            "trace_sha256",
            "observations",
            "executed_ticks",
            "termination",
            "scenario_sha256",
            "schema",
            "ticks")) {
      assertThat(inProcess.get(field)).as(field).isEqualTo(manifest.get(field));
    }
    // The same refusal, and the same error.
    JsonNode refusedManifest =
        MAPPER.readTree(folder.resolve("unsupported/manifest.json").toFile());
    assertThat(refused.status()).isEqualTo("unsupported");
    assertThat(MAPPER.valueToTree(refused.manifest()).get("unsupported"))
        .isEqualTo(refusedManifest.get("unsupported"));
    assertThat(refused.trace()).isEmpty();
    JsonNode invalidManifest = MAPPER.readTree(folder.resolve("invalid/manifest.json").toFile());
    assertThat(stopped.status()).isEqualTo("invalid");
    assertThat(stopped.manifest().get("error")).isEqualTo(invalidManifest.path("error").asText());
  }

  @Test
  void aGeneratedCaseRunsWhenTheRunNamesItsShape() throws IOException {
    GameTables tables = GameTables.loadConfigured();
    byte[] scenario = MAPPER.writeValueAsBytes(fit(Scenarios.generatedKnight()));

    ReplaySmokeRun.InProcessRun generated =
        ReplaySmokeRun.runInProcess(SmokeSchema.V2, 260, scenario, tables, ScenarioShape.GENERATED);
    ReplaySmokeRun.InProcessRun replay =
        ReplaySmokeRun.runInProcess(SmokeSchema.V2, 260, scenario, tables);

    assertThat(generated.status()).isEqualTo("completed");
    JsonNode manifest = MAPPER.valueToTree(generated.manifest());
    assertThat(manifest.path("adapter").path("scenario_shape").asText()).isEqualTo("generated");
    List<String> lines = List.of(new String(generated.trace()).split("\n"));
    assertThat(lines).hasSize(261);
    // Each king at level 1, its row's own hit points, as the recorded battles of the version's
    // generated cases hold it, and the Knight placed on the play's run tick.
    int kingHitpoints =
        tables.table("buildings").row("KingTower").columns().get("Hitpoints").asInt();
    JsonNode first = MAPPER.readTree(lines.get(0));
    assertThat(first.path("entities").get(0).path("row").asText()).isEqualTo("KingTower");
    assertThat(first.path("entities").get(0).path("hp").asInt()).isEqualTo(kingHitpoints);
    assertThat(first.path("entities").get(3).path("hp").asInt()).isEqualTo(kingHitpoints);
    assertThat(MAPPER.readTree(lines.get(221)).path("entities").get(6).path("row").asText())
        .isEqualTo("Knight");
    // Read as a replay of the version, the case lacks the request lists.
    assertThat(replay.status()).isEqualTo("invalid");
    assertThat(replay.manifest().get("error").toString()).contains("the scenario has no srq");
  }

  @Test
  void theRunOptionNamesTheScenarioShapeAndRefusesAnUnknownOne() throws IOException {
    Path generated = folder.resolve("generated");
    Path unknown = folder.resolve("unknown");

    int exit = run(fit(Scenarios.generatedKnight()), generated, identity, 30, "generated");
    int refused = run(fit(Scenarios.knight()), unknown, identity, 30, "recorded");

    // The case generated for the version leaves out what its replays add beyond it, each king's
    // level 1 among them: generated, it runs the same battle as the replay.
    assertThat(exit).isEqualTo(ReplaySmokeRun.COMPLETED);
    run(fit(Scenarios.knight()), folder.resolve("replay"), identity, 30);
    assertThat(Files.readAllBytes(generated.resolve("observations.jsonl")))
        .isEqualTo(Files.readAllBytes(folder.resolve("replay/observations.jsonl")));
    JsonNode manifest = MAPPER.readTree(generated.resolve("manifest.json").toFile());
    assertThat(manifest.path("adapter").path("scenario_shape").asText()).isEqualTo("generated");
    JsonNode replayManifest = MAPPER.readTree(folder.resolve("replay/manifest.json").toFile());
    assertThat(replayManifest.path("adapter").path("scenario_shape").asText()).isEqualTo("replay");
    assertThat(refused).isEqualTo(ReplaySmokeRun.INVALID);
    assertThat(MAPPER.readTree(unknown.resolve("manifest.json").toFile()).path("error").asText())
        .contains("not a scenario shape: recorded");
  }

  @Test
  void aRunRepeatsByteForByteFromAFreshBattle() throws IOException {
    run(fit(Scenarios.knight()), folder.resolve("one"), identity, 260);
    run(fit(Scenarios.knight()), folder.resolve("two"), identity, 260);

    assertThat(Files.readAllBytes(folder.resolve("one").resolve("observations.jsonl")))
        .isEqualTo(Files.readAllBytes(folder.resolve("two").resolve("observations.jsonl")));
  }

  @Test
  void thePlayRunsOnItsRunTickAndItsUnitIsInTheNextObservation() throws IOException {
    Path out = folder.resolve("run");

    run(fit(Scenarios.knight()), out, identity, 230);

    List<String> lines = Files.readAllLines(out.resolve("observations.jsonl"));
    assertThat(MAPPER.readTree(lines.get(220)).path("entities")).hasSize(6);
    JsonNode after = MAPPER.readTree(lines.get(221));
    assertThat(after.path("entities")).hasSize(7);
    JsonNode knight = after.path("entities").get(6);
    assertThat(knight.path("id").asInt()).isEqualTo(5000006);
    assertThat(knight.path("row").asText()).isEqualTo("Knight");
    assertThat(knight.path("side").asInt()).isZero();
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    assertThat(manifest.path("plays_run").get(0).path("tick").asInt()).isEqualTo(220);
    assertThat(manifest.path("plays_run").get(0).path("placed").asBoolean()).isTrue();
  }

  @Test
  void anEvolutionSlotsCardIsPlayedPlainTwiceAndEvolvedOnItsThirdPlay() throws IOException {
    usePlannedSchedule();
    Path out = folder.resolve("run");

    int exit = run(fit(Scenarios.knightEvolvedThirdPlay()), out, terminalIdentity, 1430);

    assertThat(exit).isEqualTo(ReplaySmokeRun.COMPLETED);
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    assertThat(manifest.path("plays_run")).hasSize(11);
    for (JsonNode play : manifest.path("plays_run")) {
      assertThat(play.path("placed").asBoolean()).as(play.toString()).isTrue();
    }
    // Each Knight play's unit is in the observation after its run tick, as the row its item casts:
    // the evolved row once the count (0, 1, 2) has reached the evolved row's DarkElixirCost.
    int cost =
        ScenarioItems.number(
            ScenarioItems.evolvedRow(tables, ScenarioItems.card(tables, "Knight")),
            "DarkElixirCost");
    List<String> lines = Files.readAllLines(out.resolve("observations.jsonl"));
    int[] runTicks = {220, 630, 1416};
    for (int count = 0; count < runTicks.length; count++) {
      assertThat(rowOfNewestUnit(lines.get(runTicks[count] + 1)))
          .as("the Knight play of count %d", count)
          .isEqualTo(count >= cost ? "Knight_EV1" : "Knight");
    }
    assertThat(manifest.has("items_not_built")).isFalse();
  }

  @Test
  void anAbilityCommandRunsOnItsRunTickPaysAndCastsTheNamedChampionsAbility() throws IOException {
    ObjectNode without = fit(Scenarios.archerQueenAbility());
    ((ArrayNode) without.path("cmd")).remove(1);
    run(without, folder.resolve("without"), terminalIdentity, 360);
    Path out = folder.resolve("run");

    int exit = run(fit(Scenarios.archerQueenAbility()), out, terminalIdentity, 360);

    assertThat(exit).isEqualTo(ReplaySmokeRun.COMPLETED);
    List<String> lines = Files.readAllLines(out.resolve("observations.jsonl"));
    List<String> plain =
        Files.readAllLines(folder.resolve("without").resolve("observations.jsonl"));
    // Nothing differs before the command's run tick, 350.
    assertThat(lines.subList(0, 351)).isEqualTo(plain.subList(0, 351));
    // In the observation after it the Archer Queen's ability cost, its ability row's ManaCost, is
    // spent and she casts (state 10) where she shot (state 2).
    GameRow ability =
        tables
            .table("character_abilities")
            .row(unitRow("ArcherQueen").columns().get("Ability").asText());
    JsonNode after = MAPPER.readTree(lines.get(351));
    JsonNode plainAfter = MAPPER.readTree(plain.get(351));
    assertThat(after.path("sides").get(0).path("elixir").asInt())
        .isEqualTo(
            plainAfter.path("sides").get(0).path("elixir").asInt()
                - ScenarioItems.number(ability, "ManaCost") * 10000);
    assertThat(entity(after, 5000006).path("state").asInt()).isEqualTo(10);
    assertThat(entity(plainAfter, 5000006).path("state").asInt()).isEqualTo(2);
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    JsonNode used = manifest.path("abilities_run").get(0);
    assertThat(used.path("name").asText()).isEqualTo("cmd1");
    assertThat(used.path("tick").asInt()).isEqualTo(350);
    assertThat(used.path("code").asInt()).isZero();
  }

  @Test
  void anAbilityCommandNamingNoLiveUnitIsRefusedAndChangesNothing() throws IOException {
    ObjectNode without = fit(Scenarios.archerQueenAbility());
    ((ArrayNode) without.path("cmd")).remove(1);
    run(without, folder.resolve("without"), terminalIdentity, 360);
    ObjectNode scenario = fit(Scenarios.archerQueenAbility());
    ((ObjectNode) scenario.path("cmd").get(1).path("c")).put("cgid", 5000099);
    Path out = folder.resolve("run");

    int exit = run(scenario, out, terminalIdentity, 360);

    assertThat(exit).isEqualTo(ReplaySmokeRun.COMPLETED);
    assertThat(Files.readAllBytes(out.resolve("observations.jsonl")))
        .isEqualTo(Files.readAllBytes(folder.resolve("without").resolve("observations.jsonl")));
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    // Refused: no champion found (0x3ee).
    assertThat(manifest.path("abilities_run").get(0).path("code").asInt()).isEqualTo(0x3ee);
  }

  @Test
  void anEvolutionSlotsPlayThatNeverRunsIsListedAsNotBuilt() throws IOException {
    usePlannedSchedule();
    Path out = folder.resolve("run");

    // The horizon ends before the third Knight play's run tick, 1416.
    int exit = run(fit(Scenarios.knightEvolvedThirdPlay()), out, terminalIdentity, 1400);

    assertThat(exit).isEqualTo(ReplaySmokeRun.COMPLETED);
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    assertThat(manifest.path("plays_run")).hasSize(10);
    assertThat(manifest.path("items_not_built").toString()).isEqualTo("[\"cmd[10]\"]");
  }

  @Test
  void aPlayWhoseItemIsNotTheItemTheSimulatorBuildsIsUnsupported() throws IOException {
    usePlannedSchedule();
    ObjectNode scenario = fit(Scenarios.knightEvolvedThirdPlay());
    // The first play claims the item of the third: the count plus 1 of 3.
    int third = item(scenario, 10);
    ((ObjectNode) scenario.path("cmd").get(0).path("c").path("sel")).put("pd", third);
    Path out = folder.resolve("run");

    int exit = run(scenario, out, terminalIdentity, 1430);

    assertThat(exit).isEqualTo(ReplaySmokeRun.UNSUPPORTED);
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    assertThat(manifest.path("status").asText()).isEqualTo("unsupported");
    assertThat(manifest.path("unsupported").path("feature").asText()).contains("packed item");
    assertThat(manifest.path("unsupported").path("input").asText())
        .startsWith("cmd[0].c.sel.pd=" + third);
    assertThat(out.resolve("COMPLETE")).doesNotExist();
  }

  @Test
  void aPlayWhoseCountIsNotTheSimulatorsIsUnsupported() throws IOException {
    usePlannedSchedule();
    ObjectNode scenario = fit(Scenarios.knightEvolvedThirdPlay());
    // The second Knight play repeats the first's count plus 1, 1, where the simulator counts 2.
    int firstItem = item(scenario, 0);
    ((ObjectNode) scenario.path("cmd").get(5).path("c").path("sel")).put("pd", firstItem);
    Path out = folder.resolve("run");

    int exit = run(scenario, out, terminalIdentity, 1430);

    assertThat(exit).isEqualTo(ReplaySmokeRun.UNSUPPORTED);
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    assertThat(manifest.path("unsupported").path("input").asText())
        .startsWith("cmd[5].c.sel.pd=" + firstItem);
    assertThat(out.resolve("COMPLETE")).doesNotExist();
  }

  @Test
  void aMirrorPlayRepeatsItsSidesLastCardOneLevelAboveTheMirrorsForItsItem() throws IOException {
    usePlannedSchedule();
    Path out = folder.resolve("run");

    int exit = run(fit(Scenarios.knightThenMirror()), out, terminalIdentity, 420);

    assertThat(exit).isEqualTo(ReplaySmokeRun.COMPLETED);
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    JsonNode mirror = manifest.path("plays_run").get(4);
    assertThat(mirror.path("name").asText()).isEqualTo("cmd4");
    assertThat(mirror.path("tick").asInt()).isEqualTo(410);
    assertThat(mirror.path("placed").asBoolean()).isTrue();
    assertThat(mirror.path("units").asInt()).isEqualTo(1);
    List<String> lines = Files.readAllLines(out.resolve("observations.jsonl"));
    // The Knight again, at the Mirror's level field plus MIRROR_LEVEL_OFFSET: that level's hit
    // points, where the Knight played at its own level index 0 has its first level's.
    int level = cardLevel("Mirror", 0) + ScenarioItems.global(tables, "MIRROR_LEVEL_OFFSET");
    assertThat(level).isGreaterThan(cardLevel("Knight", 0));
    JsonNode repeated = newestUnit(MAPPER.readTree(lines.get(411)));
    assertThat(repeated.path("row").asText()).isEqualTo("Knight");
    assertThat(repeated.path("side").asInt()).isZero();
    assertThat(repeated.path("hp").asInt()).isEqualTo(hitpointsAt("Knight", level));
    // The Mirror costs its own cost more than the Knight, less one step's regeneration.
    int cost = costOf("Mirror") + costOf("Knight");
    int before = MAPPER.readTree(lines.get(410)).path("sides").get(0).path("elixir").asInt();
    int after = MAPPER.readTree(lines.get(411)).path("sides").get(0).path("elixir").asInt();
    assertThat(before - after).isBetween(cost - 200, cost);
  }

  @Test
  void aMirrorPlayNamingAnotherRepeatedCardThanTheSimulatorsIsUnsupported() throws IOException {
    usePlannedSchedule();
    ObjectNode scenario = fit(Scenarios.knightThenMirror());
    // The Archer was played before the Knight: the Mirror repeats the Knight, the last card.
    ((ObjectNode) scenario.path("cmd").get(4).path("c").path("sel")).put("fs", Scenarios.ARCHER);
    Path out = folder.resolve("run");

    int exit = run(scenario, out, terminalIdentity, 420);

    assertThat(exit).isEqualTo(ReplaySmokeRun.UNSUPPORTED);
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    assertThat(manifest.path("unsupported").path("feature").asText())
        .isEqualTo(
            "a Mirror play whose repeated card is not the one the simulator's Mirror repeats");
    assertThat(manifest.path("unsupported").path("input").asText())
        .isEqualTo(
            "cmd[4].c.sel.fs="
                + Scenarios.ARCHER
                + " (Archer), where the simulator repeats Knight");
    assertThat(out.resolve("COMPLETE")).doesNotExist();
  }

  @Test
  void aMirrorPlayWhoseCostOrLevelIsNotTheSimulatorsIsUnsupported() throws IOException {
    // A Mirror one level above its own, so its own level field is another item than the built one.
    columns("globals", "MIRROR_LEVEL_OFFSET").put("NumberValue", 1);
    usePlannedSchedule();
    // The Mirror's own cost and its own level field, where it repeats the Knight.
    int built = item(fit(Scenarios.knightThenMirror()), 4);
    int ownLevelField = cardLevel("Mirror", 0) - 1;
    assertThat(ScenarioItems.costOf(built)).isNotEqualTo(ScenarioItems.cost(tables, "Mirror"));
    assertThat(ScenarioItems.levelFieldOf(built)).isNotEqualTo(ownLevelField);
    for (int item :
        new int[] {
          ScenarioItems.withCost(built, ScenarioItems.cost(tables, "Mirror")),
          ScenarioItems.withLevelField(built, ownLevelField)
        }) {
      ObjectNode scenario = fit(Scenarios.knightThenMirror());
      ((ObjectNode) scenario.path("cmd").get(4).path("c").path("sel")).put("pd", item);
      Path out = folder.resolve("run-" + Integer.toHexString(item));

      int exit = run(scenario, out, terminalIdentity, 420);

      assertThat(exit).isEqualTo(ReplaySmokeRun.UNSUPPORTED);
      JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
      assertThat(manifest.path("unsupported").path("feature").asText()).contains("packed item");
      // The item the simulator builds: the Mirror's level field plus the offset, its deck index
      // field and its cost plus the Knight's.
      assertThat(manifest.path("unsupported").path("input").asText())
          .startsWith("cmd[4].c.sel.pd=" + item)
          .endsWith("where the simulator builds " + built + " (" + describe(built) + ")");
    }
  }

  @Test
  void aVariantPlayFromAFullBarRunsAsTheMountedMaidenForItsCost() throws IOException {
    usePlannedSchedule();
    Path out = folder.resolve("run");

    int exit = run(fit(Scenarios.mergeMaidenMounted()), out, terminalIdentity, 250);

    assertThat(exit).isEqualTo(ReplaySmokeRun.COMPLETED);
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    JsonNode maiden = manifest.path("plays_run").get(0);
    assertThat(maiden.path("name").asText()).isEqualTo("cmd0");
    assertThat(maiden.path("tick").asInt()).isEqualTo(240);
    assertThat(maiden.path("placed").asBoolean()).isTrue();
    assertThat(manifest.has("items_not_built")).isFalse();
    List<String> lines = Files.readAllLines(out.resolve("observations.jsonl"));
    JsonNode unit = newestUnit(MAPPER.readTree(lines.get(241)));
    assertThat(unit.path("row").asText()).isEqualTo("MergeMaiden_Mounted");
    assertThat(unit.path("side").asInt()).isZero();
    // The mounted maiden's cost, less one step's regeneration.
    int cost = costOf("MergeMaiden_Mounted");
    int before = MAPPER.readTree(lines.get(240)).path("sides").get(0).path("elixir").asInt();
    int after = MAPPER.readTree(lines.get(241)).path("sides").get(0).path("elixir").asInt();
    assertThat(before - after).isBetween(cost - 200, cost);
  }

  @Test
  void aVariantPlayBelowTheMountedTriggerRunsAsTheMaidenOnFootForItsCost() throws IOException {
    usePlannedSchedule();
    Path out = folder.resolve("run");

    int exit = run(fit(Scenarios.mergeMaidenOnFoot()), out, terminalIdentity, 280);

    assertThat(exit).isEqualTo(ReplaySmokeRun.COMPLETED);
    List<String> lines = Files.readAllLines(out.resolve("observations.jsonl"));
    JsonNode unit = newestUnit(MAPPER.readTree(lines.get(271)));
    assertThat(unit.path("row").asText()).isEqualTo("MergeMaiden_Normal");
    int cost = costOf("MergeMaiden_Normal");
    int before = MAPPER.readTree(lines.get(270)).path("sides").get(0).path("elixir").asInt();
    int after = MAPPER.readTree(lines.get(271)).path("sides").get(0).path("elixir").asInt();
    assertThat(before - after).isBetween(cost - 200, cost);
  }

  @Test
  void aVariantPlayGivenAsAnotherOptionThanTheSimulatorPicksIsUnsupported() throws IOException {
    usePlannedSchedule();
    ObjectNode scenario = fit(Scenarios.mergeMaidenMounted());
    int mounted = item(scenario, 0);
    // The maiden on foot, where the client picks the mounted maiden from a full bar: the on-foot
    // play's item, the Merge Maiden at the same deck index.
    int onFoot = item(fit(Scenarios.mergeMaidenOnFoot()), 1);
    ((ObjectNode) scenario.path("cmd").get(0).path("c").path("sel")).put("pd", onFoot);
    Path out = folder.resolve("run");

    int exit = run(scenario, out, terminalIdentity, 250);

    assertThat(exit).isEqualTo(ReplaySmokeRun.UNSUPPORTED);
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    assertThat(manifest.path("unsupported").path("feature").asText())
        .isEqualTo("a play whose packed item is not the item the simulator builds as it runs");
    assertThat(manifest.path("unsupported").path("input").asText())
        .isEqualTo(
            "cmd[0].c.sel.pd="
                + onFoot
                + " ("
                + describe(onFoot)
                + ") for MergeMaiden, where the simulator builds "
                + mounted
                + " ("
                + describe(mounted)
                + ")");
    assertThat(describe(onFoot)).contains("option field 2");
    assertThat(describe(mounted)).contains("option field 1");
    assertThat(out.resolve("COMPLETE")).doesNotExist();
  }

  @Test
  void aMirrorOfAVariantPlayIsRefusedByTheBattle() throws IOException {
    usePlannedSchedule();
    Path out = folder.resolve("run");

    int exit = run(fit(Scenarios.mergeMaidenThenMirror()), out, terminalIdentity, 700);

    // The maiden's play runs; the Mirror, which would repeat the option it was played as, is
    // refused as it runs.
    assertThat(exit).isEqualTo(ReplaySmokeRun.UNSUPPORTED);
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    assertThat(manifest.path("unsupported").path("feature").asText())
        .isEqualTo(
            "a Mirror of MergeMaiden, which repeats the option it was played as, which no"
                + " reference holds");
    assertThat(manifest.path("unsupported").path("input").asText()).isEqualTo("the run");
  }

  @Test
  void anUnsupportedScenarioWritesNoObservationAndNoMarker() throws IOException {
    ObjectNode scenario = fit(Scenarios.knight());
    ((ObjectNode) scenario.path("battle").path("deck0").path("sc").get(0)).put("d", 159000003);
    Path out = folder.resolve("run");

    int exit = run(scenario, out, identity, 30);

    assertThat(exit).isEqualTo(ReplaySmokeRun.UNSUPPORTED);
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    assertThat(manifest.path("status").asText()).isEqualTo("unsupported");
    assertThat(manifest.path("unsupported").path("input").asText())
        .isEqualTo("battle.deck0.sc[0].d=159000003");
    assertThat(out.resolve("COMPLETE")).doesNotExist();
    assertThat(out.resolve("observations.jsonl")).doesNotExist();
  }

  @Test
  void tablesOfAnotherContentAreAnInvalidRun() throws IOException {
    ObjectNode fields = (ObjectNode) MAPPER.readTree(identity.toFile());
    fields.put(ContentFields.CONTENT_SHA, "0000000000000000000000000000000000000000");
    Path other = folder.resolve("other-identity.json");
    MAPPER.writeValue(other.toFile(), fields);
    Path out = folder.resolve("run");

    int exit = run(fit(Scenarios.knight()), out, other, 30);

    assertThat(exit).isEqualTo(ReplaySmokeRun.INVALID);
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    assertThat(manifest.path("status").asText()).isEqualTo("invalid");
    assertThat(out.resolve("COMPLETE")).doesNotExist();
  }

  @Test
  void anExistingRunDirectoryIsNeverWrittenInto() throws IOException {
    Path out = folder.resolve("run");
    Files.createDirectory(out);

    int exit = run(fit(Scenarios.knight()), out, identity, 30);

    assertThat(exit).isEqualTo(ReplaySmokeRun.INVALID);
    assertThat(out.resolve("manifest.json")).doesNotExist();
  }

  /** The entity an observation lists under a game object id, or a missing node. */
  private static JsonNode entity(JsonNode observation, int id) {
    for (JsonNode entity : observation.path("entities")) {
      if (entity.path("id").asInt() == id) {
        return entity;
      }
    }
    return MAPPER.missingNode();
  }

  /** The entity with the highest id an observation lists, the newest unit. */
  private static JsonNode newestUnit(JsonNode observation) {
    JsonNode newest = MAPPER.missingNode();
    for (JsonNode entity : observation.path("entities")) {
      if (newest.isMissingNode() || entity.path("id").asInt() > newest.path("id").asInt()) {
        newest = entity;
      }
    }
    return newest;
  }

  /** The row of the entity with the highest id an observation lists, the newest unit. */
  private static String rowOfNewestUnit(String line) throws IOException {
    JsonNode newest = null;
    for (JsonNode entity : MAPPER.readTree(line).path("entities")) {
      if (newest == null || entity.path("id").asInt() > newest.path("id").asInt()) {
        newest = entity;
      }
    }
    return newest == null ? null : newest.path("row").asText();
  }

  private int run(ObjectNode scenario, Path out, Path identityFile, int ticks) throws IOException {
    Path file = folder.resolve("scenario-" + out.getFileName() + ".json");
    MAPPER.writeValue(file.toFile(), scenario);
    return ReplaySmokeRun.run(
        new String[] {
          "--scenario", file.toString(),
          "--tables", tablesFolder.toString(),
          "--identity", identityFile.toString(),
          "--ticks", Integer.toString(ticks),
          "--out", out.toString()
        });
  }

  /** A run from the command line that names the scenario's shape. */
  private int run(ObjectNode scenario, Path out, Path identityFile, int ticks, String shape)
      throws IOException {
    Path file = folder.resolve("scenario-" + out.getFileName() + ".json");
    MAPPER.writeValue(file.toFile(), scenario);
    return ReplaySmokeRun.run(
        new String[] {
          "--scenario", file.toString(),
          "--tables", tablesFolder.toString(),
          "--identity", identityFile.toString(),
          "--ticks", Integer.toString(ticks),
          "--out", out.toString(),
          "--scenario-shape", shape
        });
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
