package vn.cane.support.web;

import java.beans.PropertyEditorSupport;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;
import vn.cane.support.validation.InputNormalizer;

@ControllerAdvice
@Profile("api & !worker")
public final class ApiInputBinding {
    @InitBinder
    void canonicalUuids(WebDataBinder binder) {
        // A Converter alone can fall back to Spring's permissive default UUID editor.
        binder.registerCustomEditor(UUID.class, new PropertyEditorSupport() {
            @Override
            public void setAsText(String value) {
                setValue(InputNormalizer.uuid(value, "id"));
            }
        });
    }
}
