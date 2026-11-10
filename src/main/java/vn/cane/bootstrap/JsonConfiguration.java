package vn.cane.bootstrap;

import java.util.UUID;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.type.LogicalType;
import vn.cane.support.validation.InputNormalizer;
import vn.cane.support.validation.InputValidationException;

@Configuration(proxyBeanMethods = false)
public class JsonConfiguration {
    @Bean
    JsonMapperBuilderCustomizer strictApiJson() {
        return builder -> builder.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .addModule(new SimpleModule().addDeserializer(UUID.class, new ValueDeserializer<UUID>() {
                    @Override
                    public UUID deserialize(JsonParser parser, DeserializationContext context) {
                        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                            throw InputValidationException.malformed("id");
                        }
                        return InputNormalizer.uuid(parser.getString(), "id");
                    }
                }))
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .enable(
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .withCoercionConfig(
                        LogicalType.Textual,
                        config -> config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
