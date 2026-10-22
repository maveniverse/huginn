/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.huginn;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A hermetic Maven world under a temp dir: a {@code file:} repository with a few fake libraries
 * (one version conflict, several releases, one sources jar, one plugin), and a two-module reactor that uses it.
 * The surefire fork runs with a throwaway {@code user.home}; its settings.xml mirrors everything
 * to the file repository, so nothing touches the network or the developer's ~/.m2.
 *
 * <pre>
 * app  -&gt; core (reactor sibling, never installed) -&gt; liba:1.0 -&gt; libc:1.0
 *                                                  -&gt; libb:1.0 -&gt; libc:2.0 (loses to libc:1.0)
 *      -&gt; libd:1.0 (test)
 * </pre>
 */
final class DemoReactor {

    final Path root;
    final Path repo;

    DemoReactor(Path base) throws IOException {
        this.repo = base.resolve("repo");
        this.root = base.resolve("proj");
        library("liba", List.of("1.0", "2.0"), "libc:1.0");
        library("libb", List.of("1.0"), "libc:2.0");
        library("libc", List.of("1.0", "2.0", "2.1-RC1", "3.0"), null);
        library("libd", List.of("1.0"), null);
        library("pluginx", List.of("1.0", "2.0"), null);
        sourcesJar("liba", "1.0");

        Path m2 = Path.of(System.getProperty("user.home")).resolve(".m2");
        deleteRecursively(m2.resolve("repository"));
        write(
                m2.resolve("settings.xml"),
                "<settings><mirrors><mirror><id>demo-mirror</id><mirrorOf>*</mirrorOf><url>" + repo.toUri()
                        + "</url></mirror></mirrors></settings>");
        write(root.resolve(".mvn/maven.config"), "-Drevision=1.4.0\n");
        write(root.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>parent</artifactId>
                  <version>${revision}</version>
                  <packaging>pom</packaging>
                  <modules><module>core</module><module>app</module></modules>
                  <properties><revision>0.0.0</revision></properties>
                  <repositories>
                    <repository><id>demo</id><url>%1$s</url></repository>
                  </repositories>
                  <pluginRepositories>
                    <pluginRepository><id>demo-plugins</id><url>%1$s</url></pluginRepository>
                  </pluginRepositories>
                  <dependencyManagement>
                    <dependencies>
                      <dependency><groupId>com.acme.lib</groupId><artifactId>libd</artifactId><version>1.0</version></dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """.formatted(repo.toUri()));
        module(
                "core",
                "<dependencies>" + dep("liba", "1.0", null) + dep("libb", "1.0", null) + "</dependencies>"
                        + "<build><plugins><plugin><groupId>com.acme.lib</groupId><artifactId>pluginx</artifactId>"
                        + "<version>1.0</version></plugin></plugins></build>");
        write(root.resolve("app/pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.acme</groupId><artifactId>parent</artifactId><version>${revision}</version></parent>
                  <artifactId>app</artifactId>
                  <dependencies>
                    <dependency><groupId>com.acme</groupId><artifactId>core</artifactId><version>${revision}</version></dependency>
                    <dependency><groupId>com.acme.lib</groupId><artifactId>libd</artifactId><scope>test</scope></dependency>
                  </dependencies>
                </project>
                """);
    }

    private void module(String name, String body) throws IOException {
        write(root.resolve(name + "/pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.acme</groupId><artifactId>parent</artifactId><version>${revision}</version></parent>
                  <artifactId>%s</artifactId>
                  %s
                </project>
                """.formatted(name, body));
    }

    private static String dep(String artifactId, String version, String scope) {
        return "<dependency><groupId>com.acme.lib</groupId><artifactId>" + artifactId + "</artifactId><version>"
                + version + "</version>" + (scope != null ? "<scope>" + scope + "</scope>" : "") + "</dependency>";
    }

    /** One library with the given releases; each release depends on {@code dependency} (artifactId:version). */
    private void library(String artifactId, List<String> versions, String dependency) throws IOException {
        Path dir = repo.resolve("com/acme/lib").resolve(artifactId);
        StringBuilder metadata = new StringBuilder("<metadata><versioning><versions>");
        for (String v : versions) {
            String deps = "";
            if (dependency != null) {
                String[] d = dependency.split(":");
                deps = "<dependencies>" + dep(d[0], d[1], null) + "</dependencies>";
            }
            write(
                    dir.resolve(v).resolve(artifactId + "-" + v + ".pom"),
                    "<project><modelVersion>4.0.0</modelVersion><groupId>com.acme.lib</groupId><artifactId>"
                            + artifactId + "</artifactId><version>" + v + "</version>" + deps + "</project>");
            metadata.append("<version>").append(v).append("</version>");
        }
        metadata.append("</versions></versioning></metadata>");
        write(dir.resolve("maven-metadata.xml"), metadata.toString());
    }

    private void sourcesJar(String artifactId, String version) throws IOException {
        Path jar = repo.resolve("com/acme/lib")
                .resolve(artifactId)
                .resolve(version)
                .resolve(artifactId + "-" + version + "-sources.jar");
        try (OutputStream out = Files.newOutputStream(jar);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("com/acme/lib/Hello.java"));
            zip.write("package com.acme.lib; public class Hello {}".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (var paths = Files.walk(dir)) {
                for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
    }

    static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
