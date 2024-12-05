package com.github.liyue2008.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class VersionController {

    @GetMapping("/version")
    public String getVersion() {
        return "1.0.0";
    }
}
