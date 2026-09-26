package org.crforge.core.battle.action;

/** A run that keeps a counter, which its tests and observers can read. */
public interface CountingRun {

  /** The run's counter, in the unit its class keeps it in. */
  long counter();
}
