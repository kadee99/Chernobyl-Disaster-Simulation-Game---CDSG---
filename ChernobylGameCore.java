 import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.openal.*;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.ALC10.*;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.stb.STBVorbis.*;

/**
 * Core game renderer using pure LWJGL/OpenGL 3.3
 * With Minecraft-style block textures
 */
public class ChernobylGameCore {
    // Shader program
    private int shaderProgram;
    private int modelLoc, viewLoc, projectionLoc;
    private int colorLoc, lightPosLoc, viewPosLoc, lightColorLoc, ambientLoc;
    private int useTextureLoc, textureLoc, fullBrightLoc;
    
    // Minecraft-style textures
    private Map<String, Integer> blockTextures = new HashMap<>();
    private static final int TEXTURE_SIZE = 16; // Classic Minecraft 16x16 textures
    
    // Meshes
    private Mesh cubeMesh;
    private Mesh texturedCubeMesh;
    private Mesh npcHeadMesh;  // Head mesh with different texture per face
    private Mesh cylinderMesh;
    
    // Scene objects
    private List<GameObject> gameObjects = new ArrayList<>();
    private List<TexturedGameObject> texturedObjects = new ArrayList<>();
    
    // Animated fuel rods
    private List<FuelRodAnimation> fuelRodAnimations = new ArrayList<>();
    private float animationTime = 0f;
    
    // Animated gauge displays
    private List<GaugeDisplay> gaugeDisplays = new ArrayList<>();
    private int[] gaugeTextureIds = new int[20]; // Pool of gauge textures
    
    // NPC Engineers
    private List<NPCEngineer> npcEngineers = new ArrayList<>();

    // Player model (visible in 3rd person)
    private TexturedGameObject playerHead, playerBody, playerLeftLeg, playerRightLeg, playerLeftArm, playerRightArm;
    
    // Camera - Start position overlooking the control room facing the Elena display
    private Vector3f cameraPos = new Vector3f(0, 120, -200);
    private float yaw = 90f;  // Face towards +Z (the back wall with Elena display)
    private float pitch = -10f; // Slight downward angle
    private float moveSpeed = 200f;
    private float mouseSensitivity = 0.1f;
    
    // Jump physics
    private float velocityY = 0f;
    private boolean isOnGround = true;
    private static final float GRAVITY = 2500f;
    private static final float JUMP_VELOCITY = 600f;
    private static final float GROUND_LEVEL = 120f;
    
    // Fullscreen toggle
    private boolean isFullscreen = false;
    private boolean fKeyPressed = false;
    private int windowedX, windowedY, windowedWidth, windowedHeight;

    // 3rd person camera
    private boolean thirdPerson = false;
    private boolean vKeyPressed = false;
    private static final float THIRD_PERSON_DISTANCE = 250f;
    private static final float THIRD_PERSON_HEIGHT = 80f;

    // Walk animation
    private float walkAnimPhase = 0f;
    private boolean isPlayerWalking = false;
    private float playerFacingYaw = 90f; // Player model facing direction (independent of camera in 3rd person)
    
    // Mouse
    private double lastMouseX, lastMouseY;
    private boolean firstMouse = true;
    
    // Matrices
    private Matrix4f projection = new Matrix4f();
    private Matrix4f view = new Matrix4f();
    private Matrix4f model = new Matrix4f();
    
    // Constants
    private static final float BLOCK_SIZE = 50f;
    private static final int ROOM_SIZE = 10;
    private static final int CONTROL_ROOM_WINDOW_START_H = 1;
    private static final int CONTROL_ROOM_WINDOW_END_H = 4;
    private static final float CONTROL_ROOM_WINDOW_BOTTOM_WORLD_Y = CONTROL_ROOM_WINDOW_START_H * BLOCK_SIZE;
    private static final float CONTROL_ROOM_WINDOW_TOP_WORLD_Y = CONTROL_ROOM_WINDOW_END_H * BLOCK_SIZE;
    
    // Collision constants
    private static final float PLAYER_RADIUS = 30f;  // Player collision radius
    private static final float PLAYER_HEIGHT = 80f;  // Player eye height
    private static final float ROOM_WIDTH = 16 * BLOCK_SIZE;  // X boundaries
    private static final float ROOM_DEPTH = 12 * BLOCK_SIZE;  // Z boundaries
    private static final float ROOM_FLOOR = 20f;               // Minimum Y (above floor)
    private static final float ROOM_CEILING = 6 * BLOCK_SIZE - 20f; // Maximum Y (below ceiling)
    private static final int LOCKER_DOOR_MIN_Z = -24;
    private static final int LOCKER_DOOR_MAX_Z = -20;
    // === CORRIDOR TO REACTOR HALL ===
    // Long corridor: straight -Z from door → stairs down → turn left → left arm →
    // connecting walkway +Z → into reactor hall
    private static final float CORRIDOR_START_Z = -12 * BLOCK_SIZE;  // Front wall Z (door)
    private static final float CORRIDOR_STRAIGHT_END_Z = -34 * BLOCK_SIZE; // End of flat straight section
    private static final float CORRIDOR_HALF_WIDTH = 2 * BLOCK_SIZE; // Corridor is 4 blocks wide
    private static final int CORRIDOR_WALL_H = 6; // Corridor wall height in blocks
    // Stairs section: z from -34 to -42, descending from y=0 to y=-180
    private static final float STAIR_START_Z = -34 * BLOCK_SIZE;
    private static final float STAIR_END_Z = -42 * BLOCK_SIZE;
    // Turn/landing section: z = -42 to -46, at bottom level (y=-180)
    private static final float CORRIDOR_TURN_Z_MIN = -46 * BLOCK_SIZE;
    private static final float CORRIDOR_TURN_Z_MAX = -42 * BLOCK_SIZE;
    // Left arm going -X at z=-42 to z=-46, from x=+2 to x=-18
    private static final float CORRIDOR_LEFT_ARM_START_X = 2 * BLOCK_SIZE;
    private static final float CORRIDOR_LEFT_ARM_END_X = -18 * BLOCK_SIZE;
    // Connecting walkway: x=-18 to -20, z from -42 to -12 (enters reactor hall)
    private static final float WALKWAY_X_MIN = -20 * BLOCK_SIZE;
    private static final float WALKWAY_X_MAX = -18 * BLOCK_SIZE;
    private static final float WALKWAY_Z_START = -42 * BLOCK_SIZE;
    private static final float PUMP_WALKWAY_X_MIN = 18 * BLOCK_SIZE;
    private static final float PUMP_WALKWAY_X_MAX = 20 * BLOCK_SIZE;
    private static final float PUMP_WALKWAY_INNER_WALL = PUMP_WALKWAY_X_MIN - BLOCK_SIZE; // 17 * BS
    private static final float PUMP_WALKWAY_OUTER_WALL = PUMP_WALKWAY_X_MAX + BLOCK_SIZE; // 21 * BS
    private static final float LOCKER_ROOM_MIN_X = CORRIDOR_HALF_WIDTH + BLOCK_SIZE;
    private static final float LOCKER_ROOM_MAX_X = LOCKER_ROOM_MIN_X + 9 * BLOCK_SIZE;
    private static final float LOCKER_ROOM_MIN_Z = (LOCKER_DOOR_MIN_Z - 4) * BLOCK_SIZE;
    private static final float LOCKER_ROOM_MAX_Z = (LOCKER_DOOR_MAX_Z + 4) * BLOCK_SIZE;
    private static final float PUMP_ANNEX_MIN_X = PUMP_WALKWAY_INNER_WALL; // Mirror of reactor hall boundary
    private static final float PUMP_ANNEX_MAX_X = PUMP_ANNEX_MIN_X + 25 * BLOCK_SIZE;
    private static final float PUMP_ANNEX_MIN_Z = -12 * BLOCK_SIZE;
    private static final float PUMP_ANNEX_MAX_Z = 12 * BLOCK_SIZE;
    // Anchor the interactive dais in the center of the front wall so it becomes the focal station
    private static final float PUMP_CONSOLE_X = (PUMP_ANNEX_MIN_X + PUMP_ANNEX_MAX_X) * 0.5f;
    private static final float PUMP_CONSOLE_Z = PUMP_ANNEX_MAX_Z - BLOCK_SIZE * 2.5f;
    private static final float REACTOR_HALL_FLOOR_Y = -180f; // Existing reactor hall floor
    private static final int REACTOR_HALL_BASE_HEIGHT_BLOCKS = 8;
    private static final float REACTOR_HALL_HEIGHT_SCALE = 2.5f;
    private static final int REACTOR_HALL_WIDTH_BLOCKS = 42;
    private static final int REACTOR_HALL_DEPTH_BLOCKS = 24;
    private static final float REACTOR_HALL_MEZZANINE_RATIO = 0.55f;
    private static final int REACTOR_HALL_CATWALK_WIDTH_BLOCKS = 2;
    private static final int REACTOR_HALL_TALL_HEIGHT_BLOCKS = Math.round(REACTOR_HALL_BASE_HEIGHT_BLOCKS * REACTOR_HALL_HEIGHT_SCALE);
    private static final float REACTOR_HALL_MEZZANINE_Y = REACTOR_HALL_FLOOR_Y + REACTOR_HALL_TALL_HEIGHT_BLOCKS * BLOCK_SIZE * REACTOR_HALL_MEZZANINE_RATIO;
    private static final float REACTOR_HALL_WINDOW_X = -17 * BLOCK_SIZE;
    private static final float REACTOR_HALL_FAR_X = REACTOR_HALL_WINDOW_X - (REACTOR_HALL_WIDTH_BLOCKS - 1) * BLOCK_SIZE;
    private static final float REACTOR_HALL_FRONT_Z = REACTOR_HALL_DEPTH_BLOCKS * BLOCK_SIZE;
    private static final float REACTOR_HALL_BACK_Z = -REACTOR_HALL_FRONT_Z;
    private static final float REACTOR_HALL_WALL_MARGIN = BLOCK_SIZE * 0.02f;
    private static final int REACTOR_HALL_STAIRS_STEPS = 14;
    private static final float REACTOR_HALL_STAIRS_WIDTH = BLOCK_SIZE * 1.6f;
    private static final float REACTOR_HALL_STAIRS_RUN = BLOCK_SIZE * 0.85f;
    private static final float REACTOR_HALL_STAIRS_BASE_X = REACTOR_HALL_WINDOW_X - (REACTOR_HALL_WIDTH_BLOCKS - 5) * BLOCK_SIZE;
    private static final float REACTOR_HALL_STAIRS_BASE_Z = (REACTOR_HALL_DEPTH_BLOCKS - (REACTOR_HALL_CATWALK_WIDTH_BLOCKS * 0.5f)) * BLOCK_SIZE;
    private static final float REACTOR_HALL_STAIRS_BASE_Y = REACTOR_HALL_FLOOR_Y + BLOCK_SIZE * 0.2f;
    private static final float REACTOR_CORE_CENTER_X = REACTOR_HALL_WINDOW_X - (REACTOR_HALL_WIDTH_BLOCKS * 0.5f) * BLOCK_SIZE;
    private static final float REACTOR_CORE_CENTER_Z = 0f;
    private static final int REACTOR_GUARD_RADIUS_BLOCKS = 12 + 6;
    private static final float REACTOR_FENCE_RADIUS = REACTOR_GUARD_RADIUS_BLOCKS * BLOCK_SIZE * 0.8f;
    private static final float REACTOR_FENCE_MARGIN = BLOCK_SIZE * 0.04f;
    private boolean reactorHallDoorOpen = false;       // Door state
    private static final boolean DEV_CREATIVE_MODE_ENABLED = true;
    private static final float CREATIVE_RAY_DISTANCE = 12f * BLOCK_SIZE;
    private static final float CREATIVE_SNAP_STEP = BLOCK_SIZE / 2f;
    private static final String CREATIVE_SAVE_FILE = "creative_edits.txt";
    private static final String[] CREATIVE_PALETTE = new String[] {
        "reactor_wall_white",
        "soviet_gray_wall",
        "gray_concrete",
        "reactor_floor_metal",
        "light_blue_concrete",
        "oak_planks",
        "fluorescent_light"
    };

    // === STORY SYSTEM ===
    private int storyPhase = 0; // 0=intro, 1=talk to akimov, 2=go to toptunov, 3=test begins, etc.
    private String currentObjective = "";
    private String storyTime = "25 APRIL 1986 - 23:00";
    private float storyTimer = 0f;
    private boolean storyIntroShown = false;

    // Dialogue system
    private boolean dialogueActive = false;
    private String[] dialogueLines = {};
    private String dialogueSpeaker = "";
    private int dialoguePage = 0;
    private boolean tKeyPressed = false;
    private boolean enterKeyPressed = false;
    private String nearbyNPCName = ""; // NPC player is close to
    private static final float NPC_INTERACT_DISTANCE = 200f;

    // HUD shader + mesh
    private int hudShaderProgram;
    private int hudProjLoc, hudModelLoc, hudTexLoc, hudColorLoc, hudUseTexLoc;
    private int hudQuadVAO;

    // Notification popups
    private String notificationText = "";
    private float notificationTimer = 0f;
    private static final float NOTIFICATION_DURATION = 4f;

    // E-key prompt
    private boolean showInteractPrompt = false;
    private boolean showDebugGPSOverlay = true; // Temporary on-screen GPS readout
    private boolean creativeMode = false;
    private boolean creativeTogglePressed = false;
    private boolean creativePlacePressed = false;
    private boolean creativeBreakPressed = false;
    private boolean creativeClearPressed = false;
    private boolean creativeNextPressed = false;
    private boolean creativePrevPressed = false;
    private int creativePaletteIndex = 0;
    private List<TexturedGameObject> creativeBlocks = new ArrayList<>();
    private List<CreativePlacement> savedPlacements = new ArrayList<>();
    private List<CreativeRemoval> savedRemovals = new ArrayList<>();

    // === MACHINE INTERACTION SYSTEM (Phase 2) ===
    private boolean machineUIActive = false;
    private String activeMachine = ""; // "CONTROL_PANEL", "ELENA", ""
    private String nearbyMachine = "";
    private boolean escKeyPressed = false;
    private boolean xKeyPressed = false;
    private boolean spaceKeyPressed = false;
    private boolean az5Pressed = false;
    private static final float MACHINE_INTERACT_DISTANCE = 250f;

    // Reactor state (player-controllable + story-driven)
    private float reactorPower = 1600f;        // MW thermal
    private float reactorTemperature = 270f;   // Celsius
    private float reactorPressure = 70f;       // atm
    private int controlRodsInserted = 211;     // out of 211
    private float coolantFlow = 100f;          // percent
    private float neutronFlux = 100f;          // percent (relative)
    private float xenonLevel = 5f;             // percent (0-100)
    private boolean coolantPumpsOn = true;
    private boolean turbineConnected = true;
    private boolean emergencyCoolingOn = true;
    private float reactorStability = 100f;     // 0=meltdown, 100=stable
    private float voidCoefficient = 0f;        // positive void effect

    // Screen shake system
    private float screenShakeIntensity = 0f;    // current shake strength
    private float screenShakeTimer = 0f;        // time for shake animation
    private float screenShakeDecay = 0f;        // for one-shot shakes

    // Radiation exposure system
    private float playerRadiation = 0f;         // accumulated dose in roentgen
    private float radiationRate = 0f;           // current rads per second
    private float geigerClickTimer = 0f;        // timer for visual geiger clicks
    private int geigerClickCount = 0;           // recent click count for display
    private float radiationFlashTimer = 0f;     // visual flash effect

    // Dynamic lighting & alarms
    private List<AlarmLight> alarmLights = new ArrayList<>();
    private float alarmPulseTimer = 0f;
    private float alarmActivation = 0f;
    private float lightingTimer = 0f;

    // Player control state (for machine UI interactivity)
    private int selectedControl = 0;           // Which control is highlighted (0-5)
    private boolean upKeyHeld = false;
    private boolean downKeyHeld = false;
    private boolean leftKeyHeld = false;
    private boolean rightKeyHeld = false;
    private boolean key1Held = false;
    private boolean key2Held = false;
    private boolean key3Held = false;
    private float controlRepeatTimer = 0f;     // For held-key repeat
    private float reactorSimTimer = 0f;        // Reactor physics simulation
    private boolean playerReducedPower = false; // Track if player did the power reduction
    private boolean playerRaisedPower = false;  // Track if player raised power back
    private boolean playerStartedTest = false;  // Track if player initiated test
    private boolean playerDisconnectedTurbine = false;
    private boolean playerCheckedElena = false;         // Track if player used ELENA display
    private boolean playerReadIndicators = false;        // Track if player read indicator panels
    private boolean playerConfirmedTestReady = false;    // ELENA scan to confirm test readiness
    private float phaseTimer = 0f;                       // Timer before phase can advance
    private int elenaScanCount = 0;                      // How many sectors scanned
    private int elenaSelectedSector = -1;       // Selected ELENA core sector
    private float elenaScanTimer = 0f;          // Scanning animation
    private String machineLogMessage = "";      // Log message at bottom of panel
    private float machineLogTimer = 0f;         // How long to show log
    private float pumpConsoleFlowA = 72f;
    private float pumpConsoleFlowB = 64f;
    private float pumpConsoleSealTemp = 215f;
    private float pumpConsoleVibration = 0.18f;
    private float pumpConsoleCavitationRisk = 0.25f;
    private float pumpConsoleTrimA = 0f;
    private float pumpConsoleTrimB = -3f;
    private boolean pumpConsoleCrossfeedOpen = false;
    private boolean pumpConsoleEmergencyFlush = false;
    private boolean pumpConsoleDiagnosticsActive = false;
    private float pumpConsoleDiagnosticsProgress = 0f;
    private int pumpConsoleSelection = 0;
    private float pumpConsoleClickRepeat = 0f;
    private float pumpConsoleAlertLevel = 0.3f;
    private boolean playerCheckedPumpConsole = false;

    // === ELENA ENHANCED SCAN SYSTEM ===
    private boolean[] elenaScannedSectors = new boolean[18 * 18]; // Track which sectors were scanned
    private int elenaUniqueScanCount = 0;        // Unique sectors scanned (for bonuses)
    private int elenaHotSpotsFound = 0;          // Xenon hot spots detected
    private int elenaVoidSectorsFound = 0;       // Void coefficient anomalies detected
    private float elenaScanStabilityBonus = 0f;  // Cumulative stability bonus from scanning
    private boolean elenaXenonWarningGiven = false;  // Whether player got xenon early warning
    private boolean elenaVoidWarningGiven = false;    // Whether player detected void danger
    private float elenaScanNeglectPenalty = 0f;  // Stability penalty for not scanning enough

    // === INDICATOR PANEL ENHANCED SYSTEM ===
    private float indicatorDriftPower = 0f;       // Drift offset applied to power reading
    private float indicatorDriftTemp = 0f;        // Drift offset applied to temperature reading
    private float indicatorDriftPressure = 0f;    // Drift offset applied to pressure reading
    private float indicatorDriftFlux = 0f;        // Drift offset applied to neutron flux reading
    private float indicatorDriftXenon = 0f;       // Drift offset applied to xenon reading
    private float indicatorDriftCoolant = 0f;     // Drift offset applied to coolant reading
    private float indicatorCheckTimer = 0f;       // Seconds since last indicator panel check
    private float indicatorCheckBonus = 0f;       // Stability bonus from regular checking (max +10%)
    private float indicatorNeglectPenalty = 0f;   // Stability penalty for not checking (max -10%)
    private int indicatorCalibrationsDone = 0;    // Total calibrations performed
    private int indicatorAlarmsAcked = 0;         // Total alarms acknowledged
    private boolean[] indicatorAlarmActive = new boolean[6]; // Alarms: power, temp, pressure, flux, coolant, rods
    private boolean[] indicatorAlarmAcknowledged = new boolean[6]; // Which alarms player acknowledged
    private float[] powerHistory = new float[20];     // Circular buffer of recent power readings
    private float[] tempHistory = new float[20];      // Circular buffer of recent temp readings
    private float[] pressureHistory = new float[20];  // Circular buffer of recent pressure readings
    private int paramHistoryIdx = 0;                  // Current index in circular buffers
    private float paramHistoryTimer = 0f;             // Timer for recording parameter snapshots
    private int indicatorHoveredGauge = -1;           // Which gauge is mouse-hovered
    private java.util.Random indicatorRng = new java.util.Random(); // RNG for drift
    private final Random pumpConsoleRng = new Random(6801);

    // Mouse state for machine UI
    private float machineMouseX = 0f;           // Mouse X in screen coords
    private float machineMouseY = 0f;           // Mouse Y in screen coords (flipped for OpenGL)
    private boolean mouseLeftClicked = false;    // Left button just pressed this frame
    private boolean mouseRightClicked = false;   // Right button just pressed this frame
    private boolean mouseLeftHeld = false;       // Left button held last frame
    private boolean mouseRightHeld = false;      // Right button held last frame
    private float mouseClickRepeatTimer = 0f;   // For held-click repeat adjust

    // === GAME STATE ===
    private static final int STATE_MENU = 0;
    private static final int STATE_TUTORIAL = 1;
    private static final int STATE_PLAYING = 2;
    private static final int STATE_ENDING = 3;
    private int gameState = STATE_MENU;

    // === PAUSE MENU ===
    private boolean pauseMenuOpen = false;
    private boolean mKeyPressed = false;
    private int pauseMenuHovered = -1; // 0=Resume, 1=Quit to Title
    private float pauseMenuTime = 0f;

    // === ENDING STATE ===
    private boolean playerWon = false;
    private float endingTimer = 0f;          // Countdown before ending screen appears
    private boolean endingCountdownActive = false;
    private float endingSceneTimer = 0f;     // Time elapsed in ending scene
    private int endingBtnHovered = -1;       // 0=RETURN TO MENU hovered

    // Ending particle system (confetti for win, embers for lose)
    private static final int END_MAX_PARTS = 150;
    private float[] epX = new float[END_MAX_PARTS];
    private float[] epY = new float[END_MAX_PARTS];
    private float[] epVX = new float[END_MAX_PARTS];
    private float[] epVY = new float[END_MAX_PARTS];
    private float[] epR = new float[END_MAX_PARTS];
    private float[] epG = new float[END_MAX_PARTS];
    private float[] epB = new float[END_MAX_PARTS];
    private float[] epLife = new float[END_MAX_PARTS];
    private float[] epSize = new float[END_MAX_PARTS];
    private int epCount = 0;
    private boolean endingParticlesSpawned = false;

    // Menu animation
    private float menuTime = 0f;
    private int menuHovered = -1;               // -1=none, 0=START, 1=TUTORIAL, 2=QUIT
    private boolean menuEscHeld = false;

    // Menu explosion particle system
    private static final int MENU_MAX_PARTS = 200;
    private float[] mpX = new float[MENU_MAX_PARTS];
    private float[] mpY = new float[MENU_MAX_PARTS];
    private float[] mpVX = new float[MENU_MAX_PARTS];
    private float[] mpVY = new float[MENU_MAX_PARTS];
    private float[] mpR = new float[MENU_MAX_PARTS];
    private float[] mpG = new float[MENU_MAX_PARTS];
    private float[] mpB = new float[MENU_MAX_PARTS];
    private float[] mpLife = new float[MENU_MAX_PARTS];
    private float[] mpSize = new float[MENU_MAX_PARTS];
    private int mpCount = 0;
    private boolean menuParticlesSpawned = false;

    // Tutorial
    private int tutorialStep = 0;
    private static final int TUTORIAL_TOTAL = 9;
    private boolean tutClickHeld = false;

    // Machine interaction points (x, z positions)
    private static final float CONTROL_PANEL_X = 0f;
    private static final float CONTROL_PANEL_Z = -6f * BLOCK_SIZE; // -300
    private static final float ELENA_X = 0f;
    private static final float ELENA_Z = 11f * BLOCK_SIZE; // 550 (in front of ELENA)
    private static final float INDICATOR_X = (16f - 1.5f) * BLOCK_SIZE; // Right wall secondary panels
    private static final float INDICATOR_Z = 0f; // Middle of right wall

    // === AUDIO SYSTEM ===
    private long audioDevice = 0;
    private long audioContext = 0;
    private int musicBuffer = 0;
    private int musicSource = 0;
    private boolean audioInitialized = false;

    public void init(long window) {
        // Create shaders
        createShaders();
        
        // Create Minecraft-style block textures
        createBlockTextures();
        
        // Create basic meshes
        cubeMesh = createCubeMesh();
        texturedCubeMesh = createTexturedCubeMesh();
        npcHeadMesh = createNPCHeadMesh();
        cylinderMesh = createCylinderMesh(8); // Lower poly for blocky look
        
        // Build the scene with textures
        buildControlRoom();
        // buildReactorRoom(); // Disabled - reactor room removed
        loadCreativeEdits();
        
        // Setup projection matrix
        float aspectRatio = (float) ChernobylGame.WIDTH / ChernobylGame.HEIGHT;
        projection.setPerspective((float) Math.toRadians(70.0), aspectRatio, 0.1f, 5000.0f);
        
        // Get initial mouse position
        double[] xpos = new double[1], ypos = new double[1];
        glfwGetCursorPos(window, xpos, ypos);
        lastMouseX = xpos[0];
        lastMouseY = ypos[0];

        // Create player model for 3rd person view (reuse Akimov textures)
        createPlayerModel();

        // Create HUD rendering resources
        createHUDResources();

        // Initialize audio and start background music
        initAudio();

        // Game starts in menu state - story begins when START is clicked
    }

    private void initAudio() {
        try {
            // Open default audio device
            String defaultDevice = alcGetString(0, ALC_DEFAULT_DEVICE_SPECIFIER);
            audioDevice = alcOpenDevice(defaultDevice);
            if (audioDevice == 0) {
                System.err.println("[AUDIO] Failed to open audio device");
                return;
            }

            // Create context
            audioContext = alcCreateContext(audioDevice, (IntBuffer) null);
            if (audioContext == 0) {
                System.err.println("[AUDIO] Failed to create audio context");
                alcCloseDevice(audioDevice);
                audioDevice = 0;
                return;
            }
            alcMakeContextCurrent(audioContext);
            AL.createCapabilities(ALC.createCapabilities(audioDevice));

            // Try to load OGG file from project folder
            // First try music.ogg, then any .ogg file in the directory
            Path musicPath = Paths.get("music.ogg");
            if (!Files.exists(musicPath)) {
                musicPath = Paths.get(System.getProperty("user.dir"), "music.ogg");
            }
            if (!Files.exists(musicPath)) {
                // Search for any .ogg file in the project folder
                Path projectDir = Paths.get(System.getProperty("user.dir"));
                try (java.util.stream.Stream<Path> files = Files.list(projectDir)) {
                    musicPath = files
                        .filter(p -> p.toString().toLowerCase().endsWith(".ogg"))
                        .findFirst()
                        .orElse(null);
                } catch (Exception ex) {
                    musicPath = null;
                }
            }
            if (musicPath == null || !Files.exists(musicPath)) {
                System.out.println("[AUDIO] No music.ogg found - place music.ogg in project folder for background music");
                audioInitialized = true; // context is fine, just no music
                return;
            }

            System.out.println("[AUDIO] Loading " + musicPath.toAbsolutePath());

            // Read the entire file into a ByteBuffer
            byte[] fileBytes = Files.readAllBytes(musicPath);
            ByteBuffer fileBuffer = MemoryUtil.memAlloc(fileBytes.length);
            fileBuffer.put(fileBytes).flip();

            // Decode OGG Vorbis
            int[] errorOut = new int[1];
            long decoder = stb_vorbis_open_memory(fileBuffer, errorOut, null);
            if (decoder == 0) {
                System.err.println("[AUDIO] Failed to decode music.ogg (error: " + errorOut[0] + ")");
                MemoryUtil.memFree(fileBuffer);
                audioInitialized = true;
                return;
            }

            // Get info
            int channels, sampleRate, samples;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                STBVorbisInfo info = STBVorbisInfo.malloc(stack);
                stb_vorbis_get_info(decoder, info);
                channels = info.channels();
                sampleRate = info.sample_rate();
            }
            samples = stb_vorbis_stream_length_in_samples(decoder);
            int totalSamples = samples * channels;

            // Decode all samples
            ShortBuffer pcmBuffer = MemoryUtil.memAllocShort(totalSamples);
            stb_vorbis_get_samples_short_interleaved(decoder, channels, pcmBuffer);
            stb_vorbis_close(decoder);
            MemoryUtil.memFree(fileBuffer);

            // Create OpenAL buffer
            musicBuffer = alGenBuffers();
            int format = (channels == 1) ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16;
            alBufferData(musicBuffer, format, pcmBuffer, sampleRate);
            MemoryUtil.memFree(pcmBuffer);

            // Create source and set looping
            musicSource = alGenSources();
            alSourcei(musicSource, AL_BUFFER, musicBuffer);
            alSourcei(musicSource, AL_LOOPING, AL_TRUE);
            alSourcef(musicSource, AL_GAIN, 0.5f); // 50% volume

            // Start playing
            alSourcePlay(musicSource);
            audioInitialized = true;

            float duration = (float) samples / sampleRate;
            System.out.println("[AUDIO] Playing music.ogg (" + String.format("%.1f", duration) + "s, " + channels + "ch, " + sampleRate + "Hz) - LOOPING");

        } catch (Exception e) {
            System.err.println("[AUDIO] Error initializing audio: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void cleanupAudio() {
        if (musicSource != 0) {
            alSourceStop(musicSource);
            alDeleteSources(musicSource);
            musicSource = 0;
        }
        if (musicBuffer != 0) {
            alDeleteBuffers(musicBuffer);
            musicBuffer = 0;
        }
        if (audioContext != 0) {
            alcDestroyContext(audioContext);
            audioContext = 0;
        }
        if (audioDevice != 0) {
            alcCloseDevice(audioDevice);
            audioDevice = 0;
        }
        audioInitialized = false;
    }

    private void createPlayerModel() {
        float headSize = 25f;
        float bodyWidth = 30f, bodyHeight = 50f;
        float legWidth = 14f, legHeight = 55f;
        float armWidth = 12f, armHeight = 45f;

        // All positions relative to origin - will be transformed at render time
        playerHead = new TexturedGameObject(npcHeadMesh,
            new Vector3f(0, legHeight + bodyHeight + headSize/2, 0),
            new Vector3f(headSize, headSize, headSize),
            blockTextures.get("player_head"), false);
        playerHead.textureName = "player_head";
        playerBody = new TexturedGameObject(texturedCubeMesh,
            new Vector3f(0, legHeight + bodyHeight/2, 0),
            new Vector3f(bodyWidth, bodyHeight, bodyWidth * 0.5f),
            blockTextures.get("player_body"), false);
        playerBody.textureName = "player_body";
        playerLeftLeg = new TexturedGameObject(texturedCubeMesh,
            new Vector3f(-legWidth * 0.6f, legHeight/2, 0),
            new Vector3f(legWidth, legHeight, legWidth),
            blockTextures.get("player_legs"), false);
        playerLeftLeg.textureName = "player_legs";
        playerRightLeg = new TexturedGameObject(texturedCubeMesh,
            new Vector3f(legWidth * 0.6f, legHeight/2, 0),
            new Vector3f(legWidth, legHeight, legWidth),
            blockTextures.get("player_legs"), false);
        playerRightLeg.textureName = "player_legs";
        playerLeftArm = new TexturedGameObject(texturedCubeMesh,
            new Vector3f(-bodyWidth/2 - armWidth/2, legHeight + bodyHeight/2, 0),
            new Vector3f(armWidth, armHeight, armWidth),
            blockTextures.get("player_arms"), false);
        playerLeftArm.textureName = "player_arms";
        playerRightArm = new TexturedGameObject(texturedCubeMesh,
            new Vector3f(bodyWidth/2 + armWidth/2, legHeight + bodyHeight/2, 0),
            new Vector3f(armWidth, armHeight, armWidth),
            blockTextures.get("player_arms"), false);
        playerRightArm.textureName = "player_arms";
    }

    private void createHUDResources() {
        // Simple 2D shader for HUD overlay
        String hudVertSrc = "#version 330 core\n"
            + "layout (location = 0) in vec2 aPos;\n"
            + "layout (location = 1) in vec2 aTexCoord;\n"
            + "out vec2 TexCoord;\n"
            + "uniform mat4 projection;\n"
            + "uniform mat4 model;\n"
            + "void main() {\n"
            + "    TexCoord = aTexCoord;\n"
            + "    gl_Position = projection * model * vec4(aPos, 0.0, 1.0);\n"
            + "}\n";
        String hudFragSrc = "#version 330 core\n"
            + "out vec4 FragColor;\n"
            + "in vec2 TexCoord;\n"
            + "uniform vec4 color;\n"
            + "uniform bool useTexture;\n"
            + "uniform sampler2D tex;\n"
            + "void main() {\n"
            + "    if (useTexture) {\n"
            + "        FragColor = texture(tex, TexCoord) * color;\n"
            + "    } else {\n"
            + "        FragColor = color;\n"
            + "    }\n"
            + "}\n";

        // Compile the lightweight HUD shader once up-front
        int hudVertShader = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(hudVertShader, hudVertSrc);
        glCompileShader(hudVertShader);
        checkShaderCompilation(hudVertShader, "HUD VERTEX");

        int hudFragShader = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(hudFragShader, hudFragSrc);
        glCompileShader(hudFragShader);
        checkShaderCompilation(hudFragShader, "HUD FRAGMENT");

        hudShaderProgram = glCreateProgram();
        glAttachShader(hudShaderProgram, hudVertShader);
        glAttachShader(hudShaderProgram, hudFragShader);
        glLinkProgram(hudShaderProgram);
        checkProgramLinking(hudShaderProgram);

        glDeleteShader(hudVertShader);
        glDeleteShader(hudFragShader);

        hudProjLoc = glGetUniformLocation(hudShaderProgram, "projection");
        hudModelLoc = glGetUniformLocation(hudShaderProgram, "model");
        hudColorLoc = glGetUniformLocation(hudShaderProgram, "color");
        hudUseTexLoc = glGetUniformLocation(hudShaderProgram, "useTexture");
        hudTexLoc = glGetUniformLocation(hudShaderProgram, "tex");

        float[] quadVerts = {
            // pos      // uv
            0f, 0f,     0f, 1f,
            1f, 0f,     1f, 1f,
            1f, 1f,     1f, 0f,
            0f, 0f,     0f, 1f,
            1f, 1f,     1f, 0f,
            0f, 1f,     0f, 0f,
        };
        int vao = glGenVertexArrays();
        int vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        FloatBuffer buf = BufferUtils.createFloatBuffer(quadVerts.length);
        buf.put(quadVerts).flip();
        glBufferData(GL_ARRAY_BUFFER, buf, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 16, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 16, 8);
        glEnableVertexAttribArray(1);
        glBindVertexArray(0);
        hudQuadVAO = vao;
    }

    // =============================================
    // === MENU & TUTORIAL SYSTEM ===
    // =============================================

    private void updateMenu(long window, float dt) {
        menuTime += dt;

        // Ensure cursor is visible in menu
        if (glfwGetInputMode(window, GLFW_CURSOR) != GLFW_CURSOR_NORMAL) {
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
        }

        // Track mouse
        double[] mx = new double[1], my = new double[1];
        glfwGetCursorPos(window, mx, my);
        int[] ww = new int[1], wh = new int[1];
        glfwGetWindowSize(window, ww, wh);
        float mouseX = (float) mx[0];
        float mouseY = (float)(wh[0] - my[0]); // Flip Y for OpenGL
        float screenW = (float) ww[0];
        float screenH = (float) wh[0];

        // Button layout (above the building)
        float btnW = 320, btnH = 50;
        float btnX = (screenW - btnW) / 2;
        float btnStartY = screenH * 0.74f;
        float btnGap = 55;

        // Hover detection
        menuHovered = -1;
        for (int i = 0; i < 3; i++) {
            float by = btnStartY - i * btnGap;
            if (mouseX >= btnX && mouseX <= btnX + btnW &&
                mouseY >= by && mouseY <= by + btnH) {
                menuHovered = i;
            }
        }

        // Click detection
        boolean leftNow = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS;
        if (leftNow && !mouseLeftHeld) {
            if (menuHovered == 0) {
                // START GAME
                gameState = STATE_PLAYING;
                glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                firstMouse = true;
                startStory();
            } else if (menuHovered == 1) {
                // TUTORIAL
                gameState = STATE_TUTORIAL;
                tutorialStep = 0;
            } else if (menuHovered == 2) {
                // QUIT
                glfwSetWindowShouldClose(window, true);
            }
        }
        mouseLeftHeld = leftNow;
        mouseRightHeld = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_RIGHT) == GLFW_PRESS;

        // === Explosion animation cycle ===
        // Cycle: 0-3s building intact, 3s explosion, 3-8s debris, 8-10s fade, 10s reset
        float cycleTime = menuTime % 10f;

        // Spawn explosion particles at explosion moment
        if (cycleTime >= 3f && cycleTime < 3f + dt * 2 + 0.05f && !menuParticlesSpawned) {
            menuParticlesSpawned = true;
            mpCount = 0;
            // Building center (in screen coords, approx)
            float buildCX = screenW * 0.5f;
            float buildExpY = screenH * 0.38f; // Explosion origin (top of reactor hall)
            Random pRand = new Random((long)(menuTime * 1000));
            for (int i = 0; i < MENU_MAX_PARTS && mpCount < MENU_MAX_PARTS; i++) {
                mpX[mpCount] = buildCX + (pRand.nextFloat() - 0.5f) * 200;
                mpY[mpCount] = buildExpY + pRand.nextFloat() * 40;
                float angle = pRand.nextFloat() * (float)Math.PI; // spread upward
                float speed = 80 + pRand.nextFloat() * 300;
                mpVX[mpCount] = (float)Math.cos(angle) * speed * (pRand.nextFloat() - 0.5f) * 2;
                mpVY[mpCount] = (float)Math.sin(angle) * speed * 0.5f + 50 + pRand.nextFloat() * 200;
                // Color: mix of fire (orange/red/yellow) and debris (gray)
                if (pRand.nextFloat() < 0.6f) {
                    // Fire particle
                    mpR[mpCount] = 0.8f + pRand.nextFloat() * 0.2f;
                    mpG[mpCount] = 0.2f + pRand.nextFloat() * 0.6f;
                    mpB[mpCount] = 0f;
                } else {
                    // Debris particle
                    float gray = 0.3f + pRand.nextFloat() * 0.3f;
                    mpR[mpCount] = gray;
                    mpG[mpCount] = gray * 0.95f;
                    mpB[mpCount] = gray * 0.9f;
                }
                mpLife[mpCount] = 3f + pRand.nextFloat() * 4f;
                mpSize[mpCount] = 4 + pRand.nextFloat() * 12;
                mpCount++;
            }
        }
        if (cycleTime < 3f) {
            menuParticlesSpawned = false;
        }

        // Update particles
        for (int i = 0; i < mpCount; i++) {
            if (mpLife[i] <= 0) continue;
            mpLife[i] -= dt;
            mpX[i] += mpVX[i] * dt;
            mpY[i] += mpVY[i] * dt;
            mpVY[i] -= 120f * dt; // Gravity
            mpVX[i] *= 0.99f; // Air drag
        }

        // Fullscreen toggle with F key (works in menu too)
        if (glfwGetKey(window, GLFW_KEY_F) == GLFW_PRESS) {
            if (!fKeyPressed) { fKeyPressed = true; toggleFullscreen(window); }
        } else { fKeyPressed = false; }
    }

    private void updateTutorial(long window, float dt) {
        // Ensure cursor visible
        if (glfwGetInputMode(window, GLFW_CURSOR) != GLFW_CURSOR_NORMAL) {
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
        }

        double[] mx = new double[1], my = new double[1];
        glfwGetCursorPos(window, mx, my);
        int[] ww = new int[1], wh = new int[1];
        glfwGetWindowSize(window, ww, wh);
        float mouseX = (float) mx[0];
        float mouseY = (float)(wh[0] - my[0]);
        float screenW = (float) ww[0];
        float screenH = (float) wh[0];

        boolean leftNow = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS;
        boolean clicked = leftNow && !tutClickHeld;
        tutClickHeld = leftNow;

        // Button positions
        float cardW = screenW * 0.82f;
        float cardX = (screenW - cardW) / 2;
        float cardH = screenH * 0.82f;
        float cardY = (screenH - cardH) / 2;

        float nbW = 200, nbH = 55;
        float nBtnW = tutorialStep < TUTORIAL_TOTAL - 1 ? nbW : nbW + 80;
        float nextX = cardX + cardW - nBtnW - 25;
        float nextY = cardY + 18;
        float backX = cardX + 25;
        float backY = cardY + 18;

        if (clicked) {
            // NEXT button
            if (mouseX >= nextX && mouseX <= nextX + nBtnW && mouseY >= nextY && mouseY <= nextY + nbH) {
                if (tutorialStep < TUTORIAL_TOTAL - 1) {
                    tutorialStep++;
                } else {
                    // Last step - start game
                    gameState = STATE_PLAYING;
                    glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                    firstMouse = true;
                    startStory();
                }
            }
            // BACK button
            if (mouseX >= backX && mouseX <= backX + nbW && mouseY >= backY && mouseY <= backY + nbH) {
                if (tutorialStep > 0) {
                    tutorialStep--;
                } else {
                    gameState = STATE_MENU;
                }
            }
        }

        // ESC to go back to menu
        if (glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS) {
            if (!menuEscHeld) { menuEscHeld = true; gameState = STATE_MENU; }
        } else { menuEscHeld = false; }

        // Fullscreen toggle with F key (works in tutorial too)
        if (glfwGetKey(window, GLFW_KEY_F) == GLFW_PRESS) {
            if (!fKeyPressed) { fKeyPressed = true; toggleFullscreen(window); }
        } else { fKeyPressed = false; }
    }

    // === PAUSE MENU (Minecraft-style) ===
    private void updatePauseMenu(long window, float dt) {
        pauseMenuTime += dt;

        // Ensure cursor is visible
        if (glfwGetInputMode(window, GLFW_CURSOR) != GLFW_CURSOR_NORMAL) {
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
        }

        // Track mouse
        double[] mx = new double[1], my = new double[1];
        glfwGetCursorPos(window, mx, my);
        int[] ww = new int[1], wh = new int[1];
        glfwGetWindowSize(window, ww, wh);
        float mouseX = (float) mx[0];
        float mouseY = (float)(wh[0] - my[0]); // Flip Y for OpenGL
        float screenW = (float) ww[0];
        float screenH = (float) wh[0];

        // Button layout (centered)
        float btnW = 340, btnH = 50;
        float btnX = (screenW - btnW) / 2;
        float centerY = screenH * 0.5f;
        float btnGap = 60;
        int btnCount = 2; // Resume, Quit to Title

        // Hover detection
        pauseMenuHovered = -1;
        for (int i = 0; i < btnCount; i++) {
            float by = centerY + (btnCount / 2f - i - 0.5f) * btnGap;
            float pulse = (pauseMenuHovered == i) ? (float)(Math.sin(pauseMenuTime * 5) * 0.05f + 1.05f) : 1f;
            float bw = btnW * pulse;
            float bx = (screenW - bw) / 2;
            if (mouseX >= bx && mouseX <= bx + bw &&
                mouseY >= by && mouseY <= by + btnH) {
                pauseMenuHovered = i;
            }
        }

        // Click detection
        boolean leftNow = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS;
        if (leftNow && !mouseLeftHeld) {
            if (pauseMenuHovered == 0) {
                // RESUME
                pauseMenuOpen = false;
                glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                firstMouse = true;
            } else if (pauseMenuHovered == 1) {
                // QUIT TO TITLE
                pauseMenuOpen = false;
                gameState = STATE_MENU;
                glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
            }
        }
        mouseLeftHeld = leftNow;

        // ESC also closes pause menu
        if (glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS) {
            if (!menuEscHeld) {
                menuEscHeld = true;
                pauseMenuOpen = false;
                glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                firstMouse = true;
            }
        } else {
            menuEscHeld = false;
        }
    }

    private void renderPauseMenu() {
        setupHUD2D();

        int[] wBuf = new int[1], hBuf = new int[1];
        glfwGetFramebufferSize(glfwGetCurrentContext(), wBuf, hBuf);
        float screenW = wBuf[0] > 0 ? wBuf[0] : ChernobylGame.WIDTH;
        float screenH = hBuf[0] > 0 ? hBuf[0] : ChernobylGame.HEIGHT;

        // Semi-transparent dark overlay (like Minecraft)
        drawHUDRect(0, 0, screenW, screenH, 0.0f, 0.0f, 0.0f, 0.65f);

        // "GAME PAUSED" title
        String title = "GAME PAUSED";
        float titleW = title.length() * (4 * 4 + 1 * 4); // scale 4
        float titleX = (screenW - titleW) / 2;
        drawHUDText(title, titleX, screenH * 0.72f, 4, 1f, 0.7f, 0.2f, 1f);

        // Divider line under title
        float divW = 400;
        drawHUDRect((screenW - divW) / 2, screenH * 0.68f, divW, 2, 0.7f, 0.4f, 0.1f, 0.6f);

        // Buttons
        float btnW = 340, btnH = 50;
        float centerY = screenH * 0.5f;
        float btnGap = 60;
        String[] btnLabels = {"RESUME GAME", "QUIT TO TITLE"};
        float[][] btnColors = {
            {0.6f, 0.35f, 0.08f},   // Warm amber (resume)
            {0.55f, 0.15f, 0.05f}   // Deep red-orange (quit)
        };
        int btnCount = btnLabels.length;

        for (int i = 0; i < btnCount; i++) {
            float by = centerY + (btnCount / 2f - i - 0.5f) * btnGap;
            boolean hovered = (pauseMenuHovered == i);
            float brightness = hovered ? 1.4f : 1f;
            float pulse = hovered ? (float)(Math.sin(pauseMenuTime * 5) * 0.05f + 1.05f) : 1f;
            float bw = btnW * pulse;
            float bx = (screenW - bw) / 2;

            // Button background
            drawHUDRect(bx, by, bw, btnH,
                btnColors[i][0] * brightness, btnColors[i][1] * brightness, btnColors[i][2] * brightness,
                hovered ? 0.95f : 0.8f);
            // Top highlight
            drawHUDRect(bx, by + btnH - 3, bw, 3,
                btnColors[i][0] * 2f, btnColors[i][1] * 2f, btnColors[i][2] * 2f, hovered ? 1f : 0.6f);
            // Bottom shadow
            drawHUDRect(bx, by, bw, 3,
                btnColors[i][0] * 0.5f, btnColors[i][1] * 0.5f, btnColors[i][2] * 0.5f, hovered ? 1f : 0.6f);

            // Button text
            float tw = btnLabels[i].length() * (4 * 2 + 1 * 2);
            float tx = (screenW - tw) / 2;
            drawHUDText(btnLabels[i], tx, by + 20, 2, 1f, 1f, 1f, 1f);
        }

        // Hint text at bottom
        String hint = "PRESS M OR ESC TO RESUME";
        float hintW = hint.length() * (4 * 2 + 1 * 2);
        drawHUDText(hint, (screenW - hintW) / 2, screenH * 0.28f, 2, 0.5f, 0.4f, 0.2f, 0.7f);

        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
    }

    private void setupHUD2D() {
        glDisable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_CULL_FACE);
        glUseProgram(hudShaderProgram);

        int[] wBuf = new int[1], hBuf = new int[1];
        glfwGetFramebufferSize(glfwGetCurrentContext(), wBuf, hBuf);
        float screenW = wBuf[0] > 0 ? wBuf[0] : ChernobylGame.WIDTH;
        float screenH = hBuf[0] > 0 ? hBuf[0] : ChernobylGame.HEIGHT;

        Matrix4f hudProj = new Matrix4f().ortho(0, screenW, 0, screenH, -1, 1);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer fb = stack.mallocFloat(16);
            glUniformMatrix4fv(hudProjLoc, false, hudProj.get(fb));
        }
        glUniform1i(hudTexLoc, 0);
    }

    private void renderMenuScreen() {
        setupHUD2D();

        int[] wBuf = new int[1], hBuf = new int[1];
        glfwGetFramebufferSize(glfwGetCurrentContext(), wBuf, hBuf);
        float screenW = wBuf[0] > 0 ? wBuf[0] : ChernobylGame.WIDTH;
        float screenH = hBuf[0] > 0 ? hBuf[0] : ChernobylGame.HEIGHT;

        float cycleTime = menuTime % 10f;
        float explosionT = Math.max(0, cycleTime - 3f); // time since explosion in this cycle

        // === BACKGROUND: Dark warm night sky ===
        drawHUDRect(0, 0, screenW, screenH, 0.06f, 0.03f, 0.01f, 1f);

        // Stars
        Random starRand = new Random(12345);
        for (int i = 0; i < 60; i++) {
            float sx = starRand.nextFloat() * screenW;
            float sy = screenH * 0.5f + starRand.nextFloat() * screenH * 0.5f;
            float twinkle = (float)(Math.sin(menuTime * (1.5 + starRand.nextFloat() * 2) + i) * 0.3 + 0.7);
            float sSize = 1 + starRand.nextFloat() * 2;
            drawHUDRect(sx, sy, sSize, sSize, 0.9f, 0.65f, 0.3f, twinkle * 0.7f);
        }

        // Ground
        drawHUDRect(0, 0, screenW, screenH * 0.12f, 0.1f, 0.06f, 0.02f, 1f);
        // Ground detail blocks
        Random gndRand = new Random(999);
        for (int i = 0; i < 40; i++) {
            float gx = gndRand.nextFloat() * screenW;
            float gy = gndRand.nextFloat() * screenH * 0.1f;
            float gs = 6 + gndRand.nextFloat() * 10;
            float gc = 0.06f + gndRand.nextFloat() * 0.06f;
            drawHUDRect(gx, gy, gs, gs, gc + 0.02f, gc, gc * 0.4f, 1f);
        }

        // === CHERNOBYL BUILDING (Minecraft pixel-art style) ===
        float bs = Math.min(screenW / 90f, screenH / 55f); // block size to fit screen
        float buildW = 35 * bs; // building grid width
        float buildBaseX = (screenW - buildW) / 2;
        float buildBaseY = screenH * 0.12f;

        // Draw the building block by block
        for (int gy = 0; gy < 25; gy++) {
            for (int gx = 0; gx < 35; gx++) {
                float[] color = getMenuBuildingBlock(gx, gy);
                if (color == null) continue; // empty cell

                float bx = buildBaseX + gx * bs;
                float by = buildBaseY + gy * bs;

                // If explosion happened and this block is above the explosion line
                if (explosionT > 0 && gy >= 12 && gx >= 5 && gx <= 19) {
                    // Displace this block based on explosion physics
                    Random blockRand = new Random(gx * 37 + gy * 73);
                    float vxB = (gx - 12) * 30 + (blockRand.nextFloat() - 0.5f) * 250;
                    float vyB = 100 + blockRand.nextFloat() * 350 + (gy - 12) * 40;
                    float dispX = vxB * explosionT;
                    float dispY = vyB * explosionT - 250f * explosionT * explosionT; // gravity
                    float alpha = Math.max(0, 1f - explosionT * 0.25f);
                    if (alpha <= 0) continue;
                    drawHUDRect(bx + dispX, by + dispY, bs - 1, bs - 1,
                        color[0], color[1], color[2], alpha);
                } else {
                    drawHUDRect(bx, by, bs - 1, bs - 1, color[0], color[1], color[2], 1f);
                }
            }
        }

        // === FIRE GLOW at explosion point ===
        if (explosionT > 0 && explosionT < 7f) {
            float fireCX = buildBaseX + 12 * bs;
            float fireCY = buildBaseY + 13 * bs;
            float fireIntensity = Math.min(1f, explosionT * 2) * Math.max(0, 1f - (explosionT - 3) * 0.3f);
            // Glow
            for (int g = 0; g < 5; g++) {
                float glowR = 80 + g * 30;
                float glowA = fireIntensity * (0.15f - g * 0.025f);
                drawHUDRect(fireCX - glowR, fireCY - glowR * 0.6f, glowR * 2, glowR * 1.2f,
                    1f, 0.3f + g * 0.05f, 0f, Math.max(0, glowA));
            }
            // Fire blocks (animated)
            Random fireRand = new Random((long)(menuTime * 10));
            int fireCount = (int)(15 * fireIntensity);
            for (int f = 0; f < fireCount; f++) {
                float fx = fireCX + (fireRand.nextFloat() - 0.5f) * 10 * bs;
                float fy = fireCY + fireRand.nextFloat() * 6 * bs;
                float fs = bs * (0.5f + fireRand.nextFloat() * 1.5f);
                float fr = 0.9f + fireRand.nextFloat() * 0.1f;
                float fg = 0.2f + fireRand.nextFloat() * 0.5f;
                drawHUDRect(fx, fy, fs, fs, fr, fg, 0f, fireIntensity * (0.4f + fireRand.nextFloat() * 0.5f));
            }
        }

        // === EXPLOSION PARTICLES ===
        for (int i = 0; i < mpCount; i++) {
            if (mpLife[i] <= 0) continue;
            float alpha = Math.min(1f, mpLife[i] * 0.5f);
            drawHUDRect(mpX[i], mpY[i], mpSize[i], mpSize[i],
                mpR[i], mpG[i], mpB[i], alpha);
        }

        // Smoke column (after explosion)
        if (explosionT > 0.5f && explosionT < 8f) {
            float smokeCX = buildBaseX + 12 * bs;
            float smokeBaseY = buildBaseY + 14 * bs;
            float smokeAlpha = Math.min(0.5f, (explosionT - 0.5f) * 0.3f) * Math.max(0, 1f - (explosionT - 5) * 0.3f);
            Random smokeRand = new Random((long)(menuTime * 5));
            for (int s = 0; s < 20; s++) {
                float smokeY = smokeBaseY + s * bs * 1.2f + (float)Math.sin(menuTime * 0.5 + s) * bs;
                float smokeX = smokeCX + (float)Math.sin(menuTime * 0.3 + s * 0.7) * (2 + s * 0.5f) * bs;
                float smokeSize = bs * (2 + s * 0.4f);
                float gray = 0.15f + smokeRand.nextFloat() * 0.1f;
                float sA = smokeAlpha * (1f - s / 25f);
                if (sA > 0) {
                    drawHUDRect(smokeX - smokeSize/2, smokeY, smokeSize, smokeSize * 0.7f,
                        gray, gray, gray + 0.02f, sA);
                }
            }
        }

        // === TITLE (very top) ===
        // Dark panel behind title
        float titlePanelW = screenW * 0.8f;
        float titlePanelX = (screenW - titlePanelW) / 2;
        float titlePanelY = screenH * 0.90f;
        drawHUDRect(titlePanelX, titlePanelY, titlePanelW, screenH * 0.10f, 0f, 0f, 0f, 0.6f);

        // Main title: "CHERNOBYL" in large text
        String title1 = "CHERNOBYL";
        float t1W = title1.length() * (4 * 4 + 1 * 4); // scale 4: charW=16, spacing=4
        float t1X = (screenW - t1W) / 2;
        drawHUDText(title1, t1X, screenH * 0.94f, 4, 1f, 0.5f, 0.1f, 1f);

        // Subtitle
        String title2 = "DISASTER SIMULATION GAME";
        float t2W = title2.length() * (4 * 2 + 1 * 2); // scale 2
        float t2X = (screenW - t2W) / 2;
        drawHUDText(title2, t2X, screenH * 0.905f, 2, 0.9f, 0.7f, 0.2f, 1f);

        // === MENU BUTTONS (above the building) ===
        float btnW = 320, btnH = 50;
        float btnX = (screenW - btnW) / 2;
        float btnStartY = screenH * 0.74f;
        float btnGap = 55;
        String[] btnLabels = {"START GAME", "TUTORIAL", "QUIT"};
        float[][] btnColors = {
            {0.6f, 0.35f, 0.08f},  // Warm amber
            {0.45f, 0.25f, 0.08f},  // Dark amber
            {0.55f, 0.15f, 0.05f}   // Deep red-orange
        };

        for (int i = 0; i < 3; i++) {
            float by = btnStartY - i * btnGap;
            boolean hovered = (menuHovered == i);
            float brightness = hovered ? 1.4f : 1f;
            float pulse = hovered ? (float)(Math.sin(menuTime * 5) * 0.05f + 1.05f) : 1f;
            float bw = btnW * pulse;
            float bx = (screenW - bw) / 2;

            // Button background
            drawHUDRect(bx, by, bw, btnH,
                btnColors[i][0] * brightness, btnColors[i][1] * brightness, btnColors[i][2] * brightness,
                hovered ? 0.95f : 0.8f);
            // Button border
            drawHUDRect(bx, by + btnH - 3, bw, 3,
                btnColors[i][0] * 2f, btnColors[i][1] * 2f, btnColors[i][2] * 2f, hovered ? 1f : 0.6f);
            drawHUDRect(bx, by, bw, 3,
                btnColors[i][0] * 0.5f, btnColors[i][1] * 0.5f, btnColors[i][2] * 0.5f, hovered ? 1f : 0.6f);
            // Button text
            float tw = btnLabels[i].length() * (4 * 2 + 1 * 2);
            float tx = (screenW - tw) / 2;
            drawHUDText(btnLabels[i], tx, by + 18, 2, 1f, 1f, 1f, 1f);
        }

        // Version / credit text
        drawHUDText("UNIT 4 - RBMK-1000 - 1986", 15, 15, 2, 0.4f, 0.3f, 0.15f, 0.7f);

        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
    }

    // Returns {r, g, b} for a building block at grid position, or null if empty
    private float[] getMenuBuildingBlock(int gx, int gy) {
        // Main reactor hall base: cols 3-20, rows 0-10
        if (gx >= 3 && gx <= 20 && gy >= 0 && gy <= 10) {
            // Windows
            if (gy == 3 || gy == 7) {
                if (gx == 5 || gx == 8 || gx == 11 || gx == 14 || gx == 17) {
                    return new float[]{0.12f, 0.15f, 0.2f}; // dark window
                }
            }
            // Door
            if (gx >= 10 && gx <= 12 && gy >= 0 && gy <= 2) {
                return new float[]{0.2f, 0.18f, 0.15f}; // dark door
            }
            // Left/right wall edges darker
            if (gx == 3 || gx == 20) {
                return new float[]{0.35f, 0.33f, 0.30f};
            }
            // Base row slightly darker
            if (gy == 0) {
                return new float[]{0.38f, 0.36f, 0.33f};
            }
            return new float[]{0.50f, 0.48f, 0.45f}; // concrete
        }

        // Upper reactor hall: cols 5-18, rows 10-15
        if (gx >= 5 && gx <= 18 && gy >= 10 && gy <= 15) {
            // Roof peak
            if (gy == 15) {
                if (gx < 7 || gx > 16) return null;
                return new float[]{0.28f, 0.27f, 0.25f}; // dark roof
            }
            if (gy == 14) {
                return new float[]{0.32f, 0.30f, 0.28f}; // roof edge
            }
            // Upper windows
            if (gy == 12 && (gx == 7 || gx == 10 || gx == 13 || gx == 16)) {
                return new float[]{0.15f, 0.18f, 0.25f}; // blue-ish window
            }
            if (gx == 5 || gx == 18) {
                return new float[]{0.40f, 0.38f, 0.35f}; // edge
            }
            return new float[]{0.52f, 0.50f, 0.47f}; // lighter concrete
        }

        // Ventilation chimney: cols 24-26, rows 0-22
        if (gx >= 24 && gx <= 26 && gy >= 0 && gy <= 22) {
            // Red and white stripes (every 3 rows)
            if ((gy / 3) % 2 == 0) {
                return new float[]{0.8f, 0.15f, 0.1f}; // red stripe
            } else {
                return new float[]{0.85f, 0.85f, 0.8f}; // white stripe
            }
        }
        // Chimney cap
        if (gx >= 23 && gx <= 27 && gy >= 22 && gy <= 23) {
            return new float[]{0.4f, 0.38f, 0.35f};
        }

        // Small auxiliary building: cols 21-22, rows 0-5
        if (gx >= 21 && gx <= 23 && gy >= 0 && gy <= 5) {
            if (gy == 5) return new float[]{0.30f, 0.28f, 0.25f}; // roof
            return new float[]{0.42f, 0.40f, 0.37f};
        }

        // Cooling pipes / connector: cols 1-2, rows 0-6
        if (gx >= 1 && gx <= 2 && gy >= 0 && gy <= 6) {
            if (gy == 6) return new float[]{0.30f, 0.28f, 0.25f};
            return new float[]{0.45f, 0.43f, 0.40f};
        }

        return null; // empty
    }

    // =============================================
    // === ENDING SYSTEM ===
    // =============================================

    private void updateEnding(long window, float dt) {
        endingSceneTimer += dt;

        // Ensure cursor is visible
        if (glfwGetInputMode(window, GLFW_CURSOR) != GLFW_CURSOR_NORMAL) {
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
        }

        // Track mouse
        double[] mx = new double[1], my = new double[1];
        glfwGetCursorPos(window, mx, my);
        int[] ww = new int[1], wh = new int[1];
        glfwGetWindowSize(window, ww, wh);
        float mouseX = (float) mx[0];
        float mouseY = (float)(wh[0] - my[0]);
        float screenW = (float) ww[0];
        float screenH = (float) wh[0];

        // RETURN TO MENU button (appears after 12 seconds)
        endingBtnHovered = -1;
        if (endingSceneTimer > 12f) {
            float btnW = 320, btnH = 50;
            float btnX = (screenW - btnW) / 2;
            float btnY = screenH * 0.08f;
            if (mouseX >= btnX && mouseX <= btnX + btnW &&
                mouseY >= btnY && mouseY <= btnY + btnH) {
                endingBtnHovered = 0;
            }
        }

        // Click detection
        boolean leftNow = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS;
        if (leftNow && !mouseLeftHeld) {
            if (endingBtnHovered == 0) {
                // Return to menu - reset game state
                resetGameForMenu();
            }
        }
        mouseLeftHeld = leftNow;
        mouseRightHeld = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_RIGHT) == GLFW_PRESS;

        // Spawn particles
        if (!endingParticlesSpawned && endingSceneTimer > 3f) {
            endingParticlesSpawned = true;
            epCount = 0;
            Random pRand = new Random((long)(endingSceneTimer * 1000));
            if (playerWon) {
                // Confetti particles
                for (int i = 0; i < END_MAX_PARTS; i++) {
                    epX[i] = pRand.nextFloat() * screenW;
                    epY[i] = screenH + pRand.nextFloat() * 100;
                    epVX[i] = (pRand.nextFloat() - 0.5f) * 80;
                    epVY[i] = -(40 + pRand.nextFloat() * 80);
                    // Bright confetti colors: red, gold, green, blue, white
                    int colorChoice = pRand.nextInt(5);
                    switch (colorChoice) {
                        case 0: epR[i] = 1f; epG[i] = 0.2f; epB[i] = 0.2f; break;
                        case 1: epR[i] = 1f; epG[i] = 0.85f; epB[i] = 0.1f; break;
                        case 2: epR[i] = 0.2f; epG[i] = 0.9f; epB[i] = 0.2f; break;
                        case 3: epR[i] = 0.2f; epG[i] = 0.4f; epB[i] = 1f; break;
                        case 4: epR[i] = 1f; epG[i] = 1f; epB[i] = 1f; break;
                    }
                    epLife[i] = 5f + pRand.nextFloat() * 8f;
                    epSize[i] = 3 + pRand.nextFloat() * 6;
                    epCount++;
                }
            } else {
                // Ember / ash particles
                for (int i = 0; i < END_MAX_PARTS; i++) {
                    epX[i] = screenW * 0.3f + pRand.nextFloat() * screenW * 0.4f;
                    epY[i] = screenH * 0.15f + pRand.nextFloat() * screenH * 0.2f;
                    epVX[i] = (pRand.nextFloat() - 0.5f) * 40;
                    epVY[i] = 20 + pRand.nextFloat() * 60;
                    float ember = pRand.nextFloat();
                    if (ember < 0.5f) {
                        epR[i] = 0.8f + pRand.nextFloat() * 0.2f;
                        epG[i] = 0.2f + pRand.nextFloat() * 0.3f;
                        epB[i] = 0f;
                    } else {
                        float gray = 0.2f + pRand.nextFloat() * 0.2f;
                        epR[i] = gray; epG[i] = gray; epB[i] = gray;
                    }
                    epLife[i] = 4f + pRand.nextFloat() * 8f;
                    epSize[i] = 2 + pRand.nextFloat() * 5;
                    epCount++;
                }
            }
        }

        // Update particles
        for (int i = 0; i < epCount; i++) {
            if (epLife[i] <= 0) continue;
            epLife[i] -= dt;
            epX[i] += epVX[i] * dt;
            epY[i] += epVY[i] * dt;
            if (playerWon) {
                epVY[i] -= 15f * dt; // slow gravity for confetti
                epVX[i] += (float)Math.sin(endingSceneTimer * 3 + i) * 10f * dt; // flutter
            } else {
                epVY[i] -= 5f * dt; // very slow rising embers
                epVX[i] *= 0.995f;
            }
        }

        // Fullscreen toggle
        if (glfwGetKey(window, GLFW_KEY_F) == GLFW_PRESS) {
            if (!fKeyPressed) { fKeyPressed = true; toggleFullscreen(window); }
        } else { fKeyPressed = false; }
    }

    private void resetGameForMenu() {
        gameState = STATE_MENU;
        storyPhase = 0;
        playerWon = false;
        endingTimer = 0;
        endingCountdownActive = false;
        endingSceneTimer = 0;
        endingParticlesSpawned = false;
        epCount = 0;
        az5Pressed = false;
        playerReducedPower = false;
        playerRaisedPower = false;
        playerStartedTest = false;
        playerDisconnectedTurbine = false;
        playerCheckedElena = false;
        playerReadIndicators = false;
        playerConfirmedTestReady = false;
        phaseTimer = 0;
        elenaScanCount = 0;
        elenaUniqueScanCount = 0;
        elenaHotSpotsFound = 0;
        elenaVoidSectorsFound = 0;
        elenaScanStabilityBonus = 0f;
        elenaXenonWarningGiven = false;
        elenaVoidWarningGiven = false;
        elenaScanNeglectPenalty = 0f;
        java.util.Arrays.fill(elenaScannedSectors, false);
        indicatorDriftPower = 0f;
        indicatorDriftTemp = 0f;
        indicatorDriftPressure = 0f;
        indicatorDriftFlux = 0f;
        indicatorDriftXenon = 0f;
        indicatorDriftCoolant = 0f;
        indicatorCheckTimer = 0f;
        indicatorCheckBonus = 0f;
        indicatorNeglectPenalty = 0f;
        indicatorCalibrationsDone = 0;
        indicatorAlarmsAcked = 0;
        java.util.Arrays.fill(indicatorAlarmActive, false);
        java.util.Arrays.fill(indicatorAlarmAcknowledged, false);
        java.util.Arrays.fill(powerHistory, 0f);
        java.util.Arrays.fill(tempHistory, 0f);
        java.util.Arrays.fill(pressureHistory, 0f);
        paramHistoryIdx = 0;
        paramHistoryTimer = 0f;
        indicatorHoveredGauge = -1;
        machineUIActive = false;
        dialogueActive = false;
        storyIntroShown = false;
        currentObjective = "";
        notificationTimer = 0;
        menuTime = 0;
        menuParticlesSpawned = false;
        // Reset reactor
        reactorPower = 1600;
        controlRodsInserted = 211;
        reactorTemperature = 250;
        reactorPressure = 65;
        reactorStability = 100;
        coolantFlow = 80;
        xenonLevel = 0;
        neutronFlux = 100;
        voidCoefficient = 0;
        coolantPumpsOn = true;
        turbineConnected = true;
        emergencyCoolingOn = true;
        reactorHallDoorOpen = false;
    }

    private void renderEndingScreen() {
        setupHUD2D();

        int[] wBuf = new int[1], hBuf = new int[1];
        glfwGetFramebufferSize(glfwGetCurrentContext(), wBuf, hBuf);
        float screenW = wBuf[0] > 0 ? wBuf[0] : ChernobylGame.WIDTH;
        float screenH = hBuf[0] > 0 ? hBuf[0] : ChernobylGame.HEIGHT;

        float t = endingSceneTimer;
        float fadeIn = Math.min(1f, t / 2f); // 0-2 seconds fade in

        if (playerWon) {
            renderWinEnding(screenW, screenH, t, fadeIn);
        } else {
            renderLoseEnding(screenW, screenH, t, fadeIn);
        }

        // === RETURN TO MENU button (after 12 seconds) ===
        if (t > 12f) {
            float btnAlpha = Math.min(1f, (t - 12f) / 1f);
            float btnW = 320, btnH = 50;
            float btnX = (screenW - btnW) / 2;
            float btnY = screenH * 0.08f;
            boolean hovered = (endingBtnHovered == 0);
            float brightness = hovered ? 1.3f : 1f;

            drawHUDRect(btnX, btnY, btnW, btnH,
                0.2f * brightness, 0.12f * brightness, 0.05f * brightness, 0.9f * btnAlpha);
            drawHUDRect(btnX, btnY + btnH - 3, btnW, 3,
                0.5f * brightness, 0.3f * brightness, 0.1f * brightness, btnAlpha);
            drawHUDRect(btnX, btnY, btnW, 3,
                0.25f * brightness, 0.15f * brightness, 0.06f * brightness, btnAlpha);
            String btnLabel = "RETURN TO MENU";
            float tw = btnLabel.length() * (4 * 2 + 1 * 2);
            drawHUDText(btnLabel, (screenW - tw) / 2, btnY + 18, 2, 1f, 1f, 1f, btnAlpha);
        }

        // === Particles ===
        for (int i = 0; i < epCount; i++) {
            if (epLife[i] <= 0) continue;
            float alpha = Math.min(1f, epLife[i] * 0.4f) * fadeIn;
            drawHUDRect(epX[i], epY[i], epSize[i], epSize[i],
                epR[i], epG[i], epB[i], alpha);
        }

        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
    }

    // ===== WIN ENDING: Kremlin congratulation scene =====
    private void renderWinEnding(float screenW, float screenH, float t, float fadeIn) {
        // Background: Deep warm dark sky with stars
        drawHUDRect(0, 0, screenW, screenH, 0.06f * fadeIn, 0.03f * fadeIn, 0.01f * fadeIn, 1f);

        // Stars
        Random starRand = new Random(54321);
        for (int i = 0; i < 50; i++) {
            float sx = starRand.nextFloat() * screenW;
            float sy = screenH * 0.55f + starRand.nextFloat() * screenH * 0.45f;
            float twinkle = (float)(Math.sin(t * (1.5 + starRand.nextFloat() * 2) + i) * 0.3 + 0.7);
            float sSize = 1 + starRand.nextFloat() * 2;
            drawHUDRect(sx, sy, sSize, sSize, 0.9f, 0.65f, 0.3f, twinkle * 0.6f * fadeIn);
        }

        // Ground: Red Square cobblestone
        drawHUDRect(0, 0, screenW, screenH * 0.15f, 0.2f * fadeIn, 0.1f * fadeIn, 0.04f * fadeIn, 1f);
        Random gndRand = new Random(777);
        for (int i = 0; i < 50; i++) {
            float gx = gndRand.nextFloat() * screenW;
            float gy = gndRand.nextFloat() * screenH * 0.13f;
            float gs = 5 + gndRand.nextFloat() * 10;
            drawHUDRect(gx, gy, gs, gs,
                0.3f * fadeIn, 0.15f * fadeIn, 0.1f * fadeIn, 0.6f);
        }

        // === KREMLIN BUILDING (Minecraft pixel-art) ===
        float bs = Math.min(screenW / 80f, screenH / 50f); // block size
        float kremlinX = (screenW - 40 * bs) / 2;
        float kremlinY = screenH * 0.15f;

        // Main wall
        for (int gy = 0; gy < 12; gy++) {
            for (int gx = 0; gx < 40; gx++) {
                float bx = kremlinX + gx * bs;
                float by = kremlinY + gy * bs;
                // Red brick wall
                float r = 0.6f + ((gx + gy) % 3) * 0.03f;
                float g = 0.15f + ((gx + gy) % 3) * 0.02f;
                float b = 0.08f;
                // Battlements at top
                if (gy == 11 && (gx % 4 == 0 || gx % 4 == 1)) continue; // gaps in battlements
                // Gate
                if (gx >= 18 && gx <= 21 && gy <= 5) {
                    drawHUDRect(bx, by, bs - 1, bs - 1, 0.15f * fadeIn, 0.1f * fadeIn, 0.05f * fadeIn, 1f);
                    continue;
                }
                // Windows
                if ((gy == 4 || gy == 8) && (gx == 5 || gx == 10 || gx == 15 || gx == 24 || gx == 29 || gx == 34)) {
                    drawHUDRect(bx, by, bs - 1, bs - 1, 0.9f * fadeIn, 0.8f * fadeIn, 0.3f * fadeIn, 1f);
                    continue;
                }
                drawHUDRect(bx, by, bs - 1, bs - 1, r * fadeIn, g * fadeIn, b * fadeIn, 1f);
            }
        }

        // Central tower (Spasskaya tower style)
        float towerX = kremlinX + 17 * bs;
        for (int gy = 12; gy < 22; gy++) {
            for (int gx = 0; gx < 6; gx++) {
                float bx = towerX + gx * bs;
                float by = kremlinY + gy * bs;
                // Narrowing as it goes up
                if (gy >= 18) {
                    if (gx < 1 || gx > 4) continue;
                    // Green roof/spire
                    drawHUDRect(bx, by, bs - 1, bs - 1, 0.1f * fadeIn, 0.35f * fadeIn, 0.15f * fadeIn, 1f);
                } else if (gy >= 15) {
                    // White accent
                    drawHUDRect(bx, by, bs - 1, bs - 1, 0.85f * fadeIn, 0.82f * fadeIn, 0.75f * fadeIn, 1f);
                } else {
                    // Red brick
                    drawHUDRect(bx, by, bs - 1, bs - 1, 0.55f * fadeIn, 0.13f * fadeIn, 0.07f * fadeIn, 1f);
                }
            }
        }

        // Clock face on tower
        float clockX = towerX + 2 * bs;
        float clockY = kremlinY + 14 * bs;
        drawHUDRect(clockX, clockY, bs * 2, bs * 2, 0.9f * fadeIn, 0.9f * fadeIn, 0.8f * fadeIn, 1f);
        drawHUDRect(clockX + bs * 0.8f, clockY + bs * 0.3f, bs * 0.3f, bs * 1.2f, 0.1f * fadeIn, 0.1f * fadeIn, 0.1f * fadeIn, 1f);

        // Red star at very top
        float starX = towerX + 2.5f * bs;
        float starY = kremlinY + 22 * bs;
        drawHUDRect(starX - bs * 0.4f, starY, bs * 1.8f, bs * 1.5f, 0.9f * fadeIn, 0.15f * fadeIn, 0.1f * fadeIn, 1f);
        // Star glow
        float glow = (float)(Math.sin(t * 3) * 0.2 + 0.8);
        drawHUDRect(starX - bs * 1f, starY - bs * 0.3f, bs * 3f, bs * 2.2f, 0.9f, 0.2f, 0.1f, 0.15f * glow * fadeIn);

        // Side towers
        for (int side = 0; side < 2; side++) {
            float stX = kremlinX + (side == 0 ? 2 : 35) * bs;
            for (int gy = 12; gy < 17; gy++) {
                for (int gx = 0; gx < 3; gx++) {
                    float bx = stX + gx * bs;
                    float by = kremlinY + gy * bs;
                    if (gy >= 15) {
                        drawHUDRect(bx, by, bs - 1, bs - 1, 0.1f * fadeIn, 0.3f * fadeIn, 0.12f * fadeIn, 1f);
                    } else {
                        drawHUDRect(bx, by, bs - 1, bs - 1, 0.55f * fadeIn, 0.13f * fadeIn, 0.07f * fadeIn, 1f);
                    }
                }
            }
        }

        // === ANIMATED NPCs: Kremlin Officials walking in ===
        float npcStartTime = 3f;
        float npcWalkDuration = 4f;
        if (t > npcStartTime) {
            float walkProgress = Math.min(1f, (t - npcStartTime) / npcWalkDuration);
            float easeProgress = walkProgress * walkProgress * (3f - 2f * walkProgress); // smoothstep

            // 3 Officials walking from right side to center
            for (int n = 0; n < 3; n++) {
                float npcDelay = n * 0.3f;
                float npcProgress = Math.min(1f, Math.max(0f, (t - npcStartTime - npcDelay) / npcWalkDuration));
                float npcEase = npcProgress * npcProgress * (3f - 2f * npcProgress);

                float startNX = screenW + 50 + n * 80;
                float endNX = screenW * 0.55f + n * bs * 4;
                float npcX = startNX + (endNX - startNX) * npcEase;
                float npcY = screenH * 0.15f;

                drawMinecraftNPC(npcX, npcY, bs * 0.8f, fadeIn, t, npcProgress > 0 && npcProgress < 1,
                    0.15f, 0.15f, 0.2f,   // Dark suit body
                    0.85f, 0.7f, 0.55f,    // Face skin tone
                    0.1f, 0.1f, 0.12f);    // Dark suit legs
            }

            // 3 Engineers standing at center-left
            for (int n = 0; n < 3; n++) {
                float engX = screenW * 0.25f + n * bs * 4;
                float engY = screenH * 0.15f;

                drawMinecraftNPC(engX, engY, bs * 0.8f, fadeIn, t, false,
                    0.7f, 0.7f, 0.65f,    // White lab coat body
                    0.85f, 0.7f, 0.55f,    // Face
                    0.3f, 0.3f, 0.35f);    // Dark pants
            }
        }

        // === CONGRATULATION TEXT ===
        float textStartTime = 7f;
        if (t > textStartTime) {
            float textAlpha = Math.min(1f, (t - textStartTime) / 1.5f);

            // Dark panel behind text
            float panelW = screenW * 0.75f;
            float panelX = (screenW - panelW) / 2;
            float panelY = screenH * 0.72f;
            drawHUDRect(panelX, panelY, panelW, screenH * 0.22f, 0f, 0f, 0f, 0.7f * textAlpha);

            // Main title
            String title1 = "CONGRATULATIONS";
            float t1W = title1.length() * (4 * 4 + 1 * 4);
            drawHUDText(title1, (screenW - t1W) / 2, screenH * 0.87f, 4, 1f, 0.6f, 0.1f, textAlpha);

            // Subtitle
            String sub1 = "THE REACTOR WAS SAFELY SHUT DOWN";
            float s1W = sub1.length() * (4 * 2 + 1 * 2);
            drawHUDText(sub1, (screenW - s1W) / 2, screenH * 0.81f, 2, 1f, 0.7f, 0.2f, textAlpha);

            String sub2 = "THE SOVIET UNION COMMENDS YOUR SERVICE";
            float s2W = sub2.length() * (4 * 2 + 1 * 2);
            drawHUDText(sub2, (screenW - s2W) / 2, screenH * 0.77f, 2, 0.9f, 0.65f, 0.2f, textAlpha);
        }

        // Handshake indicator (after NPCs arrive)
        if (t > 8f) {
            float hsAlpha = (float)(Math.sin(t * 2) * 0.3 + 0.7) * Math.min(1f, (t - 8f));
            String hsText = "* OFFICIALS SHAKE HANDS WITH ENGINEERS *";
            float hsW = hsText.length() * (4 * 1 + 1 * 1);
            drawHUDText(hsText, (screenW - hsW) / 2, screenH * 0.35f, 1, 0.9f, 0.7f, 0.3f, hsAlpha * fadeIn);
        }

        // Soviet flag waving (top right)
        if (t > 5f) {
            float flagAlpha = Math.min(1f, (t - 5f) / 2f) * fadeIn;
            float flagX = screenW * 0.82f;
            float flagY = screenH * 0.50f;
            float flagW = bs * 8;
            float flagH = bs * 5;
            // Pole
            drawHUDRect(flagX - bs * 0.5f, kremlinY, bs * 0.5f, flagY + flagH - kremlinY, 0.3f, 0.3f, 0.3f, flagAlpha);
            // Red flag with wave
            for (int fy = 0; fy < 5; fy++) {
                for (int fx = 0; fx < 8; fx++) {
                    float wave = (float)Math.sin(t * 2 + fx * 0.5f) * bs * 0.3f;
                    drawHUDRect(flagX + fx * bs, flagY + fy * bs + wave, bs - 1, bs - 1,
                        0.8f, 0.1f, 0.08f, flagAlpha);
                }
            }
            // Hammer and sickle area (simplified - yellow star)
            drawHUDRect(flagX + bs, flagY + 3 * bs, bs * 2, bs * 2, 0.95f, 0.85f, 0.1f, flagAlpha);
        }
    }

    // ===== LOSE ENDING: Prison scene =====
    private void renderLoseEnding(float screenW, float screenH, float t, float fadeIn) {
        // Background: Dark red/grey overcast sky
        float skyR = 0.12f + (float)Math.sin(t * 0.5) * 0.02f;
        drawHUDRect(0, 0, screenW, screenH, skyR * fadeIn, 0.03f * fadeIn, 0.02f * fadeIn, 1f);
        // Orange glow at horizon (nuclear fire in distance)
        drawHUDRect(0, screenH * 0.12f, screenW, screenH * 0.15f, 0.3f * fadeIn, 0.08f * fadeIn, 0.02f * fadeIn, 0.5f);

        // Ground: Warm dark
        drawHUDRect(0, 0, screenW, screenH * 0.14f, 0.1f * fadeIn, 0.07f * fadeIn, 0.03f * fadeIn, 1f);
        Random gndRand = new Random(888);
        for (int i = 0; i < 40; i++) {
            float gx = gndRand.nextFloat() * screenW;
            float gy = gndRand.nextFloat() * screenH * 0.12f;
            float gs = 4 + gndRand.nextFloat() * 8;
            drawHUDRect(gx, gy, gs, gs, 0.08f * fadeIn, 0.08f * fadeIn, 0.06f * fadeIn, 0.7f);
        }

        // === PRISON BUILDING (Minecraft pixel-art) ===
        float bs = Math.min(screenW / 80f, screenH / 50f);
        float prisonX = (screenW - 44 * bs) / 2;
        float prisonY = screenH * 0.14f;

        // Main prison wall (dark grey concrete)
        for (int gy = 0; gy < 14; gy++) {
            for (int gx = 0; gx < 44; gx++) {
                float bx = prisonX + gx * bs;
                float by = prisonY + gy * bs;

                // Barred windows
                if ((gy == 5 || gy == 9) && gx > 2 && gx < 41 && gx % 5 == 0) {
                    // Window with bars
                    drawHUDRect(bx, by, bs - 1, bs - 1, 0.05f * fadeIn, 0.05f * fadeIn, 0.08f * fadeIn, 1f);
                    // Vertical bars
                    drawHUDRect(bx + bs * 0.2f, by, bs * 0.15f, bs, 0.25f * fadeIn, 0.25f * fadeIn, 0.25f * fadeIn, 1f);
                    drawHUDRect(bx + bs * 0.6f, by, bs * 0.15f, bs, 0.25f * fadeIn, 0.25f * fadeIn, 0.25f * fadeIn, 1f);
                    continue;
                }
                // Prison gate (center)
                if (gx >= 20 && gx <= 23 && gy <= 4) {
                    // Iron gate
                    drawHUDRect(bx, by, bs - 1, bs - 1, 0.2f * fadeIn, 0.2f * fadeIn, 0.18f * fadeIn, 1f);
                    // Gate bars
                    if (gx == 20 || gx == 23) {
                        drawHUDRect(bx + bs * 0.4f, by, bs * 0.2f, bs, 0.35f * fadeIn, 0.35f * fadeIn, 0.3f * fadeIn, 1f);
                    }
                    continue;
                }
                // Roof edge
                if (gy == 13) {
                    drawHUDRect(bx, by, bs - 1, bs - 1, 0.18f * fadeIn, 0.17f * fadeIn, 0.15f * fadeIn, 1f);
                    continue;
                }
                // Wall variation
                float wallBright = 0.25f + ((gx + gy * 3) % 5) * 0.01f;
                drawHUDRect(bx, by, bs - 1, bs - 1, wallBright * fadeIn, wallBright * 0.95f * fadeIn, wallBright * 0.9f * fadeIn, 1f);
            }
        }

        // Guard towers (corners)
        for (int side = 0; side < 2; side++) {
            float tX = prisonX + (side == 0 ? -2 : 43) * bs;
            for (int gy = 0; gy < 18; gy++) {
                for (int gx = 0; gx < 3; gx++) {
                    float bx = tX + gx * bs;
                    float by = prisonY + gy * bs;
                    if (gy >= 14) {
                        // Watchtower cabin
                        drawHUDRect(bx, by, bs - 1, bs - 1, 0.2f * fadeIn, 0.18f * fadeIn, 0.15f * fadeIn, 1f);
                        // Window light
                        if (gy == 15 && gx == 1) {
                            float flicker = (float)(Math.sin(t * 5 + side * 3) * 0.15 + 0.85);
                            drawHUDRect(bx, by, bs - 1, bs - 1, 0.8f * flicker * fadeIn, 0.7f * flicker * fadeIn, 0.2f * flicker * fadeIn, 1f);
                        }
                    } else {
                        drawHUDRect(bx, by, bs - 1, bs - 1, 0.22f * fadeIn, 0.2f * fadeIn, 0.18f * fadeIn, 1f);
                    }
                }
            }
            // Searchlight beam
            float beamAlpha = (float)(Math.sin(t * 0.8 + side * Math.PI) * 0.2 + 0.3) * fadeIn;
            float beamX = tX + 1.5f * bs;
            float beamY = prisonY + 18 * bs;
            drawHUDRect(beamX - bs * 2, beamY, bs * 5, bs * 8, 0.9f, 0.9f, 0.7f, beamAlpha * 0.1f);
        }

        // Barbed wire along top
        for (int gx = 0; gx < 44; gx++) {
            float bx = prisonX + gx * bs;
            float by = prisonY + 14 * bs;
            float waveY = (float)Math.sin(gx * 1.2f) * bs * 0.3f;
            drawHUDRect(bx, by + waveY, bs * 0.6f, bs * 0.3f, 0.35f * fadeIn, 0.35f * fadeIn, 0.3f * fadeIn, 0.9f);
            // Barbs
            if (gx % 3 == 0) {
                drawHUDRect(bx + bs * 0.2f, by + waveY + bs * 0.3f, bs * 0.2f, bs * 0.4f, 0.35f * fadeIn, 0.35f * fadeIn, 0.3f * fadeIn, 0.8f);
            }
        }

        // === ANIMATED NPCs: Officials escorting engineers to prison ===
        float npcStartTime = 3f;
        float npcWalkDuration = 5f;
        if (t > npcStartTime) {
            float walkProgress = Math.min(1f, (t - npcStartTime) / npcWalkDuration);
            float easeProgress = walkProgress * walkProgress * (3f - 2f * walkProgress);

            // Target: the prison gate
            float gateX = prisonX + 21 * bs;

            // 3 Engineers (heads down, being escorted)
            for (int n = 0; n < 3; n++) {
                float npcDelay = n * 0.5f;
                float npcProgress = Math.min(1f, Math.max(0f, (t - npcStartTime - npcDelay) / npcWalkDuration));
                float npcEase = npcProgress * npcProgress * (3f - 2f * npcProgress);

                float startNX = screenW * 0.75f + n * bs * 5;
                float endNX = gateX - n * bs * 2;
                float npcNX = startNX + (endNX - startNX) * npcEase;
                float npcY = screenH * 0.14f;

                // Engineers in white coats (heads slightly down via smaller head offset)
                drawMinecraftNPC(npcNX, npcY, bs * 0.8f, fadeIn, t, npcProgress > 0 && npcProgress < 1,
                    0.6f, 0.6f, 0.55f,    // Dirty white coat
                    0.75f, 0.6f, 0.45f,    // Face (paler)
                    0.25f, 0.25f, 0.28f);  // Dark pants
            }

            // 2 KGB Officers (behind engineers)
            for (int n = 0; n < 2; n++) {
                float npcDelay = n * 0.4f;
                float npcProgress = Math.min(1f, Math.max(0f, (t - npcStartTime - npcDelay) / npcWalkDuration));
                float npcEase = npcProgress * npcProgress * (3f - 2f * npcProgress);

                float startNX = screenW * 0.85f + n * bs * 5;
                float endNX = gateX + bs * 6 + n * bs * 4;
                float npcNX = startNX + (endNX - startNX) * npcEase;
                float npcY = screenH * 0.14f;

                // Officers in dark uniforms
                drawMinecraftNPC(npcNX, npcY, bs * 0.85f, fadeIn, t, npcProgress > 0 && npcProgress < 1,
                    0.12f, 0.15f, 0.1f,   // Dark green military
                    0.85f, 0.7f, 0.55f,    // Face
                    0.1f, 0.12f, 0.08f);   // Dark boots
            }
        }

        // === SENTENCING TEXT ===
        float textStartTime = 8f;
        if (t > textStartTime) {
            float textAlpha = Math.min(1f, (t - textStartTime) / 1.5f);

            // Dark panel behind text
            float panelW = screenW * 0.75f;
            float panelX = (screenW - panelW) / 2;
            float panelY = screenH * 0.72f;
            drawHUDRect(panelX, panelY, panelW, screenH * 0.22f, 0f, 0f, 0f, 0.75f * textAlpha);

            // Main title: Red text
            String title1 = "SENTENCED";
            float t1W = title1.length() * (4 * 4 + 1 * 4);
            drawHUDText(title1, (screenW - t1W) / 2, screenH * 0.87f, 4, 1f, 0.15f, 0.1f, textAlpha);

            // Subtitle
            String sub1 = "THE ENGINEERS WERE FOUND GUILTY";
            float s1W = sub1.length() * (4 * 2 + 1 * 2);
            drawHUDText(sub1, (screenW - s1W) / 2, screenH * 0.81f, 2, 0.9f, 0.5f, 0.3f, textAlpha);

            String sub2 = "AND IMPRISONED FOR NEGLIGENCE";
            float s2W = sub2.length() * (4 * 2 + 1 * 2);
            drawHUDText(sub2, (screenW - s2W) / 2, screenH * 0.77f, 2, 0.7f, 0.4f, 0.3f, textAlpha);

            // Historical note
            if (t > textStartTime + 3f) {
                float noteAlpha = Math.min(1f, (t - textStartTime - 3f) / 2f);
                String note = "THE CHERNOBYL DISASTER - 26 APRIL 1986";
                float nW = note.length() * (4 * 1 + 1 * 1);
                drawHUDText(note, (screenW - nW) / 2, screenH * 0.73f, 1, 0.5f, 0.5f, 0.4f, noteAlpha * textAlpha);
            }
        }

        // Distant nuclear glow on horizon (pulsing)
        float glowPulse = (float)(Math.sin(t * 0.7) * 0.15 + 0.85);
        drawHUDRect(screenW * 0.1f, screenH * 0.12f, screenW * 0.2f, screenH * 0.25f,
            0.9f, 0.3f, 0.05f, 0.08f * glowPulse * fadeIn);
    }

    // Draw a Minecraft-style pixel NPC
    private void drawMinecraftNPC(float x, float y, float scale, float fadeIn, float time,
                                   boolean walking,
                                   float bodyR, float bodyG, float bodyB,
                                   float skinR, float skinG, float skinB,
                                   float legR, float legG, float legB) {
        float s = scale;
        // Walking animation
        float legSwing = walking ? (float)Math.sin(time * 6) * s * 0.4f : 0;
        float armSwing = walking ? (float)Math.sin(time * 6) * s * 0.3f : 0;

        // Legs (2 blocks wide each, 3 blocks tall)
        drawHUDRect(x - s * 1f, y, s * 0.9f, s * 3 + legSwing, legR * fadeIn, legG * fadeIn, legB * fadeIn, 1f);
        drawHUDRect(x + s * 0.1f, y, s * 0.9f, s * 3 - legSwing, legR * fadeIn, legG * fadeIn, legB * fadeIn, 1f);

        // Body (2 blocks wide, 3 blocks tall)
        drawHUDRect(x - s * 1.2f, y + s * 3, s * 2.4f, s * 3, bodyR * fadeIn, bodyG * fadeIn, bodyB * fadeIn, 1f);

        // Arms (1 block wide each, 3 blocks tall)
        drawHUDRect(x - s * 2f, y + s * 3 + armSwing, s * 0.8f, s * 2.8f, bodyR * 0.85f * fadeIn, bodyG * 0.85f * fadeIn, bodyB * 0.85f * fadeIn, 1f);
        drawHUDRect(x + s * 1.2f, y + s * 3 - armSwing, s * 0.8f, s * 2.8f, bodyR * 0.85f * fadeIn, bodyG * 0.85f * fadeIn, bodyB * 0.85f * fadeIn, 1f);

        // Head (2x2 blocks)
        drawHUDRect(x - s * 0.9f, y + s * 6, s * 1.8f, s * 1.8f, skinR * fadeIn, skinG * fadeIn, skinB * fadeIn, 1f);
        // Hair
        drawHUDRect(x - s * 1f, y + s * 7.2f, s * 2f, s * 0.7f, 0.15f * fadeIn, 0.1f * fadeIn, 0.05f * fadeIn, 1f);
        // Eyes
        drawHUDRect(x - s * 0.5f, y + s * 6.8f, s * 0.25f, s * 0.25f, 0.1f * fadeIn, 0.1f * fadeIn, 0.1f * fadeIn, 1f);
        drawHUDRect(x + s * 0.3f, y + s * 6.8f, s * 0.25f, s * 0.25f, 0.1f * fadeIn, 0.1f * fadeIn, 0.1f * fadeIn, 1f);
    }

    private void renderTutorialScreen() {
        setupHUD2D();

        int[] wBuf = new int[1], hBuf = new int[1];
        glfwGetFramebufferSize(glfwGetCurrentContext(), wBuf, hBuf);
        float screenW = wBuf[0] > 0 ? wBuf[0] : ChernobylGame.WIDTH;
        float screenH = hBuf[0] > 0 ? hBuf[0] : ChernobylGame.HEIGHT;

        // Background
        drawHUDRect(0, 0, screenW, screenH, 0.06f, 0.03f, 0.01f, 1f);

        // Card
        float cardW = screenW * 0.82f;
        float cardH = screenH * 0.82f;
        float cardX = (screenW - cardW) / 2;
        float cardY = (screenH - cardH) / 2;

        drawHUDRect(cardX, cardY, cardW, cardH, 0.08f, 0.05f, 0.02f, 0.95f);
        // Card border
        drawHUDRect(cardX, cardY + cardH - 3, cardW, 3, 0.7f, 0.4f, 0.1f, 1f);
        drawHUDRect(cardX, cardY, cardW, 3, 0.7f, 0.4f, 0.1f, 1f);
        drawHUDRect(cardX, cardY, 3, cardH, 0.7f, 0.4f, 0.1f, 1f);
        drawHUDRect(cardX + cardW - 3, cardY, 3, cardH, 0.7f, 0.4f, 0.1f, 1f);

        // Step indicator
        String stepTxt = "STEP " + (tutorialStep + 1) + " OF " + TUTORIAL_TOTAL;
        drawHUDText(stepTxt, cardX + cardW - stepTxt.length() * 10 - 25,
            cardY + cardH - 30, 2, 0.5f, 0.35f, 0.15f, 1f);

        // Progress bar
        float progW = cardW - 60;
        float progH = 10;
        float progX = cardX + 30;
        float progY = cardY + cardH - 60;
        drawHUDRect(progX, progY, progW, progH, 0.15f, 0.1f, 0.05f, 1f);
        float fillW = progW * ((tutorialStep + 1f) / TUTORIAL_TOTAL);
        drawHUDRect(progX, progY, fillW, progH, 0.8f, 0.45f, 0.1f, 1f);

        // Content area
        float contentX = cardX + 50;
        float contentTopY = cardY + cardH - 90;

        // Tutorial content per step
        String title;
        String[] lines;
        switch (tutorialStep) {
            case 0:
                title = "CHERNOBYL - APRIL 26, 1986";
                lines = new String[]{
                    "IT IS THE NIGHT OF APRIL 25TH, 1986.",
                    "YOU ARE A REACTOR OPERATOR AT THE CHERNOBYL",
                    "NUCLEAR POWER PLANT IN SOVIET UKRAINE.",
                    "",
                    "*A SAFETY TEST HAS BEEN ORDERED ON REACTOR 4.",
                    "*THE TEST WILL PUSH THE REACTOR TO ITS LIMITS.",
                    "",
                    "YOUR JOB: FOLLOW ORDERS, OPERATE THE REACTOR,",
                    "AND TRY TO PREVENT THE WORST NUCLEAR DISASTER",
                    "IN HISTORY.",
                    "",
                    "!YOUR CHOICES DETERMINE IF YOU SAVE THE REACTOR",
                    "!OR CAUSE A CATASTROPHIC MELTDOWN."
                };
                break;
            case 1:
                title = "THE RBMK-1000 REACTOR";
                lines = new String[]{
                    "THE RBMK-1000 IS A SOVIET-DESIGNED REACTOR.",
                    "IT HAS A FATAL DESIGN FLAW:",
                    "",
                    "!POSITIVE VOID COEFFICIENT - WHEN COOLANT BOILS,",
                    "!POWER INCREASES INSTEAD OF DECREASING.",
                    "",
                    "*KEY PARAMETERS YOU MUST MONITOR:",
                    "  POWER (MW) - HOW MUCH ENERGY THE CORE PRODUCES",
                    "  TEMPERATURE (C) - CORE HEAT LEVEL",
                    "  XENON-135 - NEUTRON POISON THAT BUILDS UP",
                    "  STABILITY (%) - OVERALL REACTOR SAFETY",
                    "",
                    "+KEEP STABILITY HIGH. AT 0% THE CORE IS DOOMED."
                };
                break;
            case 2:
                title = "CONTROLS & MOVEMENT";
                lines = new String[]{
                    ">W/A/S/D - MOVE       SHIFT - SPRINT",
                    ">MOUSE - LOOK AROUND  SPACE - JUMP",
                    ">F - FULLSCREEN       V - 3RD PERSON VIEW",
                    ">M - PAUSE MENU       ESC - CLOSE PANELS",
                    "",
                    ">E - OPEN MACHINES (CONTROL PANEL / ELENA /",
                    ">    INDICATOR PANELS) WHEN STANDING NEAR THEM",
                    ">T - TALK TO NPCS WHEN STANDING NEAR THEM",
                    "",
                    "+YOU CAN SEE PROMPTS ON SCREEN WHEN NEAR",
                    "+AN INTERACTABLE OBJECT OR PERSON."
                };
                break;
            case 3:
                title = "YOUR CREW";
                lines = new String[]{
                    "THREE ENGINEERS ARE IN THE CONTROL ROOM:",
                    "",
                    ">AKIMOV (SHIFT SUPERVISOR)",
                    "+  YOUR ALLY. GIVES HELPFUL GUIDANCE AND WARNINGS.",
                    "+  LISTEN TO HIM - HE KNOWS THE PROCEDURES.",
                    "",
                    ">TOPTUNOV (REACTOR OPERATOR)",
                    "*  NERVOUS BUT KNOWLEDGEABLE. EXPLAINS TECHNICAL",
                    "*  DETAILS ABOUT THE REACTOR AND EQUIPMENT.",
                    "",
                    ">DYATLOV (DEPUTY CHIEF ENGINEER)",
                    "!  YOUR BOSS. DEMANDS THE TEST CONTINUES NO",
                    "!  MATTER WHAT. HIS ORDERS MAY BE DANGEROUS."
                };
                break;
            case 4:
                title = "THE CONTROL PANEL";
                lines = new String[]{
                    "PRESS E AT THE CONTROL PANEL TO OPEN IT.",
                    "",
                    ">CONTROL RODS - INSERT (SAFER) / WITHDRAW (MORE POWER)",
                    "!  BELOW 30 RODS = EXTREMELY DANGEROUS!",
                    ">COOLANT FLOW - KEEPS THE CORE COOL",
                    ">PUMPS ON/OFF - CIRCULATES COOLANT WATER",
                    "!  TURNING OFF PUMPS CAUSES RAPID OVERHEATING!",
                    ">TURBINE - CONNECT/DISCONNECT FOR THE TEST",
                    ">ECCS - EMERGENCY CORE COOLING SYSTEM",
                    "",
                    "*LEFT-CLICK = DECREASE/OFF  RIGHT-CLICK = INCREASE/ON",
                    "*OR USE ARROW KEYS TO ADJUST THE SELECTED CONTROL.",
                    "+AZ-5 EMERGENCY BUTTON APPEARS WHEN THE STORY DEMANDS."
                };
                break;
            case 5:
                title = "ELENA CORE SCANNER";
                lines = new String[]{
                    "PRESS E AT THE ELENA DISPLAY TO OPEN IT.",
                    "ELENA MAPS NEUTRON FLUX ACROSS THE CORE.",
                    "",
                    ">CLICK SECTORS ON THE CORE MAP TO SCAN THEM.",
                    "+EACH SCAN IMPROVES STABILITY BY +0.5%.",
                    "",
                    "*ORANGE SECTORS = XENON HOT SPOTS (HIGH POISON)",
                    "*  FINDING 3+ GIVES YOU AN EARLY WARNING!",
                    "*PURPLE SECTORS = VOID ANOMALIES (DANGEROUS)",
                    "*  APPEARS AFTER PHASE 5 - WARNS OF VOID DANGER.",
                    "",
                    "!IF YOU NEGLECT ELENA, STABILITY DROPS FASTER!",
                    "+SCAN REGULARLY TO MAINTAIN YOUR BONUS."
                };
                break;
            case 6:
                title = "INDICATOR PANELS";
                lines = new String[]{
                    "PRESS E AT THE INDICATOR PANELS (RIGHT WALL).",
                    "THESE SHOW INDEPENDENT INSTRUMENT READINGS.",
                    "",
                    "!INSTRUMENTS DRIFT OVER TIME - THE READINGS",
                    "!SLOWLY BECOME INACCURATE AND UNRELIABLE!",
                    "",
                    ">LEFT-CLICK A GAUGE = CALIBRATE (FIXES DRIFT)",
                    ">RIGHT-CLICK = ACKNOWLEDGE ACTIVE ALARMS",
                    "+EACH CALIBRATION AND ALARM ACK BOOSTS STABILITY.",
                    "",
                    "*WATCH THE TREND ARROWS TO PREDICT PROBLEMS.",
                    "*RISING FAST = DANGER AHEAD!",
                    "!IGNORING INDICATORS FOR 60+ SEC = PENALTY!"
                };
                break;
            case 7:
                title = "STABILITY & DANGER";
                lines = new String[]{
                    "*STABILITY IS YOUR LIFELINE. IT STARTS AT 100%.",
                    "",
                    "!STABILITY DROPS WHEN:",
                    "!  - CONTROL RODS BELOW 30 (MASSIVE PENALTY)",
                    "!  - XENON LEVEL ABOVE 50%",
                    "!  - COOLANT FLOW BELOW 50%",
                    "!  - YOU NEGLECT ELENA OR INDICATOR PANELS",
                    "",
                    "+STABILITY INCREASES WHEN:",
                    "+  - YOU SCAN SECTORS IN ELENA",
                    "+  - YOU CALIBRATE INDICATOR GAUGES",
                    "+  - YOU ACKNOWLEDGE ALARMS",
                    "",
                    "*WHEN AZ-5 IS PRESSED, STABILITY > 15% = YOU WIN!",
                    "!STABILITY TOO LOW + AZ-5 = GRAPHITE TIP SURGE!"
                };
                break;
            default:
                title = "YOUR MISSION BEGINS";
                lines = new String[]{
                    "THE STORY UNFOLDS IN PHASES:",
                    "",
                    ">PHASE 1: REDUCE POWER (INSERT CONTROL RODS)",
                    ">PHASE 2: VERIFY INDICATOR PANELS",
                    ">PHASE 3: SCAN ELENA FOR XENON BUILDUP",
                    ">PHASE 4: DEAL WITH XENON POISONING",
                    ">PHASE 5: RAISE POWER (DYATLOV'S ORDER)",
                    ">PHASE 6: DISCONNECT TURBINE FOR THE TEST",
                    ">PHASE 7: PRESS AZ-5 WHEN THE TIME COMES",
                    "",
                    "+TALK TO NPCS FOR HINTS. CHECK YOUR EQUIPMENT.",
                    "*THE FATE OF CHERNOBYL IS IN YOUR HANDS.",
                    "",
                    "!CLICK START GAME TO BEGIN..."
                };
                break;
        }

        // Render title
        drawHUDText(title, contentX, contentTopY, 3, 1f, 0.6f, 0.15f, 1f);

        // Render lines with color coding based on prefix
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            float lr = 0.8f, lg = 0.8f, lb = 0.75f, la = 1f; // default gray
            if (line.startsWith("!")) {
                // Red/warning
                line = line.substring(1);
                lr = 1f; lg = 0.35f; lb = 0.3f;
            } else if (line.startsWith("+")) {
                // Green/positive tip
                line = line.substring(1);
                lr = 0.3f; lg = 0.9f; lb = 0.4f;
            } else if (line.startsWith("*")) {
                // Orange/important
                line = line.substring(1);
                lr = 1f; lg = 0.7f; lb = 0.2f;
            } else if (line.startsWith(">")) {
                // Cyan/key binding
                line = line.substring(1);
                lr = 0.4f; lg = 0.8f; lb = 1f;
            }
            drawHUDText(line, contentX, contentTopY - 40 - i * 26, 2, lr, lg, lb, la);
        }

        // === NAVIGATION BUTTONS ===
        float nbW = 200, nbH = 55;

        // BACK button
        float backX = cardX + 25;
        float backY = cardY + 18;
        String backLabel = tutorialStep > 0 ? "BACK" : "MENU";
        drawHUDRect(backX, backY, nbW, nbH, 0.4f, 0.2f, 0.08f, 0.9f);
        drawHUDRect(backX, backY + nbH - 2, nbW, 2, 0.6f, 0.35f, 0.1f, 1f);
        float backTW = backLabel.length() * 10;
        drawHUDText(backLabel, backX + (nbW - backTW) / 2, backY + 20, 2, 1f, 0.8f, 0.7f, 1f);

        // NEXT button
        float nextX = cardX + cardW - nbW - 25;
        float nextY = cardY + 18;
        String nextLabel = tutorialStep < TUTORIAL_TOTAL - 1 ? "NEXT" : "START GAME";
        float nBtnW = tutorialStep < TUTORIAL_TOTAL - 1 ? nbW : nbW + 80;
        float nBtnX = cardX + cardW - nBtnW - 25;
        boolean isLast = tutorialStep >= TUTORIAL_TOTAL - 1;
        drawHUDRect(nBtnX, nextY, nBtnW, nbH,
            isLast ? 0.7f : 0.5f, isLast ? 0.35f : 0.28f, isLast ? 0.08f : 0.08f, 0.9f);
        drawHUDRect(nBtnX, nextY + nbH - 2, nBtnW, 2,
            isLast ? 0.9f : 0.65f, isLast ? 0.5f : 0.38f, isLast ? 0.15f : 0.12f, 1f);
        float nextTW = nextLabel.length() * 10;
        drawHUDText(nextLabel, nBtnX + (nBtnW - nextTW) / 2, nextY + 20, 2, 1f, 1f, 1f, 1f);

        // ESC hint
        drawHUDText("ESC - BACK TO MENU", cardX + 20, cardY - 25, 2, 0.5f, 0.35f, 0.15f, 0.7f);

        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
    }

    private void startStory() {
        storyPhase = 0;
        storyTime = "25 APRIL 1986 - 23:10";
        currentObjective = "APPROACH AKIMOV FOR BRIEFING";
        showNotification("CHERNOBYL NUCLEAR POWER PLANT - UNIT 4 CONTROL ROOM");
        storyIntroShown = true;
        updateReactorState(); // Set initial reactor values
    }

    private void showNotification(String text) {
        notificationText = text;
        notificationTimer = NOTIFICATION_DURATION;
    }

    private void advanceStory() {
        phaseTimer = 0; // Reset timer on phase change
        switch (storyPhase) {
            case 1:
                storyTime = "25 APRIL 1986 - 23:15";
                currentObjective = "CLICK CONTROL PANEL - INSERT RODS (RIGHT-CLICK) TO LOWER POWER TO 700";
                showNotification("GO TO CONTROL PANEL (CLICK) - RIGHT-CLICK OR RIGHT ARROW TO INSERT RODS");
                break;
            case 2:
                storyTime = "25 APRIL 1986 - 23:40";
                currentObjective = "OPEN INDICATOR PANELS (RIGHT WALL) - CALIBRATE GAUGES AND CHECK TRENDS";
                showNotification("POWER REDUCED! WALK TO RIGHT WALL INDICATORS - PRESS E TO OPEN!");
                break;
            case 3:
                storyTime = "26 APRIL 1986 - 00:05";
                currentObjective = "OPEN ELENA DISPLAY (CLICK) - SCAN 3+ SECTORS TO CHECK XENON LEVELS";
                showNotification("XENON DETECTED! GO TO ELENA DISPLAY (BACK WALL) - X TO OPEN - CLICK TO SCAN!");
                elenaScanCount = 0; // Reset scan count for this phase
                break;
            case 4:
                storyTime = "26 APRIL 1986 - 00:28";
                currentObjective = "WARNING! TALK TO AKIMOV (PRESS T) ABOUT THE XENON CRISIS!";
                showNotification("XENON CRISIS CONFIRMED ON ELENA! FIND AKIMOV AND PRESS T!");
                break;
            case 5:
                storyTime = "26 APRIL 1986 - 00:43";
                currentObjective = "OPEN PANEL (E) - LEFT-CLICK RODS TO WITHDRAW - RAISE POWER ABOVE 400";
                showNotification("DYATLOV ORDERS: WITHDRAW RODS (LEFT-CLICK/LEFT ARROW) TO RAISE POWER!");
                break;
            case 6:
                storyTime = "26 APRIL 1986 - 01:00";
                currentObjective = "SCAN ELENA (5+ SECTORS) THEN DISCONNECT TURBINE AT CONTROL PANEL";
                showNotification("GO TO ELENA - SCAN 5+ SECTORS TO CONFIRM - THEN DISCONNECT TURBINE!");
                elenaScanCount = 0; // Reset scan count for this phase
                break;
            case 7:
                storyTime = "26 APRIL 1986 - 01:23";
                currentObjective = "RIGHT-CLICK RODS TO INSERT (30+) THEN PRESS SPACE FOR AZ-5!";
                showNotification("DANGER! INSERT RODS (30+) THEN PRESS SPACE - AZ-5 MAY NOT SAVE YOU!");
                break;
            case 8:
                storyTime = "26 APRIL 1986 - 01:23:40";
                currentObjective = "THE AZ-5 BUTTON HAS BEEN PRESSED...";
                showNotification("AZ-5 EMERGENCY SHUTDOWN ACTIVATED");
                break;
            case 9:
                storyTime = "26 APRIL 1986 - 01:23:45";
                currentObjective = "";
                showNotification("THE REACTOR EXPLODES.");
                break;
        }
    }

    private String[] getDialogueForNPC(String npcName) {
        if (npcName.equals("Laptev")) {
            return getLockerCrewDialogue();
        } else if (npcName.equals("Kudryavtsev")) {
            return getPumpTechDialogue();
        }
        switch (storyPhase) {
            case 0:
                if (npcName.equals("Akimov")) {
                    storyPhase = 1;
                    advanceStory();
                    return new String[]{
                        "Ah, you must be the new engineer assigned",
                        "to observe tonight's safety test.",
                        "",
                        "I am Alexander Akimov, shift supervisor.",
                        "Tonight we conduct a turbine rundown test",
                        "on Reactor Number Four.",
                        "",
                        "Deputy Chief Engineer Dyatlov has ordered",
                        "this test. We must determine how long the",
                        "turbines can power the coolant pumps during",
                        "a station blackout.",
                        "",
                        "Go speak with Toptunov at the reactor",
                        "console. He will brief you on the reactor",
                        "status. He is near the ELENA display."
                    };
                } else if (npcName.equals("Dyatlov")) {
                    return new String[]{
                        "What are you standing around for?",
                        "This test will be completed tonight.",
                        "Go speak with Akimov. He will brief",
                        "you. Do not waste my time."
                    };
                } else if (npcName.equals("Perevozchenko")) {
                    return new String[]{
                        "Comrade, I am Valery Perevozchenko,",
                        "reactor section foreman.",
                        "",
                        "I oversee the reactor hall - the room",
                        "with the fuel channels. If you need",
                        "anything about the reactor itself,",
                        "come find me in hall 2.",
                        "",
                        "But first, speak with Akimov.",
                        "He is expecting you."
                    };
                } else {
                    return new String[]{
                        "Not now, comrade. Akimov wants to",
                        "see you first. He is by the indicator",
                        "panels."
                    };
                }
            case 1:
                if (npcName.equals("Toptunov")) {
                    // Don't auto-advance - player must use control panel to reduce power
                    return new String[]{
                        "Hello, comrade. I am Leonid Toptunov,",
                        "senior reactor control engineer.",
                        "",
                        "The reactor is running at 1600 MW but",
                        "climbing! We need it down to 700.",
                        "",
                        "Walk to the CONTROL PANEL and click it.",
                        "Then RIGHT-CLICK (or RIGHT arrow) on",
                        "CONTROL RODS to INSERT them.",
                        "",
                        "More rods inserted = less power.",
                        "Be careful not to go too low!"
                    };
                } else if (npcName.equals("Dyatlov")) {
                    return new String[]{
                        "Why are you standing around? Go to",
                        "the control panel and begin the",
                        "power reduction! Press E near it.",
                        "We do not have all night."
                    };
                } else {
                    return new String[]{
                        "The control panel is ahead of you.",
                        "Press E when near it to interact.",
                        "We need to lower the reactor power."
                    };
                }
            case 2:
                // Indicator verification phase
                if (npcName.equals("Toptunov")) {
                    return new String[]{
                        "Good, the power is coming down.",
                        "",
                        "Go check the INDICATOR PANELS on",
                        "the RIGHT WALL. Press E to open.",
                        "",
                        "The instruments drift over time -",
                        "LEFT-CLICK each gauge to calibrate.",
                        "RIGHT-CLICK to acknowledge alarms.",
                        "",
                        "Watch the TREND ARROWS to see if",
                        "values are rising or falling. If",
                        "you ignore indicators too long,",
                        "stability drops!"
                    };
                } else if (npcName.equals("Akimov")) {
                    return new String[]{
                        "Check the indicator panels on the",
                        "right wall. Press E to open them.",
                        "Calibrate the gauges by clicking",
                        "and acknowledge any alarms.",
                        "Keep checking them regularly!"
                    };
                } else {
                    return new String[]{
                        "Akimov wants you to verify the",
                        "indicator readings. The panels",
                        "are on the right wall. Press E."
                    };
                }
            case 3:
                // NEW: ELENA scan phase
                if (npcName.equals("Toptunov")) {
                    return new String[]{
                        "Good, the indicators check out.",
                        "",
                        "Now we need to check the ELENA",
                        "display. Walk to it and press E.",
                        "",
                        "ELENA shows the neutron flux in",
                        "each sector of the core. Scan at",
                        "least 3 sectors by clicking on them.",
                        "",
                        "Look for XENON HOT SPOTS - they",
                        "glow ORANGE on the display. Each",
                        "scan you do improves stability.",
                        "",
                        "Do NOT skip this! If you neglect",
                        "ELENA the reactor gets worse faster."
                    };
                } else if (npcName.equals("Akimov")) {
                    return new String[]{
                        "Go to the ELENA display and scan",
                        "the core sectors. We need to know",
                        "the xenon distribution before the",
                        "test can continue.",
                        "",
                        "Scan thoroughly - the more sectors",
                        "you check the better our stability."
                    };
                } else {
                    return new String[]{
                        "ELENA is the large display near",
                        "the back wall. Press E to open it",
                        "and click sectors to scan them.",
                        "",
                        "Watch for orange hot spots - those",
                        "are xenon-135 danger zones."
                    };
                }
            case 4:
                if (npcName.equals("Akimov")) {
                    // Talking to Akimov triggers the xenon crisis and advances to phase 5
                    storyPhase = 5;
                    advanceStory();
                    xenonLevel = 80f;
                    reactorPower = 30f;
                    showMachineLog("XENON-135 BUILDUP CRITICAL!");
                    return new String[]{
                        "Comrade, we have a serious problem!",
                        "",
                        "Xenon-135 has poisoned the reactor!",
                        "Power has collapsed to almost zero.",
                        "",
                        "Dyatlov demands we raise power NOW.",
                        "Go to the CONTROL PANEL (press E).",
                        "LEFT-CLICK rods to WITHDRAW them.",
                        "",
                        "We need power back ABOVE 400 MW!",
                        "WARNING: Keep at least 30 rods in",
                        "or the reactor becomes unstable!"
                    };
                } else if (npcName.equals("Dyatlov")) {
                    return new String[]{
                        "The power dropped? Incompetence!",
                        "Toptunov cannot handle a simple",
                        "power reduction!",
                        "",
                        "The xenon will kill the reactor if",
                        "you do not act. Wait for my orders."
                    };
                } else {
                    return new String[]{
                        "I... I made a mistake. The power",
                        "dropped too low. The xenon is building.",
                        "We must wait and see what happens."
                    };
                }
            case 5:
                if (npcName.equals("Toptunov")) {
                    // Don't auto-advance - player must withdraw rods in control panel
                    return new String[]{
                        "The situation is critical, comrade!",
                        "",
                        "Xenon-135 has poisoned the reactor.",
                        "Dyatlov demands we raise the power.",
                        "",
                        "Open the CONTROL PANEL (press E) and",
                        "LEFT-CLICK rods to WITHDRAW them.",
                        "We need power above 400 MW!",
                        "",
                        "WARNING: Regulations say minimum 30",
                        "rods must stay inserted at all times.",
                        "Below that the reactor is UNSTABLE!",
                        "",
                        "Do what you must. God help us."
                    };
                } else if (npcName.equals("Dyatlov")) {
                    return new String[]{
                        "Pull out the rods! All of them if",
                        "you have to! I need that power up!",
                        "",
                        "Open the control panel and withdraw",
                        "those rods NOW. The test proceeds."
                    };
                } else {
                    return new String[]{
                        "I know about the control rods.",
                        "We are operating outside all safe",
                        "parameters. But Dyatlov insists.",
                        "Talk to Toptunov for the details."
                    };
                }
            case 6:
                if (npcName.equals("Akimov")) {
                    // Don't auto-advance - player must scan ELENA + disconnect turbine
                    return new String[]{
                        "Only " + controlRodsInserted + " control rods remain...",
                        "",
                        "Before the turbine test, scan ELENA",
                        "one more time - we need 5+ sectors",
                        "scanned to confirm core stability.",
                        "",
                        "Look for PURPLE sectors - those show",
                        "VOID COEFFICIENT anomalies. If you",
                        "see them AZ-5 could cause a surge!",
                        "",
                        "Then open the CONTROL PANEL (press E).",
                        "Scroll DOWN to TURBINE then LEFT-CLICK",
                        "to DISCONNECT it.",
                        "",
                        "If anything goes wrong after the",
                        "test... we use the AZ-5 button.",
                        "God help us all."
                    };
                } else {
                    return new String[]{
                        "Scan ELENA display first (5 sectors),",
                        "then disconnect the turbine at the",
                        "control panel. Click nearby."
                    };
                }
            case 7:
                if (npcName.equals("Akimov")) {
                    // Don't auto-advance - player must press AZ-5 in control panel
                    return new String[]{
                        "The turbine is spinning down but...",
                        "the power... it is RISING!",
                        "",
                        "You must press AZ-5! NOW!",
                        "Open the CONTROL PANEL and press",
                        "SPACE to activate emergency shutdown!",
                        "",
                        "Make sure we have ENOUGH RODS",
                        "INSERTED (30+) before pressing AZ-5",
                        "or the graphite tips could cause",
                        "a POWER SURGE! INSERT RODS FIRST!",
                        "",
                        "HURRY!"
                    };
                } else if (npcName.equals("Dyatlov")) {
                    return new String[]{
                        "Stop panicking! Everything is under",
                        "control. We begin the test now.",
                        "Any more delays and I will have you",
                        "all replaced. Understood?"
                    };
                } else if (npcName.equals("Perevozchenko")) {
                    return new String[]{
                        "COMRADE! You have to listen to me!",
                        "",
                        "I was just in the reactor hall -",
                        "the fuel channel CAPS! They were",
                        "JUMPING! Bouncing on and off like",
                        "something is pushing them from below!",
                        "",
                        "I have NEVER seen anything like it!",
                        "The reactor is doing something it",
                        "should not be doing!",
                        "",
                        "For God's sake, press AZ-5! SHUT",
                        "IT DOWN BEFORE IT IS TOO LATE!"
                    };
                } else {
                    return new String[]{
                        "Something is wrong! The readings",
                        "are going crazy! Talk to Akimov!",
                        "NOW!"
                    };
                }
            case 8:
                if (npcName.equals("Akimov")) {
                    storyPhase = 9;
                    advanceStory();
                    playerWon = false;
                    endingTimer = 8f;
                    endingCountdownActive = true;
                    return new String[]{
                        "EXPLOSION!",
                        "",
                        "The reactor... it is destroyed.",
                        "The building... the roof is gone.",
                        "",
                        "This is not possible. An RBMK",
                        "reactor cannot explode. It cannot.",
                        "",
                        "I did everything right. I pressed",
                        "AZ-5. I followed the procedures...",
                        "",
                        "What have we done?"
                    };
                } else if (npcName.equals("Dyatlov")) {
                    return new String[]{
                        "An RBMK reactor cannot explode.",
                        "You are delusional. The reactor",
                        "core is intact. It must be the",
                        "water tanks that exploded.",
                        "",
                        "Go to the roof and assess the",
                        "damage. That is an order."
                    };
                } else if (npcName.equals("Perevozchenko")) {
                    return new String[]{
                        "The reactor hall... it is GONE.",
                        "The upper biological shield -",
                        "Elena - it flipped over completely.",
                        "",
                        "I saw the fuel channels flying",
                        "through the air like matchsticks.",
                        "The graphite... it is everywhere.",
                        "",
                        "Khodemchuk... Khodemchuk was in",
                        "the pump room. I cannot find him.",
                        "I have to go back. I have to try.",
                        "",
                        "The radiation... I can taste metal",
                        "in my mouth. My skin is burning."
                    };
                } else {
                    return new String[]{
                        "What happened? What was that sound?",
                        "The reactor building... look at it!",
                        "This is not possible..."
                    };
                }
            default:
                return new String[]{"..."};
        }
    }

    private String[] getLockerCrewDialogue() {
        switch (storyPhase) {
            case 0:
            case 1:
            case 2:
                return new String[]{
                    "Evening, comrade. Locker room is east wing.",
                    "Take the corridor past the panels to reach us."
                };
            case 3:
            case 4:
                return new String[]{
                    "Radiation badges read clean so far.",
                    "Have you heard anything about the test?"
                };
            case 5:
                return new String[]{
                    "Everyone's whispering about xenon poisoning.",
                    "If we need to muster, this room is ready."
                };
            case 6:
                return new String[]{
                    "We staged medical kits in the lockers.",
                    "If alarms sound, grab one on your way past."
                };
            case 7:
                return new String[]{
                    "Sirens from the control room carry this far.",
                    "Tell us if we need to don respirators."
                };
            case 8:
            case 9:
                return new String[]{
                    "Radiation spikes hit our dosimeters!",
                    "The corridor air tastes metallic—what happened?"
                };
            default:
                return new String[]{"Stay sharp, comrade."};
        }
    }

    private String[] getPumpTechDialogue() {
        switch (storyPhase) {
            case 0:
            case 1:
                return new String[]{
                    "Pump Annex humming fine.",
                    "We're balancing flow for the test later."
                };
            case 2:
            case 3:
                return new String[]{
                    "Coolant circulation is steady.",
                    "We could push harder if Dyatlov insists."
                };
            case 4:
            case 5:
                return new String[]{
                    "Xenon rumors reached us.",
                    "If flow surges, I'll shut valves manually."
                };
            case 6:
                return new String[]{
                    "Turbine disconnect order came through.",
                    "If power drops, our pumps will starve."
                };
            case 7:
                return new String[]{
                    "We hear banging in the conduits.",
                    "Are you sure AZ-5 is still optional?"
                };
            case 8:
            case 9:
                return new String[]{
                    "Pressure gauges just died.",
                    "Whatever blew upstairs shook the annex."};
            default:
                return new String[]{"Flow lines are your lifeline."};
        }
    }

    private void updateNPCStoryBehavior() {
        for (NPCEngineer npc : npcEngineers) {
            // Only update if story state changed for this NPC
            if (npc.npcStoryState == storyPhase) continue;
            npc.npcStoryState = storyPhase;

            if (npc.name.equals("Akimov")) {
                switch (storyPhase) {
                    case 0: // Standing by indicators, waiting
                        npc.targetPosition.set(11 * BLOCK_SIZE, 0, 0);
                        npc.calloutText = "OVER HERE, COMRADE!";
                        npc.calloutTimer = 8f;
                        break;
                    case 1: // Player talked to Akimov, sent to Toptunov
                        npc.targetPosition.set(10 * BLOCK_SIZE, 0, -2 * BLOCK_SIZE);
                        npc.calloutText = "";
                        break;
                    case 2: // Verify indicator panels
                        npc.targetPosition.set(12 * BLOCK_SIZE, 0, 0);
                        npc.calloutText = "CHECK THE INDICATORS!";
                        npc.calloutTimer = 6f;
                        break;
                    case 3: // ELENA scan phase
                        npc.targetPosition.set(8 * BLOCK_SIZE, 0, -1 * BLOCK_SIZE);
                        npc.calloutText = "";
                        break;
                    case 4: // Akimov worried about xenon crisis
                        npc.targetPosition.set(5 * BLOCK_SIZE, 0, -2 * BLOCK_SIZE);
                        npc.calloutText = "COME HERE, QUICKLY!";
                        npc.calloutTimer = 6f;
                        break;
                    case 5: // Akimov pacing near indicators
                        npc.targetPosition.set(9 * BLOCK_SIZE, 0, -4 * BLOCK_SIZE);
                        npc.calloutText = "";
                        break;
                    case 6: // Akimov near AZ-5 area, nervous
                        npc.targetPosition.set(3 * BLOCK_SIZE, 0, -5 * BLOCK_SIZE);
                        npc.calloutText = "COME SEE THIS...";
                        npc.calloutTimer = 6f;
                        break;
                    case 7: // Test about to begin, Akimov at control panel
                        npc.targetPosition.set(0, 0, -5 * BLOCK_SIZE);
                        npc.calloutText = "THE TEST BEGINS NOW!";
                        npc.calloutTimer = 5f;
                        break;
                    case 8: // AZ-5 pressed, Akimov panicking
                        npc.targetPosition.set(1 * BLOCK_SIZE, 0, -4 * BLOCK_SIZE);
                        npc.calloutText = "WHAT IS HAPPENING?!";
                        npc.calloutTimer = 4f;
                        npc.moveSpeed = 180f;
                        break;
                    case 9: // Explosion
                        npc.targetPosition.set(3 * BLOCK_SIZE, 0, -2 * BLOCK_SIZE);
                        npc.calloutText = "NO... NO!";
                        npc.calloutTimer = 10f;
                        npc.moveSpeed = 200f;
                        break;
                }
            } else if (npc.name.equals("Toptunov")) {
                switch (storyPhase) {
                    case 0: // Standing beside table near ELENA
                        npc.targetPosition.set(-3.5f * BLOCK_SIZE, 0, 5 * BLOCK_SIZE);
                        npc.calloutText = "";
                        break;
                    case 1: // Waiting for player at ELENA
                        npc.targetPosition.set(-3.5f * BLOCK_SIZE, 0, 7 * BLOCK_SIZE);
                        npc.calloutText = "COME, LOOK AT THE READINGS!";
                        npc.calloutTimer = 6f;
                        break;
                    case 2: // Indicator verification - Toptunov near right wall
                        npc.targetPosition.set(13 * BLOCK_SIZE, 0, 1 * BLOCK_SIZE);
                        npc.calloutText = "CHECK THE RIGHT WALL PANELS!";
                        npc.calloutTimer = 6f;
                        break;
                    case 3: // ELENA scan phase - Toptunov near ELENA
                        npc.targetPosition.set(-3.5f * BLOCK_SIZE, 0, 6 * BLOCK_SIZE);
                        npc.calloutText = "USE THE ELENA DISPLAY!";
                        npc.calloutTimer = 6f;
                        break;
                    case 4: // Xenon crisis - Moving along table edge, worried
                        npc.targetPosition.set(-3.5f * BLOCK_SIZE, 0, 3 * BLOCK_SIZE);
                        npc.calloutText = "";
                        break;
                    case 5: // Frantically at ELENA end
                        npc.targetPosition.set(-3.5f * BLOCK_SIZE, 0, 7 * BLOCK_SIZE);
                        npc.calloutText = "I CANNOT RAISE THE POWER!";
                        npc.calloutTimer = 6f;
                        npc.moveSpeed = 150f;
                        break;
                    case 6: // Runs to desk area
                        npc.targetPosition.set(-4 * BLOCK_SIZE, 0, 0);
                        npc.calloutText = "";
                        break;
                    case 7: // Test starting, near ELENA
                        npc.targetPosition.set(-3.5f * BLOCK_SIZE, 0, 6 * BLOCK_SIZE);
                        npc.calloutText = "THIS IS MADNESS...";
                        npc.calloutTimer = 5f;
                        break;
                    case 8: // Surge, backs away
                        npc.targetPosition.set(-5 * BLOCK_SIZE, 0, 3 * BLOCK_SIZE);
                        npc.calloutText = "THE READINGS ARE IMPOSSIBLE!";
                        npc.calloutTimer = 4f;
                        npc.moveSpeed = 200f;
                        break;
                    case 9: // Explosion
                        npc.targetPosition.set(4 * BLOCK_SIZE, 0, 1 * BLOCK_SIZE);
                        npc.calloutText = "WE HAVE TO GET OUT!";
                        npc.calloutTimer = 10f;
                        npc.moveSpeed = 220f;
                        break;
                }
            } else if (npc.name.equals("Dyatlov")) {
                switch (storyPhase) {
                    case 0: // Standing behind control desk, arms crossed, supervising
                        npc.targetPosition.set(2 * BLOCK_SIZE, 0, -7.5f * BLOCK_SIZE);
                        npc.calloutText = "";
                        break;
                    case 1: // Pacing behind the desk
                        npc.targetPosition.set(-2 * BLOCK_SIZE, 0, -7.5f * BLOCK_SIZE);
                        npc.calloutText = "";
                        break;
                    case 2: // Indicator verification - Dyatlov impatient
                        npc.targetPosition.set(1 * BLOCK_SIZE, 0, -7.5f * BLOCK_SIZE);
                        npc.calloutText = "HURRY UP!";
                        npc.calloutTimer = 5f;
                        break;
                    case 3: // ELENA scan - Dyatlov pacing
                        npc.targetPosition.set(-1 * BLOCK_SIZE, 0, -7.5f * BLOCK_SIZE);
                        npc.calloutText = "STOP WASTING TIME!";
                        npc.calloutTimer = 5f;
                        break;
                    case 4: // Angry about power drop, moves closer to desk
                        npc.targetPosition.set(0, 0, -7 * BLOCK_SIZE);
                        npc.calloutText = "RAISE THE POWER! NOW!";
                        npc.calloutTimer = 6f;
                        break;
                    case 5: // Furious, threatening
                        npc.targetPosition.set(3 * BLOCK_SIZE, 0, -7 * BLOCK_SIZE);
                        npc.calloutText = "THE TEST WILL PROCEED!";
                        npc.calloutTimer = 6f;
                        break;
                    case 6: // Demanding, impatient
                        npc.targetPosition.set(-1 * BLOCK_SIZE, 0, -7 * BLOCK_SIZE);
                        npc.calloutText = "DO NOT QUESTION ME!";
                        npc.calloutTimer = 5f;
                        break;
                    case 7: // Orders the test
                        npc.targetPosition.set(0, 0, -7 * BLOCK_SIZE);
                        npc.calloutText = "BEGIN THE TEST!";
                        npc.calloutTimer = 5f;
                        break;
                    case 8: // Confused, in denial
                        npc.targetPosition.set(2 * BLOCK_SIZE, 0, -6 * BLOCK_SIZE);
                        npc.calloutText = "WHAT HAVE YOU DONE?!";
                        npc.calloutTimer = 4f;
                        npc.moveSpeed = 150f;
                        break;
                    case 9: // Explosion — denial
                        npc.targetPosition.set(0, 0, -4 * BLOCK_SIZE);
                        npc.calloutText = "THE REACTOR IS FINE!";
                        npc.calloutTimer = 10f;
                        npc.moveSpeed = 160f;
                        break;
                }
            }
        }
    }

    private void updateStory(long window, float deltaTime) {
        if (notificationTimer > 0) {
            notificationTimer -= deltaTime;
        }

        // Machine interaction system
        updateMachineInteraction(window);

        // Don't process NPC interactions if machine UI is open
        if (machineUIActive) return;

        showInteractPrompt = false;
        nearbyNPCName = "";
        for (NPCEngineer npc : npcEngineers) {
            float dx = cameraPos.x - npc.basePosition.x;
            float dz = cameraPos.z - npc.basePosition.z;
            float dist = (float) Math.sqrt(dx * dx + dz * dz);
            if (dist < NPC_INTERACT_DISTANCE) {
                showInteractPrompt = true;
                nearbyNPCName = npc.name;
                break;
            }
        }

        if (glfwGetKey(window, GLFW_KEY_T) == GLFW_PRESS) {
            if (!tKeyPressed) {
                tKeyPressed = true;
                if (!dialogueActive && showInteractPrompt && !nearbyNPCName.isEmpty()) {
                    dialogueLines = getDialogueForNPC(nearbyNPCName);
                    dialogueSpeaker = nearbyNPCName.toUpperCase();
                    dialoguePage = 0;
                    dialogueActive = true;
                } else if (dialogueActive) {
                    advanceDialoguePage();
                }
            }
        } else {
            tKeyPressed = false;
        }

        if (glfwGetKey(window, GLFW_KEY_ENTER) == GLFW_PRESS) {
            if (!enterKeyPressed) {
                enterKeyPressed = true;
                if (dialogueActive) {
                    advanceDialoguePage();
                }
            }
        } else {
            enterKeyPressed = false;
        }
    }

    private void advanceDialoguePage() {
        int linesPerPage = 6;
        int totalPages = (dialogueLines.length + linesPerPage - 1) / linesPerPage;
        dialoguePage++;
        if (dialoguePage >= totalPages) {
            dialogueActive = false;
            dialoguePage = 0;
        }
    }

    private void renderHUD() {
        glDisable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_CULL_FACE);

        glUseProgram(hudShaderProgram);

        int[] wBuf = new int[1], hBuf = new int[1];
        glfwGetFramebufferSize(glfwGetCurrentContext(), wBuf, hBuf);
        float screenW = wBuf[0] > 0 ? wBuf[0] : ChernobylGame.WIDTH;
        float screenH = hBuf[0] > 0 ? hBuf[0] : ChernobylGame.HEIGHT;

        Matrix4f hudProj = new Matrix4f().ortho(0, screenW, 0, screenH, -1, 1);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer fb = stack.mallocFloat(16);
            glUniformMatrix4fv(hudProjLoc, false, hudProj.get(fb));
        }
        glUniform1i(hudTexLoc, 0);

        // Machine UI takes over the full screen
        if (machineUIActive) {
            if (activeMachine.equals("CONTROL_PANEL")) {
                renderControlPanelUI(screenW, screenH);
            } else if (activeMachine.equals("ELENA")) {
                renderElenaDisplayUI(screenW, screenH);
            } else if (activeMachine.equals("INDICATOR_PANEL")) {
                renderIndicatorPanelUI(screenW, screenH);
            } else if (activeMachine.equals("PUMP_CONSOLE")) {
                renderPumpConsoleUI(screenW, screenH);
            }
            // Restore state
            glEnable(GL_DEPTH_TEST);
            glEnable(GL_CULL_FACE);
            return;
        }

        // Top left: Time display
        drawHUDRect(10, screenH - 50, 420, 45, 0f, 0f, 0f, 0.65f);
        drawHUDText(storyTime, 20, screenH - 38, 2, 1.0f, 0.7f, 0.2f, 1.0f);

        if (showDebugGPSOverlay) {
            float blockX = cameraPos.x / BLOCK_SIZE;
            float blockY = cameraPos.y / BLOCK_SIZE;
            float blockZ = cameraPos.z / BLOCK_SIZE;
            String gpsText = String.format("GPS  X:%6.2f  Y:%5.2f  Z:%6.2f (blocks)", blockX, blockY, blockZ);
            String zoneText = "Zone: " + describePlayerLocation(cameraPos.x, cameraPos.z);
            float maxChars = Math.max(gpsText.length(), zoneText.length());
            float gpsW = Math.max(320f, maxChars * 8f + 30f);
            float gpsX = 10f;
            float gpsY = screenH - 110f;
            drawHUDRect(gpsX, gpsY, gpsW, 55f, 0f, 0f, 0f, 0.55f);
            drawHUDText(gpsText, gpsX + 14f, gpsY + 34f, 2, 0.6f, 0.9f, 1f, 1f);
            drawHUDText(zoneText, gpsX + 14f, gpsY + 12f, 2, 0.9f, 0.75f, 0.3f, 0.95f);
        }

        // Top center: Current objective
        if (!currentObjective.isEmpty()) {
            float objW = currentObjective.length() * 10 + 30;
            float objX = (screenW - objW) / 2;
            drawHUDRect(objX, screenH - 85, objW, 35, 0f, 0f, 0f, 0.55f);
            drawHUDText(currentObjective, objX + 15, screenH - 72, 2, 1.0f, 0.7f, 0.2f, 1.0f);
        }

        // --- Geiger Counter & Radiation HUD ---
        float geigerX = 30, geigerY = 120;
        float geigerW = 320, geigerH = 60;
        // Geiger counter background
        drawHUDRect(geigerX, geigerY, geigerW, geigerH, 0.08f, 0.08f, 0.08f, 0.82f);
        // Geiger label
        drawHUDText("GEIGER COUNTER", geigerX + 12, geigerY + 38, 2, 0.7f, 1f, 0.7f, 1f);
        // Radiation value
        String radStr = String.format("%.2f R/h", radiationRate);
        drawHUDText(radStr, geigerX + 200, geigerY + 38, 2, 1f, 0.95f, 0.5f, 1f);
        // Geiger clicks (visual bar)
        int maxClicks = 20;
        int clicks = Math.min(maxClicks, geigerClickCount);
        for (int i = 0; i < clicks; i++) {
            float bx = geigerX + 18 + i * 13;
            float by = geigerY + 12;
            float bw = 9, bh = 18;
            float a = 0.7f + 0.3f * (float)Math.random();
            drawHUDRect(bx, by, bw, bh, 0.95f, 0.95f, 0.2f, a);
        }
        // Accumulated dose
        String doseStr = String.format("DOSE: %.1f R", playerRadiation);
        drawHUDText(doseStr, geigerX + 12, geigerY + 12, 2, 1f, 0.7f, 0.7f, 1f);
        // Flash effect for high radiation
        if (radiationFlashTimer > 0f) {
            float fa = Math.min(1f, radiationFlashTimer * 6f);
            drawHUDRect(0, 0, screenW, screenH, 1f, 1f, 0.7f, 0.18f * fa);
        }

        // Notification popup
        if (notificationTimer > 0) {
            float alpha = Math.min(1f, notificationTimer);
            float notifW = notificationText.length() * 10 + 40;
            float notifX = (screenW - notifW) / 2;
            float notifY = screenH * 0.65f;
            drawHUDRect(notifX, notifY, notifW, 40, 0.1f, 0.05f, 0f, 0.75f * alpha);
            drawHUDText(notificationText, notifX + 20, notifY + 13, 2, 1f, 0.7f, 0.2f, alpha);
        }

        // Interact prompt
        if (showInteractPrompt && !dialogueActive) {
            String prompt = "PRESS T TO TALK TO " + nearbyNPCName.toUpperCase();
            float promptW = prompt.length() * 10 + 30;
            float promptX = (screenW - promptW) / 2;
            drawHUDRect(promptX, 55, promptW, 35, 0f, 0f, 0f, 0.6f);
            drawHUDText(prompt, promptX + 15, 68, 2, 1f, 1f, 1f, 1f);
        }

        // Machine interaction prompt
        renderMachinePrompt(screenW, screenH);

        // Crosshair
        if (!dialogueActive) {
            float cx = screenW / 2, cy = screenH / 2;
            drawHUDRect(cx - 1, cy - 10, 2, 20, 1f, 1f, 1f, 0.5f);
            drawHUDRect(cx - 10, cy - 1, 20, 2, 1f, 1f, 1f, 0.5f);
        }

        // NPC callout bubbles (world-to-screen)
        for (NPCEngineer npc : npcEngineers) {
            if (npc.calloutTimer > 0 && npc.calloutText != null && !npc.calloutText.isEmpty()) {
                // Project NPC head position to screen
                float npcWX = npc.basePosition.x;
                float npcWY = npc.basePosition.y + 170f; // above head
                float npcWZ = npc.basePosition.z;

                // World-to-screen projection
                Vector4f worldPos = new Vector4f(npcWX, npcWY, npcWZ, 1f);
                Matrix4f vpMatrix = new Matrix4f(projection).mul(view);
                vpMatrix.transform(worldPos);

                if (worldPos.w > 0) {
                    float ndcX = worldPos.x / worldPos.w;
                    float ndcY = worldPos.y / worldPos.w;
                    float sx = (ndcX * 0.5f + 0.5f) * screenW;
                    float sy = (ndcY * 0.5f + 0.5f) * screenH;

                    // Only show if on screen
                    if (sx > 0 && sx < screenW && sy > 0 && sy < screenH) {
                        float alpha = Math.min(1f, npc.calloutTimer);
                        float bubbleW = npc.calloutText.length() * 10 + 30;
                        float bubbleX = sx - bubbleW / 2;
                        float bubbleY = sy;

                        // Speech bubble background
                        drawHUDRect(bubbleX, bubbleY, bubbleW, 30, 0.1f, 0.06f, 0.02f, 0.85f * alpha);
                        // Border
                        drawHUDRect(bubbleX, bubbleY + 28, bubbleW, 2, 1f, 0.8f, 0.2f, 0.9f * alpha);
                        drawHUDRect(bubbleX, bubbleY, bubbleW, 2, 1f, 0.8f, 0.2f, 0.9f * alpha);
                        // Text
                        drawHUDText(npc.calloutText, bubbleX + 15, bubbleY + 8, 2, 1f, 0.9f, 0.3f, alpha);
                    }
                }
            }
        }

        // Dialogue box
        if (dialogueActive) {
            float boxW = screenW * 0.75f;
            float boxH = 220;
            float boxX = (screenW - boxW) / 2;
            float boxY = 30;

            drawHUDRect(boxX, boxY, boxW, boxH, 0.06f, 0.04f, 0.02f, 0.88f);
            // Borders
            drawHUDRect(boxX, boxY + boxH - 2, boxW, 2, 0.7f, 0.4f, 0.1f, 0.9f);
            drawHUDRect(boxX, boxY, boxW, 2, 0.7f, 0.4f, 0.1f, 0.9f);
            drawHUDRect(boxX, boxY, 2, boxH, 0.7f, 0.4f, 0.1f, 0.9f);
            drawHUDRect(boxX + boxW - 2, boxY, 2, boxH, 0.7f, 0.4f, 0.1f, 0.9f);

            // Speaker name
            drawHUDText(dialogueSpeaker, boxX + 20, boxY + boxH - 30, 2, 1f, 0.7f, 0.2f, 1f);

            // Dialogue text (6 lines per page)
            int linesPerPage = 6;
            int startLine = dialoguePage * linesPerPage;
            for (int i = 0; i < linesPerPage && (startLine + i) < dialogueLines.length; i++) {
                String line = dialogueLines[startLine + i];
                drawHUDText(line.toUpperCase(), boxX + 20, boxY + boxH - 52 - i * 22, 2, 0.9f, 0.9f, 0.85f, 1f);
            }

            // Continue prompt
            int totalPages = (dialogueLines.length + linesPerPage - 1) / linesPerPage;
            String contText = (dialoguePage < totalPages - 1) ? "PRESS T OR ENTER TO CONTINUE..." : "PRESS T OR ENTER TO CLOSE";
            drawHUDText(contText, boxX + 20, boxY + 10, 2, 0.6f, 0.45f, 0.2f, 1f);
        }

        if (creativeMode && DEV_CREATIVE_MODE_ENABLED) {
            String blockLabel = CREATIVE_PALETTE[Math.max(0, Math.min(CREATIVE_PALETTE.length - 1, creativePaletteIndex))].replace('_', ' ').toUpperCase();
            String tip = "Creative (" + blockLabel + "): B place | N remove | Backspace clear | [ ] cycle | C exit";
            float tipW = tip.length() * 8f + 30f;
            float tipX = screenW - tipW - 20f;
            float tipY = 30f;
            drawHUDRect(tipX, tipY, tipW, 32f, 0f, 0f, 0f, 0.55f);
            drawHUDText(tip, tipX + 12f, tipY + 10f, 2, 0.7f, 1f, 0.8f, 1f);
        }

        // Restore state
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
    }

    private void handleCreativeBuildControls(long window, Vector3f direction) {
        if (!creativeMode || !DEV_CREATIVE_MODE_ENABLED) {
            creativePlacePressed = false;
            creativeBreakPressed = false;
            creativeClearPressed = false;
            creativeNextPressed = false;
            creativePrevPressed = false;
            return;
        }

        CreativeHit hit = pickBlockOnRay(direction, CREATIVE_RAY_DISTANCE);
        Vector3f fallbackTarget = computeCreativeTarget(direction);

        if (glfwGetKey(window, GLFW_KEY_RIGHT_BRACKET) == GLFW_PRESS) {
            if (!creativeNextPressed) {
                creativeNextPressed = true;
                creativePaletteIndex = (creativePaletteIndex + 1) % CREATIVE_PALETTE.length;
                notificationText = "Creative block: " + CREATIVE_PALETTE[creativePaletteIndex];
                notificationTimer = 1.2f;
            }
        } else {
            creativeNextPressed = false;
        }

        if (glfwGetKey(window, GLFW_KEY_LEFT_BRACKET) == GLFW_PRESS) {
            if (!creativePrevPressed) {
                creativePrevPressed = true;
                creativePaletteIndex = (creativePaletteIndex - 1 + CREATIVE_PALETTE.length) % CREATIVE_PALETTE.length;
                notificationText = "Creative block: " + CREATIVE_PALETTE[creativePaletteIndex];
                notificationTimer = 1.2f;
            }
        } else {
            creativePrevPressed = false;
        }

        if (glfwGetKey(window, GLFW_KEY_B) == GLFW_PRESS) {
            if (!creativePlacePressed) {
                creativePlacePressed = true;
                Vector3f placement = null;
                if (hit != null && hit.point != null && hit.normal != null && hit.normal.lengthSquared() > 0.0001f) {
                    placement = new Vector3f(hit.point).add(new Vector3f(hit.normal).normalize().mul(BLOCK_SIZE * 0.5f));
                } else {
                    placement = fallbackTarget;
                }
                placement = snapToCreativeGrid(placement);
                String tex = CREATIVE_PALETTE[Math.max(0, Math.min(CREATIVE_PALETTE.length - 1, creativePaletteIndex))];
                if (placeCreativeBlock(placement, tex)) {
                    notificationText = String.format(Locale.US,
                        "Placed %s at (%.1f, %.1f, %.1f)", tex, placement.x, placement.y, placement.z);
                } else {
                    notificationText = "No valid spot for creative block";
                }
                notificationTimer = 1.5f;
            }
        } else {
            creativePlacePressed = false;
        }

        if (glfwGetKey(window, GLFW_KEY_N) == GLFW_PRESS) {
            if (!creativeBreakPressed) {
                creativeBreakPressed = true;
                TexturedGameObject block = (hit != null) ? hit.block : findCreativeBlockAt(fallbackTarget);
                if (block != null && removeBlock(block)) {
                    notificationText = block.isCreativeBlock ? "Removed creative block" : "Removed structural block";
                } else {
                    notificationText = "No block to remove";
                }
                notificationTimer = 1.2f;
            }
        } else {
            creativeBreakPressed = false;
        }

        if (glfwGetKey(window, GLFW_KEY_BACKSPACE) == GLFW_PRESS) {
            if (!creativeClearPressed) {
                creativeClearPressed = true;
                int cleared = clearCreativeBlocks();
                notificationText = cleared > 0 ? ("Cleared " + cleared + " creative blocks") : "No creative blocks to clear";
                notificationTimer = 1.2f;
            }
        } else {
            creativeClearPressed = false;
        }
    }

    private Vector3f computeCreativeTarget(Vector3f direction) {
        if (direction == null) return null;
        Vector3f norm = new Vector3f(direction);
        if (norm.lengthSquared() < 0.0001f) {
            norm.set(0, 0, -1);
        }
        norm.normalize();
        Vector3f target = new Vector3f(cameraPos).add(norm.mul(CREATIVE_RAY_DISTANCE));
        return snapToCreativeGrid(target);
    }

    private Vector3f snapToCreativeGrid(Vector3f point) {
        if (point == null) return null;
        float snappedX = snapToStep(point.x, CREATIVE_SNAP_STEP);
        float snappedY = snapToStep(point.y, CREATIVE_SNAP_STEP);
        float snappedZ = snapToStep(point.z, CREATIVE_SNAP_STEP);
        return new Vector3f(snappedX, snappedY, snappedZ);
    }

    private float snapToStep(float value, float step) {
        return (float) Math.round(value / step) * step;
    }

    private boolean placeCreativeBlock(Vector3f target, String textureName) {
        if (target == null || textureName == null || textureName.isEmpty()) return false;
        if (findCreativeBlockAt(target) != null) return false;
        TexturedGameObject placed = addTexturedCubeAndReturn(target.x, target.y, target.z,
            BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, textureName);
        placed.isCreativeBlock = true;
        creativeBlocks.add(placed);
        savedPlacements.add(new CreativePlacement(textureName, target));
        saveCreativeEdits();
        return true;
    }

    private int clearCreativeBlocks() {
        int count = creativeBlocks.size();
        if (count == 0) return 0;
        for (TexturedGameObject block : new ArrayList<>(creativeBlocks)) {
            texturedObjects.remove(block);
        }
        creativeBlocks.clear();
        savedPlacements.clear();
        saveCreativeEdits();
        return count;
    }

    private boolean isSameCreativeCell(Vector3f a, Vector3f b) {
        if (a == null || b == null) return false;
        float tol = CREATIVE_SNAP_STEP * 0.4f;
        return Math.abs(a.x - b.x) <= tol && Math.abs(a.y - b.y) <= tol && Math.abs(a.z - b.z) <= tol;
    }

    private String describePlayerLocation(float px, float pz) {
        float BS = BLOCK_SIZE;
        float hallEntranceZWorld = -REACTOR_HALL_DEPTH_BLOCKS * BS;
        float hallPositiveZWorld = REACTOR_HALL_DEPTH_BLOCKS * BS;
        if (pz >= CORRIDOR_START_Z) {
            return "Control Room";
        }
        if (px <= PUMP_ANNEX_MAX_X && px >= PUMP_ANNEX_MIN_X && pz >= PUMP_ANNEX_MIN_Z && pz <= PUMP_ANNEX_MAX_Z) {
            return "Pump Annex";
        }
        if (px <= -17 * BS && px >= -21 * BS && pz >= -46 * BS && pz <= hallEntranceZWorld) {
            return "Reactor Walkway";
        }
        if (px >= PUMP_WALKWAY_INNER_WALL && px <= PUMP_WALKWAY_OUTER_WALL && pz >= -46 * BS && pz <= hallEntranceZWorld) {
            return "Pump Walkway";
        }
        if (px <= -17 * BS && pz >= hallEntranceZWorld && pz <= hallPositiveZWorld) {
            return "Reactor Hall";
        }
        if (pZInRange(pz, -46, -42) && px >= (CORRIDOR_HALF_WIDTH + BS) && px < PUMP_WALKWAY_INNER_WALL) {
            return "Pump Arm Landing";
        }
        if (pZInRange(pz, -46, -42) && px <= -17 * BS) {
            return "Reactor Arm Landing";
        }
        if (pZInRange(pz, -46, -42)) {
            return "Bottom Landing";
        }
        if (pZInRange(pz, -42, -34)) {
            return "Stairwell";
        }
        if (pZInRange(pz, -34, -12)) {
            return "Straight Corridor";
        }
        return "Service Corridor";
    }

    private boolean pZInRange(float pz, int maxBlock, int minBlock) {
        float BS = BLOCK_SIZE;
        return pz <= minBlock * BS && pz >= maxBlock * BS;
    }

    private void drawHUDRect(float x, float y, float w, float h, float r, float g, float b, float a) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer fb = stack.mallocFloat(16);
            Matrix4f m = new Matrix4f().translate(x, y, 0).scale(w, h, 1);
            glUniformMatrix4fv(hudModelLoc, false, m.get(fb));
        }
        glUniform4f(hudColorLoc, r, g, b, a);
        glUniform1i(hudUseTexLoc, 0);
        glBindVertexArray(hudQuadVAO);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glBindVertexArray(0);
    }

    private void drawHUDText(String text, float x, float y, int scale, float r, float g, float b, float a) {
        if (text == null || text.isEmpty()) return;
        float charW = 4 * scale;
        float charH = 6 * scale;
        float spacing = 1 * scale;
        float cursorX = x;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ') {
                cursorX += charW + spacing;
                continue;
            }
            int[][] font = getCharFont(Character.toUpperCase(c));
            for (int dy = 0; dy < 6; dy++) {
                for (int dx = 0; dx < 4; dx++) {
                    if (font[dy][dx] == 1) {
                        drawHUDRect(cursorX + dx * scale, y + (5 - dy) * scale, scale, scale, r, g, b, a);
                    }
                }
            }
            cursorX += charW + spacing;
        }
    }

    private void loadCreativeEdits() {
        creativeBlocks.clear();
        savedPlacements.clear();
        savedRemovals.clear();
        Path savePath = Paths.get(CREATIVE_SAVE_FILE);
        if (!Files.exists(savePath)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(savePath, StandardCharsets.UTF_8);
            for (String rawLine : lines) {
                if (rawLine == null) continue;
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\s+");
                if (parts.length == 0) continue;
                if (parts[0].equalsIgnoreCase("PLACE") && parts.length >= 5) {
                    try {
                        String tex = parts[1];
                        float x = Float.parseFloat(parts[2]);
                        float y = Float.parseFloat(parts[3]);
                        float z = Float.parseFloat(parts[4]);
                        CreativePlacement placement = new CreativePlacement(tex, new Vector3f(x, y, z));
                        savedPlacements.add(placement);
                        spawnCreativeBlockFromSave(tex, new Vector3f(x, y, z));
                    } catch (NumberFormatException ignore) {
                        System.err.println("[CREATIVE] Invalid PLACE entry: " + line);
                    }
                } else if (parts[0].equalsIgnoreCase("REMOVE") && parts.length >= 7) {
                    try {
                        float x = Float.parseFloat(parts[1]);
                        float y = Float.parseFloat(parts[2]);
                        float z = Float.parseFloat(parts[3]);
                        float sx = Float.parseFloat(parts[4]);
                        float sy = Float.parseFloat(parts[5]);
                        float sz = Float.parseFloat(parts[6]);
                        savedRemovals.add(new CreativeRemoval(new Vector3f(x, y, z), new Vector3f(sx, sy, sz)));
                    } catch (NumberFormatException ignore) {
                        System.err.println("[CREATIVE] Invalid REMOVE entry: " + line);
                    }
                }
            }
            applySavedRemovals();
        } catch (IOException ex) {
            System.err.println("[CREATIVE] Failed to read edits: " + ex.getMessage());
        }
    }

    private void spawnCreativeBlockFromSave(String textureName, Vector3f position) {
        TexturedGameObject obj = addTexturedCubeAndReturn(position.x, position.y, position.z,
            BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, textureName);
        obj.isCreativeBlock = true;
        creativeBlocks.add(obj);
    }

    private void applySavedRemovals() {
        if (savedRemovals.isEmpty()) return;
        List<TexturedGameObject> toCull = new ArrayList<>();
        for (CreativeRemoval removal : savedRemovals) {
            TexturedGameObject victim = findBlockByTransform(removal.position, removal.scale);
            if (victim != null) {
                toCull.add(victim);
            }
        }
        texturedObjects.removeAll(toCull);
    }

    private TexturedGameObject findBlockByTransform(Vector3f position, Vector3f scale) {
        float posTol = CREATIVE_SNAP_STEP * 0.25f;
        float scaleTol = BLOCK_SIZE * 0.1f;
        for (TexturedGameObject obj : texturedObjects) {
            if (obj == null || obj.isNPCPart) continue;
            if (Math.abs(obj.position.x - position.x) <= posTol &&
                Math.abs(obj.position.y - position.y) <= posTol &&
                Math.abs(obj.position.z - position.z) <= posTol &&
                Math.abs(obj.scale.x - scale.x) <= scaleTol &&
                Math.abs(obj.scale.y - scale.y) <= scaleTol &&
                Math.abs(obj.scale.z - scale.z) <= scaleTol) {
                return obj;
            }
        }
        return null;
    }

    private void saveCreativeEdits() {
        if (!DEV_CREATIVE_MODE_ENABLED) return;
        Path savePath = Paths.get(CREATIVE_SAVE_FILE);
        List<String> out = new ArrayList<>();
        out.add("# Creative edit log for corridor tuning - auto generated");
        for (CreativePlacement placement : savedPlacements) {
            out.add(String.format(Locale.US, "PLACE %s %.3f %.3f %.3f",
                placement.textureName,
                placement.position.x,
                placement.position.y,
                placement.position.z));
        }
        for (CreativeRemoval removal : savedRemovals) {
            out.add(String.format(Locale.US, "REMOVE %.3f %.3f %.3f %.3f %.3f %.3f",
                removal.position.x,
                removal.position.y,
                removal.position.z,
                removal.scale.x,
                removal.scale.y,
                removal.scale.z));
        }
        try {
            Files.write(savePath, out, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            System.err.println("[CREATIVE] Failed to write edits: " + ex.getMessage());
        }
    }

    private CreativeHit pickBlockOnRay(Vector3f direction, float maxDistance) {
        if (direction == null || maxDistance <= 0f) return null;
        Vector3f dir = new Vector3f(direction);
        if (dir.lengthSquared() < 0.0001f) return null;
        dir.normalize();
        Vector3f origin = new Vector3f(cameraPos);
        CreativeHit bestHit = null;
        float bestDistance = maxDistance;
        for (TexturedGameObject obj : texturedObjects) {
            if (obj == null || obj.isNPCPart) continue;
            CreativeHit hit = intersectRayWithBlock(origin, dir, obj, maxDistance);
            if (hit != null && hit.distance < bestDistance) {
                bestDistance = hit.distance;
                bestHit = hit;
            }
        }
        return bestHit;
    }

    private CreativeHit intersectRayWithBlock(Vector3f origin, Vector3f dir, TexturedGameObject obj, float maxDistance) {
        float halfX = obj.scale.x * 0.5f;
        float halfY = obj.scale.y * 0.5f;
        float halfZ = obj.scale.z * 0.5f;
        float minX = obj.position.x - halfX;
        float maxX = obj.position.x + halfX;
        float minY = obj.position.y - halfY;
        float maxY = obj.position.y + halfY;
        float minZ = obj.position.z - halfZ;
        float maxZ = obj.position.z + halfZ;
        float tMin = 0f;
        float tMax = maxDistance;
        Vector3f hitNormal = new Vector3f();
        float[] bounds = tempBounds;
        bounds[0] = tMin;
        bounds[1] = tMax;
        if (!axisIntersect(origin.x, dir.x, minX, maxX, 0, hitNormal, bounds)) return null;
        tMin = bounds[0];
        tMax = bounds[1];
        if (!axisIntersect(origin.y, dir.y, minY, maxY, 1, hitNormal, bounds)) return null;
        tMin = bounds[0];
        tMax = bounds[1];
        if (!axisIntersect(origin.z, dir.z, minZ, maxZ, 2, hitNormal, bounds)) return null;
        tMin = bounds[0];
        if (tMin < 0f) {
            if (tMax < 0f) return null;
            tMin = tMax;
        }
        if (tMin > maxDistance) return null;
        Vector3f hitPoint = new Vector3f(dir).mul(tMin).add(origin);
        return new CreativeHit(obj, hitPoint, new Vector3f(hitNormal), tMin);
    }

    private float[] tempBounds = new float[2];

    private boolean axisIntersect(float origin, float dir, float min, float max, int axis,
                                  Vector3f normalOut, float[] bounds) {
        float tMin = bounds[0];
        float tMax = bounds[1];
        float EPS = 1e-4f;
        if (Math.abs(dir) < EPS) {
            if (origin < min || origin > max) {
                return false;
            }
            bounds[0] = tMin;
            bounds[1] = tMax;
            return true;
        }
        float inv = 1f / dir;
        float t1 = (min - origin) * inv;
        float t2 = (max - origin) * inv;
        if (t1 > t2) {
            float tmp = t1;
            t1 = t2;
            t2 = tmp;
        }
        if (t1 > tMin) {
            tMin = t1;
            normalOut.set(0f, 0f, 0f);
            float sign = (dir > 0f) ? -1f : 1f;
            if (axis == 0) normalOut.x = sign;
            else if (axis == 1) normalOut.y = sign;
            else normalOut.z = sign;
        }
        if (t2 < tMax) {
            tMax = t2;
        }
        if (tMax < tMin) {
            return false;
        }
        bounds[0] = tMin;
        bounds[1] = tMax;
        return true;
    }

    private boolean removeBlock(TexturedGameObject block) {
        if (block == null || block.isNPCPart) return false;
        boolean removed = texturedObjects.remove(block);
        if (!removed) return false;
        creativeBlocks.remove(block);
        if (block.isCreativeBlock) {
            removePlacementEntry(block.position);
        } else {
            if (!removalExists(block.position, block.scale)) {
                savedRemovals.add(new CreativeRemoval(block.position, block.scale));
            }
        }
        saveCreativeEdits();
        return true;
    }

    private boolean removalExists(Vector3f position, Vector3f scale) {
        float tol = CREATIVE_SNAP_STEP * 0.25f;
        float scaleTol = BLOCK_SIZE * 0.1f;
        for (CreativeRemoval removal : savedRemovals) {
            if (Math.abs(removal.position.x - position.x) <= tol &&
                Math.abs(removal.position.y - position.y) <= tol &&
                Math.abs(removal.position.z - position.z) <= tol &&
                Math.abs(removal.scale.x - scale.x) <= scaleTol &&
                Math.abs(removal.scale.y - scale.y) <= scaleTol &&
                Math.abs(removal.scale.z - scale.z) <= scaleTol) {
                return true;
            }
        }
        return false;
    }

    private void removePlacementEntry(Vector3f position) {
        float tol = CREATIVE_SNAP_STEP * 0.25f;
        savedPlacements.removeIf(entry ->
            Math.abs(entry.position.x - position.x) <= tol &&
            Math.abs(entry.position.y - position.y) <= tol &&
            Math.abs(entry.position.z - position.z) <= tol);
    }

    private TexturedGameObject findCreativeBlockAt(Vector3f target) {
        if (target == null) return null;
        float tol = CREATIVE_SNAP_STEP * 0.25f;
        for (TexturedGameObject block : creativeBlocks) {
            if (Math.abs(block.position.x - target.x) <= tol &&
                Math.abs(block.position.y - target.y) <= tol &&
                Math.abs(block.position.z - target.z) <= tol) {
                return block;
            }
        }
        return null;
    }

    // === MACHINE INTERACTION SYSTEM METHODS ===

    private void updateReactorState() {
        // Set BASELINE values from story phase, then let player adjustments modify
        switch (storyPhase) {
            case 0: case 1:
                if (!playerReducedPower) {
                    reactorPower = 1600f; reactorTemperature = 270f; reactorPressure = 70f;
                    controlRodsInserted = 80; coolantFlow = 100f; neutronFlux = 100f;
                    xenonLevel = 5f;
                }
                break;
            case 8: // AZ-5 surge
                reactorPower = 33000f; reactorTemperature = 4650f; reactorPressure = 350f;
                controlRodsInserted = 0; coolantFlow = 0f; neutronFlux = 30000f;
                xenonLevel = 0f; reactorStability = 0f;
                break;
            case 9: // Explosion
                reactorPower = 0f; reactorTemperature = 9999f; reactorPressure = 0f;
                controlRodsInserted = 0; coolantFlow = 0f; neutronFlux = 0f;
                xenonLevel = 0f; reactorStability = 0f;
                break;
        }
    }

    private void simulateReactor(float dt) {
        if (storyPhase >= 8) return; // No simulation after surge

        reactorSimTimer += dt;
        if (reactorSimTimer < 0.1f) return;
        reactorSimTimer = 0;

        // Physics: control rod insertion affects power
        float rodFraction = controlRodsInserted / 211f;
        float targetPower = 3200f * (1f - rodFraction * 0.9f); // Max 3200 MW with all rods out

        // Xenon poisoning: builds up when power is low, suppresses power
        float xenonBuildThreshold = (storyPhase >= 4) ? 800f : 400f;
        if (reactorPower < xenonBuildThreshold) {
            xenonLevel = Math.min(100f, xenonLevel + 0.3f);
        } else if (reactorPower > 800f) {
            xenonLevel = Math.max(0f, xenonLevel - 0.2f);
        }
        float xenonSuppression = 1f - (xenonLevel / 130f); // Xenon reduces achievable power
        targetPower *= Math.max(0.01f, xenonSuppression);

        // Void coefficient: becomes dangerous with low rods + low coolant
        if (controlRodsInserted < 30 && !coolantPumpsOn) {
            voidCoefficient = 0.5f + (30f - controlRodsInserted) * 0.03f;
        } else {
            voidCoefficient = Math.max(0, voidCoefficient - 0.05f);
        }
        targetPower += voidCoefficient * 800f;

        // Smoothly approach target power
        reactorPower += (targetPower - reactorPower) * 0.08f;
        reactorPower = Math.max(0f, reactorPower);

        // Temperature follows power (much worse without cooling!)
        float targetTemp = 150f + reactorPower * 0.08f;
        if (!coolantPumpsOn) targetTemp += 200f + reactorPower * 0.1f;
        if (!coolantPumpsOn && !emergencyCoolingOn) targetTemp += 300f;
        reactorTemperature += (targetTemp - reactorTemperature) * 0.05f;

        // Pressure follows temperature
        reactorPressure = 30f + reactorTemperature * 0.15f;

        // Neutron flux correlates to power
        neutronFlux = reactorPower * 0.065f;

        // Coolant flow
        if (coolantPumpsOn) {
            if (turbineConnected) coolantFlow = Math.min(100f, coolantFlow + 2f);
            else coolantFlow = Math.max(40f, coolantFlow - 0.5f);
        } else {
            coolantFlow = Math.max(0f, coolantFlow - 3f);
        }

        // Stability: low rods + high xenon + low coolant = bad
        // ELENA scanning rewards knowledge, neglect penalizes ignorance
        reactorStability = 100f;
        if (controlRodsInserted < 30) reactorStability -= (30 - controlRodsInserted) * 2f;
        if (xenonLevel > 50) reactorStability -= (xenonLevel - 50) * 0.5f;
        if (coolantFlow < 50) reactorStability -= (50 - coolantFlow) * 0.8f;
        reactorStability += elenaScanStabilityBonus;  // Reward for ELENA scanning
        reactorStability -= elenaScanNeglectPenalty;   // Penalty for ignoring ELENA
        reactorStability += indicatorCheckBonus;        // Reward for indicator monitoring
        reactorStability -= indicatorNeglectPenalty;    // Penalty for ignoring indicators
        reactorStability = Math.max(0f, Math.min(100f, reactorStability));

        // === INDICATOR PANEL: Drift simulation ===
        // Instrument drift grows randomly; faster when reactor is unstable
        float driftRate = 0.02f + (100f - reactorStability) * 0.003f; // More unstable = more drift
        indicatorDriftPower += (indicatorRng.nextFloat() - 0.5f) * driftRate * reactorPower * 0.01f;
        indicatorDriftTemp += (indicatorRng.nextFloat() - 0.5f) * driftRate * reactorTemperature * 0.008f;
        indicatorDriftPressure += (indicatorRng.nextFloat() - 0.5f) * driftRate * reactorPressure * 0.008f;
        indicatorDriftFlux += (indicatorRng.nextFloat() - 0.5f) * driftRate * neutronFlux * 0.01f;
        indicatorDriftXenon += (indicatorRng.nextFloat() - 0.5f) * driftRate * 0.3f;
        indicatorDriftCoolant += (indicatorRng.nextFloat() - 0.5f) * driftRate * 0.4f;
        // Clamp drift to reasonable bounds (max ~15% of value)
        float maxPDrift = Math.max(20f, reactorPower * 0.15f);
        indicatorDriftPower = Math.max(-maxPDrift, Math.min(maxPDrift, indicatorDriftPower));
        float maxTDrift = Math.max(5f, reactorTemperature * 0.12f);
        indicatorDriftTemp = Math.max(-maxTDrift, Math.min(maxTDrift, indicatorDriftTemp));
        float maxPrDrift = Math.max(3f, reactorPressure * 0.1f);
        indicatorDriftPressure = Math.max(-maxPrDrift, Math.min(maxPrDrift, indicatorDriftPressure));
        indicatorDriftFlux = Math.max(-15f, Math.min(15f, indicatorDriftFlux));
        indicatorDriftXenon = Math.max(-5f, Math.min(5f, indicatorDriftXenon));
        indicatorDriftCoolant = Math.max(-8f, Math.min(8f, indicatorDriftCoolant));

        // === INDICATOR PANEL: Parameter history for trends ===
        paramHistoryTimer += 0.1f; // Each sim tick = 0.1s
        if (paramHistoryTimer >= 2.0f) { // Record every 2 seconds
            paramHistoryTimer = 0f;
            powerHistory[paramHistoryIdx] = reactorPower;
            tempHistory[paramHistoryIdx] = reactorTemperature;
            pressureHistory[paramHistoryIdx] = reactorPressure;
            paramHistoryIdx = (paramHistoryIdx + 1) % powerHistory.length;
        }

        // === INDICATOR PANEL: Alarm detection ===
        indicatorAlarmActive[0] = reactorPower > 2500f || reactorPower < 100f;     // Power alarm
        indicatorAlarmActive[1] = reactorTemperature > 400f;                        // Temperature alarm
        indicatorAlarmActive[2] = reactorPressure > 90f;                           // Pressure alarm
        indicatorAlarmActive[3] = neutronFlux > 140f;                              // Flux alarm
        indicatorAlarmActive[4] = coolantFlow < 60f;                               // Coolant alarm
        indicatorAlarmActive[5] = controlRodsInserted < 30;                        // Rod safety alarm
        // Clear acknowledged status when alarm deactivates
        for (int i = 0; i < 6; i++) {
            if (!indicatorAlarmActive[i]) indicatorAlarmAcknowledged[i] = false;
        }

        // === INDICATOR PANEL: Neglect timer ===
        indicatorCheckTimer += 0.1f; // Each sim tick ~0.1s
        if (indicatorCheckTimer > 60f) { // After 60s without checking
            indicatorNeglectPenalty = Math.min(10f, (indicatorCheckTimer - 60f) * 0.05f);
        } else {
            indicatorNeglectPenalty = Math.max(0f, indicatorNeglectPenalty - 0.01f);
        }

        updatePumpAnnexTelemetry(0.1f);

        // Story progression triggers from machine interaction
        phaseTimer += dt;
        if (storyPhase == 1 && playerReducedPower && reactorPower < 800f) {
            // Player reduced power - go to verify indicators
            storyPhase = 2;
            advanceStory();
        }
        if (storyPhase == 2 && playerReadIndicators && phaseTimer > 3f) {
            // Player verified indicator panels - go to ELENA
            storyPhase = 3;
            advanceStory();
        }
        if (storyPhase == 3 && playerCheckedElena && elenaScanCount >= 3) {
            // Player scanned ELENA - go to talk Akimov
            storyPhase = 4;
            advanceStory();
        }
        if (storyPhase == 5 && playerRaisedPower && reactorPower > 400f) {
            // Player raised power - advance to ELENA scan + turbine phase
            storyPhase = 6;
            advanceStory();
            showMachineLog("WARNING: ONLY " + controlRodsInserted + " RODS REMAIN!");
        }
        if (storyPhase == 6 && playerStartedTest && playerConfirmedTestReady) {
            storyPhase = 7;
            advanceStory();
            showMachineLog("SAFETY TEST INITIATED - TURBINE RUNDOWN");
        }

        // === CRITICAL FAILURE CONDITIONS - mistakes cause disaster! ===
        if (storyPhase >= 1 && storyPhase < 8) {
            if (reactorTemperature > 1200f) {
                // Overheating meltdown - player failed to maintain cooling
                storyPhase = 9;
                playerWon = false;
                endingTimer = 6f;
                endingCountdownActive = true;
                showNotification("CORE MELTDOWN! TEMPERATURE EXCEEDED 1200 C!");
                updateReactorState();
                return;
            }
            if (reactorPower > 4000f) {
                // Power excursion - player withdrew too many rods with pumps off
                storyPhase = 9;
                playerWon = false;
                endingTimer = 6f;
                endingCountdownActive = true;
                showNotification("POWER EXCURSION! REACTOR SUPERCRITICAL!");
                updateReactorState();
                return;
            }
            if (reactorStability <= 0f && controlRodsInserted < 15 && storyPhase >= 5) {
                // Total loss of control - too few rods in unstable conditions
                storyPhase = 9;
                playerWon = false;
                endingTimer = 6f;
                endingCountdownActive = true;
                showNotification("TOTAL LOSS OF CONTROL! REACTOR EXPLODES!");
                updateReactorState();
                return;
            }
        }
    }

    private void updatePumpAnnexTelemetry(float tick) {
        float pumpLoad = Math.max(0.2f, coolantFlow / 100f);
        float targetFlowA = 60f + pumpLoad * 30f + pumpConsoleTrimA + (pumpConsoleCrossfeedOpen ? 4f : 0f);
        float targetFlowB = 55f + pumpLoad * 25f + pumpConsoleTrimB + (pumpConsoleCrossfeedOpen ? 2f : -3f);
        if (pumpConsoleEmergencyFlush) targetFlowA += 6f;

        float noiseA = (pumpConsoleRng.nextFloat() - 0.5f) * 0.8f;
        float noiseB = (pumpConsoleRng.nextFloat() - 0.5f) * 0.8f;
        pumpConsoleFlowA += (targetFlowA - pumpConsoleFlowA) * 0.12f + noiseA * 0.05f;
        pumpConsoleFlowB += (targetFlowB - pumpConsoleFlowB) * 0.15f + noiseB * 0.05f;
        pumpConsoleFlowA = Math.max(35f, Math.min(115f, pumpConsoleFlowA));
        pumpConsoleFlowB = Math.max(30f, Math.min(110f, pumpConsoleFlowB));

        float tempTarget = 180f + reactorTemperature * 0.2f - (pumpConsoleEmergencyFlush ? 35f : 0f) + pumpLoad * 40f;
        pumpConsoleSealTemp += (tempTarget - pumpConsoleSealTemp) * 0.1f;
        pumpConsoleSealTemp = Math.max(120f, Math.min(360f, pumpConsoleSealTemp));

        float vibTarget = 0.12f + Math.abs(pumpConsoleFlowA - pumpConsoleFlowB) * 0.0025f;
        if (pumpConsoleCrossfeedOpen) vibTarget += 0.04f;
        if (pumpConsoleEmergencyFlush) vibTarget += 0.03f;
        pumpConsoleVibration += (vibTarget - pumpConsoleVibration) * 0.3f;
        pumpConsoleVibration = Math.max(0.05f, Math.min(0.45f, pumpConsoleVibration));

        float cavTarget = 0.15f + (1f - pumpLoad) * 0.4f + (pumpConsoleCrossfeedOpen ? 0.08f : 0f);
        if (pumpConsoleEmergencyFlush) cavTarget += 0.15f;
        pumpConsoleCavitationRisk += (cavTarget - pumpConsoleCavitationRisk) * 0.25f;
        pumpConsoleCavitationRisk = Math.max(0f, Math.min(1f, pumpConsoleCavitationRisk));

        float sealStress = Math.max(0f, (pumpConsoleSealTemp - 220f) / 180f);
        pumpConsoleAlertLevel = Math.max(0f, Math.min(1f,
            Math.max(sealStress, Math.max(pumpConsoleVibration * 2.1f, pumpConsoleCavitationRisk))));

        if (pumpConsoleDiagnosticsActive) {
            pumpConsoleDiagnosticsProgress += tick * 0.25f;
            if (pumpConsoleDiagnosticsProgress >= 1f) {
                pumpConsoleDiagnosticsActive = false;
                pumpConsoleDiagnosticsProgress = 1f;
                pumpConsoleAlertLevel = Math.max(0f, pumpConsoleAlertLevel - 0.1f);
                pumpConsoleCavitationRisk = Math.max(0.05f, pumpConsoleCavitationRisk - 0.1f);
                showMachineLog("FLOW DIAGNOSTIC COMPLETE - READINGS RESET");
            }
        } else {
            pumpConsoleDiagnosticsProgress = Math.max(0f, pumpConsoleDiagnosticsProgress - tick * 0.1f);
            if (!pumpConsoleEmergencyFlush) {
                pumpConsoleAlertLevel = Math.max(0f, pumpConsoleAlertLevel - tick * 0.02f);
            }
        }
    }

    // === ELENA ENHANCED SCAN METHOD ===
    private void performElenaScan(int sectorIndex, int gridSize) {
        elenaScanTimer = 2f;
        elenaScanCount++;
        playerCheckedElena = true;

        int row = sectorIndex / gridSize;
        int col = sectorIndex % gridSize;

        // Track unique scans and give stability bonus
        boolean isNewScan = !elenaScannedSectors[sectorIndex];
        elenaScannedSectors[sectorIndex] = true;
        if (isNewScan) {
            elenaUniqueScanCount++;
            // Each unique scan gives a small stability bonus (reward for thoroughness)
            elenaScanStabilityBonus += 0.5f;
            elenaScanStabilityBonus = Math.min(15f, elenaScanStabilityBonus); // cap at +15%
        }

        // Determine sector characteristics based on position + reactor state
        Random sectorRand = new Random(row * 19 + col * 37 + (int)(reactorPower * 10));
        float sectorXenon = xenonLevel * (0.6f + sectorRand.nextFloat() * 0.8f);
        float sectorVoid = voidCoefficient * (0.5f + sectorRand.nextFloat());
        boolean isHotSpot = sectorXenon > 40f;
        boolean isVoidSector = sectorVoid > 0.3f;

        // Detect xenon hot spots
        if (isHotSpot && isNewScan) {
            elenaHotSpotsFound++;
            if (elenaHotSpotsFound >= 3 && !elenaXenonWarningGiven) {
                elenaXenonWarningGiven = true;
                showNotification("ELENA WARNING: XENON-135 HOT SPOTS DETECTED IN " + elenaHotSpotsFound + " SECTORS!");
                // Early xenon warning gives a stability bonus
                elenaScanStabilityBonus += 3f;
                elenaScanStabilityBonus = Math.min(15f, elenaScanStabilityBonus);
            }
        }

        // Detect void coefficient anomalies (late game)
        if (isVoidSector && isNewScan && storyPhase >= 5) {
            elenaVoidSectorsFound++;
            if (elenaVoidSectorsFound >= 2 && !elenaVoidWarningGiven) {
                elenaVoidWarningGiven = true;
                showNotification("ELENA ALERT: POSITIVE VOID COEFFICIENT DETECTED! AZ-5 MAY CAUSE SURGE!");
                // Critical info - bigger stability bonus for finding this
                elenaScanStabilityBonus += 5f;
                elenaScanStabilityBonus = Math.min(15f, elenaScanStabilityBonus);
            }
        }

        // Generate scan result message
        if (storyPhase == 6 && elenaScanCount >= 5) {
            playerConfirmedTestReady = true;
            showMachineLog("CORE SCAN COMPLETE - TEST READINESS CONFIRMED");
        } else if (isHotSpot && isVoidSector) {
            showMachineLog("SECTOR " + row + "," + col + ": XENON=" + String.format("%.0f", sectorXenon) + "% + VOID ANOMALY!");
        } else if (isHotSpot) {
            showMachineLog("SECTOR " + row + "," + col + ": XENON HOT SPOT - " + String.format("%.0f", sectorXenon) + "%");
        } else if (isVoidSector) {
            showMachineLog("SECTOR " + row + "," + col + ": VOID COEFFICIENT WARNING");
        } else if (isNewScan) {
            showMachineLog("SECTOR " + row + "," + col + ": NOMINAL - XENON=" + String.format("%.0f", sectorXenon) + "%");
        } else {
            showMachineLog("SECTOR " + row + "," + col + ": RE-SCAN - NO CHANGE DETECTED");
        }
    }

    // Helper: check if a sector is a xenon hot spot (for rendering)
    private boolean isSectorXenonHotSpot(int row, int col) {
        Random r = new Random(row * 19 + col * 37 + (int)(reactorPower * 10));
        float sectorXenon = xenonLevel * (0.6f + r.nextFloat() * 0.8f);
        return sectorXenon > 40f;
    }

    // Helper: check if a sector has void coefficient anomaly (for rendering)
    private boolean isSectorVoidAnomaly(int row, int col) {
        Random r = new Random(row * 19 + col * 37 + (int)(reactorPower * 10));
        r.nextFloat(); // skip xenon random
        float sectorVoid = voidCoefficient * (0.5f + r.nextFloat());
        return sectorVoid > 0.3f && storyPhase >= 5;
    }

    private void showMachineLog(String msg) {
        machineLogMessage = msg;
        machineLogTimer = 5f;
    }

    private void updateMachineInteraction(long window) {
        float dt = 1f / 60f; // approximate

        // Machine log timer
        if (machineLogTimer > 0) machineLogTimer -= dt;

        // Always simulate reactor when game is running
        if (storyPhase >= 1 && storyPhase < 8) {
            simulateReactor(dt);
        }

        // Track mouse position (screen coords, Y flipped for top-down)
        double[] mxArr = new double[1], myArr = new double[1];
        glfwGetCursorPos(window, mxArr, myArr);
        int[] winW = new int[1], winH = new int[1];
        glfwGetWindowSize(window, winW, winH);
        machineMouseX = (float) mxArr[0];
        machineMouseY = (float) (winH[0] - myArr[0]); // Flip Y for OpenGL coords

        // Track mouse button clicks (detect press edge)
        boolean leftNow = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS;
        boolean rightNow = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_RIGHT) == GLFW_PRESS;
        mouseLeftClicked = leftNow && !mouseLeftHeld;
        mouseRightClicked = rightNow && !mouseRightHeld;
        mouseLeftHeld = leftNow;
        mouseRightHeld = rightNow;

        if (machineUIActive) {
            // Show cursor when machine UI is open
            if (glfwGetInputMode(window, GLFW_CURSOR) != GLFW_CURSOR_NORMAL) {
                glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
                // Center the cursor on screen
                glfwSetCursorPos(window, winW[0] / 2.0, winH[0] / 2.0);
                machineMouseX = winW[0] / 2f;
                machineMouseY = winH[0] / 2f;
                firstMouse = true;
            }

            // ESC closes machine UI (reserve right-click for machine controls)
            if (glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS) {
                if (!escKeyPressed) {
                    escKeyPressed = true;
                    machineUIActive = false;
                    activeMachine = "";
                    // Re-capture mouse for FPS controls
                    glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                    firstMouse = true;
                    // Prevent ESC from also triggering pause menu on same frame
                    menuEscHeld = true;
                }
            } else {
                escKeyPressed = false;
            }

            float screenW = (float) winW[0];
            float screenH = (float) winH[0];

            // === CONTROL PANEL MOUSE CONTROLS ===
            if (activeMachine.equals("CONTROL_PANEL")) {
                float panelW = screenW * 0.88f;
                float panelH = screenH * 0.88f;
                float panelX = (screenW - panelW) / 2;
                float panelY = (screenH - panelH) / 2;
                float leftW2 = panelW * 0.58f;
                float ctrlX = panelX + leftW2 + 5;
                float ctrlW = panelW - leftW2 - 25;
                float ctrlTopY = panelY + panelH - 55;

                // Detect hover over control items
                float itemH = 48;
                float itemStartY = ctrlTopY - 62;
                for (int i = 0; i < 6; i++) {
                    float iy = itemStartY - i * itemH;
                    float iyBot = iy - itemH + 4;
                    float iyTop = iy;
                    if (machineMouseX >= ctrlX + 5 && machineMouseX <= ctrlX + ctrlW - 10 &&
                        machineMouseY >= iyBot && machineMouseY <= iyTop) {
                        selectedControl = i;
                        break;
                    }
                }

                // Left click = decrease/withdraw/off, Right click = increase/insert/on
                mouseClickRepeatTimer += dt;
                if ((leftNow || rightNow) && mouseClickRepeatTimer > 0.1f) {
                    mouseClickRepeatTimer = 0f;
                    // Check cursor is in the controls area
                    float ctrlBotY = itemStartY - 6 * itemH;
                    if (machineMouseX >= ctrlX && machineMouseX <= ctrlX + ctrlW &&
                        machineMouseY >= ctrlBotY && machineMouseY <= ctrlTopY) {
                        int dir = rightNow ? 1 : -1;
                        applyControlAdjustment(selectedControl, dir);
                    }
                }
                if (!leftNow && !rightNow) mouseClickRepeatTimer = 1f; // instant first click

                // ECCS toggle button area (matches render togY-18)
                float togY2 = itemStartY - 6 * itemH - 10;
                float eccsTextY = togY2 - 18;
                if (mouseLeftClicked &&
                    machineMouseX >= ctrlX + 5 && machineMouseX <= ctrlX + ctrlW - 10 &&
                    machineMouseY >= eccsTextY - 5 && machineMouseY <= eccsTextY + 18) {
                    emergencyCoolingOn = !emergencyCoolingOn;
                    showMachineLog(emergencyCoolingOn ? "ECCS ENABLED" : "WARNING: ECCS DISABLED!");
                }

                // AZ-5 button click (match render size)
                float az5W2 = 200;
                float az5H2 = 50;
                float az5X2 = panelX + panelW - az5W2 - 20;
                float az5Y2 = panelY + 8;
                if (storyPhase == 7 && !az5Pressed && mouseLeftClicked &&
                    machineMouseX >= az5X2 && machineMouseX <= az5X2 + az5W2 &&
                    machineMouseY >= az5Y2 && machineMouseY <= az5Y2 + az5H2) {
                    az5Pressed = true;
                    // AZ-5 outcome depends on multiple factors — not just rod count!
                    boolean rodsOk = controlRodsInserted >= 30;
                    boolean tempSafe = reactorTemperature < 800f;
                    boolean stableEnough = reactorStability > 15f;
                    boolean coolingOk = coolantPumpsOn || emergencyCoolingOn;
                    
                    if (rodsOk && tempSafe && stableEnough && coolingOk) {
                        // WIN: Safe conditions - AZ-5 works
                        playerWon = true;
                        storyPhase = 10;
                        storyTime = "26 APRIL 1986 - 01:23:40";
                        currentObjective = "";
                        showNotification("AZ-5 ACTIVATED - REACTOR SHUTTING DOWN SAFELY!");
                        reactorPower = 0;
                        reactorTemperature = 250;
                        controlRodsInserted = 211;
                        reactorStability = 100;
                        endingTimer = 6f;
                        endingCountdownActive = true;
                        showMachineLog("REACTOR SCRAM SUCCESSFUL - ALL RODS INSERTED");
                    } else {
                        // LOSE: Conditions too dangerous - graphite tip surge or thermal failure
                        playerWon = false;
                        storyPhase = 8;
                        advanceStory();
                        updateReactorState();
                        if (!rodsOk) showMachineLog("!!! AZ-5 GRAPHITE TIP SURGE - TOO FEW RODS !!!");
                        else if (!tempSafe) showMachineLog("!!! AZ-5 FAILED - CORE TOO HOT !!!");
                        else if (!stableEnough) showMachineLog("!!! AZ-5 FAILED - REACTOR UNSTABLE !!!");
                        else showMachineLog("!!! AZ-5 FAILED - NO COOLING !!!");
                    }
                }

                // Keyboard still works too (UP/DOWN select, LEFT/RIGHT adjust)
                if (glfwGetKey(window, GLFW_KEY_UP) == GLFW_PRESS) {
                    if (!upKeyHeld) { upKeyHeld = true; selectedControl = Math.max(0, selectedControl - 1); }
                } else { upKeyHeld = false; }
                if (glfwGetKey(window, GLFW_KEY_DOWN) == GLFW_PRESS) {
                    if (!downKeyHeld) { downKeyHeld = true; selectedControl = Math.min(5, selectedControl + 1); }
                } else { downKeyHeld = false; }

                boolean leftPress = glfwGetKey(window, GLFW_KEY_LEFT) == GLFW_PRESS;
                boolean rightPress = glfwGetKey(window, GLFW_KEY_RIGHT) == GLFW_PRESS;
                controlRepeatTimer += dt;
                if ((leftPress || rightPress) && controlRepeatTimer > 0.08f) {
                    controlRepeatTimer = 0;
                    int dir = rightPress ? 1 : -1;
                    applyControlAdjustment(selectedControl, dir);
                }
                if (!leftPress && !rightPress) controlRepeatTimer = 1f;

                // Space for AZ-5
                if (storyPhase == 7 && !az5Pressed) {
                    if (glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS) {
                        if (!spaceKeyPressed) {
                            spaceKeyPressed = true;
                            az5Pressed = true;
                            boolean rodsOk = controlRodsInserted >= 30;
                            boolean tempSafe = reactorTemperature < 800f;
                            boolean stableEnough = reactorStability > 15f;
                            boolean coolingOk = coolantPumpsOn || emergencyCoolingOn;
                            
                            if (rodsOk && tempSafe && stableEnough && coolingOk) {
                                // WIN: Safe conditions - AZ-5 works
                                playerWon = true;
                                storyPhase = 10;
                                storyTime = "26 APRIL 1986 - 01:23:40";
                                currentObjective = "";
                                showNotification("AZ-5 ACTIVATED - REACTOR SHUTTING DOWN SAFELY!");
                                reactorPower = 0;
                                reactorTemperature = 250;
                                controlRodsInserted = 211;
                                reactorStability = 100;
                                endingTimer = 6f;
                                endingCountdownActive = true;
                                showMachineLog("REACTOR SCRAM SUCCESSFUL - ALL RODS INSERTED");
                            } else {
                                // LOSE: Conditions too dangerous
                                playerWon = false;
                                storyPhase = 8;
                                advanceStory();
                                updateReactorState();
                                if (!rodsOk) showMachineLog("!!! AZ-5 GRAPHITE TIP SURGE - TOO FEW RODS !!!");
                                else if (!tempSafe) showMachineLog("!!! AZ-5 FAILED - CORE TOO HOT !!!");
                                else if (!stableEnough) showMachineLog("!!! AZ-5 FAILED - REACTOR UNSTABLE !!!");
                                else showMachineLog("!!! AZ-5 FAILED - NO COOLING !!!");
                            }
                        }
                    } else { spaceKeyPressed = false; }
                }
            }

            // === ELENA MOUSE CONTROLS ===
            if (activeMachine.equals("ELENA")) {
                float eScreenW = screenW;
                float eScreenH = screenH;
                float ePanelW = eScreenW * 0.80f;
                float ePanelH = eScreenH * 0.85f;
                float ePanelX = (eScreenW - ePanelW) / 2;
                float ePanelY = (eScreenH - ePanelH) / 2;
                float coreR = Math.min(ePanelW, ePanelH) * 0.33f;
                float coreCX = ePanelX + ePanelW / 2;
                float coreCY = ePanelY + ePanelH / 2 + 5;
                int gridSize = 18;
                float cellSize = coreR * 2 / gridSize;

                // Detect mouse hover/click on core grid cells
                float gridLeft = coreCX - coreR;
                float gridBot = coreCY - coreR;
                if (machineMouseX >= gridLeft && machineMouseX <= gridLeft + gridSize * cellSize &&
                    machineMouseY >= gridBot && machineMouseY <= gridBot + gridSize * cellSize) {
                    int gx = (int)((machineMouseX - gridLeft) / cellSize);
                    int gy = (int)((machineMouseY - gridBot) / cellSize);
                    gx = Math.max(0, Math.min(gridSize - 1, gx));
                    gy = Math.max(0, Math.min(gridSize - 1, gy));
                    // Check if inside circle
                    float dx = (gx - gridSize / 2f + 0.5f) / (gridSize / 2f);
                    float dy = (gy - gridSize / 2f + 0.5f) / (gridSize / 2f);
                    float dist = (float) Math.sqrt(dx * dx + dy * dy);
                    if (dist <= 1.0f) {
                        elenaSelectedSector = gy * gridSize + gx;
                        // Left click to scan
                        if (mouseLeftClicked) {
                            performElenaScan(elenaSelectedSector, gridSize);
                        }
                    }
                }

                // Right-click anywhere to scan current selection
                if (mouseRightClicked && elenaSelectedSector >= 0) {
                    performElenaScan(elenaSelectedSector, gridSize);
                }

                elenaScanTimer = Math.max(0, elenaScanTimer - dt);

                // Scan neglect penalty: if story has progressed but player isn't scanning, reactor degrades faster
                if (storyPhase >= 3 && elenaUniqueScanCount < 5) {
                    elenaScanNeglectPenalty = (5 - elenaUniqueScanCount) * 2f; // up to -10 stability
                } else if (storyPhase >= 6 && elenaUniqueScanCount < 15) {
                    elenaScanNeglectPenalty = (15 - elenaUniqueScanCount) * 1f; // up to -15 stability
                } else {
                    elenaScanNeglectPenalty = 0f;
                }

                // Keyboard still works for ELENA
                if (glfwGetKey(window, GLFW_KEY_UP) == GLFW_PRESS) {
                    if (!upKeyHeld) { upKeyHeld = true; elenaSelectedSector = Math.max(0, elenaSelectedSector - 18); }
                } else { upKeyHeld = false; }
                if (glfwGetKey(window, GLFW_KEY_DOWN) == GLFW_PRESS) {
                    if (!downKeyHeld) { downKeyHeld = true; elenaSelectedSector = Math.min(323, elenaSelectedSector + 18); }
                } else { downKeyHeld = false; }
                if (glfwGetKey(window, GLFW_KEY_LEFT) == GLFW_PRESS) {
                    if (!leftKeyHeld) { leftKeyHeld = true; elenaSelectedSector = Math.max(0, elenaSelectedSector - 1); }
                } else { leftKeyHeld = false; }
                if (glfwGetKey(window, GLFW_KEY_RIGHT) == GLFW_PRESS) {
                    if (!rightKeyHeld) { rightKeyHeld = true; elenaSelectedSector = Math.min(323, elenaSelectedSector + 1); }
                } else { rightKeyHeld = false; }
                if (glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS) {
                    if (!spaceKeyPressed && elenaSelectedSector >= 0) {
                        spaceKeyPressed = true;
                        elenaScanTimer = 2f;
                        showMachineLog("SCANNING SECTOR " + elenaSelectedSector + "...");
                    }
                } else { spaceKeyPressed = false; }
            }

            // === INDICATOR PANEL MOUSE CONTROLS ===
            if (activeMachine.equals("INDICATOR_PANEL")) {
                float iPanelW = screenW * 0.88f;
                float iPanelH = screenH * 0.88f;
                float iPanelX = (screenW - iPanelW) / 2;
                float iPanelY = (screenH - iPanelH) / 2;
                float iContentY = iPanelY + iPanelH - 60;

                // Gauge layout: 3 columns x 2 rows
                float gaugeW = (iPanelW - 80) / 3;
                float gaugeH = (iPanelH - 200) / 2 - 20;
                float gaugeStartX = iPanelX + 20;
                float gaugeStartY = iContentY - 10;

                // Detect hover over gauge areas
                indicatorHoveredGauge = -1;
                for (int row = 0; row < 2; row++) {
                    for (int col = 0; col < 3; col++) {
                        int idx = row * 3 + col;
                        float gx = gaugeStartX + col * (gaugeW + 15);
                        float gy = gaugeStartY - row * (gaugeH + 15) - gaugeH;
                        if (machineMouseX >= gx && machineMouseX <= gx + gaugeW &&
                            machineMouseY >= gy && machineMouseY <= gy + gaugeH) {
                            indicatorHoveredGauge = idx;
                        }
                    }
                }

                // Left-click on gauge = CALIBRATE that instrument
                if (mouseLeftClicked && indicatorHoveredGauge >= 0) {
                    switch (indicatorHoveredGauge) {
                        case 0: indicatorDriftPower = 0f; break;
                        case 1: indicatorDriftTemp = 0f; break;
                        case 2: indicatorDriftPressure = 0f; break;
                        case 3: indicatorDriftFlux = 0f; break;
                        case 4: indicatorDriftXenon = 0f; break;
                        case 5: indicatorDriftCoolant = 0f; break;
                    }
                    indicatorCalibrationsDone++;
                    indicatorCheckBonus = Math.min(10f, indicatorCalibrationsDone * 0.5f + indicatorAlarmsAcked * 1.0f);
                    showMachineLog("INSTRUMENT CALIBRATED - DRIFT CORRECTED");
                }

                // Right-click = ACKNOWLEDGE alarms (any active alarm)
                if (mouseRightClicked) {
                    boolean anyAcked = false;
                    for (int i = 0; i < 6; i++) {
                        if (indicatorAlarmActive[i] && !indicatorAlarmAcknowledged[i]) {
                            indicatorAlarmAcknowledged[i] = true;
                            indicatorAlarmsAcked++;
                            anyAcked = true;
                        }
                    }
                    if (anyAcked) {
                        indicatorCheckBonus = Math.min(10f, indicatorCalibrationsDone * 0.5f + indicatorAlarmsAcked * 1.0f);
                        showMachineLog("ALARMS ACKNOWLEDGED - OPERATOR AWARE");
                    } else {
                        showMachineLog("NO ACTIVE UNACKNOWLEDGED ALARMS");
                    }
                }
            }

            if (activeMachine.equals("PUMP_CONSOLE")) {
                float panelW = screenW * 0.82f;
                float panelH = screenH * 0.8f;
                float panelX = (screenW - panelW) / 2f;
                float panelY = (screenH - panelH) / 2f;
                float leftW = panelW * 0.56f;
                float ctrlX = panelX + leftW + 15f;
                float ctrlW = panelW - leftW - 30f;
                float ctrlTop = panelY + panelH - 70f;
                float itemH = 54f;
                int optionCount = 5;

                for (int i = 0; i < optionCount; i++) {
                    float rowTop = ctrlTop - i * itemH;
                    float rowBot = rowTop - itemH + 6f;
                    if (machineMouseX >= ctrlX + 8f && machineMouseX <= ctrlX + ctrlW - 8f &&
                        machineMouseY >= rowBot && machineMouseY <= rowTop) {
                        pumpConsoleSelection = i;
                    }
                }

                pumpConsoleClickRepeat += dt;
                if ((leftNow || rightNow) && pumpConsoleClickRepeat > 0.12f) {
                    pumpConsoleClickRepeat = 0f;
                    int dir = rightNow ? 1 : -1;
                    adjustPumpConsoleSetting(pumpConsoleSelection, dir);
                }
                if (!leftNow && !rightNow) pumpConsoleClickRepeat = 0.2f;

                if (glfwGetKey(window, GLFW_KEY_UP) == GLFW_PRESS) {
                    if (!upKeyHeld) { upKeyHeld = true; pumpConsoleSelection = Math.max(0, pumpConsoleSelection - 1); }
                } else { upKeyHeld = false; }
                if (glfwGetKey(window, GLFW_KEY_DOWN) == GLFW_PRESS) {
                    if (!downKeyHeld) { downKeyHeld = true; pumpConsoleSelection = Math.min(optionCount - 1, pumpConsoleSelection + 1); }
                } else { downKeyHeld = false; }

                boolean leftPress = glfwGetKey(window, GLFW_KEY_LEFT) == GLFW_PRESS;
                boolean rightPress = glfwGetKey(window, GLFW_KEY_RIGHT) == GLFW_PRESS;
                controlRepeatTimer += dt;
                if ((leftPress || rightPress) && controlRepeatTimer > 0.1f) {
                    controlRepeatTimer = 0f;
                    int dir = rightPress ? 1 : -1;
                    adjustPumpConsoleSetting(pumpConsoleSelection, dir);
                }
                if (!leftPress && !rightPress) controlRepeatTimer = 0.25f;

                if (glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS) {
                    if (!spaceKeyPressed) {
                        spaceKeyPressed = true;
                        pumpConsoleSelection = 4;
                        adjustPumpConsoleSetting(4, 1);
                    }
                } else {
                    spaceKeyPressed = false;
                }
            }

            return; // Don't check proximity while UI is open
        }

        // Check proximity to machines
        nearbyMachine = "";
        float dxP = cameraPos.x - CONTROL_PANEL_X;
        float dzP = cameraPos.z - CONTROL_PANEL_Z;
        float distPanel = (float) Math.sqrt(dxP * dxP + dzP * dzP);

        float dxE = cameraPos.x - ELENA_X;
        float dzE = cameraPos.z - ELENA_Z;
        float distElena = (float) Math.sqrt(dxE * dxE + dzE * dzE);

        float dxI = cameraPos.x - INDICATOR_X;
        float dzI = cameraPos.z - INDICATOR_Z;
        float distIndicator = (float) Math.sqrt(dxI * dxI + dzI * dzI);

        float dxPump = cameraPos.x - PUMP_CONSOLE_X;
        float dzPump = cameraPos.z - PUMP_CONSOLE_Z;
        float distPumpConsole = (float) Math.sqrt(dxPump * dxPump + dzPump * dzPump);

        if (distPanel < MACHINE_INTERACT_DISTANCE) {
            nearbyMachine = "CONTROL_PANEL";
        } else if (distElena < MACHINE_INTERACT_DISTANCE) {
            nearbyMachine = "ELENA";
        } else if (distIndicator < MACHINE_INTERACT_DISTANCE * 1.5f) {
            nearbyMachine = "INDICATOR_PANEL";
        } else if (distPumpConsole < MACHINE_INTERACT_DISTANCE * 0.9f) {
            nearbyMachine = "PUMP_CONSOLE";
        }

        // Press E to open machine
        if (!nearbyMachine.isEmpty() && !dialogueActive) {
            if (glfwGetKey(window, GLFW_KEY_E) == GLFW_PRESS) {
                if (!xKeyPressed) {
                    xKeyPressed = true;
                    machineUIActive = true;
                    activeMachine = nearbyMachine;
                    if (nearbyMachine.equals("INDICATOR_PANEL")) {
                        playerReadIndicators = true;
                        indicatorCheckTimer = 0f; // Reset neglect timer
                        indicatorHoveredGauge = -1;
                        showMachineLog("INDICATOR PANELS OPENED - CHECK READINGS");
                    }
                    if (nearbyMachine.equals("PUMP_CONSOLE")) {
                        playerCheckedPumpConsole = true;
                        pumpConsoleSelection = 0;
                        pumpConsoleClickRepeat = 0.2f;
                        showMachineLog("PUMP TERMINAL ONLINE - MONITOR COOLING HEADER");
                    }
                    if (elenaSelectedSector < 0) elenaSelectedSector = 9 * 18 + 9; // center
                }
            } else {
                xKeyPressed = false;
            }
        } else {
            xKeyPressed = false;
        }
    }

    private void applyControlAdjustment(int control, int dir) {
        switch (control) {
            case 0: // Control rods (left/left-click = withdraw, right/right-click = insert)
                controlRodsInserted = Math.max(0, Math.min(211, controlRodsInserted + dir * 3));
                if (dir > 0 && storyPhase >= 1) playerReducedPower = true; // INSERT rods = reduce power
                if (dir < 0 && storyPhase >= 5) playerRaisedPower = true;
                if (dir < 0) showMachineLog("WITHDRAWING CONTROL RODS... " + controlRodsInserted + "/211");
                else showMachineLog("INSERTING CONTROL RODS... " + controlRodsInserted + "/211");
                break;
            case 1: // Coolant flow
                coolantFlow = Math.max(0, Math.min(100, coolantFlow + dir * 5));
                showMachineLog("COOLANT FLOW: " + String.format("%.0f", coolantFlow) + " PCT");
                break;
            case 2: // Power indicator (read-only but shows reading)
                if (reactorPower > 3000) showMachineLog("!!! POWER CRITICAL: " + String.format("%.0f", reactorPower) + " MW !!!");
                else if (reactorPower < 100) showMachineLog("WARNING: POWER DANGEROUSLY LOW: " + String.format("%.0f", reactorPower) + " MW");
                else showMachineLog("POWER READING: " + String.format("%.0f", reactorPower) + " MW - NOMINAL");
                break;
            case 3: // Temperature indicator (read-only but shows reading)
                if (reactorTemperature > 500) showMachineLog("!!! CORE TEMP CRITICAL: " + String.format("%.0f", reactorTemperature) + " C !!!");
                else if (reactorTemperature > 350) showMachineLog("CORE TEMP ELEVATED: " + String.format("%.0f", reactorTemperature) + " C");
                else showMachineLog("CORE TEMP: " + String.format("%.0f", reactorTemperature) + " C - NOMINAL");
                break;
            case 4: // Pumps toggle
                if (dir > 0 && !coolantPumpsOn) { coolantPumpsOn = true; showMachineLog("COOLANT PUMPS ACTIVATED"); }
                if (dir < 0 && coolantPumpsOn) { coolantPumpsOn = false; showMachineLog("WARNING: COOLANT PUMPS DISABLED!"); }
                break;
            case 5: // Turbine toggle
                if (dir < 0 && turbineConnected) {
                    turbineConnected = false;
                    playerDisconnectedTurbine = true;
                    showMachineLog("TURBINE DISCONNECTED FROM REACTOR");
                    if (storyPhase == 6) { playerStartedTest = true; }
                }
                if (dir > 0 && !turbineConnected) { turbineConnected = true; showMachineLog("TURBINE CONNECTED"); }
                break;
        }
    }

    private void adjustPumpConsoleSetting(int option, int dir) {
        playerCheckedPumpConsole = true;
        switch (option) {
            case 0:
                pumpConsoleTrimA = Math.max(-20f, Math.min(20f, pumpConsoleTrimA + dir * 1.5f));
                showMachineLog("PUMP A TRIM " + (dir > 0 ? "UP " : "DOWN ") + String.format("%+.1f%%", pumpConsoleTrimA));
                break;
            case 1:
                pumpConsoleTrimB = Math.max(-20f, Math.min(20f, pumpConsoleTrimB + dir * 1.5f));
                showMachineLog("PUMP B TRIM " + (dir > 0 ? "UP " : "DOWN ") + String.format("%+.1f%%", pumpConsoleTrimB));
                break;
            case 2:
                if (dir > 0 && !pumpConsoleCrossfeedOpen) {
                    pumpConsoleCrossfeedOpen = true;
                    showMachineLog("CROSSFLOW HEADER OPENED");
                } else if (dir < 0 && pumpConsoleCrossfeedOpen) {
                    pumpConsoleCrossfeedOpen = false;
                    showMachineLog("CROSSFLOW HEADER SEALED");
                }
                break;
            case 3:
                if (dir > 0 && !pumpConsoleEmergencyFlush) {
                    pumpConsoleEmergencyFlush = true;
                    showMachineLog("EMERGENCY FLUSH ARMED - WATCH TEMPERATURE");
                } else if (dir < 0 && pumpConsoleEmergencyFlush) {
                    pumpConsoleEmergencyFlush = false;
                    showMachineLog("EMERGENCY FLUSH SAFED");
                }
                break;
            case 4:
                if (dir > 0 && !pumpConsoleDiagnosticsActive) {
                    pumpConsoleDiagnosticsActive = true;
                    pumpConsoleDiagnosticsProgress = 0f;
                    showMachineLog("RUNNING FLOW DIAGNOSTIC...");
                } else if (dir < 0 && pumpConsoleDiagnosticsActive) {
                    pumpConsoleDiagnosticsActive = false;
                    pumpConsoleDiagnosticsProgress = 0f;
                    showMachineLog("FLOW DIAGNOSTIC ABORTED");
                }
                break;
        }
    }

    private void renderMachinePrompt(float screenW, float screenH) {
        if (!nearbyMachine.isEmpty() && !machineUIActive && !dialogueActive) {
            String machineName;
            if (nearbyMachine.equals("CONTROL_PANEL")) {
                machineName = "CONTROL PANEL";
            } else if (nearbyMachine.equals("ELENA")) {
                machineName = "ELENA DISPLAY";
            } else if (nearbyMachine.equals("PUMP_CONSOLE")) {
                machineName = "PUMP ANNEX TERMINAL";
            } else {
                machineName = "INDICATOR PANELS";
            }
            boolean readOnly = nearbyMachine.equals("INDICATOR_PANEL");
            String prompt = "PRESS E TO " + (readOnly ? "READ " : "OPEN ") + machineName;
            float promptW = prompt.length() * 10 + 30;
            float promptX = (screenW - promptW) / 2;
            drawHUDRect(promptX, 100, promptW, 35, 0f, 0f, 0f, 0.6f);
            drawHUDText(prompt, promptX + 15, 113, 2, 1f, 0.7f, 0.25f, 1f);
        }
    }

    private void renderControlPanelUI(float screenW, float screenH) {
        // Full-screen semi-transparent overlay
        drawHUDRect(0, 0, screenW, screenH, 0f, 0f, 0f, 0.45f);

        // Main panel frame
        float panelW = screenW * 0.88f;
        float panelH = screenH * 0.88f;
        float panelX = (screenW - panelW) / 2;
        float panelY = (screenH - panelH) / 2;

        // Dark panel background
        drawHUDRect(panelX, panelY, panelW, panelH, 0.08f, 0.05f, 0.02f, 0.97f);
        // Border
        drawHUDRect(panelX, panelY + panelH - 3, panelW, 3, 0.6f, 0.35f, 0.12f, 1f);
        drawHUDRect(panelX, panelY, panelW, 3, 0.6f, 0.35f, 0.12f, 1f);
        drawHUDRect(panelX, panelY, 3, panelH, 0.6f, 0.35f, 0.12f, 1f);
        drawHUDRect(panelX + panelW - 3, panelY, 3, panelH, 0.6f, 0.35f, 0.12f, 1f);

        // Title bar
        drawHUDRect(panelX + 3, panelY + panelH - 42, panelW - 6, 39, 0.15f, 0.08f, 0.03f, 1f);
        drawHUDText("RBMK-1000 REACTOR CONTROL PANEL - UNIT 4", panelX + 20, panelY + panelH - 30, 2, 1f, 0.7f, 0.2f, 1f);
        // Stability indicator on title bar
        String stabTxt = "STABILITY: " + String.format("%.0f", reactorStability) + " PCT";
        float stabR = reactorStability < 30 ? 1f : (reactorStability < 60 ? 1f : 0f);
        float stabG = reactorStability < 30 ? 0.2f : (reactorStability < 60 ? 0.8f : 1f);
        drawHUDText(stabTxt, panelX + panelW - stabTxt.length() * 10 - 30, panelY + panelH - 30, 2, stabR, stabG, 0f, 1f);

        float contentY = panelY + panelH - 60;

        // Available height for content
        float availH = contentY - panelY - 55;

        // === LEFT HALF: Gauges + Rod Visualization ===
        float leftW = panelW * 0.58f;
        float rx = panelX + 15;

        // ROW 1: Digital readouts
        float readoutW = (leftW - 50) / 4;
        float readoutH = availH * 0.19f;
        float rowGap = availH * 0.03f;

        renderDigitalGauge(rx, contentY - readoutH, readoutW, readoutH,
            "THERMAL POWER", String.format("%.0f", reactorPower), "MW",
            reactorPower > 3000 ? 1f : (reactorPower < 100 ? 1f : 0f),
            reactorPower > 3000 ? 0.2f : (reactorPower < 100 ? 0.8f : 1f),
            reactorPower > 3000 ? 0.2f : (reactorPower < 100 ? 0f : 0f));

        renderDigitalGauge(rx + readoutW + 10, contentY - readoutH, readoutW, readoutH,
            "CORE TEMP", String.format("%.0f", reactorTemperature), "C",
            reactorTemperature > 500 ? 1f : 0f,
            reactorTemperature > 500 ? 0.2f : 1f,
            reactorTemperature > 500 ? 0.2f : 0f);

        renderDigitalGauge(rx + (readoutW + 10) * 2, contentY - readoutH, readoutW, readoutH,
            "PRESSURE", String.format("%.0f", reactorPressure), "ATM",
            reactorPressure > 100 ? 1f : 0f,
            reactorPressure > 100 ? 0.2f : 1f,
            reactorPressure > 100 ? 0.2f : 0f);

        renderDigitalGauge(rx + (readoutW + 10) * 3, contentY - readoutH, readoutW, readoutH,
            "RODS IN CORE", String.valueOf(controlRodsInserted) + "/211", "",
            controlRodsInserted < 30 ? 1f : 0f,
            controlRodsInserted < 30 ? 0.2f : 1f,
            0f);

        // ROW 2: Bar gauges
        float row2Y = contentY - readoutH - rowGap;
        float barW = (leftW - 50) / 3;
        float barH = availH * 0.15f;

        renderBarGauge(rx, row2Y - barH, barW, barH,
            "COOLANT FLOW", coolantFlow, 100f, 0.3f, 0.6f, 1f);
        renderBarGauge(rx + barW + 10, row2Y - barH, barW, barH,
            "NEUTRON FLUX", Math.min(neutronFlux, 200f), 200f, 0.3f, 1f, 0.5f);
        renderBarGauge(rx + (barW + 10) * 2, row2Y - barH, barW, barH,
            "XENON-135", xenonLevel, 100f, 0.8f, 0.3f, 1f);

        // ROW 3: Control rod visualization
        float row3Y = row2Y - barH - rowGap;
        float rodVisH = availH * 0.22f;
        drawHUDRect(rx, row3Y - rodVisH, leftW - 30, rodVisH, 0.06f, 0.04f, 0.02f, 1f);
        drawHUDText("CONTROL ROD POSITIONS", rx + 10, row3Y - 12, 2, 0.7f, 0.5f, 0.2f, 1f);

        int maxBars = 35;
        float barSpacing = (leftW - 60) / maxBars;
        float maxBarH2 = rodVisH - 35;
        float insertionFraction = controlRodsInserted / 211f;
        for (int i = 0; i < maxBars; i++) {
            float bx = rx + 15 + i * barSpacing;
            float bh = maxBarH2 * insertionFraction;
            drawHUDRect(bx, row3Y - rodVisH + 8, barSpacing * 0.65f, maxBarH2, 0.1f, 0.1f, 0.12f, 1f);
            float fr = insertionFraction < 0.15f ? 1f : 0f;
            float fg = insertionFraction < 0.15f ? 0.2f : 0.7f;
            drawHUDRect(bx, row3Y - rodVisH + 8, barSpacing * 0.65f, bh, fr, fg, 0f, 0.9f);
        }

        // === RIGHT HALF: Interactive Controls Panel ===
        float ctrlX = panelX + leftW + 5;
        float ctrlW = panelW - leftW - 25;
        float ctrlY = contentY;

        drawHUDRect(ctrlX, panelY + 55, ctrlW, ctrlY - panelY - 55, 0.08f, 0.06f, 0.03f, 1f);
        drawHUDRect(ctrlX, ctrlY - 2, ctrlW, 2, 0.7f, 0.4f, 0.1f, 0.8f);
        drawHUDText("OPERATOR CONTROLS", ctrlX + 10, ctrlY - 18, 2, 1f, 0.65f, 0.2f, 1f);
        drawHUDText("HOVER TO SELECT", ctrlX + 10, ctrlY - 42, 2, 0.5f, 0.4f, 0.2f, 1f);
        drawHUDText("L-CLICK DECREASE  R-CLICK INCREASE", ctrlX + 10, ctrlY - 60, 2, 0.5f, 0.4f, 0.2f, 1f);

        // Control items list
        String[] controlNames = {
            "CONTROL RODS: " + controlRodsInserted + "/211",
            "COOLANT FLOW: " + String.format("%.0f", coolantFlow) + " PCT",
            "POWER OUTPUT (READ ONLY)",
            "TEMPERATURE (READ ONLY)",
            "PUMPS: " + (coolantPumpsOn ? "ON" : "OFF"),
            "TURBINE: " + (turbineConnected ? "CONNECTED" : "DISCONNECTED")
        };
        String[] controlHints = {
            "L-CLICK=WITHDRAW  R-CLICK=INSERT",
            "L-CLICK=DECREASE  R-CLICK=INCREASE",
            String.format("%.0f MW", reactorPower),
            String.format("%.0f C", reactorTemperature),
            "L-CLICK=OFF  R-CLICK=ON",
            "L-CLICK=DISCONNECT  R-CLICK=CONNECT"
        };

        float itemH = 56;
        float itemY = ctrlY - 70;
        for (int i = 0; i < controlNames.length; i++) {
            float iy = itemY - i * itemH;
            boolean selected = (i == selectedControl);
            // Check if mouse is hovering this item
            boolean hovered = (machineMouseX >= ctrlX + 5 && machineMouseX <= ctrlX + ctrlW - 10 &&
                machineMouseY >= iy - itemH + 4 && machineMouseY <= iy);
            // Background highlight for selected or hovered
            if (selected || hovered) {
                float pulse = (float)(Math.sin(System.currentTimeMillis() * 0.006) * 0.1 + 0.3);
                drawHUDRect(ctrlX + 5, iy - itemH + 4, ctrlW - 10, itemH - 4, 0f, pulse, 0f, selected ? 0.6f : 0.3f);
                drawHUDRect(ctrlX + 5, iy - itemH + 4, 4, itemH - 4, 0f, 1f, 0f, 1f); // Left accent bar
            }
            // Control name — read-only items get dimmer/cyan tint
            boolean readOnly = (i == 2 || i == 3);
            float nr, ng, nb;
            if (readOnly) {
                nr = selected ? 0.8f : (hovered ? 0.6f : 0.4f);
                ng = selected ? 0.45f : (hovered ? 0.35f : 0.25f);
                nb = selected ? 0.15f : (hovered ? 0.12f : 0.1f);
            } else {
                nr = selected ? 0f : (hovered ? 0.2f : 0.6f);
                ng = selected ? 1f : (hovered ? 0.9f : 0.8f);
                nb = selected ? 0.3f : (hovered ? 0.3f : 0.6f);
            }
            drawHUDText(controlNames[i], ctrlX + 16, iy - 6, 2, nr, ng, nb, 1f);
            // Hint text — read-only shows "CLICK TO READ" instead of adjustment hints
            String hint = readOnly && !selected ? "CLICK TO READ INSTRUMENT" : controlHints[i];
            drawHUDText(hint, ctrlX + 16, iy - 26, 2, readOnly ? 0.4f : 0.6f, readOnly ? 0.3f : 0.45f, readOnly ? 0.2f : 0.2f, selected ? 1f : 0.6f);
        }

        // Extra toggles section
        float togY = itemY - controlNames.length * itemH - 10;
        drawHUDRect(ctrlX + 5, togY - 55, ctrlW - 10, 2, 0.4f, 0.25f, 0.1f, 0.5f);

        // ECCS toggle with hover highlight
        float eccsTextY = togY - 18;
        boolean eccsHovered = (machineMouseX >= ctrlX + 5 && machineMouseX <= ctrlX + ctrlW - 10 &&
            machineMouseY >= eccsTextY - 5 && machineMouseY <= eccsTextY + 18);
        if (eccsHovered) {
            drawHUDRect(ctrlX + 5, eccsTextY - 5, ctrlW - 10, 23, 0.2f, 0.15f, 0.05f, 0.5f);
        }
        drawHUDText("CLICK: ECCS " + (emergencyCoolingOn ? "(ON)" : "(OFF)"), ctrlX + 16, eccsTextY, 2,
            emergencyCoolingOn ? 0.3f : 1f, emergencyCoolingOn ? 1f : 0.3f, 0.3f, 1f);
        drawHUDText("VOID COEFF: " + String.format("%.2f", voidCoefficient), ctrlX + 16, togY - 38, 2,
            voidCoefficient > 0.3f ? 1f : 0.6f, voidCoefficient > 0.3f ? 0.3f : 0.8f, 0.3f, 1f);

        // === Status messages panel (bottom-left of left section) ===
        float statusY = row3Y - rodVisH - rowGap;
        float statusH = statusY - panelY - 55;
        if (statusH > 30) {
            drawHUDRect(rx, panelY + 55, leftW - 30, statusH, 0.06f, 0.04f, 0.02f, 1f);
            drawHUDRect(rx, panelY + 55 + statusH - 2, leftW - 30, 2, 0.6f, 0.35f, 0.1f, 0.8f);
            drawHUDText("SYSTEM STATUS", rx + 10, panelY + 55 + statusH - 17, 2, 1f, 0.6f, 0.15f, 1f);

            String[] statusLines = getStatusMessages();
            for (int i = 0; i < statusLines.length && i < 5; i++) {
                boolean isWarning = statusLines[i].startsWith("!");
                String line = isWarning ? statusLines[i].substring(1) : statusLines[i];
                float sr = isWarning ? 1f : 0.6f;
                float sg = isWarning ? 0.3f : 0.8f;
                float sb = isWarning ? 0.3f : 0.6f;
                drawHUDText(line, rx + 10, panelY + 55 + statusH - 42 - i * 22, 2, sr, sg, sb, 1f);
            }
        }

        // === AZ-5 BUTTON (bottom-right) ===
        float az5W = 200;
        float az5H = 50;
        float az5X = panelX + panelW - az5W - 20;
        float az5Y = panelY + 8;

        // Check if mouse is hovering AZ-5 button
        boolean az5Hovered = (machineMouseX >= az5X && machineMouseX <= az5X + az5W &&
            machineMouseY >= az5Y && machineMouseY <= az5Y + az5H);

        if (storyPhase >= 7 && !az5Pressed) {
            float flash = (float)(Math.sin(System.currentTimeMillis() * 0.005) * 0.3 + 0.7);
            drawHUDRect(az5X - 3, az5Y - 3, az5W + 6, az5H + 6, 1f, 0f, 0f, flash * 0.6f);
            drawHUDRect(az5X, az5Y, az5W, az5H, az5Hovered ? 0.9f : 0.7f, 0f, 0f, 1f);
            drawHUDText("AZ-5 EMERGENCY", az5X + 15, az5Y + 28, 2, 1f, 1f, 1f, 1f);
            drawHUDText("CLICK TO ACTIVATE", az5X + 20, az5Y + 8, 2, 1f, 1f, 0.5f, flash);
        } else if (az5Pressed) {
            drawHUDRect(az5X, az5Y, az5W, az5H, 0.3f, 0f, 0f, 1f);
            drawHUDText("AZ-5 ACTIVATED", az5X + 20, az5Y + 18, 2, 1f, 0.3f, 0.3f, 1f);
        } else {
            drawHUDRect(az5X, az5Y, az5W, az5H, 0.15f, 0.15f, 0.15f, 1f);
            drawHUDText("AZ-5 (LOCKED)", az5X + 25, az5Y + 18, 2, 0.4f, 0.4f, 0.4f, 1f);
        }

        // Machine log message (above AZ-5 to avoid overlap)
        if (machineLogTimer > 0 && !machineLogMessage.isEmpty()) {
            float logW = machineLogMessage.length() * 10 + 30;
            float logX = panelX + (panelW - logW) / 2;
            float logAlpha = Math.min(1f, machineLogTimer);
            drawHUDRect(logX, panelY + 62, logW, 28, 0.15f, 0.08f, 0f, logAlpha * 0.85f);
            drawHUDText(machineLogMessage, logX + 15, panelY + 70, 2, 1f, 0.7f, 0.2f, logAlpha);
        }

        // Close instruction
        drawHUDText("ESC TO CLOSE", panelX + 20, panelY + 8, 2, 0.5f, 0.4f, 0.2f, 1f);
    }

    private void renderDigitalGauge(float x, float y, float w, float h,
                                     String label, String value, String unit,
                                     float vr, float vg, float vb) {
        // Gauge background
        drawHUDRect(x, y, w, h, 0.06f, 0.04f, 0.02f, 1f);
        drawHUDRect(x, y + h - 2, w, 2, 0.4f, 0.28f, 0.1f, 0.8f);
        drawHUDRect(x, y, w, 2, 0.4f, 0.28f, 0.1f, 0.8f);

        // Label
        drawHUDText(label, x + 8, y + h - 22, 2, 0.6f, 0.45f, 0.2f, 1f);

        // Value (large)
        drawHUDText(value, x + 10, y + 35, 3, vr, vg, vb, 1f);

        // Unit
        if (!unit.isEmpty()) {
            drawHUDText(unit, x + 10, y + 10, 2, 0.5f, 0.4f, 0.2f, 1f);
        }
    }

    private void renderBarGauge(float x, float y, float w, float h,
                                 String label, float value, float maxVal,
                                 float br, float bg, float bb) {
        drawHUDRect(x, y, w, h, 0.06f, 0.04f, 0.02f, 1f);
        drawHUDText(label, x + 8, y + h - 20, 2, 0.6f, 0.45f, 0.2f, 1f);

        // Bar background
        float barX = x + 10;
        float barY = y + 10;
        float barW = w - 20;
        float barH = 25;
        drawHUDRect(barX, barY, barW, barH, 0.12f, 0.08f, 0.04f, 1f);

        // Bar fill
        float fillW = barW * Math.min(value / maxVal, 1f);
        drawHUDRect(barX, barY, fillW, barH, br, bg, bb, 0.85f);

        // Percentage text
        String pct = String.format("%.0f", value);
        drawHUDText(pct, barX + barW + 5 - pct.length() * 5, y + h - 35, 2, 0.8f, 0.8f, 0.7f, 1f);
    }

    private String[] getStatusMessages() {
        if (storyPhase >= 9) {
            return new String[]{"!!! REACTOR DESTROYED !!!", "!ALL READINGS OFFLINE", "!CONTAINMENT BREACH", "!RADIATION LEVELS EXTREME"};
        }
        if (storyPhase >= 8) {
            return new String[]{"!!! EMERGENCY !!!", "!POWER SURGE: " + String.format("%.0f", reactorPower) + " MW", "!CORE TEMPERATURE CRITICAL", "!AZ-5 SCRAM FAILED", "!POSITIVE VOID COEFFICIENT"};
        }
        // Dynamic messages based on actual reactor state
        java.util.List<String> msgs = new java.util.ArrayList<>();
        if (reactorPower > 3000) msgs.add("!POWER EXCEEDS SAFE LIMIT!");
        else if (reactorPower < 100 && storyPhase >= 4) msgs.add("!POWER CRITICALLY LOW: " + String.format("%.0f", reactorPower) + " MW");
        else msgs.add("POWER: " + String.format("%.0f", reactorPower) + " MW");

        if (controlRodsInserted < 15) msgs.add("!DANGER: ONLY " + controlRodsInserted + " RODS IN CORE!");
        else if (controlRodsInserted < 30) msgs.add("!WARNING: " + controlRodsInserted + " RODS (MIN 30)");
        else msgs.add("RODS: " + controlRodsInserted + "/211 INSERTED");

        if (xenonLevel > 60) msgs.add("!XENON-135 POISONING CRITICAL");
        else if (xenonLevel > 30) msgs.add("XENON BUILDUP: " + String.format("%.0f", xenonLevel) + " PCT");

        if (!coolantPumpsOn) msgs.add("!COOLANT PUMPS DISABLED!");
        else if (coolantFlow < 50) msgs.add("!LOW COOLANT FLOW: " + String.format("%.0f", coolantFlow) + " PCT");

        if (!turbineConnected) msgs.add("TURBINE DISCONNECTED");
        if (!emergencyCoolingOn) msgs.add("!ECCS DISABLED");
        if (voidCoefficient > 0.3f) msgs.add("!POSITIVE VOID COEFFICIENT!");

        if (reactorStability > 80 && msgs.stream().noneMatch(s -> s.startsWith("!")))
            msgs.add(0, "ALL SYSTEMS NOMINAL");

        return msgs.toArray(new String[0]);
    }

    private void renderIndicatorPanelUI(float screenW, float screenH) {
        // Full-screen semi-transparent overlay
        drawHUDRect(0, 0, screenW, screenH, 0f, 0f, 0f, 0.45f);

        // Main panel frame
        float panelW = screenW * 0.90f;
        float panelH = screenH * 0.90f;
        float panelX = (screenW - panelW) / 2;
        float panelY = (screenH - panelH) / 2;

        // Dark panel background
        drawHUDRect(panelX, panelY, panelW, panelH, 0.06f, 0.08f, 0.12f, 0.97f);
        // Border (blue-tinted for instrument panel)
        drawHUDRect(panelX, panelY + panelH - 3, panelW, 3, 0.2f, 0.4f, 0.7f, 1f);
        drawHUDRect(panelX, panelY, panelW, 3, 0.2f, 0.4f, 0.7f, 1f);
        drawHUDRect(panelX, panelY, 3, panelH, 0.2f, 0.4f, 0.7f, 1f);
        drawHUDRect(panelX + panelW - 3, panelY, 3, panelH, 0.2f, 0.4f, 0.7f, 1f);

        // Title bar
        drawHUDRect(panelX + 3, panelY + panelH - 42, panelW - 6, 39, 0.08f, 0.12f, 0.2f, 1f);
        drawHUDText("RBMK-1000 INDEPENDENT INSTRUMENT PANEL - UNIT 4", panelX + 20, panelY + panelH - 30, 2, 0.4f, 0.7f, 1f, 1f);

        // Alarm status summary on title bar
        int activeAlarms = 0;
        int unackedAlarms = 0;
        for (int i = 0; i < 6; i++) {
            if (indicatorAlarmActive[i]) { activeAlarms++; if (!indicatorAlarmAcknowledged[i]) unackedAlarms++; }
        }
        String alarmSummary = unackedAlarms > 0 ? "!! " + unackedAlarms + " UNACKED ALARMS !!" :
                              activeAlarms > 0 ? activeAlarms + " ALARMS (ACKED)" : "ALL PARAMETERS NOMINAL";
        float aR = unackedAlarms > 0 ? 1f : (activeAlarms > 0 ? 1f : 0.3f);
        float aG = unackedAlarms > 0 ? 0.2f : (activeAlarms > 0 ? 0.8f : 1f);
        float aB = unackedAlarms > 0 ? 0.2f : (activeAlarms > 0 ? 0f : 0.3f);
        if (unackedAlarms > 0) {
            float flash = (float)(Math.sin(System.currentTimeMillis() * 0.008) * 0.3 + 0.7);
            aR *= flash; aG *= flash;
        }
        drawHUDText(alarmSummary, panelX + panelW - alarmSummary.length() * 10 - 25, panelY + panelH - 30, 2, aR, aG, aB, 1f);

        float contentY = panelY + panelH - 55;

        // === INSTRUMENT GAUGES: 3 columns x 2 rows ===
        float gaugeW = (panelW - 80) / 3;
        float gaugeH = (panelH - 200) / 2 - 20;
        float gStartX = panelX + 20;
        float gStartY = contentY - 10;

        // Gauge data
        String[] gaugeNames = {"THERMAL POWER", "CORE TEMPERATURE", "PRESSURE", "NEUTRON FLUX", "XENON-135 LEVEL", "COOLANT FLOW"};
        String[] gaugeUnits = {"MW", "C", "ATM", "%", "%", "%"};
        float[] trueValues = {reactorPower, reactorTemperature, reactorPressure, neutronFlux, xenonLevel, coolantFlow};
        float[] driftValues = {indicatorDriftPower, indicatorDriftTemp, indicatorDriftPressure, indicatorDriftFlux, indicatorDriftXenon, indicatorDriftCoolant};
        float[] maxValues = {4000f, 800f, 150f, 250f, 100f, 100f};
        // Safe ranges: min, max (for bar coloring)
        float[][] safeRanges = {{100f, 2500f}, {150f, 400f}, {20f, 90f}, {5f, 140f}, {0f, 60f}, {60f, 100f}};

        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 3; col++) {
                int idx = row * 3 + col;
                float gx = gStartX + col * (gaugeW + 15);
                float gy = gStartY - row * (gaugeH + 15) - gaugeH;

                boolean hovered = (indicatorHoveredGauge == idx);
                boolean hasAlarm = indicatorAlarmActive[idx];
                boolean alarmAcked = indicatorAlarmAcknowledged[idx];

                // Gauge background
                float bgR = 0.07f, bgG = 0.09f, bgB = 0.14f;
                if (hovered) { bgR = 0.1f; bgG = 0.14f; bgB = 0.2f; }
                if (hasAlarm && !alarmAcked) {
                    float flash = (float)(Math.sin(System.currentTimeMillis() * 0.006 + idx) * 0.15 + 0.15);
                    bgR += flash; bgG += flash * 0.2f;
                }
                drawHUDRect(gx, gy, gaugeW, gaugeH, bgR, bgG, bgB, 1f);
                // Border
                float bR = hasAlarm ? (alarmAcked ? 1f : 1f) : 0.2f;
                float bG = hasAlarm ? (alarmAcked ? 0.6f : 0.15f) : 0.35f;
                float bB = hasAlarm ? 0f : 0.6f;
                drawHUDRect(gx, gy + gaugeH - 2, gaugeW, 2, bR, bG, bB, 1f);
                drawHUDRect(gx, gy, gaugeW, 2, bR, bG, bB, 0.6f);

                // Alarm indicator light (top-right corner)
                if (hasAlarm) {
                    float lightFlash = alarmAcked ? 0.6f : (float)(Math.sin(System.currentTimeMillis() * 0.01 + idx) * 0.5 + 0.5);
                    drawHUDRect(gx + gaugeW - 22, gy + gaugeH - 25, 16, 16,
                        alarmAcked ? 1f : 1f, alarmAcked ? 0.6f : 0.1f, 0f, lightFlash);
                } else {
                    drawHUDRect(gx + gaugeW - 22, gy + gaugeH - 25, 16, 16, 0.1f, 0.4f, 0.1f, 0.7f);
                }

                // Gauge label
                drawHUDText(gaugeNames[idx], gx + 10, gy + gaugeH - 20, 2, 0.5f, 0.65f, 0.9f, 1f);

                // Displayed value (with drift applied)
                float displayedValue = Math.max(0f, trueValues[idx] + driftValues[idx]);
                String valueStr = String.format("%.1f", displayedValue);
                // Value color based on safety range
                boolean inDanger = displayedValue < safeRanges[idx][0] || displayedValue > safeRanges[idx][1];
                // Special case: for coolant, below is danger, for xenon below is fine
                float vR = inDanger ? 1f : 0.2f;
                float vG = inDanger ? 0.3f : 0.9f;
                float vB = inDanger ? 0.2f : 0.4f;
                drawHUDText(valueStr, gx + 12, gy + gaugeH - 50, 3, vR, vG, vB, 1f);
                drawHUDText(gaugeUnits[idx], gx + 12 + valueStr.length() * 15 + 5, gy + gaugeH - 45, 2, 0.4f, 0.55f, 0.7f, 1f);

                // Drift indicator (how far off from true value)
                float driftPct = trueValues[idx] > 0.1f ? Math.abs(driftValues[idx]) / trueValues[idx] * 100f : Math.abs(driftValues[idx]);
                String driftStr = String.format("DRIFT: %.1f%%", driftPct);
                float dR = driftPct > 10f ? 1f : (driftPct > 5f ? 1f : 0.3f);
                float dG = driftPct > 10f ? 0.3f : (driftPct > 5f ? 0.7f : 0.7f);
                float dB = driftPct > 10f ? 0.3f : (driftPct > 5f ? 0f : 0.3f);
                drawHUDText(driftStr, gx + gaugeW - driftStr.length() * 10 - 8, gy + gaugeH - 50, 2, dR, dG, dB, 0.9f);

                // === Trend arrow ===
                float trend = computeParamTrend(idx);
                String trendStr;
                float tR, tG, tB;
                if (trend > 5f) { trendStr = "^^ RISING FAST"; tR = 1f; tG = 0.3f; tB = 0.3f; }
                else if (trend > 1f) { trendStr = "^ RISING"; tR = 1f; tG = 0.7f; tB = 0.2f; }
                else if (trend < -5f) { trendStr = "vv FALLING FAST"; tR = 0.3f; tG = 0.5f; tB = 1f; }
                else if (trend < -1f) { trendStr = "v FALLING"; tR = 0.4f; tG = 0.7f; tB = 1f; }
                else { trendStr = "-- STABLE"; tR = 0.4f; tG = 0.8f; tB = 0.4f; }
                // Special: for coolant, falling is bad, rising is good
                if (idx == 5 && trend < -1f) { tR = 1f; tG = 0.3f; tB = 0.3f; }
                if (idx == 5 && trend > 1f) { tR = 0.3f; tG = 0.8f; tB = 0.3f; }
                drawHUDText(trendStr, gx + 12, gy + gaugeH - 75, 2, tR, tG, tB, 1f);

                // === Bar gauge ===
                float barX = gx + 10;
                float barY = gy + 12;
                float barW = gaugeW - 20;
                float barH = 22;
                drawHUDRect(barX, barY, barW, barH, 0.1f, 0.12f, 0.18f, 1f);
                // Safe zone marking (green band)
                float safeStartPct = safeRanges[idx][0] / maxValues[idx];
                float safeEndPct = safeRanges[idx][1] / maxValues[idx];
                drawHUDRect(barX + barW * safeStartPct, barY, barW * (safeEndPct - safeStartPct), barH, 0.05f, 0.15f, 0.05f, 0.5f);
                // Current value marker
                float valPct = Math.min(1f, Math.max(0f, displayedValue / maxValues[idx]));
                float markerX = barX + barW * valPct;
                drawHUDRect(markerX - 2, barY - 2, 5, barH + 4, vR, vG, vB, 1f);

                // Hover instruction
                if (hovered) {
                    drawHUDRect(gx + 5, gy + gaugeH - 95, gaugeW - 10, 18, 0.15f, 0.2f, 0.3f, 0.8f);
                    drawHUDText("CLICK TO CALIBRATE", gx + 12, gy + gaugeH - 92, 2, 0.6f, 0.9f, 1f, 1f);
                }
            }
        }

        // === BOTTOM STATUS BAR ===
        float statusBarY = panelY + 8;
        float statusBarH = 55;
        drawHUDRect(panelX + 3, statusBarY, panelW - 6, statusBarH, 0.05f, 0.07f, 0.12f, 1f);
        drawHUDRect(panelX + 3, statusBarY + statusBarH - 1, panelW - 6, 1, 0.2f, 0.3f, 0.5f, 0.6f);

        // Left: Instrument accuracy
        float totalDrift = Math.abs(indicatorDriftPower / Math.max(1f, reactorPower)) +
                           Math.abs(indicatorDriftTemp / Math.max(1f, reactorTemperature)) +
                           Math.abs(indicatorDriftPressure / Math.max(1f, reactorPressure)) +
                           Math.abs(indicatorDriftFlux / Math.max(1f, neutronFlux)) +
                           Math.abs(indicatorDriftXenon / Math.max(1f, xenonLevel + 1f)) +
                           Math.abs(indicatorDriftCoolant / Math.max(1f, coolantFlow + 1f));
        float accuracy = Math.max(0f, 100f - totalDrift * 100f / 6f);
        String accStr = "INSTRUMENT ACCURACY: " + String.format("%.0f", accuracy) + "%";
        float accR = accuracy < 70f ? 1f : (accuracy < 85f ? 1f : 0.3f);
        float accG = accuracy < 70f ? 0.3f : (accuracy < 85f ? 0.8f : 0.9f);
        drawHUDText(accStr, panelX + 20, statusBarY + 32, 2, accR, accG, 0.2f, 1f);

        // Middle: Calibrations & acknowledged alarms count
        String calStr = "CALIBRATIONS: " + indicatorCalibrationsDone + "  ALARMS ACKED: " + indicatorAlarmsAcked;
        drawHUDText(calStr, panelX + 20, statusBarY + 12, 2, 0.4f, 0.6f, 0.8f, 0.9f);

        // Right: Stability bonus/penalty
        String bonusStr = "STABILITY EFFECT: ";
        float netEffect = indicatorCheckBonus - indicatorNeglectPenalty;
        if (netEffect >= 0) bonusStr += "+" + String.format("%.1f", netEffect) + "%";
        else bonusStr += String.format("%.1f", netEffect) + "%";
        float bR = netEffect < 0 ? 1f : 0.2f;
        float bG2 = netEffect < 0 ? 0.3f : 0.9f;
        drawHUDText(bonusStr, panelX + panelW - bonusStr.length() * 10 - 25, statusBarY + 32, 2, bR, bG2, 0.3f, 1f);

        // Next check reminder
        float timeToNeglect = Math.max(0f, 60f - indicatorCheckTimer);
        String checkStr = timeToNeglect > 0 ? "NEXT CHECK IN: " + String.format("%.0f", timeToNeglect) + "s" : "!! OVERDUE - CHECK INSTRUMENTS !!";
        float cR = timeToNeglect > 20f ? 0.3f : (timeToNeglect > 0f ? 1f : 1f);
        float cG = timeToNeglect > 20f ? 0.7f : (timeToNeglect > 0f ? 0.7f : 0.2f);
        drawHUDText(checkStr, panelX + panelW - checkStr.length() * 10 - 25, statusBarY + 12, 2, cR, cG, 0.2f, 0.9f);

        // === INSTRUCTIONS ===
        drawHUDText("LEFT-CLICK GAUGE: CALIBRATE INSTRUMENT  |  RIGHT-CLICK: ACKNOWLEDGE ALARMS  |  ESC: CLOSE", panelX + 20, panelY - 18, 2, 0.4f, 0.5f, 0.7f, 0.7f);

        // Machine log message
        if (machineLogTimer > 0 && !machineLogMessage.isEmpty()) {
            float logW = machineLogMessage.length() * 10 + 30;
            float logX = panelX + (panelW - logW) / 2;
            float logAlpha = Math.min(1f, machineLogTimer);
            drawHUDRect(logX, statusBarY + statusBarH + 5, logW, 28, 0.1f, 0.15f, 0.25f, logAlpha * 0.9f);
            drawHUDText(machineLogMessage, logX + 15, statusBarY + statusBarH + 11, 2, 0.4f, 0.75f, 1f, logAlpha);
        }
    }

    // Compute parameter trend from history (returns rate of change per sample)
    private float computeParamTrend(int paramIdx) {
        float[] history;
        float currentVal;
        switch (paramIdx) {
            case 0: history = powerHistory; currentVal = reactorPower; break;
            case 1: history = tempHistory; currentVal = reactorTemperature; break;
            case 2: history = pressureHistory; currentVal = reactorPressure; break;
            default: return 0f; // flux, xenon, coolant don't have separate history arrays
        }
        // Compare current value to average of last few recordings
        float sum = 0f;
        int count = 0;
        for (int i = 0; i < history.length; i++) {
            if (history[i] > 0.01f) { sum += history[i]; count++; }
        }
        if (count < 3) return 0f; // Not enough data yet
        float avg = sum / count;
        if (avg < 0.01f) return 0f;
        return ((currentVal - avg) / avg) * 100f; // % change from average
    }

    private void renderElenaDisplayUI(float screenW, float screenH) {
        // Full-screen semi-transparent overlay
        drawHUDRect(0, 0, screenW, screenH, 0f, 0f, 0f, 0.45f);

        // Circular ELENA panel (rectangular approximation)
        float panelW = screenW * 0.80f;
        float panelH = screenH * 0.85f;
        float panelX = (screenW - panelW) / 2;
        float panelY = (screenH - panelH) / 2;

        // Beige/cream ELENA background
        drawHUDRect(panelX, panelY, panelW, panelH, 0.15f, 0.1f, 0.05f, 0.97f);
        // Amber border (warm industrial style)
        drawHUDRect(panelX, panelY + panelH - 3, panelW, 3, 0.6f, 0.35f, 0.1f, 1f);
        drawHUDRect(panelX, panelY, panelW, 3, 0.6f, 0.35f, 0.1f, 1f);
        drawHUDRect(panelX, panelY, 3, panelH, 0.6f, 0.35f, 0.1f, 1f);
        drawHUDRect(panelX + panelW - 3, panelY, 3, panelH, 0.6f, 0.35f, 0.1f, 1f);

        // Title
        drawHUDRect(panelX + 3, panelY + panelH - 50, panelW - 6, 47, 0.12f, 0.08f, 0.03f, 1f);
        drawHUDText("ELENA - REACTOR CORE MAP - UNIT 4", panelX + 20, panelY + panelH - 30, 2, 1f, 0.7f, 0.2f, 1f);
        drawHUDText("CLICK CELL TO SELECT AND SCAN", panelX + panelW - 310, panelY + panelH - 30, 2, 0.7f, 0.5f, 0.2f, 1f);

        // Circular reactor core visualization
        float coreR = Math.min(panelW, panelH) * 0.33f;
        float coreCX = panelX + panelW / 2;
        float coreCY = panelY + panelH / 2 + 5;

        // Draw core circle background
        int gridSize = 18;
        float cellSize = coreR * 2 / gridSize;
        int selRow = elenaSelectedSector / gridSize;
        int selCol = elenaSelectedSector % gridSize;

        for (int gy = 0; gy < gridSize; gy++) {
            for (int gx = 0; gx < gridSize; gx++) {
                float cx = coreCX - coreR + gx * cellSize;
                float cy = coreCY - coreR + gy * cellSize;
                float dx = (gx - gridSize / 2f + 0.5f) / (gridSize / 2f);
                float dy = (gy - gridSize / 2f + 0.5f) / (gridSize / 2f);
                float dist = (float) Math.sqrt(dx * dx + dy * dy);

                if (dist <= 1.0f) {
                    float r, g, b;
                    // Dynamic coloring based on reactor state
                    float localPower = reactorPower / 1600f;
                    float localStress = (1f - reactorStability / 100f);
                    Random rand = new Random(gx * 19 + gy * 37);
                    float variation = rand.nextFloat();
                    boolean sectorScanned = elenaScannedSectors[gy * gridSize + gx];
                    boolean isHotSpot = sectorScanned && isSectorXenonHotSpot(gy, gx);
                    boolean isVoid = sectorScanned && isSectorVoidAnomaly(gy, gx);

                    if (storyPhase >= 8) {
                        float flash = (float)(Math.sin(System.currentTimeMillis() * 0.01 + gx * 0.5 + gy * 0.3) * 0.5 + 0.5);
                        r = 1f; g = flash * 0.3f; b = 0f;
                    } else if (isVoid) {
                        // Void sectors pulse magenta/purple
                        float pulse = (float)(Math.sin(System.currentTimeMillis() * 0.006 + gx) * 0.3 + 0.7);
                        r = 0.8f * pulse; g = 0.1f; b = 0.9f * pulse;
                    } else if (isHotSpot) {
                        // Xenon hot spots glow orange/red
                        float pulse = (float)(Math.sin(System.currentTimeMillis() * 0.005 + gy) * 0.2 + 0.8);
                        r = 1f * pulse; g = 0.4f * pulse; b = 0f;
                    } else if (reactorStability < 30) {
                        // Unstable - red/yellow
                        if (variation < 0.4f) { r = 1f; g = 0.2f; b = 0f; }
                        else if (variation < 0.7f) { r = 1f; g = 0.8f; b = 0f; }
                        else { r = 0f; g = 0.7f; b = 0f; }
                    } else if (reactorStability < 60) {
                        // Warning - some yellow
                        if (variation < localStress * 0.6f) { r = 1f; g = 0.8f; b = 0f; }
                        else { r = 0f; g = 0.6f + variation * 0.2f; b = 0f; }
                    } else {
                        // Normal - green with variation
                        r = 0f; g = 0.5f + localPower * 0.3f + variation * 0.15f; b = 0f;
                    }

                    // Scanning animation over selected sector
                    if (elenaScanTimer > 0 && gy == selRow && gx == selCol) {
                        float scanFlash = (float)(Math.sin(System.currentTimeMillis() * 0.02) * 0.5 + 0.5);
                        r = Math.min(1f, r + scanFlash * 0.5f);
                        g = Math.min(1f, g + scanFlash * 0.5f);
                        b = Math.min(1f, b + scanFlash * 0.8f);
                    }

                    drawHUDRect(cx + 1, cy + 1, cellSize - 2, cellSize - 2, r, g, b, 0.9f);

                    // Scanned sector marker (small cyan dot in corner)
                    if (sectorScanned && storyPhase < 8) {
                        drawHUDRect(cx + cellSize - 5, cy + cellSize - 5, 3, 3, 0.3f, 0.9f, 1f, 0.8f);
                    }

                    // Draw selection cursor
                    if (gy == selRow && gx == selCol) {
                        float selPulse = (float)(Math.sin(System.currentTimeMillis() * 0.008) * 0.3 + 0.7);
                        // White border around selected cell
                        drawHUDRect(cx - 1, cy - 1, cellSize + 2, 2, 1f, 1f, 1f, selPulse);
                        drawHUDRect(cx - 1, cy + cellSize - 1, cellSize + 2, 2, 1f, 1f, 1f, selPulse);
                        drawHUDRect(cx - 1, cy - 1, 2, cellSize + 2, 1f, 1f, 1f, selPulse);
                        drawHUDRect(cx + cellSize - 1, cy - 1, 2, cellSize + 2, 1f, 1f, 1f, selPulse);
                    }
                }
            }
        }

        // Core circle border
        for (int a = 0; a < 60; a++) {
            float angle = (float)(a * Math.PI * 2 / 60);
            float bx = coreCX + (float)Math.cos(angle) * coreR - 3;
            float by = coreCY + (float)Math.sin(angle) * coreR - 3;
            drawHUDRect(bx, by, 6, 6, 0.5f, 0.5f, 0.4f, 1f);
        }

        // === Sector info panel (right side) ===
        float infoX = panelX + panelW - 230;
        float infoW = 210;
        float infoH = 280;
        float infoTopY = coreCY + coreR - 20;
        drawHUDRect(infoX, infoTopY - infoH, infoW, infoH, 0.08f, 0.06f, 0.03f, 0.9f);
        drawHUDRect(infoX, infoTopY, infoW, 2, 0.6f, 0.35f, 0.1f, 0.8f);
        drawHUDText("SECTOR INFO", infoX + 10, infoTopY - 16, 2, 1f, 0.65f, 0.2f, 1f);
        drawHUDText("ROW: " + selRow + "  COL: " + selCol, infoX + 10, infoTopY - 34, 2, 0.8f, 0.6f, 0.3f, 1f);

        // Calculate sector specific values with some variation
        Random sectorRand = new Random(selRow * 19 + selCol * 37 + (int)(reactorPower));
        float sectorPower = reactorPower * (0.85f + sectorRand.nextFloat() * 0.3f) / gridSize;
        float sectorTemp = reactorTemperature * (0.9f + sectorRand.nextFloat() * 0.2f);
        float sectorFlux = neutronFlux * (0.8f + sectorRand.nextFloat() * 0.4f);

        // Compute sector xenon & void for display
        Random scanInfoRand = new Random(selRow * 19 + selCol * 37 + (int)(reactorPower * 10));
        float displayXenon = xenonLevel * (0.6f + scanInfoRand.nextFloat() * 0.8f);
        float displayVoidRaw = voidCoefficient * (0.5f + scanInfoRand.nextFloat());
        boolean selScanned = (elenaSelectedSector >= 0 && elenaSelectedSector < 324) && elenaScannedSectors[elenaSelectedSector];
        boolean selHotSpot = selScanned && displayXenon > 40f;
        boolean selVoid = selScanned && displayVoidRaw > 0.3f && storyPhase >= 5;

        drawHUDText("POWER: " + String.format("%.1f", sectorPower) + " MW", infoX + 10, infoTopY - 55, 2, 0.9f, 0.6f, 0.2f, 1f);
        drawHUDText("TEMP: " + String.format("%.0f", sectorTemp) + " C", infoX + 10, infoTopY - 75, 2, sectorTemp > 500 ? 1f : 0f, sectorTemp > 500 ? 0.3f : 0.8f, 0f, 1f);
        drawHUDText("FLUX: " + String.format("%.1f", sectorFlux), infoX + 10, infoTopY - 95, 2, 0.9f, 0.6f, 0.2f, 1f);

        // Xenon level per sector (only visible after scanning)
        if (selScanned) {
            float xenonR = displayXenon > 40f ? 1f : 0.8f;
            float xenonG = displayXenon > 40f ? 0.4f : 0.7f;
            drawHUDText("XENON: " + String.format("%.0f", displayXenon) + "%", infoX + 10, infoTopY - 115, 2, xenonR, xenonG, 0.1f, 1f);
            if (selHotSpot) {
                drawHUDText("!! HOT SPOT !!", infoX + 10, infoTopY - 133, 2, 1f, 0.3f, 0f, 1f);
            }
            if (selVoid) {
                drawHUDText("!! VOID ANOMALY !!", infoX + 10, infoTopY - 151, 2, 0.8f, 0.1f, 0.9f, 1f);
            }
        } else {
            drawHUDText("XENON: [NOT SCANNED]", infoX + 10, infoTopY - 115, 2, 0.5f, 0.4f, 0.2f, 1f);
        }

        // Scan result
        if (elenaScanTimer > 0) {
            drawHUDText("SCANNING...", infoX + 10, infoTopY - 175, 2, 1f, 0.7f, 0.2f, 1f);
        } else if (selScanned) {
            String scanResult;
            if (selVoid) scanResult = "VOID DANGER!";
            else if (selHotSpot) scanResult = "XENON WARNING";
            else if (reactorStability > 70) scanResult = "SECTOR NOMINAL";
            else if (reactorStability > 40) scanResult = "ELEVATED";
            else scanResult = "ANOMALOUS!";
            boolean danger = selVoid || selHotSpot || reactorStability < 50;
            drawHUDText(scanResult, infoX + 10, infoTopY - 175, 2, danger ? 1f : 0f, danger ? 0.3f : 1f, danger && selVoid ? 0.9f : 0f, 1f);
        }
        drawHUDText("L-CLICK: SCAN", infoX + 10, infoTopY - 200, 2, 0.5f, 0.38f, 0.15f, 1f);
        drawHUDText("R-CLICK: SCAN SEL", infoX + 10, infoTopY - 220, 2, 0.5f, 0.38f, 0.15f, 1f);

        // === Scan progress panel (left side) ===
        float spX = panelX + 15;
        float spW = 190;
        float spH = 160;
        float spTopY = coreCY + coreR - 20;
        drawHUDRect(spX, spTopY - spH, spW, spH, 0.08f, 0.06f, 0.03f, 0.9f);
        drawHUDRect(spX, spTopY, spW, 2, 0.6f, 0.35f, 0.1f, 0.8f);
        drawHUDText("SCAN PROGRESS", spX + 10, spTopY - 16, 2, 1f, 0.65f, 0.2f, 1f);
        drawHUDText("SCANNED: " + elenaUniqueScanCount + "/324", spX + 10, spTopY - 38, 2, 0.8f, 0.8f, 0.6f, 1f);
        drawHUDText("HOT SPOTS: " + elenaHotSpotsFound, spX + 10, spTopY - 58, 2,
            elenaHotSpotsFound > 0 ? 1f : 0.6f, elenaHotSpotsFound > 0 ? 0.4f : 0.6f, 0.1f, 1f);
        drawHUDText("VOID WARNS: " + elenaVoidSectorsFound, spX + 10, spTopY - 78, 2,
            elenaVoidSectorsFound > 0 ? 0.8f : 0.6f, 0.1f, elenaVoidSectorsFound > 0 ? 0.9f : 0.6f, 1f);
        // Stability bonus indicator
        String bonusStr = String.format("+%.1f%%", elenaScanStabilityBonus);
        drawHUDText("STAB BONUS: " + bonusStr, spX + 10, spTopY - 98, 2, 0.3f, 1f, 0.5f, 1f);
        // Neglect penalty
        if (elenaScanNeglectPenalty > 0) {
            String penStr = String.format("-%.0f%%", elenaScanNeglectPenalty);
            drawHUDText("NEGLECT: " + penStr, spX + 10, spTopY - 118, 2, 1f, 0.3f, 0.2f, 1f);
        } else {
            drawHUDText("NEGLECT: NONE", spX + 10, spTopY - 118, 2, 0.4f, 0.7f, 0.3f, 1f);
        }
        // Legend
        drawHUDRect(spX + 10, spTopY - 145, 8, 8, 0.3f, 0.9f, 1f, 0.8f);
        drawHUDText("= SCANNED", spX + 22, spTopY - 143, 2, 0.5f, 0.5f, 0.4f, 1f);

        // Readouts below core
        float infoY2 = panelY + 65;
        float col1X = panelX + 30;
        float col2X = panelX + panelW / 2 + 20;

        drawHUDText("POWER: " + String.format("%.0f", reactorPower) + " MW",
            col1X, infoY2 + 30, 2, 1f, 0.7f, 0.2f, 1f);
        drawHUDText("RODS: " + controlRodsInserted + "/211",
            col1X, infoY2 + 10, 2, controlRodsInserted < 30 ? 1f : 0f,
            controlRodsInserted < 30 ? 0.3f : 1f, 0f, 1f);
        drawHUDText("NEUTRON FLUX: " + String.format("%.0f", neutronFlux) + " PCT",
            col2X, infoY2 + 30, 2, 1f, 0.7f, 0.2f, 1f);
        drawHUDText("COOLANT: " + String.format("%.0f", coolantFlow) + " PCT",
            col2X, infoY2 + 10, 2, coolantFlow < 50 ? 1f : 0f,
            coolantFlow < 50 ? 0.5f : 1f, 0f, 1f);

        // Status indicator
        String coreStatus;
        float csR, csG, csB;
        if (storyPhase >= 9) { coreStatus = "DESTROYED"; csR = 1f; csG = 0f; csB = 0f; }
        else if (storyPhase >= 8) { coreStatus = "CRITICAL SURGE"; csR = 1f; csG = 0.2f; csB = 0f; }
        else if (reactorStability < 30) { coreStatus = "DANGER - UNSTABLE"; csR = 1f; csG = 0.2f; csB = 0f; }
        else if (reactorStability < 60) { coreStatus = "CAUTION - ABNORMAL"; csR = 1f; csG = 0.8f; csB = 0f; }
        else { coreStatus = "NOMINAL"; csR = 0f; csG = 1f; csB = 0f; }

        float statusW2 = coreStatus.length() * 10 + 20;
        drawHUDRect(panelX + panelW / 2 - statusW2 / 2, infoY2 - 15, statusW2, 25,
            csR * 0.2f, csG * 0.2f, csB * 0.2f, 0.8f);
        drawHUDText(coreStatus, panelX + panelW / 2 - statusW2 / 2 + 10, infoY2 - 7, 2, csR, csG, csB, 1f);

        // Machine log message (between close text and status to avoid overlap)
        if (machineLogTimer > 0 && !machineLogMessage.isEmpty()) {
            float logW = machineLogMessage.length() * 10 + 30;
            float logX = panelX + (panelW - logW) / 2;
            float logAlpha = Math.min(1f, machineLogTimer);
            drawHUDRect(logX, panelY + 20, logW, 24, 0.15f, 0.08f, 0f, logAlpha * 0.85f);
            drawHUDText(machineLogMessage, logX + 15, panelY + 27, 2, 1f, 0.7f, 0.2f, logAlpha);
        }

        // Close instruction
        drawHUDText("ESC TO CLOSE", panelX + 20, panelY + 8, 2, 0.5f, 0.4f, 0.2f, 1f);
    }

    private void createBlockTextures() {
        // Generate Minecraft-style procedural textures
        blockTextures.put("stone", generateStoneTexture());
        blockTextures.put("cobblestone", generateCobblestoneTexture());
        blockTextures.put("dirt", generateDirtTexture());
        blockTextures.put("oak_planks", generateWoodPlanksTexture(0x9C7C5C, 0x6B5433));
        blockTextures.put("dark_planks", generateWoodPlanksTexture(0x4A3728, 0x2D2118));
        blockTextures.put("bricks", generateBricksTexture());
        blockTextures.put("iron_block", generateIronBlockTexture());
        blockTextures.put("diamond_block", generateDiamondBlockTexture());
        blockTextures.put("coal_block", generateCoalBlockTexture());
        blockTextures.put("white_concrete", generateConcreteTexture(0xE0D5C8));
        blockTextures.put("gray_concrete", generateConcreteTexture(0x555555));
        blockTextures.put("light_gray_concrete", generateConcreteTexture(0x9D8D7D));
        blockTextures.put("cyan_concrete", generateConcreteTexture(0x8B7B3B));
        blockTextures.put("blue_concrete", generateConcreteTexture(0x7B5A2A));
        blockTextures.put("light_blue_concrete", generateConcreteTexture(0xB87A40));
        blockTextures.put("green_concrete", generateConcreteTexture(0x2D5A27));
        blockTextures.put("glass", generateGlassTexture());
        blockTextures.put("glowstone", generateGlowstoneTexture());
        blockTextures.put("redstone_block", generateRedstoneBlockTexture());
        blockTextures.put("obsidian", generateObsidianTexture());
        
        // Chernobyl Control Room specific textures
        blockTextures.put("slate_tile_dark", generateSlateTileTexture(0x2A2A2A));
        blockTextures.put("slate_tile_light", generateSlateTileTexture(0x3D3D3D));
        blockTextures.put("soviet_gray_wall", generateSovietWallTexture(0x6B5A45));
        blockTextures.put("soviet_blue_gray", generateSovietWallTexture(0x5A4A38));
        blockTextures.put("wood_panel_brown", generateWoodPanelTexture(0x5C3D2E));
        blockTextures.put("control_panel_cream", generateControlPanelTexture(0xD8C0A0));
        blockTextures.put("control_panel_gray", generateControlPanelTexture(0x8A8A8A));
        blockTextures.put("control_panel_dark", generateControlPanelTexture(0x3A3A3A));
        blockTextures.put("button_panel_green", generateButtonPanelTexture(0x2D5A2D, 0x1A3D1A));
        blockTextures.put("button_panel_red", generateButtonPanelTexture(0x8B2020, 0x5C1515));
        blockTextures.put("elena_display", generateElenaDisplayTexture());
        blockTextures.put("elena_cell_green", generateElenaCellTexture(0x30AA30, "OK"));
        blockTextures.put("elena_cell_yellow", generateElenaCellTexture(0xDDAA20, "!!"));
        blockTextures.put("elena_cell_red", generateElenaCellTexture(0xCC3030, "XX"));
        blockTextures.put("digital_display_power", generateDigitalDisplayTexture("3200", "MW"));
        blockTextures.put("digital_display_temp", generateDigitalDisplayTexture("286", "C"));
        blockTextures.put("digital_display_pressure", generateDigitalDisplayTexture("68.4", "ATM"));
        blockTextures.put("digital_display_rods", generateDigitalDisplayTexture("211", "RODS"));
        blockTextures.put("indicator_panel", generateIndicatorPanelTexture());
        blockTextures.put("switch_panel", generateSwitchPanelTexture());
        blockTextures.put("ceiling_tile", generateCeilingTileTexture());
        blockTextures.put("fluorescent_light", generateFluorescentLightTexture());
        blockTextures.put("monitor_screen", generateMonitorTexture());
        blockTextures.put("cable_tray", generateConcreteTexture(0x2A2218));
        blockTextures.put("window_frame", generateConcreteTexture(0x4A3A28));
        blockTextures.put("hazard_stripe", generateHazardStripeTexture());
        blockTextures.put("warning_label", generateWarningLabelTexture());
        
        // Reactor hall textures
        blockTextures.put("reactor_floor_metal", generateReactorFloorTexture());
        blockTextures.put("fuel_cap_gray", generateFuelCapTexture(0x6A6A6A));
        blockTextures.put("fuel_cap_yellow", generateFuelCapTexture(0xD4AA00));
        blockTextures.put("fuel_cap_red", generateFuelCapTexture(0xAA3030));
        blockTextures.put("fuel_cap_blue", generateFuelCapTexture(0x3050AA));
        blockTextures.put("reactor_wall_white", generateConcreteTexture(0xD0C8B8));
        blockTextures.put("reactor_step_metal", generateReactorStepTexture());
        
        // NPC Engineer textures
        blockTextures.put("akimov_head", generateAkimovHeadAtlas());
        blockTextures.put("akimov_body", generateAkimovBodyTexture());
        blockTextures.put("akimov_legs", generateAkimovLegsTexture());
        blockTextures.put("toptunov_head", generateToptunvHeadAtlas());
        blockTextures.put("toptunov_body", generateEngineerBodyTexture(0xD8D8D0)); // Slightly gray lab coat
        blockTextures.put("toptunov_legs", generateEngineerLegsTexture(0x1E1E1E)); // Dark pants
        blockTextures.put("engineer_arms", generateAkimovArmsTexture()); // Akimov's arms
        blockTextures.put("toptunov_arms", generateEngineerArmsTexture(0xCC8844)); // Toptunov's skin arms

        // Dyatlov NPC textures
        blockTextures.put("dyatlov_head", generateDyatlovHeadAtlas());
        blockTextures.put("dyatlov_body", generateEngineerBodyTexture(0x2A2A30)); // Dark suit jacket
        blockTextures.put("dyatlov_legs", generateEngineerLegsTexture(0x222228)); // Dark suit pants
        blockTextures.put("dyatlov_arms", generateEngineerArmsTexture(0xDEB896)); // Dyatlov's skin arms

        // Player textures (distinct blue jumpsuit + yellow hard hat)
        blockTextures.put("player_head", generatePlayerHeadAtlas());
        blockTextures.put("player_body", generatePlayerBodyTexture());
        blockTextures.put("player_legs", generatePlayerLegsTexture());
        blockTextures.put("player_arms", generatePlayerArmsTexture());
        
        // Name label textures
        blockTextures.put("akimov_label", generateNameRoleLabelTexture("A. AKIMOV", "SHIFT SUPERVISOR"));
        blockTextures.put("toptunov_label", generateNameRoleLabelTexture("L. TOPTUNOV", "SR. REACTOR ENGINEER"));
        blockTextures.put("dyatlov_label", generateNameRoleLabelTexture("A. DYATLOV", "DEPUTY CHIEF ENGINEER"));
        blockTextures.put("laptev_label", generateNameRoleLabelTexture("S. LAPTEV", "MAINT. CREW"));
        blockTextures.put("kudryavtsev_label", generateNameRoleLabelTexture("B. KUDRYAVTSEV", "PUMP TECHNICIAN"));

        // Perevozchenko NPC textures (reactor hall worker - orange hard hat + coveralls)
        blockTextures.put("perevozchenko_head", generatePerevozchHeadAtlas());
        blockTextures.put("perevozchenko_body", generateEngineerBodyTexture(0x3A5A3A)); // Dark green coveralls
        blockTextures.put("perevozchenko_legs", generateEngineerLegsTexture(0x2A3A2A)); // Dark green pants
        blockTextures.put("perevozchenko_arms", generateEngineerArmsTexture(0xCC8855)); // Skin tone arms
        blockTextures.put("perevozchenko_label", generateNameRoleLabelTexture("V. PEREVOZCHENKO", "REACTOR SECTION FOREMAN"));

        // Maintenance locker crew NPC textures
        blockTextures.put("laptev_head", generateEngineerHeadTexture(0xD2A070, true));
        blockTextures.put("laptev_body", generateEngineerBodyTexture(0x334A66)); // Navy coveralls
        blockTextures.put("laptev_legs", generateEngineerLegsTexture(0x1E2F42));
        blockTextures.put("laptev_arms", generateEngineerArmsTexture(0xD2A070));

        // Pump technician NPC textures
        blockTextures.put("kudryavtsev_head", generateEngineerHeadTexture(0xC78E63, false));
        blockTextures.put("kudryavtsev_body", generateEngineerBodyTexture(0x5A3E2A)); // Brown work jacket
        blockTextures.put("kudryavtsev_legs", generateEngineerLegsTexture(0x2E1F18));
        blockTextures.put("kudryavtsev_arms", generateEngineerArmsTexture(0xC78E63));
    }
    
    // Chernobyl-specific texture generators
    private int generateSlateTileTexture(int baseColor) {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        Random rand = new Random(baseColor + 8888);
        
        int baseR = (baseColor >> 16) & 0xFF;
        int baseG = (baseColor >> 8) & 0xFF;
        int baseB = baseColor & 0xFF;
        
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                int variation = rand.nextInt(20) - 10;
                int r = Math.max(0, Math.min(255, baseR + variation));
                int g = Math.max(0, Math.min(255, baseG + variation));
                int b = Math.max(0, Math.min(255, baseB + variation));
                
                // Tile grout lines
                boolean isGrout = (x == 0 || x == 15 || y == 0 || y == 15);
                if (isGrout) {
                    r = Math.max(0, r - 30);
                    g = Math.max(0, g - 30);
                    b = Math.max(0, b - 30);
                }
                
                // Slight marble effect
                if (rand.nextFloat() < 0.08f) {
                    r = Math.min(255, r + 15);
                    g = Math.min(255, g + 15);
                    b = Math.min(255, b + 15);
                }
                
                pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return createTextureFromPixels(pixels);
    }
    
    private int generateSovietWallTexture(int baseColor) {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        Random rand = new Random(baseColor + 9999);
        
        int baseR = (baseColor >> 16) & 0xFF;
        int baseG = (baseColor >> 8) & 0xFF;
        int baseB = baseColor & 0xFF;
        
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                int variation = rand.nextInt(12) - 6;
                int r = Math.max(0, Math.min(255, baseR + variation));
                int g = Math.max(0, Math.min(255, baseG + variation));
                int b = Math.max(0, Math.min(255, baseB + variation));
                
                // Subtle paint texture
                if (rand.nextFloat() < 0.05f) {
                    r = Math.max(0, r - 10);
                    g = Math.max(0, g - 10);
                    b = Math.max(0, b - 10);
                }
                
                pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return createTextureFromPixels(pixels);
    }
    
    private int generateWoodPanelTexture(int baseColor) {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        Random rand = new Random(baseColor + 1234);
        
        int baseR = (baseColor >> 16) & 0xFF;
        int baseG = (baseColor >> 8) & 0xFF;
        int baseB = baseColor & 0xFF;
        
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                int variation = rand.nextInt(25) - 12;
                int r = Math.max(0, Math.min(255, baseR + variation));
                int g = Math.max(0, Math.min(255, baseG + variation));
                int b = Math.max(0, Math.min(255, baseB + variation));
                
                // Vertical wood grain
                if ((x + rand.nextInt(3)) % 4 == 0) {
                    r = Math.max(0, r - 15);
                    g = Math.max(0, g - 12);
                    b = Math.max(0, b - 8);
                }
                
                // Panel edge
                if (x == 0 || x == 15) {
                    r = Math.max(0, r - 25);
                    g = Math.max(0, g - 20);
                    b = Math.max(0, b - 15);
                }
                
                pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return createTextureFromPixels(pixels);
    }

    private int generateHazardStripeTexture() {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                boolean stripe = ((x + y) / 2) % 2 == 0;
                int r = stripe ? 235 : 20;
                int g = stripe ? 200 : 20;
                int b = stripe ? 40 : 20;
                if (stripe) {
                    r = Math.min(255, r + (int)(Math.sin((x + y) * 0.7f) * 10));
                    g = Math.min(255, g + (int)(Math.cos((x - y) * 0.5f) * 8));
                }
                pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return createTextureFromPixels(pixels);
    }

    private int generateWarningLabelTexture() {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                boolean border = (x <= 1 || x >= 14 || y <= 1 || y >= 14);
                int r = border ? 250 : 180;
                int g = border ? 210 : 30;
                int b = border ? 60 : 30;
                // White exclamation mark in center
                if (x >= 7 && x <= 8 && y >= 4 && y <= 11) {
                    r = 255;
                    g = 255;
                    b = 255;
                }
                if (x >= 7 && x <= 8 && y >= 12 && y <= 13) {
                    r = 255;
                    g = 255;
                    b = 255;
                }
                pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return createTextureFromPixels(pixels);
    }
    
    private int generateControlPanelTexture(int baseColor) {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        Random rand = new Random(baseColor + 5678);
        
        int baseR = (baseColor >> 16) & 0xFF;
        int baseG = (baseColor >> 8) & 0xFF;
        int baseB = baseColor & 0xFF;
        
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                int variation = rand.nextInt(10) - 5;
                int r = Math.max(0, Math.min(255, baseR + variation));
                int g = Math.max(0, Math.min(255, baseG + variation));
                int b = Math.max(0, Math.min(255, baseB + variation));
                
                // Panel border
                boolean isBorder = (x <= 1 || x >= 14 || y <= 1 || y >= 14);
                if (isBorder) {
                    r = Math.max(0, r - 20);
                    g = Math.max(0, g - 20);
                    b = Math.max(0, b - 20);
                }
                
                pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return createTextureFromPixels(pixels);
    }
    
    private int generateButtonPanelTexture(int buttonColor, int bgColor) {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        Random rand = new Random(buttonColor + bgColor);
        
        // Background
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                int br = (bgColor >> 16) & 0xFF;
                int bg = (bgColor >> 8) & 0xFF;
                int bb = bgColor & 0xFF;
                int variation = rand.nextInt(10) - 5;
                pixels[y * TEXTURE_SIZE + x] = (255 << 24) | 
                    (Math.max(0, Math.min(255, br + variation)) << 16) | 
                    (Math.max(0, Math.min(255, bg + variation)) << 8) | 
                    Math.max(0, Math.min(255, bb + variation));
            }
        }
        
        // Draw buttons in grid
        for (int by = 0; by < 4; by++) {
            for (int bx = 0; bx < 4; bx++) {
                int cx = 2 + bx * 4;
                int cy = 2 + by * 4;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int px = cx + dx;
                        int py = cy + dy;
                        if (px >= 0 && px < 16 && py >= 0 && py < 16) {
                            int br = (buttonColor >> 16) & 0xFF;
                            int bg = (buttonColor >> 8) & 0xFF;
                            int bb = buttonColor & 0xFF;
                            // Highlight
                            if (dx == -1 || dy == -1) {
                                br = Math.min(255, br + 40);
                                bg = Math.min(255, bg + 40);
                                bb = Math.min(255, bb + 40);
                            }
                            pixels[py * TEXTURE_SIZE + px] = (255 << 24) | (br << 16) | (bg << 8) | bb;
                        }
                    }
                }
            }
        }
        return createTextureFromPixels(pixels);
    }
    
    private int generateNameLabelTexture(String name) {
        return generateNameRoleLabelTexture(name, "");
    }

    private int generateNameRoleLabelTexture(String name, String role) {
        int texW = 256, texH = 64;
        int scale = 2; // Each font pixel becomes 2x2
        int[] pixels = new int[texW * texH];
        // Semi-transparent dark background for readability
        int bg = (200 << 24) | (15 << 16) | (15 << 8) | 20;
        for (int i = 0; i < pixels.length; i++) pixels[i] = bg;

        // Draw a thin border for visibility
        int border = (255 << 24) | (100 << 16) | (100 << 8) | 100;
        for (int x = 0; x < texW; x++) {
            pixels[x] = border;
            pixels[(texH - 1) * texW + x] = border;
        }
        for (int y = 0; y < texH; y++) {
            pixels[y * texW] = border;
            pixels[y * texW + texW - 1] = border;
        }

        int white = (255 << 24) | (255 << 16) | (255 << 8) | 255;
        int yellow = (255 << 24) | (255 << 16) | (220 << 8) | 50;

        int charW = 4 * scale; // 8px per char
        int charH = 6 * scale; // 12px per char
        int spacing = 2;

        // Draw name centered on top row
        int nameWidth = name.length() * (charW + spacing) - spacing;
        int nameStartX = (texW - nameWidth) / 2;
        for (int i = 0; i < name.length(); i++) {
            drawScaledCharOnLabel(pixels, texW, texH, name.charAt(i), nameStartX + i * (charW + spacing), 6, white, scale);
        }

        // Draw role centered on bottom row
        if (role != null && !role.isEmpty()) {
            int roleWidth = role.length() * (charW + spacing) - spacing;
            int roleStartX = (texW - roleWidth) / 2;
            for (int i = 0; i < role.length(); i++) {
                drawScaledCharOnLabel(pixels, texW, texH, role.charAt(i), roleStartX + i * (charW + spacing), 6 + charH + 6, yellow, scale);
            }
        }

        // Flip vertically for OpenGL (UV origin is bottom-left)
        int[] flipped = new int[texW * texH];
        for (int row = 0; row < texH; row++) {
            System.arraycopy(pixels, row * texW, flipped, (texH - 1 - row) * texW, texW);
        }

        return createTextureFromPixelsRaw(flipped, texW, texH);
    }

    private void drawScaledCharOnLabel(int[] pixels, int texWidth, int texHeight, char c, int x, int y, int color, int scale) {
        int[][] font = getCharFont(c);
        for (int dy = 0; dy < 6; dy++) {
            for (int dx = 0; dx < 4; dx++) {
                if (font[dy][dx] == 1) {
                    // Draw a scale x scale block for each font pixel
                    for (int sy = 0; sy < scale; sy++) {
                        for (int sx = 0; sx < scale; sx++) {
                            int px = x + dx * scale + sx;
                            int py = y + dy * scale + sy;
                            if (px >= 0 && px < texWidth && py >= 0 && py < texHeight)
                                pixels[py * texWidth + px] = color;
                        }
                    }
                }
            }
        }
    }

    // Draw a single ASCII char (A-Z, space, needed letters)
    private void drawCharOnPixels(int[] pixels, char c, int x, int y, int color) {
        int[][] font = getCharFont(c);
        for (int dy = 0; dy < 6; dy++) {
            for (int dx = 0; dx < 4; dx++) {
                if (font[dy][dx] == 1) {
                    int px = x + dx;
                    int py = y + dy;
                    if (px >= 0 && px < TEXTURE_SIZE && py >= 0 && py < TEXTURE_SIZE)
                        pixels[py * TEXTURE_SIZE + px] = color;
                }
            }
        }
    }

    // Returns a 4x6 font bitmap for a given char
    private int[][] getCharFont(char c) {
        switch (c) {
            case 'A': return new int[][]{{0,1,1,0},{1,0,0,1},{1,1,1,1},{1,0,0,1},{1,0,0,1},{1,0,0,1}};
            case 'E': return new int[][]{{1,1,1,1},{1,0,0,0},{1,1,1,0},{1,0,0,0},{1,0,0,0},{1,1,1,1}};
            case 'G': return new int[][]{{0,1,1,1},{1,0,0,0},{1,0,1,1},{1,0,0,1},{1,0,0,1},{0,1,1,1}};
            case 'I': return new int[][]{{1,1,1,1},{0,1,1,0},{0,1,1,0},{0,1,1,0},{0,1,1,0},{1,1,1,1}};
            case 'K': return new int[][]{{1,0,0,1},{1,0,1,0},{1,1,0,0},{1,0,1,0},{1,0,0,1},{1,0,0,1}};
            case 'M': return new int[][]{{1,0,0,1},{1,1,1,1},{1,0,0,1},{1,0,0,1},{1,0,0,1},{1,0,0,1}};
            case 'N': return new int[][]{{1,0,0,1},{1,1,0,1},{1,0,1,1},{1,0,0,1},{1,0,0,1},{1,0,0,1}};
            case 'O': return new int[][]{{0,1,1,0},{1,0,0,1},{1,0,0,1},{1,0,0,1},{1,0,0,1},{0,1,1,0}};
            case 'P': return new int[][]{{1,1,1,0},{1,0,0,1},{1,1,1,0},{1,0,0,0},{1,0,0,0},{1,0,0,0}};
            case 'R': return new int[][]{{1,1,1,0},{1,0,0,1},{1,1,1,0},{1,0,1,0},{1,0,0,1},{1,0,0,1}};
            case 'T': return new int[][]{{1,1,1,1},{0,1,1,0},{0,1,1,0},{0,1,1,0},{0,1,1,0},{0,1,1,0}};
            case 'U': return new int[][]{{1,0,0,1},{1,0,0,1},{1,0,0,1},{1,0,0,1},{1,0,0,1},{0,1,1,0}};
            case 'B': return new int[][]{{1,1,1,0},{1,0,0,1},{1,1,1,0},{1,0,0,1},{1,0,0,1},{1,1,1,0}};
            case 'C': return new int[][]{{0,1,1,1},{1,0,0,0},{1,0,0,0},{1,0,0,0},{1,0,0,0},{0,1,1,1}};
            case 'D': return new int[][]{{1,1,1,0},{1,0,0,1},{1,0,0,1},{1,0,0,1},{1,0,0,1},{1,1,1,0}};
            case 'F': return new int[][]{{1,1,1,1},{1,0,0,0},{1,1,1,0},{1,0,0,0},{1,0,0,0},{1,0,0,0}};
            case 'H': return new int[][]{{1,0,0,1},{1,0,0,1},{1,1,1,1},{1,0,0,1},{1,0,0,1},{1,0,0,1}};
            case 'J': return new int[][]{{0,0,0,1},{0,0,0,1},{0,0,0,1},{0,0,0,1},{1,0,0,1},{0,1,1,0}};
            case 'L': return new int[][]{{1,0,0,0},{1,0,0,0},{1,0,0,0},{1,0,0,0},{1,0,0,0},{1,1,1,1}};
            case 'S': return new int[][]{{0,1,1,1},{1,0,0,0},{0,1,1,0},{0,0,0,1},{0,0,0,1},{1,1,1,0}};
            case 'V': return new int[][]{{1,0,0,1},{1,0,0,1},{1,0,0,1},{1,0,0,1},{0,1,1,0},{0,1,1,0}};
            case 'W': return new int[][]{{1,0,0,1},{1,0,0,1},{1,0,0,1},{1,0,0,1},{1,1,1,1},{1,0,0,1}};
            case 'X': return new int[][]{{1,0,0,1},{1,0,0,1},{0,1,1,0},{0,1,1,0},{1,0,0,1},{1,0,0,1}};
            case 'Y': return new int[][]{{1,0,0,1},{1,0,0,1},{0,1,1,0},{0,1,1,0},{0,1,1,0},{0,1,1,0}};
            case 'Z': return new int[][]{{1,1,1,1},{0,0,1,0},{0,1,0,0},{0,1,0,0},{1,0,0,0},{1,1,1,1}};
            case '0': return new int[][]{{0,1,1,0},{1,0,0,1},{1,0,1,1},{1,1,0,1},{1,0,0,1},{0,1,1,0}};
            case '1': return new int[][]{{0,1,0,0},{1,1,0,0},{0,1,0,0},{0,1,0,0},{0,1,0,0},{1,1,1,0}};
            case '2': return new int[][]{{0,1,1,0},{1,0,0,1},{0,0,1,0},{0,1,0,0},{1,0,0,0},{1,1,1,1}};
            case '3': return new int[][]{{1,1,1,0},{0,0,0,1},{0,1,1,0},{0,0,0,1},{0,0,0,1},{1,1,1,0}};
            case '4': return new int[][]{{1,0,0,1},{1,0,0,1},{1,1,1,1},{0,0,0,1},{0,0,0,1},{0,0,0,1}};
            case '5': return new int[][]{{1,1,1,1},{1,0,0,0},{1,1,1,0},{0,0,0,1},{0,0,0,1},{1,1,1,0}};
            case '6': return new int[][]{{0,1,1,0},{1,0,0,0},{1,1,1,0},{1,0,0,1},{1,0,0,1},{0,1,1,0}};
            case '7': return new int[][]{{1,1,1,1},{0,0,0,1},{0,0,1,0},{0,1,0,0},{0,1,0,0},{0,1,0,0}};
            case '8': return new int[][]{{0,1,1,0},{1,0,0,1},{0,1,1,0},{1,0,0,1},{1,0,0,1},{0,1,1,0}};
            case '9': return new int[][]{{0,1,1,0},{1,0,0,1},{0,1,1,1},{0,0,0,1},{0,0,0,1},{0,1,1,0}};
            case ':': return new int[][]{{0,0,0,0},{0,1,1,0},{0,0,0,0},{0,0,0,0},{0,1,1,0},{0,0,0,0}};
            case '!': return new int[][]{{0,1,1,0},{0,1,1,0},{0,1,1,0},{0,1,1,0},{0,0,0,0},{0,1,1,0}};
            case '?': return new int[][]{{0,1,1,0},{1,0,0,1},{0,0,1,0},{0,1,0,0},{0,0,0,0},{0,1,0,0}};
            case ',': return new int[][]{{0,0,0,0},{0,0,0,0},{0,0,0,0},{0,0,0,0},{0,1,0,0},{1,0,0,0}};
            case '\'': return new int[][]{{0,1,0,0},{0,1,0,0},{0,0,0,0},{0,0,0,0},{0,0,0,0},{0,0,0,0}};
            case '"': return new int[][]{{1,0,1,0},{1,0,1,0},{0,0,0,0},{0,0,0,0},{0,0,0,0},{0,0,0,0}};
            case '(': return new int[][]{{0,0,1,0},{0,1,0,0},{0,1,0,0},{0,1,0,0},{0,1,0,0},{0,0,1,0}};
            case ')': return new int[][]{{0,1,0,0},{0,0,1,0},{0,0,1,0},{0,0,1,0},{0,0,1,0},{0,1,0,0}};
            case '/': return new int[][]{{0,0,0,1},{0,0,1,0},{0,0,1,0},{0,1,0,0},{0,1,0,0},{1,0,0,0}};
            case '.': return new int[][]{{0,0,0,0},{0,0,0,0},{0,0,0,0},{0,0,0,0},{0,1,1,0},{0,1,1,0}};
            case '-': return new int[][]{{0,0,0,0},{0,0,0,0},{1,1,1,1},{0,0,0,0},{0,0,0,0},{0,0,0,0}};
            case ' ': return new int[][]{{0,0,0,0},{0,0,0,0},{0,0,0,0},{0,0,0,0},{0,0,0,0},{0,0,0,0}};
            default:  return new int[][]{{1,0,1,0},{0,1,0,1},{1,0,1,0},{0,1,0,1},{1,0,1,0},{0,1,0,1}};
        }
    }
    
    private int generateElenaDisplayTexture() {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        Random rand = new Random(1111);
        
        // Beige/cream background
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                int r = 200 + rand.nextInt(20) - 10;
                int g = 195 + rand.nextInt(20) - 10;
                int b = 170 + rand.nextInt(20) - 10;
                pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (r << 16) | (g << 8) | b;
            }
        }
        
        // Create circular pattern with colored indicator lights
        int cx = 8, cy = 8;
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                float dist = (float) Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy));
                if (dist <= 7 && dist > 1) {
                    // Inside the circle - create grid of indicator lights
                    if ((x + y) % 2 == 0 && rand.nextFloat() < 0.7f) {
                        // Random colored lights (green, red, yellow)
                        float lightRand = rand.nextFloat();
                        if (lightRand < 0.5f) {
                            pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (50 << 16) | (180 << 8) | 50; // Green
                        } else if (lightRand < 0.75f) {
                            pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (200 << 16) | (50 << 8) | 50; // Red  
                        } else {
                            pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (220 << 16) | (200 << 8) | 50; // Yellow
                        }
                    }
                }
            }
        }
        
        // Border
        for (int i = 0; i < TEXTURE_SIZE; i++) {
            pixels[i] = (255 << 24) | (80 << 16) | (80 << 8) | 80;
            pixels[(TEXTURE_SIZE - 1) * TEXTURE_SIZE + i] = (255 << 24) | (80 << 16) | (80 << 8) | 80;
            pixels[i * TEXTURE_SIZE] = (255 << 24) | (80 << 16) | (80 << 8) | 80;
            pixels[i * TEXTURE_SIZE + TEXTURE_SIZE - 1] = (255 << 24) | (80 << 16) | (80 << 8) | 80;
        }
        
        return createTextureFromPixels(pixels);
    }
    
    // Generate animated gauge texture with needle at specific position
    private int generateGaugeTexture(String label, float value, float minVal, float maxVal) {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        
        // Cream/tan background
        int bgR = 215, bgG = 200, bgB = 165;
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (255 << 24) | (bgR << 16) | (bgG << 8) | bgB;
        }
        
        int cx = 8, cy = 9; // Center of gauge (slightly lower for needle pivot)
        
        // Draw scale arc (darker brown)
        int arcColor = (255 << 24) | (120 << 16) | (100 << 8) | 70;
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                float dist = (float) Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy));
                if (dist >= 5.5f && dist <= 6.5f && y < cy + 2) {
                    pixels[y * TEXTURE_SIZE + x] = arcColor;
                }
            }
        }
        
        // Draw scale tick marks and numbers
        int tickColor = (255 << 24) | (40 << 16) | (30 << 8) | 20;
        int numColor = (255 << 24) | (30 << 16) | (20 << 8) | 10;
        
        // Major ticks at 0%, 25%, 50%, 75%, 100%
        for (int i = 0; i <= 4; i++) {
            float angle = (float) (Math.PI * 0.8 - (Math.PI * 0.6) * i / 4.0); // Arc from ~144° to ~36°
            int tx = cx + (int)(Math.cos(angle) * 5);
            int ty = cy - (int)(Math.sin(angle) * 5);
            int tx2 = cx + (int)(Math.cos(angle) * 7);
            int ty2 = cy - (int)(Math.sin(angle) * 7);
            
            if (tx >= 0 && tx < 16 && ty >= 0 && ty < 16) pixels[ty * TEXTURE_SIZE + tx] = tickColor;
            if (tx2 >= 0 && tx2 < 16 && ty2 >= 0 && ty2 < 16) pixels[ty2 * TEXTURE_SIZE + tx2] = tickColor;
        }
        
        // Draw needle (black line from center pointing to value)
        float normalizedValue = (value - minVal) / (maxVal - minVal);
        normalizedValue = Math.max(0, Math.min(1, normalizedValue));
        float needleAngle = (float) (Math.PI * 0.8 - Math.PI * 0.6 * normalizedValue);
        
        int needleColor = (255 << 24) | (20 << 16) | (20 << 8) | 20;
        for (float r = 0; r < 6; r += 0.3f) {
            int nx = cx + (int)(Math.cos(needleAngle) * r);
            int ny = cy - (int)(Math.sin(needleAngle) * r);
            if (nx >= 0 && nx < 16 && ny >= 0 && ny < 16) {
                pixels[ny * TEXTURE_SIZE + nx] = needleColor;
            }
        }
        
        // Center pivot point (red dot)
        int pivotColor = (255 << 24) | (180 << 16) | (50 << 8) | 50;
        pixels[cy * TEXTURE_SIZE + cx] = pivotColor;
        
        // Draw value number at bottom
        int displayValue = (int) value;
        String valStr = String.valueOf(displayValue);
        int startX = 8 - valStr.length();
        for (int i = 0; i < valStr.length() && i < 4; i++) {
            drawMiniDigit(pixels, valStr.charAt(i), startX + i * 3, 12, numColor);
        }
        
        // Draw label at very top
        int labelColor = (255 << 24) | (80 << 16) | (60 << 8) | 40;
        if (label.equals("TEMP")) {
            // T
            pixels[1 * TEXTURE_SIZE + 5] = labelColor; pixels[1 * TEXTURE_SIZE + 6] = labelColor; pixels[1 * TEXTURE_SIZE + 7] = labelColor;
            pixels[2 * TEXTURE_SIZE + 6] = labelColor;
        } else if (label.equals("PRES")) {
            // P
            pixels[1 * TEXTURE_SIZE + 5] = labelColor; pixels[1 * TEXTURE_SIZE + 6] = labelColor;
            pixels[2 * TEXTURE_SIZE + 5] = labelColor; pixels[2 * TEXTURE_SIZE + 6] = labelColor;
        } else if (label.equals("PWR")) {
            // W shape
            pixels[1 * TEXTURE_SIZE + 5] = labelColor; pixels[2 * TEXTURE_SIZE + 6] = labelColor;
            pixels[1 * TEXTURE_SIZE + 7] = labelColor; pixels[2 * TEXTURE_SIZE + 8] = labelColor;
            pixels[1 * TEXTURE_SIZE + 9] = labelColor;
        } else if (label.equals("ROD")) {
            // R
            pixels[1 * TEXTURE_SIZE + 6] = labelColor; pixels[1 * TEXTURE_SIZE + 7] = labelColor;
            pixels[2 * TEXTURE_SIZE + 6] = labelColor; pixels[2 * TEXTURE_SIZE + 8] = labelColor;
        } else if (label.equals("FLOW")) {
            // F
            pixels[1 * TEXTURE_SIZE + 5] = labelColor; pixels[1 * TEXTURE_SIZE + 6] = labelColor; pixels[1 * TEXTURE_SIZE + 7] = labelColor;
            pixels[2 * TEXTURE_SIZE + 5] = labelColor;
        }
        
        // Brown border
        int borderColor = (255 << 24) | (100 << 16) | (80 << 8) | 60;
        for (int i = 0; i < TEXTURE_SIZE; i++) {
            pixels[i] = borderColor;
            pixels[(TEXTURE_SIZE - 1) * TEXTURE_SIZE + i] = borderColor;
            pixels[i * TEXTURE_SIZE] = borderColor;
            pixels[i * TEXTURE_SIZE + TEXTURE_SIZE - 1] = borderColor;
        }
        
        return createTextureFromPixels(pixels);
    }
    
    // Mini digit drawing for gauge displays (2x3 pixels)
    private void drawMiniDigit(int[] pixels, char digit, int x, int y, int color) {
        if (digit < '0' || digit > '9') return;
        
        // Simple 2x3 digit patterns
        int[][] patterns = {
            {1,1, 1,0, 1,0, 1,0, 1,1}, // 0
            {0,1, 0,1, 0,1, 0,1, 0,1}, // 1
            {1,1, 0,1, 1,1, 1,0, 1,1}, // 2
            {1,1, 0,1, 1,1, 0,1, 1,1}, // 3
            {1,0, 1,0, 1,1, 0,1, 0,1}, // 4
            {1,1, 1,0, 1,1, 0,1, 1,1}, // 5
            {1,1, 1,0, 1,1, 1,1, 1,1}, // 6
            {1,1, 0,1, 0,1, 0,1, 0,1}, // 7
            {1,1, 1,1, 1,1, 1,1, 1,1}, // 8
            {1,1, 1,1, 1,1, 0,1, 1,1}, // 9
        };

        int idx = digit - '0';
        int[] p = patterns[idx];
        for (int row = 0; row < 5; row++) {
            for (int col = 0; col < 2; col++) {
                if (p[row * 2 + col] == 1 && x + col < 16 && y + row < 16) {
                    pixels[(y + row) * TEXTURE_SIZE + x + col] = color;
                }
            }
        }
    }

    private void renderPumpConsoleUI(float screenW, float screenH) {
        drawHUDRect(0, 0, screenW, screenH, 0f, 0f, 0.02f, 0.7f);

        float bezelW = screenW * 0.86f;
        float bezelH = screenH * 0.82f;
        float bezelX = (screenW - bezelW) / 2f;
        float bezelY = (screenH - bezelH) / 2f;

        drawHUDRect(bezelX, bezelY, bezelW, bezelH, 0.03f, 0.04f, 0.08f, 0.94f);
        drawHUDRect(bezelX + 14f, bezelY + 14f, bezelW - 28f, bezelH - 28f, 0.05f, 0.09f, 0.14f, 0.9f);
        drawHUDRect(bezelX + 14f, bezelY + bezelH - 90f, bezelW - 28f, 76f, 0.65f, 0.32f, 0.07f, 0.95f);
        drawHUDRect(bezelX + 24f, bezelY + bezelH - 58f, bezelW * 0.5f, 34f, 0.95f, 0.71f, 0.2f, 0.8f);

        drawHUDText("PUMP ANNEX FRONT STATION", bezelX + 42f, bezelY + bezelH - 38f, 3, 1f, 0.94f, 0.65f, 1f);
        drawHUDText("LOOP 4 COOLING VECTOR", bezelX + bezelW - 320f, bezelY + bezelH - 38f, 2, 0.95f, 0.82f, 0.45f, 1f);

        float leftColumnX = bezelX + 40f;
        float leftColumnY = bezelY + 60f;
        float leftColumnW = bezelW * 0.28f;
        float leftColumnH = bezelH - 170f;
        drawHUDRect(leftColumnX, leftColumnY, leftColumnW, leftColumnH, 0.04f, 0.11f, 0.16f, 0.9f);
        drawHUDRect(leftColumnX + 8f, leftColumnY + 8f, leftColumnW - 16f, leftColumnH - 16f, 0.02f, 0.18f, 0.25f, 0.6f);

        for (int i = 1; i < 5; i++) {
            float gy = leftColumnY + (leftColumnH / 5f) * i;
            drawHUDRect(leftColumnX + 12f, gy, leftColumnW - 24f, 1.2f, 0.08f, 0.35f, 0.48f, 0.35f);
        }
        for (int i = 0; i < 3; i++) {
            float gx = leftColumnX + 16f + i * ((leftColumnW - 32f) / 2f);
            drawHUDRect(gx, leftColumnY + 18f, 1f, leftColumnH - 36f, 0.08f, 0.24f, 0.32f, 0.25f);
        }

        float barW = (leftColumnW - 72f) / 2f;
        float barH = leftColumnH - 150f;
        float barBase = leftColumnY + 70f;
        float[] flows = { pumpConsoleFlowA, pumpConsoleFlowB };
        String[] flowLabels = { "PUMP A", "PUMP B" };
        for (int i = 0; i < flows.length; i++) {
            float x = leftColumnX + 28f + i * (barW + 44f);
            drawHUDRect(x, barBase, barW, barH, 0.01f, 0.09f, 0.14f, 0.85f);
            drawHUDRect(x + 4f, barBase + 4f, barW - 8f, barH - 8f, 0.02f, 0.25f, 0.35f, 0.75f);

            float norm = Math.max(0f, Math.min(1f, flows[i] / 120f));
            float fill = (barH - 16f) * norm;
            float r = (flows[i] < 55f || flows[i] > 110f) ? 0.95f : 0.2f;
            float g = (flows[i] < 55f || flows[i] > 110f) ? 0.25f : 0.82f;
            float b = 0.4f;
            drawHUDRect(x + 6f, barBase + 8f, barW - 12f, fill, r, g, b, 0.95f);

            float warnHeight = barBase + 8f + (barH - 16f) * (100f / 120f);
            drawHUDRect(x + 6f, warnHeight, barW - 12f, 2f, 0.95f, 0.45f, 0.25f, 0.8f);

            drawHUDText(flowLabels[i], x, barBase + barH + 20f, 2, 0.8f, 0.95f, 1f, 1f);
            drawHUDText(String.format("%3.0f %%", flows[i]), x, barBase + barH - 32f, 3, 0.95f, 0.95f, 0.7f, 1f);
        }

        float capsuleY = leftColumnY + 18f;
        float capsuleW = (leftColumnW - 56f) / 2f;
        float capsuleH = 46f;
        float sealWarn = pumpConsoleSealTemp > 300f ? 1f : 0.35f;
        float vibWarn = pumpConsoleVibration > 0.3f ? 1f : 0.35f;
        drawHUDRect(leftColumnX + 20f, capsuleY, capsuleW, capsuleH, 0.2f, 0.08f, 0.05f, 0.85f);
        drawHUDRect(leftColumnX + 28f, capsuleY + 6f, capsuleW - 16f, capsuleH - 12f, 0.35f, sealWarn, 0.2f, 0.9f);
        drawHUDText("SEAL TEMP", leftColumnX + 32f, capsuleY + capsuleH - 14f, 2, 0.9f, 0.9f, 0.8f, 1f);
        drawHUDText(String.format("%.0f C", pumpConsoleSealTemp), leftColumnX + 32f, capsuleY + 12f, 2, 1f, 0.95f, 0.65f, 1f);

        float vibX = leftColumnX + 36f + capsuleW;
        drawHUDRect(vibX, capsuleY, capsuleW, capsuleH, 0.04f, 0.12f, 0.2f, 0.85f);
        drawHUDRect(vibX + 8f, capsuleY + 6f, capsuleW - 16f, capsuleH - 12f, vibWarn, 0.6f, 0.25f, 0.9f);
        drawHUDText("SHAFT VIB", vibX + 12f, capsuleY + capsuleH - 14f, 2, 0.8f, 0.95f, 1f, 1f);
        drawHUDText(String.format("%.2f G", pumpConsoleVibration), vibX + 12f, capsuleY + 12f, 2, 0.95f, 0.95f, 0.75f, 1f);

        float cavY = capsuleY + capsuleH + 18f;
        drawHUDRect(leftColumnX + 20f, cavY, leftColumnW - 40f, 42f, 0.2f, 0.05f, 0.05f, 0.85f);
        float cavFill = (leftColumnW - 76f) * Math.min(1f, pumpConsoleCavitationRisk);
        drawHUDRect(leftColumnX + 28f, cavY + 10f, leftColumnW - 56f, 12f, 0.1f, 0.15f, 0.18f, 0.7f);
        drawHUDRect(leftColumnX + 28f, cavY + 10f, cavFill, 12f, 0.9f, Math.max(0.2f, 1f - pumpConsoleCavitationRisk), 0.15f, 0.95f);
        drawHUDText("CAVITATION RISK", leftColumnX + 32f, cavY + 28f, 2, 0.9f, 0.85f, 0.6f, 1f);
        drawHUDText(String.format("%d %%", (int)(pumpConsoleCavitationRisk * 100f)), leftColumnX + leftColumnW - 90f, cavY + 12f, 2, 1f, 0.9f, 0.6f, 1f);

        float coreX = leftColumnX + leftColumnW + 44f;
        float coreY = leftColumnY;
        float coreW = bezelW * 0.32f;
        float coreH = leftColumnH;
        drawHUDRect(coreX, coreY, coreW, coreH, 0.02f, 0.06f, 0.09f, 0.85f);
        drawHUDRect(coreX + 12f, coreY + 40f, coreW - 24f, coreH - 80f, 0.01f, 0.14f, 0.22f, 0.7f);

        for (int ring = 0; ring < 4; ring++) {
            float inset = 20f + ring * 22f;
            drawHUDRect(coreX + inset, coreY + inset + 12f, coreW - inset * 2f, coreH - inset * 2f - 24f,
                0.04f + ring * 0.02f, 0.16f + ring * 0.03f, 0.24f + ring * 0.05f, 0.15f);
        }
        drawHUDRect(coreX + coreW / 2f - 1f, coreY + 36f, 2f, coreH - 72f, 0.15f, 0.75f, 0.85f, 0.4f);
        drawHUDRect(coreX + 24f, coreY + coreH / 2f - 1f, coreW - 48f, 2f, 0.15f, 0.75f, 0.85f, 0.4f);

        float avgFlow = (pumpConsoleFlowA + pumpConsoleFlowB) * 0.5f;
        float deltaFlow = pumpConsoleFlowA - pumpConsoleFlowB;
        drawHUDText("FLOW FIELD", coreX + 28f, coreY + coreH - 28f, 2, 0.8f, 0.95f, 1f, 1f);
        drawHUDText(String.format("AVG %.0f %%", avgFlow), coreX + 28f, coreY + coreH - 58f, 3, 0.95f, 0.95f, 0.7f, 1f);
        drawHUDText(String.format("DELTA %+4.1f %%", deltaFlow), coreX + 28f, coreY + coreH - 88f, 2, 0.9f, 0.8f, 0.5f, 1f);

        float trimBaseY = coreY + 90f;
        drawHUDRect(coreX + 32f, trimBaseY, coreW - 64f, 62f, 0.04f, 0.12f, 0.18f, 0.85f);
        float trimSpan = coreW - 80f;
        float trimAFrac = (pumpConsoleTrimA + 20f) / 40f;
        float trimBFrac = (pumpConsoleTrimB + 20f) / 40f;
        drawHUDRect(coreX + 40f, trimBaseY + 14f, trimSpan, 6f, 0.2f, 0.2f, 0.3f, 0.8f);
        drawHUDRect(coreX + 40f, trimBaseY + 14f, trimSpan * trimAFrac, 6f, 0.8f, 0.55f, 0.2f, 0.9f);
        drawHUDRect(coreX + 40f, trimBaseY + 34f, trimSpan, 6f, 0.2f, 0.2f, 0.3f, 0.8f);
        drawHUDRect(coreX + 40f, trimBaseY + 34f, trimSpan * trimBFrac, 6f, 0.2f, 0.7f, 0.9f, 0.9f);
        drawHUDText(String.format("TRIM A %+.1f%%", pumpConsoleTrimA), coreX + 44f, trimBaseY + 48f, 2, 0.9f, 0.85f, 0.65f, 1f);
        drawHUDText(String.format("TRIM B %+.1f%%", pumpConsoleTrimB), coreX + 44f, trimBaseY + 22f, 2, 0.8f, 0.95f, 1f, 1f);

        float alertBoxY = coreY + 24f;
        float alertWidth = coreW - 64f;
        float alertFill = alertWidth * Math.min(1f, pumpConsoleAlertLevel);
        drawHUDRect(coreX + 32f, alertBoxY, alertWidth, 30f, 0.2f, 0.05f, 0.05f, 0.85f);
        drawHUDRect(coreX + 32f, alertBoxY, alertFill, 30f, 0.95f, 0.45f, 0.15f, 0.9f);
        drawHUDText(String.format("ALERT VECTOR %.0f%%", pumpConsoleAlertLevel * 100f), coreX + 38f, alertBoxY + 8f, 2, 1f, 0.9f, 0.7f, 1f);

        float controlColumnX = coreX + coreW + 40f;
        float controlColumnW = bezelX + bezelW - controlColumnX - 36f;
        float controlColumnTop = bezelY + bezelH - 120f;
        float controlHeight = 68f;
        float controlGap = 16f;
        String[] pumpControlNames = {
            "PUMP A SPEED TRIM: " + String.format("%+.1f%%", pumpConsoleTrimA),
            "PUMP B SPEED TRIM: " + String.format("%+.1f%%", pumpConsoleTrimB),
            "CROSSFLOW HEADER: " + (pumpConsoleCrossfeedOpen ? "OPEN" : "SHUT"),
            "EMERGENCY FLUSH: " + (pumpConsoleEmergencyFlush ? "ARMED" : "SAFE"),
            pumpConsoleDiagnosticsActive ? "DIAGNOSTIC: RUNNING" : "RUN FLOW DIAGNOSTIC"
        };
        String[] pumpControlHints = {
            "Balances suction on A loop",
            "Maintains standby circulation",
            pumpConsoleCrossfeedOpen ? "LEFT-CLICK TO ISOLATE" : "RIGHT-CLICK TO OPEN",
            pumpConsoleEmergencyFlush ? "LEFT-CLICK TO SAFE" : "RIGHT-CLICK TO ARM",
            pumpConsoleDiagnosticsActive ? "ANALYZING CAVITATION" : (pumpConsoleDiagnosticsProgress > 0.05f ? "READY - LAST REPORT OK" : "CLICK TO START")
        };

        for (int i = 0; i < pumpControlNames.length; i++) {
            float cardTop = controlColumnTop - i * (controlHeight + controlGap);
            float cardBottom = cardTop - controlHeight;
            boolean hovered = (machineMouseX >= controlColumnX + 8f && machineMouseX <= controlColumnX + controlColumnW - 8f &&
                machineMouseY >= cardBottom && machineMouseY <= cardTop);
            if (hovered) pumpConsoleSelection = i;
            boolean selected = (pumpConsoleSelection == i);

            drawHUDRect(controlColumnX, cardBottom, controlColumnW, controlHeight, 0.05f, 0.05f, 0.08f, 0.8f);
            float accent = selected ? 0.85f : (hovered ? 0.55f : 0.25f);
            drawHUDRect(controlColumnX + 6f, cardBottom + 6f, 6f, controlHeight - 12f, 0.65f, 0.9f * accent, 0.4f * accent, 1f);
            if (selected || hovered) {
                drawHUDRect(controlColumnX + 18f, cardBottom + 6f, controlColumnW - 36f, controlHeight - 12f,
                    selected ? 0.18f : 0.12f,
                    selected ? 0.4f : 0.22f,
                    selected ? 0.3f : 0.2f,
                    selected ? 0.85f : 0.6f);
            }
            drawHUDText(pumpControlNames[i], controlColumnX + 32f, cardBottom + controlHeight - 18f, 2,
                0.9f, 0.95f, 0.75f, 1f);
            drawHUDText(pumpControlHints[i], controlColumnX + 32f, cardBottom + 12f, 2, 0.65f, 0.8f, 0.75f, 0.95f);
        }

        float diagBoxTop = controlColumnTop - pumpControlNames.length * (controlHeight + controlGap) - 20f;
        drawHUDRect(controlColumnX, diagBoxTop - 80f, controlColumnW, 72f, 0.03f, 0.08f, 0.12f, 0.85f);
        drawHUDText("FLOW DIAGNOSTIC", controlColumnX + 18f, diagBoxTop - 26f, 2, 0.8f, 0.95f, 1f, 1f);
        drawHUDRect(controlColumnX + 18f, diagBoxTop - 54f, controlColumnW - 36f, 12f, 0.15f, 0.25f, 0.32f, 0.8f);
        float diagFillW = (controlColumnW - 36f) * Math.min(1f, pumpConsoleDiagnosticsProgress);
        drawHUDRect(controlColumnX + 18f, diagBoxTop - 54f, diagFillW, 12f, 0.25f, 0.85f, 0.55f, 1f);
        drawHUDText(pumpConsoleDiagnosticsActive ? "RUNNING" : (pumpConsoleDiagnosticsProgress >= 1f ? "COMPLETE" : "STANDBY"),
            controlColumnX + 18f, diagBoxTop - 70f, 2, 0.7f, 0.95f, 0.7f, 1f);

        drawHUDText("ARROWS / MOUSE NAV   L-CLICK=DECREASE   R-CLICK=INCREASE   SPACE=RUN DIAGNOSTIC",
            bezelX + 32f, bezelY + 24f, 2, 0.6f, 0.85f, 0.75f, 0.9f);

        if (machineLogTimer > 0 && !machineLogMessage.isEmpty()) {
            float logW = machineLogMessage.length() * 11f + 40f;
            float logX = bezelX + (bezelW - logW) / 2f;
            float logAlpha = Math.min(1f, machineLogTimer);
            drawHUDRect(logX, bezelY + bezelH - 128f, logW, 28f, 0.09f, 0.2f, 0.24f, logAlpha * 0.9f);
            drawHUDText(machineLogMessage, logX + 14f, bezelY + bezelH - 118f, 2, 0.8f, 0.95f, 0.85f, logAlpha);
        }

        drawHUDText("ESC TO CLOSE", bezelX + bezelW - 180f, bezelY + 24f, 2, 0.5f, 0.75f, 0.5f, 1f);
    }
    
    // Generate individual ELENA cell with status indicator and label
    private int generateElenaCellTexture(int statusColor, String label) {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        
        // Dark background
        int bgColor = (255 << 24) | (20 << 16) | (25 << 8) | 20;
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = bgColor;
        }
        
        // Status light in center (large, visible)
        int sr = (statusColor >> 16) & 0xFF;
        int sg = (statusColor >> 8) & 0xFF;
        int sb = statusColor & 0xFF;
        
        for (int y = 2; y < 12; y++) {
            for (int x = 2; x < 14; x++) {
                // Glow effect - brighter in center
                int dist = Math.abs(x - 8) + Math.abs(y - 6);
                float brightness = Math.max(0.5f, 1.0f - dist * 0.05f);
                int r = Math.min(255, (int)(sr * brightness));
                int g = Math.min(255, (int)(sg * brightness));
                int b = Math.min(255, (int)(sb * brightness));
                pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (r << 16) | (g << 8) | b;
            }
        }
        
        // Draw label text (simple 2-char pixel font)
        int textColor = (255 << 24) | (255 << 16) | (255 << 8) | 255;
        if (label.equals("OK")) {
            // O at x=4-6
            pixels[13 * TEXTURE_SIZE + 4] = textColor;
            pixels[13 * TEXTURE_SIZE + 6] = textColor;
            pixels[14 * TEXTURE_SIZE + 4] = textColor;
            pixels[14 * TEXTURE_SIZE + 6] = textColor;
            // K at x=8-10
            pixels[13 * TEXTURE_SIZE + 8] = textColor;
            pixels[13 * TEXTURE_SIZE + 10] = textColor;
            pixels[14 * TEXTURE_SIZE + 8] = textColor;
            pixels[14 * TEXTURE_SIZE + 9] = textColor;
        } else if (label.equals("!!")) {
            // Two exclamation marks
            pixels[13 * TEXTURE_SIZE + 5] = textColor;
            pixels[14 * TEXTURE_SIZE + 5] = textColor;
            pixels[13 * TEXTURE_SIZE + 10] = textColor;
            pixels[14 * TEXTURE_SIZE + 10] = textColor;
        } else if (label.equals("XX")) {
            // X shapes
            pixels[13 * TEXTURE_SIZE + 4] = textColor;
            pixels[13 * TEXTURE_SIZE + 6] = textColor;
            pixels[14 * TEXTURE_SIZE + 5] = textColor;
            pixels[13 * TEXTURE_SIZE + 9] = textColor;
            pixels[13 * TEXTURE_SIZE + 11] = textColor;
            pixels[14 * TEXTURE_SIZE + 10] = textColor;
        }
        
        // Metal border
        int borderColor = (255 << 24) | (100 << 16) | (100 << 8) | 100;
        for (int i = 0; i < TEXTURE_SIZE; i++) {
            pixels[i] = borderColor;
            pixels[(TEXTURE_SIZE - 1) * TEXTURE_SIZE + i] = borderColor;
            pixels[i * TEXTURE_SIZE] = borderColor;
            pixels[i * TEXTURE_SIZE + TEXTURE_SIZE - 1] = borderColor;
        }
        
        return createTextureFromPixels(pixels);
    }
    
    // Generate digital display with numbers
    private int generateDigitalDisplayTexture(String value, String unit) {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        
        // Black screen background
        int bgColor = (255 << 24) | (5 << 16) | (15 << 8) | 5;
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = bgColor;
        }
        
        // Green digital text color
        int digitColor = (255 << 24) | (50 << 16) | (255 << 8) | 80;
        int dimColor = (255 << 24) | (20 << 16) | (80 << 8) | 30;
        
        // Draw main value (large digits) - simplified 7-segment style
        int startX = 1;
        for (int i = 0; i < Math.min(value.length(), 4); i++) {
            char c = value.charAt(i);
            drawDigit(pixels, c, startX + i * 4, 2, digitColor);
        }
        
        // Draw unit label at bottom
        int unitColor = (255 << 24) | (150 << 16) | (200 << 8) | 150;
        if (unit.equals("MW")) {
            // M
            pixels[12 * TEXTURE_SIZE + 2] = unitColor;
            pixels[13 * TEXTURE_SIZE + 2] = unitColor;
            pixels[12 * TEXTURE_SIZE + 4] = unitColor;
            pixels[13 * TEXTURE_SIZE + 4] = unitColor;
            pixels[12 * TEXTURE_SIZE + 3] = unitColor;
            // W
            pixels[12 * TEXTURE_SIZE + 6] = unitColor;
            pixels[13 * TEXTURE_SIZE + 6] = unitColor;
            pixels[13 * TEXTURE_SIZE + 7] = unitColor;
            pixels[12 * TEXTURE_SIZE + 8] = unitColor;
            pixels[13 * TEXTURE_SIZE + 8] = unitColor;
        } else if (unit.equals("C")) {
            // Degree C
            pixels[12 * TEXTURE_SIZE + 4] = unitColor;
            pixels[12 * TEXTURE_SIZE + 6] = unitColor;
            pixels[13 * TEXTURE_SIZE + 6] = unitColor;
            pixels[14 * TEXTURE_SIZE + 6] = unitColor;
        } else if (unit.equals("ATM")) {
            // ATM
            pixels[12 * TEXTURE_SIZE + 2] = unitColor;
            pixels[13 * TEXTURE_SIZE + 1] = unitColor;
            pixels[13 * TEXTURE_SIZE + 3] = unitColor;
            pixels[12 * TEXTURE_SIZE + 5] = unitColor;
            pixels[13 * TEXTURE_SIZE + 5] = unitColor;
            pixels[12 * TEXTURE_SIZE + 8] = unitColor;
            pixels[13 * TEXTURE_SIZE + 8] = unitColor;
            pixels[12 * TEXTURE_SIZE + 10] = unitColor;
            pixels[13 * TEXTURE_SIZE + 10] = unitColor;
        } else if (unit.equals("RODS")) {
            // RODS text
            pixels[12 * TEXTURE_SIZE + 1] = unitColor;
            pixels[13 * TEXTURE_SIZE + 1] = unitColor;
            pixels[12 * TEXTURE_SIZE + 3] = unitColor;
            pixels[12 * TEXTURE_SIZE + 5] = unitColor;
            pixels[13 * TEXTURE_SIZE + 5] = unitColor;
        }
        
        // Screen bezel
        int bezelColor = (255 << 24) | (60 << 16) | (60 << 8) | 70;
        for (int i = 0; i < TEXTURE_SIZE; i++) {
            pixels[i] = bezelColor;
            pixels[(TEXTURE_SIZE - 1) * TEXTURE_SIZE + i] = bezelColor;
            pixels[i * TEXTURE_SIZE] = bezelColor;
            pixels[i * TEXTURE_SIZE + TEXTURE_SIZE - 1] = bezelColor;
        }
        
        return createTextureFromPixels(pixels);
    }
    
    // Helper to draw a simplified digit
    private void drawDigit(int[] pixels, char digit, int x, int y, int color) {
        // Simple 3x5 pixel digits
        boolean[][] patterns = {
            {true, true, true, true, false, true, true, false, true, true, false, true, true, true, true},   // 0
            {false, true, false, false, true, false, false, true, false, false, true, false, false, true, false}, // 1
            {true, true, true, false, false, true, true, true, true, true, false, false, true, true, true},   // 2
            {true, true, true, false, false, true, true, true, true, false, false, true, true, true, true},   // 3
            {true, false, true, true, false, true, true, true, true, false, false, true, false, false, true}, // 4
            {true, true, true, true, false, false, true, true, true, false, false, true, true, true, true},   // 5
            {true, true, true, true, false, false, true, true, true, true, false, true, true, true, true},    // 6
            {true, true, true, false, false, true, false, false, true, false, false, true, false, false, true}, // 7
            {true, true, true, true, false, true, true, true, true, true, false, true, true, true, true},     // 8
            {true, true, true, true, false, true, true, true, true, false, false, true, true, true, true},    // 9
        };
        
        int idx = digit - '0';
        if (idx < 0 || idx > 9) {
            if (digit == '.') {
                pixels[(y + 4) * TEXTURE_SIZE + x + 1] = color;
                return;
            }
            return;
        }
        
        boolean[] pattern = patterns[idx];
        for (int row = 0; row < 5; row++) {
            for (int col = 0; col < 3; col++) {
                if (pattern[row * 3 + col] && x + col < 16 && y + row < 16) {
                    pixels[(y + row) * TEXTURE_SIZE + x + col] = color;
                }
            }
        }
    }
    
    private int generateIndicatorPanelTexture() {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        Random rand = new Random(2222);
        
        // Dark gray background
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                int gray = 60 + rand.nextInt(15);
                pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (gray << 16) | (gray << 8) | gray;
            }
        }
        
        // Grid of small indicator lights
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                int px = 1 + col * 2;
                int py = 1 + row * 2;
                if (px < 16 && py < 16) {
                    float r = rand.nextFloat();
                    if (r < 0.4f) {
                        pixels[py * TEXTURE_SIZE + px] = (255 << 24) | (50 << 16) | (200 << 8) | 50; // Green
                    } else if (r < 0.6f) {
                        pixels[py * TEXTURE_SIZE + px] = (255 << 24) | (200 << 16) | (50 << 8) | 50; // Red
                    } else {
                        pixels[py * TEXTURE_SIZE + px] = (255 << 24) | (80 << 16) | (80 << 8) | 80; // Off
                    }
                }
            }
        }
        return createTextureFromPixels(pixels);
    }
    
    private int generateSwitchPanelTexture() {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        Random rand = new Random(3333);
        
        // Cream background
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                int r = 220 + rand.nextInt(15) - 7;
                int g = 215 + rand.nextInt(15) - 7;
                int b = 200 + rand.nextInt(15) - 7;
                pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (r << 16) | (g << 8) | b;
            }
        }
        
        // Draw switch rows
        for (int row = 0; row < 4; row++) {
            int y = 2 + row * 4;
            for (int col = 0; col < 4; col++) {
                int x = 2 + col * 4;
                // Switch base
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = 0; dx <= 0; dx++) {
                        int px = x + dx;
                        int py = y + dy;
                        if (px >= 0 && px < 16 && py >= 0 && py < 16) {
                            pixels[py * TEXTURE_SIZE + px] = (255 << 24) | (40 << 16) | (40 << 8) | 40;
                        }
                    }
                }
            }
        }
        
        return createTextureFromPixels(pixels);
    }
    
    private int generateCeilingTileTexture() {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        Random rand = new Random(4444);
        
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                int gray = 235 + rand.nextInt(15) - 7;
                
                // Grid pattern
                boolean isGrid = (x % 8 == 0) || (y % 8 == 0);
                if (isGrid) {
                    gray -= 30;
                }
                
                // Tile edge
                if (x == 0 || x == 15 || y == 0 || y == 15) {
                    gray -= 50;
                }
                
                gray = Math.max(150, Math.min(255, gray));
                pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (gray << 16) | (gray << 8) | gray;
            }
        }
        return createTextureFromPixels(pixels);
    }
    
    private int generateFluorescentLightTexture() {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        Random rand = new Random(5555);
        
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                // Bright white/yellow light
                int r = 255;
                int g = 250 + rand.nextInt(5);
                int b = 230 + rand.nextInt(20);
                
                // Frame edges
                if (x <= 1 || x >= 14 || y <= 1 || y >= 14) {
                    r = 180; g = 180; b = 180;
                }
                
                // Light tube pattern
                if (x > 1 && x < 14 && (y == 5 || y == 10)) {
                    r = 255; g = 255; b = 255;
                }
                
                pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return createTextureFromPixels(pixels);
    }
    
    private int generateMonitorTexture() {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        Random rand = new Random(6666);
        
        // Dark monitor frame
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (40 << 16) | (40 << 8) | 40;
            }
        }
        
        // Screen area (green phosphor CRT look)
        for (int y = 2; y < 14; y++) {
            for (int x = 2; x < 14; x++) {
                int g = 30 + rand.nextInt(20);
                // Scanlines
                if (y % 2 == 0) {
                    g += 10;
                }
                pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (0 << 16) | (g << 8) | 0;
            }
        }
        
        return createTextureFromPixels(pixels);
    }
    
    private int generateStoneTexture() {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        Random rand = new Random(42);
        
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                int gray = 128 + rand.nextInt(20) - 10;
                // Add some darker spots
                if (rand.nextFloat() < 0.15f) {
                    gray -= 20 + rand.nextInt(15);
                }
                // Add some lighter spots
                if (rand.nextFloat() < 0.1f) {
                    gray += 15 + rand.nextInt(10);
                }
                gray = Math.max(80, Math.min(160, gray));
                pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (gray << 16) | (gray << 8) | gray;
            }
        }
        return createTextureFromPixels(pixels);
    }
    
    private int generateCobblestoneTexture() {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        Random rand = new Random(123);
        
        // Base gray
        for (int i = 0; i < pixels.length; i++) {
            int gray = 100 + rand.nextInt(30);
            pixels[i] = (255 << 24) | (gray << 16) | (gray << 8) | gray;
        }
        
        // Add "stone" outlines
        for (int i = 0; i < 12; i++) {
            int cx = rand.nextInt(14) + 1;
            int cy = rand.nextInt(14) + 1;
            int size = 2 + rand.nextInt(4);
            
            for (int dy = -size; dy <= size; dy++) {
                for (int dx = -size; dx <= size; dx++) {
                    int px = (cx + dx + TEXTURE_SIZE) % TEXTURE_SIZE;
                    int py = (cy + dy + TEXTURE_SIZE) % TEXTURE_SIZE;
                    
                    if (Math.abs(dx) == size || Math.abs(dy) == size) {
                        // Dark outline
                        int gray = 60 + rand.nextInt(20);
                        pixels[py * TEXTURE_SIZE + px] = (255 << 24) | (gray << 16) | (gray << 8) | gray;
                    } else {
                        // Inner stone
                        int gray = 120 + rand.nextInt(40);
                        pixels[py * TEXTURE_SIZE + px] = (255 << 24) | (gray << 16) | (gray << 8) | gray;
                    }
                }
            }
        }
        return createTextureFromPixels(pixels);
    }
    
    private int generateDirtTexture() {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        Random rand = new Random(456);
        
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                int r = 134 + rand.nextInt(24) - 12;
                int g = 96 + rand.nextInt(24) - 12;
                int b = 67 + rand.nextInt(24) - 12;
                
                // Dark spots
                if (rand.nextFloat() < 0.2f) {
                    r -= 30; g -= 25; b -= 20;
                }
                
                r = Math.max(80, Math.min(180, r));
                g = Math.max(50, Math.min(130, g));
                b = Math.max(30, Math.min(100, b));
                
                pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return createTextureFromPixels(pixels);
    }
    
    private int generateWoodPlanksTexture(int baseColor, int darkColor) {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        Random rand = new Random(baseColor);
        
        int baseR = (baseColor >> 16) & 0xFF;
        int baseG = (baseColor >> 8) & 0xFF;
        int baseB = baseColor & 0xFF;
        
        int darkR = (darkColor >> 16) & 0xFF;
        int darkG = (darkColor >> 8) & 0xFF;
        int darkB = darkColor & 0xFF;
        
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                int plankSection = y / 4; // 4 horizontal planks
                boolean isLine = (y % 4 == 0);
                
                int r, g, b;
                if (isLine) {
                    // Dark line between planks
                    r = darkR + rand.nextInt(10) - 5;
                    g = darkG + rand.nextInt(10) - 5;
                    b = darkB + rand.nextInt(10) - 5;
                } else {
                    // Wood grain
                    int variation = rand.nextInt(20) - 10;
                    r = baseR + variation;
                    g = baseG + variation;
                    b = baseB + variation;
                    
                    // Add wood grain lines
                    if (rand.nextFloat() < 0.15f) {
                        r -= 15; g -= 12; b -= 8;
                    }
                }
                
                r = Math.max(0, Math.min(255, r));
                g = Math.max(0, Math.min(255, g));
                b = Math.max(0, Math.min(255, b));
                
                pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return createTextureFromPixels(pixels);
    }
    
    private int generateBricksTexture() {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        Random rand = new Random(789);
        
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                int row = y / 4;
                boolean isHorizontalMortar = (y % 4 == 0);
                int offset = (row % 2 == 0) ? 0 : 8;
                boolean isVerticalMortar = ((x + offset) % 8 == 0);
                
                if (isHorizontalMortar || isVerticalMortar) {
                    // Mortar (light gray)
                    int gray = 180 + rand.nextInt(20) - 10;
                    pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (gray << 16) | (gray << 8) | gray;
                } else {
                    // Brick (red/brown)
                    int r = 156 + rand.nextInt(30) - 15;
                    int g = 82 + rand.nextInt(20) - 10;
                    int b = 67 + rand.nextInt(20) - 10;
                    pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (r << 16) | (g << 8) | b;
                }
            }
        }
        return createTextureFromPixels(pixels);
    }
    
    private int generateIronBlockTexture() {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        Random rand = new Random(999);
        
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                int gray = 200 + rand.nextInt(30) - 15;
                
                // Cross pattern like iron block
                boolean onEdge = (x == 0 || x == 15 || y == 0 || y == 15);
                boolean inCenter = (x >= 6 && x <= 9 && y >= 6 && y <= 9);
                
                if (onEdge) {
                    gray -= 40;
                } else if (inCenter) {
                    gray += 20;
                }
                
                // Add subtle scratches
                if (rand.nextFloat() < 0.08f) {
                    gray -= 25;
                }
                
                gray = Math.max(140, Math.min(240, gray));
                pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (gray << 16) | (gray << 8) | gray;
            }
        }
        return createTextureFromPixels(pixels);
    }
    
    private int generateDiamondBlockTexture() {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        Random rand = new Random(2222);
        
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                int r = 97 + rand.nextInt(30) - 15;
                int g = 219 + rand.nextInt(30) - 15;
                int b = 213 + rand.nextInt(30) - 15;
                
                // Diamond pattern
                boolean onEdge = (x == 0 || x == 15 || y == 0 || y == 15);
                if (onEdge) {
                    r -= 30; g -= 30; b -= 30;
                }
                
                // Sparkle
                if (rand.nextFloat() < 0.05f) {
                    r += 50; g += 30; b += 30;
                }
                
                r = Math.max(60, Math.min(255, r));
                g = Math.max(150, Math.min(255, g));
                b = Math.max(150, Math.min(255, b));
                
                pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return createTextureFromPixels(pixels);
    }
    
    private int generateCoalBlockTexture() {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        Random rand = new Random(3333);
        
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                int gray = 35 + rand.nextInt(25) - 12;
                
                // Slight highlights
                if (rand.nextFloat() < 0.1f) {
                    gray += 20;
                }
                
                gray = Math.max(15, Math.min(80, gray));
                pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (gray << 16) | (gray << 8) | gray;
            }
        }
        return createTextureFromPixels(pixels);
    }
    
    private int generateConcreteTexture(int baseColor) {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        Random rand = new Random(baseColor + 1111);
        
        int baseR = (baseColor >> 16) & 0xFF;
        int baseG = (baseColor >> 8) & 0xFF;
        int baseB = baseColor & 0xFF;
        
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                int variation = rand.nextInt(16) - 8;
                int r = Math.max(0, Math.min(255, baseR + variation));
                int g = Math.max(0, Math.min(255, baseG + variation));
                int b = Math.max(0, Math.min(255, baseB + variation));
                
                pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return createTextureFromPixels(pixels);
    }
    
    private int generateGlassTexture() {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                // Minecraft-style glass - visible light blue grid lines, transparent center
                boolean isEdge = (x == 0 || x == 15 || y == 0 || y == 15);
                
                if (isEdge) {
                    // Visible light blue frame lines (semi-transparent)
                    int r = 180, g = 210, b = 240;
                    int alpha = 180;  // Visible but not solid
                    pixels[y * TEXTURE_SIZE + x] = (alpha << 24) | (r << 16) | (g << 8) | b;
                } else {
                    // Very transparent center - can see through
                    int r = 220, g = 240, b = 255;
                    int alpha = 30;  // Very transparent
                    pixels[y * TEXTURE_SIZE + x] = (alpha << 24) | (r << 16) | (g << 8) | b;
                }
            }
        }
        return createTextureFromPixels(pixels);
    }
    
    private int generateGlowstoneTexture() {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        Random rand = new Random(5555);
        
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                int r = 255;
                int g = 200 + rand.nextInt(55);
                int b = 100 + rand.nextInt(60);
                
                // Dark cracks
                if (rand.nextFloat() < 0.12f) {
                    r = 180 + rand.nextInt(40);
                    g = 140 + rand.nextInt(40);
                    b = 60 + rand.nextInt(40);
                }
                
                pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return createTextureFromPixels(pixels);
    }
    
    private int generateRedstoneBlockTexture() {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        Random rand = new Random(6666);
        
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                int r = 170 + rand.nextInt(40) - 20;
                int g = 20 + rand.nextInt(20) - 10;
                int b = 20 + rand.nextInt(20) - 10;
                
                // Bright spots
                if (rand.nextFloat() < 0.1f) {
                    r = Math.min(255, r + 50);
                    g = Math.min(60, g + 20);
                }
                
                pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return createTextureFromPixels(pixels);
    }
    
    private int generateObsidianTexture() {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        Random rand = new Random(7777);
        
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                int base = 20 + rand.nextInt(20) - 10;
                int r = base + rand.nextInt(10);
                int g = base;
                int b = base + 20 + rand.nextInt(15); // Slight purple tint
                
                // Purple highlights
                if (rand.nextFloat() < 0.08f) {
                    r += 30;
                    b += 40;
                }
                
                r = Math.max(10, Math.min(80, r));
                g = Math.max(10, Math.min(50, g));
                b = Math.max(20, Math.min(100, b));
                
                pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return createTextureFromPixels(pixels);
    }
    
    private int createTextureFromPixels(int[] pixels) {
        // Flip vertically - OpenGL UV origin is bottom-left, our pixels are top-down
        int[] flipped = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        for (int row = 0; row < TEXTURE_SIZE; row++) {
            System.arraycopy(pixels, row * TEXTURE_SIZE, flipped, (TEXTURE_SIZE - 1 - row) * TEXTURE_SIZE, TEXTURE_SIZE);
        }
        ByteBuffer buffer = BufferUtils.createByteBuffer(TEXTURE_SIZE * TEXTURE_SIZE * 4);
        for (int pixel : flipped) {
            buffer.put((byte) ((pixel >> 16) & 0xFF)); // R
            buffer.put((byte) ((pixel >> 8) & 0xFF));  // G
            buffer.put((byte) (pixel & 0xFF));         // B
            buffer.put((byte) ((pixel >> 24) & 0xFF)); // A
        }
        buffer.flip();
        
        int textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);
        
        // Minecraft-style nearest neighbor filtering (pixelated look)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, TEXTURE_SIZE, TEXTURE_SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        
        return textureId;
    }

    // Raw version - no vertical flip (caller must handle flipping)
    private int createTextureFromPixelsRaw(int[] pixels, int width, int height) {
        ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
        for (int pixel : pixels) {
            buffer.put((byte) ((pixel >> 16) & 0xFF)); // R
            buffer.put((byte) ((pixel >> 8) & 0xFF));  // G
            buffer.put((byte) (pixel & 0xFF));         // B
            buffer.put((byte) ((pixel >> 24) & 0xFF)); // A
        }
        buffer.flip();

        int textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);

        return textureId;
    }

    // Version with automatic vertical flip + custom size
    private int createTextureFromPixels(int[] pixels, int width, int height) {
        int[] flipped = new int[width * height];
        for (int row = 0; row < height; row++) {
            System.arraycopy(pixels, row * width, flipped, (height - 1 - row) * width, width);
        }
        return createTextureFromPixelsRaw(flipped, width, height);
    }
    
    private void createShaders() {
        // Vertex shader with lighting and texture coords
        String vertexShaderSource = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec3 aNormal;
            layout (location = 2) in vec2 aTexCoord;
            
            out vec3 FragPos;
            out vec3 Normal;
            out vec2 TexCoord;
            
            uniform mat4 model;
            uniform mat4 view;
            uniform mat4 projection;
            
            void main() {
                FragPos = vec3(model * vec4(aPos, 1.0));
                Normal = mat3(transpose(inverse(model))) * aNormal;
                TexCoord = aTexCoord;
                gl_Position = projection * view * model * vec4(aPos, 1.0);
            }
            """;
        
        // Fragment shader with Phong lighting and texture support
        String fragmentShaderSource = """
            #version 330 core
            out vec4 FragColor;
            
            in vec3 FragPos;
            in vec3 Normal;
            in vec2 TexCoord;
            
            uniform vec3 objectColor;
            uniform vec3 lightPos;
            uniform vec3 viewPos;
            uniform vec3 lightColor;
            uniform float ambient;
            uniform bool useTexture;
            uniform bool fullBright;
            uniform sampler2D blockTexture;
            
            void main() {
                // Get base color from texture or uniform
                vec4 texColor;
                float alpha = 1.0;
                if (useTexture) {
                    texColor = texture(blockTexture, TexCoord);
                    alpha = texColor.a;
                } else {
                    texColor = vec4(objectColor, 1.0);
                }
                vec3 baseColor = texColor.rgb;
                
                // Skip lighting for fullBright objects (labels, UI elements)
                if (fullBright) {
                    FragColor = vec4(baseColor, alpha);
                    return;
                }
                
                // Ambient
                vec3 ambientLight = ambient * lightColor;
                
                // Diffuse
                vec3 norm = normalize(Normal);
                vec3 lightDir = normalize(lightPos - FragPos);
                float diff = max(dot(norm, lightDir), 0.0);
                vec3 diffuse = diff * lightColor;
                
                // Specular
                float specularStrength = 0.3;
                vec3 viewDir = normalize(viewPos - FragPos);
                vec3 reflectDir = reflect(-lightDir, norm);
                float spec = pow(max(dot(viewDir, reflectDir), 0.0), 16);
                vec3 specular = specularStrength * spec * lightColor;
                
                vec3 result = (ambientLight + diffuse + specular) * baseColor;
                FragColor = vec4(result, alpha);
            }
            """;
        
        // Compile vertex shader
        int vertexShader = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vertexShader, vertexShaderSource);
        glCompileShader(vertexShader);
        checkShaderCompilation(vertexShader, "VERTEX");
        
        // Compile fragment shader
        int fragmentShader = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fragmentShader, fragmentShaderSource);
        glCompileShader(fragmentShader);
        checkShaderCompilation(fragmentShader, "FRAGMENT");
        
        // Link shader program
        shaderProgram = glCreateProgram();
        glAttachShader(shaderProgram, vertexShader);
        glAttachShader(shaderProgram, fragmentShader);
        glLinkProgram(shaderProgram);
        checkProgramLinking(shaderProgram);
        
        // Delete shaders (no longer needed after linking)
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
        
        // Get uniform locations
        modelLoc = glGetUniformLocation(shaderProgram, "model");
        viewLoc = glGetUniformLocation(shaderProgram, "view");
        projectionLoc = glGetUniformLocation(shaderProgram, "projection");
        colorLoc = glGetUniformLocation(shaderProgram, "objectColor");
        lightPosLoc = glGetUniformLocation(shaderProgram, "lightPos");
        viewPosLoc = glGetUniformLocation(shaderProgram, "viewPos");
        lightColorLoc = glGetUniformLocation(shaderProgram, "lightColor");
        ambientLoc = glGetUniformLocation(shaderProgram, "ambient");
        useTextureLoc = glGetUniformLocation(shaderProgram, "useTexture");
        textureLoc = glGetUniformLocation(shaderProgram, "blockTexture");
        fullBrightLoc = glGetUniformLocation(shaderProgram, "fullBright");
    }
    
    private void checkShaderCompilation(int shader, String type) {
        int success = glGetShaderi(shader, GL_COMPILE_STATUS);
        if (success == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            throw new RuntimeException(type + " shader compilation failed: " + log);
        }
    }
    
    private void checkProgramLinking(int program) {
        int success = glGetProgrami(program, GL_LINK_STATUS);
        if (success == GL_FALSE) {
            String log = glGetProgramInfoLog(program);
            throw new RuntimeException("Shader program linking failed: " + log);
        }
    }
    
    private Mesh createCubeMesh() {
        // Cube with normals
        float[] vertices = {
            // Front face (normal: 0, 0, 1)
            -0.5f, -0.5f,  0.5f,  0, 0, 1,
             0.5f, -0.5f,  0.5f,  0, 0, 1,
             0.5f,  0.5f,  0.5f,  0, 0, 1,
            -0.5f,  0.5f,  0.5f,  0, 0, 1,
            // Back face (normal: 0, 0, -1)
             0.5f, -0.5f, -0.5f,  0, 0, -1,
            -0.5f, -0.5f, -0.5f,  0, 0, -1,
            -0.5f,  0.5f, -0.5f,  0, 0, -1,
             0.5f,  0.5f, -0.5f,  0, 0, -1,
            // Top face (normal: 0, 1, 0)
            -0.5f,  0.5f,  0.5f,  0, 1, 0,
             0.5f,  0.5f,  0.5f,  0, 1, 0,
             0.5f,  0.5f, -0.5f,  0, 1, 0,
            -0.5f,  0.5f, -0.5f,  0, 1, 0,
            // Bottom face (normal: 0, -1, 0)
            -0.5f, -0.5f, -0.5f,  0, -1, 0,
             0.5f, -0.5f, -0.5f,  0, -1, 0,
             0.5f, -0.5f,  0.5f,  0, -1, 0,
            -0.5f, -0.5f,  0.5f,  0, -1, 0,
            // Right face (normal: 1, 0, 0)
             0.5f, -0.5f,  0.5f,  1, 0, 0,
             0.5f, -0.5f, -0.5f,  1, 0, 0,
             0.5f,  0.5f, -0.5f,  1, 0, 0,
             0.5f,  0.5f,  0.5f,  1, 0, 0,
            // Left face (normal: -1, 0, 0)
            -0.5f, -0.5f, -0.5f,  -1, 0, 0,
            -0.5f, -0.5f,  0.5f,  -1, 0, 0,
            -0.5f,  0.5f,  0.5f,  -1, 0, 0,
            -0.5f,  0.5f, -0.5f,  -1, 0, 0,
        };
        
        int[] indices = {
            0, 1, 2, 2, 3, 0,       // Front
            4, 5, 6, 6, 7, 4,       // Back
            8, 9, 10, 10, 11, 8,   // Top
            12, 13, 14, 14, 15, 12, // Bottom
            16, 17, 18, 18, 19, 16, // Right
            20, 21, 22, 22, 23, 20  // Left
        };
        
        return new Mesh(vertices, indices);
    }
    
    private Mesh createTexturedCubeMesh() {
        // Cube with normals and texture coordinates (8 floats per vertex)
        float[] vertices = {
            // Front face (normal: 0, 0, 1) - pos(3), normal(3), uv(2)
            -0.5f, -0.5f,  0.5f,  0, 0, 1,  0, 0,
             0.5f, -0.5f,  0.5f,  0, 0, 1,  1, 0,
             0.5f,  0.5f,  0.5f,  0, 0, 1,  1, 1,
            -0.5f,  0.5f,  0.5f,  0, 0, 1,  0, 1,
            // Back face (normal: 0, 0, -1)
             0.5f, -0.5f, -0.5f,  0, 0, -1,  0, 0,
            -0.5f, -0.5f, -0.5f,  0, 0, -1,  1, 0,
            -0.5f,  0.5f, -0.5f,  0, 0, -1,  1, 1,
             0.5f,  0.5f, -0.5f,  0, 0, -1,  0, 1,
            // Top face (normal: 0, 1, 0)
            -0.5f,  0.5f,  0.5f,  0, 1, 0,  0, 0,
             0.5f,  0.5f,  0.5f,  0, 1, 0,  1, 0,
             0.5f,  0.5f, -0.5f,  0, 1, 0,  1, 1,
            -0.5f,  0.5f, -0.5f,  0, 1, 0,  0, 1,
            // Bottom face (normal: 0, -1, 0)
            -0.5f, -0.5f, -0.5f,  0, -1, 0,  0, 0,
             0.5f, -0.5f, -0.5f,  0, -1, 0,  1, 0,
             0.5f, -0.5f,  0.5f,  0, -1, 0,  1, 1,
            -0.5f, -0.5f,  0.5f,  0, -1, 0,  0, 1,
            // Right face (normal: 1, 0, 0)
             0.5f, -0.5f,  0.5f,  1, 0, 0,  0, 0,
             0.5f, -0.5f, -0.5f,  1, 0, 0,  1, 0,
             0.5f,  0.5f, -0.5f,  1, 0, 0,  1, 1,
             0.5f,  0.5f,  0.5f,  1, 0, 0,  0, 1,
            // Left face (normal: -1, 0, 0)
            -0.5f, -0.5f, -0.5f,  -1, 0, 0,  0, 0,
            -0.5f, -0.5f,  0.5f,  -1, 0, 0,  1, 0,
            -0.5f,  0.5f,  0.5f,  -1, 0, 0,  1, 1,
            -0.5f,  0.5f, -0.5f,  -1, 0, 0,  0, 1,
        };
        
        int[] indices = {
            0, 1, 2, 2, 3, 0,       // Front
            4, 5, 6, 6, 7, 4,       // Back
            8, 9, 10, 10, 11, 8,   // Top
            12, 13, 14, 14, 15, 12, // Bottom
            16, 17, 18, 18, 19, 16, // Right
            20, 21, 22, 22, 23, 20  // Left
        };
        
        return new Mesh(vertices, indices, true); // true = has UV coords
    }

    // NPC Head mesh: 64x16 atlas with 4 sections (front, back, side, top)
    // UV layout: front=[0, 0.25], back=[0.25, 0.5], side=[0.5, 0.75], top=[0.75, 1.0]
    private Mesh createNPCHeadMesh() {
        float f0 = 0.0f, f1 = 0.25f;   // front
        float b0 = 0.25f, b1 = 0.5f;   // back
        float s0 = 0.5f, s1 = 0.75f;   // side (left & right)
        float t0 = 0.75f, t1 = 1.0f;   // top (& bottom)

        float[] vertices = {
            // Front face (normal: 0, 0, 1) — FACE texture
            -0.5f, -0.5f,  0.5f,  0, 0, 1,  f0, 0,
             0.5f, -0.5f,  0.5f,  0, 0, 1,  f1, 0,
             0.5f,  0.5f,  0.5f,  0, 0, 1,  f1, 1,
            -0.5f,  0.5f,  0.5f,  0, 0, 1,  f0, 1,
            // Back face (normal: 0, 0, -1) — BACK texture
             0.5f, -0.5f, -0.5f,  0, 0, -1,  b0, 0,
            -0.5f, -0.5f, -0.5f,  0, 0, -1,  b1, 0,
            -0.5f,  0.5f, -0.5f,  0, 0, -1,  b1, 1,
             0.5f,  0.5f, -0.5f,  0, 0, -1,  b0, 1,
            // Top face (normal: 0, 1, 0) — TOP texture (hair)
            -0.5f,  0.5f,  0.5f,  0, 1, 0,  t0, 0,
             0.5f,  0.5f,  0.5f,  0, 1, 0,  t1, 0,
             0.5f,  0.5f, -0.5f,  0, 1, 0,  t1, 1,
            -0.5f,  0.5f, -0.5f,  0, 1, 0,  t0, 1,
            // Bottom face (normal: 0, -1, 0) — TOP texture reused (chin/neck)
            -0.5f, -0.5f, -0.5f,  0, -1, 0,  t0, 0,
             0.5f, -0.5f, -0.5f,  0, -1, 0,  t1, 0,
             0.5f, -0.5f,  0.5f,  0, -1, 0,  t1, 1,
            -0.5f, -0.5f,  0.5f,  0, -1, 0,  t0, 1,
            // Right face (normal: 1, 0, 0) — SIDE texture
             0.5f, -0.5f,  0.5f,  1, 0, 0,  s0, 0,
             0.5f, -0.5f, -0.5f,  1, 0, 0,  s1, 0,
             0.5f,  0.5f, -0.5f,  1, 0, 0,  s1, 1,
             0.5f,  0.5f,  0.5f,  1, 0, 0,  s0, 1,
            // Left face (normal: -1, 0, 0) — SIDE texture (mirrored)
            -0.5f, -0.5f, -0.5f,  -1, 0, 0,  s0, 0,
            -0.5f, -0.5f,  0.5f,  -1, 0, 0,  s1, 0,
            -0.5f,  0.5f,  0.5f,  -1, 0, 0,  s1, 1,
            -0.5f,  0.5f, -0.5f,  -1, 0, 0,  s0, 1,
        };

        int[] indices = {
            0, 1, 2, 2, 3, 0,       // Front
            4, 5, 6, 6, 7, 4,       // Back
            8, 9, 10, 10, 11, 8,    // Top
            12, 13, 14, 14, 15, 12, // Bottom
            16, 17, 18, 18, 19, 16, // Right
            20, 21, 22, 22, 23, 20  // Left
        };

        return new Mesh(vertices, indices, true);
    }

    private Mesh createCylinderMesh(int segments) {
        List<Float> verticesList = new ArrayList<>();
        List<Integer> indicesList = new ArrayList<>();
        
        // Top center
        verticesList.add(0f); verticesList.add(0.5f); verticesList.add(0f);
        verticesList.add(0f); verticesList.add(1f); verticesList.add(0f);
        
        // Bottom center
        verticesList.add(0f); verticesList.add(-0.5f); verticesList.add(0f);
        verticesList.add(0f); verticesList.add(-1f); verticesList.add(0f);
        
        // Side vertices
        for (int i = 0; i <= segments; i++) {
            float angle = (float) (i * 2 * Math.PI / segments);
            float x = (float) Math.cos(angle) * 0.5f;
            float z = (float) Math.sin(angle) * 0.5f;
            float nx = (float) Math.cos(angle);
            float nz = (float) Math.sin(angle);
            
            // Top rim
            verticesList.add(x); verticesList.add(0.5f); verticesList.add(z);
            verticesList.add(0f); verticesList.add(1f); verticesList.add(0f);
            
            // Bottom rim
            verticesList.add(x); verticesList.add(-0.5f); verticesList.add(z);
            verticesList.add(0f); verticesList.add(-1f); verticesList.add(0f);
            
            // Side top
            verticesList.add(x); verticesList.add(0.5f); verticesList.add(z);
            verticesList.add(nx); verticesList.add(0f); verticesList.add(nz);
            
            // Side bottom
            verticesList.add(x); verticesList.add(-0.5f); verticesList.add(z);
            verticesList.add(nx); verticesList.add(0f); verticesList.add(nz);
        }
        
        // Indices for top and bottom caps
        int topCenter = 0;
        int bottomCenter = 1;
        for (int i = 0; i < segments; i++) {
            int baseIdx = 2 + i * 4;
            int nextBaseIdx = 2 + ((i + 1) % (segments + 1)) * 4;
            
            // Top cap
            indicesList.add(topCenter);
            indicesList.add(baseIdx);
            indicesList.add(nextBaseIdx);
            
            // Bottom cap
            indicesList.add(bottomCenter);
            indicesList.add(nextBaseIdx + 1);
            indicesList.add(baseIdx + 1);
            
            // Side
            indicesList.add(baseIdx + 2);
            indicesList.add(baseIdx + 3);
            indicesList.add(nextBaseIdx + 3);
            
            indicesList.add(baseIdx + 2);
            indicesList.add(nextBaseIdx + 3);
            indicesList.add(nextBaseIdx + 2);
        }
        
        float[] vertices = new float[verticesList.size()];
        for (int i = 0; i < verticesList.size(); i++) vertices[i] = verticesList.get(i);
        
        int[] indices = new int[indicesList.size()];
        for (int i = 0; i < indicesList.size(); i++) indices[i] = indicesList.get(i);
        
        return new Mesh(vertices, indices);
    }
    
    private void buildControlRoom() {
        // =====================================================
        // AUTHENTIC CHERNOBYL CONTROL ROOM (Based on reference)
        // =====================================================
        
        int roomWidth = 16;  // Wider room
        int roomDepth = 12;  // Depth of room
        int wallHeight = 6;  // Taller walls
        
        // === FLOOR - Dark checkered slate tiles ===
        for (int x = -roomWidth; x <= roomWidth; x++) {
            for (int z = -roomDepth; z <= roomDepth; z++) {
                String texture = ((x + z) % 2 == 0) ? "slate_tile_dark" : "slate_tile_light";
                addTexturedCube(x * BLOCK_SIZE, 0, z * BLOCK_SIZE, BLOCK_SIZE, 10, BLOCK_SIZE, texture);
            }
        }
        
        // === BACK WALL (+Z) - Main control wall ===
        for (int x = -roomWidth; x <= roomWidth; x++) {
            for (int h = 0; h < wallHeight; h++) {
                addTexturedCube(x * BLOCK_SIZE, h * BLOCK_SIZE + BLOCK_SIZE/2, roomDepth * BLOCK_SIZE,
                       BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, "soviet_gray_wall");
            }
        }

        // === ELENA DISPLAY (Circular reactor fuel rod display) - Center of back wall ===
        int elenaRadius = 4;
        for (int dx = -elenaRadius; dx <= elenaRadius; dx++) {
            for (int dh = -elenaRadius + 3; dh <= elenaRadius - 2; dh++) {
                float dist = (float) Math.sqrt(dx * dx + dh * dh);
                if (dist <= elenaRadius) {
                    addTexturedCube(dx * BLOCK_SIZE, (2 + dh) * BLOCK_SIZE + BLOCK_SIZE/2,
                           (roomDepth - 0.4f) * BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE * 0.3f, "elena_display");
                }
            }
        }
        
        // Right side of Elena - indicator panels
        for (int x = elenaRadius + 2; x <= roomWidth; x++) {
            for (int h = 1; h < wallHeight - 1; h++) {
                String texture = (h % 2 == 0) ? "switch_panel" : "indicator_panel";
                addTexturedCube(x * BLOCK_SIZE, h * BLOCK_SIZE + BLOCK_SIZE/2, (roomDepth - 0.3f) * BLOCK_SIZE,
                       BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE * 0.2f, texture);
            }
        }
        
        // === FRONT WALL (-Z) - With door opening to corridor ===
        // Door opening at x = -2 to 2 (4 blocks wide), h = 0 to 5 (5 blocks tall)
        for (int x = -roomWidth; x <= roomWidth; x++) {
            for (int h = 0; h < wallHeight; h++) {
                boolean isDoorOpening = (x >= -2 && x <= 2 && h < 5);
                if (!isDoorOpening) {
                    addTexturedCube(x * BLOCK_SIZE, h * BLOCK_SIZE + BLOCK_SIZE/2, -roomDepth * BLOCK_SIZE,
                           BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, "soviet_gray_wall");
                }
            }
        }
        // Door frame accent (dark metal around the opening)
        addTexturedCube(-2.5f * BLOCK_SIZE, 2.5f * BLOCK_SIZE, -roomDepth * BLOCK_SIZE,
               BLOCK_SIZE * 0.3f, 5 * BLOCK_SIZE, BLOCK_SIZE * 0.3f, "iron_block");
        addTexturedCube(2.5f * BLOCK_SIZE, 2.5f * BLOCK_SIZE, -roomDepth * BLOCK_SIZE,
               BLOCK_SIZE * 0.3f, 5 * BLOCK_SIZE, BLOCK_SIZE * 0.3f, "iron_block");
        addTexturedCube(0, 5f * BLOCK_SIZE + BLOCK_SIZE/2, -roomDepth * BLOCK_SIZE,
               5 * BLOCK_SIZE, BLOCK_SIZE * 0.3f, BLOCK_SIZE * 0.3f, "iron_block");
        
        // === LEFT WALL (-X) - With observation window ===
        int windowStartZ = -4;  // Window start position
        int windowEndZ = 4;     // Window end position  
        
        for (int z = -roomDepth; z <= roomDepth; z++) {
            for (int h = 0; h < wallHeight; h++) {
                boolean isWindowArea = (z >= windowStartZ && z <= windowEndZ
                    && h >= CONTROL_ROOM_WINDOW_START_H && h < CONTROL_ROOM_WINDOW_END_H);
                
                if (isWindowArea) {
                    // Transparent glass pane
                    addTexturedCube(-roomWidth * BLOCK_SIZE, h * BLOCK_SIZE + BLOCK_SIZE/2, z * BLOCK_SIZE,
                           BLOCK_SIZE * 0.1f, BLOCK_SIZE, BLOCK_SIZE, "glass");
                } else {
                    addTexturedCube(-roomWidth * BLOCK_SIZE, h * BLOCK_SIZE + BLOCK_SIZE/2, z * BLOCK_SIZE,
                           BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, "soviet_gray_wall");
                }
            }
        }
        
        // === RIGHT WALL (+X) - Plain gray wall ===
        for (int z = -roomDepth; z <= roomDepth; z++) {
            for (int h = 0; h < wallHeight; h++) {
                addTexturedCube(roomWidth * BLOCK_SIZE, h * BLOCK_SIZE + BLOCK_SIZE/2, z * BLOCK_SIZE,
                       BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, "soviet_gray_wall");
            }
        }
        
        // === MAIN CONTROL DESK - Pushed to FRONT of room (facing back wall) ===
        float deskHeight = BLOCK_SIZE * 0.9f;
        float deskY = BLOCK_SIZE * 0.45f;
        float deskZ = -6 * BLOCK_SIZE; // Moved to front area
        
        // Main desk body (cream colored)
        for (int x = -10; x <= 10; x++) {
            // Front panel of desk
            addTexturedCube(x * BLOCK_SIZE, deskY, deskZ, BLOCK_SIZE, deskHeight, BLOCK_SIZE * 1.5f, "control_panel_cream");
            
            // Desk top surface with controls
            addTexturedCube(x * BLOCK_SIZE, deskHeight + 5, deskZ, BLOCK_SIZE, 10, BLOCK_SIZE * 1.5f, "control_panel_gray");
        }
        
        // === BUTTON PANELS on desk ===
        // Section 1A - Left side buttons
        for (int x = -10; x <= -7; x++) {
            addTexturedCube(x * BLOCK_SIZE, deskHeight + 15, deskZ, BLOCK_SIZE, BLOCK_SIZE * 0.3f, BLOCK_SIZE, "button_panel_mixed");
        }
        
        // Section 2A-3A - Center left with switch panels
        for (int x = -6; x <= -3; x++) {
            addTexturedCube(x * BLOCK_SIZE, deskHeight + 15, deskZ, BLOCK_SIZE, BLOCK_SIZE * 0.3f, BLOCK_SIZE, "switch_panel");
        }
        
        // Section 4A - Center (main reactor controls)
        for (int x = -2; x <= 2; x++) {
            addTexturedCube(x * BLOCK_SIZE, deskHeight + 15, deskZ, BLOCK_SIZE, BLOCK_SIZE * 0.3f, BLOCK_SIZE, "button_panel_green");
        }
        
        // Section 5A - Center right
        for (int x = 3; x <= 6; x++) {
            addTexturedCube(x * BLOCK_SIZE, deskHeight + 15, deskZ, BLOCK_SIZE, BLOCK_SIZE * 0.3f, BLOCK_SIZE, "button_panel_mixed");
        }
        
        // Section 6A - Right side
        for (int x = 7; x <= 10; x++) {
            addTexturedCube(x * BLOCK_SIZE, deskHeight + 15, deskZ, BLOCK_SIZE, BLOCK_SIZE * 0.3f, BLOCK_SIZE, "switch_panel");
        }
        
        // === SECONDARY CONTROL PANELS (along right wall) ===
        float secondaryX = (roomWidth - 1.5f) * BLOCK_SIZE;  // Against right wall
        for (int z = -8; z <= 8; z += 4) {
            // Upright panel section - facing into the room
            addTexturedCube(secondaryX, BLOCK_SIZE * 2, z * BLOCK_SIZE, BLOCK_SIZE * 0.5f, BLOCK_SIZE * 3, BLOCK_SIZE * 2, "control_panel_cream");
            // Button section on panel (facing left, into room)
            addTexturedCube(secondaryX - BLOCK_SIZE * 0.3f, BLOCK_SIZE * 2.5f, z * BLOCK_SIZE, BLOCK_SIZE * 0.3f, BLOCK_SIZE * 2, BLOCK_SIZE * 1.5f, "indicator_panel");
        }
        
        // === MONITORS on secondary panels ===
        addTexturedCube(secondaryX - BLOCK_SIZE * 0.3f, BLOCK_SIZE * 2, -6 * BLOCK_SIZE, BLOCK_SIZE * 0.3f, BLOCK_SIZE, BLOCK_SIZE, "monitor_screen");
        addTexturedCube(secondaryX - BLOCK_SIZE * 0.3f, BLOCK_SIZE * 2, 6 * BLOCK_SIZE, BLOCK_SIZE * 0.3f, BLOCK_SIZE, BLOCK_SIZE, "monitor_screen");
        
        // === SOVIET ENGINEER TABLE (long table running through center like in reference) ===
        float tableY = BLOCK_SIZE * 0.9f;  // Taller table
        float tableX = 0;  // Centered in room
        float tableZ = 4 * BLOCK_SIZE;  // Moved towards Elena display
        // Long gray table top 
        addTexturedCube(tableX, tableY, tableZ, BLOCK_SIZE * 2.5f, BLOCK_SIZE * 0.1f, BLOCK_SIZE * 6, "light_gray_concrete");
        // Dark wood trim around edges
        addTexturedCube(tableX - BLOCK_SIZE * 1.2f, tableY, tableZ, BLOCK_SIZE * 0.15f, BLOCK_SIZE * 0.12f, BLOCK_SIZE * 6, "dark_oak_planks");
        addTexturedCube(tableX + BLOCK_SIZE * 1.2f, tableY, tableZ, BLOCK_SIZE * 0.15f, BLOCK_SIZE * 0.12f, BLOCK_SIZE * 6, "dark_oak_planks");
        addTexturedCube(tableX, tableY, tableZ - 3 * BLOCK_SIZE, BLOCK_SIZE * 2.5f, BLOCK_SIZE * 0.12f, BLOCK_SIZE * 0.15f, "dark_oak_planks");
        addTexturedCube(tableX, tableY, tableZ + 3 * BLOCK_SIZE, BLOCK_SIZE * 2.5f, BLOCK_SIZE * 0.12f, BLOCK_SIZE * 0.15f, "dark_oak_planks");
        // Table support panels (solid sides like in image)
        addTexturedCube(tableX, tableY * 0.5f, tableZ - 2 * BLOCK_SIZE, BLOCK_SIZE * 2.3f, tableY, BLOCK_SIZE * 0.2f, "gray_concrete");
        addTexturedCube(tableX, tableY * 0.5f, tableZ + 2 * BLOCK_SIZE, BLOCK_SIZE * 2.3f, tableY, BLOCK_SIZE * 0.2f, "gray_concrete");
        addTexturedCube(tableX, tableY * 0.5f, tableZ, BLOCK_SIZE * 2.3f, tableY, BLOCK_SIZE * 0.2f, "gray_concrete");
        // Papers/documents scattered on table
        addTexturedCube(tableX - BLOCK_SIZE * 0.5f, tableY + BLOCK_SIZE * 0.08f, tableZ - 1.5f * BLOCK_SIZE, BLOCK_SIZE * 0.5f, BLOCK_SIZE * 0.02f, BLOCK_SIZE * 0.35f, "white_concrete");
        addTexturedCube(tableX + BLOCK_SIZE * 0.3f, tableY + BLOCK_SIZE * 0.08f, tableZ - 0.5f * BLOCK_SIZE, BLOCK_SIZE * 0.4f, BLOCK_SIZE * 0.02f, BLOCK_SIZE * 0.3f, "white_concrete");
        addTexturedCube(tableX - BLOCK_SIZE * 0.2f, tableY + BLOCK_SIZE * 0.08f, tableZ + 1 * BLOCK_SIZE, BLOCK_SIZE * 0.6f, BLOCK_SIZE * 0.02f, BLOCK_SIZE * 0.4f, "white_concrete");
        addTexturedCube(tableX + BLOCK_SIZE * 0.5f, tableY + BLOCK_SIZE * 0.08f, tableZ + 2 * BLOCK_SIZE, BLOCK_SIZE * 0.45f, BLOCK_SIZE * 0.02f, BLOCK_SIZE * 0.35f, "white_concrete");
        // Red desk lamp (iconic Soviet style)
        addTexturedCube(tableX + BLOCK_SIZE * 0.8f, tableY + BLOCK_SIZE * 0.2f, tableZ + 0.5f * BLOCK_SIZE, BLOCK_SIZE * 0.15f, BLOCK_SIZE * 0.3f, BLOCK_SIZE * 0.15f, "redstone_block");
        addTexturedCube(tableX + BLOCK_SIZE * 0.8f, tableY + BLOCK_SIZE * 0.4f, tableZ + 0.5f * BLOCK_SIZE, BLOCK_SIZE * 0.25f, BLOCK_SIZE * 0.1f, BLOCK_SIZE * 0.2f, "redstone_block");
        // Telephones (gray Soviet phones)
        addTexturedCube(tableX - BLOCK_SIZE * 0.7f, tableY + BLOCK_SIZE * 0.1f, tableZ - 0.5f * BLOCK_SIZE, BLOCK_SIZE * 0.2f, BLOCK_SIZE * 0.08f, BLOCK_SIZE * 0.12f, "gray_concrete");
        addTexturedCube(tableX + BLOCK_SIZE * 0.4f, tableY + BLOCK_SIZE * 0.1f, tableZ + 1.5f * BLOCK_SIZE, BLOCK_SIZE * 0.2f, BLOCK_SIZE * 0.08f, BLOCK_SIZE * 0.12f, "gray_concrete");
        // Green desk lamp on other end
        addTexturedCube(tableX - BLOCK_SIZE * 0.6f, tableY + BLOCK_SIZE * 0.15f, tableZ - 2 * BLOCK_SIZE, BLOCK_SIZE * 0.12f, BLOCK_SIZE * 0.25f, BLOCK_SIZE * 0.12f, "green_concrete");
        
        // === CHAIRS around the table (scaled up) ===
        float chairY = BLOCK_SIZE * 0.55f;  // Taller chairs
        float chairSeatSize = BLOCK_SIZE * 0.7f;  // Bigger seat
        // Left side chairs (facing table)
        for (int cz = -2; cz <= 2; cz += 2) {
            float chairZ = tableZ + cz * BLOCK_SIZE;
            float chairX = tableX - BLOCK_SIZE * 2.2f;
            // Seat
            addTexturedCube(chairX, chairY, chairZ, chairSeatSize, BLOCK_SIZE * 0.1f, chairSeatSize, "dark_oak_planks");
            // Back rest
            addTexturedCube(chairX - BLOCK_SIZE * 0.3f, chairY + BLOCK_SIZE * 0.5f, chairZ, BLOCK_SIZE * 0.12f, BLOCK_SIZE * 0.7f, chairSeatSize * 0.9f, "dark_oak_planks");
            // Legs
            addTexturedCube(chairX + BLOCK_SIZE * 0.25f, chairY * 0.5f, chairZ - BLOCK_SIZE * 0.25f, BLOCK_SIZE * 0.1f, chairY, BLOCK_SIZE * 0.1f, "iron_block");
            addTexturedCube(chairX + BLOCK_SIZE * 0.25f, chairY * 0.5f, chairZ + BLOCK_SIZE * 0.25f, BLOCK_SIZE * 0.1f, chairY, BLOCK_SIZE * 0.1f, "iron_block");
            addTexturedCube(chairX - BLOCK_SIZE * 0.25f, chairY * 0.5f, chairZ - BLOCK_SIZE * 0.25f, BLOCK_SIZE * 0.1f, chairY, BLOCK_SIZE * 0.1f, "iron_block");
            addTexturedCube(chairX - BLOCK_SIZE * 0.25f, chairY * 0.5f, chairZ + BLOCK_SIZE * 0.25f, BLOCK_SIZE * 0.1f, chairY, BLOCK_SIZE * 0.1f, "iron_block");
        }
        // Right side chairs (facing table)
        for (int cz = -2; cz <= 2; cz += 2) {
            float chairZ = tableZ + cz * BLOCK_SIZE;
            float chairX = tableX + BLOCK_SIZE * 2.2f;
            // Seat
            addTexturedCube(chairX, chairY, chairZ, chairSeatSize, BLOCK_SIZE * 0.1f, chairSeatSize, "dark_oak_planks");
            // Back rest
            addTexturedCube(chairX + BLOCK_SIZE * 0.3f, chairY + BLOCK_SIZE * 0.5f, chairZ, BLOCK_SIZE * 0.12f, BLOCK_SIZE * 0.7f, chairSeatSize * 0.9f, "dark_oak_planks");
            // Legs
            addTexturedCube(chairX + BLOCK_SIZE * 0.25f, chairY * 0.5f, chairZ - BLOCK_SIZE * 0.25f, BLOCK_SIZE * 0.1f, chairY, BLOCK_SIZE * 0.1f, "iron_block");
            addTexturedCube(chairX + BLOCK_SIZE * 0.25f, chairY * 0.5f, chairZ + BLOCK_SIZE * 0.25f, BLOCK_SIZE * 0.1f, chairY, BLOCK_SIZE * 0.1f, "iron_block");
            addTexturedCube(chairX - BLOCK_SIZE * 0.25f, chairY * 0.5f, chairZ - BLOCK_SIZE * 0.25f, BLOCK_SIZE * 0.1f, chairY, BLOCK_SIZE * 0.1f, "iron_block");
            addTexturedCube(chairX - BLOCK_SIZE * 0.25f, chairY * 0.5f, chairZ + BLOCK_SIZE * 0.25f, BLOCK_SIZE * 0.1f, chairY, BLOCK_SIZE * 0.1f, "iron_block");
        }
        
        // === CEILING with drop ceiling tiles and fluorescent lights ===
        for (int x = -roomWidth; x <= roomWidth; x++) {
            for (int z = -roomDepth; z <= roomDepth; z++) {
                // Fluorescent light pattern (rectangular lights)
                boolean isLight = (Math.abs(x) % 6 <= 1) && (Math.abs(z) % 6 <= 1);
                String texture = isLight ? "fluorescent_light" : "ceiling_tile";
                addTexturedCube(x * BLOCK_SIZE, wallHeight * BLOCK_SIZE + BLOCK_SIZE/2, z * BLOCK_SIZE,
                       BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, texture);
            }
        }

        // === Alarm beacons mounted near ceiling corners and doorway ===
        float sirenHeight = wallHeight * BLOCK_SIZE - 40f;
        float inset = BLOCK_SIZE * 1.5f;
        createAlarmLight(-roomWidth * BLOCK_SIZE + inset, sirenHeight, roomDepth * BLOCK_SIZE - inset);
        createAlarmLight(roomWidth * BLOCK_SIZE - inset, sirenHeight, roomDepth * BLOCK_SIZE - inset);
        createAlarmLight(-roomWidth * BLOCK_SIZE + inset, sirenHeight, -roomDepth * BLOCK_SIZE + inset);
        createAlarmLight(roomWidth * BLOCK_SIZE - inset, sirenHeight, -roomDepth * BLOCK_SIZE + inset);
        
        // === LABELS on desk sections (red labels like 1A, 2A, etc.) ===
        float labelY = deskHeight + BLOCK_SIZE * 0.5f;
        for (int section = 0; section < 6; section++) {
            int x = -9 + section * 3;
            addTexturedCube(x * BLOCK_SIZE, labelY, deskZ - BLOCK_SIZE * 0.5f, BLOCK_SIZE * 0.5f, BLOCK_SIZE * 0.2f, BLOCK_SIZE * 0.3f, "redstone_block");
        }
        
        // === NPC ENGINEERS ===
        // Aleksandr Akimov (shift supervisor) - standing next to indicator panels on right wall
        createNPCEngineer("Akimov", 
            11 * BLOCK_SIZE,   // x: near secondary indicator panels (right wall)
            0,                  // y: floor level  
            0,                  // z: center of room
            -1.57f,            // facing: towards center of room (negative X)
            "akimov_head", "akimov_body", "akimov_legs", "engineer_arms");
        
        // Leonid Toptunov (senior reactor control engineer) - beside the engineer table, near ELENA
        createNPCEngineer("Toptunov",
            -3.5f * BLOCK_SIZE, // x: beside the table (table edge at x=-125)
            0,                   // y: floor level
            5 * BLOCK_SIZE,     // z: near the ELENA display end
            1.57f,              // facing: towards the table (positive X)
            "toptunov_head", "toptunov_body", "toptunov_legs", "toptunov_arms");

        // Anatoly Dyatlov (deputy chief engineer) - standing behind the control desk, supervising
        createNPCEngineer("Dyatlov",
            2 * BLOCK_SIZE,     // x: slightly right of center
            0,                   // y: floor level
            -7.5f * BLOCK_SIZE, // z: behind the control desk (desk at z=-300)
            0.0f,               // facing: towards the desk (positive Z)
            "dyatlov_head", "dyatlov_body", "dyatlov_legs", "dyatlov_arms");
        
        // Build reactor hall visible through window
        buildReactorHall();
        
        // Build corridor and walkable reactor hall (Room 2)
        buildCorridorAndReactorHall();
    }
    
    private void buildReactorHall() {
        // Reactor hall is to the LEFT of control room, at a LOWER level
        // Visible through the window on the left wall (left wall is at x = -16 * BLOCK_SIZE)
        float hallX = REACTOR_HALL_WINDOW_X;  // Starts right at the window
        float hallY = REACTOR_HALL_FLOOR_Y;  // Much lower floor (control room is at y=0)
        float hallZ = 0f;  // Centered with window

        int hallWidth = REACTOR_HALL_WIDTH_BLOCKS;
        int hallDepth = REACTOR_HALL_DEPTH_BLOCKS;
        int hallEntranceZ = -REACTOR_HALL_DEPTH_BLOCKS;
        int baseHallHeight = REACTOR_HALL_BASE_HEIGHT_BLOCKS;
        int tallHallHeight = REACTOR_HALL_TALL_HEIGHT_BLOCKS;
        float ceilingY = hallY + tallHallHeight * BLOCK_SIZE;
        float mezzanineY = REACTOR_HALL_MEZZANINE_Y;

        // === REACTOR HALL FLOOR ===
        for (int x = 0; x < hallWidth; x++) {
            for (int z = -hallDepth; z <= hallDepth; z++) {
                addTexturedCube(hallX - x * BLOCK_SIZE, hallY, hallZ + z * BLOCK_SIZE,
                       BLOCK_SIZE, 10, BLOCK_SIZE, "gray_concrete");
            }
        }

        // === REACTOR TOP (Upper Biological Shield - "Elena") ===
        float reactorCenterX = hallX - (hallWidth * 0.5f) * BLOCK_SIZE;  // Centered deeper in enlarged hall
        float reactorTopY = hallY + BLOCK_SIZE * 0.45f;  // Slightly raised platform
        int reactorRadius = 12;
        int guardRadius = reactorRadius + 6;

        Random capRand = new Random(1986);  // Chernobyl year for deterministic pattern

        int shieldRadius = (guardRadius - 1);
        for (int dx = -shieldRadius; dx <= shieldRadius; dx++) {
            for (int dz = -shieldRadius; dz <= shieldRadius; dz++) {
                float distBlocks = (float)Math.sqrt(dx * dx + dz * dz);
                float worldX = reactorCenterX + dx * BLOCK_SIZE * 0.8f;
                float worldZ = hallZ + dz * BLOCK_SIZE * 0.8f;

                if (distBlocks <= shieldRadius) {
                    String shieldTex = (distBlocks > shieldRadius - 1) ? "reactor_step_metal" : "reactor_floor_metal";
                    addTexturedCube(worldX,
                           hallY + BLOCK_SIZE * 0.25f,
                           worldZ,
                           BLOCK_SIZE * 0.8f, BLOCK_SIZE * 0.25f, BLOCK_SIZE * 0.8f, shieldTex);
                }

                if (distBlocks <= reactorRadius + 0.5f) {
                    String blanketTex = (distBlocks > reactorRadius - 1) ? "hazard_stripe" : "reactor_floor_metal";
                    addTexturedCube(worldX,
                           hallY + BLOCK_SIZE * 0.55f,
                           worldZ,
                           BLOCK_SIZE * 0.7f, BLOCK_SIZE * 0.14f, BLOCK_SIZE * 0.7f, blanketTex);
                }
            }
        }

        // Dense grid of fuel channel caps filling the fenced platform
        float channelSpacing = BLOCK_SIZE * 0.85f;
        int channelRadius = 6;
        for (int gx = -channelRadius; gx <= channelRadius; gx++) {
            for (int gz = -channelRadius; gz <= channelRadius; gz++) {
                float px = reactorCenterX + gx * channelSpacing;
                float pz = hallZ + gz * channelSpacing;
                float distToCenter = (float)Math.sqrt(Math.pow(px - reactorCenterX, 2) + Math.pow(pz - hallZ, 2));
                if (distToCenter > (reactorRadius - 1.2f) * BLOCK_SIZE * 0.8f) continue;

                addTexturedCube(px, reactorTopY + BLOCK_SIZE * 0.05f, pz,
                       BLOCK_SIZE * 0.3f, BLOCK_SIZE * 0.1f, BLOCK_SIZE * 0.3f, "reactor_floor_metal");

                float chance = capRand.nextFloat();
                String capTexture;
                boolean axialLine = (gx == 0 || gz == 0 || Math.abs(gx) == Math.abs(gz));
                if (axialLine && chance < 0.45f) {
                    capTexture = "fuel_cap_yellow";
                } else if (chance < 0.08f) {
                    capTexture = "fuel_cap_red";
                } else if (chance < 0.16f) {
                    capTexture = "fuel_cap_blue";
                } else if (chance < 0.3f) {
                    capTexture = "fuel_cap_yellow";
                } else {
                    capTexture = "fuel_cap_gray";
                }

                float capY = reactorTopY + BLOCK_SIZE * 0.18f;
                TexturedGameObject rodObj = addTexturedCubeAndReturn(px,
                       capY,
                       pz,
                       BLOCK_SIZE * 0.25f, BLOCK_SIZE * 0.18f, BLOCK_SIZE * 0.25f, capTexture);

                float speed = 0.4f + capRand.nextFloat() * 1.6f;  // 0.4 to 2.0 Hz
                float phase = capRand.nextFloat() * (float)Math.PI * 2;
                float amplitude = BLOCK_SIZE * 0.04f + capRand.nextFloat() * BLOCK_SIZE * 0.12f;
                fuelRodAnimations.add(new FuelRodAnimation(rodObj, capY, speed, phase, amplitude));
            }
        }

        // Central graphite stack and control rod drive columns
        addTexturedCube(reactorCenterX,
               reactorTopY + BLOCK_SIZE * 0.7f,
               hallZ,
               BLOCK_SIZE * 1.4f, BLOCK_SIZE * 1.2f, BLOCK_SIZE * 1.4f, "iron_block");
        addTexturedCube(reactorCenterX,
               reactorTopY + BLOCK_SIZE * 1.15f,
               hallZ,
               BLOCK_SIZE * 1.8f, BLOCK_SIZE * 0.25f, BLOCK_SIZE * 1.8f, "hazard_stripe");
        for (int i = 0; i < 4; i++) {
            double angle = i * Math.PI / 2.0;
            float dirX = (float)Math.cos(angle);
            float dirZ = (float)Math.sin(angle);
            float colX = reactorCenterX + dirX * BLOCK_SIZE * 4.5f;
            float colZ = hallZ + dirZ * BLOCK_SIZE * 4.5f;
            addTexturedCube(colX,
                   reactorTopY + BLOCK_SIZE * 0.9f,
                   colZ,
                   BLOCK_SIZE * 0.6f, BLOCK_SIZE * 1.6f, BLOCK_SIZE * 0.6f, "iron_block");
            addTexturedCube(colX,
                   reactorTopY + BLOCK_SIZE * 1.5f,
                   colZ,
                   BLOCK_SIZE * 0.7f, BLOCK_SIZE * 0.2f, BLOCK_SIZE * 0.7f, "reactor_floor_metal");
        }

        // Radial coolant pipes crisscrossing the biological shield
        for (int i = 0; i < 4; i++) {
            double angle = (Math.PI / 4.0) + i * (Math.PI / 2.0);
            float dirX = (float)Math.cos(angle);
            float dirZ = (float)Math.sin(angle);
            addTexturedCube(reactorCenterX + dirX * BLOCK_SIZE * 2.5f,
                   reactorTopY + BLOCK_SIZE * 0.35f,
                   hallZ + dirZ * BLOCK_SIZE * 2.5f,
                   BLOCK_SIZE * 0.5f, BLOCK_SIZE * 0.2f, BLOCK_SIZE * 3.5f, "cable_tray");
        }

        // === SAFETY FENCE around reactor ===
        float guardOffset = guardRadius * BLOCK_SIZE * 0.8f;
        float guardHeight = BLOCK_SIZE * 1.6f;
        for (int dx = -guardRadius; dx <= guardRadius; dx++) {
            float px = reactorCenterX + dx * BLOCK_SIZE * 0.8f;
            addTexturedCube(px, hallY + guardHeight / 2f, hallZ + guardOffset,
                   BLOCK_SIZE * 0.12f, guardHeight, BLOCK_SIZE * 0.12f, "iron_block");
            addTexturedCube(px, hallY + guardHeight / 2f, hallZ - guardOffset,
                   BLOCK_SIZE * 0.12f, guardHeight, BLOCK_SIZE * 0.12f, "iron_block");
        }
        for (int dz = -guardRadius; dz <= guardRadius; dz++) {
            float pz = hallZ + dz * BLOCK_SIZE * 0.8f;
            addTexturedCube(reactorCenterX + guardOffset, hallY + guardHeight / 2f, pz,
                   BLOCK_SIZE * 0.12f, guardHeight, BLOCK_SIZE * 0.12f, "iron_block");
            addTexturedCube(reactorCenterX - guardOffset, hallY + guardHeight / 2f, pz,
                   BLOCK_SIZE * 0.12f, guardHeight, BLOCK_SIZE * 0.12f, "iron_block");
        }
        float railSpan = guardOffset * 2f + BLOCK_SIZE * 0.5f;
        addTexturedCube(reactorCenterX, hallY + guardHeight + BLOCK_SIZE * 0.05f, hallZ + guardOffset,
               railSpan, BLOCK_SIZE * 0.08f, BLOCK_SIZE * 0.08f, "iron_block");
        addTexturedCube(reactorCenterX, hallY + guardHeight + BLOCK_SIZE * 0.05f, hallZ - guardOffset,
               railSpan, BLOCK_SIZE * 0.08f, BLOCK_SIZE * 0.08f, "iron_block");
        addTexturedCube(reactorCenterX + guardOffset, hallY + guardHeight + BLOCK_SIZE * 0.05f, hallZ,
               BLOCK_SIZE * 0.08f, BLOCK_SIZE * 0.08f, railSpan, "iron_block");
        addTexturedCube(reactorCenterX - guardOffset, hallY + guardHeight + BLOCK_SIZE * 0.05f, hallZ,
               BLOCK_SIZE * 0.08f, BLOCK_SIZE * 0.08f, railSpan, "iron_block");

        // === MEZZANINE CATWALK ===
        int catwalkWidth = REACTOR_HALL_CATWALK_WIDTH_BLOCKS;
        for (int x = 0; x < hallWidth; x++) {
            for (int w = 0; w < catwalkWidth; w++) {
                float zFront = hallZ + (hallDepth - w) * BLOCK_SIZE;
                float zBack = hallZ - (hallDepth - w) * BLOCK_SIZE;
                boolean skipDoorOverhang = (w == catwalkWidth - 1 && x >= 1 && x <= 4);
                if (!skipDoorOverhang) {
                    addTexturedCube(hallX - x * BLOCK_SIZE, mezzanineY, zFront,
                           BLOCK_SIZE, 10, BLOCK_SIZE, "reactor_floor_metal");
                }
                addTexturedCube(hallX - x * BLOCK_SIZE, mezzanineY, zBack,
                       BLOCK_SIZE, 10, BLOCK_SIZE, "reactor_floor_metal");
            }
        }
        for (int z = -hallDepth + 1; z <= hallDepth - 1; z++) {
            for (int w = 0; w < catwalkWidth; w++) {
                addTexturedCube(hallX - w * BLOCK_SIZE, mezzanineY, hallZ + z * BLOCK_SIZE,
                       BLOCK_SIZE, 10, BLOCK_SIZE, "reactor_floor_metal");
                addTexturedCube(hallX - (hallWidth - 1 - w) * BLOCK_SIZE, mezzanineY, hallZ + z * BLOCK_SIZE,
                       BLOCK_SIZE, 10, BLOCK_SIZE, "reactor_floor_metal");
            }
        }

        // Cover the observation booth notch above the corridor-facing wall
        float frontZ = hallZ + (hallDepth - (catwalkWidth - 1)) * BLOCK_SIZE;
        for (int x = 1; x <= 4; x++) {
            addTexturedCube(hallX - x * BLOCK_SIZE,
                   mezzanineY,
                   frontZ,
                   BLOCK_SIZE, 10, BLOCK_SIZE, "reactor_floor_metal");
        }

        // Catwalk railings
        float railingHeight = BLOCK_SIZE * 1.2f;
        for (int x = 0; x < hallWidth; x++) {
            addTexturedCube(hallX - x * BLOCK_SIZE, mezzanineY + railingHeight / 2f,
                   hallZ + (hallDepth - catwalkWidth + 0.2f) * BLOCK_SIZE,
                   BLOCK_SIZE * 0.15f, railingHeight, BLOCK_SIZE * 0.15f, "iron_block");
            addTexturedCube(hallX - x * BLOCK_SIZE, mezzanineY + railingHeight / 2f,
                   hallZ - (hallDepth - catwalkWidth + 0.2f) * BLOCK_SIZE,
                   BLOCK_SIZE * 0.15f, railingHeight, BLOCK_SIZE * 0.15f, "iron_block");
        }
        for (int z = -hallDepth; z <= hallDepth; z++) {
            addTexturedCube(hallX - (catwalkWidth - 0.2f) * BLOCK_SIZE, mezzanineY + railingHeight / 2f, hallZ + z * BLOCK_SIZE,
                   BLOCK_SIZE * 0.15f, railingHeight, BLOCK_SIZE * 0.15f, "iron_block");
            addTexturedCube(hallX - (hallWidth - catwalkWidth + 0.2f) * BLOCK_SIZE, mezzanineY + railingHeight / 2f, hallZ + z * BLOCK_SIZE,
                   BLOCK_SIZE * 0.15f, railingHeight, BLOCK_SIZE * 0.15f, "iron_block");
        }

         // === STAIRS TO CATWALK (visible access) ===
         int totalSteps = REACTOR_HALL_STAIRS_STEPS;
         float stairWidth = REACTOR_HALL_STAIRS_WIDTH;
         float stairRun = REACTOR_HALL_STAIRS_RUN;
         float stairBaseX = REACTOR_HALL_STAIRS_BASE_X;
         float stairBaseZ = hallZ + REACTOR_HALL_STAIRS_BASE_Z;
         float stairBaseY = REACTOR_HALL_STAIRS_BASE_Y;
         float stepRise = (mezzanineY - stairBaseY) / totalSteps;
         for (int step = 0; step < totalSteps; step++) {
             float centerY = stairBaseY + stepRise * (step + 0.5f);
             float centerZ = stairBaseZ - stairRun * step;
             addTexturedCube(stairBaseX, centerY, centerZ,
                 stairWidth, stepRise * 0.95f, stairRun * 0.95f, "reactor_floor_metal");

             for (int side = -1; side <= 1; side += 2) {
              float railX = stairBaseX + side * (stairWidth / 2f - BLOCK_SIZE * 0.25f);
              addTexturedCube(railX, centerY + stepRise * 0.2f, centerZ,
                  BLOCK_SIZE * 0.12f, stepRise + BLOCK_SIZE * 0.4f, BLOCK_SIZE * 0.12f, "iron_block");
             }
         }
         addTexturedCube(stairBaseX,
             mezzanineY + BLOCK_SIZE * 0.05f,
             stairBaseZ - stairRun * totalSteps - BLOCK_SIZE * 0.5f,
             stairWidth, 10, BLOCK_SIZE * 1.6f, "reactor_floor_metal");

        // === REACTOR HALL WALLS (extra tall) ===
        for (int z = -hallDepth; z <= hallDepth; z++) {
            for (int h = 0; h < tallHallHeight; h++) {
                float blockCenterY = hallY + h * BLOCK_SIZE;
                float blockBottom = blockCenterY - BLOCK_SIZE * 0.5f;
                float blockTop = blockBottom + BLOCK_SIZE;
                boolean overlapsControlRoomView = (blockTop > CONTROL_ROOM_WINDOW_BOTTOM_WORLD_Y
                    && blockBottom < CONTROL_ROOM_WINDOW_TOP_WORLD_Y);
                String wallTexture = overlapsControlRoomView ? "glass" : "reactor_wall_white";
                addTexturedCube(hallX - hallWidth * BLOCK_SIZE, blockCenterY, hallZ + z * BLOCK_SIZE,
                       BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, wallTexture);
            }
        }

        final int walkwayDoorHeightBlocks = 5; // keep entry usable while sealing the visible gap above it
        for (int x = 0; x < hallWidth; x++) {
            for (int h = 0; h < tallHallHeight; h++) {
                boolean isWalkwayDoor = (x >= 1 && x <= 3 && h < walkwayDoorHeightBlocks);
                  if (!isWalkwayDoor) {
                      addTexturedCube(hallX - x * BLOCK_SIZE, hallY + h * BLOCK_SIZE, hallZ - hallDepth * BLOCK_SIZE,
                          BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, "reactor_wall_white");
                  }
                  addTexturedCube(hallX - x * BLOCK_SIZE, hallY + h * BLOCK_SIZE, hallZ + hallDepth * BLOCK_SIZE,
                      BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, "glass");
            }
        }

        // === DOME RING (structural rim) ===
        for (int x = 0; x < hallWidth; x++) {
            for (int z = -hallDepth; z <= hallDepth; z++) {
                addTexturedCube(hallX - x * BLOCK_SIZE, ceilingY, hallZ + z * BLOCK_SIZE,
                       BLOCK_SIZE, BLOCK_SIZE * 0.4f, BLOCK_SIZE, "gray_concrete");
            }
        }

        // === GLASS DOME OPEN TO SKY ===
        int domeRadiusBlocks = 12;
        float hallCenterX = hallX - (hallWidth / 2f) * BLOCK_SIZE;
        for (int dx = -domeRadiusBlocks; dx <= domeRadiusBlocks; dx++) {
            for (int dz = -domeRadiusBlocks; dz <= domeRadiusBlocks; dz++) {
                float dist = (float)Math.sqrt(dx * dx + dz * dz);
                if (dist > domeRadiusBlocks) continue;
                float heightBlocks = (float)Math.sqrt(Math.max(0f, domeRadiusBlocks * domeRadiusBlocks - dist * dist));
                float y = ceilingY + heightBlocks * BLOCK_SIZE * 0.5f;
                addTexturedCube(hallCenterX + dx * BLOCK_SIZE, y, hallZ + dz * BLOCK_SIZE,
                       BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, "glass");
            }
        }

         // Moon + stars for the glass dome view
         float moonY = ceilingY + domeRadiusBlocks * BLOCK_SIZE * 0.65f;
         float moonX = hallCenterX - domeRadiusBlocks * BLOCK_SIZE * 0.35f;
         float moonZ = hallZ + domeRadiusBlocks * BLOCK_SIZE * 0.12f;
         addTexturedCube(moonX, moonY, moonZ,
             BLOCK_SIZE * 3.4f, BLOCK_SIZE * 3.4f, BLOCK_SIZE * 0.6f, "white_concrete");
         addTexturedCube(moonX, moonY, moonZ,
             BLOCK_SIZE * 3.8f, BLOCK_SIZE * 3.8f, BLOCK_SIZE * 0.35f, "glowstone");

         Random skyRand = new Random(4261986L);
         int starsPlaced = 0;
         float domeRadiusWorld = domeRadiusBlocks * BLOCK_SIZE;
         while (starsPlaced < 90) {
             float offsetX = (skyRand.nextFloat() * 2f - 1f) * domeRadiusWorld;
             float offsetZ = (skyRand.nextFloat() * 2f - 1f) * domeRadiusWorld;
             float dist = (float)Math.sqrt(offsetX * offsetX + offsetZ * offsetZ);
             if (dist > domeRadiusWorld) continue;

             float starHeight = ceilingY + (0.35f + skyRand.nextFloat() * 0.4f) * domeRadiusWorld;
             float starSize = BLOCK_SIZE * (0.25f + skyRand.nextFloat() * 0.35f);
             String starTex = (skyRand.nextFloat() < 0.35f) ? "glowstone" : "white_concrete";
             addTexturedCube(hallCenterX + offsetX, starHeight, hallZ + offsetZ,
                 starSize, starSize, starSize * 0.4f, starTex);
             starsPlaced++;
         }

        // === INDUSTRIAL DETAILS NEAR STAIRS ===
        for (int z = -6; z <= 6; z += 3) {
            addTexturedCube(hallX - 3 * BLOCK_SIZE, hallY + BLOCK_SIZE, hallZ + z * BLOCK_SIZE,
                   BLOCK_SIZE * 0.2f, BLOCK_SIZE * 0.8f, BLOCK_SIZE * 0.2f, "iron_block");
        }
    }

    private void buildCorridorAndReactorHall() {
        // =========================================================
        // LONG CORRIDOR from control room door DOWN to the EXISTING
        // reactor hall visible through the glass window.
        //
        // Layout:
        //   1) STRAIGHT section: z = -12 to -34 (22 blocks, flat, ~5.5s)
        //   2) STAIRS section: z = -34 to -42 (8 blocks, descending y=0 to y=-180)
        //   3) BOTTOM LANDING: z = -42 to -44 (flat at y=-180)
        //   4) TURN LEFT: corridor turns -X at bottom level
        //   5) LEFT ARM: x from +2 to -17 (19 blocks at y=-180, ~4.5s)
        //      running at z=-42 to z=-46, enters reactor hall through
        //      an extended walkway on the -Z side
        //
        // Total run: ~10-12 seconds
        // =========================================================

        int cw = 2; // corridor half-width in blocks
        int wallH = CORRIDOR_WALL_H;
        float hallFloorY = REACTOR_HALL_FLOOR_Y; // -180

        // ============================================================
        // SECTION 1: STRAIGHT CORRIDOR (z = -12 to -34, flat at y=0)
        // ============================================================
        for (int z = -12; z >= -34; z--) {
            for (int x = -cw; x <= cw; x++) {
                String tex = ((x + z) % 2 == 0) ? "gray_concrete" : "light_gray_concrete";
                addTexturedCube(x * BLOCK_SIZE, 0, z * BLOCK_SIZE, BLOCK_SIZE, 10, BLOCK_SIZE, tex);
            }
            // Left wall
            for (int h = 0; h < wallH; h++) {
                addTexturedCube((-cw - 1) * BLOCK_SIZE, h * BLOCK_SIZE + BLOCK_SIZE/2, z * BLOCK_SIZE,
                       BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, "soviet_gray_wall");
            }
            // Right wall
            for (int h = 0; h < wallH; h++) {
                addTexturedCube((cw + 1) * BLOCK_SIZE, h * BLOCK_SIZE + BLOCK_SIZE/2, z * BLOCK_SIZE,
                       BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, "soviet_gray_wall");
            }
            // Ceiling (extend over walls at x=-(cw+1) to cw+1 to seal corridor)
            for (int x = -(cw + 1); x <= (cw + 1); x++) {
                boolean isLight = (z % 4 == 0) && (x == 0);
                String tex = isLight ? "fluorescent_light" : "gray_concrete";
                addTexturedCube(x * BLOCK_SIZE, wallH * BLOCK_SIZE + BLOCK_SIZE/2, z * BLOCK_SIZE,
                       BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, tex);
            }
        }

        // Pipes along straight corridor ceiling
        addTexturedCube(-cw * BLOCK_SIZE + BLOCK_SIZE * 0.3f,
               (wallH - 0.5f) * BLOCK_SIZE, -23 * BLOCK_SIZE,
               BLOCK_SIZE * 0.15f, BLOCK_SIZE * 0.15f, 11 * BLOCK_SIZE, "iron_block");
        addTexturedCube(cw * BLOCK_SIZE - BLOCK_SIZE * 0.3f,
               (wallH - 0.5f) * BLOCK_SIZE, -23 * BLOCK_SIZE,
               BLOCK_SIZE * 0.15f, BLOCK_SIZE * 0.15f, 11 * BLOCK_SIZE, "iron_block");

        // SEAL: add continuous back wall slabs behind left and right corridor walls
        // to prevent any hairline gaps between block edges
        // Left side slab: x=-3, runs from z=-12 to z=-34, floor to ceiling
        addTexturedCube((-cw - 1) * BLOCK_SIZE, wallH * BLOCK_SIZE / 2, -23 * BLOCK_SIZE,
               BLOCK_SIZE, wallH * BLOCK_SIZE + BLOCK_SIZE, 23 * BLOCK_SIZE, "soviet_gray_wall");
        // Right side slab
        addTexturedCube((cw + 1) * BLOCK_SIZE, wallH * BLOCK_SIZE / 2, -23 * BLOCK_SIZE,
               BLOCK_SIZE, wallH * BLOCK_SIZE + BLOCK_SIZE, 23 * BLOCK_SIZE, "soviet_gray_wall");

         // Right corridor wall now stays sealed where the locker doorway was



        // ============================================================
        // SECTION 2: STAIRS (z = -34 to -42, descending from y=0 to y=-180)
        // ============================================================
        float stepDrop = Math.abs(hallFloorY) / 8.0f; // 22.5 per step
        for (int z = -34; z >= -42; z--) {
            float t = (float)(z - (-34)) / (float)(-42 - (-34)); // 0 at z=-34, 1 at z=-42
            float floorY = t * hallFloorY; // 0 to -180

            for (int x = -cw; x <= cw; x++) {
                // SOLID step: thick enough to fill gap to next step below
                addTexturedCube(x * BLOCK_SIZE, floorY - stepDrop/2 + 5, z * BLOCK_SIZE,
                       BLOCK_SIZE, stepDrop + 10, BLOCK_SIZE, "reactor_step_metal");
            }
            // Walls follow the descent — extend from bottom of stairs to corridor ceiling level
            // to ensure no gaps are visible from any angle
            float wallBase = Math.min(floorY, hallFloorY);
            float wallTopY = wallH * BLOCK_SIZE + BLOCK_SIZE; // always reach corridor ceiling level
            float wallTotalH = wallTopY - wallBase;
            addTexturedCube((-cw - 1) * BLOCK_SIZE, wallBase + wallTotalH/2, z * BLOCK_SIZE,
                   BLOCK_SIZE, wallTotalH, BLOCK_SIZE, "soviet_gray_wall");
            addTexturedCube((cw + 1) * BLOCK_SIZE, wallBase + wallTotalH/2, z * BLOCK_SIZE,
                   BLOCK_SIZE, wallTotalH, BLOCK_SIZE, "soviet_gray_wall");
            // Ceiling: each block extends from local ceiling height up to corridor ceiling level,
            // creating a solid wedge that seals all gaps
            float localCeilY = floorY + wallH * BLOCK_SIZE + BLOCK_SIZE/2;
            float corridorCeilY = wallH * BLOCK_SIZE + BLOCK_SIZE/2; // flat corridor ceiling at y=0 level
            float ceilBlockH = corridorCeilY - localCeilY + BLOCK_SIZE; // tall enough to fill gap
            float ceilCenterY = (localCeilY + corridorCeilY) / 2.0f;
            for (int x = -(cw + 1); x <= (cw + 1); x++) {
                boolean isLight = (z % 3 == 0) && (x == 0);
                // Bottom visible face at localCeilY, extends up to corridor ceiling
                addTexturedCube(x * BLOCK_SIZE, ceilCenterY, z * BLOCK_SIZE,
                       BLOCK_SIZE, ceilBlockH, BLOCK_SIZE,
                       isLight ? "fluorescent_light" : "gray_concrete");
            }
        }

        // Stair handrails
        for (int z = -34; z >= -42; z--) {
            if (z == -42) continue; // keep landing clear of handrail posts
            float t = (float)(z - (-34)) / (float)(-42 - (-34));
            float floorY = t * hallFloorY;
            addTexturedCube((-cw - 0.3f) * BLOCK_SIZE, floorY + BLOCK_SIZE * 0.9f, z * BLOCK_SIZE,
                   BLOCK_SIZE * 0.08f, BLOCK_SIZE * 0.8f, BLOCK_SIZE * 0.08f, "iron_block");
            addTexturedCube((cw + 0.3f) * BLOCK_SIZE, floorY + BLOCK_SIZE * 0.9f, z * BLOCK_SIZE,
                   BLOCK_SIZE * 0.08f, BLOCK_SIZE * 0.8f, BLOCK_SIZE * 0.08f, "iron_block");
        }

        // SEAL: continuous stairwell wall slabs from z=-34 to z=-42, floor-to-ceiling
        // Left + right wall slabs
        {
            float slabBase = hallFloorY; // -180
            float slabTop = wallH * BLOCK_SIZE + BLOCK_SIZE; // 350
            float slabH = slabTop - slabBase;
            // Left slab: z=-34 to z=-42 ONLY (landing is open left for the left arm)
            float leftSlabCenterZ = (-34 * BLOCK_SIZE + -42 * BLOCK_SIZE) / 2.0f;
            float leftSlabLenZ = 9 * BLOCK_SIZE;
            addTexturedCube((-cw - 1) * BLOCK_SIZE, slabBase + slabH / 2, leftSlabCenterZ,
                   BLOCK_SIZE, slabH, leftSlabLenZ, "soviet_gray_wall");
                 // Right slab: keep the stair enclosure solid down to z=-42, but leave the landing open
                 float rightSlabUpperCenterZ = (-34 * BLOCK_SIZE + -42 * BLOCK_SIZE) / 2.0f;
                 float rightSlabUpperLenZ = 9 * BLOCK_SIZE;
                 addTexturedCube((cw + 1) * BLOCK_SIZE, slabBase + slabH / 2, rightSlabUpperCenterZ,
                     BLOCK_SIZE, slabH, rightSlabUpperLenZ, "soviet_gray_wall");
        }

        // ============================================================
        // SECTION 3: BOTTOM LANDING + TURN LEFT (z = -42 to -46, y = -180)
        // The corridor widens into a landing at the bottom of stairs,
        // then opens LEFT (-X direction)
        // ============================================================
        for (int z = -42; z >= -46; z--) {
            // Floor — extend to x=-3 so player can walk to left arm
            for (int x = -(cw + 1); x <= cw; x++) {
                addTexturedCube(x * BLOCK_SIZE, hallFloorY, z * BLOCK_SIZE,
                       BLOCK_SIZE, 10, BLOCK_SIZE, "gray_concrete");
            }
            // Right wall — extend from hallFloorY all the way up to corridor ceiling
            // Ceiling — extend from landing ceiling up to corridor ceiling to seal
            float landingCeilY = hallFloorY + wallH * BLOCK_SIZE + BLOCK_SIZE / 2;
            float corridorCeilY2 = wallH * BLOCK_SIZE + BLOCK_SIZE / 2;
            float cBlockH = corridorCeilY2 - landingCeilY + BLOCK_SIZE;
            float cCenterY = (landingCeilY + corridorCeilY2) / 2.0f;
            for (int x = -(cw + 1); x <= (cw + 1); x++) {
                addTexturedCube(x * BLOCK_SIZE, cCenterY, z * BLOCK_SIZE,
                       BLOCK_SIZE, cBlockH, BLOCK_SIZE, "gray_concrete");
            }
        }
        // Far wall at z = -46 to close the landing (full width including wall positions)
        for (int x = -(cw + 1); x <= (cw + 1); x++) {
            // Far wall: extend from hallFloorY up to corridor ceiling level
            float fwBase = hallFloorY;
            float fwTop = wallH * BLOCK_SIZE + BLOCK_SIZE;
            float fwH = fwTop - fwBase;
            addTexturedCube(x * BLOCK_SIZE, fwBase + fwH / 2, -46 * BLOCK_SIZE,
                   BLOCK_SIZE, fwH, BLOCK_SIZE, "soviet_gray_wall");
        }

        // ============================================================
        // SECTION 4: LEFT ARM (going -X at y=-180, z from -42 to -46)
        // From x = -cw-1 to x = -17 (enters reactor hall's open window side)
        // ============================================================
        for (int x = -cw - 2; x >= -18; x--) {
            for (int z = -42; z >= -46; z--) {
                addTexturedCube(x * BLOCK_SIZE, hallFloorY, z * BLOCK_SIZE,
                       BLOCK_SIZE, 10, BLOCK_SIZE, "gray_concrete");
                // Ceiling
                addTexturedCube(x * BLOCK_SIZE, hallFloorY + wallH * BLOCK_SIZE + BLOCK_SIZE/2, z * BLOCK_SIZE,
                       BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE,
                       (z == -44 && x % 4 == 0) ? "fluorescent_light" : "gray_concrete");
            }
            // z=-42 wall — closes left arm from stairwell. Full height to corridor ceiling.
            // Stop before x=-18 where the walkway connects
            if (x <= -cw - 2 && x > -18) {
                float armWallBase = hallFloorY;
                float armWallTop = wallH * BLOCK_SIZE + BLOCK_SIZE;
                float armWallH = armWallTop - armWallBase;
                addTexturedCube(x * BLOCK_SIZE, armWallBase + armWallH / 2, -42 * BLOCK_SIZE,
                       BLOCK_SIZE, armWallH, BLOCK_SIZE, "soviet_gray_wall");
            }
            // Far wall (z = -46 side) — full height to corridor ceiling
            // Stop before x=-18 where the walkway connects
            if (x > -18) {
                float fwBase = hallFloorY;
                float fwTop = wallH * BLOCK_SIZE + BLOCK_SIZE;
                float fwH = fwTop - fwBase;
                addTexturedCube(x * BLOCK_SIZE, fwBase + fwH / 2, -46 * BLOCK_SIZE,
                       BLOCK_SIZE, fwH, BLOCK_SIZE, "soviet_gray_wall");
            }
        }

        // Mirror corridor arm on +X side leading toward pump annex walkway
        for (int x = cw; x <= 18; x++) {
            for (int z = -42; z >= -46; z--) {
                addTexturedCube(x * BLOCK_SIZE, hallFloorY, z * BLOCK_SIZE,
                       BLOCK_SIZE, 10, BLOCK_SIZE, "gray_concrete");
                addTexturedCube(x * BLOCK_SIZE, hallFloorY + wallH * BLOCK_SIZE + BLOCK_SIZE/2, z * BLOCK_SIZE,
                       BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE,
                       (z == -44 && x % 4 == 0) ? "fluorescent_light" : "gray_concrete");
            }
            // Far wall at z = -46 — keep closed until the walkway takes over
            if (x < 18) {
                float fwBase = hallFloorY;
                float fwTop = wallH * BLOCK_SIZE + BLOCK_SIZE;
                float fwH = fwTop - fwBase;
                addTexturedCube(x * BLOCK_SIZE, fwBase + fwH / 2, -46 * BLOCK_SIZE,
                       BLOCK_SIZE, fwH, BLOCK_SIZE, "soviet_gray_wall");
            }
            // z=-42 wall — mirror of the reactor-side wall, leave opening exactly where the pump walkway begins
            if (x >= cw + 1 && x < 18) {
                float armWallBase = hallFloorY;
                float armWallTop = wallH * BLOCK_SIZE + BLOCK_SIZE;
                float armWallH = armWallTop - armWallBase;
                addTexturedCube(x * BLOCK_SIZE, armWallBase + armWallH / 2, -42 * BLOCK_SIZE,
                       BLOCK_SIZE, armWallH, BLOCK_SIZE, "soviet_gray_wall");
            }
        }

        // Pipes along left arm ceiling
        addTexturedCube(-10 * BLOCK_SIZE, hallFloorY + (wallH - 0.5f) * BLOCK_SIZE, -44 * BLOCK_SIZE,
               8 * BLOCK_SIZE, BLOCK_SIZE * 0.15f, BLOCK_SIZE * 0.15f, "iron_block");

        // ============================================================
        // SECTION 5: CONNECTING WALKWAY from left arm to reactor hall
        // Extends from z=-46 (far wall) to z=-12 (reactor hall entry)
        // at x = -18 to -20, forming the vertical bar of the L-shape
        // Walkway: x = -18 to -20, z from -46 to -12
        // ============================================================
        for (int z = -46; z <= -12; z++) {
            for (int x = -18; x >= -20; x--) {
                // Skip floor where left arm already placed it (x=-18, z=-42 to -46)
                if (x == -18 && z <= -42) continue;
                addTexturedCube(x * BLOCK_SIZE, hallFloorY, z * BLOCK_SIZE,
                       BLOCK_SIZE, 10, BLOCK_SIZE, "reactor_floor_metal");
            }
            // Ceiling — cover full walkway width
            for (int cx = -18; cx >= -20; cx--) {
                addTexturedCube(cx * BLOCK_SIZE, hallFloorY + wallH * BLOCK_SIZE + BLOCK_SIZE/2, z * BLOCK_SIZE,
                       BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE,
                       (z % 6 == 0 && cx == -19) ? "fluorescent_light" : "gray_concrete");
            }
        }

        // Smooth continuous walls so the walkway sides read as solid panels instead of sand columns
        float walkwayWallHeight = wallH * BLOCK_SIZE;
        int walkwayStartZ = -46;
        int walkwayEndZ = -12;
        float walkwayLength = (walkwayEndZ - walkwayStartZ + 1) * BLOCK_SIZE;
        float walkwayCenterZ = ((walkwayStartZ + walkwayEndZ) / 2f) * BLOCK_SIZE;
        addTexturedCube(-21 * BLOCK_SIZE, hallFloorY + walkwayWallHeight / 2f, walkwayCenterZ,
                BLOCK_SIZE, walkwayWallHeight, walkwayLength, "reactor_wall_white");

        // Inner guard wall spans the whole run but leaves the final doorway clear
        int innerWallStartZ = -46;
        int innerWallEndZ = -13; // keep the hall entry (z > -13) unobstructed
        float innerWallLength = (innerWallEndZ - innerWallStartZ + 1) * BLOCK_SIZE;
        float innerWallCenterZ = ((innerWallStartZ + innerWallEndZ) / 2f) * BLOCK_SIZE;
        addTexturedCube(-17 * BLOCK_SIZE, hallFloorY + walkwayWallHeight / 2f, innerWallCenterZ,
            BLOCK_SIZE, walkwayWallHeight, innerWallLength, "reactor_wall_white");

        // Single column directly in front of the landing to close the lone gap the player sees
        for (int h = 0; h < wallH; h++) {
            addTexturedCube(-17 * BLOCK_SIZE,
                hallFloorY + h * BLOCK_SIZE + BLOCK_SIZE / 2f,
                -45 * BLOCK_SIZE,
                BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE,
                "reactor_wall_white");
        }

         // Far wall closing walkway at z=-46 for x=-19,-20
         for (int x = -19; x >= -20; x--) {
             for (int h = 0; h < wallH; h++) {
                addTexturedCube(x * BLOCK_SIZE, hallFloorY + h * BLOCK_SIZE + BLOCK_SIZE/2, -46 * BLOCK_SIZE,
                    BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, "soviet_gray_wall");
             }
         }

         // ============================================================
         // SECTION 5B: Pump annex access walkway on +X side (mirror)
         // ============================================================
         for (int z = -46; z <= -12; z++) {
             for (int x = 18; x <= 20; x++) {
                if (x == 18 && z <= -42) continue;
                addTexturedCube(x * BLOCK_SIZE, hallFloorY, z * BLOCK_SIZE,
                    BLOCK_SIZE, 10, BLOCK_SIZE, "reactor_floor_metal");
             }
             // Inner wall (toward the landing) — skip near the open turn
             if (z < -12 && z >= -41) {
                for (int h = 0; h < wallH; h++) {
                    addTexturedCube(PUMP_WALKWAY_INNER_WALL, hallFloorY + h * BLOCK_SIZE + BLOCK_SIZE/2, z * BLOCK_SIZE,
                        BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, "reactor_wall_white");
                }
             }
             // Outer wall (prevent falling into annex void) — extend closer to the landing so the corner is sealed
             if (z < -12 && z >= -45) {
                for (int h = 0; h < wallH; h++) {
                    addTexturedCube(PUMP_WALKWAY_OUTER_WALL, hallFloorY + h * BLOCK_SIZE + BLOCK_SIZE/2, z * BLOCK_SIZE,
                        BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, "reactor_wall_white");
                }
             }
             // Ceiling tiles
             for (int cx = 18; cx <= 20; cx++) {
                addTexturedCube(cx * BLOCK_SIZE, hallFloorY + wallH * BLOCK_SIZE + BLOCK_SIZE/2, z * BLOCK_SIZE,
                    BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE,
                    (z % 6 == 0 && cx == 19) ? "fluorescent_light" : "gray_concrete");
             }
         }

         for (int x = 18; x <= 20; x++) {
             for (int h = 0; h < wallH; h++) {
                addTexturedCube(x * BLOCK_SIZE, hallFloorY + h * BLOCK_SIZE + BLOCK_SIZE/2, -46 * BLOCK_SIZE,
                    BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, "soviet_gray_wall");
             }
         }

        // Build optional explorable rooms off the corridor
        buildPumpServiceAnnex();



        // === NPC: PEREVOZCHENKO (Reactor Section Foreman) ===
        // Standing on the walkway near where it opens to the reactor hall,
        // inspecting the reactor from the catwalk
        createNPCEngineer("Perevozchenko",
            -19 * BLOCK_SIZE,       // x: on the walkway
            hallFloorY,             // y: reactor hall floor level (-180)
            -6 * BLOCK_SIZE,        // z: midway along walkway, near reactor
            0.0f,                   // facing: towards +Z (looking along the reactor hall)
            "perevozchenko_head", "perevozchenko_body", "perevozchenko_legs", "perevozchenko_arms");
    }

    private void buildPumpServiceAnnex() {
        int minGridX = Math.round(PUMP_ANNEX_MIN_X / BLOCK_SIZE);
        int maxGridX = Math.round(PUMP_ANNEX_MAX_X / BLOCK_SIZE);
        int minGridZ = Math.round(PUMP_ANNEX_MIN_Z / BLOCK_SIZE);
        int maxGridZ = Math.round(PUMP_ANNEX_MAX_Z / BLOCK_SIZE);
        float floorY = REACTOR_HALL_FLOOR_Y;
        // Raise annex ceiling one block higher than the corridor so the room breathes a bit
        int wallHeight = 6;
        int walkwayOpenMinGX = Math.round(PUMP_WALKWAY_X_MIN / BLOCK_SIZE);
        int walkwayOpenMaxGX = Math.round(PUMP_WALKWAY_X_MAX / BLOCK_SIZE);
        int walkwayEntranceGZ = Math.round(PUMP_ANNEX_MIN_Z / BLOCK_SIZE);

        for (int gx = minGridX; gx <= maxGridX; gx++) {
            for (int gz = minGridZ; gz <= maxGridZ; gz++) {
                addTexturedCube(gx * BLOCK_SIZE, floorY, gz * BLOCK_SIZE,
                    BLOCK_SIZE, 10, BLOCK_SIZE, "reactor_floor_metal");
            }
        }

        // Concrete walls (leave an opening at the walkway interface, similar to the reactor hall)
        for (int h = 0; h < wallHeight; h++) {
            for (int gz = minGridZ; gz <= maxGridZ; gz++) {
                addTexturedCube(PUMP_ANNEX_MAX_X, floorY + h * BLOCK_SIZE + BLOCK_SIZE / 2f, gz * BLOCK_SIZE,
                    BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, "reactor_wall_white");
                boolean walkwayOpening = (gz == walkwayEntranceGZ);
                if (!(walkwayOpening)) {
                    addTexturedCube(PUMP_ANNEX_MIN_X, floorY + h * BLOCK_SIZE + BLOCK_SIZE / 2f, gz * BLOCK_SIZE,
                        BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, "reactor_wall_white");
                }
            }
            for (int gx = minGridX; gx <= maxGridX; gx++) {
                boolean walkwayOpening = (gx >= walkwayOpenMinGX && gx <= walkwayOpenMaxGX);
                if (!(walkwayOpening)) {
                    addTexturedCube(gx * BLOCK_SIZE, floorY + h * BLOCK_SIZE + BLOCK_SIZE / 2f, PUMP_ANNEX_MIN_Z,
                        BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, "reactor_wall_white");
                }
                addTexturedCube(gx * BLOCK_SIZE, floorY + h * BLOCK_SIZE + BLOCK_SIZE / 2f, PUMP_ANNEX_MAX_Z,
                    BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, "reactor_wall_white");
            }
        }

        // Ceiling with utilitarian lights
        for (int gx = minGridX; gx <= maxGridX; gx++) {
            for (int gz = minGridZ; gz <= maxGridZ; gz++) {
                boolean light = ((gx - minGridX) % 3 == 1) && ((gz - minGridZ) % 2 == 0);
                String tex = light ? "fluorescent_light" : "gray_concrete";
                addTexturedCube(gx * BLOCK_SIZE, floorY + wallHeight * BLOCK_SIZE + BLOCK_SIZE / 2f, gz * BLOCK_SIZE,
                    BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, tex);
            }
        }

        // Structured annex layout: clear travel lane, discrete pump pods, and a staged console dais
        float annexCenterX = (PUMP_ANNEX_MIN_X + PUMP_ANNEX_MAX_X) * 0.5f;
        float annexCenterZ = (PUMP_ANNEX_MIN_Z + PUMP_ANNEX_MAX_Z) * 0.5f;
        float annexSpanX = PUMP_ANNEX_MAX_X - PUMP_ANNEX_MIN_X;
        float annexSpanZ = PUMP_ANNEX_MAX_Z - PUMP_ANNEX_MIN_Z;

        // Low-profile maintenance spine keeps the circulation lane clean
        float spineLength = annexSpanZ - BLOCK_SIZE * 5.6f;
        addTexturedCube(annexCenterX, floorY + BLOCK_SIZE * 0.04f, annexCenterZ,
            BLOCK_SIZE * 0.9f, BLOCK_SIZE * 0.12f, spineLength, "reactor_floor_metal");
        addTexturedCube(annexCenterX, floorY + BLOCK_SIZE * 0.12f, annexCenterZ,
            BLOCK_SIZE * 0.4f, BLOCK_SIZE * 0.04f, spineLength - BLOCK_SIZE * 0.8f, "glass");
        for (int dir = -1; dir <= 1; dir += 2) {
            float capZ = annexCenterZ + dir * (spineLength * 0.5f - BLOCK_SIZE * 0.6f);
            addTexturedCube(annexCenterX, floorY + BLOCK_SIZE * 0.2f, capZ,
                BLOCK_SIZE * 0.7f, BLOCK_SIZE * 0.08f, BLOCK_SIZE * 0.4f, "warning_label");
            addTexturedCube(annexCenterX, floorY + BLOCK_SIZE * 0.32f, capZ,
                BLOCK_SIZE * 0.6f, BLOCK_SIZE * 0.1f, BLOCK_SIZE * 0.18f, "glass");
        }

        // Wall ribs echo the rhythm of the control room columns for cohesion
        for (int side = -1; side <= 1; side += 2) {
            float ribX = (side < 0 ? PUMP_ANNEX_MIN_X + BLOCK_SIZE * 0.7f : PUMP_ANNEX_MAX_X - BLOCK_SIZE * 0.7f);
            for (int seg = -1; seg <= 1; seg++) {
                float ribZ = annexCenterZ + seg * BLOCK_SIZE * 2.6f;
                addTexturedCube(ribX, floorY + BLOCK_SIZE * 1.8f, ribZ,
                    BLOCK_SIZE * 0.25f, BLOCK_SIZE * 3.0f, BLOCK_SIZE * 0.25f, "iron_block");
            }
        }

        // Cable troughs hug the perimeter walls for a cleaner floor plate
        float troughLength = annexSpanZ - BLOCK_SIZE * 2.5f;
        addTexturedCube(PUMP_ANNEX_MIN_X + BLOCK_SIZE * 0.55f, floorY + BLOCK_SIZE * 0.35f, annexCenterZ,
            BLOCK_SIZE * 0.35f, BLOCK_SIZE * 0.5f, troughLength, "cable_tray");
        addTexturedCube(PUMP_ANNEX_MAX_X - BLOCK_SIZE * 0.55f, floorY + BLOCK_SIZE * 0.35f, annexCenterZ,
            BLOCK_SIZE * 0.35f, BLOCK_SIZE * 0.5f, troughLength, "cable_tray");

        // Discrete pump pods on each side of the lane
        float[] podRows = { annexCenterZ - BLOCK_SIZE * 3.2f, annexCenterZ + BLOCK_SIZE * 3.2f };
        for (float podZ : podRows) {
            for (int side = -1; side <= 1; side += 2) {
                float podX = annexCenterX + side * BLOCK_SIZE * 3.6f;
                addTexturedCube(podX, floorY + BLOCK_SIZE * 0.55f, podZ,
                    BLOCK_SIZE * 1.8f, BLOCK_SIZE * 0.9f, BLOCK_SIZE * 2.4f, "reactor_floor_metal");
                addTexturedCube(podX + side * BLOCK_SIZE * 0.4f, floorY + BLOCK_SIZE * 1.25f, podZ - BLOCK_SIZE * 0.3f,
                    BLOCK_SIZE * 1.2f, BLOCK_SIZE * 1.1f, BLOCK_SIZE * 1.5f, "control_panel_gray");
                addTexturedCube(podX - side * BLOCK_SIZE * 0.1f, floorY + BLOCK_SIZE * 2.05f, podZ + BLOCK_SIZE * 0.4f,
                    BLOCK_SIZE * 0.8f, BLOCK_SIZE * 0.8f, BLOCK_SIZE, "hazard_stripe");
                addTexturedCube(podX + side * BLOCK_SIZE * 0.9f, floorY + BLOCK_SIZE * 2.2f, podZ - BLOCK_SIZE * 0.6f,
                    BLOCK_SIZE * 0.35f, BLOCK_SIZE * 2.3f, BLOCK_SIZE * 0.35f, "iron_block");
                addTexturedCube(podX + side * BLOCK_SIZE * 0.9f, floorY + wallHeight * BLOCK_SIZE - BLOCK_SIZE * 1.0f, podZ - BLOCK_SIZE * 0.6f,
                    BLOCK_SIZE * 0.9f, BLOCK_SIZE * 0.4f, BLOCK_SIZE * 0.4f, "cable_tray");
                float sensorX = podX - side * BLOCK_SIZE * 0.95f;
                float sensorZ = podZ - BLOCK_SIZE * 1.1f;
                addTexturedCube(sensorX, floorY + BLOCK_SIZE * 1.05f, sensorZ,
                    BLOCK_SIZE * 0.45f, BLOCK_SIZE * 1.8f, BLOCK_SIZE * 0.65f, "control_panel_dark");
                addTexturedCube(sensorX, floorY + BLOCK_SIZE * 2.1f, sensorZ,
                    BLOCK_SIZE * 0.6f, BLOCK_SIZE * 0.25f, BLOCK_SIZE * 0.9f, "glass");
            }
        }

        // Wall wash lights keep the spine legible without adding clutter
        float wallLightInset = BLOCK_SIZE * 0.95f;
        for (int side = -1; side <= 1; side += 2) {
            float wallLightX = (side < 0 ? PUMP_ANNEX_MIN_X + wallLightInset : PUMP_ANNEX_MAX_X - wallLightInset);
            addTexturedCube(wallLightX, floorY + BLOCK_SIZE * 0.2f, annexCenterZ,
                BLOCK_SIZE * 0.4f, BLOCK_SIZE * 0.3f, spineLength - BLOCK_SIZE * 1.4f, "glass");
            addTexturedCube(wallLightX, floorY + BLOCK_SIZE * 0.5f, annexCenterZ,
                BLOCK_SIZE * 0.5f, BLOCK_SIZE * 0.08f, BLOCK_SIZE * 0.4f, "warning_label");
        }

        // Entry staging pads and hose reels
        float entryZ = PUMP_ANNEX_MIN_Z + BLOCK_SIZE * 2.4f;
        addTexturedCube(annexCenterX - BLOCK_SIZE * 2.8f, floorY + BLOCK_SIZE * 0.9f, entryZ,
            BLOCK_SIZE * 1.6f, BLOCK_SIZE * 1.6f, BLOCK_SIZE * 1.4f, "control_panel_dark");
        addTexturedCube(annexCenterX + BLOCK_SIZE * 2.8f, floorY + BLOCK_SIZE * 0.9f, entryZ,
            BLOCK_SIZE * 1.6f, BLOCK_SIZE * 1.6f, BLOCK_SIZE * 1.4f, "control_panel_dark");
        addTexturedCube(annexCenterX, floorY + BLOCK_SIZE * 1.2f, entryZ + BLOCK_SIZE * 1.2f,
            BLOCK_SIZE * 1.2f, BLOCK_SIZE * 2.2f, BLOCK_SIZE * 0.8f, "control_panel_gray");

        // Overhead coolant manifold with risers feeding each pump row
        addTexturedCube(annexCenterX, floorY + wallHeight * BLOCK_SIZE - BLOCK_SIZE * 0.5f, annexCenterZ,
            annexSpanX - BLOCK_SIZE * 4f, BLOCK_SIZE * 0.45f, BLOCK_SIZE * 0.8f, "cable_tray");
        for (float podZ : podRows) {
            addTexturedCube(annexCenterX - BLOCK_SIZE * 2.6f, floorY + BLOCK_SIZE * 1.6f, podZ,
                BLOCK_SIZE * 0.4f, BLOCK_SIZE * 2.7f, BLOCK_SIZE * 0.4f, "iron_block");
            addTexturedCube(annexCenterX + BLOCK_SIZE * 2.6f, floorY + BLOCK_SIZE * 1.6f, podZ,
                BLOCK_SIZE * 0.4f, BLOCK_SIZE * 2.7f, BLOCK_SIZE * 0.4f, "iron_block");
        }

        // Tool crib and spare impeller crates sit at the back wall for a cleaner front half
        float backZ = PUMP_ANNEX_MAX_Z - BLOCK_SIZE * 2f;
        addTexturedCube(annexCenterX - BLOCK_SIZE * 3.4f, floorY + BLOCK_SIZE * 1.1f, backZ,
            BLOCK_SIZE * 1.4f, BLOCK_SIZE * 2.0f, BLOCK_SIZE * 1.2f, "control_panel_gray");
        addTexturedCube(annexCenterX + BLOCK_SIZE * 3.4f, floorY + BLOCK_SIZE * 0.9f, backZ,
            BLOCK_SIZE * 1.2f, BLOCK_SIZE * 1.6f, BLOCK_SIZE * 1.8f, "iron_block");
        addTexturedCube(annexCenterX + BLOCK_SIZE * 3.4f, floorY + BLOCK_SIZE * 1.5f, backZ + BLOCK_SIZE * 0.8f,
            BLOCK_SIZE * 1.0f, BLOCK_SIZE * 0.8f, BLOCK_SIZE * 1.0f, "warning_label");

        // Staged console dais located on the front wall
        float daisDepth = BLOCK_SIZE * 3.6f;
        addTexturedCube(PUMP_CONSOLE_X, floorY + BLOCK_SIZE * 0.45f, PUMP_CONSOLE_Z,
            BLOCK_SIZE * 7.5f, BLOCK_SIZE * 0.9f, daisDepth, "reactor_floor_metal");
        addTexturedCube(PUMP_CONSOLE_X, floorY + BLOCK_SIZE * 0.18f, PUMP_CONSOLE_Z - daisDepth * 0.6f,
            BLOCK_SIZE * 5.8f, BLOCK_SIZE * 0.25f, BLOCK_SIZE * 0.7f, "reactor_step");
        addTexturedCube(PUMP_CONSOLE_X, floorY + BLOCK_SIZE * 1.3f, PUMP_CONSOLE_Z + BLOCK_SIZE * 0.6f,
            BLOCK_SIZE * 3.6f, BLOCK_SIZE * 2.1f, BLOCK_SIZE * 1.2f, "control_panel_dark");
        addTexturedCube(PUMP_CONSOLE_X, floorY + BLOCK_SIZE * 2.3f, PUMP_CONSOLE_Z + BLOCK_SIZE * 0.95f,
            BLOCK_SIZE * 3.0f, BLOCK_SIZE * 0.8f, BLOCK_SIZE * 0.5f, "control_panel_gray");
        addTexturedCube(PUMP_CONSOLE_X, floorY + BLOCK_SIZE * 2.75f, PUMP_CONSOLE_Z + BLOCK_SIZE * 1.2f,
            BLOCK_SIZE * 2.8f, BLOCK_SIZE * 0.45f, BLOCK_SIZE * 0.3f, "glass");
        addTexturedCube(PUMP_CONSOLE_X, floorY + BLOCK_SIZE * 3.0f, PUMP_CONSOLE_Z + BLOCK_SIZE * 1.35f,
            BLOCK_SIZE * 3.2f, BLOCK_SIZE * 0.25f, BLOCK_SIZE * 0.25f, "warning_label");
        float tabletOffsetX = BLOCK_SIZE * 2.05f;
        for (int side = -1; side <= 1; side += 2) {
            float tabletX = PUMP_CONSOLE_X + side * tabletOffsetX;
            addTexturedCube(tabletX, floorY + BLOCK_SIZE * 1.35f, PUMP_CONSOLE_Z + BLOCK_SIZE * 0.5f,
                BLOCK_SIZE * 0.9f, BLOCK_SIZE * 1.4f, BLOCK_SIZE * 0.9f, "control_panel_gray");
            addTexturedCube(tabletX, floorY + BLOCK_SIZE * 2.05f, PUMP_CONSOLE_Z + BLOCK_SIZE * 0.85f,
                BLOCK_SIZE * 0.8f, BLOCK_SIZE * 0.5f, BLOCK_SIZE * 0.35f, "glass");
        }
        addTexturedCube(PUMP_CONSOLE_X, floorY + BLOCK_SIZE * 0.95f, PUMP_CONSOLE_Z - BLOCK_SIZE * 0.75f,
            BLOCK_SIZE * 3.8f, BLOCK_SIZE * 0.6f, BLOCK_SIZE * 0.9f, "control_panel_gray");
        addTexturedCube(PUMP_CONSOLE_X, floorY + BLOCK_SIZE * 1.25f, PUMP_CONSOLE_Z - BLOCK_SIZE * 1.05f,
            BLOCK_SIZE * 4.0f, BLOCK_SIZE * 0.2f, BLOCK_SIZE * 0.35f, "iron_block");
        addTexturedCube(PUMP_CONSOLE_X, floorY + BLOCK_SIZE * 0.85f, PUMP_CONSOLE_Z - BLOCK_SIZE * 1.1f,
            BLOCK_SIZE * 2.4f, BLOCK_SIZE * 0.16f, BLOCK_SIZE * 0.35f, "hazard_stripe");

        // Glass canopy and signage frame the dais without cluttering the floor
        addTexturedCube(PUMP_CONSOLE_X, floorY + wallHeight * BLOCK_SIZE - BLOCK_SIZE * 0.9f, PUMP_CONSOLE_Z + BLOCK_SIZE * 0.9f,
            BLOCK_SIZE * 5.2f, BLOCK_SIZE * 0.3f, BLOCK_SIZE * 0.9f, "glass");
        addTexturedCube(PUMP_CONSOLE_X - BLOCK_SIZE * 2.5f, floorY + wallHeight * BLOCK_SIZE - BLOCK_SIZE * 1.4f, PUMP_CONSOLE_Z + BLOCK_SIZE * 0.9f,
            BLOCK_SIZE * 0.35f, BLOCK_SIZE * 1.1f, BLOCK_SIZE * 0.35f, "iron_block");
        addTexturedCube(PUMP_CONSOLE_X + BLOCK_SIZE * 2.5f, floorY + wallHeight * BLOCK_SIZE - BLOCK_SIZE * 1.4f, PUMP_CONSOLE_Z + BLOCK_SIZE * 0.9f,
            BLOCK_SIZE * 0.35f, BLOCK_SIZE * 1.1f, BLOCK_SIZE * 0.35f, "iron_block");
        addTexturedCube(PUMP_CONSOLE_X, floorY + wallHeight * BLOCK_SIZE - BLOCK_SIZE * 0.6f, PUMP_CONSOLE_Z - BLOCK_SIZE * 0.3f,
            BLOCK_SIZE * 4.6f, BLOCK_SIZE * 0.2f, BLOCK_SIZE * 0.25f, "hazard_stripe");

        // Symmetric wall lockers frame the dais to keep the hero prop clear
        addTexturedCube(PUMP_ANNEX_MIN_X + BLOCK_SIZE * 1.6f, floorY + BLOCK_SIZE * 1.8f, PUMP_CONSOLE_Z,
            BLOCK_SIZE * 1.5f, BLOCK_SIZE * 2.8f, BLOCK_SIZE * 1.2f, "control_panel_gray");
        addTexturedCube(PUMP_ANNEX_MAX_X - BLOCK_SIZE * 1.6f, floorY + BLOCK_SIZE * 1.8f, PUMP_CONSOLE_Z,
            BLOCK_SIZE * 1.5f, BLOCK_SIZE * 2.8f, BLOCK_SIZE * 1.2f, "control_panel_gray");

        // Lighting beacons highlight the center lane and the console wall
        float annexLightY = floorY + wallHeight * BLOCK_SIZE - 20f;
        createAlarmLight(annexCenterX, annexLightY, annexCenterZ);
        createAlarmLight(PUMP_CONSOLE_X, annexLightY, PUMP_CONSOLE_Z + BLOCK_SIZE * 0.4f);

        // Pump technician NPC
        float kudX = annexCenterX - BLOCK_SIZE * 4.4f;
        float kudZ = annexCenterZ - BLOCK_SIZE * 0.2f;
        createNPCEngineer("Kudryavtsev",
            kudX,
            floorY,
            kudZ,
            10f,
            "kudryavtsev_head", "kudryavtsev_body", "kudryavtsev_legs", "kudryavtsev_arms");
    }
    
    private int generateReactorFloorTexture() {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        Random rand = new Random(4444);
        
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                // Metal gray floor with grid pattern
                int gray = 90 + rand.nextInt(20);
                
                // Grid lines
                if (x == 0 || x == 15 || y == 0 || y == 15) {
                    gray -= 25;
                }
                // Center square indent
                if (x >= 5 && x <= 10 && y >= 5 && y <= 10) {
                    gray -= 10;
                }
                
                pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (gray << 16) | (gray << 8) | gray;
            }
        }
        return createTextureFromPixels(pixels);
    }
    
    private int generateFuelCapTexture(int baseColor) {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        Random rand = new Random(baseColor);
        
        int baseR = (baseColor >> 16) & 0xFF;
        int baseG = (baseColor >> 8) & 0xFF;
        int baseB = baseColor & 0xFF;
        
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                int variation = rand.nextInt(15) - 7;
                int r = Math.max(0, Math.min(255, baseR + variation));
                int g = Math.max(0, Math.min(255, baseG + variation));
                int b = Math.max(0, Math.min(255, baseB + variation));
                
                // Square cap with center hole
                float dx = x - 8;
                float dy = y - 8;
                float dist = (float)Math.sqrt(dx*dx + dy*dy);
                
                // Dark center hole
                if (dist < 3) {
                    r = r / 3;
                    g = g / 3;
                    b = b / 3;
                }
                // Edge highlight
                if (x <= 1 || x >= 14 || y <= 1 || y >= 14) {
                    r = Math.min(255, r + 20);
                    g = Math.min(255, g + 20);
                    b = Math.min(255, b + 20);
                }
                
                pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return createTextureFromPixels(pixels);
    }
    
    private int generateReactorStepTexture() {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        Random rand = new Random(5556);
        
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                // Metal step texture
                int gray = 100 + rand.nextInt(15);
                
                // Anti-slip pattern
                if ((x + y) % 3 == 0) {
                    gray -= 15;
                }
                
                pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (gray << 16) | (gray << 8) | gray;
            }
        }
        return createTextureFromPixels(pixels);
    }
    
    // NPC Engineer texture generators
    private int generateEngineerHeadTexture(int skinColor, boolean hasHair) {
        int S = TEXTURE_SIZE;
        int[] facePixels = new int[S * S];
        Random rand = new Random(skinColor + 1234);

        int skinR = (skinColor >> 16) & 0xFF;
        int skinG = (skinColor >> 8) & 0xFF;
        int skinB = skinColor & 0xFF;
        int hairColor = (255 << 24) | (50 << 16) | (40 << 8) | 30;

        // === FRONT FACE (detailed features) ===
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                int r = skinR + rand.nextInt(10) - 5;
                int g = skinG + rand.nextInt(10) - 5;
                int b = skinB + rand.nextInt(10) - 5;
                facePixels[y * S + x] = (255 << 24) | (r << 16) | (g << 8) | b;
            }
        }

        if (hasHair) {
            for (int y = 0; y < 4; y++) {
                for (int x = 2; x < 14; x++) {
                    if (rand.nextFloat() > 0.2f) facePixels[y * S + x] = hairColor;
                }
            }
        }

        int eyeColor = (255 << 24) | (60 << 16) | (100 << 8) | 180;
        int eyeWhite = (255 << 24) | (240 << 16) | (240 << 8) | 240;
        facePixels[6 * S + 4] = eyeWhite;
        facePixels[6 * S + 5] = eyeColor;
        facePixels[6 * S + 10] = eyeColor;
        facePixels[6 * S + 11] = eyeWhite;

        int browColor = (255 << 24) | (60 << 16) | (50 << 8) | 40;
        facePixels[5 * S + 4] = browColor;
        facePixels[5 * S + 5] = browColor;
        facePixels[5 * S + 10] = browColor;
        facePixels[5 * S + 11] = browColor;

        int noseColor = (255 << 24) | (Math.max(0, skinR - 20) << 16) | (Math.max(0, skinG - 20) << 8) | Math.max(0, skinB - 15);
        facePixels[8 * S + 7] = noseColor;
        facePixels[8 * S + 8] = noseColor;

        int mouthColor = (255 << 24) | (Math.max(0, skinR - 40) << 16) | (Math.max(0, skinG - 40) << 8) | Math.max(0, skinB - 30);
        for (int x = 6; x < 10; x++) facePixels[10 * S + x] = mouthColor;

        if (!hasHair && skinColor == 0xCC8844) {
            int mustacheColor = (255 << 24) | (100 << 16) | (70 << 8) | 40;
            for (int x = 5; x < 11; x++) facePixels[9 * S + x] = mustacheColor;
        }

        // === BUILD 64x16 ATLAS (front/back/side/top) ===
        int W = S * 4;
        int H = S;
        int[] atlas = new int[W * H];

        // Front section
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                atlas[y * W + x] = facePixels[y * S + x];
            }
        }

        Random backRand = new Random(skinColor + 4321);
        Random sideRand = new Random(skinColor + 6789);
        Random topRand = new Random(skinColor + 9876);

        // Back section
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                boolean hairRow = hasHair && y < 4;
                int r = skinR + backRand.nextInt(8) - 4;
                int g = skinG + backRand.nextInt(8) - 4;
                int b = skinB + backRand.nextInt(8) - 4;
                int color = hairRow ? hairColor : (255 << 24) | (r << 16) | (g << 8) | b;
                atlas[y * W + S + x] = color;
            }
        }

        // Side section (with simple ear highlight)
        int earColor = (255 << 24) | (Math.min(255, skinR + 10) << 16) | (Math.min(255, skinG + 5) << 8) | Math.max(0, skinB - 5);
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                boolean hairRow = hasHair && y < 4;
                int r = skinR + sideRand.nextInt(8) - 4;
                int g = skinG + sideRand.nextInt(8) - 4;
                int b = skinB + sideRand.nextInt(8) - 4;
                int color = hairRow ? hairColor : (255 << 24) | (r << 16) | (g << 8) | b;
                boolean earZone = (y >= 6 && y <= 9) && (x == 1 || x == 14);
                if (!hairRow && earZone) color = earColor;
                atlas[y * W + 2 * S + x] = color;
            }
        }

        // Top/bottom section (used for both in mesh)
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                int color;
                if (hasHair) {
                    int variation = topRand.nextInt(10) - 5;
                    int hr = Math.max(0, Math.min(255, 50 + variation));
                    int hg = Math.max(0, Math.min(255, 40 + variation));
                    int hb = Math.max(0, Math.min(255, 30 + variation));
                    color = (255 << 24) | (hr << 16) | (hg << 8) | hb;
                } else {
                    int r = skinR + topRand.nextInt(8) - 4;
                    int g = skinG + topRand.nextInt(8) - 4;
                    int b = skinB + topRand.nextInt(8) - 4;
                    color = (255 << 24) | (r << 16) | (g << 8) | b;
                }
                atlas[y * W + 3 * S + x] = color;
            }
        }

        return createTextureFromPixels(atlas, W, H);
    }

    // === PLAYER TEXTURE GENERATORS (distinct from NPCs) ===

    private int generatePlayerHeadAtlas() {
        int W = 64, H = 16, S = 16;
        int[] atlas = new int[W * H];
        int[] front = generatePlayerHeadFrontPixels();
        int[] back  = generatePlayerHeadBackPixels();
        int[] side  = generatePlayerHeadSidePixels();
        int[] top   = generatePlayerHeadTopPixels();
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                atlas[y * W + x]          = front[y * S + x];
                atlas[y * W + S + x]      = back[y * S + x];
                atlas[y * W + S * 2 + x]  = side[y * S + x];
                atlas[y * W + S * 3 + x]  = top[y * S + x];
            }
        }
        return createTextureFromPixels(atlas, W, H);
    }

    private int[] generatePlayerHeadFrontPixels() {
        int S = 16;
        int[] p = new int[S * S];
        // Player: younger face, black hair, blue eyes, no mustache, yellow hard hat
        int skin   = rgba(210, 170, 130);
        int skinLt = rgba(220, 180, 140);
        int skinDk = rgba(190, 150, 112);
        int skinNk = rgba(175, 138, 100);

        // Fill with skin
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++)
                p[y * S + x] = skin;

        // === YELLOW HARD HAT: rows 0-3 ===
        int hatY  = rgba(220, 190, 40);
        int hatLt = rgba(240, 210, 60);
        int hatDk = rgba(185, 155, 20);
        int hatBr = rgba(160, 135, 15);
        for (int x = 2; x <= 13; x++) p[0 * S + x] = hatDk;
        for (int x = 1; x <= 14; x++) p[1 * S + x] = hatY;
        for (int x = 0; x < S; x++) p[2 * S + x] = (x % 3 == 0) ? hatLt : hatY;
        for (int x = 0; x < S; x++) p[3 * S + x] = hatBr;

        // Black hair fringe below the brim
        int hairDk = rgba(30, 28, 25);
        int hairMd = rgba(45, 42, 38);
        for (int x = 2; x <= 13; x++) {
            p[4 * S + x] = (x % 2 == 0) ? hairDk : hairMd;
        }
        int brow = rgba(30, 28, 25);
        for (int x = 4; x <= 6; x++) p[5 * S + x] = brow;
        for (int x = 9; x <= 11; x++) p[5 * S + x] = brow;
        p[5 * S + 7] = skinLt; p[5 * S + 8] = skinLt;

        // === BLUE EYES: rows 6-7 ===
        int eyeW = rgba(235, 235, 235);
        int eyeB = rgba(40, 90, 200);
        int eyeBd = rgba(25, 65, 160);
        int pupil = rgba(10, 10, 10);
        // Left eye
        p[6 * S + 3] = skinDk; p[6 * S + 4] = eyeW; p[6 * S + 5] = eyeB; p[6 * S + 6] = eyeW;
        p[7 * S + 3] = skinDk; p[7 * S + 4] = eyeBd; p[7 * S + 5] = pupil; p[7 * S + 6] = eyeBd;
        // Right eye
        p[6 * S + 9] = eyeW; p[6 * S + 10] = eyeB; p[6 * S + 11] = eyeW; p[6 * S + 12] = skinDk;
        p[7 * S + 9] = eyeBd; p[7 * S + 10] = pupil; p[7 * S + 11] = eyeBd; p[7 * S + 12] = skinDk;

        // Under-eye
        for (int x = 3; x <= 6; x++) p[8 * S + x] = skinDk;
        for (int x = 9; x <= 12; x++) p[8 * S + x] = skinDk;

        // === NOSE: rows 8-9 ===
        int nose = rgba(195, 150, 115);
        int noseT = rgba(185, 142, 108);
        p[8 * S + 7] = nose; p[8 * S + 8] = nose;
        p[9 * S + 6] = skinDk; p[9 * S + 7] = noseT; p[9 * S + 8] = noseT; p[9 * S + 9] = skinDk;

        // === NO MUSTACHE - clean shaven ===
        // Simple smile line
        int mouth = rgba(175, 115, 85);
        int mouthDk = rgba(140, 85, 60);
        p[11 * S + 6] = mouth; p[11 * S + 7] = mouthDk; p[11 * S + 8] = mouthDk; p[11 * S + 9] = mouth;

        // Chin
        for (int x = 3; x <= 12; x++) p[12 * S + x] = skin;
        p[12 * S + 2] = skinDk; p[12 * S + 13] = skinDk;
        for (int x = 4; x <= 11; x++) p[13 * S + x] = skinDk;

        // Neck
        for (int x = 5; x <= 10; x++) p[14 * S + x] = skinNk;
        for (int x = 5; x <= 10; x++) p[15 * S + x] = skinNk;

        // Ears
        int ear = rgba(200, 158, 118);
        for (int y = 6; y <= 9; y++) { p[y * S + 1] = ear; p[y * S + 14] = ear; }

        return p;
    }

    private int[] generatePlayerHeadBackPixels() {
        int S = 16;
        int[] p = new int[S * S];
        int skin   = rgba(210, 170, 130);
        int skinDk = rgba(190, 150, 112);
        int hairDk = rgba(30, 28, 25);
        int hairMd = rgba(45, 42, 38);
        int hatY   = rgba(220, 190, 40);
        int hatDk  = rgba(185, 155, 20);
        int hatBr  = rgba(160, 135, 15);
        // Hard hat
        for (int x = 2; x <= 13; x++) p[0 * S + x] = hatDk;
        for (int x = 1; x <= 14; x++) p[1 * S + x] = hatY;
        for (int x = 0; x <= 15; x++) p[2 * S + x] = hatY;
        for (int x = 0; x <= 15; x++) p[3 * S + x] = hatBr;
        // Black hair below hat
        for (int y = 4; y < 8; y++)
            for (int x = 0; x < S; x++)
                p[y * S + x] = (x == 0 || x == 15 || y == 4) ? hairDk : hairMd;
        // Skin below hair
        for (int y = 8; y < 14; y++)
            for (int x = 0; x < S; x++)
                p[y * S + x] = (x == 0 || x == 15) ? skinDk : skin;
        // Neck
        for (int x = 0; x < S; x++) p[14 * S + x] = skinDk;
        for (int x = 0; x < S; x++) p[15 * S + x] = skinDk;
        return p;
    }

    private int[] generatePlayerHeadSidePixels() {
        int S = 16;
        int[] p = new int[S * S];
        int skin   = rgba(210, 170, 130);
        int skinDk = rgba(190, 150, 112);
        int hairDk = rgba(30, 28, 25);
        int hairMd = rgba(45, 42, 38);
        int hatY   = rgba(220, 190, 40);
        int hatDk  = rgba(185, 155, 20);
        int hatBr  = rgba(160, 135, 15);
        int ear    = rgba(200, 158, 118);
        int earDk  = rgba(180, 138, 98);
        // Hard hat
        for (int x = 0; x < S; x++) p[0 * S + x] = hatDk;
        for (int x = 0; x < S; x++) p[1 * S + x] = hatY;
        for (int x = 0; x < S; x++) p[2 * S + x] = hatY;
        for (int x = 0; x < S; x++) p[3 * S + x] = hatBr;
        // Hair
        for (int y = 4; y <= 6; y++)
            for (int x = 0; x < S; x++)
                p[y * S + x] = (y == 4) ? hairDk : hairMd;
        // Skin face
        for (int y = 5; y < 14; y++)
            for (int x = 0; x < S; x++)
                p[y * S + x] = skin;
        // Hair sideburns
        for (int y = 4; y <= 6; y++) {
            p[y * S + 0] = hairDk; p[y * S + 1] = hairMd;
            p[y * S + 14] = hairMd; p[y * S + 15] = hairDk;
        }
        // Ear
        for (int y = 7; y <= 10; y++)
            for (int x = 5; x <= 8; x++)
                p[y * S + x] = (y == 7 || y == 10 || x == 5 || x == 8) ? earDk : ear;
        // Neck
        for (int x = 0; x < S; x++) p[14 * S + x] = skinDk;
        for (int x = 0; x < S; x++) p[15 * S + x] = skinDk;
        return p;
    }

    private int[] generatePlayerHeadTopPixels() {
        int S = 16;
        int[] p = new int[S * S];
        // All yellow hard hat from top
        int hatY  = rgba(220, 190, 40);
        int hatLt = rgba(240, 210, 60);
        int hatDk = rgba(185, 155, 20);
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                if (y == 0 || y == 15 || x == 0 || x == 15) p[y * S + x] = hatDk;
                else if ((x + y) % 5 == 0) p[y * S + x] = hatLt;
                else p[y * S + x] = hatY;
            }
        // Center ridge/line
        for (int y = 2; y <= 13; y++) p[y * S + 8] = hatDk;
        return p;
    }

    private int generatePlayerBodyTexture() {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        int S = TEXTURE_SIZE;

        // === BLUE JUMPSUIT ===
        int blueM  = rgba(55, 80, 140);
        int blueLt = rgba(70, 95, 160);
        int blueDk = rgba(40, 60, 110);
        int blueSh = rgba(30, 48, 88);

        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                if ((x + y) % 5 == 0) pixels[y * S + x] = blueLt;
                else if ((x * 3 + y * 2) % 7 == 0) pixels[y * S + x] = blueDk;
                else pixels[y * S + x] = blueM;
            }

        // Collar: lighter blue
        int collarB = rgba(65, 88, 148);
        int collarD = rgba(45, 65, 115);
        for (int x = 0; x < S; x++) { pixels[0 * S + x] = collarB; pixels[1 * S + x] = collarB; }
        for (int x = 6; x <= 9; x++) { pixels[0 * S + x] = collarD; }
        pixels[1 * S + 7] = collarD; pixels[1 * S + 8] = collarD;

        // Center zipper line
        int zipper = rgba(150, 150, 155);
        int zipperDk = rgba(120, 120, 125);
        for (int y = 2; y < S; y++) { pixels[y * S + 7] = zipper; pixels[y * S + 8] = zipperDk; }

        // Upper left chest: radiation badge (yellow/black)
        int badgeY = rgba(220, 190, 40);
        int badgeB = rgba(25, 25, 28);
        for (int x = 2; x <= 5; x++) { pixels[3 * S + x] = badgeY; pixels[6 * S + x] = badgeY; }
        for (int y = 3; y <= 6; y++) { pixels[y * S + 2] = badgeY; pixels[y * S + 5] = badgeY; }
        // Radiation trefoil inside badge
        pixels[4 * S + 3] = badgeB; pixels[4 * S + 4] = badgeB;
        pixels[5 * S + 3] = badgeB; pixels[5 * S + 4] = badgeB;

        // "ЧАЭС" text area - right chest pocket
        int pocketLn = rgba(48, 68, 118);
        for (int x = 10; x <= 14; x++) { pixels[3 * S + x] = pocketLn; pixels[6 * S + x] = pocketLn; }
        for (int y = 3; y <= 6; y++) { pixels[y * S + 10] = pocketLn; pixels[y * S + 14] = pocketLn; }

        // Side shadows
        for (int y = 2; y < S; y++) {
            pixels[y * S + 0] = blueSh; pixels[y * S + 15] = blueSh;
            pixels[y * S + 1] = blueDk; pixels[y * S + 14] = blueDk;
        }

        // Belt at waist (row 9-10)
        int belt  = rgba(60, 50, 35);
        int beltB = rgba(160, 140, 50); // buckle
        for (int x = 0; x < S; x++) { pixels[9 * S + x] = belt; pixels[10 * S + x] = belt; }
        pixels[9 * S + 7] = beltB; pixels[9 * S + 8] = beltB;
        pixels[10 * S + 7] = beltB; pixels[10 * S + 8] = beltB;

        // Lower pockets
        for (int x = 2; x <= 6; x++) { pixels[11 * S + x] = pocketLn; pixels[14 * S + x] = pocketLn; }
        for (int y = 11; y <= 14; y++) { pixels[y * S + 2] = pocketLn; pixels[y * S + 6] = pocketLn; }
        for (int x = 9; x <= 13; x++) { pixels[11 * S + x] = pocketLn; pixels[14 * S + x] = pocketLn; }
        for (int y = 11; y <= 14; y++) { pixels[y * S + 9] = pocketLn; pixels[y * S + 13] = pocketLn; }

        // Bottom hem
        for (int x = 0; x < S; x++) pixels[15 * S + x] = blueSh;

        return createTextureFromPixels(pixels);
    }

    private int generatePlayerArmsTexture() {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        int S = TEXTURE_SIZE;

        // Blue jumpsuit sleeve
        int blueM  = rgba(55, 80, 140);
        int blueLt = rgba(70, 95, 160);
        int blueDk = rgba(40, 60, 110);
        int blueSh = rgba(30, 48, 88);

        for (int y = 0; y < 10; y++)
            for (int x = 0; x < S; x++) {
                if (x == 0 || x == 15) pixels[y * S + x] = blueSh;
                else if (x == 1 || x == 14) pixels[y * S + x] = blueDk;
                else if ((x + y) % 4 == 0) pixels[y * S + x] = blueLt;
                else pixels[y * S + x] = blueM;
            }
        // Shoulder seam
        for (int x = 0; x < S; x++) pixels[0 * S + x] = blueSh;

        // Orange cuff stripe (safety marking)
        int orangeM = rgba(220, 130, 30);
        int orangeDk = rgba(185, 105, 20);
        for (int x = 0; x < S; x++) {
            pixels[10 * S + x] = orangeM;
            pixels[11 * S + x] = orangeDk;
        }

        // Work gloves (darker, thicker hands)
        int gloveM  = rgba(140, 110, 60);
        int gloveDk = rgba(110, 85, 45);
        int gloveLt = rgba(160, 130, 75);
        for (int y = 12; y < S; y++)
            for (int x = 0; x < S; x++) {
                if (x == 0 || x == 15) pixels[y * S + x] = gloveDk;
                else if (y == 12) pixels[y * S + x] = gloveLt;
                else pixels[y * S + x] = gloveM;
            }
        // Glove knuckle stitching
        pixels[13 * S + 4] = gloveDk; pixels[13 * S + 7] = gloveDk;
        pixels[13 * S + 10] = gloveDk; pixels[13 * S + 13] = gloveDk;
        for (int x = 2; x <= 13; x++) pixels[15 * S + x] = gloveDk;

        return createTextureFromPixels(pixels);
    }

    private int generatePlayerLegsTexture() {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        int S = TEXTURE_SIZE;

        // Blue jumpsuit pants
        int blueM  = rgba(50, 72, 130);
        int blueLt = rgba(65, 88, 148);
        int blueDk = rgba(38, 55, 100);
        int blueSh = rgba(28, 42, 78);

        for (int y = 0; y < 11; y++)
            for (int x = 0; x < S; x++) {
                if (x == 0 || x == 15) pixels[y * S + x] = blueSh;
                else if (x == 1 || x == 14) pixels[y * S + x] = blueDk;
                else if ((x + y * 2) % 5 == 0) pixels[y * S + x] = blueLt;
                else pixels[y * S + x] = blueM;
            }
        // Pant leg seam
        for (int y = 0; y < 11; y++) pixels[y * S + 8] = blueDk;
        // Waistband
        for (int x = 0; x < S; x++) pixels[0 * S + x] = blueDk;
        // Knee crease
        for (int x = 3; x <= 12; x++) pixels[6 * S + x] = blueDk;

        // Orange ankle stripe (safety marking)
        int orangeM = rgba(220, 130, 30);
        int orangeDk = rgba(185, 105, 20);
        for (int x = 0; x < S; x++) pixels[11 * S + x] = orangeM;
        for (int x = 0; x < S; x++) pixels[12 * S + x] = orangeDk;

        // Brown heavy work boots
        int bootM  = rgba(85, 55, 30);
        int bootDk = rgba(60, 38, 18);
        int bootH  = rgba(110, 75, 42);
        for (int x = 0; x < S; x++) pixels[13 * S + x] = bootH;
        for (int y = 14; y < S; y++)
            for (int x = 0; x < S; x++)
                pixels[y * S + x] = bootM;
        for (int x = 1; x <= 14; x++) pixels[15 * S + x] = bootDk;
        pixels[14 * S + 2] = bootDk; pixels[14 * S + 3] = bootH;
        pixels[14 * S + 12] = bootH; pixels[14 * S + 13] = bootDk;

        return createTextureFromPixels(pixels);
    }


    private int generateAkimovHeadAtlas() {
        int W = 64, H = 16, S = 16;
        int[] atlas = new int[W * H];
        int[] front = generateAkimovHeadFrontPixels();
        int[] back  = generateHeadBackPixels(198, 156, 108, 139, 90, 43);     // Akimov skin + hair colors
        int[] side  = generateHeadSidePixels(198, 156, 108, 139, 90, 43, 190, 148, 100); // + ear
        int[] top   = generateHeadTopPixels(139, 90, 43);                     // hair color
        // Copy each 16x16 into the atlas
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                atlas[y * W + x]          = front[y * S + x]; // section 0: front
                atlas[y * W + S + x]      = back[y * S + x];  // section 1: back
                atlas[y * W + S * 2 + x]  = side[y * S + x];  // section 2: side
                atlas[y * W + S * 3 + x]  = top[y * S + x];   // section 3: top
            }
        }
        return createTextureFromPixels(atlas, W, H);
    }

    private int generateToptunvHeadAtlas() {
        int W = 64, H = 16, S = 16;
        int[] atlas = new int[W * H];
        int[] front = generateToptunvHeadFrontPixels();
        int[] back  = generateHeadBackPixels(204, 136, 68, 100, 70, 40);
        int[] side  = generateHeadSidePixels(204, 136, 68, 100, 70, 40, 190, 130, 65);
        int[] top   = generateHeadTopPixels(100, 70, 40);
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                atlas[y * W + x]          = front[y * S + x];
                atlas[y * W + S + x]      = back[y * S + x];
                atlas[y * W + S * 2 + x]  = side[y * S + x];
                atlas[y * W + S * 3 + x]  = top[y * S + x];
            }
        }
        return createTextureFromPixels(atlas, W, H);
    }

    private int generateDyatlovHeadAtlas() 
    {
        int W = 64, H = 16, S = 16;
        int[] atlas = new int[W * H];
        int[] front = generateDyatlovHeadFrontPixels();
        // Dyatlov: pale skin, gray/thin hair, balding
        int[] back  = generateHeadBackPixels(222, 184, 150, 140, 135, 130);
        int[] side  = generateHeadSidePixels(222, 184, 150, 140, 135, 130, 215, 178, 145);
        int[] top   = generateHeadTopPixels(140, 135, 130); // Gray sparse hair
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                atlas[y * W + x]          = front[y * S + x];
                atlas[y * W + S + x]      = back[y * S + x];
                atlas[y * W + S * 2 + x]  = side[y * S + x];
                atlas[y * W + S * 3 + x]  = top[y * S + x];
            }
        }
        return createTextureFromPixels(atlas, W, H);
    }

    // Perevozchenko: reactor section foreman, young worker with orange hard hat
    private int generatePerevozchHeadAtlas() {
        int W = 64, H = 16, S = 16;
        int[] atlas = new int[W * H];
        int[] front = generatePerevozchHeadFrontPixels();
        // Tanned skin (210,170,130), dark brown hair (90,60,35) mostly hidden under hard hat
        int[] back  = generateHeadBackPixels(210, 170, 130, 90, 60, 35);
        int[] side  = generateHeadSidePixels(210, 170, 130, 90, 60, 35, 200, 160, 120);
        // Top: orange hard hat color
        int[] top   = generateHeadTopPixels(220, 120, 30);
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                atlas[y * W + x]          = front[y * S + x];
                atlas[y * W + S + x]      = back[y * S + x];
                atlas[y * W + S * 2 + x]  = side[y * S + x];
                atlas[y * W + S * 3 + x]  = top[y * S + x];
            }
        }
        return createTextureFromPixels(atlas, W, H);
    }

    private int[] generatePerevozchHeadFrontPixels() {
        int S = 16;
        int[] pixels = new int[S * S];
        // Perevozchenko: young, tanned worker skin, short dark hair, orange hard hat, blue eyes
        int skin     = rgba(210, 170, 130);
        int skinLt   = rgba(220, 182, 142);
        int skinDk   = rgba(190, 150, 112);
        int skinNeck = rgba(180, 140, 102);

        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++)
                pixels[y * S + x] = skin;

        // Orange hard hat (top 4 rows)
        int hatOr  = rgba(220, 120, 30);
        int hatDk  = rgba(190, 100, 20);
        int hatLt  = rgba(240, 140, 45);
        for (int x = 1; x <= 14; x++) pixels[0 * S + x] = hatDk;
        for (int x = 0; x <= 15; x++) pixels[1 * S + x] = hatOr;
        for (int x = 0; x <= 15; x++) pixels[2 * S + x] = (x == 4 || x == 11) ? hatLt : hatOr;
        for (int x = 1; x <= 14; x++) pixels[3 * S + x] = hatDk; // Hat brim
        pixels[3 * S + 0] = hatDk; pixels[3 * S + 15] = hatDk;

        // Short dark hair visible below hat brim on sides
        int hairDk = rgba(90, 60, 35);
        pixels[4 * S + 0] = hairDk; pixels[4 * S + 1] = hairDk;
        pixels[4 * S + 14] = hairDk; pixels[4 * S + 15] = hairDk;
        pixels[5 * S + 0] = hairDk; pixels[5 * S + 15] = hairDk;

        // Forehead
        for (int x = 2; x <= 13; x++) pixels[4 * S + x] = skinLt;

        // Eyebrows - dark, strong worker brows
        int brow = rgba(75, 50, 28);
        for (int x = 3; x <= 6; x++) pixels[5 * S + x] = brow;
        for (int x = 9; x <= 12; x++) pixels[5 * S + x] = brow;

        // Blue eyes
        int eyeW = rgba(235, 235, 235);
        int eyeB = rgba(50, 120, 200);
        int eyeBd = rgba(35, 90, 160);
        int pupil = rgba(15, 15, 15);
        pixels[6 * S + 3] = skinDk; pixels[6 * S + 4] = eyeW; pixels[6 * S + 5] = eyeB; pixels[6 * S + 6] = eyeW;
        pixels[7 * S + 3] = skinDk; pixels[7 * S + 4] = eyeBd; pixels[7 * S + 5] = pupil; pixels[7 * S + 6] = eyeBd;
        pixels[6 * S + 9] = eyeW; pixels[6 * S + 10] = eyeB; pixels[6 * S + 11] = eyeW; pixels[6 * S + 12] = skinDk;
        pixels[7 * S + 9] = eyeBd; pixels[7 * S + 10] = pupil; pixels[7 * S + 11] = eyeBd; pixels[7 * S + 12] = skinDk;

        // Under-eye shadows (faint, young face)
        for (int x = 3; x <= 6; x++) pixels[8 * S + x] = skinDk;
        for (int x = 9; x <= 12; x++) pixels[8 * S + x] = skinDk;

        // Nose
        int nose = rgba(195, 155, 118);
        int noseTip = rgba(185, 145, 108);
        pixels[8 * S + 7] = nose; pixels[8 * S + 8] = nose;
        pixels[9 * S + 6] = skinDk; pixels[9 * S + 7] = noseTip; pixels[9 * S + 8] = noseTip; pixels[9 * S + 9] = skinDk;

        // Light stubble (worker)
        int stubble = rgba(175, 140, 105);
        pixels[10 * S + 4] = stubble; pixels[10 * S + 6] = stubble;
        pixels[10 * S + 9] = stubble; pixels[10 * S + 11] = stubble;
        pixels[11 * S + 3] = stubble; pixels[11 * S + 5] = stubble;
        pixels[11 * S + 10] = stubble; pixels[11 * S + 12] = stubble;

        // Mouth
        int mouth = rgba(160, 105, 72);
        int mouthDk = rgba(130, 80, 55);
        pixels[11 * S + 6] = mouth;
        for (int x = 7; x <= 8; x++) pixels[11 * S + x] = mouthDk;
        pixels[11 * S + 9] = mouth;

        // Chin + jaw (broader face)
        for (int x = 2; x <= 13; x++) pixels[12 * S + x] = skin;
        pixels[13 * S + 1] = skinDk; pixels[13 * S + 14] = skinDk;
        for (int x = 3; x <= 12; x++) pixels[13 * S + x] = skin;
        for (int x = 4; x <= 11; x++) pixels[14 * S + x] = skinDk;

        // Neck
        for (int x = 4; x <= 11; x++) pixels[15 * S + x] = skinNeck;

        // Ears
        int ear = rgba(200, 160, 120);
        for (int y = 6; y <= 9; y++) { pixels[y * S + 1] = ear; pixels[y * S + 14] = ear; }

        return pixels;
    }

    private int[] generateDyatlovHeadFrontPixels() {
        int S = 16;
        int[] pixels = new int[S * S];
        // Dyatlov: older, paler skin, balding with gray hair on sides, stern face, glasses
        int skin     = rgba(222, 184, 150);
        int skinLt   = rgba(232, 195, 162);
        int skinDk   = rgba(200, 165, 132);
        int skinNeck = rgba(190, 155, 122);

        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++)
                pixels[y * S + x] = skin;

        // Balding top - mostly skin with thin gray hair on sides
        int hairGray = rgba(140, 135, 130);
        int hairDkGr = rgba(110, 105, 100);
        // Sparse gray hair on top edges
        for (int y = 0; y <= 1; y++) {
            for (int x = 0; x <= 3; x++) pixels[y * S + x] = hairGray;
            for (int x = 12; x <= 15; x++) pixels[y * S + x] = hairGray;
        }
        for (int x = 0; x <= 2; x++) pixels[2 * S + x] = hairDkGr;
        for (int x = 13; x <= 15; x++) pixels[2 * S + x] = hairDkGr;
        pixels[3 * S + 0] = hairDkGr; pixels[3 * S + 15] = hairDkGr;
        // Forehead wrinkles (age lines)
        pixels[3 * S + 4] = skinDk; pixels[3 * S + 7] = skinDk;
        pixels[3 * S + 8] = skinDk; pixels[3 * S + 11] = skinDk;
        pixels[4 * S + 5] = skinDk; pixels[4 * S + 10] = skinDk;

        // Thick stern eyebrows
        int brow = rgba(90, 85, 80);
        for (int x = 3; x <= 6; x++) pixels[5 * S + x] = brow;
        for (int x = 9; x <= 12; x++) pixels[5 * S + x] = brow;

        // Glasses frames
        int frame = rgba(60, 55, 50);
        // Left lens frame
        pixels[6 * S + 2] = frame; pixels[6 * S + 7] = frame;
        pixels[8 * S + 2] = frame; pixels[8 * S + 7] = frame;
        for (int x = 2; x <= 7; x++) { pixels[5 * S + x] = (pixels[5 * S + x] == brow) ? brow : frame; }
        pixels[7 * S + 2] = frame; pixels[7 * S + 7] = frame;
        // Right lens frame
        pixels[6 * S + 8] = frame; pixels[6 * S + 13] = frame;
        pixels[8 * S + 8] = frame; pixels[8 * S + 13] = frame;
        pixels[7 * S + 8] = frame; pixels[7 * S + 13] = frame;
        // Bridge
        pixels[7 * S + 7] = frame; pixels[7 * S + 8] = frame;

        // Gray-blue eyes behind glasses
        int eyeW = rgba(230, 230, 230);
        int eyeGr = rgba(100, 120, 140);
        int pupil = rgba(10, 10, 10);
        pixels[6 * S + 4] = eyeW; pixels[6 * S + 5] = eyeGr; pixels[6 * S + 6] = eyeW;
        pixels[7 * S + 4] = eyeGr; pixels[7 * S + 5] = pupil; pixels[7 * S + 6] = eyeGr;
        pixels[6 * S + 10] = eyeW; pixels[6 * S + 11] = eyeGr; pixels[6 * S + 12] = eyeW;
        pixels[7 * S + 10] = eyeGr; pixels[7 * S + 11] = pupil; pixels[7 * S + 12] = eyeGr;

        // Under-eye bags (he looks tired/stern)
        for (int x = 3; x <= 6; x++) pixels[8 * S + x] = skinDk;
        for (int x = 9; x <= 12; x++) pixels[8 * S + x] = skinDk;

        // Nose - slightly larger
        int nose = rgba(210, 170, 138);
        int noseTip = rgba(200, 162, 130);
        pixels[8 * S + 7] = nose; pixels[8 * S + 8] = nose;
        pixels[9 * S + 6] = skinDk; pixels[9 * S + 7] = noseTip; pixels[9 * S + 8] = noseTip; pixels[9 * S + 9] = skinDk;
        pixels[10 * S + 7] = noseTip; pixels[10 * S + 8] = noseTip;

        // Thin stern mouth (no mustache - Dyatlov was clean-shaven)
        int mouth = rgba(180, 130, 105);
        int mouthDk = rgba(150, 105, 82);
        pixels[11 * S + 5] = mouth; pixels[11 * S + 6] = mouthDk;
        pixels[11 * S + 7] = mouthDk; pixels[11 * S + 8] = mouthDk;
        pixels[11 * S + 9] = mouthDk; pixels[11 * S + 10] = mouth;

        // Chin / jaw - more defined
        for (int x = 3; x <= 12; x++) pixels[13 * S + x] = skin;
        pixels[13 * S + 2] = skinDk; pixels[13 * S + 13] = skinDk;
        pixels[12 * S + 3] = skinDk; pixels[12 * S + 12] = skinDk;
        for (int x = 4; x <= 11; x++) pixels[14 * S + x] = skinDk;

        // Neck
        for (int x = 4; x <= 11; x++) pixels[15 * S + x] = skinNeck;

        // Ears
        int ear = rgba(215, 178, 145);
        for (int y = 6; y <= 9; y++) { pixels[y * S + 1] = ear; pixels[y * S + 14] = ear; }

        return pixels;
    }

    // Back of head: hair on top, skin below
    private int[] generateHeadBackPixels(int skinR, int skinG, int skinB, int hairR, int hairG, int hairB) {
        int S = 16;
        int[] p = new int[S * S];
        int skin = rgba(skinR, skinG, skinB);
        int skinDk = rgba(skinR - 15, skinG - 15, skinB - 12);
        int hair = rgba(hairR, hairG, hairB);
        int hairDk = rgba(hairR - 25, hairG - 20, hairB - 15);
        // Hair covers top 7 rows from behind (more coverage than front)
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                if (y < 3) p[y * S + x] = hairDk;
                else if (y < 7) p[y * S + x] = hair;
                else if (y < 14) p[y * S + x] = (x == 0 || x == 15) ? skinDk : skin;
                else p[y * S + x] = skinDk; // neck
            }
        return p;
    }

    // Side of head: hair on top, skin, ear bump in middle
    private int[] generateHeadSidePixels(int skinR, int skinG, int skinB, int hairR, int hairG, int hairB, int earR, int earG, int earB) {
        int S = 16;
        int[] p = new int[S * S];
        int skin = rgba(skinR, skinG, skinB);
        int skinDk = rgba(skinR - 15, skinG - 15, skinB - 12);
        int hair = rgba(hairR, hairG, hairB);
        int hairDk = rgba(hairR - 25, hairG - 20, hairB - 15);
        int ear = rgba(earR, earG, earB);
        int earDk = rgba(earR - 20, earG - 20, earB - 15);

        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                if (y < 2) p[y * S + x] = hairDk;
                else if (y < 5) p[y * S + x] = hair;
                else if (y < 14) p[y * S + x] = skin;
                else p[y * S + x] = skinDk;
            }
        // Ear (rows 6-10, columns 5-8)
        for (int y = 6; y <= 10; y++)
            for (int x = 5; x <= 8; x++)
                p[y * S + x] = (y == 6 || y == 10 || x == 5 || x == 8) ? earDk : ear;
        // Sideburn (hair coming down side, rows 5-6, cols 0-3)
        for (int y = 5; y <= 6; y++)
            for (int x = 0; x <= 2; x++)
                p[y * S + x] = hair;
        return p;
    }

    // Top of head: all hair
    private int[] generateHeadTopPixels(int hairR, int hairG, int hairB) {
        int S = 16;
        int[] p = new int[S * S];
        int hair = rgba(hairR, hairG, hairB);
        int hairDk = rgba(hairR - 20, hairG - 15, hairB - 10);
        int hairLt = rgba(hairR + 15, hairG + 12, hairB + 8);
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++)
                p[y * S + x] = ((x + y) % 4 == 0) ? hairLt : ((x * 3 + y) % 5 == 0) ? hairDk : hair;
        return p;
    }

    // Akimov front face pixels (same detail as before, returns raw pixel array)
    private int[] generateAkimovHeadFrontPixels() {
        int S = 16;
        int[] pixels = new int[S * S];

        int skin     = rgba(198, 156, 108);
        int skinLt   = rgba(208, 168, 120);
        int skinDk   = rgba(178, 136, 92);
        int skinNeck = rgba(170, 128, 85);

        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++)
                pixels[y * S + x] = skin;

        // Hair
        int hairDk = rgba(101, 67, 33);
        int hairMd = rgba(130, 85, 40);
        int hairLt = rgba(150, 100, 50);
        for (int x = 0; x < S; x++) pixels[0 * S + x] = hairDk;
        for (int x = 0; x < S; x++) pixels[1 * S + x] = (x == 3 || x == 7 || x == 12) ? hairLt : hairDk;
        for (int x = 0; x < S; x++) pixels[2 * S + x] = (x == 5 || x == 10) ? hairLt : hairMd;
        for (int x = 2; x <= 13; x++) pixels[3 * S + x] = hairMd;
        pixels[3 * S + 4] = hairLt; pixels[3 * S + 11] = hairLt;
        pixels[3 * S + 0] = hairDk; pixels[3 * S + 1] = hairDk;
        pixels[3 * S + 14] = hairDk; pixels[3 * S + 15] = hairDk;
        pixels[4 * S + 0] = hairDk; pixels[4 * S + 1] = hairMd;
        pixels[4 * S + 14] = hairMd; pixels[4 * S + 15] = hairDk;
        pixels[5 * S + 0] = hairMd; pixels[5 * S + 15] = hairMd;

        // Forehead
        for (int x = 2; x <= 13; x++) pixels[4 * S + x] = skinLt;

        // Eyebrows
        int brow = rgba(85, 58, 30);
        pixels[5 * S + 4] = brow; pixels[5 * S + 5] = brow; pixels[5 * S + 6] = brow;
        pixels[5 * S + 9] = brow; pixels[5 * S + 10] = brow; pixels[5 * S + 11] = brow;
        pixels[5 * S + 7] = skinLt; pixels[5 * S + 8] = skinLt;

        // Green eyes
        int eyeW = rgba(235, 235, 235);
        int eyeG = rgba(20, 150, 50);
        int eyeGd = rgba(10, 110, 35);
        int pupil = rgba(15, 15, 15);
        pixels[6 * S + 3] = skinDk; pixels[6 * S + 4] = eyeW; pixels[6 * S + 5] = eyeG; pixels[6 * S + 6] = eyeW;
        pixels[7 * S + 3] = skinDk; pixels[7 * S + 4] = eyeGd; pixels[7 * S + 5] = pupil; pixels[7 * S + 6] = eyeGd;
        pixels[6 * S + 9] = eyeW; pixels[6 * S + 10] = eyeG; pixels[6 * S + 11] = eyeW; pixels[6 * S + 12] = skinDk;
        pixels[7 * S + 9] = eyeGd; pixels[7 * S + 10] = pupil; pixels[7 * S + 11] = eyeGd; pixels[7 * S + 12] = skinDk;

        // Under-eye shadows
        for (int x = 3; x <= 6; x++) pixels[8 * S + x] = skinDk;
        for (int x = 9; x <= 12; x++) pixels[8 * S + x] = skinDk;

        // Nose
        int nose = rgba(185, 138, 95);
        int noseTip = rgba(175, 130, 88);
        pixels[8 * S + 7] = nose; pixels[8 * S + 8] = nose;
        pixels[9 * S + 6] = skinDk; pixels[9 * S + 7] = noseTip; pixels[9 * S + 8] = noseTip; pixels[9 * S + 9] = skinDk;

        // Mustache
        int mustDk = rgba(115, 75, 35);
        int mustMd = rgba(135, 90, 45);
        int mustLt = rgba(155, 105, 55);
        pixels[10 * S + 4] = mustLt;
        for (int x = 5; x <= 10; x++) pixels[10 * S + x] = mustDk;
        pixels[10 * S + 11] = mustLt;
        pixels[11 * S + 3] = mustLt;
        for (int x = 4; x <= 11; x++) pixels[11 * S + x] = (x == 7 || x == 8) ? mustMd : mustDk;
        pixels[11 * S + 12] = mustLt;

        // Mouth
        int mouth = rgba(140, 95, 65);
        int mouthDk = rgba(110, 70, 48);
        pixels[12 * S + 5] = mouth;
        for (int x = 6; x <= 9; x++) pixels[12 * S + x] = mouthDk;
        pixels[12 * S + 10] = mouth;

        // Chin + jaw
        for (int x = 3; x <= 12; x++) pixels[13 * S + x] = skin;
        pixels[13 * S + 2] = skinDk; pixels[13 * S + 13] = skinDk;
        for (int x = 4; x <= 11; x++) pixels[14 * S + x] = skinDk;

        // Neck
        for (int x = 4; x <= 11; x++) pixels[15 * S + x] = skinNeck;

        // Ears
        int ear = rgba(190, 148, 100);
        for (int y = 6; y <= 9; y++) { pixels[y * S + 1] = ear; pixels[y * S + 14] = ear; }

        return pixels;
    }

    // Toptunov front face
    private int[] generateToptunvHeadFrontPixels() {
        int S = 16;
        int[] pixels = new int[S * S];
        int skin = rgba(204, 136, 68);
        int skinLt = rgba(215, 150, 82);
        int skinDk = rgba(185, 120, 58);
        int skinNeck = rgba(170, 110, 50);

        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++)
                pixels[y * S + x] = skin;

        // No hair on front (bald/thin on top), just skin forehead
        // Slight hair on sides
        int hairSp = rgba(100, 70, 40);
        for (int y = 0; y <= 2; y++) {
            for (int x = 0; x <= 2; x++) pixels[y * S + x] = hairSp;
            for (int x = 13; x <= 15; x++) pixels[y * S + x] = hairSp;
        }
        for (int x = 0; x <= 1; x++) pixels[3 * S + x] = hairSp;
        for (int x = 14; x <= 15; x++) pixels[3 * S + x] = hairSp;

        // Eyebrows
        int brow = rgba(80, 55, 30);
        pixels[5 * S + 4] = brow; pixels[5 * S + 5] = brow; pixels[5 * S + 6] = brow;
        pixels[5 * S + 9] = brow; pixels[5 * S + 10] = brow; pixels[5 * S + 11] = brow;

        // Blue eyes (different from Akimov)
        int eyeW = rgba(235, 235, 235);
        int eyeB = rgba(60, 100, 180);
        int pupil = rgba(15, 15, 15);
        pixels[6 * S + 4] = eyeW; pixels[6 * S + 5] = eyeB; pixels[6 * S + 6] = eyeW;
        pixels[7 * S + 4] = eyeB; pixels[7 * S + 5] = pupil; pixels[7 * S + 6] = eyeB;
        pixels[6 * S + 10] = eyeW; pixels[6 * S + 11] = eyeB; pixels[6 * S + 12] = eyeW;
        pixels[7 * S + 10] = eyeB; pixels[7 * S + 11] = pupil; pixels[7 * S + 12] = eyeB;

        // Nose
        int nose = rgba(185, 118, 58);
        pixels[8 * S + 7] = nose; pixels[8 * S + 8] = nose;
        pixels[9 * S + 7] = nose; pixels[9 * S + 8] = nose;

        // Mustache
        int must = rgba(100, 70, 40);
        for (int x = 5; x <= 10; x++) pixels[10 * S + x] = must;

        // Mouth
        int mouth = rgba(160, 100, 55);
        for (int x = 6; x <= 9; x++) pixels[11 * S + x] = mouth;

        // Chin / neck
        for (int x = 4; x <= 11; x++) pixels[14 * S + x] = skinDk;
        for (int x = 5; x <= 10; x++) pixels[15 * S + x] = skinNeck;

        // Ears
        int ear = rgba(190, 130, 65);
        for (int y = 6; y <= 9; y++) { pixels[y * S + 1] = ear; pixels[y * S + 14] = ear; }

        return pixels;
    }

    // --- AKIMOV SKIN (matches reference Minecraft skin exactly) ---
    private int generateAkimovHeadTexture() {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        int S = TEXTURE_SIZE; // 16

        // === Skin base: warm golden-tan matching reference ===
        int skin     = rgba(198, 156, 108);  // main skin
        int skinLt   = rgba(208, 168, 120);  // highlight skin (forehead, cheeks)
        int skinDk   = rgba(178, 136, 92);   // shadow skin (under features)
        int skinNeck = rgba(170, 128, 85);   // neck shadow

        // Fill all with base skin
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++)
                pixels[y * S + x] = skin;

        // === HAIR: brown, rows 0-2 full coverage, row 3 partial (fringe) ===
        int hairDk   = rgba(101, 67, 33);   // dark brown base
        int hairMd   = rgba(130, 85, 40);   // medium brown
        int hairLt   = rgba(150, 100, 50);  // highlight strand

        // Row 0: darkest roots
        for (int x = 0; x < S; x++) pixels[0 * S + x] = hairDk;
        // Row 1: dark with a few highlight strands
        for (int x = 0; x < S; x++) pixels[1 * S + x] = (x == 3 || x == 7 || x == 12) ? hairLt : hairDk;
        // Row 2: medium brown, main hair body
        for (int x = 0; x < S; x++) pixels[2 * S + x] = (x == 5 || x == 10) ? hairLt : hairMd;
        // Row 3: fringe/bangs - hair in center, skin on far edges
        for (int x = 2; x <= 13; x++) pixels[3 * S + x] = hairMd;
        pixels[3 * S + 4] = hairLt; pixels[3 * S + 11] = hairLt; // highlight strands
        // Sideburns along edges rows 3-5
        pixels[3 * S + 0] = hairDk; pixels[3 * S + 1] = hairDk;
        pixels[3 * S + 14] = hairDk; pixels[3 * S + 15] = hairDk;
        pixels[4 * S + 0] = hairDk; pixels[4 * S + 1] = hairMd;
        pixels[4 * S + 14] = hairMd; pixels[4 * S + 15] = hairDk;
        pixels[5 * S + 0] = hairMd;
        pixels[5 * S + 15] = hairMd;

        // === FOREHEAD: rows 4-5, light skin ===
        for (int x = 2; x <= 13; x++) pixels[4 * S + x] = skinLt;

        // === EYEBROWS: row 5, dark brown, 3px wide each ===
        int brow = rgba(85, 58, 30);
        // Left brow
        pixels[5 * S + 4] = brow; pixels[5 * S + 5] = brow; pixels[5 * S + 6] = brow;
        // Right brow
        pixels[5 * S + 9] = brow; pixels[5 * S + 10] = brow; pixels[5 * S + 11] = brow;
        // Skin between brows at row 5
        pixels[5 * S + 7] = skinLt; pixels[5 * S + 8] = skinLt;

        // === EYES: row 6-7, GREEN with white and pupil ===
        int eyeW  = rgba(235, 235, 235);  // eye white
        int eyeG  = rgba(20, 150, 50);    // bright green iris
        int eyeGd = rgba(10, 110, 35);    // darker green edge
        int pupil = rgba(15, 15, 15);     // pupil black

        // Left eye (x=3..6, y=6..7)
        pixels[6 * S + 3] = skinDk;  // inner corner shadow
        pixels[6 * S + 4] = eyeW;    // white
        pixels[6 * S + 5] = eyeG;    // green iris
        pixels[6 * S + 6] = eyeW;    // white outer
        pixels[7 * S + 3] = skinDk;
        pixels[7 * S + 4] = eyeGd;   // lower green edge
        pixels[7 * S + 5] = pupil;   // pupil
        pixels[7 * S + 6] = eyeGd;   // lower green edge

        // Right eye (x=9..12, y=6..7) — mirrored
        pixels[6 * S + 9]  = eyeW;
        pixels[6 * S + 10] = eyeG;
        pixels[6 * S + 11] = eyeW;
        pixels[6 * S + 12] = skinDk;
        pixels[7 * S + 9]  = eyeGd;
        pixels[7 * S + 10] = pupil;
        pixels[7 * S + 11] = eyeGd;
        pixels[7 * S + 12] = skinDk;

        // Under-eye skin shadow
        for (int x = 3; x <= 6; x++) pixels[8 * S + x] = skinDk;
        for (int x = 9; x <= 12; x++) pixels[8 * S + x] = skinDk;

        // === NOSE: rows 8-9, protruding center in darker skin ===
        int nose   = rgba(185, 138, 95);
        int noseTip = rgba(175, 130, 88);
        pixels[8 * S + 7]  = nose;    pixels[8 * S + 8]  = nose;
        pixels[9 * S + 6]  = skinDk;  // nostril shadow
        pixels[9 * S + 7]  = noseTip; pixels[9 * S + 8]  = noseTip;
        pixels[9 * S + 9]  = skinDk;  // nostril shadow

        // === MUSTACHE: rows 10-11, thick brown, signature feature ===
        int mustDk = rgba(115, 75, 35);  // dark mustache
        int mustMd = rgba(135, 90, 45);  // medium mustache
        int mustLt = rgba(155, 105, 55); // highlight edge

        // Row 10: upper mustache — narrower
        pixels[10 * S + 4] = mustLt;
        for (int x = 5; x <= 10; x++) pixels[10 * S + x] = mustDk;
        pixels[10 * S + 11] = mustLt;

        // Row 11: lower mustache — wider and bushier
        pixels[11 * S + 3] = mustLt;
        for (int x = 4; x <= 11; x++) pixels[11 * S + x] = (x == 7 || x == 8) ? mustMd : mustDk;
        pixels[11 * S + 12] = mustLt;

        // === MOUTH: row 12, dark line under mustache ===
        int mouth  = rgba(140, 95, 65);   // lip color
        int mouthDk = rgba(110, 70, 48);  // inner mouth shadow
        pixels[12 * S + 5] = mouth;
        pixels[12 * S + 6] = mouthDk; pixels[12 * S + 7] = mouthDk;
        pixels[12 * S + 8] = mouthDk; pixels[12 * S + 9] = mouthDk;
        pixels[12 * S + 10] = mouth;

        // === CHIN: rows 13-14, with subtle jaw shading ===
        for (int x = 3; x <= 12; x++) pixels[13 * S + x] = skin;
        for (int x = 4; x <= 11; x++) pixels[14 * S + x] = skinDk;
        // Jaw line edges
        pixels[13 * S + 2] = skinDk; pixels[13 * S + 13] = skinDk;

        // === NECK: row 15, darker shadow ===
        for (int x = 4; x <= 11; x++) pixels[15 * S + x] = skinNeck;

        // Ear hints on sides of face (rows 6-9)
        int ear = rgba(190, 148, 100);
        for (int y = 6; y <= 9; y++) {
            pixels[y * S + 1] = ear;
            pixels[y * S + 14] = ear;
        }

        return createTextureFromPixels(pixels);
    }

    private int generateAkimovBodyTexture() {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        int S = TEXTURE_SIZE;

        // === WHITE LAB COAT: main body ===
        int coatW  = rgba(238, 238, 238);  // main white
        int coatLt = rgba(248, 248, 248);  // bright highlight
        int coatDk = rgba(220, 220, 220);  // shadow/fold
        int coatSh = rgba(205, 205, 205);  // deep shadow

        // Fill with white coat, add subtle fabric variation
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                if ((x + y) % 5 == 0) pixels[y * S + x] = coatLt;
                else if ((x * 3 + y * 2) % 7 == 0) pixels[y * S + x] = coatDk;
                else pixels[y * S + x] = coatW;
            }

        // === COLLAR: rows 0-1, slightly gray with fold lines ===
        int collar  = rgba(215, 215, 220);
        int collarH = rgba(230, 230, 235);
        int collarD = rgba(195, 195, 200);
        // V-neck collar shape
        for (int x = 0; x < S; x++) { pixels[0 * S + x] = collar; pixels[1 * S + x] = collar; }
        // Collar fold highlights
        pixels[0 * S + 3] = collarH; pixels[0 * S + 12] = collarH;
        pixels[1 * S + 4] = collarH; pixels[1 * S + 11] = collarH;
        // Neck opening shadow
        for (int x = 6; x <= 9; x++) { pixels[0 * S + x] = collarD; }
        pixels[1 * S + 7] = collarD; pixels[1 * S + 8] = collarD;

        // === CENTER SEAM with buttons ===
        int seam   = rgba(195, 195, 195);
        int button = rgba(170, 170, 175);
        for (int y = 2; y < S; y++) pixels[y * S + 8] = seam;
        // Buttons along seam
        pixels[3 * S + 8] = button;
        pixels[5 * S + 8] = button;
        pixels[7 * S + 8] = button;
        pixels[9 * S + 8] = button;
        pixels[12 * S + 8] = button;

        // === DARK BADGE/POCKET on upper-left chest ===
        int badgeDk = rgba(35, 35, 40);    // badge dark fill
        int badgeOL = rgba(55, 55, 60);    // badge outline/edge
        // Badge outline
        for (int x = 2; x <= 5; x++) { pixels[3 * S + x] = badgeOL; pixels[6 * S + x] = badgeOL; }
        for (int y = 3; y <= 6; y++) { pixels[y * S + 2] = badgeOL; pixels[y * S + 5] = badgeOL; }
        // Badge fill
        for (int y = 4; y <= 5; y++)
            for (int x = 3; x <= 4; x++)
                pixels[y * S + x] = badgeDk;

        // === SIDE FOLD shadows (left edge, right edge) ===
        for (int y = 2; y < S; y++) {
            pixels[y * S + 0] = coatSh;
            pixels[y * S + 15] = coatSh;
        }
        for (int y = 2; y < S; y++) {
            pixels[y * S + 1] = coatDk;
            pixels[y * S + 14] = coatDk;
        }

        // === LOWER POCKETS (outlined rectangles like reference) ===
        int pocketLine = rgba(210, 210, 215);
        // Left pocket
        for (int x = 2; x <= 6; x++) { pixels[10 * S + x] = pocketLine; pixels[14 * S + x] = pocketLine; }
        for (int y = 10; y <= 14; y++) { pixels[y * S + 2] = pocketLine; pixels[y * S + 6] = pocketLine; }
        // Right pocket
        for (int x = 9; x <= 13; x++) { pixels[10 * S + x] = pocketLine; pixels[14 * S + x] = pocketLine; }
        for (int y = 10; y <= 14; y++) { pixels[y * S + 9] = pocketLine; pixels[y * S + 13] = pocketLine; }
        // Pocket flap shadow
        for (int x = 2; x <= 6; x++) pixels[11 * S + x] = coatDk;
        for (int x = 9; x <= 13; x++) pixels[11 * S + x] = coatDk;

        // === COAT BOTTOM HEM: row 15 ===
        for (int x = 0; x < S; x++) pixels[15 * S + x] = coatSh;

        return createTextureFromPixels(pixels);
    }

    // Parametric coat/jacket texture for generic engineers
    private int generateEngineerBodyTexture(int coatColor) {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        int S = TEXTURE_SIZE;

        int baseR = (coatColor >> 16) & 0xFF;
        int baseG = (coatColor >> 8) & 0xFF;
        int baseB = coatColor & 0xFF;
        Random rand = new Random(coatColor * 97L + 1337);

        // Base fabric fill with subtle noise
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                int r = Math.max(0, Math.min(255, baseR + rand.nextInt(9) - 4));
                int g = Math.max(0, Math.min(255, baseG + rand.nextInt(9) - 4));
                int b = Math.max(0, Math.min(255, baseB + rand.nextInt(9) - 4));
                pixels[y * S + x] = (255 << 24) | (r << 16) | (g << 8) | b;
            }
        }

        int edgeShade = rgba(baseR - 35, baseG - 35, baseB - 35);
        int deeperShade = rgba(baseR - 55, baseG - 55, baseB - 55);
        int highlight = rgba(baseR + 25, baseG + 25, baseB + 25);
        int collarHighlight = rgba(baseR + 40, baseG + 40, baseB + 40);

        // Vertical edge shading to give depth
        for (int y = 0; y < S; y++) {
            pixels[y * S + 0] = deeperShade;
            pixels[y * S + 1] = edgeShade;
            pixels[y * S + 14] = edgeShade;
            pixels[y * S + 15] = deeperShade;
        }

        // Soft collar rows at top
        for (int x = 0; x < S; x++) {
            pixels[0 * S + x] = collarHighlight;
            pixels[1 * S + x] = highlight;
        }

        // Neck opening shadow in the middle
        for (int x = 6; x <= 9; x++) {
            pixels[0 * S + x] = deeperShade;
            pixels[1 * S + x] = edgeShade;
        }

        // Center seam + buttons
        int seamColor = rgba(baseR - 25, baseG - 25, baseB - 25);
        int buttonColor = rgba(220, 220, 220);
        for (int y = 2; y < S; y++) {
            pixels[y * S + 7] = seamColor;
            pixels[y * S + 8] = seamColor;
        }
        int[] buttonRows = {3, 6, 9, 12};
        for (int row : buttonRows) {
            pixels[row * S + 7] = buttonColor;
            pixels[row * S + 8] = buttonColor;
        }

        // Upper chest pockets (outlined rectangles)
        int pocketOutline = rgba(baseR - 15, baseG - 15, baseB - 15);
        int pocketFill = rgba(baseR + 10, baseG + 10, baseB + 10);
        for (int x = 2; x <= 5; x++) {
            pixels[3 * S + x] = pocketOutline;
            pixels[6 * S + x] = pocketOutline;
            pixels[3 * S + x + 7] = pocketOutline;
            pixels[6 * S + x + 7] = pocketOutline;
        }
        for (int y = 4; y <= 5; y++) {
            for (int x = 3; x <= 4; x++) {
                pixels[y * S + x] = pocketFill;
                pixels[y * S + x + 7] = pocketFill;
            }
        }

        // Utility belt / waist seam near lower third
        int beltColor = rgba(baseR - 45, baseG - 45, baseB - 45);
        for (int x = 0; x < S; x++) {
            pixels[11 * S + x] = beltColor;
            pixels[12 * S + x] = beltColor;
        }

        // Lower pockets below the belt
        for (int x = 2; x <= 6; x++) {
            pixels[13 * S + x] = pocketOutline;
            pixels[14 * S + x] = pocketOutline;
            pixels[13 * S + x + 7] = pocketOutline;
            pixels[14 * S + x + 7] = pocketOutline;
        }

        // Bottom hem shadow
        for (int x = 0; x < S; x++) {
            pixels[15 * S + x] = deeperShade;
        }

        return createTextureFromPixels(pixels);
    }

    private int generateAkimovArmsTexture() {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        int S = TEXTURE_SIZE;

        // === WHITE SLEEVE: rows 0-8 ===
        int sleeveW  = rgba(238, 238, 238);
        int sleeveLt = rgba(248, 248, 248);
        int sleeveDk = rgba(218, 218, 218);
        int sleeveSh = rgba(200, 200, 200);

        for (int y = 0; y < 9; y++)
            for (int x = 0; x < S; x++) {
                if (x == 0 || x == 15) pixels[y * S + x] = sleeveSh;  // edge shadow
                else if (x == 1 || x == 14) pixels[y * S + x] = sleeveDk;
                else if ((x + y) % 4 == 0) pixels[y * S + x] = sleeveLt;  // fabric highlight
                else pixels[y * S + x] = sleeveW;
            }
        // Shoulder seam at top
        for (int x = 0; x < S; x++) pixels[0 * S + x] = sleeveSh;

        // === GRAY CUFF BAND: rows 9-10 (matching reference) ===
        int cuffDk = rgba(160, 160, 165);
        int cuffLt = rgba(180, 180, 185);
        int cuffH  = rgba(200, 200, 205);
        for (int x = 0; x < S; x++) {
            pixels[9 * S + x] = (x == 0 || x == 15) ? cuffDk : (x == 7 || x == 8) ? cuffH : cuffLt;
            pixels[10 * S + x] = (x == 0 || x == 15) ? cuffDk : cuffDk;
        }

        // === SKIN HAND: rows 11-15 ===
        int handBase = rgba(198, 156, 108);
        int handLt   = rgba(210, 168, 120);
        int handDk   = rgba(180, 140, 95);
        for (int y = 11; y < S; y++)
            for (int x = 0; x < S; x++) {
                if (x == 0 || x == 15) pixels[y * S + x] = handDk;
                else if (y == 11) pixels[y * S + x] = handLt;  // wrist highlight
                else pixels[y * S + x] = handBase;
            }
        // Knuckle detail on row 13
        pixels[13 * S + 4] = handDk; pixels[13 * S + 7] = handDk;
        pixels[13 * S + 10] = handDk; pixels[13 * S + 13] = handDk;
        // Fingertip row
        for (int x = 2; x <= 13; x++) pixels[15 * S + x] = handDk;

        return createTextureFromPixels(pixels);
    }

    private int generateAkimovLegsTexture() {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        int S = TEXTURE_SIZE;
        Random rand = new Random(19860426L);

        int baseR = 32;
        int baseG = 52;
        int baseB = 92;

        // Fill trouser fabric with slight vertical gradient + noise
        for (int y = 0; y < S; y++) {
            float t = y / (float) (S - 1);
            int shadeR = baseR + (int) (t * 10);
            int shadeG = baseG + (int) (t * 8);
            int shadeB = baseB + (int) (t * 6);
            for (int x = 0; x < S; x++) {
                int noise = rand.nextInt(7) - 3;
                int r = Math.max(0, Math.min(255, shadeR + noise));
                int g = Math.max(0, Math.min(255, shadeG + noise));
                int b = Math.max(0, Math.min(255, shadeB + noise));
                pixels[y * S + x] = (255 << 24) | (r << 16) | (g << 8) | b;
            }
        }

        int edgeShadow = rgba(12, 20, 40);
        int innerShadow = rgba(20, 28, 55);
        for (int y = 0; y < S; y++) {
            pixels[y * S + 0] = edgeShadow;
            pixels[y * S + 1] = innerShadow;
            pixels[y * S + 14] = innerShadow;
            pixels[y * S + 15] = edgeShadow;
        }

        // Crease highlight running down the center of each leg
        int creaseHighlight = rgba(70, 90, 140);
        int creaseShadow = rgba(24, 34, 64);
        for (int y = 0; y < S; y++) {
            pixels[y * S + 6] = creaseShadow;
            pixels[y * S + 7] = creaseHighlight;
            pixels[y * S + 8] = creaseHighlight;
            pixels[y * S + 9] = creaseShadow;
        }

        // Belt band at top
        int belt = rgba(25, 18, 18);
        int beltHighlight = rgba(65, 55, 40);
        for (int x = 0; x < S; x++) {
            pixels[0 * S + x] = belt;
            pixels[1 * S + x] = belt;
        }
        pixels[1 * S + 3] = beltHighlight;
        pixels[1 * S + 12] = beltHighlight;

        // Knee reinforcement patch
        int kneeHighlight = rgba(80, 100, 150);
        int kneeShadow = rgba(25, 35, 70);
        for (int x = 3; x <= 12; x++) {
            pixels[8 * S + x] = kneeShadow;
            pixels[9 * S + x] = kneeHighlight;
            pixels[10 * S + x] = kneeHighlight;
            pixels[11 * S + x] = kneeShadow;
        }

        // Cuff shadow near bottom
        int cuff = rgba(18, 25, 45);
        for (int x = 0; x < S; x++) {
            pixels[14 * S + x] = cuff;
            pixels[15 * S + x] = edgeShadow;
        }

        return createTextureFromPixels(pixels);
    }

    // Generic legs texture for Toptunov (original style)
    private int generateEngineerLegsTexture(int pantsColor) {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        Random rand = new Random(pantsColor + 5555);
        int baseR = (pantsColor >> 16) & 0xFF;
        int baseG = (pantsColor >> 8) & 0xFF;
        int baseB = pantsColor & 0xFF;
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                int r = Math.max(0, Math.min(255, baseR + rand.nextInt(8) - 4));
                int g = Math.max(0, Math.min(255, baseG + rand.nextInt(8) - 4));
                int b = Math.max(0, Math.min(255, baseB + rand.nextInt(8) - 4));
                pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return createTextureFromPixels(pixels);
    }

    // Generic arms texture for Toptunov (original style)
    private int generateEngineerArmsTexture(int skinColor) {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        Random rand = new Random(skinColor + 3456);
        int skinR = (skinColor >> 16) & 0xFF;
        int skinG = (skinColor >> 8) & 0xFF;
        int skinB = skinColor & 0xFF;
        for (int y = 0; y < 10; y++)
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                int w = 240 + rand.nextInt(10) - 5;
                pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (w << 16) | (w << 8) | w;
            }
        for (int y = 10; y < TEXTURE_SIZE; y++)
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                int r = skinR + rand.nextInt(10) - 5;
                int g = skinG + rand.nextInt(10) - 5;
                int b = skinB + rand.nextInt(10) - 5;
                pixels[y * TEXTURE_SIZE + x] = (255 << 24) | (r << 16) | (g << 8) | b;
            }
        return createTextureFromPixels(pixels);
    }

    // Helper to make ARGB pixel from RGB
    private int rgba(int r, int g, int b) {
        return (255 << 24) | (Math.max(0, Math.min(255, r)) << 16) | (Math.max(0, Math.min(255, g)) << 8) | Math.max(0, Math.min(255, b));
    }

    // Create an NPC engineer in the scene
    private void createNPCEngineer(String name, float x, float y, float z, float facing, String headTex, String bodyTex, String legsTex, String armsTex) {
        // Scale NPCs to match player height (player eye at 120)
        float floorY = y; // Floor level (use the passed Y position)
        float headSize = 25f;
        float bodyWidth = 30f;
        float bodyHeight = 50f;
        float legWidth = 14f;
        float legHeight = 55f;
        float armWidth = 12f;
        float armHeight = 45f;
        // Total height: legs(55) + body(50) + head(25) = 130 (head top at 130, eyes around 120)
        
        // Head (top) - uses npcHeadMesh for per-face UV mapping (face only on front)
        TexturedGameObject head = new TexturedGameObject(npcHeadMesh, 
            new Vector3f(x, floorY + legHeight + bodyHeight + headSize/2, z),
            new Vector3f(headSize, headSize, headSize),
            blockTextures.get(headTex), false);
        head.isNPCPart = true;
        head.textureName = headTex;
        texturedObjects.add(head);
        
        // Body - uses texturedCubeMesh for lab coat details
        TexturedGameObject body = new TexturedGameObject(texturedCubeMesh,
            new Vector3f(x, floorY + legHeight + bodyHeight/2, z),
            new Vector3f(bodyWidth, bodyHeight, bodyWidth * 0.5f),
            blockTextures.get(bodyTex), false);
        body.isNPCPart = true;
        body.textureName = bodyTex;
        texturedObjects.add(body);
        
        // Left leg
        TexturedGameObject leftLeg = new TexturedGameObject(texturedCubeMesh,
            new Vector3f(x - legWidth * 0.6f, floorY + legHeight/2, z),
            new Vector3f(legWidth, legHeight, legWidth),
            blockTextures.get(legsTex), false);
        leftLeg.isNPCPart = true;
        leftLeg.textureName = legsTex;
        texturedObjects.add(leftLeg);
        
        // Right leg
        TexturedGameObject rightLeg = new TexturedGameObject(texturedCubeMesh,
            new Vector3f(x + legWidth * 0.6f, floorY + legHeight/2, z),
            new Vector3f(legWidth, legHeight, legWidth),
            blockTextures.get(legsTex), false);
        rightLeg.isNPCPart = true;
        rightLeg.textureName = legsTex;
        texturedObjects.add(rightLeg);
        
        // Left arm
        TexturedGameObject leftArm = new TexturedGameObject(texturedCubeMesh,
            new Vector3f(x - bodyWidth/2 - armWidth/2, floorY + legHeight + bodyHeight/2, z),
            new Vector3f(armWidth, armHeight, armWidth),
            blockTextures.get(armsTex), false);
        leftArm.isNPCPart = true;
        leftArm.textureName = armsTex;
        texturedObjects.add(leftArm);
        
        // Right arm
        TexturedGameObject rightArm = new TexturedGameObject(texturedCubeMesh,
            new Vector3f(x + bodyWidth/2 + armWidth/2, floorY + legHeight + bodyHeight/2, z),
            new Vector3f(armWidth, armHeight, armWidth),
            blockTextures.get(armsTex), false);
        rightArm.isNPCPart = true;
        rightArm.textureName = armsTex;
        texturedObjects.add(rightArm);
        
        // Name + role label above head - wide billboard (fullBright, no lighting)
        String nameLabelTex = name.toLowerCase() + "_label";
        TexturedGameObject nameLabel = new TexturedGameObject(texturedCubeMesh,
            new Vector3f(x, floorY + legHeight + bodyHeight + headSize + 20f, z),
            new Vector3f(80f, 20f, 2f),
            blockTextures.get(nameLabelTex), false);
        nameLabel.fullBright = true;
        nameLabel.isNPCPart = true;
        nameLabel.textureName = nameLabelTex;
        texturedObjects.add(nameLabel);
        
        // Add to NPC list for animation
        NPCEngineer npc = new NPCEngineer(name, head, body, leftLeg, rightLeg, leftArm, rightArm, 
            new Vector3f(x, floorY, z), facing, new Random().nextFloat() * 6.28f);
        npc.nameLabel = nameLabel;
        npcEngineers.add(npc);
    }
    
    private void buildReactorRoom() {
        float wallX = ROOM_SIZE * BLOCK_SIZE;
        float floorY = -200f;
        
        // Reactor room floor - checkered stone/cobblestone
        for (int x = 0; x < 15; x++) {
            for (int z = -8; z <= 8; z++) {
                String texture = ((x + z) % 2 == 0) ? "stone" : "cobblestone";
                addTexturedCube(wallX + x * BLOCK_SIZE, floorY, z * BLOCK_SIZE, BLOCK_SIZE, 10, BLOCK_SIZE, texture);
            }
        }
        
        // Build reactor using blocks instead of cylinders for Minecraft style
        // Reactor outer shell - obsidian
        int reactorX = (int)(wallX + 350f);
        int reactorRadius = 4;
        int reactorHeight = 6;
        
        for (int h = 0; h < reactorHeight; h++) {
            for (int dx = -reactorRadius; dx <= reactorRadius; dx++) {
                for (int dz = -reactorRadius; dz <= reactorRadius; dz++) {
                    float dist = (float)Math.sqrt(dx*dx + dz*dz);
                    if (dist <= reactorRadius && dist > reactorRadius - 1.5f) {
                        // Outer shell
                        addTexturedCube(reactorX + dx * BLOCK_SIZE, floorY + BLOCK_SIZE + h * BLOCK_SIZE, dz * BLOCK_SIZE,
                               BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, "obsidian");
                    } else if (dist <= reactorRadius - 1.5f && dist > reactorRadius - 2.5f) {
                        // Inner ring - diamond (Cherenkov glow)
                        addTexturedCube(reactorX + dx * BLOCK_SIZE, floorY + BLOCK_SIZE + h * BLOCK_SIZE, dz * BLOCK_SIZE,
                               BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, "diamond_block");
                    } else if (dist <= reactorRadius - 2.5f) {
                        // Core - light blue concrete (bright glow)
                        addTexturedCube(reactorX + dx * BLOCK_SIZE, floorY + BLOCK_SIZE + h * BLOCK_SIZE, dz * BLOCK_SIZE,
                               BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, "light_blue_concrete");
                    }
                }
            }
        }
        
        // Top cap
        for (int dx = -reactorRadius; dx <= reactorRadius; dx++) {
            for (int dz = -reactorRadius; dz <= reactorRadius; dz++) {
                float dist = (float)Math.sqrt(dx*dx + dz*dz);
                if (dist <= reactorRadius) {
                    addTexturedCube(reactorX + dx * BLOCK_SIZE, floorY + BLOCK_SIZE + reactorHeight * BLOCK_SIZE, dz * BLOCK_SIZE,
                           BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, "iron_block");
                }
            }
        }
        
        // Control rods - coal blocks sticking up
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI * 2 / 8;
            float radius = 2.5f * BLOCK_SIZE;
            int rx = (int)(reactorX + Math.cos(angle) * radius);
            int rz = (int)(Math.sin(angle) * radius);
            for (int h = 0; h < reactorHeight + 3; h++) {
                addTexturedCube(rx, floorY + BLOCK_SIZE + h * BLOCK_SIZE, rz,
                       BLOCK_SIZE * 0.5f, BLOCK_SIZE, BLOCK_SIZE * 0.5f, "coal_block");
            }
        }
        
        // Reactor base - stone bricks
        for (int dx = -reactorRadius - 1; dx <= reactorRadius + 1; dx++) {
            for (int dz = -reactorRadius - 1; dz <= reactorRadius + 1; dz++) {
                addTexturedCube(reactorX + dx * BLOCK_SIZE, floorY, dz * BLOCK_SIZE,
                       BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, "cobblestone");
            }
        }
        
        // Glow effects around reactor - glowstone
        for (int i = 0; i < 16; i++) {
            double angle = i * Math.PI * 2 / 16;
            float radius = (reactorRadius + 2) * BLOCK_SIZE;
            int gx = (int)(reactorX + Math.cos(angle) * radius);
            int gz = (int)(Math.sin(angle) * radius);
            addTexturedCube(gx, floorY + BLOCK_SIZE, gz, BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, "glowstone");
        }
    }
    
    private void addTexturedCube(float x, float y, float z, float sx, float sy, float sz, String textureName) {
        int textureId = blockTextures.getOrDefault(textureName, blockTextures.get("stone"));
        boolean isTransparent = textureName.equals("glass");
        TexturedGameObject obj = new TexturedGameObject(texturedCubeMesh, new Vector3f(x, y, z), 
                                                        new Vector3f(sx, sy, sz), textureId, isTransparent);
        obj.textureName = textureName;
        texturedObjects.add(obj);
    }
    
    private TexturedGameObject addTexturedCubeAndReturn(float x, float y, float z, float sx, float sy, float sz, String textureName) {
        int textureId = blockTextures.getOrDefault(textureName, blockTextures.get("stone"));
        boolean isTransparent = textureName.equals("glass");
        TexturedGameObject obj = new TexturedGameObject(texturedCubeMesh, new Vector3f(x, y, z), 
                                                        new Vector3f(sx, sy, sz), textureId, isTransparent);
        obj.textureName = textureName;
        texturedObjects.add(obj);
        return obj;
    }
    
    private void addCube(float x, float y, float z, float sx, float sy, float sz, Vector3f color) {
        GameObject obj = new GameObject(cubeMesh, new Vector3f(x, y, z), new Vector3f(sx, sy, sz), color);
        gameObjects.add(obj);
    }
    
    private void addCylinder(float x, float y, float z, float diameter, float height, Vector3f color) {
        GameObject obj = new GameObject(cylinderMesh, new Vector3f(x, y, z), new Vector3f(diameter, height, diameter), color);
        gameObjects.add(obj);
    }

    private void createAlarmLight(float x, float y, float z) {
        GameObject base = new GameObject(cylinderMesh,
            new Vector3f(x, y - 25f, z),
            new Vector3f(24f, 20f, 24f),
            new Vector3f(0.2f, 0.2f, 0.2f));
        gameObjects.add(base);

        GameObject glass = new GameObject(cylinderMesh,
            new Vector3f(x, y + 10f, z),
            new Vector3f(20f, 40f, 20f),
            new Vector3f(0.8f, 0.15f, 0.15f));
        gameObjects.add(glass);
        alarmLights.add(new AlarmLight(glass));
    }

    public void update(long window, float deltaTime) {
        // Handle game state
        if (gameState == STATE_MENU) {
            updateMenu(window, deltaTime);
            return;
        }
        if (gameState == STATE_TUTORIAL) {
            updateTutorial(window, deltaTime);
            return;
        }
        if (gameState == STATE_ENDING) {
            updateEnding(window, deltaTime);
            return;
        }

        // === PLAYING state update ===

        // M key: toggle pause menu (with debounce)
        if (glfwGetKey(window, GLFW_KEY_M) == GLFW_PRESS) {
            if (!mKeyPressed) {
                mKeyPressed = true;
                pauseMenuOpen = !pauseMenuOpen;
                if (pauseMenuOpen) {
                    glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
                    pauseMenuHovered = -1;
                    pauseMenuTime = 0f;
                } else {
                    glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                    firstMouse = true;
                }
            }
        } else {
            mKeyPressed = false;
        }

        // If pause menu is open, handle its input and skip game update
        if (pauseMenuOpen) {
            updatePauseMenu(window, deltaTime);
            return;
        }

        // Creative mode toggle (C key)
        if (glfwGetKey(window, GLFW_KEY_C) == GLFW_PRESS) {
            if (!creativeTogglePressed) {
                creativeTogglePressed = true;
                if (!DEV_CREATIVE_MODE_ENABLED) {
                    notificationText = "Creative tools disabled in this build";
                    notificationTimer = 2.0f;
                } else {
                    creativeMode = !creativeMode;
                    if (creativeMode) {
                        notificationText = "Creative tools enabled (B=place, N=remove)";
                    } else {
                        notificationText = "Creative tools disabled";
                    }
                    notificationTimer = 2.0f;
                }
            }
        } else {
            creativeTogglePressed = false;
        }

        // Ending countdown timer (transitions to ending screen after delay)
        if (endingCountdownActive && endingTimer > 0) {
            endingTimer -= deltaTime;
            if (endingTimer <= 0) {
                endingTimer = 0;
                endingCountdownActive = false;
                gameState = STATE_ENDING;
                endingSceneTimer = 0f;
                endingParticlesSpawned = false;
                epCount = 0;
                // Show cursor for ending screen
                glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
                return;
            }
        }

        // === Radiation/Geiger Counter System ===
        // Simulate radiation based on proximity to reactor and story events
        float baseRadiation = 0.01f; // background
        float reactorDist = (float)Math.sqrt(cameraPos.x * cameraPos.x + (cameraPos.z + 8 * BLOCK_SIZE) * (cameraPos.z + 8 * BLOCK_SIZE));
        float reactorRad = 0f;
        if (storyPhase >= 3) {
            // Radiation increases as reactor destabilizes and after explosion
            reactorRad = (100f - reactorStability) * 0.05f;
            if (storyPhase >= 8) reactorRad += 10f;
            if (storyPhase >= 9) reactorRad += 50f;
            // Closer to reactor core = higher dose
            float distFactor = Math.max(0.2f, 1.0f - reactorDist / (8 * BLOCK_SIZE));
            reactorRad *= distFactor;
        }
        // Add local hot spots (future: leaking pipes, etc.)
        radiationRate = baseRadiation + reactorRad;
        playerRadiation += radiationRate * deltaTime;
        // Geiger counter click logic
        float geigerBaseRate = Math.min(1.0f, radiationRate / 5f); // 0-1
        geigerClickTimer += deltaTime * (2f + geigerBaseRate * 18f); // more clicks at higher rad
        if (geigerClickTimer > 1f) {
            geigerClickCount = (int)(geigerClickTimer * (6f + geigerBaseRate * 30f));
            geigerClickTimer = 0f;
            // Visual flash for high radiation
            if (radiationRate > 2f) radiationFlashTimer = 0.15f;
        } else {
            geigerClickCount = 0;
        }
        if (radiationFlashTimer > 0f) radiationFlashTimer -= deltaTime;

        // Dynamic lighting/alarm blending
        lightingTimer += deltaTime;
        float targetAlarmLevel = 0f;
        if (storyPhase >= 5) targetAlarmLevel = 0.35f;
        if (reactorStability < 70f) {
            targetAlarmLevel = Math.max(targetAlarmLevel, (70f - reactorStability) / 40f);
        }
        if (storyPhase >= 8) targetAlarmLevel = 1f;
        if (storyPhase >= 9) targetAlarmLevel = 1.25f;
        float blendSpeed = 2.5f;
        alarmActivation += (targetAlarmLevel - alarmActivation) * Math.min(1f, deltaTime * blendSpeed);
        alarmPulseTimer += deltaTime * (1.5f + Math.max(0.2f, alarmActivation));
        float alarmPulse = 0.5f + 0.5f * (float)Math.sin(alarmPulseTimer * 4.2f);
        for (AlarmLight beacon : alarmLights) {
            beacon.updateGlow(Math.max(0f, alarmActivation), alarmPulse);
        }

        // ESC key: only used to close panels/machine UI (handled in updateMachineInteraction)
        // Reset menuEscHeld when ESC is released so machine UI close works properly
        if (glfwGetKey(window, GLFW_KEY_ESCAPE) != GLFW_PRESS) {
            if (!machineUIActive) menuEscHeld = false;
        }

        // Animate fuel rods
        animationTime += deltaTime;
        for (FuelRodAnimation anim : fuelRodAnimations) {
            float offset = (float)Math.sin(animationTime * anim.speed + anim.phase) * anim.amplitude;
            anim.rod.position.y = anim.baseY + offset;
        }
        
        // Animate NPC engineers - AI behavior
        for (NPCEngineer npc : npcEngineers) {
            // Update callout timer
            if (npc.calloutTimer > 0) {
                npc.calloutTimer -= deltaTime;
            }

            // Check distance to player
            float dxP = cameraPos.x - npc.basePosition.x;
            float dzP = cameraPos.z - npc.basePosition.z;
            float distToPlayer = (float) Math.sqrt(dxP * dxP + dzP * dzP);

            // Face player when nearby (within 300 units)
            if (distToPlayer < 300f) {
                npc.facingPlayer = true;
                float targetFacing = (float) Math.atan2(dxP, dzP);
                // Smooth rotation towards player
                float angleDiff = targetFacing - npc.facing;
                while (angleDiff > Math.PI) angleDiff -= 2 * (float) Math.PI;
                while (angleDiff < -Math.PI) angleDiff += 2 * (float) Math.PI;
                npc.facing += angleDiff * Math.min(1f, 5f * deltaTime);
            } else {
                npc.facingPlayer = false;
            }

            // Movement towards target
            float tdx = npc.targetPosition.x - npc.basePosition.x;
            float tdz = npc.targetPosition.z - npc.basePosition.z;
            float distToTarget = (float) Math.sqrt(tdx * tdx + tdz * tdz);

            if (distToTarget > 5f) {
                npc.isWalking = true;
                // Face movement direction (unless facing player)
                if (!npc.facingPlayer) {
                    float moveFacing = (float) Math.atan2(tdx, tdz);
                    float angleDiff = moveFacing - npc.facing;
                    while (angleDiff > Math.PI) angleDiff -= 2 * (float) Math.PI;
                    while (angleDiff < -Math.PI) angleDiff += 2 * (float) Math.PI;
                    npc.facing += angleDiff * Math.min(1f, 5f * deltaTime);
                }
                // Move towards target
                float moveStep = npc.moveSpeed * deltaTime;
                if (moveStep > distToTarget) moveStep = distToTarget;
                float nx = tdx / distToTarget * moveStep;
                float nz = tdz / distToTarget * moveStep;
                npc.basePosition.x += nx;
                npc.basePosition.z += nz;
            } else {
                npc.isWalking = false;
            }

            // Walk animation phase
            if (npc.isWalking) {
                npc.walkPhase += 10f * deltaTime;
            } else {
                npc.walkPhase *= 0.85f;
                if (Math.abs(npc.walkPhase) < 0.01f) npc.walkPhase = 0;
            }

            // Idle animation (subtle breathing / head look)
            npc.headBobTime += deltaTime;
            npc.lookTimer -= deltaTime;
            if (npc.lookTimer <= 0) {
                npc.lookingAround = !npc.lookingAround;
                npc.lookTimer = 2f + (float) Math.random() * 3f;
                if (npc.lookingAround) {
                    npc.lookDirection = ((float) Math.random() - 0.5f) * 0.5f;
                }
            }
        }
        
        // Update NPC story behavior
        updateNPCStoryBehavior();
        
        // Mouse look
        double[] xpos = new double[1], ypos = new double[1];
        glfwGetCursorPos(window, xpos, ypos);
        
        if (firstMouse) {
            lastMouseX = xpos[0];
            lastMouseY = ypos[0];
            firstMouse = false;
        }
        
        float xOffset = (float) (xpos[0] - lastMouseX);
        float yOffset = (float) (lastMouseY - ypos[0]);
        lastMouseX = xpos[0];
        lastMouseY = ypos[0];
        
        if (glfwGetInputMode(window, GLFW_CURSOR) == GLFW_CURSOR_DISABLED && !dialogueActive && !machineUIActive) {
            yaw += xOffset * mouseSensitivity;
            pitch += yOffset * mouseSensitivity;
            pitch = Math.max(-89f, Math.min(89f, pitch));
        }
        
        // Calculate camera direction
        Vector3f direction = new Vector3f(
            (float) (Math.cos(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch))),
            (float) Math.sin(Math.toRadians(pitch)),
            (float) (Math.sin(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)))
        ).normalize();

        handleCreativeBuildControls(window, direction);
        
        // Movement
        Vector3f right = new Vector3f(direction).cross(new Vector3f(0, 1, 0)).normalize();
        Vector3f forward = new Vector3f(direction.x, 0, direction.z).normalize();
        
        float speed = moveSpeed * deltaTime;
        
        // Shift key for sprinting
        if (glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS || glfwGetKey(window, GLFW_KEY_RIGHT_SHIFT) == GLFW_PRESS) {
            speed *= 2.5f;
        }
        if (creativeMode) {
            speed *= 1.35f;
        }
        
        // Store old position for collision rollback
        Vector3f oldPos = new Vector3f(cameraPos);
        Vector3f newPos = new Vector3f(cameraPos);
        
        boolean frozen = (dialogueActive || machineUIActive) && !creativeMode;
        boolean movingForward = !frozen && glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS;
        boolean movingBack = !frozen && glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS;
        boolean movingLeft = !frozen && glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS;
        boolean movingRight = !frozen && glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS;
        isPlayerWalking = movingForward || movingBack || movingLeft || movingRight;

        if (movingForward) {
            newPos.add(new Vector3f(forward).mul(speed));
        }
        if (movingBack) {
            newPos.sub(new Vector3f(forward).mul(speed));
        }
        if (movingLeft) {
            newPos.sub(new Vector3f(right).mul(speed));
        }
        if (movingRight) {
            newPos.add(new Vector3f(right).mul(speed));
        }

        // Walk animation phase
        if (isPlayerWalking && isOnGround) {
            float animSpeed = (glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS || glfwGetKey(window, GLFW_KEY_RIGHT_SHIFT) == GLFW_PRESS) ? 18f : 10f;
            walkAnimPhase += animSpeed * deltaTime;
        } else {
            // Smoothly return to rest pose
            walkAnimPhase *= 0.85f;
            if (Math.abs(walkAnimPhase) < 0.01f) walkAnimPhase = 0f;
        }
        
        // Apply collision detection - check each axis separately for sliding
        float prevX = cameraPos.x, prevZ = cameraPos.z;
        // X axis
        if (!checkCollision(newPos.x, cameraPos.y, cameraPos.z)) {
            cameraPos.x = newPos.x;
        }
        // Z axis
        if (!checkCollision(cameraPos.x, cameraPos.y, newPos.z)) {
            cameraPos.z = newPos.z;
        }

        // Update player facing direction based on actual movement
        float dx = cameraPos.x - prevX;
        float dz = cameraPos.z - prevZ;
        if (dx * dx + dz * dz > 0.001f) {
            float targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx));
            // Smooth rotation towards movement direction
            float diff = targetYaw - playerFacingYaw;
            // Normalize to -180..180
            while (diff > 180f) diff -= 360f;
            while (diff < -180f) diff += 360f;
            playerFacingYaw += diff * 0.15f; // smooth turn
        }
        
        // Jump with Space key
        if (glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS && isOnGround) {
            velocityY = JUMP_VELOCITY;
            isOnGround = false;
        }
        
        // Apply gravity (cap deltaTime to prevent floating on lag spikes)
        float gravDt = Math.min(deltaTime, 0.05f); // Cap at 50ms (20fps minimum)
        velocityY -= GRAVITY * gravDt;
        float newY = cameraPos.y + velocityY * gravDt;
        
        // Check if landing on table
        float tableZPos = 4 * BLOCK_SIZE;
        float tableWidth = BLOCK_SIZE * 2.5f;
        float tableLength = BLOCK_SIZE * 6;
        float tableTopY = 0.9f * BLOCK_SIZE + 120f; // Table height + ground level
        boolean onTable = false;
        if (cameraPos.x > -tableWidth/2 && cameraPos.x < tableWidth/2 &&
            cameraPos.z > tableZPos - tableLength/2 && cameraPos.z < tableZPos + tableLength/2) {
            if (newY <= tableTopY && velocityY < 0) {
                newY = tableTopY;
                velocityY = 0f;
                isOnGround = true;
                onTable = true;
            }
        }
        
        // Check if landing on control desk
        float deskZ = -6 * BLOCK_SIZE;
        float deskDepth = BLOCK_SIZE * 1.5f;
        float deskHalfDepth = deskDepth * 0.5f;
        float deskMinZ = deskZ - deskHalfDepth;
        float deskMaxZ = deskZ + deskHalfDepth;
        float deskTopY = 1.2f * BLOCK_SIZE + 120f; // Desk height + ground level
        if (cameraPos.x > -10 * BLOCK_SIZE && cameraPos.x < 10 * BLOCK_SIZE &&
            cameraPos.z > deskMinZ && cameraPos.z < deskMaxZ) {
            if (newY <= deskTopY && velocityY < 0) {
                newY = deskTopY;
                velocityY = 0f;
                isOnGround = true;
            }
        }
        
        cameraPos.y = newY;
        
        // Ground collision - dynamic floor level based on zone
        float currentGroundLevel = GROUND_LEVEL; // Default control room floor
        
        // Check player zone for correct floor height
        float px = cameraPos.x;
        float pz = cameraPos.z;
        
        // Zone: STAIRS (z from -34 to -42, x within corridor width)
        boolean onStairs = (pz < -34 * BLOCK_SIZE && pz >= -42 * BLOCK_SIZE
                           && px > -3 * BLOCK_SIZE && px < 3 * BLOCK_SIZE);
        // Zone: BOTTOM LEVEL (landing, left arm, walkway, or reactor hall at y=-180)
        // NOTE: Do NOT use cameraPos.y in zone checks — it changes during jumps
        // and would cause the ground level to flip mid-air, teleporting the player.
        boolean atBottomLevel = false;
        if (pz < -42 * BLOCK_SIZE && pz >= -46 * BLOCK_SIZE) {
            // Landing + both arms: z=-42 to -46
            atBottomLevel = true;
        } else if (px <= -17 * BLOCK_SIZE && px >= -21 * BLOCK_SIZE
                   && pz >= -42 * BLOCK_SIZE && pz <= -12 * BLOCK_SIZE) {
            // Reactor walkway: x=-17 to -21, z=-42 to -12
            atBottomLevel = true;
        } else if (px >= PUMP_WALKWAY_INNER_WALL && px <= PUMP_WALKWAY_OUTER_WALL
                && pz >= -42 * BLOCK_SIZE && pz <= -12 * BLOCK_SIZE) {
            // Pump walkway mirrors the reactor walkway deck height
            atBottomLevel = true;
        } else if (px >= PUMP_ANNEX_MIN_X && px <= PUMP_ANNEX_MAX_X
                && pz >= PUMP_ANNEX_MIN_Z && pz <= PUMP_ANNEX_MAX_Z) {
            // Pump annex shares reactor hall deck height
            atBottomLevel = true;
        }
        // Zone: REACTOR HALL (x < -17*BS, within hall Z range)
        boolean inReactorHall = (px <= REACTOR_HALL_WINDOW_X && pz >= REACTOR_HALL_BACK_Z && pz <= REACTOR_HALL_FRONT_Z
                                && !onStairs);

        float hallFloorWorld = GROUND_LEVEL + REACTOR_HALL_FLOOR_Y;

        if (onStairs) {
            // Stairs: z=-34 → y=0, z=-42 → y=-180
            float t = (pz - (-34 * BLOCK_SIZE)) / ((-42 * BLOCK_SIZE) - (-34 * BLOCK_SIZE));
            t = Math.max(0f, Math.min(1f, t));
            float stairFloorY = t * REACTOR_HALL_FLOOR_Y;
            currentGroundLevel = GROUND_LEVEL + stairFloorY;
        } else if (atBottomLevel || inReactorHall) {
            currentGroundLevel = hallFloorWorld;
        }

        float reactorStairGround = getReactorHallStairGround(px, pz);
        if (!Float.isNaN(reactorStairGround)) {
            currentGroundLevel = Math.max(currentGroundLevel, reactorStairGround);
        }

        if (inReactorHall) {
            float catwalkGround = getReactorHallCatwalkGround(px, pz, cameraPos.y);
            if (!Float.isNaN(catwalkGround)) {
                currentGroundLevel = Math.max(currentGroundLevel, catwalkGround);
            }
        }
        
        if (cameraPos.y <= currentGroundLevel) {
            cameraPos.y = currentGroundLevel;
            velocityY = 0f;
            isOnGround = true;
        }
        
        // Fullscreen toggle with F key (with debounce)
        if (glfwGetKey(window, GLFW_KEY_F) == GLFW_PRESS) {
            if (!fKeyPressed) {
                fKeyPressed = true;
                toggleFullscreen(window);
            }
        } else {
            fKeyPressed = false;
        }

        // Toggle 3rd person view with V key (with debounce)
        if (glfwGetKey(window, GLFW_KEY_V) == GLFW_PRESS) {
            if (!vKeyPressed) {
                vKeyPressed = true;
                thirdPerson = !thirdPerson;
            }
        } else {
            vKeyPressed = false;
        }
        
        // Update view matrix
        // Calculate screen shake offset based on reactor state
        screenShakeTimer += deltaTime;
        float shakeX = 0, shakeY = 0;
        
        // Continuous shake from reactor instability
        float instabilityShake = 0f;
        if (reactorStability < 60 && storyPhase >= 1) {
            instabilityShake = (60 - reactorStability) / 60f * 3f; // up to 3 units
        }
        if (storyPhase >= 8) instabilityShake = 15f; // massive shake during surge
        if (storyPhase >= 9) instabilityShake = 25f; // explosion shake
        
        // One-shot shake decay
        if (screenShakeDecay > 0) {
            screenShakeDecay -= deltaTime * 3f;
            instabilityShake = Math.max(instabilityShake, screenShakeDecay * screenShakeIntensity);
        }
        
        if (instabilityShake > 0.1f) {
            float freq = 15f + instabilityShake * 2f;
            shakeX = (float)(Math.sin(screenShakeTimer * freq) * instabilityShake * 0.7f
                + Math.cos(screenShakeTimer * freq * 1.7f) * instabilityShake * 0.3f);
            shakeY = (float)(Math.cos(screenShakeTimer * freq * 1.3f) * instabilityShake * 0.5f
                + Math.sin(screenShakeTimer * freq * 2.1f) * instabilityShake * 0.2f);
        }
        
        if (thirdPerson) {
            // 3rd person: camera behind and above the player, looking at the player
            Vector3f behindDir = new Vector3f(-direction.x, 0, -direction.z).normalize();
            Vector3f camPos3rd = new Vector3f(cameraPos)
                .add(behindDir.x * THIRD_PERSON_DISTANCE, THIRD_PERSON_HEIGHT, behindDir.z * THIRD_PERSON_DISTANCE);
            // Look at a point slightly above the player's feet (chest height)
            Vector3f lookTarget = new Vector3f(cameraPos.x, cameraPos.y - 20f, cameraPos.z);
            camPos3rd.add(shakeX, shakeY, shakeX * 0.5f);
            view.setLookAt(camPos3rd, lookTarget, new Vector3f(0, 1, 0));
        } else {
            // 1st person: standard
            Vector3f eyePos = new Vector3f(cameraPos.x + shakeX, cameraPos.y + shakeY, cameraPos.z + shakeX * 0.5f);
            Vector3f target = new Vector3f(eyePos).add(direction);
            view.setLookAt(eyePos, target, new Vector3f(0, 1, 0));
        }
        
        // Story system update
        updateStory(window, deltaTime);
    }
    
    // Toggle fullscreen mode
    private void toggleFullscreen(long window) {
        // Remember current cursor mode so we can restore it after the switch
        int prevCursorMode = glfwGetInputMode(window, GLFW_CURSOR);

        if (!isFullscreen) {
            // Save windowed position and size
            int[] xpos = new int[1], ypos = new int[1];
            glfwGetWindowPos(window, xpos, ypos);
            windowedX = xpos[0];
            windowedY = ypos[0];
            int[] width = new int[1], height = new int[1];
            glfwGetWindowSize(window, width, height);
            windowedWidth = width[0];
            windowedHeight = height[0];
            
            // Get primary monitor and its video mode
            long monitor = glfwGetPrimaryMonitor();
            GLFWVidMode vidMode = glfwGetVideoMode(monitor);
            
            // Switch to fullscreen
            glfwSetWindowMonitor(window, monitor, 0, 0, vidMode.width(), vidMode.height(), vidMode.refreshRate());
            glViewport(0, 0, vidMode.width(), vidMode.height());
            projection.setPerspective((float) Math.toRadians(70), (float) vidMode.width() / vidMode.height(), 0.1f, 10000f);
            isFullscreen = true;
        } else {
            // Switch back to windowed mode
            glfwSetWindowMonitor(window, 0, windowedX, windowedY, windowedWidth, windowedHeight, 0);
            glViewport(0, 0, windowedWidth, windowedHeight);
            projection.setPerspective((float) Math.toRadians(70), (float) windowedWidth / windowedHeight, 0.1f, 10000f);
            isFullscreen = false;
        }

        // Restore cursor mode so fullscreen toggle never leaks the cursor
        glfwSetInputMode(window, GLFW_CURSOR, prevCursorMode);
        firstMouse = true;
    }

    // Reactor hall mezzanine helpers keep rendering + physics in sync
    private float getReactorHallStairGround(float px, float pz) {
        float halfWidth = REACTOR_HALL_STAIRS_WIDTH / 2f + BLOCK_SIZE * 0.3f;
        float minX = REACTOR_HALL_STAIRS_BASE_X - halfWidth;
        float maxX = REACTOR_HALL_STAIRS_BASE_X + halfWidth;
        if (px < minX || px > maxX) {
            return Float.NaN;
        }

        float topZ = REACTOR_HALL_STAIRS_BASE_Z;
        float bottomZ = topZ - REACTOR_HALL_STAIRS_RUN * REACTOR_HALL_STAIRS_STEPS;
        float minZ = Math.min(topZ, bottomZ) - BLOCK_SIZE * 0.4f;
        float maxZ = Math.max(topZ, bottomZ) + BLOCK_SIZE * 0.4f;
        if (pz < minZ || pz > maxZ) {
            return Float.NaN;
        }

        float totalRun = REACTOR_HALL_STAIRS_RUN * REACTOR_HALL_STAIRS_STEPS;
        float progress = (topZ - pz) / totalRun;
        progress = Math.max(0f, Math.min(1f, progress));
        float stairY = REACTOR_HALL_STAIRS_BASE_Y + (REACTOR_HALL_MEZZANINE_Y - REACTOR_HALL_STAIRS_BASE_Y) * progress;
        return GROUND_LEVEL + stairY;
    }

    private float getReactorHallCatwalkGround(float px, float pz, float currentY) {
        float mezzanineWorld = GROUND_LEVEL + REACTOR_HALL_MEZZANINE_Y;
        float activationY = mezzanineWorld - BLOCK_SIZE * 0.6f;
        if (currentY < activationY) {
            return Float.NaN;
        }

        boolean insideHall = (px <= REACTOR_HALL_WINDOW_X && px >= REACTOR_HALL_FAR_X
            && pz <= REACTOR_HALL_FRONT_Z && pz >= REACTOR_HALL_BACK_Z);
        if (!insideHall) {
            return Float.NaN;
        }

        float band = REACTOR_HALL_CATWALK_WIDTH_BLOCKS * BLOCK_SIZE + BLOCK_SIZE * 0.3f;
        boolean alongFront = pz >= REACTOR_HALL_FRONT_Z - band;
        boolean alongBack = pz <= REACTOR_HALL_BACK_Z + band;
        boolean alongWindow = px >= REACTOR_HALL_WINDOW_X - band;
        boolean alongFar = px <= REACTOR_HALL_FAR_X + band;

        if (alongFront || alongBack || alongWindow || alongFar) {
            return mezzanineWorld;
        }
        return Float.NaN;
    }
    
    // Collision detection - returns true if position collides with walls or objects
    // Supports: Control Room, Long Straight Corridor, Turn, Left Arm with Stairs, Reactor Hall
    private boolean checkCollision(float x, float y, float z) {
        float PR = PLAYER_RADIUS;
        float BS = BLOCK_SIZE;

        // Zone detection — check specific lower-level zones FIRST
        float hallEntranceZWorld = -REACTOR_HALL_DEPTH_BLOCKS * BS;
        float hallPositiveZWorld = REACTOR_HALL_DEPTH_BLOCKS * BS;

        boolean inPumpAnnex = (x <= PUMP_ANNEX_MAX_X + BS && x >= PUMP_ANNEX_MIN_X - BS
            && z >= PUMP_ANNEX_MIN_Z - BS && z <= PUMP_ANNEX_MAX_Z + BS);
        boolean inWalkway = (!inPumpAnnex
            && x <= (REACTOR_HALL_WINDOW_X + BS) + PR && x >= (WALKWAY_X_MIN - BS) - PR
            && z >= -46 * BS && z <= PUMP_ANNEX_MIN_Z);
        boolean inPumpWalkway = (!inPumpAnnex
            && x >= PUMP_WALKWAY_INNER_WALL - PR && x <= PUMP_WALKWAY_OUTER_WALL + PR
            && z >= -46 * BS && z <= PUMP_ANNEX_MIN_Z);
        boolean inPumpRightArm = (!inPumpAnnex && !inPumpWalkway && z < -42 * BS && z >= -46 * BS
            && x >= (CORRIDOR_HALF_WIDTH + BS) && x < PUMP_WALKWAY_INNER_WALL);
        boolean inReactorHall = (x <= REACTOR_HALL_WINDOW_X && z >= REACTOR_HALL_BACK_Z && z <= REACTOR_HALL_FRONT_Z
            && !inWalkway && !inPumpWalkway && !inPumpAnnex);
        boolean inControlRoom = (z >= CORRIDOR_START_Z && !inReactorHall);
        boolean inBottomLanding = (!inWalkway && !inPumpWalkway && !inPumpAnnex && z < -42 * BS && z >= -46 * BS
            && x >= -18 * BS && x <= 3 * BS);
        boolean inStairs = (!inWalkway && !inPumpWalkway && !inPumpAnnex && z < -34 * BS && z >= -42 * BS 
            && x >= -(CORRIDOR_HALF_WIDTH + 2 * BS) && x <= (CORRIDOR_HALF_WIDTH + 2 * BS));
        boolean inStraightCorridor = (!inWalkway && !inPumpWalkway && !inReactorHall && !inPumpAnnex
            && z < CORRIDOR_START_Z && z >= -34 * BS
            && x >= -(CORRIDOR_HALF_WIDTH + 2 * BS) && x <= (CORRIDOR_HALF_WIDTH + 2 * BS));

        if (inPumpAnnex) {
            // Pump annex bounds mirrored to the reactor hall
            if (x - PR < PUMP_ANNEX_MIN_X) return true;
            if (x + PR > PUMP_ANNEX_MAX_X) return true;
            if (z + PR > PUMP_ANNEX_MAX_Z) return true;

            float walkwayDoorMinX = PUMP_WALKWAY_X_MIN - BLOCK_SIZE; // Matches rendered doorway width
            float walkwayDoorMaxX = PUMP_WALKWAY_X_MAX + BLOCK_SIZE;
            if (z - PR < PUMP_ANNEX_MIN_Z) {
                boolean inDoorway = (x + PR > walkwayDoorMinX && x - PR < walkwayDoorMaxX);
                if (!inDoorway) return true;
            }

        } else if (inWalkway) {
            // ============= WALKWAY (x=-17 to -21, z=-46 to -12) =============
            if (x - PR < (WALKWAY_X_MIN - BS)) return true;  // Outer railing always solid
            if (z - PR < -46 * BS) return true;  // Far wall
            // Right wall only exists from z=-42 to z=-12 (the long stretch)
            // At z=-42 to z=-46, the right side is OPEN (connects to the landing/left arm)
            if (z >= -42 * BS && z < PUMP_ANNEX_MIN_Z && x + PR > REACTOR_HALL_WINDOW_X) return true;
            // z > -12*BS: open to reactor hall (no near wall)

        } else if (inPumpWalkway) {
            // ============= PUMP WALKWAY (x=17 to 21, z=-46 to -12) =============
            if (x + PR > PUMP_WALKWAY_OUTER_WALL && z < PUMP_ANNEX_MIN_Z) return true;
            if (z - PR < -46 * BS) return true;
            if (z >= -42 * BS && z < PUMP_ANNEX_MIN_Z && x - PR < PUMP_WALKWAY_INNER_WALL - 0.2f * BS) return true;

        } else if (inPumpRightArm) {
            // ============= RIGHT ARM PRIOR TO PUMP WALKWAY =============
            if (z - PR < -46 * BS) return true;
            if (z + PR > -42 * BS) return true;

        } else if (inReactorHall) {
            // ============= GLASS DOME REACTOR HALL =============
            float hallMaxX = REACTOR_HALL_WINDOW_X + REACTOR_HALL_WALL_MARGIN;
            float hallMinX = REACTOR_HALL_FAR_X - REACTOR_HALL_WALL_MARGIN;
            if (x + PR > hallMaxX) return true;
            if (x - PR < hallMinX) return true;

            float hallMaxZ = REACTOR_HALL_FRONT_Z - REACTOR_HALL_WALL_MARGIN;
            float hallMinZ = REACTOR_HALL_BACK_Z + REACTOR_HALL_WALL_MARGIN;
            if (z + PR > hallMaxZ) return true;

            float walkwayDoorMinX = WALKWAY_X_MIN - BLOCK_SIZE;
            float walkwayDoorMaxX = WALKWAY_X_MAX + BLOCK_SIZE;
            boolean inWalkwayDoor = (x + PR > walkwayDoorMinX && x - PR < walkwayDoorMaxX);
            if (z - PR < hallMinZ && !inWalkwayDoor) return true;

            float dxFence = Math.abs(x - REACTOR_CORE_CENTER_X) - PR;
            float dzFence = Math.abs(z - REACTOR_CORE_CENTER_Z) - PR;
            float fenceHalf = REACTOR_FENCE_RADIUS - REACTOR_FENCE_MARGIN;
            if (dxFence < fenceHalf && dzFence < fenceHalf) return true;

        } else if (inControlRoom) {
            // ============= CONTROL ROOM =============
            boolean withinWindowBand = (z >= -4 * BS && z <= 4 * BS);
            float leftWallPlane = withinWindowBand
                ? -ROOM_WIDTH + BS * 0.08f   // hug the glass when looking into the hall
                : -ROOM_WIDTH + BS * 0.4f;   // elsewhere keep a thicker buffer
            float rightWallPlane = ROOM_WIDTH - BS * 0.1f;    // slight cushion so textures do not clip
            float backWallPlane = ROOM_DEPTH - BS * 0.1f;
            float corridorEntryPlane = CORRIDOR_START_Z + BS * 0.5f;

            if (x - PR < leftWallPlane) return true;
            if (x + PR > rightWallPlane) return true;
            if (z + PR > backWallPlane) return true;
            // Front wall - with door opening to corridor
            if (z - PR < corridorEntryPlane) {
                boolean inDoorway = (x > -2.5f * BS && x < 2.5f * BS);
                if (!inDoorway) return true;
            }

        } else if (inStraightCorridor) {
            // ============= STRAIGHT CORRIDOR (z from -12 to -34, flat) =============
            if (x - PR < -CORRIDOR_HALF_WIDTH - BS) return true;  // Left wall
            if (x + PR > CORRIDOR_HALF_WIDTH + BS) return true;   // Right wall stays sealed

        } else if (inStairs) {
            // ============= STAIRS (z from -34 to -42, descending) =============
            if (x - PR < -CORRIDOR_HALF_WIDTH - BS) return true;  // Left wall
            if (x + PR > CORRIDOR_HALF_WIDTH + BS) return true;   // Right wall


        } else if (inBottomLanding) {
            // ============= BOTTOM LANDING + LEFT ARM (z from -42 to -46) =============
            // Right wall at x = +3*BS unless stepping into the mirrored hallway
            float rightWallX = CORRIDOR_HALF_WIDTH + BS;
            if (x + PR > rightWallX) {
                boolean enteringRightHallway = (x <= PUMP_WALKWAY_INNER_WALL + PR);
                if (!enteringRightHallway) return true;
            }
            // Far wall at z = -46
            if (z - PR < -46 * BS) return true;
            // Left side is open to walkway zone
            // z=-42 side: wall except at the stair opening (x from -2 to +2)
            if (z + PR > -42 * BS) {
                boolean inStairOpening = (x >= -CORRIDOR_HALF_WIDTH - PR && x <= CORRIDOR_HALF_WIDTH + PR);
                boolean enteringLeftArm = (x <= REACTOR_HALL_WINDOW_X + PR);
                boolean enteringRightArm = (x >= (CORRIDOR_HALF_WIDTH + BS) - PR && x <= PUMP_WALKWAY_INNER_WALL + PR);
                if (!inStairOpening && !enteringLeftArm && !enteringRightArm) return true;
            }

        } else {
            // Outside all known zones - solid wall
            return true;
        }

        // === NPC COLLISION (floor-aware so only nearby NPCs block) ===
        for (NPCEngineer npc : npcEngineers) {
            float npcEyeLevel = npc.basePosition.y + GROUND_LEVEL;
            if (Math.abs(y - npcEyeLevel) > 80f) continue;
            float npcRadius = 40f;
            float dx = x - npc.basePosition.x;
            float dz = z - npc.basePosition.z;
            float combinedRadius = npcRadius + PR;
            if (dx * dx + dz * dz < combinedRadius * combinedRadius) {
                return true;
            }
        }

        // === CONTROL ROOM FURNITURE COLLISION ===
        if (inControlRoom) {
            float deskZ = -6 * BS;
            float deskDepth = BS * 1.5f;
            float deskHalfDepth = deskDepth * 0.5f;
            float deskTopY = 1.2f * BS + 120f;
            if (y < deskTopY) {
                boolean insideDeskZ = (z > deskZ - deskHalfDepth - PR && z < deskZ + deskHalfDepth + PR);
                boolean insideDeskX = (x > -10 * BS - PR && x < 10 * BS + PR);
                if (insideDeskX && insideDeskZ) {
                    return true;
                }
            }

            float tableZPos = 4 * BS;
            float tableWidth = BS * 2.5f;
            float tableLength = BS * 6;
            float tableTopY = 0.9f * BS + 120f;
            if (y < tableTopY) {
                if (x > -tableWidth/2 - PR && x < tableWidth/2 + PR) {
                    if (z > tableZPos - tableLength/2 - PR && z < tableZPos + tableLength/2 + PR) {
                        return true;
                    }
                }
            }

            float chairSize = BS * 0.7f;
            for (int cz = -2; cz <= 2; cz += 2) {
                float chairZ = tableZPos + cz * BS;
                float leftChairX = -BS * 2.2f;
                if (x > leftChairX - chairSize/2 - PR && x < leftChairX + chairSize/2 + PR) {
                    if (z > chairZ - chairSize/2 - PR && z < chairZ + chairSize/2 + PR) {
                        return true;
                    }
                }
                float rightChairX = BS * 2.2f;
                if (x > rightChairX - chairSize/2 - PR && x < rightChairX + chairSize/2 + PR) {
                    if (z > chairZ - chairSize/2 - PR && z < chairZ + chairSize/2 + PR) {
                        return true;
                    }
                }
            }

            float panelX = (16 - 1.5f) * BS;
            float panelWidth = BS * 0.5f;
            float panelDepth = BS * 2;
            for (int pz = -8; pz <= 8; pz += 4) {
                float panelZ = pz * BS;
                if (x > panelX - panelWidth/2 - PR && x < panelX + panelWidth/2 + PR) {
                    if (z > panelZ - panelDepth/2 - PR && z < panelZ + panelDepth/2 + PR) {
                        return true;
                    }
                }
            }

            for (int pz = (int)(-ROOM_DEPTH/BS) + 3; pz <= (int)(ROOM_DEPTH/BS) - 3; pz += 4) {
                if (pz >= -6 && pz <= 6) continue;
                float panelZ = pz * BS;
                float leftPanelX = (-16 + 1.5f) * BS;
                if (x < leftPanelX + BS + PR) {
                    if (z > panelZ - BS - PR && z < panelZ + BS + PR) {
                        return true;
                    }
                }
            }
        }

        // === PUMP ANNEX EQUIPMENT COLLISION ===
        if (inPumpAnnex) {
            float annexCenterX = (PUMP_ANNEX_MIN_X + PUMP_ANNEX_MAX_X) * 0.5f;
            float annexCenterZ = (PUMP_ANNEX_MIN_Z + PUMP_ANNEX_MAX_Z) * 0.5f;

            // Pump pods on both sides of the trench
            float[] podRows = { annexCenterZ - 3.2f * BS, annexCenterZ + 3.2f * BS };
            float pumpHalfX = 0.95f * BS + PR;
            float pumpHalfZ = 1.35f * BS + PR;
            for (float podZ : podRows) {
                for (int side = -1; side <= 1; side += 2) {
                    float podX = annexCenterX + side * 3.6f * BS;
                    if (Math.abs(x - podX) < pumpHalfX && Math.abs(z - podZ) < pumpHalfZ) return true;
                }
            }

            // Sensor pylons / mast assemblies
            float mastHalf = 0.45f * BS + PR;
            for (float podZ : podRows) {
                for (int side = -1; side <= 1; side += 2) {
                    float podX = annexCenterX + side * 3.6f * BS;
                    float mastX = podX - side * 0.95f * BS;
                    float mastZ = podZ - 1.1f * BS;
                    if (Math.abs(x - mastX) < mastHalf && Math.abs(z - mastZ) < mastHalf) return true;
                }
            }

            // Entry staging pads, hose cradle, and bollards
            float entryZ = PUMP_ANNEX_MIN_Z + 2.4f * BS;
            float entryPadHalfX = 0.85f * BS + PR;
            float entryPadHalfZ = 0.7f * BS + PR;
            if (Math.abs(z - entryZ) < entryPadHalfZ) {
                if (Math.abs(x - (annexCenterX - 2.8f * BS)) < entryPadHalfX) return true;
                if (Math.abs(x - (annexCenterX + 2.8f * BS)) < entryPadHalfX) return true;
            }
            float hoseZ = entryZ + 1.2f * BS;
            float hoseHalfX = 0.6f * BS + PR;
            float hoseHalfZ = 0.45f * BS + PR;
            if (Math.abs(x - annexCenterX) < hoseHalfX && Math.abs(z - hoseZ) < hoseHalfZ) return true;
            // Tool crib and crates near the rear wall
            float backZ = PUMP_ANNEX_MAX_Z - 2f * BS;
            float cribHalfX = 0.7f * BS + PR;
            float cribHalfZ = 0.6f * BS + PR;
            if (Math.abs(x - (annexCenterX - 3.4f * BS)) < cribHalfX && Math.abs(z - backZ) < cribHalfZ) return true;
            float crateHalfX = 0.6f * BS + PR;
            float crateHalfZ = 0.9f * BS + PR;
            if (Math.abs(x - (annexCenterX + 3.4f * BS)) < crateHalfX && Math.abs(z - backZ) < crateHalfZ) return true;

            // Console cluster on the front wall
            float consoleCoreZ = PUMP_CONSOLE_Z + 0.6f * BS;
            float consoleCoreHalfX = 1.8f * BS + PR;
            float consoleCoreHalfZ = 0.6f * BS + PR;
            if (Math.abs(x - PUMP_CONSOLE_X) < consoleCoreHalfX && Math.abs(z - consoleCoreZ) < consoleCoreHalfZ) return true;
            float tabletOffsetX = 2.05f * BS;
            float tabletHalfX = 0.45f * BS + PR;
            float tabletHalfZ = 0.45f * BS + PR;
            if (Math.abs(x - (PUMP_CONSOLE_X - tabletOffsetX)) < tabletHalfX
                    && Math.abs(z - (PUMP_CONSOLE_Z + 0.5f * BS)) < tabletHalfZ) return true;
            if (Math.abs(x - (PUMP_CONSOLE_X + tabletOffsetX)) < tabletHalfX
                    && Math.abs(z - (PUMP_CONSOLE_Z + 0.5f * BS)) < tabletHalfZ) return true;
            float apronZ = PUMP_CONSOLE_Z - 0.75f * BS;
            float apronHalfX = 1.9f * BS + PR;
            float apronHalfZ = 0.45f * BS + PR;
            if (Math.abs(x - PUMP_CONSOLE_X) < apronHalfX && Math.abs(z - apronZ) < apronHalfZ) return true;
            float frontRailZ = PUMP_CONSOLE_Z - 1.05f * BS;
            float frontRailHalfX = 2.0f * BS + PR;
            float frontRailHalfZ = 0.25f * BS + PR;
            if (Math.abs(x - PUMP_CONSOLE_X) < frontRailHalfX && Math.abs(z - frontRailZ) < frontRailHalfZ) return true;

            // Wall lockers flanking the dais
            float lockerHalfX = 0.75f * BS + PR;
            float lockerHalfZ = 0.6f * BS + PR;
            if (Math.abs(z - PUMP_CONSOLE_Z) < lockerHalfZ) {
                if (Math.abs(x - (PUMP_ANNEX_MIN_X + 1.6f * BS)) < lockerHalfX) return true;
                if (Math.abs(x - (PUMP_ANNEX_MAX_X - 1.6f * BS)) < lockerHalfX) return true;
            }
        }

        return false;
    }

    public void render() {
        // Handle game state rendering
        if (gameState == STATE_MENU) {
            renderMenuScreen();
            return;
        }
        if (gameState == STATE_TUTORIAL) {
            renderTutorialScreen();
            return;
        }
        if (gameState == STATE_ENDING) {
            renderEndingScreen();
            return;
        }

        // === PLAYING state render ===
        // Enable blending for transparent glass
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        
        glUseProgram(shaderProgram);
        
        // Upload matrices
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer fb = stack.mallocFloat(16);
            
            glUniformMatrix4fv(viewLoc, false, view.get(fb));
            glUniformMatrix4fv(projectionLoc, false, projection.get(fb));
        }
        
        // Set lighting uniforms
        glUniform3f(lightPosLoc, 0, 300, 0);  // Main light above
        glUniform3f(viewPosLoc, cameraPos.x, cameraPos.y, cameraPos.z);
        float alarmBlend = Math.min(1f, Math.max(0f, alarmActivation));
        float pulseBoost = 0.08f * (float)Math.sin(alarmPulseTimer * 2.2f);
        float flicker = 0.05f * (float)Math.sin(lightingTimer * 5f + alarmBlend * 3f);
        float lightR = 1.0f;
        float lightG = 0.75f * (1f - alarmBlend) + 0.2f * alarmBlend;
        float lightB = 0.45f * (1f - alarmBlend) + 0.2f * alarmBlend;
        glUniform3f(lightColorLoc,
            lightR,
            Math.max(0f, lightG + pulseBoost * alarmBlend),
            Math.max(0f, lightB + pulseBoost * alarmBlend * 0.5f));
        float ambientBase = 0.45f * (1f - alarmBlend) + 0.78f * alarmBlend;
        float ambient = Math.max(0.25f, Math.min(0.95f, ambientBase + flicker));
        glUniform1f(ambientLoc, ambient);
        
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer fb = stack.mallocFloat(16);
            
            // Render textured objects - opaque first
            glUniform1i(useTextureLoc, 1); // Enable texture
            glActiveTexture(GL_TEXTURE0);
            glUniform1i(textureLoc, 0);
            
            // First pass: render opaque objects
            for (TexturedGameObject obj : texturedObjects) {
                if (obj.isTransparent) continue; // Skip transparent objects
                if (obj.isNPCPart) continue; // Rendered in dedicated NPC pass
                model.identity()
                    .translate(obj.position)
                    .scale(obj.scale);
                
                glUniform1i(fullBrightLoc, obj.fullBright ? 1 : 0);
                glUniformMatrix4fv(modelLoc, false, model.get(fb));
                glBindTexture(GL_TEXTURE_2D, obj.textureId);
                
                obj.mesh.render();
            }

            // Render player model in 3rd person view
            if (thirdPerson && playerHead != null) {
                glUniform1i(fullBrightLoc, 0);
                float rotAngle = (float) Math.toRadians(-(playerFacingYaw - 90f));
                float baseX = cameraPos.x;
                float baseY = cameraPos.y - GROUND_LEVEL;
                float baseZ = cameraPos.z;

                float swingAngle = (float) Math.sin(walkAnimPhase) * 0.6f; // ~34 degrees max

                // Pivot heights (from base/feet)
                float legPivotY = 55f;   // top of legs (hip)
                float armPivotY = 55f + 50f; // top of body (shoulder) = legH + bodyH

                // --- Head (static) ---
                model.identity()
                    .translate(baseX, baseY + playerHead.position.y, baseZ)
                    .rotateY(rotAngle)
                    .translate(playerHead.position.x, 0, playerHead.position.z)
                    .scale(playerHead.scale);
                glUniformMatrix4fv(modelLoc, false, model.get(fb));
                glBindTexture(GL_TEXTURE_2D, playerHead.textureId);
                playerHead.mesh.render();

                // --- Body (static) ---
                model.identity()
                    .translate(baseX, baseY + playerBody.position.y, baseZ)
                    .rotateY(rotAngle)
                    .translate(playerBody.position.x, 0, playerBody.position.z)
                    .scale(playerBody.scale);
                glUniformMatrix4fv(modelLoc, false, model.get(fb));
                glBindTexture(GL_TEXTURE_2D, playerBody.textureId);
                playerBody.mesh.render();

                // --- Left Leg (swings forward when right arm goes forward) ---
                model.identity()
                    .translate(baseX, baseY + legPivotY, baseZ)
                    .rotateY(rotAngle)
                    .translate(playerLeftLeg.position.x, 0, 0)
                    .rotateX(swingAngle)
                    .translate(0, -legPivotY/2, 0)
                    .scale(playerLeftLeg.scale);
                glUniformMatrix4fv(modelLoc, false, model.get(fb));
                glBindTexture(GL_TEXTURE_2D, playerLeftLeg.textureId);
                playerLeftLeg.mesh.render();

                // --- Right Leg (opposite swing) ---
                model.identity()
                    .translate(baseX, baseY + legPivotY, baseZ)
                    .rotateY(rotAngle)
                    .translate(playerRightLeg.position.x, 0, 0)
                    .rotateX(-swingAngle)
                    .translate(0, -legPivotY/2, 0)
                    .scale(playerRightLeg.scale);
                glUniformMatrix4fv(modelLoc, false, model.get(fb));
                glBindTexture(GL_TEXTURE_2D, playerRightLeg.textureId);
                playerRightLeg.mesh.render();

                // --- Left Arm (opposite to left leg = same as right leg) ---
                model.identity()
                    .translate(baseX, baseY + armPivotY, baseZ)
                    .rotateY(rotAngle)
                    .translate(playerLeftArm.position.x, 0, 0)
                    .rotateX(-swingAngle)
                    .translate(0, -playerLeftArm.scale.y/2, 0)
                    .scale(playerLeftArm.scale);
                glUniformMatrix4fv(modelLoc, false, model.get(fb));
                glBindTexture(GL_TEXTURE_2D, playerLeftArm.textureId);
                playerLeftArm.mesh.render();

                // --- Right Arm (opposite to right leg = same as left leg) ---
                model.identity()
                    .translate(baseX, baseY + armPivotY, baseZ)
                    .rotateY(rotAngle)
                    .translate(playerRightArm.position.x, 0, 0)
                    .rotateX(swingAngle)
                    .translate(0, -playerRightArm.scale.y/2, 0)
                    .scale(playerRightArm.scale);
                glUniformMatrix4fv(modelLoc, false, model.get(fb));
                glBindTexture(GL_TEXTURE_2D, playerRightArm.textureId);
                playerRightArm.mesh.render();
            }

            // --- Dedicated NPC render pass ---
            glUniform1i(useTextureLoc, 1);
            for (NPCEngineer npc : npcEngineers) {
                if (npc.head == null) continue;
                glUniform1i(fullBrightLoc, 0);

                // Current world position of NPC base (feet level)
                float npcBaseX = npc.basePosition.x;
                float npcBaseY = npc.basePosition.y;
                float npcBaseZ = npc.basePosition.z;
                float npcRot = npc.facing;

                // Compute relative offsets from the original home position
                float homeX = npc.homePosition.x;
                float homeY = npc.homePosition.y;
                float homeZ = npc.homePosition.z;

                float npcSwing = npc.isWalking ? (float) Math.sin(npc.walkPhase) * 0.6f : 0f;
                float npcLegPivotY = 55f;
                float npcArmPivotY = 55f + 50f;

                // Relative X/Z offsets for each part (subtract homePosition to get local coords)
                float headRelX = npc.head.position.x - homeX;
                float headRelY = npc.head.position.y - homeY;
                float headRelZ = npc.head.position.z - homeZ;
                float bodyRelX = npc.body.position.x - homeX;
                float bodyRelY = npc.body.position.y - homeY;
                float bodyRelZ = npc.body.position.z - homeZ;
                float llRelX = npc.leftLeg.position.x - homeX;
                float rlRelX = npc.rightLeg.position.x - homeX;
                float laRelX = npc.leftArm.position.x - homeX;
                float raRelX = npc.rightArm.position.x - homeX;
                float labelRelY = (npc.nameLabel != null) ? npc.nameLabel.position.y - homeY : 150f;

                // --- NPC Head (static, no swing) ---
                model.identity()
                    .translate(npcBaseX, npcBaseY + headRelY, npcBaseZ)
                    .rotateY(npcRot)
                    .translate(headRelX, 0, headRelZ)
                    .scale(npc.head.scale);
                glUniformMatrix4fv(modelLoc, false, model.get(fb));
                glBindTexture(GL_TEXTURE_2D, npc.head.textureId);
                npc.head.mesh.render();

                // --- NPC Body (static) ---
                model.identity()
                    .translate(npcBaseX, npcBaseY + bodyRelY, npcBaseZ)
                    .rotateY(npcRot)
                    .translate(bodyRelX, 0, bodyRelZ)
                    .scale(npc.body.scale);
                glUniformMatrix4fv(modelLoc, false, model.get(fb));
                glBindTexture(GL_TEXTURE_2D, npc.body.textureId);
                npc.body.mesh.render();

                // --- NPC Left Leg (swings forward) ---
                model.identity()
                    .translate(npcBaseX, npcBaseY + npcLegPivotY, npcBaseZ)
                    .rotateY(npcRot)
                    .translate(llRelX, 0, 0)
                    .rotateX(npcSwing)
                    .translate(0, -npcLegPivotY / 2, 0)
                    .scale(npc.leftLeg.scale);
                glUniformMatrix4fv(modelLoc, false, model.get(fb));
                glBindTexture(GL_TEXTURE_2D, npc.leftLeg.textureId);
                npc.leftLeg.mesh.render();

                // --- NPC Right Leg (opposite swing) ---
                model.identity()
                    .translate(npcBaseX, npcBaseY + npcLegPivotY, npcBaseZ)
                    .rotateY(npcRot)
                    .translate(rlRelX, 0, 0)
                    .rotateX(-npcSwing)
                    .translate(0, -npcLegPivotY / 2, 0)
                    .scale(npc.rightLeg.scale);
                glUniformMatrix4fv(modelLoc, false, model.get(fb));
                glBindTexture(GL_TEXTURE_2D, npc.rightLeg.textureId);
                npc.rightLeg.mesh.render();

                // --- NPC Left Arm (opposite to left leg) ---
                model.identity()
                    .translate(npcBaseX, npcBaseY + npcArmPivotY, npcBaseZ)
                    .rotateY(npcRot)
                    .translate(laRelX, 0, 0)
                    .rotateX(-npcSwing)
                    .translate(0, -npc.leftArm.scale.y / 2, 0)
                    .scale(npc.leftArm.scale);
                glUniformMatrix4fv(modelLoc, false, model.get(fb));
                glBindTexture(GL_TEXTURE_2D, npc.leftArm.textureId);
                npc.leftArm.mesh.render();

                // --- NPC Right Arm (opposite to right leg) ---
                model.identity()
                    .translate(npcBaseX, npcBaseY + npcArmPivotY, npcBaseZ)
                    .rotateY(npcRot)
                    .translate(raRelX, 0, 0)
                    .rotateX(npcSwing)
                    .translate(0, -npc.rightArm.scale.y / 2, 0)
                    .scale(npc.rightArm.scale);
                glUniformMatrix4fv(modelLoc, false, model.get(fb));
                glBindTexture(GL_TEXTURE_2D, npc.rightArm.textureId);
                npc.rightArm.mesh.render();

                // --- NPC Name Label (billboard - always faces camera) ---
                if (npc.nameLabel != null) {
                    float labelY = npcBaseY + labelRelY;
                    float dx = cameraPos.x - npcBaseX;
                    float dz = cameraPos.z - npcBaseZ;
                    float labelRot = (float) Math.atan2(dx, dz);

                    model.identity()
                        .translate(npcBaseX, labelY, npcBaseZ)
                        .rotateY(labelRot)
                        .scale(npc.nameLabel.scale);
                    glUniformMatrix4fv(modelLoc, false, model.get(fb));
                    glUniform1i(fullBrightLoc, 1);
                    glBindTexture(GL_TEXTURE_2D, npc.nameLabel.textureId);
                    npc.nameLabel.mesh.render();
                    glUniform1i(fullBrightLoc, 0);
                }
            }
            
            // Render non-textured objects
            glUniform1i(useTextureLoc, 0); // Disable texture
            glUniform1i(fullBrightLoc, 0); // No fullBright for non-textured
            
            for (GameObject obj : gameObjects) {
                model.identity()
                    .translate(obj.position)
                    .scale(obj.scale);
                
                glUniformMatrix4fv(modelLoc, false, model.get(fb));
                glUniform3f(colorLoc, obj.color.x, obj.color.y, obj.color.z);
                
                obj.mesh.render();
            }
            
            // Second pass: render transparent objects (glass) with depth write disabled
            glDepthMask(false); // Disable writing to depth buffer
            glUniform1i(useTextureLoc, 1); // Enable texture
            
            for (TexturedGameObject obj : texturedObjects) {
                if (!obj.isTransparent) continue; // Only transparent objects
                model.identity()
                    .translate(obj.position)
                    .scale(obj.scale);
                
                glUniformMatrix4fv(modelLoc, false, model.get(fb));
                glBindTexture(GL_TEXTURE_2D, obj.textureId);
                
                obj.mesh.render();
            }
            
            glDepthMask(true); // Re-enable depth writing
        }
        
        // Render HUD overlay
        renderHUD();

        // Render pause menu overlay on top of everything
        if (pauseMenuOpen) {
            renderPauseMenu();
        }
    }

    public void cleanup() {
        // Cleanup audio first
        cleanupAudio();

        cubeMesh.cleanup();
        texturedCubeMesh.cleanup();
        npcHeadMesh.cleanup();
        cylinderMesh.cleanup();
        
        // Cleanup textures
        for (int textureId : blockTextures.values()) {
            glDeleteTextures(textureId);
        }
        
        glDeleteProgram(shaderProgram);
        if (hudShaderProgram != 0) {
            glDeleteProgram(hudShaderProgram);
            hudShaderProgram = 0;
        }
    }
    
    // Inner classes for Mesh and GameObject
    
    static class Mesh {
        int vao, vbo, ebo;
        int indexCount;
        boolean hasTexCoords;
        
        Mesh(float[] vertices, int[] indices) {
            this(vertices, indices, false);
        }
        
        Mesh(float[] vertices, int[] indices, boolean hasTexCoords) {
            this.hasTexCoords = hasTexCoords;
            indexCount = indices.length;
            
            int stride = hasTexCoords ? 8 : 6; // 8 floats with UV, 6 without
            
            vao = glGenVertexArrays();
            glBindVertexArray(vao);
            
            vbo = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
            vertexBuffer.put(vertices).flip();
            glBufferData(GL_ARRAY_BUFFER, vertexBuffer, GL_STATIC_DRAW);
            
            ebo = glGenBuffers();
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
            IntBuffer indexBuffer = BufferUtils.createIntBuffer(indices.length);
            indexBuffer.put(indices).flip();
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL_STATIC_DRAW);
            
            // Position attribute
            glVertexAttribPointer(0, 3, GL_FLOAT, false, stride * Float.BYTES, 0);
            glEnableVertexAttribArray(0);
            
            // Normal attribute
            glVertexAttribPointer(1, 3, GL_FLOAT, false, stride * Float.BYTES, 3 * Float.BYTES);
            glEnableVertexAttribArray(1);
            
            // Texture coordinate attribute (if present)
            if (hasTexCoords) {
                glVertexAttribPointer(2, 2, GL_FLOAT, false, stride * Float.BYTES, 6 * Float.BYTES);
                glEnableVertexAttribArray(2);
            }
            
            glBindVertexArray(0);
        }
        
        void render() {
            glBindVertexArray(vao);
            glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0);
            glBindVertexArray(0);
        }
        
        void cleanup() {
            glDeleteVertexArrays(vao);
            glDeleteBuffers(vbo);
            glDeleteBuffers(ebo);
        }
    }
    
    static class GameObject {
        Mesh mesh;
        Vector3f position;
        Vector3f scale;
        Vector3f color;
        
        GameObject(Mesh mesh, Vector3f position, Vector3f scale, Vector3f color) {
            this.mesh = mesh;
            this.position = position;
            this.scale = scale;
            this.color = color;
        }
    }

    static class AlarmLight {
        GameObject glassDome;

        AlarmLight(GameObject glassDome) {
            this.glassDome = glassDome;
        }

        void updateGlow(float activation, float pulse) {
            if (glassDome == null) return;
            float glow = activation * (0.35f + 0.65f * pulse);
            float r = Math.min(1f, 0.65f + glow * 0.35f);
            float g = 0.08f + glow * 0.18f;
            float b = 0.08f + glow * 0.12f;
            glassDome.color.set(r, g, b);
        }
    }
    
    static class TexturedGameObject {
        Mesh mesh;
        Vector3f position;
        Vector3f scale;
        int textureId;
        String textureName;
        boolean isTransparent;
        boolean fullBright;
        boolean isNPCPart; // Skip in generic render loop, rendered in NPC pass
        boolean isCreativeBlock;
        
        TexturedGameObject(Mesh mesh, Vector3f position, Vector3f scale, int textureId, boolean isTransparent) {
            this.mesh = mesh;
            this.position = position;
            this.scale = scale;
            this.textureId = textureId;
            this.isTransparent = isTransparent;
            this.fullBright = false;
            this.isNPCPart = false;
            this.textureName = "";
            this.isCreativeBlock = false;
        }
    }
    
    static class CreativePlacement {
        String textureName;
        Vector3f position;

        CreativePlacement(String textureName, Vector3f position) {
            this.textureName = textureName;
            this.position = new Vector3f(position);
        }
    }

    static class CreativeRemoval {
        Vector3f position;
        Vector3f scale;

        CreativeRemoval(Vector3f position, Vector3f scale) {
            this.position = new Vector3f(position);
            this.scale = new Vector3f(scale);
        }
    }

    static class CreativeHit {
        TexturedGameObject block;
        Vector3f point;
        Vector3f normal;
        float distance;

        CreativeHit(TexturedGameObject block, Vector3f point, Vector3f normal, float distance) {
            this.block = block;
            this.point = point;
            this.normal = normal;
            this.distance = distance;
        }
    }
    
    // Fuel rod animation data
    static class FuelRodAnimation {
        TexturedGameObject rod;
        float baseY;
        float speed;
        float phase;
        float amplitude;
        
        FuelRodAnimation(TexturedGameObject rod, float baseY, float speed, float phase, float amplitude) {
            this.rod = rod;
            this.baseY = baseY;
            this.speed = speed;
            this.phase = phase;
            this.amplitude = amplitude;
        }
    }
    
    // Gauge display animation data
    static class GaugeDisplay {
        TexturedGameObject display;
        String label;
        float minValue;
        float maxValue;
        float baseValue;
        float fluctuation;
        float speed;
        float phase;
        int textureIndex;
        
        GaugeDisplay(TexturedGameObject display, String label, float minValue, float maxValue, 
                     float baseValue, float fluctuation, float speed, float phase, int textureIndex) {
            this.display = display;
            this.label = label;
            this.minValue = minValue;
            this.maxValue = maxValue;
            this.baseValue = baseValue;
            this.fluctuation = fluctuation;
            this.speed = speed;
            this.phase = phase;
            this.textureIndex = textureIndex;
        }
    }
    
    // NPC Engineer data for idle animation
    static class NPCEngineer {
        String name;
        TexturedGameObject head;
        TexturedGameObject body;
        TexturedGameObject leftLeg;
        TexturedGameObject rightLeg;
        TexturedGameObject leftArm;
        TexturedGameObject rightArm;
        TexturedGameObject nameLabel;
        Vector3f basePosition;
        float facing; // Rotation angle
        float idlePhase; // For subtle idle animation
        float headBobTime;
        boolean lookingAround;
        float lookTimer;
        float lookDirection;

        // Phase 3: AI fields
        Vector3f targetPosition;    // Where the NPC is walking to
        boolean isWalking;          // Currently moving
        float walkPhase;            // Leg/arm swing phase
        float moveSpeed;            // Units per second
        String calloutText;         // Text shown above NPC head when calling player
        float calloutTimer;         // Countdown for callout visibility
        boolean facingPlayer;       // Should NPC rotate to face the player
        int npcStoryState;          // Tracks which story state this NPC acts on
        Vector3f homePosition;      // Default position (where NPC was placed)
        
        NPCEngineer(String name, TexturedGameObject head, TexturedGameObject body,
                    TexturedGameObject leftLeg, TexturedGameObject rightLeg,
                    TexturedGameObject leftArm, TexturedGameObject rightArm,
                    Vector3f basePosition, float facing, float idlePhase) {
            this.name = name;
            this.head = head;
            this.body = body;
            this.leftLeg = leftLeg;
            this.rightLeg = rightLeg;
            this.leftArm = leftArm;
            this.rightArm = rightArm;
            this.basePosition = basePosition;
            this.facing = facing;
            this.idlePhase = idlePhase;
            this.headBobTime = 0;
            this.lookingAround = false;
            this.lookTimer = 3.0f + (float)Math.random() * 2.0f;
            this.lookDirection = 0;
            // AI defaults
            this.targetPosition = new Vector3f(basePosition);
            this.isWalking = false;
            this.walkPhase = 0;
            this.moveSpeed = 120f;
            this.calloutText = "";
            this.calloutTimer = 0;
            this.facingPlayer = false;
            this.npcStoryState = -1;
            this.homePosition = new Vector3f(basePosition);
        }
    }
}
