package org.crforge.core.ability.handler;

import lombok.Setter;
import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.ability.TunnelAbility;
import org.crforge.core.ability.TunnelMorphHandler;
import org.crforge.core.component.Combat;
import org.crforge.core.component.ModifierSource;
import org.crforge.core.component.Position;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.unit.Troop;

/** Handles the TUNNEL ability (Miner underground travel). */
public class TunnelHandler implements AbilityHandler {

  /** Tolerance for diagonal vs cardinal direction selection. */
  private static final float DIRECTION_TOLERANCE = 0.2f;

  /** Distance threshold for switching from waypoint to final target. */
  private static final float WAYPOINT_ARRIVAL_DISTANCE = 0.5f;

  /** Distance threshold for emerging at the final target. */
  private static final float EMERGE_DISTANCE = 1.0f;

  @Setter private TunnelMorphHandler tunnelMorphHandler;

  @Override
  public void update(Entity entity, AbilityComponent ability, float deltaTime) {
    if (!(entity instanceof Troop troop)) {
      return;
    }

    if (ability.getTunnelState() != AbilityComponent.TunnelState.TUNNELING) {
      return;
    }

    TunnelAbility data = (TunnelAbility) ability.getData();
    float speed = data.tunnelSpeed() * deltaTime;

    // Determine current target: waypoint if still routing, else final target
    float destX;
    float destY;
    if (ability.isTunnelUsingWaypoint()) {
      destX = ability.getTunnelWaypointX();
      destY = ability.getTunnelWaypointY();
    } else {
      destX = ability.getTunnelTargetX();
      destY = ability.getTunnelTargetY();
    }

    Position pos = troop.getPosition();
    float dx = destX - pos.getX();
    float dy = destY - pos.getY();

    // 8-directional movement matching reference JS pattern
    float absDx = Math.abs(dx);
    float absDy = Math.abs(dy);

    float moveX;
    float moveY;
    if (absDx > absDy + DIRECTION_TOLERANCE) {
      // Cardinal horizontal
      moveX = Math.signum(dx) * speed;
      moveY = 0;
    } else if (absDy > absDx + DIRECTION_TOLERANCE) {
      // Cardinal vertical
      moveX = 0;
      moveY = Math.signum(dy) * speed;
    } else {
      // Diagonal: speed * sqrt(2)/2 along both axes
      float diag = speed * 0.7071f;
      moveX = Math.signum(dx) * diag;
      moveY = Math.signum(dy) * diag;
    }

    pos.set(pos.getX() + moveX, pos.getY() + moveY);

    // Check waypoint arrival
    if (ability.isTunnelUsingWaypoint()) {
      float distToWaypoint = pos.distanceTo(destX, destY);
      if (distToWaypoint < WAYPOINT_ARRIVAL_DISTANCE) {
        ability.setTunnelUsingWaypoint(false);
      }
      return;
    }

    // Check final target arrival
    float distToTarget = pos.distanceTo(ability.getTunnelTargetX(), ability.getTunnelTargetY());
    if (distToTarget < EMERGE_DISTANCE) {
      emergeTunnel(troop, ability);
    }
  }

  /** Completes the tunnel travel: snap to target, become targetable, enter deploy animation. */
  private void emergeTunnel(Troop troop, AbilityComponent ability) {
    float targetX = ability.getTunnelTargetX();
    float targetY = ability.getTunnelTargetY();

    // Morph path: dig troop transforms into a building (e.g. GoblinDrillDig -> GoblinDrill)
    if (troop.getMorphCard() != null && tunnelMorphHandler != null) {
      tunnelMorphHandler.onTunnelMorph(troop, targetX, targetY);
      return;
    }

    troop.getPosition().set(targetX, targetY);
    ability.setTunnelState(AbilityComponent.TunnelState.EMERGED);
    troop.setTunneling(false);
    troop.setInvulnerable(false);

    // Clear tunnel modifiers from movement and combat
    troop.getMovement().clearModifiers(ModifierSource.ABILITY_TUNNEL);
    Combat combat = troop.getCombat();
    if (combat != null) {
      combat.setCurrentTarget(null);
      combat.clearModifiers(ModifierSource.ABILITY_TUNNEL);
    }

    // Reset deploy timer so the Miner goes through a normal deploy animation at the target.
    // This gives a counterplay window: targetable but cannot attack.
    troop.setDeployTimer(troop.getDeployTime());
  }
}
