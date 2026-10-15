package com.materia.grpc.convert;

import com.materia.backend.common.domain.enums.CurrencyCode;
import com.materia.backend.common.domain.valueObjects.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.UUID;

/**
 * The value conventions of the contracts, in one place: ids are UUID strings, amounts decimal strings, dates ISO
 * strings, and an absent value is an unset field (proto3 {@code optional}) rather than an empty string.
 */
public final class ProtoValues {

    private ProtoValues() {
    }

    public static String text(UUID id) {
        return id == null ? null : id.toString();
    }

    public static UUID uuid(String id) {
        return id == null || id.isBlank() ? null : UUID.fromString(id);
    }

    public static String text(BigDecimal amount) {
        return amount == null ? null : amount.toPlainString();
    }

    public static BigDecimal decimal(String amount) {
        return amount == null || amount.isBlank() ? null : new BigDecimal(amount);
    }

    /** Zero for an amount the caller always expects (sums, totals). */
    public static BigDecimal decimalOrZero(String amount) {
        BigDecimal value = decimal(amount);
        return value == null ? BigDecimal.ZERO : value;
    }

    public static String text(LocalDate date) {
        return date == null ? null : date.toString();
    }

    public static LocalDate date(String date) {
        return date == null || date.isBlank() ? null : LocalDate.parse(date);
    }

    public static String text(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.toString();
    }

    public static LocalDateTime dateTime(String dateTime) {
        return dateTime == null || dateTime.isBlank() ? null : LocalDateTime.parse(dateTime);
    }

    public static String text(YearMonth month) {
        return month == null ? null : month.toString();
    }

    public static YearMonth yearMonth(String month) {
        return month == null || month.isBlank() ? null : YearMonth.parse(month);
    }

    public static com.materia.proto.common.v1.Money money(Money money) {
        return com.materia.proto.common.v1.Money.newBuilder()
                .setAmount(money.getAmount().toPlainString())
                .setCurrency(money.getCurrency().getCode())
                .build();
    }

    public static Money money(com.materia.proto.common.v1.Money money) {
        return Money.of(new BigDecimal(money.getAmount()), CurrencyCode.fromCode(money.getCurrency()));
    }
}
