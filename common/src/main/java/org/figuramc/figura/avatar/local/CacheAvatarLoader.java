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
     * 캐시 파일명. 경로로 쓸 수 없거나 남의 네임스페이스를 사칭하는 이름이면 {@code null}.
     *
     * <p>⚠ {@code null} 은 "아바타를 거부한다" 가 아니라 <b>"캐시를 쓰지 않는다"</b> 는 뜻이다.
     * 공식 백엔드가 어떤 형식의 해시를 주는지 확정할 수 없으므로, 형식을 엄격히 강제하면
     * 공식 전용 사용자의 캐시가 영구히 죽는다. 여기서는 경로 탈출과 사칭만 막고
     * 판정에 걸리면 그냥 캐시를 건너뛴다 — 다운로드는 그대로 진행되어 아바타는 정상으로 뜬다.
     */
    private static String cacheFileName(AvatarSource source, String hash) {
        if (hash == null || hash.isEmpty() || hash.length() > 128) return null;
        for (int i = 0; i < hash.length(); i++) {
            char c = hash.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')
                      || (c >= 'A' && c <= 'Z') || c == '-' || c == '_';
            if (!ok) return null;                // '.' 도 불허 → ".." 와 확장자 조작을 원천 차단
        }
        // 공식 해시가 FSB 네임스페이스를 사칭할 수 없게 한다 (반대 방향은 접두어가 이미 막는다)
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
