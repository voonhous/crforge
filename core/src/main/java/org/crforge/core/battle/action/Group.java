/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import java.util.List;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that schedules its parts the moment it is scheduled, in list order, each at the group's
 * delay plus its own entry of the delay list - a part past the end of that list at the group's
 * delay alone. Its own start does nothing, and a false start gate stops it scheduling anything.
 *
 * <p>Its context mode decides the context the parts carry: none at all, whatever the group was
 * scheduled with; a context made for this schedule of the group; or the context the group was
 * scheduled with, and one made for it when it came with none.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled and held by the recorded cases: the parts scheduled when the group is scheduled,"
            + " in order, at the group's delay plus their own, none past a false start gate, and"
            + " the group's own empty start. The context the parts carry by the three modes - none,"
            + " made, handed on or made - held by pekka-resurrect-v2.")
public final class Group extends RowAction {

  /** What the group hands its parts as their context. */
  public enum ContextMode {
    /** No context, whatever the group was scheduled with. */
    NONE,
    /** A context made for each schedule of the group. */
    CREATE,
    /** The context the group was scheduled with, or one made for it when it came with none. */
    INHERIT
  }

  private final List<BattleAction> parts;
  private final List<Integer> partDelaysMs;
  private final ContextMode contextMode;

  /**
   * A group that hands its parts no context.
   *
   * @param row the row's shared columns
   * @param parts the actions the group schedules
   * @param partDelaysMs each part's delay on top of the group's, in milliseconds
   */
  public Group(ActionRow row, List<BattleAction> parts, List<Integer> partDelaysMs) {
    this(row, parts, partDelaysMs, ContextMode.NONE);
  }

  /**
   * @param row the row's shared columns
   * @param parts the actions the group schedules
   * @param partDelaysMs each part's delay on top of the group's, in milliseconds
   * @param contextMode what the group hands its parts as their context
   */
  public Group(
      ActionRow row,
      List<BattleAction> parts,
      List<Integer> partDelaysMs,
      ContextMode contextMode) {
    super(row);
    this.parts = List.copyOf(parts);
    this.partDelaysMs = List.copyOf(partDelaysMs);
    this.contextMode = contextMode;
  }

  @Override
  public void scheduled(
      ActionHolder holder, int delayMs, boolean immediate, ActionHolder instigator) {
    scheduled(holder, delayMs, immediate, instigator, null);
  }

  @Override
  public void scheduled(
      ActionHolder holder,
      int delayMs,
      boolean immediate,
      ActionHolder instigator,
      ActionContext context) {
    // The start gate is asked with the context the group was scheduled with.
    if (executeIf() != null && executeIf().getAsInt() == 0) {
      return;
    }
    ActionContext handed =
        switch (contextMode) {
          case NONE -> null;
          case CREATE -> new ActionContext();
          case INHERIT -> context != null ? context : new ActionContext();
        };
    for (int i = 0; i < parts.size(); i++) {
      int own = i < partDelaysMs.size() ? partDelaysMs.get(i) : 0;
      holder.schedule(parts.get(i), delayMs + own, immediate, instigator, handed);
    }
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return null;
  }
}
