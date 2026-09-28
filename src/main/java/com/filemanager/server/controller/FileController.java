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

    @GetMapping("/list")
    public ResponseEntity<?> getFileList(
            @RequestParam("pcId") Long pcId,
            @RequestParam(name = "path", required = false, defaultValue = "") String relativePath) {

        if (!pcDeviceRepository.findById(pcId).isPresent()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("등록된 PC를 찾을 수 없습니다.");
        }

        try {
            Path realRoot = filenameOrganizer.getRootDirectory();

            String safeRelativePath = relativePath == null
                    ? ""
                    : relativePath.trim().replace('\\', '/');

            // 앱이 보내는 "/"는 지정 폴더의 최상위 경로입니다.
            while (safeRelativePath.startsWith("/")) {
                safeRelativePath = safeRelativePath.substring(1);
            }

            Path relative = Paths.get(safeRelativePath).normalize();

            if (relative.isAbsolute()
                    || safeRelativePath.matches("^[A-Za-z]:.*")
                    || relative.startsWith("..")) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("지정 폴더 밖의 경로에는 접근할 수 없습니다.");
            }

            Path requestedDirectory = realRoot.resolve(relative).normalize();

            if (!requestedDirectory.startsWith(realRoot)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("지정 폴더 밖의 경로에는 접근할 수 없습니다.");
            }

            if (!Files.exists(requestedDirectory)
                    || !Files.isDirectory(requestedDirectory)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body("요청한 폴더를 찾을 수 없습니다.");
            }

            Path realRequestedDirectory = requestedDirectory.toRealPath();

            if (!realRequestedDirectory.startsWith(realRoot)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("지정 폴더 밖의 경로에는 접근할 수 없습니다.");
            }

            // 최상위 목록 요청 때 놓친 파일도 다시 확인합니다.
            if (realRequestedDirectory.equals(realRoot)) {
                filenameOrganizer.organizeRootFiles();
            }

            return ResponseEntity.ok(
                    createFileList(realRoot, realRequestedDirectory)
            );

        } catch (InvalidPathException e) {
            return ResponseEntity.badRequest()
                    .body("올바르지 않은 폴더 경로입니다.");
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("파일 목록을 처리하지 못했습니다: " + e.getMessage());
        }
    }

    private List<Map<String, Object>> createFileList(
            Path realRoot,
            Path directory) throws IOException {

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
            Path realChild = child.toRealPath();

            if (!realChild.startsWith(realRoot)) {
                continue;
            }

            boolean isDirectory = Files.isDirectory(realChild);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fileName", child.getFileName().toString());
            item.put("isDirectory", isDirectory);
            item.put("fileSize", isDirectory ? 0L : Files.size(realChild));
            item.put(
                    "path",
                    realRoot.relativize(child.toAbsolutePath().normalize())
                            .toString()
                            .replace('\\', '/')
            );

            fileList.add(item);
        }

        return fileList;
    }
}