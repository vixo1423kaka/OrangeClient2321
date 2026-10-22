# Live agent status (Minecraft 1.21.4, Fabric)

Implemented: the agent finds the game's Knot classloader, adds the bundled client jar to its class path,
runs `OrangeClient.onInitializeClient()` on the game thread and validates the license key through
`LicenseManager.validate`. The launcher shows the real result (`%TEMP%/orange-agent-result.txt`).

Known limits (untested against a real game in CI - run it and check the log):
- Mixins only apply when a class is first loaded, so the 22 mixins/accessors cannot be applied to a running game.
  Modules using them (KillAura swing/rotation parts, Freecam, FastPlace/FastBreak, NoJumpDelay, HandAnimations,
  Chams, AutoDripstone...) can throw ClassCastException or stay inactive. Needs a guard or an accessor-free rewrite.
- Fabric resource packs are registered at startup, so `orangeclient` fonts/textures are not found (fallback font, no logo).
- Requires Fabric Loader + Fabric API in the target game.
