package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.BattleCommand;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.EntityActions;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.ActionOwner;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.Berserk;
import org.crforge.core.battle.action.GhostEvo;
import org.crforge.core.battle.action.GoblinHutLifeState;
import org.crforge.core.battle.action.InertAction;
import org.crforge.core.battle.action.ShapeSelector;
import org.crforge.core.battle.action.TargetIndicatorAttack;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.match.EvolutionItem;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.match.MirrorItem;
import org.crforge.core.battle.match.VariantItem;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.AreaDamage;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.crforge.core.pathfinding.math.FixedMath;
import org.crforge.core.pathfinding.move.MovementState;
import org.crforge.core.pathfinding.state.StateQueries;
import org.crforge.core.pathfinding.target.TargetView;
import org.crforge.core.pathfinding.target.TargetingState;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Plays the runs that no reference battle holds yet through {@link Battle} and holds the battle to
 * them tick for tick: every unit's position, state and hit points, and each event list a run
 * carries (actions, spawns, buffs, area effects, projectiles, reflects and Mirror items).
 *
 * <p>The runs are the battle generator's output on the earlier data, not recordings of the game.
 * The others were deleted once reference battles held what they checked; these seven stay until
 * reference battles recorded for them take their place.
 *
 * <p>{@code mirror_knight} plays a Knight and then the Mirror, which repeats it one level up for
 * one elixir more and goes to the back of the queue itself. {@code mirror_fireball} does the same
 * with a Fireball, its first Mirror refused, the elixir short of the item's cost. Each is held to
 * every Mirror item - the card it repeats, its level and its cost - and to the last card kept.
 *
 * <p>{@code ram_rider_bola} has a Ram Rider snare a Knight with her bola, again with each throw
 * while the snare still holds. {@code ram_rider_drop_knights} and {@code ram_rider_drop_tower} hold
 * the rider's reference dropped on every bola: against two Knights, which she takes in turn as each
 * snare ends, and while the Ram charges a princess tower, until the Ram dies and the rider with it.
 * {@code parent_buff_ram_rider_rage} has a Rage refresh its buff on a Ram every six ticks, each
 * handed to its rider, whose throws come 17 or 18 ticks apart instead of 22; it is held to every
 * hand-over and the rider's instances after it.
 *
 * <p>{@code electro_giant_struck} plays an Electro Giant into two Knights and a Musketeer: every
 * Knight hit and every shot from inside its reach is struck back with 192 and a stun, which drops
 * the attacker's reference that tick, and the hit that kills it is still struck back. It is held to
 * every hit that reached the reflect, what it struck back with, and every reflected hit.
 *
 * <p>The runs are in two lists: the first is played; the second holds the runs that the current
 * rows or rules move, disabled until the recorded references replace them.
 */
class BattleActionSpawnRunTest {

  /** Which bit of a tag word each flag is, as the configured tables number the game tags. */
  private static final EntityFlags BITS = EntityFlags.of(GameData.tables());

  /** Writes a match's trace row as the reference lists it. */
  private static final ObjectMapper JSON = new ObjectMapper();

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "mirror_fireball",
        "ram_rider_bola",
        "electro_giant_struck",
        "ram_rider_drop_knights",
        "ram_rider_drop_tower",
        "parent_buff_ram_rider_rage"
      })
  @Disabled("golden recorded on 14.593.1; awaiting decision")
  void theRunMatchesTheReferenceTickForTickAwaitingDecision(String name) {
    theRunMatchesTheReferenceTickForTick(name);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"mirror_knight"})
  void theRunMatchesTheReferenceTickForTick(String name) {
    JsonNode reference = load("/pathfinding/golden/" + name + ".json");
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
    // What each buff did, from before the placements: a unit's start buff is applied as it is
    // placed, on the tick it is placed for.
    List<String> buffLog = new ArrayList<>();
    match.getWorld().addObserver(buffLog(currentTick, buffLog));
    // Every count that applied a BuffAfterHits buff, and every buff's start or remove action, from
    // before the placements: a unit's start buff is applied as it is placed.
    List<String> buffAfterHitsLog = new ArrayList<>();
    match.getWorld().addObserver(buffAfterHitsLog(buffAfterHitsLog));
    // Every action a broken shield scheduled, and every charge reset a listed buff made.
    List<String> shieldLostLog = new ArrayList<>();
    match.getWorld().addObserver(shieldLostLog(shieldLostLog));
    // What every uppercut, knock and wind did, and every change of a watched tag word.
    // What the evolved Furnace's runs did, for a run that logs them, and every projectile an
    // action launched.
    // A run that logs the evolved Furnace or the evolved Executioner's axe lists the looping
    // effects'
    // runs too.
    FurnaceLog evoLog =
        new FurnaceLog(
            reference.has("furnace_evo"),
            reference.has("furnace_evo") || reference.has("axe_man_evo"),
            new ArrayList<>());
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void actionProjectileLaunched(
                  int tick,
                  WorldEntity owner,
                  String action,
                  int phase,
                  ProjectileEntity projectile) {
                evoLog
                    .lines()
                    .add(
                        "%d spawn_projectile %s %s %d %s %s [%d, %d, %d] [%d, %d] %d"
                            .formatted(
                                tick,
                                owner.name(),
                                action,
                                phase,
                                projectile.name(),
                                projectile.getData().name(),
                                projectile.getStartX(),
                                projectile.getStartY(),
                                projectile.getStartZ(),
                                projectile.getAimX(),
                                projectile.getAimY(),
                                projectile.getPackedLevel()));
              }
            });
    // Every barrage's start and update, with the bombs' areas it made, and every bomb dropped.
    List<String> barrageLog = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void barrageStarted(
                  int tick, CharacterEntity owner, String action, int phase) {
                barrageLog.add(
                    "%d barrage_start %s %s %d".formatted(tick, owner.name(), action, phase));
              }

              @Override
              public void barrageStepped(
                  int tick, CharacterEntity owner, String action, List<AreaEffectEntity> made) {
                List<String> areas = new ArrayList<>();
                for (AreaEffectEntity a : made) {
                  areas.add(
                      "%s %d %s %d %d %d %d"
                          .formatted(
                              a.name(),
                              a.getId(),
                              a.getData().name(),
                              a.getX(),
                              a.getY(),
                              a.getPackedLevel(),
                              a.getCountdown()));
                }
                barrageLog.add("%d barrage %s %s %s".formatted(tick, owner.name(), action, areas));
              }

              @Override
              public void bombDropped(
                  int tick,
                  AreaEffectEntity area,
                  String action,
                  int phase,
                  ProjectileEntity projectile,
                  int lifetime) {
                barrageLog.add(
                    "%d bomb %s %s %s [%d, %d, %d] [%d, %d] %d %d %d %d"
                        .formatted(
                            tick,
                            area.name(),
                            action,
                            projectile.name(),
                            projectile.getStartX(),
                            projectile.getStartY(),
                            projectile.getStartZ(),
                            projectile.getAimX(),
                            projectile.getAimY(),
                            projectile.getSpeedOverride(),
                            lifetime,
                            projectile.getPackedLevel(),
                            projectile.side()));
              }
            });
    // A projectile's runs are listed like a unit's, its listener set as it schedules its
    // starting action; and every axe controller's start, damage, hit action and push, and every
    // swap of a projectile's row; a run that logs the evolved Snowball lists its starts and swaps
    // in its own log.
    List<String> axeLog = new ArrayList<>();
    List<String> snowLog = new ArrayList<>();
    List<String> projectileLog = reference.has("snowball_evo") ? snowLog : axeLog;
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void projectileStarting(int tick, ProjectileEntity projectile, String action) {
                projectile
                    .actionHolder()
                    .setListener(
                        listener(projectile.name(), currentTick, actions, dropping, evoLog));
                projectileLog.add(
                    "%d start %s %s %s"
                        .formatted(tick, projectile.name(), projectile.getData().name(), action));
              }

              @Override
              public void projectileSwapped(
                  int tick, ProjectileEntity projectile, String from, String to) {
                projectileLog.add(
                    "%d swap %s %s %s %d %d"
                        .formatted(
                            tick,
                            projectile.name(),
                            from,
                            to,
                            projectile.getPingpongDistance(),
                            projectile.getPingpongTimeMs()));
              }

              @Override
              public void executionerStarted(
                  int tick, ProjectileEntity axe, String action, int phase) {
                axeLog.add(
                    "%d controller_start %s %s %d %d"
                        .formatted(tick, axe.name(), action, phase, axe.getPackedLevel()));
              }

              @Override
              public void axeDamage(
                  int tick,
                  ProjectileEntity axe,
                  WorldEntity target,
                  int hitId,
                  int before,
                  int after,
                  Integer edge,
                  boolean strong) {
                axeLog.add(
                    "%d damage %s %s %d [%d, %d] %s %d"
                        .formatted(
                            tick,
                            axe.name(),
                            target == null ? null : target.name(),
                            hitId,
                            before,
                            after,
                            edge,
                            strong ? 1 : 0));
              }

              @Override
              public void axeHitAction(
                  int tick, ProjectileEntity axe, WorldEntity target, String action, int hitId) {
                axeLog.add(
                    "%d hit_action %s %s %s %d"
                        .formatted(tick, axe.name(), target.name(), action, hitId));
              }

              @Override
              public void axePushed(
                  int tick,
                  ProjectileEntity axe,
                  WorldEntity target,
                  int x,
                  int y,
                  int distance,
                  int hitId) {
                axeLog.add(
                    "%d push %s %s [%d, %d] %d %d"
                        .formatted(tick, axe.name(), target.name(), x, y, distance, hitId));
              }
            });
    // Every roll's start, step and buff, every capture's start, lock request, step and schedule,
    // and every run that waited for its cause to leave.
    match.getWorld().addObserver(snowballLog(match, snowLog));
    // Every area effect an impact made that follows the projectile's target, and each of its
    // updates with its point and what it follows.
    List<String> iceLog = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void projectileAreaEffect(
                  int tick, ProjectileEntity projectile, AreaEffectEntity a) {
                if (!a.getData().followsTarget()) {
                  return;
                }
                iceLog.add(
                    "%d made %s %s %d %d %s %d %d"
                        .formatted(
                            tick,
                            a.name(),
                            projectile.name(),
                            a.getX(),
                            a.getY(),
                            a.getFollow() == null ? null : a.getFollow().name(),
                            a.getPackedLevel(),
                            a.getCountdown()));
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
                if (!a.getData().followsTarget()) {
                  return;
                }
                iceLog.add(
                    "%d update %s %d %d %s %d %d"
                        .formatted(
                            tick,
                            a.name(),
                            a.getX(),
                            a.getY(),
                            a.getFollow() == null ? null : a.getFollow().name(),
                            after,
                            hits));
              }
            });
    Set<String> furnaceOwners = new HashSet<>();
    for (JsonNode e : reference.path("furnace_evo")) {
      furnaceOwners.add(e.get("owner").asText());
    }
    Map<String, String> furnaceRunning = new HashMap<>();
    Map<String, String> furnaceTags = new HashMap<>();
    List<String> uppercutWindLog = new ArrayList<>();
    match.getWorld().addObserver(uppercutWindLog(match.getWorld(), uppercutWindLog));
    // Every action a hiding building scheduled on itself as it started to hide or rose.
    List<String> hidingHookLog = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void hidingHookScheduled(
                  int tick, CharacterEntity unit, String column, String action) {
                hidingHookLog.add("%d %s %s %s".formatted(tick, unit.name(), column, action));
              }
            });
    List<CharacterEntity> placed = new ArrayList<>();
    if (!reference.get("card").isNull()) {
      placed.addAll(deployAll(match, reference));
    } else if (!reference.has("action_owners")) {
      // A unit a card play created is listed with its command, and so is what it morphs into; the
      // command places the one, the battle makes the other, and neither is placed here. A unit an
      // action made, a Clone's clone, is listed with its action and made by the battle too.
      Set<String> commanded = new HashSet<>();
      for (JsonNode u : reference.path("units")) {
        if (u.has("command")) {
          commanded.add(u.get("name").asText());
        }
      }
      for (JsonNode u : reference.path("units")) {
        if (commanded.contains(u.get("name").asText()) || u.has("action")) {
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
      unit.actionHolder()
          .setListener(listener(unit.name(), currentTick, actions, dropping, evoLog));
    }
    // A princess tower's runs are listed like a unit's: a row another entity schedules on it runs
    // in its own passes. The king's holder tells its activation steps instead.
    for (BattleEntity entity : battle.getHolder().entities()) {
      if (entity instanceof TowerEntity tower && !tower.getData().king()) {
        tower
            .actionHolder()
            .setListener(listener(tower.name(), currentTick, actions, dropping, evoLog));
      }
    }
    // What the champion slots did, every ability's buff, and every slot step; the deck pass runs
    // as a match is set up.
    List<String> championLog = new ArrayList<>();
    List<String> championTrace = new ArrayList<>();
    match.getWorld().addObserver(championLog(currentTick, championLog, championTrace));
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
      // A deck's evolution and hero slots: each card's slot flags, by its deck index.
      int[][] slots = {new int[decks.get(0).size()], new int[decks.get(1).size()]};
      for (JsonNode e : reference.path("evolution")) {
        if (e.get("event").asText().equals("slot")) {
          slots[e.get("side").asInt()][e.get("index").asInt()] = e.get("flags").asInt();
        }
      }
      ladder =
          match.startLadderMatch(
              decks.get(0),
              decks.get(1),
              m.get("avatar_words").get(0).asInt(),
              m.get("avatar_words").get(1).asInt(),
              slots[0],
              slots[1]);
      for (JsonNode row : m.get("trace")) {
        trace.put(row.get(0).asInt(), row);
      }
      assertOpeningHands(ladder, m);
      match.getWorld().addObserver(matchLog(currentTick, circleKills, drains, elixirPaid));
    }
    // A run with card plays plays each as a place-card command due on its tick.
    if (reference.has("commands")) {
      playAll(match, reference);
    }
    // Every request for a unit's ability the run supplies, in place of its player's command: in the
    // unit's phase-2 pass of its tick, after the run pass.
    List<String> goldenKnightLog = new ArrayList<>();
    for (JsonNode g : reference.path("golden_knight")) {
      if (!g.get("event").asText().equals("supplied_request")) {
        continue;
      }
      int tick = g.get("tick").asInt();
      String requested = g.get("unit").asText();
      battle.queue(
          new BattleCommand() {
            @Override
            public int tick() {
              return tick;
            }

            @Override
            public void execute(Battle target) {
              CharacterEntity unit = (CharacterEntity) named(target, requested);
              goldenKnightLog.add(tick + " supplied_request " + requested);
              unit.actionHolder().schedule(new SuppliedRequest(unit), ActionHolder.OWN_DELAY);
            }
          });
    }
    match.getWorld().addObserver(goldenKnightLog(currentTick, goldenKnightLog));
    List<String> skeletonKingLog = new ArrayList<>();
    match.getWorld().addObserver(skeletonKingLog(currentTick, skeletonKingLog));
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
      owner
          .actionHolder()
          .setListener(listener(owner.name(), currentTick, actions, dropping, evoLog));
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
    match.getWorld().addObserver(eventCollector(currentTick, events));
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
    // A hiding building's deploy end, and every change of its hide counter that shows something.
    List<String> hidingLog = new ArrayList<>();
    match.getWorld().addObserver(hidingLog(currentTick, hidingLog));
    // Every pull of an attracting area effect's hit.
    List<String> pullLog = new ArrayList<>();
    match.getWorld().addObserver(pullLog(currentTick, pullLog));
    // Every push of a unit entering its deploying state.
    List<String> deployPushLog = new ArrayList<>();
    match.getWorld().addObserver(deployPushLog(currentTick, deployPushLog));
    // What every Clone did, and every area effect a projectile's impact made.
    List<String> cloneLog = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            cloneLog(
                currentTick, cloneLog, reference.has("snowball_evo") && reference.has("clones")));
    // Every hit that reached a reflecting unit's reflect.
    List<String> reflectLog = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void reflected(int tick, Reflection r) {
                reflectLog.add(reflectLine(currentTick[0], r));
              }
            });
    // Every special load armed, every state a dragging projectile set, and the hold and the pull
    // its leaving ended.
    List<String> hookLog = new ArrayList<>();
    match.getWorld().addObserver(hookLog(currentTick, hookLog));
    // Every death projectile, every egg made untargetable and every spawner destroyed at its limit.
    List<String> phoenixLog = new ArrayList<>();
    match.getWorld().addObserver(phoenixLog(currentTick, phoenixLog));
    // Every Kamikaze end and drain, and every lane a ring's mirror asked for.
    List<String> kamikazeLog = new ArrayList<>();
    List<String> laneLog = new ArrayList<>();
    match.getWorld().addObserver(kamikazeLog(currentTick, kamikazeLog, laneLog));
    // What every Goblin Hut's life state did, and the children it made, which its log holds.
    List<String> hutLog = new ArrayList<>();
    Set<String> hutChildren = new HashSet<>();
    match.getWorld().addObserver(goblinHutLog(match, currentTick, hutLog, hutChildren));
    // Every start and notice of a Berserker's index toggle, with the index before and after.
    List<String> berserkLog = new ArrayList<>();
    match.getWorld().addObserver(berserkLog(currentTick, berserkLog));
    // Every link and unlink of a card's group chain, and what Goblinstein's ability did.
    List<String> goblinsteinLog = new ArrayList<>();
    match.getWorld().addObserver(goblinsteinLog(currentTick, goblinsteinLog));
    // What Goblinstein's tether did, and every card play a listener heard.
    List<String> tetherLog = new ArrayList<>();
    match.getWorld().addObserver(tetherLog(currentTick, tetherLog));
    // Every buff a parent handed to its riders.
    List<String> handOverLog = new ArrayList<>();
    match.getWorld().addObserver(handOverLog(currentTick, handOverLog));
    List<String> guardLog = new ArrayList<>();
    match.getWorld().addObserver(guardLog(currentTick, guardLog));
    // Every start and step of a Boss Bandit ability's run, and every warp.
    List<String> warpLog = new ArrayList<>();
    match.getWorld().addObserver(warpLog(currentTick, warpLog));
    // Every hit a unit whose row passes over buffed targets landed, and the reference it dropped.
    List<String> dropLog = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void referenceDroppedOnHit(
                  int tick,
                  CharacterEntity unit,
                  WorldEntity hit,
                  ProjectileEntity via,
                  boolean kills,
                  boolean active,
                  TargetView dropped) {
                dropLog.add(
                    "%d drop %s hit %s via %s kills %b active %b dropped %s state %d"
                        .formatted(
                            currentTick[0],
                            unit.name(),
                            hit.name(),
                            via == null ? null : via.name(),
                            kills,
                            active,
                            dropped == null ? null : dropped.name(),
                            unit.getView().getState()));
              }
            });
    // Every tick a unit holds its own lock as the post-hooks end, which a Boss Bandit ability's run
    // asks for as it starts and releases as it finishes.
    List<String> selfLocks = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void afterPostHooks(
                  int tick, List<WorldEntity> present, List<ProjectileEntity> projectiles) {
                for (WorldEntity entity : present) {
                  if (match.getWorld().locks().claim(entity.getId(), entity.getId(), 0)) {
                    selfLocks.add(currentTick[0] + " " + entity.name());
                  }
                }
              }
            });
    // Every ask of an area effect's buff test of a clone, with the path that asked.
    List<String> cloneGateLog = new ArrayList<>();
    match.getWorld().addObserver(cloneGateLog(currentTick, cloneGateLog));
    List<String> princeLog = new ArrayList<>();
    match.getWorld().addObserver(princeLog(currentTick, princeLog));
    // Every killer's killed-done check scheduled, and every check of what caused an action.
    List<String> killLog = new ArrayList<>();
    match.getWorld().addObserver(killLog(currentTick, killLog));
    List<String> areaEffectSpawnLog = new ArrayList<>();
    match.getWorld().addObserver(areaEffectSpawnLog(currentTick, areaEffectSpawnLog));
    // Every laser ball's start and fire, and every area effect's life-end action scheduled.
    List<String> laserLog = new ArrayList<>();
    match.getWorld().addObserver(laserLog(currentTick, laserLog));
    // A played unit's runs are listed from its play, before its start.
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void characterPlayed(int tick, CharacterEntity unit) {
                unit.actionHolder()
                    .setListener(listener(unit.name(), currentTick, actions, dropping, evoLog));
              }
            });
    // Every shape selector's start, step and removal, and every air-to-ground run's start, phase
    // change, finish and re-trigger.
    List<String> vinesLog = new ArrayList<>();
    match.getWorld().addObserver(vinesLog(match, currentTick, vinesLog));
    // Every target indicator attack's start, find, signal, shot, signal ended, step and stop.
    List<String> indicatorLog = new ArrayList<>();
    match.getWorld().addObserver(indicatorLog(match, currentTick, indicatorLog));
    // An area effect's own runs are listed like any owner's, from its creation; the removal of a
    // shape selector's run joins the selectors' log.
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void areaEffectCreated(
                  int tick, AreaEffectEntity a, String how, String source) {
                ActionHolder.Listener runs =
                    listener(a.name(), currentTick, actions, dropping, evoLog);
                a.actionHolder()
                    .setListener(
                        new ActionHolder.Listener() {
                          @Override
                          public void starting(BattleAction action, int phase, boolean queued) {
                            runs.starting(action, phase, queued);
                          }

                          @Override
                          public void dropped(BattleAction action, int ticksLeft) {
                            runs.dropped(action, ticksLeft);
                          }

                          @Override
                          public void removed(ActionInstance instance) {
                            if (instance.getAction() instanceof GhostEvo.Summon) {
                              buffAfterHitsLog.add(
                                  "%d summon_removed %s %s"
                                      .formatted(
                                          currentTick[0], a.name(), instance.getAction().name()));
                            }
                            if (instance.getAction() instanceof ShapeSelector) {
                              vinesLog.add(
                                  "%d selector_removed %s %s"
                                      .formatted(
                                          currentTick[0], a.name(), instance.getAction().name()));
                            }
                          }
                        });
              }
            });
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void characterSpawned(
                  int tick, SpawnHost owner, CharacterEntity child, int createdX, int createdY) {
                // A child's runs are listed from its spawn, the action it runs on being spawned
                // included.
                child
                    .actionHolder()
                    .setListener(listener(child.name(), currentTick, actions, dropping, evoLog));
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
              public void ghostSummonMade(int tick, AreaEffectEntity area, CharacterEntity summon) {
                // A summon is listed from its making, as a spawned child is.
                summon
                    .actionHolder()
                    .setListener(listener(summon.name(), currentTick, actions, dropping, evoLog));
                spawnTicks.put(summon.name(), currentTick[0]);
              }

              @Override
              public void guardRegistered(int tick, CharacterEntity guard) {
                // A guard is listed from its making, as a spawned child is.
                guard
                    .actionHolder()
                    .setListener(listener(guard.name(), currentTick, actions, dropping, evoLog));
                spawnTicks.put(guard.name(), currentTick[0]);
              }

              @Override
              public void cloned(
                  int tick,
                  CharacterEntity original,
                  CharacterEntity clone,
                  SpawnHost instigator,
                  List<Integer> registrationVisits) {
                // A clone is listed from its making, as a spawned child is.
                clone
                    .actionHolder()
                    .setListener(listener(clone.name(), currentTick, actions, dropping, evoLog));
                spawnTicks.put(clone.name(), currentTick[0]);
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

    Map<Integer, List<JsonNode>> records = otherRecordsByTick(reference);
    // The run's own unit's records, when it has one, are held to its outside like the others, each
    // read at the end of its tick, the deploy-end tick's too.
    if (!reference.get("card").isNull()) {
      List<JsonNode> own = records(reference);
      for (int i = 0; i < own.size(); i++) {
        ObjectNode named = own.get(i).deepCopy();
        named.put("name", placed.get(0).name());
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
    // The reference logs a tower's lock only while the tower holds none: a lock holds until a visit
    // leaves the tower with no reference at all, so a tower that falls back to its default seed and
    // takes its target again logs no second lock.
    Set<String> locked = new HashSet<>();
    // A morph's new building takes its target in its registration visit, before it is set
    // deploying; the reference logs that as its lock, on the tick it is made.
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void morphed(int tick, CharacterEntity old, CharacterEntity made) {
                if (referenceName(made) != null) {
                  locks.add(tick + " " + made.name() + " " + referenceName(made));
                }
              }

              // A tower that takes a new target in its visit and dies later in the same step has
              // it dropped by its combat gate before the step ends; the reference logs the lock
              // from the visit all the same.
              @Override
              public void combatGateDropped(
                  int tick, WorldEntity entity, TargetView reference, int hitSpeed) {
                if (!(entity instanceof TowerEntity)
                    || entity.getView().getState() != GridEntityState.ATTACKING) {
                  return;
                }
                String before = towerStates.get(entity.name());
                String beforeRef =
                    before == null ? null : before.substring(before.indexOf(' ') + 1);
                if (!reference.name().equals(beforeRef) && locked.add(entity.name())) {
                  locks.add(tick + " " + entity.name() + " " + reference.name());
                }
              }
            });
    // The reference logs a tower's or a building's reference whenever a targeting visit or a
    // removal changes it, and, for a removal, the retarget countdown it left. A combat gate's drop
    // (a freeze) is logged with the events instead, and a tower that takes the same target again
    // after it logs no change, so a tower's reference is unknown from such a drop until its next
    // change; a tower that died is not followed further.
    Map<Integer, List<JsonNode>> referenceChanges = new HashMap<>();
    for (JsonNode event : reference.path("tower_events")) {
      if (event.get("event").asText().equals("reference")) {
        referenceChanges
            .computeIfAbsent(event.get("tick").asInt(), t -> new ArrayList<>())
            .add(event);
      }
    }
    Map<Integer, List<String>> gateDrops = new HashMap<>();
    Map<Integer, List<String>> towerDeaths = new HashMap<>();
    for (JsonNode event : reference.get("events")) {
      String kind = event.get("event").asText();
      if (kind.equals("combat_gate_drop")) {
        gateDrops
            .computeIfAbsent(event.get("tick").asInt(), t -> new ArrayList<>())
            .add(event.get("unit").asText());
      } else if (kind.equals("death")) {
        towerDeaths
            .computeIfAbsent(event.get("tick").asInt(), t -> new ArrayList<>())
            .add(event.get("target").asText());
      }
    }
    Map<String, String> heldReferences = new HashMap<>();
    Set<String> unknownReferences = new HashSet<>();
    // The characters seen walking, which a swap may turn into buildings.
    Set<String> walkers = new HashSet<>();
    for (int tick = 0; tick <= lastTick; tick++) {
      currentTick[0] = tick;
      battle.step();
      // The evolved Furnace's listed runs, each with whether it has finished, and its three tags,
      // as the tick ends, whenever either changes.
      for (BattleEntity entity : battle.getHolder().entities()) {
        if (entity instanceof CharacterEntity c && furnaceOwners.contains(c.name())) {
          String running =
              c.actionHolder().running().stream()
                  .map(i -> "[" + i.getAction().name() + ", " + (i.isFinished() ? 1 : 0) + "]")
                  .toList()
                  .toString();
          if (!running.equals(furnaceRunning.put(c.name(), running))) {
            evoLog.lines().add("%d running %s %s".formatted(tick, c.name(), running));
          }
          List<String> carried = new ArrayList<>();
          for (String tag : FURNACE_TAGS) {
            long mask = match.getWorld().gameTagMask(match.getWorld().gameTagIndex(tag));
            if ((c.getView().getFlags() & mask) == mask) {
              carried.add(tag);
            }
          }
          String tags = carried.toString();
          if (!tags.equals(furnaceTags.getOrDefault(c.name(), "[]"))) {
            furnaceTags.put(c.name(), tags);
            evoLog.lines().add("%d tags %s %s".formatted(tick, c.name(), tags));
          }
        }
      }
      if (stateAfter.containsKey(tick)) {
        assertThat(Integer.toUnsignedLong(match.getWorld().getRandom().getState()))
            .as("tick %d: the random state after its draws", tick)
            .isEqualTo(stateAfter.get(tick));
      }
      for (BattleEntity entity : battle.getHolder().entities()) {
        if (entity instanceof CharacterEntity c) {
          units.putIfAbsent(c.name(), c);
          if (!c.getData().building()) {
            walkers.add(c.name());
          }
        }
        // A building locks on as a tower does.
        if (entity instanceof TowerEntity
            || entity instanceof CharacterEntity c && c.getData().building()) {
          WorldEntity tower = (WorldEntity) entity;
          // A lock is the tower attacking a target it did not hold at the end of the last step:
          // entering the attacking state with a new target, or, still in it after its target left,
          // taking the next one. Entering it again with the target it kept is none.
          String held = tower.getView().getState() + " " + referenceName(tower);
          String before = towerStates.put(tower.name(), held);
          // The reference logs a building's lock from its visits as a building: the step a
          // walking unit becomes one, as the Moving Cannon breaks down while it attacks, lists
          // nothing, and its next visit asks for the attacking state again and is its lock.
          if (before == null && walkers.contains(tower.name())) {
            towerStates.put(tower.name(), "first seen");
            continue;
          }
          String beforeRef = before == null ? null : before.substring(before.indexOf(' ') + 1);
          if (tower.getView().getState() == GridEntityState.ATTACKING
              && referenceName(tower) != null
              && !referenceName(tower).equals(beforeRef)
              && locked.add(tower.name())) {
            locks.add(tick + " " + tower.name() + " " + referenceName(tower));
          }
          if (referenceName(tower) == null) {
            locked.remove(tower.name());
          }
        }
      }
      for (JsonNode change : referenceChanges.getOrDefault(tick, List.of())) {
        JsonNode target = change.get("target");
        heldReferences.put(change.get("tower").asText(), target.isNull() ? null : target.asText());
        unknownReferences.remove(change.get("tower").asText());
      }
      unknownReferences.addAll(gateDrops.getOrDefault(tick, List.of()));
      towerDeaths.getOrDefault(tick, List.of()).forEach(heldReferences::remove);
      for (BattleEntity entity : battle.getHolder().entities()) {
        if (entity instanceof TowerEntity tower
            && heldReferences.containsKey(tower.name())
            && !unknownReferences.contains(tower.name())) {
          assertThat(referenceName(tower))
              .as("tick %d: %s's reference", tick, tower.name())
              .isEqualTo(heldReferences.get(tower.name()));
          JsonNode change =
              referenceChanges.getOrDefault(tick, List.of()).stream()
                  .filter(c -> c.get("tower").asText().equals(tower.name()))
                  .reduce((first, second) -> second)
                  .orElse(null);
          if (change != null && change.has("target_lost_timer")) {
            assertThat(tower.getTargeting().getTargetLostTimerMs())
                .as("tick %d: %s's retarget countdown after the removal", tick, tower.name())
                .isEqualTo(change.get("target_lost_timer").asInt());
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
      assertAbilityUses(match, reference);
      assertEnd(match, ladder, reference.get("match"));
      assertMirror(match, ladder, reference);
      assertVariant(match, reference);
      assertEvolution(match, ladder, reference);
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
    if (reference.has("snowball_evo")) {
      // The evolved Snowball's log lists the runs of its four classes, each as it starts or is
      // re-triggered; the other runs are held by their own logs.
      List<String> snowballRuns = new ArrayList<>();
      Set<String> rows = new HashSet<>();
      for (JsonNode e : reference.get("snowball_evo")) {
        String kind = e.get("event").asText();
        if (kind.equals("instance") || kind.equals("retrigger")) {
          rows.add(e.get("action").asText());
          snowballRuns.add(
              "%d run %s %s %d"
                  .formatted(
                      e.get("tick").asInt(),
                      e.get("owner").asText(),
                      e.get("action").asText(),
                      e.get("phase").asInt()));
        }
      }
      assertThat(actions.stream().filter(line -> rows.contains(line.split(" ")[3])).toList())
          .as("every run of the evolved Snowball's classes")
          .containsExactlyElementsOf(snowballRuns);
    } else if (reference.has("actions")
        || !(reference.has("buff_after_hits") || reference.has("shield_lost"))) {
      assertThat(actions).as("every run of an action").containsExactlyElementsOf(expectedActions);
    } else if (reference.has("buff_after_hits")) {
      // A run that lists no runs of its own still lists every buff hook it scheduled: each run is
      // one of those, on its unit and tick.
      List<String> hookRuns = new ArrayList<>();
      for (JsonNode e : reference.get("buff_after_hits")) {
        if (e.get("event").asText().equals("buff_hook")) {
          hookRuns.add(
              "%d run %s %s"
                  .formatted(
                      e.get("tick").asInt(), e.get("unit").asText(), e.get("action").asText()));
        }
      }
      assertThat(actions.stream().map(line -> line.substring(0, line.lastIndexOf(' '))).toList())
          .as("every run of an action, each a buff hook the reference scheduled")
          .containsExactlyElementsOf(hookRuns);
    } else {
      // A run that lists no runs of its own still lists every run of a broken shield's action,
      // with its pending pass.
      List<String> shieldRuns = new ArrayList<>();
      for (JsonNode e : reference.get("shield_lost")) {
        if (e.get("event").asText().equals("run")) {
          shieldRuns.add(
              "%d run %s %s %d"
                  .formatted(
                      e.get("tick").asInt(),
                      e.get("unit").asText(),
                      e.get("action").asText(),
                      e.get("phase").asInt()));
        }
      }
      assertThat(actions)
          .as("every run of an action, each a broken shield's the reference ran")
          .containsExactlyElementsOf(shieldRuns);
    }
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
      // A Goblin Hut's children are held by its own log.
      assertThat(spawns.stream().filter(line -> !hutChildren.contains(line.split(" ")[3])))
          .as("every spawn")
          .containsExactlyElementsOf(expectedSpawns);
    } else {
      // A run that lists no actions lists its riders under unit_spawner, the children of a
      // buff's death spawn in its buff log and a Phoenix's egg, made by its death projectile's
      // impact, in its Phoenix log: they are its only spawns. The egg is made where and when the
      // log says.
      Set<String> buffChildren = new HashSet<>();
      for (JsonNode b : reference.path("buffs")) {
        if (b.get("event").asText().equals("death_spawn")) {
          b.get("units").forEach(u -> buffChildren.add(u.get(0).asText()));
        }
      }
      List<String> eggs = new ArrayList<>();
      for (JsonNode p : reference.path("phoenix")) {
        if (p.get("event").asText().equals("untargetable_when_spawned")) {
          eggs.add(
              "%d %s %d %d"
                  .formatted(
                      p.get("tick").asInt(),
                      p.get("unit").asText(),
                      p.get("x").asInt(),
                      p.get("y").asInt()));
        }
      }
      List<String> eggSpawns = new ArrayList<>();
      for (String line : spawns) {
        String[] f = line.split(" ");
        if (eggs.stream().anyMatch(egg -> egg.split(" ")[1].equals(f[3]))) {
          eggSpawns.add(f[0] + " " + f[3] + " " + f[6] + " " + f[7]);
        }
      }
      assertThat(eggSpawns).as("every egg the Phoenix log lists").containsExactlyElementsOf(eggs);
      Set<String> eggNames = new HashSet<>();
      eggs.forEach(egg -> eggNames.add(egg.split(" ")[1]));
      // An area effect's spawns are held by the Skeleton King's log.
      Set<String> areaChildren = new HashSet<>();
      for (JsonNode k : reference.path("skeleton_king_ability")) {
        if (k.get("event").asText().equals("skeleton")) {
          areaChildren.add(k.get("unit").asText());
        }
      }
      List<String> riderSpawns =
          spawns.stream()
              .filter(line -> !buffChildren.contains(line.split(" ")[3]))
              .filter(line -> !eggNames.contains(line.split(" ")[3]))
              .filter(line -> !areaChildren.contains(line.split(" ")[3]))
              .toList();
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

    assertThat(hidingLog)
        .as("every deploy end's targeting visit and every hide counter change that shows something")
        .containsExactlyElementsOf(expectedHidingLog(reference));

    assertThat(pullLog)
        .as("every pull, its targets and their push accumulators")
        .containsExactlyElementsOf(expectedPullLog(reference));

    assertThat(deployPushLog)
        .as("every push of a unit entering its deploying state, what it found and whom it pushed")
        .containsExactlyElementsOf(expectedDeployPushLog(reference));

    assertThat(cloneLog)
        .as("every clone scheduled, made and moved apart, and every area effect an impact made")
        .containsExactlyElementsOf(expectedCloneLog(reference));
    List<String> expectedReflects = new ArrayList<>();
    for (JsonNode r : reference.path("reflects")) {
      String line =
          "%d reflect %s by %s kind %s source %s struck %s speed %d"
              .formatted(
                  r.get("tick").asInt(),
                  r.get("target").asText(),
                  r.get("attacker").isNull() ? null : r.get("attacker").asText(),
                  r.get("kind").isNull() ? null : r.get("kind").asText(),
                  r.get("source").isNull() ? null : r.get("source").asText(),
                  r.get("struck").isNull() ? null : r.get("struck").asText(),
                  r.get("hit_speed").asInt());
      if (r.has("buff")) {
        line +=
            " buff %s %d %d"
                .formatted(r.get("buff").asText(), r.get("time").asInt(), r.get("level").asInt());
      }
      if (r.has("damage")) {
        line +=
            " damage %d %d %d"
                .formatted(
                    r.get("damage").asInt(),
                    r.get("hp").get(0).asInt(),
                    r.get("hp").get(1).asInt());
      }
      expectedReflects.add(line);
    }
    assertThat(reflectLog)
        .as("every hit that reached a reflect, and what it struck back with")
        .containsExactlyElementsOf(expectedReflects);

    assertThat(hookLog)
        .as("every special load armed, every state a drag set, and every hold and pull let go")
        .containsExactlyElementsOf(expectedHookLog(reference));

    assertThat(phoenixLog)
        .as("every death projectile, egg made untargetable and spawner destroyed at its limit")
        .containsExactlyElementsOf(expectedPhoenixLog(reference));

    // The runs with a drain list their Kamikaze ends and drains; a ring's lanes are listed with the
    // actions.
    if (reference.has("kamikaze")) {
      assertThat(kamikazeLog)
          .as("every Kamikaze end and every drain")
          .containsExactlyElementsOf(expectedKamikazeLog(reference));
    }
    assertThat(laneLog)
        .as("every lane a const-priority ring asked for")
        .containsExactlyElementsOf(expectedLaneLog(reference));
    assertThat(hutLog)
        .as("every start and step of a Goblin Hut's life state, its finds, points and children")
        .containsExactlyElementsOf(expectedGoblinHutLog(reference));
    assertThat(areaEffectSpawnLog)
        .as("every area effect an action spawned, and every hit action one scheduled")
        .containsExactlyElementsOf(expectedAreaEffectSpawnLog(reference));
    assertThat(berserkLog)
        .as("every start and notice of a Berserker's index toggle")
        .containsExactlyElementsOf(expectedBerserkLog(reference));
    assertThat(goblinsteinLog)
        .as("every group chain link and unlink, and what Goblinstein's ability did")
        .containsExactlyElementsOf(expectedGoblinsteinLog(reference));
    // The reference lists its tether log only for a run in which a tether ran or a heard card
    // play acted: a run without one may hear plays that did nothing.
    List<String> tetherActed =
        reference.has("tether")
            ? tetherLog
            : tetherLog.stream().filter(l -> !l.endsWith(" -")).toList();
    assertThat(
            tetherActed.stream()
                .map(l -> l.endsWith(" -") ? l.substring(0, l.length() - 2) : l)
                .toList())
        .as(
            "every activation row, damage pass, hit and hit action of a tether, and every play heard")
        .containsExactlyElementsOf(expectedTetherLog(reference));
    List<String> expectedHandOvers = new ArrayList<>();
    for (JsonNode h : reference.path("parent_buff")) {
      List<String> instances = new ArrayList<>();
      for (JsonNode i : h.get("instances")) {
        instances.add(
            "%s %d %d %b"
                .formatted(
                    i.get(0).asText(), i.get(1).asInt(), i.get(2).asInt(), i.get(3).asBoolean()));
      }
      expectedHandOvers.add(
          "%d handed_over %s %s %s %d %d %s %s"
              .formatted(
                  h.get("tick").asInt(),
                  h.get("parent").asText(),
                  h.get("rider").asText(),
                  h.get("buff").asText(),
                  h.get("time").asInt(),
                  h.get("level").asInt(),
                  h.get("source").isNull() ? null : h.get("source").asText(),
                  instances));
    }
    assertThat(handOverLog)
        .as("every buff a parent handed to its riders, and the rider's instances of it")
        .containsExactlyElementsOf(expectedHandOvers);
    assertThat(guardLog)
        .as("every guard made, and every step of a guard spawn's runs")
        .containsExactlyElementsOf(expectedGuardLog(reference));
    assertThat(warpLog)
        .as("every start and step of a Boss Bandit ability's run, and every warp")
        .containsExactlyElementsOf(expectedWarpLog(reference));
    List<String> expectedReferenceDrops = new ArrayList<>();
    for (JsonNode d : reference.path("reference_drops")) {
      expectedReferenceDrops.add(
          "%d drop %s hit %s via %s kills %b active %b dropped %s state %d"
              .formatted(
                  d.get("tick").asInt(),
                  d.get("unit").asText(),
                  d.get("hit").asText(),
                  d.get("via").isNull() ? null : d.get("via").asText(),
                  d.get("flag").asInt() == 1,
                  d.get("active").asInt() == 1,
                  d.get("dropped").isNull() ? null : d.get("dropped").asText(),
                  d.get("state").asInt()));
    }
    assertThat(dropLog)
        .as("every hit a unit that passes over buffed targets landed, and what it dropped")
        .containsExactlyElementsOf(expectedReferenceDrops);
    assertThat(selfLocks)
        .as("every tick a unit holds its own lock as the post-hooks end")
        .containsExactlyElementsOf(expectedSelfLocks(reference));
    assertThat(goldenKnightLog)
        .as("every supplied request, ability dash, stun cleanse, chained dash and chain's end")
        .containsExactlyElementsOf(expectedGoldenKnightLog(reference));
    assertThat(skeletonKingLog)
        .as("every soul counted, the souls spent, the spawner's order and every skeleton")
        .containsExactlyElementsOf(expectedSkeletonKingLog(reference));
    assertThat(championLog)
        .as("what the champion slots did, and every ability's buff")
        .containsExactlyElementsOf(expectedChampionLog(reference));
    List<String> expectedTrace = new ArrayList<>();
    reference.path("champion").path("trace").forEach(row -> expectedTrace.add(row.toString()));
    assertThat(championTrace)
        .as("every champion slot step")
        .containsExactlyElementsOf(expectedTrace);
    assertThat(cloneGateLog)
        .as("every ask of an area effect's buff test of a clone")
        .containsExactlyElementsOf(expectedCloneGateLog(reference));
    assertThat(princeLog)
        .as("every read of attack_count and every ask of a buff's life condition")
        .containsExactlyElementsOf(expectedPrinceLog(reference));
    assertThat(killLog)
        .as("every killed-done check scheduled and every check of an action's cause")
        .containsExactlyElementsOf(expectedKillLog(reference));
    assertThat(buffAfterHitsLog)
        .as("every count that applied a BuffAfterHits buff and every buff's start or remove action")
        .containsExactlyElementsOf(expectedBuffAfterHitsLog(reference));
    assertThat(uppercutWindLog)
        .as("every uppercut, knock and wind, and every watched tag word")
        .containsExactlyElementsOf(expectedUppercutWindLog(reference));
    assertThat(evoLog.furnace() ? evoLog.lines() : List.of())
        .as("every re-trigger, force stop and launch of the evolved Furnace, its runs and its tags")
        .containsExactlyElementsOf(expectedFurnaceLog(reference));
    assertThat(iceLog)
        .as("every area effect following a projectile's target, made and updated")
        .containsExactlyElementsOf(expectedIceLog(reference));
    assertThat(axeLog)
        .as("every axe's start, controller, damage, hit action, push and swap")
        .containsExactlyElementsOf(expectedAxeLog(reference));
    assertThat(snowLog)
        .as("every rolling snowball's start, roll, buff, lock, capture, schedule and release")
        .containsExactlyElementsOf(expectedSnowballLog(reference));
    assertThat(barrageLog)
        .as("every barrage's start and its bombs' areas, and every bomb dropped")
        .containsExactlyElementsOf(expectedBarrageLog(reference));
    assertThat(hidingHookLog)
        .as("every action a hiding building scheduled as it started to hide or rose")
        .containsExactlyElementsOf(expectedHidingHookLog(reference));
    assertThat(shieldLostLog)
        .as("every action a broken shield scheduled and every charge reset a listed buff made")
        .containsExactlyElementsOf(expectedShieldLostLog(reference));
    assertThat(vinesLog)
        .as("every shape selector's start, step and removal, and every air-to-ground run")
        .containsExactlyElementsOf(expectedVinesLog(reference));
    assertThat(laserLog)
        .as("every laser ball's start and fire, and every life-end action scheduled")
        .containsExactlyElementsOf(expectedLaserLog(reference));
    assertThat(indicatorLog)
        .as("every target indicator attack's start, find, signal, shot, step and stop")
        .containsExactlyElementsOf(expectedIndicatorLog(reference));

    if (reference.has("buffs")) {
      assertThat(buffLog)
          .as("every area buff, and every buff applied, refreshed, removed and dealing damage")
          .containsExactlyElementsOf(expectedBuffLog(reference));
    } else {
      // The reference writes its buff log only for a run in which a buff was applied, so a run
      // without one holds that none was: it lists no area buff that found nobody.
      assertThat(buffLog)
          .as("no buff applied: only area buffs that found nobody")
          .allMatch(line -> line.contains(" area_buff ") && line.endsWith(" []"));
    }
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
      expectedEvents.add(eventLine(event));
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
      assertThat(unitReferenceName(unit))
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
      String owner,
      int[] currentTick,
      List<String> actions,
      List<String> dropping,
      FurnaceLog evoLog) {
    return new ActionHolder.Listener() {
      @Override
      public void starting(BattleAction action, int phase, boolean queued) {
        // A looping effect row only shows something, and the reference leaves it out, but for a
        // run that logs the evolved Furnace or the evolved Executioner's axe, which lists its
        // looping effects' runs.
        if (action instanceof InertAction inert && inert.isLasting() && !evoLog.lasting()) {
          return;
        }
        // The request a run supplies is the run's own, not the battle's.
        if (action instanceof SuppliedRequest) {
          return;
        }
        actions.add(
            "%d run %s %s %s"
                .formatted(currentTick[0], owner, action.name(), queued ? phase : "at once"));
      }

      // A singleton row's start that re-triggers its run is listed as a run too; a run that logs
      // the evolved Furnace lists it in that log instead.
      @Override
      public void retriggering(BattleAction action, int phase, boolean queued) {
        if (evoLog.furnace()) {
          evoLog
              .lines()
              .add("%d retrigger %s %s %d".formatted(currentTick[0], owner, action.name(), phase));
          return;
        }
        starting(action, phase, queued);
      }

      @Override
      public void forceStopped(ActionInstance instance) {
        evoLog
            .lines()
            .add(
                "%d force_stop %s %s"
                    .formatted(currentTick[0], owner, instance.getAction().name()));
      }

      @Override
      public void dropped(BattleAction action, int ticksLeft) {
        dropping.add(
            "%d dropped %s %s %d".formatted(currentTick[0], owner, action.name(), ticksLeft));
      }
    };
  }

  /**
   * What the evolved Furnace's runs did, for a run whose reference logs them: each re-trigger,
   * force stop and projectile launch, and, as each tick ends, its listed runs and its three tags
   * whenever they change.
   *
   * @param furnace true when the reference logs the evolved Furnace
   * @param lasting true when the reference lists the looping effects' runs
   * @param lines the log
   */
  private record FurnaceLog(boolean furnace, boolean lasting, List<String> lines) {}

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
                        a.getCountdown())
                + (a.getParent() == null ? "" : " parent " + a.getParent().name()));
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

      @Override
      public void areaEffectLaunched(
          int tick,
          AreaEffectEntity a,
          int hit,
          int bound,
          AreaEffectEntity.Choice choice,
          ProjectileEntity p) {
        String line =
            "%d launch %s hit %d bound %d".formatted(currentTick[0], a.name(), hit, bound);
        if (choice != null) {
          line +=
              " reach %d candidates %s refused %s listed %s chosen %s"
                  .formatted(
                      choice.reach(),
                      choice.candidates().stream()
                          .map(c -> c.target().name() + " " + c.size())
                          .toList(),
                      choice.refused().stream().map(WorldEntity::name).toList(),
                      choice.struck(),
                      choice.chosen() == null ? null : choice.chosen().name());
        }
        if (p != null) {
          line +=
              " %s %s target %s at %d %d %d aim %d %d level %d side %d"
                  .formatted(
                      p.name(),
                      p.getData().name(),
                      p.getTarget() == null ? null : p.getTarget().name(),
                      p.getX(),
                      p.getY(),
                      p.getZ(),
                      p.getAimX(),
                      p.getAimY(),
                      p.getPackedLevel(),
                      p.side());
        }
        lines.add(line);
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
                            a.get("countdown").asInt())
                    // The battle keeps the parent of an area effect an action made, a target
                    // indicator attack made as its signal, Goblinstein's ability made as its
                    // death area, a unit's ability made at the unit, an evolved Royal Ghost's
                    // run made, or a resetable action made, and only that.
                    + (Set.of(
                                    "action",
                                    "target_indicator",
                                    "goblinstein_death",
                                    "ability",
                                    "ghost_evo",
                                    "resetable")
                                .contains(a.get("how").asText())
                            && !a.get("parent").isNull()
                        ? " parent " + a.get("parent").asText()
                        : ""));
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
        case "launch" -> {
          String line =
              "%d launch %s hit %d bound %d"
                  .formatted(tick, name, a.get("hit").asInt(), a.get("bound").asInt());
          if (a.has("chosen")) {
            List<String> candidates = new ArrayList<>();
            a.get("candidates")
                .forEach(c -> candidates.add(c.get(0).asText() + " " + c.get(1).asInt()));
            List<String> refused = new ArrayList<>();
            a.get("refused").forEach(r -> refused.add(r.asText()));
            List<Integer> listed = new ArrayList<>();
            a.get("listed").forEach(l -> listed.add(l.asInt()));
            line +=
                " reach %d candidates %s refused %s listed %s chosen %s"
                    .formatted(
                        a.get("reach").asInt(),
                        candidates,
                        refused,
                        listed,
                        a.get("chosen").isNull() ? null : a.get("chosen").asText());
          }
          if (a.has("projectile")) {
            line +=
                " %s %s target %s at %d %d %d aim %d %d level %d side %d"
                    .formatted(
                        a.get("projectile").asText(),
                        a.get("config").asText(),
                        a.get("target").isNull() ? null : a.get("target").asText(),
                        a.get("x").asInt(),
                        a.get("y").asInt(),
                        a.get("z").asInt(),
                        a.get("aim").get(0).asInt(),
                        a.get("aim").get(1).asInt(),
                        a.get("level").asInt(),
                        a.get("side").asInt());
          }
          expected.add(line);
        }
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
        // A placement comes before the first step, on the tick it is placed for.
        lines.add(
            "%d applied %s %s %s %d %d %s"
                .formatted(
                    currentTick[0] < 0 ? tick : currentTick[0],
                    target.name(),
                    buff.getBuff().name(),
                    buff.getKey(),
                    buff.getRemaining(),
                    buff.getPackedLevel(),
                    buff.getSource() == null ? null : buff.getSource().name()));
      }

      @Override
      public void buffCopied(int tick, WorldEntity original, WorldEntity clone, BuffInstance copy) {
        lines.add(
            "%d copied %s %s %s %d %d %s from %s"
                .formatted(
                    currentTick[0],
                    clone.name(),
                    copy.getBuff().name(),
                    copy.getKey(),
                    copy.getRemaining(),
                    copy.getPackedLevel(),
                    copy.getSource() == null ? null : copy.getSource().name(),
                    original.name()));
      }

      @Override
      public void buffRefreshed(
          int tick, WorldEntity target, BuffInstance buff, int before, SpawnHost source) {
        // The reference names what the re-application came from.
        lines.add(
            "%d refreshed %s %s %s %d %d %s"
                .formatted(
                    currentTick[0],
                    target.name(),
                    buff.getBuff().name(),
                    buff.getKey(),
                    before,
                    buff.getRemaining(),
                    source == null ? null : source.name()));
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

      @Override
      public void buffHealed(
          int tick, WorldEntity target, BuffInstance buff, int amount, int hitPointsBefore) {
        lines.add(
            "%d heal %s %d %d %d %d"
                .formatted(
                    currentTick[0],
                    target.name(),
                    amount,
                    hitPointsBefore,
                    target.getHitPoints().getHitPoints(),
                    target.getHitPoints().getMaximum()));
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
        case "copied" ->
            expected.add(
                "%d copied %s %s %s %d %d %s from %s"
                    .formatted(
                        tick,
                        b.get("target").asText(),
                        b.get("buff").asText(),
                        b.get("key").asText(),
                        b.get("remaining").asInt(),
                        b.get("level").asInt(),
                        source,
                        b.get("original").asText()));
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
        case "heal" ->
            expected.add(
                "%d heal %s %d %d %d %d"
                    .formatted(
                        tick,
                        b.get("target").asText(),
                        b.get("amount").asInt(),
                        b.get("hp").get(0).asInt(),
                        b.get("hp").get(1).asInt(),
                        b.get("max").asInt()));
        default -> throw new IllegalStateException("unknown buff event " + b);
      }
    }
    return expected;
  }

  /**
   * Lists what every Clone does, and every area effect an impact makes, in the reference's layout.
   *
   * @param everyBuffSpawn true for a reference that lists every buff a spawn applies, as the
   *     evolved Snowball's does
   */
  static WorldObserver cloneLog(int[] currentTick, List<String> lines, boolean everyBuffSpawn) {
    return new WorldObserver() {
      @Override
      public void projectileAreaEffect(int tick, ProjectileEntity p, AreaEffectEntity a) {
        lines.add(
            "%d area_effect_from_projectile %s %s at %d %d level %d target %s"
                .formatted(
                    currentTick[0],
                    a.name(),
                    p.name(),
                    a.getX(),
                    a.getY(),
                    a.getPackedLevel(),
                    p.getTarget() == null ? null : p.getTarget().name()));
      }

      @Override
      public void onHitActionScheduled(
          int tick, AreaEffectEntity a, WorldEntity target, BattleAction action) {
        // The hit actions of an area effect that does not clone are in the area-effect spawn log.
        if (a.getData().cloning()) {
          lines.add(
              "%d scheduled %s %s %s"
                  .formatted(currentTick[0], a.name(), target.name(), action.name()));
        }
      }

      @Override
      public void buffSpawned(
          int tick,
          WorldEntity owner,
          String action,
          BuffData buff,
          int time,
          int packedLevel,
          SpawnHost source) {
        // The reference lists the buffs a Clone's actions spawn and those a projectile's hit
        // action spawns, or every one; every buff any spawn applies is in the buff log.
        if (!everyBuffSpawn
            && !(source instanceof AreaEffectEntity a && a.getData().cloning())
            && !(source instanceof ProjectileEntity)) {
          return;
        }
        lines.add(
            "%d buff_spawn %s %s %s %d %d %s"
                .formatted(
                    currentTick[0],
                    owner.name(),
                    action,
                    buff.name(),
                    time,
                    packedLevel,
                    source.name()));
      }

      @Override
      public void cloneRefused(
          int tick, WorldEntity original, String reason, SpawnHost instigator) {
        lines.add(
            "%d refused %s %s %s"
                .formatted(currentTick[0], original.name(), reason, instigator.name()));
      }

      @Override
      public void cloned(
          int tick,
          CharacterEntity original,
          CharacterEntity clone,
          SpawnHost instigator,
          List<Integer> registrationVisits) {
        HitPoints hp = clone.getHitPoints();
        lines.add(
            "%d clone %s %s %d %s at %d %d side %d level %d hp %d shield %s state %d deploy %d"
                    .formatted(
                        currentTick[0],
                        original.name(),
                        clone.name(),
                        clone.getId(),
                        clone.getData().name(),
                        clone.getView().getX(),
                        clone.getView().getY(),
                        clone.side(),
                        clone.getPackedLevel(),
                        hp.getHitPoints(),
                        List.of(hp.getShield(), hp.getShieldMaximum()),
                        clone.getView().getState(),
                        clone.getView().getDeployCountdown())
                + " visits %s by %s".formatted(registrationVisits, instigator.name()));
      }

      @Override
      public void cloneMoveStarted(int tick, CharacterEntity unit, int targetX, int targetY) {
        lines.add(
            "%d move_start %s at %d %d target %d %d route %s"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    unit.getView().getX(),
                    unit.getView().getY(),
                    targetX,
                    targetY,
                    Arrays.toString(unit.getUnit().movement().getRoute().toArray())));
      }

      @Override
      public void cloneMoveEnded(int tick, CharacterEntity unit, boolean resumed) {
        lines.add(
            "%d move_end %s at %d %d state %d resumed %s"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    unit.getView().getX(),
                    unit.getView().getY(),
                    unit.getView().getState(),
                    resumed));
      }
    };
  }

  /**
   * Lists every area effect an action spawned and every hit action an area effect that does not
   * clone scheduled, in the reference's layout.
   */
  static WorldObserver areaEffectSpawnLog(int[] currentTick, List<String> lines) {
    return new WorldObserver() {
      @Override
      public void areaEffectSpawned(
          int tick,
          SpawnHost owner,
          String action,
          int phase,
          SpawnHost source,
          AreaEffectEntity a) {
        lines.add(
            ("%d spawn_area_effect %s %s phase %d source %s %s %d at %d %d side %d level %d parent %s"
                    + " follow %s")
                .formatted(
                    currentTick[0],
                    owner.name(),
                    action,
                    phase,
                    source.name(),
                    a.name(),
                    a.getId(),
                    a.getX(),
                    a.getY(),
                    a.side(),
                    a.getPackedLevel(),
                    a.getParent().name(),
                    a.getFollow() == null ? null : a.getFollow().name()));
      }

      @Override
      public void tauntPerformed(
          int tick,
          WorldEntity unit,
          String action,
          int phase,
          ActionOwner instigator,
          WorldEntity forced) {
        lines.add(
            "%d taunt_perform %s %s phase %d instigator %s parent %s"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    action,
                    phase,
                    ((AreaEffectEntity) instigator).name(),
                    forced.name()));
      }

      @Override
      public void tauntStepped(
          int tick,
          WorldEntity unit,
          WorldEntity forced,
          int durationMs,
          int falloffMs,
          List<String> calls) {
        lines.add(
            "%d taunt %s forced %s duration %d falloff %d calls %s ref %s cooldown %d"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    forced == null ? null : forced.name(),
                    durationMs,
                    falloffMs,
                    calls,
                    referenceName(unit),
                    unit.getTargeting().getRetargetCooldownMs()));
      }

      @Override
      public void onHitActionScheduled(
          int tick, AreaEffectEntity a, WorldEntity target, BattleAction action) {
        if (!a.getData().cloning()) {
          lines.add(
              "%d scheduled %s %s %s"
                  .formatted(currentTick[0], a.name(), target.name(), action.name()));
        }
      }
    };
  }

  /**
   * The reference's area-effect spawn log in the same layout. No area effect it lists carries a
   * clone byte, which the battle refuses, and every taunt it lists reaches one unit, which carries
   * no riders.
   */
  static List<String> expectedAreaEffectSpawnLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode e : reference.path("area_effect_spawn")) {
      int tick = e.get("tick").asInt();
      switch (e.get("event").asText()) {
        case "spawn_area_effect" -> {
          assertThat(e.get("b119").asInt())
              .as("tick %d: the area effect is no clone's", tick)
              .isZero();
          expected.add(
              ("%d spawn_area_effect %s %s phase %d source %s %s %d at %d %d side %d level %d"
                      + " parent %s follow %s")
                  .formatted(
                      tick,
                      e.get("owner").asText(),
                      e.get("action").asText(),
                      e.get("phase").asInt(),
                      e.get("source").asText(),
                      e.get("area_effect").asText(),
                      e.get("id").asInt(),
                      e.get("x").asInt(),
                      e.get("y").asInt(),
                      e.get("side").asInt(),
                      e.get("level").asInt(),
                      e.get("parent").asText(),
                      e.get("follow").isNull() ? null : e.get("follow").asText()));
        }
        case "taunt_perform" -> {
          assertThat(e.get("instances").asInt()).as("tick %d: one unit taunted", tick).isOne();
          expected.add(
              "%d taunt_perform %s %s phase %d instigator %s parent %s"
                  .formatted(
                      tick,
                      e.get("unit").asText(),
                      e.get("action").asText(),
                      e.get("phase").asInt(),
                      e.get("instigator").asText(),
                      e.get("parent").asText()));
        }
        case "taunt" -> {
          List<String> calls = new ArrayList<>();
          for (JsonNode c : e.get("calls")) {
            calls.add(
                switch (c.get(0).asText()) {
                  case "set_target" ->
                      "set_target %s %d %d %d"
                          .formatted(
                              c.get(2).isNull() ? null : c.get(2).asText(),
                              c.get(3).asInt(),
                              c.get(4).asInt(),
                              c.get(5).asInt());
                  case "raise" -> "raise " + c.get(2).asText();
                  case "remaining" -> "remaining " + c.get(2).asInt();
                  case "apply_buff" ->
                      "apply_buff %s %d level %d source %s side %d"
                          .formatted(
                              c.get(3).asText(),
                              c.get(4).asInt(),
                              c.get(5).asInt(),
                              c.get(6).asText(),
                              c.get(7).asInt());
                  case "remove_buff" -> "remove_buff " + c.get(3).asText();
                  case "finish" -> "finish";
                  default -> throw new IllegalStateException("unknown taunt call " + c);
                });
          }
          expected.add(
              "%d taunt %s forced %s duration %d falloff %d calls %s ref %s cooldown %d"
                  .formatted(
                      tick,
                      e.get("unit").asText(),
                      e.get("forced").isNull() ? null : e.get("forced").asText(),
                      e.get("duration").asInt(),
                      e.get("falloff").asInt(),
                      calls,
                      e.get("ref").isNull() ? null : e.get("ref").asText(),
                      e.get("f1c").asInt()));
        }
        case "scheduled" ->
            expected.add(
                "%d scheduled %s %s %s"
                    .formatted(
                        tick,
                        e.get("area_effect").asText(),
                        e.get("target").asText(),
                        e.get("action").asText()));
        default -> throw new IllegalStateException("unknown area-effect spawn event " + e);
      }
    }
    return expected;
  }

  /** The reference's Clone log in the same layout. */
  static List<String> expectedCloneLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode c : reference.path("clones")) {
      int tick = c.get("tick").asInt();
      switch (c.get("event").asText()) {
        case "area_effect_from_projectile" ->
            expected.add(
                "%d area_effect_from_projectile %s %s at %d %d level %d target %s"
                    .formatted(
                        tick,
                        c.get("area_effect").asText(),
                        c.get("projectile").asText(),
                        c.get("x").asInt(),
                        c.get("y").asInt(),
                        c.get("level").asInt(),
                        c.get("target").isNull() ? null : c.get("target").asText()));
        case "scheduled" ->
            expected.add(
                "%d scheduled %s %s %s"
                    .formatted(
                        tick,
                        c.get("area_effect").asText(),
                        c.get("target").asText(),
                        c.get("action").asText()));
        case "buff_spawn" ->
            expected.add(
                "%d buff_spawn %s %s %s %d %d %s"
                    .formatted(
                        tick,
                        c.get("owner").asText(),
                        c.get("action").asText(),
                        c.get("buff").asText(),
                        c.get("time").asInt(),
                        c.get("level").asInt(),
                        c.get("source").asText()));
        case "refused" ->
            expected.add(
                "%d refused %s %s %s"
                    .formatted(
                        tick,
                        c.get("original").asText(),
                        c.get("reason").asText(),
                        c.get("instigator").asText()));
        case "clone" -> {
          List<Integer> shield = new ArrayList<>();
          c.get("shield").forEach(v -> shield.add(v.asInt()));
          List<Integer> visits = new ArrayList<>();
          c.get("registration_visits").forEach(v -> visits.add(v.asInt()));
          expected.add(
              "%d clone %s %s %d %s at %d %d side %d level %d hp %d shield %s state %d deploy %d"
                      .formatted(
                          tick,
                          c.get("original").asText(),
                          c.get("clone").asText(),
                          c.get("id").asInt(),
                          c.get("row").asText(),
                          c.get("x").asInt(),
                          c.get("y").asInt(),
                          c.get("side").asInt(),
                          c.get("level").asInt(),
                          c.get("hp").asInt(),
                          shield,
                          c.get("state").asInt(),
                          c.get("deploy").asInt())
                  + " visits %s by %s".formatted(visits, c.get("instigator").asText()));
        }
        case "move_start" -> {
          List<Integer> route = new ArrayList<>();
          c.get("route").forEach(v -> route.add(v.asInt()));
          expected.add(
              "%d move_start %s at %d %d target %d %d route %s"
                  .formatted(
                      tick,
                      c.get("unit").asText(),
                      c.get("x").asInt(),
                      c.get("y").asInt(),
                      c.get("target").get(0).asInt(),
                      c.get("target").get(1).asInt(),
                      route));
        }
        case "move_end" ->
            expected.add(
                "%d move_end %s at %d %d state %d resumed %s"
                    .formatted(
                        tick,
                        c.get("unit").asText(),
                        c.get("x").asInt(),
                        c.get("y").asInt(),
                        c.get("state").asInt(),
                        c.get("resumed").asBoolean()));
        default -> throw new IllegalStateException("unknown clone event " + c);
      }
    }
    return expected;
  }

  /** Logs every special load armed, every state a drag set, and every hold and pull let go. */
  private static WorldObserver hookLog(int[] currentTick, List<String> log) {
    return new WorldObserver() {
      @Override
      public void specialArmed(
          int tick,
          CharacterEntity unit,
          WorldEntity reference,
          long distanceSquared,
          int ringMin,
          int ringMax,
          int loadMs,
          int afterMs) {
        log.add(
            "%d arm %s ref %s distance %d ring %d %d load %d after %d"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    reference.name(),
                    distanceSquared,
                    ringMin,
                    ringMax,
                    loadMs,
                    afterMs));
      }

      @Override
      public void dragStateSet(
          int tick, ProjectileEntity projectile, WorldEntity unit, int oldState, int newState) {
        GridEntity view = unit.getView();
        log.add(
            "%d state %s %s %d to %d now %d at %d %d"
                .formatted(
                    currentTick[0],
                    attackerName(projectile),
                    unit.name(),
                    oldState,
                    newState,
                    view.getState(),
                    view.getX(),
                    view.getY()));
      }

      @Override
      public void holdLeft(int tick, WorldEntity unit, ProjectileEntity projectile) {
        log.add(
            "%d held_left %s %s".formatted(currentTick[0], unit.name(), attackerName(projectile)));
      }

      @Override
      public void followLeft(int tick, WorldEntity unit, ProjectileEntity projectile) {
        GridEntity view = unit.getView();
        log.add(
            "%d follow_left %s %s now %d at %d %d"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    attackerName(projectile),
                    view.getState(),
                    view.getX(),
                    view.getY()));
      }
    };
  }

  /**
   * Logs every death projectile with its start and aim, every unit made with the immunity its row
   * starts it with, and every spawner destroyed at its limit with its hit points.
   */
  private static WorldObserver phoenixLog(int[] currentTick, List<String> log) {
    return new WorldObserver() {
      @Override
      public void deathProjectileLaunched(
          int tick, WorldEntity dying, ProjectileEntity projectile) {
        log.add(
            "%d death_projectile %s %s %s start %d %d %d aim %d %d"
                .formatted(
                    currentTick[0],
                    dying.name(),
                    projectile.name(),
                    projectile.getData().name(),
                    projectile.getStartX(),
                    projectile.getStartY(),
                    projectile.getStartZ(),
                    projectile.getAimX(),
                    projectile.getAimY()));
      }

      @Override
      public void characterSpawned(
          int tick, SpawnHost source, CharacterEntity child, int x, int y) {
        if (child.getData().untargetableWhenSpawned() && child.isSpawnImmune()) {
          log.add(
              "%d untargetable_when_spawned %s at %d %d"
                  .formatted(currentTick[0], child.name(), x, y));
        }
      }

      @Override
      public void destroyedAtLimit(int tick, CharacterEntity spawner) {
        GridEntity view = spawner.getView();
        log.add(
            "%d destroy_at_limit %s at %d %d hp %d"
                .formatted(
                    currentTick[0],
                    spawner.name(),
                    view.getX(),
                    view.getY(),
                    spawner.getHitPoints().getHitPoints()));
      }
    };
  }

  /** The reference's Phoenix log, in the Phoenix log's layout. */
  private static List<String> expectedPhoenixLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode p : reference.path("phoenix")) {
      int tick = p.get("tick").asInt();
      switch (p.get("event").asText()) {
        case "death_projectile" ->
            expected.add(
                "%d death_projectile %s %s %s start %d %d %d aim %d %d"
                    .formatted(
                        tick,
                        p.get("unit").asText(),
                        p.get("projectile").asText(),
                        p.get("config").asText(),
                        p.get("start").get(0).asInt(),
                        p.get("start").get(1).asInt(),
                        p.get("start").get(2).asInt(),
                        p.get("aim").get(0).asInt(),
                        p.get("aim").get(1).asInt()));
        case "untargetable_when_spawned" ->
            expected.add(
                "%d untargetable_when_spawned %s at %d %d"
                    .formatted(
                        tick, p.get("unit").asText(), p.get("x").asInt(), p.get("y").asInt()));
        case "destroy_at_limit" ->
            expected.add(
                "%d destroy_at_limit %s at %d %d hp %d"
                    .formatted(
                        tick,
                        p.get("unit").asText(),
                        p.get("x").asInt(),
                        p.get("y").asInt(),
                        p.get("hp").asInt()));
        default -> throw new IllegalStateException("unknown Phoenix event " + p);
      }
    }
    return expected;
  }

  /**
   * Logs every Kamikaze end with the hit points it found and whether it killed, every drain with
   * what it took and the hit points before and after, and every lane a const-priority ring asked
   * for at its source's point.
   */
  private static WorldObserver kamikazeLog(
      int[] currentTick, List<String> log, List<String> lanes) {
    return new WorldObserver() {
      @Override
      public void kamikazeHitEnded(int tick, WorldEntity unit, boolean kills) {
        log.add(
            "%d end %s hp %d kill %s"
                .formatted(currentTick[0], unit.name(), unit.getHitPoints().getHitPoints(), kills));
      }

      @Override
      public void kamikazeDrained(
          int tick, WorldEntity unit, int damage, int hitPointsBefore, DamageResult result) {
        log.add(
            "%d drain %s %d hp %d before %d"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    damage,
                    unit.getHitPoints().getHitPoints(),
                    hitPointsBefore));
      }

      @Override
      public void ringLaneAsked(int tick, SpawnHost source, int x, int y, int lane) {
        lanes.add(
            "%d const_priority_lane %s %d %d lane %d"
                .formatted(currentTick[0], source.name(), x, y, lane));
      }
    };
  }

  /** Logs every start and notice of a Berserker's index toggle, with the index before and after. */
  private static WorldObserver berserkLog(int[] currentTick, List<String> log) {
    return new WorldObserver() {
      @Override
      public void berserked(
          int tick, WorldEntity unit, Berserk.Event event, int before, int index) {
        log.add(
            "%d berserk %s %s %d %d"
                .formatted(
                    currentTick[0],
                    event.name().toLowerCase(Locale.ROOT),
                    unit.name(),
                    before,
                    index));
      }
    };
  }

  /**
   * Logs every ask of an area effect's buff test of a clone: the area effect, its buff, the clone,
   * the path that asked, the query and whether it refused.
   */
  private static WorldObserver cloneGateLog(int[] currentTick, List<String> log) {
    return new WorldObserver() {
      @Override
      public void cloneBuffGateAsked(
          int tick,
          AreaEffectEntity areaEffect,
          String buff,
          CharacterEntity clone,
          String path,
          int query,
          boolean refused) {
        log.add(
            "%d gate %s %s %s %s query %d refused %b"
                .formatted(
                    currentTick[0], areaEffect.name(), buff, clone.name(), path, query, refused));
      }
    };
  }

  /**
   * Logs every read of attack_count, with the attack time it divided, and every ask of a buff's
   * life condition, with its expression and answer.
   */
  private static WorldObserver princeLog(int[] currentTick, List<String> log) {
    return new WorldObserver() {
      @Override
      public void attackCountRead(int tick, WorldEntity context, int attackTimeMs, int count) {
        log.add(
            "%d attack_count %s %d %d"
                .formatted(currentTick[0], context.name(), attackTimeMs, count));
      }

      @Override
      public void lifeConditionAsked(int tick, WorldEntity carrier, BuffInstance buff, int answer) {
        log.add(
            "%d alive_if %s %s %d"
                .formatted(currentTick[0], carrier.name(), buff.getBuff().aliveIfTrue(), answer));
      }
    };
  }

  /**
   * Logs every count that applied a BuffAfterHits buff, with the counter before and after, and
   * every buff's start or remove action as it is scheduled, with whether its carrier's own pending
   * pass was running.
   */
  private static WorldObserver buffAfterHitsLog(List<String> log) {
    return new WorldObserver() {
      @Override
      public void hitCounted(
          int tick,
          WorldEntity attacker,
          WorldEntity target,
          int before,
          int after,
          String buff,
          int timeMs) {
        if (buff != null) {
          log.add(
              "%d buff_after_hits %s %s %d %d %s %d"
                  .formatted(tick, attacker.name(), target.name(), before, after, buff, timeMs));
        }
      }

      @Override
      public void ghostEvoStarted(int tick, CharacterEntity ghost, String action, int phase) {
        log.add("%d ghost_evo_start %s %s %d".formatted(tick, ghost.name(), action, phase));
      }

      @Override
      public void ghostAreaMade(
          int tick, CharacterEntity ghost, AreaEffectEntity area, WorldEntity target) {
        log.add(
            "%d area %s %s %s %d %d %s %d"
                .formatted(
                    tick,
                    ghost.name(),
                    area.getData().name(),
                    area.name(),
                    area.getX(),
                    area.getY(),
                    target == null ? null : target.name(),
                    area.packedLevel()));
      }

      @Override
      public void ghostSummoned(
          int tick, CharacterEntity ghost, WorldEntity reference, int x, int y, int countdownMs) {
        log.add(
            "%d summon %s %s %d %d %d"
                .formatted(tick, ghost.name(), reference.name(), x, y, countdownMs));
      }

      @Override
      public void ghostSummonSpawned(int tick, AreaEffectEntity area, CharacterEntity summon) {
        TargetView reference = summon.getTargeting().getReference();
        log.add(
            "%d summoned %s %s %d %d %d %d %d %d %s %d %d"
                .formatted(
                    tick,
                    area.name(),
                    summon.name(),
                    summon.getId(),
                    summon.getView().getX(),
                    summon.getView().getY(),
                    summon.getView().getState(),
                    summon.getView().getDeployCountdown(),
                    summon.getPackedLevel(),
                    reference == null ? null : reference.getEntity().getName(),
                    summon.getView().getDirX(),
                    summon.getView().getDirY()));
      }

      @Override
      public void buffHookScheduled(
          int tick, WorldEntity carrier, BuffInstance buff, String action, boolean start) {
        log.add(
            "%d buff_hook %s %s %b"
                .formatted(tick, carrier.name(), action, carrier.actionHolder().passPhase() != 0));
      }
    };
  }

  /**
   * Logs every action a broken shield scheduled, with what broke it and whether a pending pass was
   * in progress, and every charge reset a listed buff that gives a charge range made.
   */
  private static WorldObserver shieldLostLog(List<String> log) {
    return new WorldObserver() {
      @Override
      public void shieldLostScheduled(
          int tick, WorldEntity unit, String action, SpawnHost cause, boolean inPendingPass) {
        log.add(
            "%d scheduled %s %s %s %b"
                .formatted(
                    tick, unit.name(), action, cause == null ? null : cause.name(), inPendingPass));
      }

      @Override
      public void buffChargeReset(
          int tick, CharacterEntity unit, BuffInstance instance, int before, int after) {
        log.add("%d charge_reset %s %d %d".formatted(tick, unit.name(), before, after));
      }
    };
  }

  /** The names of the watched tags a word carries, in the reference's order. */
  private static String tagNames(BattleWorld world, long word) {
    List<String> names = new ArrayList<>();
    String[] all = {"NO_MOVE", "NO_ATTACK", "LOCK_TARGET", "FORCE_IS_AIR", "DISABLE_PHYSICAL"};
    long[] bits = {
      BITS.noMove(), BITS.noAttack(), BITS.lockTarget(), world.forceIsAir(), BITS.disablePhysical()
    };
    for (int i = 0; i < all.length; i++) {
      if ((word & bits[i]) != 0) {
        names.add(all[i]);
      }
    }
    return names.toString();
  }

  /** A name, or null for no entity. */
  private static String nameOf(WorldEntity entity) {
    return entity == null ? null : entity.name();
  }

  /**
   * Logs what every uppercut, knock and wind did, every change of a watched tag word, every
   * rectangle's list, every choice by team and every action run at an age.
   */
  private static WorldObserver uppercutWindLog(BattleWorld world, List<String> log) {
    return new WorldObserver() {
      @Override
      public void tagWordChanged(int tick, WorldEntity entity, long word) {
        log.add("%d tags %s %s".formatted(tick, entity.name(), tagNames(world, word)));
      }

      @Override
      public void uppercutStarted(
          int tick,
          CharacterEntity unit,
          String action,
          int phase,
          WorldEntity instigator,
          WorldEntity target,
          boolean finished) {
        log.add(
            "%d uppercut_start %s %d %s %s %b"
                .formatted(tick, unit.name(), phase, nameOf(instigator), nameOf(target), finished));
      }

      @Override
      public void uppercutStepped(
          int tick,
          CharacterEntity unit,
          int delay,
          boolean finished,
          String outcome,
          int[] pushPoint) {
        log.add(
            "%d uppercut_update %s %d %b %s %s %s"
                .formatted(
                    tick,
                    unit.name(),
                    delay,
                    finished,
                    outcome,
                    pushPoint == null ? null : List.of(pushPoint[0], pushPoint[1]),
                    pushPoint == null ? null : List.of(pushPoint[2], pushPoint[3])));
      }

      @Override
      public void uppercutMarked(
          int tick, CharacterEntity unit, WorldEntity target, int priority, WorldEntity current) {
        log.add(
            "%d uppercut_mark %s %s %d %s"
                .formatted(tick, unit.name(), target.name(), priority, nameOf(current)));
      }

      @Override
      public void targetQueueFlushed(int tick, CharacterEntity unit) {
        log.add("%d queue_flushed %s".formatted(tick, unit.name()));
      }

      @Override
      public void uppercutTargetLeft(int tick, CharacterEntity unit, WorldEntity target) {
        log.add("%d uppercut_target_left %s %s".formatted(tick, unit.name(), target.name()));
      }

      @Override
      public void knockbackStarted(
          int tick,
          CharacterEntity unit,
          String action,
          int phase,
          WorldEntity instigator,
          int counter) {
        log.add(
            "%d knockback_start %s %d %s %d %b"
                .formatted(tick, unit.name(), phase, nameOf(instigator), counter, false));
      }

      @Override
      public void knockbackStepped(
          int tick,
          CharacterEntity unit,
          int before,
          int after,
          int height,
          long tags,
          boolean finished) {
        log.add(
            "%d knockback_update %s %d %d %d %s %b"
                .formatted(
                    tick, unit.name(), before, after, height, tagNames(world, tags), finished));
      }

      @Override
      public void resetableStarted(
          int tick,
          CharacterEntity unit,
          int phase,
          WorldEntity instigator,
          AreaEffectEntity areaEffect,
          int x,
          int y) {
        log.add(
            "%d wind_start %s %d %s %s %d %d"
                .formatted(tick, unit.name(), phase, nameOf(instigator), areaEffect.name(), x, y));
      }

      @Override
      public void resetableEnded(int tick, CharacterEntity unit, String areaEffect) {
        log.add("%d wind_update %s %s %b".formatted(tick, unit.name(), "area effect gone", true));
      }

      @Override
      public void resetableRetriggered(
          int tick, CharacterEntity unit, int phase, String areaEffect, Integer countdown) {
        log.add(
            "%d wind_retrigger %s %d %s %s"
                .formatted(tick, unit.name(), phase, areaEffect, countdown));
      }

      @Override
      public void resetableLeft(int tick, CharacterEntity unit, String areaEffect) {
        log.add("%d wind_left %s %s".formatted(tick, unit.name(), areaEffect));
      }

      @Override
      public void resetableReleased(
          int tick, CharacterEntity unit, String areaEffect, int before, int after) {
        log.add(
            "%d wind_release %s %s %s %d %d"
                .formatted(tick, unit.name(), "owner left", areaEffect, before, after));
      }

      @Override
      public void shapeListed(int tick, AreaEffectEntity areaEffect, List<WorldEntity> listed) {
        log.add(
            "%d wind_collect %s %d %d %d %s"
                .formatted(
                    tick,
                    areaEffect.name(),
                    areaEffect.getX(),
                    areaEffect.getY(),
                    areaEffect.getCountdown(),
                    listed.stream().map(WorldEntity::name).toList()));
      }

      @Override
      public void filteredByTeam(
          int tick,
          WorldEntity unit,
          String action,
          SpawnHost instigator,
          boolean sameTeam,
          String chosen) {
        log.add(
            "%d filter_by_enemy %s %s %d %s"
                .formatted(
                    tick,
                    unit.name(),
                    instigator == null ? null : instigator.name(),
                    sameTeam ? 1 : 0,
                    chosen));
      }

      @Override
      public void aliveTimerFired(int tick, AreaEffectEntity areaEffect, String action) {
        log.add("%d alive_timer %s %s".formatted(tick, areaEffect.name(), action));
      }
    };
  }

  /** A list of numbers in the reference as the log writes it, or null. */
  private static String numbers(JsonNode list) {
    if (list == null || list.isNull()) {
      return null;
    }
    List<Integer> out = new ArrayList<>();
    list.forEach(n -> out.add(n.asInt()));
    return out.toString();
  }

  /** A list of names in the reference as the log writes it. */
  private static String names(JsonNode list) {
    List<String> out = new ArrayList<>();
    list.forEach(n -> out.add(n.asText()));
    return out.toString();
  }

  /** The reference's log of the area effects that follow a projectile's target, in its order. */
  private static List<String> expectedIceLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode e : reference.path("ice_spirits_evo")) {
      int tick = e.get("tick").asInt();
      String follow = e.get("follow").asText(null);
      if (e.get("event").asText().equals("made")) {
        expected.add(
            "%d made %s %s %d %d %s %d %d"
                .formatted(
                    tick,
                    e.get("area_effect").asText(),
                    e.get("projectile").asText(),
                    e.get("x").asInt(),
                    e.get("y").asInt(),
                    follow,
                    e.get("level").asInt(),
                    e.get("countdown").asInt()));
      } else {
        expected.add(
            "%d update %s %d %d %s %d %d"
                .formatted(
                    tick,
                    e.get("area_effect").asText(),
                    e.get("x").asInt(),
                    e.get("y").asInt(),
                    follow,
                    e.get("countdown").asInt(),
                    e.get("hits").asInt()));
      }
    }
    return expected;
  }

  /**
   * What the evolved Snowball's runs did, as its log writes it: each projectile's start and swap,
   * each roll's start with its destination, its steps and its buffs, each capture's start, lock
   * request, step and schedule, and each run that waited for its cause to leave.
   */
  private static WorldObserver snowballLog(Standard1v1Battle match, List<String> log) {
    return new WorldObserver() {
      @Override
      public void rollStarted(
          int tick,
          ProjectileEntity projectile,
          String action,
          int phase,
          int destinationX,
          int destinationY) {
        log.add(
            "%d instance %s %s %d [%d, %d]"
                .formatted(tick, projectile.name(), action, phase, destinationX, destinationY));
      }

      @Override
      public void captureStarted(int tick, BattleEntity owner, String action, int phase) {
        log.add("%d instance %s %s %d".formatted(tick, nameOf(owner), action, phase));
      }

      @Override
      public void rollBuffed(
          int tick, ProjectileEntity projectile, WorldEntity target, String buff, int timeMs) {
        log.add(
            "%d roll_buff %s %s %s %d"
                .formatted(tick, projectile.name(), target.name(), buff, timeMs));
      }

      @Override
      public void rolled(int tick, ProjectileEntity projectile, boolean released) {
        // The release is listed before the step that made it.
        if (released) {
          log.add(
              "%d roll_end %s %d %d"
                  .formatted(tick, projectile.name(), projectile.getX(), projectile.getY()));
        }
        log.add(
            "%d roll %s %d %d %d"
                .formatted(
                    tick,
                    projectile.name(),
                    projectile.getX(),
                    projectile.getY(),
                    released ? 1 : 0));
      }

      @Override
      public void captureRequested(
          int tick, BattleEntity owner, WorldEntity unit, int priority, boolean answer) {
        log.add(
            "%d lock_request %s %s %d %d"
                .formatted(tick, nameOf(owner), unit.name(), priority, answer ? 1 : 0));
      }

      @Override
      public void captureScheduled(
          int tick, BattleEntity owner, BattleEntity cause, String action) {
        log.add("%d schedule %s %s %s".formatted(tick, nameOf(owner), nameOf(cause), action));
      }

      @Override
      public void captureStepped(
          int tick,
          BattleEntity owner,
          List<Integer> captured,
          List<Integer> complete,
          List<Integer> timesMs) {
        List<String> names = new ArrayList<>();
        List<String> positions = new ArrayList<>();
        for (int id : captured) {
          WorldEntity unit = match.getWorld().liveEntity(id);
          names.add(unit.name());
          positions.add(
              "%s [%d, %d]".formatted(unit.name(), unit.getView().getX(), unit.getView().getY()));
        }
        List<String> done = new ArrayList<>();
        for (int id : complete) {
          done.add(match.getWorld().liveEntity(id).name());
        }
        log.add(
            "%d capture %s %s %s %s %s"
                .formatted(tick, nameOf(owner), names, done, timesMs, positions));
      }

      @Override
      public void instigatorGone(int tick, WorldEntity unit, String action, String scheduled) {
        log.add("%d instigator_gone %s %s".formatted(tick, unit.name(), scheduled));
      }

      @Override
      public void captureTagsFolded(int tick, WorldEntity unit, boolean hidden, long word) {
        log.add("%d tags %s %d 0x%x".formatted(tick, unit.name(), hidden ? 1 : 0, word));
      }
    };
  }

  /** The name an entity goes by in the logs: a projectile's, or a unit's. */
  private static String nameOf(BattleEntity entity) {
    return entity instanceof ProjectileEntity projectile
        ? projectile.name()
        : ((WorldEntity) entity).name();
  }

  /**
   * The reference's log of the evolved Snowball, in its order, as {@link #snowballLog} writes it.
   * The runs of its hiding and waiting classes are held by the run list.
   */
  private static List<String> expectedSnowballLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode e : reference.path("snowball_evo")) {
      int tick = e.get("tick").asInt();
      switch (e.get("event").asText()) {
        case "start" ->
            expected.add(
                "%d start %s %s %s"
                    .formatted(
                        tick,
                        e.get("projectile").asText(),
                        e.get("config").asText(),
                        e.get("action").asText()));
        case "instance" -> {
          // The hiding and waiting runs on units are held by the run list.
          if (!e.get("owner").asText().startsWith("proj_")) {
            continue;
          }
          JsonNode to = e.get("destination");
          expected.add(
              to == null
                  ? "%d instance %s %s %d"
                      .formatted(
                          tick,
                          e.get("owner").asText(),
                          e.get("action").asText(),
                          e.get("phase").asInt())
                  : "%d instance %s %s %d [%d, %d]"
                      .formatted(
                          tick,
                          e.get("owner").asText(),
                          e.get("action").asText(),
                          e.get("phase").asInt(),
                          to.get(0).asInt(),
                          to.get(1).asInt()));
        }
        case "roll_buff" ->
            expected.add(
                "%d roll_buff %s %s %s %d"
                    .formatted(
                        tick,
                        e.get("projectile").asText(),
                        e.get("unit").asText(),
                        e.get("buff").asText(),
                        e.get("time").asInt()));
        case "roll" ->
            expected.add(
                "%d roll %s %d %d %d"
                    .formatted(
                        tick,
                        e.get("projectile").asText(),
                        e.get("x").asInt(),
                        e.get("y").asInt(),
                        e.get("done").asInt()));
        case "roll_end" ->
            expected.add(
                "%d roll_end %s %d %d"
                    .formatted(
                        tick,
                        e.get("projectile").asText(),
                        e.get("x").asInt(),
                        e.get("y").asInt()));
        case "lock_request" ->
            expected.add(
                "%d lock_request %s %s %d %d"
                    .formatted(
                        tick,
                        e.get("projectile").asText(),
                        e.get("unit").asText(),
                        e.get("priority").asInt(),
                        e.get("answer").asInt()));
        case "schedule" ->
            expected.add(
                "%d schedule %s %s %s"
                    .formatted(
                        tick,
                        e.get("owner").asText(),
                        e.get("instigator").asText(),
                        e.get("action").asText()));
        case "capture" -> {
          List<String> positions = new ArrayList<>();
          for (JsonNode name : e.get("captured")) {
            JsonNode at = e.get("positions").get(name.asText());
            positions.add(
                "%s [%d, %d]".formatted(name.asText(), at.get(0).asInt(), at.get(1).asInt()));
          }
          List<Integer> times = new ArrayList<>();
          e.get("times").forEach(t -> times.add(t.asInt()));
          expected.add(
              "%d capture %s %s %s %s %s"
                  .formatted(
                      tick,
                      e.get("projectile").asText(),
                      names(e.get("captured")),
                      names(e.get("complete")),
                      times,
                      positions));
        }
        case "projectile_data" ->
            expected.add(
                "%d swap %s %s %s %d %d"
                    .formatted(
                        tick,
                        e.get("projectile").asText(),
                        e.get("row").get(0).asText(),
                        e.get("row").get(1).asText(),
                        0,
                        0));
        case "instigator_gone" ->
            expected.add(
                "%d instigator_gone %s %s"
                    .formatted(tick, e.get("unit").asText(), e.get("action").asText()));
        case "tags" ->
            expected.add(
                "%d tags %s %d %s"
                    .formatted(
                        tick,
                        e.get("unit").asText(),
                        e.get("hidden").asInt(),
                        e.get("b0").asText()));
        default -> {
          // Re-triggers and the ends of the hiding runs are held elsewhere.
        }
      }
    }
    return expected;
  }

  /**
   * The reference's log of the evolved Executioner's axe, in its order: each axe's start, its
   * controller's start, each damage asked of it, each strong hit's action and push, and each swap
   * of its row.
   */
  private static List<String> expectedAxeLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode e : reference.path("axe_man_evo")) {
      int tick = e.get("tick").asInt();
      switch (e.get("event").asText()) {
        case "start" ->
            expected.add(
                "%d start %s %s %s"
                    .formatted(
                        tick,
                        e.get("projectile").asText(),
                        e.get("row").asText(),
                        e.get("action").asText()));
        case "controller_start" ->
            expected.add(
                "%d controller_start %s %s %d %d"
                    .formatted(
                        tick,
                        e.get("owner").asText(),
                        e.get("action").asText(),
                        e.get("phase").asInt(),
                        e.get("level").asInt()));
        case "damage" ->
            expected.add(
                "%d damage %s %s %d [%d, %d] %s %d"
                    .formatted(
                        tick,
                        e.get("projectile").asText(),
                        e.get("target").asText(null),
                        e.get("hit_id").asInt(),
                        e.get("damage").get(0).asInt(),
                        e.get("damage").get(1).asInt(),
                        e.get("edge").isNull() ? null : e.get("edge").asInt(),
                        e.get("strong").asInt()));
        case "hit_action" ->
            expected.add(
                "%d hit_action %s %s %s %d"
                    .formatted(
                        tick,
                        e.get("projectile").asText(),
                        e.get("target").asText(),
                        e.get("action").asText(),
                        e.get("hit_id").asInt()));
        case "push" ->
            expected.add(
                "%d push %s %s [%d, %d] %d %d"
                    .formatted(
                        tick,
                        e.get("projectile").asText(),
                        e.get("target").asText(),
                        e.get("frm").get(0).asInt(),
                        e.get("frm").get(1).asInt(),
                        e.get("distance").asInt(),
                        e.get("hit_id").asInt()));
        case "swap" ->
            expected.add(
                "%d swap %s %s %s %d %d"
                    .formatted(
                        tick,
                        e.get("projectile").asText(),
                        e.get("row").get(0).asText(),
                        e.get("row").get(1).asText(),
                        e.get("distance").asInt(),
                        e.get("time").asInt()));
        default -> {
          // The waits, the effects' instances and their stops are held by the runs listed.
        }
      }
    }
    return expected;
  }

  /** The reference's barrage log, in its order. */
  private static List<String> expectedBarrageLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode e : reference.path("cannon_barrage")) {
      int tick = e.get("tick").asInt();
      String owner = e.get("owner").asText();
      switch (e.get("event").asText()) {
        case "barrage_start" ->
            expected.add(
                "%d barrage_start %s %s %d"
                    .formatted(tick, owner, e.get("action").asText(), e.get("phase").asInt()));
        case "barrage" -> {
          assertThat(e.get("finished").asBoolean()).as("a barrage finishes in its update").isTrue();
          List<String> areas = new ArrayList<>();
          for (JsonNode a : e.get("areas")) {
            areas.add(
                "%s %d %s %d %d %d %d"
                    .formatted(
                        a.get("area_effect").asText(),
                        a.get("id").asInt(),
                        a.get("row").asText(),
                        a.get("x").asInt(),
                        a.get("y").asInt(),
                        a.get("level").asInt(),
                        a.get("countdown").asInt()));
          }
          expected.add(
              "%d barrage %s %s %s".formatted(tick, owner, e.get("action").asText(), areas));
        }
        case "bomb" -> {
          JsonNode start = e.get("start");
          JsonNode aim = e.get("aim");
          expected.add(
              "%d bomb %s %s %s [%d, %d, %d] [%d, %d] %d %d %d %d"
                  .formatted(
                      tick,
                      owner,
                      e.get("action").asText(),
                      e.get("projectile").asText(),
                      start.get(0).asInt(),
                      start.get(1).asInt(),
                      start.get(2).asInt(),
                      aim.get(0).asInt(),
                      aim.get(1).asInt(),
                      e.get("speed").asInt(),
                      e.get("lifetime").asInt(),
                      e.get("level").asInt(),
                      e.get("side").asInt()));
        }
        default -> throw new IllegalStateException("a barrage event " + e);
      }
    }
    return expected;
  }

  /** The three tags the evolved Furnace's runs raise and read, in name order. */
  private static final List<String> FURNACE_TAGS =
      List.of("FURNACE_DELAY_NORMAL_SPAWN", "FURNACE_STOP_QUICK_SPAWN", "UNIT_CUSTOM_TAG_1");

  /**
   * The reference's log of the evolved Furnace, in its order: each re-trigger, force stop and
   * launch, and its listed runs and tags as each tick ends, whenever they change.
   */
  private static List<String> expectedFurnaceLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode e : reference.path("furnace_evo")) {
      int tick = e.get("tick").asInt();
      String owner = e.get("owner").asText();
      switch (e.get("event").asText()) {
        case "retrigger" ->
            expected.add(
                "%d retrigger %s %s %d"
                    .formatted(tick, owner, e.get("action").asText(), e.get("phase").asInt()));
        case "force_stop" ->
            expected.add("%d force_stop %s %s".formatted(tick, owner, e.get("action").asText()));
        case "spawn_projectile" -> {
          JsonNode start = e.get("start");
          JsonNode aim = e.get("aim");
          expected.add(
              "%d spawn_projectile %s %s %d %s %s [%d, %d, %d] [%d, %d] %d"
                  .formatted(
                      tick,
                      owner,
                      e.get("action").asText(),
                      e.get("phase").asInt(),
                      e.get("projectile").asText(),
                      e.get("config").asText(),
                      start.get(0).asInt(),
                      start.get(1).asInt(),
                      start.get(2).asInt(),
                      aim.get(0).asInt(),
                      aim.get(1).asInt(),
                      e.get("level").asInt()));
        }
        case "running" -> {
          List<String> runs = new ArrayList<>();
          e.get("running")
              .forEach(r -> runs.add("[" + r.get(0).asText() + ", " + r.get(1).asInt() + "]"));
          expected.add("%d running %s %s".formatted(tick, owner, runs));
        }
        case "tags" -> {
          List<String> tags = new ArrayList<>();
          e.get("tags").forEach(t -> tags.add(t.asText()));
          expected.add("%d tags %s %s".formatted(tick, owner, tags));
        }
        default -> {
          // The flips, the durations, the effect's instance and the flip-flop's steps are held by
          // the runs listed and by the tags.
        }
      }
    }
    return expected;
  }

  /**
   * The actions the reference's hiding buildings scheduled, in its order: the looping effect as
   * each starts to hide and the action as each rises.
   */
  private static List<String> expectedHidingHookLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode e : reference.path("tesla_evo")) {
      String event = e.get("event").asText();
      if (event.equals("looping_effect") || event.equals("hook")) {
        expected.add(
            "%d %s %s %s"
                .formatted(
                    e.get("tick").asInt(),
                    e.get("unit").asText(),
                    e.get("column").asText(),
                    e.get("action").asText()));
      }
    }
    return expected;
  }

  /** The reference's uppercut, knock and wind log, in its order. */
  private static List<String> expectedUppercutWindLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode e : reference.path("uppercut_wind")) {
      int tick = e.get("tick").asInt();
      String unit = e.path("unit").asText(null);
      expected.add(
          switch (e.get("event").asText()) {
            case "tags" -> "%d tags %s %s".formatted(tick, unit, names(e.get("tags")));
            case "uppercut_start" ->
                "%d uppercut_start %s %d %s %s %b"
                    .formatted(
                        tick,
                        unit,
                        e.get("phase").asInt(),
                        e.get("instigator").asText(null),
                        e.get("target").asText(null),
                        e.get("finished").asBoolean());
            case "uppercut_update" ->
                "%d uppercut_update %s %d %b %s %s %s"
                    .formatted(
                        tick,
                        unit,
                        e.get("delay").asInt(),
                        e.get("finished").asBoolean(),
                        e.get("outcome").get(0).asText(),
                        numbers(e.get("push_point")),
                        numbers(e.get("vector")));
            case "uppercut_mark" ->
                "%d uppercut_mark %s %s %d %s"
                    .formatted(
                        tick,
                        unit,
                        e.get("target").asText(),
                        e.get("priority").asInt(),
                        e.get("current").asText(null));
            case "queue_flushed" -> "%d queue_flushed %s".formatted(tick, unit);
            case "uppercut_target_left" ->
                "%d uppercut_target_left %s %s".formatted(tick, unit, e.get("target").asText());
            case "knockback_start" ->
                "%d knockback_start %s %d %s %d %b"
                    .formatted(
                        tick,
                        unit,
                        e.get("phase").asInt(),
                        e.get("instigator").asText(null),
                        e.get("counter").asInt(),
                        e.get("finished").asBoolean());
            case "knockback_update" ->
                "%d knockback_update %s %d %d %d %s %b"
                    .formatted(
                        tick,
                        unit,
                        e.get("counter").get(0).asInt(),
                        e.get("counter").get(1).asInt(),
                        e.get("height").asInt(),
                        names(e.get("tags")),
                        e.get("finished").asBoolean());
            case "wind_start" ->
                "%d wind_start %s %d %s %s %d %d"
                    .formatted(
                        tick,
                        unit,
                        e.get("phase").asInt(),
                        e.get("instigator").asText(null),
                        e.get("area_effect").asText(),
                        e.get("x").asInt(),
                        e.get("y").asInt());
            case "wind_update" ->
                "%d wind_update %s %s %b"
                    .formatted(
                        tick, unit, e.get("outcome").asText(), e.get("finished").asBoolean());
            case "wind_retrigger" ->
                "%d wind_retrigger %s %d %s %s"
                    .formatted(
                        tick,
                        unit,
                        e.get("phase").asInt(),
                        e.get("area_effect").asText(),
                        e.get("countdown").isNull() ? null : e.get("countdown").get(0).asText());
            case "wind_left" ->
                "%d wind_left %s %s".formatted(tick, unit, e.get("area_effect").asText());
            case "wind_release" ->
                "%d wind_release %s %s %s %d %d"
                    .formatted(
                        tick,
                        unit,
                        e.get("why").asText(),
                        e.get("area_effect").asText(),
                        e.get("countdown").get(0).asInt(),
                        e.get("countdown").get(1).asInt());
            case "wind_collect" ->
                "%d wind_collect %s %d %d %d %s"
                    .formatted(
                        tick,
                        e.get("area_effect").asText(),
                        e.get("x").asInt(),
                        e.get("y").asInt(),
                        e.get("countdown").asInt(),
                        names(e.get("found")));
            case "filter_by_enemy" ->
                "%d filter_by_enemy %s %s %d %s"
                    .formatted(
                        tick,
                        unit,
                        e.get("instigator").asText(),
                        e.get("same_team").asInt(),
                        e.get("action").asText(null));
            case "alive_timer" ->
                "%d alive_timer %s %s"
                    .formatted(tick, e.get("area_effect").asText(), e.get("action").asText());
            default -> throw new IllegalStateException("unknown uppercut_wind event " + e);
          });
    }
    return expected;
  }

  /** The reference's broken shields' schedules and charge resets, in its log's order. */
  private static List<String> expectedShieldLostLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode e : reference.path("shield_lost")) {
      int tick = e.get("tick").asInt();
      switch (e.get("event").asText()) {
        case "scheduled" ->
            expected.add(
                "%d scheduled %s %s %s %b"
                    .formatted(
                        tick,
                        e.get("unit").asText(),
                        e.get("action").asText(),
                        e.get("by").isNull() ? null : e.get("by").asText(),
                        e.get("in_pending").asBoolean()));
        case "charge_reset" ->
            expected.add(
                "%d charge_reset %s %d %d"
                    .formatted(
                        tick,
                        e.get("unit").asText(),
                        e.get("charge").get(0).asInt(),
                        e.get("charge").get(1).asInt()));
        case "run" -> {
          // Each run is held by the action runs.
        }
        default -> throw new IllegalStateException("unknown shield_lost event " + e);
      }
    }
    return expected;
  }

  /** The reference's BuffAfterHits applies and buff hooks, in its log's order. */
  private static List<String> expectedBuffAfterHitsLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode e : reference.path("buff_after_hits")) {
      int tick = e.get("tick").asInt();
      switch (e.get("event").asText()) {
        case "buff_after_hits" ->
            expected.add(
                "%d buff_after_hits %s %s %d %d %s %d"
                    .formatted(
                        tick,
                        e.get("unit").asText(),
                        e.get("target").asText(),
                        e.get("counter").get(0).asInt(),
                        e.get("counter").get(1).asInt(),
                        e.get("buff").asText(),
                        e.get("time").asInt()));
        case "buff_hook" ->
            expected.add(
                "%d buff_hook %s %s %b"
                    .formatted(
                        tick,
                        e.get("unit").asText(),
                        e.get("action").asText(),
                        e.get("in_pending").asBoolean()));
        case "ghost_evo_start" ->
            expected.add(
                "%d ghost_evo_start %s %s %d"
                    .formatted(
                        tick,
                        e.get("owner").asText(),
                        e.get("action").asText(),
                        e.get("phase").asInt()));
        case "area" ->
            expected.add(
                "%d area %s %s %s %d %d %s %d"
                    .formatted(
                        tick,
                        e.get("owner").asText(),
                        e.get("row").asText(),
                        e.get("area_effect").asText(),
                        e.get("x").asInt(),
                        e.get("y").asInt(),
                        e.get("target").isNull() ? null : e.get("target").asText(),
                        e.get("level").asInt()));
        case "summon" ->
            expected.add(
                "%d summon %s %s %d %d %d"
                    .formatted(
                        tick,
                        e.get("owner").asText(),
                        e.get("reference").asText(),
                        e.get("point").get(0).asInt(),
                        e.get("point").get(1).asInt(),
                        e.get("countdown").asInt()));
        case "summoned" ->
            expected.add(
                "%d summoned %s %s %d %d %d %d %d %d %s %d %d"
                    .formatted(
                        tick,
                        e.get("area").asText(),
                        e.get("unit").asText(),
                        e.get("id").asInt(),
                        e.get("x").asInt(),
                        e.get("y").asInt(),
                        e.get("state").asInt(),
                        e.get("deploy").asInt(),
                        e.get("level").asInt(),
                        e.get("ref").isNull() ? null : e.get("ref").asText(),
                        e.get("dir").get(0).asInt(),
                        e.get("dir").get(1).asInt()));
        case "summon_removed" ->
            expected.add(
                "%d summon_removed %s %s"
                    .formatted(tick, e.get("owner").asText(), e.get("action").asText()));
        // A group's gate shows in the runs it lets through, listed with every run.
        default -> {}
      }
    }
    return expected;
  }

  /**
   * Logs every killer's killed-done check as it is scheduled, with what it killed and whether a
   * pending pass ran, and every check of what caused an action, with the cause's row and what the
   * check scheduled.
   */
  private static WorldObserver killLog(int[] currentTick, List<String> log) {
    return new WorldObserver() {
      @Override
      public void killedDoneScheduled(
          int tick, WorldEntity killer, WorldEntity killed, String action, boolean inPendingPass) {
        log.add(
            "%d killed_done %s %s %s %s"
                .formatted(currentTick[0], killer.name(), killed.name(), action, inPendingPass));
      }

      @Override
      public void instigatorChecked(
          int tick, WorldEntity owner, String action, ActionOwner instigator, String scheduled) {
        log.add(
            "%d instigator_match %s %s %s %s %s"
                .formatted(
                    currentTick[0],
                    owner.name(),
                    action,
                    instigator instanceof BattleEntity cause ? attackerName(cause) : null,
                    instigator == null ? null : instigator.actionRowName(),
                    scheduled));
      }
    };
  }

  /**
   * Logs every laser ball's start, with its pass and timer, every fire, with the count, the index
   * it picked, the targets, the action and the timer before and after, and every area effect's
   * life-end action as it is scheduled.
   */
  private static WorldObserver laserLog(int[] currentTick, List<String> log) {
    return new WorldObserver() {
      @Override
      public void laserStarted(
          int tick, AreaEffectEntity areaEffect, String action, int phase, int timerMs) {
        log.add(
            "%d start %s %s phase %d timer %d"
                .formatted(currentTick[0], areaEffect.name(), action, phase, timerMs));
      }

      @Override
      public void laserFired(
          int tick,
          AreaEffectEntity areaEffect,
          int count,
          int index,
          List<WorldEntity> targets,
          String action,
          int timerBefore,
          int timerAfter) {
        log.add(
            "%d fire %s count %d index %d targets %s action %s timer %d %d"
                .formatted(
                    currentTick[0],
                    areaEffect.name(),
                    count,
                    index,
                    targets.stream().map(WorldEntity::name).toList(),
                    action,
                    timerBefore,
                    timerAfter));
      }

      @Override
      public void lifeTimeEndScheduled(int tick, AreaEffectEntity areaEffect, String action) {
        log.add("%d lifetime_end %s %s".formatted(currentTick[0], areaEffect.name(), action));
      }
    };
  }

  /**
   * The reference's laser ball starts and fires and life-end actions, in the laser log's layout.
   */
  private static List<String> expectedLaserLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode l : reference.path("laser")) {
      int tick = l.get("tick").asInt();
      switch (l.get("event").asText()) {
        case "start" ->
            expected.add(
                "%d start %s %s phase %d timer %d"
                    .formatted(
                        tick,
                        l.get("owner").asText(),
                        l.get("action").asText(),
                        l.get("phase").asInt(),
                        l.get("timer").asInt()));
        case "fire" -> {
          List<String> targets = new ArrayList<>();
          l.get("targets").forEach(t -> targets.add(t.asText()));
          expected.add(
              "%d fire %s count %d index %d targets %s action %s timer %d %d"
                  .formatted(
                      tick,
                      l.get("owner").asText(),
                      l.get("count").asInt(),
                      l.get("index").asInt(),
                      targets,
                      l.get("action").isNull() ? null : l.get("action").asText(),
                      l.get("timer").get(0).asInt(),
                      l.get("timer").get(1).asInt()));
        }
        case "lifetime_end" ->
            expected.add(
                "%d lifetime_end %s %s"
                    .formatted(tick, l.get("area_effect").asText(), l.get("action").asText()));
        default -> throw new IllegalArgumentException("unknown laser event " + l);
      }
    }
    return expected;
  }

  /**
   * Logs every shape selector's start with its due ticks, every step that queried or finished, with
   * what it found, the scores, the picks, the entries that picked nobody and the picks and due
   * ticks before and after, and every air-to-ground run's start, phase change, finish and
   * re-trigger, with its pushes. Objects are named as they stand.
   */
  private static WorldObserver vinesLog(
      Standard1v1Battle match, int[] currentTick, List<String> log) {
    return new WorldObserver() {
      private String named(int id) {
        return ((WorldEntity) match.getWorld().liveObject(id)).name();
      }

      @Override
      public void selectorStarted(
          int tick, AreaEffectEntity areaEffect, String action, int phase, List<Integer> due) {
        log.add(
            "%d selector_start %s %s phase %d due %s"
                .formatted(currentTick[0], areaEffect.name(), action, phase, due));
      }

      @Override
      public void selectorStepped(
          int tick, AreaEffectEntity areaEffect, String action, ShapeSelector.Step step) {
        log.add(
            "%d selector_update %s found %s scores %s chosen %s empty %s none %s finished %s hit %s"
                    .formatted(
                        currentTick[0],
                        areaEffect.name(),
                        step.found().stream().map(this::named).toList(),
                        step.scores().stream().map(p -> named(p[0]) + "=" + p[1]).toList(),
                        step.chosen().stream().map(p -> p[0] + "=" + named(p[1])).toList(),
                        step.empty(),
                        step.none(),
                        step.finished(),
                        step.hit().stream().map(this::named).toList())
                + " before due %s hit %s".formatted(step.dueBefore(), step.hitBefore()));
      }

      @Override
      public void airToGroundStarted(
          int tick,
          WorldEntity unit,
          String action,
          int phase,
          int runPhase,
          int counter,
          int height) {
        log.add(
            "%d air_start %s %s phase %d counter %d height %d"
                .formatted(currentTick[0], unit.name(), action, runPhase, counter, height));
      }

      @Override
      public void airToGroundStepped(
          int tick,
          WorldEntity unit,
          int phaseBefore,
          int phaseAfter,
          int counterBefore,
          int counterAfter,
          boolean done,
          List<Integer> pushes) {
        log.add(
            "%d air_phase %s phase %d %d counter %d %d done %s pushes %s"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    phaseBefore,
                    phaseAfter,
                    counterBefore,
                    counterAfter,
                    done,
                    pushes));
      }

      @Override
      public void airToGroundRetriggered(
          int tick, WorldEntity unit, String action, int phase, int counter) {
        log.add(
            "%d air_retrigger %s %s phase %d counter %d"
                .formatted(currentTick[0], unit.name(), action, phase, counter));
      }
    };
  }

  /** The reference's shape selectors and air-to-ground runs, in the selectors' log layout. */
  private static List<String> expectedVinesLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode v : reference.path("vines")) {
      int tick = v.get("tick").asInt();
      switch (v.get("event").asText()) {
        case "selector_start" ->
            expected.add(
                "%d selector_start %s %s phase %d due %s"
                    .formatted(
                        tick,
                        v.get("owner").asText(),
                        v.get("action").asText(),
                        v.get("phase").asInt(),
                        ints(v.get("due"))));
        case "selector_update" -> {
          List<String> scores = new ArrayList<>();
          v.get("scores").forEach(p -> scores.add(p.get(0).asText() + "=" + p.get(1).asInt()));
          List<String> chosen = new ArrayList<>();
          v.get("chosen").forEach(p -> chosen.add(p.get(0).asInt() + "=" + p.get(1).asText()));
          expected.add(
              "%d selector_update %s found %s scores %s chosen %s empty %s none %s finished %s hit %s"
                      .formatted(
                          tick,
                          v.get("owner").asText(),
                          texts(v.get("found")),
                          scores,
                          chosen,
                          v.get("empty").asBoolean(),
                          ints(v.get("none")),
                          v.get("finished").asBoolean(),
                          texts(v.get("hit")))
                  + " before due %s hit %s"
                      .formatted(
                          ints(v.get("before").get("due")), ints(v.get("before").get("hit"))));
        }
        case "selector_removed" ->
            expected.add(
                "%d selector_removed %s %s"
                    .formatted(tick, v.get("owner").asText(), v.get("action").asText()));
        case "air_start" ->
            expected.add(
                "%d air_start %s %s phase %d counter %d height %d"
                    .formatted(
                        tick,
                        v.get("unit").asText(),
                        v.get("action").asText(),
                        v.get("phase").asInt(),
                        v.get("counter").asInt(),
                        v.get("height").asInt()));
        case "air_phase" ->
            expected.add(
                "%d air_phase %s phase %d %d counter %d %d done %s pushes %s"
                    .formatted(
                        tick,
                        v.get("unit").asText(),
                        v.get("phase").get(0).asInt(),
                        v.get("phase").get(1).asInt(),
                        v.get("counter").get(0).asInt(),
                        v.get("counter").get(1).asInt(),
                        v.get("done").asBoolean(),
                        ints(v.get("pushes"))));
        case "air_retrigger" ->
            expected.add(
                "%d air_retrigger %s %s phase %d counter %d"
                    .formatted(
                        tick,
                        v.get("unit").asText(),
                        v.get("action").asText(),
                        v.get("phase").asInt(),
                        v.get("counter").asInt()));
        default -> throw new IllegalArgumentException("unknown vines event " + v);
      }
    }
    return expected;
  }

  /**
   * Logs every target indicator attack's start, every object its finder found with what its query
   * listed, every signal and shot, every signal ended, every step that did more than ask the finder
   * for nobody or end no attack, with its fields before and after and its calls, and every stop.
   * Objects are named as they stand, or as they wait to be admitted.
   */
  private static WorldObserver indicatorLog(
      Standard1v1Battle match, int[] currentTick, List<String> log) {
    return new WorldObserver() {
      private String named(int id) {
        BattleEntity found = match.getWorld().liveObject(id);
        if (found == null) {
          found =
              match.getBattle().getHolder().queued().stream()
                  .filter(e -> e.getId() == id)
                  .findFirst()
                  .orElseThrow();
        }
        if (found instanceof ProjectileEntity p) {
          return p.name();
        }
        return found instanceof AreaEffectEntity a ? a.name() : ((WorldEntity) found).name();
      }

      @Override
      public void targetIndicatorLogged(
          int tick, CharacterEntity unit, TargetIndicatorAttack.Event event) {
        String owner = unit.name();
        int now = currentTick[0];
        if (event instanceof TargetIndicatorAttack.Started e) {
          log.add("%d start %s %s %d".formatted(now, owner, e.action(), e.phase()));
        } else if (event instanceof TargetIndicatorAttack.Found e) {
          log.add(
              "%d find %s %s %s"
                  .formatted(
                      now, owner, e.listed().stream().map(this::named).toList(), named(e.found())));
        } else if (event instanceof TargetIndicatorAttack.Signalled e) {
          log.add(
              "%d signal %s %s %s %d at %d %d level %d"
                  .formatted(
                      now,
                      owner,
                      named(e.target()),
                      named(e.signal()),
                      e.signal(),
                      e.x(),
                      e.y(),
                      e.packedLevel()));
        } else if (event instanceof TargetIndicatorAttack.Shot e) {
          log.add(
              "%d shoot %s %s %s at %d %d %d aim %d %d level %d facing %d %d"
                  .formatted(
                      now,
                      owner,
                      named(e.projectile()),
                      named(e.signal()),
                      e.x(),
                      e.y(),
                      e.z(),
                      e.aimX(),
                      e.aimY(),
                      e.packedLevel(),
                      e.facingX(),
                      e.facingY()));
        } else if (event instanceof TargetIndicatorAttack.SignalEnded e) {
          log.add("%d signal_ended %s %s".formatted(now, owner, named(e.signal())));
        } else if (event instanceof TargetIndicatorAttack.Stepped e) {
          log.add(
              "%d step %s %s calls %s %s"
                  .formatted(now, owner, fields(e.before()), e.calls(), fields(e.after())));
        } else if (event instanceof TargetIndicatorAttack.Stopped e) {
          log.add("%d stop %s calls %s %s".formatted(now, owner, e.calls(), fields(e.after())));
        }
      }

      private String fields(TargetIndicatorAttack.Fields f) {
        return indicatorFields(
            f.loadMs(),
            f.cooldownMs(),
            f.timesMs(),
            f.signals(),
            f.projectiles(),
            f.stopTags() ? 1 : 0);
      }
    };
  }

  /** A target indicator attack's fields, in its log's layout. */
  private static String indicatorFields(
      int load,
      int cooldown,
      List<Integer> times,
      List<Integer> signals,
      List<Integer> projectiles,
      int tags) {
    return "load %d cooldown %d times %s signals %s projectiles %s tags %d"
        .formatted(load, cooldown, times, signals, projectiles, tags);
  }

  /** The reference's target indicator attacks, in their log's layout. */
  private static List<String> expectedIndicatorLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode v : reference.path("target_indicator_attack")) {
      int tick = v.get("tick").asInt();
      String owner = v.get("owner").asText();
      switch (v.get("event").asText()) {
        case "start" ->
            expected.add(
                "%d start %s %s %d"
                    .formatted(tick, owner, v.get("action").asText(), v.get("phase").asInt()));
        case "find" ->
            expected.add(
                "%d find %s %s %s"
                    .formatted(tick, owner, texts(v.get("listed")), v.get("found").asText()));
        case "signal" ->
            expected.add(
                "%d signal %s %s %s %d at %d %d level %d"
                    .formatted(
                        tick,
                        owner,
                        v.get("target").asText(),
                        v.get("area_effect").asText(),
                        v.get("id").asInt(),
                        v.get("x").asInt(),
                        v.get("y").asInt(),
                        v.get("level").asInt()));
        case "shoot" ->
            expected.add(
                "%d shoot %s %s %s at %d %d %d aim %d %d level %d facing %d %d"
                    .formatted(
                        tick,
                        owner,
                        v.get("projectile").asText(),
                        v.get("signal").asText(),
                        v.get("start").get(0).asInt(),
                        v.get("start").get(1).asInt(),
                        v.get("start").get(2).asInt(),
                        v.get("aim").get(0).asInt(),
                        v.get("aim").get(1).asInt(),
                        v.get("level").asInt(),
                        v.get("facing").get(0).asInt(),
                        v.get("facing").get(1).asInt()));
        case "signal_ended" ->
            expected.add(
                "%d signal_ended %s %s".formatted(tick, owner, v.get("area_effect").asText()));
        case "step" ->
            expected.add(
                "%d step %s %s calls %s %s"
                    .formatted(
                        tick,
                        owner,
                        indicatorFields(v.get("before")),
                        indicatorCalls(v.get("calls")),
                        indicatorFields(v.get("after"))));
        case "stop" ->
            expected.add(
                "%d stop %s calls %s %s"
                    .formatted(
                        tick,
                        owner,
                        indicatorCalls(v.get("calls")),
                        indicatorFields(v.get("after"))));
        default -> throw new IllegalArgumentException("unknown target indicator event " + v);
      }
    }
    return expected;
  }

  /** The reference's fields of a target indicator attack, in its log's layout. */
  private static String indicatorFields(JsonNode f) {
    return indicatorFields(
        f.get("load").asInt(),
        f.get("cooldown").asInt(),
        ints(f.get("times")),
        ints(f.get("signals")),
        ints(f.get("projectiles")),
        f.get("tags").asInt());
  }

  /** The reference's calls of a target indicator attack, each its parts joined by spaces. */
  private static List<String> indicatorCalls(JsonNode calls) {
    List<String> out = new ArrayList<>();
    for (JsonNode call : calls) {
      List<String> parts = new ArrayList<>();
      call.forEach(part -> parts.add(part.isNull() ? "null" : part.asText()));
      out.add(String.join(" ", parts));
    }
    return out;
  }

  private static List<Integer> ints(JsonNode values) {
    List<Integer> out = new ArrayList<>();
    values.forEach(value -> out.add(value.asInt()));
    return out;
  }

  private static List<String> texts(JsonNode values) {
    List<String> out = new ArrayList<>();
    values.forEach(value -> out.add(value.asText()));
    return out;
  }

  /** The reference's Berserker index toggles, in the Berserker log's layout. */
  private static List<String> expectedBerserkLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode b : reference.path("berserk")) {
      expected.add(
          "%d berserk %s %s %d %d"
              .formatted(
                  b.get("tick").asInt(),
                  b.get("event").asText(),
                  b.get("owner").asText(),
                  b.get("before").asInt(),
                  b.get("index").asInt()));
    }
    return expected;
  }

  /**
   * Logs every link of a card's group chain and every unlink, and every start, connection, death
   * area made and death area ended of Goblinstein's ability.
   */
  /**
   * Logs every guard a guard spawn made, as its registration visit left it, the run's start, and
   * every step of its two runs: the first's, which finishes it, and the guard's, with whether it is
   * charging, its tags, whether it is done and what it did.
   */
  private static WorldObserver guardLog(int[] currentTick, List<String> log) {
    return new WorldObserver() {
      @Override
      public void guardRegistered(int tick, CharacterEntity guard) {
        TargetView reference = guard.getUnit().targeting().getReference();
        log.add(
            "%d guard %s %d at %d %d state %d hp %d ref %s"
                .formatted(
                    currentTick[0],
                    guard.name(),
                    guard.getId(),
                    guard.getView().getX(),
                    guard.getView().getY(),
                    guard.getView().getState(),
                    guard.getHitPoints().getHitPoints(),
                    reference == null ? null : reference.name()));
      }

      @Override
      public void guardStarted(
          int tick,
          AreaEffectEntity areaEffect,
          String action,
          int phase,
          CharacterEntity guard,
          int x,
          int y,
          int toX,
          int toY) {
        // The maker faces the guard toward the area effect's point, after its registration visit;
        // no record shows the facing, and the charge faces it anew.
        int[] facing = {
          areaEffect.getX() - guard.getView().getX(), areaEffect.getY() - guard.getView().getY()
        };
        FixedMath.normalize(facing, MovementState.DIRECTION_SCALE);
        assertThat(new int[] {guard.getView().getDirX(), guard.getView().getDirY()})
            .as("%d: %s faces the area effect's point", currentTick[0], guard.name())
            .containsExactly(facing);
        log.add(
            "%d start %s %s %d guard %s relocate %d %d to %d %d"
                .formatted(
                    currentTick[0],
                    areaEffect.name(),
                    action,
                    phase,
                    guard.name(),
                    x,
                    y,
                    toX,
                    toY));
      }

      @Override
      public void guardFirstStepped(int tick, AreaEffectEntity areaEffect, String action) {
        log.add("%d update %s first finish".formatted(currentTick[0], areaEffect.name()));
      }

      @Override
      public void guardStepped(
          int tick,
          CharacterEntity guard,
          boolean charging,
          long tags,
          boolean done,
          List<String> calls) {
        log.add(
            "%d update %s charging %b tags %s done %b calls %s"
                .formatted(currentTick[0], guard.name(), charging, tagNames(tags), done, calls));
      }
    };
  }

  /**
   * Logs every start of a Boss Bandit ability's run, with its warp and lock ticks and the answers
   * to its asks for the lock; every step that changed the run or asked again, with the unit's
   * state; and every warp, with where the unit stood and landed, the reference it dropped and its
   * state.
   */
  private static WorldObserver warpLog(int[] currentTick, List<String> log) {
    return new WorldObserver() {
      @Override
      public void bossBanditAbilityStarted(
          int tick,
          CharacterEntity unit,
          String action,
          int phase,
          int warpTick,
          int lockTick,
          List<Boolean> requests) {
        log.add(
            "%d start %s %s %d warp %d lock %d requests %s"
                .formatted(
                    currentTick[0], unit.name(), action, phase, warpTick, lockTick, requests));
      }

      @Override
      public void bossBanditAbilityStepped(
          int tick, CharacterEntity unit, boolean locked, int releaseMs, List<String> calls) {
        log.add(
            "%d step %s locked %b release %d state %d calls %s"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    locked,
                    releaseMs,
                    unit.getView().getState(),
                    calls));
      }

      @Override
      public void warped(
          int tick,
          CharacterEntity unit,
          String action,
          int phase,
          int fromX,
          int fromY,
          String referenceBefore) {
        log.add(
            "%d warp %s %s %d from %d %d to %d %d ref %s state %d"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    action,
                    phase,
                    fromX,
                    fromY,
                    unit.getView().getX(),
                    unit.getView().getY(),
                    referenceBefore,
                    unit.getView().getState()));
      }
    };
  }

  /**
   * The reference's Boss Bandit ability log, in the warp log's layout. A warp that dropped a
   * projectile's target is refused by the battle, so the reference must list none.
   */
  private static List<String> expectedWarpLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode b : reference.path("boss_bandit_ability")) {
      int tick = b.get("tick").asInt();
      switch (b.get("event").asText()) {
        case "ability_start" -> {
          List<Boolean> requests = new ArrayList<>();
          b.get("lock_requests").forEach(r -> requests.add(r.asInt() == 1));
          expected.add(
              "%d start %s %s %d warp %d lock %d requests %s"
                  .formatted(
                      tick,
                      b.get("owner").asText(),
                      b.get("action").asText(),
                      b.get("phase").asInt(),
                      b.get("warp_tick").asInt(),
                      b.get("lock_tick").asInt(),
                      requests));
        }
        case "ability_step" -> {
          List<String> calls = new ArrayList<>();
          if (b.has("claim")) {
            calls.add("claim " + (b.get("claim").asInt() == 1));
          }
          if (b.has("request")) {
            calls.add("request " + (b.get("request").asInt() == 1));
          }
          if (b.has("warp_action")) {
            calls.add("warp " + b.get("warp_action").asText());
          }
          if (b.path("finished").asBoolean(false)) {
            calls.add("finish");
          }
          expected.add(
              "%d step %s locked %b release %d state %d calls %s"
                  .formatted(
                      tick,
                      b.get("owner").asText(),
                      b.get("locked").asInt() == 1,
                      b.get("release").asInt(),
                      b.get("state").asInt(),
                      calls));
        }
        case "warp" -> {
          assertThat(b.get("dropped")).as("%d: the projectiles the warp dropped", tick).isEmpty();
          expected.add(
              "%d warp %s %s %d from %d %d to %d %d ref %s state %d"
                  .formatted(
                      tick,
                      b.get("owner").asText(),
                      b.get("action").asText(),
                      b.get("phase").asInt(),
                      b.get("start").get(0).asInt(),
                      b.get("start").get(1).asInt(),
                      b.get("to").get(0).asInt(),
                      b.get("to").get(1).asInt(),
                      b.get("ref_before").isNull() ? null : b.get("ref_before").asText(),
                      b.get("state").asInt()));
        }
        default -> throw new IllegalArgumentException("unknown Boss Bandit ability event " + b);
      }
    }
    return expected;
  }

  /**
   * The ticks each Boss Bandit ability's run holds its lock as the post-hooks end: granted by the
   * post-pass of the tick it starts in, so from the next tick, and dropped by the pre-pass after
   * the tick it finishes in, so up to that tick.
   */
  private static List<String> expectedSelfLocks(JsonNode reference) {
    Map<String, Integer> started = new HashMap<>();
    List<String> expected = new ArrayList<>();
    for (JsonNode b : reference.path("boss_bandit_ability")) {
      String owner = b.get("owner").asText();
      if (b.get("event").asText().equals("ability_start")) {
        started.put(owner, b.get("tick").asInt());
      } else if (b.path("finished").asBoolean(false)) {
        for (int t = started.remove(owner) + 1; t <= b.get("tick").asInt(); t++) {
          expected.add(t + " " + owner);
        }
      }
    }
    assertThat(started).as("a run that never finished").isEmpty();
    return expected;
  }

  /** The names of the game tags set in a word, in the order of their names. */
  private static List<String> tagNames(long tags) {
    List<String> names = new ArrayList<>();
    for (GameRow row : GameData.tables().table("game_tags").rows()) {
      if (row.index() < Long.SIZE && (tags & (1L << row.index())) != 0) {
        names.add(row.name());
      }
    }
    return names.stream().sorted().toList();
  }

  /**
   * The reference's guards and guard spawn steps, in the guard log's layout. The duration run's
   * start is held by the run log and its finish by the unit's walk; a run's removal by the holder's
   * own pass. The presentation the hit hands its view is not modelled.
   */
  private static List<String> expectedGuardLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode g : reference.path("little_prince_ability")) {
      int tick = g.get("tick").asInt();
      switch (g.get("event").asText()) {
        case "duration_start", "duration_finished", "removed" -> {}
        case "guard" ->
            expected.add(
                "%d guard %s %d at %d %d state %d hp %d ref %s"
                    .formatted(
                        tick,
                        g.get("unit").asText(),
                        g.get("id").asInt(),
                        g.get("x").asInt(),
                        g.get("y").asInt(),
                        g.get("state").asInt(),
                        g.get("hp").asInt(),
                        g.get("ref").isNull() ? null : g.get("ref").asText()));
        case "start" -> {
          JsonNode relocate = g.get("calls").get(0);
          expected.add(
              "%d start %s %s %d guard %s relocate %d %d to %d %d"
                  .formatted(
                      tick,
                      g.get("owner").asText(),
                      g.get("action").asText(),
                      g.get("phase").asInt(),
                      g.get("guard").asText(),
                      relocate.get(1).asInt(),
                      relocate.get(2).asInt(),
                      relocate.get(3).asInt(),
                      relocate.get(4).asInt()));
        }
        case "update" -> {
          if (g.get("child").asInt() == 0) {
            expected.add("%d update %s first finish".formatted(tick, g.get("owner").asText()));
            continue;
          }
          List<String> calls = new ArrayList<>();
          for (JsonNode c : g.get("calls")) {
            String kind = c.get(0).asText();
            switch (kind) {
              case "query" -> {
                List<String> found = new ArrayList<>();
                c.get(1).forEach(n -> found.add(n.asText()));
                calls.add("query " + String.join(",", found) + " " + c.get(2).asInt());
              }
              case "push" ->
                  calls.add("push " + c.get(1).asText() + " " + (c.get(2).asBoolean() ? 1 : 0));
              case "presentation_f0" -> {}
              case "hit" -> calls.add("hit " + c.get(1).asText() + " " + c.get(2).asInt());
              case "untouchable" -> calls.add("untouchable " + c.get(1).asText());
              case "deploy_cut" -> calls.add("deploy_cut");
              case "charge" -> calls.add("charge " + c.get(1).asInt() + " " + c.get(2).asInt());
              case "finish" -> calls.add("finish " + c.get(1).asText());
              default -> throw new IllegalArgumentException("unknown guard step call " + c);
            }
          }
          List<String> tags = new ArrayList<>();
          g.get("tags").forEach(t -> tags.add(t.asText()));
          expected.add(
              "%d update %s charging %b tags %s done %b calls %s"
                  .formatted(
                      tick,
                      g.get("owner").asText(),
                      g.get("charging").asInt() == 1,
                      tags.stream().sorted().toList(),
                      g.get("done").asInt() == 1,
                      calls));
        }
        default -> throw new IllegalArgumentException("unknown guard event " + g);
      }
    }
    return expected;
  }

  private static WorldObserver goblinsteinLog(int[] currentTick, List<String> log) {
    return new WorldObserver() {
      @Override
      public void chainLinked(int tick, CharacterEntity unit, CharacterEntity after) {
        log.add(
            "%d group_link %s %s"
                .formatted(currentTick[0], unit.name(), after == null ? null : after.name()));
      }

      @Override
      public void chainUnlinked(int tick, CharacterEntity unit) {
        log.add("%d group_unlink %s".formatted(currentTick[0], unit.name()));
      }

      @Override
      public void goblinsteinStarted(int tick, AreaEffectEntity owner, String action, int phase) {
        log.add("%d start %s %s %d".formatted(currentTick[0], owner.name(), action, phase));
      }

      @Override
      public void goblinsteinConnected(int tick, AreaEffectEntity owner, BattleEntity connected) {
        String name =
            connected == null
                ? null
                : connected instanceof WorldEntity w
                    ? w.name()
                    : ((AreaEffectEntity) connected).name();
        log.add("%d connect %s %s".formatted(currentTick[0], owner.name(), name));
      }

      @Override
      public void goblinsteinDeathAreaMade(
          int tick,
          AreaEffectEntity owner,
          WorldEntity left,
          AreaEffectEntity deathArea,
          int x,
          int y) {
        log.add(
            "%d death_area %s %s %s %d %d %d %d %d %d"
                .formatted(
                    currentTick[0],
                    owner.name(),
                    left.name(),
                    deathArea.name(),
                    deathArea.getId(),
                    x,
                    y,
                    deathArea.side(),
                    deathArea.getPackedLevel(),
                    deathArea.getCountdown()));
      }

      @Override
      public void goblinsteinDeathAreaEnded(
          int tick, AreaEffectEntity owner, AreaEffectEntity deathArea) {
        log.add(
            "%d death_area_ended %s %s".formatted(currentTick[0], owner.name(), deathArea.name()));
      }

      @Override
      public void goblinsteinStepped(int tick, AreaEffectEntity owner, String step) {
        log.add("%d %s %s".formatted(currentTick[0], step, owner.name()));
      }
    };
  }

  /** Lists every buff a parent handed to its riders, in the reference's layout. */
  private static WorldObserver handOverLog(int[] currentTick, List<String> log) {
    return new WorldObserver() {
      @Override
      public void buffHandedOver(
          int tick,
          WorldEntity parent,
          WorldEntity rider,
          BuffData buff,
          int time,
          int packedLevel,
          SpawnHost source,
          List<BuffInstance> instances,
          Set<String> heldBefore) {
        log.add(
            "%d handed_over %s %s %s %d %d %s %s"
                .formatted(
                    currentTick[0],
                    parent.name(),
                    rider.name(),
                    buff.name(),
                    time,
                    packedLevel,
                    source == null ? null : source.name(),
                    instances.stream()
                        .map(
                            i ->
                                "%s %d %d %b"
                                    .formatted(
                                        i.getKey(),
                                        i.getRemaining(),
                                        i.getPackedLevel(),
                                        heldBefore.contains(i.getKey())))
                        .toList()));
      }
    };
  }

  /** The name of a tether's end: a unit's or an area effect's. */
  private static String endName(BattleEntity end) {
    return end instanceof WorldEntity w ? w.name() : ((AreaEffectEntity) end).name();
  }

  private static WorldObserver tetherLog(int[] currentTick, List<String> log) {
    // The elixir each listener has counted, by owner and row.
    Map<String, Integer> totals = new HashMap<>();
    return new WorldObserver() {
      @Override
      public void cardPlayHeard(
          int tick,
          WorldEntity owner,
          String action,
          int side,
          String played,
          String deployed,
          int total,
          String scheduled) {
        List<String> calls =
            scheduled == null ? List.of() : List.of("schedule " + owner.name() + " " + scheduled);
        Integer before = totals.put(owner.name() + " " + action, total);
        // A play that scheduled nothing and left the count as it was is marked as doing nothing.
        boolean idle = scheduled == null && total == (before == null ? 0 : before);
        log.add(
            "%d card_play_heard %s %s %d %s %s %d %s%s"
                .formatted(
                    currentTick[0],
                    owner.name(),
                    action,
                    side,
                    played,
                    deployed,
                    total,
                    calls,
                    idle ? " -" : ""));
      }

      @Override
      public void tetherActivated(
          int tick, AreaEffectEntity owner, BattleEntity target, String action) {
        log.add(
            "%d schedule %s %s %s"
                .formatted(currentTick[0], endName(target), owner.name(), action));
      }

      @Override
      public void tetherDamagePass(
          int tick,
          AreaEffectEntity owner,
          int ax,
          int ay,
          int bx,
          int by,
          List<WorldEntity> found) {
        log.add(
            "%d damage_pass %s %d %d %d %d %s"
                .formatted(
                    currentTick[0],
                    owner.name(),
                    ax,
                    ay,
                    bx,
                    by,
                    found == null ? null : found.stream().map(WorldEntity::name).toList()));
      }

      @Override
      public void tetherHit(
          int tick,
          AreaEffectEntity owner,
          WorldEntity target,
          int damage,
          int directionX,
          int directionY,
          DamageResult result) {
        log.add(
            "%d hit %s %s %d %d %d"
                .formatted(
                    currentTick[0], owner.name(), target.name(), damage, directionX, directionY));
      }

      @Override
      public void tetherHitAction(
          int tick, AreaEffectEntity owner, WorldEntity target, String action) {
        log.add(
            "%d hit_action %s %s %s"
                .formatted(currentTick[0], owner.name(), target.name(), action));
      }
    };
  }

  /** The reference's tether log, in the battle's layout. */
  private static List<String> expectedTetherLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode t : reference.path("tether")) {
      int tick = t.get("tick").asInt();
      switch (t.get("event").asText()) {
        case "schedule" ->
            expected.add(
                "%d schedule %s %s %s"
                    .formatted(
                        tick,
                        t.get("owner").asText(),
                        t.get("instigator").asText(),
                        t.get("action").asText()));
        case "damage_pass" -> {
          JsonNode segment = t.get("segment");
          List<String> found = null;
          if (!t.get("found").isNull()) {
            found = new ArrayList<>();
            for (JsonNode f : t.get("found")) {
              found.add(f.asText());
            }
          }
          expected.add(
              "%d damage_pass %s %d %d %d %d %s"
                  .formatted(
                      tick,
                      t.get("owner").asText(),
                      segment.get(0).get(0).asInt(),
                      segment.get(0).get(1).asInt(),
                      segment.get(1).get(0).asInt(),
                      segment.get(1).get(1).asInt(),
                      found));
        }
        case "hit" ->
            expected.add(
                "%d hit %s %s %d %d %d"
                    .formatted(
                        tick,
                        t.get("owner").asText(),
                        t.get("target").asText(),
                        t.get("damage").asInt(),
                        t.get("direction").get(0).asInt(),
                        t.get("direction").get(1).asInt()));
        case "hit_action" ->
            expected.add(
                "%d hit_action %s %s %s"
                    .formatted(
                        tick,
                        t.get("owner").asText(),
                        t.get("target").asText(),
                        t.get("action").asText()));
        case "card_play_heard" -> {
          List<String> calls = new ArrayList<>();
          for (JsonNode c : t.get("calls")) {
            calls.add(c.get(0).asText() + " " + c.get(1).asText() + " " + c.get(2).asText());
          }
          expected.add(
              "%d card_play_heard %s %s %d %s %s %d %s"
                  .formatted(
                      tick,
                      t.get("owner").asText(),
                      t.get("action").asText(),
                      t.get("side").asInt(),
                      t.get("card").asText(),
                      t.get("effective").asText(),
                      t.get("total").asInt(),
                      calls));
        }
        default -> throw new IllegalArgumentException("unknown tether event " + t);
      }
    }
    return expected;
  }

  /**
   * A request for a unit's ability that a run supplies in place of its player's command: started in
   * the unit's phase-2 pass, after the run pass, where the reference makes it.
   */
  private record SuppliedRequest(CharacterEntity unit) implements BattleAction {

    @Override
    public String name() {
      return "supplied ability request";
    }

    @Override
    public int phase() {
      return EntityActions.PHASE_POST_COMPONENT_TICK;
    }

    @Override
    public ActionInstance start(ActionHolder holder) {
      unit.requestAbility();
      return null;
    }
  }

  /**
   * Logs every dash a chained dasher started with its count, hit list and first vector, every next
   * target its chain found and every chain's end.
   */
  private static WorldObserver goldenKnightLog(int[] currentTick, List<String> log) {
    return new WorldObserver() {
      @Override
      public void chainDashStarted(
          int tick, CharacterEntity unit, int fromX, int fromY, int aimX, int aimY, int radius) {
        TargetingState t = unit.getUnit().targeting();
        log.add(
            "%d dash_start %s %s count %d hit %s first %d %d aim %d %d radius %d at %d %d route %s"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    t.getReference().getEntity().getName(),
                    t.getDashChainCount(),
                    t.getHitTargetIds(),
                    t.getDashFirstX(),
                    t.getDashFirstY(),
                    aimX,
                    aimY,
                    radius,
                    fromX,
                    fromY,
                    Arrays.stream(unit.getUnit().movement().getRoute().toArray())
                        .boxed()
                        .toList()));
      }

      @Override
      public void chainDashed(
          int tick, CharacterEntity unit, WorldEntity next, int count, int x, int y) {
        log.add(
            "%d chain %s %s %d %d %d"
                .formatted(currentTick[0], unit.name(), next.name(), count, x, y));
      }

      @Override
      public void chainDashEnded(int tick, CharacterEntity unit, int count, TargetView reference) {
        log.add(
            "%d chain_end %s %d %s %d %d"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    count,
                    reference == null ? null : reference.getEntity().getName(),
                    unit.getView().getX(),
                    unit.getView().getY()));
      }
    };
  }

  /** The reference's chained dash log, in the battle's layout. */
  /**
   * Lists what the Skeleton King's ability did: each soul counted, the souls spent on its area
   * effect and the lifetime they bought, the order its spawner shuffled with the battle's random
   * state before and after, and each skeleton as its registration visit and its clone setter left
   * it, with the area effect's point and the random state after its draws.
   */
  private static WorldObserver skeletonKingLog(int[] currentTick, List<String> log) {
    return new WorldObserver() {
      @Override
      public void soulCounted(int tick, CharacterEntity unit, WorldEntity dying, int souls) {
        log.add(
            "%d soul %s %s side %d souls %d"
                .formatted(currentTick[0], unit.name(), dying.name(), dying.side(), souls));
      }

      @Override
      public void soulsSpent(
          int tick,
          CharacterEntity unit,
          AreaEffectEntity areaEffect,
          int souls,
          int count,
          int lifetimeMs) {
        log.add(
            "%d lifetime %s %d souls %d count %d"
                .formatted(currentTick[0], areaEffect.name(), lifetimeMs, souls, count));
      }

      @Override
      public void spawnOrdered(
          int tick, AreaEffectEntity areaEffect, int[] order, int stateBefore, int stateAfter) {
        log.add(
            "%d order %s %s state %d %d"
                .formatted(
                    currentTick[0],
                    areaEffect.name(),
                    Arrays.toString(order),
                    Integer.toUnsignedLong(stateBefore),
                    Integer.toUnsignedLong(stateAfter)));
      }

      @Override
      public void areaSpawned(
          int tick,
          AreaEffectEntity areaEffect,
          CharacterEntity child,
          int retries,
          int stateAfter) {
        log.add(
            "%d skeleton %s %s %d at %d %d state %d deploy %d hp %d level %d clone %d tries %d"
                    .formatted(
                        currentTick[0],
                        areaEffect.name(),
                        child.name(),
                        child.getId(),
                        child.getView().getX(),
                        child.getView().getY(),
                        child.getView().getState(),
                        child.getView().getDeployCountdown(),
                        child.getHitPoints().getHitPoints(),
                        child.getPackedLevel(),
                        child.isClone() ? 1 : 0,
                        retries + 1)
                + " area %d %d state %d"
                    .formatted(areaEffect.x(), areaEffect.y(), Integer.toUnsignedLong(stateAfter)));
      }
    };
  }

  private static List<String> expectedSkeletonKingLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode k : reference.path("skeleton_king_ability")) {
      int tick = k.get("tick").asInt();
      switch (k.get("event").asText()) {
        case "soul" ->
            expected.add(
                "%d soul %s %s side %d souls %d"
                    .formatted(
                        tick,
                        k.get("unit").asText(),
                        k.get("dying").asText(),
                        k.get("side").asInt(),
                        k.get("souls").asInt()));
        case "lifetime" ->
            expected.add(
                "%d lifetime %s %d souls %d count %d"
                    .formatted(
                        tick,
                        k.get("area_effect").asText(),
                        k.get("lifetime").asInt(),
                        k.get("souls").asInt(),
                        k.get("count").asInt()));
        case "order" ->
            expected.add(
                "%d order %s %s state %d %d"
                    .formatted(
                        tick,
                        k.get("area_effect").asText(),
                        ints(k.get("order")),
                        k.get("state").get(0).asLong(),
                        k.get("state").get(1).asLong()));
        case "skeleton" ->
            expected.add(
                "%d skeleton %s %s %d at %d %d state %d deploy %d hp %d level %d clone %d tries %d"
                        .formatted(
                            tick,
                            k.get("area_effect").asText(),
                            k.get("unit").asText(),
                            k.get("id").asInt(),
                            k.get("x").asInt(),
                            k.get("y").asInt(),
                            k.get("state").asInt(),
                            k.get("deploy").asInt(),
                            k.get("hp").asInt(),
                            k.get("level").asInt(),
                            k.get("clone").asInt(),
                            k.get("tries").asInt())
                    + " area %d %d state %d"
                        .formatted(
                            k.get("at").get(0).asInt(),
                            k.get("at").get(1).asInt(),
                            k.get("state_after").asLong()));
        default -> {
          // The area effect's creation is held by the area effects' own log.
        }
      }
    }
    return expected;
  }

  private static List<String> expectedGoldenKnightLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode g : reference.path("golden_knight")) {
      int tick = g.get("tick").asInt();
      String unit = g.get("unit").asText();
      switch (g.get("event").asText()) {
        case "supplied_request" -> expected.add(tick + " supplied_request " + unit);
        case "ability_handler" -> {
          List<List<Object>> found = new ArrayList<>();
          g.get("candidates")
              .forEach(
                  c -> found.add(List.of(c.get(0).asText(), c.get(1).asInt(), c.get(2).asInt())));
          Map<String, JsonNode> calls = new HashMap<>();
          g.get("calls").forEach(c -> calls.put(c.get(0).asText(), c));
          JsonNode query = calls.get("query");
          JsonNode dash = calls.get("dash_to");
          expected.add(
              "%d ability_handler %s query %d %d %d found %s winner %s %d %d %d"
                  .formatted(
                      tick,
                      unit,
                      query.get(1).asInt(),
                      query.get(2).asInt(),
                      query.get(3).asInt(),
                      found,
                      calls.get("set_target").get(1).asText(),
                      dash.get(1).asInt(),
                      dash.get(2).asInt(),
                      dash.get(3).asInt()));
        }
        case "stun_cleanse" -> {
          List<String> removed = new ArrayList<>();
          g.get("removed").forEach(r -> removed.add(r.asText()));
          expected.add("%d stun_cleanse %s %s".formatted(tick, unit, removed));
        }
        case "dash_start" -> {
          List<Integer> hit = new ArrayList<>();
          g.get("hitlist").forEach(h -> hit.add(h.asInt()));
          List<Integer> route = new ArrayList<>();
          g.get("route").forEach(r -> route.add(r.asInt()));
          expected.add(
              "%d dash_start %s %s count %d hit %s first %d %d aim %d %d radius %d at %d %d route %s"
                  .formatted(
                      tick,
                      unit,
                      g.get("ref").asText(),
                      g.get("count").asInt(),
                      hit,
                      g.get("first").get(0).asInt(),
                      g.get("first").get(1).asInt(),
                      g.get("aim").get(0).asInt(),
                      g.get("aim").get(1).asInt(),
                      g.get("w3").asInt(),
                      g.get("x").asInt(),
                      g.get("y").asInt(),
                      route));
        }
        case "chain" ->
            expected.add(
                "%d chain %s %s %d %d %d"
                    .formatted(
                        tick,
                        unit,
                        g.get("target").asText(),
                        g.get("count").asInt(),
                        g.get("x").asInt(),
                        g.get("y").asInt()));
        case "chain_end" ->
            expected.add(
                "%d chain_end %s %d %s %d %d"
                    .formatted(
                        tick,
                        unit,
                        g.get("count").asInt(),
                        g.get("ref").isNull() ? null : g.get("ref").asText(),
                        g.get("x").asInt(),
                        g.get("y").asInt()));
        default -> throw new IllegalArgumentException("a chained dash log entry " + g);
      }
    }
    return expected;
  }

  /**
   * Logs what the champion slots did - the deck pass, each follow of a play, each activation, each
   * cooldown's end and each refund - every ability's buff, and every ability command with what it
   * came to; and writes every slot step as the reference's trace row.
   */
  private static WorldObserver championLog(
      int[] currentTick, List<String> log, List<String> trace) {
    return new WorldObserver() {
      @Override
      public void championDeckPass(int tick, ChampionController slot) {
        // The pass runs as the match is set up, before the first step.
        log.add(
            "%d deck_pass %d %d %s %d %d %d"
                .formatted(
                    tick,
                    slot.side(),
                    slot.getSlot(),
                    slot.getChampion().name(),
                    slot.getDeckIndex(),
                    slot.getState(),
                    slot.getCooldownFullMs()));
      }

      @Override
      public void championFollowed(int tick, ChampionController slot, String play) {
        log.add(
            "%d follow %d %d %s %s %d %d %d %d"
                .formatted(
                    currentTick[0],
                    slot.side(),
                    slot.getSlot(),
                    slot.getChampion().name(),
                    play,
                    slot.getDeployIndex(),
                    slot.getCharges(),
                    slot.getCooldownMs(),
                    slot.getState()));
      }

      @Override
      public void championActivated(
          int tick, ChampionController slot, List<CharacterEntity> requested) {
        log.add(
            "%d activation %d %d %s %d %d %d %d %d"
                .formatted(
                    currentTick[0],
                    slot.side(),
                    slot.getSlot(),
                    requested.stream().map(CharacterEntity::name).toList(),
                    slot.getCooldownMs(),
                    slot.getCharges(),
                    slot.getTriggerMs(),
                    slot.getPaidMana(),
                    slot.getState()));
      }

      @Override
      public void championCooldownOut(int tick, ChampionController slot) {
        log.add(
            "%d cooldown_out %d %d %d"
                .formatted(currentTick[0], slot.side(), slot.getSlot(), slot.getState()));
      }

      @Override
      public void championRefunded(
          int tick, ChampionController slot, int mana, int elixirBefore, int elixirAfter) {
        log.add(
            "%d refund %d %d %d %d %d"
                .formatted(
                    currentTick[0], slot.side(), slot.getSlot(), mana, elixirBefore, elixirAfter));
      }

      @Override
      public void abilityBuffed(
          int tick, CharacterEntity unit, String buff, int timeMs, int packedLevel) {
        log.add(
            "%d ability_buff %s %s %d %d %d"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    buff,
                    timeMs,
                    packedLevel,
                    unit.getView().getState()));
      }

      @Override
      public void championStepped(
          int tick, ChampionController slot, int elixir, List<ChampionView> views) {
        List<Object> row = new ArrayList<>();
        row.add(currentTick[0]);
        row.add("step");
        row.add(slot.side());
        row.add(slot.getSlot());
        row.add(slot.getState());
        row.add(slot.getCooldownMs());
        row.add(slot.getCharges());
        row.add(slot.getTriggerMs());
        row.add(slot.getDeployIndex());
        row.add(slot.champions().stream().map(CharacterEntity::name).toList());
        row.add(elixir);
        List<List<Object>> seen = new ArrayList<>();
        for (ChampionView v : views) {
          seen.add(
              List.of(
                  v.name(),
                  v.row(),
                  v.deployIndex(),
                  v.state(),
                  v.pending() ? 1 : 0,
                  v.warning(),
                  v.tags(),
                  v.cloned() ? 1 : 0));
        }
        row.add(seen);
        trace.add(JSON.valueToTree(row).toString());
      }
    };
  }

  /**
   * The reference's champion log in the battle's layout. A command is held by the battle's own
   * record of it; the log's command entries are compared there.
   */
  private static List<String> expectedChampionLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode c : reference.path("champion").path("log")) {
      int tick = c.get("tick").asInt();
      switch (c.get("event").asText()) {
        case "deck_pass" ->
            expected.add(
                "%d deck_pass %d %d %s %d %d %d"
                    .formatted(
                        tick,
                        c.get("side").asInt(),
                        c.get("slot").asInt(),
                        c.get("champion").asText(),
                        c.get("deck_index").asInt(),
                        c.get("state").asInt(),
                        c.get("cooldown_full").asInt()));
        case "follow" ->
            expected.add(
                "%d follow %d %d %s %s %d %d %d %d"
                    .formatted(
                        tick,
                        c.get("side").asInt(),
                        c.get("slot").asInt(),
                        c.get("champion").asText(),
                        c.get("command").asText(),
                        c.get("deploy_index").asInt(),
                        c.get("charges").asInt(),
                        c.get("cooldown").asInt(),
                        c.get("state").asInt()));
        case "activation" -> {
          List<String> requests = new ArrayList<>();
          c.get("requests").forEach(r -> requests.add(r.asText()));
          expected.add(
              "%d activation %d %d %s %d %d %d %d %d"
                  .formatted(
                      tick,
                      c.get("side").asInt(),
                      c.get("slot").asInt(),
                      requests,
                      c.get("cooldown").asInt(),
                      c.get("charges").asInt(),
                      c.get("trigger").asInt(),
                      c.get("paid").asInt(),
                      c.get("state").asInt()));
        }
        case "cooldown_out" ->
            expected.add(
                "%d cooldown_out %d %d %d"
                    .formatted(
                        tick,
                        c.get("side").asInt(),
                        c.get("slot").asInt(),
                        c.get("state").asInt()));
        case "refund" ->
            expected.add(
                "%d refund %d %d %d %d %d"
                    .formatted(
                        tick,
                        c.get("side").asInt(),
                        c.get("slot").asInt(),
                        c.get("amount").asInt(),
                        c.get("elixir").get(0).asInt(),
                        c.get("elixir").get(1).asInt()));
        case "ability_buff" ->
            expected.add(
                "%d ability_buff %s %s %d %d %d"
                    .formatted(
                        tick,
                        c.get("unit").asText(),
                        c.get("buff").asText(),
                        c.get("time").asInt(),
                        c.get("level").asInt(),
                        c.get("state").asInt()));
        case "command" -> {}
        default -> throw new IllegalArgumentException("a champion log entry " + c);
      }
    }
    return expected;
  }

  /**
   * Every ability command: the tick it ran on, its code, the elixir before and after, and the units
   * it requested; and the code and elixir the reference's champion log gives it.
   */
  private static void assertAbilityUses(Standard1v1Battle match, JsonNode reference) {
    Map<String, Standard1v1Battle.AbilityUse> uses = new HashMap<>();
    for (Standard1v1Battle.AbilityUse use : match.getAbilityUses()) {
      uses.put(use.name(), use);
    }
    for (JsonNode command : reference.path("commands")) {
      if (!command.has("ability")) {
        continue;
      }
      String name = command.get("name").asText();
      Standard1v1Battle.AbilityUse use = uses.get(name);
      assertThat(use).as("the ability command %s ran", name).isNotNull();
      assertThat(use.tick()).as("%s: its tick", name).isEqualTo(command.get("tick").asInt());
      AbilityCommand.Outcome outcome = use.outcome();
      assertThat(outcome.code()).as("%s: its code", name).isEqualTo(command.get("code").asInt());
      assertThat(List.of(outcome.elixirBefore(), outcome.elixirAfter()))
          .as("%s: the elixir before and after", name)
          .containsExactly(
              command.get("elixir_before").asInt(), command.get("elixir_after").asInt());
      List<String> requests = new ArrayList<>();
      command.path("requests").forEach(r -> requests.add(r.asText()));
      assertThat(outcome.requested().stream().map(CharacterEntity::name).toList())
          .as("%s: the units requested", name)
          .isEqualTo(requests);
    }
    assertThat(uses.keySet())
        .as("every ability command")
        .hasSize(
            (int)
                StreamSupport.stream(reference.path("commands").spliterator(), false)
                    .filter(c -> c.has("ability"))
                    .count());
  }

  /**
   * The reference's group chain and Goblinstein log, in the battle's layout. The HP-bar run and the
   * card-play listener the monster's starting group lists are held by the run log, which lists each
   * as it starts; the steps that only connect are listed by what they connected to.
   */
  private static List<String> expectedGoblinsteinLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode g : reference.path("goblinstein")) {
      int tick = g.get("tick").asInt();
      switch (g.get("event").asText()) {
        case "group_link" ->
            expected.add(
                "%d group_link %s %s"
                    .formatted(
                        tick,
                        g.get("unit").asText(),
                        g.get("after").isNull() ? null : g.get("after").asText()));
        case "group_unlink" ->
            expected.add("%d group_unlink %s".formatted(tick, g.get("unit").asText()));
        case "hp_bar", "listener" -> {}
        case "start" ->
            expected.add(
                "%d start %s %s %d"
                    .formatted(
                        tick,
                        g.get("owner").asText(),
                        g.get("action").asText(),
                        g.get("phase").asInt()));
        case "step" -> {
          // The tether's activation rows and damage passes are held by the tether log.
          for (JsonNode call : g.get("calls")) {
            String owner = g.get("owner").asText();
            switch (call.get(0).asText()) {
              case "connect" -> {
                JsonNode connected = call.get(1);
                expected.add(
                    "%d connect %s %s"
                        .formatted(tick, owner, connected.isNull() ? null : connected.asText()));
              }
              case "cast_seen", "tether_end" ->
                  expected.add("%d %s %s".formatted(tick, call.get(0).asText(), owner));
              case "tether_start" ->
                  expected.add("%d tether_start %d %s".formatted(tick, call.get(1).asInt(), owner));
              case "schedule", "damage_pass" -> {}
              default ->
                  throw new IllegalArgumentException("a Goblinstein step that does more: " + g);
            }
          }
        }
        case "death_area" ->
            expected.add(
                "%d death_area %s %s %s %d %d %d %d %d %d"
                    .formatted(
                        tick,
                        g.get("owner").asText(),
                        g.get("removed").asText(),
                        g.get("area_effect").asText(),
                        g.get("id").asInt(),
                        g.get("x").asInt(),
                        g.get("y").asInt(),
                        g.get("side").asInt(),
                        g.get("level").asInt(),
                        g.get("countdown").asInt()));
        case "death_area_ended" ->
            expected.add(
                "%d death_area_ended %s %s"
                    .formatted(tick, g.get("owner").asText(), g.get("area_effect").asText()));
        default -> throw new IllegalArgumentException("unknown Goblinstein event " + g);
      }
    }
    return expected;
  }

  /** The reference's asks of an area effect's buff test of a clone, in the gate log's layout. */
  private static List<String> expectedCloneGateLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode g : reference.path("clone_buff_gate")) {
      expected.add(
          "%d gate %s %s %s %s query %d refused %b"
              .formatted(
                  g.get("tick").asInt(),
                  g.get("area_effect").asText(),
                  g.get("buff").asText(),
                  g.get("clone").asText(),
                  g.get("path").asText(),
                  g.get("query").asInt(),
                  g.get("refused").asInt() == 1));
    }
    return expected;
  }

  /**
   * The reference's reads of attack_count and asks of a buff's life condition, in its log's order.
   */
  private static List<String> expectedPrinceLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode e : reference.path("little_prince")) {
      int tick = e.get("tick").asInt();
      if (e.get("event").asText().equals("attack_count")) {
        expected.add(
            "%d attack_count %s %d %d"
                .formatted(
                    tick, e.get("owner").asText(), e.get("timer").asInt(), e.get("value").asInt()));
      } else {
        expected.add(
            "%d alive_if %s %s %d"
                .formatted(
                    tick,
                    e.get("unit").asText(),
                    e.get("expression").asText(),
                    e.get("value").asInt()));
      }
    }
    return expected;
  }

  /**
   * The reference's killed-done checks scheduled and checks of an action's cause, in its log's
   * order.
   */
  private static List<String> expectedKillLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode e : reference.path("kill_hooks")) {
      int tick = e.get("tick").asInt();
      if (e.get("event").asText().equals("killed_done")) {
        expected.add(
            "%d killed_done %s %s %s %s"
                .formatted(
                    tick,
                    e.get("owner").asText(),
                    e.get("killed").asText(),
                    e.get("action").asText(),
                    e.get("in_pending").asBoolean()));
      } else {
        expected.add(
            "%d instigator_match %s %s %s %s %s"
                .formatted(
                    tick,
                    e.get("owner").asText(),
                    e.get("action").asText(),
                    e.get("instigator").isNull() ? null : e.get("instigator").asText(),
                    e.get("row").isNull() ? null : e.get("row").asText(),
                    e.get("scheduled").isNull() ? null : e.get("scheduled").asText()));
      }
    }
    return expected;
  }

  /**
   * The reference's Kamikaze ends and drains, in the Kamikaze log's layout. An end of a unit with
   * hit points never sets the no-hit-points byte.
   */
  private static List<String> expectedKamikazeLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode k : reference.path("kamikaze")) {
      int tick = k.get("tick").asInt();
      if (k.has("drain")) {
        expected.add(
            "%d drain %s %d hp %d before %d"
                .formatted(
                    tick,
                    k.get("unit").asText(),
                    k.get("drain").asInt(),
                    k.get("hp").asInt(),
                    k.get("before").asInt()));
      } else {
        assertThat(k.get("f173").asInt()).as("%s: the no-hit-points byte", k).isZero();
        expected.add(
            "%d end %s hp %d kill %s"
                .formatted(
                    tick, k.get("unit").asText(), k.get("hp").asInt(), k.get("kill").asBoolean()));
      }
    }
    return expected;
  }

  /**
   * Logs what every Goblin Hut's life state does: its start and each step with its four words
   * before and after and what it called, each find the query answered, each child's point, each
   * child as it is made, and the notice that its target left. Objects are named as they were when
   * the run met them.
   */
  private static WorldObserver goblinHutLog(
      Standard1v1Battle match, int[] currentTick, List<String> log, Set<String> children) {
    Map<Integer, String> names = new HashMap<>();
    boolean[] childDue = {false};
    return new WorldObserver() {
      private String name(int id) {
        if (id == GoblinHutLifeState.NO_TARGET) {
          return null;
        }
        if (match.getWorld().liveObject(id) instanceof WorldEntity entity) {
          names.put(id, entity.name());
        }
        return names.get(id);
      }

      @Override
      public void goblinHutLogged(int tick, CharacterEntity hut, GoblinHutLifeState.Event event) {
        int t = currentTick[0];
        if (event instanceof GoblinHutLifeState.Stepped s) {
          log.add(
              s.start()
                  ? "%d start %s calls %s after %s"
                      .formatted(t, hut.name(), s.calls(), words(s.after()))
                  : "%d step %s before %s calls %s after %s"
                      .formatted(t, hut.name(), words(s.before()), s.calls(), words(s.after())));
        } else if (event instanceof GoblinHutLifeState.Found f) {
          log.add(
              "%d find %s listed %s found %s"
                  .formatted(
                      t,
                      hut.name(),
                      f.listed().stream().map(this::name).toList(),
                      name(f.found())));
        } else if (event instanceof GoblinHutLifeState.SpawnPoint p) {
          log.add(
              "%d spawn_point %s count %d i %d target %s point %d %d at %d %d"
                  .formatted(
                      t,
                      hut.name(),
                      p.count(),
                      p.index(),
                      name(p.target()),
                      p.x(),
                      p.y(),
                      p.atX(),
                      p.atY()));
          childDue[0] = true;
        } else if (event instanceof GoblinHutLifeState.TargetLeft l) {
          log.add("%d target_left %s %s".formatted(t, hut.name(), name(l.target())));
        }
      }

      @Override
      public void characterSpawned(
          int tick, SpawnHost source, CharacterEntity child, int x, int y) {
        if (!childDue[0]) {
          return;
        }
        childDue[0] = false;
        children.add(child.name());
        log.add(
            "%d spawn %s %s %d %s at %d %d state %d deploy %d hp %d level %d immune %d"
                .formatted(
                    currentTick[0],
                    source.name(),
                    child.name(),
                    child.getId(),
                    child.getData().name(),
                    x,
                    y,
                    child.getView().getState(),
                    child.getView().getDeployCountdown(),
                    child.getHitPoints().getHitPoints(),
                    child.getPackedLevel(),
                    child.isSpawnImmune() ? 1 : 0));
      }
    };
  }

  /** A life state's four words as the hut log lists them. */
  private static String words(GoblinHutLifeState.Memory m) {
    return "%d %d %d %d".formatted(m.timerMs(), m.target(), m.lost() ? 1 : 0, m.count());
  }

  /** The reference's Goblin Hut log, in the hut log's layout. */
  private static List<String> expectedGoblinHutLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode h : reference.path("goblin_hut")) {
      int tick = h.get("tick").asInt();
      String owner = h.get("owner").asText();
      switch (h.get("event").asText()) {
        case "start" ->
            expected.add(
                "%d start %s calls %s after %s"
                    .formatted(tick, owner, calls(h.get("calls")), jsonWords(h.get("after"))));
        case "step" ->
            expected.add(
                "%d step %s before %s calls %s after %s"
                    .formatted(
                        tick,
                        owner,
                        jsonWords(h.get("before")),
                        calls(h.get("calls")),
                        jsonWords(h.get("after"))));
        case "find" -> {
          List<String> listed = new ArrayList<>();
          h.get("listed").forEach(n -> listed.add(n.asText()));
          expected.add(
              "%d find %s listed %s found %s"
                  .formatted(
                      tick,
                      owner,
                      listed,
                      h.get("found").isNull() ? null : h.get("found").asText()));
        }
        case "spawn_point" ->
            expected.add(
                "%d spawn_point %s count %d i %d target %s point %d %d at %d %d"
                    .formatted(
                        tick,
                        owner,
                        h.get("count").asInt(),
                        h.get("i").asInt(),
                        h.get("target").asText(),
                        h.get("point").get(0).asInt(),
                        h.get("point").get(1).asInt(),
                        h.get("relocated").get(0).asInt(),
                        h.get("relocated").get(1).asInt()));
        case "spawn" ->
            expected.add(
                "%d spawn %s %s %d %s at %d %d state %d deploy %d hp %d level %d immune %d"
                    .formatted(
                        tick,
                        owner,
                        h.get("unit").asText(),
                        h.get("id").asInt(),
                        h.get("row").asText(),
                        h.get("created").get(0).asInt(),
                        h.get("created").get(1).asInt(),
                        h.get("state").asInt(),
                        h.get("deploy").asInt(),
                        h.get("hp").asInt(),
                        h.get("level").asInt(),
                        h.get("immune").asInt()));
        case "target_left" ->
            expected.add("%d target_left %s %s".formatted(tick, owner, h.get("target").asText()));
        default -> throw new IllegalStateException("unknown Goblin Hut event " + h);
      }
    }
    return expected;
  }

  /** A logged call list as the hut log lists it: each call's words joined by spaces. */
  private static List<String> calls(JsonNode calls) {
    List<String> out = new ArrayList<>();
    for (JsonNode call : calls) {
      List<String> parts = new ArrayList<>();
      call.forEach(part -> parts.add(part.isNull() ? "null" : part.asText()));
      out.add(String.join(" ", parts));
    }
    return out;
  }

  /** The reference's four words as the hut log lists them. */
  private static String jsonWords(JsonNode m) {
    return "%d %d %d %d"
        .formatted(
            m.get("timer").asInt(),
            m.get("target").asInt(),
            m.get("lost").asInt(),
            m.get("count").asInt());
  }

  /** The lanes the reference's const-priority rings asked for, in the lane log's layout. */
  private static List<String> expectedLaneLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode a : reference.path("actions")) {
      if (a.get("event").asText().equals("const_priority_lane")) {
        expected.add(
            "%d const_priority_lane %s %d %d lane %d"
                .formatted(
                    a.get("tick").asInt(),
                    a.get("owner").asText(),
                    a.get("x").asInt(),
                    a.get("y").asInt(),
                    a.get("lane").asInt()));
      }
    }
    return expected;
  }

  /** The reference's special loads, drag states, holds and pulls, in the hook log's layout. */
  private static List<String> expectedHookLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode h : reference.path("hooks")) {
      int tick = h.get("tick").asInt();
      switch (h.get("event").asText()) {
        case "arm" ->
            expected.add(
                "%d arm %s ref %s distance %d ring %d %d load %d after %d"
                    .formatted(
                        tick,
                        h.get("unit").asText(),
                        h.get("ref").asText(),
                        h.get("distance").asLong(),
                        h.get("ring").get(0).asInt(),
                        h.get("ring").get(1).asInt(),
                        h.get("load").asInt(),
                        h.get("after").asInt()));
        case "state" ->
            expected.add(
                "%d state %s %s %d to %d now %d at %d %d"
                    .formatted(
                        tick,
                        h.get("projectile").asText(),
                        h.get("unit").asText(),
                        h.get("old").asInt(),
                        h.get("new").asInt(),
                        h.get("state").asInt(),
                        h.get("x").asInt(),
                        h.get("y").asInt()));
        case "held_left" -> {
          // No owner of a dragging projectile morphs back: the battle refuses one that would.
          assertThat(h.get("morph_character").isNull()).isTrue();
          expected.add(
              "%d held_left %s %s"
                  .formatted(tick, h.get("unit").asText(), h.get("projectile").asText()));
        }
        case "follow_left" ->
            expected.add(
                "%d follow_left %s %s now %d at %d %d"
                    .formatted(
                        tick,
                        h.get("unit").asText(),
                        h.get("projectile").asText(),
                        h.get("state").asInt(),
                        h.get("x").asInt(),
                        h.get("y").asInt()));
        default -> throw new IllegalStateException("unknown hook event " + h);
      }
    }
    return expected;
  }

  /** A reflect in the reference's layout. */
  private static String reflectLine(int tick, Reflection r) {
    String line =
        "%d reflect %s by %s kind %s source %s struck %s speed %d"
            .formatted(
                tick,
                r.target().name(),
                r.attacker() instanceof AreaEffectEntity a ? a.name() : attackerName(r.attacker()),
                r.attacker() == null ? null : String.valueOf(r.attacker().getKind()),
                r.source() == null ? null : r.source().name(),
                r.struck() == null ? null : r.struck().name(),
                r.hitSpeed());
    if (r.buff() != null) {
      line += " buff %s %d %d".formatted(r.buff(), r.buffTimeMs(), r.buffLevel());
    }
    if (r.damage() != 0) {
      line += " damage %d %d %d".formatted(r.damage(), r.hitPointsBefore(), r.hitPointsAfter());
    }
    return line;
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
                    // A guard's charge has no reference.
                    reference == null ? null : reference.getEntity().getName(),
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
  /**
   * Logs a hiding building's deploy end, which runs its targeting visit from the state visit, and
   * each visit of its hide handler that shows something, as the reference marks it: the effects the
   * handler plays, the counter reaching the hide time (hidden) or leaving it (visible), reaching 0
   * (up), and a change of the step from the last one logged.
   */
  private static WorldObserver hidingLog(int[] currentTick, List<String> log) {
    Map<String, Integer> lastStep = new HashMap<>();
    return new WorldObserver() {
      @Override
      public void deployEndVisited(int tick, CharacterEntity unit) {
        log.add(
            "%d deploy_end_visit %s %s %d"
                .formatted(
                    currentTick[0], unit.name(), referenceName(unit), unit.getView().getState()));
      }

      @Override
      public void hideVisited(
          int tick,
          CharacterEntity unit,
          int state,
          int before,
          int after,
          int step,
          List<String> effects) {
        int hide = unit.getData().hideTimeMs();
        List<String> marks = new ArrayList<>(effects);
        if (after == hide && before != hide) {
          marks.add("hidden");
        }
        if (before == hide && after != hide) {
          marks.add("visible");
        }
        if (after == 0 && before != 0) {
          marks.add("up");
        }
        if (step != lastStep.getOrDefault(unit.name(), StateQueries.TICK_MS)) {
          marks.add("step");
          lastStep.put(unit.name(), step);
        }
        if (!marks.isEmpty()) {
          log.add(
              "%d hide %s %d %d %d %d %s"
                  .formatted(currentTick[0], unit.name(), state, before, after, step, marks));
        }
      }
    };
  }

  /**
   * Logs each hit of an attracting area effect: its centre, and each unit it pulled with the vector
   * to the centre and its push accumulators before and after.
   */
  private static WorldObserver pullLog(int[] currentTick, List<String> log) {
    return new WorldObserver() {
      @Override
      public void areaPulled(
          int tick, AreaEffectEntity areaEffect, List<AreaEffectEntity.Pull> pulls) {
        List<String> pulled = new ArrayList<>();
        for (AreaEffectEntity.Pull pull : pulls) {
          pulled.add(
              "%s %d %d %s %s"
                  .formatted(
                      pull.target().name(),
                      pull.dx(),
                      pull.dy(),
                      Arrays.toString(pull.before()),
                      Arrays.toString(pull.after())));
        }
        log.add(
            "%d pull %s %d %d %s"
                .formatted(
                    currentTick[0],
                    areaEffect.name(),
                    areaEffect.getX(),
                    areaEffect.getY(),
                    pulled));
      }
    };
  }

  /**
   * Logs each push of a unit entering its deploying state: its radius and distance, what its query
   * found and whom it asked to push.
   */
  private static WorldObserver deployPushLog(int[] currentTick, List<String> log) {
    return new WorldObserver() {
      @Override
      public void deployPushed(
          int tick,
          CharacterEntity unit,
          int radius,
          int distance,
          List<WorldEntity> found,
          List<WorldEntity> pushed) {
        log.add(
            "%d %s %d %d %s %s"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    radius,
                    distance,
                    found.stream().map(WorldEntity::name).toList(),
                    pushed.stream().map(WorldEntity::name).toList()));
      }
    };
  }

  private static List<String> expectedDeployPushLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode e : reference.path("deploy_push")) {
      List<String> found = new ArrayList<>();
      e.get("candidates").forEach(n -> found.add(n.asText()));
      List<String> pushed = new ArrayList<>();
      e.get("requested").forEach(n -> pushed.add(n.asText()));
      expected.add(
          "%d %s %d %d %s %s"
              .formatted(
                  e.get("tick").asInt(),
                  e.get("unit").asText(),
                  e.get("radius").asInt(),
                  e.get("distance").asInt(),
                  found,
                  pushed));
    }
    return expected;
  }

  private static List<String> expectedPullLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode e : reference.path("pulls")) {
      List<String> pulled = new ArrayList<>();
      for (JsonNode p : e.get("pushed")) {
        int[] before = new int[5];
        int[] after = new int[5];
        for (int i = 0; i < 5; i++) {
          before[i] = p.get("before").get(i).asInt();
          after[i] = p.get("after").get(i).asInt();
        }
        pulled.add(
            "%s %d %d %s %s"
                .formatted(
                    p.get("target").asText(),
                    p.get("vector").get(0).asInt(),
                    p.get("vector").get(1).asInt(),
                    Arrays.toString(before),
                    Arrays.toString(after)));
      }
      expected.add(
          "%d pull %s %d %d %s"
              .formatted(
                  e.get("tick").asInt(),
                  e.get("area_effect").asText(),
                  e.get("centre").get(0).asInt(),
                  e.get("centre").get(1).asInt(),
                  pulled));
    }
    return expected;
  }

  private static List<String> expectedHidingLog(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    for (JsonNode e : reference.path("hiding")) {
      if (e.get("event").asText().equals("deploy_end_visit")) {
        expected.add(
            "%d deploy_end_visit %s %s %d"
                .formatted(
                    e.get("tick").asInt(),
                    e.get("unit").asText(),
                    e.get("ref").asText(),
                    e.get("state").asInt()));
      } else {
        List<String> marks = new ArrayList<>();
        e.get("marks").forEach(mark -> marks.add(mark.asText()));
        expected.add(
            "%d hide %s %d %d %d %d %s"
                .formatted(
                    e.get("tick").asInt(),
                    e.get("unit").asText(),
                    e.get("state").asInt(),
                    e.get("before").asInt(),
                    e.get("after").asInt(),
                    e.get("step").asInt(),
                    marks));
      }
    }
    return expected;
  }

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
                      e.get("ref").isNull() ? null : e.get("ref").asText(),
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
   * A push asked before its own hit's damage, as the evolved Executioner's strong hit asks it, is
   * listed just before that hit in both, and stays where it is.
   */
  private static List<String> pushesAfterTheirArea(List<String> events) {
    List<String> ordered = new ArrayList<>();
    List<String> pushes = new ArrayList<>();
    String area = null;
    for (int i = 0; i < events.size(); i++) {
      String event = events.get(i);
      String[] words = event.split(" ");
      String last = ordered.isEmpty() ? null : areaOf(ordered.get(ordered.size() - 1));
      if (words[1].equals("pushback") && pushedBeforeItsHit(words, events, i)) {
        ordered.addAll(pushes);
        pushes.clear();
        ordered.add(event);
        continue;
      }
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

  /**
   * Whether a push is followed at once by an impact on the unit it pushed, on the same tick: a push
   * the hit asked for before its damage.
   */
  private static boolean pushedBeforeItsHit(String[] words, List<String> events, int i) {
    if (i + 1 >= events.size()) {
      return false;
    }
    String[] next = events.get(i + 1).split(" ");
    return next[0].equals(words[0]) && next[1].equals("impact") && next[3].equals(words[2]);
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

  /**
   * Every Mirror item a play carried: the card it repeats, the Mirror's deck index, its level and
   * the item's, and the item's cost; the level each repeated play ran at, and the last card a side
   * keeps after its final play.
   */
  private static void assertMirror(
      Standard1v1Battle match, LadderMatch ladder, JsonNode reference) {
    Map<String, Standard1v1Battle.Play> plays = new HashMap<>();
    for (Standard1v1Battle.Play play : match.getPlays()) {
      plays.put(play.name(), play);
    }
    Map<Integer, String> lastKept = new HashMap<>();
    for (JsonNode e : reference.path("mirror")) {
      Standard1v1Battle.Play play = plays.get(e.get("command").asText());
      assertThat(play).as("the Mirror play %s", e.get("command").asText()).isNotNull();
      MirrorItem item = play.mirror();
      switch (e.get("event").asText()) {
        case "item" -> {
          assertThat(item).as("%s carries a Mirror item", play.name()).isNotNull();
          assertThat(item.repeats() == null ? null : item.repeats().name())
              .as("%s: the card repeated", play.name())
              .isEqualTo(e.get("repeats").asText(null))
              .isEqualTo(e.get("source").asText(null));
          assertThat(item.index())
              .as("%s: the Mirror's index", play.name())
              .isEqualTo(e.get("index").asInt());
          assertThat(item.mirrorLevelField())
              .as("%s: the Mirror's level", play.name())
              .isEqualTo(e.get("mirror_level").asInt());
          assertThat(item.levelField())
              .as("%s: the item's level", play.name())
              .isEqualTo(e.get("item_level").asInt());
          assertThat(item.cost())
              .as("%s: the item's cost", play.name())
              .isEqualTo(e.get("cost").asInt());
        }
        case "refused" -> {
          assertThat(play.matchCode())
              .as("%s: the code", play.name())
              .isEqualTo(e.get("code").asInt());
          assertThat(item.cost()).as("%s: the cost", play.name()).isEqualTo(e.get("cost").asInt());
        }
        case "play" -> {
          assertThat(item.level())
              .as("%s: the level played", play.name())
              .isEqualTo(e.get("level").asInt());
          assertThat(item.cost()).as("%s: the cost", play.name()).isEqualTo(e.get("cost").asInt());
          lastKept.put(e.get("side").asInt(), e.get("last_item").asText(null));
        }
        default -> throw new IllegalArgumentException("a Mirror log entry " + e);
      }
    }
    // No run plays again after its last Mirror, so the card a side keeps at the end is the one its
    // last Mirror play left.
    lastKept.forEach(
        (side, card) -> {
          MatchCard last = ladder.side(side).lastPlayed();
          assertThat(last == null ? null : last.name())
              .as("side %d's last card", side)
              .isEqualTo(card);
        });
  }

  /**
   * Every item a variant card's play carried: the option it was picked as, that option's index and
   * cost, and the card's deck index the play cycled.
   */
  private static void assertVariant(Standard1v1Battle match, JsonNode reference) {
    Map<String, Standard1v1Battle.Play> plays = new HashMap<>();
    for (Standard1v1Battle.Play play : match.getPlays()) {
      plays.put(play.name(), play);
    }
    Map<String, JsonNode> commands = new HashMap<>();
    for (JsonNode command : reference.path("commands")) {
      commands.put(command.get("name").asText(), command);
    }
    for (JsonNode e : reference.path("variant_picks")) {
      String name = e.get("command").asText();
      Standard1v1Battle.Play play = plays.get(name);
      assertThat(play).as("the variant play %s", name).isNotNull();
      assertThat(play.tick()).as("%s: the tick it ran on", name).isEqualTo(e.get("run").asInt());
      VariantItem item = play.variant();
      assertThat(item).as("%s carries a variant item", name).isNotNull();
      assertThat(item.spell()).as("%s: the option", name).isEqualTo(e.get("picked").asText());
      assertThat(item.option()).as("%s: its index", name).isEqualTo(e.get("option").asInt());
      assertThat(item.cost()).as("%s: its cost", name).isEqualTo(e.get("cost").asInt());
      assertThat(item.index())
          .as("%s: the deck index", name)
          .isEqualTo(commands.get(name).get("play").get("deck_index").asInt());
    }
  }

  /**
   * Every evolution and hero slot of a deck, with the card's evolved and hero rows, and every
   * play's item in a run with one: the deck index, the evolution field, the row cast, its cost and
   * the count the item was built from; and each count a side holds at the end, the one its last
   * play of the card left.
   */
  private static void assertEvolution(
      Standard1v1Battle match, LadderMatch ladder, JsonNode reference) {
    Map<String, Standard1v1Battle.Play> plays = new HashMap<>();
    for (Standard1v1Battle.Play play : match.getPlays()) {
      plays.put(play.name(), play);
    }
    Map<List<Integer>, Integer> lastCount = new HashMap<>();
    for (JsonNode e : reference.path("evolution")) {
      int side = e.get("side").asInt();
      int index = e.get("index").asInt();
      if (e.get("event").asText().equals("slot")) {
        MatchCard card = ladder.side(side).deck().get(index);
        assertThat(card.name())
            .as("side %d's card %d", side, index)
            .isEqualTo(e.get("card").asText());
        assertThat(ladder.side(side).slotFlags(index))
            .as("side %d's card %d: its slot flags", side, index)
            .isEqualTo(e.get("flags").asInt());
        assertThat(card.formRow(MatchCard.EVO_FORM).name())
            .as("%s's evolved row", card.name())
            .isEqualTo(e.get("evolution").asText());
        assertThat(card.formRow(MatchCard.HERO_FORM).name())
            .as("%s's hero row", card.name())
            .isEqualTo(e.get("hero").asText());
        continue;
      }
      String name = e.get("command").asText();
      Standard1v1Battle.Play play = plays.get(name);
      assertThat(play).as("the play %s", name).isNotNull();
      EvolutionItem item = play.evolution();
      assertThat(item).as("%s carries a deck card's item", name).isNotNull();
      assertThat(item.index()).as("%s: the deck index", name).isEqualTo(index);
      assertThat(item.field())
          .as("%s: the evolution field", name)
          .isEqualTo(e.get("field").asInt());
      assertThat(item.spell().name())
          .as("%s: the row cast", name)
          .isEqualTo(e.get("cast").asText());
      assertThat(item.cost()).as("%s: the cost", name).isEqualTo(e.get("cost").asInt());
      assertThat(item.count())
          .as("%s: the count the item was built from", name)
          .isEqualTo(e.get("count_before").asInt());
      lastCount.put(List.of(side, index), e.get("count_after").asInt());
    }
    lastCount.forEach(
        (key, count) ->
            assertThat(ladder.side(key.get(0)).evolutionCount(key.get(1)))
                .as("side %d's count of card %d at the end", key.get(0), key.get(1))
                .isEqualTo(count));
  }

  /** Every play refused by a match's gate with the reference's code, and every other let on. */
  private static void assertPlays(Standard1v1Battle match, JsonNode reference) {
    Map<String, Integer> codes = new HashMap<>();
    for (Standard1v1Battle.Play play : match.getPlays()) {
      codes.put(play.name(), play.matchCode());
    }
    for (JsonNode command : reference.path("commands")) {
      if (command.has("ability")) {
        continue;
      }
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

  // The helpers below came from the deleted kill, Musketeer, tower and placement run tests: they
  // read the golden runs' layout, collect the events those runs list and play their cards.

  private static final ObjectMapper MAPPER = new ObjectMapper();

  static JsonNode load(String resource) {
    try (InputStream stream = BattleActionSpawnRunTest.class.getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException("Missing test resource " + resource);
      }
      return MAPPER.readTree(stream);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read " + resource, e);
    }
  }

  /** The reference's records as objects keyed by its field names. */
  static List<JsonNode> records(JsonNode reference) {
    List<String> fields = new ArrayList<>();
    reference.get("fields").forEach(field -> fields.add(field.asText()));
    List<JsonNode> records = new ArrayList<>();
    for (JsonNode row : reference.get("records")) {
      ObjectNode record = MAPPER.createObjectNode();
      for (int i = 0; i < fields.size(); i++) {
        record.set(fields.get(i), row.get(i));
      }
      records.add(record);
    }
    return records;
  }

  static String unitReferenceName(CharacterEntity unit) {
    TargetView reference = unit.getUnit().targeting().getReference();
    return reference == null ? null : reference.name();
  }

  /**
   * The records of the further units, each tagged with its unit's name and keyed by tick, from the
   * layout's compact rows.
   */
  static Map<Integer, List<JsonNode>> otherRecordsByTick(JsonNode reference) {
    Map<Integer, List<JsonNode>> byTick = new HashMap<>();
    if (!reference.has("unit_records")) {
      return byTick;
    }
    List<String> fields = new ArrayList<>();
    reference.get("unit_fields").forEach(field -> fields.add(field.asText()));
    reference
        .get("unit_records")
        .fields()
        .forEachRemaining(
            entry -> {
              for (JsonNode row : entry.getValue()) {
                ObjectNode record = MAPPER.createObjectNode();
                record.put("name", entry.getKey());
                for (int i = 0; i < fields.size(); i++) {
                  record.set(fields.get(i), row.get(i));
                }
                byTick
                    .computeIfAbsent(record.get("tick").asInt(), t -> new ArrayList<>())
                    .add(record);
              }
            });
    return byTick;
  }

  /**
   * Collects every launch, impact, hit and death as the reference lists them. An area effect's hit
   * pushes each victim right after damaging it; the reference lists its pushbacks after all its
   * hits, at its area, which is the order kept here.
   */
  static WorldObserver eventCollector(int[] currentTick, List<String> events) {
    return new WorldObserver() {
      /** The pushbacks of the area effect hit under way, listed with its area. */
      private final List<String> areaEffectPushbacks = new ArrayList<>();

      /** True between an area effect's first victim and its area. */
      private boolean inAreaEffectHit;

      @Override
      public void reflectedHit(
          int tick, WorldEntity reflector, WorldEntity struck, int damage, DamageResult result) {
        events.add(
            "%d reflected_hit %s %s %d %d"
                .formatted(
                    currentTick[0],
                    reflector.name(),
                    struck.name(),
                    damage,
                    struck.getTargetView().getHitPoints()));
        if (result.died()) {
          events.add("%d death %s".formatted(currentTick[0], struck.name()));
        }
      }

      @Override
      public void damageDealt(int tick, WorldEntity target, int damage, DamageResult result) {
        // The reference lists every hit handed to the damage entry, whether it lands or the entry
        // refuses it: one of no damage, as a building without damage lands its attacks, and one on
        // an untouchable target, such as a dash landing on a dasher.
        if (currentTick[0] < 0) {
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

      /**
       * A typed hit from the drain, listed with what it took, the hit points left, its damage id
       * and its source, then its death.
       */
      @Override
      public void typedHitDealt(
          int tick,
          WorldEntity source,
          WorldEntity target,
          int amount,
          int damageId,
          DamageResult result) {
        events.add(
            "%d typed_hit %s %d %d %d %s"
                .formatted(
                    currentTick[0],
                    target.name(),
                    result.applied(),
                    target.getTargetView().getHitPoints(),
                    damageId,
                    source == null ? null : source.name()));
        if (result.died()) {
          events.add("%d death %s".formatted(currentTick[0], target.name()));
        }
      }

      /** A Kamikaze unit's kill of itself, listed as the reference lists it, then its death. */
      @Override
      public void kamikazeKilled(int tick, WorldEntity unit, int damage, DamageResult result) {
        events.add(
            "%d kamikaze_kill %s %d %d"
                .formatted(
                    currentTick[0], unit.name(), damage, unit.getTargetView().getHitPoints()));
        if (result.died()) {
          events.add("%d death %s".formatted(currentTick[0], unit.name()));
        }
      }

      /** A step of a Kamikaze unit's drain, listed as the reference lists it, then its death. */
      @Override
      public void kamikazeDrained(
          int tick, WorldEntity unit, int damage, int hitPointsBefore, DamageResult result) {
        events.add(
            "%d kamikaze_drain %s %d %d"
                .formatted(
                    currentTick[0], unit.name(), damage, unit.getTargetView().getHitPoints()));
        if (result.died()) {
          events.add("%d death %s".formatted(currentTick[0], unit.name()));
        }
      }

      /** A fallen king's circle kills with no hit of its own: the reference lists the death. */
      @Override
      public void circleKilled(int tick, WorldEntity target, int radius) {
        events.add("%d death %s".formatted(currentTick[0], target.name()));
      }

      @Override
      public void clearingKilled(int tick, WorldEntity target) {
        events.add("%d death %s".formatted(currentTick[0], target.name()));
      }

      @Override
      public void drained(int tick, WorldEntity target, int damage, int hitPoints, boolean died) {
        if (died) {
          events.add("%d death %s".formatted(currentTick[0], target.name()));
        }
      }

      @Override
      public void areaHit(
          int tick,
          WorldEntity attacker,
          WorldEntity victim,
          int damage,
          int hitId,
          DamageResult result) {
        if (currentTick[0] < 0) {
          return;
        }
        events.add(
            "%d area_hit %s %s %d %d %d"
                .formatted(
                    currentTick[0],
                    attacker.name(),
                    victim.name(),
                    damage,
                    victim.getTargetView().getHitPoints(),
                    hitId));
        if (result.died()) {
          events.add("%d death %s".formatted(currentTick[0], victim.name()));
        }
      }

      @Override
      public void areaDamaged(
          int tick, WorldEntity owner, AreaDamage.Area area, AreaDamage.Outcome outcome) {
        if (currentTick[0] < 0) {
          return;
        }
        events.add(
            "%d area %s %d %d r%d %d %d %d %s %s %s push %d %s"
                .formatted(
                    currentTick[0],
                    owner.name(),
                    area.x(),
                    area.y(),
                    area.radius(),
                    area.damage(),
                    area.towerDamage(),
                    area.hitId(),
                    viewNames(outcome.inCircle()),
                    viewNames(outcome.validated()),
                    viewNames(outcome.damaged()),
                    area.push(),
                    viewNames(outcome.pushed())));
      }

      @Override
      public void diedAtRemoval(int tick, WorldEntity entity) {
        if (currentTick[0] < 0) {
          return;
        }
        events.add("%d deploy_end_death %s".formatted(currentTick[0], entity.name()));
      }

      @Override
      public void areaEffectHit(
          int tick,
          AreaEffectEntity areaEffect,
          WorldEntity victim,
          int damage,
          DamageResult result) {
        if (currentTick[0] < 0) {
          return;
        }
        inAreaEffectHit = true;
        events.add(
            "%d area_effect_hit %s %s %d %d"
                .formatted(
                    currentTick[0],
                    areaEffect.name(),
                    victim.name(),
                    damage,
                    victim.getTargetView().getHitPoints()));
        if (result.died()) {
          events.add("%d death %s".formatted(currentTick[0], victim.name()));
        }
      }

      @Override
      public void tetherHit(
          int tick,
          AreaEffectEntity owner,
          WorldEntity target,
          int damage,
          int directionX,
          int directionY,
          DamageResult result) {
        if (currentTick[0] < 0) {
          return;
        }
        events.add(
            "%d tether_hit %s %s %d %d"
                .formatted(
                    currentTick[0],
                    owner.name(),
                    target.name(),
                    damage,
                    target.getTargetView().getHitPoints()));
        if (result.died()) {
          events.add("%d death %s".formatted(currentTick[0], target.name()));
        }
      }

      @Override
      public void areaEffectDamaged(
          int tick, AreaEffectEntity owner, AreaDamage.Area area, AreaDamage.Outcome outcome) {
        inAreaEffectHit = false;
        events.addAll(areaEffectPushbacks);
        areaEffectPushbacks.clear();
        if (currentTick[0] < 0) {
          return;
        }
        events.add(
            "%d area %s %d %d r%d %d %d %d %s %s %s push %d %s"
                .formatted(
                    currentTick[0],
                    owner.name(),
                    area.x(),
                    area.y(),
                    area.radius(),
                    area.damage(),
                    area.towerDamage(),
                    area.hitId(),
                    viewNames(outcome.inCircle()),
                    viewNames(outcome.validated()),
                    viewNames(outcome.damaged()),
                    area.push(),
                    viewNames(outcome.pushed())));
      }

      @Override
      public void buffDamaged(
          int tick,
          WorldEntity target,
          BuffInstance buff,
          int damage,
          int hitPointsBefore,
          DamageResult result) {
        if (currentTick[0] < 0) {
          return;
        }
        events.add(
            "%d buff_hit %s %d %d %d %s"
                .formatted(
                    currentTick[0],
                    target.name(),
                    damage,
                    target.getTargetView().getHitPoints(),
                    hitPointsBefore,
                    buff.getSource() == null ? null : buff.getSource().name()));
        if (result.died()) {
          events.add("%d death %s".formatted(currentTick[0], target.name()));
        }
      }

      @Override
      public void combatGateDropped(
          int tick, WorldEntity entity, TargetView reference, int hitSpeed) {
        // Only a drop a stun causes is listed: that of a living entity whose hit speed is 0.
        if (currentTick[0] < 0 || hitSpeed != 0 || !entity.getView().isAlive()) {
          return;
        }
        events.add(
            "%d combat_gate_drop %s %s %d"
                .formatted(currentTick[0], entity.name(), reference.name(), hitSpeed));
      }

      @Override
      public void combatComponentSwitched(int tick, WorldEntity entity, boolean on, int hitSpeed) {
        if (currentTick[0] < 0) {
          return;
        }
        events.add(
            "%d combat_component %s %d %d"
                .formatted(currentTick[0], entity.name(), on ? 1 : 0, hitSpeed));
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
                    projectile.launcherName(),
                    projectile.targetName(),
                    projectile.getX(),
                    projectile.getY(),
                    projectile.getZ(),
                    projectile.getAimX(),
                    projectile.getAimY(),
                    projectile.getAimZ()));
      }

      @Override
      public void pushbackRequested(
          int tick,
          WorldEntity unit,
          boolean started,
          int fromX,
          int fromY,
          MovementState pushback) {
        (inAreaEffectHit ? areaEffectPushbacks : events)
            .add(
                "%d pushback %s %d from %d %d at %d %d target %d %d budget %d"
                    .formatted(
                        currentTick[0],
                        unit.name(),
                        started ? 1 : 0,
                        fromX,
                        fromY,
                        unit.getView().getX(),
                        unit.getView().getY(),
                        pushback.getTargetX(),
                        pushback.getTargetY(),
                        pushback.getPushbackBudget()));
      }

      @Override
      public void relocated(int tick, WorldEntity unit, int x, int y, int toX, int toY) {
        events.add(
            "%d relocate %s %d %d to %d %d".formatted(currentTick[0], unit.name(), x, y, toX, toY));
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

  private static List<String> viewNames(List<TargetView> views) {
    return views.stream().map(TargetView::name).toList();
  }

  private static List<String> jsonNames(JsonNode list) {
    List<String> names = new ArrayList<>();
    list.forEach(name -> names.add(name.asText()));
    return names;
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
      case "typed_hit" ->
          "%d typed_hit %s %d %d %d %s"
              .formatted(
                  tick,
                  event.get("target").asText(),
                  event.get("damage").asInt(),
                  event.get("hp").asInt(),
                  event.get("hit_id").asInt(),
                  event.get("source").asText());
      case "reflected_hit" ->
          "%d reflected_hit %s %s %d %d"
              .formatted(
                  tick,
                  event.get("attacker").asText(),
                  event.get("target").asText(),
                  event.get("damage").asInt(),
                  event.get("hp").asInt());
      case "kamikaze_kill" ->
          "%d kamikaze_kill %s %d %d"
              .formatted(
                  tick,
                  event.get("unit").asText(),
                  event.get("damage").asInt(),
                  event.get("hp").asInt());
      case "kamikaze_drain" ->
          "%d kamikaze_drain %s %d %d"
              .formatted(
                  tick,
                  event.get("unit").asText(),
                  event.get("damage").asInt(),
                  event.get("hp").asInt());
      case "pushback" ->
          "%d pushback %s %d from %d %d at %d %d target %d %d budget %d"
              .formatted(
                  tick,
                  event.get("unit").asText(),
                  event.get("started").asInt(),
                  event.get("frm").get(0).asInt(),
                  event.get("frm").get(1).asInt(),
                  event.get("x").asInt(),
                  event.get("y").asInt(),
                  event.get("target").get(0).asInt(),
                  event.get("target").get(1).asInt(),
                  event.get("budget").asInt());
      case "relocate" ->
          "%d relocate %s %d %d to %d %d"
              .formatted(
                  tick,
                  event.get("unit").asText(),
                  event.get("x").asInt(),
                  event.get("y").asInt(),
                  event.get("to").get(0).asInt(),
                  event.get("to").get(1).asInt());
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
      case "area_hit" ->
          "%d area_hit %s %s %d %d %d"
              .formatted(
                  tick,
                  event.get("attacker").asText(),
                  event.get("target").asText(),
                  event.get("damage").asInt(),
                  event.get("hp").asInt(),
                  event.get("hit_id").asInt());
      case "deploy_end_death" ->
          "%d deploy_end_death %s".formatted(tick, event.get("unit").asText());
      case "area_effect_hit" ->
          "%d area_effect_hit %s %s %d %d"
              .formatted(
                  tick,
                  event.get("area_effect").asText(),
                  event.get("target").asText(),
                  event.get("damage").asInt(),
                  event.get("hp").asInt());
      case "tether_hit" ->
          "%d tether_hit %s %s %d %d"
              .formatted(
                  tick,
                  event.get("area_effect").asText(),
                  event.get("target").asText(),
                  event.get("damage").asInt(),
                  event.get("hp").asInt());
      case "area" ->
          "%d area %s %d %d r%d %d %d %d %s %s %s push %d %s"
              .formatted(
                  tick,
                  event.get("owner").asText(),
                  event.get("centre").get(0).asInt(),
                  event.get("centre").get(1).asInt(),
                  event.get("radius").asInt(),
                  event.get("damage").asInt(),
                  event.get("tower_damage").asInt(),
                  event.get("hit_id").asInt(),
                  jsonNames(event.get("in_circle")),
                  jsonNames(event.get("validated")),
                  jsonNames(event.get("victims")),
                  event.get("push").asInt(),
                  jsonNames(event.get("pushed")));
      case "buff_hit" ->
          "%d buff_hit %s %d %d %d %s"
              .formatted(
                  tick,
                  event.get("target").asText(),
                  event.get("damage").asInt(),
                  event.get("hp").asInt(),
                  event.get("before").asInt(),
                  event.get("source").isNull() ? null : event.get("source").asText());
      case "combat_gate_drop" ->
          "%d combat_gate_drop %s %s %d"
              .formatted(
                  tick,
                  event.get("unit").asText(),
                  event.get("ref").asText(),
                  event.get("hit_speed").asInt());
      case "combat_component" ->
          "%d combat_component %s %d %d"
              .formatted(
                  tick,
                  event.get("unit").asText(),
                  event.get("on").asInt(),
                  event.get("hit_speed").asInt());
      default -> throw new IllegalStateException("unknown event " + kind);
    };
  }

  /**
   * Places the reference's unit on tick 0 and every further unit the reference lists on its own
   * tick, under its own name, at the reference's level, then schedules each row the reference lists
   * on its unit in the command pass of its tick, the unit as its cause, as a buff's starting action
   * or an ability's activation would.
   *
   * <p>A placement runs at the head of the step of its tick, so a unit placed on the reference's
   * tick {@code n} is first visited in battle step {@code n}, as in the reference.
   *
   * @return the reference's unit first, then the further units in the order the reference lists
   *     them
   */
  static List<CharacterEntity> deployAll(Standard1v1Battle match, JsonNode reference) {
    List<CharacterEntity> units = new ArrayList<>();
    units.add(
        match.deploy(
            0,
            unitData(reference.get("card").asText()),
            reference.get("level").asInt(),
            reference.get("side").asInt(),
            reference.get("deploy").get(0).asInt(),
            reference.get("deploy").get(1).asInt()));
    if (reference.has("units")) {
      for (JsonNode unit : reference.get("units")) {
        units.add(
            match.deploy(
                unit.get("tick").asInt(),
                unitData(unit.get("card").asText()),
                // A further unit may be placed at a level of its own.
                unit.path("level").asInt(reference.get("level").asInt()),
                unit.get("side").asInt(),
                unit.get("deploy").get(0).asInt(),
                unit.get("deploy").get(1).asInt(),
                unit.get("name").asText()));
      }
    }
    for (JsonNode schedule : reference.path("unit_schedules")) {
      String name = schedule.get("unit").asText();
      CharacterEntity unit =
          units.stream().filter(u -> u.name().equals(name)).findFirst().orElseThrow();
      match.scheduleAction(
          schedule.get("tick").asInt(),
          unit,
          GameData.actions()
              .build(schedule.get("action").asText(), match.getWorld().binding(unit)));
    }
    return units;
  }

  private static UnitData unitData(String cardName) {
    return GameData.unit(cardName);
  }

  /**
   * Queues every card play of the reference on its tick; a Mirror is played as the Mirror, and a
   * variant card as the variant.
   */
  static void playAll(Standard1v1Battle match, JsonNode reference) {
    for (JsonNode command : reference.get("commands")) {
      // An ability command names a unit a play made.
      if (command.has("ability")) {
        match.useAbility(
            command.get("tick").asInt(),
            command.get("side").asInt(),
            command.get("ability").asText(),
            command.get("name").asText());
        continue;
      }
      String name = command.get("card").asText();
      if (GameData.records().matchCard(name).variant() != null) {
        match.playVariant(
            command.get("tick").asInt(),
            name,
            reference.get("level").asInt(),
            command.get("side").asInt(),
            command.get("point").get(0).asInt(),
            command.get("point").get(1).asInt(),
            command.get("name").asText());
        continue;
      }
      if (GameData.records().matchCard(name).mirror()) {
        match.playMirror(
            command.get("tick").asInt(),
            name,
            reference.get("level").asInt(),
            command.get("side").asInt(),
            command.get("point").get(0).asInt(),
            command.get("point").get(1).asInt(),
            command.get("name").asText());
        continue;
      }
      DeployCard card = GameData.card(name);
      match.play(
          command.get("tick").asInt(),
          card,
          reference.get("level").asInt(),
          command.get("side").asInt(),
          command.get("point").get(0).asInt(),
          command.get("point").get(1).asInt(),
          command.get("name").asText());
    }
  }
}
