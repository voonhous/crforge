package org.crforge.core.battle.unit;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.combat.HitPoints;

/**
 * A player's ability command: the tap on a champion's button, which names one unit and runs in the
 * command pass of its tick, with the card plays, before the tick's entity tick.
 *
 * <p>Its gates, in order: the king alive (0x3ec); the unit found, by itself in the live list, else
 * the first character of the side with its row and play, and a slot of the king following it
 * (0x3ee); the slot's cooldown out (0x3ed) and a charge left (0x3f6); the unit's ability a
 * champion's (8); no ability pending on it (0x3f1); no clone (0xa); not disabled by its tag
 * (0x3f5); and the king's whole elixir covering the cost (0x41a). A frozen, stunned or deploying
 * champion passes: the standard game lets one use its ability. A refused command changes nothing.
 *
 * <p>A command that passes pays: the ability's cost, in whole elixir, leaves the king's elixir,
 * never more than it holds, and is counted as spent; then every run of the king, from the last to
 * the first, hears the paid ability, and the slot following the unit's row requests the ability of
 * each of its live copies. The cost is taken whether the request starts the cast or leaves it
 * pending, and whether or not a live copy is left.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the gates in order, the payment and the broadcast to the king's"
            + " runs; held by archer_queen_ability and archer_queen_ability_refused. Not reached:"
            + " the frozen gate, open in the standard game, the tutorial's, and the gates of an"
            + " avatar with no king or a sender other than its owner. Not modelled: the issuing"
            + " client's reserving pass, which only its own button reads.")
public final class AbilityCommand {

  /** The command passed and paid. */
  public static final int OK = 0;

  /** The unit is not a champion's: its ability is none or no champion's. */
  public static final int NOT_CHAMPION = 8;

  /** The unit is a clone. */
  public static final int CLONE = 0xa;

  /** The king is dead. */
  public static final int KING_GONE = 0x3ec;

  /** The slot's cooldown runs. */
  public static final int COOLDOWN = 0x3ed;

  /** No unit found, or no slot follows it. */
  public static final int NO_CHAMPION = 0x3ee;

  /** The unit holds its ability pending. */
  public static final int PENDING = 0x3f1;

  /** The unit cannot act and the game refuses a frozen champion's ability; not in the standard. */
  public static final int FROZEN = 0x3f3;

  /** The unit carries the tag that disables its ability. */
  public static final int DISABLED = 0x3f5;

  /** The slot has no charge left. */
  public static final int NO_CHARGES = 0x3f6;

  /** The king's whole elixir is below the ability's cost. */
  public static final int ELIXIR = 0x41a;

  /** Whether a champion that cannot act may use its ability: so in the standard game. */
  private static final boolean CAN_EXECUTE_ABILITY_FROZEN = true;

  /**
   * What a command came to.
   *
   * @param code {@link #OK} or the code it was refused with
   * @param elixirBefore the king's elixir as it ran, in ten-thousandths
   * @param elixirAfter the king's elixir after it
   * @param requested the live copies whose ability was requested, in order
   */
  public record Outcome(
      int code, int elixirBefore, int elixirAfter, List<CharacterEntity> requested) {}

  private AbilityCommand() {}

  /**
   * Runs a command of a side naming a unit.
   *
   * @param world the battle's world, in a match
   * @param side the commanding side
   * @param named the unit the command names
   * @return what it came to
   */
  public static Outcome run(BattleWorld world, int side, CharacterEntity named) {
    TowerEntity king = world.kingTower(side);
    int before = world.elixir(side);
    int code = gates(world, king, side, named);
    if (code != OK) {
      return new Outcome(code, before, before, List.of());
    }
    CharacterEntity unit = find(world, side, named);
    int cost = unit.getData().ability().manaCost();
    if (cost >= 1) {
      world.getKingElixir().spend(side, cost * KingElixir.SCALE);
    }
    king.actionHolder().abilityPaid(unit);
    // The runs heard it from the last to the first: the second slot, then the first. A slot that
    // follows the unit's row requested the live copies it found as it heard it.
    List<CharacterEntity> requested = new ArrayList<>();
    for (int n = 2; n >= 1; n--) {
      ChampionController slot = king.championSlot(n);
      if (slot.getChampion() != null && slot.getChampion().name().equals(unit.getData().name())) {
        requested.addAll(slot.champions());
      }
    }
    return new Outcome(OK, before, world.elixir(side), List.copyOf(requested));
  }

  /** The gates in order: 0 when the command may pay, else the code it is refused with. */
  private static int gates(BattleWorld world, TowerEntity king, int side, CharacterEntity named) {
    if (!HitPoints.alive(king.getHitPoints())) {
      return KING_GONE;
    }
    CharacterEntity unit = find(world, side, named);
    if (unit == null) {
      return NO_CHAMPION;
    }
    ChampionController slot = null;
    for (int n = 1; n <= 2 && slot == null; n++) {
      if (king.championSlot(n).follows(unit)) {
        slot = king.championSlot(n);
      }
    }
    if (slot == null) {
      return NO_CHAMPION;
    }
    if (slot.getCooldownMs() > 0) {
      return COOLDOWN;
    }
    if (slot.getCharges() == 0) {
      return NO_CHARGES;
    }
    AbilityData ability = unit.getData().ability();
    if (ability == null || !ability.champion()) {
      return NOT_CHAMPION;
    }
    if (unit.abilityPending()) {
      return PENDING;
    }
    if (unit.isClone()) {
      return CLONE;
    }
    if (!CAN_EXECUTE_ABILITY_FROZEN && !unit.isActive(CharacterEntity.TARGETING_SLOT)) {
      return FROZEN;
    }
    if ((unit.getView().getFlags() & EntityFlags.ABILITY_DISABLED) != 0) {
      return DISABLED;
    }
    if (world.wholeElixir(side) < ability.manaCost()) {
      return ELIXIR;
    }
    return OK;
  }

  /**
   * The unit a command names: the unit itself while it is in the live list, else the first
   * character of the side in the live list with its row and its play; null for none.
   */
  private static CharacterEntity find(BattleWorld world, int side, CharacterEntity named) {
    List<BattleEntity> live = world.getHolder().entities();
    if (live.contains(named)) {
      return named;
    }
    for (BattleEntity entity : live) {
      if (entity instanceof CharacterEntity unit
          && unit.side() == side
          && unit.getData().name().equals(named.getData().name())
          && unit.getDeployIndex() == named.getDeployIndex()) {
        return unit;
      }
    }
    return null;
  }
}
