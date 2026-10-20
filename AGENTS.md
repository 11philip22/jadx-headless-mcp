# Agent instructions

## Code structure

```text
src/main/java/reforge/jadx/
├── JadxTools.java          — MCP tool contract and response encoding.
├── JadxWorker.java         — Serialized calls, lazy loading, reload, and shutdown.
├── JadxServices.java       — Typed Java interfaces implemented by the six services.
├── JadxModels.java         — Request and result records.
├── JadxArguments.java      — Tool argument decoding.
├── JadxSession.java        — Loaded decompiler state.
├── JadxCodeCache.java      — Decompiled source cache state.
├── JadxEditStore.java      — Committed renames and comments.
├── JadxNodes.java          — Shared original IDs and node resolution.
├── JadxInputs.java         — Workspace input validation and container deduplication.
├── ClassService.java       — Class inventory, packages, members, and smali.
├── DecompilerService.java  — Class and method source, source metadata, and cache operations.
├── SearchService.java      — Text search, method lookup, and cross-references.
├── ResourceService.java    — Resource names, file contents, and strings.
├── ManifestService.java    — Manifests, components, application classes, and main activities.
├── EditService.java        — Renames and comments with source regeneration and verification.
└── WorkerJson.java         — Shared JSON codecs and text normalization.
```

## Behavior to preserve

Preserve all 31 tools and the existing `analysis/jadx/` inventory, edit, cache,
and log paths. Source remains lazy; renames and comments regenerate and verify
source before an atomic save. Reload closes the old session before publishing
a fully constructed replacement.
