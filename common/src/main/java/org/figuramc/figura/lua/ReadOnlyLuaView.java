package org.figuramc.figura.lua;

import org.luaj.vm2.LuaInteger;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A read-only view of another Lua table that does <b>not copy</b> it.
 * <p>
 * {@link ReadOnlyLuaTable} deep-copies the whole source table recursively when it is created, so reading a single key
 * copied everything that avatar had {@code avatar:store}d ({@code entity:getVariable(key)} and
 * {@code world.avatarVars()} did that on every call). This view only does work for what is actually read:
 * <ul>
 *   <li>Reads ({@code rawget}, {@code get}, {@code next}, {@code inext}, length) are delegated to the source.
 *       Table values are wrapped in a view on the spot (<b>lazily</b> - sub-tables that are never read are never touched).
 *       Primitives, functions and userdata are returned as-is (the old copy did the same).</li>
 *   <li>Writes ({@code set}, {@code rawset}, {@code hashset}, {@code insert}, {@code remove},
 *       {@code setmetatable}) all raise {@code "table is read-only"}.</li>
 * </ul>
 * Note: <b>this is a live view, not a snapshot.</b> If the source (a table another avatar stored) changes later,
 * a view you are holding sees the change. The old copy was a snapshot taken at call time.
 * When that avatar is reloaded, its new runtime gets a new store, so an old view keeps looking at the old store -
 * call {@code getVariable} / {@code avatarVars} again to read the new one.
 * <p>
 * Kept the same as the old copy:
 * <ul>
 *   <li><b>The source metatable is never consulted</b> - every read is raw. The old copy had no metatable either,
 *       and this keeps another avatar's {@code __index} / {@code __len} functions from running in the reader's context.</li>
 *   <li>The view itself has a nil metatable ({@code getmetatable} returns nil, and Figura's {@code pairs} wrapper
 *       falls back to the default {@code next}).</li>
 *   <li>The same source table always gives the same view, so {@code t.a == t.a} and cycles ({@code t.self == t}) hold -
 *       within views that come from the same root (the old copy was only consistent within one copy, too).</li>
 *   <li>Keys are not wrapped - a table used as a key comes back as the original key (the old copy kept keys as-is).
 *       Wrapping it would make the source unable to find it when {@code next} is called with it again.</li>
 * </ul>
 * Bonus: the old copy only guarded direct self-references, so a cycle of two or more steps ({@code a.b.a == a})
 * recursed forever (StackOverflowError). The view is lazy, so cycles of any depth are fine.
 * <p>
 * Threading: the child-view cache ({@link WeakHashMap}) is not synchronized. Avatar Lua runs on the client main thread
 * (reading the source has the same thread requirements as the old copy).
 */
public class ReadOnlyLuaView extends LuaTable {
    private static final String READ_ONLY = "table is read-only";

    /** The table being shown. This view's own storage (array / hash) stays empty and unused */
    private final LuaTable source;
    /** The root view this view came from (itself if it is the root). Only the root holds the child-view cache */
    private final ReadOnlyLuaView root;
    /**
     * Root only - source table to its view. Created the first time a table value is read (zero bytes if never read).
     * Both the key (source) and the value (view) are weak, so nothing piles up: views nobody holds are collected,
     * and an entry goes away once its source leaves the store (the value is a WeakReference to avoid the
     * WeakHashMap pitfall of a value strongly holding its key).
     */
    private Map<LuaTable, WeakReference<ReadOnlyLuaView>> children;

    public ReadOnlyLuaView(LuaTable source) {
        this(source, null);
    }

    private ReadOnlyLuaView(LuaTable source, ReadOnlyLuaView root) {
        super();
        this.source = source;
        this.root = root == null ? this : root;
    }

    /** Wraps a table in a view (returns it unchanged if it already is one) and returns anything else as-is - for handing out a single value */
    public static LuaValue of(LuaValue value) {
        if (value instanceof LuaTable table && !(value instanceof ReadOnlyLuaView))
            return new ReadOnlyLuaView(table);
        return value;
    }

    // ---- wrapping ---- //

    private LuaValue view(LuaValue value) {
        if (value instanceof LuaTable table && !(value instanceof ReadOnlyLuaView))
            return root.child(table);
        return value;
    }

    /** Only called on the root */
    private ReadOnlyLuaView child(LuaTable table) {
        if (table == source)
            return this;
        if (children == null)
            children = new WeakHashMap<>();
        WeakReference<ReadOnlyLuaView> ref = children.get(table);
        ReadOnlyLuaView v = ref == null ? null : ref.get();
        if (v == null) {
            v = new ReadOnlyLuaView(table, this);
            children.put(table, new WeakReference<>(v));
        }
        return v;
    }

    // ---- reads: delegated to the source (raw) ---- //

    @Override
    public LuaValue rawget(int key) {
        return view(source.rawget(key));
    }

    @Override
    public LuaValue rawget(LuaValue key) {
        return view(source.rawget(key));
    }

    // The view has no metatable, so get == rawget (the source's __index is not called either)
    @Override
    public LuaValue get(int key) {
        return rawget(key);
    }

    @Override
    public LuaValue get(LuaValue key) {
        return rawget(key);
    }

    /** Used by pairs, next, keys() and keyCount(). Returns the source's next entry as-is, wrapping only the value */
    @Override
    public Varargs next(LuaValue key) {
        Varargs n = source.next(key);
        LuaValue v = n.arg(2);
        return v instanceof LuaTable ? varargsOf(n.arg1(), view(v)) : n;
    }

    /** Used by ipairs */
    @Override
    public Varargs inext(LuaValue key) {
        int k = key.checkint() + 1;
        LuaValue v = source.rawget(k);
        return v.isnil() ? NONE : varargsOf(LuaInteger.valueOf(k), view(v));
    }

    // Length: the default LuaTable.rawlen picks its search stride from its own array / hash size (0 for a view),
    // so it would probe one slot at a time -> delegate to the source.
    // length() and len() are pinned to raw as well so the source's __len is never called (#, table.concat and table.unpack use these)
    @Override
    public int rawlen() {
        return source.rawlen();
    }

    @Override
    public int length() {
        return source.rawlen();
    }

    @Override
    public LuaValue len() {
        return LuaInteger.valueOf(source.rawlen());
    }

    @Override
    public int keyCount() {
        return source.keyCount();
    }

    @Override
    public LuaValue[] keys() {
        return source.keys();
    }

    // ---- writes: all blocked ---- //
    // The other LuaValue overloads (set(String, ...), rawset(String, ...), rawset(int, String), rawsetlist ...) all funnel into the four below.
    // table.sort is blocked by set(int) (same as the old ReadOnlyLuaTable - with one element or fewer there is nothing to swap, so it ends without an error).

    @Override
    public LuaValue setmetatable(LuaValue metatable) {
        return error(READ_ONLY);
    }

    @Override
    public void set(int key, LuaValue value) {
        error(READ_ONLY);
    }

    @Override
    public void set(LuaValue key, LuaValue value) {
        error(READ_ONLY);
    }

    @Override
    public void rawset(int key, LuaValue value) {
        error(READ_ONLY);
    }

    @Override
    public void rawset(LuaValue key, LuaValue value) {
        error(READ_ONLY);
    }

    @Override
    public void hashset(LuaValue key, LuaValue value) {
        error(READ_ONLY);
    }

    @Override
    public void insert(int pos, LuaValue value) {
        error(READ_ONLY);
    }

    @Override
    public LuaValue remove(int pos) {
        return error(READ_ONLY);
    }
}
