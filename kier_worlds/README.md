# Kier Worlds

Fabric server mod for named dimensions managed from chat/RCON.

## Commands

```mcfunction
/kworld templates
/kworld list
/kworld here
/kworld current
/kworld info <world>
/kworld create <name> [template] [seed]
/kworld tp <world> [player]
/kworld tp <world> <x> <y> <z> [player]
/kworld setspawn <world>
/kworld delete <name>
/kworld delete <name> confirm
```

`/world` and `/worlds` are aliases for `/kworld`.

`/kworld list` prints clickable chat actions for teleporting, inspecting, and requesting deletion of managed worlds. Delete is two-step: `/kworld delete <name>` asks for confirmation, and `/kworld delete <name> confirm` removes the datapack definition.

`/kworld templates` prints clickable template rows. Click `[use]` to prefill a create command, or `[copy]` to copy the template value.

## Built-in templates

```mcfunction
/kworld create wild vanilla
/kworld create wild vanilla 12345
/kworld create arena flat
/kworld create mountains amplified
/kworld create huge large_biomes
```

New worlds are written to the save as a datapack:

```text
world/datapacks/kier-worlds/data/kier_worlds/dimension/<name>.json
```

Restart the Minecraft server after creating or deleting a world. Minecraft loads dimension registries at startup, so the command writes the definition first and the next boot materializes the dimension.

## Teleport targets

Managed world names can be used directly:

```mcfunction
/kworld tp wild
/kworld tp wild 0 90 0
/kworld tp wild 0 90 0 FirefoxBrowser
```

Full dimension IDs also work:

```mcfunction
/kworld tp minecraft:overworld
/kworld tp minecraft:the_nether
/kworld tp minecraft:the_end
```

## Custom generation

For datapacks/mods that expose worldgen registry IDs, use:

```mcfunction
/kworld create <name> custom:<noise_settings_namespace>:<noise_settings_path>:<biome_preset_namespace>:<biome_preset_path> [seed]
```

Example using vanilla IDs:

```mcfunction
/kworld create fancy custom:minecraft:overworld:minecraft:overworld 12345
```

Terralith, Tectonic, and other worldgen packs may expose their own registry IDs. Use those IDs here once confirmed.
