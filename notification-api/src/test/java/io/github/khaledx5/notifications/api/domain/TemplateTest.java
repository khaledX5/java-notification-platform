package io.github.khaledx5.notifications.api.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class TemplateTest {

    private static final Template ORDER_SHIPPED = new Template(
            "order-shipped", "Order {{orderId}} shipped", "Hi {{ name }}, order {{orderId}} is on its way.");

    @Test
    void fillsInEveryPlaceholder() {
        RenderedTemplate rendered = ORDER_SHIPPED.render(Map.of("name", "Dina", "orderId", "A-1042"));

        assertThat(rendered.subject()).isEqualTo("Order A-1042 shipped");
        assertThat(rendered.body()).isEqualTo("Hi Dina, order A-1042 is on its way.");
    }

    @Test
    void listsPlaceholdersInOrderOfFirstUse() {
        assertThat(ORDER_SHIPPED.placeholders()).containsExactly("orderId", "name");
    }

    @Test
    void valuesAreInsertedLiterally() {
        Template template = new Template("price-drop", null, "Now {{price}} for {{item}}");

        RenderedTemplate rendered = template.render(Map.of("price", "$1 \\ {{item}}", "item", "a kettle"));

        assertThat(rendered.body()).isEqualTo("Now $1 \\ {{item}} for a kettle");
    }

    @Test
    void ignoresVariablesTheTemplateDoesNotUse() {
        Template template = new Template("login-code", null, "Code {{code}}");

        assertThat(template.render(Map.of("code", "4821", "unused", "x")).body())
                .isEqualTo("Code 4821");
    }

    @Test
    void templateWithoutPlaceholdersRendersAsIs() {
        Template template = new Template("maintenance", "Planned maintenance", "We will be back at 02:00 UTC.");

        RenderedTemplate rendered = template.render(Map.of());

        assertThat(template.placeholders()).isEmpty();
        assertThat(rendered.subject()).isEqualTo("Planned maintenance");
        assertThat(rendered.body()).isEqualTo("We will be back at 02:00 UTC.");
    }

    @Test
    void reportsEveryMissingVariableAtOnce() {
        assertThatThrownBy(() -> ORDER_SHIPPED.render(Map.of()))
                .isInstanceOfSatisfying(MissingTemplateVariableException.class, ex -> {
                    assertThat(ex.templateKey()).isEqualTo("order-shipped");
                    assertThat(ex.missingVariables()).containsExactly("orderId", "name");
                })
                .hasMessage("Template 'order-shipped' is missing values for: orderId, name");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void subjectIsOptional(String subject) {
        Template template = new Template("login-code", subject, "Code {{code}}");

        assertThat(template.subject()).isNull();
        assertThat(template.render(Map.of("code", "4821")).subject()).isNull();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
            strings = {"", "Order-Shipped", "order_shipped", "order shipped", "-order", "order-", "order--shipped"})
    void rejectsKeysThatAreNotLowercaseHyphenatedWords(String key) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Template(key, null, "Body"));
    }

    @Test
    void rejectsAKeyThatIsTooLong() {
        String key = "a".repeat(Template.MAX_KEY_LENGTH + 1);

        assertThatIllegalArgumentException().isThrownBy(() -> new Template(key, null, "Body"));
        assertThat(new Template("a".repeat(Template.MAX_KEY_LENGTH), null, "Body").key())
                .hasSize(Template.MAX_KEY_LENGTH);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void rejectsABlankBody(String body) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Template("login-code", null, body))
                .withMessage("body must not be blank");
    }
}
