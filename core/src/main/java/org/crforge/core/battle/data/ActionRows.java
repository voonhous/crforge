package org.crforge.core.battle.data;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;
import org.crforge.core.battle.action.ActionRow;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.DamageType;
import org.crforge.core.battle.action.DealDamage;
import org.crforge.core.battle.action.Filter;
import org.crforge.core.battle.action.FlipFlop;
import org.crforge.core.battle.action.Group;
import org.crforge.core.battle.action.Heal;
import org.crforge.core.battle.action.InertAction;
import org.crforge.core.battle.action.Interval;
import org.crforge.core.battle.action.Kill;
import org.crforge.core.battle.action.RunActionAtHealth;
import org.crforge.core.battle.action.RunOnInstigator;
import org.crforge.core.battle.action.Select;
import org.crforge.core.battle.action.SetCharacterLevel;
import org.crforge.core.battle.action.SetShield;
import org.crforge.core.battle.action.SetVariable;
import org.crforge.core.battle.action.WaitToActivate;
import org.crforge.core.battle.action.WithDuration;
import org.crforge.core.battle.spawn.SpawnCharacters;
import org.crforge.core.battle.spawn.SpawnRow;
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
 * something and ignored, or refused; a column it does not know is refused too, so nothing a row
 * sets is dropped unnoticed. A row of a class the battle does not have is refused, naming the
 * class; so is a character spawn row of any other spawn type.
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
          Map.entry("ActionSelect", Set.of("SubActions", "Condition", "PerActionConditions")),
          Map.entry("ActionFilter", Set.of("Condition", "OnTrueAction", "OnFalseAction")),
          Map.entry("ActionRunOnInstigator", Set.of("ActionToExecute")),
          Map.entry("ActionWaitToActivate", Set.of("Condition", "OnActivateAction")),
          Map.entry("ActionWithDuration", Set.of("ActionDuration")),
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
              "ActionSetCharacterLevel", Set.of("RelativeLevelAdjustment", "AbsoluteLevelToSet")),
          Map.entry("ActionDealDamage", Set.of("BaseDamageAmount", "BaseDamageType")),
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
          Map.entry(
              "ActionRunForcedAnimationOnce",
              Set.of(
                  "PlaybackDuration", "CustomStateNumber", "PointToInstigator", "ForcedDuration")),
          Map.entry("ActionSpawn", spawnColumns()),
          Map.entry("ActionSpawnToLocation", spawnColumns()));

  /** The spawn columns: the character branch's, and the three it never reads. */
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
        // Not read by the character branch: the offsets are the area-effect branch's and the spawn
        // time is the buff branch's.
        "OffsetX",
        "OffsetY",
        "SpawnTime");
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
                    expression(f.get("Condition")),
                    expressions(f.get("PerActionConditions")));
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
            case "ActionInterval" ->
                new Interval(
                    shared,
                    integer(f, "Interval"),
                    integer(f, "StartCounterAt"),
                    action(f.get("ActionToExecute")),
                    f.has("PauseTag") ? tagMask(f.get("PauseTag").asText()) : 0,
                    binding.tags(),
                    // The spawn speed would set the rate; with no buffs it is the usual 100.
                    () -> 100);
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
            case "ActionSetCharacterLevel" ->
                new SetCharacterLevel(
                    shared,
                    integer(f, "RelativeLevelAdjustment"),
                    f.path("AbsoluteLevelToSet").asInt(1));
            case "ActionDealDamage" ->
                new DealDamage(
                    shared, integer(f, "BaseDamageAmount"), damageType(f.get("BaseDamageType")));
            case "ActionSpawn", "ActionSpawnToLocation" ->
                new SpawnCharacters(shared, spawn(name, type, f));
            case "ActionPlayEffect" -> new InertAction(shared, lasting(name, f.get("EffectFlags")));
            case "ActionRunForcedAnimationOnce" -> new InertAction(shared);
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

    /** Refuses a row that sets a column its class does not read, show or share. */
    private void checkColumns(GameAction row) {
      Set<String> reads = READS.getOrDefault(row.classType(), Set.of());
      row.fields()
          .fieldNames()
          .forEachRemaining(
              column -> {
                if (!SHARED.contains(column)
                    && !PRESENTATION.contains(column)
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

    /** The columns every row shares. */
    private ActionRow shared(GameAction row) {
      JsonNode f = row.fields();
      return ActionRow.builder()
          .name(row.name())
          .phase(integer(f, "UpdatePhase"))
          .delayMs(integer(f, "ActionDelay"))
          .singleton(f.path("Singleton").asBoolean(false))
          .nextAction(action(f.get("NextAction")))
          .nextActionWait(f.path("NextActionWait").asBoolean(false))
          .tags(f.has("GameTagsToSet") ? tagMask(f.get("GameTagsToSet").asText()) : 0)
          .executeIf(expression(f.get("ExecuteIfTrue")))
          .forceStopIf(expression(f.get("ForceStopIfTrue")))
          .pausedIf(expression(f.get("ActionPausedIfTrue")))
          .abortIfInstigatorDies(f.path("AbortIfInstigatorDies").asBoolean(true))
          .build();
    }

    /** A character spawn row's columns; any other spawn type is refused. */
    private SpawnRow spawn(String name, String type, JsonNode f) {
      String spawnType = f.path("SpawnType").asText("");
      if (!spawnType.equals("CharacterType")) {
        throw new UnsupportedOperationException(
            name + " spawns " + spawnType + ", which is not modelled");
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
