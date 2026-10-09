package org.figuramc.figura.serverdata;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import kr.asmbl.figuracontroller.protocol.ProtocolException;
import kr.asmbl.figuracontroller.protocol.S2CBuilder;
import kr.asmbl.figuracontroller.protocol.S2COp;
import kr.asmbl.figuracontroller.protocol.S2CReader;
import kr.asmbl.figuracontroller.protocol.Values;
import org.luaj.vm2.LuaValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The pure core of the server_data store — knows nothing about Minecraft or avatars (testable offline).
 * Applies packets · records the «before first touch» values · drains what changed · builds Flashback snapshots. Delivery to avatars is {@link ServerDataStore}.
 */
public final class ServerDataModel {

    /** One value — its Lua value is built once on first read and shared between avatars */
    public static final class Entry {
        public final JsonElement json;
        final byte[] bytes;
        private LuaValue lua;

        Entry(JsonElement json) {
            this.json = json;
            this.bytes = Values.encode(json);
        }

        public LuaValue lua() {
            if (lua == null) lua = JsonLua.toLua(json);
            return lua;
        }

        /** Same value? — compared by bytes (1 and 1.0 differ) · both absent counts as same */
        public static boolean same(Entry a, Entry b) {
            if (a == null || b == null) return a == b;
            return Arrays.equals(a.bytes, b.bytes);
        }
    }

    /** One change — {@code subject == null} means global. For a subject, old and new follow «own value, else global». Absent = null */
    public record Change(UUID subject, String name, Entry before, Entry now) {}

    public record Signal(UUID subject, String name, JsonElement data) {}

    /** One drain — order: subject changes · global changes · signals */
    public record Drained(List<Change> own, List<Change> global, List<Signal> signals) {
        public boolean isEmpty() {
            return own.isEmpty() && global.isEmpty() && signals.isEmpty();
        }
    }

    private static final Entry ABSENT = new Entry(JsonNull.INSTANCE);

    private static final class PendingFull {
        final int total;
        final LinkedHashMap<String, Entry> map = new LinkedHashMap<>();

        PendingFull(int total) {
            this.total = total;
        }
    }

    private final Map<UUID, LinkedHashMap<String, Entry>> subjects = new HashMap<>();
    private final LinkedHashMap<String, Entry> global = new LinkedHashMap<>();
    private final Map<Integer, String> names = new HashMap<>();
    private final Map<UUID, PendingFull> pending = new HashMap<>();
    private PendingFull pendingGlobal;
    private final Map<UUID, Map<String, Entry>> ownBefore = new LinkedHashMap<>();
    private final Map<String, Entry> globalBefore = new LinkedHashMap<>();
    private final List<Signal> signals = new ArrayList<>();

    // ── packets

    /** @throws ProtocolException on a malformed packet — nothing changes (the whole packet is read first) */
    public void apply(byte[] body) {
        List<S2COp> ops = S2CReader.read(body);
        UUID ctx = null;
        boolean isGlobal = false;
        for (S2COp op : ops) {
            UUID target = isGlobal ? null : ctx;
            if (op instanceof S2COp.NameOp n) {
                names.put(n.id(), n.name());
            } else if (op instanceof S2COp.SubjectOp s) {
                ctx = s.uuid();
                isGlobal = false;
            } else if (op instanceof S2COp.GlobalOp) {
                ctx = null;
                isGlobal = true;
            } else if (op instanceof S2COp.SetOp s) {
                String name = names.get(s.nameId());
                if (name != null) put(target, name, new Entry(s.value()));
            } else if (op instanceof S2COp.UnsetOp u) {
                String name = names.get(u.nameId());
                if (name != null) remove(target, name);
            } else if (op instanceof S2COp.FullOp f) {
                PendingFull p = new PendingFull(f.total());
                addEntries(p, f.entries());
                if (p.map.size() >= p.total) {
                    replaceAll(target, p.map);
                } else if (isGlobal) {
                    pendingGlobal = p;
                } else {
                    pending.put(ctx, p);
                }
            } else if (op instanceof S2COp.FullContOp c) {
                PendingFull p = isGlobal ? pendingGlobal : pending.get(ctx);
                if (p == null) continue;
                addEntries(p, c.entries());
                if (p.map.size() >= p.total) {
                    replaceAll(target, p.map);
                    if (isGlobal) pendingGlobal = null;
                    else pending.remove(ctx);
                }
            } else if (op instanceof S2COp.DropOp) {
                if (!isGlobal && ctx != null) {
                    pending.remove(ctx);
                    LinkedHashMap<String, Entry> m = subjects.get(ctx);
                    if (m != null) for (String name : new ArrayList<>(m.keySet())) remove(ctx, name);
                }
            } else if (op instanceof S2COp.SignalOp s) {
                String name = names.get(s.nameId());
                if (name != null) signals.add(new Signal(target, name, s.value()));
            } else if (op instanceof S2COp.ResetOp) {
                reset();
            }
        }
    }

    private void addEntries(PendingFull p, List<S2COp.Entry> entries) {
        for (S2COp.Entry e : entries) {
            String name = names.get(e.nameId());
            if (name != null) p.map.put(name, new Entry(e.value()));
        }
    }

    private void markBefore(UUID subject, String name) {
        if (subject == null) {
            if (!globalBefore.containsKey(name)) {
                Entry e = global.get(name);
                globalBefore.put(name, e == null ? ABSENT : e);
            }
            return;
        }
        Map<String, Entry> m = ownBefore.computeIfAbsent(subject, k -> new LinkedHashMap<>());
        if (!m.containsKey(name)) {
            LinkedHashMap<String, Entry> own = subjects.get(subject);
            Entry e = own == null ? null : own.get(name);
            m.put(name, e == null ? ABSENT : e);
        }
    }

    private void put(UUID subject, String name, Entry e) {
        markBefore(subject, name);
        if (subject == null) global.put(name, e);
        else subjects.computeIfAbsent(subject, k -> new LinkedHashMap<>()).put(name, e);
    }

    private void remove(UUID subject, String name) {
        markBefore(subject, name);
        if (subject == null) {
            global.remove(name);
            return;
        }
        LinkedHashMap<String, Entry> own = subjects.get(subject);
        if (own == null) return;
        own.remove(name);
        if (own.isEmpty()) subjects.remove(subject);
    }

    private void replaceAll(UUID subject, LinkedHashMap<String, Entry> now) {
        Map<String, Entry> old = subject == null ? global : subjects.get(subject);
        if (old != null) for (String name : new ArrayList<>(old.keySet())) if (!now.containsKey(name)) remove(subject, name);
        for (Map.Entry<String, Entry> e : now.entrySet()) put(subject, e.getKey(), e.getValue());
    }

    /** RESET — clears everything (including the name dictionary) · keeps the «before» values for the next drain */
    private void reset() {
        for (String name : new ArrayList<>(global.keySet())) remove(null, name);
        for (UUID s : new ArrayList<>(subjects.keySet())) {
            LinkedHashMap<String, Entry> m = subjects.get(s);
            if (m != null) for (String name : new ArrayList<>(m.keySet())) remove(s, name);
        }
        names.clear();
        pending.clear();
        pendingGlobal = null;
    }

    /** Left the server · left the world — clears without recording changes */
    public void clear() {
        subjects.clear();
        global.clear();
        names.clear();
        pending.clear();
        pendingGlobal = null;
        ownBefore.clear();
        globalBefore.clear();
        signals.clear();
    }

    // ── reading

    /** Own value, else the global value · null when neither exists */
    public Entry effective(UUID subject, String name) {
        LinkedHashMap<String, Entry> own = subject == null ? null : subjects.get(subject);
        Entry e = own == null ? null : own.get(name);
        return e != null ? e : global.get(name);
    }

    public Entry globalEntry(String name) {
        return global.get(name);
    }

    public boolean hasOwn(UUID subject, String name) {
        LinkedHashMap<String, Entry> own = subjects.get(subject);
        return own != null && own.containsKey(name);
    }

    /** Global + own (own wins) — order: global names, then names only the subject has */
    public LinkedHashMap<String, Entry> all(UUID subject) {
        LinkedHashMap<String, Entry> out = new LinkedHashMap<>(global);
        LinkedHashMap<String, Entry> own = subject == null ? null : subjects.get(subject);
        if (own != null) out.putAll(own);
        return out;
    }

    /** Only the subject's own values (no global) */
    public Map<String, Entry> own(UUID subject) {
        LinkedHashMap<String, Entry> own = subjects.get(subject);
        return own == null ? Map.of() : java.util.Collections.unmodifiableMap(own);
    }

    public int subjectCount() {
        return subjects.size();
    }

    public int globalCount() {
        return global.size();
    }

    // ── draining changes (once per client tick)

    public Drained drain() {
        if (ownBefore.isEmpty() && globalBefore.isEmpty() && signals.isEmpty()) return new Drained(List.of(), List.of(), List.of());
        List<Change> own = new ArrayList<>();
        for (Map.Entry<UUID, Map<String, Entry>> s : ownBefore.entrySet()) {
            for (Map.Entry<String, Entry> n : s.getValue().entrySet()) {
                String name = n.getKey();
                Entry ob = nullIfAbsent(n.getValue());
                Entry gb = globalBefore.containsKey(name) ? nullIfAbsent(globalBefore.get(name)) : global.get(name);
                Entry before = ob != null ? ob : gb;
                Entry now = effective(s.getKey(), name);
                if (!Entry.same(before, now)) own.add(new Change(s.getKey(), name, before, now));
            }
        }
        List<Change> glob = new ArrayList<>();
        for (Map.Entry<String, Entry> g : globalBefore.entrySet()) {
            Entry before = nullIfAbsent(g.getValue());
            Entry now = global.get(g.getKey());
            if (!Entry.same(before, now)) glob.add(new Change(null, g.getKey(), before, now));
        }
        Drained d = new Drained(own, glob, new ArrayList<>(signals));
        ownBefore.clear();
        globalBefore.clear();
        signals.clear();
        return d;
    }

    /** Did this drain already count an «own value change» for this subject and name (so a global change is not delivered twice) */
    public static boolean touchedOwn(Drained d, UUID subject, String name) {
        for (Change c : d.own()) if (c.subject().equals(subject) && c.name().equals(name)) return true;
        return false;
    }

    private static Entry nullIfAbsent(Entry e) {
        return e == ABSENT ? null : e;
    }

    // ── Flashback

    /** RESET · the whole name dictionary (the server's ids as-is — deltas recorded afterwards use them) · global FULL · subject FULLs */
    public List<byte[]> snapshotBodies() {
        S2CBuilder b = new S2CBuilder();
        b.reset();
        Map<String, Integer> ids = new HashMap<>();
        for (Map.Entry<Integer, String> e : new TreeMap<>(names).entrySet()) {
            b.name(e.getKey(), e.getValue());
            ids.put(e.getValue(), e.getKey());
        }
        writeFull(b, ids, global, null);
        for (Map.Entry<UUID, LinkedHashMap<String, Entry>> s : subjects.entrySet()) writeFull(b, ids, s.getValue(), s.getKey());
        return b.build();
    }

    private static void writeFull(S2CBuilder b, Map<String, Integer> ids, Map<String, Entry> m, UUID subject) {
        List<Integer> nameIds = new ArrayList<>();
        List<byte[]> vals = new ArrayList<>();
        for (Map.Entry<String, Entry> e : m.entrySet()) {
            Integer id = ids.get(e.getKey());
            if (id == null) continue;
            nameIds.add(id);
            vals.add(e.getValue().bytes);
        }
        if (nameIds.isEmpty()) return;
        if (subject == null) b.global();
        else b.subject(subject);
        b.full(nameIds.stream().mapToInt(Integer::intValue).toArray(), vals.toArray(new byte[0][]));
    }
}
