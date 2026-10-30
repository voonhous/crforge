/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.data;

import static org.crforge.core.util.ValidationUtils.checkArgument;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.crforge.core.battle.unit.BuffData;

/**
 * The character buffs of the battle, built from the character buffs table or an inline buff row.
 */
final class BuffRecords extends RowReader {

  private static final String CHARACTER_BUFFS = "character_buffs";

  /** The columns of a buff the battle reads. */
  private static final Set<String> MODELLED_BUFF_COLUMNS =
      Set.of(
          "Name",
          "Rarity",
          // Folded into the two percents the damage entry scales a hit the carrier deals by, the
          // second only on a crown tower.
          "DamageMultiplier",
          "CharacterCrownTowerDamagePercent",
          "Invisible",
          // Read only by the clone creator, which leaves such a buff off a clone; a clone of a
          // carrier is refused.
          "NotCloned",
          "HealPerSecond",
          "AllowedOverHealPerc",
          "SpeedMultiplier",
          "HitSpeedMultiplier",
          "SpawnSpeedMultiplier",
          "HitFrequency",
          "DamagePerSecond",
          "CrownTowerDamagePerHit",
          "CrownTowerDamagePercent",
          "BuildingDamagePercent",
          "HitTickFromSource",
          "AttractPercentage",
          "LateralPushPercentage",
          "PushMassFactor",
          "PushSpeedFactor",
          "AttractMinAngle",
          "AttractMaxAngle",
          "ControlledByParent",
          "EnableStacking",
          "PlayerSpecificBuff",
          "CastIdSpecificBuff",
          "NoEffectToCrownTowers",
          "IgnoreBuildings",
          "DeathSpawn",
          "DeathSpawnCount",
          "DeathSpawnRadius",
          "DeathSpawnSameLocation",
          "DeathSpawnIsEnemy",
          "DeathSpawnDeployDelay",
          "OtherBuffDeathSpawnAllowed",
          "LockTarget",
          "AddAsIndividualBuff",
          "AliveIfTrue",
          // Read by the damage reduction, at the hit-points entry, the pending lethal test, a typed
          // hit and the damage over time; and by the pushback request.
          "DamageReduction",
          "IgnorePushBack",
          // Read only by the apply's hand-over to a parent's riders: a Clone buff is kept from
          // them,
          // and a buff naming another hands them that one.
          "Clone",
          "AttachedInheritAs",
          // Read by the charge reset as an instance is listed, and by the post-move charge for a
          // unit whose row has no charge range.
          "OverrideChargeRange",
          // Read by the targeting visit's hit, whose projectiles it replaces, and by the attacker's
          // hit counter, which removes the instances of a row that sets RemoveOnAttack.
          "OverrideProjectile",
          "RemoveOnAttack",
          // Read by an instance's spawner, in the buff visit, and by the cleanup's fold, which
          // admits a child of a spawner that must be alive only while it is.
          "SpawnObject",
          "SpawnStartTime",
          "SpawnInterval",
          "SpawnLimit",
          "SpawnNumber",
          "SpawnPauseTime",
          "SpawnerAliveRequired",
          // Resolved when the tables are derived: a row that names a base already carries every
          // column it inherits.
          "Base");

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
   * The tags a buff may set: the two the push pass reads, which keep the carrier's enemies, or its
   * own side, from pushing it, and are the only code that tests either; UNIT_CUSTOM_TAG_1, which no
   * battle code and no filter tests, read only by the expressions of the carrier's own action rows,
   * which read the tag word the buff is folded into; and IGNORE_RANGE_EXTENSION_TO_KEEP_TARGET,
   * which only the targeting visit's keep test reads, from the same tag word; and NO_DAMAGE, as the
   * evolved Minion Horde's ghost buff sets it: the tag word's recompute folds every listed
   * instance's tags in beside the row's and the runs', and the damage entry and the typed hit read
   * NO_DAMAGE from that word, as for a row that sets it; and ABILITY_PENDING, as the Valkyrie hero
   * form's charge buff sets it, which only the health bar's view reads from the tag word: no battle
   * code, filter or row tests it; and UNKILLABLE, as the Berserker hero form's buff sets it, which
   * only the subtraction, holding the carrier's hit points at 1 against a hit that does not pierce
   * immunity, and the target validator, sparing the carrier the pending damage rule, read from the
   * tag word; and NO_ATTACK and NO_MOVE_ALLOW_ATTRACT, as the Electro Wizard hero form's recovery
   * buffs set them, which every reader tests in the same tag word as a row's. A buff that sets any
   * other is refused.
   */
  private static final Set<String> MODELLED_BUFF_TAGS =
      Set.of(
          "NO_PUSHED_BY_ENEMY",
          "NO_PUSHED_BY_ALLY",
          "UNIT_CUSTOM_TAG_1",
          "IGNORE_RANGE_EXTENSION_TO_KEEP_TARGET",
          "NO_DAMAGE",
          "ABILITY_PENDING",
          "UNKILLABLE",
          "NO_ATTACK",
          "NO_MOVE_ALLOW_ATTRACT");

  /**
   * The actions a buff schedules on its carrier as an instance is listed, removed and refreshed:
   * read when they name an action row, listed as not modelled when written inline.
   */
  private static final Set<String> BUFF_HOOK_COLUMNS =
      Set.of("OnStartAction", "OnRemoveAction", "OnStackedAction");

  BuffRecords(GameTables tables, BattleRecords records) {
    super(tables, records);
  }

  /**
   * A character buff as the battle reads it, from the character buffs table, or the buff row a buff
   * spawn row writes inline under that name. Every column it sets that is neither read nor only
   * shows something is listed as not modelled, and so is a start or remove action written inline
   * rather than naming a row.
   *
   * @param name the row's name
   */
  BuffData buff(String name) {
    GameTable table = tables.table(CHARACTER_BUFFS);
    GameRow row = table.has(name) ? table.row(name) : tables.inlineBuff(name);
    checkArgument(row != null, () -> "the game tables have no buff " + name);
    List<String> unmodelled = new ArrayList<>();
    for (String column : row.columns().keySet()) {
      if (!MODELLED_BUFF_COLUMNS.contains(column)
          && !PRESENTATION_BUFF_COLUMNS.contains(column)
          && sets(row, column)
          && !inertDamageReductionAction(row, column)
          && !modelledBuffTags(row, column)
          && !(BUFF_HOOK_COLUMNS.contains(column) && readHook(row, column))) {
        unmodelled.add(column);
      }
    }
    // A start or remove action written inline is a row of its own, which the battle does not read
    // unless it is a group of named rows the actions table holds under the buff's and column's
    // name.
    for (String column : BUFF_HOOK_COLUMNS) {
      JsonNode value = row.value(column);
      if (value != null && value.isObject() && !readHook(row, column)) {
        unmodelled.add(column);
      }
    }
    Collections.sort(unmodelled);
    return BuffData.builder()
        .name(row.name())
        .rarity(rarity(row.string("Rarity")))
        .speedMultiplier(row.intValue("SpeedMultiplier"))
        .hitSpeedMultiplier(row.intValue("HitSpeedMultiplier"))
        .damageMultiplier(row.intValue("DamageMultiplier"))
        .spawnSpeedMultiplier(row.intValue("SpawnSpeedMultiplier"))
        .hitFrequency(row.intValue("HitFrequency"))
        .damagePerSecond(row.intValue("DamagePerSecond"))
        .crownTowerDamagePerHit(row.intValue("CrownTowerDamagePerHit"))
        .crownTowerDamagePercent(row.intValue("CrownTowerDamagePercent"))
        .characterCrownTowerDamagePercent(row.intValue("CharacterCrownTowerDamagePercent"))
        .buildingDamagePercent(row.intValue("BuildingDamagePercent"))
        .hitTickFromSource(row.bool("HitTickFromSource"))
        .attractPercentage(row.intValue("AttractPercentage"))
        .lateralPushPercentage(row.intValue("LateralPushPercentage"))
        .pushMassFactor(row.intValue("PushMassFactor"))
        .pushSpeedFactor(row.intValue("PushSpeedFactor"))
        .attractMinAngle(row.intValue("AttractMinAngle"))
        .attractMaxAngle(row.intValue("AttractMaxAngle"))
        .controlledByParent(row.bool("ControlledByParent"))
        .enableStacking(row.bool("EnableStacking"))
        .playerSpecificBuff(row.bool("PlayerSpecificBuff"))
        // A row that leaves the column out reads false, the client's default for it.
        .castIdSpecificBuff(row.bool("CastIdSpecificBuff"))
        .noEffectToCrownTowers(row.bool("NoEffectToCrownTowers"))
        .ignoreBuildings(row.bool("IgnoreBuildings"))
        .deathSpawn(sets(row, "DeathSpawn") ? row.string("DeathSpawn") : null)
        .deathSpawnCount(row.intValue("DeathSpawnCount"))
        .deathSpawnRadius(row.intValue("DeathSpawnRadius"))
        .deathSpawnSameLocation(row.bool("DeathSpawnSameLocation"))
        .deathSpawnIsEnemy(row.bool("DeathSpawnIsEnemy"))
        .deathSpawnDeployDelay(row.bool("DeathSpawnDeployDelay"))
        .otherBuffDeathSpawnAllowed(row.bool("OtherBuffDeathSpawnAllowed"))
        .invisible(row.bool("Invisible"))
        .notCloned(row.bool("NotCloned"))
        .healPerSecond(row.intValue("HealPerSecond"))
        .allowedOverHealPercent(row.intValue("AllowedOverHealPerc"))
        .lockTarget(row.bool("LockTarget"))
        .addAsIndividualBuff(row.bool("AddAsIndividualBuff"))
        .aliveIfTrue(sets(row, "AliveIfTrue") ? row.string("AliveIfTrue") : null)
        .damageReduction(row.intValue("DamageReduction"))
        .ignorePushBack(row.bool("IgnorePushBack"))
        .cloneBuff(row.bool("Clone"))
        .attachedInheritAs(sets(row, "AttachedInheritAs") ? row.string("AttachedInheritAs") : null)
        .gameTagsToSet(tagBits(row.string("GameTagsToSet")))
        .overrideChargeRange(row.intValue("OverrideChargeRange"))
        .overrideProjectile(
            sets(row, "OverrideProjectile") ? row.string("OverrideProjectile") : null)
        .removeOnAttack(row.bool("RemoveOnAttack"))
        .onStartAction(hookAction(row, "OnStartAction"))
        .onRemoveAction(hookAction(row, "OnRemoveAction"))
        .onStackedAction(hookAction(row, "OnStackedAction"))
        .spawnObject(sets(row, "SpawnObject") ? row.string("SpawnObject") : null)
        .spawnStartTimeMs(row.intValue("SpawnStartTime"))
        .spawnIntervalMs(row.intValue("SpawnInterval"))
        .spawnLimit(row.intValue("SpawnLimit"))
        .spawnNumber(row.intValue("SpawnNumber"))
        .spawnPauseTimeMs(row.intValue("SpawnPauseTime"))
        .spawnerAliveRequired(row.bool("SpawnerAliveRequired"))
        .unmodelledColumns(unmodelled)
        .build();
  }

  /**
   * Whether a buff column is the action a reduced hit schedules on the buffed unit and that action
   * only plays an effect for as long as it plays: such a row makes no run, so scheduling it changes
   * nothing in the battle, and it is not scheduled. The evolved Knight's protection effect is one.
   */
  private boolean inertDamageReductionAction(GameRow row, String column) {
    return column.equals("OnDamageReductionAction")
        && ActionRows.inertEffect(tables.action(row.string(column)));
  }

  /**
   * A buff's start or remove action: the row the column names, or the row of an inline group of
   * named rows; null for none and for any other one written inline, which is listed as not modelled
   * instead.
   */
  private String hookAction(GameRow row, String column) {
    if (inlineNamedGroup(row, column)) {
      return inlineActionName(row, column);
    }
    return namedAction(row.value(column)) ? actionName(row, column) : null;
  }

  /**
   * Whether the battle reads a buff's start or remove action: one that names a row, or a group of
   * named rows written inline, as the Royal Chef's level-up buff's is, which is the actions table's
   * row named after the buff and the column.
   */
  private boolean readHook(GameRow row, String column) {
    return namedAction(row.value(column)) || inlineNamedGroup(row, column);
  }

  /**
   * Whether a buff column is its tags and every tag it sets is one the battle reads where it reads
   * the tag word of the buff's carrier: the one that keeps enemies from pushing it, and the custom
   * tag only expressions read.
   */
  private static boolean modelledBuffTags(GameRow row, String column) {
    if (!column.equals("GameTagsToSet")) {
      return false;
    }
    for (String tag : row.string(column).split(",")) {
      if (!tag.isBlank() && !MODELLED_BUFF_TAGS.contains(tag.trim())) {
        return false;
      }
    }
    return true;
  }
}
