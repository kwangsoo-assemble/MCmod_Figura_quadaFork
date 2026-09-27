# Figura 0.1.6 (MC 1.21.8) — 커스텀 포크

Figura 0.1.5-1.21.8 포팅본을 베이스로 0.1.6 기능 + FSB(서버 클라우드) 클라이언트를 이식한
**자체 생태계 전용 포크**. 공식 Figura/FSB와 와이어 호환되지 않는다 (아래 프로토콜 변경 참고).

## 빌드

- **반드시 JDK 21**: `JAVA_HOME="C:\Program Files\Java\jdk-21"` 지정 후 `./gradlew :fabric:build`
  (시스템 기본 JDK 25는 Gradle 8.11과 비호환 — "Unsupported class file major version 69" 에러)
- Fabric 전용 (forge/neoforge는 settings.gradle에서 주석 처리됨)
- 산출물: `fabric/build/libs/figura-0.1.6+1.21.8-fabric-mc.jar`
- 짝꿍 서버 플러그인: `../figura_fsbplugin_internal` (플러그인과 **반드시 같이 배포** — 프로토콜 변경 시 특히)

## ★ 공개판 · 내부판 (2026-09-25)

이 폴더는 **내부판**이다 — 작업은 여기서 한다(한글 주석 · 이 CLAUDE.md · 빌드 산출물). 로컬 git 만 있고 **원격이 없다.**
공개판 `../figura_core_but_ai_edited` (GitHub `kwangsoo-assemble/figura_core_but_ai_edited`) 는 **같은 코드에 주석만 영어**인 사본이다
(이 CLAUDE.md 는 없다 · 원본 README 는 `README.upstream.md` · 영어 README 는 하네스의 한글판을 옮긴 것).

- 코드를 고쳤으면 **공개판에도 같은 변경을 옮긴다** — 주석은 영어로, 내부 이름(하네스 · 콘텐츠명 · 사람 인용)은 빼고 일반 표현으로.
- 판정: `python ../../../mc_content_production/projects/mc_repositories/tools/repos.py status` — «주석을 빼면 같은가 · 공개판 한글 0 · 내부 문서 유출 0 · README 동기».
- 올리기: `python ../../../mc_content_production/projects/mc_repositories/tools/repos.py publish core -F <메시지 파일>` — 위가 초록일 때만 커밋 · 푸시한다
  (공개판 커밋 · 푸시는 세션이 자동으로 한다 — 하네스 `CLAUDE.md` 「외부 저장소 — mc_repositories」).
- README 는 **한글판이 원본**이다 — 하네스 `projects/mc_repositories/readme_ko/figura_core_but_ai_edited.md`. 기능을 바꾸면 거기 먼저 적고
  영어 README 에 옮긴 뒤 `repos.py mark-readme core`.
- 내부판 변경은 **로컬 커밋**으로 남긴다(원격 없음).
- `server-common/` 은 FSB 내부판(`../figura_fsbplugin_internal`)이 원본이다. 공개판도 같다 —
  FSB 공개판에서 영어로 옮긴 뒤 **코어 공개판으로 복사**한다(두 공개판의 `server-common/` 이 다르면 통신이 깨진다).

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
