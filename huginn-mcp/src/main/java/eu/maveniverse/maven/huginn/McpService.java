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
import eu.maveniverse.maven.huginn.support.Extensions;
import eu.maveniverse.maven.huginn.support.PluginDescriptors;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.model.Build;
import org.apache.maven.model.InputLocation;
import org.apache.maven.model.InputLocationTracker;
import org.apache.maven.model.InputSource;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.Profile;
import org.apache.maven.search.api.MAVEN;
import org.apache.maven.search.api.Record;
import org.apache.maven.search.api.SearchBackend;
import org.apache.maven.search.api.SearchRequest;
import org.apache.maven.search.api.SearchResponse;
import org.apache.maven.search.api.request.Paging;
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
import org.eclipse.aether.util.version.GenericVersionScheme;
import org.eclipse.aether.version.InvalidVersionSpecificationException;
import org.eclipse.aether.version.Version;

/**
 * The operations behind the MCP tools, independent of the MCP framework. Every operation
 * is project-first: it works on the reactor that contains the given path (default: the
 * server's working directory), with that project's effective models and repositories.
 */
public final class McpService implements AutoCloseable {

    private static final int MAX_PROPERTIES = 80;
    private static final int MAX_PATHS_PER_MODULE = 10;
    private static final int MAX_SOURCE_CHARS = 60_000;
    private static final GenericVersionScheme VERSION_SCHEME = new GenericVersionScheme();

    private final ProjectSessions sessions = new ProjectSessions();
    private final Path defaultDir;
    private final Path userHome;
    private final Path mavenHome; // nullable: installation not found
    private Context standaloneContext;

    public McpService(Path defaultDir) {
        this(defaultDir, Path.of(System.getProperty("user.home")), Extensions.findMavenHome());
    }

    McpService(Path defaultDir, Path userHome, Path mavenHome) {
        this.defaultDir = defaultDir.toAbsolutePath().normalize();
        this.userHome = userHome;
        this.mavenHome = mavenHome;
    }

    // ---- describe -----------------------------------------------------------------------------------

    public String describe(String project, String module, String plugin) {
        Path dir = dir(project);
        ProjectSession s = session(dir);
        if (plugin != null && !plugin.isBlank()) {
            return Json.write(pluginDetail(s, module(s, module, dir), plugin.trim()));
        }
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

        Map<String, List<String>> lines = new HashMap<>();
        List<Map<String, Object>> deps = new ArrayList<>();
        for (org.apache.maven.model.Dependency d : e.getDependencies()) {
            Dependency rd = Models.toDependency(d, s.types());
            Json.Obj dj = Json.obj().with("id", Models.id(rd.getArtifact()));
            dj.putAll(versionOrigin(s, m, m, d, lines));
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
                pj.putAll(versionOrigin(s, m, m, p, lines));
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
        Set<String> names = new TreeSet<>(props.stringPropertyNames());
        Map<String, Object> shown = new LinkedHashMap<>();
        for (String k : names) {
            if (shown.size() < MAX_PROPERTIES) {
                shown.put(k, props.getProperty(k));
            }
        }
        o.with("properties", shown);
        if (names.size() > MAX_PROPERTIES) {
            o.with("properties_omitted", names.size() - MAX_PROPERTIES);
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

    /**
     * One plugin as the module's effective model configures it. Executions are listed with their own
     * configuration; Maven merges each over the plugin's configuration when it runs them.
     */
    private Map<String, Object> pluginDetail(ProjectSession s, Module m, String selector) {
        if (m.effective() == null) {
            throw new ToolException("module " + s.rel(m.raw().dir()) + " has an invalid model: " + m.problem());
        }
        Predicate<Plugin> matches = pluginMatcher(selector.split(":"));
        Build build = m.effective().getBuild();
        boolean managedOnly = false;
        Optional<Plugin> found = build == null
                ? Optional.empty()
                : build.getPlugins().stream().filter(matches).findFirst();
        if (found.isEmpty() && build != null && build.getPluginManagement() != null) {
            found = build.getPluginManagement().getPlugins().stream()
                    .filter(matches)
                    .findFirst();
            managedOnly = found.isPresent();
        }
        Plugin p = found.orElseThrow(() -> new ToolException("plugin '" + selector + "' is not configured in "
                + s.rel(m.raw().dir()) + "; configured: "
                + (build == null
                        ? "none"
                        : build.getPlugins().stream().map(Plugin::getArtifactId).collect(Collectors.joining(", ")))));
        Json.Obj o = Json.obj()
                .with("module", s.rel(m.raw().dir()))
                .with("plugin", p.getGroupId() + ":" + p.getArtifactId() + ":" + p.getVersion());
        o.putAll(versionOrigin(s, m, m, p, new HashMap<>()));
        o.with("managed_only", managedOnly ? Boolean.TRUE : null)
                .with("configuration", xml(p.getConfiguration()))
                .with(
                        "executions",
                        p.getExecutions().stream()
                                .map(x -> Json.obj()
                                        .with("id", x.getId())
                                        .with("phase", x.getPhase())
                                        .with("goals", x.getGoals())
                                        .with("configuration", xml(x.getConfiguration())))
                                .toList())
                .with(
                        "dependencies",
                        p.getDependencies().stream()
                                .map(d -> d.getGroupId() + ":" + d.getArtifactId() + ":" + d.getVersion())
                                .toList());
        if (managedOnly) {
            o.with(
                    "note",
                    "only in pluginManagement: applies whenever the plugin runs in this module, also when the packaging's lifecycle binds it");
        } else if (!p.getExecutions().isEmpty()) {
            o.with("note", "each execution's configuration is merged over the plugin's configuration");
        }
        return o;
    }

    /** XML text of a configuration DOM, or null when there is none. */
    private static String xml(Object configuration) {
        return configuration == null
                ? null
                : configuration
                        .toString()
                        .replaceFirst("^<\\?xml[^>]*\\?>\\s*", "")
                        .trim();
    }

    // ---- dependencies ---------------------------------------------------------------------------------

    public String dependencies(
            String project, String module, String scope, Integer maxDepth, String override, String add) {
        Path dir = dir(project);
        ProjectSession s = session(dir);
        Module m = requireBuildableModule(s, module, dir);
        Scope rs = scope(scope);
        Artifact forced = gav(override, "override");
        Artifact added = gav(add, "add");
        if (forced != null || added != null) {
            return whatIf(s, m, rs, forced, added);
        }
        CollectResult cr = collect(s, m, rs, null, null);
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

    private static Artifact gav(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String[] gav = value.trim().split(":");
        if (gav.length != 3) {
            throw new ToolException(name + " must be groupId:artifactId:version");
        }
        return new DefaultArtifact(gav[0], gav[1], "jar", gav[2]);
    }

    /**
     * What changes on the classpath when one artifact is forced to a version, as dependencyManagement would,
     * and/or one is added as a direct dependency.
     */
    private String whatIf(ProjectSession s, Module m, Scope rs, Artifact forced, Artifact added) {
        Map<String, String> before =
                Graphs.selected(collect(s, m, rs, null, null).getRoot());
        CollectResult cr = collect(s, m, rs, forced, added);
        Map<String, String> after = Graphs.selected(cr.getRoot());
        List<Map<String, Object>> changed = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        before.forEach((k, v) -> {
            String now = after.get(k);
            if (now == null) {
                removed.add(k + ":" + v);
            } else if (!now.equals(v)) {
                changed.add(Json.obj().with("artifact", k).with("from", v).with("to", now));
            }
        });
        List<String> addedDeps = new ArrayList<>();
        after.forEach((k, v) -> {
            if (!before.containsKey(k)) {
                addedDeps.add(k + ":" + v);
            }
        });
        Graphs.Tree t = Graphs.tree(cr.getRoot(), 0);
        return Json.write(Json.obj()
                .with("module", s.rel(m.raw().dir()))
                .with("scope", rs.name().toLowerCase())
                .with("override", forced == null ? null : Models.id(forced))
                .with("add", added == null ? null : Models.id(added))
                .with("changed", changed)
                .with("added", addedDeps)
                .with("removed", removed)
                .with("conflicts", new ArrayList<>(t.conflicts.values()))
                .with("errors", errors(cr))
                .with(
                        "hint",
                        changed.isEmpty() && addedDeps.isEmpty() && removed.isEmpty()
                                ? "nothing changes: not a dependency of this module, or already at that version"
                                : added == null
                                        ? "to apply it, set the version in dependencyManagement (or the dependency itself)"
                                        : "'changed' lists versions the new dependency moves; pin them in"
                                                + " dependencyManagement if that is not wanted"));
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
            CollectResult cr = collect(s, m, rs, null, null);
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
                // declared first (cheap, finds plugins too), then the graph, like sources does
                ProjectSession ps = s.orElseThrow();
                Optional<String> declared =
                        module == null || module.isBlank() ? currentVersion(ps, parts[0], parts[1]) : Optional.empty();
                current = declared.or(() -> selectedVersion(ps, module, dir(project), parts[0], parts[1]))
                        .orElse(null);
            }
            List<RemoteRepository> repos =
                    s.map(ProjectSession::allRepositories).orElseGet(ctx::remoteRepositories);
            List<String> all = newerVersions(ctx, repos, parts[0], parts[1], current != null ? current : "0");
            Json.Obj o = Json.obj().with("artifact", parts[0] + ":" + parts[1]).with("current", current);
            if (current != null) {
                o.putAll(Versions.candidates(current, all, previews));
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
        // groupId:artifactId:version -> modules declaring it, and where its version is set
        Map<String, List<String>> declared = new TreeMap<>();
        Map<String, List<String>> declaredPlugins = new TreeMap<>();
        Map<String, Map<String, Object>> origins = new HashMap<>();
        Map<String, List<String>> lines = new HashMap<>();
        for (Module m : targets) {
            if (m.effective() == null) {
                continue;
            }
            for (org.apache.maven.model.Dependency d : m.effective().getDependencies()) {
                String key = d.getGroupId() + ":" + d.getArtifactId();
                if (reactorKeys.contains(key)
                        || "system".equals(d.getScope())
                        || d.getVersion() == null
                        || isRange(d.getVersion())) {
                    continue;
                }
                declared.computeIfAbsent(key + ":" + d.getVersion(), k -> new ArrayList<>())
                        .add(ps.rel(m.raw().dir()));
                origins.putIfAbsent(key + ":" + d.getVersion(), versionOrigin(ps, m, null, d, lines));
            }
            if (m.effective().getBuild() != null) {
                for (Plugin p : m.effective().getBuild().getPlugins()) {
                    if (p.getVersion() != null) {
                        String key = p.getGroupId() + ":" + p.getArtifactId() + ":" + p.getVersion();
                        declaredPlugins
                                .computeIfAbsent(key, k -> new ArrayList<>())
                                .add(ps.rel(m.raw().dir()));
                        origins.putIfAbsent(key, versionOrigin(ps, m, null, p, lines));
                    }
                }
            }
        }
        List<String> failed = new ArrayList<>();
        int[] upToDate = new int[1];
        List<Map<String, Object>> outdated = outdated(declared, origins, ps.context(), previews, failed, upToDate);
        List<Map<String, Object>> outdatedPlugins =
                outdated(declaredPlugins, origins, ps.pluginContext(), previews, failed, upToDate);
        return Json.write(Json.obj()
                .with("checked", declared.size() + declaredPlugins.size())
                .with("up_to_date", upToDate[0])
                .with("outdated", outdated)
                .with("outdated_plugins", outdatedPlugins)
                .with("errors", failed)
                .with(
                        "note",
                        "direct dependencies and plugins with versions as resolved in the effective model;"
                                + " to upgrade, edit version_in, or property_in when the version is a property;"
                                + " only plugins declared in <build><plugins> are checked, lifecycle plugins not"
                                + " declared there are not"));
    }

    private static List<Map<String, Object>> outdated(
            Map<String, List<String>> declared,
            Map<String, Map<String, Object>> origins,
            Context ctx,
            boolean previews,
            List<String> failed,
            int[] upToDate) {
        // One metadata lookup per artifact, the slow part on a cold cache: run them side by side. Pool
        // threads inherit this thread's context class loader, which common-pool threads would not.
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(8, declared.size())));
        try {
            Map<String, Future<List<String>>> lookups = new LinkedHashMap<>();
            for (String key : declared.keySet()) {
                String[] gav = key.split(":");
                lookups.put(
                        key, pool.submit(() -> newerVersions(ctx, ctx.remoteRepositories(), gav[0], gav[1], gav[2])));
            }
            List<Map<String, Object>> outdated = new ArrayList<>();
            for (Map.Entry<String, List<String>> e : declared.entrySet()) {
                String[] gav = e.getKey().split(":");
                try {
                    Map<String, Object> c = Versions.candidates(gav[2], await(lookups.get(e.getKey())), previews);
                    if (c.get("latest") == null) {
                        upToDate[0]++;
                        continue;
                    }
                    outdated.add(Json.obj()
                            .with("artifact", gav[0] + ":" + gav[1])
                            .with("current", gav[2])
                            .with("candidates", c)
                            .with("modules", e.getValue().size() <= 5 ? e.getValue() : null));
                    outdated.get(outdated.size() - 1).putAll(origins.getOrDefault(e.getKey(), Map.of()));
                } catch (ToolException ex) {
                    failed.add(gav[0] + ":" + gav[1] + ": " + ex.getMessage());
                }
            }
            return outdated;
        } finally {
            pool.shutdownNow();
        }
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.get();
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException r ? r : new ToolException(rootMessage(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("interrupted", e);
        }
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
                    .filter(x -> x != null && !x.isBlank() && !isRange(x))
                    .findFirst();
            if (v.isEmpty() && m.effective().getBuild() != null) {
                v = m.effective().getBuild().getPlugins().stream()
                        .filter(p -> groupId.equals(p.getGroupId()) && artifactId.equals(p.getArtifactId()))
                        .map(Plugin::getVersion)
                        .filter(x -> x != null && !x.isBlank())
                        .findFirst();
            }
            if (v.isPresent()) {
                return v;
            }
        }
        return Optional.empty();
    }

    /** A range has no single current version to compare against; the graph's selection does. */
    private static boolean isRange(String version) {
        return version.startsWith("[") || version.startsWith("(");
    }

    private static List<String> newerVersions(
            Context ctx, List<RemoteRepository> repos, String g, String a, String current) {
        try {
            VersionRangeRequest request =
                    new VersionRangeRequest(new DefaultArtifact(g, a, "pom", "(" + current + ",)"), repos, null);
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

    public String search(String query, Integer limit, Integer page) {
        if (query == null || query.isBlank()) {
            throw new ToolException("query is required, e.g. 'guava' or 'g:com.google.guava a:guava'");
        }
        int max = limit == null || limit <= 0 ? 20 : Math.min(limit, 100);
        int pageNo = page == null ? 0 : Math.max(0, page);
        // A class search returns one record per version, so fetch more records and keep one per artifact.
        // shortcut: past `limit` artifacts the rest of those records are not shown; a larger limit shows them
        boolean classSearch = query.matches("(?s).*\\b(fc|c):.*");
        int fetch = classSearch ? 200 : max;
        try (SearchBackend backend = SmoSearchBackendFactory.createCsc(new Java11HttpClientTransport())) {
            SearchResponse response =
                    backend.search(new SearchRequest(new Paging(fetch, pageNo), Query.query(centralQuery(query))));
            List<String> ids = new ArrayList<>();
            for (Record r : response.getPage()) {
                ids.add(r.getValue(MAVEN.GROUP_ID) + ":" + r.getValue(MAVEN.ARTIFACT_ID) + ":"
                        + r.getValue(MAVEN.VERSION));
            }
            List<String> results = newestPerArtifact(ids);
            return Json.write(Json.obj()
                    .with("query", query)
                    .with("total_hits", response.getTotalHits())
                    .with("page", pageNo)
                    .with(
                            "more",
                            (long) (pageNo + 1) * fetch < response.getTotalHits() || results.size() > max
                                    ? Boolean.TRUE
                                    : null)
                    .with("results", results.size() > max ? results.subList(0, max) : results)
                    .with(
                            "note",
                            classSearch
                                    ? "class search: every jar containing the class, shaded copies included,"
                                            + " sorted by artifactId; add g:<groupId> to narrow"
                                    : "Maven Central only; use versions for the project's own repositories"));
        } catch (IOException e) {
            throw new ToolException("search failed: " + rootMessage(e), e);
        }
    }

    /** One entry per groupId:artifactId, with its newest version: class searches return every version. */
    static List<String> newestPerArtifact(List<String> gavs) {
        Map<String, String> newest = new LinkedHashMap<>();
        for (String gav : gavs) {
            int colon = gav.lastIndexOf(':');
            newest.merge(gav.substring(0, colon), gav.substring(colon + 1), (a, b) -> newer(a, b));
        }
        return newest.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue())
                .toList();
    }

    private static String newer(String a, String b) {
        try {
            return VERSION_SCHEME.parseVersion(a).compareTo(VERSION_SCHEME.parseVersion(b)) >= 0 ? a : b;
        } catch (InvalidVersionSpecificationException e) {
            return a;
        }
    }

    static String centralQuery(String query) {
        // groupId:artifactId[:version] is rewritten to Central's field syntax; g:x or a:x already is
        String text = query.trim();
        // a bare SHA-1 identifies a jar by its content: Central's field for it is '1'
        if (text.matches("(?i)(1:)?[0-9a-f]{40}")) {
            return "1:" + text.substring(text.length() - 40).toLowerCase(Locale.ROOT);
        }
        if (text.matches("[^\\s:]+:[^\\s:]+(:[^\\s:]+)?") && !text.matches("(g|a|v|p|c|l|fc):.*")) {
            String[] gav = text.split(":");
            text = "g:" + gav[0] + " a:" + gav[1] + (gav.length > 2 ? " v:" + gav[2] : "");
        }
        // Central answers HTTP 400 to terms separated by a bare space: make the AND explicit
        if (!text.contains("\"") && !text.matches(".*\\b(AND|OR|NOT)\\b.*")) {
            text = String.join(" AND ", text.split("\\s+"));
        }
        return text;
    }

    // ---- extensions ---------------------------------------------------------------------------------

    public String extensions(String project) {
        // user and installation files need no project; repositories come from settings then
        Optional<ProjectSession> s = optionalSession(dir(project));
        Context ctx = s.map(ProjectSession::context).orElseGet(this::standaloneContext);
        Path projectFile =
                s.map(ps -> ps.root().resolve(".mvn").resolve("extensions.xml")).orElse(null);
        Path userFile = userHome.resolve(".m2").resolve("extensions.xml");
        Path installFile = mavenHome == null ? null : mavenHome.resolve("conf").resolve("extensions.xml");
        List<Extensions.Declared> declared = new ArrayList<>();
        List<Map<String, Object>> installed = new ArrayList<>();
        String[] scopes = {"project", "user", "installation"};
        Path[] files = {projectFile, userFile, installFile};
        for (int i = 0; i < files.length; i++) {
            if (files[i] == null) {
                continue;
            }
            try {
                for (Extensions.Declared d : Extensions.read(files[i])) {
                    declared.add(d);
                    installed.add(Json.obj()
                            .with("id", d.id())
                            .with("version", d.version())
                            .with("scope", scopes[i]));
                }
            } catch (IOException e) {
                throw new ToolException(rootMessage(e), e);
            }
        }
        List<Map<String, Object>> proposals = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        for (Extensions.Known known : Extensions.CATALOG) {
            if (Extensions.has(declared, known)) {
                continue;
            }
            String[] ga = known.id().split(":");
            List<String> release;
            try {
                release = Versions.recent(newerVersions(ctx, ctx.remoteRepositories(), ga[0], ga[1], "0"), false, 1);
            } catch (ToolException e) {
                unavailable.add(known.id() + " (" + known.name() + "): " + e.getMessage());
                continue;
            }
            if (release.isEmpty()) {
                unavailable.add(known.id() + " (" + known.name() + "): no release in the project's repositories");
                continue;
            }
            proposals.add(Json.obj()
                    .with("id", known.id())
                    .with("name", known.name())
                    .with("why", known.why())
                    .with("version", release.get(0))
                    .with("snippet", Extensions.snippet(known, release.get(0))));
        }
        boolean present = Files.isRegularFile(userFile);
        String mavenVersion = Extensions.mavenVersion(mavenHome);
        return Json.write(Json.obj()
                .with(
                        "files",
                        Arrays.stream(files)
                                .filter(f -> f != null)
                                .map(f ->
                                        f.equals(projectFile) ? s.orElseThrow().rel(f) : f.toString())
                                .toList())
                .with("mavenHome", mavenHome == null ? null : mavenHome.toString())
                .with("mavenVersion", mavenVersion)
                .with(
                        "maven",
                        "recommended: Maven 3.10.x (user-wide extensions need it; MWM requires it)"
                                + (mavenVersion != null && Extensions.olderThan310(mavenVersion)
                                        ? "; this installation is " + mavenVersion + ", suggest upgrading"
                                        : ""))
                .with("installed", installed)
                .with("proposals", proposals)
                .with("unavailable", unavailable)
                .with(
                        "howto",
                        "propose these to the user to be installed user-wide, in " + userFile + "; "
                                + (present
                                        ? "add the chosen snippets inside <extensions>"
                                        : "create it as <?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                                                + "<extensions> with the chosen snippets </extensions>")));
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
                    .getPath();
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
                .with("hint", "read files under dir, or call again with className=<fully qualified name>"));
    }

    // ---- plugin -----------------------------------------------------------------------------------

    public String plugin(String project, String module, String plugin, String goal) {
        if (plugin == null || plugin.isBlank()) {
            throw new ToolException(
                    "plugin is required, e.g. 'surefire' or 'org.apache.maven.plugins:maven-surefire-plugin'");
        }
        String[] parts = plugin.trim().split(":");
        Path dir = dir(project);
        Optional<ProjectSession> s = optionalSession(dir);
        Optional<Plugin> declared = s.flatMap(ps -> projectPlugin(ps, module, dir, parts));
        String groupId =
                parts.length >= 2 ? parts[0] : declared.map(Plugin::getGroupId).orElse("org.apache.maven.plugins");
        String artifactId = parts.length >= 2
                ? parts[1]
                : declared.map(Plugin::getArtifactId)
                        .orElse(parts[0].endsWith("-plugin") ? parts[0] : "maven-" + parts[0] + "-plugin");
        Context ctx = s.map(ProjectSession::pluginContext).orElseGet(this::standaloneContext);
        String version =
                parts.length >= 3 ? parts[2] : declared.map(Plugin::getVersion).orElse(null);
        String versionSource = parts.length >= 3 ? null : version != null ? "project" : "latest release";
        if (version == null) {
            List<String> release =
                    Versions.recent(newerVersions(ctx, ctx.remoteRepositories(), groupId, artifactId, "0"), false, 1);
            if (release.isEmpty()) {
                throw new ToolException("no release of " + groupId + ":" + artifactId
                        + " in the plugin repositories; pass groupId:artifactId:version");
            }
            version = release.get(0);
        }
        String id = groupId + ":" + artifactId + ":" + version;
        PluginDescriptors.Descriptor descriptor;
        try {
            Path jar = ctx.repositorySystem()
                    .resolveArtifact(
                            ctx.repositorySystemSession(),
                            new ArtifactRequest(
                                    new DefaultArtifact(groupId, artifactId, "jar", version),
                                    ctx.remoteRepositories(),
                                    null))
                    .getArtifact()
                    .getPath();
            descriptor = PluginDescriptors.read(jar);
        } catch (Exception e) {
            throw new ToolException("cannot read the plugin descriptor of " + id + ": " + rootMessage(e), e);
        }
        Json.Obj o = Json.obj().with("plugin", id).with("version_source", versionSource);
        if (goal == null || goal.isBlank()) {
            return Json.write(o.with("prefix", descriptor.goalPrefix())
                    .with(
                            "goals",
                            descriptor.mojos().stream()
                                    .map(m -> Json.obj()
                                            .with("goal", m.goal())
                                            .with("phase", m.phase())
                                            .with("description", m.description()))
                                    .toList())
                    .with("hint", "call again with goal=<goal> for its parameters"));
        }
        PluginDescriptors.Mojo mojo = descriptor.mojos().stream()
                .filter(m -> m.goal().equals(goal.trim()))
                .findFirst()
                .orElseThrow(() -> new ToolException("no goal '" + goal + "' in " + id + "; goals: "
                        + descriptor.mojos().stream()
                                .map(PluginDescriptors.Mojo::goal)
                                .collect(Collectors.joining(", "))));
        List<Map<String, Object>> parameters = new ArrayList<>();
        for (PluginDescriptors.Parameter p : mojo.parameters()) {
            parameters.add(Json.obj()
                    .with("name", p.name())
                    .with("type", p.type())
                    .with("required", p.required() ? Boolean.TRUE : null)
                    .with("property", p.property())
                    .with("default", p.defaultValue())
                    .with("deprecated", p.deprecated() ? Boolean.TRUE : null)
                    .with("description", p.description()));
        }
        return Json.write(o.with("goal", mojo.goal())
                .with("phase", mojo.phase())
                .with("description", mojo.description())
                .with("parameters", parameters)
                .with("note", "property is the user property, set as -D<property>=value"));
    }

    /**
     * The plugin as the project declares it, in the module's build plugins or plugin management first,
     * then in any module. A bare name matches {@code name}, {@code maven-name-plugin} and
     * {@code name-maven-plugin}.
     */
    private Optional<Plugin> projectPlugin(ProjectSession s, String module, Path dir, String[] parts) {
        Predicate<Plugin> matches = pluginMatcher(parts);
        List<Module> candidates = new ArrayList<>();
        candidates.add(module(s, module, dir));
        s.modules().stream().filter(m -> !candidates.contains(m)).forEach(candidates::add);
        for (Module m : candidates) {
            if (m.effective() == null || m.effective().getBuild() == null) {
                continue;
            }
            Stream<Plugin> all = Stream.concat(
                    m.effective().getBuild().getPlugins().stream(),
                    m.effective().getBuild().getPluginManagement() != null
                            ? m.effective().getBuild().getPluginManagement().getPlugins().stream()
                            : Stream.empty());
            Optional<Plugin> found = all.filter(matches).findFirst();
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static Predicate<Plugin> pluginMatcher(String[] parts) {
        String name = parts[0];
        return parts.length >= 2
                ? p -> parts[0].equals(p.getGroupId()) && parts[1].equals(p.getArtifactId())
                : p -> p.getArtifactId().equals(name)
                        || p.getArtifactId().equals("maven-" + name + "-plugin")
                        || p.getArtifactId().equals(name + "-maven-plugin");
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
            CollectResult cr = collect(s, m, Scope.TEST, null, null);
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

    private static final Pattern VERSION_PROPERTY = Pattern.compile("^[^<]*?\\$\\{([^}]+)}");

    /**
     * Where the version of a dependency or plugin is written: {@code version_in}, the POM (a reactor
     * path, else groupId:artifactId:version of a parent or BOM), and when it is a property,
     * {@code version_property} and {@code property_in}. A location in {@code own}'s POM is left out.
     */
    private static Map<String, Object> versionOrigin(
            ProjectSession s, Module m, Module own, InputLocationTracker element, Map<String, List<String>> lines) {
        Json.Obj o = Json.obj();
        InputLocation loc = element.getLocation("version");
        if (loc == null || loc.getSource() == null) {
            return o;
        }
        if (own == null || !isPomOf(own, loc.getSource())) {
            o.with("version_in", pomName(s, loc.getSource()));
        }
        String property = versionProperty(loc, lines);
        if (property == null) {
            return o;
        }
        o.with("version_property", property);
        if (s.config().userProperties().containsKey(property)) {
            return o.with("property_in", ".mvn/maven.config");
        }
        InputLocation props = m.effective().getLocation("properties");
        InputLocation p = props != null ? props.getLocation(property) : null;
        if (p != null && p.getSource() != null && (own == null || !isPomOf(own, p.getSource()))) {
            o.with("property_in", pomName(s, p.getSource()));
        }
        return o;
    }

    private static boolean isPomOf(Module m, InputSource source) {
        return source.getLocation() != null
                && Path.of(source.getLocation())
                        .toAbsolutePath()
                        .normalize()
                        .equals(m.raw().pom());
    }

    private static String pomName(ProjectSession s, InputSource source) {
        if (source.getLocation() != null) {
            Path pom = Path.of(source.getLocation()).toAbsolutePath().normalize();
            if (pom.startsWith(s.root())) {
                return s.rel(pom);
            }
        }
        return source.getModelId();
    }

    /** The property a {@code <version>} element starts with, read from the POM right after its start tag. */
    private static String versionProperty(InputLocation loc, Map<String, List<String>> lines) {
        String file = loc.getSource().getLocation();
        if (file == null || loc.getLineNumber() <= 0) {
            return null;
        }
        List<String> text = lines.computeIfAbsent(file, f -> {
            try {
                return Files.readAllLines(Path.of(f), StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                return List.of();
            }
        });
        if (loc.getLineNumber() > text.size()) {
            return null;
        }
        // shortcut: only the start tag's line is read, so a version on the next line shows no property
        String line = text.get(loc.getLineNumber() - 1);
        String rest = line.substring(Math.max(0, Math.min(line.length(), loc.getColumnNumber() - 1)));
        Matcher matcher = VERSION_PROPERTY.matcher(rest);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * @param forced nullable: an artifact whose version wins everywhere, direct and transitive
     * @param added nullable: an extra compile dependency, declared first so it wins over the module's own
     */
    private CollectResult collect(ProjectSession s, Module m, Scope scope, Artifact forced, Artifact added) {
        Model e = m.effective();
        Stream<Dependency> extra = added == null ? Stream.empty() : Stream.of(new Dependency(added, "compile"));
        List<Dependency> deps = Stream.concat(
                        extra,
                        Models.dependencies(e, s.types()).stream()
                                .filter(d -> scope.direct.contains(d.getScope().isEmpty() ? "compile" : d.getScope())))
                .map(d -> force(d, forced))
                .toList();
        List<Dependency> managed = new ArrayList<>();
        if (forced != null) {
            managed.add(new Dependency(forced, null)); // first entry wins over the project's own
        }
        Models.managedDependencies(e, s.types()).forEach(d -> managed.add(force(d, forced)));
        try {
            // verbose: conflict losers stay in the graph, so 'why' can show who asked for what
            DefaultRepositorySystemSession session =
                    new DefaultRepositorySystemSession(s.context().repositorySystemSession());
            session.setConfigProperty(DependencyManagerUtils.CONFIG_PROP_VERBOSE, true);
            session.setConfigProperty(ConflictResolver.CONFIG_PROP_VERBOSE, ConflictResolver.Verbosity.STANDARD);
            CollectRequest request = new CollectRequest();
            request.setRootArtifact(m.artifact());
            request.setDependencies(deps);
            request.setManagedDependencies(managed);
            request.setRepositories(s.context().remoteRepositories());
            CollectResult result = s.context().repositorySystem().collectDependencies(session, request);
            prune(result.getRoot(), scope.direct, Collections.newSetFromMap(new IdentityHashMap<>()));
            return result;
        } catch (DependencyCollectionException ex) {
            if (ex.getResult() != null && ex.getResult().getRoot() != null) {
                // partial graph plus its exceptions beats no answer
                prune(ex.getResult().getRoot(), scope.direct, Collections.newSetFromMap(new IdentityHashMap<>()));
                return ex.getResult();
            }
            throw new ToolException(
                    "cannot collect dependencies of " + s.rel(m.raw().dir()) + ": " + rootMessage(ex), ex);
        }
    }

    private static Dependency force(Dependency d, Artifact forced) {
        Artifact a = d.getArtifact();
        return forced != null
                        && forced.getGroupId().equals(a.getGroupId())
                        && forced.getArtifactId().equals(a.getArtifactId())
                ? d.setArtifact(a.setVersion(forced.getVersion()))
                : d;
    }

    /**
     * Like Maven, filters the resolved graph by classpath scope: the scope of a transitive node is
     * derived (runtime below compile stays runtime), so filtering direct dependencies is not enough.
     */
    private static void prune(DependencyNode node, Set<String> scopes, Set<DependencyNode> seen) {
        if (!seen.add(node)) {
            return;
        }
        node.setChildren(node.getChildren().stream()
                .filter(c -> c.getDependency() == null
                        || scopes.contains(
                                c.getDependency().getScope().isEmpty()
                                        ? "compile"
                                        : c.getDependency().getScope()))
                .collect(Collectors.toCollection(ArrayList::new)));
        for (DependencyNode c : node.getChildren()) {
            prune(c, scopes, seen);
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
        } catch (IOException | RuntimeException e) {
            throw new ToolException("cannot load the Maven project at " + dir + ": " + rootMessage(e), e);
        }
    }

    /** Empty only when there is no project at all; a project that fails to load is an error, not "no project". */
    private Optional<ProjectSession> optionalSession(Path dir) {
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("pom.xml"))) {
                return Optional.of(session(dir));
            }
        }
        return Optional.empty();
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

    /** First line only: a tool error should read as one sentence. */
    static String rootMessage(Throwable t) {
        String msg = ProjectSession.rootMessage(t);
        return msg.lines().findFirst().orElse(msg);
    }

    @Override
    public synchronized void close() {
        sessions.close();
        if (standaloneContext != null) {
            standaloneContext.close();
        }
    }
}
