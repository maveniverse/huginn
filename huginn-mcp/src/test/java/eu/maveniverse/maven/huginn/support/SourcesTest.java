/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.huginn.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourcesTest {

    private static Path jar(Path tmp) throws IOException {
        Path jar = tmp.resolve("lib-1.0-sources.jar");
        try (OutputStream out = Files.newOutputStream(jar);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("com/acme/Foo.java"));
            zip.write("package com.acme; class Foo {}".getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("com/acme/Extensions.kt"));
            zip.write("package com.acme".getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("../../evil.txt"));
            zip.write("nope".getBytes(StandardCharsets.UTF_8));
        }
        return jar;
    }

    @Test
    void extractsOnceAndRefusesZipSlip(@TempDir Path tmp) throws IOException {
        Path cache = tmp.resolve("cache");
        Path jar = jar(tmp);
        Path dir = Sources.extract(jar, cache, "com.acme", "lib", "1.0");
        assertEquals(cache.resolve("com.acme/lib/1.0"), dir);
        assertTrue(Files.isRegularFile(dir.resolve("com/acme/Foo.java")));
        assertFalse(Files.exists(tmp.resolve("evil.txt")));
        assertFalse(Files.exists(cache.resolve("com.acme/evil.txt")));
        assertEquals(2, Sources.countSourceFiles(dir));

        Files.delete(dir.resolve("com/acme/Foo.java"));
        Sources.extract(jar, cache, "com.acme", "lib", "1.0");
        assertFalse(Files.exists(dir.resolve("com/acme/Foo.java")), "second call reuses the extracted directory");

        assertThrows(IOException.class, () -> Sources.extract(jar, cache, "..", "..", "x"));
    }

    @Test
    void aNewerJarIsExtractedAgain(@TempDir Path tmp) throws IOException {
        Path cache = tmp.resolve("cache");
        Path jar = jar(tmp);
        Path dir = Sources.extract(jar, cache, "com.acme", "lib", "1.0-SNAPSHOT");
        Files.delete(dir.resolve("com/acme/Foo.java"));
        Files.setLastModifiedTime(jar, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
        Sources.extract(jar, cache, "com.acme", "lib", "1.0-SNAPSHOT");
        assertTrue(Files.exists(dir.resolve("com/acme/Foo.java")));
    }

    @Test
    void findsClassesIncludingInnerAndKotlin(@TempDir Path tmp) throws IOException {
        Path dir = Sources.extract(jar(tmp), tmp.resolve("cache"), "com.acme", "lib", "1.0");
        assertEquals(
                dir.resolve("com/acme/Foo.java"),
                Sources.findClass(dir, "com.acme.Foo$Inner").orElseThrow());
        assertEquals(
                dir.resolve("com/acme/Extensions.kt"),
                Sources.findClass(dir, "com.acme.ExtensionsKt").orElseThrow());
        assertTrue(Sources.findClass(dir, "com.acme.Missing").isEmpty());
        Path outside = tmp.resolve("Outside.java");
        Files.writeString(outside, "class Outside {}");
        assertTrue(
                Sources.findClass(dir, outside.toString().replace(".java", "")).isEmpty());
    }
}
