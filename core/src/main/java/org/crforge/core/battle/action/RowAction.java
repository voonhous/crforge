package org.crforge.core.battle.action;

import java.util.function.IntSupplier;
import lombok.Getter;

/** An action whose shared columns come from its row. */
public abstract class RowAction implements BattleAction {

  /** The row's shared columns. */
  @Getter private final ActionRow row;

  protected RowAction(ActionRow row) {
    this.row = row;
  }

  @Override
  public String name() {
    return row.name();
  }

  @Override
  public int phase() {
    return row.phase();
  }

  @Override
  public int delayMs() {
    return row.delayMs();
  }

  @Override
  public boolean singleton() {
    return row.singleton();
  }

  @Override
  public BattleAction nextAction() {
    return row.nextAction();
  }

  @Override
  public boolean nextActionWait() {
    return row.nextActionWait();
  }

  @Override
  public long tags() {
    return row.tags();
  }

  @Override
  public IntSupplier executeIf() {
    return row.executeIf();
  }

  @Override
  public IntSupplier forceStopIf() {
    return row.forceStopIf();
  }

  @Override
  public IntSupplier pausedIf() {
    return row.pausedIf();
  }
}
