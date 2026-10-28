/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.huginn.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.model.Activation;
import org.apache.maven.model.ActivationFile;
import org.apache.maven.model.ActivationOS;
import org.apache.maven.model.ActivationProperty;
import org.apache.maven.model.Profile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileActivationTest {

    private static Profile profile(String id, Activation activation) {
        Profile p = new Profile();
        p.setId(id);
        p.setActivation(activation);
        return p;
    }

    private static List<String> ids(List<Profile> profiles, MavenConfig config, Path dir) {
        return ProfileActivation.active(profiles, config, dir).stream()
                .map(Profile::getId)
                .toList();
    }

    @Test
    void jdkPrefixNegationAndRange() {
        assertTrue(ProfileActivation.jdk("17", "17.0.2"));
        assertTrue(ProfileActivation.jdk("1.8", "1.8.0_292"));
        assertFalse(ProfileActivation.jdk("1.8", "17.0.2"));
        assertTrue(ProfileActivation.jdk("!1.8", "17.0.2"));
        assertFalse(ProfileActivation.jdk("!17", "17.0.2"));
        assertTrue(ProfileActivation.jdk("[11,)", "17.0.2"));
        assertTrue(ProfileActivation.jdk("[1.8,1.9)", "1.8.0_292"));
        assertFalse(ProfileActivation.jdk("[1.8,17)", "17.0.2"));
        assertFalse(ProfileActivation.jdk("[11,", "17.0.2")); // malformed ranges never activate
    }

    @Test
    void osMatchesNameFamilyArchVersionAndNegation() {
        ActivationOS os = new ActivationOS();
        os.setFamily("unix");
        os.setArch("amd64");
        assertTrue(ProfileActivation.os(os, "Linux", "amd64", "6.1"));
        assertFalse(ProfileActivation.os(os, "Windows 11", "amd64", "10.0"));
        assertFalse(ProfileActivation.os(os, "Linux", "aarch64", "6.1"));
        ActivationOS notWindows = new ActivationOS();
        notWindows.setFamily("!windows");
        assertTrue(ProfileActivation.os(notWindows, "Linux", "amd64", "6.1"));
        assertFalse(ProfileActivation.os(notWindows, "Windows 11", "amd64", "10.0"));
        ActivationOS mac = new ActivationOS();
        mac.setFamily("mac");
        mac.setName("mac os x");
        assertTrue(ProfileActivation.os(mac, "Mac OS X", "aarch64", "14.0"));
    }

    @Test
    void propertyPresenceValueAndNegation() {
        MavenConfig config = MavenConfig.parse("-Dci=true -Dflavor=blue");
        assertTrue(ProfileActivation.property(prop("ci", null), config.userProperties()));
        assertTrue(ProfileActivation.property(prop("ci", "true"), config.userProperties()));
        assertFalse(ProfileActivation.property(prop("ci", "false"), config.userProperties()));
        assertTrue(ProfileActivation.property(prop("!release", null), config.userProperties()));
        assertFalse(ProfileActivation.property(prop("!ci", null), config.userProperties()));
        assertTrue(ProfileActivation.property(prop("flavor", "!red"), config.userProperties()));
        assertFalse(ProfileActivation.property(prop("flavor", "!blue"), config.userProperties()));
        assertTrue(ProfileActivation.property(prop("java.version", null), config.userProperties())); // system property
    }

    private static ActivationProperty prop(String name, String value) {
        ActivationProperty p = new ActivationProperty();
        p.setName(name);
        p.setValue(value);
        return p;
    }

    @Test
    void fileExistsAndMissingResolveAgainstThePomDir(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("marker"), "x");
        ActivationFile exists = new ActivationFile();
        exists.setExists("${basedir}/marker");
        assertTrue(ProfileActivation.file(exists, tmp));
        ActivationFile relative = new ActivationFile();
        relative.setExists("marker");
        assertTrue(ProfileActivation.file(relative, tmp));
        ActivationFile missing = new ActivationFile();
        missing.setMissing("nope");
        assertTrue(ProfileActivation.file(missing, tmp));
        missing.setMissing("marker");
        assertFalse(ProfileActivation.file(missing, tmp));
    }

    @Test
    void allGivenConditionsMustHold() {
        Activation both = new Activation();
        both.setJdk("[1,)");
        both.setProperty(prop("ci", "true"));
        assertTrue(ProfileActivation.conditionsHold(both, MavenConfig.parse("-Dci=true"), Path.of(".")));
        assertFalse(ProfileActivation.conditionsHold(both, MavenConfig.EMPTY, Path.of("."))); // property missing
        assertFalse(ProfileActivation.conditionsHold(new Activation(), MavenConfig.EMPTY, Path.of(".")));
        assertFalse(ProfileActivation.conditionsHold(null, MavenConfig.EMPTY, Path.of(".")));
    }

    @Test
    void activeByDefaultCountsOnlyWhenNothingElseIsActive() {
        Activation byDefault = new Activation();
        byDefault.setActiveByDefault(true);
        Activation onCi = new Activation();
        onCi.setProperty(prop("ci", null));
        List<Profile> profiles = List.of(profile("dflt", byDefault), profile("ci", onCi), profile("named", null));

        assertEquals(List.of("dflt"), ids(profiles, MavenConfig.EMPTY, Path.of(".")));
        assertEquals(List.of("ci"), ids(profiles, MavenConfig.parse("-Dci"), Path.of(".")));
        assertEquals(List.of("named"), ids(profiles, MavenConfig.parse("-P named"), Path.of(".")));
        assertEquals(List.of(), ids(profiles, MavenConfig.parse("-P !dflt"), Path.of(".")));
        assertEquals(List.of("ci"), ids(profiles, MavenConfig.parse("-Dci -P !named"), Path.of(".")));
    }

    @Test
    void jdkComesFromJavaHomeWhenSet(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("release"), "IMPLEMENTOR=\"x\"\nJAVA_VERSION=\"21.0.4\"\n");
        assertEquals("21.0.4", ProfileActivation.javaVersion(tmp.toString()));
        assertEquals(
                System.getProperty("java.version"),
                ProfileActivation.javaVersion(tmp.resolve("none").toString()));
        assertEquals(System.getProperty("java.version"), ProfileActivation.javaVersion(null));
    }
}
