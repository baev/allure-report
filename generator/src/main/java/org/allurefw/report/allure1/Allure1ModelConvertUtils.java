package org.allurefw.report.allure1;

import org.allurefw.report.entity.Status;
import org.allurefw.report.entity.WithDescription;
import org.allurefw.report.entity.WithFailure;
import org.allurefw.report.entity.WithLabels;
import ru.yandex.qatools.allure.model.Description;
import ru.yandex.qatools.allure.model.DescriptionType;
import ru.yandex.qatools.allure.model.Failure;
import ru.yandex.qatools.allure.model.Label;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.allurefw.report.ModelUtils.createLabel;
import static org.allurefw.report.entity.Status.BROKEN;
import static org.allurefw.report.entity.Status.CANCELED;
import static org.allurefw.report.entity.Status.FAILED;
import static org.allurefw.report.entity.Status.PASSED;
import static org.allurefw.report.entity.Status.PENDING;

/**
 * Collection of useful util methods to convert Allure 1.0 Model beans to 2.0 version.
 *
 * @author charlie (Dmitry Baev).
 * @since 2.0
 */
public final class Allure1ModelConvertUtils {

    public static <T, R> List<R> convertList(List<T> t, Function<T, R> convertFunction) {
        return t.stream()
                .map(convertFunction)
                .collect(Collectors.toList());
    }

    public static void convertLabels(WithLabels dest, List<Label> labels) {
        if (Objects.nonNull(labels)) {
            dest.setLabels(labels.stream()
                    .map(label -> createLabel(label.getName(), label.getValue()))
                    .collect(Collectors.toList())
            );
        }
    }

    public static void convertFailure(WithFailure dest, Failure source) {
        if (Objects.nonNull(source)) {
            dest.setFailure(
                    source.getMessage(),
                    source.getStackTrace()
            );
        }
    }

    public static void convertDescription(WithDescription dest, Description source) {
        if (Objects.nonNull(source)) {
            if (DescriptionType.HTML.equals(source.getType())) {
                dest.setDescriptionHtml(source.getValue());
            } else {
                dest.setDescription(source.getValue());
            }
        }
    }

    public static Status convertStatus(ru.yandex.qatools.allure.model.Status status) {
        switch (status) {
            case FAILED:
                return FAILED;
            case BROKEN:
                return BROKEN;
            case PASSED:
                return PASSED;
            case CANCELED:
            case SKIPPED:
                return CANCELED;
            default:
                return PENDING;
        }
    }
}
