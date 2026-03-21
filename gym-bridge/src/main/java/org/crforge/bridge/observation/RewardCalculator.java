package org.crforge.bridge.observation;

import org.crforge.bridge.dto.RewardDTO;
import org.crforge.core.engine.GameState;
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
 *   <li>Tower damage: +0.001 per HP of damage dealt to enemy towers (light gradient signal)
 *   <li>Crown earned: +10.0 per crown (tower destruction is the primary milestone)
 *   <li>Game win: +30.0
 *   <li>Game loss: -30.0
 *   <li>Game draw: -2.0 (draws are always net negative)
 *   <li>Elixir waste penalty: -0.02 when elixir is capped at 10
 *   <li>Time penalty: -0.001 per step (discourages passive play)
 * </ul>
 *
 * <p>Rewards are computed as deltas between steps. Symmetric for both players (blue's reward for
 * damaging red = red's penalty).
 */
public class RewardCalculator {

  private static final float TOWER_DAMAGE_REWARD = 0.005f;
  private static final float CROWN_REWARD = 10.0f;
  private static final float WIN_REWARD = 30.0f;
  private static final float LOSS_REWARD = -30.0f;
  private static final float DRAW_PENALTY = -10.0f;
  private static final float ELIXIR_WASTE_PENALTY = -0.02f;
  private static final float TIME_PENALTY = -0.001f;

  // Snapshots from previous step
  private int prevBlueTowerHp;
  private int prevRedTowerHp;
  private int prevBlueCrowns;
  private int prevRedCrowns;

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
  }

  /** Computes the reward delta for this step. */
  public RewardDTO computeReward(GameState state) {
    int currentBlueTowerHp = getTotalTowerHp(state, Team.BLUE);
    int currentRedTowerHp = getTotalTowerHp(state, Team.RED);
    int currentBlueCrowns = state.getCrownCount(Team.BLUE);
    int currentRedCrowns = state.getCrownCount(Team.RED);

    float blueReward = 0f;
    float redReward = 0f;

    // Tower damage rewards: damage to enemy towers = positive reward
    int blueDamageToRed = Math.max(0, prevRedTowerHp - currentRedTowerHp);
    int redDamageToBlue = Math.max(0, prevBlueTowerHp - currentBlueTowerHp);

    blueReward += blueDamageToRed * TOWER_DAMAGE_REWARD;
    blueReward -= redDamageToBlue * TOWER_DAMAGE_REWARD;

    redReward += redDamageToBlue * TOWER_DAMAGE_REWARD;
    redReward -= blueDamageToRed * TOWER_DAMAGE_REWARD;

    // Crown rewards
    int blueCrownsEarned = currentBlueCrowns - prevBlueCrowns;
    int redCrownsEarned = currentRedCrowns - prevRedCrowns;

    blueReward += blueCrownsEarned * CROWN_REWARD;
    blueReward -= redCrownsEarned * CROWN_REWARD;

    redReward += redCrownsEarned * CROWN_REWARD;
    redReward -= blueCrownsEarned * CROWN_REWARD;

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

    // Win/loss/draw rewards
    if (state.isGameOver()) {
      Team winner = state.getWinner();
      if (winner == Team.BLUE) {
        blueReward += WIN_REWARD;
        redReward += LOSS_REWARD;
      } else if (winner == Team.RED) {
        redReward += WIN_REWARD;
        blueReward += LOSS_REWARD;
      } else {
        // Draw: penalize both players to discourage passive play
        blueReward += DRAW_PENALTY;
        redReward += DRAW_PENALTY;
      }
    }

    // Update snapshots
    prevBlueTowerHp = currentBlueTowerHp;
    prevRedTowerHp = currentRedTowerHp;
    prevBlueCrowns = currentBlueCrowns;
    prevRedCrowns = currentRedCrowns;

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
}
