package org.figuramc.figura.lua;

import org.luaj.vm2.LuaInteger;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 다른 Lua 테이블을 **복사하지 않고** 읽기 전용으로 보여 주는 뷰.
 * <p>
 * {@link ReadOnlyLuaTable} 은 만들 때 원본 전체를 재귀로 깊은 복사한다 — 키 하나를 읽으려 해도
 * 그 아바타가 {@code avatar:store} 한 것 전부를 복사했다({@code entity:getVariable(key)} ·
 * {@code world.avatarVars()} 가 부를 때마다). 이 뷰는 읽은 만큼만 일한다:
 * <ul>
 *   <li>읽기({@code rawget} · {@code get} · {@code next} · {@code inext} · 길이)는 원본에 그대로 맡긴다.
 *       값이 테이블이면 그 자리에서 뷰로 감싸 돌려준다(**게으르게** — 안 읽은 하위 테이블은 손대지 않는다).
 *       기본값 · 함수 · userdata 는 그대로 돌려준다(옛 복사도 그랬다).</li>
 *   <li>쓰기({@code set} · {@code rawset} · {@code hashset} · {@code insert} · {@code remove} ·
 *       {@code setmetatable})는 전부 {@code "table is read-only"} 오류다.</li>
 * </ul>
 * ⚠ <b>의미가 바뀐 점 — 스냅샷이 아니라 살아 있는 뷰다.</b> 원본(남의 아바타가 store 한 테이블)이
 * 나중에 바뀌면 들고 있던 뷰에도 보인다. 옛 복사는 부른 순간의 사본이었다.
 * 단 그 아바타가 다시 불러와지면(리로드) 새 런타임의 새 저장소가 생기므로, 예전 뷰는 옛 저장소를 계속 본다 —
 * 다시 읽으려면 {@code getVariable} · {@code avatarVars} 를 다시 부른다.
 * <p>
 * 옛 복사와 같게 지킨 것:
 * <ul>
 *   <li><b>원본의 메타테이블을 보지 않는다</b> — 모든 읽기가 raw 다. 옛 사본도 메타테이블이 없었다.
 *       그리고 남의 아바타의 {@code __index} · {@code __len} 함수가 읽는 쪽 맥락에서 돌지 않게 한다.</li>
 *   <li>뷰 자신의 메타테이블은 nil 이다({@code getmetatable} → nil · Figura 의 {@code pairs} 래퍼도 기본 {@code next} 로 간다).</li>
 *   <li>같은 원본 테이블에는 같은 뷰를 돌려준다 — {@code t.a == t.a} · 순환({@code t.self == t}) 이 참이다.
 *       같은 뿌리 뷰에서 나온 것끼리만 그렇다(옛 사본도 한 번 부른 사본 안에서만 같았다).</li>
 *   <li>키는 감싸지 않는다 — 테이블을 키로 쓴 경우 원본 키가 그대로 나온다(옛 복사도 키는 그대로 옮겼다).
 *       감싸면 그 키로 {@code next} 를 다시 부를 때 원본에서 못 찾는다.</li>
 * </ul>
 * ★ 덤 — 옛 복사는 직접 자기 참조만 막아서 {@code a.b.a == a} 같은 두 단 이상의 순환이면 무한 재귀(StackOverflowError)였다.
 * 뷰는 게으르니 순환이 몇 단이든 문제없다.
 * <p>
 * 스레드: 하위 뷰 캐시({@link WeakHashMap})는 동기화하지 않는다. 아바타 Lua 는 클라 메인 스레드에서 돈다
 * (원본을 읽는 것 자체도 옛 복사와 같은 스레드 조건이다).
 */
public class ReadOnlyLuaView extends LuaTable {
    private static final String READ_ONLY = "table is read-only";

    /** 보여 줄 원본. 이 뷰의 자기 저장소(array · hash)는 비어 있고 쓰지 않는다 */
    private final LuaTable source;
    /** 이 뷰를 낳은 뿌리 뷰(뿌리면 자기 자신). 하위 뷰 캐시는 뿌리 하나에만 있다 */
    private final ReadOnlyLuaView root;
    /**
     * 뿌리만 쓴다 — 원본 테이블 → 그 뷰. 처음으로 테이블 값을 읽을 때 만든다(안 읽으면 0 바이트).
     * 키(원본)도 값(뷰)도 약한 참조라 쌓이지 않는다: 아무도 안 든 뷰는 GC 가 치우고,
     * 원본이 store 에서 빠져 사라지면 그 칸도 빠진다(값이 키를 강하게 잡는 WeakHashMap 함정을 피하려고 값도 WeakReference).
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

    /** 테이블이면 뷰로 감싸고(이미 뷰면 그대로), 아니면 그대로 돌려준다 — 값 하나만 꺼내 줄 때 쓴다 */
    public static LuaValue of(LuaValue value) {
        if (value instanceof LuaTable table && !(value instanceof ReadOnlyLuaView))
            return new ReadOnlyLuaView(table);
        return value;
    }

    // ---- 감싸기 ---- //

    private LuaValue view(LuaValue value) {
        if (value instanceof LuaTable table && !(value instanceof ReadOnlyLuaView))
            return root.child(table);
        return value;
    }

    /** 뿌리에서만 불린다 */
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

    // ---- 읽기: 원본에 맡긴다 (raw) ---- //

    @Override
    public LuaValue rawget(int key) {
        return view(source.rawget(key));
    }

    @Override
    public LuaValue rawget(LuaValue key) {
        return view(source.rawget(key));
    }

    // 뷰는 메타테이블이 없으니 get == rawget (원본의 __index 도 부르지 않는다)
    @Override
    public LuaValue get(int key) {
        return rawget(key);
    }

    @Override
    public LuaValue get(LuaValue key) {
        return rawget(key);
    }

    /** pairs · next · keys() · keyCount() 가 이것을 탄다. 원본의 다음 칸을 그대로 돌려주되 값만 감싼다 */
    @Override
    public Varargs next(LuaValue key) {
        Varargs n = source.next(key);
        LuaValue v = n.arg(2);
        return v instanceof LuaTable ? varargsOf(n.arg1(), view(v)) : n;
    }

    /** ipairs 가 이것을 탄다 */
    @Override
    public Varargs inext(LuaValue key) {
        int k = key.checkint() + 1;
        LuaValue v = source.rawget(k);
        return v.isnil() ? NONE : varargsOf(LuaInteger.valueOf(k), view(v));
    }

    // 길이: 기본 LuaTable.rawlen 은 자기 array · hash 길이로 탐색 보폭을 정해서(뷰는 0) 한 칸씩 훑게 된다 → 원본에 맡긴다.
    // length() · len() 도 원본의 __len 을 부르지 않게 raw 로 고정한다(# · table.concat · table.unpack 이 이것을 탄다)
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

    // ---- 쓰기: 전부 막는다 ---- //
    // LuaValue 의 다른 오버로드(set(String, …) · rawset(String, …) · rawset(int, String) · rawsetlist …)는 전부 아래 넷으로 모인다.
    // table.sort 는 set(int) 로 막힌다(옛 ReadOnlyLuaTable 과 같다 — 원소 1개 이하면 바꿀 게 없어 오류 없이 끝난다).

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
