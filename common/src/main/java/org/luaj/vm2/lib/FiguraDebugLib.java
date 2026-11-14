package org.luaj.vm2.lib;

import org.luaj.vm2.LuaClosure;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaThread;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

/**
 * 명령마다 도는 길을 가볍게 한 DebugLib (포크 0.1.6.10 · 하네스 M61).
 *
 * 재고판은 Lua 명령 하나마다 globals.running.state 읽기 → callstack()(없으면 만들기) → synchronized
 * CallStack.onInstruction → 프레임에 pc · v · top 셋 적기를 한다. 실려 있기만 해도 이것이 Lua 시간의 절반쯤이다
 * (훅을 안 걸어도 같다 — 훅 유무 차 0 ~ 3%). 이 판은 지금 스레드 · 상태 · 프레임을 호출 · 반환 때 잡아 두고
 * 명령마다는 그 프레임에 pc 하나만 적는다. 오류 줄 · 스택 추적 · 명령 수 한도 동작은 재고판과 같다.
 *
 * 왜 이래도 되나:
 * - 호출 기록이 바뀌는 길은 onCall · onReturn 뿐이다(LuaClosure.execute · BaseLib pcall · xpcall) → 둘 다 super 뒤 다시 잡는다
 * - 프레임의 v · top 은 LuaJ 어디서도 읽지 않는다(TRACE 출력뿐) — pc 만 오류 줄 · getinfo · traceback 이 읽는다
 * - globals.running 은 코루틴만 바꾼다 — 포크는 CoroutineLib 을 안 싣지만 명령마다 스레드를 견줘 바뀌면 다시 잡는다
 * - synchronized 를 뺐다: 한 런타임을 두 스레드가 동시에 돌리면 재고판 호출 기록도 이미 깨진다
 *   (FiguraSVC 는 run · init 을 ReentrantLock 으로 직렬화한다 — 잠금이 스레드 사이 보임도 보장한다)
 *
 * ⚠ CallStack.frame · calls · CallFrame.pc · globals · callHook 은 패키지 전용이라 LuaJ 와 같은 패키지에 둔다
 *   (Fabric 은 모드 jar 와 jar 안의 LuaJ 를 한 Knot 로더로 정의한다). 막힌 환경이면 USABLE 이 false 가 되어
 *   FiguraLuaRuntime 이 재고판을 쓴다. LuaJ 판을 바꾸면 이 필드 이름을 다시 맞춘다.
 * ⚠ 스위치: JVM 인자 -Dfigura.stockDebugLib=true 면 재고판(되돌리기 · 인게임 비교용 — 다시 빌드할 필요 없음)
 */
public class FiguraDebugLib extends DebugLib {
    /** false 면 재고 DebugLib 을 쓴다 — 스위치가 켜졌거나 패키지 전용 접근이 막혔다 */
    public static final boolean USABLE = probe();

    private static final LuaString COUNT_ = LuaValue.valueOf("count");
    private static final LuaString LINE_ = LuaValue.valueOf("line");

    private LuaThread thread;
    private LuaThread.State st;
    private CallFrame cur;

    private static boolean probe() {
        if (Boolean.getBoolean("figura.stockDebugLib"))
            return false;
        try {
            // 아래 메서드들이 쓰는 패키지 전용 멤버를 한 번씩 건드려 본다(막히면 IllegalAccessError — LinkageError)
            CallStack cs = new CallStack();
            CallFrame f = new CallFrame();
            f.pc = cs.calls + cs.frame.length;
            return true;
        } catch (LinkageError e) {
            return false;
        }
    }

    private void sync() {
        LuaThread t = globals.running;
        thread = t;
        st = t.state;
        CallStack cs = (CallStack) t.callstack;
        cur = (cs != null && cs.calls > 0) ? cs.frame[cs.calls - 1] : null;
    }

    @Override
    public void onCall(LuaFunction f) {
        super.onCall(f);
        sync();
    }

    @Override
    public void onCall(LuaClosure c, Varargs varargs, LuaValue[] stack) {
        super.onCall(c, varargs, stack);
        sync();
    }

    @Override
    public void onReturn() {
        super.onReturn();
        sync();
    }

    @Override
    public void onInstruction(int pc, Varargs v, int top) {
        LuaThread.State s = st;
        if (s == null || thread != globals.running) {
            sync();
            s = st;
        }
        if (s.inhook)
            return;
        CallFrame f = cur;
        if (f != null)
            f.pc = pc;
        if (s.hookfunc == null)
            return;
        int c = s.hookcount;
        if (c > 0) {
            // 재고판 «++b % c == 0» 과 모든 int 에서 같다 — 0 < b < c 면 b % c == b ≠ 0 이라 나눗셈을 건너뛴다
            // (b <= 0 은 카운터가 int 를 넘어 음수가 된 때 — 재고판은 거기서도 배수마다 부른다)
            int b = ++s.bytecodes;
            if ((b >= c || b <= 0) && b % c == 0)
                callHook(s, COUNT_, NIL);
        }
        if (s.hookline) {
            int newline = f != null ? f.currentline() : -1;
            if (newline != s.lastline) {
                s.lastline = newline;
                callHook(s, LINE_, LuaValue.valueOf(newline));
            }
        }
    }
}
