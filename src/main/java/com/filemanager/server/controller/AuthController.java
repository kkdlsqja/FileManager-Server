package com.filemanager.server.controller;

import com.filemanager.server.domain.User;
import com.filemanager.server.domain.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;

    public AuthController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // 로그인
    @PostMapping("/login")
    public ResponseEntity<String> login(@RequestParam("email") String email, @RequestParam("password") String password) {
        Optional<User> user = userRepository.findByEmail(email);
        
        if (user.isPresent() && user.get().getPassword().equals(password)) {
            return ResponseEntity.ok("로그인 성공! Token: dummy-token-123");
        }
        
        return ResponseEntity.status(401).body("로그인 실패: 이메일이나 비밀번호가 틀렸습니다.");
    }

    // 회원가입
    @PostMapping("/signup")
    public ResponseEntity<String> signup(@RequestParam("email") String email, @RequestParam("password") String password) {
        if (userRepository.findByEmail(email).isPresent()) {
            return ResponseEntity.status(409).body("회원가입 실패: 이미 존재하는 이메일입니다.");
        }
        
        User newUser = new User();
        newUser.setEmail(email);
        newUser.setPassword(password);
        
        userRepository.save(newUser);
        
        return ResponseEntity.ok("회원가입 성공! 환영합니다.");
    }

    // 로그아웃
    @PostMapping("/logout")
    public ResponseEntity<String> logout() {
        return ResponseEntity.ok("로그아웃 성공! 안전하게 종료되었습니다.");
    }

    // 사용자 인증 상태 확인 (새로 추가된 부분)
    @GetMapping("/verify")
    public ResponseEntity<String> verifyToken(@RequestHeader(value = "Authorization", required = false) String token) {
        // 클라이언트가 보낸 헤더에 'dummy-token-123'이 포함되어 있는지 확인
        if ("Bearer dummy-token-123".equals(token) || "dummy-token-123".equals(token)) {
            return ResponseEntity.ok("인증 성공: 유효한 사용자입니다.");
        }
        return ResponseEntity.status(401).body("인증 실패: 유효하지 않거나 만료된 토큰입니다.");
    }
}