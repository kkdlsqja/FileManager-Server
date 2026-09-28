package com.filemanager.server.controller;

import java.util.List;
import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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

    // PC 등록
    @GetMapping("/register")
    public ResponseEntity<?> registerPc(
            @RequestParam("userId") Long userId,
            @RequestParam("pcName") String pcName,
            @RequestParam("pcIdentifier") String pcIdentifier) {

        Optional<PcDevice> existingPc =
                repository.findByPcIdentifier(pcIdentifier);

        if (existingPc.isPresent()) {
            return ResponseEntity.ok(existingPc.get());
        }

        PcDevice pc = new PcDevice();
        pc.setUserId(userId);
        pc.setPcName(pcName);
        pc.setPcIdentifier(pcIdentifier);

        PcDevice savedPc = repository.save(pc);
        return ResponseEntity.ok(savedPc);
    }

    // 해당 사용자의 PC 목록 조회
    @GetMapping("/list")
    public ResponseEntity<List<PcDevice>> getPcList(
            @RequestParam("userId") Long userId) {

        List<PcDevice> pcList = repository.findByUserId(userId);
        return ResponseEntity.ok(pcList);
    }
    
    @GetMapping("/connect")
    public ResponseEntity<String> connectPc(
            @RequestParam("pcId") Long pcId) {

        if (!repository.existsById(pcId)) {
            return ResponseEntity.status(404)
                    .body("해당 PC를 찾을 수 없습니다.");
        }

        return ResponseEntity.ok("등록된 PC를 확인했습니다.");
    }
}