package org.crforge.bridge.observation;

import org.crforge.bridge.dto.RewardDTO;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.EntityType;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.player.Elixir;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;

/**
 * Computes per-step rewards for RL training.
 *
 * <p>Reward sources:
 *
 * <ul>
 *   <li>Tower damage dealt: +0.005 per HP of damage dealt to enemy towers
 *   <li>Tower damage taken: -0.008 per HP of damage taken on own towers (asymmetric -- defense
 *       weighted higher)
 *   <li>Crown earned: +1.0 per crown
 *   <li>Game win: +5.0
 *   <li>Game loss: -5.0
 *   <li>Elixir waste penalty: -0.005 when elixir is capped at 10
 *   <li>Time penalty: -0.0001 per step (discourages passive play)
 *   <li>Unit kill: +0.05 per enemy non-tower entity killed
 *   <li>Unit lost: -0.07 per own non-tower entity killed (asymmetric -- defense weighted higher)
 *   <li>Unit HP damage dealt: +0.001 per HP of damage dealt to enemy units
 *   <li>Unit HP damage taken: -0.0015 per HP of damage taken on own units (asymmetric)
 * </ul>
 *
 * <p>Rewards are computed as deltas between steps. Both players use the same reward function but
 * offense and defense are weighted asymmetrically: taking damage hurts more than dealing damage
 * helps. This encourages the agent to defend before pushing.
 */
public class RewardCalculator {

  private static final float TOWER_DAMAGE_DEALT_REWARD = 0.005f;
  private static final float TOWER_DAMAGE_TAKEN_PENALTY = 0.008f;
  private static final float CROWN_REWARD = 1.0f;
  private static final float WIN_REWARD = 5.0f;
  private static final float LOSS_REWARD = -5.0f;
  private static final float ELIXIR_WASTE_PENALTY = -0.005f;
  private static final float TIME_PENALTY = -0.0001f;
  private static final float UNIT_KILL_REWARD = 0.05f;
  private static final float UNIT_LOST_PENALTY = 0.07f;
  private static final float UNIT_DAMAGE_DEALT_REWARD = 0.001f;
  private static final float UNIT_DAMAGE_TAKEN_PENALTY = 0.0015f;

  // Snapshots from previous step
  private int prevBlueTowerHp;
  private int prevRedTowerHp;
  private int prevBlueCrowns;
  private int prevRedCrowns;

  // B1: Entity tracking for kill and damage rewards
  private int prevBlueEntityCount;
  private int prevRedEntityCount;
  private int prevBlueEntityHp;
  private int prevRedEntityHp;

  // Player references for elixir checking
  private Player bluePlayer;
  private Player redPlayer;

  /**
   * Initializes the reward calculator by capturing the current tower HP and crown state. Call this
   * once after match initialization, before the first step.
   */
  public void reset(GameState state) {
    reset(state, null, null);
  }

  /** Initializes the reward calculator with player references for elixir-based rewards. */
  public void reset(GameState state, Player bluePlayer, Player redPlayer) {
    prevBlueTowerHp = getTotalTowerHp(state, Team.BLUE);
    prevRedTowerHp = getTotalTowerHp(state, Team.RED);
    prevBlueCrowns = state.getCrownCount(Team.BLUE);
    prevRedCrowns = state.getCrownCount(Team.RED);
    this.bluePlayer = bluePlayer;
    this.redPlayer = redPlayer;

    // B1: Initialize entity tracking
    prevBlueEntityCount = countNonTowerEntities(state, Team.BLUE);
    prevRedEntityCount = countNonTowerEntities(state, Team.RED);
    prevBlueEntityHp = getTotalNonTowerEntityHp(state, Team.BLUE);
    prevRedEntityHp = getTotalNonTowerEntityHp(state, Team.RED);
  }

  /** Computes the reward delta for this step. */
  public RewardDTO computeReward(GameState state) {
    int currentBlueTowerHp = getTotalTowerHp(state, Team.BLUE);
    int currentRedTowerHp = getTotalTowerHp(state, Team.RED);
    int currentBlueCrowns = state.getCrownCount(Team.BLUE);
    int currentRedCrowns = state.getCrownCount(Team.RED);

    float blueReward = 0f;
    float redReward = 0f;

    // Tower damage rewards (asymmetric: taking damage penalized more than dealing is rewarded)
    int blueDamageToRed = Math.max(0, prevRedTowerHp - currentRedTowerHp);
    int redDamageToBlue = Math.max(0, prevBlueTowerHp - currentBlueTowerHp);

    blueReward += blueDamageToRed * TOWER_DAMAGE_DEALT_REWARD;
    blueReward -= redDamageToBlue * TOWER_DAMAGE_TAKEN_PENALTY;

    redReward += redDamageToBlue * TOWER_DAMAGE_DEALT_REWARD;
    redReward -= blueDamageToRed * TOWER_DAMAGE_TAKEN_PENALTY;

    // Crown rewards
    int blueCrownsEarned = currentBlueCrowns - prevBlueCrowns;
    int redCrownsEarned = currentRedCrowns - prevRedCrowns;

    blueReward += blueCrownsEarned * CROWN_REWARD;
    blueReward -= redCrownsEarned * CROWN_REWARD;

    redReward += redCrownsEarned * CROWN_REWARD;
    redReward -= blueCrownsEarned * CROWN_REWARD;

    // Unit kill rewards (asymmetric: losing own units penalized more than killing enemy units)
    int currentBlueEntityCount = countNonTowerEntities(state, Team.BLUE);
    int currentRedEntityCount = countNonTowerEntities(state, Team.RED);

    int blueUnitsKilled = Math.max(0, prevRedEntityCount - currentRedEntityCount);
    int redUnitsKilled = Math.max(0, prevBlueEntityCount - currentBlueEntityCount);

    blueReward += blueUnitsKilled * UNIT_KILL_REWARD;
    blueReward -= redUnitsKilled * UNIT_LOST_PENALTY;

    redReward += redUnitsKilled * UNIT_KILL_REWARD;
    redReward -= blueUnitsKilled * UNIT_LOST_PENALTY;

    // Unit HP damage rewards (asymmetric: taking damage penalized more than dealing)
    int currentBlueEntityHp = getTotalNonTowerEntityHp(state, Team.BLUE);
    int currentRedEntityHp = getTotalNonTowerEntityHp(state, Team.RED);

    int blueDamageToRedUnits = Math.max(0, prevRedEntityHp - currentRedEntityHp);
    int redDamageToBlueUnits = Math.max(0, prevBlueEntityHp - currentBlueEntityHp);

    blueReward += blueDamageToRedUnits * UNIT_DAMAGE_DEALT_REWARD;
    blueReward -= redDamageToBlueUnits * UNIT_DAMAGE_TAKEN_PENALTY;

    redReward += redDamageToBlueUnits * UNIT_DAMAGE_DEALT_REWARD;
    redReward -= blueDamageToRedUnits * UNIT_DAMAGE_TAKEN_PENALTY;

    // Elixir waste penalty: penalize capping at max elixir
    if (bluePlayer != null && bluePlayer.getElixir().getCurrent() >= Elixir.MAX_ELIXIR) {
      blueReward += ELIXIR_WASTE_PENALTY;
    }
    if (redPlayer != null && redPlayer.getElixir().getCurrent() >= Elixir.MAX_ELIXIR) {
      redReward += ELIXIR_WASTE_PENALTY;
    }

    // Time penalty: small negative reward per step to discourage passive play
    blueReward += TIME_PENALTY;
    redReward += TIME_PENALTY;

    // Win/loss rewards
    if (state.isGameOver()) {
      Team winner = state.getWinner();
      if (winner == Team.BLUE) {
        blueReward += WIN_REWARD;
        redReward += LOSS_REWARD;
      } else if (winner == Team.RED) {
        redReward += WIN_REWARD;
        blueReward += LOSS_REWARD;
      }
      // Draw: no win/loss bonus
    }

    // Update snapshots
    prevBlueTowerHp = currentBlueTowerHp;
    prevRedTowerHp = currentRedTowerHp;
    prevBlueCrowns = currentBlueCrowns;
    prevRedCrowns = currentRedCrowns;
    prevBlueEntityCount = currentBlueEntityCount;
    prevRedEntityCount = currentRedEntityCount;
    prevBlueEntityHp = currentBlueEntityHp;
    prevRedEntityHp = currentRedEntityHp;

    return new RewardDTO(blueReward, redReward);
  }

  private int getTotalTowerHp(GameState state, Team team) {
    int total = 0;
    for (Tower tower : state.getTowers().get(team)) {
      if (tower.isAlive()) {
        total += tower.getHealth().getCurrent();
      }
    }
    return total;
  }

  /** Counts alive non-tower entities for a team (troops + buildings). */
  private int countNonTowerEntities(GameState state, Team team) {
    int count = 0;
    for (Entity entity : state.getAliveEntities()) {
      if (entity.getTeam() == team && entity.getEntityType() != EntityType.TOWER) {
        count++;
      }
    }
    return count;
  }

  /** Sums HP of alive non-tower entities for a team. */
  private int getTotalNonTowerEntityHp(GameState state, Team team) {
    int total = 0;
    for (Entity entity : state.getAliveEntities()) {
      if (entity.getTeam() == team && entity.getEntityType() != EntityType.TOWER) {
        total += entity.getHealth().getCurrent();
      }
    }
    return total;
  }
}
