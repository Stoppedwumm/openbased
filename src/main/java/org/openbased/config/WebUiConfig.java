package org.openbased.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Serves the bundled web UI; {@code /callback} is its OAuth2 redirect URI. */
@Configuration
public class WebUiConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/callback").setViewName("forward:/index.html");
    }
}
