package org.figuramc.figura.avatar;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.ints.*;
import net.minecraft.client.Minecraft;
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

    // ★ 네트워크 덮어쓰기 방지 (2026-09-22) — 「pin / netlock」 절 참고
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
            // 1.21.8 클라는 엔티티를 지우는 순간 조회 목록에서도 뺀다(ClientLevel.removeEntity → EntityLookup.remove) —
            // 죽거나 사라진 몹은 여기서 isRemoved() 가 아니라 null 로 보인다. 둘 다 «사라짐» 으로 보고 clearCEMAvatars 처럼 clean() 까지 부른다.
            // 전에는 null 이면 캐시만 지워서 아바타가 남아 틱 · 렌더 이벤트를 계속 돌았다(누수 — 라운드마다 스폰하면 쌓인다, 2026-09-27).
            // 추적 범위 밖으로 나간 몹도 null 이라 지워지고, 돌아오면 새로 만든다(로드 비용이 다시 든다 — 남아서 도는 것보다 낫다)
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
     * 로드된 아바타 중 하나라도 파츠 발광을 요청 중인가.
     *
     * <p>바닐라는 발광 엔티티가 하나도 없으면 {@code entity_outline} 후처리를 프레임그래프에
     * <b>넣지 않는다.</b> 그래서 아웃라인 타겟에 그려도 테두리가 되지 않고
     * <b>불투명 단색 실루엣</b>이 화면을 덮는다. 이 값으로 그 게이트만 연다.
     *
     * <p>⚠ {@code shouldEntityAppearGlowing} 을 건드려서 여는 것이 아니다 — 그러면 아바타가
     * {@code OutlineBufferSource} 로 감싸이고, 그 안의 {@code setColor} 가 정점 색을 팀 색으로
     * 덮어써 <b>파츠별 색이 하나로 뭉개진다.</b>
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
            loadLocalAvatarFor(id, pinned); // pin 된 사용자는 그 로컬 경로를 다시 컴파일한다 (호스트의 !localUploaded 와 같은 뜻)
        else if (!localUploaded && FiguraMod.isLocal(id))
            loadLocalAvatar(LocalAvatarLoader.getLastLoadedPath());
        else
            clearAvatars(id);
    }

    // -- pin / netlock -- //
    //
    // 문제 (2026-09-22): `/figura nonhost_load` 로 남에게 로컬 아바타를 입혀도 몇 프레임 뒤 클라우드 아바타가 덮어썼다.
    //   ① clearAvatars() 가 FETCHED_USERS 표식을 지우는데 그것을 되돌려 놓는 공개 API 가 없어서
    //      다음 렌더의 getAvatarForPlayer() → fetchBackend() 가 백엔드를 다시 쳤다. (호스트의 loadLocalAvatar 는
    //      clear 직후 FETCHED_USERS.add 를 하므로 멀쩡했다 — 같은 순서를 남에게도 적용해야 했다)
    //   ② 그 뒤로도 웹소켓 event(reloadAvatar) · FSB S2CConnected/S2CNotify(clearAvatars) · 비동기 응답
    //      (UserData.loadData / loadAvatar) 이 언제든 다시 덮는다. 리플레이(Flashback)는 녹화된 FSB 패킷을
    //      재생·탐색 때마다 다시 흘려보내므로 ②가 반복된다.
    // 해법 — 두 겹의 스위치. 네트워크 진입점은 전부 acceptsNetworkAvatar() 를 본다.
    //   pin      사용자별. loadLocalAvatarFor() 가 건다. 네트워크가 그 사용자를 못 건드리고,
    //            무엇이 아바타를 지워도 fetchBackend() 가 pin 된 경로에서 되살린다.
    //   netlock  전역. /figura netlock. 아무 사용자의 아바타도 네트워크(공식 클라우드·FSB)에서 새로 받지 않는다.
    //            이미 로드된 것은 그대로 남는다. 로컬 로드(/figura load · nonhost_load)는 영향 없다.
    //   둘 다 월드를 나갈 때 풀린다 (MinecraftMixin.clearLevel → resetPinsAndLock).
    //   ⚠ clearAllAvatars() 는 풀지 않는다 — 그건 in-world «전부 다시 불러오기» 라서 pin 된 것은 되살아나고
    //     잠금은 유지돼야 한다. FSB 핸드셰이크·권한 화면·/figura reload all 이 그 경로다.

    /** 네트워크(공식 클라우드·FSB)가 이 사용자의 아바타를 적용하거나 지워도 되는가. 모든 네트워크 진입점이 이걸 본다. */
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
     * 로컬 아바타 폴더를 임의 플레이어에게 입히고 pin 한다. 로컬 플레이어면 {@link #loadLocalAvatar} 와 같다.
     * 클라이언트 한정 — 서버·타 클라이언트에는 아무것도 보내지 않는다.
     */
    public static void loadLocalAvatarFor(UUID id, Path path) {
        if (FiguraMod.isLocal(id)) {
            loadLocalAvatar(path);
            return;
        }

        PINNED_USERS.put(id, path);

        // clear 직후 표식을 되돌려 놓는다 — loadLocalAvatar 와 같은 순서. 이 한 줄이 없어서 덮어썼다.
        clearAvatars(id);
        FETCHED_USERS.add(id);

        UserData user = LOADED_USERS.computeIfAbsent(id, UserData::new);
        LocalAvatarLoader.loadAvatar(path, user); // 비호스트면 로더가 핫리로드 감시를 건드리지 않는다
    }

    /**
     * pin 해제. netlock 이 아니면 다음 렌더에서 네트워크 아바타를 다시 받는다.
     * @return pin 돼 있었는가
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

    /** 월드를 나갈 때. 이 세션 한정 상태(pin·netlock)를 모두 버린다. */
    public static void resetPinsAndLock() {
        if (!PINNED_USERS.isEmpty() || networkLocked)
            FiguraMod.LOGGER.info("Releasing " + PINNED_USERS.size() + " pinned avatar(s)" + (networkLocked ? " and the network lock" : ""));
        PINNED_USERS.clear();
        networkLocked = false;
    }

    /** 네트워크가 요청한 삭제 — pin·netlock 이면 무시한다. 네트워크 핸들러는 clearAvatars 대신 이걸 부른다. */
    public static void clearAvatarsFromNetwork(UUID id) {
        if (!acceptsNetworkAvatar(id)) {
            FiguraMod.debug("Ignoring network clear for " + id + " (pinned or network locked)");
            return;
        }
        clearAvatars(id);
    }

    /** 네트워크가 요청한 리로드 — pin·netlock 이면 무시한다. */
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
            FETCHED_USERS.add(id); // nonhost_load 와 같은 결함이었다 — 표식을 안 되돌리면 다음 렌더가 백엔드로 덮는다
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

        // pin 된 사용자는 네트워크 대신 pin 된 경로에서 되살린다 — 무엇이 지웠든 (reload nonhost · clearAllAvatars · …)
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
