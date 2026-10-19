# HRPCore — Fabric rewrite bootstrap

This is the first isolated Fabric implementation for Minecraft Java Edition **1.21.10**. The old Paper implementation is intentionally not copied into this project: Paper/Vault/PlaceholderAPI APIs cannot be used by a Fabric mod.

## Current scope

- Fabric common and client entrypoints.
- `/hrp` server command for a basic smoke test.
- Press **O** to open the first HRPCore screen.
- Hybrid UI direction: custom RP navigation screen, vanilla HUD remains intact.
- Java 21 and Gradle/Fabric Loom build configuration.

This is an architectural bootstrap, not a feature-complete RP mod. Profile networking, persistence, economy, professions and other systems are not implemented yet.

## Requirements

- JDK 21
- Minecraft Java Edition 1.21.10
- Fabric Loader and Fabric API matching `gradle.properties`

## Open and build

Open this folder as a Gradle project in IntelliJ IDEA. Select JDK 21 as the Gradle JVM, allow Gradle to sync, then run the `build` task. The wrapper scripts are not bundled in this bootstrap archive yet; if you use a local Gradle installation, run `gradle build` from this directory.

The distributable mod JAR will be under `build/libs/`.

## First smoke test

1. Run the development client using `runClient`.
2. Join a world and press **O**.
3. Confirm the HRPCore screen opens and closes.
4. Run `/hrp` and confirm the server command responds.
5. Run a dedicated server test before treating this as release-ready.

## Architecture rules

- Server is authoritative for money, roles, jobs, talents, crime, jail and medical state.
- Client screens only display server snapshots and send validated requests.
- No Bukkit, Paper, Vault or PlaceholderAPI dependencies in the Fabric module.
- Database work must not block the Minecraft server thread.
