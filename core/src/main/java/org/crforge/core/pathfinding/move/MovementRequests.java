package org.crforge.core.pathfinding.move;

/**
 * What the movement pass asks of the entity's owner while it runs, at the moment it asks: a state
 * change, the notice that the charge has just completed, and the landing of a dash.
 *
 * <p>A state change is applied at once, through the owner's state setter, so the rest of the same
 * visit sees the new state: a unit that starts a jump displaces its later steps as a jumper, and a
 * unit that lands has its route prepared from the point it landed on.
 */
public interface MovementRequests {

  /**
   * Asks for a state change, which the state setter applies at once with its exit and entry
   * actions.
   *
   * @param state the state asked for
   */
  void requestState(int state);

  /**
   * Tells the owner that the displacement just made completed the entity's charge. The action a
   * completed charge runs is announced here; an owner with none has nothing to do.
   */
  default void chargeCompleted() {}

  /**
   * Has the owner land the dash that just ended: its landing hit, then the moving state or its
   * landing hold. An owner without one has nothing to do.
   */
  default void dashLanded() {}
}
