package com.filemanager.server.controller;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.filemanager.server.domain.FileOperationLog;
import com.filemanager.server.domain.FileOperationLogRepository;
import com.filemanager.server.domain.PcDeviceRepository;
import com.filemanager.server.service.FilenameOrganizer;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private final PcDeviceRepository pcDeviceRepository;
    private final FileOperationLogRepository fileOperationLogRepository;
    private final FilenameOrganizer filenameOrganizer;

    public FileController(
            PcDeviceRepository pcDeviceRepository,
            FileOperationLogRepository fileOperationLogRepository,
            FilenameOrganizer filenameOrganizer) {
        this.pcDeviceRepository = pcDeviceRepository;
        this.fileOperationLogRepository = fileOperationLogRepository;
        this.filenameOrganizer = filenameOrganizer;
    }

    /** path가 비어 있거나 "/"이면 바탕화면 내용을 반환합니다. */
    @GetMapping("/list")
    public ResponseEntity<?> getFileList(
            Authentication authentication,
            @RequestParam("pcId") Long pcId,
            @RequestParam(
                    name = "path",
                    required = false,
                    defaultValue = ""
            ) String path) {

        var pc = pcDeviceRepository.findById(pcId);

        if (pc.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("등록된 PC를 찾을 수 없습니다.");
        }

        if (!Long.valueOf(authentication.getName())
                .equals(pc.get().getUserId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("이 PC의 파일 목록을 볼 권한이 없습니다.");
        }

        try {
            String virtualPath = normalizeVirtualPath(path);
            Path browseRoot = filenameOrganizer.getBrowseRootDirectory();
            Path requestedDirectory = browseRoot;

            if (!virtualPath.isEmpty()) {
                Path relative = Paths.get(virtualPath).normalize();
                if (relative.isAbsolute() || relative.startsWith("..")) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body("바탕화면 바깥의 경로에는 접근할 수 없습니다.");
                }
                requestedDirectory = browseRoot.resolve(relative).normalize();
            }

            if (!requestedDirectory.startsWith(browseRoot)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("바탕화면 바깥의 경로에는 접근할 수 없습니다.");
            }

            if (!Files.exists(requestedDirectory)
                    || !Files.isDirectory(requestedDirectory)
                    || Files.isSymbolicLink(requestedDirectory)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body("요청한 폴더를 찾을 수 없습니다.");
            }

            Path realBrowseRoot = browseRoot.toRealPath();
            Path realRequestedDirectory = requestedDirectory.toRealPath();

            if (!realRequestedDirectory.startsWith(realBrowseRoot)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("바탕화면 바깥의 경로에는 접근할 수 없습니다.");
            }

            return ResponseEntity.ok(
                    createFileList(
                            realBrowseRoot,
                            realRequestedDirectory,
                            virtualPath
                    )
            );

        } catch (InvalidPathException e) {
            return ResponseEntity.badRequest()
                    .body("올바르지 않은 폴더 경로입니다.");
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("이 폴더에 접근할 권한이 없습니다.");
        } catch (IOException e) {
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("파일 목록을 처리하지 못했습니다: " + e.getMessage());
        }
    }

    /** 바탕화면에서 선택한 파일을 바탕화면 안의 목적지 폴더로 이동합니다. */
    @PostMapping("/move")
    public ResponseEntity<String> moveFile(
            Authentication authentication,
            @RequestParam("pcId") Long pcId,
            @RequestParam("sourcePath") String sourcePath,
            @RequestParam("destinationPath") String destinationPath) {

        var pc = pcDeviceRepository.findById(pcId);
        if (pc.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("등록된 PC를 찾을 수 없습니다.");
        }

        Long userId = Long.valueOf(authentication.getName());
        if (!userId.equals(pc.get().getUserId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("이 PC의 파일을 이동할 권한이 없습니다.");
        }

        try {
            filenameOrganizer.moveFile(sourcePath, destinationPath);
            return ResponseEntity.ok("파일을 이동했습니다.");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("선택한 파일에 접근할 권한이 없습니다.");
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("파일 이동에 실패했습니다: " + e.getMessage());
        }
    }

    /**
     * 요청한 PC의 최근 분류 기록 100개를 반환합니다.
     * 로그인한 사용자가 해당 PC의 소유자인지도 확인합니다.
     */
    @GetMapping("/history")
    public ResponseEntity<?> getFileHistory(
            Authentication authentication,
            @RequestParam("pcId") Long pcId) {

        var pc = pcDeviceRepository.findById(pcId);

        if (pc.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("등록된 PC를 찾을 수 없습니다.");
        }

        Long userId = Long.valueOf(authentication.getName());

        if (!userId.equals(pc.get().getUserId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("이 PC의 분류 기록을 볼 권한이 없습니다.");
        }

        List<FileOperationLog> history =
                fileOperationLogRepository
                        .findTop100ByPcIdentifierOrderByOccurredAtDesc(
                                pc.get().getPcIdentifier()
                        );

        return ResponseEntity.ok(history);
    }

    private String normalizeVirtualPath(String path) {
        String safePath = path == null
                ? ""
                : path.trim().replace('\\', '/');

        while (safePath.startsWith("/")) {
            safePath = safePath.substring(1);
        }

        if (safePath.matches("^[A-Za-z]:.*")) {
            throw new InvalidPathException(
                    safePath,
                    "드라이브 경로는 허용되지 않습니다."
            );
        }

        Path normalized = Paths.get(safePath).normalize();

        if (normalized.isAbsolute() || normalized.startsWith("..")) {
            throw new InvalidPathException(
                    safePath,
                    "상위 경로는 허용되지 않습니다."
            );
        }

        String result = normalized.toString().replace('\\', '/');

        if (".".equals(result)) {
            return "";
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
                        .comparing(
                                (Path child) -> !Files.isDirectory(child)
                        )
                        .thenComparing(
                                child -> child.getFileName().toString(),
                                String.CASE_INSENSITIVE_ORDER
                        )
        );

        List<Map<String, Object>> fileList = new ArrayList<>();

        for (Path child : children) {
            try {
                if (Files.isSymbolicLink(child)) {
                    continue;
                }

                Path realChild = child.toRealPath();

                if (!realChild.startsWith(allowedRoot)) {
                    continue;
                }

                boolean isDirectory = Files.isDirectory(realChild);
                String childVirtualPath = virtualDirectory
                        + "/"
                        + child.getFileName().toString();

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("fileName", child.getFileName().toString());
                item.put("isDirectory", isDirectory);
                item.put("fileSize", isDirectory ? 0L : Files.size(realChild));
                item.put("path", childVirtualPath);
                item.put(
                        "lastModified",
                        Files.getLastModifiedTime(realChild).toMillis()
                );

                fileList.add(item);
            } catch (AccessDeniedException ignored) {
                // 접근 제한 항목 하나 때문에 전체 목록이 실패하지 않게 건너뜁니다.
            }
        }

        return fileList;
    }
}
