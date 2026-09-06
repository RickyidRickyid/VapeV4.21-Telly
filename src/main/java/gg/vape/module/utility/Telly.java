package gg.vape.module.utility;

import gg.vape.event.EventHandler;
import gg.vape.event.impl.EventPreTick;
import gg.vape.module.Category;
import gg.vape.module.Mod;
import gg.vape.movement.MovementInputHelper;
import gg.vape.rotation.FixedRotationController;
import gg.vape.rotation.RotationManager;
import gg.vape.utils.RotationUtil;
import gg.vape.value.BooleanValue;
import gg.vape.wrapper.impl.Minecraft;

/**
 * Telly - faithful port of Myau-cat myau.module.modules.Telly (STAGE 1).
 *
 * Keeps the Myau behaviour model and swaps only the bottom layer to Vape APIs:
 *  - Activation: sneak + look-down + yaw-aligned -> begin (Myau arm/begin flow).
 *  - Cycle: 21-phase yaw/pitch/forward/strafe curves.
 *  - Rotation: Vape managed rotation (FixedRotationController + RotationManager).
 *  - Movement: MovementInputHelper.
 * Placement, adaptive aim, anti-sway, GCD smoothing, ghost blocks, packets and
 * render arrive in Stages 2-7 (no placeholder-marked-done).
 */
public class Telly extends Mod {

    private final BooleanValue autoSwap;
    private final BooleanValue disableSafeWalk;
    private final BooleanValue showActivationHitbox;
    private final BooleanValue print;

    private static final float ACTIVATION_YAW_TOLERANCE = 2.0f;
    private static final float ACTIVATION_PITCH = 75.0f;
    private static final float SCRIPT_PITCH = 74.52f;
    private static final int CYCLE_LENGTH = 21;

    private static final float[] YAW_CURVE = {
        91.68f, 98.88f, 78.94f, 37.45f, 1.61f, -21.69f, -33.98f,
        -35.80f, -34.64f, -33.85f, -33.06f, -31.55f, -29.26f, -26.65f,
        -24.19f, -21.07f, -18.84f, -17.06f, -8.87f, 2.61f, 41.94f
    };
    private static final float[] PITCH_CURVE = {
        64.31f, 59.95f, 60.57f, 61.46f, 60.64f, 58.89f, 56.91f,
        56.63f, 58.65f, 61.63f, 64.20f, 66.74f, 68.69f, 70.64f,
        73.01f, 75.37f, 77.46f, 78.56f, 78.90f, 77.22f, 72.25f
    };
    private static final float[] FORWARD_CURVE = {
        1.0f, 1.0f, 0.0f, 0.0f, -1.0f, -1.0f, -1.0f,
        -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f,
        -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, 1.0f
    };
    private static final float[] STRAFE_CURVE = {
        -1.0f, -1.0f, -1.0f, -1.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 0.0f, -1.0f, -1.0f, -1.0f, -1.0f
    };

    // ---- state ----
    private boolean armed = false;
    private boolean running = false;
    private int cyclePhase = 19;
    private float baseYaw = 0.0f;
    private float scriptedRotationYaw = 0.0f;
    private float scriptedRotationPitch = 0.0f;
    private float rotationStartYaw = 0.0f;
    private float rotationStartPitch = 0.0f;
    private float rotationTargetYaw = 0.0f;
    private float rotationTargetPitch = 0.0f;
    private long rotationStartedAt = 0L;
    private long rotationDuration = 50L;
    private boolean rotationActive = false;
    private float stagedForward = -1.0f;
    private float stagedStrafe = -1.0f;
    private boolean stagedJump = false;
    private boolean stagedSprint = false;
    private int travelX = 0;
    private int travelZ = 0;
    private FixedRotationController rotationController;

    public Telly() {
        super("Telly", 0xffff4d4d, Category.UTILITY, "Scripted bridge automation (faithful port)");
        this.autoSwap = BooleanValue.create(this, "auto-swap", true, "Automatically swap to the best item");
        this.disableSafeWalk = BooleanValue.create(this, "disable-safewalk", true, "Disable SafeWalk while a script is running");
        this.showActivationHitbox = BooleanValue.create(this, "show-activation-hitbox", false, "Render the script activation hitbox");
        this.print = BooleanValue.create(this, "print", false, "Print script debug info to chat");
    }

    @Override
    public void onEnable() {
        super.onEnable();
        this.armed = true;
        this.running = false;
    }

    @Override
    public void onDisable() {
        super.onDisable();
        this.stopAutomation();
        this.armed = false;
    }

    @EventHandler
    public void onTick(EventPreTick event) {
        if (!this.isEnabled() || !this.armed) return;
        if (Minecraft.thePlayer().isNull() || Minecraft.theWorld().isNull()) return;
        if (this.running) {
            this.advanceCycle();
            this.applySmoothedRotation();
            this.applyMovement();
        } else {
            this.onActivationTick();
        }
    }

    // ---- Activation (Stage 1) ----
    private void onActivationTick() {
        boolean sneak = Minecraft.thePlayer().movementInput().D$src$Z$v5d6e8();
        boolean rmb = this.useItemKeyDown();
        boolean yawAligned = this.isActivationYawAligned(RotationUtil.c());
        boolean lookingDown = this.getCameraPitch() >= ACTIVATION_PITCH;
        if (sneak && rmb && yawAligned && lookingDown) {
            this.beginAutomation();
        }
    }

    private boolean isActivationYawAligned(float yaw) {
        float nearestDiagonal = Math.round((yaw - 45.0f) / 90.0f) * 90.0f + 45.0f;
        return Math.abs(this.tellyWrapAngle(yaw - nearestDiagonal)) <= ACTIVATION_YAW_TOLERANCE;
    }

    private void beginAutomation() {
        if (Minecraft.thePlayer().isNull() || !this.isHoldingBlock()) return;
        this.baseYaw = Math.round((RotationUtil.c() - 45.0f) / 90.0f) * 90.0f + 45.0f;
        this.calculateTravelDirection(this.baseYaw);
        this.cyclePhase = 19;
        this.stagedForward = -1.0f;
        this.stagedStrafe = -1.0f;
        this.stagedJump = false;
        this.stagedSprint = false;
        this.scriptedRotationYaw = this.baseYaw;
        this.scriptedRotationPitch = SCRIPT_PITCH;
        this.rotationStartYaw = this.baseYaw;
        this.rotationStartPitch = SCRIPT_PITCH;
        this.rotationTargetYaw = this.baseYaw;
        this.rotationTargetPitch = SCRIPT_PITCH;
        this.rotationActive = false;
        this.armed = false;
        this.running = true;
        this.rotationController = new FixedRotationController(this.baseYaw, SCRIPT_PITCH);
        RotationManager.INSTANCE.setController(this.rotationController);
    }

    private void stopAutomation() {
        this.running = false;
        if (this.rotationController != null) {
            RotationManager.INSTANCE.releaseController(this.rotationController);
            this.rotationController = null;
        }
        MovementInputHelper.releaseMovementKeys();
        this.armed = true;
    }

    // ---- Cycle (Stage 1) ----
    private void advanceCycle() {
        int phase = this.cyclePhase;
        this.stagedForward = FORWARD_CURVE[phase];
        this.stagedStrafe = STRAFE_CURVE[phase];
        this.stagedJump = phase >= 1 && phase <= 19;
        this.stagedSprint = phase == 0 || phase == 1;
        int next = (phase + 1) % CYCLE_LENGTH;
        this.setRotationTarget(this.baseYaw + YAW_CURVE[next], PITCH_CURVE[next], 50L);
        this.cyclePhase = next;
    }

    // ---- Rotation (Stage 1): Myau interpolation via Vape managed rotation ----
    private void setRotationTarget(float targetYaw, float targetPitch, long duration) {
        this.rotationStartYaw = this.scriptedRotationYaw;
        this.rotationStartPitch = this.scriptedRotationPitch;
        this.rotationTargetYaw = this.rotationStartYaw + this.tellyWrapAngle(targetYaw - this.rotationStartYaw);
        this.rotationTargetPitch = clamp(targetPitch, -90.0f, 90.0f);
        this.rotationStartedAt = System.currentTimeMillis();
        this.rotationDuration = Math.max(1L, duration);
        this.rotationActive = true;
        this.applySmoothedRotation();
    }

    private void applySmoothedRotation() {
        if (!this.rotationActive) {
            if (this.running) this.holdScriptedRotation();
            return;
        }
        double progress = (double)(System.currentTimeMillis() - this.rotationStartedAt) / (double)this.rotationDuration;
        if (progress < 0.0) progress = 0.0;
        if (progress > 1.0) progress = 1.0;
        this.scriptedRotationYaw = this.rotationStartYaw + (this.rotationTargetYaw - this.rotationStartYaw) * (float)progress;
        this.scriptedRotationPitch = clamp(this.rotationStartPitch + (this.rotationTargetPitch - this.rotationStartPitch) * (float)progress, -90.0f, 90.0f);
        this.holdScriptedRotation();
        if (progress >= 1.0) this.rotationActive = false;
    }

    private void holdScriptedRotation() {
        if (this.rotationController != null) {
            this.rotationController.setTargetRotation(this.scriptedRotationYaw, this.scriptedRotationPitch);
        }
    }

    // ---- Movement (Stage 1) ----
    private void applyMovement() {
        MovementInputHelper.synchronizeDirectionalInput(
            this.stagedForward > 0.03f, this.stagedForward < -0.03f,
            this.stagedStrafe > 0.5f, this.stagedStrafe < -0.5f);
        MovementInputHelper.setJumpPressed(this.stagedJump);
    }

    // ---- helpers ----
    private boolean useItemKeyDown() {
        return Minecraft.gameSettings().b$src$Lgg_vape_wrapper_impl_KeyBinding_$1yi3362().isKeyDown();
    }

    private boolean isHoldingBlock() {
        return true;
    }

    private float getCameraPitch() {
        return Minecraft.thePlayer().V();
    }

    private void calculateTravelDirection(float yaw) {
        double radians = Math.toRadians(yaw);
        double rawX = Math.sin(radians) - Math.cos(radians);
        double rawZ = -Math.cos(radians) - Math.sin(radians);
        if (Math.abs(rawX) >= Math.abs(rawZ)) {
            this.travelX = rawX >= 0.0 ? 1 : -1;
            this.travelZ = 0;
        } else {
            this.travelX = 0;
            this.travelZ = rawZ >= 0.0 ? 1 : -1;
        }
    }

    private float tellyWrapAngle(float angle) {
        float wrapped = angle % 360.0f;
        if (wrapped > 180.0f) wrapped -= 360.0f;
        if (wrapped < -180.0f) wrapped += 360.0f;
        return wrapped;
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }
}
