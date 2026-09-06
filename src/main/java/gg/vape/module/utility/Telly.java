package gg.vape.module.utility;

import gg.vape.event.EventHandler;
import gg.vape.event.impl.EventPreTick;
import gg.vape.module.Category;
import gg.vape.module.Mod;
import gg.vape.movement.MovementInputHelper;
import gg.vape.rotation.FixedRotationController;
import gg.vape.rotation.RotationManager;
import gg.vape.utils.RotationUtil;
import gg.vape.utils.MathUtil;
import gg.vape.rotation.RotationManager;
import gg.vape.wrapper.impl.RayTraceResult;
import gg.vape.wrapper.impl.RayTraceResult_type;
import gg.vape.wrapper.impl.WorldClient;
import gg.vape.value.BooleanValue;
import gg.vape.wrapper.impl.KeyBinding;
import gg.vape.wrapper.impl.Minecraft;

import java.util.HashSet;

/**
 * Telly - faithful port of Myau-cat myau.module.modules.Telly onto the Vape (gg.vape) Mod system.
 *
 * STAGE A: STATE MACHINE. Ports the Myau activation / running / reset state
 * transitions (armAutomation / beginAutomation / stopAutomation) and the state
 * fields, wired to Vape's Mod onEnable/onDisable lifecycle and a tick hook.
 * Placement, adaptive aim, raycast (Stage B-D), anti-sway (Stage E), GCD
 * smoothing (Stage F), rendering (Stage G) and integration (Stage H) are
 * stubbed/TODO and filled in by their own commits.
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

    // ---- state (Stage A) ----
    private boolean armed = false;
    private boolean running = false;
    private int setupTick = 0;
    private int cyclePhase = 19;
    private float stagedForward = -1.0f;
    private float stagedStrafe = -1.0f;
    private boolean stagedJump = false;
    private boolean stagedSprint = false;
    private float baseYaw = 0.0f;
    private int travelX = 0;
    private int travelZ = 0;
    private double antiSwayLane = 0.0;
    private float antiSwayYawOffset = 0.0f;
    private int bridgeLaneBlock = 0;
    private int bridgeStartProgress = 0;
    private int[] latestStraightPlacedPos = null;
    private int[] lastPlacedPos = null;
    private boolean firstTellyPlacementPending = false;
    private boolean adaptiveAimValid = false;
    private float adaptiveAimYaw = 0.0f;
    private float adaptiveAimPitch = 0.0f;
    private long adaptiveAimUpdatedAt = 0L;
    private long takeoverDetectionAt = 0L;
    private boolean takeoverCameraValid = false;
    private float takeoverCameraYaw = 0.0f;
    private float takeoverCameraPitch = 0.0f;
    private float takeoverAccumulated = 0.0f;
    private long takeoverLastFrameAt = 0L;
    private long freezeLastTickAt = 0L;
    private boolean rotationActive = false;
    private long rotationStartedAt = 0L;
    private long rotationDuration = 50L;
    private float rotationStartYaw = 0.0f;
    private float rotationStartPitch = 0.0f;
    private float rotationTargetYaw = 0.0f;
    private float rotationTargetPitch = 0.0f;
    private float scriptedRotationYaw = 0.0f;
    private float scriptedRotationPitch = 0.0f;
    private int rotationStepCounter = 0;
    private int[] activationAnchorPos = null;
    private int activationAnchorFace = -1;
    private boolean activationMovementHeld = false;
    private boolean eagleDisabledForActivation = false;
    private boolean eagleWasDisabledByTelly = false;
    private boolean antiSwayTapUsed = false;
    private final HashSet<String> cancelledGhostBlocks = new HashSet<>();
    private boolean tellyAutoPlaceWindow = false;
    private boolean autoPlaceDebugActive = false;
    private boolean safeWalkStateCaptured = false;
    private boolean safeWalkWasEnabled = false;
    private long activatePromptAt = 0L;
    private long promptBrokeAt = 0L;
    private float promptAlpha = 0.0f;
    private long promptFadeLastAt = 0L;
    private int promptFadeRgb = 0xFF5555;
    private int[] hitboxLastPos = null;
    private int hitboxLastFace = -1;
    private boolean ignoreForwardUntilRelease = false;
    private boolean ignoreBackUntilRelease = false;
    private boolean ignoreLeftUntilRelease = false;
    private boolean ignoreRightUntilRelease = false;
    private boolean ignoreJumpUntilRelease = false;
    private boolean ignoreSneakUntilRelease = false;
    private boolean ignoreSprintUntilRelease = false;

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
        this.armAutomation();
    }

    @Override
    public void onDisable() {
        super.onDisable();
        this.stopAutomation(false);
    }

    @EventHandler
    public void onTick(EventPreTick event) {
        if (!this.isEnabled()) return;
        if (Minecraft.thePlayer().isNull() || Minecraft.theWorld().isNull()) return;
        // Stage A: state-machine hook. The real activation judgement (raycast edge
        // detection) and the bridge cycle run here from Stage B onwards.
        if (this.running) {
            this.onRunningTick();
        } else {
            this.onActivationTick();
        }
    }

    // ---- Stage A state machine ----
    private void armAutomation() {
        this.armed = true;
        this.running = false;
        this.activatePromptAt = 0L;
        this.promptBrokeAt = 0L;
        this.setupTick = 0;
        this.cyclePhase = 19;
        this.rotationActive = false;
        this.activationMovementHeld = false;
        this.eagleDisabledForActivation = false;
        this.eagleWasDisabledByTelly = false;
        this.printStatus("&eArmed. Sneak looking down, wait for green, hold rmb and release sneak");
    }

    private void beginAutomation() {
        if (Minecraft.thePlayer().isNull() || !this.isHoldingBlock()) {
            this.printStatus("&cHold blocks before starting");
            return;
        }
        // snap to nearest diagonal so the whole bridge does not drift
        this.baseYaw = Math.round((RotationUtil.c() - 45.0f) / 90.0f) * 90.0f + 45.0f;
        this.calculateTravelDirection(this.baseYaw);
        this.antiSwayLane = this.travelX != 0 ? Minecraft.thePlayer().z() : Minecraft.thePlayer().h();
        this.antiSwayYawOffset = 0.0f;
        this.antiSwayTapUsed = false;
        this.cancelledGhostBlocks.clear();
        this.captureActivationAnchor();
        this.initializeStraightBridgeLane();
        this.firstTellyPlacementPending = false;
        this.adaptiveAimValid = false;
        this.adaptiveAimUpdatedAt = 0L;
        this.setupTick = 0;
        this.cyclePhase = 19;
        this.stagedForward = -1.0f;
        this.stagedStrafe = -1.0f;
        this.stagedJump = false;
        this.stagedSprint = false;
        this.armed = false;
        this.running = true;
        this.freezeLastTickAt = System.currentTimeMillis();
        this.activationMovementHeld = false;
        this.tellyAutoPlaceWindow = true;
        this.scriptedRotationYaw = this.baseYaw;
        this.scriptedRotationPitch = SCRIPT_PITCH;
        this.rotationStartYaw = this.baseYaw;
        this.rotationStartPitch = SCRIPT_PITCH;
        this.rotationTargetYaw = this.baseYaw;
        this.rotationTargetPitch = SCRIPT_PITCH;
        this.rotationActive = false;
        this.takeoverDetectionAt = 0L;
        this.takeoverCameraValid = false;
        this.clearInitialMovementHolds();
        this.resetControllerState();
        this.applyMovement(-1.0f, -1.0f, false, false);
        this.setRotationTarget(this.baseYaw, SCRIPT_PITCH, 50L);
        this.applySmoothedRotation();
        this.applyUse(true);
        this.printStatus("&aStarted");
    }

    private void stopAutomation(boolean turnOffButton) {
        boolean restoreEagleAfterStop = this.eagleWasDisabledByTelly;
        this.armed = false;
        this.running = false;
        this.setupTick = 0;
        this.cyclePhase = 19;
        this.rotationActive = false;
        this.activationMovementHeld = false;
        this.eagleDisabledForActivation = false;
        this.eagleWasDisabledByTelly = false;
        this.tellyAutoPlaceWindow = false;
        this.autoPlaceDebugActive = false;
        this.antiSwayYawOffset = 0.0f;
        this.antiSwayTapUsed = false;
        this.firstTellyPlacementPending = false;
        this.latestStraightPlacedPos = null;
        this.activationAnchorPos = null;
        this.activationAnchorFace = -1;
        this.stagedForward = 0.0f;
        this.stagedStrafe = 0.0f;
        this.stagedJump = false;
        this.stagedSprint = false;
        this.adaptiveAimValid = false;
        this.adaptiveAimUpdatedAt = 0L;
        this.scriptedRotationYaw = 0.0f;
        this.scriptedRotationPitch = 0.0f;
        this.takeoverDetectionAt = 0L;
        this.takeoverCameraValid = false;
        this.takeoverCameraYaw = 0.0f;
        this.takeoverCameraPitch = 0.0f;
        this.takeoverAccumulated = 0.0f;
        this.takeoverLastFrameAt = 0L;
        try {
            this.cancelledGhostBlocks.clear();
            this.clearInitialMovementHolds();
            this.resetControllerState();
            MovementInputHelper.releaseMovementKeys();
            this.restorePhysicalUse();
        } catch (Exception ignored) {}
        this.restoreSafeWalkState();
        this.freezeLastTickAt = 0L;
        this.armed = true;
        this.activatePromptAt = 0L;
        this.promptBrokeAt = 0L;
        if (restoreEagleAfterStop) this.restoreEagleAfterTelly();
        if (turnOffButton) this.printStatus("&eStopped. Sneak looking down to arm again");
    }

    // ---- Stage B: raycast target-block + edge detection ----
    private boolean isLookingAtEdge() {
        RayTraceResult hit = RotationManager.INSTANCE.getNormalReachRayTrace();
        if (hit == null || hit.isNull() || !hit.getTypeOfHit().equals(RayTraceResult_type.block())) return false;
        int face = this.faceFromName(hit);
        if (face < 2) return false;
        if (!this.isInActivationFaceCenter(face, hit)) return false;
        float yaw = RotationUtil.c();
        int[] travel = this.travelDirectionFromYaw(yaw);
        int travelFace = travel[0] > 0 ? 5 : travel[0] < 0 ? 4 : travel[1] > 0 ? 3 : 2;
        if (face != travelFace) return false;
        int[] pos = this.posFromReach(hit);
        if (!this.isPlayerOnActivationBlock(pos)) return false;
        int aheadX = pos[0] + travel[0];
        int aheadZ = pos[2] + travel[1];
        if (!this.isReplaceable(aheadX, pos[1] + 1, aheadZ)) return false;
        double lipDistance = this.lipDistance(face, pos);
        if (lipDistance > 0.65) return false;
        this.hitboxLastPos = new int[]{pos[0], pos[1], pos[2]};
        this.hitboxLastFace = face;
        return true;
    }
    private int faceFromName(RayTraceResult hit) {
        // Vape ray face accessor is obfuscated; map known side faces (2..5) where possible.
        // Fallback: derive a stable side index from the hit via the exposed block coordinates.
        try {
            String faceName = hit.getTypeOfHit().toString();
            // 'block' hit -> derive face from EnumFacing exposed by the wrapper if available
        } catch (Exception ignored) {}
        if (this.hitboxLastFace >= 2) return this.hitboxLastFace;
        return -1;
    }
    private boolean isInActivationFaceCenter(int face, RayTraceResult hit) {
        // Stage B: approximate by checking the hit is on a horizontal (side) face region.
        return face >= 2;
    }
    private int[] travelDirectionFromYaw(float yaw) {
        double radians = Math.toRadians(yaw);
        double rawX = Math.sin(radians) - Math.cos(radians);
        double rawZ = -Math.cos(radians) - Math.sin(radians);
        if (Math.abs(rawX) >= Math.abs(rawZ)) return new int[]{rawX >= 0.0 ? 1 : -1, 0};
        return new int[]{0, rawZ >= 0.0 ? 1 : -1};
    }
    private int[] posFromReach(RayTraceResult hit) {
        return new int[]{MathUtil.floor(hit.g()), MathUtil.floor(hit.T()), MathUtil.floor(hit.a$src$I$8nuo9d())};
    }
    private boolean isPlayerOnActivationBlock(int[] pos) {
        double px = Minecraft.thePlayer().z();
        double py = MathUtil.floor(Minecraft.thePlayer().N());
        double pz = Minecraft.thePlayer().h();
        return py == pos[1] && Math.abs(px - (pos[0] + 0.5)) < 0.9 && Math.abs(pz - (pos[2] + 0.5)) < 0.9;
    }
    private boolean isReplaceable(int x, int y, int z) {
        // Stage B: use Vape world air-block check where exposed; fallback true.
        try {
            return Minecraft.theWorld().h();
        } catch (Exception e) { return true; }
    }
    private double lipDistance(int face, int[] pos) {
        double px = Minecraft.thePlayer().z();
        double pz = Minecraft.thePlayer().h();
        if (face == 5) return (pos[0] + 1) - px;
        if (face == 4) return px - pos[0];
        if (face == 3) return (pos[2] + 1) - pz;
        return pz - pos[2];
    }

    // ---- Stage B+ hooks (stubbed, filled by later stages) ----
    private void onActivationTick() {
        // Stage B: raycast-based activation judgement.
        boolean sneak = Minecraft.thePlayer().movementInput().D$src$Z$v5d6e8();
        boolean rmb = this.useItemKey.isKeyDown();
        boolean lookingDown = Minecraft.thePlayer().g() >= ACTIVATION_PITCH;
        if (sneak && rmb && lookingDown && this.isLookingAtEdge()) {
            this.beginAutomation();
        }
    }
    private void onRunningTick() {
        // Stage B/C/D: advance cycle + adaptive aim + real placement.
    }
    private boolean isHoldingBlock() {
        // Stage D: check the held item is a usable block stack.
        return false;
    }
    private void calculateTravelDirection(float yaw) { /* Stage D */ }
    private void captureActivationAnchor() { /* Stage B */ }
    private void initializeStraightBridgeLane() { /* Stage D */ }
    private void clearInitialMovementHolds() { /* Stage C */ }
    private void resetControllerState() {
        if (this.rotationController != null) {
            RotationManager.INSTANCE.releaseController(this.rotationController);
            this.rotationController = null;
        }
    }
    private void applyMovement(float forward, float strafe, boolean jump, boolean sprint) {
        MovementInputHelper.synchronizeDirectionalInput(forward > 0.01f, forward < -0.01f, strafe < -0.01f, strafe > 0.01f);
        MovementInputHelper.setJumpPressed(jump);
    }
    private void setRotationTarget(float yaw, float pitch, long duration) {
        if (this.rotationController == null) {
            this.rotationController = new FixedRotationController(yaw, pitch);
            RotationManager.INSTANCE.setController(this.rotationController);
        } else {
            this.rotationController.setTargetRotation(yaw, pitch);
        }
    }
    private void applySmoothedRotation() { /* Stage F */ }
    private void applyUse(boolean pressed) {
        this.useItemKey.onTick(pressed ? 1 : 0);
    }
    private void restorePhysicalUse() {
        this.useItemKey.onTick(0);
    }
    private void restoreSafeWalkState() { /* Stage C */ }
    private void restoreEagleAfterTelly() { /* Stage C */ }
    private void printStatus(String message) {
        if (this.print.getValue()) {
            // Stage G: route through Vape chat/notification.
        }
    }
    private float tellyWrapAngle(float angle) {
        float wrapped = angle % 360.0f;
        if (wrapped > 180.0f) wrapped -= 360.0f;
        if (wrapped < -180.0f) wrapped += 360.0f;
        return wrapped;
    }
}