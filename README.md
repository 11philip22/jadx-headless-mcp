# JADX MCP server

## Build

Install JDK **21** or newer, Maven 3.9, and the full JADX **1.5.6** distribution,
including its `lib/` JARs and input plugins. Put `java` and `javac` from the same
JDK on `PATH`.

Build from the repository root on macOS/Linux:

```sh
export REFORGE_JADX_HOME=/path/to/jadx-1.5.6
mvn -B -f worker_jadx/pom.xml package
```

In PowerShell:

```powershell
$env:REFORGE_JADX_HOME = 'C:\tools\jadx-1.5.6'
mvn -B -f worker_jadx/pom.xml package
```

Maven compiles `src/main/java/` to `target/classes/`, builds the worker JAR, and
copies Java MCP SDK **2.0.1** and runtime dependencies into `target/lib/`.
Compilation uses the installed `lib/jadx-1.5.6-all.jar`; `REFORGE_JADX_HOME` defaults to
`/opt/jadx` and can also be supplied to Maven as `-Djadx.home=/path/to/jadx-1.5.6`.

## Static analysis

With the build prerequisites above installed and `REFORGE_JADX_HOME` configured,
run PMD from this repository's root:

```sh
mvn -B pmd:check
```

The Maven plugin loads the four rulesets in `config/pmd/`. Violations fail the
check; the HTML report is written to `target/reports/pmd.html`.

## MCP configuration

After building, configure a stdio MCP client. Replace the absolute paths with
your checkout, JADX distribution, and job workspace:

```json
{
  "mcpServers": {
    "jadx": {
      "command": "java",
      "args": [
        "-Xmx2g",
        "-Djava.awt.headless=true",
        "-cp",
        "/path/to/reforge/worker_jadx/target/classes:/path/to/reforge/worker_jadx/target/lib/*:/path/to/jadx-1.5.6/lib/*",
        "reforge.jadx.JadxWorker",
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

On Windows, use `;` between classpath entries.

## Behavior

Discovery exposes 29 analysis tools, `status`, and `reload_inputs` without
reading APKs or creating artifacts.

The workspace inventory at `analysis/jadx/inputs.json` is an array of objects
with workspace-relative `path` fields (`.apk`, `.apkm`, `.xapk`, or `.dex`).
Extraction writes this inventory.
Optional `container` and `member` fields identify recovered nested APKs for
container deduplication.

`status` loads inputs and checks the base manifest; `reload_inputs` rereads the
inventory in the same process. Each workspace needs its own server process.
