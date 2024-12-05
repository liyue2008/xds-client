package com.github.liyue2008.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "istiod.host=localhost",
    "istiod.port=15010"
})
public class XdsServiceTest {
    @Autowired
    private XdsService xdsService;


    @Test
    void testFetchServiceRoutes() {
        xdsService.fetchServiceRoutes("xds-client", "default");
    }
}
