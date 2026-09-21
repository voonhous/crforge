package org.crforge.core.battle;

import static org.crforge.core.util.ValidationUtils.checkArgument;
import static org.crforge.core.util.ValidationUtils.checkState;

import lombok.Getter;

/**
 * Anything the {@link EntityHolder} ticks: a container of component slots with two per-tick hooks.
 *
 * <p>The entity itself carries no game rules. What it does in a tick is decided by the components
 * in its slots, by which of them are switched on, and by the two hooks a subclass overrides: the
 * pre-hook that runs before any component of any entity, and the post-hook that runs after all of
 * them.
 */
public abstract class BattleEntity {

  /** Number of component slots, and therefore of whole-list component passes per tick. */
  public static final int COMPONENT_SLOTS = 4;

  /** Id of an entity the holder has not registered yet. */
  public static final int UNASSIGNED_ID = 0;

  /** Position in the holder's id-sorted list; assigned on registration and never reused. */
  @Getter private int id = UNASSIGNED_ID;

  private final BattleComponent[] components = new BattleComponent[COMPONENT_SLOTS];

  /** Bit {@code n} is set while the component in slot {@code n} is switched on. */
  private int activeBits;

  /** Called by the holder when it registers the entity. */
  void assignId(int id) {
    checkState(this.id == UNASSIGNED_ID, () -> "entity " + this.id + " is already registered");
    this.id = id;
  }

  /**
   * Puts a component into the slot its own index names and switches it on.
   *
   * @param component the component to attach; its slot must be empty
   */
  protected void attach(BattleComponent component) {
    int index = component.index();
    checkArgument(
        index >= 0 && index < COMPONENT_SLOTS, () -> "component index out of range: " + index);
    checkState(components[index] == null, () -> "component slot " + index + " is already taken");
    components[index] = component;
    activeBits |= 1 << index;
  }

  /** The component in a slot, or null when the slot is empty. */
  public BattleComponent component(int index) {
    return index >= 0 && index < COMPONENT_SLOTS ? components[index] : null;
  }

  /** Whether the component in a slot is switched on. An empty slot is never active. */
  public boolean isActive(int index) {
    return component(index) != null && (activeBits & (1 << index)) != 0;
  }

  /**
   * Switches one component on or off. A component that is off still has its {@link
   * BattleComponent#refresh()} run on every pass; only its visit stops. An empty slot is left
   * alone.
   */
  public void setActive(int index, boolean active) {
    if (component(index) == null) {
      return;
    }
    if (active) {
      activeBits |= 1 << index;
    } else {
      activeBits &= ~(1 << index);
    }
  }

  /**
   * Runs once, when the holder admits the entity and has just given it its id. Everything admitted
   * before it, in this cleanup or an earlier one, is already registered.
   */
  protected void onRegistered() {}

  /** Runs once per tick before any component of any entity. */
  protected void preHook() {}

  /** Runs once per tick after the component passes of every entity. */
  protected void postHook() {}

  /** The entity's scheduled actions. */
  public EntityActions actions() {
    return EntityActions.NONE;
  }

  /**
   * Whether the holder should drop the entity at its next cleanup. Asked at the head of a tick and
   * again near its end, so an entity that dies during a tick is still in that tick's snapshot and
   * is gone before the next one is taken.
   */
  public abstract boolean isRemovable();
}
