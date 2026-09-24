package org.crforge.core.battle.unit;

import java.util.ArrayList;
import org.crforge.core.battle.BattleComponent;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridStateSetter;
import org.crforge.core.pathfinding.grid.LaneAssignment;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.state.EntityStateVisit;
import org.crforge.core.pathfinding.state.ResumeHelper;
import org.crforge.core.pathfinding.state.StateQueries;
import org.crforge.core.pathfinding.state.StateTimers;
import org.crforge.core.pathfinding.state.StateVisitConfig;
import org.crforge.core.pathfinding.state.StateVisitGlobals;
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
 * switches the targeting component off while the tower is inactive.
 *
 * <p>A king tower is inactive from creation until it is activated. The component is switched on
 * when the tower is created, so the king is visited once, on its first tick, before the gate
 * switches it off.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: a tower occludes routing from its collision radius, takes no part in pushes or"
            + " steering, is a default target, answers as a crown tower whether king or princess"
            + " tower and as the tower slot only when king, and stands at its hit points at its"
            + " level; it carries the targeting component in slot 0 and no movement component,"
            + " the building branches of the targeting visit, the state visit as its post-hook"
            + " with the combat gate at its end, and a king tower inactive from creation, visited"
            + " once before the gate first runs. Supplied, not settled: the towers scale as"
            + " Common. Not modelled yet: king tower activation, so a king never fights.")
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
    this.setter = new GridStateSetter(getView(), null, getTargeting(), () -> null);
    SelectionChain selection = getSelection();
    selection.setStateSetter(setter);
    selection.getOutcome().setRoutePreparer(setter::prepareRoute);
    // A building with no reference resets its attack only when it has hit points at the first
    // level; every tower does.
    selection.setBuildingKeepsAttacking(data.hitpoints() != 0);
    attach(new TargetingComponent());
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
    view.setMovementActive(false);
    // A building takes no part in pushing; the routing overlay is what keeps units off it.
    view.setPushEnabled(false);
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
        .build();
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
   * Whether the tower is inactive, which keeps its targeting component off. A king tower is
   * inactive from creation; its activation is not modelled yet, so it stays inactive. A princess
   * tower never is.
   */
  public boolean isInactive() {
    return getData().king();
  }

  private StateQueries stateQueries() {
    // A tower has no movement component, so it can never be given a route: a resume stands it.
    return StateQueries.forUnitWithRoute(side() & 1).withMayHoldRoute(false);
  }

  /**
   * The entity state visit, which for a standing tower steps its elapsed time and nothing else, and
   * at its end the combat gate: the targeting component runs while the tower is not inactive.
   */
  @Override
  protected void postHook() {
    EntityStateVisit.stateVisit(
        getView(),
        timers,
        null,
        stateConfig,
        StateVisitGlobals.standard(),
        stateQueries(),
        new ArrayList<>(),
        setter);
    setActive(TARGETING_SLOT, !holdingFire && !isInactive());
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
