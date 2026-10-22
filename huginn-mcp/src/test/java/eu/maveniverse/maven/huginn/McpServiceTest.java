/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.huginn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The tools against {@link DemoReactor}, with real Resolver and MIMA but no network. */
class McpServiceTest {

    @TempDir
    static Path tmp;

    static DemoReactor reactor;
    static McpService service;

    @BeforeAll
    static void setUp() throws IOException {
        reactor = new DemoReactor(tmp);
        Path mavenHome = tmp.resolve("maven-home");
        Files.createDirectories(mavenHome.resolve("lib"));
        Files.createFile(mavenHome.resolve("lib/maven-core-3.9.9.jar"));
        service = new McpService(reactor.root, tmp.resolve("home"), mavenHome);
    }

    @AfterAll
    static void tearDown() {
        service.close();
    }

    private static void assertContains(String json, String... parts) {
        for (String part : parts) {
            assertTrue(json.contains(part), () -> "expected " + part + " in " + json);
        }
    }

    // ---- describe -----------------------------------------------------------------------------------

    @Test
    void describeRootListsModulesAndMavenConfig() {
        String json = service.describe(null, null);
        assertContains(
                json,
                "\"id\":\"com.acme:parent:pom:1.4.0\"", // ${revision} from .mvn/maven.config
                "\"id\":\"com.acme:core:1.4.0\"",
                "\"id\":\"com.acme:app:1.4.0\"",
                "\"depends_on\":[\"core\"]", // sibling dependency, by module path
                "\"user_properties\":{\"revision\":\"1.4.0\"}",
                "\"id\":\"demo-mirror\"");
    }

    @Test
    void describeModuleShowsEffectiveModel() {
        String json = service.describe(null, "app");
        assertContains(
                json,
                "\"parent\":\"com.acme:parent:1.4.0\"",
                "\"id\":\"com.acme:core:1.4.0\"",
                "\"id\":\"com.acme.lib:libd:1.0\"", // version managed by the parent
                "\"scope\":\"test\"");
    }

    @Test
    void moduleSelectorAcceptsPathArtifactIdAndGa() {
        for (String selector : new String[] {"app", "com.acme:app"}) {
            assertContains(service.describe(null, selector), "\"module\":\"app\"");
        }
        assertContains(service.describe("app", null), "\"module\":\"app\"");
        assertContains(service.describe("app/pom.xml", null), "\"module\":\"app\"");
    }

    @Test
    void unknownModuleListsTheKnownOnes() {
        ToolException e = assertThrows(ToolException.class, () -> service.describe(null, "nope"));
        assertContains(e.getMessage(), "no module 'nope'", "core", "app");
    }

    // ---- dependencies -------------------------------------------------------------------------------

    @Test
    void dependenciesResolveSiblingsFromTheWorkingTreeAndReportConflicts() {
        String json = service.dependencies(null, "app", null, null);
        assertContains(
                json,
                "\"id\":\"com.acme:core:1.4.0\"", // never installed, served by the workspace reader
                "\"id\":\"com.acme.lib:liba:1.0\"",
                "\"id\":\"com.acme.lib:libc:1.0\"",
                "\"conflicts\":[{\"artifact\":\"com.acme.lib:libc\",\"selected\":\"1.0\","
                        + "\"requested\":[{\"version\":\"2.0\",\"by\":\"com.acme.lib:libb:1.0\"}]}]");
        assertFalse(json.contains("\"errors\""), json);
    }

    @Test
    void dependenciesScopeFiltersTestDependencies() {
        assertContains(service.dependencies(null, "app", "test", null), "com.acme.lib:libd:1.0");
        assertFalse(service.dependencies(null, "app", "compile", null).contains("libd"));
        assertFalse(service.dependencies(null, "app", "runtime", null).contains("libd"));
        assertThrows(ToolException.class, () -> service.dependencies(null, "app", "bogus", null));
    }

    @Test
    void dependenciesMaxDepthCountsWhatItHides() {
        String json = service.dependencies(null, "app", null, 1);
        assertContains(json, "\"id\":\"com.acme:core:1.4.0\"", "\"hidden_deps\":");
        assertFalse(json.contains("liba"), json);
    }

    @Test
    void dependenciesNeedsAModuleAtAnAggregatorRoot() {
        ToolException e = assertThrows(ToolException.class, () -> service.dependencies(null, null, null, null));
        assertContains(e.getMessage(), "aggregator", "app");
    }

    // ---- why ----------------------------------------------------------------------------------------

    @Test
    void whyShowsSelectedAndLostPathsInEveryModule() {
        String json = service.why(null, null, "libc", null);
        assertContains(
                json,
                "\"modules_checked\":2",
                "\"module\":\"core\"",
                "\"module\":\"app\"",
                "\"com.acme.lib:liba:1.0 (compile)\",\"com.acme.lib:libc:1.0 (compile)\"],\"status\":\"selected\"",
                "\"com.acme.lib:libb:1.0 (compile)\",\"com.acme.lib:libc:2.0 (compile)\"],"
                        + "\"status\":\"omitted: conflict, 1.0 selected\"");
    }

    @Test
    void whyAcceptsGaAndGavAndOneModule() {
        String json = service.why(null, "core", "com.acme.lib:libc:2.0", null);
        assertContains(json, "\"modules_checked\":1", "libc:2.0 (compile)");
        assertFalse(json.contains("libc:1.0 (compile)\"],\"status\":\"selected\""), json);
    }

    @Test
    void whyHintsWhenTheScopeHidesTheArtifact() {
        assertContains(service.why(null, null, "libd", "runtime"), "try scope=test");
        assertContains(service.why(null, null, "nonexistent", null), "not a dependency of any checked module");
        assertThrows(ToolException.class, () -> service.why(null, null, " ", null));
    }

    // ---- versions -----------------------------------------------------------------------------------

    @Test
    void versionsOfAnArtifactSkipPreviewsUnlessAsked() {
        String json = service.versions(null, null, "com.acme.lib:libc", null);
        assertContains(json, "\"recent\":[\"1.0\",\"2.0\",\"3.0\"]");
        assertFalse(json.contains("2.1-RC1"), json);
        assertContains(service.versions(null, null, "com.acme.lib:libc", true), "2.1-RC1");
    }

    @Test
    void versionsTakeTheCurrentVersionFromTheProject() {
        assertContains(
                service.versions(null, "core", "com.acme.lib:liba", null), "\"current\":\"1.0\"", "\"latest\":\"2.0\"");
        assertContains(service.versions(null, null, "com.acme.lib:liba:2.0", null), "\"current\":\"2.0\"");
    }

    @Test
    void versionsWithoutArtifactListOutdatedDirectDependencies() {
        String json = service.versions(null, null, null, null);
        assertContains(json, "\"artifact\":\"com.acme.lib:liba\"", "\"current\":\"1.0\"", "\"latest\":\"2.0\"");
        assertFalse(json.contains("\"artifact\":\"com.acme.lib:libb\""), json); // already the newest
        assertFalse(json.contains("com.acme:core"), json); // reactor modules are not upgrade candidates
    }

    @Test
    void versionsCoverPluginsToo() {
        String json = service.versions(null, null, null, null);
        assertContains(json, "\"outdated_plugins\":[{\"artifact\":\"com.acme.lib:pluginx\"", "\"latest\":\"2.0\"");
        assertContains(service.versions(null, "core", "com.acme.lib:pluginx", null), "\"current\":\"1.0\"", "\"2.0\"");
        assertFalse(service.versions(null, "app", null, null).contains("pluginx"));
    }

    // ---- extensions ---------------------------------------------------------------------------------

    @Test
    void extensionsProposeWhatIsPublishedAndMissing() throws IOException {
        String json = service.extensions(null);
        assertContains(
                json,
                "recommended: Maven 3.10.x",
                "\"mavenVersion\":\"3.9.9\"",
                "suggest upgrading",
                "user-wide",
                "\"id\":\"eu.maveniverse.maven.mimir:extension3\"",
                "\"version\":\"0.3.6\"", // the release, not the 0.4.0-RC1 preview
                "<artifactId>extension3</artifactId>",
                "create it as",
                "nisse:extension"); // not published to the demo repository: listed as unavailable
        assertFalse(json.contains("0.4.0-RC1"), json);
        assertFalse(json.contains("\"installed\""), json);
    }

    @Test
    void extensionsAlreadyDeclaredAreNotProposedAgain() throws IOException {
        Path file = tmp.resolve("home/.m2/extensions.xml");
        DemoReactor.write(
                file,
                "<extensions><extension><groupId>eu.maveniverse.maven.mimir</groupId>"
                        + "<artifactId>extension3</artifactId><version>0.3.5</version></extension></extensions>");
        try {
            String json = service.extensions(null);
            assertContains(
                    json,
                    "\"installed\":[{\"id\":\"eu.maveniverse.maven.mimir:extension3\",\"version\":\"0.3.5\","
                            + "\"scope\":\"user\"}]",
                    "add the chosen snippets");
            assertFalse(json.contains("\"proposals\""), json); // nothing else is published to the demo repository
        } finally {
            Files.delete(file);
        }
    }

    // ---- sources ------------------------------------------------------------------------------------

    @Test
    void sourcesUnpackAndReturnOneClass() {
        String json = service.sources(null, "app", "com.acme.lib:liba:1.0", "com.acme.lib.Hello");
        assertContains(json, "\"content\":\"package com.acme.lib; public class Hello {}\"");
        assertContains(service.sources(null, "app", "com.acme.lib:liba:1.0", null), "\"files\":1");
        assertContains(service.sources(null, "app", "com.acme.lib:liba:1.0", "com.acme.Missing"), "\"error\"");
    }

    @Test
    void sourcesWithoutVersionUseTheOneTheProjectResolves() {
        assertContains(
                service.sources(null, "app", "com.acme.lib:liba", null), "\"artifact\":\"com.acme.lib:liba:1.0\"");
        assertThrows(ToolException.class, () -> service.sources(null, "app", "com.acme.lib:libz", null));
        assertThrows(ToolException.class, () -> service.sources(null, "app", "com.acme.lib:libb:1.0", null)); // no jar
    }

    // ---- reload -------------------------------------------------------------------------------------

    @Test
    void editingAPomReloadsTheReactor() throws IOException {
        Path pom = reactor.root.resolve("core/pom.xml");
        String original = Files.readString(pom);
        assertContains(service.dependencies(null, "core", null, null), "liba:1.0");
        try {
            Files.writeString(
                    pom,
                    original.replace(
                            "<version>1.0</version></dependency><dependency>",
                            "<version>2.0</version></dependency><dependency>"));
            String json = service.dependencies(null, "core", null, null);
            assertContains(json, "com.acme.lib:liba:2.0");
            assertFalse(json.contains("liba:1.0"), json);
        } finally {
            Files.writeString(pom, original);
        }
        assertContains(service.dependencies(null, "core", null, null), "liba:1.0");
    }
}
