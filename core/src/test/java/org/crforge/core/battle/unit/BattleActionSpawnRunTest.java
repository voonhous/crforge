package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.BattleCommand;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.target.TargetView;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Plays the fifty runs in which an action, a death, a building or a unit's own spawner spawns
 * characters, or a unit charges, jumps or dashes, through {@link Battle} and holds the battle to
 * them tick for tick.
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
 * <p>Six runs hold air units, created at their flying height, routed to one node, crossing water
 * and meeting only units on their side of height 0: {@code minion_musketeer}, a Minion shot down by
 * a Musketeer while a Knight cannot reach it; {@code balloon_tower}, a Balloon and its bomb, which
 * dies on the ground as its deploy ends; {@code balloon_river}, a Balloon and its bomb dying over
 * the river, the bomb no default target; {@code balloons_cross}, two Balloons crossing over a
 * Knight with the towers passive; {@code lava_hound_river}, a Lava Hound dying over the river and
 * its pups flying back to their ring points; and {@code baby_dragon_left}, a Baby Dragon firing
 * from its height. A further unit is placed at its own level where the run gives one.
 *
 * <p>Three runs play a spell by a command: {@code fireball_knight_tower}, a Fireball from the blue
 * king tower landing on a Knight and a princess tower and pushing the Knight; {@code
 * zap_knight_cast}, the Zap run with its area effect cast at the snapped point; and {@code
 * goblin_barrel_tower}, a Goblin Barrel whose Goblins stand in formation around its landing point;
 * {@code arrows_skeletons}, Arrows' three waves of chained arrows, landing on their ring points. A
 * run lasts to its last projectile position. {@code log_goblins} and {@code barb_barrel_knight}, a
 * thrown projectile whose impact launches a rolling one, which hits what its body passes. Four runs
 * hold shields - {@code recruit_tower}, {@code guards_knight}, {@code poison_guards} and {@code
 * tombstone_crazy_life} - and every hit a shield took.
 *
 * <p>Two runs place a unit whose own spawner fires while it walks and attacks: {@code
 * witch_left_lane}, a Witch whose four Skeletons stand on the ring of its spawn radius around it,
 * the first wave while it walks and the second while it attacks a princess tower; and {@code
 * night_witch}, a Night Witch whose two Bats a wave stand at its sides, the ring turned by its
 * angle shift and the angle it faces, as does the Bat of its death spawn.
 *
 * <p>{@code goblin_giant_tower} plays a Goblin Giant, whose two Spear Goblins ride on it: made as
 * the play sets it deploying and queued ahead of it, so they take the lower ids and are visited
 * first, placed behind its shoulders a tick behind it, shooting the tower from their height while
 * nothing can target them, and let go in the cleanup that removes the Giant, each leaving a Spear
 * Goblin where it rode.
 *
 * <p>Three runs hold the charge and the river jump: {@code prince_tower} and {@code
 * dark_prince_tower}, a unit charged by the steps it walks, twice as fast once full, whose first
 * hit lands at once for its special damage; and {@code hog_river}, a Hog Rider whose route crosses
 * the river and which jumps it to the first land cell beyond. Two hold the dash: {@code
 * bandit_knight}, a Bandit's wind-up, its dash stopped in range of a Knight and its single landing
 * hit; and {@code mega_knight_group}, a Mega Knight's timed dash, its landing over three Knights
 * with a push and its landing hold. Each is also held to every charge completed and lost, every
 * state a movement pass asked for, every dash started and every landing.
 *
 * <p>{@code ram_rider_tower} plays a Ram Rider: the Ram charges into the princess tower while its
 * rider, which targets troops only, takes no target; the run lists no actions, so the rider's
 * attachment and release are held from its {@code unit_spawner} log.
 *
 * <p>{@code match_elixir_150s} is a Ladder match: both decks shuffled with the battle's source, and
 * on every tick both elixirs, hands and cooldowns, the timeline and the crowns held to the
 * reference's trace, and every play's match code - a card still in the queue refused with 9, one
 * the elixir does not cover with 0xd. {@code match_knights_king} plays a match to its end: the
 * king's fall, the winner, the end timer, the fallen king's circle and its kills, nothing attacking
 * after the end, and the stop. {@code match_overtime_tiebreak} and {@code match_overtime_draw} play
 * past overtime with equal crowns into the tiebreaker: its clearing with nothing to clear, its idle
 * window, every step of its drain, and its end by a fallen princess tower, or as a draw by equal
 * towers. {@code match_building_cards} plays building cards from the hand: a Cannon snapped to its
 * tile corner, a Tombstone moved off the Cannon's tiles, a Cannon pulled back from across the river
 * and an Elixir Collector that pays its king, each living as the same building placed directly.
 *
 * <p>{@code electro_wizard_knights} and {@code ice_wizard_knights} play a wizard onto two Knights:
 * the card names no unit, so it is cast as a spell, its area effect zapping or chilling the Knights
 * while its starting action makes the wizard in the same tick. Both end before the wizard's own
 * first attack.
 *
 * <p>{@code royal_giant_tower} and {@code elite_archer_knight} fire a projectile at a constant
 * height: the Royal Giant's cannonball starts at 1500 and descends onto the tower it homes on, and
 * the Elite Archer's arrow flies level at 2000 past a Knight, the first two arrows re-aiming at it
 * on their first two steps. {@code snowball_knights} casts a Snowball onto two Knights, whose
 * impact damages and pushes both and then slows both with its target buff. {@code
 * witch_mother_skeletons} shoots Skeletons with a curse applied before the damage, so each dies
 * carrying it and leaves a Voodoo Hog for the other side. {@code electro_dragon_knights} hops an
 * Electro Dragon's bolt across three Knights, freezing each.
 */
class BattleActionSpawnRunTest {

  /** Writes a match's trace row as the reference lists it. */
  private static final ObjectMapper JSON = new ObjectMapper();

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
        "balloon_river",
        "lava_hound_river",
        "baby_dragon_left",
        "fireball_knight_tower",
        "zap_knight_cast",
        "goblin_barrel_tower",
        "arrows_skeletons",
        "log_goblins",
        "barb_barrel_knight",
        "recruit_tower",
        "guards_knight",
        "poison_guards",
        "tombstone_crazy_life",
        "witch_left_lane",
        "night_witch",
        "goblin_giant_tower",
        "prince_tower",
        "dark_prince_tower",
        "hog_river",
        "bandit_knight",
        "mega_knight_group",
        "ram_rider_tower",
        "match_elixir_150s",
        "match_knights_king",
        "match_overtime_tiebreak",
        "match_overtime_draw",
        "match_elixir_sources",
        "match_building_cards",
        "electro_wizard_knights",
        "ice_wizard_knights",
        "royal_giant_tower",
        "elite_archer_knight",
        "snowball_knights",
        "witch_mother_skeletons",
        "electro_dragon_knights"
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
      for (JsonNode u : reference.path("units")) {
        // A unit a card play created is listed with its command; the command places it.
        if (u.has("command")) {
          continue;
        }
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
    // A match is set up before the first step, its decks shuffled with the battle's source.
    LadderMatch ladder = null;
    Map<Integer, JsonNode> trace = new HashMap<>();
    List<String> circleKills = new ArrayList<>();
    List<String> drains = new ArrayList<>();
    List<String> elixirPaid = new ArrayList<>();
    List<Integer> endTicks = new ArrayList<>();
    if (reference.has("match")) {
      JsonNode m = reference.get("match");
      List<List<String>> decks = new ArrayList<>();
      for (JsonNode deck : m.get("decks")) {
        List<String> cards = new ArrayList<>();
        deck.forEach(card -> cards.add(card.asText()));
        decks.add(cards);
      }
      ladder =
          match.startLadderMatch(
              decks.get(0),
              decks.get(1),
              m.get("avatar_words").get(0).asInt(),
              m.get("avatar_words").get(1).asInt());
      for (JsonNode row : m.get("trace")) {
        trace.put(row.get(0).asInt(), row);
      }
      assertOpeningHands(ladder, m);
      match.getWorld().addObserver(matchLog(currentTick, circleKills, drains, elixirPaid));
    }
    // A run with card plays plays each as a place-card command due on its tick.
    if (reference.has("commands")) {
      BattlePlacementRunTest.playAll(match, reference);
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
    // The riders a card play attached, and each let go as its parent left.
    List<String> riders = new ArrayList<>();
    List<String> riderLog = new ArrayList<>();
    List<String> groups = new ArrayList<>();
    List<String> deaths = new ArrayList<>();
    Map<String, Integer> spawnTicks = new HashMap<>();
    match.getWorld().addObserver(BattleTowerRunTest.eventCollector(currentTick, events));
    List<String> areaEffects = new ArrayList<>();
    match.getWorld().addObserver(areaEffectLog(currentTick, areaEffects));
    // Every hit a shield took.
    List<String> shieldLog = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void shieldHit(
                  int tick, WorldEntity target, int damage, int shieldBefore, int shieldAfter) {
                shieldLog.add(
                    "%d shield %s %d %d %d %d %s"
                        .formatted(
                            currentTick[0],
                            target.name(),
                            damage,
                            shieldBefore,
                            shieldAfter,
                            target.getHitPoints().getHitPoints(),
                            shieldAfter == 0));
              }
            });
    // Every charge completed and lost, and every state change a unit's movement pass asked for.
    List<String> jumpChargeDash = new ArrayList<>();
    match.getWorld().addObserver(jumpChargeDashLog(currentTick, jumpChargeDash));
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
                  int tick, AreaEffectEntity a, String how, String source) {
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
              public void riderAttached(
                  int tick, CharacterEntity parent, CharacterEntity rider, int index, int angle) {
                // A rider is made in the command pass, ahead of the opening cleanup that folds it,
                // so no record finds it still pending.
                spawnTicks.remove(rider.name());
                riders.add(rider.name());
                riderLog.add(
                    "%d attached %s %d %s %d %d at %d %d state %d deploy %d dir %d %d"
                        .formatted(
                            currentTick[0],
                            rider.name(),
                            rider.getId(),
                            parent.name(),
                            index,
                            angle,
                            rider.getView().getX(),
                            rider.getView().getY(),
                            rider.getView().getState(),
                            rider.getView().getDeployCountdown(),
                            rider.getView().getDirX(),
                            rider.getView().getDirY()));
              }

              @Override
              public void parentLeft(int tick, CharacterEntity rider, CharacterEntity parent) {
                riderLog.add(
                    "%d parent_left %s %s at %d %d state %d"
                        .formatted(
                            currentTick[0],
                            rider.name(),
                            parent.name(),
                            rider.getView().getX(),
                            rider.getView().getY(),
                            rider.getView().getState()));
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
    // A shot still in flight when the run ends is recorded past the last record and event, and an
    // area effect, a buff or a shield may be logged past them too.
    for (JsonNode p : reference.path("projectiles")) {
      lastTick = Math.max(lastTick, p.get(0).asInt());
    }
    for (String log : List.of("area_effects", "buffs", "shields")) {
      for (JsonNode entry : reference.path(log)) {
        lastTick = Math.max(lastTick, entry.get("tick").asInt());
      }
    }
    // A match runs to the end of its trace.
    for (int traced : trace.keySet()) {
      lastTick = Math.max(lastTick, traced);
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
      if (ladder != null && ladder.isEnded() && endTicks.isEmpty()) {
        endTicks.add(tick);
      }
      if (trace.containsKey(tick)) {
        assertThat(traceRow(tick, ladder))
            .as("tick %d: the match", tick)
            .isEqualTo(trace.get(tick).toString());
      }
    }
    if (ladder != null) {
      assertPlays(match, reference);
      assertEnd(match, ladder, reference.get("match"));
      List<String> expectedKills = new ArrayList<>();
      List<String> expectedDrains = new ArrayList<>();
      List<String> expectedElixir = new ArrayList<>();
      List<Integer> expectedEnd = new ArrayList<>();
      for (JsonNode entry : reference.get("match").get("log")) {
        String kind = entry.get("event").asText();
        if (kind.equals("circle_kill")) {
          expectedKills.add(
              "%d %s %d"
                  .formatted(
                      entry.get("tick").asInt(),
                      entry.get("target").asText(),
                      entry.get("radius").asInt()));
        } else if (kind.equals("collector_elixir") || kind.equals("death_elixir")) {
          boolean collector = kind.equals("collector_elixir");
          expectedElixir.add(
              "%d %s %s %d %d"
                  .formatted(
                      entry.get("tick").asInt(),
                      collector ? "collector" : "death",
                      entry.get(collector ? "building" : "unit").asText(),
                      entry.get("side").asInt(),
                      entry.get("amount").asInt()));
        } else if (kind.equals("drain")) {
          expectedDrains.add(
              "%d %s %d %d"
                  .formatted(
                      entry.get("tick").asInt(),
                      entry.get("target").asText(),
                      entry.get("damage").asInt(),
                      entry.get("hp").asInt()));
        } else if (kind.equals("end")) {
          expectedEnd.add(entry.get("tick").asInt());
          assertThat(List.of(ladder.crowns(0), ladder.crowns(1)))
              .as("the crowns at the end")
              .containsExactly(
                  entry.get("crowns").get(0).asInt(), entry.get("crowns").get(1).asInt());
        }
      }
      assertThat(circleKills).as("every kill of a fallen king's circle").isEqualTo(expectedKills);
      assertThat(drains).as("every step of the tiebreaker's drain").isEqualTo(expectedDrains);
      assertThat(elixirPaid)
          .as("every elixir a collector or a death paid a king")
          .isEqualTo(expectedElixir);
      assertThat(endTicks).as("the tick the match ended on").isEqualTo(expectedEnd);
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
    if (reference.has("actions")) {
      assertThat(spawns).as("every spawn").containsExactlyElementsOf(expectedSpawns);
    } else {
      // A run that lists no actions lists its riders under unit_spawner and the children of a
      // buff's death spawn in its buff log: they are its only spawns.
      Set<String> buffChildren = new HashSet<>();
      for (JsonNode b : reference.path("buffs")) {
        if (b.get("event").asText().equals("death_spawn")) {
          b.get("units").forEach(u -> buffChildren.add(u.get(0).asText()));
        }
      }
      List<String> riderSpawns =
          spawns.stream().filter(line -> !buffChildren.contains(line.split(" ")[3])).toList();
      assertThat(riderSpawns)
          .as("every spawn but a buff's death spawn, a rider each")
          .allSatisfy(line -> assertThat(riders).contains(line.split(" ")[3]))
          .hasSameSizeAs(riders);
    }
    List<String> expectedRiders = new ArrayList<>();
    for (JsonNode e : reference.path("unit_spawner")) {
      String kind = e.get("event").asText();
      if (kind.equals("attached")) {
        expectedRiders.add(
            "%d attached %s %d %s %d %d at %d %d state %d deploy %d dir %d %d"
                .formatted(
                    e.get("tick").asInt(),
                    e.get("unit").asText(),
                    e.get("id").asInt(),
                    e.get("parent").asText(),
                    e.get("index").asInt(),
                    e.get("angle").asInt(),
                    e.get("x").asInt(),
                    e.get("y").asInt(),
                    e.get("state").asInt(),
                    e.get("deploy").asInt(),
                    e.get("dir").get(0).asInt(),
                    e.get("dir").get(1).asInt()));
      } else if (kind.equals("parent_left")) {
        expectedRiders.add(
            "%d parent_left %s %s at %d %d state %d"
                .formatted(
                    e.get("tick").asInt(),
                    e.get("unit").asText(),
                    e.get("parent").asText(),
                    e.get("x").asInt(),
                    e.get("y").asInt(),
                    e.get("state").asInt()));
      }
    }
    if (reference.has("unit_spawner")) {
      assertThat(riderLog)
          .as("every rider attached and let go")
          .containsExactlyElementsOf(expectedRiders);
    }
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
      // A lock names the target the tower attacks. The reference also logs the attacking state
      // requested again while an attack runs on with no reference, as a lock of nothing; that is
      // no lock.
      if (event.get("event").asText().equals("lock") && !event.get("target").isNull()) {
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
    assertThat(jumpChargeDash)
        .as("every charge, its loss, and every state a movement pass asked for")
        .containsExactlyElementsOf(expectedJumpChargeDash(reference));

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
    List<String> expectedShields = new ArrayList<>();
    for (JsonNode s : reference.path("shields")) {
      expectedShields.add(
          "%d shield %s %d %d %d %d %s"
              .formatted(
                  s.get("tick").asInt(),
                  s.get("target").asText(),
                  s.get("damage").asInt(),
                  s.get("shield_before").asInt(),
                  s.get("shield").asInt(),
                  s.get("hp").asInt(),
                  s.get("broke").asBoolean()));
    }
    assertThat(shieldLog).as("every hit a shield took").containsExactlyElementsOf(expectedShields);

    List<String> expectedEvents = new ArrayList<>();
    for (JsonNode event : reference.get("events")) {
      expectedEvents.add(BattleTowerRunTest.eventLine(event));
    }
    assertThat(pushesAfterTheirArea(events))
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
    assertThat(Integer.valueOf(tick).equals(spawnTicks.get(unit.name())))
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
      public void areaEffectCreated(int tick, AreaEffectEntity a, String how, String source) {
        lines.add(
            "%d created %s %s %d %s %s %d %d %d %d %d"
                .formatted(
                    currentTick[0],
                    a.name(),
                    a.getData().name(),
                    a.getId(),
                    how,
                    source,
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
      public void projectileBuff(
          int tick, ProjectileEntity p, BuffData buff, int time, List<WorldEntity> targets) {
        lines.add(
            "%d target_buff %s %s %d %s"
                .formatted(
                    currentTick[0],
                    p.name(),
                    buff.name(),
                    time,
                    targets.stream().map(WorldEntity::name).toList()));
      }

      @Override
      public void buffDeathSpawn(
          int tick, WorldEntity dying, BuffInstance buff, List<CharacterEntity> made) {
        lines.add(
            "%d death_spawn %s %s %d %d %s"
                .formatted(
                    currentTick[0],
                    dying.name(),
                    buff.getBuff().deathSpawn(),
                    made.size(),
                    buff.getPackedLevel(),
                    made.stream()
                        .map(
                            c ->
                                "%s %d %d %d %d %d %d"
                                    .formatted(
                                        c.name(),
                                        c.side(),
                                        c.getView().getX(),
                                        c.getView().getY(),
                                        c.getView().getState(),
                                        c.getView().getDeployCountdown(),
                                        c.getHitPoints().getMaximum()))
                        .toList()));
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
        case "target_buff" -> {
          List<String> targets = new ArrayList<>();
          b.get("targets").forEach(t -> targets.add(t.asText()));
          expected.add(
              "%d target_buff %s %s %d %s"
                  .formatted(
                      tick,
                      b.get("projectile").asText(),
                      b.get("buff").asText(),
                      b.get("time").asInt(),
                      targets));
        }
        case "death_spawn" -> {
          List<String> units = new ArrayList<>();
          b.get("units")
              .forEach(
                  u ->
                      units.add(
                          "%s %d %d %d %d %d %d"
                              .formatted(
                                  u.get(0).asText(),
                                  u.get(1).asInt(),
                                  u.get(2).asInt(),
                                  u.get(3).asInt(),
                                  u.get(4).asInt(),
                                  u.get(5).asInt(),
                                  u.get(6).asInt())));
          expected.add(
              "%d death_spawn %s %s %d %d %s"
                  .formatted(
                      tick,
                      b.get("target").asText(),
                      b.get("row").asText(),
                      b.get("count").asInt(),
                      b.get("level").asInt(),
                      units));
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

  /**
   * Lists every charge a unit completed, with its progress and where it stood, every charge it lost
   * at a movement visit, with its state then, and every state its movement pass asked for; a jump
   * lists its length and its landing node.
   */
  private static WorldObserver jumpChargeDashLog(int[] currentTick, List<String> log) {
    return new WorldObserver() {
      @Override
      public void chargeCompleted(int tick, CharacterEntity unit, int progress) {
        log.add(
            "%d charged %s %d %d %d"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    progress,
                    unit.getView().getX(),
                    unit.getView().getY()));
      }

      @Override
      public void chargeLost(int tick, CharacterEntity unit) {
        log.add(
            "%d charge_lost %s %d %d %d"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    unit.getView().getState(),
                    unit.getView().getX(),
                    unit.getView().getY()));
      }

      @Override
      public void dashStarted(
          int tick,
          CharacterEntity unit,
          TargetView reference,
          int fromX,
          int fromY,
          int aimX,
          int aimY) {
        log.add(
            "%d dash_start %s %s %d %d aim %d %d route %s stop %d time %d windup %d"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    reference.getEntity().getName(),
                    fromX,
                    fromY,
                    aimX,
                    aimY,
                    Arrays.stream(unit.getUnit().movement().getRoute().toArray()).boxed().toList(),
                    unit.getUnit().movement().getDashStopsInRange(),
                    unit.getUnit().movement().getDashTimeMs(),
                    unit.getUnit().targeting().getDashWindupMs()));
      }

      @Override
      public void dashLanded(
          int tick, CharacterEntity unit, WorldEntity hit, int damage, boolean area) {
        String what = "none";
        if (area) {
          what =
              "area %d %d %d %d %d"
                  .formatted(
                      unit.getView().getX(),
                      unit.getView().getY(),
                      unit.getData().dashRadius(),
                      damage,
                      unit.getData().dashPushBack());
        } else if (hit != null) {
          what = "single %s %d".formatted(hit.name(), damage);
        }
        log.add(
            "%d landing %s %d %d %s delay %d state %d"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    unit.getView().getX(),
                    unit.getView().getY(),
                    what,
                    unit.getView().getBlockCountdownMs(),
                    unit.getView().getState()));
      }

      @Override
      public void movementStateRequested(int tick, CharacterEntity unit, int from, int to) {
        String jump = "";
        if (to == GridEntityState.JUMPING) {
          jump =
              " jump %d %s"
                  .formatted(
                      unit.getUnit().movement().getJumpTotalDistance(),
                      List.of(unit.getUnit().movement().getRoute().last()));
        }
        log.add(
            "%d state %s %d %d %d %d%s"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    from,
                    to,
                    unit.getView().getX(),
                    unit.getView().getY(),
                    jump));
      }
    };
  }

  /** The reference's charges, their losses and the states movement passes asked for, as listed. */
  private static List<String> expectedJumpChargeDash(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode e : reference.path("jump_charge_dash")) {
      String kind = e.get("event").asText();
      int tick = e.get("tick").asInt();
      String unit = e.get("unit").asText();
      switch (kind) {
        case "charged" ->
            expected.add(
                "%d charged %s %d %d %d"
                    .formatted(
                        tick,
                        unit,
                        e.get("charge").asInt(),
                        e.get("x").asInt(),
                        e.get("y").asInt()));
        case "charge_lost" ->
            expected.add(
                "%d charge_lost %s %d %d %d"
                    .formatted(
                        tick,
                        unit,
                        e.get("state").asInt(),
                        e.get("x").asInt(),
                        e.get("y").asInt()));
        case "state" -> {
          String jump = "";
          if (e.has("jump_total")) {
            List<Integer> target = new ArrayList<>();
            e.get("target").forEach(node -> target.add(node.asInt()));
            jump = " jump %d %s".formatted(e.get("jump_total").asInt(), target);
          }
          expected.add(
              "%d state %s %d %d %d %d%s"
                  .formatted(
                      tick,
                      unit,
                      e.get("old").asInt(),
                      e.get("state").asInt(),
                      e.get("x").asInt(),
                      e.get("y").asInt(),
                      jump));
        }
        case "dash_start" -> {
          List<Integer> route = new ArrayList<>();
          e.get("route").forEach(node -> route.add(node.asInt()));
          expected.add(
              "%d dash_start %s %s %d %d aim %d %d route %s stop %d time %d windup %d"
                  .formatted(
                      tick,
                      unit,
                      e.get("ref").asText(),
                      e.get("x").asInt(),
                      e.get("y").asInt(),
                      e.get("aim").get(0).asInt(),
                      e.get("aim").get(1).asInt(),
                      route,
                      e.get("stop_check").asInt(),
                      e.get("dash_time").asInt(),
                      e.get("windup").asInt()));
        }
        case "landing" -> {
          JsonNode hit = e.get("hit");
          String what = "none";
          if (hit != null && !hit.isNull()) {
            what =
                hit.get("kind").asText().equals("area")
                    ? "area %d %d %d %d %d"
                        .formatted(
                            hit.get("centre").get(0).asInt(),
                            hit.get("centre").get(1).asInt(),
                            hit.get("radius").asInt(),
                            hit.get("damage").asInt(),
                            hit.get("push").asInt())
                    : "single %s %d"
                        .formatted(hit.get("target").asText(), hit.get("damage").asInt());
          }
          expected.add(
              "%d landing %s %d %d %s delay %d state %d"
                  .formatted(
                      tick,
                      unit,
                      e.get("x").asInt(),
                      e.get("y").asInt(),
                      what,
                      e.get("landing_delay").asInt(),
                      e.get("state").asInt()));
        }
        default -> throw new IllegalArgumentException("an event this test does not hold: " + kind);
      }
    }
    return expected;
  }

  /**
   * The events with an area's pushes listed after all of its hits. The battle pushes each victim
   * right after its damage, as the area damage does; the reference applies the pushes the area
   * asked for once its loop ends, so it lists them after the last hit. Nothing a push does reaches
   * the later victims' damage, so only the order they are listed in differs. An area's hits are an
   * area effect's or a unit's area hits of one tick, or the impacts of one projectile on one tick.
   */
  private static List<String> pushesAfterTheirArea(List<String> events) {
    List<String> ordered = new ArrayList<>();
    List<String> pushes = new ArrayList<>();
    String area = null;
    for (String event : events) {
      String[] words = event.split(" ");
      String last = ordered.isEmpty() ? null : areaOf(ordered.get(ordered.size() - 1));
      if (words[1].equals("pushback") && (!pushes.isEmpty() || last != null)) {
        if (pushes.isEmpty()) {
          area = last;
        }
        pushes.add(event);
        continue;
      }
      if (!pushes.isEmpty() && !area.equals(areaOf(event))) {
        ordered.addAll(pushes);
        pushes.clear();
      }
      ordered.add(event);
    }
    ordered.addAll(pushes);
    return ordered;
  }

  /** The area a hit event belongs to, or null for an event that is not an area's hit. */
  private static String areaOf(String event) {
    String[] words = event.split(" ");
    return switch (words[1]) {
      case "area_hit" -> words[0] + " area_hit";
      case "impact" -> words[0] + " impact " + words[2];
      default -> null;
    };
  }

  /** Both sides' opening hands and queues, and the draws that seeded their shuffles. */
  private static void assertOpeningHands(LadderMatch ladder, JsonNode m) {
    for (JsonNode entry : m.get("log")) {
      if (!entry.get("event").asText().equals("hand")) {
        continue;
      }
      int side = entry.get("side").asInt();
      MatchSide matchSide = ladder.side(side);
      assertThat(ladder.shuffleDraw(side))
          .as("side %d's shuffle draw", side)
          .isEqualTo(entry.get("seed_draw").asInt());
      List<Integer> slots = new ArrayList<>();
      entry.get("slots").forEach(slot -> slots.add(slot.asInt()));
      List<Integer> queue = new ArrayList<>();
      entry.get("queue").forEach(index -> queue.add(index.asInt()));
      assertThat(Arrays.stream(matchSide.getHand().slots()).boxed().toList())
          .as("side %d's opening hand", side)
          .isEqualTo(slots);
      assertThat(matchSide.getHand().queue()).as("side %d's queue", side).isEqualTo(queue);
      assertThat(matchSide.getElixir()).isEqualTo(entry.get("elixir").asInt());
    }
  }

  /**
   * One step of the match as the reference traces it: both elixirs, both hands, both cooldowns, the
   * timeline's time, section and rate, both crowns, the end timer, whether the battle ended, the
   * tiebreaker's time and whether the entities were ticked.
   */
  private static String traceRow(int tick, LadderMatch ladder) {
    List<Object> values = new ArrayList<>();
    values.add(tick);
    values.add(ladder.side(0).getElixir());
    values.add(ladder.side(1).getElixir());
    values.add(Arrays.stream(ladder.side(0).getHand().slots()).boxed().toList());
    values.add(Arrays.stream(ladder.side(1).getHand().slots()).boxed().toList());
    values.add(ladder.side(0).getHand().getCooldownMs());
    values.add(ladder.side(1).getHand().getCooldownMs());
    values.add(ladder.getTimeline().getTimeMs());
    values.add(ladder.getTimeline().getSection());
    values.add(ladder.getTimeline().getRate());
    values.add(ladder.crowns(0));
    values.add(ladder.crowns(1));
    values.add(ladder.getEndTimerMs());
    values.add(ladder.isEnded() ? 1 : 0);
    values.add(ladder.getTiebreakMs());
    values.add(ladder.isLastTicked() ? 1 : 0);
    return JSON.valueToTree(values).toString();
  }

  /**
   * The match's end: its winner, and the step the battle stops on - which runs nothing at all, not
   * even the tick counter.
   */
  private static void assertEnd(Standard1v1Battle match, LadderMatch ladder, JsonNode m) {
    if (m.get("end").isNull()) {
      assertThat(ladder.isEnded()).as("the match goes on").isFalse();
      return;
    }
    assertThat(ladder.getWinner()).as("the winner").isEqualTo(m.get("end").get("winner").asInt());
    int stopped = m.get("stopped_at").asInt();
    assertThat(match.getBattle().getTick()).as("the step the battle stops on").isEqualTo(stopped);
    assertThat(match.getBattle().getMode().isOver()).as("stopped").isTrue();
    match.getBattle().step();
    assertThat(match.getBattle().getTick()).as("a stopped battle runs no step").isEqualTo(stopped);
  }

  /** Every play refused by a match's gate with the reference's code, and every other let on. */
  private static void assertPlays(Standard1v1Battle match, JsonNode reference) {
    Map<String, Integer> codes = new HashMap<>();
    for (Standard1v1Battle.Play play : match.getPlays()) {
      codes.put(play.name(), play.matchCode());
    }
    for (JsonNode command : reference.path("commands")) {
      String name = command.get("name").asText();
      int expected =
          command.path("stage").asText().equals("match_gates") ? command.get("code").asInt() : 0;
      assertThat(codes).as("the play %s ran", name).containsKey(name);
      assertThat(codes.get(name)).as("the play %s's match code", name).isEqualTo(expected);
    }
  }

  /**
   * Lists every kill of a fallen king's circle with its radius, every step of a tiebreaker's drain
   * with the tower's hit points after it, and every elixir a collector or a death paid a king.
   */
  private static WorldObserver matchLog(
      int[] currentTick, List<String> circle, List<String> drain, List<String> elixir) {
    return new WorldObserver() {
      @Override
      public void elixirCollected(int tick, WorldEntity collector, int side, int amount) {
        elixir.add(
            "%d collector %s %d %d".formatted(currentTick[0], collector.name(), side, amount));
      }

      @Override
      public void deathElixirPaid(int tick, WorldEntity dying, int side, int amount) {
        elixir.add("%d death %s %d %d".formatted(currentTick[0], dying.name(), side, amount));
      }

      @Override
      public void circleKilled(int tick, WorldEntity target, int radius) {
        circle.add("%d %s %d".formatted(currentTick[0], target.name(), radius));
      }

      @Override
      public void drained(int tick, WorldEntity target, int damage, int hitPoints, boolean died) {
        drain.add("%d %s %d %d".formatted(currentTick[0], target.name(), damage, hitPoints));
      }
    };
  }
}
