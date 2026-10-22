/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.huginn.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MavenConfigTest {

    @Test
    void parsesPropertiesProfilesAndOffline() {
        MavenConfig c = MavenConfig.parse("-Drevision=1.2.3\n-Dchangelist=  -D sha1=abc\n-P ci,!slow,+extra --offline");
        assertEquals(Map.of("revision", "1.2.3", "changelist", "", "sha1", "abc"), c.userProperties());
        assertEquals(List.of("ci", "extra"), c.activeProfiles());
        assertEquals(List.of("slow"), c.inactiveProfiles());
        assertTrue(c.offline());
    }

    @Test
    void quotedValuesKeepSpaces() {
        MavenConfig c = MavenConfig.parse("\"-Dgreeting=hello world\" -Pfast");
        assertEquals("hello world", c.userProperties().get("greeting"));
        assertEquals(List.of("fast"), c.activeProfiles());
    }
}
