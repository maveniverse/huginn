/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.mcp;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.Tool.Annotations;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * MCP surface: six read-only tools. Descriptions are short on purpose, since every tool
 * description is sent to the agent on every turn. Results are compact JSON.
 */
@ApplicationScoped
public class MavenMcpTools {

    private static final String PROJECT =
            "Directory or pom.xml inside the Maven project; default: the server's working directory";
    private static final String MODULE =
            "Module path relative to the reactor root, artifactId, or groupId:artifactId; default: module of 'project'";
    private static final String SCOPE = "compile, runtime or test (default: test, which includes everything)";

    private final McpService service = new McpService(defaultDir());

    private static Path defaultDir() {
        String env = System.getenv("MAVEN_MCP_PROJECT");
        return Path.of(env != null && !env.isBlank() ? env : System.getProperty("user.dir"));
    }

    @Tool(
            description = "Describe the Maven project: at the reactor root, its modules, how they depend on each"
                    + " other and the repositories in use; for one module, its effective model (dependencies,"
                    + " plugins, properties, profiles).",
            annotations = @Annotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    String describe(
            @ToolArg(description = PROJECT, required = false) String project,
            @ToolArg(description = MODULE, required = false) String module) {
        return call(() -> service.describe(project, module));
    }

    @Tool(
            description = "Resolved dependency tree of a module, as the build sees it, plus version conflicts"
                    + " (which version was requested by whom and which one won).",
            annotations = @Annotations(readOnlyHint = true, idempotentHint = true))
    String dependencies(
            @ToolArg(description = PROJECT, required = false) String project,
            @ToolArg(description = MODULE, required = false) String module,
            @ToolArg(description = SCOPE, required = false) String scope,
            @ToolArg(
                            description =
                                    "Maximum tree depth; deeper dependencies are counted, not listed (default: all)",
                            required = false)
                    Integer maxDepth) {
        return call(() -> service.dependencies(project, module, scope, maxDepth));
    }

    @Tool(
            description = "Explain why an artifact is on the classpath: every dependency path that brings it in,"
                    + " including requests that lost a version conflict. Checks all modules unless one is given.",
            annotations = @Annotations(readOnlyHint = true, idempotentHint = true))
    String why(
            @ToolArg(
                            description = "groupId:artifactId[:version], '*' wildcards allowed; a bare word matches"
                                    + " groupId or artifactId")
                    String artifact,
            @ToolArg(description = PROJECT, required = false) String project,
            @ToolArg(description = MODULE, required = false) String module,
            @ToolArg(description = SCOPE, required = false) String scope) {
        return call(() -> service.why(project, module, artifact, scope));
    }

    @Tool(
            description = "Newer versions from the project's repositories. With artifact: candidates for that"
                    + " artifact (current version taken from the project if omitted). Without: outdated direct"
                    + " dependencies of the module, or of all modules at the reactor root.",
            annotations = @Annotations(readOnlyHint = true, idempotentHint = true))
    String versions(
            @ToolArg(description = "groupId:artifactId[:version]", required = false) String artifact,
            @ToolArg(description = PROJECT, required = false) String project,
            @ToolArg(description = MODULE, required = false) String module,
            @ToolArg(description = "Include alpha, beta, RC and milestone versions (default: false)", required = false)
                    Boolean includePreviews) {
        return call(() -> service.versions(project, module, artifact, includePreviews));
    }

    @Tool(
            description = "Search Maven Central for artifacts by name or coordinates, e.g. 'jackson databind' or"
                    + " 'g:com.fasterxml.jackson.core a:jackson-databind'.",
            annotations = @Annotations(readOnlyHint = true, idempotentHint = true))
    String search(
            @ToolArg(description = "Search text or g:/a:/v: query") String query,
            @ToolArg(description = "Maximum results (default 20, max 100)", required = false) Integer limit) {
        return call(() -> service.search(query, limit));
    }

    @Tool(
            description = "Source code of a dependency: unpacks its sources jar to a local directory, or returns"
                    + " one class's source. Without a version, uses the version this project resolves.",
            annotations = @Annotations(readOnlyHint = true, idempotentHint = true))
    String sources(
            @ToolArg(description = "groupId:artifactId[:version]") String artifact,
            @ToolArg(description = "Fully qualified class name to return the source of", required = false)
                    String className,
            @ToolArg(description = PROJECT, required = false) String project,
            @ToolArg(description = MODULE, required = false) String module) {
        return call(() -> service.sources(project, module, artifact, className));
    }

    private static String call(Supplier<String> op) {
        try {
            return op.get();
        } catch (ToolException e) {
            throw new ToolCallException(e.getMessage());
        } catch (RuntimeException e) {
            throw new ToolCallException(McpService.rootMessage(e), e);
        }
    }

    @PreDestroy
    void close() {
        service.close();
    }
}
