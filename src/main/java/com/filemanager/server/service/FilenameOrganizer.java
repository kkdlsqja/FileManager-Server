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
import java.util.List;
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

    private volatile Path rootDirectory;
    private volatile boolean running;
    private WatchService watchService;
    private Thread watchThread;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path configuredPath =
                Paths.get(configuredRootDirectory).toAbsolutePath().normalize();

        Files.createDirectories(configuredPath);
        rootDirectory = configuredPath.toRealPath();

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

        // 서버 시작 전에 폴더에 이미 들어 있던 파일도 분류합니다.
        organizeRootFiles();

        logger.info("폴더 감시 시작: {}", rootDirectory);
    }

    public Path getRootDirectory() throws IOException {
        if (rootDirectory == null) {
            Path configuredPath =
                    Paths.get(configuredRootDirectory).toAbsolutePath().normalize();

            Files.createDirectories(configuredPath);
            rootDirectory = configuredPath.toRealPath();
        }

        return rootDirectory;
    }

    /**
     * 지정 폴더 바로 아래의 파일들을 분류합니다.
     */
    public void organizeRootFiles() throws IOException {
        Path root = getRootDirectory();
        List<Path> files = new ArrayList<>();

        try (Stream<Path> entries = Files.list(root)) {
            entries.forEach(files::add);
        }

        for (Path file : files) {
            if (waitUntilFileIsStable(file)) {
                organizeFile(file);
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

                Path relativePath = (Path) context;
                Path source = rootDirectory.resolve(relativePath)
                        .toAbsolutePath()
                        .normalize();

                // 감시 대상 폴더 바로 아래에 생긴 파일만 처리합니다.
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

    /**
     * 파일 복사가 끝날 때까지 크기가 안정되는지 확인합니다.
     */
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

    /**
     * 파일명 키워드에 따라 지정 폴더 아래의 분류 폴더로 이동합니다.
     */
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

        Path categoryDirectory = root.resolve(category).normalize();

        if (!categoryDirectory.startsWith(root)) {
            logger.warn("지정 폴더 밖으로 이동하려는 요청을 막았습니다.");
            return;
        }

        Files.createDirectories(categoryDirectory);

        Path destination = createUniqueDestination(categoryDirectory, fileName);
        Files.move(normalizedSource, destination);

        logger.info("파일 분류 완료: {} -> {}", fileName, category);
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
        String baseName;
        String extension;

        if (dotIndex > 0) {
            baseName = fileName.substring(0, dotIndex);
            extension = fileName.substring(dotIndex);
        } else {
            baseName = fileName;
            extension = "";
        }

        int number = 1;

        while (true) {
            String newName = baseName + " (" + number + ")" + extension;
            destination = directory.resolve(newName);

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