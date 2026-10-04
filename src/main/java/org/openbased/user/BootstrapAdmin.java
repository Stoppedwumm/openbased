package org.openbased.user;

import java.util.Set;

import org.openbased.common.Ids;
import org.openbased.config.OpenBasedProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Creates an administrator on first start so the installation can be configured. */
@Component
@Order(0)
public class BootstrapAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    private final UserRepository users;
    private final UserService userService;
    private final OpenBasedProperties properties;

    public BootstrapAdmin(UserRepository users, UserService userService, OpenBasedProperties properties) {
        this.users = users;
        this.userService = userService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        String username = properties.getBootstrap().getAdminUsername();
        String password = properties.getBootstrap().getAdminPassword();
        boolean generated = password == null || password.isBlank();
        if (generated) {
            password = Ids.randomAlphanumeric(20);
        }
        userService.create(username, password, "Administrator", null, Set.of(Role.ADMIN), Set.of());
        if (generated) {
            log.warn("Created administrator '{}' with generated password: {}  (change it after signing in)",
                    username, password);
        } else {
            log.info("Created administrator '{}'", username);
        }
    }
}
