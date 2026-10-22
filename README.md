# Maven MCP

A project-aware MCP server for Maven, for coding agents. It answers questions about the build the agent is actually editing: that reactor's effective models, its repositories and its resolved dependency graph, rather than an arbitrary GAV on Maven Central.

Built on [Toolbox](https://github.com/maveniverse/toolbox) (`shared`) and [MIMA](https://github.com/maveniverse/mima), with the same Quarkus MCP stack as `toolbox:mcp`.

## Tools

There are six tools, all read-only. Every one takes an optional `project` (any directory or `pom.xml` inside the reactor; default: the server's working directory) and `module` (path relative to the root, artifactId, or `groupId:artifactId`). Results are compact JSON.

| Tool | Answers |
|---|---|
| `describe` | At the root: modules, inter-module dependencies, repositories in use, `maven.config` settings. For a module: its effective model, with dependencies, plugins and executions, properties and profiles. |
| `dependencies` | The resolved tree, as the build sees it, plus **conflicts**: which version was requested by whom, and which one won. `scope` is compile, runtime or test; `maxDepth` caps output on large trees. |
| `why` | Every path that brings an artifact in, **including requests that lost a conflict**, across all modules by default. One call answers both "why is X here" and "who wants the old X". |
| `versions` | Upgrade candidates from the project's own repositories: latest, latest within the same major, latest within the same minor; pre-releases skipped unless asked for. Without an artifact it lists outdated direct dependencies. |
| `search` | Maven Central search by text or `g:`/`a:` query. |
| `sources` | Unpacks a dependency's sources jar into `~/.cache/maven-mcp/sources/`, or returns one class's source. Without a version, uses the version this project resolves. |

Builds are deliberately not here. Running `mvn` with the agent-output extension gives agents a compact JSON report of the build itself; this server covers the questions a shell can't answer cheaply.

## How it differs from `toolbox:mcp`

- **Project first:** answers come from the reactor's effective models, including `dependencyManagement`, BOMs, profiles and exclusions, not from a standalone GAV.
- **The project's repositories:** repositories declared in the POMs are added to those from settings, and mirrors and authentication from settings apply. Toolbox's MCP passes Central explicitly on most calls.
- **Reactor siblings resolve from the working tree:** a workspace reader serves modules that were never installed, including their effective models, the same way Maven's `ReactorReader` does.
- **CI-friendly versions work:** `-D` and `-P` from `.mvn/maven.config` apply, with user properties winning as on the command line. MIMA's `MavenModelReader` deliberately demotes user properties for dependency POMs (MNG-7563), so project models are built separately in `ProjectModels`.
- **Fewer, broader tools:** six instead of fifteen, because every tool description costs the agent context on every turn.
- **Caching:** a loaded reactor is cached and reloaded only when a POM or `maven.config` changes.

## Use

With Claude Code, run this from the project directory:

```sh
claude mcp add maven -- jbang --quiet maven-mcp@maveniverse
```

Or in an MCP client config:

```json
"mcpServers": {
  "maven": { "command": "jbang", "args": ["--quiet", "maven-mcp@maveniverse"] }
}
```

The server works on its working directory by default. Set `MAVEN_MCP_PROJECT` to point it elsewhere.

The catalog entry still needs adding to `maveniverse/jbang-catalog`, next to `toolbox-mcp`:

```json
"maven-mcp": {
  "script-ref": "https://repo.maven.apache.org/maven2/eu/maveniverse/maven/mcp/maven-mcp/<version>/maven-mcp-<version>-runner.jar",
  "java-agents": [],
  "description": "Maven MCP (use with --quiet)"
}
```

## Build

```sh
./mvnw clean verify
```

## Status

First cut. It was written in a workspace without access to Maven Central, so the Maven build itself has **not been run** yet. What was checked:

- **Type-check:** all main sources were compiled against the exact released sources of Toolbox `shared` 0.16.0, MIMA 2.4.48, mase 1.1.2 and domtrip 1.6.0, with Maven 3.9.11 and Resolver 1.9.24 jars. The `quarkus-mcp-server` annotations were checked against the 1.13.1 sources.
- **Unit tests:** all 17 pass. They cover reactor scanning, `maven.config` parsing, the workspace reader, tree and conflict rendering, the matcher, version bands, JSON output and sources extraction.
- **End to end:** `McpService` was run against a demo reactor with a file-based repository, using MIMA's Sisu runtime. That covers every tool except `search`, which needs Central, plus reload after a POM edit. The Quarkus layer was not run.

Known gaps:

- **Profile modules:** profiles that contribute `<modules>` are honoured when they are active by default or named with `-P` in `maven.config`. Property, JDK or OS activation is not evaluated for module discovery; it is for the effective models.
- **Maven 4:** POMs using model version 4.1.0 are not read, because the reader is Maven 3.9's.
- **Search:** Central only. Toolbox's search pages through every hit, so this server queries a single page itself.
- **Plugins:** `versions` does not cover plugins, and plugin repositories are ignored.
- **Upstream candidates:** a "project mode" in MIMA's MavenModelReader, and a standalone `ReactorLocator` in Toolbox, would let `ProjectModels` and the reactor code move upstream.
