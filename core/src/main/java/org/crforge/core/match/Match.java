package org.crforge.core.match;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.crforge.core.arena.Arena;
import org.crforge.core.arena.Tile;
import org.crforge.core.arena.TileType;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.EntityType;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;

/**
 * Represents an active match with its configuration, players, and mode-specific rules.
 *
 * <p>This is the base class for all match types. Extend this class to create different game modes
 * (2v2, Triple Elixir, Draft, etc.) with different:
 *
 * <ul>
 *   <li>Arena layouts (dimensions, tower positions)
 *   <li>Player configurations (1v1, 2v2, shared/separate elixir)
 *   <li>Timing rules (match duration, overtime duration, elixir rates)
 *   <li>Win conditions
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
  protected int elixirMultiplier = 1;
  @Setter protected boolean draw;
  @Setter protected Team winner;
  @Setter protected GameState gameState;

  protected Match(Arena arena) {
    this.arena = arena;
    this.bluePlayers = new ArrayList<>();
    this.redPlayers = new ArrayList<>();
    this.overtime = false;
    this.elixirMultiplier = 1;
    this.draw = false;
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

  /** Returns all players on a team. */
  public List<Player> getPlayers(Team team) {
    return Collections.unmodifiableList(getPlayersForTeam(team));
  }

  /** Returns all players in the match. */
  public List<Player> getAllPlayers() {
    List<Player> all = new ArrayList<>(bluePlayers);
    all.addAll(redPlayers);
    return Collections.unmodifiableList(all);
  }

  private List<Player> getPlayersForTeam(Team team) {
    return team == Team.BLUE ? bluePlayers : redPlayers;
  }

  /** Updates all players (elixir regen, etc.). */
  public void update(float deltaTime) {
    for (Player player : bluePlayers) {
      player.update(deltaTime);
    }
    for (Player player : redPlayers) {
      player.update(deltaTime);
    }
  }

  /** Called when match enters overtime. Sets double elixir for all players. */
  public void enterOvertime() {
    if (overtime) {
      return;
    }
    overtime = true;
    applyElixirMultiplier(2);
  }

  /** Called when match enters triple elixir phase (60s into overtime). */
  public void enterTripleElixir() {
    if (elixirMultiplier >= 3) {
      return;
    }
    applyElixirMultiplier(3);
  }

  private void applyElixirMultiplier(int multiplier) {
    this.elixirMultiplier = multiplier;
    for (Player player : getAllPlayers()) {
      player.setElixirMultiplier(multiplier);
    }
  }

  /**
   * Returns the offset in ticks from the start of overtime at which triple elixir activates.
   * Default is 0 (disabled). Subclasses override for mode-specific timing.
   */
  public int getTripleElixirOffsetTicks() {
    return 0;
  }

  /** Checks if the match has ended (has a winner or draw). */
  public boolean isEnded() {
    return winner != null || draw;
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

    // Resolve Mirror to the card it will copy for validation purposes
    if (card.isMirror()) {
      Card lastPlayed = player.getLastPlayedCard();
      if (lastPlayed == null || lastPlayed.isMirror()) {
        return false;
      }
      card = lastPlayed; // Validate placement against the mirrored card's rules
    }

    // Spells: spellAsDeploy spells (e.g. The Log) can only be deployed on the player's own side,
    // while other spells can be placed anywhere in the arena
    if (card.getType() == CardType.SPELL) {
      if (card.isSpellAsDeploy()) {
        return arena.isValidPlacement(action.getX(), action.getY(), player.getTeam());
      }
      if (!card.isCanPlaceOnBuildings()
          && gameState != null
          && isBuildingAtLocation(action.getX(), action.getY())) {
        return false;
      }
      return true;
    }

    // Cards that can deploy on enemy side (e.g. Miner, GoblinDrill) skip zone restriction
    if (card.isCanDeployOnEnemySide()) {
      Tile tile = arena.getTileAt(action.getX(), action.getY());
      return tile != null && tile.type() != TileType.BANNED && tile.type() != TileType.TOWER;
    }

    // Buildings must follow strict placement rules (entire footprint in zone)
    if (card.getType() == CardType.BUILDING) {
      float radius = 0.5f; // Default small radius
      if (card.getUnitStats() != null) {
        radius = card.getUnitStats().getCollisionRadius();
      }
      return arena.isValidBuildingPlacement(action.getX(), action.getY(), radius, player.getTeam());
    }

    // Troops check center point (legacy/standard behavior for now)
    return arena.isValidPlacement(action.getX(), action.getY(), player.getTeam());
  }

  /**
   * Returns true if any building or tower entity's collision area covers the given coordinates.
   * Used to enforce canPlaceOnBuildings=false spell placement restrictions.
   */
  private boolean isBuildingAtLocation(float x, float y) {
    for (Entity entity : gameState.getAliveEntities()) {
      EntityType type = entity.getEntityType();
      if (type != EntityType.BUILDING && type != EntityType.TOWER) {
        continue;
      }
      float dx = entity.getPosition().getX() - x;
      float dy = entity.getPosition().getY() - y;
      float r = entity.getCollisionRadius();
      if (dx * dx + dy * dy < r * r) {
        return true;
      }
    }
    return false;
  }

  // --- Abstract methods for subclasses to implement ---

  /** Returns the maximum number of players per team for this mode. */
  public abstract int getMaxPlayersPerTeam();

  /** Returns the match duration in ticks (before overtime). */
  public abstract int getMatchDurationTicks();

  /** Returns the overtime duration in ticks. */
  public abstract int getOvertimeDurationTicks();

  /** Returns the game mode identifier. */
  public abstract GameMode getGameMode();
}
