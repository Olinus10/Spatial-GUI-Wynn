package org.tastytrash.spatialGUI.client;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.*;
import net.minecraft.world.phys.Vec3;
import org.tastytrash.spatialGUI.api.SpatialGUIAPI;
import org.tastytrash.spatialGUI.compat.VisorCompat;
import org.tastytrash.spatialGUI.config.ConfigManager;
import org.tastytrash.spatialGUI.editor.EditorMode;import org.tastytrash.spatialGUI.editor.EditorScreen;
import org.tastytrash.spatialGUI.render.SpatialGUIRenderer;
import org.tastytrash.spatialGUI.SpatialGUI;
import org.tastytrash.spatialGUI.config.SpatialGUIConfig;
//? if fabric {
 import net.fabricmc.api.ClientModInitializer;
 import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
//? } else if neoforge {
/*import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
*///? } else if forge {
/*import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
*///? }

//? if fabric {
 public class SpatialGUIClient implements ClientModInitializer {
//? } else if neoforge{
/*public class SpatialGUIClient {
*///?} else if forge {
/*@Mod(value = SpatialGUI.MOD_ID)
public class SpatialGUIClient {
*///?}

    private static SpatialGUIRenderer renderer;
    private static boolean effectiveFirstPersonMode = false;
    private static boolean switchedToFirstPersonDueToBlock = false;
    private static boolean wasThirdPersonCamera = false;
    private static CameraType originalCameraType;
    private static boolean capturingPanorama = false;
    private static Vec3 cameraPosition;
    private static Vec3 screenAnchorDelta = Vec3.ZERO;
    private static boolean wasClosing = false;
    private static boolean inShouldHookScreen = false;

    //? if fabric {
    @Override
    public void onInitializeClient() {
        SpatialGUI.initCommon();
        renderer = new SpatialGUIRenderer();
        SpatialGUIKeybinds.register();

        ScreenEvents.BEFORE_INIT.register((clientArg, screen, scaledWidth, scaledHeight) -> {
            if (SpatialGUIClient.shouldHookScreen(screen)) {
                renderer.hookScreen(screen);
            }
        });
    }
    //? } else if neoforge {
    /*public SpatialGUIClient(net.neoforged.bus.api.IEventBus modBus) {
        renderer = new SpatialGUIRenderer();

        modBus.addListener((RegisterKeyMappingsEvent e) -> SpatialGUIKeybinds.register(e));

        NeoForge.EVENT_BUS.addListener(this::onScreenInit);
    }

    private void onScreenInit(ScreenEvent.Init.Pre event) {
        var screen = event.getScreen();

        if (SpatialGUIClient.shouldHookScreen(screen)) {
            renderer.hookScreen(screen);
        }
    }
    *///?} else if forge {
    /*public SpatialGUIClient() {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        SpatialGUI.initCommon();
        renderer = new SpatialGUIRenderer();

        ModLoadingContext.get().registerExtensionPoint(
        ConfigScreenHandler.ConfigScreenFactory.class,
        () -> new ConfigScreenHandler.ConfigScreenFactory(
                (mc, parent) -> SpatialGUIConfig.createScreen(parent))
        );

        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(SpatialGUIKeybinds::register);
        MinecraftForge.EVENT_BUS.addListener(this::onScreenInit);
    }

    private void onScreenInit(ScreenEvent.Init.Pre event) {
        var screen = event.getScreen();
        if (shouldHookScreen(screen)) renderer.hookScreen(screen);
    }
    *///?}

    public static boolean isEnabled() {
        if (ConfigManager.config() == null) return false;
        if (VisorCompat.isActive()) return false;
        if (capturingPanorama) return false;
        if (sessionDisabled) return false;
        return ConfigManager.config().enabled;
    }

    // ==========================================================
    // Title-based exclusion & runtime toggle
    // (server-side "fake" GUIs, e.g. Wynncraft lootrun chests)
    // ==========================================================

    /** Session-only global kill switch, toggled by the keybind outside of menus. Not saved. */
    private static boolean sessionDisabled = false;
    /** The one screen instance the user turned Spatial GUI off for with the keybind. */
    private static java.lang.ref.WeakReference<Screen> bypassedScreen = new java.lang.ref.WeakReference<>(null);
    /** True while the HUD shows a full-screen server overlay (a resource-pack glyph title, e.g. Wynncraft's black backdrop). */
    private static boolean hudOverlayActive = false;
    /** Set when the open screen must be re-laid out after a toggle (done on the next tick, not inside keyPressed). */
    private static boolean pendingRelayout = false;

    // shouldHookScreen() runs many times per frame (the GUI scale mixin calls it), so the title scan is cached per screen.
    private static java.lang.ref.WeakReference<Screen> titleCacheScreen = new java.lang.ref.WeakReference<>(null);
    private static String titleCacheLower = "";
    private static boolean titleCacheCustomGlyphs = false;
    private static final java.util.Map<String, java.util.regex.Pattern> PATTERN_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    public static boolean isSessionDisabled() {
        return sessionDisabled;
    }

    private static boolean isScreenBypassed(Screen screen) {
        return screen != null && bypassedScreen.get() == screen;
    }

    /**
     * True if the screen title matches an entry in {@code disabledTitles}, or if
     * {@code disableCustomTextureMenus} is on and the title draws a server resource-pack texture.
     * Servers like Wynncraft draw custom backgrounds by putting private-use-area font glyphs in a
     * container's title; those don't survive being moved onto the 3D panel (the black background ends up on top).
     */
    public static boolean isExcludedByTitle(Screen screen) {
        var config = ConfigManager.config();
        if (config == null || screen == null) return false;
        boolean checkGlyphs = config.disableCustomTextureMenus;
        boolean checkList = config.disabledTitles != null && !config.disabledTitles.isEmpty();
        if (!checkGlyphs && !checkList) return false;

        if (titleCacheScreen.get() != screen) {
            String title;
            try {
                title = screen.getTitle() == null ? "" : screen.getTitle().getString();
            } catch (Throwable t) {
                title = "";
            }
            titleCacheScreen = new java.lang.ref.WeakReference<>(screen);
            titleCacheLower = title.toLowerCase(java.util.Locale.ROOT);
            titleCacheCustomGlyphs = containsPrivateUseGlyph(title);
        }

        if (checkGlyphs && titleCacheCustomGlyphs) return true;
        if (!checkList) return false;

        for (String entry : config.disabledTitles) {
            if (entry == null || entry.isBlank()) continue;
            if (entry.startsWith("regex:")) {
                java.util.regex.Pattern pattern = PATTERN_CACHE.computeIfAbsent(entry, e -> {
                    try {
                        return java.util.regex.Pattern.compile(e.substring(6), java.util.regex.Pattern.CASE_INSENSITIVE);
                    } catch (java.util.regex.PatternSyntaxException ex) {
                        return null;
                    }
                });
                if (pattern != null && pattern.matcher(titleCacheLower).find()) return true;
            } else if (titleCacheLower.contains(entry.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsPrivateUseGlyph(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            // BMP Private Use Area, and Supplementary Private Use Areas A and B
            if ((cp >= 0xE000 && cp <= 0xF8FF) || cp >= 0xF0000) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    /**
     * Keybind pressed while a menu is open: turn Spatial GUI off for this menu only (or back on).
     * Returns true if the key was handled.
     */
    public static boolean toggleForScreen(Screen screen) {
        if (screen == null || renderer == null || screen instanceof EditorScreen) return false;
        if (Minecraft.getInstance().level == null) return false;
        if (renderer.getHookedScreen() == screen) {
            bypassedScreen = new java.lang.ref.WeakReference<>(screen);
            renderer.unhookScreen();
            pendingRelayout = true;
            SpatialGUIKeybinds.showStatus(net.minecraft.network.chat.Component.translatable("message.spatial-gui.screen_disabled"));
            return true;
        }
        if (isScreenBypassed(screen)) {
            bypassedScreen = new java.lang.ref.WeakReference<>(null);
            pendingRelayout = true;
            SpatialGUIKeybinds.showStatus(net.minecraft.network.chat.Component.translatable("message.spatial-gui.screen_enabled"));
            return true;
        }
        return false;
    }

    /** Keybind pressed with no menu open: toggle Spatial GUI for the rest of the session. */
    public static void toggleSession() {
        if (renderer != null && renderer.getHookedScreen() != null) renderer.unhookScreen();
        sessionDisabled = !sessionDisabled;
        SpatialGUIKeybinds.showStatus(net.minecraft.network.chat.Component.translatable(
                sessionDisabled ? "message.spatial-gui.session_disabled" : "message.spatial-gui.session_enabled"));
    }

    /**
     * Called every client tick. Spatial GUI draws the menu into the world, which is rendered before the HUD,
     * so a full-screen HUD title (the black background Wynncraft shows behind lootrun menus) ends up covering it.
     * While such a title is up, the open menu is shown flat instead, and goes back to spatial once it fades.
     * Same while the server has the camera spectating another entity (e.g. Wynncraft's class selection),
     * where the panel would otherwise be anchored to that entity's view.
     */
    public static void updateHudOverlay(Minecraft client) {
        boolean active = false;
        var config = ConfigManager.config();
        if (config != null && config.disableDuringHudOverlays && client.gui != null && client.level != null) {
            var gui = (org.tastytrash.spatialGUI.mixin.accessor.GuiTitleAccessor) client.gui;
            if (gui.spatialGUI$getTitleTime() > 0) {
                active = isGlyphText(gui.spatialGUI$getTitle()) || isGlyphText(gui.spatialGUI$getSubtitle());
            }
        }
        if (!active && config != null && config.disableWhileSpectatingEntity && client.player != null && client.level != null) {
            var camera = client.getCameraEntity();
            active = camera != null && camera != client.player;
        }
        if (active == hudOverlayActive) return;
        hudOverlayActive = active;

        //? if >=26.2 {
        /*Screen screen = client.gui.screen();
         *///?} else {
        Screen screen = client.screen;
        //?}
        if (screen == null || renderer == null) return;
        if (active && renderer.getHookedScreen() == screen && !(screen instanceof EditorScreen)) {
            renderer.unhookScreen();
        }
        // Re-init the menu so it picks up the right GUI scale, and re-hooks when the overlay is gone.
        pendingRelayout = true;
    }

    private static boolean isGlyphText(net.minecraft.network.chat.Component component) {
        if (component == null) return false;
        try {
            return containsPrivateUseGlyph(component.getString());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Called every client tick: re-lays out the open screen after a toggle so it picks up the right GUI scale and (un)hooks. */
    public static void processPendingRelayout(Minecraft client) {
        if (!pendingRelayout) return;
        pendingRelayout = false;
        //? if >=26.2 {
        /*Screen screen = client.gui.screen();
         *///?} else {
        Screen screen = client.screen;
        //?}
        if (screen == null) return;
        int w = client.getWindow().getGuiScaledWidth();
        int h = client.getWindow().getGuiScaledHeight();
        //? if >1.21.1 {
        screen.resize(w, h);
        //?} else {
        /*screen.resize(client, w, h);
         *///?}
    }

    public static boolean shouldHideHud() {
        return SpatialGUIAPI.isActive() && ConfigManager.config().hideHud;
    }

    public static boolean shouldHideHotbar() {
        if (!SpatialGUIAPI.isActive()) return false;
        var renderer = renderer();
        boolean editorOpen = renderer != null && renderer.getHookedScreen() instanceof EditorScreen;
        return editorOpen || ConfigManager.config().hideHotbar;
    }

    public static boolean shouldHideCrosshair() {
        return SpatialGUIAPI.isFirstPersonMode()
                && (!ConfigManager.config().useCrosshairForFirstPerson || EditorMode.mouseFree());
    }

    public static boolean shouldHookScreen(Screen screen) {
        if (screen == null) return false;
        if (screen instanceof EditorScreen) return true;

        //? if >1.20.1 {
        if (screen instanceof net.minecraft.client.gui.screens.GenericMessageScreen) return false;
        //?} else {
        /*if (screen instanceof net.minecraft.client.gui.screens.GenericDirtMessageScreen) return false;
        *///?}
        //? if <1.21.11 {
        /*if (screen instanceof net.minecraft.client.gui.screens.LevelLoadingScreen
         || screen instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen) return false;
        *///?}

        if (ConfigManager.config() == null) return false;
        if (Minecraft.getInstance().level == null) return false;
        var client = Minecraft.getInstance();
        boolean isFirstPerson;

        if (inShouldHookScreen) {
            isFirstPerson = true;
        } else {
            inShouldHookScreen = true;
            try {
                isFirstPerson = client.options.getCameraType() == CameraType.FIRST_PERSON;
            } finally {
                inShouldHookScreen = false;
            }
        }

        if (isFirstPerson && ConfigManager.config().disableFirstPerson) return false;
        if (!isFirstPerson && ConfigManager.config().disableThirdPerson) return false;

        String id = screen.getClass().getName();
        if (id.contains("TitleScreen") || id.contains("ReceivingLevelScreen")
                || id.contains("LevelLoadingScreen") || id.contains("ChatScreen")
                || id.contains("yacl") || id.contains("Reconfig")
                || id.contains("Progress") || id.contains("GenericMessage")
                || id.contains("Disconnected") || id.contains("ConnectScreen")
                || id.contains("GenericDirtMessage") || id.contains("GenericWaiting")
                || id.contains("WaitingForResponse") || id.contains("JoinMultiplayer")
                || id.contains("SelectWorld") || id.contains("ErrorScreen")
                || id.contains("OutOfMemory") || id.contains("DatapackLoadFailure")
                || id.contains("WinScreen") || id.contains("FileFixer")
                || id.contains("ScreenManagerScreen"))
            return false;

        if (!id.contains("$") && !ConfigManager.config().seenScreens.contains(id)) {
            try {
                ConfigManager.config().seenScreens.add(id);
            } catch (UnsupportedOperationException e) {
                ConfigManager.config().seenScreens = new java.util.ArrayList<>(ConfigManager.config().seenScreens);
                ConfigManager.config().seenScreens.add(id);
            }
        }

        if (ConfigManager.config().disabledScreens.contains(id)) return false;
        if (isScreenBypassed(screen)) return false;
        if (hudOverlayActive) return false;
        if (isExcludedByTitle(screen)) return false;

        boolean categorized = true;
        boolean toggle;
        if (screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen) {
            toggle = ConfigManager.config().inventory;
        } else if (screen instanceof AbstractContainerScreen<?>) {
            toggle = ConfigManager.config().containers;
        } else if (screen instanceof PauseScreen) {
            toggle = ConfigManager.config().pauseScreen;
        } else {
            categorized = false;
            toggle = false;
        }

        if (categorized) return toggle;
        if (ConfigManager.config().enabledScreens.contains(id)) return true;
        return ConfigManager.config().allScreens && Minecraft.getInstance().level != null;
    }

    public static void showScreen(Minecraft client, Screen screen) {
        //? if >1.21.11 {
        client.setScreen(screen);
        //?} else {
        /*client.setScreen(screen);
        *///?}
    }

    public static SpatialGUIRenderer renderer() {
        return renderer;
    }

    public static void clearTarget() {
        if (!SpatialGUIAPI.isActive()) {
            return;
        }
        var client = Minecraft.getInstance();
        client.hitResult = net.minecraft.world.phys.BlockHitResult.miss(Vec3.ZERO, net.minecraft.core.Direction.UP, net.minecraft.core.BlockPos.ZERO);
        client.crosshairPickEntity = null;
    }

    public static boolean getEffectiveFirstPersonMode() {
        return effectiveFirstPersonMode;
    }

    public static void setEffectiveFirstPersonMode(boolean value) {
        effectiveFirstPersonMode = value;
    }

    public static boolean getSwitchedToFirstPersonDueToBlock() {
        return switchedToFirstPersonDueToBlock;
    }

    public static void setSwitchedToFirstPersonDueToBlock(boolean value) {
        switchedToFirstPersonDueToBlock = value;
    }

    public static boolean getWasThirdPersonCamera() {
        return wasThirdPersonCamera;
    }

    public static void setWasThirdPersonCamera(boolean value) {
        wasThirdPersonCamera = value;
    }

    public static boolean shouldUseFirstPersonMode(Screen screen) {
        if (screen instanceof EditorScreen) {
            Boolean forced = EditorMode.viewOverride();
            if (forced != null) return forced;
        }
        if (ConfigManager.config().autoDetectCameraMode) {
            var client = Minecraft.getInstance();
            return switchedToFirstPersonDueToBlock || client.options.getCameraType() == net.minecraft.client.CameraType.FIRST_PERSON;
        } else {
            return ConfigManager.config().viewMode == SpatialGUIConfig.ViewMode.FIRST_PERSON
                || switchedToFirstPersonDueToBlock;
        }
    }

    public static void setCapturingPanorama(boolean value) {
        capturingPanorama = value;
    }

    private static long handPokeStart = -1;

    public static void startHandPoke() {
        handPokeStart = System.nanoTime();
    }

    public static float handPokeOffset() {
        if (handPokeStart < 0) return 0f;
        float t = (System.nanoTime() - handPokeStart) / (ConfigManager.config().handPokeDurationMs * 1_000_000f);
        if (t >= 1f) {
            handPokeStart = -1;
            return 0f;
        }
        return (float) (0.05 * ConfigManager.config().handPokeStrength * Math.sin(Math.PI * Math.pow(t, 0.45)));
    }

    public static Vec3 getCameraPosition() {
        return cameraPosition;
    }

    public static void setCameraPosition(Vec3 position) {
        cameraPosition = position;
    }

    public static boolean getWasClosing() {
        return wasClosing;
    }

    public static void setWasClosing(boolean value) {
        wasClosing = value;
    }

    public static CameraType getOriginalCameraType() {
        return originalCameraType;
    }

    public static void setOriginalCameraType(CameraType value) {
        originalCameraType = value;
    }

    public static Vec3 getScreenAnchorDelta() {
        return screenAnchorDelta;
    }

    public static void setScreenAnchorDelta(Vec3 delta) {
        screenAnchorDelta = delta;
    }
 }