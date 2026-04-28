package in.codefarm.price.aggregator.config;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // Capture MDC context from the submitting (parent) thread
        Map<String, String> parentMdcContext = MDC.getCopyOfContextMap();

        return () -> {
            // Preserve any existing MDC context in the child thread (for reuse safety)
            Map<String, String> originalChildMdcContext = MDC.getCopyOfContextMap();
            try {
                // Restore parent MDC context in the child thread
                if (parentMdcContext != null) {
                    MDC.setContextMap(parentMdcContext);
                } else {
                    MDC.clear();
                }
                // Execute the original task
                runnable.run();
            } finally {
                // Restore original child MDC context to prevent leaks
                if (originalChildMdcContext != null) {
                    MDC.setContextMap(originalChildMdcContext);
                } else {
                    MDC.clear();
                }
            }
        };
    }
}
