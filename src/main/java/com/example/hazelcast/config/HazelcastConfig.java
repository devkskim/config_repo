package com.example.hazelcast.config;

import com.example.hazelcast.model.CachePurpose;
import com.hazelcast.config.*;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Hazelcast 클러스터 및 캐시 Map 설정
 *
 * CachePurpose enum 에 정의된 각 용도별로 독립적인 Hazelcast Map을 구성합니다.
 * TTL과 최대 크기는 hazelcast-service.yml의 프로파일별 설정값을 따릅니다.
 */
@EnableCaching
@Configuration
public class HazelcastConfig {

    @Value("${hazelcast.cluster-name:hazelcast-cluster}")
    private String clusterName;

    @Value("${hazelcast.network.port:5701}")
    private int port;

    @Value("${hazelcast.network.port-auto-increment:true}")
    private boolean portAutoIncrement;

    @Value("${hazelcast.network.join.multicast.enabled:false}")
    private boolean multicastEnabled;

    @Value("${hazelcast.network.join.multicast.multicast-group:224.2.2.3}")
    private String multicastGroup;

    @Value("${hazelcast.network.join.multicast.multicast-port:54327}")
    private int multicastPort;

    @Value("${hazelcast.network.join.tcp-ip.enabled:false}")
    private boolean tcpIpEnabled;

    @Value("${hazelcast.network.join.tcp-ip.members:}")
    private String tcpIpMembers;

    // Purpose별 Map 크기 설정 (yml에서 purpose 이름으로 오버라이드 가능)
    @Value("${hazelcast.map.user-session.max-size:5000}")
    private int sessionMaxSize;

    @Value("${hazelcast.map.user-profile.max-size:10000}")
    private int profileMaxSize;

    @Value("${hazelcast.map.user-cart.max-size:10000}")
    private int cartMaxSize;

    @Value("${hazelcast.map.user-authority.max-size:10000}")
    private int authorityMaxSize;

    @Value("${hazelcast.map.user-settings.max-size:10000}")
    private int settingsMaxSize;

    @Value("${hazelcast.map.user-auth-token.max-size:5000}")
    private int authTokenMaxSize;

    @Value("${hazelcast.map.user-api-response.max-size:5000}")
    private int apiResponseMaxSize;

    @Bean
    public HazelcastInstance hazelcastInstance() {
        return Hazelcast.newHazelcastInstance(buildConfig());
    }

    private Config buildConfig() {
        Config config = new Config();
        config.setClusterName(clusterName);

        // 네트워크 설정
        NetworkConfig network = config.getNetworkConfig();
        network.setPort(port).setPortAutoIncrement(portAutoIncrement);

        JoinConfig join = network.getJoin();
        join.getMulticastConfig()
            .setEnabled(multicastEnabled)
            .setMulticastGroup(multicastGroup)
            .setMulticastPort(multicastPort);

        join.getTcpIpConfig().setEnabled(tcpIpEnabled);
        if (tcpIpEnabled && tcpIpMembers != null && !tcpIpMembers.isBlank()) {
            for (String member : tcpIpMembers.split(",")) {
                join.getTcpIpConfig().addMember(member.trim());
            }
        }

        // CachePurpose 별 Map 자동 등록
        registerPurposeMap(config, CachePurpose.SESSION,      sessionMaxSize);
        registerPurposeMap(config, CachePurpose.PROFILE,      profileMaxSize);
        registerPurposeMap(config, CachePurpose.CART,         cartMaxSize);
        registerPurposeMap(config, CachePurpose.AUTHORITY,    authorityMaxSize);
        registerPurposeMap(config, CachePurpose.SETTINGS,     settingsMaxSize);
        registerPurposeMap(config, CachePurpose.AUTH_TOKEN,   authTokenMaxSize);
        registerPurposeMap(config, CachePurpose.API_RESPONSE, apiResponseMaxSize);

        // Spring Session용 Map
        config.addMapConfig(buildMapConfig(
                "spring:session:sessions",
                CachePurpose.SESSION.getDefaultTtlSeconds(),
                sessionMaxSize
        ));

        return config;
    }

    private void registerPurposeMap(Config config, CachePurpose purpose, int maxSize) {
        config.addMapConfig(buildMapConfig(
                purpose.getMapName(),
                purpose.getDefaultTtlSeconds(),
                maxSize
        ));
    }

    private MapConfig buildMapConfig(String name, int ttlSeconds, int maxSize) {
        return new MapConfig(name)
                .setTimeToLiveSeconds(ttlSeconds)
                .setEvictionConfig(
                    new EvictionConfig()
                        .setEvictionPolicy(EvictionPolicy.LRU)
                        .setMaxSizePolicy(MaxSizePolicy.PER_NODE)
                        .setSize(maxSize)
                );
    }
}
