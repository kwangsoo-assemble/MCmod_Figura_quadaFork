package org.figuramc.figura.model;

import org.luaj.vm2.LuaError;

import java.util.Locale;

/**
 * HUD 부모 파츠의 층 — 바닐라 GUI 묶음 «바로 위» 에 그린다 · 차례(ordinal) = 아래에서 위
 * 하네스 `projects/mc_repositories/design/HUD_GUI_층/plan.md` D4
 */
public enum HudLevel {
    BOTTOM,             // 모든 GUI 밑(지금까지의 HUD 자리 · 기본)
    CAMERA_OVERLAY,     // 비네트 · 망원경 · 호박 · 가루눈 · 포털 · 멀미
    CROSSHAIR,
    HOTBAR,             // 핫바 · 손 아이템 · 왼손 칸(관전 핫바)
    STATUS_BARS,        // 갑옷 · 하트 · 배고픔 · 공기 방울 · 탈것 체력
    EXPERIENCE,         // 경험치 · 위치 · 점프 바 · 경험치 숫자
    ITEM_NAME,          // 손에 든 아이템 이름 · 관전 동작 글
    EFFECTS,
    BOSS_BAR,
    SLEEP,
    DEBUG,              // 데모 · F3
    SCOREBOARD,
    ACTIONBAR,
    TITLE,
    CHAT,               // 포커스 안 된 채팅(HUD) — 채팅 창을 열면 바닐라가 HUD 채팅을 건너뛰고 화면에서 그린다
    PLAYER_LIST,
    SUBTITLES,
    SCREEN_BACKGROUND,  // 화면 흐림 · 어둡게 · 인벤토리 판
    CHAT_FOCUSED,       // 채팅 창 안 채팅 글 · 배경(입력칸 · 명령 제안 밑) · 다른 화면에선 배경 바로 위 · 화면 없으면 HUD 맨 위
    SCREEN,             // 화면 내용(상자 화면은 든 아이템 밑)
    SCREEN_CARRIED,     // 상자 화면에서 마우스로 든 아이템
    TOOLTIP,
    TOP;                // 토스트 · 저장 표시까지 모두 위

    public static final HudLevel LAST_HUD = SUBTITLES;
    public static final HudLevel LAST_SCREEN = TOOLTIP;

    /** 이름(대소문자 무시) → 층 · null → null(부모를 따름) · 모르는 이름은 Lua 오류 */
    public static HudLevel get(String name) {
        if (name == null)
            return null;
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new LuaError("Invalid hud level \"" + name + "\" — see the HudLevels list in the docs");
        }
    }
}
