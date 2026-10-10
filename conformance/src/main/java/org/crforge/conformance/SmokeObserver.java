/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.BattleMode;
import org.crforge.core.battle.match.Hand;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.unit.AreaEffectEntity;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.pathfinding.combat.HitPoints;

/**
 * Reads one observation of the smoke schema from the production simulator's own objects, after a
 * completed step. Nothing is written back, and nothing is derived: a value the simulator does not
 * hold fails the run instead of being reported as zero.
 *
 * <p>The fields and where each is read:
 *
 * <ul>
 *   <li>{@code tick}: the battle's count of completed steps;
 *   <li>{@code rng}: the battle stream's state, as an unsigned 32-bit number;
 *   <li>{@code avatar_count}, {@code ended}, {@code winner}, {@code end_timer}: the match's player
 *       count, its ended flag, its winner as it holds it and its end timer in milliseconds;
 *   <li>{@code entities}: the holder's live list in its own order, each with its id, side and
 *       position in game units; an entity of the character kind also has its row's name, its state
 *       number and its hit points, maximum and shield, null when it has no hit points;
 *   <li>{@code sides}: side 0 then side 1, each with its elixir in ten-thousandths, its hand and
 *       queue as deck indices, and the hand's refill cooldown in milliseconds;
 *   <li>{@code stopped}, in a terminal-aware schema only: the battle mode's own stop predicate
 *       ({@link BattleMode#isOver()}), the question the battle asks before it runs a step. It is
 *       not the match's ended flag: a decided match keeps stepping through its end delay.
 * </ul>
 */
public final class SmokeObserver {

  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

  private SmokeObserver() {
    // Utility class
  }

  /**
   * One observation of the exact-horizon schema.
   *
   * @param standard the battle, played as a match
   */
  public static JsonNode observe(Standard1v1Battle standard) {
    return observe(standard, SmokeSchema.V1);
  }

  /**
   * One observation of the given schema.
   *
   * @param standard the battle, played as a match
   * @param schema the schema the run is made in
   */
  public static JsonNode observe(Standard1v1Battle standard, SmokeSchema schema) {
    Battle battle = standard.getBattle();
    LadderMatch match = standard.getMatch();
    if (match == null) {
      throw new IllegalStateException("the battle is not played as a match: no sides to observe");
    }
    ObjectNode observation = JSON.objectNode();
    observation.put("tick", battle.getTick());
    observation.put("rng", Integer.toUnsignedLong(standard.getWorld().getRandom().getState()));
    observation.put("avatar_count", match.playerCount());
    observation.put("ended", match.isEnded());
    observation.put("winner", match.getWinner());
    observation.put("end_timer", match.getEndTimerMs());
    ArrayNode entities = observation.putArray("entities");
    for (BattleEntity entity : battle.getHolder().entities()) {
      entities.add(entity(entity));
    }
    ArrayNode sides = observation.putArray("sides");
    for (int side = 0; side < match.playerCount(); side++) {
      sides.add(side(match.side(side), side));
    }
    if (schema.terminal()) {
      observation.put("stopped", stopped(standard));
    }
    return observation;
  }

  /**
   * Whether the battle has stopped: the predicate {@link Battle#step()} asks first, under which a
   * stopped battle runs no step at all.
   */
  static boolean stopped(Standard1v1Battle standard) {
    return standard.getBattle().getMode().isOver();
  }

  private static ObjectNode entity(BattleEntity entity) {
    ObjectNode item = JSON.objectNode();
    item.put("id", entity.getId());
    if (entity instanceof WorldEntity world) {
      position(item, world.side(), world.x(), world.y());
    } else if (entity instanceof ProjectileEntity projectile) {
      position(item, projectile.side(), projectile.x(), projectile.y());
    } else if (entity instanceof AreaEffectEntity area) {
      position(item, area.side(), area.x(), area.y());
    } else {
      throw new IllegalStateException(
          "no extraction for an entity of " + entity.getClass().getSimpleName());
    }
    if (entity.getKind() != BattleEntity.KIND_CHARACTER) {
      return item;
    }
    if (!(entity instanceof WorldEntity character)) {
      throw new IllegalStateException(
          "no extraction for a character of " + entity.getClass().getSimpleName());
    }
    item.put("row", character.getData().name());
    item.put("state", character.getView().getState());
    HitPoints hitPoints = character.getHitPoints();
    if (hitPoints == null) {
      item.putNull("hp");
      item.putNull("max_hp");
      item.putNull("shield");
    } else {
      item.put("hp", hitPoints.getHitPoints());
      item.put("max_hp", hitPoints.getMaximum());
      item.put("shield", hitPoints.getShield());
    }
    return item;
  }

  private static void position(ObjectNode item, int side, int x, int y) {
    item.put("side", side);
    item.put("x", x);
    item.put("y", y);
  }

  private static ObjectNode side(MatchSide matchSide, int side) {
    ObjectNode item = JSON.objectNode();
    item.put("side", side);
    item.put("elixir", matchSide.getElixir());
    Hand hand = matchSide.getHand();
    ArrayNode slots = item.putArray("hand");
    for (int slot : hand.slots()) {
      slots.add(slot);
    }
    ArrayNode queue = item.putArray("queue");
    for (int index : hand.queue()) {
      queue.add(index);
    }
    item.put("refill_ms", hand.getCooldownMs());
    return item;
  }
}
