package com.filemanager.server.config;

import com.filemanager.server.domain.User;
import com.filemanager.server.domain.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DummyDataInit {

    @Bean
    public CommandLineRunner initData(UserRepository userRepository) {
        return args -> {
            // 서버가 켜질 때 'test@test.com' 계정이 없으면 자동으로 생성
            if (userRepository.findByEmail("test@test.com").isEmpty()) {
                User user = new User();
                user.setEmail("test@test.com");
                user.setPassword("1234");
                userRepository.save(user);
                System.out.println("✅ 테스트용 계정 자동 생성 완료 (ID: test@test.com / PW: 1234)");
            }
        };
    }
}