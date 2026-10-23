package org.figuramc.figura.lua.api;

import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.lua.LuaNotNil;
import org.figuramc.figura.lua.LuaWhitelist;
import org.figuramc.figura.lua.ReadOnlyLuaView;
import org.figuramc.figura.lua.api.entity.EntityAPI;
import org.figuramc.figura.lua.api.event.LuaEvent;
import org.figuramc.figura.lua.docs.LuaFieldDoc;
import org.figuramc.figura.lua.docs.LuaMetamethodDoc;
import org.figuramc.figura.lua.docs.LuaMetamethodDoc.LuaMetamethodOverload;
import org.figuramc.figura.lua.docs.LuaMethodDoc;
import org.figuramc.figura.lua.docs.LuaMethodOverload;
import org.figuramc.figura.lua.docs.LuaTypeDoc;
import org.figuramc.figura.serverdata.JsonLua;
import org.figuramc.figura.serverdata.ServerDataStore;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * `server_data` — 서버 플러그인 FiguraController 가 주인인 상태를 **읽는다**(모든 아바타 — 플레이어 · 몹). 쓰는 것은 서버뿐이다.
 * 값은 «자기 값이 없으면 전역 값». 표는 읽기 전용(고치면 오류). 바뀌면 `STATE_CHANGED`(옛 값 ≠ 새 값일 때만 · 다음 틱).
 * 저장소는 코어에 있다 — 아바타를 다시 불러도 · 몹 아바타가 다시 생겨도 값이 그대로다.
 */
@LuaWhitelist
@LuaTypeDoc(
        name = "ServerDataAPI",
        value = "server_data"
)
public class ServerDataAPI {
    private final Avatar owner;
    private final Set<UUID> watching = new HashSet<>();

    @LuaWhitelist
    @LuaFieldDoc("server_data.state_changed")
    public final LuaEvent STATE_CHANGED = new LuaEvent();

    @LuaWhitelist
    @LuaFieldDoc("server_data.signal")
    public final LuaEvent SIGNAL = new LuaEvent();

    public ServerDataAPI(Avatar owner) {
        this.owner = owner;
    }

    public boolean isWatching(UUID subject) {
        return watching.contains(subject);
    }

    /** UUID 글 · 엔티티 → UUID */
    private static UUID subject(Object o) {
        if (o instanceof EntityAPI<?> e) return UUID.fromString(e.getUUID());
        if (o instanceof String s) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException ex) {
                throw new LuaError("Invalid subject UUID: " + s);
            }
        }
        throw new LuaError("Subject must be an entity or a UUID string");
    }

    private static LuaValue ro(LuaValue v) {
        return v.istable() ? ReadOnlyLuaView.of(v) : v;
    }

    @LuaWhitelist
    @LuaMethodDoc(
            overloads = {
                    @LuaMethodOverload(argumentTypes = String.class, argumentNames = "name"),
                    @LuaMethodOverload(argumentTypes = {EntityAPI.class, String.class}, argumentNames = {"entity", "name"}),
                    @LuaMethodOverload(argumentTypes = {String.class, String.class}, argumentNames = {"uuid", "name"})
            },
            value = "server_data.get"
    )
    public LuaValue get(@LuaNotNil Object a, String name) {
        if (name == null) {
            if (!(a instanceof String n)) throw new LuaError("Expected a state name");
            return ro(ServerDataStore.get(owner.owner, n));
        }
        return ro(ServerDataStore.get(subject(a), name));
    }

    @LuaWhitelist
    @LuaMethodDoc(
            overloads = @LuaMethodOverload(argumentTypes = String.class, argumentNames = "name"),
            value = "server_data.get_global"
    )
    public LuaValue getGlobal(@LuaNotNil String name) {
        return ro(ServerDataStore.getGlobal(name));
    }

    @LuaWhitelist
    @LuaMethodDoc(
            overloads = {
                    @LuaMethodOverload,
                    @LuaMethodOverload(argumentTypes = EntityAPI.class, argumentNames = "entity"),
                    @LuaMethodOverload(argumentTypes = String.class, argumentNames = "uuid")
            },
            value = "server_data.get_all"
    )
    public LuaValue getAll(Object a) {
        UUID s = a == null ? owner.owner : subject(a);
        LuaTable t = new LuaTable();
        for (Map.Entry<String, org.figuramc.figura.serverdata.ServerDataModel.Entry> e : ServerDataStore.all(s).entrySet()) t.rawset(e.getKey(), e.getValue().lua());
        return ReadOnlyLuaView.of(t);
    }

    @LuaWhitelist
    @LuaMethodDoc(
            overloads = {
                    @LuaMethodOverload(argumentTypes = EntityAPI.class, argumentNames = "entity"),
                    @LuaMethodOverload(argumentTypes = String.class, argumentNames = "uuid")
            },
            value = "server_data.watch"
    )
    public void watch(@LuaNotNil Object a) {
        UUID s = subject(a);
        if (watching.add(s) && !s.equals(owner.owner)) ServerDataStore.onWatch(owner, s);
    }

    @LuaWhitelist
    @LuaMethodDoc(
            overloads = {
                    @LuaMethodOverload(argumentTypes = EntityAPI.class, argumentNames = "entity"),
                    @LuaMethodOverload(argumentTypes = String.class, argumentNames = "uuid")
            },
            value = "server_data.unwatch"
    )
    public void unwatch(@LuaNotNil Object a) {
        watching.remove(subject(a));
    }

    @LuaWhitelist
    @LuaMethodDoc(
            overloads = @LuaMethodOverload(argumentTypes = {String.class, LuaValue.class}, argumentNames = {"name", "data"}),
            value = "server_data.send"
    )
    public boolean send(@LuaNotNil String name, LuaValue data) {
        if (!owner.isHost) throw new LuaError("Only the host player avatar can send signals to the server");
        return ServerDataStore.sendSignal(owner.owner, name, JsonLua.toJson(data == null ? LuaValue.NIL : data));
    }

    /**
     * ★ 이벤트 칸을 Lua 에 내보낸다 — `LuaTypeManager` 는 화이트리스트 **메서드만** 메타테이블에 싣고 필드는 안 싣는다.
     * 이게 없으면 `server_data.STATE_CHANGED` 가 nil 이다(2026-10-10 인게임 실측 — 하네스 figura_controller T8. T4 시험은 Java 모델만 봤다).
     * `events` 와 같게 대소문자를 안 가린다.
     */
    @LuaWhitelist
    @LuaMetamethodDoc(overloads = @LuaMetamethodOverload(
            types = {LuaEvent.class, ServerDataAPI.class, String.class},
            comment = "server_data.__index.comment1"
    ))
    public LuaEvent __index(String key) {
        if (key == null) return null;
        return switch (key.toUpperCase(Locale.US)) {
            case "STATE_CHANGED" -> STATE_CHANGED;
            case "SIGNAL" -> SIGNAL;
            default -> null;
        };
    }

    /** `function server_data.STATE_CHANGED(...)` 처럼 대입하면 처리기를 단다(`events` 와 같다) */
    @LuaWhitelist
    public void __newindex(@LuaNotNil String key, LuaFunction func) {
        LuaEvent event = __index(key);
        if (event != null) event.register(func, null);
        else throw new LuaError("Cannot assign value on key \"" + key + "\"");
    }

    @Override
    public String toString() {
        return "ServerDataAPI";
    }
}
