/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.replay.ScenarioWriter;
import org.crforge.core.battle.unit.Standard1v1Battle;

/**
 * A faulted episode written to a folder of its own, so it can be replayed and read away from the
 * run that met it: the battle as a replay up to and including the step that faulted, and what the
 * environment knew of the fault.
 *
 * <ul>
 *   <li>{@value #REPLAY}: the episode's battle as a replay of the tables' data version ({@link
 *       ScenarioWriter}): its seed, decks, levels, towers and every command the seats gave, with
 *       the ticks they were given and run on. Read back ({@code ReplayScenario}, {@code
 *       ReplayBattle}) and stepped, it meets the same fault at the same tick; its {@code endTick}
 *       is the tick after the faulting step, so the debug visualizer's replay viewer opens it and
 *       stops there. Left out when the battle cannot be written, with the reason in {@code
 *       replay_refusal}.
 *   <li>{@value #FAULT}: the fault's kind, signature, exception, message and stack trace, the data
 *       version and content sha of the tables, the schema's version, the environment's
 *       configuration, its index in the vector, the episode's seed and decks, the tick and the
 *       decision it faulted at, both seats' actions of every decision of the episode, the faulting
 *       one last, and every command given to the battle.
 * </ul>
 *
 * <p>Bundles are written only when {@link EnvConfig#faultBundles} names a folder, and only on the
 * machine that runs the environment: they are never committed (a run's own data; the repository's
 * {@code .gitignore} lists {@code fault-bundles/} for a folder kept in a checkout).
 */
public final class FaultBundle {

  /** The replay's file in a bundle. */
  public static final String REPLAY = "replay.json";

  /** The fault's record in a bundle. */
  public static final String FAULT = "fault.json";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private FaultBundle() {
    // Utility class
  }

  /**
   * Writes a Ladder environment's faulted episode into a folder, made here.
   *
   * @param folder the bundle's folder, which must not exist yet
   * @param env the environment, its episode ended by the fault
   * @param index the environment's index in its vector
   * @param thrown the exception the vector's boundary caught
   */
  static void write(Path folder, LadderEnv env, int index, Throwable thrown) throws IOException {
    Files.createDirectories(folder.getParent());
    Files.createDirectory(folder);
    EnvFault fault = env.fault();
    GameTables tables = env.tables();
    EnvConfig config = env.config();
    Standard1v1Battle battle = env.battle();
    ObjectNode record = MAPPER.createObjectNode();
    record.put("kind", fault.kind().name().toLowerCase(Locale.ROOT));
    record.put("signature", fault.signature());
    record.put("exception", fault.exception());
    record.put("message", fault.message());
    StringWriter stack = new StringWriter();
    thrown.printStackTrace(new PrintWriter(stack));
    record.put("stack", stack.toString());
    record.put("data_version", tables.version());
    record.put("content_sha", tables.contentSha());
    record.put("schema_version", Schema.VERSION);
    ObjectNode configNode = record.putObject("config");
    configNode.put("decision_ticks", config.decisionTicks());
    configNode.put("level", config.level());
    configNode.put("tower_level", config.towerLevel());
    configNode.put("max_ticks", config.maxTicks());
    record.put("env_index", index);
    record.put("seed", env.seed());
    ArrayNode decks = record.putArray("decks");
    for (List<String> deck : env.decks()) {
      ArrayNode names = decks.addArray();
      deck.forEach(names::add);
    }
    ArrayNode slotFlags = record.putArray("slots");
    for (int[] side : env.slots()) {
      ArrayNode flags = slotFlags.addArray();
      for (int flag : side) {
        flags.add(flag);
      }
    }
    record.put("tick", fault.tick());
    record.put("episode_step", env.episodeStep());
    ArrayNode actions = record.putArray("actions");
    int[] log = env.actionLog();
    for (int i = 0; i + 1 < log.length; i += 2) {
      actions.addArray().add(log[i]).add(log[i + 1]);
    }
    ArrayNode commands = record.putArray("commands");
    if (battle != null) {
      for (Standard1v1Battle.Command command : battle.getCommands()) {
        ObjectNode node = commands.addObject();
        node.put("name", command.name());
        node.put("kind", command.kind().name().toLowerCase(Locale.ROOT));
        node.put("side", command.side());
        node.put("card", command.card());
        node.put("level", command.level());
        node.put("given", command.given());
        node.put("tick", command.tick());
        node.put("x", command.x());
        node.put("y", command.y());
        node.put("option", command.option());
        node.put("unit", command.unit());
        node.put("object_id", command.objectId());
      }
    }
    String refusal = null;
    if (battle == null) {
      refusal = "no battle was built before the fault";
    } else {
      try {
        ScenarioWriter writer = new ScenarioWriter(tables);
        ObjectNode replay = writer.write(writer.plan(battle, config.level()), fault.tick() + 1);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(folder.resolve(REPLAY).toFile(), replay);
      } catch (RuntimeException e) {
        refusal = e.getClass().getSimpleName() + ": " + e.getMessage();
      }
    }
    if (refusal == null) {
      record.put("replay", REPLAY);
    } else {
      record.putNull("replay");
      record.put("replay_refusal", refusal);
    }
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(folder.resolve(FAULT).toFile(), record);
  }
}
