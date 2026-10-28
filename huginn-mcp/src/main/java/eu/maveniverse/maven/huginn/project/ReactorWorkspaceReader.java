/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.huginn.project;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.maven.model.Model;
import org.apache.maven.repository.internal.MavenWorkspaceReader;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.WorkspaceRepository;

/**
 * Serves reactor modules from the working tree, like Maven's own ReactorReader: POMs always,
 * packaged files or {@code target/classes} when they exist, and the module's effective model.
 * The model matters: without it Resolver rebuilds a sibling's POM as a dependency, where
 * maven.config user properties lose to POM properties (MNG-7563), so a {@code ${revision}}
 * reference inside the sibling would resolve to the POM's default instead of the build's.
 */
final class ReactorWorkspaceReader implements MavenWorkspaceReader {

    private record Entry(Path dir, String packagingExtension, String finalName, String outputDirectory, Model model) {}

    private final WorkspaceRepository repository = new WorkspaceRepository("reactor");
    private final Map<String, Entry> byGav = new ConcurrentHashMap<>();

    void put(
            String groupId,
            String artifactId,
            String version,
            Path dir,
            String packagingExtension,
            String finalName,
            String outputDirectory,
            Model model) {
        byGav.put(
                groupId + ":" + artifactId + ":" + version,
                new Entry(dir, packagingExtension, finalName, outputDirectory, model));
    }

    @Override
    public Model findModel(Artifact artifact) {
        Entry e = byGav.get(artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getBaseVersion());
        return e != null && e.model() != null ? e.model().clone() : null;
    }

    @Override
    public WorkspaceRepository getRepository() {
        return repository;
    }

    @Override
    public File findArtifact(Artifact artifact) {
        Entry e = byGav.get(artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getBaseVersion());
        if (e == null) {
            return null;
        }
        String classifier = artifact.getClassifier();
        if ("pom".equals(artifact.getExtension()) && classifier.isEmpty()) {
            return e.dir().resolve("pom.xml").toFile();
        }
        String name =
                (e.finalName() != null ? e.finalName() : artifact.getArtifactId() + "-" + artifact.getBaseVersion())
                        + (classifier.isEmpty() ? "" : "-" + classifier) + "." + artifact.getExtension();
        Path packaged = e.dir().resolve("target").resolve(name);
        if (Files.isRegularFile(packaged)) {
            return packaged.toFile();
        }
        if ("jar".equals(artifact.getExtension()) && classifier.isEmpty() && e.outputDirectory() != null) {
            Path classes = Path.of(e.outputDirectory());
            if (Files.isDirectory(classes)) {
                return classes.toFile();
            }
        }
        return null;
    }

    @Override
    public List<String> findVersions(Artifact artifact) {
        String prefix = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":";
        return byGav.keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .map(k -> k.substring(prefix.length()))
                .toList();
    }
}
