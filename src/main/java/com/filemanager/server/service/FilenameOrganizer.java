package com.filemanager.server.service;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.filemanager.server.domain.FileOperationLog;
import com.filemanager.server.domain.FileOperationLogRepository;

@Component
public class FilenameOrganizer implements ApplicationRunner, DisposableBean {

    private static final Logger logger =
            LoggerFactory.getLogger(FilenameOrganizer.class);

    private final FileOperationLogRepository operationLogRepository;
    private final ClassificationProperties classificationProperties;

    public FilenameOrganizer(
            FileOperationLogRepository operationLogRepository,
            ClassificationProperties classificationProperties) {
        this.operationLogRepository = operationLogRepository;
        this.classificationProperties = classificationProperties;
    }

    @Value("${filemanager.root-directory}")
    private String configuredRootDirectory;

    @Value("${filemanager.browse-root-directory:C:/Users/COMPUTER/Desktop}")
    private String configuredBrowseRootDirectory;

    @Value("${filemanager.destinations.school}")
    private String configuredSchoolDirectory;

    @Value("${filemanager.destinations.work}")
    private String configuredWorkDirectory;

    @Value("${filemanager.destinations.travel}")
    private String configuredTravelDirectory;

    @Value("${filemanager.destinations.personal}")
    private String configuredPersonalDirectory;

    @Value("${filemanager.destinations.unclassified}")
    private String configuredUnclassifiedDirectory;

    @Value("${filemanager.pc-identifier:folderhelper-computer-01}")
    private String configuredPcIdentifier;

    private volatile Path rootDirectory;
    private volatile Map<String, Path> destinationDirectories;
    private volatile boolean running;
    private WatchService watchService;
    private Thread watchThread;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        initializeDirectories();

        // 기존 DB에서 PC 식별자가 비어 있는 기록을 이 PC의 기록으로 연결합니다.
        operationLogRepository.assignMissingPcIdentifier(
                configuredPcIdentifier
        );

        watchService = FileSystems.getDefault().newWatchService();

        rootDirectory.register(
                watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY
        );

        running = true;
        watchThread = new Thread(
                this::watchFolder,
                "folderhelper-folder-watcher"
        );
        watchThread.setDaemon(true);
        watchThread.start();

        // 서버 시작 전에 감시 폴더에 이미 있던 파일도 분류합니다.
        organizeRootFiles();

        logger.info("입력 폴더 감시 시작: {}", rootDirectory);
    }

    public Path getRootDirectory() throws IOException {
        initializeDirectories();
        return rootDirectory;
    }

    /** 모바일에서 탐색할 수 있는 PC 바탕화면의 최상위 경로입니다. */
    public Path getBrowseRootDirectory() throws IOException {
        Path browseRoot = Paths.get(configuredBrowseRootDirectory)
                .toAbsolutePath()
                .normalize();

        if (!Files.exists(browseRoot) || !Files.isDirectory(browseRoot)) {
            throw new IOException(
                    "파일 탐색 경로가 없거나 폴더가 아닙니다: " + browseRoot
            );
        }

        return browseRoot.toRealPath();
    }

    /**
     * 앱에서 분류명과 실제 폴더 경로를 사용할 수 있도록 제공합니다.
     */
    public Map<String, Path> getDestinationDirectories()
            throws IOException {
        initializeDirectories();
        return Collections.unmodifiableMap(destinationDirectories);
    }

    private synchronized void initializeDirectories() throws IOException {
        if (rootDirectory != null && destinationDirectories != null) {
            return;
        }

        Path input = Paths.get(configuredRootDirectory)
                .toAbsolutePath()
                .normalize();

        Files.createDirectories(input);
        rootDirectory = input.toRealPath();

        Map<String, String> configured = new LinkedHashMap<>();
        configured.put("학교", configuredSchoolDirectory);
        configured.put("업무", configuredWorkDirectory);
        configured.put("여행", configuredTravelDirectory);
        configured.put("개인", configuredPersonalDirectory);
        configured.put("미분류", configuredUnclassifiedDirectory);

        Map<String, Path> resolved = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : configured.entrySet()) {
            Path destination = Paths.get(entry.getValue())
                    .toAbsolutePath()
                    .normalize();

            // 결과 폴더가 입력 폴더 안에 있으면 감시 대상과 섞이지 않게 막습니다.
            if (destination.startsWith(rootDirectory)) {
                throw new IOException(
                        "분류 폴더는 감시 입력 폴더 바깥에 지정해야 합니다: "
                                + destination
                );
            }

            Files.createDirectories(destination);
            resolved.put(entry.getKey(), destination.toRealPath());
        }

        destinationDirectories = resolved;
    }

    /**
     * 감시 폴더 바로 아래에 있는 기존 파일을 정리합니다.
     */
    public void organizeRootFiles() throws IOException {
        Path root = getRootDirectory();
        List<Path> entries = new ArrayList<>();

        try (Stream<Path> stream = Files.list(root)) {
            stream.forEach(entries::add);
        }

        for (Path entry : entries) {
            if (waitUntilFileIsStable(entry)) {
                organizeFile(entry);
            }
        }
    }

    /** PC 바탕화면 안의 파일을 다른 바탕화면 폴더로 이동합니다. */
    public void moveFile(String sourceVirtualPath, String destinationVirtualPath)
            throws IOException {
        Path browseRoot = getBrowseRootDirectory();
        Path realSource = resolvePathUnderBrowseRoot(
                sourceVirtualPath,
                browseRoot,
                false
        );

        if (!Files.isRegularFile(realSource)) {
            throw new IllegalArgumentException("파일만 이동할 수 있습니다.");
        }

        Path realDestinationDirectory = resolvePathUnderBrowseRoot(
                destinationVirtualPath,
                browseRoot,
                true
        );

        if (!Files.isDirectory(realDestinationDirectory)) {
            throw new IllegalArgumentException("목적지로 폴더를 선택해 주세요.");
        }

        if (realSource.getParent().equals(realDestinationDirectory)) {
            throw new IllegalArgumentException(
                    "파일이 이미 선택한 폴더에 있습니다."
            );
        }

        String fileName = realSource.getFileName().toString();
        Path destination = createUniqueDestination(
                realDestinationDirectory,
                fileName
        );

        try {
            Files.move(realSource, destination);
        } catch (IOException exception) {
            saveOperationLog(
                    fileName,
                    "모바일 이동",
                    "FAILED",
                    realSource,
                    destination,
                    exception.getMessage()
            );
            throw exception;
        }

        Path watchRoot = getRootDirectory().toRealPath();
        boolean sentToFolderHelperTest =
                realDestinationDirectory.equals(watchRoot);

        saveOperationLog(
                fileName,
                "모바일 이동",
                "SUCCESS",
                realSource,
                destination,
                sentToFolderHelperTest
                        ? "모바일에서 FolderHelperTest로 보냈습니다. 자동 분류를 시작합니다."
                        : "모바일에서 바탕화면의 다른 폴더로 이동했습니다."
        );

        // 감시 폴더로 보내면 WatchService 이벤트를 기다리지 않고 바로 이름 분류합니다.
        if (sentToFolderHelperTest) {
            organizeFile(destination);
        }
    }

    private Path resolvePathUnderBrowseRoot(
            String virtualPath,
            Path browseRoot,
            boolean allowRoot) throws IOException {
        String normalizedPath = virtualPath == null
                ? ""
                : virtualPath.trim().replace('\\', '/');

        while (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }

        if (normalizedPath.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException(
                    "바탕화면 기준 상대 경로를 보내야 합니다."
            );
        }

        if (normalizedPath.isEmpty()) {
            if (allowRoot) {
                return browseRoot;
            }
            throw new IllegalArgumentException("파일 경로를 선택해 주세요.");
        }

        Path relativePath = Paths.get(normalizedPath).normalize();
        if (relativePath.isAbsolute() || relativePath.startsWith("..")) {
            throw new IllegalArgumentException(
                    "바탕화면 바깥의 경로는 사용할 수 없습니다."
            );
        }

        Path candidate = browseRoot.resolve(relativePath).normalize();
        if (!candidate.startsWith(browseRoot)) {
            throw new IllegalArgumentException(
                    "바탕화면 바깥의 경로는 사용할 수 없습니다."
            );
        }

        Path current = browseRoot;
        for (Path segment : relativePath) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException(
                        "바로가기 또는 심볼릭 링크는 이동 경로로 사용할 수 없습니다."
                );
            }
        }

        if (!Files.exists(candidate)) {
            throw new IllegalArgumentException("선택한 경로를 찾을 수 없습니다.");
        }

        Path realPath = candidate.toRealPath();
        if (!realPath.startsWith(browseRoot)) {
            throw new IllegalArgumentException(
                    "바탕화면 바깥의 경로는 사용할 수 없습니다."
            );
        }

        return realPath;
    }

    private void watchFolder() {
        while (running) {
            WatchKey key;

            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (running) {
                    logger.error("폴더 감시 중 오류가 발생했습니다.", e);
                }
                break;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }

                Object context = event.context();

                if (!(context instanceof Path)) {
                    continue;
                }

                Path source = rootDirectory.resolve((Path) context)
                        .toAbsolutePath()
                        .normalize();

                // 입력 폴더 바로 아래에 생성된 파일만 분류합니다.
                if (source.getParent() == null
                        || !source.getParent().equals(rootDirectory)) {
                    continue;
                }

                try {
                    if (waitUntilFileIsStable(source)) {
                        organizeFile(source);
                    }
                } catch (Exception e) {
                    logger.error("파일 처리에 실패했습니다: {}", source, e);
                }
            }

            if (!key.reset()) {
                logger.error("폴더 감시가 종료되었습니다: {}", rootDirectory);
                break;
            }
        }
    }

    private boolean waitUntilFileIsStable(Path file) {
        long previousSize = -1;
        int stableChecks = 0;

        for (int i = 0; i < 40 && running; i++) {
            try {
                if (!Files.exists(file)
                        || Files.isDirectory(file)
                        || Files.isSymbolicLink(file)) {
                    return false;
                }

                long currentSize = Files.size(file);

                if (currentSize == previousSize) {
                    stableChecks++;

                    if (stableChecks >= 2) {
                        return true;
                    }
                } else {
                    previousSize = currentSize;
                    stableChecks = 0;
                }

                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (IOException e) {
                return false;
            }
        }

        return false;
    }

    private synchronized void organizeFile(Path source) throws IOException {
        Path root = getRootDirectory();
        Path normalizedSource = source.toAbsolutePath().normalize();

        if (!normalizedSource.startsWith(root)
                || normalizedSource.getParent() == null
                || !normalizedSource.getParent().equals(root)
                || !Files.isRegularFile(normalizedSource)
                || Files.isSymbolicLink(normalizedSource)) {
            return;
        }

        String fileName = normalizedSource.getFileName().toString();
        String category = classifyByFileName(fileName);

        Path destinationDirectory =
                getDestinationDirectories().get(category);

        if (destinationDirectory == null) {
            throw new IOException(
                    "분류 폴더 설정을 찾을 수 없습니다: " + category
            );
        }

        Path realDestinationDirectory = destinationDirectory.toRealPath();

        if (!realDestinationDirectory.equals(destinationDirectory)) {
            throw new IOException(
                    "분류 폴더 경로가 변경되었습니다: "
                            + destinationDirectory
            );
        }

        Path destination =
                createUniqueDestination(destinationDirectory, fileName);

        try {
            Files.move(normalizedSource, destination);

            saveOperationLog(
                    fileName,
                    category,
                    "SUCCESS",
                    normalizedSource,
                    destination,
                    "파일을 분류 폴더로 이동했습니다."
            );

            logger.info(
                    "파일 분류 완료: {} -> {}",
                    normalizedSource,
                    destination
            );
        } catch (IOException exception) {
            saveOperationLog(
                    fileName,
                    category,
                    "FAILED",
                    normalizedSource,
                    destination,
                    exception.getMessage()
            );

            throw exception;
        }
    }

    private void saveOperationLog(
            String fileName,
            String category,
            String status,
            Path source,
            Path destination,
            String detail) {

        try {
            operationLogRepository.save(
                    new FileOperationLog(
                            configuredPcIdentifier,
                            fileName,
                            category,
                            status,
                            source.toString(),
                            destination == null
                                    ? null
                                    : destination.toString(),
                            detail == null
                                    ? "처리 결과 상세 정보가 없습니다."
                                    : detail
                    )
            );
        } catch (RuntimeException exception) {
            logger.error(
                    "파일 처리 기록을 저장하지 못했습니다: {}",
                    fileName,
                    exception
            );
        }
    }

    private String classifyByFileName(String fileName) {
        String name = fileName.toLowerCase();

        // 여러 분류의 키워드와 일치하면 학교, 업무, 여행, 개인 순서로 적용합니다.
        if (containsAny(
                name,
                classificationProperties.getKeywordsFor("school")
        )) {
            return "학교";
        }

        if (containsAny(
                name,
                classificationProperties.getKeywordsFor("work")
        )) {
            return "업무";
        }

        if (containsAny(
                name,
                classificationProperties.getKeywordsFor("travel")
        )) {
            return "여행";
        }

        if (containsAny(
                name,
                classificationProperties.getKeywordsFor("personal")
        )) {
            return "개인";
        }

        return "미분류";
    }

    private boolean containsAny(String fileName, List<String> keywords) {
        for (String keyword : keywords) {
            if (keyword != null
                    && !keyword.trim().isEmpty()
                    && fileName.contains(keyword.trim().toLowerCase())) {
                return true;
            }
        }

        return false;
    }

    private Path createUniqueDestination(
            Path directory,
            String fileName) {

        Path destination = directory.resolve(fileName);

        if (!Files.exists(destination)) {
            return destination;
        }

        int dotIndex = fileName.lastIndexOf('.');
        String baseName = dotIndex > 0
                ? fileName.substring(0, dotIndex)
                : fileName;
        String extension = dotIndex > 0
                ? fileName.substring(dotIndex)
                : "";

        int number = 1;

        while (true) {
            destination = directory.resolve(
                    baseName + " (" + number + ")" + extension
            );

            if (!Files.exists(destination)) {
                return destination;
            }

            number++;
        }
    }

    @Override
    public void destroy() throws Exception {
        running = false;

        if (watchService != null) {
            watchService.close();
        }

        if (watchThread != null) {
            watchThread.interrupt();
        }
    }
}
