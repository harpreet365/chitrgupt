package com.bms.seed;

import com.bms.common.ApiException;
import com.bms.config.BmsProperties;
import com.bms.domain.RoleName;
import com.bms.repo.UserRepository;
import com.bms.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Creates the first administrator ONLY when no administrator exists and BOOTSTRAP_ADMIN_USERNAME /
 * BOOTSTRAP_ADMIN_PASSWORD are supplied through the environment (setup.ps1 generates a random password
 * into the git-ignored .env file). No administrator password exists in source code.
 */
@Component
@Order(1)
public class AdminBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final UserService userService;
    private final BmsProperties props;

    public AdminBootstrap(UserRepository users, UserService userService, BmsProperties props) {
        this.users = users;
        this.userService = userService;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.countByRole_Name(RoleName.ADMIN) > 0) return;
        var cfg = props.bootstrapAdmin();
        if (isBlank(cfg.username()) || isBlank(cfg.password())) {
            log.warn("No administrator exists. Set BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_EMAIL and "
                    + "BOOTSTRAP_ADMIN_PASSWORD (see .env.example) and restart to create one.");
            return;
        }
        String email = isBlank(cfg.email()) ? cfg.username() + "@bank.example" : cfg.email();
        try {
            userService.createUser(cfg.username(), email, "System Administrator", cfg.password(), RoleName.ADMIN,
                    cfg.mustChangePassword(), false);
        } catch (ApiException e) {
            log.error("Bootstrap administrator NOT created: {}", e.getMessage());
            return;
        }
        log.info("Bootstrap administrator '{}' created (must change password at first sign-in: {})",
                cfg.username(), cfg.mustChangePassword());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
