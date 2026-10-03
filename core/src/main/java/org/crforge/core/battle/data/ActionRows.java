package org.crforge.core.battle.data;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;
import org.crforge.core.battle.action.ActionRow;
import org.crforge.core.battle.action.AirToGround;
import org.crforge.core.battle.action.AliveTimer;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.Berserk;
import org.crforge.core.battle.action.BossBanditAbility;
import org.crforge.core.battle.action.CannonBarrage;
import org.crforge.core.battle.action.CannonProjectileSpawn;
import org.crforge.core.battle.action.CaptureCharacter;
import org.crforge.core.battle.action.CardDeployListener;
import org.crforge.core.battle.action.ChampionAbility;
import org.crforge.core.battle.action.ChangeGameObjectData;
import org.crforge.core.battle.action.ChefCooking;
import org.crforge.core.battle.action.Clone;
import org.crforge.core.battle.action.CollectFriends;
import org.crforge.core.battle.action.DamageType;
import org.crforge.core.battle.action.DealDamage;
import org.crforge.core.battle.action.ExecutionerEvoProjectile;
import org.crforge.core.battle.action.Filter;
import org.crforge.core.battle.action.FilterByEnemy;
import org.crforge.core.battle.action.FlipFlop;
import org.crforge.core.battle.action.GhostEvo;
import org.crforge.core.battle.action.GiantBufferBuff;
import org.crforge.core.battle.action.GoblinHutLifeState;
import org.crforge.core.battle.action.GoblinsteinAbility;
import org.crforge.core.battle.action.Group;
import org.crforge.core.battle.action.Heal;
import org.crforge.core.battle.action.Hide;
import org.crforge.core.battle.action.InertAction;
import org.crforge.core.battle.action.Interval;
import org.crforge.core.battle.action.Kill;
import org.crforge.core.battle.action.Knockback;
import org.crforge.core.battle.action.LaserBall;
import org.crforge.core.battle.action.MegaKnightUppercut;
import org.crforge.core.battle.action.PlayAnimationIfHasTarget;
import org.crforge.core.battle.action.PopBalloons;
import org.crforge.core.battle.action.RollingProjectile;
import org.crforge.core.battle.action.RunActionAtHealth;
import org.crforge.core.battle.action.RunActionOnInstigatorDeath;
import org.crforge.core.battle.action.RunIfGameObjectExists;
import org.crforge.core.battle.action.RunIfInstigatorMatches;
import org.crforge.core.battle.action.RunOnInstigator;
import org.crforge.core.battle.action.Select;
import org.crforge.core.battle.action.SetAttackSequenceIndex;
import org.crforge.core.battle.action.SetCharacterLevel;
import org.crforge.core.battle.action.SetShield;
import org.crforge.core.battle.action.SetVariable;
import org.crforge.core.battle.action.ShapeSelector;
import org.crforge.core.battle.action.SpawnBuff;
import org.crforge.core.battle.action.SpawnGuard;
import org.crforge.core.battle.action.SpawnResetableAreaEffect;
import org.crforge.core.battle.action.TargetIndicatorAttack;
import org.crforge.core.battle.action.Taunt;
import org.crforge.core.battle.action.WaitToActivate;
import org.crforge.core.battle.action.WarpCharacter;
import org.crforge.core.battle.action.WithDuration;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.spawn.SpawnAreaEffect;
import org.crforge.core.battle.spawn.SpawnCharacters;
import org.crforge.core.battle.spawn.SpawnProjectile;
import org.crforge.core.battle.spawn.SpawnRow;
import org.crforge.core.battle.unit.AreaEffectData;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The battle's actions built from the game's action rows, for one entity.
 *
 * <p>A row is built with the class its class type names, from its columns, in the columns' own
 * units; the actions it names are built the same way, and a row named twice in one tree is built
 * once. Expressions are compiled for the entity the tree is built for, so a tree belongs to one
 * entity. The columns every row shares - its delay, its phase, whether it is a singleton, the
 * action chained to it and when, the tags its run sets and its three gates - go into its {@link
 * ActionRow}.
 *
 * <p>The builder is strict. Every column of a row is either read, listed as one that only shows
 * something or one the derived tables have already resolved and ignored, or refused; a column it
 * does not know is refused too, so nothing a row sets is dropped unnoticed. A row of a class the
 * battle does not have is refused, naming the class; so is a character spawn row of any other spawn
 * type.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the classes built and the columns each reads, the shared columns, a game tag"
            + " as its bit in the tag word, a damage type's switches defaulting on. Supplied: an"
            + " interval's rate, which the spawn speed would set, is the usual 100 with no buffs;"
            + " a variable's key is its row's index, which only has to agree between the actions"
            + " that write a variable and the expressions that read it. Refused: every other"
            + " class, and every column not modelled.")
public final class ActionRows {

  /** The columns every row may set, read into its shared columns. */
  private static final Set<String> SHARED =
      Set.of(
          "ClassType",
          "ActionDelay",
          "UpdatePhase",
          "Singleton",
          "NextAction",
          "NextActionWait",
          "GameTagsToSet",
          "ExecuteIfTrue",
          "ForceStopIfTrue",
          "ActionPausedIfTrue",
          "AbortIfInstigatorDies");

  /** Columns that only show something, which the simulation never reads. */
  private static final Set<String> PRESENTATION = Set.of("StatsTags");

  /**
   * Columns resolved when the tables are derived: a row that names a base already carries every
   * column it inherits, as the Graveyard's skeleton spawns carry their base's.
   */
  private static final Set<String> RESOLVED = Set.of("Base");

  /** Milliseconds one step of a taunt's run takes off its duration. */
  private static final int TAUNT_STEP_MS = 50;

  /** The four classes that override none of the runtime's three places. */
  private static final Set<String> INERT =
      Set.of(
          "ActionAnimatorLayer",
          "ActionAddHealthBarPart",
          "ActionVisualActionGroup",
          "ChampionLogicAbilityButtonAnimatorData");

  /** The columns each class reads besides the shared ones. */
  private static final Map<String, Set<String>> READS =
      Map.ofEntries(
          Map.entry("ActionGroup", Set.of("SubActions", "SubActionsDelay")),
          // The Royal Chef's cooking. Its animation share, view indicator, AI state name, full-bar
          // hold and throw duration only show something; any other column of the class (an
          // overflow, the king's own shot, a cooking-done action, single buffs, deploying troops
          // or shields left out) is set by no shipped row and refused as one nothing reads.
          Map.entry(
              "ActionChefTower",
              Set.of(
                  "StartCookingDelay",
                  "ContributionNeeded",
                  "ContributionBaseline",
                  "ContributionIdle",
                  "ContributionAttacking",
                  "ContributionDestroyed",
                  "TargetFilter",
                  "MinCurrentHpThreshold",
                  "MinCurrentHpPercentageThreshold",
                  "MinMaxHpThreshold",
                  "DeprioritizeBuffed",
                  "BuffProjectile",
                  "PancakeThrowDelay",
                  "PancakeStartOffset",
                  "PancakeThrowDelayTreshold",
                  "WaitPancakeThrowAfterAttackTime",
                  "FinishWhenBothTowersLost",
                  "ContributionPercentForAltAnimation",
                  "IndicatorFileName",
                  "IndicatorExportName",
                  "IndicatorOffsetYBlue",
                  "IndicatorOffsetYRed",
                  "AIStateName",
                  "HoldFullBarTime",
                  "PancakeThrowDuration")),
          Map.entry(
              "ActionSelect",
              Set.of("SubActions", "Condition", "PerActionConditions", "PassOptionalActionDelay")),
          Map.entry("ActionFilter", Set.of("Condition", "OnTrueAction", "OnFalseAction")),
          Map.entry("ActionRunOnInstigator", Set.of("ActionToExecute")),
          Map.entry("ActionWaitToActivate", Set.of("Condition", "OnActivateAction")),
          Map.entry("ActionWithDuration", Set.of("ActionDuration")),
          // Its own columns, the condition and the bar it shows, only show something.
          Map.entry(
              "ActionEnabbleHPBarConditionForDuration",
              Set.of(
                  "ActionDuration",
                  "Condition",
                  "ContainerName",
                  "RequestShowBadge",
                  "RequestShowHealthIndicator")),
          // The card play's group, card and elixir tests, and the action an activating play runs.
          Map.entry(
              "ActionActivateOnCardDeploy",
              Set.of("CardGroup", "EvaluateDeployedCard", "OnActivateAction", "ElixirCost")),
          // The champion slot's row: whether a slot may follow another champion.
          Map.entry("ActionChampionAbilityData", Set.of("AllowDynamicReassignments")),
          // The tether's columns; the tags it sets on both ends are read by no battle code, and
          // the effects only show something.
          Map.entry(
              "ActionGoblinsteinAbility",
              Set.of(
                  "ConnectedCharacterGameTagsToSetDutingTether",
                  "GameTagsToSetDutingTether",
                  "OnTetherActivationAction",
                  "OnTetherActivationActionOnConnectedUnit",
                  "TetherDuration",
                  "TetherWidth",
                  "TetherDamage",
                  "TetherCrownTowerDamage",
                  "TetherHitInterval",
                  "TetherDamageTargets",
                  "TetherHitAction",
                  "TetherHitActionInterval",
                  "DeathAreaEffectData",
                  "TetherEffect",
                  "TetherTargetEffect",
                  "TetherTargetEffectMaxPerFrame",
                  "TetherTargetEffectOffset",
                  "TetherVolumeEffect",
                  "TetherVolumeEffectDistance")),
          Map.entry(
              "ActionInterval",
              Set.of(
                  "Interval",
                  "StartCounterAt",
                  "ActionToExecute",
                  "PauseTag",
                  "AffectedBySpawnSpeed")),
          Map.entry(
              "ActionFlipFlop",
              Set.of(
                  "Condition",
                  "ActivationTime",
                  "DeactivationTime",
                  "OnActivatedAction",
                  "OnDeactivatedAction")),
          Map.entry("ActionSetVariable", Set.of("Variable", "Value")),
          Map.entry("ActionSetShield", Set.of("ShieldPercent")),
          Map.entry("ActionRunActionAtHealth", Set.of("HealthPercentages", "Actions")),
          Map.entry("ActionHeal", Set.of("Value", "MaxOverHealPercent")),
          Map.entry("ActionKill", Set.of("OnKillAction")),
          Map.entry(
              "ActionSetAttackSequenceIndex", Set.of("AttackIndex", "SetEvenIfCombatDisabled")),
          Map.entry(
              "ActionChangeGameObjectData",
              Set.of("NewCharacterData", "ResetTarget", "NewProjectileData")),
          // HitAction is written by the row but read by nothing: the class reads hitAction.
          Map.entry(
              "ActionExecutionerEvoProjectile",
              Set.of(
                  "Damage",
                  "StrongDamage",
                  "StrongDamageRange",
                  "FirstStrongHitPushback",
                  "StrongHitAction",
                  "HitAction",
                  "hitAction")),
          Map.entry(
              "ActionRollingProjectile",
              Set.of(
                  "TargetFilter",
                  "Speed",
                  "DistanceY",
                  "DistanceX",
                  "Radius",
                  "BuffOnHit",
                  "BuffTime")),
          // The pull's clips and its start effect only show something.
          Map.entry(
              "ActionCaptureCharacter",
              Set.of(
                  "CaptureRadius",
                  "DamagePerHit",
                  "HitFrequency",
                  "NumberOfUnitsToCapture",
                  "CapturePriority",
                  "TargetFilter",
                  "DragDelay",
                  "CaptureDragTime",
                  "HideDistance",
                  "CaptureCooldown",
                  "PullCenterOffsetX",
                  "PullCenterOffsetY",
                  "HideAction",
                  "TimePausedWhenGrabbing",
                  "OnFirstCaptureAction",
                  "OnCaptureAction",
                  "ActionOnCapturedObject",
                  "BuffDuringCapture",
                  "HeightModifier",
                  "HeightModifierCap",
                  "PullEndClipExportName",
                  "PullFileName",
                  "PullStartEffect",
                  "StretchingClipExportName")),
          // The health bar's offset only shows something.
          Map.entry("ActionHide", Set.of("Duration", "StopWhenHiderDies", "HealthBarYOffset")),
          Map.entry("ActionRunActionOnInstigatorDeath", Set.of("ActionToRun")),
          // Every other column a taunt has keeps the loader's default here: the reach by
          // distance, the end as the duration runs out with no falloff, no end by a stun, no
          // building retargeting, the buff removed as it finishes, and no invalid or crown tower
          // duration or buff.
          Map.entry("ActionTaunt", Set.of("ValidDuration", "ValidTargetBuff")),
          Map.entry(
              "ActionRunActionListOnObjectsInShapeWithPrio",
              Set.of(
                  "OncePerTarget",
                  "TargetSelectionMode",
                  "TargetFilter",
                  "Delays",
                  "Shape",
                  "WaitForTarget",
                  "MaxWaitTimeForTarget",
                  "PauseTags",
                  "ParentAsInstigatorForSelfActions",
                  "Actions",
                  "OnFinishedAction",
                  "ActionOnSelfWhenTriggered",
                  "ActionOnSelfWhenTriggeredLeft",
                  "ActionOnSelfWhenTriggeredRight")),
          // Whether it acts on flyers alone is read by nothing; whether a clone runs the landing
          // actions is read only with a landing action.
          Map.entry(
              "ActionAirToGround",
              Set.of(
                  "OnlyAffectFlyers",
                  "TransitionDuration",
                  "TotalDuration",
                  "ResetPathAtEnd",
                  "ResetPathAtLanding",
                  "AllowIsGroundTagOnIdle",
                  "CloneTriggersLandingActions",
                  "ActionOnLanding",
                  "ActionOnLandingEnd",
                  "ActionOnGround")),
          // The follow-up dash's range, radius test and track are read only by the dash, which a
          // row
          // without DoFollowUpJump never reaches, and the push's gate byte only by the request's
          // path; its two effects only show something.
          Map.entry(
              "ActionMegaKnightUppercut",
              Set.of(
                  "PushBackStrength",
                  "PushRadiusDirectionalOffset",
                  "ResetPushbackIfStronger",
                  "DistanceProportinalPush",
                  "IgnorePushbackChecks",
                  "OnlyRunActionOnPushback",
                  "DoFollowUpJump",
                  "DashFollowUpMinRange",
                  "DashFollowUpMaxRange",
                  "DashFollowUpDelay",
                  "DashFollowUpUseRadius",
                  "DashFollowUpTrackEffect",
                  "DashFollowUpStartEffect",
                  "ActionOnTargets")),
          // Its landing and attached effects only show something.
          Map.entry(
              "ActionKnockback",
              Set.of(
                  "Height",
                  "Duration",
                  "ApplyNoCollisionTag",
                  "PassInstigatorToLandingAction",
                  "ActionOnLanding",
                  "LandingEffect",
                  "AttachedEffect")),
          Map.entry(
              "ActionSpawnResetableAeO",
              Set.of(
                  "Aeo",
                  "OffsetX",
                  "OffsetY",
                  "StopAeoIfParentHasCombatDisabled",
                  "StayAliveAfterParentDiesDuration")),
          Map.entry("ActionFilterByEnemy", Set.of("IsEnemyAction", "IsSameTeamAction")),
          Map.entry(
              "ActionAeoRunActionAtAliveTimerData",
              Set.of("AliveTimeList", "Actions", "AllowRepeatAction")),
          // The effects it lists, and which one its count picks, reach its client view alone.
          Map.entry(
              "ActionLaserBall",
              Set.of(
                  "DetectionRadius",
                  "FirstHitDelay",
                  "HitFrequency",
                  "PermanentDetection",
                  "ResetDetetcedUnitsAfterHit",
                  "DetectionCooldownAfterHit",
                  "HitFilter",
                  "MaxUnitPerActionList",
                  "OnDetectedUnitActionList",
                  "OnAttackActionList",
                  "TargetEffectList",
                  "MainEffectList")),
          Map.entry(
              "ActionSpawnGuard",
              Set.of(
                  "SpawnData",
                  "AppearBehindAtDistance",
                  "TargetRadius",
                  "PushBackStrength",
                  "PushBackRadius",
                  "ContinuosPushBack",
                  "DistanceProportinalPush",
                  "PushBackDamage",
                  "HitFilter")),
          // The lock, its timing and the warp row it schedules; the two speed bytes are read only
          // to refuse a row with either clear.
          Map.entry(
              "ActionBossBanditAbility",
              Set.of(
                  "WarpDelay",
                  "LockDelay",
                  "ReleaseLockDelay",
                  "WarpAction",
                  "WaitForDashToFinish",
                  "AllowWarpWhenMovementSpeedZero",
                  "AllowWarpWhenAttackSpeedZero")),
          // The instant warp's columns; the mode is read only to refuse every mode but the relative
          // one, and the two effects only show something. The flying warp's columns, a target
          // resolver and the warp's end action are refused as columns nothing reads.
          Map.entry(
              "ActionWarpCharacter",
              Set.of(
                  "WarpMode",
                  "WarpX",
                  "WarpY",
                  "ResetPath",
                  "ResetTarget",
                  "AvoidWaterVertically",
                  "AvoidBlockedTilesVertically",
                  "ResetPendingDamageAtWarp",
                  "WarpPositionEffect",
                  "WarpTargetEffect")),
          // Its stats tags only fill the card's stats panel.
          Map.entry(
              "ActionTargetIndicatorAttack",
              Set.of(
                  "LoadTime",
                  "TargetIndicatorDelay",
                  "AttackDelay",
                  "AttackCooldown",
                  "Range",
                  "MinimumRange",
                  "TargetFilter",
                  "TargetAoE",
                  "Projectile",
                  "ProjectileStartZ",
                  "ProjectileOffsetToCharacterLookDirection",
                  "GameTagsToSetToStopTargetIndication",
                  "TargetStartIndicationAction",
                  "OnProjectileShootAction")),
          Map.entry(
              "ActionRunIfGameObjectExists",
              Set.of(
                  "GameObjectFilter",
                  "MatchName",
                  "ExcludeName",
                  "NumMatchesNeeded",
                  "ActionToRun",
                  "ActionToRunIfNoMatch")),
          // A check of the cause reads no object filter: no shipped row sets one, so a row that
          // does is refused as a column nothing reads.
          Map.entry(
              "ActionRunIfInstigatorMatches",
              Set.of("MatchName", "ActionToRun", "ActionToRunIfNoMatch")),
          Map.entry(
              "ActionSetCharacterLevel", Set.of("RelativeLevelAdjustment", "AbsoluteLevelToSet")),
          Map.entry("ActionDealDamage", Set.of("BaseDamageAmount", "BaseDamageType")),
          Map.entry(
              "ActionGiantBufferCollectFriends",
              Set.of(
                  "Cooldown",
                  "MaxFriendlyTroops",
                  "TargetFilter",
                  "DistanceToGetTargets",
                  "DistanceToBuff",
                  "DistanceToUnbuff",
                  "UseAbility",
                  "BuffDelay",
                  "OnBuffAction",
                  "OnTargetBuffAction",
                  "Projectile",
                  "ActionWhenUnitBuffed")),
          // The buff hands the hits it enchanted to its enemy-target visual, which only shows
          // them, and keeps a refresh time nothing reads.
          Map.entry(
              "ActionGiantBufferBuff",
              Set.of(
                  "AttackAmount",
                  "AttackAmountAction",
                  "OnFinishedAction",
                  "AddedDamage",
                  "AddedCrownTowerDamage",
                  "FinishIfInstigatorDies",
                  "InstigatorDepth",
                  "RefreshTime",
                  "DamageMultiplierPerUnitNames",
                  "DamageMultiplierPerUnitValues",
                  "VisualActionForEnemyTarget")),
          // The evolved Royal Ghost's summons: the run on the Ghost and the row its summon areas
          // follow.
          Map.entry(
              "ActionGhostEvoAction",
              Set.of(
                  "SummonDistance",
                  "DamageAEO",
                  "DamageAEOSpawnDelay",
                  "SummonSpawnDelay",
                  "LeftSummonAreaType",
                  "RightSummonAreaType",
                  "SummonActionData")),
          Map.entry(
              "ActionGhostEvoSpawnSummon",
              Set.of(
                  "LeftSummonType",
                  "RightSummonType",
                  "InstantHitForSummons",
                  "ActionOnSummons",
                  "UseDeployForSummons")),
          // The effect-playing and forced-animation rows only show something: their own columns
          // reach the view alone, but for the two effect flags that keep a run.
          Map.entry(
              "ActionPlayEffect",
              Set.of(
                  "Effect",
                  "EffectFlags",
                  "OverrideDuration",
                  "OverrideScale",
                  "SpriteName",
                  "SpriteVisibility",
                  "PrefabAsset",
                  "OffsetZ",
                  "TargetOffsetZ",
                  "PauseIfTrue")),
          // The Goblin Hut's life state: its spawns, their point and child, the filter its finder
          // asks, the actions it runs on its owner, and the tag only the owner's effect rows read.
          Map.entry(
              "ActionGoblinHutLifeState",
              Set.of(
                  "SpawnInterval",
                  "SpawnData",
                  "SpawnNumber",
                  "SpawnOffset",
                  "SingleDeployOffsetAngle",
                  "ObjectFilter",
                  "OnSpawnAction",
                  "OnStartSpawningAction",
                  "OnStartWaitingAction",
                  "ToggleEffectTag")),
          // The Skeleton Barrel's pop action has no perform: its run is started and stepped and
          // does nothing. The balloons it pops as the hit points fall, their frames and effects
          // reach its view object alone. The columns of a singleton's second start, which drops
          // containers, are not read and so refused.
          Map.entry(
              "ActionSkeletonBarrelPopBalloon",
              Set.of(
                  "TransitionTime",
                  "DropBalloonAtHpList",
                  "BalloonPopStartFrameList",
                  "BalloonPopEndFrameList",
                  "BalloonFlyStartFrameList",
                  "BalloonFlyEndFrameList",
                  "UseSpecialKamikaze",
                  "SpecialKamikazeStartFrameList",
                  "SpecialKamikazeEndFrameList",
                  "SpecialDeployStartFrameLabel",
                  "SpecialDeployEndFrameLabel",
                  "OnPopBalloonEffectList")),
          // The Goblin Cage's shake has no perform: its run is started and stepped and does
          // nothing. Which frames it plays, and at what priority, is read by its client view
          // alone, from whether the owner holds a troop.
          Map.entry(
              "ActionPlayAnimationIfHasTarget",
              Set.of(
                  "TargetStartFrame",
                  "TargetEndFrame",
                  "IdleStartFrame",
                  "IdleEndFrame",
                  "Priority")),
          // The Berserker's starting action has no column of its own: its run sets and flips the
          // attack sequence index.
          Map.entry("ActionBerserk", Set.of()),
          Map.entry(
              "ActionRunForcedAnimationOnce",
              Set.of(
                  "PlaybackDuration", "CustomStateNumber", "PointToInstigator", "ForcedDuration")),
          // The deploy animation it names only shows something, and the card it names only
          // counts toward the statistics.
          Map.entry(
              "ActionClone",
              Set.of("OnClonedAction", "CloneDuration", "SpawnDeployBaseAnim", "CardDataForStats")),
          Map.entry("ActionSpawn", spawnColumns()),
          Map.entry("ActionSpawnToLocation", spawnColumns()),
          // The evolved Cannon's barrage: its bombs' areas and points. The relative offsets are
          // read only where an absolute one is missing or negative; the indicator clips and files
          // are read only by the card's placement preview.
          Map.entry(
              "ActionCannonBarrage",
              Set.of(
                  "BombVerticalOffsets",
                  "BombHorizontalOffsets",
                  "BombAbsoluteHorizontalOffsets",
                  "BombAreaEffectObjects",
                  "BombSpellTargetIndicatorClips",
                  "BombSpellTargetIndicatorFiles")),
          Map.entry("ActionCannonProjectileSpawn", Set.of("BombProjectile", "BombZOffset")));

  /** The spawn columns: the character branch's, and the other branches' it does not read. */
  private static Set<String> spawnColumns() {
    return Set.of(
        "SpawnData",
        "SpawnType",
        "Count",
        "SpawnRadius",
        "DeployTime",
        "SpawnLevelIndex",
        "UseDeploy",
        "IsEnemy",
        "IsDeathSpawn",
        "IsSpawnConstPriority",
        "SpawnPushback",
        "IgnoreEffects",
        "UseMorph",
        "AddToSourceGroup",
        "SpawnAsClone",
        "InheritPrestigeFromParent",
        "ParentGOAsSource",
        "ShareContext",
        "ValidatePlacementAsBuilding",
        "ActionToRunOnSpawned",
        "AbsoluteX",
        "AbsoluteY",
        "RelativeX",
        "RelativeY",
        "MirroredX",
        "MirroredY",
        "XPositionExpression",
        "YPositionExpression",
        // Not read by the character branch: the offsets are the area-effect branch's, the spawn
        // time is the buff branch's, and the start height and the target expressions are the
        // projectile branch's.
        "OffsetX",
        "OffsetY",
        "SpawnTime",
        "StartPositionZOffset",
        "TargetExprX",
        "TargetExprY");
  }

  private final GameTables tables;
  private final BattleRecords records;

  /**
   * @param tables the game tables of one data version
   * @param records the battle's records from the same tables, for the units a spawn names
   */
  public ActionRows(GameTables tables, BattleRecords records) {
    this.tables = tables;
    this.records = records;
  }

  /**
   * The action a row names, and every action it names in turn, built for one entity.
   *
   * @param name the row's name
   * @param binding what the rows need from the entity
   */
  public BattleAction build(String name, ActionBinding binding) {
    return new Build(binding).action(name);
  }

  /**
   * Whether an action row only plays an effect, looping or not: presentation, which nothing the
   * battle reads changes.
   *
   * @param name the row's name
   */
  public boolean playsEffect(String name) {
    return tables.action(name).classType().equals("ActionPlayEffect");
  }

  /** The bits of a list of game tags, each its row's index in the game tags table. */
  public long tagMask(String names) {
    long mask = 0;
    for (String name : names.split(",")) {
      String tag = name.trim();
      if (tag.isEmpty()) {
        continue;
      }
      GameTable table = tables.table("game_tags");
      if (!table.has(tag)) {
        throw new IllegalArgumentException("no game tag " + tag);
      }
      mask |= 1L << table.row(tag).index();
    }
    return mask;
  }

  /** One tree being built: every row it has built so far, and the rows still being built. */
  private final class Build {
    private final ActionBinding binding;
    private final Map<String, BattleAction> built = new HashMap<>();
    private final Set<String> building = new HashSet<>();

    private Build(ActionBinding binding) {
      this.binding = binding;
    }

    BattleAction action(String name) {
      BattleAction done = built.get(name);
      if (done != null) {
        return done;
      }
      if (!building.add(name)) {
        throw new UnsupportedOperationException(name + " names itself, which is not modelled");
      }
      GameAction row = tables.action(name);
      JsonNode f = row.fields();
      String type = row.classType();
      if (!READS.containsKey(type) && !INERT.contains(type)) {
        throw new UnsupportedOperationException(
            name + " is an " + type + ", which the battle does not have");
      }
      checkColumns(row);
      ActionRow shared = shared(row);
      BattleAction action =
          switch (type) {
            case "ActionGroup" ->
                new Group(shared, actions(f.get("SubActions")), ints(f.get("SubActionsDelay")));
            case "ActionSelect" ->
                new Select(
                    shared,
                    actions(f.get("SubActions")),
                    // A condition the data writes as a list compiles from its first element.
                    expression(first(f.get("Condition"))),
                    expressions(f.get("PerActionConditions")),
                    bool(f, "PassOptionalActionDelay"));
            case "ActionFilter" ->
                new Filter(
                    shared,
                    expression(f.get("Condition")),
                    action(f.get("OnTrueAction")),
                    action(f.get("OnFalseAction")));
            case "ActionRunOnInstigator" ->
                new RunOnInstigator(shared, action(f.get("ActionToExecute")));
            case "ActionWaitToActivate" ->
                new WaitToActivate(
                    shared, expression(f.get("Condition")), action(f.get("OnActivateAction")));
            case "ActionWithDuration" ->
                new WithDuration(shared, integer(f, "ActionDuration"), false, () -> 100, false);
            case "ActionEnabbleHPBarConditionForDuration" -> {
              // In a battle its run is a run with a duration; its own columns are read only by
              // what the bar shows.
              refuseShared(
                  name,
                  f,
                  "GameTagsToSet",
                  "Singleton",
                  "NextAction",
                  "ExecuteIfTrue",
                  "ActionPausedIfTrue");
              yield new WithDuration(shared, integer(f, "ActionDuration"), false, () -> 100, false);
            }
            case "ActionActivateOnCardDeploy" -> {
              refuseShared(
                  name,
                  f,
                  "GameTagsToSet",
                  "Singleton",
                  "NextAction",
                  "ExecuteIfTrue",
                  "ActionPausedIfTrue",
                  "ForceStopIfTrue");
              String group = f.path("CardGroup").asText("");
              yield new CardDeployListener(
                  shared,
                  group,
                  group.isEmpty() ? null : records.cardGroup(group),
                  bool(f, "EvaluateDeployedCard"),
                  integer(f, "ElixirCost"),
                  rowName(f.get("OnActivateAction")));
            }
            case "ActionChampionAbilityData" -> {
              refuseShared(
                  name,
                  f,
                  "GameTagsToSet",
                  "Singleton",
                  "NextAction",
                  "ExecuteIfTrue",
                  "ActionPausedIfTrue",
                  "ForceStopIfTrue");
              yield new ChampionAbility(
                  shared,
                  !f.hasNonNull("AllowDynamicReassignments")
                      || f.get("AllowDynamicReassignments").asBoolean());
            }
            case "ActionGoblinsteinAbility" -> {
              refuseShared(
                  name,
                  f,
                  "GameTagsToSet",
                  "Singleton",
                  "NextAction",
                  "ExecuteIfTrue",
                  "ActionPausedIfTrue",
                  "ForceStopIfTrue",
                  "AffectedByHitSpeed");
              String targets = rowName(f.get("TetherDamageTargets"));
              yield new GoblinsteinAbility(
                  shared,
                  new GoblinsteinAbility.Columns(
                      integer(f, "TetherDuration"),
                      rowName(f.get("DeathAreaEffectData")),
                      integer(f, "TetherWidth"),
                      integer(f, "TetherDamage"),
                      integer(f, "TetherCrownTowerDamage"),
                      integer(f, "TetherHitInterval"),
                      integer(f, "TetherHitActionInterval"),
                      targets == null ? null : records.filter(targets),
                      rowName(f.get("TetherHitAction")),
                      rowName(f.get("OnTetherActivationAction")),
                      rowName(f.get("OnTetherActivationActionOnConnectedUnit"))));
            }
            case "ActionInterval" ->
                new Interval(
                    shared,
                    integer(f, "Interval"),
                    integer(f, "StartCounterAt"),
                    action(f.get("ActionToExecute")),
                    f.has("PauseTag") ? tagMask(f.get("PauseTag").asText()) : 0,
                    binding.tags(),
                    // A row affected by the spawn speed steps at the owner's spawn rate, which
                    // its buffs set; any other at the usual 100.
                    bool(f, "AffectedBySpawnSpeed") ? binding.spawnRate() : () -> 100);
            case "ActionFlipFlop" ->
                new FlipFlop(
                    shared,
                    expression(f.get("Condition")),
                    integer(f, "ActivationTime"),
                    integer(f, "DeactivationTime"),
                    action(f.get("OnActivatedAction")),
                    action(f.get("OnDeactivatedAction")));
            case "ActionSetVariable" ->
                new SetVariable(
                    shared,
                    expression(f.get("Value")),
                    f.has("Variable")
                        ? binding.variableKey(f.get("Variable").asText())
                        : SetVariable.NO_VARIABLE);
            case "ActionSetShield" -> new SetShield(shared, integer(f, "ShieldPercent"));
            case "ActionRunActionAtHealth" ->
                new RunActionAtHealth(
                    shared, ints(f.get("HealthPercentages")), actions(f.get("Actions")));
            case "ActionHeal" ->
                new Heal(shared, expression(f.get("Value")), integer(f, "MaxOverHealPercent"));
            case "ActionKill" -> new Kill(shared, action(f.get("OnKillAction")));
            case "ActionSetAttackSequenceIndex" ->
                new SetAttackSequenceIndex(
                    shared, integer(f, "AttackIndex"), bool(f, "SetEvenIfCombatDisabled"));
            case "ActionTaunt" -> taunt(name, shared, f);
            case "ActionLaserBall" -> laserBall(name, shared, f);
            case "ActionSpawnGuard" -> spawnGuard(name, shared, f);
            case "ActionBossBanditAbility" -> bossBanditAbility(name, shared, f);
            case "ActionGhostEvoAction" -> ghostEvo(name, shared, f);
            case "ActionGhostEvoSpawnSummon" -> ghostSummon(name, shared, f);
            case "ActionWarpCharacter" -> warpCharacter(name, shared, f);
            case "ActionTargetIndicatorAttack" -> targetIndicatorAttack(name, shared, f);
            case "ActionRunActionListOnObjectsInShapeWithPrio" -> shapeSelector(name, shared, f);
            case "ActionAirToGround" -> airToGround(name, shared, f);
            case "ActionMegaKnightUppercut" -> uppercut(name, shared, f);
            case "ActionKnockback" -> knockback(name, shared, f);
            case "ActionCannonBarrage" -> cannonBarrage(name, shared, f);
            case "ActionCannonProjectileSpawn" -> cannonProjectileSpawn(name, shared, f);
            case "ActionSpawnResetableAeO" -> resetableAreaEffect(name, shared, f);
            case "ActionFilterByEnemy" -> {
              refuseUnread(name, f, true);
              yield new FilterByEnemy(
                  shared, action(f.get("IsSameTeamAction")), action(f.get("IsEnemyAction")));
            }
            case "ActionAeoRunActionAtAliveTimerData" -> aliveTimer(name, shared, f);
            case "ActionChangeGameObjectData" -> {
              // A projectile row's swap: the new row must read as a projectile the battle models,
              // and nothing else may be set.
              if (f.hasNonNull("NewProjectileData")) {
                if (f.hasNonNull("NewCharacterData") || bool(f, "ResetTarget")) {
                  throw new UnsupportedOperationException(
                      name + " swaps a projectile's row and sets a character's, not modelled");
                }
                String projectile = f.get("NewProjectileData").asText();
                List<String> unmodelled = records.projectile(projectile).unmodelledColumns();
                if (!unmodelled.isEmpty()) {
                  throw new UnsupportedOperationException(
                      name
                          + " swaps to "
                          + projectile
                          + ", which sets columns not modelled: "
                          + unmodelled);
                }
                yield new ChangeGameObjectData(shared, null, false, projectile);
              }
              // The new row must read as a unit here, so a row the battle cannot take is refused
              // as the action is built rather than when it runs.
              String newRow = f.path("NewCharacterData").asText();
              records.unit(newRow);
              yield new ChangeGameObjectData(shared, newRow, bool(f, "ResetTarget"));
            }
            case "ActionExecutionerEvoProjectile" -> executioner(name, shared, f);
            case "ActionRollingProjectile" -> rollingProjectile(name, shared, f);
            case "ActionCaptureCharacter" -> captureCharacter(name, shared, f);
            case "ActionHide" -> {
              refuseUnread(name, f, false);
              // The loader stores true for an empty column.
              yield new Hide(
                  shared,
                  integer(f, "Duration"),
                  !f.hasNonNull("StopWhenHiderDies") || bool(f, "StopWhenHiderDies"));
            }
            case "ActionRunActionOnInstigatorDeath" -> {
              refuseUnread(name, f, true);
              BattleAction toRun = action(f.get("ActionToRun"));
              if (toRun == null) {
                throw new UnsupportedOperationException(
                    name + " waits for its cause with no action to run, not modelled");
              }
              yield new RunActionOnInstigatorDeath(shared, toRun);
            }
            case "ActionRunIfGameObjectExists" ->
                new RunIfGameObjectExists(
                    shared,
                    f.hasNonNull("GameObjectFilter")
                        ? records.filter(f.get("GameObjectFilter").asText())
                        : null,
                    globalIds(f.get("MatchName")),
                    globalIds(f.get("ExcludeName")),
                    f.has("NumMatchesNeeded") ? f.get("NumMatchesNeeded").asInt() : 1,
                    action(f.get("ActionToRun")),
                    action(f.get("ActionToRunIfNoMatch")));
            case "ActionRunIfInstigatorMatches" ->
                new RunIfInstigatorMatches(
                    shared,
                    globalIds(f.get("MatchName")),
                    action(f.get("ActionToRun")),
                    action(f.get("ActionToRunIfNoMatch")));
            case "ActionSetCharacterLevel" ->
                new SetCharacterLevel(
                    shared,
                    integer(f, "RelativeLevelAdjustment"),
                    f.path("AbsoluteLevelToSet").asInt(1));
            case "ActionDealDamage" ->
                new DealDamage(
                    shared, integer(f, "BaseDamageAmount"), damageType(f.get("BaseDamageType")));
            case "ActionClone" ->
                new Clone(
                    shared,
                    action(f.get("OnClonedAction")),
                    integer(f, "CloneDuration") != 0
                        ? integer(f, "CloneDuration")
                        : Clone.DEFAULT_CLONE_DURATION_MS);
            case "ActionSpawn", "ActionSpawnToLocation" ->
                switch (f.path("SpawnType").asText("")) {
                  case "BuffType" ->
                      type.equals("ActionSpawn")
                          ? spawnBuff(name, shared, f)
                          : new SpawnCharacters(shared, spawn(name, type, f));
                  case "AreaEffectType" -> spawnAreaEffect(name, type, shared, f);
                  case "ProjectileType" -> spawnProjectile(name, type, shared, f);
                  default -> new SpawnCharacters(shared, spawn(name, type, f));
                };
            case "ActionGiantBufferCollectFriends" -> collectFriends(name, shared, f);
            case "ActionChefTower" -> chefCooking(shared, f);
            case "ActionGiantBufferBuff" -> giantBufferBuff(shared, f);
            case "ActionPlayEffect" -> new InertAction(shared, lasting(name, f.get("EffectFlags")));
            case "ActionRunForcedAnimationOnce" -> new InertAction(shared);
            case "ActionPlayAnimationIfHasTarget" -> {
              // Its run is listed for as long as its owner lives and changes nothing; the columns
              // that would give such a run something to do are held by no reference.
              for (String column :
                  List.of("GameTagsToSet", "ForceStopIfTrue", "Singleton", "NextAction")) {
                JsonNode value = f.get(column);
                boolean set =
                    value != null
                        && !value.isNull()
                        && !(value.isBoolean() && !value.asBoolean())
                        && !value.asText().isEmpty();
                if (set) {
                  throw new UnsupportedOperationException(
                      name + " plays an animation with " + column + ", which is not modelled");
                }
              }
              yield new PlayAnimationIfHasTarget(shared);
            }
            case "ActionBerserk" -> {
              // The shipped rows set only their class; a delay, a phase, tags, a gate or a chained
              // action on such a run is held by no reference.
              for (Iterator<String> columns = f.fieldNames(); columns.hasNext(); ) {
                String column = columns.next();
                if (!column.equals("ClassType")) {
                  throw new UnsupportedOperationException(
                      name + " sets " + column + " on a Berserker's run, which is not modelled");
                }
              }
              yield new Berserk(shared);
            }
            case "ActionGoblinHutLifeState" ->
                new GoblinHutLifeState(
                    shared,
                    GoblinHutLifeState.Columns.builder()
                        .spawnIntervalMs(integer(f, "SpawnInterval"))
                        .spawnData(f.path("SpawnData").asText())
                        .spawnNumber(integer(f, "SpawnNumber"))
                        .spawnOffset(integer(f, "SpawnOffset"))
                        .singleDeployOffsetAngle(integer(f, "SingleDeployOffsetAngle"))
                        .objectFilter(records.filter(f.path("ObjectFilter").asText()))
                        .onSpawnAction(action(f.get("OnSpawnAction")))
                        .onStartSpawningAction(action(f.get("OnStartSpawningAction")))
                        .onStartWaitingAction(action(f.get("OnStartWaitingAction")))
                        .toggleEffectTag(
                            f.has("ToggleEffectTag") ? f.get("ToggleEffectTag").asText() : null)
                        .build());
            case "ActionSkeletonBarrelPopBalloon" -> {
              if (shared.singleton()) {
                throw new UnsupportedOperationException(
                    name
                        + " is a singleton Skeleton Barrel pop, whose second start drops"
                        + " containers, which is not modelled");
              }
              yield new PopBalloons(shared);
            }
            default -> {
              if (INERT.contains(type)) {
                yield new InertAction(shared);
              }
              throw new UnsupportedOperationException(
                  name + " is an " + type + ", which the battle does not have");
            }
          };
      building.remove(name);
      built.put(name, action);
      return action;
    }

    /**
     * Refuses a row that sets one of the given shared columns, which its class's run does not
     * model.
     */
    private void refuseShared(String name, JsonNode f, String... columns) {
      for (String column : columns) {
        JsonNode value = f.get(column);
        boolean set =
            value != null
                && !value.isNull()
                && (value.isContainerNode()
                    ? !value.isEmpty()
                    : value.isBoolean() ? value.asBoolean() : !value.asText().isEmpty());
        if (set) {
          throw new UnsupportedOperationException(
              name
                  + " sets "
                  + column
                  + " on a "
                  + f.path("ClassType").asText()
                  + ", not modelled");
        }
      }
    }

    /** Refuses a row that sets a column its class does not read, show or share. */
    private void checkColumns(GameAction row) {
      Set<String> reads = READS.getOrDefault(row.classType(), Set.of());
      row.fields()
          .fieldNames()
          .forEachRemaining(
              column -> {
                if (!SHARED.contains(column)
                    && !PRESENTATION.contains(column)
                    && !RESOLVED.contains(column)
                    && !reads.contains(column)
                    && !INERT.contains(row.classType())) {
                  throw new UnsupportedOperationException(
                      row.name() + " sets " + column + ", which is not modelled");
                }
              });
      if (INERT.contains(row.classType()) && row.fields().has("GameTagsToSet")) {
        throw new UnsupportedOperationException(
            row.name() + " is inert but sets tags, which is not modelled");
      }
    }

    /**
     * The columns every row shares. An effect row that sets no tags and is no singleton changes
     * nothing while its run is listed, so its stop condition only decides when a run that changes
     * nothing ends: it is not compiled, and may ask what the object it runs on does not answer.
     */
    private ActionRow shared(GameAction row) {
      JsonNode f = row.fields();
      boolean bareEffect =
          row.classType().equals("ActionPlayEffect")
              && !f.path("Singleton").asBoolean(false)
              && f.path("GameTagsToSet").asText("").isEmpty();
      return ActionRow.builder()
          .name(row.name())
          .phase(integer(f, "UpdatePhase"))
          .delayMs(integer(f, "ActionDelay"))
          .singleton(f.path("Singleton").asBoolean(false))
          .nextAction(action(f.get("NextAction")))
          .nextActionWait(f.path("NextActionWait").asBoolean(false))
          .tags(f.has("GameTagsToSet") ? tagMask(f.get("GameTagsToSet").asText()) : 0)
          .executeIf(expression(f.get("ExecuteIfTrue")))
          .forceStopIf(bareEffect ? null : expression(f.get("ForceStopIfTrue")))
          .pausedIf(expression(f.get("ActionPausedIfTrue")))
          .abortIfInstigatorDies(f.path("AbortIfInstigatorDies").asBoolean(true))
          .build();
    }

    /**
     * A friend collector's columns. One that unbuffs a friend beyond a distance, by ending the
     * action it gave it, is refused: that end is not modelled.
     */
    private CollectFriends collectFriends(String name, ActionRow shared, JsonNode f) {
      if (integer(f, "DistanceToUnbuff") != 0 && action(f.get("ActionWhenUnitBuffed")) != null) {
        throw new UnsupportedOperationException(
            name + " unbuffs a friend beyond a distance, which is not modelled");
      }
      return new CollectFriends(
          shared,
          CollectFriends.Columns.builder()
              .cooldownMs(integer(f, "Cooldown"))
              .maxFriendlyTroops(integer(f, "MaxFriendlyTroops"))
              .targetFilter(records.filter(f.get("TargetFilter").asText()))
              .distanceToGetTargets(integer(f, "DistanceToGetTargets"))
              .distanceToBuff(integer(f, "DistanceToBuff"))
              .distanceToUnbuff(integer(f, "DistanceToUnbuff"))
              // The loader's default uses the ability.
              .useAbility(f.path("UseAbility").asBoolean(true))
              .buffDelayMs(integer(f, "BuffDelay"))
              .onBuffAction(action(f.get("OnBuffAction")))
              .onTargetBuffAction(action(f.get("OnTargetBuffAction")))
              .projectile(records.projectile(f.get("Projectile").asText()))
              .build());
    }

    /**
     * The Royal Chef's cooking columns, each with the default the game's loader gives a column the
     * row leaves out.
     */
    private ChefCooking chefCooking(ActionRow shared, JsonNode f) {
      return new ChefCooking(
          shared,
          ChefCooking.Columns.builder()
              .startCookingDelayMs(f.path("StartCookingDelay").asInt(0))
              .contributionNeeded(f.path("ContributionNeeded").asInt(1000))
              .contributionBaseline(f.path("ContributionBaseline").asInt(22))
              .contributionIdle(f.path("ContributionIdle").asInt(22))
              .contributionAttacking(f.path("ContributionAttacking").asInt(11))
              .contributionDestroyed(f.path("ContributionDestroyed").asInt(8))
              .targetFilter(
                  f.hasNonNull("TargetFilter")
                      ? records.filter(f.get("TargetFilter").asText())
                      : null)
              .minCurrentHpThreshold(f.path("MinCurrentHpThreshold").asInt(0))
              .minCurrentHpPercentage(f.path("MinCurrentHpPercentageThreshold").asInt(0))
              .minMaxHpThreshold(f.path("MinMaxHpThreshold").asInt(0))
              .deprioritizeBuffed(f.path("DeprioritizeBuffed").asBoolean(false))
              .buffProjectile(records.projectile(f.get("BuffProjectile").asText()))
              .pancakeThrowDelayMs(f.path("PancakeThrowDelay").asInt(300))
              .pancakeStartOffset(f.path("PancakeStartOffset").asInt(0))
              .pancakeThrowDelayThresholdMs(f.path("PancakeThrowDelayTreshold").asInt(200))
              .waitPancakeThrowAfterAttackMs(f.path("WaitPancakeThrowAfterAttackTime").asInt(200))
              .finishWhenBothTowersLost(f.path("FinishWhenBothTowersLost").asBoolean(true))
              .build());
    }

    /**
     * A hit-enchanting buff's columns. Its multipliers are keyed by the rows they name: a character
     * or building row, else a projectile row; a name that is neither is dropped, as the game drops
     * it when it reads the row.
     */
    private GiantBufferBuff giantBufferBuff(ActionRow shared, JsonNode f) {
      Map<String, Integer> characters = new HashMap<>();
      Map<String, Integer> projectiles = new HashMap<>();
      JsonNode names = f.path("DamageMultiplierPerUnitNames");
      List<Integer> values = ints(f.get("DamageMultiplierPerUnitValues"));
      for (int i = 0; i < names.size() && i < values.size(); i++) {
        String unit = names.get(i).asText();
        if (records.unitGlobalId(unit) != null) {
          characters.put(unit, values.get(i));
        } else if (tables.table("projectiles").has(unit)) {
          projectiles.put(unit, values.get(i));
        }
      }
      return new GiantBufferBuff(
          shared,
          GiantBufferBuff.Columns.builder()
              .attackAmount(integer(f, "AttackAmount"))
              .attackAmountAction(action(f.get("AttackAmountAction")))
              .onFinishedAction(action(f.get("OnFinishedAction")))
              .addedDamage(integer(f, "AddedDamage"))
              .addedCrownTowerDamage(integer(f, "AddedCrownTowerDamage"))
              .finishIfInstigatorDiesMs(integer(f, "FinishIfInstigatorDies"))
              .instigatorDepth(integer(f, "InstigatorDepth"))
              .characterMultipliers(characters)
              .projectileMultipliers(projectiles)
              .build());
    }

    /**
     * A buff spawn row's columns: the buff, its time and whether the owner is the source, nothing
     * more. A buff written inline is the buff row of its Name. A row that sets any other spawn
     * column, names a buff the battle does not model or one its parent controls, or gives it a time
     * below 1 is refused.
     */
    private SpawnBuff spawnBuff(String name, ActionRow shared, JsonNode f) {
      f.fieldNames()
          .forEachRemaining(
              column -> {
                if (spawnColumns().contains(column)
                    && !Set.of("SpawnData", "SpawnType", "SpawnTime", "ParentGOAsSource")
                        .contains(column)) {
                  throw new UnsupportedOperationException(
                      name + " spawns a buff and sets " + column + ", which is not modelled");
                }
              });
      JsonNode data = f.path("SpawnData");
      String buff = data.isObject() ? data.path("Name").asText() : data.asText();
      if (!records.buff(buff).unmodelledColumns().isEmpty()) {
        throw new UnsupportedOperationException(
            name
                + " spawns "
                + buff
                + ", which sets columns not modelled: "
                + records.buff(buff).unmodelledColumns());
      }
      if (records.buff(buff).controlledByParent() || integer(f, "SpawnTime") < 1) {
        throw new UnsupportedOperationException(
            name + " spawns a buff its parent controls or for no time, which is not modelled");
      }
      return new SpawnBuff(shared, buff, integer(f, "SpawnTime"), bool(f, "ParentGOAsSource"));
    }

    /**
     * A shape selector's columns: its circle, its filter, how it scores, its delays and their
     * actions, and whether it picks each object once, which by default it does. A row that waits
     * for a target, pauses, runs an action as it finishes or on its owner, scores by maximum hit
     * points or distance, has fewer actions than delays, is a singleton, chains a next action or
     * sets tags is refused; so is one without a filter, or whose shape is not a circle.
     */
    private ShapeSelector shapeSelector(String name, ActionRow shared, JsonNode f) {
      for (String column :
          List.of(
              "WaitForTarget",
              "MaxWaitTimeForTarget",
              "PauseTags",
              "OnFinishedAction",
              "ActionOnSelfWhenTriggered",
              "ActionOnSelfWhenTriggeredLeft",
              "ActionOnSelfWhenTriggeredRight",
              "ParentAsInstigatorForSelfActions",
              "Singleton",
              "NextAction",
              "GameTagsToSet")) {
        if (sets(f, column)) {
          throw new UnsupportedOperationException(
              name + " is a shape selector that sets " + column + ", which is not modelled");
        }
      }
      if (f.path("TargetFilter").asText("").isEmpty()) {
        throw new UnsupportedOperationException(
            name + " is a shape selector without a filter, which is not modelled");
      }
      String mode = f.path("TargetSelectionMode").asText("HighestCurrentHpIncludeShields");
      int selection =
          switch (mode) {
            case "HighestCurrentHp" -> ShapeSelector.HIGHEST_CURRENT_HP;
            case "HighestCurrentHpIncludeShields" ->
                ShapeSelector.HIGHEST_CURRENT_HP_INCLUDE_SHIELDS;
            default ->
                throw new UnsupportedOperationException(
                    name
                        + " is a shape selector that scores by "
                        + mode
                        + ", which is not modelled");
          };
      List<Integer> delays = ints(f.get("Delays"));
      // Each action is built for the object it is scheduled on, whose expressions it may ask.
      List<String> actions = new ArrayList<>();
      for (JsonNode reference : f.path("Actions")) {
        actions.add(reference.isObject() ? reference.path("action").asText() : reference.asText());
      }
      if (actions.size() < delays.size()) {
        throw new UnsupportedOperationException(
            name + " is a shape selector with fewer actions than delays, which is not modelled");
      }
      return new ShapeSelector(
          shared,
          ShapeSelector.Columns.builder()
              .oncePerTarget(f.path("OncePerTarget").asBoolean(true))
              .targetSelectionMode(selection)
              .targetFilter(records.filter(f.get("TargetFilter").asText()))
              .shapeRadius(records.circleRadius(f.path("Shape").asText()))
              .delaysMs(delays)
              .actions(actions)
              .build());
    }

    /**
     * An air-to-ground row's columns, a column it leaves out taking the loader's default: a
     * transition of 200, a whole of 1000, the path reset at the end and no ground tag on idle. A
     * row with a landing action, a path reset at landing, a next action or tags is refused.
     */
    private AirToGround airToGround(String name, ActionRow shared, JsonNode f) {
      for (String column :
          List.of(
              "ActionOnLanding",
              "ActionOnLandingEnd",
              "ActionOnGround",
              "ResetPathAtLanding",
              "NextAction",
              "GameTagsToSet")) {
        if (sets(f, column)) {
          throw new UnsupportedOperationException(
              name + ", an air-to-ground row, sets " + column + ", which is not modelled");
        }
      }
      return new AirToGround(
          shared,
          f.path("TransitionDuration").asInt(200),
          f.path("TotalDuration").asInt(1000),
          f.path("AllowIsGroundTagOnIdle").asBoolean(false),
          f.path("ResetPathAtEnd").asBoolean(true));
    }

    /**
     * Refuses a row that sets one of the shared columns the runs of the evolved Mega Knight's and
     * Baby Dragon's classes do not read: tags, a next action, the three gates, a delay and a phase,
     * and, unless the class reads it, Singleton.
     */
    private void refuseUnread(String name, JsonNode f, boolean singleton) {
      List<String> columns =
          new ArrayList<>(
              List.of(
                  "GameTagsToSet",
                  "NextAction",
                  "ExecuteIfTrue",
                  "ActionPausedIfTrue",
                  "ForceStopIfTrue",
                  "ActionDelay",
                  "UpdatePhase"));
      if (singleton) {
        columns.add("Singleton");
      }
      for (String column : columns) {
        if (sets(f, column)) {
          throw new UnsupportedOperationException(
              name
                  + ", an "
                  + f.path("ClassType").asText()
                  + ", sets "
                  + column
                  + ", which is not modelled");
        }
      }
    }

    /**
     * An uppercut's columns, a column it leaves out taking the loader's default: an offset of 50, a
     * longer pushback kept, no proportional push, the follow-up dash and a delay of 1000. A row
     * that pushes through the request's gates or dashes after the delay is refused.
     */
    private MegaKnightUppercut uppercut(String name, ActionRow shared, JsonNode f) {
      refuseUnread(name, f, true);
      if (!f.path("IgnorePushbackChecks").asBoolean(false)) {
        throw new UnsupportedOperationException(
            name + " pushes through the pushback request's gates, which is not modelled");
      }
      if (f.path("DoFollowUpJump").asBoolean(true)) {
        throw new UnsupportedOperationException(
            name + " dashes after its delay, which is not modelled");
      }
      return new MegaKnightUppercut(
          shared,
          integer(f, "PushBackStrength"),
          f.path("PushRadiusDirectionalOffset").asInt(50),
          f.path("DistanceProportinalPush").asBoolean(false),
          f.path("ResetPushbackIfStronger").asBoolean(true),
          f.path("DashFollowUpDelay").asInt(1000),
          action(f.get("ActionOnTargets")));
    }

    /**
     * A knock's columns. A row with a landing action, passing the cause on to it or the
     * no-collision tag is refused.
     */
    private Knockback knockback(String name, ActionRow shared, JsonNode f) {
      refuseUnread(name, f, true);
      for (String column :
          List.of("ActionOnLanding", "PassInstigatorToLandingAction", "ApplyNoCollisionTag")) {
        if (sets(f, column)) {
          throw new UnsupportedOperationException(
              name + ", a knock, sets " + column + ", which is not modelled");
        }
      }
      return new Knockback(shared, integer(f, "Height"), integer(f, "Duration"));
    }

    /**
     * The evolved Executioner's axe controller: its two damages, its strong range, the loader's
     * 3000 when it is left out, its push and its strong hit's action. A plain hit's action, read
     * under hitAction, and a push below 1 are refused.
     */
    private ExecutionerEvoProjectile executioner(String name, ActionRow shared, JsonNode f) {
      refuseUnread(name, f, false);
      if (action(f.get("hitAction")) != null) {
        throw new UnsupportedOperationException(
            name + " runs an action on a plain hit, which is not modelled");
      }
      if (integer(f, "FirstStrongHitPushback") < 1) {
        throw new UnsupportedOperationException(
            name + " pushes by less than 1 on a strong hit, which is not modelled");
      }
      return new ExecutionerEvoProjectile(
          shared,
          integer(f, "Damage"),
          integer(f, "StrongDamage"),
          f.hasNonNull("StrongDamageRange") ? integer(f, "StrongDamageRange") : 3000,
          integer(f, "FirstStrongHitPushback"),
          action(f.get("StrongHitAction")));
    }

    /**
     * A rolling projectile's columns. Refused: the shared columns its run does not read, and a row
     * without a filter or a buff.
     */
    private RollingProjectile rollingProjectile(String name, ActionRow shared, JsonNode f) {
      refuseUnread(name, f, true);
      for (String column : List.of("TargetFilter", "BuffOnHit")) {
        if (!sets(f, column)) {
          throw new UnsupportedOperationException(
              name + " rolls without " + column + ", which is not modelled");
        }
      }
      return new RollingProjectile(
          shared,
          RollingProjectile.Columns.builder()
              .speed(integer(f, "Speed"))
              .distanceY(integer(f, "DistanceY"))
              .distanceX(integer(f, "DistanceX"))
              .radius(integer(f, "Radius"))
              .buffOnHit(records.buff(f.get("BuffOnHit").asText()).name())
              .buffTimeMs(integer(f, "BuffTime"))
              .targetFilter(records.filter(f.get("TargetFilter").asText()))
              .build());
    }

    /**
     * A capture's columns. Its three actions are built here, so a row the battle cannot take is
     * refused as the capture is built, and each is built again on what it runs on as it is
     * scheduled. Refused: a delay before the drag, a pause in it, a pull centre off the projectile,
     * a cooldown, a height change, an action on each completed capture, damage per hit, a row
     * without a filter or a capture buff, and the shared columns its run does not read.
     */
    private CaptureCharacter captureCharacter(String name, ActionRow shared, JsonNode f) {
      refuseUnread(name, f, true);
      for (String column :
          List.of(
              "DragDelay",
              "TimePausedWhenGrabbing",
              "PullCenterOffsetX",
              "PullCenterOffsetY",
              "CaptureCooldown",
              "HeightModifier",
              "HeightModifierCap",
              "OnCaptureAction",
              "DamagePerHit")) {
        if (sets(f, column)) {
          throw new UnsupportedOperationException(
              name + " is a capture that sets " + column + ", which is not modelled");
        }
      }
      for (String column : List.of("TargetFilter", "BuffDuringCapture")) {
        if (!sets(f, column)) {
          throw new UnsupportedOperationException(
              name + " captures without " + column + ", which is not modelled");
        }
      }
      BattleAction hide = action(f.get("HideAction"));
      BattleAction first = action(f.get("OnFirstCaptureAction"));
      BattleAction onCaptured = action(f.get("ActionOnCapturedObject"));
      return new CaptureCharacter(
          shared,
          CaptureCharacter.Columns.builder()
              .captureRadius(integer(f, "CaptureRadius"))
              .numberOfUnitsToCapture(integer(f, "NumberOfUnitsToCapture"))
              .capturePriority(integer(f, "CapturePriority"))
              .captureDragTimeMs(integer(f, "CaptureDragTime"))
              .hideDistance(integer(f, "HideDistance"))
              .hitFrequencyMs(integer(f, "HitFrequency"))
              .targetFilter(records.filter(f.get("TargetFilter").asText()))
              .hideAction(hide == null ? null : hide.name())
              .onFirstCaptureAction(first == null ? null : first.name())
              .actionOnCapturedObject(onCaptured == null ? null : onCaptured.name())
              .buffDuringCapture(records.buff(f.get("BuffDuringCapture").asText()).name())
              .build());
    }

    /**
     * A barrage's columns. A row whose every bomb does not have an absolute, non-negative offset
     * across the arena is refused: the relative offset, counted from the edge of the owner's half,
     * is the one the shipped row never reaches. So is an area effect a bomb names that the battle
     * does not have, which would make no bomb.
     */
    private CannonBarrage cannonBarrage(String name, ActionRow shared, JsonNode f) {
      refuseUnread(name, f, true);
      List<Integer> vertical = ints(f.get("BombVerticalOffsets"));
      List<Integer> absolute = ints(f.get("BombAbsoluteHorizontalOffsets"));
      List<String> areas = new ArrayList<>();
      f.path("BombAreaEffectObjects").forEach(a -> areas.add(a.asText()));
      int bombs =
          Math.min(
              vertical.size(), Math.min(ints(f.get("BombHorizontalOffsets")).size(), areas.size()));
      for (int i = 0; i < bombs; i++) {
        if (i >= absolute.size() || absolute.get(i) < 0) {
          throw new UnsupportedOperationException(
              name + " places a bomb by its relative offset, which is not modelled");
        }
        List<String> unmodelled = records.areaEffect(areas.get(i)).unmodelledColumns();
        if (!unmodelled.isEmpty()) {
          throw new UnsupportedOperationException(
              name + " drops " + areas.get(i) + ", which sets columns not modelled: " + unmodelled);
        }
      }
      return new CannonBarrage(
          shared, vertical.subList(0, bombs), absolute.subList(0, bombs), areas.subList(0, bombs));
    }

    /**
     * A bomb drop's columns: its projectile, which must be one the battle models and must not home,
     * and its height. Its next action is read as any row's; the other shared columns are refused.
     */
    private CannonProjectileSpawn cannonProjectileSpawn(String name, ActionRow shared, JsonNode f) {
      for (String column :
          List.of(
              "GameTagsToSet",
              "Singleton",
              "ExecuteIfTrue",
              "ActionPausedIfTrue",
              "ForceStopIfTrue",
              "ActionDelay",
              "UpdatePhase")) {
        if (sets(f, column)) {
          throw new UnsupportedOperationException(
              name
                  + ", an ActionCannonProjectileSpawn, sets "
                  + column
                  + ", which is not modelled");
        }
      }
      String projectile = f.path("BombProjectile").asText("");
      if (projectile.isEmpty()) {
        throw new UnsupportedOperationException(
            name + " drops no projectile, which is not modelled");
      }
      List<String> unmodelled = records.projectile(projectile).unmodelledColumns();
      if (!unmodelled.isEmpty()) {
        throw new UnsupportedOperationException(
            name + " drops " + projectile + ", which sets columns not modelled: " + unmodelled);
      }
      if (records.projectile(projectile).homing()) {
        throw new UnsupportedOperationException(
            name + " drops a homing projectile, which is not modelled");
      }
      return new CannonProjectileSpawn(shared, projectile, integer(f, "BombZOffset"));
    }

    /**
     * A resetable area effect's columns, its stay -1, for no cut, when the row leaves it out. A row
     * that destroys its area effect while its owner's combat is disabled is refused.
     */
    private SpawnResetableAreaEffect resetableAreaEffect(
        String name, ActionRow shared, JsonNode f) {
      refuseUnread(name, f, false);
      if (f.path("StopAeoIfParentHasCombatDisabled").asBoolean(false)) {
        throw new UnsupportedOperationException(
            name
                + " destroys its area effect while its owner's combat is disabled, which is not"
                + " modelled");
      }
      return new SpawnResetableAreaEffect(
          shared,
          f.get("Aeo").asText(),
          integer(f, "OffsetX"),
          integer(f, "OffsetY"),
          f.path("StayAliveAfterParentDiesDuration").asInt(-1));
    }

    /**
     * The columns of actions run at ages, repeats allowed when the row leaves it out. An action
     * that is not an effect is refused.
     */
    private AliveTimer aliveTimer(String name, ActionRow shared, JsonNode f) {
      refuseUnread(name, f, true);
      for (JsonNode reference : f.path("Actions")) {
        String action = reference.path("action").asText();
        if (!playsEffect(action)) {
          throw new UnsupportedOperationException(
              name + " runs " + action + " at an age, which is not an effect, not modelled");
        }
      }
      return new AliveTimer(
          shared,
          ints(f.get("AliveTimeList")),
          actions(f.get("Actions")),
          f.path("AllowRepeatAction").asBoolean(true));
    }

    /** Whether a row sets a column to a value other than empty, 0, false or an empty list. */
    private static boolean sets(JsonNode f, String column) {
      JsonNode value = f.get(column);
      return value != null
          && !value.isNull()
          && !(value.isBoolean() && !value.asBoolean())
          && !(value.isNumber() && value.asInt() == 0)
          && !(value.isContainerNode() && value.isEmpty())
          && !(value.isTextual() && value.asText().isEmpty());
    }

    /**
     * A laser ball's columns: its query, its rate and the action lists its count picks from. A row
     * that keeps its detection from one step to the next, resets it after a hit, cools down after
     * one, runs an action list on its owner, is a singleton, chains a next action or sets tags is
     * refused; so is one without a filter.
     */
    private LaserBall laserBall(String name, ActionRow shared, JsonNode f) {
      for (String column :
          List.of(
              "PermanentDetection",
              "ResetDetetcedUnitsAfterHit",
              "DetectionCooldownAfterHit",
              "OnAttackActionList",
              "Singleton",
              "NextAction",
              "GameTagsToSet")) {
        if (sets(f, column)) {
          throw new UnsupportedOperationException(
              name + " is a laser ball that sets " + column + ", which is not modelled");
        }
      }
      if (f.path("HitFilter").asText("").isEmpty()) {
        throw new UnsupportedOperationException(
            name + " is a laser ball without a filter, which is not modelled");
      }
      return new LaserBall(
          shared,
          LaserBall.Columns.builder()
              .detectionRadius(integer(f, "DetectionRadius"))
              .firstHitDelayMs(integer(f, "FirstHitDelay"))
              .hitFrequencyMs(integer(f, "HitFrequency"))
              .hitFilter(records.filter(f.get("HitFilter").asText()))
              .maxUnitPerActionList(ints(f.get("MaxUnitPerActionList")))
              .onDetectedUnitActionList(actions(f.get("OnDetectedUnitActionList")))
              .build());
    }

    /**
     * A guard spawn's columns: its guard's row, where the guard appears and charges to, the push
     * and its damage, and the filter of its query; the tags its run on the guard sets. Refused: a
     * row that sets tags, a singleton, a next action, a gate or a phase of its own, one without a
     * filter, and one whose guard the battle cannot take.
     */
    private SpawnGuard spawnGuard(String name, ActionRow shared, JsonNode f) {
      refuseShared(
          name,
          f,
          "GameTagsToSet",
          "Singleton",
          "NextAction",
          "ExecuteIfTrue",
          "ActionPausedIfTrue",
          "ForceStopIfTrue",
          "UpdatePhase");
      if (f.path("HitFilter").asText("").isEmpty()) {
        throw new UnsupportedOperationException(
            name + " is a guard spawn without a filter, which is not modelled");
      }
      // The guard must read as a unit here, so a row the battle cannot take is refused as the
      // action is built rather than when it runs.
      String guard = f.path("SpawnData").asText();
      records.unit(guard);
      return new SpawnGuard(
          shared,
          SpawnGuard.Columns.builder()
              .spawnData(guard)
              .appearBehindAtDistance(integer(f, "AppearBehindAtDistance"))
              .targetRadius(f.hasNonNull("TargetRadius") ? f.get("TargetRadius").asInt() : 1000)
              .pushBackStrength(integer(f, "PushBackStrength"))
              .pushBackRadius(integer(f, "PushBackRadius"))
              .continuousPushBack(bool(f, "ContinuosPushBack"))
              .distanceProportionalPush(bool(f, "DistanceProportinalPush"))
              .pushBackDamage(integer(f, "PushBackDamage"))
              .hitFilter(records.filter(f.get("HitFilter").asText()))
              .guardTags(tagMask("NO_CHECKCOLLISIONS,NO_CHECKAVOIDANCE,NO_BUFFS"))
              .shadowTag(tagMask("NO_SHADOW"))
              .build());
    }

    /**
     * The evolved Royal Ghost's run: the summon distance, the two summon areas, the damage area and
     * its delay, and the row its summon areas follow, whose summon delay is this row's. Refused:
     * tags, a singleton, a next action, the gates, a phase, a delay and a speed byte, which no
     * shipped row sets, and a summon row of another class.
     */
    private GhostEvo ghostEvo(String name, ActionRow shared, JsonNode f) {
      refuseGhostShared(name, f);
      BattleAction summon = action(f.get("SummonActionData"));
      if (!(summon instanceof GhostEvo.Summon summonRow)) {
        throw new UnsupportedOperationException(
            name + " names a summon row of another class, which is not modelled");
      }
      // The summon delay is read from this row and handed to the summon runs it makes.
      GhostEvo.Summon withDelay = summonRow.withDelay(integer(f, "SummonSpawnDelay"));
      return new GhostEvo(
          shared,
          GhostEvo.Columns.builder()
              // The loader's default distance is 250.
              .summonDistance(f.path("SummonDistance").asInt(250))
              .damageArea(rowName(f.get("DamageAEO")))
              .damageAreaDelayMs(integer(f, "DamageAEOSpawnDelay"))
              .leftArea(rowName(f.get("LeftSummonAreaType")))
              .rightArea(rowName(f.get("RightSummonAreaType")))
              .summon(withDelay)
              .build());
    }

    /**
     * The row an evolved Royal Ghost's summon areas follow: the summon each side spawns. Refused:
     * the shared columns no shipped row sets, an instant hit, an action on the summons and a spawn
     * without the deploy, which the loader defaults to on.
     */
    private GhostEvo.Summon ghostSummon(String name, ActionRow shared, JsonNode f) {
      refuseGhostShared(name, f);
      if (bool(f, "InstantHitForSummons")
          || f.hasNonNull("ActionOnSummons")
          || !f.path("UseDeployForSummons").asBoolean(true)) {
        throw new UnsupportedOperationException(
            name
                + " hits at once, runs an action on its summons or spawns them without their"
                + " deploy, which is not modelled");
      }
      return new GhostEvo.Summon(
          shared, rowName(f.get("LeftSummonType")), rowName(f.get("RightSummonType")), 0);
    }

    /** Refuses the shared columns no evolved Royal Ghost row sets. */
    private void refuseGhostShared(String name, JsonNode f) {
      refuseShared(
          name,
          f,
          "GameTagsToSet",
          "Singleton",
          "NextAction",
          "ExecuteIfTrue",
          "ActionPausedIfTrue",
          "ForceStopIfTrue",
          "AffectedByHitSpeed",
          "UpdatePhase",
          "ActionDelay");
    }

    /**
     * A Boss Bandit ability's columns: its warp and lock delays, the release delay, the warp row
     * and whether it waits while its unit dashes. Refused: a row that sets tags, a singleton, a
     * next action or a gate; one with either speed byte clear, whose speed scalers are not
     * modelled; one without a warp row; and one whose release delay is below one step, which would
     * finish in the warp's own step.
     */
    private BossBanditAbility bossBanditAbility(String name, ActionRow shared, JsonNode f) {
      refuseShared(
          name,
          f,
          "GameTagsToSet",
          "Singleton",
          "NextAction",
          "ExecuteIfTrue",
          "ActionPausedIfTrue",
          "ForceStopIfTrue");
      if (!bool(f, "AllowWarpWhenMovementSpeedZero") || !bool(f, "AllowWarpWhenAttackSpeedZero")) {
        throw new UnsupportedOperationException(
            name + " asks its unit's speeds before it warps, which is not modelled");
      }
      BattleAction warp = action(f.get("WarpAction"));
      if (warp == null) {
        throw new UnsupportedOperationException(name + " has no warp row, which is not modelled");
      }
      if (integer(f, "ReleaseLockDelay") < 1) {
        throw new UnsupportedOperationException(
            name + " releases its lock in the warp's own step, which is not modelled");
      }
      return new BossBanditAbility(
          shared,
          BossBanditAbility.Columns.builder()
              .warpDelayMs(integer(f, "WarpDelay"))
              .lockDelayMs(integer(f, "LockDelay"))
              .releaseLockDelayMs(integer(f, "ReleaseLockDelay"))
              .warpAction(warp)
              // The loader defaults the dash wait to on.
              .waitForDashToFinish(f.path("WaitForDashToFinish").asBoolean(true))
              .build());
    }

    /**
     * An instant warp's columns: its offset, the landing's avoidance and the resets after it, the
     * four the loader defaults to on. Refused: a mode other than the relative one, and a row that
     * sets tags, a singleton, a next action that waits for it, or a gate.
     */
    private WarpCharacter warpCharacter(String name, ActionRow shared, JsonNode f) {
      refuseShared(
          name,
          f,
          "GameTagsToSet",
          "Singleton",
          "NextActionWait",
          "ExecuteIfTrue",
          "ActionPausedIfTrue",
          "ForceStopIfTrue");
      String mode = f.path("WarpMode").asText("");
      if (!mode.isEmpty() && !mode.equals("RelativeWarp")) {
        throw new UnsupportedOperationException(
            name + " warps in mode " + mode + ", which is not modelled");
      }
      return new WarpCharacter(
          shared,
          WarpCharacter.Columns.builder()
              .warpX(integer(f, "WarpX"))
              .warpY(integer(f, "WarpY"))
              .resetPath(f.path("ResetPath").asBoolean(true))
              .resetTarget(bool(f, "ResetTarget"))
              .avoidWater(f.path("AvoidWaterVertically").asBoolean(true))
              .avoidBlocked(f.path("AvoidBlockedTilesVertically").asBoolean(true))
              .resetPendingDamage(f.path("ResetPendingDamageAtWarp").asBoolean(true))
              .build());
    }

    /**
     * A target indicator attack's columns: its clock, its ring, its signal, its projectile and
     * where it starts, the tags a shot sets and the two actions it runs on its owner. A row with an
     * indication delay, a negative attack delay, a minimum range below 1, a singleton, a next
     * action, tags or a gate is refused; so is one without a filter, whose signal follows anything
     * or sets a column not modelled, or whose projectile homes, comes back, waits a random delay or
     * sets a column not modelled.
     */
    private TargetIndicatorAttack targetIndicatorAttack(String name, ActionRow shared, JsonNode f) {
      for (String column :
          List.of(
              "TargetIndicatorDelay",
              "Singleton",
              "NextAction",
              "GameTagsToSet",
              "ExecuteIfTrue",
              "ActionPausedIfTrue",
              "ForceStopIfTrue")) {
        if (sets(f, column)) {
          throw new UnsupportedOperationException(
              name
                  + " is a target indicator attack that sets "
                  + column
                  + ", which is not modelled");
        }
      }
      if (integer(f, "AttackDelay") < 0 || integer(f, "MinimumRange") < 1) {
        throw new UnsupportedOperationException(
            name
                + " is a target indicator attack that sets a negative AttackDelay or a"
                + " MinimumRange below 1, which is not modelled");
      }
      if (f.path("TargetFilter").asText("").isEmpty()) {
        throw new UnsupportedOperationException(
            name + " is a target indicator attack without a filter, which is not modelled");
      }
      String signal = f.path("TargetAoE").asText();
      AreaEffectData signalData = records.areaEffect(signal);
      if (signalData.followsParent() || !signalData.unmodelledColumns().isEmpty()) {
        throw new UnsupportedOperationException(
            name
                + " marks its target with "
                + signal
                + ", which follows something or sets columns not modelled");
      }
      ProjectileData projectile = records.projectile(f.path("Projectile").asText());
      if (projectile.homing()
          || projectile.homingTimeMs() >= 1
          || projectile.pingpongVisualTimeMs() >= 1
          || projectile.dragBackSpeed() >= 1
          || projectile.randomDelayMs() >= 1
          || !projectile.unmodelledColumns().isEmpty()) {
        throw new UnsupportedOperationException(
            name
                + " shoots "
                + projectile.name()
                + ", which homes, comes back, waits or sets columns not modelled");
      }
      return new TargetIndicatorAttack(
          shared,
          TargetIndicatorAttack.Columns.builder()
              .loadTimeMs(integer(f, "LoadTime"))
              .attackDelayMs(integer(f, "AttackDelay"))
              .attackCooldownMs(integer(f, "AttackCooldown"))
              .range(integer(f, "Range"))
              .minimumRange(integer(f, "MinimumRange"))
              .targetFilter(records.filter(f.get("TargetFilter").asText()))
              .targetAoE(signal)
              .projectile(projectile.name())
              .projectileStartZ(integer(f, "ProjectileStartZ"))
              .lookOffset(integer(f, "ProjectileOffsetToCharacterLookDirection"))
              .stopTags(
                  f.has("GameTagsToSetToStopTargetIndication")
                      ? tagMask(f.get("GameTagsToSetToStopTargetIndication").asText())
                      : 0)
              .targetStartIndicationAction(action(f.get("TargetStartIndicationAction")))
              .onProjectileShootAction(action(f.get("OnProjectileShootAction")))
              .build());
    }

    /**
     * An area-effect spawn row's columns: the area effect and whether the owner is the source. A
     * row of the location class, one that sets any other spawn column, writes its area effect
     * inline, or names one whose row sets a column not modelled is refused.
     */
    private SpawnAreaEffect spawnAreaEffect(
        String name, String type, ActionRow shared, JsonNode f) {
      if (!type.equals("ActionSpawn")) {
        throw new UnsupportedOperationException(
            name + " spawns an area effect to a location, which is not modelled");
      }
      for (Iterator<String> columns = f.fieldNames(); columns.hasNext(); ) {
        String column = columns.next();
        if (spawnColumns().contains(column)
            && !Set.of("SpawnData", "SpawnType", "ParentGOAsSource").contains(column)) {
          throw new UnsupportedOperationException(
              name + " spawns an area effect and sets " + column + ", which is not modelled");
        }
      }
      if (!f.path("SpawnData").isTextual()) {
        throw new UnsupportedOperationException(
            name + " spawns an area effect written inline, which is not modelled");
      }
      String areaEffect = f.path("SpawnData").asText();
      List<String> unmodelled = records.areaEffect(areaEffect).unmodelledColumns();
      if (!unmodelled.isEmpty()) {
        throw new UnsupportedOperationException(
            name + " spawns " + areaEffect + ", which sets columns not modelled: " + unmodelled);
      }
      return new SpawnAreaEffect(shared, areaEffect, bool(f, "ParentGOAsSource"));
    }

    /**
     * A taunt row. One that lasts past a step reaches the parts of its update that re-check and
     * mark its reference, which are not modelled, and its buff must read as a modelled buff.
     */
    private Taunt taunt(String name, ActionRow shared, JsonNode f) {
      int duration = integer(f, "ValidDuration");
      if (duration > TAUNT_STEP_MS) {
        throw new UnsupportedOperationException(
            name + " taunts for " + duration + " ms, past one step, which is not modelled");
      }
      String buff = f.hasNonNull("ValidTargetBuff") ? f.get("ValidTargetBuff").asText() : null;
      if (buff != null && !records.buff(buff).unmodelledColumns().isEmpty()) {
        throw new UnsupportedOperationException(
            name
                + " taunts with "
                + buff
                + ", which sets columns not modelled: "
                + records.buff(buff).unmodelledColumns());
      }
      return new Taunt(shared, duration, buff);
    }

    /**
     * A projectile spawn row: of the location class, the owner as its source, aimed by at least one
     * target expression; any other spawn column but the action to run on what it spawned, which
     * this branch does not read, is refused.
     */
    private SpawnProjectile spawnProjectile(
        String name, String type, ActionRow shared, JsonNode f) {
      if (!type.equals("ActionSpawnToLocation")) {
        throw new UnsupportedOperationException(
            name + " spawns a projectile from a spawn row's own position, which is not modelled");
      }
      for (Iterator<String> columns = f.fieldNames(); columns.hasNext(); ) {
        String column = columns.next();
        if (spawnColumns().contains(column)
            && !Set.of(
                    "SpawnData",
                    "SpawnType",
                    "ParentGOAsSource",
                    "StartPositionZOffset",
                    "TargetExprX",
                    "TargetExprY",
                    "ActionToRunOnSpawned")
                .contains(column)) {
          throw new UnsupportedOperationException(
              name + " spawns a projectile and sets " + column + ", which is not modelled");
        }
      }
      if (!bool(f, "ParentGOAsSource")) {
        throw new UnsupportedOperationException(
            name + " spawns a projectile from its cause, which is not modelled");
      }
      IntSupplier aimX = expression(f.get("TargetExprX"));
      IntSupplier aimY = expression(f.get("TargetExprY"));
      if (aimX == null && aimY == null) {
        throw new UnsupportedOperationException(
            name + " spawns a projectile at its owner's target, which is not modelled");
      }
      if (!f.path("SpawnData").isTextual()) {
        throw new UnsupportedOperationException(
            name + " spawns a projectile written inline, which is not modelled");
      }
      return new SpawnProjectile(
          shared, f.path("SpawnData").asText(), integer(f, "StartPositionZOffset"), aimX, aimY);
    }

    /** A character spawn row's columns; any other spawn type is refused. */
    private SpawnRow spawn(String name, String type, JsonNode f) {
      String spawnType = f.path("SpawnType").asText("");
      if (!spawnType.equals("CharacterType")) {
        throw new UnsupportedOperationException(
            name + " spawns " + spawnType + ", which is not modelled");
      }
      // The projectile branch's columns, which the character branch does not read.
      for (String column : List.of("StartPositionZOffset", "TargetExprX", "TargetExprY")) {
        if (f.has(column)) {
          throw new UnsupportedOperationException(
              name + " spawns characters and sets " + column + ", which is not modelled");
        }
      }
      SpawnRow.SpawnRowBuilder row =
          SpawnRow.builder()
              .toLocation(type.equals("ActionSpawnToLocation"))
              .spawnData(records.unit(f.get("SpawnData").asText()))
              .spawnRadius(integer(f, "SpawnRadius"))
              .deployTimeMs(integer(f, "DeployTime"))
              .useDeploy(bool(f, "UseDeploy"))
              .isEnemy(bool(f, "IsEnemy"))
              .isSpawnConstPriority(bool(f, "IsSpawnConstPriority"))
              .spawnPushback(bool(f, "SpawnPushback"))
              .ignoreEffects(bool(f, "IgnoreEffects"))
              .useMorph(bool(f, "UseMorph"))
              .addToSourceGroup(bool(f, "AddToSourceGroup"))
              .spawnAsClone(bool(f, "SpawnAsClone"))
              .inheritPrestigeFromParent(bool(f, "InheritPrestigeFromParent"))
              .parentGoAsSource(bool(f, "ParentGOAsSource"))
              .shareContext(bool(f, "ShareContext"))
              .validatePlacementAsBuilding(bool(f, "ValidatePlacementAsBuilding"))
              .actionToRunOnSpawned(action(f.get("ActionToRunOnSpawned")))
              .absoluteX(integer(f, "AbsoluteX"))
              .absoluteY(integer(f, "AbsoluteY"))
              .relativeX(integer(f, "RelativeX"))
              .relativeY(integer(f, "RelativeY"))
              .mirroredX(integer(f, "MirroredX"))
              .mirroredY(integer(f, "MirroredY"))
              .xPositionExpression(expression(f.get("XPositionExpression")))
              .yPositionExpression(expression(f.get("YPositionExpression")));
      if (f.has("Count")) {
        row.count(integer(f, "Count"));
      }
      if (f.has("SpawnLevelIndex")) {
        row.spawnLevelIndex(integer(f, "SpawnLevelIndex"));
      }
      if (f.has("IsDeathSpawn")) {
        row.isDeathSpawn(bool(f, "IsDeathSpawn"));
      }
      return row.build();
    }

    /** A damage type row, its switches on unless the row turns them off. */
    private DamageType damageType(JsonNode name) {
      if (name == null || name.isNull() || name.asText().isEmpty()) {
        return null;
      }
      GameRow row = tables.table("damage_types").row(name.asText());
      JsonNode actionOnSource = row.value("ActionOnSource");
      JsonNode actionOnTarget = row.value("ActionOnTarget");
      return DamageType.builder()
          .name(row.name())
          .enableLevelScaling(on(row, "EnableLevelScaling"))
          .enableProtection(on(row, "EnableProtection"))
          .enableDamageMultiplier(on(row, "EnableDamageMultiplier"))
          .enableDamageOnHit(on(row, "EnableDamageOnHit"))
          .acquireDamageId(on(row, "AcquireDamageId"))
          .actionOnSource(action(actionOnSource))
          .actionOnTarget(action(actionOnTarget))
          .build();
    }

    /** A named action, a row inline by name, or null for none. */
    private BattleAction action(JsonNode reference) {
      if (reference == null || reference.isNull()) {
        return null;
      }
      String name = reference.isObject() ? reference.path("action").asText("") : reference.asText();
      return name.isEmpty() ? null : action(name);
    }

    private List<BattleAction> actions(JsonNode references) {
      List<BattleAction> out = new ArrayList<>();
      if (references != null && references.isArray()) {
        references.forEach(reference -> out.add(action(reference)));
      } else if (references != null && !references.isNull()) {
        out.add(action(references));
      }
      return out;
    }

    /** An expression column: a number is a constant, text is compiled for the entity. */
    private IntSupplier expression(JsonNode value) {
      if (value == null || value.isNull()) {
        return null;
      }
      if (value.isNumber()) {
        int constant = value.asInt();
        return () -> constant;
      }
      String text = value.asText();
      return text.isEmpty() ? null : binding.expression(text);
    }

    /**
     * The global ids of the character and building rows a list names, in order; a name the data has
     * no such row for is dropped, as the game drops it when it reads the row.
     */
    private List<Integer> globalIds(JsonNode names) {
      List<Integer> ids = new ArrayList<>();
      if (names == null || names.isNull()) {
        return ids;
      }
      for (JsonNode name : names.isArray() ? names : List.of(names)) {
        Integer id = records.unitGlobalId(name.asText());
        if (id != null) {
          ids.add(id);
        }
      }
      return ids;
    }

    /** A list's first element, null for an empty one, or the value itself when it is no list. */
    private static JsonNode first(JsonNode value) {
      if (value == null || !value.isArray()) {
        return value;
      }
      return value.isEmpty() ? null : value.get(0);
    }

    private List<IntSupplier> expressions(JsonNode values) {
      if (values == null || values.isNull()) {
        return null;
      }
      List<IntSupplier> out = new ArrayList<>();
      if (values.isArray()) {
        values.forEach(value -> out.add(expression(value)));
      } else {
        out.add(expression(values));
      }
      return out;
    }
  }

  /** The effect flag that loops an effect. */
  private static final String LOOPING = "looping";

  /** The effect flag that ties an effect's life to its run's. */
  private static final String LINK_LIFE = "linklifetoactionlife";

  /**
   * Whether an action row only plays an effect that ends as it plays: an effect row whose flags
   * keep no run, which makes no run of its own.
   *
   * @param row the action row
   */
  static boolean inertEffect(GameAction row) {
    return row.classType().equals("ActionPlayEffect")
        && !lasting(row.name(), row.fields().get("EffectFlags"));
  }

  /**
   * True when an effect row's flags keep a run: Looping or LinkLifeToActionLife among them. The
   * flags are a comma list, trimmed and in any case; a list written as an array is read as its one
   * string; none is the default, which keeps no run.
   */
  private static boolean lasting(String row, JsonNode flags) {
    if (flags == null || flags.isNull()) {
      return false;
    }
    String text;
    if (flags.isArray()) {
      if (flags.size() != 1) {
        throw new UnsupportedOperationException(
            row + " writes its effect flags as an array of " + flags.size() + ", not modelled");
      }
      text = flags.get(0).asText();
    } else {
      text = flags.asText();
    }
    for (String flag : text.split(",")) {
      String name = flag.trim().toLowerCase(Locale.ROOT);
      if (name.equals(LOOPING) || name.equals(LINK_LIFE)) {
        return true;
      }
    }
    return false;
  }

  /**
   * A column that names a row, written as the name or as a reference to it: the name, or null when
   * the column is unset or empty.
   */
  private static String rowName(JsonNode value) {
    if (value == null || value.isNull()) {
      return null;
    }
    String name = value.isObject() ? value.path("action").asText("") : value.asText();
    return name.isEmpty() ? null : name;
  }

  private static int integer(JsonNode fields, String column) {
    JsonNode value = fields.get(column);
    return value == null || value.isNull() ? 0 : value.asInt();
  }

  private static boolean bool(JsonNode fields, String column) {
    return fields.path(column).asBoolean(false);
  }

  private static List<Integer> ints(JsonNode values) {
    List<Integer> out = new ArrayList<>();
    if (values != null && values.isArray()) {
      values.forEach(value -> out.add(value.asInt()));
    } else if (values != null && !values.isNull()) {
      out.add(values.asInt());
    }
    return out;
  }

  /** A switch of a damage type row: on unless the row sets it off. */
  private static boolean on(GameRow row, String column) {
    JsonNode value = row.value(column);
    return value == null || value.isNull() || value.asBoolean();
  }
}
