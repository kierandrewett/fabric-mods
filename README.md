# Fabric Mods

Local Fabric mods for the Minecraft stack.

## Mods

- `skin_tools`: adds `/globalskin`, `/skin`, and `/help`.
- `visitor_protection`: adds `/buildlist`, lets visitors join without being able to edit the world, expires command-added builders after 14 days, and grants builders WorldEdit permissions without LuckPerms.
- `kier_worlds`: adds `/kworld`, `/world`, and `/worlds` for managing named dimensions.
- `ntfy`: sends ntfy alerts when players join or leave, including non-builder alerts based on the visitor-protection builder list.

## Build

```sh
cd skin_tools
./gradlew clean build
cd ../visitor_protection
gradle clean build
cd ../kier_worlds
gradle clean build
cd ../ntfy
gradle clean build
```

The built jars are written to each mod's `build/libs/` directory.

## Release

Push a tag like `v1.0.0` to build the Fabric mods and publish stable jars to GitHub Releases.
