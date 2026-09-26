package org.crforge.core.battle.action;

import java.util.function.IntSupplier;
import lombok.Builder;

/**
 * The columns every action row shares, whatever its class: its name, the pass that starts it, its
 * own delay, whether a second start re-triggers its run, the action chained to it and when, the
 * tags its run sets, and its three gates.
 *
 * @param name the row's name
 * @param phase the pending pass that starts it, or {@link BattleAction#ANY_PHASE}
 * @param delayMs its own delay in milliseconds
 * @param singleton true when a second start re-triggers its listed run
 * @param nextAction the action chained to it, or null
 * @param nextActionWait true when the chained action is scheduled once it has run
 * @param tags the tags its run sets while listed
 * @param executeIf the start gate, or null for none
 * @param forceStopIf the stop gate, or null for none
 * @param pausedIf the queue gate, or null for none
 */
@Builder(toBuilder = true)
public record ActionRow(
    String name,
    int phase,
    int delayMs,
    boolean singleton,
    BattleAction nextAction,
    boolean nextActionWait,
    long tags,
    IntSupplier executeIf,
    IntSupplier forceStopIf,
    IntSupplier pausedIf) {

  /** A row with a name and every other column left out. */
  public static ActionRow named(String name) {
    return builder().name(name).build();
  }
}
