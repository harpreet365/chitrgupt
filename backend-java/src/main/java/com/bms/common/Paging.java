package com.bms.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

/** Builds validated Pageable objects so clients can only sort on whitelisted properties. */
public final class Paging {
    public static final int MAX_SIZE = 100;

    private Paging() {}

    public static Pageable of(Integer page, Integer size, String sort, Set<String> allowedSort, String defaultSort) {
        int p = page == null ? 0 : page;
        int s = size == null ? 20 : size;
        if (p < 0 || s < 1 || s > MAX_SIZE) {
            throw ApiException.badRequest("INVALID_PAGINATION", "page must be >= 0 and size between 1 and " + MAX_SIZE);
        }
        String spec = (sort == null || sort.isBlank()) ? defaultSort : sort;
        String[] parts = spec.split(",");
        String property = parts[0].trim();
        if (!allowedSort.contains(property)) {
            throw ApiException.badRequest("INVALID_SORT", "Sorting by '" + property + "' is not supported");
        }
        Sort.Direction dir = parts.length > 1 && parts[1].trim().equalsIgnoreCase("asc")
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        return PageRequest.of(p, s, Sort.by(dir, property).and(Sort.by(Sort.Direction.DESC, "id")));
    }
}
