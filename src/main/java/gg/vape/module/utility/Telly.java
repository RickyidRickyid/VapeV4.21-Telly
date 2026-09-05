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
import gg.vape.wrapper.impl.KeyBinding;
import gg.vape.wrapper.impl.Minecraft;

/**
 * Telly - scripted bridge automation (ported from Myau-cat myau.module.modules.Telly).
 *
 * CHUNK 2: the 21-phase scripted bridge cycle now also drives rotation (via a
 * FixedRotationController claimed on the RotationManager) and auto-places the
 * held block by pressing the use-item key during the placement phases.
 * Adaptive aim, anti-sway, GCD smoothing, packet handling and the activation
 * flow arrive in chunks 3-4.
 */
public class Telly extends Mod {

    private final BooleanValue autoSwap;
    private final BooleanValue disableSafeWalk;
    private final BooleanValue showActivationHitbox;
    private final BooleanValue print;

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
    private float baseYaw = 0.0f;
    private FixedRotationController rotationController;
    private KeyBinding useItemKey;

    public Telly() {
        super("Telly", 0xffff4d4d, Category.UTILITY, "Scripted bridge automation (ported from Myau)");
        this.autoSwap = BooleanValue.create(this, "auto-swap", true, "Automatically swap to the best item");
        this.disableSafeWalk = BooleanValue.create(this, "disable-safewalk", true, "Disable SafeWalk while a script is running");
        this.showActivationHitbox = BooleanValue.create(this, "show-activation-hitbox", false, "Render the script activation hitbox");
        this.print = BooleanValue.create(this, "print", false, "Print script debug info to chat");
        this.useItemKey = Minecraft.gameSettings().b$src$Lgg_vape_wrapper_impl_KeyBinding_$1yi3362();
    }

    @Override
    public void onEnable() {
        super.onEnable();
        this.running = true;
        this.cyclePhase = 19;
        this.baseYaw = RotationUtil.c();
        this.rotationController = new FixedRotationController(this.baseYaw + YAW_CURVE[19], PITCH_CURVE[19]);
        RotationManager.INSTANCE.setController(this.rotationController);
    }

    @Override
    public void onDisable() {
        super.onDisable();
        this.running = false;
        if (this.rotationController != null) {
            RotationManager.INSTANCE.releaseController(this.rotationController);
            this.rotationController = null;
        }
        MovementInputHelper.releaseMovementKeys();
        this.useItemKey.onTick(0);
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
        boolean use = phase >= 7;
        MovementInputHelper.synchronizeDirectionalInput(forward > 0.01f, forward < -0.01f, strafe < -0.01f, strafe > 0.01f);
        MovementInputHelper.setJumpPressed(jumping);
        this.useItemKey.onTick(use ? 1 : 0);
        int next = (phase + 1) % YAW_CURVE.length;
        if (this.rotationController != null) {
            this.rotationController.setTargetRotation(this.baseYaw + YAW_CURVE[next], PITCH_CURVE[next]);
        }
        this.cyclePhase = next;
    }
}
