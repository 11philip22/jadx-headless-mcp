# JADX MCP server

## Build

Requires JDK **21+** and Maven **3.9**.

```sh
mvn -B package
java -Xmx2g -Djava.awt.headless=true -jar target/jadx-mcp.jar --workspace /path/to/job
```

## Static analysis

```sh
mvn -B pmd:check
```

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

A new workspace starts idle. Inputs can be `.apk`, `.apkm`, `.xapk`, or `.dex`
files; paths must be workspace-relative and resolve inside the workspace.

- `load_apk {"path":"apks/app.apk"}` loads and registers a file.
- `unload_apk {"path":"apks/app.apk"}` unregisters it and reloads remaining inputs.
  Files and saved edits are kept. For embedded APKs, unload their container.
- `status` loads inputs and checks the base manifest.
- `reload_inputs` rereads `analysis/jadx/inputs.json`.

The optional inventory contains rows such as `[{"path":"apks/app.apk"}]`.
Updates are saved atomically after successful loading. Discovery does not load
inputs, and source generation stays lazy.

Use one server process per workspace and separate workspaces for different app
versions: JADX combines inputs into one class namespace.
