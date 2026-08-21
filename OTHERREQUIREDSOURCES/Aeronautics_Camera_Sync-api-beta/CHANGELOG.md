# Changelog

## 1.3.7

### Added
- Added a public API for other mods (`com.playsi.aero_cam_sync.api`) — see the [mod developer guide](docs/API.md)
- Added an API switch that turns camera collision off for mods that genuinely rotate the player: such a mod keeps its own rotated hitbox and takes over the duty of keeping the camera out of blocks
- Added an API switch that turns third person on. By default ACS now does nothing in third person at all — vanilla camera, vanilla aiming — and a mod whose scenario lives there asks for the tilt through `AcsHandle.enableThirdPerson`. One switch covers the whole of it: the camera is rotated and every ray follows it, or neither happens

### Changed
- The `Allow in 3rd person (Beta)` option is gone from the settings screen. Third person is now something a mod switches on, not something the player ticks — the beta option only ever produced a half-tilted state where the crosshair and the ray disagreed. The config schema is bumped to 4, so the game offers a one-time config reset

### Fixed
- Fixed aiming in third person disagreeing with the camera: the view direction was tilted there while the ray still started at the untilted eye, so the crosshair and the hit drifted apart. Both halves now answer to a single switch and cannot diverge
- Fixed the tilt being cancelled in third person whenever the camera came near a wall: the clear-test measured a gap of 0.15 around a point vanilla had already parked 0.1 from the wall, so it reported "blocked" at the very position vanilla picked as safe. The camera is still kept out of blocks — the position clamp is untouched and runs in both camera modes
- Fixed a wall-scale carry-over when switching between first and third person against a wall: the tilt now resets instantly on the switch instead of easing over ~80 ms with the camera inside a block

### Note for mod developers
- In third person the tilt is no longer scaled down near walls, so the server now receives the full tilt there instead of a reduced one
- `AcsClientState.firstPerson()` no longer decides on its own whether we are correcting anything: in third person that now depends on whether a mod has switched third person on. Check `thirdPersonEnabled()` alongside it

## 1.3.6

### Fixed
- Fixed wrong block/entity picking while standing on a tilted contraption (1.3.5 regression)
- Fixed camera tilt jitter while a screen is open, e.g. the Create Simulated diagram (1.3.5 regression)
- Fixed Cut Through compatibility without letting entities be hit through deck blocks again (Issue #16, Issue #26)
- Fixed server-side features silently doing nothing on dedicated servers: the handshake never completed, so projectiles, buckets, tossed items and aim were left uncorrected (Issue #33)
- Fixed aim desync when Client Side Only was toggled during play: the client fell back to client-only mode while the server kept aiming by the last tilt it had received

### Reworked
- Client Side Only now applies after rejoining the world; while the change is pending, the mode badge in the config screen is greyed out and says so

## 1.3.5

### Added
- Added server-side tilt sync: projectiles, tossed items, buckets and aim follow the tilted camera when the mod is installed on the server (by MrLemonHog, #28)
- Added activation thresholds: tilt only on contraptions above a minimum mass, block count or size
- Added camera collision with a smoothing slider
- Added auto-disable for projectile and bucket items in client-only mode
- Added mode indicator to the config screen (`mode: client-only` / `mode: server-client`)
- Added default blacklisted items (`create:handheld_worldshaper`, `create:potato_cannon`)

### Fixed
- Fixed incorrect projectile aiming on rotated contraptions (Issue #3)
- Fixed camera X-ray when tilted near a wall (Issue #27)
- Fixed hitting entities through walls (Issue #26)
- Fixed incompatibility with Sound Physics Perfected (Issue #24)
- Fixed wrong player rendering with First Person Model (Issue #14)
- Fixed incompatibility with Point Blank (Issue #11)
- Fixed wrong fluid placement position when using buckets on a tilted contraption
- Fixed camera collision logic and made it smooth

### Reworked
- Reworked the config screen layout: Camera tab split into Tilt and \Collision sections, options regrouped across tabs
- Options that require the mod on the server are now marked in the config
- Renamed Check Offhand to Include Offhand, rewrote tooltips
- Removed unused server blacklist config entries
- Updated dependencies: Sable 2.0.3, Create Aeronautics 1.3.0, NeoForge 21.1.229

## 1.3.1

### Fixed
- Fixed visual issues with tires & laser pointers (Issue #22)
