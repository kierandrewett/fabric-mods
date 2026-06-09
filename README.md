# Fabric Mods

Local Fabric mods for the Minecraft stack.

## Mods

- `skin_tools`: adds `/globalskin`, `/skin`, and `/help`.

## Build

```sh
cd skin_tools
./gradlew clean build
```

The built jar is written to `skin_tools/build/libs/`.

## Release

Push a tag like `v1.0.0` to build `skin_tools` and publish the jar to GitHub Releases.
