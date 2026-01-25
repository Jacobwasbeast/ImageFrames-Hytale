package dev.jacobwasbeast;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import dev.jacobwasbeast.config.ImageFramesConfig;
import dev.jacobwasbeast.interaction.ImageFrameBreakSystem;
import dev.jacobwasbeast.interaction.ImageFrameInteractionSystem;
import dev.jacobwasbeast.runtime.ImageFrameRuntimeManager;
import dev.jacobwasbeast.store.ImageFrameStore;
import dev.jacobwasbeast.ui.ImageFrameConfigSupplier;

import java.util.logging.Level;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;

public class ImageFramesPlugin extends JavaPlugin {
    private static ImageFramesPlugin instance;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private ImageFramesConfig config;
    private ImageFrameStore store;
    private ImageFrameRuntimeManager runtimeManager;

    public ImageFramesPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    @Override
    protected void setup() {
        getLogger().at(Level.INFO).log("ImageFrames setup...");
        this.getCodecRegistry(OpenCustomUIInteraction.PAGE_CODEC)
                .register("ImageFrames_Config", ImageFrameConfigSupplier.class, ImageFrameConfigSupplier.CODEC);

        this.config = new ImageFramesConfig();
        this.store = new ImageFrameStore();
        this.runtimeManager = new ImageFrameRuntimeManager(this, store);
    }

    @Override
    protected void start() {
        getLogger().at(Level.INFO).log("ImageFrames starting...");

        var entityRegistry = com.hypixel.hytale.server.core.modules.entity.EntityModule.get().getEntityStoreRegistry();
        entityRegistry.registerSystem(new ImageFrameInteractionSystem(this));
        entityRegistry.registerSystem(new ImageFrameBreakSystem(this));

        com.hypixel.hytale.server.core.HytaleServer.get().getEventBus().registerGlobal(
                com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent.class,
                event -> {
                    if (initialized.compareAndSet(false, true)) {
                        getLogger().at(Level.INFO).log("First player joined. Starting async initialization...");
                        new Thread(() -> {
                            try {
                                this.config.syncLoad();
                                this.store.syncLoad();
                                this.runtimeManager.init();

                                com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.execute(() -> {
                                    getLogger().at(Level.INFO).log("Async init complete. Broadcasting assets.");
                                    this.runtimeManager.broadcastRuntimeAssets();
                                    this.runtimeManager.refreshFramesForWorld(event.getPlayer().getWorld());
                                });
                            } catch (Exception e) {
                                getLogger().at(Level.SEVERE).withCause(e)
                                        .log("Failed to initialize ImageFrames asynchronously");
                            }
                        }).start();
                        initialized.set(true);
                        return;
                    }
                    // Always broadcast/refresh on join to ensure client sync
                    getLogger().at(Level.INFO).log("Player ready. Broadcasting ImageFrames assets.");
                    this.runtimeManager.broadcastRuntimeAssets();
                    this.runtimeManager.refreshFramesForWorld(event.getPlayer().getWorld());
                });

        getLogger().at(Level.INFO).log("ImageFrames started.");
    }

    @Override
    protected void shutdown() {
        getLogger().at(Level.INFO).log("ImageFrames shutting down.");
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
