package org.crforge.core.battle.action;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.Builder;
import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.combat.LevelScaling;
import org.crforge.core.pathfinding.combat.RarityTable;
import org.crforge.core.pathfinding.combat.ScalingGlobals;
import org.crforge.core.pathfinding.combat.ScalingMode;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * An action that lasts and enchants its unit's hits, as the Giant Buffer's buff does: every so many
 * landed single-target hits, the next one deals added damage, scaled at the level of the object
 * that gave the buff.
 *
 * <p>Its start walks back from its cause - the projectile that landed it - to the object that made
 * it, as many links as the row's depth less one, and keeps that object's id, rarity and level. The
 * run then listens to its unit's hits:
 *
 * <ul>
 *   <li><b>A hit's damage.</b> On the hit that completes the count - the counter one short of the
 *       row's attack amount - a positive damage with a hit id gains the added damage, and the crown
 *       tower damage its own; the hit id is recorded.
 *   <li><b>An attack's end.</b> The counter goes up; reaching the attack amount it schedules the
 *       row's attack amount action on the unit and starts again from 0.
 * </ul>
 *
 * <p>A projectile the unit registers on the hit that completes the count gets a copy of the run,
 * primed one short, whose hook adds the damage as the projectile lands.
 *
 * <p>Each step empties the recorded hit ids. Once the object that gave the buff is no longer in the
 * battle's live list, the run counts the row's finish time down and then ends, scheduling its
 * finished action on the unit.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled line for line: the start's walk back to the giver, the damage hook on the"
            + " completing hit, the count and its action, the end after the giver leaves and the"
            + " finished action; held by the reference battle cg_giantbuffer_buffs_friends, a"
            + " Knight's hits for the hook and the count and an Archer's for a projectile's copy."
            + " Not modelled: the enemy-target visual it hands a landed hit to, which is"
            + " presentation. Refused: a unit with attached children, which would get copies, a"
            + " unit whose attack sequence replaces its attack, and the Berserker's attack"
            + " sequence step.")
public final class GiantBufferBuff extends RowAction {

  /**
   * The row's own columns.
   *
   * @param attackAmount the hits that make one count
   * @param attackAmountAction what the count's end runs on the unit, or null
   * @param onFinishedAction what the run's end runs on the unit, or null
   * @param addedDamage the damage the completing hit gains, before its multiplier and scaling
   * @param addedCrownTowerDamage the crown tower damage it gains, likewise
   * @param finishIfInstigatorDiesMs how long the run lasts once its giver has left; below 0 for
   *     ever
   * @param instigatorDepth how many links back from its cause the giver is, plus one
   * @param characterMultipliers the multiplier of the added damage per character or building row,
   *     in thousandths; 1000 for a row it does not name
   * @param projectileMultipliers the multiplier per projectile row, likewise
   */
  @Builder
  public record Columns(
      int attackAmount,
      BattleAction attackAmountAction,
      BattleAction onFinishedAction,
      int addedDamage,
      int addedCrownTowerDamage,
      int finishIfInstigatorDiesMs,
      int instigatorDepth,
      Map<String, Integer> characterMultipliers,
      Map<String, Integer> projectileMultipliers) {

    public Columns {
      characterMultipliers = Map.copyOf(characterMultipliers);
      projectileMultipliers = Map.copyOf(projectileMultipliers);
    }
  }

  /** The multiplier a row the map does not name has, in thousandths. */
  private static final int NO_MULTIPLIER = 1000;

  /** The step the finish countdown loses. */
  private static final int STEP_MS = 50;

  private static final int RUNNING = 1;
  private static final int FINISHING = 2;

  private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns its own columns
   */
  public GiantBufferBuff(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    ActionOwner owner = holder.getOwner();
    if (owner.actionSpawnsAttached()) {
      throw new UnsupportedOperationException(
          name()
              + " on "
              + owner.actionRowName()
              + ", whose attached children get copies,"
              + " which is not modelled");
    }
    if (owner.actionAttackSequence()) {
      // The hits of a unit whose attack sequence replaces its attack - an entry's projectile, or
      // an entry's action in place of a hit - are not established under an enchanting run.
      throw new UnsupportedOperationException(
          name()
              + " on "
              + owner.actionRowName()
              + ", whose attack sequence replaces its attack, which is not modelled");
    }
    Run run = new Run(this, owner);
    ActionOwner giver = instigator == null ? null : instigator.getOwner();
    if (giver == null) {
      // A cause with no object ends the run at once.
      run.finish();
      return run;
    }
    int depth = columns.instigatorDepth();
    if (depth >= 1) {
      while (depth >= 2) {
        if (!giver.actionCreated()) {
          break;
        }
        ActionOwner maker = giver.actionCreator();
        if (maker == null) {
          throw new UnsupportedOperationException(
              name() + " walks back from an object whose maker has left, which is not modelled");
        }
        giver = maker;
        depth--;
      }
      run.source = giver.actionId();
    }
    run.rarity = giver.actionRarity();
    run.packedLevel = giver.actionPackedLevel();
    run.phase = RUNNING;
    return run;
  }

  /** One run on a unit: the giver, the counter and the hits it added damage to. */
  public final class Run extends ActionInstance {

    private final ActionOwner owner;

    /** True for a copy on a projectile, which only carries the added damage to its impact. */
    private boolean onProjectile;

    private int phase;
    private int source;
    private RarityTable rarity;
    private int packedLevel;

    /** The unit's landed hits since the count's last completion. */
    @Getter private int counter;

    private int countdownMs;
    private final Set<Integer> hits = new HashSet<>();

    private Run(BattleAction action, ActionOwner owner) {
      super(action);
      this.owner = owner;
    }

    @Override
    protected void update(ActionHolder holder) {
      hits.clear();
      if (onProjectile) {
        return;
      }
      if (phase == RUNNING) {
        if (source == 0 || columns.finishIfInstigatorDiesMs() < 0) {
          return;
        }
        if (owner.liveObject(source)) {
          return;
        }
        phase = FINISHING;
        countdownMs = columns.finishIfInstigatorDiesMs() - STEP_MS;
        if (countdownMs <= 0) {
          end(holder);
        }
      } else if (phase == FINISHING) {
        countdownMs -= STEP_MS;
        if (countdownMs <= 0) {
          end(holder);
        }
      }
    }

    /** Ends the run and schedules the finished action on the unit, the unit as its cause. */
    private void end(ActionHolder holder) {
      finish();
      if (columns.onFinishedAction() != null) {
        holder.schedule(columns.onFinishedAction(), ActionHolder.OWN_DELAY, false, holder);
      }
    }

    /**
     * The damage hook of a hit: on the hit that completes the count, a positive damage with a hit
     * id gains the added damage, the crown tower's for the crown tower damage.
     *
     * @param damage the damage so far
     * @param hitId the hit's id; 0 for none
     * @param crownTower true for the crown tower damage
     * @return the damage the hit carries on
     */
    public int damage(int damage, int hitId, boolean crownTower) {
      if (damage < 1 || hitId == 0 || counter != columns.attackAmount() - 1) {
        return damage;
      }
      hits.add(hitId);
      return damage + added(crownTower);
    }

    /**
     * The end of one of the unit's attacks: the count goes up, and at the attack amount the row's
     * action is scheduled on the unit and the count starts again.
     *
     * @param holder the unit's holder
     */
    @Override
    protected void attackEnded(ActionHolder holder) {
      int before = counter;
      counter = before + 1;
      if (before == columns.attackAmount() - 2) {
        if (owner.actionRowName().equals("Berserker")) {
          throw new UnsupportedOperationException(
              name() + " sets the Berserker's attack sequence step, which is not modelled");
        }
      } else if (counter >= columns.attackAmount()) {
        if (columns.attackAmountAction() != null) {
          holder.schedule(columns.attackAmountAction(), ActionHolder.OWN_DELAY, false, holder);
        }
        counter = 0;
      }
      hits.clear();
    }

    /**
     * The copy a projectile the unit is registering gets on the hit that completes the count: the
     * same giver's level and rarity, the unit as its giver, primed one short of the attack amount,
     * so the projectile's impact carries the added damage. None on any other hit.
     *
     * @param projectile the projectile being registered
     * @return the copy, or null for none
     */
    public Run projectileCopy(ActionOwner projectile) {
      if (counter != columns.attackAmount() - 1) {
        return null;
      }
      Run copy = new Run(getAction(), projectile);
      copy.onProjectile = true;
      copy.packedLevel = packedLevel;
      copy.rarity = rarity;
      copy.source = owner.actionId();
      copy.counter = columns.attackAmount() - 1;
      copy.phase = RUNNING;
      return copy;
    }

    /**
     * The added damage: the column times the multiplier of the row it runs on - the unit's, or the
     * projectile's for a copy - in thousandths, rounded toward zero, then scaled as a card's damage
     * at the giver's level.
     */
    private int added(boolean crownTower) {
      int base = crownTower ? columns.addedCrownTowerDamage() : columns.addedDamage();
      Map<String, Integer> multipliers =
          onProjectile ? columns.projectileMultipliers() : columns.characterMultipliers();
      int multiplier = multipliers.getOrDefault(owner.actionRowName(), NO_MULTIPLIER);
      int product = FixedMath.s32((long) base * multiplier);
      int value = product / 1000;
      return LevelScaling.scale(
          ScalingGlobals.standard(), value, packedLevel, ScalingMode.CARD_DAMAGE, rarity);
    }
  }
}
