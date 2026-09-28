package org.crforge.core.battle.data;

import static org.crforge.core.util.ValidationUtils.checkArgument;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.battle.match.BattleTimeline;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.unit.AbilityData;
import org.crforge.core.battle.unit.AreaEffectData;
import org.crforge.core.battle.unit.AttackSequence;
import org.crforge.core.battle.unit.BuffData;
import org.crforge.core.battle.unit.UnitData;
import org.crforge.core.pathfinding.combat.RarityTable;
import org.crforge.core.pathfinding.combat.ScalingMode;

/**
 * The battle's records built from the game's own rows.
 *
 * <p>Units, projectiles and troop cards. Every field is the column of the same name, in the
 * column's own units - milliseconds, game units, the published speed - and nothing is converted or
 * chosen. A column the row leaves empty is 0 or false. Only six fields are not a column: a unit
 * carries its row's global id; a unit flies when its flying height is above 0; a unit is a champion
 * when its ability row makes it one; a unit lists the columns of its death that its row sets and
 * the battle does not model; a projectile's damage scaling rule is named by its scaling mode
 * column, the king tower's or the princess towers', and is the card rule otherwise; and a rarity is
 * the published row of that name.
 */
public final class BattleRecords {

  private static final String CHARACTERS = "characters";
  private static final String BUILDINGS = "buildings";
  private static final String PROJECTILES = "projectiles";
  private static final String SPELLS_CHARACTERS = "spells_characters";
  private static final String SPELLS_OTHER = "spells_other";
  private static final String SPELLS_BUILDINGS = "spells_buildings";
  private static final String GAME_MODES = "game_modes";
  private static final String BATTLE_TIMELINES = "battle_timelines";
  private static final String GLOBALS = "globals";
  private static final String LOCATIONS = "locations";

  /** The section types of a battle timeline by name; the order is their number. */
  private static final List<String> SECTION_TYPES = List.of("Normal", "Overtime", "BonusTime");

  /**
   * The columns of a spell card the cast does not model yet: a Mirror, a first projectile of its
   * own, a spell deployed as a thrown projectile, and the play variants no reference holds. A spell
   * that sets one is refused.
   */
  private static final List<String> UNMODELLED_SPELL_COLUMNS =
      List.of("Mirror", "CustomFirstProjectile", "CustomClassType", "UseProjectedTimeSummon");

  /**
   * The columns of a projectile the impact does not model: the area effect it spawns, spawned
   * projectiles laid along an axis, and the push's floor and a push along the flight. A spell whose
   * projectile, or the projectile that one spawns, sets one is refused as it is cast or spawned,
   * and a unit's shot as it is fired.
   */
  private static final List<String> UNMODELLED_PROJECTILE_COLUMNS =
      List.of(
          "SpawnAreaEffectObject",
          "OnTargetReachedAction",
          "SpawnAxisX",
          "SpawnAxisY",
          "MinPushback",
          "DoDirectionalPushback");

  /** The target limit the loader stores for a projectile row that leaves it empty. */
  private static final int DEFAULT_MAXIMUM_TARGETS = 1000;

  /** The card columns the placement does not model; a card that sets one is refused. */
  private static final List<String> UNMODELLED_CARD_COLUMNS =
      List.of(
          "SummonCharactersList",
          "SummonCharactersOffsetsX",
          "SummonCharactersOffsetsY",
          "CustomDeployTime");

  private static final String CHARACTER_ABILITIES = "character_abilities";
  private static final String GAME_OBJECT_FILTERS = "game_object_filters";
  private static final String AREA_EFFECT_OBJECTS = "area_effect_objects";
  private static final String CHARACTER_BUFFS = "character_buffs";

  /** The columns of a buff the battle reads. */
  private static final Set<String> MODELLED_BUFF_COLUMNS =
      Set.of(
          "Name",
          "Rarity",
          "SpeedMultiplier",
          "HitSpeedMultiplier",
          "SpawnSpeedMultiplier",
          "HitFrequency",
          "DamagePerSecond",
          "CrownTowerDamagePerHit",
          "CrownTowerDamagePercent",
          "BuildingDamagePercent",
          "EnableStacking",
          "PlayerSpecificBuff",
          "NoEffectToCrownTowers",
          "IgnoreBuildings",
          "DeathSpawn",
          "DeathSpawnCount",
          "DeathSpawnRadius",
          "DeathSpawnSameLocation",
          "DeathSpawnIsEnemy",
          "DeathSpawnDeployDelay",
          "OtherBuffDeathSpawnAllowed");

  /** The columns of a buff that only show something: its effects, icons, filters and sounds. */
  private static final Set<String> PRESENTATION_BUFF_COLUMNS =
      Set.of(
          "AudioPitchModifier",
          "ContinuousEffect",
          "DeathEffectOverride",
          "Effect",
          "EffectScale",
          "FilterAffectsTransformation",
          "FilterExportName",
          "FilterFile",
          "FilterInheritLifeDuration",
          "HideEffectWhenUnderground",
          "HitEffect",
          "IconExportName",
          "IconFileName",
          "LoopContinuousEffect",
          "MarkEffect",
          "PreContinuousEffect",
          "PreContinuousEffectExclusiveTime",
          "ProjectileEffect",
          "RemoveEffect",
          "Scale",
          "ShadowAlpha",
          "StatsTags",
          "SwitchTeamContinuosEffect",
          "TID",
          "TopEffect",
          "TopEffectDisabledForAttachedCharacters",
          "TopEffectVerticalOffset",
          "UNUSED0");

  /**
   * The columns of an area effect the battle does not model: a row that sets one is refused as the
   * area effect is created. A buff that boosts one target or lasts longer by level, clones, the hit
   * action, the shape, the filter, the spawns and launches, the life condition, the following, the
   * tags, the deflection, the per-level lifetime and the push's floor and gate lift.
   */
  private static final List<String> UNMODELLED_AREA_EFFECT_COLUMNS =
      List.of(
          "Boost",
          "BuffTimeIncreasePerLevel",
          "BuffTimeIncreaseAfterTournamentCap",
          "Clone",
          "OnHitAction",
          "OnHitSelfAction",
          "Shape",
          "Filter",
          "SpawnCharacter",
          "Projectile",
          "AliveIfTrue",
          "FollowBehaviour",
          "Tags",
          "DeflectProjectilesEnabled",
          "LifeDurationIncreasePerLevel",
          "LifeDurationIncreaseAfterTournamentCap",
          "MinPushback",
          "PushbackAll",
          "OneHitPerTarget");

  private static final String GAME_TAGS = "game_tags";

  /**
   * The columns of what a unit does as it dies that the battle does not model: a unit whose row
   * sets one is refused when it dies. The elixir a death gives is not among them: the death handler
   * pays a player's unit's ManaOnDeathForOpponent to the side that killed it in a match, and pays
   * ManaOnDeath only for a neutral object, which the battle has none of.
   */
  private static final List<String> UNMODELLED_DEATH_COLUMNS =
      List.of(
          "DeathSpawnCharacter2",
          "DeathSpawnCharacter3",
          "DeathSpawnProjectile",
          "StartingBuff",
          "DeathSpawnIsSameUnit");

  /**
   * The columns that change where a unit's death spawn stands or what its children take, which the
   * battle does not model: refused only for a unit that spawns on its death. The inherited ignore
   * list is not among them: the id lists it joins are written only by such a death spawn and by a
   * rider let go under a parent that sets it, which is refused, so every list stays empty and the
   * column changes nothing.
   */
  private static final List<String> UNMODELLED_DEATH_SPAWN_COLUMNS =
      List.of("SpawnConstPriority", "SpawnLimit");

  /**
   * The columns of a unit the battle does not model, whatever it does: a unit whose row sets one is
   * refused as it is created. A shield, hiding while not attacking or before the first hit, a buff
   * at a share of its hit points, hovering, a flying unit's direct paths, the action a completed
   * charge runs, a chained dash, a dash's contact damage, fixed distance, area effect and closing
   * action, a limit on the elixir a collector makes, a spawner's launches, its second and third
   * characters, its destruction at the limit, the deploy it gives its children, and a Kamikaze
   * row's drain over a time rather than its kill.
   */
  private static final List<String> UNMODELLED_UNIT_COLUMNS =
      List.of(
          "ShieldDiePushback",
          "ShieldLostAction",
          "HidesWhenNotAttacking",
          "HideBeforeFirstHit",
          "BuffOnXHP",
          "Hovering",
          "FlyDirectPaths",
          "OnStartChargingAction",
          "DashCount",
          "DashingDamage",
          "DashDistance",
          "AreaEffectOnDash",
          "OnAfterDashAction",
          "ManaGenerateLimit",
          "SpawnProjectile",
          "SpawnCharacter2",
          "SpawnCharacter3",
          "DestroyAtLimit",
          "SpawnCharacterWithDeploy",
          "KamikazeTime");

  /**
   * The columns of a spawner the battle does not model, refused only for a unit whose spawner makes
   * characters: a limit on its waves and its push on its children.
   */
  private static final List<String> UNMODELLED_SPAWNER_COLUMNS =
      List.of("SpawnLimit", "SpawnPushback");

  private final GameTables tables;

  /**
   * @param tables the game tables of one data version
   */
  public BattleRecords(GameTables tables) {
    this.tables = tables;
  }

  /**
   * A unit or a building as the battle reads it, from the characters table or, for a building, the
   * buildings table.
   *
   * @param name the row's name
   */
  public UnitData unit(String name) {
    GameRow row = unitRow(name);
    String deathSpawn = row.string("DeathSpawnCharacter");
    return UnitData.builder()
        .name(row.name())
        .speed(row.intValue("Speed"))
        .range(row.intValue("Range"))
        .sightRange(row.intValue("SightRange"))
        .collisionRadius(row.intValue("CollisionRadius"))
        .mass(row.intValue("Mass"))
        .hitSpeedMs(row.intValue("HitSpeed"))
        .loadTimeMs(row.intValue("LoadTime"))
        .deployTimeMs(row.intValue("DeployTime"))
        .attacksGround(row.bool("AttacksGround"))
        .attacksAir(row.bool("AttacksAir"))
        .air(row.intValue("FlyingHeight") > 0)
        .building(row.bool("IsBuilding"))
        .king(row.bool("IsSummoner"))
        .summonerTower(row.bool("IsSummonerTower"))
        .hitpoints(row.intValue("Hitpoints"))
        .damage(row.intValue("Damage"))
        .crownTowerDamagePercent(row.intValue("CrownTowerDamagePercent"))
        .rarity(rarity(row.string("Rarity")))
        .projectile(
            row.string("Projectile").isEmpty() ? null : projectile(row.string("Projectile")))
        .customFirstProjectile(
            row.string("CustomFirstProjectile").isEmpty()
                ? null
                : projectile(row.string("CustomFirstProjectile")))
        .projectileStartRadius(row.intValue("ProjectileStartRadius"))
        .projectileStartZ(row.intValue("ProjectileStartZ"))
        .projectileYOffset(row.intValue("ProjectileYOffset"))
        .multipleProjectiles(row.intValue("MultipleProjectiles"))
        .areaDamageRadius(row.intValue("AreaDamageRadius"))
        .selfAsAoeCenter(row.bool("SelfAsAoeCenter"))
        .overrideAttackFinishTime(row.bool("OverrideAttackFinishTime"))
        .attackFinishTimeMs(row.intValue("AttackFinishTime"))
        .spawnRadius(row.intValue("SpawnRadius"))
        .spawnAngleShift(row.intValue("SpawnAngleShift"))
        .flyingHeight(row.intValue("FlyingHeight"))
        .spawnPathfindSpeed(row.intValue("SpawnPathfindSpeed"))
        .spawnPathfindMorph(
            row.string("SpawnPathfindMorph").isEmpty() ? null : row.string("SpawnPathfindMorph"))
        .spawnAreaObject(
            row.string("SpawnAreaObject").isEmpty() ? null : row.string("SpawnAreaObject"))
        .tileSizeOverride(row.intValue("TileSizeOverride"))
        .noDeploySizeW(row.intValue("NoDeploySizeW"))
        .noDeploySizeH(row.intValue("NoDeploySizeH"))
        .attackPushBack(row.intValue("AttackPushBack"))
        .ignorePushback(row.bool("IgnorePushback"))
        .onStartingAction(actionName(row, "OnStartingAction"))
        .onDeathAction(actionName(row, "OnDeathAction"))
        .onKilledAction(actionName(row, "OnKilledAction"))
        .deathDamage(row.intValue("DeathDamage"))
        .deathDamageRadius(row.intValue("DeathDamageRadius"))
        .deathPushBack(row.intValue("DeathPushBack"))
        .deathSpawnCharacter(deathSpawn.isEmpty() ? null : deathSpawn)
        // The loader keeps at least one child for a row that spawns on its death.
        .deathSpawnCount(deathSpawn.isEmpty() ? 0 : Math.max(row.intValue("DeathSpawnCount"), 1))
        .deathSpawnRadius(row.intValue("DeathSpawnRadius"))
        .deathSpawnDeployTimeMs(row.intValue("DeathSpawnDeployTime"))
        .deathAreaEffect(
            row.string("DeathAreaEffect").isEmpty() ? null : row.string("DeathAreaEffect"))
        .deathSpawnPushback(row.bool("DeathSpawnPushback"))
        .deathSpawnMinRadius(row.intValue("DeathSpawnMinRadius"))
        .unmodelledDeathColumns(unmodelledDeathColumns(row, !deathSpawn.isEmpty()))
        .champion(champion(row))
        .ability(ability(row))
        .globalId(row.globalId())
        .lifeTimeMs(row.intValue("LifeTime"))
        .targetOnlyBuildings(row.bool("TargetOnlyBuildings"))
        .attackSequence(attackSequence(row))
        .onStartingAttackAction(actionName(row, "OnStartingAttackAction"))
        .onAttackAction(actionName(row, "OnAttackAction"))
        .minimumRange(row.intValue("MinimumRange"))
        .spawnCharacter(
            row.string("SpawnCharacter").isEmpty() ? null : row.string("SpawnCharacter"))
        .spawnNumber(row.intValue("SpawnNumber"))
        .spawnIntervalMs(row.intValue("SpawnInterval"))
        .spawnPauseTimeMs(row.intValue("SpawnPauseTime"))
        .spawnStartTimeMs(row.intValue("SpawnStartTime"))
        .manaCollectAmount(row.intValue("ManaCollectAmount"))
        .manaGenerateTimeMs(row.intValue("ManaGenerateTimeMs"))
        .manaOnDeathForOpponent(row.intValue("ManaOnDeathForOpponent"))
        .ignoreBuffs(namesOf(row, "IgnoreBuff"))
        .shieldHitpoints(row.intValue("ShieldHitpoints"))
        .stopMovementAfterMs(row.intValue("StopMovementAfterMS"))
        .waitMs(row.intValue("WaitMS"))
        .deathInheritIgnoreList(row.bool("DeathInheritIgnoreList"))
        .spawnAttach(row.bool("SpawnAttach"))
        .spawnMaxAngle(row.intValue("SpawnMaxAngle"))
        .spawnAttachMaxRotation(row.intValue("SpawnAttachMaxRotation"))
        .chargeRange(row.intValue("ChargeRange"))
        .chargeSpeedMultiplier(row.intValue("ChargeSpeedMultiplier"))
        .damageSpecial(row.intValue("DamageSpecial"))
        .keepChargingAfterAttack(row.bool("KeepChargingAfterAttack"))
        .jumpEnabled(row.bool("JumpEnabled"))
        .jumpHeight(row.intValue("JumpHeight"))
        .jumpSpeed(row.intValue("JumpSpeed"))
        .kamikaze(row.bool("Kamikaze"))
        .multipleTargets(row.intValue("MultipleTargets"))
        .buffOnDamage(set(row, "BuffOnDamage") ? row.string("BuffOnDamage") : null)
        .dashCooldown(row.intValue("DashCooldown"))
        .dashMinRange(row.intValue("DashMinRange"))
        .dashMaxRange(row.intValue("DashMaxRange"))
        .dashDamage(row.intValue("DashDamage"))
        .dashRadius(row.intValue("DashRadius"))
        .dashPushBack(row.intValue("DashPushBack"))
        .dashLandingTimeMs(row.intValue("DashLandingTime"))
        .dashConstantTimeMs(row.intValue("DashConstantTime"))
        .dashImmuneToDamageTimeMs(row.intValue("DashImmuneToDamageTime"))
        .dashToTargetRadius(row.bool("DashToTargetRadius"))
        .targetOnlyTroops(row.bool("TargetOnlyTroops"))
        .ignoreTargetsWithBuff(
            row.string("IgnoreTargetsWithBuff").isEmpty()
                ? null
                : row.string("IgnoreTargetsWithBuff"))
        .deprioritizeTargetsWithBuff(row.bool("DeprioritizeTargetsWithBuff"))
        .unmodelledColumns(unmodelledColumns(row))
        .build();
  }

  /**
   * The columns a unit's row sets that the battle does not model: those of any unit, those of a
   * spawner for a row that spawns characters, and a spawner with neither a count nor an interval,
   * which spawns once as its deploy ends.
   */
  /** A column of row names, written as one name or a list of them. */
  private static List<String> namesOf(GameRow row, String column) {
    if (row.has(column) && row.value(column).isTextual()) {
      String name = row.string(column);
      return name.isEmpty() ? List.of() : List.of(name);
    }
    return row.strings(column);
  }

  private static List<String> unmodelledColumns(GameRow row) {
    List<String> columns = new ArrayList<>();
    for (String column : UNMODELLED_UNIT_COLUMNS) {
      if (sets(row, column)) {
        columns.add(column);
      }
    }
    if (!row.string("SpawnCharacter").isEmpty()) {
      for (String column : UNMODELLED_SPAWNER_COLUMNS) {
        if (sets(row, column)) {
          columns.add(column);
        }
      }
      if (row.intValue("SpawnNumber") == 0 && row.intValue("SpawnInterval") == 0) {
        columns.add("SpawnCharacter");
      }
    }
    return columns;
  }

  /**
   * A game object filter as the battle reads it, from the game object filters table. Every column
   * is its field of the same name; the dead are filtered unless the row says not; the tags it
   * excludes, written as names separated by commas, are the bits the game tags table gives them;
   * the text the game shows for it is not read.
   *
   * @param name the row's name
   */
  public GameObjectFilter filter(String name) {
    GameTable table = tables.table(GAME_OBJECT_FILTERS);
    checkArgument(table.has(name), () -> "the game tables have no game object filter " + name);
    GameRow row = table.row(name);
    return GameObjectFilter.builder()
        .matchTeamOwn(row.bool("MatchTeamOwn"))
        .matchTeamEnemy(row.bool("MatchTeamEnemy"))
        .matchTypeCharacters(row.bool("MatchTypeCharacters"))
        .matchTypeBuildings(row.bool("MatchTypeBuildings"))
        .matchTypeProjectiles(row.bool("MatchTypeProjectiles"))
        .matchTypeAoe(row.bool("MatchTypeAoe"))
        .matchTypeGoblinRef(row.bool("MatchTypeGoblinRef"))
        .matchTowers(row.bool("MatchTowers"))
        .filterHidden(row.bool("FilterHidden"))
        .filterInvisible(row.bool("FilterInvisible"))
        .filterUnderground(row.bool("FilterUnderground"))
        .filterBuildings(row.bool("FilterBuildings"))
        .filterTowers(row.bool("FilterTowers"))
        .filterSummoner(row.bool("FilterSummoner"))
        .filterFlying(row.bool("FilterFlying"))
        .filterJumping(row.bool("FilterJumping"))
        .filterDashImmune(row.bool("FilterDashImmune"))
        .filterDragging(row.bool("FilterDragging"))
        .filterCloning(row.bool("FilterCloning"))
        .filterIfNoHitpointComponent(row.bool("FilterIfNoHitpointComponent"))
        .filterPushbackIgnore(row.bool("FilterPushbackIgnore"))
        .matchAttachedChildren(row.bool("MatchAttachedChildren"))
        .filterSameObjects(row.bool("FilterSameObjects"))
        .filterTags(tagBits(row.string("FilterTags")))
        .filterPrincessTowers(row.bool("FilterPrincessTowers"))
        .filterDead(!row.has("FilterDead") || row.bool("FilterDead"))
        .filterClones(row.bool("FilterClones"))
        .includeCharactersWithData(Set.copyOf(row.strings("IncludeCharactersWithData")))
        .excludeCharactersWithData(Set.copyOf(row.strings("ExcludeCharactersWithData")))
        .build();
  }

  /** The bits of game tags written as names separated by commas; none for an empty text. */
  private long tagBits(String names) {
    long bits = 0;
    for (String name : names.split(",")) {
      String tag = name.trim();
      if (tag.isEmpty()) {
        continue;
      }
      GameTable tags = tables.table(GAME_TAGS);
      checkArgument(tags.has(tag), () -> "the game tables have no game tag " + tag);
      bits |= 1L << tags.row(tag).index();
    }
    return bits;
  }

  /** The attack sequence modes by name; any other name is 0. */
  private static final Map<String, Integer> SEQUENCE_MODES =
      Map.of("None", 0, "StaticLoop", 1, "HittimeLoop", 2, "Hittime", 3, "Manual", 4);

  /**
   * A row's attack sequence as the loader builds it. The order is the AttackSequence column, and
   * the mode is read only when the order has an element, an empty name there giving 1. With an
   * AttackSequenceList, one entry per element, and the order a single 0 when the row has none.
   * Without a list, entry 0 comes from the row's own columns; with an order, entries 1 and 2 come
   * from the columns numbered 2 and 3; without one, the order is a single 0, and while the last
   * entry has a variable damage time the next numbered entry joins it and the order, the first of
   * them making the mode 3.
   */
  private AttackSequence attackSequence(GameRow row) {
    List<Integer> order = new ArrayList<>();
    JsonNode orderColumn = row.value("AttackSequence");
    if (orderColumn != null && orderColumn.isArray()) {
      orderColumn.forEach(element -> order.add(element.asInt()));
    }
    int mode = 0;
    if (!order.isEmpty()) {
      String name = row.string("AttackSequenceMode");
      mode = name.isEmpty() ? 1 : SEQUENCE_MODES.getOrDefault(name, 0);
    }
    List<AttackSequence.Entry> entries = new ArrayList<>();
    JsonNode list = row.value("AttackSequenceList");
    if (list != null && list.isArray() && !list.isEmpty()) {
      for (JsonNode element : list) {
        entries.add(listEntry(element));
      }
      if (order.isEmpty()) {
        order.add(0);
      }
      return new AttackSequence(mode, order, entries);
    }
    entries.add(
        entry(
            row.intValue("Damage"),
            row.string("Projectile"),
            row.intValue("VariableDamageTime1"),
            row.intValue("MeleePushback")));
    if (!order.isEmpty()) {
      entries.add(numbered(row, 2));
      entries.add(numbered(row, 3));
      return new AttackSequence(mode, order, entries);
    }
    order.add(0);
    for (int n = 2; n <= 3; n++) {
      if (entries.get(entries.size() - 1).variableDamageTime() < 1) {
        break;
      }
      entries.add(numbered(row, n));
      order.add(n - 1);
      if (n == 2) {
        mode = 3;
      }
    }
    return new AttackSequence(mode, order, entries);
  }

  /** The entry the columns numbered n make; the third has no variable damage time. */
  private AttackSequence.Entry numbered(GameRow row, int n) {
    return entry(
        row.intValue("VariableDamage" + n),
        row.string("Projectile" + n),
        n < 3 ? row.intValue("VariableDamageTime" + n) : 0,
        row.intValue("MeleePushback" + n));
  }

  /** An entry of the four columns the row's own and numbered entries carry, the rest defaults. */
  private AttackSequence.Entry entry(
      int damage, String projectile, int variableDamageTime, int meleePushback) {
    return new AttackSequence.Entry(
        damage,
        projectile.isEmpty() ? null : projectile(projectile),
        variableDamageTime,
        100,
        -1,
        -1,
        -1,
        -1,
        -1,
        meleePushback,
        null);
  }

  /** An entry of an AttackSequenceList element, with the entry columns' defaults. */
  private AttackSequence.Entry listEntry(JsonNode element) {
    String projectile = element.path("Projectile").asText("");
    JsonNode action = element.path("DoAttackAction");
    return new AttackSequence.Entry(
        element.path("Damage").asInt(0),
        projectile.isEmpty() ? null : projectile(projectile),
        element.path("VariableDamageTime").asInt(0),
        element.path("HitSpeedMultiplier").asInt(100),
        element.path("CustomRange").asInt(-1),
        element.path("CustomSightRange").asInt(-1),
        element.path("CustomMinimunRange").asInt(-1),
        element.path("CustomProjectileStartZ").asInt(-1),
        element.path("CustomProjectileStartRadius").asInt(-1),
        element.path("MeleePushback").asInt(0),
        action.isMissingNode() || action.isNull() ? null : action.toString());
  }

  /** The columns of a row's death that are not modelled, those of its death spawn only with one. */
  private static List<String> unmodelledDeathColumns(GameRow row, boolean spawns) {
    List<String> columns = new ArrayList<>();
    for (String column : UNMODELLED_DEATH_COLUMNS) {
      if (sets(row, column)) {
        columns.add(column);
      }
    }
    if (spawns) {
      for (String column : UNMODELLED_DEATH_SPAWN_COLUMNS) {
        if (sets(row, column)) {
          columns.add(column);
        }
      }
    }
    return columns;
  }

  /**
   * An area effect as the battle reads it, from the area effect objects table.
   *
   * @param name the row's name
   */
  public AreaEffectData areaEffect(String name) {
    GameTable table = tables.table(AREA_EFFECT_OBJECTS);
    checkArgument(table.has(name), () -> "the game tables have no area effect " + name);
    GameRow row = table.row(name);
    List<String> unmodelled = new ArrayList<>();
    for (String column : UNMODELLED_AREA_EFFECT_COLUMNS) {
      if (sets(row, column)) {
        unmodelled.add(column);
      }
    }
    return AreaEffectData.builder()
        .name(row.name())
        .rarity(rarity(row.string("Rarity")))
        .lifeDurationMs(row.intValue("LifeDuration"))
        .radius(row.intValue("Radius"))
        .maxRadius(row.intValue("MaxRadius"))
        .hitSpeedMs(row.intValue("HitSpeed"))
        .hitSpeedOffsetMs(row.intValue("HitSpeedOffset"))
        .damage(row.intValue("Damage"))
        .crownTowerDamagePercent(row.intValue("CrownTowerDamagePercent"))
        .hitsAir(row.bool("HitsAir"))
        .hitsGround(row.bool("HitsGround"))
        .onlyEnemies(row.bool("OnlyEnemies"))
        .ignoreBuildings(row.bool("IgnoreBuildings"))
        .affectsHidden(row.bool("AffectsHidden"))
        .pushback(row.intValue("Pushback"))
        .maximumTargets(row.intValue("MaximumTargets"))
        .sharedDamage(row.bool("SharedDamage"))
        .onStartingAction(actionName(row, "OnStartingAction"))
        .onLifeTimeEndAction(actionName(row, "OnLifeTimeEndAction"))
        .buff(row.string("Buff").isEmpty() ? null : row.string("Buff"))
        .buffTimeMs(row.intValue("BuffTime"))
        .capBuffTimeToAreaEffectTime(row.bool("CapBuffTimeToAreaEffectTime"))
        .onlyOwnTroops(row.bool("OnlyOwnTroops"))
        .spawnAreaEffectObject(
            row.string("SpawnAreaEffectObject").isEmpty()
                ? null
                : row.string("SpawnAreaEffectObject"))
        .unmodelledColumns(unmodelled)
        .build();
  }

  /**
   * A character buff as the battle reads it, from the character buffs table. Every column it sets
   * that is neither read nor only shows something is listed as not modelled.
   *
   * @param name the row's name
   */
  public BuffData buff(String name) {
    GameTable table = tables.table(CHARACTER_BUFFS);
    checkArgument(table.has(name), () -> "the game tables have no buff " + name);
    GameRow row = table.row(name);
    List<String> unmodelled = new ArrayList<>();
    for (String column : row.columns().keySet()) {
      if (!MODELLED_BUFF_COLUMNS.contains(column)
          && !PRESENTATION_BUFF_COLUMNS.contains(column)
          && sets(row, column)) {
        unmodelled.add(column);
      }
    }
    Collections.sort(unmodelled);
    return BuffData.builder()
        .name(row.name())
        .rarity(rarity(row.string("Rarity")))
        .speedMultiplier(row.intValue("SpeedMultiplier"))
        .hitSpeedMultiplier(row.intValue("HitSpeedMultiplier"))
        .spawnSpeedMultiplier(row.intValue("SpawnSpeedMultiplier"))
        .hitFrequency(row.intValue("HitFrequency"))
        .damagePerSecond(row.intValue("DamagePerSecond"))
        .crownTowerDamagePerHit(row.intValue("CrownTowerDamagePerHit"))
        .crownTowerDamagePercent(row.intValue("CrownTowerDamagePercent"))
        .buildingDamagePercent(row.intValue("BuildingDamagePercent"))
        .enableStacking(row.bool("EnableStacking"))
        .playerSpecificBuff(row.bool("PlayerSpecificBuff"))
        .noEffectToCrownTowers(row.bool("NoEffectToCrownTowers"))
        .ignoreBuildings(row.bool("IgnoreBuildings"))
        .deathSpawn(sets(row, "DeathSpawn") ? row.string("DeathSpawn") : null)
        .deathSpawnCount(row.intValue("DeathSpawnCount"))
        .deathSpawnRadius(row.intValue("DeathSpawnRadius"))
        .deathSpawnSameLocation(row.bool("DeathSpawnSameLocation"))
        .deathSpawnIsEnemy(row.bool("DeathSpawnIsEnemy"))
        .deathSpawnDeployDelay(row.bool("DeathSpawnDeployDelay"))
        .otherBuffDeathSpawnAllowed(row.bool("OtherBuffDeathSpawnAllowed"))
        .unmodelledColumns(unmodelled)
        .build();
  }

  /** True when a row sets a column: a value other than empty, 0 or false. */
  private static boolean sets(GameRow row, String column) {
    JsonNode value = row.value(column);
    if (value == null || value.isNull()) {
      return false;
    }
    if (value.isTextual()) {
      return !value.asText().isEmpty();
    }
    if (value.isBoolean()) {
      return value.asBoolean();
    }
    if (value.isArray()) {
      return !value.isEmpty();
    }
    return value.asInt() != 0;
  }

  /**
   * Whether a unit is a champion: its ability row says so, and an ability row that says nothing
   * makes it one. A unit without an ability is none.
   */
  private boolean champion(GameRow row) {
    String ability = row.string("Ability");
    if (ability.isEmpty()) {
      return false;
    }
    GameTable abilities = tables.table(CHARACTER_ABILITIES);
    checkArgument(
        abilities.has(ability),
        () -> row.name() + " names the ability " + ability + ", which the game tables lack");
    GameRow abilityRow = abilities.row(ability);
    return !abilityRow.has("IsChampion") || abilityRow.bool("IsChampion");
  }

  /**
   * The ability columns that make an ability do more than run its activation action - its dash,
   * buff, area object, lane switch, morph, spawn and follow-up state - or keep a buff on a unit
   * waiting to cast; the rest are the champion controller's or presentation, which a request never
   * reads.
   */
  private static final List<String> UNMODELLED_ABILITY_COLUMNS =
      List.of(
          "DashRange",
          "Buff",
          "AreaEffectObject",
          "SwitchLanes",
          "MorphTarget",
          "ActivationSpawnCharacter",
          "AbilityStateDuration",
          "PendingBuff");

  /**
   * A unit's ability row, or null for a unit without one. Its activation action, written inline, is
   * the actions table's row named after the ability and the column.
   */
  private AbilityData ability(GameRow row) {
    String name = row.string("Ability");
    if (name.isEmpty()) {
      return null;
    }
    GameTable abilities = tables.table(CHARACTER_ABILITIES);
    checkArgument(
        abilities.has(name),
        () -> row.name() + " names the ability " + name + ", which the game tables lack");
    GameRow ability = abilities.row(name);
    return AbilityData.builder()
        .name(name)
        .castTimeMs(ability.intValue("CastTime"))
        .triggerDelayMs(ability.intValue("TriggerDelay"))
        .keepCurrentTarget(ability.bool("KeepCurrentTarget"))
        .champion(!ability.has("IsChampion") || ability.bool("IsChampion"))
        .onActivationAction(inlineActionName(ability, "OnActivationAction"))
        .unmodelledColumns(
            UNMODELLED_ABILITY_COLUMNS.stream().filter(column -> set(ability, column)).toList())
        .build();
  }

  /**
   * The action row a column names, or, for a row written inline, the actions table's row named
   * after the row and the column; null for none.
   */
  private String inlineActionName(GameRow row, String column) {
    JsonNode value = row.value(column);
    if (value == null || value.isNull()) {
      return null;
    }
    if (value.isObject() && !value.has("action")) {
      String inline = row.name() + "_" + column;
      checkArgument(
          tables.actionNames().contains(inline),
          () -> row.name() + " writes its " + column + " inline, with no row " + inline);
      return inline;
    }
    String name = value.isObject() ? value.path("action").asText("") : value.asText();
    return name.isEmpty() ? null : name;
  }

  /**
   * A projectile as the battle reads it, from the projectiles table.
   *
   * @param name the row's name
   */
  public ProjectileData projectile(String name) {
    GameTable table = tables.table(PROJECTILES);
    checkArgument(table.has(name), () -> "the game tables have no projectile " + name);
    GameRow row = table.row(name);
    ProjectileData data =
        ProjectileData.builder()
            .name(row.name())
            .rarity(rarity(row.string("Rarity")))
            .speed(row.intValue("Speed"))
            .gravity(row.intValue("Gravity"))
            .homing(row.bool("Homing"))
            .homingTimeMs(row.intValue("HomingTime"))
            .homingMinDistance(row.intValue("HomingMinDistance"))
            .damage(row.intValue("Damage"))
            .crownTowerDamagePercent(row.intValue("CrownTowerDamagePercent"))
            .damageMode(damageMode(row.string("DamageScalingMode")))
            .radius(row.intValue("Radius"))
            .aoeToAir(row.bool("AoeToAir"))
            .aoeToGround(row.bool("AoeToGround"))
            .onlyEnemies(row.bool("OnlyEnemies"))
            .projectileRadius(row.intValue("ProjectileRadius"))
            .projectileRange(row.intValue("ProjectileRange"))
            .checkCollisions(row.bool("CheckCollisions"))
            .minDistance(row.intValue("MinDistance"))
            .circleScatter("Circle".equals(row.string("Scatter")))
            .lineScatter("Line".equals(row.string("Scatter")))
            .pushback(row.intValue("Pushback"))
            .spawnCharacter(set(row, "SpawnCharacter") ? row.string("SpawnCharacter") : null)
            // The loader stores at least one child for a row that names a spawned character.
            .spawnCharacterCount(
                set(row, "SpawnCharacter") ? Math.max(row.intValue("SpawnCharacterCount"), 1) : 0)
            .spawnCharacterDeployTimeMs(row.intValue("SpawnCharacterDeployTime"))
            .radiusY(row.intValue("RadiusY"))
            .projectileRadiusY(row.intValue("ProjectileRadiusY"))
            .projectileStartExtraRadius(row.intValue("ProjectileStartExtraRadius"))
            .pushbackAll(row.bool("PushbackAll"))
            .spawnProjectile(set(row, "SpawnProjectile") ? row.string("SpawnProjectile") : null)
            // The loader stores at least one link for a row that names a spawned projectile.
            .spawnChain(set(row, "SpawnProjectile") ? Math.max(row.intValue("SpawnChain"), 1) : 0)
            .constantHeight(row.intValue("ConstantHeight"))
            .targetBuff(set(row, "TargetBuff") ? row.string("TargetBuff") : null)
            .applyBuffBeforeDamage(row.bool("ApplyBuffBeforeDamage"))
            .applyBuffEvenIfImmuneToDamage(row.bool("ApplyBuffEvenIfImmuneToDamage"))
            .buffTimeMs(row.intValue("BuffTime"))
            .buffTimeIncreasePerLevel(row.intValue("BuffTimeIncreasePerLevel"))
            // The loader stores 1000 for an empty target limit.
            .maximumTargets(
                set(row, "MaximumTargets")
                    ? row.intValue("MaximumTargets")
                    : DEFAULT_MAXIMUM_TARGETS)
            .onlyOwnTroops(row.bool("OnlyOwnTroops"))
            .spawnCount(row.intValue("SpawnCount"))
            .spawnRadius(row.intValue("SpawnRadius"))
            .chainedHitRadius(row.intValue("ChainedHitRadius"))
            .chainedHitCount(row.intValue("ChainedHitCount"))
            .pingpongVisualTimeMs(row.intValue("PingpongVisualTime"))
            .randomDelayMs(row.intValue("RandomDelay"))
            .onHitTargetAction(inlineActionName(row, "OnHitTargetAction"))
            .build();
    List<String> unmodelled =
        new ArrayList<>(
            UNMODELLED_PROJECTILE_COLUMNS.stream().filter(column -> set(row, column)).toList());
    // The target buff is modelled on the circle or the one target of the impact; a projectile that
    // flies to a point buffs through its hits on the way instead, which is not.
    if (data.targetBuff() != null && data.homingLike()) {
      unmodelled.add(0, "TargetBuff");
    }
    return data.toBuilder().unmodelledColumns(unmodelled).build();
  }

  /**
   * A troop or building card's placement as the battle reads it, from the spells characters table
   * or the spells buildings table: the units it summons, built from their own rows, how many, its
   * formation and where it may be placed. A building card is played as a troop card is; its unit is
   * a buildings-table row, whose footprint the placement snaps and searches over.
   *
   * <p>A card that names no unit - no summoned character, no second group and no list - but a
   * projectile or an area effect is a spell, whichever table it is in: the Electro Wizard and the
   * Ice Wizard are cast, and their unit is made by their area effect's starting action. Deploying
   * as a spell changes nothing else for a card: it only moves a projectile spell's start and, for a
   * card with a unit and a projectile or an area effect, the search's snap, which no card sets
   * together and which is refused.
   *
   * <p>A card with no count summons one. The level index a card may carry is not read, as the game
   * never reads it: the summoned units take the level the card is played at. A card that summons a
   * list of characters, places them at offsets of its own or has a deploy time of its own is
   * refused, naming the column: the placement does not model those.
   *
   * @param name the card row's name
   */
  public DeployCard card(String name) {
    GameTable table = tables.table(SPELLS_CHARACTERS);
    if (!table.has(name) && tables.table(SPELLS_BUILDINGS).has(name)) {
      table = tables.table(SPELLS_BUILDINGS);
    } else if (!table.has(name) && tables.table(SPELLS_OTHER).has(name)) {
      table = tables.table(SPELLS_OTHER);
    }
    checkArgument(table.has(name), () -> "the game tables have no card " + name);
    GameRow row = table.row(name);
    // The card's unit is its summoned character, else its second group, else its list; a card with
    // none of them that casts is a spell, whichever table it is in. A card with a unit keeps the
    // troop path, and its refusals, whatever it casts besides.
    boolean namesUnit =
        set(row, "SummonCharacter")
            || set(row, "SummonCharacterSecond")
            || set(row, "SummonCharactersList");
    boolean casts = set(row, "Projectile") || set(row, "AreaEffectObject");
    if (!namesUnit && casts) {
      return spell(row);
    }
    if (casts && row.bool("SpellAsDeploy")) {
      throw new UnsupportedOperationException(
          name
              + " deploys as a spell with a unit and a cast, which clears the search's snap and is"
              + " not modelled");
    }
    for (String column : UNMODELLED_CARD_COLUMNS) {
      if (set(row, column)) {
        throw new UnsupportedOperationException(
            name + " sets " + column + ", which the card placement does not model");
      }
    }
    checkArgument(!row.string("SummonCharacter").isEmpty(), () -> name + " summons no character");
    String second = row.string("SummonCharacterSecond");
    UnitData summoned = unit(row.string("SummonCharacter"));
    return new DeployCard(
        row.name(),
        summoned,
        Math.max(row.intValue("SummonNumber"), 1),
        second.isEmpty() ? null : unit(second),
        second.isEmpty() ? 0 : row.intValue("SummonCharacterSecondCount"),
        row.intValue("SummonRadius"),
        row.intValue("SummonWidth"),
        row.intValue("SummonDeployDelay"),
        row.intValue("SummonDeployDelaySecond"),
        row.bool("CanDeployOnEnemySide"),
        row.bool("CanPlaceOnBuildings"),
        row.bool("CanPlaceOnWater"),
        row.bool("FullLaneDeploy"),
        row.bool("TouchdownLimitedDeploy"),
        row.intValue("DeployWTileMargin"),
        row.intValue("DeployStartY"),
        row.intValue("DeployEndY"),
        null,
        null,
        // A unit that tunnels and morphs as it surfaces is searched for as its morph.
        tunnelMorph(summoned),
        false,
        0,
        0,
        0,
        0,
        0);
  }

  /** The row a unit that tunnels morphs into as it surfaces, or null for none. */
  private UnitData tunnelMorph(UnitData unit) {
    return unit.spawnPathfindMorph() == null ? null : unit(unit.spawnPathfindMorph());
  }

  /**
   * A spell card: no unit, the projectile it casts or the area effect it creates, and the unit its
   * placement is searched for - its projectile's spawned character, which the Goblin Barrel has.
   * The offsets a spell card may list for its characters are not read by the cast.
   */
  private DeployCard spell(GameRow row) {
    for (String column : UNMODELLED_SPELL_COLUMNS) {
      if (set(row, column)) {
        throw new UnsupportedOperationException(
            row.name() + " sets " + column + ", which the spell's cast does not model");
      }
    }
    String projectile = set(row, "Projectile") ? row.string("Projectile") : null;
    String areaEffect = set(row, "AreaEffectObject") ? row.string("AreaEffectObject") : null;
    UnitData searchUnit = null;
    if (projectile != null) {
      GameRow projectileRow = tables.table(PROJECTILES).row(projectile);
      if (set(projectileRow, "SpawnCharacter")) {
        searchUnit = unit(projectileRow.string("SpawnCharacter"));
        UnitData morph = tunnelMorph(searchUnit);
        if (morph != null) {
          searchUnit = morph;
        }
      }
    }
    return new DeployCard(
        row.name(),
        null,
        0,
        null,
        0,
        0,
        0,
        0,
        0,
        row.bool("CanDeployOnEnemySide"),
        row.bool("CanPlaceOnBuildings"),
        row.bool("CanPlaceOnWater"),
        row.bool("FullLaneDeploy"),
        row.bool("TouchdownLimitedDeploy"),
        row.intValue("DeployWTileMargin"),
        row.intValue("DeployStartY"),
        row.intValue("DeployEndY"),
        projectile,
        areaEffect,
        searchUnit,
        row.bool("SpellAsDeploy"),
        row.intValue("Radius"),
        row.intValue("MultipleProjectiles"),
        row.intValue("ProjectileWaves"),
        row.intValue("ProjectileWaveInterval"),
        row.intValue("ProjectileInterval"));
  }

  /** True when a row sets a column: a value that is not empty, false, 0 or an empty list. */
  private static boolean set(GameRow row, String column) {
    JsonNode value = row.value(column);
    if (value == null || value.isNull()) {
      return false;
    }
    if (value.isTextual()) {
      return !value.asText().isEmpty();
    }
    if (value.isBoolean()) {
      return value.asBoolean();
    }
    if (value.isNumber()) {
      return value.asInt() != 0;
    }
    return !value.isEmpty();
  }

  /**
   * The action row a hook column names, as a reference or by its name; null for none. A hook
   * written as an inline row of its own, with no name to build it by, is refused, so it is never
   * read as no hook at all.
   */
  private static String actionName(GameRow row, String column) {
    JsonNode value = row.value(column);
    if (value == null || value.isNull()) {
      return null;
    }
    if (value.isObject() && !value.has("action")) {
      throw new UnsupportedOperationException(
          row.name()
              + " writes its "
              + column
              + " inline, as "
              + value.path("ClassType").asText("an unnamed row")
              + ", which is not modelled");
    }
    String name = value.isObject() ? value.path("action").asText("") : value.asText();
    return name.isEmpty() ? null : name;
  }

  /** The row of a unit: the characters table's, else the buildings table's. */
  /**
   * The global id of the character or building row of that name, as an expression names it: the
   * characters are asked first. Null for a name that is neither.
   *
   * @param name the row's name, matched exactly
   */
  public Integer unitGlobalId(String name) {
    for (String table : new String[] {CHARACTERS, BUILDINGS}) {
      GameTable rows = tables.table(table);
      if (rows.has(name)) {
        return rows.row(name).globalId();
      }
    }
    return null;
  }

  private GameRow unitRow(String name) {
    for (String table : new String[] {CHARACTERS, BUILDINGS}) {
      GameTable rows = tables.table(table);
      if (rows.has(name)) {
        return rows.row(name);
      }
    }
    throw new IllegalArgumentException("the game tables have no unit " + name);
  }

  /** The published rarity row of that name; a row without a rarity is scaled as Common. */
  private static RarityTable rarity(String name) {
    if (name.isEmpty()) {
      return RarityTable.COMMON;
    }
    for (RarityTable rarity : RarityTable.PUBLISHED) {
      if (rarity.name().equals(name)) {
        return rarity;
      }
    }
    throw new IllegalArgumentException("no published rarity " + name);
  }

  /** The scaling rule a damage scaling mode names: the two tower rules, else the card rule. */
  private static ScalingMode damageMode(String mode) {
    return switch (mode) {
      case "KingTower" -> ScalingMode.KING_DAMAGE;
      case "PrincessTower" -> ScalingMode.TOWER_DAMAGE;
      default -> ScalingMode.CARD_DAMAGE;
    };
  }

  /**
   * The battle timeline of a game mode: its row's BattleTimeline, read from the battle timelines
   * table. Each array column is as long as the row gives it; section flags left out are 0.
   *
   * @param gameMode the game mode row's name
   */
  public BattleTimeline gameModeTimeline(String gameMode) {
    GameTable modes = tables.table(GAME_MODES);
    checkArgument(modes.has(gameMode), () -> "the game tables have no game mode " + gameMode);
    String name = modes.row(gameMode).string("BattleTimeline");
    GameTable timelines = tables.table(BATTLE_TIMELINES);
    checkArgument(timelines.has(name), () -> "the game tables have no battle timeline " + name);
    GameRow row = timelines.row(name);
    List<Integer> types = new ArrayList<>();
    for (String type : row.strings("SectionType")) {
      int number = SECTION_TYPES.indexOf(type);
      checkArgument(number >= 0, () -> name + " has a section of type " + type);
      types.add(number);
    }
    List<Boolean> notify = new ArrayList<>();
    for (JsonNode element : arrayOf(row, "ElixirNotifyChange")) {
      notify.add(element.asBoolean());
    }
    return new BattleTimeline(
        row.name(),
        row.intValue("StartingElixir"),
        ints(row, "SectionLength"),
        types,
        ints(row, "SectionFlags"),
        ints(row, "ElixirRateLength"),
        ints(row, "ElixirFullBarMS"),
        ints(row, "ElixirRateVisible"),
        notify,
        ints(row, "NextSpellCooldownLength"),
        ints(row, "NextSpellCooldownMS"),
        ints(row, "EventTime"));
  }

  /**
   * What the match reads of a card: its cost, its two opening-hand columns, its production stop and
   * whether it is the Mirror. The card is looked up in the three card tables in turn.
   *
   * @param name the card row's name
   */
  public MatchCard matchCard(String name) {
    GameRow row = null;
    for (String table : List.of(SPELLS_CHARACTERS, SPELLS_BUILDINGS, SPELLS_OTHER)) {
      if (tables.table(table).has(name)) {
        row = tables.table(table).row(name);
        break;
      }
    }
    checkArgument(row != null, () -> "the game tables have no card " + name);
    return new MatchCard(
        row.name(),
        row.intValue("ManaCost"),
        row.bool("ForceToStartingHand"),
        row.bool("OmitFromStartingHand"),
        row.intValue("ElixirProductionStopTime"),
        row.bool("Mirror"));
  }

  /**
   * The end screen's delay, in milliseconds: how long a battle goes on after its end before it
   * stops. It is the location's, and every location has the same one; the battle's location is not
   * read, so one that differed would be refused here.
   */
  public int endScreenDelayMs() {
    Set<Integer> delays = new TreeSet<>();
    for (GameRow row : tables.table(LOCATIONS).rows()) {
      delays.add(row.intValue("EndScreenDelay"));
    }
    checkArgument(delays.size() == 1, () -> "the locations' end screen delays differ: " + delays);
    return delays.iterator().next();
  }

  /**
   * A published global's number.
   *
   * @param name the global's name
   */
  public int globalNumber(String name) {
    GameTable globals = tables.table(GLOBALS);
    checkArgument(globals.has(name), () -> "the game tables have no global " + name);
    return globals.row(name).intValue("NumberValue");
  }

  /** An array column of whole numbers; an empty list for a column left out. */
  private static List<Integer> ints(GameRow row, String column) {
    List<Integer> out = new ArrayList<>();
    for (JsonNode element : arrayOf(row, column)) {
      out.add(element.asInt());
    }
    return out;
  }

  /** An array column's elements; none for a column left out or not an array. */
  private static List<JsonNode> arrayOf(GameRow row, String column) {
    List<JsonNode> out = new ArrayList<>();
    JsonNode value = row.value(column);
    if (value != null && value.isArray()) {
      value.forEach(out::add);
    }
    return out;
  }
}
