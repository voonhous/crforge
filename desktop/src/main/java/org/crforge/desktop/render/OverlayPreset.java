/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render;

/** Complete overlay presets; individual switches remain independently adjustable. */
public enum OverlayPreset {
  CLEAN("Clean"),
  COMBAT("Combat"),
  PATHING("Pathing");
  private final String label;

  OverlayPreset(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }
}
