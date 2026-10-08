package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The hits a character counts and the buff the count reaches, and a buff's start and remove
 * actions, where the reference runs do not take them: the list of counts, a projectile's shooter, a
 * reflecting unit, the paths no reference holds, and the hooks on a refresh, an expiry, a cleanse
 * and a death.
 */
class BattleBuffAfterHitsTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** A battle with the towers passive, and every count and every buff hook it makes. */
  private static final class Scene {
    final Standard1v1Battle match;
    final List<String> counts = new ArrayList<>();
    final List<String> hooks = new ArrayList<>();
    final List<Integer> impacts = new ArrayList<>();
    int tick;

    Scene() {
      this(GameData.tables());
    }

    Scene(GameTables tables) {
      match = new Standard1v1Battle(tables, LEVEL, false);
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void hitCounted(
                    int t,
                    WorldEntity attacker,
                    WorldEntity target,
                    int before,
                    int after,
                    String buff,
                    int timeMs) {
                  counts.add(
                      "%d %s %s %d %d %s"
                          .formatted(t, attacker.name(), target.name(), before, after, buff));
                }

                @Override
                public void buffHookScheduled(
                    int t, WorldEntity carrier, BuffInstance buff, String action, boolean start) {
                  hooks.add(
                      "%d %s %s %s"
                          .formatted(t, carrier.name(), action, start ? "start" : "remove"));
                }

                @Override
                public void projectileImpacted(
                    int t,
                    ProjectileEntity projectile,
                    WorldEntity target,
                    int damage,
                    DamageResult result) {
                  impacts.add(t);
                }
              });
    }

    /** A unit placed on tick 0 that never moves, under a name of its own. */
    CharacterEntity still(int side, UnitData row, int x, int y, String name) {
      CharacterEntity unit = match.deploy(0, row, LEVEL, side, x, y, name);
      unit.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      return unit;
    }

    void step(int ticks) {
      for (int i = 0; i < ticks; i++) {
        match.getBattle().step();
        tick++;
      }
    }
  }

  /** A row with a list of buffs after so many hits. */
  private static UnitData withBuffAfterHits(
      String row, List<String> buffs, List<Integer> counts, List<Integer> timesMs) {
    return GameData.unit(row).toBuilder()
        .buffAfterHits(buffs)
        .buffAfterHitsCounts(counts)
        .buffAfterHitsTimesMs(timesMs)
        .build();
  }

  @Test
  @DisplayName(
      "with counts 2 and 4 the second hit applies the first buff and the fourth the second, and"
          + " the counter starts over")
  void ascendingCountsCycle() {
    Scene scene = new Scene();
    CharacterEntity barbarian =
        scene.still(
            0,
            withBuffAfterHits(
                "Barbarian_EV1",
                List.of("Rage", "Barbarian_EVO_Rage"),
                List.of(2, 4),
                List.of(1000, 3000)),
            3500,
            10000,
            "b");
    CharacterEntity knight = scene.still(1, GameData.unit("Knight"), 3500, 11000, "k");

    for (int i = 0; i < 5; i++) {
      barbarian.countHit(knight, true);
    }

    assertThat(scene.counts)
        .containsExactly(
            "0 b k 0 1 null",
            "0 b k 1 2 Rage",
            "0 b k 2 3 null",
            "0 b k 3 0 Barbarian_EVO_Rage",
            "0 b k 0 1 null");
    assertThat(barbarian.getBuffs().items())
        .extracting(i -> i.getBuff().name() + " " + i.getRemaining() + " " + i.getSource().name())
        .containsExactly("Rage 1000 b", "Barbarian_EVO_Rage 3000 b");
  }

  @Test
  @DisplayName(
      "with counts 3 and 1 the walk stops at the first until the third hit, and since the last is"
          + " never picked the counter never starts over")
  void countsOutOfOrderStopTheWalk() {
    Scene scene = new Scene();
    CharacterEntity barbarian =
        scene.still(
            0,
            withBuffAfterHits(
                "Barbarian_EV1",
                List.of("Rage", "Barbarian_EVO_Rage"),
                List.of(3, 1),
                List.of(1000, 3000)),
            3500,
            10000,
            "b");
    CharacterEntity knight = scene.still(1, GameData.unit("Knight"), 3500, 11000, "k");

    for (int i = 0; i < 5; i++) {
      barbarian.countHit(knight, true);
    }

    assertThat(scene.counts)
        .containsExactly(
            "0 b k 0 1 null",
            "0 b k 1 2 null",
            "0 b k 2 3 Rage",
            "0 b k 3 4 null",
            "0 b k 4 5 null");
    assertThat(barbarian.getHitCounter()).isEqualTo(5);
  }

  @Test
  @DisplayName("a list of buffs, counts and times of different lengths is refused")
  void listsOfDifferentLengthsAreRefused() {
    Scene scene = new Scene();
    CharacterEntity barbarian =
        scene.still(
            0,
            withBuffAfterHits("Barbarian_EV1", List.of("Rage"), List.of(1, 2), List.of(1000, 1000)),
            3500,
            10000,
            "b");
    CharacterEntity knight = scene.still(1, GameData.unit("Knight"), 3500, 11000, "k");

    assertThatThrownBy(() -> barbarian.countHit(knight, true))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("of different lengths");
  }

  @Test
  @DisplayName(
      "a projectile's impact counts for its shooter, not its launch, and applies the buff to the"
          + " shooter")
  void aProjectileCountsForItsShooter() {
    Scene scene = new Scene();
    CharacterEntity goblin =
        scene.still(
            0,
            withBuffAfterHits("SpearGoblin", List.of("Rage"), List.of(1), List.of(1000)),
            3500,
            10000,
            "g");
    scene.still(1, GameData.unit("Knight"), 3500, 14000, "k");
    while (scene.impacts.isEmpty() && scene.tick < 100) {
      scene.step(1);
    }

    assertThat(scene.impacts).isNotEmpty();
    assertThat(scene.counts).isNotEmpty();
    assertThat(scene.counts.get(0)).isEqualTo(scene.impacts.get(0) + " g k 0 0 Rage");
    assertThat(goblin.getBuffs().carries("Rage")).isTrue();
  }

  @Test
  @DisplayName("a reflected attack counts for the reflecting unit, with the attacker as its target")
  void aReflectCountsForTheReflectingUnit() {
    Scene scene = new Scene();
    CharacterEntity giant =
        scene.still(
            0,
            withBuffAfterHits("ElectroGiant", List.of("Rage"), List.of(1), List.of(1000)),
            3500,
            11000,
            "giant");
    scene.still(1, GameData.unit("Knight"), 3500, 12900, "k");
    scene.step(40);

    // The Electro Giant attacks only buildings, and none is near: every count is a reflect's.
    assertThat(scene.counts).isNotEmpty().allMatch(line -> line.contains(" giant k "));
    assertThat(giant.getBuffs().carries("Rage")).isTrue();
  }

  @Test
  @DisplayName(
      "a typed hit and a buff's damage over time from a unit with BuffAfterHits are refused, as"
          + " no reference holds them")
  void typedHitsAndDamageOverTimeAreRefused() {
    Scene scene = new Scene();
    CharacterEntity barbarian = scene.still(0, GameData.unit("Barbarian_EV1"), 3500, 10000, "b");
    CharacterEntity knight = scene.still(1, GameData.unit("Knight"), 3500, 11000, "k");

    assertThatThrownBy(() -> knight.takeTypedHit(barbarian, 10, 0, 0, 1))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("by a typed hit or damage over time");
    assertThatThrownBy(() -> knight.takeDamageOverTime(10, barbarian))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("by a typed hit or damage over time");
  }

  @Test
  @DisplayName(
      "a buff's start action is scheduled for a new instance only, not on a refresh, and its"
          + " remove action as it runs out")
  void theStartActionIsNotRunOnARefresh() {
    Scene scene = new Scene();
    CharacterEntity knight = scene.still(0, GameData.unit("Knight"), 3500, 10000, "k");
    scene.step(1);
    BuffData invisibility = GameData.records().buff("Ghost_EV1_Invisibility");
    knight.getBuffs().apply(invisibility, 200, LEVEL, knight, 0);
    knight.getBuffs().apply(invisibility, 500, LEVEL, knight, 0);
    scene.step(12);

    assertThat(scene.hooks)
        .containsExactly(
            "0 k Ghost_EV1_Invisible_Group start", "10 k Ghost_EV1_Visible_Group remove");
  }

  @Test
  @DisplayName("a buff's remove action is not scheduled by a death")
  void theRemoveActionDoesNotRunAtADeath(@TempDir Path folder) throws IOException {
    GameTables tables =
        GameData.altered(
            folder,
            "character_buffs",
            rows ->
                GameData.columns(rows, "ZapFreeze").put("OnRemoveAction", "Bats_EV1_HelingVFX"));
    Scene scene = new Scene(tables);
    CharacterEntity killed = scene.still(0, GameData.unit("Knight"), 5500, 10000, "d");
    scene.step(1);
    BuffData freeze = scene.match.getWorld().buffData("ZapFreeze");
    killed.getBuffs().apply(freeze, 1000, LEVEL, null, 1);

    scene.match.getWorld().kill(killed, null);
    scene.step(3);

    assertThat(scene.hooks).isEmpty();
  }

  @Test
  @DisplayName("a clone of a carrier of a buff with a start or remove action is refused")
  void aCloneOfAHookCarrierIsRefused() {
    Scene scene = new Scene();
    CharacterEntity knight = scene.still(0, GameData.unit("Knight"), 3500, 10000, "k");
    CharacterEntity other = scene.still(0, GameData.unit("Knight"), 5500, 10000, "o");
    knight.getBuffs().apply(GameData.records().buff("BatsEV1_Heal"), 1000, LEVEL, knight, 0);

    assertThatThrownBy(() -> other.getBuffs().copyFrom(knight.getBuffs()))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("runs an action as it is listed or removed");
  }

  /** An evolved Royal Ghost and a Knight it reaches, as buff_after_hits_ghost_evo places them. */
  private static CharacterEntity ghostAndKnight(Scene scene) {
    CharacterEntity ghost =
        scene.match.deploy(0, GameData.unit("Ghost_EV1"), LEVEL, 0, 3500, 9500, "G");
    scene.match.deploy(0, GameData.unit("Knight"), LEVEL, 1, 3500, 17500, "K");
    return ghost;
  }

  @Test
  @DisplayName("an evolved Royal Ghost that is a clone is refused as its run starts")
  void aCloneGhostIsRefused() {
    Scene scene = new Scene();
    ghostAndKnight(scene).markClone(null);

    assertThatThrownBy(() -> scene.step(1))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("G is a clone running Ghost_EV1_Spawn_Summons_Action");
  }

  @Test
  @DisplayName(
      "a summon whose starting action does more than show something is refused, as is a summon"
          + " area that follows its parent")
  void theSummonRefusals(@TempDir Path folder) throws IOException {
    Files.createDirectories(folder.resolve("starting"));
    Files.createDirectories(folder.resolve("following"));
    GameTables starting =
        GameData.altered(
            folder.resolve("starting"),
            "characters",
            rows ->
                GameData.columns(rows, "Ghost_EV1_Summon_Left")
                    .put("OnStartingAction", "Ghost_EV1_Summon_Invisible_Group"));
    Scene scene = new Scene(starting);
    ghostAndKnight(scene);
    assertThatThrownBy(() -> scene.step(80))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("summons Ghost_EV1_Summon_Left, a building, a unit that paths");

    GameTables following =
        GameData.altered(
            folder.resolve("following"),
            "area_effect_objects",
            rows ->
                GameData.columns(rows, "Ghost_EV1_Summon_Spawn_Area")
                    .put("FollowBehaviour", "FollowParent"));
    Scene other = new Scene(following);
    ghostAndKnight(other);
    assertThatThrownBy(() -> other.step(80))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("G makes Ghost_EV1_Summon_Spawn_Area, which follows its parent");
  }

  @Test
  @DisplayName(
      "a summon runs its combat gate as it is made: deploying, its targeting component is off"
          + " before its first visit")
  void aSummonRunsItsCombatGate() {
    Scene scene = new Scene();
    List<Boolean> targeting = new ArrayList<>();
    scene
        .match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void ghostSummonSpawned(int t, AreaEffectEntity area, CharacterEntity summon) {
                targeting.add(summon.isActive(CharacterEntity.TARGETING_SLOT));
              }
            });
    ghostAndKnight(scene);
    scene.step(80);

    assertThat(targeting).containsExactly(false, false);
  }
}
