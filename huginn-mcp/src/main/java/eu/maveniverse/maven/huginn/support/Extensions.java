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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** Core extensions of {@code .mvn/extensions.xml}: reading what a project has, and what is worth proposing. */
public final class Extensions {

    /**
     * An extension worth proposing. {@code ids} are the {@code groupId:artifactId} coordinates that
     * count as having it (the first one is what gets proposed).
     */
    public record Known(String name, List<String> ids, String why) {
        public String id() {
            return ids.get(0);
        }
    }

    /** What the server proposes; versions are looked up at call time, never pinned here. */
    public static final List<Known> CATALOG = List.of(
            new Known(
                    "MWM",
                    List.of("eu.maveniverse.maven.mwm:extension3.10"),
                    "Makes Maven git worktree aware, and keeps installed artifacts within worktree. Requires Maven 3.10+."),
            new Known(
                    "Mimir",
                    List.of("eu.maveniverse.maven.mimir:extension3"),
                    "System-wide cache between Resolver and transport: faster resolution across projects,"
                            + " and the local repository can be wiped without pain. Requires Maven 3.9+."),
            new Known(
                    "Nisse",
                    List.of("eu.maveniverse.maven.nisse:extension3"),
                    "Property sources published to the build (e.g. a version derived from Git tags), a more"
                            + " reliable CI-friendly versions implementation on Maven 3.9+, and an OS detector"
                            + " replacement."),
            new Known(
                    "Muninn",
                    List.of("eu.maveniverse.maven.muninn:extension3"),
                    "Replaces console output with a compact JSON build report meant for coding agents on Maven 3.9+."));

    /** An extension as declared in {@code extensions.xml}. */
    public record Declared(String groupId, String artifactId, String version) {
        public String id() {
            return groupId + ":" + artifactId;
        }
    }

    private Extensions() {}

    /** The extensions declared in the file; empty if it is absent. */
    public static List<Declared> read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try (InputStream in = Files.newInputStream(file)) {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            NodeList nodes = f.newDocumentBuilder().parse(in).getElementsByTagName("extension");
            List<Declared> out = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                Element e = (Element) nodes.item(i);
                out.add(new Declared(text(e, "groupId"), text(e, "artifactId"), text(e, "version")));
            }
            return out;
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("cannot parse " + file + ": " + e.getMessage(), e);
        }
    }

    /** Maven installation: {@code MAVEN_HOME}/{@code M2_HOME}, else the one {@code mvn} on PATH belongs to. */
    public static Path findMavenHome() {
        for (String v : new String[] {"MAVEN_HOME", "M2_HOME"}) {
            String h = System.getenv(v);
            if (h != null && !h.isBlank() && Files.isDirectory(Path.of(h))) {
                return Path.of(h).toAbsolutePath().normalize();
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                for (String name : new String[] {"mvn", "mvn.cmd"}) {
                    Path mvn = Path.of(dir, name);
                    if (Files.isExecutable(mvn)) {
                        return homeOf(mvn);
                    }
                }
            }
        }
        return null;
    }

    /** The installation {@code bin/mvn} belongs to; Homebrew keeps it under {@code libexec/}. */
    static Path homeOf(Path mvn) {
        try {
            Path home = mvn.toRealPath().getParent().getParent();
            for (Path h : new Path[] {home, home.resolve("libexec")}) {
                if (Files.isDirectory(h.resolve("conf"))) {
                    return h;
                }
            }
        } catch (IOException | RuntimeException e) {
            // ponytail: not resolvable, treat as not found
        }
        return null;
    }

    /** Version from {@code lib/maven-core-X.jar}, or null. */
    public static String mavenVersion(Path mavenHome) {
        if (mavenHome == null) {
            return null;
        }
        try (var jars = Files.newDirectoryStream(mavenHome.resolve("lib"), "maven-core-*.jar")) {
            for (Path j : jars) {
                String n = j.getFileName().toString();
                return n.substring("maven-core-".length(), n.length() - ".jar".length());
            }
        } catch (IOException e) {
            // fall through
        }
        return null;
    }

    /** Maven 4 and later 3.x need no upgrade; an unparseable version is not flagged. */
    public static boolean olderThan310(String mavenVersion) {
        List<Integer> n = Versions.numbers(mavenVersion);
        return n.size() >= 2 && n.get(0) == 3 && n.get(1) < 10;
    }

    private static String text(Element parent, String tag) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && tag.equals(e.getTagName())) {
                return e.getTextContent().trim();
            }
        }
        return null;
    }

    /** True if any of the known extension's coordinates is declared. */
    public static boolean has(List<Declared> declared, Known known) {
        return declared.stream().anyMatch(d -> known.ids().contains(d.id()));
    }

    public static String snippet(Known known, String version) {
        String[] ga = known.id().split(":");
        return "<extension>\n    <groupId>" + ga[0] + "</groupId>\n    <artifactId>" + ga[1]
                + "</artifactId>\n    <version>" + version + "</version>\n</extension>";
    }
}
