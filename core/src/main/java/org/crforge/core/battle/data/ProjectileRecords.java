/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.data;

import static org.crforge.core.util.ValidationUtils.checkArgument;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.unit.AreaEffectData;
import org.crforge.core.pathfinding.combat.ScalingMode;

/** The projectiles of the battle, built from the projectiles table. */
final class ProjectileRecords extends RowReader {

  /**
   * The columns of a projectile the impact does not model: spawned projectiles laid along the
   * width, and the push's floor and a push along the flight. A spell whose projectile, or the
   * projectile that one spawns, sets one is refused as it is cast or spawned, and a unit's shot as
   * it is fired. The area effect it spawns is modelled unless its row is refused.
   */
  private static final List<String> UNMODELLED_PROJECTILE_COLUMNS =
      List.of("SpawnAxisX", "MinPushback", "DoDirectionalPushback");

  /** The target limit the loader stores for a projectile row that leaves it empty. */
  private static final int DEFAULT_MAXIMUM_TARGETS = 1000;

  /**
   * The columns of a projectile's row that only show something: its art, effects, sounds, shadow
   * and the shakes it makes, classified by what they name, not each by a trace.
   */
  private static final Set<String> PRESENTATION_PROJECTILE_COLUMNS =
      Set.of(
          // The table of stats the card's info shows, as a buff's and an action row's are.
          "StatsTags",
          "AlwaysResetAnimation",
          "DeathEffect",
          "DragEffect",
          "ExportName",
          "FileName",
          "HitEffect",
          "HitSoundWhenParentAlive",
          "PingpongDeathEffect",
          "PrefabAsset",
          "PrestigeExportName",
          "PrestigeExportName2",
          "PrestigeExportName3",
          "PrestigeRedExportName2",
          "PrestigeRedExportName3",
          "PrestigeSWF",
          "RedExportName",
          "RedShadowExportName",
          "Scale",
          "ShadowDisableRotate",
          "ShadowExportName",
          "ShakesShooter",
          "ShakesTargets",
          "SpawnDeployBaseAnim",
          "TargettedEffect",
          "TrailEffect",
          // The filtered hit effects: the hit-effect chooser tries the filters in order for the
          // entity hit and the first that accepts names the effect shown, the shooter's own target
          // keeping HitEffect under the last; its answer only goes to the effect display.
          "FilteredHitEffects",
          "HitEffectFilters",
          "FilteredHitEffectOnlyIfNotShootersTarget",
          // Read only by the projectile view, for the angle it draws the projectile at.
          "MinimumLengthForVisualAngleCalculation",
          // Read only where the impact hands its hit effect to the projectile's presentation
          // object: whether the effect starts from the stored source point, and that point's height
          // correction. The source point itself is read nowhere else.
          "UseFixedEffectSourcePosition",
          "DoEffectSourcePositionHeightCorrection");

  /** The columns of a projectile's row the record shows no battle logic reads to any effect. */
  private static final Set<String> INERT_PROJECTILE_COLUMNS =
      Set.of(
          "Name",
          "Base",
          // Nothing in the battle logic reads it; carried as presentation.
          "PingpongMovingShooter",
          // Read only by the projectile view: its frame set and whether it shows while delayed.
          "use360Frames",
          "HideWhenDelayed");

  /** The columns of a projectile's row whose role is not yet established: none now. */
  private static final Set<String> PENDING_PROJECTILE_COLUMNS = Set.of();

  ProjectileRecords(GameTables tables, BattleRecords records) {
    super(tables, records);
  }

  /**
   * A projectile as the battle reads it, from the projectiles table.
   *
   * @param name the row's name
   */
  ProjectileData projectile(String name) {
    GameTable table = tables.table(PROJECTILES);
    checkArgument(table.has(name), () -> "the game tables have no projectile " + name);
    GameRow row = table.row(name).tracking();
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
            .considerZDistance(row.bool("ConsiderZDistance"))
            .alwaysApplyPushback(row.bool("AlwaysApplyPushback"))
            .minDistance(row.intValue("MinDistance"))
            .circleScatter("Circle".equals(row.string("Scatter")))
            .lineScatter("Line".equals(row.string("Scatter")))
            .pushback(row.intValue("Pushback"))
            .spawnCharacter(set(row, "SpawnCharacter") ? row.string("SpawnCharacter") : null)
            // The loader stores at least one child for a row that names a spawned character.
            .spawnCharacterCount(
                set(row, "SpawnCharacter") ? Math.max(row.intValue("SpawnCharacterCount"), 1) : 0)
            .spawnCharacterDeployTimeMs(row.intValue("SpawnCharacterDeployTime"))
            .deflectedCharacterSpawn(
                set(row, "DeflectedCharacterSpawn") ? row.string("DeflectedCharacterSpawn") : null)
            .spawnConstPriority(row.bool("SpawnConstPriority"))
            .radiusY(row.intValue("RadiusY"))
            .projectileRadiusY(row.intValue("ProjectileRadiusY"))
            .projectileStartExtraRadius(row.intValue("ProjectileStartExtraRadius"))
            .pushbackAll(row.bool("PushbackAll"))
            .spawnProjectile(set(row, "SpawnProjectile") ? row.string("SpawnProjectile") : null)
            .onStartingAction(actionName(row, "OnStartingAction"))
            // The loader stores at least one link for a row that names a spawned projectile.
            .spawnChain(set(row, "SpawnProjectile") ? Math.max(row.intValue("SpawnChain"), 1) : 0)
            .chainIsNewProjectile(row.bool("ChainIsNewProjectile"))
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
            .onTargetReachedAction(inlineActionName(row, "OnTargetReachedAction"))
            .spawnAreaEffectObject(
                set(row, "SpawnAreaEffectObject") ? row.string("SpawnAreaEffectObject") : null)
            .ignoreReflectedAttack(row.bool("IgnoreReflectedAttack"))
            .dragBackSpeed(row.intValue("DragBackSpeed"))
            .dragSelfSpeed(row.intValue("DragSelfSpeed"))
            .dragMargin(row.intValue("DragMargin"))
            .dragBackAsAttractor(row.bool("DragBackAsAttractor"))
            // The loader stores true for an empty column.
            .allowResetTarget(!row.has("AllowResetTarget") || row.bool("AllowResetTarget"))
            .deflectBehaviour(deflectBehaviour(row.string("DeflectBehaviour")))
            .deflectRadius(row.intValue("DeflectRadius"))
            // The loader stores true for an empty column.
            .deflectGroup(!row.has("DeflectGroup") || row.bool("DeflectGroup"))
            .actionOnDeflector(
                set(row, "ActionOnDeflector") ? row.string("ActionOnDeflector") : null)
            .customDeflectAction(actionName(row, "CustomDeflectAction"))
            .useCustomMovement(row.bool("UseCustomMovement"))
            .spawnAxisY(row.bool("SpawnAxisY"))
            .initialCollisionCheckFilter(
                set(row, "InitialCollisionCheckFilter")
                    ? records.filter(row.string("InitialCollisionCheckFilter"))
                    : null)
            .build();
    List<String> unmodelled =
        new ArrayList<>(
            UNMODELLED_PROJECTILE_COLUMNS.stream().filter(column -> set(row, column)).toList());
    // The spawned area effect is refused with its row, and so is one that follows the projectile,
    // which is made on its first flight visit, not at its impact.
    if (data.spawnAreaEffectObject() != null) {
      AreaEffectData spawned = records.areaEffect(data.spawnAreaEffectObject());
      if (!spawned.unmodelledColumns().isEmpty() || spawned.followsParent()) {
        unmodelled.add("SpawnAreaEffectObject");
      }
    }
    // A deflected spawn without a spawned character: how many of it the impact makes is not
    // established. No shipped row has one.
    if (data.deflectedCharacterSpawn() != null && data.spawnCharacter() == null) {
      unmodelled.add("DeflectedCharacterSpawn");
    }
    // A hook's impacts carry its hooked flag, whose effect on a damage is not established: no
    // hooking row deals any.
    if (data.dragBackSpeed() >= 1 && data.damage() != 0) {
      unmodelled.add("Damage");
    }
    // The target buff is modelled on the circle or the one target of the impact; a projectile that
    // flies to a point buffs through its hits on the way instead, which is not.
    if (data.targetBuff() != null && data.homingLike()) {
      unmodelled.add(0, "TargetBuff");
    }
    // The height a projectile under the z-distance column steps toward is its target's for one
    // that homes, which no shipped row does; and one that flies to a point has no single aim
    // height to fall onto.
    if (data.considerZDistance() && (data.homing() || data.homingLike())) {
      unmodelled.add("ConsiderZDistance");
    }
    return data.toBuilder()
        .unmodelledColumns(
            withUnread(
                unmodelled,
                row,
                PRESENTATION_PROJECTILE_COLUMNS,
                INERT_PROJECTILE_COLUMNS,
                PENDING_PROJECTILE_COLUMNS))
        .build();
  }

  /** The bits of game tags written as names separated by commas; none for an empty text. */
  /** The deflection flags a projectile row names, separated by commas, as their bits. */
  private static int deflectBehaviour(String names) {
    int bits = 0;
    for (String name : names.split(",")) {
      bits |=
          switch (name.trim()) {
            case "" -> 0;
            case "NoDeflect" -> ProjectileData.NO_DEFLECT;
            case "InvertDirection" -> ProjectileData.INVERT_DIRECTION;
            case "CheckOnlyTargetPosition" -> ProjectileData.CHECK_ONLY_TARGET_POSITION;
            case "UseSpellsTowerDamageMul" -> ProjectileData.USE_SPELLS_TOWER_DAMAGE_MUL;
            case "IgnoreHeight" -> ProjectileData.IGNORE_HEIGHT;
            default ->
                throw new IllegalArgumentException("an unknown deflection flag: " + name.trim());
          };
    }
    return bits;
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
