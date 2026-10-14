package org.openbased.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openbased.common.ApiException;
import org.openbased.media.MediaLookup;
import org.openbased.security.AccessService;
import org.openbased.user.User;

class ProgressControllerTest {

    @Test
    void anUnexpectedErrorFailsOnlyItsOwnBatchItem() {
        ProgressService service = mock(ProgressService.class);
        AccessService access = mock(AccessService.class);
        User user = new User();
        user.setId("user_1");
        when(access.user()).thenReturn(user);
        Instant now = Instant.now();
        when(service.record(eq(user), eq("media_ok"), any()))
                .thenReturn(new ProgressService.Result("media_ok", 10, false, now, true));
        when(service.record(eq(user), eq("media_db_error"), any()))
                .thenThrow(new IllegalStateException("database unavailable"));
        when(service.record(eq(user), eq("media_missing"), any()))
                .thenThrow(ApiException.notFound("MEDIA_NOT_FOUND", "The requested media item does not exist."));
        ProgressController controller = new ProgressController(mock(PlaybackProgressRepository.class),
                mock(WatchHistoryRepository.class), service, mock(MediaLookup.class), access);

        ProgressController.BatchResponse response = controller.batch(new ProgressController.BatchRequest(List.of(
                new ProgressController.BatchItem("media_ok", 10.0, null, null, null),
                new ProgressController.BatchItem("media_db_error", 20.0, null, null, null),
                new ProgressController.BatchItem("media_missing", 30.0, null, null, null))));

        assertThat(response.results()).extracting(ProgressController.BatchResult::status).containsExactly(
                ProgressController.BatchStatus.APPLIED, ProgressController.BatchStatus.FAILED,
                ProgressController.BatchStatus.FAILED);
        assertThat(response.results().get(1).error()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.results().get(1).message()).doesNotContain("database");
        assertThat(response.results().get(2).error()).isEqualTo("MEDIA_NOT_FOUND");
    }
}
