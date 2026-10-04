package org.crforge.core.battle.action;

import lombok.Builder;
import lombok.Getter;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * A push the unit it runs on carries ahead of itself, as the evolved Battle Ram's completed charge
 * starts it: every step the run is listed it asks the object query around a point ahead of the
 * unit, along its facing, and pushes and hits each object it finds once per run.
 *
 * <p>The row's own delay holds the run back after the charge completes, and its stop gate ends it
 * once the unit no longer charges; the run never finishes by itself. Each step, for each object
 * found, in the query's order: one already hit by this run is left alone, neither pushed nor hit
 * again. One with a movement component that the push filter accepts is pushed away from the query's
 * centre, or with PushToSide away from its own foot on the line through the centre along the unit's
 * facing, so to the side of that line it stands on; every gate of the request is skipped. Then,
 * unless it is a character that may not be touched, the damage is below 1 or it has no hit points,
 * it is hit for the push damage at the unit's level, through the unit's listening actions, and
 * listed; the first hit of a step also counts as an ended attack for them.
 *
 * <p>Refused as the row is built: tags, a singleton, a next action, the run and pause gates and a
 * phase of its own, which no shipped row sets, and a row without either filter. As it starts: an
 * owner other than a character.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line from the class's update: the centre ahead along the facing, the"
            + " query and its filter, the run's list checked before the push, the push filter,"
            + " the side point and its tie, the push with the gates skipped, the damage at the"
            + " unit's level through its listeners with the run's hit id, the hit once per run and"
            + " the ended-attack notice after the first hit of a step; the delay and the stop gate"
            + " are the runtime's. Held up to the ram's first hit by"
            + " battle_ram_evolved_knight_after_20, battle_ram_evolved_knight_after_50 and"
            + " battle_ram_evolved_knight_after_80, which then part over the ram's own recoil"
            + " after a direct hit. Not modelled: that recoil, the push effect and its interval,"
            + " and the hit's presentation, which only show something.")
public final class DamagingPushBack extends RowAction {

  /**
   * The row's own columns.
   *
   * @param pushBackStrength how far a push carries
   * @param pushBackRadius the radius of the query
   * @param continuousPushBack true to push while a push is in flight, keeping the longer one
   * @param distanceProportionalPush true to take the current separation off a push
   * @param pushBackDamage the damage of a hit, at the first level
   * @param pushToSide true to push away from the line along the facing rather than the centre
   * @param pushRadiusDirectionalOffset how far ahead of the unit, along its facing, the query is
   *     centred
   * @param gameObjectFilter the filter the query asks
   * @param pushFilter the filter an object must also pass to be pushed
   */
  @Builder
  public record Columns(
      int pushBackStrength,
      int pushBackRadius,
      boolean continuousPushBack,
      boolean distanceProportionalPush,
      int pushBackDamage,
      boolean pushToSide,
      int pushRadiusDirectionalOffset,
      GameObjectFilter gameObjectFilter,
      GameObjectFilter pushFilter) {}

  /** The row's own columns. */
  @Getter private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns its own columns
   */
  public DamagingPushBack(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return holder.getOwner().damagingPushBack(this, holder.passPhase());
  }
}
