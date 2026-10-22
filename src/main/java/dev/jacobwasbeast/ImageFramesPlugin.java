package dev.jacobwasbeast;

import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.asset.LoadAssetEvent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import dev.jacobwasbeast.config.ImageFramesConfig;
import dev.jacobwasbeast.interaction.ImageFrameBreakSystem;
import dev.jacobwasbeast.interaction.ImageFrameInteractionSystem;
import dev.jacobwasbeast.runtime.ImageFrameRuntimeManager;
import dev.jacobwasbeast.store.ImageFrameStore;
import dev.jacobwasbeast.ui.ImageFrameConfigSupplier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import javax.annotation.Nonnull;

public class ImageFramesPlugin extends JavaPlugin {
    private static ImageFramesPlugin instance;
    private final ExecutorService assetExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ImageFrames-assets");
        thread.setDaemon(true);
        return thread;
    });
    private final CompletableFuture<Void> initialization = new CompletableFuture<>();
    private volatile boolean initialized;
    private volatile boolean stopping;
    private ImageFramesConfig config;
    private ImageFrameStore store;
    private ImageFrameRuntimeManager runtimeManager;

    public ImageFramesPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    @Override
    protected void setup() {
        getCodecRegistry(OpenCustomUIInteraction.PAGE_CODEC)
                .register("ImageFrames_Config", ImageFrameConfigSupplier.class, ImageFrameConfigSupplier.CODEC);
        config = new ImageFramesConfig();
        store = new ImageFrameStore();
        runtimeManager = new ImageFrameRuntimeManager(this, store);
        getEntityStoreRegistry().registerSystem(new ImageFrameInteractionSystem(this));
        getEntityStoreRegistry().registerSystem(new ImageFrameBreakSystem(this));
        getEventRegistry().registerGlobal(PlayerReadyEvent.class, event -> {
            World world = event.getPlayer().getWorld();
            initialization.thenRun(() -> refreshWorld(world));
        });
        // Restore dynamic block types before world sections can deserialize their saved ids.
        getEventRegistry().register(LoadAssetEvent.PRIORITY_LOAD_LATE, LoadAssetEvent.class, event -> {
            try {
                config.syncLoad();
                store.syncLoad();
                runtimeManager.init();
                initialized = true;
                initialization.complete(null);
            } catch (Exception e) {
                initialization.completeExceptionally(e);
                event.failed(true, "ImageFrames runtime assets could not be initialized");
                getLogger().at(Level.SEVERE).withCause(e).log("Failed to initialize ImageFrames");
            }
        });
    }

    @Override
    protected void start() {
        if (!isInitialized()) return;
        runtimeManager.startIntegrityChecks(2);
        Universe.get().getUniverseReady().thenRun(() -> {
            for (World world : Universe.get().getWorlds().values()) {
                refreshWorld(world);
            }
        });
        getLogger().at(Level.INFO).log("ImageFrames initialized for Hytale 0.7.3.");
    }

    private void refreshWorld(World world) {
        if (world == null || stopping) return;
        world.execute(() -> {
            if (stopping || !world.isAlive()) return;
            runtimeManager.broadcastRuntimeAssets();
            runtimeManager.refreshFramesForWorld(world);
        });
    }

    @Override
    protected void shutdown() {
        stopping = true;
        initialized = false;
        initialization.cancel(false);
        if (runtimeManager != null) runtimeManager.shutdown();
        assetExecutor.shutdownNow();
        instance = null;
    }

    public boolean isInitialized() {
        return initialized && !stopping;
    }

    public ExecutorService getAssetExecutor() {
        return assetExecutor;
    }

    public static ImageFramesPlugin getInstance() {
        return instance;
    }

    public ImageFramesConfig getConfig() {
        return config;
    }

    public ImageFrameStore getStore() {
        return store;
    }

    public ImageFrameRuntimeManager getRuntimeManager() {
        return runtimeManager;
    }
}
