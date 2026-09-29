package com.filemanager.server.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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
public class FileController {

    private final PcDeviceRepository pcDeviceRepository;
    private final FilenameOrganizer filenameOrganizer;

    public FileController(
            PcDeviceRepository pcDeviceRepository,
            FilenameOrganizer filenameOrganizer) {
        this.pcDeviceRepository = pcDeviceRepository;
        this.filenameOrganizer = filenameOrganizer;
    }

    /**
     * path가 비어 있거나 "/"이면 다섯 분류 폴더를 반환하고,
     * 예를 들어 path="업무"이면 C 드라이브의 업무 폴더 내용을 반환합니다.
     */
    @GetMapping("/list")
    public ResponseEntity<?> getFileList(
            Authentication authentication,
            @RequestParam("pcId") Long pcId,
            @RequestParam(name = "path", required = false, defaultValue = "") String path) {

        var pc = pcDeviceRepository.findById(pcId);
        if (pc.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("등록된 PC를 찾을 수 없습니다.");
        }
        if (!Long.valueOf(authentication.getName()).equals(pc.get().getUserId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("이 PC의 파일 목록을 볼 권한이 없습니다.");
        }

        try {
            String virtualPath = normalizeVirtualPath(path);

            // 휴대폰의 최상위 화면은 지정된 분류 폴더 다섯 개만 보여줍니다.
            if (virtualPath.isEmpty()) {
                filenameOrganizer.organizeRootFiles();
                return ResponseEntity.ok(createCategoryList());
            }

            String[] parts = virtualPath.split("/");
            String category = parts[0];
            Path categoryRoot = filenameOrganizer.getDestinationDirectories().get(category);

            if (categoryRoot == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body("요청한 분류 폴더를 찾을 수 없습니다.");
            }

            StringBuilder nestedPath = new StringBuilder();
            for (int i = 1; i < parts.length; i++) {
                if (nestedPath.length() > 0) {
                    nestedPath.append('/');
                }
                nestedPath.append(parts[i]);
            }

            Path requestedDirectory = categoryRoot;
            if (nestedPath.length() > 0) {
                Path relative = Paths.get(nestedPath.toString()).normalize();
                if (relative.isAbsolute() || relative.startsWith("..")) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body("분류 폴더 밖의 경로에는 접근할 수 없습니다.");
                }
                requestedDirectory = categoryRoot.resolve(relative).normalize();
            }

            if (!requestedDirectory.startsWith(categoryRoot)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("분류 폴더 밖의 경로에는 접근할 수 없습니다.");
            }

            if (!Files.exists(requestedDirectory)
                    || !Files.isDirectory(requestedDirectory)
                    || Files.isSymbolicLink(requestedDirectory)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body("요청한 폴더를 찾을 수 없습니다.");
            }

            Path realCategoryRoot = categoryRoot.toRealPath();
            Path realRequestedDirectory = requestedDirectory.toRealPath();
            if (!realRequestedDirectory.startsWith(realCategoryRoot)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("분류 폴더 밖의 경로에는 접근할 수 없습니다.");
            }

            return ResponseEntity.ok(
                    createFileList(realCategoryRoot, realRequestedDirectory, virtualPath)
            );

        } catch (InvalidPathException e) {
            return ResponseEntity.badRequest()
                    .body("올바르지 않은 폴더 경로입니다.");
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("파일 목록을 처리하지 못했습니다: " + e.getMessage());
        }
    }

    private String normalizeVirtualPath(String path) {
        String safePath = path == null ? "" : path.trim().replace('\\', '/');

        while (safePath.startsWith("/")) {
            safePath = safePath.substring(1);
        }

        if (safePath.matches("^[A-Za-z]:.*")) {
            throw new InvalidPathException(safePath, "드라이브 경로는 허용되지 않습니다.");
        }

        Path normalized = Paths.get(safePath).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..")) {
            throw new InvalidPathException(safePath, "상위 경로는 허용되지 않습니다.");
        }

        String result = normalized.toString().replace('\\', '/');
        if (".".equals(result)) {
            return "";
        }

        return result;
    }

    private List<Map<String, Object>> createCategoryList() throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();

        for (Map.Entry<String, Path> entry : filenameOrganizer
                .getDestinationDirectories().entrySet()) {
            Files.createDirectories(entry.getValue());

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fileName", entry.getKey());
            item.put("isDirectory", true);
            item.put("fileSize", 0L);
            item.put("path", entry.getKey());
            item.put("lastModified", Files.getLastModifiedTime(entry.getValue()).toMillis());
            result.add(item);
        }

        return result;
    }

    private List<Map<String, Object>> createFileList(
            Path allowedRoot,
            Path directory,
            String virtualDirectory) throws IOException {

        List<Path> children = new ArrayList<>();
        try (Stream<Path> entries = Files.list(directory)) {
            entries.forEach(children::add);
        }

        children.sort(
                Comparator
                        .comparing((Path child) -> !Files.isDirectory(child))
                        .thenComparing(
                                child -> child.getFileName().toString(),
                                String.CASE_INSENSITIVE_ORDER
                        )
        );

        List<Map<String, Object>> fileList = new ArrayList<>();

        for (Path child : children) {
            if (Files.isSymbolicLink(child)) {
                continue;
            }

            Path realChild = child.toRealPath();
            if (!realChild.startsWith(allowedRoot)) {
                continue;
            }

            boolean isDirectory = Files.isDirectory(realChild);
            String childVirtualPath = virtualDirectory + "/"
                    + child.getFileName().toString();

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fileName", child.getFileName().toString());
            item.put("isDirectory", isDirectory);
            item.put("fileSize", isDirectory ? 0L : Files.size(realChild));
            item.put("path", childVirtualPath);
            item.put("lastModified", Files.getLastModifiedTime(realChild).toMillis());
            fileList.add(item);
        }

        return fileList;
    }
}
