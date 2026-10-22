/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.huginn;

import eu.maveniverse.maven.huginn.project.Models;
import eu.maveniverse.maven.huginn.project.ProjectSession;
import eu.maveniverse.maven.huginn.project.ProjectSession.Module;
import eu.maveniverse.maven.huginn.project.ProjectSessions;
import eu.maveniverse.maven.huginn.render.Graphs;
import eu.maveniverse.maven.huginn.render.Json;
import eu.maveniverse.maven.huginn.support.Sources;
import eu.maveniverse.maven.huginn.support.Versions;
import eu.maveniverse.maven.mima.context.Context;
import eu.maveniverse.maven.mima.context.ContextOverrides;
import eu.maveniverse.maven.mima.context.Runtimes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.Profile;
import org.apache.maven.search.api.MAVEN;
import org.apache.maven.search.api.Record;
import org.apache.maven.search.api.SearchBackend;
import org.apache.maven.search.api.SearchRequest;
import org.apache.maven.search.api.SearchResponse;
import org.apache.maven.search.api.request.Query;
import org.apache.maven.search.api.transport.java11.Java11HttpClientTransport;
import org.apache.maven.search.backend.smo.SmoSearchBackendFactory;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.util.graph.manager.DependencyManagerUtils;
import org.eclipse.aether.util.graph.transformer.ConflictResolver;
import org.eclipse.aether.version.Version;

/**
 * The six operations behind the MCP tools, independent of the MCP framework. Every operation
 * is project-first: it works on the reactor that contains the given path (default: the
 * server's working directory), with that project's effective models and repositories.
 */
public final class McpService implements AutoCloseable {

    private static final int MAX_PROPERTIES = 80;
    private static final int MAX_PATHS_PER_MODULE = 10;
    private static final int MAX_SOURCE_CHARS = 60_000;

    private final ProjectSessions sessions = new ProjectSessions();
    private final Path defaultDir;
    private Context standaloneContext;

    public McpService(Path defaultDir) {
        this.defaultDir = defaultDir.toAbsolutePath().normalize();
    }

    // ---- describe -----------------------------------------------------------------------------------

    public String describe(String project, String module) {
        Path dir = dir(project);
        ProjectSession s = session(dir);
        boolean atRoot = (module == null || module.isBlank()) && dir.equals(s.root());
        if (atRoot && s.modules().size() > 1) {
            return Json.write(overview(s));
        }
        Module m = module(s, module, dir);
        return Json.write(moduleDetail(s, m));
    }

    private Map<String, Object> overview(ProjectSession s) {
        Map<String, String> keyToPath = new LinkedHashMap<>();
        for (Module m : s.modules()) {
            keyToPath.put(m.key(), s.rel(m.raw().dir()));
        }
        List<Map<String, Object>> modules = new ArrayList<>();
        for (Module m : s.modules()) {
            Json.Obj o = Json.obj()
                    .with("path", s.rel(m.raw().dir()))
                    .with(
                            "id",
                            m.effective() != null
                                    ? Models.id(m.artifact())
                                    : m.raw().key())
                    .with("packaging", m.packaging());
            if (m.effective() != null) {
                Set<String> uses = new LinkedHashSet<>();
                for (org.apache.maven.model.Dependency d : m.effective().getDependencies()) {
                    String p = keyToPath.get(d.getGroupId() + ":" + d.getArtifactId());
                    if (p != null) {
                        uses.add(p);
                    }
                }
                o.with("depends_on", new ArrayList<>(uses));
            }
            o.with("problem", m.problem());
            modules.add(o);
        }
        return Json.obj()
                .with("root", s.root().toString())
                .with("modules", modules)
                .with("repositories", repositories(s.repositories()))
                .with("maven_config", mavenConfig(s))
                .with("hint", "call describe with module=<path> for one module's effective model");
    }

    private Map<String, Object> moduleDetail(ProjectSession s, Module m) {
        Json.Obj o = Json.obj().with("module", s.rel(m.raw().dir()));
        if (m.effective() == null) {
            return o.with("id", m.raw().key()).with("problem", m.problem());
        }
        Model e = m.effective();
        o.with("id", Models.id(m.artifact())).with("packaging", e.getPackaging());
        if (e.getParent() != null) {
            o.with(
                    "parent",
                    e.getParent().getGroupId() + ":" + e.getParent().getArtifactId() + ":"
                            + e.getParent().getVersion());
        }
        o.with("modules", e.getModules());

        List<Map<String, Object>> deps = new ArrayList<>();
        for (org.apache.maven.model.Dependency d : e.getDependencies()) {
            Dependency rd = Models.toDependency(d, s.types());
            Json.Obj dj = Json.obj().with("id", Models.id(rd.getArtifact()));
            if (d.getScope() != null && !"compile".equals(d.getScope())) {
                dj.with("scope", d.getScope());
            }
            if (d.isOptional()) {
                dj.with("optional", true);
            }
            if (!d.getExclusions().isEmpty()) {
                dj.with(
                        "exclusions",
                        d.getExclusions().stream()
                                .map(x -> x.getGroupId() + ":" + x.getArtifactId())
                                .toList());
            }
            deps.add(dj);
        }
        o.with("dependencies", deps);
        if (e.getDependencyManagement() != null) {
            o.with(
                    "managed_dependencies",
                    e.getDependencyManagement().getDependencies().size());
        }

        List<Map<String, Object>> plugins = new ArrayList<>();
        if (e.getBuild() != null) {
            for (Plugin p : e.getBuild().getPlugins()) {
                Json.Obj pj = Json.obj().with("id", p.getGroupId() + ":" + p.getArtifactId() + ":" + p.getVersion());
                List<Map<String, Object>> execs = new ArrayList<>();
                for (PluginExecution x : p.getExecutions()) {
                    execs.add(Json.obj()
                            .with("id", x.getId())
                            .with("phase", x.getPhase())
                            .with("goals", x.getGoals()));
                }
                plugins.add(pj.with("executions", execs));
            }
        }
        o.with("plugins", plugins);

        Properties props = e.getProperties();
        Map<String, Object> shown = new TreeMap<>();
        for (String k : props.stringPropertyNames()) {
            if (shown.size() < MAX_PROPERTIES) {
                shown.put(k, props.getProperty(k));
            }
        }
        o.with("properties", shown);
        if (props.size() > MAX_PROPERTIES) {
            o.with("properties_omitted", props.size() - MAX_PROPERTIES);
        }

        List<Map<String, Object>> profiles = new ArrayList<>();
        for (Profile p : e.getProfiles()) {
            profiles.add(Json.obj()
                    .with("id", p.getId())
                    .with(
                            "active_by_default",
                            p.getActivation() != null && p.getActivation().isActiveByDefault() ? Boolean.TRUE : null));
        }
        o.with("profiles", profiles);
        o.with(
                "repositories",
                e.getRepositories().stream()
                        .map(r -> Json.obj().with("id", r.getId()).with("url", r.getUrl()))
                        .toList());
        return o;
    }

    // ---- dependencies ---------------------------------------------------------------------------------

    public String dependencies(String project, String module, String scope, Integer maxDepth) {
        Path dir = dir(project);
        ProjectSession s = session(dir);
        Module m = requireBuildableModule(s, module, dir);
        Scope rs = scope(scope);
        CollectResult cr = collect(s, m, rs);
        Graphs.Tree t = Graphs.tree(cr.getRoot(), maxDepth == null ? 0 : Math.max(0, maxDepth));
        return Json.write(Json.obj()
                .with("module", s.rel(m.raw().dir()))
                .with("id", Models.id(m.artifact()))
                .with("scope", rs.name().toLowerCase())
                .with("count", t.count)
                .with("deps", t.children)
                .with("hidden_deps", t.truncated > 0 ? t.truncated : null)
                .with("conflicts", new ArrayList<>(t.conflicts.values()))
                .with("errors", errors(cr)));
    }

    // ---- why ---------------------------------------------------------------------------------------------

    public String why(String project, String module, String artifact, String scope) {
        if (artifact == null || artifact.isBlank()) {
            throw new ToolException("artifact is required, e.g. 'com.google.guava:guava' or 'guava'");
        }
        Path dir = dir(project);
        ProjectSession s = session(dir);
        Scope rs = scope(scope);
        Predicate<Artifact> matcher = Graphs.matcher(artifact);
        List<Module> targets = new ArrayList<>();
        if (module != null && !module.isBlank()) {
            targets.add(requireBuildableModule(s, module, dir));
        } else {
            for (Module m : s.modules()) {
                if (m.effective() != null && !m.effective().getDependencies().isEmpty()) {
                    targets.add(m);
                }
            }
        }
        List<Map<String, Object>> found = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (Module m : targets) {
            CollectResult cr = collect(s, m, rs);
            List<Map<String, Object>> paths = Graphs.paths(cr.getRoot(), matcher, MAX_PATHS_PER_MODULE);
            if (!paths.isEmpty()) {
                found.add(Json.obj().with("module", s.rel(m.raw().dir())).with("paths", paths));
            }
            errors.addAll(errors(cr));
        }
        Json.Obj out = Json.obj()
                .with("artifact", artifact)
                .with("scope", rs.name().toLowerCase())
                .with("modules_checked", targets.size())
                .with("found", found);
        if (found.isEmpty()) {
            out.with(
                    "hint",
                    rs == Scope.TEST
                            ? "not a dependency of any checked module in any scope"
                            : "not found in " + rs.name().toLowerCase() + " scope; try scope=test");
        }
        return Json.write(out.with("errors", errors));
    }

    // ---- versions -------------------------------------------------------------------------------------

    public String versions(String project, String module, String artifact, Boolean includePreviews) {
        boolean previews = includePreviews != null && includePreviews;
        Optional<ProjectSession> s = optionalSession(dir(project));
        Context ctx = s.map(ProjectSession::context).orElseGet(this::standaloneContext);

        if (artifact != null && !artifact.isBlank()) {
            String[] parts = artifact.trim().split(":");
            if (parts.length < 2) {
                throw new ToolException("artifact must be groupId:artifactId[:version]");
            }
            String current = parts.length >= 3 ? parts[parts.length - 1] : null;
            if (current == null && s.isPresent()) {
                current = currentVersion(s.orElseThrow(), parts[0], parts[1]).orElse(null);
            }
            List<String> all = newerVersions(ctx, parts[0], parts[1], current != null ? current : "0");
            Json.Obj o = Json.obj().with("artifact", parts[0] + ":" + parts[1]).with("current", current);
            if (current != null) {
                o.putAll(Versions.candidates(current, all));
            }
            return Json.write(o.with("recent", Versions.recent(all, previews, 15)));
        }

        ProjectSession ps = s.orElseThrow(
                () -> new ToolException("no Maven project at " + dir(project) + "; pass artifact=groupId:artifactId"));
        Path dir = dir(project);
        List<Module> targets = (module == null || module.isBlank()) && dir.equals(ps.root())
                ? ps.modules()
                : List.of(module(ps, module, dir));
        Set<String> reactorKeys = new LinkedHashSet<>();
        ps.modules().forEach(m -> reactorKeys.add(m.key()));
        // groupId:artifactId:version -> modules declaring it
        Map<String, List<String>> declared = new TreeMap<>();
        for (Module m : targets) {
            if (m.effective() == null) {
                continue;
            }
            for (org.apache.maven.model.Dependency d : m.effective().getDependencies()) {
                String key = d.getGroupId() + ":" + d.getArtifactId();
                if (reactorKeys.contains(key) || "system".equals(d.getScope()) || d.getVersion() == null) {
                    continue;
                }
                declared.computeIfAbsent(key + ":" + d.getVersion(), k -> new ArrayList<>())
                        .add(ps.rel(m.raw().dir()));
            }
        }
        List<Map<String, Object>> outdated = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int upToDate = 0;
        for (Map.Entry<String, List<String>> e : declared.entrySet()) {
            String[] gav = e.getKey().split(":");
            try {
                Map<String, Object> c =
                        Versions.candidates(gav[2], newerVersions(ps.context(), gav[0], gav[1], gav[2]));
                if (c.get("latest") == null) {
                    upToDate++;
                    continue;
                }
                outdated.add(Json.obj()
                        .with("artifact", gav[0] + ":" + gav[1])
                        .with("current", gav[2])
                        .with("candidates", c)
                        .with("modules", e.getValue().size() <= 5 ? e.getValue() : null));
            } catch (ToolException ex) {
                failed.add(gav[0] + ":" + gav[1] + ": " + ex.getMessage());
            }
        }
        return Json.write(
                Json.obj()
                        .with("checked", declared.size())
                        .with("up_to_date", upToDate)
                        .with("outdated", outdated)
                        .with("errors", failed)
                        .with(
                                "note",
                                "direct dependencies with versions as resolved in the effective model;"
                                        + " versions set via dependencyManagement change in the BOM or parent that manages them"));
    }

    private Optional<String> currentVersion(ProjectSession s, String groupId, String artifactId) {
        for (Module m : s.modules()) {
            if (m.effective() == null) {
                continue;
            }
            Stream<org.apache.maven.model.Dependency> all = Stream.concat(
                    m.effective().getDependencies().stream(),
                    m.effective().getDependencyManagement() != null
                            ? m.effective().getDependencyManagement().getDependencies().stream()
                            : Stream.empty());
            Optional<String> v = all.filter(d -> groupId.equals(d.getGroupId()) && artifactId.equals(d.getArtifactId()))
                    .map(org.apache.maven.model.Dependency::getVersion)
                    .filter(x -> x != null && !x.isBlank())
                    .findFirst();
            if (v.isPresent()) {
                return v;
            }
        }
        return Optional.empty();
    }

    private static List<String> newerVersions(Context ctx, String g, String a, String current) {
        try {
            VersionRangeRequest request = new VersionRangeRequest(
                    new DefaultArtifact(g, a, "pom", "(" + current + ",)"), ctx.remoteRepositories(), null);
            return ctx
                    .repositorySystem()
                    .resolveVersionRange(ctx.repositorySystemSession(), request)
                    .getVersions()
                    .stream()
                    .map(Version::toString)
                    .toList();
        } catch (Exception e) {
            throw new ToolException("cannot list versions of " + g + ":" + a + ": " + rootMessage(e), e);
        }
    }

    // ---- search --------------------------------------------------------------------------------------

    public String search(String query, Integer limit) {
        if (query == null || query.isBlank()) {
            throw new ToolException("query is required, e.g. 'guava' or 'g:com.google.guava a:guava'");
        }
        int max = limit == null || limit <= 0 ? 20 : Math.min(limit, 100);
        try (SearchBackend backend = SmoSearchBackendFactory.createSmo(new Java11HttpClientTransport())) {
            // groupId:artifactId[:version] is rewritten to Central's field syntax
            String text = query.trim();
            if (text.matches("[^\\s:]+:[^\\s:]+(:[^\\s:]+)?")) {
                String[] gav = text.split(":");
                text = "g:" + gav[0] + " a:" + gav[1] + (gav.length > 2 ? " v:" + gav[2] : "");
            }
            SearchResponse response = backend.search(new SearchRequest(Query.query(text)));
            List<String> ids = new ArrayList<>();
            for (Record r : response.getPage()) {
                if (ids.size() >= max) {
                    break;
                }
                ids.add(r.getValue(MAVEN.GROUP_ID) + ":" + r.getValue(MAVEN.ARTIFACT_ID) + ":"
                        + r.getValue(MAVEN.VERSION));
            }
            return Json.write(Json.obj()
                    .with("query", query)
                    .with("total_hits", response.getTotalHits())
                    .with("results", ids)
                    .with("note", "Maven Central only; use versions for the project's own repositories"));
        } catch (IOException e) {
            throw new ToolException("search failed: " + rootMessage(e), e);
        }
    }

    // ---- sources -----------------------------------------------------------------------------------

    public String sources(String project, String module, String artifact, String className) {
        if (artifact == null || artifact.isBlank()) {
            throw new ToolException("artifact is required: groupId:artifactId[:version]");
        }
        String[] parts = artifact.trim().split(":");
        if (parts.length < 2) {
            throw new ToolException("artifact must be groupId:artifactId[:version]");
        }
        Path dir = dir(project);
        Optional<ProjectSession> s = optionalSession(dir);
        String version = parts.length >= 3 ? parts[parts.length - 1] : null;
        if (version == null) {
            ProjectSession ps = s.orElseThrow(() -> new ToolException(
                    "no Maven project here to take the version from; pass groupId:artifactId:version"));
            version = selectedVersion(ps, module, dir, parts[0], parts[1])
                    .orElseThrow(() -> new ToolException(parts[0] + ":" + parts[1]
                            + " is not a dependency of this project; pass groupId:artifactId:version"));
        }
        Context ctx = s.map(ProjectSession::context).orElseGet(this::standaloneContext);
        Path jar;
        try {
            ArtifactRequest request = new ArtifactRequest(
                    new DefaultArtifact(parts[0], parts[1], "sources", "jar", version), ctx.remoteRepositories(), null);
            jar = ctx.repositorySystem()
                    .resolveArtifact(ctx.repositorySystemSession(), request)
                    .getArtifact()
                    .getFile()
                    .toPath();
        } catch (Exception e) {
            throw new ToolException(
                    "no sources jar for " + parts[0] + ":" + parts[1] + ":" + version + ": " + rootMessage(e), e);
        }
        Path extracted;
        try {
            extracted = Sources.extract(jar, Sources.cacheRoot(), parts[0], parts[1], version);
        } catch (IOException e) {
            throw new ToolException("cannot unpack " + jar + ": " + rootMessage(e), e);
        }
        Json.Obj o = Json.obj()
                .with("artifact", parts[0] + ":" + parts[1] + ":" + version)
                .with("dir", extracted.toString());
        if (className != null && !className.isBlank()) {
            Optional<Path> file = Sources.findClass(extracted, className.trim());
            if (file.isEmpty()) {
                return Json.write(o.with("error", "no source file for " + className + " in this artifact"));
            }
            try {
                String content = Files.readString(file.orElseThrow(), StandardCharsets.UTF_8);
                boolean cut = content.length() > MAX_SOURCE_CHARS;
                return Json.write(o.with("file", file.orElseThrow().toString())
                        .with("truncated", cut ? Boolean.TRUE : null)
                        .with("content", cut ? content.substring(0, MAX_SOURCE_CHARS) : content));
            } catch (IOException e) {
                throw new ToolException("cannot read " + file.orElseThrow() + ": " + rootMessage(e), e);
            }
        }
        return Json.write(o.with("files", Sources.countSourceFiles(extracted))
                .with("hint", "read files under dir, or call again with class=<fully qualified name>"));
    }

    /** The version the project actually ends up with: declared, managed, or selected in the graph. */
    private Optional<String> selectedVersion(ProjectSession s, String module, Path dir, String g, String a) {
        List<Module> candidates = new ArrayList<>();
        if (module != null && !module.isBlank()) {
            candidates.add(requireBuildableModule(s, module, dir));
        } else {
            s.module(null, dir).ifPresent(candidates::add);
            s.modules().stream().filter(m -> !candidates.contains(m)).forEach(candidates::add);
        }
        Predicate<Artifact> matcher = x -> g.equals(x.getGroupId()) && a.equals(x.getArtifactId());
        for (Module m : candidates) {
            if (m.effective() == null || m.effective().getDependencies().isEmpty()) {
                continue;
            }
            CollectResult cr = collect(s, m, Scope.TEST);
            Optional<String> v = findSelected(cr.getRoot(), matcher, new LinkedHashSet<>());
            if (v.isPresent()) {
                return v;
            }
        }
        return currentVersion(s, g, a);
    }

    private static Optional<String> findSelected(
            DependencyNode node, Predicate<Artifact> matcher, Set<DependencyNode> seen) {
        for (DependencyNode c : node.getChildren()) {
            if (c.getArtifact() == null || !seen.add(c)) {
                continue;
            }
            Object winner =
                    c.getData().get(org.eclipse.aether.util.graph.transformer.ConflictResolver.NODE_DATA_WINNER);
            if (winner == null && matcher.test(c.getArtifact())) {
                return Optional.of(c.getArtifact().getVersion());
            }
            Optional<String> v = findSelected(c, matcher, seen);
            if (v.isPresent()) {
                return v;
            }
        }
        return Optional.empty();
    }

    // ---- shared helpers ---------------------------------------------------------------------------------

    private CollectResult collect(ProjectSession s, Module m, Scope scope) {
        Model e = m.effective();
        List<Dependency> deps = Models.dependencies(e, s.types()).stream()
                .filter(d -> scope.direct.contains(d.getScope().isEmpty() ? "compile" : d.getScope()))
                .toList();
        try {
            // verbose: conflict losers stay in the graph, so 'why' can show who asked for what
            DefaultRepositorySystemSession session =
                    new DefaultRepositorySystemSession(s.context().repositorySystemSession());
            session.setConfigProperty(DependencyManagerUtils.CONFIG_PROP_VERBOSE, true);
            session.setConfigProperty(ConflictResolver.CONFIG_PROP_VERBOSE, ConflictResolver.Verbosity.STANDARD);
            CollectRequest request = new CollectRequest();
            request.setRootArtifact(m.artifact());
            request.setDependencies(deps);
            request.setManagedDependencies(Models.managedDependencies(e, s.types()));
            request.setRepositories(s.context().remoteRepositories());
            return s.context().repositorySystem().collectDependencies(session, request);
        } catch (DependencyCollectionException ex) {
            if (ex.getResult() != null && ex.getResult().getRoot() != null) {
                return ex.getResult(); // partial graph plus its exceptions beats no answer
            }
            throw new ToolException(
                    "cannot collect dependencies of " + s.rel(m.raw().dir()) + ": " + rootMessage(ex), ex);
        }
    }

    private static List<String> errors(CollectResult cr) {
        return cr.getExceptions().stream()
                .map(McpService::rootMessage)
                .distinct()
                .limit(10)
                .toList();
    }

    /** Dependency scopes that count as direct dependencies, like Maven's mojo resolution scopes. */
    private enum Scope {
        COMPILE(Set.of("provided", "compile", "system")),
        RUNTIME(Set.of("compile", "runtime")),
        TEST(Set.of("provided", "compile", "system", "runtime", "test"));

        final Set<String> direct;

        Scope(Set<String> direct) {
            this.direct = direct;
        }
    }

    private static Scope scope(String scope) {
        if (scope == null || scope.isBlank()) {
            return Scope.TEST;
        }
        return switch (scope.trim().toLowerCase()) {
            case "compile" -> Scope.COMPILE;
            case "runtime" -> Scope.RUNTIME;
            case "test" -> Scope.TEST;
            default -> throw new ToolException("scope must be compile, runtime or test");
        };
    }

    private Path dir(String project) {
        if (project == null || project.isBlank()) {
            return defaultDir;
        }
        Path p = defaultDir.resolve(project.trim()).normalize();
        return Files.isRegularFile(p) ? p.getParent() : p;
    }

    private ProjectSession session(Path dir) {
        try {
            return sessions.forPath(dir);
        } catch (IOException e) {
            throw new ToolException("no Maven project at " + dir + ": " + rootMessage(e), e);
        } catch (RuntimeException e) {
            throw new ToolException("cannot load the Maven project at " + dir + ": " + rootMessage(e), e);
        }
    }

    private Optional<ProjectSession> optionalSession(Path dir) {
        try {
            return Optional.of(sessions.forPath(dir));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Module module(ProjectSession s, String selector, Path cwd) {
        return s.module(selector, cwd)
                .orElseThrow(() -> new ToolException(
                        "no module '" + selector + "'; modules: " + String.join(", ", s.moduleNames())));
    }

    /** A module whose dependencies can be collected; an aggregator root is not a useful default. */
    private Module requireBuildableModule(ProjectSession s, String selector, Path cwd) {
        Module m = module(s, selector, cwd);
        if (m.effective() == null) {
            throw new ToolException("module " + s.rel(m.raw().dir()) + " has an invalid model: " + m.problem());
        }
        boolean aggregatorDefault = (selector == null || selector.isBlank())
                && "pom".equals(m.packaging())
                && !m.effective().getModules().isEmpty()
                && m.effective().getDependencies().isEmpty();
        if (aggregatorDefault) {
            throw new ToolException("the root is an aggregator without dependencies; pass module, one of: "
                    + String.join(", ", s.moduleNames()));
        }
        return m;
    }

    private static List<Map<String, Object>> repositories(List<RemoteRepository> repos) {
        return repos.stream()
                .map(r -> (Map<String, Object>) Json.obj().with("id", r.getId()).with("url", r.getUrl()))
                .toList();
    }

    private static Map<String, Object> mavenConfig(ProjectSession s) {
        return Json.obj()
                .with("user_properties", s.config().userProperties())
                .with("active_profiles", s.config().activeProfiles())
                .with("offline", s.config().offline() ? Boolean.TRUE : null);
    }

    private synchronized Context standaloneContext() {
        if (standaloneContext == null) {
            standaloneContext = Runtimes.INSTANCE
                    .getRuntime()
                    .create(ContextOverrides.create().withUserSettings(true).build());
        }
        return standaloneContext;
    }

    static String rootMessage(Throwable t) {
        String msg = null;
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c.getMessage() != null && !c.getMessage().isBlank()) {
                msg = c.getMessage();
            }
        }
        return msg != null ? msg.lines().findFirst().orElse(msg) : t.getClass().getSimpleName();
    }

    @Override
    public synchronized void close() {
        sessions.close();
        if (standaloneContext != null) {
            standaloneContext.close();
        }
    }
}
