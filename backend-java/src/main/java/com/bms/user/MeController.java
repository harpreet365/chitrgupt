package com.bms.user;

import com.bms.audit.AuditViews.AuditView;
import com.bms.common.PageResponse;
import com.bms.common.Paging;
import com.bms.repo.AuditLogRepository;
import com.bms.repo.LoginActivityRepository;
import com.bms.security.CurrentUser;
import com.bms.user.UserViews.LoginActivityView;
import com.bms.user.UserViews.MeView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    public record UpdateProfileRequest(
            @Email @Size(max = 254) String email,
            @Pattern(regexp = "^[0-9+\\- ]{7,20}$", message = "must be 7-20 digits, spaces, + or -") String phone,
            @Size(max = 200) String addressLine,
            @Size(max = 80) String city,
            @Pattern(regexp = "^[A-Za-z0-9 \\-]{3,12}$", message = "must be 3-12 letters or digits") String postalCode) {}

    private final MeService me;
    private final LoginActivityRepository loginActivity;
    private final AuditLogRepository auditLogs;

    public MeController(MeService me, LoginActivityRepository loginActivity, AuditLogRepository auditLogs) {
        this.me = me;
        this.loginActivity = loginActivity;
        this.auditLogs = auditLogs;
    }

    @GetMapping
    public MeView get() {
        return me.me(CurrentUser.get().id());
    }

    @PutMapping("/profile")
    public MeView update(@Valid @RequestBody UpdateProfileRequest body) {
        return me.updateContact(CurrentUser.get().id(), body.email(), body.phone(), body.addressLine(), body.city(),
                body.postalCode());
    }

    @GetMapping("/login-activity")
    public PageResponse<LoginActivityView> myLogins(@RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer size) {
        var pageable = Paging.of(page, size, null, Set.of("createdAt"), "createdAt,desc");
        return PageResponse.of(loginActivity.findByUserId(CurrentUser.get().id(), pageable), LoginActivityView::of);
    }

    /** The caller's own actions from the audit trail. */
    @GetMapping("/activity")
    public PageResponse<AuditView> myActivity(@RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size) {
        var pageable = Paging.of(page, size, null, Set.of("createdAt"), "createdAt,desc");
        return PageResponse.of(auditLogs.findByActorUserId(CurrentUser.get().id(), pageable), AuditView::of);
    }
}
