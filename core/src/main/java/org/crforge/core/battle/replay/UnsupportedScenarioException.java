/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.replay;

/**
 * A scenario input the production simulator has no mapping for: the run is reported unsupported,
 * naming the missing feature and the input that needs it, and no observation is written.
 */
public final class UnsupportedScenarioException extends RuntimeException {

  private final String feature;
  private final String input;

  /**
   * @param feature the production feature the scenario needs
   * @param input the scenario field and value that needs it
   */
  public UnsupportedScenarioException(String feature, String input) {
    super(feature + ": " + input);
    this.feature = feature;
    this.input = input;
  }

  /** The production feature the scenario needs. */
  public String feature() {
    return feature;
  }

  /** The scenario field and value that needs it. */
  public String input() {
    return input;
  }
}
