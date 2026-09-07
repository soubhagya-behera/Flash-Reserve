package com.soubhagya.flashreserve.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer;

/**
 * Bounds the effective page size of every HTTP {@code Pageable} endpoint
 * (security finding WEB-02).
 *
 * Without this customization Spring Data's resolver accepts client-requested
 * page sizes up to its built-in maximum of 2000 rows, and the public,
 * anonymous {@code GET /api/events?size=...} catalog can therefore be pulled
 * 2000 rows at a time by unauthenticated callers. This customizer lowers the
 * cap to {@value #MAX_PAGE_SIZE} items, which is applied by Spring Boot to
 * the {@code PageableHandlerMethodArgumentResolver} in every profile
 * (development, tests and production) without relying on an environment
 * property that an operator could forget.
 *
 * Deliberately unchanged: the default page size (20, from
 * {@code @PageableDefault}), the default sorting, and Spring's handling of
 * {@code size=0}, negative and non-numeric values (they keep falling back to
 * the default page size with a normal 200 response). Only the upper bound
 * shrinks: requested sizes above {@value #MAX_PAGE_SIZE} are clamped to
 * {@value #MAX_PAGE_SIZE}.
 */
@Configuration
public class PaginationConfig {

	/** Maximum number of items a client may request per page. */
	public static final int MAX_PAGE_SIZE = 100;

	@Bean
	PageableHandlerMethodArgumentResolverCustomizer pageableCustomizer() {
		return resolver -> resolver.setMaxPageSize(MAX_PAGE_SIZE);
	}

}
