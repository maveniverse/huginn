/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.huginn.support;

import eu.maveniverse.maven.huginn.render.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Picks the upgrade candidates an agent actually acts on: the newest version overall, within
 * the same major, and within the same minor. Pre-releases are skipped unless asked for, or the
 * current version is itself a pre-release.
 */
public final class Versions {

    /** A qualifier token marking a pre-release, possibly followed by a number: RC1, M3, beta2. */
    private static final Pattern PREVIEW_TOKEN =
            Pattern.compile("(alpha|beta|rc|cr|m|milestone|ea|preview|pre|dev|snapshot)\\d*", Pattern.CASE_INSENSITIVE);

    private Versions() {}

    public static boolean isPreview(String version) {
        for (String token : version.split("[.\\-_+]")) {
            if (PREVIEW_TOKEN.matcher(token).matches()) {
                return true;
            }
        }
        return false;
    }

    /** Leading numeric segments: 3.12.0-RC1 gives [3, 12, 0]. */
    static List<Integer> numbers(String version) {
        List<Integer> out = new ArrayList<>();
        for (String part : version.split("[.\\-]")) {
            if (!part.matches("\\d+")) {
                break;
            }
            try {
                out.add(Integer.parseInt(part));
            } catch (NumberFormatException e) {
                break;
            }
        }
        return out;
    }

    private static boolean sameLeading(List<Integer> a, List<Integer> b, int count) {
        for (int i = 0; i < count; i++) {
            int x = i < a.size() ? a.get(i) : 0;
            int y = i < b.size() ? b.get(i) : 0;
            if (x != y) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param newer versions newer than {@code current}, ascending (as Resolver returns them)
     * @return {@code latest}, {@code latest_minor} (same major), {@code latest_patch} (same
     *     major.minor); keys are absent when there is nothing newer in that band
     */
    public static Map<String, Object> candidates(String current, List<String> newer, boolean includePreviews) {
        boolean allowPreview = includePreviews || isPreview(current);
        List<Integer> cur = numbers(current);
        String latest = null;
        String minor = null;
        String patch = null;
        for (String v : newer) {
            if (!allowPreview && isPreview(v)) {
                continue;
            }
            List<Integer> n = numbers(v);
            latest = v;
            if (sameLeading(cur, n, 1)) {
                minor = v;
            }
            if (sameLeading(cur, n, 2)) {
                patch = v;
            }
        }
        Json.Obj o = Json.obj();
        o.with("latest", latest);
        if (minor != null && !minor.equals(latest)) {
            o.with("latest_minor", minor);
        }
        if (patch != null && !patch.equals(minor)) {
            o.with("latest_patch", patch);
        }
        return o;
    }

    /** Stable versions only (or all, when asked), newest last, at most {@code limit}. */
    public static List<String> recent(List<String> versions, boolean includePreviews, int limit) {
        List<String> kept = new ArrayList<>();
        for (String v : versions) {
            if (includePreviews || !isPreview(v)) {
                kept.add(v);
            }
        }
        return kept.size() <= limit ? kept : kept.subList(kept.size() - limit, kept.size());
    }
}
