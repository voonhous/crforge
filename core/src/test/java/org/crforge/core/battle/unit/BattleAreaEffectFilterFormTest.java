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
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.math.FixedMath;
import org.crforge.core.pathfinding.move.MovementState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The area effect class of a newer data version has no hit switches: a row names a game object
 * filter, which alone chooses what it reaches, and writes its damage as a damage type, inline or
 * named, with an amount for anything and one for a crown tower. On an update a hit is due it lists
 * the objects in its circle that pass the filter, nearest first, and queues its damage on each,
 * then applies its buff to each. A hit is due on the step its HitSpeedOffset falls in and on every
 * step a whole number of HitSpeed steps after it, and the row stays one update longer than a row
 * with hit switches: until its countdown is below 0. Each scene plays a spell of the configured
 * tables rewritten in that form, with a filter written as the newer data writes it.
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
  private static GameTables filterForm(Path folder, String row, Consumer<ObjectNode> edit)
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

  /** Steps a battle until its counter shows the given tick. */
  private static void stepTo(Standard1v1Battle match, int tick) {
    while (match.getBattle().getTick() < tick) {
      match.getBattle().step();
    }
  }

  /** The princess tower of a side on the right lane. */
  private static WorldEntity rightPrincessTower(Standard1v1Battle match, int side) {
    for (WorldEntity entity : match.getWorld().present()) {
      if (entity.getData().name().equals("PrincessTower")
          && entity.side() == side
          && entity.getView().getX() == 14500) {
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
    match.play(190, match.getWorld().getRecords().card("Knight"), LEVEL, 1, 14500, 22500, "knight");
    match.play(200, match.getWorld().getRecords().card("Zap"), LEVEL, 0, 14500, 23500, "zap");
    stepTo(match, 200);
    WorldEntity tower = rightPrincessTower(match, 1);
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
    // The scene of BattleHookedAreaPushTest: side 1's Fisherman hooks side 0's Knight on tick 284
    // and pulls it, its movement off, until tick 293; the pushing Zap lands on it on tick 287.
    Standard1v1Battle match = new Standard1v1Battle(pushingZap(folder), 1, true);
    match.getWorld().seed(1131);
    match.play(220, match.getWorld().getRecords().card("Knight"), 1, 0, 3500, 14000, "k");
    match.play(230, match.getWorld().getRecords().card("Fisherman"), 9, 1, 3500, 22000, "f");
    List<String> events = pushEvents(match, 0);
    match.play(287, match.getWorld().getRecords().card("Zap"), LEVEL, 1, 3433, 20820, "zap");
    stepTo(match, 288);
    CharacterEntity knight = match.getPlays().get(0).units().get(0);
    assertThat(knight.getView().getState()).isEqualTo(GridEntityState.FOLLOWING_REMOVED);
    assertThat(events).containsExactly(knight.getId() + " hit 75 moving false");
    assertThat(knight.isActive(CharacterEntity.MOVEMENT_SLOT)).as("movement still off").isFalse();
    assertThat(knight.getUnit().movement().getPushbackInFlight()).isZero();
  }

  @Test
  @DisplayName(
      "a ticking row in the filter form hits on the step of its offset and every HitSpeed after"
          + " it, and stays until its countdown is below 0")
  void aTickingRowHitsOnItsSchedule(@TempDir Path folder) throws IOException {
    GameTables tables = filterForm(folder, "Poison", columns -> columns.put("HitSpeedOffset", 250));
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
}
