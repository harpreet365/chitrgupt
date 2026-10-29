package com.bms.user;

import com.bms.audit.AuditService;
import com.bms.common.ApiException;
import com.bms.domain.CustomerProfile;
import com.bms.domain.LoginActivity;
import com.bms.domain.LoginOutcome;
import com.bms.domain.RoleName;
import com.bms.domain.User;
import com.bms.repo.CustomerProfileRepository;
import com.bms.repo.LoginActivityRepository;
import com.bms.repo.UserRepository;
import com.bms.user.UserViews.MeView;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MeService {

    private final UserRepository users;
    private final CustomerProfileRepository profiles;
    private final LoginActivityRepository loginActivity;
    private final AuditService audit;

    public MeService(UserRepository users, CustomerProfileRepository profiles, LoginActivityRepository loginActivity,
                     AuditService audit) {
        this.users = users;
        this.profiles = profiles;
        this.loginActivity = loginActivity;
        this.audit = audit;
    }

    public User loadUser(Long id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
    }

    @Transactional(readOnly = true)
    public MeView me(Long userId) {
        User u = loadUser(userId);
        CustomerProfile profile = u.roleName() == RoleName.CUSTOMER ? profiles.findByUser_Id(userId).orElse(null) : null;
        return MeView.of(u, previousSuccessfulLogin(userId), profile);
    }

    /** The successful sign-in before the current one ("Last login" in the UI). */
    private OffsetDateTime previousSuccessfulLogin(Long userId) {
        Specification<LoginActivity> spec = (root, q, cb) -> cb.and(
                cb.equal(root.get("userId"), userId), cb.equal(root.get("outcome"), LoginOutcome.SUCCESS));
        List<LoginActivity> last2 = loginActivity.findAll(spec,
                PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
        return last2.size() < 2 ? null : last2.get(1).getCreatedAt();
    }

    /** Customers may update contact details only - never name, username, role or anything financial. */
    @Transactional
    public MeView updateContact(Long userId, String email, String phone, String addressLine, String city,
                                String postalCode) {
        User u = loadUser(userId);
        Map<String, Object> changed = new LinkedHashMap<>();
        if (email != null && !email.equalsIgnoreCase(u.getEmail())) {
            if (users.emailTakenByOther(email, userId)) {
                throw ApiException.conflict("EMAIL_IN_USE", "That email address is already registered");
            }
            u.setEmail(email.trim().toLowerCase());
            changed.put("email", "updated");
        }
        if (u.roleName() == RoleName.CUSTOMER) {
            CustomerProfile p = profiles.findByUser_Id(userId).orElseThrow(() -> ApiException.notFound("Profile"));
            if (phone != null) { p.setPhone(phone.trim()); changed.put("phone", "updated"); }
            if (addressLine != null) { p.setAddressLine(addressLine.trim()); changed.put("addressLine", "updated"); }
            if (city != null) { p.setCity(city.trim()); changed.put("city", "updated"); }
            if (postalCode != null) { p.setPostalCode(postalCode.trim()); changed.put("postalCode", "updated"); }
        }
        if (!changed.isEmpty()) {
            audit.record("PROFILE_UPDATED", "USER", userId, changed);
        }
        return me(userId);
    }
}
