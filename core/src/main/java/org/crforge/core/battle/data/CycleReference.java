/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.data;

import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.crforge.core.battle.action.ActionContext;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.BattleAction;

/**
 * A row named again while it is still being built: a reference back into a cycle of rows. The
 * game's rows are one object each, which every row naming one points at, so a group whose part
 * chains back to the group is a loop the data can write. The reference answers as the row it names
 * once that row is built, which is before anything can be scheduled.
 */
final class CycleReference implements BattleAction {

  private final String name;
  private final Supplier<BattleAction> row;

  /**
   * @param name the row's name
   * @param row the row once it is built
   */
  CycleReference(String name, Supplier<BattleAction> row) {
    this.name = name;
    this.row = row;
  }

  /** The row, built. */
  private BattleAction row() {
    BattleAction built = row.get();
    if (built == null) {
      throw new IllegalStateException(name + " is used before it is built");
    }
    return built;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public int phase() {
    return row().phase();
  }

  @Override
  public int delayMs() {
    return row().delayMs();
  }

  @Override
  public boolean singleton() {
    return row().singleton();
  }

  @Override
  public BattleAction nextAction() {
    return row().nextAction();
  }

  @Override
  public boolean nextActionWait() {
    return row().nextActionWait();
  }

  @Override
  public long tags() {
    return row().tags();
  }

  @Override
  public IntSupplier executeIf() {
    return row().executeIf();
  }

  @Override
  public IntSupplier forceStopIf() {
    return row().forceStopIf();
  }

  @Override
  public boolean abortIfInstigatorDies() {
    return row().abortIfInstigatorDies();
  }

  @Override
  public IntSupplier pausedIf() {
    return row().pausedIf();
  }

  @Override
  public void scheduled(
      ActionHolder holder, int delayMs, boolean immediate, ActionHolder instigator) {
    row().scheduled(holder, delayMs, immediate, instigator);
  }

  @Override
  public void scheduled(
      ActionHolder holder,
      int delayMs,
      boolean immediate,
      ActionHolder instigator,
      ActionContext context) {
    row().scheduled(holder, delayMs, immediate, instigator, context);
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return row().start(holder);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    return row().start(holder, instigator);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator, ActionContext context) {
    return row().start(holder, instigator, context);
  }
}
