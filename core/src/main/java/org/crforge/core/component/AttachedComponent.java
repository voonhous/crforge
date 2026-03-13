package org.crforge.core.component;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.crforge.core.entity.base.Entity;

/**
 * Component for units that are permanently attached to a parent entity (e.g. Ram Rider on Ram,
 * Spear Goblins on Goblin Giant). Attached units move with their parent, cannot be independently
 * targeted, and die when the parent dies.
 */
@Getter
@RequiredArgsConstructor
public class AttachedComponent {

  /** The parent entity this unit is attached to. */
  private final Entity parent;

  /** Local X offset relative to the parent's center. */
  private final float offsetX;

  /** Local Y offset relative to the parent's center. */
  private final float offsetY;

  /** Returns true if the parent entity is still alive. */
  public boolean isParentAlive() {
    return parent != null && parent.isAlive();
  }
}
