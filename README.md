# Huginn

A project-aware MCP server for Maven, for coding agents. It answers questions about the build the agent is actually editing: that reactor's effective models, its repositories and its resolved dependency graph, rather than an arbitrary GAV on Maven Central.

Built on [MIMA](https://github.com/maveniverse/mima), [mase](https://github.com/maveniverse/mase) (Central search) and the Quarkus MCP server (stdio), on Java 17.

## Tools

There are six tools, all read-only. Every one takes an optional `project` (any directory or `pom.xml` inside the reactor; default: the server's working directory) and `module` (path relative to the root, artifactId, or `groupId:artifactId`). Results are compact JSON.

| Tool | Answers |
|---|---|
| `describe` | At the root: modules, inter-module dependencies, repositories in use, `maven.config` settings. For a module: its effective model, with dependencies, plugins and executions, properties and profiles. |
| `dependencies` | The resolved tree, as the build sees it, plus **conflicts**: which version was requested by whom, and which one won. `scope` is compile, runtime or test; `maxDepth` caps output on large trees. |
| `why` | Every path that brings an artifact in, **including requests that lost a conflict**, across all modules by default. One call answers both "why is X here" and "who wants the old X". |
| `versions` | Upgrade candidates from the project's own repositories: latest, latest within the same major, latest within the same minor; pre-releases skipped unless asked for. Without an artifact it lists outdated direct dependencies. |
| `search` | Maven Central search by text or `g:`/`a:` query. |
| `sources` | Unpacks a dependency's sources jar into `~/.cache/huginn/sources/`, or returns one class's source. Without a version, uses the version this project resolves. |

Builds are deliberately not here. Running `mvn` with the agent-output extension gives agents a compact JSON report of the build itself; this server covers the questions a shell can't answer cheaply.

## How it works

- **Project first:** answers come from the reactor's effective models, including `dependencyManagement`, BOMs, profiles and exclusions, not from a standalone GAV.
- **The project's repositories:** repositories declared in the POMs are added to those from settings, and mirrors and authentication from settings apply.
- **Reactor siblings resolve from the working tree:** a workspace reader serves modules that were never installed, including their effective models, the same way Maven's `ReactorReader` does.
- **CI-friendly versions work:** `-D` and `-P` from `.mvn/maven.config` apply, with user properties winning as on the command line. MIMA's `MavenModelReader` deliberately demotes user properties for dependency POMs (MNG-7563), so project models are built separately in `ProjectModels`.
- **Few tools:** six, because every tool description costs the agent context on every turn.
- **Caching:** a loaded reactor is cached and reloaded only when a POM or `maven.config` changes. Unpacked sources are cached under `$XDG_CACHE_HOME/huginn/sources/` (default `~/.cache`), and logs go to `~/.cache/huginn/huginn.log`, since stdout belongs to the MCP protocol.

## Use

With Claude Code, run this from the project directory:

```sh
claude mcp add huginn -- jbang --quiet huginn@maveniverse
```

Or in an MCP client config:

```json
"mcpServers": {
  "huginn": { "command": "jbang", "args": ["--quiet", "huginn@maveniverse"] }
}
```

The server works on its working directory by default. Set `HUGINN_PROJECT` to point it elsewhere.

The catalog entry still needs adding to `maveniverse/jbang-catalog`, next to the other Maveniverse entries:

```json
"huginn": {
  "script-ref": "https://repo.maven.apache.org/maven2/eu/maveniverse/maven/huginn/huginn-mcp/<version>/huginn-mcp-<version>-runner.jar",
  "java-agents": [],
  "description": "Huginn (use with --quiet)"
}
```

## Build

```sh
./mvnw clean verify
```

The project is a parent POM with one module, `mcp`, which builds the runnable `mcp/target/huginn-mcp-<version>-runner.jar`.

Tests:

- **Unit tests** cover the helpers: reactor scanning, `maven.config` parsing, the workspace reader, tree and conflict rendering, the matcher, version bands, JSON output and sources extraction.
- **`McpServiceTest`** runs all tools but `search` against a generated demo reactor and a `file:` repository, with real Resolver and MIMA: version conflicts, a managed dependency, a reactor sibling that was never installed, `${revision}` from `maven.config`, and reload after a POM edit. It runs with a throwaway `user.home` (`mcp/target/test-home`) whose `settings.xml` mirrors everything to the file repository, so it never touches the network or your `~/.m2`.
- **`McpServerIT`** starts the packaged runner jar and talks to it over stdio like an MCP client: tool list, schemas, hints, results and error mapping. It runs in the `integration-test` phase. Its `search` tests query Maven Central, so `verify` needs network access for them.

## Status

First cut. It needs MIMA 3 with Maven Resolver 2; the POM overrides the Resolver 1.9 pinned by the Quarkus BOM.

Not yet done:

- **Release:** no release workflow, and the jbang catalog entry above is not added yet.

Known gaps:

- **Profile modules:** profiles that contribute `<modules>` are honoured when they are active by default or named with `-P` in `maven.config`. Property, JDK or OS activation is not evaluated for module discovery; it is for the effective models.
- **Maven 4:** POMs using model version 4.1.0 are not read, because the reader is Maven 3.9's.
- **Search:** Central only (its CSC endpoint, which wants an explicit `AND` between terms, so free text is joined with `AND`), and a single page of hits.
- **Plugins:** `versions` does not cover plugins, and plugin repositories are ignored.
- **Upstream candidates:** a "project mode" in MIMA's MavenModelReader, and a standalone reactor locator, would let `ProjectModels` and the reactor code move upstream.
