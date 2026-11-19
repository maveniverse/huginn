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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Reads a plugin's {@code META-INF/maven/plugin.xml}: its goals and, per goal, the parameters a user
 * can set, with the user property ({@code -Dx}) and default of each.
 */
public final class PluginDescriptors {

    public record Descriptor(String goalPrefix, List<Mojo> mojos) {}

    public record Mojo(String goal, String phase, String description, List<Parameter> parameters) {}

    public record Parameter(
            String name,
            String type,
            boolean required,
            String property,
            String defaultValue,
            String description,
            boolean deprecated) {}

    private PluginDescriptors() {}

    public static Descriptor read(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("META-INF/maven/plugin.xml");
            if (entry == null) {
                throw new IOException(jar.getFileName() + " has no META-INF/maven/plugin.xml; is it a Maven plugin?");
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return parse(in);
            }
        }
    }

    static Descriptor parse(InputStream in) throws IOException {
        Element root;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            root = f.newDocumentBuilder().parse(in).getDocumentElement();
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("cannot parse plugin.xml: " + e.getMessage(), e);
        }
        List<Mojo> mojos = new ArrayList<>();
        for (Element mojo : children(child(root, "mojos"), "mojo")) {
            // <configuration><name implementation="..." default-value="d">${property}</name></configuration>
            Map<String, Element> config = new HashMap<>();
            for (Element c : children(child(mojo, "configuration"), null)) {
                config.put(c.getTagName(), c);
            }
            List<Parameter> parameters = new ArrayList<>();
            for (Element p : children(child(mojo, "parameters"), "parameter")) {
                if ("false".equals(text(p, "editable"))) {
                    continue; // set by Maven itself, e.g. ${project}
                }
                String name = text(p, "name");
                Element c = config.get(name);
                String expression = c != null ? c.getTextContent().trim() : "";
                String property = expression.startsWith("${") && expression.endsWith("}")
                        ? expression.substring(2, expression.length() - 1)
                        : null;
                String defaultValue =
                        c != null && c.hasAttribute("default-value") ? c.getAttribute("default-value") : null;
                String type = text(p, "type");
                parameters.add(new Parameter(
                        name,
                        type == null ? null : type.substring(type.lastIndexOf('.') + 1),
                        "true".equals(text(p, "required")),
                        property,
                        defaultValue,
                        summary(text(p, "description")),
                        child(p, "deprecated") != null));
            }
            mojos.add(
                    new Mojo(text(mojo, "goal"), text(mojo, "phase"), summary(text(mojo, "description")), parameters));
        }
        return new Descriptor(text(root, "goalPrefix"), mojos);
    }

    /** First sentence of a Javadoc-style description, without HTML, at most 200 characters. */
    static String summary(String description) {
        if (description == null) {
            return null;
        }
        String s = description
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\{@\\w+\\s+([^}]*)}", "$1")
                .replaceAll("\\s+", " ")
                .replaceAll(" ([.,;:])", "$1")
                .trim();
        int end = s.indexOf(". ");
        if (end > 0) {
            s = s.substring(0, end + 1);
        }
        return s.length() <= 200 ? s : s.substring(0, 197) + "...";
    }

    private static Element child(Element parent, String tag) {
        if (parent == null) {
            return null;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && tag.equals(e.getTagName())) {
                return e;
            }
        }
        return null;
    }

    /** Child elements with the tag, or all child elements when the tag is null. */
    private static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        if (parent != null) {
            for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element e && (tag == null || tag.equals(e.getTagName()))) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    private static String text(Element parent, String tag) {
        Element e = child(parent, tag);
        return e == null ? null : e.getTextContent().trim();
    }
}
