/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import java.util.HashSet;
import java.util.List;
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
 * it, as many links as the row's depth less one, and keeps that object's id, rarity and level. A
 * link whose maker has already left ends the walk there: the object reached is the giver, so a
 * projectile whose launcher left before it landed is its own giver. A unit whose row attaches its
 * children, as the Goblin Giant's spear goblins, the Ram Rider and the hero Ice Wizard's cube, then
 * gives each child still in the battle a run of its own, listed on the child and started with the
 * same cause, so with the same giver; each counts and enchants its child's hits as the unit's run
 * does the unit's. The run then listens to its unit's hits:
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
 * primed one short, whose hook adds the damage as the projectile lands. A deflection leaves the
 * copy on the projectile, so the deflector's parent and the turned projectile's impact both gain
 * the added damage.
 *
 * <p>Each step empties the recorded hit ids. Once the object that gave the buff is no longer in the
 * battle's live list, the run counts the row's finish time down and then ends, scheduling its
 * finished action on the unit. A child's run does the same on its own: the unit's run ending, or
 * the unit leaving, does not end it.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled line for line: the start's walk back to the giver, the damage hook on the"
            + " completing hit, the count and its action, the end after the giver leaves and the"
            + " finished action; held by the reference battle cg_giantbuffer_buffs_friends, a"
            + " Knight's hits for the hook and the count and an Archer's for a projectile's copy."
            + " A walk that reaches an object whose maker has left stops there, that object the"
            + " giver: held by the reference battles runegiant_buff_maker_left_miner and"
            + " runegiant_buff_maker_left_skeleton, a buff projectile landing after its Rune"
            + " Giant died, whose enchant ends; the end after the finish time rather than at"
            + " once by BattleEnchantGiverLeftTest only, and the added damage at the"
            + " projectile's level by no case."
            + " A dash's landing is one of the unit's attacks: its damage passes the hook as one"
            + " that is not a crown tower's, and the count goes up as the landing ends, hit or"
            + " not; held by the reference battles bandit_dash_enchant_count and"
            + " bandit_dash_enchant_landing and BattleEnchantedDashTest."
            + " Not modelled: the enemy-target visual it hands a landed hit to, which is"
            + " presentation. A unit whose attack sequence is damage entries alone, as the"
            + " Inferno Dragon's ramp, runs with its ordinary hit hooks, the ramp untouched: the"
            + " build's hit picks only whether an entry launches, acts or hits directly, and its"
            + " direct hit is the one a row without a sequence takes; held by"
            + " BattleEnchantedSequenceTest. A unit whose sequence entries launch its projectiles,"
            + " as the Little Prince's and the reworked Three Musketeers' shots, gives copies to"
            + " the completing hit's projectiles as a row's launch does; held by the reference"
            + " battles runegiant_enchant_little_prince and runegiant_enchant_three_musketeers."
            + " An entry that runs an action in place of the hit, as the reworked musketeers'"
            + " bayonet, is neither counted nor enchanted, and the damage its action deals gains"
            + " nothing: held by runegiant_enchant_musketeer_bayonet, a bayonet on the hit that"
            + " would complete the count. An entry's first projectile gets a copy as the others"
            + " do, held by runegiant_enchant_princess_evo; a hit whose entry names its own attack"
            + " action is counted as any other, held by runegiant_enchant_electrowizard_hero."
            + " A projectile carrying a copy keeps it when it is deflected: the deflector's parent"
            + " takes the added damage on its hit, and the turned projectile lands with it again"
            + " at the giver's level, the count untouched; held by the reference battle"
            + " monk_deflect_enchanted_shot, the Monk at another level than the Rune Giant, and"
            + " BattleEnchantedDeflectTest. Whether the added damage is taken before"
            + " or after the deflected share on a crown tower is read in the build (after) and"
            + " held by no case: the units' share is the whole damage."
            + " A unit whose row attaches its children gives each child still in the battle's"
            + " live list, in the order they were made, a run of its own as its run starts, once"
            + " the giver is found: listed on the child past the singleton walk, then started"
            + " with the same cause, so it walks to the same giver and hands itself on to the"
            + " child's own children; no child gets one when the cause has no object. Held by"
            + " the reference battle giantbuffer_enchant_icewizard_hero only as far as the start"
            + " runs: the hero Ice Wizard's cube deals no damage, and no shipped child's hit"
            + " gains any, as the shipped row's multiplier is 0 for the spear goblins' and the"
            + " Ram Rider's projectiles; the children's own counts and added damage are held by"
            + " BattleEnchantedRidersTest only."
            + " Refused: a start in the run pass that hands a run on to a child that pass has"
            + " stepped, which the game steps at once, the Berserker's attack sequence step, a"
            + " walk that meets"
            + " an area effect, which the game follows to the area effect's parent, and a walk"
            + " that ends on one, which the game keeps as the giver by its id and rarity; no"
            + " shipped row starts the buff from an area effect or a projectile one launched.")
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
    Run run = new Run(this, holder.getOwner());
    begin(run, instigator);
    return run;
  }

  /**
   * The run's own start: the walk back from its cause to its giver, then a run of its own for each
   * child riding on its unit.
   *
   * @param run the run, listed or about to be
   * @param instigator the holder of the cause, or null for none
   */
  private void begin(Run run, ActionHolder instigator) {
    // A unit's attack sequence needs no check: an entry only chooses whether a hit launches, runs
    // an action or hits directly. A launch registers its projectiles as the row's launch does, so
    // the completing hit's projectiles get copies and the hit is counted; an entry's action is
    // run in place of the hit, which is then neither counted nor enchanted, and the damage the
    // action deals reaches no hook of this run.
    ActionOwner giver = instigator == null ? null : instigator.getOwner();
    if (giver == null) {
      // A cause with no object ends the run at once, before any child is given a run.
      run.finish();
      return;
    }
    int depth = columns.instigatorDepth();
    // A depth of 0 or below keeps the cause as the giver without its id.
    boolean keepsSource = depth >= 1;
    if (keepsSource) {
      while (depth >= 2) {
        if (giver.actionAreaEffect()) {
          // The game's next link from an area effect is its parent, the object it was made from,
          // while that object is in the battle; the battle core keeps that parent only for an area
          // effect an action's spawn made, so the walk is refused rather than stopped here.
          throw new UnsupportedOperationException(
              name() + " walks back from an area effect to its parent, which is not modelled");
        }
        if (!giver.actionCreated()) {
          break;
        }
        ActionOwner maker = giver.actionCreator();
        if (maker == null) {
          // A maker that has left is forgotten by its object's removal notice: the walk stops at
          // the object, which stays the giver, its id, rarity and level kept as a giver's.
          break;
        }
        giver = maker;
        depth--;
      }
    }
    if (giver.actionAreaEffect()) {
      // The game keeps an area effect the walk ends on as the giver - the cause itself, or a
      // projectile's launcher - by its id, rarity and level; an area effect's id and rarity are
      // not modelled.
      throw new UnsupportedOperationException(
          name() + " takes an area effect as its giver, whose id and rarity are not modelled");
    }
    if (keepsSource) {
      run.source = giver.actionId();
    }
    run.rarity = giver.actionRarity();
    run.packedLevel = giver.actionPackedLevel();
    handOn(run.owner, instigator);
    run.phase = RUNNING;
  }

  /**
   * Gives each child riding on a unit a run of its own, as the game's start does once it has its
   * giver: a new run of this row, listed on the child's holder with the row's tags and then started
   * with the same cause, so it walks back to the same giver and hands itself on to the child's own
   * riders in turn. Each child's run counts the child's hits, enchants them and gives copies to its
   * projectiles, and ends after its giver leaves, as the unit's own run does. The listing passes by
   * the singleton walk.
   *
   * <p>The game steps a child's new run at once when the child's actions were already stepped in a
   * run pass that is still in progress; an enchanting buff starts from its cause's hit action,
   * outside the run pass, so that is refused rather than modelled.
   *
   * @param owner the unit the run is on
   * @param instigator the holder of the run's cause
   */
  private void handOn(ActionOwner owner, ActionHolder instigator) {
    List<ActionHolder> children = owner.actionAttachedChildren();
    if (children.isEmpty()) {
      return;
    }
    int runPassTick = owner.actionRunPassTick();
    for (ActionHolder child : children) {
      if (runPassTick >= 0 && child.getLastTick() == runPassTick) {
        throw new UnsupportedOperationException(
            name()
                + " starts in the run pass and hands a run on to a child the pass has stepped,"
                + " which the game steps at once; not modelled");
      }
      Run copy = new Run(this, child.getOwner());
      child.list(copy);
      begin(copy, instigator);
    }
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
