package com.aid.config.imagedetection.controller;

import java.net.URI;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.aid.common.aid.core.service.ConfigService;

import lombok.RequiredArgsConstructor;

/** Restricts the admin probe to images already recorded by the application. */
@Component
@RequiredArgsConstructor
class RegisteredDetectionImage {
    private static final String PATH_SQL = "SELECT ("
            + "EXISTS(SELECT 1 FROM aid_media_result WHERE media_type='IMAGE' AND oss_url IN (?,?)) OR "
            + "EXISTS(SELECT 1 FROM aid_comic_asset WHERE del_flag='0' AND image_url IN (?,?)) OR "
            + "EXISTS(SELECT 1 FROM aid_user_comic_asset WHERE del_flag='0' AND status='0' AND image_url IN (?,?)))";
    private static final String URL_SQL = "SELECT ("
            + "EXISTS(SELECT 1 FROM aid_media_result WHERE media_type='IMAGE' AND (origin_url=? OR oss_url=?)) OR "
            + "EXISTS(SELECT 1 FROM aid_comic_asset WHERE del_flag='0' AND image_url=?) OR "
            + "EXISTS(SELECT 1 FROM aid_user_comic_asset WHERE del_flag='0' AND status='0' AND image_url=?))";

    private final JdbcTemplate jdbcTemplate;
    private final ConfigService configService;

    boolean hasCosKey(String key) {
        if (key == null || key.isBlank()) return false;
        String clean = key.startsWith("/") ? key.substring(1) : key;
        return exists(PATH_SQL, clean, "/" + clean, clean, "/" + clean, clean, "/" + clean);
    }

    boolean hasUrl(String value) {
        if (value == null || value.isBlank()) return false;
        if (exists(URL_SQL, value, value, value, value)) return true;
        try {
            URI url = URI.create(value);
            Map<String, String> oss = configService.getConfigValues("oss");
            URI base = URI.create(oss.getOrDefault("resourceAccessDomain", ""));
            if (!"https".equalsIgnoreCase(url.getScheme()) || !"https".equalsIgnoreCase(base.getScheme())
                    || url.getHost() == null || !url.getHost().equalsIgnoreCase(base.getHost())) return false;
            String prefix = base.getPath() == null ? "" : base.getPath().replaceAll("/$", "");
            String path = url.getPath();
            if (!prefix.isEmpty()) {
                if (!path.startsWith(prefix + "/")) return false;
                path = path.substring(prefix.length());
            }
            return hasCosKey(path);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean exists(String sql, Object... parameters) {
        return Integer.valueOf(1).equals(jdbcTemplate.queryForObject(sql, Integer.class, parameters));
    }
}
