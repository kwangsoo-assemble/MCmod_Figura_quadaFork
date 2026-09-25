package org.figuramc.figura.avatar.local;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.avatar.AvatarSource;
import org.figuramc.figura.avatar.UserData;
import org.figuramc.figura.utils.IOUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class CacheAvatarLoader {

    public static void init() {
        LocalAvatarLoader.async(() -> {
            Path file = getAvatarCacheDirectory();
            if (!(Files.exists(file) && Files.isDirectory(file)))
                return;

            List<Path> children = IOUtils.listPaths(file);
            if (children == null)
                return;

            for (Path child : children) {
                try {
                    FileTime time = Files.getLastModifiedTime(child);
                    long diff = System.currentTimeMillis() - time.toMillis();
                    long elapsed = TimeUnit.MILLISECONDS.toDays(diff);
                    if (elapsed > 7) {
                        if (Files.deleteIfExists(child)) {
                            FiguraMod.debug("Successfully deleted cache avatar \"{}\" with \"{}\" days old", IOUtils.getFileNameOrEmpty(child), elapsed);
                        } else {
                            throw new Exception();
                        }
                    }
                } catch (Exception ignored) {
                    FiguraMod.debug("Failed to delete cache avatar \"{}\"", IOUtils.getFileNameOrEmpty(child));
                }
            }
        });
    }

    /**
     * Cache file name, or {@code null} if the name cannot be used as a path or impersonates another namespace.
     *
     * <p>Warning: {@code null} does not mean "reject the avatar"; it means <b>"do not use the cache"</b>.
     * The hash format of the official backend cannot be pinned down, so enforcing a strict format would
     * permanently break the cache for official-only users. This only blocks path escapes and impersonation,
     * and simply skips the cache when a check fails; the download still goes ahead and the avatar loads normally.
     */
    private static String cacheFileName(AvatarSource source, String hash) {
        if (hash == null || hash.isEmpty() || hash.length() > 128) return null;
        for (int i = 0; i < hash.length(); i++) {
            char c = hash.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')
                      || (c >= 'A' && c <= 'Z') || c == '-' || c == '_';
            if (!ok) return null;                // '.' is rejected too -> rules out ".." and extension tricks entirely
        }
        // an official hash must not impersonate the FSB namespace (the prefix already blocks the other direction)
        if (source == AvatarSource.OFFICIAL && hash.startsWith(AvatarSource.FSB.cachePrefix()))
            return null;
        return source.cachePrefix() + hash + ".nbt";
    }

    public static boolean checkAndLoad(AvatarSource source, String hash, UserData target) {
        String name = cacheFileName(source, hash);
        if (name == null) {
            FiguraMod.debug("Skipping cache lookup for unsafe hash \"{}\"", hash);
            return false;
        }

        if (Files.exists(getAvatarCacheDirectory().resolve(name))) {
            load(source, hash, target);
            return true;
        }

        return false;
    }

    public static void load(AvatarSource source, String hash, UserData target) {
        String name = cacheFileName(source, hash);
        if (name == null) return;
        LocalAvatarLoader.async(() -> {
            Path path = getAvatarCacheDirectory().resolve(name);
            try {
                target.loadAvatar(NbtIo.readCompressed(Files.newInputStream(path), NbtAccounter.unlimitedHeap()));
                FiguraMod.debug("Loaded avatar \"{}\" from cache to \"{}\"", hash, target.id);
            } catch (Exception e) {
                FiguraMod.LOGGER.error("Failed to load cache avatar: " + hash, e);
            }
        });
    }

    public static void save(AvatarSource source, String hash, CompoundTag nbt) {
        String name = cacheFileName(source, hash);
        if (name == null) {
            FiguraMod.debug("Refusing to cache avatar under unsafe hash \"{}\"", hash);
            return;
        }
        LocalAvatarLoader.async(() -> {
            Path file = getAvatarCacheDirectory().resolve(name);
            try (java.io.OutputStream out = Files.newOutputStream(file)) {
                NbtIo.writeCompressed(nbt, out);
                FiguraMod.debug("Saved avatar \"{}\" on cache", hash);
            } catch (Exception e) {
                FiguraMod.LOGGER.error("Failed to save avatar on cache: " + hash, e);
            }
        });
    }

    public static void clearCache() {
        LocalAvatarLoader.async(() -> {
            Path file = getAvatarCacheDirectory();

            if (!(Files.exists(file) && Files.isDirectory(file)))
                return;

            List<Path> children = IOUtils.listPaths(file);
            if (children == null)
                return;

            for (Path child : children) {
                try {
                    if (!Files.deleteIfExists(child))
                        throw new Exception();
                } catch (Exception ignored) {
                    FiguraMod.debug("Failed to delete cache avatar \"{}\"", IOUtils.getFileNameOrEmpty(child));
                }
            }

            FiguraMod.debug("Finished clearing avatar cache");
        });
    }

    // cache directory
    public static Path getAvatarCacheDirectory() {
        return IOUtils.getOrCreateDir(FiguraMod.getCacheDirectory(), "avatars");
    }
}
