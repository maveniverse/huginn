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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExtensionsTest {

    private static final String XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <extensions>
              <extension>
                <groupId>eu.maveniverse.maven.nisse</groupId>
                <artifactId>extension3</artifactId>
                <version>0.4.4</version>
              </extension>
              <extension>
                <groupId>com.acme</groupId>
                <artifactId>other</artifactId>
              </extension>
            </extensions>
            """;

    @Test
    void readsDeclaredExtensionsAndToleratesAMissingFile(@TempDir Path tmp) throws IOException {
        assertEquals(List.of(), Extensions.read(tmp.resolve("extensions.xml")));
        Files.writeString(tmp.resolve("extensions.xml"), XML);
        List<Extensions.Declared> declared = Extensions.read(tmp.resolve("extensions.xml"));
        assertEquals(
                List.of(
                        new Extensions.Declared("eu.maveniverse.maven.nisse", "extension3", "0.4.4"),
                        new Extensions.Declared("com.acme", "other", null)),
                declared);
    }

    @Test
    void anyOfTheKnownCoordinatesCountsAsHavingIt(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("extensions.xml"), XML);
        List<Extensions.Declared> declared = Extensions.read(tmp.resolve("extensions.xml"));
        for (Extensions.Known known : Extensions.CATALOG) {
            assertEquals("Nisse".equals(known.name()), Extensions.has(declared, known), known.name());
        }
    }

    @Test
    void doctypesAndBrokenFilesAreRejected(@TempDir Path tmp) throws IOException {
        Files.writeString(
                tmp.resolve("evil.xml"), "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>");
        assertThrows(IOException.class, () -> Extensions.read(tmp.resolve("evil.xml")));
        Files.writeString(tmp.resolve("broken.xml"), "<extensions>");
        assertThrows(IOException.class, () -> Extensions.read(tmp.resolve("broken.xml")));
    }

    @Test
    void snippetIsAnExtensionElement() {
        String s = Extensions.snippet(Extensions.CATALOG.get(0), "1.2.3");
        assertTrue(s.startsWith("<extension>") && s.endsWith("</extension>"), s);
        assertTrue(s.contains("<version>1.2.3</version>"), s);
        assertFalse(s.contains("<?xml"), s);
    }
}
