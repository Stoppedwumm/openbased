package org.openbased.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/** Validates {@code page}/{@code pageSize} query parameters. */
public final class Paging {

    public static final int MAX_PAGE_SIZE = 200;

    private Paging() {
    }

    public static PageRequest of(Integer page, Integer pageSize, Sort sort) {
        int p = page == null ? 0 : page;
        int size = pageSize == null ? 50 : pageSize;
        if (p < 0) {
            throw ApiException.badRequest("page must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw ApiException.badRequest("pageSize must be between 1 and " + MAX_PAGE_SIZE);
        }
        return PageRequest.of(p, size, sort);
    }
}
