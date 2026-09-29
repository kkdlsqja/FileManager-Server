package com.filemanager.server.controller;

import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.filemanager.server.domain.PcDevice;
import com.filemanager.server.domain.PcDeviceRepository;

@RestController
@RequestMapping("/api/pc")
public class PcController {

    private final PcDeviceRepository repository;

    public PcController(PcDeviceRepository repository) {
        this.repository = repository;
    }

    @PostMapping("/register")
    public ResponseEntity<?> registerPc(
            Authentication authentication,
            @RequestParam("pcName") String pcName,
            @RequestParam("pcIdentifier") String pcIdentifier) {

        Long userId = Long.valueOf(authentication.getName());
        Optional<PcDevice> existing =
                repository.findByPcIdentifier(pcIdentifier);

        if (existing.isPresent()) {
            if (!userId.equals(existing.get().getUserId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("다른 계정에 등록된 PC입니다.");
            }
            return ResponseEntity.ok(existing.get());
        }

        PcDevice pc = new PcDevice();
        pc.setUserId(userId);
        pc.setPcName(pcName);
        pc.setPcIdentifier(pcIdentifier);

        return ResponseEntity.ok(repository.save(pc));
    }

    @GetMapping("/list")
    public ResponseEntity<List<PcDevice>> getPcList(
            Authentication authentication) {

        Long userId = Long.valueOf(authentication.getName());
        return ResponseEntity.ok(repository.findByUserId(userId));
    }

    @GetMapping("/connect")
    public ResponseEntity<String> connectPc(
            Authentication authentication,
            @RequestParam("pcId") Long pcId) {

        Optional<PcDevice> device = repository.findById(pcId);

        if (device.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("해당 PC를 찾을 수 없습니다.");
        }

        Long userId = Long.valueOf(authentication.getName());

        if (!userId.equals(device.get().getUserId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("이 PC에 접근할 권한이 없습니다.");
        }

        return ResponseEntity.ok("등록된 PC를 확인했습니다.");
    }
}