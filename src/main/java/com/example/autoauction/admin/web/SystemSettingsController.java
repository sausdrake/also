package com.example.autoauction.admin.web;

import com.example.autoauction.admin.application.SystemSettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/settings")
@PreAuthorize("hasRole('ADMIN')")
public class SystemSettingsController {

    private final SystemSettingsService settingsService;

    public SystemSettingsController(SystemSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public ResponseEntity<Map<String, String>> getAll() {
        return ResponseEntity.ok(settingsService.getAll());
    }

    @PutMapping
    public ResponseEntity<Map<String, String>> updateAll(@RequestBody Map<String, String> settings) {
        settingsService.setAll(settings);
        return ResponseEntity.ok(settingsService.getAll());
    }
}
