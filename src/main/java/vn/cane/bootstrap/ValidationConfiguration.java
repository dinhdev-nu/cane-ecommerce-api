package vn.cane.bootstrap;

import jakarta.validation.Validator;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.admin.application.SettingsValidator;
import vn.cane.support.validation.InputNormalizer;

@Configuration(proxyBeanMethods = false)
public class ValidationConfiguration {
    @Bean
    InputNormalizer inputNormalizer(Validator validator) {
        return new InputNormalizer(validator);
    }

    @Bean
    SettingsValidator settingsValidator(JsonMapper mapper, InputNormalizer normalizer, Clock clock) {
        return new SettingsValidator(mapper, normalizer, clock);
    }
}
