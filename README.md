# ImmersiveCinematics

**Script-driven cutscenes for Minecraft modpacks.** Describe camera work, letterbox, subtitles, music, commands and color grading in a JSON file; the mod plays it when a trigger fires. No command blocks, no redstone.

[中文文档](README_CN.md)

> **Code implementation is AI-assisted.** The author owns the design — architecture, script format, behavior contracts and verification; an AI coding agent writes the code under that direction.

---

## What It Is

ImmersiveCinematics is a Minecraft 1.20.1 mod that plays cutscenes from JSON scripts. Triggers run on the server: they watch gameplay events and conditions, and when one matches, the script plays on the clients of the players it applies to. Playback is data-driven — a script declares tracks, clips and keyframes, and the runtime executes them.

Typical uses: intro sequences, boss-fight cinematics, fixed-camera puzzle rooms, chest close-ups, server announcements and automated world flythroughs.

## Scripts

A script is one JSON file: a `meta` block (id, name, author, version, triggers, runtime behavior) and a `timeline` block (tracks → clips → keyframes). The script format is v3, and `meta.version` must be `3`.

Seven track types:

| Track | Purpose |
|---|---|
| `CAMERA` | Camera position, rotation and optics. Each track produces its own picture lane. |
| `LETTERBOX` | Widescreen bars; aspect ratio and opacity are keyframed. |
| `OVERLAY` | Color fades (gradients, masks, blend modes), PNG/GIF images, subtitles. |
| `AUDIO` | Music and sound effects (OGG/WAV) through the vanilla sound engine. |
| `EVENT` | Server commands at given times, with `&&` chains and permission level 4. |
| `MOD_EVENT` | Events exposed by other mods. |
| `ADJUST` | Color grading applied to the whole composited picture. |

Clips on a track may overlap; later clips draw on top. Loops are `repeat` or `ping-pong`, and script-level macro loops are `repeat`. A script ends when its content is played out — every clip consumed, finite loop counts exhausted, nothing pending — not when `total_duration` elapses; `total_duration` is informational.

Scripts load from `immersive_cinematics/scripts/` under the game directory (subfolders up to five levels, `/`-separated paths, `.json` optional). Images, audio, `.cube` LUTs and translation files load from `immersive_cinematics/resource/`.

## Camera

- Six independent channels per keyframe: position, yaw, pitch, roll, FOV, zoom.
- Linear interpolation by default; Bézier paths are arc-length parameterized, so speed stays even through curves.
- Position is either world-absolute or an offset from a point source — the player, a fixed coordinate, a nearby block, an entity selector or a structure. Sources can be `live` (tracked every frame) or `snapshot` (frozen at first resolution).
- Look-at targets: player, entity, coordinate, block or structure. `look_at_part` aims at a percentage point inside an entity's bounding box. `yaw_lock` / `pitch_lock` pin an axis outright.
- Entity selectors accept `@a` / `@p` / `@e[…]` with NBT and `type=` filters, plus per-call-site refresh and switching policy.
- Motion models per channel: `smoothstep`, `ease_in` / `ease_out` / `ease_in_out`, `cubic_bezier`, `exponential_smooth`, `spring`, `inertia`, composable with `chain` and `blend`.
- Modifiers patch the running camera per channel (`add` / `multiply` / `replace`, priority-ordered, maskable, suppressible) without rewriting keyframes.
- Transitions: `cut` and `morph`, with per-channel `transition_params` (blend / hold / cut) and `transition_interrupt`.
- Handheld feel: `perlin`, `perlin_axis`, `sine`, and `trauma` for short impacts.

## Multiple cameras

Every `CAMERA` track is an independent camera producing its own lane. Lanes composite into split screen and picture-in-picture, blend by per-lane opacity, and can be cropped by rectangular or circular masks. A clip may name a `dimension`, so a lane renders another dimension's terrain without teleporting the player.

## Color grading

`ADJUST` grades the final composite; a `CAMERA` clip grades only its own lane. Available controls: exposure, contrast, highlights and shadows, white and black point, saturation, vibrance, temperature, tint, grayscale, invert, RGB channel gains, full HSL, RGB and per-channel curves, six hue curves, a 3×3 channel mixer, Lift/Gamma/Gain wheels, `.cube` LUTs, six-band hue/saturation trim, and blend modes (`multiply` / `screen` / `soft_light` / `overlay`). Every parameter is a keyframe channel.

## Triggers

27 trigger types, evaluated server-side: `login`, `advancement`, `xp`, `observation`, `inventory`, `location`, `biome`, `dimension_change`, `dimension`, `structure`, `entity_kill`, `entity_interact`, `block_interact`, `item_on_interact`, `item_craft`, `item_use`, `item_consume`, `item_release`, `item_instant_use`, `item_use_interrupt`, `item_pickup`, `item_drop`, `gamestage`, `death`, `facing`, and the nestable combinators `all_of` / `any` (up to eight levels deep).

Each trigger supports `delay`, `repeatable`, `on_enter` / `exit_buffer`, `poll_interval`, and `requires` — prerequisites such as "another script has started and finished" (`script_started` / `script_completed` / `script_played`), or conditions registered by other mods. A polling trigger takes its interval from the trigger, then the script, then the type default. Per-player trigger state syncs to the client, so reconnecting does not lose progress.

## Playback control

- `meta.play_mode`: `parallel` (default — never blocks newcomers, never interrupted), `exclusive` (others queue up), `interruptible` (a newcomer takes over).
- The queue is unbounded and survives across instances. Only an `exclusive` script blocks newcomers; queued requests resume by `priority`, then arrival order.
- Hold **C** to skip the current cutscene; **Ctrl+P** force-quits. Multiplayer skip voting is configurable per script or globally.
- Keyboard and mouse blocking, mob-AI blocking, per-layer HUD hiding (chat, scoreboard, action bar, title, subtitles, hotbar, crosshair, boss bar, skip prompt), arm hiding, view-bob and distortion suppression, player model rendering, pause-when-paused. `hard_hide_hud` (Forge) takes over GUI rendering, so third-party HUDs that draw outside the overlay registry are hidden too.
- Several scripts can run at once: hiding and blocking switches are OR-ed across instances, and pause behavior follows any instance that asks for it.

## World loading

While the camera is away from the player, the mod neither moves the player nor spawns a stand-in: the client reports the camera position, the server writes it into the vanilla chunk system as a virtual section centre, and chunks, entity pairing and mob spawning follow the camera by vanilla rules. When the shot ends the centre reverts and normal player-range loading resumes. Scripts without a `CAMERA` track never start this.

## Getting Started

1. Minecraft 1.20.1 with Java 17+.
2. Forge 47.x+ (no extra prerequisites), or Fabric 1.20.1 (requires Fabric API).
3. Put the jar in `mods/` on both client and server — multiplayer needs both sides.
4. Put scripts in `immersive_cinematics/scripts/` under the game directory.

Releases: [GitHub Releases](https://github.com/SanZiNEO/ImmersiveCinematics/releases).

### A minimal script

```json
{
  "meta": {
    "id": "welcome",
    "name": "Welcome",
    "author": "Your Name",
    "version": 3,
    "triggers": [
      { "id": "on_login", "type": "login", "delay": 1.0, "conditions": {} }
    ]
  },
  "timeline": {
    "total_duration": 6.0,
    "tracks": [
      {
        "type": "camera",
        "clips": [
          {
            "start_time": 0.0,
            "duration": 6.0,
            "keyframes": [
              { "time": 0.0, "position": { "dx": 5, "dy": 2, "dz": 3 }, "yaw": 90, "pitch": 5 },
              { "time": 6.0, "position": { "dx": 0, "dy": 2, "dz": -5 }, "yaw": 0, "pitch": 10 }
            ]
          }
        ]
      }
    ]
  }
}
```

`position` with `dx` / `dy` / `dz` is a world-axis offset from the player's activation position; `yaw` and `pitch` are degrees. Save it as `immersive_cinematics/scripts/welcome.json`, run `/icinematics reload`, and it plays one second after login.

### Commands

| Command | Permission | Description |
|---|---|---|
| `/icinematics play <script> [players]` | op 2 | Play a script; tab-completion, `@a` / `@p` selectors |
| `/icinematics stop [script] [players]` | — | Stop playback; no arguments stops everything |
| `/icinematics reload` | op 2 | Re-read the scripts from disk and rebuild triggers |
| `/icinematics validate <script>` | op 2 | Static-validate a script and list every problem at once |
| `/icinematics template list` | op 2 | List the built-in clip templates and their parameters |
| `/icinematics template <id> [key=value…]` | op 2 | Expand a clip template into a generated script |

Script paths are relative to `immersive_cinematics/scripts/`; `.json` is optional.

## Editor

Cutscenes are authored in the **Standalone Editor** — a desktop app (Electron + Vue 3) that talks to the game over a local connection. It is a separate download — currently a Windows installer (version 0.1.0, testing); its sources live in `editor/`.

Press **F9** in game to start the local service and open the preview screen. The editor provides a multi-track timeline, schema-driven forms, live preview, flight camera (**F7**), A-B looping, markers and shortcut keys, and ships the mod's script format so its forms always match the running version. Easing and speed curves are baked into explicit keyframes by the editor; the runtime only interpolates linearly.

The mod jar ships the playback runtime plus the WebSocket server, frame streaming and preview screen the editor talks to. The old in-game Java editor (F6) is retired in 0.3.6.

## Compatibility

- Forge 47.x+ and Fabric 1.20.1 from one common codebase; the platforms differ only in rendering and HUD adapters (`hard_hide_hud` is Forge-only) and the config screen entry.
- Compatible with Sodium, Rubidium and Embeddium.
- Iris / Oculus shader packs: per-lane shader state is isolated, including cross-dimension lanes; switching shader packs no longer requires a restart.
- Pause-aware: scripts freeze when the game is paused.

## Version

| | |
|---|---|
| Minecraft | 1.20.1 |
| Java | 17+ |
| Script format | v3 |
| Latest release | 0.3.5 (2026-09-09) |
| In development | 0.3.6 (unreleased) — this README describes the current development state |
| Standalone editor | 0.1.0 (testing) |

## Roadmap

0.3.6 is still in progress: the WebUI editor workflow, the editor-side template engine, and the remaining shader-pipeline isolation. After that, multi-version support for Minecraft; the script format is not yet frozen.

---

## License

MIT License
