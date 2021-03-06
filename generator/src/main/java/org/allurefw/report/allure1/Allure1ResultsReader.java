package org.allurefw.report.allure1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.baev.BadXmlCharactersFilterReader;
import com.google.inject.Inject;
import org.allurefw.report.AttachmentsStorage;
import org.allurefw.report.ResultsReader;
import org.allurefw.report.entity.Attachment;
import org.allurefw.report.entity.Failure;
import org.allurefw.report.entity.Label;
import org.allurefw.report.entity.LabelName;
import org.allurefw.report.entity.Parameter;
import org.allurefw.report.entity.StageResult;
import org.allurefw.report.entity.Status;
import org.allurefw.report.entity.Step;
import org.allurefw.report.entity.TestCaseResult;
import org.allurefw.report.entity.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.yandex.qatools.allure.model.Description;
import ru.yandex.qatools.allure.model.DescriptionType;
import ru.yandex.qatools.allure.model.ParameterKind;
import ru.yandex.qatools.allure.model.TestSuiteResult;

import javax.xml.bind.JAXB;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.allurefw.allure1.AllureConstants.ATTACHMENTS_FILE_GLOB;
import static org.allurefw.allure1.AllureConstants.TEST_SUITE_JSON_FILE_GLOB;
import static org.allurefw.allure1.AllureConstants.TEST_SUITE_XML_FILE_GLOB;
import static org.allurefw.report.ReportApiUtils.generateUid;
import static org.allurefw.report.ReportApiUtils.listFiles;
import static org.allurefw.report.allure1.Allure1ModelConvertUtils.convertList;
import static org.allurefw.report.allure1.Allure1ModelConvertUtils.convertStatus;
import static org.allurefw.report.entity.Status.BROKEN;
import static org.allurefw.report.entity.Status.CANCELED;
import static org.allurefw.report.entity.Status.FAILED;
import static org.allurefw.report.entity.Status.PASSED;
import static org.allurefw.report.entity.Status.PENDING;
import static org.allurefw.report.utils.ListUtils.firstNonNull;

/**
 * @author charlie (Dmitry Baev).
 */
public class Allure1ResultsReader implements ResultsReader {

    private static final Logger LOGGER = LoggerFactory.getLogger(Allure1ResultsReader.class);

    private final AttachmentsStorage storage;

    private final ObjectMapper mapper;

    @Inject
    public Allure1ResultsReader(AttachmentsStorage storage) {
        this.storage = storage;
        this.mapper = new ObjectMapper();
    }

    @Override
    public List<TestCaseResult> readResults(Path source) {
        listFiles(source, ATTACHMENTS_FILE_GLOB)
                .forEach(storage::addAttachment);

        return getStreamOfAllure1Results(source)
                .flatMap(testSuite -> testSuite.getTestCases().stream()
                        .map(testCase -> convert(testSuite, testCase)))
                .collect(Collectors.toList());
    }

    private TestCaseResult convert(TestSuiteResult testSuite,
                                   ru.yandex.qatools.allure.model.TestCaseResult source) {
        TestCaseResult dest = new TestCaseResult();


        String suiteName = firstNonNull(testSuite.getTitle(), testSuite.getName(), "unknown test suite");
        String testClass = firstNonNull(testSuite.getName(), "unknown");
        String name = firstNonNull(source.getTitle(), source.getName(), "unknown test case");

        dest.setId(String.format("%s#%s", testClass, name));
        dest.setUid(generateUid());
        dest.setName(name);
        dest.setFullName(String.format("%s#%s", testSuite.getName(), source.getName()));

        dest.setStatus(convert(source.getStatus()));
        dest.setTime(source.getStart(), source.getStop());
        dest.setParameters(convert(source.getParameters(), this::hasArgumentType, this::convert));
        dest.setDescription(getDescription(source));
        dest.setDescriptionHtml(getDescriptionHtml(source));
        dest.setFailure(convert(source.getFailure()));

        if (!source.getSteps().isEmpty() || !source.getAttachments().isEmpty()) {
            StageResult testStage = new StageResult();
            testStage.setSteps(convertList(source.getSteps(), this::convert));
            testStage.setAttachments(convertList(source.getAttachments(), this::convert));
            testStage.setFailure(convert(source.getFailure()));
            dest.setTestStage(testStage);
        }

        Set<Label> set = new TreeSet<>(Comparator.comparing(Label::getName).thenComparing(Label::getValue));
        set.addAll(convert(testSuite.getLabels(), this::convert));
        set.addAll(convert(source.getLabels(), this::convert));
        dest.setLabels(set.stream().collect(Collectors.toList()));

        dest.addLabelIfNotExists(LabelName.SUITE, suiteName);
        dest.addLabelIfNotExists(LabelName.TEST_CLASS, testClass);

        return dest;
    }

    private String getDescription(ru.yandex.qatools.allure.model.TestCaseResult source) {
        return Optional.ofNullable(source.getDescription())
                .filter(description -> !DescriptionType.HTML.equals(description.getType()))
                .map(Description::getValue)
                .orElse(null);
    }

    private String getDescriptionHtml(ru.yandex.qatools.allure.model.TestCaseResult source) {
        return Optional.ofNullable(source.getDescription())
                .filter(description -> DescriptionType.HTML.equals(description.getType()))
                .map(Description::getValue)
                .orElse(null);
    }

    private <T, R> List<R> convert(Collection<T> source, Function<T, R> converter) {
        return convert(source, t -> true, converter);
    }

    private <T, R> List<R> convert(Collection<T> source, Predicate<T> predicate, Function<T, R> converter) {
        return Objects.isNull(source) ? null : source.stream()
                .filter(predicate)
                .map(converter)
                .collect(Collectors.toList());
    }

    private Step convert(ru.yandex.qatools.allure.model.Step s) {
        return new Step()
                .withName(s.getTitle() == null ? s.getName() : s.getTitle())
                .withTime(new Time()
                        .withStart(s.getStart())
                        .withStop(s.getStop())
                        .withDuration(s.getStop() - s.getStart()))
                .withStatus(convertStatus(s.getStatus()))
                .withSteps(convertList(s.getSteps(), this::convert))
                .withAttachments(convertList(s.getAttachments(), this::convert));
    }

    private Failure convert(ru.yandex.qatools.allure.model.Failure failure) {
        return Objects.isNull(failure) ? null : new Failure()
                .withMessage(failure.getMessage())
                .withTrace(failure.getStackTrace());
    }

    private Label convert(ru.yandex.qatools.allure.model.Label label) {
        return new Label()
                .withName(label.getName())
                .withValue(label.getValue());
    }

    private Parameter convert(ru.yandex.qatools.allure.model.Parameter parameter) {
        return new Parameter()
                .withName(parameter.getName())
                .withValue(parameter.getValue());
    }

    private Attachment convert(ru.yandex.qatools.allure.model.Attachment attachment) {
        return storage.findAttachmentByFileName(attachment.getSource())
                .map(attach -> Objects.isNull(attachment.getType()) ? attach : attach.withType(attachment.getType()))
                .map(attach -> Objects.isNull(attachment.getTitle()) ? attach : attach.withName(attachment.getTitle()))
                .orElseGet(Attachment::new);
    }

    public static Status convert(ru.yandex.qatools.allure.model.Status status) {
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

    private boolean hasArgumentType(ru.yandex.qatools.allure.model.Parameter parameter) {
        return ParameterKind.ARGUMENT.equals(parameter.getKind());
    }

    private Stream<TestSuiteResult> getStreamOfAllure1Results(Path source) {
        return Stream.concat(
                listFiles(source, TEST_SUITE_XML_FILE_GLOB).map(this::readXmlTestSuiteFile),
                listFiles(source, TEST_SUITE_JSON_FILE_GLOB).map(this::readJsonTestSuiteFile)
        ).filter(Optional::isPresent).map(Optional::get);
    }

    private Optional<TestSuiteResult> readXmlTestSuiteFile(Path source) {
        try (BadXmlCharactersFilterReader reader = new BadXmlCharactersFilterReader(source)) {
            return Optional.of(JAXB.unmarshal(reader, TestSuiteResult.class));
        } catch (IOException e) {
            LOGGER.debug("Could not read result {}: {}", source, e);
        }
        return Optional.empty();
    }

    private Optional<TestSuiteResult> readJsonTestSuiteFile(Path source) {
        try (InputStream is = Files.newInputStream(source)) {
            return Optional.of(mapper.readValue(is, TestSuiteResult.class));
        } catch (IOException e) {
            LOGGER.debug("Could not read result {}: {}", source, e);
            return Optional.empty();
        }
    }
}
