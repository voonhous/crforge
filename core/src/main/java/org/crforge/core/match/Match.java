package org.crforge.core.match;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.crforge.core.arena.Arena;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;

/**
 * Represents an active match with its configuration, players, and mode-specific rules.
 * <p>
 * This is the base class for all match types. Extend this class to create different game modes
 * (2v2, Triple Elixir, Draft, etc.) with different:
 * <ul>
 * <li>Arena layouts (dimensions, tower positions)</li>
 * <li>Player configurations (1v1, 2v2, shared/separate elixir)</li>
 * <li>Timing rules (match duration, overtime duration, elixir rates)</li>
 * <li>Win conditions</li>
 * </ul>
 *
 * @see Standard1v1Match
 */
@Getter
public abstract class Match {

  protected final Arena arena;
  protected final List<Player> bluePlayers;
  protected final List<Player> redPlayers;

  protected boolean overtime;
  @Setter
  protected Team winner;

  protected Match(Arena arena) {
    this.arena = arena;
    this.bluePlayers = new ArrayList<>();
    this.redPlayers = new ArrayList<>();
    this.overtime = false;
    this.winner = null;
  }

  /**
   * Adds a player to the match.
   *
   * @throws IllegalArgumentException if team is full
   * @throws IllegalArgumentException if player's team doesn't match
   */
  public void addPlayer(Player player) {
    List<Player> teamList = getPlayersForTeam(player.getTeam());
    if (teamList.size() >= getMaxPlayersPerTeam()) {
      throw new IllegalArgumentException(
          "Team " + player.getTeam() + " is full (max " + getMaxPlayersPerTeam() + ")");
    }
    teamList.add(player);
  }

  /**
   * Returns all players on a team.
   */
  public List<Player> getPlayers(Team team) {
    return Collections.unmodifiableList(getPlayersForTeam(team));
  }

  /**
   * Returns all players in the match.
   */
  public List<Player> getAllPlayers() {
    List<Player> all = new ArrayList<>(bluePlayers);
    all.addAll(redPlayers);
    return Collections.unmodifiableList(all);
  }

  private List<Player> getPlayersForTeam(Team team) {
    return team == Team.BLUE ? bluePlayers : redPlayers;
  }

  /**
   * Updates all players (elixir regen, etc.).
   */
  public void update(float deltaTime) {
    for (Player player : bluePlayers) {
      player.update(deltaTime);
    }
    for (Player player : redPlayers) {
      player.update(deltaTime);
    }
  }

  /**
   * Called when match enters overtime. Updates elixir rates for all players.
   */
  public void enterOvertime() {
    if (overtime) {
      return;
    }
    overtime = true;
    for (Player player : getAllPlayers()) {
      player.setOvertime(true);
    }
  }

  /**
   * Checks if the match has ended (has a winner or draw).
   */
  public boolean isEnded() {
    return winner != null;
  }

  /**
   * Validates a player action. Override to add mode-specific validation (e.g., placement zones,
   * shared elixir in 2v2).
   *
   * @return true if the action is valid for this match type
   */
  public boolean validateAction(Player player, PlayerActionDTO action) {
    if (!action.isValid()) {
      return false;
    }

    // Check bounds
    if (!arena.isInBounds(action.getX(), action.getY())) {
      return false;
    }

    // Get the card to check its type
    Card card = player.getHand().getCard(action.getHandIndex());
    if (card == null) {
      return false;
    }

    // Spells can be placed anywhere in the arena
    if (card.getType() == CardType.SPELL) {
      return true;
    }

    // Buildings must follow strict placement rules (entire footprint in zone)
    if (card.getType() == CardType.BUILDING) {
      float radius = 0.5f; // Default small radius
      if (card.getTroops() != null && !card.getTroops().isEmpty()) {
        radius = card.getTroops().get(0).getCollisionRadius();
      }
      return arena.isValidBuildingPlacement(action.getX(), action.getY(), radius, player.getTeam());
    }

    // Troops check center point (legacy/standard behavior for now)
    return arena.isValidPlacement(action.getX(), action.getY(), player.getTeam());
  }

  // --- Abstract methods for subclasses to implement ---

  /**
   * Returns the maximum number of players per team for this mode.
   */
  public abstract int getMaxPlayersPerTeam();

  /**
   * Returns the match duration in ticks (before overtime).
   */
  public abstract int getMatchDurationTicks();

  /**
   * Returns the overtime duration in ticks.
   */
  public abstract int getOvertimeDurationTicks();

  /**
   * Returns the game mode identifier.
   */
  public abstract GameMode getGameMode();
}
