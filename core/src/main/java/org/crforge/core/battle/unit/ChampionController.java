package org.crforge.core.battle.unit;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.ChampionAbility;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntityState;

/**
 * One champion slot of a king: the run of the champion ability controller's row that holds a
 * champion's cooldown, charges and button state.
 *
 * <p>A slot follows one champion row and, of it, the copies one card play made, by that play's
 * deploy count. The deck pass at the match's setup gives the slot its champion; a play of a card
 * that summons that champion makes the slot follow the play: its charges refilled, its cooldown
 * cleared, the state deploying. Its live copies are rebuilt each time its state is worked out:
 * every character of the king's side in the live list, from the last to the first, of the followed
 * play, that is not a clone and whose row names the champion's ability row.
 *
 * <p>Each king run pass steps it: the state worked out, then the cooldown counted down 50 a step,
 * except while a copy waits for its ability's gate or carries the tag that pauses it; it runs on
 * while the champion casts, is frozen with nothing pending, and is dead. The refund window since
 * the last use counts down 50 a step while a copy casts (in the casting state, or carrying the tag
 * of a cast) and holds while every live copy does neither; with no copy left while it is open the
 * ability's cost is given back once. A refund that only a held window lets through is refused: the
 * hold is read from the game's code but no recorded battle shows one.
 *
 * <p>A paid ability reaches every run of the king, from the last to the first; the slot that
 * follows the unit's row requests the ability of every live copy, opens the refund window for the
 * ability's RefundWindow (its trigger delay for a row without a positive one), starts the full
 * cooldown and spends a charge if it counts them. With no live copy it does nothing more.
 *
 * <p>An action may write a button state for the step, which wins the next working out outright, and
 * refill the charges; the slot clears that state at the end of each of its own steps. The state is
 * read by nothing else in the battle: the activation and the ability command's gates never look at
 * it. A champion an action spawns is handed to the slots too: the slot that follows its row follows
 * its play, as a card play's would.
 *
 * <p>The button state's last two inputs - a limited availability and a reservation of elixir the
 * player's own client files - are never set in a battle here: only a saved battle's state sets the
 * first, and the reserving pass belongs to the issuing client.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the live copies, the state, the step's cooldown and its pauses,"
            + " the refund window and the refund, the activation, the deck pass, the follow of a"
            + " play; held by the reference battles ability_archer_queen and"
            + " ability_archer_queen_missing_unit. The state override an action writes, cleared"
            + " each step, the charges refill and the follow of a spawned champion, held by"
            + " hero_goblins. The state worked out again as a followed copy takes another row,"
            + " held by ability_hero_wizard. The refund window's length, the RefundWindow or else"
            + " the trigger delay, held by tv_replay_004 and tv_replay_016; a copy kept on another"
            + " row of the same ability, held by hero_berserker. Refused: a refund only a held window lets through, which no"
            + " reference reaches. Not carried: the limited availability and the reservation, which"
            + " nothing in a battle here sets.")
public final class ChampionController extends ActionInstance {

  /** The button state before any champion. */
  public static final int UNKNOWN = 0;

  /** No live copy of the champion. */
  public static final int ABSENT = 1;

  /** The ability may be used. */
  public static final int READY = 2;

  /** The champion's play is followed and its copies deploy. */
  public static final int DEPLOYING = 3;

  /** The ability is available for a limited time: written only by an action here. */
  public static final int LIMITED_AVAILABILITY = 4;

  /** No charge is left. */
  public static final int ALL_CHARGES_CONSUMED = 6;

  /** A live copy holds the ability pending. */
  public static final int PENDING = 7;

  /** The cooldown runs. */
  public static final int ON_COOLDOWN = 8;

  /** The king's whole elixir is below the ability's cost. */
  public static final int NOT_ENOUGH_ELIXIR = 9;

  /** A live copy casts with its effect still to fire. */
  public static final int CASTING = 10;

  /** A live copy carries the tag that disables the ability. */
  public static final int DISABLED = 11;

  /** The ability is out of reach for a while: written only by an action. */
  public static final int TEMPORARILY_UNAVAILABLE = 12;

  /** The ability is not available yet: written only by an action. */
  public static final int NO_YET_AVAILABLE = 13;

  /**
   * The button states by their names, as an action row spells them, matched exactly: the states the
   * slot works out and the ones only an action writes, with the two markers that bound the reasons.
   */
  private static final List<String> STATE_NAMES =
      List.of(
          "ChampionUnknown",
          "ChampionAbsent",
          "Ready",
          "ChampionDeploying",
          "LimitedAvailability",
          "ERR_START",
          "AllChargesConsumed",
          "ChampionPending",
          "OnCooldown",
          "NotEnoughElixir",
          "ChampionCasting",
          "Disabled",
          "TemporarilyUnavailable",
          "NoYetAvailable",
          "ERR_MAX");

  /** Milliseconds one step takes off the cooldown and the refund window. */
  private static final int STEP_MS = 50;

  /** The charges of a champion whose uses are not counted. */
  public static final int UNLIMITED = -1;

  /** The full cooldown the constructor sets before any champion. */
  private static final int INITIAL_COOLDOWN_MS = 1000;

  /** Whether a champion's plays share one cooldown: not in the standard game. */
  private static final boolean SHARED_CHAMPION_COOLDOWNS = false;

  private final BattleWorld world;
  private final TowerEntity king;
  private final ChampionAbility row;

  /** 1 or 2: the slot of the king it fills. */
  @Getter private final int slot;

  /** The champion row it follows, or null for none. */
  @Getter private UnitData champion;

  /** The deploy count of the play it follows, or -1 for none. */
  @Getter private int deployIndex = -1;

  /** The cooldown left, in milliseconds. */
  @Getter private int cooldownMs;

  /** The champion's cooldown, which a use starts. */
  @Getter private int cooldownFullMs = INITIAL_COOLDOWN_MS;

  /** The charges left, or {@link #UNLIMITED}. */
  @Getter private int charges = UNLIMITED;

  /** The button state. */
  @Getter private int state = ABSENT;

  /** A state an action wrote for this step, which wins the next working out; 0 for none. */
  @Getter private int override;

  /** The refund window: what is left of the ability's RefundWindow since the last use, in ms. */
  @Getter private int triggerMs;

  /**
   * How long the refund window has held since the last use, its copies alive and none casting, in
   * milliseconds.
   */
  @Getter private int heldMs;

  /** The cost of the last use, which a refund gives back, in whole elixir. */
  @Getter private int paidMana;

  /** The deck index of the card the deck pass found the champion in, or -1. */
  @Getter private int deckIndex = -1;

  /** The live copies, as the last working out of the state found them. */
  private final List<CharacterEntity> champions = new ArrayList<>();

  ChampionController(BattleWorld world, TowerEntity king, ChampionAbility row, int slot) {
    super(row);
    this.world = world;
    this.king = king;
    this.row = row;
    this.slot = slot;
  }

  /** The live copies, as the last working out of the state found them. */
  public List<CharacterEntity> champions() {
    return List.copyOf(champions);
  }

  /** The king's side. */
  public int side() {
    return king.side();
  }

  /**
   * Whether a unit is a live copy this slot follows: its side, its play, no clone, and a row of the
   * champion's ability row - the champion's own, or another form of it that names the same ability,
   * as the Berserker hero's bear form does.
   */
  boolean follows(CharacterEntity unit) {
    AbilityData ability = unit.getData().ability();
    return champion != null
        && unit.side() == king.side()
        && unit.getDeployIndex() == deployIndex
        && !unit.isClone()
        && ability != null
        && champion.ability() != null
        && ability.name().equals(champion.ability().name());
  }

  /** Whether the slot may follow another champion its player plays or spawns. */
  boolean allowsReassignment() {
    return row.isAllowDynamicReassignments();
  }

  /** Rebuilds the live copies from the live list, from the last to the first. */
  private void refresh() {
    champions.clear();
    if (champion == null) {
      return;
    }
    List<BattleEntity> live = world.getHolder().entities();
    for (int i = live.size() - 1; i >= 0; i--) {
      if (live.get(i) instanceof CharacterEntity unit && follows(unit)) {
        champions.add(unit);
      }
    }
  }

  /**
   * The button state a name spells, matched exactly; 0, the unknown state, for any other name.
   *
   * @param name the state's name
   */
  public static int stateNamed(String name) {
    int index = STATE_NAMES.indexOf(name);
    return Math.max(index, UNKNOWN);
  }

  /**
   * Works out the button state: a state an action wrote for the step outright, else the first
   * reason in a fixed order.
   */
  private int compute() {
    refresh();
    if (override != 0) {
      state = override;
      return state;
    }
    if (champion == null) {
      state = UNKNOWN;
      return state;
    }
    boolean shortOfElixir = world.wholeElixir(king.side()) < champion.ability().manaCost();
    boolean pending = false;
    boolean casting = false;
    boolean disabled = false;
    for (CharacterEntity unit : champions) {
      disabled |= (unit.getView().getFlags() & unit.getView().getFlagBits().abilityDisabled()) != 0;
      pending |= unit.abilityPending();
      casting |=
          unit.abilityWarningCountdown() > 0
              && unit.getView().getState() == GridEntityState.CASTING;
    }
    if (champions.isEmpty()) {
      state = ABSENT;
    } else if (charges == 0) {
      state = ALL_CHARGES_CONSUMED;
    } else if (pending) {
      state = PENDING;
    } else if (cooldownMs > 0) {
      state = ON_COOLDOWN;
    } else if (shortOfElixir) {
      state = NOT_ENOUGH_ELIXIR;
    } else if (casting) {
      state = CASTING;
    } else if (disabled) {
      state = DISABLED;
    } else {
      state = READY;
    }
    return state;
  }

  /**
   * The step in the king's run pass: the state, the cooldown unless paused, and the refund window.
   */
  @Override
  protected void update(ActionHolder holder) {
    // What the observers are told of the step: the copies as it found them, and the elixir before
    // any refund.
    List<ChampionView> views = world.championViews(king.side());
    int elixir = world.elixir(king.side());
    compute();
    int before = cooldownMs;
    if (cooldownMs >= 1 && state != PENDING) {
      boolean paused = false;
      for (CharacterEntity unit : champions) {
        paused |=
            (unit.getView().getFlags() & unit.getView().getFlagBits().abilityCooldownPaused()) != 0;
      }
      if (!paused) {
        cooldownMs = Math.max(cooldownMs, STEP_MS) - STEP_MS;
        if (!champions.isEmpty() && cooldownMs == 0 && charges != 0) {
          state = READY;
        }
      }
    }
    if (triggerMs >= 1) {
      if (champions.isEmpty()) {
        if (triggerMs - heldMs < 1) {
          throw new UnsupportedOperationException(
              champion.name()
                  + "'s ability cost is given back only because its refund window held while no"
                  + " live copy cast, which no recorded battle establishes");
        }
        world.championRefund(this, paidMana);
        triggerMs = 0;
      } else if (anyCasting()) {
        // Not clamped: a window of 933 ends at -17.
        triggerMs -= STEP_MS;
      } else {
        heldMs += STEP_MS;
      }
    }
    if (before > 0 && cooldownMs == 0) {
      world.championCooldownOut(this);
    }
    if (champion != null) {
      world.championStepped(this, elixir, views);
    }
    // A state an action wrote lasts the one step.
    override = 0;
  }

  /**
   * Whether a live copy casts, as the refund window reads it: in the casting state, or carrying the
   * tag of a cast in its tag word.
   */
  private boolean anyCasting() {
    for (CharacterEntity unit : champions) {
      if ((unit.getView().getFlags() & unit.getView().getFlagBits().castingAbility()) != 0
          || unit.getView().getState() == GridEntityState.CASTING) {
        return true;
      }
    }
    return false;
  }

  /**
   * A state an action writes for the step: it wins the slot's next working out, and the slot's step
   * clears it.
   *
   * @param written the state
   */
  void override(int written) {
    override = written;
  }

  /**
   * A character this slot followed took another row: when it is one of the live copies the last
   * working out found, the state is worked out again at once, the copies rebuilt from the live
   * list, which a unit on a row that names another ability row has left. Any other unit changes
   * nothing.
   *
   * @param unit the character, already on its new row
   */
  void dataChanged(CharacterEntity unit) {
    if (champions.contains(unit)) {
      compute();
    }
  }

  /**
   * Restarts the cooldown at the full cooldown, as the ready action's ForceCooldown asks; the
   * charges and the state are left as they are, the state worked out again on the slot's next step.
   */
  void forceCooldown() {
    cooldownMs = cooldownFullMs;
  }

  /** Refills the charges to the champion's most, as an action asks: its row's value as it is. */
  void refillCharges() {
    charges = champion.ability().maxCharges();
  }

  /**
   * A champion an action spawned, of the row this slot follows: the slot follows the unit's play.
   *
   * @param index the unit's deploy count
   */
  void followSpawned(int index) {
    follow(index);
  }

  /**
   * A paid ability, which every run of the king hears: the slot that follows the unit's row
   * requests the ability of every live copy and starts its cooldown. It does not look at the button
   * state.
   */
  @Override
  protected void abilityPaid(ActionHolder holder, BattleEntity paid) {
    if (!(paid instanceof CharacterEntity unit)
        || champion == null
        || !unit.getData().name().equals(champion.name())) {
      return;
    }
    compute();
    if (champions.isEmpty()) {
      return;
    }
    List<CharacterEntity> requested = List.copyOf(champions);
    for (CharacterEntity copy : requested) {
      copy.requestAbility();
    }
    AbilityData ability = champion.ability();
    triggerMs = ability.refundWindowMs();
    heldMs = 0;
    paidMana = ability.manaCost();
    cooldownMs = cooldownFullMs;
    if (charges > 0) {
      charges--;
    }
    world.championActivated(this, requested);
  }

  /**
   * Follows a new champion row, or none, with no play of it yet, and works the state out.
   *
   * @param row the champion row, or null
   */
  void assign(UnitData row) {
    champion = row;
    if (row != null) {
      cooldownFullMs = row.ability().cooldownMs();
    }
    cooldownMs = 0;
    deployIndex = -1;
    compute();
  }

  /** Keeps the deck index of the card the deck pass found the champion in. */
  void setDeckIndex(int index) {
    deckIndex = index;
  }

  /** Follows one play of the champion: cooldown cleared, charges refilled, deploying. */
  private void follow(int index) {
    deployIndex = index;
    if (!SHARED_CHAMPION_COOLDOWNS) {
      cooldownMs = 0;
    }
    int maxCharges = champion.ability().maxCharges();
    charges = maxCharges > 0 ? maxCharges : UNLIMITED;
    state = DEPLOYING;
  }

  /**
   * A card play of a side, after its cast: the champion its card summons, if any, and the play's
   * deploy count. A slot of that side takes a champion no slot follows when it follows none, or
   * when either slot follows a later play than its own; the slot following the champion then
   * follows the play if it is later than the one it follows.
   *
   * @param side the playing side
   * @param played the champion the card summons, or null
   * @param index the play's deploy count
   * @return true when the slot now follows the play
   */
  boolean cardPlayed(int side, UnitData played, int index) {
    if (side != king.side() || played == null) {
      return false;
    }
    if (row.isAllowDynamicReassignments() || champion == null) {
      if (king.championSlotOf(played) == 0) {
        boolean take = champion == null;
        if (!take) {
          take =
              king.championSlot(1).deployIndex > deployIndex
                  || king.championSlot(2).deployIndex > deployIndex;
        }
        if (take) {
          champion = played;
          cooldownMs = 0;
          cooldownFullMs = played.ability().cooldownMs();
          deployIndex = -1;
          compute();
        }
      }
    }
    if (champion == null || !played.name().equals(champion.name()) || index <= deployIndex) {
      return false;
    }
    follow(index);
    return true;
  }
}
