package org.figuramc.figura.avatar;

import com.mojang.datafixers.util.Pair;
import net.minecraft.nbt.CompoundTag;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.avatar.local.CacheAvatarLoader;
import org.figuramc.figura.backend2.NetworkStuff;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

public class UserData {

    public final UUID id;
    private final Queue<Avatar> avatars = new ConcurrentLinkedQueue<>();
    private Pair<BitSet, BitSet> badges;

    public UserData(UUID id) {
        this.id = id;
    }

    /**
     * @param source who issued these hashes. Decides the download path and the cache namespace.
     *               The side that produced the hash must pick the transport, so the origin cannot get mixed up.
     */
    public void loadData(ArrayList<Pair<String, Pair<String, UUID>>> avatars, Pair<BitSet,BitSet> badges,
                         AvatarSource source) {
        // pin / netlock: even if set after the request went out, the response is blocked here
        // (this is called from an async thread)
        if (!AvatarManager.acceptsNetworkAvatar(id)) {
            FiguraMod.debug("Ignoring network userdata for " + id + " (pinned or network locked)");
            return;
        }
        loadBadges(badges);
        clear();
        for (Pair<String, Pair<String, UUID>> avatar : avatars) {
            String hash = avatar.getFirst();
            if (!CacheAvatarLoader.checkAndLoad(source, hash, this)) {
                Pair<String, UUID> pair = avatar.getSecond();
                NetworkStuff.getAvatar(this, source, pair.getSecond(), pair.getFirst(), hash);
            }
        }
    }

    public void loadAvatar(CompoundTag nbt) {
        FiguraMod.debug("--- avatar loading: " + id + " ---");
        Avatar avatar = new Avatar(id);
        this.avatars.add(avatar);
        avatar.load(nbt);
        FiguraMod.debug("--- loaded " + id + " ---");
    }

    public void loadBadges(Pair<BitSet, BitSet> pair) {
        this.badges = pair;
    }

    public Pair<BitSet, BitSet> getBadges() {
        return badges;
    }

    public Queue<Avatar> getAvatars() {
        return avatars;
    }

    public Avatar getMainAvatar() {
        return avatars.peek();
    }

    public void clear() {
        for (Avatar avatar : avatars)
            avatar.clean();
        avatars.clear();
    }

}
