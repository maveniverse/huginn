/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.huginn.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.model.Activation;
import org.apache.maven.model.ActivationFile;
import org.apache.maven.model.ActivationOS;
import org.apache.maven.model.ActivationProperty;
import org.apache.maven.model.Profile;
import org.eclipse.aether.util.version.GenericVersionScheme;
import org.eclipse.aether.version.InvalidVersionSpecificationException;
import org.eclipse.aether.version.Version;
import org.eclipse.aether.version.VersionConstraint;

/**
 * Which profiles of one POM are active, by Maven's rules: named in {@code maven.config} (or
 * deactivated there), else activated by {@code jdk}, {@code os}, {@code property} or
 * {@code file} conditions (all given conditions must hold), else {@code activeByDefault}, which
 * counts only when no other profile of the POM is active. Settings profiles are not consulted.
 */
final class ProfileActivation {

    private static final Pattern JDK_VERSION = Pattern.compile("\\d+(\\.\\d+)*");

    /** Maven runs on {@code JAVA_HOME} when set, which need not be the JDK this server runs on. */
    private static final String BUILD_JAVA_VERSION = javaVersion(System.getenv("JAVA_HOME"));

    private ProfileActivation() {}

    /** {@code JAVA_VERSION} from {@code <javaHome>/release}, else this JVM's version. */
    static String javaVersion(String javaHome) {
        if (javaHome != null && !javaHome.isBlank()) {
            try {
                for (String line : Files.readAllLines(Path.of(javaHome, "release"))) {
                    if (line.startsWith("JAVA_VERSION=")) {
                        return line.substring("JAVA_VERSION=".length()).replace("\"", "");
                    }
                }
            } catch (IOException | RuntimeException e) {
                // no release file: fall back to this JVM
            }
        }
        return System.getProperty("java.version", "");
    }

    static List<Profile> active(List<Profile> profiles, MavenConfig config, Path pomDir) {
        List<Profile> active = new ArrayList<>();
        List<Profile> byDefault = new ArrayList<>();
        for (Profile p : profiles) {
            if (config.inactiveProfiles().contains(p.getId())) {
                continue;
            }
            if (config.activeProfiles().contains(p.getId()) || conditionsHold(p.getActivation(), config, pomDir)) {
                active.add(p);
            } else if (p.getActivation() != null && p.getActivation().isActiveByDefault()) {
                byDefault.add(p);
            }
        }
        return active.isEmpty() ? byDefault : active;
    }

    /** True when the activation names at least one condition, and every named condition holds. */
    static boolean conditionsHold(Activation a, MavenConfig config, Path pomDir) {
        if (a == null) {
            return false;
        }
        boolean any = false;
        if (a.getJdk() != null) {
            if (!jdk(a.getJdk().trim(), BUILD_JAVA_VERSION)) {
                return false;
            }
            any = true;
        }
        if (a.getOs() != null && !isEmpty(a.getOs())) {
            if (!os(a.getOs())) {
                return false;
            }
            any = true;
        }
        if (a.getProperty() != null) {
            if (!property(a.getProperty(), config.userProperties())) {
                return false;
            }
            any = true;
        }
        if (a.getFile() != null) {
            if (!file(a.getFile(), pomDir)) {
                return false;
            }
            any = true;
        }
        return any;
    }

    static boolean jdk(String spec, String javaVersion) {
        if (spec.isEmpty()) {
            return false;
        }
        if (spec.startsWith("!")) {
            return !jdk(spec.substring(1), javaVersion);
        }
        if (spec.startsWith("[") || spec.startsWith("(")) {
            Matcher m = JDK_VERSION.matcher(javaVersion);
            if (!m.find()) {
                return false;
            }
            try {
                GenericVersionScheme scheme = new GenericVersionScheme();
                VersionConstraint range = scheme.parseVersionConstraint(spec);
                Version actual = scheme.parseVersion(m.group());
                return range.containsVersion(actual);
            } catch (InvalidVersionSpecificationException e) {
                return false;
            }
        }
        return javaVersion.startsWith(spec);
    }

    private static boolean isEmpty(ActivationOS os) {
        return os.getName() == null && os.getFamily() == null && os.getArch() == null && os.getVersion() == null;
    }

    private static boolean os(ActivationOS os) {
        return os(
                os,
                System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""),
                System.getProperty("os.version", ""));
    }

    static boolean os(ActivationOS os, String name, String arch, String version) {
        name = name.toLowerCase(Locale.ROOT);
        arch = arch.toLowerCase(Locale.ROOT);
        version = version.toLowerCase(Locale.ROOT);
        return (os.getFamily() == null || family(os.getFamily().toLowerCase(Locale.ROOT), name))
                && (os.getName() == null || eq(os.getName().toLowerCase(Locale.ROOT), name))
                && (os.getArch() == null || eq(os.getArch().toLowerCase(Locale.ROOT), arch))
                && (os.getVersion() == null || eq(os.getVersion().toLowerCase(Locale.ROOT), version));
    }

    /** {@code !value} negates; families are the common Maven ones, derived from os.name. */
    private static boolean family(String wanted, String osName) {
        boolean negate = wanted.startsWith("!");
        String family = negate ? wanted.substring(1) : wanted;
        boolean windows = osName.startsWith("windows");
        boolean mac = osName.contains("mac");
        boolean matches =
                switch (family) {
                    case "windows" -> windows;
                    case "mac" -> mac;
                    case "unix" -> !windows;
                    default -> osName.startsWith(family);
                };
        return matches != negate;
    }

    private static boolean eq(String wanted, String actual) {
        return wanted.startsWith("!") ? !actual.equals(wanted.substring(1)) : actual.equals(wanted);
    }

    static boolean property(ActivationProperty p, Map<String, String> userProperties) {
        String name = p.getName();
        if (name == null || name.isBlank()) {
            return false;
        }
        boolean negate = name.startsWith("!");
        String key = negate ? name.substring(1) : name;
        String actual = userProperties.get(key);
        if (actual == null) {
            actual = System.getProperty(key);
        }
        if (actual == null && key.startsWith("env.")) {
            actual = System.getenv(key.substring("env.".length()));
        }
        String wanted = p.getValue();
        if (wanted == null || wanted.isEmpty()) {
            return negate == (actual == null);
        }
        if (wanted.startsWith("!")) {
            return actual != null && !actual.equals(wanted.substring(1)) && !negate;
        }
        return !negate && wanted.equals(actual);
    }

    static boolean file(ActivationFile f, Path pomDir) {
        boolean exists = f.getExists() != null && !f.getExists().isBlank();
        String spec = exists ? f.getExists() : f.getMissing();
        if (spec == null || spec.isBlank()) {
            return false;
        }
        spec = spec.replace("${basedir}", pomDir.toString()).replace("${user.dir}", System.getProperty("user.dir"));
        Path path = pomDir.resolve(spec.trim());
        return exists == Files.exists(path);
    }
}
