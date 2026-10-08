package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.PackedLevel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Clone where the reference runs leave it: whom its hit reaches, the clone each unit gets - its
 * level, its 1 hit point and its shield, its buffs - and the move that sets the two apart, a clone
 * never cloned again and its death spawns clones too; and the clones the battle refuses. Then the
 * area effects with a buff over clones where the runs do not take them, written in the hit-switch
 * form: a Rage and a Heal Spirit's area beside a Minion's clone, a Zap, a Tornado's pull, and an
 * Earthquake's air test after the buff test.
 *
 * <p>Each scene writes the circles, timings and buff columns it counts on into the rows: the
 * Clone's circle of 3000 for 1000 ms and its clone buff of 500 ms, and those of the area effects
 * below.
 */
class BattleCloneTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** A tick after every unit placed on the first one has deployed. */
  private static final int CAST_TICK = 25;

  /** The Clone's point, on the bottom side's left, at a cell's centre and away from every tower. */
  private static final int X = 3250;

  private static final int Y = 11250;

  @TempDir static Path tablesFolder;

  /** The configured tables with the Clone's columns written. */
  private static GameTables cloneTables;

  @BeforeAll
  static void writeTheClone() throws IOException {
    cloneTables = withClone(tablesFolder.resolve("clone"), rows -> {});
  }

  /**
   * The configured tables with the Clone written as the scenes count on it - a circle of 3000 for
   * 1000 ms, and the clone buff its hit spawns for 500 ms - and the area effects' rows then edited.
   *
   * @param folder the folder the tables are copied into
   * @param areas what is done to the area effects' rows once the Clone's are written
   */
  private static GameTables withClone(Path folder, Consumer<ObjectNode> areas) throws IOException {
    Files.createDirectories(folder);
    GameData.altered(
        folder,
        "area_effect_objects",
        rows -> {
          GameData.columns(rows, "Clone").put("Radius", 3000).put("LifeDuration", 1000);
          areas.accept(rows);
        });
    GameData.alterLoaded(
        folder,
        "actions",
        rows -> ((ObjectNode) rows.get("SpawnCloneBufAction").get("fields")).put("SpawnTime", 500));
    return GameTables.load(folder);
  }

  /** A battle with the towers passive that logs what every Clone does. */
  private static final class Scene {
    final Standard1v1Battle match;
    final List<String> scheduled = new ArrayList<>();
    final List<CharacterEntity> clones = new ArrayList<>();
    final List<String> refused = new ArrayList<>();
    final List<String> moves = new ArrayList<>();
    final List<String> copied = new ArrayList<>();
    final List<String> gate = new ArrayList<>();
    AreaEffectEntity area;
    int tick;

    Scene() {
      this(cloneTables);
    }

    Scene(GameTables tables) {
      match = new Standard1v1Battle(tables, LEVEL, false);
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void areaEffectCreated(
                    int t, AreaEffectEntity a, String how, String source) {
                  area = a;
                }

                @Override
                public void onHitActionScheduled(
                    int t, AreaEffectEntity a, WorldEntity target, BattleAction action) {
                  scheduled.add(target.name());
                }

                @Override
                public void cloned(
                    int t,
                    CharacterEntity original,
                    CharacterEntity clone,
                    SpawnHost instigator,
                    List<Integer> registrationVisits) {
                  clones.add(clone);
                }

                @Override
                public void cloneRefused(
                    int t, WorldEntity original, String reason, SpawnHost instigator) {
                  refused.add(original.name() + " " + reason);
                }

                @Override
                public void cloneMoveStarted(int t, CharacterEntity unit, int x, int y) {
                  moves.add(unit.name() + " to " + x + " " + y);
                }

                @Override
                public void buffCopied(
                    int t, WorldEntity original, WorldEntity clone, BuffInstance copy) {
                  copied.add(
                      clone.name() + " " + copy.getBuff().name() + " " + copy.getRemaining());
                }

                @Override
                public void cloneBuffGateAsked(
                    int t,
                    AreaEffectEntity a,
                    String buff,
                    CharacterEntity clone,
                    String path,
                    int query,
                    boolean refused) {
                  gate.add(
                      "%d %s %s %s %d %b".formatted(t, clone.name(), buff, path, query, refused));
                }
              });
    }

    /** A unit placed at a tick that never moves on its own, under a name of its own. */
    CharacterEntity still(int at, int side, String row, int x, int y, String name) {
      return still(at, side, row, LEVEL, x, y, name);
    }

    CharacterEntity still(int at, int side, String row, int level, int x, int y, String name) {
      CharacterEntity unit = match.deploy(at, GameData.unit(row), level, side, x, y, name);
      unit.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      return unit;
    }

    /** Casts a Clone for side 0 at the point on a tick and steps through that tick. */
    void clone(int at) {
      match.placeAreaEffect(at, "Clone", LEVEL, 0, X, Y, "C");
      step(at + 1 - tick);
    }

    void step(int ticks) {
      for (int i = 0; i < ticks; i++) {
        match.getBattle().step();
        tick++;
      }
    }

    CharacterEntity named(String name) {
      for (BattleEntity entity : match.getBattle().getHolder().entities()) {
        if (entity instanceof CharacterEntity c && c.name().equals(name)) {
          return c;
        }
      }
      throw new IllegalArgumentException(name);
    }

    List<String> cloneNames() {
      return clones.stream().map(CharacterEntity::name).toList();
    }
  }

  @Test
  @DisplayName(
      "its hit clones its own troops in its circle, air ones too, but no building, no enemy and no"
          + " unit that a Clone passes by")
  void whoIsCloned() {
    Scene scene = new Scene();
    scene.still(0, 0, "Knight", X + 1000, Y, "knight");
    scene.still(0, 0, "Minion", X, Y + 1000, "minion");
    scene.still(0, 0, "Cannon", X - 1500, Y - 1000, "cannon");
    scene.still(0, 1, "Knight", X - 1000, Y, "enemy");
    scene.still(0, 0, "Recruit_Chess", X, Y - 1000, "chess");
    scene.still(0, 0, "Knight", X + 4000, Y, "far");
    scene.clone(CAST_TICK);

    // The Clone's filter lists its own side's characters but buildings and clones; its hit action
    // is scheduled on each, and its perform passes by the unit a Clone ignores.
    assertThat(scene.scheduled).containsExactlyInAnyOrder("knight", "minion", "chess");
    assertThat(scene.cloneNames()).containsExactlyInAnyOrder("knight_clone0", "minion_clone0");
    assertThat(scene.refused).containsExactly("chess ignore clone");
  }

  @Test
  @DisplayName(
      "a clone is made on its unit at the Clone's level with 1 hit point of 1, set up as a clone"
          + " with its targeting off, and carries a copy of the Clone's buff its unit took")
  void theClone() {
    Scene scene = new Scene();
    // A lower level than the Clone's, which the clone does not keep.
    CharacterEntity knight = scene.still(0, 0, "Knight", 8, X, Y, "knight");
    scene.clone(CAST_TICK);

    CharacterEntity clone = scene.named("knight_clone0");
    assertThat(clone.isClone()).isTrue();
    assertThat(knight.isClone()).isFalse();
    assertThat(clone.getData().name()).isEqualTo("Knight");
    assertThat(clone.side()).isZero();
    assertThat(PackedLevel.level(clone.getPackedLevel())).isEqualTo(LEVEL);
    assertThat(clone.getHitPoints().getHitPoints()).isEqualTo(1);
    assertThat(clone.getHitPoints().getMaximum()).isEqualTo(1);
    assertThat(clone.getView().getState()).isEqualTo(GridEntityState.CLONE_SETUP);
    assertThat(knight.getView().getState()).isEqualTo(GridEntityState.CLONE_SETUP);
    assertThat(clone.isActive(CharacterEntity.TARGETING_SLOT)).isFalse();
    assertThat(knight.getBuffs().carries("Clone")).isTrue();
    assertThat(scene.copied).containsExactly("knight_clone0 Clone 500");
  }

  @Test
  @DisplayName(
      "a clone's shield is 1 of 1 while its unit's is up, and none of 1 once its unit's is broken")
  void theShield() {
    Scene scene = new Scene();
    scene.still(0, 0, "Recruit", X + 1000, Y, "up");
    CharacterEntity broken = scene.still(0, 0, "Recruit", X - 1000, Y, "broken");
    scene.step(CAST_TICK);
    broken.getHitPoints().setShield(0);
    scene.clone(CAST_TICK);

    CharacterEntity up = scene.named("up_clone0");
    assertThat(up.getHitPoints().getShield()).isEqualTo(1);
    assertThat(up.getHitPoints().getShieldMaximum()).isEqualTo(1);
    CharacterEntity none = scene.named("broken_clone0");
    assertThat(none.getHitPoints().getShield()).isZero();
    assertThat(none.getHitPoints().getShieldMaximum()).isEqualTo(1);
    assertThat(none.getHitPoints().getHitPoints()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "the clone moves back toward its own side and its unit forward, 125 a visit for ten visits,"
          + " and both are resumed as the move ends")
  void theMoveApart() {
    Scene scene = new Scene();
    CharacterEntity knight = scene.still(0, 0, "Knight", X, Y, "knight");
    knight.setActive(CharacterEntity.MOVEMENT_SLOT, true);
    scene.clone(CAST_TICK);
    CharacterEntity clone = scene.named("knight_clone0");
    assertThat(scene.moves).containsExactly("knight_clone0 to 3250 0", "knight to 3250 31999");

    // Both start where the Knight stood as the Clone reached it.
    int fromY = clone.getView().getY();
    assertThat(knight.getView().getY()).isEqualTo(fromY);
    scene.step(9);
    assertThat(clone.getView().getY()).isEqualTo(fromY - 9 * 125);
    assertThat(knight.getView().getY()).isEqualTo(fromY + 9 * 125);
    assertThat(clone.getView().getState()).isEqualTo(GridEntityState.CLONE_SETUP);
    scene.step(1);
    assertThat(clone.getView().getY()).isEqualTo(fromY - 10 * 125);
    assertThat(knight.getView().getY()).isEqualTo(fromY + 10 * 125);
    assertThat(clone.getView().getX()).isEqualTo(X);
    assertThat(clone.getView().getState()).isNotEqualTo(GridEntityState.CLONE_SETUP);
    assertThat(knight.getView().getState()).isNotEqualTo(GridEntityState.CLONE_SETUP);
    assertThat(clone.getUnit().movement().getExplicitX()).isEqualTo(-1);
  }

  @Test
  @DisplayName(
      "a second Clone clones the units again and passes their clones by; a clone the action reaches"
          + " directly is refused by its perform")
  void noCloneOfAClone() {
    Scene scene = new Scene();
    scene.still(0, 0, "Knight", X, Y, "knight");
    scene.clone(CAST_TICK);
    CharacterEntity clone = scene.named("knight_clone0");
    scene.step(15);
    scene.scheduled.clear();
    scene.clone(scene.tick);

    assertThat(scene.scheduled).containsExactly("knight");
    assertThat(scene.cloneNames()).containsExactly("knight_clone0", "knight_clone1");

    BattleAction row =
        GameData.actions().build("CloneAction", scene.match.getWorld().binding(clone));
    clone.actionHolder().schedule(row, ActionHolder.OWN_DELAY, true, scene.area.actionHolder());
    assertThat(scene.refused).containsExactly("knight_clone0 is clone");
  }

  @Test
  @DisplayName("a clone's death spawns are clones of 1 hit point")
  void theDeathSpawnsOfAClone() {
    Scene scene = new Scene();
    scene.still(0, 0, "Golem", X, Y, "golem");
    scene.step(CAST_TICK + 60);
    scene.clone(scene.tick);
    CharacterEntity clone = scene.named("golem_clone0");
    scene.match.getWorld().kill(clone, null);
    scene.step(1);

    for (String child : List.of("golem_clone0_0", "golem_clone0_1")) {
      CharacterEntity golemite = scene.named(child);
      assertThat(golemite.isClone()).as(child).isTrue();
      assertThat(golemite.getHitPoints().getMaximum()).as(child).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("a unit still deploying as the Clone reaches it is refused")
  void aDeployingUnitIsRefused() {
    Scene scene = new Scene();
    scene.still(CAST_TICK, 0, "Knight", X, Y, "late");

    assertThatThrownBy(() -> scene.clone(CAST_TICK + 2))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("deploy countdown");
  }

  @Test
  @DisplayName("an area effect spawned with a clone as its cause is refused")
  void anAreaEffectSpawnedFromAClone() {
    Scene scene = new Scene();
    scene.still(0, 0, "Knight", X, Y, "knight");
    scene.clone(CAST_TICK);
    CharacterEntity clone = scene.named("knight_clone0");
    BattleAction spawn =
        GameData.actions().build("GoblinCurseCore", scene.match.getWorld().binding(clone));

    assertThatThrownBy(() -> clone.actionHolder().start(spawn, clone.actionHolder()))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("from a clone");
  }

  /**
   * The configured tables with the five area effects below written in the hit-switch form, as an
   * area effect without a filter writes them: its hit switches and its own-side or enemy switch in
   * place of its filter, a number damage and a crown tower percent in place of the Zap's damage
   * table, one buff a hit, the Earthquake reaching hidden units and starting no area of its own,
   * and the Tornado hitting from its first update. Every 16.402.18 area effect is in the filter
   * form, whose hit pass lists what it reaches through its filter and never asks the clone test;
   * these hold the hit-switch walk's test, which the battle keeps. Their circles and timings, and
   * their buffs' heal, damage and hit frequency, are written as the scenes count on them.
   */
  private static GameTables hitSwitches(Path folder) throws IOException {
    withClone(
        folder,
        rows -> {
          for (String row : List.of("Rage", "HealSpirit", "Zap", "Tornado", "Earthquake")) {
            ObjectNode columns = GameData.columns(rows, row);
            columns.remove("Filter");
            columns.put("HitsGround", true);
            if (!row.equals("Earthquake")) {
              columns.put("HitsAir", true);
            }
            if (row.equals("Rage") || row.equals("HealSpirit")) {
              columns.put("OnlyOwnTroops", true);
            } else {
              columns.put("OnlyEnemies", true);
            }
            if (!row.equals("Zap") && !row.equals("Tornado")) {
              columns.put("BuffNumber", 1);
            }
          }
          GameData.columns(rows, "HealSpirit").put("IgnoreBuildings", true);
          GameData.columns(rows, "Zap").put("Damage", 75).put("CrownTowerDamagePercent", -70);
          GameData.columns(rows, "Tornado").remove("HitSpeedOffset");
          ObjectNode earthquake = GameData.columns(rows, "Earthquake");
          earthquake.put("AffectsHidden", true);
          earthquake.remove("OnStartingAction");
          GameData.columns(rows, "Rage")
              .put("Radius", 3000)
              .put("HitSpeed", 300)
              .put("LifeDuration", 4500)
              .put("BuffTime", 1000);
          GameData.columns(rows, "HealSpirit")
              .put("Radius", 2500)
              .put("LifeDuration", 1000)
              .put("BuffTime", 1000);
          GameData.columns(rows, "Zap").put("Radius", 2500).put("LifeDuration", 1);
          GameData.columns(rows, "Tornado")
              .put("Radius", 5500)
              .put("HitSpeed", 50)
              .put("LifeDuration", 1050)
              .put("BuffTime", 500);
          earthquake
              .put("Radius", 3500)
              .put("HitSpeed", 100)
              .put("LifeDuration", 3000)
              .put("BuffTime", 1000);
        });
    GameData.alterLoaded(
        folder,
        "character_buffs",
        rows -> {
          GameData.columns(rows, "HealSpiritBuff")
              .put("HealPerSecond", 157)
              .put("HitFrequency", 250);
          GameData.columns(rows, "Tornado").put("DamagePerSecond", 60).put("HitFrequency", 550);
          GameData.columns(rows, "Earthquake").put("DamagePerSecond", 32).put("HitFrequency", 1000);
        });
    return GameTables.load(folder);
  }

  /**
   * A Knight and a Minion beside it cloned, and an area effect placed over them on the next tick:
   * for side 0 when it buffs its own troops, for side 1 otherwise.
   */
  private static Scene clonesUnder(Path folder, String row, int side) throws IOException {
    Scene scene = new Scene(hitSwitches(folder));
    scene.still(0, 0, "Knight", X, Y, "knight");
    scene.still(0, 0, "Minion", X + 600, Y, "minion");
    scene.clone(CAST_TICK);
    scene.match.placeAreaEffect(scene.tick, row, LEVEL, side, X, Y, "A");
    return scene;
  }

  private static boolean present(Scene scene, String name) {
    return scene.match.getBattle().getHolder().entities().stream()
        .anyMatch(e -> e instanceof CharacterEntity c && c.name().equals(name));
  }

  @Test
  @DisplayName(
      "a Rage, whose buff heals nothing, buffs the clones as it buffs any unit: its test is asked of"
          + " each clone twice a hit, by the walk and again by the apply, and passes")
  void aRageBuffsTheClones(@TempDir Path folder) throws IOException {
    Scene scene = clonesUnder(folder, "Rage", 0);
    scene.step(6);

    assertThat(scene.gate)
        .containsExactly(
            "31 knight_clone0 Rage area_buff 0 false",
            "31 knight_clone0 Rage area_buff 0 false",
            "31 minion_clone0 Rage area_buff 0 false",
            "31 minion_clone0 Rage area_buff 0 false");
    assertThat(scene.named("knight_clone0").getBuffs().carries("Rage")).isTrue();
    assertThat(scene.named("minion_clone0").getBuffs().carries("Rage")).isTrue();
  }

  @Test
  @DisplayName(
      "a Heal Spirit's area, whose buff heals 157 a second, refuses the clones in its walk and buffs"
          + " their originals")
  void aHealingAreaRefusesTheClones(@TempDir Path folder) throws IOException {
    Scene scene = clonesUnder(folder, "HealSpirit", 0);
    scene.step(2);

    assertThat(scene.gate)
        .containsExactly(
            "26 knight_clone0 HealSpiritBuff area_buff 157 true",
            "26 minion_clone0 HealSpiritBuff area_buff 157 true");
    assertThat(scene.named("knight").getBuffs().carries("HealSpiritBuff")).isTrue();
    assertThat(scene.named("knight_clone0").getBuffs().carries("HealSpiritBuff")).isFalse();
    assertThat(scene.named("minion_clone0").getBuffs().carries("HealSpiritBuff")).isFalse();
  }

  @Test
  @DisplayName(
      "a Zap kills the clones with its damage, whose validator asks the test, and so does not stun"
          + " them: the buff's walk tests alive before it asks")
  void aZapKillsTheClonesBeforeItsBuff(@TempDir Path folder) throws IOException {
    Scene scene = clonesUnder(folder, "Zap", 1);
    scene.step(1);

    assertThat(scene.gate)
        .containsExactly(
            "26 knight_clone0 ZapFreeze area_damage 0 false",
            "26 minion_clone0 ZapFreeze area_damage 0 false");
    // Both left in the tick's closing cleanup, neither carrying the stun.
    assertThat(scene.clones)
        .allSatisfy(
            clone -> {
              assertThat(present(scene, clone.name())).isFalse();
              assertThat(clone.getBuffs().carries("ZapFreeze")).isFalse();
            });
  }

  @Test
  @DisplayName(
      "a Tornado pulls the clones before it buffs them, its pull asking the test first, and its"
          + " damage kills them")
  void aTornadoPullsTheClones(@TempDir Path folder) throws IOException {
    Scene scene = clonesUnder(folder, "Tornado", 1);
    scene.step(1);

    assertThat(scene.gate)
        .containsExactly(
            "26 knight_clone0 Tornado pull 0 false",
            "26 minion_clone0 Tornado pull 0 false",
            "26 knight_clone0 Tornado area_buff 0 false",
            "26 knight_clone0 Tornado area_buff 0 false",
            "26 minion_clone0 Tornado area_buff 0 false",
            "26 minion_clone0 Tornado area_buff 0 false");
    assertThat(scene.named("knight_clone0").getBuffs().carries("Tornado")).isTrue();
    scene.step(12);
    assertThat(present(scene, "knight_clone0")).isFalse();
    assertThat(present(scene, "minion_clone0")).isFalse();
  }

  @Test
  @DisplayName(
      "an Earthquake passes a ground clone and buffs it until its damage kills it; an air clone"
          + " passes the buff test and is refused by the air test after it, so it is asked once a"
          + " hit and never buffed")
  void anEarthquakeOnAGroundAndAnAirClone(@TempDir Path folder) throws IOException {
    Scene scene = clonesUnder(folder, "Earthquake", 1);
    scene.step(2);

    assertThat(scene.gate)
        .containsExactly(
            "27 knight_clone0 Earthquake area_buff 0 false",
            "27 knight_clone0 Earthquake area_buff 0 false",
            "27 minion_clone0 Earthquake area_buff 0 false");
    assertThat(scene.named("knight_clone0").getBuffs().carries("Earthquake")).isTrue();
    assertThat(scene.named("minion_clone0").getBuffs().carries("Earthquake")).isFalse();
    scene.step(30);
    assertThat(present(scene, "knight_clone0")).isFalse();
    assertThat(present(scene, "minion_clone0")).isTrue();
  }
}
