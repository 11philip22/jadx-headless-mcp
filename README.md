# JADX MCP server

## Build

Install JDK **21** or newer and Maven **3.9**. Put `java` and `javac` from the same
JDK on `PATH`.

Build from the repository root on Windows, macOS, or Linux:

```sh
mvn -B package
```

Maven downloads JADX **1.5.6**, its plugins, and Java MCP SDK **2.0.1**, then
bundles them into `target/jadx-mcp.jar`. Run the JAR with Java **21** or newer:

```sh
java -Xmx2g -Djava.awt.headless=true -jar target/jadx-mcp.jar --workspace /path/to/job
```

## Static analysis

With the build prerequisites above installed, run PMD from the repository root:

```sh
mvn -B pmd:check
```

The Maven plugin loads the four rulesets in `config/pmd/`. Violations fail the
check; the HTML report is written to `target/reports/pmd.html`.

## MCP configuration

After building, configure a stdio MCP client. Replace the absolute paths with
your built JAR and job workspace:

```json
{
  "mcpServers": {
    "jadx": {
      "command": "java",
      "args": [
        "-Xmx2g",
        "-Djava.awt.headless=true",
        "-jar",
        "/path/to/jadx-mcp.jar",
        "--workspace",
        "/path/to/job"
      ],
      "env": {
        "REFORGE_JADX_THREADS": "2"
      }
    }
  }
}
```

## Behavior

Discovery exposes 29 analysis tools, `status`, `reload_inputs`, `load_apk`, and
`unload_apk` without reading APKs or creating artifacts.

The workspace inventory at `analysis/jadx/inputs.json` is an array of objects
with workspace-relative `path` fields (`.apk`, `.apkm`, `.xapk`, or `.dex`).
The inventory is optional: a new workspace starts idle until inputs are registered.
Optional `container` and `member` fields identify recovered nested APKs for
container deduplication.

`status` loads inputs and checks the base manifest; `reload_inputs` rereads the
inventory in the same process. Each workspace needs its own server process.

Call `load_apk` with `{"path":"apks/app.apk"}` to load a file immediately and
append it to the inventory for future runs. Paths must be relative to the
workspace and resolve to files inside it. Absolute paths, traversal outside the
workspace, and symlinks to external files are rejected. Files are never copied.
Registering the same normalized path again does not add another row or force a
reload. Byte-identical copies at different paths are not detected.

Call `unload_apk` with `{"path":"apks/app.apk"}` to remove its registration and
reload the remaining inputs. This also works if the registered file was deleted.
Unloading preserves APK files and saved renames/comments. Unloading a container
removes rows explicitly associated with it; unload the supplying container
instead of an embedded member. Removing the last input saves `[]` and returns
the server to idle.

Inventory changes are saved atomically after successful loading. A failed load
or save leaves the previous inventory unchanged. If the old session was already
closed, the next analysis call reloads that inventory. Source generation remains
lazy. Manifest-readiness limitations, such as a DEX file having no manifest, are
reported without rejecting an otherwise loadable input.

Use separate workspaces for different app versions: JADX combines inputs into
one class namespace, and these tools do not provide version isolation.
