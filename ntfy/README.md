# ntfy

Fabric server mod that sends ntfy alerts when players join or leave.

It also reads the visitor-protection builder list and highlights joins from players who are not current builders.

## Config

The config file is:

```text
config/ntfy.json
```

Minimal setup:

```json
{
  "enabled": true,
  "url": "https://ntfy.example.com/minecraft"
}
```

For authenticated ntfy servers, prefer `tokenFile`:

```json
{
  "enabled": true,
  "url": "https://ntfy.example.com/minecraft",
  "tokenFile": "/run/secrets/ntfy-token"
}
```

## Commands

```mcfunction
/ntfy status
/ntfy reload
/ntfy test
/ntfy placeholders
```

## Templates

Titles, bodies, priorities, and tags can use placeholders. Both `{player}` and `${player}` forms work.

Available placeholders:

```text
{player}
{username}
{uuid}
{action}
{server}
{builder}
{operator}
{world}
{x}
{y}
{z}
{position}
{address}
{online}
{max_players}
{builder_names}
{builder_uuids}
{time}
```

Example:

```json
{
  "joinTitle": "{player} joined {server}",
  "nonBuilderTitle": "Non-builder {player} joined {server}",
  "nonBuilderBody": "**{player}** is not on the builder list.\n\n- UUID: `{uuid}`\n- World: `{world}`\n- Position: `{position}`\n- Online: `{online}/{max_players}`"
}
```

## Builder Detection

By default the mod reads:

```text
config/visitor-protection.json
```

The `builders` array may contain player names, UUIDs, or objects with `player` and `expiresAt`, matching the visitor-protection config format.
