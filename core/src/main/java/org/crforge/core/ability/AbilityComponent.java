package org.crforge.core.ability;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * Runtime ability state attached to an entity. Tracks charge progress, variable damage stage, dash
 * state, hook state, and other dynamic ability state.
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

  // Tracks whether the combat target was locked last tick (for out-of-range reset detection)
  private boolean wasTargetLocked = false;

  // DASH state
  private DashState dashState = DashState.IDLE;
  private float dashTimer = 0f;
  private float dashCooldownTimer = 0f;
  private float dashTargetX = 0f;
  private float dashTargetY = 0f;
  private float dashSpeed = 0f;
  // True once a target entered the acquisition range [minRange, maxRange].
  // While acquired, the target stays valid as long as it is within maxRange.
  private boolean dashCandidateAcquired = false;

  // HOOK state
  private HookState hookState = HookState.IDLE;
  private float hookTimer = 0f;
  private long hookedTargetId = -1;

  // REFLECT: no runtime state needed (passive)

  // TUNNEL state (Miner underground travel)
  private TunnelState tunnelState = TunnelState.INACTIVE;
  private float tunnelTargetX = 0f;
  private float tunnelTargetY = 0f;
  private float tunnelWaypointX = 0f;
  private float tunnelWaypointY = 0f;
  private boolean tunnelUsingWaypoint = false;

  // STEALTH state (Royal Ghost)
  private float stealthFadeTimer = 0f;
  private float stealthRevealTimer = 0f;
  private boolean invisible = false;

  // HIDING state (Tesla)
  private HidingState hidingState = HidingState.UP;
  private float hidingTimer = 0f;
  private float upTimer = 0f;

  // BUFF_ALLY state (on the GiantBuffer entity itself)
  private float buffAllyTimer = 0f;
  private boolean buffAllyActionDelayDone = false;
  private int scaledAddedDamage = 0;
  private int scaledAddedCrownTowerDamage = 0;

  // RANGED_ATTACK state (independent secondary ranged attack, e.g. Goblin Machine rocket)
  private RangedAttackState rangedAttackState = RangedAttackState.IDLE;
  private float rangedAttackTimer = 0f;
  private long rangedAttackTargetId = -1;
  private int scaledRangedDamage = 0;

  public AbilityComponent(AbilityData data) {
    this.data = data;
    // First dash must also wait for the cooldown before triggering
    if (data instanceof DashAbility dash) {
      this.dashCooldownTimer = dash.dashCooldown();
    }
    // Ghost spawns invisible: pre-fill fade timer so it stays invisible,
    // and set reveal timer to 0 so the first attack gets the full grace period.
    if (data instanceof StealthAbility stealth) {
      this.invisible = true;
      this.stealthFadeTimer = stealth.fadeTime();
      this.stealthRevealTimer = 0f;
    }
    // BUFF_ALLY: start with action delay timer
    if (data instanceof BuffAllyAbility buffAlly) {
      this.buffAllyTimer = buffAlly.actionDelay();
      this.buffAllyActionDelayDone = false;
    }
  }

  /** Resets all ability state (called on target loss, stun, etc.) */
  public void reset() {
    chargeTimer = 0f;
    charged = false;
    currentStage = 0;
    stageTimer = 0f;
    lastTargetId = -1;
    wasTargetLocked = false;
  }

  /**
   * Returns the current variable damage stage's damage, or the base damage from the first stage if
   * not yet escalated.
   */
  public int getCurrentStageDamage() {
    if (!(data instanceof VariableDamageAbility varDmg)) {
      return 0;
    }
    List<VariableDamageStage> stages = varDmg.stages();
    if (stages.isEmpty()) {
      return 0;
    }
    if (currentStage >= stages.size()) {
      return stages.get(stages.size() - 1).damage();
    }
    return stages.get(currentStage).damage();
  }

  /** Returns the charge time threshold in seconds. */
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

  public enum TunnelState {
    INACTIVE,
    TUNNELING,
    EMERGED
  }

  public boolean isTunneling() {
    return tunnelState == TunnelState.TUNNELING;
  }

  public enum HidingState {
    HIDDEN,
    REVEALING,
    UP,
    HIDING
  }

  public boolean isHidingUnderground() {
    return hidingState == HidingState.HIDDEN;
  }

  public enum RangedAttackState {
    IDLE,
    WINDING_UP,
    ATTACK_DELAY,
    COOLDOWN
  }
}
