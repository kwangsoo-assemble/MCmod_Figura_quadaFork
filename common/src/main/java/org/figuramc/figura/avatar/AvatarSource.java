package org.figuramc.figura.avatar;

/**
 * Origin of an avatar hash. It decides the download path and the cache namespace <b>at the same time</b>.
 *
 * <p>This used to be a mutable boolean, {@code UserData.fromFSB}, but the point where that flag was set
 * and the point where the request was sent were separate, which caused a bug that
 * <b>queried the official cloud with an FSB-issued hash</b>.
 * The official path has no hash verification, so a legacy avatar served by the official backend was cached
 * under the FSB hash name, and from then on the cache kept returning that file.
 *
 * <p>Passing the origin as a value makes that mismatch impossible to even express.
 */
public enum AvatarSource {
    /** Issued by the FSB server: the SHA-256 ({@code Utils.getHash}) of the compressed wire bytes, so verifiable. */
    FSB("fsb_"),
    /**
     * The {@code "hash"} from the official backend's userdata JSON.
     * Its format is not public, so it is <b>never verified.</b> The prefix is empty, so existing cache file
     * names stay unchanged.
     */
    OFFICIAL("");

    private final String cachePrefix;

    AvatarSource(String cachePrefix) {
        this.cachePrefix = cachePrefix;
    }

    /** Cache file name prefix. Lets both origins share one directory without being able to overwrite each other. */
    public String cachePrefix() {
        return cachePrefix;
    }
}
