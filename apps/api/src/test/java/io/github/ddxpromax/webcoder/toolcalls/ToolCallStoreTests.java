package io.github.ddxpromax.webcoder.toolcalls;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class ToolCallStoreTests {
    @Test void managesRunningCallLifecycle(){ToolCallStore store=new ToolCallStore();store.register("call-1","shell","session-1","default");assertThat(store.extendDeadline("call-1",60.0,false)).isTrue();assertThat(store.info(store.get("call-1")).get("deadline")).isNotNull();assertThat(store.offload("call-1")).isTrue();assertThat(store.cancel("call-1",true)).isFalse();store.register("call-2","read_file","session-1","default");store.finish("call-2","success","done");assertThat(store.get("call-2").output).isEqualTo("done");}
}
