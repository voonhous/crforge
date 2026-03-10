package org.crforge.core.ability;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * Runtime ability state attached to an entity. Tracks charge progress,
 * variable damage stage, dash state, hook state, and other dynamic ability state.
 */
@Getter
@Setter
public class AbilityComponent {

  private final AbilityData data;

  // CHARGE state
  private static final float CHARGE_TIME = 2.5f;
  private float chargeTimer = 0f;
  private boolean charged = false;

  // VARIABLE_DAMAGE state
  private int currentStage = 0;
  private float stageTimer = 0f;

  // Tracks the target ID for reset detection
  private long lastTargetId = -1;

  // DASH state
  private DashState dashState = DashState.IDLE;
  private float dashTimer = 0f;
  private float dashCooldownTimer = 0f;
  private float dashTargetX = 0f;
  private float dashTargetY = 0f;
  private float dashSpeed = 0f;

  // HOOK state
  private HookState hookState = HookState.IDLE;
  private float hookTimer = 0f;
  private long hookedTargetId = -1;

  // REFLECT: no runtime state needed (passive)

  public AbilityComponent(AbilityData data) {
    this.data = data;
  }

  /**
   * Resets all ability state (called on target loss, stun, etc.)
   */
  public void reset() {
    chargeTimer = 0f;
    charged = false;
    currentStage = 0;
    stageTimer = 0f;
    lastTargetId = -1;
  }

  /**
   * Returns the current variable damage stage's damage, or the base damage
   * from the first stage if not yet escalated.
   */
  public int getCurrentStageDamage() {
    List<VariableDamageStage> stages = data.getStages();
    if (stages.isEmpty()) {
      return 0;
    }
    if (currentStage >= stages.size()) {
      return stages.get(stages.size() - 1).damage();
    }
    return stages.get(currentStage).damage();
  }

  /**
   * Returns the charge time threshold in seconds.
   */
  public float getChargeTime() {
    return CHARGE_TIME;
  }

  public boolean isDashing() {
    return dashState == DashState.DASHING;
  }

  public boolean isHooking() {
    return hookState == HookState.PULLING || hookState == HookState.DRAGGING_SELF;
  }

  public enum DashState {
    IDLE,
    DASHING,
    LANDING
  }

  public enum HookState {
    IDLE,
    WINDING_UP,
    PULLING,
    DRAGGING_SELF
  }
}
