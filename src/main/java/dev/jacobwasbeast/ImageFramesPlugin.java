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
import javax.annotation.Nonnull;

public class ImageFramesPlugin extends JavaPlugin {
    private static ImageFramesPlugin instance;
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
    }

    @Override
    protected void start() {
        getLogger().at(Level.INFO).log("ImageFrames starting...");
        this.config = new ImageFramesConfig();
        this.config.syncLoad();

        this.store = new ImageFrameStore();
        this.store.syncLoad();

        this.runtimeManager = new ImageFrameRuntimeManager(this, store);
        this.runtimeManager.init();

        var entityRegistry = com.hypixel.hytale.server.core.modules.entity.EntityModule.get().getEntityStoreRegistry();
        entityRegistry.registerSystem(new ImageFrameInteractionSystem(this));
        entityRegistry.registerSystem(new ImageFrameBreakSystem(this));

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
