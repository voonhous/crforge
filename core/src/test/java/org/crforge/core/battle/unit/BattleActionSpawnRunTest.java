package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.BattleCommand;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.target.TargetView;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Plays the twenty-eight runs in which an action, a death or a building spawns characters through
 * {@link Battle} and holds the battle to them tick for tick.
 *
 * <p>The rows are the game's own, built from its action rows. Four runs give the battle an action
 * owner: an entity with an action holder, a position, a side and a level and nothing else, on which
 * the row is scheduled in the command pass of its tick. In {@code witch_hooks} a Witch placed
 * directly runs its row's own starting action, whose rounds spawn Skeletons around it and link each
 * into its group. In {@code bush_goblins} a group spawns two Bush Goblins to either side of a
 * bottom-side owner a tick apart, the second pushed one unit off the first as it is registered. In
 * {@code brawler_goblins} a top-side owner spawns four Goblin Brawlers, the side flipping both axes
 * of the location. In {@code gift_knight} the owner spawns a Knight on itself that walks at once:
 * its registration visit takes its target and a first step. {@code abort_instigator} drops a spawn
 * that Knight caused when it dies. In {@code tombstone_death_hook} a Wizard's projectile kills a
 * Tombstone while it deploys, and the Tombstone's death action, scheduled as it dies with the
 * projectile as its cause, runs in its phase-3 pass and spawns SkeletonKing on it, a champion
 * handed over to its side. In {@code goblin_wave} the Goblin Hero's second-wave rows are scheduled
 * on a Knight of each side, which has a run of its own: each spawns four deploying goblins around
 * its Knight, placed by the Knight's team and the middle of the arena, and links each into the
 * Knight's group. In {@code golemite_convert} a baby golemite turns into an Elixir Golem, which
 * dies on tick 110 and spawns two golemites on the ring of its death spawn radius, each immune for
 * its first ticks and killed by a tower later. In {@code golemite_death_damage} a Golemite's death
 * damages a tower and a Knight around it and pushes the Knight away. In {@code archer_ev1_vs_tower}
 * and {@code archer_ev1_knight} the evolved Archer's starting-attack row picks its attack sequence
 * index by whether its target is within 4500: its long shots launch the double-damage arrow of its
 * second entry, and once a Knight closes in it goes back to its first. In {@code
 * area_effect_direct} two area effects placed directly hit a Knight and a tower, one of them
 * pushing the Knight, and leave as their countdown runs out. In {@code gift_select} two owners each
 * schedule the gift delivery's select on the same tick, from a random state of the run's own: each
 * select draws its part as it is scheduled, in the command pass, the first owner's draw first, and
 * the part it chose spawns its unit in that owner's phase-1 pass.
 *
 * <p>Four runs place a building directly with the towers fighting. In {@code cannon_knight} a
 * Cannon deploys, stands, takes the default tower as its reference on the tick after and locks on a
 * Knight that walks into range, firing until the Knight destroys it, its hit points falling by its
 * lifetime's decay meanwhile; in {@code mortar_knight} a Mortar drops the Knight once it comes
 * inside its minimum range. In {@code tombstone_life} a Tombstone spawns Skeletons in front of it
 * in waves of two from the end of its deploy, until its decay kills it and its death spawns four
 * more on the same point; {@code goblin_hut_life} does the same with a Goblin Hut's three Spear
 * Goblins and its one death spawn on its own point.
 *
 * <p>Every spawned child is registered inside the pass that made it, joins the live list at the
 * tick's closing cleanup and is first visited on the next tick. A child of an action or a death
 * cannot be targeted until its sixth state visit, so the towers lock on it six ticks late; a
 * building's spawner gives its children no such immunity.
 *
 * <p>Three runs place a spell's area effect directly on a walking or attacking Knight, and hold its
 * buffs. In {@code rage_knight} Rage, with its chained RageDamage, refreshes a 1000 ms buff on the
 * Knight every six ticks: from the tick after the first, the Knight walks at 78 and steps its
 * attack timer by 65, and it goes back to 60 and 50 once the last refresh runs out. In {@code
 * zap_knight} Zap's 500 ms stun drops the attacking Knight's reference and switches its targeting
 * off on the tick it lands, and the first gate after the buff goes switches it back on. In {@code
 * poison_knight_tower} Poison, stacking by its source, hits a red Knight for 92 and a princess
 * tower for 23 every twenty visits and slows the Knight to 51.
 *
 * <p>Five runs hold air units, created at their flying height, routed to one node, crossing water
 * and meeting only units on their side of height 0: {@code minion_musketeer}, a Minion shot down by
 * a Musketeer while a Knight cannot reach it; {@code balloon_tower}, a Balloon and its bomb, which
 * dies on the ground as its deploy ends; {@code balloons_cross}, two Balloons crossing over a
 * Knight with the towers passive; {@code lava_hound_river}, a Lava Hound dying over the river and
 * its pups flying back to their ring points; and {@code baby_dragon_left}, a Baby Dragon firing
 * from its height. A further unit is placed at its own level where the run gives one.
 */
class BattleActionSpawnRunTest {

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "bush_goblins",
        "brawler_goblins",
        "gift_knight",
        "abort_instigator",
        "witch_hooks",
        "tombstone_death_hook",
        "gift_select",
        "goblin_wave",
        "golemite_convert",
        "golemite_death_damage",
        "archer_ev1_vs_tower",
        "archer_ev1_knight",
        "area_effect_direct",
        "area_effect_death",
        "golem_death_pushback",
        "giant_skeleton_bomb",
        "cannon_knight",
        "tombstone_life",
        "goblin_hut_life",
        "mortar_knight",
        "rage_knight",
        "zap_knight",
        "poison_knight_tower",
        "minion_musketeer",
        "balloon_tower",
        "balloons_cross",
        "lava_hound_river",
        "baby_dragon_left"
      })
  void theRunMatchesTheReferenceTickForTick(String name) {
    JsonNode reference = BattleMusketeerRunTest.load("/pathfinding/golden/" + name + ".json");
    // A run made without the towers fighting names no tower level; its towers stand at 11.
    Standard1v1Battle match =
        new Standard1v1Battle(
            GameData.tables(),
            reference.path("tower_level").asInt(11),
            reference.path("towers_attack").asBoolean(false));
    Battle battle = match.getBattle();
    // A run that draws starts the battle's random source from its own state.
    if (reference.has("seed")) {
      match.getWorld().seed(reference.get("seed").asInt());
    }
    // The random state each drawing tick leaves behind: the last draw's.
    Map<Integer, Long> stateAfter = new HashMap<>();
    for (JsonNode draw : reference.path("draws")) {
      stateAfter.put(draw.get("tick").asInt(), draw.get("state").get(1).asLong());
    }

    int[] currentTick = {-1};
    // A run is listed as it starts and a spawn as it is made, so the runs and the spawns are
    // compared as two lists, each in order.
    List<String> actions = new ArrayList<>();
    List<String> spawns = new ArrayList<>();
    List<String> dropping = new ArrayList<>();
    // A run with a unit of its own places it, its further units and its schedules on them as the
    // tower runs do; one without action owners places its units directly. Each unit starts its
    // row's own starting action as it is placed.
    List<CharacterEntity> placed = new ArrayList<>();
    if (!reference.get("card").isNull()) {
      placed.addAll(BattleTowerRunTest.deployAll(match, reference));
    } else if (!reference.has("action_owners")) {
      for (JsonNode u : reference.get("units")) {
        placed.add(
            match.deploy(
                u.get("tick").asInt(),
                GameData.unit(u.get("card").asText()),
                u.path("level").asInt(reference.get("level").asInt()),
                u.get("side").asInt(),
                u.get("deploy").get(0).asInt(),
                u.get("deploy").get(1).asInt(),
                u.get("name").asText()));
      }
    }
    for (CharacterEntity unit : placed) {
      unit.actionHolder().setListener(listener(unit.name(), currentTick, actions, dropping));
    }
    // The area effects a run places directly, each in the command pass of its tick.
    for (JsonNode a : reference.path("area_effects")) {
      if (a.get("event").asText().equals("created") && a.get("how").asText().equals("placed")) {
        match.placeAreaEffect(
            a.get("tick").asInt(),
            a.get("row").asText(),
            reference.get("level").asInt(),
            a.get("side").asInt(),
            a.get("x").asInt(),
            a.get("y").asInt(),
            a.get("area_effect").asText());
      }
    }
    for (JsonNode o : reference.path("action_owners")) {
      ActionOwnerEntity owner =
          match.addActionOwner(
              o.get("name").asText(),
              o.get("side").asInt(),
              o.get("x").asInt(),
              o.get("y").asInt(),
              o.get("level").asInt());
      owner.actionHolder().setListener(listener(owner.name(), currentTick, actions, dropping));
      for (JsonNode s : o.get("schedule")) {
        // The row is built from the game's own action rows, for the owner it runs on. A schedule
        // may name another entity as its cause, which is found by name when the schedule is made.
        BattleAction row = GameData.actions().build(s.get("action").asText(), owner.binding());
        if (s.has("instigator")) {
          String cause = s.get("instigator").asText();
          battle.queue(
              new BattleCommand() {
                @Override
                public int tick() {
                  return s.get("tick").asInt();
                }

                @Override
                public void execute(Battle target) {
                  WorldEntity instigator = named(target, cause);
                  owner
                      .actionHolder()
                      .schedule(row, ActionHolder.OWN_DELAY, false, instigator.actionHolder());
                }
              });
        } else {
          match.scheduleAction(s.get("tick").asInt(), owner, row);
        }
      }
    }

    List<String> events = new ArrayList<>();
    List<String> positions = new ArrayList<>();
    List<String> groups = new ArrayList<>();
    List<String> deaths = new ArrayList<>();
    Map<String, Integer> spawnTicks = new HashMap<>();
    match.getWorld().addObserver(BattleTowerRunTest.eventCollector(currentTick, events));
    List<String> areaEffects = new ArrayList<>();
    match.getWorld().addObserver(areaEffectLog(currentTick, areaEffects));
    // What each buff did.
    List<String> buffLog = new ArrayList<>();
    match.getWorld().addObserver(buffLog(currentTick, buffLog));
    // What each building's spawner and lifetime did.
    List<String> buildingLog = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void spawnerFired(
                  int tick,
                  CharacterEntity spawner,
                  String row,
                  int count,
                  int radius,
                  int timerAfter,
                  int waveMade) {
                buildingLog.add(
                    "%d spawner %s %s %d %d %d %d"
                        .formatted(
                            currentTick[0],
                            spawner.name(),
                            row,
                            count,
                            radius,
                            timerAfter,
                            waveMade));
              }

              @Override
              public void decayDied(int tick, WorldEntity entity, int hitPointsBefore) {
                buildingLog.add(
                    "%d decay_death %s %d"
                        .formatted(currentTick[0], entity.name(), hitPointsBefore));
              }
            });
    // An area effect's own runs are listed like any owner's, from its creation.
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void areaEffectCreated(
                  int tick, AreaEffectEntity a, String how, BattleEntity source) {
                a.actionHolder().setListener(listener(a.name(), currentTick, actions, dropping));
              }
            });
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void characterSpawned(
                  int tick, SpawnHost owner, CharacterEntity child, int createdX, int createdY) {
                spawnTicks.put(child.name(), currentTick[0]);
                spawns.add(
                    "%d spawn %s %s %d at %d %d state %d deploy %d lane %d hp %d then %d %d %d"
                        .formatted(
                            currentTick[0],
                            owner.name(),
                            child.name(),
                            child.getId(),
                            createdX,
                            createdY,
                            child.getView().getState(),
                            child.getView().getDeployCountdown(),
                            child.getView().getLane(),
                            child.getHitPoints() == null ? 0 : child.getHitPoints().getHitPoints(),
                            child.getView().getX(),
                            child.getView().getY(),
                            child.getView().getState()));
              }

              @Override
              public void groupLinked(int tick, CharacterEntity source, CharacterEntity child) {
                groups.add(groupLine(currentTick[0], "group_link", source, child));
              }

              @Override
              public void groupUnlinked(int tick, CharacterEntity source, CharacterEntity child) {
                groups.add(groupLine(currentTick[0], "group_unlink", source, child));
              }

              @Override
              public void deathHooksScheduled(
                  int tick,
                  WorldEntity dying,
                  BattleEntity attacker,
                  int side,
                  List<String> hooks,
                  boolean inPendingPass) {
                deaths.add(
                    "%d death_hooks %s %s %d %s %s"
                        .formatted(
                            currentTick[0],
                            dying.name(),
                            attackerName(attacker),
                            side,
                            hooks,
                            inPendingPass));
              }

              @Override
              public void championHandedOver(int tick, SpawnHost source, CharacterEntity child) {
                deaths.add(
                    "%d champion_handover %s %s"
                        .formatted(currentTick[0], source.name(), child.name()));
              }

              @Override
              public void afterPostHooks(
                  int tick, List<WorldEntity> present, List<ProjectileEntity> inFlight) {
                for (ProjectileEntity p : inFlight) {
                  if (!p.isReleased()) {
                    positions.add(
                        "[%d,%d,%d,%d,%d]"
                            .formatted(currentTick[0], p.getId(), p.getX(), p.getY(), p.getZ()));
                  }
                }
              }
            });

    Map<Integer, List<JsonNode>> records = BattleTowerRunTest.otherRecordsByTick(reference);
    // The run's own unit's records, when it has one, are held to its outside like the others. Its
    // record of the last deploying tick is taken before the state visit that ends the deployment.
    if (!reference.get("card").isNull()) {
      List<JsonNode> own = BattleMusketeerRunTest.records(reference);
      for (int i = 0; i < own.size(); i++) {
        ObjectNode named = own.get(i).deepCopy();
        named.put("name", placed.get(0).name());
        named.put("state", BattleGoldenTrajectoryTest.expectedState(own, i));
        records.computeIfAbsent(named.get("tick").asInt(), t -> new ArrayList<>()).add(named);
      }
    }
    int lastTick = records.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
    for (JsonNode event : reference.get("events")) {
      lastTick = Math.max(lastTick, event.get("tick").asInt());
    }
    Map<String, CharacterEntity> units = new HashMap<>();
    Map<String, String> towerStates = new HashMap<>();
    List<String> locks = new ArrayList<>();
    for (int tick = 0; tick <= lastTick; tick++) {
      currentTick[0] = tick;
      battle.step();
      if (stateAfter.containsKey(tick)) {
        assertThat(Integer.toUnsignedLong(match.getWorld().getRandom().getState()))
            .as("tick %d: the random state after its draws", tick)
            .isEqualTo(stateAfter.get(tick));
      }
      for (BattleEntity entity : battle.getHolder().entities()) {
        if (entity instanceof CharacterEntity c) {
          units.putIfAbsent(c.name(), c);
        }
        // A building locks on as a tower does.
        if (entity instanceof TowerEntity
            || entity instanceof CharacterEntity c && c.getData().building()) {
          WorldEntity tower = (WorldEntity) entity;
          // A lock is the tower attacking a target it was not attacking at the end of the last
          // step: entering the attacking state, or, still in it after its target left, taking the
          // next one.
          String held = tower.getView().getState() + " " + referenceName(tower);
          String before = towerStates.put(tower.name(), held);
          if (tower.getView().getState() == GridEntityState.ATTACKING
              && referenceName(tower) != null
              && !held.equals(before)) {
            locks.add(tick + " " + tower.name() + " " + referenceName(tower));
          }
        }
      }
      for (JsonNode record : records.getOrDefault(tick, List.of())) {
        CharacterEntity unit = units.get(record.get("name").asText());
        assertThat(unit).as("tick %d: %s is in the battle", tick, record.get("name")).isNotNull();
        assertRecord(battle, unit, record, tick, spawnTicks);
      }
    }

    List<String> expectedActions = new ArrayList<>();
    List<String> expectedSpawns = new ArrayList<>();
    for (JsonNode a : reference.path("actions")) {
      String kind = a.get("event").asText();
      if (kind.equals("run")) {
        expectedActions.add(
            "%d run %s %s %s"
                .formatted(
                    a.get("tick").asInt(),
                    a.get("owner").asText(),
                    a.get("action").asText(),
                    a.get("phase").isNull() ? "at once" : a.get("phase").asText()));
      } else if (kind.equals("spawn")) {
        JsonNode after = a.get("after_registration");
        expectedSpawns.add(
            "%d spawn %s %s %d at %d %d state %d deploy %d lane %d hp %d then %d %d %d"
                .formatted(
                    a.get("tick").asInt(),
                    a.get("owner").asText(),
                    a.get("unit").asText(),
                    a.get("id").asInt(),
                    a.get("created").get(0).asInt(),
                    a.get("created").get(1).asInt(),
                    a.get("state").asInt(),
                    a.get("deploy").asInt(),
                    a.get("lane").asInt(),
                    a.get("hp").asInt(),
                    after.get(0).asInt(),
                    after.get(1).asInt(),
                    after.get(2).asInt()));
      }
    }
    assertThat(actions).as("every run of an action").containsExactlyElementsOf(expectedActions);
    List<String> expectedDrops = new ArrayList<>();
    for (JsonNode a : reference.path("actions")) {
      if (a.get("event").asText().equals("dropped")) {
        expectedDrops.add(
            "%d dropped %s %s %d"
                .formatted(
                    a.get("tick").asInt(),
                    a.get("owner").asText(),
                    a.get("action").asText(),
                    a.get("ticks_left").asInt()));
      }
    }
    assertThat(dropping)
        .as("every pending action dropped as its instigator left")
        .containsExactlyElementsOf(expectedDrops);
    assertThat(spawns).as("every spawn").containsExactlyElementsOf(expectedSpawns);
    List<String> expectedGroups = new ArrayList<>();
    for (JsonNode a : reference.path("actions")) {
      String kind = a.get("event").asText();
      if (kind.equals("group_link") || kind.equals("group_unlink")) {
        List<String> chain = new ArrayList<>();
        a.get("chain").forEach(linked -> chain.add(linked.asText()));
        expectedGroups.add(
            "%d %s %s %s %s"
                .formatted(
                    a.get("tick").asInt(),
                    kind,
                    a.get("owner").asText(),
                    a.get("unit").asText(),
                    chain));
      }
    }
    assertThat(groups)
        .as("every child linked into its source's group and unlinked as it leaves")
        .containsExactlyElementsOf(expectedGroups);
    List<String> expectedDeaths = new ArrayList<>();
    for (JsonNode a : reference.path("actions")) {
      String kind = a.get("event").asText();
      if (kind.equals("death_hooks")) {
        List<String> hooks = new ArrayList<>();
        a.get("actions").forEach(hook -> hooks.add(hook.asText()));
        expectedDeaths.add(
            "%d death_hooks %s %s %d %s %s"
                .formatted(
                    a.get("tick").asInt(),
                    a.get("owner").asText(),
                    a.get("attacker").asText(),
                    a.get("side").asInt(),
                    hooks,
                    a.get("in_pending").asBoolean()));
      } else if (kind.equals("champion_handover")) {
        expectedDeaths.add(
            "%d champion_handover %s %s"
                .formatted(a.get("tick").asInt(), a.get("owner").asText(), a.get("unit").asText()));
      }
    }
    assertThat(deaths)
        .as("every death's hooks as they are scheduled, and every champion handed over")
        .containsExactlyElementsOf(expectedDeaths);

    List<String> expectedLocks = new ArrayList<>();
    for (JsonNode event : reference.path("tower_events")) {
      if (event.get("event").asText().equals("lock")) {
        expectedLocks.add(
            event.get("tick").asInt()
                + " "
                + event.get("tower").asText()
                + " "
                + event.get("target").asText());
      }
    }
    assertThat(locks).as("every tower's lock").containsExactlyElementsOf(expectedLocks);

    assertThat(areaEffects)
        .as("what every area effect did")
        .containsExactlyElementsOf(expectedAreaEffects(reference));

    List<String> expectedBuildingLog = new ArrayList<>();
    for (JsonNode b : reference.path("building_log")) {
      String kind = b.get("event").asText();
      if (kind.equals("spawner")) {
        expectedBuildingLog.add(
            "%d spawner %s %s %d %d %d %d"
                .formatted(
                    b.get("tick").asInt(),
                    b.get("building").asText(),
                    b.get("row").asText(),
                    b.get("count").asInt(),
                    b.get("radius").asInt(),
                    b.get("timer_after").asInt(),
                    b.get("burst").asInt()));
      } else if (kind.equals("decay_death")) {
        expectedBuildingLog.add(
            "%d decay_death %s %d"
                .formatted(
                    b.get("tick").asInt(), b.get("entity").asText(), b.get("hp_before").asInt()));
      }
    }
    assertThat(buildingLog)
        .as("every spawner firing and every death by a lifetime's decay")
        .containsExactlyElementsOf(expectedBuildingLog);

    assertThat(buffLog)
        .as("every area buff, and every buff applied, refreshed, removed and dealing damage")
        .containsExactlyElementsOf(expectedBuffLog(reference));

    List<String> expectedEvents = new ArrayList<>();
    for (JsonNode event : reference.get("events")) {
      expectedEvents.add(BattleTowerRunTest.eventLine(event));
    }
    assertThat(events)
        .as("every launch, impact, hit and death")
        .containsExactlyElementsOf(expectedEvents);
    List<String> expectedPositions = new ArrayList<>();
    for (JsonNode p : reference.path("projectiles")) {
      expectedPositions.add(p.toString());
    }
    assertThat(positions)
        .as("every projectile position")
        .containsExactlyElementsOf(expectedPositions);
  }

  /**
   * Holds a unit to its record, taken as the step ends: position, state, reference and its own hit
   * points, and for a spawned child its elapsed time and deploy countdown, whether it is still
   * immune, and whether it was spawned in this tick. A reference to an entity that left in the
   * step's closing cleanup is not shown by the record, which is taken before it; the reference of a
   * unit leaving in that cleanup is not compared.
   */
  private static void assertRecord(
      Battle battle,
      CharacterEntity unit,
      JsonNode record,
      int tick,
      Map<String, Integer> spawnTicks) {
    String where = "tick " + tick + ": " + unit.name();
    assertThat(unit.getView().getX()).as("%s x", where).isEqualTo(record.get("x").asInt());
    assertThat(unit.getView().getY()).as("%s y", where).isEqualTo(record.get("y").asInt());
    assertThat(unit.getView().getState())
        .as("%s state", where)
        .isEqualTo(record.get("state").asInt());
    String recorded = record.get("ref").isNull() ? null : record.get("ref").asText();
    boolean stillThere =
        battle.getHolder().entities().stream()
            .anyMatch(e -> e instanceof WorldEntity w && w.name().equals(recorded));
    if (battle.getHolder().entities().contains(unit)) {
      assertThat(BattleMusketeerRunTest.referenceName(unit))
          .as("%s reference", where)
          .isEqualTo(stillThere ? recorded : null);
    }
    // A run without the towers fighting does not record its own unit's hit points.
    if (record.hasNonNull("own_hp")) {
      assertThat(unit.getHitPoints() == null ? 0 : unit.getHitPoints().getHitPoints())
          .as("%s own hit points", where)
          .isEqualTo(record.get("own_hp").asInt());
    }
    if (record.path("delay").isNull() || record.path("delay").isMissingNode()) {
      // A unit the run places itself, not a spawned child, has only its outside recorded.
      return;
    }
    assertThat(unit.getView().getDelay())
        .as("%s elapsed time", where)
        .isEqualTo(record.get("delay").asInt());
    assertThat(unit.getView().getDeployCountdown())
        .as("%s deploy countdown", where)
        .isEqualTo(record.get("deploy").asInt());
    assertThat(unit.isSpawnImmune() ? 1 : 0)
        .as("%s immune", where)
        .isEqualTo(record.get("immune").asInt());
    assertThat(spawnTicks.get(unit.name()) == tick)
        .as("%s spawned this tick", where)
        .isEqualTo(record.get("pending").asBoolean());
  }

  /**
   * Records the runs and drops of one holder's actions, named after its owner: a run as it starts,
   * with the pass that took it from the queue, or at once for one that did not wait there.
   */
  private static ActionHolder.Listener listener(
      String owner, int[] currentTick, List<String> actions, List<String> dropping) {
    return new ActionHolder.Listener() {
      @Override
      public void starting(BattleAction action, int phase, boolean queued) {
        actions.add(
            "%d run %s %s %s"
                .formatted(currentTick[0], owner, action.name(), queued ? phase : "at once"));
      }

      @Override
      public void dropped(BattleAction action, int ticksLeft) {
        dropping.add(
            "%d dropped %s %s %d".formatted(currentTick[0], owner, action.name(), ticksLeft));
      }
    };
  }

  /** A link or unlink with the source's group as it stands after it, newest first. */
  private static String groupLine(
      int tick, String kind, CharacterEntity source, CharacterEntity child) {
    List<String> chain = source.group().stream().map(CharacterEntity::name).toList();
    return "%d %s %s %s %s".formatted(tick, kind, source.name(), child.name(), chain);
  }

  /** Lists what each area effect does, in the reference's layout. */
  static WorldObserver areaEffectLog(int[] currentTick, List<String> lines) {
    return new WorldObserver() {
      @Override
      public void areaEffectCreated(int tick, AreaEffectEntity a, String how, BattleEntity source) {
        lines.add(
            "%d created %s %s %d %s %s %d %d %d %d %d"
                .formatted(
                    currentTick[0],
                    a.name(),
                    a.getData().name(),
                    a.getId(),
                    how,
                    sourceName(source),
                    a.side(),
                    a.getX(),
                    a.getY(),
                    a.getPackedLevel(),
                    a.getCountdown()));
      }

      @Override
      public void areaEffectAdmitted(int tick, AreaEffectEntity a) {
        lines.add("%d folded %s".formatted(currentTick[0], a.name()));
      }

      @Override
      public void areaEffectUpdated(
          int tick,
          AreaEffectEntity a,
          int before,
          int after,
          int hits,
          int radius,
          List<Integer> damages) {
        lines.add(
            "%d update %s %d %d hits %d r%d %s"
                .formatted(currentTick[0], a.name(), before, after, hits, radius, damages));
      }

      @Override
      public void areaEffectRemoved(int tick, AreaEffectEntity a) {
        lines.add("%d removed %s %d".formatted(currentTick[0], a.name(), a.getCountdown()));
      }
    };
  }

  /** What an area effect was created from, by name: an arena entity or another area effect. */
  private static String sourceName(BattleEntity source) {
    if (source instanceof WorldEntity w) {
      return w.name();
    }
    return source instanceof AreaEffectEntity a ? a.name() : null;
  }

  /** The reference's area-effect log in the same layout. */
  static List<String> expectedAreaEffects(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode a : reference.path("area_effects")) {
      int tick = a.get("tick").asInt();
      String name = a.get("area_effect").asText();
      switch (a.get("event").asText()) {
        case "created" ->
            expected.add(
                "%d created %s %s %d %s %s %d %d %d %d %d"
                    .formatted(
                        tick,
                        name,
                        a.get("row").asText(),
                        a.get("id").asInt(),
                        a.get("how").asText(),
                        a.get("source").isNull() ? null : a.get("source").asText(),
                        a.get("side").asInt(),
                        a.get("x").asInt(),
                        a.get("y").asInt(),
                        a.get("level").asInt(),
                        a.get("countdown").asInt()));
        case "folded" -> expected.add("%d folded %s".formatted(tick, name));
        case "update" -> {
          List<Integer> damages = new ArrayList<>();
          a.get("damage").forEach(d -> damages.add(d.asInt()));
          expected.add(
              "%d update %s %d %d hits %d r%d %s"
                  .formatted(
                      tick,
                      name,
                      a.get("countdown").get(0).asInt(),
                      a.get("countdown").get(1).asInt(),
                      a.get("hits").asInt(),
                      a.get("radius").asInt(),
                      damages));
        }
        case "removed" ->
            expected.add("%d removed %s %d".formatted(tick, name, a.get("countdown").asInt()));
        default -> throw new IllegalStateException("unknown area effect event " + a);
      }
    }
    return expected;
  }

  /** Lists what each buff does, in the reference's layout. */
  static WorldObserver buffLog(int[] currentTick, List<String> lines) {
    return new WorldObserver() {
      @Override
      public void areaBuff(
          int tick, AreaEffectEntity a, BuffData buff, int time, List<WorldEntity> targets) {
        lines.add(
            "%d area_buff %s %s %d %s"
                .formatted(
                    currentTick[0],
                    a.name(),
                    buff.name(),
                    time,
                    targets.stream().map(WorldEntity::name).toList()));
      }

      @Override
      public void buffApplied(int tick, WorldEntity target, BuffInstance buff) {
        lines.add(
            "%d applied %s %s %s %d %d %s"
                .formatted(
                    currentTick[0],
                    target.name(),
                    buff.getBuff().name(),
                    buff.getKey(),
                    buff.getRemaining(),
                    buff.getPackedLevel(),
                    buff.getSource() == null ? null : buff.getSource().name()));
      }

      @Override
      public void buffRefreshed(int tick, WorldEntity target, BuffInstance buff, int before) {
        lines.add(
            "%d refreshed %s %s %s %d %d %s"
                .formatted(
                    currentTick[0],
                    target.name(),
                    buff.getBuff().name(),
                    buff.getKey(),
                    before,
                    buff.getRemaining(),
                    buff.getSource() == null ? null : buff.getSource().name()));
      }

      @Override
      public void buffRemoved(int tick, WorldEntity target, BuffInstance buff) {
        lines.add(
            "%d removed %s %s %s"
                .formatted(currentTick[0], target.name(), buff.getBuff().name(), buff.getKey()));
      }

      @Override
      public void buffDamaged(
          int tick,
          WorldEntity target,
          BuffInstance buff,
          int damage,
          int hitPointsBefore,
          DamageResult result) {
        lines.add(
            "%d damage %s %d %d"
                .formatted(
                    currentTick[0], target.name(), damage, target.getTargetView().getHitPoints()));
      }
    };
  }

  /** The reference's buff log in the same layout. */
  static List<String> expectedBuffLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode b : reference.path("buffs")) {
      int tick = b.get("tick").asInt();
      String source = b.path("source").isNull() ? null : b.path("source").asText();
      switch (b.get("event").asText()) {
        case "area_buff" -> {
          List<String> targets = new ArrayList<>();
          b.get("targets").forEach(t -> targets.add(t.asText()));
          expected.add(
              "%d area_buff %s %s %d %s"
                  .formatted(
                      tick,
                      b.get("area_effect").asText(),
                      b.get("buff").asText(),
                      b.get("time").asInt(),
                      targets));
        }
        case "applied" ->
            expected.add(
                "%d applied %s %s %s %d %d %s"
                    .formatted(
                        tick,
                        b.get("target").asText(),
                        b.get("buff").asText(),
                        b.get("key").asText(),
                        b.get("time").asInt(),
                        b.get("level").asInt(),
                        source));
        case "refreshed" ->
            expected.add(
                "%d refreshed %s %s %s %d %d %s"
                    .formatted(
                        tick,
                        b.get("target").asText(),
                        b.get("buff").asText(),
                        b.get("key").asText(),
                        b.get("remaining").get(0).asInt(),
                        b.get("remaining").get(1).asInt(),
                        source));
        case "removed" ->
            expected.add(
                "%d removed %s %s %s"
                    .formatted(
                        tick,
                        b.get("target").asText(),
                        b.get("buff").asText(),
                        b.get("key").asText()));
        case "damage" ->
            expected.add(
                "%d damage %s %d %d"
                    .formatted(
                        tick,
                        b.get("target").asText(),
                        b.get("damage").asInt(),
                        b.get("hp").asInt()));
        default -> throw new IllegalStateException("unknown buff event " + b);
      }
    }
    return expected;
  }

  /** An attacker as the reference names it: a projectile by its id. */
  private static String attackerName(BattleEntity attacker) {
    if (attacker instanceof WorldEntity entity) {
      return entity.name();
    }
    return attacker instanceof ProjectileEntity ? "proj_" + attacker.getId() : null;
  }

  /** The arena entity of the given name. */
  private static WorldEntity named(Battle battle, String name) {
    return battle.getHolder().entities().stream()
        .filter(e -> e instanceof WorldEntity w && w.name().equals(name))
        .map(WorldEntity.class::cast)
        .findFirst()
        .orElseThrow();
  }

  private static String referenceName(WorldEntity tower) {
    TargetView reference = tower.getTargeting().getReference();
    return reference == null ? null : reference.name();
  }
}
