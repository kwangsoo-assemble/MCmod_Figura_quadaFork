package org.figuramc.figura.avatar;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.ints.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.avatar.local.LocalAvatarLoader;
import org.figuramc.figura.backend2.NetworkStuff;
import org.figuramc.figura.ducks.FiguraEntityRenderStateExtension;
import org.figuramc.figura.gui.FiguraToast;
import org.figuramc.figura.gui.widgets.lists.AvatarList;
import org.figuramc.figura.lua.api.particle.ParticleAPI;
import org.figuramc.figura.lua.api.sound.SoundAPI;
import org.figuramc.figura.lua.api.world.WorldAPI;
import org.figuramc.figura.utils.EntityUtils;
import org.figuramc.figura.utils.FiguraClientCommandSource;
import org.figuramc.figura.utils.FiguraResourceListener;
import org.figuramc.figura.utils.FiguraText;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Manages all the avatars that are currently loaded in memory, and also
 * handles getting the avatars of entities. If an entity does not have a loaded avatar,
 * the AvatarManager will fetch the avatar and cache it.
 */
public class AvatarManager {

    private static final Map<UUID, UserData> LOADED_USERS = new ConcurrentHashMap<>();
    private static final Set<UUID> FETCHED_USERS = new HashSet<>();

    // Note: guards against network overwrites (2026-09-22); see the "pin / netlock" section below
    private static final Map<UUID, Path> PINNED_USERS = new ConcurrentHashMap<>();
    private static volatile boolean networkLocked = false;

    private static final Int2ObjectMap<Avatar> LOADED_CEM = new Int2ObjectOpenHashMap<>();

    public static final FiguraResourceListener RESOURCE_RELOAD_EVENT = FiguraResourceListener.createResourceListener("resource_reload_event", manager -> executeAll("resourceReloadEvent", Avatar::resourceReloadEvent));

    public static boolean localUploaded = true; // init as true :3
    public static boolean panic = false;

    // Added to reduce look up for entities + fixes trident and arrow that no longer exist in world not being rendered while picked up
    public static Int2ObjectMap<Entity> ENTITY_CACHE = new Int2ObjectOpenHashMap<>();

    // -- panic mode -- // 

    public static void togglePanic() {
        AvatarManager.panic = !AvatarManager.panic;
        FiguraToast.sendToast(FiguraText.of(AvatarManager.panic ? "toast.panic_enabled" : "toast.panic_disabled"), FiguraToast.ToastType.WARNING);
        SoundAPI.getSoundEngine().figura$stopAllSounds();
        ParticleAPI.getParticleEngine().figura$clearParticles(null);
    }

    // -- avatar events -- // 

    public static void tickLoadedAvatars() {
        if (panic)
            return;

        // tick the avatars
        for (UserData user : LOADED_USERS.values()) {
            Avatar avatar = user.getMainAvatar();
            if (avatar != null) {
                FiguraMod.pushProfiler(avatar);
                avatar.tick();
                FiguraMod.popProfiler();
            }
        }

        // CEM
        if (LOADED_CEM.isEmpty())
            return;

        // unload entities
        IntSet toBeRemoved = new IntOpenHashSet();

        for (int entityId : LOADED_CEM.keySet()) {
            Entity entity = Minecraft.getInstance().level.getEntity(entityId);
            // The 1.21.8 client drops an entity from the lookup the moment it is removed (ClientLevel.removeEntity -> EntityLookup.remove),
            // so a dead or despawned mob shows up here as null rather than isRemoved(). Treat both as gone and clean() the avatar like
            // clearCEMAvatars does. Previously a null entity only cleared the cache, so its avatar stayed loaded and kept running tick and
            // render events (a leak that grows every time mobs are spawned and removed). A mob that leaves tracking range is also null here;
            // it gets a fresh avatar when it comes back, which costs a reload but beats running forever
            if (entity == null || entity.isRemoved()) {
                toBeRemoved.add(entityId);
                ENTITY_CACHE.remove(entityId);
            }
        }

        for (int entityId : toBeRemoved) {
            Avatar avatar = LOADED_CEM.remove(entityId);
            if (avatar != null)
                avatar.clean();
        }

        // tick entities
        for (Avatar avatar : LOADED_CEM.values()) {
            if (avatar != null) {
                FiguraMod.pushProfiler(avatar);
                avatar.tick();
                FiguraMod.popProfiler();
            }
        }
    }

    /**
     * Whether any loaded avatar is currently requesting part glow.
     *
     * <p>When there is no glowing entity at all, vanilla <b>does not add</b> the {@code entity_outline}
     * post-processing to the frame graph. Drawing into the outline target then produces no outline, and an
     * <b>opaque solid-color silhouette</b> covers the screen instead. This value opens only that gate.
     *
     * <p>Warning: the gate is not opened by touching {@code shouldEntityAppearGlowing}. That would wrap the
     * avatar in {@code OutlineBufferSource}, whose {@code setColor} overwrites vertex colors with the team
     * color, <b>collapsing the per-part colors into one.</b>
     */
    public static boolean anyLoadedAvatarHasPartGlow() {
        if (panic) return false;
        for (UserData user : LOADED_USERS.values()) {
            Avatar avatar = user.getMainAvatar();
            if (avatar != null && avatar.partGlowCount > 0)
                return true;
        }
        return false;
    }

    public static void executeAll(String src, Consumer<Avatar> consumer) {
        if (panic) return;

        FiguraMod.pushProfiler(FiguraMod.MOD_ID);
        FiguraMod.pushProfiler(src);

        for (UserData user : LOADED_USERS.values()) {
            Avatar avatar = user.getMainAvatar();
            if (avatar != null) {
                FiguraMod.pushProfiler(avatar);
                consumer.accept(avatar);
                FiguraMod.popProfiler();
            }
        }

        for (Avatar avatar : LOADED_CEM.values()) {
            if (avatar != null) {
                FiguraMod.pushProfiler(avatar);
                consumer.accept(avatar);
                FiguraMod.popProfiler();
            }
        }

        FiguraMod.popProfiler(2);
    }

    // -- avatar getters -- // 

    // player will also attempt to load from network, if possible
    public static Avatar getAvatarForPlayer(UUID player) {
        if (panic || Minecraft.getInstance().level == null)
            return null;

        fetchBackend(player);

        UserData user = LOADED_USERS.get(player);
        return user == null ? null : user.getMainAvatar();
    }

    private static Avatar getAvatarForEntity(Entity entity) {
        // get loaded
        Avatar loaded = LOADED_CEM.get(entity.getId());
        if (loaded != null)
            return loaded;

        // new avatar
        ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        CompoundTag nbt = LocalAvatarLoader.CEM_AVATARS.get(type);
        return nbt == null ? null : loadEntityAvatar(entity, nbt);
    }

    public static Avatar getAvatar(EntityRenderState state) {
        if (panic || Minecraft.getInstance().level == null || state == null) return null;

        if (state instanceof PlayerRenderState playerRenderState) {
            return getAvatar(Minecraft.getInstance().level.getEntity(playerRenderState.id));
        }
        Integer id = ((FiguraEntityRenderStateExtension)state).figura$getEntityId();
        Entity entity = id != null ? Minecraft.getInstance().level.getEntity(id) : null;
        return getAvatar(entity);
    }

    public static Entity getEntity(EntityRenderState state) {
        if (Minecraft.getInstance().level == null || state == null) return null;

        if (state instanceof PlayerRenderState playerRenderState) {
            return Minecraft.getInstance().level.getEntity(playerRenderState.id);
        }
        Integer id = ((FiguraEntityRenderStateExtension)state).figura$getEntityId();
        return id != null ? Minecraft.getInstance().level.getEntity(id) : null;
    }

    // tries to get data from an entity
    public static Avatar getAvatar(Entity entity) {
        if (panic || Minecraft.getInstance().level == null || entity == null)
            return null;

        UUID uuid = entity.getUUID();

        // load from player (fetch backend) if is a player
        if (entity instanceof Player){
            Avatar avatar = getAvatarForPlayer(uuid);
            if (avatar != null)
                return avatar;
        }

        // otherwise check for CEM
        return getAvatarForEntity(entity);
    }

    // get a loaded avatar without fetching backend or creating a new one
    public static Avatar getLoadedAvatar(UUID owner) {
        if (panic || Minecraft.getInstance().level == null)
            return null;

        UserData user = LOADED_USERS.get(owner);
        return user == null ? null : user.getMainAvatar();
    }

    // get all main loaded avatars
    public static List<Avatar> getLoadedAvatars() {
        List<Avatar> list = new ArrayList<>();
        for (UserData user : LOADED_USERS.values()) {
            Avatar avatar = user.getMainAvatar();
            if (avatar != null && avatar.nbt != null)
                list.add(avatar);
        }
        return list;
    }

    // -- avatar management -- // 

    // removes an loaded avatar
    public static void clearAvatars(UUID id) {
        FETCHED_USERS.remove(id);

        UserData user = LOADED_USERS.get(id);
        if (user != null) user.clear();

        NetworkStuff.clear(id);
        FiguraMod.debug("Cleared avatars of " + id);
    }

    public static void clearCEMAvatars() {
        for (Avatar avatar : LOADED_CEM.values())
            avatar.clean();
        LOADED_CEM.clear();
    }

    // Drops the loaded mob avatars of these entity types only; the next render recreates them from CEM_AVATARS (used by /figura cem build).
    // Ones whose type cannot be told (mob gone, no world) are dropped too: they are cleaned up on the next tick or recreated anyway
    public static void clearCEMAvatars(Collection<ResourceLocation> types) {
        if (LOADED_CEM.isEmpty() || types.isEmpty())
            return;

        ClientLevel level = Minecraft.getInstance().level;
        IntSet toBeRemoved = new IntOpenHashSet();
        for (int entityId : LOADED_CEM.keySet()) {
            Entity entity = level == null ? null : level.getEntity(entityId);
            if (entity == null || types.contains(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())))
                toBeRemoved.add(entityId);
        }

        for (int entityId : toBeRemoved) {
            Avatar avatar = LOADED_CEM.remove(entityId);
            if (avatar != null)
                avatar.clean();
        }
    }

    // Number of CEM avatars running right now, per entity type (/figura cem status). A mob that is gone but not cleaned up yet counts as "?"
    public static Map<String, Integer> countCEMAvatars() {
        Map<String, Integer> counts = new TreeMap<>();
        ClientLevel level = Minecraft.getInstance().level;
        for (int entityId : LOADED_CEM.keySet()) {
            Entity entity = level == null ? null : level.getEntity(entityId);
            String type = entity == null ? "?" : BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            counts.merge(type, 1, Integer::sum);
        }
        return counts;
    }

    // clears ALL loaded avatars, including local
    public static void clearAllAvatars() {
        for (UUID id : LOADED_USERS.keySet())
            clearAvatars(id);

        LOADED_USERS.clear();
        FETCHED_USERS.clear();
        ENTITY_CACHE.clear();
        clearCEMAvatars();

        localUploaded = true;
        AvatarList.selectedEntry = null;
        LocalAvatarLoader.loadAvatar(null, null);
        FiguraMod.LOGGER.info("Cleared all avatars");
    }

    // reloads an avatar
    public static void reloadAvatar(UUID id) {
        Path pinned = PINNED_USERS.get(id);
        if (pinned != null)
            loadLocalAvatarFor(id, pinned); // pinned users recompile their local path (same idea as the host's !localUploaded)
        else if (!localUploaded && FiguraMod.isLocal(id))
            loadLocalAvatar(LocalAvatarLoader.getLastLoadedPath());
        else
            clearAvatars(id);
    }

    // -- pin / netlock -- //
    //
    // Problem (2026-09-22): after `/figura nonhost_load` put a local avatar on another player, the cloud avatar
    // overwrote it a few frames later.
    //   (1) clearAvatars() erases the FETCHED_USERS mark and there was no public API to restore it, so
    //       getAvatarForPlayer() -> fetchBackend() on the next render hit the backend again. (The host's
    //       loadLocalAvatar was fine because it calls FETCHED_USERS.add right after clear; the same order had
    //       to be applied to other players.)
    //   (2) After that, the websocket event (reloadAvatar), FSB S2CConnected/S2CNotify (clearAvatars) and async
    //       responses (UserData.loadData / loadAvatar) could still overwrite it at any time. Replays (Flashback)
    //       re-send the recorded FSB packets on every playback and seek, so (2) keeps repeating.
    // Solution: two layers of switches. Every network entry point checks acceptsNetworkAvatar().
    //   pin      Per user, set by loadLocalAvatarFor(). The network cannot touch that user, and whatever
    //            clears the avatar, fetchBackend() restores it from the pinned path.
    //   netlock  Global, /figura netlock. No user's avatar is newly fetched from the network (official cloud,
    //            FSB). Already loaded avatars stay. Local loads (/figura load, nonhost_load) are unaffected.
    //   Both are released when leaving the world (MinecraftMixin.clearLevel -> resetPinsAndLock).
    //   Warning: clearAllAvatars() does not release them. It is an in-world "reload everything", so pinned
    //     avatars must come back and the lock must stay. The FSB handshake, the permissions screen and
    //     /figura reload all take that path.

    /** May the network (official cloud, FSB) apply or clear this user's avatar? Every network entry point checks this. */
    public static boolean acceptsNetworkAvatar(UUID id) {
        return !networkLocked && !PINNED_USERS.containsKey(id);
    }

    public static boolean isPinned(UUID id) {
        return PINNED_USERS.containsKey(id);
    }

    public static Path getPinnedPath(UUID id) {
        return PINNED_USERS.get(id);
    }

    public static Map<UUID, Path> getPinnedAvatars() {
        return Collections.unmodifiableMap(PINNED_USERS);
    }

    public static boolean isNetworkLocked() {
        return networkLocked;
    }

    public static void setNetworkLocked(boolean locked) {
        networkLocked = locked;
        FiguraMod.LOGGER.info("Avatar network lock " + (locked ? "enabled" : "disabled"));
    }

    /**
     * Puts a local avatar folder on an arbitrary player and pins it. For the local player this is the same as
     * {@link #loadLocalAvatar}.
     * Client-only: nothing is sent to the server or to other clients.
     */
    public static void loadLocalAvatarFor(UUID id, Path path) {
        if (FiguraMod.isLocal(id)) {
            loadLocalAvatar(path);
            return;
        }

        PINNED_USERS.put(id, path);

        // Restore the mark right after clear, in the same order as loadLocalAvatar.
        // The lack of this one line is what caused the overwrite.
        clearAvatars(id);
        FETCHED_USERS.add(id);

        UserData user = LOADED_USERS.computeIfAbsent(id, UserData::new);
        LocalAvatarLoader.loadAvatar(path, user); // for a non-host, the loader leaves the hot-reload watcher alone
    }

    /**
     * Unpins the user. Unless netlock is on, the network avatar is fetched again on the next render.
     * @return whether the user was pinned
     */
    public static boolean unpinAvatar(UUID id) {
        if (PINNED_USERS.remove(id) == null)
            return false;
        clearAvatars(id);
        return true;
    }

    public static int unpinAll() {
        List<UUID> ids = new ArrayList<>(PINNED_USERS.keySet());
        for (UUID id : ids)
            unpinAvatar(id);
        return ids.size();
    }

    /** Called when leaving the world. Drops all session-only state (pins, netlock). */
    public static void resetPinsAndLock() {
        if (!PINNED_USERS.isEmpty() || networkLocked)
            FiguraMod.LOGGER.info("Releasing " + PINNED_USERS.size() + " pinned avatar(s)" + (networkLocked ? " and the network lock" : ""));
        PINNED_USERS.clear();
        networkLocked = false;
    }

    /** Network-requested clear; ignored if pinned or netlocked. Network handlers call this instead of clearAvatars. */
    public static void clearAvatarsFromNetwork(UUID id) {
        if (!acceptsNetworkAvatar(id)) {
            FiguraMod.debug("Ignoring network clear for " + id + " (pinned or network locked)");
            return;
        }
        clearAvatars(id);
    }

    /** Network-requested reload; ignored if pinned or netlocked. */
    public static void reloadAvatarFromNetwork(UUID id) {
        if (!acceptsNetworkAvatar(id)) {
            FiguraMod.debug("Ignoring network reload for " + id + " (pinned or network locked)");
            return;
        }
        reloadAvatar(id);
    }

    // load the local player avatar
    public static void loadLocalAvatar(Path path) {
        UUID id = FiguraMod.getLocalPlayerUUID();

        // clear
        clearAvatars(id);
        FETCHED_USERS.add(id);

        // load
        UserData user = LOADED_USERS.computeIfAbsent(id, UserData::new);
        LocalAvatarLoader.loadAvatar(path, user);

        // mark as not uploaded
        localUploaded = false;
    }

    // load CEM avatar
    public static Avatar loadEntityAvatar(Entity entity, CompoundTag nbt) {
        Avatar targetAvatar = new Avatar(entity);
        targetAvatar.load(nbt);
        LOADED_CEM.put(entity.getId(), targetAvatar);
        AvatarManager.ENTITY_CACHE.putIfAbsent(entity.getId(), entity);
        return targetAvatar;
    }

    // load CEM avatar
    public static Avatar loadEntityAvatar(EntityRenderState entity, CompoundTag nbt) {
        Avatar targetAvatar = new Avatar(entity);
        targetAvatar.load(nbt);
        Integer id = entity instanceof PlayerRenderState playerRenderState ? playerRenderState.id : ((FiguraEntityRenderStateExtension)entity).figura$getEntityId();
        LOADED_CEM.put(id, targetAvatar);
        AvatarManager.ENTITY_CACHE.putIfAbsent(id, WorldAPI.getCurrentWorld().getEntity(id));
        return targetAvatar;
    }

    // set an user's avatar
    public static void setAvatar(UUID id, CompoundTag nbt) {
        try {
            UserData user = LOADED_USERS.computeIfAbsent(id, UserData::new);
            clearAvatars(id);
            FETCHED_USERS.add(id); // same bug as nonhost_load: without the mark, the next render refetches from the backend
            user.loadAvatar(nbt);
        } catch (Exception e) {
            FiguraMod.LOGGER.error("Failed to set avatar for " + id, e);
        }
    }

    // get avatar from the backend
    private static void fetchBackend(UUID id) {
        if (FETCHED_USERS.contains(id))
            return;

        FETCHED_USERS.add(id);

        // Pinned users are restored from their pinned path instead of the network, whatever cleared them
        // (reload nonhost, clearAllAvatars, ...)
        Path pinned = PINNED_USERS.get(id);
        if (pinned != null) {
            FiguraMod.debug("Restoring pinned avatar for " + id);
            UserData user = LOADED_USERS.computeIfAbsent(id, UserData::new);
            LocalAvatarLoader.loadAvatar(pinned, user);
            return;
        }

        if (networkLocked) {
            FiguraMod.debug("Network lock: not fetching userdata for " + id);
            return;
        }

        if (EntityUtils.checkInvalidPlayer(id)) {
            FiguraMod.debug("Voiding userdata for " + id);
            return;
        }

        UserData user = LOADED_USERS.computeIfAbsent(id, UserData::new);

        FiguraMod.debug("Getting userdata for " + id);
        NetworkStuff.getUser(user);
    }

    // -- badges -- // 

    public static Pair<BitSet, BitSet> getBadges(UUID id) {
        UserData user = LOADED_USERS.get(id);
        if (user == null)
            return null;

        Pair<BitSet, BitSet> badges = user.getBadges();
        if (badges != null)
            return badges;

        badges = Badges.emptyBadges();
        user.loadBadges(badges);
        return badges;
    }

    // -- command -- // 

    public static LiteralArgumentBuilder<FiguraClientCommandSource> getCommand() {
        // root
        LiteralArgumentBuilder<FiguraClientCommandSource> root = LiteralArgumentBuilder.literal("set_avatar");

        // source
        RequiredArgumentBuilder<FiguraClientCommandSource, String> target = RequiredArgumentBuilder.argument("target", StringArgumentType.word());

        // target
        RequiredArgumentBuilder<FiguraClientCommandSource, String> source = RequiredArgumentBuilder.argument("source", StringArgumentType.word());
        source.executes(context -> {
            String s = StringArgumentType.getString(context, "source");
            String t = StringArgumentType.getString(context, "target");

            UUID sourceUUID, targetUUID;
            try {
                sourceUUID = UUID.fromString(s);
                targetUUID = UUID.fromString(t);
            } catch (Exception e) {
                context.getSource().figura$sendError(Component.literal("Failed to parse uuids"));
                return 0;
            }

            UserData user = LOADED_USERS.get(sourceUUID);
            Avatar avatar = user == null ? null : user.getMainAvatar();
            if (avatar == null || avatar.nbt == null) {
                context.getSource().figura$sendError(Component.literal("No source Avatar found"));
                return 0;
            }

            if (LOADED_USERS.get(targetUUID) != null) {
                if (FiguraMod.isLocal(targetUUID)) {
                    context.getSource().figura$sendError(Component.literal("Cannot set your own avatar this way; use '/figura load' instead"));
                    return 0;
                }
                setAvatar(targetUUID, avatar.nbt);
                context.getSource().figura$sendFeedback(Component.literal("Set avatar for " + t));
                return 1;
            }

            Entity targetEntity = EntityUtils.getEntityByUUID(targetUUID);
            if (targetEntity == null) {
                context.getSource().figura$sendError(Component.literal("Target entity not found"));
                return 0;
            }

            loadEntityAvatar(targetEntity, avatar.nbt);
            return 1;
        });
        target.then(source);

        // build root
        root.then(target);
        return root;
    }
}
