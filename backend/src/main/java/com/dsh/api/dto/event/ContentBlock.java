package com.dsh.api.dto.event;

/** events.schema.json 中 ToolOutcome.content 的 ContentBlock（oneOf text/artifact）。 */
public sealed interface ContentBlock permits ContentBlock.TextBlock, ContentBlock.ArtifactBlock {

    record TextBlock(String type, String text) implements ContentBlock {
        public TextBlock(String text) {
            this("text", text);
        }
    }

    record ArtifactBlock(String type, String artifactId, String mimeType, String description,
                         boolean unsupported) implements ContentBlock {
    }
}
