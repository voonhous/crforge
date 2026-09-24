package org.crforge.core.pathfinding.target;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.index.SpatialIndex;
import org.crforge.core.pathfinding.index.SpatialQuery;
import org.crforge.core.pathfinding.state.StateSetter;

/**
 * Wires the spatial index, the validator, the default target selection and the candidate selector
 * together into the one answer the targeting visit asks for: "which target should I have now".
 *
 * <p>The order matters and is the order of the methods below. One selection does this:
 *
 * <ol>
 *   <li>ask the spatial index for the entities within the unit's sight circle, whose radius is the
 *       sight range plus the crown-tower sight bonus plus the unit's own collision radius, with
 *       king towers ordered last;
 *   <li>return that list to the index;
 *   <li>work out the default target, which is the opposing side's tower the unit would walk at;
 *   <li>run the candidate selector, which filters and ranks the candidates and falls back to the
 *       default;
 *   <li>copy the chosen reference back into the component.
 * </ol>
 *
 * <p>The selection runs at most once per tick: the visit may ask for it more than once, and the
 * answer is cached until {@link #beginTick()} clears it. A caller clears the cache once per tick,
 * before the visit.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Wires the index, the validator, the selector and default selection into one"
            + " answer per tick; held by the 53 reference walks. A reference to an entity that"
            + " leaves is cleared directly and not through the setter's null path. Hits go to"
            + " a sink that applies nothing.")
public class SelectionChain implements SelectionQueries, TargetingQueries {

  /** Arena length in routing cells, used by the default selection's lane bonus. */
  private final int arenaHeightCells;

  private final SpatialIndex index;
  private final TargetingState state;
  private final Map<GridEntity, TargetView> views = new IdentityHashMap<>();
  private final ValidatorQueries validatorQueries;
  private final DefaultSelectionQueries defaultSelectionQueries;
  private final DefaultTargetSelection.Rules defaultSelectionRules;

  /** The opposing side's registered towers, in placement order, king included. */
  @Getter private final List<TargetView> registeredTowers = new ArrayList<>();

  /** The opposing side's king tower, which seeds the default selection. */
  @Getter @Setter private TargetView seed;

  /** Where the targeting visit's hits go; the default answers that every hit landed. */
  @Getter @Setter private HitSink hitSink = (target, sequenceIndex, extra, last) -> false;

  /**
   * How the visit's request for the attacking state is applied: the bare interrupt guard until the
   * unit's owner installs the unit's own setter, which also runs the actions a state change
   * carries.
   */
  @Getter @Setter private StateSetter stateSetter = StateSetter.guarded();

  /**
   * Whether a building that holds no reference still resets its attack, as a walking unit always
   * does: true for a building whose hit points at the first level are not zero. Only a building's
   * visit asks.
   */
  @Setter private boolean buildingKeepsAttacking;

  /** Collects the route and resume requests the selection and the visit make. */
  @Getter private final TargetingOutcome outcome = new TargetingOutcome();

  private boolean selectionDone;
  private TargetView selectionResult;

  /**
   * Creates a chain for one unit.
   *
   * @param index the spatial index, rebuilt once per tick before the visit
   * @param state the unit's targeting component
   * @param arenaHeightCells arena length in routing cells
   */
  public SelectionChain(SpatialIndex index, TargetingState state, int arenaHeightCells) {
    this(
        index,
        state,
        arenaHeightCells,
        ValidatorQueries.standard1v1(),
        DefaultSelectionQueries.standard1v1(),
        DefaultTargetSelection.Rules.standard());
  }

  /** Creates a chain with explicit game-mode answers. */
  public SelectionChain(
      SpatialIndex index,
      TargetingState state,
      int arenaHeightCells,
      ValidatorQueries validatorQueries,
      DefaultSelectionQueries defaultSelectionQueries,
      DefaultTargetSelection.Rules defaultSelectionRules) {
    this.index = index;
    this.state = state;
    this.arenaHeightCells = arenaHeightCells;
    this.validatorQueries = validatorQueries;
    this.defaultSelectionQueries = defaultSelectionQueries;
    this.defaultSelectionRules = defaultSelectionRules;
  }

  /** Registers the view the chain uses for one entity. */
  public void register(TargetView view) {
    views.put(view.getEntity(), view);
  }

  /** Registers one of the opposing side's towers, in placement order. */
  public void registerTower(TargetView tower) {
    register(tower);
    registeredTowers.add(tower);
  }

  /** The view of an entity, or null when the entity was never registered. */
  public TargetView view(GridEntity entity) {
    return views.get(entity);
  }

  /**
   * Drops an entity that has left the match, both from the views and, when it was one, from the
   * opposing side's tower list. A chain that keeps a destroyed tower would keep offering it as the
   * default target.
   */
  public void unregister(GridEntity entity) {
    TargetView view = views.remove(entity);
    if (view == null) {
      return;
    }
    registeredTowers.remove(view);
    if (seed == view) {
      seed = null;
    }
    if (state.getReference() == view) {
      state.setReference(null);
    }
  }

  /** Clears the per-tick selection cache and the outcome. Call once, before the visit. */
  public void beginTick() {
    selectionDone = false;
    selectionResult = null;
    outcome.clear();
  }

  // -------------------------------------------------------------------------------------------
  // TargetingQueries
  // -------------------------------------------------------------------------------------------

  @Override
  public TargetView runSelection() {
    if (selectionDone) {
      return selectionResult;
    }
    CandidateSelector.select(state, false, false, this, outcome);
    selectionResult = state.getReference();
    selectionDone = true;
    return selectionResult;
  }

  @Override
  public boolean validateReference(int mode) {
    return validate(state.getReference(), mode);
  }

  @Override
  public boolean buildingKeepsAttacking() {
    return buildingKeepsAttacking;
  }

  @Override
  public HitSink hitSink() {
    return hitSink;
  }

  @Override
  public StateSetter stateSetter() {
    return stateSetter;
  }

  /**
   * Runs the action the owner performs when it starts an attack. Both the targeting visit and the
   * reference setter ask for it through this one chain, so the single override below answers both.
   * No card the grid drives carries such an action yet, so nothing runs.
   */
  @Override
  public void onStartingAttack() {
    // No grid-driven character carries an on-starting-attack action.
  }

  // -------------------------------------------------------------------------------------------
  // SelectionQueries
  // -------------------------------------------------------------------------------------------

  @Override
  public List<TargetView> candidates(int x, int y, int radius) {
    List<GridEntity> found = index.query(SpatialQuery.targetCandidates(x, y, radius));
    if (found == null) {
      return List.of();
    }
    List<TargetView> result = new ArrayList<>(found.size());
    for (GridEntity entity : found) {
      TargetView view = views.get(entity);
      if (view != null) {
        result.add(view);
      }
    }
    index.release(found);
    return result;
  }

  @Override
  public List<TargetView> allCandidates() {
    List<GridEntity> found = index.listQuery(true);
    if (found == null) {
      return List.of();
    }
    List<TargetView> result = new ArrayList<>(found.size());
    for (GridEntity entity : found) {
      TargetView view = views.get(entity);
      if (view != null) {
        result.add(view);
      }
    }
    index.release(found);
    return result;
  }

  @Override
  public boolean validate(TargetView candidate, int mode) {
    return ReferenceValidator.validate(state, candidate, mode, validatorQueries);
  }

  @Override
  public TargetView defaultTarget() {
    return DefaultTargetSelection.selectDefaultTarget(
        state.getOwner().getX(),
        state.getOwner().getY(),
        state.getOwner().getLane(),
        state.getOwner().getDelay(),
        arenaHeightCells,
        seed,
        registeredTowers,
        defaultSelectionRules,
        defaultSelectionQueries,
        candidate -> validate(candidate, ReferenceValidator.MODE_TAKE));
  }
}
