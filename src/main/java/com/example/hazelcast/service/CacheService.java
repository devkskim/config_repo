package com.example.hazelcast.service;

import com.example.hazelcast.model.CachePurpose;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 사용자 ID + 용도(Purpose) 2중 구조 Hazelcast 캐시 서비스
 *
 * <pre>
 * [ 캐시 구조 ]
 *
 *  Hazelcast Map (Purpose별 독립 Map)
 *  ┌──────────────────┬────────────────────────────┐
 *  │  Map 이름        │  Key(userId) → Value        │
 *  ├──────────────────┼────────────────────────────┤
 *  │ user:session     │ "user-001" → SessionData    │
 *  │ user:profile     │ "user-001" → ProfileData    │
 *  │ user:cart        │ "user-001" → CartData       │
 *  │ user:authority   │ "user-001" → List<Role>     │
 *  │ user:settings    │ "user-001" → SettingsData   │
 *  │ user:auth-token  │ "user-001" → "abc123"       │
 *  │ user:api-response│ "user-001" → ResponseData   │
 *  └──────────────────┴────────────────────────────┘
 *
 *  조회: get(userId, CachePurpose.PROFILE)
 *  저장: put(userId, CachePurpose.PROFILE, data)
 *  삭제: evict(userId, CachePurpose.PROFILE)
 *  전체 삭제: evictUser(userId)   — 해당 유저의 모든 Purpose 삭제
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheService {

    private final HazelcastInstance hazelcastInstance;

    // -------------------------------------------------------------------------
    // 기본 CRUD (userId + Purpose)
    // -------------------------------------------------------------------------

    /**
     * 캐시에서 값을 조회합니다.
     *
     * @param userId  사용자 ID
     * @param purpose 캐시 용도
     * @return Optional로 감싼 캐시 값
     */
    public Optional<Object> get(String userId, CachePurpose purpose) {
        Object value = getMap(purpose).get(userId);
        if (value == null) {
            log.debug("[MISS] purpose={}, userId={}", purpose.getMapName(), userId);
        } else {
            log.debug("[HIT]  purpose={}, userId={}", purpose.getMapName(), userId);
        }
        return Optional.ofNullable(value);
    }

    /**
     * 기본 TTL로 캐시에 값을 저장합니다.
     *
     * @param userId  사용자 ID
     * @param purpose 캐시 용도
     * @param value   저장할 값
     */
    public void put(String userId, CachePurpose purpose, Object value) {
        getMap(purpose).set(userId, value, purpose.getDefaultTtlSeconds(), TimeUnit.SECONDS);
        log.debug("[PUT]  purpose={}, userId={}, ttl={}s", purpose.getMapName(), userId, purpose.getDefaultTtlSeconds());
    }

    /**
     * 커스텀 TTL로 캐시에 값을 저장합니다.
     *
     * @param userId   사용자 ID
     * @param purpose  캐시 용도
     * @param value    저장할 값
     * @param ttl      유지 시간
     * @param timeUnit 시간 단위
     */
    public void put(String userId, CachePurpose purpose, Object value, long ttl, TimeUnit timeUnit) {
        getMap(purpose).set(userId, value, ttl, timeUnit);
        log.debug("[PUT]  purpose={}, userId={}, ttl={} {}", purpose.getMapName(), userId, ttl, timeUnit);
    }

    /**
     * 캐시에 값이 없을 때만 저장합니다.
     *
     * @param userId  사용자 ID
     * @param purpose 캐시 용도
     * @param value   저장할 값
     * @return 이미 존재하던 이전 값 (없으면 null)
     */
    public Object putIfAbsent(String userId, CachePurpose purpose, Object value) {
        Object prev = getMap(purpose).putIfAbsent(userId, value,
                purpose.getDefaultTtlSeconds(), TimeUnit.SECONDS);
        log.debug("[PUT_IF_ABSENT] purpose={}, userId={}, inserted={}", purpose.getMapName(), userId, prev == null);
        return prev;
    }

    /**
     * 캐시에서 값을 조회하고, 없으면 loader를 실행하여 저장 후 반환합니다 (Cache-Aside 패턴).
     *
     * @param userId  사용자 ID
     * @param purpose 캐시 용도
     * @param loader  캐시 미스 시 값을 로드할 Supplier
     * @return 캐시 또는 로더로부터 가져온 값
     */
    public Object getOrLoad(String userId, CachePurpose purpose, Supplier<Object> loader) {
        IMap<String, Object> map = getMap(purpose);
        Object cached = map.get(userId);
        if (cached != null) {
            log.debug("[GET_OR_LOAD - HIT] purpose={}, userId={}", purpose.getMapName(), userId);
            return cached;
        }
        log.debug("[GET_OR_LOAD - MISS] purpose={}, userId={} → loading", purpose.getMapName(), userId);
        Object loaded = loader.get();
        if (loaded != null) {
            map.set(userId, loaded, purpose.getDefaultTtlSeconds(), TimeUnit.SECONDS);
        }
        return loaded;
    }

    // -------------------------------------------------------------------------
    // 삭제
    // -------------------------------------------------------------------------

    /**
     * 특정 userId + Purpose의 캐시를 삭제합니다.
     *
     * @param userId  사용자 ID
     * @param purpose 캐시 용도
     */
    public void evict(String userId, CachePurpose purpose) {
        getMap(purpose).delete(userId);
        log.debug("[EVICT] purpose={}, userId={}", purpose.getMapName(), userId);
    }

    /**
     * 특정 userId의 모든 Purpose 캐시를 삭제합니다 (로그아웃 등에 활용).
     *
     * @param userId 사용자 ID
     */
    public void evictUser(String userId) {
        for (CachePurpose purpose : CachePurpose.values()) {
            getMap(purpose).delete(userId);
        }
        log.info("[EVICT_USER] userId={} - 모든 캐시 삭제 완료", userId);
    }

    /**
     * 특정 Purpose의 모든 캐시를 삭제합니다.
     *
     * @param purpose 캐시 용도
     */
    public void evictAll(CachePurpose purpose) {
        getMap(purpose).clear();
        log.warn("[EVICT_ALL] purpose={} - 전체 캐시 삭제", purpose.getMapName());
    }

    // -------------------------------------------------------------------------
    // 조회/상태 확인
    // -------------------------------------------------------------------------

    /**
     * 특정 userId + Purpose 캐시가 존재하는지 확인합니다.
     *
     * @param userId  사용자 ID
     * @param purpose 캐시 용도
     * @return 존재 여부
     */
    public boolean exists(String userId, CachePurpose purpose) {
        return getMap(purpose).containsKey(userId);
    }

    /**
     * 특정 Purpose 맵에 캐시된 userId 목록을 반환합니다.
     *
     * @param purpose 캐시 용도
     * @return userId Set
     */
    public Set<String> getCachedUserIds(CachePurpose purpose) {
        return getMap(purpose).keySet();
    }

    /**
     * 특정 userId의 모든 Purpose별 캐시 존재 여부를 반환합니다.
     *
     * @param userId 사용자 ID
     * @return Purpose → 존재여부 Map
     */
    public Map<CachePurpose, Boolean> getUserCacheStatus(String userId) {
        return java.util.Arrays.stream(CachePurpose.values())
                .collect(Collectors.toMap(
                        p -> p,
                        p -> getMap(p).containsKey(userId)
                ));
    }

    /**
     * 특정 Purpose 맵의 현재 캐시 항목 수를 반환합니다.
     *
     * @param purpose 캐시 용도
     * @return 항목 수
     */
    public int size(CachePurpose purpose) {
        return getMap(purpose).size();
    }

    // -------------------------------------------------------------------------
    // 용도별 편의 메서드
    // -------------------------------------------------------------------------

    public Optional<Object> getSession(String userId) {
        return get(userId, CachePurpose.SESSION);
    }

    public void putSession(String userId, Object session) {
        put(userId, CachePurpose.SESSION, session);
    }

    public void removeSession(String userId) {
        evict(userId, CachePurpose.SESSION);
    }

    public Optional<Object> getProfile(String userId) {
        return get(userId, CachePurpose.PROFILE);
    }

    public void putProfile(String userId, Object profile) {
        put(userId, CachePurpose.PROFILE, profile);
    }

    public Optional<Object> getAuthority(String userId) {
        return get(userId, CachePurpose.AUTHORITY);
    }

    public void putAuthority(String userId, Object roles) {
        put(userId, CachePurpose.AUTHORITY, roles);
    }

    public Optional<Object> getCart(String userId) {
        return get(userId, CachePurpose.CART);
    }

    public void putCart(String userId, Object cart) {
        put(userId, CachePurpose.CART, cart);
    }

    // -------------------------------------------------------------------------
    // 내부 헬퍼
    // -------------------------------------------------------------------------

    private IMap<String, Object> getMap(CachePurpose purpose) {
        return hazelcastInstance.getMap(purpose.getMapName());
    }
}
