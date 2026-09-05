package gg.vape.module.utility;

import gg.vape.event.EventHandler;
import gg.vape.event.impl.EventPreTick;
import gg.vape.module.Category;
import gg.vape.module.Mod;
import gg.vape.movement.MovementInputHelper;
import gg.vape.value.BooleanValue;
import gg.vape.wrapper.impl.Minecraft;

/**
 * Telly - scripted bridge automation (ported from Myau-cat myau.module.modules.Telly).
 *
 * CHUNK 1 of the incremental port: the core 21-phase scripted bridge cycle.
 * Each tick advances the phase and applies the scripted directional movement
 * (forward/strafe/jump) from the curve arrays. Rotation, auto-placement,
 * adaptive aim, anti-sway, GCD smoothing, packet handling and the activation
 * flow are added in subsequent commits.
 */
public class Telly extends Mod {

    private final BooleanValue autoSwap;
    private final BooleanValue disableSafeWalk;
    private final BooleanValue showActivationHitbox;
    private final BooleanValue print;

    // ---- 21-phase scripted bridge cycle (ported verbatim from Myau Telly) ----
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

    private int cyclePhase = 19;
    private boolean running = false;

    public Telly() {
        super("Telly", 0xffff4d4d, Category.UTILITY, "Scripted bridge automation (ported from Myau)");
        this.autoSwap = BooleanValue.create(this, "auto-swap", true, "Automatically swap to the best item");
        this.disableSafeWalk = BooleanValue.create(this, "disable-safewalk", true, "Disable SafeWalk while a script is running");
        this.showActivationHitbox = BooleanValue.create(this, "show-activation-hitbox", false, "Render the script activation hitbox");
        this.print = BooleanValue.create(this, "print", false, "Print script debug info to chat");
    }

    @Override
    public void onEnable() {
        super.onEnable();
        this.running = true;
        this.cyclePhase = 19;
    }

    @Override
    public void onDisable() {
        super.onDisable();
        this.running = false;
        MovementInputHelper.releaseMovementKeys();
    }

    @EventHandler
    public void onTick(EventPreTick event) {
        if (!this.isEnabled() || !this.running) return;
        if (Minecraft.thePlayer().isNull() || Minecraft.theWorld().isNull()) return;
        advanceCycle();
    }

    private void advanceCycle() {
        int phase = this.cyclePhase;
        float forward = FORWARD_CURVE[phase];
        float strafe = STRAFE_CURVE[phase];
        boolean jumping = phase >= 1 && phase <= 19;
        MovementInputHelper.synchronizeDirectionalInput(forward > 0.01f, forward < -0.01f, strafe < -0.01f, strafe > 0.01f);
        MovementInputHelper.setJumpPressed(jumping);
        this.cyclePhase = (phase + 1) % YAW_CURVE.length;
    }
}
