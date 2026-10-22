/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.mcp.project;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.Model;
import org.apache.maven.model.Repository;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.ArtifactProperties;
import org.eclipse.aether.artifact.ArtifactType;
import org.eclipse.aether.artifact.ArtifactTypeRegistry;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.artifact.DefaultArtifactType;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;

/**
 * Conversions from the Maven model to Resolver types, equivalent to maven-core's
 * RepositoryUtils (which is not on the standalone classpath).
 */
public final class Models {

    private Models() {}

    public static Artifact projectArtifact(Model model, ArtifactTypeRegistry types) {
        String packaging = model.getPackaging() == null ? "jar" : model.getPackaging();
        ArtifactType type = types.get(packaging);
        String extension = type != null ? type.getExtension() : packaging;
        return new DefaultArtifact(model.getGroupId(), model.getArtifactId(), extension, model.getVersion());
    }

    public static Dependency toDependency(org.apache.maven.model.Dependency d, ArtifactTypeRegistry types) {
        String typeId = d.getType() == null ? "jar" : d.getType();
        ArtifactType type = types.get(typeId);
        if (type == null) {
            type = new DefaultArtifactType(typeId);
        }
        boolean system = d.getSystemPath() != null && !d.getSystemPath().isBlank();
        Map<String, String> props = system ? Map.of(ArtifactProperties.LOCAL_PATH, d.getSystemPath()) : null;
        Artifact artifact = new DefaultArtifact(
                d.getGroupId(), d.getArtifactId(), d.getClassifier(), type.getExtension(), d.getVersion(), props, type);
        List<org.eclipse.aether.graph.Exclusion> exclusions = new ArrayList<>();
        for (Exclusion e : d.getExclusions()) {
            exclusions.add(new org.eclipse.aether.graph.Exclusion(e.getGroupId(), e.getArtifactId(), "*", "*"));
        }
        Boolean optional = d.getOptional() != null ? d.isOptional() : null;
        return new Dependency(artifact, d.getScope(), optional, exclusions);
    }

    public static List<Dependency> dependencies(Model model, ArtifactTypeRegistry types) {
        return model.getDependencies().stream().map(d -> toDependency(d, types)).toList();
    }

    public static List<Dependency> managedDependencies(Model model, ArtifactTypeRegistry types) {
        if (model.getDependencyManagement() == null) {
            return List.of();
        }
        return model.getDependencyManagement().getDependencies().stream()
                .map(d -> toDependency(d, types))
                .toList();
    }

    public static RemoteRepository toRemoteRepository(Repository r) {
        return new RemoteRepository.Builder(r.getId(), r.getLayout() == null ? "default" : r.getLayout(), r.getUrl())
                .setReleasePolicy(policy(r.getReleases()))
                .setSnapshotPolicy(policy(r.getSnapshots()))
                .build();
    }

    private static RepositoryPolicy policy(org.apache.maven.model.RepositoryPolicy p) {
        if (p == null) {
            return new RepositoryPolicy();
        }
        return new RepositoryPolicy(
                p.isEnabled(),
                p.getUpdatePolicy() != null ? p.getUpdatePolicy() : RepositoryPolicy.UPDATE_POLICY_DAILY,
                p.getChecksumPolicy() != null ? p.getChecksumPolicy() : RepositoryPolicy.CHECKSUM_POLICY_WARN);
    }

    /** {@code g:a:v}, with extension and classifier only when they are not the plain jar. */
    public static String id(Artifact a) {
        StringBuilder sb = new StringBuilder(a.getGroupId()).append(':').append(a.getArtifactId());
        boolean plain = "jar".equals(a.getExtension()) && a.getClassifier().isEmpty();
        if (!plain) {
            sb.append(':').append(a.getExtension());
            if (!a.getClassifier().isEmpty()) {
                sb.append(':').append(a.getClassifier());
            }
        }
        return sb.append(':').append(a.getVersion()).toString();
    }
}
