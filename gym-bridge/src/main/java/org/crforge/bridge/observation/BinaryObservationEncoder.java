package org.crforge.bridge.observation;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.component.Combat;
import org.crforge.core.effect.AppliedEffect;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.EntityType;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.player.Hand;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.data.card.CardRegistry;

/**
 * Encodes observations as a flat float32 array sent as raw bytes. Pre-allocates buffers and writes
 * directly from entity/player/state fields with no DTO allocations.
 *
 * <p>Float layout (1079 values):
 *
 * <pre>
 * Offset  Count  Field
 * 0       1      frame
 * 1       1      game_time
 * 2       1      is_overtime
 * 3       2      elixir (blue, red)
 * 5       2      crowns (blue, red)
 * 7       4      hand_costs (normalized /10)
 * 11      4      hand_types (0/1/2)
 * 15      4      hand_card_ids
 * 19      1      next_card_cost
 * 20      1      next_card_type
 * 21      1      next_card_id
 * 22      24     towers (6 x [hp_frac, x_norm, y_norm, alive])
 * 46      1024   entities (64 x 16 features, zero-padded)
 * 1070    1      num_entities
 * 1071    8      lane_summary
 * </pre>
 */
public class BinaryObservationEncoder {

  // Arena dimensions for normalization
  private static final float ARENA_WIDTH = 18f;
  private static final float ARENA_HEIGHT = 32f;

  // Max entities in observation
  private static final int MAX_ENTITIES = 64;
  private static final int ENTITY_FEATURES = 16;

  // Total floats in the observation
  public static final int OBS_FLOATS = 1079;
  public static final int OBS_BYTES = OBS_FLOATS * 4;

  // Lane summary normalization
  private static final float MAX_LANE_HP = 5000f;
  private static final float LANE_SPLIT = 9f;

  // Step result header: 2 floats (rewards) + 4 bytes (flags)
  public static final int STEP_HEADER_BYTES = 12;
  public static final int STEP_RESULT_BYTES = STEP_HEADER_BYTES + OBS_BYTES;

  // Type mappings matching Python env.py
  private static final float CARD_TYPE_TROOP = 0f;
  private static final float CARD_TYPE_SPELL = 1f;
  private static final float CARD_TYPE_BUILDING = 2f;

  private static final float ENTITY_TYPE_TROOP = 0f;
  private static final float ENTITY_TYPE_BUILDING = 1f;
  private static final float ENTITY_TYPE_TOWER = 2f;

  private static final float MOVEMENT_GROUND = 0f;
  private static final float MOVEMENT_AIR = 1f;
  private static final float MOVEMENT_BUILDING = 2f;

  private static final float TEAM_BLUE = 0f;
  private static final float TEAM_RED = 1f;

  // Pre-allocated buffers, reused across encode() calls
  private final float[] obs;
  private final ByteBuffer obsBuf;
  private final ByteBuffer stepBuf;

  public BinaryObservationEncoder() {
    this.obs = new float[OBS_FLOATS];
    this.obsBuf = ByteBuffer.allocate(OBS_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    this.stepBuf = ByteBuffer.allocate(STEP_RESULT_BYTES).order(ByteOrder.LITTLE_ENDIAN);
  }

  /**
   * Encodes the current game state into a flat float32 byte array (observation only, for reset
   * responses).
   */
  public byte[] encodeObservation(GameEngine engine, Player bluePlayer, Player redPlayer) {
    fillObsBuffer(engine, bluePlayer, redPlayer);
    obsBuf.clear();
    for (int i = 0; i < OBS_FLOATS; i++) {
      obsBuf.putFloat(obs[i]);
    }
    return obsBuf.array().clone();
  }

  /**
   * Encodes a full step result: rewards, flags, and observation into a single byte array.
   *
   * <p>Layout (4328 bytes):
   *
   * <pre>
   * Offset  Type       Field
   * 0       float32    blue_reward
   * 4       float32    red_reward
   * 8       byte       terminated (0/1)
   * 9       byte       truncated (0/1)
   * 10      byte       blueActionFailed (0/1)
   * 11      byte       redActionFailed (0/1)
   * 12      float32[]  observation (1079 floats)
   * </pre>
   */
  public byte[] encodeStepResult(
      GameEngine engine,
      Player bluePlayer,
      Player redPlayer,
      float blueReward,
      float redReward,
      boolean terminated,
      boolean truncated,
      boolean blueActionFailed,
      boolean redActionFailed) {
    fillObsBuffer(engine, bluePlayer, redPlayer);

    stepBuf.clear();
    stepBuf.putFloat(blueReward);
    stepBuf.putFloat(redReward);
    stepBuf.put((byte) (terminated ? 1 : 0));
    stepBuf.put((byte) (truncated ? 1 : 0));
    stepBuf.put((byte) (blueActionFailed ? 1 : 0));
    stepBuf.put((byte) (redActionFailed ? 1 : 0));

    for (int i = 0; i < OBS_FLOATS; i++) {
      stepBuf.putFloat(obs[i]);
    }
    return stepBuf.array().clone();
  }

  /**
   * Fills the internal float[] from current game state and returns it directly. Intended for
   * in-process callers (JPype) where no byte serialization is needed -- the caller copies via
   * np.array().
   */
  public float[] fillAndGetObsBuffer(GameEngine engine, Player bluePlayer, Player redPlayer) {
    fillObsBuffer(engine, bluePlayer, redPlayer);
    return obs;
  }

  /** Fills the obs float buffer from current game state. Reuses the pre-allocated float array. */
  private void fillObsBuffer(GameEngine engine, Player bluePlayer, Player redPlayer) {
    // Zero out previous values
    java.util.Arrays.fill(obs, 0f);

    GameState state = engine.getGameState();
    int idx = 0;

    // Global state
    obs[idx++] = state.getFrameCount(); // 0: frame
    obs[idx++] = state.getGameTimeSeconds(); // 1: game_time
    obs[idx++] = engine.isOvertime() ? 1f : 0f; // 2: is_overtime

    // Elixir
    obs[idx++] = bluePlayer.getElixir().getCurrent(); // 3: blue elixir
    obs[idx++] = redPlayer.getElixir().getCurrent(); // 4: red elixir

    // Crowns
    obs[idx++] = state.getCrownCount(Team.BLUE); // 5: blue crowns
    obs[idx++] = state.getCrownCount(Team.RED); // 6: red crowns

    // Hand costs (normalized /10)
    Hand hand = bluePlayer.getHand();
    for (int i = 0; i < Hand.HAND_SIZE; i++) {
      Card card = hand.getCard(i);
      if (card != null) {
        obs[idx++] = card.getCost() / 10f; // 7-10: hand costs
      } else {
        obs[idx++] = 0f;
      }
    }

    // Hand types
    for (int i = 0; i < Hand.HAND_SIZE; i++) {
      Card card = hand.getCard(i);
      if (card != null) {
        obs[idx++] = cardTypeToFloat(card.getType()); // 11-14: hand types
      } else {
        obs[idx++] = 0f;
      }
    }

    // Hand card IDs
    for (int i = 0; i < Hand.HAND_SIZE; i++) {
      Card card = hand.getCard(i);
      if (card != null) {
        obs[idx++] = CardRegistry.getIndex(card.getId()); // 15-18: hand card IDs
      } else {
        obs[idx++] = -1f;
      }
    }

    // Next card
    Card nextCard = hand.getNextCard();
    if (nextCard != null) {
      obs[idx++] = nextCard.getCost() / 10f; // 19: next card cost
      obs[idx++] = cardTypeToFloat(nextCard.getType()); // 20: next card type
      obs[idx++] = CardRegistry.getIndex(nextCard.getId()); // 21: next card id
    } else {
      obs[idx++] = 0f;
      obs[idx++] = 0f;
      obs[idx++] = -1f;
    }

    // Towers: 6 towers x [hp_frac, x_norm, y_norm, alive]
    // Order: blue towers (up to 3), then red towers (up to 3)
    idx = encodeTowers(state, Team.BLUE, idx); // 22-33
    idx = encodeTowers(state, Team.RED, idx); // 34-45

    // Entities
    int entitiesOffset = 46;
    idx = entitiesOffset;
    java.util.List<Entity> alive = state.getAliveEntities();
    int numEntities = Math.min(alive.size(), MAX_ENTITIES);

    // Lane summary accumulators
    float leftEnemyHp = 0f;
    float rightEnemyHp = 0f;
    float leftFriendlyHp = 0f;
    float rightFriendlyHp = 0f;
    float leftEnemyFrontY = ARENA_HEIGHT;
    float rightEnemyFrontY = ARENA_HEIGHT;
    int friendlyCount = 0;
    int enemyCount = 0;

    for (int i = 0; i < numEntities; i++) {
      Entity e = alive.get(i);
      int base = entitiesOffset + i * ENTITY_FEATURES;

      // Team
      obs[base] = e.getTeam() == Team.BLUE ? TEAM_BLUE : TEAM_RED;

      // Entity type
      obs[base + 1] = entityTypeToFloat(e.getEntityType());

      // Movement type
      obs[base + 2] = movementTypeToFloat(e.getMovementType());

      // Position normalized
      float ex = e.getPosition().getX();
      float ey = e.getPosition().getY();
      obs[base + 3] = ex / ARENA_WIDTH;
      obs[base + 4] = ey / ARENA_HEIGHT;

      // HP fraction
      int maxHp = e.getHealth().getMax();
      if (maxHp == 0) {
        maxHp = 1;
      }
      obs[base + 5] = (float) e.getHealth().getCurrent() / maxHp;
      obs[base + 6] = (float) e.getHealth().getShield() / maxHp;

      // Combat state
      Combat combat = e.getCombat();
      if (combat != null) {
        float cooldownFraction;
        if (combat.getAttackCooldown() > 0) {
          float remaining = Math.max(0, combat.getCurrentCooldown());
          cooldownFraction = 1f - (remaining / combat.getAttackCooldown());
          cooldownFraction = Math.max(0f, Math.min(1f, cooldownFraction));
        } else {
          cooldownFraction = 1f;
        }
        obs[base + 7] = cooldownFraction;
        obs[base + 8] = combat.isAttacking() ? 1f : 0f;
        obs[base + 9] = combat.hasTarget() ? 1f : 0f;
      }

      // Status effects
      for (AppliedEffect effect : e.getAppliedEffects()) {
        switch (effect.getType()) {
          case STUN -> obs[base + 10] = 1f;
          case SLOW -> obs[base + 11] = 1f;
          case RAGE -> obs[base + 12] = 1f;
          case FREEZE -> obs[base + 13] = 1f;
          case POISON -> obs[base + 14] = 1f;
          default -> {}
        }
      }

      // Building lifetime
      if (e instanceof Building building && building.hasLifetime()) {
        float lifetime = building.getLifetime();
        if (lifetime > 0) {
          float frac = building.getRemainingLifetime() / lifetime;
          obs[base + 15] = Math.max(0f, Math.min(1f, frac));
        }
      }

      // Lane summary accumulation (skip towers)
      if (e.getEntityType() != EntityType.TOWER) {
        int hp = e.getHealth().getCurrent();
        boolean isLeft = ex < LANE_SPLIT;

        if (e.getTeam() == Team.RED) {
          enemyCount++;
          if (isLeft) {
            leftEnemyHp += hp;
            leftEnemyFrontY = Math.min(leftEnemyFrontY, ey);
          } else {
            rightEnemyHp += hp;
            rightEnemyFrontY = Math.min(rightEnemyFrontY, ey);
          }
        } else {
          friendlyCount++;
          if (isLeft) {
            leftFriendlyHp += hp;
          } else {
            rightFriendlyHp += hp;
          }
        }
      }
    }

    // Also accumulate lane summary for entities beyond MAX_ENTITIES (they're still in the game)
    for (int i = numEntities; i < alive.size(); i++) {
      Entity e = alive.get(i);
      if (e.getEntityType() != EntityType.TOWER) {
        float ex = e.getPosition().getX();
        float ey = e.getPosition().getY();
        int hp = e.getHealth().getCurrent();
        boolean isLeft = ex < LANE_SPLIT;

        if (e.getTeam() == Team.RED) {
          enemyCount++;
          if (isLeft) {
            leftEnemyHp += hp;
            leftEnemyFrontY = Math.min(leftEnemyFrontY, ey);
          } else {
            rightEnemyHp += hp;
            rightEnemyFrontY = Math.min(rightEnemyFrontY, ey);
          }
        } else {
          friendlyCount++;
          if (isLeft) {
            leftFriendlyHp += hp;
          } else {
            rightFriendlyHp += hp;
          }
        }
      }
    }

    // num_entities at offset 1070
    obs[entitiesOffset + MAX_ENTITIES * ENTITY_FEATURES] = numEntities;

    // Lane summary at offset 1071
    int laneIdx = entitiesOffset + MAX_ENTITIES * ENTITY_FEATURES + 1;
    float blueElixir = bluePlayer.getElixir().getCurrent();
    float redElixir = redPlayer.getElixir().getCurrent();

    obs[laneIdx] = Math.min(leftEnemyHp / MAX_LANE_HP, 1f);
    obs[laneIdx + 1] = Math.min(rightEnemyHp / MAX_LANE_HP, 1f);
    obs[laneIdx + 2] = 1f - leftEnemyFrontY / ARENA_HEIGHT;
    obs[laneIdx + 3] = 1f - rightEnemyFrontY / ARENA_HEIGHT;
    obs[laneIdx + 4] = Math.min(leftFriendlyHp / MAX_LANE_HP, 1f);
    obs[laneIdx + 5] = Math.min(rightFriendlyHp / MAX_LANE_HP, 1f);
    obs[laneIdx + 6] = (blueElixir - redElixir) / 10f;
    obs[laneIdx + 7] = (float) (friendlyCount - enemyCount) / MAX_ENTITIES;
  }

  /** Encodes up to 3 towers for a team into the obs buffer. Returns the updated index. */
  private int encodeTowers(GameState state, Team team, int idx) {
    java.util.List<Tower> towerList = state.getTowers().get(team);
    int count = Math.min(towerList.size(), 3);
    for (int i = 0; i < count; i++) {
      Tower tower = towerList.get(i);
      int maxHp = tower.getHealth().getMax();
      if (maxHp == 0) {
        maxHp = 1;
      }
      obs[idx++] = (float) tower.getHealth().getCurrent() / maxHp;
      obs[idx++] = tower.getPosition().getX() / ARENA_WIDTH;
      obs[idx++] = tower.getPosition().getY() / ARENA_HEIGHT;
      obs[idx++] = tower.isAlive() ? 1f : 0f;
    }
    // Zero-pad if fewer than 3 towers
    for (int i = count; i < 3; i++) {
      obs[idx++] = 0f;
      obs[idx++] = 0f;
      obs[idx++] = 0f;
      obs[idx++] = 0f;
    }
    return idx;
  }

  private static float cardTypeToFloat(CardType type) {
    return switch (type) {
      case TROOP, HERO -> CARD_TYPE_TROOP;
      case SPELL -> CARD_TYPE_SPELL;
      case BUILDING -> CARD_TYPE_BUILDING;
    };
  }

  private static float entityTypeToFloat(EntityType type) {
    return switch (type) {
      case TROOP -> ENTITY_TYPE_TROOP;
      case BUILDING -> ENTITY_TYPE_BUILDING;
      case TOWER -> ENTITY_TYPE_TOWER;
      // Projectiles and spells are mapped to higher values, but shouldn't appear
      // in alive entities typically
      case PROJECTILE -> 3f;
      case SPELL -> 4f;
    };
  }

  private static float movementTypeToFloat(MovementType type) {
    return switch (type) {
      case GROUND -> MOVEMENT_GROUND;
      case AIR -> MOVEMENT_AIR;
      case BUILDING -> MOVEMENT_BUILDING;
    };
  }
}
