package com.example.hazelcast.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 캐시 용도 정의
 *
 * 사용자 ID + 용도로 2중 캐시 키 구조를 구성합니다.
 * 각 용도마다 별도의 Hazelcast Map을 사용하여 TTL·크기를 독립 관리합니다.
 *
 *  구조: {mapName} → key: {userId} → value: Object
 */
@Getter
@RequiredArgsConstructor
public enum CachePurpose {

    /** 로그인 세션 */
    SESSION("user:session", 1800),

    /** 사용자 프로필 정보 */
    PROFILE("user:profile", 600),

    /** 장바구니 */
    CART("user:cart", 3600),

    /** 권한/롤 */
    AUTHORITY("user:authority", 300),

    /** 사용자 설정 */
    SETTINGS("user:settings", 1800),

    /** 임시 인증 토큰 (이메일 인증 등) */
    AUTH_TOKEN("user:auth-token", 300),

    /** API 응답 캐시 (단기) */
    API_RESPONSE("user:api-response", 60);

    /** Hazelcast Map 이름 */
    private final String mapName;

    /** 기본 TTL (초) */
    private final int defaultTtlSeconds;
}
