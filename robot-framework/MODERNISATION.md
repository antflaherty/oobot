# Java Robot Framework Modernisation

Progress tracker for the build-system and library upgrade. Each step is verified
by building the fat JAR and running a smoke test before moving on.

## Status legend
- `[ ]` not started
- `[~]` in progress
- `[x]` done & verified

## Steps

1. `[x]` **Gradle build (Kotlin DSL) + fat JAR** — replace hand-rolled `javac`/`jar`
2. `[x]` **Jackson JSON** — replace hand-rolled parser with typed records/data-binding
3. `[x]` **OkHttp WebSocket** — replace hand-rolled `WsConnection.java` with OkHttp (~120 lines)
4. `[x]` **SLF4J + Logback** — replace `System.out`/`err` with structured logging
5. `[x]` **picocli CLI** — replace hand-rolled arg parsing in `RobotController`

## Notes / decisions

- Build tool: Gradle Kotlin DSL (`build.gradle.kts`) over Maven
- JSON: Jackson `jackson-databind`
- WebSocket: OkHttp `okhttp` (com.squareup.okhttp3)
- Logging: SLF4J API + Logback backend
- CLI: picocli
- Fat JAR must still work with `java -jar robot.jar` (no extra flags)
- Dependencies must be embedded — no external classpath required at runtime

## Progress log

- 2026-11-17: Initial document created. Starting Step 1.
- 2026-11-17: Step 1 done — Gradle wrapper + fat JAR task (`./gradlew fatJar` → `robot.jar`).
- 2026-11-17: Step 2 done — Jackson replaces hand-rolled JSON parser; typed sealed records (`WireMessage`, `SensorSnapshotMessage`, `MotorPayload`, etc.); `Envelope.java` removed. Verified wire format unchanged and deserialisation works.
- 2026-11-17: Step 3 done — OkHttp replaces hand-rolled RFC 6455 client. Verified end-to-end with mock WS server: connect, motorCommand send, sensorSnapshot receive with simTick parsing, disconnect.
- 2026-11-17: Step 4 done — SLF4J + Logback replaces all 26 `System.out`/`err` calls across 10 files. Added `logback.xml` with timestamped pattern and `LOG_LEVEL` env override. Per-tick logs are DEBUG (hidden by default).
- 2026-11-17: Step 5 done — picocli replaces hand-rolled arg parsing. `--help`, `--version`, type coercion, and validation all work; invalid input exits 2 with usage output.
- 2026-11-17: All steps complete. Final verification: clean Gradle build, 6.4 MB fat JAR with all deps embedded, full integration test with real sim server (149 motorCommands received, safe-stop on disconnect). Docs updated: `README.md`, `AGENTS.md`, `PLAN_SENSORS.md`, `robot-framework/README.md`, `package.json`.

## Verification checklist

- [x] `./gradlew clean fatJar` produces `robot.jar` from scratch
- [x] `java -jar robot.jar --help` shows usage
- [x] `java -jar robot.jar --version` prints version
- [x] Invalid args exit 2 with usage
- [x] motorCommand wire format unchanged (Jackson polymorphic types)
- [x] sensorSnapshot deserialisation works (typed records)
- [x] OkHttp connects, sends, receives, reconnects, closes cleanly
- [x] Structured logs with timestamps and levels
- [x] Full integration: robot connects to real sim, 149 commands received, safe-stop on disconnect
