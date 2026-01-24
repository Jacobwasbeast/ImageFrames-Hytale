package dev.jacobwasbeast.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.server.core.util.io.BlockingDiskFile;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Path;

public class ImageFramesConfig extends BlockingDiskFile {
    private boolean ownerLockEnabled = true;
    private int tileSize = 256;

    public ImageFramesConfig() {
        super(Path.of("ImageFrames/config.json"));
        ensureParent();
    }

    @Override
    protected void read(BufferedReader bufferedReader) throws IOException {
        JsonObject obj = JsonParser.parseReader(bufferedReader).getAsJsonObject();
        if (obj.has("ownerLockEnabled")) {
            ownerLockEnabled = obj.get("ownerLockEnabled").getAsBoolean();
        }
        if (obj.has("tileSize")) {
            tileSize = Math.max(16, obj.get("tileSize").getAsInt());
        }
    }

    @Override
    protected void write(BufferedWriter bufferedWriter) throws IOException {
        if (getPath().getParent() != null) {
            java.nio.file.Files.createDirectories(getPath().getParent());
        }
        JsonObject obj = new JsonObject();
        obj.addProperty("ownerLockEnabled", ownerLockEnabled);
        obj.addProperty("tileSize", tileSize);
        bufferedWriter.write(obj.toString());
    }

    @Override
    protected void create(BufferedWriter bufferedWriter) throws IOException {
        if (getPath().getParent() != null) {
            java.nio.file.Files.createDirectories(getPath().getParent());
        }
        write(bufferedWriter);
    }

    private java.nio.file.Path getPath() {
        return java.nio.file.Path.of("ImageFrames/config.json");
    }

    private void ensureParent() {
        try {
            java.nio.file.Path parent = getPath().getParent();
            if (parent != null) {
                java.nio.file.Files.createDirectories(parent);
            }
        } catch (IOException ignored) {
        }
    }

    public boolean isOwnerLockEnabled() {
        return ownerLockEnabled;
    }

    public void setOwnerLockEnabled(boolean ownerLockEnabled) {
        this.ownerLockEnabled = ownerLockEnabled;
    }

    public int getTileSize() {
        return tileSize;
    }

    public void setTileSize(int tileSize) {
        this.tileSize = Math.max(16, tileSize);
    }
}
