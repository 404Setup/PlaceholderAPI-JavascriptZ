package com.extendedclip.papi.expansion.javascript.evaluator.util;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class DependUtil {
    private static final List<RemoteRepository> REPOSITORIES = new ArrayList<>();

    static {
        REPOSITORIES.add(new RemoteRepository.Builder("central", "default", "https://repo.maven.apache.org/maven2/").build());
        REPOSITORIES.add(new RemoteRepository.Builder("sonatype", "default", "https://s01.oss.sonatype.org/content/repositories/releases/").build());
    }

    private final Path libsFolder;
    private final RepositorySystem repositorySystem;
    private final RepositorySystemSession session;
    public DependUtil() {
        try {
            this.libsFolder = InjectionUtil.getLibsFolder().toPath();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        this.repositorySystem = createRepositorySystem();
        this.session = createRepositorySystemSession(repositorySystem, libsFolder.toFile());
    }

    public static void setMirror(String url) {
        if (url == null) {
            return;
        }
        try {
            new URI(url);
        } catch (URISyntaxException e) {
            throw new RuntimeException("Invalid mirror url: " + url, e);
        }
        REPOSITORIES.set(0, new RemoteRepository.Builder("central", "default", url).build());
    }

    private static RepositorySystem createRepositorySystem() {
        DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
        locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
        locator.addService(TransporterFactory.class, FileTransporterFactory.class);
        locator.addService(TransporterFactory.class, HttpTransporterFactory.class);
        return locator.getService(RepositorySystem.class);
    }

    private static RepositorySystemSession createRepositorySystemSession(RepositorySystem system, File localRepo) {
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        LocalRepository localRepository = new LocalRepository(localRepo);
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepository));
        return session;
    }

    public String downloadDependency(String coordinates) throws Exception {
        if (!Files.exists(libsFolder)) {
            Files.createDirectories(libsFolder);
        }

        Artifact artifact = new DefaultArtifact(coordinates);
        String expectedFileName = String.format("%s-%s.jar", artifact.getArtifactId(), artifact.getVersion());
        Path jarPath = libsFolder.resolve(expectedFileName);

        if (Files.exists(jarPath)) {
            return expectedFileName;
        }

        CollectRequest collectRequest = new CollectRequest();
        collectRequest.setRoot(new Dependency(artifact, ""));
        collectRequest.setRepositories(REPOSITORIES);

        DependencyRequest dependencyRequest = new DependencyRequest(collectRequest, null);
        DependencyResult dependencyResult = repositorySystem.resolveDependencies(session, dependencyRequest);

        File resolvedFile = dependencyResult.getRoot().getArtifact().getFile();
        Files.copy(resolvedFile.toPath(), jarPath);

        return expectedFileName;
    }

    public List<String> downloadDependencies(String coordinates) throws Exception {
        if (!Files.exists(libsFolder)) {
            Files.createDirectories(libsFolder);
        }

        Artifact artifact = new DefaultArtifact(coordinates);
        List<String> downloadedFiles = new ArrayList<>();

        CollectRequest collectRequest = new CollectRequest();
        collectRequest.setRoot(new Dependency(artifact, ""));
        collectRequest.setRepositories(REPOSITORIES);

        DependencyRequest dependencyRequest = new DependencyRequest(collectRequest, null);
        DependencyResult dependencyResult = repositorySystem.resolveDependencies(session, dependencyRequest);

        for (ArtifactResult artifactResult : dependencyResult.getArtifactResults()) {
            Artifact resolvedArtifact = artifactResult.getArtifact();
            String expectedFileName = String.format("%s-%s.jar",
                    resolvedArtifact.getArtifactId(),
                    resolvedArtifact.getVersion());
            Path jarPath = libsFolder.resolve(expectedFileName);

            if (!Files.exists(jarPath)) {
                Files.copy(resolvedArtifact.getFile().toPath(), jarPath);
            }

            downloadedFiles.add(expectedFileName);
        }

        return downloadedFiles;
    }

    public List<String> downloadDependencies(List<String> coordinates) {
        List<String> downloadedDependencies = new ArrayList<>();
        for (String coord : coordinates) {
            try {
                downloadedDependencies.addAll(downloadDependencies(coord));
            } catch (Exception e) {
                throw new RuntimeException("Failed to download dependency: " + coord, e);
            }
        }
        return downloadedDependencies;
    }

}
