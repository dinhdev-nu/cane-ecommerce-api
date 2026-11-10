package vn.cane.bootstrap;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;

@Configuration(proxyBeanMethods = false)
public class TransactionConfiguration {
    @Bean
    JdbcTransactionManager transactionManager(DataSource dataSource) {
        var manager = new JdbcTransactionManager(dataSource);
        manager.setValidateExistingTransaction(true);
        manager.setEnforceReadOnly(true);
        return manager;
    }
}
