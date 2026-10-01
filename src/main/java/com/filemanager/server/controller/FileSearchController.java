package com.filemanager.server.controller;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.filemanager.server.domain.PcDeviceRepository;
import com.filemanager.server.service.FilenameOrganizer;

@RestController
@RequestMapping("/api/files")
public class FileSearchController {

    private static final int MAX_RESULTS = 300;

    private final PcDeviceRepository pcDeviceRepository;
    private final FilenameOrganizer filenameOrganizer;

    public FileSearchController(
            PcDeviceRepository pcDeviceRepository,
            FilenameOrganizer filenameOrganizer) {
        this.pcDeviceRepository = pcDeviceRepository;
        this.filenameOrganizer = filenameOrganizer;
    }

    @GetMapping("/search")
    public ResponseEntity<?> searchFiles(
            Authentication authentication,
            @RequestParam("pcId") Long pcId,
            @RequestParam("query") String query) {

        if (query == null || query.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body("검색어를 입력해 주세요.");
        }

        var pc = pcDeviceRepository.findById(pcId);
        if (pc.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("등록된 PC를 찾을 수 없습니다.");
        }

        Long userId = Long.valueOf(authentication.getName());
        if (!userId.equals(pc.get().getUserId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("이 PC의 파일을 검색할 권한이 없습니다.");
        }

        try {
            Path browseRoot = filenameOrganizer
                    .getBrowseRootDirectory()
                    .toRealPath();
            String keyword = normalizeForSearch(query);
            List<Map<String, Object>> results = new ArrayList<>();

            Files.walkFileTree(browseRoot, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(
                        Path directory,
                        BasicFileAttributes attributes) throws IOException {

                    if (Files.isSymbolicLink(directory)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    if (!directory.equals(browseRoot)
                            && directory.getFileName() != null
                            && normalizeForSearch(
                                    directory.getFileName().toString()
                            ).contains(keyword)) {
                        addResult(browseRoot, directory, attributes, results);
                    }

                    return results.size() >= MAX_RESULTS
                            ? FileVisitResult.TERMINATE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(
                        Path file,
                        BasicFileAttributes attributes) throws IOException {

                    if (!Files.isSymbolicLink(file)
                            && attributes.isRegularFile()
                            && file.getFileName() != null
                            && normalizeForSearch(
                                    file.getFileName().toString()
                            ).contains(keyword)) {
                        addResult(browseRoot, file, attributes, results);
                    }

                    return results.size() >= MAX_RESULTS
                            ? FileVisitResult.TERMINATE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(
                        Path file,
                        IOException exception) {
                    return FileVisitResult.CONTINUE;
                }
            });

            results.sort(
                    Comparator
                            .comparing(
                                    (Map<String, Object> item) ->
                                            !Boolean.TRUE.equals(
                                                    item.get("isDirectory")
                                            )
                            )
                            .thenComparing(
                                    item -> String.valueOf(item.get("fileName")),
                                    String.CASE_INSENSITIVE_ORDER
                            )
            );

            return ResponseEntity.ok(results);
        } catch (IOException exception) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("바탕화면 파일 검색에 실패했습니다.");
        }
    }

    private void addResult(
            Path browseRoot,
            Path candidate,
            BasicFileAttributes attributes,
            List<Map<String, Object>> results) throws IOException {

        Path realPath = candidate.toRealPath();
        if (!realPath.startsWith(browseRoot) || realPath.equals(browseRoot)) {
            return;
        }

        String relativePath = browseRoot
                .relativize(realPath)
                .toString()
                .replace(File.separatorChar, '/');

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("fileName", candidate.getFileName().toString());
        item.put("isDirectory", attributes.isDirectory());
        item.put("fileSize", attributes.isDirectory() ? 0L : attributes.size());
        item.put("path", relativePath);
        item.put("lastModified", attributes.lastModifiedTime().toMillis());
        results.add(item);
    }

    private String normalizeForSearch(String value) {
        return value
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
    }
}
