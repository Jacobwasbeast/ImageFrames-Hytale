# Hytale 0.7.3 migration

The previous version references removed Hytale vector classes and column-based
block methods. Those calls fail to compile against 0.7.2 and later, and an older
compiled JAR can fail with linkage errors during startup or interaction.

The plugin compiles and passes its tests against 0.7.3 without source changes.
Only the pinned server version moves from 0.7.2, so the server no longer warns
that the plugin targets an older release.

The migration uses JOML vectors, Hytale rotation types, `SectionReader` and
`BlockOperations`. It also updates runtime pack registration to use
`AssetPack.PackSource.RUNTIME` and declares the valid exact server range
`=0.7.3`. Gradle targets Java 25 and resolves server API version 0.7.3 explicitly.

Runtime textures and models are restored before registering the runtime pack,
then block types are loaded before worlds start. This ordering matters because
registering a pack immediately validates its block JSON. Initialization failures
stop asset loading before a world can deserialize missing tile types.

The plugin owns its entity systems and player event registration, starts and
cancels the integrity timer with its lifecycle, and serializes image generation
on its asset executor. Reads, writes, restoration and rotation access happen on
the world thread. Unloaded sections are skipped during restoration and integrity
checks; a stored group can only be edited or removed when all its sections are
loaded. Rotation reads no longer rely on reflection over old chunk internals.

## Verification

- Build and regression tests: `./gradlew build` with JDK 25. Tests cover manifest
  version-range parsing, legacy stored IDs and positions, cross-section tile
  coordinates at negative Y, image limits, alpha decoding and cache eviction.
- Server reference: the official release 0.7.3 JAR, revision
  `e1a1986a6f9db589637f1d6aa675a6920cd5b798`.
- Asset validation in an isolated working directory, with the built plugin in
  `mods/` and the game's `Assets.zip`:

  ```text
  java --enable-native-access=ALL-UNNAMED -Xmx4G -jar HytaleServer.jar --assets Assets.zip --auth-mode offline --bind 127.0.0.1:0 --disable-sentry --bare --validate-assets --shutdown-after-validate --log INFO
  ```

- Headless server exercise (run against 0.7.2, not repeated for 0.7.3) with a
  temporary test plugin: generate and place 2x2
  frames, panels and banners in a cubic world, including X/Z section boundaries,
  Y=-33 and Y=352; preserve panel/banner rotations; restart with the same saved
  world and image cache, verify every tile and rotation, then remove each group
  and verify the blocks become `Empty` and the stored groups are removed.

No interactive client was used for these checks. Visual rendering, UI navigation,
client-side texture rebuilding and multiplayer synchronization still need an
in-game check. The server may report vanilla asset warnings and naming-style
warnings for the existing lowercase item IDs; those IDs are retained to avoid
another rename of items already installed from the fork.

## Resource limits

HTTP images are limited to 16 MiB. Width and height are checked before decoding,
and source/rendered images are limited to 16 megapixels. Tile size is clamped to
16–1024 and frame discovery stops at 256 blocks. Decoded images use a bounded LRU
cache (64 MiB of estimated pixel storage); disk entries remain available after
eviction and a damaged entry can be downloaded again. HTTP streams are closed
and connections disconnected on both success and failure.

Saved layouts exceeding the geometry limit stop initialization before worlds
load. The saved frame data is not deleted. Review oversized layouts before
upgrading, or deliberately adjust the limits and rerun validation.
