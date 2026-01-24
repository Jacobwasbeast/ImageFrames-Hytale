package dev.jacobwasbeast.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import javax.imageio.ImageIO;

public class ImageFrameImageCache {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type INDEX_TYPE = new TypeToken<Map<String, CacheEntry>>() {}.getType();

    private final Path cacheDir;
    private final Path indexPath;
    private final Map<String, CacheEntry> index = new HashMap<>();
    private final Map<String, BufferedImage> memoryCache = new HashMap<>();

    public ImageFrameImageCache(Path baseDir) {
        this.cacheDir = baseDir.resolve("images");
        this.indexPath = baseDir.resolve("image_cache.json");
        loadIndex();
    }

    public synchronized BufferedImage loadOrDownload(String url, Supplier<BufferedImage> downloader) throws IOException {
        if (url == null || url.isEmpty()) {
            throw new IOException("URL is empty");
        }
        BufferedImage cached = memoryCache.get(url);
        if (cached != null) {
            return cached;
        }
        CacheEntry entry = index.get(url);
        if (entry != null && entry.fileName != null) {
            Path path = cacheDir.resolve(entry.fileName);
            if (Files.exists(path)) {
                BufferedImage img = ImageIO.read(path.toFile());
                if (img != null) {
                    memoryCache.put(url, img);
                    return img;
                }
            }
        }
        BufferedImage downloaded = downloader.get();
        if (downloaded == null) {
            throw new IOException("Failed to download image");
        }
        store(url, downloaded);
        return downloaded;
    }

    public synchronized void store(String url, BufferedImage image) throws IOException {
        ensureCacheDir();
        String fileName = fileNameForUrl(url);
        Path path = cacheDir.resolve(fileName);
        ImageIO.write(image, "png", path.toFile());
        CacheEntry entry = new CacheEntry();
        entry.fileName = fileName;
        index.put(url, entry);
        saveIndex();
        memoryCache.put(url, image);
    }

    private void loadIndex() {
        if (!Files.exists(indexPath)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(indexPath)) {
            Map<String, CacheEntry> loaded = GSON.fromJson(reader, INDEX_TYPE);
            if (loaded != null) {
                index.clear();
                index.putAll(loaded);
            }
        } catch (IOException ignored) {
        }
    }

    private void saveIndex() throws IOException {
        ensureCacheDir();
        if (indexPath.getParent() != null) {
            Files.createDirectories(indexPath.getParent());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(indexPath)) {
            writer.write(GSON.toJson(index));
        }
    }

    private void ensureCacheDir() throws IOException {
        Files.createDirectories(cacheDir);
    }

    private String fileNameForUrl(String url) {
        return sha256(url) + ".png";
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static class CacheEntry {
        String fileName;
    }
}
