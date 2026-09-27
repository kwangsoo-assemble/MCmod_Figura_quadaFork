# Notice

In this repository I've applied a number of changes to make the Figura mod behave the way I need it to
(integration with and control from a game server for producing video content for broadcast, replay compatibility, and so on).

Because of that, this Figura mod includes features that break the existing rules of the Figura community.
For example:
- A `netlock` command, added to keep avatars fixed in place while watching replays.
- A feature that lets you put any local avatar you choose on other players.

So please don't casually use this mod, or redistribute and share it.
It's meant for people who can analyze and understand the code — please use it or refer to it only if that's you.

I made this repository public simply because I thought sharing it might be nice.
After all, I'm just one of many Figura users, and I figured good things are worth sharing. :)
That said, if the Figura team asks me to take this repository down, I will.

A little aside — did you know?
According to Pew Research Center, 50% of Americans say they are more concerned than excited about AI,
while only 16% of South Koreans say the same.
Ha, I'm Korean myself, and I have to admit I'm pretty optimistic about AI.
Anyway, I asked the AI to document as transparently as possible where and how this fork differs from the
original project, so you'll find the changes listed below.
I don't think the AI wrote sloppy or potentially dangerous code, but just in case,
please be sure to review everything carefully yourself.

---

# figura_core_but_ai_edited

> An **AI-assisted fork** of [Figura](https://github.com/FiguraMC/Figura) for Minecraft **1.21.8 (Fabric)**.
> It brings the Figura 0.1.6 features to 1.21.8 and adds an **FSB (Figura Server Backend) client** — the server you are
> connected to relays avatars — along with replay support, client-side selectors and per-part glow.

> ⚠ **This is an unofficial fork** and is not affiliated with FiguraMC. Most changes were written with an AI coding
> assistant (Claude Code) and checked by the maintainer in real use. Before reporting a problem to upstream Figura,
> please make sure it is not caused by this fork.

## At a glance

| Item | Value |
|---|---|
| Minecraft | 1.21.8 |
| Loader | Fabric only (the Forge and NeoForge modules are excluded from the build) |
| Java | 21 |
| Mod version | `0.1.6-but-ai-edited.1` — a pre-release tag added to tell it apart from upstream 0.1.6 (see 11) |
| Based on | A 1.21.8 port of Figura 0.1.5, plus the Figura 0.1.6 (MC 1.21.4) features ported over |
| Companion server plugin | [figura_fsbplugin_but_ai_edited](https://github.com/kwangsoo-assemble/figura_fsbplugin_but_ai_edited) — **not wire-compatible with the official FSB** |

## Changes from upstream

Compared against upstream branch `1.21.8` at [`19195ab`](https://github.com/FiguraMC/Figura/commit/19195ab98ae71f76957fdaf95156781daad728ca).
The **Code** paths under each item are relative to `common/src/main/java/org/figuramc/figura/`, except those starting
with `fabric/` or `server-common/`, which are relative to the repository root. **(new)** marks files that upstream does not have.

### 1. Figura 0.1.6 features ported

Features added on upstream branch `0.1.6-dev/1.21.4` (MC 1.21.4) are carried over to 1.21.8.

- Updated settings and language entries
- Lua quality-of-life — the vector `__div` metamethod is static, clearer operator error messages
- `PermissivePath` — an OS-independent path implementation for resolving script and resource paths inside avatars
- Badge system
- **Rewritten Blockbench parser** (`BlockbenchParser2`) — V4 and V5 formats, lazily compiled keyframes
- Small upstream fixes that came along — the sign of the 2×2 matrix inverse, the environment argument of Lua `load()`,
  sizes shown in KiB, the error message for malformed links, Blockbench export format 4.10, and clearing a player's
  avatar when they join so it is fetched fresh
- 1.21.8 API changes — NBT getters returning `Optional` (`getXxxOr`), `ResourceLocation.fromNamespaceAndPath`,
  and the common `Minecraft.disconnect(Screen, boolean)` entry point

Code: `parsers/BlockbenchParser2.java` and 5 more new files in `parsers/` (the old `BlockbenchModel` and
`BlockbenchModelParser` are removed) · `animation/Keyframe.java` · `utils/PermissivePath.java` (new) ·
`commands/BadgeCommand.java` (new) · `lua/LuaTypeManager.java` · `math/` · `mixin/PlayerSocialManagerMixin.java` (new)

### 2. FSB (Figura Server Backend) client

Instead of the official cloud, **the server you are connected to relays avatar uploads, downloads and pings**.
The implementation from the official Figura repository's 1.20.6 branch (the diff against `35d1b591`, the last commit
before FSB) is ported to 1.21.8.

- Choosing where to upload — `Destination` (`BACKEND`, `FSB`, `BOTH`, `FSB_OR_BACKEND`)
- Upload and delete entries in the Wardrobe right-click menu
- FSB connection state in the status bar, and an FSB checkbox in the server edit screen
- Lua — the `server_packets` global (avatar scripts and server plugins exchange packets), plus
  `client:fsbConnected()`, `client:pingRateLimit()` and `client:pingSizeLimit()`
- The `server-common/` module — the shared FSB protocol. It is **the same source** as the companion server plugin

Code: `backend2/FSB.java` · `server/` · `lua/api/ServerPacketsAPI.java` (all new) · `backend2/NetworkStuff.java` ·
`gui/screens/WardrobeScreen.java` · `gui/widgets/StatusWidget.java` · `mixin/gui/EditServerScreenMixin.java` (new) ·
`mixin/ServerDataMixin.java` (new) · `lua/api/ClientAPI.java` · `fabric/…/backend2/` (new) · `server-common/` (new)

### 3. Server packets for other players' avatars (protocol change)

`CustomFSBPacket` gains an `avatarOwner` UUID. Upstream only lets the host (your own avatar) use `server_packets`;
now **other players' avatar scripts** can exchange packets with the server too.

- ⚠ Because of this change the fork is **not wire-compatible** with the official FSB server or client. Use it together
  with the companion server plugin.
- The server decides whether to allow it — server setting `allowNonHostPackets` (off by default).

Code: `server-common/…/packets/CustomFSBPacket.java` · `lua/api/ServerPacketsAPI.java` ·
`server/packets/handlers/s2c/S2CCustomFSBPacketHandler.java`

### 4. Flashback replay support

While watching a [Flashback](https://modrinth.com/mod/flashback) replay, recorded FSB server packets are still
delivered to avatars.

- An optional dependency attached through reflection. Everything works without Flashback.
- Server packets are handled when `connected() || isInReplay()`.

Code: `compat/FlashbackCompat.java` (new) · `backend2/FSB.java` · `server/packets/handlers/s2c/ConnectedPacketHandler.java`

### 5. FSB avatar distribution fixes

- The origin of an avatar hash is passed explicitly as an `AvatarSource` enum. Previously an FSB hash could be looked
  up on the official cloud and the answer cached under the FSB hash name, which **brought old avatars back**.
- The cache is split by origin (official cache file names are unchanged, so existing caches are kept).
- Two missing `return`s in `FSB.acceptDataChunk` are fixed.

Code: `avatar/AvatarSource.java` (new) · `avatar/UserData.java` · `avatar/local/CacheAvatarLoader.java` ·
`backend2/NetworkStuff.java` · `backend2/FSB.java`

### 6. Per-part vanilla glow (outline)

- Model part Lua methods `setGlow(bool)` and `setGlowColor(r, g, b)`, with getters `getGlow()` and `getGlowColor()` —
  `nil` follows the parent, a value overrides it (values are not multiplied together).
- Vanilla armor and items attached to a glowing part glow with it.
- In first person, the held item is drawn at the avatar's pivot (first-person armor is not supported yet).

![Per-part glow example](docs/images/part_glow.webp)

*Each part can have its own glow color (red, blue), and vanilla items held by a part glow in that part's color.*

Code: `model/FiguraModelPart.java` · `model/PartCustomization.java` · `model/rendering/` · `utils/RenderUtils.java` ·
`avatar/Avatar.java` · `mixin/render/` (level renderer, layer and held-item mixins) · `fabric/…/mixin/fabric/` (armor and level renderer mixins)

### 7. First-person rendering fixes

- Fixed `partToWorldMatrix()` freezing in first person for skull parts inside item displays, item frames and
  dropped items.
- In first person, hidden parts skip matrix calculation — the same behavior as third person.
- Fixed enchantment glint disappearing on parts that a shader pack draws pulled forward: the glint now gets the same
  depth rank and is pulled forward the same way. Vanilla items and armor are unaffected.

Code: `avatar/Avatar.java` · `fabric/…/mixin/fabric/LevelRendererMixinFabric.java` · `model/rendering/ImmediateAvatarRenderer.java` ·
`model/rendering/texture/RenderTypes.java`

### 8. New commands — `nonhost_load`, `nonhost_unload`, `netlock`

Put a local avatar on another player and keep it there — for cases like recording replays, where **someone else's
avatar has to stay fixed on your side**.

- `/figura nonhost_load <target> <path>` — puts a local avatar on the target and **pins** it. Cloud, FSB and websocket
  events can no longer overwrite it.
- `/figura nonhost_unload <target|all>` — removes the pin.
- `/figura netlock on|off|status` — stops loading avatars from the network entirely.
- Pins and the lock are released when you leave the world (or the replay).
- `<target>` accepts **client-side selectors** — `@s @p @r @a @e @n` with
  `[type, name, team, gamemode, distance, x, y, z, dx, dy, dz, x_rotation, y_rotation, limit, sort]`.
  Vanilla selectors are server-only and do not work in singleplayer or replays, so they are resolved from what the
  client knows (world entities, the tab list, synced teams). Conditions the client cannot know (`tag`, `scores`,
  `nbt`, …) produce an **error** instead of an empty result.
- Public API — `AvatarManager.loadLocalAvatarFor`, `unpinAvatar`, `isPinned`, `getPinnedAvatars`,
  `setNetworkLocked`, `isNetworkLocked`, `acceptsNetworkAvatar` and more.
- Hot-reload watching of local avatars now applies to the host's avatar only.

Code: `commands/NonhostLoadCommand.java` · `commands/NetlockCommand.java` · `commands/TargetArgumentType.java` ·
`utils/selector/` (all new) · `avatar/AvatarManager.java` · the network entry points that respect pins
(`avatar/UserData.java`, `backend2/`) · `mixin/MinecraftMixin.java` · `avatar/local/LocalAvatarLoader.java`

### 9. New command — `/figura reload all`

The same as the "Reload all" button in the permissions screen. It is also available as a command so that servers or
scripts can ask the client to reload.

Code: `commands/ReloadCommand.java`

### 10. UI

- The FSB connection state is shown with the same icon and color (green `+`) as the official cloud connection.

Code: `gui/widgets/StatusWidget.java`

### 11. Version number

To avoid confusion with the official 0.1.6, the version carries a [SemVer pre-release](https://semver.org/#spec-item-9) tag —
`0.1.6-but-ai-edited.1` (jar: `figura-0.1.6-but-ai-edited.1+1.21.8-fabric-mc.jar`). The last number is this fork's own release number.

- The wardrobe, F3 and `client:getFiguraVersion()` show the tagged version.
- **Comparisons** with upstream, backend and avatar versions use 0.1.6 without the tag. Under SemVer `0.1.6-…` is lower than
  `0.1.6`, so without this the official 0.1.6 would look like a newer version and trigger update notices and avatar version warnings.
- A mod that requires `figura >=0.1.6` will not accept this version (Fabric Loader compares by SemVer too) — use `>=0.1.6-` instead.

Code: root `gradle.properties` · `FiguraMod.java` (`FORK_PRERELEASE`, `COMPARE_VERSION`) · `avatar/Avatar.java` · `backend2/NetworkStuff.java` ·
`gui/screens/WardrobeScreen.java`

### 12. Mob (CEM) avatars — leak fix, new commands `/figura cem build` and `status`

Figura puts `assets/figura/cem/<ns>/<type>.nbt` from a resource pack on **every mob of that type** as an avatar (CEM).

- **Leak fix** — avatars of mobs that died or went away were never released and kept running tick and render events. The 1.21.8 client
  returns `null` for a removed entity, but the cleanup only checked `isRemoved()`. `null` now counts as gone too, and `clean()` is called
  (a mob that leaves tracking range is dropped as well, and recreated when it comes back).
- **`/figura cem build <folder>`** — compiles local avatar folders into CEM avatars. Meant for making several avatars, one per mob type, in one go.
  - `<folder>` is relative to the local avatar folder, like `/figura load`. If it has a `cem.json`, that folder is built;
    otherwise every direct subfolder that has one.
  - `cem.json` is `{"entity": "minecraft:husk"}` — one mob type or a list. If two folders name the same type, nothing is built.
  - Output goes to `figura/cem_out/<ns>/<type>.nbt` — copy it as-is under a resource pack's `assets/figura/cem/` (it never writes to a resource pack itself).
  - The written file is read back and **applied right away** — mob avatars of that type are recreated on the next render. A resource reload (F3+T) goes back to the resource pack version.
- **`/figura cem status`** — how many mob avatars are running right now, per type. Mob avatars load when first rendered and do not show up in F3, so this is how to see how many mobs actually have one.

Code: `avatar/AvatarManager.java` (CEM cleanup, per-type `clearCEMAvatars`) · `commands/CemCommand.java` (new) ·
`avatar/local/LocalAvatarLoader.java` (the compile inside `loadAvatar` moved out to `compileAvatarFolder`, the background queue `async` made public — same behavior)

Registration of the new mixins, commands and Lua APIs, and their doc strings, live in `figura-common.mixins.json`,
`commands/FiguraCommands.java`, `lua/FiguraAPIManager.java`, `lua/docs/` and `assets/figura/lang/en_us.json`.

## Building

JDK 21 is required (newer JDKs do not work with this Gradle version).

```
./gradlew :fabric:build
```

Output: `fabric/build/libs/figura-0.1.6-but-ai-edited.1+1.21.8-fabric-mc.jar`

Build setup changes: `settings.gradle` (Fabric and `server-common` only) · `gradle.properties` (version 0.1.6-but-ai-edited.1) · `build.gradle` ·
`common/build.gradle` · `fabric/build.gradle` · `fabric.mod.json`

## Related projects

- [figura_fsbplugin_but_ai_edited](https://github.com/kwangsoo-assemble/figura_fsbplugin_but_ai_edited) —
  the companion server plugin (Paper / Purpur 1.21.8). It holds the original of `server-common/`.
- [figura_sillyplugin_but_ai_edited](https://github.com/kwangsoo-assemble/figura_sillyplugin_but_ai_edited) —
  the SillyPlugin add-on built against this fork.

## License

**PolyForm Noncommercial 1.0.0**, the same as upstream (`LICENSE.md`) — noncommercial use only.
The upstream credits are in `CREDITS`, and the upstream README is kept as `README.upstream.md`.
