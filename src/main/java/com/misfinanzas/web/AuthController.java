package com.misfinanzas.web;

import com.misfinanzas.dto.ChangePasswordRequest;
import com.misfinanzas.entity.AppUser;
import com.misfinanzas.repository.AppUserRepository;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final String dataDir;

    public AuthController(AppUserRepository appUserRepository, PasswordEncoder passwordEncoder,
                           @org.springframework.beans.factory.annotation.Value("${app.data-dir}") String dataDir) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.dataDir = dataDir;
    }

    @GetMapping("/auth/whoami")
    public Map<String, String> whoami(Authentication authentication) {
        return Map.of("username", authentication.getName());
    }

    @PostMapping("/auth/change-password")
    public Map<String, Object> changePassword(Authentication authentication,
                                               @RequestBody ChangePasswordRequest payload) {
        if (payload.new_password() == null || payload.new_password().length() < 6) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "La contraseña nueva debe tener al menos 6 caracteres");
        }
        AppUser user = appUserRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "No autenticado"));
        if (!passwordEncoder.matches(payload.current_password(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "La contraseña actual no es correcta");
        }
        user.setPasswordHash(passwordEncoder.encode(payload.new_password()));
        appUserRepository.save(user);
        return Map.of("ok", true);
    }

    @GetMapping("/backup/download")
    public ResponseEntity<FileSystemResource> downloadBackup() {
        File dbFile = new File(dataDir, "finanzas.db");
        if (!dbFile.exists()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Todavía no hay datos guardados");
        }
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"finanzas_backup_" + stamp + ".db\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(dbFile));
    }
}
