/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import java.util.List;
import lombok.Builder;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that writes what its target resolver finds into the context it runs with, as the
 * Balloon hero's ability names the enemy its skeleton trooper falls on: the found object's id, or
 * the row's default value when nothing is found, under the key of its result name.
 *
 * <p>When it starts with a context and a key, the resolver collects around the owner's position as
 * {@link RunOnResolvedObjects} does - a Global shape's census, or the circle query of a Cone or a
 * Circle shape through the resolver's filter asked by the owner, the cone then keeping what it
 * keeps - and the strategies narrow the pool in turn to the one object found. Its id goes to the
 * main board, or to the scratch board under UseScratchBlackboard. Without a context, or with a key
 * of hash 0, it does nothing at all; a row without a resolver writes the default. It does not last.
 *
 * <p>The key is the hash of the result name as the row loads it, an empty name included.
 * UseDefaultValue is loaded and never read: nothing found always writes the default.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: nothing without a context or with a zero key, the default without"
            + " a resolver or with nothing found, the found object's id otherwise, the board by"
            + " UseScratchBlackboard; the resolver asked at the owner's position through the shared"
            + " single pick. Held by hero_balloon and BattleBalloonHeroTest.")
public final class WriteResolverResultToContext extends RowAction {

  /**
   * The row's columns besides the shared ones.
   *
   * @param resolver the target resolver's row name, or null for none
   * @param filter the resolver's filter, or null without a resolver
   * @param cone the resolver's Cone shape, a Circle one as a cone that keeps every angle, or null
   *     for a Global one
   * @param strategies the resolver's strategies, as the data names them
   * @param useScratch true to write to the scratch board, false for the main board
   * @param defaultValue the value written when nothing is found
   * @param key the key: the hash of the result name
   */
  @Builder
  public record Columns(
      String resolver,
      GameObjectFilter filter,
      ConeShape cone,
      List<String> strategies,
      boolean useScratch,
      int defaultValue,
      int key) {

    /** Copies the strategies. */
    public Columns {
      strategies = strategies == null ? List.of() : List.copyOf(strategies);
    }
  }

  private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns its own columns
   */
  public WriteResolverResultToContext(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  /** The row's own columns. */
  public Columns columns() {
    return columns;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    return start(holder, instigator, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator, ActionContext context) {
    if (context == null || columns.key() == 0) {
      return null;
    }
    int value = columns.defaultValue();
    if (columns.resolver() != null) {
      RunOnResolvedObjects.Host host = holder.getOwner().resolvedObjectsHost(this);
      List<SetIndicatorOnTarget.Candidate> pool =
          columns.cone() == null
              ? host.candidates(columns.filter())
              : host.candidates(columns.filter(), columns.cone());
      SetIndicatorOnTarget.Candidate found =
          RunOnResolvedObjects.pick(host, pool, columns.strategies(), name(), columns.resolver());
      if (found != null) {
        value = found.id();
      }
    }
    context.write(columns.useScratch(), columns.key(), value);
    return null;
  }
}
