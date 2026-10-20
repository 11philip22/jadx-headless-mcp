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

Run `mvn -B verify` to build and run the packaged-JAR smoke check. It launches
the JAR in an isolated directory and checks MCP discovery, input formats,
decompilation, edits, cache operations, and reload using a small test fixture.

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

Discovery exposes 29 analysis tools, `status`, and `reload_inputs` without
reading APKs or creating artifacts.

The workspace inventory at `analysis/jadx/inputs.json` is an array of objects
with workspace-relative `path` fields (`.apk`, `.apkm`, `.xapk`, or `.dex`).
Extraction writes this inventory.
Optional `container` and `member` fields identify recovered nested APKs for
container deduplication.

`status` loads inputs and checks the base manifest; `reload_inputs` rereads the
inventory in the same process. Each workspace needs its own server process.
