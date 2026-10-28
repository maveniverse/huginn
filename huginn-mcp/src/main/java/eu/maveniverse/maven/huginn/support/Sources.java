/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.huginn.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Unpacks sources jars into a cache directory, so agents can read library code with their
 * ordinary file tools. The local repository itself is never written to.
 */
public final class Sources {

    private static final String COMPLETE = ".huginn-complete";

    private Sources() {}

    public static Path cacheRoot() {
        String xdg = System.getenv("XDG_CACHE_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".cache");
        return base.resolve("huginn").resolve("sources");
    }

    /** Extracts once per jar; later calls return the existing directory until the jar changes (snapshots). */
    public static Path extract(Path jar, Path cacheRoot, String groupId, String artifactId, String version)
            throws IOException {
        Path dir = cacheRoot.resolve(groupId).resolve(artifactId).resolve(version);
        if (!dir.toAbsolutePath()
                .normalize()
                .startsWith(cacheRoot.toAbsolutePath().normalize())) {
            throw new IOException("invalid coordinates " + groupId + ":" + artifactId + ":" + version);
        }
        Path complete = dir.resolve(COMPLETE);
        if (Files.isRegularFile(complete)) {
            if (Files.getLastModifiedTime(complete).compareTo(Files.getLastModifiedTime(jar)) >= 0) {
                return dir;
            }
            deleteRecursively(dir); // a newer jar: drop what it no longer has
        }
        Files.createDirectories(dir);
        Path normalized = dir.toAbsolutePath().normalize();
        try (InputStream in = Files.newInputStream(jar);
                ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                Path target = normalized.resolve(e.getName()).normalize();
                if (!target.startsWith(normalized)) {
                    continue; // zip-slip: never write outside the cache directory
                }
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Files.writeString(dir.resolve(COMPLETE), jar.toString());
        return dir;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    /** {@code com.acme.Foo} or {@code com.acme.Foo$Inner} to its source file, any JVM language. */
    public static Optional<Path> findClass(Path dir, String className) {
        Path root = dir.toAbsolutePath().normalize();
        return findClassFile(dir, className)
                .filter(p -> p.toAbsolutePath().normalize().startsWith(root));
    }

    private static Optional<Path> findClassFile(Path dir, String className) {
        String outer = className.contains("$") ? className.substring(0, className.indexOf('$')) : className;
        String base = outer.replace('.', '/');
        for (String ext : new String[] {".java", ".kt", ".groovy", ".scala"}) {
            Path p = dir.resolve(base + ext);
            if (Files.isRegularFile(p)) {
                return Optional.of(p);
            }
        }
        // Kotlin top-level functions compile to a FooKt facade for Foo.kt.
        String simple = outer.substring(outer.lastIndexOf('.') + 1);
        if (simple.endsWith("Kt") && simple.length() > 2) {
            String pkgPath = outer.contains(".")
                    ? outer.substring(0, outer.lastIndexOf('.')).replace('.', '/') + "/"
                    : "";
            Path facade = dir.resolve(pkgPath + simple.substring(0, simple.length() - 2) + ".kt");
            if (Files.isRegularFile(facade)) {
                return Optional.of(facade);
            }
        }
        return Optional.empty();
    }

    public static long countSourceFiles(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().equals(COMPLETE))
                    .count();
        } catch (IOException e) {
            return -1;
        }
    }
}
