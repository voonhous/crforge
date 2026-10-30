/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.data;

import static org.crforge.core.util.ValidationUtils.checkArgument;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.crforge.core.battle.unit.AreaDamageType;
import org.crforge.core.battle.unit.AreaEffectData;

/**
 * The area effects of the battle, built from the area effect objects table and the shapes it names.
 */
final class AreaEffectRecords extends RowReader {

  private static final String AREA_EFFECT_OBJECTS = "area_effect_objects";

  private static final String SHAPES = "shapes";

  /**
   * The columns of an area effect the battle does not model: a row that sets one is refused as the
   * area effect is created. A buff that boosts one target or lasts longer by level, the life
   * condition, the tags other than the one that hides the pushback's presentation, the per-level
   * lifetime and the push's floor; its gate lift only in the filter form, whose load reads it. Its
   * projectile is modelled, but not a launch from its source or a spread one; for a row with hit
   * switches its hit action only for a Clone, as a buff spawn, a group of buff spawns and a taunt,
   * and on a shaped row as a choice by team, one hit per target only with a hit action, and neither
   * the hit action on itself nor the end on its first hit (both only in the filter form); following
   * only its parent; its spawns only in a shuffled order; and its shape only as a rectangle with a
   * filter whose hits do nothing but their hit action.
   */
  private static final List<String> UNMODELLED_AREA_EFFECT_COLUMNS =
      List.of(
          "Boost",
          "BuffTimeIncreasePerLevel",
          "BuffTimeIncreaseAfterTournamentCap",
          "AliveIfTrue",
          "Tags",
          "LifeDurationIncreasePerLevel",
          "LifeDurationIncreaseAfterTournamentCap",
          "MinPushback");

  private static final String DAMAGE_TYPES = "damage_types";

  /**
   * The fields of a damage type the filter form of an area effect deals as it is held for: its two
   * amounts and the effect the view shows.
   */
  private static final Set<String> DAMAGE_TYPE_FIELDS =
      Set.of("BaseDamage", "TowerDamage", "Effect");

  /**
   * The columns of an area effect's row that only show something: its effects and art, classified
   * by what they name.
   */
  private static final Set<String> PRESENTATION_AREA_EFFECT_COLUMNS =
      Set.of(
          // The table of stats the card's info shows, as a buff's and an action row's are.
          "StatsTags",
          "DeflectedProjectileEffect",
          "DeflectionFBEffect",
          // The art the evolved Cannon's crosshair shows.
          "ExportName",
          "FileName",
          // Handed to the view's listener at each hit an update makes, which shows it and nothing
          // more.
          "HitEffect",
          "LoopingEffect",
          // The effect a following area effect shows while it follows nothing.
          "NoFollowObjectEffect",
          // Whether the view shows the looping effect on the area effect itself or on its parent;
          // only the area effect's view reads it.
          "ParentLoopingEffectToSelf",
          "OneShotEffect",
          "ScaledEffect",
          "ScaledEffectFollowAeO",
          "SpawnDeployBaseAnim",
          "SpawnEffect");

  /**
   * The columns of an area effect's row the record shows no battle logic reads. BuffNumber and
   * SpawnMaxRadius are declared by the table and never looked up: the spawner places its characters
   * within the radius of its hits.
   */
  private static final Set<String> INERT_AREA_EFFECT_COLUMNS =
      Set.of("Name", "Base", "BuffNumber", "SpawnMaxRadius");

  /** The columns of an area effect's row whose role is not yet established: none now. */
  private static final Set<String> PENDING_AREA_EFFECT_COLUMNS = Set.of();

  AreaEffectRecords(GameTables tables, BattleRecords records) {
    super(tables, records);
  }

  /**
   * An area effect as the battle reads it, from the area effect objects table. A starting or
   * life-end action written inline, as Dark Magic's are, is the actions table's row named after the
   * area effect and the column.
   *
   * @param name the row's name
   */
  AreaEffectData areaEffect(String name) {
    GameTable table = tables.table(AREA_EFFECT_OBJECTS);
    checkArgument(table.has(name), () -> "the game tables have no area effect " + name);
    GameRow row = table.row(name).tracking();
    List<String> unmodelled = new ArrayList<>();
    for (String column : UNMODELLED_AREA_EFFECT_COLUMNS) {
      if (sets(row, column) && !(column.equals("Tags") && presentationTags(row))) {
        unmodelled.add(column);
      }
    }
    // The filter form: a row that names a filter and neither hit switch, as every shipped area
    // effect row is, its class having no hit switches. It chooses what it
    // reaches by the filter alone and deals its damage as a damage type. A shaped row is of the
    // form when it names no damage type column either, which that class does not have: its Damage
    // column is its damage type, and it lists what it reaches in its shape.
    boolean filterHits =
        (!sets(row, "Shape") || !sets(row, "DamageType"))
            && sets(row, "Filter")
            && !row.bool("HitsAir")
            && !row.bool("HitsGround");
    AreaEffectData data =
        AreaEffectData.builder()
            .name(row.name())
            .rarity(rarity(row.string("Rarity")))
            .lifeDurationMs(row.intValue("LifeDuration"))
            .radius(row.intValue("Radius"))
            .maxRadius(row.intValue("MaxRadius"))
            .hitSpeedMs(row.intValue("HitSpeed"))
            .hitSpeedOffsetMs(row.intValue("HitSpeedOffset"))
            .damage(filterHits ? 0 : row.intValue("Damage"))
            .crownTowerDamagePercent(row.intValue("CrownTowerDamagePercent"))
            .hitsAir(row.bool("HitsAir"))
            .hitsGround(row.bool("HitsGround"))
            .onlyEnemies(row.bool("OnlyEnemies"))
            .ignoreBuildings(row.bool("IgnoreBuildings"))
            .affectsHidden(row.bool("AffectsHidden"))
            .controlsBuff(row.bool("ControlsBuff"))
            .pushback(row.intValue("Pushback"))
            .pushbackAll(row.bool("PushbackAll"))
            .relativePushback(row.bool("RelativePushback"))
            .continuousPushback(row.bool("ContinuousPushback"))
            .maximumTargets(row.intValue("MaximumTargets"))
            .sharedDamage(row.bool("SharedDamage"))
            .onStartingAction(inlineActionName(row, "OnStartingAction"))
            .onLifeTimeEndAction(inlineActionName(row, "OnLifeTimeEndAction"))
            .buff(row.string("Buff").isEmpty() ? null : row.string("Buff"))
            .buffTimeMs(row.intValue("BuffTime"))
            .capBuffTimeToAreaEffectTime(row.bool("CapBuffTimeToAreaEffectTime"))
            .onlyOwnTroops(row.bool("OnlyOwnTroops"))
            .spawnAreaEffectObject(
                row.string("SpawnAreaEffectObject").isEmpty()
                    ? null
                    : row.string("SpawnAreaEffectObject"))
            .projectile(row.string("Projectile").isEmpty() ? null : row.string("Projectile"))
            .hitBiggestTargets(row.bool("HitBiggestTargets"))
            .projectileStartHeight(row.intValue("ProjectileStartHeight"))
            // On unless the row turns it off.
            .targetProjectiles(!row.has("TargetProjectiles") || row.bool("TargetProjectiles"))
            .cloning(row.bool("Clone"))
            .onHitAction(actionName(row, "OnHitAction"))
            .oneHitPerTarget(row.bool("OneHitPerTarget"))
            .onHitSelfAction(actionName(row, "OnHitSelfAction"))
            .expireOnTrigger(row.bool("ExpireOnTrigger"))
            .followsParent(row.string("FollowBehaviour").equals("FollowParent"))
            .followsTarget(row.string("FollowBehaviour").equals("FollowTarget"))
            .deflectsProjectiles(row.bool("DeflectProjectilesEnabled"))
            .spawnCharacter(set(row, "SpawnCharacter") ? row.string("SpawnCharacter") : null)
            .spawnIntervalMs(row.intValue("SpawnInterval"))
            .spawnInitialDelayMs(row.intValue("SpawnInitialDelay"))
            .spawnTimeMs(row.intValue("SpawnTime"))
            .spawnMaxCount(row.intValue("SpawnMaxCount"))
            .spawnMinRadius(row.intValue("SpawnMinRadius"))
            .spawnRandomizeSequence(row.bool("SpawnRandomizeSequence"))
            .spawnClones(row.bool("SpawnClones"))
            .stayAfterParentDies(row.bool("StayAfterParentDies"))
            .linkToInstigatorLife(row.bool("LinkToInstigatorLife"))
            .shaped(sets(row, "Shape"))
            .filter(
                (sets(row, "Shape") || filterHits) && sets(row, "Filter")
                    ? row.string("Filter")
                    : null)
            .filterHits(filterHits)
            .typedDamage(filterHits ? areaDamageType(row, unmodelled) : null)
            .unmodelledColumns(unmodelled)
            .build();
    if (data.shaped() && data.filterHits()) {
      data = filterShape(data, row.string("Shape"), unmodelled);
    } else if (data.shaped()) {
      data = shaped(data, row.string("Shape"), unmodelled);
      // A shaped row's damage type is read only by its hits' damage, which a row without damage
      // never deals; a circle's damage is queued with it as a typed hit.
      if (data.damage() == 0) {
        row.has("DamageType");
      } else if (data.shapeRadius() >= 1 && sets(row, "DamageType")) {
        data = data.toBuilder().damageType(row.string("DamageType")).build();
      } else if (data.shapeRadius() >= 1) {
        // A circle's damage without a damage type is not held.
        unmodelled.add("Shape");
      }
    }
    // For a row with hit switches, the hit action is modelled for a Clone, a Clone row whose hit
    // action clones, and which neither deals damage nor applies a buff, as the shipped Clone does;
    // and for a row that is not a Clone's whose hit action is a buff spawn, as the evolved Tesla's
    // ring's is, a group of buff spawns, as the Goblin Curse's base is, a taunt, as the Goblin
    // Demolisher's is, a group of taunts, as the hero Knight's is, or the evolved Dart Goblin's
    // poison damage. The filter form's hit pass schedules any hit action on each object it lists,
    // as it is built for that object, so it takes every one.
    boolean cloning =
        data.onHitAction() != null
            && tables.action(data.onHitAction()).classType().equals("ActionClone");
    boolean buffSpawns = data.onHitAction() != null && buffSpawnGroup(data.onHitAction());
    boolean taunt = data.onHitAction() != null && tauntGroup(data.onHitAction());
    // The evolved Dart Goblin's poison areas start its poison damage on what they reach.
    boolean poison =
        data.onHitAction() != null
            && tables
                .action(data.onHitAction())
                .classType()
                .equals("ActionBlowdartGoblinEvoDamage");
    // A shaped row's hit pass schedules a choice by team, as the evolved Baby Dragon's wind does.
    boolean byTeam =
        data.onHitAction() != null
            && data.shaped()
            && tables.action(data.onHitAction()).classType().equals("ActionFilterByEnemy");
    // A circle's hit pass schedules a choice between buff spawns, as the Ice Golemite hero form's
    // slow circle does; the circle's load refuses anything else it would schedule.
    boolean circleBuffs =
        data.onHitAction() != null
            && data.shaped()
            && data.shapeRadius() >= 1
            && buffSelect(data.onHitAction());
    if (data.onHitAction() != null
        && !data.filterHits()
        && !(data.cloning() && cloning)
        && !(!data.cloning() && !data.shaped() && (buffSpawns || taunt || poison))
        && !byTeam
        && !circleBuffs) {
      unmodelled.add("OnHitAction");
    }
    // For a row with hit switches one hit per target is read by the hit action's loop; whether
    // anything else reads it is not established. The filter form's hit pass reads it for every
    // object it lists.
    if (data.oneHitPerTarget() && data.onHitAction() == null && !data.filterHits()) {
      unmodelled.add("OneHitPerTarget");
    }
    // The hit action on itself and the end on its first hit are read only by the filter form's
    // hit pass.
    if (data.onHitSelfAction() != null && !data.filterHits()) {
      unmodelled.add("OnHitSelfAction");
    }
    if (data.expireOnTrigger() && !data.filterHits()) {
      unmodelled.add("ExpireOnTrigger");
    }
    // FollowParent follows its parent; FollowTarget the target of the projectile whose impact made
    // it, the only maker that hands it one. Any other behaviour is not modelled.
    if (sets(row, "FollowBehaviour") && !data.followsParent() && !data.followsTarget()) {
      unmodelled.add("FollowBehaviour");
    }
    // The Filter is read only by the Shape path, which a row without a Shape never enters: a row
    // without one hits by its air and ground switches, whatever filter it names, as the taunt
    // cancelling rows do. A row that sets neither switch and names a filter would choose what it
    // reaches by the filter alone, which is not modelled; read as its switches, it would reach
    // nothing.
    if (!data.shaped() && !data.filterHits()) {
      if (sets(row, "Filter") && !data.hitsAir() && !data.hitsGround()) {
        unmodelled.add("Filter");
      }
    }
    if (data.filterHits()) {
      filterForm(data, unmodelled);
    } else {
      // The push columns beyond its distance are read only by the filter form's hit pass: a row
      // with hit switches pushes by its own path, which lifting the gates, the separation and
      // keeping the longer push are not held for.
      if (data.pushbackAll()) {
        unmodelled.add("PushbackAll");
      }
      if (data.relativePushback()) {
        unmodelled.add("RelativePushback");
      }
      if (data.continuousPushback()) {
        unmodelled.add("ContinuousPushback");
      }
    }
    // The filter form's hit pass does not read the Clone switch: what a Clone reaches is its
    // filter's choice, and what it does its hit action's.
    if (data.cloning()
        && !data.filterHits()
        && (!cloning || data.damage() != 0 || data.buff() != null)) {
      unmodelled.add("Clone");
    }
    // Whether a projectile goes onto each object hit is read only by the filter form's hit pass.
    if (!data.targetProjectiles() && !data.filterHits()) {
      unmodelled.add("TargetProjectiles");
    }
    if (data.projectile() != null && !data.filterHits()) {
      // A start height of -1 launches from the area effect's source at height 1000, which no row
      // carried here does.
      if (data.projectileStartHeight() == -1) {
        unmodelled.add("ProjectileStartHeight");
      }
      // Without HitBiggestTargets every projectile from the second hit on is spread about the
      // point by two battle draws; no shipped row has two hits without it.
      int lifeHits = data.hitSpeedMs() == 0 ? 1 : data.lifeDurationMs() / data.hitSpeedMs();
      if (!data.hitBiggestTargets() && lifeHits >= 2) {
        unmodelled.add("Projectile");
      }
    }
    // Without SpawnRandomizeSequence the spawner turns each direction by a fixed step from the side
    // the area effect is on, which no reference holds.
    if (data.spawnCharacter() != null && !data.spawnRandomizeSequence()) {
      unmodelled.add("SpawnCharacter");
    }
    return data.toBuilder()
        .unmodelledColumns(
            withUnread(
                unmodelled,
                row,
                PRESENTATION_AREA_EFFECT_COLUMNS,
                INERT_AREA_EFFECT_COLUMNS,
                PENDING_AREA_EFFECT_COLUMNS))
        .build();
  }

  /**
   * Refuses, for the filter form, what each object it lists would get beyond its push, its pull (a
   * buff that attracts), its damage, its hit action, its buff and its projectile, and what the pass
   * does beyond passing by an object it has reached for a row that hits each once, its target
   * limit, its biggest targets first, its hit action on itself and its end on its first hit: a
   * launch from the area effect's source (a start height below 0), which the filter form's hit pass
   * is not held for. Its push is held for with its gates in place or lifted (PushbackAll), the
   * separation taken off (RelativePushback) and the longer push kept (ContinuousPushback). A damage
   * type that names a column the pass is not held for is refused by its Damage column.
   *
   * <p>A deflection (DeflectProjectilesEnabled, the Monk's Deflect) is not the hit pass's: the
   * deflection pass of a flying projectile reads only that switch, the area effect's radius and its
   * team, never its filter or hit switches, so a filter form row deflects as a row with switches.
   *
   * <p>Nor is the spawner (SpawnCharacter, the Skeleton King's graveyard): the update runs it after
   * its counters and before its hit pass, for a row of either form, reading only the spawn columns,
   * its lifetime and its radius, so a filter form row makes its characters as a row with switches.
   */
  private void filterForm(AreaEffectData data, List<String> unmodelled) {
    if (data.projectile() != null && data.projectileStartHeight() < 0) {
      unmodelled.add("ProjectileStartHeight");
    }
  }

  /**
   * A shaped filter form row with its shape read: a circle's radius, which its list is taken in
   * instead of its own radius's circle, or a rectangle's width and height, about its point either
   * way. The shape is only where the filter form's hit pass lists its objects; the pass is the
   * same. Refused, by its Shape column: a shape of another class or none, a circle without a radius
   * and a shape that checks its origin, which drops what stands beyond its circle by its centre.
   */
  private AreaEffectData filterShape(AreaEffectData data, String shape, List<String> unmodelled) {
    GameTable table = tables.table(SHAPES);
    if (!table.has(shape)) {
      unmodelled.add("Shape");
      return data;
    }
    GameRow row = table.row(shape);
    if (row.bool("CheckOrigin")) {
      unmodelled.add("Shape");
    }
    return switch (row.string("ClassType")) {
      case "Circle" -> {
        if (row.intValue("Radius") < 1) {
          unmodelled.add("Shape");
        }
        yield data.toBuilder().shapeRadius(row.intValue("Radius")).build();
      }
      case "Rectangle" ->
          data.toBuilder()
              .shapeWidth(row.intValue("Width"))
              .shapeHeight(row.intValue("Height"))
              .build();
      default -> {
        unmodelled.add("Shape");
        yield data;
      }
    };
  }

  /**
   * The damage type a filter form row deals, from its Damage column: a table written inline or the
   * name of a damage types row, each giving BaseDamage (0 when left out) and TowerDamage (none when
   * left out), and an Effect, which only the view shows. A row without Damage deals none. A number,
   * or a type that sets anything else (its Flags, an action on its source or its target, or a field
   * the type has no column for), is refused by the Damage column.
   */
  private AreaDamageType areaDamageType(GameRow row, List<String> unmodelled) {
    JsonNode value = row.value("Damage");
    if (value == null || value.isTextual() && value.asText().isEmpty()) {
      return null;
    }
    if (value.isObject()) {
      for (Iterator<String> it = value.fieldNames(); it.hasNext(); ) {
        if (!DAMAGE_TYPE_FIELDS.contains(it.next())) {
          unmodelled.add("Damage");
          return null;
        }
      }
      return new AreaDamageType(
          null,
          row.intField("Damage", value, "BaseDamage", 0),
          row.intField("Damage", value, "TowerDamage", AreaDamageType.NO_TOWER_DAMAGE));
    }
    if (value.isTextual()) {
      GameTable types = tables.table(DAMAGE_TYPES);
      if (!types.has(value.asText())) {
        unmodelled.add("Damage");
        return null;
      }
      GameRow type = types.row(value.asText());
      for (String column : type.setColumns()) {
        if (!DAMAGE_TYPE_FIELDS.contains(column)) {
          unmodelled.add("Damage");
          return null;
        }
      }
      return new AreaDamageType(
          type.name(),
          type.intValue("BaseDamage"),
          type.has("TowerDamage") ? type.intValue("TowerDamage") : AreaDamageType.NO_TOWER_DAMAGE);
    }
    unmodelled.add("Damage");
    return null;
  }

  /**
   * A shaped row with its rectangle read. Refused, by its Shape column: a shape of another class or
   * none, a rectangle without a filter, and one whose hits would do more than schedule their hit
   * action - a damage, a buff, a push, a launch, a spawner, a growth or one hit per target - none
   * of which the shape's hit pass is held for.
   */
  private AreaEffectData shaped(AreaEffectData data, String shape, List<String> unmodelled) {
    GameTable table = tables.table(SHAPES);
    if (table.has(shape) && table.row(shape).string("ClassType").equals("Circle")) {
      return circle(data, table.row(shape), unmodelled);
    }
    if (!table.has(shape) || !table.row(shape).string("ClassType").equals("Rectangle")) {
      unmodelled.add("Shape");
      return data;
    }
    GameRow row = table.row(shape);
    if (data.filter() == null
        || data.damage() != 0
        || data.buff() != null
        || data.pushback() != 0
        || data.projectile() != null
        || data.spawnCharacter() != null
        || data.maxRadius() != 0
        || data.oneHitPerTarget()) {
      unmodelled.add("Shape");
    }
    return data.toBuilder()
        .shapeWidth(row.intValue("Width"))
        .shapeHeight(row.intValue("Height"))
        .build();
  }

  /**
   * A shaped row with its circle read, as the Giant hero form's landing and the Ice Golemite hero
   * form's damage, knockback and slow circles have: a filter, a hit action that chooses a buff to
   * spawn, a damage queued through a damage type as a typed hit, a crown tower taking its share of
   * it, a push away from its point, and nothing else a hit would do. Refused, by its Shape column:
   * a circle without a filter, with neither a hit action, damage nor a push, with damage but no
   * damage type, with any other hit action, a buff, a launch, a spawner, a growth, one hit per
   * target or shared damage, none of which the circle's hit pass is held for. The push's floor and
   * gate lift are refused for every area effect.
   */
  private AreaEffectData circle(AreaEffectData data, GameRow row, List<String> unmodelled) {
    boolean buffSelect = data.onHitAction() != null && buffSelect(data.onHitAction());
    if (data.filter() == null
        || (data.damage() == 0 && data.pushback() < 1 && !buffSelect)
        || (data.onHitAction() != null && !buffSelect)
        || data.buff() != null
        || data.projectile() != null
        || data.spawnCharacter() != null
        || data.maxRadius() != 0
        || data.oneHitPerTarget()
        || data.sharedDamage()) {
      unmodelled.add("Shape");
    }
    return data.toBuilder().shapeRadius(row.intValue("Radius")).build();
  }

  /**
   * Whether a hit action is a taunt, or a group whose every part is a taunt: the hero Knight's
   * checks its start gate on the unit it reaches, then schedules its taunt there.
   */
  private boolean tauntGroup(String action) {
    GameAction row = tables.action(action);
    if (row.classType().equals("ActionTaunt")) {
      return true;
    }
    if (!row.classType().equals("ActionGroup")) {
      return false;
    }
    for (JsonNode part : row.fields().path("SubActions")) {
      if (!tables.action(part.path("action").asText()).classType().equals("ActionTaunt")) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether an action row spawns buffs only: a buff spawn, as the evolved Tesla's ring runs, or a
   * group whose every part spawns a buff.
   */
  private boolean buffSpawnGroup(String action) {
    GameAction row = tables.action(action);
    if (row.classType().equals("ActionSpawn")) {
      return row.fields().path("SpawnType").asText("").equals("BuffType");
    }
    if (!row.classType().equals("ActionGroup")) {
      return false;
    }
    for (JsonNode part : row.fields().path("SubActions")) {
      GameAction sub = tables.action(part.path("action").asText());
      if (!sub.classType().equals("ActionSpawn")
          || !sub.fields().path("SpawnType").asText("").equals("BuffType")) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether a hit action is a choice between buff spawns, as the Ice Golemite hero form's slow
   * circle has: a select whose every part spawns a buff. Scheduled on the object a hit reaches, its
   * conditions are read on that object, and the part it chooses is scheduled there.
   */
  private boolean buffSelect(String action) {
    GameAction row = tables.action(action);
    if (!row.classType().equals("ActionSelect") || row.fields().path("SubActions").isEmpty()) {
      return false;
    }
    for (JsonNode part : row.fields().path("SubActions")) {
      GameAction sub = tables.action(part.path("action").asText());
      if (!sub.classType().equals("ActionSpawn")
          || !sub.fields().path("SpawnType").asText("").equals("BuffType")) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether every tag an area effect's row lists only keeps its pushback from being shown: the
   * update hands that tag to the area damage, which reads it only to skip the pushback's
   * presentation.
   */
  private static boolean presentationTags(GameRow row) {
    for (String tag : row.string("Tags").split(",")) {
      if (!tag.isBlank() && !tag.trim().equals("NO_AOE_PUSHBACK_VFX")) {
        return false;
      }
    }
    return true;
  }

  /**
   * The radius of a shape row that is a circle. A shape of any other class, or none, is refused.
   *
   * @param name the shape row's name
   */
  int circleRadius(String name) {
    GameTable table = tables.table(SHAPES);
    if (!table.has(name)) {
      throw new UnsupportedOperationException("the shape " + name + " is not modelled");
    }
    GameRow row = table.row(name);
    if (!row.string("ClassType").equals("Circle")) {
      throw new UnsupportedOperationException(
          "the shape " + name + " is a " + row.string("ClassType") + ", which is not modelled");
    }
    return row.intValue("Radius");
  }

  /**
   * Whether a shape row that is a circle checks its origin (CheckOrigin): the shape's narrowing
   * then keeps only what its query found whose centre lies within its radius. A shape of any other
   * class, or none, is refused.
   *
   * @param name the shape row's name
   */
  boolean circleChecksOrigin(String name) {
    circleRadius(name);
    return tables.table(SHAPES).row(name).bool("CheckOrigin");
  }
}
