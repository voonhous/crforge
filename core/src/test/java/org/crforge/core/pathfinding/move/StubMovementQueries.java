package org.crforge.core.pathfinding.move;

import org.crforge.core.pathfinding.grid.Route;

/**
 * A movement query provider whose answers are plain fields, for tests that drive one module at a
 * time.
 *
 * <p>Every answer defaults to what the reference trajectories use; a test sets only the field whose
 * branch it wants to exercise.
 */
class StubMovementQueries implements MovementQueries {

  int speedBudget;
  int facingGate = 1;
  int avoidanceGate;
  int pushGate;
  int routeRequest = 1;
  int attackRange = 1700;
  int farther;
  int ownerSide;
  int air;
  int ground = 1;
  int referenceAvailable = 1;
  int gridWidth = 36;
  int cellTest;
  int relocate;
  int endpoint = -1;
  int gameModeGoal = -1;
  boolean hasModifierComponent;
  int chargeRangeFromModifiers;
  int touchdownModeActive;
  int touchdownEdgeSeparate;
  int pushDisplacementEnabled;
  int facingUpdateSuppressed;
  int hovering;
  Route searchResult = new Route();
  GridMoveEntity entityView = new GridMoveEntity(5, 1, false, false);

  @Override
  public Route search(int startCol, int startRow, int goalCol, int goalRow, int adjust) {
    return searchResult;
  }

  @Override
  public int endpoint(int referenceCol, int referenceRow, int radius) {
    return endpoint;
  }

  @Override
  public int relocate(int x, int y) {
    return relocate;
  }

  @Override
  public int cellTest(int worldX, int worldY) {
    return cellTest;
  }

  @Override
  public int speedBudget() {
    return speedBudget;
  }

  @Override
  public int facingGate() {
    return facingGate;
  }

  @Override
  public int avoidanceGate() {
    return avoidanceGate;
  }

  @Override
  public int pushGate() {
    return pushGate;
  }

  @Override
  public int routeRequest() {
    return routeRequest;
  }

  @Override
  public int attackRange() {
    return attackRange;
  }

  @Override
  public int farther() {
    return farther;
  }

  @Override
  public int ownerSide() {
    return ownerSide;
  }

  @Override
  public int air() {
    return air;
  }

  @Override
  public int ground() {
    return ground;
  }

  @Override
  public int referenceAvailable() {
    return referenceAvailable;
  }

  @Override
  public int gridWidth() {
    return gridWidth;
  }

  @Override
  public GridMoveEntity entityView() {
    return entityView;
  }

  @Override
  public int gameModeGoal() {
    return gameModeGoal;
  }

  @Override
  public boolean hasModifierComponent() {
    return hasModifierComponent;
  }

  @Override
  public int chargeRangeFromModifiers() {
    return chargeRangeFromModifiers;
  }

  @Override
  public int touchdownModeActive() {
    return touchdownModeActive;
  }

  @Override
  public int touchdownEdgeSeparate() {
    return touchdownEdgeSeparate;
  }

  @Override
  public int pushDisplacementEnabled() {
    return pushDisplacementEnabled;
  }

  @Override
  public int facingUpdateSuppressed() {
    return facingUpdateSuppressed;
  }

  @Override
  public int hovering() {
    return hovering;
  }
}
