package gg.vape.module.utility;

import gg.vape.event.EventHandler;
import gg.vape.event.impl.EventPreTick;
import gg.vape.input.KeyBindingInputState;
import gg.vape.module.Category;
import gg.vape.module.Mod;
import gg.vape.movement.MovementInputHelper;
import gg.vape.rotation.FixedRotationController;
import gg.vape.rotation.RotationManager;
import gg.vape.utils.RotationUtil;
import gg.vape.value.BooleanValue;
import gg.vape.value.NumberValue;
import gg.vape.wrapper.impl.Minecraft;

/**
 * Telly - fused: Myau Telly script (21-phase cycle + activation) on the Vape
 * native engine (managed rotation + useItem placement).
 *  - Activation: sneak + hold RMB + look down (pitch>=75) + yaw-aligned -> red->green -> begin.
 *  - Cycle: Myau 21-phase yaw/pitch/forward/strafe curves.
 *  - Rotation: Vape managed rotation (FixedRotationController + RotationManager).
 *  - Movement: MovementInputHelper.
 *  - Placement: aim at bridge point (atan2) + useItem (simulated right-click).
 * Render (red/green prompt) arrives in a later pass.
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

    private boolean armed = false;
    private boolean running = false;
    private int cyclePhase = 19;
    private float baseYaw = 0.0f;
    private int travelX = 0;
    private int travelZ = 0;
    private float scriptedRotationYaw = 0.0f;
    private float scriptedRotationPitch = 0.0f;
    private FixedRotationController rotationController;
    private int debugTick = 0;

    public Telly() {
        super("Telly", 0xffff4d4d, Category.UTILITY, "Scripted bridge automation (Myau-fused)");
        this.autoSwap = BooleanValue.create(this, "auto-swap", true, "Automatically swap to the best item");
        this.disableSafeWalk = BooleanValue.create(this, "disable-safewalk", true, "Disable SafeWalk while a script is running");
        this.showActivationHitbox = BooleanValue.create(this, "show-activation-hitbox", false, "Render the script activation hitbox");
        this.print = BooleanValue.create(this, "print", false, "Print script debug info to chat");
        this.addValue(this.autoSwap, this.disableSafeWalk, this.showActivationHitbox, this.print);
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
        this.running = false;
        this.armed = false;
        if (this.rotationController != null) {
            RotationManager.INSTANCE.releaseController(this.rotationController);
            this.rotationController = null;
        }
        MovementInputHelper.releaseMovementKeys();
    }

    @EventHandler
    public void onTick(EventPreTick event) {
        if (!this.isEnabled() || !this.armed) return;
        if (Minecraft.thePlayer().isNull() || Minecraft.theWorld().isNull()) return;
        if (this.running) {
            this.advanceCycle();
            this.applyRotation();
            this.applyMovement();
            this.tryPlace();
        } else {
            this.onActivationTick();
        }
    }

    // ---- Activation (Myau: sneak + RMB + look down + yaw aligned -> begin) ----
    private void onActivationTick() {
        boolean sneak = Minecraft.thePlayer().movementInput().D$src$Z$v5d6e8();
        boolean rmb = KeyBindingInputState.isMouseButtonDown(1);
        boolean yawAligned = this.isActivationYawAligned(RotationUtil.c());
        float pitch = Minecraft.thePlayer().V();
        boolean lookingDown = pitch >= ACTIVATION_PITCH;
        if (this.showActivationHitbox.getEffectiveValue()) {
            this.debugTick++;
            if (this.debugTick % 200 == 0) {
                this.sendDebug("Telly[act] sneak=" + sneak + " rmb=" + rmb + " yaw=" + yawAligned + " pitch=" + pitch);
            }
        }
        if (sneak && rmb && yawAligned && lookingDown) {
            this.beginAutomation();
        }
    }

    private boolean isActivationYawAligned(float yaw) {
        float nearestDiagonal = Math.round((yaw - 45.0f) / 90.0f) * 90.0f + 45.0f;
        return Math.abs(this.tellyWrapAngle(yaw - nearestDiagonal)) <= ACTIVATION_YAW_TOLERANCE;
    }

    private void beginAutomation() {
        this.baseYaw = Math.round((RotationUtil.c() - 45.0f) / 90.0f) * 90.0f + 45.0f;
        this.calculateTravelDirection(this.baseYaw);
        this.cyclePhase = 19;
        this.scriptedRotationYaw = this.baseYaw;
        this.scriptedRotationPitch = SCRIPT_PITCH;
        this.armed = false;
        this.running = true;
        this.rotationController = new FixedRotationController(this.baseYaw, SCRIPT_PITCH);
        RotationManager.INSTANCE.setController(this.rotationController);
    }

    // ---- Cycle (Myau 21-phase) ----
    private void advanceCycle() {
        int phase = this.cyclePhase;
        int next = (phase + 1) % CYCLE_LENGTH;
        this.cyclePhase = next;
    }

    private void applyRotation() {
        int phase = this.cyclePhase;
        int next = (phase + 1) % CYCLE_LENGTH;
        if (this.rotationController != null) {
            this.rotationController.setTargetRotation(this.baseYaw + YAW_CURVE[next], PITCH_CURVE[next]);
        }
    }

    private void applyMovement() {
        int phase = this.cyclePhase;
        float forward = FORWARD_CURVE[phase];
        float strafe = STRAFE_CURVE[phase];
        boolean jumping = phase >= 1 && phase <= 19;
        MovementInputHelper.synchronizeDirectionalInput(forward > 0.03f, forward < -0.03f, strafe < -0.03f, strafe > 0.03f);
        MovementInputHelper.setJumpPressed(jumping);
    }

    // ---- Placement (Vape native: aim at bridge point + useItem) ----
    private void tryPlace() {
        int phase = this.cyclePhase;
        boolean use = phase >= 7;
        if (!use) return;
        double px = Minecraft.thePlayer().z();
        double py = Minecraft.thePlayer().N();
        double pz = Minecraft.thePlayer().h();
        double tx = px - this.travelX * 1.0;
        double ty = Math.floor(py) - 1.0;
        double tz = pz - this.travelZ * 1.0;
        this.aimAtPoint(tx, ty + 1.0, tz);
        Minecraft.gameSettings().b$src$Lgg_vape_wrapper_impl_KeyBinding_$1yi3362().onTick(1);
    }

    private void aimAtPoint(double tx, double ty, double tz) {
        double px = Minecraft.thePlayer().z();
        double py = Minecraft.thePlayer().N() + 1.62;
        double pz = Minecraft.thePlayer().h();
        double dx = tx - px;
        double dy = ty - py;
        double dz = tz - pz;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0E-5 && Math.abs(dy) < 1.0E-5) return;
        float yaw = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = clamp((float)(-Math.toDegrees(Math.atan2(dy, horizontal))), -89.0f, 89.0f);
        if (this.rotationController != null) {
            this.rotationController.setTargetRotation(yaw, pitch);
        }
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

    private void sendDebug(String message) {
        try { Minecraft.thePlayer().sendChatMessage(message); } catch (Exception ignored) {}
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