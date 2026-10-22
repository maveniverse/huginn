/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.mcp.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.model.Model;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReactorWorkspaceReaderTest {

    @Test
    void servesPomsAndBuildOutput(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("core");
        Files.createDirectories(dir.resolve("target/classes"));
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        ReactorWorkspaceReader r = new ReactorWorkspaceReader();
        Model model = new Model();
        model.setArtifactId("core");
        r.put(
                "com.acme",
                "core",
                "1.0-SNAPSHOT",
                dir,
                "jar",
                null,
                dir.resolve("target/classes").toString(),
                model);

        assertEquals(
                dir.resolve("pom.xml").toFile(), r.findArtifact(new DefaultArtifact("com.acme:core:pom:1.0-SNAPSHOT")));
        assertEquals(
                dir.resolve("target/classes").toFile(),
                r.findArtifact(new DefaultArtifact("com.acme:core:jar:1.0-SNAPSHOT")));
        assertNull(r.findArtifact(new DefaultArtifact("com.acme:core:jar:tests:1.0-SNAPSHOT")));
        assertNull(r.findArtifact(new DefaultArtifact("com.acme:other:jar:1.0-SNAPSHOT")));
        assertEquals(List.of("1.0-SNAPSHOT"), r.findVersions(new DefaultArtifact("com.acme:core:pom:0")));
        assertEquals(
                "core",
                r.findModel(new DefaultArtifact("com.acme:core:pom:1.0-SNAPSHOT"))
                        .getArtifactId());
        assertNull(r.findModel(new DefaultArtifact("com.acme:other:pom:1.0-SNAPSHOT")));

        Files.writeString(dir.resolve("target/core-1.0-SNAPSHOT.jar"), "jar");
        assertEquals(
                dir.resolve("target/core-1.0-SNAPSHOT.jar").toFile(),
                r.findArtifact(new DefaultArtifact("com.acme:core:jar:1.0-SNAPSHOT")));
    }
}
