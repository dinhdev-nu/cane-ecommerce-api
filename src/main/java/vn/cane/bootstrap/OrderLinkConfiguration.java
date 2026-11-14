package vn.cane.bootstrap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import vn.cane.orders.application.OrderLinkTokens;
import vn.cane.orders.infrastructure.security.HmacOrderLinkTokens;
import vn.cane.orders.infrastructure.security.LinkKeyRing;

@Configuration(proxyBeanMethods = false)
public class OrderLinkConfiguration {
    @Bean
    LinkKeyRing linkKeyRing(Environment environment) {
        return LinkKeyRing.parse(environment.getProperty("LINK_SECRETS"), environment.getProperty("LINK_ACTIVE_KID"));
    }

    @Bean
    OrderLinkTokens orderLinkTokens(LinkKeyRing ring) {
        return new HmacOrderLinkTokens(ring);
    }
}
