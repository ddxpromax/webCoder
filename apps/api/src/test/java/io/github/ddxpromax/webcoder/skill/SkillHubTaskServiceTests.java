package io.github.ddxpromax.webcoder.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SkillHubTaskServiceTests {
    @Test
    void cancelsPendingTaskAndPreservesTerminalState() {
        var service = new SkillHubTaskService();
        var task = service.create("https://example.test/bundle", "1", true);

        assertThat(service.cancel(task.taskId())).isEqualTo("cancelled");
        assertThat(service.cancellation(task.taskId()).get()).isTrue();
        assertThat(service.cancel(task.taskId())).isEqualTo("cancelled");
    }

    @Test
    void completionWinsOverLaterCancellation() {
        var service = new SkillHubTaskService();
        var task = service.create("https://example.test/bundle", "1", false);

        service.importing(task.taskId());
        service.finish(task.taskId(), "completed", null, Map.of("count", 1));

        assertThat(service.get(task.taskId()).status()).isEqualTo("completed");
        assertThat(service.cancel(task.taskId())).isEqualTo("completed");
    }

    @Test
    void rejectsNonTerminalFinishStatus() {
        var service = new SkillHubTaskService();
        var task = service.create("https://example.test/bundle", "", true);

        assertThatThrownBy(() -> service.finish(
            task.taskId(), "importing", null, null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cancellationCannotBeOverwrittenByCompletion() {
        var service = new SkillHubTaskService();
        var task = service.create("https://example.test/bundle", "", true);

        service.cancel(task.taskId());
        service.finish(task.taskId(), "completed", null, Map.of("count", 1));

        assertThat(service.get(task.taskId()).status()).isEqualTo("cancelled");
        service.finish(task.taskId(), "cancelled", null, Map.of("count", 0));
        assertThat(service.get(task.taskId()).result()).containsEntry("count", 0);
    }
}
