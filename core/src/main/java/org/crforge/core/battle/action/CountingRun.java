/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

/** A run that keeps a counter, which its tests and observers can read. */
public interface CountingRun {

  /** The run's counter, in the unit its class keeps it in. */
  long counter();
}
