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
