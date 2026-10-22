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
