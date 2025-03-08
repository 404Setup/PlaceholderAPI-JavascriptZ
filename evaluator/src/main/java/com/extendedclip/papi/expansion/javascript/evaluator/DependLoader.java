package com.extendedclip.papi.expansion.javascript.evaluator;

import com.extendedclip.papi.expansion.javascript.evaluator.util.DependUtil;
import com.extendedclip.papi.expansion.javascript.evaluator.util.InjectionUtil;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DependLoader {
    private static final Logger LOGGER = Logger.getLogger(DependLoader.class.getName());

    private DependLoader() {
    }

    public static void loadNashorn() {
        downloadAndInject(DependencyConstants.NASHORN_DEPENDENCIES);
    }

    public static void loadQuickJs() {
        downloadAndInject(List.of("io.webfolder:quickjs:" + DependencyConstants.QUICKJS_VERSION));
    }

    public static void loadV8(boolean node) {
        try {
            List<String> coordinates = new java.util.ArrayList<>();
            coordinates.add("com.caoccao.javet:javet:" + DependencyConstants.JAVET_VERSION);

            String platformDependency = determineV8PlatformDependency(node);
            if (platformDependency != null) {
                coordinates.add(platformDependency);
            }

            downloadAndInject(coordinates);
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Failed to load V8 dependencies", e);
        }
    }

    private static String determineV8PlatformDependency(boolean node) {
        OsInfo osInfo = new OsInfo();

        if (osInfo.isArm64() && osInfo.isWindows()) {
            throw new RuntimeException("V8 does not support aarch64 on Windows");
        }

        String baseArtifact = node ? "javet-node-" : "javet-v8-";
        String version = DependencyConstants.JAVET_VERSION;

        if (osInfo.isAmd64()) {
            if (osInfo.isMac()) return "com.caoccao.javet:" + baseArtifact + "macos-x86_64:" + version;
            if (osInfo.isLinux()) return "com.caoccao.javet:" + baseArtifact + "linux-x86_64:" + version;
            if (osInfo.isWindows()) return "com.caoccao.javet:" + baseArtifact + "windows-x86_64:" + version;
        }

        if (osInfo.isArm64()) {
            if (osInfo.isMac()) return "com.caoccao.javet:" + baseArtifact + "macos-arm64:" + version;
            if (osInfo.isLinux()) return "com.caoccao.javet:" + baseArtifact + "linux-arm64:" + version;
        }

        return null;
    }

    private static void downloadAndInject(List<String> dependencies) {
        try {
            DependUtil downloader = new DependUtil();
            List<String> jarFileNames = downloader.downloadDependencies(dependencies);
            LOGGER.info(() -> "Downloaded files: " + jarFileNames);
            InjectionUtil.inject(jarFileNames);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to download and inject dependencies", e);
        }
    }

    public static class DependencyConstants {
        public static final String NASHORN_VERSION = "15.6";
        public static final String ASM_VERSION = "9.7.1";
        public static final String QUICKJS_VERSION = "1.1.0";
        public static final String JAVET_VERSION = "4.1.1";

        public static final List<String> NASHORN_DEPENDENCIES = List.of(
                "org.openjdk.nashorn:nashorn-core:" + NASHORN_VERSION,
                "org.ow2.asm:asm:" + ASM_VERSION,
                "org.ow2.asm:asm-commons:" + ASM_VERSION,
                "org.ow2.asm:asm-util:" + ASM_VERSION
        );
    }
}

class OsInfo {
    private final String osName = System.getProperty("os.name", "").toLowerCase();
    private final String osArch = System.getProperty("os.arch", "");

    boolean isAmd64() {
        return osArch.equals("amd64") || osArch.equals("x86_64");
    }

    boolean isArm64() {
        return osArch.equals("aarch64") || osArch.equals("arm64");
    }

    boolean isLinux() {
        return osName.contains("linux");
    }

    boolean isWindows() {
        return osName.contains("windows");
    }

    boolean isMac() {
        return osName.contains("mac");
    }
}