package org.crforge.core.battle.data;

import static org.crforge.core.util.ValidationUtils.checkArgument;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.unit.AreaEffectData;
import org.crforge.core.battle.unit.AttackSequence;
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

  /** The card columns the placement does not model; a card that sets one is refused. */
  private static final List<String> UNMODELLED_CARD_COLUMNS =
      List.of(
          "SummonCharactersList",
          "SummonCharactersOffsetsX",
          "SummonCharactersOffsetsY",
          "CustomDeployTime",
          "SpellAsDeploy");

  private static final String CHARACTER_ABILITIES = "character_abilities";
  private static final String GAME_OBJECT_FILTERS = "game_object_filters";
  private static final String AREA_EFFECT_OBJECTS = "area_effect_objects";

  /**
   * The columns of an area effect the battle does not model: a row that sets one is refused as the
   * area effect is created. Buffs and everything the buff block does, clones, the hit action, the
   * shape, the filter, the spawns and launches, the chained area effect, the life condition, the
   * following, the tags, the deflection, the per-level lifetime and the push's floor and gate lift.
   */
  private static final List<String> UNMODELLED_AREA_EFFECT_COLUMNS =
      List.of(
          "Buff",
          "Clone",
          "OnHitAction",
          "OnHitSelfAction",
          "Shape",
          "Filter",
          "SpawnCharacter",
          "Projectile",
          "SpawnAreaEffectObject",
          "AliveIfTrue",
          "FollowBehaviour",
          "Tags",
          "DeflectProjectilesEnabled",
          "LifeDurationIncreasePerLevel",
          "LifeDurationIncreaseAfterTournamentCap",
          "MinPushback",
          "PushbackAll",
          "AffectsHidden",
          "OneHitPerTarget");

  private static final String GAME_TAGS = "game_tags";

  /**
   * The columns of what a unit does as it dies that the battle does not model: a unit whose row
   * sets one is refused when it dies. The elixir a death gives is not among them: the battle models
   * no elixir at all.
   */
  private static final List<String> UNMODELLED_DEATH_COLUMNS =
      List.of(
          "DeathSpawnCharacter2",
          "DeathSpawnCharacter3",
          "DeathSpawnProjectile",
          "StartingBuff",
          "SpawnAreaObject");

  /**
   * The columns that change where a unit's death spawn stands or what its children take, which the
   * battle does not model: refused only for a unit that spawns on its death.
   */
  private static final List<String> UNMODELLED_DEATH_SPAWN_COLUMNS =
      List.of(
          "DeathSpawnPushback",
          "DeathSpawnMinRadius",
          "DeathInheritIgnoreList",
          "SpawnConstPriority",
          "SpawnLimit",
          "SpawnAngleShift");

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
        .unmodelledDeathColumns(unmodelledDeathColumns(row, !deathSpawn.isEmpty()))
        .champion(champion(row))
        .globalId(row.globalId())
        .lifeTimeMs(row.intValue("LifeTime"))
        .targetOnlyBuildings(row.bool("TargetOnlyBuildings"))
        .attackSequence(attackSequence(row))
        .onStartingAttackAction(actionName(row, "OnStartingAttackAction"))
        .onAttackAction(actionName(row, "OnAttackAction"))
        .build();
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
        .pushback(row.intValue("Pushback"))
        .maximumTargets(row.intValue("MaximumTargets"))
        .sharedDamage(row.bool("SharedDamage"))
        .onStartingAction(actionName(row, "OnStartingAction"))
        .onLifeTimeEndAction(actionName(row, "OnLifeTimeEndAction"))
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
   * A projectile as the battle reads it, from the projectiles table.
   *
   * @param name the row's name
   */
  public ProjectileData projectile(String name) {
    GameTable table = tables.table(PROJECTILES);
    checkArgument(table.has(name), () -> "the game tables have no projectile " + name);
    GameRow row = table.row(name);
    return ProjectileData.builder()
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
        .build();
  }

  /**
   * A troop card's placement as the battle reads it, from the spells characters table: the units it
   * summons, built from their own rows, how many, its formation and where it may be placed.
   *
   * <p>A card with no count summons one. The level index a card may carry is not read, as the game
   * never reads it: the summoned units take the level the card is played at. A card that summons a
   * list of characters, places them at offsets of its own, has a deploy time of its own or deploys
   * as a spell is refused, naming the column: the placement does not model those.
   *
   * @param name the card row's name
   */
  public DeployCard card(String name) {
    GameTable table = tables.table(SPELLS_CHARACTERS);
    checkArgument(table.has(name), () -> "the game tables have no troop card " + name);
    GameRow row = table.row(name);
    for (String column : UNMODELLED_CARD_COLUMNS) {
      if (set(row, column)) {
        throw new UnsupportedOperationException(
            name + " sets " + column + ", which the card placement does not model");
      }
    }
    checkArgument(!row.string("SummonCharacter").isEmpty(), () -> name + " summons no character");
    String second = row.string("SummonCharacterSecond");
    return new DeployCard(
        row.name(),
        unit(row.string("SummonCharacter")),
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
        row.intValue("DeployEndY"));
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
}
