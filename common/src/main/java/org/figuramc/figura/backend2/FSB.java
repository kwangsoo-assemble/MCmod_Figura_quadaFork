package org.figuramc.figura.backend2;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.avatar.AvatarManager;
import org.figuramc.figura.avatar.AvatarSource;
import org.figuramc.figura.avatar.UserData;
import org.figuramc.figura.avatar.local.CacheAvatarLoader;
import org.figuramc.figura.compat.FlashbackCompat;
import org.figuramc.figura.ducks.ServerDataAccessor;
import org.figuramc.figura.gui.FiguraToast;
import org.figuramc.figura.server.avatars.EHashPair;
import org.figuramc.figura.server.packets.AvatarDataPacket;
import org.figuramc.figura.server.packets.CloseIncomingStreamPacket;
import org.figuramc.figura.server.packets.Packet;
import org.figuramc.figura.server.packets.c2s.*;
import org.figuramc.figura.server.packets.s2c.*;
import org.figuramc.figura.server.utils.Hash;
import com.mojang.datafixers.util.Pair;
import org.figuramc.figura.server.utils.StatusCode;
import org.figuramc.figura.server.utils.Utils;
import org.figuramc.figura.utils.FiguraText;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class FSB {
    private static FSB instance;
    private byte[] key;
    private S2CBackendHandshakePacket s2CHandshake;
    private State state = State.Uninitialized;
    private final HashMap<Integer, UserdataHandler> awaitingUserdata = new HashMap<>();
    private final HashMap<Integer, AvatarOutputStream> outputStreams = new HashMap<>();
    private int nextTransactionId;
    private final HashMap<Integer, AvatarInputStream> inputStreams = new HashMap<>();

    private final HashSet<UUID> connectedPlayers = new HashSet<>();
    private final ConcurrentLinkedDeque<S2CAvatarReadyPacket> uploadCompletedAvatars = new ConcurrentLinkedDeque<>();
    private final AtomicBoolean hasDeletedAvatar = new AtomicBoolean(false);

    private int handshakeTick = 0;
    private int handshakeAttempts = 0;
    private static final int HANDSHAKE_SEND_DELAY = 40;
    private static final int MAX_ATTEMPTS_TO_CONNECT = 10;

    protected FSB() {
        if (instance != null) throw new IllegalStateException("Unable to create more than one FSB instance");
        instance = this;
    }

    public static FSB instance() {
        return instance;
    }

    public int getNextId() {
        int id = nextTransactionId;
        nextTransactionId++;
        return id;
    }

    public State state() {
        return state;
    }

    public boolean connected() {
        return s2CHandshake != null && state == State.Connected;
    }

    public S2CBackendHandshakePacket handshake() {
        return s2CHandshake;
    }

    private static boolean fsbAllowed() {
        ServerDataAccessor data = (ServerDataAccessor) Minecraft.getInstance().getCurrentServer();
        return data != null && data.figura$allowFigura();
    }

    /**
     * Accepts a handshake both as the answer to our own request ({@link State#HandshakeSent})
     * and as an unsolicited one while already {@link State#Connected}.
     * <p>
     * The second case is the server telling us it lost our session — it happens when the FSB
     * plugin is disabled and re-enabled by {@code /reload}, which wipes the server side user map.
     * We would otherwise stay {@code Connected} forever: {@link #tick} only retries while
     * disconnected, so nothing would ever re-handshake and the link stayed dead until the player
     * rejoined (rejoining works because {@link #onDisconnect} is the only path back to
     * {@code Uninitialized}).
     * <p>
     * Treated as a fresh session: the player list is replaced rather than merged, so entries for
     * players the server no longer reports do not linger.
     */
    public void handleHandshake(S2CBackendHandshakePacket packet) {
        if (fsbAllowed() && (state == State.HandshakeSent || state == State.Connected)) {
            boolean resync = state == State.Connected;
            s2CHandshake = packet;
            state = State.Connected;
            handshakeAttempts = 0;
            handshakeTick = 0;
            if (!resync) FiguraToast.sendToast(FiguraText.of("backend.fsb_connected"));
            connectedPlayers.clear();
            connectedPlayers.addAll(packet.connectedPlayers());
            AvatarManager.clearAllAvatars();
            // server_data — tell the server we are ready (HELLO). Also on a re-handshake (server lost the session) — it answers with RESET + everything
            org.figuramc.figura.serverdata.ServerDataStore.sendHello();
        }
    }

    public void handleConnectionRefusal() {
        state = State.Refused;
    }

    public void getUser(UUID user, UserdataHandler handler) {
        int id = getNextId();
        awaitingUserdata.put(id, handler);
        sendPacket(new C2SFetchUserdataPacket(id, user));
    }

    public void getUserAndApply(UserData userData) {
        getUser(userData.id, new UserdataApplier(userData));
    }

    public void uploadAvatar(String avatarId, byte[] avatarData) {
        int id = getNextId();
        outputStreams.put(id, new AvatarOutputStream(this, avatarId, id, avatarData));
        Hash hash = Utils.getHash(avatarData);
        Hash ehash = getEHash(hash);
        sendPacket(new C2SUploadAvatarPacket(id, avatarId, hash, ehash));
    }

    public void deleteAvatar(String avatarId) {
        sendPacket(new C2SDeleteAvatarPacket(avatarId));
    }

    public void equipAvatar(List<Pair<String, Hash>> avatars) {
        HashMap<String, EHashPair> eHashPairs = new HashMap<>();
        for (Pair<String, Hash> pair: avatars) {
            eHashPairs.put(pair.getFirst(), new EHashPair(pair.getSecond(), getEHash(pair.getSecond())));
        }
        sendPacket(new C2SEquipAvatarsPacket(eHashPairs));
    }

    public void onDisconnect() {
        s2CHandshake = null;
        state = State.Uninitialized;
        handshakeTick = 0;
        handshakeAttempts = 0;
        inputStreams.clear();
        outputStreams.clear();
        connectedPlayers.clear();
        nextTransactionId = 0;
        org.figuramc.figura.serverdata.ServerDataStore.clear();
    }

    public void tick() {
        if (!fsbAllowed()) return;
        if (!connected()) {
            if (state != State.Refused && handshakeAttempts < MAX_ATTEMPTS_TO_CONNECT) {
                handshakeTick++;
                if (handshakeTick == HANDSHAKE_SEND_DELAY) {
                    sendPacket(new C2SBackendHandshakePacket());
                    state = State.HandshakeSent;
                    handshakeTick = 0;
                    handshakeAttempts++;
                }
            }
        }
        else outputStreams.forEach((i, s) -> s.tick());
    }

    public void getAvatar(UserData target, String hash) {
        // Note: do not remove the replay clause. ConnectedPacketHandler bypasses the connected() gate during
        //    playback, so this is reached without a connection. A bare connected() guard here would
        //    **drop the receive registration of the recorded avatar stream**, and avatars would not show in replays.
        if (!connected() && !FlashbackCompat.isInReplay()) {
            FiguraMod.debug("Dropping FSB avatar request for {}: FSB not connected", target.id);
            return;
        }

        Hash h;
        try {
            h = Utils.parseHash(hash);
        }
        catch (RuntimeException e) {
            // contain it so it does not blow up the main-thread packet handler (Hash requires exactly 32 bytes)
            FiguraMod.LOGGER.error("Malformed FSB avatar hash \"" + hash + "\"", e);
            return;
        }

        int streamId = nextTransactionId++;
        inputStreams.put(streamId, new AvatarInputStream(this, streamId, h, getEHash(h), target));
        sendPacket(new C2SFetchAvatarPacket(streamId, h));
    }

    public void handleUserdata(S2CUserdataPacket packet) {
        int id = packet.responseId();
        UserdataHandler handler = awaitingUserdata.get(id);
        if (handler != null) handler.handle(packet);
        awaitingUserdata.remove(id);
    }

    public void handleUserdataNotFound(S2CUserdataNotFoundPacket packet) {
        int id = packet.transactionId();
        UserdataHandler handler = awaitingUserdata.get(id);
        if (handler != null) handler.userdataNotFound();
        awaitingUserdata.remove(id);
    }

    public void handleUserConnected(S2CConnectedPacket packet) {
        connectedPlayers.add(packet.player());
        AvatarManager.clearAvatarsFromNetwork(packet.player()); // ignored if pinned/netlocked; replays resend this on every playback
    }

    public void handleAvatarReady(S2CAvatarReadyPacket packet) {
        uploadCompletedAvatars.add(packet);
    }

    public @Nullable S2CAvatarReadyPacket nextReadyAvatar() {
        return uploadCompletedAvatars.poll();
    }

    public void handleAvatarDeleted() {
        hasDeletedAvatar.set(true);
    }

    public boolean pollAvatarDeleted() {
        return hasDeletedAvatar.getAndSet(false);
    }

    public void reset(UUID id) {
        connectedPlayers.remove(id);
    }

    public boolean isPlayerConnected(UUID id) {
        return connectedPlayers.contains(id);
    }

    public void handleAvatarData(int streamId, byte[] chunk, boolean finalChunk) {
        var inputStream = inputStreams.get(streamId);
        if (inputStream == null) {
            sendPacket(new CloseIncomingStreamPacket(streamId, StatusCode.INVALID_STREAM_ID));
            return;
        }
        inputStream.acceptDataChunk(chunk, finalChunk);
    }

    public void handleAllow(int stream) {
        var outputStream = outputStreams.get(stream);
        if (outputStream != null) {
            outputStream.allow();
        }
    }

    public void closeIncomingStream(int streamId, StatusCode code) {
        var inputStream = inputStreams.get(streamId);
        if (inputStream != null) {
            inputStream.close(code);
        }
    }

    public void closeOutcomingStreamPacket(int streamId, StatusCode code) {
        var outputStream = outputStreams.get(streamId);
        if (outputStream != null) {
            outputStream.close(code);
        }
    }

    public void handlePing(S2CPingPacket packet) {
        Avatar avatar = AvatarManager.getLoadedAvatar(packet.sender());
        if (avatar == null)
            return;
        avatar.runPing(packet.id(), packet.data());
    }

    void applyUserData(UserData user, S2CUserdataPacket packet) {
        boolean isHost = FiguraMod.isLocal(user.id);
        ArrayList<Pair<String, Pair<String, UUID>>> list = new ArrayList<>();
        org.figuramc.figura.server.utils.Pair<String, EHashPair> avatar = packet.avatar();
        if (avatar != null) {
            EHashPair hashPair = avatar.right();
            if (!isHost || FSB.instance.getEHash(hashPair.hash()).equals(hashPair.ehash())) {
                list.add(new Pair<>(hashPair.hash().toString(), new Pair<>(avatar.left(), user.id)));
            }
        }
        // Note: the origin is passed as an argument. This used to set user.fromFSB(true) **after** this line,
        //   but loadData already starts downloading right here, so the first request leaked to the official cloud.
        //   There is no order-dependent state anymore, so the result is the same whatever runs in between.
        user.loadData(list, new Pair<>(packet.prideBadges(), new BitSet()), AvatarSource.FSB);
    }

    public abstract void sendPacket(Packet packet);

    public Hash getEHash(Hash hash) {
        byte[] hashBytes = hash.get();
        byte[] key = getKey();
        byte[] ehashBytes = new byte[hashBytes.length + key.length];
        System.arraycopy(hashBytes, 0, ehashBytes, 0, hashBytes.length);
        System.arraycopy(key, 0, ehashBytes, hashBytes.length, key.length);
        return Utils.getHash(ehashBytes);
    }

    private static File keyFile() {
        return FiguraMod.getFiguraDirectory().resolve(".fsbkey").toFile();
    }

    public byte[] getKey() {
        if (key == null) {
            var f = keyFile();
            if (f.exists()) {
                try (FileInputStream fis = new FileInputStream(f)) {
                    key = fis.readAllBytes();
                }
                catch (IOException e) {
                    FiguraMod.LOGGER.error("Error occured while getting a key for FSB: ", e);
                    key = new byte[16];;
                }
            }
            else {
                regenerateKey();
            }
        }
        return key;
    }

    public void regenerateKey() {
        Random rnd = new Random();
        key = new byte[16];
        rnd.nextBytes(key);
        try (FileOutputStream fos = new FileOutputStream(keyFile())) {
            fos.write(key);
        }
        catch (IOException e) {
            FiguraMod.LOGGER.error("Error occured while writing a key for FSB: ", e);
        }
    }

    private static class AvatarInputStream {
        private final FSB parent;
        private final int id;
        private final Hash hash;
        private final Hash ehash;
        private final UserData target;
        private final LinkedList<byte[]> dataChunks = new LinkedList<>();
        private int size = 0;

        private AvatarInputStream(FSB parent, int id, Hash hash, Hash ehash, UserData target) {
            this.parent = parent;
            this.id = id;
            this.hash = hash;
            this.ehash = ehash;
            this.target = target;
        }

        private void acceptDataChunk(byte[] chunk, boolean finalChunk) {
            dataChunks.add(chunk);
            size += chunk.length;
            if (finalChunk) {
                byte[] avatarData = new byte[size];
                int offset = 0;
                for (byte[] dataChunk : dataChunks) {
                    System.arraycopy(dataChunk, 0, avatarData, offset, dataChunk.length);
                    offset += dataChunk.length;
                }
                // Note: verify the **wire bytes before decompression**. Decompressing and recompressing
                //   changes the NBT key order and therefore the bytes, so the hash could never match that way.
                try {
                    Hash resultHash = Utils.getHash(avatarData);
                    if (!resultHash.equals(hash)) {
                        // Warning: this used to send only the reject packet **with no return**, so it fell
                        //   through, cached the rejected data and loaded it. That was the second way the cache
                        //   got polluted.
                        parent.sendPacket(new CloseIncomingStreamPacket(id, StatusCode.INVALID_HASH));
                        FiguraMod.LOGGER.error("Rejected FSB avatar for {}: expected {}, got {}",
                                target.id, hash, resultHash);
                        return;
                    }
                    if (FiguraMod.isLocal(target.id) && !parent.getEHash(hash).equals(ehash)) {
                        parent.sendPacket(new CloseIncomingStreamPacket(id, StatusCode.OWNERSHIP_CHECK_ERROR));
                        FiguraMod.LOGGER.error("Rejected own FSB avatar {}: ownership check failed", hash);
                        return;
                    }

                    ByteArrayInputStream bais = new ByteArrayInputStream(avatarData);
                    CompoundTag tag = NbtIo.readCompressed(bais, NbtAccounter.unlimitedHeap());
                    // Save under the computed hash. It equals the expected one (checked above), but even if someone
                    // removes the check later, "FSB cache file name = wire SHA-256 of its content" still holds.
                    CacheAvatarLoader.save(AvatarSource.FSB, resultHash.toString(), tag);
                    // if pin/netlock was set mid-stream, keep only the cache and do not apply (finally closes the stream)
                    if (!AvatarManager.acceptsNetworkAvatar(target.id))
                        return;
                    target.loadAvatar(tag);
                }
                catch (Exception e) {
                    FiguraMod.LOGGER.error("Failed to load avatar for " + target.id, e);
                }
                finally {
                    // Warning: this must be in finally. If the returns above skipped this cleanup the stream
                    //   would leak, and the server does not close it for us (the CloseIncomingStreamPacket the
                    //   client sends has no entry in the server's PACKET_HANDLERS and is silently dropped).
                    parent.inputStreams.remove(id);
                }
            }
        }

        private void close(StatusCode code) {
            switch (code) {
                case AVATAR_DOES_NOT_EXIST -> FiguraMod.LOGGER.info("Avatar with hash %s does not exist on this server".formatted(hash));
                default -> FiguraMod.LOGGER.error("Incoming stream was closed by unexpected reason: %s".formatted(code.name()));
            }
            parent.inputStreams.remove(id);
        }
    }

    private static class AvatarOutputStream {
        private final FSB parent;
        private final String avatarId;
        private final int id;
        private final byte[] data;
        private int position;
        private boolean upload;

        private AvatarOutputStream(FSB parent, String avatarId, int id, byte[] data) {
            this.parent = parent;
            this.avatarId = avatarId;
            this.id = id;
            this.data = data;
        }

        private void tick() {
            if (upload) {
                int size = nextChunkSize();
                byte[] chunk = new byte[size];
                System.arraycopy(data, position, chunk, 0, chunk.length);
                position += size;
                boolean finalChunk = data.length == position;
                parent.sendPacket(new AvatarDataPacket(id, finalChunk, chunk));
                if (finalChunk) upload = false;
            }
        }

        private int nextChunkSize() {
            return Math.min(AvatarDataPacket.MAX_CHUNK_SIZE, data.length - position);
        }

        private void allow() {
            upload = true;
        }

        private void close(StatusCode code) {
            switch (code) {
                case FINISHED, ALREADY_EXISTS -> {
                    // This is handled by the AvatarReadyPacket.
                }
                case MAX_AVATAR_SIZE_EXCEEDED -> {
                    FiguraToast.sendToast(FiguraText.of("backend.upload_too_big"), FiguraToast.ToastType.ERROR);
                }
                default -> {
                    FiguraToast.sendToast(FiguraText.of("backend.upload_error"), code, FiguraToast.ToastType.ERROR);
                }
            }
            parent.outputStreams.remove(id);
        }
    }

    public enum State {
        Uninitialized,
        HandshakeSent,
        Connected,
        Refused
    }

    public interface UserdataHandler {
        void handle(S2CUserdataPacket userdata);
        void userdataNotFound();
    }

    private static class UserdataApplier implements UserdataHandler {
        private final UserData user;

        private UserdataApplier(UserData user) {
            this.user = user;
        }

        @Override
        public void handle(S2CUserdataPacket packet) {
            FSB.instance().applyUserData(user, packet);
        }

        @Override
        public void userdataNotFound() {
            NetworkStuff.getUserFromBackend(user);
        }
    }
}
