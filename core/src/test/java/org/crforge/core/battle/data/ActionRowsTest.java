package org.crforge.core.battle.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionOwner;
import org.crforge.core.battle.action.AirToGround;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.Berserk;
import org.crforge.core.battle.action.BossBanditAbility;
import org.crforge.core.battle.action.CannonBarrage;
import org.crforge.core.battle.action.CannonProjectileSpawn;
import org.crforge.core.battle.action.Clone;
import org.crforge.core.battle.action.DamageType;
import org.crforge.core.battle.action.GameTags;
import org.crforge.core.battle.action.GhostEvo;
import org.crforge.core.battle.action.LaserBall;
import org.crforge.core.battle.action.PlayAnimationIfHasTarget;
import org.crforge.core.battle.action.PopBalloons;
import org.crforge.core.battle.action.ShapeSelector;
import org.crforge.core.battle.action.SpawnBuff;
import org.crforge.core.battle.action.SpawnGuard;
import org.crforge.core.battle.action.TargetIndicatorAttack;
import org.crforge.core.battle.action.Taunt;
import org.crforge.core.battle.action.WarpCharacter;
import org.crforge.core.battle.spawn.SpawnAreaEffect;
import org.crforge.core.battle.spawn.SpawnCharacters;
import org.crforge.core.battle.spawn.SpawnProjectile;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.core.battle.unit.TowerEntity;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The battle's actions built from the game's action rows: each class from its columns, the actions
 * a row names built in turn, game tags as their bits, expressions compiled for the entity, and
 * every row of the data either built or refused by name.
 */
class ActionRowsTest {

  /** A binding that compiles nothing: every expression answers 0, every variable its hash. */
  private static final ActionBinding INERT_BINDING =
      new ActionBinding() {
        @Override
        public IntSupplier expression(String text) {
          return () -> 0;
        }

        @Override
        public int variableKey(String name) {
          return name.hashCode();
        }

        @Override
        public LongSupplier tags() {
          return () -> 0;
        }
      };

  private static List<String> queue(ActionHolder holder) {
    return holder.queued().stream().map(q -> q.action().name() + " " + q.ticks()).toList();
  }

  @Test
  @DisplayName("a group row schedules its parts at their own delays, each built from its row")
  void aGroupOfSpawns() {
    BattleAction group = GameData.actions().build("SuspiciousBush_SpawnBushGoblin", INERT_BINDING);
    ActionHolder holder = new ActionHolder();
    holder.schedule(group, ActionHolder.OWN_DELAY);
    assertThat(queue(holder))
        .containsExactly(
            "SuspiciousBush_SpawnBushGoblin 0",
            "SuspiciousBush_SpawnBushGoblin1 13",
            "SuspiciousBush_SpawnBushGoblin2 12");
    assertThat(holder.queued().get(1).action()).isInstanceOf(SpawnCharacters.class);
  }

  @Test
  @DisplayName("a Clone's action clones for its default duration and spawns its buff first")
  void theCloneRows() {
    BattleAction clone = GameData.actions().build("CloneAction", INERT_BINDING);
    assertThat(clone).isInstanceOf(Clone.class);
    assertThat(((Clone) clone).getCloneDurationMs()).isEqualTo(500);
    assertThat(GameData.actions().build("SpawnCloneBufAction", INERT_BINDING))
        .isInstanceOf(SpawnBuff.class);
  }

  @Test
  @DisplayName(
      "Dark Magic's laser ball reads its query, its rate and its three buff spawns, each written"
          + " inline, and its count picks the first list at or above it, else the last")
  void aLaserBallIsBuilt() {
    BattleAction built =
        GameData.actions().build("DarkMagicAOE_OnStartingAction_SubActions1", INERT_BINDING);
    assertThat(built).isInstanceOf(LaserBall.class);
    LaserBall laser = (LaserBall) built;
    LaserBall.Columns columns = laser.getColumns();
    assertThat(columns.detectionRadius()).isEqualTo(2500);
    assertThat(columns.firstHitDelayMs()).isEqualTo(1000);
    assertThat(columns.hitFrequencyMs()).isEqualTo(1000);
    assertThat(columns.hitFilter()).isNotNull();
    assertThat(columns.maxUnitPerActionList()).containsExactly(1, 4);
    assertThat(columns.onDetectedUnitActionList())
        .allSatisfy(action -> assertThat(action).isInstanceOf(SpawnBuff.class))
        .extracting(BattleAction::name)
        .containsExactly(
            "DarkMagicAOE_OnStartingAction_SubActions1_OnDetectedUnitActionList0",
            "DarkMagicAOE_OnStartingAction_SubActions1_OnDetectedUnitActionList1",
            "DarkMagicAOE_OnStartingAction_SubActions1_OnDetectedUnitActionList2");
    assertThat(columns.onDetectedUnitActionList().get(0).nextAction().name())
        .isEqualTo(
            "DarkMagicAOE_OnStartingAction_SubActions1_OnDetectedUnitActionList0_NextAction");
    assertThat(List.of(0, 1, 2, 3, 4, 5, 9).stream().map(laser::pick).toList())
        .containsExactly(0, 0, 1, 1, 1, 2, 2);
  }

  @Test
  @DisplayName(
      "Vines' selector reads its circle, its filter, its scoring by hit points and shield, its"
          + " delays and the rows of their actions, and picks each object once")
  void aShapeSelectorIsBuilt() {
    BattleAction built = GameData.actions().build("Vines_Target_Selector", INERT_BINDING);
    assertThat(built).isInstanceOf(ShapeSelector.class);
    ShapeSelector.Columns columns = ((ShapeSelector) built).getColumns();
    assertThat(columns.oncePerTarget()).isTrue();
    assertThat(columns.targetSelectionMode())
        .isEqualTo(ShapeSelector.HIGHEST_CURRENT_HP_INCLUDE_SHIELDS);
    assertThat(columns.targetFilter()).isNotNull();
    assertThat(columns.shapeRadius()).isEqualTo(2500);
    assertThat(columns.delaysMs()).containsExactly(0, 50, 150);
    assertThat(columns.actions())
        .containsExactly("Vines_Action_Group", "Vines_Action_Group", "Vines_Action_Group");
  }

  @Test
  @DisplayName(
      "a shape selector that waits for its target, scores by distance, has fewer actions than"
          + " delays, has no filter or a shape other than a circle is refused")
  void aShapeSelectorIsRefused(@TempDir Path folder) throws IOException {
    assertThatThrownBy(() -> GameData.actions().build("GiantHero_Target_Selector", INERT_BINDING))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("sets WaitForTarget");
    String row = "Vines_Target_Selector";
    Map<String, Consumer<ObjectNode>> changes =
        Map.of(
            "scores by Closest", f -> f.put("TargetSelectionMode", "Closest"),
            "fewer actions than delays", f -> f.putArray("Delays").add(0).add(50).add(100).add(150),
            "without a filter", f -> f.remove("TargetFilter"),
            "is a Rectangle", f -> f.put("Shape", "BabyDragon_EV1_wind_aeo_shape"));
    for (Map.Entry<String, Consumer<ObjectNode>> change : changes.entrySet()) {
      Path dir = folder.resolve(change.getKey().replace(' ', '_'));
      Files.createDirectories(dir);
      GameTables altered =
          GameData.altered(
              dir,
              "actions",
              rows -> change.getValue().accept((ObjectNode) rows.get(row).get("fields")));
      ActionRows rows = new ActionRows(altered, new BattleRecords(altered));
      assertThatThrownBy(() -> rows.build(row, INERT_BINDING))
          .as(change.getKey())
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessageContaining(change.getKey());
    }
  }

  @Test
  @DisplayName(
      "Vines' air-to-ground row reads its two durations and its ground tag; a column it leaves out"
          + " takes the loader's default")
  void anAirToGroundIsBuilt(@TempDir Path folder) throws IOException {
    AirToGround vines =
        (AirToGround) GameData.actions().build("Vines_Air_To_Ground", INERT_BINDING);
    assertThat(vines.getTransitionDurationMs()).isEqualTo(50);
    assertThat(vines.getTotalDurationMs()).isEqualTo(2000);
    assertThat(vines.isAllowIsGroundTagOnIdle()).isTrue();
    assertThat(vines.isResetPathAtEnd()).isTrue();
    assertThat(vines.singleton()).isTrue();

    Files.createDirectories(folder);
    GameTables bare =
        GameData.altered(
            folder,
            "actions",
            rows -> {
              ObjectNode f = (ObjectNode) rows.get("Vines_Air_To_Ground").get("fields");
              f.remove("TransitionDuration");
              f.remove("TotalDuration");
              f.remove("AllowIsGroundTagOnIdle");
            });
    AirToGround defaults =
        (AirToGround)
            new ActionRows(bare, new BattleRecords(bare))
                .build("Vines_Air_To_Ground", INERT_BINDING);
    assertThat(defaults.getTransitionDurationMs()).isEqualTo(200);
    assertThat(defaults.getTotalDurationMs()).isEqualTo(1000);
    assertThat(defaults.isAllowIsGroundTagOnIdle()).isFalse();
  }

  @Test
  @DisplayName(
      "the evolved Furnace's spawn behind it is a projectile from 6000 high; a count, the cause as"
          + " the source, no aim, the spawn class and a character spawn with an aim are refused")
  void aProjectileSpawnIsBuilt(@TempDir Path folder) throws IOException {
    SpawnProjectile left =
        (SpawnProjectile) GameData.actions().build("Furnace_EV1_Spawn_Behind_Left", INERT_BINDING);
    assertThat(left.getProjectile()).isEqualTo("Furnace_EV1_Spawn_Spirit_Projectile");
    assertThat(left.getStartHeight()).isEqualTo(6000);

    Map<String, Consumer<ObjectNode>> refused =
        Map.of(
            "sets Count",
            f -> f.put("Count", 2),
            "from its cause",
            f -> f.remove("ParentGOAsSource"),
            "at its owner's target",
            f -> {
              f.remove("TargetExprX");
              f.remove("TargetExprY");
            },
            "from a spawn row's own position",
            f -> f.put("ClassType", "ActionSpawn"));
    for (Map.Entry<String, Consumer<ObjectNode>> change : refused.entrySet()) {
      Path dir = folder.resolve(change.getKey().replace(' ', '_').replace("'", ""));
      Files.createDirectories(dir);
      GameTables altered =
          GameData.altered(
              dir,
              "actions",
              rows -> {
                ObjectNode row = (ObjectNode) rows.get("Furnace_EV1_Spawn_Behind_Left");
                ObjectNode f = (ObjectNode) row.get("fields");
                change.getValue().accept(f);
                if (f.has("ClassType")) {
                  row.put("ClassType", f.get("ClassType").asText());
                }
              });
      assertThatThrownBy(
              () ->
                  new ActionRows(altered, new BattleRecords(altered))
                      .build("Furnace_EV1_Spawn_Behind_Left", INERT_BINDING))
          .as(change.getKey())
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessageContaining(change.getKey());
    }

    Path characters = folder.resolve("characters");
    Files.createDirectories(characters);
    GameTables aimed =
        GameData.altered(
            characters,
            "actions",
            rows ->
                ((ObjectNode) rows.get("Furnace_rework_spawn_forward").get("fields"))
                    .put("TargetExprX", "x"));
    assertThatThrownBy(
            () ->
                new ActionRows(aimed, new BattleRecords(aimed))
                    .build("Furnace_rework_spawn_forward", INERT_BINDING))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("spawns characters and sets TargetExprX");
  }

  @Test
  @DisplayName(
      "the evolved Cannon's barrage reads its nine bombs; a bomb without an absolute offset is"
          + " refused, and its drop reads its projectile and height")
  void aBarrageIsBuilt(@TempDir Path folder) throws IOException {
    CannonBarrage barrage =
        (CannonBarrage) GameData.actions().build("Cannon_EV1_barrage", INERT_BINDING);
    assertThat(barrage.getAreaEffects()).hasSize(9);
    assertThat(barrage.getAbsoluteHorizontalOffsets())
        .containsExactly(3, 13, 23, 33, 2, 10, 18, 26, 34);
    assertThat(barrage.getVerticalOffsets()).containsExactly(3, 3, 3, 3, 17, 17, 17, 17, 17);
    assertThat(barrage.getAreaEffects().get(5)).isEqualTo("Cannon_EV1_barrage_aeo_JULIO");
    CannonProjectileSpawn drop =
        (CannonProjectileSpawn)
            GameData.actions().build("Cannon_EV1_spawn_projectile_JULIO", INERT_BINDING);
    assertThat(drop.getProjectile()).isEqualTo("Cannon_EV1_barrage_projectile");
    assertThat(drop.getHeight()).isEqualTo(70000);

    Files.createDirectories(folder);
    GameTables relative =
        GameData.altered(
            folder,
            "actions",
            rows ->
                ((ObjectNode) rows.get("Cannon_EV1_barrage").get("fields"))
                    .withArray("BombAbsoluteHorizontalOffsets")
                    .set(4, -1));
    assertThatThrownBy(
            () ->
                new ActionRows(relative, new BattleRecords(relative))
                    .build("Cannon_EV1_barrage", INERT_BINDING))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("by its relative offset");
  }

  @Test
  @DisplayName(
      "the evolved Royal Ghost's row reads its distance, areas and delay, the loader's distance"
          + " 250 when it is left out")
  void theGhostEvoRowIsBuilt(@TempDir Path folder) throws IOException {
    String row = "Ghost_EV1_Spawn_Summons_Action";
    GhostEvo ghost = (GhostEvo) GameData.actions().build(row, INERT_BINDING);
    assertThat(ghost.getColumns().summonDistance()).isEqualTo(2000);
    assertThat(ghost.getColumns().damageArea()).isEqualTo("Ghost_EV1_Summon_Damage_Area");
    assertThat(ghost.getColumns().damageAreaDelayMs()).isEqualTo(200);
    assertThat(ghost.getColumns().leftArea()).isEqualTo("Ghost_EV1_Summon_Spawn_Area");
    assertThat(ghost.getColumns().rightArea()).isEqualTo("Ghost_EV1_Summon_Spawn_Area");
    assertThat(ghost.getColumns().summon().name()).isEqualTo("Ghost_EV1_Area_Spawn_Summons_Action");

    Files.createDirectories(folder);
    GameTables bare =
        GameData.altered(
            folder,
            "actions",
            rows -> ((ObjectNode) rows.get(row).get("fields")).remove("SummonDistance"));
    GhostEvo defaults =
        (GhostEvo) new ActionRows(bare, new BattleRecords(bare)).build(row, INERT_BINDING);
    assertThat(defaults.getColumns().summonDistance()).isEqualTo(250);
  }

  @Test
  @DisplayName(
      "an evolved Royal Ghost's summon row that hits at once, runs an action on its summons or"
          + " spawns them without their deploy is refused, as is a shared column no row sets")
  void aGhostEvoRowIsRefused(@TempDir Path folder) throws IOException {
    String row = "Ghost_EV1_Spawn_Summons_Action";
    String summon = "Ghost_EV1_Area_Spawn_Summons_Action";
    Map<String, Consumer<ObjectNode>> changes =
        Map.of(
            "instant",
            f -> f.put("InstantHitForSummons", true),
            "action",
            f -> f.put("ActionOnSummons", "Ghost_EV1_Hide_Glow_Filter"),
            "deploy",
            f -> f.put("UseDeployForSummons", false));
    for (Map.Entry<String, Consumer<ObjectNode>> change : changes.entrySet()) {
      Path dir = folder.resolve(change.getKey());
      Files.createDirectories(dir);
      GameTables altered =
          GameData.altered(
              dir,
              "actions",
              rows -> change.getValue().accept((ObjectNode) rows.get(summon).get("fields")));
      ActionRows rows = new ActionRows(altered, new BattleRecords(altered));
      assertThatThrownBy(() -> rows.build(row, INERT_BINDING))
          .as(change.getKey())
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessageContaining("hits at once, runs an action on its summons");
    }
    Path dir = folder.resolve("singleton");
    Files.createDirectories(dir);
    GameTables singleton =
        GameData.altered(
            dir,
            "actions",
            rows -> ((ObjectNode) rows.get(row).get("fields")).put("Singleton", true));
    ActionRows rows = new ActionRows(singleton, new BattleRecords(singleton));
    assertThatThrownBy(() -> rows.build(row, INERT_BINDING))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("Singleton");
  }

  @Test
  @DisplayName("an air-to-ground row with a landing action or a path reset at landing is refused")
  void anAirToGroundIsRefused(@TempDir Path folder) throws IOException {
    assertThatThrownBy(() -> GameData.actions().build("RoyalHog_EV1_To_Ground", INERT_BINDING))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("sets ActionOnGround");
    Files.createDirectories(folder);
    GameTables landing =
        GameData.altered(
            folder,
            "actions",
            rows ->
                ((ObjectNode) rows.get("Vines_Air_To_Ground").get("fields"))
                    .put("ResetPathAtLanding", true));
    ActionRows rows = new ActionRows(landing, new BattleRecords(landing));
    assertThatThrownBy(() -> rows.build("Vines_Air_To_Ground", INERT_BINDING))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("sets ResetPathAtLanding");
  }

  @Test
  @DisplayName(
      "a laser ball that keeps its detection, resets it after a hit, cools down after one, runs a"
          + " list on its owner or has no filter is refused")
  void aLaserBallIsRefused(@TempDir Path folder) throws IOException {
    String row = "DarkMagicAOE_OnStartingAction_SubActions1";
    Map<String, Consumer<ObjectNode>> changes =
        Map.of(
            "PermanentDetection", f -> f.put("PermanentDetection", true),
            "ResetDetetcedUnitsAfterHit", f -> f.put("ResetDetetcedUnitsAfterHit", true),
            "DetectionCooldownAfterHit", f -> f.put("DetectionCooldownAfterHit", 500),
            "OnAttackActionList",
                f ->
                    f.putArray("OnAttackActionList")
                        .addObject()
                        .put("action", "DarkMagicAOE_OnLifeTimeEndAction"),
            "a filter", f -> f.remove("HitFilter"));
    for (Map.Entry<String, Consumer<ObjectNode>> change : changes.entrySet()) {
      Path dir = folder.resolve(change.getKey().replace(' ', '_'));
      Files.createDirectories(dir);
      GameTables altered =
          GameData.altered(
              dir,
              "actions",
              rows -> change.getValue().accept((ObjectNode) rows.get(row).get("fields")));
      ActionRows rows = new ActionRows(altered, new BattleRecords(altered));
      assertThatThrownBy(() -> rows.build(row, INERT_BINDING))
          .as(change.getKey())
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessageContaining(change.getKey());
    }
  }

  @Test
  @DisplayName(
      "the Little Prince's guard spawn reads its guard, where it appears and charges to, its push"
          + " and damage, its filter, and the tags of its run on the guard")
  void aGuardSpawnIsBuilt() {
    BattleAction built = GameData.actions().build("Spawn_ChampionGuardCharge", INERT_BINDING);
    assertThat(built).isInstanceOf(SpawnGuard.class);
    assertThat(built.delayMs()).isEqualTo(850);
    SpawnGuard.Columns columns = ((SpawnGuard) built).getColumns();
    assertThat(columns.spawnData()).isEqualTo("ChampionGuard");
    assertThat(columns.appearBehindAtDistance()).isEqualTo(2000);
    assertThat(columns.targetRadius()).isEqualTo(4000);
    assertThat(columns.pushBackStrength()).isEqualTo(2500);
    assertThat(columns.pushBackRadius()).isEqualTo(2500);
    assertThat(columns.continuousPushBack()).isTrue();
    assertThat(columns.distanceProportionalPush()).isTrue();
    assertThat(columns.pushBackDamage()).isEqualTo(100);
    assertThat(columns.hitFilter()).isNotNull();
    assertThat(columns.guardTags())
        .isEqualTo(GameData.actions().tagMask("NO_CHECKCOLLISIONS,NO_CHECKAVOIDANCE,NO_BUFFS"));
    assertThat(columns.shadowTag()).isEqualTo(GameData.actions().tagMask("NO_SHADOW"));
  }

  @Test
  @DisplayName(
      "a guard spawn that sets tags, is a singleton, chains a next action, has a gate or a phase of"
          + " its own, has no filter or whose target radius is left out takes the default 1000")
  void aGuardSpawnIsRefused(@TempDir Path folder) throws IOException {
    String row = "Spawn_ChampionGuardCharge";
    Map<String, Consumer<ObjectNode>> changes =
        Map.of(
            "GameTagsToSet", f -> f.put("GameTagsToSet", "NO_MOVE"),
            "Singleton", f -> f.put("Singleton", true),
            "NextAction", f -> f.putObject("NextAction").put("action", "LittlePrinceWaitGuard"),
            "ExecuteIfTrue", f -> f.put("ExecuteIfTrue", "1"),
            "UpdatePhase", f -> f.put("UpdatePhase", 2),
            "a filter", f -> f.remove("HitFilter"));
    for (Map.Entry<String, Consumer<ObjectNode>> change : changes.entrySet()) {
      Path dir = folder.resolve(change.getKey().replace(' ', '_'));
      Files.createDirectories(dir);
      GameTables altered =
          GameData.altered(
              dir,
              "actions",
              rows -> change.getValue().accept((ObjectNode) rows.get(row).get("fields")));
      ActionRows rows = new ActionRows(altered, new BattleRecords(altered));
      assertThatThrownBy(() -> rows.build(row, INERT_BINDING))
          .as(change.getKey())
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessageContaining(change.getKey());
    }
    Path dir = folder.resolve("default_radius");
    Files.createDirectories(dir);
    GameTables altered =
        GameData.altered(
            dir,
            "actions",
            rows -> ((ObjectNode) rows.get(row).get("fields")).remove("TargetRadius"));
    SpawnGuard guard =
        (SpawnGuard) new ActionRows(altered, new BattleRecords(altered)).build(row, INERT_BINDING);
    assertThat(guard.getColumns().targetRadius()).isEqualTo(1000);
  }

  @Test
  @DisplayName(
      "the Boss Bandit's ability reads its warp and lock delays, its release delay and its warp"
          + " row, waits while dashing by default, and its warp reads its offset, the four resets"
          + " and avoidances on by default, and the target reset")
  void aBossBanditAbilityAndItsWarpAreBuilt() {
    BattleAction built = GameData.actions().build("BossBandit_ability_action", INERT_BINDING);
    assertThat(built).isInstanceOf(BossBanditAbility.class);
    BossBanditAbility.Columns columns = ((BossBanditAbility) built).getColumns();
    assertThat(columns.warpDelayMs()).isEqualTo(700);
    assertThat(columns.lockDelayMs()).isZero();
    assertThat(columns.releaseLockDelayMs()).isEqualTo(50);
    assertThat(columns.waitForDashToFinish()).isTrue();
    assertThat(columns.warpAction()).isInstanceOf(WarpCharacter.class);
    WarpCharacter warp = (WarpCharacter) columns.warpAction();
    assertThat(warp.name()).isEqualTo("BossBandit_ability_warp");
    assertThat(warp.nextAction().name()).isEqualTo("BossBandit_ability_warp_done_group");
    assertThat(warp.getColumns())
        .isEqualTo(
            WarpCharacter.Columns.builder()
                .warpX(0)
                .warpY(-6000)
                .resetPath(true)
                .resetTarget(true)
                .avoidWater(true)
                .avoidBlocked(true)
                .resetPendingDamage(true)
                .build());
  }

  @Test
  @DisplayName(
      "a Boss Bandit ability that sets tags, chains a next action, asks its unit's speeds, has no"
          + " warp row or releases its lock in the warp's step is refused, and so is a warp in"
          + " another mode, with a speed or that waits as a next action")
  void aBossBanditAbilityOrItsWarpIsRefused(@TempDir Path folder) throws IOException {
    Map<String, Map.Entry<String, Consumer<ObjectNode>>> changes =
        Map.of(
            "GameTagsToSet",
            Map.entry("BossBandit_ability_action", f -> f.put("GameTagsToSet", "NO_MOVE")),
            "NextAction",
            Map.entry(
                "BossBandit_ability_action",
                f -> f.putObject("NextAction").put("action", "BossBandit_ability_effect")),
            "speeds",
            Map.entry(
                "BossBandit_ability_action", f -> f.put("AllowWarpWhenAttackSpeedZero", false)),
            "no warp row",
            Map.entry("BossBandit_ability_action", f -> f.remove("WarpAction")),
            "in the warp's own step",
            Map.entry("BossBandit_ability_action", f -> f.put("ReleaseLockDelay", 0)),
            "mode AbsoluteWarp",
            Map.entry("BossBandit_ability_warp", f -> f.put("WarpMode", "AbsoluteWarp")),
            "Speed",
            Map.entry("BossBandit_ability_warp", f -> f.put("Speed", 1500)),
            "NextActionWait",
            Map.entry("BossBandit_ability_warp", f -> f.put("NextActionWait", true)));
    for (Map.Entry<String, Map.Entry<String, Consumer<ObjectNode>>> change : changes.entrySet()) {
      Path dir = folder.resolve(change.getKey().replace(' ', '_').replace("'", ""));
      Files.createDirectories(dir);
      String row = change.getValue().getKey();
      GameTables altered =
          GameData.altered(
              dir,
              "actions",
              rows ->
                  change.getValue().getValue().accept((ObjectNode) rows.get(row).get("fields")));
      ActionRows rows = new ActionRows(altered, new BattleRecords(altered));
      assertThatThrownBy(() -> rows.build(row, INERT_BINDING))
          .as(change.getKey())
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessageContaining(change.getKey());
    }
  }

  @Test
  @DisplayName(
      "the Goblin Machine's rocket reads its clock, its ring, its signal, its projectile and where"
          + " it starts, the tag a shot sets and the two actions it runs on its owner")
  void aTargetIndicatorAttackIsBuilt() {
    BattleAction built = GameData.actions().build("goblin_machine_rocket", INERT_BINDING);
    assertThat(built).isInstanceOf(TargetIndicatorAttack.class);
    TargetIndicatorAttack.Columns columns = ((TargetIndicatorAttack) built).getColumns();
    assertThat(columns.loadTimeMs()).isEqualTo(1500);
    assertThat(columns.attackDelayMs()).isEqualTo(1000);
    assertThat(columns.attackCooldownMs()).isEqualTo(2500);
    assertThat(columns.range()).isEqualTo(5000);
    assertThat(columns.minimumRange()).isEqualTo(2500);
    assertThat(columns.targetFilter()).isNotNull();
    assertThat(columns.targetAoE()).isEqualTo("goblin_machine_rocket_target_signal");
    assertThat(columns.projectile()).isEqualTo("GoblinMachineRocketProjectile");
    assertThat(columns.projectileStartZ()).isEqualTo(5000);
    assertThat(columns.lookOffset()).isEqualTo(-1200);
    assertThat(columns.stopTags()).isEqualTo(GameData.actions().tagMask("UNIT_CUSTOM_TAG_1"));
    assertThat(columns.targetStartIndicationAction().name())
        .isEqualTo("goblin_machine_rocket_load");
    assertThat(columns.onProjectileShootAction().name()).isEqualTo("goblin_machine_rocket_hide");
  }

  @Test
  @DisplayName(
      "a target indicator attack with an indication delay, a negative attack delay, no minimum"
          + " range, a next action, a following signal or a homing projectile is refused")
  void aTargetIndicatorAttackIsRefused(@TempDir Path folder) throws IOException {
    String row = "goblin_machine_rocket";
    Map<String, Consumer<ObjectNode>> actions =
        Map.of(
            "sets TargetIndicatorDelay", f -> f.put("TargetIndicatorDelay", 100),
            "a negative AttackDelay", f -> f.put("AttackDelay", -50),
            "a MinimumRange below 1", f -> f.put("MinimumRange", 0),
            "sets NextAction", f -> f.put("NextAction", "goblin_machine_rocket_hide"));
    for (Map.Entry<String, Consumer<ObjectNode>> change : actions.entrySet()) {
      Path dir = folder.resolve(change.getKey().replace(' ', '_'));
      Files.createDirectories(dir);
      GameTables altered =
          GameData.altered(
              dir,
              "actions",
              rows -> change.getValue().accept((ObjectNode) rows.get(row).get("fields")));
      ActionRows rows = new ActionRows(altered, new BattleRecords(altered));
      assertThatThrownBy(() -> rows.build(row, INERT_BINDING))
          .as(change.getKey())
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessageContaining(change.getKey());
    }
    Path following = folder.resolve("following");
    Files.createDirectories(following);
    GameTables signal =
        GameData.altered(
            following,
            "area_effect_objects",
            rows ->
                GameData.columns(rows, "goblin_machine_rocket_target_signal")
                    .put("FollowBehaviour", "FollowParent"));
    assertThatThrownBy(
            () -> new ActionRows(signal, new BattleRecords(signal)).build(row, INERT_BINDING))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("which follows something");
    Path homing = folder.resolve("homing");
    Files.createDirectories(homing);
    GameTables rocket =
        GameData.altered(
            homing,
            "projectiles",
            rows -> GameData.columns(rows, "GoblinMachineRocketProjectile").put("Homing", true));
    assertThatThrownBy(
            () -> new ActionRows(rocket, new BattleRecords(rocket)).build(row, INERT_BINDING))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("which homes");
  }

  @Test
  @DisplayName("game tags are the bits of their rows, a list of them the bits of all")
  void gameTags() {
    ActionRows rows = GameData.actions();
    assertThat(rows.tagMask("INACTIVE")).isEqualTo(GameTags.INACTIVE);
    assertThat(rows.tagMask("ACTIVATING")).isEqualTo(GameTags.ACTIVATING);
    assertThat(rows.tagMask("INACTIVE, ACTIVATING"))
        .isEqualTo(GameTags.INACTIVE | GameTags.ACTIVATING);
    assertThatThrownBy(() -> rows.tagMask("NO_SUCH_TAG"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("NO_SUCH_TAG");
  }

  @Test
  @DisplayName("a set-variable row writes its value to the variable the battle declares")
  void aVariableRow() {
    Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
    TowerEntity tower = (TowerEntity) match.getBattle().getHolder().entities().get(0);
    BattleAction row =
        GameData.actions()
            .build("MiniPekkaHero_set_ability_played", match.getWorld().binding(tower));
    tower.actionHolder().start(row);
    int key = match.getWorld().binding(tower).variableKey("MiniPekkaHero_ability_played");
    assertThat(tower.variable(key)).isEqualTo(1);
  }

  @Test
  @DisplayName("a row whose tree reaches a class the battle does not have is refused, naming it")
  void anUnmodelledClassIsRefused() {
    assertThatThrownBy(() -> GameData.actions().build("BarbLog_hero_reset_target", INERT_BINDING))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("ActionResetTarget");
  }

  @Test
  @DisplayName("the Skeleton Barrel's pop keeps a run that is stepped doing nothing")
  void thePopRunLasts() {
    BattleAction pop = GameData.actions().build("skeleton_balloon_pop_balloons", INERT_BINDING);
    assertThat(pop).isInstanceOf(PopBalloons.class);
    ActionHolder holder = new ActionHolder();
    holder.start(pop);
    for (int tick = 1; tick <= 100; tick++) {
      holder.runPass(tick);
    }
    assertThat(holder.running()).as("still listed after a hundred steps").hasSize(1);
    assertThat(holder.running().get(0).isFinished()).isFalse();
  }

  @Test
  @DisplayName(
      "a Skeleton Barrel pop that drops containers is refused for its columns, and a singleton one"
          + " for its second start")
  void aContainerPopIsRefused(@TempDir Path folder) throws IOException {
    assertThatThrownBy(
            () -> GameData.actions().build("skeleton_balloon_evo_pop_balloon", INERT_BINDING))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("ContainerAeoList");
    GameTables singleton =
        GameData.altered(
            folder,
            "actions",
            rows ->
                ((ObjectNode) rows.get("skeleton_balloon_pop_balloons").get("fields"))
                    .put("Singleton", true));
    ActionRows rows = new ActionRows(singleton, new BattleRecords(singleton));
    assertThatThrownBy(() -> rows.build("skeleton_balloon_pop_balloons", INERT_BINDING))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("singleton");
  }

  @Test
  @DisplayName("the Goblin Cage's shake keeps a run that is stepped doing nothing")
  void theShakeRunLasts() {
    BattleAction shake = GameData.actions().build("goblin_cage_shake_when_target", INERT_BINDING);
    assertThat(shake).isInstanceOf(PlayAnimationIfHasTarget.class);
    ActionHolder holder = new ActionHolder();
    holder.start(shake);
    for (int tick = 1; tick <= 100; tick++) {
      holder.runPass(tick);
    }
    assertThat(holder.running()).as("still listed after a hundred steps").hasSize(1);
    assertThat(holder.running().get(0).isFinished()).isFalse();
  }

  @Test
  @DisplayName(
      "a Goblin Cage shake that sets tags, stops on a gate, is a singleton or chains an action is"
          + " refused for that column")
  void aShakeWithSomethingToDoIsRefused(@TempDir Path folder) throws IOException {
    Map<String, Consumer<ObjectNode>> columns =
        Map.of(
            "GameTagsToSet", f -> f.put("GameTagsToSet", "UNIT_CUSTOM_TAG_1"),
            "ForceStopIfTrue", f -> f.put("ForceStopIfTrue", "UNIT_CUSTOM_TAG_1"),
            "Singleton", f -> f.put("Singleton", true),
            "NextAction", f -> f.put("NextAction", "skeleton_balloon_pop_balloons"));
    for (Map.Entry<String, Consumer<ObjectNode>> column : columns.entrySet()) {
      ActionRows rows = shakeRows(folder.resolve(column.getKey()), column.getValue());
      assertThatThrownBy(() -> rows.build("goblin_cage_shake_when_target", INERT_BINDING))
          .as(column.getKey())
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessageContaining(column.getKey());
    }
    // A singleton written as false is no singleton, and the row is built.
    assertThat(
            shakeRows(folder.resolve("false"), f -> f.put("Singleton", false))
                .build("goblin_cage_shake_when_target", INERT_BINDING))
        .isInstanceOf(PlayAnimationIfHasTarget.class);
  }

  /** The rows of tables whose Goblin Cage shake has one column set by the given edit. */
  private static ActionRows shakeRows(Path folder, Consumer<ObjectNode> edit) throws IOException {
    Files.createDirectories(folder);
    GameTables altered =
        GameData.altered(
            folder,
            "actions",
            rows ->
                edit.accept((ObjectNode) rows.get("goblin_cage_shake_when_target").get("fields")));
    return new ActionRows(altered, new BattleRecords(altered));
  }

  /**
   * An owner that keeps only an attack sequence index, stored below the length of its order, and
   * whether the last store was asked to ignore the targeting component.
   */
  private static final class IndexOwner implements ActionOwner {
    private final int orderLength;
    private int index;
    private boolean ignoredComponent;

    private IndexOwner(int orderLength, int index) {
      this.orderLength = orderLength;
      this.index = index;
    }

    @Override
    public void setAttackSequenceIndex(int index, boolean evenIfCombatDisabled) {
      ignoredComponent = evenIfCombatDisabled;
      if (orderLength > index) {
        this.index = index;
      }
    }

    @Override
    public int attackSequenceIndex() {
      return index;
    }

    @Override
    public HitPoints actionHitPoints() {
      return null;
    }

    @Override
    public int variable(int key) {
      return 0;
    }

    @Override
    public void setVariable(int key, int value) {}

    @Override
    public void killBy(ActionOwner killer) {}

    @Override
    public void queueTypedHit(ActionOwner source, int amount, DamageType type) {}
  }

  @Test
  @DisplayName(
      "the Berserker's starting action sets the index to 0 as it starts, and every landed attack"
          + " flips it: one up from 0, back to 0 from above")
  void theBerserkRunTogglesTheIndex() {
    BattleAction berserk = GameData.actions().build("Berserker_OnStartingAction", INERT_BINDING);
    assertThat(berserk).isInstanceOf(Berserk.class);
    IndexOwner owner = new IndexOwner(3, 2);
    ActionHolder holder = new ActionHolder(owner);
    holder.start(berserk);
    assertThat(owner.index).as("the start's index").isZero();
    assertThat(owner.ignoredComponent).as("stored with the component's bit ignored").isTrue();
    List<Integer> indices = new ArrayList<>();
    for (int hit = 0; hit < 4; hit++) {
      holder.attackEnded();
      indices.add(owner.index);
    }
    assertThat(indices).containsExactly(1, 0, 1, 0);
    // From 2, which another action may have set, the next notice goes back to 0.
    owner.index = 2;
    holder.attackEnded();
    assertThat(owner.index).isZero();
    for (int tick = 1; tick <= 100; tick++) {
      holder.runPass(tick);
    }
    assertThat(holder.running()).as("still listed after a hundred steps").hasSize(1);
    assertThat(holder.running().get(0).isFinished()).isFalse();
  }

  @Test
  @DisplayName("a Berserker row that sets any column besides its class is refused for that column")
  void aBerserkWithAColumnIsRefused(@TempDir Path folder) throws IOException {
    Files.createDirectories(folder);
    GameTables altered =
        GameData.altered(
            folder,
            "actions",
            rows ->
                ((ObjectNode) rows.get("Berserker_OnStartingAction").get("fields"))
                    .put("ActionDelay", 100));
    ActionRows rows = new ActionRows(altered, new BattleRecords(altered));
    assertThatThrownBy(() -> rows.build("Berserker_OnStartingAction", INERT_BINDING))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("ActionDelay");
  }

  @Test
  @DisplayName("an area-effect spawn is built from its area effect alone")
  void anAreaEffectSpawnIsBuilt() {
    assertThat(GameData.actions().build("GoblinCurseCore", INERT_BINDING))
        .isInstanceOf(SpawnAreaEffect.class);
    assertThat(GameData.actions().build("SpawnCancelTauntAEO", INERT_BINDING))
        .as("an area effect that follows its parent and taunts")
        .isInstanceOf(SpawnAreaEffect.class);
  }

  @Test
  @DisplayName("a taunt is built from its valid duration and buff")
  void aTauntIsBuilt() {
    BattleAction built = GameData.actions().build("ResetTauntEffect", INERT_BINDING);
    assertThat(built).isInstanceOf(Taunt.class);
    Taunt taunt = (Taunt) built;
    assertThat(taunt.getValidDurationMs()).isEqualTo(50);
    assertThat(taunt.getValidTargetBuff()).isEqualTo("GoblinDemolisher_ResetTargetBuff");
  }

  @Test
  @DisplayName(
      "a taunt with another column, lasting past one step or with a buff not modelled is refused")
  void aTauntIsRefused(@TempDir Path folder) throws IOException {
    assertThatThrownBy(() -> GameData.actions().build("Knight_hero_ApplyTaunt", INERT_BINDING))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining(" sets ");

    Files.createDirectories(folder.resolve("long"));
    GameTables longer =
        GameData.altered(
            folder.resolve("long"),
            "actions",
            rows ->
                ((ObjectNode) rows.get("ResetTauntEffect").get("fields"))
                    .put("ValidDuration", 100));
    ActionRows longerRows = new ActionRows(longer, new BattleRecords(longer));
    assertThatThrownBy(() -> longerRows.build("ResetTauntEffect", INERT_BINDING))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("past one step");

    Files.createDirectories(folder.resolve("buff"));
    GameTables buff =
        GameData.altered(
            folder.resolve("buff"),
            "actions",
            rows ->
                ((ObjectNode) rows.get("ResetTauntEffect").get("fields"))
                    .put("ValidTargetBuff", "ShieldBoost"));
    ActionRows buffRows = new ActionRows(buff, new BattleRecords(buff));
    assertThatThrownBy(() -> buffRows.build("ResetTauntEffect", INERT_BINDING))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("ShieldBoost, which sets columns not modelled");
  }

  @Test
  @DisplayName(
      "an area-effect spawn to a location, with another spawn column, written inline or of an area"
          + " effect not modelled is refused")
  void anAreaEffectSpawnIsRefused(@TempDir Path folder) throws IOException {
    assertThatThrownBy(() -> GameData.actions().build("ElectroWizardAOE", INERT_BINDING))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("to a location");
    assertThatThrownBy(() -> GameData.actions().build("Knight_hero_CreateTauntAEO", INERT_BINDING))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("Knight_hero_TauntAEO, which sets columns not modelled");

    Files.createDirectories(folder.resolve("offset"));
    GameTables offset =
        GameData.altered(
            folder.resolve("offset"),
            "actions",
            rows -> ((ObjectNode) rows.get("GoblinCurseCore").get("fields")).put("OffsetY", 1000));
    ActionRows offsetRows = new ActionRows(offset, new BattleRecords(offset));
    assertThatThrownBy(() -> offsetRows.build("GoblinCurseCore", INERT_BINDING))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("sets OffsetY");

    Files.createDirectories(folder.resolve("inline"));
    GameTables inline =
        GameData.altered(
            folder.resolve("inline"),
            "actions",
            rows -> {
              ObjectNode fields = (ObjectNode) rows.get("GoblinCurseCore").get("fields");
              fields.putObject("SpawnData").put("Radius", 3000);
            });
    ActionRows inlineRows = new ActionRows(inline, new BattleRecords(inline));
    assertThatThrownBy(() -> inlineRows.build("GoblinCurseCore", INERT_BINDING))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("written inline");
  }

  @Test
  @DisplayName("a Berserker run started beside an enchanting buff is refused")
  void aBerserkBesideAnEnchantingBuffIsRefused() {
    ActionHolder holder = new ActionHolder(new IndexOwner(3, 0));
    holder.start(GameData.actions().build("giantbuffer_enchanting_buff", INERT_BINDING));
    assertThat(holder.running()).hasSize(1);
    BattleAction berserk = GameData.actions().build("Berserker_OnStartingAction", INERT_BINDING);
    assertThatThrownBy(() -> holder.start(berserk))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("enchanting");
  }

  @Test
  @DisplayName("an effect row that loops keeps a run that never finishes by itself")
  void aLoopingEffectLasts() {
    BattleAction effect = GameData.actions().build("goblin_machine_signal_core", INERT_BINDING);
    ActionHolder holder = new ActionHolder();
    holder.start(effect);
    assertThat(holder.running()).hasSize(1);
    for (int tick = 1; tick <= 100; tick++) {
      holder.runPass(tick);
    }
    assertThat(holder.running()).as("still listed after a hundred steps").hasSize(1);
    assertThat(holder.running().get(0).isFinished()).isFalse();
  }

  @Test
  @DisplayName("an effect row without a lasting flag has no run, so a tag it names never applies")
  void aPlainEffectHasNoRun() {
    ActionHolder plain = new ActionHolder();
    plain.start(GameData.actions().build("GoblinQueen_ActivationEffect", INERT_BINDING));
    assertThat(plain.running()).isEmpty();

    ActionHolder tagged = new ActionHolder();
    tagged.start(
        GameData.actions().build("GoblinHero_Flag_About_To_Disappear_Effect", INERT_BINDING));
    assertThat(tagged.running()).isEmpty();
    assertThat(tagged.tags()).as("the row's tag never reaches its owner").isZero();
  }

  @Test
  @DisplayName("the king's starting group builds from its rows, the effect filter included")
  void theKingsStartingGroupBuilds() {
    BattleAction group = GameData.actions().build("KingTower_StartingGroup", INERT_BINDING);
    ActionHolder holder = new ActionHolder();
    holder.schedule(group, ActionHolder.OWN_DELAY);
    assertThat(queue(holder))
        .containsExactly("KingTower_StartingGroup 0", "WaitForKingTowerActivation 0");
  }

  @Test
  @DisplayName("every row of the data is built or refused by name, never failed")
  void everyRowIsBuiltOrRefused() {
    ActionRows rows = GameData.actions();
    int built = 0;
    Map<String, Integer> refusals = new TreeMap<>();
    List<String> failures = new ArrayList<>();
    for (String name : GameData.tables().actionNames()) {
      try {
        rows.build(name, INERT_BINDING);
        built++;
      } catch (UnsupportedOperationException e) {
        String message = e.getMessage();
        String reason =
            message.contains(" is an ")
                ? "class"
                : message.contains(" sets ")
                    ? "column"
                    : message.contains(" spawns ") ? "spawn type" : "other";
        refusals.merge(reason, 1, Integer::sum);
        if (reason.equals("other")) {
          failures.add(name + ": " + message);
        }
      } catch (RuntimeException e) {
        failures.add(name + ": " + e);
      }
    }
    assertThat(failures).as("rows that fail instead of being built or refused").isEmpty();
    assertThat(built + refusals.values().stream().mapToInt(Integer::intValue).sum())
        .isEqualTo(GameData.tables().actionNames().size());
    // Pinned, so a change in what the battle builds shows here: of 946 rows, 772 are built; the
    // rest are refused for their class, a column the battle does not model, a spawn type other
    // than characters, buffs and area effects, or a spawned buff or area effect the battle does
    // not model.
    assertThat(built).as("rows built").isEqualTo(772);
    assertThat(refusals)
        .containsExactlyInAnyOrderEntriesOf(Map.of("class", 88, "column", 73, "spawn type", 13));
  }
}
