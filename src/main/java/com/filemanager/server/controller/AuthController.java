package com.filemanager.server.controller;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.filemanager.server.domain.AuthRequest;
import com.filemanager.server.domain.User;
import com.filemanager.server.domain.UserRepository;
import com.filemanager.server.security.TokenService;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    public AuthController(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            TokenService tokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
    }

    @PostMapping("/signup")
    public ResponseEntity<String> signup(@RequestBody AuthRequest request) {
        String email = normalizeEmail(request.getEmail());
        String password = request.getPassword();
        if (email.isBlank() || password == null || password.length() < 8) {
            return ResponseEntity.badRequest().body("이메일을 입력하고 비밀번호는 8자 이상으로 설정해 주세요.");
        }
        if (userRepository.findByEmail(email).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("이미 가입된 이메일입니다.");
        }

        User user = new User();
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        userRepository.save(user);
        return ResponseEntity.ok("회원가입 성공! 로그인해 주세요.");
    }

    @PostMapping("/login")
    public ResponseEntity<String> login(@RequestBody AuthRequest request) {
        String email = normalizeEmail(request.getEmail());
        String password = request.getPassword();
        User user = userRepository.findByEmail(email).orElse(null);

        if (user == null || password == null || !passwordMatchesAndUpgrade(user, password)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("로그인 실패: 이메일이나 비밀번호를 확인해 주세요.");
        }

        // 앱은 이 토큰을 이후 요청의 Authorization: Bearer 헤더에 담습니다.
        return ResponseEntity.ok(tokenService.issueToken(user));
    }

    @PostMapping("/logout")
    public ResponseEntity<String> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        tokenService.revokeToken(extractBearerToken(authorization));
        return ResponseEntity.ok("로그아웃했습니다.");
    }

    @GetMapping("/verify")
    public ResponseEntity<String> verifyToken(Authentication authentication) {
        return ResponseEntity.ok("인증 성공: 사용자 ID " + authentication.getName());
    }

    private boolean passwordMatchesAndUpgrade(User user, String rawPassword) {
        String savedPassword = user.getPassword();
        if (savedPassword != null && savedPassword.matches("\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}")
                && passwordEncoder.matches(rawPassword, savedPassword)) {
            return true;
        }

        // 기존 H2 파일 DB의 평문 비밀번호는 최초 로그인 성공 때 BCrypt로 변환합니다.
        if (rawPassword.equals(savedPassword)) {
            user.setPassword(passwordEncoder.encode(rawPassword));
            userRepository.save(user);
            return true;
        }
        return false;
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private String extractBearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring(7).trim();
    }
}
