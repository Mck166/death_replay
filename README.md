# Death Replay

A RuneLite plugin that keeps the last 30 seconds of combat in memory and saves a
breakdown whenever you die, so you can see exactly what killed you.

## What it records

For every game tick in the window (0.6 s each):

- Your HP, prayer points and special attack energy
- Every hitsplat on you: amount and type (damage, block, poison, venom, heal...)
- Your active prayers
- Food and potions you ate or drank
- Who **you** were attacking

At the moment of death it also saves your equipped gear, world and location.

## Video

While you play, the last ~35 seconds of gameplay are kept in memory (15 fps,
full size by default). When you die, recording continues for a couple of
seconds to catch the death animation, then the clip is saved as an MJPEG
`.avi` that plays in VLC and most video players.

Each clip is roughly 30-80 MB depending on your game window size, so only the
newest 10 are kept by default. Frame rate, size and how many to keep are in
the plugin's Video settings.

## What you see

- A one-line recap in the chatbox (can be turned off), e.g.
  *"Took 94 damage in the last 3s (biggest hit 52)"*
- A **Death Replay** side panel listing every saved death. Click one for an HP
  chart, a summary, your gear and a tick-by-tick timeline.
- A **replay window** with the video next to every tick: HP, prayer, spec,
  hits and their types, active prayers, food, and your target. Step tick by
  tick with the arrow keys, play at 0.25x-2x, or click any tick to jump to it.
  **Save video...** copies the clip anywhere you like, e.g. to share it.

Deaths are saved as JSON (plus the `.avi`) in `.runelite/plugin-data/death-replay/deaths/`.
Old deaths are deleted once the limit set in the config is reached.

## Game rules

The plugin only reads information the game already shows you, and only shows
it **after** you die. It does not:

- show anything during combat (no overlays or live indicators)
- predict attacks, track projectiles, or say which player an NPC is targeting
- recommend prayers or tell you what you should have done
- touch menus, input or chat
- send any data off your computer

## Development

Requires JDK 11.

```
./gradlew test   # unit tests
./gradlew run    # launch RuneLite in developer mode with the plugin loaded
```

To log in with a Jagex account in the dev client, follow
[Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts).
