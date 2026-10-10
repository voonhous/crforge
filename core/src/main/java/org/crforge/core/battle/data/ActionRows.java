/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.IntUnaryOperator;
import java.util.stream.Stream;
import org.crforge.core.battle.action.ActionContext;
import org.crforge.core.battle.action.ActionRow;
import org.crforge.core.battle.action.AirToGround;
import org.crforge.core.battle.action.AliveTimer;
import org.crforge.core.battle.action.AttackChain;
import org.crforge.core.battle.action.BarbBarrelHeroReRoll;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.Berserk;
import org.crforge.core.battle.action.BlackboardSetInt;
import org.crforge.core.battle.action.BlowdartController;
import org.crforge.core.battle.action.BlowdartDamage;
import org.crforge.core.battle.action.BlowdartDartSelect;
import org.crforge.core.battle.action.BossBanditAbility;
import org.crforge.core.battle.action.BurstAttack;
import org.crforge.core.battle.action.CannonBarrage;
import org.crforge.core.battle.action.CannonProjectileSpawn;
import org.crforge.core.battle.action.CaptureCharacter;
import org.crforge.core.battle.action.CardDeployListener;
import org.crforge.core.battle.action.ChainProjectileAttack;
import org.crforge.core.battle.action.ChampionAbility;
import org.crforge.core.battle.action.ChangeGameObjectData;
import org.crforge.core.battle.action.ChefCooking;
import org.crforge.core.battle.action.Clone;
import org.crforge.core.battle.action.CollectFriends;
import org.crforge.core.battle.action.ConeShape;
import org.crforge.core.battle.action.ContextToVariable;
import org.crforge.core.battle.action.Counter;
import org.crforge.core.battle.action.CreateParallelProjectiles;
import org.crforge.core.battle.action.DamageType;
import org.crforge.core.battle.action.DamagingPushBack;
import org.crforge.core.battle.action.DashingAttackChain;
import org.crforge.core.battle.action.DealDamage;
import org.crforge.core.battle.action.DoPushbackFromInstigator;
import org.crforge.core.battle.action.ExecutionerEvoProjectile;
import org.crforge.core.battle.action.Filter;
import org.crforge.core.battle.action.FilterByEnemy;
import org.crforge.core.battle.action.FlipFlop;
import org.crforge.core.battle.action.GhostEvo;
import org.crforge.core.battle.action.GiantBufferBuff;
import org.crforge.core.battle.action.GoblinDrillEvoRelocate;
import org.crforge.core.battle.action.GoblinHutLifeState;
import org.crforge.core.battle.action.GoblinsteinAbility;
import org.crforge.core.battle.action.GroundToAir;
import org.crforge.core.battle.action.Group;
import org.crforge.core.battle.action.Heal;
import org.crforge.core.battle.action.Hide;
import org.crforge.core.battle.action.HunterNetAttack;
import org.crforge.core.battle.action.InertAction;
import org.crforge.core.battle.action.Interval;
import org.crforge.core.battle.action.Kill;
import org.crforge.core.battle.action.Knockback;
import org.crforge.core.battle.action.LaserBall;
import org.crforge.core.battle.action.LumberjackGhostWait;
import org.crforge.core.battle.action.MegaKnightUppercut;
import org.crforge.core.battle.action.MegaMinionHeroAbility;
import org.crforge.core.battle.action.MirroredExtraSpell;
import org.crforge.core.battle.action.MusketeerSnipe;
import org.crforge.core.battle.action.OverrideAbilityButtonState;
import org.crforge.core.battle.action.OverrideProjectileSpeed;
import org.crforge.core.battle.action.PlayAnimationIfHasTarget;
import org.crforge.core.battle.action.PopBalloons;
import org.crforge.core.battle.action.ReadyChampionAbility;
import org.crforge.core.battle.action.ResetPath;
import org.crforge.core.battle.action.ResetTarget;
import org.crforge.core.battle.action.RollingProjectile;
import org.crforge.core.battle.action.RunActionAtHealth;
import org.crforge.core.battle.action.RunActionOnInstigatorDeath;
import org.crforge.core.battle.action.RunActionOnShooter;
import org.crforge.core.battle.action.RunActionOnTroopDestroyed;
import org.crforge.core.battle.action.RunIfGameObjectExists;
import org.crforge.core.battle.action.RunIfInstigatorMatches;
import org.crforge.core.battle.action.RunIfUnitGroupContains;
import org.crforge.core.battle.action.RunOnAttached;
import org.crforge.core.battle.action.RunOnInstigator;
import org.crforge.core.battle.action.RunOnMatchingUnitsInGroup;
import org.crforge.core.battle.action.RunOnResolvedObjects;
import org.crforge.core.battle.action.Select;
import org.crforge.core.battle.action.SetAttackSequenceIndex;
import org.crforge.core.battle.action.SetCharacterLevel;
import org.crforge.core.battle.action.SetIndicatorOnTarget;
import org.crforge.core.battle.action.SetInstantHit;
import org.crforge.core.battle.action.SetShield;
import org.crforge.core.battle.action.SetVariable;
import org.crforge.core.battle.action.ShapeSelector;
import org.crforge.core.battle.action.SoulDrain;
import org.crforge.core.battle.action.SpawnBuff;
import org.crforge.core.battle.action.SpawnGuard;
import org.crforge.core.battle.action.SpawnResetableAreaEffect;
import org.crforge.core.battle.action.TakeDamage;
import org.crforge.core.battle.action.TargetIndicatorAttack;
import org.crforge.core.battle.action.Taunt;
import org.crforge.core.battle.action.TimerQuest;
import org.crforge.core.battle.action.WaitToActivate;
import org.crforge.core.battle.action.WarpCharacter;
import org.crforge.core.battle.action.WithDuration;
import org.crforge.core.battle.action.WriteInstigatorInfoToContext;
import org.crforge.core.battle.action.WriteResolverResultToContext;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.spawn.SpawnAreaEffect;
import org.crforge.core.battle.spawn.SpawnCharacters;
import org.crforge.core.battle.spawn.SpawnProjectile;
import org.crforge.core.battle.spawn.SpawnRow;
import org.crforge.core.battle.unit.AreaEffectData;
import org.crforge.core.battle.unit.ChampionController;
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

  /** The table of target resolvers. */
  private static final String TARGET_RESOLVERS = "target_resolvers";

  /** The four classes that override none of the runtime's three places. */
  private static final Set<String> INERT =
      Set.of(
          "ActionAnimatorLayer",
          "ActionAddHealthBarPart",
          "ActionVisualActionGroup",
          "ChampionLogicAbilityButtonAnimatorData");

  /** The columns the timer reads besides the shared ones; the bar's own columns only show. */
  private static final Set<String> TIMER_QUEST_READS =
      Set.of(
          "Intervals",
          "IntervalStartAt",
          "StartTimerDelay",
          "MaxResets",
          "UpgradeBarIfTrue",
          "AmountToIncreaseOnUpgradeBarList",
          "OnIntervalReachedAction",
          "OnMaxResetsReachedAction",
          "Type",
          "AffectedByHitSpeed",
          "BarIndicatorName",
          "BarNamesList",
          "ContainerName",
          "ExportNameAtFull",
          "InvertBar",
          "OpponentVisualInterval");

  /** Both sets of columns in one. */
  private static Set<String> union(Set<String> first, Set<String> second) {
    Set<String> both = new HashSet<>(first);
    both.addAll(second);
    return Set.copyOf(both);
  }

  /** The columns each class reads besides the shared ones. */
  private static final Map<String, Set<String>> READS =
      Map.ofEntries(
          // The group's context mode decides the context its parts carry.
          Map.entry("ActionGroup", Set.of("SubActions", "SubActionsDelay", "ContextMode")),
          // The context writer's hit points and shield keys, the level they are read at and the
          // board. The cause's global id and position keys are refused by being left out.
          Map.entry(
              "ActionWriteInstigatorInfoToContext",
              Set.of("HitpointsKey", "ShieldHitpointsKey", "HitpointsLevelIndex", "UseScratch")),
          // The context's two other actions: a value written under a key, and a key's value
          // copied into a variable.
          Map.entry("ActionBlackboardSetInt", Set.of("Key", "Value", "UseScratch")),
          // The projectile speed override's one column.
          Map.entry("ActionOverrideProjectileSpeed", Set.of("SpeedOverride")),
          // The resolver write: its resolver, its board, its default and its result name.
          // UseDefaultValue is loaded and never read by the write.
          Map.entry(
              "ActionWriteResolverResultToContext",
              Set.of(
                  "Resolver",
                  "UseScratchBlackboard",
                  "UseDefaultValue",
                  "DefaultValue",
                  "ResultName")),
          Map.entry(
              "ActionContextToVariable",
              Set.of("BlackboardKey", "OutputVariable", "UseScratch", "DefaultValue")),
          // The path reset reads no column of its own.
          Map.entry("ActionResetPath", Set.of()),
          // The target reset reads no column of its own.
          Map.entry("ActionResetTarget", Set.of()),
          // The hero Barbarian Barrel's reroll. The deploy animation, the health bar hiding and the
          // target indicator's columns are read only by the views; the tags while the spawn delay
          // runs, which the run never raises, are refused by being left out.
          Map.entry(
              "ActionBarbBarrelHeroReRoll",
              Set.of(
                  "OffsetY",
                  "DeployDuration",
                  "SpawnDelay",
                  "ReRollProjectile",
                  "GameTagsToSetWhileOnReRolling",
                  "OnReRollStartAction",
                  "OnReRollEndAction",
                  "OnDeflectedAction",
                  "ReSpawnDeployBaseAnim",
                  "HideHealthbarWhileRolling",
                  "TargetIndicatorBarrelScale",
                  "TargetIndicatorUsesBarrelVersion",
                  "TargetIndicatorOffsetX",
                  "TargetIndicatorOffsetY",
                  "TargetIndicatorFileName",
                  "TargetIndicatorEffectName")),
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
          // The hand-back from a projectile to the entity that launched it.
          Map.entry("ActionRunActionOnShooter", Set.of("ActionToExecute")),
          // The hand-over to what rides the owner.
          Map.entry("ActionRunOnAttached", Set.of("ActionToRun")),
          // The run on what a target resolver finds. The custom position expressions and the
          // ignored ids are refused by being left out.
          Map.entry(
              "ActionRunActionOnResolvedGameObjects",
              Set.of(
                  "Resolver",
                  "Amount",
                  "RunActionsOnSelf",
                  "Action",
                  "ActionToRunOnSelfIfNoObjectsFound")),
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
          // The group checks: the filter and the actions. A walk limited to a range is refused.
          Map.entry(
              "ActionRunActionIfUnitGroupContains",
              Set.of("Action", "ActionIfNoMatch", "ObjectFilter")),
          Map.entry("ActionRunOnMatchingUnitsInGroup", Set.of("ObjectFilter", "ActionToRun")),
          // The timer: its intervals, start, start delay, count, upgrade gate and amounts, the
          // actions, bar type and hit speed switch. Its bar's names, file, inversion and the
          // interval the other player sees only show something.
          Map.entry("ActionTimerQuest", TIMER_QUEST_READS),
          // The hero Mini Pekka's ability level timer: the timer's columns and run, and the
          // ability button's labels and values, which only the button shows.
          Map.entry(
              "ActionMiniPekkaHeroQuest",
              union(
                  TIMER_QUEST_READS,
                  Set.of(
                      "AbilityButtonStartLabel",
                      "AbilityButtonEndLabel",
                      "AbilityButtonTextFieldValues"))),
          // The button state override: the champion whose slot it writes into, the state, the
          // refill and whether its run lasts. Its immediate and highlight switches are stored and
          // read by nothing.
          Map.entry(
              "ActionOverrideAbilityButtonState",
              Set.of(
                  "ChampionCharacterData",
                  "StateToSet",
                  "ResetCharges",
                  "Persistent",
                  "ApplyImmediate",
                  "EnableChampionHighlight")),
          // The ready action: its switch that forces the cooldown, and the two switches that ready
          // the ability and give a charge back, refused when set on. The flag it stores on the
          // character travels with the character's state and is read by nothing.
          Map.entry(
              "ActionReadyChampionAbility",
              Set.of("ReadyAbility", "ForceCooldown", "RecoverCharge", "AbilityCanResetAfter")),
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
                  "AffectedByHitSpeed",
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
          // The instant hit has no columns of its own: its start gate is a shared column.
          Map.entry("ActionSetInstantHit", Set.of()),
          Map.entry("ActionRunActionAtHealth", Set.of("HealthPercentages", "Actions")),
          // The evolved Goblin Drill's relocation. Its hide, reappear and target effects only show
          // something; the spawn deploy time and radius only feed the character spawns.
          Map.entry(
              "ActionGoblinDrillEvoRelocate",
              Set.of(
                  "UseDistanceBasedPositioning",
                  "StepsToMove",
                  "HideTime",
                  "SpawnCharacterOnHide",
                  "SpawnCharacterOnHideCounts",
                  "SpawnCharacterOnReappear",
                  "SpawnCharacterOnReappearCounts",
                  "OnHideEffect",
                  "OnReappearEffect",
                  "TargetEffectList",
                  "HideHpThresholds",
                  "FirstAppearAction",
                  "ReappearActions",
                  "HideActions",
                  "SpawnCharacterDeployTime",
                  "SpawnCharaterRadius")),
          Map.entry("ActionHeal", Set.of("Value", "MaxOverHealPercent")),
          Map.entry("ActionKill", Set.of("OnKillAction")),
          Map.entry(
              "ActionSetAttackSequenceIndex",
              Set.of("AttackIndex", "SetEvenIfCombatDisabled", "ResetRealHitStarted")),
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
          // The pull's clips, frames and effects, the grab point and the capture and idle
          // animation labels and priority only show something: the capture's run reads none of
          // them, only its view does.
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
                  "StretchingClipExportName",
                  "PullEndClipScale",
                  "StretcingClipWidthScale",
                  "GrabPointOffset",
                  "PullEndIdleStartFrame",
                  "PullEndIdleEndFrame",
                  "PullEndGrabStartFrame",
                  "PullEndGrabEndFrame",
                  "PullGrabEffect",
                  "PullCompleteEffect",
                  "CaptureAnimationStartLabel",
                  "CaptureAnimationEndLabel",
                  "CaptureAnimationPriority",
                  "IdleAnimationStartLabel",
                  "IdleAnimationEndLabel")),
          // The health bar's offset only shows something.
          Map.entry("ActionHide", Set.of("Duration", "StopWhenHiderDies", "HealthBarYOffset")),
          Map.entry("ActionRunActionOnInstigatorDeath", Set.of("ActionToRun")),
          // The destroyed-object listener: its action, filter and two match switches. A trigger
          // limit, a range and names to match are read too, and refused as the row is built.
          Map.entry(
              "ActionRunActionOnTroopDestroyed",
              Set.of(
                  "ActionToRun",
                  "TroopFilter",
                  "MatchOnlyOwnSpawnedTroops",
                  "MatchOnlyFromSameOwnerIndex",
                  "MaxTriggerCount",
                  "MaxRange",
                  "MatchName")),
          // The soul's flight: its time and the action as it arrives. Its effects, their flags and
          // loops, the pivot, the start at the character, the lerp, the waits and the wobble only
          // show the flight.
          Map.entry(
              "ActionSoulDrain",
              Set.of(
                  "ConstantFlightDuration",
                  "ActionOnTargetReached",
                  "Effect",
                  "SecondaryEffect",
                  "EffectAbsolutePositionToParent",
                  "SecondaryEffectAbsolutePositionToParent",
                  "LoopEffect",
                  "LoopSecondaryEffect",
                  "PivotName",
                  "CharacterPosAsStart",
                  "UseLerpForSouls",
                  "MinVisualWaitTime",
                  "MaxVisualWaitTime",
                  "MinWobble",
                  "MaxWobble",
                  "FlipPivotOffsetIfTopBottom")),
          // A taunt's end by a stun and its visual effect keep the loader's defaults here: no
          // end by a stun and no effect.
          Map.entry(
              "ActionTaunt",
              Set.of(
                  "ResetsOnDistance",
                  "ResetOnExpiration",
                  "AllowBuildingRetargeting",
                  "FalloffDelay",
                  "ValidDuration",
                  "ValidTargetBuff",
                  "InvalidDuration",
                  "InvalidTargetBuff",
                  "CrownTowerDuration",
                  "CrownTowerBuff",
                  "RemoveBuffOnDeath")),
          Map.entry(
              "ActionLumberjackGhostWaitUntilLooseBuff",
              Set.of(
                  "BuffToConsider",
                  "BuffOverride",
                  "ActionToExecute",
                  "Delay",
                  "PortalTimer",
                  "OnAboutToDieAction")),
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
          // Every column is read by the run; the descent's, which the run refuses, are loaded for
          // it.
          Map.entry(
              "ActionGroundToAir",
              Set.of(
                  "FlyingHeight",
                  "TransitionDuration",
                  "TotalDuration",
                  "ResetPathInAir",
                  "ResetPathWhenBackToGround",
                  "GameTagsToSetOnToAirState",
                  "GameTagsToSetOnOnAirState",
                  "GameTagsToSetOnToGroundState",
                  "ActionOnFlyHeightReached",
                  "ActionOnStartDescending",
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
                  "ResetAvoidanceAtPushback",
                  "ActionOnTargets")),
          // The four switches are loaded but read by nothing the class does, and the push effect
          // and its interval only show something.
          Map.entry(
              "ActionDamagingPushBack",
              Set.of(
                  "PushBackStrength",
                  "PushBackRadius",
                  "ContinuosPushBack",
                  "DistanceProportinalPush",
                  "PushBackDamage",
                  "AffectInvisible",
                  "AffectFlying",
                  "AffectBuildings",
                  "FullPushBackCollisionCheck",
                  "PushToSide",
                  "PushRadiusDirectionalOffset",
                  "OnPushEffect",
                  "OnPushEffectMinInterval",
                  "GameObjectFilter",
                  "PushFilter")),
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
          // The alive timer.
          Map.entry(
              "ActionAeoRunActionAtAliveTimer",
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
          // The guard's push columns and its filter are accepted and read by no part of its run:
          // the area effect the run makes pushes and hits.
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
                  "HitFilter",
                  "SpawnAEO")),
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
          // The warp's columns; the mode is read to refuse every mode but the relative one and
          // InjectedCharacter, and the two effects only show something. The target resolver is
          // read only to refuse it on a relative warp: InjectedCharacter never reads it. The tower
          // offset and the step of untargetability are read only to refuse them.
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
                  "WarpTargetEffect",
                  "Speed",
                  "Acceleration",
                  "OffsetX",
                  "OffsetY",
                  "OffsetToTargetConsideringDirectionToTower",
                  "ForceKeepTargetAfterWarp",
                  "MakeUntargetableForTickAfterWarp",
                  "OnWarpEndAction",
                  "TargetResolver")),
          // The mark: its resolver, its two actions, its two tag masks, its pause, its search
          // delay, its pin and the pinned point's two expressions, asked as the pin holds. The
          // targetter effects, the effect lists and the radii that
          // pick among them only
          // show something; the arrow's stop condition, which only a client arrow reads, is refused
          // as a column nothing reads.
          Map.entry(
              "ActionSetIndicatorOnTarget",
              Set.of(
                  "TargetResolver",
                  "OnPickNewTargetAction",
                  "OnTargetDiedAction",
                  "GameTagsToSetWhileHasNotTarget",
                  "GameTagsToSetWhileHasTarget",
                  "PauseIfInCooldown",
                  "DelayBeforeSearchForNextTarget",
                  "PlayerTargetterEffect",
                  "EnemyTargetterEffect",
                  "RadiusListForEffectSelection",
                  "PlayerTargettedEffectList",
                  "EnemyTargettedEffectList",
                  "PlayerCircleTargetIndicatorList",
                  "EnemyCircleTargetIndicatorList",
                  "PinnedActiveExpression",
                  "PinnedPositionXExpression",
                  "PinnedPositionYExpression")),
          // The hand-over: the mark it reads, the warp it launches, the two deploy actions, the
          // return to the origin and the warp window's three keys; the spell target indicator's
          // file and clip only show something.
          Map.entry(
              "ActionMegaMinionHeroAbility",
              Set.of(
                  "ActionToGetTargetFrom",
                  "ActionToExecute",
                  "HasTargetOnDeployAction",
                  "NoTargetOnDeployAction",
                  "ReturnToOrigin",
                  "ReturnOnTargetDeath",
                  "ReturnDelay",
                  "ReturnWarpAction",
                  "WarpWindowActiveKey",
                  "WarpWindowOriginXKey",
                  "WarpWindowOriginYKey",
                  "SpellTargetIndicatorFilename",
                  "SpellTargetIndicatorClipName")),
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
          Map.entry(
              "ActionRunIfInstigatorMatches",
              Set.of("GameObjectFilter", "MatchName", "ActionToRun", "ActionToRunIfNoMatch")),
          // The adjustment written as an expression in place of the number, and whether it runs on
          // the entity whose holder runs it rather than on its cause.
          Map.entry(
              "ActionSetCharacterLevel",
              Set.of(
                  "RelativeLevelAdjustment",
                  "AbsoluteLevelToSet",
                  "RelativeLevelAdjustmentExpression",
                  "ExecuteOnParent")),
          Map.entry("ActionDealDamage", Set.of("BaseDamageAmount", "BaseDamageType")),
          // A run that counters a hit on its owner: the damage reaction's timers and actions, and
          // the counter's gates and scale.
          Map.entry(
              "ActionCounter",
              Set.of(
                  "Duration",
                  "Cooldown",
                  "TriggerCount",
                  "DamageKey",
                  "SelfAction",
                  "InstigatorAction",
                  "IncludedFilter",
                  "DefenseScalar",
                  "AttackerRangeThreshold",
                  "CounterProjectiles",
                  "CounterFlying",
                  "DeployActive")),
          // A hit of an inline damage on its owner, with an amount added by an expression.
          Map.entry("ActionTakeDamage", Set.of("Damage", "AddedDamage")),
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
          // reach its view object alone. A singleton's second start drops a container: the
          // balloons it starts with, the container list, the offsets and the double container.
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
                  "ContainerAeoList",
                  "OverrideKamikazeDoubleContainer",
                  "TotalBalloons",
                  "OffsetXList",
                  "OffsetYList",
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
          // The Dagger Duchess's charge counter. Its run reads the charges, the recharge and the
          // indices; the counter it shows above the tower (the indicator's file, export name and
          // offsets) and its state name are stored by the row and read by none of the run's
          // start, step or notice.
          Map.entry(
              "ActionBurstAttack",
              Set.of(
                  "MaxChargeCount",
                  "RechargeTime",
                  "RechargeIncrement",
                  "AttackSequenceIndices",
                  "DepletedAttackSequenceIndex",
                  "IndicatorFileName",
                  "IndicatorExportName",
                  "IndicatorOffsetYBlue",
                  "IndicatorOffsetYRed",
                  "AIStateName")),
          // The evolved Musketeer's snipe: its rounds, the box, filter and minimum range its look
          // lists candidates by, the two side clips, the validator's mode and the two actions a
          // shot and the last round schedule (refused when set); the rest only shows something.
          Map.entry(
              "ActionMusketeerSnipe",
              Set.of(
                  "AmmoCount",
                  "LockedTargetSnipeSideClip",
                  "SnipeMaxRange",
                  "SnipeMinRange",
                  "SnipeTargetFilter",
                  "SnipeSideClip",
                  "IgnorePendingDamageTargets",
                  "ActionOnSnipe",
                  "ActionOnOutOfAmmo",
                  "TargetingEffects",
                  "TargetingEffectVerticalOffset",
                  "FinalCrosshairFadeTime",
                  "LoopingEffectWhileHasBullets",
                  "ExtraSpellTargetIndicatorFile",
                  "ExtraSpellTargetIndicator",
                  "ExtraSpellTargetIndicatorXOffset",
                  "ExtraSpellTargetIndicatorYOffset")),
          // The evolved Dart Goblin's dart choice: the controller row it looks for and schedules,
          // and the special dart.
          Map.entry(
              "ActionBlowdartGoblinEvoDartSelect",
              Set.of("ActionToTakeDataFrom", "SpecialProjectile")),
          // Its poison controller. OnStackIncrementAction is read only to be refused.
          Map.entry(
              "ActionBlowdartGoblinEvoController",
              Set.of(
                  "Duration",
                  "CrownTowerDuration",
                  "StackAmountChecks",
                  "MaxStacks",
                  "SpawnInterval",
                  "AeoList",
                  "OnStackIncrementAction")),
          // Its poison damage. OnHitAction is read only to be refused.
          Map.entry(
              "ActionBlowdartGoblinEvoDamage",
              Set.of(
                  "ActionToGetDataFrom",
                  "Duration",
                  "HitSpeed",
                  "CrownTowerDuration",
                  "CrownDamageDamageMultiplier",
                  "DamageList",
                  "OnHitAction")),
          // The evolved Electro Dragon's chain: its hops' projectiles, the search's reach and
          // filters, the repeat rules and the limits on hops, remembered targets, time and delays.
          Map.entry(
              "ActionChainProjectileAttack",
              Set.of(
                  "Projectiles",
                  "ChainRange",
                  "ChainTargets",
                  "MaxChainLength",
                  "RepeatTargets",
                  "DeprioritizeRepeatTargets",
                  "MaximumTargetsToRememberForRepeatChecks",
                  "MaxTime",
                  "ChainDelays")),
          // The net's columns; the prepare action is read only to refuse a row that sets it.
          Map.entry(
              "ActionHunterNetAttack",
              Set.of(
                  "Cooldown",
                  "InitialCooldown",
                  "Range",
                  "MinRange",
                  "ProjectileStartZ",
                  "ActionOnCooldownReady",
                  "ActionOnPrepareShot",
                  "ActionOnShot",
                  "TargetFilter",
                  "Projectile",
                  "TrapCastTime",
                  "ForbidNetShotIfAttackWithin",
                  "ForbidNetShotIfAttackedIn",
                  "ProjectileStartExtraRadius")),
          Map.entry(
              "ActionRunForcedAnimationOnce",
              Set.of(
                  "PlaybackDuration", "CustomStateNumber", "PointToInstigator", "ForcedDuration")),
          // The stop of a forced animation: its perform only tells the character's view, so it
          // changes nothing the simulation reads.
          Map.entry("ActionStopForcedAnimation", Set.of()),
          // The attack chain's columns: the resolver, the links, the actions, the phase buff and
          // the gates. The ones its run refuses are read to refuse a row that sets them.
          Map.entry(
              "ActionAttackChain",
              Set.of(
                  "TargetResolver",
                  "ChainCount",
                  "ResetTargetAfterReach",
                  "PerformAttackOnReach",
                  "ReachRange",
                  "MaxDurationMs",
                  "ChainCompleteIfTrue",
                  "PauseIfAttackSpeedZero",
                  "StopMovementWhenAtTarget",
                  "ForgetTargetRange",
                  "ForgetRangeAlwaysOn",
                  "ForgetAllTargetsIfNoNewOnes",
                  "ForgetOldestTargetIfNoNewOnes",
                  "CanUseDefaultTargetAsFallback",
                  "OnChainBegan",
                  "OnReachTarget",
                  "OnTargetDied",
                  "OnChainComplete",
                  "OnNoTargetFound",
                  "OnFinishedAction",
                  "ChainPhaseBuff")),
          // The dashing attack chain's columns: the resolver, the dashes, the reset and the
          // actions, the ones its run refuses read to refuse a row that sets them.
          Map.entry(
              "ActionDashingAttackChain",
              Set.of(
                  "TargetResolver",
                  "DashCount",
                  "ResetTargetAfterDash",
                  "OnDashChainBegan",
                  "OnDashReachTarget",
                  "OnTargetDied",
                  "OnChainComplete",
                  "OnNoTargetFound")),
          // A run that waits for its owner to be damaged or attacked, then runs its action on the
          // owner at most once a threshold. Its run keeps a countdown, -1 as it starts and 50 less
          // a step down to 0; told its owner was damaged under TriggerOnParentDamaged, with the
          // countdown at 0 or below, it sets the countdown to TimeThreshold and schedules
          // ActionToRun on its owner, the owner its cause. It has no end of its own: only its
          // owner leaving ends it. So while its action only shows something, as the decoy's forced
          // animation does, it changes nothing the simulation reads, and is built as a run that
          // lasts and does nothing. One whose action does more, and one told of an attack on its
          // owner (TriggerOnAttacked), whose callback is not modelled, are refused.
          Map.entry(
              "ActionRunActionOnCallbackWithThreshold",
              Set.of(
                  "ActionToRun", "TimeThreshold", "TriggerOnParentDamaged", "TriggerOnAttacked")),
          // An animator parameter and the value its perform hands the owner's presentation object.
          Map.entry("ActionSetAnimationModifier", Set.of("Parameter", "Value")),
          // The deploy animation it names only shows something, and the card it names only
          // counts toward the statistics.
          Map.entry(
              "ActionClone",
              Set.of("OnClonedAction", "CloneDuration", "SpawnDeployBaseAnim", "CardDataForStats")),
          // The evolved Goblin Barrel's decoy: the projectile it throws, mirrored.
          Map.entry("ActionMirroredExtraSpell", Set.of("Projectile")),
          // A row of projectiles shot across the line of the projectile it runs on. ShooterData is
          // read by no part of the perform; a row that sets it is refused until it is traced.
          Map.entry(
              "ActionCreateParallelProjectiles",
              Set.of("ProjectileType", "ProjectileCount", "ProjectileDistance")),
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
          Map.entry("ActionCannonProjectileSpawn", Set.of("BombProjectile", "BombZOffset")),
          Map.entry(
              "ActionDoPushbackFromInstigator",
              Set.of(
                  "DirectionMode",
                  "PushbackStrength",
                  "PushRadiusDirectionalOffset",
                  "ResetPushbackIfStronger",
                  "DistanceProportinalPush",
                  "IgnorePushbackChecks",
                  "ForcedPushback",
                  "PushbackInvisible",
                  "AttackPushback",
                  "GameTagsToDisallowPush",
                  "ResetAvoidanceOnTarget",
                  "PushbackDelay",
                  "SuccessActionOnInstigator",
                  "FailureActionOnInstigator",
                  "SuccessAction",
                  "FailureAction")));

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
        // time and the source as the buff's parent are the buff branch's, and the start height
        // and the target expressions are the projectile branch's.
        "OffsetX",
        "OffsetY",
        "SpawnTime",
        "InstigatorAsBuffController",
        "StartPositionZOffset",
        "TargetExprX",
        "TargetExprY",
        // The projectile branch's target from the context, its board and the start's move
        // toward that target.
        "TargetFromContextName",
        "UseScratchBlackboard",
        "ProjectileStartOffset");
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
   * Builds a damage type row, its switches and the actions it runs, for one owner.
   *
   * @param name the damage type row's name
   * @param binding the owner's binding, which its actions are built for
   * @return the damage type, or null for no name
   */
  public DamageType damageType(String name, ActionBinding binding) {
    try {
      return name == null ? null : new Build(binding).damageType(TextNode.valueOf(name));
    } catch (MistypedField e) {
      throw new UnsupportedOperationException(name + " " + e.getMessage(), e);
    }
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

  /**
   * A group's context mode by its text: None, Create or Inherit; any other text reads as None, as
   * the game's loader reads it.
   */
  private static Group.ContextMode contextMode(String text) {
    return switch (text) {
      case "Create" -> Group.ContextMode.CREATE;
      case "Inherit" -> Group.ContextMode.INHERIT;
      default -> Group.ContextMode.NONE;
    };
  }

  /** A context key column's key: the hash of its name, or none for an empty name. */
  private static int contextKey(String name) {
    return name.isEmpty() ? WriteInstigatorInfoToContext.NO_KEY : ActionContext.key(name);
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

    /**
     * The action a row names, built; a field of the row whose value is of another shape than its
     * reader reads is refused, naming the row.
     */
    BattleAction action(String name) {
      try {
        return buildRow(name);
      } catch (MistypedField e) {
        throw new UnsupportedOperationException(name + " " + e.getMessage(), e);
      }
    }

    private BattleAction buildRow(String name) {
      BattleAction done = built.get(name);
      if (done != null) {
        return done;
      }
      if (!building.add(name)) {
        // The row is reached again through itself while it is built: a part, an action to run or a
        // next action names it back. The game's rows are one object each, which the rows naming
        // them point at, so the cycle is a loop; the reference answers as the row once it is built.
        return new CycleReference(name, () -> built.get(name));
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
                new Group(
                    shared,
                    actions(f.get("SubActions")),
                    ints(f, "SubActionsDelay"),
                    contextMode(text(f, "ContextMode", "None")));
            case "ActionWriteInstigatorInfoToContext" ->
                new WriteInstigatorInfoToContext(
                    shared,
                    bool(f, "UseScratch"),
                    contextKey(text(f, "HitpointsKey", "")),
                    contextKey(text(f, "ShieldHitpointsKey", "")),
                    integer(f, "HitpointsLevelIndex", -1));
            case "ActionWriteResolverResultToContext" -> writeResolverResult(name, shared, f);
            case "ActionBlackboardSetInt" ->
                new BlackboardSetInt(
                    shared,
                    bool(f, "UseScratch"),
                    ActionContext.key(text(f, "Key", "")),
                    expression(f.get("Value")));
            case "ActionContextToVariable" ->
                new ContextToVariable(
                    shared,
                    bool(f, "UseScratch"),
                    ActionContext.key(text(f, "BlackboardKey", "")),
                    integer(f, "DefaultValue", 0),
                    f.hasNonNull("OutputVariable")
                        ? binding.variableKey(f.get("OutputVariable").asText())
                        : SetVariable.NO_VARIABLE);
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
                new RunOnInstigator(shared, rowName(f.get("ActionToExecute")));
            case "ActionRunActionOnShooter" ->
                new RunActionOnShooter(shared, rowName(f.get("ActionToExecute")));
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
              String group = text(f, "CardGroup", "");
              yield new CardDeployListener(
                  shared,
                  group,
                  group.isEmpty() ? null : records.cardGroup(group),
                  group.isEmpty() ? null : records.cardGroupHeroes(group),
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
            case "ActionOverrideAbilityButtonState" -> {
              String champion = rowName(f.get("ChampionCharacterData"));
              if (champion == null) {
                throw new UnsupportedOperationException(
                    name + " names no champion, which is not modelled");
              }
              String state = text(f, "StateToSet", "");
              yield new OverrideAbilityButtonState(
                  shared,
                  champion,
                  state.isEmpty() ? 0 : ChampionController.stateNamed(state),
                  bool(f, "ResetCharges"),
                  bool(f, "Persistent", true));
            }
            case "ActionReadyChampionAbility" -> {
              // ReadyAbility is on unless the row turns it off.
              if (bool(f, "ReadyAbility", true)) {
                throw new UnsupportedOperationException(
                    name + " sets ReadyAbility, which is not modelled");
              }
              if (bool(f, "RecoverCharge")) {
                throw new UnsupportedOperationException(
                    name + " sets RecoverCharge, which is not modelled");
              }
              yield new ReadyChampionAbility(shared, bool(f, "ForceCooldown"));
            }
            case "ActionRunActionIfUnitGroupContains" ->
                new RunIfUnitGroupContains(
                    shared,
                    objectFilter(name, f),
                    action(f.get("Action")),
                    action(f.get("ActionIfNoMatch")));
            case "ActionRunOnMatchingUnitsInGroup" -> {
              // Built here so that what it runs is checked with the tree; each run builds it
              // afresh for the object it runs on.
              action(f.get("ActionToRun"));
              yield new RunOnMatchingUnitsInGroup(
                  shared, objectFilter(name, f), rowName(f.get("ActionToRun")));
            }
            // The hero Mini Pekka's quest builds the timer's own run: its instance starts and
            // steps as the timer's does.
            case "ActionTimerQuest", "ActionMiniPekkaHeroQuest" -> {
              String barType = text(f, "Type", "Continuous");
              if (!barType.equals("Continuous")) {
                throw new UnsupportedOperationException(
                    name + " sets Type " + barType + ", a segmented bar, which is not modelled");
              }
              if (f.hasNonNull("OnMaxResetsReachedAction")) {
                throw new UnsupportedOperationException(
                    name
                        + " sets OnMaxResetsReachedAction, the action after its last interval,"
                        + " which is not modelled");
              }
              // The game's loader takes a row without the column as one that follows the hit
              // speed.
              boolean affected = bool(f, "AffectedByHitSpeed", true);
              yield new TimerQuest(
                  shared,
                  ints(f, "Intervals"),
                  integer(f, "IntervalStartAt"),
                  integer(f, "StartTimerDelay"),
                  integer(f, "MaxResets"),
                  expression(f.get("UpgradeBarIfTrue")),
                  ints(f, "AmountToIncreaseOnUpgradeBarList"),
                  action(f.get("OnIntervalReachedAction")),
                  affected ? binding.hitSpeed() : IntUnaryOperator.identity());
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
                    intervalRate(f, binding));
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
            case "ActionOverrideProjectileSpeed" ->
                new OverrideProjectileSpeed(shared, expression(f.get("SpeedOverride")));
            case "ActionSetShield" -> new SetShield(shared, integer(f, "ShieldPercent"));
            case "ActionSetInstantHit" -> new SetInstantHit(shared);
            case "ActionRunActionAtHealth" ->
                new RunActionAtHealth(
                    shared, ints(f, "HealthPercentages"), actions(f.get("Actions")));
            case "ActionHeal" ->
                new Heal(shared, expression(f.get("Value")), integer(f, "MaxOverHealPercent"));
            case "ActionKill" -> new Kill(shared, action(f.get("OnKillAction")));
            case "ActionSetAttackSequenceIndex" ->
                new SetAttackSequenceIndex(
                    shared,
                    integer(f, "AttackIndex"),
                    bool(f, "SetEvenIfCombatDisabled"),
                    bool(f, "ResetRealHitStarted"));
            case "ActionTaunt" -> taunt(name, shared, f);
            case "ActionLumberjackGhostWaitUntilLooseBuff" -> lumberjackGhostWait(shared, f);
            case "ActionLaserBall" -> laserBall(name, shared, f);
            case "ActionSpawnGuard" -> spawnGuard(name, shared, f);
            case "ActionBossBanditAbility" -> bossBanditAbility(name, shared, f);
            case "ActionGhostEvoAction" -> ghostEvo(name, shared, f);
            case "ActionGhostEvoSpawnSummon" -> ghostSummon(name, shared, f);
            case "ActionWarpCharacter" -> warpCharacter(name, shared, f);
            case "ActionSetIndicatorOnTarget" -> setIndicatorOnTarget(name, shared, f);
            case "ActionMegaMinionHeroAbility" -> megaMinionHeroAbility(name, shared, f);
            case "ActionTargetIndicatorAttack" -> targetIndicatorAttack(name, shared, f);
            case "ActionRunActionListOnObjectsInShapeWithPrio" -> shapeSelector(name, shared, f);
            case "ActionAirToGround" -> airToGround(name, shared, f);
            case "ActionGroundToAir" -> groundToAir(name, shared, f);
            case "ActionRunOnAttached" -> new RunOnAttached(shared, rowName(f.get("ActionToRun")));
            case "ActionRunActionOnResolvedGameObjects" -> resolvedObjects(name, shared, f);
            case "ActionResetPath" -> new ResetPath(shared);
            case "ActionResetTarget" -> new ResetTarget(shared);
            case "ActionBarbBarrelHeroReRoll" -> barbBarrelReRoll(name, shared, f);
            case "ActionMegaKnightUppercut" -> uppercut(name, shared, f);
            case "ActionKnockback" -> knockback(name, shared, f);
            case "ActionDoPushbackFromInstigator" -> pushbackFromInstigator(name, shared, f);
            case "ActionGoblinDrillEvoRelocate" -> goblinDrillRelocate(name, shared, f);
            case "ActionDamagingPushBack" -> damagingPushBack(name, shared, f);
            case "ActionCannonBarrage" -> cannonBarrage(name, shared, f);
            case "ActionCannonProjectileSpawn" -> cannonProjectileSpawn(name, shared, f);
            case "ActionSpawnResetableAeO" -> resetableAreaEffect(name, shared, f);
            case "ActionFilterByEnemy" -> {
              refuseUnread(name, f, true);
              yield new FilterByEnemy(
                  shared, action(f.get("IsSameTeamAction")), action(f.get("IsEnemyAction")));
            }
            case "ActionAeoRunActionAtAliveTimer" -> aliveTimer(name, shared, f);
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
              String newRow = text(f, "NewCharacterData", "");
              records.unit(newRow);
              yield new ChangeGameObjectData(shared, newRow, bool(f, "ResetTarget"));
            }
            case "ActionExecutionerEvoProjectile" -> executioner(name, shared, f);
            case "ActionRollingProjectile" -> rollingProjectile(name, shared, f);
            case "ActionCaptureCharacter" -> captureCharacter(name, shared, f);
            case "ActionHide" -> {
              // A next action scheduled alongside, the stop gate and the row's tags are the
              // runtime's own, the same for every class: the hide's run carries the tags as every
              // run does, as the Tombstone hero's passive monster's hide sets no physical
              // interaction. A next action that waits for the hide's run is not.
              refuseShared(
                  name,
                  f,
                  "NextActionWait",
                  "ExecuteIfTrue",
                  "ActionPausedIfTrue",
                  "ActionDelay",
                  "UpdatePhase");
              // The loader stores true for an empty column.
              yield new Hide(
                  shared,
                  integer(f, "Duration"),
                  !f.hasNonNull("StopWhenHiderDies") || bool(f, "StopWhenHiderDies"),
                  tagMask("HIDDEN"));
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
            case "ActionRunActionOnTroopDestroyed" -> {
              refuseUnread(name, f, true);
              for (String column : List.of("MaxTriggerCount", "MaxRange", "MatchName")) {
                if (sets(f, column)) {
                  throw new UnsupportedOperationException(
                      name + " listens for destroyed objects with " + column + ", not modelled");
                }
              }
              String troopFilter = text(f, "TroopFilter", "");
              yield new RunActionOnTroopDestroyed(
                  shared,
                  action(f.get("ActionToRun")),
                  troopFilter.isEmpty() ? null : records.filter(troopFilter),
                  bool(f, "MatchOnlyOwnSpawnedTroops"),
                  // The loader stores true for an empty column.
                  !f.hasNonNull("MatchOnlyFromSameOwnerIndex")
                      || bool(f, "MatchOnlyFromSameOwnerIndex"));
            }
            case "ActionSoulDrain" -> {
              // The start gate is the runtime's: asked as the flight starts.
              refuseUnread(name, f, true, true);
              yield new SoulDrain(
                  shared,
                  integer(f, "ConstantFlightDuration"),
                  action(f.get("ActionOnTargetReached")));
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
                    f.hasNonNull("GameObjectFilter")
                        ? records.filter(f.get("GameObjectFilter").asText())
                        : null,
                    globalIds(f.get("MatchName")),
                    action(f.get("ActionToRun")),
                    action(f.get("ActionToRunIfNoMatch")));
            case "ActionSetCharacterLevel" -> setCharacterLevel(name, shared, f);
            case "ActionMirroredExtraSpell" ->
                new MirroredExtraSpell(shared, text(f, "Projectile", ""));
            case "ActionCreateParallelProjectiles" -> {
              String projectile = text(f, "ProjectileType", "");
              yield new CreateParallelProjectiles(
                  shared,
                  projectile.isEmpty() ? null : projectile,
                  integer(f, "ProjectileCount"),
                  integer(f, "ProjectileDistance"));
            }
            case "ActionCounter" -> counter(name, shared, f);
            case "ActionTakeDamage" ->
                new TakeDamage(
                    shared, takenDamage(name, f.get("Damage")), expression(f.get("AddedDamage")));
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
                switch (text(f, "SpawnType", "")) {
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
            case "ActionRunForcedAnimationOnce", "ActionStopForcedAnimation" ->
                new InertAction(shared);
            case "ActionAttackChain" -> attackChain(name, shared, f);
            case "ActionDashingAttackChain" -> dashingAttackChain(name, shared, f);
            case "ActionRunActionOnCallbackWithThreshold" -> {
              if (bool(f, "TriggerOnAttacked")) {
                throw new UnsupportedOperationException(
                    name + " sets TriggerOnAttacked, whose callback is not modelled");
              }
              BattleAction run = action(f.get("ActionToRun"));
              // Only an action with no run of its own, which shows something and is done.
              if (run != null && (!(run instanceof InertAction inert) || inert.isLasting())) {
                throw new UnsupportedOperationException(
                    name
                        + " sets ActionToRun to "
                        + run.name()
                        + ", whose run on a callback is not modelled");
              }
              yield new InertAction(shared, true);
            }
            // Its perform reaches only a character's presentation object and the animator behind
            // it, handing it the parameter's value; no logic state of the battle.
            case "ActionSetAnimationModifier" -> new InertAction(shared);
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
            case "ActionBurstAttack" ->
                new BurstAttack(
                    shared,
                    BurstAttack.Columns.builder()
                        .maxChargeCount(integer(f, "MaxChargeCount"))
                        .rechargeTimeMs(integer(f, "RechargeTime"))
                        .rechargeIncrement(integer(f, "RechargeIncrement"))
                        .attackSequenceIndices(ints(f, "AttackSequenceIndices"))
                        .depletedAttackSequenceIndex(integer(f, "DepletedAttackSequenceIndex"))
                        .noAttackTag(tagMask("NO_ATTACK"))
                        .build());
            case "ActionMusketeerSnipe" ->
                new MusketeerSnipe(
                    shared,
                    MusketeerSnipe.Columns.builder()
                        .ammoCount(integer(f, "AmmoCount"))
                        .snipeSideClip(integer(f, "SnipeSideClip"))
                        .lockedTargetSnipeSideClip(integer(f, "LockedTargetSnipeSideClip"))
                        .snipeMaxRange(integer(f, "SnipeMaxRange"))
                        .snipeMinRange(integer(f, "SnipeMinRange"))
                        .snipeTargetFilter(records.filter(text(f, "SnipeTargetFilter", "")))
                        .ignorePendingDamageTargets(bool(f, "IgnorePendingDamageTargets", true))
                        .actionOnSnipe(action(f.get("ActionOnSnipe")))
                        .actionOnOutOfAmmo(action(f.get("ActionOnOutOfAmmo")))
                        .build());
            case "ActionBlowdartGoblinEvoDartSelect" -> {
              BattleAction controller = action(f.get("ActionToTakeDataFrom"));
              String special = text(f, "SpecialProjectile", "");
              if (!(controller instanceof BlowdartController) || special.isEmpty()) {
                throw new UnsupportedOperationException(
                    name
                        + " picks darts without a poison controller or a special dart, which is"
                        + " not modelled");
              }
              records.projectile(special);
              yield new BlowdartDartSelect(shared, controller, special);
            }
            case "ActionBlowdartGoblinEvoController" -> {
              if (action(f.get("OnStackIncrementAction")) != null) {
                throw new UnsupportedOperationException(
                    name + " sets OnStackIncrementAction, which is not modelled");
              }
              List<Integer> checks = ints(f, "StackAmountChecks");
              List<String> areas = new ArrayList<>();
              f.path("AeoList").forEach(area -> areas.add(area.asText()));
              int maxStacks = integer(f, "MaxStacks");
              if (maxStacks < 1 || checks.size() < maxStacks || areas.isEmpty()) {
                throw new UnsupportedOperationException(
                    name
                        + " has fewer stack counts than stacks, or no area, which is not"
                        + " modelled");
              }
              for (String area : areas) {
                records.areaEffect(area);
              }
              yield new BlowdartController(
                  shared,
                  BlowdartController.Columns.builder()
                      .durationMs(f.has("Duration") ? integer(f, "Duration") : 1000)
                      .crownTowerDurationMs(
                          f.has("CrownTowerDuration") ? integer(f, "CrownTowerDuration") : -1)
                      .stackAmountChecks(List.copyOf(checks))
                      .maxStacks(maxStacks)
                      .spawnIntervalMs(integer(f, "SpawnInterval"))
                      .aeoList(List.copyOf(areas))
                      .build());
            }
            case "ActionBlowdartGoblinEvoDamage" -> {
              if (action(f.get("OnHitAction")) != null) {
                throw new UnsupportedOperationException(
                    name + " sets OnHitAction, which is not modelled");
              }
              String controller = rowName(f.get("ActionToGetDataFrom"));
              if (controller == null
                  || !tables
                      .action(controller)
                      .classType()
                      .equals("ActionBlowdartGoblinEvoController")
                  || integer(f, "HitSpeed") < 1) {
                throw new UnsupportedOperationException(
                    name
                        + " deals poison without a poison controller or a hit speed, which is not"
                        + " modelled");
              }
              yield new BlowdartDamage(
                  shared,
                  BlowdartDamage.Columns.builder()
                      .controller(controller)
                      .durationMs(integer(f, "Duration"))
                      .hitSpeedMs(integer(f, "HitSpeed"))
                      .crownTowerDurationMs(
                          f.has("CrownTowerDuration") ? integer(f, "CrownTowerDuration") : -1)
                      .crownDamageMultiplier(integer(f, "CrownDamageDamageMultiplier"))
                      .damageList(List.copyOf(ints(f, "DamageList")))
                      .build());
            }
            case "ActionChainProjectileAttack" -> chainProjectileAttack(name, shared, f);
            case "ActionHunterNetAttack" -> hunterNetAttack(name, shared, f);
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
                        .spawnData(text(f, "SpawnData", ""))
                        .spawnNumber(integer(f, "SpawnNumber"))
                        .spawnOffset(integer(f, "SpawnOffset"))
                        .singleDeployOffsetAngle(integer(f, "SingleDeployOffsetAngle"))
                        .objectFilter(records.filter(text(f, "ObjectFilter", "")))
                        .onSpawnAction(action(f.get("OnSpawnAction")))
                        .onStartSpawningAction(action(f.get("OnStartSpawningAction")))
                        .onStartWaitingAction(action(f.get("OnStartWaitingAction")))
                        .toggleEffectTag(
                            f.has("ToggleEffectTag") ? f.get("ToggleEffectTag").asText() : null)
                        .build());
            case "ActionSkeletonBarrelPopBalloon" -> {
              // The containers are read only by a singleton's second start; a row that is no
              // singleton never re-triggers its run, so its containers never drop.
              List<String> containers = new ArrayList<>();
              f.path("ContainerAeoList").forEach(value -> containers.add(value.asText()));
              String doubleContainer = text(f, "OverrideKamikazeDoubleContainer", "");
              yield new PopBalloons(
                  shared,
                  containers,
                  doubleContainer.isEmpty() ? null : doubleContainer,
                  integer(f, "TotalBalloons"),
                  ints(f, "OffsetXList"),
                  ints(f, "OffsetYList"));
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
              name + " sets " + column + " on a " + text(f, "ClassType", "") + ", not modelled");
        }
      }
    }

    /**
     * The rate an interval's steps follow, in percent, read afresh on each step: for a row that
     * follows the hit speed, what the owner's buffs make of 100; else, for one that follows the
     * spawn speed, the owner's spawn rate; else the usual 100. The first of the two the row sets
     * decides, the hit speed before the spawn speed.
     */
    private IntSupplier intervalRate(JsonNode f, ActionBinding binding) {
      if (bool(f, "AffectedByHitSpeed")) {
        IntUnaryOperator hitSpeed = binding.hitSpeed();
        return () -> hitSpeed.applyAsInt(Interval.USUAL_RATE);
      }
      return bool(f, "AffectedBySpawnSpeed") ? binding.spawnRate() : () -> Interval.USUAL_RATE;
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
      if (nextChainReturns(row.name())) {
        // The loader clears a NextAction chain that leads back to its own row, whatever the rows
        // between; in which row of a longer chain it is cleared depends on their load order.
        throw new UnsupportedOperationException(
            row.name()
                + " chains NextAction back to itself, which the loader clears, not modelled");
      }
      boolean bareEffect =
          row.classType().equals("ActionPlayEffect")
              && !bool(f, "Singleton", false)
              && text(f, "GameTagsToSet", "").isEmpty();
      return ActionRow.builder()
          .name(row.name())
          .phase(updatePhase(f))
          .delayMs(integer(f, "ActionDelay"))
          .singleton(bool(f, "Singleton", false))
          .nextAction(action(f.get("NextAction")))
          .nextActionWait(bool(f, "NextActionWait", false))
          .tags(f.has("GameTagsToSet") ? tagMask(f.get("GameTagsToSet").asText()) : 0)
          .executeIf(expression(f.get("ExecuteIfTrue")))
          .forceStopIf(bareEffect ? null : expression(f.get("ForceStopIfTrue")))
          .pausedIf(expression(f.get("ActionPausedIfTrue")))
          .abortIfInstigatorDies(bool(f, "AbortIfInstigatorDies", true))
          .build();
    }

    /**
     * Whether the row's NextAction chain, followed from row to row by name, leads back to the row:
     * the chain the loader clears. Only NextAction is followed, as the loader follows it; a chain
     * that loops without the row is not.
     */
    private boolean nextChainReturns(String name) {
      Set<String> seen = new HashSet<>();
      String next = rowName(tables.action(name).fields().get("NextAction"));
      while (next != null && seen.add(next)) {
        if (next.equals(name)) {
          return true;
        }
        next = rowName(tables.action(next).fields().get("NextAction"));
      }
      return false;
    }

    /**
     * A chain projectile attack's columns, each with the default the game's loader gives a column
     * the row leaves out. A row without a projectile or a filter is refused: its hops would index
     * an empty list.
     */
    private ChainProjectileAttack chainProjectileAttack(String name, ActionRow shared, JsonNode f) {
      List<String> projectiles = new ArrayList<>();
      f.path("Projectiles").forEach(value -> projectiles.add(value.asText()));
      List<GameObjectFilter> filters = new ArrayList<>();
      f.path("ChainTargets").forEach(value -> filters.add(records.filter(value.asText())));
      if (projectiles.isEmpty() || filters.isEmpty()) {
        throw new UnsupportedOperationException(
            name + " chains with no projectile or no filter, which is not modelled");
      }
      return new ChainProjectileAttack(
          shared,
          ChainProjectileAttack.Columns.builder()
              .projectiles(projectiles)
              .chainRange(integer(f, "ChainRange"))
              .chainTargets(filters)
              .maxChainLength(integer(f, "MaxChainLength", -1))
              .repeatTargets(bool(f, "RepeatTargets", true))
              .deprioritizeRepeatTargets(bool(f, "DeprioritizeRepeatTargets", false))
              .maxRemembered(integer(f, "MaximumTargetsToRememberForRepeatChecks", -1))
              .maxTimeMs(integer(f, "MaxTime", -1))
              .chainDelaysMs(ints(f, "ChainDelays"))
              .build());
    }

    /**
     * A net attack's columns, each with the default the game's loader gives a column the row leaves
     * out. A row with a MinRange of 1 or more, whose finder would pass over close objects, or with
     * an ActionOnPrepareShot is refused: no shipped row sets either. So is one without a filter or
     * a projectile.
     */
    private HunterNetAttack hunterNetAttack(String name, ActionRow shared, JsonNode f) {
      if (integer(f, "MinRange", 100) >= 1) {
        throw new UnsupportedOperationException(
            name + " is a net attack with a MinRange of 1 or more, which is not modelled");
      }
      if (sets(f, "ActionOnPrepareShot")) {
        throw new UnsupportedOperationException(
            name + " is a net attack that sets ActionOnPrepareShot, which is not modelled");
      }
      if (text(f, "TargetFilter", "").isEmpty() || text(f, "Projectile", "").isEmpty()) {
        throw new UnsupportedOperationException(
            name + " is a net attack without a filter or a projectile, which is not modelled");
      }
      return new HunterNetAttack(
          shared,
          HunterNetAttack.Columns.builder()
              .cooldownMs(integer(f, "Cooldown", 4000))
              .initialCooldownMs(integer(f, "InitialCooldown", 0))
              .range(integer(f, "Range", 2000))
              .projectile(records.projectile(f.get("Projectile").asText()).name())
              .projectileStartZ(integer(f, "ProjectileStartZ", 1000))
              .projectileStartExtraRadius(integer(f, "ProjectileStartExtraRadius", 0))
              .targetFilter(records.filter(f.get("TargetFilter").asText()))
              .trapCastTimeMs(integer(f, "TrapCastTime", 0))
              .forbidIfAttackWithinMs(integer(f, "ForbidNetShotIfAttackWithin", 200))
              .forbidIfAttackedInMs(integer(f, "ForbidNetShotIfAttackedIn", 200))
              .onCooldownReady(action(f.get("ActionOnCooldownReady")))
              .onShot(action(f.get("ActionOnShot")))
              .build());
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
              // The shipped row names a filter row the tables do not ship; the loader reads it as
              // no filter.
              .targetFilter(records.filterIfHeld(f.get("TargetFilter").asText()))
              .distanceToGetTargets(integer(f, "DistanceToGetTargets"))
              .distanceToBuff(integer(f, "DistanceToBuff"))
              .distanceToUnbuff(integer(f, "DistanceToUnbuff"))
              // The loader's default uses the ability.
              .useAbility(bool(f, "UseAbility", true))
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
              .startCookingDelayMs(integer(f, "StartCookingDelay", 0))
              .contributionNeeded(integer(f, "ContributionNeeded", 1000))
              .contributionBaseline(integer(f, "ContributionBaseline", 22))
              .contributionIdle(integer(f, "ContributionIdle", 22))
              .contributionAttacking(integer(f, "ContributionAttacking", 11))
              .contributionDestroyed(integer(f, "ContributionDestroyed", 8))
              .targetFilter(
                  f.hasNonNull("TargetFilter")
                      ? records.filter(f.get("TargetFilter").asText())
                      : null)
              .minCurrentHpThreshold(integer(f, "MinCurrentHpThreshold", 0))
              .minCurrentHpPercentage(integer(f, "MinCurrentHpPercentageThreshold", 0))
              .minMaxHpThreshold(integer(f, "MinMaxHpThreshold", 0))
              .deprioritizeBuffed(bool(f, "DeprioritizeBuffed", false))
              .buffProjectile(records.projectile(f.get("BuffProjectile").asText()))
              .pancakeThrowDelayMs(integer(f, "PancakeThrowDelay", 300))
              .pancakeStartOffset(integer(f, "PancakeStartOffset", 0))
              .pancakeThrowDelayThresholdMs(integer(f, "PancakeThrowDelayTreshold", 200))
              .waitPancakeThrowAfterAttackMs(integer(f, "WaitPancakeThrowAfterAttackTime", 200))
              .finishWhenBothTowersLost(bool(f, "FinishWhenBothTowersLost", true))
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
      List<Integer> values = ints(f, "DamageMultiplierPerUnitValues");
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
     * A buff spawn row's columns: the buff, its time, whether the owner is the source and whether
     * the source is the buff's parent, nothing more. A buff written inline is the buff row of its
     * Name. A row that sets any other spawn column, names a buff the battle does not model or one
     * its parent controls without taking the source as its parent, or gives it a time below 1 is
     * refused.
     */
    private SpawnBuff spawnBuff(String name, ActionRow shared, JsonNode f) {
      f.fieldNames()
          .forEachRemaining(
              column -> {
                if (spawnColumns().contains(column)
                    && !Set.of(
                            "SpawnData",
                            "SpawnType",
                            "SpawnTime",
                            "ParentGOAsSource",
                            "InstigatorAsBuffController")
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
      boolean sourceAsParent = bool(f, "InstigatorAsBuffController");
      // Without InstigatorAsBuffController a buff its parent controls takes the owner as its
      // parent, which no reference holds.
      if ((records.buff(buff).controlledByParent() && !sourceAsParent)
          || integer(f, "SpawnTime") < 1) {
        throw new UnsupportedOperationException(
            name + " spawns a buff its parent controls or for no time, which is not modelled");
      }
      return new SpawnBuff(
          shared, buff, integer(f, "SpawnTime"), bool(f, "ParentGOAsSource"), sourceAsParent);
    }

    /**
     * A shape selector's columns: its circle, its filter, how it scores, its delays and their
     * actions, whether it picks each object once, which by default it does, whether it waits for a
     * target, its pause tags, its actions on the owner by the pick's side and whatever the side,
     * whether those take the owner as their cause, and its finishing action. The circle's
     * CheckOrigin narrows what its query found to the objects whose centre lies within it. A row
     * that waits at most a while, scores by maximum hit points, has fewer actions than delays, is a
     * singleton or chains a next action is refused; so is one without a filter, or whose shape is
     * not a circle.
     */
    private ShapeSelector shapeSelector(String name, ActionRow shared, JsonNode f) {
      for (String column : List.of("MaxWaitTimeForTarget", "Singleton", "NextAction")) {
        if (sets(f, column)) {
          throw new UnsupportedOperationException(
              name + " is a shape selector that sets " + column + ", which is not modelled");
        }
      }
      if (text(f, "TargetFilter", "").isEmpty()) {
        throw new UnsupportedOperationException(
            name + " is a shape selector without a filter, which is not modelled");
      }
      String mode = text(f, "TargetSelectionMode", "HighestCurrentHpIncludeShields");
      int selection =
          switch (mode) {
            case "HighestCurrentHp" -> ShapeSelector.HIGHEST_CURRENT_HP;
            case "HighestCurrentHpIncludeShields" ->
                ShapeSelector.HIGHEST_CURRENT_HP_INCLUDE_SHIELDS;
            case "Closest" -> ShapeSelector.CLOSEST;
            default ->
                throw new UnsupportedOperationException(
                    name
                        + " is a shape selector that scores by "
                        + mode
                        + ", which is not modelled");
          };
      List<Integer> delays = ints(f, "Delays");
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
              .oncePerTarget(bool(f, "OncePerTarget", true))
              .targetSelectionMode(selection)
              .targetFilter(records.filter(f.get("TargetFilter").asText()))
              .shapeRadius(records.circleRadius(text(f, "Shape", "")))
              .checkOrigin(records.circleChecksOrigin(text(f, "Shape", "")))
              .delaysMs(delays)
              .actions(actions)
              .waitForTarget(bool(f, "WaitForTarget", false))
              .pauseTags(f.has("PauseTags") ? tagMask(f.get("PauseTags").asText()) : 0)
              .actionOnSelfLeft(rowName(f.get("ActionOnSelfWhenTriggeredLeft")))
              .actionOnSelfRight(rowName(f.get("ActionOnSelfWhenTriggeredRight")))
              .actionOnSelf(rowName(f.get("ActionOnSelfWhenTriggered")))
              .parentAsInstigatorForSelfActions(bool(f, "ParentAsInstigatorForSelfActions"))
              .onFinishedAction(rowName(f.get("OnFinishedAction")))
              .build());
    }

    /**
     * An air-to-ground row's columns, a column it leaves out taking the loader's default: a
     * transition of 200, a whole of 1000, the path reset at the end, no ground tag on idle and no
     * action once on the ground. A row with a landing action or a landing end action, a path reset
     * at landing or a next action is refused. Its tags are carried by its run as any row's are.
     */
    private AirToGround airToGround(String name, ActionRow shared, JsonNode f) {
      for (String column :
          List.of("ActionOnLanding", "ActionOnLandingEnd", "ResetPathAtLanding", "NextAction")) {
        if (sets(f, column)) {
          throw new UnsupportedOperationException(
              name + ", an air-to-ground row, sets " + column + ", which is not modelled");
        }
      }
      return new AirToGround(
          shared,
          integer(f, "TransitionDuration", 200),
          integer(f, "TotalDuration", 1000),
          bool(f, "AllowIsGroundTagOnIdle", false),
          bool(f, "ResetPathAtEnd", true),
          action(f.get("ActionOnGround")));
    }

    /**
     * A ground-to-air row's columns, a column it leaves out taking the loader's default: a flying
     * height of 0, a transition of 200, a whole of 1000, no path reset, no tags and no actions. A
     * row with a next action is refused. Its own tags are carried by its run as any row's are.
     */
    private GroundToAir groundToAir(String name, ActionRow shared, JsonNode f) {
      if (sets(f, "NextAction")) {
        throw new UnsupportedOperationException(
            name + ", a ground-to-air row, sets NextAction, which is not modelled");
      }
      return new GroundToAir(
          shared,
          integer(f, "FlyingHeight", 0),
          integer(f, "TransitionDuration", 200),
          integer(f, "TotalDuration", 1000),
          bool(f, "ResetPathInAir", false),
          bool(f, "ResetPathWhenBackToGround", false),
          tags(f, "GameTagsToSetOnToAirState"),
          tags(f, "GameTagsToSetOnOnAirState"),
          tags(f, "GameTagsToSetOnToGroundState"),
          action(f.get("ActionOnFlyHeightReached")),
          action(f.get("ActionOnStartDescending")),
          action(f.get("ActionOnGround")));
    }

    private BarbBarrelHeroReRoll barbBarrelReRoll(String name, ActionRow shared, JsonNode f) {
      if (!f.hasNonNull("ReRollProjectile")) {
        throw new UnsupportedOperationException(
            name + ", a reroll row, names no ReRollProjectile, which is not modelled");
      }
      return new BarbBarrelHeroReRoll(
          shared,
          BarbBarrelHeroReRoll.Columns.builder()
              .offsetY(integer(f, "OffsetY", 0))
              .deployDurationMs(integer(f, "DeployDuration", 0))
              .spawnDelayMs(integer(f, "SpawnDelay", 0))
              .reRollProjectile(f.get("ReRollProjectile").asText())
              .rollingTags(tags(f, "GameTagsToSetWhileOnReRolling"))
              .onReRollStartAction(action(f.get("OnReRollStartAction")))
              .onReRollEndAction(action(f.get("OnReRollEndAction")))
              .onDeflectedAction(action(f.get("OnDeflectedAction")))
              .build());
    }

    /** The mask of a tag list column, or 0 when the row leaves it out. */
    private long tags(JsonNode f, String column) {
      return f.has(column) ? tagMask(f.get(column).asText()) : 0;
    }

    /**
     * Refuses a row that sets one of the shared columns the runs of the evolved Mega Knight's and
     * Baby Dragon's classes do not read: tags, a next action, the three gates, a delay and a phase,
     * and, unless the class reads it, Singleton.
     */
    private void refuseUnread(String name, JsonNode f, boolean singleton) {
      refuseUnread(name, f, singleton, false);
    }

    /**
     * Refuses a row that sets one of the shared columns its class's run does not read, as {@link
     * #refuseUnread(String, JsonNode, boolean)} does; with {@code startGated} the start gate
     * (ExecuteIfTrue) is left to the runtime, which asks it as the action starts, before the class
     * does anything, the same for every class it starts.
     */
    private void refuseUnread(String name, JsonNode f, boolean singleton, boolean startGated) {
      List<String> columns =
          new ArrayList<>(
              List.of(
                  "GameTagsToSet",
                  "NextAction",
                  "ActionPausedIfTrue",
                  "ForceStopIfTrue",
                  "ActionDelay",
                  "UpdatePhase"));
      if (!startGated) {
        columns.add("ExecuteIfTrue");
      }
      if (singleton) {
        columns.add("Singleton");
      }
      for (String column : columns) {
        if (sets(f, column)) {
          throw new UnsupportedOperationException(
              name
                  + ", an "
                  + text(f, "ClassType", "")
                  + ", sets "
                  + column
                  + ", which is not modelled");
        }
      }
    }

    /**
     * An uppercut's columns, a column it leaves out taking the loader's default: an offset of 50, a
     * longer pushback kept, no proportional push, the follow-up dash, a delay of 1000, the target's
     * avoidance blend cleared after the push and the action on the target only after a push that
     * went through. A row that pushes through the request's gates or dashes after the delay is
     * refused.
     */
    private MegaKnightUppercut uppercut(String name, ActionRow shared, JsonNode f) {
      // The start gate is the runtime's: asked as the uppercut starts, before its hold and target.
      refuseUnread(name, f, true, true);
      if (!bool(f, "IgnorePushbackChecks", false)) {
        throw new UnsupportedOperationException(
            name + " pushes through the pushback request's gates, which is not modelled");
      }
      if (bool(f, "DoFollowUpJump", true)) {
        throw new UnsupportedOperationException(
            name + " dashes after its delay, which is not modelled");
      }
      return new MegaKnightUppercut(
          shared,
          integer(f, "PushBackStrength"),
          integer(f, "PushRadiusDirectionalOffset", 50),
          bool(f, "DistanceProportinalPush", false),
          bool(f, "ResetPushbackIfStronger", true),
          integer(f, "DashFollowUpDelay", 1000),
          bool(f, "ResetAvoidanceAtPushback", true),
          bool(f, "OnlyRunActionOnPushback", true),
          action(f.get("ActionOnTargets")));
    }

    /**
     * A carried push's columns. A row that sets tags, a singleton, a next action, the run or pause
     * gate or a phase of its own, or that leaves out either filter, is refused; its delay and stop
     * gate are the runtime's.
     */
    private DamagingPushBack damagingPushBack(String name, ActionRow shared, JsonNode f) {
      refuseShared(
          name,
          f,
          "GameTagsToSet",
          "Singleton",
          "NextAction",
          "ExecuteIfTrue",
          "ActionPausedIfTrue",
          "UpdatePhase");
      for (String column : List.of("GameObjectFilter", "PushFilter")) {
        if (f.path(column).asText("").isEmpty()) {
          throw new UnsupportedOperationException(
              name + " is a carried push without its " + column + ", which is not modelled");
        }
      }
      return new DamagingPushBack(
          shared,
          DamagingPushBack.Columns.builder()
              .pushBackStrength(integer(f, "PushBackStrength"))
              .pushBackRadius(integer(f, "PushBackRadius"))
              .continuousPushBack(bool(f, "ContinuosPushBack"))
              .distanceProportionalPush(bool(f, "DistanceProportinalPush"))
              .pushBackDamage(integer(f, "PushBackDamage"))
              .pushToSide(bool(f, "PushToSide"))
              .pushRadiusDirectionalOffset(integer(f, "PushRadiusDirectionalOffset"))
              .gameObjectFilter(records.filter(f.get("GameObjectFilter").asText()))
              .pushFilter(records.filter(f.get("PushFilter").asText()))
              .build());
    }

    /**
     * A push from its cause's columns, each the loader's default when left out: a strength of 1000,
     * a directional offset of 50, forced, resetting only for a stronger push and resetting
     * avoidance, the rest off. Refused: no delay, a mode other than toward the horizontal centre
     * from the cause, the push that skips the request's checks and a failure action on the owner.
     */
    private DoPushbackFromInstigator pushbackFromInstigator(
        String name, ActionRow shared, JsonNode f) {
      int delay = integer(f, "PushbackDelay");
      if (delay <= 0) {
        throw new UnsupportedOperationException(
            name + ", a push from its cause, sets no PushbackDelay, which is not modelled");
      }
      String mode = text(f, "DirectionMode", "");
      if (!mode.equals("ToHorizontalCenterFromInstigator")) {
        throw new UnsupportedOperationException(
            name
                + ", a push from its cause, sets DirectionMode "
                + mode
                + ", which is not modelled");
      }
      if (bool(f, "IgnorePushbackChecks")) {
        throw new UnsupportedOperationException(
            name + ", a push from its cause, sets IgnorePushbackChecks, which is not modelled");
      }
      if (rowName(f.get("FailureAction")) != null) {
        throw new UnsupportedOperationException(
            name + ", a push from its cause, sets FailureAction, which is not modelled");
      }
      return new DoPushbackFromInstigator(
          shared,
          DoPushbackFromInstigator.Columns.builder()
              .delayMs(delay)
              .strength(f.hasNonNull("PushbackStrength") ? integer(f, "PushbackStrength") : 1000)
              .directionalOffset(
                  f.hasNonNull("PushRadiusDirectionalOffset")
                      ? integer(f, "PushRadiusDirectionalOffset")
                      : 50)
              .disallowTags(
                  f.has("GameTagsToDisallowPush")
                      ? tagMask(f.get("GameTagsToDisallowPush").asText())
                      : 0)
              .forced(bool(f, "ForcedPushback", true))
              .attack(bool(f, "AttackPushback"))
              .proportional(bool(f, "DistanceProportinalPush"))
              .resetIfStronger(bool(f, "ResetPushbackIfStronger", true))
              .invisible(bool(f, "PushbackInvisible"))
              .resetAvoidance(bool(f, "ResetAvoidanceOnTarget", true))
              .successOnInstigator(rowName(f.get("SuccessActionOnInstigator")))
              .failureOnInstigator(rowName(f.get("FailureActionOnInstigator")))
              .successAction(rowName(f.get("SuccessAction")))
              .build());
    }

    /**
     * A knock's columns: its height, its duration, its landing action and whether that takes the
     * knock's cause. A row with the no-collision tag is refused.
     */
    private Knockback knockback(String name, ActionRow shared, JsonNode f) {
      refuseUnread(name, f, true);
      if (sets(f, "ApplyNoCollisionTag")) {
        throw new UnsupportedOperationException(
            name + ", a knock, sets ApplyNoCollisionTag, which is not modelled");
      }
      // The landing action is built as it is scheduled, for the unit that lands.
      String landing = rowName(f.get("ActionOnLanding"));
      return new Knockback(
          shared,
          integer(f, "Height"),
          integer(f, "Duration"),
          landing,
          bool(f, "PassInstigatorToLandingAction", false));
    }

    /**
     * The evolved Goblin Drill's relocation, StepsToMove taking the loader's 5 when left out.
     * Refused: the shared columns its run does not read, a character spawned on a hide or a
     * reappearance, and reappear actions, which no shipped row sets.
     */
    private GoblinDrillEvoRelocate goblinDrillRelocate(String name, ActionRow shared, JsonNode f) {
      refuseUnread(name, f, true);
      for (String column :
          List.of(
              "SpawnCharacterOnHide",
              "SpawnCharacterOnHideCounts",
              "SpawnCharacterOnReappear",
              "SpawnCharacterOnReappearCounts",
              "ReappearActions")) {
        if (sets(f, column)) {
          throw new UnsupportedOperationException(
              name + ", a relocation, sets " + column + ", which is not modelled");
        }
      }
      return new GoblinDrillEvoRelocate(
          shared,
          GoblinDrillEvoRelocate.Columns.builder()
              .distanceBased(bool(f, "UseDistanceBasedPositioning"))
              .stepsToMove(f.hasNonNull("StepsToMove") ? integer(f, "StepsToMove") : 5)
              .hideTimeMs(integer(f, "HideTime"))
              .hideHpThresholds(ints(f, "HideHpThresholds"))
              .firstAppearAction(action(f.get("FirstAppearAction")))
              .hideActions(actions(f.get("HideActions")))
              .reappearActions(List.of())
              .build());
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
     * A capture's columns. Its four actions are built here, so a row the battle cannot take is
     * refused as the capture is built, and each is built again on what it runs on as it is
     * scheduled. HeightModifier keeps the loader's default of -15000 when the row leaves it unset.
     * Refused: a row without a filter, and the shared columns its run does not read.
     */
    private CaptureCharacter captureCharacter(String name, ActionRow shared, JsonNode f) {
      refuseUnread(name, f, true);
      if (!sets(f, "TargetFilter")) {
        throw new UnsupportedOperationException(
            name + " captures without TargetFilter, which is not modelled");
      }
      BattleAction hide = action(f.get("HideAction"));
      BattleAction first = action(f.get("OnFirstCaptureAction"));
      BattleAction onCapture = action(f.get("OnCaptureAction"));
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
              .damagePerHit(integer(f, "DamagePerHit"))
              .dragDelayMs(integer(f, "DragDelay"))
              .timePausedWhenGrabbingMs(integer(f, "TimePausedWhenGrabbing"))
              .pullCenterOffsetX(integer(f, "PullCenterOffsetX"))
              .pullCenterOffsetY(integer(f, "PullCenterOffsetY"))
              .captureCooldownMs(integer(f, "CaptureCooldown"))
              .heightModifier(
                  sets(f, "HeightModifier")
                      ? integer(f, "HeightModifier")
                      : CaptureCharacter.DEFAULT_HEIGHT_MODIFIER)
              .heightModifierCap(integer(f, "HeightModifierCap"))
              .targetFilter(records.filter(f.get("TargetFilter").asText()))
              .hideAction(hide == null ? null : hide.name())
              .onFirstCaptureAction(first == null ? null : first.name())
              .onCaptureAction(onCapture == null ? null : onCapture.name())
              .actionOnCapturedObject(onCaptured == null ? null : onCaptured.name())
              .buffDuringCapture(
                  sets(f, "BuffDuringCapture")
                      ? records.buff(f.get("BuffDuringCapture").asText()).name()
                      : null)
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
      List<Integer> vertical = ints(f, "BombVerticalOffsets");
      List<Integer> absolute = ints(f, "BombAbsoluteHorizontalOffsets");
      List<String> areas = new ArrayList<>();
      f.path("BombAreaEffectObjects").forEach(a -> areas.add(a.asText()));
      int bombs =
          Math.min(
              vertical.size(), Math.min(ints(f, "BombHorizontalOffsets").size(), areas.size()));
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
      String projectile = text(f, "BombProjectile", "");
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
      if (bool(f, "StopAeoIfParentHasCombatDisabled", false)) {
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
          integer(f, "StayAliveAfterParentDiesDuration", -1));
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
          ints(f, "AliveTimeList"),
          actions(f.get("Actions")),
          bool(f, "AllowRepeatAction", true));
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
    /**
     * A level change, by its number columns or, as the shipped rows write it, by an expression for
     * the relative adjustment, on its cause or, with ExecuteOnParent, on the entity whose holder
     * runs it. A row that writes both forms is refused.
     */
    private SetCharacterLevel setCharacterLevel(String name, ActionRow shared, JsonNode f) {
      boolean onParent = bool(f, "ExecuteOnParent");
      if (!f.hasNonNull("RelativeLevelAdjustmentExpression")) {
        return new SetCharacterLevel(
            shared,
            integer(f, "RelativeLevelAdjustment"),
            integer(f, "AbsoluteLevelToSet", 1),
            onParent);
      }
      if (f.has("RelativeLevelAdjustment") || f.has("AbsoluteLevelToSet")) {
        throw new UnsupportedOperationException(
            name
                + " writes its level as an expression and as a number, which no data version"
                + " writes together");
      }
      IntSupplier adjustment = expression(f.get("RelativeLevelAdjustmentExpression"));
      if (adjustment == null) {
        throw new UnsupportedOperationException(
            name + " writes an empty level expression, which is not modelled");
      }
      return SetCharacterLevel.ofExpression(shared, adjustment, onParent);
    }

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
      if (text(f, "HitFilter", "").isEmpty()) {
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
              .maxUnitPerActionList(ints(f, "MaxUnitPerActionList"))
              .onDetectedUnitActionList(actions(f.get("OnDetectedUnitActionList")))
              .build());
    }

    /**
     * A guard spawn's columns: its guard's row, where the guard appears and charges to, the area
     * effect its run makes, which pushes and hits, and the tags its run on the guard sets. Its push
     * columns and its filter are read by no part of the run. Refused: a row that sets tags, a
     * singleton, a next action, a gate or a phase of its own, one whose guard the battle cannot
     * take, and one whose area effect sets a column not modelled.
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
      // The guard must read as a unit here, so a row the battle cannot take is refused as the
      // action is built rather than when it runs.
      String guard = text(f, "SpawnData", "");
      records.unit(guard);
      String areaEffect = text(f, "SpawnAEO", "");
      if (!areaEffect.isEmpty()) {
        List<String> unmodelled = records.areaEffect(areaEffect).unmodelledColumns();
        if (!unmodelled.isEmpty()) {
          throw new UnsupportedOperationException(
              name + " makes " + areaEffect + ", which sets columns not modelled: " + unmodelled);
        }
      }
      return new SpawnGuard(
          shared,
          SpawnGuard.Columns.builder()
              .spawnData(guard)
              .appearBehindAtDistance(integer(f, "AppearBehindAtDistance"))
              .targetRadius(f.hasNonNull("TargetRadius") ? f.get("TargetRadius").asInt() : 1000)
              .spawnAeo(areaEffect.isEmpty() ? null : areaEffect)
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
              .summonDistance(integer(f, "SummonDistance", 250))
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
          || !bool(f, "UseDeployForSummons", true)) {
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
              .waitForDashToFinish(bool(f, "WaitForDashToFinish", true))
              .build());
    }

    /**
     * A warp's columns: its offset, the landing's avoidance and the resets after it, the four the
     * loader defaults to on; for an InjectedCharacter warp also its speed, its acceleration (1000
     * when the row leaves it out, as the loader defaults it), its offsets, the target kept on
     * arrival and the name of the action run then, built as the warp arrives. Its target resolver
     * is never read in that mode. Refused: a mode other than the relative one or InjectedCharacter,
     * a relative warp with a speed or any of the flying warp's columns, an InjectedCharacter warp
     * without a speed, an offset away from a tower, a step of untargetability after the warp, and a
     * row that sets tags, a next action that waits for it, or a pause or stop gate; a singleton
     * relative warp, and an InjectedCharacter warp with a next action or a start gate. A relative
     * warp's start gate is asked as the warp starts, as every action's is.
     */
    private WarpCharacter warpCharacter(String name, ActionRow shared, JsonNode f) {
      // A relative warp's start gate is the runtime's, asked as the warp starts, before it moves
      // the unit.
      refuseShared(
          name,
          f,
          "GameTagsToSet",
          "NextActionWait",
          "ActionPausedIfTrue",
          "ForceStopIfTrue",
          "MakeUntargetableForTickAfterWarp");
      String mode = text(f, "WarpMode", "");
      boolean injected = mode.equals("InjectedCharacter");
      if (!mode.isEmpty() && !mode.equals("RelativeWarp") && !injected) {
        throw new UnsupportedOperationException(
            name + " warps in mode " + mode + ", which is not modelled");
      }
      int speed = integer(f, "Speed");
      WarpCharacter.Flight flight = null;
      if (injected) {
        // The hand-over lists the run it builds; nothing schedules a next action after it, and
        // its launch does not pass the runtime's start, which asks the start gate.
        refuseShared(name, f, "NextAction", "ExecuteIfTrue");
        if (speed <= 0) {
          throw new UnsupportedOperationException(
              name + " warps to an injected target with no Speed, which is not modelled");
        }
        if (integer(f, "OffsetToTargetConsideringDirectionToTower") != 0) {
          throw new UnsupportedOperationException(
              name
                  + " sets OffsetToTargetConsideringDirectionToTower, whose tower pick is not"
                  + " modelled");
        }
        flight =
            WarpCharacter.Flight.builder()
                .speedPerStep(speed)
                .acceleration(integer(f, "Acceleration", 1000))
                .offsetX(integer(f, "OffsetX"))
                .offsetY(integer(f, "OffsetY"))
                .forceKeepTarget(bool(f, "ForceKeepTargetAfterWarp"))
                .onWarpEnd(rowName(f.get("OnWarpEndAction")))
                .build();
      } else {
        refuseShared(
            name,
            f,
            "Singleton",
            "Acceleration",
            "OnWarpEndAction",
            "ForceKeepTargetAfterWarp",
            "TargetResolver");
        // A relative warp with a speed flies toward a point near the map's origin.
        for (String column :
            List.of("Speed", "OffsetX", "OffsetY", "OffsetToTargetConsideringDirectionToTower")) {
          if (integer(f, column) != 0) {
            throw new UnsupportedOperationException(
                name + " sets " + column + " on a relative warp, not modelled");
          }
        }
      }
      return new WarpCharacter(
          shared,
          WarpCharacter.Columns.builder()
              .warpX(integer(f, "WarpX"))
              .warpY(integer(f, "WarpY"))
              .resetPath(bool(f, "ResetPath", true))
              .resetTarget(bool(f, "ResetTarget"))
              .avoidWater(bool(f, "AvoidWaterVertically", true))
              .avoidBlocked(bool(f, "AvoidBlockedTilesVertically", true))
              .resetPendingDamage(bool(f, "ResetPendingDamageAtWarp", true))
              .build(),
          flight);
    }

    /**
     * The columns of a run on what a target resolver finds: the resolver's filter, shape and
     * strategies, how many objects it may run on, whether it runs on its owner instead, and the
     * names of its two actions, each built for the object it is scheduled on. A Cone shape's
     * columns are its Circle's Radius and CheckOrigin and its own Angle, AngleOffset and
     * UseGameObjectDirection. Refused: a row without a resolver, a resolver whose shape is neither
     * a Global nor a Cone one, that has no filter or no strategy.
     */
    private RunOnResolvedObjects resolvedObjects(String name, ActionRow shared, JsonNode f) {
      String resolverName = text(f, "Resolver", "");
      if (resolverName.isEmpty()) {
        throw new UnsupportedOperationException(
            name + " resolves with no target resolver, which is not modelled");
      }
      GameTable resolvers = tables.table(TARGET_RESOLVERS);
      if (!resolvers.has(resolverName)) {
        throw new IllegalArgumentException("no target resolver " + resolverName);
      }
      GameRow resolver = resolvers.row(resolverName);
      String shape = resolver.string("Shape");
      GameTable shapes = tables.table("shapes");
      String shapeType =
          shape == null || !shapes.has(shape) ? null : shapes.row(shape).string("ClassType");
      if (!"Global".equals(shapeType) && !"Cone".equals(shapeType)) {
        throw new UnsupportedOperationException(
            name
                + " resolves through "
                + resolverName
                + ", whose shape "
                + shape
                + " is neither a Global nor a Cone one, which is not modelled");
      }
      ConeShape cone = null;
      if ("Cone".equals(shapeType)) {
        GameRow row = shapes.row(shape);
        cone =
            ConeShape.builder()
                .radius(row.intValue("Radius"))
                .angle(row.intValue("Angle"))
                .angleOffset(row.intValue("AngleOffset"))
                .useDirection(row.bool("UseGameObjectDirection"))
                .checkOrigin(row.bool("CheckOrigin"))
                .build();
      }
      String filter = resolver.string("Filter");
      if (filter == null || filter.isEmpty()) {
        throw new UnsupportedOperationException(
            name + " resolves through " + resolverName + " with no filter, which is not modelled");
      }
      List<String> strategies = new ArrayList<>();
      JsonNode list = resolver.value("StrategyList");
      if (list != null && list.isArray()) {
        list.forEach(value -> strategies.add(value.asText()));
      }
      if (strategies.isEmpty()) {
        throw new UnsupportedOperationException(
            name
                + " resolves through "
                + resolverName
                + " with no strategy, which is not modelled");
      }
      return new RunOnResolvedObjects(
          shared,
          RunOnResolvedObjects.Columns.builder()
              .resolver(resolverName)
              .filter(records.filter(filter))
              .cone(cone)
              .strategies(strategies)
              .amount(integer(f, "Amount"))
              .runActionsOnSelf(bool(f, "RunActionsOnSelf"))
              .action(rowName(f.get("Action")))
              .noObjectsAction(rowName(f.get("ActionToRunOnSelfIfNoObjectsFound")))
              .build());
    }

    /**
     * A resolver write's columns: its resolver's filter, shape and strategies, its board, its
     * default value and the key of its result name, hashed whatever the name, an empty one
     * included. A Circle shape is read as a cone that keeps every angle: the circle query is the
     * same, and a circle's narrowing keeps, with CheckOrigin, what lies within its radius by the
     * centre, as such a cone's does, and everything without it. Refused: a resolver whose shape is
     * not a Global, a Cone or a Circle one, or that has no filter or no strategy.
     */
    private WriteResolverResultToContext writeResolverResult(
        String name, ActionRow shared, JsonNode f) {
      WriteResolverResultToContext.Columns.ColumnsBuilder columns =
          WriteResolverResultToContext.Columns.builder()
              .useScratch(bool(f, "UseScratchBlackboard"))
              .defaultValue(integer(f, "DefaultValue", -1))
              .key(ActionContext.key(text(f, "ResultName", "")));
      String resolverName = text(f, "Resolver", "");
      if (resolverName.isEmpty()) {
        return new WriteResolverResultToContext(shared, columns.build());
      }
      ResolverParts parts = resolverParts(name, resolverName);
      return new WriteResolverResultToContext(
          shared,
          columns
              .resolver(resolverName)
              .filter(parts.filter())
              .cone(parts.cone())
              .strategies(parts.strategies())
              .build());
    }

    /** A resolver's filter, its shape as a cone (null for a Global one) and its strategies. */
    private record ResolverParts(
        GameObjectFilter filter, ConeShape cone, List<String> strategies) {}

    /**
     * A resolver's parts as a row asks through it. A Circle shape is read as a cone that keeps
     * every angle. Refused: a resolver whose shape is not a Global, a Cone or a Circle one, or that
     * has no filter or no strategy.
     */
    private ResolverParts resolverParts(String name, String resolverName) {
      GameTable resolvers = tables.table(TARGET_RESOLVERS);
      if (!resolvers.has(resolverName)) {
        throw new IllegalArgumentException("no target resolver " + resolverName);
      }
      GameRow resolver = resolvers.row(resolverName);
      String shape = resolver.string("Shape");
      GameTable shapes = tables.table("shapes");
      String shapeType =
          shape == null || !shapes.has(shape) ? null : shapes.row(shape).string("ClassType");
      ConeShape cone = null;
      if ("Cone".equals(shapeType) || "Circle".equals(shapeType)) {
        GameRow row = shapes.row(shape);
        boolean circle = "Circle".equals(shapeType);
        cone =
            ConeShape.builder()
                .radius(row.intValue("Radius"))
                .angle(circle ? 360 : row.intValue("Angle"))
                .angleOffset(circle ? 0 : row.intValue("AngleOffset"))
                .useDirection(!circle && row.bool("UseGameObjectDirection"))
                .checkOrigin(row.bool("CheckOrigin"))
                .build();
      } else if (!"Global".equals(shapeType)) {
        throw new UnsupportedOperationException(
            name
                + " resolves through "
                + resolverName
                + ", whose shape "
                + shape
                + " is not a Global, a Cone or a Circle one, which is not modelled");
      }
      String filter = resolver.string("Filter");
      if (filter == null || filter.isEmpty()) {
        throw new UnsupportedOperationException(
            name + " resolves through " + resolverName + " with no filter, which is not modelled");
      }
      List<String> strategies = new ArrayList<>();
      JsonNode list = resolver.value("StrategyList");
      if (list != null && list.isArray()) {
        list.forEach(value -> strategies.add(value.asText()));
      }
      if (strategies.isEmpty()) {
        throw new UnsupportedOperationException(
            name
                + " resolves through "
                + resolverName
                + " with no strategy, which is not modelled");
      }
      return new ResolverParts(records.filter(filter), cone, strategies);
    }

    /**
     * An attack chain's columns, a column it leaves out taking the loader's default: 1 to reset the
     * target after a reach, a longest duration of 10000 ms, and the default target as a fallback.
     * Refused: a reach that attacks, a reach range of its own, the forget columns, the default
     * target as a fallback, a no-target action, no resolver or one of more than one strategy, and
     * the shared columns its run does not read.
     */
    private AttackChain attackChain(String name, ActionRow shared, JsonNode f) {
      refuseShared(
          name,
          f,
          "NextAction",
          "NextActionWait",
          "ForceStopIfTrue",
          "ActionPausedIfTrue",
          "Singleton",
          "PerformAttackOnReach",
          "ForgetRangeAlwaysOn",
          "ForgetAllTargetsIfNoNewOnes",
          "ForgetOldestTargetIfNoNewOnes",
          "OnNoTargetFound");
      if (integer(f, "ReachRange", -1) >= 0 || integer(f, "ForgetTargetRange", 0) != 0) {
        throw new UnsupportedOperationException(
            name + " sets a reach or forget range on an ActionAttackChain, not modelled");
      }
      if (bool(f, "CanUseDefaultTargetAsFallback", true)) {
        throw new UnsupportedOperationException(
            name + " falls back on the default target, which is not modelled");
      }
      String resolverName = text(f, "TargetResolver", "");
      if (resolverName.isEmpty()) {
        throw new UnsupportedOperationException(
            name + " chains with no target resolver, which is not modelled");
      }
      ResolverParts parts = resolverParts(name, resolverName);
      if (parts.strategies().size() != 1) {
        throw new UnsupportedOperationException(
            name
                + " chains through "
                + resolverName
                + ", of more than one strategy, whose ranking is not modelled");
      }
      parts.strategies().forEach(s -> RunOnResolvedObjects.checkStrategy(s, name, resolverName));
      String buff = text(f, "ChainPhaseBuff", "");
      if (!buff.isEmpty()) {
        records.buff(buff);
      }
      return new AttackChain(
          shared,
          AttackChain.Columns.builder()
              .resolver(resolverName)
              .filter(parts.filter())
              .cone(parts.cone())
              .strategies(parts.strategies())
              .chainCount(integer(f, "ChainCount", 0))
              .resetTargetAfterReach(bool(f, "ResetTargetAfterReach", true))
              .onChainBegan(rowName(f.get("OnChainBegan")))
              .onReachTarget(rowName(f.get("OnReachTarget")))
              .onTargetDied(rowName(f.get("OnTargetDied")))
              .onChainComplete(rowName(f.get("OnChainComplete")))
              .onFinishedAction(rowName(f.get("OnFinishedAction")))
              .chainPhaseBuff(buff.isEmpty() ? null : buff)
              .maxDurationMs(integer(f, "MaxDurationMs", 10000))
              .chainCompleteIf(expression(f.get("ChainCompleteIfTrue")))
              .pauseIfAttackSpeedZero(bool(f, "PauseIfAttackSpeedZero"))
              .stopMovementWhenAtTarget(bool(f, "StopMovementWhenAtTarget"))
              .build());
    }

    /**
     * A dashing attack chain's columns, a column it leaves out taking the loader's default: no
     * dashes and the target reset after the dash. Refused: a row of other than one dash, one that
     * keeps the target, one that names any of the chain's actions, no resolver or one of more than
     * one strategy, and the shared columns its run does not read.
     */
    private DashingAttackChain dashingAttackChain(String name, ActionRow shared, JsonNode f) {
      refuseShared(
          name,
          f,
          "NextAction",
          "NextActionWait",
          "ForceStopIfTrue",
          "ActionPausedIfTrue",
          "Singleton",
          "OnDashChainBegan",
          "OnDashReachTarget",
          "OnTargetDied",
          "OnChainComplete",
          "OnNoTargetFound");
      int dashCount = integer(f, "DashCount", 0);
      if (dashCount != 1) {
        throw new UnsupportedOperationException(
            name + " dashes " + dashCount + " times, whose chain no reference holds");
      }
      if (!bool(f, "ResetTargetAfterDash", true)) {
        throw new UnsupportedOperationException(
            name + " keeps its target after the dash, which no reference holds");
      }
      String resolverName = text(f, "TargetResolver", "");
      if (resolverName.isEmpty()) {
        throw new UnsupportedOperationException(
            name + " dashes with no target resolver, which is not modelled");
      }
      ResolverParts parts = resolverParts(name, resolverName);
      if (parts.strategies().size() != 1) {
        throw new UnsupportedOperationException(
            name
                + " dashes through "
                + resolverName
                + ", of more than one strategy, whose ranking is not modelled");
      }
      parts.strategies().forEach(s -> RunOnResolvedObjects.checkStrategy(s, name, resolverName));
      return new DashingAttackChain(
          shared,
          DashingAttackChain.Columns.builder()
              .resolver(resolverName)
              .filter(parts.filter())
              .cone(parts.cone())
              .strategies(parts.strategies())
              .dashCount(dashCount)
              .build());
    }

    /**
     * A mark's columns: its resolver's filter and strategies, its two actions, its two tag masks,
     * its pause and its search delay. Refused: a row without a resolver, a resolver whose shape is
     * not a Global one or that has no filter, and a row that waits for its next action.
     */
    private SetIndicatorOnTarget setIndicatorOnTarget(String name, ActionRow shared, JsonNode f) {
      refuseShared(name, f, "NextActionWait");
      String resolverName = text(f, "TargetResolver", "");
      if (resolverName.isEmpty()) {
        throw new UnsupportedOperationException(
            name + " marks with no target resolver, which is not modelled");
      }
      GameTable resolvers = tables.table(TARGET_RESOLVERS);
      if (!resolvers.has(resolverName)) {
        throw new IllegalArgumentException("no target resolver " + resolverName);
      }
      GameRow resolver = resolvers.row(resolverName);
      String shape = resolver.string("Shape");
      GameTable shapes = tables.table("shapes");
      if (shape == null
          || !shapes.has(shape)
          || !"Global".equals(shapes.row(shape).string("ClassType"))) {
        throw new UnsupportedOperationException(
            name
                + " resolves through "
                + resolverName
                + ", whose shape "
                + shape
                + " is not a Global one, which is not modelled");
      }
      String filter = resolver.string("Filter");
      if (filter == null || filter.isEmpty()) {
        throw new UnsupportedOperationException(
            name + " resolves through " + resolverName + " with no filter, which is not modelled");
      }
      List<String> strategies = new ArrayList<>();
      JsonNode list = resolver.value("StrategyList");
      if (list != null && list.isArray()) {
        list.forEach(value -> strategies.add(value.asText()));
      }
      return new SetIndicatorOnTarget(
          shared,
          SetIndicatorOnTarget.Columns.builder()
              .resolver(resolverName)
              .filter(records.filter(filter))
              .strategies(strategies)
              .onPickNewTarget(action(f.get("OnPickNewTargetAction")))
              .onTargetDied(action(f.get("OnTargetDiedAction")))
              .tagsWithoutTarget(tagMask(text(f, "GameTagsToSetWhileHasNotTarget", "")))
              .tagsWithTarget(tagMask(text(f, "GameTagsToSetWhileHasTarget", "")))
              .pauseIfInCooldown(bool(f, "PauseIfInCooldown"))
              .delayBeforeSearchMs(integer(f, "DelayBeforeSearchForNextTarget"))
              .pinnedActive(expression(f.get("PinnedActiveExpression")))
              .pinnedX(expression(f.get("PinnedPositionXExpression")))
              .pinnedY(expression(f.get("PinnedPositionYExpression")))
              .build());
    }

    /**
     * A hand-over's columns: the name of the mark it reads, the name of the warp it launches, its
     * two deploy actions, the return (ReturnToOrigin, ReturnOnTargetDeath, ReturnDelay, the name of
     * the ReturnWarpAction row) and the warp window's three keys, each the hash of its name, a key
     * column left out naming the default key. Refused: a row without a mark to read. Both warps are
     * built as they are launched, for the owner.
     */
    private MegaMinionHeroAbility megaMinionHeroAbility(String name, ActionRow shared, JsonNode f) {
      String mark = rowName(f.get("ActionToGetTargetFrom"));
      if (mark == null) {
        throw new UnsupportedOperationException(name + " reads no mark, which is not modelled");
      }
      return new MegaMinionHeroAbility(
          shared,
          MegaMinionHeroAbility.Columns.builder()
              .markRow(mark)
              .actionToExecute(rowName(f.get("ActionToExecute")))
              .noTargetOnDeploy(action(f.get("NoTargetOnDeployAction")))
              .hasTargetOnDeploy(action(f.get("HasTargetOnDeployAction")))
              .returnToOrigin(bool(f, "ReturnToOrigin"))
              .returnOnTargetDeath(bool(f, "ReturnOnTargetDeath"))
              .returnDelayMs(integer(f, "ReturnDelay"))
              .returnWarpRow(rowName(f.get("ReturnWarpAction")))
              .activeKey(
                  ActionContext.key(
                      text(f, "WarpWindowActiveKey", MegaMinionHeroAbility.DEFAULT_ACTIVE_KEY)))
              .originXKey(
                  ActionContext.key(
                      text(f, "WarpWindowOriginXKey", MegaMinionHeroAbility.DEFAULT_ORIGIN_X_KEY)))
              .originYKey(
                  ActionContext.key(
                      text(f, "WarpWindowOriginYKey", MegaMinionHeroAbility.DEFAULT_ORIGIN_Y_KEY)))
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
      if (text(f, "TargetFilter", "").isEmpty()) {
        throw new UnsupportedOperationException(
            name + " is a target indicator attack without a filter, which is not modelled");
      }
      String signal = text(f, "TargetAoE", "");
      AreaEffectData signalData = records.areaEffect(signal);
      if (signalData.followsParent() || !signalData.unmodelledColumns().isEmpty()) {
        throw new UnsupportedOperationException(
            name
                + " marks its target with "
                + signal
                + ", which follows something or sets columns not modelled");
      }
      ProjectileData projectile = records.projectile(text(f, "Projectile", ""));
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
     * An area-effect spawn row's columns: the area effect, whether the owner is the source, the two
     * offsets and, for the location class, its two position expressions, which give the point in
     * place of the owner's, and UseDeploy and the two target expressions, which the area-effect
     * branch does not read (the target expressions only aim a projectile); for the plain class, the
     * action run on the area effect it spawns and whether that action shares the context. A
     * location row that sets no position column at all spawns at the owner's point, as the plain
     * class does: the location's x and y fall through the absolute, relative, expression and
     * mirrored columns to the owner's own. A location row with only one expression, a row that sets
     * any other spawn column, writes its area effect inline, or names one whose row sets a column
     * not modelled is refused.
     */
    private SpawnAreaEffect spawnAreaEffect(
        String name, String type, ActionRow shared, JsonNode f) {
      boolean location = !type.equals("ActionSpawn");
      // A location row's point is its two expressions; one that leaves either out, or places by
      // an absolute or relative point, takes another of the location slots' paths.
      boolean ownerPoint =
          location
              && Stream.of(
                      "AbsoluteX",
                      "AbsoluteY",
                      "RelativeX",
                      "RelativeY",
                      "MirroredX",
                      "MirroredY",
                      "XPositionExpression",
                      "YPositionExpression")
                  .noneMatch(column -> sets(f, column));
      if (location
          && !ownerPoint
          && (!f.hasNonNull("XPositionExpression") || !f.hasNonNull("YPositionExpression"))) {
        throw new UnsupportedOperationException(
            name
                + " spawns an area effect to a location without both position expressions, which"
                + " is not modelled");
      }
      Set<String> read =
          location
              ? Set.of(
                  "SpawnData",
                  "SpawnType",
                  "ParentGOAsSource",
                  "OffsetX",
                  "OffsetY",
                  "XPositionExpression",
                  "YPositionExpression",
                  "UseDeploy",
                  "TargetExprX",
                  "TargetExprY")
              : Set.of(
                  "SpawnData",
                  "SpawnType",
                  "ParentGOAsSource",
                  "OffsetX",
                  "OffsetY",
                  "ActionToRunOnSpawned",
                  "ShareContext");
      for (Iterator<String> columns = f.fieldNames(); columns.hasNext(); ) {
        String column = columns.next();
        if (spawnColumns().contains(column) && !read.contains(column)) {
          throw new UnsupportedOperationException(
              name + " spawns an area effect and sets " + column + ", which is not modelled");
        }
      }
      if (!f.path("SpawnData").isTextual()) {
        throw new UnsupportedOperationException(
            name + " spawns an area effect written inline, which is not modelled");
      }
      String areaEffect = text(f, "SpawnData", "");
      List<String> unmodelled = records.areaEffect(areaEffect).unmodelledColumns();
      if (!unmodelled.isEmpty()) {
        throw new UnsupportedOperationException(
            name + " spawns " + areaEffect + ", which sets columns not modelled: " + unmodelled);
      }
      return new SpawnAreaEffect(
          shared,
          areaEffect,
          bool(f, "ParentGOAsSource"),
          integer(f, "OffsetX"),
          integer(f, "OffsetY"),
          location ? expression(f.get("XPositionExpression")) : null,
          location ? expression(f.get("YPositionExpression")) : null,
          location ? null : rowName(f.get("ActionToRunOnSpawned")),
          !location && bool(f, "ShareContext"));
    }

    /**
     * A taunt row, each column with the default the game's loader gives a column the row leaves
     * out: the reach tested by distance, the falloff run down as the duration expires and the buffs
     * removed as the run finishes all on. Each buff it names must read as a modelled buff.
     */
    private Taunt taunt(String name, ActionRow shared, JsonNode f) {
      String valid = buffName(name, f, "ValidTargetBuff");
      String invalid = buffName(name, f, "InvalidTargetBuff");
      String crown = buffName(name, f, "CrownTowerBuff");
      return Taunt.builder()
          .row(shared)
          .resetsOnDistance(bool(f, "ResetsOnDistance", true))
          .resetOnExpiration(bool(f, "ResetOnExpiration", true))
          .allowBuildingRetargeting(bool(f, "AllowBuildingRetargeting"))
          .falloffDelayMs(integer(f, "FalloffDelay"))
          .validDurationMs(integer(f, "ValidDuration"))
          .validTargetBuff(valid)
          .invalidDurationMs(integer(f, "InvalidDuration"))
          .invalidTargetBuff(invalid)
          .crownTowerDurationMs(integer(f, "CrownTowerDuration"))
          .crownTowerBuff(crown)
          .removeBuffOnDeath(bool(f, "RemoveBuffOnDeath", true))
          .build();
    }

    /** A buff a taunt row names, or null for none; one setting a column not modelled is refused. */
    private String buffName(String name, JsonNode f, String column) {
      String buff = f.hasNonNull(column) ? f.get(column).asText() : null;
      if (buff != null && !records.buff(buff).unmodelledColumns().isEmpty()) {
        throw new UnsupportedOperationException(
            name
                + " taunts with "
                + buff
                + ", which sets columns not modelled: "
                + records.buff(buff).unmodelledColumns());
      }
      return buff;
    }

    /**
     * A projectile spawn row of either class, the location class starting it from the source's
     * point at the row's start height, the plain class from the source's point and live height;
     * aimed by neither expression, it is launched at the source's current target, refused as it
     * starts while the source holds one. The source is the owner, or the cause without
     * ParentGOAsSource, refused as it starts when the cause is not the owner. Any other spawn
     * column but the action to run on what it spawned, which this branch does not read, is refused.
     */
    private SpawnProjectile spawnProjectile(
        String name, String type, ActionRow shared, JsonNode f) {
      boolean spawnClass = type.equals("ActionSpawn");
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
                    "ActionToRunOnSpawned",
                    "TargetFromContextName",
                    "UseScratchBlackboard",
                    "ProjectileStartOffset")
                .contains(column)) {
          throw new UnsupportedOperationException(
              name + " spawns a projectile and sets " + column + ", which is not modelled");
        }
      }
      // A row that names no context target keeps the existing aim, which reads no board.
      if (!f.hasNonNull("TargetFromContextName") && f.has("UseScratchBlackboard")) {
        throw new UnsupportedOperationException(
            name + " spawns a projectile and picks a board without a context name, not modelled");
      }
      IntSupplier aimX = expression(f.get("TargetExprX"));
      IntSupplier aimY = expression(f.get("TargetExprY"));
      if (!f.path("SpawnData").isTextual()) {
        throw new UnsupportedOperationException(
            name + " spawns a projectile written inline, which is not modelled");
      }
      return new SpawnProjectile(
          shared,
          text(f, "SpawnData", ""),
          integer(f, "StartPositionZOffset"),
          aimX,
          aimY,
          spawnClass,
          bool(f, "ParentGOAsSource"),
          f.hasNonNull("TargetFromContextName")
              ? ActionContext.key(f.get("TargetFromContextName").asText())
              : null,
          bool(f, "UseScratchBlackboard"),
          integer(f, "ProjectileStartOffset"));
    }

    /** A character spawn row's columns; any other spawn type is refused. */
    private SpawnRow spawn(String name, String type, JsonNode f) {
      String spawnType = text(f, "SpawnType", "");
      if (!spawnType.equals("CharacterType")) {
        throw new UnsupportedOperationException(
            name + " spawns " + spawnType + ", which is not modelled");
      }
      // The projectile branch's columns, which the character branch does not read, and the
      // context target, which the perform hands every branch and this port does not follow. The
      // two aim expressions are read by the projectile branch alone, so a character spawn that
      // sets them (the hero Dark Prince's mount, at "x" and "y") spawns where it would without.
      for (String column :
          List.of(
              "StartPositionZOffset",
              "TargetFromContextName",
              "UseScratchBlackboard",
              "ProjectileStartOffset")) {
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

    /**
     * The evolved Rage Barbarian's ghost wait, each column with the loader's default when the row
     * leaves it out: no buff, no action, a delay and portal time of 0. Each buff it names must be a
     * buff row. A raged-back action and ResetOnDelay are not read: a row setting either is refused
     * by its columns.
     */
    private LumberjackGhostWait lumberjackGhostWait(ActionRow shared, JsonNode f) {
      String consider = f.has("BuffToConsider") ? f.get("BuffToConsider").asText() : null;
      String override = f.has("BuffOverride") ? f.get("BuffOverride").asText() : null;
      for (String buff : new String[] {consider, override}) {
        if (buff != null) {
          records.buff(buff);
        }
      }
      return new LumberjackGhostWait(
          shared,
          LumberjackGhostWait.Columns.builder()
              .buffToConsider(consider)
              .buffOverride(override)
              .actionToExecute(action(f.get("ActionToExecute")))
              .delayMs(integer(f, "Delay"))
              .portalTimerMs(integer(f, "PortalTimer"))
              .onAboutToDie(action(f.get("OnAboutToDieAction")))
              .build());
    }

    /**
     * A counter's row: its timers and actions as the damage reaction's loader reads them (Duration,
     * Cooldown and TriggerCount default 0; the context key is hashed only when named), then its own
     * (DefenseScalar 100 and AttackerRangeThreshold 1800 by default). CounterProjectiles, whose
     * counter strikes back at the projectile's shooter, is refused: no row sets it.
     */
    private Counter counter(String name, ActionRow shared, JsonNode f) {
      if (bool(f, "CounterProjectiles")) {
        throw new UnsupportedOperationException(
            name + " sets CounterProjectiles, which is not modelled");
      }
      String key = text(f, "DamageKey", "");
      String included = text(f, "IncludedFilter", "");
      return new Counter(
          shared,
          Counter.Columns.builder()
              .durationMs(integer(f, "Duration", 0))
              .cooldownMs(integer(f, "Cooldown", 0))
              .triggerCount(integer(f, "TriggerCount", 0))
              .selfAction(action(f.get("SelfAction")))
              .instigatorAction(action(f.get("InstigatorAction")))
              .damageKey(key.isEmpty() ? 0 : ActionContext.key(key))
              .defenseScalar(integer(f, "DefenseScalar", 100))
              .includedFilter(included.isEmpty() ? null : records.filter(included))
              .attackerRangeThreshold(integer(f, "AttackerRangeThreshold", 1800))
              .counterFlying(bool(f, "CounterFlying"))
              .deployActive(bool(f, "DeployActive"))
              .build());
    }

    /**
     * A damage-taking row's inline damage: BaseDamage (0 when left out), TowerDamage (none when
     * left out), its Flags (one comma-separated text or a list) and an Effect only the view shows.
     * Refused: a damage that is not a table, a flag other than Reflected, NoScaling, NoProtection,
     * NoAmplification and DamagesHidden, and any other field.
     */
    private TakeDamage.Damage takenDamage(String name, JsonNode value) {
      if (value == null || !value.isObject()) {
        throw new UnsupportedOperationException(
            name + " writes its Damage as other than a table, which is not modelled");
      }
      Set<String> flags = new HashSet<>();
      for (Iterator<String> it = value.fieldNames(); it.hasNext(); ) {
        String field = it.next();
        if (!Set.of("BaseDamage", "TowerDamage", "Flags", "Effect").contains(field)) {
          throw new UnsupportedOperationException(
              name + " sets " + field + " in its Damage, which is not modelled");
        }
      }
      // The flags are written as one comma-separated text or as a list of names.
      JsonNode written = value.get("Flags");
      List<String> names = new ArrayList<>();
      if (written != null && written.isArray()) {
        written.forEach(flag -> names.add(flag.asText()));
      } else if (written != null && !written.isNull()) {
        names.addAll(List.of(text(value, "Flags", "").split(",")));
      }
      for (String flag : names) {
        if (!flag.isBlank()) {
          flags.add(flag.trim());
        }
      }
      for (String flag : flags) {
        if (!Set.of("Reflected", "NoScaling", "NoProtection", "NoAmplification", "DamagesHidden")
            .contains(flag)) {
          throw new UnsupportedOperationException(
              name + " sets the damage flag " + flag + ", which is not modelled");
        }
      }
      return new TakeDamage.Damage(
          integer(value, "BaseDamage", 0),
          integer(value, "TowerDamage", TakeDamage.NO_TOWER_DAMAGE),
          flags.contains("Reflected"),
          flags.contains("NoProtection"),
          flags.contains("NoAmplification"),
          !flags.contains("NoScaling"),
          flags.contains("DamagesHidden"));
    }

    /** A named action, a row inline by name, or null for none. */
    private BattleAction action(JsonNode reference) {
      if (reference == null || reference.isNull()) {
        return null;
      }
      JsonNode named = reference.isObject() ? reference.get("action") : reference;
      if (named == null || !named.isTextual()) {
        throw new MistypedField(
            "names an action as " + GameRow.shape(reference) + " where a row's name is read");
      }
      String name = named.asText();
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
        if (!GameRow.isWhole(value)) {
          throw new MistypedField(
              "writes an expression as " + GameRow.shape(value) + " where a whole number is read");
        }
        int constant = value.asInt();
        return () -> constant;
      }
      if (!value.isTextual()) {
        throw new MistypedField(
            "writes an expression as " + GameRow.shape(value) + " where a text is read");
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

  /**
   * The phase name a row's UpdatePhase writes that the reader reads, as the phase it reads it as.
   * The column is written only as a name. PostGameObjectTick, the one name a row the battle builds
   * writes (the Giant hero's slap push), is read as it always has been: as any phase, not as the
   * post game object pass its name says, which is left for a trace to settle.
   */
  private static final Map<String, Integer> UPDATE_PHASES =
      Map.of("PostGameObjectTick", BattleAction.ANY_PHASE);

  /**
   * A row's update phase: any phase when it sets none; a phase name the reader reads as above.
   *
   * @throws MistypedField for any other value, a name or a number alike
   */
  private static int updatePhase(JsonNode fields) {
    JsonNode value = fields.get("UpdatePhase");
    if (value == null || value.isNull() || value.isTextual() && value.asText().isEmpty()) {
      return BattleAction.ANY_PHASE;
    }
    Integer phase = value.isTextual() ? UPDATE_PHASES.get(value.asText()) : null;
    if (phase == null) {
      throw new MistypedField("UpdatePhase", value, "a phase name the battle reads");
    }
    return phase;
  }

  /**
   * A field as an integer: 0 when the row leaves it out or sets an empty cell.
   *
   * @throws MistypedField when it holds anything but a whole number
   */
  private static int integer(JsonNode fields, String column) {
    return integer(fields, column, 0);
  }

  /**
   * A field as an integer: the fallback when the row leaves it out or sets an empty cell.
   *
   * @throws MistypedField when it holds anything but a whole number
   */
  private static int integer(JsonNode fields, String column, int fallback) {
    JsonNode value = fields.get(column);
    if (value == null || value.isNull() || value.isTextual() && value.asText().isEmpty()) {
      return fallback;
    }
    if (!GameRow.isWhole(value)) {
      throw new MistypedField(column, value, "a number");
    }
    return value.asInt();
  }

  /**
   * A field of an action row whose value is of another shape than its reader reads, refused with
   * the row that sets it once the row's build names it.
   */
  private static final class MistypedField extends UnsupportedOperationException {
    MistypedField(String column, JsonNode value, String expected) {
      this("sets " + column + " to " + GameRow.shape(value) + " where " + expected + " is read");
    }

    /** A refusal of what the row does, said without the row's name, which the build adds. */
    MistypedField(String what) {
      super(what + ", which is not modelled");
    }
  }

  /** A group check's filter, which it must name. */
  private GameObjectFilter objectFilter(String name, JsonNode fields) {
    String filter = text(fields, "ObjectFilter", "");
    if (filter.isEmpty()) {
      throw new UnsupportedOperationException(
          name + " checks its group with no filter, which is not modelled");
    }
    return records.filter(filter);
  }

  /**
   * A field as a boolean: false when the row leaves it out or sets an empty cell.
   *
   * @throws MistypedField when it holds anything but a boolean
   */
  private static boolean bool(JsonNode fields, String column) {
    return bool(fields, column, false);
  }

  /**
   * A field as a boolean: the fallback when the row leaves it out or sets an empty cell.
   *
   * @throws MistypedField when it holds anything but a boolean
   */
  private static boolean bool(JsonNode fields, String column, boolean fallback) {
    JsonNode value = fields.get(column);
    if (value == null || value.isNull() || value.isTextual() && value.asText().isEmpty()) {
      return fallback;
    }
    if (!value.isBoolean()) {
      throw new MistypedField(column, value, "a boolean");
    }
    return value.asBoolean();
  }

  /**
   * A field as a string: the fallback when the row leaves it out.
   *
   * @throws MistypedField when it holds anything but a text
   */
  private static String text(JsonNode fields, String column, String fallback) {
    JsonNode value = fields.get(column);
    if (value == null || value.isNull()) {
      return fallback;
    }
    if (!value.isTextual()) {
      throw new MistypedField(column, value, "a text");
    }
    return value.asText();
  }

  /**
   * A field of whole numbers, written as a list of them or as one: none when the row leaves it out
   * or sets an empty cell.
   *
   * @throws MistypedField when it, or an element of its list, is anything but a whole number
   */
  private static List<Integer> ints(JsonNode fields, String column) {
    List<Integer> out = new ArrayList<>();
    JsonNode values = fields.get(column);
    if (values != null && values.isArray()) {
      for (JsonNode value : values) {
        if (!GameRow.isWhole(value)) {
          throw new MistypedField(column, value, "a list of numbers");
        }
        out.add(value.asInt());
      }
    } else if (values != null && !values.isNull()) {
      int value = integer(fields, column);
      if (!(values.isTextual() && values.asText().isEmpty())) {
        out.add(value);
      }
    }
    return out;
  }

  /** A switch of a damage type row: on unless the row sets it off. */
  private static boolean on(GameRow row, String column) {
    JsonNode value = row.value(column);
    return value == null || value.isNull() || value.asBoolean();
  }
}
