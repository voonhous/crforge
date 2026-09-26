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
 *
 * <p>Every entity is of a kind, and its id is built from that kind: a kind's entities occupy one
 * band of a million ids, so the holder's id-sorted list runs every area effect before every
 * projectile and every projectile before every character in each of its passes.
 */
public abstract class BattleEntity {

  /** Number of component slots, and therefore of whole-list component passes per tick. */
  public static final int COMPONENT_SLOTS = 4;

  /** Id of an entity the holder has not been handed yet. */
  public static final int UNASSIGNED_ID = 0;

  /** The kind of an area effect. */
  public static final int KIND_AREA_EFFECT = 3;

  /** The kind of a projectile. */
  public static final int KIND_PROJECTILE = 4;

  /** The kind of a character: every troop and every building, the crown towers included. */
  public static final int KIND_CHARACTER = 5;

  /** Which band of ids the entity's kind occupies: one million per kind. */
  public static final int IDS_PER_KIND = 1_000_000;

  /** The entity's kind, which selects the holder's counter its id is taken from. */
  @Getter private final int kind;

  /**
   * Position in the holder's id-sorted list: the kind times a million plus the kind's counter at
   * the moment the entity was handed to the holder. Assigned once and never reused.
   */
  @Getter private int id = UNASSIGNED_ID;

  private final BattleComponent[] components = new BattleComponent[COMPONENT_SLOTS];

  /** Bit {@code n} is set while the component in slot {@code n} is switched on. */
  private int activeBits;

  /**
   * @param kind the entity's kind, one of the {@code KIND_} constants
   */
  protected BattleEntity(int kind) {
    checkArgument(kind >= 0, () -> "an entity's kind is not negative: " + kind);
    this.kind = kind;
  }

  /** Called by the holder when the entity is handed to it. */
  void assignId(int id) {
    checkState(this.id == UNASSIGNED_ID, () -> "entity " + this.id + " already has its id");
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
   * The registration visit an entity handed over inside a pending pass is given at once: the visit
   * of each of its active components, in slot order, and nothing else.
   */
  void registrationVisit() {
    beforeRegistrationVisit();
    for (int slot = 0; slot < COMPONENT_SLOTS; slot++) {
      if (isActive(slot)) {
        components[slot].visit();
      }
    }
  }

  /**
   * Runs at the head of the registration visit, with the entity's id already given: what its
   * components need from the battle before they are visited for the first time.
   */
  protected void beforeRegistrationVisit() {}

  /**
   * Runs once, when the holder admits the entity to its live list. The entity has had its id since
   * it was handed to the holder; everything admitted before it, in this cleanup or an earlier one,
   * is already registered.
   */
  protected void onRegistered() {}

  /**
   * Runs when the holder removes another entity, inside the cleanup that removes it and before the
   * cleanup admits any waiting entity, so nothing here ever holds on to an entity that has left.
   * The removed entity keeps its id.
   *
   * @param removed the entity that has just left the holder
   */
  protected void entityRemoved(BattleEntity removed) {}

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
