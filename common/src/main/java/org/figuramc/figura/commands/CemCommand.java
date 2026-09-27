package org.figuramc.figura.commands;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.avatar.AvatarManager;
import org.figuramc.figura.avatar.local.LocalAvatarFetcher;
import org.figuramc.figura.avatar.local.LocalAvatarLoader;
import org.figuramc.figura.utils.FiguraClientCommandSource;
import org.figuramc.figura.utils.FiguraText;
import org.figuramc.figura.utils.IOUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * 몹(CEM) 아바타 일괄 빌드 — 로컬 아바타 폴더들을 몹 종류의 CEM 아바타로 한 번에 컴파일한다.
 *
 * <pre>
 *   /figura cem build &lt;폴더&gt;     폴더는 로컬 아바타 폴더 기준이다(/figura load 와 같다)
 * </pre>
 *
 * <p>{@code <폴더>} 에 {@code cem.json} 이 있으면 그 폴더 하나를, 없으면 바로 아래 하위 폴더 중 {@code cem.json} 이 있는 것을
 * 하나씩 빌드한다. {@code cem.json} 은 {@code {"entity": "minecraft:husk"}} — 몹 종류 하나 또는 목록이다.
 *
 * <p>하나마다: 컴파일({@link LocalAvatarLoader#compileAvatarFolder}) → {@code figura/cem_out/<ns>/<type>.nbt} 에 gzip NBT 로 쓴다
 * (리소스팩의 {@code assets/figura/cem/<ns>/<type>.nbt} 와 같은 꼴) → 쓴 파일을 <b>다시 읽어</b> {@code CEM_AVATARS} 에 올리고
 * 그 종류의 로드된 몹 아바타를 내린다(다음 렌더에 새로 만든다). 리소스 리로드(F3+T)를 하면 리소스팩 판으로 돌아간다.
 *
 * <p>리소스팩에는 쓰지 않는다 — 팩으로 옮기는 것은 배포 쪽 일이다(하네스 배포 도구가 외부 변경 가드 · 백업을 갖는다).
 * 컴파일은 로컬 아바타 로드와 같은 백그라운드 큐에서 돈다 — 스크립트 파서가 정적 상태를 가져서 컴파일이 겹치면 안 된다.
 * 결과 반영과 채팅 줄은 메인 스레드에서.
 *
 * <p>2026-09-27 — 엔티티 아바타 템플릿의 변형 여러 벌(변형 하나 = 몹 종류 하나)을 한 번에 만들려고 추가했다.
 */
class CemCommand {

    private static final String CEM_FILE = "cem.json";

    private record Job(Path folder, String name, List<ResourceLocation> types) {}

    private record Line(Component text, boolean error) {}

    public static LiteralArgumentBuilder<FiguraClientCommandSource> getCommand() {
        LiteralArgumentBuilder<FiguraClientCommandSource> cem = LiteralArgumentBuilder.literal("cem");
        LiteralArgumentBuilder<FiguraClientCommandSource> build = LiteralArgumentBuilder.literal("build");

        RequiredArgumentBuilder<FiguraClientCommandSource, String> path = RequiredArgumentBuilder.argument("path", StringArgumentType.greedyString());
        path.executes(CemCommand::build);

        return cem.then(build.then(path));
    }

    private static int build(CommandContext<FiguraClientCommandSource> context) {
        FiguraClientCommandSource source = context.getSource();
        String str = StringArgumentType.getString(context, "path");

        // 빌드 목록 — 작은 cem.json 만 읽으므로 여기서(메인 스레드) 바로 한다. 틀린 설정은 컴파일 전에 멈춘다
        List<Job> jobs;
        try {
            Path root = LocalAvatarFetcher.getLocalAvatarDirectory().resolve(Path.of(str));
            if (!Files.isDirectory(root)) {
                source.figura$sendError(FiguraText.of("command.load.invalid", str));
                return 0;
            }
            jobs = findJobs(root);
        } catch (Exception e) {
            source.figura$sendError(FiguraText.of("command.cem.error", str, String.valueOf(e.getMessage())));
            return 0;
        }

        if (jobs.isEmpty()) {
            source.figura$sendError(FiguraText.of("command.cem.no_jobs", str));
            return 0;
        }

        // 몹 종류 하나에 아바타 하나 — 두 폴더가 같은 종류를 가리키면 뒤엣것이 조용히 덮으므로 막는다
        Map<ResourceLocation, String> claimed = new HashMap<>();
        for (Job job : jobs) {
            for (ResourceLocation type : job.types()) {
                String other = claimed.putIfAbsent(type, job.name());
                if (other != null) {
                    source.figura$sendError(FiguraText.of("command.cem.duplicate", type.toString(), other, job.name()));
                    return 0;
                }
            }
        }

        source.figura$sendFeedback(FiguraText.of("command.cem.building", jobs.size()));

        Minecraft client = source.figura$getClient();
        LocalAvatarLoader.async(() -> {
            Map<ResourceLocation, CompoundTag> built = new LinkedHashMap<>();
            List<Line> lines = new ArrayList<>();
            int ok = 0;

            for (Job job : jobs) {
                try {
                    CompoundTag compiled = LocalAvatarLoader.compileAvatarFolder(job.folder(), Util.NIL_UUID, false);

                    // 리소스팩이 읽을 것과 같게 — 쓴 파일을 다시 읽어 그걸 올린다(읽히지 않는 파일이면 여기서 실패한다)
                    Map<ResourceLocation, CompoundTag> mine = new LinkedHashMap<>();
                    CompoundTag written = null;
                    long bytes = 0;
                    for (ResourceLocation type : job.types()) {
                        Path file = write(type, compiled);
                        if (written == null) {
                            bytes = Files.size(file);
                            written = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
                        }
                        mine.put(type, written);
                    }

                    built.putAll(mine);
                    ok++;
                    lines.add(new Line(FiguraText.of("command.cem.built", job.name(), joinTypes(job.types()), (bytes + 1023) / 1024), false));
                } catch (Throwable e) {
                    FiguraMod.LOGGER.error("Failed to build the CEM avatar from " + job.folder(), e);
                    lines.add(new Line(FiguraText.of("command.cem.failed", job.name(), String.valueOf(e.getMessage())), true));
                }
            }

            int builtJobs = ok;
            int failedJobs = jobs.size() - ok;
            client.execute(() -> {
                LocalAvatarLoader.CEM_AVATARS.putAll(built);
                AvatarManager.clearCEMAvatars(built.keySet());

                for (Line line : lines) {
                    if (line.error())
                        source.figura$sendError(line.text());
                    else
                        source.figura$sendFeedback(line.text());
                }
                source.figura$sendFeedback(FiguraText.of("command.cem.done", builtJobs, failedJobs));
            });
        });

        return jobs.size();
    }

    private static List<Job> findJobs(Path root) throws IOException {
        List<Job> jobs = new ArrayList<>();
        if (Files.isRegularFile(root.resolve(CEM_FILE))) {
            jobs.add(readJob(root));
            return jobs;
        }

        // 바로 아래 하위 폴더만 본다(이름순) — 변형 폴더 안의 폴더까지 뒤지면 뜻하지 않은 것이 걸린다
        List<Path> children = IOUtils.listPaths(root);
        if (children != null) {
            for (Path child : children) {
                if (Files.isDirectory(child) && !IOUtils.isHidden(child) && Files.isRegularFile(child.resolve(CEM_FILE)))
                    jobs.add(readJob(child));
            }
        }
        return jobs;
    }

    private static Job readJob(Path folder) throws IOException {
        String name = IOUtils.getFileNameOrEmpty(folder);
        String where = name + "/" + CEM_FILE;

        List<String> ids = new ArrayList<>();
        try {
            JsonElement entity = JsonParser.parseString(IOUtils.readFile(folder.resolve(CEM_FILE))).getAsJsonObject().get("entity");
            if (entity != null && entity.isJsonArray()) {
                for (JsonElement id : entity.getAsJsonArray())
                    ids.add(id.getAsString());
            } else if (entity != null) {
                ids.add(entity.getAsString());
            }
        } catch (RuntimeException e) {
            throw new IOException(where + ": " + e.getMessage(), e);
        }
        if (ids.isEmpty())
            throw new IOException(where + ": \"entity\" needs an entity type id or a list of them");

        List<ResourceLocation> types = new ArrayList<>();
        for (String id : ids) {
            ResourceLocation type = ResourceLocation.tryParse(id);
            if (type == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(type))
                throw new IOException(where + ": unknown entity type \"" + id + "\"");
            // 리소스팩 로더는 <ns>/<name>.nbt 의 마지막 두 조각만 읽는다 — 경로에 "/" 가 있는 종류는 CEM 파일로 못 적는다
            if (type.getPath().contains("/"))
                throw new IOException(where + ": \"" + id + "\" cannot be a CEM file name");
            if (!types.contains(type))
                types.add(type);
        }
        return new Job(folder, name, types);
    }

    private static Path write(ResourceLocation type, CompoundTag nbt) throws IOException {
        Path dir = FiguraMod.getFiguraDirectory().resolve("cem_out").resolve(type.getNamespace());
        Files.createDirectories(dir);

        // 임시 파일에 다 쓴 뒤 옮긴다 — 반쯤 쓴 파일이 배포 도구에 집히지 않게
        Path file = dir.resolve(type.getPath() + ".nbt");
        Path tmp = dir.resolve(type.getPath() + ".nbt.tmp");
        NbtIo.writeCompressed(nbt, tmp);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        return file;
    }

    private static String joinTypes(List<ResourceLocation> types) {
        List<String> names = new ArrayList<>();
        for (ResourceLocation type : types)
            names.add(type.toString());
        return String.join(", ", names);
    }
}
