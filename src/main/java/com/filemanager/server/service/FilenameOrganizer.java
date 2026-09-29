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

@Component
public class FilenameOrganizer implements ApplicationRunner, DisposableBean {

    private static final Logger logger =
            LoggerFactory.getLogger(FilenameOrganizer.class);

    @Value("${filemanager.root-directory}")
    private String configuredRootDirectory;

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

    private volatile Path rootDirectory;
    private volatile Map<String, Path> destinationDirectories;
    private volatile boolean running;
    private WatchService watchService;
    private Thread watchThread;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        initializeDirectories();

        watchService = FileSystems.getDefault().newWatchService();
        rootDirectory.register(
                watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY
        );

        running = true;
        watchThread = new Thread(this::watchFolder, "folderhelper-folder-watcher");
        watchThread.setDaemon(true);
        watchThread.start();

        // 서버 시작 전에 감시 폴더에 들어 있던 파일도 분류합니다.
        organizeRootFiles();
        logger.info("입력 폴더 감시 시작: {}", rootDirectory);
    }

    public Path getRootDirectory() throws IOException {
        initializeDirectories();
        return rootDirectory;
    }

    /** 앱이 분류된 파일을 읽을 수 있도록 분류명과 실제 폴더 경로를 제공합니다. */
    public Map<String, Path> getDestinationDirectories() throws IOException {
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

    /** 감시 폴더 바로 아래에 있는 기존 파일을 정리합니다. */
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
        Path destinationDirectory = getDestinationDirectories().get(category);

        if (destinationDirectory == null) {
            throw new IOException("분류 폴더 설정을 찾을 수 없습니다: " + category);
        }

        Path realDestinationDirectory = destinationDirectory.toRealPath();
        if (!realDestinationDirectory.equals(destinationDirectory)) {
            throw new IOException("분류 폴더 경로가 변경되었습니다: " + destinationDirectory);
        }

        Path destination = createUniqueDestination(destinationDirectory, fileName);
        Files.move(normalizedSource, destination);

        logger.info("파일 분류 완료: {} -> {}", normalizedSource, destination);
    }

    private String classifyByFileName(String fileName) {
        String name = fileName.toLowerCase();

        if (containsAny(name, "과제", "강의", "수업", "학교", "시험", "졸업")) {
            return "학교";
        }
        if (containsAny(name, "업무", "회의", "보고서", "계약", "영수증", "회사")) {
            return "업무";
        }
        if (containsAny(name, "여행", "항공", "호텔", "숙소", "여행지")) {
            return "여행";
        }
        if (containsAny(name, "가족", "개인", "취미")) {
            return "개인";
        }

        return "미분류";
    }

    private boolean containsAny(String fileName, String... keywords) {
        for (String keyword : keywords) {
            if (fileName.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private Path createUniqueDestination(Path directory, String fileName) {
        Path destination = directory.resolve(fileName);
        if (!Files.exists(destination)) {
            return destination;
        }

        int dotIndex = fileName.lastIndexOf('.');
        String baseName = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        String extension = dotIndex > 0 ? fileName.substring(dotIndex) : "";
        int number = 1;

        while (true) {
            destination = directory.resolve(baseName + " (" + number + ")" + extension);
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
