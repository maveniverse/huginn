/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.huginn.project;

import eu.maveniverse.maven.mima.context.Context;
import eu.maveniverse.maven.mima.context.ContextOverrides;
import eu.maveniverse.maven.mima.context.Runtimes;
import eu.maveniverse.maven.mima.context.internal.RuntimeSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.maven.model.Model;
import org.apache.maven.model.Repository;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.ArtifactTypeRegistry;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * One loaded reactor: effective models of all modules, a Resolver session that sees the
 * modules through a workspace reader, and remote repositories that include the ones the
 * project declares. Without those two, every answer would describe a different build than
 * the one the agent is editing.
 */
public final class ProjectSession implements AutoCloseable {

    /** A reactor module with its effective model; {@code effective} is null if the model failed to build. */
    public record Module(ReactorScanner.RawModule raw, Model effective, Artifact artifact, String problem) {
        public String key() {
            return effective != null ? effective.getGroupId() + ":" + effective.getArtifactId() : raw.key();
        }

        public String artifactId() {
            return effective != null ? effective.getArtifactId() : raw.artifactId();
        }

        public String packaging() {
            return effective != null ? effective.getPackaging() : raw.raw().getPackaging();
        }
    }

    private final Path root;
    private final MavenConfig config;
    private final Context baseContext;
    private final Context context;
    private final List<Module> modules;
    private final long fingerprint;

    private ProjectSession(
            Path root,
            MavenConfig config,
            Context baseContext,
            Context context,
            List<Module> modules,
            long fingerprint) {
        this.root = root;
        this.config = config;
        this.baseContext = baseContext;
        this.context = context;
        this.modules = modules;
        this.fingerprint = fingerprint;
    }

    public static ProjectSession open(Path root) throws IOException {
        MavenConfig config = MavenConfig.read(root);
        List<ReactorScanner.RawModule> raws = ReactorScanner.scan(root, config);

        ContextOverrides.Builder ob = ContextOverrides.create()
                .withUserSettings(true)
                .withBasedirOverride(root)
                .userProperties(config.userProperties())
                .withActiveProfileIds(config.activeProfiles())
                .withInactiveProfileIds(config.inactiveProfiles());
        if (config.offline()) {
            ob.offline(true);
        }
        Context base = Runtimes.INSTANCE.getRuntime().create(ob.build());
        try {
            ReactorWorkspaceReader workspace = new ReactorWorkspaceReader();
            DefaultRepositorySystemSession session = new DefaultRepositorySystemSession(base.repositorySystemSession());
            session.setWorkspaceReader(workspace);
            ArtifactTypeRegistry types = session.getArtifactTypeRegistry();

            // Seed the workspace from raw coordinates, so in-reactor parents and BOM imports resolve.
            for (ReactorScanner.RawModule raw : raws) {
                if (raw.groupId() != null && raw.version() != null) {
                    String packaging =
                            raw.raw().getPackaging() == null ? "jar" : raw.raw().getPackaging();
                    workspace.put(
                            raw.groupId(),
                            raw.artifactId(),
                            raw.version(),
                            raw.dir(),
                            extension(types, packaging),
                            null,
                            raw.dir().resolve("target/classes").toString(),
                            null);
                }
            }

            ProjectModels models = new ProjectModels(derive(base, session, base.contextOverrides()), session, config);
            List<Module> modules = new ArrayList<>();
            for (ReactorScanner.RawModule raw : raws) {
                try {
                    Model effective = models.effectiveModel(raw.pom());
                    Artifact artifact = Models.projectArtifact(effective, types);
                    workspace.put(
                            effective.getGroupId(),
                            effective.getArtifactId(),
                            effective.getVersion(),
                            raw.dir(),
                            artifact.getExtension(),
                            effective.getBuild() != null ? effective.getBuild().getFinalName() : null,
                            effective.getBuild() != null ? effective.getBuild().getOutputDirectory() : null,
                            effective);
                    modules.add(new Module(raw, effective, artifact, null));
                } catch (Exception e) {
                    modules.add(new Module(raw, null, null, rootMessage(e)));
                }
            }

            // Repositories the project declares are part of its build; settings and mirrors still apply.
            Map<String, RemoteRepository> repos = new LinkedHashMap<>();
            for (RemoteRepository r : base.contextOverrides().getRepositories()) {
                repos.put(r.getId(), r);
            }
            for (Module m : modules) {
                if (m.effective() != null) {
                    for (Repository r : m.effective().getRepositories()) {
                        repos.putIfAbsent(r.getId(), Models.toRemoteRepository(r));
                    }
                }
            }
            Context context = derive(
                    base,
                    session,
                    base.contextOverrides().toBuilder()
                            .repositories(new ArrayList<>(repos.values()))
                            .build());
            return new ProjectSession(
                    root, config, base, context, Collections.unmodifiableList(modules), fingerprint(root, raws));
        } catch (RuntimeException e) {
            base.close();
            throw e;
        }
    }

    /**
     * A context sharing the base context's repository system but with our own session and
     * repositories. Its closer is null, so closing it never tears down the shared system.
     */
    private static Context derive(Context base, RepositorySystemSession session, ContextOverrides overrides) {
        return new Context(
                (RuntimeSupport) base.getRuntime(),
                overrides,
                base.basedir(),
                base.mavenUserHome(),
                base.mavenSystemHome(),
                base.repositorySystem(),
                session,
                base.httpProxy(),
                base.lookup(),
                null);
    }

    private static String extension(ArtifactTypeRegistry types, String packaging) {
        var type = types.get(packaging);
        return type != null ? type.getExtension() : packaging;
    }

    /** Changes when any reactor POM or maven.config changes; module lists live in POMs. */
    static long fingerprint(Path root, List<ReactorScanner.RawModule> raws) {
        long h = 17;
        List<Path> files = new ArrayList<>();
        files.add(root.resolve(".mvn/maven.config"));
        for (ReactorScanner.RawModule r : raws) {
            files.add(r.pom());
        }
        for (Path f : files) {
            h = 31 * h + f.hashCode();
            try {
                h = 31 * h + Files.getLastModifiedTime(f).toMillis();
                h = 31 * h + Files.size(f);
            } catch (IOException e) {
                h = 31 * h - 1;
            }
        }
        return h;
    }

    /** Only stats known files: a new module always means an edited POM, which is among them. */
    boolean isStale() {
        return fingerprint(root, modules.stream().map(Module::raw).toList()) != fingerprint;
    }

    public Path root() {
        return root;
    }

    public MavenConfig config() {
        return config;
    }

    public Context context() {
        return context;
    }

    public List<Module> modules() {
        return modules;
    }

    public List<RemoteRepository> repositories() {
        return context.remoteRepositories();
    }

    public ArtifactTypeRegistry types() {
        return context.repositorySystemSession().getArtifactTypeRegistry();
    }

    public String rel(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        if (!abs.startsWith(root)) {
            return abs.toString();
        }
        String r = root.relativize(abs).toString().replace('\\', '/');
        return r.isEmpty() ? "." : r;
    }

    /**
     * Finds a module by path relative to the root, directory, artifactId or groupId:artifactId.
     * A null selector means the module containing {@code cwd}, or the root.
     */
    public Optional<Module> module(String selector, Path cwd) {
        if (selector == null || selector.isBlank()) {
            Module best = null;
            Path dir = cwd == null ? root : cwd.toAbsolutePath().normalize();
            for (Module m : modules) {
                if (dir.startsWith(m.raw().dir())
                        && (best == null
                                || m.raw().dir().getNameCount()
                                        > best.raw().dir().getNameCount())) {
                    best = m;
                }
            }
            return Optional.ofNullable(best != null ? best : modules.isEmpty() ? null : modules.get(0));
        }
        String s = selector.trim();
        if (s.endsWith("/pom.xml")) {
            s = s.substring(0, s.length() - "/pom.xml".length());
        }
        Path asPath = root.resolve(s).normalize();
        for (Module m : modules) {
            if (m.raw().dir().equals(asPath)) {
                return Optional.of(m);
            }
        }
        for (Module m : modules) {
            if (s.equals(m.key()) || s.equals(m.artifactId())) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    public List<String> moduleNames() {
        return modules.stream()
                .map(m -> rel(m.raw().dir()) + " (" + m.artifactId() + ")")
                .toList();
    }

    @Override
    public void close() {
        baseContext.close();
    }

    static String rootMessage(Throwable t) {
        String msg = null;
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c.getMessage() != null && !c.getMessage().isBlank()) {
                msg = c.getMessage();
            }
        }
        return msg != null ? msg : t.getClass().getName();
    }
}
