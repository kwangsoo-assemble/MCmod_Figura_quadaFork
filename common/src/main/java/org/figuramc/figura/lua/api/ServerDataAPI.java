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
 * {@code server_data} — <b>reads</b> the states owned by the FiguraController server plugin (every avatar — players and mobs). Only the server writes them.
 * A value is «own value, else the global value». Tables are read-only (writes error). Changes fire {@code STATE_CHANGED} (only when old ≠ new · on the next tick).
 * The store lives in the core — values survive avatar reloads and mob avatars being recreated.
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

    /** UUID string · entity → UUID */
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
     * Exposes the event fields to Lua — {@code LuaTypeManager} only puts whitelisted <b>methods</b> into the metatable, not fields.
     * Without this, {@code server_data.STATE_CHANGED} is nil. Case-insensitive, like {@code events}.
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

    /** Assigning a function registers it, like {@code events}: {@code function server_data.STATE_CHANGED(...)} */
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
