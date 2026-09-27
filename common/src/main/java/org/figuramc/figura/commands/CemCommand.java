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
 * Batch build of mob (CEM) avatars: compiles local avatar folders into CEM avatars for entity types in one go.
 *
 * <pre>
 *   /figura cem build &lt;folder&gt;     the folder is relative to the local avatar folder (same as /figura load)
 * </pre>
 *
 * <p>If {@code <folder>} has a {@code cem.json}, that folder is built; otherwise every direct subfolder that has one.
 * {@code cem.json} is {@code {"entity": "minecraft:husk"}}: one entity type or a list of them.
 *
 * <p>For each: compile ({@link LocalAvatarLoader#compileAvatarFolder}) → write gzip NBT to {@code figura/cem_out/<ns>/<type>.nbt}
 * (the same layout as a resource pack's {@code assets/figura/cem/<ns>/<type>.nbt}) → <b>read that file back</b> into
 * {@code CEM_AVATARS} and drop the loaded mob avatars of that type (they are recreated on the next render).
 * A resource reload (F3+T) goes back to the resource pack version.
 *
 * <p>Nothing is written to a resource pack: moving the files into one is left to your deploy step.
 * Compiling runs on the same background queue as local avatar loads, because the script parser keeps static state and
 * compiles must not overlap. Applying the result and the chat lines happen on the main thread.
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

        // the job list only reads the small cem.json files, so it runs here (main thread) and bad settings stop before compiling
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

        // one avatar per entity type: if two folders name the same type, the later one would silently win
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

                    // same as what the resource pack will load: read the written file back and use that (an unreadable file fails here)
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

        // direct subfolders only (by name): searching inside a variant folder could pick up something unintended
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
            // the resource pack loader reads only the last two parts of <ns>/<name>.nbt, so a type path with "/" cannot be a CEM file
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

        // write to a temp file and then move it, so a half-written file is never picked up
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
