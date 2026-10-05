package org.crforge.core.battle.unit;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.BattleComponent;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.ActionOwner;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.ChampionAbility;
import org.crforge.core.battle.action.CookingHost;
import org.crforge.core.battle.action.Filter;
import org.crforge.core.battle.action.Taunt;
import org.crforge.core.battle.action.WaitToActivate;
import org.crforge.core.battle.action.WithDuration;
import org.crforge.core.battle.expression.BattleFunctions;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.projectile.ProjectileLauncher;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.GridStateSetter;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.crforge.core.pathfinding.combat.LevelScaling;
import org.crforge.core.pathfinding.combat.ScalingGlobals;
import org.crforge.core.pathfinding.combat.ScalingMode;
import org.crforge.core.pathfinding.grid.LaneAssignment;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.math.FixedMath;
import org.crforge.core.pathfinding.move.MovementState;
import org.crforge.core.pathfinding.state.EntityStateVisit;
import org.crforge.core.pathfinding.state.ResumeHelper;
import org.crforge.core.pathfinding.state.StateQueries;
import org.crforge.core.pathfinding.state.StateTimers;
import org.crforge.core.pathfinding.state.StateVisitConfig;
import org.crforge.core.pathfinding.state.StateVisitGlobals;
import org.crforge.core.pathfinding.target.AttackSequenceEntry;
import org.crforge.core.pathfinding.target.SelectionChain;
import org.crforge.core.pathfinding.target.TargetingConfig;
import org.crforge.core.pathfinding.target.TargetingVisit;

/**
 * A crown tower: a building that stands still, occludes the routing grid under its footprint, is
 * what a unit with nothing else to attack walks towards, and shoots at what comes into its range.
 *
 * <p>A tower carries the same targeting component as a troop, in the same slot, and no movement
 * component, so it never leaves the standing state except to attack. With nothing in range it holds
 * the opposing side's tower its default selection gives as its reference, out of range, and selects
 * again every tick; once a unit comes into range it locks on, fires its projectile on the attack
 * ticks, and returns to standing when the reference is gone. Its post-hook is the entity state
 * visit, which steps its elapsed time from its first tick, and at its end the combat gate, which
 * switches the targeting component off while the tower is inactive or dead: a king tower that dies
 * drops its reference and fires no more.
 *
 * <p>A king tower sleeps until its side loses a princess tower or it loses hit points itself. Its
 * placement queues its starting action, built from its row: a group around a wait that sets the
 * inactive tag; the first tick's first pending pass starts it, after that tick's tags were folded,
 * so the king is visited on its first two ticks before the gate switches it off. The run pass that
 * first sees the condition ends the wait, which queues a 3300 ms activating run that the same
 * tick's second pending pass starts. That run keeps the tag that holds the component off until the
 * run pass after it finishes removes it, so the king's first visit falls seventy ticks after the
 * tick that saw the condition.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: a tower occludes routing from its collision radius, takes part in pushes and"
            + " steering as a static neighbour with a mass of 0, is a default target, answers as a crown tower whether king or princess"
            + " tower and as the tower slot only when king, and stands at its hit points at its"
            + " level; it carries the targeting component in slot 0 and no movement component,"
            + " the building branches of the targeting visit, the state visit as its post-hook"
            + " with the combat gate at its end, and a king tower asleep from creation, visited"
            + " on its first two ticks, the wait its placement queues and its condition, the"
            + " activating run that follows and the tags both set, which the pre-hook folds in and"
            + " the gate reads, all built from the king's own starting row; a king tower never"
            + " removable, so it stays in the holder dead, switched off by the gate from the tick"
            + " it dies. Supplied, not settled: the towers scale as Common. Not modelled: the"
            + " rest of the king's own state visit.")
public class TowerEntity extends WorldEntity {

  /** Slot of the targeting component, the same slot a troop's is in. */
  public static final int TARGETING_SLOT = 0;

  /** Applies every state change the tower asks for; a tower has no route to act on. */
  private final GridStateSetter setter;

  /** The countdowns the state visit owns. */
  private final StateTimers timers = new StateTimers();

  /** The tower's state-visit columns: no deploy time, and none of the other columns set. */
  private final StateVisitConfig stateConfig = StateVisitConfig.forGroundUnit(0);

  /** True for a tower placed to stand passive: its targeting component never runs. */
  private boolean holdingFire;

  /** A king's two champion slots, once a match has made them; null before. */
  private final ChampionController[] championSlots = new ChampionController[2];

  /** The global that names the champion slot's row. */
  private static final String SUMMONER_CHAMPION_ABILITY_ACTION = "SUMMONER_CHAMPION_ABILITY_ACTION";

  /**
   * @param world the battle's shared arena state, whose arena assigns the tower its lane from the
   *     road nearest to it
   * @param data the tower's published columns
   * @param name the tower's unique name within the battle
   * @param side the side that owns the tower
   * @param x position in game units
   * @param y position in game units
   * @param level the tower's level, counted from 1
   */
  public TowerEntity(
      BattleWorld world, UnitData data, String name, int side, int x, int y, int level) {
    super(
        world,
        data,
        createView(world.getTileMap(), data, name, side, x, y),
        targetingConfig(data),
        level);
    if (!data.unmodelledColumns().isEmpty()) {
      throw new UnsupportedOperationException(
          data.name() + " sets columns the battle does not model: " + data.unmodelledColumns());
    }
    // A tower takes no buff as it is made: no tower row sets a starting buff.
    if (data.startingBuff() != null) {
      throw new UnsupportedOperationException(
          data.name() + " sets a StartingBuff, which a tower is not held to take");
    }
    this.setter = new GridStateSetter(getView(), null, getTargeting(), () -> null);
    SelectionChain selection = getSelection();
    selection.setStateSetter(setter);
    selection.getOutcome().setRoutePreparer(setter::prepareRoute);
    // A building with no reference resets its attack only when it has hit points at the first
    // level; every tower does.
    selection.setBuildingKeepsAttacking(data.hitpoints() != 0);
    attach(new TargetingComponent());

    refuseAttack(data);
    if (data.onStartingAction() != null) {
      // A tower's starting action is its row's, which its placement queues; with no pending pass
      // running it waits for the first one. The king's is a group around the wait for its
      // activation; the Dagger Duchess's is its charge counter.
      BattleAction starting =
          world.getActions().build(data.onStartingAction(), world.binding(this));
      if (data.king()) {
        actionHolder().setListener(new ActivationListener());
      }
      actionHolder().schedule(starting, ActionHolder.OWN_DELAY);
    }
  }

  /**
   * Refuses the parts of a tower's attack sequence that are not established: a mode that moves the
   * index by itself, and an entry that sets anything but its damage, its projectile and its pace
   * (the hit speed multiplier the attack timer steps at). Only an action moves a tower's index.
   */
  private static void refuseAttack(UnitData data) {
    AttackSequence sequence = data.attackSequence();
    String refused = null;
    if (sequence.mode() != AttackSequence.MODE_NONE) {
      refused = "an attack sequence whose mode " + sequence.mode() + " moves the index itself";
    }
    for (AttackSequence.Entry entry : sequence.entries()) {
      boolean more =
          entry.variableDamageTime() != 0
              || entry.customRange() != -1
              || entry.customSightRange() != -1
              || entry.customMinimumRange() != -1
              || entry.customProjectileStartZ() != -1
              || entry.customProjectileStartRadius() != -1
              || entry.meleePushback() != 0
              || entry.meleePushbackAll()
              || entry.doAttackAction() != null;
      if (more) {
        refused = "an attack sequence entry that sets more than its damage, projectile and pace";
      } else if (sequence.replacesAttack() && entry.projectile() == null && data.hasProjectile()) {
        refused = "an attack sequence entry without a projectile on a tower that fires";
      }
    }
    if (refused != null) {
      throw new UnsupportedOperationException(data.name() + " has " + refused + ", not modelled");
    }
  }

  /** Tells observers which part of the king's condition held when its wait ended. */
  private void conditionHeld() {
    BattleExpressionEnvironment environment = new BattleExpressionEnvironment(this, world);
    boolean damaged = environment.call(BattleFunctions.id("king_tower_damaged"), new int[0]) != 0;
    boolean towerDestroyed =
        environment.call(BattleFunctions.id("tower_destroyed"), new int[0]) != 0;
    world.activation(
        this, new ActivationEvent(ActivationEvent.Kind.CONDITION, 0, damaged, towerDestroyed));
  }

  /**
   * Turns the king's action steps into the activation events observers are told. The steps are told
   * apart by their classes, each of which its starting action holds once: the wait, the activating
   * duration and the filter that shows the effect.
   */
  private final class ActivationListener implements ActionHolder.Listener {

    @Override
    public void started(BattleAction action, int phase) {
      if (action instanceof WithDuration) {
        world.activation(
            TowerEntity.this, ActivationEvent.of(ActivationEvent.Kind.ACTIVATING_STARTED, phase));
      } else if (action instanceof Filter) {
        world.activation(TowerEntity.this, ActivationEvent.of(ActivationEvent.Kind.EFFECT, phase));
      }
    }

    @Override
    public void finished(ActionInstance instance) {
      if (instance.getAction() instanceof WaitToActivate) {
        conditionHeld();
      } else if (instance.getAction() instanceof WithDuration) {
        world.activation(
            TowerEntity.this, ActivationEvent.of(ActivationEvent.Kind.ACTIVATING_FINISHED, 0));
      }
    }

    @Override
    public void removed(ActionInstance instance) {
      if (instance.getAction() instanceof WithDuration) {
        world.activation(
            TowerEntity.this, ActivationEvent.of(ActivationEvent.Kind.ACTIVATING_REMOVED, 0));
      }
    }
  }

  /**
   * The tower's view at placement. Like every character it is given the lane of the road nearest to
   * its position, which the default selection compares with a unit's own lane.
   */
  private static GridEntity createView(
      TileMap tileMap, UnitData data, String name, int side, int x, int y) {
    GridEntity view = new GridEntity();
    view.setName(name);
    view.setSide(side);
    view.setLane(
        LaneAssignment.lane(
            tileMap.width(), tileMap.height(), tileMap.width(), x, y, -1, 0, tileMap::bits));
    view.setCollisionRadius(data.collisionRadius());
    view.setMass(data.mass());
    view.setBuilding(true);
    view.setOccludes(true);
    view.setMovementComponent(false);
    view.setMovementActive(false);
    // Both tower kinds are crown towers: noticed from farther away, ordered last by the index and
    // dealt the crown-tower damage. Only the king tower fills its side's tower slot.
    view.setCrownTower(data.king() || data.summonerTower());
    view.setKingCandidate(data.king() ? 1 : 0);
    view.setTargetable(1);
    view.setX(x);
    view.setY(y);
    return view;
  }

  private static TargetingConfig targetingConfig(UnitData data) {
    return TargetingConfig.tower(
            data.name(),
            data.range(),
            data.sightRange(),
            data.collisionRadius(),
            data.hitSpeedMs(),
            data.loadTimeMs(),
            data.summonerTower())
        .toBuilder()
        .hasProjectile(data.hasProjectile())
        .sightClip(data.sightClip())
        .sightClipSide(data.sightClipSide())
        .keepTargetWithPendingDamage(data.keepTargetWithPendingDamage())
        .attackSequenceMode(data.attackSequence().mode())
        .attackSequenceLength(data.attackSequence().order().size())
        .attackSequenceStepIds(data.attackSequence().order())
        .attackSequenceEntries(sequenceEntries(data.attackSequence()))
        .build();
  }

  /**
   * The steps the targeting reads of a tower's attack sequence: each entry's pace, the hit speed
   * multiplier the attack timer steps at, and no range of its own. A tower without a sequence has
   * the one ordinary step.
   */
  private static List<AttackSequenceEntry> sequenceEntries(AttackSequence sequence) {
    if (sequence.entries().isEmpty()) {
      return List.of(AttackSequenceEntry.none());
    }
    List<AttackSequenceEntry> steps = new ArrayList<>();
    for (AttackSequence.Entry entry : sequence.entries()) {
      steps.add(
          new AttackSequenceEntry(
              AttackSequenceEntry.NO_OVERRIDE,
              AttackSequenceEntry.NO_OVERRIDE,
              AttackSequenceEntry.NO_OVERRIDE,
              entry.hitSpeedMultiplier()));
    }
    return steps;
  }

  /**
   * Keeps the tower passive for the rest of the battle: its targeting component is switched off and
   * the gate never switches it back on. The reference runs made without the towers fighting are
   * played this way.
   */
  public void holdFire() {
    holdingFire = true;
    setActive(TARGETING_SLOT, false);
  }

  /** Whether the tower was placed to stand passive for the whole battle. */
  public boolean isHoldingFire() {
    return holdingFire;
  }

  /**
   * Whether the tags folded in at the last pre-hook keep the targeting component off: a sleeping or
   * waking king. A princess tower never is.
   */
  public boolean isInactive() {
    return (getView().getFlags() & getView().getFlagBits().keepsTargetingOff()) != 0;
  }

  private StateQueries stateQueries() {
    // A tower has no movement component, so it can never be given a route: a resume stands it.
    return StateQueries.forUnitWithRoute(side() & 1).withMayHoldRoute(false);
  }

  /**
   * Makes the king's two champion slots, the first and then the second, each a run of the row the
   * globals name, listed in the king's holder. The king makes them as it starts; they are made here
   * as a match is set up, before any step, listed before the king's starting action, which starts
   * in the first step's pending pass: neither reads the other.
   */
  public void makeChampionSlots() {
    if (!getData().king()) {
      throw new IllegalStateException(name() + " is no king, which alone has champion slots");
    }
    BattleAction row =
        world
            .getActions()
            .build(
                world.getRecords().globalText(SUMMONER_CHAMPION_ABILITY_ACTION),
                world.binding(this));
    actionHolder().start(row);
    actionHolder().start(row);
  }

  @Override
  public ActionInstance championAbility(ChampionAbility action) {
    if (!getData().king()) {
      return super.championAbility(action);
    }
    int free = championSlots[0] == null ? 0 : 1;
    if (championSlots[free] != null) {
      throw new IllegalStateException(name() + " makes a third champion slot");
    }
    championSlots[free] = new ChampionController(world, this, action, free + 1);
    return championSlots[free];
  }

  /**
   * Taunts the tower onto an object, as a taunt's perform does: the run, armed at once, which the
   * holder lists. Every tower is a crown tower, so the arming tries the crown tower branch first.
   * The perform is told to the observers first.
   *
   * <p>Refused rather than guessed: an object to force onto that is not in the battle or flies,
   * which a tag may count as on the ground.
   */
  @Override
  public ActionInstance taunt(Taunt action, ActionOwner instigator, ActionOwner forced, int phase) {
    if (!(forced instanceof WorldEntity onto) || onto.getTargetView().air()) {
      throw new UnsupportedOperationException(
          action.name()
              + " taunts "
              + name()
              + " onto a flying object or one not in the battle, which is not modelled");
    }
    world.tauntPerformed(this, action.name(), phase, instigator, onto);
    TauntRun run = new TauntRun(action, this, onto);
    run.start();
    return run;
  }

  /**
   * One of the king's champion slots.
   *
   * @param slot 1 or 2
   * @return the slot, or null before a match made them
   */
  public ChampionController championSlot(int slot) {
    return championSlots[slot - 1];
  }

  /** The slot that follows a champion row: 1, else 2, else 0 for neither. */
  int championSlotOf(UnitData champion) {
    for (int slot = 1; slot <= 2; slot++) {
      UnitData followed = championSlot(slot).getChampion();
      if (followed != null && followed.name().equals(champion.name())) {
        return slot;
      }
    }
    return 0;
  }

  /**
   * The slot that follows a champion row by its name: the first, else the second, else null.
   *
   * @param champion the champion row's name
   */
  ChampionController championSlotFollowing(String champion) {
    for (int slot = 1; slot <= 2; slot++) {
      UnitData followed = championSlot(slot).getChampion();
      if (followed != null && followed.name().equals(champion)) {
        return championSlot(slot);
      }
    }
    return null;
  }

  /**
   * A champion an action spawned, handed to the king's slots: the slot that follows its row follows
   * its play; else an empty slot, the first before the second, takes the row and follows the play;
   * else, where the slot allows another champion, the slot that follows the earlier play of the two
   * is given the row. With neither the unit is followed by no slot.
   *
   * @param unit the champion
   * @return the slot that now follows it, or null
   */
  ChampionController championSpawned(CharacterEntity unit) {
    UnitData row = unit.getData();
    int index = unit.getDeployIndex();
    ChampionController first = championSlot(1);
    ChampionController second = championSlot(2);
    for (ChampionController slot : List.of(first, second)) {
      UnitData followed = slot.getChampion();
      if (followed != null && followed.name().equals(row.name())) {
        slot.followSpawned(index);
        return slot;
      }
    }
    ChampionController take = null;
    if (first.getChampion() == null) {
      take = first;
    } else if (second.getChampion() == null) {
      take = second;
    } else if (first.allowsReassignment() && second.getDeployIndex() > first.getDeployIndex()) {
      take = first;
    } else if (second.allowsReassignment() && first.getDeployIndex() > second.getDeployIndex()) {
      take = second;
    }
    if (take == null) {
      return null;
    }
    take.assign(row);
    take.followSpawned(index);
    return take;
  }

  /**
   * The deck pass at the match's setup: both slots cleared, then the deck walked from its last card
   * to its first, at most eight: the first champion found goes to the first slot, with its card's
   * deck index, a second to the second slot, and the walk ends.
   *
   * @param deckChampions the champion each card of the deck summons, by deck index; null for none
   */
  public void championDeckPass(List<UnitData> deckChampions) {
    championSlot(1).assign(null);
    championSlot(2).assign(null);
    boolean first = true;
    for (int index = Math.min(deckChampions.size(), 8) - 1; index >= 0; index--) {
      UnitData champion = deckChampions.get(index);
      if (champion == null) {
        continue;
      }
      ChampionController slot = championSlot(first ? 1 : 2);
      slot.assign(champion);
      slot.setDeckIndex(index);
      world.championDeckPass(slot);
      if (!first) {
        break;
      }
      first = false;
    }
  }

  /**
   * A card play of a match after its cast, told to the king's slots, the first and then the second.
   *
   * @param side the playing side
   * @param champion the champion the card summons, or null
   * @param index the play's deploy count
   * @param play the play's name
   */
  void championCardPlayed(int side, UnitData champion, int index, String play) {
    for (int slot = 1; slot <= 2; slot++) {
      ChampionController controller = championSlot(slot);
      if (controller != null && controller.cardPlayed(side, champion, index)) {
        world.championFollowed(controller, play);
      }
    }
  }

  /**
   * A king tower is never removable: it stays in the holder after its hit points run out, and an
   * entity that held it drops it by its own targeting, not by a removal notice. A princess tower
   * leaves in the cleanup of the tick it dies.
   */
  @Override
  public boolean isRemovable() {
    return !getData().king() && super.isRemovable();
  }

  /**
   * The entity state visit, which for a standing tower steps its elapsed time and nothing else, and
   * at its end the combat gate - for a king in a match, after its hand refill and elixir: the
   * targeting component runs while the tower is alive and not inactive. A king tower that dies
   * drops its reference and is switched off on that tick.
   */
  @Override
  protected void postHook() {
    // In a match a king first refills its hand and regenerates its elixir.
    if (getData().king()) {
      world.kingVisit(this);
    }
    EntityStateVisit.stateVisit(
        getView(),
        timers,
        null,
        stateConfig,
        StateVisitGlobals.standard(),
        stateQueries(),
        new ArrayList<>(),
        setter);
    combatGate(isActive(TARGETING_SLOT), setter::prepareRoute);
    if (holdingFire) {
      setActive(TARGETING_SLOT, false);
    }
  }

  /**
   * What a Royal Chef's cooking run on the king asks of the battle: its side's princess-slot towers
   * and what they are doing, the live list's friendly candidates and their hit points and buffs,
   * and the throw from a tower.
   */
  @Override
  public CookingHost cookingHost() {
    if (!getData().king()) {
      throw new UnsupportedOperationException(
          name() + " cooks, which only a king tower is modelled to do");
    }
    return new CookingHost() {
      /** The side's towers as the first step found them; one that leaves is refused. */
      private List<Integer> listed;

      @Override
      public List<Integer> towers() {
        List<Integer> ids = new ArrayList<>();
        for (TowerEntity tower : world.princessTowers(side())) {
          if (!HitPoints.alive(tower.getHitPoints())) {
            throw destroyed(tower.name());
          }
          ids.add(tower.getId());
        }
        if (listed == null) {
          listed = List.copyOf(ids);
        } else if (!listed.equals(ids)) {
          throw destroyed("a princess tower");
        }
        return ids;
      }

      private UnsupportedOperationException destroyed(String tower) {
        return new UnsupportedOperationException(
            "the Royal Chef's cooking with "
                + tower
                + " destroyed, whose contribution is not modelled");
      }

      @Override
      public boolean attacking(int towerId) {
        return entity(towerId).getView().getState() == GridEntityState.ATTACKING;
      }

      @Override
      public int scaled(int towerId, int contribution) {
        return entity(towerId).getBuffs().hitSpeed(contribution);
      }

      @Override
      public List<Integer> candidates(GameObjectFilter filter) {
        List<Integer> ids = new ArrayList<>();
        for (WorldEntity entity : world.filteredEntities(filter, side() & 1, getData().name())) {
          ids.add(entity.getId());
        }
        return ids;
      }

      @Override
      public boolean found(int id) {
        return world.liveObject(id) != null;
      }

      @Override
      public int state(int id) {
        return entity(id).getView().getState();
      }

      @Override
      public boolean hasHitPoints(int id) {
        return entity(id).getHitPoints() != null;
      }

      @Override
      public int maximum(int id) {
        HitPoints hp = entity(id).getHitPoints();
        return hp.getMaximum() + hp.getShieldMaximum();
      }

      @Override
      public int current(int id) {
        HitPoints hp = entity(id).getHitPoints();
        return hp.getHitPoints() + hp.getShield();
      }

      @Override
      public int scaledThreshold(int id, int base) {
        WorldEntity entity = entity(id);
        return LevelScaling.scale(
            ScalingGlobals.standard(),
            base,
            entity.getPackedLevel(),
            ScalingMode.CARD_HITPOINTS,
            entity.getData().rarity());
      }

      @Override
      public int buffCount(int id, String buff) {
        int count = 0;
        for (var instance : entity(id).getBuffs().items()) {
          if (instance.getBuff().name().equals(buff)) {
            count++;
          }
        }
        return count;
      }

      @Override
      public int squaredDistance(int towerId, int id) {
        GridEntity tower = entity(towerId).getView();
        GridEntity other = entity(id).getView();
        return FixedMath.squaredDistance(tower.getX(), tower.getY(), other.getX(), other.getY());
      }

      @Override
      public boolean throwAllowed(int towerId, int waitAfterAttackMs, int thresholdMs) {
        WorldEntity tower = entity(towerId);
        if (!tower.isActive(TARGETING_SLOT)
            || tower.getView().getState() != GridEntityState.ATTACKING) {
          return true;
        }
        UnitData row = tower.getData();
        if (row.loadTimeMs() - tower.getTargeting().getLoadTimerMs() <= waitAfterAttackMs) {
          return false;
        }
        int hitSpeed = row.hitSpeedMs();
        int attackTime = tower.getTargeting().getAttackTimerMs();
        return hitSpeed - (attackTime - attackTime / hitSpeed * hitSpeed) > thresholdMs;
      }

      @Override
      public void turn(int towerId, int id) {
        GridEntity tower = entity(towerId).getView();
        GridEntity other = entity(id).getView();
        int[] facing = {other.getX() - tower.getX(), other.getY() - tower.getY()};
        FixedMath.normalize(facing, MovementState.DIRECTION_SCALE);
        tower.setDirX(facing[0]);
        tower.setDirY(facing[1]);
      }

      @Override
      public void throwAt(ProjectileData data, int towerId, int targetId, int startOffset) {
        ProjectileEntity projectile = new ProjectileEntity(world, data, side());
        GridEntity tower = entity(towerId).getView();
        WorldEntity target = entity(targetId);
        int dx = target.getView().getX() - tower.getX();
        int dy = target.getView().getY() - tower.getY();
        int length = FixedMath.isqrt(dx * dx + dy * dy);
        if (length != 0) {
          dx = FixedMath.div(startOffset * dx, length);
          dy = FixedMath.div(dy * startOffset, length);
        }
        GridEntity king = getView();
        ProjectileLauncher.launchThrown(
            projectile,
            TowerEntity.this,
            target,
            tower.getX() + dx,
            tower.getY() + dy,
            king.getZ() + king.getHeightOffset());
        world.launch(projectile);
      }

      private WorldEntity entity(int id) {
        return (WorldEntity) world.liveObject(id);
      }
    };
  }

  /** Chooses, keeps or drops the tower's target and decides whether it fires this tick. */
  private final class TargetingComponent implements BattleComponent {

    @Override
    public int index() {
      return TARGETING_SLOT;
    }

    @Override
    public void visit() {
      SelectionChain selection = getSelection();
      selection.beginTick();
      TargetingVisit.targetingVisit(
          getTargeting(), getView(), null, selection, selection.getOutcome());
      if (selection.getOutcome().isResumeRequested()) {
        ResumeHelper.resume(getView(), stateConfig, stateQueries(), new ArrayList<>(), setter);
      }
    }
  }
}
