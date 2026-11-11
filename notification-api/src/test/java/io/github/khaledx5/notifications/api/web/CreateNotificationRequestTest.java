package io.github.khaledx5.notifications.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.khaledx5.notifications.api.domain.Channel;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CreateNotificationRequestTest {

    private static final CreateNotificationRequest EMAIL = new CreateNotificationRequest(
            "dina@example.com", Channel.EMAIL, "Order shipped", "Your order is on its way", null, null);

    @Test
    void requestsWithTheSameContentHaveTheSameFingerprint() {
        CreateNotificationRequest sameContent = new CreateNotificationRequest(
                "dina@example.com", Channel.EMAIL, "Order shipped", "Your order is on its way", null, null);

        assertThat(sameContent.fingerprint()).isEqualTo(EMAIL.fingerprint());
    }

    @Test
    void fingerprintIsASha256InHex() {
        assertThat(EMAIL.fingerprint()).matches("[0-9a-f]{64}");
    }

    @ParameterizedTest
    @MethodSource("requestsThatDifferFromTheEmailInOneField")
    void changingAnyFieldChangesTheFingerprint(CreateNotificationRequest different) {
        assertThat(different.fingerprint()).isNotEqualTo(EMAIL.fingerprint());
    }

    static Stream<CreateNotificationRequest> requestsThatDifferFromTheEmailInOneField() {
        return Stream.of(
                new CreateNotificationRequest(
                        "omar@example.com", Channel.EMAIL, "Order shipped", "Your order is on its way", null, null),
                new CreateNotificationRequest(
                        "dina@example.com", Channel.PUSH, "Order shipped", "Your order is on its way", null, null),
                new CreateNotificationRequest(
                        "dina@example.com", Channel.EMAIL, "Order delayed", "Your order is on its way", null, null),
                new CreateNotificationRequest(
                        "dina@example.com", Channel.EMAIL, "Order shipped", "Your order is late", null, null),
                new CreateNotificationRequest(
                        "dina@example.com", Channel.EMAIL, null, "Your order is on its way", null, null));
    }

    @Test
    void orderOfTheVariablesDoesNotChangeTheFingerprint() {
        Map<String, String> orderIdFirst = new LinkedHashMap<>();
        orderIdFirst.put("orderId", "A-1042");
        orderIdFirst.put("name", "Dina");
        Map<String, String> nameFirst = new LinkedHashMap<>();
        nameFirst.put("name", "Dina");
        nameFirst.put("orderId", "A-1042");

        assertThat(fromTemplate(orderIdFirst).fingerprint())
                .isEqualTo(fromTemplate(nameFirst).fingerprint());
    }

    @Test
    void differentVariableValuesGiveDifferentFingerprints() {
        assertThat(fromTemplate(Map.of("orderId", "A-1042")).fingerprint())
                .isNotEqualTo(fromTemplate(Map.of("orderId", "A-1043")).fingerprint());
    }

    @Test
    void textThatMovesFromOneFieldToTheNextGivesADifferentFingerprint() {
        assertThat(RequestFingerprint.of(List.of("ab", "c"))).isNotEqualTo(RequestFingerprint.of(List.of("a", "bc")));
    }

    @Test
    void missingFieldDiffersFromAnEmptyOne() {
        assertThat(RequestFingerprint.of(Arrays.asList("a", null)))
                .isNotEqualTo(RequestFingerprint.of(List.of("a", "")));
    }

    private static CreateNotificationRequest fromTemplate(Map<String, String> variables) {
        return new CreateNotificationRequest("dina@example.com", Channel.EMAIL, null, null, "order-shipped", variables);
    }
}
