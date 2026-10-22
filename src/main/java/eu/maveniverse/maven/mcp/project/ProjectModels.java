/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.mcp.project;

import eu.maveniverse.maven.mima.context.Context;
import eu.maveniverse.maven.mima.extensions.mmr.internal.ModelResolverImpl;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.impl.RemoteRepositoryManager;

/**
 * Builds effective models of reactor POMs the way Maven builds project models. MIMA's
 * MavenModelReader is made for dependency POMs and deliberately demotes user properties to
 * system properties (MNG-7563), so a project's own {@code <revision>} default would win over
 * {@code -Drevision} from maven.config; for the project itself, user properties must win.
 */
final class ProjectModels {

    private final ModelBuilder modelBuilder;
    private final Context context;
    private final RepositorySystemSession session;
    private final RemoteRepositoryManager remoteRepositoryManager;
    private final MavenConfig config;

    ProjectModels(Context context, RepositorySystemSession session, MavenConfig config) {
        this.context = context;
        this.session = session;
        this.config = config;
        this.modelBuilder = context.lookup()
                .lookup(ModelBuilder.class)
                .orElseThrow(() -> new IllegalStateException("ModelBuilder not available"));
        this.remoteRepositoryManager = context.lookup()
                .lookup(RemoteRepositoryManager.class)
                .orElseThrow(() -> new IllegalStateException("RemoteRepositoryManager not available"));
    }

    Model effectiveModel(Path pom) throws ModelBuildingException {
        DefaultModelBuildingRequest request = new DefaultModelBuildingRequest();
        request.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);
        request.setProcessPlugins(false);
        request.setTwoPhaseBuilding(false);
        request.setLocationTracking(false);
        request.setPomFile(pom.toFile());
        request.setSystemProperties(properties(session.getSystemProperties()));
        request.setUserProperties(properties(session.getUserProperties()));
        request.setActiveProfileIds(config.activeProfiles());
        request.setInactiveProfileIds(config.inactiveProfiles());
        request.setModelResolver(new ModelResolverImpl(
                context.repositorySystem(),
                session,
                null,
                "maven-mcp",
                remoteRepositoryManager,
                context.remoteRepositories()));
        return modelBuilder.build(request).getEffectiveModel();
    }

    private static Properties properties(Map<String, String> map) {
        Properties p = new Properties();
        p.putAll(map);
        return p;
    }
}
