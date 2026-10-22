/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.mcp.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.maveniverse.maven.mcp.render.Json;
import java.util.List;
import org.junit.jupiter.api.Test;

class VersionsTest {

    @Test
    void recognizesPreReleases() {
        for (String v : List.of("2.0.0-RC1", "5.10.0-M1", "1.0-beta-2", "3.0.0.Alpha3", "1.0-SNAPSHOT", "21-ea+5")) {
            assertTrue(Versions.isPreview(v), v);
        }
        for (String v : List.of("33.0.0-jre", "1.2.3", "5.6.15.Final", "20240303", "2.17.1", "1.0-android")) {
            assertFalse(Versions.isPreview(v), v);
        }
    }

    @Test
    void candidatesPerBand() {
        List<String> newer = List.of("1.2.4", "1.2.5", "1.3.0", "2.0.0-RC1", "2.0.0", "2.1.0-M1");
        assertEquals(
                "{\"latest\":\"2.0.0\",\"latest_minor\":\"1.3.0\",\"latest_patch\":\"1.2.5\"}",
                Json.write(Versions.candidates("1.2.3", newer)));
        assertEquals(
                "{\"latest\":\"2.1.0-M1\",\"latest_patch\":\"2.0.0\"}",
                Json.write(Versions.candidates("2.0.0-RC1", List.of("2.0.0", "2.1.0-M1"))));
        assertEquals("{}", Json.write(Versions.candidates("3.0", List.of("3.1-beta"))));
    }

    @Test
    void recentKeepsNewestStable() {
        assertEquals(List.of("1.1", "1.2"), Versions.recent(List.of("1.0", "1.1", "1.2-RC1", "1.2"), false, 2));
    }
}
