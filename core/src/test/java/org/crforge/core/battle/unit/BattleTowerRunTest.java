package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.card.UnitDataMapper;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Drives the runs in which the towers fight back through {@link Battle} and compares them with the
 * reference runs, tick for tick.
 *
 * <p>Three references. In the first a Knight walks up the left lane: the princess tower in front of
 * it locks on at 130, fires its first arrow at 145 and one every sixteen ticks after, each arrow
 * taking 128 off the Knight, which reaches the tower, lands five hits of its own and dies to the
 * fourteenth arrow at 357. In the second a Musketeer stops short of the same tower and fires four
 * shots before the tower's sixth arrow kills it at 237. In the third a Wizard fires three
 * fireballs, whose impacts damage everything within their radius - the tower alone, as the other
 * towers stand outside it - before the tower kills it at 237. The king towers stay out of all
 * three: nothing damages a king tower and no princess tower falls, so neither king is activated.
 *
 * <p>Reference tick {@code n} is battle step {@code n + 1}, as in {@link BattleKillRunTest}, and
 * the same one-tick deploying correction and end-of-step reference allowance apply. The towers are
 * in the holder from the first step, one step before the unit's first tick, so they are visited
 * once more than the reference visits them; nothing is in any tower's range on that step.
 */
class BattleTowerRunTest {

  /** A Knight on the left lane, shot down by the princess tower in front of it. */
  static final String KNIGHT_REFERENCE = "/pathfinding/golden/tower_vs_knight_left.json";

  /** A Musketeer on the left lane, trading shots with the princess tower in front of it. */
  static final String MUSKETEER_REFERENCE = "/pathfinding/golden/musketeer_vs_tower.json";

  /** A Wizard on the left lane, whose fireballs damage everything in their radius. */
  static final String WIZARD_REFERENCE = "/pathfinding/golden/wizard_vs_tower.json";

  /**
   * The Knight again with the towers at the first level: it destroys the princess tower, which
   * wakes the king tower, and dies under the fire of three towers.
   */
  static final String LEVEL_ONE_REFERENCE = "/pathfinding/golden/tower_vs_knight_left_level1.json";

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {KNIGHT_REFERENCE, MUSKETEER_REFERENCE, WIZARD_REFERENCE, LEVEL_ONE_REFERENCE})
  void theWholeRunMatchesTheReferenceTickForTick(String resource) {
    JsonNode reference = BattleMusketeerRunTest.load(resource);
    List<JsonNode> records = BattleMusketeerRunTest.records(reference);
    Standard1v1Battle match = new Standard1v1Battle(reference.get("tower_level").asInt());
    Battle battle = match.getBattle();
    CharacterEntity unit = deploy(match, reference);

    battle.step();
    for (int i = 0; i < records.size(); i++) {
      battle.step();
      JsonNode record = records.get(i);
      String where = "reference tick " + record.get("tick").asInt();

      assertThat(unit.getView().getX()).as("%s x", where).isEqualTo(record.get("x").asInt());
      assertThat(unit.getView().getY()).as("%s y", where).isEqualTo(record.get("y").asInt());
      assertThat(unit.getView().getState())
          .as("%s state", where)
          .isEqualTo(BattleGoldenTrajectoryTest.expectedState(records, i));
      assertThat(BattleMusketeerRunTest.referenceName(unit))
          .as("%s reference", where)
          .isEqualTo(BattleMusketeerRunTest.expectedReference(record));
      assertThat(unit.getUnit().movement().getRoute().size())
          .as("%s route length", where)
          .isEqualTo(record.get("route").asInt());
      if (!record.get("speed").isNull()) {
        assertThat(unit.getSpeedBudget())
            .as("%s movement budget", where)
            .isEqualTo(record.get("speed").asInt());
      }
      assertThat(BattleMusketeerRunTest.referenceHitPoints(unit))
          .as("%s hit points of the reference", where)
          .isEqualTo(
              BattleMusketeerRunTest.expectedReference(record) == null
                  ? null
                  : record.get("hp").asInt());
      assertThat(unit.getHitPoints().getHitPoints())
          .as("%s the unit's own hit points", where)
          .isEqualTo(record.get("own_hp").asInt());
    }

    assertThat(unit.getHitPoints().getHitPoints()).as("the unit is dead").isZero();
    assertThat(battle.getHolder().entities())
        .as("the unit has left the holder in the tick it died")
        .doesNotContain(unit);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {KNIGHT_REFERENCE, MUSKETEER_REFERENCE, WIZARD_REFERENCE, LEVEL_ONE_REFERENCE})
  void everyLaunchImpactHitAndDeathFallsOnTheReferenceTick(String resource) {
    JsonNode reference = BattleMusketeerRunTest.load(resource);
    List<String> expected = new ArrayList<>();
    for (JsonNode event : reference.get("events")) {
      expected.add(eventLine(event));
    }

    Standard1v1Battle match = new Standard1v1Battle(reference.get("tower_level").asInt());
    Battle battle = match.getBattle();
    CharacterEntity unit = deploy(match, reference);
    int[] currentTick = {-1};
    List<String> events = new ArrayList<>();
    match.getWorld().addObserver(eventCollector(currentTick, events));

    battle.step();
    int lastTick = BattleMusketeerRunTest.records(reference).size() - 1;
    for (int tick = 0; tick <= lastTick; tick++) {
      currentTick[0] = tick;
      battle.step();
    }

    assertThat(events)
        .as("every launch, impact, hit and death, in the order the battle made them")
        .containsExactlyElementsOf(expected);
    assertThat(battle.getHolder().entities()).doesNotContain(unit);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {KNIGHT_REFERENCE, MUSKETEER_REFERENCE, WIZARD_REFERENCE, LEVEL_ONE_REFERENCE})
  void everyProjectileFliesThroughTheReferencePositions(String resource) {
    JsonNode reference = BattleMusketeerRunTest.load(resource);
    List<String> expected = new ArrayList<>();
    for (JsonNode position : reference.get("projectiles")) {
      expected.add(position.toString());
    }

    Standard1v1Battle match = new Standard1v1Battle(reference.get("tower_level").asInt());
    Battle battle = match.getBattle();
    deploy(match, reference);
    int[] currentTick = {-1};
    List<String> positions = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void afterPostHooks(
                  int tick, List<WorldEntity> present, List<ProjectileEntity> inFlight) {
                if (currentTick[0] < 0) {
                  return;
                }
                for (ProjectileEntity projectile : inFlight) {
                  if (!projectile.isReleased()) {
                    positions.add(
                        "[%d,%d,%d,%d,%d]"
                            .formatted(
                                currentTick[0],
                                projectile.getId(),
                                projectile.getX(),
                                projectile.getY(),
                                projectile.getZ()));
                  }
                }
              }
            });

    battle.step();
    int lastTick = BattleMusketeerRunTest.records(reference).size() - 1;
    for (int tick = 0; tick <= lastTick; tick++) {
      currentTick[0] = tick;
      battle.step();
    }

    assertThat(positions)
        .as("every projectile's position after each of its flight steps")
        .containsExactlyElementsOf(expected);
  }

  /** Collects every launch, impact, hit and death as the reference lists them. */
  static WorldObserver eventCollector(int[] currentTick, List<String> events) {
    return new WorldObserver() {
      @Override
      public void damageDealt(int tick, WorldEntity target, int damage, DamageResult result) {
        if (currentTick[0] < 0 || !result.landed()) {
          return;
        }
        events.add(
            "%d hit %s %d %d"
                .formatted(
                    currentTick[0], target.name(), damage, target.getTargetView().getHitPoints()));
        if (result.died()) {
          events.add("%d death %s".formatted(currentTick[0], target.name()));
        }
      }

      @Override
      public void projectileLaunched(int tick, ProjectileEntity projectile) {
        if (currentTick[0] < 0) {
          return;
        }
        events.add(
            "%d launch %s %s %s %s %d %d %d aim %d %d %d"
                .formatted(
                    currentTick[0],
                    projectile.name(),
                    projectile.getData().name(),
                    projectile.getOwner() == null ? null : projectile.getOwner().name(),
                    projectile.getTarget() == null ? null : projectile.getTarget().name(),
                    projectile.getX(),
                    projectile.getY(),
                    projectile.getZ(),
                    projectile.getAimX(),
                    projectile.getAimY(),
                    projectile.getAimZ()));
      }

      @Override
      public void projectileImpacted(
          int tick,
          ProjectileEntity projectile,
          WorldEntity target,
          int damage,
          DamageResult result) {
        if (currentTick[0] < 0) {
          return;
        }
        events.add(
            "%d impact %s %s %d %d %d %d %d"
                .formatted(
                    currentTick[0],
                    projectile.name(),
                    target.name(),
                    damage,
                    target.getTargetView().getHitPoints(),
                    projectile.getX(),
                    projectile.getY(),
                    projectile.getZ()));
        if (result.died()) {
          events.add("%d death %s".formatted(currentTick[0], target.name()));
        }
      }
    };
  }

  /** One reference event in the collector's layout. */
  static String eventLine(JsonNode event) {
    int tick = event.get("tick").asInt();
    String kind = event.get("event").asText();
    return switch (kind) {
      case "hit" ->
          "%d hit %s %d %d"
              .formatted(
                  tick,
                  event.get("target").asText(),
                  event.get("damage").asInt(),
                  event.get("hp").asInt());
      case "death" -> "%d death %s".formatted(tick, event.get("target").asText());
      case "launch" ->
          "%d launch %s %s %s %s %d %d %d aim %d %d %d"
              .formatted(
                  tick,
                  event.get("projectile").asText(),
                  event.get("config").asText(),
                  event.get("owner").isNull() ? null : event.get("owner").asText(),
                  event.get("target").isNull() ? null : event.get("target").asText(),
                  event.get("x").asInt(),
                  event.get("y").asInt(),
                  event.get("z").asInt(),
                  event.get("aim").get(0).asInt(),
                  event.get("aim").get(1).asInt(),
                  event.get("aim_z").asInt());
      case "impact" ->
          "%d impact %s %s %d %d %d %d %d"
              .formatted(
                  tick,
                  event.get("projectile").asText(),
                  event.get("target").asText(),
                  event.get("damage").asInt(),
                  event.get("hp").asInt(),
                  event.get("x").asInt(),
                  event.get("y").asInt(),
                  event.get("z").asInt());
      default -> throw new IllegalStateException("unknown event " + kind);
    };
  }

  /** Places the reference's unit at the reference's level, side and position on tick 0. */
  static CharacterEntity deploy(Standard1v1Battle match, JsonNode reference) {
    String card = reference.get("card").asText().toLowerCase(Locale.ROOT);
    return match.deploy(
        0,
        UnitDataMapper.toUnitData(Objects.requireNonNull(CardRegistry.get(card), card)),
        reference.get("level").asInt(),
        reference.get("side").asInt(),
        reference.get("deploy").get(0).asInt(),
        reference.get("deploy").get(1).asInt());
  }
}
