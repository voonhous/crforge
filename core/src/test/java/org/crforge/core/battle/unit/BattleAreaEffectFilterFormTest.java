package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.math.FixedMath;
import org.crforge.core.pathfinding.move.MovementState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The area effect class has no hit switches: a row names a game object filter, which alone chooses
 * what it reaches, and writes its damage as a damage type, inline or named, with an amount for
 * anything and one for a crown tower. On an update a hit is due it lists the objects in its circle
 * that pass the filter, nearest first, and queues its damage on each, then applies its buff to
 * each. A hit is due on the step its HitSpeedOffset falls in and on every step a whole number of
 * HitSpeed steps after it, and the row stays until its countdown is below 0. Each scene plays a
 * spell of the configured tables in that form, any hit switch dropped and its filter
 * CommonAreaDamageFilter written without the filter's tags, and with the radius and the timings it
 * counts on written into its row.
 */
class BattleAreaEffectFilterFormTest {

  /** The level the scene's cards are played at. */
  private static final int LEVEL = 1;

  /** The hit switches and the columns the newer area effect class no longer has. */
  private static final List<String> REMOVED =
      List.of(
          "HitsAir",
          "HitsGround",
          "OnlyEnemies",
          "OnlyOwnTroops",
          "IgnoreBuildings",
          "AffectsHidden",
          "NoEffectToCrownTowers",
          "CrownTowerDamagePercent",
          "Damage");

  /** The filter the newer data's damaging spells name: enemy characters, as it writes it. */
  private static final String FILTER = "CommonAreaDamageFilter";

  /**
   * The configured tables with one area effect row rewritten in the filter form, the filter added.
   *
   * @param folder the folder the tables are copied into
   * @param row the area effect row
   * @param edit what is set on the row once its hit switches are gone
   */
  static GameTables filterForm(Path folder, String row, Consumer<ObjectNode> edit)
      throws IOException {
    GameData.altered(
        folder,
        "game_object_filters",
        rows -> {
          int index = 0;
          for (Iterator<JsonNode> it = rows.elements(); it.hasNext(); ) {
            index = Math.max(index, it.next().path("index").asInt() + 1);
          }
          ObjectNode filter = rows.putObject(FILTER);
          filter.put("index", index);
          filter.put("class", "LogicGameObjectFilterData");
          ObjectNode columns = filter.putObject("columns");
          columns.put("MatchTeamEnemy", true);
          columns.put("MatchTypeCharacters", true);
          columns
              .putArray("Filters")
              .add("Hidden")
              .add("Underground")
              .add("NoHitpointComponent")
              .add("DashImmune");
        });
    ObjectMapper mapper = new ObjectMapper();
    Path file = folder.resolve("area_effect_objects.json");
    ObjectNode document = (ObjectNode) mapper.readTree(file.toFile());
    ObjectNode columns = GameData.columns((ObjectNode) document.get("rows"), row);
    columns.remove(REMOVED);
    columns.put("Filter", FILTER);
    edit.accept(columns);
    mapper.writeValue(file.toFile(), document);
    return GameTables.load(folder);
  }

  /**
   * Writes the Zap's circle and life the scenes count on: a radius of 2500 and a life of 1 ms, so
   * its one hit falls on its first update.
   */
  private static void zapCircle(ObjectNode columns) {
    columns.put("Radius", 2500);
    columns.put("LifeDuration", 1);
  }

  /** Writes the Poison's circle and timings: a radius of 3500, a hit every 250 ms for 8000 ms. */
  private static void poisonCircle(ObjectNode columns) {
    columns.put("Radius", 3500);
    columns.put("HitSpeed", 250);
    columns.put("LifeDuration", 8000);
  }

  /** Steps a battle until its counter shows the given tick. */
  private static void stepTo(Standard1v1Battle match, int tick) {
    while (match.getBattle().getTick() < tick) {
      match.getBattle().step();
    }
  }

  /** The princess tower of a side on the right lane, right of the arena's middle. */
  private static WorldEntity rightPrincessTower(Standard1v1Battle match, int side) {
    for (BattleEntity listed : match.getBattle().getHolder().entities()) {
      if (listed instanceof WorldEntity entity
          && entity.getData().name().equals("PrincessTower")
          && entity.side() == side
          && entity.getView().getX() > 9000) {
        return entity;
      }
    }
    throw new IllegalStateException("no right princess tower of side " + side);
  }

  @Test
  @DisplayName(
      "a zap in the filter form deals its base damage to a troop and its tower damage to a crown"
          + " tower, nearest first")
  void theDamageTypeGivesTheTowerItsOwnAmount(@TempDir Path folder) throws IOException {
    GameTables tables =
        filterForm(
            folder,
            "Zap",
            columns -> {
              zapCircle(columns);
              ObjectNode damage = columns.putObject("Damage");
              damage.put("BaseDamage", 75);
              damage.put("TowerDamage", 19);
            });
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    List<String> hits = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void typedHitDealt(
                  int tick,
                  WorldEntity source,
                  WorldEntity target,
                  int amount,
                  int damageId,
                  DamageResult result) {
                hits.add(target.getData().name() + " side " + target.side() + " " + amount);
              }
            });
    // The Knight 3000 in front of side 1's right princess tower, the Zap 2000 in front of it.
    WorldEntity tower = rightPrincessTower(match, 1);
    int x = tower.getView().getX();
    int y = tower.getView().getY();
    match.play(190, match.getWorld().getRecords().card("Knight"), LEVEL, 1, x, y - 3000, "knight");
    match.play(200, match.getWorld().getRecords().card("Zap"), LEVEL, 0, x, y - 2000, "zap");
    stepTo(match, 200);
    int towerBefore = tower.getHitPoints().getHitPoints();
    assertThat(hits).isEmpty();
    stepTo(match, 300);
    CharacterEntity knight = match.getPlays().get(0).units().get(0);
    assertThat(hits).containsExactly("Knight side 1 75", "PrincessTower side 1 19");
    assertThat(knight.getHitPoints().getHitPoints())
        .isEqualTo(knight.getHitPoints().getMaximum() - 75);
    assertThat(tower.getHitPoints().getHitPoints()).isEqualTo(towerBefore - 19);
  }

  /** The pushback of the pushing explosion the push scenes cast. */
  private static final int PUSHBACK = 1000;

  /**
   * The configured tables with Zap rewritten in the filter form as a pushing explosion of the newer
   * data is written: a base damage, a pushback and no buff.
   */
  private static GameTables pushingZap(Path folder) throws IOException {
    return filterForm(
        folder,
        "Zap",
        columns -> {
          zapCircle(columns);
          columns.remove("Buff");
          columns.putObject("Damage").put("BaseDamage", 75);
          columns.put("Pushback", PUSHBACK);
        });
  }

  /**
   * Records, in order, every push request on a character of the given side - its id, whether a
   * pushback started, the point it was pushed from, where it stood and where the pushback aims -
   * and every typed hit it takes, with whether its movement component was on as the hit was dealt.
   */
  private static List<String> pushEvents(Standard1v1Battle match, int side) {
    List<String> events = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void pushbackRequested(
                  int tick,
                  WorldEntity unit,
                  boolean started,
                  int fromX,
                  int fromY,
                  MovementState pushback) {
                if (unit.side() == side) {
                  events.add(
                      String.format(
                          "%d push %s from %d,%d at %d,%d to %d,%d",
                          unit.getId(),
                          started,
                          fromX,
                          fromY,
                          unit.getView().getX(),
                          unit.getView().getY(),
                          pushback.getTargetX(),
                          pushback.getTargetY()));
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
                if (target.side() == side && target instanceof CharacterEntity character) {
                  events.add(
                      target.getId() + " hit " + amount + " moving " + character.movementOn());
                }
              }
            });
    return events;
  }

  @Test
  @DisplayName(
      "a pushing row in the filter form pushes a walking troop it lists the whole pushback away"
          + " from its point, every gate in place, before its damage")
  void aPushingRowPushesAWalkingTroopBeforeItsDamage(@TempDir Path folder) throws IOException {
    Standard1v1Battle match = new Standard1v1Battle(pushingZap(folder), LEVEL, false);
    List<String> events = pushEvents(match, 1);
    match.play(150, match.getWorld().getRecords().card("Knight"), LEVEL, 1, 14500, 22500, "k");
    match.play(200, match.getWorld().getRecords().card("Zap"), LEVEL, 0, 14500, 23500, "zap");
    stepTo(match, 300);
    CharacterEntity knight = match.getPlays().get(0).units().get(0);
    assertThat(events).hasSize(2);
    assertThat(events.get(1)).isEqualTo(knight.getId() + " hit 75 moving true");
    // The pushback aims the whole distance from where the Knight stands, straight away from the
    // cast point: the setter's direction, C division.
    String[] at = events.get(0).split(" at ")[1].split(" to ")[0].split(",");
    int x = Integer.parseInt(at[0]);
    int y = Integer.parseInt(at[1]);
    int dx = x - 14500;
    int dy = y - 23500;
    int length = FixedMath.isqrt(dx * dx + dy * dy);
    assertThat(events.get(0))
        .isEqualTo(
            String.format(
                "%d push true from 14500,23500 at %d,%d to %d,%d",
                knight.getId(), x, y, x + PUSHBACK * dx / length, y + PUSHBACK * dy / length));
  }

  @Test
  @DisplayName(
      "a pushing row in the filter form neither pushes a troop a hook holds with its movement off"
          + " nor switches its movement on, and still deals it its damage")
  void aPushingRowLeavesAHookedTroopAlone(@TempDir Path folder) throws IOException {
    // The scene of BattleHookedAreaPushTest: side 1's Fisherman hooks side 0's Knight and pulls
    // it, its movement off; the pushing Zap lands on it on the hold's fourth tick, where the scene
    // without the Zap shows it then.
    GameTables tables = pushingZap(folder);
    Standard1v1Battle dry = hookScene(tables);
    stepTo(dry, 231);
    CharacterEntity pulled = dry.getPlays().get(0).units().get(0);
    while (pulled.getView().getState() != GridEntityState.FOLLOWING_REMOVED
        && dry.getBattle().getTick() < 600) {
      dry.getBattle().step();
    }
    assertThat(pulled.getView().getState())
        .as("the hook")
        .isEqualTo(GridEntityState.FOLLOWING_REMOVED);
    int cast = dry.getBattle().getTick() + 3;
    stepTo(dry, cast);
    int x = pulled.getView().getX();
    int y = pulled.getView().getY();

    Standard1v1Battle match = hookScene(tables);
    List<String> events = pushEvents(match, 0);
    match.play(cast, match.getWorld().getRecords().card("Zap"), LEVEL, 1, x, y, "zap");
    stepTo(match, cast + 1);
    CharacterEntity knight = match.getPlays().get(0).units().get(0);
    assertThat(knight.getView().getState()).isEqualTo(GridEntityState.FOLLOWING_REMOVED);
    assertThat(events).containsExactly(knight.getId() + " hit 75 moving false");
    assertThat(knight.isActive(CharacterEntity.MOVEMENT_SLOT)).as("movement still off").isFalse();
    assertThat(knight.getUnit().movement().getPushbackInFlight()).isZero();
  }

  /**
   * The hook scene on the given tables: the towers fighting, the Knight and the Fisherman played.
   */
  private static Standard1v1Battle hookScene(GameTables tables) {
    Standard1v1Battle match = new Standard1v1Battle(tables, 1, true);
    match.getWorld().seed(1131);
    match.play(220, match.getWorld().getRecords().card("Knight"), 1, 0, 3500, 14000, "k");
    match.play(230, match.getWorld().getRecords().card("Fisherman"), 9, 1, 3500, 22000, "f");
    return match;
  }

  @Test
  @DisplayName(
      "a ticking row in the filter form hits on the step of its offset and every HitSpeed after"
          + " it, and stays until its countdown is below 0")
  void aTickingRowHitsOnItsSchedule(@TempDir Path folder) throws IOException {
    GameTables tables =
        filterForm(
            folder,
            "Poison",
            columns -> {
              poisonCircle(columns);
              columns.put("HitSpeedOffset", 250);
            });
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    List<Integer> updates = new ArrayList<>();
    List<Integer> buffed = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void areaEffectUpdated(
                  int tick,
                  AreaEffectEntity areaEffect,
                  int before,
                  int after,
                  int hits,
                  int radius,
                  List<Integer> dealt) {
                if (areaEffect.getData().name().equals("Poison")) {
                  updates.add(tick);
                }
              }

              @Override
              public void areaBuff(
                  int tick,
                  AreaEffectEntity areaEffect,
                  BuffData buff,
                  int time,
                  List<WorldEntity> targets) {
                for (WorldEntity target : targets) {
                  if (target.getData().name().equals("PrincessTower")) {
                    buffed.add(tick);
                  }
                }
              }
            });
    match.play(200, match.getWorld().getRecords().card("Poison"), LEVEL, 0, 14500, 25500, "poison");
    stepTo(match, 600);
    // 8000 ms of life: 161 updates, the last with the countdown at -50.
    assertThat(updates).hasSize(161);
    List<Integer> expected = new ArrayList<>();
    for (int k = 5; k <= 160; k += 5) {
      expected.add(updates.get(k));
    }
    assertThat(buffed).hasSize(32).isEqualTo(expected);
  }

  /**
   * Records, in order, what a filter form row named {@code row} does to each object of side 1 it
   * lists: the hit action it schedules on it, the damage it deals it (any area effect's typed hit
   * on side 1, the only one the scenes cast) and the buff it applies to it, each with its tick and
   * the object's row name.
   */
  private static List<String> hitEvents(Standard1v1Battle match, String row) {
    List<String> events = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void onHitActionScheduled(
                  int tick, AreaEffectEntity areaEffect, WorldEntity target, BattleAction action) {
                if (areaEffect.getData().name().equals(row) && target.side() == 1) {
                  events.add(tick + " action " + action.name() + " " + target.getData().name());
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
                // An area effect's typed hit has no character as its source.
                if (source == null && target.side() == 1) {
                  events.add(tick + " hit " + amount + " " + target.getData().name());
                }
              }

              @Override
              public void areaBuff(
                  int tick,
                  AreaEffectEntity areaEffect,
                  BuffData buff,
                  int time,
                  List<WorldEntity> targets) {
                for (WorldEntity target : targets) {
                  if (areaEffect.getData().name().equals(row) && target.side() == 1) {
                    events.add(tick + " buff " + target.getData().name());
                  }
                }
              }
            });
    return events;
  }

  /** The events of the given kind, without their ticks. */
  private static List<String> ofKind(List<String> events, String kind) {
    List<String> out = new ArrayList<>();
    for (String event : events) {
      String rest = event.substring(event.indexOf(' ') + 1);
      if (rest.startsWith(kind + " ")) {
        out.add(rest);
      }
    }
    return out;
  }

  /**
   * The configured tables with Poison rewritten in the filter form with a damage, its buff and the
   * Goblin Curse's group of buff spawns as its hit action, then edited.
   */
  private static GameTables poisonWithHitAction(Path folder, Consumer<ObjectNode> edit)
      throws IOException {
    return filterForm(
        folder,
        "Poison",
        columns -> {
          poisonCircle(columns);
          columns.putObject("Damage").put("BaseDamage", 10);
          columns.put("HitSpeedOffset", 250);
          columns.put("OnHitAction", "GoblinCurseCreateBuffs");
          edit.accept(columns);
        });
  }

  @Test
  @DisplayName(
      "a filter form row's hit action is built for each object it lists and scheduled on it on"
          + " every hit, after its damage and before its buff, the area effect the cause")
  void theHitActionGoesOnEveryListedObjectOnEveryHit(@TempDir Path folder) throws IOException {
    Standard1v1Battle match =
        new Standard1v1Battle(poisonWithHitAction(folder, columns -> {}), LEVEL, false);
    List<String> events = hitEvents(match, "Poison");
    List<String> cursed = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void buffSpawned(
                  int tick,
                  WorldEntity owner,
                  String action,
                  BuffData buff,
                  int time,
                  int packedLevel,
                  SpawnHost source) {
                if (owner.getData().name().equals("PrincessTower") && owner.side() == 1) {
                  cursed.add(buff.name());
                }
              }
            });
    match.play(200, match.getWorld().getRecords().card("Poison"), LEVEL, 0, 14500, 25500, "poison");
    stepTo(match, 600);
    // 32 hits on the tower, each scheduling the group on it in the update, after its damage is
    // queued and before its buff; the damage lands at the tick's drain.
    assertThat(ofKind(events, "action"))
        .hasSize(32)
        .containsOnly("action GoblinCurseCreateBuffs PrincessTower");
    assertThat(ofKind(events, "hit")).hasSize(32);
    assertThat(ofKind(events, "buff")).hasSize(32);
    String first = events.get(0).split(" ")[0];
    assertThat(events.subList(0, 3))
        .containsExactly(
            first + " action GoblinCurseCreateBuffs PrincessTower",
            first + " buff PrincessTower",
            first + " hit 10 PrincessTower");
    // The group's buff spawns run on the tower.
    assertThat(cursed).contains("GoblinCurse");
  }

  @Test
  @DisplayName(
      "a filter form row that hits each object once passes by an object it has reached: no damage,"
          + " no hit action and no buff on any later hit")
  void oneHitPerTargetPassesByAReachedObject(@TempDir Path folder) throws IOException {
    Standard1v1Battle match =
        new Standard1v1Battle(
            poisonWithHitAction(folder, columns -> columns.put("OneHitPerTarget", true)),
            LEVEL,
            false);
    List<String> events = hitEvents(match, "Poison");
    match.play(200, match.getWorld().getRecords().card("Poison"), LEVEL, 0, 14500, 25500, "poison");
    stepTo(match, 600);
    assertThat(ofKind(events, "action"))
        .containsExactly("action GoblinCurseCreateBuffs PrincessTower");
    assertThat(ofKind(events, "hit")).containsExactly("hit 10 PrincessTower");
    assertThat(ofKind(events, "buff")).containsExactly("buff PrincessTower");
  }

  @Test
  @DisplayName(
      "a filter form row that expires on its trigger ends with the first object it hits: the"
          + " nearest takes its damage, the rest of its list nothing, and it leaves after the"
          + " update")
  void expireOnTriggerEndsItWithTheFirstHit(@TempDir Path folder) throws IOException {
    GameTables tables =
        filterForm(
            folder,
            "Zap",
            columns -> {
              zapCircle(columns);
              columns.remove("Buff");
              columns.putObject("Damage").put("BaseDamage", 75);
              columns.put("LifeDuration", 10000);
              columns.put("HitSpeed", 300);
              columns.put("ExpireOnTrigger", true);
            });
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    List<String> events = hitEvents(match, "Zap");
    List<Integer> removed = new ArrayList<>();
    List<Integer> updated = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void areaEffectUpdated(
                  int tick,
                  AreaEffectEntity areaEffect,
                  int before,
                  int after,
                  int hits,
                  int radius,
                  List<Integer> dealt) {
                if (areaEffect.getData().name().equals("Zap")) {
                  updated.add(tick);
                }
              }

              @Override
              public void areaEffectRemoved(int tick, AreaEffectEntity areaEffect) {
                if (areaEffect.getData().name().equals("Zap")) {
                  removed.add(tick);
                }
              }
            });
    match.play(190, match.getWorld().getRecords().card("Knight"), LEVEL, 1, 14500, 22500, "knight");
    match.play(200, match.getWorld().getRecords().card("Zap"), LEVEL, 0, 14500, 23500, "zap");
    stepTo(match, 400);
    // Its first update hits: the Knight, nearest, takes the damage; the tower, listed after it,
    // nothing; and it is removed at the cleanup after that update.
    assertThat(ofKind(events, "hit")).containsExactly("hit 75 Knight");
    assertThat(updated).hasSize(1);
    assertThat(removed).containsExactly(updated.get(0));
  }

  @Test
  @DisplayName(
      "a filter form row's hit action on itself runs once a hit, with the first object it hits,"
          + " however many it lists")
  void theSelfActionRunsOnceAHit(@TempDir Path folder) throws IOException {
    GameTables tables =
        filterForm(
            folder,
            "Zap",
            columns -> {
              zapCircle(columns);
              columns.remove("Buff");
              columns.putObject("Damage").put("BaseDamage", 75);
              columns.put("OnHitSelfAction", "rage_barbarian_spawn_bottle");
            });
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    List<String> events = hitEvents(match, "Zap");
    List<String> spawned = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void characterSpawned(
                  int tick, SpawnHost source, CharacterEntity child, int createdX, int createdY) {
                if (child.getData().name().equals("RageBarbarianBottle")) {
                  spawned.add(child.side() + " at " + createdX + "," + createdY);
                }
              }
            });
    match.play(190, match.getWorld().getRecords().card("Knight"), LEVEL, 1, 14500, 22500, "knight");
    match.play(200, match.getWorld().getRecords().card("Zap"), LEVEL, 0, 14500, 23500, "zap");
    stepTo(match, 300);
    assertThat(ofKind(events, "hit")).containsExactly("hit 75 Knight", "hit 75 PrincessTower");
    // One spawn for the two objects hit, its cause the first: the spawn takes its cause's side
    // and stands where it stood.
    assertThat(spawned).hasSize(1);
    assertThat(spawned.get(0)).startsWith("1 at 14500,");
  }

  /**
   * Records each pull a Tornado in the filter form makes: per hit, the units it pulled in order,
   * each with the vector to the centre and its push accumulators before and after.
   */
  private static List<List<AreaEffectEntity.Pull>> pulls(Standard1v1Battle match) {
    List<List<AreaEffectEntity.Pull>> pulls = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void areaPulled(
                  int tick, AreaEffectEntity areaEffect, List<AreaEffectEntity.Pull> pulled) {
                pulls.add(List.copyOf(pulled));
              }
            });
    return pulls;
  }

  /** The speed the Tornado scenes write into the Knight's row. */
  private static final int KNIGHT_SPEED = 60;

  /**
   * The configured tables with the Tornado in the filter form, its circle and timings written - a
   * radius of 5500, a hit every step from 50 ms for 1050 ms - then edited; its buff pulling by
   * PushSpeedFactor 100 and AttractPercentage 360 alone, and the Knight's row at {@link
   * #KNIGHT_SPEED}.
   */
  private static GameTables tornadoForm(Path folder, Consumer<ObjectNode> edit) throws IOException {
    filterForm(
        folder,
        "Tornado",
        columns -> {
          columns.put("Radius", 5500);
          columns.put("HitSpeed", 50);
          columns.put("HitSpeedOffset", 50);
          columns.put("LifeDuration", 1050);
          edit.accept(columns);
        });
    GameData.alterLoaded(
        folder,
        "character_buffs",
        rows -> {
          ObjectNode buff = GameData.columns(rows, "Tornado");
          buff.put("PushSpeedFactor", 100);
          buff.put("AttractPercentage", 360);
          buff.remove(List.of("PushMassFactor", "LateralPushPercentage", "AttractMaxAngle"));
        });
    GameData.alterLoaded(
        folder, "characters", rows -> GameData.columns(rows, "Knight").put("Speed", KNIGHT_SPEED));
    return GameTables.load(folder);
  }

  /** The Tornado's point, on the top side's left, 4000 in front of its princess tower. */
  private static final int TORNADO_X = 3500;

  private static final int TORNADO_Y = 21500;

  /**
   * Places a Tornado of side 0 on tick 25, once the units of the first tick have deployed, and
   * steps through its first hit: its row's HitSpeedOffset of 50 puts it on the step after its first
   * update.
   */
  private static void tornado(Standard1v1Battle match) {
    match.placeAreaEffect(25, "Tornado", LEVEL, 0, TORNADO_X, TORNADO_Y, "Tornado");
    stepTo(match, 27);
  }

  @Test
  @DisplayName(
      "a filter form row whose buff attracts pulls each enemy troop it lists toward its point on"
          + " every hit, before its buff; a friendly troop and a building are left alone")
  void anAttractingBuffPullsEachListedTroop(@TempDir Path folder) throws IOException {
    Standard1v1Battle match = new Standard1v1Battle(tornadoForm(folder, c -> {}), 1, false);
    UnitData knightRow = match.getWorld().getRecords().unit("Knight");
    CharacterEntity near =
        match.deploy(0, knightRow, LEVEL, 1, TORNADO_X + 2000, TORNADO_Y, "near");
    CharacterEntity far =
        match.deploy(0, knightRow, LEVEL, 1, TORNADO_X + 3500, TORNADO_Y + 1500, "far");
    match.deploy(0, knightRow, LEVEL, 0, TORNADO_X - 1500, TORNADO_Y - 1500, "friend");
    CharacterEntity cannon =
        match.deploy(
            0, match.getWorld().getRecords().unit("Cannon"), LEVEL, 1, TORNADO_X, TORNADO_Y - 2000);
    List<List<AreaEffectEntity.Pull>> pulls = pulls(match);
    tornado(match);

    assertThat(pulls).hasSize(1);
    assertThat(pulls.get(0)).extracting(AreaEffectEntity.Pull::target).containsExactly(near, far);
    for (AreaEffectEntity.Pull pull : pulls.get(0)) {
      // The buff push: PushSpeedFactor 100 and AttractPercentage 360 of the Knight's written
      // Speed 60 give 216, along the way to the centre, C division; one more sample, the cap
      // lifted.
      int push = KNIGHT_SPEED * 360 / 100 * 100 / 100;
      int length = FixedMath.isqrt(pull.dx() * pull.dx() + pull.dy() * pull.dy());
      assertThat(pull.after()[0] - pull.before()[0]).isEqualTo(push * pull.dx() / length);
      assertThat(pull.after()[1] - pull.before()[1]).isEqualTo(push * pull.dy() / length);
      assertThat(pull.after()[2] - pull.before()[2]).isEqualTo(1);
      assertThat(pull.after()[4]).isEqualTo(1);
      assertThat(pull.dx()).isEqualTo(TORNADO_X - pull.target().getView().getX());
      assertThat(pull.dy()).isEqualTo(TORNADO_Y - pull.target().getView().getY());
    }
    // The buff is applied after the pull, to the building too, which nothing pulls.
    assertThat(near.getBuffs().carries("Tornado")).isTrue();
    assertThat(cannon.getBuffs().carries("Tornado")).isTrue();
  }

  @Test
  @DisplayName(
      "a filter form row whose buff attracts pulls only the objects its hit takes: with a target"
          + " limit of one, the nearest alone")
  void thePullFollowsTheTargetLimit(@TempDir Path folder) throws IOException {
    Standard1v1Battle match =
        new Standard1v1Battle(
            tornadoForm(folder, columns -> columns.put("MaximumTargets", 1)), 1, false);
    UnitData knightRow = match.getWorld().getRecords().unit("Knight");
    CharacterEntity near =
        match.deploy(0, knightRow, LEVEL, 1, TORNADO_X + 2000, TORNADO_Y, "near");
    CharacterEntity far =
        match.deploy(0, knightRow, LEVEL, 1, TORNADO_X + 3500, TORNADO_Y + 1500, "far");
    List<List<AreaEffectEntity.Pull>> pulls = pulls(match);
    tornado(match);
    stepTo(match, 31);

    assertThat(pulls).hasSize(5);
    for (List<AreaEffectEntity.Pull> hit : pulls) {
      assertThat(hit).extracting(AreaEffectEntity.Pull::target).containsExactly(near);
    }
    assertThat(far.getBuffs().carries("Tornado")).isFalse();
  }

  /** The pushback of the lasting pushing area the flag scenes cast, as the guard's charge has. */
  private static final int LONG_PUSHBACK = 2500;

  /**
   * The configured tables with Zap rewritten in the filter form as a lasting pushing area: a hit
   * every step for 500 ms, each object hit once, a base damage, a long pushback, the given push
   * columns set and no buff.
   */
  private static GameTables lastingPushZap(Path folder, String... flags) throws IOException {
    return filterForm(
        folder,
        "Zap",
        columns -> {
          zapCircle(columns);
          columns.remove("Buff");
          columns.put("LifeDuration", 500);
          columns.put("HitSpeed", 50);
          columns.put("OneHitPerTarget", true);
          columns.putObject("Damage").put("BaseDamage", 75);
          columns.put("Pushback", LONG_PUSHBACK);
          for (String flag : flags) {
            columns.put(flag, true);
          }
        });
  }

  /** Where the flag scenes cast their Zap: about 800 ahead of where the Knight walks to by then. */
  private static final int ZAP_Y = 21500;

  /** A side 1 Knight walking down the right lane into a lasting pushing Zap of side 0. */
  private static List<String> knightUnderLastingZap(GameTables tables) {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    List<String> events = pushEvents(match, 1);
    match.play(150, match.getWorld().getRecords().card("Knight"), LEVEL, 1, 14500, 22500, "k");
    match.play(200, match.getWorld().getRecords().card("Zap"), LEVEL, 0, 14500, ZAP_Y, "zap");
    stepTo(match, 300);
    return events;
  }

  @Test
  @DisplayName(
      "a row in the filter form that hits each object once and pushes continuously and relatively"
          + " pushes a troop it reached again on every update, to the edge of its pushback circle,"
          + " and deals it its damage once")
  void aContinuousRelativeRowPushesAReachedTroopAgain(@TempDir Path folder) throws IOException {
    List<String> events =
        knightUnderLastingZap(
            lastingPushZap(folder, "RelativePushback", "ContinuousPushback", "PushbackAll"));
    List<String> hits = events.stream().filter(e -> e.contains(" hit ")).toList();
    List<String> pushes = events.stream().filter(e -> e.contains(" push ")).toList();
    assertThat(hits).hasSize(1);
    assertThat(hits.get(0)).endsWith(" hit 75 moving true");
    assertThat(pushes).hasSizeGreaterThan(1);
    // The first push comes before the hit, and aims the pushback less the separation from where
    // the Knight stands, straight away from the cast point: the edge of the circle.
    assertThat(events.get(0)).contains(" push true ");
    String[] at = events.get(0).split(" at ")[1].split(" to ")[0].split(",");
    int x = Integer.parseInt(at[0]);
    int y = Integer.parseInt(at[1]);
    int dx = x - 14500;
    int dy = y - ZAP_Y;
    int length = FixedMath.isqrt(dx * dx + dy * dy);
    int distance = LONG_PUSHBACK - length;
    assertThat(distance).isPositive().isLessThan(LONG_PUSHBACK);
    assertThat(events.get(0))
        .endsWith(
            String.format(
                " push true from 14500,%d at %d,%d to %d,%d",
                ZAP_Y, x, y, x + distance * dx / length, y + distance * dy / length));
  }

  @Test
  @DisplayName(
      "a row in the filter form that hits each object once without ContinuousPushback passes a"
          + " troop it reached by: one push and one hit")
  void aRowWithoutContinuousPushbackPushesOnce(@TempDir Path folder) throws IOException {
    List<String> events = knightUnderLastingZap(lastingPushZap(folder));
    assertThat(events).hasSize(2);
    assertThat(events.get(0)).contains(" push true ");
    assertThat(events.get(1)).endsWith(" hit 75 moving true");
  }

  /** A side 1 P.E.K.K.A., whose row ignores pushback, walking into a pushing Zap of side 0. */
  private static List<String> pekkaUnderZap(GameTables tables) {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    List<String> events = pushEvents(match, 1);
    match.deploy(150, match.getWorld().getRecords().unit("Pekka"), LEVEL, 1, 14500, 22500, "p");
    match.play(200, match.getWorld().getRecords().card("Zap"), LEVEL, 0, 14500, ZAP_Y, "zap");
    stepTo(match, 300);
    return events.stream().filter(e -> e.contains(" push ")).toList();
  }

  @Test
  @DisplayName(
      "a row in the filter form with PushbackAll pushes a troop whose row ignores pushback; one"
          + " without it is refused")
  void pushbackAllLiftsTheGates(@TempDir Path lifted, @TempDir Path gated) throws IOException {
    assertThat(pekkaUnderZap(lastingPushZap(lifted, "PushbackAll")))
        .first()
        .asString()
        .contains(" push true ");
    assertThat(pekkaUnderZap(lastingPushZap(gated))).first().asString().contains(" push false ");
  }
}
