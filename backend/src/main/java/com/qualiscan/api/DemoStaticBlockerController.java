package com.qualiscan.api;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * Ensures the static development page ws-test.html is NOT served when running in the demo profile.
 */
@Controller
@Profile("demo")
public class DemoStaticBlockerController {

    @RequestMapping(value = "/ws-test.html", method = {RequestMethod.GET, RequestMethod.HEAD})
    public ResponseEntity<Void> blockWsTest() {
        return ResponseEntity.notFound().build();
    }
}
