package org.figuramc.figura.avatar;

/**
 * 아바타 해시의 출처. 다운로드 경로와 캐시 네임스페이스를 <b>동시에</b> 결정한다.
 *
 * <p>예전에는 {@code UserData.fromFSB} 라는 가변 boolean 이 이 역할을 했는데,
 * 그 플래그를 세우는 시점과 요청을 내보내는 시점이 분리돼 있어서
 * <b>FSB 가 발급한 해시를 들고 공식 클라우드를 치는</b> 버그가 있었다.
 * 공식 경로에는 해시 검증이 없어 공식이 준 레거시 아바타가 FSB 해시 이름으로
 * 캐시에 저장됐고, 그 뒤로는 캐시가 계속 그 파일을 돌려줬다.
 *
 * <p>출처를 값으로 넘기면 그 불일치가 표현 자체로 불가능해진다.
 */
public enum AvatarSource {
    /** FSB 서버 발급. 압축된 와이어 바이트의 SHA-256({@code Utils.getHash}) 이라 검증 가능하다. */
    FSB("fsb_"),
    /**
     * 공식 백엔드 userdata JSON 의 {@code "hash"}.
     * 규약이 비공개라 <b>절대 검증하지 않는다.</b> 접두어가 빈 문자열이라 기존 캐시 파일명이 그대로 유지된다.
     */
    OFFICIAL("");

    private final String cachePrefix;

    AvatarSource(String cachePrefix) {
        this.cachePrefix = cachePrefix;
    }

    /** 캐시 파일명 접두어. 두 출처가 같은 디렉터리를 쓰면서도 서로를 덮어쓸 수 없게 한다. */
    public String cachePrefix() {
        return cachePrefix;
    }
}
