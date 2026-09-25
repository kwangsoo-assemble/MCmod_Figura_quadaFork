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
     * @param source 이 해시들을 발급한 곳. 다운로드 경로와 캐시 네임스페이스를 결정한다.
     *               해시를 만든 쪽이 전송로를 정해야 출처가 뒤바뀌지 않는다.
     */
    public void loadData(ArrayList<Pair<String, Pair<String, UUID>>> avatars, Pair<BitSet,BitSet> badges,
                         AvatarSource source) {
        // pin / netlock — 요청이 나간 뒤에 걸렸어도 응답은 여기서 막힌다 (비동기 스레드에서 불린다)
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
