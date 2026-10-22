/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.huginn.project;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Profile;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

/**
 * Finds the reactor root for a path and lists its modules from the raw POMs, the way Maven
 * discovers them, without any repository access. Coordinates found here are provisional (only
 * simple property interpolation, enough for CI-friendly versions); they seed the workspace
 * reader before effective models are built.
 */
public final class ReactorScanner {

    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");

    /** A module as read from its raw POM. */
    public record RawModule(Path dir, Path pom, Model raw, String groupId, String artifactId, String version) {
        public String key() {
            return groupId + ":" + artifactId;
        }
    }

    private ReactorScanner() {}

    /**
     * The reactor root for {@code start}: the nearest directory with {@code .mvn/} (Maven's own
     * rule), otherwise the outermost aggregator POM that lists {@code start} as a module.
     */
    public static Path findRoot(Path start) throws IOException {
        Path dir = start.toAbsolutePath().normalize();
        if (Files.isRegularFile(dir)) {
            dir = dir.getParent();
        }
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve(".mvn"))) {
                return p;
            }
        }
        Path cur = dir;
        while (cur != null && !Files.isRegularFile(cur.resolve("pom.xml"))) {
            cur = cur.getParent();
        }
        if (cur == null) {
            throw new IOException("no pom.xml found at or above " + dir);
        }
        while (cur.getParent() != null && Files.isRegularFile(cur.getParent().resolve("pom.xml"))) {
            Path parent = cur.getParent();
            Model raw = readRaw(parent.resolve("pom.xml"));
            boolean listed = false;
            for (String m : moduleEntries(raw, MavenConfig.EMPTY, parent)) {
                if (moduleDir(parent, m).equals(cur)) {
                    listed = true;
                    break;
                }
            }
            if (!listed) {
                break;
            }
            cur = parent;
        }
        return cur;
    }

    /** All modules of the reactor rooted at {@code root}, the root first, in discovery order. */
    public static List<RawModule> scan(Path root, MavenConfig config) throws IOException {
        Map<Path, Model> raws = new LinkedHashMap<>();
        collect(root.toAbsolutePath().normalize(), config, raws, new HashSet<>());
        List<RawModule> out = new ArrayList<>();
        for (Map.Entry<Path, Model> e : raws.entrySet()) {
            Path dir = e.getKey();
            Model raw = e.getValue();
            Map<String, String> props = properties(dir, raw, raws, config);
            String groupId = raw.getGroupId() != null
                    ? raw.getGroupId()
                    : raw.getParent() != null ? raw.getParent().getGroupId() : null;
            String version = raw.getVersion() != null
                    ? raw.getVersion()
                    : raw.getParent() != null ? raw.getParent().getVersion() : null;
            out.add(new RawModule(
                    dir,
                    dir.resolve("pom.xml"),
                    raw,
                    interpolate(groupId, props),
                    interpolate(raw.getArtifactId(), props),
                    interpolate(version, props)));
        }
        return out;
    }

    private static void collect(Path dir, MavenConfig config, Map<Path, Model> out, Set<Path> seen) throws IOException {
        if (!seen.add(dir)) {
            return;
        }
        Path pom = dir.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return;
        }
        Model raw = readRaw(pom);
        out.put(dir, raw);
        for (String m : moduleEntries(raw, config, dir)) {
            collect(moduleDir(dir, m), config, out, seen);
        }
    }

    /** Main modules plus those of the profiles that are active for this POM, see {@link ProfileActivation}. */
    static List<String> moduleEntries(Model raw, MavenConfig config, Path pomDir) {
        List<String> modules = new ArrayList<>(raw.getModules());
        for (Profile p : ProfileActivation.active(raw.getProfiles(), config, pomDir)) {
            for (String m : p.getModules()) {
                if (!modules.contains(m)) {
                    modules.add(m);
                }
            }
        }
        return modules;
    }

    /** A module entry is a directory, or a POM file inside it. */
    static Path moduleDir(Path parent, String entry) {
        Path p = parent.resolve(entry.trim()).normalize();
        return p.toString().endsWith(".xml") ? p.getParent() : p;
    }

    public static Model readRaw(Path pom) throws IOException {
        try (Reader r = Files.newBufferedReader(pom)) {
            return new MavenXpp3Reader().read(r, false);
        } catch (XmlPullParserException e) {
            throw new IOException("cannot parse " + pom + ": " + e.getMessage(), e);
        }
    }

    /**
     * Properties visible to a module's coordinates: its own, then those of in-reactor parents,
     * then maven.config user properties on top (they win, as on the command line).
     */
    private static Map<String, String> properties(Path dir, Model raw, Map<Path, Model> raws, MavenConfig config) {
        Map<String, String> props = new HashMap<>();
        List<Model> chain = new ArrayList<>();
        Path cur = dir;
        Model m = raw;
        Set<Path> visited = new HashSet<>();
        while (m != null && visited.add(cur)) {
            chain.add(m);
            Parent parent = m.getParent();
            if (parent == null) {
                break;
            }
            String rel = parent.getRelativePath() == null ? "../pom.xml" : parent.getRelativePath();
            if (rel.isBlank()) {
                break;
            }
            Path parentDir = moduleDir(cur, rel.endsWith(".xml") ? rel : rel + "/pom.xml");
            cur = parentDir;
            m = raws.get(parentDir);
        }
        for (int i = chain.size() - 1; i >= 0; i--) {
            Properties p = chain.get(i).getProperties();
            for (String k : p.stringPropertyNames()) {
                props.put(k, p.getProperty(k));
            }
        }
        props.putAll(config.userProperties());
        return props;
    }

    static String interpolate(String value, Map<String, String> props) {
        if (value == null) {
            return null;
        }
        String cur = value;
        for (int round = 0; round < 5 && cur.contains("${"); round++) {
            Matcher m = PROPERTY.matcher(cur);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String v = props.get(m.group(1));
                m.appendReplacement(sb, Matcher.quoteReplacement(v != null ? v : m.group()));
            }
            m.appendTail(sb);
            if (sb.toString().equals(cur)) {
                break;
            }
            cur = sb.toString();
        }
        return cur;
    }
}
