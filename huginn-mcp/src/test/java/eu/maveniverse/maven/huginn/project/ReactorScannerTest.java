/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.huginn.project;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReactorScannerTest {

    private static void pom(Path dir, String body) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion>" + body + "</project>");
    }

    private static final String PARENT =
            "<parent><groupId>com.acme</groupId><artifactId>root</artifactId><version>${revision}</version></parent>";

    private static Path reactor(Path root) throws IOException {
        pom(
                root,
                "<groupId>com.acme</groupId><artifactId>root</artifactId><version>${revision}</version>"
                        + "<packaging>pom</packaging><properties><revision>0-SNAPSHOT</revision></properties>"
                        + "<modules><module>a</module><module>b/pom.xml</module></modules>"
                        + "<profiles><profile><id>extra</id><activation><activeByDefault>true</activeByDefault></activation>"
                        + "<modules><module>c</module></modules></profile></profiles>");
        pom(
                root.resolve("a"),
                PARENT + "<artifactId>a</artifactId><packaging>pom</packaging>"
                        + "<modules><module>sub</module></modules>");
        pom(
                root.resolve("a/sub"),
                "<parent><groupId>com.acme</groupId><artifactId>a</artifactId>"
                        + "<version>${revision}</version></parent><artifactId>sub</artifactId>");
        pom(root.resolve("b"), PARENT + "<groupId>com.other</groupId><artifactId>b</artifactId><version>9</version>");
        pom(root.resolve("c"), PARENT + "<artifactId>c</artifactId>");
        return root;
    }

    @Test
    void rootIsNearestDotMvn(@TempDir Path tmp) throws IOException {
        Path root = reactor(tmp.resolve("proj"));
        Files.createDirectories(root.resolve(".mvn"));
        assertEquals(root, ReactorScanner.findRoot(root.resolve("a/sub")));
        assertEquals(root, ReactorScanner.findRoot(root.resolve("a/sub/pom.xml")));
    }

    @Test
    void rootWithoutDotMvnIsOutermostAggregator(@TempDir Path tmp) throws IOException {
        Path root = reactor(tmp.resolve("proj"));
        Files.createDirectories(root.resolve("a/sub/src/main/java"));
        assertEquals(root, ReactorScanner.findRoot(root.resolve("a/sub/src/main/java")));
    }

    @Test
    void scansModulesWithInterpolatedCoordinates(@TempDir Path tmp) throws IOException {
        Path root = reactor(tmp.resolve("proj"));
        Files.createDirectories(root.resolve(".mvn"));
        Files.writeString(root.resolve(".mvn/maven.config"), "-Drevision=1.4.0");
        List<ReactorScanner.RawModule> modules = ReactorScanner.scan(root, MavenConfig.read(root));
        List<String> gavs = modules.stream()
                .map(m -> m.groupId() + ":" + m.artifactId() + ":" + m.version())
                .toList();
        assertEquals(
                List.of(
                        "com.acme:root:1.4.0",
                        "com.acme:a:1.4.0",
                        "com.acme:sub:1.4.0",
                        "com.other:b:9",
                        "com.acme:c:1.4.0"),
                gavs);
    }

    @Test
    void profileModulesFollowMavenConfig(@TempDir Path tmp) throws IOException {
        Path root = reactor(tmp.resolve("proj"));
        MavenConfig noExtra = MavenConfig.parse("-P !extra");
        assertEquals(4, ReactorScanner.scan(root, noExtra).size());
    }

    @Test
    void conditionalProfilesContributeModules(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("proj");
        pom(
                root,
                "<groupId>com.acme</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging>"
                        + "<profiles>"
                        + "<profile><id>ci</id><activation><property><name>ci</name></property></activation>"
                        + "<modules><module>ci-only</module></modules></profile>"
                        + "<profile><id>jdk</id><activation><jdk>[1,)</jdk></activation>"
                        + "<modules><module>any-jdk</module></modules></profile>"
                        + "<profile><id>never</id><activation><os><family>nonexistent</family></os></activation>"
                        + "<modules><module>never</module></modules></profile>"
                        + "</profiles>");
        for (String m : List.of("ci-only", "any-jdk", "never")) {
            pom(root.resolve(m), "<groupId>com.acme</groupId><artifactId>" + m + "</artifactId><version>1</version>");
        }
        assertEquals(
                List.of("root", "any-jdk"),
                ReactorScanner.scan(root, MavenConfig.EMPTY).stream()
                        .map(ReactorScanner.RawModule::artifactId)
                        .toList());
        assertEquals(
                List.of("root", "ci-only", "any-jdk"),
                ReactorScanner.scan(root, MavenConfig.parse("-Dci")).stream()
                        .map(ReactorScanner.RawModule::artifactId)
                        .toList());
    }
}
