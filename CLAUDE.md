# Figura 0.1.6 (MC 1.21.8) — 커스텀 포크

Figura 0.1.5-1.21.8 포팅본을 베이스로 0.1.6 기능 + FSB(서버 클라우드) 클라이언트를 이식한
**자체 생태계 전용 포크**. 공식 Figura/FSB와 와이어 호환되지 않는다 (아래 프로토콜 변경 참고).

## 빌드

- **반드시 JDK 21**: `JAVA_HOME="C:\Program Files\Java\jdk-21"` 지정 후 `./gradlew :fabric:build`
  (시스템 기본 JDK 25는 Gradle 8.11과 비호환 — "Unsupported class file major version 69" 에러)
- Fabric 전용 (forge/neoforge는 settings.gradle에서 주석 처리됨)
- 산출물: `fabric/build/libs/figura-<mod_version>+1.21.8-fabric-mc.jar` — 버전은 `gradle.properties` (2026-10-09 지금 `0.1.6-but-ai-edited.1`) · 옛 이름 `figura-0.1.6+1.21.8-fabric-mc.jar` 는 낡은 산출물
- 짝꿍 서버 플러그인: `../figura_fsbplugin_internal` (플러그인과 **반드시 같이 배포** — 프로토콜 변경 시 특히)

## ★ GitHub 저장소 — 이 작업본을 그대로 올린다 (2026-10-10 — 🙋 규칙 변경)

이 폴더가 GitHub `kwangsoo-assemble/MCmod_Figura_quadaFork` 의 **작업본**이다 — 한글 주석 · 이 CLAUDE.md 까지 그대로 올라간다.
옛 «공개판» 사본(`figura_core_but_ai_edited` — 같은 코드에 주석만 영어)은 2026-10-10 에 없앴다 — 그 이력은 이 저장소에 합쳐 두었다.

- 매듭마다 로컬 커밋 → `python ../../../mc_content_production/projects/mc_repositories/tools/repos.py publish core` 로 푸시한다
  (세션이 자동으로 한다 · `--force` 금지 · 원격이 앞서 있거나 README 동기가 빨가면 멈춘다 — 하네스 `CLAUDE.md` 「외부 저장소 — mc_repositories」).
- README.md 는 영어판이고 **원본은 하네스의 한글판**이다 — `projects/mc_repositories/readme_ko/MCmod_Figura_quadaFork.md`. 기능을 바꾸면 거기 먼저 적고
  영어 README 에 옮긴 뒤 `repos.py mark-readme core`. 원본 README 는 `README.upstream.md`.
- 레포 이름 규칙 — `MCmod_` / `MCplugin_` + 프로젝트 이름 (+ 포크는 `_quadaFork`). `but-ai-edited` 는 **버전명에만** 쓴다.
- ★ **버전 · 릴리스** (🙋 2026-10-10 «기능 업데이트 할때마다 버전명 올리는 처리»): jar 에 들어가는 코드가 바뀌면 `gradle.properties` 의 `mod_version` 를 올린다 —
  포크는 꼬리 `but-ai-edited.N` 의 **N+1**(원본 버전이 바뀌면 원본을 따르고 `.1` 부터). 그 작업의 **첫 코드 수정과 함께** 올리고(같은 작업의
  시험 배포는 같은 버전) → 빌드 · 배포 · 🎬 → `repos.py release core`(태그 + GitHub Release 에 jar). 문서만 바뀌면 안 올린다.
  안 올리면 `repos.py status` 가 `VERSION_STALE` · 올리고 안 내면 `UNRELEASED` 로 빨갛다.
  ⚠ 코어 버전을 올리면 **Silly · Chat-Heads 의 `.libs/figura-<버전>+1.21.8-fabric-mc.jar` 참조**(빌드 설정)도 같이 바꾼다 · FSB ↔ 코어 비교는 꼬리를 뗀다(`FiguraMod.COMPARE_VERSION`)라 N 만 올려도 짝은 안 깨진다
- 원본 CI(`.github/workflows`) · 이슈 양식 · 후원 링크(`FUNDING.yml`)는 뺐다 — 우리 레포에서 돌면 안 된다(옛 공개판도 뺐다).
- `server-common/` 은 FSB 작업본(`../figura_fsbplugin_internal`)이 원본이다 — 고치면 여기로 복사한다(두 쪽이 다르면 통신이 깨진다).
- `kr/asmbl/figuracontroller/protocol/` 은 FiguraController(`../../plugins/FiguraController`)가 원본이다 — 거기서 고치고 복사한다.

## 모듈 구조

- `common/` — 본체. `org.figuramc.figura.server.*`(클라 FSB 글루 + s2c 핸들러 21종),
  `backend2/FSB.java`(FSB 클라 코어), `lua/api/ServerPacketsAPI.java`(`server_packets` Lua 전역)
- `server-common/` — FSB 공유 프로토콜(패킷/유틸). **원본은 FiguraFSB 프로젝트**이며
  수정 시 그쪽에서 고치고 이 프로젝트로 `src` 통째 복사 동기화한다 (양쪽 불일치 = 와이어 파손).
  루트 build.gradle에서 loom 제외 가드 처리됨 (순수 Java 모듈).
- `fabric/` — FSBFabric/FabricNetworking(PayloadTypeRegistry 등록), FiguraModFabric에 수신기 등록

## 적용된 업데이트 이력

1. **0.1.6 기능 이식** (0.1.6-1.21.4 → 이 프로젝트, 5단계): 설정/언어 → Lua QoL(Vec `__div` static화,
   연산자 에러메시지) → PermissivePath → 뱃지 → **Blockbench 파서 리라이트**(BlockbenchParser2, V4/V5
   포맷, Keyframe lazy compile)
2. **FSB 클라 이식**: 공식 repo 1.20.6 브랜치(pre-FSB 커밋 35d1b591 대비 diff로 델타 추출)에서 이식.
   NetworkStuff에 Destination(BACKEND/FSB/BOTH/FSB_OR_BACKEND) 업로드 분기, Wardrobe 업로드/삭제
   컨텍스트 메뉴, StatusWidget 5번째 상태(FSB 연결), EditServerScreen FSB 체크박스
3. **비호스트 서버패킷 확장(자체)**: `CustomFSBPacket`에 avatarOwner UUID 필드 추가.
   ServerPacketsAPI의 isHost 가드 제거 → 타 플레이어 아바타도 server_packets 송수신 가능
4. **Flashback 리플레이 호환(R1)**: `compat/FlashbackCompat`(리플렉션 soft-dep,
   `Flashback.isInReplay()`), `ConnectedPacketHandler` 가드를 `connected()||isInReplay()`로 완화
   → 재생 중 녹화된 FSB 서버패킷이 아바타에 정상 전달
5. **UI 자잘한 픽스**(`gui/widgets/StatusWidget.java`): FSB 연결 상태(인덱스 4)를 메인 클라우드
   연결과 **동일하게** 보이도록 — `STATUS_INDICATORS` 를 `"-*/+#"` → `"-*/++"`(아이콘 `+` 공유),
   `TEXT_COLORS` 인덱스 4를 BLUE → **GREEN**(툴팁 `status.backend.4` 색상).
   두 상수는 StatusWidget 안에서만 쓰이므로 여기만 고치면 된다.

6. **FSB 아바타 배포 결함 수정** (2026-09-04): `fromFSB` 가변 플래그를 없애고 **해시 출처를
   `AvatarSource` enum 으로 명시 전달**. 예전에는 `FSB.applyUserData` 가 `loadData` 를
   `fromFSB(true)` 보다 먼저 불러 **FSB 해시로 공식 클라우드를 치고**, 그 응답을 FSB 해시 이름으로
   캐시에 저장했다(= 레거시 아바타가 되살아남). 캐시는 `AvatarSource` 접두어로 네임스페이스 분리
   (`OFFICIAL` 은 빈 접두어 → 기존 공식 캐시 파일명 불변). `FSB.acceptDataChunk` 의 누락 `return` 2개도 수정.
   ⚠ **업스트림 병합 후 `grep -rn fromFSB` 가 0건인지 확인할 것** — 되살아나면 버그가 부활한다.
   정본: 하네스 `knowledge/shared/fsb_avatar_distribution.md`
7. **파츠별 바닐라 발광 + 1인칭 아이템 피벗** (2026-09-04):
   - `PartCustomization.glow`/`glowColor` (nil=상속·값=덮어쓰기, **`light`/`overlay` 패턴**).
     ⚠ `color` 처럼 곱셈 누적하면 안 된다 — 사용자 요구가 "간섭 없는 덮어쓰기" 다.
   - `ImmediateAvatarRenderer` 에 3번째 아웃라인 패스. Lua: `setGlow`/`setGlowColor`(+게터).
   - `LevelRenderer.collectVisibleEntities` 반환값을 믹스인으로 열어 `entity_outline` 후처리를 강제.
     ⚠⚠ **`shouldEntityAppearGlowing` 은 절대 건드리지 마라** — `OutlineBufferSource` 로 감싸이면
     `setColor` 가 no-op 이라 **파츠별 색이 팀 색 하나로 뭉개진다.**
   - 바닐라 갑옷·아이템은 `RenderUtils.pivotGlowBuffer`/`vanillaPartGlowBuffer` 로 감싸 아웃라인에 흘린다.
   - 1인칭: `LevelRendererMixinFabric` 이 월드 패스 뒤 아이템 피벗 큐를 소비해 손 아이템을 그 자리에 그린다.
     (**갑옷은 미구현** — 1인칭은 `LivingEntityRendererMixin.preRender` 가 `ci.cancel()` 로
      레이어 루프를 통째로 막고, `calculatePartMatrices` 에는 `savePivotTransform` 이 없다)

   ★★ **`RenderUtils.figura$inLevelPass`** — 아웃라인 타겟 유효 구간 가드.
   `avatar.renderMode` 로 판정하면 **안 된다**: Iris 가 `renderEntities` 를 대체하면
   `renderMode = RENDER` 를 세팅하는 `@ModifyArg` 가 발화하지 않아 레이어 시점에 `OTHER` 로 남는다
   (인게임 실측). 이 가드를 빼면 GUI·HUD 에서 **NPE 크래시**다.

8. **1인칭 스컬 `partToWorldMatrix` 좌표 멈춤 수정** (2026-09-11):
   `Avatar.skullRender` 가 `allowMatrixUpdate` 를 **직전 패스에서 물려받고** 있었다.
   3인칭은 엔티티 루프가 내 플레이어를 그리며 `worldRender()` 가 true 로 켜 두어 우연히 맞았고,
   1인칭은 `collectVisibleEntities` 가 카메라 엔티티를 제외해(`!isDetached()`) 그 지점이 아예 없다
   → `renderEntities` 안에서 그려지는 스컬(**아이템 디스플레이**·아이템 프레임·드롭 아이템)의
   `savedPartToWorldMat` 이 통째로 멈춰 직전 3인칭 값에 박제됐다.
   ★★ 같은 1인칭이어도 스컬 **블럭**은 멀집하게 동작했다 — `renderBlockEntities` 가
   `firstPersonWorldRender`(allowMatrixUpdate 를 true 로 켜는 곳, `checkPoseStack` ordinal 0) **뒤**라서다.
   증상이 반쪽만 나타나 오진하기 쉽다.
   ⇒ 이제 물려받지 않고 **`RenderUtils.figura$inLevelPass`** (레벨 메인 패스 안인가)로 직접 판정한다.
   ⚠ GUI·인벤토리·워드로브·1인칭 손 아이템은 그 밖이라 **예전처럼 갱신하지 않는다** —
   그 포즈스택은 카메라 상대 월드 좌표가 아니라 쓰면 월드 위치가 오염된다.
   (`LevelRenderer` 에 `renderItemInHand` 호출이 없음을 바이트코드로 확인 — 손은 `GameRenderer` 쪽)
   ⚠ 공개 API 변경 없음 → 애드온 재빌드 불필요.

9. **1인칭 프롭의 글린트가 안 보이던 문제 수정** (2026-09-14, ✅ 인게임 검증 완료):
   글린트 3종은 전부 `RenderPipelines.GLINT` 을 쓰고, 그 파이프라인은
   **`EQUAL_DEPTH_TEST` + `POSITION_TEX`(색 속성 없음)** 이다. 그런데 템플릿이 1인칭 프롭
   큰브에 앞렌더 마커 `#FFFFFB` 를 칠하고, 사내 BSL 포크가 `gbuffers_entities.vsh` 에서
   그걸 보고 **본체의 깊이만** `z*0.001` 로 당긴다 → 글린트의 EQUAL 이 영원히 불일치 → 전량 discard.
   ★ 즉 *"1인칭이라서"* 가 아니라 *"앞렌더 마커가 칠해진 파츠라서"* 다 (3인칭 main 모델은 마커가 없다).

   **최종 해법 — 깊이를 버리지 말고, 순위를 글린트 프로그램에도 전달해 같은 식으로 당긴다.**
   통로는 **글린트 텍스처 행렬의 빈 칸**(`m32` = z 이동)이다.
   - 바닐라 글린트 셰이더는 `(TextureMat * vec4(UV0,0,1)).xy` 만 쓴다 → **행 2는 버려진다.**
     `setupGlintTexturing` 은 `translation(-f,g,0)` → `rotateZ` → `scale` 뿐이라 그 칸은 **항상 0**.
   - 모드: `FiguraRenderType.frontGlintTexturing` 이 **바닐라 상태를 먼저 돌린 뒤** 그 행렬을 복사해
     `m32 = layer` 만 찍는다. ⚠ 스크롤 속도·각도를 직접 베끼지 마라 — 모양이 갈라진다.
   - 셰이더팩 `gbuffers_armor_glint.vsh`: `int frontLayer = int(gl_TextureMatrix[0][3][2] + 0.5);`
     이후 `gbuffers_entities.vsh` 와 **상수·식이 동일한** 압축을 건다.
   - ★ **파이프라인은 바닐라 `RenderPipelines.GLINT` 인스턴스 그대로** 쓴다 —
     Iris 가 `coreShaderMap` 에서 인스턴스로 바로 찾아 **매칭 문제가 원천적으로 없다.**
     `assignPipeline` 도 자체 코어 셰이더도 필요 없다.
   - 순위 1~3 × 글린트 3종 = 렌더타입 9개. ⚠ 행렬은 **배치 단위**라 순위별로 갈라져야 한다.
   ⚠ 발동 조건은 *마커색 + 셰이더팩 켜짐* 둘 다 — `RenderTypes#frontLayerGlint`.
   ⚠ 판정은 **`customization.color`(primary 슬롯)** 으로 한다 (깊이를 당기는 것은 *본체* 의 정점색).
   ⚠ 바닐라 아이템·갑옷은 그 칸이 0 이라 **영향 없다.**

   ★★ **두 번 틀렸다 — 둘 다 되돌리지 마라.**
   ① **깊이 테스트를 `NO_DEPTH_TEST` 로 끄는 방식** — 인게임에서 글린트 뒷면까지 가산돼
     본체를 덮었고, 같이 당겨진 다른 프롭 파츠에 가려야 할 부분이 안 가려졌다.
     깊이를 무시하는 순간 가림 관계가 통째로 사라진다 (컴링만 켜서는 둘째가 안 잡힌다).
   ② **정점 포맷을 `POSITION_TEX_COLOR` 로 올려 `gl_Color` 로 보내는 방식** —
     Iris `ShaderKey.GLINT` 에 정점 포맷이 **`POSITION_TEX` 로 박혀 있어**, 포맷이 다르면
     `findBestMatch` 가 **프로그램 id 만 맞추는 마지막 단계**로 떨어진다.
     그 경우 **Color 가 바인딩되지 않아 `gl_Color` 가 항상 흰색**이라 분기가 죽는다.
     ⇒ 진단 단서는 로그의 `Found *decent* program match for ...` 경고 한 줄이다.
     (`"perfect" → "okay" → "fine" → "*decent*"` 순으로 내려간다. **decent = 포맷 불일치**)

   셰이더 계약 정본: 하네스 `knowledge/misc/shaderpack_system.md` §3.3
   (앞렌더 분기가 **entities·block·armor_glint 세 파일**이라는 사실과 압축식 일치 규칙 포함).

10. **`nonhost_load` pin + `netlock` — 네트워크 아바타 덮어쓰기 방지** (2026-09-22, 🙋 리플레이 실측 요구):
    `/figura nonhost_load <p> <path>` 로 남에게 로컬 아바타를 입혀도 **몇 프레임 뒤 클라우드 아바타가 덮어썼다.**
    - ★★ 원인 ①: 옛 구현(SillyPlugin)이 `clearAvatars()` 만 부르고 **`FETCHED_USERS` 표식을 되돌리지 못했다**(비공개).
      다음 렌더의 `getAvatarForPlayer` → `fetchBackend` 가 백엔드를 다시 쳤다. 호스트의 `loadLocalAvatar` 는
      clear 직후 `FETCHED_USERS.add` 를 하므로 멀쩡했다 — **같은 순서를 남에게도** 적용해야 했다.
      (`set_avatar` 디버그 명령도 같은 결함이었다 — 같이 고침)
    - 원인 ②: 그 뒤로도 웹소켓 `event`(reloadAvatar) · FSB `S2CConnected`/`S2CNotify`(clearAvatars) · 비동기 응답
      (`UserData.loadData` / `loadAvatar`) 이 언제든 다시 덮는다. **리플레이는 녹화된 FSB 패킷을 재생·탐색마다 되풀이**한다.
    - 해법 — `AvatarManager` 에 두 겹의 스위치. **네트워크 진입점은 전부 `acceptsNetworkAvatar(uuid)` 를 본다**
      (`UserData.loadData` · `NetworkStuff.getAvatar` 콜백 · `FSB.AvatarInputStream` 완료 · `clearAvatarsFromNetwork` ·
      `reloadAvatarFromNetwork`). 캐시 저장은 그대로 하고 **적용만** 막는다.
      · **pin** (사용자별) — `loadLocalAvatarFor(uuid, path)` 가 건다. 무엇이 지워도 `fetchBackend` 가 pin 된 경로에서 되살린다.
        `reloadAvatar(pinned)` 는 그 경로를 다시 컴파일한다(호스트 `!localUploaded` 와 같은 뜻).
      · **netlock** (전역) — `/figura netlock on|off|status`. 새 userdata 요청을 보내지 않고 네트워크발 삭제·리로드를 무시한다.
      · 둘 다 **월드를 나갈 때만** 풀린다 (`MinecraftMixin.clearLevel` → `resetPinsAndLock`). Flashback 종료도 `Minecraft.disconnect`
        를 타므로(jar 바이트코드 `MixinMinecraft` 확인) 리플레이를 나가면 풀린다.
        ⚠ `clearAllAvatars()` 는 풀지 **않는다** — FSB 핸드셰이크·권한 화면·`/figura reload all` 이 in-world 에서 부르기 때문.
    - ★ `LocalAvatarLoader.loadAvatar(path, target)` — **핫리로드 감시는 호스트 전용**이 됐다. 옛 구현은 남의 아바타를 컴파일하며
      `resetWatchKeys()` 로 호스트 감시를 끊고 남의 폴더를 감시했다 → 그 폴더를 고치면 `tick()` 이 **호스트 경로를 호스트에게** 다시 입혔다.
    - 명령은 코어로 **이관**: `commands/NonhostLoadCommand`(`nonhost_load` · `nonhost_unload <p|all>`) · `commands/NetlockCommand`.
      SillyPlugin 에서 제거(`SillyCommands.nonhostLoad` + 접근자 믹스인 2개). ⚠ **양쪽에 두면 안 된다** — Brigadier 가 같은 이름 리터럴을
      합치고 **나중 등록(SillyPlugin)이 이겨서** 버그가 부활한다.
    - 공개 API 추가(`AvatarManager.loadLocalAvatarFor/unpinAvatar/isPinned/getPinnedAvatars/setNetworkLocked/isNetworkLocked/
      acceptsNetworkAvatar/clearAvatarsFromNetwork/reloadAvatarFromNetwork/resetPinsAndLock`) → SillyPlugin `.libs` 재복사 + 재빌드 필요(했음).
    - **셀렉터** (🙋 같은 날 추가 요구 — *"`@a` 같은 엔티티 셀렉터 구성 가능해?"*): `nonhost_load`/`nonhost_unload` 의 target 에
      `@s @p @r @a @e @n` + `[type name team gamemode distance x y z dx dy dz x_rotation y_rotation limit sort]` 를 받는다.
      ★ **바닐라 셀렉터는 어디서도 못 쓴다** — `EntitySelector.findEntities` 가 `CommandSourceStack`(서버)을 요구하고, 클라 명령은
      싱글에서도 통합 서버(다른 스레드·다른 월드)에 못 닿으며, Flashback ReplayServer 에는 관전자뿐이다(엔티티는 클라 재구성).
      ⇒ 문법만 빌려 **클라가 아는 것**으로 푼다: `utils/selector/SelectorSpec`(순수 파서·판정, brigadier 만 의존 —
      오프라인 테스트 60건 통과) + `ClientSelector`(후보 수집: 월드 엔티티 + **탭리스트에만 있는 플레이어**, gamemode 는 `PlayerInfo`,
      team 은 동기화된 스코어보드). 클라가 모르는 `tag scores nbt level advancements predicate` 는 **에러**로 막는다(조용한 빈 결과 금지).
      `commands/TargetArgumentType` 이 `@a[limit=2, sort=nearest]` 처럼 대괄호 안 공백을 통째로 읽는다(`word()` 는 공백에서 끊겼다).
      ⚠ 아바타는 **플레이어에게만** 붙는다 — `@e` 가 고른 몹은 건너뛰고 개수를 알린다. `@a` 는 바닐라처럼 **본인 포함**
      (`@a[name=!내이름]` 으로 뺀다). 셀렉터 부피 `dx` 는 바닐라와 같이 `[x, x+dx+1)` 이다.

11. **블록 외곽선 색 인자 순서 복구** (2026-09-25, `2dd5b0a`, ✅ 인게임 확인): `LevelRendererMixin.renderHitOutline` 이
    `ARGB.colorFromFloat` 에 (r, g, b, a) 로 넘겼다 — 1.21.8 시그니처는 **(alpha, red, green, blue)** 다.
12. **버전 프리릴리스 꼬리** (2026-09-26, `79d35ee`, ✅ 인게임 확인): `0.1.6-but-ai-edited.1` (업스트림 요청 — semver 프리릴리스로 구분).
    **비교는 꼬리를 뗀 판**으로 한다(`FiguraMod.COMPARE_VERSION` — `Avatar.getVersionStatus` · `NetworkStuff.checkVersion` · 옷장) —
    SemVer 로 `0.1.6-x < 0.1.6` 이라 그대로 두면 공식 0.1.6 을 새 버전으로 본다. ⚠ 의존 조건은 `>=0.1.6-` 로 적는다(Chat-Heads 가 그렇게 바뀌었다).
13. **몹(CEM) 아바타 — 누수 수정 + `/figura cem build`** (2026-09-27, 하네스 `projects/entity_avatar_template` P1 · P2′):
    - 누수(`c6c3c84`): 1.21.8 클라는 지운 엔티티를 조회 목록에서 바로 빼서 `getEntity` 가 **null** 을 준다 — 정리 조건이
      `isRemoved()` 뿐이라 죽은 몹의 아바타가 `LOADED_CEM` 에 남아 계속 돌았다. null 도 사라짐으로 보고 `clean()` 까지 부른다.
    - `/figura cem build <폴더>` (`commands/CemCommand`): `<폴더>`(또는 바로 아래 하위 폴더마다)의 `cem.json`
      `{"entity": "<종류>" | [...]}` 을 읽어 컴파일 → `figura/cem_out/<ns>/<type>.nbt` → **그 파일을 다시 읽어** `CEM_AVATARS` 에 넣고
      그 종류의 `LOADED_CEM` 만 내린다(`AvatarManager.clearCEMAvatars(types)` 새 오버로드). F3+T 하면 리소스팩 판으로 돌아간다.
      리소스팩에는 **안 쓴다** — 배치는 하네스 배포 도구 몫(외부 변경 가드).
    - `/figura cem status`: 종류별로 지금 돌고 있는 CEM 아바타 수(`AvatarManager.countCEMAvatars`).
      ★ 몹 아바타는 **처음 그려질 때** 로드되고 **F3 에는 안 나온다**(`DebugScreenOverlayMixin` 은 로컬 아바타 줄뿐) ·
      `/figura debug` 도 로컬 아바타만 적는다 — 몇 마리에 실제로 붙었는지 · 누수 수정이 도는지는 이것으로만 본다.
    - `LocalAvatarLoader.loadAvatar` 안의 컴파일을 `compileAvatarFolder(folder, owner, trackState)` 로 **떼어 냈다**(동작 같음 —
      `trackState=false` 면 옷장의 로드 단계 표시를 안 건드린다). 컴파일은 `async` 큐에서 돈다(**public 으로 바꿈**) —
      `LuaScriptParser` 가 정적 `error` 플래그를 가져 컴파일이 겹치면 안 된다.
    - ⚠ 인게임 확인은 B0(몹 아바타 부하 측정) 때 — 그때까지 몹 아바타가 없다.
14. **FSB 스트림 시작 패킷 수신기** (2026-09-28, 하네스 `projects/mc_repositories`): 서버가 아바타 스트림마다 먼저 보내는
    `figura:s2c/stream/init`(`S2CInitializeAvatarStreamPacket`)은 **원본 1.20.6 부터 클라 수신기가 없었다** — 형식만 등록돼 있어
    바닐라 `ClientPacketListener.handleUnknownCustomPayload` 가 아바타를 받을 때마다 `Unknown custom packet payload` WARN 을 찍었다.
    전달은 멀쩡했다 — 받을 자리는 `FSB.getAvatar` 가 요청 **전에** streamId 로 만들고, 데이터 조각이 그 id 로 붙는다
    (2026-09-28 실측: 경고 10번 모두 같은 초에 `fsb_<해시>.nbt` 저장 · 서버 원본과 내용 같음).
    - `S2CInitializeAvatarStreamPacketHandler`(새)가 받기만 하고 디버그 로그를 남긴다. 클라 전용 — `server-common` · 와이어 · 공개 API 변화 없음(애드온 재빌드 불필요).
    - ⚠ 실린 ehash 로 스트림 끝 소유권 검사(`FSB.AvatarInputStream` — 원본부터 비교값을 자기 키로 채워 늘 참)를 되살리지 말 것.
      자기 아바타 소유권은 `applyUserData` 가 이미 본다 · 서버 소유 기록이 장착 기록과 어긋나면 자기 아바타가 거부되는 경로만 생긴다.
15. **몹(CEM) 아바타 — 바닐라 통째 숨김 · 이름표 숨김** (2026-09-28, 하네스 `projects/entity_avatar_template` P4 · P5):
    - P4 (`LivingEntityRendererMixin`): 몹 아바타가 `vanilla_model.ALL` 을 숨기면(`getVisible() == false`) 바닐라 **몸**(`renderToBuffer`)과
      **모든 레이어**(`shouldRenderLayers`)를 호출 자리에서 건너뛴다. 전에는 파츠 숨기기(`VanillaModelProvider`)라 사람형 모델만 숨었고
      드라운드 · 스트레이 · 보그드의 겉옷(`DrownedOuterLayer` · `SkeletonClothingLayer` — 자기 모델을 가진 레이어)과 주민 같은 비사람형 몹은 남았다
      (🙋 2026-09-28 인게임: «스트레이 · 보그드 · 드라운드는 겉레이어가 안 숨는다»). 그림자 · 이름표 · 끈 · 아바타 파츠(피격 붉은빛)는 그대로.
      플레이어 아바타는 안 바꾼다(`PlayerRenderState` 제외). ⓘ 원래 있던 `@ModifyArg customOverlay` 는 같은 호출을 두 번 잡지 않으려고 `@WrapOperation` 안으로 합쳤다(동작 같음).
    - P5 (`EntityRendererMixin.setupAvatar`): 몹 아바타의 `nameplate.ENTITY:setVisible(false)` 가 이제 먹는다 — 전에는 `PlayerRendererMixin` 만
      `visible` 을 봐서 몹 이름표는 못 숨겼다. 조건은 플레이어 쪽과 같다(설정 `entity_nameplate` > 0 · panic 아님 · 이름표 편집 권한).
    - 확인: `:fabric:build` 성공(JDK 21) · refmap 에 두 대상의 1.21.8 중간 이름(`method_62100` · `method_62483` · `method_3926`) · 믹스인 처리기 새 경고 0.
      ⚠ 인게임 확인 전(배포는 🙋 승인 뒤).
16. **글 태스크 · 이름표 바깥선이 안 보이던 것 · 바깥선을 켜면 `setOpacity` 가 안 먹던 것** (2026-10-07, 하네스 `projects/부라더_부캉이` BR115 ·
    `projects/mc_repositories` M13 — `de1f11f` · 2026-10-07 07:30 두 프로필 배포 · 공개판 `9cc2ff8` · ⚠ 인게임 확인 전):
    - ★★ 원인 — 1.21.8 `Font.drawInBatch8xOutline` · `Font$PreparedTextBuilder` 는 받은 색의 알파를 **그대로** 쓴다(`getTextColor` = `ARGB.color(ARGB.alpha(color), 스타일색)`).
      옛 판의 `adjustColor`(알파 0 → 불투명)가 없어졌다. `setOutlineColor` 는 `ColorUtils.rgbToInt` 로 **알파 0** 색을 만들고 기본값 `0x202020` 도 알파 0 이라
      바깥선 글리프가 `rendertype_text.fsh` 의 `color.a < 0.1 → discard` 에 다 버려졌다. 게다가 `TextTask` 는 8벌 호출의 본문 색을 `-1` 로 고정했다.
      (javap — loom 캐시 1.21.8 jar. GUI 는 같은 계약인데 `GuiGraphics.drawString` 이 알파 0 이면 곧바로 return 한다)
    - `TextTask.render` — 바깥선 색 = (글 알파 ^ `OUTLINE_ALPHA_POW`(8)) + 바깥선 RGB · 본문 = `op` · 시스루면 8벌 호출의 본문은 알파 0(본문은 시스루 호출이 한 번만 그린다).
      ★ 곡선 까닭 — 8벌이 본문 **밑에** 겹쳐(글자 속을 거의 다 덮는다) 반투명 글 속이 바깥선 색으로 탁해진다. 그리는 순서로 막는 길은 Iris 일괄 그리기 · HUD 에서 못 믿는다
    - `EntityRendererMixin.drawWithOutline` — 이름표 바깥선 색에 본문 색(바닐라 `-1`)의 알파를 붙인다
    - GUI 는 이식 때 `UIHelper.adjustColor` 로 이미 보정돼 있다(모든 `drawString` · `renderOutlineText`) — 해당 없음
    - ⓘ `glow_outline_fix`(한글 바깥선 두께 — 같은 8벌 람다 `method_37297` 를 감싼다)가 이제 글 태스크 · 이름표에도 같이 먹는다
    - ⚠ 기본 바깥선 `0x202020` 은 R=0x20 — 사내 코어 텍스트 셰이더는 R ≤ 0x2F 를 효과 비트로 읽는다(하네스 `knowledge/shared/core_effects.md` §1) → 사내 아바타는 R ≥ 0x30 색을 준다
    - 공개 API 변경 없음 → 애드온 재빌드 불필요 · Lua 문서 `text_task.set_opacity` 에 곡선 한 줄
    - 확인: `:fabric:build` 성공(JDK 21) · 믹스인 처리기 새 경고 0 · 재매핑 jar javap(8벌 호출 인자 · `ARGB` → `class_9848`) · 배포 md5 `a875efed…` = 빌드

17. **`entity:getVariable` · `world.avatarVars()` — 깊은 복사를 «읽기 전용 뷰» 로** (2026-10-09, 하네스 `projects/figura_controller` FC4 · ⚠ 인게임 확인 전):
    - ★★ 원인 — 두 API 가 `new ReadOnlyLuaTable(storedStuff)` 를 **부를 때마다** 만들었고, 그 생성자는 원본 전체를 재귀로 깊은 복사한다.
      키 하나(`getVariable("hitboxes")`)를 읽어도 그 아바타가 store 한 것 전부를, `avatarVars()` 는 **모든 아바타의 것 전부**를 복사했다.
      템플릿 아바타는 클릭마다(히트박스 · 입력 · 래그돌 잡기) `world.avatarVars()` → `pairs` → `store["hitboxes"]` 를 돈다.
      (오프라인 실측: 값 10만 개짜리 store 에서 키 하나 — 원본 읽기 118,007 → 2 · 할당 3.1 MB → 1.3 KB · 1.2 ms → 73 ns)
    - `lua/ReadOnlyLuaView`(새) — 원본을 **복사하지 않고** 감싼다. 읽기(rawget · get · next · inext · 길이 · keys)는 원본에 raw 로 맡기고
      테이블 값만 그 자리에서 뷰로 감싼다(게으르게). 쓰기(set · rawset · hashset · insert · remove · setmetatable)는 `table is read-only`.
      같은 뿌리 안에서는 같은 원본에 같은 뷰를 준다(`t.a == t.a` · 순환) — 캐시는 키 · 값 모두 약한 참조라 쌓이지 않는다.
      ★ 덤 — 옛 복사는 두 단 이상 순환(`a.b.a == a`)에서 StackOverflowError 였다. 뷰는 게으르니 문제없다.
    - ⚠ **의미 변화 — 스냅샷이 아니라 살아 있는 뷰다.** 들고 있으면 그 아바타가 나중에 바꾼 store 가 보인다.
      그 아바타가 리로드되면 새 저장소가 생기므로 다시 불러야 한다 · 읽는 쪽이 `pairs` 도중 주인 함수를 불러 주인이 키를 지우면 `next` 가 오류를 낼 수 있다(스냅샷엔 없던 일).
      원본 메타테이블은 옛 사본처럼 안 본다(raw) · `table.insert(t, nil)` 이 이제 오류(옛 클래스는 조용히 넘어감).
    - 다른 `ReadOnlyLuaTable` 사용처(Config · 어깨 NBT · Http · BlockState · ItemStack · LuaUtils · 문자열 메타테이블)는 그대로.
    - 공개 API 시그니처 변화 없음(클래스 추가뿐) → 애드온 재빌드 불필요.
    - 확인: `:fabric:build` 성공(JDK 21 · 믹스인 경고는 기존 2개뿐) · LuaJ 3.0.8-figura 소스로 재정의 대상을 확정(모드에 묶인 jar 와 클래스 동일) ·
      오프라인 시험 72/72(Lua 53 · Java 19 — pairs · ipairs · next · # · 중첩 · rawget · concat · unpack · 쓰기 13종 오류 · 살아 있는 반영 ·
      동일성 · 순환 · 키 하나 읽기 비용 · 캐시 누수 없음) — 시험 원본은 하네스 세션 스크래치(`viewtest/`)였다
    - 산출물 `figura-0.1.6-but-ai-edited.1+1.21.8-fabric-mc.jar` md5 `bc97e650…`
18. **`server_data` — 서버 플러그인 FiguraController 가 주인인 상태 · 신호를 코어 저장소로** (2026-10-10, 하네스 `projects/figura_controller` T2 · T4 · T8 배포 · ✅ 인게임 확인 — 19 를 고친 뒤 혼자 1 ~ 7(처음 모습 · set · 래그돌 · 히트박스 · 투명 · `/rc` · Flashback 되감기) · 여럿이 보는 것은 아직):
    - 규약 `figuracontroller:v1` — FSB `CustomFSBPacket`(`figura:ping/server`)의 `id` = `"figuracontroller:v1".hashCode()` · 몸 = 이진 op 줄.
      코덱 `common/src/main/java/kr/asmbl/figuracontroller/protocol/` 은 **플러그인 저장소(`plugins/FiguraController`)가 원본 · 여기는 그대로 사본** — 고치지 마라(하네스 `verify_codec.py` 가 대조)
    - `serverdata/ServerDataModel`(순수 — 패킷 적용 · «처음 건드리기 전» 기록 · 꺼내기 · Flashback 스냅샷) · `serverdata/ServerDataStore`(Minecraft 연결 — 가로채기 · 이벤트 나누기 · HELLO · 신호 보내기) · `serverdata/JsonLua`
    - `S2CCustomFSBPacketHandler` — 우리 id 면 **아바타보다 먼저** 저장소로(아바타 `server_packets` 에는 안 간다)
    - Lua `server_data`(`lua/api/ServerDataAPI` · `FiguraAPIManager` · 문서 `en_us.json` · `FiguraDocsManager` · `FiguraGlobalsDocs`):
      `get(name)` · `get(entity|uuid, name)` — «자기 값 없으면 전역» · `getGlobal` · `getAll([entity|uuid])`(읽기 전용 · 부를 때마다 새 표) · `watch` · `unwatch` ·
      `send(name, data)`(호스트 플레이어 아바타만) · 이벤트 `STATE_CHANGED(name, new, old, subject, initial)` · `SIGNAL(name, data, subject)`
    - 이벤트는 패킷마다가 아니라 **다음 클라 틱 시작**(`AvatarManager.tickLoadedAvatars` 맨 앞 → `Avatar.queueLuaCall` — 핑과 같은 대기열 · 틱 예산)에 «처음 ↔ 지금» 비교 —
      RESET 뒤 FULL(초기화 · 되감기)은 헛변화가 없다 · 같은 값이면 안 낸다(바이트 비교 — 1 ≠ 1.0) · 아바타가 실리면(`ENTITY_INIT` 직후) 지금 값을 `initial = true` 로 한 번
    - HELLO — `FSB.handleHandshake`(처음 · 다시 악수) · 비우기 — `FSB.onDisconnect` · `MinecraftMixin.clearLevel`(월드 · 리플레이 나감)
    - Flashback — `mixin/compat/FlashbackRecorderMixin`(`@Pseudo` · `writeCustomSnapshot` HEAD)이 녹화 스냅샷에 RESET · 이름 사전 · 전역/대상 FULL 을 넣는다
      (하네스 T1 스파이크 실측: 스냅샷 커스텀 페이로드는 되감기마다 돌아온다 · Render thread)
    - `AvatarManager.getLoadedCEMAvatars()` 추가 · 공개 API 는 추가뿐 → 애드온 재빌드 불필요
    - 확인: `:fabric:build` 성공 · 하네스 `verify_core_serverdata.py` — 순수 모델 29 + **서버 플러그인 → 패킷 → 이 모델 무작위 이어 붙이기**(씨앗 20 · 대조 96만 · 실패 0 —
      그 시험이 플러그인의 «같은 틱 보기 시작 → 끝» 결함을 찾았다) · ⚠ 아바타에 나누는 규칙 · Lua API 는 인게임(T8)
    - 서버 플러그인이 없는 서버: HELLO 는 FSB 가 리스너 없이 버린다 · 우리 id 패킷이 안 오니 아무 일도 없다(안전)
19. **`server_data.STATE_CHANGED` · `SIGNAL` 이 Lua 에서 nil 이던 것** (2026-10-10, 하네스 `projects/figura_controller` T8 인게임 첫 실측 · `d98e5a2` · ✅ 인게임 확인 — 배포 md5 `81ff87ee…`):
    - 원인: `LuaTypeManager` 는 화이트리스트 **메서드만** 메타테이블에 싣는다 — `@LuaWhitelist` **필드**는 그 클래스가 `__index` 를 가져야 보인다
      (`events` · `host` · `renderer` · `nameplate` … 코어의 필드 있는 19 클래스가 전부 그렇게 한다). 18 의 `ServerDataAPI` 만 `__index` 가 없었다
    - 고침: `ServerDataAPI.__index(key)`(대소문자 무시 — `events` 와 같다) · `__newindex`(대입하면 처리기 — `function server_data.STATE_CHANGED(...)`) · 문서 `server_data.__index.comment1`
    - ★ 18 의 확인은 Java 모델뿐이었다 — 하네스 `verify_core_serverdata.py` 에 ③ «Lua 표면»(코어 전체 정적 — 필드 있는 클래스는 `__index` 필수) · 자가 점검으로 이 결함을 되살리면 잡는다

## 1.21.8 API 어댑트 포인트 (이식/수정 시 주의)

- NBT: 1.21.5+에서 getter가 Optional 반환 → `getXxxOr(key, default)` / `.get()` 패턴
- `ResourceLocation` 생성자 제거 → `ResourceLocation.fromNamespaceAndPath(...)`
- `Minecraft.disconnect()`(무인자) 제거 → 믹스인은 공통 진입점 `disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V` 사용
- 믹스인 타겟 검증: loom 캐시 매핑 `~/.gradle/caches/fabric-loom/1.21.8/loom.mappings.../mappings.tiny`에서
  named 메서드 존재를 grep으로 사전 확인 가능

## 리플레이(Flashback) 동작 요약

- Flashback은 **수신 S2C 패킷만** 녹화 → FSB 서버패킷/핑은 기록됨, 로컬 실행(비-sync 본인 핑)은 기록 안 됨
- 재생 시 Flashback이 커스텀 페이로드를 복원·재디스패치 → R1 가드 완화로 아바타에 전달됨
- seek(타임라인 점프)는 5분 청크 스냅샷부터 재처리 — 아바타 커스텀 상태는 스냅샷에 없으므로
  **서버가 저주기(2초) 전체 상태 재전송**으로 복원 (서버측 fc 시스템 담당)
- 리플레이에서 타 플레이어 아바타는 메인 클라우드 업로드 + "모두 다시 불러오기" 필요 — **또는** `/figura nonhost_load <p> <path>`
  (클라 한정 pin, 리플레이를 나갈 때까지 유지) · `/figura netlock on` (클라우드/FSB 로딩 전면 정지). 위 10 참고.

## 이 포크에 의존하는 애드온 모드

`.libs/figura-0.1.6+1.21.8-fabric-mc.jar` 로 **이 프로젝트의 빌드 산출물을 사본 참조**한다.
**공개 API가 바뀌는 수정을 했으면 각 애드온의 `.libs/` 에 재복사 + 재빌드** 할 것
(UI 상수 등 내부 변경만이면 불필요).

- `../figura_sillyplugin_internal` — `silly` Lua API 애드온 (자체 확장 포함, CLAUDE.md 참고)
- `../figura_chatheads_internal` — 채팅 머리를 아바타 얼굴로 (CLAUDE.md 참고)
- `figura-aero-addon-main` — 포팅 불가 상태 (2026-09-25 옮길 때 옛 폴더에 이미 없었다 — 옮기지 않음)

또한 애드온이 **Figura 내부 클래스를 미신 타깃으로 삼는다**. 포크 코드를 리팩터링할 때
`FiguraLuaPrinter.lambda$static$*`, `LuaSound.lambda$play$*` 같은 합성 람다 이름이 밀리면
애드온이 조용히 깨지므로, 그런 변경 후엔 애드온도 함께 검증한다.

## 관련 문서

- FSB 플러그인/프로토콜/타 플러그인 API: `../figura_fsbplugin_internal/API.md`, `../figura_fsbplugin_internal/CLAUDE.md`
- 서버 CH↔아바타 브리지 규약: mc_content_production 하네스
  `knowledge/shared/fsb_bridge_system.md`
- 아바타 Lua docs(VSC 자동완성): 아바타 레포 `RaD_FiguraAvatars/.vscode/docs/`
  (server_packets/FSB 추가분 포함, 0.1.6 기준 전수 감사 완료)
